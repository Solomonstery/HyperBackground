package com.ciallo.hyperbackground.dynamic.popup

import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.RenderNode
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ListView
import com.ciallo.hyperbackground.HookRuntime
import com.ciallo.hyperbackground.appearance.ComponentKeys
import com.ciallo.hyperbackground.appearance.KEY_APP_COMPONENT_DISABLED
import com.ciallo.hyperbackground.appearance.KEY_APP_SCOPE_DISABLED
import com.ciallo.hyperbackground.appearance.KEY_CARD_DARK_FOLLOWS_LIGHT
import com.ciallo.hyperbackground.appearance.KEY_CARD_BACKGROUND_MODE
import com.ciallo.hyperbackground.appearance.KEY_COMPONENT_POPUP
import com.ciallo.hyperbackground.appearance.KEY_CUSTOM_CARD_ENABLED
import com.ciallo.hyperbackground.appearance.KEY_DARK_CARD_COLOR
import com.ciallo.hyperbackground.appearance.KEY_DARK_CARD_BLUR
import com.ciallo.hyperbackground.appearance.KEY_DARK_FROST_COLOR
import com.ciallo.hyperbackground.appearance.KEY_DARK_SOFT_GLASS
import com.ciallo.hyperbackground.appearance.KEY_LIGHT_CARD_COLOR
import com.ciallo.hyperbackground.appearance.KEY_LIGHT_CARD_BLUR
import com.ciallo.hyperbackground.appearance.KEY_LIGHT_FROST_COLOR
import com.ciallo.hyperbackground.appearance.KEY_LIGHT_SOFT_GLASS
import com.ciallo.hyperbackground.appearance.CARD_BACKGROUND_SOFT_GLASS
import com.ciallo.hyperbackground.appearance.CARD_BACKGROUND_FROST
import com.ciallo.hyperbackground.dynamic.material.DynamicFrostDrawable
import com.ciallo.hyperbackground.dynamic.material.DynamicMaterialPalette
import com.ciallo.hyperbackground.dynamic.material.DynamicSoftGlassDrawable
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Modifier
import java.util.WeakHashMap

/** Apply popup color to the rounded outer surface, not the list inside it. */
internal object DynamicPopupMaterialHook {
    private const val TAG = "HyperBackgroundCards"
    private const val POPUP_VIEW = "miuix.popupwidget.widget.PopupView"
    private const val LIST_POPUP = "miuix.popupwidget.widget.PopupWindow"
    private const val HYPER_POPUP = "miuix.appcompat.widget.HyperPopupWindow"
    private const val DIALOG_PANEL = "miuix.appcompat.internal.widget.DialogParentPanel2"
    private const val SMOOTH_FRAME = "miuix.smooth.SmoothFrameLayout2"
    private const val SPRING_BACK = "miuix.springback.view.SpringBackLayout"

    private lateinit var module: XposedModule
    @Volatile private var palette = DynamicMaterialPalette()
    private val main = Handler(Looper.getMainLooper())
    private var preferences: SharedPreferences? = null
    private val originals = WeakHashMap<View, Drawable?>()
    private val replacements = WeakHashMap<View, Drawable>()
    private val glass = WeakHashMap<View, Boolean>()
    private val frost = WeakHashMap<View, Boolean>()
    private val outlines = WeakHashMap<View, ViewOutlineProvider?>()
    private val originalClipping = WeakHashMap<View, Boolean>()
    private val outlineListeners = WeakHashMap<View, View.OnLayoutChangeListener>()
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key == null || key in setOf(
                KEY_CUSTOM_CARD_ENABLED, KEY_COMPONENT_POPUP, KEY_LIGHT_CARD_COLOR,
                KEY_DARK_CARD_COLOR, KEY_CARD_DARK_FOLLOWS_LIGHT, KEY_CARD_BACKGROUND_MODE,
                KEY_LIGHT_FROST_COLOR, KEY_DARK_FROST_COLOR,
                KEY_LIGHT_CARD_BLUR, KEY_DARK_CARD_BLUR,
                KEY_LIGHT_SOFT_GLASS, KEY_DARK_SOFT_GLASS,
                KEY_APP_SCOPE_DISABLED, KEY_APP_COMPONENT_DISABLED,
            )
        ) {
            palette = DynamicMaterialPalette.read(prefs)
            main.post { originals.keys.toList().forEach { applyBackground(it, true) } }
        }
    }

    fun install(value: XposedModule, loader: ClassLoader, prefs: SharedPreferences) {
        module = value
        preferences?.unregisterOnSharedPreferenceChangeListener(listener)
        preferences = prefs
        palette = DynamicMaterialPalette.read(prefs)
        prefs.registerOnSharedPreferenceChangeListener(listener)
        runCatching { installPopupViewHook(loader) }
            .onFailure { module.log(Log.WARN, TAG, "PopupView hook unavailable", it) }
        runCatching { installDropDownPopupHook(loader) }
            .onFailure { module.log(Log.WARN, TAG, "MIUIX drop-down popup hook unavailable", it) }
        runCatching { installHyperPopupHook(loader) }
            .onFailure { module.log(Log.WARN, TAG, "HyperPopupWindow hook unavailable", it) }
        runCatching { installDialogPanelHook(loader) }
            .onFailure { module.log(Log.WARN, TAG, "DialogParentPanel2 hook unavailable", it) }
        runCatching { installListSurfaceHook(loader) }
            .onFailure { module.log(Log.WARN, TAG, "MIUIX list popup hook unavailable", it) }
        module.log(Log.INFO, TAG, "MIUIX popup hook installed for ${loader.javaClass.name}")
    }

    private fun installPopupViewHook(loader: ClassLoader) {
        val type = Class.forName(POPUP_VIEW, false, loader)
        val contentField = runCatching {
            type.getDeclaredField("mContentView").apply { isAccessible = true }
        }.getOrNull()
        val contentGetter = if (contentField == null) type.getMethod("getContentView") else null
        fun content(popup: View): View? = runCatching {
            (contentField?.get(popup) ?: contentGetter?.invoke(popup)) as? View
        }.getOrNull()
        type.declaredConstructors.forEachIndexed { index, ctor ->
            ctor.isAccessible = true
            module.hook(ctor).setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("dynamic-cards:popupview-ctor-$index").intercept { chain ->
                    val result = chain.proceed()
                    (chain.thisObject as? View)?.let { popup ->
                        content(popup)?.let { applyBackground(it, true) }
                    }
                    result
                }
        }
        // Keep MIUIX's pass-window blur setup on the menu layer. Our glass replaces its
        // surface after prepareShow, but cannot sample the backdrop if setup is skipped.
        val show = runCatching { type.getMethod("prepareShow", View::class.java) }.getOrNull()
            ?: type.declaredMethods.singleOrNull { method ->
                Modifier.isPublic(method.modifiers) && !Modifier.isStatic(method.modifiers) &&
                    method.returnType == Boolean::class.javaPrimitiveType &&
                    method.parameterTypes.contentEquals(arrayOf(View::class.java))
            } ?: return
        show.let { method ->
            module.hook(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("dynamic-cards:popupview-show").intercept { chain ->
                    val result = chain.proceed()
                    if (result == true) (chain.thisObject as? View)?.let { popup ->
                        content(popup)?.let { applyBackground(it) }
                    }
                    result
                }
        }
    }

    private fun installDialogPanelHook(loader: ClassLoader) {
        val type = Class.forName(DIALOG_PANEL, false, loader)
        type.declaredConstructors.forEachIndexed { index, ctor ->
            ctor.isAccessible = true
            module.hook(ctor).setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("dynamic-cards:dialogpanel-ctor-$index").intercept { chain ->
                    val result = chain.proceed()
                    (chain.thisObject as? View)?.let { watchFirstDraw(it) }
                    result
                }
        }
        val draw = type.getDeclaredMethod("draw", Canvas::class.java).apply { isAccessible = true }
        module.hook(draw).setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("dynamic-cards:dialogpanel-draw").intercept { chain ->
                (chain.thisObject as? View)?.let { panel ->
                    if (panel.isAttachedToWindow) applyBackground(panel)
                }
                chain.proceed()
            }
    }

    private fun installDropDownPopupHook(loader: ClassLoader) {
        val type = Class.forName(LIST_POPUP, false, loader)
        val content = type.getDeclaredField("mContentView").apply { isAccessible = true }
        val material = type.getDeclaredMethod("isMaterialEnabled").apply { isAccessible = true }
        module.hook(material).setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("dynamic-cards:dropdown-native-material").intercept { chain ->
                val result = chain.proceed()
                val config = palette
                val surface = runCatching { content.get(chain.thisObject) as? View }.getOrNull()
                if (surface != null && isListPopup(surface) &&
                    config.enabledFor(HookRuntime.targetPackage, ComponentKeys.POPUP) &&
                    config.mode == CARD_BACKGROUND_SOFT_GLASS
                ) true else result
            }
    }

    private fun installHyperPopupHook(loader: ClassLoader) {
        val type = Class.forName(HYPER_POPUP, false, loader)
        val container = type.getMethod("getContainerView")
        // HyperPopupWindow also sets up the backdrop in applyMaterial. Let it run before
        // replacing the surface, rather than suppressing its blur and leaving a flat fill.
        type.getMethod("show", View::class.java).let { method ->
            module.hook(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("dynamic-cards:hyperpopup-show").intercept { chain ->
                    val result = chain.proceed()
                    (chain.thisObject as? android.widget.PopupWindow)?.let { popup ->
                        (container.invoke(popup) as? View)?.let { applyBackground(it, true) }
                    }
                    result
                }
        }
    }

    private fun installListSurfaceHook(loader: ClassLoader) {
        val frame = Class.forName(SMOOTH_FRAME, false, loader)
        val draw = frame.getDeclaredMethod("draw", Canvas::class.java).apply { isAccessible = true }
        module.hook(draw).setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("dynamic-cards:popup-list-draw").intercept { chain ->
                val view = chain.thisObject as? View
                if (view != null && isListPopup(view)) applyBackground(view)
                chain.proceed()
            }
    }

    private fun watchFirstDraw(view: View) {
        view.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                v: View, left: Int, top: Int, right: Int, bottom: Int,
                oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int,
            ) {
                v.removeOnLayoutChangeListener(this)
                applyBackground(v)
            }
        })
    }

    private fun isListPopup(frame: View): Boolean {
        val group = frame as? ViewGroup ?: return false
        for (i in 0 until group.childCount) {
            val spring = group.getChildAt(i) as? ViewGroup ?: continue
            if (spring.javaClass.name != SPRING_BACK) continue
            for (j in 0 until spring.childCount) {
                if (spring.getChildAt(j) is ListView) return true
            }
        }
        return false
    }

    private fun isUnsupportedContactsMenu(view: View): Boolean =
        HookRuntime.targetPackage == "com.android.contacts" &&
            view.javaClass.name == SMOOTH_FRAME && isListPopup(view) &&
            view.parent?.javaClass == android.widget.FrameLayout::class.java &&
            view.parent?.parent?.javaClass?.name == "miuix.popupwidget.widget.PopupWindow\$ContainerView" &&
            view.rootView.javaClass.simpleName == "PopupDecorView"

    internal fun owns(view: View): Boolean {
        var node: View? = view
        repeat(8) {
            val current = node ?: return false
            if (current.javaClass.name == POPUP_VIEW || current.javaClass.name == DIALOG_PANEL ||
                current.javaClass.name == SMOOTH_FRAME && isListPopup(current)
            ) return true
            node = current.parent as? View
        }
        return false
    }

    private fun applyBackground(view: View, refresh: Boolean = false) {
        val current = view.background
        val previous = replacements[view]
        if (current !== previous && (!originals.containsKey(view) || current != null)) {
            originals[view] = current
        }
        if (!originals.containsKey(view)) originals[view] = null
        val original = originals[view]
        val config = palette
        val popupEnabled = config.enabledFor(HookRuntime.targetPackage, ComponentKeys.POPUP)
        val wantsGlass = popupEnabled && config.mode == CARD_BACKGROUND_SOFT_GLASS
        // The primary HyperPopupWindow menu cannot sample the backdrop on this Contacts build.
        // Leave it native; the ClipLayout-hosted secondary menu retains its working glass.
        if (!popupEnabled || isUnsupportedContactsMenu(view)) {
            if (glass.remove(view) != null) DynamicSoftGlassDrawable.clearFromView(view)
            if (frost.remove(view) != null) DynamicFrostDrawable.clearFromView(view)
            if (outlines.containsKey(view)) {
                outlineListeners.remove(view)?.let(view::removeOnLayoutChangeListener)
                view.outlineProvider = outlines.remove(view)
                originalClipping.remove(view)?.let { view.clipToOutline = it }
                view.invalidateOutline()
            }
            if (current === previous) view.background = original
            replacements.remove(view)
            return
        }
        if (current === previous && previous != null && !refresh) {
            if (glass[view] == true) {
                if (previous.alpha != 255) previous.alpha = 255
                return
            }
            if (frost[view] == true) return
            if (!wantsGlass && config.mode != CARD_BACKGROUND_FROST) return
            if (!view.isAttachedToWindow || !view.isHardwareAccelerated) return
        }
        if (glass.remove(view) != null) DynamicSoftGlassDrawable.clearFromView(view)
        if (frost.remove(view) != null) DynamicFrostDrawable.clearFromView(view)
        if (outlines.containsKey(view)) {
            outlineListeners.remove(view)?.let(view::removeOnLayoutChangeListener)
            view.outlineProvider = outlines.remove(view)
            originalClipping.remove(view)?.let { view.clipToOutline = it }
            view.invalidateOutline()
        }
        val replacement = DynamicPopupBackground.create(original, view.context, config) ?: run {
            module.log(Log.WARN, TAG, "Popup background unavailable: ${original?.javaClass?.name}")
            return
        }
        replacements[view] = replacement
        view.background = replacement
        if (config.mode == CARD_BACKGROUND_FROST && view.isAttachedToWindow && view.isHardwareAccelerated) {
            val night = view.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
            val dark = night && !config.darkFollowsLight
            if (DynamicFrostDrawable.applyToView(
                    view, if (dark) config.darkBlur else config.lightBlur,
                    view.resources.displayMetrics.density,
                )
            ) {
                frost[view] = true
                return
            }
            DynamicFrostDrawable.clearFromView(view)
        }
        if (wantsGlass) {
            if (!view.isAttachedToWindow || !view.isHardwareAccelerated) return
            val night = view.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
            val dark = night && !config.darkFollowsLight
            val color = if (dark) config.darkFrost else config.lightFrost
            val params = if (dark) config.darkGlass else config.lightGlass
            if (DynamicSoftGlassDrawable.applyToView(
                    view, color, params, view.resources.displayMetrics.density,
                )
            ) {
                replacement.alpha = 255
                if (view.javaClass.name == SMOOTH_FRAME && isListPopup(view) &&
                    view.rootView.javaClass.simpleName == "PopupDecorView"
                ) {
                    val radius = DynamicPopupBackground.cornerRadius(original) ?: runCatching {
                        (view.javaClass.getMethod("getCornerRadius").invoke(view) as Number).toFloat()
                    }.getOrDefault(0f)
                    if (radius > 0f) {
                        outlines[view] = view.outlineProvider
                        originalClipping[view] = view.clipToOutline
                        view.outlineProvider = object : ViewOutlineProvider() {
                            override fun getOutline(v: View, outline: Outline) {
                                val currentRadius = DynamicPopupBackground.cornerRadius(v.background) ?: radius
                                outline.setRoundRect(0, 0, v.width, v.height, currentRadius)
                            }
                        }
                        view.clipToOutline = true
                        val updateOutline: (View) -> Unit = { v ->
                            v.invalidateOutline()
                            runCatching {
                                val currentRadius = DynamicPopupBackground.cornerRadius(v.background) ?: radius
                                val node = View::class.java.getDeclaredField("mRenderNode")
                                    .apply { isAccessible = true }.get(v) as RenderNode
                                node.setOutline(Outline().apply {
                                    setRoundRect(0, 0, v.width, v.height, currentRadius)
                                    alpha = 1f
                                })
                                node.setClipToOutline(true)
                                node.setClipToBounds(true)
                            }.onFailure {
                                module.log(Log.WARN, TAG, "Drop-down glass outline unavailable", it)
                            }
                        }
                        val listener = object : View.OnLayoutChangeListener {
                            override fun onLayoutChange(
                                v: View, left: Int, top: Int, right: Int, bottom: Int,
                                oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int,
                            ) {
                                if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                                    updateOutline(v)
                                }
                            }
                        }
                        outlineListeners[view] = listener
                        view.addOnLayoutChangeListener(listener)
                        if (view.width > 0 && view.height > 0) updateOutline(view)
                    }
                }
                // SmoothContainerDrawable2 paints its child directly; tinting the wrapper
                // transparent does not remove that opaque fill on MIUIX builds.
                DynamicPopupBackground.clearFill(replacement)
                glass[view] = true
                return
            }
            DynamicSoftGlassDrawable.clearFromView(view)
            replacement.setTintList(null)
            glass[view] = false
        }
        module.log(Log.INFO, TAG, "Popup color fallback: ${view.javaClass.name}@${System.identityHashCode(view).toString(16)} original=${original?.javaClass?.name} mode=${config.mode}")
    }
}
