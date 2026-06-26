package eu.kanade.translation.inpainting

import eu.kanade.translation.detection.Detection
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.TranslationBlock
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PageInpaintingPlannerTest {

    @Test
    fun `mlkit-style block becomes a smart cleaner text region`() {
        val page = PageTranslation(
            blocks = mutableListOf(
                block(x = 10f, y = 20f, width = 30f, height = 40f, label = 1),
            ),
        )

        val input = PageInpaintingPlanner.build(page)

        input.boxes.map { it.toList() }.shouldContainExactly(listOf(listOf(10, 20, 40, 60)))
        input.labels.shouldContainExactly(listOf(1))
        input.extraDetectorCount shouldBe 0
        input.source shouldBe PageInpaintingPlanner.MaskSource.RECOMPUTED
    }

    @Test
    fun `parent bubble block emits bubble and text regions`() {
        val page = PageTranslation(
            blocks = mutableListOf(
                block(
                    x = 25f,
                    y = 35f,
                    width = 30f,
                    height = 40f,
                    label = 1,
                    parentX = 10f,
                    parentY = 20f,
                    parentWidth = 80f,
                    parentHeight = 90f,
                ),
            ),
        )

        val input = PageInpaintingPlanner.build(page)

        input.boxes.map { it.toList() }.shouldContainExactly(
            listOf(
                listOf(10, 20, 90, 110),
                listOf(25, 35, 55, 75),
            ),
        )
        input.labels.shouldContainExactly(listOf(0, 1))
    }

    @Test
    fun `non-overlapping detector box is preserved for cleanup`() {
        val page = PageTranslation(
            blocks = mutableListOf(block(x = 10f, y = 10f, width = 20f, height = 20f, label = 1)),
        )
        page.allTextDetections = listOf(
            Detection(intArrayOf(100, 100, 130, 130), label = 2, score = 0.9f, className = "text"),
        )

        val input = PageInpaintingPlanner.build(page)

        input.boxes.map { it.toList() }.shouldContainExactly(
            listOf(
                listOf(10, 10, 30, 30),
                listOf(100, 100, 130, 130),
            ),
        )
        input.labels.shouldContainExactly(listOf(1, 2))
        input.extraDetectorCount shouldBe 1
    }

    @Test
    fun `detector box overlapping OCR block is not duplicated`() {
        val page = PageTranslation(
            blocks = mutableListOf(block(x = 10f, y = 10f, width = 30f, height = 30f, label = 1)),
        )
        page.allTextDetections = listOf(
            Detection(intArrayOf(11, 11, 39, 39), label = 2, score = 0.9f, className = "text"),
        )

        val input = PageInpaintingPlanner.build(page)

        input.boxes.map { it.toList() }.shouldContainExactly(listOf(listOf(10, 10, 40, 40)))
        input.labels.shouldContainExactly(listOf(1))
        input.extraDetectorCount shouldBe 0
    }

    // ── computeMask + persisted-mask path ───────────────────────────────────
    // The resume/reopen path: allTextDetections (@Transient) is gone, so the
    // planner must load the mask from the persisted inpaintMaskBoxes captured
    // at OCR time. computeMask is the single source of truth for that capture.

    @Test
    fun `computeMask captures bubble + text + detector-only boxes`() {
        val page = PageTranslation(
            blocks = mutableListOf(
                block(
                    x = 25f, y = 35f, width = 30f, height = 40f, label = 1,
                    parentX = 10f, parentY = 20f, parentWidth = 80f, parentHeight = 90f,
                ),
                block(x = 10f, y = 10f, width = 20f, height = 20f, label = 1),
            ),
        )
        page.allTextDetections = listOf(
            // detector-only region, disjoint from the OCR boxes
            Detection(intArrayOf(100, 100, 130, 130), label = 2, score = 0.9f, className = "text"),
        )

        val mask = PageInpaintingPlanner.computeMask(page)
        page.inpaintMaskBoxes = mask

        // bubble box (label 0), two text boxes (label 1 each), detector-only (label 2)
        mask.map { it.label } shouldBe listOf(0, 1, 1, 2)
        mask.map { listOf(it.x1, it.y1, it.x2, it.y2) }.shouldContainExactly(
            listOf(
                listOf(10, 20, 90, 110), // bubble
                listOf(25, 35, 55, 75), // text under bubble
                listOf(10, 10, 30, 30), // standalone text
                listOf(100, 100, 130, 130), // detector-only
            ),
        )
    }

    // ── render-aware erase (unread regions keep their original pixels) ──────

    @Test
    fun `blank-text OCR block is excluded from the erase mask`() {
        val page = PageTranslation(
            blocks = mutableListOf(
                block(x = 10f, y = 10f, width = 20f, height = 20f, label = 1, text = ""),
                block(x = 100f, y = 100f, width = 20f, height = 20f, label = 1, text = "readable"),
            ),
        )

        val mask = PageInpaintingPlanner.computeMask(page)

        // Only the readable block's text box is erased; the blank block is left intact.
        mask.map { listOf(it.x1, it.y1, it.x2, it.y2) }.shouldContainExactly(
            listOf(listOf(100, 100, 120, 120)),
        )
    }

    @Test
    fun `parent bubble whose only children are blank is not erased`() {
        val page = PageTranslation(
            blocks = mutableListOf(
                block(
                    x = 25f, y = 35f, width = 30f, height = 40f, label = 1, text = "",
                    parentX = 10f, parentY = 20f, parentWidth = 80f, parentHeight = 90f,
                ),
            ),
        )

        val mask = PageInpaintingPlanner.computeMask(page)

        // Neither the bubble box nor the blank text box: original pixels kept.
        mask.shouldBeEmpty()
    }

    @Test
    fun `parent bubble with a readable child is erased even when a sibling is blank`() {
        // Limitation: the parent bubble is erased as a unit, so the blank
        // sibling's sub-area is still erased (no per-glyph erase). The readable
        // child still emits the bubble + its own text box.
        val page = PageTranslation(
            blocks = mutableListOf(
                block(
                    x = 25f, y = 35f, width = 30f, height = 40f, label = 1, text = "",
                    parentX = 10f, parentY = 20f, parentWidth = 80f, parentHeight = 90f,
                ),
                block(
                    x = 26f, y = 36f, width = 30f, height = 40f, label = 1, text = "ok",
                    parentX = 10f, parentY = 20f, parentWidth = 80f, parentHeight = 90f,
                ),
            ),
        )

        val mask = PageInpaintingPlanner.computeMask(page)

        mask.map { it.label } shouldBe listOf(0, 1)
    }

    @Test
    fun `detector box overlapping a blank-text OCR block is not re-added as extra`() {
        // Load-bearing overlap test: the blank block contributes nothing, and
        // the overlapping detector box must NOT be promoted to an extra-detector
        // (label 2) entry — that would re-erase the preserved region.
        val page = PageTranslation(
            blocks = mutableListOf(
                block(x = 10f, y = 10f, width = 30f, height = 30f, label = 1, text = ""),
            ),
        )
        page.allTextDetections = listOf(
            Detection(intArrayOf(11, 11, 39, 39), label = 2, score = 0.9f, className = "text"),
        )

        val mask = PageInpaintingPlanner.computeMask(page)

        mask.shouldBeEmpty()
    }

    @Test
    fun `readable text block is still erased`() {
        // Contract #14a preserved: a readable (e.g. watermark) block is erased.
        val page = PageTranslation(
            blocks = mutableListOf(
                block(x = 10f, y = 10f, width = 40f, height = 15f, label = 1, text = "WATERMARK"),
            ),
        )

        val mask = PageInpaintingPlanner.computeMask(page)

        mask.map { listOf(it.x1, it.y1, it.x2, it.y2) }.shouldContainExactly(
            listOf(listOf(10, 10, 50, 25)),
        )
    }

    @Test
    fun `build prefers the persisted mask over live allTextDetections`() {
        // Simulate a resumed page: inpaintMaskBoxes was persisted at OCR time,
        // and allTextDetections (transient) is empty. build must load the
        // persisted mask, not recompute an incomplete one from the empty
        // transient field.
        val page = PageTranslation(
            blocks = mutableListOf(block(x = 10f, y = 10f, width = 20f, height = 20f, label = 1)),
        )
        page.inpaintMaskBoxes = listOf(
            eu.kanade.translation.model.InpaintMaskBox(10, 10, 30, 30, 1),
            eu.kanade.translation.model.InpaintMaskBox(100, 100, 130, 130, 2),
        )
        page.allTextDetections = emptyList() // lost on resume

        val input = PageInpaintingPlanner.build(page)

        input.source shouldBe PageInpaintingPlanner.MaskSource.PERSISTED
        input.boxes.map { it.toList() }.shouldContainExactly(
            listOf(listOf(10, 10, 30, 30), listOf(100, 100, 130, 130)),
        )
        input.labels.shouldContainExactly(listOf(1, 2))
        input.extraDetectorCount shouldBe 1
    }

    @Test
    fun `build with no persisted mask still recomputes from live detections`() {
        // The fresh single-page path: no persisted mask yet, but allTextDetections
        // is populated in memory. build must recompute (back-compat for the path
        // that has not been through computeMask yet).
        val page = PageTranslation(
            blocks = mutableListOf(block(x = 10f, y = 10f, width = 20f, height = 20f, label = 1)),
        )
        page.allTextDetections = listOf(
            Detection(intArrayOf(100, 100, 130, 130), label = 2, score = 0.9f, className = "text"),
        )

        val input = PageInpaintingPlanner.build(page)

        input.source shouldBe PageInpaintingPlanner.MaskSource.RECOMPUTED
        input.extraDetectorCount shouldBe 1
    }

    private fun block(
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        label: Int,
        parentX: Float = 0f,
        parentY: Float = 0f,
        parentWidth: Float = 0f,
        parentHeight: Float = 0f,
        text: String = "source",
    ): TranslationBlock = TranslationBlock(
        text = text,
        width = width,
        height = height,
        x = x,
        y = y,
        symHeight = 10f,
        symWidth = 10f,
        angle = 0f,
        label = label,
        parentX = parentX,
        parentY = parentY,
        parentWidth = parentWidth,
        parentHeight = parentHeight,
    )
}
