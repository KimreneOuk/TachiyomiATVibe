package eu.kanade.presentation.manga.components

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import eu.kanade.presentation.components.AdaptiveSheet
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

            LinearProgressIndicator(
                progress = { snapshot.fraction },
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
                else -> stringResource(ATMR.strings.reader_translation_stage_done)
            }
            Text(
                text = activeText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (snapshot.totalPages > 0) {
                val ocrCount = snapshot.pages.count { it.stage == TranslationProgressStage.OCR }
                val inpaintCount = snapshot.pages.count { it.stage == TranslationProgressStage.INPAINT }
                val translateCount = snapshot.pages.count { it.stage == TranslationProgressStage.TRANSLATE }
                val renderCount = snapshot.pages.count { it.stage == TranslationProgressStage.RENDER }
                val doneCount = snapshot.pages.count { it.stage == TranslationProgressStage.DONE }

                Text(
                    text = "OCR: $ocrCount  |  Inpaint: $inpaintCount  |  Translate: $translateCount  |  Render: $renderCount  |  Done: $doneCount",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val failures = snapshot.pages.filter { it.stage == TranslationProgressStage.FAILED }
            if (failures.isNotEmpty()) {
                HorizontalDivider()
                Text(
                    text = stringResource(ATMR.strings.manga_translation_failed_reasons),
                    style = MaterialTheme.typography.titleSmall,
                )
                failures.take(8).forEach { page ->
                    Text(
                        text = "${page.index}. ${page.errorMessage ?: stageLabel(page.stage)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            if (snapshot.pages.isNotEmpty()) {
                HorizontalDivider()
                Text(
                    text = stringResource(ATMR.strings.manga_translation_pages),
                    style = MaterialTheme.typography.titleSmall,
                )
                snapshot.pages.take(80).forEach { page ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = page.index.toString(),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            text = stageLabel(page.stage),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (page.stage == TranslationProgressStage.FAILED) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
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
