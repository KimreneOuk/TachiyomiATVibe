package eu.kanade.translation.engines.inpainting

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.translation.NeuralInpaintModel

class RegionDispatchTest {

    @Test
    fun `mode table maps the three modes to their spec backends`() {
        InpaintingMode.FAST.resolveDispatch(neuralAvailable = true) shouldBe
            RegionDispatch(RegionBackend.SMOOTH_DIFFUSION, RegionBackend.OPENCV_TELEA)
        InpaintingMode.FAST.resolveDispatch(neuralAvailable = false) shouldBe
            RegionDispatch(RegionBackend.SMOOTH_DIFFUSION, RegionBackend.OPENCV_TELEA)

        InpaintingMode.BALANCE.resolveDispatch(neuralAvailable = true) shouldBe
            RegionDispatch(RegionBackend.SMOOTH_DIFFUSION, RegionBackend.NEURAL)
        InpaintingMode.BALANCE.resolveDispatch(neuralAvailable = false) shouldBe
            RegionDispatch(RegionBackend.SMOOTH_DIFFUSION, RegionBackend.OPENCV_TELEA)

        InpaintingMode.QUALITY.resolveDispatch(neuralAvailable = true) shouldBe
            RegionDispatch(RegionBackend.NEURAL, RegionBackend.NEURAL)
        InpaintingMode.QUALITY.resolveDispatch(neuralAvailable = false) shouldBe
            RegionDispatch(RegionBackend.SMOOTH_DIFFUSION, RegionBackend.OPENCV_TELEA)
    }

    @Test
    fun `classical bubbles always use the solid median fill and free text never does`() {
        InpaintingMode.entries.forEach { mode ->
            listOf(true, false).forEach { neuralAvailable ->
                val dispatch = mode.resolveDispatch(neuralAvailable)
                setOf(RegionBackend.SMOOTH_DIFFUSION, RegionBackend.NEURAL).contains(dispatch.bubble) shouldBe true
                dispatch.freeText shouldBe if (dispatch.freeText == RegionBackend.NEURAL) {
                    RegionBackend.NEURAL
                } else {
                    RegionBackend.OPENCV_TELEA
                }
            }
        }
    }

    @Test
    fun `usesNeural is true only when some class dispatches neural`() {
        InpaintingMode.FAST.resolveDispatch(true).usesNeural shouldBe false
        InpaintingMode.BALANCE.resolveDispatch(true).usesNeural shouldBe true
        InpaintingMode.BALANCE.resolveDispatch(false).usesNeural shouldBe false
        InpaintingMode.QUALITY.resolveDispatch(true).usesNeural shouldBe true
    }

    @Test
    fun `QUALITY dispatches bubbles to neural only when sessions exist`() {
        InpaintingMode.QUALITY.resolveDispatch(neuralAvailable = true).bubble shouldBe RegionBackend.NEURAL
        InpaintingMode.QUALITY.resolveDispatch(neuralAvailable = false).bubble shouldBe RegionBackend.SMOOTH_DIFFUSION
    }

    @Test
    fun `free text route prefers the delegate, then onnx, then classical`() {
        resolveFreeTextRoute(dispatchNeural = true, delegateAvailable = true, onnxInitialized = false) shouldBe
            FreeTextRoute.NEURAL_DELEGATE
        resolveFreeTextRoute(dispatchNeural = true, delegateAvailable = true, onnxInitialized = true) shouldBe
            FreeTextRoute.NEURAL_DELEGATE
        resolveFreeTextRoute(dispatchNeural = true, delegateAvailable = false, onnxInitialized = true) shouldBe
            FreeTextRoute.NEURAL_ONNX
        resolveFreeTextRoute(dispatchNeural = true, delegateAvailable = false, onnxInitialized = false) shouldBe
            FreeTextRoute.CLASSICAL
        resolveFreeTextRoute(dispatchNeural = false, delegateAvailable = true, onnxInitialized = true) shouldBe
            FreeTextRoute.CLASSICAL
    }

    @Test
    fun `bare stamps predate model tags and force a re-inpaint`() {
        val decision = InpaintStampDecision(InpaintingMode.BALANCE, neuralAvailable = false)
        decision.stampNeedsReinpaint(stored = null) shouldBe false
        // Bare "BALANCE" is a legacy ONNX-era stamp with no model tag: it no
        // longer matches any selectable model route and must re-inpaint.
        decision.stampNeedsReinpaint(stored = "BALANCE") shouldBe true
    }

    @Test
    fun `bare degraded stamps are legacy and always re-inpaint`() {
        val degradedStored = "BALANCE_DEGRADED"
        InpaintStampDecision(InpaintingMode.BALANCE, neuralAvailable = null)
            .stampNeedsReinpaint(degradedStored) shouldBe true
        InpaintStampDecision(InpaintingMode.BALANCE, neuralAvailable = false)
            .stampNeedsReinpaint(degradedStored) shouldBe true
        InpaintStampDecision(InpaintingMode.BALANCE, neuralAvailable = true)
            .stampNeedsReinpaint(degradedStored) shouldBe true
    }

    @Test
    fun `mode switch or junk stamp re-inpaints and FAST emergency never does`() {
        InpaintStampDecision(InpaintingMode.BALANCE, neuralAvailable = true)
            .stampNeedsReinpaint("QUALITY") shouldBe true
        InpaintStampDecision(InpaintingMode.FAST, neuralAvailable = null)
            .stampNeedsReinpaint("FAST_DEGRADED") shouldBe false
        InpaintStampDecision(InpaintingMode.BALANCE, neuralAvailable = false)
            .stampNeedsReinpaint("junk") shouldBe true
    }

    @Test
    fun `stamp parser recognizes the LiteRT model tags`() {
        InpaintStampDecision(InpaintingMode.BALANCE, neuralAvailable = null, neuralModel = NeuralInpaintModel.LAMA_LITERT_GPU)
            .stampNeedsReinpaint("BALANCE:LAMA_LITERT") shouldBe false
        InpaintStampDecision(
            InpaintingMode.QUALITY,
            neuralAvailable = false,
            neuralModel = NeuralInpaintModel.LAMA_LITERT_GPU,
        ).stampNeedsReinpaint("QUALITY:LAMA_LITERT_DEGRADED") shouldBe false
        InpaintStampDecision(
            InpaintingMode.QUALITY,
            neuralAvailable = true,
            neuralModel = NeuralInpaintModel.LAMA_LITERT_GPU,
        ).stampNeedsReinpaint("QUALITY:LAMA_LITERT_DEGRADED") shouldBe true
        // Optimized AOT carries its own AOT_LITERT token (LiteRT GPU runtime).
        InpaintStampDecision(
            InpaintingMode.QUALITY,
            neuralAvailable = null,
            neuralModel = NeuralInpaintModel.AOT_GAN,
        ).stampNeedsReinpaint("QUALITY:AOT_LITERT") shouldBe false
        InpaintStampDecision(
            InpaintingMode.BALANCE,
            neuralAvailable = false,
            neuralModel = NeuralInpaintModel.AOT_GAN,
        ).stampNeedsReinpaint("BALANCE:AOT_LITERT_DEGRADED") shouldBe false
    }

    @Test
    fun `deprecated model stamps no longer parse and force a re-inpaint`() {
        InpaintStampDecision(InpaintingMode.QUALITY, neuralAvailable = null, neuralModel = NeuralInpaintModel.LAMA_LITERT_GPU)
            .stampNeedsReinpaint("QUALITY:LAMA") shouldBe true
        InpaintStampDecision(InpaintingMode.QUALITY, neuralAvailable = null, neuralModel = NeuralInpaintModel.AOT_GAN)
            .stampNeedsReinpaint("QUALITY:LAMA512F") shouldBe true
        // "AOT" is the retired ONNX-AOT runtime's token: cached pages stamped
        // with it must re-inpaint under the optimized LiteRT AOT model.
        InpaintStampDecision(InpaintingMode.QUALITY, neuralAvailable = null, neuralModel = NeuralInpaintModel.AOT_GAN)
            .stampNeedsReinpaint("QUALITY:AOT") shouldBe true
        InpaintStampDecision(InpaintingMode.BALANCE, neuralAvailable = true, neuralModel = NeuralInpaintModel.AOT_GAN)
            .stampNeedsReinpaint("BALANCE:AOT_DEGRADED") shouldBe true
    }

    @Test
    fun `pref parser accepts all three values and maps unknown to FAST`() {
        InpaintingMode.fromPref("FAST") shouldBe InpaintingMode.FAST
        InpaintingMode.fromPref("BALANCE") shouldBe InpaintingMode.BALANCE
        InpaintingMode.fromPref("QUALITY") shouldBe InpaintingMode.QUALITY
        InpaintingMode.fromPref("garbage") shouldBe InpaintingMode.FAST
        InpaintingMode.fromPref(null) shouldBe InpaintingMode.FAST
    }

    @Test
    fun `only FAST skips neural session init`() {
        InpaintingMode.FAST.initializesNeuralSessions shouldBe false
        InpaintingMode.BALANCE.initializesNeuralSessions shouldBe true
        InpaintingMode.QUALITY.initializesNeuralSessions shouldBe true
    }

    @Test
    fun `stamp name degrades only for neural modes without sessions`() {
        InpaintingMode.FAST.stampName(neuralAvailable = false) shouldBe "FAST"
        InpaintingMode.BALANCE.stampName(neuralAvailable = true) shouldBe "BALANCE"
        InpaintingMode.BALANCE.stampName(neuralAvailable = false) shouldBe "BALANCE_DEGRADED"
        InpaintingMode.QUALITY.stampName(neuralAvailable = false) shouldBe "QUALITY_DEGRADED"
    }
}
