package eu.kanade.presentation.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.presentation.more.settings.widget.SearchableListPreferenceWidget
import eu.kanade.presentation.more.settings.widget.SwitchPreferenceWidget
import eu.kanade.presentation.reader.settings.SheetSection
import eu.kanade.tachiyomi.ui.reader.TranslationSettingsState
import eu.kanade.translation.model.TextRecognizerLanguage
import eu.kanade.translation.model.TextTranslatorLanguage
import eu.kanade.translation.model.TranslationProgressSnapshot
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableMap
import tachiyomi.domain.translation.AiEngine
import tachiyomi.domain.translation.NeuralInpaintModel
import tachiyomi.domain.translation.OcrModel
import tachiyomi.domain.translation.PaddleOcrComputeDevice
import tachiyomi.domain.translation.StandardEngine
import tachiyomi.domain.translation.TranslationEngineCategory
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Reader translation settings sheet (UX prototype).
 *
 * Primary product configuration sits at the top without any expanding: enable,
 * auto-translate, languages, engine family, the concrete engine/provider and
 * its model/credentials. Technical groups (OCR, inpainting), live activity and
 * destructive data management are separate collapsible sections below.
 *
 * All callbacks dispatch the same view-model actions as before; only the
 * presentation hierarchy changed.
 */
@Composable
fun TranslationSettingsSheet(
    state: TranslationSettingsState,
    onDismissRequest: () -> Unit,
    onStopAllTranslation: () -> Unit,
    onTranslationEnabledChange: (Boolean) -> Unit,
    onAutoTranslateChange: (Boolean) -> Unit,
    onTranslateFromLanguageChange: (String) -> Unit,
    onTranslateToLanguageChange: (String) -> Unit,
    onOcrModelChange: (OcrModel) -> Unit,
    onPaddleOcrComputeDeviceOverrideChange: (PaddleOcrComputeDevice?) -> Unit,
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
    onDeleteChapterTranslation: () -> Unit,
    queue: ImmutableList<eu.kanade.tachiyomi.ui.reader.ReaderViewModel.QueuedPageInfo> = persistentListOf(),
    translationProgress: Pair<Int, Int> = Pair(0, 0),
    translationCurrentPage: Int = 0,
    translationBatchProgress: TranslationProgressSnapshot? = null,
    onRetryBatch: (() -> Unit)? = null,
) {
    AdaptiveSheet(onDismissRequest = onDismissRequest) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(vertical = MaterialTheme.padding.medium),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MaterialTheme.padding.medium),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(ATMR.strings.reader_compare_open_settings),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
            }

            QuickControlsSection(
                state = state,
                onTranslationEnabledChange = onTranslationEnabledChange,
                onAutoTranslateChange = onAutoTranslateChange,
                onTranslateFromLanguageChange = onTranslateFromLanguageChange,
                onTranslateToLanguageChange = onTranslateToLanguageChange,
                onTranslationEngineCategoryChange = onTranslationEngineCategoryChange,
                onTranslationStandardEngineChange = onTranslationStandardEngineChange,
                onTranslationDeeplApiKeyChange = onTranslationDeeplApiKeyChange,
                onTranslationAiEngineChange = onTranslationAiEngineChange,
                onTranslationAiApiKeyChange = onTranslationAiApiKeyChange,
                onTranslationAiBaseUrlChange = onTranslationAiBaseUrlChange,
                onTranslationAiModelChange = onTranslationAiModelChange,
                onFetchAiModels = onFetchAiModels,
            )

            SheetSection(
                title = stringResource(ATMR.strings.translation_section_ocr),
            ) {
                OcrSection(
                    state = state,
                    onOcrModelChange = onOcrModelChange,
                    onPaddleOcrComputeDeviceOverrideChange = onPaddleOcrComputeDeviceOverrideChange,
                )
            }

            SheetSection(
                title = stringResource(ATMR.strings.translation_section_inpainting),
            ) {
                InpaintSection(
                    inpaintingMode = state.inpaintingMode,
                    neuralModel = state.inpaintingNeuralModel,
                    onTranslationInpaintingModeChange = onTranslationInpaintingModeChange,
                    onTranslationInpaintingNeuralModelChange = onTranslationInpaintingNeuralModelChange,
                )
            }

            SheetSection(
                title = stringResource(ATMR.strings.translation_section_activity),
            ) {
                ActivitySection(
                    queue = queue,
                    translationProgress = translationProgress,
                    translationCurrentPage = translationCurrentPage,
                    translationBatchProgress = translationBatchProgress,
                    onRetryBatch = onRetryBatch,
                    onStopAllTranslation = onStopAllTranslation,
                )
            }

            SheetSection(
                title = stringResource(ATMR.strings.translation_section_data_management),
            ) {
                DataManagementSection(
                    onResetTranslationData = onResetTranslationData,
                    onResetInpaintData = onResetInpaintData,
                    onResetOcrData = onResetOcrData,
                    onResetEverything = onResetEverything,
                    onDeleteChapterTranslation = onDeleteChapterTranslation,
                )
            }
        }
    }
}

/**
 * Primary translation configuration. Engine selection is a first-class task
 * here, no longer tucked behind an Advanced expander.
 */
@Composable
private fun ColumnScope.QuickControlsSection(
    state: TranslationSettingsState,
    onTranslationEnabledChange: (Boolean) -> Unit,
    onAutoTranslateChange: (Boolean) -> Unit,
    onTranslateFromLanguageChange: (String) -> Unit,
    onTranslateToLanguageChange: (String) -> Unit,
    onTranslationEngineCategoryChange: (TranslationEngineCategory) -> Unit,
    onTranslationStandardEngineChange: (StandardEngine) -> Unit,
    onTranslationDeeplApiKeyChange: (String) -> Unit,
    onTranslationAiEngineChange: (AiEngine) -> Unit,
    onTranslationAiApiKeyChange: (String) -> Unit,
    onTranslationAiBaseUrlChange: (String) -> Unit,
    onTranslationAiModelChange: (String) -> Unit,
    onFetchAiModels: () -> Unit,
) {
    SwitchPreferenceWidget(
        title = stringResource(ATMR.strings.pref_translation_enabled),
        checked = state.enabled,
        onCheckedChanged = onTranslationEnabledChange,
    )
    if (state.enabled) {
        SwitchPreferenceWidget(
            title = stringResource(ATMR.strings.pref_auto_translate),
            subtitle = stringResource(ATMR.strings.pref_auto_translate_summary),
            checked = state.autoTranslate,
            onCheckedChanged = onAutoTranslateChange,
        )
    }

    val fromLangs = remember {
        TextRecognizerLanguage.entries.associate { it.name to it.label }.toImmutableMap()
    }
    val toLangs = remember {
        TextTranslatorLanguage.entries.associate { it.name to it.label }.toImmutableMap()
    }
    SearchableListPreferenceWidget(
        value = state.translateFromLanguage,
        title = stringResource(ATMR.strings.pref_translate_from),
        subtitle = fromLangs[state.translateFromLanguage],
        icon = null,
        entries = fromLangs,
        recentItems = state.translationRecentLanguagesFrom,
        onValueChange = onTranslateFromLanguageChange,
    )
    SearchableListPreferenceWidget(
        value = state.translateToLanguage,
        title = stringResource(ATMR.strings.pref_translate_to),
        subtitle = toLangs[state.translateToLanguage],
        icon = null,
        entries = toLangs,
        recentItems = state.translationRecentLanguagesTo,
        onValueChange = onTranslateToLanguageChange,
    )

    EngineRows(
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
}
