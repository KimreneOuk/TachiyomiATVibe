package eu.kanade.translation.ocr.paddle.batch

import eu.kanade.translation.runtime.onnx.PaddleOcrProviderTarget
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PaddleOcrDevicePolicyTest {

    @Test
    fun `unconfirmed staged cell takes explicit CPU B1 emergency fallback`() {
        val decision = PaddleOcrDevicePolicy.resolveActivation(
            stagedEnabled = true,
            requestedBatchSize = PaddleOcrBatchSize.B8,
            requestedProvider = PaddleOcrProviderTarget.QNN_HTP,
            profile = PaddleOcrDeviceProfile.untested(),
            widthBucket = PaddleOcrWidthBucket.WIDTH_1600,
        )

        assertEquals(PaddleOcrBatchSize.B1, decision.activeBatchSize)
        assertEquals(PaddleOcrProviderTarget.CPU, decision.providerTarget)
        assertTrue(decision.forceCpuB1EmergencyFallback)
    }

    @Test
    fun `confirmed cell activates only when thermal guard is clear`() {
        val combination = PaddleOcrMatrixCombination(
            provider = PaddleOcrProviderTarget.QNN_GPU,
            batchSize = PaddleOcrBatchSize.B4,
            widthBucket = PaddleOcrWidthBucket.WIDTH_640,
        )
        val profile = PaddleOcrDeviceProfile.untested().copy(
            evidenceByCombination = mapOf(combination to PaddleOcrEvidence.CONFIRMED),
        )

        val active = PaddleOcrDevicePolicy.resolveActivation(
            stagedEnabled = true,
            requestedBatchSize = PaddleOcrBatchSize.B4,
            requestedProvider = PaddleOcrProviderTarget.QNN_GPU,
            profile = profile,
        )
        assertEquals(PaddleOcrBatchSize.B4, active.activeBatchSize)
        assertFalse(active.forceCpuB1EmergencyFallback)

        val hot = PaddleOcrDevicePolicy.resolveActivation(
            stagedEnabled = true,
            requestedBatchSize = PaddleOcrBatchSize.B4,
            requestedProvider = PaddleOcrProviderTarget.QNN_GPU,
            profile = profile,
            thermalSeverity = 3,
        )
        assertEquals(PaddleOcrBatchSize.B1, hot.activeBatchSize)
        assertTrue(hot.forceCpuB1EmergencyFallback)
    }

    @Test
    fun `production B1 keeps existing provider routing`() {
        val decision = PaddleOcrDevicePolicy.resolveActivation(
            stagedEnabled = false,
            requestedBatchSize = PaddleOcrBatchSize.B1,
            requestedProvider = PaddleOcrProviderTarget.QNN_HTP,
            profile = PaddleOcrDeviceProfile.untested(),
        )

        assertEquals(PaddleOcrBatchSize.B1, decision.activeBatchSize)
        assertEquals(PaddleOcrProviderTarget.QNN_HTP, decision.providerTarget)
        assertFalse(decision.forceCpuB1EmergencyFallback)
    }
}
