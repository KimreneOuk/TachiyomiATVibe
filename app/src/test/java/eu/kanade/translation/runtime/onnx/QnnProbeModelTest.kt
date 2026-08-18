package eu.kanade.translation.runtime.onnx

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class QnnProbeModelTest {

    @Test
    fun `probe model is the validated 91-byte single-Relu graph`() {
        QnnProbeModel.MODEL_BYTES.size shouldBe 91
        val asText = String(QnnProbeModel.MODEL_BYTES, Charsets.ISO_8859_1)
        asText shouldContain "Relu"
        asText shouldContain "qnn_probe"
    }

    @Test
    fun `probe combos order most-specific first and always end with autodetect`() {
        HardwareDiscoveryEngine.qnnProbeCombos("57", "75") shouldBe
            listOf("57" to "75", "57" to null, null to null)
        HardwareDiscoveryEngine.qnnProbeCombos("69", null) shouldBe
            listOf("69" to null, null to null)
    }

    @Test
    fun `probe combos collapse to a single autodetect attempt without soc`() {
        HardwareDiscoveryEngine.qnnProbeCombos(null, "75") shouldBe listOf(null to null)
        HardwareDiscoveryEngine.qnnProbeCombos(null, null) shouldBe listOf(null to null)
    }
}
