package eu.kanade.presentation.reader.sheet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
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
 * Data wipe section of the reader translation sheet: per-page reset rows —
 * translation output with manual edits preserved, and full page
 * invalidation — each gated behind a confirm dialog.
 */
@Composable
internal fun TranslationResetSection(
    onResetTranslationData: () -> Unit,
    onResetOcrData: () -> Unit,
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
            onClick = { pendingReset = ResetTarget.TRANSLATION },
        )
        TranslationActionRow(
            icon = Icons.Outlined.DeleteOutline,
            title = stringResource(ATMR.strings.reader_data_reset_page),
            subtitle = stringResource(ATMR.strings.reader_translation_reset_summary),
            onClick = { pendingReset = ResetTarget.PAGE },
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
                            ResetTarget.TRANSLATION -> onResetTranslationData()
                            ResetTarget.PAGE -> onResetOcrData()
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
    TRANSLATION,
    PAGE,
}

@Composable
private fun ResetTarget.label(): String = stringResource(
    when (this) {
        ResetTarget.TRANSLATION -> ATMR.strings.reader_data_reset_keep_edits
        ResetTarget.PAGE -> ATMR.strings.reader_data_reset_page
    },
)
