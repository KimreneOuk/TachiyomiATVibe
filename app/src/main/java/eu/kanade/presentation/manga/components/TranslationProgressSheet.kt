package eu.kanade.presentation.manga.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.presentation.components.designsystem.DesignTokens
import eu.kanade.presentation.components.designsystem.SectionHeading
import eu.kanade.presentation.components.designsystem.SegmentedPillBar
import eu.kanade.presentation.components.designsystem.SheetHeader
import eu.kanade.tachiyomi.util.system.activeNetworkState
import eu.kanade.tachiyomi.util.system.networkStateFlow
import eu.kanade.translation.model.BatchPhase
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationBatchPhase
import eu.kanade.translation.model.TranslationProgressSnapshot
import eu.kanade.translation.model.TranslationProgressStage
import eu.kanade.translation.model.TranslationRequestFailureKind
import eu.kanade.translation.model.TranslationRequestPhase
import eu.kanade.translation.presentation.TranslationUiTruth
import kotlinx.coroutines.delay
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.i18n.MR
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

private const val RESUME_PENDING_TIMEOUT_MS = 10_000L
private const val PAGE_GRID_COLUMNS = 17
private val ReadNowGreen = Color(0xFF10B981)

/** One row of the batch queue card: a chapter and its coarse translation state. */
data class BatchChapterStatus(
    val chapterId: Long,
    val title: String,
    val state: Translation.State,
    val donePages: Int? = null,
    val totalPages: Int? = null,
    val failureReason: String? = null,
)

/**
 * Batch translation progress sheet: a pinned Focus hero (ring, phase, telemetry,
 * actions) and a Details tab carrying queue, pipeline, per-page and live status.
 */
@Composable
fun TranslationProgressSheet(
    snapshot: TranslationProgressSnapshot,
    queue: List<BatchChapterStatus>,
    onDismissRequest: () -> Unit,
    onReadNow: () -> Unit,
    onCancel: () -> Unit,
    onResume: (() -> Unit)? = null,
    onPauseResume: ((paused: Boolean) -> Unit)? = null,
    onRetryChapter: ((Long) -> Unit)? = null,
) {
    var paused by remember(snapshot.chapterId) { mutableStateOf(false) }
    var isResuming by remember(snapshot.chapterId) { mutableStateOf(false) }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    val downloadPreferences = remember { Injekt.get<DownloadPreferences>() }
    val context = LocalContext.current
    val wifiRequired by downloadPreferences.downloadOnlyOverWifi().changes()
        .collectAsState(initial = downloadPreferences.downloadOnlyOverWifi().get())
    val networkState by remember(context) { context.networkStateFlow() }
        .collectAsState(initial = context.activeNetworkState())

    val isWifiWaiting = !snapshot.aborted &&
        (
            (
                wifiRequired &&
                    !networkState.isWifi &&
                    (
                        snapshot.requestState?.phase == TranslationRequestPhase.WAITING_FOR_DOWNLOAD ||
                            snapshot.requestState?.phase == TranslationRequestPhase.STARTING ||
                            (
                                snapshot.requestState?.phase == TranslationRequestPhase.DOWNLOAD_FAILED &&
                                    snapshot.requestState.failureKind == TranslationRequestFailureKind.DOWNLOADER_STOPPED
                                )
                        )
                ) ||
                snapshot.requestState?.reason?.contains("Wi-Fi", ignoreCase = true) == true
            )

    val phaseActive = snapshot.batchPhase != TranslationBatchPhase.IDLE ||
        snapshot.state == Translation.State.TRANSLATING
    LaunchedEffect(snapshot.chapterId, snapshot.batchPhase, snapshot.state) {
        if (isResuming && phaseActive) isResuming = false
    }
    LaunchedEffect(snapshot.chapterId, isResuming) {
        if (isResuming) {
            delay(RESUME_PENDING_TIMEOUT_MS)
            isResuming = false
        }
    }

    val isSnapshotPaused = snapshot.state == Translation.State.PAUSED || snapshot.pauseReason != null
    val isBatchRunning = (snapshot.batchPhase != TranslationBatchPhase.IDLE || snapshot.state == Translation.State.TRANSLATING) &&
        !isSnapshotPaused &&
        !paused
    val isTerminal = (snapshot.batchPhase == TranslationBatchPhase.FINISHED || snapshot.state == Translation.State.TRANSLATED) &&
        !isSnapshotPaused

    val totalPages = snapshot.totalPages.coerceAtLeast(1)
    val donePages = snapshot.donePages
    val fraction = (donePages.toFloat() / totalPages.toFloat()).coerceIn(0f, 1f)
    val percent = (fraction * 100).toInt()

    val animatedFraction by animateFloatAsState(
        targetValue = fraction,
        animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing),
        label = "hero_progress",
    )

    // Honest telemetry: placeholders stay em-dash until real numbers exist.
    val elapsedSeconds = snapshot.elapsedMs.takeIf { it > 0 }?.let { it / 1000 }
    val elapsedText = elapsedSeconds?.let(::formatSeconds) ?: "—"
    val speedPagesPerMin = snapshot.pagesPerMinute?.takeIf { it > 0.05f }
        ?: elapsedSeconds?.takeIf { it > 5 && donePages > 0 }?.let { (donePages.toFloat() / it) * 60f }
    val speedText = speedPagesPerMin
        ?.let { stringResource(ATMR.strings.batch_speed_pages_per_min, it) }
        ?: "—"
    val remainingText = snapshot.estimatedRemainingMs?.takeIf { it > 0 }
        ?.let { "~" + formatSeconds(it / 1000) }
        ?: "—"

    val chipText = batchPhaseChipText(
        snapshot = snapshot,
        isTerminal = isTerminal,
        isPaused = isSnapshotPaused || paused,
        totalPages = totalPages,
        donePages = donePages,
    )

    AdaptiveSheet(onDismissRequest = onDismissRequest) {
        Surface(
            color = DesignTokens.SheetBackground,
            shape = DesignTokens.ShapeSheet,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 24.dp),
            ) {
                SheetHeader(
                    icon = Icons.Outlined.Translate,
                    title = stringResource(ATMR.strings.batch_translation_title),
                    subtitle = batchStatusHeaderSubtitle(snapshot, isResuming),
                    onClose = onDismissRequest,
                )

                SegmentedPillBar(
                    tabs = listOf(
                        stringResource(ATMR.strings.batch_tab_focus),
                        stringResource(ATMR.strings.batch_tab_details),
                    ),
                    selectedIndex = selectedTab,
                    onTabSelected = { selectedTab = it },
                )

                if (selectedTab == 0) {
                    FocusPanel(
                        snapshot = snapshot,
                        isWifiWaiting = isWifiWaiting,
                        isBatchRunning = isBatchRunning,
                        isTerminal = isTerminal,
                        isPaused = isSnapshotPaused || paused,
                        chipText = chipText,
                        animatedFraction = animatedFraction,
                        percent = percent,
                        donePages = donePages,
                        totalPages = totalPages,
                        elapsedText = elapsedText,
                        remainingText = remainingText,
                        speedText = speedText,
                        isResuming = isResuming,
                        canRetry = onRetryChapter != null,
                        onRetry = { onRetryChapter?.invoke(snapshot.chapterId) },
                        onReadNow = onReadNow,
                        onPause = {
                            paused = true
                            onPauseResume?.invoke(true)
                        },
                        onResume = {
                            paused = false
                            isResuming = true
                            onResume?.invoke() ?: onPauseResume?.invoke(false)
                        },
                        onCancel = onCancel,
                        onDismissRequest = onDismissRequest,
                    )
                } else {
                    DetailsPanel(
                        snapshot = snapshot,
                        queue = queue,
                        chipText = chipText,
                        totalPages = totalPages,
                        elapsedText = elapsedText,
                        remainingText = remainingText,
                        speedText = speedText,
                        onRetryChapter = onRetryChapter,
                    )
                }
            }
        }
    }
}

@Composable
private fun FocusPanel(
    snapshot: TranslationProgressSnapshot,
    isWifiWaiting: Boolean,
    isBatchRunning: Boolean,
    isTerminal: Boolean,
    isPaused: Boolean,
    chipText: String,
    animatedFraction: Float,
    percent: Int,
    donePages: Int,
    totalPages: Int,
    elapsedText: String,
    remainingText: String,
    speedText: String,
    isResuming: Boolean,
    canRetry: Boolean,
    onRetry: () -> Unit,
    onReadNow: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .padding(top = 8.dp)
                .size(160.dp),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(
                progress = { 1f },
                modifier = Modifier.size(160.dp),
                strokeWidth = 14.dp,
                color = DesignTokens.CardBorder.copy(alpha = 0.35f),
                strokeCap = StrokeCap.Round,
            )
            CircularProgressIndicator(
                progress = { animatedFraction },
                modifier = Modifier.size(160.dp),
                strokeWidth = 14.dp,
                color = DesignTokens.AccentPrimary,
                strokeCap = StrokeCap.Round,
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "$percent%",
                    fontSize = 34.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
                Text(
                    text = "$donePages / $totalPages",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = DesignTokens.TextMuted,
                )
            }
        }

        PhaseChip(text = chipText, pulsing = isBatchRunning)

        if (isWifiWaiting) {
            Banner(
                text = stringResource(ATMR.strings.manga_batch_download_wifi_warning),
                container = MaterialTheme.colorScheme.tertiaryContainer,
                content = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        } else if (snapshot.requestState?.phase == TranslationRequestPhase.DOWNLOAD_FAILED && !snapshot.aborted) {
            val failureReason = snapshot.requestState.reason.takeUnless { it.isNullOrBlank() }
            Banner(
                text = listOfNotNull(
                    stringResource(ATMR.strings.manga_batch_phase_download_failed),
                    failureReason,
                ).joinToString(" — "),
                container = MaterialTheme.colorScheme.errorContainer,
                content = MaterialTheme.colorScheme.onErrorContainer,
            )
        } else if (snapshot.aborted) {
            Banner(
                text = snapshot.abortedReason
                    ?.let { stringResource(ATMR.strings.manga_batch_aborted, it) }
                    ?: stringResource(ATMR.strings.manga_batch_phase_cancelled),
                container = MaterialTheme.colorScheme.errorContainer,
                content = MaterialTheme.colorScheme.onErrorContainer,
            )
        }

        MetricsCard(
            elapsedText = elapsedText,
            remainingText = remainingText,
            speedText = speedText,
        )

        val sheetRetryTruth = TranslationUiTruth.forSheetRetryAction(snapshot, canRetry)
        if (sheetRetryTruth != null && canRetry) {
            Button(
                onClick = onRetry,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .height(44.dp)
                    .semantics { contentDescription = sheetRetryTruth.contentDescription },
                colors = ButtonDefaults.buttonColors(
                    containerColor = DesignTokens.AccentPrimary,
                    contentColor = DesignTokens.TextOnAccent,
                ),
                shape = DesignTokens.ShapePill,
            ) {
                Text(
                    text = stringResource(ATMR.strings.manga_batch_retry),
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (snapshot.canReadTranslated) {
                Button(
                    onClick = onReadNow,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = ReadNowGreen),
                    shape = DesignTokens.ShapePill,
                ) {
                    Text(
                        text = stringResource(ATMR.strings.manga_batch_read_now),
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            if (!isTerminal) {
                if (isBatchRunning) {
                    Button(
                        onClick = onPause,
                        modifier = Modifier.weight(1.3f),
                        shape = DesignTokens.ShapePill,
                    ) {
                        Icon(Icons.Default.Pause, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(MR.strings.action_pause), fontWeight = FontWeight.Bold)
                    }
                } else {
                    Button(
                        onClick = onResume,
                        modifier = Modifier.weight(1.3f),
                        shape = DesignTokens.ShapePill,
                    ) {
                        if (isResuming) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(Modifier.width(8.dp))
                        } else {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(stringResource(MR.strings.action_resume), fontWeight = FontWeight.Bold)
                    }
                }

                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f),
                    shape = DesignTokens.ShapePill,
                ) {
                    Text(stringResource(MR.strings.action_cancel), fontWeight = FontWeight.SemiBold)
                }
            } else {
                OutlinedButton(
                    onClick = onDismissRequest,
                    modifier = Modifier.weight(1f),
                    shape = DesignTokens.ShapePill,
                ) {
                    Text(stringResource(MR.strings.action_close), fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun DetailsPanel(
    snapshot: TranslationProgressSnapshot,
    queue: List<BatchChapterStatus>,
    chipText: String,
    totalPages: Int,
    elapsedText: String,
    remainingText: String,
    speedText: String,
    onRetryChapter: ((Long) -> Unit)?,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SectionHeading(title = stringResource(ATMR.strings.batch_section_queue))
        DetailCard {
            queue.forEachIndexed { index, chapter ->
                QueueRow(chapter = chapter, onRetryChapter = onRetryChapter)
                if (index < queue.lastIndex) {
                    HorizontalRule()
                }
            }
        }

        SectionHeading(title = stringResource(ATMR.strings.batch_section_pipeline))
        DetailCard {
            PipelineStage(
                label = stringResource(ATMR.strings.manga_batch_stage_ocr_name),
                valueText = stageValueText(
                    processed = snapshot.perStage[BatchPhase.OCR]?.processed ?: 0,
                    totalPages = totalPages,
                    extra = snapshot.ocrDurationMs?.takeIf { it > 0 }?.let { formatSeconds(it / 1000) },
                ),
                fraction = stageFraction(snapshot.perStage[BatchPhase.OCR]?.processed ?: 0, totalPages),
            )
            HorizontalRule()
            PipelineStage(
                label = stringResource(ATMR.strings.manga_batch_stage_ai_name),
                valueText = stageValueText(
                    processed = snapshot.perStage[BatchPhase.TRANSLATE]?.processed ?: 0,
                    totalPages = totalPages,
                ),
                fraction = stageFraction(snapshot.perStage[BatchPhase.TRANSLATE]?.processed ?: 0, totalPages),
            )
            HorizontalRule()
            PipelineStage(
                label = stringResource(ATMR.strings.manga_batch_stage_inpaint_name),
                valueText = stageValueText(
                    processed = snapshot.perStage[BatchPhase.INPAINT]?.processed ?: 0,
                    totalPages = totalPages,
                ),
                fraction = stageFraction(snapshot.perStage[BatchPhase.INPAINT]?.processed ?: 0, totalPages),
            )
        }

        val pages = snapshot.pages
        if (pages.isNotEmpty()) {
            SectionHeading(title = stringResource(ATMR.strings.batch_section_pages))
            DetailCard {
                val doneCount = pages.count { it.stage == TranslationProgressStage.DONE || it.displayReady }
                val failedCount = pages.count { it.stage == TranslationProgressStage.FAILED }
                val runningCount = pages.count { it.stage.isRunning }
                val queuedCount = (pages.size - doneCount - failedCount - runningCount).coerceAtLeast(0)
                PageGrid(pages = pages)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    LegendEntry(
                        color = DesignTokens.AccentPrimary,
                        label = stringResource(ATMR.strings.manga_batch_stage_done) + " $doneCount",
                    )
                    if (failedCount > 0) {
                        LegendEntry(
                            color = MaterialTheme.colorScheme.error,
                            label = stringResource(ATMR.strings.manga_batch_stage_failed) + " $failedCount",
                        )
                    }
                    LegendEntry(
                        color = DesignTokens.SheetBackground,
                        label = stringResource(ATMR.strings.manga_batch_stage_queued) + " $queuedCount",
                    )
                }
            }
        }

        SectionHeading(title = stringResource(ATMR.strings.batch_live_status))
        DetailCard {
            LiveStatusRow(
                icon = Icons.Outlined.Description,
                label = stringResource(ATMR.strings.batch_live_current_step),
                value = chipText,
            )
            HorizontalRule()
            LiveStatusRow(
                icon = Icons.Outlined.Schedule,
                label = stringResource(ATMR.strings.batch_live_time_elapsed),
                value = elapsedText,
            )
            HorizontalRule()
            LiveStatusRow(
                icon = Icons.Outlined.HourglassEmpty,
                label = stringResource(ATMR.strings.batch_live_estimated_remaining),
                value = remainingText,
            )
            HorizontalRule()
            LiveStatusRow(
                icon = Icons.Outlined.Speed,
                label = stringResource(ATMR.strings.batch_live_processing_speed),
                value = speedText,
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
    }
}

@Composable
private fun PhaseChip(text: String, pulsing: Boolean) {
    val transition = rememberInfiniteTransition(label = "phase_dot")
    val dotAlpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "phase_dot_alpha",
    )
    Row(
        modifier = Modifier
            .padding(top = 12.dp)
            .clip(DesignTokens.ShapePill)
            .background(DesignTokens.CardFill)
            .border(1.dp, DesignTokens.CardBorder, DesignTokens.ShapePill)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(DesignTokens.AccentPrimary.copy(alpha = if (pulsing) dotAlpha else 1f)),
        )
        Text(
            text = text,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun Banner(text: String, container: Color, content: Color) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Default.Warning, contentDescription = null)
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun MetricsCard(elapsedText: String, remainingText: String, speedText: String) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        shape = DesignTokens.ShapeCard,
        color = DesignTokens.CardFill,
        border = androidx.compose.foundation.BorderStroke(1.dp, DesignTokens.CardBorder),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            MetricCell(stringResource(ATMR.strings.batch_stat_elapsed), elapsedText, Modifier.weight(1f))
            VerticalRule()
            MetricCell(stringResource(ATMR.strings.batch_stat_remaining), remainingText, Modifier.weight(1f))
            VerticalRule()
            MetricCell(stringResource(ATMR.strings.batch_stat_speed), speedText, Modifier.weight(1f))
        }
    }
}

@Composable
private fun MetricCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(vertical = 11.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = value,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            fontSize = 10.sp,
            color = DesignTokens.TextMuted,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun QueueRow(chapter: BatchChapterStatus, onRetryChapter: ((Long) -> Unit)?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val icon = when (chapter.state) {
            Translation.State.TRANSLATED, Translation.State.READY_WITH_WARNINGS ->
                QueueIcon(Icons.Default.Check, DesignTokens.AccentPrimary)
            Translation.State.TRANSLATING -> null
            Translation.State.ERROR -> QueueIcon(Icons.Outlined.ErrorOutline, MaterialTheme.colorScheme.error)
            Translation.State.PAUSED -> QueueIcon(Icons.Outlined.PauseCircle, DesignTokens.TextMuted)
            else -> QueueIcon(Icons.Outlined.Schedule, DesignTokens.TextMuted)
        }
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(DesignTokens.SheetBackground),
            contentAlignment = Alignment.Center,
        ) {
            if (chapter.state == Translation.State.TRANSLATING) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = DesignTokens.AccentPrimary,
                )
            } else {
                icon?.let {
                    Icon(
                        imageVector = it.vector,
                        contentDescription = null,
                        tint = it.tint,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = chapter.title,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subText = when (chapter.state) {
                Translation.State.TRANSLATED, Translation.State.READY_WITH_WARNINGS ->
                    listOfNotNull(
                        stringResource(ATMR.strings.batch_queue_done),
                        chapter.totalPages?.takeIf { it > 0 }?.let { "$it" },
                    ).joinToString(" · ")
                Translation.State.ERROR -> listOfNotNull(
                    stringResource(ATMR.strings.manga_batch_tier_failed),
                    chapter.failureReason,
                ).joinToString(" · ")
                Translation.State.QUEUE -> stringResource(ATMR.strings.batch_queue_queued)
                Translation.State.PAUSED -> stringResource(ATMR.strings.manga_batch_status_paused)
                else -> null
            }
            subText?.let {
                Text(
                    text = it,
                    fontSize = 11.sp,
                    color = if (chapter.state == Translation.State.ERROR) {
                        MaterialTheme.colorScheme.error
                    } else {
                        DesignTokens.TextMuted
                    },
                )
            }
            if (chapter.state == Translation.State.TRANSLATING) {
                val total = chapter.totalPages?.takeIf { it > 0 }
                val done = chapter.donePages ?: 0
                if (total != null) {
                    Box(
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(DesignTokens.ShapePill)
                            .background(DesignTokens.SheetBackground),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth((done.toFloat() / total).coerceIn(0f, 1f))
                                .fillMaxHeight()
                                .clip(DesignTokens.ShapePill)
                                .background(DesignTokens.AccentPrimary),
                        )
                    }
                }
            }
        }

        when (chapter.state) {
            Translation.State.TRANSLATED, Translation.State.READY_WITH_WARNINGS ->
                QueueBadge(
                    text = stringResource(ATMR.strings.manga_batch_tier_readable),
                    color = DesignTokens.AccentPrimary,
                )
            Translation.State.TRANSLATING -> {
                val total = chapter.totalPages?.takeIf { it > 0 }
                val done = chapter.donePages ?: 0
                if (total != null) {
                    QueueBadge(text = "${(done.toFloat() / total * 100).toInt()}%", color = DesignTokens.AccentPrimary)
                }
            }
            Translation.State.ERROR -> onRetryChapter?.let { retry ->
                OutlinedButton(
                    onClick = { retry(chapter.chapterId) },
                    shape = DesignTokens.ShapePill,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.height(30.dp),
                ) {
                    Text(
                        text = stringResource(ATMR.strings.batch_queue_retry),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            else -> Unit
        }
    }
}

private data class QueueIcon(val vector: ImageVector, val tint: Color)

@Composable
private fun QueueBadge(text: String, color: Color) {
    Text(
        text = text,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        color = color,
        modifier = Modifier
            .clip(DesignTokens.ShapePill)
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 9.dp, vertical = 3.dp),
    )
}

@Composable
private fun PipelineStage(label: String, valueText: String, fraction: Float) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = label,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = valueText,
                fontSize = 11.sp,
                color = DesignTokens.TextMuted,
            )
        }
        Spacer(Modifier.height(9.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(DesignTokens.ShapePill)
                .background(DesignTokens.SheetBackground),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .clip(DesignTokens.ShapePill)
                    .background(
                        if (fraction >= 1f) MaterialTheme.colorScheme.tertiary else DesignTokens.AccentPrimary,
                    ),
            )
        }
    }
}

@Composable
private fun PageGrid(pages: List<TranslationProgressSnapshot.Page>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        pages.chunked(PAGE_GRID_COLUMNS).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                row.forEach { page ->
                    val color = when {
                        page.stage == TranslationProgressStage.DONE || page.displayReady -> DesignTokens.AccentPrimary
                        page.stage == TranslationProgressStage.FAILED -> MaterialTheme.colorScheme.error
                        page.stage.isRunning -> DesignTokens.AccentPrimary.copy(alpha = 0.45f)
                        else -> DesignTokens.SheetBackground
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(3.dp))
                            .background(color),
                    )
                }
            }
        }
    }
}

@Composable
private fun LegendEntry(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color),
        )
        Spacer(Modifier.width(6.dp))
        Text(text = label, fontSize = 11.sp, color = DesignTokens.TextMuted)
    }
}

@Composable
private fun LiveStatusRow(icon: ImageVector, label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = DesignTokens.TextMuted,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = DesignTokens.TextMuted,
            )
        }
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun DetailCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .clip(DesignTokens.ShapeCard)
            .background(DesignTokens.CardFill)
            .border(1.dp, DesignTokens.CardBorder, DesignTokens.ShapeCard),
    ) {
        content()
    }
}

@Composable
private fun HorizontalRule() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(1.dp)
            .background(DesignTokens.CardBorder.copy(alpha = 0.45f)),
    )
}

@Composable
private fun VerticalRule() {
    Box(
        modifier = Modifier
            .height(44.dp)
            .width(1.dp)
            .background(DesignTokens.CardBorder),
    )
}

private fun stageFraction(processed: Int, totalPages: Int): Float =
    if (totalPages <= 0) 0f else (processed.toFloat() / totalPages).coerceIn(0f, 1f)

private fun stageValueText(processed: Int, totalPages: Int, extra: String? = null): String =
    listOfNotNull("$processed / $totalPages", extra).joinToString(" · ")

@Composable
private fun batchPhaseChipText(
    snapshot: TranslationProgressSnapshot,
    isTerminal: Boolean,
    isPaused: Boolean,
    totalPages: Int,
    donePages: Int,
): String {
    val stageLabels = mapOf(
        TranslationProgressStage.OCR to stringResource(ATMR.strings.manga_batch_stage_ocr_name),
        TranslationProgressStage.TRANSLATE to stringResource(ATMR.strings.manga_batch_stage_ai_name),
        TranslationProgressStage.INPAINT to stringResource(ATMR.strings.manga_batch_stage_inpaint_name),
        TranslationProgressStage.RENDER to stringResource(ATMR.strings.manga_batch_stage_render_name),
    )
    return when {
        isTerminal -> stringResource(ATMR.strings.manga_batch_phase_completed)
        isPaused -> stringResource(ATMR.strings.manga_batch_status_paused)
        else -> {
            val activeStage = snapshot.activeStages.firstOrNull { stageLabels.containsKey(it) }
            val currentPage = (snapshot.activePage.takeIf { it > 0 } ?: (donePages + 1))
                .coerceIn(1, totalPages)
            val pageSuffix = stringResource(ATMR.strings.manga_batch_page_progress, currentPage, totalPages)
            when {
                activeStage != null -> stageLabels.getValue(activeStage) + " · " + pageSuffix
                snapshot.batchPhase == TranslationBatchPhase.FIRST_PASS ->
                    stringResource(ATMR.strings.manga_batch_first_pass, donePages, totalPages)
                snapshot.batchPhase == TranslationBatchPhase.FINALIZING ->
                    stringResource(ATMR.strings.manga_batch_finalizing)
                else -> stringResource(ATMR.strings.manga_batch_phase_queued)
            }
        }
    }
}

private fun formatSeconds(totalSeconds: Long): String {
    val m = totalSeconds / 60
    val s = totalSeconds % 60
    return if (m > 0) "${m}m ${s}s" else "${s}s"
}

internal fun queuePositionLabel(position: Int, total: Int): String =
    TranslationUiTruth.queuedPositionLabel(position, total)

internal fun ordinalSuffix(value: Int): String =
    TranslationUiTruth.ordinalSuffixOf(value)

internal fun batchStatusHeaderSubtitle(
    snapshot: TranslationProgressSnapshot,
    isResuming: Boolean = false,
): String = TranslationUiTruth.batchStatusLine(snapshot, isResuming).fallback
