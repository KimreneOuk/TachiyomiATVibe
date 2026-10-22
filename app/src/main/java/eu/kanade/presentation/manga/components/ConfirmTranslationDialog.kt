package eu.kanade.presentation.manga.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Power
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.presentation.components.SearchableConfigSelectorBox
import eu.kanade.presentation.components.SelectorOption
import eu.kanade.presentation.components.TranslatorChoice
import eu.kanade.presentation.components.vortex.VortexLanguagePairSelector
import eu.kanade.presentation.components.vortex.VortexPreset
import eu.kanade.presentation.components.vortex.VortexPresetHeroBar
import eu.kanade.presentation.components.vortex.VortexSectionHeading
import eu.kanade.presentation.components.vortex.VortexSelectionTile
import eu.kanade.presentation.components.vortex.VortexSheetHeader
import eu.kanade.presentation.components.vortex.VortexSwitchSlate
import eu.kanade.presentation.components.vortex.VortexTokens
import eu.kanade.presentation.components.vortex.resolveActivePreset
import eu.kanade.translation.engines.translator.AiTranslatorKind
import eu.kanade.translation.engines.translator.StandardTranslatorKind
import eu.kanade.translation.model.BatchExecutionMode
import eu.kanade.translation.model.TextRecognizerLanguage
import eu.kanade.translation.model.TextTranslatorLanguage
import eu.kanade.translation.presentation.TranslationSettingsSummary
import tachiyomi.domain.translation.AiEngine
import tachiyomi.domain.translation.NeuralInpaintModel
import tachiyomi.domain.translation.OcrModel
import tachiyomi.domain.translation.StandardEngine
import tachiyomi.domain.translation.TranslationEngineCategory
import tachiyomi.domain.translation.TranslationPreferences
import tachiyomi.i18n.MR
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.components.LabeledCheckbox
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Vortex-styled interactive pre-configuration sheet for batch translation.
 * Features 3-tier master presets, fluid language pair selector, and live feedback.
 */
@Composable
fun ConfirmTranslationDialog(
    chapterNames: List<String>,
    summary: TranslationSettingsSummary,
    showAgain: Boolean,
    onShowAgainChange: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    onConfirm: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    val translationPreferences = remember { Injekt.get<TranslationPreferences>() }

    var selectedFromLangName by rememberSaveable {
        mutableStateOf(translationPreferences.translateFromLanguage().get())
    }
    var selectedToLangName by rememberSaveable {
        mutableStateOf(translationPreferences.translateToLanguage().get())
    }

    var ocrModel by rememberSaveable {
        mutableStateOf(translationPreferences.translationOcrModel(selectedFromLangName).get())
    }
    var inpaintModel by rememberSaveable {
        mutableStateOf(translationPreferences.translationInpaintingNeuralModel().get())
    }

    var engineCategory by rememberSaveable {
        mutableStateOf(translationPreferences.translationEngineCategory().get())
    }
    var standardEngine by rememberSaveable {
        mutableStateOf(translationPreferences.translationStandardEngine().get())
    }
    var aiEngine by rememberSaveable {
        mutableStateOf(translationPreferences.translationAiEngine().get())
    }

    var executionMode by rememberSaveable {
        mutableStateOf(
            if (translationPreferences.translationRequireCharging().get()) {
                BatchExecutionMode.ONLY_WHILE_CHARGING
            } else {
                BatchExecutionMode.STANDARD
            },
        )
    }
    var continueInBackground by rememberSaveable {
        mutableStateOf(translationPreferences.translationContinueInBackground().get())
    }

    val activePreset = remember(ocrModel, inpaintModel) {
        resolveActivePreset(ocrModel, inpaintModel)
    }

    val currentTranslatorLabel = remember(engineCategory, standardEngine, aiEngine) {
        when (engineCategory) {
            TranslationEngineCategory.STANDARD -> StandardTranslatorKind.entries
                .firstOrNull { it.name == standardEngine.name }?.label ?: standardEngine.name
            TranslationEngineCategory.AI_MODEL -> AiTranslatorKind.entries
                .firstOrNull { it.engine == aiEngine }?.label ?: aiEngine.name
        }
    }

    val currentModelSubtitle = remember(engineCategory, aiEngine) {
        if (engineCategory == TranslationEngineCategory.AI_MODEL) {
            val model = translationPreferences.translationAiModel(aiEngine).get()
            if (model.isNotBlank()) "Model: $model" else null
        } else {
            null
        }
    }

    val fromOptions = remember {
        TextRecognizerLanguage.entries.map { lang ->
            SelectorOption(
                value = lang.name,
                label = lang.label,
            )
        }
    }
    val toOptions = remember {
        TextTranslatorLanguage.entries.map { lang ->
            SelectorOption(
                value = lang.name,
                label = lang.label,
            )
        }
    }
    val standardSubtitle = stringResource(ATMR.strings.pref_translation_type_standard)
    val aiSubtitle = stringResource(ATMR.strings.pref_translation_type_ai)
    val translatorOptions = remember(standardSubtitle, aiSubtitle) {
        val standard: List<SelectorOption<TranslatorChoice>> = StandardTranslatorKind.entries.map { kind ->
            SelectorOption(
                value = TranslatorChoice.Standard(StandardEngine.valueOf(kind.name)),
                label = kind.label,
                subtitle = standardSubtitle,
            )
        }
        val ai: List<SelectorOption<TranslatorChoice>> = AiTranslatorKind.entries.map { kind ->
            SelectorOption(
                value = TranslatorChoice.Ai(kind.engine),
                label = kind.label,
                subtitle = aiSubtitle,
            )
        }
        standard + ai
    }

    AdaptiveSheet(onDismissRequest = onDismissRequest) {
        Surface(
            color = VortexTokens.SheetBackground,
            shape = VortexTokens.ShapeSheet,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 24.dp),
            ) {
                // Header Row
                VortexSheetHeader(
                    icon = Icons.Outlined.Translate,
                    title = stringResource(ATMR.strings.batch_translate_chapters),
                    subtitle = if (chapterNames.size == 1) {
                        chapterNames.first()
                    } else {
                        "${chapterNames.size} chapters selected"
                    },
                    onClose = onDismissRequest,
                )

                // Master Quick Presets
                VortexSectionHeading(title = "Master Presets")
                VortexPresetHeroBar(
                    currentPreset = activePreset,
                    onApplyPreset = { preset ->
                        when (preset) {
                            VortexPreset.SPEED -> {
                                ocrModel = OcrModel.PADDLEOCR_V6_SMALL
                                inpaintModel = NeuralInpaintModel.AOT_GAN
                            }
                            VortexPreset.BALANCED -> {
                                ocrModel = OcrModel.PADDLEOCR_V6_SMALL
                                inpaintModel = NeuralInpaintModel.LAMA_LITERT_GPU
                            }
                            VortexPreset.QUALITY -> {
                                ocrModel = OcrModel.MANGAOCR
                                inpaintModel = NeuralInpaintModel.LAMA_LITERT_GPU
                            }
                            VortexPreset.CUSTOM -> {}
                        }
                        translationPreferences.translationOcrModel(selectedFromLangName).set(ocrModel)
                        translationPreferences.translationInpaintingNeuralModel().set(inpaintModel)
                    },
                    modifier = Modifier.padding(horizontal = 20.dp),
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Language Pair Section
                VortexSectionHeading(title = stringResource(ATMR.strings.translation_section_languages))
                VortexLanguagePairSelector(
                    fromLanguage = selectedFromLangName,
                    toLanguage = selectedToLangName,
                    onFromLanguageChange = { lang ->
                        selectedFromLangName = lang
                        translationPreferences.translateFromLanguage().set(lang)
                    },
                    onToLanguageChange = { lang ->
                        selectedToLangName = lang
                        translationPreferences.translateToLanguage().set(lang)
                    },
                    fromOptions = fromOptions,
                    toOptions = toOptions,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Translator Engine Section
                VortexSectionHeading(title = stringResource(ATMR.strings.translation_section_translator))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                ) {
                    SearchableConfigSelectorBox(
                        selectedLabel = currentTranslatorLabel,
                        options = translatorOptions,
                        onSelect = { choice ->
                            when (choice) {
                                is TranslatorChoice.Standard -> {
                                    engineCategory = TranslationEngineCategory.STANDARD
                                    standardEngine = choice.engine
                                    translationPreferences.translationEngineCategory().set(engineCategory)
                                    translationPreferences.translationStandardEngine().set(standardEngine)
                                }
                                is TranslatorChoice.Ai -> {
                                    engineCategory = TranslationEngineCategory.AI_MODEL
                                    aiEngine = choice.engine
                                    translationPreferences.translationEngineCategory().set(engineCategory)
                                    translationPreferences.translationAiEngine().set(aiEngine)
                                }
                            }
                        },
                        placeholder = stringResource(ATMR.strings.translation_section_translator),
                        matchAnchorWidth = true,
                    )
                    currentModelSubtitle?.let { modelSubtitle ->
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = modelSubtitle,
                            fontSize = 11.sp,
                            color = VortexTokens.TextMuted,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Execution Policy
                VortexSectionHeading(title = stringResource(ATMR.strings.batch_execution_section))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    VortexSelectionTile(
                        selected = executionMode == BatchExecutionMode.STANDARD,
                        onClick = {
                            executionMode = BatchExecutionMode.STANDARD
                            translationPreferences.translationRequireCharging().set(false)
                        },
                        title = stringResource(ATMR.strings.batch_execution_standard),
                        icon = Icons.Outlined.Bolt,
                        modifier = Modifier.weight(1f),
                    )
                    VortexSelectionTile(
                        selected = executionMode == BatchExecutionMode.ONLY_WHILE_CHARGING,
                        onClick = {
                            executionMode = BatchExecutionMode.ONLY_WHILE_CHARGING
                            translationPreferences.translationRequireCharging().set(true)
                        },
                        title = stringResource(ATMR.strings.batch_execution_charging_only),
                        icon = Icons.Outlined.Power,
                        modifier = Modifier.weight(1f),
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Background Execution Switch Slate
                VortexSwitchSlate(
                    icon = Icons.Outlined.Sync,
                    title = stringResource(ATMR.strings.batch_continue_background),
                    subtitle = stringResource(ATMR.strings.batch_background_progress_summary),
                    checked = continueInBackground,
                    onCheckedChange = { checked ->
                        continueInBackground = checked
                        translationPreferences.translationContinueInBackground().set(checked)
                    },
                    modifier = Modifier.padding(horizontal = 20.dp),
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Advanced link & Don't show again
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Outlined.Tune,
                            contentDescription = null,
                            tint = VortexTokens.AccentPrimary,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(ATMR.strings.translation_section_advanced),
                            color = VortexTokens.AccentPrimary,
                            fontSize = 12.sp,
                        )
                    }
                    LabeledCheckbox(
                        label = stringResource(ATMR.strings.translation_confirm_dont_show),
                        checked = !showAgain,
                        onCheckedChange = { checked -> onShowAgainChange(!checked) },
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Primary CTA Button
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        onClick = onDismissRequest,
                        shape = VortexTokens.ShapeSquircle,
                        modifier = Modifier.height(48.dp),
                    ) {
                        Text(
                            text = stringResource(MR.strings.action_cancel),
                            color = VortexTokens.TextMuted,
                        )
                    }
                    Button(
                        onClick = {
                            onDismissRequest()
                            onConfirm()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = VortexTokens.AccentPrimary,
                            contentColor = VortexTokens.TextOnAccent,
                        ),
                        shape = VortexTokens.ShapeSquircle,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                    ) {
                        Text(
                            text = if (chapterNames.size == 1) {
                                stringResource(ATMR.strings.manga_translate)
                            } else {
                                "Start Batch Translation (${chapterNames.size})"
                            },
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                        )
                    }
                }
            }
        }
    }
}
