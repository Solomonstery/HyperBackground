package com.ciallo.hyperbackground

import org.junit.Assert.*
import org.junit.Test

class BackgroundDecodeSizeTest {
    @Test fun cameraImagesAreDownsampledWithTheirAspectRatio() {
        assertEquals(2400 to 1600, backgroundDecodeSize(6000, 4000, 2400))
        assertEquals(1600 to 2400, backgroundDecodeSize(4000, 6000, 2400))
    }

    @Test fun smallImagesAreNotUpscaled() {
        assertEquals(320 to 240, backgroundDecodeSize(320, 240, 2400))
    }

    @Test fun hugeAndNarrowImagesStayWithinThePixelBudget() {
        for ((width, height) in listOf(20_000 to 20_000, Int.MAX_VALUE to 1, 1 to Int.MAX_VALUE)) {
            val (targetWidth, targetHeight) = backgroundDecodeSize(width, height, 4800)
            assertTrue(targetWidth in 1..4800)
            assertTrue(targetHeight in 1..4800)
            assertTrue(targetWidth.toLong() * targetHeight <= 4_194_304L)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidDimensionsAreRejected() {
        backgroundDecodeSize(0, 100, 2400)
    }
}
