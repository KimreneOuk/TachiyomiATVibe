package eu.kanade.translation.processing

import eu.kanade.translation.engines.inpainting.InpaintingHardwareOverride
import eu.kanade.translation.engines.inpainting.InpaintingMode
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.translation.NeuralInpaintModel

class EngineLaneNeuralModelTest {

    @Test
    fun `neural model change rebuilds recognition even when mode stays the same`() {
        assertTrue(
            EngineLane.shouldRebuildRecognitionForInpainting(
                oldMode = InpaintingMode.QUALITY,
                newMode = InpaintingMode.QUALITY,
                oldModel = NeuralInpaintModel.AOT_GAN,
                newModel = NeuralInpaintModel.LAMA_LITERT_GPU,
            ),
        )
        assertTrue(
            EngineLane.shouldRebuildRecognitionForInpainting(
                oldMode = InpaintingMode.QUALITY,
                newMode = InpaintingMode.QUALITY,
                oldModel = NeuralInpaintModel.LAMA_LITERT_GPU,
                newModel = NeuralInpaintModel.AOT_GAN,
            ),
        )
    }

    @Test
    fun `matching inpainting mode and neural model do not require a rebuild`() {
        assertFalse(
            EngineLane.shouldRebuildRecognitionForInpainting(
                oldMode = InpaintingMode.BALANCE,
                newMode = InpaintingMode.BALANCE,
                oldModel = NeuralInpaintModel.LAMA_LITERT_GPU,
                newModel = NeuralInpaintModel.LAMA_LITERT_GPU,
            ),
        )
    }

    @Test
    fun `hardware override change rebuilds recognition while mode and model stay the same`() {
        assertTrue(
            EngineLane.shouldRebuildRecognitionForInpainting(
                oldMode = InpaintingMode.QUALITY,
                newMode = InpaintingMode.QUALITY,
                oldModel = NeuralInpaintModel.AOT_GAN,
                newModel = NeuralInpaintModel.AOT_GAN,
                oldHardwareOverride = InpaintingHardwareOverride.CPU,
                newHardwareOverride = InpaintingHardwareOverride.NPU,
            ),
        )
        assertFalse(
            EngineLane.shouldRebuildRecognitionForInpainting(
                oldMode = InpaintingMode.QUALITY,
                newMode = InpaintingMode.QUALITY,
                oldModel = NeuralInpaintModel.AOT_GAN,
                newModel = NeuralInpaintModel.AOT_GAN,
                oldHardwareOverride = InpaintingHardwareOverride.NPU,
                newHardwareOverride = InpaintingHardwareOverride.NPU,
            ),
        )
    }
}
