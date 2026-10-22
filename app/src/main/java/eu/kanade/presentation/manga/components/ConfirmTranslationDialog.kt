package eu.kanade.presentation.manga.components

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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.presentation.components.ConfigSwitchRow
import eu.kanade.presentation.components.SearchableConfigSelectorBox
import eu.kanade.presentation.components.SelectorOption
import eu.kanade.presentation.components.TranslatorChoice
import eu.kanade.translation.engines.translator.AiTranslatorKind
import eu.kanade.translation.engines.translator.StandardTranslatorKind
import eu.kanade.translation.model.BatchExecutionMode
import eu.kanade.translation.model.TextRecognizerLanguage
import eu.kanade.translation.model.TextTranslatorLanguage
import eu.kanade.translation.presentation.TranslationSettingsSummary
import tachiyomi.domain.translation.AiEngine
import tachiyomi.domain.translation.StandardEngine
import tachiyomi.domain.translation.TranslationEngineCategory
import tachiyomi.domain.translation.TranslationPreferences
import tachiyomi.i18n.MR
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.components.LabeledCheckbox
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Modern interactive pre-configuration sheet for batch translation.
 * Matches the design specifications in media_1792648334499_21e9d6f7.png:
 * side-by-side language selectors, direct translator and model picker,
 * execution policy radio options, and background execution toggle.
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

    // Source language selection
    var selectedFromLangName by rememberSaveable {
        mutableStateOf(translationPreferences.translateFromLanguage().get())
    }
    // Target language selection
    var selectedToLangName by rememberSaveable {
        mutableStateOf(translationPreferences.translateToLanguage().get())
    }

    // Active engine selection
    var engineCategory by rememberSaveable {
        mutableStateOf(translationPreferences.translationEngineCategory().get())
    }
    var standardEngine by rememberSaveable {
        mutableStateOf(translationPreferences.translationStandardEngine().get())
    }
    var aiEngine by rememberSaveable {
        mutableStateOf(translationPreferences.translationAiEngine().get())
    }

    // Execution mode & background toggle
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

    val currentFromLabel = remember(selectedFromLangName) {
        TextRecognizerLanguage.entries.firstOrNull { it.name.equals(selectedFromLangName, true) }?.label
            ?: selectedFromLangName
    }
    val currentToLabel = remember(selectedToLangName) {
        TextTranslatorLanguage.entries.firstOrNull { it.name.equals(selectedToLangName, true) }?.label
            ?: selectedToLangName
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
            // Header: Title + Translate Icon
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(ATMR.strings.batch_translate_chapters),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = if (chapterNames.size == 1) {
                            chapterNames.first()
                        } else {
                            "${chapterNames.size} chapters selected"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    imageVector = Icons.Outlined.Translate,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
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
                        SearchableConfigSelectorBox(
                            selectedLabel = currentFromLabel,
                            options = fromOptions,
                            onSelect = { langName ->
                                selectedFromLangName = langName
                                translationPreferences.translateFromLanguage().set(langName)
                            },
                            placeholder = stringResource(ATMR.strings.pref_translate_from),
                            matchAnchorWidth = false,
                        )
                    }

                    // To Language Picker
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(ATMR.strings.pref_translate_to),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        SearchableConfigSelectorBox(
                            selectedLabel = currentToLabel,
                            options = toOptions,
                            onSelect = { langName ->
                                selectedToLangName = langName
                                translationPreferences.translateToLanguage().set(langName)
                            },
                            placeholder = stringResource(ATMR.strings.pref_translate_to),
                            matchAnchorWidth = false,
                        )
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
                    Text(
                        text = modelSubtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Execution Policies Section: Standard vs Only while charging
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(ATMR.strings.batch_execution_section),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = executionMode == BatchExecutionMode.STANDARD,
                            onClick = {
                                executionMode = BatchExecutionMode.STANDARD
                                translationPreferences.translationRequireCharging().set(false)
                            },
                            role = Role.RadioButton,
                        )
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = executionMode == BatchExecutionMode.STANDARD,
                        onClick = null,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(ATMR.strings.batch_execution_standard),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = executionMode == BatchExecutionMode.ONLY_WHILE_CHARGING,
                            onClick = {
                                executionMode = BatchExecutionMode.ONLY_WHILE_CHARGING
                                translationPreferences.translationRequireCharging().set(true)
                            },
                            role = Role.RadioButton,
                        )
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = executionMode == BatchExecutionMode.ONLY_WHILE_CHARGING,
                        onClick = null,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(ATMR.strings.batch_execution_charging_only),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                // Continue in background independent switch
                ConfigSwitchRow(
                    title = stringResource(ATMR.strings.batch_continue_background),
                    subtitle = stringResource(ATMR.strings.batch_background_progress_summary),
                    checked = continueInBackground,
                    onCheckedChange = { checked ->
                        continueInBackground = checked
                        translationPreferences.translationContinueInBackground().set(checked)
                    },
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            // Advanced settings row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onOpenSettings) {
                    Icon(
                        imageVector = Icons.Outlined.Tune,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(text = stringResource(ATMR.strings.translation_section_advanced))
                }
                LabeledCheckbox(
                    label = stringResource(ATMR.strings.translation_confirm_dont_show),
                    checked = !showAgain,
                    onCheckedChange = { checked -> onShowAgainChange(!checked) },
                )
            }

            // Actions row: Cancel & Translate
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = onDismissRequest,
                ) {
                    Text(text = stringResource(MR.strings.action_cancel))
                }
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        onDismissRequest()
                        onConfirm()
                    },
                ) {
                    Text(text = stringResource(ATMR.strings.manga_translate))
                }
            }
        }
    }
}
