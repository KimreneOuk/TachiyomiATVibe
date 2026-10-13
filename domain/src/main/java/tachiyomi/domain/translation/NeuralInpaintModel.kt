package tachiyomi.domain.translation

enum class NeuralInpaintModel(val prefValue: String) {
    LAMA_LITERT_GPU("LAMA_LITERT_GPU"),
    AOT_GAN("AOT_GAN");

    companion object {
        val DEFAULT = LAMA_LITERT_GPU

        fun fromPrefOrNull(value: String?): NeuralInpaintModel? =
            entries.firstOrNull { it.prefValue.equals(value, ignoreCase = true) }
    }
}
