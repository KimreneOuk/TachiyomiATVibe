package eu.kanade.translation.store

import com.hippo.unifile.UniFile
import eu.kanade.translation.ChapterTranslationStore
import eu.kanade.translation.artifact.ManifestAuthority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

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

    private val artifactStore get() = store.artifactStore

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
        // Once the artifact manifest owns this chapter, the flat JSON is a
        // legacy compatibility snapshot only. Writing the mutable candidate
        // map back into it would erase the durable committed pointer on the
        // next reopen, so all page durability is handled by the artifact
        // bridge in publishLocked().
        persistCount++
        // All durable page writes go through the artifact bridge. A legacy
        // flat file is a migration-time read/recovery source only.
        return artifactManifest?.authority == ManifestAuthority.ARTIFACTS
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
        // 57s crawl on a 260-page chapter). Same rationale as the loadOrMigrate
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
                reconcileArtifactRetentionLocked()
            }
        }.invokeOnCompletion {
            persistScope.cancel()
        }
    }

    /** Performs the bounded artifact-tree sweep at a serialized chapter boundary. */
    suspend fun reconcileArtifactRetention() {
        mutex.withLock { reconcileArtifactRetentionLocked() }
    }

    private fun reconcileArtifactRetentionLocked() {
        val store = artifactStore ?: return
        val manifest = artifactManifest ?: return
        store.reconcileRetention(manifest)
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
