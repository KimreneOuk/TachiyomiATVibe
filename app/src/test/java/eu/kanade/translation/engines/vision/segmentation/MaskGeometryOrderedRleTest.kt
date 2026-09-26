package eu.kanade.translation.engines.vision.segmentation

import eu.kanade.translation.engines.rendering.SharedMaskSession
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Budgeted ordered RLE → [MaskGeometry] conversion.
 *
 * Pure-JVM. Pins the two-pass contract: no sorting, no dense decode, explicit
 * fallbacks (never exceptions) for empty/invalid/overflow/budget inputs, caps
 * checked BEFORE any component/string allocation, and query parity with the
 * legacy [MaskGeometry.fromSpans] for equivalent masks. The planner grouping
 * session is pinned for fingerprint-bucket collisions and full-equality
 * verification.
 */
class MaskGeometryOrderedRleTest {

    private fun rle(
        width: Int,
        height: Int,
        runs: List<Int>,
        bounds: List<Int> = listOf(0, 0, width, height),
    ): BubbleMaskRle = BubbleMaskRle(width, height, bounds, runs, score = 1f)

    private fun success(result: OrderedMaskResult): MaskGeometry = when (result) {
        is OrderedMaskResult.Success -> result.geometry
        is OrderedMaskResult.Fallback -> throw AssertionError("expected success, got ${result.reason}")
    }

    private fun fallback(result: OrderedMaskResult): OrderedMaskFallbackReason = when (result) {
        is OrderedMaskResult.Success -> throw AssertionError("expected fallback, got success")
        is OrderedMaskResult.Fallback -> result.reason
    }

    @Test
    fun `row-crossing run splits into one span per row without sorting`() {
        // width 10: run [8,13) crosses the row boundary at pixel 10.
        val geometry = success(MaskGeometry.fromOrderedRle(rle(10, 2, listOf(8, 5)), MaskConversionBudgets()))
        assertEquals(
            listOf(MaskGeometry.RowSpan(0, 8, 10), MaskGeometry.RowSpan(1, 0, 3)),
            geometry.spans,
        )
        assertTrue(geometry.containsPoint(8, 0))
        assertTrue(geometry.containsPoint(9, 0))
        assertFalse(geometry.containsPoint(7, 0))
        assertTrue(geometry.containsPoint(0, 1))
        assertTrue(geometry.containsPoint(2, 1))
        assertFalse(geometry.containsPoint(3, 1))
        assertTrue(geometry.containsRectangle(0, 1, 3, 2))
        assertFalse(geometry.containsRectangle(8, 0, 10, 2))
    }

    @Test
    fun `ordered output is row-major and queries match an equivalent fromSpans geometry`() {
        // Connected L-shaped mask.
        val width = 6
        val height = 3
        val runs = listOf(0, 4, 6, 5, 13, 5) // row0: (0,4); row1: (6,5); row2: (13,5)
        val geometry = success(MaskGeometry.fromOrderedRle(rle(width, height, runs), MaskConversionBudgets()))
        val fromLegacy = MaskGeometry.fromSpans(
            width,
            height,
            listOf(MaskGeometry.RowSpan(0, 0, 4), MaskGeometry.RowSpan(1, 0, 5), MaskGeometry.RowSpan(2, 1, 6)),
        )

        // Row-major/start order with string-free component ids 0..n-1 in
        // first-appearance order.
        assertEquals(
            geometry.spans,
            geometry.spans.sortedWith(compareBy({ it.y }, { it.start })),
        )
        assertEquals((0 until geometry.components.size).toList(), geometry.components.map { it.id })

        // Query semantics match the legacy path for the same mask.
        for (y in 0 until height) {
            for (x in 0 until width) {
                assertEquals(fromLegacy.containsPoint(x, y), geometry.containsPoint(x, y), "point $x,$y")
            }
        }
        assertEquals(fromLegacy.components.size, geometry.components.size)
        assertEquals(
            fromLegacy.componentForRectangle(0, 0, width, height),
            geometry.componentForRectangle(0, 0, width, height),
        )
        assertEquals(fromLegacy.containsRectangle(1, 1, 5, 3), geometry.containsRectangle(1, 1, 5, 3))
    }

    @Test
    fun `component ids follow row-major first appearance for three or more components`() {
        // With ordered runs, the
        // first-appearance order is row-major, so a coordinate-STRING sort
        // would order "10:..." before "2:..." while the ordered path must not.
        val width = 100
        val height = 12
        val runs = listOf(
            2 * width + 50,
            1, // row 2 — first appearance
            5 * width + 90,
            1, // row 5
            10 * width + 10,
            1, // row 10 — string-sorted keys would rank this first
        )
        val geometry = success(MaskGeometry.fromOrderedRle(rle(width, height, runs), MaskConversionBudgets()))

        assertEquals(3, geometry.components.size)
        assertEquals(listOf(0, 1, 2), geometry.components.map { it.id })
        assertEquals(listOf(MaskGeometry.RowSpan(2, 50, 51)), geometry.components[0].spans)
        assertEquals(listOf(MaskGeometry.RowSpan(5, 90, 91)), geometry.components[1].spans)
        assertEquals(listOf(MaskGeometry.RowSpan(10, 10, 11)), geometry.components[2].spans)
        // The ids differ from the legacy key-sorted fromSpans ids on purpose:
        // "10:10-11" < "2:50-51" < "5:90-91" as strings, so legacy ranks the
        // row-10 island first while the ordered path keeps row-major order.
        val legacy = MaskGeometry.fromSpans(
            width,
            height,
            listOf(MaskGeometry.RowSpan(2, 50, 51), MaskGeometry.RowSpan(5, 90, 91), MaskGeometry.RowSpan(10, 10, 11)),
        )
        assertEquals(listOf(MaskGeometry.RowSpan(10, 10, 11)), legacy.components[0].spans)
    }

    @Test
    fun `empty invalid and overflow inputs fall back explicitly`() {
        val budgets = MaskConversionBudgets()

        assertEquals(
            OrderedMaskFallbackReason.EMPTY_MASK,
            fallback(MaskGeometry.fromOrderedRle(rle(10, 2, emptyList()), budgets)),
        )
        assertEquals(
            OrderedMaskFallbackReason.INVALID_DIMENSIONS,
            fallback(MaskGeometry.fromOrderedRleCore(0, 2, intArrayOf(0, 0, 0, 2), intArrayOf(0, 1), budgets)),
        )
        assertEquals(
            OrderedMaskFallbackReason.INVALID_DIMENSIONS,
            fallback(MaskGeometry.fromOrderedRleCore(2, 0, intArrayOf(0, 0, 2, 0), intArrayOf(0, 1), budgets)),
        )
        // start + length leaves the page's representable pixel space (and Int range).
        assertEquals(
            OrderedMaskFallbackReason.ARITHMETIC_OVERFLOW,
            fallback(
                MaskGeometry.fromOrderedRleCore(
                    2,
                    2,
                    intArrayOf(0, 0, 2, 2),
                    intArrayOf(Int.MAX_VALUE - 1, 4),
                    budgets,
                ),
            ),
        )
        assertEquals(0, budgets.derivedSpans)
        assertEquals(0, budgets.componentsBuilt)
    }

    @Test
    fun `component cap stops before any component is created`() {
        // 100 disjoint 1px islands on one row: spans and RLE budgets allow the
        // scan, but 100 union roots exceed the 64-per-mask component cap.
        val runs = buildList {
            for (i in 0 until 100) {
                add(i * 2)
                add(1)
            }
        }
        val budgets = MaskConversionBudgets()
        val result = MaskGeometry.fromOrderedRle(rle(200, 1, runs), budgets)

        // Explicit fallback object, not an exception.
        assertEquals(OrderedMaskFallbackReason.BUDGET_EXCEEDED, fallback(result))
        assertEquals(0, budgets.componentsBuilt)
        assertEquals(runs.size, budgets.rleIntsScanned)
        assertEquals(100, budgets.derivedSpans)
    }

    @Test
    fun `fingerprint collision cannot merge distinct masks`() {
        // Injected constant hash forces EVERY mask into one fingerprint bucket;
        // full-geometry equality verification must still keep them apart.
        val session = SharedMaskSession(fingerprint = { 42L })
        val full = rle(300, 120, listOf(0, 300 * 120))
        val half = rle(300, 120, listOf(0, 300 * 60))

        val first = session.groupIdOf(full)
        val second = session.groupIdOf(half)

        assertEquals(0, first)
        assertEquals(1, second)
        // Same instance reuses its group with no rescan.
        assertEquals(first, session.groupIdOf(full))

        session.convertAll()
        assertNotNull(session.geometryFor(first))
        assertNotNull(session.geometryFor(second))
    }

    @Test
    fun `equal distinct instances merge after full verification`() {
        val session = SharedMaskSession()
        val a = rle(300, 120, listOf(0, 300 * 120))
        val b = rle(300, 120, listOf(0, 300 * 120)) // equal geometry, distinct instance
        val scored = a.copy(score = 0.5f) // score is deliberately excluded

        assertEquals(0, session.groupIdOf(a))
        assertEquals(0, session.groupIdOf(b))
        assertEquals(0, session.groupIdOf(scored))
        assertEquals(1, session.groupCount)
    }

    @Test
    fun `hundred thousand islands abort at the component cap within time and allocation budget`() {
        val width = 200_000
        val runs = buildList {
            for (i in 0 until 100_000) {
                add(i * 2)
                add(1)
            }
        }
        val mask = rle(width, 1, runs)
        // Test-enlarged page RLE-int budget so the scan runs; the derived-span
        // (100_000, == allowed) and component (64) caps stay at defaults.
        val budgets = MaskConversionBudgets(maxRleIntsScannedPerPage = 400_000)

        // com.sun.management.ThreadMXBean is not on the android.jar compile
        // classpath, so the whole thread-allocation probe goes through
        // reflection (the host JVM provides it at runtime) and degrades
        // gracefully when the MXBean is unavailable.
        val threadMx = runCatching {
            Class.forName("java.lang.management.ManagementFactory")
                .getMethod("getThreadMXBean")
                .invoke(null)
        }.getOrNull()
        val allocatedBytes = object {
            fun current(): Long? = runCatching {
                val klass = Class.forName("com.sun.management.ThreadMXBean")
                val bytes = klass.getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
                bytes.invoke(threadMx, Thread.currentThread().id) as Long
            }.getOrNull()

            fun before(): Long? = runCatching {
                val klass = Class.forName("com.sun.management.ThreadMXBean")
                val supported = klass.getMethod("isThreadAllocatedMemorySupported")
                    .invoke(threadMx) as Boolean
                if (!supported) return@runCatching null
                klass.getMethod("setThreadAllocatedMemoryEnabled", Boolean::class.javaPrimitiveType)
                    .invoke(threadMx, true)
                current()
            }.getOrNull()
        }
        val allocatedBefore = if (threadMx != null) allocatedBytes.before() else null

        val startNanos = System.nanoTime()
        val result = MaskGeometry.fromOrderedRle(mask, budgets)
        val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000

        assertEquals(OrderedMaskFallbackReason.BUDGET_EXCEEDED, fallback(result))
        assertEquals(0, budgets.componentsBuilt)
        assertTrue(elapsedMs < 5_000) { "conversion took ${elapsedMs}ms" }
        val allocatedAfter = if (allocatedBefore != null) allocatedBytes.current() else null
        if (allocatedBefore != null && allocatedAfter != null) {
            val allocated = allocatedAfter - allocatedBefore
            assertTrue(allocated < 16L * 1024 * 1024) { "conversion allocated $allocated bytes" }
        }
    }

    @Test
    fun `second large mask aborts on the page rle-int budget before scanning`() {
        val budgets = MaskConversionBudgets() // default 200_000 RLE ints per page
        success(MaskGeometry.fromOrderedRle(rle(10, 2, listOf(8, 5)), budgets))
        assertEquals(2, budgets.rleIntsScanned)

        val large = rle(
            200_000,
            1,
            buildList {
                for (i in 0 until 100_000) {
                    add(i * 2)
                    add(1)
                }
            },
        )
        assertEquals(
            OrderedMaskFallbackReason.BUDGET_EXCEEDED,
            fallback(MaskGeometry.fromOrderedRle(large, budgets)),
        )
        // Nothing was counted for the aborted scan.
        assertEquals(2, budgets.rleIntsScanned)
    }
}
