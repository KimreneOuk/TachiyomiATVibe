package eu.kanade.translation.store

import com.hippo.unifile.UniFile
import eu.kanade.translation.ChapterTranslationStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

// T909 Phase 17b: the persistence scheduler moved from
// `ChapterTranslationStore` (persistLocked + flush/close lifecycle + the
// debounced persist job + the retention sweep, plus the persistence
// constants). The scheduler owns `persistScope` and is constructed eagerly by
// the store (its ctor resolves no store state), so the debounce scope and its
// join/cancel semantics — including `markDefunct`'s bounded
// `PERSIST_JOIN_TIMEOUT_MS` join, which stays store-side and reads the
// constant through the scheduler companion — are unchanged. `dirty` and
// `persistJob` remain store fields (non-moved mutation bodies read and write
// them directly); the scheduler reaches them, the store mutex, the glossary
// delegate, and the vestigial legacy-ctor probes through the same-name
// accessors below.
internal class StorePersistenceScheduler(private val store: ChapterTranslationStore) {

    val persistScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    internal companion object {
        internal const val PERSIST_JOIN_TIMEOUT_MS = 2_000L
        private const val PERSIST_DEBOUNCE_MS = 250L
    }

    // Same-name dependency reads the moved bodies use; resolved through the
    // owning store at each call.
    private val mutex get() = store.mutex

    private val defunct get() = store.isDefunct

    private val artifactStore get() = store.artifactEngine

    private val artifactManifest get() = store.artifactManifest

    private val glossaryStore get() = store.glossaryStore

    private val translationFile: UniFile? get() = store.translationFile

    private val fileCreator: (() -> UniFile)? get() = store.fileCreator

    private val artifactParent: UniFile? get() = store.artifactParent

    private var dirty: Boolean
        get() = store.dirty
        set(value) {
            store.dirty = value
        }

    private var persistJob: Job?
        get() = store.persistJob
        set(value) {
            store.persistJob = value
        }

    private var persistCount: Int
        get() = store.persistCount
        set(value) {
            store.persistCount = value
        }

    private fun persistGlossaryLocked(): Boolean = store.persistGlossaryLocked()

    internal fun persistLocked(): Boolean {
        if (defunct) return false
        // All page durability is handled by the artifact bridge.
        persistCount++
        return artifactManifest != null
    }

    suspend fun flush() {
        mutex.withLock {
            flushDirtyLocked()
        }
    }

    private fun flushDirtyLocked() {
        store.flushStagedMutationsLocked(eu.kanade.translation.artifact.CommitPoint.EXPLICIT_FLUSH)
        if (dirty) {
            if (persistLocked()) dirty = false else dirty = true
        }
        if (glossaryStore.glossaryDirty) {
            // Keep dirty on failure so a later completion/close flush can retry.
            if (persistGlossaryLocked()) glossaryStore.glossaryDirty = false
        }
    }

    suspend fun closeAndFlush() {
        // T921 hotfix: no retention sweep here either. closeAndFlush runs on
        // the caller's coroutine — for probe stores that is the reader-entry
        // path itself (DurableChapterStatusResolver.withProbeStore's finally),
        // so the multi-second recursive SAF crawl stalled every first open of
        // a chapter (measured ~15s on a 68-page chapter, proportional to the
        // 57s crawl on a 260-page chapter). Same rationale as the artifact-load
        // removal: deferred cleanup is owned by the event-driven retention
        // follow-up. Explicit reconcileArtifactRetention() remains available
        // for callers that truly want it at a boundary.
        mutex.withLock {
            flushDirtyLocked()
        }
        persistScope.cancel()
    }

    fun close() {
        persistScope.launch {
            mutex.withLock {
                flushDirtyLocked()
            }
            reconcileArtifactRetention()
        }.invokeOnCompletion {
            persistScope.cancel()
        }
    }

    /**
     * Performs the bounded artifact-tree sweep at a serialized chapter
     * boundary. The CRAWL runs OFF the store mutex — it is minutes of SAF
     * round-trips on real storage and holding the mutex during it froze every
     * page lease in the pipeline (jdb thread dump, 2026-09-15: 20+ minute
     * batch stall). Only the short candidate re-verification/deletion takes
     * the mutex, against the live manifest.
     */
    suspend fun reconcileArtifactRetention() {
        val plan = mutex.withLock {
            val store = artifactStore ?: return
            val manifest = artifactManifest ?: return
            store to manifest
        }
        val startedAt = System.currentTimeMillis()
        val candidates = withContext(Dispatchers.IO) {
            plan.first.collectRetentionCandidates(plan.second)
        }
        val result = mutex.withLock {
            val store = artifactStore ?: return
            // Verify against the manifest the STORE resolves under its own
            // monitor, never a snapshot captured here: the facade cache is
            // written only AFTER a publication's durable manifest rotation,
            // so a capture taken outside the artifact monitor can lag an
            // in-flight publication by its whole body — deleting the sidecar
            // that publication just pointed at (the T924 COMPLETE run record
            // was observed vanishing exactly this way between publication and
            // the next read).
            store.deleteVerifiedRetentionCandidates(candidates)
        }
        logcat(LogPriority.INFO) {
            "TachiyomiAT retention sweep: candidates=${candidates.size} " +
                "deleted=${result.deletedCount} in ${System.currentTimeMillis() - startedAt}ms"
        }
    }

    private val retentionInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Fire-and-forget boundary sweep for batch close paths: the schedule must
     * not wait on a minutes-long crawl at its finally (the 2026-09-15 stall
     * made a cancelled resume's schedule_end take 21 minutes), the gate keeps
     * back-to-back boundaries from double-crawling, and re-verification under
     * the mutex keeps deletions safe while later runs publish new files.
     */
    fun reconcileArtifactRetentionAsync() {
        if (translationFile == null && fileCreator == null && artifactParent == null) return
        if (!retentionInFlight.compareAndSet(false, true)) return
        persistScope.launch {
            try {
                reconcileArtifactRetention()
            } finally {
                retentionInFlight.set(false)
            }
        }
    }

    internal fun schedulePersist(markPageDirty: Boolean = true) {
        if (markPageDirty) dirty = true
        // A memory-only store has no future persistence target. Avoid leaving a
        // delayed job behind for eviction to join; explicit flush() still
        // remains available for deterministic callers and preserves dirty state.
        if (translationFile == null && fileCreator == null && artifactParent == null) return
        if (persistJob?.isActive == true) return
        persistJob = persistScope.launch {
            delay(PERSIST_DEBOUNCE_MS)
            mutex.withLock { flushDirtyLocked() }
            persistJob = null
        }
    }
}
