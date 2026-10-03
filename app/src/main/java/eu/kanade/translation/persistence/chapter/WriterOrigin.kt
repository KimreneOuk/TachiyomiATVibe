package eu.kanade.translation.persistence.chapter

/**
 * Process-wide registry of active chapter writers in [ActiveChapterStoreRegistry].
 * Registration is observability-only: it records writers but excludes
 * nothing, preserving probe/status-resolver choreography.
 */
enum class WriterOrigin(val description: String) {
    MAIN_STORE("main chapter translation store"),
    PROBE_STORE("probe store for durable inspection"),
    STATUS_RESOLVER("durable chapter status resolver"),
}

data class ActiveWriter(
    val chapterId: Long? = null,
    val chapterKey: String? = null,
    val origin: WriterOrigin,
    val tag: String? = null,
    val registeredAtEpochMs: Long = System.currentTimeMillis(),
)
