package tachiyomi.domain.translation

enum class NeuralInpaintModel(val prefValue: String) {
    LAMA_MANGA("LAMA_MANGA"),
    LAMA_MANGA_FP16("LAMA_MANGA_FP16"),
    LAMA_512_INT8("LAMA_512_INT8"),
    LAMA_512_FP16("LAMA_512_FP16"),
    AOT_GAN("AOT_GAN");

    companion object {
        val DEFAULT = LAMA_MANGA

        fun fromPrefOrNull(value: String?): NeuralInpaintModel? =
            entries.firstOrNull { it.prefValue.equals(value, ignoreCase = true) }
    }
}
