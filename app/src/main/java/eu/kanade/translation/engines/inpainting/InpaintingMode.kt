package eu.kanade.translation.engines.inpainting

import tachiyomi.domain.translation.NeuralInpaintModel

enum class InpaintingMode {
    FAST,
    BALANCE,
    QUALITY,
    ;

    /** True when this mode asks the recognition engine to load AOT sessions. */
    val initializesNeuralSessions: Boolean
        get() = this != FAST

    companion object {
        const val DEGRADED_SUFFIX = "_DEGRADED"

        /** Unknown and legacy values map to FAST so stale prefs never select a mode that can hard-fail. */
        fun fromPref(value: String?): InpaintingMode = when (value) {
            "BALANCE" -> BALANCE
            "QUALITY" -> QUALITY
            "FAST" -> FAST
            else -> FAST
        }
    }
}

/** Neural modes with unavailable sessions are stamped as degraded for honest persistence. */
fun InpaintingMode.stampName(neuralAvailable: Boolean): String =
    if (initializesNeuralSessions && !neuralAvailable) name + InpaintingMode.DEGRADED_SUFFIX else name

/** Persists the selected neural route while preserving FAST's classical-only stamp. */
fun InpaintingMode.stampName(
    neuralModel: NeuralInpaintModel,
    neuralAvailable: Boolean,
    degraded: Boolean = false,
): String {
    if (this == InpaintingMode.FAST) {
        return if (degraded) name + InpaintingMode.DEGRADED_SUFFIX else name
    }
    val modelTag = when (neuralModel) {
        NeuralInpaintModel.LAMA_MANGA -> "LAMA"
        NeuralInpaintModel.LAMA_MANGA_FP16 -> "LAMA16"
        NeuralInpaintModel.AOT_GAN -> "AOT"
    }
    val degradedTag = if (degraded || !neuralAvailable) InpaintingMode.DEGRADED_SUFFIX else ""
    return "$name:$modelTag$degradedTag"
}
