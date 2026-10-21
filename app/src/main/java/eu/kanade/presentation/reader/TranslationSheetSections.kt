package eu.kanade.presentation.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import eu.kanade.tachiyomi.ui.reader.TranslationSettingsState
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationProgressSnapshot
import eu.kanade.translation.model.TranslationRequestPhase
import kotlinx.collections.immutable.ImmutableList
import tachiyomi.domain.translation.NeuralInpaintModel
import tachiyomi.domain.translation.OcrModel
import tachiyomi.domain.translation.PaddleOcrComputeDevice
import tachiyomi.i18n.MR
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Technical OCR configuration: model selection and, for Paddle v6, the
 * reader-scoped compute device override.
 */
@Composable
internal fun ColumnScope.OcrSection(
    state: TranslationSettingsState,
    onOcrModelChange: (OcrModel) -> Unit,
    onPaddleOcrComputeDeviceOverrideChange: (PaddleOcrComputeDevice?) -> Unit,
) {
    EngineListRow(
        title = stringResource(ATMR.strings.pref_ocr_model),
        entries = state.ocrModelEntries,
        value = state.ocrModel,
        onValueChange = onOcrModelChange,
    )

    if (state.ocrModel == OcrModel.PADDLEOCR_V6_SMALL) {
        val gpuLabel = stringResource(ATMR.strings.pref_paddle_ocr_device_gpu)
        val cpuLabel = stringResource(ATMR.strings.pref_paddle_ocr_device_cpu)
        val defaultLabel = when (state.paddleOcrDefaultComputeDevice) {
            PaddleOcrComputeDevice.GPU -> gpuLabel
            PaddleOcrComputeDevice.CPU -> cpuLabel
        }
        TextPreferenceWidget(
            title = stringResource(ATMR.strings.pref_paddle_ocr_device),
            subtitle = stringResource(ATMR.strings.reader_paddle_ocr_device_summary),
        )
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MaterialTheme.padding.medium),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
        ) {
            FilterChip(
                selected = state.paddleOcrComputeDeviceOverride == null,
                onClick = { onPaddleOcrComputeDeviceOverrideChange(null) },
                label = { Text(stringResource(ATMR.strings.reader_paddle_ocr_device_default, defaultLabel)) },
            )
            FilterChip(
                selected = state.paddleOcrComputeDeviceOverride == PaddleOcrComputeDevice.GPU,
                onClick = { onPaddleOcrComputeDeviceOverrideChange(PaddleOcrComputeDevice.GPU) },
                label = { Text(gpuLabel) },
            )
            FilterChip(
                selected = state.paddleOcrComputeDeviceOverride == PaddleOcrComputeDevice.CPU,
                onClick = { onPaddleOcrComputeDeviceOverrideChange(PaddleOcrComputeDevice.CPU) },
                label = { Text(cpuLabel) },
            )
        }
    }
}

/**
 * Inpainting configuration. Neural modes run on the shared LiteRT GPU
 * delegate with no CPU/NPU fallback — displayed as an honest, non-selectable
 * hardware label rather than a selector the runtime ignores.
 */
@Composable
internal fun ColumnScope.InpaintSection(
    inpaintingMode: String,
    neuralModel: NeuralInpaintModel,
    onTranslationInpaintingModeChange: (String) -> Unit,
    onTranslationInpaintingNeuralModelChange: (NeuralInpaintModel) -> Unit,
) {
    val entries = mapOf(
        "QUALITY" to stringResource(ATMR.strings.pref_inpainting_mode_quality),
        "BALANCE" to stringResource(ATMR.strings.pref_inpainting_mode_balance),
        "FAST" to stringResource(ATMR.strings.pref_inpainting_mode_fast),
    )

    EngineListRow(
        title = stringResource(ATMR.strings.pref_inpainting_mode),
        entries = entries,
        value = inpaintingMode,
        onValueChange = onTranslationInpaintingModeChange,
    )

    if (inpaintingMode == "BALANCE" || inpaintingMode == "QUALITY") {
        val neuralModelEntries = mapOf(
            NeuralInpaintModel.LAMA_LITERT_GPU to stringResource(ATMR.strings.pref_inpainting_mode_lama_gpu),
            NeuralInpaintModel.AOT_GAN to stringResource(ATMR.strings.pref_inpainting_neural_model_aot_gan),
        )
        EngineListRow(
            title = stringResource(ATMR.strings.pref_inpainting_neural_model),
            entries = neuralModelEntries,
            value = neuralModel,
            onValueChange = onTranslationInpaintingNeuralModelChange,
        )

        FilterChip(
            selected = true,
            onClick = {},
            enabled = false,
            label = {
                Text(text = stringResource(ATMR.strings.pref_inpainting_hardware_gpu_fixed))
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MaterialTheme.padding.medium),
        )
        Text(
            text = stringResource(ATMR.strings.pref_inpainting_hardware_override_summary),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium),
        )
    }
}

/**
 * Live translation activity: batch status line, queue summary, bounded queue
 * rows, retry while paused, and Stop All for the chapter.
 */
@Composable
internal fun ColumnScope.ActivitySection(
    queue: ImmutableList<ReaderViewModel.QueuedPageInfo>,
    translationProgress: Pair<Int, Int>,
    translationCurrentPage: Int,
    translationBatchProgress: TranslationProgressSnapshot?,
    onRetryBatch: (() -> Unit)?,
    onStopAllTranslation: () -> Unit,
) {
    val (done, total) = translationProgress
    val queued = queue.count { it.stage != ReaderViewModel.QueueStage.DONE }
    val summary = when {
        total == 0 && queue.isEmpty() ->
            stringResource(ATMR.strings.reader_translation_queue_idle)
        translationCurrentPage > 0 && total > 0 ->
            stringResource(ATMR.strings.reader_translation_queue_summary, translationCurrentPage, total, queued)
        total > 0 ->
            stringResource(ATMR.strings.reader_translation_queued_count, queued)
        else ->
            stringResource(ATMR.strings.reader_translation_queue_idle)
    }

    val snapshot = translationBatchProgress
    val isPaused = snapshot?.state == Translation.State.PAUSED || snapshot?.pauseReason != null
    val request = snapshot?.requestState
    val hasBatchProjection = snapshot != null &&
        (
            snapshot.batchPhase != eu.kanade.translation.model.TranslationBatchPhase.IDLE ||
                snapshot.state == Translation.State.QUEUE ||
                snapshot.state == Translation.State.TRANSLATING ||
                isPaused ||
                request != null
            )

    if (hasBatchProjection && snapshot != null) {
        val status = when {
            request?.phase == TranslationRequestPhase.STARTING ->
                stringResource(ATMR.strings.manga_batch_phase_accepted)
            request?.phase == TranslationRequestPhase.PREPARING ->
                stringResource(ATMR.strings.manga_batch_phase_preparing)
            request?.phase == TranslationRequestPhase.WAITING_FOR_DOWNLOAD ->
                stringResource(ATMR.strings.manga_batch_phase_waiting_for_download)
            request?.phase == TranslationRequestPhase.DOWNLOAD_FAILED ->
                stringResource(ATMR.strings.manga_batch_phase_download_failed)
            isPaused -> {
                val reason = snapshot.pauseReason?.takeIf { it.isNotBlank() }
                    ?: stringResource(ATMR.strings.manga_batch_status_paused)
                val retryAt = snapshot.nextEligibleRetryAtEpochMs?.let {
                    " · ${stringResource(ATMR.strings.manga_batch_resume)}"
                }.orEmpty()
                "$reason$retryAt"
            }
            snapshot.state == Translation.State.QUEUE ->
                stringResource(ATMR.strings.manga_batch_status_queued)
            snapshot.state == Translation.State.TRANSLATING ->
                stringResource(ATMR.strings.reader_translation_queue_summary, translationCurrentPage, snapshot.totalPages, queued)
            else ->
                stringResource(ATMR.strings.manga_batch_pages_ready_hint, snapshot.donePages, snapshot.totalPages)
        }
        Text(
            text = status,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isPaused) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small),
        )
        if (isPaused && onRetryBatch != null) {
            TextButton(
                onClick = onRetryBatch,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MaterialTheme.padding.medium),
            ) {
                Text(stringResource(ATMR.strings.manga_batch_resume))
            }
        }
    }

    Text(
        text = summary,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small),
    )

    if (queue.isNotEmpty()) {
        val visible = queue.take(8)
        val remainder = queue.size - visible.size
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MaterialTheme.padding.medium),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall / 2),
        ) {
            visible.forEach { info ->
                QueueRow(info)
            }
            if (remainder > 0) {
                Text(
                    text = "… +$remainder",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    TextPreferenceWidget(
        title = stringResource(ATMR.strings.reader_translation_stop_all),
        subtitle = stringResource(ATMR.strings.reader_translation_stop_all_summary),
        onPreferenceClick = onStopAllTranslation,
    )
}

@Composable
private fun QueueRow(info: ReaderViewModel.QueuedPageInfo) {
    val stageLabel: String
    val stageColor: Color
    when (info.stage) {
        ReaderViewModel.QueueStage.DONE -> {
            stageLabel = stringResource(ATMR.strings.reader_translation_stage_done)
            stageColor = MaterialTheme.colorScheme.primary
        }
        ReaderViewModel.QueueStage.FAILED -> {
            stageLabel = stringResource(ATMR.strings.reader_translation_stage_failed)
            stageColor = MaterialTheme.colorScheme.error
        }
        ReaderViewModel.QueueStage.QUEUED -> {
            stageLabel = stringResource(ATMR.strings.reader_translation_stage_queued)
            stageColor = MaterialTheme.colorScheme.onSurfaceVariant
        }
        else -> {
            val name = when (info.stage) {
                ReaderViewModel.QueueStage.OCR -> stringResource(ATMR.strings.reader_translation_stage_ocr)
                ReaderViewModel.QueueStage.INPAINT -> stringResource(ATMR.strings.reader_translation_stage_inpaint)
                ReaderViewModel.QueueStage.TRANSLATE -> stringResource(ATMR.strings.reader_translation_stage_translate)
                else -> ""
            }
            stageLabel = stringResource(ATMR.strings.reader_translation_stage_running, name)
            stageColor = MaterialTheme.colorScheme.primary
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(ATMR.strings.reader_translation_page, info.index),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = stageLabel,
            style = MaterialTheme.typography.labelMedium,
            color = stageColor,
        )
    }
}

/**
 * Destructive maintenance, separated from normal configuration. Every action
 * requires confirmation; the labels name exactly which artifacts are
 * invalidated. Semantics map 1:1 onto the existing view-model calls.
 */
@Composable
internal fun ColumnScope.DataManagementSection(
    onResetTranslationData: (Boolean) -> Unit,
    onResetInpaintData: () -> Unit,
    onResetOcrData: () -> Unit,
    onResetEverything: () -> Unit,
    onDeleteChapterTranslation: () -> Unit,
) {
    var pendingAction by remember { mutableStateOf<PendingResetAction?>(null) }

    Text(
        text = stringResource(ATMR.strings.translation_data_management_summary),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium),
    )

    TextPreferenceWidget(
        title = stringResource(ATMR.strings.reader_data_reset_keep_edits),
        onPreferenceClick = { pendingAction = PendingResetAction.KeepEdits },
    )
    TextPreferenceWidget(
        title = stringResource(ATMR.strings.reader_data_reset_clear_edits),
        onPreferenceClick = { pendingAction = PendingResetAction.ClearEdits },
    )
    TextPreferenceWidget(
        title = stringResource(ATMR.strings.reader_data_reset_inpaint),
        onPreferenceClick = { pendingAction = PendingResetAction.Inpaint },
    )
    TextPreferenceWidget(
        title = stringResource(ATMR.strings.reader_data_reset_ocr),
        onPreferenceClick = { pendingAction = PendingResetAction.Ocr },
    )
    TextPreferenceWidget(
        title = stringResource(ATMR.strings.reader_data_reset_everything),
        onPreferenceClick = { pendingAction = PendingResetAction.Everything },
    )
    TextPreferenceWidget(
        title = stringResource(ATMR.strings.reader_data_delete_chapter),
        subtitle = stringResource(ATMR.strings.reader_data_delete_chapter_summary),
        onPreferenceClick = { pendingAction = PendingResetAction.DeleteChapter },
    )

    pendingAction?.let { action ->
        AlertDialog(
            onDismissRequest = { pendingAction = null },
            title = { Text(stringResource(ATMR.strings.reader_data_reset_confirm_title)) },
            text = {
                Text(
                    text = stringResource(
                        ATMR.strings.reader_data_reset_confirm_message,
                        action.confirmLabel(),
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingAction = null
                        when (action) {
                            PendingResetAction.KeepEdits -> onResetTranslationData(true)
                            PendingResetAction.ClearEdits -> onResetTranslationData(false)
                            PendingResetAction.Inpaint -> onResetInpaintData()
                            PendingResetAction.Ocr -> onResetOcrData()
                            PendingResetAction.Everything -> onResetEverything()
                            PendingResetAction.DeleteChapter -> onDeleteChapterTranslation()
                        }
                    },
                ) {
                    Text(stringResource(ATMR.strings.action_reset))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingAction = null }) {
                    Text(stringResource(MR.strings.action_cancel))
                }
            },
        )
    }
}

private enum class PendingResetAction {
    KeepEdits,
    ClearEdits,
    Inpaint,
    Ocr,
    Everything,
    DeleteChapter,
}

@Composable
private fun PendingResetAction.confirmLabel(): String = when (this) {
    PendingResetAction.KeepEdits -> stringResource(ATMR.strings.reader_data_reset_keep_edits)
    PendingResetAction.ClearEdits -> stringResource(ATMR.strings.reader_data_reset_clear_edits)
    PendingResetAction.Inpaint -> stringResource(ATMR.strings.reader_data_reset_inpaint)
    PendingResetAction.Ocr -> stringResource(ATMR.strings.reader_data_reset_ocr)
    PendingResetAction.Everything -> stringResource(ATMR.strings.reader_data_reset_everything)
    PendingResetAction.DeleteChapter -> stringResource(ATMR.strings.reader_data_delete_chapter)
}
