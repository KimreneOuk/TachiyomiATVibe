package eu.kanade.translation.engines.inpainting

import tachiyomi.domain.translation.NeuralInpaintModel

/** Explicit region-class × backend table: bubbles and free text can evolve independently. */
enum class RegionBackend {
    /**
     * AotReportBubbleFill smooth diffusion over the selected bubble mask — the
     * clean fill speech bubbles use in every mode. Bubbles must never route to
     * neural tiling (chromatic hallucination inside white interiors) or OpenCV
     * NS (blurry smudge patches).
     */
    SMOOTH_DIFFUSION,

    /** OpenCV Navier–Stokes, crop-scoped — never selected for bubbles. */
    OPENCV_NS,

    /** OpenCV Telea fast-marching — classical free text. */
    OPENCV_TELEA,

    /** AOT-GAN neural inference through the EP ladder. */
    AOT_NEURAL,
}

data class RegionDispatch(
    val bubble: RegionBackend,
    val freeText: RegionBackend,
) {
    val usesNeural: Boolean
        get() = bubble == RegionBackend.AOT_NEURAL || freeText == RegionBackend.AOT_NEURAL
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
                "LAMA" -> NeuralInpaintModel.LAMA_MANGA
                "LAMA16" -> NeuralInpaintModel.LAMA_MANGA_FP16
                "LAMA512" -> NeuralInpaintModel.LAMA_512_INT8
                "LAMA512F" -> NeuralInpaintModel.LAMA_512_FP16
                "AOT" -> NeuralInpaintModel.AOT_GAN
                null -> NeuralInpaintModel.AOT_GAN
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
 * Bubbles are mode-independent by design: every mode feeds the selected bubble
 * mask (eroded segmentation mask, dynamic pill fallback) into the clean smooth
 * diffusion fill. Only free text changes with the mode.
 */
fun InpaintingMode.resolveDispatch(neuralAvailable: Boolean): RegionDispatch = when (this) {
    InpaintingMode.FAST -> RegionDispatch(RegionBackend.SMOOTH_DIFFUSION, RegionBackend.OPENCV_TELEA)
    InpaintingMode.BALANCE -> if (neuralAvailable) {
        RegionDispatch(RegionBackend.SMOOTH_DIFFUSION, RegionBackend.AOT_NEURAL)
    } else {
        RegionDispatch(RegionBackend.SMOOTH_DIFFUSION, RegionBackend.OPENCV_TELEA)
    }
    InpaintingMode.QUALITY -> if (neuralAvailable) {
        RegionDispatch(RegionBackend.SMOOTH_DIFFUSION, RegionBackend.AOT_NEURAL)
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
