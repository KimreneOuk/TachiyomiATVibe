package eu.kanade.translation.engines.vision.ocr

import eu.kanade.translation.model.Detection
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PaddleLineGrouperTest {

    @Test
    fun `line inside speech bubble is assigned to that bubble`() {
        val bubble = Detection(bbox = intArrayOf(100, 100, 300, 400), label = 0, score = 0.9f)
        val line = TextLine(bbox = intArrayOf(150, 150, 200, 350), meanScore = 0.95f)

        val groups = PaddleLineGrouper.groupLinesIntoRegions(
            detections = listOf(bubble),
            lines = listOf(line),
            imageWidth = 1000,
            imageHeight = 1000,
            isVerticalLanguage = true,
            isRtl = true,
        )

        groups.size shouldBe 1
        groups[0].detection shouldBe bubble
        groups[0].lines.size shouldBe 1
        groups[0].lines[0] shouldBe line
    }

    @Test
    fun `lines inside speech bubble are sorted Right-to-Left for vertical Japanese`() {
        val bubble = Detection(bbox = intArrayOf(100, 100, 400, 400), label = 0, score = 0.9f)
        // lineRight is at x=250..280, lineLeft is at x=150..180
        val lineLeft = TextLine(bbox = intArrayOf(150, 120, 180, 380), meanScore = 0.95f)
        val lineRight = TextLine(bbox = intArrayOf(250, 120, 280, 380), meanScore = 0.95f)

        val groups = PaddleLineGrouper.groupLinesIntoRegions(
            detections = listOf(bubble),
            lines = listOf(lineLeft, lineRight), // Passed in arbitrary order
            imageWidth = 1000,
            imageHeight = 1000,
            isVerticalLanguage = true,
            isRtl = true,
        )

        groups[0].lines[0] shouldBe lineRight
        groups[0].lines[1] shouldBe lineLeft
    }

    @Test
    fun `orphan lines outside bubbles are clustered into free-text regions`() {
        // Two adjacent vertical lines outside any bubble
        val line1 = TextLine(bbox = intArrayOf(500, 200, 530, 400), meanScore = 0.9f)
        val line2 = TextLine(bbox = intArrayOf(540, 210, 570, 390), meanScore = 0.9f)

        val groups = PaddleLineGrouper.groupLinesIntoRegions(
            detections = emptyList(),
            lines = listOf(line1, line2),
            imageWidth = 1000,
            imageHeight = 1000,
            isVerticalLanguage = true,
            isRtl = true,
        )

        groups.size shouldBe 1
        groups[0].detection.label shouldBe 2 // text_free
        groups[0].lines.size shouldBe 2
    }
}
