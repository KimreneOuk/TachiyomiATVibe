package tachiyomi.domain.translation

enum class NeuralInpaintModel(val prefValue: String) {
    LAMA_MANGA("LAMA_MANGA"),
    AOT_GAN("AOT_GAN");

    companion object {
        val DEFAULT = LAMA_MANGA

        fun fromPrefOrNull(value: String?): NeuralInpaintModel? =
            entries.firstOrNull { it.prefValue.equals(value, ignoreCase = true) }
    }
}
