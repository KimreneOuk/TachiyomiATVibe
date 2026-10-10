package eu.kanade.translation.engines.inpainting.aot

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.translation.NeuralInpaintModel
import java.io.File

class LamaMangaModelSelectionTest {

    @Test
    fun `fp16 selection uses only the fp16 file and has a distinct route tag`() {
        val int8Model = File("lama-manga.onnx")
        val fp16Model = File("lama-manga-fp16.onnx")

        val selected = requireNotNull(
            selectLamaMangaModel(
                neuralModel = NeuralInpaintModel.LAMA_MANGA_FP16,
                lamaMangaModelFile = int8Model,
                lamaMangaFp16ModelFile = fp16Model,
                lama512Int8ModelFile = null,
                lama512Fp16ModelFile = null,
            ),
        )
        selected.modelFile shouldBe fp16Model
        selected.routeTag shouldBe "lama_manga_fp16"

        val missingFp16 = requireNotNull(
            selectLamaMangaModel(
                neuralModel = NeuralInpaintModel.LAMA_MANGA_FP16,
                lamaMangaModelFile = int8Model,
                lamaMangaFp16ModelFile = null,
                lama512Int8ModelFile = null,
                lama512Fp16ModelFile = null,
            ),
        )
        missingFp16.modelFile shouldBe null
    }

    @Test
    fun `int8 selection stays on the recommended file`() {
        val int8Model = File("lama-manga.onnx")
        val fp16Model = File("lama-manga-fp16.onnx")

        val selected = requireNotNull(
            selectLamaMangaModel(
                neuralModel = NeuralInpaintModel.LAMA_MANGA,
                lamaMangaModelFile = int8Model,
                lamaMangaFp16ModelFile = fp16Model,
                lama512Int8ModelFile = null,
                lama512Fp16ModelFile = null,
            ),
        )
        selected.modelFile shouldBe int8Model
        selected.routeTag shouldBe "lama_manga"
    }

    @Test
    fun `LaMa 512 int8 and fp16 select only their own files and distinct route tags`() {
        val lamaManga = File("lama-manga.onnx")
        val lamaMangaFp16 = File("lama-manga-fp16.onnx")
        val int8 = File("lama-512-int8.onnx")
        val fp16 = File("lama-512-fp16.onnx")

        val int8Selection = requireNotNull(
            selectLamaMangaModel(
                neuralModel = NeuralInpaintModel.LAMA_512_INT8,
                lamaMangaModelFile = lamaManga,
                lamaMangaFp16ModelFile = lamaMangaFp16,
                lama512Int8ModelFile = int8,
                lama512Fp16ModelFile = fp16,
            ),
        )
        int8Selection.modelFile shouldBe int8
        int8Selection.routeTag shouldBe "lama_512_int8"

        val fp16Selection = requireNotNull(
            selectLamaMangaModel(
                neuralModel = NeuralInpaintModel.LAMA_512_FP16,
                lamaMangaModelFile = lamaManga,
                lamaMangaFp16ModelFile = lamaMangaFp16,
                lama512Int8ModelFile = int8,
                lama512Fp16ModelFile = fp16,
            ),
        )
        fp16Selection.modelFile shouldBe fp16
        fp16Selection.routeTag shouldBe "lama_512_fp16"

        val missingFp16 = requireNotNull(
            selectLamaMangaModel(
                neuralModel = NeuralInpaintModel.LAMA_512_FP16,
                lamaMangaModelFile = lamaManga,
                lamaMangaFp16ModelFile = lamaMangaFp16,
                lama512Int8ModelFile = int8,
                lama512Fp16ModelFile = null,
            ),
        )
        missingFp16.modelFile shouldBe null
    }
}
