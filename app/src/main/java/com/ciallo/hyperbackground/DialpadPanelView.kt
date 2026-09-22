package com.ciallo.hyperbackground

import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import com.ciallo.hyperbackground.util.setAdditionalInstanceField

/** Shared final clipping for both custom images and the native backdrop/tint layers. */
internal abstract class DialpadPanelView(context: Context) : FrameLayout(context) {
    private val cornerRadius = 30f * resources.displayMetrics.density
    private val roundedBounds = Path()
    private val panelBounds = RectF()
    private val allowedBounds = RectF()
    private val requestedBounds = RectF()
    private val resolvedBounds = RectF()
    private var usePanelBounds = false
    private var useContentBounds = false
    private var contentScale = 1f

    abstract val canReuse: Boolean

    init {
        setAdditionalInstanceField(DialpadBackdropView.OWNED_VIEW_FIELD, true)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        isClickable = false
        isFocusable = false
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                if (resolvedBounds.isEmpty) {
                    outline.setEmpty()
                    return
                }
                outline.setRoundRect(
                    resolvedBounds.left.toInt(), resolvedBounds.top.toInt(),
                    resolvedBounds.right.toInt(), resolvedBounds.bottom.toInt(),
                    resolvedCornerRadius(),
                )
            }
        }
        clipToOutline = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildRoundedBounds()
    }

    /** Restricts both modes to the opaque body inside the native background's shadow padding. */
    internal fun followPanelBounds(left: Float, top: Float, right: Float, bottom: Float) {
        if (usePanelBounds && panelBounds.left == left && panelBounds.top == top &&
            panelBounds.right == right && panelBounds.bottom == bottom) return
        panelBounds.set(left, top, right, bottom)
        usePanelBounds = true
        rebuildRoundedBounds()
        invalidate()
    }

    /** Custom images call this with their matrix-mapped drawable bounds; default mode never does. */
    protected fun followContentBounds(bounds: RectF, scale: Float) {
        requestedBounds.set(bounds)
        useContentBounds = true
        contentScale = scale.coerceAtLeast(0f)
        rebuildRoundedBounds()
        invalidate()
    }

    private fun rebuildRoundedBounds() {
        roundedBounds.rewind()
        if (width <= 0 || height <= 0) {
            resolvedBounds.setEmpty()
            invalidateOutline()
            return
        }
        allowedBounds.set(0f, 0f, width.toFloat(), height.toFloat())
        if (usePanelBounds && !allowedBounds.intersect(panelBounds)) {
            allowedBounds.setEmpty()
        }
        if (useContentBounds) {
            resolvedBounds.set(requestedBounds)
            if (allowedBounds.isEmpty || !resolvedBounds.intersect(allowedBounds)) {
                resolvedBounds.setEmpty()
            }
        } else {
            resolvedBounds.set(allowedBounds)
        }
        if (!resolvedBounds.isEmpty) {
            val radius = resolvedCornerRadius()
            roundedBounds.addRoundRect(resolvedBounds, radius, radius, Path.Direction.CW)
        }
        invalidateOutline()
    }

    private fun resolvedCornerRadius(): Float {
        // The custom image's corner radius uses the same scale as its matrix. Default mode
        // never enables content bounds and therefore always keeps the established 30dp radius.
        val scaledRadius = if (useContentBounds) cornerRadius * contentScale else cornerRadius
        return minOf(scaledRadius, minOf(resolvedBounds.width(), resolvedBounds.height()) / 2f)
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
