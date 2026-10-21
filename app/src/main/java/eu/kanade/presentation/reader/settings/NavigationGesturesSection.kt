package eu.kanade.presentation.reader.settings

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsScreenModel
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonViewer
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.SettingsChipRow
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

@Composable
internal fun ColumnScope.NavigationGesturesSection(screenModel: ReaderSettingsScreenModel) {
    val viewer by screenModel.viewerFlow.collectAsState()
    val isWebtoon = viewer is WebtoonViewer

    val navigationMode by (
        if (isWebtoon) {
            screenModel.preferences.navigationModeWebtoon()
        } else {
            screenModel.preferences.navigationModePager()
        }
        ).collectAsState()
    val navInverted by (
        if (isWebtoon) {
            screenModel.preferences.webtoonNavInverted()
        } else {
            screenModel.preferences.pagerNavInverted()
        }
        ).collectAsState()

    TapZonesItems(
        selected = navigationMode,
        onSelect = {
            if (isWebtoon) {
                screenModel.preferences.navigationModeWebtoon().set(it)
            } else {
                screenModel.preferences.navigationModePager().set(it)
            }
        },
        invertMode = navInverted,
        onSelectInvertMode = {
            if (isWebtoon) {
                screenModel.preferences.webtoonNavInverted().set(it)
            } else {
                screenModel.preferences.pagerNavInverted().set(it)
            }
        },
    )

    CheckboxItem(
        label = stringResource(MR.strings.pref_read_with_long_tap),
        pref = screenModel.preferences.readWithLongTap(),
    )

    val volumeKeys by screenModel.preferences.readWithVolumeKeys().collectAsState()
    CheckboxItem(
        label = stringResource(MR.strings.pref_read_with_volume_keys),
        pref = screenModel.preferences.readWithVolumeKeys(),
    )
    if (volumeKeys) {
        CheckboxItem(
            label = stringResource(MR.strings.pref_read_with_volume_keys_inverted),
            pref = screenModel.preferences.readWithVolumeKeysInverted(),
        )
    }
}

@Composable
private fun ColumnScope.TapZonesItems(
    selected: Int,
    onSelect: (Int) -> Unit,
    invertMode: ReaderPreferences.TappingInvertMode,
    onSelectInvertMode: (ReaderPreferences.TappingInvertMode) -> Unit,
) {
    SettingsChipRow(MR.strings.pref_viewer_nav) {
        ReaderPreferences.TapZones.mapIndexed { index, labelRes ->
            FilterChip(
                selected = selected == index,
                onClick = { onSelect(index) },
                label = { Text(stringResource(labelRes)) },
            )
        }
    }

    if (selected != 5) {
        SettingsChipRow(MR.strings.pref_read_with_tapping_inverted) {
            ReaderPreferences.TappingInvertMode.entries.map {
                FilterChip(
                    selected = it == invertMode,
                    onClick = { onSelectInvertMode(it) },
                    label = { Text(stringResource(it.titleRes)) },
                )
            }
        }
    }
}
