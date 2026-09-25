package com.ciallo.hyperbackground.dialpad

import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import com.ciallo.hyperbackground.util.log
import kotlin.math.ceil
import kotlin.math.floor

/** An independent underlay positioned from the native dialpad background's final bounds. */
internal class DialpadLayerSession(
    private val host: ViewGroup,
    private val panel: View,
    private val nativeBackground: View?,
    private val layer: DialpadPanelView,
) : View.OnAttachStateChangeListener {
    private val originalBackgroundAlpha = nativeBackground?.alpha
    private val panelPadding = Rect()
    private val panelBounds = RectF()
    private val contentBounds = RectF()
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
        // The native dialer_background_new 9-patch fills this View but keeps its opaque panel
        // inside the Drawable padding (the surrounding pixels are only its shadow). Follow that
        // inner rectangle so custom media and blur do not cover the shadow and look oversized.
        panelBounds.set(0f, 0f, panel.width.toFloat(), panel.height.toFloat())
        contentBounds.set(panelBounds)
        panelPadding.setEmpty()
        if (panel === nativeBackground) {
            nativeBackground?.background?.getPadding(panelPadding)
            val paddedLeft = panelPadding.left.coerceAtLeast(0)
            val paddedTop = panelPadding.top.coerceAtLeast(0)
            val paddedRight = panelPadding.right.coerceAtLeast(0)
            val paddedBottom = panelPadding.bottom.coerceAtLeast(0)
            if (paddedLeft + paddedRight < panel.width &&
                paddedTop + paddedBottom < panel.height) {
                contentBounds.set(
                    paddedLeft.toFloat(),
                    paddedTop.toFloat(),
                    (panel.width - paddedRight).toFloat(),
                    (panel.height - paddedBottom).toFloat(),
                )
            }
        }
        // Mapping into host coordinates also follows the dialpad's native slide/scale animation.
        panelTransform.reset()
        panel.transformMatrixToGlobal(panelTransform)
        host.transformMatrixToLocal(panelTransform)
        panelTransform.mapRect(panelBounds)
        panelTransform.mapRect(contentBounds)
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
        layer.followPanelBounds(
            contentBounds.left - left,
            contentBounds.top - top,
            contentBounds.right - left,
            contentBounds.bottom - top,
        )
        layer.visibility = View.VISIBLE
        // nativeBackground is made transparent below, so never mirror its alpha back to the
        // replacement layer on the next pre-draw. Other fallback panels may still animate alpha.
        layer.alpha = if (panel === host || panel === nativeBackground) 1f else panel.alpha
        // A DialerBgView can paint in onDraw: replacing only its Drawable does not hide it.
        // Keep its geometry intact and hide its rendering while the independent layer is active.
        nativeBackground?.alpha = 0f
        if (!boundsLogged) {
            boundsLogged = true
            val mode = if (layer is DialpadImageView) "image" else "backdrop"
            log(
                "[HyperBackground] Dialpad layer=$mode panel=${panel.javaClass.name} " +
                        "bounds=($left,$top,$right,$bottom) host=${host.width}x${host.height} " +
                        "padding=(${panelPadding.left},${panelPadding.top}," +
                        "${panelPadding.right},${panelPadding.bottom}) " +
                        "native=${nativeBackground?.javaClass?.name}:" +
                        "${nativeBackground?.width}x${nativeBackground?.height}"
            )
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
