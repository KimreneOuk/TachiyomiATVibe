package eu.kanade.translation.inpainting

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Guards the pure mask-construction and morphology helpers extracted from
 * [SmartBubbleTextCleaner]. These byte-array algorithms were previously private
 * and untestable; they are easy to get wrong (corner geometry, flood-fill edge
 * margins, the dilation quirk), so each behaviour is pinned here.
 */
class BubbleMaskBuilderTest {

    // ---- andMasks ----

    @Test
    fun `andMasks keeps only pixels set in both masks`() {
        val a = byteArrayOf(1, 1, 0, 0)
        val b = byteArrayOf(1, 0, 1, 0)

        BubbleMaskBuilder.andMasks(a, b) shouldBe byteArrayOf(1, 0, 0, 0)
    }

    @Test
    fun `andMasks treats any nonzero byte as set`() {
        // Non-canonical nonzero values (e.g. 7) must count as set, then normalize to 1.
        val a = byteArrayOf(7, 0)
        val b = byteArrayOf(3, 9)

        BubbleMaskBuilder.andMasks(a, b) shouldBe byteArrayOf(1, 0)
    }

    // ---- maskCoverage ----

    @Test
    fun `maskCoverage is the set-pixel percentage`() {
        BubbleMaskBuilder.maskCoverage(byteArrayOf(1, 1, 0, 0)) shouldBe 50f
    }

    @Test
    fun `maskCoverage of an empty mask is zero`() {
        BubbleMaskBuilder.maskCoverage(ByteArray(0)) shouldBe 0f
    }

    // ---- insideRoundedRect ----

    @Test
    fun `insideRoundedRect returns true everywhere when radius is zero`() {
        for (y in 0 until 10) {
            for (x in 0 until 10) {
                BubbleMaskBuilder.insideRoundedRect(x, y, 10, 10, radius = 0) shouldBe true
            }
        }
    }

    @Test
    fun `insideRoundedRect rejects the extreme corner outside the corner circle`() {
        // 10x10 box, radius 4 → corner center at (4,4); the far corner (0,0) is
        // distance ~5.66 > 4 → outside.
        BubbleMaskBuilder.insideRoundedRect(0, 0, 10, 10, radius = 4) shouldBe false
    }

    @Test
    fun `insideRoundedRect accepts a point just inside the corner circle`() {
        // Corner center (4,4); (4,4) itself is distance 0 <= 4 → inside.
        BubbleMaskBuilder.insideRoundedRect(4, 4, 10, 10, radius = 4) shouldBe true
    }

    @Test
    fun `insideRoundedRect accepts any point outside the corner zones`() {
        // Mid-edge and centre are never in a corner zone.
        BubbleMaskBuilder.insideRoundedRect(5, 5, 10, 10, radius = 4) shouldBe true
        BubbleMaskBuilder.insideRoundedRect(5, 0, 10, 10, radius = 4) shouldBe true
    }

    // ---- bubbleInteriorMask ----

    @Test
    fun `bubbleInteriorMask fills the eroded rectangle and clamps to canvas`() {
        // Bubble (1,1)-(5,5) on a 6x6 canvas, erode 1 → interior (2,2)-(4,4).
        val mask = BubbleMaskBuilder.bubbleInteriorMask(intArrayOf(1, 1, 5, 5), width = 6, height = 6, erodePx = 1)

        mask[2 * 6 + 2] shouldBe 1 // interior
        mask[1 * 6 + 1] shouldBe 0 // eroded border
        mask.size shouldBe 36
    }

    @Test
    fun `bubbleInteriorMask of an over-eroded bubble is empty`() {
        val mask = BubbleMaskBuilder.bubbleInteriorMask(intArrayOf(0, 0, 2, 2), width = 4, height = 4, erodePx = 10)

        mask.count { it != 0.toByte() } shouldBe 0
    }

    // ---- roundedAllowedMask ----

    @Test
    fun `roundedAllowedMask fills the box interior minus the corners`() {
        val mask = BubbleMaskBuilder.roundedAllowedMask(
            boxes = listOf(intArrayOf(0, 0, 10, 10)),
            width = 10,
            height = 10,
        )
        // Centre is always set.
        mask[5 * 10 + 5] shouldBe 1
        // Extreme corner is outside the corner circle (radius derived from the
        // 10x10 box clamps to min(8, 20, 5) = 5; corner (0,0) is > 5 from (5,5)).
        mask[0] shouldBe 0
    }

    @Test
    fun `roundedAllowedMask skips degenerate boxes`() {
        val mask = BubbleMaskBuilder.roundedAllowedMask(
            boxes = listOf(intArrayOf(2, 2, 2, 2)), // zero area
            width = 4,
            height = 4,
        )
        mask.count { it != 0.toByte() } shouldBe 0
    }

    // ---- dilateMask (pins the documented quirk) ----

    @Test
    fun `dilateMask with one iteration grows set pixels by a 4-neighbourhood`() {
        // 3x3, single set pixel at centre.
        val mask = ByteArray(9).also { it[4] = 1 }

        val out = BubbleMaskBuilder.dilateMask(mask, width = 3, height = 3, iterations = 1)

        // Centre + up/down/left/right set; corners untouched.
        out.toList() shouldBe listOf(0, 1, 0, 1, 1, 1, 0, 1, 0).map { it.toByte() }
    }

    @Test
    fun `dilateMask re-reads the original mask each iteration, not the running result`() {
        // Regression guard for the documented quirk: because the inner loop reads
        // the ORIGINAL `mask`, multiple iterations do NOT compound a normal
        // multi-pass dilation. A single isolated pixel dilated N times grows to
        // the same plus-shape as 1 iteration (the corners never fill), unlike a
        // true multi-pass dilate which would fill the whole 3x3 by iteration 2.
        val mask = ByteArray(9).also { it[4] = 1 }

        val onePass = BubbleMaskBuilder.dilateMask(mask, width = 3, height = 3, iterations = 1)
        val threePass = BubbleMaskBuilder.dilateMask(mask, width = 3, height = 3, iterations = 3)

        // Identical output under both — proving the quirk (no compaction).
        onePass.toList() shouldBe threePass.toList()
        // Corners stay 0 under both.
        threePass[0] shouldBe 0
        threePass[2] shouldBe 0
        threePass[6] shouldBe 0
        threePass[8] shouldBe 0
    }

    // ---- featherAlpha ----

    @Test
    fun `featherAlpha of an empty mask is all zeros`() {
        val alpha = BubbleMaskBuilder.featherAlpha(ByteArray(16), width = 4, height = 4, featherRadius = 2)

        alpha.toList() shouldBe List(16) { 0f }
    }

    @Test
    fun `featherAlpha makes core pixels fully opaque and neighbours partially opaque`() {
        // 5x5, single core pixel at centre, small radius.
        val mask = ByteArray(25).also { it[12] = 1 }
        val alpha = BubbleMaskBuilder.featherAlpha(mask, width = 5, height = 5, featherRadius = 2)

        // Core is fully opaque.
        alpha[12] shouldBe 1.0f
        // The corner (0,0) is reached by the radius-2 box blur (clipped kernel
        // of 9 cells, one set) → partially opaque, not 0 and not 1.
        val corner = alpha[0]
        (corner > 0f) shouldBe true
        (corner < 1f) shouldBe true
        // A pixel within the kernel but not the core is also partially opaque.
        val neighbour = alpha[7]
        (neighbour > 0f) shouldBe true
        (neighbour < 1f) shouldBe true
    }

    // ---- removeEdgeTouchingComponents ----

    @Test
    fun `removeEdgeTouchingComponents drops a blob touching the 2px border margin`() {
        // 6x6, a single set pixel at (0,0) — within the 2px margin (x<=1 || y<=1).
        val mask = ByteArray(36).also { it[0] = 1 }

        val out = BubbleMaskBuilder.removeEdgeTouchingComponents(mask, width = 6, height = 6)

        out.count { it != 0.toByte() } shouldBe 0
    }

    @Test
    fun `removeEdgeTouchingComponents keeps an interior blob`() {
        // 6x6, a set pixel at (3,3) — outside the 2px margin.
        val mask = ByteArray(36).also { it[3 * 6 + 3] = 1 }

        val out = BubbleMaskBuilder.removeEdgeTouchingComponents(mask, width = 6, height = 6)

        out[3 * 6 + 3] shouldBe 1
    }

    @Test
    fun `removeEdgeTouchingComponents keeps interior blob but drops a connected edge blob`() {
        // 6x6: an interior blob at (3,3) plus a separate edge blob at (0,5).
        val mask = ByteArray(36).also {
            it[3 * 6 + 3] = 1
            it[5 * 6 + 0] = 1
        }

        val out = BubbleMaskBuilder.removeEdgeTouchingComponents(mask, width = 6, height = 6)

        out[3 * 6 + 3] shouldBe 1
        out[5 * 6 + 0] shouldBe 0
    }
}
