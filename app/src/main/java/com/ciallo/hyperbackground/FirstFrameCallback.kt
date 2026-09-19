package com.ciallo.hyperbackground

import android.view.View
import android.view.ViewTreeObserver

/** Keeps the previous background alive until a frame containing its replacement is submitted. */
internal class FirstFrameCallback(private val view: View) {
    var isReady = false
        private set
    var onReady: (() -> Unit)? = null
        set(value) {
            field = value
            if (isReady && !disposed) value?.invoke()
        }

    private var disposed = false
    private var pending = false
    private var observer: ViewTreeObserver? = null
    private val complete = Runnable {
        pending = false
        observer = null
        if (!disposed && view.isAttachedToWindow && view.isShown) {
            isReady = true
            onReady?.invoke()
        }
    }
    // Finish on the UI queue, outside the traversal that drew the replacement.
    private val frameCommitted = Runnable { view.post(complete) }

    /** Call after drawing real image content or a video texture with an available frame. */
    fun afterDraw() {
        if (disposed || isReady || pending || onReady == null ||
            !view.isAttachedToWindow || !view.isShown || view.width <= 0 || view.height <= 0) return
        pending = true
        if (view.isHardwareAccelerated) {
            val tree = view.viewTreeObserver
            if (tree.isAlive) {
                observer = tree
                tree.registerFrameCommitCallback(frameCommitted)
            } else {
                pending = false
            }
        } else {
            // Software rendering has no frame-commit callback; the draw has already completed.
            view.post(complete)
        }
    }

    fun dispose() {
        disposed = true
        isReady = false
        onReady = null
        observer?.takeIf { it.isAlive }?.unregisterFrameCommitCallback(frameCommitted)
        observer = null
        view.removeCallbacks(complete)
    }
}
