package com.ciallo.hyperbackground.appearance

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.WeakHashMap

/**
 * Settings 17 draws whole preference groups through MIUIX ItemDecorations.
 * Replace only their fill; MIUIX still computes the group bounds, corners and touch feedback.
 * Read remote preferences on change, never through a ContentProvider from a drawing callback.
 */
internal object SettingsCardBackgroundHook {
    private const val TAG = "HyperBackgroundCards"
    private data class Palette(val enabled: Boolean, val light: Int, val dark: Int)
    @Volatile private var palette = Palette(false, DEFAULT_LIGHT_CARD_COLOR, DEFAULT_DARK_CARD_COLOR)
    private val states = WeakHashMap<Any, State>()
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
    }

    private class State(val access: Access) {
        var context: WeakReference<Context>? = null
        var host: WeakReference<View>? = null
        var original: Drawable? = null
        var originalPaintColor: Int? = null
        var fill: ColorDrawable? = null
        var applied = false
        var failed = false
    }

    private val refresh = Runnable {
        val tracked = synchronized(states) { states.entries.map { it.key to it.value } }
        for ((owner, state) in tracked) {
            val context = state.context?.get() ?: continue
            update(owner, state, context)
            state.host?.get()?.invalidate()
        }
    }

    // Keep a strong reference: SharedPreferences holds its listeners weakly.
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key == null || key == KEY_CUSTOM_CARD_ENABLED || key == KEY_LIGHT_CARD_COLOR || key == KEY_DARK_CARD_COLOR) {
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
        // Isolate both paths: a missing MIUIX class must not disable the other one.
        runCatching { installRecycler(classLoader) }
            .onFailure { module.log(Log.WARN, TAG, "Recycler group color hook unavailable", it) }
        runCatching { installPreference(classLoader) }
            .onFailure { module.log(Log.WARN, TAG, "Preference group color hook unavailable", it) }
    }

    private fun readPalette(prefs: SharedPreferences): Palette {
        val values = prefs.all
        return Palette(
            values[KEY_CUSTOM_CARD_ENABLED] as? Boolean ?: false,
            values[KEY_LIGHT_CARD_COLOR] as? Int ?: DEFAULT_LIGHT_CARD_COLOR,
            values[KEY_DARK_CARD_COLOR] as? Int ?: DEFAULT_DARK_CARD_COLOR,
        )
    }

    private fun installRecycler(classLoader: ClassLoader) {
        val type = classLoader.loadClass("miuix.recyclerview.card.CardItemDecoration")
        val factory = type.getDeclaredMethod("getGroupDrawable", Context::class.java).apply { isAccessible = true }
        val access = Access(requireNotNull(field(type, "mGroupDrawable")), field(type, "mPaint"), factory)
        module.hook(factory).setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("settings-cards:recycler-fill").intercept { chain ->
                // Let the system resolve its native drawable first, so disabling can restore it.
                val original = chain.proceed()
                val owner = chain.thisObject ?: return@intercept original
                val context = chain.getArg(0) as? Context ?: return@intercept original
                val state = state(owner, access)
                update(owner, state, context, nativeResolved = true)
                if (state.applied) state.fill else original
            }
        installDrawHooks(type, access, "calculateGroupRectAndDraw", "recycler")
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
        module.hook(factory).setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("settings-cards:preference-fill").intercept { chain ->
                val result = chain.proceed()
                val owner = chain.thisObject ?: return@intercept result
                val context = fragmentContext(owner, access) ?: return@intercept result
                update(owner, state(owner, access), context, nativeResolved = true)
                result
            }
        installDrawHooks(type, access, "onDraw", "preference")
        module.log(Log.INFO, TAG, "Installed Preference group colors for light and dark themes")
    }

    private fun installDrawHooks(type: Class<*>, access: Access, name: String, id: String) {
        // Resolve signatures once; RecyclerView itself belongs to the target application's loader.
        val methods = generateSequence<Class<*>>(type) { it.superclass }.flatMap { it.declaredMethods.asSequence() }
            .filter { it.name == name }
            .distinctBy { it.parameterTypes.toList() }.toList()
        for ((index, method) in methods.withIndex()) {
            val hostIndex = method.parameterTypes.indexOfFirst { View::class.java.isAssignableFrom(it) }
            method.isAccessible = true
            module.hook(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("settings-cards:$id-draw-$index").intercept { chain ->
                    val owner = chain.thisObject
                    if (owner != null && type.isInstance(owner)) {
                        val state = state(owner, access)
                        val host = if (hostIndex >= 0) chain.getArg(hostIndex) as? View else null
                        if (host != null && state.host?.get() !== host) state.host = WeakReference(host)
                        val context = host?.context ?: state.context?.get() ?: fragmentContext(owner, access)
                        if (context != null) update(owner, state, context)
                    }
                    chain.proceed()
                }
        }
    }

    private fun state(owner: Any, access: Access): State = synchronized(states) {
        states.getOrPut(owner) { State(access) }
    }

    private fun update(owner: Any, state: State, context: Context, nativeResolved: Boolean = false) {
        val access = state.access
        if (state.failed) return
        try {
            if (state.context?.get() !== context) state.context = WeakReference(context)
            val current = access.drawable.get(owner) as? Drawable
            val paint = access.paint?.get(owner) as? Paint
            if (nativeResolved || !state.applied || current !== state.fill) {
                // Never save our own replacement as the system's original.
                if (current !== state.fill || state.fill == null) {
                    state.original = current
                    state.originalPaintColor = paint?.color
                }
            }
            val colors = palette
            if (!colors.enabled) {
                if (state.applied) {
                    state.applied = false
                    if (current === state.fill) {
                        access.drawable.set(owner, state.original)
                        state.originalPaintColor?.let { paint?.color = it }
                        // Re-resolve the current theme instead of restoring hardcoded white/transparent.
                        // Our factory hook now observes enabled=false, so this does not recurse.
                        if (access.factory.parameterCount == 1) access.factory.invoke(owner, context)
                        else access.factory.invoke(owner)
                    }
                }
                return
            }
            val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            val color = if (night) colors.dark else colors.light
            val fill = state.fill ?: ColorDrawable(color).also { state.fill = it }
            if (fill.color != color) fill.color = color
            if (current !== fill) access.drawable.set(owner, fill)
            if (paint != null && paint.color != color) paint.color = color
            state.applied = true
        } catch (error: Throwable) {
            // Fail open for this instance, without repeated reflection failures during scrolling.
            state.failed = true
            runCatching {
                if (access.drawable.get(owner) === state.fill) access.drawable.set(owner, state.original)
                state.originalPaintColor?.let { color -> (access.paint?.get(owner) as? Paint)?.color = color }
            }
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
}
