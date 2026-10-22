package eu.kanade.presentation.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.presentation.more.settings.widget.SearchableListPreferenceWidget
import eu.kanade.presentation.more.settings.widget.SwitchPreferenceWidget
import eu.kanade.presentation.reader.settings.OptionCardRow
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import eu.kanade.tachiyomi.ui.reader.TranslationSettingsState
import eu.kanade.translation.engines.translator.AiTranslatorKind
import eu.kanade.translation.engines.translator.StandardTranslatorKind
import eu.kanade.translation.model.TextRecognizerLanguage
import eu.kanade.translation.model.TextTranslatorLanguage
import eu.kanade.translation.model.TranslationProgressSnapshot
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableMap
import tachiyomi.domain.translation.AiEngine
import tachiyomi.domain.translation.NeuralInpaintModel
import tachiyomi.domain.translation.OcrModel
import tachiyomi.domain.translation.StandardEngine
import tachiyomi.domain.translation.TranslationEngineCategory
import tachiyomi.i18n.MR
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Modern single-surface reader translation settings sheet.
 * Consolidates the five primary controls (Enable, Auto, From, To, Translator Engine)
 * on a single accessible landing page, with a clean progressive-disclosure accordion
 * for advanced OCR, inpainting, and data management.
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
    queue: ImmutableList<ReaderViewModel.QueuedPageInfo> = persistentListOf(),
    translationProgress: Pair<Int, Int> = Pair(0, 0),
    translationCurrentPage: Int = 0,
    translationBatchProgress: TranslationProgressSnapshot? = null,
    onRetryBatch: (() -> Unit)? = null,
) {
    var advancedExpanded by rememberSaveable { mutableStateOf(false) }

    val fromLangs = remember {
        TextRecognizerLanguage.entries.associate { it.name to it.label }.toImmutableMap()
    }
    val toLangs = remember {
        TextTranslatorLanguage.entries.associate { it.name to it.label }.toImmutableMap()
    }

    AdaptiveSheet(
        onDismissRequest = onDismissRequest,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(vertical = MaterialTheme.padding.medium),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
        ) {
            // Header: Title and Close button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MaterialTheme.padding.medium),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(ATMR.strings.reader_compare_open_settings),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                IconButton(onClick = onDismissRequest) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = stringResource(MR.strings.action_close),
                    )
                }
            }

            // Primary Switches
            SwitchPreferenceWidget(
                title = stringResource(ATMR.strings.pref_translation_enabled),
                checked = state.enabled,
                onCheckedChanged = onTranslationEnabledChange,
            )
            SwitchPreferenceWidget(
                title = stringResource(ATMR.strings.pref_auto_translate),
                subtitle = stringResource(ATMR.strings.pref_auto_translate_summary),
                checked = state.autoTranslate,
                onCheckedChanged = onAutoTranslateChange,
            )

            // Languages Section
            TranslationSectionLabel(stringResource(ATMR.strings.translation_section_languages))
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

            // Translator Section
            TranslationSectionLabel(stringResource(ATMR.strings.translation_section_translator))
            OptionCardRow(
                options = listOf(
                    stringResource(ATMR.strings.pref_translation_type_standard) to TranslationEngineCategory.STANDARD,
                    stringResource(ATMR.strings.pref_translation_type_ai) to TranslationEngineCategory.AI_MODEL,
                ),
                selected = state.engineCategory,
                onSelect = onTranslationEngineCategoryChange,
                perRow = 2,
            )

            when (state.engineCategory) {
                TranslationEngineCategory.STANDARD -> {
                    val engines = remember {
                        StandardTranslatorKind.entries.associate {
                            StandardEngine.valueOf(it.name) to it.label
                        }
                    }
                    EngineListRow(
                        title = stringResource(ATMR.strings.pref_standard_engine),
                        entries = engines,
                        value = state.standardEngine,
                        onValueChange = onTranslationStandardEngineChange,
                    )
                }
                TranslationEngineCategory.AI_MODEL -> {
                    val providers = remember {
                        AiTranslatorKind.entries.associate { it.engine to it.label }
                    }
                    EngineListRow(
                        title = stringResource(ATMR.strings.pref_ai_provider),
                        entries = providers,
                        value = state.aiEngine,
                        onValueChange = onTranslationAiEngineChange,
                    )
                    if (state.aiModel.isNotBlank()) {
                        Text(
                            text = "Model: ${state.aiModel}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium),
                        )
                    }
                }
            }

            // Stop All Translation button if active
            if (queue.isNotEmpty() || (translationBatchProgress != null && translationBatchProgress.donePages < translationBatchProgress.totalPages)) {
                Button(
                    onClick = onStopAllTranslation,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = MaterialTheme.padding.medium, vertical = 4.dp),
                ) {
                    Text(text = stringResource(ATMR.strings.reader_translation_stop_all))
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            )

            // Progressive Disclosure: Collapsible Advanced Settings
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MaterialTheme.padding.medium)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { advancedExpanded = !advancedExpanded },
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Tune,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Column {
                            Text(
                                text = stringResource(ATMR.strings.translation_section_advanced),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = stringResource(ATMR.strings.translation_section_advanced_summary),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Icon(
                        imageVector = if (advancedExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            AnimatedVisibility(
                visible = advancedExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                ) {
                    // OCR Model Selection
                    TranslationSectionLabel(stringResource(ATMR.strings.translation_advanced_ocr))
                    OcrSection(
                        state = state,
                        onOcrModelChange = onOcrModelChange,
                    )

                    // Inpainting Mode Selection
                    TranslationSectionLabel(stringResource(ATMR.strings.translation_section_inpainting))
                    InpaintSection(
                        inpaintingMode = state.inpaintingMode,
                        neuralModel = state.inpaintingNeuralModel,
                        onTranslationInpaintingModeChange = onTranslationInpaintingModeChange,
                        onTranslationInpaintingNeuralModelChange = onTranslationInpaintingNeuralModelChange,
                    )

                    // Data Management Section
                    TranslationSectionLabel(stringResource(ATMR.strings.translation_section_data_management))
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
}
