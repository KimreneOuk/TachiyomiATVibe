package eu.kanade.presentation.manga.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.translation.model.PageDisplayState
import eu.kanade.translation.model.TranslationBatchPhase
import eu.kanade.translation.model.TranslationProgressSnapshot
import eu.kanade.translation.model.TranslationProgressStage
import tachiyomi.i18n.MR
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource

private val BatchDone = Color(0xFF20C978)
private val TierFinalColor = Color(0xFF1A8C58)
private const val CHIP_COLUMNS = 6

@Composable
fun TranslationProgressSheet(
    chapterName: String,
    snapshot: TranslationProgressSnapshot,
    onDismissRequest: () -> Unit,
    onReadNow: () -> Unit,
    onCancel: () -> Unit,
    onPauseResume: ((paused: Boolean) -> Unit)? = null,
) {
    var paused by remember(snapshot.chapterId) { mutableStateOf(false) }
    val isTerminal = snapshot.batchPhase == TranslationBatchPhase.FINISHED
    val animatedFraction by animateFloatAsState(
        targetValue = snapshot.fraction.coerceIn(0f, 1f),
        label = "batch_progress",
    )

    AdaptiveSheet(onDismissRequest = onDismissRequest) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = chapterName,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = batchStatusLabel(snapshot),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (snapshot.aborted) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(
                        text = stringResource(ATMR.strings.manga_batch_aborted, snapshot.abortedReason.orEmpty()),
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            TierStatusBar(snapshot)

            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                LinearProgressIndicator(
                    progress = { animatedFraction },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(
                            ATMR.strings.manga_batch_page_progress,
                            snapshot.activePage.coerceAtLeast(snapshot.donePages.coerceAtMost(snapshot.totalPages)),
                            snapshot.totalPages,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(
                            ATMR.strings.manga_batch_percent,
                            (animatedFraction * 100).toInt(),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            if (snapshot.pages.isNotEmpty()) {
                PageTierChipGrid(snapshot.pages)
                TierLegend(snapshot)
            }

            if (snapshot.groupedFailures.isNotEmpty()) {
                FailureSummary(snapshot)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = onReadNow,
                    enabled = snapshot.canReadTranslated,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(ATMR.strings.manga_batch_read_now))
                }
                if (!isTerminal && onPauseResume != null) {
                    OutlinedButton(
                        onClick = {
                            paused = !paused
                            onPauseResume(paused)
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(if (paused) MR.strings.action_resume else MR.strings.action_pause))
                    }
                }
                if (!isTerminal) {
                    Button(
                        onClick = onCancel,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                    ) {
                        Text(stringResource(MR.strings.action_cancel))
                    }
                } else {
                    Spacer(Modifier.weight(1f))
                    Button(onClick = onDismissRequest) {
                        Text(stringResource(MR.strings.action_close))
                    }
                }
            }
        }
    }
}

@Composable
private fun TierStatusBar(snapshot: TranslationProgressSnapshot) {
    val readable = snapshot.displayReadyPages
    val finalDone = snapshot.processedPages
    val working = snapshot.pages.count {
        it.displayState == PageDisplayState.CANDIDATE_RUNNING ||
            it.displayState == PageDisplayState.REFRESHING_WITH_COMMITTED_RESULT
    }
    val failed = snapshot.pages.count {
        it.displayState == PageDisplayState.FAILED_NO_RESULT ||
            it.displayState == PageDisplayState.FAILED_WITH_COMMITTED_RESULT
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        TierStatCell(
            BatchDone,
            stringResource(ATMR.strings.manga_batch_tier_readable),
            readable,
            Modifier.weight(1f),
        )
        TierStatCell(
            TierFinalColor,
            stringResource(ATMR.strings.manga_batch_tier_final),
            finalDone,
            Modifier.weight(1f),
        )
        TierStatCell(
            MaterialTheme.colorScheme.primary,
            stringResource(ATMR.strings.manga_batch_tier_working),
            working,
            Modifier.weight(1f),
        )
        TierStatCell(
            MaterialTheme.colorScheme.error,
            stringResource(ATMR.strings.manga_batch_tier_failed),
            failed,
            Modifier.weight(1f),
        )
    }
}

@Composable
private fun TierStatCell(color: Color, label: String, count: Int, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(color.copy(alpha = 0.14f), RoundedCornerShape(9.dp))
            .padding(horizontal = 6.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.titleMedium,
            color = color,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
        Text(label, style = MaterialTheme.typography.labelSmall, color = color.copy(alpha = 0.85f), maxLines = 1)
    }
}

@Composable
private fun PageTierChipGrid(pages: List<TranslationProgressSnapshot.Page>) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        pages.chunked(CHIP_COLUMNS).forEach { rowPages ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                rowPages.forEach { page ->
                    PageTierChip(page, modifier = Modifier.weight(1f))
                }
                repeat(CHIP_COLUMNS - rowPages.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun PageTierChip(page: TranslationProgressSnapshot.Page, modifier: Modifier = Modifier) {
    val failed = page.displayState == PageDisplayState.FAILED_NO_RESULT ||
        page.displayState == PageDisplayState.FAILED_WITH_COMMITTED_RESULT
    val isFinal = page.processed
    val readable = page.displayReady
    val working = page.stage.isRunning ||
        page.displayState == PageDisplayState.CANDIDATE_RUNNING ||
        page.displayState == PageDisplayState.REFRESHING_WITH_COMMITTED_RESULT
    val container = when {
        failed -> MaterialTheme.colorScheme.errorContainer
        isFinal -> TierFinalColor.copy(alpha = 0.16f)
        readable -> BatchDone.copy(alpha = 0.12f)
        working -> MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    }
    val content = when {
        failed -> MaterialTheme.colorScheme.error
        isFinal -> TierFinalColor
        readable -> BatchDone
        working -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        modifier = modifier
            .background(container, RoundedCornerShape(7.dp))
            .padding(horizontal = 3.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            text = page.index.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = content,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            TierBlock(tier1State(page), TierBlockKind.TIER1, failed, Modifier.weight(1f))
            TierBlock(tier2State(page), TierBlockKind.TIER2, failed, Modifier.weight(1f))
        }
    }
}

private enum class TierBlockState { PENDING, ACTIVE, DONE }
private enum class TierBlockKind { TIER1, TIER2 }

private fun tier1State(page: TranslationProgressSnapshot.Page): TierBlockState = when {
    page.ocrDone && page.translateDone -> TierBlockState.DONE
    page.stage == TranslationProgressStage.OCR ||
        page.stage == TranslationProgressStage.TRANSLATE -> TierBlockState.ACTIVE
    else -> TierBlockState.PENDING
}

private fun tier2State(page: TranslationProgressSnapshot.Page): TierBlockState = when {
    page.stage == TranslationProgressStage.DONE -> TierBlockState.DONE
    page.stage == TranslationProgressStage.INPAINT ||
        page.stage == TranslationProgressStage.RENDER -> TierBlockState.ACTIVE
    else -> TierBlockState.PENDING
}

@Composable
private fun TierBlock(
    state: TierBlockState,
    kind: TierBlockKind,
    failed: Boolean,
    modifier: Modifier = Modifier,
) {
    val color = when {
        failed -> MaterialTheme.colorScheme.error
        state == TierBlockState.DONE -> if (kind == TierBlockKind.TIER1) BatchDone else TierFinalColor
        state == TierBlockState.ACTIVE -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    Box(
        modifier = modifier
            .height(6.dp)
            .background(color, RoundedCornerShape(1.5.dp)),
    )
}

@Composable
private fun TierLegend(snapshot: TranslationProgressSnapshot) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LegendDot(MaterialTheme.colorScheme.primary, stringResource(ATMR.strings.manga_batch_tier_working))
            LegendDot(BatchDone, stringResource(ATMR.strings.manga_batch_tier_readable))
            LegendDot(TierFinalColor, stringResource(ATMR.strings.manga_batch_tier_final))
            LegendDot(MaterialTheme.colorScheme.error, stringResource(ATMR.strings.manga_batch_tier_failed))
        }
        Text(
            text = stringResource(ATMR.strings.manga_batch_tier_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (snapshot.partialPages > 0 || snapshot.failedCount > 0) {
            Text(
                text = stringResource(
                    ATMR.strings.manga_batch_warnings,
                    snapshot.partialPages + snapshot.failedCount,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(6.dp).background(color, RoundedCornerShape(50)))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FailureSummary(snapshot: TranslationProgressSnapshot) {
    HorizontalDivider()
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(
            text = stringResource(ATMR.strings.manga_batch_failures),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.error,
        )
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

@Composable
private fun batchStatusLabel(snapshot: TranslationProgressSnapshot): String = when (snapshot.batchPhase) {
    TranslationBatchPhase.IDLE -> stringResource(ATMR.strings.manga_translation_no_progress)
    TranslationBatchPhase.FIRST_PASS -> when {
        snapshot.activeStages.contains(TranslationProgressStage.OCR) -> stringResource(ATMR.strings.manga_batch_status_ocr)
        snapshot.activeStages.contains(TranslationProgressStage.TRANSLATE) -> stringResource(ATMR.strings.manga_batch_status_translate)
        snapshot.activeStages.contains(TranslationProgressStage.INPAINT) -> stringResource(ATMR.strings.manga_batch_status_inpaint)
        snapshot.activeStages.contains(TranslationProgressStage.RENDER) -> stringResource(ATMR.strings.manga_batch_status_render)
        else -> stringResource(ATMR.strings.manga_batch_first_pass, snapshot.donePages, snapshot.totalPages)
    }
    TranslationBatchPhase.FINALIZING -> stringResource(ATMR.strings.manga_batch_finalizing)
    TranslationBatchPhase.FINISHED -> stringResource(ATMR.strings.reader_translation_stage_done)
}
