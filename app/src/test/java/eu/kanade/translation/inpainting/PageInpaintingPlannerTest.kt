package eu.kanade.translation.inpainting

import eu.kanade.translation.detection.Detection
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.TranslationBlock
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
    ): TranslationBlock = TranslationBlock(
        text = "source",
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
