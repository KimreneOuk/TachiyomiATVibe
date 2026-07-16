package eu.kanade.translation.model

import androidx.compose.runtime.Immutable

/**
 * CP7 pure UI state derived from [RevisionPreflightOutcome] and
 * [ChapterRevisionEligibility]. Lives in the pure `model` package so the manga
 * and reader surfaces share one reducer, and so the logic is unit-testable
 * without Android or Compose.
 *
 * The reducer never reads credentials, blocks, bitmaps, streams, store objects,
 * or provider clients. Every number arrives backend-computed via
 * [RevisionConfirmation] / [ChapterRevisionEligibility]; this layer only maps
 * those values to display state and decides whether the confirm affordance is
 * enabled.
 */

/**
 * One-line summary of a rejection, tagged with the typed reason so the UI can
 * render the localized message and decide whether to offer a setup/settings
 * navigation action (for [RevisionRejectionReason.NO_REVIEWER_CONFIGURED]).
 */
@Immutable
data class RevisionRejectionState(
    val reason: RevisionRejectionReason,
    val messageArgs: List<String> = emptyList(),
) {
    /** True when reviewer setup in translation settings can recover the rejection. */
    val offersSettingsNav: Boolean get() = reason == RevisionRejectionReason.NO_REVIEWER_CONFIGURED
}

/**
 * Resolved reviewer selection. The engine is what the view-model dispatches to
 * the manager; the model is read from the persisted per-engine model preference
 * so the manager-side preflight sees the same identity the user picked.
 */
@Immutable
data class RevisionReviewerSelection(
    val engine: tachiyomi.domain.translation.AiEngine,
    val model: String,
    val displayLabel: String,
)

/**
 * State surfaced by the shared confirm composable. A preflight either yields a
 * confirmable [Ready] state or a [Rejected] reason. [Idle] is the initial state
 * before the first preflight.
 */
sealed interface RevisionConfirmState {

    @Immutable
    data object Idle : RevisionConfirmState

    @Immutable
    data class Ready(
        val confirmation: RevisionConfirmation,
        val reviewerOptions: List<RevisionReviewerOption>,
        val selection: RevisionReviewerSelection,
        /** Mirrors backend admission; a Ready confirmation is always actionable. */
        val canConfirm: Boolean,
    ) : RevisionConfirmState

    @Immutable
    data class Rejected(val rejection: RevisionRejectionState) : RevisionConfirmState
}

/**
 * Terminal report state for the result sheet. [Loading] while the durable report
 * is being read, [Missing] when no run completed yet, [Loaded] otherwise.
 */
sealed interface RevisionResultState {

    @Immutable
    data object Loading : RevisionResultState

    @Immutable
    data object Missing : RevisionResultState

    @Immutable
    data class Loaded(val report: RevisionReport, val displayedChanges: Int) : RevisionResultState {
        val moreChanges: Int get() = (report.changes.size - displayedChanges).coerceAtLeast(0)
    }
}

/**
 * Caps the number of accepted-change rows the result sheet renders so a large
 * run cannot make the surface unusable. Matches the bounded list guidance in
 * CP7.
 */
const val REVISION_RESULT_MAX_CHANGES: Int = 20

/**
 * Default scope a surface should pre-select: FLAGGED when the manager reports
 * flagged targets, otherwise ALL_TRANSLATED. The manager still re-validates
 * the chosen scope at preflight, so this only picks the initial radio button.
 */
fun defaultScope(eligibility: ChapterRevisionEligibility?): RevisionScope {
    if (eligibility == null) return RevisionScope.ALL_TRANSLATED
    return if (eligibility.flaggedTargets > 0) RevisionScope.FLAGGED else RevisionScope.ALL_TRANSLATED
}

/**
 * Resolves the initial reviewer selection for a chapter from the configured
 * options. Prefers the persisted reviewer-engine preference when it has a
 * matching configured option; otherwise falls back to the first option. Returns
 * null when no reviewer is configured (the surface shows the setup-required
 * state in that case).
 */
fun resolveInitialSelection(
    options: List<RevisionReviewerOption>,
    persistedEngine: tachiyomi.domain.translation.AiEngine,
): RevisionReviewerSelection? {
    if (options.isEmpty()) return null
    val persisted = options.firstOrNull { it.engine == persistedEngine }
    val chosen = persisted ?: options.first()
    return RevisionReviewerSelection(
        engine = chosen.engine,
        model = chosen.model,
        displayLabel = chosen.displayLabel,
    )
}

/**
 * Pure reducer: maps a typed preflight outcome to the confirm state the shared
 * composable renders. The caller passes the configured reviewer options and the
 * persisted reviewer engine so the Ready branch can carry the selection forward
 * without recomputing it from the (display-only) confirmation.
 */
fun RevisionPreflightOutcome.toConfirmState(
    reviewerOptions: List<RevisionReviewerOption>,
    persistedEngine: tachiyomi.domain.translation.AiEngine,
): RevisionConfirmState = when (this) {
    is RevisionPreflightOutcome.Ready -> {
        val selection = resolveInitialSelection(reviewerOptions, persistedEngine)
            ?: RevisionReviewerSelection(
                engine = confirmation.reviewerEngine,
                model = confirmation.reviewerModel,
                displayLabel = confirmation.reviewerLabel,
            )
        RevisionConfirmState.Ready(
            confirmation = confirmation,
            reviewerOptions = reviewerOptions,
            selection = selection,
            canConfirm = true,
        )
    }
    is RevisionPreflightOutcome.Rejected -> RevisionConfirmState.Rejected(
        RevisionRejectionState(reason = reason, messageArgs = messageArgs),
    )
}

/**
 * Pure reducer: applies a user-picked reviewer option to a Ready state. Returns
 * the unchanged state for non-Ready inputs (the picker is only rendered for
 * Ready). The view-model persists the engine preference; this only updates the
 * in-memory selection the composable shows.
 */
fun RevisionConfirmState.withReviewerPicked(
    option: RevisionReviewerOption,
): RevisionConfirmState {
    if (this !is RevisionConfirmState.Ready) return this
    return copy(
        selection = RevisionReviewerSelection(
            engine = option.engine,
            model = option.model,
            displayLabel = option.displayLabel,
        ),
    )
}

/**
 * Pure reducer: applies a scope change to a Ready state's confirmation. The
 * scope radio drives the next preflight; numbers on the displayed confirmation
 * are not recomputed here (they belong to the last successful preflight for the
 * prior scope) so the surface avoids showing stale counts by re-running
 * preflight when the scope toggles.
 */
fun RevisionConfirmState.withScopeChanged(scope: RevisionScope): RevisionConfirmState {
    if (this !is RevisionConfirmState.Ready) return this
    return copy(confirmation = confirmation.copy(scope = scope))
}

/**
 * Pure reducer: builds the terminal result state from a durable report. Bounds
 * the displayed changes to [REVISION_RESULT_MAX_CHANGES].
 */
fun RevisionReport.toResultState(): RevisionResultState.Loaded =
    RevisionResultState.Loaded(
        report = this,
        displayedChanges = changes.size.coerceAtMost(REVISION_RESULT_MAX_CHANGES),
    )

/**
 * The UI guard for a duplicate start. The manager already serializes starts via
 * its active-job map; this mirrors that admission at the UI layer so a fast
 * double-tap on confirm cannot enqueue a second preflight/start cycle. True
 * means the surface should ignore the start request.
 */
fun isStartAlreadyActive(revisionActive: Boolean): Boolean = revisionActive

/**
 * The UI guard for closing the surface. The manager owns the job; closing the
 * manga/reader surface never cancels it. This function exists to document and
 * test that invariant: it always returns false, so a "close = cancel" wiring
 * can never be introduced accidentally.
 */
fun shouldCancelOnSurfaceClosed(): Boolean = false
