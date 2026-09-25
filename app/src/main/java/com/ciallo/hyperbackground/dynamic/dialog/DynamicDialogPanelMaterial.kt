package com.ciallo.hyperbackground.dynamic.dialog

import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableContainer
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.util.Log
import android.view.View
import com.ciallo.hyperbackground.HookRuntime
import com.ciallo.hyperbackground.appearance.CARD_BACKGROUND_SOFT_GLASS
import com.ciallo.hyperbackground.appearance.ComponentKeys
import com.ciallo.hyperbackground.dynamic.material.DynamicMaterialPalette
import com.ciallo.hyperbackground.dynamic.material.DynamicSoftGlassDrawable
import io.github.libxposed.api.XposedModule
import java.util.Collections
import java.util.IdentityHashMap
import java.util.WeakHashMap

/** Preserve MIUIX's layout-bearing background and only mute its paint while glass is ready. */
internal object DynamicDialogPanelMaterial {
    private const val TAG = "HyperBackgroundCards"
    private lateinit var module: XposedModule
    private data class SavedAlpha(val drawable: Drawable, val alpha: Int)
    private data class State(
        val background: Drawable,
        val layers: List<SavedAlpha>,
        val originalPassBlur: Boolean,
        val listener: View.OnAttachStateChangeListener,
        val palette: DynamicMaterialPalette,
        val contentFills: DialogContentFills,
    )
    private val states = WeakHashMap<View, State>()
    private val failed = WeakHashMap<View, Boolean>()

    fun install(value: XposedModule) { module = value }

    fun refresh(palette: DynamicMaterialPalette) {
        states.keys.toList().forEach { update(it, palette) }
    }

    fun update(panel: View, palette: DynamicMaterialPalette) {
        val enabled = palette.enabledFor(HookRuntime.targetPackage, ComponentKeys.POPUP) &&
            palette.mode == CARD_BACKGROUND_SOFT_GLASS
        if (!enabled || !panel.isAttachedToWindow || !panel.isHardwareAccelerated) {
            restore(panel)
            if (!enabled) failed.remove(panel)
            return
        }
        if (failed[panel] == true) return
        if (passBlurWhitelisted(panel) == false) {
            restore(panel)
            return
        }
        val current = panel.background ?: return
        states[panel]?.let { state ->
            if (current === state.background && state.palette == palette && passBlur(panel) == true) {
                // AlertController may asynchronously reset alpha after setupMaterial().
                state.layers.forEach { if (it.drawable.alpha != 0) it.drawable.alpha = 0 }
                state.contentFills.update(panel)
                return
            }
            restore(panel)
        }
        val background = panel.background ?: return
        // Do not change the fill or apply any shader until the actual pass-window state is true.
        val before = passBlur(panel) ?: return
        if (!before) {
            if (!setPassBlur(panel, true) || passBlur(panel) != true) {
                setPassBlur(panel, false)
                failed[panel] = true
                return
            }
        }
        val night = panel.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        val dark = night && !palette.darkFollowsLight
        val applied = DynamicSoftGlassDrawable.applyToView(
            panel, if (dark) palette.darkFrost else palette.lightFrost,
            if (dark) palette.darkGlass else palette.lightGlass,
            panel.resources.displayMetrics.density, clearBackground = false,
        )
        if (!applied) {
            DynamicSoftGlassDrawable.clearFromView(panel)
            if (!before) setPassBlur(panel, false)
            failed[panel] = true
            return
        }
        val layers = collectLayers(background).map { SavedAlpha(it, it.alpha) }
        layers.forEach { it.drawable.alpha = 0 }
        val listener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) { restore(v) }
        }
        val contentFills = DialogContentFills()
        states[panel] = State(background, layers, before, listener, palette, contentFills)
        panel.addOnAttachStateChangeListener(listener)
        contentFills.update(panel)
        module.log(Log.INFO, TAG, "Dialog glass applied: ${panel.javaClass.name}")
    }

    private fun restore(panel: View) {
        val state = states.remove(panel) ?: return
        panel.removeOnAttachStateChangeListener(state.listener)
        state.contentFills.restore()
        DynamicSoftGlassDrawable.clearFromView(panel)
        if (panel.background === state.background) {
            state.layers.forEach { it.drawable.alpha = it.alpha }
        }
        if (!state.originalPassBlur) setPassBlur(panel, false)
    }

    private fun collectLayers(background: Drawable): List<Drawable> {
        val seen = Collections.newSetFromMap(IdentityHashMap<Drawable, Boolean>())
        val result = ArrayList<Drawable>()
        fun visit(drawable: Drawable?) {
            if (drawable == null || !seen.add(drawable)) return
            result.add(drawable)
            when (drawable) {
                is LayerDrawable -> for (i in 0 until drawable.numberOfLayers) visit(drawable.getDrawable(i))
                is InsetDrawable -> visit(drawable.drawable)
                is DrawableContainer -> visit(drawable.current)
                else -> if (drawable.javaClass.name == "miuix.smooth.SmoothContainerDrawable2") {
                    visit(runCatching { drawable.javaClass.getMethod("getChildDrawable").invoke(drawable) as? Drawable }
                        .getOrNull())
                }
            }
        }
        visit(background)
        return result
    }

    private fun passBlur(view: View): Boolean? = runCatching {
        View::class.java.getMethod("getPassWindowBlurEnabled").invoke(view) as Boolean
    }.getOrNull()

    private fun passBlurWhitelisted(view: View): Boolean? = runCatching {
        View::class.java.getMethod("isPassWindowBlurWhitelisted", String::class.java)
            .invoke(view, view.context.packageName) as Boolean
    }.getOrNull()

    private fun setPassBlur(view: View, enabled: Boolean): Boolean = runCatching {
        View::class.java.getMethod("setPassWindowBlurEnabled", Boolean::class.javaPrimitiveType)
            .invoke(view, enabled)
        true
    }.getOrDefault(false)
}
