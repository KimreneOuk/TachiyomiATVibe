package eu.kanade.translation.engines.inpainting.aot

import tachiyomi.domain.translation.NeuralInpaintModel
import java.io.File

internal data class LamaMangaModelSelection(
    val modelFile: File?,
    val routeTag: String,
)

/** Select exactly the stored LaMa variant named by the preference. */
internal fun selectLamaMangaModel(
    neuralModel: NeuralInpaintModel,
    lamaMangaModelFile: File?,
    lamaMangaFp16ModelFile: File?,
): LamaMangaModelSelection? = when (neuralModel) {
    NeuralInpaintModel.LAMA_MANGA -> LamaMangaModelSelection(lamaMangaModelFile, "lama_manga")
    NeuralInpaintModel.LAMA_MANGA_FP16 -> LamaMangaModelSelection(lamaMangaFp16ModelFile, "lama_manga_fp16")
    NeuralInpaintModel.AOT_GAN -> null
}
