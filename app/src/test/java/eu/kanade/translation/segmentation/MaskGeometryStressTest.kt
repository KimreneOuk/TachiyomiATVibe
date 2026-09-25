package eu.kanade.translation.segmentation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Checkpoint 4 stress coverage for [MaskGeometry].
 *
 * Pure-JVM: large configured span budget, dense component counts, repeated
 * construction identity, and deterministic queries at scale. Pins the
 * allocation-bounded row-offset / union-find build path and the binary-search
 * query path so realistic large masks stay stable. The build path is
 * structurally bounded (single pass over spans + two-pointer row sweep), so no
 * wall-clock timeout is used; scales are sized well above the focused
 * [MaskGeometryTest] case without making the suite brittle on slow hosts.
 *
 * Component ids are assigned by stable-key order (not position), so these tests
 * resolve the component for a known island through [componentForRectangle] and
 * then assert against that resolved id, rather than assuming positional ids.
 */
class MaskGeometryStressTest {

    @Test
    fun `large configured span budget stays bounded and queryable`() {
        val width = 10_000
        val height = 20
        val limit = 20_000
        // Many short disjoint spans across rows — stresses the union-find sweep
        // and row-offset build without approaching the pixel-dimension cap.
        val spans = buildList {
            for (i in 0 until limit) {
                val y = i % height
                val x = (i / height) * 2
                add(MaskGeometry.RowSpan(y, x, x + 1))
            }
        }
        assertEquals(limit, spans.size)

        val geometry = MaskGeometry.fromSpans(width, height, spans, maxSpans = limit)
        // Immutable snapshot: the same span list rebuilds an equal, stable geometry.
        val rebuilt = MaskGeometry.fromSpans(width, height, spans, maxSpans = limit)
        assertEquals(geometry.stableKey, rebuilt.stableKey)
        assertEquals(geometry.spans, rebuilt.spans)

        // The last span's coordinates are contained exactly. Each span is a single
        // pixel (x..x+1) with a one-pixel gap to the next, so assert per-pixel.
        val lastX = ((limit - 1) / height) * 2
        val lastY = (limit - 1) % height
        assertTrue(geometry.containsPoint(lastX, lastY))
        assertTrue(geometry.containsPoint(0, 0))
        assertTrue(geometry.containsRectangle(0, 0, 1, 1))
        assertFalse(geometry.containsRectangle(0, 0, 2, 1)) // gap at x=1
    }

    @Test
    fun `many components keep deterministic distinct stable keys and resolve by overlap`() {
        // 200 disjoint 1px islands on one row -> 200 components, each its own key.
        // Component ids are assigned by stable-key order, not position, so resolve
        // the component for each island through componentForRectangle and verify
        // every island maps to a distinct component id.
        val width = 400
        val count = 200
        val spans = (0 until count).map { MaskGeometry.RowSpan(0, it * 2, it * 2 + 1) }
        val geometry = MaskGeometry.fromSpans(width, 1, spans)

        assertEquals(count, geometry.components.size)
        assertEquals(count, geometry.components.map { it.stableKey }.toSet().size)

        val ids = (0 until count).map { island ->
            geometry.componentForRectangle(island * 2, 0, island * 2 + 1, 1)
        }
        // Every island resolves to a non-null, distinct component id.
        assertTrue(ids.all { it != null })
        assertEquals(count, ids.toSet().size)
        // The first and last islands resolve to different components.
        assertNotEquals(ids.first(), ids.last())
    }
}
