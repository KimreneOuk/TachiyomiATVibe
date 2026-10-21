package eu.kanade.presentation.reader

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import eu.kanade.presentation.more.settings.widget.AiModelPickerWidget
import eu.kanade.presentation.more.settings.widget.ApiKeyPreferenceWidget
import eu.kanade.presentation.more.settings.widget.EditTextPreferenceWidget
import eu.kanade.presentation.more.settings.widget.ListPreferenceWidget
import eu.kanade.translation.engines.translator.AiTranslatorKind
import eu.kanade.translation.engines.translator.StandardTranslatorKind
import eu.kanade.translation.engines.translator.providers.AiModelFetcher
import tachiyomi.domain.translation.AiEngine
import tachiyomi.domain.translation.StandardEngine
import tachiyomi.domain.translation.TranslationEngineCategory
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Engine selection + provider-specific credential/model rows shared by the
 * translation settings sheet's quick controls. Behavior is identical to the
 * previous Advanced-expander content; only placement changed.
 */
@Composable
internal fun ColumnScope.EngineRows(
    state: eu.kanade.tachiyomi.ui.reader.TranslationSettingsState,
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
    }

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
    }

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
    state: eu.kanade.tachiyomi.ui.reader.TranslationSettingsState,
    onTranslationAiEngineChange: (AiEngine) -> Unit,
    onTranslationAiApiKeyChange: (String) -> Unit,
    onTranslationAiBaseUrlChange: (String) -> Unit,
    onTranslationAiModelChange: (String) -> Unit,
    onFetchAiModels: () -> Unit,
) {
    val providers = AiTranslatorKind.entries.associate { it.engine to it.label }

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
internal fun <T> ColumnScope.EngineListRow(
    title: String,
    entries: Map<out T, String>,
    value: T,
    onValueChange: (T) -> Unit,
) {
    ListPreferenceWidget(
        value = value,
        title = title,
        subtitle = entries[value],
        icon = null,
        entries = entries,
        onValueChange = onValueChange,
    )
}
