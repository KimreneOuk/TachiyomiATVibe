package eu.kanade.translation.engines.vision.detection

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class OnnxPanelDetectorDecodeTest {

    @Test
    fun decodesCxcywhRowToCorners() {
        // cx=100, cy=200, w=60, h=40, conf=0.9
        val box = decodePanelYoloRow(floatArrayOf(100f, 200f, 60f, 40f, 0.9f, 0.1f), 0.5f)
        box shouldBe floatArrayOf(70f, 180f, 130f, 220f, 0.9f)
    }

    @Test
    fun rejectsRowsBelowConfidenceThreshold() {
        decodePanelYoloRow(floatArrayOf(100f, 200f, 60f, 40f, 0.49f), 0.5f).shouldBeNull()
    }

    @Test
    fun rejectsRowsWithNaNConfidence() {
        decodePanelYoloRow(floatArrayOf(100f, 200f, 60f, 40f, Float.NaN), 0.5f).shouldBeNull()
    }

    @Test
    fun rejectsDegenerateBoxes() {
        decodePanelYoloRow(floatArrayOf(100f, 200f, 0f, 40f, 0.9f), 0.5f).shouldBeNull()
        decodePanelYoloRow(floatArrayOf(100f, 200f, 60f, -5f, 0.9f), 0.5f).shouldBeNull()
    }

    @Test
    fun rejectsShortRows() {
        decodePanelYoloRow(floatArrayOf(100f, 200f, 60f), 0.5f).shouldBeNull()
    }

    @Test
    fun decodesNegativeLetterboxCentres() {
        // A row whose box straddles the letterbox edge keeps its negative
        // corner; the inverse letterbox + clamp in detect() trims it later.
        val box = decodePanelYoloRow(floatArrayOf(5f, 5f, 30f, 30f, 0.8f), 0.5f)
        box shouldBe floatArrayOf(-10f, -10f, 20f, 20f, 0.8f)
    }
}
