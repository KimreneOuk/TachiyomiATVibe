package eu.kanade.translation.inpainting

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class NnapiHealthMonitorTest {
    @Test fun `native exception disables immediately for engine lifetime`() {
        val monitor = NnapiHealthMonitor(windowSize = 5, minimumSamples = 3, mismatchLimit = 2)
        monitor.disableForNativeException()
        monitor.recordNnapiAccepted()

        monitor.snapshot() shouldBe NnapiHealthMonitor.Snapshot(
            enabled = false,
            samples = 0,
            mismatches = 0,
            disableReason = NnapiHealthMonitor.DisableReason.NATIVE_EXCEPTION,
        )
    }

    @Test fun `rolling NNAPI-only mismatches disable at threshold`() {
        val monitor = NnapiHealthMonitor(windowSize = 5, minimumSamples = 4, mismatchLimit = 2)
        monitor.recordNnapiAccepted()
        monitor.recordNnapiOnlyGuardMismatch()
        monitor.recordNnapiAccepted()
        monitor.recordNnapiOnlyGuardMismatch()

        monitor.snapshot().enabled shouldBe false
        monitor.snapshot().disableReason shouldBe NnapiHealthMonitor.DisableReason.GUARD_MISMATCH_RATE
    }

    @Test fun `shared rejections do not penalize NNAPI`() {
        val monitor = NnapiHealthMonitor(windowSize = 3, minimumSamples = 2, mismatchLimit = 1)
        repeat(10) { monitor.recordSharedRejection() }

        monitor.snapshot() shouldBe NnapiHealthMonitor.Snapshot(true, 0, 0, null)
    }

    @Test fun `rolling window evicts old mismatches`() {
        val monitor = NnapiHealthMonitor(windowSize = 3, minimumSamples = 3, mismatchLimit = 2)
        monitor.recordNnapiOnlyGuardMismatch()
        monitor.recordNnapiAccepted()
        monitor.recordNnapiAccepted()
        monitor.recordNnapiAccepted()

        monitor.snapshot() shouldBe NnapiHealthMonitor.Snapshot(true, 3, 0, null)
    }
}
