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
            ),
        )
        selected.modelFile shouldBe fp16Model
        selected.routeTag shouldBe "lama_manga_fp16"

        val missingFp16 = requireNotNull(
            selectLamaMangaModel(
                neuralModel = NeuralInpaintModel.LAMA_MANGA_FP16,
                lamaMangaModelFile = int8Model,
                lamaMangaFp16ModelFile = null,
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
            ),
        )
        selected.modelFile shouldBe int8Model
        selected.routeTag shouldBe "lama_manga"
    }
}
