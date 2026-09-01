package eu.kanade.translation.rendering

import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.segmentation.BubbleMaskRle
import eu.kanade.translation.segmentation.MaskGeometry
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * T912 quality repair (Director mandate, post-device-testing): the three
 * render-quality fixes on top of the missing-text repair —
 *
 *  - Fix 2, band acceptance guard: adaptive bands are accepted only when they
 *    MEANINGFULLY beat the conservative rectangle layout of the same cell
 *    ([TextLayoutTuning.BAND_ACCEPT_FACTOR] × the rectangle fit of the cell's
 *    content bounds, or the rectangle cannot host the text while the bands
 *    can). A rejected fit falls through to the legacy rectangle form.
 *  - Fix 3, font harmony: same-component sibling fonts are capped DOWN to
 *    [TextLayoutTuning.FONT_HARMONY_MEDIAN_CAP] × the group median (never
 *    inflated, deterministic).
 *
 * Fix 1 (the overlay consuming the positioned layout model) is an Android
 * View and has no JVM test surface; its compile gate plus the
 * PageTextRenderer-mirrored semantics are the automated proof.
 *
 * T912 containment-first ordering: the contained reflow rescue now runs for
 * EVERY masked horizontal block BEFORE the Fix 2 band-acceptance guard, and
 * sibling font harmony (Fix 3) caps whatever was adopted. The tests below
 * reach the Fix 2 machinery via fixtures whose rescue declines (a degenerate
 * OCR box below the rescue's 4px home minimum); the harmony fixture lets the
 * rescue adopt so the cap demonstrably binds on an adopted layout.
 */
class TextLayoutPlannerQualityRepairTest {

    /** Deterministic measurement: every char is `charWidth` wide at the given size. */
    private class FakeMeasurer : TextMeasurer {
        override fun measureTextWidth(text: String, fontSizePx: Float): Float =
            text.length * 0.6f * fontSizePx

        override fun lineHeight(fontSizePx: Float): Float = fontSizePx * 1.2f
    }

    private val m = FakeMeasurer()

    private fun block(
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        text: String,
        score: Float = 1f,
    ) = TranslationBlock(
        text = "",
        translation = text,
        width = w,
        height = h,
        x = x,
        y = y,
        symHeight = 1f,
        symWidth = 1f,
        angle = 0f,
        label = 1,
        score = score,
        direction = "LTR",
    )

    private fun fullMask(width: Int = 300, height: Int = 120) =
        BubbleMaskRle(width, height, listOf(0, 0, width, height), listOf(0, width * height), score = 1f)

    /** A thin lobe slanting down-right: row y owns x `[y/4, y/4 + 120)` on a 300x400 page. */
    private fun slantLobeMask(): BubbleMaskRle {
        val runs = ArrayList<Int>(800)
        var maxRight = 0
        for (y in 0 until 400) {
            runs += y * 300 + y / 4
            runs += 120
            maxRight = maxOf(maxRight, y / 4 + 120)
        }
        return BubbleMaskRle(300, 400, listOf(0, 0, maxRight, 400), runs, score = 1f)
    }

    private fun draw(result: LayoutResult): BlockLayout =
        (result.outcome as LayoutOutcome.Draw).layout

    // ---- Fix 2: the beats-the-rectangle rule -------------------------------

    @Test
    fun `bands that merely match the rectangle fit are rejected for the legacy rectangle layout`() {
        // Re-pinned fixture for T912 containment-first: the rescue must decline
        // for the band-acceptance guard to be reached. The degenerate 3x3 OCR
        // box (same cell center) is below the rescue's 4px home minimum, so the
        // block falls to the band path exactly as before.
        //
        // OCR ≈ the whole cell (the 300x120 slab): the band fit (font 43) does
        // NOT beat the rectangle fit of the cell's content bounds (font 44) by
        // BAND_ACCEPT_FACTOR, and the rectangle does not overflow at its
        // fitted font → the adaptive result is REJECTED and the block keeps
        // the EXISTING legacy rectangle form in the cell's fit region, still
        // cell'd with full metadata.
        val input = block(148.5f, 58.5f, 3f, 3f, "Hello there friend").copy(segmentationMask = fullMask())

        val plan = TextLayoutPlanner.planPage(listOf(input), 300f, 120f, 1, false, m)

        val layout = draw(plan.resultsInInputOrder.single())
        layout.positionedLines.shouldBeNull()
        layout.isVertical shouldBe false
        // Legacy rectangle placement inside the fit region (= the whole cell).
        layout.originX shouldBe 150f
        layout.originY shouldBe 60f
        layout.drawAlign shouldBe TextAlign.CENTER
        layout.clipRect.shouldBeNull()
        layout.fontSizePx shouldBe 44f
        layout.lines shouldBe listOf("Hello there", "friend")
        layout.strokeWidth shouldBe TextLayoutPlanner.computeStrokeWidth(44f, 1f)
        // The hard cell and its metadata are unaffected by the layout form.
        layout.cellRect shouldBe FloatRect(0f, 0f, 300f, 120f)
        layout.planGeometryId shouldBe 0
        layout.maskComponentId shouldBe 0
        layout.hardClip shouldBe HardClip(0, 0, FloatRect(0f, 0f, 300f, 120f))
    }

    @Test
    fun `bands that clearly beat the rectangle on a thin slanted lobe are kept`() {
        // Re-pinned fixture for T912 containment-first: the rescue must decline
        // for the band-acceptance guard to be reached. The degenerate 3x3 OCR
        // box (same cell center) is below the rescue's 4px home minimum, so the
        // block falls to the band path exactly as before.
        //
        // The lobe is a 120px-wide band slanting 100px over 400 rows. The
        // ALL-CAPS trial splits the atomic token, and the bands follow the
        // slant across the whole 400-row slab (three short lines at font 33)
        // while the rectangle fit of the cell's content bounds manages only
        // font 26 for the atomic token → bands beat the rectangle by far →
        // adaptive kept.
        val input = block(108.5f, 198.5f, 3f, 3f, "SUPERLONGWORD").copy(segmentationMask = slantLobeMask())

        val plan = TextLayoutPlanner.planPage(listOf(input), 300f, 400f, 1, false, m)

        val layout = draw(plan.resultsInInputOrder.single())
        val lines = layout.positionedLines.shouldNotBeNull()
        lines shouldHaveSize 3
        // The trial's inserted hyphens exist only in the planned lines; the
        // planned lines reconstruct the block's own text.
        lines.map { it.text.replace("-", "") }.joinToString("") shouldBe "SUPERLONGWORD"
        layout.text shouldBe "SUPERLONGWORD"
        // Strict beats-the-rectangle margin: 26 is the rectangle fit of the
        // content bounds (atomic 13-char token in (219 - 2*inset) x (400 - 2*inset)).
        (layout.fontSizePx >= TextLayoutTuning.BAND_ACCEPT_FACTOR * 26f) shouldBe true
        layout.clipRect.shouldBeNull()
        // Single member of the one component: the hard cell is the slab.
        layout.cellRect shouldBe FloatRect(0f, 0f, 219f, 400f)
    }

    // ---- Fix 3: sibling font harmony ----------------------------------------

    @Test
    fun `harmony caps a wild sibling to the median multiple and is deterministic`() {
        // Three members of one full-rectangle 1200x300 component, partitioned
        // horizontally into three slabs (OCR centers x = 200 / 600 / 1000).
        // Re-pinned for T912 containment-first: a's OCR box is large (300x300),
        // so its contained rescue adopts the atomic token on one line at font
        // 30 (the OCR box's own natural reflow fit) — far above its siblings.
        // B and C carry long texts that get no contained fit at the render
        // floor (rescue null) and whose band fits now land as positioned lines
        // at the font floor 8. Median = 8 → cap = 11.2 → A is capped DOWN: the
        // band refit bounded at the cap lands on the unhyphenated token at
        // font 11; B and C are untouched. The cap demonstrably binds on an
        // ADOPTED rescue layout and only ever shrinks.
        val longText = "ab ".repeat(300).trimEnd()
        val token = "A".repeat(16)
        val mask = fullMask(1200, 300)
        val inputs = listOf(
            block(450f, 0f, 300f, 300f, token, score = 0.9f).copy(blockId = "a", segmentationMask = mask),
            block(150f, 100f, 100f, 100f, longText, score = 0.8f).copy(blockId = "b", segmentationMask = mask),
            block(950f, 100f, 100f, 100f, longText, score = 0.7f).copy(blockId = "c", segmentationMask = mask),
        )

        val plan = TextLayoutPlanner.planPage(inputs, 1200f, 300f, 1, false, m)
        val byId = plan.resultsInInputOrder.associate { it.identity.blockId!! to draw(it) }

        plan.resultsInInputOrder.forEach { result ->
            (result.outcome as? LayoutOutcome.Draw).shouldNotBeNull()
        }
        val a = byId.getValue("a")
        val b = byId.getValue("b")
        val c = byId.getValue("c")
        // Capped DOWN to the refit under the 11.2 cap (never inflated): the
        // refit's own trial does not qualify under the bounded font, so the
        // atomic text fits on one line at font 11.
        val aLines = a.positionedLines.shouldNotBeNull()
        aLines shouldHaveSize 1
        aLines.single().text shouldBe token
        a.fontSizePx shouldBe 11f
        a.cellRect shouldBe FloatRect(401f, 0f, 799f, 300f)
        a.planGeometryId shouldBe 0
        a.maskComponentId shouldBe 0
        // Below-cap members are untouched (positioned band fits at the floor).
        b.positionedLines.shouldNotBeNull()
        b.fontSizePx shouldBe 8f
        c.positionedLines.shouldNotBeNull()
        c.fontSizePx shouldBe 8f
        // Every member is within the cap multiple of the median font.
        val median = listOf(a.fontSizePx, b.fontSizePx, c.fontSizePx).sorted()[1]
        listOf(a, b, c).forEach { layout ->
            (layout.fontSizePx <= TextLayoutTuning.FONT_HARMONY_MEDIAN_CAP * median + 0.001f) shouldBe true
        }
        // Deterministic: an identical plan replays byte-for-byte.
        val replay = TextLayoutPlanner.planPage(inputs, 1200f, 300f, 1, false, m)
        val replayById = replay.resultsInInputOrder.associate { it.identity.blockId!! to draw(it) }
        for (key in listOf("a", "b", "c")) {
            replayById.getValue(key).copy(maskGeometry = null) shouldBe byId.getValue(key).copy(maskGeometry = null)
        }
    }

    @Test
    fun `capped adaptive member whose refit fails falls back to the legacy rectangle form`() {
        // Seam test (see harmonizedReplacement): through planPage a band refit
        // under a lower font cap cannot fail on well-formed span geometry
        // (band fitting is downward-closed in the font for a fixed cell), so
        // the fallback is driven directly with a span set no band can fit — a
        // single 20px-wide spanned row, narrower than any positioned line's
        // minimum integer layout width at the floor font.
        val longText = "ab ".repeat(300).trimEnd()
        val token = "A".repeat(16)
        val mask = fullMask(1200, 300)
        val inputs = listOf(
            block(550f, 100f, 100f, 100f, token, score = 0.9f).copy(blockId = "a", segmentationMask = mask),
            block(150f, 100f, 100f, 100f, longText, score = 0.8f).copy(blockId = "b", segmentationMask = mask),
            block(950f, 100f, 100f, 100f, longText, score = 0.7f).copy(blockId = "c", segmentationMask = mask),
        )
        val plan = TextLayoutPlanner.planPage(inputs, 1200f, 300f, 1, false, m)
        val byId = plan.resultsInInputOrder.associate { it.identity.blockId!! to draw(it) }
        val adaptiveA = byId.getValue("a")
        // T912 containment-first: member A is placed by the contained rescue
        // (positioned lines) — the seam test only needs an adaptive-form input.
        adaptiveA.positionedLines.shouldNotBeNull()

        val replacement = TextLayoutPlanner.harmonizedReplacement(
            layout = adaptiveA,
            block = adaptiveA.block,
            cellSpans = listOf(MaskGeometry.RowSpan(5, 401, 421)),
            slab = FloatRect(401f, 0f, 799f, 300f),
            fitRegion = FloatRect(401f, 0f, 799f, 300f),
            cap = 11.2f,
            collisionGap = 2,
            sampleSize = 1,
            scale = 1f,
            minLegible = 14f,
            pageWidth = 1200f,
            pageHeight = 300f,
            obstacles = emptyList(),
            measurer = m,
        )

        // Legacy rectangle form in the same fit region: still a Draw-able
        // layout, no positioned lines, no clip, centered on the fit region.
        replacement.positionedLines.shouldBeNull()
        replacement.isVertical shouldBe false
        replacement.originX shouldBe 600f
        replacement.originY shouldBe 150f
        replacement.drawAlign shouldBe TextAlign.CENTER
        replacement.clipRect.shouldBeNull()
        replacement.fontSizePx shouldBe 40f
        replacement.lines shouldBe listOf(token)
    }
}
