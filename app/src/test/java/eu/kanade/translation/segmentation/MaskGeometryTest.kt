package eu.kanade.translation.segmentation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MaskGeometryTest {
    @Test
    fun `exact containment respects concavity and holes`() {
        val geometry = geometry("111", "101", "111")
        assertTrue(geometry.containsPoint(0, 0))
        assertFalse(geometry.containsPoint(1, 1))
        assertFalse(geometry.containsRectangle(0, 0, 3, 3))
        assertTrue(geometry.containsRectangle(0, 0, 3, 1))
    }

    @Test
    fun `four connected components preserve corridor and split islands`() {
        assertEquals(1, geometry("100", "111", "001").components.size)
        val islands = geometry("1001", "0000", "1001")
        assertEquals(4, islands.components.size)
        assertNotEquals(islands.components[0].stableKey, islands.components[1].stableKey)
    }

    @Test
    fun `component assignment uses exact overlap with deterministic ambiguity`() {
        val islands = geometry("11011")
        assertEquals(0, islands.componentForRectangle(0, 0, 2, 1))
        assertNull(islands.componentForRectangle(0, 0, 5, 1))
    }

    @Test
    fun `inset safety handles tiny and empty erosion`() {
        val square = geometry("111", "111", "111")
        assertTrue(square.isRectangleSafe(1, 1, 2, 2, inset = 1))
        assertFalse(square.isRectangleSafe(0, 0, 1, 1, inset = 1))
        assertFalse(geometry("1").isRectangleSafe(0, 0, 1, 1, inset = 1))
    }

    @Test
    fun `source and exposed collections cannot mutate geometry snapshots`() {
        val source = mutableListOf(MaskGeometry.RowSpan(0, 0, 2))
        val geometry = MaskGeometry.fromSpans(3, 1, source)
        val key = geometry.stableKey
        source.clear()

        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (geometry.spans as MutableList<MaskGeometry.RowSpan>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (geometry.components as MutableList<MaskGeometry.Component>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (geometry.components.single().spans as MutableList<MaskGeometry.RowSpan>).clear()
        }
        assertTrue(geometry.containsPoint(1, 0))
        assertEquals(key, geometry.stableKey)
    }

    @Test
    fun `adjacent same-row spans are normalized before component construction`() {
        val geometry = MaskGeometry.fromSpans(
            5,
            1,
            listOf(MaskGeometry.RowSpan(0, 0, 2), MaskGeometry.RowSpan(0, 2, 5)),
        )

        assertEquals(listOf(MaskGeometry.RowSpan(0, 0, 5)), geometry.spans)
        assertTrue(geometry.containsRectangle(0, 0, 5, 1))
        assertEquals(1, geometry.components.size)
    }

    @Test
    fun `rejects invalid dimensions empty masks and configured limits`() {
        assertThrows(IllegalArgumentException::class.java) {
            MaskGeometry.fromSpans(0, 1, listOf(MaskGeometry.RowSpan(0, 0, 1)))
        }
        assertThrows(IllegalArgumentException::class.java) { MaskGeometry.fromSpans(2, 1, emptyList()) }
        assertThrows(IllegalArgumentException::class.java) {
            MaskGeometry.fromSpans(
                3,
                1,
                listOf(MaskGeometry.RowSpan(0, 0, 1), MaskGeometry.RowSpan(0, 2, 3)),
                maxSpans = 1,
            )
        }
    }

    private fun geometry(vararg rows: String): MaskGeometry {
        val spans = mutableListOf<MaskGeometry.RowSpan>()
        rows.forEachIndexed { y, row ->
            var x = 0
            while (x < row.length) {
                if (row[x] == '0') {
                    x++
                    continue
                }
                val start = x
                while (x < row.length && row[x] == '1') x++
                spans += MaskGeometry.RowSpan(y, start, x)
            }
        }
        return MaskGeometry.fromSpans(rows.first().length, rows.size, spans)
    }
}
