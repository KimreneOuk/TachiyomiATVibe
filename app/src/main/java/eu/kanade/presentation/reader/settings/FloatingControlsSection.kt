package eu.kanade.presentation.reader.settings

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import eu.kanade.presentation.more.settings.widget.SwitchPreferenceWidget
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsScreenModel
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

/**
 * Configuration for the reader's floating surfaces: the draggable translator
 * floater and the manual translate button presentation.
 */
@Composable
internal fun ColumnScope.FloatingControlsSection(
    screenModel: ReaderSettingsScreenModel,
    translateButtonWithControls: Boolean,
    onTranslateButtonWithControlsChange: (Boolean) -> Unit,
) {
    val floaterEnabled by screenModel.preferences.translationFloaterEnabled().collectAsState()
    SwitchPreferenceWidget(
        title = stringResource(ATMR.strings.pref_floater_show),
        subtitle = stringResource(ATMR.strings.pref_floater_show_summary),
        checked = floaterEnabled,
        onCheckedChanged = { screenModel.preferences.translationFloaterEnabled().set(it) },
    )

    val idleFade by screenModel.preferences.translationFloaterIdleFade().collectAsState()
    SwitchPreferenceWidget(
        title = stringResource(ATMR.strings.pref_floater_idle_fade),
        subtitle = stringResource(ATMR.strings.pref_floater_idle_fade_summary),
        checked = idleFade,
        onCheckedChanged = { screenModel.preferences.translationFloaterIdleFade().set(it) },
    )

    // Writing the position prefs (even with default values) makes the handle
    // re-dock, because it observes its position preferences.
    TextPreferenceWidget(
        title = stringResource(ATMR.strings.pref_floater_reset_position),
        subtitle = stringResource(ATMR.strings.pref_floater_reset_position_summary),
        onPreferenceClick = {
            screenModel.preferences.translationFloaterDockEnd().set(false)
            screenModel.preferences.translationFloaterVerticalFraction().set(0.5f)
        },
    )

    SwitchPreferenceWidget(
        title = stringResource(ATMR.strings.pref_reader_translate_button_mode),
        subtitle = stringResource(
            if (translateButtonWithControls) {
                ATMR.strings.pref_reader_translate_button_mode_summary_with_controls
            } else {
                ATMR.strings.pref_reader_translate_button_mode_summary_default
            },
        ),
        checked = translateButtonWithControls,
        onCheckedChanged = onTranslateButtonWithControlsChange,
    )
}
