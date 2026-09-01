package eu.kanade.translation.rendering

import eu.kanade.translation.segmentation.MaskGeometry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/**
 * T912 slice 2: the compact `(planGeometryId, componentId)` clip cache.
 *
 * Pure-JVM: [ComponentClipCache] has no `android.graphics` dependency, so a fake
 * clip type stands in for `Path`. Pins hit/miss, instance-identity verification,
 * per-component entries, fail-closed invalid ids, and cap checks that stop
 * BEFORE create while leaving earlier entries resolvable.
 */
class ComponentClipCacheTest {

    private class FakeClip(val label: String)

    private fun twoComponentGeometry(): MaskGeometry = MaskGeometry.fromSpans(
        10,
        1,
        listOf(MaskGeometry.RowSpan(0, 0, 2), MaskGeometry.RowSpan(0, 4, 6)),
    )

    @Test
    fun `hit returns the same prepared instance without recreating`() {
        var creations = 0
        val cache = ComponentClipCache(4, 100) { component ->
            creations++
            FakeClip("c${component.id}")
        }
        val geometry = twoComponentGeometry()

        val first = cache.resolve(0, 0, geometry)
        val second = cache.resolve(0, 0, geometry)

        assertNotNull(first)
        assertSame(first, second)
        assertEquals(1, creations)
        assertEquals(1, cache.size)
    }

    @Test
    fun `same key from a different geometry instance fails closed`() {
        val cache = ComponentClipCache<FakeClip>(4, 100) { FakeClip("c${it.id}") }
        val geometryA = twoComponentGeometry()
        val geometryB = twoComponentGeometry() // equal content, DISTINCT instance

        assertNotNull(cache.resolve(0, 0, geometryA))
        assertNull(cache.resolve(0, 0, geometryB))
        // The stored entry still resolves for its own geometry instance.
        assertNotNull(cache.resolve(0, 0, geometryA))
    }

    @Test
    fun `per-component entries are independent`() {
        val cache = ComponentClipCache(4, 100) { FakeClip("c${it.id}") }
        val geometry = twoComponentGeometry()

        val clip0 = cache.resolve(7, 0, geometry)
        val clip1 = cache.resolve(7, 1, geometry)

        assertEquals("c0", clip0?.label)
        assertEquals("c1", clip1?.label)
        assertSame(clip0, cache.resolve(7, 0, geometry))
        assertSame(clip1, cache.resolve(7, 1, geometry))
    }

    @Test
    fun `invalid component ids fail closed`() {
        val cache = ComponentClipCache<FakeClip>(4, 100) { FakeClip("c${it.id}") }
        val geometry = twoComponentGeometry()

        assertNull(cache.resolve(0, 2, geometry))
        assertNull(cache.resolve(0, -1, geometry))
        assertEquals(0, cache.size)
    }

    @Test
    fun `component cap stops creation but keeps earlier entries resolvable`() {
        val cache = ComponentClipCache<FakeClip>(2, 1_000) { FakeClip("c${it.id}") }
        val g0 = twoComponentGeometry()
        val g1 = twoComponentGeometry()
        val g2 = twoComponentGeometry()

        assertNotNull(cache.resolve(0, 0, g0))
        assertNotNull(cache.resolve(1, 0, g1))
        // Third distinct component exceeds the cap BEFORE create.
        assertNull(cache.resolve(2, 0, g2))
        assertNotNull(cache.resolve(0, 0, g0))
        assertNotNull(cache.resolve(1, 0, g1))
    }

    @Test
    fun `span cap stops creation but keeps earlier entries resolvable`() {
        val cache = ComponentClipCache<FakeClip>(16, 3) { FakeClip("c${it.id}") }
        val small = MaskGeometry.fromSpans(10, 1, listOf(MaskGeometry.RowSpan(0, 0, 2)))
        // One CONNECTED component with 4 spans (each row overlaps the next in x,
        // so no same-row adjacency merge): exceeds the 3-span budget.
        val big = MaskGeometry.fromSpans(
            8,
            4,
            listOf(
                MaskGeometry.RowSpan(0, 1, 3),
                MaskGeometry.RowSpan(1, 2, 4),
                MaskGeometry.RowSpan(2, 3, 5),
                MaskGeometry.RowSpan(3, 4, 6),
            ),
        )

        // One connected component with 4 spans exceeds the 3-span budget: never created.
        assertNull(cache.resolve(0, 0, big))
        val first = cache.resolve(1, 0, small)
        assertNotNull(first)
        assertNull(cache.resolve(2, 0, big))
        assertSame(first, cache.resolve(1, 0, small))
    }
}
