package eu.kanade.translation.persistence.chapter

/**
 * Process-wide registry of active chapter writers in [ActiveChapterStoreRegistry].
 * With group commit disabled, registration is observability-only: it records writers
 * but excludes nothing, preserving existing probe/status-resolver choreography.
 *
 * With group commit enabled, a second writer
 * (probe store or status resolver) force-flushes the owning
 * store's staged buffer before its own publication and re-reads durable truth.
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
