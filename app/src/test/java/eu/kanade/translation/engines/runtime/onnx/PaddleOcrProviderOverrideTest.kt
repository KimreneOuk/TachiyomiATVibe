package eu.kanade.translation.engines.runtime.onnx

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PaddleOcrProviderOverrideTest {

    @Test
    fun `matrix contains all explicit accelerator and CPU targets`() {
        assertEquals(
            listOf(
                PaddleOcrProviderTarget.CPU,
                PaddleOcrProviderTarget.CPU_NO_XNNPACK,
                PaddleOcrProviderTarget.QNN_GPU,
                PaddleOcrProviderTarget.QNN_HTP,
                PaddleOcrProviderTarget.NNAPI,
            ),
            PaddleOcrProviderOverride.matrixTargets,
        )
        PaddleOcrProviderOverride.matrixTargets
            .filter { it.isAccelerator }
            .forEach { target ->
                assertTrue(PaddleOcrProviderOverride(target).strictNoCpuFallback)
            }
    }

    @Test
    fun `cpu configurations separate xnnpack registration without changing labels`() {
        val xnnpack = PaddleOcrProviderOverride.cpuB1EmergencyFallback()
        val defaultCpu = PaddleOcrProviderOverride.cpuDefaultNoXnnpack()

        assertEquals(PaddleOcrProviderTarget.CPU, xnnpack.target)
        assertEquals(HardwareDiscoveryEngine.HardwareRoute.CPU_XNNPACK, xnnpack.sessionRouteOverride)
        assertEquals("cpu", xnnpack.expectedProviderLabel)
        assertEquals("xnnpack", xnnpack.target.expectedRegisteredProviderLabel)
        assertFalse(xnnpack.strictNoCpuFallback)

        assertEquals(PaddleOcrProviderTarget.CPU_NO_XNNPACK, defaultCpu.target)
        assertEquals(null, defaultCpu.sessionRouteOverride)
        assertEquals("cpu", defaultCpu.expectedProviderLabel)
        assertEquals("cpu", defaultCpu.target.expectedRegisteredProviderLabel)
        assertFalse(defaultCpu.strictNoCpuFallback)
        assertFalse(defaultCpu.target.isAccelerator)
        assertTrue(defaultCpu.target.isCpu)
    }

    @Test
    fun `existing explicit cpu matrix target remains xnnpack and cpu label`() {
        val target = PaddleOcrProviderTarget.CPU

        assertTrue(target.isCpu)
        assertFalse(target.isAccelerator)
        assertEquals(HardwareDiscoveryEngine.HardwareRoute.CPU_XNNPACK, target.sessionRouteOverride)
        assertEquals("cpu", target.wireLabel)
        assertEquals("xnnpack", target.expectedRegisteredProviderLabel)
    }
}
