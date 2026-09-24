package com.ciallo.hyperbackground.dynamic.topbar

import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.util.Log
import android.view.View
import android.view.ViewGroup
import com.ciallo.hyperbackground.BackgroundContract
import com.ciallo.hyperbackground.HookRuntime
import com.ciallo.hyperbackground.appearance.KEY_APP_SCOPE_DISABLED
import com.ciallo.hyperbackground.appearance.SETTINGS_APPEARANCE_PREFERENCES
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
    private val owned = Collections.synchronizedMap(WeakHashMap<ViewGroup, State>())
    private data class State(val blur: View, var original: Drawable? = null, var cleared: Boolean = false,
                             var lastRadius: Float = -1f, var lastHeight: Int = -1, var lastAlpha: Float = 0f)

    private lateinit var module: XposedModule
    private var maskAlpha: Field? = null
    private var setType: Method? = null
    private var setMode: Method? = null
    private var setViewMode: Method? = null
    private var setGradient: Method? = null
    private var setPrimary: Method? = null
    private var getPrimary: Method? = null
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

        // Scan only the class we will hook. Both the painter and the alpha field are discovered
        // through framework calls; do not rely on JADX's R8-renamed method/field identifiers.
        val painter = runCatching { discoverMask(type, loader) }
            .onFailure { module.log(Log.WARN, TAG, "Action bar mask discovery failed", it) }
            .getOrNull()
        if (painter == null || maskAlpha == null) {
            module.log(Log.WARN, TAG, "Action bar mask is ambiguous; leaving native bar intact")
            return
        }

        type.getDeclaredMethod("onFinishInflate").apply { isAccessible = true }.let { method ->
            module.hook(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("dynamic-topbar:inflate").intercept { chain ->
                    val result = chain.proceed()
                    (chain.thisObject as? ViewGroup)?.let(::ensure)
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
                        }
                        result
                    }
            }
        type.getDeclaredMethod("onDraw", Canvas::class.java).apply { isAccessible = true }.let { method ->
            module.hook(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("dynamic-topbar:draw").intercept { chain ->
                    (chain.thisObject as? ViewGroup)?.takeIf { owned.containsKey(it) }?.let(::update)
                    chain.proceed()
                }
        }
        module.hook(painter).setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("dynamic-topbar:mask").intercept { chain ->
                val bar = chain.thisObject as? ViewGroup
                if (bar != null && enabled() && owned.containsKey(bar)) null else chain.proceed()
            }
        module.log(Log.INFO, TAG, "Action bar installed mask=${painter.name} alpha=${maskAlpha?.name}")
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
            val animatedWriters = data.methods.filter { method ->
                method.invokes.any { it.className == "android.animation.ValueAnimator" && it.name == "getAnimatedValue" }
            }.map { it.descriptor }.toSet()
            val fields = painter.usingFields.map { it.field }.filter { field ->
                field.className == BAR && field.typeName == "float" &&
                    field.writers.any { it.descriptor in animatedWriters }
            }.distinctBy { it.descriptor }
            if (fields.size != 1) return null
            maskAlpha = fields.single().getFieldInstance(loader).apply { isAccessible = true }
            return painter.getMethodInstance(loader).apply { isAccessible = true }
        }
    }

    private fun enabled(): Boolean {
        val disabled = scope?.getStringSet(KEY_APP_SCOPE_DISABLED, emptySet())
        return HookRuntime.targetPackage !in (disabled ?: emptySet()) &&
            (HookRuntime.preferences().getBoolean(BackgroundContract.UI_TOP_BLUR_ENABLED, true) ||
                HookRuntime.preferences().getBoolean(BackgroundContract.UI_TOP_CLEAR_ENABLED, false))
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
