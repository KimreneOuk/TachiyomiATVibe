package eu.kanade.translation.engines.inpainting

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class RegionDispatchTest {

    @Test
    fun `mode table maps the three modes to their spec backends`() {
        InpaintingMode.FAST.resolveDispatch(neuralAvailable = true) shouldBe
            RegionDispatch(RegionBackend.SMOOTH_DIFFUSION, RegionBackend.OPENCV_TELEA)
        InpaintingMode.FAST.resolveDispatch(neuralAvailable = false) shouldBe
            RegionDispatch(RegionBackend.SMOOTH_DIFFUSION, RegionBackend.OPENCV_TELEA)

        InpaintingMode.BALANCE.resolveDispatch(neuralAvailable = true) shouldBe
            RegionDispatch(RegionBackend.SMOOTH_DIFFUSION, RegionBackend.AOT_NEURAL)
        InpaintingMode.BALANCE.resolveDispatch(neuralAvailable = false) shouldBe
            RegionDispatch(RegionBackend.SMOOTH_DIFFUSION, RegionBackend.OPENCV_TELEA)

        InpaintingMode.QUALITY.resolveDispatch(neuralAvailable = true) shouldBe
            RegionDispatch(RegionBackend.SMOOTH_DIFFUSION, RegionBackend.AOT_NEURAL)
        InpaintingMode.QUALITY.resolveDispatch(neuralAvailable = false) shouldBe
            RegionDispatch(RegionBackend.SMOOTH_DIFFUSION, RegionBackend.OPENCV_TELEA)
    }

    @Test
    fun `usesNeural is true only when some class dispatches AOT`() {
        InpaintingMode.FAST.resolveDispatch(true).usesNeural shouldBe false
        InpaintingMode.BALANCE.resolveDispatch(true).usesNeural shouldBe true
        InpaintingMode.BALANCE.resolveDispatch(false).usesNeural shouldBe false
        InpaintingMode.QUALITY.resolveDispatch(true).usesNeural shouldBe true
    }

    @Test
    fun `all modes keep speech bubbles on clean smooth diffusion`() {
        for (mode in InpaintingMode.entries) {
            mode.resolveDispatch(neuralAvailable = true).bubble shouldBe RegionBackend.SMOOTH_DIFFUSION
            mode.resolveDispatch(neuralAvailable = false).bubble shouldBe RegionBackend.SMOOTH_DIFFUSION
        }
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
    fun `resume predicate reuses clean and legacy stamps`() {
        val decision = InpaintStampDecision(InpaintingMode.BALANCE, neuralAvailable = false)
        decision.stampNeedsReinpaint(stored = null) shouldBe false
        decision.stampNeedsReinpaint(stored = "BALANCE") shouldBe false
    }

    @Test
    fun `degraded stamp upgrades only on positive neural recovery`() {
        val degradedStored = "BALANCE_DEGRADED"
        InpaintStampDecision(InpaintingMode.BALANCE, neuralAvailable = null)
            .stampNeedsReinpaint(degradedStored) shouldBe false
        InpaintStampDecision(InpaintingMode.BALANCE, neuralAvailable = false)
            .stampNeedsReinpaint(degradedStored) shouldBe false
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
