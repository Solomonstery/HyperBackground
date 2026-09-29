package com.ciallo.hyperbackground.dynamic.search

import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.PorterDuff
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import com.ciallo.hyperbackground.HookRuntime
import com.ciallo.hyperbackground.appearance.CARD_BACKGROUND_SOFT_GLASS
import com.ciallo.hyperbackground.appearance.CARD_BACKGROUND_COLOR
import com.ciallo.hyperbackground.appearance.CARD_BACKGROUND_FROST
import com.ciallo.hyperbackground.appearance.ComponentKeys
import com.ciallo.hyperbackground.appearance.KEY_APP_COMPONENT_DISABLED
import com.ciallo.hyperbackground.appearance.KEY_APP_SCOPE_DISABLED
import com.ciallo.hyperbackground.appearance.KEY_CARD_BACKGROUND_MODE
import com.ciallo.hyperbackground.appearance.KEY_CARD_DARK_FOLLOWS_LIGHT
import com.ciallo.hyperbackground.appearance.KEY_COMPONENT_SEARCH
import com.ciallo.hyperbackground.appearance.KEY_CUSTOM_CARD_ENABLED
import com.ciallo.hyperbackground.appearance.KEY_DARK_FROST_COLOR
import com.ciallo.hyperbackground.appearance.KEY_DARK_CARD_BLUR
import com.ciallo.hyperbackground.appearance.KEY_DARK_CARD_COLOR
import com.ciallo.hyperbackground.appearance.KEY_DARK_SOFT_GLASS
import com.ciallo.hyperbackground.appearance.KEY_LIGHT_FROST_COLOR
import com.ciallo.hyperbackground.appearance.KEY_LIGHT_CARD_BLUR
import com.ciallo.hyperbackground.appearance.KEY_LIGHT_CARD_COLOR
import com.ciallo.hyperbackground.dynamic.material.DynamicFrostDrawable
import com.ciallo.hyperbackground.appearance.KEY_LIGHT_SOFT_GLASS
import com.ciallo.hyperbackground.dynamic.material.DynamicMaterialPalette
import com.ciallo.hyperbackground.dynamic.material.DynamicSoftGlassDrawable
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap

/** Own MIUIX search surfaces independently of rounded standalone cards. */
internal object DynamicSearchMaterialHook {
    private const val TAG = "HyperBackgroundSearch"
    private const val STUB = "miuix.appcompat.app.SearchModeStubView"
    private const val ACTION = "miuix.appcompat.internal.app.widget.SearchActionModeView"
    private const val MATERIAL = "miuix.appcompat.app.SearchViewMaterialImpl"

    private data class Surface(val original: Drawable, var replacement: Drawable? = null, var mode: Int = -1)
    private val tracked = Collections.synchronizedMap(WeakHashMap<View, Surface>())
    private val owners = Collections.synchronizedMap(WeakHashMap<View, Unit>())
    private val nativeMaterials = Collections.synchronizedMap(WeakHashMap<View, Pair<WeakReference<Any>, WeakReference<View>>>())
    private val restoringNative = ThreadLocal<Boolean>()
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var palette = DynamicMaterialPalette()
    private var preferences: SharedPreferences? = null
    private val keys = setOf(
        KEY_CUSTOM_CARD_ENABLED, KEY_CARD_BACKGROUND_MODE, KEY_COMPONENT_SEARCH,
        KEY_CARD_DARK_FOLLOWS_LIGHT, KEY_LIGHT_FROST_COLOR, KEY_DARK_FROST_COLOR,
        KEY_LIGHT_CARD_COLOR, KEY_DARK_CARD_COLOR, KEY_LIGHT_CARD_BLUR, KEY_DARK_CARD_BLUR,
        KEY_LIGHT_SOFT_GLASS, KEY_DARK_SOFT_GLASS,
        KEY_APP_SCOPE_DISABLED, KEY_APP_COMPONENT_DISABLED,
    )
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key == null || key in keys) {
            palette = DynamicMaterialPalette.read(prefs)
            handler.post {
                synchronized(owners) { owners.keys.toList() }.forEach { owner ->
                    if (owner.isAttachedToWindow) applyChildren(owner)
                }
            }
        }
    }

    fun install(module: XposedModule, loader: ClassLoader, prefs: SharedPreferences) {
        val stub = Class.forName(STUB, false, loader)
        val action = Class.forName(ACTION, false, loader)
        val attach = ViewGroup::class.java.declaredMethods.first {
            it.name == "dispatchAttachedToWindow" && it.parameterCount == 2
        }.apply { isAccessible = true }
        module.hook(attach).setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("dynamic-search:owner-attached").intercept { chain ->
                val result = chain.proceed()
                val owner = chain.thisObject as? View
                if (owner != null && (stub.isInstance(owner) || action.isInstance(owner))) watch(owner)
                result
            }

        // MIUIX overwrites the shader during floating/collapsed transitions. Run after its
        // per-view material setter, still before the frame is drawn.
        val material = Class.forName(MATERIAL, false, loader)
        val applyNative = material.getDeclaredMethod("applyMaterialEffects", View::class.java)
            .apply { isAccessible = true }
        val nativeEnabled = material.getMethod("isMaterialEnabled")
        module.hook(applyNative).setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("dynamic-search:native-material").intercept { chain ->
                val result = chain.proceed()
                val view = chain.getArg(0) as? View ?: return@intercept result
                val owner = findOwner(view, stub, action)
                if (owner != null) {
                    watch(owner)
                    ownedChildren(owner).forEach { surface ->
                        chain.thisObject?.let {
                            nativeMaterials[surface] = WeakReference(it) to WeakReference(view)
                        }
                    }
                    if (restoringNative.get() != true) applyChildren(owner)
                }
                result
            }
        val clearNative = material.getDeclaredMethod("clearMaterialEffects", View::class.java)
            .apply { isAccessible = true }
        module.hook(clearNative).setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("dynamic-search:native-clear").intercept { chain ->
                val view = chain.getArg(0) as? View
                val owner = view?.let { findOwner(it, stub, action) }
                if (owner != null) ownedChildren(owner).forEach { restore(it, false) }
                chain.proceed()
            }
        reapplyNative = refresh@{ surface ->
            val (materialRef, targetRef) = nativeMaterials[surface] ?: return@refresh
            val instance = materialRef.get() ?: return@refresh
            val target = targetRef.get() ?: return@refresh
            if (nativeEnabled.invoke(instance) == true) {
                restoringNative.set(true)
                try {
                    applyNative.invoke(instance, target)
                } finally {
                    restoringNative.remove()
                }
            }
        }
        preferences?.unregisterOnSharedPreferenceChangeListener(listener)
        preferences = prefs
        palette = DynamicMaterialPalette.read(prefs)
        prefs.registerOnSharedPreferenceChangeListener(listener)
        module.log(Log.INFO, TAG, "MIUIX search material hooks installed")
    }

    private fun applyChildren(owner: View) {
        ownedChildren(owner).forEach(::apply)
    }

    private var reapplyNative: ((View) -> Unit)? = null

    private fun watch(owner: View) {
        if (owners.put(owner, Unit) != null) return
        owner.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                val observer = v.viewTreeObserver
                observer.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                    override fun onPreDraw(): Boolean {
                        if (observer.isAlive) observer.removeOnPreDrawListener(this)
                        applyChildren(v)
                        return true
                    }
                })
            }
            override fun onViewDetachedFromWindow(v: View) {
                ownedChildren(v).forEach { child ->
                    nativeMaterials.remove(child)
                    restore(child, false)
                }
            }
        })
        val observer = owner.viewTreeObserver
        if (observer.isAlive) observer.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (observer.isAlive) observer.removeOnPreDrawListener(this)
                applyChildren(owner)
                return true
            }
        })
    }

    private fun ownedChildren(owner: View): List<View> {
        owner as? ViewGroup ?: return emptyList()
        // Both SearchModeStubView and SearchActionModeView expose their material parent as a
        // direct child. The painted surface can be that child or one level below it.
        val parent = runCatching {
            owner.javaClass.getMethod("getMaterialTargetParent").invoke(owner) as? ViewGroup
        }.getOrNull() ?: return emptyList()
        val result = ArrayList<View>(1)
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            if (isSearchSurface(child)) result.add(child)
        }
        if (result.isEmpty() && isSearchSurface(parent)) result.add(parent)
        return result
    }

    private fun isSearchSurface(view: View): Boolean =
        view is ViewGroup && view.background != null &&
            view.findViewById<View>(android.R.id.input) != null

    private fun findOwner(view: View, stub: Class<*>, action: Class<*>): View? {
        var current: View? = view
        repeat(4) {
            val candidate = current ?: return null
            if (stub.isInstance(candidate) || action.isInstance(candidate)) return candidate
            current = candidate.parent as? View
        }
        return null
    }

    private fun apply(view: View) {
        val config = palette
        if (!config.enabledFor(HookRuntime.targetPackage, ComponentKeys.SEARCH)) {
            restore(view)
            return
        }
        if (!view.isAttachedToWindow) return
        val night = view.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        val dark = night && !config.darkFollowsLight
        val current = view.background ?: return
        val previous = tracked[view]
        val state = if (previous == null || current !== previous.replacement && current !== previous.original) {
            if (previous != null) clearEffects(view, previous.mode)
            Surface(current).also { tracked[view] = it }
        } else previous
        if (state.mode != config.mode) {
            clearEffects(view, state.mode)
            if (state.mode == CARD_BACKGROUND_SOFT_GLASS) state.original.setTintList(null)
        }
        val color = if (dark) config.darkFrost else config.lightFrost
        val density = view.resources.displayMetrics.density
        when (config.mode) {
            CARD_BACKGROUND_SOFT_GLASS -> {
                if (view.background === state.replacement) view.background = state.original
                state.replacement = null
                if (view.isHardwareAccelerated && DynamicSoftGlassDrawable.applyToView(
                        view, color, if (dark) config.darkGlass else config.lightGlass, density,
                    )
                ) state.mode = config.mode else {
                    DynamicSoftGlassDrawable.clearFromView(view)
                    state.mode = -1
                }
            }
            CARD_BACKGROUND_FROST, CARD_BACKGROUND_COLOR -> {
                val fill = if (config.mode == CARD_BACKGROUND_COLOR) {
                    if (dark) config.dark else config.light
                } else color
                val replacement = tintedBackground(state.original, view, fill)
                view.background = replacement
                state.replacement = replacement
                state.mode = config.mode
                if (config.mode == CARD_BACKGROUND_FROST &&
                    (!view.isHardwareAccelerated || !DynamicFrostDrawable.applyToView(
                        view, if (dark) config.darkBlur else config.lightBlur, density,
                    ))
                ) DynamicFrostDrawable.clearFromView(view)
            }
        }
    }

    private fun tintedBackground(original: Drawable, view: View, color: Int): Drawable {
        val copy = runCatching {
            original.constantState?.newDrawable(view.resources, view.context.theme)?.mutate()
        }.getOrNull() ?: return ColorDrawable(color)
        val childSet = runCatching {
            if (copy.javaClass.name != "miuix.smooth.SmoothContainerDrawable2") return@runCatching false
            copy.javaClass.getMethod("setChildDrawable", Drawable::class.java)
                .invoke(copy, ColorDrawable(color))
            true
        }.getOrDefault(false)
        if (!childSet) copy.setColorFilter(color, PorterDuff.Mode.SRC_IN)
        return copy
    }

    private fun clearEffects(view: View, mode: Int) {
        when (mode) {
            CARD_BACKGROUND_SOFT_GLASS -> DynamicSoftGlassDrawable.clearFromView(view)
            CARD_BACKGROUND_FROST -> DynamicFrostDrawable.clearFromView(view)
        }
    }

    private fun restore(view: View, restoreNative: Boolean = true) {
        val state = tracked.remove(view) ?: return
        clearEffects(view, state.mode)
        if (view.background === state.replacement) view.background = state.original
        if (state.mode == CARD_BACKGROUND_SOFT_GLASS) state.original.setTintList(null)
        if (restoreNative && view.isAttachedToWindow) {
            runCatching { reapplyNative?.invoke(view) }
        }
    }
}
