package eu.kanade.presentation.more.settings.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.widget.AiModelListState
import eu.kanade.presentation.more.settings.widget.AiModelPickerWidget
import eu.kanade.presentation.more.settings.widget.ApiKeyPreferenceWidget
import eu.kanade.presentation.more.settings.widget.EditTextPreferenceWidget
import eu.kanade.presentation.more.settings.widget.ListPreferenceWidget
import eu.kanade.presentation.more.settings.widget.SearchableListPreferenceWidget
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.translation.data.TranslationFont
import eu.kanade.translation.ocr.OcrModelCatalog
import eu.kanade.translation.ocr.TextRecognizerLanguage
import eu.kanade.translation.translator.providers.AiModelFetcher
import eu.kanade.translation.translator.AiTranslatorKind
import eu.kanade.translation.translator.StandardTranslatorKind
import eu.kanade.translation.translator.TextTranslatorLanguage
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.launch
import tachiyomi.domain.translation.AiEngine
import tachiyomi.domain.translation.GeminiThinkingMode
import tachiyomi.domain.translation.StandardEngine
import tachiyomi.domain.translation.TranslationEngineCategory
import tachiyomi.domain.translation.TranslationHardwareAccelerator
import tachiyomi.domain.translation.TranslationPreferences
import tachiyomi.domain.translation.TranslationReadingOrder
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object SettingsTranslationScreen : SearchableSettings {
    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = ATMR.strings.pref_category_translations

    @Composable
    override fun getPreferences(): List<Preference> {
        val entries = TranslationFont.entries
        val translationPreferences = remember { Injekt.get<TranslationPreferences>() }
        return listOf(
            Preference.PreferenceItem.SwitchPreference(
                pref = translationPreferences.translationConfirmPretranslate(),
                title = stringResource(ATMR.strings.pref_confirm_pretranslate),
                subtitle = stringResource(ATMR.strings.pref_confirm_pretranslate_summary),
            ),
            Preference.PreferenceItem.ListPreference(
                pref = translationPreferences.translationFont(),
                title = stringResource(ATMR.strings.pref_reader_font),
                entries = entries.withIndex().associate { it.index to it.value.label }.toImmutableMap(),
            ),
            getTranslationLangGroup(translationPreferences),
            getInpaintingModeGroup(translationPreferences),
            getHardwareAccelerationGroup(translationPreferences),
            getEngineGroup(translationPreferences),
        ) + if (BuildConfig.DEBUG) {
            listOf(getExperimentsGroup(translationPreferences))
        } else {
            emptyList()
        }
    }

    /**
     * T924 debug-build-only experiment switches. The remaining flag is read at
     * each translation commit/render (never mid-run), so flipping between
     * commits is the supported flow. Never shown in release builds. (FF-01
     * completed its A/B lifecycle: the profile pipeline is now the only
     * pipeline and the switch was removed with the flag.)
     */
    @Composable
    private fun getExperimentsGroup(
        translationPreferences: TranslationPreferences,
    ): Preference.PreferenceGroup = Preference.PreferenceGroup(
        title = "Experiments (debug)",
        preferenceItems = persistentListOf(
            Preference.PreferenceItem.SwitchPreference(
                pref = translationPreferences.translationBatchPersistedLayout(),
                title = "Persisted layout reader bridge (FF-02)",
                subtitle = "Persist translation layouts with committed pages and " +
                    "hydrate them in the reader. Takes effect on the next commit/render.",
            ),
        ),
    )

    @Composable
    private fun getHardwareAccelerationGroup(
        translationPreferences: TranslationPreferences,
    ): Preference.PreferenceGroup {
        // NNAPI is deliberately absent: the onnxruntime-android-qnn artifact does
        // not compile the NNAPI execution provider, so offering it would be a lie.
        val accelerators = mapOf(
            TranslationHardwareAccelerator.AUTO to stringResource(ATMR.strings.pref_hardware_accelerator_auto),
            TranslationHardwareAccelerator.QUALCOMM_NPU to stringResource(
                ATMR.strings.pref_hardware_accelerator_qualcomm_npu,
            ),
            TranslationHardwareAccelerator.CPU_XNNPACK to stringResource(ATMR.strings.pref_hardware_accelerator_cpu),
        )
        return Preference.PreferenceGroup(
            title = stringResource(ATMR.strings.pref_hardware_acceleration),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.ListPreference(
                    pref = translationPreferences.translationHardwareAccelerator(),
                    title = stringResource(ATMR.strings.pref_hardware_acceleration),
                    subtitle = stringResource(ATMR.strings.pref_hardware_acceleration_summary),
                    entries = accelerators.toImmutableMap(),
                ),
            ),
        )
    }

    @Composable
    private fun getInpaintingModeGroup(
        translationPreferences: TranslationPreferences,
    ): Preference.PreferenceGroup {
        val inpaintMode by translationPreferences.translationInpaintingMode().collectAsState()
        val modes = mapOf(
            "QUALITY" to stringResource(ATMR.strings.pref_inpainting_mode_quality),
            "FAST" to stringResource(ATMR.strings.pref_inpainting_mode_fast),
        )
        return Preference.PreferenceGroup(
            title = stringResource(ATMR.strings.pref_inpainting_mode),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.ListPreference(
                    pref = translationPreferences.translationInpaintingMode(),
                    title = stringResource(ATMR.strings.pref_inpainting_mode),
                    entries = modes.toImmutableMap(),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    pref = translationPreferences.translationInpaintQualityFallback(),
                    title = "QUALITY → FAST fallback",
                    subtitle = "Use FAST inpainting when the QUALITY neural model is unavailable",
                    enabled = inpaintMode == "QUALITY",
                ),
            ),
        )
    }

    @Composable
    private fun getTranslationLangGroup(
        translationPreferences: TranslationPreferences,
    ): Preference.PreferenceGroup {
        val fromLangs = TextRecognizerLanguage.entries.associate { it.name to it.label }
        val toLangs = TextTranslatorLanguage.entries.associate { it.name to it.label }
        return Preference.PreferenceGroup(
            title = stringResource(ATMR.strings.pref_group_setup),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.CustomPreference(
                    title = stringResource(ATMR.strings.pref_translate_from),
                ) {
                    val pref = translationPreferences.translateFromLanguage()
                    val value by pref.collectAsState()
                    val recentPref = translationPreferences.translationRecentLanguagesFrom()
                    val recentRaw by recentPref.collectAsState()
                    val recentLangs = remember(recentRaw) {
                        TranslationPreferences.decodeRecentLanguages(recentRaw)
                    }
                    SearchableListPreferenceWidget(
                        value = value,
                        title = stringResource(ATMR.strings.pref_translate_from),
                        subtitle = fromLangs[value],
                        icon = null,
                        entries = fromLangs,
                        recentItems = recentLangs,
                        onValueChange = { newValue ->
                            pref.set(newValue)
                            TextRecognizerLanguage.entries
                                .firstOrNull { it.name == newValue }
                                ?.let { OcrModelCatalog.selectedModel(translationPreferences, it) }
                            val updated = TranslationPreferences.encodeRecentLanguages(listOf(newValue) + recentLangs)
                            recentPref.set(updated)
                        },
                    )
                },
                Preference.PreferenceItem.CustomPreference(
                    title = stringResource(ATMR.strings.pref_ocr_model),
                ) {
                    val fromPref = translationPreferences.translateFromLanguage()
                    val fromValue by fromPref.collectAsState()
                    val language = remember(fromValue) {
                        TextRecognizerLanguage.entries.firstOrNull { it.name == fromValue }
                            ?: TextRecognizerLanguage.CHINESE
                    }
                    val ocrPref = remember(language) {
                        OcrModelCatalog.preferenceFor(translationPreferences, language)
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
                    val ocrEntries = remember(language) {
                        OcrModelCatalog.labelsFor(language)
                    }
                    ListPreferenceWidget(
                        value = selectedModel,
                        title = stringResource(ATMR.strings.pref_ocr_model),
                        subtitle = ocrEntries[selectedModel],
                        icon = null,
                        entries = ocrEntries,
                        onValueChange = { ocrPref.set(it) },
                    )
                },
                Preference.PreferenceItem.CustomPreference(
                    title = stringResource(ATMR.strings.pref_translate_to),
                ) {
                    val pref = translationPreferences.translateToLanguage()
                    val value by pref.collectAsState()
                    val recentPref = translationPreferences.translationRecentLanguagesTo()
                    val recentRaw by recentPref.collectAsState()
                    val recentLangs = remember(recentRaw) {
                        TranslationPreferences.decodeRecentLanguages(recentRaw)
                    }
                    SearchableListPreferenceWidget(
                        value = value,
                        title = stringResource(ATMR.strings.pref_translate_to),
                        subtitle = toLangs[value],
                        icon = null,
                        entries = toLangs,
                        recentItems = recentLangs,
                        onValueChange = { newValue ->
                            pref.set(newValue)
                            val updated = TranslationPreferences.encodeRecentLanguages(listOf(newValue) + recentLangs)
                            recentPref.set(updated)
                        },
                    )
                },
                Preference.PreferenceItem.ListPreference(
                    pref = translationPreferences.translationReadingOrder(),
                    title = "Reading Direction (Panel Sorting)",
                    entries = mapOf(
                        TranslationReadingOrder.AUTO to "Auto (Based on language)",
                        TranslationReadingOrder.RTL_MANGA to "Right-to-Left (Manga)",
                        TranslationReadingOrder.LTR_COMIC to "Left-to-Right (Comic)",
                    ).toImmutableMap(),
                ),
            ),
        )
    }

    /**
     * State-driven engine section. The returned items depend on the current
     * [TranslationEngineCategory] and (for AI) the selected provider, so this
     * group is rebuilt on every recomposition from live preference state.
     */
    @Composable
    private fun getEngineGroup(
        translationPreferences: TranslationPreferences,
    ): Preference.PreferenceGroup {
        val category by translationPreferences.translationEngineCategory().collectAsState()
        val typeEntries = TranslationEngineCategory.entries.associateWith { entry ->
            when (entry) {
                TranslationEngineCategory.STANDARD -> stringResource(ATMR.strings.pref_translation_type_standard)
                TranslationEngineCategory.AI_MODEL -> stringResource(ATMR.strings.pref_translation_type_ai)
            }
        }.toImmutableMap()

        val items = mutableListOf<Preference.PreferenceItem<out Any>>(
            Preference.PreferenceItem.ListPreference(
                pref = translationPreferences.translationEngineCategory(),
                title = stringResource(ATMR.strings.pref_translation_type),
                entries = typeEntries.toImmutableMap(),
            ),
        )

        when (category) {
            TranslationEngineCategory.STANDARD -> items.addAll(standardEngineItems(translationPreferences))
            TranslationEngineCategory.AI_MODEL -> items.addAll(aiEngineItems(translationPreferences))
        }

        return Preference.PreferenceGroup(
            title = stringResource(ATMR.strings.pref_group_engine),
            preferenceItems = persistentListOf(*items.toTypedArray()),
        )
    }

    @Composable
    private fun standardEngineItems(
        translationPreferences: TranslationPreferences,
    ): List<Preference.PreferenceItem<out Any>> {
        val engines = StandardTranslatorKind.entries
        val enginePref = translationPreferences.translationStandardEngine()
        val selectedEngine by enginePref.collectAsState()
        // DeepL is the only Standard engine that needs credentials. Collect its
        // key so the row stays reactive while DeepL is selected.
        val apiKeyPref = translationPreferences.translationDeeplApiKey()
        val apiKey by apiKeyPref.collectAsState()
        val deeplKeyTitle = stringResource(ATMR.strings.pref_deepl_api_key)
        val keySetLabel = stringResource(ATMR.strings.pref_ai_key_set)
        val keyNotSetLabel = stringResource(ATMR.strings.pref_ai_key_not_set)

        return buildList {
            add(
                Preference.PreferenceItem.ListPreference(
                    pref = enginePref,
                    title = stringResource(ATMR.strings.pref_standard_engine),
                    // The pref stores a StandardEngine; entries are keyed by the
                    // matching StandardEngine and labelled from StandardTranslatorKind.
                    entries = engines.associate { translator ->
                        StandardEngine.valueOf(translator.name) to translator.label
                    }.toImmutableMap(),
                ),
            )
            // Show the DeepL key row only when DeepL is selected (it is the only
            // Standard engine with credentials). Mirrors the AI-provider key row.
            if (selectedEngine == StandardEngine.DEEPL) {
                add(
                    Preference.PreferenceItem.CustomPreference(
                        title = deeplKeyTitle,
                    ) {
                        ApiKeyPreferenceWidget(
                            title = deeplKeyTitle,
                            apiKey = apiKey,
                            keySetLabel = keySetLabel,
                            keyNotSetLabel = keyNotSetLabel,
                            onApiKeyChange = { newKey -> apiKeyPref.set(newKey) },
                        )
                    },
                )
            }
        }
    }

    @Composable
    private fun aiEngineItems(
        translationPreferences: TranslationPreferences,
    ): List<Preference.PreferenceItem<out Any>> {
        val scope = rememberCoroutineScope()
        val providers = AiTranslatorKind.entries

        // Live AI-provider selection drives the API key / model / picker rows.
        val aiEngine by translationPreferences.translationAiEngine().collectAsState()
        val apiKeyPref = remember(aiEngine) { translationPreferences.translationAiApiKey(aiEngine) }
        val apiKey by apiKeyPref.collectAsState()
        val baseUrlPref = remember(aiEngine) { translationPreferences.translationAiBaseUrl(aiEngine) }
        val baseUrl by baseUrlPref?.collectAsState() ?: remember { mutableStateOf("") }
        val modelPref = remember(aiEngine) { translationPreferences.translationAiModel(aiEngine) }
        val currentModel by modelPref.collectAsState()
        val recentPref = remember(aiEngine) { translationPreferences.translationAiRecentModels(aiEngine) }
        val recentRaw by recentPref.collectAsState()
        val recentModels = remember(recentRaw) {
            TranslationPreferences.decodeRecentModels(recentRaw)
        }

        // Fetch state is scoped per provider; resetting it when the provider
        // changes avoids showing a stale model list for the wrong engine.
        var fetchState by remember(aiEngine) { mutableStateOf<AiModelListState>(AiModelListState.Idle) }

        val apiKeyTitle = when (aiEngine) {
            AiEngine.GEMINI -> stringResource(ATMR.strings.pref_ai_api_key_gemini)
            AiEngine.OPENROUTER -> stringResource(ATMR.strings.pref_ai_api_key_openrouter)
            AiEngine.DEEPSEEK -> stringResource(ATMR.strings.pref_ai_api_key_deepseek)
            AiEngine.LMSTUDIO -> stringResource(ATMR.strings.pref_ai_base_url_lmstudio)
        }
        val lmStudioBaseUrlTitle = stringResource(ATMR.strings.pref_ai_base_url_lmstudio)
        val keySetLabel = stringResource(ATMR.strings.pref_ai_key_set)
        val keyNotSetLabel = stringResource(ATMR.strings.pref_ai_key_not_set)
        val pickerTitle = stringResource(ATMR.strings.pref_engine_model)
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
                    is AiModelFetcher.Result.Success ->
                        AiModelListState.Loaded(result.models)
                    is AiModelFetcher.Result.InvalidKey ->
                        AiModelListState.Failed("Invalid or expired API key")
                    is AiModelFetcher.Result.NoModels ->
                        AiModelListState.Loaded(emptyList())
                    is AiModelFetcher.Result.Error ->
                        AiModelListState.Failed(result.message)
                }
            }
        }

        val onSelectModel: (String) -> Unit = { id ->
            modelPref.set(id)
            // Prepend to recent, collapse duplicates, cap to the configured limit.
            val updated = TranslationPreferences.encodeRecentModels(listOf(id) + recentModels)
            recentPref.set(updated)
        }

        val onManualModel: (String) -> Unit = { id ->
            modelPref.set(id)
            val updated = TranslationPreferences.encodeRecentModels(listOf(id) + recentModels)
            recentPref.set(updated)
        }

        return buildList {
            // AI provider selector
            add(
                Preference.PreferenceItem.ListPreference(
                    pref = translationPreferences.translationAiEngine(),
                    title = stringResource(ATMR.strings.pref_ai_provider),
                    entries = providers.associate { it.engine to it.label }.toImmutableMap(),
                ),
            )

            if (aiEngine == AiEngine.LMSTUDIO && baseUrlPref != null) {
                add(
                    Preference.PreferenceItem.CustomPreference(
                        title = lmStudioBaseUrlTitle,
                    ) {
                        EditTextPreferenceWidget(
                            title = lmStudioBaseUrlTitle,
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
                    },
                )
            } else {
                // Provider API key (masked, non-revealing subtitle)
                add(
                    Preference.PreferenceItem.CustomPreference(
                        title = apiKeyTitle,
                    ) {
                        ApiKeyPreferenceWidget(
                            title = apiKeyTitle,
                            apiKey = apiKey,
                            keySetLabel = keySetLabel,
                            keyNotSetLabel = keyNotSetLabel,
                            onApiKeyChange = { newKey -> apiKeyPref.set(newKey) },
                        )
                    },
                )
            }

            // Searchable model picker
            add(
                Preference.PreferenceItem.CustomPreference(
                    title = pickerTitle,
                ) {
                    AiModelPickerWidget(
                        title = pickerTitle,
                        currentModel = currentModel,
                        recentModels = recentModels,
                        listState = fetchState,
                        hasApiKey = hasConnection,
                        missingConnectionMessage = missingConnectionMessage,
                        onFetchModels = onFetch,
                        onSelectModel = onSelectModel,
                        onManualModel = onManualModel,
                    )
                },
            )

            // Shared AI generation params
            if (aiEngine == AiEngine.GEMINI) {
                add(
                    Preference.PreferenceItem.ListPreference(
                        pref = translationPreferences.translationGeminiThinkingMode(),
                        title = stringResource(ATMR.strings.pref_gemini_thinking_mode),
                        entries = mapOf(
                            GeminiThinkingMode.DISABLED to stringResource(
                                ATMR.strings.pref_gemini_thinking_disabled,
                            ),
                            GeminiThinkingMode.AUTO to stringResource(ATMR.strings.pref_gemini_thinking_auto),
                            GeminiThinkingMode.LOW to stringResource(ATMR.strings.pref_gemini_thinking_low),
                        ).toImmutableMap(),
                    ),
                )
            }
            add(
                Preference.PreferenceItem.EditTextPreference(
                    pref = translationPreferences.translationAiTemperature(),
                    title = stringResource(ATMR.strings.pref_engine_temperature),
                ),
            )
            add(
                Preference.PreferenceItem.EditTextPreference(
                    pref = translationPreferences.translationAiOutputTokens(),
                    title = stringResource(ATMR.strings.pref_engine_max_output),
                ),
            )
        }
    }
}
