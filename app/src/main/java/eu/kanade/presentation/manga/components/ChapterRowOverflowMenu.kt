package eu.kanade.presentation.manga.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.DropdownMenu
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationProgressSnapshot
import eu.kanade.translation.model.TranslationRequestPhase
import eu.kanade.translation.model.TranslationRequestState
import eu.kanade.translation.presentation.TranslationUiTruth
import tachiyomi.i18n.MR
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.components.material.DISABLED_ALPHA
import tachiyomi.presentation.core.components.material.IconButtonTokens
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Menu items the chapter row's overflow menu offers for the translation
 * coordinator, derived from the chapter's translation state. Chapters with
 * observable work always expose the progress drawer; only chapters with
 * committed data expose a reset.
 */
internal fun chapterOverflowTranslationActions(
    state: Translation.State,
    hasPendingRequest: Boolean,
): List<ChapterTranslationAction> = when (state) {
    Translation.State.NOT_TRANSLATED ->
        if (hasPendingRequest) {
            listOf(ChapterTranslationAction.DETAILS, ChapterTranslationAction.CANCEL)
        } else {
            listOf(ChapterTranslationAction.START)
        }
    Translation.State.QUEUE, Translation.State.TRANSLATING ->
        listOf(ChapterTranslationAction.DETAILS, ChapterTranslationAction.CANCEL)
    Translation.State.PAUSED ->
        listOf(
            ChapterTranslationAction.START,
            ChapterTranslationAction.DETAILS,
            ChapterTranslationAction.CANCEL,
        )
    Translation.State.TRANSLATED,
    Translation.State.READY_WITH_WARNINGS,
    Translation.State.ERROR,
    ->
        listOf(
            ChapterTranslationAction.DETAILS,
            ChapterTranslationAction.START,
            ChapterTranslationAction.DELETE,
        )
}

/** Download menu items for the chapter row's overflow menu. */
internal fun chapterOverflowDownloadActions(state: Download.State): List<ChapterDownloadAction> = when (state) {
    Download.State.NOT_DOWNLOADED -> listOf(ChapterDownloadAction.START, ChapterDownloadAction.START_NOW)
    Download.State.QUEUE, Download.State.DOWNLOADING ->
        listOf(ChapterDownloadAction.START_NOW, ChapterDownloadAction.CANCEL)
    Download.State.DOWNLOADED -> listOf(ChapterDownloadAction.DELETE)
    Download.State.ERROR -> listOf(ChapterDownloadAction.START)
}

/**
 * The chapter row's single action entry point: a three-dot button opening a
 * menu with the translation and download functions that are valid for the
 * chapter's current state.
 */
@Composable
fun ChapterRowOverflowMenu(
    enabled: Boolean,
    translationStateProvider: () -> Translation.State,
    translationRequestProvider: () -> TranslationRequestState?,
    downloadedProvider: () -> Boolean,
    downloadStateProvider: () -> Download.State,
    onTranslationAction: ((ChapterTranslationAction) -> Unit)?,
    onDownloadAction: ((ChapterDownloadAction) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var isMenuExpanded by remember { mutableStateOf(false) }
    val translationActions = chapterOverflowTranslationActions(
        state = translationStateProvider(),
        hasPendingRequest = translationRequestProvider() != null,
    )
    val downloadActions = if (onDownloadAction != null) {
        chapterOverflowDownloadActions(downloadStateProvider())
    } else {
        emptyList()
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        DropdownMenu(
            expanded = isMenuExpanded,
            onDismissRequest = { isMenuExpanded = false },
        ) {
            translationActions.forEach { action ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = translationActionLabel(
                                action = action,
                                state = translationStateProvider(),
                                downloaded = downloadedProvider(),
                            ),
                        )
                    },
                    onClick = {
                        isMenuExpanded = false
                        onTranslationAction?.invoke(action)
                    },
                )
            }
            if (translationActions.isNotEmpty() && downloadActions.isNotEmpty()) {
                HorizontalDivider()
            }
            downloadActions.forEach { action ->
                DropdownMenuItem(
                    text = { Text(text = downloadActionLabel(action)) },
                    onClick = {
                        isMenuExpanded = false
                        onDownloadAction?.invoke(action)
                    },
                )
            }
        }
        Box(
            modifier = Modifier
                .size(IconButtonTokens.StateLayerSize)
                .alpha(if (enabled) 1f else DISABLED_ALPHA)
                .clickable(
                    interactionSource = null,
                    indication = ripple(
                        bounded = false,
                        radius = IconButtonTokens.StateLayerSize / 2,
                    ),
                    enabled = enabled,
                    role = Role.Button,
                    onClick = { isMenuExpanded = true },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.MoreVert,
                contentDescription = stringResource(MR.strings.action_menu),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(KebabIconSize),
            )
        }
    }
}

@Composable
private fun translationActionLabel(
    action: ChapterTranslationAction,
    state: Translation.State,
    downloaded: Boolean,
): String = when (action) {
    ChapterTranslationAction.START -> when (state) {
        Translation.State.PAUSED -> stringResource(MR.strings.action_resume)
        Translation.State.NOT_TRANSLATED -> stringResource(
            if (downloaded) ATMR.strings.manga_translate else ATMR.strings.manga_translate_download_first,
        )
        else -> stringResource(ATMR.strings.reader_page_retranslate_action)
    }
    ChapterTranslationAction.DETAILS -> stringResource(ATMR.strings.manga_translation_details)
    ChapterTranslationAction.CANCEL -> stringResource(ATMR.strings.manga_translation_cancel)
    ChapterTranslationAction.DELETE -> stringResource(ATMR.strings.manga_chapter_reset_data)
}

@Composable
private fun downloadActionLabel(action: ChapterDownloadAction): String = when (action) {
    ChapterDownloadAction.START -> stringResource(MR.strings.manga_download)
    ChapterDownloadAction.START_NOW -> stringResource(MR.strings.action_start_downloading_now)
    ChapterDownloadAction.CANCEL -> stringResource(MR.strings.action_cancel)
    ChapterDownloadAction.DELETE -> stringResource(MR.strings.action_delete)
}

/**
 * Compact passive status for the chapter row: replaces the two always-visible
 * action buttons with glyphs that only appear while work is in flight, a
 * result needs attention, or a download is active.
 */
@Composable
fun ChapterRowStatus(
    translationStateProvider: () -> Translation.State,
    translationRequestProvider: () -> TranslationRequestState?,
    translationProgressProvider: () -> TranslationProgressSnapshot?,
    downloadStateProvider: () -> Download.State,
    downloadProgressProvider: () -> Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(StatusSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TranslationStatusGlyph(
            state = translationStateProvider(),
            request = translationRequestProvider(),
            progress = translationProgressProvider(),
        )
        DownloadStatusGlyph(
            state = downloadStateProvider(),
            progress = downloadProgressProvider(),
        )
    }
}

@Composable
private fun TranslationStatusGlyph(
    state: Translation.State,
    request: TranslationRequestState?,
    progress: TranslationProgressSnapshot?,
) {
    when (state) {
        Translation.State.NOT_TRANSLATED -> when {
            request == null -> {}
            request.phase == TranslationRequestPhase.DOWNLOAD_FAILED -> ErrorGlyph()
            else -> ProgressGlyph(color = MaterialTheme.colorScheme.primary)
        }
        Translation.State.QUEUE, Translation.State.TRANSLATING -> ProgressGlyph(
            color = MaterialTheme.colorScheme.primary,
            progress = progress?.takeIf { it.totalStages > 0 }?.fraction,
        )
        Translation.State.PAUSED -> Icon(
            imageVector = Icons.Outlined.Pause,
            contentDescription = null,
            modifier = Modifier.size(StatusGlyphSize),
            tint = WarningColor,
        )
        Translation.State.TRANSLATED -> DotGlyph(
            color = MaterialTheme.colorScheme.tertiary,
            description = TranslationUiTruth.forChapterIndicator(state, null).contentDescription,
        )
        Translation.State.READY_WITH_WARNINGS -> DotGlyph(
            color = WarningColor,
            description = TranslationUiTruth.forChapterIndicator(state, null).contentDescription,
        )
        Translation.State.ERROR -> ErrorGlyph(
            description = TranslationUiTruth.forChapterIndicator(state, null).contentDescription,
        )
    }
}

@Composable
private fun DownloadStatusGlyph(
    state: Download.State,
    progress: Int,
) {
    when (state) {
        Download.State.QUEUE, Download.State.DOWNLOADING -> ProgressGlyph(
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            progress = progress.takeIf { state == Download.State.DOWNLOADING && it > 0 }?.div(100f),
        )
        Download.State.ERROR -> ErrorGlyph()
        Download.State.NOT_DOWNLOADED, Download.State.DOWNLOADED -> {}
    }
}

@Composable
private fun ProgressGlyph(color: Color, progress: Float? = null) {
    if (progress == null) {
        CircularProgressIndicator(
            modifier = Modifier.size(StatusGlyphSize),
            color = color,
            strokeWidth = StatusStrokeWidth,
        )
    } else {
        CircularProgressIndicator(
            progress = { progress },
            modifier = Modifier.size(StatusGlyphSize),
            color = color,
            strokeWidth = StatusStrokeWidth,
            trackColor = Color.Transparent,
        )
    }
}

@Composable
private fun DotGlyph(color: Color, description: String?) {
    Icon(
        imageVector = Icons.Filled.Circle,
        contentDescription = description,
        modifier = Modifier.size(StatusDotSize),
        tint = color,
    )
}

@Composable
private fun ErrorGlyph(description: String? = null) {
    Icon(
        imageVector = Icons.Outlined.ErrorOutline,
        contentDescription = description,
        modifier = Modifier.size(StatusGlyphSize),
        tint = MaterialTheme.colorScheme.error,
    )
}

private val WarningColor = Color(0xFFFFA000)

private val KebabIconSize = 24.dp
private val StatusGlyphSize = 16.dp
private val StatusDotSize = 10.dp
private val StatusStrokeWidth = 2.dp
private val StatusSpacing = 2.dp
