package eu.kanade.presentation.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
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
import eu.kanade.presentation.components.ConfigSelectorBox
import eu.kanade.presentation.components.ConfigSwitchRow
import eu.kanade.presentation.components.DropdownMenu
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import eu.kanade.tachiyomi.ui.reader.TranslationSettingsState
import eu.kanade.translation.engines.translator.AiTranslatorKind
import eu.kanade.translation.engines.translator.StandardTranslatorKind
import eu.kanade.translation.model.TextRecognizerLanguage
import eu.kanade.translation.model.TextTranslatorLanguage
import eu.kanade.translation.model.TranslationProgressSnapshot
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
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
 * Adapts the card-based layout and selector patterns of ConfirmTranslationDialog.
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

    var fromMenuOpen by remember { mutableStateOf(false) }
    var toMenuOpen by remember { mutableStateOf(false) }
    var translatorMenuOpen by remember { mutableStateOf(false) }

    val currentFromLabel = remember(state.translateFromLanguage) {
        TextRecognizerLanguage.entries.firstOrNull { it.name.equals(state.translateFromLanguage, true) }?.label
            ?: state.translateFromLanguage
    }
    val currentToLabel = remember(state.translateToLanguage) {
        TextTranslatorLanguage.entries.firstOrNull { it.name.equals(state.translateToLanguage, true) }?.label
            ?: state.translateToLanguage
    }

    val currentTranslatorLabel = remember(state.engineCategory, state.standardEngine, state.aiEngine) {
        when (state.engineCategory) {
            TranslationEngineCategory.STANDARD -> StandardTranslatorKind.entries
                .firstOrNull { it.name == state.standardEngine.name }?.label ?: state.standardEngine.name
            TranslationEngineCategory.AI_MODEL -> AiTranslatorKind.entries
                .firstOrNull { it.engine == state.aiEngine }?.label ?: state.aiEngine.name
        }
    }

    val currentModelSubtitle = remember(state.engineCategory, state.aiModel) {
        if (state.engineCategory == TranslationEngineCategory.AI_MODEL && state.aiModel.isNotBlank()) {
            "Model: ${state.aiModel}"
        } else {
            null
        }
    }

    AdaptiveSheet(
        onDismissRequest = onDismissRequest,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(MaterialTheme.padding.medium),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Header: Title + Subtitle + Translate Icon + Close Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Translate,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp),
                    )
                    Column {
                        Text(
                            text = stringResource(ATMR.strings.reader_compare_open_settings),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = stringResource(ATMR.strings.pref_translation_summary),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(onClick = onDismissRequest) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = stringResource(MR.strings.action_close),
                    )
                }
            }

            // Quick Switches: Enable & Auto-translate
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ConfigSwitchRow(
                    title = stringResource(ATMR.strings.pref_translation_enabled),
                    checked = state.enabled,
                    onCheckedChange = onTranslationEnabledChange,
                )
                ConfigSwitchRow(
                    title = stringResource(ATMR.strings.pref_auto_translate),
                    subtitle = stringResource(ATMR.strings.pref_auto_translate_summary),
                    checked = state.autoTranslate,
                    onCheckedChange = onAutoTranslateChange,
                )
            }

            // Languages Section: From & To side-by-side
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(ATMR.strings.translation_section_languages),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // From Language Picker
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(ATMR.strings.pref_translate_from),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        Box {
                            ConfigSelectorBox(
                                text = currentFromLabel,
                                onClick = { fromMenuOpen = true },
                            )
                            DropdownMenu(
                                expanded = fromMenuOpen,
                                onDismissRequest = { fromMenuOpen = false },
                            ) {
                                TextRecognizerLanguage.entries.forEach { lang ->
                                    DropdownMenuItem(
                                        text = { Text(lang.label) },
                                        onClick = {
                                            onTranslateFromLanguageChange(lang.name)
                                            fromMenuOpen = false
                                        },
                                    )
                                }
                            }
                        }
                    }

                    // To Language Picker
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(ATMR.strings.pref_translate_to),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        Box {
                            ConfigSelectorBox(
                                text = currentToLabel,
                                onClick = { toMenuOpen = true },
                            )
                            DropdownMenu(
                                expanded = toMenuOpen,
                                onDismissRequest = { toMenuOpen = false },
                            ) {
                                TextTranslatorLanguage.entries.forEach { lang ->
                                    DropdownMenuItem(
                                        text = { Text(lang.label) },
                                        onClick = {
                                            onTranslateToLanguageChange(lang.name)
                                            toMenuOpen = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Translator Section: Engine and active model
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(ATMR.strings.translation_section_translator),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Box(modifier = Modifier.fillMaxWidth()) {
                    ConfigSelectorBox(
                        text = currentTranslatorLabel,
                        onClick = { translatorMenuOpen = true },
                    )
                    DropdownMenu(
                        expanded = translatorMenuOpen,
                        onDismissRequest = { translatorMenuOpen = false },
                    ) {
                        StandardTranslatorKind.entries.forEach { kind ->
                            DropdownMenuItem(
                                text = { Text(kind.label) },
                                onClick = {
                                    onTranslationEngineCategoryChange(TranslationEngineCategory.STANDARD)
                                    onTranslationStandardEngineChange(StandardEngine.valueOf(kind.name))
                                    translatorMenuOpen = false
                                },
                            )
                        }
                        AiTranslatorKind.entries.forEach { kind ->
                            DropdownMenuItem(
                                text = { Text(kind.label) },
                                onClick = {
                                    onTranslationEngineCategoryChange(TranslationEngineCategory.AI_MODEL)
                                    onTranslationAiEngineChange(kind.engine)
                                    translatorMenuOpen = false
                                },
                            )
                        }
                    }
                }
                currentModelSubtitle?.let { modelSubtitle ->
                    Text(
                        text = modelSubtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Stop All Button (if active queue/batch)
            if (queue.isNotEmpty() || (translationBatchProgress != null && translationBatchProgress.donePages < translationBatchProgress.totalPages)) {
                Button(
                    onClick = onStopAllTranslation,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = stringResource(ATMR.strings.reader_translation_stop_all))
                }
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            )

            // Progressive Disclosure: Collapsible Advanced Settings
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
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
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    // OCR Model Selection
                    OcrSection(
                        state = state,
                        onOcrModelChange = onOcrModelChange,
                    )

                    // Inpainting Mode Selection
                    InpaintSection(
                        inpaintingMode = state.inpaintingMode,
                        neuralModel = state.inpaintingNeuralModel,
                        onTranslationInpaintingModeChange = onTranslationInpaintingModeChange,
                        onTranslationInpaintingNeuralModelChange = onTranslationInpaintingNeuralModelChange,
                    )

                    // Data Management Section
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
