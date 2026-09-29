package com.ciallo.hyperbackground.dynamic.material

import org.junit.Assert.*
import org.junit.Test

class GlassOutlineGeometryTest {
    @Test fun topReappearingRestoresTopAtSameHeight() {
        val geometry = GlassOutlineGeometry()
        geometry.update(400f, 200f, 24f, false, true)
        assertEquals(48f, geometry.topInset, 0f)
        assertEquals(200f, geometry.height - geometry.topInset, 0f)
        assertTrue(geometry.update(400f, 200f, 24f, true, true))
        assertEquals(0f, geometry.topInset, 0f)
        assertEquals(200f, geometry.height, 0f)
    }

    @Test fun onlyMissingEdgesAreExtended() {
        val geometry = GlassOutlineGeometry()
        for (top in listOf(false, true)) for (bottom in listOf(false, true)) {
            geometry.update(400f, 200f, 24f, top, bottom)
            val drawTop = 120f - geometry.topInset
            if (top) assertEquals(120f, drawTop, 0f) else assertTrue(drawTop < 120f)
            if (bottom) assertEquals(320f, drawTop + geometry.height, 0f)
            else assertTrue(drawTop + geometry.height > 320f)
        }
    }

    @Test fun slotReusedBySmallerCardDoesNotInheritLargeOutline() {
        val geometry = GlassOutlineGeometry()
        geometry.update(400f, 600f, 24f, false, true)
        geometry.update(400f, 90f, 24f, true, true)
        assertEquals(90f, geometry.height, 0f)
        assertEquals(0f, geometry.topInset, 0f)
    }

    @Test fun steadyScrollingDoesNotUpdateOutline() {
        val geometry = GlassOutlineGeometry()
        assertTrue(geometry.update(400f, 200f, 24f, true, true))
        repeat(1000) {
            assertFalse(geometry.update(400f, 200f, 24f, true, true))
        }
        assertFalse(geometry.update(400f, 200.00002f, 24f, true, true))
        assertTrue(geometry.update(400f, 220f, 24f, true, true))
        geometry.reset()
        assertTrue(geometry.update(400f, 220f, 24f, true, true))
    }
}
