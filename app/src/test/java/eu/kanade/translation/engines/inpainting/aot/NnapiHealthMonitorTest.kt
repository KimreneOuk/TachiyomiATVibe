package eu.kanade.translation.engines.inpainting.aot

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class NnapiHealthMonitorTest {

    @Test
    fun `runtime exception disables only this engine monitor and new session can reset it`() {
        val monitor = NnapiHealthMonitor()
        monitor.disableForNativeException()

        monitor.snapshot() shouldBe NnapiHealthMonitor.Snapshot(
            enabled = false,
            disableReason = NnapiHealthMonitor.DisableReason.NATIVE_EXCEPTION,
        )

        monitor.resetForNewSession()

        monitor.snapshot() shouldBe NnapiHealthMonitor.Snapshot(
            enabled = true,
            disableReason = null,
        )
    }
}
