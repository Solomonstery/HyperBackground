package com.ciallo.hyperbackground.dynamic.bar

import android.content.SharedPreferences
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
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

/** Replace the split action bar's tinted scrim with a bottom-up transparent blur. */
internal object DynamicBottomGradientHook {
    private const val BAR = "miuix.appcompat.internal.app.widget.ActionBarContainer"
    private const val NAVIGATOR = "miuix.bottomnavigation.BottomNavigator"
    private data class State(
        val view: View, var height: Int = -1, var radius: Float = -1f,
        var opacity: Float = -1f, var failed: Boolean = false, var logged: Boolean = false,
    )
    private val bars = Collections.synchronizedMap(WeakHashMap<ViewGroup, State>())
    private val navigators = Collections.synchronizedMap(WeakHashMap<View, Unit>())
    private val main = Handler(Looper.getMainLooper())
    private var scope: SharedPreferences? = null
    private var scopeListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var configListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    fun install(module: XposedModule, loader: ClassLoader) {
        installBottomNavigator(module, loader)
        val type = runCatching { loader.loadClass(BAR) }.getOrNull() ?: run {
            module.log(Log.WARN, "HyperBackgroundBars", "Bottom gradient class unavailable package=${HookRuntime.targetPackage}")
            return
        }
        val native = runCatching {
            val field = type.getDeclaredField("mIsSplit").apply { isAccessible = true }
            val painter = type.getDeclaredMethod("drawSplitScrim", Canvas::class.java).apply { isAccessible = true }
            field to painter
        }.getOrNull()
        // Contacts R8 renames both members. Match the split painter by its Canvas
        // drawRect call plus the two responsive-menu sources used for its alpha.
        val resolved = native ?: runCatching { discoverSplit(type, loader) }
            .onFailure { module.log(Log.WARN, "HyperBackgroundBars", "Split scrim discovery failed", it) }
            .getOrNull().also {
                if (it == null) module.log(
                    Log.WARN, "HyperBackgroundBars",
                    "Split scrim unresolved package=${HookRuntime.targetPackage}",
                )
            } ?: return
        val (split, scrim) = resolved
        module.log(
            Log.INFO, "HyperBackgroundBars",
            "Bottom gradient resolved package=${HookRuntime.targetPackage} split=${split.name} painter=${scrim.name}",
        )
        val setMode = View::class.java.getMethod("setMiBackgroundBlurMode", Int::class.javaPrimitiveType)
        val setViewMode = View::class.java.getMethod("setMiViewBlurMode", Int::class.javaPrimitiveType)
        val setType = View::class.java.getMethod("setMiBackgroundBlurType", Int::class.javaPrimitiveType)
        val setGradient = View::class.java.getMethod(
            "setBackgroundGradientBlurParams", FloatArray::class.java, Int::class.javaPrimitiveType,
        )
        scope = HookRuntime.remotePreferences(SETTINGS_APPEARANCE_PREFERENCES)
        fun active(): Boolean = HookRuntime.targetPackage !in
            (scope?.getStringSet(KEY_APP_SCOPE_DISABLED, emptySet()) ?: emptySet())
        fun clear(): Boolean = active() && HookRuntime.preferences()
            .getBoolean(BackgroundContract.UI_BOTTOM_CLEAR_ENABLED, false)
        // If old preferences contain both flags, bottom takes priority. The top
        // hook checks the same setting before enabling its blur.
        fun enabled(): Boolean = active() && HookRuntime.preferences()
            .getBoolean(BackgroundContract.UI_BOTTOM_GRADIENT_ENABLED, false) && !clear()
        fun isSplit(bar: ViewGroup): Boolean = runCatching { split.getBoolean(bar) }.getOrDefault(false)

        fun hide(state: State) {
            if (state.radius >= 0f) {
                runCatching {
                    setType.invoke(state.view, 0)
                    setViewMode.invoke(state.view, 0)
                    setMode.invoke(state.view, 0)
                }
                state.radius = -1f
                state.opacity = -1f
            }
            if (state.view.visibility != View.INVISIBLE) state.view.visibility = View.INVISIBLE
        }

        fun refresh(bar: ViewGroup, state: State) {
            if (state.failed || !enabled() || bar.height <= 0 || bar.width <= 0) {
                hide(state)
                return
            }
            val height = bar.height
            val strength = HookRuntime.preferences().getInt(
                BackgroundContract.UI_BOTTOM_GRADIENT_STRENGTH, 10,
            ).coerceIn(0, 100)
            val opacity = HookRuntime.preferences().getInt(
                BackgroundContract.UI_BOTTOM_GRADIENT_OPACITY, 100,
            ).coerceIn(0, 100) / 100f
            val radius = min(strength * bar.resources.displayMetrics.density, 400f)
            if (radius <= 0f || opacity <= 0f) {
                hide(state)
                return
            }
            // The child measures 0x0 so onMeasureSplit cannot enlarge the bar.
            // Only size it after MIUIX completes its layout; never mutate it in onDraw.
            if (state.view.width != bar.width || state.view.height != height) {
                state.view.layout(0, 0, bar.width, height)
            }
            if (state.height != height || state.radius != radius || state.opacity != opacity) {
                setMode.invoke(state.view, 1)
                setViewMode.invoke(state.view, 1)
                setType.invoke(state.view, 2)
                // Native top gradient uses two (x, y, blur radius) stops:
                // (0, 0, radius) -> (0, height, 0). Reverse the radii for the split bar.
                setGradient.invoke(state.view, floatArrayOf(0f, 0f, 0f, 0f, height.toFloat(), radius), 1)
                state.height = height
                state.radius = radius
                state.opacity = opacity
            }
            state.view.alpha = opacity
            if (state.view.visibility != View.VISIBLE) state.view.visibility = View.VISIBLE
        }

        val inflate = type.getDeclaredMethod("onFinishInflate").apply { isAccessible = true }
        module.hook(inflate).setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("dynamic-bottom-gradient:inflate").intercept { chain ->
                val result = chain.proceed()
                (chain.thisObject as? ViewGroup)?.takeIf(::isSplit)?.let { bar ->
                    if (!bars.containsKey(bar)) {
                        val view = View(bar.context).apply {
                            isClickable = false
                            isFocusable = false
                            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                            visibility = View.INVISIBLE
                        }
                        bar.addView(view, 0, ViewGroup.LayoutParams(0, 0))
                        bars[bar] = State(view)
                    }
                }
                result
            }
        val layout = type.getDeclaredMethod(
            "onLayout", Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        module.hook(layout).setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("dynamic-bottom-gradient:layout").intercept { chain ->
                val result = chain.proceed()
                (chain.thisObject as? ViewGroup)?.takeIf(::isSplit)?.let { bar ->
                    val state = bars[bar] ?: run {
                        val view = View(bar.context).apply {
                            isClickable = false
                            isFocusable = false
                            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                            visibility = View.INVISIBLE
                        }
                        bar.addView(view, 0, ViewGroup.LayoutParams(0, 0))
                        State(view).also { bars[bar] = it }
                    }
                    if (!state.logged) {
                        state.logged = true
                        module.log(
                            Log.INFO, "HyperBackgroundBars",
                            "Split bar active ${bar.javaClass.name} enabled=${enabled()} clear=${clear()} height=${bar.height}",
                        )
                    }
                    runCatching { refresh(bar, state) }.onFailure { error ->
                        hide(state)
                        state.failed = true
                        module.log(Log.WARN, "HyperBackgroundBars", "Bottom gradient unavailable", error)
                    }
                }
                result
            }
        module.hook(scrim).setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("dynamic-bottom-gradient:scrim").intercept { chain ->
                val bar = chain.thisObject as? ViewGroup
                if (bar != null && isSplit(bar) &&
                    (clear() || enabled() && bars[bar]?.view?.visibility == View.VISIBLE)
                ) {
                    module.log(Log.DEBUG, "HyperBackgroundBars", "Split scrim suppressed enabled=${enabled()} clear=${clear()}")
                    null
                } else chain.proceed()
            }

        fun relayout() {
            main.post {
                synchronized(bars) { bars.keys.toList() }.forEach { bar ->
                    bar.requestLayout()
                    bar.invalidate()
                }
            }
        }
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == KEY_APP_SCOPE_DISABLED) relayout()
        }
        scope?.registerOnSharedPreferenceChangeListener(listener)
        scopeListener = listener
        val configChanges = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key in setOf(
                    BackgroundContract.UI_BOTTOM_GRADIENT_ENABLED,
                    BackgroundContract.UI_BOTTOM_GRADIENT_STRENGTH,
                    BackgroundContract.UI_BOTTOM_GRADIENT_OPACITY,
                    BackgroundContract.UI_BOTTOM_CLEAR_ENABLED,
                )
            ) relayout()
        }
        HookRuntime.preferences().registerOnSharedPreferenceChangeListener(configChanges)
        configListener = configChanges
        module.log(Log.INFO, "HyperBackgroundBars", "Split action bar gradient installed")
    }

    private fun installBottomNavigator(module: XposedModule, loader: ClassLoader) {
        val type = runCatching { loader.loadClass(NAVIGATOR) }.getOrNull() ?: return
        val setGradient = runCatching {
            type.getMethod("setGradientBackground", Boolean::class.javaPrimitiveType)
        }.getOrNull() ?: return
        val setSupport = runCatching {
            type.getMethod("setSupportBlur", Boolean::class.javaPrimitiveType)
        }.getOrNull()
        val setEnable = runCatching {
            type.getMethod("setEnableBlur", Boolean::class.javaPrimitiveType)
        }.getOrNull()
        val setMode = runCatching { View::class.java.getMethod("setMiBackgroundBlurMode", Int::class.javaPrimitiveType) }.getOrNull()
        val setViewMode = runCatching { View::class.java.getMethod("setMiViewBlurMode", Int::class.javaPrimitiveType) }.getOrNull()
        val setType = runCatching { View::class.java.getMethod("setMiBackgroundBlurType", Int::class.javaPrimitiveType) }.getOrNull()
        val setRadius = runCatching { View::class.java.getMethod("setMiBackgroundBlurRadius", Int::class.javaPrimitiveType) }.getOrNull()
        val setParams = runCatching { View::class.java.getMethod(
            "setBackgroundGradientBlurParams", FloatArray::class.java, Int::class.javaPrimitiveType,
        ) }.getOrNull()
        if (setMode == null || setViewMode == null || (setParams == null && setRadius == null)) {
            module.log(Log.WARN, "HyperBackgroundBars", "BottomNavigator blur API unavailable package=${HookRuntime.targetPackage}")
            return
        }
        val prefs = HookRuntime.preferences()
        val appearance = HookRuntime.remotePreferences(SETTINGS_APPEARANCE_PREFERENCES)
        fun active(): Boolean = HookRuntime.targetPackage !in
            (appearance?.getStringSet(KEY_APP_SCOPE_DISABLED, emptySet()) ?: emptySet())
        fun clear(): Boolean = active() && prefs.getBoolean(BackgroundContract.UI_BOTTOM_CLEAR_ENABLED, false)
        fun enabled(): Boolean = active() && prefs.getBoolean(
            BackgroundContract.UI_BOTTOM_GRADIENT_ENABLED, false,
        ) && !clear()
        fun suppressNativeBackground(view: View) {
            if (enabled() || clear()) {
                // The private background refresh method writes the native
                // GradientDrawable directly, so setGradientBackground(false)
                // must be re-applied after every such refresh.
                runCatching { setGradient.invoke(view, false) }
            }
        }
        fun apply(view: View) {
            navigators[view] = Unit
            if (!enabled() || view.height <= 0 || view.width <= 0) {
                runCatching {
                    setSupport?.invoke(view, false)
                    setEnable?.invoke(view, false)
                    setType?.invoke(view, 0)
                    setViewMode.invoke(view, 0)
                    setMode.invoke(view, 0)
                }
                if (!active() || !clear()) runCatching { setGradient.invoke(view, true) }
                return
            }
            val strength = prefs.getInt(BackgroundContract.UI_BOTTOM_GRADIENT_STRENGTH, 10)
                .coerceIn(0, 100)
            val radius = min(strength * view.resources.displayMetrics.density, 400f)
            if (radius <= 0f) {
                setSupport?.invoke(view, false)
                setEnable?.invoke(view, false)
                setMode.invoke(view, 0)
                runCatching { setGradient.invoke(view, true) }
                return
            }
            setSupport?.invoke(view, true)
            setEnable?.invoke(view, true)
            setMode.invoke(view, 1)
            setViewMode.invoke(view, 1)
            setType?.invoke(view, 2)
            // BottomNavigator's native drawable is BOTTOM_TOP: transparent at the
            // upper edge and colored at the bottom. Match that direction for blur.
            if (setParams != null) {
                setParams.invoke(view, floatArrayOf(0f, 0f, 0f, 0f, view.height.toFloat(), radius), 1)
            } else {
                setRadius?.invoke(view, radius.toInt().coerceIn(0, 400))
            }
            // Do not set View.alpha here: BottomNavigator contains the actual
            // navigation items. Opacity is retained by the gradient settings and
            // the system blur node remains opaque to its children.
        }
        module.hook(setGradient).setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("dynamic-bottom-gradient:navigator-background").intercept { chain ->
                if (clear() || enabled()) chain.proceed(arrayOf<Any?>(false)) else chain.proceed()
            }
        val refreshBackground = runCatching {
            type.getDeclaredMethod("m25227T").apply { isAccessible = true }
        }.getOrNull()
        if (refreshBackground != null) {
            module.hook(refreshBackground).setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("dynamic-bottom-gradient:navigator-refresh").intercept { chain ->
                    val result = chain.proceed()
                    (chain.thisObject as? View)?.let { view -> suppressNativeBackground(view) }
                    result
                }
        }
        val layout = runCatching { type.getDeclaredMethod(
            "onLayout", Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
        ).apply { isAccessible = true } }.getOrNull()
        if (layout == null) {
            module.log(Log.WARN, "HyperBackgroundBars", "BottomNavigator onLayout unavailable package=${HookRuntime.targetPackage}")
            return
        }
        module.hook(layout).setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("dynamic-bottom-gradient:navigator-layout").intercept { chain ->
                val result = chain.proceed()
                (chain.thisObject as? View)?.let { view ->
                    suppressNativeBackground(view)
                    runCatching { apply(view) }
                }
                result
            }
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key in setOf(
                    BackgroundContract.UI_BOTTOM_GRADIENT_ENABLED,
                    BackgroundContract.UI_BOTTOM_GRADIENT_STRENGTH,
                    BackgroundContract.UI_BOTTOM_GRADIENT_OPACITY,
                    BackgroundContract.UI_BOTTOM_CLEAR_ENABLED,
                )
            ) {
                main.post {
                    synchronized(navigators) { navigators.keys.toList() }.forEach { view ->
                        runCatching { apply(view) }
                        view.invalidate()
                    }
                }
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        appearance?.registerOnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == KEY_APP_SCOPE_DISABLED) {
                main.post {
                    synchronized(navigators) { navigators.keys.toList() }.forEach { view ->
                        runCatching { apply(view) }
                        view.invalidate()
                    }
                }
            }
        }
        module.log(Log.INFO, "HyperBackgroundBars", "BottomNavigator gradient hook installed package=${HookRuntime.targetPackage}")
    }

    private fun discoverSplit(type: Class<*>, loader: ClassLoader): Pair<Field, Method>? {
        System.loadLibrary("dexkit")
        DexKitBridge.create(loader, false).use { bridge ->
            val data = bridge.getClassData(type) ?: return null
            val painter = data.methods.filter { method ->
                method.returnTypeName == "void" && method.paramTypeNames == listOf("android.graphics.Canvas") &&
                    method.invokes.any { it.className == "android.graphics.Canvas" && it.name == "drawRect" } &&
                    method.invokes.any { it.className == "android.view.View" && it.name == "getTop" }
            }.singleOrNull() ?: return null
            val onDraw = data.methods.singleOrNull { it.name == "onDraw" &&
                it.paramTypeNames == listOf("android.graphics.Canvas") } ?: return null
            val splitField = onDraw.usingFields.map { it.field }
                .filter { it.className == BAR && it.typeName == "boolean" }
                .distinctBy { it.descriptor }.singleOrNull() ?: return null
            return splitField.getFieldInstance(loader).apply { isAccessible = true } to
                painter.getMethodInstance(loader).apply { isAccessible = true }
        }
    }
}
