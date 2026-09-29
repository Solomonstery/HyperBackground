package com.ciallo.hyperbackground.dynamic.material

import kotlin.math.abs

/** Allocation-free geometry for the native glass outline, independent of previous card identity. */
internal class GlassOutlineGeometry {
    var width = 0f
        private set
    var height = 0f
        private set
    var radius = 0f
        private set
    var topInset = 0f
        private set
    private var visibleHeight = 0f
    private var topRounded = false
    private var bottomRounded = false
    private var initialized = false

    fun update(w: Float, h: Float, r: Float, top: Boolean, bottom: Boolean): Boolean {
        val safeRadius = r.coerceAtLeast(0f).coerceAtMost(w / 2f)
            .let { if (top && bottom) it.coerceAtMost(h / 2f) else it }
        // Subpixel subtraction of two scrolling Y coordinates can change height by an ULP.
        // Ignore that noise, but never ignore a corner-state change at identical dimensions.
        if (initialized && abs(w - width) < 0.01f && abs(h - visibleHeight) < 0.01f &&
            safeRadius == radius && top == topRounded && bottom == bottomRounded
        ) return false
        initialized = true
        width = w
        visibleHeight = h
        radius = safeRadius
        topRounded = top
        bottomRounded = bottom
        val extension = 2f * safeRadius
        topInset = if (top) 0f else extension
        height = h + topInset + if (bottom) 0f else extension
        return true
    }

    fun reset() { initialized = false }
}
