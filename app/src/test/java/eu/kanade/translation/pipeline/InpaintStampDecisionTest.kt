package eu.kanade.translation.pipeline

import eu.kanade.translation.engines.inpainting.InpaintingMode
import eu.kanade.translation.engines.inpainting.stampName
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.translation.NeuralInpaintModel

class InpaintStampDecisionTest {

    @Test
    fun `neural model mismatch requires reinpainting`() {
        assertTrue(
            eu.kanade.translation.engines.inpainting.InpaintStampDecision.stampNeedsReinpaint(
                existingStamp = "QUALITY:AOT",
                desiredMode = InpaintingMode.QUALITY,
                desiredModel = NeuralInpaintModel.LAMA_MANGA,
            ),
        )
    }

    @Test
    fun `matching neural model stamp is reusable`() {
        assertFalse(
            eu.kanade.translation.engines.inpainting.InpaintStampDecision.stampNeedsReinpaint(
                existingStamp = "QUALITY:LAMA",
                desiredMode = InpaintingMode.QUALITY,
                desiredModel = NeuralInpaintModel.LAMA_MANGA,
            ),
        )
    }

    @Test
    fun `legacy neural stamps remain compatible with the incumbent AOT route`() {
        assertFalse(
            eu.kanade.translation.engines.inpainting.InpaintStampDecision.stampNeedsReinpaint(
                existingStamp = "QUALITY",
                desiredMode = InpaintingMode.QUALITY,
                desiredModel = NeuralInpaintModel.AOT_GAN,
            ),
        )
        assertTrue(
            eu.kanade.translation.engines.inpainting.InpaintStampDecision.stampNeedsReinpaint(
                existingStamp = "QUALITY",
                desiredMode = InpaintingMode.QUALITY,
                desiredModel = NeuralInpaintModel.LAMA_MANGA,
            ),
        )
    }

    @Test
    fun `legacy degraded stamp retries only after neural recovery`() {
        assertFalse(
            eu.kanade.translation.engines.inpainting.InpaintStampDecision.stampNeedsReinpaint(
                existingStamp = "QUALITY_DEGRADED",
                desiredMode = InpaintingMode.QUALITY,
                desiredModel = NeuralInpaintModel.AOT_GAN,
                neuralAvailable = false,
            ),
        )
        assertTrue(
            eu.kanade.translation.engines.inpainting.InpaintStampDecision.stampNeedsReinpaint(
                existingStamp = "QUALITY_DEGRADED",
                desiredMode = InpaintingMode.QUALITY,
                desiredModel = NeuralInpaintModel.AOT_GAN,
                neuralAvailable = true,
            ),
        )
    }

    @Test
    fun `degraded route is invalidated when selected neural model changes`() {
        assertTrue(
            eu.kanade.translation.engines.inpainting.InpaintStampDecision.stampNeedsReinpaint(
                existingStamp = "QUALITY:LAMA_DEGRADED",
                desiredMode = InpaintingMode.QUALITY,
                desiredModel = NeuralInpaintModel.AOT_GAN,
                neuralAvailable = false,
            ),
        )
    }

    @Test
    fun `route stamps include selected model for neural modes`() {
        InpaintingMode.QUALITY.stampName(
            neuralModel = NeuralInpaintModel.LAMA_MANGA,
            neuralAvailable = true,
        ) shouldBe "QUALITY:LAMA"
        InpaintingMode.BALANCE.stampName(
            neuralModel = NeuralInpaintModel.AOT_GAN,
            neuralAvailable = true,
        ) shouldBe "BALANCE:AOT"
    }

    @Test
    fun `degraded neural route keeps model tag and recovery semantics`() {
        val stored = InpaintingMode.QUALITY.stampName(
            neuralModel = NeuralInpaintModel.LAMA_MANGA,
            neuralAvailable = false,
        )
        stored shouldBe "QUALITY:LAMA_DEGRADED"
        assertFalse(
            eu.kanade.translation.engines.inpainting.InpaintStampDecision.stampNeedsReinpaint(
                existingStamp = stored,
                desiredMode = InpaintingMode.QUALITY,
                desiredModel = NeuralInpaintModel.LAMA_MANGA,
                neuralAvailable = false,
            ),
        )
        assertTrue(
            eu.kanade.translation.engines.inpainting.InpaintStampDecision.stampNeedsReinpaint(
                existingStamp = stored,
                desiredMode = InpaintingMode.QUALITY,
                desiredModel = NeuralInpaintModel.LAMA_MANGA,
                neuralAvailable = true,
            ),
        )
    }
}
