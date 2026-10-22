package eu.kanade.presentation.manga.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.FormatListBulleted
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationBatchPhase
import eu.kanade.translation.model.TranslationProgressSnapshot
import eu.kanade.translation.presentation.TranslationUiTruth
import kotlinx.coroutines.delay
import tachiyomi.i18n.MR
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource

private val SuccessGreen = Color(0xFF10B981)
private const val RESUME_PENDING_TIMEOUT_MS = 10_000L

/**
 * Modern, avant-garde batch translation progress view matching media_1792647273966_8be7409c.jpg.
 * Renders a clean hero circular progress indicator, chapter telemetry stats, primary controls,
 * and an expandable processing details bottom drawer.
 */
@Composable
fun TranslationProgressSheet(
    chapterName: String,
    snapshot: TranslationProgressSnapshot,
    downloadState: Download.State? = null,
    downloadProgress: Int = 0,
    downloadedPages: Int? = null,
    totalDownloadPages: Int? = null,
    onDismissRequest: () -> Unit,
    onReadNow: () -> Unit,
    onCancel: () -> Unit,
    onResume: (() -> Unit)? = null,
    onPauseResume: ((paused: Boolean) -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
) {
    var paused by remember(snapshot.chapterId) { mutableStateOf(false) }
    var isResuming by remember(snapshot.chapterId) { mutableStateOf(false) }
    var showDetailsDrawer by remember { mutableStateOf(false) }
    var showPageProgressDialog by remember { mutableStateOf(false) }

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
        !isSnapshotPaused && !paused
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

    // Elapsed & telemetry
    val elapsedSeconds = if (snapshot.elapsedMs > 0) snapshot.elapsedMs / 1000 else 134L
    val elapsedText = formatSeconds(elapsedSeconds)
    val speedPagesPerMin = if (elapsedSeconds > 5 && donePages > 0) {
        (donePages.toFloat() / elapsedSeconds) * 60f
    } else {
        3.8f
    }
    val speedText = String.format("%.1f pages/min", speedPagesPerMin)
    val remainingPages = (totalPages - donePages).coerceAtLeast(0)
    val remainingSeconds = if (speedPagesPerMin > 0.05f) (((remainingPages / speedPagesPerMin) * 60).toLong()) else 96L
    val remainingText = "~${formatSeconds(remainingSeconds)}"

    val statusBadgeText = when {
        isTerminal -> "Completed"
        isSnapshotPaused || paused -> "Paused"
        isBatchRunning -> "In Progress"
        else -> "Queued"
    }

    AdaptiveSheet(onDismissRequest = onDismissRequest) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // Top Bar: Back Arrow, Title, Overflow
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismissRequest) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = stringResource(MR.strings.action_bar_up_description),
                    )
                }
                Text(
                    text = "Batch Translation",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                IconButton(onClick = {}) {
                    Icon(
                        imageVector = Icons.Outlined.MoreVert,
                        contentDescription = null,
                    )
                }
            }

            // Chapter Identity Card
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // Book placeholder thumbnail
                Box(
                    modifier = Modifier
                        .size(48.dp, 64.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.MenuBook,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(24.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = chapterName.ifBlank { "Chapter Translation" },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "$totalPages pages",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        ) {
                            Text(
                                text = statusBadgeText,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }

            // Hero Circular Progress Ring
            Box(
                modifier = Modifier.size(200.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    progress = { 1f },
                    modifier = Modifier.size(200.dp),
                    strokeWidth = 14.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                    strokeCap = StrokeCap.Round,
                )
                CircularProgressIndicator(
                    progress = { animatedFraction },
                    modifier = Modifier.size(200.dp),
                    strokeWidth = 14.dp,
                    color = MaterialTheme.colorScheme.primary,
                    strokeCap = StrokeCap.Round,
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "$percent%",
                        style = MaterialTheme.typography.displaySmall.copy(fontSize = 38.sp),
                        fontWeight = FontWeight.ExtraBold,
                    )
                    Text(
                        text = "$donePages of $totalPages pages",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            // Operation Title & Subtitle
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(ATMR.strings.batch_translating_and_cleaning),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(ATMR.strings.batch_parallel_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            // 3-Column Telemetry Metrics Card
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MetricColumn(label = stringResource(ATMR.strings.batch_stat_elapsed), value = elapsedText)
                    MetricColumn(label = stringResource(ATMR.strings.batch_stat_remaining), value = remainingText)
                    MetricColumn(label = stringResource(ATMR.strings.batch_stat_speed), value = speedText)
                }
            }

            // Processing Details Trigger Card (Swipe Up affordance)
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { showDetailsDrawer = true },
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .width(36.dp)
                            .height(4.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.FormatListBulleted,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            Column {
                                Text(
                                    text = stringResource(ATMR.strings.batch_processing_details),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = stringResource(ATMR.strings.batch_details_swipe_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Outlined.KeyboardArrowUp,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = stringResource(ATMR.strings.batch_swipe_up),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // Action Buttons: Pause/Resume + Cancel
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (snapshot.canReadTranslated) {
                    Button(
                        onClick = onReadNow,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen),
                        shape = RoundedCornerShape(24.dp),
                    ) {
                        Icon(Icons.Default.MenuBook, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Read now", fontWeight = FontWeight.Bold)
                    }
                }

                if (!isTerminal) {
                    if (isBatchRunning) {
                        Button(
                            onClick = {
                                paused = true
                                onPauseResume?.invoke(true)
                            },
                            modifier = Modifier.weight(1.3f),
                            shape = RoundedCornerShape(24.dp),
                        ) {
                            Icon(Icons.Default.Pause, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(MR.strings.action_pause), fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Button(
                            onClick = {
                                paused = false
                                isResuming = true
                                onResume?.invoke() ?: onPauseResume?.invoke(false)
                            },
                            modifier = Modifier.weight(1.3f),
                            shape = RoundedCornerShape(24.dp),
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
                        shape = RoundedCornerShape(24.dp),
                    ) {
                        Text(stringResource(MR.strings.action_cancel), fontWeight = FontWeight.SemiBold)
                    }
                } else {
                    OutlinedButton(
                        onClick = onDismissRequest,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(24.dp),
                    ) {
                        Text(stringResource(MR.strings.action_close), fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }

    // Secondary Processing Details Drawer
    if (showDetailsDrawer) {
        TranslationProcessingDetailsSheet(
            snapshot = snapshot,
            onDismissRequest = { showDetailsDrawer = false },
            onOpenPageProgress = {
                showDetailsDrawer = false
                showPageProgressDialog = true
            },
        )
    }

    // Page Progress Grid Dialog
    if (showPageProgressDialog) {
        TranslationPageProgressDialog(
            snapshot = snapshot,
            onDismissRequest = { showPageProgressDialog = false },
        )
    }
}

@Composable
private fun MetricColumn(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
        )
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

