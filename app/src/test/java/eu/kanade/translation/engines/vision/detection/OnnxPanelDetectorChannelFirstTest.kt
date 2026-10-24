package eu.kanade.translation.engines.vision.detection

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class OnnxPanelDetectorChannelFirstTest {

    @Test
    fun gathersAnchorRowAcrossChannelStride() {
        // Channels-first [1, 6, anchors]: anchor 1 gets cx=10 cy=20 w=30 h=40 conf=0.9.
        val anchors = 3
        val output = FloatArray(6 * anchors)
        output[1] = 10f
        output[anchors + 1] = 20f
        output[2 * anchors + 1] = 30f
        output[3 * anchors + 1] = 40f
        output[4 * anchors + 1] = 0.9f
        val row = panelChannelFirstRow(output, 1, anchors)
        row shouldBe floatArrayOf(10f, 20f, 30f, 40f, 0.9f)
    }

    @Test
    fun decodesChannelFirstAnchorIntoCorners() {
        val anchors = 2
        val output = FloatArray(6 * anchors)
        output[0] = 100f
        output[anchors + 0] = 200f
        output[2 * anchors + 0] = 60f
        output[3 * anchors + 0] = 40f
        output[4 * anchors + 0] = 0.9f
        val box = decodePanelYoloRow(panelChannelFirstRow(output, 0, anchors), 0.5f)
        box shouldBe floatArrayOf(70f, 180f, 130f, 220f, 0.9f)
    }

    @Test
    fun dropsAnchorWhosePanelConfidenceIsBelowThreshold() {
        // Only class-1 (text) confidence is high; class-0 stays low, so the
        // anchor must be dropped exactly like the ONNX row decode.
        val anchors = 1
        val output = FloatArray(6 * anchors)
        output[4 * anchors + 0] = 0.1f
        output[5 * anchors + 0] = 0.95f
        decodePanelYoloRow(panelChannelFirstRow(output, 0, anchors), 0.5f).shouldBeNull()
    }
}
