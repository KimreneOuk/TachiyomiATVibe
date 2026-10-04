package eu.kanade.translation.engines.vision.ocr

import eu.kanade.translation.model.TextRecognizerLanguage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class VerticalLineOcrPlanContractTest {

    @Test
    fun `det missing non tall whole region keeps legacy low confidence text`() {
        val fixtureText = "fallback fixture"

        // Fallback fixtures exercise the inline path for missing detection,
        // non-tall whole-region OCR applies isUsable() but not Paddle's 0.5
        // confidence threshold.
        assertEquals(
            fixtureText,
            VerticalLineOcr.filterRecognizedText(
                text = fixtureText,
                confidence = 0.49f,
                language = TextRecognizerLanguage.ENGLISH,
                applyConfidence = false,
            ),
        )
        assertEquals(
            "",
            VerticalLineOcr.filterRecognizedText(
                text = fixtureText,
                confidence = 0.49f,
                language = TextRecognizerLanguage.ENGLISH,
                applyConfidence = true,
            ),
        )
    }

    @Test
    fun `vertical CJK lines rotate 90 CCW as whole detector lines without glyph fragmentation`() = kotlinx.coroutines.runBlocking<Unit> {
        val fakeBitmap = io.mockk.mockk<android.graphics.Bitmap>(relaxed = true) {
            io.mockk.every { width } returns 100
            io.mockk.every { height } returns 200
        }
        io.mockk.mockkStatic(android.graphics.Bitmap::class)
        io.mockk.mockkConstructor(android.graphics.Matrix::class)
        try {
            io.mockk.every {
                android.graphics.Bitmap.createBitmap(
                    any<android.graphics.Bitmap>(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } returns fakeBitmap
            io.mockk.every {
                android.graphics.Bitmap.createBitmap(
                    any<android.graphics.Bitmap>(),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } returns fakeBitmap
            io.mockk.every { anyConstructed<android.graphics.Matrix>().postRotate(any()) } returns true

            val lines = listOf(
                TextLine(
                    bbox = intArrayOf(20, 10, 40, 150), // vertical line: w=20, h=140 (h > w * 1.5)
                    meanScore = 0.9f,
                ),
            )
            val plan = VerticalLineOcr.planDetColumns(
                crop = fakeBitmap,
                lines = lines,
                language = TextRecognizerLanguage.JAPANESE,
                prefersHorizontalText = true,
                isClosed = { false },
            )
            assertEquals(1, plan.leaves.size)
            val leaf = plan.leaves[0]
            assertEquals(eu.kanade.translation.engines.vision.ocr.paddle.batch.PaddleOcrRotation.CCW_90, leaf.rotation)
            assertEquals(null, leaf.glyphIndex)
            assertEquals(eu.kanade.translation.engines.vision.ocr.paddle.batch.PaddleOcrFallbackKind.DETECTOR_LINE, leaf.fallbackKind)
        } finally {
            io.mockk.unmockkAll()
        }
    }
}
