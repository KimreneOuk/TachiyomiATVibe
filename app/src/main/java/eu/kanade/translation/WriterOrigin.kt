package eu.kanade.translation

import eu.kanade.translation.storage.*

/**
 * T930 Slice A2: Writer registry (N2).
 *
 * Process-wide registry of active chapter writers in [ActiveChapterStoreRegistry].
 * Under Slice A (flag OFF), this is observability-only: registration records writers
 * but excludes nothing, preserving existing probe/verify LI-4 choreography.
 *
 * Under Slice B (flag ON), exclusion semantics apply: a second writer
 * (probe store, health-verify, migration, glossary lane) force-flushes the owning
 * store's staged buffer before its own publication and re-reads durable truth.
 */
enum class WriterOrigin(val description: String) {
    MAIN_STORE("main chapter translation store"),
    PROBE_STORE("probe store for durable inspection"),
    STATUS_RESOLVER("durable chapter status resolver"),
    MIGRATION_SOURCE("legacy chapter migration source"),
    HEALTH_VERIFY("legacy artifact health verification"),
    GLOSSARY_LANE("glossary update lane");
}

data class ActiveWriter(
    val chapterId: Long? = null,
    val chapterKey: String? = null,
    val origin: WriterOrigin,
    val tag: String? = null,
    val registeredAtEpochMs: Long = System.currentTimeMillis(),
)
