package eu.kanade.translation.ocr

import eu.kanade.translation.ocr.TextRecognizerLanguage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class VerticalLineOcrPlanContractTest {

    @Test
    fun `det missing non tall whole region keeps legacy low confidence text`() {
        val fixtureText = "fallback fixture"

        // Ticket 04/05 fallback fixtures use the old inline path: det-missing,
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
}
