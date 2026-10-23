package eu.kanade.presentation.reader.sheet

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.presentation.components.designsystem.DesignTokens
import eu.kanade.presentation.components.designsystem.SheetHeader
import eu.kanade.tachiyomi.ui.reader.TranslationSettingsState
import tachiyomi.domain.translation.AiEngine
import tachiyomi.domain.translation.NeuralInpaintModel
import tachiyomi.domain.translation.OcrModel
import tachiyomi.domain.translation.StandardEngine
import tachiyomi.domain.translation.TranslationEngineCategory
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Reader translation bottom sheet: single scrolling surface composing the
 * activation, languages, translator, vision and reset sections. Accessed via
 * the translate affordance on the reader dock or the floater menu.
 */
@Composable
fun ReaderTranslationSheet(
    state: TranslationSettingsState,
    onDismissRequest: () -> Unit,
    onShowMenus: () -> Unit,
    onHideMenus: () -> Unit,
    onTranslationEnabledChange: (Boolean) -> Unit,
    onAutoTranslateChange: (Boolean) -> Unit,
    onFloaterEnabledChange: (Boolean) -> Unit,
    onFloaterIdleFadeChange: (Boolean) -> Unit,
    onShowTranslatedTextChange: (Boolean) -> Unit,
    onTranslateButtonWithControlsChange: (Boolean) -> Unit,
    onResetFloaterPosition: () -> Unit,
    onTranslateFromLanguageChange: (String) -> Unit,
    onTranslateToLanguageChange: (String) -> Unit,
    onOcrModelChange: (OcrModel) -> Unit,
    onTranslationInpaintingModeChange: (String) -> Unit,
    onTranslationInpaintingNeuralModelChange: (NeuralInpaintModel) -> Unit,
    onTranslationEngineCategoryChange: (TranslationEngineCategory) -> Unit,
    onTranslationStandardEngineChange: (StandardEngine) -> Unit,
    onTranslationDeeplApiKeyChange: (String) -> Unit,
    onTranslationAiEngineChange: (AiEngine) -> Unit,
    onTranslationAiApiKeyChange: (String) -> Unit,
    onTranslationAiBaseUrlChange: (String) -> Unit,
    onTranslationAiModelChange: (String) -> Unit,
    onFetchAiModels: () -> Unit,
    onResetTranslationData: (Boolean) -> Unit,
    onResetInpaintData: () -> Unit,
    onResetOcrData: () -> Unit,
    onResetEverything: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AdaptiveSheet(
        forceSheetMode = true,
        onDismissRequest = {
            onDismissRequest()
            onShowMenus()
        },
    ) {
        Surface(
            color = DesignTokens.SheetBackground,
            shape = DesignTokens.ShapeSheet,
            modifier = modifier
                .fillMaxWidth()
                .fillMaxHeight(0.68f),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                SheetHeader(
                    icon = Icons.Outlined.Translate,
                    title = stringResource(ATMR.strings.reader_translation_sheet_title),
                    subtitle = stringResource(ATMR.strings.reader_translation_sheet_subtitle),
                    onClose = onDismissRequest,
                )

                Spacer(modifier = Modifier.height(4.dp))

                TranslationActivationSection(
                    state = state,
                    onTranslationEnabledChange = onTranslationEnabledChange,
                    onAutoTranslateChange = onAutoTranslateChange,
                    onFloaterEnabledChange = onFloaterEnabledChange,
                    onFloaterIdleFadeChange = onFloaterIdleFadeChange,
                    onShowTranslatedTextChange = onShowTranslatedTextChange,
                    onTranslateButtonWithControlsChange = onTranslateButtonWithControlsChange,
                    onResetFloaterPosition = onResetFloaterPosition,
                )

                TranslationLanguageSection(
                    state = state,
                    onTranslateFromLanguageChange = onTranslateFromLanguageChange,
                    onTranslateToLanguageChange = onTranslateToLanguageChange,
                )

                TranslationEngineSection(
                    state = state,
                    onTranslationEngineCategoryChange = onTranslationEngineCategoryChange,
                    onTranslationStandardEngineChange = onTranslationStandardEngineChange,
                    onTranslationDeeplApiKeyChange = onTranslationDeeplApiKeyChange,
                    onTranslationAiEngineChange = onTranslationAiEngineChange,
                    onTranslationAiApiKeyChange = onTranslationAiApiKeyChange,
                    onTranslationAiBaseUrlChange = onTranslationAiBaseUrlChange,
                    onTranslationAiModelChange = onTranslationAiModelChange,
                    onFetchAiModels = onFetchAiModels,
                )

                TranslationVisionSection(
                    state = state,
                    onOcrModelChange = onOcrModelChange,
                    onTranslationInpaintingModeChange = onTranslationInpaintingModeChange,
                    onTranslationInpaintingNeuralModelChange = onTranslationInpaintingNeuralModelChange,
                )

                TranslationResetSection(
                    onResetTranslationData = onResetTranslationData,
                    onResetInpaintData = onResetInpaintData,
                    onResetOcrData = onResetOcrData,
                    onResetEverything = onResetEverything,
                )
            }
        }
    }
}
