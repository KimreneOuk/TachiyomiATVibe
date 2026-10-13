package eu.kanade.translation.engines.inpainting

import tachiyomi.domain.translation.NeuralInpaintModel

/** Explicit region-class × backend table: bubbles and free text can evolve independently. */
enum class RegionBackend {
    /**
     * Solid median diffusion fill with feathered edge blending
     * ([eu.kanade.translation.engines.inpainting.bubble.BubbleMedianDiffusionFill])
     * — the classical path for speech bubbles in FAST and BALANCE, and the
     * fallback when QUALITY neural execution fails or the memory gate trips.
     */
    SMOOTH_DIFFUSION,

    /** OpenCV Telea fast-marching — classical free text. */
    OPENCV_TELEA,

    /**
     * Model-agnostic neural inpainting. The selected [NeuralInpaintModel]
     * selects the LiteRT tensor adapter used by the shared GPU runtime.
     */
    NEURAL,
}

data class RegionDispatch(
    val bubble: RegionBackend,
    val freeText: RegionBackend,
) {
    val usesNeural: Boolean
        get() = bubble == RegionBackend.NEURAL || freeText == RegionBackend.NEURAL
}

/** Desired mode and observed neural availability for a resume decision. */
data class InpaintStampDecision(
    val mode: InpaintingMode,
    val neuralAvailable: Boolean?,
    val neuralModel: NeuralInpaintModel = NeuralInpaintModel.AOT_GAN,
) {
    companion object {
        fun stampNeedsReinpaint(
            existingStamp: String?,
            desiredMode: InpaintingMode,
            desiredModel: NeuralInpaintModel,
            neuralAvailable: Boolean? = null,
        ): Boolean {
            if (existingStamp == null) return false
            val parsed = parseStamp(existingStamp) ?: return true
            if (parsed.mode != desiredMode) return true
            if (desiredMode == InpaintingMode.FAST) return false
            if (parsed.neuralModel != desiredModel) return true
            if (parsed.degraded) return neuralAvailable == true
            return false
        }

        private data class ParsedStamp(
            val mode: InpaintingMode,
            val neuralModel: NeuralInpaintModel,
            val degraded: Boolean,
        )

        private fun parseStamp(stamp: String): ParsedStamp? {
            val parts = stamp.split(':', limit = 2)
            val modeToken = parts[0]
            val modeWasDegraded = modeToken.endsWith(InpaintingMode.DEGRADED_SUFFIX)
            val modeName = modeToken.removeSuffix(InpaintingMode.DEGRADED_SUFFIX)
            val mode = InpaintingMode.entries.firstOrNull { it.name == modeName } ?: return null
            val modelToken = parts.getOrNull(1)
            if (mode == InpaintingMode.FAST && modelToken != null) return null
            val modelWasDegraded = modelToken?.endsWith(InpaintingMode.DEGRADED_SUFFIX) == true
            val neuralModel = when (modelToken?.removeSuffix(InpaintingMode.DEGRADED_SUFFIX)) {
                "LAMA_LITERT" -> NeuralInpaintModel.LAMA_LITERT_GPU
                // Optimized AOT via the shared LiteRT GPU runtime (FP16 asset).
                "AOT_LITERT" -> NeuralInpaintModel.AOT_GAN
                // FAST stamps legitimately carry no model tag (classical run).
                null -> if (mode == InpaintingMode.FAST) {
                    NeuralInpaintModel.AOT_GAN
                } else {
                    // "AOT" and bare BALANCE/QUALITY tokens are legacy
                    // ONNX-AOT results from the retired runtime; they no
                    // longer parse, so those cached pages re-inpaint under
                    // the optimized model instead of matching stale routes.
                    return null
                }
                // Deprecated model tags (LAMA, LAMA16, LAMA512, LAMA512F,
                // AOT) no longer parse: an unparseable stamp forces a fresh
                // inpaint with the selected model instead of matching stale
                // routes.
                else -> return null
            }
            return ParsedStamp(
                mode = mode,
                neuralModel = neuralModel,
                degraded = modeWasDegraded || modelWasDegraded,
            )
        }
    }
}

/**
 * Loop-free rule for reusing a page's effective inpainting stamp. Legacy pages
 * and clean pages are reusable. A model change always invalidates the prior
 * route; degraded pages on the same model retry only when neural availability
 * is positively known to have recovered. FAST degradation is terminal because
 * it records a classical emergency path.
 */
fun InpaintStampDecision.stampNeedsReinpaint(stored: String?): Boolean =
    InpaintStampDecision.stampNeedsReinpaint(
        existingStamp = stored,
        desiredMode = mode,
        desiredModel = neuralModel,
        neuralAvailable = neuralAvailable,
    )

/**
 * Resolve the per-class dispatch once per run from mode + session reality.
 *
 * Fast: classical everywhere — solid median fill for bubble text, OpenCV Telea
 *   for free text.
 * Balance: solid median fill for bubble text, neural (LiteRT Manga LaMa /
 *   AOT-GAN) for free text.
 * Quality: neural for both bubble text and free text.
 */
fun InpaintingMode.resolveDispatch(neuralAvailable: Boolean): RegionDispatch = when (this) {
    InpaintingMode.FAST -> RegionDispatch(RegionBackend.SMOOTH_DIFFUSION, RegionBackend.OPENCV_TELEA)
    InpaintingMode.BALANCE -> if (neuralAvailable) {
        RegionDispatch(RegionBackend.SMOOTH_DIFFUSION, RegionBackend.NEURAL)
    } else {
        RegionDispatch(RegionBackend.SMOOTH_DIFFUSION, RegionBackend.OPENCV_TELEA)
    }
    InpaintingMode.QUALITY -> if (neuralAvailable) {
        RegionDispatch(RegionBackend.NEURAL, RegionBackend.NEURAL)
    } else {
        RegionDispatch(RegionBackend.SMOOTH_DIFFUSION, RegionBackend.OPENCV_TELEA)
    }
}

/** Where one free-text group is erased. */
enum class FreeTextRoute {
    /** External neural delegate (e.g. the LiteRT Manga LaMa engine). */
    NEURAL_DELEGATE,

    /** This inpainter's own ONNX neural session ladder. */
    NEURAL_ONNX,

    /** Classical fast path (Telea/push-pull). */
    CLASSICAL,
}

/**
 * Free-text routing is decided once per group: a neural dispatch prefers the
 * external delegate when present, else the ONNX ladder when initialized, and
 * otherwise (or for FAST) falls to the classical path. Paddle-refined line
 * boxes are fed identically in every mode — only the backend differs.
 */
fun resolveFreeTextRoute(
    dispatchNeural: Boolean,
    delegateAvailable: Boolean,
    onnxInitialized: Boolean,
): FreeTextRoute = when {
    dispatchNeural && delegateAvailable -> FreeTextRoute.NEURAL_DELEGATE
    dispatchNeural && onnxInitialized -> FreeTextRoute.NEURAL_ONNX
    else -> FreeTextRoute.CLASSICAL
}
