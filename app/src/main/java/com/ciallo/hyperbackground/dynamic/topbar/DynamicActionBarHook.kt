package com.ciallo.hyperbackground.dynamic.topbar

import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import com.ciallo.hyperbackground.BackgroundContract
import com.ciallo.hyperbackground.HookRuntime
import com.ciallo.hyperbackground.appearance.ComponentKeys
import com.ciallo.hyperbackground.appearance.KEY_APP_COMPONENT_DISABLED
import com.ciallo.hyperbackground.appearance.KEY_APP_SCOPE_DISABLED
import com.ciallo.hyperbackground.appearance.SETTINGS_APPEARANCE_PREFERENCES
import com.ciallo.hyperbackground.appearance.CARD_BACKGROUND_FROST
import com.ciallo.hyperbackground.appearance.CARD_BACKGROUND_SOFT_GLASS
import com.ciallo.hyperbackground.appearance.KEY_CARD_BACKGROUND_MODE
import com.ciallo.hyperbackground.appearance.KEY_CARD_DARK_FOLLOWS_LIGHT
import com.ciallo.hyperbackground.appearance.KEY_COMPONENT_TOP_BAR_BUTTON
import com.ciallo.hyperbackground.appearance.KEY_CUSTOM_CARD_ENABLED
import com.ciallo.hyperbackground.appearance.KEY_DARK_CARD_BLUR
import com.ciallo.hyperbackground.appearance.KEY_DARK_CARD_COLOR
import com.ciallo.hyperbackground.appearance.KEY_DARK_FROST_COLOR
import com.ciallo.hyperbackground.appearance.KEY_DARK_SOFT_GLASS
import com.ciallo.hyperbackground.appearance.KEY_LIGHT_CARD_BLUR
import com.ciallo.hyperbackground.appearance.KEY_LIGHT_CARD_COLOR
import com.ciallo.hyperbackground.appearance.KEY_LIGHT_FROST_COLOR
import com.ciallo.hyperbackground.appearance.KEY_LIGHT_SOFT_GLASS
import com.ciallo.hyperbackground.dynamic.material.DynamicFrostDrawable
import com.ciallo.hyperbackground.dynamic.material.DynamicMaterialPalette
import com.ciallo.hyperbackground.dynamic.material.DynamicSoftGlassDrawable
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.min

/** MIUIX ActionBarContainer only. Its R8-obfuscated mask painter and animation field are resolved from dex. */
internal object DynamicActionBarHook {
    private const val TAG = "HyperBackgroundTopBar"
    private const val BAR = "miuix.appcompat.internal.app.widget.ActionBarContainer"

    // 顶栏按钮「浮动态」控制位。MIUIX 里它是 ActionBarView.mFloatingMode 的公开写入口：
    // 1 = 按钮常显胶囊底（原厂只在列表下拉、遮罩出现时才置 1），-1 = 交还给原厂自动判定。
    // 该方法是 MIUIX 公开 API，短信 / 设置两个 APK 都保留了名字，不需要 dex 反混淆。
    private const val FLOATING_ON = 1
    private const val FLOATING_AUTO = -1

    private val owned = Collections.synchronizedMap(WeakHashMap<ViewGroup, State>())
    private data class State(val blur: View, var original: Drawable? = null, var cleared: Boolean = false,
                             var lastRadius: Float = -1f, var lastHeight: Int = -1, var lastAlpha: Float = 0f)

    /** 每个 ActionBarContainer 的按钮浮动钉住状态；failed 后不再重试，避免每帧刷日志。 */
    private class FloatingPin { var applied: Boolean? = null; var failed = false }
    private val floatingPins = Collections.synchronizedMap(WeakHashMap<ViewGroup, FloatingPin>())
    private data class ButtonSurface(
        val original: Drawable, val replacement: Drawable, val signature: Int,
        var materialApplied: Boolean = false,
    )
    private val buttons = Collections.synchronizedMap(WeakHashMap<View, ButtonSurface>())
    private val bars = Collections.synchronizedMap(WeakHashMap<ViewGroup, Unit>())
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var palette = DynamicMaterialPalette()
    private val materialKeys = setOf(
        KEY_CUSTOM_CARD_ENABLED, KEY_COMPONENT_TOP_BAR_BUTTON, KEY_CARD_BACKGROUND_MODE,
        KEY_CARD_DARK_FOLLOWS_LIGHT, KEY_LIGHT_CARD_COLOR, KEY_DARK_CARD_COLOR,
        KEY_LIGHT_FROST_COLOR, KEY_DARK_FROST_COLOR, KEY_LIGHT_CARD_BLUR, KEY_DARK_CARD_BLUR,
        KEY_LIGHT_SOFT_GLASS, KEY_DARK_SOFT_GLASS, KEY_APP_SCOPE_DISABLED, KEY_APP_COMPONENT_DISABLED,
    )
    private val materialListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key == null || key in materialKeys) {
            palette = DynamicMaterialPalette.read(prefs)
            main.post {
                synchronized(bars) { bars.keys.toList() }.forEach { bar ->
                    syncButtonBackground(bar)
                    syncButtons(bar)
                }
            }
        }
    }

    private lateinit var module: XposedModule
    private var maskAlpha: Field? = null
    private var setType: Method? = null
    private var setMode: Method? = null
    private var setViewMode: Method? = null
    private var setGradient: Method? = null
    private var setPrimary: Method? = null
    private var getPrimary: Method? = null
    private var setButtonFloating: Method? = null
    private var scope: SharedPreferences? = null

    fun install(value: XposedModule, loader: ClassLoader) {
        val type = runCatching { loader.loadClass(BAR) }.getOrNull() ?: return
        module = value
        setType = View::class.java.getMethod("setMiBackgroundBlurType", Int::class.javaPrimitiveType)
        setMode = View::class.java.getMethod("setMiBackgroundBlurMode", Int::class.javaPrimitiveType)
        setViewMode = View::class.java.getMethod("setMiViewBlurMode", Int::class.javaPrimitiveType)
        setGradient = View::class.java.getMethod("setBackgroundGradientBlurParams", FloatArray::class.java, Int::class.javaPrimitiveType)
        getPrimary = type.getMethod("getPrimaryBackground")
        setPrimary = type.getMethod("setPrimaryBackground", Drawable::class.java)
        scope = HookRuntime.remotePreferences(SETTINGS_APPEARANCE_PREFERENCES)
        scope?.registerOnSharedPreferenceChangeListener(materialListener)
        scope?.let { palette = DynamicMaterialPalette.read(it) }
        setButtonFloating = runCatching {
            type.getMethod("setActionButtonFloatingState", Int::class.javaPrimitiveType)
        }.getOrNull()

        // 「顶栏按钮背景常驻」与顶栏遮罩是两条互不依赖的通道：即使遮罩反混淆失败（非 MIUIX
        // 布局 / 结构变化），按钮浮动这一条也必须照常生效，所以先装它，再装遮罩相关的 hook。
        hookButtonFloating()
        // onFinishInflate / onLayout 同时服务两个功能：遮罩层尺寸跟随 + 按钮浮动状态钉住。
        hookLifecycle(type)

        // Scan only the class we will hook. Both the painter and the alpha field are discovered
        // through framework calls; do not rely on JADX's R8-renamed method/field identifiers.
        val painter = runCatching { discoverMask(type, loader) }
            .onFailure { module.log(Log.WARN, TAG, "Action bar mask discovery failed", it) }
            .getOrNull()
        if (painter == null || maskAlpha == null) {
            module.log(Log.WARN, TAG, "Action bar mask is ambiguous; leaving native bar intact")
            return
        }

        val painterIsOnDraw = painter.name == "onDraw" && painter.parameterTypes.contentEquals(arrayOf(Canvas::class.java))
        type.getDeclaredMethod("onDraw", Canvas::class.java).apply { isAccessible = true }.let { method ->
            module.hook(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("dynamic-topbar:draw").intercept { chain ->
                    val bar = chain.thisObject as? ViewGroup
                    bar?.takeIf { owned.containsKey(it) }?.let(::update)
                    // Some MIUIX builds inline the mask painter into onDraw. Keep the rest of
                    // their drawing intact and suppress only the mask's animated alpha here.
                    if (painterIsOnDraw && bar != null && enabled() && owned.containsKey(bar)) {
                        val alpha = maskAlpha!!
                        val original = alpha.getFloat(bar)
                        try {
                            alpha.setFloat(bar, 0f)
                            chain.proceed()
                        } finally {
                            alpha.setFloat(bar, original)
                        }
                    } else {
                        chain.proceed()
                    }
                }
        }
        if (!painterIsOnDraw) {
            module.hook(painter).setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("dynamic-topbar:mask").intercept { chain ->
                    val bar = chain.thisObject as? ViewGroup
                    if (bar != null && enabled() && owned.containsKey(bar)) null else chain.proceed()
                }
        }
        module.log(Log.INFO, TAG, "Action bar installed mask=${painter.name} alpha=${maskAlpha?.name} inline=$painterIsOnDraw")
    }

    /**
     * 顶栏按钮背景常驻。原厂只有列表下拉、顶栏遮罩出现时才会把按钮切到浮动（胶囊材质底），
     * 这里把 ActionBarContainer 的按钮浮动状态钉死为 1：
     * - 拦截 `setActionButtonFloatingState(int)` 本身，应用后续任何一次写状态都被改写成 1；
     * - 钉住后原厂内部 `mActionButtonFloatingState != -1` 的分支会自动跳过自动判定，
     *   所以滚动状态变化不会再把它改回 0。
     */
    private fun hookButtonFloating() {
        val method = setButtonFloating
        if (method == null) {
            module.log(Log.WARN, TAG, "ActionButtonFloatingState unavailable; keeping native button state")
            return
        }
        module.hook(method).setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("dynamic-topbar:button-floating").intercept { chain ->
                val result = if (buttonBackgroundEnabled()) {
                    // 参数是 int，必须按 Array<Any?> 装箱传入，直接用 arrayOf(1) 会因数组不变型编译失败。
                    chain.proceed(arrayOf<Any?>(FLOATING_ON))
                } else {
                    chain.proceed()
                }
                (chain.thisObject as? ViewGroup)?.let(::syncButtons)
                result
            }
        module.log(Log.INFO, TAG, "Top bar button background hook installed")
    }

    private fun hookLifecycle(type: Class<*>) {
        type.getDeclaredMethod("onFinishInflate").apply { isAccessible = true }.let { method ->
            module.hook(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("dynamic-topbar:inflate").intercept { chain ->
                    val result = chain.proceed()
                    (chain.thisObject as? ViewGroup)?.let { bar ->
                        bars[bar] = Unit
                        // 遮罩层只在反混淆成功后才有意义，避免给非 MIUIX 结构多挂一个子 View。
                        if (maskAlpha != null) ensure(bar)
                        syncButtonBackground(bar)
                        syncButtons(bar)
                    }
                    result
                }
        }
        type.getDeclaredMethod("onLayout", Boolean::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).apply { isAccessible = true }
            .let { method ->
                module.hook(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("dynamic-topbar:layout").intercept { chain ->
                        val result = chain.proceed()
                        (chain.thisObject as? ViewGroup)?.let { bar ->
                            owned[bar]?.blur?.layout(0, 0, bar.width, bar.height)
                            syncButtonBackground(bar)
                            syncButtons(bar)
                        }
                        result
                    }
            }
    }

    /**
     * 按当前开关把按钮浮动状态推到 [bar]。开关切换后下一帧布局即生效：开启 → 钉 1，
     * 关闭 → 交还 -1 让原厂重新接管（原厂会在下次展开/滚动事件里把按钮收回非浮动）。
     */
    private fun syncButtonBackground(bar: ViewGroup) {
        val method = setButtonFloating ?: return
        val pin = synchronized(floatingPins) {
            floatingPins[bar] ?: FloatingPin().also { floatingPins[bar] = it }
        }
        if (pin.failed) return
        val wanted = buttonBackgroundEnabled()
        if (pin.applied == wanted || pin.applied == null && !wanted) return
        try {
            method.invoke(bar, if (wanted) FLOATING_ON else FLOATING_AUTO)
            pin.applied = wanted
        } catch (error: Throwable) {
            pin.failed = true
            module.log(Log.WARN, TAG, "Button floating state unavailable on ${bar.javaClass.name}", error)
        }
    }

    private fun discoverMask(type: Class<*>, loader: ClassLoader): Method? {
        System.loadLibrary("dexkit")
        DexKitBridge.create(loader, false).use { bridge ->
            val data = bridge.getClassData(type) ?: return null
            val painters = data.methods.filter { method ->
                method.returnTypeName == "void" && method.paramTypeNames == listOf("android.graphics.Canvas") &&
                    method.invokes.any { it.className == "android.graphics.Canvas" && it.name == "drawPath" } &&
                    method.invokes.any { it.className == BAR && it.name == "getCollapsedHeight" }
            }
            if (painters.size != 1) return null
            val painter = painters.single()
            val fields = painter.usingFields.map { it.field }.filter { field ->
                field.className == BAR && field.typeName == "float" &&
                    field.writers.any { writer ->
                        writer.invokes.any {
                            it.className == "android.animation.ValueAnimator" && it.name == "getAnimatedValue"
                        }
                    }
            }.distinctBy { it.descriptor }
            if (fields.size != 1) return null
            maskAlpha = fields.single().getFieldInstance(loader).apply { isAccessible = true }
            return painter.getMethodInstance(loader).apply { isAccessible = true }
        }
    }

    /** 软件作用域：被用户排除的包不接管（与其它动态通道一致）。 */
    private fun scoped(): Boolean {
        val disabled = scope?.getStringSet(KEY_APP_SCOPE_DISABLED, emptySet())
        return HookRuntime.targetPackage !in (disabled ?: emptySet())
    }

    private fun enabled(): Boolean =
        scoped() && (HookRuntime.preferences().getBoolean(BackgroundContract.UI_TOP_BLUR_ENABLED, true) ||
            HookRuntime.preferences().getBoolean(BackgroundContract.UI_TOP_CLEAR_ENABLED, false))

    /** 顶栏按钮背景常驻只在顶栏按钮组件启用时生效（含软件作用域的整包/按组件开关）。 */
    private fun buttonBackgroundEnabled(): Boolean =
        palette.enabledFor(HookRuntime.targetPackage, ComponentKeys.TOP_BAR_BUTTON) &&
            HookRuntime.preferences()
                .getBoolean(BackgroundContract.UI_TOP_BUTTON_BACKGROUND_ENABLED, false)

    /** Only MIUIX's floating button surfaces inside this ActionBarContainer are replaced. */
    private fun syncButtons(bar: ViewGroup) {
        val config = palette
        val active = config.enabledFor(HookRuntime.targetPackage, ComponentKeys.TOP_BAR_BUTTON)
        fun visit(view: View) {
            if (view !== bar && isTopBarButton(view)) {
                applyButton(view, config, active)
                return
            }
            if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
        }
        visit(bar)
    }

    private fun isTopBarButton(view: View): Boolean {
        val name = view.javaClass.name
        if (name == "miuix.appcompat.internal.view.menu.action.EndActionMenuItemView") return true
        // MIUIX draws floating backgrounds on the icon ImageViews in the action bar's
        // HomeView / ActionBarView / ActionBarContextView, but not on unrelated nested icons.
        return view is android.widget.ImageView && (view.parent as? View)?.javaClass?.name in setOf(
            "miuix.appcompat.internal.app.widget.ActionBarView\$HomeView",
            "miuix.appcompat.internal.app.widget.ActionBarView",
            "miuix.appcompat.internal.app.widget.ActionBarContextView",
        )
    }

    private fun applyButton(view: View, config: DynamicMaterialPalette, active: Boolean) {
        val previous = buttons[view]
        val current = view.background
        if (!active || current == null) {
            if (previous != null) restoreButton(view, previous)
            return
        }
        val signature = 31 * config.hashCode() +
            (view.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
        if (previous != null && current === previous.replacement && previous.signature == signature) {
            if (!previous.materialApplied && view.isAttachedToWindow) {
                previous.materialApplied = applyButtonMaterial(view, config)
            }
            return
        }
        if (previous != null) restoreButton(view, previous)
        val original = if (current === previous?.replacement) previous.original else current
        val dark = !config.darkFollowsLight && view.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val tint = when (config.mode) {
            CARD_BACKGROUND_FROST, CARD_BACKGROUND_SOFT_GLASS ->
                if (dark) config.darkFrost else config.lightFrost
            else -> if (dark) config.dark else config.light
        }
        val replacement = runCatching {
            if (original.javaClass.name == "miuix.appcompat.internal.graphics.drawable.RoundStateDrawable") {
                val radius = original.javaClass.getMethod("getCornerRadius").invoke(original) as Float
                original.javaClass.getConstructor(Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
                    Float::class.javaPrimitiveType).newInstance(tint, dark, radius) as Drawable
            } else original.constantState?.newDrawable(view.resources, view.context.theme)?.mutate()
        }.getOrNull() ?: return
        val prepared = runCatching { if (config.mode == CARD_BACKGROUND_SOFT_GLASS) {
            // The shader owns both refraction and transparency; keep the native rounded outline.
            if (replacement.javaClass.name == "miuix.smooth.SmoothContainerDrawable2") {
                replacement.javaClass.getMethod("setChildDrawable", Drawable::class.java)
                    .invoke(replacement, android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            } else if (replacement.javaClass.name == "miuix.appcompat.internal.graphics.drawable.RoundStateDrawable") {
                replacement.javaClass.getMethod("setColor", Int::class.javaPrimitiveType)
                    .invoke(replacement, Color.TRANSPARENT)
            } else replacement.setColorFilter(Color.TRANSPARENT, PorterDuff.Mode.SRC_IN)
        } else if (replacement.javaClass.name == "miuix.appcompat.internal.graphics.drawable.RoundStateDrawable") {
            replacement.javaClass.getMethod("setColor", Int::class.javaPrimitiveType).invoke(replacement, tint)
        } else replacement.setColorFilter(tint, PorterDuff.Mode.SRC_IN)
        }.isSuccess
        if (!prepared) return
        view.background = replacement
        val surface = ButtonSurface(original, replacement, signature)
        buttons[view] = surface
        surface.materialApplied = applyButtonMaterial(view, config)
    }

    private fun applyButtonMaterial(view: View, config: DynamicMaterialPalette): Boolean {
        if (!view.isAttachedToWindow || !view.isHardwareAccelerated) return false
        val dark = !config.darkFollowsLight && view.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val density = view.resources.displayMetrics.density
        return when (config.mode) {
            CARD_BACKGROUND_SOFT_GLASS -> DynamicSoftGlassDrawable.applyToView(
                view, if (dark) config.darkFrost else config.lightFrost,
                if (dark) config.darkGlass else config.lightGlass, density,
            )
            CARD_BACKGROUND_FROST -> DynamicFrostDrawable.applyToView(
                view, if (dark) config.darkBlur else config.lightBlur, density,
            )
            else -> true
        }
    }

    private fun restoreButton(view: View, surface: ButtonSurface) {
        DynamicSoftGlassDrawable.clearFromView(view)
        DynamicFrostDrawable.clearFromView(view)
        if (view.background === surface.replacement) view.background = surface.original
        buttons.remove(view)
    }

    private fun ensure(bar: ViewGroup): State {
        owned[bar]?.let { return it }
        val child = View(bar.context).apply {
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            visibility = View.INVISIBLE
        }
        bar.addView(child, 0, ViewGroup.LayoutParams(-1, -1))
        return State(child).also { owned[bar] = it }
    }

    private fun update(bar: ViewGroup) {
        val state = ensure(bar)
        if (!enabled()) {
            if (state.cleared) {
                if (getPrimary?.invoke(bar) == null) setPrimary?.invoke(bar, state.original)
                state.cleared = false
                state.original = null
            }
            hide(state)
            return
        }
        // Do not remove the native fill unless its mask painter has been identified too.
        if (maskAlpha == null) return
        if (!state.cleared) {
            state.original = getPrimary?.invoke(bar) as? Drawable
            state.cleared = true
        }
        if (getPrimary?.invoke(bar) != null) setPrimary?.invoke(bar, null)

        val clear = HookRuntime.preferences().getBoolean(BackgroundContract.UI_TOP_CLEAR_ENABLED, false)
        val strength = HookRuntime.preferences().getInt(BackgroundContract.UI_TOP_BLUR_STRENGTH, 10).coerceIn(0, 100)
        val opacity = HookRuntime.preferences().getInt(BackgroundContract.UI_TOP_BLUR_OPACITY, 100).coerceIn(0, 100)
        val fraction = (maskAlpha?.getFloat(bar) ?: 0f).coerceIn(0f, 1f)
        val radius = if (clear) 0f else min(strength * bar.resources.displayMetrics.density, bar.height * .5f)
        val alpha = if (clear) 0f else fraction * opacity / 100f
        if (radius <= 0f || alpha <= 0f || bar.height <= 0) {
            hide(state)
            return
        }
        if (state.lastRadius != radius || state.lastHeight != bar.height) {
            setMode?.invoke(state.blur, 1)
            setViewMode?.invoke(state.blur, 1)
            setType?.invoke(state.blur, 2)
            setGradient?.invoke(state.blur, floatArrayOf(0f, 0f, radius, 0f, bar.height.toFloat(), 0f), 1)
            state.lastRadius = radius
            state.lastHeight = bar.height
        }
        if (state.lastAlpha != alpha) {
            state.blur.alpha = alpha
            state.lastAlpha = alpha
        }
        if (state.blur.visibility != View.VISIBLE) state.blur.visibility = View.VISIBLE
    }

    private fun hide(state: State) {
        if (state.lastRadius >= 0f) {
            setType?.invoke(state.blur, 0)
            setViewMode?.invoke(state.blur, 0)
            setMode?.invoke(state.blur, 0)
            state.lastRadius = -1f
            state.lastHeight = -1
        }
        state.lastAlpha = 0f
        if (state.blur.visibility != View.INVISIBLE) state.blur.visibility = View.INVISIBLE
    }
}
