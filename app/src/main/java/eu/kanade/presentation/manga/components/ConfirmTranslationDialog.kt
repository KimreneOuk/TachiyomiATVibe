package eu.kanade.presentation.manga.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.presentation.components.SearchableConfigSelectorBox
import eu.kanade.presentation.components.SelectorOption
import eu.kanade.presentation.components.designsystem.DesignTokens
import eu.kanade.presentation.components.designsystem.LanguagePairSelector
import eu.kanade.presentation.components.designsystem.SectionHeading
import eu.kanade.presentation.components.designsystem.SegmentedPillBar
import eu.kanade.presentation.components.designsystem.SelectionTile
import eu.kanade.presentation.components.designsystem.SheetHeader
import eu.kanade.presentation.translation.aiProviderOptions
import eu.kanade.presentation.translation.neuralInpaintOptions
import eu.kanade.presentation.translation.ocrModelOptions
import eu.kanade.presentation.translation.standardEngineOptions
import eu.kanade.translation.engines.translator.AiTranslatorKind
import eu.kanade.translation.engines.translator.StandardTranslatorKind
import eu.kanade.translation.model.TextRecognizerLanguage
import eu.kanade.translation.model.TextTranslatorLanguage
import tachiyomi.domain.translation.TranslationEngineCategory
import tachiyomi.domain.translation.TranslationPreferences
import tachiyomi.i18n.MR
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.components.LabeledCheckbox
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Interactive pre-configuration sheet for batch translation, mirroring the
 * reader sheet sections: languages, translator, vision and inpainting.
 */
@Composable
fun ConfirmTranslationDialog(
    chapterNames: List<String>,
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

    var engineCategory by rememberSaveable {
        mutableStateOf(translationPreferences.translationEngineCategory().get())
    }
    var standardEngine by rememberSaveable {
        mutableStateOf(translationPreferences.translationStandardEngine().get())
    }
    var aiEngine by rememberSaveable {
        mutableStateOf(translationPreferences.translationAiEngine().get())
    }
    var ocrModel by remember(selectedFromLangName) {
        mutableStateOf(translationPreferences.translationOcrModel(selectedFromLangName).get())
    }
    var inpaintingMode by rememberSaveable {
        mutableStateOf(translationPreferences.translationInpaintingMode().get())
    }
    var neuralModel by rememberSaveable {
        mutableStateOf(translationPreferences.translationInpaintingNeuralModel().get())
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

    val standardEngineLabel = remember(standardEngine) {
        StandardTranslatorKind.entries.firstOrNull { it.name == standardEngine.name }?.label
            ?: standardEngine.name
    }
    val aiEngineLabel = remember(aiEngine) {
        AiTranslatorKind.entries.firstOrNull { it.engine == aiEngine }?.label ?: aiEngine.name
    }
    val aiModelSubtitle = remember(engineCategory, aiEngine) {
        if (engineCategory == TranslationEngineCategory.AI_MODEL) {
            translationPreferences.translationAiModel(aiEngine).get().takeIf { it.isNotBlank() }
                ?.let { "Model: $it" }
        } else {
            null
        }
    }

    val ocrOptions = ocrModelOptions()
    val neuralOptions = neuralInpaintOptions()
    val currentInpaint = inpaintingMode.ifEmpty { "QUALITY" }

    AdaptiveSheet(onDismissRequest = onDismissRequest) {
        Surface(
            color = DesignTokens.SheetBackground,
            shape = DesignTokens.ShapeSheet,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 24.dp),
            ) {
                SheetHeader(
                    icon = Icons.Outlined.Translate,
                    title = stringResource(ATMR.strings.batch_translate_chapters),
                    subtitle = if (chapterNames.size == 1) {
                        chapterNames.first()
                    } else {
                        "${chapterNames.size} chapters selected"
                    },
                    onClose = onDismissRequest,
                )

                Spacer(modifier = Modifier.height(16.dp))

                SectionHeading(title = stringResource(ATMR.strings.translation_section_languages))
                LanguagePairSelector(
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

                SectionHeading(title = stringResource(ATMR.strings.translation_section_translator))
                SegmentedPillBar(
                    tabs = listOf(
                        stringResource(ATMR.strings.reader_translation_engine_standard),
                        stringResource(ATMR.strings.reader_translation_engine_ai),
                    ),
                    selectedIndex = if (engineCategory == TranslationEngineCategory.AI_MODEL) 1 else 0,
                    onTabSelected = { index ->
                        val category =
                            if (index == 1) TranslationEngineCategory.AI_MODEL else TranslationEngineCategory.STANDARD
                        if (category != engineCategory) {
                            engineCategory = category
                            translationPreferences.translationEngineCategory().set(category)
                        }
                    },
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .clip(DesignTokens.ShapeCard)
                        .background(DesignTokens.CardFill)
                        .border(1.dp, DesignTokens.CardBorder, DesignTokens.ShapeCard)
                        .padding(14.dp),
                ) {
                    if (engineCategory == TranslationEngineCategory.STANDARD) {
                        SearchableConfigSelectorBox(
                            selectedLabel = standardEngineLabel,
                            options = standardEngineOptions,
                            onSelect = { engine ->
                                standardEngine = engine
                                translationPreferences.translationStandardEngine().set(engine)
                            },
                            placeholder = stringResource(ATMR.strings.translation_section_translator),
                            matchAnchorWidth = true,
                        )
                    } else {
                        SearchableConfigSelectorBox(
                            selectedLabel = aiEngineLabel,
                            options = aiProviderOptions,
                            onSelect = { engine ->
                                aiEngine = engine
                                translationPreferences.translationAiEngine().set(engine)
                            },
                            placeholder = stringResource(ATMR.strings.translation_section_translator),
                            matchAnchorWidth = true,
                        )
                        aiModelSubtitle?.let { modelSubtitle ->
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = modelSubtitle,
                                fontSize = 11.sp,
                                color = DesignTokens.TextMuted,
                                modifier = Modifier.padding(start = 4.dp),
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                SectionHeading(title = stringResource(ATMR.strings.reader_translation_section_vision))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .clip(DesignTokens.ShapeCard)
                        .background(DesignTokens.CardFill)
                        .border(1.dp, DesignTokens.CardBorder, DesignTokens.ShapeCard)
                        .padding(14.dp),
                ) {
                    SearchableConfigSelectorBox(
                        selectedLabel = ocrOptions.firstOrNull { it.value == ocrModel }?.label
                            ?: ocrModel.name,
                        options = ocrOptions,
                        onSelect = { model ->
                            ocrModel = model
                            translationPreferences.translationOcrModel(selectedFromLangName).set(model)
                        },
                        placeholder = stringResource(ATMR.strings.pref_ocr_model),
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                SectionHeading(title = stringResource(ATMR.strings.reader_translation_section_inpainting))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SelectionTile(
                        selected = currentInpaint.equals("FAST", ignoreCase = true),
                        onClick = {
                            inpaintingMode = "FAST"
                            translationPreferences.translationInpaintingMode().set("FAST")
                        },
                        title = "FAST",
                        modifier = Modifier.weight(1f),
                    )
                    SelectionTile(
                        selected = currentInpaint.equals("BALANCE", ignoreCase = true),
                        onClick = {
                            inpaintingMode = "BALANCE"
                            translationPreferences.translationInpaintingMode().set("BALANCE")
                        },
                        title = "BALANCE",
                        modifier = Modifier.weight(1f),
                    )
                    SelectionTile(
                        selected = currentInpaint.equals("QUALITY", ignoreCase = true),
                        onClick = {
                            inpaintingMode = "QUALITY"
                            translationPreferences.translationInpaintingMode().set("QUALITY")
                        },
                        title = "QUALITY",
                        modifier = Modifier.weight(1f),
                    )
                }
                AnimatedVisibility(
                    visible = !currentInpaint.equals("FAST", ignoreCase = true),
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(DesignTokens.ShapeCard)
                            .background(DesignTokens.CardFill)
                            .border(1.dp, DesignTokens.CardBorder, DesignTokens.ShapeCard)
                            .padding(14.dp),
                    ) {
                        SearchableConfigSelectorBox(
                            selectedLabel = neuralOptions.firstOrNull { it.value == neuralModel }?.label
                                ?: neuralModel.name,
                            options = neuralOptions,
                            onSelect = { model ->
                                neuralModel = model
                                translationPreferences.translationInpaintingNeuralModel().set(model)
                            },
                            placeholder = "Select Neural Model",
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

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
                            tint = DesignTokens.AccentPrimary,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(ATMR.strings.translation_section_advanced),
                            color = DesignTokens.AccentPrimary,
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
                        shape = DesignTokens.ShapeSquircle,
                        modifier = Modifier.height(48.dp),
                    ) {
                        Text(
                            text = stringResource(MR.strings.action_cancel),
                            color = DesignTokens.TextMuted,
                        )
                    }
                    Button(
                        onClick = {
                            onDismissRequest()
                            onConfirm()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = DesignTokens.AccentPrimary,
                            contentColor = DesignTokens.TextOnAccent,
                        ),
                        shape = DesignTokens.ShapeSquircle,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                    ) {
                        Text(
                            text = stringResource(ATMR.strings.batch_start_cta, chapterNames.size),
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                        )
                    }
                }
            }
        }
    }
}
