package eu.kanade.presentation.reader.appbars

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
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
import eu.kanade.tachiyomi.ui.reader.ReaderAutoTranslationUiState
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationBatchPhase
import eu.kanade.translation.model.TranslationProgressSnapshot
import eu.kanade.translation.model.TranslationRequestPhase
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
    translationBatchProgress: TranslationProgressSnapshot? = null,
    autoTranslation: ReaderAutoTranslationUiState = ReaderAutoTranslationUiState.empty(),
    onClickTranslate: () -> Unit = {},
    // TachiyomiAT: while translation is running the icon is disabled so repeated
    // taps can't pile up overlapping requests behind the singleton translator
    // permit. Previously the icon was always clickable and a fast double-tap
    // appeared to "do nothing" because the second request queued behind itself.
    translateEnabled: Boolean = true,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AutoTranslationStatus(
            state = autoTranslation,
            compact = true,
            modifier = Modifier.padding(top = 4.dp),
        )

        translationBatchProgress?.let { snapshot ->
            val request = snapshot.requestState
            val isPaused = snapshot.state == Translation.State.PAUSED || snapshot.pauseReason != null
            val isVisible = request != null ||
                isPaused ||
                snapshot.batchPhase != TranslationBatchPhase.IDLE
            if (isVisible) {
                val status = when {
                    request?.phase == TranslationRequestPhase.STARTING -> "Translation accepted — preparing"
                    request?.phase == TranslationRequestPhase.PREPARING -> "Preparing translation batch"
                    request?.phase == TranslationRequestPhase.WAITING_FOR_DOWNLOAD -> "Waiting for chapter download"
                    request?.phase == TranslationRequestPhase.DOWNLOAD_FAILED -> "Download failed — retry to continue"
                    isPaused -> "Translation paused"
                    snapshot.totalPages > 0 -> "Batch ${snapshot.donePages}/${snapshot.totalPages} pages"
                    else -> "Preparing translation batch"
                }
                Text(
                    text = status,
                    color = if (isPaused) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
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
                val requestPhase = translationBatchProgress?.requestState?.phase
                when {
                    requestPhase == TranslationRequestPhase.WAITING_FOR_DOWNLOAD -> {
                        Icon(
                            imageVector = Icons.Outlined.Download,
                            contentDescription = "Translation waiting for download",
                            tint = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    requestPhase == TranslationRequestPhase.DOWNLOAD_FAILED -> {
                        Icon(
                            painter = painterResource(R.drawable.ic_translate_circle),
                            contentDescription = "Translation download failed",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                    requestPhase == TranslationRequestPhase.STARTING ||
                        requestPhase == TranslationRequestPhase.PREPARING -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                    translationState == Translation.State.NOT_TRANSLATED ||
                        translationState == Translation.State.QUEUE ||
                        translationState == Translation.State.PAUSED -> {
                        Icon(
                            painter = painterResource(R.drawable.ic_translate_circle),
                            contentDescription = stringResource(ATMR.strings.reader_translate),
                        )
                    }
                    translationState == Translation.State.TRANSLATING -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                    translationState == Translation.State.TRANSLATED ||
                        translationState == Translation.State.READY_WITH_WARNINGS -> {
                        Icon(
                            painter = painterResource(R.drawable.ic_translate_circle_filled),
                            contentDescription = stringResource(ATMR.strings.reader_translate),
                        )
                    }
                    translationState == Translation.State.ERROR -> {
                        Icon(
                            painter = painterResource(R.drawable.ic_translate_circle),
                            contentDescription = stringResource(ATMR.strings.reader_translate),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            IconButton(onClick = onClickSettings) {
                Icon(
                    imageVector = Icons.Outlined.Settings,
                    contentDescription = stringResource(MR.strings.action_settings),
                )
            }
        }
    }
}
