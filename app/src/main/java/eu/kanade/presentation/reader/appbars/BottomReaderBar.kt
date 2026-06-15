package eu.kanade.presentation.reader.appbars

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.translation.model.Translation
import tachiyomi.i18n.MR
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun BottomReaderBar(
    backgroundColor: Color,
    readingMode: ReadingMode,
    onClickReadingMode: () -> Unit,
    orientation: ReaderOrientation,
    onClickOrientation: () -> Unit,
    cropEnabled: Boolean,
    onClickCropBorder: () -> Unit,
    onClickSettings: () -> Unit,
    translationState: Translation.State = Translation.State.NOT_TRANSLATED,
    translationProgress: Pair<Int, Int> = Pair(0, 0),
    onClickTranslate: () -> Unit = {},
    // TachiyomiAT: while translation is running the icon is disabled so repeated
    // taps can't pile up overlapping requests behind the singleton translator
    // permit. Previously the icon was always clickable and a fast double-tap
    // appeared to "do nothing" because the second request queued behind itself.
    translateEnabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .padding(8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClickReadingMode) {
            Icon(
                painter = painterResource(readingMode.iconRes),
                contentDescription = stringResource(MR.strings.viewer),
            )
        }

        IconButton(onClick = onClickOrientation) {
            Icon(
                imageVector = orientation.icon,
                contentDescription = stringResource(MR.strings.rotation_type),
            )
        }

        IconButton(onClick = onClickCropBorder) {
            Icon(
                painter = painterResource(if (cropEnabled) R.drawable.ic_crop_24dp else R.drawable.ic_crop_off_24dp),
                contentDescription = stringResource(MR.strings.pref_crop_borders),
            )
        }

        IconButton(onClick = onClickTranslate, enabled = translateEnabled) {
            when (translationState) {
                Translation.State.NOT_TRANSLATED, Translation.State.QUEUE -> {
                    Icon(
                        painter = painterResource(R.drawable.ic_translate_circle),
                        contentDescription = stringResource(ATMR.strings.reader_translate),
                    )
                }
                Translation.State.TRANSLATING -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                    )
                }
                Translation.State.TRANSLATED -> {
                    Icon(
                        painter = painterResource(R.drawable.ic_translate_circle_filled),
                        contentDescription = stringResource(ATMR.strings.reader_translate),
                    )
                }
                Translation.State.ERROR -> {
                    Icon(
                        painter = painterResource(R.drawable.ic_translate_circle),
                        contentDescription = stringResource(ATMR.strings.reader_translate),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        if (translationState == Translation.State.TRANSLATING && translationProgress.second > 0) {
            Text(
                text = stringResource(ATMR.strings.reader_translating, translationProgress.first, translationProgress.second),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        IconButton(onClick = onClickSettings) {
            Icon(
                imageVector = Icons.Outlined.Settings,
                contentDescription = stringResource(MR.strings.action_settings),
            )
        }
    }
}
