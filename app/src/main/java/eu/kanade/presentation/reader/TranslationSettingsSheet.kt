package eu.kanade.presentation.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import eu.kanade.translation.engines.translator.AiTranslatorKind
import eu.kanade.translation.engines.translator.StandardTranslatorKind
import eu.kanade.translation.engines.translator.providers.AiModelFetcher
import eu.kanade.translation.model.TextRecognizerLanguage
import eu.kanade.translation.model.TextTranslatorLanguage
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationProgressSnapshot
import eu.kanade.translation.model.TranslationRequestPhase
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
 * Compact Translation settings sheet shown from the reader overlay.
 *
 * Hosts the master Enable switch, the Auto-translate toggle, searchable
 * Source/Target language pickers, and the engine/model
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
    onTranslateButtonWithControlsChange: (Boolean) -> Unit,
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
    queue: ImmutableList<eu.kanade.tachiyomi.ui.reader.ReaderViewModel.QueuedPageInfo> = persistentListOf(),
    translationProgress: Pair<Int, Int> = Pair(0, 0),
    translationCurrentPage: Int = 0,
    translationBatchProgress: TranslationProgressSnapshot? = null,
    onRetryBatch: (() -> Unit)? = null,
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
                translateButtonWithControls = state.translateButtonWithControls,
                onTranslationEnabledChange = onTranslationEnabledChange,
                onAutoTranslateChange = onAutoTranslateChange,
                onTranslateButtonWithControlsChange = onTranslateButtonWithControlsChange,
            )
            QueueSection(
                queue = queue,
                translationProgress = translationProgress,
                translationCurrentPage = translationCurrentPage,
                translationBatchProgress = translationBatchProgress,
                onRetryBatch = onRetryBatch,
            )
            StopAllSection(onStopAllTranslation)
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
            if (state.ocrModel == OcrModel.PADDLEOCR_V6_SMALL) {
                PaddleOcrDeviceSection(state, onPaddleOcrComputeDeviceOverrideChange)
            }
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
                    neuralModel = state.inpaintingNeuralModel,
                    onTranslationInpaintingModeChange = onTranslationInpaintingModeChange,
                    onTranslationInpaintingNeuralModelChange = onTranslationInpaintingNeuralModelChange,
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
            }
        }
    }
}

@Composable
private fun ColumnScope.PaddleOcrDeviceSection(
    state: TranslationSettingsState,
    onDeviceChange: (PaddleOcrComputeDevice?) -> Unit,
) {
    val gpuLabel = stringResource(ATMR.strings.pref_paddle_ocr_device_gpu)
    val cpuLabel = stringResource(ATMR.strings.pref_paddle_ocr_device_cpu)
    val defaultLabel = when (state.paddleOcrDefaultComputeDevice) {
        PaddleOcrComputeDevice.GPU -> gpuLabel
        PaddleOcrComputeDevice.CPU -> cpuLabel
    }
    TextPreferenceWidget(
        title = stringResource(ATMR.strings.pref_paddle_ocr_device),
        subtitle = stringResource(ATMR.strings.reader_paddle_ocr_device_summary),
    )
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MaterialTheme.padding.medium),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
    ) {
        FilterChip(
            selected = state.paddleOcrComputeDeviceOverride == null,
            onClick = { onDeviceChange(null) },
            label = { Text(stringResource(ATMR.strings.reader_paddle_ocr_device_default, defaultLabel)) },
        )
        FilterChip(
            selected = state.paddleOcrComputeDeviceOverride == PaddleOcrComputeDevice.GPU,
            onClick = { onDeviceChange(PaddleOcrComputeDevice.GPU) },
            label = { Text(gpuLabel) },
        )
        FilterChip(
            selected = state.paddleOcrComputeDeviceOverride == PaddleOcrComputeDevice.CPU,
            onClick = { onDeviceChange(PaddleOcrComputeDevice.CPU) },
            label = { Text(cpuLabel) },
        )
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
    onRetryBatch: (() -> Unit)?,
) {
    val (done, total) = translationProgress
    val queued = queue.count {
        it.stage != eu.kanade.tachiyomi.ui.reader.ReaderViewModel.QueueStage.DONE
    }
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

    val snapshot = translationBatchProgress
    val isPaused = snapshot?.state == Translation.State.PAUSED || snapshot?.pauseReason != null
    val request = snapshot?.requestState
    val hasBatchProjection = snapshot != null &&
        (
            snapshot.batchPhase != eu.kanade.translation.model.TranslationBatchPhase.IDLE ||
                snapshot.state == Translation.State.QUEUE ||
                snapshot.state == Translation.State.TRANSLATING ||
                isPaused ||
                request != null
            )

    if (hasBatchProjection && snapshot != null) {
        val status = when {
            request?.phase == TranslationRequestPhase.STARTING -> "Translation accepted — preparing batch"
            request?.phase == TranslationRequestPhase.PREPARING -> "Preparing translation batch"
            request?.phase == TranslationRequestPhase.WAITING_FOR_DOWNLOAD ->
                "Waiting for chapter download before translation"
            request?.phase == TranslationRequestPhase.DOWNLOAD_FAILED ->
                "Download failed — retry to continue"
            isPaused -> {
                val reason = snapshot.pauseReason?.takeIf { it.isNotBlank() } ?: "Provider work is temporarily unavailable"
                val retryAt = snapshot.nextEligibleRetryAtEpochMs?.let { " · retry after ${formatRetryTime(it)}" }.orEmpty()
                "Paused — $reason$retryAt"
            }
            snapshot.state == Translation.State.QUEUE -> "Batch queued — preparing to resume"
            snapshot.state == Translation.State.TRANSLATING -> "Batch translating · ${snapshot.donePages}/${snapshot.totalPages} pages"
            else -> "Batch progress · ${snapshot.donePages}/${snapshot.totalPages} pages"
        }
        Text(
            text = status,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isPaused) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small),
        )
        if (isPaused && onRetryBatch != null) {
            TextButton(
                onClick = onRetryBatch,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MaterialTheme.padding.medium),
            ) {
                Text("Retry translation")
            }
        }
    }

    Text(
        text = summary,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small),
    )

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

private fun formatRetryTime(epochMs: Long): String =
    java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(epochMs))

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
    translateButtonWithControls: Boolean,
    onTranslationEnabledChange: (Boolean) -> Unit,
    onAutoTranslateChange: (Boolean) -> Unit,
    onTranslateButtonWithControlsChange: (Boolean) -> Unit,
) {
    SwitchPreferenceWidget(
        title = stringResource(ATMR.strings.pref_translation_enabled),
        checked = enabled,
        onCheckedChanged = onTranslationEnabledChange,
    )

    // Auto mode is only meaningful when translation is on.
    if (enabled) {
        SwitchPreferenceWidget(
            title = stringResource(ATMR.strings.pref_auto_translate),
            subtitle = stringResource(ATMR.strings.pref_auto_translate_summary),
            checked = autoTranslate,
            onCheckedChanged = onAutoTranslateChange,
        )
    }

    // TachiyomiAT: translate button presentation. One lightweight toggle maps
    // the two spec modes — unchecked = adaptive ghost (default), checked = show
    // the idle button only while reader controls are visible.
    SwitchPreferenceWidget(
        title = stringResource(ATMR.strings.pref_reader_translate_button_mode),
        subtitle = stringResource(
            if (translateButtonWithControls) {
                ATMR.strings.pref_reader_translate_button_mode_summary_with_controls
            } else {
                ATMR.strings.pref_reader_translate_button_mode_summary_default
            },
        ),
        checked = translateButtonWithControls,
        onCheckedChanged = onTranslateButtonWithControlsChange,
    )
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
    neuralModel: NeuralInpaintModel,
    onTranslationInpaintingModeChange: (String) -> Unit,
    onTranslationInpaintingNeuralModelChange: (NeuralInpaintModel) -> Unit,
) {
    val entries = mapOf(
        "QUALITY" to stringResource(ATMR.strings.pref_inpainting_mode_quality),
        "BALANCE" to stringResource(ATMR.strings.pref_inpainting_mode_balance),
        "FAST" to stringResource(ATMR.strings.pref_inpainting_mode_fast),
    ).toImmutableMap()

    EngineListRow(
        title = stringResource(ATMR.strings.pref_inpainting_mode),
        entries = entries,
        value = inpaintingMode,
        onValueChange = onTranslationInpaintingModeChange,
    )

    if (inpaintingMode == "BALANCE" || inpaintingMode == "QUALITY") {
        val neuralModelEntries = mapOf(
            NeuralInpaintModel.LAMA_LITERT_GPU to stringResource(ATMR.strings.pref_inpainting_mode_lama_gpu),
            NeuralInpaintModel.AOT_GAN to stringResource(ATMR.strings.pref_inpainting_neural_model_aot_gan),
        )
        EngineListRow(
            title = stringResource(ATMR.strings.pref_inpainting_neural_model),
            entries = neuralModelEntries,
            value = neuralModel,
            onValueChange = onTranslationInpaintingNeuralModelChange,
        )

        // Honest accelerator display for BOTH neural models: the shared
        // LiteRT runtime is GPU-first with no neural CPU/NPU fallback, so the
        // old selectable CPU/GPU/NPU chips (ignored by the runtime) and the
        // claimed CPU XNNPACK fallback were removed.
        Text(
            text = stringResource(ATMR.strings.pref_inpainting_hardware_override),
            style = MaterialTheme.typography.labelLarge,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
        ) {
            FilterChip(
                selected = true,
                onClick = {},
                enabled = false,
                label = {
                    Text(text = stringResource(ATMR.strings.pref_inpainting_hardware_gpu_fixed))
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(
            text = stringResource(ATMR.strings.pref_inpainting_hardware_override_summary),
            style = MaterialTheme.typography.bodySmall,
        )
    }
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
