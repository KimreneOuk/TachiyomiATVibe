package eu.kanade.translation.engines.inpainting

import tachiyomi.domain.translation.NeuralInpaintModel

/** Explicit region-class × backend table: bubbles and free text can evolve independently. */
enum class RegionBackend {
    /** OpenCV Navier–Stokes, crop-scoped — bubbles. */
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

/** Resolve the per-class dispatch once per run from mode + session reality. */
fun InpaintingMode.resolveDispatch(neuralAvailable: Boolean): RegionDispatch = when (this) {
    InpaintingMode.FAST -> RegionDispatch(RegionBackend.OPENCV_NS, RegionBackend.OPENCV_TELEA)
    InpaintingMode.BALANCE -> if (neuralAvailable) {
        RegionDispatch(RegionBackend.OPENCV_NS, RegionBackend.AOT_NEURAL)
    } else {
        RegionDispatch(RegionBackend.OPENCV_NS, RegionBackend.OPENCV_TELEA)
    }
    InpaintingMode.QUALITY -> if (neuralAvailable) {
        RegionDispatch(RegionBackend.AOT_NEURAL, RegionBackend.AOT_NEURAL)
    } else {
        RegionDispatch(RegionBackend.OPENCV_NS, RegionBackend.OPENCV_TELEA)
    }
}
