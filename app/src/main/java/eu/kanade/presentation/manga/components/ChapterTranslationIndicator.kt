package eu.kanade.presentation.manga.components

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.DropdownMenu
import eu.kanade.tachiyomi.R
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationProgressSnapshot
import eu.kanade.translation.model.TranslationRequestPhase
import eu.kanade.translation.model.TranslationRequestState
import eu.kanade.translation.ui.TranslationUiTruth
import tachiyomi.i18n.MR
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.components.material.IconButtonTokens
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.secondaryItemAlpha

enum class ChapterTranslationAction {
    START,
    DETAILS,
    CANCEL,
    DELETE,
}

/**
 *  slice 1: tap routing table for the chapter translation indicator.
 * Every state with observable translation work (pending, queued, downloading,
 * translating, paused, translated, warning, error) routes a tap to the
 * progress drawer (DETAILS) so progress and diagnostics are always
 * discoverable. Only a chapter with no work and no pending request starts a
 * new batch from a tap.
 */
fun translationIndicatorTapAction(
    state: Translation.State,
    hasPendingRequest: Boolean,
): ChapterTranslationAction = when (state) {
    Translation.State.NOT_TRANSLATED ->
        if (hasPendingRequest) ChapterTranslationAction.DETAILS else ChapterTranslationAction.START
    Translation.State.QUEUE,
    Translation.State.TRANSLATING,
    Translation.State.PAUSED,
    Translation.State.TRANSLATED,
    Translation.State.READY_WITH_WARNINGS,
    Translation.State.ERROR,
    -> ChapterTranslationAction.DETAILS
}

@Composable
fun ChapterTranslationIndicator(
    enabled: Boolean,
    translationStateProvider: () -> Translation.State,
    onClick: (ChapterTranslationAction) -> Unit,
    // TachiyomiAT: batch translation progress snapshot for the indicator.
    translationProgressProvider: () -> TranslationProgressSnapshot? = { null },
    // TachiyomiAT: immediate acknowledgement while queue/tracker setup is pending.
    translationRequestProvider: () -> TranslationRequestState? = { null },
    // TachiyomiAT: pre-translate is always reachable; when false the idle glyph
    // carries a "will download first" hint badge.
    downloadedProvider: () -> Boolean = { true },
    modifier: Modifier = Modifier,
) {
    val downloaded = downloadedProvider()
    val translationRequest = translationRequestProvider()
    when (val translationState = translationStateProvider()) {
        Translation.State.NOT_TRANSLATED -> if (translationRequest != null) {
            PendingTranslationIndicator(
                enabled = enabled,
                modifier = modifier,
                request = translationRequest,
                onClick = onClick,
            )
        } else {
            NotTranslatedIndicator(
                enabled = enabled,
                modifier = modifier,
                downloaded = downloaded,
                onClick = onClick,
            )
        }
        Translation.State.QUEUE, Translation.State.TRANSLATING, Translation.State.PAUSED -> TranslatingIndicator(
            enabled = enabled,
            modifier = modifier,
            onClick = onClick,
            snapshot = translationProgressProvider(),
            translationState = translationState,
        )
        Translation.State.TRANSLATED, Translation.State.READY_WITH_WARNINGS -> TranslatedIndicator(
            enabled = enabled,
            modifier = modifier,
            onClick = onClick,
            translationState = translationState,
        )
        Translation.State.ERROR -> ErrorIndicator(
            enabled = enabled,
            modifier = modifier,
            onClick = onClick,
        )
    }
}

@Composable
private fun PendingTranslationIndicator(
    enabled: Boolean,
    modifier: Modifier,
    request: TranslationRequestState?,
    onClick: (ChapterTranslationAction) -> Unit,
) {
    if (request == null) return
    val isDownloadWait = request.phase == TranslationRequestPhase.WAITING_FOR_DOWNLOAD
    val isFailed = request.phase == TranslationRequestPhase.DOWNLOAD_FAILED
    val tint = when {
        isFailed -> MaterialTheme.colorScheme.error
        isDownloadWait -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    Box(
        modifier = modifier
            .size(IconButtonTokens.StateLayerSize)
            .commonClickable(
                enabled = enabled,
                hapticFeedback = LocalHapticFeedback.current,
                onLongClick = { onClick(ChapterTranslationAction.CANCEL) },
                onClick = { onClick(ChapterTranslationAction.DETAILS) },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (isDownloadWait) {
            Icon(
                imageVector = Icons.Outlined.Download,
                contentDescription = "Translation waiting for download",
                modifier = Modifier.size(IndicatorSize),
                tint = tint,
            )
        } else if (isFailed) {
            Icon(
                imageVector = Icons.Outlined.ErrorOutline,
                contentDescription = "Translation download failed",
                modifier = Modifier.size(IndicatorSize),
                tint = tint,
            )
        } else {
            CircularProgressIndicator(
                modifier = IndicatorModifier,
                color = tint,
                strokeWidth = IndicatorStrokeWidth,
            )
            Icon(
                painter = painterResource(R.drawable.ic_translate),
                contentDescription = "Translation starting",
                modifier = TranslatingModifier,
                tint = tint,
            )
        }
    }
}

@Composable
private fun NotTranslatedIndicator(
    enabled: Boolean,
    modifier: Modifier = Modifier,
    downloaded: Boolean = true,
    onClick: (ChapterTranslationAction) -> Unit,
) {
    Box(
        modifier = modifier
            .size(IconButtonTokens.StateLayerSize)
            .commonClickable(
                enabled = enabled,
                hapticFeedback = LocalHapticFeedback.current,
                onLongClick = { onClick(ChapterTranslationAction.START) },
                onClick = { onClick(ChapterTranslationAction.START) },
            )
            .secondaryItemAlpha(),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_translate_circle),
            contentDescription = stringResource(
                if (downloaded) ATMR.strings.manga_translate else ATMR.strings.manga_translate_download_first,
            ),
            modifier = Modifier.size(IndicatorSize),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!downloaded) {
            Icon(
                imageVector = Icons.Outlined.Download,
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(10.dp)
                    .background(MaterialTheme.colorScheme.surface, CircleShape),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun TranslatingIndicator(
    enabled: Boolean,
    onClick: (ChapterTranslationAction) -> Unit,
    modifier: Modifier = Modifier,
    snapshot: TranslationProgressSnapshot? = null,
    translationState: Translation.State = Translation.State.TRANSLATING,
) {
    var isMenuExpanded by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .size(IconButtonTokens.StateLayerSize)
            .commonClickable(
                enabled = enabled,
                hapticFeedback = LocalHapticFeedback.current,
                onLongClick = { onClick(ChapterTranslationAction.CANCEL) },
                onClick = { onClick(ChapterTranslationAction.DETAILS) },
            ),
        contentAlignment = Alignment.Center,
    ) {
        val strokeColor = when (translationState) {
            Translation.State.TRANSLATING -> MaterialTheme.colorScheme.primary
            Translation.State.PAUSED -> WarningColor
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
        // TachiyomiAT: stage-based progress fraction
        val isDeterminate = snapshot != null && snapshot.totalStages > 0
        val progressFraction = if (isDeterminate) snapshot!!.fraction else 0f

        CircularProgressIndicator(
            progress = { if (isDeterminate) progressFraction else 0f },
            modifier = IndicatorModifier,
            color = strokeColor,
            strokeWidth = IndicatorStrokeWidth,
            trackColor = Color.Transparent,
            strokeCap = StrokeCap.Butt,
        )

        DropdownMenu(expanded = isMenuExpanded, onDismissRequest = { isMenuExpanded = false }) {
            //  hotfix: a PAUSED queue entry has no work to cancel — offer
            // the explicit resume instead.
            if (translationState == Translation.State.PAUSED) {
                DropdownMenuItem(
                    text = { Text(text = stringResource(MR.strings.action_resume)) },
                    onClick = {
                        onClick(ChapterTranslationAction.START)
                        isMenuExpanded = false
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(text = stringResource(MR.strings.action_cancel)) },
                onClick = {
                    onClick(ChapterTranslationAction.CANCEL)
                    isMenuExpanded = false
                },
            )
        }
        if (translationState == Translation.State.PAUSED) {
            Icon(
                painter = painterResource(R.drawable.ic_pause_24dp),
                contentDescription = snapshot?.pauseReason?.let { "Translation paused: $it" }
                    ?: "Translation paused",
                modifier = TranslatingModifier,
                tint = strokeColor,
            )
        } else {
            Icon(
                painter = painterResource(R.drawable.ic_translate),
                contentDescription = null,
                modifier = TranslatingModifier,
                tint = strokeColor,
            )
        }
        // TachiyomiAT: stage-based percentage label under the icon
        if (isDeterminate) {
            val percentageText = "${(progressFraction * 100).toInt()}%"
            Text(
                text = percentageText,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 0.dp),
                color = strokeColor,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun TranslatedIndicator(
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: (ChapterTranslationAction) -> Unit,
    translationState: Translation.State = Translation.State.TRANSLATED,
) {
    var isMenuExpanded by remember { mutableStateOf(false) }
    val tint = if (translationState == Translation.State.READY_WITH_WARNINGS) {
        WarningColor
    } else {
        MaterialTheme.colorScheme.tertiary
    }
    Box(
        modifier = modifier
            .size(IconButtonTokens.StateLayerSize)
            .commonClickable(
                enabled = enabled,
                hapticFeedback = LocalHapticFeedback.current,
                //  slice 1: tap routes to the progress drawer so completed
                // progress/warning detail stays discoverable; the retranslate
                // and delete actions remain on long-press.
                onLongClick = { isMenuExpanded = true },
                onClick = {
                    onClick(
                        translationIndicatorTapAction(
                            state = translationState,
                            hasPendingRequest = false,
                        )
                    )
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_translate_circle_filled),
            //  Phase 5: READY_WITH_WARNINGS is conveyed by LABEL, not by
            // tint alone (this icon previously had no description at all).
            contentDescription = TranslationUiTruth
                .forChapterIndicator(translationState, null)
                .contentDescription,
            modifier = Modifier.size(IndicatorSize),
            tint = tint,
        )
        DropdownMenu(expanded = isMenuExpanded, onDismissRequest = { isMenuExpanded = false }) {
            DropdownMenuItem(
                text = { Text(text = stringResource(ATMR.strings.manga_translate)) },
                onClick = {
                    onClick(ChapterTranslationAction.START)
                    isMenuExpanded = false
                },
            )
            DropdownMenuItem(
                text = { Text(text = stringResource(MR.strings.action_delete)) },
                onClick = {
                    onClick(ChapterTranslationAction.DELETE)
                    isMenuExpanded = false
                },
            )
        }
    }
}

@Composable
private fun ErrorIndicator(
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: (ChapterTranslationAction) -> Unit,
    translationState: Translation.State = Translation.State.ERROR,
) {
    var isMenuExpanded by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .size(IconButtonTokens.StateLayerSize)
            .commonClickable(
                enabled = enabled,
                hapticFeedback = LocalHapticFeedback.current,
                //  slice 1: tap opens the progress drawer with the failure
                // detail; retry and delete live in the long-press menu
                // ( hotfix: documented affordances instead of a bare
                // long-press that silently routed START).
                onLongClick = { isMenuExpanded = true },
                onClick = {
                    onClick(
                        translationIndicatorTapAction(
                            state = translationState,
                            hasPendingRequest = false,
                        )
                    )
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.ErrorOutline,
            //  Phase 5: the failure truth names the translation state and
            // the available retry, instead of the generic chapter error copy.
            contentDescription = TranslationUiTruth
                .forChapterIndicator(translationState, null)
                .contentDescription,
            modifier = Modifier.size(IndicatorSize),
            tint = MaterialTheme.colorScheme.error,
        )
        DropdownMenu(expanded = isMenuExpanded, onDismissRequest = { isMenuExpanded = false }) {
            DropdownMenuItem(
                text = { Text(text = stringResource(ATMR.strings.manga_translate)) },
                onClick = {
                    onClick(ChapterTranslationAction.START)
                    isMenuExpanded = false
                },
            )
            DropdownMenuItem(
                text = { Text(text = stringResource(MR.strings.action_delete)) },
                onClick = {
                    onClick(ChapterTranslationAction.DELETE)
                    isMenuExpanded = false
                },
            )
        }
    }
}

private fun Modifier.commonClickable(
    enabled: Boolean,
    hapticFeedback: HapticFeedback,
    onLongClick: () -> Unit,
    onClick: () -> Unit,
) = this.combinedClickable(
    enabled = enabled,
    onLongClick = {
        onLongClick()
        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
    },
    onClick = onClick,
    role = Role.Button,
    interactionSource = null,
    indication = ripple(
        bounded = false,
        radius = IconButtonTokens.StateLayerSize / 2,
    ),
)

private val IndicatorSize = 23.dp
private val IndicatorPadding = 2.dp

private val WarningColor = Color(0xFFFFA000)

// To match composable parameter name when used later
private val IndicatorStrokeWidth = IndicatorPadding

private val IndicatorModifier = Modifier
    .size(IndicatorSize)
    .padding(IndicatorPadding)
private val TranslatingModifier = Modifier
    .size(IndicatorSize - 7.dp)
