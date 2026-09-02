package eu.kanade.translation

import com.hippo.unifile.UniFile
import eu.kanade.translation.artifact.ArtifactManifestProbe
import eu.kanade.translation.artifact.ArtifactOrigin
import eu.kanade.translation.artifact.ArtifactStage
import eu.kanade.translation.artifact.ArtifactStageStatus
import eu.kanade.translation.artifact.AttemptOrigin
import eu.kanade.translation.artifact.FailureCategory
import eu.kanade.translation.artifact.AtomicChapterDocuments
import eu.kanade.translation.artifact.BitmapFactoryCleanedImageProbe
import eu.kanade.translation.artifact.ChapterArtifactLayout
import eu.kanade.translation.artifact.ChapterArtifactManifest
import eu.kanade.translation.artifact.ChapterArtifactManifestReader
import eu.kanade.translation.artifact.ChapterArtifactStore
import eu.kanade.translation.artifact.CleanedImageProbe
import eu.kanade.translation.artifact.DurableFailureMetadata
import eu.kanade.translation.artifact.LegacyChapterMigrationSource
import eu.kanade.translation.artifact.LegacyChapterSnapshot
import eu.kanade.translation.artifact.ManifestAuthority
import eu.kanade.translation.artifact.SourceIdentity
import eu.kanade.translation.artifact.StageFingerprints
import eu.kanade.translation.artifact.UniFileChapterDocumentIo
import eu.kanade.translation.model.PageStage
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.model.blockFingerprints
import eu.kanade.translation.model.detachedCopy
import eu.kanade.translation.model.hasRenderedResult
import eu.kanade.translation.model.isStageFailed
import eu.kanade.translation.model.isTextlessTerminal
import eu.kanade.translation.model.stableFingerprint
import eu.kanade.translation.store.ChapterAttemptLedger
import eu.kanade.translation.store.ChapterGlossaryStore
import eu.kanade.translation.store.PageStageLeaseTable
import eu.kanade.translation.store.StorePersistenceScheduler
import eu.kanade.translation.store.StoreStatusInputs
import eu.kanade.translation.store.StoreStatusProjector
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Durable admission result for a page mutation. */
sealed interface MutationAdmission {
    data object Granted : MutationAdmission

    data class Rejected(
        val code: Code,
        val retryable: Boolean = true,
        val message: String,
    ) : MutationAdmission {
        enum class Code {
            LEGACY_RESCUE_REQUIRED,
            LEGACY_RESCUE_FAILED,
            ARTIFACT_PUBLICATION_FAILED,
            STORE_DEFUNCT,
            FENCE_REJECTED,
        }
    }
}

class ChapterTranslationStore(
    // Retained as a source-compatible test seam; artifact-only persistence never
    // invokes this legacy flat-file creator.
    // T909 Phase 17b: internal — the moved StorePersistenceScheduler reads the
    // memory-only persistence probes (schedulePersist's early return) from them.
    internal var translationFile: UniFile?,
    internal val fileCreator: (() -> UniFile)?,
    initialPages: Map<String, PageTranslation> = emptyMap(),
    internal var artifactStore: ChapterArtifactStore? = null,
    initialCommittedPages: Map<String, PageTranslation> = emptyMap(),
    initialArtifactManifest: ChapterArtifactManifest? = null,
    initialRetiredCleanedImages: Map<String, Set<String>> = emptyMap(),
    internal val artifactParent: UniFile? = null,
    private val artifactFileName: String? = null,
) {
    internal val mutex = Mutex()

    @Volatile
    internal var pages: PersistentMap<String, PageTranslation> = persistentMapOf()
    private val _state = MutableStateFlow<Map<String, PageTranslation>>(emptyMap())

    /**
     * TachiyomiAT (Phase 3): the last-known-good committed display bundle per
     * page. A candidate retry may mutate the live [pages] entry freely —
     * clearing statuses, wiping the cleaned name, stranding stages — but it
     * can never hide or mutate the committed bundle observed through
     * [display]. Promotion happens atomically inside the store mutex after the
     * page reached [PageTranslation.hasRenderedResult] shape, so observers
     * never see a half-promoted combination.
     */
    @Volatile
    private var committedDisplay: PersistentMap<String, CommittedPageDisplay> = persistentMapOf()

    /** Durable artifact manifest snapshot used by the live writer bridge. */
    @Volatile
    internal var artifactManifest: ChapterArtifactManifest? = initialArtifactManifest

    /**
     * Cleaned-image names retained because the superseded committed bundle
     * still displays them; drained for deletion by the pipeline after a newer
     * bundles promote. Keeping all names until a drain prevents a rapid sequence
     * of promotions from losing an earlier retained file.
     */
    private val retiredCleanedImages = ConcurrentHashMap<String, MutableSet<String>>()
    private val pendingArtifactPageRegistrations = LinkedHashSet<String>()
    private var pendingExpectedPageCount: Int? = null
    private var pendingExpectedPageCountTrusted = false

    private val _display = MutableStateFlow<Map<String, PageTranslation>>(emptyMap())

    // T909 Phase 17a: the lease-table state + lease bodies moved to
    // store/PageStageLeaseTable.kt; the map is shared through the table
    // (never copied) and the same-name accessor below keeps every store-side
    // reader unchanged. The public lease members stay as delegating stubs.
    private val pageStageLeaseTable = PageStageLeaseTable(this)

    /** Writer leases per page: one origin owns a page until it releases it. */
    private val pageLeases get() = pageStageLeaseTable.pageLeases

    @Volatile
    private var generation = 0L
    private var nextPageVersion = 0L

    /** Live candidate progress; readers that need display safety use [display]. */
    val state: StateFlow<Map<String, PageTranslation>> = _state.asStateFlow()

    /**
     * Committed-pointer display projection (Phase 3): each page resolves to
     * its immutable committed display bundle when one exists, otherwise the
     * live candidate entry. Candidate emissions can replace live entries but
     * never null or mutate a committed bundle.
     */
    val display: StateFlow<Map<String, PageTranslation>> = _display.asStateFlow()

    val currentGeneration: Long get() = generation

    /**
     * Frozen snapshot of the last displayable page state, plus the identity
     * that lets the store decide when a promotion supersedes an older bundle.
     */
    data class CommittedPageDisplay(
        val page: PageTranslation,
        val pageVersion: Long,
        val displayFingerprint: String,
        val promotedAtEpochMs: Long,
    )

    // T909 Phase 17a: PageLeaseRecord moved to store/PageStageLeaseTable.kt.

    fun resetPreflight(): ChapterResetPreflight = chapterResetPreflight(state.value.values)

    data class PageSnapshot(
        val page: PageTranslation?,
        val generation: Long,
        val pageVersion: Long,
        val blockFingerprints: List<String>,
        /** Lease fencing token held at snapshot time, when this writer owns the page. */
        val leaseToken: Long? = null,
        /** Artifact-manifest page version observed with this snapshot. */
        val artifactPageVersion: Long? = null,
        /** Candidate generation observed with this snapshot, when artifact authority owns it. */
        val candidateGenerationId: String? = null,
        /** Candidate dependency fingerprint observed with this snapshot. */
        val dependencyFingerprint: String? = null,
    )

    data class PatchPrecondition(
        val generation: Long,
        val pageVersion: Long,
        val blockFingerprints: List<String>? = null,
        /** A late result carrying an old lease token is rejected after release. */
        val leaseToken: Long? = null,
        /** Artifact candidate generation observed by this writer. */
        val candidateGenerationId: String? = null,
        /** Artifact candidate dependency fingerprint observed by this writer. */
        val dependencyFingerprint: String? = null,
        /** Artifact-manifest page version observed by this writer. */
        val artifactPageVersion: Long? = null,
    )

    sealed interface PatchResult {
        data class Accepted(val snapshot: PageSnapshot) : PatchResult
        data class Rejected(val reason: String) : PatchResult
    }

    private class GenerationContext(
        val store: ChapterTranslationStore,
        val generation: Long,
    ) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<GenerationContext>
    }

    // T909 Phase 8: glossary state + bodies moved to store/ChapterGlossaryStore.kt
    // (delegates under the store mutex; legacy read fallback kept). The public
    // glossary API stays at the old qualified names as delegating stubs.
    // T909 Phase 17b: internal so the moved StorePersistenceScheduler's
    // flushDirtyLocked can reach the glossary dirty flag through it.
    internal val glossaryStore = ChapterGlossaryStore(this)
    // T917 Phase 3 (D9): durable attempt ledger collaborator (delegates under
    // the store mutex; fail-open persistence; memory-only no-op writes).
    private val attemptLedger = ChapterAttemptLedger(this)
    // T909 Phase 17b: the flush/close/debounce/retention machinery moved to
    // store/StorePersistenceScheduler.kt; the scheduler owns persistScope and
    // is constructed eagerly (its ctor resolves no store state). `dirty` and
    // `persistJob` stay here — non-moved bodies (replaceAll/rekeyPages/
    // clearTransientQueuePages/markDefunct) read and write them directly; the
    // scheduler reaches them through internal accessors.
    internal var dirty = false
    internal var persistJob: Job? = null
    private val persistenceScheduler = StorePersistenceScheduler(this)

    /**
     * TachiyomiAT: set by [markDefunct] when the store is evicted
     * ([TranslationManager.unregisterActiveTranslationStore], on delete / chapter
     * change). Once defunct, every mutator becomes a no-op and logs at WARN. This
     * guards the delete-then-retranslate race: a batch worker mid-uncancellable
     * ONNX when cancel() was requested can still reach a suspension point AFTER
     * its store was evicted and the on-disk file + images deleted; without this
     * guard it would recreate the deleted file or strand a page at RUNNING.
     * Volatile: written under mutex by markDefunct, read by mutators lock-free.
     */
    @Volatile
    private var defunct = false

    fun markDefunct() {
        persistJob?.let { pendingPersist ->
            val completed = runBlocking {
                withTimeoutOrNull(PERSIST_JOIN_TIMEOUT_MS) { pendingPersist.join() }
            }
            if (completed == null) {
                logcat(LogPriority.WARN) {
                    "TachiyomiAT store persist did not finish within $PERSIST_JOIN_TIMEOUT_MS ms before eviction"
                }
            }
            pendingPersist.cancel()
        }
        persistJob = null
        defunct = true
        generation++
        synchronized(pageLeases) { pageLeases.clear() }
        logcat(LogPriority.WARN) { "TachiyomiAT store marked defunct: generation=$generation" }
    }

    val isDefunct: Boolean
        get() = defunct

    // T909 Phase 17b: persistCount is incremented by the moved
    // StorePersistenceScheduler, so the setter is no longer private.
    internal var persistCount = 0

    init {
        initialRetiredCleanedImages.forEach { (pageKey, names) ->
            if (names.isNotEmpty()) {
                retiredCleanedImages[pageKey] = ConcurrentHashMap.newKeySet<String>().also { it.addAll(names) }
            }
        }
        initialPages.forEach { (pageKey, page) ->
            val version = nextVersion()
            pages = pages.put(
                pageKey,
                page.detachedCopy().apply {
                    sourceFileName = sourceFileName ?: pageKey
                    runGeneration = generation
                    pageVersion = version
                },
            )
        }
        // Seed the last-known-good display pointers from durable artifact
        // snapshots first. The mutable live map may be an incomplete candidate
        // after process death; it must never become the reader authority.
        initialCommittedPages.forEach { (pageKey, page) ->
            committedDisplay = committedDisplay.put(
                pageKey,
                CommittedPageDisplay(
                    page = page.detachedCopy(),
                    pageVersion = page.pageVersion,
                    displayFingerprint = displayFingerprintOf(page),
                    promotedAtEpochMs = page.updatedAt,
                ),
            )
        }
        // Memory-only stores and legacy pages without a durable artifact
        // snapshot retain the compatibility seed from their display-ready
        // initial state.
        pages.forEach { (pageKey, page) ->
            if (committedDisplay.containsKey(pageKey)) return@forEach
            if (page.hasRenderedResult) {
                committedDisplay = committedDisplay.put(
                    pageKey,
                    CommittedPageDisplay(
                        page = page.detachedCopy(),
                        pageVersion = page.pageVersion,
                        displayFingerprint = displayFingerprintOf(page),
                        promotedAtEpochMs = page.updatedAt,
                    ),
                )
            }
        }
        _state.value = snapshotPages()
        _display.value = displaySnapshotLocked()
    }

    suspend fun snapshot(pageKey: String): PageSnapshot = mutex.withLock {
        snapshotLocked(pageKey)
    }

    // T909 Phase 15: durable status projection (artifactStatus + the
    // durable-failure read API) moved to store/StoreStatusProjector.kt; these
    // same-signature stubs keep the old qualified names (ChapterTranslator,
    // DurableChapterStatusResolver, and the migration/artifact-read tests
    // resolve them here).
    private val statusProjector get() = StoreStatusProjector(this)

    /**
     * One consistent read of the status-projection inputs for
     * [StoreStatusProjector], captured in the same order the projection body
     * reads them (manifest → live pages flow → display flow). The flows are
     * handed over by reference so their `.value` reads keep landing at the
     * projection body's original points.
     */
    internal fun statusProjectionInputs(): StoreStatusInputs =
        StoreStatusInputs(artifactManifest, state, display)

    /** Current durable stage failure, if the artifact manifest owns one. */
    fun durableFailure(
        pageKey: String,
        stage: ArtifactStage = ArtifactStage.TRANSLATION,
    ): DurableFailureMetadata? = statusProjector.durableFailure(pageKey, stage)

    /** Immutable view used by queue restoration and planner admission. */
    fun durableFailuresSnapshot(): Map<String, DurableFailureMetadata> =
        statusProjector.durableFailuresSnapshot()

    // ------------------------------------------------------------------
    // T917 Phase 3 (D9): durable attempt ledger (phase3-design §3).
    // All methods delegate to store/ChapterAttemptLedger.kt under the store
    // mutex. Ledger writes are fail-open; only AUTO origins can be refused
    // (the crash-loop cap binds auto-retry loops, never the user).
    // ------------------------------------------------------------------

    /**
     * Records a started paid attempt BEFORE the provider call. Returns false
     * only when [AttemptOrigin.AUTO] is refused by the consecutive-attempt cap.
     */
    suspend fun recordAttemptStart(
        pageKey: String,
        providerKeyHash: String,
        origin: AttemptOrigin,
        generation: Long = currentGeneration,
    ): Boolean = mutex.withLock {
        attemptLedger.recordStartLocked(pageKey, providerKeyHash, origin, generation)
    }

    /**
     * A completed call for [pageKey] — commit success OR typed provider
     * failure. Resolves the page's pending entries and resets its consecutive
     * counter; only a process death leaves an entry behind.
     */
    suspend fun resolveAttempt(pageKey: String) {
        mutex.withLock { attemptLedger.resolveLocked(pageKey) }
    }

    /**
     * Startup reconciliation: consume every pending entry as one counted
     * interrupted attempt per page and persist the counters. Returns the
     * post-consume consecutive counters; the caller (TranslationManager)
     * applies the cap for pages at/over the bound.
     */
    suspend fun consumeUnresolvedAttemptsAtStartup(): Map<String, Int> = mutex.withLock {
        attemptLedger.consumeAtStartupLocked()
    }

    /**
     * Applies the crash-loop cap to one page: records an INTERRUPTED-class
     * durable failure (never auto-retryable) and marks the page PARTIAL.
     * Returns true when the cap state is durably recorded.
     */
    suspend fun applyAttemptCapPause(pageKey: String, consecutiveUnresolved: Int): Boolean {
        val failure = DurableFailureMetadata(
            pageKey = pageKey,
            stage = ArtifactStage.TRANSLATION,
            status = ArtifactStageStatus.FAILED_RETRYABLE,
            category = FailureCategory.INTERRUPTED,
            retryCount = consecutiveUnresolved,
            lastFailureMessage = "repeatedly interrupted before completing; manual retry required",
            lastFailedAtEpochMs = System.currentTimeMillis(),
            nextEligibleRetryAtEpochMs = null,
        )
        val expected = snapshot(pageKey).toPrecondition()
        val result = persistDurableStageFailure(
            pageKey = pageKey,
            expected = expected,
            failure = failure,
            description = "D9 attempt cap reached ($consecutiveUnresolved consecutive interrupted attempts)",
        ) { current ->
            (current ?: PageTranslation(sourceFileName = pageKey)).apply {
                translationStatus = StageStatus.PARTIAL
            }
        }
        return result is PatchResult.Accepted
    }

    /**
     * Explicit user force: clear the page's consecutive counter and remove the
     * INTERRUPTED-class cap failure from the manifest, so the user's retry is
     * admitted and the cap restarts from zero.
     */
    suspend fun clearAttemptCapForManualRetry(pageKey: String): Boolean = mutex.withLock {
        val counterCleared = attemptLedger.clearCapLocked(pageKey)
        removeInterruptedCapFailureLocked(pageKey)
        counterCleared
    }

    /** Caller holds the store mutex. */
    private fun removeInterruptedCapFailureLocked(pageKey: String) {
        val artifact = artifactStore ?: return
        val manifest = artifactManifest ?: return
        if (manifest.authority != ManifestAuthority.ARTIFACTS) return
        val key = "$pageKey:${ArtifactStage.TRANSLATION.name}"
        val existing = manifest.durableFailures[key] ?: return
        if (existing.category != FailureCategory.INTERRUPTED) return
        val updated = manifest.copy(
            durableFailures = manifest.durableFailures - key,
            updatedAtEpochMs = System.currentTimeMillis(),
        )
        if (artifact.publishManifest(updated)) {
            artifactManifest = updated
            _state.value = snapshotPages()
            _display.value = displaySnapshotLocked()
        } else {
            logcat(LogPriority.WARN) {
                "TachiyomiAT D9: cap-failure clear publish failed (fail-open): pageKey=$pageKey"
            }
        }
    }

    /** Establishes artifact authority before a caller installs a new page state. */
    suspend fun ensureArtifactAuthorityForMutation(): MutationAdmission = mutex.withLock {
        admitMutationLocked()
    }

    internal fun admitMutationLocked(): MutationAdmission {
        if (defunct) {
            return MutationAdmission.Rejected(
                code = MutationAdmission.Rejected.Code.STORE_DEFUNCT,
                message = "store is defunct",
            )
        }
        if (artifactStore != null && artifactManifest?.authority == ManifestAuthority.ARTIFACTS) {
            return MutationAdmission.Granted
        }
        // Explicitly memory-only stores are used by pure reducer/unit tests;
        // they have no persistence target and therefore cannot accidentally
        // create a legacy document. Production stores always provide a parent
        // or an already-open legacy document and take the rescue path below.
        if (artifactStore == null &&
            artifactParent == null &&
            artifactFileName == null &&
            translationFile == null &&
            fileCreator == null
        ) {
            return MutationAdmission.Granted
        }
        if (!ensureArtifactStoreLocked()) {
            return MutationAdmission.Rejected(
                code = MutationAdmission.Rejected.Code.LEGACY_RESCUE_FAILED,
                message = "artifact store rescue/publication failed",
            )
        }
        return if (artifactStore != null && artifactManifest?.authority == ManifestAuthority.ARTIFACTS) {
            MutationAdmission.Granted
        } else {
            MutationAdmission.Rejected(
                code = MutationAdmission.Rejected.Code.LEGACY_RESCUE_REQUIRED,
                message = "artifact authority was not established",
            )
        }
    }

    suspend fun beginGeneration(reason: String): Long = mutex.withLock {
        generation++
        logcat(LogPriority.INFO) { "TachiyomiAT store generation advanced: generation=$generation reason=$reason" }
        generation
    }

    // T909 Phase 17a: lease bodies (and the Phase 3 lifecycle-contract comment
    // block) moved to store/PageStageLeaseTable.kt; these same-signature stubs
    // keep the old qualified names (the pipeline, ReaderViewModel, and the
    // lease tests resolve them here). The DUAL locking (store mutex in the
    // table's withLock paths + synchronized(leases) lock-free readers) and the
    // NonCancellable wrappers moved verbatim with the bodies.

    suspend fun tryAcquirePageStageLease(
        pageKey: String,
        stage: PageStage,
        origin: PageWriteOrigin,
    ): LeaseAcquisition = pageStageLeaseTable.tryAcquirePageStageLease(pageKey, stage, origin)

    suspend fun releasePageStageLease(pageKey: String, origin: PageWriteOrigin) =
        pageStageLeaseTable.releasePageStageLease(pageKey, origin)

    /** Cancels the active artifact candidate and releases its matching writer lease. */
    suspend fun cancelPageStageWork(pageKey: String, origin: PageWriteOrigin): Boolean =
        pageStageLeaseTable.cancelPageStageWork(pageKey, origin)

    /** Releases every lease held by [origin]; used at batch teardown so no lease outlives its run. */
    suspend fun releaseAllPageLeases(origin: PageWriteOrigin) =
        pageStageLeaseTable.releaseAllPageLeases(origin)

    fun pageLeaseOwner(pageKey: String): PageWriteOrigin? = pageStageLeaseTable.pageLeaseOwner(pageKey)

    /**
     * T917 D3 defer-and-rescan: suspends until the page is lease-free, bounded
     * by [timeoutMs] (true = lease-free at resume, false = timed out or a newer
     * lease appeared). See [PageStageLeaseTable.awaitPageLeaseRelease].
     */
    suspend fun awaitPageLeaseRelease(pageKey: String, timeoutMs: Long): Boolean =
        pageStageLeaseTable.awaitPageLeaseRelease(pageKey, timeoutMs)

    suspend fun invalidateGeneration(reason: String): Long = beginGeneration(reason)

    suspend fun <T> withGeneration(generation: Long, block: suspend () -> T): T =
        withContext(GenerationContext(this, generation)) { block() }

    suspend fun patchPage(
        pageKey: String,
        expected: PatchPrecondition,
        description: String,
        patch: (PageTranslation?) -> PageTranslation,
    ): PatchResult {
        if (defunct) return rejected(pageKey, description, "store is defunct")
        return mutex.withLock {
            when (val admission = admitMutationLocked()) {
                MutationAdmission.Granted -> Unit
                is MutationAdmission.Rejected -> return@withLock rejected(
                    pageKey,
                    description,
                    "${admission.code}: ${admission.message}",
                )
            }
            val current = pages[pageKey]
            val rejection = when {
                expected.generation != generation -> "generation expected=${expected.generation} actual=$generation"
                expected.pageVersion != (current?.pageVersion ?: 0L) ->
                    "pageVersion expected=${expected.pageVersion} actual=${current?.pageVersion ?: 0L}"
                expected.artifactPageVersion != null &&
                    expected.artifactPageVersion != artifactManifest?.pages?.get(pageKey)?.pageVersion ->
                    "artifact pageVersion changed"
                expected.candidateGenerationId != null &&
                    expected.candidateGenerationId != artifactManifest?.pages?.get(pageKey)?.candidate?.generationId ->
                    "candidate generation changed"
                // T917 Phase 3 backlog fold-in (phase3-design §4): same
                // `candidate != null` grace persistArtifactMutationLocked
                // applies — a dependency fingerprint expected against a
                // candidate-LESS record (candidate never opened, cleared by an
                // abort, or the candidate-less registration a batch start
                // performs for a held page) has nothing real to compare
                // against, so the mismatch must not reject. Snapshot captures
                // arm this clause from the page-snapshot fallback even with no
                // candidate, which made every such manual commit a false
                // reject. Fail direction preserved: generation, pageVersion,
                // candidate generation, block fingerprints, and the lease
                // token fences stay fully armed.
                expected.dependencyFingerprint != null &&
                    artifactManifest?.pages?.get(pageKey)?.candidate != null &&
                    expected.dependencyFingerprint != artifactManifest?.pages?.get(pageKey)?.candidate?.dependencyFingerprint ->
                    "candidate dependency fingerprint changed"
                expected.blockFingerprints != null &&
                    expected.blockFingerprints != current?.blockFingerprints().orEmpty() ->
                    "block fingerprint changed"
                expected.leaseToken != null && pageLeases[pageKey]?.token != expected.leaseToken ->
                    "page lease token changed"
                expected.leaseToken == null && pageLeases[pageKey] != null ->
                    "page lease token required"
                else -> null
            }
            if (rejection != null) {
                rejected(pageKey, description, rejection)
            } else {
                val candidate = try {
                    patch(current?.detachedCopy())
                } catch (failure: IllegalArgumentException) {
                    return@withLock rejected(
                        pageKey,
                        description,
                        failure.message ?: failure::class.java.simpleName,
                    )
                } catch (failure: IllegalStateException) {
                    return@withLock rejected(
                        pageKey,
                        description,
                        failure.message ?: failure::class.java.simpleName,
                    )
                }
                val updated = ownedPage(pageKey, candidate)
                pages = pages.put(pageKey, updated)
                if (!publishLocked(current, updated, expected)) {
                    restorePageLocked(pageKey, current)
                    return@withLock rejected(pageKey, description, "ARTIFACT_PUBLICATION_FAILED")
                }
                PatchResult.Accepted(snapshotLocked(pageKey))
            }
        }
    }

    /**
     * Guarded whole-page candidate mutation for production stage writers. The
     * caller must carry the exact identity it observed before the asynchronous
     * stage began; a released/reacquired lease, page mutation, generation reset,
     * or artifact candidate replacement rejects the write before the candidate
     * bridge can publish anything durable.
     */
    suspend fun updatePageGuarded(
        pageKey: String,
        expected: PatchPrecondition,
        description: String,
        update: (PageTranslation?) -> PageTranslation,
    ): PatchResult {
        if (defunct) return rejected(pageKey, description, "store is defunct")
        return mutex.withLock {
            when (val admission = admitMutationLocked()) {
                MutationAdmission.Granted -> Unit
                is MutationAdmission.Rejected -> return@withLock rejected(
                    pageKey,
                    description,
                    "${admission.code}: ${admission.message}",
                )
            }
            val rejection = pageWriteRejection(pageKey, expected)
            if (rejection != null) {
                rejected(pageKey, description, rejection)
            } else {
                val previous = pages[pageKey]
                val updated = ownedPage(pageKey, update(previous?.detachedCopy()))
                pages = pages.put(pageKey, updated)
                if (!publishLocked(previous, updated, expected)) {
                    restorePageLocked(pageKey, previous)
                    return@withLock rejected(pageKey, description, "ARTIFACT_PUBLICATION_FAILED")
                }
                PatchResult.Accepted(snapshotLocked(pageKey))
            }
        }
    }

    /**
     * Persists a retryable/terminal stage failure together with the current
     * candidate snapshot. The page mutation and durable failure pointer share
     * one artifact-manifest publication, so a restart cannot observe a page
     * status without its failure metadata (or vice versa).
     */
    suspend fun persistDurableStageFailure(
        pageKey: String,
        expected: PatchPrecondition,
        failure: DurableFailureMetadata,
        description: String = "durable stage failure",
        update: (PageTranslation?) -> PageTranslation,
    ): PatchResult {
        if (defunct) return rejected(pageKey, description, "store is defunct")
        return mutex.withLock {
            when (val admission = admitMutationLocked()) {
                MutationAdmission.Granted -> Unit
                is MutationAdmission.Rejected -> return@withLock rejected(
                    pageKey,
                    description,
                    "${admission.code}: ${admission.message}",
                )
            }
            val rejection = pageWriteRejection(pageKey, expected)
            if (rejection != null) {
                return@withLock rejected(pageKey, description, rejection)
            }
            val previous = pages[pageKey]
            val updated = try {
                ownedPage(pageKey, update(previous?.detachedCopy()))
            } catch (error: IllegalArgumentException) {
                return@withLock rejected(pageKey, description, error.message ?: error::class.java.simpleName)
            } catch (error: IllegalStateException) {
                return@withLock rejected(pageKey, description, error.message ?: error::class.java.simpleName)
            }
            pages = pages.put(pageKey, updated)
            val persisted = persistArtifactMutationLocked(
                pageKey = pageKey,
                previous = previous,
                updated = updated,
                expected = expected,
                durableFailure = failure,
            )
            if (!persisted) {
                restorePageLocked(pageKey, previous)
                return@withLock rejected(pageKey, description, "ARTIFACT_PUBLICATION_FAILED")
            }
            // A retryable/terminal candidate is deliberately not promoted. The
            // prior committed display remains the reader authority.
            _state.value = snapshotPages()
            _display.value = displaySnapshotLocked()
            PatchResult.Accepted(snapshotLocked(pageKey))
        }
    }

    /** Compatibility-shaped convenience for non-stage callers; still fenced by a snapshot. */
    suspend fun updatePageFromCurrentSnapshot(
        pageKey: String,
        description: String,
        update: (PageTranslation?) -> PageTranslation,
    ): PatchResult = updatePageGuarded(pageKey, snapshot(pageKey).toPrecondition(), description, update)

    suspend fun patchBlock(
        pageKey: String,
        blockIndex: Int,
        expected: PatchPrecondition,
        expectedBlockFingerprint: String,
        expectedTranslation: String? = null,
        expectedUserEditedAt: Long? = null,
        description: String,
        patch: (eu.kanade.translation.model.TranslationBlock) -> eu.kanade.translation.model.TranslationBlock,
    ): PatchResult = patchPage(pageKey, expected, description) { page ->
        requireNotNull(page) { "page missing" }
        val current = page.blocks.getOrNull(blockIndex)
            ?: throw IllegalStateException("block index $blockIndex missing")
        check(current.stableFingerprint() == expectedBlockFingerprint) { "block fingerprint changed" }
        if (expectedTranslation !=
            null
        ) {
            check(current.translation == expectedTranslation) { "block translation changed" }
        }
        check(current.userEditedAt == expectedUserEditedAt) { "block edit timestamp changed" }
        page.apply { blocks[blockIndex] = patch(current.detachedCopy()).detachedCopy() }
    }

    /** Apply a stage patch while checking only the identities owned by that stage. */
    suspend fun applyStagePatch(
        patch: StagePatch,
        description: String,
    ): StagePatchResult {
        if (defunct) return rejectedStage(patch.pageKey, description, "store is defunct")
        return mutex.withLock {
            when (val admission = admitMutationLocked()) {
                MutationAdmission.Granted -> Unit
                is MutationAdmission.Rejected -> return@withLock rejectedStage(
                    patch.pageKey,
                    description,
                    "${admission.code}: ${admission.message}",
                )
            }
            val current = pages[patch.pageKey]
            when (patch) {
                is StagePatch.Ocr -> mergeOcrLocked(current, patch.value, description)
                is StagePatch.Translation -> mergeTranslationLocked(current, patch.value, description)
                is StagePatch.Inpaint -> mergeInpaintLocked(current, patch.value, description)
                is StagePatch.Render -> mergeRenderLocked(current, patch.value, description)
            }
        }
    }

    /**
     * Detection/OCR stage merge (Phase 3). The patch must carry the page
     * version and prior OCR identity observed before the native recognition
     * pass; anything that touched the page since makes the writer stale and
     * the merge is rejected, so a late recognition result can never clobber
     * newer work or drop translations merged in the meantime.
     *
     * Manual target edits are authoritative for their exact source block: a
     * new block whose OCR identity matches an edited block inherits its
     * translation and edit timestamp. On a failed recognition the merge keeps
     * the existing blocks and only records the failure diagnostics.
     */
    suspend fun mergeOcr(
        patch: OcrStagePatch,
        description: String = "detection/ocr stage merge",
    ): StagePatchResult = applyStagePatch(StagePatch.Ocr(patch), description)

    private fun mergeOcrLocked(
        current: PageTranslation?,
        patch: OcrStagePatch,
        description: String,
    ): StagePatchResult {
        val rejection = when {
            patch.generation != generation ->
                "generation expected=${patch.generation} actual=$generation"
            current == null -> "page missing"
            patch.expectedPageVersion != current.pageVersion ->
                "pageVersion expected=${patch.expectedPageVersion} actual=${current.pageVersion}"
            patch.expectedArtifactPageVersion != null &&
                patch.expectedArtifactPageVersion != artifactManifest?.pages?.get(patch.pageKey)?.pageVersion ->
                "artifact pageVersion changed"
            patch.expectedCandidateGenerationId != null &&
                patch.expectedCandidateGenerationId != artifactManifest?.pages?.get(patch.pageKey)?.candidate?.generationId ->
                "candidate generation changed"
            patch.expectedDependencyFingerprint != null &&
                artifactManifest?.pages?.get(patch.pageKey)?.candidate?.let { candidate ->
                    patch.expectedDependencyFingerprint != candidate.dependencyFingerprint
                } == true ->
                "candidate dependency fingerprint changed"
            patch.expectedPriorOcrFingerprints != current.ocrBlockFingerprints() ->
                "prior OCR identity changed"
            patch.expectedLeaseToken != null && pageLeases[patch.pageKey]?.token != patch.expectedLeaseToken ->
                "page lease token changed"
            patch.expectedLeaseToken == null && pageLeases[patch.pageKey] != null ->
                "page lease token required"
            else -> null
        }
        if (rejection != null) return rejectedStage(patch.pageKey, description, rejection)

        val result = patch.ocrResult
        val updated = current!!.detachedCopy()
        if (result.ocrStatus == StageStatus.FAILED) {
            // Failure diagnostics only: reusable blocks, masks, and any
            // committed display survive a failed recognition attempt.
            updated.ocrStatus = StageStatus.FAILED
            updated.errorMessage = patch.errorMessage ?: result.activeError
        } else {
            val preserveReusableInpaint =
                result.inpaintStatus == StageStatus.PENDING &&
                    result.sourceFingerprint != null &&
                    result.sourceFingerprint == current.sourceFingerprint &&
                    result.detectionFingerprint != null &&
                    result.detectionFingerprint == current.detectionFingerprint &&
                    current.inpaintStatus == StageStatus.READY &&
                    current.cleanedImageName != null &&
                    current.inpaintRevision >= PageTranslation.CURRENT_INPAINT_REVISION
            val editedByIdentity = current.blocks
                .filter { it.userEditedAt != null }
                .associateBy { it.ocrFingerprint() }
            updated.blocks = result.blocks.map { block ->
                editedByIdentity[block.ocrFingerprint()]?.let { edited ->
                    block.copy(translation = edited.translation, userEditedAt = edited.userEditedAt)
                } ?: block
            }.toMutableList()
            updated.inpaintMaskBoxes = result.inpaintMaskBoxes
            updated.allTextDetections = result.allTextDetections
            updated.ocrStatus = result.ocrStatus
            updated.inpaintStatus = result.inpaintStatus
            updated.renderStatus = result.renderStatus
            updated.detectionCount = result.detectionCount
            updated.ocrBlockCount = result.ocrBlockCount
            updated.recognitionEngine = result.recognitionEngine
            updated.decodeSampleSize = result.decodeSampleSize
            updated.imgWidth = result.imgWidth
            updated.imgHeight = result.imgHeight
            updated.originalImgWidth = result.originalImgWidth
            updated.originalImgHeight = result.originalImgHeight
            updated.cleanedImageName = result.cleanedImageName ?: updated.cleanedImageName
            updated.inpaintRevision = result.inpaintRevision
            updated.inpaintingModeUsed = result.inpaintingModeUsed
            updated.detectionFingerprint = result.detectionFingerprint
            updated.ocrFingerprint = result.ocrFingerprint
            if (preserveReusableInpaint) {
                // OCR-only invalidation does not invalidate a mask/cleaned
                // artifact derived from the unchanged detector. Keep that
                // durable payload reusable while the new OCR result proceeds
                // to translation; a detector or source change still takes the
                // normal invalidation path above.
                updated.inpaintMaskBoxes = current.inpaintMaskBoxes
                updated.inpaintStatus = current.inpaintStatus
                updated.inpaintError = current.inpaintError
                updated.inpaintRevision = current.inpaintRevision
                updated.inpaintingModeUsed = current.inpaintingModeUsed
                updated.inpaintFingerprint = current.inpaintFingerprint
                updated.cleanedImageName = current.cleanedImageName
            }
            patch.errorMessage?.let { updated.errorMessage = it }
        }
        val owned = ownedPage(patch.pageKey, updated)
        pages = pages.put(patch.pageKey, owned)
        if (!publishLocked(current, owned, patch.toPrecondition())) {
            restorePageLocked(patch.pageKey, current)
            return rejectedStage(patch.pageKey, description, "ARTIFACT_PUBLICATION_FAILED")
        }
        return StagePatchResult.Accepted(snapshotLocked(patch.pageKey))
    }

    suspend fun mergeTranslation(
        patch: TranslationStagePatch,
        description: String = "translation stage merge",
    ): StagePatchResult = applyStagePatch(StagePatch.Translation(patch), description)

    suspend fun mergeInpaint(
        patch: InpaintStagePatch,
        description: String = "inpaint stage merge",
    ): StagePatchResult = applyStagePatch(StagePatch.Inpaint(patch), description)

    suspend fun mergeRender(
        patch: RenderStagePatch,
        description: String = "render stage merge",
    ): StagePatchResult = applyStagePatch(StagePatch.Render(patch), description)

    private fun mergeTranslationLocked(
        current: PageTranslation?,
        patch: TranslationStagePatch,
        description: String,
    ): StagePatchResult {
        val identityRejection = stageIdentityRejection(
            current,
            patch.generation,
            patch.expectedPageVersion,
            patch.expectedLeaseToken,
            patch.pageKey,
            patch.expectedCandidateGenerationId,
            patch.expectedDependencyFingerprint,
            patch.expectedArtifactPageVersion,
        )
            ?: ocrIdentityRejection(current, patch.expectedOcrBlockFingerprints, patch.expectedSourceTexts)
        if (identityRejection != null) {
            return rejectedStage(patch.pageKey, description, identityRejection)
        }
        val page = current!!.detachedCopy()
        val applied = mutableListOf<Int>()
        val rejectedTargets = mutableListOf<String>()
        patch.blocks.forEach { target ->
            val block = page.blocks.getOrNull(target.blockIndex)
            val rejection = when {
                block == null -> "block index ${target.blockIndex} missing"
                block.ocrFingerprint() != target.expectedOcrFingerprint ->
                    "block ${target.blockIndex} OCR identity changed"
                block.text != target.expectedSourceText ->
                    "block ${target.blockIndex} source changed"
                block.translation != target.expectedTranslation ->
                    "block ${target.blockIndex} translation changed"
                block.userEditedAt != target.expectedUserEditedAt ->
                    "block ${target.blockIndex} user edit changed"
                else -> null
            }
            if (rejection != null) {
                rejectedTargets += rejection
            } else {
                block!!.translation = target.translation
                applied += target.blockIndex
            }
        }
        if (applied.isEmpty()) {
            return rejectedStage(
                patch.pageKey,
                description,
                rejectedTargets.firstOrNull() ?: "no translation targets",
            )
        }
        page.translationStatus = patch.translationStatus
        page.errorMessage = patch.errorMessage
        val updated = ownedPage(patch.pageKey, page)
        pages = pages.put(patch.pageKey, updated)
        if (!publishLocked(current, updated, patch.toPrecondition())) {
            restorePageLocked(patch.pageKey, current)
            return rejectedStage(patch.pageKey, description, "ARTIFACT_PUBLICATION_FAILED")
        }
        return StagePatchResult.Accepted(snapshotLocked(patch.pageKey), applied)
    }

    private fun mergeInpaintLocked(
        current: PageTranslation?,
        patch: InpaintStagePatch,
        description: String,
    ): StagePatchResult {
        val rejection = stageIdentityRejection(
            current,
            patch.generation,
            patch.expectedPageVersion,
            patch.expectedLeaseToken,
            patch.pageKey,
            patch.expectedCandidateGenerationId,
            patch.expectedDependencyFingerprint,
            patch.expectedArtifactPageVersion,
        )
            ?: ocrIdentityRejection(current, patch.expectedOcrBlockFingerprints, null)
            ?: current?.takeUnless { it.inpaintMaskFingerprint() == patch.expectedMaskFingerprint }
                ?.let { "inpaint mask identity changed" }
        if (rejection != null) return rejectedStage(patch.pageKey, description, rejection)

        val updated = ownedPage(
            patch.pageKey,
            current!!.detachedCopy().apply {
                cleanedImageName = patch.cleanedImageName
                inpaintRevision = patch.inpaintRevision
                inpaintingModeUsed = patch.inpaintingModeUsed
                inpaintStatus = patch.inpaintStatus
                errorMessage = patch.errorMessage
            },
        )
        pages = pages.put(patch.pageKey, updated)
        if (!publishLocked(current, updated, patch.toPrecondition())) {
            restorePageLocked(patch.pageKey, current)
            return rejectedStage(patch.pageKey, description, "ARTIFACT_PUBLICATION_FAILED")
        }
        return StagePatchResult.Accepted(snapshotLocked(patch.pageKey))
    }

    private fun mergeRenderLocked(
        current: PageTranslation?,
        patch: RenderStagePatch,
        description: String,
    ): StagePatchResult {
        val rejection = stageIdentityRejection(
            current,
            patch.generation,
            patch.expectedPageVersion,
            patch.expectedLeaseToken,
            patch.pageKey,
            patch.expectedCandidateGenerationId,
            patch.expectedDependencyFingerprint,
            patch.expectedArtifactPageVersion,
        )
            ?: ocrIdentityRejection(current, patch.expectedOcrBlockFingerprints, null)
            ?: current?.takeUnless { it.cleanedImageName == patch.expectedCleanedImageName }
                ?.let { "cleaned image identity changed" }
            ?: current?.takeUnless { it.inpaintRevision == patch.expectedInpaintRevision }
                ?.let { "inpaint revision changed" }
        if (rejection != null) return rejectedStage(patch.pageKey, description, rejection)

        val page = current!!.detachedCopy()
        patch.blocks.forEach { target ->
            val block = page.blocks.getOrNull(target.blockIndex)
            if (block == null) {
                return rejectedStage(patch.pageKey, description, "block index ${target.blockIndex} missing")
            }
            if (block.stableFingerprint() != target.expectedBlockFingerprint) {
                return rejectedStage(patch.pageKey, description, "render block identity changed")
            }
        }
        patch.blocks.forEach { target ->
            val block = page.blocks[target.blockIndex]
            block.textColor = target.textColor
            block.strokeColor = target.strokeColor
            block.strokeWidth = target.strokeWidth
        }
        page.renderStatus = patch.renderStatus
        patch.layoutFingerprint?.let { page.layoutFingerprint = it }
        page.errorMessage = patch.errorMessage
        val updated = ownedPage(patch.pageKey, page)
        pages = pages.put(patch.pageKey, updated)
        if (!publishLocked(current, updated, patch.toPrecondition())) {
            restorePageLocked(patch.pageKey, current)
            return rejectedStage(patch.pageKey, description, "ARTIFACT_PUBLICATION_FAILED")
        }
        return StagePatchResult.Accepted(snapshotLocked(patch.pageKey), patch.blocks.map { it.blockIndex })
    }

    private fun stageIdentityRejection(
        page: PageTranslation?,
        expectedGeneration: Long,
        expectedPageVersion: Long? = null,
        expectedLeaseToken: Long? = null,
        pageKey: String? = null,
        expectedCandidateGenerationId: String? = null,
        expectedDependencyFingerprint: String? = null,
        expectedArtifactPageVersion: Long? = null,
    ): String? = when {
        expectedGeneration != generation -> "generation expected=$expectedGeneration actual=$generation"
        page == null -> "page missing"
        expectedPageVersion != null && expectedPageVersion != page.pageVersion ->
            "pageVersion expected=$expectedPageVersion actual=${page.pageVersion}"
        expectedArtifactPageVersion != null &&
            pageKey != null &&
            expectedArtifactPageVersion != artifactManifest?.pages?.get(pageKey)?.pageVersion ->
            "artifact pageVersion changed"
        expectedCandidateGenerationId != null &&
            pageKey != null &&
            expectedCandidateGenerationId != artifactManifest?.pages?.get(pageKey)?.candidate?.generationId ->
            "candidate generation changed"
        expectedDependencyFingerprint != null &&
            pageKey != null &&
            artifactManifest?.pages?.get(pageKey)?.candidate?.let { candidate ->
                expectedDependencyFingerprint != candidate.dependencyFingerprint
            } == true ->
            "candidate dependency fingerprint changed"
        expectedLeaseToken != null && pageKey != null && pageLeases[pageKey]?.token != expectedLeaseToken ->
            "page lease token changed"
        expectedLeaseToken == null && pageKey != null && pageLeases[pageKey] != null ->
            "page lease token required"
        else -> null
    }

    private fun OcrStagePatch.toPrecondition() = PatchPrecondition(
        generation = generation,
        pageVersion = expectedPageVersion,
        blockFingerprints = expectedPriorOcrFingerprints,
        leaseToken = expectedLeaseToken,
        candidateGenerationId = expectedCandidateGenerationId,
        dependencyFingerprint = expectedDependencyFingerprint,
        artifactPageVersion = expectedArtifactPageVersion,
    )

    private fun TranslationStagePatch.toPrecondition() = PatchPrecondition(
        generation = generation,
        pageVersion = expectedPageVersion ?: 0L,
        leaseToken = expectedLeaseToken,
        candidateGenerationId = expectedCandidateGenerationId,
        dependencyFingerprint = expectedDependencyFingerprint,
        artifactPageVersion = expectedArtifactPageVersion,
    )

    private fun InpaintStagePatch.toPrecondition() = PatchPrecondition(
        generation = generation,
        pageVersion = expectedPageVersion ?: 0L,
        leaseToken = expectedLeaseToken,
        candidateGenerationId = expectedCandidateGenerationId,
        dependencyFingerprint = expectedDependencyFingerprint,
        artifactPageVersion = expectedArtifactPageVersion,
    )

    private fun RenderStagePatch.toPrecondition() = PatchPrecondition(
        generation = generation,
        pageVersion = expectedPageVersion ?: 0L,
        leaseToken = expectedLeaseToken,
        candidateGenerationId = expectedCandidateGenerationId,
        dependencyFingerprint = expectedDependencyFingerprint,
        artifactPageVersion = expectedArtifactPageVersion,
    )

    private fun pageWriteRejection(pageKey: String, expected: PatchPrecondition): String? {
        val current = pages[pageKey]
        val artifactPage = artifactManifest?.pages?.get(pageKey)
        return when {
            expected.generation != generation ->
                "generation expected=${expected.generation} actual=$generation"
            expected.pageVersion != (current?.pageVersion ?: 0L) ->
                "pageVersion expected=${expected.pageVersion} actual=${current?.pageVersion ?: 0L}"
            expected.artifactPageVersion != null && expected.artifactPageVersion != artifactPage?.pageVersion ->
                "artifact pageVersion changed"
            expected.candidateGenerationId != null &&
                expected.candidateGenerationId != artifactPage?.candidate?.generationId ->
                "candidate generation changed"
            expected.dependencyFingerprint != null &&
                artifactPage?.candidate?.let { candidate ->
                    expected.dependencyFingerprint != candidate.dependencyFingerprint
                } == true ->
                "candidate dependency fingerprint changed"
            expected.blockFingerprints != null &&
                expected.blockFingerprints != current?.blockFingerprints().orEmpty() ->
                "block fingerprint changed"
            expected.leaseToken != null && pageLeases[pageKey]?.token != expected.leaseToken ->
                "page lease token changed"
            expected.leaseToken == null && pageLeases[pageKey] != null ->
                "page lease token required"
            else -> null
        }
    }

    private fun ocrIdentityRejection(
        page: PageTranslation?,
        expectedFingerprints: List<String>,
        expectedSources: List<String>?,
    ): String? = when {
        page == null -> "page missing"
        page.ocrBlockFingerprints() != expectedFingerprints -> "OCR block identity changed"
        expectedSources != null && page.blocks.map { it.text } != expectedSources -> "OCR source changed"
        else -> null
    }

    private fun rejectedStage(pageKey: String, description: String, reason: String): StagePatchResult.Rejected {
        logcat(LogPriority.WARN) {
            "TachiyomiAT stage patch rejected: pageKey=$pageKey generation=$generation " +
                "operation=$description reason=$reason"
        }
        return StagePatchResult.Rejected(reason)
    }

    suspend fun updatePage(pageKey: String, update: (PageTranslation?) -> PageTranslation) {
        if (defunct) {
            rejected(pageKey, "updatePage", "store is defunct")
            return
        }
        val context = currentCoroutineContext()[GenerationContext]
        val expected = mutex.withLock {
            if (context?.store === this && context.generation != generation) {
                null
            } else if (artifactManifest?.authority == ManifestAuthority.ARTIFACTS && pageLeases[pageKey] != null) {
                logcat(LogPriority.WARN) {
                    "TachiyomiAT compatibility updatePage rejected while a page lease is active: pageKey=$pageKey"
                }
                null
            } else {
                snapshotLocked(pageKey).toPrecondition()
            }
        }
        if (expected == null) {
            if (context?.store === this && context.generation != generation) {
                rejected(
                    pageKey,
                    "updatePage",
                    "generation expected=${context.generation} actual=$generation",
                )
            }
            return
        }
        updatePageGuarded(pageKey, expected, "updatePage", update)
    }

    suspend fun deletePage(pageKey: String) {
        if (defunct) return
        mutex.withLock {
            when (val admission = admitMutationLocked()) {
                MutationAdmission.Granted -> Unit
                is MutationAdmission.Rejected -> {
                    logcat(LogPriority.WARN) {
                        "TachiyomiAT store deletePage rejected: " +
                            "code=${admission.code} reason=${admission.message}"
                    }
                    return@withLock
                }
            }
            if (pages.containsKey(pageKey)) {
                deleteArtifactPageLocked(pageKey)
                pages = pages.remove(pageKey)
                committedDisplay = committedDisplay.remove(pageKey)
                retiredCleanedImages.remove(pageKey)
                pageLeases.remove(pageKey)
                schedulePersist()
                _state.value = snapshotPages()
                _display.value = displaySnapshotLocked()
            }
        }
    }

    suspend fun replaceAll(updatedPages: Map<String, PageTranslation>) {
        if (defunct) {
            logcat(LogPriority.WARN) {
                "TachiyomiAT store replaceAll rejected: store is defunct"
            }
            return
        }
        mutex.withLock {
            when (val admission = admitMutationLocked()) {
                MutationAdmission.Granted -> Unit
                is MutationAdmission.Rejected -> {
                    logcat(LogPriority.WARN) {
                        "TachiyomiAT store replaceAll rejected: " +
                            "code=${admission.code} reason=${admission.message}"
                    }
                    return@withLock
                }
            }
            if (artifactManifest?.authority == ManifestAuthority.ARTIFACTS) {
                artifactManifest?.pages?.keys?.toList().orEmpty().forEach { pageKey ->
                    deleteArtifactPageLocked(pageKey)
                }
            }
            pages = persistentMapOf()
            committedDisplay = persistentMapOf()
            retiredCleanedImages.clear()
            updatedPages.forEach { (pageKey, page) ->
                val owned = ownedPage(pageKey, page)
                pages = pages.put(pageKey, owned)
                if (persistArtifactMutationLocked(pageKey, null, owned)) {
                    promoteDisplayIfReadyLocked(pageKey, owned)
                }
            }
            dirty = !persistLocked()
            _state.value = snapshotPages()
            _display.value = displaySnapshotLocked()
        }
    }

    /** Moves source-keyed pages to their completed-download keys atomically. */
    suspend fun rekeyPages(
        onlineKeys: List<String>,
        onDiskKeys: List<String>,
    ): List<Pair<String, String>> {
        if (defunct || onlineKeys.size != onDiskKeys.size) return emptyList()
        return mutex.withLock {
            when (val admission = admitMutationLocked()) {
                MutationAdmission.Granted -> Unit
                is MutationAdmission.Rejected -> {
                    logcat(LogPriority.WARN) {
                        "TachiyomiAT store rekeyPages rejected: " +
                            "code=${admission.code} reason=${admission.message}"
                    }
                    return@withLock emptyList()
                }
            }
            if (pages.size != onlineKeys.size) return@withLock emptyList()
            val onDiskKeySet = onDiskKeys.toSet()
            if (pages.keys.all { it in onDiskKeySet }) return@withLock emptyList()

            val moves = pages.keys.mapNotNull { oldKey ->
                val index = onlineKeys.indexOf(oldKey)
                if (index < 0) return@mapNotNull null
                val newKey = onDiskKeys[index]
                if (newKey == oldKey || pages.containsKey(newKey)) return@mapNotNull null
                oldKey to newKey
            }
            if (moves.isEmpty()) return@withLock emptyList()

            val moveByOldKey = moves.toMap()
            var updatedPages = persistentMapOf<String, PageTranslation>()
            var updatedCommitted = persistentMapOf<String, CommittedPageDisplay>()
            pages.forEach { (oldKey, page) ->
                val newKey = moveByOldKey[oldKey] ?: oldKey
                val updated = if (newKey == oldKey) {
                    page
                } else {
                    page.detachedCopy().apply { sourceFileName = newKey }
                }
                updatedPages = updatedPages.put(newKey, ownedPage(newKey, updated))
                committedDisplay[oldKey]?.let { committed ->
                    updatedCommitted = updatedCommitted.put(newKey, committed)
                }
                retiredCleanedImages.remove(oldKey)?.let { retired ->
                    retiredCleanedImages[newKey] = retired
                }
            }
            pages = updatedPages
            committedDisplay = updatedCommitted
            rekeyArtifactPagesLocked(moveByOldKey)
            retiredCleanedImages.keys.toList().forEach { pageKey ->
                if (pageKey !in updatedPages) retiredCleanedImages.remove(pageKey)
            }
            dirty = true
            schedulePersist()
            _state.value = snapshotPages()
            _display.value = displaySnapshotLocked()
            moves
        }
    }

    /**
     * T911 slice 3: outcome of [preRegisterPages]. A rejection is observable so
     * the caller can fail the batch explicitly instead of running a tracker
     * whose totals silently stay zero.
     */
    sealed class PagePreRegistration {
        data object Accepted : PagePreRegistration()

        /** [reason] names why registration was refused (defunct store, artifact authority, ...). */
        data class Rejected(val reason: String) : PagePreRegistration()
    }

    /**
     * Registers the full ordered page set for a chapter before OCR starts.
     *
     * These placeholders are intentionally memory-only: they let progress UI show
     * the chapter total immediately, while the expected-page count is captured
     * in the artifact manifest when an artifact parent is already available.
     *
     * T911 slice 3: returns [PagePreRegistration.Rejected] instead of silently
     * returning Unit when the store refuses the registration (defunct store,
     * artifact-authority failure), so the caller can surface a typed terminal
     * error rather than a live zero tracker.
     */
    suspend fun preRegisterPages(pageKeys: List<String>): PagePreRegistration {
        if (pageKeys.isEmpty()) return PagePreRegistration.Accepted
        if (defunct) {
            logcat(LogPriority.WARN) {
                "TachiyomiAT store preRegisterPages rejected: store is defunct"
            }
            return PagePreRegistration.Rejected("store is defunct")
        }
        mutex.withLock {
            when (val admission = admitMutationLocked()) {
                MutationAdmission.Granted -> Unit
                is MutationAdmission.Rejected -> {
                    logcat(LogPriority.WARN) {
                        "TachiyomiAT store preRegisterPages rejected: " +
                            "code=${admission.code} reason=${admission.message}"
                    }
                    return PagePreRegistration.Rejected(admission.message)
                }
            }
            pendingExpectedPageCount = maxOf(pendingExpectedPageCount ?: 0, pageKeys.distinct().size)
            pendingExpectedPageCountTrusted = true
            var changed = false
            pageKeys.forEach { pageKey ->
                if (!pages.containsKey(pageKey)) {
                    pages = pages.put(pageKey, ownedPage(pageKey, PageTranslation(sourceFileName = pageKey)))
                    pendingArtifactPageRegistrations += pageKey
                    changed = true
                }
            }
            if (changed) {
                _state.value = snapshotPages()
            }
            if (artifactStore == null && artifactParent != null && artifactFileName != null) {
                ensureArtifactStoreLocked()
            }
            val store = artifactStore
            val manifest = artifactManifest
            val expected = pendingExpectedPageCount
            val expectedTrusted = manifest?.expectedPageCountTrusted == true || pendingExpectedPageCountTrusted
            if (store != null && manifest != null && expected != null) {
                val updated = manifest.copy(
                    expectedPageCount = maxOf(expected, manifest.expectedPageCount ?: 0),
                    expectedPageCountTrusted = expectedTrusted,
                    updatedAtEpochMs = System.currentTimeMillis(),
                )
                if (
                    updated.expectedPageCount == manifest.expectedPageCount &&
                    updated.expectedPageCountTrusted == manifest.expectedPageCountTrusted
                ) {
                    pendingExpectedPageCount = null
                    pendingExpectedPageCountTrusted = false
                } else if (store.publishManifest(updated)) {
                    artifactManifest = updated
                    pendingExpectedPageCount = null
                    pendingExpectedPageCountTrusted = false
                }
            }
        }
        return PagePreRegistration.Accepted
    }

    /**
     * Clears queue-like entries after a user cancel/auto-window replacement.
     * Pages with a rendered/cleaned result are kept; transient running, pending,
     * cancelled, and failed entries without output are reset so the reader queue
     * reflects only the next active window.
     */
    suspend fun clearTransientQueuePages(reason: String? = null) {
        if (defunct) {
            logcat(LogPriority.WARN) {
                "TachiyomiAT store clearTransientQueuePages rejected: store is defunct"
            }
            return
        }
        mutex.withLock {
            when (val admission = admitMutationLocked()) {
                MutationAdmission.Granted -> Unit
                is MutationAdmission.Rejected -> {
                    logcat(LogPriority.WARN) {
                        "TachiyomiAT store clearTransientQueuePages rejected: " +
                            "code=${admission.code} reason=${admission.message}"
                    }
                    return@withLock
                }
            }
            generation++
            logcat(LogPriority.INFO) {
                "TachiyomiAT store generation invalidated: generation=$generation reason=${reason ?: "clear transient queue"}"
            }
            var changed = false
            val now = System.currentTimeMillis()
            pages = pages.mapValues { (_, page) ->
                if (page.hasRenderedResult || !page.isQueueVisibleTransient()) {
                    page
                } else {
                    changed = true
                    ownedPage(
                        page.sourceFileName.orEmpty(),
                        page.copy(
                            ocrStatus = page.ocrStatus.cancelIfTransient(),
                            translationStatus = page.translationStatus.cancelIfTransient(),
                            inpaintStatus = page.inpaintStatus.cancelIfTransient(),
                            renderStatus = page.renderStatus.cancelIfTransient(),
                            updatedAt = now,
                        ).also {
                            it.ocrError = reason
                            it.translationError = reason
                            it.inpaintError = reason
                            it.renderError = reason
                        },
                    )
                }
            }.toPersistentMap()
            if (changed) {
                // Cancel semantics: cancelled pages release their writer
                // leases; committed display bundles survive untouched.
                synchronized(pageLeases) {
                    pageLeases.keys.retainAll { pageKey ->
                        pages[pageKey]?.hasRenderedResult ?: false
                    }
                }
                pages.keys.forEach { pageKey ->
                    cancelArtifactCandidateLocked(pageKey)
                }
                dirty = !persistLocked()
                _state.value = snapshotPages()
                _display.value = displaySnapshotLocked()
            }
        }
    }

    private fun nextVersion(): Long = ++nextPageVersion

    private fun ownedPage(pageKey: String, candidate: PageTranslation): PageTranslation =
        candidate.detachedCopy().apply {
            sourceFileName = sourceFileName ?: pageKey
            updatedAt = System.currentTimeMillis()
            runGeneration = generation
            pageVersion = nextVersion()
        }

    private fun publishLocked(
        previous: PageTranslation?,
        updated: PageTranslation,
        expected: PatchPrecondition? = null,
        durableFailure: DurableFailureMetadata? = null,
    ): Boolean {
        val pageKey = updated.sourceFileName ?: ""
        val isDurable = shouldPersistUpdate(previous, updated)
        val artifactAccepted = if (isDurable || artifactManifest?.pages?.containsKey(pageKey) != true) {
            persistArtifactMutationLocked(pageKey, previous, updated, expected, durableFailure)
        } else {
            true
        }
        if (!artifactAccepted) return false
        promoteDisplayIfReadyLocked(pageKey, updated)
        if (isDurable && artifactManifest?.authority != ManifestAuthority.ARTIFACTS) {
            schedulePersist()
        }
        _state.value = snapshotPages()
        _display.value = displaySnapshotLocked()
        return true
    }

    private fun restorePageLocked(pageKey: String, previous: PageTranslation?) {
        pages = if (previous == null) pages.remove(pageKey) else pages.put(pageKey, previous)
        _state.value = snapshotPages()
        _display.value = displaySnapshotLocked()
    }

    /**
     * Production writer bridge for the Phase 3 artifact authority. Every
     * mutable page emission is first written to a candidate snapshot. A
     * display-ready emission writes the immutable committed snapshot and moves
     * the manifest pointer in one final publication. The legacy flat file is
     * never rewritten once authority has cut over.
     */
    private fun persistArtifactMutationLocked(
        pageKey: String,
        previous: PageTranslation?,
        updated: PageTranslation,
        expected: PatchPrecondition? = null,
        durableFailure: DurableFailureMetadata? = null,
    ): Boolean {
        if (artifactStore == null) {
            if (artifactParent == null && artifactFileName == null && translationFile == null && fileCreator == null) {
                // Pure in-memory stores have no persistence target and never
                // create a compatibility document as a side effect.
                return true
            }
            if (!ensureArtifactStoreLocked()) return false
        }
        val store = artifactStore ?: return false
        var manifest = artifactManifest ?: return true
        if (pageKey.isEmpty()) return true
        val firstReaderBaseline = if (
            manifest.expectedPageCount == null && pendingExpectedPageCount == null
        ) {
            pages.size
        } else {
            0
        }
        val expectedPageCount = maxOf(
            manifest.expectedPageCount ?: 0,
            pendingExpectedPageCount ?: 0,
            firstReaderBaseline,
        ).takeIf { it > 0 }
        val expectedPageCountTrusted = manifest.expectedPageCountTrusted || pendingExpectedPageCountTrusted
        val registrationKeys = (pendingArtifactPageRegistrations + pageKey)
            .filter { it.isNotEmpty() && it !in manifest.pages }
        val expectedCountChanged = expectedPageCount != manifest.expectedPageCount ||
            expectedPageCountTrusted != manifest.expectedPageCountTrusted
        if (registrationKeys.isNotEmpty() || expectedCountChanged) {
            val added = manifest.copy(
                pages = manifest.pages + registrationKeys.associateWith {
                    eu.kanade.translation.artifact.PageArtifactRecord(pageKey = it)
                },
                expectedPageCount = expectedPageCount,
                expectedPageCountTrusted = expectedPageCountTrusted,
                updatedAtEpochMs = System.currentTimeMillis(),
            )
            if (!store.publishManifest(added)) {
                logcat(LogPriority.WARN) {
                    "TachiyomiAT artifact page registration failed: pageKey=$pageKey count=${registrationKeys.size}"
                }
                return false
            }
            manifest = added
            artifactManifest = manifest
            pendingArtifactPageRegistrations.removeAll(registrationKeys.toSet())
            if (expectedCountChanged) {
                pendingExpectedPageCount = null
                pendingExpectedPageCountTrusted = false
            }
        }
        val record = manifest.pages.getValue(pageKey)
        if (expected != null) {
            if (expected.artifactPageVersion != null && expected.artifactPageVersion != record.pageVersion) {
                logcat(LogPriority.WARN) {
                    "TachiyomiAT artifact candidate write rejected: pageKey=$pageKey reason=artifact pageVersion changed"
                }
                return false
            }
            if (expected.candidateGenerationId != null && expected.candidateGenerationId != record.candidate?.generationId) {
                logcat(LogPriority.WARN) {
                    "TachiyomiAT artifact candidate write rejected: pageKey=$pageKey reason=candidate generation changed"
                }
                return false
            }
            if (expected.dependencyFingerprint != null &&
                expected.dependencyFingerprint != record.candidate?.dependencyFingerprint &&
                record.candidate != null
            ) {
                logcat(LogPriority.WARN) {
                    "TachiyomiAT artifact candidate write rejected: pageKey=$pageKey reason=dependency fingerprint changed"
                }
                return false
            }
        } else if (manifest.authority == ManifestAuthority.ARTIFACTS && pageLeases[pageKey] != null) {
            logcat(LogPriority.WARN) {
                "TachiyomiAT artifact candidate write rejected: pageKey=$pageKey reason=unfenced active lease"
            }
            return false
        }
        // Compatibility writers may resume an on-disk candidate after process
        // death before a new page lease is attached. Preserve the durable
        // candidate provenance in that narrow no-lease window; otherwise a
        // READER_ADHOC candidate would be misclassified as BATCH and its next
        // snapshot could never be persisted.
        val origin = pageLeases[pageKey]?.origin?.toArtifactOrigin()
            ?: record.candidate?.origin
            ?: ArtifactOrigin.BATCH
        val candidate = record.candidate
        val dependencyFingerprint = expected?.dependencyFingerprint
            ?: candidate?.dependencyFingerprint
            ?: StageFingerprints.pageSnapshot(previous ?: updated)
        if (candidate == null || candidate.origin != origin) {
            val baseManifest = if (candidate != null && candidate.origin != origin) {
                when (val cancelled = store.cancelLiveCandidate(manifest, pageKey, candidate.generationId)) {
                    is ChapterArtifactStore.TransactionOutcome.Committed -> cancelled.manifest
                    else -> manifest
                }
            } else {
                manifest
            }
            val opened = store.openCandidate(
                manifest = baseManifest,
                pageKey = pageKey,
                origin = origin,
                expectedPageVersion = baseManifest.pages[pageKey]?.pageVersion ?: record.pageVersion,
                dependencyFingerprint = dependencyFingerprint,
            )
            manifest = when (opened) {
                is ChapterArtifactStore.TransactionOutcome.Committed -> opened.manifest
                is ChapterArtifactStore.TransactionOutcome.Rejected -> {
                    logcat(LogPriority.WARN) {
                        "TachiyomiAT artifact candidate open rejected: pageKey=$pageKey reason=${opened.reason}"
                    }
                    return false
                }
            }
            artifactManifest = manifest
        }
        val currentCandidate = manifest.pages.getValue(pageKey).candidate ?: return false
        val expectedPageVersion = manifest.pages.getValue(pageKey).pageVersion
        val persisted = if (durableFailure != null) {
            store.persistLiveCandidateAndFailure(
                manifest = manifest,
                pageKey = pageKey,
                generationId = currentCandidate.generationId,
                expectedPageVersion = expectedPageVersion,
                expectedDependencyFingerprint = currentCandidate.dependencyFingerprint.orEmpty(),
                pageSnapshot = updated,
                origin = origin,
                failure = durableFailure,
                sourceIdentity = updated.sourceIdentity(pageKey),
            )
        } else {
            store.persistLiveCandidate(
                manifest = manifest,
                pageKey = pageKey,
                generationId = currentCandidate.generationId,
                expectedPageVersion = expectedPageVersion,
                expectedDependencyFingerprint = currentCandidate.dependencyFingerprint.orEmpty(),
                pageSnapshot = updated,
                origin = origin,
                sourceIdentity = updated.sourceIdentity(pageKey),
            )
        }
        manifest = when (persisted) {
            is ChapterArtifactStore.TransactionOutcome.Committed -> persisted.manifest
            is ChapterArtifactStore.TransactionOutcome.Rejected -> {
                logcat(LogPriority.WARN) {
                    "TachiyomiAT artifact candidate persist rejected: pageKey=$pageKey reason=${persisted.reason}"
                }
                return false
            }
        }
        artifactManifest = manifest
        if (durableFailure == null && (updated.hasRenderedResult || updated.isTextlessTerminal)) {
            val promoted = store.promoteLiveCandidate(
                manifest = manifest,
                pageKey = pageKey,
                generationId = currentCandidate.generationId,
                expectedPageVersion = manifest.pages.getValue(pageKey).pageVersion,
                expectedDependencyFingerprint = currentCandidate.dependencyFingerprint.orEmpty(),
                pageSnapshot = updated,
                origin = origin,
                sourceIdentity = updated.sourceIdentity(pageKey),
            )
            manifest = when (promoted) {
                is ChapterArtifactStore.TransactionOutcome.Committed -> promoted.manifest
                is ChapterArtifactStore.TransactionOutcome.Rejected -> {
                    logcat(LogPriority.WARN) {
                        "TachiyomiAT artifact candidate promotion rejected: pageKey=$pageKey reason=${promoted.reason}"
                    }
                    return false
                }
            }
        }
        artifactManifest = manifest
        return true
    }

    /** Lazily creates the artifact authority for a chapter with no legacy file. */
    private fun ensureArtifactStoreLocked(): Boolean {
        artifactStore?.let { return artifactManifest != null }
        // Lazy production stores must provide an artifact parent/name. An
        // existing flat file is migrated by openInternal; this method never
        // creates one as a mutation side effect.
        val parent = artifactParent ?: translationFile?.parentFile ?: return false
        val fileName = artifactFileName ?: translationFile?.name ?: return false
        val layout = ChapterArtifactLayout.fromTranslationFileName(fileName)
        val documents = AtomicChapterDocuments(UniFileChapterDocumentIo(parent))
        val store = ChapterArtifactStore(documents, layout, artifactImageProbe)
        var manifest = synchronized(artifactMigrationLock(parent, fileName)) {
            store.loadOrMigrate(
                LegacyChapterSnapshot(
                    pages = emptyMap(),
                    glossary = emptyMap(),
                    translationFileCorrupt = false,
                    legacyIdentity = null,
                    migratedAtEpochMs = System.currentTimeMillis(),
                ),
            ).manifest
        }
        // A new artifact-backed chapter has no legacy source to rescue. The
        // first mutation still needs an ARTIFACTS authority manifest, but must
        // not manufacture a compatibility flat file as an admission side
        // effect.
        if (
            manifest.authority == ManifestAuthority.LEGACY &&
            manifest.legacyMigration == null &&
            manifest.pages.isEmpty()
        ) {
            val now = System.currentTimeMillis()
            val artifactManifest = manifest.copy(
                authority = ManifestAuthority.ARTIFACTS,
                cutoverAtEpochMs = manifest.cutoverAtEpochMs ?: now,
                migratedFromLegacyAtEpochMs = manifest.migratedFromLegacyAtEpochMs ?: now,
                updatedAtEpochMs = now,
            )
            if (store.publishManifest(artifactManifest)) {
                manifest = artifactManifest
            }
        }
        artifactStore = store
        artifactManifest = manifest
        return true
    }

    // T917 D1: the lease-origin -> durable-provenance mapping moved to the
    // shared top-level `PageWriteOrigin?.toArtifactOrigin()` in
    // TranslationStageContracts.kt (same two-value ArtifactOrigin result).

    private fun PageTranslation.sourceIdentity(pageKey: String): SourceIdentity? =
        sourceFingerprint?.let { fingerprint ->
            SourceIdentity(
                pageKey = pageKey,
                sha256 = fingerprint,
                width = imgWidth.takeIf { it > 0f }?.toInt(),
                height = imgHeight.takeIf { it > 0f }?.toInt(),
            )
        }

    /**
     * Atomic committed-display promotion: when the page just reached a fully
     * displayable state (cleaned image file published and verified upstream by
     * [CleanedImagePublisher], translation READY/PARTIAL, render READY, some
     * translated text), the committed pointer swaps to a frozen snapshot of
     * exactly that state. A superseded bundle's cleaned file is retained until
     * the pipeline drains it, never deleted under a live committed pointer.
     */
    private fun promoteDisplayIfReadyLocked(pageKey: String, updated: PageTranslation) {
        if (pageKey.isEmpty() || (!updated.hasRenderedResult && !updated.isTextlessTerminal)) return
        val fingerprint = displayFingerprintOf(updated)
        val existing = committedDisplay[pageKey]
        if (existing != null && existing.displayFingerprint == fingerprint) return
        if (existing != null) {
            existing.page.cleanedImageName
                ?.takeIf { it != updated.cleanedImageName && it.isNotEmpty() }
                ?.let {
                    retiredCleanedImages
                        .computeIfAbsent(pageKey) { ConcurrentHashMap.newKeySet() }
                        .add(it)
                }
        }
        committedDisplay = committedDisplay.put(
            pageKey,
            CommittedPageDisplay(
                page = updated.detachedCopy(),
                pageVersion = updated.pageVersion,
                displayFingerprint = fingerprint,
                promotedAtEpochMs = updated.updatedAt,
            ),
        )
    }

    private fun displaySnapshotLocked(): Map<String, PageTranslation> {
        if (committedDisplay.isEmpty()) return _state.value
        val live = snapshotPages()
        return buildMap {
            live.forEach { (pageKey, page) ->
                put(pageKey, committedDisplay[pageKey]?.page?.detachedCopy() ?: page)
            }
        }
    }

    private fun displayFingerprintOf(page: PageTranslation): String {
        val canonical = buildString {
            appendField(page.cleanedImageName)
            appendField(page.inpaintRevision)
            page.blockFingerprints().forEach { fingerprint -> appendField(fingerprint) }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun StringBuilder.appendField(value: Any?) {
        val text = value?.toString() ?: "<null>"
        append(text.length).append(':').append(text).append('|')
    }

    /** Resolves the reader-facing page state: committed bundle first, live candidate otherwise. */
    fun resolveDisplayPage(pageKey: String): PageTranslation? =
        committedDisplay[pageKey]?.page?.detachedCopy() ?: pages[pageKey]?.detachedCopy()

    /** The frozen committed display bundle for [pageKey], if one exists. */
    fun committedDisplayPage(pageKey: String): PageTranslation? =
        committedDisplay[pageKey]?.page?.detachedCopy()

    /**
     * True while [name] still backs the committed display bundle (or its
     * retained predecessor) for [pageKey]; such files must not be deleted
     * before a newer bundle promotes.
     */
    fun mayDeleteCleanedImage(pageKey: String, name: String): Boolean =
        committedDisplay[pageKey]?.page?.cleanedImageName != name &&
            name !in (retiredCleanedImages[pageKey] ?: emptySet())

    /** All cleaned-image names still reachable from live, committed, or retired state. */
    fun referencedCleanedImageNames(): Set<String> = buildSet {
        pages.values.mapNotNullTo(this) { it.cleanedImageName }
        committedDisplay.values.mapNotNullTo(this) { it.page.cleanedImageName }
        retiredCleanedImages.values.forEach { addAll(it) }
        artifactManifest?.pages?.values?.forEach { record ->
            record.committed?.displayBase?.fileName?.let(::add)
            record.previousCommitted?.displayBase?.fileName?.let(::add)
        }
    }

    /**
     * Returns and forgets one cleaned-image name retained for [pageKey] after
     * a newer bundle promoted; retained names are drained as a set so rapid
     * promotions cannot orphan an earlier superseded file.
     */
    fun drainRetiredCleanedImage(pageKey: String): String? =
        retiredCleanedImages[pageKey]?.let { retired ->
            val name = retired.firstOrNull() ?: return@let null
            retired.remove(name)
            if (retired.isEmpty()) retiredCleanedImages.remove(pageKey, retired)
            name
        }

    /** Atomically takes every superseded cleaned-image name for [pageKey]. */
    fun drainRetiredCleanedImages(pageKey: String): List<String> =
        retiredCleanedImages.remove(pageKey)?.toList().orEmpty()

    /**
     * Explicitly drops the committed display pointer — reserved for user
     * resets (reset translation/inpaint data), never for candidate retries:
     * pause/cancel/failure keep the committed bundle visible.
     */
    suspend fun demoteCommittedDisplay(pageKey: String, reason: String) {
        if (defunct) return
        mutex.withLock {
            if (committedDisplay.containsKey(pageKey)) {
                committedDisplay = committedDisplay.remove(pageKey)
                retiredCleanedImages.remove(pageKey)
                demoteArtifactPageLocked(pageKey)
                logcat(LogPriority.INFO) {
                    "TachiyomiAT committed display demoted: pageKey=$pageKey reason=$reason"
                }
                _display.value = displaySnapshotLocked()
            }
        }
    }

    internal fun cancelArtifactCandidateLocked(pageKey: String): Boolean {
        val store = artifactStore ?: return true
        val manifest = artifactManifest ?: return true
        val candidate = manifest.pages[pageKey]?.candidate ?: return true
        val outcome = store.cancelLiveCandidate(manifest, pageKey, candidate.generationId)
        if (outcome is ChapterArtifactStore.TransactionOutcome.Committed) {
            artifactManifest = outcome.manifest
            return true
        } else if (outcome is ChapterArtifactStore.TransactionOutcome.Rejected) {
            logcat(LogPriority.WARN) {
                "TachiyomiAT artifact candidate cancel rejected: pageKey=$pageKey reason=${outcome.reason}"
            }
        }
        return false
    }

    private fun demoteArtifactPageLocked(pageKey: String) {
        val store = artifactStore ?: return
        val manifest = artifactManifest ?: return
        val outcome = store.demoteLivePage(manifest, pageKey)
        when (outcome) {
            is ChapterArtifactStore.TransactionOutcome.Committed -> artifactManifest = outcome.manifest
            is ChapterArtifactStore.TransactionOutcome.Rejected -> logcat(LogPriority.WARN) {
                "TachiyomiAT artifact display demotion rejected: pageKey=$pageKey reason=${outcome.reason}"
            }
        }
    }

    private fun deleteArtifactPageLocked(pageKey: String) {
        val store = artifactStore ?: return
        val manifest = artifactManifest ?: return
        val outcome = store.deleteLivePage(manifest, pageKey)
        when (outcome) {
            is ChapterArtifactStore.TransactionOutcome.Committed -> artifactManifest = outcome.manifest
            is ChapterArtifactStore.TransactionOutcome.Rejected -> logcat(LogPriority.WARN) {
                "TachiyomiAT artifact page deletion rejected: pageKey=$pageKey reason=${outcome.reason}"
            }
        }
    }

    private fun rekeyArtifactPagesLocked(moveByOldKey: Map<String, String>) {
        val store = artifactStore ?: return
        val manifest = artifactManifest ?: return
        val updatedPages = manifest.pages.entries.associate { (oldKey, record) ->
            (moveByOldKey[oldKey] ?: oldKey) to record.copy(
                pageKey = moveByOldKey[oldKey] ?: oldKey,
                pageVersion = record.pageVersion + if (oldKey in moveByOldKey) 1L else 0L,
            )
        }
        val updatedFailures = manifest.durableFailures.entries.associate { (_, failure) ->
            val newPageKey = moveByOldKey[failure.pageKey] ?: failure.pageKey
            "$newPageKey:${failure.stage.name}" to failure.copy(pageKey = newPageKey)
        }
        if (updatedPages == manifest.pages && updatedFailures == manifest.durableFailures) return
        val updated = manifest.copy(
            pages = updatedPages,
            durableFailures = updatedFailures,
            updatedAtEpochMs = System.currentTimeMillis(),
        )
        if (store.publishManifest(updated)) {
            artifactManifest = updated
        } else {
            logcat(LogPriority.WARN) { "TachiyomiAT artifact page re-key publication failed" }
        }
    }

    internal fun snapshotLocked(pageKey: String): PageSnapshot {
        val page = pages[pageKey]
        val artifactPage = artifactManifest?.pages?.get(pageKey)
        return PageSnapshot(
            page = page?.detachedCopy(),
            generation = generation,
            pageVersion = page?.pageVersion ?: 0L,
            blockFingerprints = page?.blockFingerprints().orEmpty(),
            leaseToken = pageLeases[pageKey]?.token,
            artifactPageVersion = artifactPage?.pageVersion,
            candidateGenerationId = artifactPage?.candidate?.generationId,
            dependencyFingerprint = artifactPage?.candidate?.dependencyFingerprint
                ?: page?.let(StageFingerprints::pageSnapshot),
        )
    }

    private fun PageSnapshot.toPrecondition() = PatchPrecondition(
        generation = generation,
        pageVersion = pageVersion,
        blockFingerprints = blockFingerprints,
        leaseToken = leaseToken,
        candidateGenerationId = candidateGenerationId,
        dependencyFingerprint = dependencyFingerprint,
        artifactPageVersion = artifactPageVersion,
    )

    private fun rejected(pageKey: String, description: String, reason: String): PatchResult.Rejected {
        logcat(LogPriority.WARN) {
            "TachiyomiAT store patch rejected: pageKey=$pageKey generation=$generation " +
                "operation=$description reason=$reason"
        }
        return PatchResult.Rejected(reason)
    }

    private fun snapshotPages(): Map<String, PageTranslation> =
        pages.entries.associate { (key, page) -> key to page.detachedCopy() }

    fun glossarySnapshot(): Map<String, String> = glossaryStore.glossarySnapshot()

    // T909 Phase 15: body moved to store/StoreStatusProjector.kt.
    // Same-signature stub keeps the call sites.
    fun artifactStatus(): Translation.State? = statusProjector.artifactStatus()

    fun translatedPairs(): List<Pair<String, String>> = glossaryStore.translatedPairs()

    suspend fun updateGlossary(updated: Map<String, String>) {
        glossaryStore.updateGlossary(updated)
    }

    /**
     * TachiyomiAT T917 D5: live glossary version for the reuse gate and
     * provenance stamps; `null` = gate off (legacy authority / no glossary
     * ever published). Delegates to [ChapterGlossaryStore]; a pure in-memory
     * read, safe under the store mutex (the batch provenance stamp consumes it
     * inside the guarded patch lambda).
     */
    internal fun currentGlossaryVersion(): Int? = glossaryStore.currentGlossaryVersion()

    internal fun loadGlossary() {
        glossaryStore.loadGlossary()
    }

    // T909 Phase 17b: internal — the moved StorePersistenceScheduler reaches
    // it from flushDirtyLocked.
    internal fun persistGlossaryLocked(): Boolean = glossaryStore.persistGlossaryLocked()

    // T909 Phase 8: kept store-side (flat-file plumbing over ctor state); the
    // moved loadGlossary reads them through these internal accessors.
    internal fun legacyDocuments(): AtomicChapterDocuments? =
        (translationFile?.parentFile ?: artifactParent)?.let(::UniFileChapterDocumentIo)?.let(::AtomicChapterDocuments)

    internal fun glossaryName(): String =
        LegacyChapterMigrationSource.legacyGlossaryName(translationFile?.name ?: artifactFileName ?: "translation")

    private fun PageTranslation.isQueueVisibleTransient(): Boolean {
        return ocrStatus.isQueueTransient() ||
            translationStatus.isQueueTransient() ||
            inpaintStatus.isQueueTransient() ||
            renderStatus.isQueueTransient()
    }

    private fun String.isQueueTransient(): Boolean {
        return this == StageStatus.PENDING ||
            this == StageStatus.RUNNING ||
            this == StageStatus.FAILED ||
            this == StageStatus.CANCELLED
    }

    private fun String.cancelIfTransient(): String {
        return if (isQueueTransient()) StageStatus.CANCELLED else this
    }

    internal fun shouldPersistUpdate(previous: PageTranslation?, updated: PageTranslation): Boolean {
        // Persist only DURABLE progress worth surviving a chapter reopen, not
        // transient placeholders; transient bookkeeping stays in-memory (the
        // reader observes the live StateFlow). Critical because the stranded-page
        // sweep (ReaderViewModel.sweepStrandedPageStatus) writes exactly the
        // placeholder shape — CANCELLED stages + errorMessage, no blocks, no
        // image — via updatePage. The old logic (errorMessage != null -> persist,
        // and `!isStageRunning` -> persist) saved those placeholders to JSON,
        // where under the old pages.isNotEmpty() check a single such entry made
        // the chapter falsely read TRANSLATED forever. clearTransientQueuePages()
        // bypasses this gate via persistLocked(), so legitimate cancels still hit disk.
        // 1. A final rendered image (actual translation output).
        if (updated.hasRenderedResult) return true
        // 2. Recognized text blocks (real OCR work).
        if (updated.blocks.isNotEmpty()) return true
        // 3. Cleaned image; lets a later render resume without redoing the neural pass.
        if (updated.cleanedImageName != null) return true
        // 4. Stage failures so retry-exhaustion (hasExhaustedRetries) survives
        //    reopen and the scheduler can skip permanently-failing pages.
        if (updated.isStageFailed) return true
        // 5. Transition away from a previously-rendered result (e.g. forced retry
        //    cleared the image) so the reader stops showing the stale one.
        if (previous?.hasRenderedResult == true && !updated.hasRenderedResult) return true
        // Transient placeholder (RUNNING/PENDING/CANCELLED with no content and no
        // failure): keep it in memory only.
        return false
    }

    // T909 Phase 17b: flush/close/debounce/retention bodies moved to
    // store/StorePersistenceScheduler.kt; these same-signature stubs keep the
    // old qualified names (the manager reset flows, the durable resolver's
    // probe release, the glossary delegate, and the persistence/defunct tests
    // resolve them here). markDefunct's bounded persist-join keeps its exact
    // semantics and reads the join timeout through the same-name companion
    // delegating val below.

    private fun persistLocked(): Boolean = persistenceScheduler.persistLocked()

    suspend fun flush() = persistenceScheduler.flush()

    suspend fun closeAndFlush() = persistenceScheduler.closeAndFlush()

    fun close() = persistenceScheduler.close()

    /** Performs the bounded artifact-tree sweep at a serialized chapter boundary. */
    suspend fun reconcileArtifactRetention() = persistenceScheduler.reconcileArtifactRetention()

    internal fun schedulePersist(markPageDirty: Boolean = true) =
        persistenceScheduler.schedulePersist(markPageDirty)

    companion object {
        // T909 Phase 17b: the persistence constants moved to
        // StorePersistenceScheduler; this delegating val keeps markDefunct's
        // bounded persist-join read unchanged.
        private val PERSIST_JOIN_TIMEOUT_MS get() = StorePersistenceScheduler.PERSIST_JOIN_TIMEOUT_MS

        /** Fallback name for the rename target if [UniFile.getName] is null. */
        private const val DEFAULT_FILE_NAME = "translation.json"

        /**
         * The flat page map predates the artifact schema and may contain fields
         * removed by a later refactor. Unknown keys are additive compatibility
         * data here, so they must not make an otherwise valid page unreadable.
         */
        // T909 Phase 3b: the single legacy Json config lives in LegacyFlatFileDecoder;
        // the manifest probe lives in ChapterArtifactManifestReader.

        /** Reads only the small manifest header; page snapshots stay unopened. */
        internal fun probeArtifactManifest(translationFile: UniFile): ArtifactManifestProbe =
            ChapterArtifactManifestReader.probeArtifactManifest(translationFile)

        /** Reads only the manifest header when the legacy flat document is absent. */
        internal fun probeArtifactManifest(parent: UniFile, fileName: String): ArtifactManifestProbe =
            ChapterArtifactManifestReader.probeArtifactManifest(parent, fileName)

        /** Opens an existing on-disk translation file into a store. */
        fun open(translationFile: UniFile): ChapterTranslationStore =
            LegacyChapterMigrationSource.openInternal(
                translationFile = translationFile,
                parent = translationFile.parentFile,
                fileName = (translationFile.name ?: DEFAULT_FILE_NAME).removeSuffix(".migrated"),
            )

        /** Opens an artifact-authority chapter whose legacy flat file is absent. */
        internal fun openArtifact(parent: UniFile, fileName: String): ChapterTranslationStore =
            LegacyChapterMigrationSource.openInternal(translationFile = null, parent = parent, fileName = fileName)

        // T909 Phase 3b: the legacy open/migration machine moved to
        // artifact/LegacyChapterMigrationSource.kt; these seams stay for callers.
        private fun artifactMigrationLock(parent: UniFile?, fileName: String): Any =
            LegacyChapterMigrationSource.artifactMigrationLock(parent, fileName)

        /**
         * Test seam for the bounded cleaned-image probe. Production uses the
         * BitmapFactory bounds-only probe; JVM tests inject a header-parsing
         * fake because android.graphics is unavailable there.
         */
        @Volatile
        internal var artifactImageProbe: CleanedImageProbe = BitmapFactoryCleanedImageProbe

        /**
         * Creates a store whose on-disk file is created lazily on the first
         * artifact-backed [updatePage]/[replaceAll] write. The optional
         * [fileCreator] remains source-compatible but is never invoked.
         */
        fun lazy(
            artifactParent: UniFile? = null,
            artifactFileName: String? = null,
            fileCreator: (() -> UniFile)? = null,
        ): ChapterTranslationStore =
            ChapterTranslationStore(
                translationFile = null,
                fileCreator = fileCreator,
                initialPages = emptyMap(),
                artifactParent = artifactParent,
                artifactFileName = artifactFileName,
            )
    }
}
