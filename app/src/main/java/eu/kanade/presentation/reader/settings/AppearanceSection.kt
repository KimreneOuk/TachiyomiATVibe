package eu.kanade.presentation.reader.settings

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsScreenModel
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.SettingsChipRow
import tachiyomi.presentation.core.components.SliderItem
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

private val grayBackground = Color(0xFF202125)

@Composable
internal fun ColumnScope.AppearanceSection(screenModel: ReaderSettingsScreenModel) {
    val readerTheme by screenModel.preferences.readerTheme().collectAsState()

    OptionCardRow(
        options = listOf(
            stringResource(MR.strings.black_background) to 1,
            stringResource(MR.strings.gray_background) to 2,
            stringResource(MR.strings.white_background) to 0,
            stringResource(MR.strings.automatic_background) to 3,
        ),
        selected = readerTheme,
        onSelect = { screenModel.preferences.readerTheme().set(it) },
        swatch = { value ->
            ThemeSwatch(
                color = when (value) {
                    1 -> Color.Black
                    2 -> grayBackground
                    0 -> Color.White
                    else -> MaterialTheme.colorScheme.surfaceVariant
                },
            )
        },
    )

    CheckboxItem(
        label = stringResource(MR.strings.pref_show_page_number),
        pref = screenModel.preferences.showPageNumber(),
    )

    CheckboxItem(
        label = stringResource(MR.strings.pref_fullscreen),
        pref = screenModel.preferences.fullscreen(),
    )

    if (screenModel.hasDisplayCutout && screenModel.preferences.fullscreen().get()) {
        CheckboxItem(
            label = stringResource(MR.strings.pref_cutout_short),
            pref = screenModel.preferences.cutoutShort(),
        )
    }

    CheckboxItem(
        label = stringResource(MR.strings.pref_keep_screen_on),
        pref = screenModel.preferences.keepScreenOn(),
    )

    CheckboxItem(
        label = stringResource(MR.strings.pref_always_show_chapter_transition),
        pref = screenModel.preferences.alwaysShowChapterTransition(),
    )

    CheckboxItem(
        label = stringResource(MR.strings.pref_page_transitions),
        pref = screenModel.preferences.pageTransitions(),
    )

    FlashSettings(screenModel)
}

@Composable
private fun ColumnScope.FlashSettings(screenModel: ReaderSettingsScreenModel) {
    val flashPageState by screenModel.preferences.flashOnPageChange().collectAsState()

    CheckboxItem(
        label = stringResource(MR.strings.pref_flash_page),
        pref = screenModel.preferences.flashOnPageChange(),
    )
    if (flashPageState) {
        val flashMillisPref = screenModel.preferences.flashDurationMillis()
        val flashMillis by flashMillisPref.collectAsState()

        val flashIntervalPref = screenModel.preferences.flashPageInterval()
        val flashInterval by flashIntervalPref.collectAsState()

        val flashColorPref = screenModel.preferences.flashColor()
        val flashColor by flashColorPref.collectAsState()

        SliderItem(
            value = flashMillis / ReaderPreferences.MILLI_CONVERSION,
            label = stringResource(MR.strings.pref_flash_duration),
            valueText = stringResource(MR.strings.pref_flash_duration_summary, flashMillis),
            onChange = { flashMillisPref.set(it * ReaderPreferences.MILLI_CONVERSION) },
            min = 1,
            max = 15,
        )
        SliderItem(
            value = flashInterval,
            label = stringResource(MR.strings.pref_flash_page_interval),
            valueText = pluralStringResource(MR.plurals.pref_pages, flashInterval, flashInterval),
            onChange = { flashIntervalPref.set(it) },
            min = 1,
            max = 10,
        )
        SettingsChipRow(MR.strings.pref_flash_with) {
            listOf(
                MR.strings.pref_flash_style_black to ReaderPreferences.FlashColor.BLACK,
                MR.strings.pref_flash_style_white to ReaderPreferences.FlashColor.WHITE,
                MR.strings.pref_flash_style_white_black to ReaderPreferences.FlashColor.WHITE_BLACK,
            ).map { (labelRes, value) ->
                FilterChip(
                    selected = flashColor == value,
                    onClick = { flashColorPref.set(value) },
                    label = { Text(stringResource(labelRes)) },
                )
            }
        }
    }
}
