package eu.kanade.translation.model

/**
 * derive per-chapter batch translation progress (done/total) from a
 * store's page map, for the manga-screen chapter-list indicator.
 *
 * - total = number of page entries the batch has registered (the staged batch
 *   writes a PENDING/RUNNING entry per page as it reaches it, so this grows to
 *   the full chapter page count as the batch progresses).
 * - done = pages that have reached a terminal state: a rendered/displayable
 *   result, a textless page (genuinely nothing to translate), or a page whose
 *   stages have failed (counted as "done" so the indicator converges and the
 *   user isn't stuck below 100% on a page that won't succeed).
 *
 * Pure and side-effect-free so it is unit-testable without a device or store.
 */
object TranslationProgress {

    /**
     * @return (done, total). (0, 0) for an empty/null page map means "not
     * started" — callers render no progress fraction in that case.
     */
    fun compute(pages: Map<String, PageTranslation>?): Pair<Int, Int> {
        if (pages.isNullOrEmpty()) return 0 to 0
        val total = pages.size
        val done = pages.values.count { it.isTerminalForProgress() }
        return done to total
    }

    /**
     * A page counts toward "done" for the progress indicator when it has reached
     * a terminal state. Mirrors the reader's counting at
     * ReaderViewModel.kt:2011-2012 (rendered/textless + permanently-failed).
     *
     * uses [hasExhaustedRetries] (keys on [PageTranslation.attemptCount],
     * i.e. DISTINCT failed attempts) rather than [PageTranslation.retryCount], so a
     * single transient inpaint failure that double-counted retryCount within one
     * attempt no longer falsely marks the page as terminal-for-progress. This
     * keeps the progress indicator consistent with the scheduler's skip gate.
     */
    private fun PageTranslation.isTerminalForProgress(): Boolean {
        if (hasRenderedResult) return true
        if (isTextlessTerminal) return true
        // Failed+exhausted is terminal: won't improve without user action.
        return hasExhaustedRetries
    }
}
