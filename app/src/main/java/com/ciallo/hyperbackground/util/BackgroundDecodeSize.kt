package com.ciallo.hyperbackground.util

import kotlin.math.floor
import kotlin.math.sqrt

/** Keep the aspect ratio and bound both the longest edge and decoded memory (at most 4M pixels). */
internal fun backgroundDecodeSize(width: Int, height: Int, maxEdge: Int): Pair<Int, Int> {
    require(width > 0 && height > 0 && maxEdge > 0)
    val scale = minOf(
        1.0,
        maxEdge.toDouble() / maxOf(width, height),
        sqrt(4_194_304.0 / (width.toDouble() * height)),
    )
    return floor(width * scale).toInt().coerceAtLeast(1) to
        floor(height * scale).toInt().coerceAtLeast(1)
}
