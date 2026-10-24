package eu.kanade.translation.engines.vision.detection

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class OnnxPageTextDetectorBoxRowsTest {

    @Test
    fun splitsFlatBoxTensorWithStrideFour() {
        val rows = detectorBoxRows(floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f), 2)
        rows.size shouldBe 2
        rows[0] shouldBe floatArrayOf(1f, 2f, 3f, 4f)
        rows[1] shouldBe floatArrayOf(5f, 6f, 7f, 8f)
    }

    @Test
    fun splitsSingleQueryRow() {
        val rows = detectorBoxRows(floatArrayOf(10f, 20f, 30f, 40f), 1)
        rows[0] shouldBe floatArrayOf(10f, 20f, 30f, 40f)
    }
}
