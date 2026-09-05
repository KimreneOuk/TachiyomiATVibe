package eu.kanade.translation.inpainting.aot

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class AotBoxGeometryTest {

    @Test
    fun `localizeBox subtracts origin and clamps to local bounds`() {
        AotBoxGeometry.localizeBox(
            box = intArrayOf(8, 9, 25, 35),
            originX = 10,
            originY = 20,
            width = 12,
            height = 10,
        )!!.toList() shouldBe listOf(0, 0, 12, 10)
    }

    @Test
    fun `localizeBox returns null when clamped bounds are invalid`() {
        AotBoxGeometry.localizeBox(
            box = intArrayOf(0, 0, 5, 5),
            originX = 10,
            originY = 10,
            width = 20,
            height = 20,
        ) shouldBe null
    }

    @Test
    fun `paddedUnionBounds returns null for empty list`() {
        AotBoxGeometry.paddedUnionBounds(emptyList(), width = 100, height = 100, pad = 8) shouldBe null
    }

    @Test
    fun `paddedUnionBounds applies padding and clamps to page bounds`() {
        AotBoxGeometry.paddedUnionBounds(
            boxes = listOf(intArrayOf(5, 6, 20, 30), intArrayOf(80, 40, 99, 90)),
            width = 100,
            height = 80,
            pad = 10,
        )!!.toList() shouldBe listOf(0, 0, 100, 80)
    }

    @Test
    fun `paddedUnionBounds returns null when final bounds are invalid`() {
        AotBoxGeometry.paddedUnionBounds(
            boxes = listOf(intArrayOf(10, 10, 10, 20)),
            width = 100,
            height = 100,
            pad = 0,
        ) shouldBe null
    }

    @Test
    fun `centeredReportCrop returns null for empty boxes`() {
        AotBoxGeometry.centeredReportCrop(emptyList(), width = 100, height = 100, contextSize = 64) shouldBe null
    }

    @Test
    fun `centeredReportCrop centers square crop around union center when inside bounds`() {
        AotBoxGeometry.centeredReportCrop(
            boxes = listOf(intArrayOf(40, 50, 60, 70)),
            width = 200,
            height = 200,
            contextSize = 64,
        )!!.toList() shouldBe listOf(18, 28, 82, 92)
    }

    @Test
    fun `centeredReportCrop clamps crop to page edge`() {
        AotBoxGeometry.centeredReportCrop(
            boxes = listOf(intArrayOf(2, 2, 8, 8)),
            width = 100,
            height = 80,
            contextSize = 64,
        )!!.toList() shouldBe listOf(0, 0, 64, 64)
    }

    @Test
    fun `centeredReportCrop returns an in-bounds crop on a sub-512 page`() {
        val crop = AotBoxGeometry.centeredReportCrop(
            boxes = listOf(intArrayOf(180, 260, 220, 300)),
            width = 400,
            height = 600,
            contextSize = 512,
        )!!

        crop.toList() shouldBe listOf(0, 80, 400, 480)
        (crop[0] >= 0 && crop[1] >= 0 && crop[2] <= 400 && crop[3] <= 600) shouldBe true
    }

    @Test
    fun `centeredReportCrop uses the whole short side on a small square page`() {
        AotBoxGeometry.centeredReportCrop(
            boxes = listOf(intArrayOf(120, 120, 180, 180)),
            width = 300,
            height = 300,
            contextSize = 512,
        )!!.toList() shouldBe listOf(0, 0, 300, 300)
    }

    @Test
    fun `findParentBubble chooses smallest bubble containing text center`() {
        val outer = intArrayOf(0, 0, 100, 100)
        val inner = intArrayOf(20, 20, 60, 60)

        AotBoxGeometry.findParentBubble(
            textBox = intArrayOf(30, 30, 40, 40),
            bubbleBoxes = listOf(outer, inner),
        ) shouldBe inner
    }

    @Test
    fun `findParentBubble returns null when text center is outside all bubbles`() {
        AotBoxGeometry.findParentBubble(
            textBox = intArrayOf(80, 80, 90, 90),
            bubbleBoxes = listOf(intArrayOf(0, 0, 50, 50)),
        ) shouldBe null
    }

    @Test
    fun `overlapsAnyBubble returns false for empty bubbles and short text box`() {
        AotBoxGeometry.overlapsAnyBubble(intArrayOf(0, 0, 10, 10), emptyList()) shouldBe false
        AotBoxGeometry.overlapsAnyBubble(intArrayOf(0, 0, 10), listOf(intArrayOf(0, 0, 10, 10))) shouldBe false
    }

    @Test
    fun `overlapsAnyBubble accepts at threshold and rejects below threshold`() {
        val textBox = intArrayOf(0, 0, 10, 10)

        AotBoxGeometry.overlapsAnyBubble(
            textBox,
            bubbleBoxes = listOf(intArrayOf(0, 0, 3, 3)),
            minOverlapFraction = 0.10f,
        ) shouldBe false
        AotBoxGeometry.overlapsAnyBubble(
            textBox,
            bubbleBoxes = listOf(intArrayOf(0, 0, 3, 4)),
            minOverlapFraction = 0.12f,
        ) shouldBe true
    }

    @Test
    fun `clusterFreeTextGroups handles empty and single groups`() {
        AotBoxGeometry.clusterFreeTextGroups(emptyList()) shouldBe emptyList()
        val single = listOf(intArrayOf(10, 10, 50, 50))
        AotBoxGeometry.clusterFreeTextGroups(listOf(single)).size shouldBe 1
    }

    @Test
    fun `clusterFreeTextGroups merges adjacent groups within maxContextSize`() {
        val g1 = listOf(intArrayOf(100, 100, 150, 200))
        val g2 = listOf(intArrayOf(160, 110, 200, 210))
        val g3 = listOf(intArrayOf(220, 120, 280, 190))

        val clusters = AotBoxGeometry.clusterFreeTextGroups(listOf(g1, g2, g3), maxContextSize = 512)
        clusters.size shouldBe 1
        clusters[0].size shouldBe 3
    }

    @Test
    fun `clusterFreeTextGroups isolates distant groups exceeding maxContextSize`() {
        val topGroup = listOf(intArrayOf(50, 50, 100, 100))
        val bottomGroup = listOf(intArrayOf(50, 600, 100, 650))

        val clusters = AotBoxGeometry.clusterFreeTextGroups(listOf(topGroup, bottomGroup), maxContextSize = 512)
        clusters.size shouldBe 2
    }

    @Test
    fun `clusterFreeTextGroups clusters multi-panel page into separate spatial groups`() {
        // Panel 1 (top): 3 text lines
        val p1_1 = listOf(intArrayOf(50, 100, 90, 250))
        val p1_2 = listOf(intArrayOf(110, 120, 150, 260))
        val p1_3 = listOf(intArrayOf(170, 100, 210, 240))

        // Panel 2 (bottom): 2 text lines
        val p2_1 = listOf(intArrayOf(60, 900, 100, 1050))
        val p2_2 = listOf(intArrayOf(120, 920, 160, 1060))

        val clusters = AotBoxGeometry.clusterFreeTextGroups(
            listOf(p1_1, p1_2, p1_3, p2_1, p2_2),
            maxContextSize = 512,
        )

        clusters.size shouldBe 2
        val totalBoxes = clusters.sumOf { it.size }
        totalBoxes shouldBe 5
    }
}
