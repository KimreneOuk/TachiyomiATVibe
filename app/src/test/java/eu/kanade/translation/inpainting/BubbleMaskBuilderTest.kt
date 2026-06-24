package eu.kanade.translation.inpainting

import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThan
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

    // ---- buildRectMask (paddle_boxes erase mask) ----
    // Port of build_rect_mask in tools/inpaint-debug-viewer/server.py: every
    // PaddleOCR-v6 line box → solid padded rectangle → disk dilate. This is the
    // erase target for both the AOT/neural and FAST free-text paths.

    @Test
    fun `buildRectMask fills a solid padded rectangle for one box without dilation`() {
        // 10x8 canvas, box [3,2,5,4] (a 2x2 box), pad 1, no dilation.
        // Padded box = [2,1,6,5] → rows 1..4, cols 2..5 (4 rows x 4 cols = 16 px).
        val mask = BubbleMaskBuilder.buildRectMask(
            boxes = listOf(intArrayOf(3, 2, 5, 4)),
            width = 10,
            height = 8,
            pad = 1,
            dilateRadius = 0,
        )

        mask.count { it != 0.toByte() } shouldBe 16
        // Interior of the padded box is solid.
        mask[1 * 10 + 2] shouldBe 1
        mask[4 * 10 + 5] shouldBe 1
        // Just outside the padded box is empty.
        mask[0 * 10 + 2] shouldBe 0
        mask[1 * 10 + 6] shouldBe 0
    }

    @Test
    fun `buildRectMask clamps the padded box to the canvas bounds`() {
        // Box near the top-left corner; pad 3 would push x1/y1 negative.
        val mask = BubbleMaskBuilder.buildRectMask(
            boxes = listOf(intArrayOf(1, 1, 3, 3)),
            width = 5,
            height = 5,
            pad = 3,
            dilateRadius = 0,
        )

        // Padded+clamped box = [0,0,5,5] minus the (3,3) exclusive end gap
        // produced by clamping x2 to width: x2 = min(5, 3+3)=5, so the filled
        // region is [0,0,5,5) = the whole 5x5 canvas = 25 px.
        mask.count { it != 0.toByte() } shouldBe 25
    }

    @Test
    fun `buildRectMask unions overlapping boxes`() {
        // Two boxes that overlap after padding; the union must not double-count.
        val mask = BubbleMaskBuilder.buildRectMask(
            boxes = listOf(
                intArrayOf(2, 2, 4, 4),
                intArrayOf(3, 3, 5, 5),
            ),
            width = 8,
            height = 8,
            pad = 0,
            dilateRadius = 0,
        )

        // Box A = [2,2,4,4] = 2x2 = 4 px. Box B = [3,3,5,5] = 2x2 = 4 px.
        // Overlap = [3,3,4,4] = 1 px. Union = 4 + 4 - 1 = 7 px.
        mask.count { it != 0.toByte() } shouldBe 7
    }

    @Test
    fun `buildRectMask returns an all-zero mask for no boxes`() {
        val mask = BubbleMaskBuilder.buildRectMask(
            boxes = emptyList(),
            width = 4,
            height = 4,
            pad = 2,
            dilateRadius = 2,
        )

        mask.count { it != 0.toByte() } shouldBe 0
    }

    @Test
    fun `buildRectMask skips zero-area and undersized boxes`() {
        // Zero-area box (x2<=x1) and a too-short box (y2<=y1) must be skipped,
        // not crash. A valid box is included as a sanity check.
        val mask = BubbleMaskBuilder.buildRectMask(
            boxes = listOf(
                intArrayOf(2, 2, 2, 5), // zero width
                intArrayOf(2, 2, 5, 2), // zero height
                intArrayOf(1, 1, 3, 3), // valid 2x2
            ),
            width = 6,
            height = 6,
            pad = 0,
            dilateRadius = 0,
        )

        // Only the valid box contributes: [1,1,3,3] = 4 px.
        mask.count { it != 0.toByte() } shouldBe 4
    }

    @Test
    fun `buildRectMask dilates the solid rectangle when dilateRadius is set`() {
        // 9x9 canvas, a 1x1 box at (4,4), pad 0, dilateRadius 2.
        // Without dilation this is 1 px; with disk radius 2 the single pixel
        // grows to a 13-px disk (centre + 4 axis-1 + 4 diagonal + 4 axis-2),
        // matching dilateMaskDisk radius 2 from a single pixel.
        val mask = BubbleMaskBuilder.buildRectMask(
            boxes = listOf(intArrayOf(4, 4, 5, 5)),
            width = 9,
            height = 9,
            pad = 0,
            dilateRadius = 2,
        )

        mask.count { it != 0.toByte() } shouldBe 13
        // Corners of the disk's bounding 5x5 are NOT reached (rounded).
        mask[2 * 9 + 2] shouldBe 0 // (2,2)
        mask[2 * 9 + 6] shouldBe 0 // (6,2)
    }

    // ---- laplaceInpaint (harmonic/Laplace inpaint; the FAST free-text fill now uses inpaintTelea, this guards the retained Laplace path) ----

    private fun grayPixel(v: Int): Int = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    private fun redOf(px: Int): Int = (px shr 16) and 0xFF

    @Test
    fun `laplaceInpaint returns input unchanged when there is no hole`() {
        val w = 16
        val h = 8
        val pixels = IntArray(w * h) { grayPixel(100 + (it % w) * 5) }
        val mask = ByteArray(w * h) // all zero — no hole

        val out = BubbleMaskBuilder.laplaceInpaint(pixels, mask, w, h)

        // No hole → byte-for-byte identical to input (early-return copy).
        out.toList() shouldBe pixels.toList()
    }

    @Test
    fun `laplaceInpaint continues a horizontal gradient across the hole, not a flat fill`() {
        // NS's defining property: isophote continuity. Build a horizontal
        // gray gradient (left=40, right=215), punch a hole in the middle, and
        // assert the reconstructed hole CONTINUES the gradient — the left edge
        // of the hole is dark-ish and the right edge is light-ish, with the
        // interior monotonically between them. A flat/median fill would make
        // the whole hole one value (the white-block symptom).
        val w = 40
        val h = 24
        val pixels = IntArray(w * h) { idx -> grayPixel(40 + (idx % w) * 5) } // 40..235 left→right
        // Hole: columns 14..25, rows 8..15 (a centered block).
        val mask = ByteArray(w * h)
        for (y in 8..15) for (x in 14..25) mask[y * w + x] = 1

        val out = BubbleMaskBuilder.laplaceInpaint(pixels, mask, w, h)

        // Non-hole pixels MUST be unchanged (Dirichlet BC honored + no round-trip drift).
        for (i in pixels.indices) {
            if (mask[i] == 0.toByte()) out[i] shouldBe pixels[i]
        }
        // Gradient continuation: the hole's left edge is darker than its right.
        val midY = 12
        val leftEdgeRed = redOf(out[midY * w + 15])   // first hole column
        val rightEdgeRed = redOf(out[midY * w + 24])  // last hole column
        // Left should be clearly below the page midpoint (grayPixel ~140 at x=20),
        // right clearly above — NOT both clamped to one flat value.
        leftEdgeRed shouldBeLessThan 130
        rightEdgeRed shouldBeGreaterThan 150
        // And the interior must be monotonic non-decreasing across the hole
        // (isophote continuity → no oscillation). Tolerate a 1-px wiggle from
        // the finite-difference stencil by checking the overall trend holds.
        var monotonic = true
        for (x in 15..23) {
            if (redOf(out[midY * w + x + 1]) < redOf(out[midY * w + x]) - 2) {
                monotonic = false
                break
            }
        }
        monotonic shouldBe true
    }

    @Test
    fun `laplaceInpaint leaves a flat-uniform page flat (no regression vs flat fill)`() {
        // A genuinely uniform page: NS should reconstruct the hole as the same
        // uniform value (gradient magnitude is zero everywhere → vorticity is
        // zero → Poisson solves to the constant BC). This guards against the
        // solver introducing noise on flat regions.
        val w = 24
        val h = 16
        val pixels = IntArray(w * h) { grayPixel(180) }
        val mask = ByteArray(w * h)
        for (y in 5..10) for (x in 8..15) mask[y * w + x] = 1

        val out = BubbleMaskBuilder.laplaceInpaint(pixels, mask, w, h)

        // Every reconstructed hole pixel should be ~180 (within a few gray
        // levels of the uniform BC; the PDE steady state is exactly 180).
        for (y in 5..10) {
            for (x in 8..15) {
                val v = redOf(out[y * w + x])
                (v in 170..190) shouldBe true
            }
        }
    }

    @Test
    fun `laplaceInpaint honors the Dirichlet boundary - reconstruction stays within neighbor range`() {
        // The discrete maximum principle for Poisson reconstruction: with a
        // bounded source, the reconstructed value inside the hole cannot exceed
        // the range of its boundary (∂Ω) neighbors. Place a hole in a SMOOTH
        // dark field (value 30 everywhere) — every ∂Ω neighbor is 30, there is
        // no nearby gradient, so the reconstruction must stay ~30. A solver
        // that violated the BC (e.g. averaged over the whole crop, or blew up)
        // would drift far from 30.
        val w = 20
        val h = 16
        val pixels = IntArray(w * h) { grayPixel(30) }
        val mask = ByteArray(w * h)
        for (y in 6..9) for (x in 6..13) mask[y * w + x] = 1

        val out = BubbleMaskBuilder.laplaceInpaint(pixels, mask, w, h)

        // Every reconstructed hole pixel must be within a small tolerance of the
        // uniform BC (30). The NS steady state on a zero-gradient field is
        // exactly the constant; allow a few gray levels for the finite-diff
        // stencil + clamp rounding.
        val midY = 8
        for (x in 6..13) {
            val v = redOf(out[midY * w + x])
            (v in 20..45) shouldBe true
        }
        // Non-hole pixels unchanged (Dirichlet BC + no round-trip drift).
        out[midY * w + 19] shouldBe pixels[midY * w + 19]
    }

    // ---- inpaintTelea (real Telea FMM; the actual FAST free-text fill) ----

    @Test
    fun `inpaintTelea returns input unchanged when there is no hole`() {
        val w = 8
        val h = 8
        val pixels = IntArray(w * h) { grayPixel((it % w) * 10) }
        val mask = ByteArray(w * h)
        val before = pixels.copyOf()
        FastMarchingMethod.inpaintTelea(pixels, mask, w, h)
        pixels.toList() shouldBe before.toList()
    }

    @Test
    fun `inpaintTelea continues a horizontal gradient across a solid-box hole, not a flat fill`() {
        // Telea directional propagation continues the linear gradient across the
        // hole. A flat fill (constant across all hole columns) fails monotonicity.
        val w = 8
        val h = 8
        val pixels = IntArray(w * h) { grayPixel((it % w) * 10) } // 0..70 L→R
        val mask = ByteArray(w * h)
        for (y in 0 until h) for (x in 3..5) mask[y * w + x] = 1
        FastMarchingMethod.inpaintTelea(pixels, mask, w, h)
        val midY = 4
        // Linearly-interpolated gradient at column 4 is ~40; allow ±15 tolerance.
        val midHole = redOf(pixels[midY * w + 4])
        (midHole in 25..55) shouldBe true
        // Monotonically increasing across hole columns (flat fill would be constant).
        (redOf(pixels[midY * w + 3]) < redOf(pixels[midY * w + 4])) shouldBe true
        (redOf(pixels[midY * w + 4]) < redOf(pixels[midY * w + 5])) shouldBe true
    }

    @Test
    fun `inpaintTelea honors the Dirichlet boundary - reconstruction stays within neighbor range`() {
        val w = 8
        val h = 8
        val pixels = IntArray(w * h) { grayPixel((it % w) * 10) } // 0..70 L→R
        val mask = ByteArray(w * h)
        for (y in 2..5) for (x in 2..5) mask[y * w + x] = 1
        val before = pixels.copyOf()
        FastMarchingMethod.inpaintTelea(pixels, mask, w, h)
        var minVal = 255
        var maxVal = 0
        for (i in before.indices) {
            if (mask[i] == 0.toByte()) {
                val v = redOf(before[i])
                if (v < minVal) minVal = v
                if (v > maxVal) maxVal = v
            }
        }
        for (i in pixels.indices) {
            if (mask[i] != 0.toByte()) {
                val v = redOf(pixels[i])
                (v in minVal..maxVal) shouldBe true
            }
        }
    }
}
