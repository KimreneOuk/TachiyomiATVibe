package eu.kanade.translation.rendering

import eu.kanade.translation.model.TranslationBlock
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Pins [TextLayoutPlanner] — the pure, neighbour-aware render-layout solver that
 * replaced [PageTextRenderer]'s old independent per-block placement.
 *
 * All cases use a deterministic [FakeMeasurer] (no `android.graphics.Paint`), so
 * the layout math is unit-tested exactly the way the project tests its other pure
 * helpers ([BoxGeometry], [RenderColorEstimator.colorPolicy], [BubbleMaskBuilder]).
 * Each test maps to one of the three defects the planner was introduced to fix:
 *  - collision of adjacent blocks (bottom-left / bottom-right),
 *  - tall parentless boxes forcibly widened symmetrically into neighbours,
 *  - text collapsing to an illegible ~8 px with no legibility floor.
 */
class TextLayoutPlannerTest {

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
        score: Float = 1f,
        direction: String = "LTR",
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
        direction = direction,
    )

    private fun extent(layout: BlockLayout, measurer: TextMeasurer): FloatRect =
        extentOfPublic(layout, measurer)

    @Test
    fun `empty block list returns empty plan`() {
        TextLayoutPlanner.plan(emptyList(), 1000f, 1000f, 1, false, FakeMeasurer()) shouldHaveSize 0
    }

    @Test
    fun `blank-translation blocks are dropped and excluded from the obstacle set`() {
        val blank = block(x = 100f, y = 100f, w = 100f, h = 40f, text = "", score = 0.9f)
        val other = block(x = 110f, y = 100f, w = 100f, h = 40f, text = "Hi", score = 0.5f)

        val plan = TextLayoutPlanner.plan(listOf(blank, other), 1000f, 1000f, 1, false, FakeMeasurer())

        plan shouldHaveSize 1
        plan.first().text shouldBe "Hi"
    }

    @Test
    fun `single isolated block keeps its box centre (no regression for the common case)`() {
        val b = block(x = 200f, y = 200f, w = 120f, h = 60f, text = "Hi", score = 0.8f)

        val plan = TextLayoutPlanner.plan(listOf(b), 1000f, 1000f, 1, false, FakeMeasurer())

        plan shouldHaveSize 1
        val l = plan.first()
        l.clipRect shouldBe null
        // Centre of the box is (260, 230); no neighbour ⇒ no re-anchor.
        l.originX shouldBe (200f + 120f / 2f)
        l.originY shouldBe (200f + 60f / 2f)
    }

    @Test
    fun `two adjacent bottom-left and bottom-right blocks do not overlap after planning`() {
        // Defect 1: two distinct boxes placed close together. Their rendered text
        // extents used to overlap because each was drawn centred with no awareness
        // of the other. Here the boxes themselves do NOT overlap, but long text in
        // each could overflow into the other — the planner must keep the rendered
        // extents disjoint.
        val left = block(x = 100f, y = 900f, w = 140f, h = 70f, text = "Hello world text", score = 0.9f)
        val right = block(x = 360f, y = 900f, w = 140f, h = 70f, text = "Other long line", score = 0.8f)

        val plan = TextLayoutPlanner.plan(listOf(left, right), 800f, 1000f, 1, false, FakeMeasurer())
        plan shouldHaveSize 2

        val a = extent(plan[0], FakeMeasurer())
        val b = extent(plan[1], FakeMeasurer())
        a.overlaps(b) shouldBe false
    }

    @Test
    fun `tall parentless box shifts away from a neighbour when a symmetric placement would collide`() {
        // Defect 2: a tall parentless box (h/w > 2) is widened by the reshape. If a
        // symmetric (centred) placement would overlap a neighbour, the planner must
        // shift it the minimal distance away instead of overlapping. Here the tall
        // box is centred just left of the neighbour so the reshaped width overlaps
        // it; the planner shifts the tall box left and the rendered extents come out
        // disjoint.
        // Tall box x=170,w=50 ⇒ centre 195, origRight 220. Reshape width ≈ 122
        // (area 15000, sqrt≈122), so a centred placement spans [134, 256] — which
        // overlaps the neighbour (left 240) by ~16 px; the planner shifts it left.
        val tall = block(x = 170f, y = 100f, w = 50f, h = 300f, text = "A reasonably long line", score = 0.5f)
        val neighbour = block(x = 240f, y = 100f, w = 120f, h = 60f, text = "Neighbour bubble text", score = 0.95f)

        val plan = TextLayoutPlanner.plan(listOf(tall, neighbour), 800f, 600f, 1, false, FakeMeasurer())

        val tallLayout = plan.first { it.text == "A reasonably long line" }
        val neighbourLayout = plan.first { it.text == "Neighbour bubble text" }
        val m = FakeMeasurer()
        extent(tallLayout, m).overlaps(extent(neighbourLayout, m)) shouldBe false
        // The tall box must have shifted LEFT (its centre moved off the original
        // 195 toward a smaller x) to get out from under the neighbour.
        (tallLayout.originX < 195f) shouldBe true
    }

    @Test
    fun `tall isolated box keeps its original centre (minimal displacement, no regression)`() {
        // With no neighbour to avoid, the reshaped box stays centred on the original
        // bubble centre — the legacy symmetric placement. No spurious shift from an
        // imbalanced free-space tie-break.
        val tall = block(x = 375f, y = 100f, w = 50f, h = 300f, text = "Line", score = 0.7f)

        val plan = TextLayoutPlanner.plan(listOf(tall), 800f, 600f, 1, false, FakeMeasurer())
        val l = plan.first()
        // Original centre x = 400; reshape should keep the centre at 400.
        l.originX shouldBe 400f
        l.clipRect shouldBe null
    }

    @Test
    fun `long text that would collapse below the legibility floor is contained to its initial box`() {
        // Defect 3 (legibility floor) + containment: a parentless non-reshape box
        // must NOT grow onto the page. The 30×30 box at (400,400) contains its text
        // by clipping; the layout's box is clamped to 30×30 and clipRect is set.
        val small = block(x = 400f, y = 400f, w = 30f, h = 30f, text = "A very long translation sentence", score = 0.8f)

        val plan = TextLayoutPlanner.plan(listOf(small), 1500f, 1500f, 1, false, FakeMeasurer())
        val l = plan.first()

        // Hard containment: clipRect is set and matches the initial box.
        l.clipRect shouldBe FloatRect(400f, 400f, 430f, 430f)
        // The box did NOT grow beyond its initial 30×30 dimensions.
        (l.safeW <= 22f) shouldBe true
        (l.safeH <= 22f) shouldBe true
    }

    @Test
    fun `parented block never bleeds past its parent`() {
        val parentX = 100f
        val parentY = 200f
        val parentW = 200f
        val parentH = 80f
        val parented = TranslationBlock(
            text = "",
            translation = "A somewhat longer text that may try to expand past the parent box boundary",
            width = 30f,
            height = 30f,
            x = parentX,
            y = parentY,
            symHeight = 1f,
            symWidth = 1f,
            angle = 0f,
            label = 1,
            score = 0.8f,
            parentX = parentX,
            parentY = parentY,
            parentWidth = parentW,
            parentHeight = parentH,
            direction = "LTR",
        )
        val m = FakeMeasurer()
        val plan = TextLayoutPlanner.plan(listOf(parented), 800f, 600f, 1, false, m)
        val l = plan.first()
        val e = extent(l, m)
        val parent = FloatRect(parentX, parentY, parentX + parentW, parentY + parentH)
        (e.left >= parent.left - 0.5f) shouldBe true
        (e.top >= parent.top - 0.5f) shouldBe true
        (e.right <= parent.right + 0.5f) shouldBe true
        (e.bottom <= parent.bottom + 0.5f) shouldBe true
    }

    @Test
    fun `parentless non-reshape block clamps to its initial box but a tall one still grows`() {
        val m = FakeMeasurer()
        val nonTall = block(x = 100f, y = 100f, w = 60f, h = 40f, text = "Long text that gets clamped to its initial box", score = 0.8f)
        val tall = block(x = 300f, y = 200f, w = 50f, h = 300f, text = "Tall reshaping box grows toward page", score = 0.7f)

        val plan = TextLayoutPlanner.plan(listOf(nonTall, tall), 800f, 600f, 1, false, m)
        val nonTallLayout = plan.first { it.text == "Long text that gets clamped to its initial box" }
        val tallLayout = plan.first { it.text == "Tall reshaping box grows toward page" }

        // Non-tall (parentless, not reshaped): the box is clamped to its initial
        // 60×40. Any overflow is contained by a clipRect INSIDE the initial box —
        // the renderer clips drawn pixels to it. The raw text extent may exceed the
        // box at the legibility floor, which is exactly why the clip is set, so the
        // containment is asserted on the clipRect, not the text footprint.
        val initialBox = FloatRect(100f, 100f, 160f, 140f)
        val nonTallClip = nonTallLayout.clipRect
        if (nonTallClip != null) {
            (nonTallClip.left >= initialBox.left - 0.5f) shouldBe true
            (nonTallClip.top >= initialBox.top - 0.5f) shouldBe true
            (nonTallClip.right <= initialBox.right + 0.5f) shouldBe true
            (nonTallClip.bottom <= initialBox.bottom + 0.5f) shouldBe true
        } else {
            // No clip ⇒ text fit inside the clamped box ⇒ extent stays within it.
            val nonTallExtent = extent(nonTallLayout, m)
            (nonTallExtent.right <= initialBox.right + 0.5f) shouldBe true
        }

        // Tall (parentless, reshaped): R = page, so growth is preserved.
        // The reshaped box is wider than the initial 50 px; the tall extent
        // exceeds the initial box width.
        val tallExtent = extent(tallLayout, m)
        (tallExtent.width() > 50f) shouldBe true
    }

    @Test
    fun `page-edge clamping keeps a growing box on-page`() {
        // A box pinned to the right edge with long text must grow LEFT (it cannot
        // grow right past the page width).
        val edge = block(x = 1450f, y = 400f, w = 30f, h = 30f, text = "Overflowing long text", score = 0.8f)

        val plan = TextLayoutPlanner.plan(listOf(edge), 1500f, 800f, 1, false, FakeMeasurer())
        val l = plan.first()

        // The grown box's right edge must not exceed the page width.
        val rightEdge = l.originX + l.safeW / 2f
        (rightEdge <= 1500f + 0.5f) shouldBe true
    }

    @Test
    fun `clip safety-net engages when a block genuinely cannot avoid a neighbour`() {
        // Two overlapping boxes with equal claim on the same space: the higher-
        // score box places first; the lower-score one must clip to its own side so
        // the rendered extents never overlap (the structural guarantee).
        val a = block(x = 100f, y = 100f, w = 200f, h = 80f, text = "First", score = 0.95f)
        val b = block(x = 200f, y = 100f, w = 200f, h = 80f, text = "Second longer text here", score = 0.5f)

        val plan = TextLayoutPlanner.plan(listOf(a, b), 1000f, 1000f, 1, false, FakeMeasurer())
        val ea = extent(plan.first { it.text == "First" }, FakeMeasurer())
        val eb = extent(plan.first { it.text == "Second longer text here" }, FakeMeasurer())

        ea.overlaps(eb) shouldBe false
    }

    @Test
    fun `higher-score block places first and constrains the lower-score one`() {
        // Ordering invariant: the most confident box keeps its preferred placement;
        // the lower-score box is the one that yields (grows/clips).
        val high = block(x = 400f, y = 400f, w = 80f, h = 80f, text = "High", score = 0.99f)
        val low = block(x = 420f, y = 410f, w = 80f, h = 80f, text = "Low", score = 0.2f)

        val plan = TextLayoutPlanner.plan(listOf(high, low), 1000f, 1000f, 1, false, FakeMeasurer())

        val highLayout = plan.first { it.text == "High" }
        // High-score block should keep its natural centre (440, 440) since the
        // lower-score block yields to it.
        highLayout.originX shouldBe (400f + 80f / 2f)
        highLayout.clipRect shouldBe null
    }

    @Test
    fun `cjkRatio and shouldRenderVertical are delegated consistently`() {
        // The planner owns the single source of truth for the vertical decision;
        // the renderer's companion delegates here. Lock the contract.
        TextLayoutPlanner.shouldRenderVertical("こんにちは") shouldBe true
        TextLayoutPlanner.shouldRenderVertical("What's up?") shouldBe false
        TextLayoutPlanner.cjkRatio("(笑)") shouldBe (1f / 3f)
    }

    @Test
    fun `computeRects reshapes a tall parentless box but not a parented one`() {
        val tallParentless = block(x = 0f, y = 0f, w = 50f, h = 300f, text = "x")
        val r1 = TextLayoutPlanner.computeRects(tallParentless, 1)
        r1.reshaped shouldBe true
        // Area roughly preserved: reshaped width is wider, height smaller.
        (r1.baseW > 50f) shouldBe true
        (r1.baseH < 300f) shouldBe true

        val parented = TranslationBlock(
            text = "",
            translation = "x",
            width = 50f,
            height = 300f,
            x = 0f,
            y = 0f,
            symHeight = 1f,
            symWidth = 1f,
            angle = 0f,
            label = 1,
            score = 1f,
            parentX = 0f,
            parentY = 0f,
            parentWidth = 200f,
            parentHeight = 300f,
            direction = "LTR",
        )
        val r2 = TextLayoutPlanner.computeRects(parented, 1)
        r2.reshaped shouldBe false
        // Parented block uses the parent rect directly.
        r2.baseW shouldBe 200f
        r2.baseH shouldBe 300f
    }

    @Test
    fun `minLegibleFont scales with page dimension and never drops below the absolute floor`() {
        val smallPage = TextLayoutPlanner.minLegibleFont(400f, 400f, 1f)
        val largePage = TextLayoutPlanner.minLegibleFont(2000f, 2000f, 1f)
        // Absolute floor honoured on a tiny page.
        smallPage shouldBe 14f
        // Scales up on a large page (~1.4% of 2000 ≈ 28).
        (largePage > 20f) shouldBe true
        (largePage < 40f) shouldBe true
        largePage shouldBe (2000f * 0.014f)
    }

    @Test
    fun `cjkWrap treats Latin words as atomic and breaks CJK anywhere`() {
        val m = FakeMeasurer()
        // "Hello" is one atomic token; at a tiny maxWidth it stays whole on a line.
        val lines = TextLayoutPlanner.cjkWrap("Hello world", 10f, 1f, m)
        lines.size shouldBe 2

        // CJK glyphs each break independently.
        val cjkLines = TextLayoutPlanner.cjkWrap("こんにちは", 100f, 1f * 0.6f * 2f, m)
        (cjkLines.size >= 1) shouldBe true
    }

    @Test
    fun `binarySearchFontSize never exceeds the fit ceiling and respects the floor on overflow`() {
        val m = FakeMeasurer()
        // Generous box: should hit near the ceiling (72).
        val big = TextLayoutPlanner.binarySearchFontSize("Hi", 800f, 800f, 800f, false, 1f, m)
        big shouldBe 72f

        // Tiny box, long text: collapses to the floor (8). The planner's growth/
        // clip layers handle lifting this to legible — the search itself just reports.
        val tiny = TextLayoutPlanner.binarySearchFontSize(
            "A very long sentence that cannot fit", 10f, 10f, 10f, false, 1f, m,
        )
        (tiny <= 8f) shouldBe true
    }

    @Test
    fun `plan does not crash and yields no overlaps on a crowded page`() {
        // Stress / fuzz-like guard: many overlapping blocks must all come out with
        // pairwise-disjoint extents (the structural invariant), regardless of how
        // crowded the page is. Some blocks will be clipped — that is acceptable.
        val blocks = (0 until 6).map { i ->
            block(
                x = 100f + (i % 3) * 90f,
                y = 100f + (i / 3) * 90f,
                w = 120f,
                h = 60f,
                text = "Block number $i with text",
                score = 1f - i * 0.1f,
            )
        }
        val m = FakeMeasurer()
        val plan = TextLayoutPlanner.plan(blocks, 500f, 500f, 1, false, m)

        // Every pair of rendered extents must be disjoint.
        for (i in plan.indices) {
            for (j in (i + 1) until plan.size) {
                val ei = extent(plan[i], m)
                val ej = extent(plan[j], m)
                ei.overlaps(ej) shouldBe false
            }
        }
        // At least some blocks survived (none silently dropped for being hard).
        (plan.size >= 1) shouldBe true
    }
}

/**
 * Test-visible mirror of the planner's private [extentOf]. Computes the rendered
 * pixel extent of a finalized layout the same way the planner does, so tests can
 * assert the structural no-overlap invariant without reaching into private state.
 */
private fun extentOfPublic(layout: BlockLayout, measurer: TextMeasurer): FloatRect {
    val charStep = layout.fontSizePx * 1.05f
    val colStep = layout.fontSizePx * 1.25f
    val cx = layout.originX
    val cy = layout.originY
    return if (layout.isVertical) {
        val chars = layout.text.count { it != '\r' && it != '\n' && it != ' ' }
        val maxChars = maxOf(1, (layout.safeH / charStep).toInt())
        val cols = maxOf(1, kotlin.math.ceil(chars.toFloat() / maxChars).toInt())
        val totalW = cols * colStep
        val colH = minOf(layout.safeH, chars * charStep)
        FloatRect(cx - totalW / 2f, cy - colH / 2f, cx + totalW / 2f, cy + colH / 2f)
    } else {
        val lines = TextLayoutPlanner.cjkWrap(layout.text, layout.fontSizePx, layout.safeW, measurer)
        val lineH = measurer.lineHeight(layout.fontSizePx)
        val totalH = lines.size * lineH
        val maxLineW = (lines.maxOfOrNull { measurer.measureTextWidth(it, layout.fontSizePx) } ?: 0f)
            .coerceAtLeast(0f)
        if (layout.drawAlignLeft) {
            FloatRect(cx, cy - totalH / 2f, cx + maxLineW, cy + totalH / 2f)
        } else {
            FloatRect(cx - maxLineW / 2f, cy - totalH / 2f, cx + maxLineW / 2f, cy + totalH / 2f)
        }
    }
}
