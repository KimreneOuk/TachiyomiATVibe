package eu.kanade.translation.engines.inpainting

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
