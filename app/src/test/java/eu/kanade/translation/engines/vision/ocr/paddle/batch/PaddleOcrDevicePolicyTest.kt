package eu.kanade.translation.engines.vision.ocr.paddle.batch

import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.translation.engines.runtime.onnx.PaddleOcrProviderTarget
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.translation.PaddleOcrExecutionProvider

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

    @Test
    fun `debug explicit opt in provisionally activates an unconfirmed cell`() {
        val decision = PaddleOcrDevicePolicy.resolveActivation(
            stagedEnabled = true,
            requestedBatchSize = PaddleOcrBatchSize.B4,
            requestedProvider = PaddleOcrProviderTarget.QNN_HTP,
            profile = PaddleOcrDeviceProfile.untested(),
            debugProvisionalOptIn = true,
        )

        assertEquals(PaddleOcrBatchSize.B4, decision.activeBatchSize)
        assertEquals(PaddleOcrProviderTarget.QNN_HTP, decision.providerTarget)
        assertFalse(decision.forceCpuB1EmergencyFallback)
        assertEquals("debug_provisional_optin", decision.reason)
    }

    @Test
    fun `release keeps unconfirmed cell on CPU B1`() {
        val decision = PaddleOcrDevicePolicy.resolveActivation(
            stagedEnabled = true,
            requestedBatchSize = PaddleOcrBatchSize.B2,
            requestedProvider = PaddleOcrProviderTarget.QNN_GPU,
            profile = PaddleOcrDeviceProfile.untested(),
            debugProvisionalOptIn = false,
        )

        assertEquals(PaddleOcrBatchSize.B1, decision.activeBatchSize)
        assertEquals(PaddleOcrProviderTarget.CPU, decision.providerTarget)
        assertTrue(decision.forceCpuB1EmergencyFallback)
        assertEquals("combination_not_confirmed", decision.reason)
    }

    @Test
    fun `debug provisional opt in still honors thermal guard`() {
        val decision = PaddleOcrDevicePolicy.resolveActivation(
            stagedEnabled = true,
            requestedBatchSize = PaddleOcrBatchSize.B2,
            requestedProvider = PaddleOcrProviderTarget.CPU,
            profile = PaddleOcrDeviceProfile.untested(),
            thermalSeverity = 3,
            debugProvisionalOptIn = true,
        )

        assertEquals(PaddleOcrBatchSize.B1, decision.activeBatchSize)
        assertEquals(PaddleOcrProviderTarget.CPU, decision.providerTarget)
        assertTrue(decision.forceCpuB1EmergencyFallback)
        assertEquals("thermal_guard", decision.reason)
    }

    @Test
    fun `pinned B1 entry keeps provider target and never enables dynamic batching`() {
        val decision = PaddleOcrBatchActivationPolicy.currentPinnedB1(
            requestedProvider = PaddleOcrExecutionProvider.QUALCOMM_QNN_GPU,
        )

        assertEquals(PaddleOcrBatchSize.B1, decision.activeBatchSize)
        assertEquals(PaddleOcrProviderTarget.QNN_GPU, decision.providerTarget)
        assertFalse(decision.dynamicPageBatch)
        assertFalse(decision.forceCpuB1EmergencyFallback)
    }

    @Test
    fun `DYNAMIC preference maps to the B8 tier with the dynamic flag in debug`() {
        val decision = PaddleOcrBatchActivationPolicy.current(
            requestedBatchSize = PaddleOcrBatchSize.B8,
            requestedProvider = PaddleOcrProviderTarget.QNN_HTP,
            dynamicPageBatch = true,
        )
        assertEquals(PaddleOcrBatchSize.B8, decision.activeBatchSize)
        assertTrue(decision.dynamicPageBatch)
        assertFalse(decision.forceCpuB1EmergencyFallback)
    }

    @Test
    fun `DYNAMIC skips the staged build-config cap that clamps fixed tiers`() {
        // Build config caps fixed tiers at 4; a dynamic request must not be
        // clamped because its ceiling is memory-derived at runtime.
        val decision = PaddleOcrBatchActivationPolicy.current(
            requestedBatchSize = PaddleOcrBatchSize.B8,
            requestedProvider = PaddleOcrProviderTarget.QNN_HTP,
            dynamicPageBatch = true,
        )
        assertEquals(PaddleOcrBatchSize.B8, decision.activeBatchSize)
    }

    @Test
    fun `staged flag off forces emergency CPU B1 even for dynamic`() {
        val decision = PaddleOcrDevicePolicy.resolveActivation(
            stagedEnabled = false,
            requestedBatchSize = PaddleOcrBatchSize.B8,
            requestedProvider = PaddleOcrProviderTarget.QNN_HTP,
            profile = PaddleOcrDeviceProfile.untested(),
        )
        assertEquals(PaddleOcrBatchSize.B1, decision.activeBatchSize)
        assertTrue(decision.forceCpuB1EmergencyFallback)
    }
}
