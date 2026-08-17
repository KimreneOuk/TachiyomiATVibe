package eu.kanade.presentation.manga.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.translation.model.RevisionReport
import eu.kanade.translation.model.RevisionResultState
import tachiyomi.i18n.MR
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

/**
 * CP7 shared terminal report sheet for standalone revision. Renders the K/C/U
 * totals from a [RevisionReport] and a bounded list of accepted changes (before
 * -> after). The manga and reader surfaces share this single composable so a
 * run started from either is displayed identically.
 *
 * The sheet owns no data fetching: the caller passes a [RevisionResultState]
 * (Loading / Missing / Loaded) and a dismiss callback. Numbers and the change
 * list arrive backend-computed; only the display cap on changes is applied
 * (see [RevisionResultState.Loaded.displayedChanges]).
 */
@Composable
fun RevisionResultSheet(
    chapterName: String,
    state: RevisionResultState,
    onDismissRequest: () -> Unit,
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
                text = stringResource(ATMR.strings.revision_result_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = chapterName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            when (state) {
                RevisionResultState.Loading -> Text(
                    text = stringResource(MR.strings.loading),
                    style = MaterialTheme.typography.bodyMedium,
                )
                RevisionResultState.Missing -> Text(
                    text = stringResource(ATMR.strings.revision_no_report),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                is RevisionResultState.Loaded -> LoadedBody(state)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismissRequest) {
                    Text(text = stringResource(MR.strings.action_close))
                }
            }
        }
    }
}

@Composable
private fun LoadedBody(state: RevisionResultState.Loaded) {
    val report = state.report
    HorizontalDivider()
    Text(
        text = stringResource(ATMR.strings.revision_result_kept, report.keptCount),
        style = MaterialTheme.typography.bodyMedium,
    )
    Text(
        text = stringResource(ATMR.strings.revision_result_corrected, report.correctedCount),
        style = MaterialTheme.typography.bodyMedium,
    )
    Text(
        text = stringResource(ATMR.strings.revision_result_unresolved, report.unresolvedCount),
        style = MaterialTheme.typography.bodyMedium,
    )

    if (report.changes.isEmpty()) {
        Text(
            text = stringResource(ATMR.strings.revision_result_no_changes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    HorizontalDivider()
    Text(
        text = stringResource(ATMR.strings.revision_result_changes),
        style = MaterialTheme.typography.titleSmall,
    )
    report.changes.take(state.displayedChanges).forEach { change ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = MaterialTheme.padding.extraSmall),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
        ) {
            Text(
                text = change.pageKey,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = change.beforeDraft,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Normal,
            )
            Text(
                text = change.afterDraft,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
            )
        }
    }
    if (state.moreChanges > 0) {
        Text(
            text = stringResource(ATMR.strings.revision_result_more_changes, state.moreChanges),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
