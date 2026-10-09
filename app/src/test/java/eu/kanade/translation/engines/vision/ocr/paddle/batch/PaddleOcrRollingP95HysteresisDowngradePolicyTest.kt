package eu.kanade.translation.engines.vision.ocr.paddle.batch

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PaddleOcrRollingP95HysteresisDowngradePolicyTest {

    @Test
    fun `two high windows downgrade one step and healthy windows recover with hysteresis`() {
        val policy = PaddleOcrRollingP95HysteresisDowngradePolicy(
            initialBatchSize = PaddleOcrBatchSize.B8,
            config = PaddleOcrRollingP95Config(
                windowSize = 2,
                downgradeP95Ms = 100.0,
                recoveryP95Ms = 50.0,
                highWindowsBeforeDowngrade = 2,
                lowWindowsBeforeRecovery = 3,
            ),
        )

        policy.record(200.0)
        policy.record(200.0)
        policy.record(200.0)
        assertEquals(PaddleOcrBatchSize.B4, policy.activeBatchSize)

        repeat(3) { policy.record(10.0) }
        assertEquals(PaddleOcrBatchSize.B8, policy.activeBatchSize)
    }

    @Test
    fun `a single slow sample does not downgrade`() {
        val policy = PaddleOcrRollingP95HysteresisDowngradePolicy(
            initialBatchSize = PaddleOcrBatchSize.B4,
            config = PaddleOcrRollingP95Config(
                windowSize = 4,
                downgradeP95Ms = 100.0,
                recoveryP95Ms = 50.0,
                highWindowsBeforeDowngrade = 2,
            ),
        )

        policy.record(500.0)
        policy.record(1.0)
        policy.record(1.0)
        policy.record(1.0)
        assertEquals(PaddleOcrBatchSize.B4, policy.activeBatchSize)
    }

    @Test
    fun `B4 governor downgrades to B2 then B1 and recovery is capped at requested B4`() {
        val policy = PaddleOcrRollingP95HysteresisDowngradePolicy(
            initialBatchSize = PaddleOcrBatchSize.B4,
            maximumBatchSize = PaddleOcrBatchSize.B4,
            config = PaddleOcrRollingP95Config(
                windowSize = 2,
                downgradeP95Ms = 100.0,
                recoveryP95Ms = 50.0,
                highWindowsBeforeDowngrade = 1,
                lowWindowsBeforeRecovery = 1,
            ),
        )

        policy.record(200.0)
        policy.record(200.0)
        assertEquals(PaddleOcrBatchSize.B2, policy.activeBatchSize)

        policy.record(200.0)
        policy.record(200.0)
        assertEquals(PaddleOcrBatchSize.B1, policy.activeBatchSize)

        repeat(6) { policy.record(10.0) }
        assertEquals(PaddleOcrBatchSize.B4, policy.activeBatchSize)
        policy.record(10.0)
        policy.record(10.0)
        assertEquals(PaddleOcrBatchSize.B4, policy.activeBatchSize)
    }

    @Test
    fun `batch latency normalizer scales multi-leaf dynamic batches against target batch size`() {
        // 16 leaves in 1200ms = 75ms/leaf. Normalized to B8 = 600ms.
        val normalizedHealthy = PaddleOcrBatchLatencyNormalizer.normalize(
            batchLatencyMs = 1200.0,
            leafCount = 16,
            targetBatchSize = PaddleOcrBatchSize.B8,
        )
        assertEquals(600.0, normalizedHealthy, 0.001)

        // 16 leaves in 2500ms = 156.25ms/leaf. Normalized to B8 = 1250ms.
        val normalizedSlow = PaddleOcrBatchLatencyNormalizer.normalize(
            batchLatencyMs = 2500.0,
            leafCount = 16,
            targetBatchSize = PaddleOcrBatchSize.B8,
        )
        assertEquals(1250.0, normalizedSlow, 0.001)

        val policy = PaddleOcrRollingP95HysteresisDowngradePolicy(
            initialBatchSize = PaddleOcrBatchSize.B8,
            maximumBatchSize = PaddleOcrBatchSize.B8,
            config = PaddleOcrRollingP95Config(
                windowSize = 2,
                downgradeP95Ms = 1000.0,
                recoveryP95Ms = 750.0,
                highWindowsBeforeDowngrade = 2,
                lowWindowsBeforeRecovery = 2,
            ),
        )

        // Consecutive 1200ms batches of 16 leaves normalize to 600ms <= 1000ms -> no downgrade
        policy.record(normalizedHealthy)
        policy.record(normalizedHealthy)
        policy.record(normalizedHealthy)
        assertEquals(PaddleOcrBatchSize.B8, policy.activeBatchSize)

        // Consecutive slow batches normalize to 1250ms > 1000ms -> downgrades
        repeat(3) { policy.record(normalizedSlow) }
        assertEquals(PaddleOcrBatchSize.B4, policy.activeBatchSize)
    }
}
