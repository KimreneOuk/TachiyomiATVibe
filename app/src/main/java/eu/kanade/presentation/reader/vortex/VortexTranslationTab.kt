package eu.kanade.presentation.reader.vortex

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
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
import eu.kanade.presentation.components.SearchableConfigSelectorBox
import eu.kanade.presentation.components.SelectorOption
import eu.kanade.presentation.components.TranslatorChoice
import eu.kanade.presentation.components.vortex.VortexLanguagePairSelector
import eu.kanade.presentation.components.vortex.VortexPreset
import eu.kanade.presentation.components.vortex.VortexPresetHeroBar
import eu.kanade.presentation.components.vortex.VortexSectionHeading
import eu.kanade.presentation.components.vortex.VortexSelectionTile
import eu.kanade.presentation.components.vortex.VortexSwitchSlate
import eu.kanade.presentation.components.vortex.VortexTokens
import eu.kanade.presentation.components.vortex.resolveActivePreset
import eu.kanade.tachiyomi.ui.reader.TranslationSettingsState
import eu.kanade.translation.engines.translator.AiTranslatorKind
import eu.kanade.translation.engines.translator.StandardTranslatorKind
import eu.kanade.translation.model.TextRecognizerLanguage
import tachiyomi.domain.translation.AiEngine
import tachiyomi.domain.translation.NeuralInpaintModel
import tachiyomi.domain.translation.OcrModel
import tachiyomi.domain.translation.StandardEngine
import tachiyomi.domain.translation.TranslationEngineCategory
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Vortex AI Engine tab with 3-tier master presets, fluid language pairs, and live feedback.
 */
@Composable
fun VortexTranslationTab(
    state: TranslationSettingsState,
    onTranslationEnabledChange: (Boolean) -> Unit,
    onAutoTranslateChange: (Boolean) -> Unit,
    onTranslateFromLanguageChange: (String) -> Unit,
    onTranslateToLanguageChange: (String) -> Unit,
    onOcrModelChange: (OcrModel) -> Unit,
    onTranslationInpaintingModeChange: (String) -> Unit,
    onTranslationInpaintingNeuralModelChange: (NeuralInpaintModel) -> Unit,
    onTranslationEngineCategoryChange: (TranslationEngineCategory) -> Unit,
    onTranslationStandardEngineChange: (StandardEngine) -> Unit,
    onTranslationAiEngineChange: (AiEngine) -> Unit,
    onTranslationAiApiKeyChange: (String) -> Unit,
    onTranslationAiModelChange: (String) -> Unit,
    onResetTranslationData: (Boolean) -> Unit,
    onResetInpaintData: () -> Unit,
    onResetOcrData: () -> Unit,
    onResetEverything: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var advancedExpanded by rememberSaveable { mutableStateOf(false) }

    val activePreset = remember(state.ocrModel, state.inpaintingNeuralModel) {
        resolveActivePreset(state.ocrModel, state.inpaintingNeuralModel)
    }

    val fromOptions = remember {
        TextRecognizerLanguage.entries.map { lang ->
            SelectorOption(value = lang.name, label = lang.label)
        }
    }
    val toOptions = remember {
        eu.kanade.translation.model.TextTranslatorLanguage.entries.map { lang ->
            SelectorOption(value = lang.name, label = lang.label)
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

    val currentTranslatorLabel = remember(state.engineCategory, state.standardEngine, state.aiEngine) {
        when (state.engineCategory) {
            TranslationEngineCategory.STANDARD -> StandardTranslatorKind.entries
                .firstOrNull { it.name == state.standardEngine.name }?.label ?: state.standardEngine.name
            TranslationEngineCategory.AI_MODEL -> AiTranslatorKind.entries
                .firstOrNull { it.engine == state.aiEngine }?.label ?: state.aiEngine.name
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        // Master Quick Presets
        VortexSectionHeading(title = "Master Presets")
        VortexPresetHeroBar(
            currentPreset = activePreset,
            onApplyPreset = { preset ->
                when (preset) {
                    VortexPreset.SPEED -> {
                        onOcrModelChange(OcrModel.PADDLEOCR_V6_SMALL)
                        onTranslationInpaintingNeuralModelChange(NeuralInpaintModel.AOT_GAN)
                    }
                    VortexPreset.BALANCED -> {
                        onOcrModelChange(OcrModel.PADDLEOCR_V6_SMALL)
                        onTranslationInpaintingNeuralModelChange(NeuralInpaintModel.LAMA_LITERT_GPU)
                    }
                    VortexPreset.QUALITY -> {
                        onOcrModelChange(OcrModel.MANGAOCR)
                        onTranslationInpaintingNeuralModelChange(NeuralInpaintModel.LAMA_LITERT_GPU)
                    }
                    VortexPreset.CUSTOM -> {}
                }
            },
            modifier = Modifier.padding(horizontal = 20.dp),
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Language Pair Section
        VortexSectionHeading(title = stringResource(ATMR.strings.translation_section_languages))
        VortexLanguagePairSelector(
            fromLanguage = state.translateFromLanguage,
            toLanguage = state.translateToLanguage,
            onFromLanguageChange = onTranslateFromLanguageChange,
            onToLanguageChange = onTranslateToLanguageChange,
            fromOptions = fromOptions,
            toOptions = toOptions,
            modifier = Modifier.padding(horizontal = 20.dp),
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Translator Engine Selection
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
                            onTranslationEngineCategoryChange(TranslationEngineCategory.STANDARD)
                            onTranslationStandardEngineChange(choice.engine)
                        }
                        is TranslatorChoice.Ai -> {
                            onTranslationEngineCategoryChange(TranslationEngineCategory.AI_MODEL)
                            onTranslationAiEngineChange(choice.engine)
                        }
                    }
                },
                placeholder = stringResource(ATMR.strings.translation_section_translator),
                matchAnchorWidth = true,
            )
            if (state.engineCategory == TranslationEngineCategory.AI_MODEL && state.aiModel.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Model: ${state.aiModel}",
                    fontSize = 11.sp,
                    color = VortexTokens.TextMuted,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Pipeline Automation & Inpainting Switches
        VortexSectionHeading(title = "Pipeline Controls")
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            VortexSwitchSlate(
                icon = Icons.Outlined.Translate,
                title = stringResource(ATMR.strings.pref_translation_enabled),
                subtitle = "Active real-time comic translation pipeline",
                checked = state.enabled,
                onCheckedChange = onTranslationEnabledChange,
            )

            VortexSwitchSlate(
                icon = Icons.Outlined.AutoAwesome,
                title = stringResource(ATMR.strings.pref_auto_translate),
                subtitle = "Translate new pages automatically as you read",
                checked = state.autoTranslate,
                onCheckedChange = onAutoTranslateChange,
            )

            VortexSwitchSlate(
                icon = Icons.Outlined.CleaningServices,
                title = "Speech Bubble Inpainting",
                subtitle = "Clean original text bubbles beneath translation",
                checked = state.inpaintingMode.equals("NEURAL", ignoreCase = true),
                onCheckedChange = { checked ->
                    onTranslationInpaintingModeChange(if (checked) "NEURAL" else "OFF")
                },
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Advanced Settings Accordion
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { advancedExpanded = !advancedExpanded }
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Tune,
                contentDescription = null,
                tint = VortexTokens.AccentPrimary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(ATMR.strings.translation_section_advanced),
                color = VortexTokens.TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (advancedExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = VortexTokens.TextMuted,
            )
        }

        AnimatedVisibility(
            visible = advancedExpanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // OCR Model Selector
                Text(text = "OCR ENGINE", color = VortexTokens.TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    VortexSelectionTile(
                        selected = state.ocrModel == OcrModel.PADDLEOCR_V6_SMALL,
                        onClick = { onOcrModelChange(OcrModel.PADDLEOCR_V6_SMALL) },
                        title = "PaddleOCR",
                        subtitle = "Fast On-Device",
                        modifier = Modifier.weight(1f),
                    )
                    VortexSelectionTile(
                        selected = state.ocrModel == OcrModel.MANGAOCR,
                        onClick = { onOcrModelChange(OcrModel.MANGAOCR) },
                        title = "Manga-OCR",
                        subtitle = "High Accuracy",
                        modifier = Modifier.weight(1f),
                    )
                }

                // Inpainting Model Selector
                Text(text = "INPAINTING MODEL", color = VortexTokens.TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    VortexSelectionTile(
                        selected = state.inpaintingNeuralModel == NeuralInpaintModel.LAMA_LITERT_GPU,
                        onClick = { onTranslationInpaintingNeuralModelChange(NeuralInpaintModel.LAMA_LITERT_GPU) },
                        title = "Manga LaMa",
                        subtitle = "Full Inpainting",
                        modifier = Modifier.weight(1f),
                    )
                    VortexSelectionTile(
                        selected = state.inpaintingNeuralModel == NeuralInpaintModel.AOT_GAN,
                        onClick = { onTranslationInpaintingNeuralModelChange(NeuralInpaintModel.AOT_GAN) },
                        title = "AOT-GAN",
                        subtitle = "Fast 512px",
                        modifier = Modifier.weight(1f),
                    )
                }

                // Cache Reset Actions
                Text(text = "CACHE MANAGEMENT", color = VortexTokens.TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = onResetInpaintData,
                        shape = VortexTokens.ShapeSquircle,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(text = "Reset Inpaint", fontSize = 11.sp, color = VortexTokens.TextMuted)
                    }
                    OutlinedButton(
                        onClick = onResetOcrData,
                        shape = VortexTokens.ShapeSquircle,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(text = "Reset OCR", fontSize = 11.sp, color = VortexTokens.TextMuted)
                    }
                    OutlinedButton(
                        onClick = onResetEverything,
                        shape = VortexTokens.ShapeSquircle,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(text = "Reset All", fontSize = 11.sp, color = VortexTokens.TextMuted)
                    }
                }
            }
        }
    }
}
