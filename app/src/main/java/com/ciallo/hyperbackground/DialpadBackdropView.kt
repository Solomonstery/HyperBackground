package com.ciallo.hyperbackground

import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import java.lang.reflect.Method

/** The native backdrop and panel tint are separate layers, both below the dialpad keys. */
internal class DialpadBackdropView(
    context: Context,
    panelBackground: Drawable?,
    panelOpacity: Float,
    private val blurRadius: Int,
) : DialpadPanelView(context) {
    private val blurView = View(context)
    private var blurApplied = false
    private var disposed = false

    override val canReuse: Boolean get() = !disposed

    init {
        blurView.apply {
            setAdditionalInstanceField(OWNED_VIEW_FIELD, true)
            setBackgroundColor(Color.TRANSPARENT)
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, 30f * resources.displayMetrics.density)
                }
            }
            clipToOutline = true
            visibility = if (blurRadius > 0) VISIBLE else INVISIBLE
        }
        addView(blurView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        val tintView = View(context).apply {
            setAdditionalInstanceField(OWNED_VIEW_FIELD, true)
            background = panelBackground?.constantState?.newDrawable(resources)?.mutate() ?: panelBackground
            alpha = panelOpacity.coerceIn(0f, 1f)
        }
        addView(tintView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (disposed || blurRadius <= 0 || !isHardwareAccelerated) return
        val api = blurApi ?: return
        runCatching {
            // Mark first so partial setup is also undone if a vendor call fails.
            blurApplied = true
            api.backgroundMode.invoke(blurView, 1)
            api.viewMode.invoke(blurView, 1)
            api.radius.invoke(blurView, blurRadius.coerceIn(0, 80))
        }.onFailure {
            clearBlur()
            log("[HyperBackground] Cannot apply dialpad backdrop blur: $it")
        }
    }

    override fun onDetachedFromWindow() {
        clearBlur()
        super.onDetachedFromWindow()
    }

    override fun dispose() {
        disposed = true
        clearBlur()
    }

    private fun clearBlur() {
        if (!blurApplied) return
        val api = blurApi ?: return
        runCatching { api.radius.invoke(blurView, 0) }
        runCatching { api.viewMode.invoke(blurView, 0) }
        runCatching { api.backgroundMode.invoke(blurView, 0) }
        blurApplied = false
    }

    private data class BlurApi(val backgroundMode: Method, val viewMode: Method, val radius: Method)

    companion object {
        const val OWNED_VIEW_FIELD = "hyperbackground.dialpad.owned_background"
        private val blurApi: BlurApi? by lazy {
            runCatching {
                val intType = Int::class.javaPrimitiveType!!
                BlurApi(
                    View::class.java.getMethod("setMiBackgroundBlurMode", intType),
                    View::class.java.getMethod("setMiViewBlurMode", intType),
                    View::class.java.getMethod("setMiBackgroundBlurRadius", intType),
                )
            }.onFailure { log("[HyperBackground] Dialpad backdrop blur unavailable: $it") }.getOrNull()
        }
    }
}
