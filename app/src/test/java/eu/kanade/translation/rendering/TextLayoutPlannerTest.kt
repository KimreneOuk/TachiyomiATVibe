package eu.kanade.translation.rendering

import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.segmentation.BubbleMaskRle
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Pins [TextLayoutPlanner] — the pure, neighbour-aware render-layout solver that
 * replaced the old independent per-block placement.
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
        label: Int = 1,
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
    fun `each nonblank shared-mask block keeps its own identity and text`() {
        // One continuous, borderless segmentation mask can contain several
        // distinct OCR blocks. The planner's current public result is a list of
        // BlockLayout values, so blockId is the observable identity key here.
        val sharedMask = BubbleMaskRle(
            width = 300,
            height = 120,
            bounds = listOf(0, 0, 300, 120),
            runs = listOf(0, 300 * 120),
            score = 1f,
        )
        val inputs = listOf(
            block(10f, 30f, 80f, 60f, "I WAS A FAN!").copy(blockId = "left", segmentationMask = sharedMask),
            block(110f, 30f, 80f, 60f, "I'M SORRY!").copy(blockId = "middle", segmentationMask = sharedMask),
            block(210f, 30f, 80f, 60f, "I FORCED YOU INTO THAT RELATIONSHIP.").copy(
                blockId = "right",
                segmentationMask = sharedMask,
            ),
        )

        val plan = TextLayoutPlanner.plan(inputs, 300f, 120f, 1, false, FakeMeasurer())

        plan shouldHaveSize inputs.size
        plan.map { it.block.blockId }.toSet() shouldBe setOf("left", "middle", "right")
        plan.associate { it.block.blockId to it.text } shouldBe mapOf(
            "left" to "I WAS A FAN!",
            "middle" to "I'M SORRY!",
            "right" to "I FORCED YOU INTO THAT RELATIONSHIP.",
        )
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
        val tallExt = extent(tallLayout, m)
        val neighbourExt = extent(neighbourLayout, m)
        tallExt.overlaps(neighbourExt) shouldBe false
        // The tall box must have moved LEFT off its original centre (195) to get
        // out from under the neighbour — asserted on the extent centre so it is
        // robust to LEFT/RIGHT edge-anchoring (originX is an edge, not the centre,
        // once the box grows horizontally).
        ((tallExt.left + tallExt.right) / 2f < 195f) shouldBe true
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
    fun `free text stays anchored to OCR centre when reshaped`() {
        val free = block(
            x = 170f,
            y = 100f,
            w = 50f,
            h = 300f,
            text = "A reasonably long line",
            score = 0.8f,
            label = 2,
        )

        val plan = TextLayoutPlanner.plan(listOf(free), 800f, 600f, 1, false, FakeMeasurer())
        val l = plan.first()

        l.originX shouldBe 195f
        l.originY shouldBe 250f
        l.drawAlign shouldBe TextAlign.CENTER
    }

    @Test
    fun `vertical source latin parented block stays anchored to parentBox centre`() {
        val parented = TranslationBlock(
            text = "",
            translation = "Latin translated text",
            width = 50f,
            height = 200f,
            x = 200f,
            y = 150f,
            symHeight = 1f,
            symWidth = 1f,
            angle = 0f,
            label = 1,
            score = 0.8f,
            parentX = 200f,
            parentY = 150f,
            parentWidth = 200f,
            parentHeight = 200f,
            direction = "TTB",
        )

        val plan = TextLayoutPlanner.plan(listOf(parented), 800f, 600f, 1, false, FakeMeasurer())
        val l = plan.first()

        l.isVertical shouldBe false
        l.originX shouldBe 300f
        l.originY shouldBe 250f
        l.drawAlign shouldBe TextAlign.CENTER
    }

    @Test
    fun `long text that cannot fit uses the containment clip and fit size`() {
        // Defect 3 (legibility floor) + containment: a parentless non-reshape box
        // must NOT grow onto the page. The 30×30 box at (400,400) contains its text
        // by clipping; the layout's box is clamped to 30×30 and clipRect is set.
        val small = block(x = 400f, y = 400f, w = 30f, h = 30f, text = "A very long translation sentence", score = 0.8f)

        val plan = TextLayoutPlanner.plan(listOf(small), 1500f, 1500f, 1, false, FakeMeasurer())
        val l = plan.first()

        // Hard containment: clipRect is set and matches the initial box.
        l.clipRect shouldBe FloatRect(400f, 400f, 430f, 430f)
        // Once containment clipping is active, the planner bypasses the
        // legibility floor and fits the text to the clipped bounds.
        l.fontSizePx shouldBe 8f
        l.drawAlign shouldBe TextAlign.LEFT
    }

    @Test
    fun `parented block keeps its fitted parent dimensions and parentBox anchor`() {
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
        val clip = l.clipRect
        clip shouldBe null
        l.originX shouldBe (parented.parentX + parented.parentWidth / 2f)
        l.originY shouldBe (parented.parentY + parented.parentHeight / 2f)
        l.safeW shouldBe 192f
        l.safeH shouldBe 72f
    }

    @Test
    fun `parentless non-reshape block clamps to its initial box but a tall one still grows`() {
        val m = FakeMeasurer()
        val nonTall =
            block(
                x = 100f,
                y = 100f,
                w = 60f,
                h = 40f,
                text = "Long text that gets clamped to its initial box",
                score = 0.8f,
            )
        val tall =
            block(x = 300f, y = 200f, w = 50f, h = 300f, text = "Tall reshaping box grows toward page", score = 0.7f)

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
            "A very long sentence that cannot fit",
            10f,
            10f,
            10f,
            false,
            1f,
            m,
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

    @Test
    fun `near-right-edge text never drifts toward the page centre`() {
        // Defect: a parentless edge box that needs to grow used to re-centre on the
        // grown box and slide toward the page centre. Full-width walls above/below
        // block vertical headroom (they do NOT consume horizontal free space — see
        // freeSpaceLeft/Right), forcing the long text to grow horizontally near the
        // right edge. Whatever axis it grows on, it must stay on its own (right)
        // side of the page and never drift to the centre.
        val pageWidth = 1500f
        val pageHeight = 600f
        val wallTop = block(x = 0f, y = 0f, w = pageWidth, h = 242f, text = "W", score = 0.99f)
        val wallBot = block(x = 0f, y = 358f, w = pageWidth, h = pageHeight - 358f, text = "W", score = 0.99f)
        val sfx = block(
            x = 1300f,
            y = 100f,
            w = 30f,
            h = 400f,
            text = "A very long sound effect line that must grow wide not tall",
            score = 0.5f,
        )
        val m = FakeMeasurer()
        val plan = TextLayoutPlanner.plan(listOf(wallTop, wallBot, sfx), pageWidth, pageHeight, 1, false, m)
        val l = plan.first { it.text != "W" }
        val e = extent(l, m)

        // Stays in the right half (no slide toward the centre) and on-page.
        ((e.left + e.right) / 2f > pageWidth / 2f) shouldBe true
        (e.right <= pageWidth + 0.5f) shouldBe true
        (e.left >= 0f) shouldBe true
    }

    @Test
    fun `near-left-edge text never drifts toward the page centre`() {
        val pageWidth = 1500f
        val pageHeight = 600f
        val wallTop = block(x = 0f, y = 0f, w = pageWidth, h = 242f, text = "W", score = 0.99f)
        val wallBot = block(x = 0f, y = 358f, w = pageWidth, h = pageHeight - 358f, text = "W", score = 0.99f)
        val sfx = block(
            x = 170f,
            y = 100f,
            w = 30f,
            h = 400f,
            text = "A very long sound effect line that must grow wide not tall",
            score = 0.5f,
        )
        val m = FakeMeasurer()
        val plan = TextLayoutPlanner.plan(listOf(wallTop, wallBot, sfx), pageWidth, pageHeight, 1, false, m)
        val l = plan.first { it.text != "W" }
        val e = extent(l, m)

        // Stays in the left half (no slide toward the centre) and on-page.
        ((e.left + e.right) / 2f < pageWidth / 2f) shouldBe true
        (e.left >= -0.5f) shouldBe true
        (e.right <= pageWidth + 0.5f) shouldBe true
    }

    @Test
    fun `horizontal growth alone does not force edge alignment without rendered overlap`() {
        val edge = block(
            x = 1410f,
            y = 100f,
            w = 30f,
            h = 220f,
            text = "SupercalifragilisticexpialidociousSoundEffectWord",
            score = 0.8f,
        )

        val plan = TextLayoutPlanner.plan(listOf(edge), 1500f, 800f, 1, false, FakeMeasurer())
        val layout = plan.first()

        layout.drawAlign shouldBe TextAlign.CENTER
    }

    @Test
    fun `long text in a box with vertical headroom wraps into more than one line`() {
        // The relaxed height-growth cap lets long text grow TALLER and wrap into
        // several lines rather than ballooning to a single wide line. With a tall
        // reshaped box (region = page ⇒ free vertical headroom) and long text, the
        // planned layout must wrap into >1 line at its fitted size.
        val tall = block(
            x = 200f,
            y = 100f,
            w = 40f,
            h = 400f,
            text = "Quite a long translated sentence that should wrap onto multiple rendered lines",
            score = 0.8f,
        )
        val m = FakeMeasurer()
        val plan = TextLayoutPlanner.plan(listOf(tall), 1500f, 1500f, 1, false, m)
        val l = plan.first()
        val lines = TextLayoutPlanner.cjkWrap(l.text, l.fontSizePx, l.safeW, m)
        (lines.size > 1) shouldBe true
        // And it never leaves the page / crosses an obstacle (none here): the
        // extent stays within the page width.
        val e = extent(l, m)
        (e.right <= 1500f + 0.5f) shouldBe true
        (e.left >= -0.5f) shouldBe true
    }

    @Test
    fun `conjoined double cloud bubbles anchor to their respective parentBoxes at legible font size`() {
        val mask = BubbleMaskRle(
            width = 1000,
            height = 1000,
            bounds = listOf(100, 100, 700, 450),
            runs = emptyList(),
            score = 0.95f,
        )
        val leftLobe = block(
            x = 170f,
            y = 220f,
            w = 170f,
            h = 120f,
            text = "No way... To meet in a place like this...",
            score = 0.9f,
        ).copy(
            parentX = 100f,
            parentY = 130f,
            parentWidth = 285f,
            parentHeight = 320f,
            segmentationMask = mask,
        )

        val rightLobe = block(
            x = 460f,
            y = 200f,
            w = 190f,
            h = 140f,
            text = "I've been searching for you all along! Thank goodness you're safe!",
            score = 0.9f,
        ).copy(
            parentX = 415f,
            parentY = 95f,
            parentWidth = 300f,
            parentHeight = 350f,
            segmentationMask = mask,
        )

        val m = FakeMeasurer()
        val plan = TextLayoutPlanner.plan(listOf(leftLobe, rightLobe), 1000f, 1000f, 1, false, m)

        plan shouldHaveSize 2
        (plan[0].fontSizePx >= 16f) shouldBe true
        (plan[1].fontSizePx >= 16f) shouldBe true
        // Center of left text is inside left parentBox
        (plan[0].originX >= 100f && plan[0].originX <= 385f) shouldBe true
        // Center of right text is inside right parentBox
        (plan[1].originX >= 415f && plan[1].originX <= 715f) shouldBe true
    }

    @Test
    fun `ultra thin tall oval utilizes vertical height with large font`() {
        val tallOval = block(
            x = 200f,
            y = 160f,
            w = 70f,
            h = 300f,
            text = "Something like that could never be forgiven by anyone at all!",
            score = 0.9f,
        ).copy(
            parentX = 180f,
            parentY = 100f,
            parentWidth = 110f,
            parentHeight = 420f,
        )

        val m = FakeMeasurer()
        val plan = TextLayoutPlanner.plan(listOf(tallOval), 1000f, 1000f, 1, false, m)

        plan shouldHaveSize 1
        val l = plan.first()
        (l.fontSizePx >= 14f) shouldBe true
        (l.lines.size >= 4) shouldBe true
    }

    @Test
    fun `touching dialogue bubbles with 0px gap maintain clean non-overlapping text layout`() {
        val leftSpeaker = block(
            x = 150f,
            y = 200f,
            w = 180f,
            h = 130f,
            text = "What is this unbelievable power?!",
            score = 0.9f,
        ).copy(
            parentX = 90f,
            parentY = 120f,
            parentWidth = 310f,
            parentHeight = 340f,
        )

        val rightSpeaker = block(
            x = 450f,
            y = 200f,
            w = 180f,
            h = 130f,
            text = "It broke through our defenses with ease!",
            score = 0.9f,
        ).copy(
            parentX = 400f,
            parentY = 110f,
            parentWidth = 310f,
            parentHeight = 345f,
        )

        val m = FakeMeasurer()
        val plan = TextLayoutPlanner.plan(listOf(leftSpeaker, rightSpeaker), 1000f, 1000f, 1, false, m)

        plan shouldHaveSize 2
        val ext0 = extentOfPublic(plan[0], m)
        val ext1 = extentOfPublic(plan[1], m)
        (ext0.right <= ext1.left) shouldBe true
    }

    @Test
    fun `cjkWrap keeps ordinary Latin words atomic but honors source hyphens`() {
        val m = FakeMeasurer(0.6f)
        // 10px font => each character is 6px wide. Ordinary Latin words are
        // atomic: the too-wide token remains intact and no hyphen is invented.
        val oversized = TextLayoutPlanner.cjkWrap("HANAZUMI", 10f, 30f, m)
        oversized shouldBe listOf("HANAZUMI")
        oversized.none { it.contains('-') } shouldBe true

        // A hyphen present in source text remains a legal break point.
        val sourceHyphen = TextLayoutPlanner.cjkWrap("NEE-CHAN", 10f, 30f, m)
        sourceHyphen shouldBe listOf("NEE-", "CHAN")
    }

    @Test
    fun `adjacent narrow bubbles wrap cleanly without horizontal collision`() {
        val m = FakeMeasurer(0.6f)
        val bubbleLeft = block(
            x = 108f,
            y = 545f,
            w = 45f,
            h = 110f,
            text = "HANAZUMI NEE-CHAN...",
            score = 0.95f,
        ).copy(
            parentX = 108f,
            parentY = 545f,
            parentWidth = 45f,
            parentHeight = 110f,
        )

        val bubbleRight = block(
            x = 155f,
            y = 530f,
            w = 55f,
            h = 120f,
            text = "IS IT POSSIBLE THAT...",
            score = 0.90f,
        ).copy(
            parentX = 155f,
            parentY = 530f,
            parentWidth = 55f,
            parentHeight = 120f,
        )

        val plan = TextLayoutPlanner.plan(listOf(bubbleLeft, bubbleRight), 1000f, 1000f, 1, false, m)
        plan shouldHaveSize 2

        val extLeft = extentOfPublic(plan[0], m)
        val extRight = extentOfPublic(plan[1], m)

        // Must not overlap horizontally
        (extLeft.right <= extRight.left + 0.5f) shouldBe true
    }

    @Test
    fun `wide horizontal webtoon ellipse contains text strictly within bubble height without vertical overflow`() {
        val m = FakeMeasurer(0.6f)
        val wideBubble = block(
            x = 80f,
            y = 120f,
            w = 520f,
            h = 140f,
            text = "HE USED SOME WEIRD MARTIAL ARTS I'VE NEVER EVEN HEARD OF, CLAIMING IT WAS A SECRET TECHNIQUE.",
            score = 0.98f,
        ).copy(
            parentX = 80f,
            parentY = 120f,
            parentWidth = 520f,
            parentHeight = 140f,
        )

        val plan = TextLayoutPlanner.plan(listOf(wideBubble), 1000f, 2000f, 1, false, m)
        plan shouldHaveSize 1
        val layout = plan[0]

        val ext = extentOfPublic(layout, m)

        // Text must not overflow top or bottom of the bubble
        (ext.top >= wideBubble.parentY - 1f) shouldBe true
        (ext.bottom <= wideBubble.parentY + wideBubble.parentHeight + 1f) shouldBe true
        // Text must not overflow left or right
        (ext.left >= wideBubble.parentX - 1f) shouldBe true
        (ext.right <= wideBubble.parentX + wideBubble.parentWidth + 1f) shouldBe true
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
        val lines = layout.lines
        val lineH = measurer.lineHeight(layout.fontSizePx)
        val totalH = lines.size * lineH
        val maxLineW = (lines.maxOfOrNull { measurer.measureTextWidth(it, layout.fontSizePx) } ?: 0f)
            .coerceAtLeast(0f)
        when (layout.drawAlign) {
            TextAlign.LEFT -> FloatRect(cx, cy - totalH / 2f, cx + maxLineW, cy + totalH / 2f)
            TextAlign.RIGHT -> FloatRect(cx - maxLineW, cy - totalH / 2f, cx, cy + totalH / 2f)
            TextAlign.CENTER -> FloatRect(cx - maxLineW / 2f, cy - totalH / 2f, cx + maxLineW / 2f, cy + totalH / 2f)
        }
    }
}
