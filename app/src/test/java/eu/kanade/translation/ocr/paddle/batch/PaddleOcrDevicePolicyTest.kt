package eu.kanade.translation.ocr.paddle.batch

import eu.kanade.translation.runtime.onnx.PaddleOcrProviderTarget
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.translation.PaddleOcrExecutionProvider
import tachiyomi.domain.translation.PaddleOcrRecognitionBatch
import tachiyomi.domain.translation.TranslationPreferences

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
    fun `recognition preference maps B1 B2 and B4 through debug activation`() {
        val expected = mapOf(
            PaddleOcrRecognitionBatch.B1 to PaddleOcrBatchSize.B1,
            PaddleOcrRecognitionBatch.B2 to PaddleOcrBatchSize.B2,
            PaddleOcrRecognitionBatch.B4 to PaddleOcrBatchSize.B4,
        )

        expected.forEach { (requested, active) ->
            val store = InMemoryPreferenceStore(
                sequenceOf(
                    InMemoryPreferenceStore.InMemoryPreference(
                        "translation_paddle_ocr_recognition_batch",
                        requested,
                        PaddleOcrRecognitionBatch.B1,
                    ),
                ),
            )
            val decision = PaddleOcrBatchActivationPolicy.currentFromPreferences(
                preferences = TranslationPreferences(store),
                requestedProvider = PaddleOcrExecutionProvider.CPU,
            )

            assertEquals(active, decision.activeBatchSize)
            assertEquals(if (active == PaddleOcrBatchSize.B1) "b1_default" else "debug_provisional_optin", decision.reason)
        }
    }
}
