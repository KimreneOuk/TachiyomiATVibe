package eu.kanade.presentation.reader.vortex

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrightnessMedium
import androidx.compose.material.icons.outlined.Colorize
import androidx.compose.material.icons.outlined.FilterBAndW
import androidx.compose.material.icons.outlined.InvertColors
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.domain.manga.model.readingMode
import eu.kanade.presentation.components.vortex.VortexSectionHeading
import eu.kanade.presentation.components.vortex.VortexSelectionTile
import eu.kanade.presentation.components.vortex.VortexSliderSlate
import eu.kanade.presentation.components.vortex.VortexSwitchSlate
import eu.kanade.presentation.components.vortex.VortexTokens
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsScreenModel
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import tachiyomi.i18n.MR
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

/**
 * Reader setup tab featuring visual reading mode tiles and live-feedback display adjustments.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VortexReaderSetupTab(
    screenModel: ReaderSettingsScreenModel,
    translateButtonWithControls: Boolean,
    onTranslateButtonWithControlsChange: (Boolean) -> Unit,
    onInteractingWithVisuals: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val manga by screenModel.mangaFlow.collectAsState()
    val currentReadingMode = remember(manga) {
        ReadingMode.fromPreference(manga?.readingMode?.toInt())
    }

    val customBrightness by screenModel.preferences.customBrightness().collectAsState()
    val customBrightnessValue by screenModel.preferences.customBrightnessValue().collectAsState()
    val colorFilter by screenModel.preferences.colorFilter().collectAsState()
    val grayscale by screenModel.preferences.grayscale().collectAsState()
    val invertedColors by screenModel.preferences.invertedColors().collectAsState()
    val showTranslations by screenModel.preferences.showTranslations().collectAsState()

    Column(modifier = modifier.fillMaxWidth()) {
        // Reading Mode Section
        VortexSectionHeading(title = stringResource(tachiyomi.i18n.MR.strings.pref_category_reading_mode))
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(
                ReadingMode.DEFAULT to "Default",
                ReadingMode.RIGHT_TO_LEFT to "Right to Left",
                ReadingMode.LEFT_TO_RIGHT to "Left to Right",
                ReadingMode.VERTICAL to "Vertical",
                ReadingMode.WEBTOON to "Webtoon",
                ReadingMode.CONTINUOUS_VERTICAL to "Continuous",
            ).forEach { (mode, label) ->
                VortexSelectionTile(
                    selected = currentReadingMode == mode,
                    onClick = { screenModel.onChangeReadingMode(mode) },
                    title = label,
                    icon = Icons.Outlined.MenuBook,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Display Adjustments Section
        VortexSectionHeading(title = stringResource(ATMR.strings.reader_section_display_adjustment))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            VortexSwitchSlate(
                icon = Icons.Outlined.BrightnessMedium,
                title = stringResource(MR.strings.pref_custom_brightness),
                subtitle = "Override system brightness for comfortable reading",
                checked = customBrightness,
                onCheckedChange = { screenModel.preferences.customBrightness().set(it) },
            )

            if (customBrightness) {
                VortexSliderSlate(
                    icon = Icons.Outlined.BrightnessMedium,
                    label = "Brightness Level",
                    value = customBrightnessValue.toFloat(),
                    valueRange = -75f..100f,
                    valueDisplay = "${customBrightnessValue}%",
                    onValueChange = {
                        onInteractingWithVisuals(true)
                        screenModel.preferences.customBrightnessValue().set(it.toInt())
                    },
                    onValueChangeFinished = {
                        onInteractingWithVisuals(false)
                    },
                )
            }

            VortexSwitchSlate(
                icon = Icons.Outlined.FilterBAndW,
                title = stringResource(MR.strings.pref_grayscale),
                checked = grayscale,
                onCheckedChange = { screenModel.preferences.grayscale().set(it) },
            )

            VortexSwitchSlate(
                icon = Icons.Outlined.InvertColors,
                title = stringResource(MR.strings.pref_inverted_colors),
                checked = invertedColors,
                onCheckedChange = { screenModel.preferences.invertedColors().set(it) },
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Translation & Controls Overlay Section
        VortexSectionHeading(title = stringResource(ATMR.strings.reader_section_floating_controls))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            VortexSwitchSlate(
                icon = Icons.Outlined.Translate,
                title = stringResource(ATMR.strings.pref_show_translations),
                subtitle = "Toggle visibility of rendered translated text",
                checked = showTranslations,
                onCheckedChange = { screenModel.preferences.showTranslations().set(it) },
            )

            VortexSwitchSlate(
                icon = Icons.Outlined.Navigation,
                title = stringResource(ATMR.strings.pref_reader_translate_button_mode),
                subtitle = "Show floating translation pill along with reader chrome",
                checked = translateButtonWithControls,
                onCheckedChange = onTranslateButtonWithControlsChange,
            )
        }
    }
}
