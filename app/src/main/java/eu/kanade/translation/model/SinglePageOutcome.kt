package eu.kanade.translation.model

import eu.kanade.translation.persistence.PageWriteOrigin

/**
 * A typed outcome of one single-page intent. It replaces
 * the previous silent `Unit` return so a denied lease can never again look like
 * a completed intent: the scheduler records the outcome and its
 * cancel path can tell "owned the page" from "only observed the owner".
 */
sealed interface SinglePageOutcome {
    /** The page intent was admitted and is queued in memory awaiting pipeline execution. */
    data object Admitted : SinglePageOutcome

    /** The executor ran the page itself (including resume-skip soft exits). */
    data object Completed : SinglePageOutcome

    /**
     * The paid call was deferred by the provider
     * request governor (window/foreground budget) instead of completing, so the
     * intent neither failed nor finished. [nextEligibleRetryAtEpochMs] is the
     * epoch ms after which a retry may be admitted, when the governor knows it.
     */
    data class Paused(val nextEligibleRetryAtEpochMs: Long? = null) : SinglePageOutcome

    /** The native lane remained occupied beyond its result timer. */
    data class Stalled(val pageKey: String, val stalledSinceEpochMs: Long) : SinglePageOutcome

    /** The page attempt reached a typed terminal failure. */
    data class Failed(val pageKey: String, val reason: String) : SinglePageOutcome

    /** The page was owned by [owner]; the executor attached to the owner's terminal commit. */
    data class Attached(val owner: PageWriteOrigin) : SinglePageOutcome

    /**
     * The intent was refused without a durable result for the current
     * attempt; an earlier committed display record can remain. [kind] is the
     * mandatory typed category each producer selects from its own control
     * flow — it is never defaulted and never inferred from diagnostic text.
     * [owner] mirrors [Attached.owner] when the refusal happens because a
     * known owner holds the page. [diagnosticDetail] is internal context for
     * logs and traces only; it never reaches user-facing copy and must not
     * influence labels, accessibility text, severity or retry affordances.
     */
    data class Rejected(
        val owner: PageWriteOrigin?,
        val kind: PageRejectionKind,
        val diagnosticDetail: String? = null,
    ) : SinglePageOutcome
}

/**
 * The closed set of reasons a single-page intent can be
 * [rejected][SinglePageOutcome.Rejected]: the smallest enum over the actual
 * producers — reader-session admission, known ownership, guarded persistence
 * publication, and the owner-less catch-all. Extend only with a real typed
 * producer, never a speculative state.
 */
enum class PageRejectionKind {
    /** The reader-session admission gate refused the request. */
    SESSION_DENIED,

    /** The page is already being translated: in-flight membership or a known lease owner. */
    ALREADY_OWNED,

    /** A guarded post-attempt publication was rejected; the current attempt produced no durable result, while an earlier committed display can remain. */
    PERSISTENCE_FAILED,

    /** A refusal whose producer cannot attribute a session cause or a known owner. */
    OTHER,
}
