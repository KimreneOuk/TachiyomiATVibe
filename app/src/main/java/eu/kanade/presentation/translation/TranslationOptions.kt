package eu.kanade.presentation.translation

import androidx.compose.runtime.Composable
import eu.kanade.presentation.components.SelectorOption
import eu.kanade.translation.engines.translator.AiTranslatorKind
import eu.kanade.translation.engines.translator.StandardTranslatorKind
import tachiyomi.domain.translation.AiEngine
import tachiyomi.domain.translation.NeuralInpaintModel
import tachiyomi.domain.translation.OcrModel
import tachiyomi.domain.translation.StandardEngine
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource

/** Option lists shared by the reader translation sheet and the batch dialog. */

internal val standardEngineOptions: List<SelectorOption<StandardEngine>> =
    StandardTranslatorKind.entries.map { kind ->
        SelectorOption(
            value = StandardEngine.valueOf(kind.name),
            label = kind.label,
        )
    }

internal val aiProviderOptions: List<SelectorOption<AiEngine>> =
    AiTranslatorKind.entries.map { kind ->
        SelectorOption(
            value = kind.engine,
            label = kind.label,
        )
    }

@Composable
internal fun ocrModelOptions(): List<SelectorOption<OcrModel>> = listOf(
    SelectorOption(
        value = OcrModel.PADDLEOCR_V6_SMALL,
        label = "PaddleOCR v6",
        subtitle = stringResource(ATMR.strings.reader_translation_ocr_paddle_summary),
    ),
    SelectorOption(
        value = OcrModel.MANGAOCR,
        label = "Manga-OCR",
        subtitle = stringResource(ATMR.strings.reader_translation_ocr_mangaocr_summary),
    ),
    SelectorOption(
        value = OcrModel.MLKIT,
        label = "Google MLKit",
        subtitle = stringResource(ATMR.strings.reader_translation_ocr_mlkit_summary),
    ),
)

@Composable
internal fun neuralInpaintOptions(): List<SelectorOption<NeuralInpaintModel>> = listOf(
    SelectorOption(NeuralInpaintModel.LAMA_LITERT_GPU, "Manga LaMa", "LiteRT GPU Accelerated"),
    SelectorOption(NeuralInpaintModel.AOT_GAN, "AOT-GAN", "512px High Speed"),
)
