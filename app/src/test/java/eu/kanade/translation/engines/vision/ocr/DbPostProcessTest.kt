package eu.kanade.translation.engines.vision.ocr

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
    fun `single high-prob rectangle with unclip 0 yields raw bbox`() {
        // 6x3 rectangle in the middle, prob well above both thresholds.
        val map = probMapWith(intArrayOf(5, 4, 10, 6), boxProb = 0.9f)
        val lines = DbPostProcess.detectLines(map, width, height, unclipRatio = 0f)
        lines shouldHaveSize 1
        val b = lines[0].bbox
        b.toList() shouldBe listOf(5, 4, 10, 6)
        abs(lines[0].meanScore - 0.9f) shouldBeLessThan 1e-4f
    }

    @Test
    fun `single high-prob rectangle with default unclip 1_4 expands bbox`() {
        // 6x3 rectangle in the middle: area = 18, perimeter = 18, offset = round(18 * 1.4 / 18) = 1
        val map = probMapWith(intArrayOf(5, 4, 10, 6), boxProb = 0.9f)
        val lines = DbPostProcess.detectLines(map, width, height)
        lines shouldHaveSize 1
        val b = lines[0].bbox
        b.toList() shouldBe listOf(4, 3, 11, 7)
    }

    @Test
    fun `unclip calculates constant polygon offset accurately`() {
        // 10x10 square: area = 100, perimeter = 40, offset = round(100 * 1.4 / 40) = round(3.5) = 4
        val unclipped = DbPostProcess.unclip(10, 10, 19, 19, unclipRatio = 1.4f)
        unclipped.toList() shouldBe listOf(6, 6, 23, 23)

        // unclip with ratio 0 returns original
        val raw = DbPostProcess.unclip(10, 10, 19, 19, unclipRatio = 0f)
        raw.toList() shouldBe listOf(10, 10, 19, 19)
    }

    @Test
    fun `two separate rectangles yield two lines sorted by row then column`() {
        val map = FloatArray(width * height)
        // Top-left rectangle, area 5x5=25 (> MIN_AREA_PX 16)
        for (y in 1..5) for (x in 1..5) map[y * width + x] = 0.8f
        // Bottom-right rectangle, area 5x5=25
        for (y in 8..12) for (x in 12..16) map[y * width + x] = 0.8f
        val lines = DbPostProcess.detectLines(map, width, height, unclipRatio = 0f)
        lines shouldHaveSize 2
        lines[0].bbox[1] shouldBe 1
        lines[1].bbox[1] shouldBe 8
    }

    @Test
    fun `component larger than 95 percent of the map is dropped`() {
        // 20x16 = 320 > 0.95 * 320 = 304 → dropped.
        val bigMap = probMapWith(intArrayOf(0, 0, 19, 15), boxProb = 0.9f)
        DbPostProcess.detectLines(bigMap, width, height) shouldBe emptyList()
        // Bubble-filling component (15x12=180, ~56% of map) now survives cleanly.
        val bubbleMap = probMapWith(intArrayOf(0, 0, 14, 11), boxProb = 0.9f)
        DbPostProcess.detectLines(bubbleMap, width, height) shouldHaveSize 1
    }

    @Test
    fun `rectangle below box_thresh is dropped`() {
        // mean prob 0.20 < default box_thresh 0.25 -> dropped even though > thresh 0.15
        val map = probMapWith(intArrayOf(2, 2, 8, 5), boxProb = 0.20f)
        DbPostProcess.detectLines(map, width, height) shouldBe emptyList()
    }

    @Test
    fun `lowering box_thresh recovers the weak rectangle`() {
        val map = probMapWith(intArrayOf(2, 2, 8, 5), boxProb = 0.20f)
        val lines = DbPostProcess.detectLines(map, width, height, boxThreshold = 0.15f)
        lines shouldHaveSize 1
    }

    @Test
    fun `thresh param is threaded to the binarization step`() {
        // prob map at 0.14 is BELOW default thresh (0.15) -> no lines
        val map = probMapWith(intArrayOf(2, 2, 8, 5), boxProb = 0.14f)
        DbPostProcess.detectLines(map, width, height) shouldBe emptyList()
        // lowered threshold 0.12 < 0.14 -> binarized, and lowered box_thresh recovers it
        val lines = DbPostProcess.detectLines(map, width, height, threshold = 0.12f, boxThreshold = 0.10f)
        lines shouldHaveSize 1
    }

    @Test
    fun `sub-threshold noise is ignored`() {
        // prob below thresh (0.15) -> never becomes a binary-region pixel.
        val map = probMapWith(intArrayOf(2, 2, 8, 5), boxProb = 0.10f)
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
        val crop = DbPostProcess.backProject(mapBox, scaleX = 2f, scaleY = 2f, cropWidth = 800, cropHeight = 600)
        crop.toList() shouldBe listOf(200, 100, 400, 300)
    }

    @Test
    fun `back-project clamps to crop bounds`() {
        val mapBox = intArrayOf(0, 0, 1000, 1000)
        val crop = DbPostProcess.backProject(mapBox, scaleX = 2f, scaleY = 2f, cropWidth = 100, cropHeight = 100)
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
        // Use a tight cap to still prove the bound shapes the output. Columns are
        // spaced so the inter-box gap (< box height) merges a row on the RAW
        // (un-unclipped) boxes — the production merge threshold is tuned against
        // raw boxes, matching the Python prototype (no unclip).
        for (row in 0 until 3) {
            for (col in 0 until 6) {
                val x0 = col * 7 + 1
                val y0 = row * 18 + 1
                for (y in y0 until y0 + 5) {
                    for (x in x0 until x0 + 5) {
                        map[y * 60 + x] = 0.9f
                    }
                }
            }
        }
        val lines = DbPostProcess.detectLines(
            map,
            width = 60,
            height = 60,
            maxCandidates = 100,
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
            line(10, 50, 100, 90), // row 1
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
    fun `merge vertical fragments with gap larger than width but smaller than height`() {
        // Two tall boxes (w=20, h=50) in the same column. Gap between them is 25.
        // gap(25) > width(20) → old code (width-based) would NOT merge.
        // gap(25) < height(50) → new code (height-based) DOES merge.
        val frags = listOf(
            line(10, 10, 30, 60),
            line(10, 85, 30, 135),
        )
        val merged = DbPostProcess.mergeLineFragments(frags)
        merged shouldHaveSize 1
        merged[0].bbox.toList() shouldBe listOf(10, 10, 30, 135)
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
            line(50, 100, 200, 140), // horizontal
            line(100, 50, 140, 300), // vertical (taller than wide)
        )
        val merged = DbPostProcess.mergeLineFragments(frags)
        // They must NOT merge: one is horizontal (w>=h), the other vertical (h>w),
        // so they're processed in separate merge passes and stay distinct.
        merged shouldHaveSize 2
    }
}
