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
    lama512Int8ModelFile: File? = null,
    lama512Fp16ModelFile: File? = null,
): LamaMangaModelSelection? = when (neuralModel) {
    NeuralInpaintModel.LAMA_MANGA -> LamaMangaModelSelection(lamaMangaModelFile, "lama_manga")
    NeuralInpaintModel.LAMA_MANGA_FP16 -> LamaMangaModelSelection(lamaMangaFp16ModelFile, "lama_manga_fp16")
    NeuralInpaintModel.LAMA_512_INT8 -> LamaMangaModelSelection(lama512Int8ModelFile, "lama_512_int8")
    NeuralInpaintModel.LAMA_512_FP16 -> LamaMangaModelSelection(lama512Fp16ModelFile, "lama_512_fp16")
    NeuralInpaintModel.AOT_GAN,
    NeuralInpaintModel.LAMA_LITERT_GPU,
    -> null
}
