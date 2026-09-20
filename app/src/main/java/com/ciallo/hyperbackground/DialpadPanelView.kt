package com.ciallo.hyperbackground

import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Path
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout

/** Shared final clipping for both custom images and the native backdrop/tint layers. */
internal abstract class DialpadPanelView(context: Context) : FrameLayout(context) {
    private val cornerRadius = 30f * resources.displayMetrics.density
    private val roundedBounds = Path()

    abstract val canReuse: Boolean

    init {
        setAdditionalInstanceField(DialpadBackdropView.OWNED_VIEW_FIELD, true)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        isClickable = false
        isFocusable = false
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val radius = minOf(cornerRadius, minOf(view.width, view.height) / 2f)
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        clipToOutline = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        roundedBounds.rewind()
        if (w > 0 && h > 0) {
            val radius = minOf(cornerRadius, minOf(w, h) / 2f)
            roundedBounds.addRoundRect(0f, 0f, w.toFloat(), h.toFloat(), radius, radius, Path.Direction.CW)
        }
        invalidateOutline()
    }

    override fun dispatchDraw(canvas: Canvas) {
        val save = canvas.save()
        // Clip outside the media's RenderEffect, after blur has been applied.
        canvas.clipPath(roundedBounds)
        super.dispatchDraw(canvas)
        canvas.restoreToCount(save)
    }

    open fun onHostResume() = Unit

    abstract fun dispose()
}
