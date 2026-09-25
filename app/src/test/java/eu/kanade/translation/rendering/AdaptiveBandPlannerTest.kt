package eu.kanade.translation.rendering

import eu.kanade.translation.segmentation.MaskGeometry
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.math.ceil

/**
 *  slice 5: the pure [AdaptiveBandPlanner] — width gain over the
 * conservative inscribed rectangle on thin/slanted components, hole rows
 * narrowing the line band, exact stroke-inset math, exact bounded candidate
 * counters, the 24-line block cap, non-consumable text, and the exact
 * layoutWidth/left/top formulas.
 */
class AdaptiveBandPlannerTest {

    /** Deterministic measurement: every char is `charWidth` wide at the given size. */
    private class FakeMeasurer(private val charWidth: Float = 0.6f) : TextMeasurer {
        override fun measureTextWidth(text: String, fontSizePx: Float): Float =
            text.length * charWidth * fontSizePx

        override fun lineHeight(fontSizePx: Float): Float = fontSizePx * 1.2f
    }

    private val measurer = FakeMeasurer()

    private fun rows(vararg spans: Pair<Int, List<Pair<Int, Int>>>): List<MaskGeometry.RowSpan> =
        spans.flatMap { (y, intervals) -> intervals.map { (s, e) -> MaskGeometry.RowSpan(y, s, e) } }

    private fun fullRows(top: Int, bottom: Int, left: Int, right: Int): List<Pair<Int, List<Pair<Int, Int>>>> =
        (top until bottom).map { y -> y to listOf(left to right) }

    private fun fit(
        text: String,
        spans: List<MaskGeometry.RowSpan>,
        slab: FloatRect,
        centerX: Float,
        centerY: Float,
        minFont: Float = 8f,
        maxFont: Float = 72f,
    ) = AdaptiveBandPlanner.fitAdaptiveBands(
        text = text,
        cellSpans = spans,
        slab = slab,
        scale = 1f,
        collisionGapPx = 2,
        measurer = measurer,
        minFontPx = minFont,
        maxFontPx = maxFont,
        blockCenterX = centerX,
        blockCenterY = centerY,
    )

    @Test
    fun `thin slanted component gains width over the conservative inscribed rectangle`() {
        // Rows 0-1 and 15-16 are narrow [30,70); rows 2-14 span the full
        // [0,100). The inscribed rectangle is [30,70)x17 while a band aligned
        // on the wide rows gets the full 100px.
        val spans = rows(
            0 to listOf(30 to 70),
            1 to listOf(30 to 70),
            *fullRows(2, 15, 0, 100).toTypedArray(),
            15 to listOf(30 to 70),
            16 to listOf(30 to 70),
        )
        val text = "abcdefghijkl" // lowercase: atomic and trial-ineligible

        val result = fit(text, spans, FloatRect(0f, 0f, 100f, 17f), centerX = 50f, centerY = 8.5f)
            .shouldNotBeNull()

        // The conservative inscribed-rectangle fit floors at 8; the adaptive
        // band uses the wide rows for a strictly larger font.
        val rectFit = TextLayoutPlanner.binarySearchFontSize(text, 32f, 9f, 32f, false, 1f, measurer)
        (result.fontPx > rectFit) shouldBe true
        result.fontPx shouldBe 10f

        result.usedTrialText shouldBe text
        result.stats shouldBe AdaptiveBandPlanner.Stats(fontSteps = 6, alignmentsTried = 1, fixedPointPasses = 1)
        result.anchorX shouldBe 50f
        result.anchorY shouldBe 8.5f

        result.lines shouldHaveSize 1
        val line = result.lines.single()
        line.text shouldBe text
        // advance 72 + 2*guard(2) = 76 centered in the [0,100) band, floor split.
        line.layoutWidthPx shouldBe 76
        line.leftPx shouldBe 12
        line.topPx shouldBe 2 // floor(firstTop = 8.5 - 12/2)
        line.layoutHeightPx shouldBe 12 // ceil(lineH 12)
        // Conservative occupancy: advance/line-height rect inflated by
        // stroke(2) + AA(1) + gap/2(1) = 4 per side, un-clipped.
        line.conservativeOccupancy shouldBe FloatRect(8f, -2f, 88f, 18f)
    }

    @Test
    fun `concave hole rows narrow the line band to the surviving strip`() {
        // Rows 0-4 and 10-14 span [0,60); rows 5-9 have a hole (only [0,20)
        // and [40,60) are owned). A line covering the hole rows may only use
        // one strip.
        val spans = rows(
            *fullRows(0, 5, 0, 60).toTypedArray(),
            *fullRows(5, 10, 0, 20).toTypedArray(),
            *fullRows(5, 10, 40, 60).toTypedArray(),
            *fullRows(10, 15, 0, 60).toTypedArray(),
        )
        val text = "abc"

        val result = fit(text, spans, FloatRect(0f, 0f, 60f, 15f), centerX = 30f, centerY = 7.5f)
            .shouldNotBeNull()

        result.fontPx shouldBe 8f // any larger font's line width misses the strip
        result.stats shouldBe AdaptiveBandPlanner.Stats(fontSteps = 6, alignmentsTried = 1, fixedPointPasses = 2)
        result.usedTrialText shouldBe text

        result.lines shouldHaveSize 1
        val line = result.lines.single()
        // advance 14.4 + 2*guard(2) = ceil(18.4) = 19 fits the [0,20) strip.
        line.layoutWidthPx shouldBe 19
        line.leftPx shouldBe 0 // floor(0 + (20-19)/2)
        line.topPx shouldBe 2 // floor(2.7)
        // The accepted rect stays inside the left strip: hole columns unowned.
        (line.leftPx + line.layoutWidthPx <= 20) shouldBe true
        // Conservative occupancy mirrors production arithmetic exactly
        // (advance/line-height rect inflated by stroke 2 + AA 1 + gap/2 1).
        val advance = measurer.measureTextWidth(text, result.fontPx)
        val lineH = measurer.lineHeight(result.fontPx)
        line.conservativeOccupancy shouldBe FloatRect(0f - 4f, 2f - 4f, 0f + advance + 4f, 2f + lineH + 4f)
    }

    @Test
    fun `stroke inset math is exact per the tuning envelope`() {
        // Task-mandated pin: font 30, scale 1 → stroke max(2, 3.6) = 3.6 →
        // ceil(1.8 + 1 + 2) = 5.
        TextLayoutTuning.strokeInsetPx(30f, 1f) shouldBe 5f
        TextLayoutTuning.strokeInsetPx(8f, 1f) shouldBe 4f // stroke 2 → ceil(1+1+2)
        TextLayoutTuning.shapingGuardPx(30f, 1f) shouldBe 3f // ceil(1.8+1)
        TextLayoutTuning.aaGuard(1f) shouldBe 1f
        TextLayoutTuning.aaGuard(0.25f) shouldBe 0.5f
        TextLayoutTuning.VISUAL_PADDING_PX shouldBe 2f
    }

    @Test
    fun `usable wrap width is slab width minus twice the stroke inset`() {
        // "ab" x7 = 20 chars → advance exactly 96 at font 8. With inset 4, a
        // 104px slab wraps at exactly 96 (one line); a 103px slab wraps at 95
        // (two lines). Any other inset flips these results. The font is
        // pinned to the 8px floor and the spans match each slab (spans are
        // always slab-intersected in production).
        val text = "ab ".repeat(7).trimEnd()
        val wide = fit(
            text,
            rows(*fullRows(0, 40, 0, 104).toTypedArray()),
            FloatRect(0f, 0f, 104f, 40f),
            52f,
            20f,
            maxFont = 8f,
        ).shouldNotBeNull()
        wide.fontPx shouldBe 8f
        wide.lines shouldHaveSize 1

        val narrow = fit(
            text,
            rows(*fullRows(0, 40, 0, 103).toTypedArray()),
            FloatRect(0f, 0f, 103f, 40f),
            52f,
            20f,
            maxFont = 8f,
        ).shouldNotBeNull()
        narrow.fontPx shouldBe 8f
        narrow.lines shouldHaveSize 2
    }

    @Test
    fun `more than the per block line cap fails every font and returns null`() {
        // At font 8 the 60px slab wraps this text into far more than 24 lines;
        // larger fonts only wrap further.
        val spans = rows(*fullRows(0, 200, 0, 60).toTypedArray())
        val text = "ab ".repeat(200).trimEnd()

        fit(text, spans, FloatRect(0f, 0f, 60f, 200f), 30f, 100f).shouldBeNull()
    }

    @Test
    fun `atomic text wider than every band is not consumable and returns null`() {
        // 8-char atomic token at font 8: advance 38.4 + guards never fits a
        // 30px band.
        val spans = rows(*fullRows(0, 50, 0, 30).toTypedArray())

        fit("abcdefgh", spans, FloatRect(0f, 0f, 30f, 50f), 15f, 25f).shouldBeNull()
    }

    @Test
    fun `layout width follows ceil advance plus two shaping guards with floor placement`() {
        val spans = rows(*fullRows(0, 80, 0, 100).toTypedArray())

        val result = fit("abc", spans, FloatRect(0f, 0f, 100f, 80f), 50f, 40f).shouldNotBeNull()
        result.lines shouldHaveSize 1
        val line = result.lines.single()
        val advance = measurer.measureTextWidth(line.text, result.fontPx)
        line.layoutWidthPx shouldBe
            maxOf(1, ceil(advance + 2f * TextLayoutTuning.shapingGuardPx(result.fontPx, 1f)).toInt())
        // Integer floor placement inside the full-width band.
        line.leftPx shouldBe ((100 - line.layoutWidthPx) / 2) // exact even split
        (line.topPx >= 0) shouldBe true
        (line.topPx + line.layoutHeightPx <= 80) shouldBe true
    }

    @Test
    fun `cell content center alignment rescues a block whose ocr center is off the component`() {
        // Block center is 92px below the 16-row component: alignment (a)
        // violates the vertical slab gate, alignment (b) anchors on the cell
        // content center and succeeds → alignmentsTried == 2.
        val spans = rows(*fullRows(0, 16, 0, 100).toTypedArray())

        val result = fit("abc", spans, FloatRect(0f, 0f, 100f, 16f), 50f, 100f).shouldNotBeNull()

        result.fontPx shouldBe 13f
        result.anchorY shouldBe 8f // (0 + 16) / 2
        result.stats shouldBe AdaptiveBandPlanner.Stats(fontSteps = 6, alignmentsTried = 2, fixedPointPasses = 1)
    }

    @Test
    fun `font binary search is bounded by the step budget`() {
        val spans = rows(*fullRows(0, 2000, 0, 2000).toTypedArray())
        val huge = fit("abc", spans, FloatRect(0f, 0f, 2000f, 2000f), 1000f, 1000f).shouldNotBeNull()
        huge.fontPx shouldBe 72f
        huge.stats.fontSteps shouldBe 7 // full 8..72 range consumed the budget

        val pinned = fit(
            "abc",
            rows(*fullRows(0, 60, 0, 100).toTypedArray()),
            FloatRect(0f, 0f, 100f, 60f),
            50f,
            30f,
            minFont = 8f,
            maxFont = 8f,
        ).shouldNotBeNull()
        pinned.fontPx shouldBe 8f
        pinned.stats.fontSteps shouldBe 1
    }
}
