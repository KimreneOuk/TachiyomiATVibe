package eu.kanade.presentation.reader.sheet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Animation
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.BrightnessMedium
import androidx.compose.material.icons.outlined.FilterBAndW
import androidx.compose.material.icons.outlined.InvertColors
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.domain.manga.model.readingMode
import eu.kanade.presentation.components.designsystem.SectionHeading
import eu.kanade.presentation.components.designsystem.SelectionTile
import eu.kanade.presentation.components.designsystem.SliderCard
import eu.kanade.presentation.components.designsystem.SwitchCard
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsScreenModel
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import tachiyomi.i18n.MR
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

/**
 * Reader settings sheet body: reading mode (Manga paged LTR / Webtoon long
 * strip), display adjustments and navigation feel. Translation-facing toggles
 * live in the translation sheet instead.
 */
@Composable
fun ReaderSetupTab(
    screenModel: ReaderSettingsScreenModel,
    onInteractingWithVisuals: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val manga by screenModel.mangaFlow.collectAsState()
    val currentReadingMode = remember(manga) {
        ReadingMode.fromPreference(manga?.readingMode?.toInt())
    }

    val customBrightness by screenModel.preferences.customBrightness().collectAsState()
    val customBrightnessValue by screenModel.preferences.customBrightnessValue().collectAsState()
    val grayscale by screenModel.preferences.grayscale().collectAsState()
    val invertedColors by screenModel.preferences.invertedColors().collectAsState()
    val pageTransitions by screenModel.preferences.pageTransitions().collectAsState()
    val volumeKeys by screenModel.preferences.readWithVolumeKeys().collectAsState()
    val showPageNumber by screenModel.preferences.showPageNumber().collectAsState()
    val keepScreenOn by screenModel.preferences.keepScreenOn().collectAsState()

    Column(modifier = modifier.fillMaxWidth()) {
        SectionHeading(title = stringResource(MR.strings.pref_category_reading_mode))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SelectionTile(
                selected = currentReadingMode == ReadingMode.LEFT_TO_RIGHT || currentReadingMode == ReadingMode.DEFAULT,
                onClick = { screenModel.onChangeReadingMode(ReadingMode.LEFT_TO_RIGHT) },
                title = stringResource(ATMR.strings.reader_mode_manga),
                subtitle = stringResource(ATMR.strings.reader_mode_manga_summary),
                icon = Icons.Outlined.MenuBook,
                modifier = Modifier.weight(1f),
            )
            SelectionTile(
                selected = currentReadingMode == ReadingMode.WEBTOON,
                onClick = { screenModel.onChangeReadingMode(ReadingMode.WEBTOON) },
                title = stringResource(ATMR.strings.reader_mode_webtoon),
                subtitle = stringResource(ATMR.strings.reader_mode_webtoon_summary),
                icon = Icons.Outlined.ViewAgenda,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        SectionHeading(title = stringResource(ATMR.strings.reader_section_display_adjustment))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SwitchCard(
                icon = Icons.Outlined.BrightnessMedium,
                title = stringResource(MR.strings.pref_custom_brightness),
                subtitle = stringResource(ATMR.strings.reader_custom_brightness_summary),
                checked = customBrightness,
                onCheckedChange = { screenModel.preferences.customBrightness().set(it) },
            )

            if (customBrightness) {
                SliderCard(
                    icon = Icons.Outlined.BrightnessMedium,
                    label = stringResource(ATMR.strings.reader_brightness_level),
                    value = customBrightnessValue.toFloat(),
                    valueRange = -75f..100f,
                    valueDisplay = "$customBrightnessValue%",
                    onValueChange = {
                        onInteractingWithVisuals(true)
                        screenModel.preferences.customBrightnessValue().set(it.toInt())
                    },
                    onValueChangeFinished = {
                        onInteractingWithVisuals(false)
                    },
                )
            }

            SwitchCard(
                icon = Icons.Outlined.FilterBAndW,
                title = stringResource(MR.strings.pref_grayscale),
                checked = grayscale,
                onCheckedChange = { screenModel.preferences.grayscale().set(it) },
            )

            SwitchCard(
                icon = Icons.Outlined.InvertColors,
                title = stringResource(MR.strings.pref_inverted_colors),
                checked = invertedColors,
                onCheckedChange = { screenModel.preferences.invertedColors().set(it) },
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        SectionHeading(title = stringResource(ATMR.strings.reader_section_navigation_feel))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SwitchCard(
                icon = Icons.Outlined.Animation,
                title = stringResource(MR.strings.pref_page_transitions),
                checked = pageTransitions,
                onCheckedChange = { screenModel.preferences.pageTransitions().set(it) },
            )
            SwitchCard(
                icon = Icons.Outlined.VolumeUp,
                title = stringResource(MR.strings.pref_read_with_volume_keys),
                checked = volumeKeys,
                onCheckedChange = { screenModel.preferences.readWithVolumeKeys().set(it) },
            )
            SwitchCard(
                icon = Icons.Outlined.Tag,
                title = stringResource(MR.strings.pref_show_page_number),
                checked = showPageNumber,
                onCheckedChange = { screenModel.preferences.showPageNumber().set(it) },
            )
            SwitchCard(
                icon = Icons.Outlined.Bolt,
                title = stringResource(MR.strings.pref_keep_screen_on),
                checked = keepScreenOn,
                onCheckedChange = { screenModel.preferences.keepScreenOn().set(it) },
            )
        }
    }
}
