package eu.kanade.translation.engines.inpainting

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class InpaintBoxGeometryTest {

    @Test
    fun `localizeBox subtracts origin and clamps to local bounds`() {
        InpaintBoxGeometry.localizeBox(
            box = intArrayOf(8, 9, 25, 35),
            originX = 10,
            originY = 20,
            width = 12,
            height = 10,
        )!!.toList() shouldBe listOf(0, 0, 12, 10)
    }

    @Test
    fun `localizeBox returns null when clamped bounds are invalid`() {
        InpaintBoxGeometry.localizeBox(
            box = intArrayOf(0, 0, 5, 5),
            originX = 10,
            originY = 10,
            width = 20,
            height = 20,
        ) shouldBe null
    }

    @Test
    fun `paddedUnionBounds returns null for empty list`() {
        InpaintBoxGeometry.paddedUnionBounds(emptyList(), width = 100, height = 100, pad = 8) shouldBe null
    }

    @Test
    fun `paddedUnionBounds applies padding and clamps to page bounds`() {
        InpaintBoxGeometry.paddedUnionBounds(
            boxes = listOf(intArrayOf(5, 6, 20, 30), intArrayOf(80, 40, 99, 90)),
            width = 100,
            height = 80,
            pad = 10,
        )!!.toList() shouldBe listOf(0, 0, 100, 80)
    }

    @Test
    fun `paddedUnionBounds returns null when final bounds are invalid`() {
        InpaintBoxGeometry.paddedUnionBounds(
            boxes = listOf(intArrayOf(10, 10, 10, 20)),
            width = 100,
            height = 100,
            pad = 0,
        ) shouldBe null
    }

    @Test
    fun `centeredReportCrop returns null for empty boxes`() {
        InpaintBoxGeometry.centeredReportCrop(emptyList(), width = 100, height = 100, contextSize = 64) shouldBe null
    }

    @Test
    fun `centeredReportCrop centers square crop around union center when inside bounds`() {
        InpaintBoxGeometry.centeredReportCrop(
            boxes = listOf(intArrayOf(40, 50, 60, 70)),
            width = 200,
            height = 200,
            contextSize = 64,
        )!!.toList() shouldBe listOf(18, 28, 82, 92)
    }

    @Test
    fun `centeredReportCrop clamps crop to page edge`() {
        InpaintBoxGeometry.centeredReportCrop(
            boxes = listOf(intArrayOf(2, 2, 8, 8)),
            width = 100,
            height = 80,
            contextSize = 64,
        )!!.toList() shouldBe listOf(0, 0, 64, 64)
    }

    @Test
    fun `centeredReportCrop returns an in-bounds crop on a sub-512 page`() {
        val crop = InpaintBoxGeometry.centeredReportCrop(
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
        InpaintBoxGeometry.centeredReportCrop(
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

        InpaintBoxGeometry.findParentBubble(
            textBox = intArrayOf(30, 30, 40, 40),
            bubbleBoxes = listOf(outer, inner),
        ) shouldBe inner
    }

    @Test
    fun `findParentBubble returns null when text center is outside all bubbles`() {
        InpaintBoxGeometry.findParentBubble(
            textBox = intArrayOf(80, 80, 90, 90),
            bubbleBoxes = listOf(intArrayOf(0, 0, 50, 50)),
        ) shouldBe null
    }

    @Test
    fun `overlapsAnyBubble returns false for empty bubbles and short text box`() {
        InpaintBoxGeometry.overlapsAnyBubble(intArrayOf(0, 0, 10, 10), emptyList()) shouldBe false
        InpaintBoxGeometry.overlapsAnyBubble(intArrayOf(0, 0, 10), listOf(intArrayOf(0, 0, 10, 10))) shouldBe false
    }

    @Test
    fun `overlapsAnyBubble accepts at threshold and rejects below threshold`() {
        val textBox = intArrayOf(0, 0, 10, 10)

        InpaintBoxGeometry.overlapsAnyBubble(
            textBox,
            bubbleBoxes = listOf(intArrayOf(0, 0, 3, 3)),
            minOverlapFraction = 0.10f,
        ) shouldBe false
        InpaintBoxGeometry.overlapsAnyBubble(
            textBox,
            bubbleBoxes = listOf(intArrayOf(0, 0, 3, 4)),
            minOverlapFraction = 0.12f,
        ) shouldBe true
    }

    @Test
    fun `clusterFreeTextGroups handles empty and single groups`() {
        InpaintBoxGeometry.clusterFreeTextGroups(emptyList()) shouldBe emptyList()
        val single = listOf(intArrayOf(10, 10, 50, 50))
        InpaintBoxGeometry.clusterFreeTextGroups(listOf(single)).size shouldBe 1
    }

    @Test
    fun `clusterFreeTextGroups merges adjacent groups within maxContextSize`() {
        val g1 = listOf(intArrayOf(100, 100, 150, 200))
        val g2 = listOf(intArrayOf(160, 110, 200, 210))
        val g3 = listOf(intArrayOf(220, 120, 280, 190))

        val clusters = InpaintBoxGeometry.clusterFreeTextGroups(listOf(g1, g2, g3), maxContextSize = 512)
        clusters.size shouldBe 1
        clusters[0].size shouldBe 3
    }

    @Test
    fun `clusterFreeTextGroups batches multiple nearby free text groups into one pass`() {
        // 3 separate free-text lines in the same manga panel
        val col1 = listOf(intArrayOf(100, 150, 140, 400))
        val col2 = listOf(intArrayOf(180, 160, 220, 420))
        val col3 = listOf(intArrayOf(260, 150, 300, 390))

        val clusters = InpaintBoxGeometry.clusterFreeTextGroups(listOf(col1, col2, col3), maxContextSize = 464)
        clusters.size shouldBe 1
        clusters[0].size shouldBe 3
    }

    @Test
    fun `clusterFreeTextGroups isolates distant groups exceeding maxContextSize`() {
        val topGroup = listOf(intArrayOf(50, 50, 100, 100))
        val bottomGroup = listOf(intArrayOf(50, 600, 100, 650))

        val clusters = InpaintBoxGeometry.clusterFreeTextGroups(listOf(topGroup, bottomGroup), maxContextSize = 512)
        clusters.size shouldBe 2
    }

    @Test
    fun `clusterFreeTextGroups clusters multi-panel page into separate spatial groups`() {
        // Panel 1 (top): 3 text lines
        val p1First = listOf(intArrayOf(50, 100, 90, 250))
        val p1Second = listOf(intArrayOf(110, 120, 150, 260))
        val p1Third = listOf(intArrayOf(170, 100, 210, 240))

        // Panel 2 (bottom): 2 text lines
        val p2First = listOf(intArrayOf(60, 900, 100, 1050))
        val p2Second = listOf(intArrayOf(120, 920, 160, 1060))

        val clusters = InpaintBoxGeometry.clusterFreeTextGroups(
            listOf(p1First, p1Second, p1Third, p2First, p2Second),
            maxContextSize = 512,
        )

        clusters.size shouldBe 2
        val totalBoxes = clusters.sumOf { it.size }
        totalBoxes shouldBe 5
    }

    @Test
    fun `small bounds yield a single tile`() {
        InpaintBoxGeometry.tileBounds(10, 20, 200, 300).map { it.toList() } shouldBe
            listOf(listOf(10, 20, 200, 300))
    }

    @Test
    fun `oversized bounds are fully covered by overlapping tiles`() {
        val tiles = InpaintBoxGeometry.tileBounds(0, 0, 1300, 700)
        val covered = BooleanArray(1300 * 700)
        for (tile in tiles) {
            (tile[2] - tile[0] <= 448 && tile[3] - tile[1] <= 448) shouldBe true
            for (y in tile[1] until tile[3]) {
                for (x in tile[0] until tile[2]) {
                    covered[y * 1300 + x] = true
                }
            }
        }
        covered.all { it } shouldBe true
    }

    @Test
    fun `adjacent tiles overlap so seams have context`() {
        val tiles = InpaintBoxGeometry.tileBounds(0, 0, 1300, 100)
        val xs = tiles.sortedBy { it[0] }.map { it[0] to it[2] }
        for (index in 1 until xs.size) {
            (xs[index].first < xs[index - 1].second) shouldBe true
        }
    }

    private fun mask(width: Int, height: Int, vararg boxes: IntArray): ByteArray {
        val result = ByteArray(width * height)
        for (box in boxes) {
            for (y in box[1] until box[3]) {
                for (x in box[0] until box[2]) {
                    result[y * width + x] = 1
                }
            }
        }
        return result
    }

    @Test
    fun `far-apart bubbles form separate clusters`() {
        val clusters = InpaintBoxGeometry.componentClusters(
            mask(200, 200, intArrayOf(10, 10, 50, 50), intArrayOf(150, 150, 190, 190)),
            width = 200,
            height = 200,
        )

        clusters.map { it.toList() } shouldBe listOf(listOf(10, 10, 50, 50), listOf(150, 150, 190, 190))
    }

    @Test
    fun `bubbles within the gap merge into one cluster`() {
        val clusters = InpaintBoxGeometry.componentClusters(
            mask(200, 100, intArrayOf(0, 10, 50, 50), intArrayOf(100, 10, 150, 50)),
            width = 200,
            height = 100,
            gap = 64,
        )

        clusters.size shouldBe 1
        clusters[0].toList() shouldBe listOf(0, 10, 150, 50)
    }

    @Test
    fun `diagonal adjacency within gap merges and empty mask yields no clusters`() {
        InpaintBoxGeometry.componentClusters(ByteArray(100 * 100), 100, 100) shouldBe emptyList()
        val clusters = InpaintBoxGeometry.componentClusters(
            mask(100, 100, intArrayOf(5, 5, 20, 20), intArrayOf(25, 25, 40, 40)),
            100,
            100,
            gap = 8,
        )

        clusters.size shouldBe 1
    }
}
