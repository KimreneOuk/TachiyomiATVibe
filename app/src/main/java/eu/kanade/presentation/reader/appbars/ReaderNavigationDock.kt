package eu.kanade.presentation.reader.appbars

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.R
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationProgressSnapshot
import eu.kanade.translation.model.TranslationRequestPhase
import eu.kanade.translation.presentation.BatchStatusLineKind
import eu.kanade.translation.presentation.TranslationUiTruth
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.components.material.Slider
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Floating bottom navigation dock: chapter prev/next (RTL aware), the page
 * slider with a compact position label, the translate action with its live
 * work-state icon, and the quick actions toggle. One rounded translucent
 * capsule that floats above the artwork.
 */
@Composable
fun ReaderNavigationDock(
    isRtl: Boolean,
    onNextChapter: () -> Unit,
    enabledNext: Boolean,
    onPreviousChapter: () -> Unit,
    enabledPrevious: Boolean,
    currentPage: Int,
    totalPages: Int,
    onPageIndexChange: (Int) -> Unit,
    translationState: Translation.State,
    translationBatchProgress: TranslationProgressSnapshot?,
    isBatchSession: Boolean,
    onClickTranslate: () -> Unit,
    quickActionsExpanded: Boolean,
    onToggleQuickActions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val transparentButtonColors = IconButtonDefaults.filledIconButtonColors(
        containerColor = Color.Transparent,
        disabledContainerColor = Color.Transparent,
    )

    // Direction is driven by the reader viewer, not the system layout direction.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .background(
                    color = MaterialTheme.colorScheme
                        .surfaceColorAtElevation(3.dp)
                        .copy(alpha = if (isSystemInDarkTheme()) 0.9f else 0.95f),
                    shape = RoundedCornerShape(percent = 50),
                )
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            FilledIconButton(
                onClick = if (isRtl) onNextChapter else onPreviousChapter,
                enabled = if (isRtl) enabledNext else enabledPrevious,
                colors = transparentButtonColors,
            ) {
                Icon(
                    imageVector = Icons.Outlined.SkipPrevious,
                    contentDescription = stringResource(
                        if (isRtl) ATMR.strings.reader_chapter_nav_next else ATMR.strings.reader_chapter_nav_previous,
                    ),
                )
            }

            if (totalPages > 1) {
                CompositionLocalProvider(
                    LocalLayoutDirection provides if (isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                ) {
                    val interactionSource = remember { MutableInteractionSource() }
                    val sliderDragged by interactionSource.collectIsDraggedAsState()
                    LaunchedEffect(currentPage) {
                        if (sliderDragged) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                    }
                    Slider(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 4.dp),
                        value = currentPage,
                        valueRange = 1..totalPages,
                        onValueChange = f@{
                            if (it == currentPage) return@f
                            onPageIndexChange(it - 1)
                        },
                        interactionSource = interactionSource,
                    )
                }
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Text(
                        text = stringResource(ATMR.strings.reader_dock_page_of_pages, currentPage, totalPages),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                }
            } else {
                Box(modifier = Modifier.weight(1f))
            }

            FilledIconButton(
                onClick = if (isRtl) onPreviousChapter else onNextChapter,
                enabled = if (isRtl) enabledPrevious else enabledNext,
                colors = transparentButtonColors,
            ) {
                Icon(
                    imageVector = Icons.Outlined.SkipNext,
                    contentDescription = stringResource(
                        if (isRtl) ATMR.strings.reader_chapter_nav_previous else ATMR.strings.reader_chapter_nav_next,
                    ),
                )
            }

            TranslateDockButton(
                translationState = translationState,
                translationBatchProgress = translationBatchProgress,
                isBatchSession = isBatchSession,
                onClick = onClickTranslate,
            )

            FilledTonalIconButton(
                onClick = onToggleQuickActions,
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = if (quickActionsExpanded) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        Color.Transparent
                    },
                ),
            ) {
                Icon(
                    imageVector = Icons.Outlined.MoreVert,
                    contentDescription = stringResource(ATMR.strings.reader_quick_actions),
                )
            }
        }
    }
}

/**
 * Translate affordance on the dock. Mirrors the per-page translation state:
 * spinner while work runs, download glyph while waiting on the chapter
 * download, error tint on failure, filled icon when a translation exists.
 */
@Composable
private fun TranslateDockButton(
    translationState: Translation.State,
    translationBatchProgress: TranslationProgressSnapshot?,
    isBatchSession: Boolean,
    onClick: () -> Unit,
) {
    FilledIconButton(
        onClick = onClick,
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.Transparent),
    ) {
        val requestPhase = translationBatchProgress
            ?.takeIf { isBatchSession }
            ?.requestState
            ?.phase
        when {
            requestPhase == TranslationRequestPhase.WAITING_FOR_DOWNLOAD -> {
                Icon(
                    imageVector = Icons.Outlined.Download,
                    contentDescription = stringResource(ATMR.strings.reader_translate),
                    tint = MaterialTheme.colorScheme.tertiary,
                )
            }
            requestPhase == TranslationRequestPhase.DOWNLOAD_FAILED -> {
                Icon(
                    painter = painterResource(R.drawable.ic_translate_circle),
                    contentDescription = stringResource(ATMR.strings.reader_translate),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
            requestPhase == TranslationRequestPhase.STARTING ||
                requestPhase == TranslationRequestPhase.PREPARING -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
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
                    modifier = Modifier.size(22.dp),
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
}

/**
 * One-line batch translation status rendered from the same shared truth the
 * progress sheet uses, so the line can never freeze on stale wording.
 */
@Composable
fun ReaderTranslationStatusLine(
    snapshot: TranslationProgressSnapshot,
    modifier: Modifier = Modifier,
) {
    val isPaused = snapshot.state == Translation.State.PAUSED || snapshot.pauseReason != null
    val line = TranslationUiTruth.readerBarLine(snapshot, isBatchSession = true) ?: return
    val status = when (line.kind) {
        BatchStatusLineKind.REBUILDING -> stringResource(ATMR.strings.translation_status_rebuilding)
        BatchStatusLineKind.RESTORING -> stringResource(
            ATMR.strings.translation_bar_restoring,
            *line.formatArgs.toTypedArray(),
        )
        else -> line.fallback
    }
    Text(
        text = status,
        color = if (isPaused) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelMedium,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(percent = 50))
            .background(
                color = MaterialTheme.colorScheme
                    .surfaceColorAtElevation(3.dp)
                    .copy(alpha = if (isSystemInDarkTheme()) 0.9f else 0.95f),
            )
            .padding(horizontal = 16.dp, vertical = 6.dp),
    )
}
