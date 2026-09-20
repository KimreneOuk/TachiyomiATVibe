package eu.kanade.translation.model

/** Pure projections shared by list, reader, and lifecycle guards. */
object TranslationUiProjection {

    /**
     * A live queue entry wins over persisted/disk state. A pending request is
     * intentionally kept separate from [Translation.State] because waiting
     * for a download is not active foreground work.
     */
    fun chapterState(
        queuedState: Translation.State?,
        persistedState: Translation.State?,
        requestState: TranslationRequestState?,
        downloaded: Boolean,
    ): Translation.State = when {
        queuedState != null -> queuedState
        requestState != null -> Translation.State.NOT_TRANSLATED
        downloaded -> persistedState ?: Translation.State.NOT_TRANSLATED
        else -> Translation.State.NOT_TRANSLATED
    }

    /** Reader auto-delete must not evict files needed by any batch intent. */
    fun protectsChapterFromDeletion(
        queuedState: Translation.State?,
        hasPendingRequest: Boolean,
    ): Boolean = hasPendingRequest ||
        queuedState == Translation.State.QUEUE ||
        queuedState == Translation.State.TRANSLATING ||
        queuedState == Translation.State.PAUSED

    /**
     *  stranded-state reconciliation. A batch cancelled mid-run removes its
     * queue entry; `statusFlow()` then drops the entry WITHOUT a terminal
     * emission, so the chapter's projected [Translation.State] stays at its
     * last in-flight value (QUEUE/TRANSLATING/PAUSED) forever — the indicator
     * routes every tap into the progress drawer and offers no restart.
     *
     * Returns the restartable replacement state when the observed snapshot is
     * a terminal-aborted batch AND the chapter holds no queue entry anymore
     * (the caller guards queue membership before calling); null when the
     * current state is already honest (terminal or idle) and must not be
     * touched. Pure: no store, no scheduler state.
     */
    fun reconcileAbortedBatchState(current: Translation.State): Translation.State? =
        when (current) {
            Translation.State.QUEUE,
            Translation.State.TRANSLATING,
            Translation.State.PAUSED,
            -> Translation.State.NOT_TRANSLATED
            else -> null
        }
}
