package eu.kanade.presentation.reader.sheet

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.SearchableConfigSelectorBox
import eu.kanade.presentation.components.designsystem.DesignTokens
import eu.kanade.presentation.components.designsystem.SectionHeading
import eu.kanade.presentation.components.designsystem.SelectionTile
import eu.kanade.presentation.translation.neuralInpaintOptions
import eu.kanade.presentation.translation.ocrModelOptions
import eu.kanade.tachiyomi.ui.reader.TranslationSettingsState
import tachiyomi.domain.translation.NeuralInpaintModel
import tachiyomi.domain.translation.OcrModel
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Vision and inpainting sections of the reader translation sheet: the OCR
 * engine selector, the FAST/BALANCE/QUALITY inpainting tiles and the neural
 * model override used whenever the mode is not FAST.
 */
@Composable
internal fun TranslationVisionSection(
    state: TranslationSettingsState,
    onOcrModelChange: (OcrModel) -> Unit,
    onTranslationInpaintingModeChange: (String) -> Unit,
    onTranslationInpaintingNeuralModelChange: (NeuralInpaintModel) -> Unit,
) {
    SectionHeading(title = stringResource(ATMR.strings.reader_translation_section_vision))

    val ocrOptions = ocrModelOptions()

    Column(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            .clip(DesignTokens.ShapeCard)
            .background(DesignTokens.CardFill)
            .border(1.dp, DesignTokens.CardBorder, DesignTokens.ShapeCard)
            .padding(14.dp),
    ) {
        SearchableConfigSelectorBox(
            selectedLabel = ocrOptions.firstOrNull { it.value == state.ocrModel }?.label
                ?: state.ocrModel.name,
            options = ocrOptions,
            onSelect = onOcrModelChange,
            placeholder = stringResource(ATMR.strings.pref_ocr_model),
        )
    }

    SectionHeading(title = stringResource(ATMR.strings.reader_translation_section_inpainting))

    val currentInpaint = state.inpaintingMode.ifEmpty { "QUALITY" }
    Column(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            .clip(DesignTokens.ShapeCard)
            .background(DesignTokens.CardFill)
            .border(1.dp, DesignTokens.CardBorder, DesignTokens.ShapeCard)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SelectionTile(
                selected = currentInpaint.equals("FAST", ignoreCase = true),
                onClick = { onTranslationInpaintingModeChange("FAST") },
                title = "FAST",
                modifier = Modifier.weight(1f),
            )
            SelectionTile(
                selected = currentInpaint.equals("BALANCE", ignoreCase = true),
                onClick = { onTranslationInpaintingModeChange("BALANCE") },
                title = "BALANCE",
                modifier = Modifier.weight(1f),
            )
            SelectionTile(
                selected = currentInpaint.equals("QUALITY", ignoreCase = true),
                onClick = { onTranslationInpaintingModeChange("QUALITY") },
                title = "QUALITY",
                modifier = Modifier.weight(1f),
            )
        }

        AnimatedVisibility(
            visible = !currentInpaint.equals("FAST", ignoreCase = true),
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            val neuralOptions = neuralInpaintOptions()
            SearchableConfigSelectorBox(
                selectedLabel = neuralOptions.firstOrNull { it.value == state.inpaintingNeuralModel }?.label
                    ?: state.inpaintingNeuralModel.name,
                options = neuralOptions,
                onSelect = onTranslationInpaintingNeuralModelChange,
                placeholder = "Select Neural Model",
            )
        }
    }
}
