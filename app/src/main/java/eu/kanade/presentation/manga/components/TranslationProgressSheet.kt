package eu.kanade.presentation.manga.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FormatPaint
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.translation.pipeline.batch.BatchPhase
import eu.kanade.translation.model.AiBatchProgress
import eu.kanade.translation.model.AiPageProgressState
import eu.kanade.translation.model.BatchHeroPhase
import eu.kanade.translation.model.BatchHeroProjection
import eu.kanade.translation.model.StageCount
import eu.kanade.translation.model.TranslationBatchPhase
import eu.kanade.translation.model.TranslationProgressSnapshot
import eu.kanade.translation.model.TranslationProgressStage
import eu.kanade.translation.model.TranslationRequestPhase
import eu.kanade.translation.ui.BatchStatusLineKind
import eu.kanade.translation.ui.TranslationUiTruth
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.delay
import tachiyomi.domain.translation.TranslationPreferences
import tachiyomi.i18n.MR
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.DateFormat
import java.util.Date

private val SuccessGreen = Color(0xFF10B981)
private val ReadyBadgeColor = Color(0xFF059669)
private val WarningAmber = Color(0xFFF59E0B)

/**
 * T934 U.5: bound on the snapshot-driven "Resuming…" hold. The manager owns
 * the asynchronous cooldown check and can reject an early retry while the
 * batch phase stays IDLE; the timeout returns the button instead of leaving
 * it permanently disabled.
 */
private const val RESUME_PENDING_TIMEOUT_MS = 10_000L

@Composable
fun TranslationProgressSheet(
    chapterName: String,
    snapshot: TranslationProgressSnapshot,
    // TachiyomiAT T911 slice 1: read-only download join so the drawer shows the
    // download phase/progress while the batch waits for the chapter download.
    downloadState: Download.State? = null,
    downloadProgress: Int = 0,
    downloadedPages: Int? = null,
    totalDownloadPages: Int? = null,
    onDismissRequest: () -> Unit,
    onReadNow: () -> Unit,
    onCancel: () -> Unit,
    onResume: (() -> Unit)? = null,
    onPauseResume: ((paused: Boolean) -> Unit)? = null,
    // TachiyomiAT T918: restart affordance for a cancelled (terminal-aborted)
    // or terminal-failed batch. Null keeps the banner-only shape for callers
    // that cannot restart the batch.
    onRetry: (() -> Unit)? = null,
) {
    var paused by remember(snapshot.chapterId) { mutableStateOf(false) }
    // T934 U.5: the "Resuming…" hold is snapshot-driven. It is armed by the
    // Resume action and holds until the batch truth shows the phase left
    // IDLE (rebuild/running/finalizing/finished). The old synchronous
    // self-reset — which cleared the state inside the click handler and
    // during composition, so "Resuming…" could never actually render — is
    // deleted.
    var isResuming by remember(snapshot.chapterId) { mutableStateOf(false) }
    // T934 U.2: the Advanced view (stage cards, page grid, failure groups,
    // queue detail) behind the Simple default; the choice is persisted in
    // the translation preferences so it survives sheet reopenings.
    val translationPreferences = remember { Injekt.get<TranslationPreferences>() }
    var advancedView by remember {
        mutableStateOf(translationPreferences.translationProgressSheetAdvancedView().get())
    }
    val phaseActive = snapshot.batchPhase != TranslationBatchPhase.IDLE ||
        snapshot.state == eu.kanade.translation.model.Translation.State.TRANSLATING
    LaunchedEffect(snapshot.chapterId, snapshot.batchPhase, snapshot.state) {
        if (isResuming && phaseActive) isResuming = false
    }
    LaunchedEffect(snapshot.chapterId, isResuming) {
        if (isResuming) {
            delay(RESUME_PENDING_TIMEOUT_MS)
            isResuming = false
        }
    }
    val isSnapshotPaused = snapshot.state == eu.kanade.translation.model.Translation.State.PAUSED ||
        snapshot.pauseReason != null
    val isTerminal = snapshot.batchPhase == TranslationBatchPhase.FINISHED && !isSnapshotPaused
    val animatedFraction by animateFloatAsState(
        targetValue = snapshot.fraction.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing),
        label = "batch_progress",
    )
    // TachiyomiAT T911 slice 1: phase-aware hero. Unknown translation totals are
    // never rendered as 0% / 0/0; the owning phase is shown instead.
    val hero = BatchHeroProjection.of(
        snapshot = snapshot,
        downloadState = downloadState,
        downloadProgress = downloadProgress,
        downloadedPages = downloadedPages,
        totalDownloadPages = totalDownloadPages,
    )

    AdaptiveSheet(onDismissRequest = onDismissRequest) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Top Bar: Chapter Title & Live Status Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = chapterName,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = batchHeaderStatusText(snapshot, isResuming),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                LiveStatusPill(
                    snapshot = snapshot,
                    paused = paused || isSnapshotPaused,
                    isResuming = isResuming,
                )
            }

            // Aborted banner if any
            if (snapshot.aborted) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Text(
                            text = stringResource(ATMR.strings.manga_batch_aborted, snapshot.abortedReason.orEmpty()),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            // T934 LI-5: a terminal persistence-rejected presentation. The
            // tracker carried the typed rejection reason in the snapshot's
            // nonDurableFailureReason but nothing rendered it (the abort
            // banner's twin). Same red-banner presentation as the abort
            // banner; abort keeps precedence so both banners never stack.
            // The reason is bounded — it can embed a whole rejection chain.
            if (snapshot.nonDurableFailure && !snapshot.aborted) {
                val rejectionReason = snapshot.nonDurableFailureReason.orEmpty().take(200)
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Text(
                            text = stringResource(ATMR.strings.manga_batch_not_saved, rejectionReason),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            // TachiyomiAT T918: a terminal-aborted (cancelled) or terminal-failed
            // batch must not dead-end in the banner — offer the restart the truth
            // projection allows, only when the caller wired a restart callback.
            // The P5 truth owns label/wording so no surface contradicts another.
            val sheetRetryTruth = TranslationUiTruth.forSheetRetryAction(snapshot, onRetry != null)
            if (sheetRetryTruth != null && onRetry != null) {
                Button(
                    onClick = onRetry,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = sheetRetryTruth.contentDescription },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                    ),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(ATMR.strings.manga_batch_retry),
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            // Hero Metric & Overall Progress Card
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        when (hero) {
                            is BatchHeroProjection.Numeric -> {
                                Column {
                                    Text(
                                        text = "${(animatedFraction * 100).toInt()}%",
                                        style = MaterialTheme.typography.headlineMedium,
                                        fontWeight = FontWeight.Black,
                                        color = if (hero.isError || isTerminal) {
                                            if (hero.isError) {
                                                MaterialTheme.colorScheme.error
                                            } else {
                                                SuccessGreen
                                            }
                                        } else {
                                            MaterialTheme.colorScheme.primary
                                        },
                                    )
                                    Text(
                                        text = stringResource(
                                            ATMR.strings.manga_batch_page_progress,
                                            hero.donePages.coerceAtMost(hero.totalPages),
                                            hero.totalPages,
                                        ),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            is BatchHeroProjection.Phase -> {
                                // TachiyomiAT T911 slice 1: unknown translation total —
                                // show the owning phase, never a numeric 0% / 0/0.
                                Column {
                                    Text(
                                        text = phaseHeroLabel(hero),
                                        style = MaterialTheme.typography.headlineMedium,
                                        fontWeight = FontWeight.Black,
                                        color = if (hero.isError) {
                                            MaterialTheme.colorScheme.error
                                        } else {
                                            MaterialTheme.colorScheme.primary
                                        },
                                    )
                                    if (hero.phase == BatchHeroPhase.DOWNLOADING &&
                                        hero.donePages != null &&
                                        hero.totalPages != null
                                    ) {
                                        Text(
                                            text = stringResource(
                                                ATMR.strings.manga_batch_page_progress,
                                                hero.donePages,
                                                hero.totalPages,
                                            ),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }

                        if (snapshot.displayReadyPages > 0) {
                            Surface(
                                color = SuccessGreen.copy(alpha = 0.12f),
                                contentColor = ReadyBadgeColor,
                                shape = RoundedCornerShape(20.dp),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Icon(
                                        Icons.Default.MenuBook,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                    )
                                    Text(
                                        text = stringResource(
                                            ATMR.strings.manga_batch_pages_ready_hint,
                                            snapshot.displayReadyPages,
                                            snapshot.totalPages,
                                        ),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }
                        }
                    }

                    // TachiyomiAT T911 slice 1: determinate only when a real
                    // fraction exists (translation totals or download percent);
                    // indeterminate for unknown-total phases; nothing for errors.
                    // T934 U.2: a resume rebuild re-validates/re-adopts durable
                    // work — its run-record counters are not page progress, so
                    // the bar is indeterminate until the phase leaves the
                    // rebuild/restore window.
                    val rebuildInProgress = snapshot.batchPhase == TranslationBatchPhase.REBUILDING ||
                        snapshot.batchPhase == TranslationBatchPhase.RESTORING
                    when {
                        rebuildInProgress -> LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        )
                        hero is BatchHeroProjection.Numeric -> LinearProgressIndicator(
                            progress = { animatedFraction },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = if (hero.isError) {
                                MaterialTheme.colorScheme.error
                            } else if (isTerminal) {
                                SuccessGreen
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        )
                        hero is BatchHeroProjection.Phase -> when {
                            hero.isError -> Unit
                            hero.fraction != null -> LinearProgressIndicator(
                                progress = { hero.fraction },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                            )
                            else -> LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                            )
                        }
                    }
                }
            }

            // T934 U.2: Simple is the default view (hero + ready chip +
            // progress bar + ONE status line + actions); the Advanced depth —
            // stage cards, page grid, failure groups, queue detail — sits
            // behind this persisted chevron toggle.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        val next = !advancedView
                        advancedView = next
                        translationPreferences.translationProgressSheetAdvancedView().set(next)
                    }
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(ATMR.strings.translation_sheet_advanced_toggle),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Icon(
                    imageVector = if (advancedView) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = stringResource(ATMR.strings.translation_sheet_advanced_toggle),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (advancedView) {
                // Live 4-Stage Pipeline Breakdown
                Text(
                    text = "Pipeline Stages",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                LivePipelineGrid(snapshot = snapshot)

                // Visual Page Matrix Strip
                if (snapshot.pages.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Page Overview",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        LazyRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            items(snapshot.pages, key = { it.pageKey }) { page ->
                                PageMiniChip(page = page)
                            }
                        }
                    }
                }

                // Failure groups (if any)
                if (snapshot.groupedFailures.isNotEmpty()) {
                    FailureSummary(snapshot)
                }

                // Queue detail: a queued chapter that is not first in line says so.
                snapshot.queuePosition
                    ?.takeIf { snapshot.queueTotal != null && it > 1 }
                    ?.let { position ->
                        Text(
                            text = queuePositionLabel(position, snapshot.queueTotal ?: position),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
            } else if (snapshot.failedCount > 0) {
                // T934 U.2: Simple view keeps failures to a one-line notice —
                // the per-group breakdown lives in the Advanced view.
                Text(
                    text = stringResource(ATMR.strings.translation_sheet_failures_notice, snapshot.failedCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            val isBatchRunning = !isSnapshotPaused &&
                (
                    snapshot.batchPhase == TranslationBatchPhase.FIRST_PASS ||
                        snapshot.batchPhase == TranslationBatchPhase.FINALIZING
                    )
            val canResume = onResume != null &&
                (
                    isSnapshotPaused ||
                        snapshot.state == eu.kanade.translation.model.Translation.State.QUEUE ||
                        snapshot.requestState != null
                    )

            // Bottom Action Bar
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (snapshot.canReadTranslated) {
                        Button(
                            onClick = onReadNow,
                            modifier = Modifier.weight(1.3f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = SuccessGreen,
                            ),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Icon(Icons.Default.MenuBook, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stringResource(ATMR.strings.manga_batch_read_now_count, snapshot.displayReadyPages),
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }

                    if (!isTerminal) {
                        if (isBatchRunning) {
                            if (onPauseResume != null) {
                                OutlinedButton(
                                    onClick = {
                                        paused = !paused
                                        onPauseResume(paused)
                                    },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(12.dp),
                                ) {
                                    Text(stringResource(if (paused) MR.strings.action_resume else MR.strings.action_pause))
                                }
                            }
                        } else if (canResume) {
                            if (isResuming) {
                                Button(
                                    onClick = {},
                                    enabled = false,
                                    modifier = Modifier.weight(1.3f),
                                    shape = RoundedCornerShape(12.dp),
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        // T934 U.6: resuming copy is truth-backed.
                                        text = stringResource(ATMR.strings.translation_status_resuming),
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            } else {
                                Button(
                                    onClick = {
                                        // T934 U.5: the snapshot-driven effects
                                        // clear the hold once the phase truth
                                        // leaves IDLE (or via the bounded
                                        // timeout when the manager's cooldown
                                        // check rejects the resume). The old
                                        // synchronous self-reset that made
                                        // "Resuming…" unobservable is gone.
                                        isResuming = true
                                        onResume?.invoke()
                                    },
                                    modifier = Modifier.weight(1.3f),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary,
                                    ),
                                    shape = RoundedCornerShape(12.dp),
                                ) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = stringResource(ATMR.strings.manga_batch_resume),
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                        }

                        Button(
                            onClick = onCancel,
                            modifier = Modifier.weight(0.9f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            ),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text(stringResource(MR.strings.action_cancel))
                        }
                    } else {
                        OutlinedButton(
                            onClick = onDismissRequest,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text(stringResource(MR.strings.action_close))
                        }
                    }
                }

                if (!isTerminal) {
                    Text(
                        text = stringResource(ATMR.strings.manga_batch_cancel_safe_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun LiveStatusPill(
    snapshot: TranslationProgressSnapshot,
    paused: Boolean,
    isResuming: Boolean = false,
) {
    val isAborted = snapshot.aborted
    // TachiyomiAT T911 slice 1: the pill must not say "Idle" while a batch
    // request is accepted/waiting for the chapter download.
    val requestPhase = snapshot.requestState?.phase
    // T934 completion oracle: FINISHED alone is NOT completion — a run with
    // failure/attention pages or a failed/paused outcome renders the
    // attention/failed pill, never the celebratory green "Completed".
    val isTerminal = snapshot.batchPhase == TranslationBatchPhase.FINISHED &&
        TranslationUiTruth.isCompletedOutcome(snapshot)
    val finishedWithAttention = snapshot.batchPhase == TranslationBatchPhase.FINISHED && !isTerminal
    val isTranslating = isResuming ||
        snapshot.batchPhase == TranslationBatchPhase.FIRST_PASS ||
        snapshot.batchPhase == TranslationBatchPhase.FINALIZING ||
        // T934 U.6: a resume rebuild is live work — the pill pulses with it.
        snapshot.batchPhase == TranslationBatchPhase.REBUILDING ||
        snapshot.batchPhase == TranslationBatchPhase.RESTORING ||
        snapshot.state == eu.kanade.translation.model.Translation.State.TRANSLATING

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alphaAnim by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse_alpha",
    )

    val pillColor = when {
        isAborted -> MaterialTheme.colorScheme.error
        paused -> WarningAmber
        isTerminal -> SuccessGreen
        // T934 completion oracle: finished-but-not-completed — error color
        // when the run outcome itself failed, attention amber otherwise.
        finishedWithAttention && snapshot.state == eu.kanade.translation.model.Translation.State.ERROR ->
            MaterialTheme.colorScheme.error
        finishedWithAttention -> WarningAmber
        requestPhase == TranslationRequestPhase.DOWNLOAD_FAILED ||
            requestPhase == TranslationRequestPhase.CANCELLED ||
            requestPhase == TranslationRequestPhase.ADMISSION_FAILED -> MaterialTheme.colorScheme.error
        requestPhase != null -> MaterialTheme.colorScheme.tertiary
        isResuming || isTranslating -> MaterialTheme.colorScheme.primary
        snapshot.state == eu.kanade.translation.model.Translation.State.QUEUE -> WarningAmber
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        color = pillColor.copy(alpha = 0.12f),
        contentColor = pillColor,
        shape = RoundedCornerShape(24.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(
                        color = pillColor.copy(alpha = if ((isTranslating || isResuming) && !paused) alphaAnim else 1f),
                        shape = CircleShape,
                    ),
            )
            Text(
                text = when {
                    isAborted -> "Aborted"
                    paused -> stringResource(ATMR.strings.manga_batch_status_paused)
                    isTerminal -> "Completed"
                    // T934 completion oracle: finished-but-not-completed
                    // renders the failed/attention state, never the
                    // celebratory completed label.
                    finishedWithAttention &&
                        snapshot.state == eu.kanade.translation.model.Translation.State.ERROR -> "Failed"
                    finishedWithAttention -> "Ready (Warnings)"
                    requestPhase != null -> when (requestPhase) {
                        TranslationRequestPhase.STARTING ->
                            stringResource(ATMR.strings.manga_batch_phase_accepted)
                        TranslationRequestPhase.WAITING_FOR_DOWNLOAD ->
                            stringResource(ATMR.strings.manga_batch_phase_waiting_for_download)
                        TranslationRequestPhase.PREPARING ->
                            stringResource(ATMR.strings.manga_batch_phase_preparing)
                        TranslationRequestPhase.DOWNLOAD_FAILED ->
                            stringResource(ATMR.strings.manga_batch_phase_download_failed)
                        TranslationRequestPhase.CANCELLED ->
                            stringResource(ATMR.strings.manga_batch_phase_cancelled)
                        TranslationRequestPhase.ADMISSION_FAILED ->
                            stringResource(ATMR.strings.manga_batch_phase_admission_failed)
                    }
                    // T934 U.6: the resume rebuild states surface in the pill too,
                    // below the request acknowledgement and above "In Progress".
                    snapshot.batchPhase == TranslationBatchPhase.REBUILDING ->
                        stringResource(ATMR.strings.translation_status_rebuilding_short)
                    snapshot.batchPhase == TranslationBatchPhase.RESTORING ->
                        stringResource(ATMR.strings.translation_status_restoring_short)
                    isResuming -> stringResource(ATMR.strings.translation_status_resuming)
                    isTranslating -> "In Progress"
                    snapshot.state == eu.kanade.translation.model.Translation.State.QUEUE -> stringResource(ATMR.strings.manga_batch_status_queued)
                    snapshot.state == eu.kanade.translation.model.Translation.State.READY_WITH_WARNINGS -> "Ready (Warnings)"
                    else -> "Idle"
                },
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun LivePipelineGrid(snapshot: TranslationProgressSnapshot) {
    val ocrCount = snapshot.perStage[BatchPhase.OCR]
    val translateCount = snapshot.perStage[BatchPhase.TRANSLATE]
    val inpaintCount = snapshot.perStage[BatchPhase.INPAINT]

    val total = snapshot.totalPages.coerceAtLeast(1)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PipelineStageCard(
                icon = Icons.Default.Search,
                label = stringResource(ATMR.strings.manga_batch_stage_ocr_name),
                stageCount = ocrCount,
                total = total,
                isActive = snapshot.activeStages.contains(TranslationProgressStage.OCR),
                modifier = Modifier.weight(1f),
            )
            PipelineStageCard(
                icon = Icons.Default.Translate,
                label = stringResource(ATMR.strings.manga_batch_stage_ai_name),
                stageCount = translateCount,
                total = total,
                aiProgress = snapshot.aiProgress,
                isActive = snapshot.activeStages.contains(TranslationProgressStage.TRANSLATE),
                modifier = Modifier.weight(1f),
            )
        }
        // T924 no-render batch design: the pipeline never runs an in-pass
        // overlay/render stage (the reader draws text overlays on demand), so
        // its card sat at 0/70 forever and read as a regression next to the
        // real stage counters. The genuinely user-facing readiness figure is
        // the "N of M pages ready to read" chip (displayReadyPages) above.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PipelineStageCard(
                icon = Icons.Default.CleaningServices,
                label = stringResource(ATMR.strings.manga_batch_stage_inpaint_name),
                stageCount = inpaintCount,
                total = total,
                isActive = snapshot.activeStages.contains(TranslationProgressStage.INPAINT),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun PipelineStageCard(
    icon: ImageVector,
    label: String,
    stageCount: StageCount?,
    total: Int,
    aiProgress: AiBatchProgress? = null,
    isActive: Boolean,
    modifier: Modifier = Modifier,
) {
    val done = aiProgress?.succeeded ?: stageCount?.succeeded ?: 0
    val progressTotal = aiProgress?.total ?: total
    val fraction = if (progressTotal > 0) (done.toFloat() / progressTotal).coerceIn(0f, 1f) else 0f
    val isComplete = done >= progressTotal && progressTotal > 0
    val progressDetail = aiProgress?.let { progress ->
        listOfNotNull(
            "${progress.pending} pending".takeIf { progress.pending > 0 },
            "${progress.buffered} buffered".takeIf { progress.buffered > 0 },
            "${progress.running} running/retrying".takeIf { progress.running > 0 },
            "${progress.failed} failed".takeIf { progress.failed > 0 },
        ).joinToString(" • ")
    }

    val cardBorderColor by animateColorAsState(
        targetValue = when {
            isActive -> MaterialTheme.colorScheme.primary
            isComplete -> SuccessGreen.copy(alpha = 0.4f)
            else -> Color.Transparent
        },
        label = "stage_border",
    )

    Surface(
        modifier = modifier
            .border(1.5.dp, cardBorderColor, RoundedCornerShape(12.dp)),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                }

                if (isComplete) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = SuccessGreen,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "$done / $progressTotal",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "${(fraction * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = if (isComplete) SuccessGreen else MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            if (!progressDetail.isNullOrBlank()) {
                Text(
                    text = progressDetail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun PageMiniChip(page: TranslationProgressSnapshot.Page) {
    val chipColor = when {
        page.displayReady -> SuccessGreen
        page.aiState == AiPageProgressState.FAILED -> MaterialTheme.colorScheme.error
        page.stage == TranslationProgressStage.FAILED -> MaterialTheme.colorScheme.error
        page.aiState == AiPageProgressState.BUFFERED -> WarningAmber
        page.aiState == AiPageProgressState.RUNNING -> MaterialTheme.colorScheme.primary
        page.stage.isRunning -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surfaceVariant
    }

    val contentColor = when {
        page.displayReady ||
            page.aiState == AiPageProgressState.FAILED ||
            page.stage == TranslationProgressStage.FAILED ||
            page.aiState == AiPageProgressState.BUFFERED ||
            page.aiState == AiPageProgressState.RUNNING ||
            page.stage.isRunning -> Color.White
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box(
        modifier = Modifier
            .size(32.dp)
            // T917 Phase 5: the chip's state truth is a semantic label —
            // failed/partial/queued state is never color-or-icon alone.
            .semantics {
                contentDescription = TranslationUiTruth.pageMiniChipLabel(page)
            }
            .background(chipColor, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "${page.index}",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = contentColor,
        )
    }
}

@Composable
private fun FailureSummary(snapshot: TranslationProgressSnapshot) {
    HorizontalDivider()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = stringResource(ATMR.strings.manga_batch_failures),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error,
            )
        }
        snapshot.groupedFailures.entries.take(4).forEach { (reason, pages) ->
            Text(
                text = stringResource(
                    ATMR.strings.manga_batch_failure_group,
                    reason,
                    pages.take(6).joinToString(", "),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** TachiyomiAT T911 slice 1 (post-review): subtitle copy for unknown-total phases. */
private fun phaseSubtitleLine(hero: BatchHeroProjection.Phase): String {
    val percent = hero.fraction?.let { " ${(it * 100).toInt()}%" }.orEmpty()
    return when (hero.phase) {
        BatchHeroPhase.ACCEPTED -> "Translation accepted — preparing batch..."
        BatchHeroPhase.WAITING_FOR_DOWNLOAD -> "Waiting for chapter download before translation"
        BatchHeroPhase.DOWNLOADING -> "Downloading chapter$percent..."
        BatchHeroPhase.DOWNLOAD_FAILED -> "Download failed — retry to continue"
        BatchHeroPhase.PREPARING -> "Preparing translation batch..."
        BatchHeroPhase.QUEUED -> "Queued — ready to resume remaining pages"
        BatchHeroPhase.PAUSED -> "Paused"
        BatchHeroPhase.FINALIZING -> "Finalizing translated chapter..."
        BatchHeroPhase.COMPLETED -> "All pages translated and ready to read"
        BatchHeroPhase.FAILED_NO_PAGES -> "Translation failed — chapter has no readable pages"
        BatchHeroPhase.CANCELLED -> "Translation cancelled — the chapter download was cancelled or removed"
        BatchHeroPhase.ADMISSION_FAILED ->
            "Translation could not be queued — check the source and translation settings"
        // T917 Phase 5 (D10): a partial download's available pages are real
        // work, but the source total is unknown — never a percentage.
        BatchHeroPhase.UNKNOWN_TOTAL ->
            hero.donePages?.let { "$it pages available · source total unknown" }
                ?: "Source page total unknown"
    }
}

/** T911 slice 2: truthful queue position (delegates to the truth layer wording). */
internal fun queuePositionLabel(position: Int, total: Int): String =
    TranslationUiTruth.queuedPositionLabel(position, total)

/** 1 -> "1st", 2 -> "2nd", 3 -> "3rd", 4 -> "4th", 11-13 -> "th". */
internal fun ordinalSuffix(value: Int): String = TranslationUiTruth.ordinalSuffixOf(value)

/**
 * T934 U.6: the sheet's ONE status line. Rebuild/restore wording is resolved
 * through the truth layer's status kind into the NEW string resources; every
 * other state keeps the historical subtitle rendering (existing truth
 * assertions stay untouched). The chain and its precedence live once in
 * [TranslationUiTruth.batchStatusLine].
 */
@Composable
private fun batchHeaderStatusText(
    snapshot: TranslationProgressSnapshot,
    isResuming: Boolean,
): String {
    val line = TranslationUiTruth.batchStatusLine(snapshot, isResuming)
    return when (line.kind) {
        BatchStatusLineKind.REBUILDING -> stringResource(ATMR.strings.translation_status_rebuilding)
        BatchStatusLineKind.RESTORING -> stringResource(
            ATMR.strings.translation_status_restoring,
            *line.formatArgs.toTypedArray(),
        )
        else -> batchStatusHeaderSubtitle(snapshot, isResuming)
    }
}

/** T911 slice 1: hero label for unknown-total phases (never 0/0). */
@Composable
private fun phaseHeroLabel(hero: BatchHeroProjection.Phase): String = when (hero.phase) {
    BatchHeroPhase.ACCEPTED -> stringResource(ATMR.strings.manga_batch_phase_accepted)
    BatchHeroPhase.WAITING_FOR_DOWNLOAD -> stringResource(ATMR.strings.manga_batch_phase_waiting_for_download)
    BatchHeroPhase.DOWNLOADING ->
        stringResource(ATMR.strings.manga_batch_phase_downloading, ((hero.fraction ?: 0f) * 100).toInt())
    BatchHeroPhase.DOWNLOAD_FAILED -> stringResource(ATMR.strings.manga_batch_phase_download_failed)
    BatchHeroPhase.PREPARING -> stringResource(ATMR.strings.manga_batch_phase_preparing)
    BatchHeroPhase.QUEUED -> stringResource(ATMR.strings.manga_batch_phase_queued)
    BatchHeroPhase.PAUSED -> stringResource(ATMR.strings.manga_batch_status_paused)
    BatchHeroPhase.FINALIZING -> stringResource(ATMR.strings.manga_batch_phase_finalizing)
    BatchHeroPhase.COMPLETED -> stringResource(ATMR.strings.manga_batch_phase_completed)
    BatchHeroPhase.FAILED_NO_PAGES -> stringResource(ATMR.strings.manga_batch_phase_failed_no_pages)
    BatchHeroPhase.CANCELLED -> stringResource(ATMR.strings.manga_batch_phase_cancelled)
    BatchHeroPhase.ADMISSION_FAILED -> stringResource(ATMR.strings.manga_batch_phase_admission_failed)
    BatchHeroPhase.UNKNOWN_TOTAL -> stringResource(ATMR.strings.manga_batch_phase_unknown_total)
}

internal fun batchStatusHeaderSubtitle(snapshot: TranslationProgressSnapshot, isResuming: Boolean = false): String {
    if (isResuming) return "Scanning completed pages & resuming batch..."
    snapshot.requestState?.let { request ->
        return when (request.phase) {
            eu.kanade.translation.model.TranslationRequestPhase.STARTING ->
                "Translation accepted — preparing batch..."
            eu.kanade.translation.model.TranslationRequestPhase.PREPARING ->
                "Preparing translation batch..."
            eu.kanade.translation.model.TranslationRequestPhase.WAITING_FOR_DOWNLOAD ->
                "Waiting for chapter download before translation"
            eu.kanade.translation.model.TranslationRequestPhase.DOWNLOAD_FAILED ->
                "Download failed — retry to continue"
            eu.kanade.translation.model.TranslationRequestPhase.CANCELLED ->
                "Translation cancelled — the chapter download was cancelled or removed"
            eu.kanade.translation.model.TranslationRequestPhase.ADMISSION_FAILED ->
                "Translation could not be queued — check the source and translation settings"
        } + request.reason.orEmpty().takeIf { it.isNotBlank() }?.let { " — $it" }.orEmpty()
    }
    if (snapshot.state == eu.kanade.translation.model.Translation.State.PAUSED ||
        snapshot.pauseReason != null
    ) {
        val reason = snapshot.pauseReason?.takeIf { it.isNotBlank() } ?: "Provider work is temporarily unavailable"
        val next = snapshot.nextEligibleRetryAtEpochMs?.let { retryAt ->
            DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(retryAt))
        }
        return if (next == null) "Paused — $reason" else "Paused — $reason · retry after $next"
    }
    return when (snapshot.batchPhase) {
        TranslationBatchPhase.IDLE -> when (snapshot.state) {
            eu.kanade.translation.model.Translation.State.QUEUE ->
                // T911 slice 2: a queued chapter that is not first in line must
                // say so instead of implying it can resume now.
                snapshot.queuePosition?.takeIf { position -> snapshot.queueTotal != null && position > 1 }
                    ?.let { position -> queuePositionLabel(position, snapshot.queueTotal ?: position) }
                    ?: "Queued — ready to resume remaining pages"
            eu.kanade.translation.model.Translation.State.TRANSLATING -> "Building context & scanning completed pages..."
            else -> "No active batch in progress"
        }
        TranslationBatchPhase.FIRST_PASS -> when {
            snapshot.activeStages.contains(TranslationProgressStage.TRANSLATE) -> {
                val progress = snapshot.aiProgress
                val detail = listOfNotNull(
                    "${progress.pending} pending".takeIf { progress.pending > 0 },
                    "${progress.buffered} buffered".takeIf { progress.buffered > 0 },
                    "${progress.running} running/retrying".takeIf { progress.running > 0 },
                    "${progress.failed} failed".takeIf { progress.failed > 0 },
                ).joinToString(", ")
                if (detail.isBlank()) "Translating dialogue with AI model..." else "AI translation: $detail"
            }
            snapshot.activeStages.contains(TranslationProgressStage.OCR) -> "Reading and detecting page text..."
            snapshot.activeStages.contains(TranslationProgressStage.INPAINT) -> "Cleaning speech bubbles..."
            snapshot.activeStages.contains(TranslationProgressStage.RENDER) -> "Rendering English text overlays..."
            else -> {
                // TachiyomiAT T911 slice 1 (post-review): unknown totals must never
                // render as "(0/0)" anywhere — route the fallback through the same
                // phase-aware projection as the hero.
                when (val hero = BatchHeroProjection.of(snapshot)) {
                    is BatchHeroProjection.Numeric ->
                        "Translating pages (${snapshot.donePages}/${snapshot.totalPages})"
                    is BatchHeroProjection.Phase -> phaseSubtitleLine(hero)
                }
            }
        }
        TranslationBatchPhase.FINALIZING -> "Finalizing translated chapter..."
        TranslationBatchPhase.FINISHED -> when {
            // T934 completion oracle: a run with failure/attention pages (or a
            // failed/paused/aborted outcome) never renders the celebratory
            // completed subtitle — it renders the attention state instead.
            TranslationUiTruth.isCompletedOutcome(snapshot) -> "All pages translated and ready to read"
            else -> "${TranslationUiTruth.pagesNeedingAttentionCount(snapshot)} pages need attention"
        }
        // T934 U.1/U.6: the resume rebuild/restore phases render the truth
        // chain's English fallback in this plain (non-composable) function;
        // the composable call site (batchHeaderStatusText) resolves the
        // resource-backed copy for these kinds first.
        TranslationBatchPhase.REBUILDING,
        TranslationBatchPhase.RESTORING,
        -> TranslationUiTruth.batchStatusLine(snapshot).fallback
    }
}
