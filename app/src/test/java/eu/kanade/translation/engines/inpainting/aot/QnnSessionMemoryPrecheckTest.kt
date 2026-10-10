package eu.kanade.translation.engines.inpainting.aot

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class QnnSessionMemoryPrecheckTest {

    @Test
    fun `QNN precheck accepts only at or above required headroom boundary`() {
        val modelSizeBytes = 2L * 1024L * 1024L * 1024L
        val requiredBytes = QnnSessionMemoryPrecheck.requiredHeadroomBytes(modelSizeBytes)

        QnnSessionMemoryPrecheck.decide(
            modelSizeBytes = modelSizeBytes,
            availableHeadroomBytes = requiredBytes - 1,
            lowMemory = false,
        ).allowed shouldBe false
        QnnSessionMemoryPrecheck.decide(
            modelSizeBytes = modelSizeBytes,
            availableHeadroomBytes = requiredBytes,
            lowMemory = false,
        ).allowed shouldBe true
    }

    @Test
    fun `QNN precheck refuses unknown headroom and system low memory`() {
        val modelSizeBytes = 64L * 1024L * 1024L
        val requiredBytes = QnnSessionMemoryPrecheck.requiredHeadroomBytes(modelSizeBytes)

        QnnSessionMemoryPrecheck.decide(modelSizeBytes, null, lowMemory = false).reason shouldBe "headroom_unavailable"
        QnnSessionMemoryPrecheck.decide(modelSizeBytes, requiredBytes, lowMemory = true).reason shouldBe "system_low_memory"
    }
}
