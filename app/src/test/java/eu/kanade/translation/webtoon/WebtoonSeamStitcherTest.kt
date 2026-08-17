package eu.kanade.translation.webtoon

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class WebtoonSeamStitcherTest {

    @Test
    fun `isBottomEdgeCandidate flags bubbles intersecting bottom boundary`() {
        val pageHeight = 2000

        // Bubble clearly in the middle
        val midBubble = intArrayOf(100, 500, 300, 700)
        WebtoonSeamStitcher.isBottomEdgeCandidate(midBubble, pageHeight) shouldBe false

        // Bubble near bottom boundary (e.g. y2 = 1990)
        val edgeBubble = intArrayOf(100, 1850, 300, 1990)
        WebtoonSeamStitcher.isBottomEdgeCandidate(edgeBubble, pageHeight) shouldBe true

        // Bubble extending exactly to or past the bottom border
        val cutBubble = intArrayOf(100, 1900, 300, 2000)
        WebtoonSeamStitcher.isBottomEdgeCandidate(cutBubble, pageHeight) shouldBe true
    }

    @Test
    fun `isTopEdgeCandidate flags bubbles intersecting top boundary`() {
        // Bubble starting at the very top (y1 = 0)
        val topBubble = intArrayOf(100, 0, 300, 150)
        WebtoonSeamStitcher.isTopEdgeCandidate(topBubble) shouldBe true

        // Bubble starting near top (y1 = 10)
        val nearTopBubble = intArrayOf(100, 10, 300, 160)
        WebtoonSeamStitcher.isTopEdgeCandidate(nearTopBubble) shouldBe true

        // Bubble deeper in the page (y1 = 100)
        val deepBubble = intArrayOf(100, 100, 300, 250)
        WebtoonSeamStitcher.isTopEdgeCandidate(deepBubble) shouldBe false
    }

    @Test
    fun `computeSeamConfig accurately calculates seam canvas dimensions`() {
        val config = WebtoonSeamStitcher.computeSeamConfig(
            pageNWidth = 1000,
            pageNHeight = 2000,
            pageNPlus1Width = 1000,
            pageNPlus1Height = 2200,
            maxSeamMarginPx = 300,
        )

        config.seamWidth shouldBe 1000
        config.pageNBottomHeight shouldBe 300
        config.pageNPlus1TopHeight shouldBe 300
        config.totalSeamHeight shouldBe 600
    }

    @Test
    fun `partitionSeamBox splits bounding box coordinates cleanly across page boundary`() {
        // Seam canvas is 600px tall (Page N bottom is 0..300, Page N+1 top is 300..600).
        // Page N total height = 2000.
        // A bubble spans from y=200 to y=450 in the seam canvas (100px on Page N, 150px on Page N+1).
        val seamBbox = intArrayOf(150, 200, 450, 450)
        val pageNBottomHeight = 300
        val pageNTotalHeight = 2000

        val partitioned = WebtoonSeamStitcher.partitionSeamBox(
            seamBbox = seamBbox,
            pageNBottomHeight = pageNBottomHeight,
            pageNTotalHeight = pageNTotalHeight,
        )

        // Page N portion: top = 2000 - 300 + 200 = 1900, bottom = 2000
        val pageNBox = partitioned.pageNInpaintBox!!
        pageNBox[0] shouldBe 150
        pageNBox[1] shouldBe 1900
        pageNBox[2] shouldBe 450
        pageNBox[3] shouldBe 2000

        // Page N+1 portion: top = 0, bottom = 450 - 300 = 150
        val pageNPlus1Box = partitioned.pageNPlus1InpaintBox!!
        pageNPlus1Box[0] shouldBe 150
        pageNPlus1Box[1] shouldBe 0
        pageNPlus1Box[2] shouldBe 450
        pageNPlus1Box[3] shouldBe 150
    }

    @Test
    fun `partitionTextLines partitions multi-line text proportionally across seam`() {
        val lines = listOf("Line 1", "Line 2", "Line 3", "Line 4")
        // 50% split
        val (n1, nPlus1_1) = WebtoonSeamStitcher.partitionTextLines(lines, 0.5f)
        n1 shouldBe listOf("Line 1", "Line 2")
        nPlus1_1 shouldBe listOf("Line 3", "Line 4")

        // 75% on Page N
        val (n2, nPlus1_2) = WebtoonSeamStitcher.partitionTextLines(lines, 0.75f)
        n2 shouldBe listOf("Line 1", "Line 2", "Line 3")
        nPlus1_2 shouldBe listOf("Line 4")
    }
}
