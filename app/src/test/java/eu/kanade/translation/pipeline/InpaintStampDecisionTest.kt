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
                desiredModel = NeuralInpaintModel.LAMA_LITERT_GPU,
            ),
        )
    }

    @Test
    fun `matching neural model stamp is reusable`() {
        assertFalse(
            eu.kanade.translation.engines.inpainting.InpaintStampDecision.stampNeedsReinpaint(
                existingStamp = "QUALITY:LAMA_LITERT",
                desiredMode = InpaintingMode.QUALITY,
                desiredModel = NeuralInpaintModel.LAMA_LITERT_GPU,
            ),
        )
        assertFalse(
            eu.kanade.translation.engines.inpainting.InpaintStampDecision.stampNeedsReinpaint(
                existingStamp = "QUALITY:AOT",
                desiredMode = InpaintingMode.QUALITY,
                desiredModel = NeuralInpaintModel.AOT_GAN,
            ),
        )
    }

    @Test
    fun `LiteRT and AOT stamps invalidate each other`() {
        assertTrue(
            eu.kanade.translation.engines.inpainting.InpaintStampDecision.stampNeedsReinpaint(
                existingStamp = "QUALITY:LAMA_LITERT",
                desiredMode = InpaintingMode.QUALITY,
                desiredModel = NeuralInpaintModel.AOT_GAN,
            ),
        )
        assertTrue(
            eu.kanade.translation.engines.inpainting.InpaintStampDecision.stampNeedsReinpaint(
                existingStamp = "QUALITY:AOT",
                desiredMode = InpaintingMode.QUALITY,
                desiredModel = NeuralInpaintModel.LAMA_LITERT_GPU,
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
                desiredModel = NeuralInpaintModel.LAMA_LITERT_GPU,
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
                existingStamp = "QUALITY:LAMA_LITERT_DEGRADED",
                desiredMode = InpaintingMode.QUALITY,
                desiredModel = NeuralInpaintModel.AOT_GAN,
                neuralAvailable = false,
            ),
        )
    }

    @Test
    fun `route stamps include selected model for neural modes`() {
        InpaintingMode.QUALITY.stampName(
            neuralModel = NeuralInpaintModel.LAMA_LITERT_GPU,
            neuralAvailable = true,
        ) shouldBe "QUALITY:LAMA_LITERT"
        InpaintingMode.BALANCE.stampName(
            neuralModel = NeuralInpaintModel.AOT_GAN,
            neuralAvailable = true,
        ) shouldBe "BALANCE:AOT"
    }

    @Test
    fun `deprecated model tags no longer parse and force a re-inpaint`() {
        listOf("LAMA", "LAMA16", "LAMA512", "LAMA512F").forEach { tag ->
            assertTrue(
                eu.kanade.translation.engines.inpainting.InpaintStampDecision.stampNeedsReinpaint(
                    existingStamp = "QUALITY:$tag",
                    desiredMode = InpaintingMode.QUALITY,
                    desiredModel = NeuralInpaintModel.LAMA_LITERT_GPU,
                ),
                "stamp QUALITY:$tag must not match the LiteRT route",
            )
        }
    }

    @Test
    fun `degraded neural route keeps model tag and recovery semantics`() {
        val stored = InpaintingMode.QUALITY.stampName(
            neuralModel = NeuralInpaintModel.LAMA_LITERT_GPU,
            neuralAvailable = false,
        )
        stored shouldBe "QUALITY:LAMA_LITERT_DEGRADED"
        assertFalse(
            eu.kanade.translation.engines.inpainting.InpaintStampDecision.stampNeedsReinpaint(
                existingStamp = stored,
                desiredMode = InpaintingMode.QUALITY,
                desiredModel = NeuralInpaintModel.LAMA_LITERT_GPU,
                neuralAvailable = false,
            ),
        )
        assertTrue(
            eu.kanade.translation.engines.inpainting.InpaintStampDecision.stampNeedsReinpaint(
                existingStamp = stored,
                desiredMode = InpaintingMode.QUALITY,
                desiredModel = NeuralInpaintModel.LAMA_LITERT_GPU,
                neuralAvailable = true,
            ),
        )
    }

    @Test
    fun `degraded AOT route keeps its model tag and recovery semantics`() {
        val stored = InpaintingMode.QUALITY.stampName(
            neuralModel = NeuralInpaintModel.AOT_GAN,
            neuralAvailable = false,
        )
        stored shouldBe "QUALITY:AOT_DEGRADED"
        assertFalse(
            eu.kanade.translation.engines.inpainting.InpaintStampDecision.stampNeedsReinpaint(
                existingStamp = stored,
                desiredMode = InpaintingMode.QUALITY,
                desiredModel = NeuralInpaintModel.AOT_GAN,
                neuralAvailable = false,
            ),
        )
        assertTrue(
            eu.kanade.translation.engines.inpainting.InpaintStampDecision.stampNeedsReinpaint(
                existingStamp = stored,
                desiredMode = InpaintingMode.QUALITY,
                desiredModel = NeuralInpaintModel.AOT_GAN,
                neuralAvailable = true,
            ),
        )
    }

    @Test
    fun `hardware provider override is not part of the persisted inpainting stamp`() {
        InpaintingMode.QUALITY.stampName(
            neuralModel = NeuralInpaintModel.LAMA_LITERT_GPU,
            neuralAvailable = true,
        ) shouldBe "QUALITY:LAMA_LITERT"
        assertFalse(
            eu.kanade.translation.engines.inpainting.InpaintStampDecision.stampNeedsReinpaint(
                existingStamp = "QUALITY:LAMA_LITERT",
                desiredMode = InpaintingMode.QUALITY,
                desiredModel = NeuralInpaintModel.LAMA_LITERT_GPU,
            ),
        )
    }
}
