package eu.kanade.translation.engines.runtime.onnx

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PaddleOcrProviderTestConfigurationTest {

    @Test
    fun `matrix contains all explicit accelerator and CPU targets`() {
        assertEquals(
            listOf(
                PaddleOcrProviderTarget.CPU,
                PaddleOcrProviderTarget.QNN_GPU,
                PaddleOcrProviderTarget.QNN_HTP,
                PaddleOcrProviderTarget.NNAPI,
            ),
            PaddleOcrProviderTestConfiguration.matrixTargets,
        )
        PaddleOcrProviderTestConfiguration.matrixTargets
            .filter { it.isAccelerator }
            .forEach { target ->
                assertTrue(PaddleOcrProviderTestConfiguration(target).strictNoCpuFallback)
            }
    }
}
