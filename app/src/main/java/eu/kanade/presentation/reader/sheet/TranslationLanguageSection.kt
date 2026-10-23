package eu.kanade.presentation.reader.sheet

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.SelectorOption
import eu.kanade.presentation.components.designsystem.LanguagePairSelector
import eu.kanade.presentation.components.designsystem.SectionHeading
import eu.kanade.tachiyomi.ui.reader.TranslationSettingsState
import eu.kanade.translation.model.TextRecognizerLanguage
import eu.kanade.translation.model.TextTranslatorLanguage
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Source/target language section of the reader translation sheet: searchable
 * from/to boxes with an animated swap.
 */
@Composable
internal fun TranslationLanguageSection(
    state: TranslationSettingsState,
    onTranslateFromLanguageChange: (String) -> Unit,
    onTranslateToLanguageChange: (String) -> Unit,
) {
    SectionHeading(title = stringResource(ATMR.strings.translation_section_languages))

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

    LanguagePairSelector(
        fromLanguage = state.translateFromLanguage,
        toLanguage = state.translateToLanguage,
        onFromLanguageChange = onTranslateFromLanguageChange,
        onToLanguageChange = onTranslateToLanguageChange,
        fromOptions = fromOptions,
        toOptions = toOptions,
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth(),
    )
}
