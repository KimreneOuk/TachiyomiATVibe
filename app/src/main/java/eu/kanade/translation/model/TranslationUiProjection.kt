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
}
