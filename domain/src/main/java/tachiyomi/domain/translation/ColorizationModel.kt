package tachiyomi.domain.translation

/**
 * On-device colorization engines. MANGA_COLORIZATION_V2 is the deterministic
 * ONNX generator (CPU runtime); SANA_V2 is the diffusion ladder whose LiteRT
 * assets are not provisioned yet.
 */
enum class ColorizationModel(val prefValue: String) {
    MANGA_COLORIZATION_V2("MANGA_COLORIZATION_V2"),
    SANA_V2("SANA_V2"),
    ;

    companion object {
        val DEFAULT = MANGA_COLORIZATION_V2

        fun fromPrefOrNull(value: String?): ColorizationModel? =
            entries.firstOrNull { it.prefValue.equals(value, ignoreCase = true) }
    }
}
