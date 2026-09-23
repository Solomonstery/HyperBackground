package com.ciallo.hyperbackground.appearance

import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
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
internal object SettingsCardBackgroundHook {
    private const val TAG = "HyperBackgroundCards"
    private data class Palette(
        val enabled: Boolean = false,
        val light: Int = DEFAULT_LIGHT_CARD_COLOR,
        val dark: Int = DEFAULT_DARK_CARD_COLOR,
        val mode: Int = CARD_BACKGROUND_COLOR,
        val lightFrost: Int = DEFAULT_LIGHT_FROST_COLOR,
        val darkFrost: Int = DEFAULT_DARK_FROST_COLOR,
        val lightBlur: Int = DEFAULT_CARD_BLUR,
        val darkBlur: Int = DEFAULT_CARD_BLUR,
        val lightGlass: SoftGlassParams = SoftGlassParams(),
        val darkGlass: SoftGlassParams = SoftGlassParams(),
        val darkFollowsLight: Boolean = false,
    )
    @Volatile private var palette = Palette()
    private val preferenceKeys = setOf(
        KEY_CUSTOM_CARD_ENABLED, KEY_LIGHT_CARD_COLOR, KEY_DARK_CARD_COLOR, KEY_CARD_BACKGROUND_MODE,
        KEY_LIGHT_FROST_COLOR, KEY_DARK_FROST_COLOR, KEY_LIGHT_CARD_BLUR, KEY_DARK_CARD_BLUR,
        KEY_LIGHT_SOFT_GLASS, KEY_DARK_SOFT_GLASS, KEY_CARD_DARK_FOLLOWS_LIGHT,
    )
    private var groupClipAvailable = false
    private val states = WeakHashMap<Any, State>()
    private val standaloneStates = Collections.synchronizedMap(WeakHashMap<View, StandaloneState>())
    private val standaloneWrite = ThreadLocal<Boolean>()
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private var preferences: SharedPreferences? = null
    private lateinit var module: XposedModule

    private class Access(
        val drawable: Field,
        val paint: Field?,
        val factory: Method,
        val outer: Field? = null,
        val getContext: Method? = null,
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
        var frost: SettingsCardFrostDrawable? = null
        var glass: SettingsSoftGlassDrawable? = null
        var replacement: Drawable? = null
        var applied = false
        var failed = false
    }

    private class StandaloneState(
        var original: Drawable?,
        val originalClipToOutline: Boolean,
        var originalCardColor: ColorStateList?,
    ) {
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
    }

    // Keep a strong reference: SharedPreferences holds its listeners weakly.
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key == null || key in preferenceKeys) {
            val updated = readPalette(prefs)
            if (updated != palette) {
                palette = updated
                handler.removeCallbacks(refresh)
                handler.post(refresh)
            }
        }
    }

    fun install(value: XposedModule, classLoader: ClassLoader, prefs: SharedPreferences) {
        module = value
        preferences?.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        preferences = prefs
        palette = readPalette(prefs)
        prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
        groupClipAvailable = runCatching { installGroupClip(classLoader) }
            .onFailure { module.log(Log.WARN, TAG, "Group material clip unavailable; using the selected tint", it) }
            .isSuccess
        module.log(Log.INFO, TAG, "Card material runtime: clip=$groupClipAvailable " +
            "bionicsApi=${SettingsSoftGlassDrawable.hasBionicsApi()}")
        // Isolate both paths: a missing MIUIX class must not disable the other one.
        runCatching { installRecycler(classLoader) }
            .onFailure { module.log(Log.WARN, TAG, "Recycler group color hook unavailable", it) }
        runCatching { installPreference(classLoader) }
            .onFailure { module.log(Log.WARN, TAG, "Preference group color hook unavailable", it) }
        runCatching { installStandaloneCards(classLoader) }
            .onFailure { module.log(Log.WARN, TAG, "Standalone Settings card hook unavailable", it) }
    }

    /** True when the exact custom card is owned by the new material router. */
    fun managesStandalone(view: View): Boolean = palette.enabled && standaloneTarget(view) != null

    private fun readPalette(prefs: SharedPreferences): Palette {
        val values = prefs.all
        return Palette(
            values[KEY_CUSTOM_CARD_ENABLED] as? Boolean ?: false,
            values[KEY_LIGHT_CARD_COLOR] as? Int ?: DEFAULT_LIGHT_CARD_COLOR,
            values[KEY_DARK_CARD_COLOR] as? Int ?: DEFAULT_DARK_CARD_COLOR,
            values[KEY_CARD_BACKGROUND_MODE] as? Int ?: CARD_BACKGROUND_COLOR,
            values[KEY_LIGHT_FROST_COLOR] as? Int ?: DEFAULT_LIGHT_FROST_COLOR,
            values[KEY_DARK_FROST_COLOR] as? Int ?: DEFAULT_DARK_FROST_COLOR,
            (values[KEY_LIGHT_CARD_BLUR] as? Int ?: DEFAULT_CARD_BLUR).coerceIn(0, 80),
            (values[KEY_DARK_CARD_BLUR] as? Int ?: DEFAULT_CARD_BLUR).coerceIn(0, 80),
            decodeSoftGlass(values[KEY_LIGHT_SOFT_GLASS] as? String),
            decodeSoftGlass(values[KEY_DARK_SOFT_GLASS] as? String),
            values[KEY_CARD_DARK_FOLLOWS_LIGHT] as? Boolean ?: false,
        )
    }

    private fun installGroupClip(classLoader: ClassLoader) {
        val type = classLoader.loadClass("miuix.recyclerview.card.base.BaseDecoration")
        val method = type.getDeclaredMethod(
            "clipDrawableRoundRect", Canvas::class.java, RectF::class.java, Path::class.java, Drawable::class.java,
        ).apply { isAccessible = true }
        module.hook(method).setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("settings-cards:material-clip").intercept { chain ->
                val material = chain.getArg(3) as? SettingsGroupMaterial
                if (material == null) {
                    chain.proceed()
                } else {
                    // MIUIX's saveLayerAlpha would isolate the card from the actual backdrop.
                    // Only our drawables bypass that layer, keeping the exact native group path.
                    material.drawGroup(chain.getArg(0) as Canvas, chain.getArg(1) as RectF, chain.getArg(2) as Path)
                    null
                }
            }
    }

    private fun installRecycler(classLoader: ClassLoader) {
        val type = classLoader.loadClass("miuix.recyclerview.card.CardItemDecoration")
        val factory = type.getDeclaredMethod("getGroupDrawable", Context::class.java).apply { isAccessible = true }
        val access = Access(requireNotNull(field(type, "mGroupDrawable")), field(type, "mPaint"), factory)
        // Never inject a frosted drawable unless its per-frame lifecycle is hooked.
        installDrawHooks(type, access, "calculateGroupRectAndDraw", "recycler")
        module.hook(factory).setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("settings-cards:recycler-fill").intercept { chain ->
                // Let the system resolve its native drawable first, so disabling can restore it.
                val original = chain.proceed()
                val owner = chain.thisObject ?: return@intercept original
                val context = chain.getArg(0) as? Context ?: return@intercept original
                val state = state(owner, access)
                update(owner, state, context, nativeResolved = true)
                if (state.applied) state.replacement else original
            }
        module.log(Log.INFO, TAG, "Installed RecyclerView group colors for light and dark themes")
    }

    private fun installPreference(classLoader: ClassLoader) {
        val type = classLoader.loadClass("miuix.preference.PreferenceFragment\$FrameDecoration")
        val factory = type.getDeclaredMethod("setCardDrawable").apply { isAccessible = true }
        val outer = requireNotNull(field(type, "this\$0"))
        val access = Access(
            requireNotNull(field(type, "mCardGroupBackground")),
            requireNotNull(field(type, "mPaint")),
            factory,
            outer,
            outer.type.getMethod("getContext").apply { isAccessible = true },
        )
        installDrawHooks(type, access, "calculateGroupRectAndDraw", "preference")
        module.hook(factory).setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("settings-cards:preference-fill").intercept { chain ->
                val result = chain.proceed()
                val owner = chain.thisObject ?: return@intercept result
                val context = fragmentContext(owner, access) ?: return@intercept result
                update(owner, state(owner, access), context, nativeResolved = true)
                result
            }
        module.log(Log.INFO, TAG, "Installed Preference group colors for light and dark themes")
    }

    /**
     * A few Settings 17 pages use ordinary LinearLayouts/CardViews instead of MIUIX group
     * decorations. Route only the verified resource ids through the same palette. Hooking the
     * framework attach dispatch also covers RecyclerView rows without scanning every screen.
     */
    private fun installStandaloneCards(classLoader: ClassLoader) {
        val attach = View::class.java.declaredMethods.firstOrNull {
            it.name == "dispatchAttachedToWindow" && it.parameterCount == 2
        } ?: error("View.dispatchAttachedToWindow not found")
        attach.isAccessible = true
        module.hook(attach).setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("settings-cards:standalone-attach").intercept { chain ->
                val result = chain.proceed()
                (chain.thisObject as? View)?.let(::applyStandalone)
                result
            }

        View::class.java.declaredMethods.firstOrNull {
            it.name == "dispatchDetachedFromWindow" && it.parameterCount == 0
        }?.apply { isAccessible = true }?.let { detach ->
            module.hook(detach).setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("settings-cards:standalone-detach").intercept { chain ->
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
            .setId("settings-cards:standalone-background").intercept { chain ->
                if (standaloneWrite.get() == true) return@intercept chain.proceed()
                val view = chain.thisObject as? View
                val result = chain.proceed()
                if (view != null && standaloneTarget(view) != null) {
                    val state = standaloneState(view)
                    state.original = view.background
                    state.applied = null
                    state.signature = null
                    if (palette.enabled && view.isAttachedToWindow) {
                        view.post { applyStandalone(view) }
                    }
                }
                result
            }

        // AndroidX CardView keeps its own RoundRectDrawable reference. Changing View.background
        // alone does not reliably replace that internal fill, and Bluetooth rebinds it through
        // setCardBackgroundColor. Observe those exact writes and re-apply our selected material.
        runCatching { installCardViewColorHooks(classLoader) }
            .onFailure { module.log(Log.WARN, TAG, "Bluetooth CardView color hook unavailable", it) }
        module.log(Log.INFO, TAG, "Installed standalone card material routing")
    }

    private fun installCardViewColorHooks(classLoader: ClassLoader) {
        val type = classLoader.loadClass("androidx.cardview.widget.CardView")
        val methods = type.declaredMethods.filter {
            it.name == "setCardBackgroundColor" && it.parameterCount == 1
        }
        check(methods.isNotEmpty()) { "CardView.setCardBackgroundColor not found" }
        methods.forEachIndexed { index, method ->
            method.isAccessible = true
            module.hook(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("settings-cards:bluetooth-card-color-$index").intercept { chain ->
                    if (standaloneWrite.get() == true) return@intercept chain.proceed()
                    val view = chain.thisObject as? View
                    val result = chain.proceed()
                    if (view != null && standaloneTarget(view) == BLUETOOTH_CARD_ID) {
                        val state = standaloneState(view)
                        state.original = view.background
                        state.originalCardColor = cardBackgroundColor(view)
                        state.applied = null
                        state.signature = null
                        if (palette.enabled && view.isAttachedToWindow) {
                            view.post { applyStandalone(view) }
                        }
                    }
                    result
                }
        }
    }

    private fun applyStandalone(view: View) {
        val target = standaloneTarget(view) ?: return
        val state = standaloneState(view)
        val colors = palette
        if (!colors.enabled) {
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
                        SettingsCardFrostDrawable.applyToView(
                            view,
                            if (dark) colors.darkBlur else colors.lightBlur,
                            density,
                        )
                    }
                    if (ready) state.material = STANDALONE_MATERIAL_FROST
                    ready
                }
            }
            CARD_BACKGROUND_SOFT_GLASS -> {
                val color = if (dark) colors.darkFrost else colors.lightFrost
                val config = if (dark) colors.darkGlass else colors.lightGlass
                val tintColor = SettingsSoftGlassDrawable.materialTintColor(color, config)
                if (!prepareStandaloneBackground(view, state, tintColor, true)) false else {
                    val ready = withStandaloneWrite {
                        SettingsSoftGlassDrawable.applyToView(
                            view,
                            config,
                            density,
                        )
                    }
                    if (ready) state.material = STANDALONE_MATERIAL_GLASS
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

    private fun clearStandaloneMaterial(view: View, state: StandaloneState) {
        when (state.material) {
            STANDALONE_MATERIAL_FROST -> SettingsCardFrostDrawable.clearFromView(view)
            STANDALONE_MATERIAL_GLASS -> SettingsSoftGlassDrawable.clearFromView(view)
        }
        state.material = STANDALONE_MATERIAL_NONE
    }

    private fun standaloneState(view: View): StandaloneState = synchronized(standaloneStates) {
        standaloneStates.getOrPut(view) {
            StandaloneState(view.background, view.clipToOutline, cardBackgroundColor(view))
        }
    }

    private fun standaloneTarget(view: View): String? {
        if (view.context.packageName != SETTINGS_PACKAGE || view.id == View.NO_ID || view.id == 0) return null
        val name = runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull() ?: return null
        if (name !in STANDALONE_CARD_IDS) return null
        if (name != BLUETOOTH_CARD_ID) return name

        // view_corner is generic; the Bluetooth row is the CardView that owns
        // view_high_light_root in preference_bt_icon_corner.
        if (!view.javaClass.name.contains("CardView")) return null
        val contentId = view.resources.getIdentifier(BLUETOOTH_CARD_CONTENT_ID, "id", SETTINGS_PACKAGE)
        return if (contentId != 0 && (view as? ViewGroup)?.findViewById<View>(contentId) != null) name else null
    }

    private fun cloneAndTint(view: View, source: Drawable?, color: Int): Drawable? =
        cloneDrawable(view, source)?.let { drawable ->
            runCatching {
                // The legacy light-card hook may already have lowered the source alpha.
                // A selected card style owns its complete ARGB value, so do not multiply
                // that value by a stale drawable alpha when cloning the native shape.
                drawable.alpha = 255
                drawable.setTint(color)
                drawable
            }.getOrNull()
        }

    private fun prepareStandaloneBackground(
        view: View,
        state: StandaloneState,
        color: Int,
        clipToOutline: Boolean,
    ): Boolean {
        if (standaloneTarget(view) == BLUETOOTH_CARD_ID) {
            val updated = withStandaloneWrite { setCardBackgroundColor(view, color) }
            if (updated) view.clipToOutline = clipToOutline
            return updated
        }
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

    private fun installDrawHooks(type: Class<*>, access: Access, name: String, id: String) {
        // Resolve signatures once; RecyclerView itself belongs to the target application's loader.
        val methods = generateSequence<Class<*>>(type) { it.superclass }.flatMap { it.declaredMethods.asSequence() }
            .filter { it.name == name && !Modifier.isAbstract(it.modifiers) }
            .distinctBy { it.parameterTypes.toList() }.toList()
        check(methods.isNotEmpty()) { "No group draw method on ${type.name}" }
        for ((index, method) in methods.withIndex()) {
            val hostIndex = method.parameterTypes.indexOfFirst { View::class.java.isAssignableFrom(it) }
            method.isAccessible = true
            module.hook(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("settings-cards:$id-draw-$index").intercept { chain ->
                    val owner = chain.thisObject
                    var frost: SettingsCardFrostDrawable? = null
                    var glass: SettingsSoftGlassDrawable? = null
                    if (owner != null && type.isInstance(owner)) {
                        val state = state(owner, access)
                        val host = if (hostIndex >= 0) chain.getArg(hostIndex) as? View else null
                        if (host != null && state.host?.get() !== host) state.host = WeakReference(host)
                        val context = host?.context ?: state.context?.get() ?: fragmentContext(owner, access)
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
            2 -> "Card material branch: soft glass (${SettingsSoftGlassDrawable.bionicsDiagnostics(context)})"
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
            if (!colors.enabled) {
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
                        if (access.factory.parameterCount == 1) access.factory.invoke(owner, context)
                        else access.factory.invoke(owner)
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
                && SettingsSoftGlassDrawable.isBionicsActive(context)
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
                    val glass = state.glass ?: SettingsSoftGlassDrawable(context) { error ->
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
                    val frost = state.frost ?: SettingsCardFrostDrawable(context) { error ->
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

    private fun fragmentContext(owner: Any, access: Access): Context? = runCatching {
        val outer = access.outer?.get(owner) ?: return@runCatching null
        access.getContext?.invoke(outer) as? Context
    }.getOrNull()

    private fun field(type: Class<*>, name: String): Field? {
        var current: Class<*>? = type
        while (current != null) {
            try {
                return current.getDeclaredField(name).apply { isAccessible = true }
            } catch (_: NoSuchFieldException) {
                current = current.superclass
            }
        }
        return null
    }

    private const val SETTINGS_PACKAGE = "com.android.settings"
    private const val BLUETOOTH_CARD_ID = "view_corner"
    private const val BLUETOOTH_CARD_CONTENT_ID = "view_high_light_root"
    private const val STANDALONE_MATERIAL_NONE = 0
    private const val STANDALONE_MATERIAL_FROST = 1
    private const val STANDALONE_MATERIAL_GLASS = 2
    private val STANDALONE_CARD_IDS = setOf(
        "lock_screen_notification_card",
        "float_notification_card",
        "show_app_badge_card",
        "device_basic_layout",
        "device_params",
        BLUETOOTH_CARD_ID,
    )
}
