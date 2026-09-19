package eu.kanade.translation.ocr.paddle.batch

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PaddleOcrRollingP95PolicyTest {

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
}
