package eu.kanade.presentation.reader.settings

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsScreenModel
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonViewer
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.SettingsChipRow
import tachiyomi.presentation.core.components.SliderItem
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import java.text.NumberFormat

@Composable
internal fun ColumnScope.ImageDisplaySection(screenModel: ReaderSettingsScreenModel) {
    val viewer by screenModel.viewerFlow.collectAsState()
    if (viewer is WebtoonViewer) {
        WebtoonImageDisplay(screenModel)
    } else {
        PagerImageDisplay(screenModel)
    }
}

@Composable
private fun ColumnScope.PagerImageDisplay(screenModel: ReaderSettingsScreenModel) {
    val imageScaleType by screenModel.preferences.imageScaleType().collectAsState()

    OptionCardRow(
        options = ReaderPreferences.ImageScaleType.mapIndexed { index, labelRes ->
            stringResource(labelRes) to (index + 1)
        },
        selected = imageScaleType,
        onSelect = { screenModel.preferences.imageScaleType().set(it) },
    )

    val zoomStart by screenModel.preferences.zoomStart().collectAsState()
    SettingsChipRow(MR.strings.pref_zoom_start) {
        ReaderPreferences.ZoomStart.mapIndexed { index, labelRes ->
            FilterChip(
                selected = zoomStart == index + 1,
                onClick = { screenModel.preferences.zoomStart().set(index + 1) },
                label = { Text(stringResource(labelRes)) },
            )
        }
    }

    CheckboxItem(
        label = stringResource(MR.strings.pref_crop_borders),
        pref = screenModel.preferences.cropBorders(),
    )

    CheckboxItem(
        label = stringResource(MR.strings.pref_landscape_zoom),
        pref = screenModel.preferences.landscapeZoom(),
    )

    CheckboxItem(
        label = stringResource(MR.strings.pref_navigate_pan),
        pref = screenModel.preferences.navigateToPan(),
    )

    DualPageSettings(
        splitPref = screenModel.preferences.dualPageSplitPaged(),
        invertPref = screenModel.preferences.dualPageInvertPaged(),
        rotatePref = screenModel.preferences.dualPageRotateToFit(),
        rotateInvertPref = screenModel.preferences.dualPageRotateToFitInvert(),
    )
}

@Composable
private fun ColumnScope.WebtoonImageDisplay(screenModel: ReaderSettingsScreenModel) {
    val numberFormat = remember { NumberFormat.getPercentInstance() }

    val webtoonSidePadding by screenModel.preferences.webtoonSidePadding().collectAsState()
    SliderItem(
        label = stringResource(MR.strings.pref_webtoon_side_padding),
        min = ReaderPreferences.WEBTOON_PADDING_MIN,
        max = ReaderPreferences.WEBTOON_PADDING_MAX,
        value = webtoonSidePadding,
        valueText = numberFormat.format(webtoonSidePadding / 100f),
        onChange = { screenModel.preferences.webtoonSidePadding().set(it) },
    )

    CheckboxItem(
        label = stringResource(MR.strings.pref_crop_borders),
        pref = screenModel.preferences.cropBordersWebtoon(),
    )

    CheckboxItem(
        label = stringResource(MR.strings.pref_double_tap_zoom),
        pref = screenModel.preferences.webtoonDoubleTapZoomEnabled(),
    )
    CheckboxItem(
        label = stringResource(MR.strings.pref_webtoon_disable_zoom_out),
        pref = screenModel.preferences.webtoonDisableZoomOut(),
    )

    DualPageSettings(
        splitPref = screenModel.preferences.dualPageSplitWebtoon(),
        invertPref = screenModel.preferences.dualPageInvertWebtoon(),
        rotatePref = screenModel.preferences.dualPageRotateToFitWebtoon(),
        rotateInvertPref = screenModel.preferences.dualPageRotateToFitInvertWebtoon(),
    )
}

@Composable
private fun ColumnScope.DualPageSettings(
    splitPref: tachiyomi.core.common.preference.Preference<Boolean>,
    invertPref: tachiyomi.core.common.preference.Preference<Boolean>,
    rotatePref: tachiyomi.core.common.preference.Preference<Boolean>,
    rotateInvertPref: tachiyomi.core.common.preference.Preference<Boolean>,
) {
    val dualPageSplit by splitPref.collectAsState()
    CheckboxItem(
        label = stringResource(MR.strings.pref_dual_page_split),
        pref = splitPref,
    )
    if (dualPageSplit) {
        CheckboxItem(
            label = stringResource(MR.strings.pref_dual_page_invert),
            pref = invertPref,
        )
    }

    val dualPageRotate by rotatePref.collectAsState()
    CheckboxItem(
        label = stringResource(MR.strings.pref_page_rotate),
        pref = rotatePref,
    )
    if (dualPageRotate) {
        CheckboxItem(
            label = stringResource(MR.strings.pref_page_rotate_invert),
            pref = rotateInvertPref,
        )
    }
}
