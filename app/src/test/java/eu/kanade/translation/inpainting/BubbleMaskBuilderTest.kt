package eu.kanade.translation.inpainting

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Guards the pure mask-construction and morphology helpers extracted from
 * [SmartBubbleTextCleaner]. These byte-array algorithms were previously private
 * and untestable; they are easy to get wrong (corner geometry, flood-fill edge
 * margins, multi-pass dilation compounding), so each behaviour is pinned here.
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

    // ---- dilateMask (true multi-pass dilation) ----

    @Test
    fun `dilateMask with one iteration grows set pixels by a 4-neighbourhood`() {
        // 3x3, single set pixel at centre.
        val mask = ByteArray(9).also { it[4] = 1 }

        val out = BubbleMaskBuilder.dilateMask(mask, width = 3, height = 3, iterations = 1)

        // Centre + up/down/left/right set; corners untouched after a single pass.
        out.toList() shouldBe listOf(0, 1, 0, 1, 1, 1, 0, 1, 0).map { it.toByte() }
    }

    @Test
    fun `dilateMask compounds growth across iterations forming a diamond by pass 2`() {
        // Regression guard for the dilation quirk: the implementation MUST read
        // the running result each pass (not the original `mask`), so iterations
        // compound. A 4-neighbourhood dilation reaches pixels by Manhattan
        // distance, so 2 passes from a single centre pixel fill a diamond (L1
        // ball) of radius 2: 1 + 4 + 8 = 13 cells. The old quirk-capped
        // implementation only ever produced the 5-cell plus-shape of pass 1
        // regardless of iteration count.
        val mask = ByteArray(25).also { it[12] = 1 }

        val twoPass = BubbleMaskBuilder.dilateMask(mask, width = 5, height = 5, iterations = 2)

        twoPass.count { it != 0.toByte() } shouldBe 13
        // Diamond corners (the 4 corners of the 5x5, Manhattan distance 4 from
        // centre) are NOT reached at 2 iterations.
        twoPass[0] shouldBe 0
        twoPass[4] shouldBe 0
        twoPass[20] shouldBe 0
        twoPass[24] shouldBe 0
    }

    @Test
    fun `dilateMask grows a single pixel by exactly iterations pixels along the axes`() {
        // 9x9 grid, single centre pixel at (4,4), iterations = 3 → arms reach
        // centre ± 3 along each axis (Manhattan distance ≤ 3), i.e. x in [1,7]
        // on the centre row and y in [1,7] on the centre column. The far ends
        // (x=0, x=8) are at Manhattan distance 4 and are NOT reached.
        val mask = ByteArray(81).also { it[40] = 1 }

        val threePass = BubbleMaskBuilder.dilateMask(mask, width = 9, height = 9, iterations = 3)

        // Centre row: x in [1,7] set; x=0 and x=8 NOT set (distance 4).
        for (x in 1..7) {
            threePass[4 * 9 + x] shouldBe 1
        }
        threePass[4 * 9 + 0] shouldBe 0
        threePass[4 * 9 + 8] shouldBe 0
        // Centre column: y in [1,7] set; y=0 and y=8 NOT set.
        for (y in 1..7) {
            threePass[y * 9 + 4] shouldBe 1
        }
        threePass[0 * 9 + 4] shouldBe 0
        threePass[8 * 9 + 4] shouldBe 0
        // Canvas corners never reached.
        threePass[0] shouldBe 0
        threePass[8 * 9 + 8] shouldBe 0
    }

    @Test
    fun `dilateMask with zero iterations returns a copy of the input`() {
        val mask = ByteArray(9).also { it[4] = 1 }

        val out = BubbleMaskBuilder.dilateMask(mask, width = 3, height = 3, iterations = 0)

        out.toList() shouldBe mask.toList()
        // Returned copy, not the same reference.
        (out !== mask) shouldBe true
    }

    // ---- dilateMaskDisk (circular structuring element) ----

    @Test
    fun `dilateMaskDisk with zero radius returns a copy of the input`() {
        val mask = ByteArray(9).also { it[4] = 1 }

        val out = BubbleMaskBuilder.dilateMaskDisk(mask, width = 3, height = 3, radius = 0)

        out.toList() shouldBe mask.toList()
        (out !== mask) shouldBe true
    }

    @Test
    fun `dilateMaskDisk radius 1 from a single pixel fills the 4-neighbourhood plus the centre`() {
        // radius 1 disk = {dx²+dy² ≤ 1} = centre + up/down/left/right (the
        // 4-neighbourhood). Same 5-cell plus-shape as dilateMask iterations=1.
        val mask = ByteArray(9).also { it[4] = 1 }

        val out = BubbleMaskBuilder.dilateMaskDisk(mask, width = 3, height = 3, radius = 1)

        out.toList() shouldBe listOf(0, 1, 0, 1, 1, 1, 0, 1, 0).map { it.toByte() }
    }

    @Test
    fun `dilateMaskDisk radius 2 fills a true circle including diagonals`() {
        // radius 2 disk = {dx²+dy² ≤ 4}. From a centre pixel this reaches the
        // 4 axis neighbours (distance 1), the 4 diagonals (distance sqrt(2)≈1.41),
        // and the axis-2 neighbours (distance 2). The (2,2) corner diagonal
        // (distance sqrt(8)≈2.83) is NOT reached — that is the key difference
        // from a square SE and what rounds rectangle corners.
        // On a 5x5 grid, centre (2,2). Set cells: 13 (centre + 4 axis-1 + 4
        // diagonal + 4 axis-2). Corners (0,0),(0,4),(4,0),(4,4) NOT set.
        val mask = ByteArray(25).also { it[12] = 1 }

        val out = BubbleMaskBuilder.dilateMaskDisk(mask, width = 5, height = 5, radius = 2)

        out.count { it != 0.toByte() } shouldBe 13
        // Corners of the 5x5 are at distance sqrt(8) > 2 — NOT reached.
        out[0] shouldBe 0
        out[4] shouldBe 0
        out[20] shouldBe 0
        out[24] shouldBe 0
        // Diagonals at distance sqrt(2) ARE reached (this is what rounds corners).
        out[6] shouldBe 1   // (1,1)
        out[8] shouldBe 1   // (3,1)
        out[16] shouldBe 1  // (1,3)
        out[18] shouldBe 1  // (3,3)
    }

    @Test
    fun `dilateMaskDisk rounds rectangle corners unlike the diamond dilateMask`() {
        // A 3x3 filled rectangle (the kind of mask buildTightTextRegionMask
        // produces) dilated by radius 2: the disk grows the rectangle but
        // ROUNDS its corners (the corner cells beyond the disk are not set),
        // whereas the 4-neighbourhood dilateMask chamfers them at 45°.
        // On a 7x7 canvas, rectangle at rows 2-4 cols 2-4.
        val rect = ByteArray(49)
        for (y in 2..4) for (x in 2..4) rect[y * 7 + x] = 1

        val diskOut = BubbleMaskBuilder.dilateMaskDisk(rect, width = 7, height = 7, radius = 2)

        // The corner-most grown cells (e.g. (0,0)) are outside the disk from
        // every rectangle pixel, so they stay 0 — the corner is rounded.
        diskOut[0] shouldBe 0
        diskOut[6] shouldBe 0
        diskOut[42] shouldBe 0
        diskOut[48] shouldBe 0
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
