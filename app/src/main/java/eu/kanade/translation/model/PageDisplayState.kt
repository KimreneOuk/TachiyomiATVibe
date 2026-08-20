package eu.kanade.translation.model

/**
 * TachiyomiAT: canonical reader/drawer display state derived from the committed
 * display bundle plus candidate/failure metadata. The drawer, chapter-list badge,
 * progress tracker, and reader must all consume this state instead of inferring
 * readiness from OCR or translation flags (batch plan §6).
 *
 * Phase 2 introduces the vocabulary and records the migration-time initial state
 * in the chapter artifact manifest; UI consumers switch to it in a later phase.
 */
enum class PageDisplayState {
    /** No committed bundle; the original page is displayed. */
    ORIGINAL_ONLY,

    /** Candidate work is active and no committed bundle exists yet. */
    CANDIDATE_RUNNING,

    /** A complete committed translated bundle is reader-visible. */
    DISPLAY_READY,

    /** A committed bundle stays visible while a candidate refresh runs. */
    REFRESHING_WITH_COMMITTED_RESULT,

    /** A committed bundle stays visible after a candidate failure. */
    FAILED_WITH_COMMITTED_RESULT,

    /** Candidate failed with no committed bundle to fall back to. */
    FAILED_NO_RESULT,

    /** OCR-confirmed textless page; terminally complete without translation. */
    TEXTLESS_COMPLETE,
}
