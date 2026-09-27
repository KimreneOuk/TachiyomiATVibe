package eu.kanade.translation.engines.inpainting.bubble

import eu.kanade.translation.engines.inpainting.opencv.OpenCvInpaintEngine
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Guards the pure mask-construction and morphology helpers extracted from
 * the inpainting engines. These byte-array algorithms were previously private
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
        // 4-neighbourhood).
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
        out[6] shouldBe 1 // (1,1)
        out[8] shouldBe 1 // (3,1)
        out[16] shouldBe 1 // (1,3)
        out[18] shouldBe 1 // (3,3)
    }

    @Test
    fun `dilateMaskDisk rounds rectangle corners`() {
        // A 3x3 filled rectangle dilated by radius 2: the disk grows the
        // rectangle but ROUNDS its corners (the corner cells beyond the disk
        // ROUNDS its corners (the corner cells beyond the disk are not set),
        // are not set), keeping the corner rounded.
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
    fun `featherAlpha makes core pixels fully opaque and near neighbours partially opaque`() {
        // 5x5, single core pixel at centre. featherAlpha now delegates to the
        // distance-field alpha: alpha = 1 inside the mask, ramping to 0 over
        // featherRadius px from the edge.
        val mask = ByteArray(25).also { it[12] = 1 }
        val alpha = BubbleMaskBuilder.featherAlpha(mask, width = 5, height = 5, featherRadius = 2)

        // Core is fully opaque.
        alpha[12] shouldBe 1.0f
        // The orthogonal neighbour (7 = (2,1)) is 1 px from the mask → inside
        // the ramp → partially opaque, not 0 and not 1.
        val neighbour = alpha[7]
        (neighbour > 0f) shouldBe true
        (neighbour < 1f) shouldBe true
        // A far corner (0,0) is 4 px away → beyond the 2px ramp → fully clear.
        alpha[0] shouldBe 0f
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
    // Port of the prototype's build_rect_mask function: every
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

    // Shared gray/red pixel helpers for the inpaint tests below.

    private fun grayPixel(v: Int): Int = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    private fun redOf(px: Int): Int = (px shr 16) and 0xFF

    // ---- inpaintTelea (real Telea FMM; the actual FAST free-text fill) ----

    @Test
    fun `inpaintTelea returns input unchanged when there is no hole`() {
        val w = 8
        val h = 8
        val pixels = IntArray(w * h) { grayPixel((it % w) * 10) }
        val mask = ByteArray(w * h)
        val before = pixels.copyOf()
        OpenCvInpaintEngine.inpaintTelea(pixels, mask, w, h)
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
        OpenCvInpaintEngine.inpaintTelea(pixels, mask, w, h)
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
        OpenCvInpaintEngine.inpaintTelea(pixels, mask, w, h)
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

    // ---- computeNeuralCrop (balanced ~1/3-box crop sizing) ----

    @Test
    fun `computeNeuralCrop never exceeds the 512 tensor cap`() {
        // A huge SFX box still yields a crop at the max clamp.
        BubbleMaskBuilder.computeNeuralCrop(2000) shouldBe 512
        BubbleMaskBuilder.computeNeuralCrop(512) shouldBe 512
    }

    @Test
    fun `computeNeuralCrop floors tiny boxes at the context minimum`() {
        // Zero/degenerate or sub-floor boxes get the floor, guaranteeing a
        // resolution floor (the box is never sub-128 in the ≤512 tensor view).
        BubbleMaskBuilder.computeNeuralCrop(0) shouldBe 384
        BubbleMaskBuilder.computeNeuralCrop(40) shouldBe 384
        BubbleMaskBuilder.computeNeuralCrop(128) shouldBe 384
    }

    @Test
    fun `computeNeuralCrop makes the box roughly one third of the crop`() {
        // For a mid-range box the crop is ~3x the box, so the box occupies
        // ~1/3 of the tensor and ~2/3 is real page context.
        BubbleMaskBuilder.computeNeuralCrop(160) shouldBe 480
        BubbleMaskBuilder.computeNeuralCrop(150) shouldBe 450
        // 170*3 = 510 (still under the 512 clamp, so no clamping).
        BubbleMaskBuilder.computeNeuralCrop(170) shouldBe 510
    }

    // ---- distanceToMask (chamfer distance transform) ----

    @Test
    fun `distanceToMask is zero inside the mask`() {
        val w = 10
        val h = 10
        val mask = ByteArray(w * h)
        for (y in 3..6) for (x in 3..6) mask[y * w + x] = 1

        val dist = BubbleMaskBuilder.distanceToMask(mask, w, h)

        for (y in 3..6) {
            for (x in 3..6) {
                dist[y * w + x] shouldBe 0f
            }
        }
    }

    @Test
    fun `distanceToMask grows monotonically away from the mask edge`() {
        val w = 11
        val h = 1
        // Mask in the middle column (x=5).
        val mask = ByteArray(w * h)
        mask[5] = 1

        val dist = BubbleMaskBuilder.distanceToMask(mask, w, h)

        // Distance at the adjacent pixel (x=4/6) must be > 0, and it must keep
        // increasing as we move further out — a true distance field, not a cliff.
        dist[4] shouldBeGreaterThan 0f
        dist[6] shouldBeGreaterThan 0f
        dist[3] shouldBeGreaterThan dist[4]
        dist[2] shouldBeGreaterThan dist[3]
        dist[1] shouldBeGreaterThan dist[2]
        dist[0] shouldBeGreaterThan dist[1]
    }

    @Test
    fun `distanceToMask is infinity when the mask is entirely empty`() {
        val w = 5
        val h = 5
        val mask = ByteArray(w * h) // all zero

        val dist = BubbleMaskBuilder.distanceToMask(mask, w, h)

        dist.all { it.isInfinite() } shouldBe true
    }

    @Test
    fun `distanceToMask horizontal step is approximately one pixel`() {
        val w = 5
        val h = 1
        val mask = ByteArray(w * h)
        mask[0] = 1

        val dist = BubbleMaskBuilder.distanceToMask(mask, w, h)

        // The pixel adjacent to the mask (x=1) is one orthogonal step away.
        // Chamfer (3,4) weights: an H step = 3 units, normalized by /3 → 1px.
        dist[1] shouldBe 1f
    }

    // ---- featherAlphaField (distance-field soft edge) ----

    @Test
    fun `featherAlphaField is 1 inside the mask and ramps down outside`() {
        val w = 10
        val h = 1
        val mask = ByteArray(w * h)
        mask[5] = 1

        val alpha = BubbleMaskBuilder.featherAlphaField(mask, w, h, rampWidth = 3)

        alpha[5] shouldBe 1f // inside mask → fully opaque
        // Immediately outside, alpha must be < 1 but > 0 (the ramp), and
        // strictly decreasing as we leave the edge — no hard cliff.
        alpha[4] shouldBeLessThan 1f
        alpha[4] shouldBeGreaterThan 0f
        alpha[3] shouldBeLessThan alpha[4]
        alpha[2] shouldBeLessThan alpha[3]
    }

    @Test
    fun `featherAlphaField returns all-zero for an empty mask`() {
        val mask = ByteArray(25)
        val alpha = BubbleMaskBuilder.featherAlphaField(mask, 5, 5, rampWidth = 4)
        alpha.all { it == 0f } shouldBe true
    }
}
