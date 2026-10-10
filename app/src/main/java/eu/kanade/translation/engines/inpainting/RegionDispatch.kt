package eu.kanade.translation.engines.inpainting

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
)

/**
 * Loop-free rule for reusing a page's effective inpainting stamp. Legacy pages
 * and clean pages are reusable. Degraded neural pages retry only when neural
 * availability is positively known to have recovered; FAST degradation is
 * terminal because it records a classical emergency path.
 */
fun InpaintStampDecision.stampNeedsReinpaint(stored: String?): Boolean = when {
    stored == null -> false
    stored == mode.name -> false
    stored == mode.name + InpaintingMode.DEGRADED_SUFFIX ->
        mode != InpaintingMode.FAST && neuralAvailable == true
    else -> true
}

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
