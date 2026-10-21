package eu.kanade.presentation.reader

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.more.settings.widget.SearchableListPreferenceWidget
import eu.kanade.presentation.more.settings.widget.SwitchPreferenceWidget
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import eu.kanade.tachiyomi.ui.reader.TranslationSettingsState
import eu.kanade.translation.engines.translator.AiTranslatorKind
import eu.kanade.translation.engines.translator.StandardTranslatorKind
import eu.kanade.translation.model.TextRecognizerLanguage
import eu.kanade.translation.model.TextTranslatorLanguage
import tachiyomi.domain.translation.AiEngine
import tachiyomi.domain.translation.TranslationEngineCategory
import tachiyomi.i18n.at.ATMR
import kotlinx.collections.immutable.toImmutableMap
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Default landing page of the translation settings sheet: the five answers a
 * reader needs (enabled, auto, from, to, which translator) plus the Advanced
 * directory entry. Every control binds to the sheet's existing callbacks.
 */
@Composable
internal fun ColumnScope.TranslationGeneralSection(
    state: TranslationSettingsState,
    onTranslationEnabledChange: (Boolean) -> Unit,
    onAutoTranslateChange: (Boolean) -> Unit,
    onTranslateFromLanguageChange: (String) -> Unit,
    onTranslateToLanguageChange: (String) -> Unit,
    onOpenTranslator: () -> Unit,
    onOpenAdvanced: () -> Unit,
) {
    SwitchPreferenceWidget(
        title = stringResource(ATMR.strings.pref_translation_enabled),
        checked = state.enabled,
        onCheckedChanged = onTranslationEnabledChange,
    )
    SwitchPreferenceWidget(
        title = stringResource(ATMR.strings.pref_auto_translate),
        subtitle = stringResource(ATMR.strings.pref_auto_translate_summary),
        checked = state.autoTranslate,
        onCheckedChanged = onAutoTranslateChange,
    )

    TranslationSectionLabel(stringResource(ATMR.strings.translation_section_languages))
    val fromLangs = remember {
        TextRecognizerLanguage.entries.associate { it.name to it.label }.toImmutableMap()
    }
    val toLangs = remember {
        TextTranslatorLanguage.entries.associate { it.name to it.label }.toImmutableMap()
    }
    SearchableListPreferenceWidget(
        value = state.translateFromLanguage,
        title = stringResource(ATMR.strings.pref_translate_from),
        subtitle = fromLangs[state.translateFromLanguage],
        icon = null,
        entries = fromLangs,
        recentItems = state.translationRecentLanguagesFrom,
        onValueChange = onTranslateFromLanguageChange,
    )
    SearchableListPreferenceWidget(
        value = state.translateToLanguage,
        title = stringResource(ATMR.strings.pref_translate_to),
        subtitle = toLangs[state.translateToLanguage],
        icon = null,
        entries = toLangs,
        recentItems = state.translationRecentLanguagesTo,
        onValueChange = onTranslateToLanguageChange,
    )

    TranslationSectionLabel(stringResource(ATMR.strings.translation_section_translator))
    TextPreferenceWidget(
        title = stringResource(ATMR.strings.translation_engine_active_title),
        subtitle = translationEngineSummary(state),
        onPreferenceClick = onOpenTranslator,
        widget = {
            Icon(
                imageVector = Icons.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )

    TextPreferenceWidget(
        title = stringResource(ATMR.strings.translation_section_advanced),
        subtitle = stringResource(ATMR.strings.translation_section_advanced_summary),
        icon = Icons.Outlined.Tune,
        iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
        onPreferenceClick = onOpenAdvanced,
        widget = {
            Icon(
                imageVector = Icons.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

/**
 * One-line, always-current description of the active translator. Derived only
 * from existing state — no provider/model initialization happens here.
 */
@Composable
private fun translationEngineSummary(state: TranslationSettingsState): String {
    val typeLabel = when (state.engineCategory) {
        TranslationEngineCategory.STANDARD -> stringResource(ATMR.strings.pref_translation_type_standard)
        TranslationEngineCategory.AI_MODEL -> stringResource(ATMR.strings.pref_translation_type_ai)
    }
    val detail = when (state.engineCategory) {
        TranslationEngineCategory.STANDARD -> StandardTranslatorKind.valueOf(state.standardEngine.name).label
        TranslationEngineCategory.AI_MODEL -> {
            val kind = AiTranslatorKind.entries.first { it.engine == state.aiEngine }
            val configured = if (state.aiEngine == AiEngine.LMSTUDIO) {
                state.aiBaseUrl.isNotBlank()
            } else {
                state.aiApiKey.isNotBlank()
            }
            if (configured) {
                listOfNotNull(kind.label, state.aiModel.takeIf { it.isNotBlank() }).joinToString(" · ")
            } else {
                "${kind.label} · ${stringResource(ATMR.strings.pref_ai_key_not_set)}"
            }
        }
    }
    return "$typeLabel · $detail"
}

@Composable
internal fun TranslationSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 2.dp),
    )
}
