package eu.kanade.translation.engines.vision.ocr

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ReadingOrderSorterTest {

    @Test
    fun `single panel passthrough returns input unchanged`() {
        val panels = listOf(floatArrayOf(0f, 0f, 10f, 10f))
        ReadingOrderSorter.readingOrderPanels(panels, rtl = false) shouldBe panels
    }

    @Test
    fun `empty list passthrough`() {
        ReadingOrderSorter.readingOrderPanels(emptyList(), rtl = false) shouldBe emptyList()
    }

    @Test
    fun `two stacked panels order top then bottom regardless of rtl`() {
        val top = floatArrayOf(0f, 0f, 100f, 50f)
        val bottom = floatArrayOf(0f, 60f, 100f, 110f)
        val out = ReadingOrderSorter.readingOrderPanels(listOf(bottom, top), rtl = false)
        out[0] shouldBe top
        out[1] shouldBe bottom
    }

    @Test
    fun `two side-by-side panels LTR orders left first`() {
        val left = floatArrayOf(0f, 0f, 50f, 100f)
        val right = floatArrayOf(60f, 0f, 110f, 100f)
        val out = ReadingOrderSorter.readingOrderPanels(listOf(right, left), rtl = false)
        out[0] shouldBe left
        out[1] shouldBe right
    }

    @Test
    fun `two side-by-side panels RTL orders right first`() {
        val left = floatArrayOf(0f, 0f, 50f, 100f)
        val right = floatArrayOf(60f, 0f, 110f, 100f)
        val out = ReadingOrderSorter.readingOrderPanels(listOf(left, right), rtl = true)
        out[0] shouldBe right
        out[1] shouldBe left
    }

    @Test
    fun `touching intervals are not split (zero-width gutter)`() {
        // Two panels touching at x=50 (no gap >= 1px) -> no vertical cut,
        // no horizontal cut -> column fallback. LTR -> left first.
        val left = floatArrayOf(0f, 0f, 50f, 100f)
        val right = floatArrayOf(50f, 0f, 100f, 100f)
        val out = ReadingOrderSorter.readingOrderPanels(listOf(right, left), rtl = false)
        out[0] shouldBe left
        out[1] shouldBe right
    }

    @Test
    fun `mutually overlapping panels use column fallback LTR`() {
        // Three panels all overlapping at centre cx ~50, same y -> sorted by cx asc.
        val a = floatArrayOf(40f, 0f, 60f, 100f) // cx=50
        val b = floatArrayOf(20f, 0f, 55f, 100f) // cx=37.5
        val c = floatArrayOf(45f, 0f, 80f, 100f) // cx=62.5
        val out = ReadingOrderSorter.readingOrderPanels(listOf(a, c, b), rtl = false)
        out[0] shouldBe b
        out[1] shouldBe a
        out[2] shouldBe c
    }

    @Test
    fun `mutually overlapping panels use column fallback RTL`() {
        val a = floatArrayOf(40f, 0f, 60f, 100f) // cx=50
        val b = floatArrayOf(20f, 0f, 55f, 100f) // cx=37.5
        val c = floatArrayOf(45f, 0f, 80f, 100f) // cx=62.5
        val out = ReadingOrderSorter.readingOrderPanels(listOf(a, b, c), rtl = true)
        out[0] shouldBe c
        out[1] shouldBe a
        out[2] shouldBe b
    }

    @Test
    fun `column fallback tie-breaks top to bottom by y`() {
        // Same cx, different y -> top first regardless of rtl.
        val top = floatArrayOf(40f, 0f, 60f, 40f)
        val bottom = floatArrayOf(40f, 50f, 60f, 90f)
        val outLtr = ReadingOrderSorter.readingOrderPanels(listOf(bottom, top), rtl = false)
        outLtr[0] shouldBe top
        outLtr[1] shouldBe bottom
        val outRtl = ReadingOrderSorter.readingOrderPanels(listOf(bottom, top), rtl = true)
        outRtl[0] shouldBe top
        outRtl[1] shouldBe bottom
    }

    @Test
    fun `nested grid orders top row before bottom row LTR`() {
        // 2x2 grid with clear gutters.
        val tl = floatArrayOf(0f, 0f, 40f, 40f)
        val tr = floatArrayOf(60f, 0f, 100f, 40f)
        val bl = floatArrayOf(0f, 60f, 40f, 100f)
        val br = floatArrayOf(60f, 60f, 100f, 100f)
        val out = ReadingOrderSorter.readingOrderPanels(listOf(br, bl, tr, tl), rtl = false)
        out[0] shouldBe tl
        out[1] shouldBe tr
        out[2] shouldBe bl
        out[3] shouldBe br
    }

    @Test
    fun `nested grid orders top row before bottom row RTL right column first`() {
        val tl = floatArrayOf(0f, 0f, 40f, 40f)
        val tr = floatArrayOf(60f, 0f, 100f, 40f)
        val bl = floatArrayOf(0f, 60f, 40f, 100f)
        val br = floatArrayOf(60f, 60f, 100f, 100f)
        val out = ReadingOrderSorter.readingOrderPanels(listOf(tl, bl, tr, br), rtl = true)
        out[0] shouldBe tr
        out[1] shouldBe tl
        out[2] shouldBe br
        out[3] shouldBe bl
    }
}
