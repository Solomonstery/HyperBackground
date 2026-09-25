package com.ciallo.hyperbackground.dialpad

import android.content.Context
import android.view.ViewGroup
import android.widget.FrameLayout
import com.ciallo.hyperbackground.BackgroundContract
import com.ciallo.hyperbackground.BackgroundMediaView

internal class DialpadImageView(
    context: Context,
    source: BackgroundContract.Source,
    panelOpacity: Float,
) : DialpadPanelView(context) {
    private val media = BackgroundMediaView(context, source)

    override val canReuse: Boolean get() = !media.isDisposed && !media.loadFailed

    init {
        // Only custom-image mode follows the scaled drawable. Default backdrop mode keeps the
        // full, fixed keypad outline supplied by DialpadPanelView.
        media.onImageDisplayBoundsChanged = { bounds, scale ->
            followContentBounds(bounds, scale)
        }
        media.alpha = panelOpacity.coerceIn(0f, 1f) * (source.opacity / 100f)
        addView(media, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))
    }

    override fun onHostResume() = media.onHostResume()

    override fun dispose() {
        media.onImageDisplayBoundsChanged = null
        media.dispose()
    }
}
