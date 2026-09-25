package com.ciallo.hyperbackground.dynamic.dialog

import android.content.res.Configuration
import android.graphics.Outline
import android.graphics.RenderNode
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewOutlineProvider
import com.ciallo.hyperbackground.HookRuntime
import com.ciallo.hyperbackground.appearance.CARD_BACKGROUND_SOFT_GLASS
import com.ciallo.hyperbackground.appearance.ComponentKeys
import com.ciallo.hyperbackground.dynamic.material.DynamicMaterialPalette
import com.ciallo.hyperbackground.dynamic.material.DynamicSoftGlassDrawable
import java.util.WeakHashMap

/** Only the rounded surface under ActionSheetRootView owns this material. */
internal object DynamicActionSheetMaterial {
    private const val ROOT = "miuix.internal.widget.ActionSheetRootView"
    private const val FRAME = "miuix.smooth.SmoothFrameLayout2"
    private class State(
        val background: Drawable,
        val palette: DynamicMaterialPalette,
        val originalBlur: Boolean,
        val outline: ViewOutlineProvider?,
        val clip: Boolean,
        val listener: View.OnAttachStateChangeListener,
        val backgroundChild: Drawable,
        val backgroundChildAlpha: Int,
        val contentFills: DialogContentFills,
    )
    private val states = WeakHashMap<View, State>()
    private val failed = WeakHashMap<View, Boolean>()

    fun refresh(palette: DynamicMaterialPalette) {
        states.keys.toList().forEach { update(it, palette) }
    }

    fun isSurface(view: View): Boolean {
        if (view.javaClass.name != FRAME || radius(view) <= 0f) return false
        var parent = view.parent
        repeat(5) {
            if (parent?.javaClass?.name == ROOT) return true
            parent = (parent as? View)?.parent
        }
        return false
    }

    fun update(view: View, palette: DynamicMaterialPalette) {
        val enabled = palette.enabledFor(HookRuntime.targetPackage, ComponentKeys.POPUP) &&
            palette.mode == CARD_BACKGROUND_SOFT_GLASS
        if (!enabled || !view.isAttachedToWindow || !view.isHardwareAccelerated) {
            restore(view)
            if (!enabled) failed.remove(view)
            return
        }
        if (failed[view] == true) return
        if (passBlurWhitelisted(view) == false) {
            restore(view)
            return
        }
        states[view]?.let { state ->
            if (view.background === state.background && state.palette == palette && passBlur(view) == true) {
                if (state.backgroundChild.alpha != 0) state.backgroundChild.alpha = 0
                state.contentFills.update(view)
                return
            }
            restore(view)
        }
        val background = view.background ?: return
        val child = if (background.javaClass.name == "miuix.smooth.SmoothContainerDrawable2") {
            runCatching { background.javaClass.getMethod("getChildDrawable").invoke(background) as? Drawable }
                .getOrNull() ?: return
        } else background
        val before = passBlur(view) ?: return
        if (!before && (!setPassBlur(view, true) || passBlur(view) != true)) {
            setPassBlur(view, false)
            failed[view] = true
            return
        }
        val night = view.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        val dark = night && !palette.darkFollowsLight
        val applied = DynamicSoftGlassDrawable.applyToView(
            view, if (dark) palette.darkFrost else palette.lightFrost,
            if (dark) palette.darkGlass else palette.lightGlass,
            view.resources.displayMetrics.density, clearBackground = false,
        )
        if (!applied) {
            DynamicSoftGlassDrawable.clearFromView(view)
            if (!before) setPassBlur(view, false)
            failed[view] = true
            return
        }
        val oldOutline = view.outlineProvider
        val oldClip = view.clipToOutline
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                outline.setRoundRect(0, 0, v.width, v.height, radius(v))
            }
        }
        view.clipToOutline = true
        view.invalidateOutline()
        runCatching {
            val node = View::class.java.getDeclaredField("mRenderNode")
                .apply { isAccessible = true }.get(view) as RenderNode
            node.setOutline(Outline().apply {
                setRoundRect(0, 0, view.width, view.height, radius(view))
                alpha = 1f
            })
            node.setClipToOutline(true)
        }
        val listener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) { restore(v) }
        }
        val state = State(background, palette, before, oldOutline, oldClip, listener,
            child, child.alpha, DialogContentFills())
        states[view] = state
        view.addOnAttachStateChangeListener(listener)
        // Only remove opaque fills once sampling and the shader are both confirmed ready.
        child.alpha = 0
        state.contentFills.update(view)
    }

    fun restore(view: View) {
        val state = states.remove(view) ?: return
        view.removeOnAttachStateChangeListener(state.listener)
        state.contentFills.restore()
        if (view.background === state.background) state.backgroundChild.alpha = state.backgroundChildAlpha
        view.outlineProvider = state.outline
        view.clipToOutline = state.clip
        view.invalidateOutline()
        DynamicSoftGlassDrawable.clearFromView(view)
        if (!state.originalBlur) setPassBlur(view, false)
    }

    private fun radius(view: View): Float = runCatching {
        (view.javaClass.getMethod("getCornerRadius").invoke(view) as Number).toFloat()
    }.getOrDefault(0f)

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
