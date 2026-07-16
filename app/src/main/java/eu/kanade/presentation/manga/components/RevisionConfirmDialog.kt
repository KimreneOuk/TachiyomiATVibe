package eu.kanade.presentation.manga.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import eu.kanade.translation.model.RevisionConfirmState
import eu.kanade.translation.model.RevisionRejectionReason
import eu.kanade.translation.model.RevisionScope
import eu.kanade.translation.model.RevisionReviewerOption
import tachiyomi.i18n.MR
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

/**
 * CP7 shared confirmation surface for standalone revision. Both the manga
 * chapter list and the reader translation settings sheet dispatch the same
 * manager request (scope + reviewer + language) and render the same model here.
 *
 * The dialog owns no state of its own: the caller holds the
 * [RevisionConfirmState] and passes back user edits via [onScopeChange],
 * [onReviewerPicked], [onOpenSettings]. Confirm is disabled until the state is
 * [RevisionConfirmState.Ready] with `canConfirm == true`; a
 * [RevisionConfirmState.Rejected] branch shows the typed rejection message and,
 * when the reason is recoverable, a "Configure a reviewer"/language-settings
 * action.
 *
 * Numbers (coverage, target/exclusion counts, request-group estimate) come
 * straight from the backend-built [eu.kanade.translation.model.RevisionConfirmation];
 * this composable never recomputes them.
 */
@Composable
fun RevisionConfirmDialog(
    state: RevisionConfirmState,
    onScopeChange: (RevisionScope) -> Unit,
    onReviewerPicked: (RevisionReviewerOption) -> Unit,
    onOpenSettings: () -> Unit,
    onConfirm: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
        confirmButton = {
            val ready = state as? RevisionConfirmState.Ready
            TextButton(
                enabled = ready?.canConfirm == true,
                onClick = {
                    onDismissRequest()
                    onConfirm()
                },
            ) {
                Text(text = stringResource(ATMR.strings.manga_translate_review))
            }
        },
        title = {
            Text(text = stringResource(ATMR.strings.revision_confirm_title))
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
            ) {
                when (state) {
                    is RevisionConfirmState.Ready -> ReadyBody(
                        state = state,
                        onScopeChange = onScopeChange,
                        onReviewerPicked = onReviewerPicked,
                    )
                    is RevisionConfirmState.Rejected -> RejectedBody(
                        reason = state.rejection.reason,
                        offersSettingsNav = state.rejection.offersSettingsNav,
                        onOpenSettings = onOpenSettings,
                    )
                    RevisionConfirmState.Idle -> Text(
                        text = stringResource(MR.strings.loading),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
    )
}

@Composable
private fun ColumnScope.ReadyBody(
    state: RevisionConfirmState.Ready,
    onScopeChange: (RevisionScope) -> Unit,
    onReviewerPicked: (RevisionReviewerOption) -> Unit,
) {
    val confirmation = state.confirmation
    Text(
        text = confirmation.chapterName,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(MaterialTheme.padding.extraSmall))

    ScopeRow(
        selectedScope = confirmation.scope,
        onScopeChange = onScopeChange,
    )

    ReviewerSection(
        options = state.reviewerOptions,
        selectedEngine = state.selection.engine,
        onReviewerPicked = onReviewerPicked,
    )

    if (confirmation.partialWarning) {
        WarningText(text = stringResource(ATMR.strings.revision_partial_warning))
    }
    InfoRow(
        label = stringResource(ATMR.strings.revision_coverage),
        value = stringResource(
            ATMR.strings.revision_coverage,
            confirmation.translatedPages,
            confirmation.expectedPages?.toString() ?: "?",
        ),
    )
    InfoRow(
        label = stringResource(ATMR.strings.revision_targets),
        value = stringResource(ATMR.strings.revision_targets, confirmation.targetCount),
    )
    InfoRow(
        label = stringResource(ATMR.strings.revision_exclusions),
        value = stringResource(ATMR.strings.revision_exclusions, confirmation.exclusionCount),
    )
    InfoRow(
        label = stringResource(ATMR.strings.revision_request_estimate),
        value = stringResource(
            ATMR.strings.revision_request_estimate,
            confirmation.estimatedRequestGroups,
        ),
    )
}

@Composable
private fun ColumnScope.ScopeRow(
    selectedScope: RevisionScope,
    onScopeChange: (RevisionScope) -> Unit,
) {
    Text(
        text = stringResource(ATMR.strings.revision_scope_help),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    ScopeRadioRow(
        label = stringResource(ATMR.strings.revision_scope_flagged),
        selected = selectedScope == RevisionScope.FLAGGED,
        onSelect = { onScopeChange(RevisionScope.FLAGGED) },
    )
    ScopeRadioRow(
        label = stringResource(ATMR.strings.revision_scope_all_translated),
        selected = selectedScope == RevisionScope.ALL_TRANSLATED,
        onSelect = { onScopeChange(RevisionScope.ALL_TRANSLATED) },
    )
}

@Composable
private fun ColumnScope.ReviewerSection(
    options: List<RevisionReviewerOption>,
    selectedEngine: tachiyomi.domain.translation.AiEngine,
    onReviewerPicked: (RevisionReviewerOption) -> Unit,
) {
    Text(
        text = stringResource(ATMR.strings.revision_reviewer),
        style = MaterialTheme.typography.titleSmall,
    )
    if (options.isEmpty()) {
        Text(
            text = stringResource(ATMR.strings.revision_reviewer_none_configured),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        options.forEach { option ->
            ScopeRadioRow(
                label = option.displayLabel,
                selected = option.engine == selectedEngine,
                onSelect = { onReviewerPicked(option) },
            )
        }
    }
}

@Composable
private fun ColumnScope.RejectedBody(
    reason: RevisionRejectionReason,
    offersSettingsNav: Boolean,
    onOpenSettings: () -> Unit,
) {
    val messageRes = when (reason) {
        RevisionRejectionReason.NO_TARGETS -> ATMR.strings.revision_rejection_no_targets
        RevisionRejectionReason.NO_REVIEWER_CONFIGURED -> ATMR.strings.revision_rejection_no_reviewer_configured
        RevisionRejectionReason.ACTIVE_BATCH -> ATMR.strings.revision_rejection_active_batch
        RevisionRejectionReason.REVISION_ACTIVE -> ATMR.strings.revision_rejection_revision_active
        RevisionRejectionReason.CHAPTER_DELETED -> ATMR.strings.revision_rejection_chapter_deleted
    }
    Text(
        text = stringResource(messageRes),
        style = MaterialTheme.typography.bodyMedium,
        color = if (offersSettingsNav) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.error
        },
    )
    if (offersSettingsNav) {
        TextButton(onClick = onOpenSettings) {
            Text(text = stringResource(ATMR.strings.revision_reviewer_open_settings))
        }
    }
}

@Composable
private fun ColumnScope.ScopeRadioRow(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect)
            .padding(vertical = MaterialTheme.padding.extraSmall),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun ColumnScope.InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = MaterialTheme.padding.medium),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun ColumnScope.WarningText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.tertiary,
    )
}
