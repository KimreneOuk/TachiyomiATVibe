package eu.kanade.translation.rendering

import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.segmentation.BubbleMaskRle
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.min

/**
 * T912 slice 6: bounded long `text_free` widening — every eligibility gate,
 * the unreshaped OCR baseline, the 1.25x/1.50x trials with their 8% page-add,
 * page-edge and collision-free caps, candidate dedup, no-gain rejection,
 * the 1.50x/8% width bounds across a fixture sweep, unchanged
 * direction/orientation decisions, byte-for-byte legacy compatibility for
 * short free text, and result-identity integrity.
 *
 * All fixtures use the deterministic [FakeMeasurer]: every char is
 * `0.6 * fontSizePx` wide, line height `1.2 * fontSizePx`, so every expected
 * font/box below is hand-derivable from the planner's pure math.
 */
@Disabled("Superseded by Desktop 1:1 text layout engine port")
class TextLayoutPlannerFreeTextTest {

    /** Deterministic measurement: every char is `charWidth` wide at the given size. */
    private class FakeMeasurer(private val charWidth: Float = 0.6f) : TextMeasurer {
        override fun measureTextWidth(text: String, fontSizePx: Float): Float =
            text.length * charWidth * fontSizePx

        override fun lineHeight(fontSizePx: Float): Float = fontSizePx * 1.2f
    }

    private fun block(
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        text: String,
        score: Float = 0.5f,
        direction: String = "LTR",
        label: Int = 2,
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
        label = label,
        score = score,
        direction = direction,
    ).copy(blockId = "free")

    /** The single Draw layout of a one-block plan (throws on any other shape). */
    private fun draw(plan: PageLayoutPlan): BlockLayout =
        (plan.resultsInInputOrder.single().outcome as LayoutOutcome.Draw).layout

    /** Test-visible mirror of the planner's private extentOf (CENTER/LEFT/RIGHT). */
    private fun extentOf(layout: BlockLayout, measurer: TextMeasurer): FloatRect {
        val lines = layout.lines
        val lineH = measurer.lineHeight(layout.fontSizePx)
        val totalH = lines.size * lineH
        val maxLineW = (lines.maxOfOrNull { measurer.measureTextWidth(it, layout.fontSizePx) } ?: 0f)
            .coerceAtLeast(0f)
        return when (layout.drawAlign) {
            TextAlign.LEFT -> FloatRect(layout.originX, layout.originY - totalH / 2f, layout.originX + maxLineW, layout.originY + totalH / 2f)
            TextAlign.RIGHT -> FloatRect(layout.originX - maxLineW, layout.originY - totalH / 2f, layout.originX, layout.originY + totalH / 2f)
            TextAlign.CENTER -> FloatRect(
                layout.originX - maxLineW / 2f,
                layout.originY - totalH / 2f,
                layout.originX + maxLineW / 2f,
                layout.originY + totalH / 2f,
            )
        }
    }

    private val m = FakeMeasurer()

    /** 26-char single atomic token: baseline font is always the 8px floor. */
    private val longToken = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"

    /** 6 x 16-char tokens: baseline font 9, a 125px box lifts it to 12. */
    private val token16 = List(6) { "ABCDEFGHIJKLMNOP" }.joinToString(" ")

    // ---- all gates met: candidate flow ------------------------------------

    @Test
    fun `all gates met accepts the 1_50 trial whose box fits where the OCR box overflowed`() {
        // OCR safe box 92x292: the 26-char token fits no font >= 8 (156px at 10,
        // 124.8 at the 8px floor > 92) -> baselineFont 8 < minLegible 14.
        // 1.25 trial (safe 117) still overflows at 8; the 1.50 trial (safe 142)
        // fits at 9 -> accepted on overflow removal.
        val input = block(300f, 200f, 100f, 300f, longToken)

        val layout = draw(TextLayoutPlanner.planPage(listOf(input), 1000f, 1000f, 1, false, m))

        layout.fontSizePx shouldBe 9f
        layout.safeW shouldBe 142f // widened box 150 - 2 * 4 parentless padding
        layout.safeH shouldBe 292f // original height 300 - 2 * 4
        layout.originX shouldBe 350f // OCR center X — anchoring unchanged
        layout.originY shouldBe 350f // OCR center Y
        layout.drawAlign shouldBe TextAlign.CENTER
        layout.clipRect.shouldBeNull()
        layout.isVertical shouldBe false
        input.direction shouldBe "LTR" // never mutated
    }

    @Test
    fun `the raw 1_25 factor binds and lifts the fitted font by at least 15 percent`() {
        // Baseline font 9 (16-char tokens in the 92px safe box, < minLegible).
        // The unclamped 1.25 trial (safe 117) fits font 12 >= 1.15 * 9.
        val input = block(300f, 200f, 100f, 300f, token16)

        val layout = draw(TextLayoutPlanner.planPage(listOf(input), 1000f, 1000f, 1, false, m))

        layout.fontSizePx shouldBe 12f
        layout.safeW shouldBe 117f // box 125 - 8: neither cap bound below 125
        layout.originX shouldBe 350f
        layout.originY shouldBe 350f
        layout.clipRect.shouldBeNull()
    }

    // ---- baseline is the unreshaped OCR box -------------------------------

    @Test
    fun `no qualifying candidate returns the unreshaped OCR baseline with containment intact`() {
        // A 100-char token cannot fit ANY trial width (480px at the 8px floor),
        // so both candidates fail and the OCR baseline is kept: today's exact
        // label-2 legacy behavior — containment clip inside the OCR box,
        // OCR-center anchor preserved.
        val input = block(300f, 200f, 100f, 300f, "A".repeat(100))

        val layout = draw(TextLayoutPlanner.planPage(listOf(input), 1000f, 1000f, 1, false, m))

        layout.clipRect shouldBe FloatRect(300f, 200f, 400f, 500f)
        layout.fontSizePx shouldBe 8f
        layout.safeW shouldBe 100f
        layout.safeH shouldBe 300f
        layout.originX shouldBe 350f
        layout.originY shouldBe 350f
        layout.drawAlign shouldBe TextAlign.CENTER
    }

    // ---- caps: 8% page-add, page edge, collision-free width, dedup --------

    @Test
    fun `the 8 percent page-add cap binds and both factors dedup to one candidate`() {
        // Page short side 300: cap = 100 + 0.08*300 = 124 < both raw factors,
        // so 1.25 and 1.50 clamp to the SAME 124px width — one deduplicated
        // candidate, accepted on the font-gain rule (9 -> 12 >= 1.15 * 9).
        val input = block(100f, 200f, 100f, 300f, token16)

        val layout = draw(TextLayoutPlanner.planPage(listOf(input), 300f, 1000f, 1, false, m))

        layout.fontSizePx shouldBe 12f
        layout.safeW shouldBe 116f // 124 - 8
        layout.originX shouldBe 150f
        layout.originY shouldBe 350f
        layout.clipRect.shouldBeNull()
    }

    @Test
    fun `the page edge clamps the 1_50 trial exactly at the page boundary`() {
        // OCR center 930 on a 1000px page: page-clamped width = 2*(1000-930) =
        // 140 < 150, so the accepted box ends exactly at the page right edge.
        val input = block(880f, 200f, 100f, 300f, longToken)

        val layout = draw(TextLayoutPlanner.planPage(listOf(input), 1000f, 1000f, 1, false, m))

        layout.fontSizePx shouldBe 8f
        layout.safeW shouldBe 132f // 140 - 8
        layout.originX shouldBe 930f
        layout.originY shouldBe 350f
        // Box right edge (origin + half box width 70) touches the page edge.
        (abs(layout.originX + (layout.safeW + 8f) / 2f - 1000f) < 0.01f) shouldBe true
    }

    @Test
    fun `the collision-free width clamps the trial to the placed obstacle extent`() {
        // A placed 1-char obstacle left of the OCR center constrains the band;
        // its text extent must bound the accepted box exactly (touching).
        val obstacle = block(370f, 260f, 120f, 60f, "N", score = 0.9f, label = 1).copy(blockId = "obs")
        val obsExtent = extentOf(
            TextLayoutPlanner.planPage(listOf(obstacle), 1000f, 1000f, 1, false, m)
                .drawableInRenderOrder.single(),
            m,
        )
        val expectedWidth = 2f * (obsExtent.left - 350f)
        (expectedWidth < 150f) shouldBe true // the obstacle actually binds below 1.50x

        val input = block(300f, 200f, 100f, 300f, longToken, score = 0.5f)
        val plan = TextLayoutPlanner.planPage(listOf(obstacle, input), 1000f, 1000f, 1, false, m)
        val layout = (plan.resultsInInputOrder.last { it.identity.blockId == "free" }
            .outcome as LayoutOutcome.Draw).layout

        layout.fontSizePx shouldBe 8f
        layout.originX shouldBe 350f
        layout.clipRect.shouldBeNull()
        (abs(layout.safeW - (expectedWidth - 8f)) < 0.01f) shouldBe true
        // The accepted box stops at the obstacle extent: touching, not
        // overlapping. (The drawn token is narrower than its box, so the
        // box edge — origin ± half box width, pad 4 — is the touching edge.)
        val ext = extentOf(layout, m)
        (ext.overlaps(obsExtent)) shouldBe false
        (abs(layout.originX + (layout.safeW + 8f) / 2f - obsExtent.left) < 0.05f) shouldBe true
    }

    // ---- eligibility gates --------------------------------------------------

    @Test
    fun `gate 1 violated - label 1 keeps the legacy reshape and growth path`() {
        val input = block(300f, 200f, 100f, 300f, longToken, label = 1)

        val layout = draw(TextLayoutPlanner.planPage(listOf(input), 1000f, 1000f, 1, false, m))

        // Legacy: area-preserving reshape (~173px box) then growth to ~218px
        // at font 14, re-centred OFF the OCR center (376.6 != 350).
        layout.fontSizePx shouldBe 14f
        (layout.safeW > 200f) shouldBe true
        (layout.originX > 370f && layout.originX < 385f) shouldBe true
        layout.clipRect.shouldBeNull()
    }

    @Test
    fun `gate 2 violated - a valid parent keeps the parent-box placement`() {
        val input = block(300f, 200f, 100f, 300f, longToken).copy(
            parentX = 250f,
            parentY = 150f,
            parentWidth = 400f,
            parentHeight = 450f,
        )

        val layout = draw(TextLayoutPlanner.planPage(listOf(input), 1000f, 1000f, 1, false, m))

        layout.originX shouldBe 450f // parent-box center, not the OCR center
        layout.originY shouldBe 375f
        layout.safeW shouldBe 392f // 400 - 8
        layout.safeH shouldBe 442f // 450 - 8
        layout.fontSizePx shouldBe 25f
        layout.clipRect.shouldBeNull()
    }

    @Test
    fun `gate 3 violated - resolved vertical text never enters the trial`() {
        val input = block(300f, 200f, 100f, 300f, "あ".repeat(30), direction = "TTB")

        val layout = draw(TextLayoutPlanner.planPage(listOf(input), 1000f, 1000f, 1, false, m))

        layout.isVertical shouldBe true
        input.direction shouldBe "TTB"
    }

    @Test
    fun `gate 4 violated - short free text keeps the legacy reshape byte for byte`() {
        val input = block(300f, 200f, 100f, 300f, "ABCDEFGHIJKLMNOPQRST") // 20 < 24 graphemes

        val layout = draw(TextLayoutPlanner.planPage(listOf(input), 1000f, 1000f, 1, false, m))

        // Legacy reshape fired (~165px square box, not the 92px OCR safe box
        // and not a widened 125/150 trial box), floor-honoured font 14.
        layout.fontSizePx shouldBe 14f
        (layout.safeW > 160f && layout.safeW < 170f) shouldBe true
        layout.drawAlign shouldBe TextAlign.CENTER
        layout.clipRect.shouldBeNull()
    }

    @Test
    fun `gate 5 violated - a squat box keeps the legacy non-reshape containment`() {
        val input = block(300f, 200f, 100f, 150f, longToken) // h/w = 1.5 < 2

        val layout = draw(TextLayoutPlanner.planPage(listOf(input), 1000f, 1000f, 1, false, m))

        layout.clipRect shouldBe FloatRect(300f, 200f, 400f, 350f)
        layout.fontSizePx shouldBe 8f
        layout.originX shouldBe 350f
        layout.originY shouldBe 275f
        layout.drawAlign shouldBe TextAlign.CENTER
    }

    @Test
    fun `gate 6 violated - text that already fits the OCR box keeps the legacy reshape`() {
        // 24 graphemes but no fit pressure: font 30 >= minLegible and no
        // overflow at 14 in the 92px safe OCR box.
        val input = block(300f, 200f, 100f, 300f, "AA BB CC DD EE FF GG HH II JJ KK LL")

        val layout = draw(TextLayoutPlanner.planPage(listOf(input), 1000f, 1000f, 1, false, m))

        // Legacy reshape fired for this ineligible long free text (~165px box).
        layout.fontSizePx shouldBe 34f
        (layout.safeW > 160f && layout.safeW < 170f) shouldBe true
        layout.drawAlign shouldBe TextAlign.CENTER
    }

    // ---- path precondition: masked blocks stay on the mask contracts ------

    @Test
    fun `masked long free text keeps the slice 5 cell contracts with no widening trial`() {
        val mask = BubbleMaskRle(100, 300, listOf(0, 0, 100, 300), listOf(0, 100 * 300), score = 1f)
        val input = block(0f, 0f, 100f, 300f, longToken).copy(segmentationMask = mask)

        val layout = draw(TextLayoutPlanner.planPage(listOf(input), 100f, 300f, 1, false, m))

        // Slice 5 adaptive bands own masked blocks — the slice 6 trial (a
        // legacy-form layout) never ran.
        layout.positionedLines.shouldNotBeNull()
    }

    // ---- direction / orientation stability ---------------------------------

    @Test
    fun `direction and the isVertical decision are identical for eligible and ineligible blocks`() {
        // Latin text under "TTB" resolves horizontal either way: the eligible
        // label-2 block takes the trial, the label-1 twin the legacy path.
        val eligible = block(300f, 200f, 100f, 300f, longToken, direction = "TTB")
        val ineligibleTwin = block(300f, 200f, 100f, 300f, longToken, direction = "TTB", label = 1)
        val le = draw(TextLayoutPlanner.planPage(listOf(eligible), 1000f, 1000f, 1, false, m))
        val li = draw(TextLayoutPlanner.planPage(listOf(ineligibleTwin), 1000f, 1000f, 1, false, m))
        le.isVertical shouldBe false
        li.isVertical shouldBe false
        eligible.direction shouldBe "TTB"
        ineligibleTwin.direction shouldBe "TTB"

        // CJK-majority text stays vertical on BOTH paths (gate 3 fails first).
        val cjk = "あ".repeat(30)
        val cjkFree = block(300f, 200f, 100f, 300f, cjk, direction = "TTB")
        val cjkOther = block(300f, 200f, 100f, 300f, cjk, direction = "TTB", label = 1)
        draw(TextLayoutPlanner.planPage(listOf(cjkFree), 1000f, 1000f, 1, false, m)).isVertical shouldBe true
        draw(TextLayoutPlanner.planPage(listOf(cjkOther), 1000f, 1000f, 1, false, m)).isVertical shouldBe true
        cjkFree.direction shouldBe "TTB"
    }

    // ---- byte-for-byte short free text compatibility ------------------------

    @Test
    fun `short free text matches the label 1 legacy decision bit for bit`() {
        // Both blocks are ineligible (10 non-whitespace graphemes < 24), so
        // label 2 and label 1 must take the identical legacy reshape path.
        val label2 = block(300f, 200f, 100f, 300f, "SHORT TEXT", score = 0.8f, label = 2)
        val label1 = block(300f, 200f, 100f, 300f, "SHORT TEXT", score = 0.8f, label = 1)

        val l2 = TextLayoutPlanner.planPage(listOf(label2), 1000f, 1000f, 1, false, m)
            .drawableInRenderOrder.single()
        val l1 = TextLayoutPlanner.planPage(listOf(label1), 1000f, 1000f, 1, false, m)
            .drawableInRenderOrder.single()

        l2.copy(block = l1.block) shouldBe l1
        // Shared values pin the legacy decision itself: reshaped ~173px box,
        // two wrapped lines at font 55.
        l1.fontSizePx shouldBe 55f
        (l1.safeW > 160f && l1.safeW < 170f) shouldBe true
        l1.lines shouldBe listOf("SHORT", "TEXT")
    }

    // ---- width bounds across a fixture sweep --------------------------------

    @Test
    fun `no eligible output box exceeds the 1_50x page-add or page caps across a sweep`() {
        val texts = listOf(longToken, token16, "A".repeat(100))
        val pages = listOf(600f to 1000f, 1000f to 600f, 300f to 1200f)
        var checked = 0
        for ((pw, ph) in pages) {
            for (w in listOf(40f, 80f, 100f)) {
                val h = 3f * w // h/w = 3: tall enough for every gate
                for (xCases in 0 until 3) {
                    val x = when (xCases) {
                        0 -> 10f
                        1 -> (pw - w) / 2f
                        else -> (pw - w - 10f).coerceAtLeast(0f)
                    }
                    val y = ((ph - h) / 2f).coerceAtLeast(0f)
                    for (text in texts) {
                        val input = block(x, y, w, h, text)
                        val layout = draw(TextLayoutPlanner.planPage(listOf(input), pw, ph, 1, false, m))
                        checked++
                        layout.isVertical shouldBe false
                        input.direction shouldBe "LTR"
                        // Width caps: 1.50x and the 8% page-add bound.
                        (layout.safeW <= 1.5f * w) shouldBe true
                        (layout.safeW <= w + 0.08f * min(pw, ph)) shouldBe true
                        // The drawn box stays on-page.
                        (layout.originX - layout.safeW / 2f >= -0.5f) shouldBe true
                        (layout.originX + layout.safeW / 2f <= pw + 0.5f) shouldBe true
                    }
                }
            }
        }
        (checked >= 27) shouldBe true
    }

    // ---- identity -----------------------------------------------------------

    @Test
    fun `eligible block yields exactly one Draw result with identity intact`() {
        val input = block(300f, 200f, 100f, 300f, longToken).copy(blockId = "free-1")

        val plan = TextLayoutPlanner.planPage(listOf(input), 1000f, 1000f, 1, false, m)

        plan.resultsInInputOrder shouldHaveSize 1
        plan.drawableInRenderOrder shouldHaveSize 1
        val result = plan.resultsInInputOrder.single()
        result.identity shouldBe InputIdentity(0, "free-1")
        result.chosenText shouldBe longToken
        result.planningOrdinal shouldBe 0
        result.renderOrdinal shouldBe 0
        (result.outcome as LayoutOutcome.Draw).layout.block shouldBe input
    }
}
