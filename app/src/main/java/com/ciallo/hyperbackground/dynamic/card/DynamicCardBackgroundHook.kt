package com.ciallo.hyperbackground.dynamic.card

import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import com.ciallo.hyperbackground.HookRuntime
import com.ciallo.hyperbackground.dynamic.popup.DynamicPopupMaterialHook
import com.ciallo.hyperbackground.appearance.CARD_BACKGROUND_COLOR
import com.ciallo.hyperbackground.appearance.CARD_BACKGROUND_FROST
import com.ciallo.hyperbackground.appearance.CARD_BACKGROUND_SOFT_GLASS
import com.ciallo.hyperbackground.appearance.ComponentKeys
import com.ciallo.hyperbackground.appearance.KEY_APP_COMPONENT_DISABLED
import com.ciallo.hyperbackground.appearance.KEY_APP_SCOPE_DISABLED
import com.ciallo.hyperbackground.appearance.KEY_CARD_BACKGROUND_MODE
import com.ciallo.hyperbackground.appearance.KEY_CARD_DARK_FOLLOWS_LIGHT
import com.ciallo.hyperbackground.appearance.KEY_COMPONENT_GROUP_CARD
import com.ciallo.hyperbackground.appearance.KEY_COMPONENT_POPUP
import com.ciallo.hyperbackground.appearance.KEY_COMPONENT_STANDALONE_CARD
import com.ciallo.hyperbackground.appearance.KEY_CUSTOM_CARD_ENABLED
import com.ciallo.hyperbackground.appearance.KEY_DARK_CARD_BLUR
import com.ciallo.hyperbackground.appearance.KEY_DARK_CARD_COLOR
import com.ciallo.hyperbackground.appearance.KEY_DARK_FROST_COLOR
import com.ciallo.hyperbackground.appearance.KEY_DARK_SOFT_GLASS
import com.ciallo.hyperbackground.appearance.KEY_LIGHT_CARD_BLUR
import com.ciallo.hyperbackground.appearance.KEY_LIGHT_CARD_COLOR
import com.ciallo.hyperbackground.appearance.KEY_LIGHT_FROST_COLOR
import com.ciallo.hyperbackground.appearance.KEY_LIGHT_SOFT_GLASS
import com.ciallo.hyperbackground.dynamic.material.DynamicMaterialPalette
import com.ciallo.hyperbackground.dynamic.material.DynamicFrostDrawable
import com.ciallo.hyperbackground.dynamic.material.DynamicGroupMaterial
import com.ciallo.hyperbackground.dynamic.material.DynamicSoftGlassDrawable
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap

/**
 * Settings 17 draws whole preference groups through MIUIX ItemDecorations.
 * Replace only their fill; MIUIX still computes the group bounds, corners and touch feedback.
 * Read remote preferences on change, never through a ContentProvider from a drawing callback.
 */
internal object DynamicCardBackgroundHook {
    private const val TAG = "HyperBackgroundCards"
    @Volatile private var palette = DynamicMaterialPalette()
    private val preferenceKeys = setOf(
        KEY_CUSTOM_CARD_ENABLED, KEY_LIGHT_CARD_COLOR, KEY_DARK_CARD_COLOR, KEY_CARD_BACKGROUND_MODE,
        KEY_LIGHT_FROST_COLOR, KEY_DARK_FROST_COLOR, KEY_LIGHT_CARD_BLUR, KEY_DARK_CARD_BLUR,
        KEY_LIGHT_SOFT_GLASS, KEY_DARK_SOFT_GLASS, KEY_CARD_DARK_FOLLOWS_LIGHT,
        KEY_COMPONENT_GROUP_CARD, KEY_COMPONENT_STANDALONE_CARD, KEY_COMPONENT_POPUP,
        KEY_APP_SCOPE_DISABLED, KEY_APP_COMPONENT_DISABLED,
    )
    private var groupClipAvailable = false
    /** 分组路由是否在该进程成功接管（Miuix 分组工厂 hook 至少一个成功）。 */
    var routingAvailable: Boolean = false
        private set
    private val states = WeakHashMap<Any, State>()
    private val standaloneStates = Collections.synchronizedMap(WeakHashMap<View, StandaloneState>())
    private val standaloneWrite = ThreadLocal<Boolean>()
    /**
     * 动态发现的分组装饰器类（来自 `RecyclerView.addItemDecoration`）。
     * 与字面名路径汇总去重，[installDynamicDecoration] 用它保证同一个类只挂一次。
     */
    private val decorationClasses = Collections.synchronizedSet(LinkedHashSet<Class<*>>())
    /** 已挂过 hook 的分组裁剪方法，避免两条路径重复挂载同一个方法。 */
    private val hookedClipMethods = Collections.synchronizedSet(HashSet<String>())
    /** 通用路由诊断日志的去重集合，见 [logCandidate]。 */
    private val candidateLog = Collections.synchronizedSet(HashSet<String>())
    /** 动态路径 hook id 的序号，保证同一进程内每次挂载都拿到唯一 id。 */
    private var dynamicHookSeq = 0
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private var preferences: SharedPreferences? = null
    private lateinit var module: XposedModule

    private class Access(
        val drawable: Field,
        val paint: Field?,
        /** 原生 drawable 的工厂方法；结构发现路径可能找不到，因此可空。 */
        val factory: Method?,
        /** 装饰器自带 `Context` 字段时的兜底（绘制方法的 View 参数取不到 context 时用）。 */
        val contextField: Field? = null,
    ) {
        var failureLogged = false
        var frostFailureLogged = false
        var glassFailureLogged = false
        var lastBranch = -1
    }

    private class State(val access: Access) {
        var context: WeakReference<Context>? = null
        var host: WeakReference<View>? = null
        var original: Drawable? = null
        var originalPaintColor: Int? = null
        var fill: ColorDrawable? = null
        var frost: DynamicFrostDrawable? = null
        var glass: DynamicSoftGlassDrawable? = null
        var replacement: Drawable? = null
        var applied = false
        var failed = false
    }

    private class StandaloneState(
        var original: Drawable?,
        val originalClipToOutline: Boolean,
        var originalCardColor: ColorStateList?,
    ) {
        var outlineProvider: ViewOutlineProvider? = null
        var outlineInstalled = false
        var outlineListener: View.OnLayoutChangeListener? = null
        var applied: Drawable? = null
        var signature: StandaloneSignature? = null
        var material = STANDALONE_MATERIAL_NONE
        var failureLogged = false
    }

    private data class StandaloneSignature(
        val paletteHash: Int,
        val night: Boolean,
        val originalIdentity: Int,
    )

    private val refresh = Runnable {
        val tracked = synchronized(states) { states.entries.map { it.key to it.value } }
        for ((owner, state) in tracked) {
            val context = state.context?.get() ?: continue
            update(owner, state, context)
            state.host?.get()?.invalidate()
        }
        val standalone = synchronized(standaloneStates) { standaloneStates.keys.toList() }
        standalone.forEach(::applyStandalone)
        val custom = synchronized(customCards) { customCards.entries.map { it.key to it.value } }
        for ((view, spec) in custom) {
            applyCustomCardMaterial(view, spec.cornerRadiusPx, spec.rippleColor)
        }
    }

    // Keep a strong reference: SharedPreferences holds its listeners weakly.
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key == null || key in preferenceKeys) {
            val updated = DynamicMaterialPalette.read(prefs)
            if (updated != palette) {
                palette = updated
                handler.removeCallbacks(refresh)
                handler.post(refresh)
            }
        }
    }

    fun install(
        value: XposedModule,
        classLoader: ClassLoader,
        prefs: SharedPreferences,
        standalone: Boolean = true,
        groupHooks: Boolean = true,
    ) {
        module = value
        preferences?.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        preferences = prefs
        palette = DynamicMaterialPalette.read(prefs)
        prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
        if (!groupHooks) {
            // 只做自绘卡片（applyCustomCardMaterial）的进程：调色板 + 刷新监听足够。
            // 分组 hook 是每帧绘制路径，接管范围之外的进程一律不装，避免拖慢整页加载。
            module.log(Log.INFO, TAG, "Card material runtime: palette only, group hooks skipped")
            return
        }
        // 纯动态路由：所有按字面类名 / 资源名认目标的 hook 都不装。分组装饰器由
        // DynamicCardMaterialHook 从 RecyclerView.addItemDecoration 现场发现；
        // 独立卡片由 CardSurfaceDetector 按「这一行自己画了什么面」判定。
        module.log(
            Log.INFO, TAG,
            "Card material runtime: dynamic routing " +
                "bionicsApi=${DynamicSoftGlassDrawable.hasBionicsApi()}",
        )
        if (standalone) {
            runCatching { installStandaloneCards(classLoader) }
                .onFailure { module.log(Log.WARN, TAG, "Standalone Settings card hook unavailable", it) }
        }
        // 动态路由的两个入口 hook（View.onSizeChanged 补判独立卡 + RecyclerView.addItemDecoration
        // 现场发现分组装饰器）随卡片材质一起装，保证任何装卡片材质的进程（含安全中心）都带上。
        runCatching { DynamicCardMaterialHook.install(module, classLoader, prefs) }
            .onFailure { module.log(Log.WARN, TAG, "Dynamic card routing unavailable", it) }
    }

    /** True when the exact custom card is owned by the new material router. */
    private const val CUSTOM_MATERIAL_NONE = 0
    private const val CUSTOM_MATERIAL_FLAT = 1
    private const val CUSTOM_MATERIAL_FROST = 2
    private const val CUSTOM_MATERIAL_GLASS = 3
    private const val CUSTOM_MATERIAL_TRANSPARENT = 4

    private class CustomCardSpec(
        var cornerRadiusPx: Float,
        var rippleColor: Int?,
    ) {
        var outlineProvider: ViewOutlineProvider? = null
        var originalClipping = false
        var outlineInstalled = false
        var outlineListener: View.OnLayoutChangeListener? = null
        var material = CUSTOM_MATERIAL_NONE
        var signature = -1
        var attachPending = false
    }

    private val customCards = Collections.synchronizedMap(WeakHashMap<View, CustomCardSpec>())

    /**
     * 自绘「我的设备」卡片（样式 1/2/3，无资源 id，走不了 standalone 路由）的材质接管。
     * 回退链：柔光玻璃 → 磨砂 → 纯色 → 透明；开关关闭或材质全部不可用时卡面直接透明，
     * 不保留任何写死兜底色。可在构造/refresh（含 PreDraw 每帧）中反复调用，签名一致时短路。
     */
    fun applyCustomCardMaterial(view: View, cornerRadiusPx: Float, rippleColor: Int? = null) {
        val colors = palette
        val night = view.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        val spec = synchronized(customCards) {
            customCards.getOrPut(view) { CustomCardSpec(cornerRadiusPx, rippleColor) }.apply {
                if (this.cornerRadiusPx != cornerRadiusPx || this.rippleColor != rippleColor) signature = -1
                this.cornerRadiusPx = cornerRadiusPx
                this.rippleColor = rippleColor
            }
        }
        if (!colors.enabledFor(HookRuntime.targetPackage)) {
            clearCustomCardMaterial(view)
            return
        }
        if (!view.isAttachedToWindow) {
            // enforce/addView 先于挂载：先保持透明，attach 后重新走一遍。
            if (!spec.attachPending) {
                restoreCardOutline(view, spec)
                spec.attachPending = true
                spec.material = CUSTOM_MATERIAL_TRANSPARENT
                spec.signature = -1
                setCustomCardBackground(view, null)
                view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) {
                        v.removeOnAttachStateChangeListener(this)
                        spec.attachPending = false
                        applyCustomCardMaterial(v, spec.cornerRadiusPx, spec.rippleColor)
                    }

                    override fun onViewDetachedFromWindow(v: View) {}
                })
            }
            return
        }
        val signature = colors.hashCode() * 2 + if (night) 1 else 0
        if (spec.signature == signature && spec.material != CUSTOM_MATERIAL_NONE &&
            spec.material != CUSTOM_MATERIAL_TRANSPARENT
        ) {
            return
        }
        spec.signature = signature
        val dark = night && !colors.darkFollowsLight
        val density = view.resources.displayMetrics.density
        val frostColor = if (dark) colors.darkFrost else colors.lightFrost
        // 1) 柔光玻璃（Bionics API + 系统开关齐备才尝试）
        if (colors.mode == CARD_BACKGROUND_SOFT_GLASS &&
            DynamicSoftGlassDrawable.isBionicsActive(view.context)
        ) {
            val config = if (dark) colors.darkGlass else colors.lightGlass
            setCustomCardBackground(
                view,
                roundedCardFill(
                    DynamicSoftGlassDrawable.materialTintColor(frostColor, config),
                    cornerRadiusPx,
                    rippleColor,
                ),
            )
            if (withStandaloneWrite { DynamicSoftGlassDrawable.applyToView(view, config, density) }) {
                installCardOutline(view, spec, cornerRadiusPx)
                spec.material = CUSTOM_MATERIAL_GLASS
                return
            }
            DynamicSoftGlassDrawable.clearFromView(view)
        }
        // 2) 磨砂（Gaussian blur）
        if (colors.mode != CARD_BACKGROUND_COLOR) {
            setCustomCardBackground(view, roundedCardFill(frostColor, cornerRadiusPx, rippleColor))
            if (withStandaloneWrite {
                    DynamicFrostDrawable.applyToView(
                        view,
                        if (dark) colors.darkBlur else colors.lightBlur,
                        density,
                    )
                }
            ) {
                installCardOutline(view, spec, cornerRadiusPx)
                spec.material = CUSTOM_MATERIAL_FROST
                return
            }
            DynamicFrostDrawable.clearFromView(view)
        }
        // 3) 纯色
        restoreCardOutline(view, spec)
        setCustomCardBackground(
            view,
            roundedCardFill(if (dark) colors.dark else colors.light, cornerRadiusPx, rippleColor),
        )
        spec.material = CUSTOM_MATERIAL_FLAT
    }

    /** 清掉模糊状态并把卡面置透明（开关关闭、切回自定义图、或材质不可用时）。幂等。 */
    fun clearCustomCardMaterial(view: View) {
        val spec = synchronized(customCards) { customCards[view] } ?: return
        restoreCardOutline(view, spec)
        if (spec.material == CUSTOM_MATERIAL_TRANSPARENT) return
        spec.material = CUSTOM_MATERIAL_TRANSPARENT
        spec.signature = -1
        DynamicSoftGlassDrawable.clearFromView(view)
        DynamicFrostDrawable.clearFromView(view)
        setCustomCardBackground(view, null)
    }

    private fun roundedCardFill(color: Int, cornerRadiusPx: Float, rippleColor: Int?): Drawable {
        val fill = GradientDrawable().apply {
            setColor(color)
            cornerRadius = cornerRadiusPx
        }
        if (rippleColor == null) return fill
        return RippleDrawable(ColorStateList.valueOf(rippleColor), fill, null)
    }

    private fun setCustomCardBackground(view: View, drawable: Drawable?) {
        withStandaloneWrite { view.background = drawable }
    }

    private fun installStandaloneCards(classLoader: ClassLoader) {
        val attach = View::class.java.declaredMethods.firstOrNull {
            it.name == "dispatchAttachedToWindow" && it.parameterCount == 2
        } ?: error("View.dispatchAttachedToWindow not found")
        attach.isAccessible = true
        module.hook(attach).setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("dynamic-cards:standalone-attach").intercept { chain ->
                val result = chain.proceed()
                (chain.thisObject as? View)?.let(::applyStandalone)
                result
            }

        View::class.java.declaredMethods.firstOrNull {
            it.name == "dispatchDetachedFromWindow" && it.parameterCount == 0
        }?.apply { isAccessible = true }?.let { detach ->
            module.hook(detach).setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("dynamic-cards:standalone-detach").intercept { chain ->
                    val view = chain.thisObject as? View
                    val state = view?.let { synchronized(standaloneStates) { standaloneStates[it] } }
                    if (view != null && state != null) {
                        clearStandaloneMaterial(view, state)
                        state.signature = null
                    }
                    chain.proceed()
                }
        }

        // Some feature sessions replace their card drawable after inflation. Treat that new
        // drawable as the native original, then re-apply the selected mode on the next frame.
        val setBackground = View::class.java.getMethod("setBackground", Drawable::class.java)
        module.hook(setBackground).setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("dynamic-cards:standalone-background").intercept { chain ->
                if (standaloneWrite.get() == true) return@intercept chain.proceed()
                val view = chain.thisObject as? View
                val result = chain.proceed()
                if (view != null && standaloneTarget(view) != null) {
                    val state = standaloneState(view)
                    restoreCardOutline(view, state)
                    state.original = view.background
                    state.applied = null
                    state.signature = null
                    if (palette.enabledFor(HookRuntime.targetPackage) && view.isAttachedToWindow) {
                        view.post { applyStandalone(view) }
                    }
                } else if (view != null &&
                    CardSurfaceDetector.probe(view) == CardSurfaceDetector.REASON_GROUP_LIST_ROW
                ) {
                    // A recycled list item may have been treated as a standalone card
                    // before its parent was attached. Keep the host's newly bound background.
                    val state = synchronized(standaloneStates) { standaloneStates[view] }
                    if (state != null) {
                        clearStandaloneMaterial(view, state)
                        val stillApplied = state.applied === view.background
                        if (stillApplied) {
                            withStandaloneWrite { view.background = state.original }
                        }
                        if (stillApplied) {
                            view.clipToOutline = state.originalClipToOutline
                        }
                        synchronized(standaloneStates) { standaloneStates.remove(view) }
                    }
                }
                result
            }
        module.log(Log.INFO, TAG, "Installed standalone card material routing")
    }

    private fun applyStandalone(view: View) {
        val tracked = synchronized(standaloneStates) { standaloneStates[view] }
        val target = standaloneTarget(view)
        if (target == null) {
            // 曾经接管过、但现在已经不是独立卡片的行（蓝牙可用设备行、列表中曾连接过的设备）：
            // 撤掉我们的材质，原生表面以系统最后一次绑定写入的状态为准。
            if (tracked != null &&
                CardSurfaceDetector.probe(view) == CardSurfaceDetector.REASON_GROUP_LIST_ROW
            ) {
                if (tracked.applied === view.background) releaseStandalone(view, tracked)
                else {
                    clearStandaloneMaterial(view, tracked)
                    restoreCardOutline(view, tracked)
                }
                synchronized(standaloneStates) { standaloneStates.remove(view) }
            } else if (tracked != null) releaseStandalone(view, tracked)
            return
        }
        val state = tracked ?: standaloneState(view)
        val colors = palette
        if (!colors.enabledFor(HookRuntime.targetPackage)) {
            restoreStandalone(view, state)
            return
        }
        if (!view.isAttachedToWindow) return

        val night = view.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        val signature = StandaloneSignature(
            colors.hashCode(),
            night,
            System.identityHashCode(state.original),
        )
        if (state.signature == signature && state.applied === view.background) return

        clearStandaloneMaterial(view, state)
        restoreCardOutline(view, state)
        val dark = night && !colors.darkFollowsLight
        val density = view.resources.displayMetrics.density
        val applied = when (colors.mode) {
            CARD_BACKGROUND_COLOR -> {
                val color = if (dark) colors.dark else colors.light
                prepareStandaloneBackground(view, state, color, state.originalClipToOutline)
            }
            CARD_BACKGROUND_FROST -> {
                val color = if (dark) colors.darkFrost else colors.lightFrost
                if (!prepareStandaloneBackground(view, state, color, true)) false else {
                    val ready = withStandaloneWrite {
                        DynamicFrostDrawable.applyToView(
                            view,
                            if (dark) colors.darkBlur else colors.lightBlur,
                            density,
                        )
                    }
                    if (ready) state.material = STANDALONE_MATERIAL_FROST
                    if (ready) installCardOutline(view, state)
                    ready
                }
            }
            CARD_BACKGROUND_SOFT_GLASS -> {
                val color = if (dark) colors.darkFrost else colors.lightFrost
                val config = if (dark) colors.darkGlass else colors.lightGlass
                val tintColor = DynamicSoftGlassDrawable.materialTintColor(color, config)
                if (!prepareStandaloneBackground(view, state, tintColor, true)) false else {
                    val ready = withStandaloneWrite {
                        DynamicSoftGlassDrawable.applyToView(
                            view,
                            config,
                            density,
                        )
                    }
                    if (ready) state.material = STANDALONE_MATERIAL_GLASS
                    if (ready) installCardOutline(view, state)
                    ready
                }
            }
            else -> false
        }

        if (!applied) {
            clearStandaloneMaterial(view, state)
            if (!prepareStandaloneBackground(view, state, Color.TRANSPARENT, state.originalClipToOutline)) {
                setStandaloneBackground(view, ColorDrawable(Color.TRANSPARENT), state.originalClipToOutline)
            }
            if (!state.failureLogged) {
                state.failureLogged = true
                module.log(
                    Log.WARN,
                    TAG,
                    "Standalone card $target cannot use mode=${colors.mode}; using transparent fallback",
                )
            }
        } else {
            state.failureLogged = false
        }
        state.applied = view.background
        state.signature = signature
        view.invalidate()
    }

    private fun restoreStandalone(view: View, state: StandaloneState) {
        if (state.applied == null && state.material == STANDALONE_MATERIAL_NONE) return
        clearStandaloneMaterial(view, state)
        restoreCardOutline(view, state)
        withStandaloneWrite {
            view.background = state.original
            restoreCardBackgroundColor(view, state.originalCardColor)
            view.clipToOutline = state.originalClipToOutline
        }
        state.applied = null
        state.signature = null
        state.failureLogged = false
        view.invalidate()
    }

    /**
     * Drop our material from a view that stopped being a standalone card. Unlike [restoreStandalone]
     * the saved highlight layer is discarded rather than put back: the view now belongs to the
     * surrounding group card and the native surface was already re-created by the latest bind.
     */
    private fun releaseStandalone(view: View, state: StandaloneState) {
        if (state.applied == null && state.material == STANDALONE_MATERIAL_NONE) return
        clearStandaloneMaterial(view, state)
        restoreCardOutline(view, state)
        withStandaloneWrite {
            // CardView rows keep the very same background object, so skip the setter: writing it
            // back would only re-enter the host's background hooks for a no-op change.
            if (view.background !== state.original) view.background = state.original
            restoreCardBackgroundColor(view, state.originalCardColor)
            view.clipToOutline = state.originalClipToOutline
        }
        state.applied = null
        state.signature = null
        state.failureLogged = false
        view.invalidate()
    }

    private fun clearStandaloneMaterial(view: View, state: StandaloneState) {
        when (state.material) {
            STANDALONE_MATERIAL_FROST -> DynamicFrostDrawable.clearFromView(view)
            STANDALONE_MATERIAL_GLASS -> DynamicSoftGlassDrawable.clearFromView(view)
        }
        state.material = STANDALONE_MATERIAL_NONE
    }

    private fun standaloneState(view: View): StandaloneState = synchronized(standaloneStates) {
        standaloneStates.getOrPut(view) {
            StandaloneState(view.background, view.clipToOutline, cardBackgroundColor(view))
        }
    }

    /**
     * 独立卡片路由的入口，返回一个稳定的 key（`null` = 这一行不该被接管）。
     *
     * 纯动态：完全不看资源 id / 类名 / 包名，只看这一行**自己画了什么面**
     * （[CardSurfaceDetector]），因此换页面、换 apk、换机型都不需要再适配。
     */
    private fun standaloneTarget(view: View): String? {
        if (!palette.enabledFor(HookRuntime.targetPackage, ComponentKeys.STANDALONE_CARD)) return null
        if (DynamicPopupMaterialHook.owns(view)) return null
        // The suspended action menu has its own material route and scope switch.
        if (view.javaClass.name == "miuix.appcompat.internal.view.menu.action.ResponsiveActionMenuView") return null
        // MIUIX search owns its material and alpha animation; do not repaint it as a card on resize.
        if (isSearchSurface(view)) return null
        val reason = CardSurfaceDetector.probe(view)
        if (reason != null) {
            // 尺寸不足 / 没有自己的背景是正常行为，不打日志；其余「自己有面却被拦下」
            // 的原因（透明面 / 负向词 / 非卡片形状 / 外层卡面）都值得记录，用来定位漏判。
            if (CardSurfaceDetector.isNearMiss(reason)) logCandidate(view, "near-miss $reason")
            return null
        }
        val key = CardSurfaceDetector.key(view)
        logCandidate(view, "matched key=$key")
        return key
    }

    private fun isSearchSurface(view: View): Boolean {
        var current: View? = view
        repeat(6) {
            val node = current ?: return false
            val type = node.javaClass.name
            if (type.contains("SearchModeStubView") || type.contains("SearchActionModeView") ||
                type.contains("SearchView")) return true
            val name = runCatching {
                if (node.id == View.NO_ID || node.id == 0) null
                else node.resources.getResourceEntryName(node.id)
            }.getOrNull()
            if (name == "search_mode_stub" || name == "search_container" ||
                name == "search_panel" || name == "search_view" || name == "search_bar"
            ) return true
            current = node.parent as? View
        }
        return false
    }

    private fun cloneAndTint(view: View, source: Drawable?, color: Int): Drawable? =
        (cloneDrawable(view, source) ?: nativeRoundedFill(view, source))?.let { drawable ->
            runCatching {
                // The legacy light-card hook may already have lowered the source alpha.
                // A selected card style owns its complete ARGB value, so do not multiply
                // that value by a stale drawable alpha when cloning the native shape.
                drawable.alpha = 255
                drawable.setTint(color)
                drawable
            }.getOrNull()
        }

    /** HyperCardView's RoundRectDrawable has no ConstantState; reconstruct its native radius. */
    private fun nativeRoundedFill(view: View, source: Drawable?): Drawable? {
        val radius = originalCardRadius(view, source) ?: return null
        return GradientDrawable().apply {
            // cloneAndTint applies the full ARGB tint; an already-tinted fill would square alpha.
            setColor(Color.WHITE)
            cornerRadius = radius
        }
    }

    private fun originalCardRadius(view: View, source: Drawable?): Float? {
        val fromDrawable = source?.let { drawable ->
            runCatching {
                val outline = Outline()
                drawable.getOutline(outline)
                outline.radius.takeIf { it > 0f }
            }.getOrNull()
        }
        if (fromDrawable != null) return fromDrawable
        return runCatching {
            (view.javaClass.getMethod("getRadius").invoke(view) as Number).toFloat()
                .takeIf { it > 0f }
        }.getOrNull()
    }

    private fun installCardOutline(view: View, state: StandaloneState) {
        val radius = originalCardRadius(view, state.original) ?: return
        if (!state.outlineInstalled) {
            state.outlineProvider = view.outlineProvider
            state.outlineInstalled = true
        }
        setCardOutline(view, radius, state.outlineListener) { state.outlineListener = it }
    }

    private fun installCardOutline(view: View, spec: CustomCardSpec, radius: Float) {
        if (radius <= 0f) return
        if (!spec.outlineInstalled) {
            spec.outlineProvider = view.outlineProvider
            spec.originalClipping = view.clipToOutline
            spec.outlineInstalled = true
        }
        setCardOutline(view, radius, spec.outlineListener) { spec.outlineListener = it }
    }

    private fun setCardOutline(
        view: View, radius: Float, previous: View.OnLayoutChangeListener?,
        saveListener: (View.OnLayoutChangeListener) -> Unit,
    ) {
        previous?.let(view::removeOnLayoutChangeListener)
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                outline.setRoundRect(0, 0, v.width, v.height, radius)
            }
        }
        view.clipToOutline = true
        val update = {
            view.invalidateOutline()
            // Bionics can keep a stale RenderNode outline even after the View provider changes.
            runCatching {
                val node = View::class.java.getDeclaredField("mRenderNode")
                    .apply { isAccessible = true }.get(view) as android.graphics.RenderNode
                node.setOutline(Outline().apply {
                    setRoundRect(0, 0, view.width, view.height, radius)
                    alpha = 1f
                })
                node.setClipToOutline(true)
            }
        }
        val listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> update() }
        view.addOnLayoutChangeListener(listener)
        saveListener(listener)
        if (view.width > 0 && view.height > 0) update()
    }

    private fun restoreCardOutline(view: View, state: StandaloneState) {
        if (!state.outlineInstalled) return
        state.outlineListener?.let(view::removeOnLayoutChangeListener)
        state.outlineListener = null
        view.outlineProvider = state.outlineProvider
        view.clipToOutline = state.originalClipToOutline
        view.invalidateOutline()
        runCatching {
            val node = View::class.java.getDeclaredField("mRenderNode")
                .apply { isAccessible = true }.get(view) as android.graphics.RenderNode
            val outline = Outline()
            view.outlineProvider?.getOutline(view, outline)
            node.setOutline(outline)
            node.setClipToOutline(state.originalClipToOutline)
        }
        state.outlineInstalled = false
    }

    private fun restoreCardOutline(view: View, spec: CustomCardSpec) {
        if (!spec.outlineInstalled) return
        spec.outlineListener?.let(view::removeOnLayoutChangeListener)
        spec.outlineListener = null
        view.outlineProvider = spec.outlineProvider
        view.clipToOutline = spec.originalClipping
        view.invalidateOutline()
        runCatching {
            val node = View::class.java.getDeclaredField("mRenderNode")
                .apply { isAccessible = true }.get(view) as android.graphics.RenderNode
            val outline = Outline()
            view.outlineProvider?.getOutline(view, outline)
            node.setOutline(outline)
            node.setClipToOutline(spec.originalClipping)
        }
        spec.outlineInstalled = false
    }

    private fun prepareStandaloneBackground(
        view: View,
        state: StandaloneState,
        color: Int,
        clipToOutline: Boolean,
    ): Boolean {
        val drawable = cloneAndTint(view, state.original, color) ?: return false
        setStandaloneBackground(view, drawable, clipToOutline)
        return true
    }

    private fun cardBackgroundColor(view: View): ColorStateList? = runCatching {
        view.javaClass.getMethod("getCardBackgroundColor").invoke(view) as? ColorStateList
    }.getOrNull()

    private fun setCardBackgroundColor(view: View, color: Int): Boolean = runCatching {
        // A previously applied drawable tint would override CardView's internal base color.
        // Clear it before switching styles or restoring the native ColorStateList.
        view.background?.setTintList(null)
        view.javaClass.getMethod("setCardBackgroundColor", Int::class.javaPrimitiveType!!)
            .invoke(view, color)
        true
    }.getOrDefault(false)

    private fun restoreCardBackgroundColor(view: View, color: ColorStateList?) {
        color ?: return
        view.background?.setTintList(null)
        val restored = runCatching {
            view.javaClass.getMethod("setCardBackgroundColor", ColorStateList::class.java)
                .invoke(view, color)
        }.isSuccess
        if (!restored) setCardBackgroundColor(view, color.defaultColor)
    }

    private fun cloneDrawable(view: View, source: Drawable?): Drawable? = runCatching {
        source?.constantState?.newDrawable(view.resources, view.context.theme)?.mutate()
            ?: source?.constantState?.newDrawable()?.mutate()
    }.getOrNull()

    private fun setStandaloneBackground(view: View, drawable: Drawable, clipToOutline: Boolean) {
        withStandaloneWrite {
            view.background = drawable
            view.clipToOutline = clipToOutline
        }
    }

    private inline fun <T> withStandaloneWrite(block: () -> T): T {
        val previous = standaloneWrite.get()
        standaloneWrite.set(true)
        return try {
            block()
        } finally {
            if (previous == true) standaloneWrite.set(true) else standaloneWrite.remove()
        }
    }

    /**
     * 动态路径用的分组绘制方法定位：**不看方法名**（R8 会改），只看签名——
     * 非抽象、非静态，首参是 `Canvas`、次参是某个 `View` 子类（即 `RecyclerView`），
     * 最后一个参数是该 RecyclerView 的 Adapter。MIUIX 有 (Canvas, RecyclerView,
     * State, Adapter) 和 (Canvas, RecyclerView, Adapter) 两种绘制入口；按 getAdapter()
     * 的返回类型识别 Adapter，而不是依赖被 R8 压缩的内部类名。
     *
     * 这个形状把同一层里的 `onDraw(Canvas)` / `dispatchDraw(Canvas)` 这类无关重载排除在外，
     * 同时不依赖 `calculateGroupRectAndDraw` 这种会被压缩掉的名字。
     */
    private fun installDrawHooksBySignature(type: Class<*>, access: Access, id: String) {
        val methods = generateSequence<Class<*>>(type) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .filter { method ->
                !Modifier.isAbstract(method.modifiers) &&
                    !Modifier.isStatic(method.modifiers) &&
                    (method.parameterCount == 3 || method.parameterCount == 4) &&
                    method.parameterTypes[0] == Canvas::class.java &&
                    View::class.java.isAssignableFrom(method.parameterTypes[1]) &&
                    method.parameterTypes[1].methods.any { getter ->
                        getter.name == "getAdapter" && getter.parameterCount == 0 &&
                            getter.returnType.isAssignableFrom(method.parameterTypes.last())
                    }
            }
            .distinctBy { it.parameterTypes.toList() }
            .toList()
        check(methods.isNotEmpty()) { "No group draw method on ${type.name}" }
        hookGroupDrawMethods(methods, type, access, id)
    }

    /**
     * 分组卡的每帧绘制入口。MIUIX 在 `Canvas.saveLayerAlpha` 里画分组卡，
     * 所以这里绕不开两件事：把宿主 View 交给材质（采背景要用它）、
     * 用 `beginFrame` / `endFrame` 框住这一帧的多张卡（模糊只做一次）。
     */
    private fun hookGroupDrawMethods(methods: List<Method>, type: Class<*>, access: Access, id: String) {
        for ((index, method) in methods.withIndex()) {
            val hostIndex = method.parameterTypes.indexOfFirst { View::class.java.isAssignableFrom(it) }
            method.isAccessible = true
            module.hook(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("dynamic-cards:$id-draw-$index").intercept { chain ->
                    val owner = chain.thisObject
                    var frost: DynamicFrostDrawable? = null
                    var glass: DynamicSoftGlassDrawable? = null
                    if (owner != null && type.isInstance(owner)) {
                        val state = state(owner, access)
                        val host = if (hostIndex >= 0) chain.getArg(hostIndex) as? View else null
                        if (host != null && state.host?.get() !== host) state.host = WeakReference(host)
                        val context = host?.context ?: state.context?.get() ?: ownerContext(owner, access)
                        if (context != null) update(owner, state, context)
                        frost = state.frost
                        glass = state.glass
                        frost?.bindHost(host)
                        glass?.bindHost(host)
                        frost?.beginFrame()
                        glass?.beginFrame()
                    }
                    try { chain.proceed() } finally {
                        frost?.endFrame()
                        glass?.endFrame()
                    }
                }
        }
    }

    private fun state(owner: Any, access: Access): State = synchronized(states) {
        states.getOrPut(owner) { State(access) }
    }

    private fun logMaterialBranch(access: Access, context: Context, glass: Boolean, frost: Boolean) {
        val branch = when {
            glass -> 2
            frost -> 1
            else -> 0
        }
        if (access.lastBranch == branch) return
        access.lastBranch = branch
        module.log(Log.INFO, TAG, when (branch) {
            2 -> "Card material branch: soft glass (${DynamicSoftGlassDrawable.bionicsDiagnostics(context)})"
            1 -> "Card material branch: frost (Gaussian path)"
            else -> "Card material branch: flat color"
        })
    }

    private fun update(owner: Any, state: State, context: Context, nativeResolved: Boolean = false) {
        val access = state.access
        if (state.failed) return
        try {
            if (state.context?.get() !== context) state.context = WeakReference(context)
            val current = access.drawable.get(owner) as? Drawable
            val paint = access.paint?.get(owner) as? Paint
            if (nativeResolved || !state.applied || current !== state.replacement) {
                // Never save our own replacement as the system's original.
                if (current !== state.replacement || current == null) {
                    state.original = current
                    state.originalPaintColor = paint?.color
                }
            }
            val colors = palette
            if (!colors.enabledFor(HookRuntime.targetPackage, ComponentKeys.GROUP_CARD)) {
                if (state.applied) {
                    state.applied = false
                    state.frost?.dispose()
                    state.frost = null
                    state.glass?.dispose()
                    state.glass = null
                    if (current === state.replacement) {
                        access.drawable.set(owner, state.original)
                        state.originalPaintColor?.let { paint?.color = it }
                        // Re-resolve the current theme instead of restoring hardcoded white/transparent.
                        // Our factory hook now observes enabled=false, so this does not recurse.
                        // 结构发现路径可能压根找不到工厂方法，那就只恢复我们自己保存的那一份。
                        access.factory?.let { factory ->
                            if (factory.parameterCount == 1) factory.invoke(owner, context)
                            else factory.invoke(owner)
                        }
                    }
                    state.replacement = null
                }
                return
            }
            val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            // 深色跟随浅色：开启后深色主题直接采用浅色侧的颜色/模糊/材质参数。
            val dark = night && !colors.darkFollowsLight
            // 柔光玻璃依赖 OS4 的 Bionics 材质 API 与系统开关（材质风格=柔光玻璃）；不可用时降级为磨砂。
            val useGlass = colors.mode == CARD_BACKGROUND_SOFT_GLASS
                && groupClipAvailable
                && DynamicSoftGlassDrawable.isBionicsActive(context)
            val useFrost = !useGlass && colors.mode != CARD_BACKGROUND_COLOR && groupClipAvailable
            logMaterialBranch(access, context, useGlass, useFrost)
            val glassy = useGlass || useFrost
            val color = if (glassy) {
                if (dark) colors.darkFrost else colors.lightFrost
            } else {
                if (dark) colors.dark else colors.light
            }
            val replacement: Drawable = when {
                useGlass -> {
                    val glass = state.glass ?: DynamicSoftGlassDrawable(context) { error ->
                        if (!access.glassFailureLogged) {
                            access.glassFailureLogged = true
                            module.log(Log.WARN, TAG, "Native soft glass unavailable; retaining the selected tint", error)
                        }
                    }.also { state.glass = it }
                    glass.configure(
                        color,
                        if (dark) colors.darkGlass else colors.lightGlass,
                        context.resources.displayMetrics.density,
                    )
                    glass.bindHost(state.host?.get())
                    state.frost?.dispose()
                    state.frost = null
                    glass
                }
                useFrost -> {
                    val frost = state.frost ?: DynamicFrostDrawable(context) { error ->
                        if (!access.frostFailureLogged) {
                            access.frostFailureLogged = true
                            module.log(Log.WARN, TAG, "Native group blur unavailable; retaining the selected tint", error)
                        }
                    }.also { state.frost = it }
                    frost.configure(
                        color,
                        if (dark) colors.darkBlur else colors.lightBlur,
                        context.resources.displayMetrics.density,
                    )
                    frost.bindHost(state.host?.get())
                    state.glass?.dispose()
                    state.glass = null
                    frost
                }
                else -> {
                    state.frost?.dispose()
                    state.frost = null
                    state.glass?.dispose()
                    state.glass = null
                    val fill = state.fill ?: ColorDrawable(color).also { state.fill = it }
                    if (fill.color != color) fill.color = color
                    fill
                }
            }
            state.replacement = replacement
            if (current !== replacement) access.drawable.set(owner, replacement)
            if (paint != null && paint.color != color) paint.color = color
            state.applied = true
        } catch (error: Throwable) {
            // Fail open for this instance, without repeated reflection failures during scrolling.
            state.failed = true
            runCatching {
                val current = access.drawable.get(owner)
                if (current === state.fill || current === state.frost || current === state.glass) access.drawable.set(owner, state.original)
                state.originalPaintColor?.let { color -> (access.paint?.get(owner) as? Paint)?.color = color }
            }
            state.frost?.dispose()
            state.frost = null
            state.glass?.dispose()
            state.glass = null
            state.replacement = null
            state.applied = false
            if (!access.failureLogged) {
                access.failureLogged = true
                module.log(Log.WARN, TAG, "Cannot update group card fill; keeping the native background", error)
            }
        }
    }

    /**
     * 分组绘制的 Context 来源：绘制点的 View → 已记录 Context → 装饰器自带 Context 字段。
     * 动态路径没有 `this$0` 可用，用「类型为 Context 的字段」兜底。
     */
    private fun ownerContext(owner: Any, access: Access): Context? =
        access.contextField?.let { runCatching { it.get(owner) as? Context }.getOrNull() }

    // ---------------------------------------------------------------- 动态路由（结构发现）

    /**
     * 布局完成时机。attach / inflate 阶段 `width == 0`，[CardSurfaceDetector.probe] 必然早退成
     * `too-small`，通用路由形同虚设；这里在真实尺寸写回的那一刻补判一次。
     *
     * 由 [DynamicCardMaterialHook] 的 `View.onSizeChanged` hook 调用。
     */
    internal fun onViewLaidOut(view: View) {
        if (!palette.enabledFor(HookRuntime.targetPackage)) return
        if (view.width <= 0 || view.height <= 0) return
        // Recheck tracked views as well: recycled rows can acquire a RecyclerView parent
        // after their initial attach and must leave the standalone material route.
        if (synchronized(standaloneStates) { standaloneStates[view] }?.applied != null) {
            if (CardSurfaceDetector.probe(view) == CardSurfaceDetector.REASON_GROUP_LIST_ROW) {
                view.post { applyStandalone(view) }
            }
            return
        }
        if (standaloneTarget(view) == null) return
        view.post { applyStandalone(view) }
    }

    /**
     * 动态接管一个刚注册到 `RecyclerView` 的分组装饰器
     * （由 [DynamicCardMaterialHook] 在 `RecyclerView.addItemDecoration` 里发现后调用）。
     *
     * 不假定类名、字段名、方法名。第一关是 [clipMethodOf]：只有存在
     * `(Canvas, RectF, Path, Drawable)` 这个裁剪入口的类才可能是分组装饰器——
     * 普通分隔线没有这个签名，会在这一步被排除，不会误伤。
     */
    internal fun installDynamicDecoration(type: Class<*>): Boolean {
        // 不在这里判断 palette.enabledFor(...)：装饰器只在 RecyclerView 初始化时注册一次，
        // 若此刻材质是关的就跳过，用户之后打开开关就再也没有第二次机会。
        // 挂上 hook 的成本是零（[update] 在 enabled=false 时会恢复原生 drawable），
        // 所以一律安装，由绘制时的 update 决定要不要接管。
        synchronized(decorationClasses) { if (!decorationClasses.add(type)) return routingAvailable }
        val clip = clipMethodOf(type) ?: return false
        val access = runCatching { structuralAccess(type) }.getOrNull() ?: return false
        return runCatching {
            installDrawHooksBySignature(type, access, dynamicHookId())
            hookClipMethod(clip)
            groupClipAvailable = true
            routingAvailable = true
            module.log(
                Log.INFO, TAG,
                "Dynamic group decoration: ${type.name} " +
                    "drawable=${access.drawable.type.simpleName} " +
                    "factory=${access.factory?.name ?: "-"}",
            )
            true
        }.getOrElse { error ->
            module.log(Log.WARN, TAG, "Dynamic group decoration failed: ${type.name}", error)
            false
        }
    }

    /**
     * 结构发现：只知道「这是个分组装饰器」，其余全靠形状推断。
     *
     * - drawable 字段：优先取子类持有的分组卡面，避免选到基类的分隔线 Drawable；
     * - paint 字段：优先取裁剪基类持有的分组画笔，跳过子类的选中态遮罩画笔；
     * - 工厂方法：返回同一个 drawable 类型、参数 0 个或 1 个 `Context` 的那个
     *   （关材质或切主题时用它让系统重新解析原生 drawable）；
     * - contextField：类型为 `Context` 的字段，绘制方法取不到宿主 View 时的兜底。
     */
    private fun structuralAccess(type: Class<*>): Access? {
        val hierarchy = generateSequence<Class<*>>(type) { it.superclass }.toList()
        val fields = hierarchy.flatMap { it.declaredFields.toList() }
        val clipDrawableType = clipMethodOf(type)?.parameterTypes?.getOrNull(3)
        val drawableCandidates = fields.filter { Drawable::class.java.isAssignableFrom(it.type) }
        val drawableField =
            (drawableCandidates.firstOrNull { it.declaringClass == type && it.type == clipDrawableType }
                ?: drawableCandidates.firstOrNull { it.declaringClass == type }
                ?: drawableCandidates.firstOrNull { it.type == clipDrawableType }
                ?: drawableCandidates.firstOrNull())
                ?.apply { isAccessible = true } ?: return null
        // MIUIX PreferenceFragment's decoration has a second Paint in the subclass for
        // checkable-row masks. The group fill for ColorDrawable is painted by the
        // clip/draw base class (drawCardRect), so taking the first Paint from the
        // subclass leaves the actual card at its original translucent color.
        val clipOwner = clipMethodOf(type)?.declaringClass
        val paintField = (clipOwner?.declaredFields?.firstOrNull {
            Paint::class.java.isAssignableFrom(it.type)
        } ?: fields.firstOrNull { Paint::class.java.isAssignableFrom(it.type) })
            ?.apply { isAccessible = true }
        val contextField = fields.firstOrNull { Context::class.java.isAssignableFrom(it.type) }
            ?.apply { isAccessible = true }
        val drawableType = drawableField.type
        val factory = hierarchy.asSequence()
            .flatMap { it.declaredMethods.asSequence() }
            .firstOrNull { method ->
                !Modifier.isStatic(method.modifiers) &&
                    method.parameterCount <= 1 &&
                    (method.parameterCount == 0 ||
                        Context::class.java.isAssignableFrom(method.parameterTypes[0])) &&
                    drawableType.isAssignableFrom(method.returnType)
            }
            ?.apply { isAccessible = true }
        return Access(drawableField, paintField, factory, contextField = contextField)
    }

    /**
     * 分组卡的裁剪入口：`void (Canvas, RectF, Path, Drawable)`。
     * 不依赖方法名或是否 static：短信把它放在基类的静态方法中。
     */
    private fun clipMethodOf(type: Class<*>): Method? =
        generateSequence<Class<*>>(type) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .firstOrNull { method ->
                method.returnType == Void.TYPE && method.parameterCount == 4 &&
                    method.parameterTypes[0] == Canvas::class.java &&
                    method.parameterTypes[1] == RectF::class.java &&
                    method.parameterTypes[2] == Path::class.java &&
                    Drawable::class.java.isAssignableFrom(method.parameterTypes[3])
            }
            ?.apply { isAccessible = true }

    /**
     * 挂上裁剪绕行。玻璃 / 磨砂必须走这条路：MIUIX 的 `saveLayerAlpha` 会把卡片与真实背景
     * 隔离成一层，材质拿不到背景就只能是死色。同一个方法只挂一次——动态发现会在多个
     * `RecyclerView` 上反复遇到同一个装饰器类。
     */
    private fun hookClipMethod(method: Method): Boolean {
        val key = "${method.declaringClass.name}#${method.name}#" +
            method.parameterTypes.joinToString { it.name }
        synchronized(hookedClipMethods) {
            if (key in hookedClipMethods) return true
            hookedClipMethods.add(key)
        }
        method.isAccessible = true
        module.hook(method).setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("dynamic-cards:clip-${dynamicHookId()}").intercept { chain ->
                val material = chain.getArg(3) as? DynamicGroupMaterial
                if (material == null) {
                    chain.proceed()
                } else {
                    // 只有我们的 drawable 需要绕开那一层，其余情况保持原生分组路径。
                    material.drawGroup(
                        chain.getArg(0) as Canvas,
                        chain.getArg(1) as RectF,
                        chain.getArg(2) as Path,
                    )
                    null
                }
            }
        return true
    }

    private fun dynamicHookId(): String = synchronized(decorationClasses) {
        "dyn${dynamicHookSeq++}"
    }

    /**
     * 通用路由的接管 / 近失诊断日志：按「原因 + 类名 + 资源名」去重并全局封顶，
     * 既能在日志里回答「这张卡到底接管了没、被哪条规则拦下」，又不会在列表页刷屏
     * （每类视图最多一行）。
     */
    internal fun logCandidate(view: View, detail: String) {
        val id = if (view.id == View.NO_ID || view.id == 0) "-" else {
            runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull() ?: "-"
        }
        val key = "$detail|${view.javaClass.name}|$id"
        synchronized(candidateLog) {
            if (key in candidateLog || candidateLog.size >= CANDIDATE_LOG_LIMIT) return
            candidateLog.add(key)
        }
        module.log(
            Log.INFO, TAG,
            "Standalone $detail class=${view.javaClass.simpleName} id=$id " +
                "size=${view.width}x${view.height}",
        )
    }

    /** 通用路由诊断日志每种视图最多记几行，防止在长列表页刷屏。 */
    private const val CANDIDATE_LOG_LIMIT = 60
    private const val STANDALONE_MATERIAL_NONE = 0
    private const val STANDALONE_MATERIAL_FROST = 1
    private const val STANDALONE_MATERIAL_GLASS = 2
}
