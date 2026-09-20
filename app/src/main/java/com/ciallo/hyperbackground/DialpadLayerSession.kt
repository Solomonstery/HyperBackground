package com.ciallo.hyperbackground

import android.graphics.Matrix
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import kotlin.math.ceil
import kotlin.math.floor

/** An underlay of the whole keypad, independent of the native background view's measurement. */
internal class DialpadLayerSession(
    private val host: ViewGroup,
    private val panel: View,
    private val nativeBackground: View?,
    private val layer: DialpadPanelView,
) : View.OnAttachStateChangeListener {
    private val originalBackgroundAlpha = nativeBackground?.alpha
    private val panelBounds = RectF()
    private val panelTransform = Matrix()
    private var observer: ViewTreeObserver? = null
    private var disposed = false
    private var boundsLogged = false
    private val beforeDraw = ViewTreeObserver.OnPreDrawListener {
        syncPanelBounds()
        true
    }

    fun matches(host: ViewGroup, panel: View, nativeBackground: View?): Boolean =
        !disposed && layer.canReuse && layer.parent === host &&
            this.host === host && this.panel === panel && this.nativeBackground === nativeBackground

    fun attach() {
        host.addOnAttachStateChangeListener(this)
        // DialpadLayout lays out known native children itself. The injected layer must not
        // contribute to its measurements; its exact size is supplied after native layout.
        host.addView(layer, 0, FrameLayout.LayoutParams(0, 0))
        if (host.isAttachedToWindow) startObserving()
    }

    override fun onViewAttachedToWindow(view: View) = startObserving()

    override fun onViewDetachedFromWindow(view: View) = stopObserving()

    private fun startObserving() {
        if (disposed) return
        stopObserving()
        observer = host.viewTreeObserver.also { it.addOnPreDrawListener(beforeDraw) }
        syncPanelBounds()
    }

    private fun stopObserving() {
        observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(beforeDraw)
        observer = null
    }

    private fun syncPanelBounds() {
        if (disposed || layer.parent !== host) return
        if (!panel.isShown || panel.width <= 0 || panel.height <= 0) {
            layer.visibility = View.INVISIBLE
            return
        }
        // Follow the actual keypad, not dialer_background_view, its padding, or its minimum
        // drawable size. Mapping to host coordinates also follows native slide/scale animations.
        panelBounds.set(0f, 0f, panel.width.toFloat(), panel.height.toFloat())
        panelTransform.reset()
        panel.transformMatrixToGlobal(panelTransform)
        host.transformMatrixToLocal(panelTransform)
        panelTransform.mapRect(panelBounds)
        val left = floor(panelBounds.left).toInt()
        val top = floor(panelBounds.top).toInt()
        val right = ceil(panelBounds.right).toInt()
        val bottom = ceil(panelBounds.bottom).toInt()
        val width = right - left
        val height = bottom - top
        if (width <= 0 || height <= 0) {
            layer.visibility = View.INVISIBLE
            return
        }
        if (layer.measuredWidth != width || layer.measuredHeight != height ||
            layer.left != left || layer.top != top || layer.right != right || layer.bottom != bottom ||
            layer.isLayoutRequested) {
            layer.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
            )
            layer.layout(left, top, right, bottom)
        }
        layer.visibility = View.VISIBLE
        layer.alpha = if (panel === host) 1f else panel.alpha
        // A DialerBgView can paint in onDraw: replacing only its Drawable does not hide it.
        // Keep its geometry intact and hide its rendering while the independent layer is active.
        nativeBackground?.alpha = 0f
        if (!boundsLogged) {
            boundsLogged = true
            val mode = if (layer is DialpadImageView) "image" else "backdrop"
            log("[HyperBackground] Dialpad layer=$mode panel=${panel.javaClass.name} " +
                "bounds=($left,$top,$right,$bottom) host=${host.width}x${host.height} " +
                "native=${nativeBackground?.javaClass?.name}:" +
                "${nativeBackground?.width}x${nativeBackground?.height}")
        }
    }

    fun onHostResume() {
        layer.onHostResume()
        syncPanelBounds()
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        stopObserving()
        host.removeOnAttachStateChangeListener(this)
        if (layer.parent === host) host.removeView(layer)
        layer.dispose()
        if (originalBackgroundAlpha != null) nativeBackground?.alpha = originalBackgroundAlpha
    }
}
