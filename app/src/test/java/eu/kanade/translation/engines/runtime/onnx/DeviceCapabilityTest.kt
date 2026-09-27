package eu.kanade.translation.engines.runtime.onnx

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class DeviceCapabilityTest {

    @Test
    fun `soc model mapping matches the QAIRT QnnTypes enum`() {
        DeviceCapability.qnnSocModelFor("SM8650") shouldBe "57"
        DeviceCapability.qnnSocModelFor("sm8650") shouldBe "57"
        DeviceCapability.qnnSocModelFor("SM8650P") shouldBe "57"
        DeviceCapability.qnnSocModelFor("SM8550") shouldBe "43"
        DeviceCapability.qnnSocModelFor("SM8475") shouldBe "42"
        DeviceCapability.qnnSocModelFor("SM8450") shouldBe "36"
        DeviceCapability.qnnSocModelFor("SM8750P") shouldBe "69"
        DeviceCapability.qnnSocModelFor("SM8850") shouldBe "87"
        DeviceCapability.qnnSocModelFor("SDM845") shouldBe "1"
    }

    @Test
    fun `soc model mapping returns null for unknown or enum-less SoCs`() {
        // The deprecated Qnn_SocModel_t has no entry for SoCs newer than SM8850.
        DeviceCapability.qnnSocModelFor("SM8845") shouldBe null
        DeviceCapability.qnnSocModelFor("Tensor G4") shouldBe null
        DeviceCapability.qnnSocModelFor("") shouldBe null
    }

    @Test
    fun `htp arch mapping only emits values the ORT parser accepts`() {
        DeviceCapability.qnnHtpArchFor("SM8350") shouldBe "68"
        DeviceCapability.qnnHtpArchFor("SM8450") shouldBe "69"
        DeviceCapability.qnnHtpArchFor("SM8475") shouldBe "69"
        DeviceCapability.qnnHtpArchFor("SM8550") shouldBe "73"
        DeviceCapability.qnnHtpArchFor("SM8650") shouldBe "75"
        DeviceCapability.qnnHtpArchFor("SM8650P") shouldBe "75"
    }

    @Test
    fun `htp arch is omitted when the ORT parser cannot express it`() {
        // v79 (SM8750) is rejected by ORT 1.27's htp_arch parser
        // (unmerged onnxruntime PR #31638); the probe falls through to the
        // soc-only and autodetect combos instead.
        DeviceCapability.qnnHtpArchFor("SM8750") shouldBe null
        DeviceCapability.qnnHtpArchFor("SM8750P") shouldBe null
        DeviceCapability.qnnHtpArchFor("SM8845") shouldBe null
    }
}
