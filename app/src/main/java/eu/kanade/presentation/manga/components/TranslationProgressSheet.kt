package eu.kanade.presentation.manga.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.translation.batch.BatchPhase
import eu.kanade.translation.model.StageCount
import eu.kanade.translation.model.TranslationProgressSnapshot
import eu.kanade.translation.model.TranslationProgressStage
import tachiyomi.i18n.MR
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun TranslationProgressSheet(
    chapterName: String,
    snapshot: TranslationProgressSnapshot,
    onDismissRequest: () -> Unit,
    onCancel: () -> Unit,
) {
    AdaptiveSheet(onDismissRequest = onDismissRequest) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(MaterialTheme.padding.medium),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
        ) {
            Text(
                text = stringResource(ATMR.strings.manga_translation_progress),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = chapterName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (snapshot.aborted) {
                Text(
                    text = stringResource(ATMR.strings.manga_batch_aborted, snapshot.abortedReason ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold,
                )
            }

            val animatedFraction by animateFloatAsState(
                targetValue = snapshot.fraction,
                label = "overall_progress",
            )
            LinearProgressIndicator(
                progress = { animatedFraction },
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                text = stringResource(
                    ATMR.strings.manga_translation_counts,
                    snapshot.donePages,
                    snapshot.totalPages,
                    snapshot.queuedCount,
                    snapshot.failedCount,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )

            val activeText = when {
                snapshot.totalPages == 0 -> stringResource(ATMR.strings.manga_translation_no_progress)
                snapshot.activeStage != null && snapshot.activePage > 0 ->
                    stringResource(
                        ATMR.strings.manga_translation_active,
                        snapshot.activePage,
                        stageLabel(snapshot.activeStage),
                    )
                snapshot.failedCount > 0 -> stringResource(ATMR.strings.reader_translation_stage_failed)
                else -> stringResource(ATMR.strings.reader_translation_stage_done)
            }
            Text(
                text = activeText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (snapshot.partialPages > 0) {
                Text(
                    text = stringResource(ATMR.strings.manga_batch_partial, snapshot.partialPages),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }

            if (snapshot.totalPages > 0) {
                HorizontalDivider()
                StageRow(stringResource(ATMR.strings.manga_batch_stage_ocr), snapshot.perStage[BatchPhase.OCR])
                StageRow(stringResource(ATMR.strings.manga_batch_stage_inpaint), snapshot.perStage[BatchPhase.INPAINT])
                StageRow(stringResource(ATMR.strings.manga_batch_stage_translate), snapshot.perStage[BatchPhase.TRANSLATE])
                StageRow(stringResource(ATMR.strings.manga_batch_stage_render), snapshot.perStage[BatchPhase.RENDER])
            }

            if (snapshot.groupedFailures.isNotEmpty()) {
                HorizontalDivider()
                Text(
                    text = stringResource(ATMR.strings.manga_batch_failures),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.error,
                )
                val formattedFailures = remember(snapshot.groupedFailures) {
                    snapshot.groupedFailures.entries.take(10).map { (reason, pageKeys) ->
                        reason to pageKeys.take(8).joinToString(", ")
                    }
                }
                formattedFailures.forEach { (reason, pagesString) ->
                    Text(
                        text = stringResource(
                            ATMR.strings.manga_batch_failure_group,
                            reason,
                            pagesString,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismissRequest) {
                    Text(text = stringResource(MR.strings.action_close))
                }
                Button(onClick = onCancel) {
                    Text(text = stringResource(MR.strings.action_cancel))
                }
            }
        }
    }
}

@Composable
private fun StageRow(label: String, count: StageCount?) {
    if (count == null || count.total == 0) return
    val stageFraction = if (count.total > 0) {
        count.done.toFloat() / count.total
    } else 0f
    val animatedFraction by animateFloatAsState(
        targetValue = stageFraction.coerceIn(0f, 1f),
        label = "stage_progress",
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        LinearProgressIndicator(
            progress = { animatedFraction },
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = MaterialTheme.padding.small),
        )
        Text(
            text = stringResource(ATMR.strings.manga_batch_stage_count, count.done, count.total),
            style = MaterialTheme.typography.bodySmall,
            color = if (count.failed > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun stageLabel(stage: TranslationProgressStage): String {
    return when (stage) {
        TranslationProgressStage.QUEUED -> stringResource(ATMR.strings.reader_translation_stage_queued)
        TranslationProgressStage.OCR -> stringResource(ATMR.strings.reader_translation_stage_ocr)
        TranslationProgressStage.INPAINT -> stringResource(ATMR.strings.reader_translation_stage_inpaint)
        TranslationProgressStage.TRANSLATE -> stringResource(ATMR.strings.reader_translation_stage_translate)
        TranslationProgressStage.RENDER -> stringResource(ATMR.strings.reader_translation_stage_render)
        TranslationProgressStage.DONE -> stringResource(ATMR.strings.reader_translation_stage_done)
        TranslationProgressStage.FAILED -> stringResource(ATMR.strings.reader_translation_stage_failed)
    }
}
