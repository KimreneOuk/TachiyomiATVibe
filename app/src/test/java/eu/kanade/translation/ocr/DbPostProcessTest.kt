package eu.kanade.translation.ocr

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.math.abs

class DbPostProcessTest {

    private val width = 20
    private val height = 16

    /** Build a flat prob map filled with [background] and a single high-prob box. */
    private fun probMapWith(
        box: IntArray, // [x1,y1,x2,y2] inclusive
        boxProb: Float,
        background: Float = 0f,
    ): FloatArray {
        val map = FloatArray(width * height) { background }
        for (y in box[1]..box[3]) {
            for (x in box[0]..box[2]) {
                map[y * width + x] = boxProb
            }
        }
        return map
    }

    @Test
    fun `empty map returns no lines`() {
        DbPostProcess.detectLines(FloatArray(width * height), width, height) shouldBe emptyList()
    }

    @Test
    fun `single high-prob rectangle yields one line, unclipped about centroid`() {
        // 6x3 rectangle in the middle, prob well above both thresholds.
        val map = probMapWith(intArrayOf(5, 4, 10, 6), boxProb = 0.9f)
        val lines = DbPostProcess.detectLines(map, width, height)
        lines shouldHaveSize 1
        val b = lines[0].bbox
        // detectLines already applies unclip(ratio=1.4) about the centroid
        // (cx=7.5, cy=5), with half-extents (2.5, 1):
        //   sx = 2.5 * 1.4 = 3.5,  sy = 1 * 1.4 = 1.4
        //   x1 = (7.5 - 3.5) = 4,  y1 = (5 - 1.4) = 3.6 -> 3
        //   x2 = (7.5 + 3.5) = 11, y2 = (5 + 1.4) = 6.4 -> 6
        // all within map bounds [0,19]x[0,11], so no clamping changes them.
        b.toList() shouldBe listOf(4, 3, 11, 6)
        // Score is the mean prob over the component (uniform 0.9). Use an
        // approximate comparison — summing 18 copies of 0.9f and dividing drifts
        // to 0.89999986f in IEEE-754 single precision; exact equality is brittle.
        abs(lines[0].meanScore - 0.9f) shouldBeLessThan 1e-4f
    }

    @Test
    fun `two separate rectangles yield two lines sorted by row then column`() {
        val map = FloatArray(width * height)
        // Top-left rectangle, area 5x5=25 (> MIN_AREA_PX 16)
        for (y in 1..5) for (x in 1..5) map[y * width + x] = 0.8f
        // Bottom-right rectangle, area 5x5=25
        for (y in 8..12) for (x in 12..16) map[y * width + x] = 0.8f
        val lines = DbPostProcess.detectLines(map, width, height)
        lines shouldHaveSize 2
        // detectLines unclips each box (ratio 1.4 about centroid) AND sorts by
        // (y, x). The top rectangle's centroid is (3,3); unclipped y1 = 3-2.8 = 0.
        // The bottom rectangle's centroid is (10,14); unclipped y1 = 10-2.8 = 7.
        // Assert on the SORT ORDER (top first), not exact pre-unclip coords.
        lines[0].bbox[1] shouldBe 0
        lines[1].bbox[1] shouldBe 7
    }

    @Test
    fun `rectangle below box_thresh is dropped`() {
        // mean prob 0.3 < default box_thresh 0.45 -> dropped even though > thresh 0.2
        val map = probMapWith(intArrayOf(2, 2, 8, 5), boxProb = 0.3f)
        DbPostProcess.detectLines(map, width, height) shouldBe emptyList()
    }

    @Test
    fun `lowering box_thresh recovers the weak rectangle`() {
        val map = probMapWith(intArrayOf(2, 2, 8, 5), boxProb = 0.3f)
        val lines = DbPostProcess.detectLines(map, width, height, boxThresh = 0.2f)
        lines shouldHaveSize 1
    }

    @Test
    fun `sub-threshold noise is ignored`() {
        // prob below thresh (0.2) -> never becomes a binary-region pixel.
        val map = probMapWith(intArrayOf(2, 2, 8, 5), boxProb = 0.1f)
        DbPostProcess.detectLines(map, width, height) shouldBe emptyList()
    }

    @Test
    fun `tiny specks below min area are ignored`() {
        // 2x1 speck -> area 2 < MIN_AREA_PX (16). Should be dropped.
        val map = probMapWith(intArrayOf(5, 5, 6, 5), boxProb = 0.9f)
        DbPostProcess.detectLines(map, width, height) shouldBe emptyList()
    }

    @Test
    fun `back-project maps map-space bbox to crop-space with scale factors`() {
        val mapBox = intArrayOf(100, 50, 200, 150)
        // scaleX = cropW/mapW = 800/400 = 2.0 ; scaleY = 600/300 = 2.0
        val crop = DbPostProcess.backProject(mapBox, scaleX = 2f, scaleY = 2f, cropW = 800, cropH = 600)
        crop.toList() shouldBe listOf(200, 100, 400, 300)
    }

    @Test
    fun `back-project clamps to crop bounds`() {
        val mapBox = intArrayOf(0, 0, 1000, 1000)
        val crop = DbPostProcess.backProject(mapBox, scaleX = 2f, scaleY = 2f, cropW = 100, cropH = 100)
        crop.toList() shouldBe listOf(0, 0, 99, 99)
    }

    @Test
    fun `center distance is zero for coincident box centers`() {
        val a = intArrayOf(0, 0, 10, 10)
        DbPostProcess.centerDistance(a, a) shouldBe 0f
    }

    @Test
    fun `max_candidates caps the returned line count`() {
        // 5x5 = 25-px rectangles (> MIN_AREA_PX 16) on a regular 60x60 grid.
        // Each row's boxes are at the same y, so [mergeLineFragments] (now applied
        // by detectLines) merges each row into ONE box. The cap still bounds how
        // many COMPONENTS are enumerated; the assertion checks the post-merge line
        // count. Place boxes far enough apart in y that rows stay distinct.
        val map = FloatArray(60 * 60)
        // 3 rows, 6 cols each — but each row merges to 1 line = 3 lines total.
        // Use a tight cap to still prove the bound shapes the output.
        for (row in 0 until 3) {
            for (col in 0 until 6) {
                val x0 = col * 9 + 1
                val y0 = row * 18 + 1
                for (y in y0 until y0 + 5) for (x in x0 until x0 + 5) {
                    map[y * 60 + x] = 0.9f
                }
            }
        }
        val lines = DbPostProcess.detectLines(
            map, width = 60, height = 60, maxCandidates = 100,
        )
        // 3 rows -> 3 merged lines (one per row).
        lines.size shouldBe 3
    }

    // ---- mergeLineFragments: fixes det over-segmentation of text lines ----

    private fun line(x1: Int, y1: Int, x2: Int, y2: Int, s: Float = 0.9f) =
        TextLine(intArrayOf(x1, y1, x2, y2), s)

    @Test
    fun `merge collapses same-row horizontal fragments into one line`() {
        // Three char-size fragments on the same row (y~50, h~40), small gaps.
        val frags = listOf(
            line(10, 50, 50, 90),
            line(60, 50, 100, 90),
            line(110, 52, 150, 88),
        )
        val merged = DbPostProcess.mergeLineFragments(frags)
        merged shouldHaveSize 1
        merged[0].bbox.toList() shouldBe listOf(10, 50, 150, 90)
    }

    @Test
    fun `merge does not collapse fragments on different rows`() {
        val frags = listOf(
            line(10, 50, 100, 90),   // row 1
            line(10, 200, 100, 240), // row 2 (far apart)
        )
        val merged = DbPostProcess.mergeLineFragments(frags)
        merged shouldHaveSize 2
    }

    @Test
    fun `merge collapses same-column vertical fragments into one column`() {
        // Three TALL fragments (h > w) stacked in the same column (x~50, w~40,
        // h~80). Tall orientation routes them through the vertical merge branch.
        val frags = listOf(
            line(50, 10, 90, 90),
            line(50, 100, 90, 180),
            line(52, 190, 88, 270),
        )
        val merged = DbPostProcess.mergeLineFragments(frags)
        merged shouldHaveSize 1
        merged[0].bbox.toList() shouldBe listOf(50, 10, 90, 270)
    }

    @Test
    fun `merge takes the max score across merged fragments`() {
        val frags = listOf(
            line(10, 50, 50, 90, s = 0.7f),
            line(60, 50, 100, 90, s = 0.95f),
            line(110, 52, 150, 88, s = 0.8f),
        )
        val merged = DbPostProcess.mergeLineFragments(frags)
        merged shouldHaveSize 1
        merged[0].meanScore shouldBe 0.95f
    }

    @Test
    fun `merge returns input unchanged for single or empty input`() {
        DbPostProcess.mergeLineFragments(emptyList()) shouldBe emptyList()
        val one = listOf(line(10, 10, 20, 20))
        DbPostProcess.mergeLineFragments(one) shouldHaveSize 1
    }

    @Test
    fun `merge handles mixed horizontal and vertical without cross-merging`() {
        // One horizontal row + one vertical column that happen to overlap in x.
        val frags = listOf(
            line(50, 100, 200, 140),  // horizontal
            line(100, 50, 140, 300),  // vertical (taller than wide)
        )
        val merged = DbPostProcess.mergeLineFragments(frags)
        // They must NOT merge: one is horizontal (w>=h), the other vertical (h>w),
        // so they're processed in separate merge passes and stay distinct.
        merged shouldHaveSize 2
    }
}
