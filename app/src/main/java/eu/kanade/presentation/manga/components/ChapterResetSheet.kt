package eu.kanade.presentation.manga.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import eu.kanade.translation.orchestration.ChapterResetPreflight
import tachiyomi.i18n.MR
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun ChapterResetSheet(
    preflight: ChapterResetPreflight,
    onResetTranslation: (preserveEdits: Boolean) -> Unit,
    onResetInpaint: () -> Unit,
    onResetOcr: () -> Unit,
    onDeleteEverything: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    var preserveEdits by remember(preflight.manualEditBlocks) {
        mutableStateOf(preflight.manualEditBlocks > 0)
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(ATMR.strings.translation_reset_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small)) {
                Text(
                    stringResource(
                        ATMR.strings.translation_reset_summary,
                        preflight.ocrPages,
                        preflight.inpaintPages,
                        preflight.translatedBlocks,
                    ),
                )
                if (preflight.manualEditBlocks > 0) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = preserveEdits, onCheckedChange = { preserveEdits = it })
                        Text(stringResource(ATMR.strings.translation_reset_keep_edits, preflight.manualEditBlocks))
                    }
                }
                ResetAction(
                    title = stringResource(ATMR.strings.translation_reset_translation),
                    summary = stringResource(ATMR.strings.translation_reset_translation_summary),
                    onClick = { onResetTranslation(preserveEdits) },
                )
                ResetAction(
                    title = stringResource(ATMR.strings.translation_reset_inpaint),
                    summary = stringResource(ATMR.strings.translation_reset_inpaint_summary),
                    onClick = onResetInpaint,
                )
                ResetAction(
                    title = stringResource(ATMR.strings.translation_reset_ocr),
                    summary = stringResource(ATMR.strings.translation_reset_ocr_summary),
                    onClick = onResetOcr,
                )
                TextButton(onClick = onDeleteEverything) {
                    Text(stringResource(ATMR.strings.translation_reset_everything))
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(MR.strings.action_cancel))
            }
        },
    )
}

@Composable
private fun ResetAction(title: String, summary: String, onClick: () -> Unit) {
    TextButton(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = MaterialTheme.padding.extraSmall)) {
            Text(title)
            Text(summary, style = MaterialTheme.typography.bodySmall)
        }
    }
}
