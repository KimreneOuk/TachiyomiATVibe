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
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.presentation.more.settings.widget.AiModelListState
import eu.kanade.presentation.more.settings.widget.AiModelPickerWidget
import eu.kanade.presentation.more.settings.widget.ApiKeyPreferenceWidget
import eu.kanade.presentation.more.settings.widget.EditTextPreferenceWidget
import eu.kanade.presentation.more.settings.widget.SearchableListPreferenceWidget
import eu.kanade.presentation.more.settings.widget.SwitchPreferenceWidget
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import eu.kanade.translation.ocr.OcrModelCatalog
import eu.kanade.translation.ocr.TextRecognizerLanguage
import eu.kanade.translation.translator.AiModelFetcher
import eu.kanade.translation.translator.AiTranslatorKind
import eu.kanade.translation.translator.StandardTranslatorKind
import eu.kanade.translation.translator.TextTranslatorLanguage
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.launch
import tachiyomi.domain.translation.AiEngine
import tachiyomi.domain.translation.StandardEngine
import tachiyomi.domain.translation.TranslationEngineCategory
import tachiyomi.domain.translation.TranslationPreferences
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

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
    onDismissRequest: () -> Unit,
    // TachiyomiAT: backs the "Stop all translation" row. Lets the user cancel
    // every in-flight single-page/auto/batch translation job from the reader
    // settings sheet — previously there was no way to stop translation at all
    // short of navigating away or disabling the master toggle.
    onStopAllTranslation: () -> Unit = {},
    // TachiyomiAT: live queue to render in the QueueSection. Passed in from the
    // activity (collected from viewModel.translationQueueState) so the sheet
    // stays a stateless composable and the heavy per-page list only recomposes
    // the sheet, not the reader. Plus the running-page index + totals for the
    // summary line.
    queue: List<eu.kanade.tachiyomi.ui.reader.ReaderViewModel.QueuedPageInfo> = emptyList(),
    translationProgress: Pair<Int, Int> = Pair(0, 0),
    translationCurrentPage: Int = 0,
) {
    val prefs = remember { Injekt.get<TranslationPreferences>() }

    AdaptiveSheet(onDismissRequest = onDismissRequest) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(vertical = MaterialTheme.padding.medium),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
        ) {
            TogglesSection(prefs)
            QueueSection(
                queue = queue,
                translationProgress = translationProgress,
                translationCurrentPage = translationCurrentPage,
            )
            StopAllSection(onStopAllTranslation)
            LanguagesSection(prefs)
            InpaintSection(prefs)
            EngineSection(prefs)
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
    queue: List<eu.kanade.tachiyomi.ui.reader.ReaderViewModel.QueuedPageInfo>,
    translationProgress: Pair<Int, Int>,
    translationCurrentPage: Int,
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
private fun ColumnScope.TogglesSection(prefs: TranslationPreferences) {
    val enabledPref = prefs.translationEnabled()
    val enabled by enabledPref.collectAsState()

    SwitchPreferenceWidget(
        title = stringResource(ATMR.strings.pref_translation_enabled),
        checked = enabled,
        onCheckedChanged = { enabledPref.set(it) },
    )

    // Auto mode + prefetch slider are only meaningful when translation is on.
    if (enabled) {
        val autoPref = prefs.autoTranslate()
        val auto by autoPref.collectAsState()

        SwitchPreferenceWidget(
            title = stringResource(ATMR.strings.pref_auto_translate),
            subtitle = stringResource(ATMR.strings.pref_auto_translate_summary),
            checked = auto,
            onCheckedChanged = { autoPref.set(it) },
        )

        if (auto) {
            PrefetchSlider(prefs)
        }

        val paddleMaskingPref = prefs.translationExperimentalPaddleMasking()
        val paddleMasking by paddleMaskingPref.collectAsState()
        SwitchPreferenceWidget(
            title = stringResource(ATMR.strings.pref_experimental_paddle_masking),
            subtitle = stringResource(ATMR.strings.pref_experimental_paddle_masking_summary),
            checked = paddleMasking,
            onCheckedChanged = { paddleMaskingPref.set(it) },
        )
    }
}

@Composable
private fun PrefetchSlider(prefs: TranslationPreferences) {
    val pref = prefs.autoTranslatePrefetchCount()
    val value by pref.collectAsState()

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
            onValueChange = { pref.set(it.toInt()) },
            valueRange = 1f..5f,
            steps = 3,
        )
    }
}

@Composable
private fun ColumnScope.LanguagesSection(prefs: TranslationPreferences) {
    val fromLangs = remember {
        TextRecognizerLanguage.entries.associate { it.name to it.label }.toImmutableMap()
    }
    val toLangs = remember {
        TextTranslatorLanguage.entries.associate { it.name to it.label }.toImmutableMap()
    }

    SearchableLanguageRow(
        title = stringResource(ATMR.strings.pref_translate_from),
        entries = fromLangs,
        valuePref = prefs.translateFromLanguage(),
        recentPref = prefs.translationRecentLanguagesFrom(),
    )
    OcrModelRow(prefs)
    SearchableLanguageRow(
        title = stringResource(ATMR.strings.pref_translate_to),
        entries = toLangs,
        valuePref = prefs.translateToLanguage(),
        recentPref = prefs.translationRecentLanguagesTo(),
    )
}

@Composable
private fun ColumnScope.OcrModelRow(prefs: TranslationPreferences) {
    val fromValue by prefs.translateFromLanguage().collectAsState()
    val language = remember(fromValue) {
        TextRecognizerLanguage.entries.firstOrNull { it.name == fromValue }
            ?: TextRecognizerLanguage.CHINESE
    }
    val ocrPref = remember(language) {
        OcrModelCatalog.preferenceFor(prefs, language)
    }
    val storedModel by ocrPref.collectAsState()
    val selectedModel = remember(storedModel, language) {
        OcrModelCatalog.coerce(storedModel, language)
    }
    LaunchedEffect(storedModel, selectedModel) {
        if (storedModel != selectedModel) {
            ocrPref.set(selectedModel)
        }
    }
    val entries = remember(language) {
        OcrModelCatalog.labelsFor(language)
    }

    EngineListRow(
        title = stringResource(ATMR.strings.pref_ocr_model),
        entries = entries,
        value = selectedModel,
        onValueChange = { ocrPref.set(it) },
    )
}

@Composable
private fun SearchableLanguageRow(
    title: String,
    entries: Map<out String, String>,
    valuePref: tachiyomi.core.common.preference.Preference<String>,
    recentPref: tachiyomi.core.common.preference.Preference<String>,
) {
    val value by valuePref.collectAsState()
    val recentRaw by recentPref.collectAsState()
    val recentLangs = remember(recentRaw) {
        TranslationPreferences.decodeRecentLanguages(recentRaw)
    }
    SearchableListPreferenceWidget(
        value = value,
        title = title,
        subtitle = entries[value],
        icon = null,
        entries = entries,
        recentItems = recentLangs,
        onValueChange = { newValue ->
            valuePref.set(newValue)
            val updated = TranslationPreferences.encodeRecentLanguages(listOf(newValue) + recentLangs)
            recentPref.set(updated)
        },
    )
}

/**
 * TachiyomiAT: reader-side inpaint-mode picker. Previously inpaint mode was
 * only configurable in global Settings. FAST = non-neural Telea fill; QUALITY
 * = neural AOT-GAN reconstruction. The preference is a raw stored string
 * ("FAST"/"QUALITY"), mapped to localized labels here.
 */
@Composable
private fun ColumnScope.InpaintSection(prefs: TranslationPreferences) {
    val pref = prefs.translationInpaintingMode()
    val value by pref.collectAsState()
    val entries = mapOf(
        "QUALITY" to stringResource(ATMR.strings.pref_inpainting_mode_quality),
        "FAST" to stringResource(ATMR.strings.pref_inpainting_mode_fast),
    ).toImmutableMap()

    EngineListRow(
        title = stringResource(ATMR.strings.pref_inpainting_mode),
        entries = entries,
        value = value,
        onValueChange = { pref.set(it) },
    )
}

@Composable
private fun ColumnScope.EngineSection(prefs: TranslationPreferences) {
    val categoryPref = prefs.translationEngineCategory()
    val category by categoryPref.collectAsState()
    val typeEntries = TranslationEngineCategory.entries.associateWith { entry ->
        when (entry) {
            TranslationEngineCategory.STANDARD -> stringResource(ATMR.strings.pref_translation_type_standard)
            TranslationEngineCategory.AI_MODEL -> stringResource(ATMR.strings.pref_translation_type_ai)
        }
    }.toImmutableMap()

    // TachiyomiAT: the top-level translator type (Standard vs AI model) is now
    // switchable from inside the reader, not only global Settings. Previously
    // this was a read-only TextPreferenceWidget (no-op click). The sub-engine
    // rows below still render the per-category picker (MLKit/Google or provider
    // + model) and reactively swap when the category changes.
    EngineListRow(
        title = stringResource(ATMR.strings.pref_translation_type),
        entries = typeEntries,
        value = category,
        onValueChange = { categoryPref.set(it) },
    )

    when (category) {
        TranslationEngineCategory.STANDARD -> StandardEngineRows(prefs)
        TranslationEngineCategory.AI_MODEL -> AiEngineRows(prefs)
    }
}

@Composable
private fun ColumnScope.StandardEngineRows(prefs: TranslationPreferences) {
    val pref = prefs.translationStandardEngine()
    val value by pref.collectAsState()
    val engines = StandardTranslatorKind.entries.associate {
        StandardEngine.valueOf(it.name) to it.label
    }.toImmutableMap()

    EngineListRow(
        title = stringResource(ATMR.strings.pref_standard_engine),
        entries = engines,
        value = value,
        onValueChange = { pref.set(it) },
    )

    // DeepL is the only Standard engine that needs credentials; show its key
    // row only when DeepL is selected so the user can configure it from the
    // reader without leaving for global Settings.
    if (value == StandardEngine.DEEPL) {
        val apiKeyPref = prefs.translationDeeplApiKey()
        val apiKey by apiKeyPref.collectAsState()
        ApiKeyPreferenceWidget(
            title = stringResource(ATMR.strings.pref_deepl_api_key),
            apiKey = apiKey,
            keySetLabel = stringResource(ATMR.strings.pref_ai_key_set),
            keyNotSetLabel = stringResource(ATMR.strings.pref_ai_key_not_set),
            onApiKeyChange = { apiKeyPref.set(it) },
        )
    }
}

@Composable
private fun ColumnScope.AiEngineRows(prefs: TranslationPreferences) {
    val scope = rememberCoroutineScope()
    val providers = AiTranslatorKind.entries.associate { it.engine to it.label }.toImmutableMap()

    val enginePref = prefs.translationAiEngine()
    val aiEngine by enginePref.collectAsState()

    val apiKeyPref = remember(aiEngine) { prefs.translationAiApiKey(aiEngine) }
    val apiKey by apiKeyPref.collectAsState()
    val baseUrlPref = remember(aiEngine) { prefs.translationAiBaseUrl(aiEngine) }
    val baseUrl by baseUrlPref?.collectAsState() ?: remember { mutableStateOf("") }
    val modelPref = remember(aiEngine) { prefs.translationAiModel(aiEngine) }
    val currentModel by modelPref.collectAsState()
    val recentPref = remember(aiEngine) { prefs.translationAiRecentModels(aiEngine) }
    val recentRaw by recentPref.collectAsState()
    val recentModels = remember(recentRaw) {
        TranslationPreferences.decodeRecentModels(recentRaw)
    }
    var fetchState by remember(aiEngine) { mutableStateOf<AiModelListState>(AiModelListState.Idle) }

    EngineListRow(
        title = stringResource(ATMR.strings.pref_ai_provider),
        entries = providers,
        value = aiEngine,
        onValueChange = { enginePref.set(it) },
    )

    val apiKeyTitle = when (aiEngine) {
        AiEngine.GEMINI -> stringResource(ATMR.strings.pref_ai_api_key_gemini)
        AiEngine.OPENROUTER -> stringResource(ATMR.strings.pref_ai_api_key_openrouter)
        AiEngine.DEEPSEEK -> stringResource(ATMR.strings.pref_ai_api_key_deepseek)
        AiEngine.LMSTUDIO -> stringResource(ATMR.strings.pref_ai_base_url_lmstudio)
    }
    if (aiEngine == AiEngine.LMSTUDIO && baseUrlPref != null) {
        EditTextPreferenceWidget(
            title = apiKeyTitle,
            subtitle = "%s",
            icon = null,
            value = baseUrl,
            isValueValid = { true },
            normalizeValue = { AiModelFetcher.normalizeBaseUrl(it) },
            onConfirm = { newUrl ->
                baseUrlPref.set(newUrl)
                true
            },
        )
    } else {
        ApiKeyPreferenceWidget(
            title = apiKeyTitle,
            apiKey = apiKey,
            keySetLabel = stringResource(ATMR.strings.pref_ai_key_set),
            keyNotSetLabel = stringResource(ATMR.strings.pref_ai_key_not_set),
            onApiKeyChange = { apiKeyPref.set(it) },
        )
    }

    val missingConnectionMessage = if (aiEngine == AiEngine.LMSTUDIO) {
        stringResource(ATMR.strings.pref_ai_no_base_url)
    } else {
        stringResource(ATMR.strings.pref_ai_no_key)
    }
    val hasConnection = if (aiEngine == AiEngine.LMSTUDIO) {
        baseUrl.isNotBlank()
    } else {
        apiKey.isNotBlank()
    }

    val onFetch: () -> Unit = {
        val key = apiKey
        val url = baseUrl
        fetchState = AiModelListState.Loading(if (aiEngine == AiEngine.LMSTUDIO) url else key)
        scope.launch {
            val result = AiModelFetcher.fetch(aiEngine, key, url)
            fetchState = when (result) {
                is AiModelFetcher.Result.Success -> AiModelListState.Loaded(result.models)
                is AiModelFetcher.Result.InvalidKey -> AiModelListState.Failed("Invalid or expired API key")
                is AiModelFetcher.Result.NoModels -> AiModelListState.Loaded(emptyList())
                is AiModelFetcher.Result.Error -> AiModelListState.Failed(result.message)
            }
        }
    }
    val onSelectModel: (String) -> Unit = { id ->
        modelPref.set(id)
        recentPref.set(TranslationPreferences.encodeRecentModels(listOf(id) + recentModels))
    }
    AiModelPickerWidget(
        title = stringResource(ATMR.strings.pref_engine_model),
        currentModel = currentModel,
        recentModels = recentModels,
        listState = fetchState,
        hasApiKey = hasConnection,
        missingConnectionMessage = missingConnectionMessage,
        onFetchModels = onFetch,
        onSelectModel = onSelectModel,
        onManualModel = onSelectModel,
    )
}

@Composable
private fun <T> ColumnScope.EngineListRow(
    title: String,
    entries: Map<out T, String>,
    value: T,
    onValueChange: (T) -> Unit,
) {
    // Use the non-searchable ListPreferenceWidget for short engine lists.
    eu.kanade.presentation.more.settings.widget.ListPreferenceWidget(
        value = value,
        title = title,
        subtitle = entries[value],
        icon = null,
        entries = entries,
        onValueChange = onValueChange,
    )
}
