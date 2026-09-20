package com.ciallo.hyperbackground

import android.content.Context
import android.view.ViewGroup
import android.widget.FrameLayout

internal class DialpadImageView(
    context: Context,
    source: BackgroundContract.Source,
    panelOpacity: Float,
) : DialpadPanelView(context) {
    private val media = BackgroundMediaView(context, source)

    override val canReuse: Boolean get() = !media.isDisposed && !media.loadFailed

    init {
        media.alpha = panelOpacity.coerceIn(0f, 1f) * (source.opacity / 100f)
        addView(media, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))
    }

    override fun onHostResume() = media.onHostResume()

    override fun dispose() = media.dispose()
}
