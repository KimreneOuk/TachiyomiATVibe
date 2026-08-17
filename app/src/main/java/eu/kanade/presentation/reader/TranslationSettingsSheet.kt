package eu.kanade.presentation.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.presentation.more.settings.widget.AiModelPickerWidget
import eu.kanade.presentation.more.settings.widget.ApiKeyPreferenceWidget
import eu.kanade.presentation.more.settings.widget.EditTextPreferenceWidget
import eu.kanade.presentation.more.settings.widget.SearchableListPreferenceWidget
import eu.kanade.presentation.more.settings.widget.SwitchPreferenceWidget
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import eu.kanade.tachiyomi.ui.reader.TranslationSettingsState
import eu.kanade.translation.model.TranslationBatchPhase
import eu.kanade.translation.model.TranslationProgressSnapshot
import eu.kanade.translation.ocr.TextRecognizerLanguage
import eu.kanade.translation.translator.AiModelFetcher
import eu.kanade.translation.translator.AiTranslatorKind
import eu.kanade.translation.translator.StandardTranslatorKind
import eu.kanade.translation.translator.TextTranslatorLanguage
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableMap
import tachiyomi.domain.translation.AiEngine
import tachiyomi.domain.translation.OcrModel
import tachiyomi.domain.translation.StandardEngine
import tachiyomi.domain.translation.TranslationEngineCategory
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Compact Translation settings sheet shown from the reader overlay.
 *
 * Hosts the master Enable switch, the Auto-translate toggle (with a prefetch
 * slider), searchable Source/Target language pickers, and the engine/model
 * selector. Built on [AdaptiveSheet] so it matches the reader overlay styling
 * and dismisses together with the reader menus.
 *
 * The three selectors uniformly use [SearchableListPreferenceWidget], so each
 * implements the same search/filter behaviour even though the source-language
 * list is short.
 */
@Composable
fun TranslationSettingsSheet(
    state: TranslationSettingsState,
    onDismissRequest: () -> Unit,
    onStopAllTranslation: () -> Unit,
    onTranslationEnabledChange: (Boolean) -> Unit,
    onAutoTranslateChange: (Boolean) -> Unit,
    onAutoTranslatePrefetchCountChange: (Int) -> Unit,
    onTranslateFromLanguageChange: (String) -> Unit,
    onTranslateToLanguageChange: (String) -> Unit,
    onOcrModelChange: (OcrModel) -> Unit,
    onTranslationInpaintingModeChange: (String) -> Unit,
    onTranslationEngineCategoryChange: (TranslationEngineCategory) -> Unit,
    onTranslationStandardEngineChange: (StandardEngine) -> Unit,
    onTranslationDeeplApiKeyChange: (String) -> Unit,
    onTranslationAiEngineChange: (AiEngine) -> Unit,
    onTranslationAiApiKeyChange: (String) -> Unit,
    onTranslationAiBaseUrlChange: (String) -> Unit,
    onTranslationAiModelChange: (String) -> Unit,
    onFetchAiModels: () -> Unit,
    queue: ImmutableList<eu.kanade.tachiyomi.ui.reader.ReaderViewModel.QueuedPageInfo> = persistentListOf(),
    translationProgress: Pair<Int, Int> = Pair(0, 0),
    translationCurrentPage: Int = 0,
    translationBatchProgress: TranslationProgressSnapshot? = null,
    // TachiyomiAT CP7: standalone revision affordances. reviewAvailable drives
    // the "Review this chapter" row; revisionActive swaps it for a cancel
    // action; onReview/onCancelRevision/onViewLastReview are wired by the
    // activity to the view-model.
    reviewAvailable: Boolean = false,
    revisionActive: Boolean = false,
    hasRevisionReport: Boolean = false,
    onReview: () -> Unit = {},
    onCancelRevision: () -> Unit = {},
    onViewLastReview: () -> Unit = {},
    // TachiyomiAT: standalone-revision reviewer configuration. Exposed in the
    // sheet so the in-reader recovery path ("Configure a reviewer") lands on a
    // real control instead of looping back to the same sheet.
    reviewerAuto: Boolean = true,
    reviewerEngine: AiEngine = AiEngine.GEMINI,
    onReviewerAutoChange: (Boolean) -> Unit = {},
    onReviewerEngineChange: (AiEngine) -> Unit = {},
) {
    var showAdvanced by remember { mutableStateOf(false) }

    AdaptiveSheet(onDismissRequest = onDismissRequest) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(vertical = MaterialTheme.padding.medium),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
        ) {
            TogglesSection(
                enabled = state.enabled,
                autoTranslate = state.autoTranslate,
                prefetchCount = state.autoTranslatePrefetchCount,
                onTranslationEnabledChange = onTranslationEnabledChange,
                onAutoTranslateChange = onAutoTranslateChange,
                onAutoTranslatePrefetchCountChange = onAutoTranslatePrefetchCountChange,
            )
            QueueSection(
                queue = queue,
                translationProgress = translationProgress,
                translationCurrentPage = translationCurrentPage,
                translationBatchProgress = translationBatchProgress,
            )
            StopAllSection(onStopAllTranslation)
            ReviewSection(
                reviewAvailable = reviewAvailable,
                revisionActive = revisionActive,
                hasRevisionReport = hasRevisionReport,
                onReview = onReview,
                onCancelRevision = onCancelRevision,
                onViewLastReview = onViewLastReview,
            )
            LanguagesSection(
                translateFromLanguage = state.translateFromLanguage,
                translateToLanguage = state.translateToLanguage,
                translationRecentLanguagesFrom = state.translationRecentLanguagesFrom,
                translationRecentLanguagesTo = state.translationRecentLanguagesTo,
                ocrModel = state.ocrModel,
                ocrModelEntries = state.ocrModelEntries,
                onTranslateFromLanguageChange = onTranslateFromLanguageChange,
                onTranslateToLanguageChange = onTranslateToLanguageChange,
                onOcrModelChange = onOcrModelChange,
            )
            TextButton(
                onClick = { showAdvanced = !showAdvanced },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(ATMR.strings.pref_group_advanced))
                Spacer(Modifier.weight(1f))
                Icon(
                    imageVector = if (showAdvanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                )
            }
            if (showAdvanced) {
                InpaintSection(
                    inpaintingMode = state.inpaintingMode,
                    onTranslationInpaintingModeChange = onTranslationInpaintingModeChange,
                )
                EngineSection(
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
                ReviewerConfigSection(
                    reviewerAuto = reviewerAuto,
                    reviewerEngine = reviewerEngine,
                    onReviewerAutoChange = onReviewerAutoChange,
                    onReviewerEngineChange = onReviewerEngineChange,
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.StopAllSection(onStopAllTranslation: () -> Unit) {
    TextPreferenceWidget(
        title = stringResource(ATMR.strings.reader_translation_stop_all),
        subtitle = stringResource(ATMR.strings.reader_translation_stop_all_summary),
        onPreferenceClick = { onStopAllTranslation() },
    )
}

/**
 * TachiyomiAT CP7: standalone revision affordances on the reader translation
 * sheet. While a revision is active, the row becomes a cancel action; otherwise
 * it offers "Review this chapter" (when eligibility exists) and "View last
 * review" (when a durable report exists). Mirrors the manga surface so both
 * dispatch the same manager request.
 */
@Composable
private fun ColumnScope.ReviewSection(
    reviewAvailable: Boolean,
    revisionActive: Boolean,
    hasRevisionReport: Boolean,
    onReview: () -> Unit,
    onCancelRevision: () -> Unit,
    onViewLastReview: () -> Unit,
) {
    if (revisionActive) {
        TextPreferenceWidget(
            title = stringResource(ATMR.strings.revision_cancel),
            onPreferenceClick = { onCancelRevision() },
        )
    } else if (reviewAvailable) {
        TextPreferenceWidget(
            title = stringResource(ATMR.strings.reader_translation_review_chapter),
            onPreferenceClick = { onReview() },
        )
    }
    if (hasRevisionReport) {
        TextPreferenceWidget(
            title = stringResource(ATMR.strings.revision_view_last),
            onPreferenceClick = { onViewLastReview() },
        )
    }
}

/**
 * TachiyomiAT: live view of the current chapter's translation queue. Shows a
 * one-line summary (current page / total · queued count) and a compact list of
 * each page with its current stage. This is the visibility the user was missing:
 * while auto-translation ran they previously had no way to see what was queued
 * or stop it. The list is bounded so a long chapter doesn't make the sheet
 * unusable — only the first few active/queued pages are listed, with an
 * "and N more" tail.
 */
@Composable
private fun ColumnScope.QueueSection(
    queue: ImmutableList<eu.kanade.tachiyomi.ui.reader.ReaderViewModel.QueuedPageInfo>,
    translationProgress: Pair<Int, Int>,
    translationCurrentPage: Int,
    translationBatchProgress: TranslationProgressSnapshot?,
) {
    val (done, total) = translationProgress
    val queued = queue.count {
        it.stage != eu.kanade.tachiyomi.ui.reader.ReaderViewModel.QueueStage.DONE
    }
    val revision = translationBatchProgress?.revision
    val summary = when {
        total == 0 && queue.isEmpty() ->
            stringResource(ATMR.strings.reader_translation_queue_idle)
        translationCurrentPage > 0 && total > 0 ->
            stringResource(ATMR.strings.reader_translation_queue_summary, translationCurrentPage, total, queued)
        total > 0 ->
            stringResource(ATMR.strings.reader_translation_queued_count, queued)
        else ->
            stringResource(ATMR.strings.reader_translation_queue_idle)
    }

    Text(
        text = summary,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small),
    )

    when (translationBatchProgress?.batchPhase) {
        TranslationBatchPhase.REVISING -> Text(
            text = stringResource(
                ATMR.strings.reader_translation_revision_progress,
                revision?.completedBlocks ?: 0,
                revision?.totalBlocks ?: 0,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium),
        )
        TranslationBatchPhase.FINALIZING -> Text(
            text = stringResource(ATMR.strings.reader_translation_revision_finalizing),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium),
        )
        TranslationBatchPhase.FINISHED -> when {
            (revision?.failedBlocks ?: 0) > 0 -> Text(
                text = stringResource(
                    ATMR.strings.reader_translation_revision_failed,
                    revision?.failedBlocks ?: 0,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium),
            )
            (revision?.totalBlocks ?: 0) > 0 -> Text(
                text = stringResource(ATMR.strings.manga_batch_revision_complete),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium),
            )
            else -> Unit
        }
        else -> Unit
    }

    if (queue.isEmpty()) return

    // Show up to 8 rows, then an "and N more" tail, so a 40-page chapter's
    // queue doesn't push the rest of the sheet off-screen.
    val visible = queue.take(8)
    val remainder = queue.size - visible.size
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MaterialTheme.padding.medium),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall / 2),
    ) {
        visible.forEach { info ->
            QueueRow(info)
        }
        if (remainder > 0) {
            Text(
                text = "… +$remainder",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun QueueRow(info: eu.kanade.tachiyomi.ui.reader.ReaderViewModel.QueuedPageInfo) {
    val stageLabel: String
    val stageColor: androidx.compose.ui.graphics.Color
    when (info.stage) {
        eu.kanade.tachiyomi.ui.reader.ReaderViewModel.QueueStage.DONE -> {
            stageLabel = stringResource(ATMR.strings.reader_translation_stage_done)
            stageColor = MaterialTheme.colorScheme.primary
        }
        eu.kanade.tachiyomi.ui.reader.ReaderViewModel.QueueStage.FAILED -> {
            stageLabel = stringResource(ATMR.strings.reader_translation_stage_failed)
            stageColor = MaterialTheme.colorScheme.error
        }
        eu.kanade.tachiyomi.ui.reader.ReaderViewModel.QueueStage.QUEUED -> {
            stageLabel = stringResource(ATMR.strings.reader_translation_stage_queued)
            stageColor = MaterialTheme.colorScheme.onSurfaceVariant
        }
        else -> {
            val name = when (info.stage) {
                eu.kanade.tachiyomi.ui.reader.ReaderViewModel.QueueStage.OCR ->
                    stringResource(ATMR.strings.reader_translation_stage_ocr)
                eu.kanade.tachiyomi.ui.reader.ReaderViewModel.QueueStage.INPAINT ->
                    stringResource(ATMR.strings.reader_translation_stage_inpaint)
                eu.kanade.tachiyomi.ui.reader.ReaderViewModel.QueueStage.TRANSLATE ->
                    stringResource(ATMR.strings.reader_translation_stage_translate)
                eu.kanade.tachiyomi.ui.reader.ReaderViewModel.QueueStage.RENDER ->
                    stringResource(ATMR.strings.reader_translation_stage_render)
                else -> ""
            }
            stageLabel = stringResource(ATMR.strings.reader_translation_stage_running, name)
            stageColor = MaterialTheme.colorScheme.primary
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(ATMR.strings.reader_translation_page, info.index),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = stageLabel,
            style = MaterialTheme.typography.labelMedium,
            color = stageColor,
        )
    }
}

@Composable
private fun ColumnScope.TogglesSection(
    enabled: Boolean,
    autoTranslate: Boolean,
    prefetchCount: Int,
    onTranslationEnabledChange: (Boolean) -> Unit,
    onAutoTranslateChange: (Boolean) -> Unit,
    onAutoTranslatePrefetchCountChange: (Int) -> Unit,
) {
    SwitchPreferenceWidget(
        title = stringResource(ATMR.strings.pref_translation_enabled),
        checked = enabled,
        onCheckedChanged = onTranslationEnabledChange,
    )

    // Auto mode + prefetch slider are only meaningful when translation is on.
    if (enabled) {
        SwitchPreferenceWidget(
            title = stringResource(ATMR.strings.pref_auto_translate),
            subtitle = stringResource(ATMR.strings.pref_auto_translate_summary),
            checked = autoTranslate,
            onCheckedChanged = onAutoTranslateChange,
        )

        if (autoTranslate) {
            PrefetchSlider(
                value = prefetchCount,
                onValueChange = onAutoTranslatePrefetchCountChange,
            )
        }
    }
}

@Composable
private fun PrefetchSlider(
    value: Int,
    onValueChange: (Int) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MaterialTheme.padding.medium),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(ATMR.strings.pref_auto_translate_prefetch),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = value.toString(),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium),
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toInt()) },
            valueRange = 1f..5f,
            steps = 3,
        )
    }
}

@Composable
private fun ColumnScope.LanguagesSection(
    translateFromLanguage: String,
    translateToLanguage: String,
    translationRecentLanguagesFrom: ImmutableList<String>,
    translationRecentLanguagesTo: ImmutableList<String>,
    ocrModel: OcrModel,
    ocrModelEntries: ImmutableMap<OcrModel, String>,
    onTranslateFromLanguageChange: (String) -> Unit,
    onTranslateToLanguageChange: (String) -> Unit,
    onOcrModelChange: (OcrModel) -> Unit,
) {
    val fromLangs = remember {
        TextRecognizerLanguage.entries.associate { it.name to it.label }.toImmutableMap()
    }
    val toLangs = remember {
        TextTranslatorLanguage.entries.associate { it.name to it.label }.toImmutableMap()
    }

    SearchableLanguageRow(
        title = stringResource(ATMR.strings.pref_translate_from),
        entries = fromLangs,
        value = translateFromLanguage,
        recentLangs = translationRecentLanguagesFrom,
        onValueChange = onTranslateFromLanguageChange,
    )

    EngineListRow(
        title = stringResource(ATMR.strings.pref_ocr_model),
        entries = ocrModelEntries,
        value = ocrModel,
        onValueChange = onOcrModelChange,
    )

    SearchableLanguageRow(
        title = stringResource(ATMR.strings.pref_translate_to),
        entries = toLangs,
        value = translateToLanguage,
        recentLangs = translationRecentLanguagesTo,
        onValueChange = onTranslateToLanguageChange,
    )
}

@Composable
private fun SearchableLanguageRow(
    title: String,
    entries: Map<out String, String>,
    value: String,
    recentLangs: List<String>,
    onValueChange: (String) -> Unit,
) {
    SearchableListPreferenceWidget(
        value = value,
        title = title,
        subtitle = entries[value],
        icon = null,
        entries = entries,
        recentItems = recentLangs,
        onValueChange = onValueChange,
    )
}

@Composable
private fun ColumnScope.InpaintSection(
    inpaintingMode: String,
    onTranslationInpaintingModeChange: (String) -> Unit,
) {
    val entries = mapOf(
        "QUALITY" to stringResource(ATMR.strings.pref_inpainting_mode_quality),
        "FAST" to stringResource(ATMR.strings.pref_inpainting_mode_fast),
    ).toImmutableMap()

    EngineListRow(
        title = stringResource(ATMR.strings.pref_inpainting_mode),
        entries = entries,
        value = inpaintingMode,
        onValueChange = onTranslationInpaintingModeChange,
    )
}

@Composable
private fun ColumnScope.EngineSection(
    state: TranslationSettingsState,
    onTranslationEngineCategoryChange: (TranslationEngineCategory) -> Unit,
    onTranslationStandardEngineChange: (StandardEngine) -> Unit,
    onTranslationDeeplApiKeyChange: (String) -> Unit,
    onTranslationAiEngineChange: (AiEngine) -> Unit,
    onTranslationAiApiKeyChange: (String) -> Unit,
    onTranslationAiBaseUrlChange: (String) -> Unit,
    onTranslationAiModelChange: (String) -> Unit,
    onFetchAiModels: () -> Unit,
) {
    val typeEntries = TranslationEngineCategory.entries.associateWith { entry ->
        when (entry) {
            TranslationEngineCategory.STANDARD -> stringResource(ATMR.strings.pref_translation_type_standard)
            TranslationEngineCategory.AI_MODEL -> stringResource(ATMR.strings.pref_translation_type_ai)
        }
    }.toImmutableMap()

    EngineListRow(
        title = stringResource(ATMR.strings.pref_translation_type),
        entries = typeEntries,
        value = state.engineCategory,
        onValueChange = onTranslationEngineCategoryChange,
    )

    when (state.engineCategory) {
        TranslationEngineCategory.STANDARD -> StandardEngineRows(
            standardEngine = state.standardEngine,
            deeplApiKey = state.deeplApiKey,
            onTranslationStandardEngineChange = onTranslationStandardEngineChange,
            onTranslationDeeplApiKeyChange = onTranslationDeeplApiKeyChange,
        )
        TranslationEngineCategory.AI_MODEL -> AiEngineRows(
            state = state,
            onTranslationAiEngineChange = onTranslationAiEngineChange,
            onTranslationAiApiKeyChange = onTranslationAiApiKeyChange,
            onTranslationAiBaseUrlChange = onTranslationAiBaseUrlChange,
            onTranslationAiModelChange = onTranslationAiModelChange,
            onFetchAiModels = onFetchAiModels,
        )
    }
}

/**
 * TachiyomiAT: standalone-revision reviewer configuration row. The Auto toggle
 * follows the Pass-1 translation engine (default); turning it off reveals the
 * explicit provider picker. The provider reuses its translation API key and
 * model, so only the engine choice is stored here. Mirrors the main Settings
 * reviewer group so the reader recovery path lands on a real control.
 */
@Composable
private fun ColumnScope.ReviewerConfigSection(
    reviewerAuto: Boolean,
    reviewerEngine: AiEngine,
    onReviewerAutoChange: (Boolean) -> Unit,
    onReviewerEngineChange: (AiEngine) -> Unit,
) {
    SwitchPreferenceWidget(
        title = stringResource(ATMR.strings.pref_revision_reviewer_auto),
        subtitle = stringResource(ATMR.strings.pref_revision_reviewer_auto_summary),
        checked = reviewerAuto,
        onCheckedChanged = onReviewerAutoChange,
    )

    if (!reviewerAuto) {
        val providers = AiTranslatorKind.entries.associate { it.engine to it.label }.toImmutableMap()
        EngineListRow(
            title = stringResource(ATMR.strings.pref_revision_reviewer_engine),
            entries = providers,
            value = reviewerEngine,
            onValueChange = onReviewerEngineChange,
        )
    }
}

@Composable
private fun ColumnScope.StandardEngineRows(
    standardEngine: StandardEngine,
    deeplApiKey: String,
    onTranslationStandardEngineChange: (StandardEngine) -> Unit,
    onTranslationDeeplApiKeyChange: (String) -> Unit,
) {
    val engines = StandardTranslatorKind.entries.associate {
        StandardEngine.valueOf(it.name) to it.label
    }.toImmutableMap()

    EngineListRow(
        title = stringResource(ATMR.strings.pref_standard_engine),
        entries = engines,
        value = standardEngine,
        onValueChange = onTranslationStandardEngineChange,
    )

    if (standardEngine == StandardEngine.DEEPL) {
        ApiKeyPreferenceWidget(
            title = stringResource(ATMR.strings.pref_deepl_api_key),
            apiKey = deeplApiKey,
            keySetLabel = stringResource(ATMR.strings.pref_ai_key_set),
            keyNotSetLabel = stringResource(ATMR.strings.pref_ai_key_not_set),
            onApiKeyChange = onTranslationDeeplApiKeyChange,
        )
    }
}

@Composable
private fun ColumnScope.AiEngineRows(
    state: TranslationSettingsState,
    onTranslationAiEngineChange: (AiEngine) -> Unit,
    onTranslationAiApiKeyChange: (String) -> Unit,
    onTranslationAiBaseUrlChange: (String) -> Unit,
    onTranslationAiModelChange: (String) -> Unit,
    onFetchAiModels: () -> Unit,
) {
    val providers = AiTranslatorKind.entries.associate { it.engine to it.label }.toImmutableMap()

    EngineListRow(
        title = stringResource(ATMR.strings.pref_ai_provider),
        entries = providers,
        value = state.aiEngine,
        onValueChange = onTranslationAiEngineChange,
    )

    val apiKeyTitle = when (state.aiEngine) {
        AiEngine.GEMINI -> stringResource(ATMR.strings.pref_ai_api_key_gemini)
        AiEngine.OPENROUTER -> stringResource(ATMR.strings.pref_ai_api_key_openrouter)
        AiEngine.DEEPSEEK -> stringResource(ATMR.strings.pref_ai_api_key_deepseek)
        AiEngine.LMSTUDIO -> stringResource(ATMR.strings.pref_ai_base_url_lmstudio)
    }

    if (state.aiEngine == AiEngine.LMSTUDIO) {
        EditTextPreferenceWidget(
            title = apiKeyTitle,
            subtitle = "%s",
            icon = null,
            value = state.aiBaseUrl,
            isValueValid = { true },
            normalizeValue = { AiModelFetcher.normalizeBaseUrl(it) },
            onConfirm = { newUrl ->
                onTranslationAiBaseUrlChange(newUrl)
                true
            },
        )
    } else {
        ApiKeyPreferenceWidget(
            title = apiKeyTitle,
            apiKey = state.aiApiKey,
            keySetLabel = stringResource(ATMR.strings.pref_ai_key_set),
            keyNotSetLabel = stringResource(ATMR.strings.pref_ai_key_not_set),
            onApiKeyChange = onTranslationAiApiKeyChange,
        )
    }

    val missingConnectionMessage = if (state.aiEngine == AiEngine.LMSTUDIO) {
        stringResource(ATMR.strings.pref_ai_no_base_url)
    } else {
        stringResource(ATMR.strings.pref_ai_no_key)
    }
    val hasConnection = if (state.aiEngine == AiEngine.LMSTUDIO) {
        state.aiBaseUrl.isNotBlank()
    } else {
        state.aiApiKey.isNotBlank()
    }

    AiModelPickerWidget(
        title = stringResource(ATMR.strings.pref_engine_model),
        currentModel = state.aiModel,
        recentModels = state.aiRecentModels,
        listState = state.aiModelFetchState,
        hasApiKey = hasConnection,
        missingConnectionMessage = missingConnectionMessage,
        onFetchModels = onFetchAiModels,
        onSelectModel = onTranslationAiModelChange,
        onManualModel = onTranslationAiModelChange,
    )
}

@Composable
private fun <T> ColumnScope.EngineListRow(
    title: String,
    entries: Map<out T, String>,
    value: T,
    onValueChange: (T) -> Unit,
) {
    eu.kanade.presentation.more.settings.widget.ListPreferenceWidget(
        value = value,
        title = title,
        subtitle = entries[value],
        icon = null,
        entries = entries,
        onValueChange = onValueChange,
    )
}
