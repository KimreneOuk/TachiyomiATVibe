package eu.kanade.presentation.reader.sheet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.designsystem.SectionHeading
import tachiyomi.i18n.MR
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Data wipe section of the reader translation sheet: granular reset rows
 * (translation output, inpainting, OCR, everything), each gated behind a
 * confirm dialog.
 */
@Composable
internal fun TranslationResetSection(
    onResetTranslationData: (Boolean) -> Unit,
    onResetInpaintData: () -> Unit,
    onResetOcrData: () -> Unit,
    onResetEverything: () -> Unit,
) {
    var pendingReset by remember { mutableStateOf<ResetTarget?>(null) }

    SectionHeading(title = stringResource(ATMR.strings.reader_translation_section_reset))

    Column(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TranslationActionRow(
            icon = Icons.Outlined.EditNote,
            title = stringResource(ATMR.strings.reader_data_reset_keep_edits),
            subtitle = stringResource(ATMR.strings.reader_translation_reset_summary),
            onClick = { pendingReset = ResetTarget.TRANSLATION_KEEP_EDITS },
        )
        TranslationActionRow(
            icon = Icons.Outlined.DeleteSweep,
            title = stringResource(ATMR.strings.reader_data_reset_clear_edits),
            subtitle = stringResource(ATMR.strings.reader_translation_reset_summary),
            onClick = { pendingReset = ResetTarget.TRANSLATION_CLEAR_EDITS },
        )
        TranslationActionRow(
            icon = Icons.Outlined.Brush,
            title = stringResource(ATMR.strings.reader_data_reset_inpaint),
            subtitle = stringResource(ATMR.strings.reader_translation_reset_summary),
            onClick = { pendingReset = ResetTarget.INPAINT },
        )
        TranslationActionRow(
            icon = Icons.Outlined.DocumentScanner,
            title = stringResource(ATMR.strings.reader_data_reset_ocr),
            subtitle = stringResource(ATMR.strings.reader_translation_reset_summary),
            onClick = { pendingReset = ResetTarget.OCR },
        )
        TranslationActionRow(
            icon = Icons.Outlined.DeleteOutline,
            title = stringResource(ATMR.strings.reader_data_reset_everything),
            subtitle = stringResource(ATMR.strings.reader_translation_reset_summary),
            onClick = { pendingReset = ResetTarget.EVERYTHING },
            danger = true,
        )
    }

    pendingReset?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingReset = null },
            title = { Text(stringResource(ATMR.strings.reader_data_reset_confirm_title)) },
            text = {
                Text(stringResource(ATMR.strings.reader_data_reset_confirm_message, target.label()))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingReset = null
                        when (target) {
                            ResetTarget.TRANSLATION_KEEP_EDITS -> onResetTranslationData(true)
                            ResetTarget.TRANSLATION_CLEAR_EDITS -> onResetTranslationData(false)
                            ResetTarget.INPAINT -> onResetInpaintData()
                            ResetTarget.OCR -> onResetOcrData()
                            ResetTarget.EVERYTHING -> onResetEverything()
                        }
                    },
                ) { Text(stringResource(ATMR.strings.action_reset)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingReset = null }) {
                    Text(stringResource(MR.strings.action_cancel))
                }
            },
        )
    }
}

private enum class ResetTarget {
    TRANSLATION_KEEP_EDITS,
    TRANSLATION_CLEAR_EDITS,
    INPAINT,
    OCR,
    EVERYTHING,
}

@Composable
private fun ResetTarget.label(): String = stringResource(
    when (this) {
        ResetTarget.TRANSLATION_KEEP_EDITS -> ATMR.strings.reader_data_reset_keep_edits
        ResetTarget.TRANSLATION_CLEAR_EDITS -> ATMR.strings.reader_data_reset_clear_edits
        ResetTarget.INPAINT -> ATMR.strings.reader_data_reset_inpaint
        ResetTarget.OCR -> ATMR.strings.reader_data_reset_ocr
        ResetTarget.EVERYTHING -> ATMR.strings.reader_data_reset_everything
    },
)
