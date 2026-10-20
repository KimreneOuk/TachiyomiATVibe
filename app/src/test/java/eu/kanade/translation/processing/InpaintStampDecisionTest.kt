package eu.kanade.translation.processing

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
                existingStamp = "QUALITY:AOT_LITERT",
                desiredMode = InpaintingMode.QUALITY,
                desiredModel = NeuralInpaintModel.AOT_GAN,
            ),
        )
    }

    @Test
    fun `legacy ONNX-AOT stamps invalidate under the optimized LiteRT AOT`() {
        // "AOT" was produced by the retired ONNX runtime; the optimized FP16
        // LiteRT AOT must re-inpaint those cached pages (fresh stamp token).
        assertTrue(
            eu.kanade.translation.engines.inpainting.InpaintStampDecision.stampNeedsReinpaint(
                existingStamp = "QUALITY:AOT",
                desiredMode = InpaintingMode.QUALITY,
                desiredModel = NeuralInpaintModel.AOT_GAN,
            ),
        )
        assertTrue(
            eu.kanade.translation.engines.inpainting.InpaintStampDecision.stampNeedsReinpaint(
                existingStamp = "BALANCE:AOT_DEGRADED",
                desiredMode = InpaintingMode.BALANCE,
                desiredModel = NeuralInpaintModel.AOT_GAN,
                neuralAvailable = true,
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
                existingStamp = "QUALITY:AOT_LITERT",
                desiredMode = InpaintingMode.QUALITY,
                desiredModel = NeuralInpaintModel.LAMA_LITERT_GPU,
            ),
        )
    }

    @Test
    fun `bare legacy stamps predate model tags and invalidate for both models`() {
        assertTrue(
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
    fun `bare legacy degraded stamps always re-inpaint`() {
        // "QUALITY_DEGRADED" is a bare ONNX-era stamp with no model tag: it no
        // longer matches any selectable route regardless of availability.
        eu.kanade.translation.engines.inpainting.InpaintStampDecision.stampNeedsReinpaint(
            existingStamp = "QUALITY_DEGRADED",
            desiredMode = InpaintingMode.QUALITY,
            desiredModel = NeuralInpaintModel.AOT_GAN,
            neuralAvailable = false,
        ) shouldBe true
        eu.kanade.translation.engines.inpainting.InpaintStampDecision.stampNeedsReinpaint(
            existingStamp = "QUALITY_DEGRADED",
            desiredMode = InpaintingMode.QUALITY,
            desiredModel = NeuralInpaintModel.AOT_GAN,
            neuralAvailable = true,
        ) shouldBe true
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
        ) shouldBe "BALANCE:AOT_LITERT"
    }

    @Test
    fun `deprecated model tags no longer parse and force a re-inpaint`() {
        listOf("LAMA", "LAMA16", "LAMA512", "LAMA512F", "AOT").forEach { tag ->
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
        stored shouldBe "QUALITY:AOT_LITERT_DEGRADED"
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
