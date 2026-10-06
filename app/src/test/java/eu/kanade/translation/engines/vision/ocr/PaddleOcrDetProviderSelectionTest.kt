package eu.kanade.translation.engines.vision.ocr

import eu.kanade.translation.engines.runtime.onnx.PaddleOcrProviderOverride
import eu.kanade.translation.engines.runtime.onnx.PaddleOcrProviderResolution
import eu.kanade.translation.engines.runtime.onnx.PaddleOcrProviderTarget
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PaddleOcrDetProviderSelectionTest {

    @Test
    fun `explicit detector configuration selects either cpu registration`() {
        val xnnpack = PaddleOcrProviderOverride.cpuB1EmergencyFallback()
        val defaultCpu = PaddleOcrProviderOverride.cpuDefaultNoXnnpack()

        selectPaddleOcrDetProvider(xnnpack, null) shouldBe
            PaddleOcrDetProviderSelection.Explicit(xnnpack)
        selectPaddleOcrDetProvider(defaultCpu, null) shouldBe
            PaddleOcrDetProviderSelection.Explicit(defaultCpu)
        xnnpack.target shouldBe PaddleOcrProviderTarget.CPU
        defaultCpu.target shouldBe PaddleOcrProviderTarget.CPU_NO_XNNPACK
    }

    @Test
    fun `detector default and resolved production routing keep their existing paths`() {
        selectPaddleOcrDetProvider(null, null) shouldBe PaddleOcrDetProviderSelection.Automatic

        val resolution = PaddleOcrProviderResolution.cpu()
        selectPaddleOcrDetProvider(null, resolution) shouldBe
            PaddleOcrDetProviderSelection.Resolved(resolution)
    }

    @Test
    fun `explicit detector configuration takes precedence when both inputs are present`() {
        val configuration = PaddleOcrProviderOverride.cpuDefaultNoXnnpack()
        val resolution = PaddleOcrProviderResolution.cpu()

        selectPaddleOcrDetProvider(configuration, resolution) shouldBe
            PaddleOcrDetProviderSelection.Explicit(configuration)
    }
}
