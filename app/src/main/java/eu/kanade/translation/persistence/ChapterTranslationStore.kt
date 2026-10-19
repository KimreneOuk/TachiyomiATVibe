package eu.kanade.translation.persistence


import com.hippo.unifile.UniFile
import eu.kanade.translation.model.PageStage
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.PageTranslationView
import eu.kanade.translation.model.PublishedPageTranslation
import eu.kanade.translation.model.StageFingerprints
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.model.blockFingerprints
import eu.kanade.translation.model.cancelInFlightStages
import eu.kanade.translation.model.detachedCopy
import eu.kanade.translation.model.hasRenderedResult
import eu.kanade.translation.model.isCleanedImageReady
import eu.kanade.translation.model.isStageFailed
import eu.kanade.translation.model.isStageRunning
import eu.kanade.translation.model.isTextlessTerminal
import eu.kanade.translation.model.stableFingerprint
import eu.kanade.translation.model.stampRenderTerminalIfDisplayComplete
import eu.kanade.translation.model.toDraft
import eu.kanade.translation.model.toPublishedPage
import eu.kanade.translation.pipeline.batch.formatWriteDiagnostic
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat



/** Bounded scalar record of an attempt's own verified artifact drain progress. */
sealed interface CheckpointOcrResult {
    data class Committed(
        val snapshot: ChapterTranslationStore.PageSnapshot,
        val manifest: ChapterArtifactManifest,
        val ocrContentFingerprint: String? = null,
    ) : CheckpointOcrResult

    data class Rejected(val reason: String) : CheckpointOcrResult
}

/** Durable admission result for a page mutation. */
sealed interface MutationAdmission {
    data object Granted : MutationAdmission

    data class Rejected(
        val code: Code,
        val retryable: Boolean = true,
        val message: String,
    ) : MutationAdmission {
        enum class Code {
            ARTIFACT_PUBLICATION_FAILED,
            STORE_DEFUNCT,
            FENCE_REJECTED,
        }
    }
}

/**
 * Journal collaborators (Phase 7 S5): the journal capture bridge owns credit
 * reservation, the capture frames, bulk capture frames, inventory
 * snapshots, and the capture permit; the lazy flusher owns the queued
 * lazy-mutation drain and its journal-barrier interleave. This facade keeps
 * delegating shells so existing callers and tests are unaffected.
 * The bulk mutator collaborator (Phase 7 S7) owns the multi-page
 * transactions and re-key planning; the store keeps delegating shells for
 * them as well.
 */

class ChapterTranslationStore internal constructor(
    initialPages: Map<String, PageTranslation> = emptyMap(),
    artifactStore: ChapterArtifactEngine? = null,
    initialCommittedPages: Map<String, PageTranslation> = emptyMap(),
    initialArtifactManifest: ChapterArtifactManifest? = null,
    initialRetiredCleanedImages: Map<String, Set<String>> = emptyMap(),
    internal val artifactParent: UniFile? = null,
    internal val artifactFileName: String? = null,
    internal val artifactParentResolver: (() -> UniFile)? = null,
    internal val persistenceDispatcher: CoroutineDispatcher = Dispatchers.IO,
    internal val privateStorageRoot: File? = null,
    private val privateStorageIdentity: String? = null,
) {
    /** Explicit memory, lazy-durable, or opened-durable storage mode. */
    internal var engineMode: ChapterStoreEngineMode =
        artifactStore?.let { ChapterStoreEngineMode.Durable(it) }
            ?: if (artifactParentResolver != null || artifactParent != null || artifactFileName != null) {
                ChapterStoreEngineMode.LazyDurable(
                    artifactParent = artifactParent,
                    artifactFileName = artifactFileName,
                    artifactParentResolver = artifactParentResolver,
                )
            } else {
                ChapterStoreEngineMode.Memory
            }

    /** The durable engine when this mode has been opened; memory and lazy modes are explicit. */
    internal val artifactEngine: ChapterArtifactEngine?
        get() = (engineMode as? ChapterStoreEngineMode.Durable)?.artifact

    internal fun hasArtifactPersistenceTarget(): Boolean =
        artifactParent != null || artifactFileName != null || artifactParentResolver != null

    /**
     * Monotonic store-side lock hierarchy (Phase 7 S2). Every acquisition in a
     * write path moves DOWN this list; nothing ever acquires a lock higher in
     * the list while holding a lower one:
     *
     * 1. journal credit reservation (`reserveJournalCredit` — before [mutex]);
     * 2. producer capture permit (`withJournalCapturePermit`);
     * 3. the store [mutex] — suspending, so holders may await lower locks;
     * 4. the per-artifact open lock (`withArtifactOpenLock`) — acquired only
     *    from inside [mutex] via publication admission, as a coroutine.
     *
     * The open path (`openArtifactSuspend`) takes ONLY level 4 and never the
     * store mutex, so the order cannot invert and a first write never blocks a
     * carrier thread under the store mutex. `runBlocking` is banned anywhere
     * below level 3.
     */
    internal val mutex = Mutex()

    /**
     * Consolidated test-seam collaborator (Phase 7 S8). Declared before the
     * init block because [publishPage] counts every publish through it.
     */
    internal val testSeams = ChapterStoreTestSeams(this)

    private val candidateReuseObservers = CopyOnWriteArrayList<
        (ChapterCandidateTransactions.CandidateOpenState.Reused) -> Unit,
        >()

    /** Observe typed artifact reuse outcomes without coupling storage to batch diagnostics. */
    internal fun observeCandidateReuse(
        observer: (ChapterCandidateTransactions.CandidateOpenState.Reused) -> Unit,
    ): AutoCloseable {
        candidateReuseObservers.add(observer)
        return AutoCloseable { candidateReuseObservers.remove(observer) }
    }

    internal fun reportCandidateReuse(state: ChapterCandidateTransactions.CandidateOpenState?) {
        val reused = state as? ChapterCandidateTransactions.CandidateOpenState.Reused ?: return
        candidateReuseObservers.forEach { observer ->
            // Diagnostics must not change whether an artifact transaction is accepted.
            runCatching { observer(reused) }
        }
    }

    internal val chapterKey: String?
        get() = artifactEngine?.layout?.chapterKey ?: artifactFileName?.substringBeforeLast('.')

    /** Runs one external artifact transaction under the facade mutex. */
    internal suspend inline fun <T> withArtifactEngineLocked(
        crossinline block: (ChapterArtifactEngine) -> T,
    ): T? = mutex.withLock {
        artifactEngine?.let(block)
    }

    /** Read-only run-record projection used by progress/status consumers. */
    internal fun readActiveRunRecord(): ChapterRunRecord? {
        return readRunRecord(artifactManifest?.activeRun)
    }

    /** Reads the record addressed by one already-captured manifest pointer. */
    internal fun readRunRecord(pointer: SidecarPointer?): ChapterRunRecord? {
        val pointer = pointer?.takeIf { it.isWellFormed() } ?: return null
        val artifact = artifactEngine ?: return null
        return (artifact.progress.readRunRecord(pointer) as? ChapterProgressRecords.RunRecordRead.Usable)?.record
    }

    /** Read-only manifest projection for context/status consumers. */
    internal fun readArtifactManifest(): ChapterArtifactManifest? = artifactEngine?.readManifest()

    /** Read-only chapter key projection without exposing the engine. */
    internal fun artifactChapterKey(): String? = artifactEngine?.layout?.chapterKey

    @Volatile
    internal var pages: PersistentMap<String, PublishedPageTranslation> = persistentMapOf()
    internal val stateFlow = MutableStateFlow<PersistentMap<String, PublishedPageTranslation>>(persistentMapOf())

    /**
     * The last-known-good committed display bundle per
     * page. A candidate retry may mutate the live [pages] entry freely —
     * clearing statuses, wiping the cleaned name, stranding stages — but it
     * can never hide or mutate the committed bundle observed through
     * [display]. Promotion happens atomically inside the store mutex after the
     * page reached [PageTranslation.hasRenderedResult] shape, so observers
     * never see a half-promoted combination.
     */
    @Volatile
    internal var committedDisplay: PersistentMap<String, CommittedPageDisplay> = persistentMapOf()

    /** Durable artifact manifest snapshot used by the live writer bridge. */
    @Volatile
    internal var artifactManifest: ChapterArtifactManifest? = initialArtifactManifest

    @Volatile
    internal var pendingExpectedPageCount: Int? = null

    @Volatile
    internal var pendingExpectedPageCountTrusted: Boolean = false

    /**
     * Cleaned-image names retained because the superseded committed bundle
     * still displays them; drained for deletion by the pipeline after a newer
     * bundles promote. Keeping all names until a drain prevents a rapid sequence
     * of promotions from losing an earlier retained file.
     */
    internal val retiredCleanedImages = ConcurrentHashMap<String, MutableSet<String>>()
    internal val pendingArtifactPageRegistrations = LinkedHashSet<String>()

    internal val _display = MutableStateFlow<PersistentMap<String, PublishedPageTranslation>>(persistentMapOf())

    // Lease ownership stays in one table so the store and lease APIs share the
    // same map and synchronization boundary.
    private val pageStageLeaseTable = PageStageLeaseTable(this)

    /** Writer leases per page: one origin owns a page until it releases it. */
    internal val pageLeases get() = pageStageLeaseTable.pageLeases

    @Volatile
    internal var generation = 0L
    private var nextPageVersion = 0L

    /** Live candidate progress; readers that need display safety use [display]. */
    val state: StateFlow<Map<String, PageTranslationView>> = stateFlow.asStateFlow()

    /**
     * Committed-pointer display projection: each page resolves to
     * its immutable committed display bundle when one exists, otherwise the
     * live candidate entry. Candidate emissions can replace live entries but
     * never null or mutate a committed bundle.
     */
    val display: StateFlow<Map<String, PageTranslationView>> = _display.asStateFlow()

    val currentGeneration: Long get() = generation

    /**
     * Frozen snapshot of the last displayable page state, plus the identity
     * that lets the store decide when a promotion supersedes an older bundle.
     */
    data class CommittedPageDisplay(
        val page: PublishedPageTranslation,
        val pageVersion: Long,
        val displayFingerprint: String,
        val promotedAtEpochMs: Long,
    )

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
    ) {
        fun toPrecondition() = PatchPrecondition(
            generation = generation,
            pageVersion = pageVersion,
            blockFingerprints = blockFingerprints,
            leaseToken = leaseToken,
            candidateGenerationId = candidateGenerationId,
            dependencyFingerprint = dependencyFingerprint,
            artifactPageVersion = artifactPageVersion,
        )
    }

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
        data class Rejected(
            val reason: String,
            val detail: Detail? = null,
        ) : PatchResult {
            sealed interface Detail {
                data object BatchPageLeaseMissing : Detail

                data object ArtifactPublicationFailed : Detail

                data class PageVersionMismatch(
                    val expectedVersion: Long,
                    val actualVersion: Long,
                ) : Detail

                data class PageLeaseTokenMismatch(
                    val expectedToken: Long,
                    val actualToken: Long?,
                ) : Detail
            }
        }
    }

    /**
     * Result of the single bounded revalidation allowed after a batch
     * translation candidate was rejected only because its page version moved.
     */
    internal sealed interface TranslationPublicationReconciliation {
        data class AlreadyDurable(val snapshot: PageSnapshot) : TranslationPublicationReconciliation
        data class Rebased(val snapshot: PageSnapshot) : TranslationPublicationReconciliation
        data class Deferred(val reason: String) : TranslationPublicationReconciliation
        data class PublicationFailed(val reason: String) : TranslationPublicationReconciliation
    }

    internal class GenerationContext(
        val store: ChapterTranslationStore,
        val generation: Long,
    ) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<GenerationContext>
    }

    // Attempt records are written under the store mutex; persistence failures
    // fail open, and memory-only stores keep no durable ledger.
    private val attemptLedger = ChapterAttemptLedger(this)

    /**
     * Publication collaborator (Phase 7 S4). Owns mutation admission, lazy
     * artifact-store initialization, the registration CAS, and the candidate
     * open/cancel/persist/promote/failure orchestration. Module-internal so
     * the store and its in-module mutator collaborators call the publication
     * path directly under [mutex] — no facade shells in between.
     */
    internal val publicationBridge = ChapterStorePublicationBridge(this)

    // Lazy-write queue state is stored by the drain coordinator, but every
    // mutation is serialized by this store's mutex. Store-to-drain scheduling
    // is a synchronous handoff with no second state lock; drain paths re-enter
    // through mutex.withLock before reading or changing store state.
    internal val writeDrain = StoreWriteDrainCoordinator(this, persistenceDispatcher)

    /**
     * Bulk-mutation collaborator (Phase 7 S7). Owns [deletePage],
     * [replaceAll], [rekeyPages], [preRegisterPages],
     * [clearTransientQueuePages], and the two-phase re-key planning machinery;
     * the store delegates every public entry point here.
     */
    private val bulkMutator = ChapterBulkMutator(this)

    internal val displayPublication = ChapterDisplayPublication(this)

    internal fun displaySnapshotLocked(changedPageKeys: Iterable<String> = emptyList()) {
        displayPublication.displaySnapshotLocked(changedPageKeys)
    }

    internal fun promoteDisplayIfReadyLocked(pageKey: String, updated: PageTranslation) {
        displayPublication.promoteDisplayIfReadyLocked(pageKey, updated)
    }
    internal val pageMutations = ChapterPageMutations(this)
    internal val stageMerges = ChapterStageMerges(this)


    /**
     * Typed result of [rekeyPages]. [Moved] with [Moved.skippedCollisions] > 0 is
     * explicitly partial: the caller must not treat it as a complete handoff.
     */
    sealed class PageRekeyOutcome {
        data class Moved(
            val moves: List<Pair<String, String>>,
            val skippedCollisions: Int,
        ) : PageRekeyOutcome()

        data object Noop : PageRekeyOutcome()

        /** [reason] names why the re-key was refused; the store is left untouched. */
        data class Rejected(val reason: String, val cause: Throwable? = null) : PageRekeyOutcome()
    }

    /**
     * Active reader stores publish live state first.  Their durable artifact
     * transactions are coalesced by [writeDrain] instead of running
     * synchronously in the stage-emission path.  Test/probe stores keep the
     * historical synchronous default unless the manager explicitly enables
     * this mode when registering the live chapter store.
     */
    @Volatile
    internal var lazyPersistenceEnabled = false

    /** Latest guarded page-write identity rejection, retained for failure diagnostics. */
    @Volatile
    internal var lastGuardedWriteRejectionDiagnostic: String? = null
        internal set

    @Volatile
    internal var lastBatchWriteGateRejectionDiagnostic: String? = null
        internal set

    internal fun recordBatchWriteGateRejectionDiagnostic(diagnostic: String) {
        lastBatchWriteGateRejectionDiagnostic = diagnostic
    }



    /** Enables memory-first publication for a registered production chapter. */
    internal fun enableLazyPersistence() {
        lazyPersistenceEnabled = true
    }

    internal fun isLazyPersistenceEnabled(): Boolean = lazyPersistenceEnabled

    internal fun isLazyGenerationCurrent(expectedGeneration: Long): Boolean =
        !defunct && generation == expectedGeneration

    /**
     * Queues disk work behind the store's persistence worker.  The returned
     * handle is a durability result, not display admission: callers may render
     * from the live state immediately and await it at a terminal commit/barrier.
     *
     * Receipt handshake: [receipt] is the CALLER-HELD completion channel and
     * the returned handle. The store completes it EXACTLY once, normally, and
     * exclusively: `true` only after [work] physically returned, `false` when
     * the task was rejected up front (defunct store, stale generation, no
     * persistence target, closed drain scope), discarded before its turn,
     * cancelled or failed mid-run — or when the caller did not stay enrollable
     * up to the queue mutation (cancellation before the lock). A `false`
     * before [work] ran means the work never started; a completion of either
     * value after queueing means the work already exited, so a resource owner
     * may treat it as borrower-exit proof. An enrolled caller must never
     * complete the receipt itself — a fabricated completion would look like
     * borrower exit to a resource owner. Callers enroll under
     * [kotlinx.coroutines.NonCancellable]; once this function is entered the
     * receipt always terminates.
     */
    internal suspend fun enqueueLazyPersistence(
        expectedGeneration: Long,
        receipt: CompletableDeferred<Boolean>,
        work: suspend () -> Boolean,
    ): Deferred<Boolean> = writeDrain.enqueueLazyPersistence(expectedGeneration, receipt, work)

    internal fun takeLazyPersistenceTaskLocked(): LazyPersistenceTask? = writeDrain.takeLazyPersistenceTaskLocked()

    internal fun hasPendingLazyPersistenceLocked(): Boolean = writeDrain.hasPendingLazyPersistenceLocked()

    internal suspend fun flushPendingLazyMutations(): Boolean = writeDrain.flushPendingLazyMutations()

    @Volatile
    internal var defunct = false

    suspend fun markDefunct() {
        writeDrain.markDefunct(
            markGenerationDefunctLocked = {
                defunct = true
                generation++
                synchronized(pageLeases) { pageLeases.clear() }
            },
        )
        logcat(LogPriority.WARN) { "TachiyomiAT store marked defunct: generation=$generation" }
    }
    val isDefunct: Boolean
        get() = defunct

    init {
        initialRetiredCleanedImages.forEach { (pageKey, names) ->
            if (names.isNotEmpty()) {
                retiredCleanedImages[pageKey] = ConcurrentHashMap.newKeySet<String>().also { it.addAll(names) }
            }
        }
        initialPages.forEach { (pageKey, page) ->
            val version = if (page.pageVersion > 0L) page.pageVersion else nextVersion()
            val owned = page.detachedCopy().apply {
                sourceFileName = sourceFileName ?: pageKey
                runGeneration = generation
                pageVersion = version
            }
            pages = pages.put(pageKey, publishPage(owned))
        }
        nextPageVersion = pages.values.maxOfOrNull(PublishedPageTranslation::pageVersion) ?: 0L
        // Seed the last-known-good display pointers from durable artifact
        // snapshots first. The mutable live map may be an incomplete candidate
        // after process death; it must never become the reader authority.
        initialCommittedPages.forEach { (pageKey, page) ->
            committedDisplay = committedDisplay.put(
                pageKey,
                CommittedPageDisplay(
                    page = publishPage(page),
                    pageVersion = page.pageVersion,
                    displayFingerprint = displayPublication.displayFingerprintOf(page),
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
                        page = page,
                        pageVersion = page.pageVersion,
                        displayFingerprint = displayPublication.displayFingerprintOf(page),
                        promotedAtEpochMs = page.updatedAt,
                    ),
                )
            }
        }
        stateFlow.value = snapshotPages()
        displayPublication.displaySnapshotLocked(pages.keys + committedDisplay.keys)
    }

    suspend fun snapshot(pageKey: String): PageSnapshot = mutex.withLock {
        snapshotLocked(pageKey)
    }

    // Durable status reads stay on the store API; the projector derives them
    // from the store's consistent manifest/state/display snapshot.
    private val statusProjector = StoreStatusProjector(this)

    /**
     * One consistent read of the status-projection inputs for
     * [StoreStatusProjector], captured in the same order the projection body
     * reads them (manifest → live pages flow → display flow). The flows are
     * handed over by reference so their `.value` reads keep landing at the
     * projection body's original points.
     */
    internal fun statusProjectionInputs(): StoreStatusInputs =
        StoreStatusInputs(
            manifest = artifactManifest,
            state = state,
            display = display,
        )

    /** Current durable stage failure, if the artifact manifest owns one. */
    fun durableFailure(
        pageKey: String,
        stage: ArtifactStage = ArtifactStage.TRANSLATION,
    ): DurableFailureMetadata? = statusProjector.durableFailure(pageKey, stage)

    /** Immutable view used by queue restoration and planner admission. */
    fun durableFailuresSnapshot(): Map<String, DurableFailureMetadata> =
        statusProjector.durableFailuresSnapshot()

    // ------------------------------------------------------------------
    // Durable attempt ledger.
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
    ): Boolean = recordAttemptStart(pageKey, providerKeyHash, origin, generation, null)

    @JvmName("recordAttemptStartWithFingerprint")
    suspend fun recordAttemptStart(
        pageKey: String,
        providerKeyHash: String,
        origin: AttemptOrigin,
        generation: Long = currentGeneration,
        requestContextFingerprint: String? = null,
    ): Boolean = mutex.withLock {
        attemptLedger.recordStartLocked(pageKey, providerKeyHash, origin, generation, requestContextFingerprint)
    }

    /** Checks the auto retry cap before dispatch; the request boundary writes the fingerprinted ledger entry. */
    suspend fun autoAttemptAllowed(pageKey: String): Boolean = mutex.withLock {
        attemptLedger.autoAttemptAllowedLocked(pageKey)
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
        val artifact = artifactEngine ?: return
        val manifest = artifactManifest ?: return
        val key = "$pageKey:${ArtifactStage.TRANSLATION.name}"
        val existing = manifest.durableFailures[key] ?: return
        if (existing.category != FailureCategory.INTERRUPTED) return
        val updated = manifest.copy(
            durableFailures = manifest.durableFailures - key,
            updatedAtEpochMs = System.currentTimeMillis(),
        )
        if (artifact.publishManifest(updated)) {
            artifactManifest = updated
            stateFlow.value = snapshotPages()
            displayPublication.displaySnapshotLocked(emptyList<String>())
        } else {
            logcat(LogPriority.WARN) {
                "TachiyomiAT D9: cap-failure clear publish failed (fail-open): pageKey=$pageKey"
            }
        }
    }

    suspend fun beginGeneration(reason: String): Long = mutex.withLock {
        generation++
        logcat(LogPriority.INFO) { "TachiyomiAT store generation advanced: generation=$generation reason=$reason" }
        generation
    }

    // Lease entry points stay on the store so readers and pipeline writers use
    // one ownership boundary. The table uses the store mutex for mutations,
    // synchronized reads for lock-free inspection, and NonCancellable release
    // paths so cancellation cannot strand an owned lease.

    suspend fun tryAcquirePageStageLease(
        pageKey: String,
        stage: PageStage,
        origin: PageWriteOrigin,
    ): LeaseAcquisition = pageStageLeaseTable.tryAcquirePageStageLease(pageKey, stage, origin)

    internal suspend fun tryAcquirePageStageLeaseIfUnowned(
        pageKey: String,
        stage: PageStage,
        origin: PageWriteOrigin,
    ): LeaseAcquisition = pageStageLeaseTable.tryAcquirePageStageLeaseIfUnowned(pageKey, stage, origin)

    suspend fun releasePageStageLease(pageKey: String, origin: PageWriteOrigin) =
        pageStageLeaseTable.releasePageStageLease(pageKey, origin)

    /**
     *   envelope-completion release — removes the page's BATCH lease
     * only while no sibling batch component has attached to the same token
     * (see `PageStageLeaseTable.releasePageStageLeaseIfUnattached`); returns
     * true when this call removed the record.
     */
    suspend fun releasePageStageLeaseIfUnattached(
        pageKey: String,
        origin: PageWriteOrigin,
        expectedToken: Long,
    ): Boolean = pageStageLeaseTable.releasePageStageLeaseIfUnattached(pageKey, origin, expectedToken)

    /**
     *  track V: undoes a same-origin sibling attach (see
     * `PageStageLeaseTable.detachPageStageLeaseIfAttached`); returns true when
     * an attach was undone.
     */
    suspend fun detachPageStageLeaseIfAttached(
        pageKey: String,
        origin: PageWriteOrigin,
        expectedToken: Long,
    ): Boolean = pageStageLeaseTable.detachPageStageLeaseIfAttached(pageKey, origin, expectedToken)

    /** Cancels the active artifact candidate and releases its matching writer lease. */
    suspend fun cancelPageStageWork(pageKey: String, origin: PageWriteOrigin): Boolean =
        pageStageLeaseTable.cancelPageStageWork(pageKey, origin)

    /** Releases every lease held by [origin]; used at batch teardown so no lease outlives its run. */
    suspend fun releaseAllPageLeases(origin: PageWriteOrigin) =
        pageStageLeaseTable.releaseAllPageLeases(origin)

    fun pageLeaseOwner(pageKey: String): PageWriteOrigin? = pageStageLeaseTable.pageLeaseOwner(pageKey)

    /**
     *   true while [pageKey] holds an ACTIVE page-stage lease acquired
     * by [PageWriteOrigin.BATCH] — i.e. a batch run currently owns the page's
     * stage state and will release the lease itself at the owning stage step or
     * run teardown. Lock-free reader over the lease table's
     * `synchronized(pageLeases)` map (same discipline as [pageLeaseOwner]); a
     * momentary race with a concurrent acquire/release is benign for the
     * cancel-writer gating this query exists for. Reader/manual cancel writers
     * must SKIP pages that answer true: a cancel write built from the current
     * snapshot carries the batch's own lease token, sails through the write
     * fence, and bumps the page version mid-stage — the flagged batch lane's
     * `checkpointOcr` then rejects (CHECKPOINT_REJECTED) on a healthy page, or
     * a mid-TRANSLATE write fails `mergeTranslation` and pauses the whole run
     * after the provider call was paid.
     */
    fun hasActiveBatchStageLease(pageKey: String): Boolean =
        pageStageLeaseTable.pageLeaseOwner(pageKey) == PageWriteOrigin.BATCH

    suspend fun invalidateGeneration(reason: String): Long = beginGeneration(reason)

    suspend fun <T> withGeneration(generation: Long, block: suspend () -> T): T =
        withContext(GenerationContext(this, generation)) { block() }




    suspend fun patchPage(
        pageKey: String,
        expected: PatchPrecondition,
        description: String,
        patch: (PageTranslation?) -> PageTranslation,
    ): PatchResult = pageMutations.patchPage(pageKey, expected, description, patch)

    internal suspend fun patchPage(
        pageKey: String,
        expected: PatchPrecondition,
        description: String,
        artifactLease: AttemptArtifactLease?,
        patch: (PageTranslation?) -> PageTranslation,
    ): PatchResult = pageMutations.patchPage(pageKey, expected, description, artifactLease, patch)

    suspend fun updatePage(pageKey: String, update: (PageTranslation?) -> PageTranslation) =
        pageMutations.updatePage(pageKey, update)

    suspend fun updatePageGuarded(
        pageKey: String,
        expected: PatchPrecondition,
        description: String,
        update: (PageTranslation?) -> PageTranslation,
    ): PatchResult = pageMutations.updatePageGuarded(pageKey, expected, description, update)

    internal suspend fun reconcileBatchTranslationPageVersionDrift(
        pageKey: String,
        expected: PatchPrecondition,
        candidate: PageTranslation,
    ): TranslationPublicationReconciliation =
        pageMutations.reconcileBatchTranslationPageVersionDrift(pageKey, expected, candidate)

    suspend fun persistDurableStageFailure(
        pageKey: String,
        expected: PatchPrecondition,
        failure: DurableFailureMetadata,
        description: String = "durable stage failure",
        update: (PageTranslation?) -> PageTranslation,
    ): PatchResult = pageMutations.persistDurableStageFailure(pageKey, expected, failure, description, update)

    suspend fun updatePageFromCurrentSnapshot(
        pageKey: String,
        description: String,
        update: (PageTranslation?) -> PageTranslation,
    ): PatchResult = pageMutations.updatePageFromCurrentSnapshot(pageKey, description, update)

    fun fastCancelInFlightStagesInMemory(
        origin: PageWriteOrigin? = null,
        cancellationReason: String = "Translation cancelled",
    ): Int = pageMutations.fastCancelInFlightStagesInMemory(origin, cancellationReason)

    internal suspend fun patchBlock(
        pageKey: String,
        blockIndex: Int,
        expected: PatchPrecondition,
        expectedBlockFingerprint: String,
        expectedTranslation: String? = null,
        expectedUserEditedAt: Long? = null,
        description: String,
        patch: (TranslationBlock) -> TranslationBlock,
    ): PatchResult = pageMutations.patchBlock(
        pageKey, blockIndex, expected, expectedBlockFingerprint,
        expectedTranslation, expectedUserEditedAt, description, patch,
    )

    suspend fun mergeOcr(
        patch: OcrStagePatch,
        description: String = "stage:ocr",
    ): StagePatchResult = stageMerges.mergeOcr(patch, description)

    suspend fun mergeTranslation(
        patch: TranslationStagePatch,
        description: String = "stage:translation",
    ): StagePatchResult = stageMerges.mergeTranslation(patch, description)

    suspend fun mergeRender(
        patch: RenderStagePatch,
        description: String = "stage:render",
    ): StagePatchResult = stageMerges.mergeRender(patch, description)

    suspend fun checkpointOcr(
        pageKey: String,
        generation: Long,
        expectedPageVersion: Long,
        expectedLeaseToken: Long,
        expectedCandidateGenerationId: String?,
        expectedArtifactPageVersion: Long?,
        expectedDependencyFingerprint: String?,
        sourceOrientation: String? = null,
        sourceSha256: String? = null,
        expectedPriorOcrFingerprints: List<String>? = null,
        mode: OcrCheckpointMode = OcrCheckpointMode.CLOSE,
        nowEpochMs: Long = System.currentTimeMillis(),
        description: String = "ocr checkpoint",
    ): CheckpointOcrResult = stageMerges.checkpointOcr(
        pageKey = pageKey,
        generation = generation,
        expectedPageVersion = expectedPageVersion,
        expectedLeaseToken = expectedLeaseToken,
        expectedCandidateGenerationId = expectedCandidateGenerationId,
        expectedArtifactPageVersion = expectedArtifactPageVersion,
        expectedDependencyFingerprint = expectedDependencyFingerprint,
        sourceOrientation = sourceOrientation,
        sourceSha256 = sourceSha256,
        expectedPriorOcrFingerprints = expectedPriorOcrFingerprints,
        mode = mode,
        nowEpochMs = nowEpochMs,
        description = description,
    )

    suspend fun hydrateOcrFromCheckpoint(
        pageKey: String,
        generation: Long,
        expectedPageVersion: Long,
        expectedLeaseToken: Long,
        expectedCandidateGenerationId: String?,
        expectedDependencyFingerprint: String?,
        expectedArtifactPageVersion: Long?,
        checkpointPointer: SidecarPointer,
        observedSourceSha256: String?,
        description: String = "checkpoint:hydrate:ocr",
    ): StagePatchResult = stageMerges.hydrateOcrFromCheckpoint(
        pageKey,
        generation,
        expectedPageVersion,
        expectedLeaseToken,
        expectedCandidateGenerationId,
        expectedDependencyFingerprint,
        expectedArtifactPageVersion,
        checkpointPointer,
        observedSourceSha256,
        description,
    )

    fun resolveDisplayPage(pageKey: String): PageTranslationView? =
        displayPublication.resolveDisplayPage(pageKey)

    suspend fun getOrLoadPageSnapshot(pageKey: String): PageTranslation? =
        displayPublication.getOrLoadPageSnapshot(pageKey)

    internal fun committedDisplayPage(pageKey: String): PageTranslationView? =
        displayPublication.committedDisplayPage(pageKey)

    fun mayDeleteCleanedImage(pageKey: String, name: String): Boolean =
        displayPublication.mayDeleteCleanedImage(pageKey, name)

    suspend fun authorizeCleanedImageDeletion(pageKey: String, name: String): Boolean =
        displayPublication.authorizeCleanedImageDeletion(pageKey, name)

    fun isCleanedImageReferencedByAnotherPage(pageKey: String, name: String): Boolean =
        displayPublication.isCleanedImageReferencedByAnotherPage(pageKey, name)

    fun referencedCleanedImageNames(): Set<String> =
        displayPublication.referencedCleanedImageNames()

    fun drainRetiredCleanedImages(pageKey: String): List<String> =
        displayPublication.drainRetiredCleanedImages(pageKey)

    suspend fun demoteCommittedDisplay(pageKey: String, reason: String): String? =
        displayPublication.demoteCommittedDisplay(pageKey, reason)

    suspend fun retireActiveRun(reason: String): Boolean =
        displayPublication.retireActiveRun(reason)

    internal fun cancelArtifactCandidateLocked(pageKey: String): Boolean =
        displayPublication.cancelArtifactCandidateLocked(pageKey)

    internal fun shouldPersistUpdate(previous: PageTranslation?, updated: PageTranslation): Boolean =
        pageMutations.shouldPersistUpdate(previous, updated)

    internal fun pageWriteRejection(pageKey: String, expected: PatchPrecondition): PageWriteRejection? =
        pageMutations.pageWriteRejection(pageKey, expected)
    suspend fun deletePage(pageKey: String) = bulkMutator.deletePage(pageKey)
    suspend fun replaceAll(updatedPages: Map<String, PageTranslation>) =
        bulkMutator.replaceAll(updatedPages)

    /** Moves source-keyed pages to their completed-download keys atomically. */
    suspend fun rekeyPages(
        onlineKeys: List<String>,
        onDiskKeys: List<String>,
    ): PageRekeyOutcome = bulkMutator.rekeyPages(onlineKeys, onDiskKeys)

    /**
     * Outcome of [preRegisterPages]. A rejection is observable so
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
     * Returns [PagePreRegistration.Rejected] when the store refuses
     * registration (defunct store or artifact-authority failure), so the caller
     * can surface a typed terminal
     * error rather than a live zero tracker.
     *
     *  The trigger may attach its
     * admission-probe cross-check. When [sourceCountKnown] is true the
     * registered truth is honest about partiality:
     *  - a known SOURCE total ([probedSourcePageCount] != null) makes the
     *    trusted baseline the source total (progress shows 37/40, not 40/40)
     *    and records the delta as [PartialBatchInfo.DOWNLOAD_CROSSCHECK] —
     *    cleared again when a later cross-check derives missing == 0;
     *  - an unknown total (null count) keeps the found count but DEMOTES the
     *    trusted stamp and records `determinedFrom = UNKNOWN` — the durable
     *    record stops claiming trust it does not have.
     * Missing pages are never registered as page records; the manifest carries
     * the absence. Without context (both defaults) the self-derived
     * stamp below is byte-identical to its pre-existing behavior.
     */
    suspend fun preRegisterPages(
        pageKeys: List<String>,
        probedSourcePageCount: Int? = null,
        sourceCountKnown: Boolean = false,
    ): PagePreRegistration =
        bulkMutator.preRegisterPages(pageKeys, probedSourcePageCount, sourceCountKnown)

    /**
     * Clears queue-like entries after a user cancel/auto-window replacement.
     * Pages with a rendered/cleaned result are kept; transient running, pending,
     * cancelled, and failed entries without output are reset so the reader queue
     * reflects only the next active window.
     */
    suspend fun clearTransientQueuePages(reason: String? = null) =
        bulkMutator.clearTransientQueuePages(reason)
    internal fun nextVersion(): Long = ++nextPageVersion

    internal fun ownedPage(pageKey: String, candidate: PageTranslation): PageTranslation =
        candidate.detachedCopy().apply {
            sourceFileName = sourceFileName ?: pageKey
            updatedAt = System.currentTimeMillis()
            runGeneration = generation
            pageVersion = nextVersion()
        }

    internal fun snapshotLocked(pageKey: String): PageSnapshot {
        val page = pages[pageKey]
        val artifactPage = artifactManifest?.pages?.get(pageKey)
        return PageSnapshot(
            page = page?.toDraft(),
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

    internal fun snapshotPages(): PersistentMap<String, PublishedPageTranslation> = pages

    internal fun publishPage(draft: PageTranslation): PublishedPageTranslation {
        testSeams.countPublishedPageCopy()
        return draft.toPublishedPage()
    }

    /** Durable translation status derived from the committed artifact state. */
    fun artifactStatus(): Translation.State? = statusProjector.artifactStatus()

    suspend fun flush() = writeDrain.flush()

    suspend fun closeAndFlush() {
        try {
            writeDrain.closeAndFlush()
        } finally {
            artifactEngine?.cancelRetentionWorkAndJoin()
        }
    }

    fun close() = writeDrain.close {
        try {
            reconcileArtifactRetention()
        } finally {
            artifactEngine?.cancelRetentionWorkAndJoin()
        }
    }

    /** Performs the bounded artifact-tree sweep at a serialized chapter boundary. */
    suspend fun reconcileArtifactRetention() {
        val engine = mutex.withLock { artifactEngine } ?: return
        engine.reconcileRetentionOffLock(
            manifestSnapshot = { mutex.withLock { artifactManifest } },
            deleteAgainstLiveManifest = { candidates ->
                mutex.withLock {
                    artifactEngine?.deleteVerifiedRetentionCandidates(candidates)
                        ?: eu.kanade.translation.persistence.RetentionResult(0, emptyList())
                }
            },
        )
    }

    /** Fire-and-forget boundary sweep; ChapterArtifactEngine owns deduplication and lifecycle. */
    fun reconcileArtifactRetentionAsync() {
        val engine = artifactEngine ?: return
        engine.reconcileRetentionAsync(
            manifestSnapshot = { mutex.withLock { artifactManifest } },
            deleteAgainstLiveManifest = { candidates ->
                mutex.withLock {
                    artifactEngine?.deleteVerifiedRetentionCandidates(candidates)
                        ?: eu.kanade.translation.persistence.RetentionResult(0, emptyList())
                }
            },
        )
    }

    companion object {
        /**
         * Open mechanics live on the opener collaborator (Phase 7 S6); these
         * shells keep existing callers and tests unaffected. The open path
         * takes only the per-artifact open lock and never the store mutex, so
         * store mutex -> artifact-open lock cannot invert.
         */

        /** Reads only the small manifest header; page snapshots stay unopened. */
        internal fun probeArtifactManifest(parent: UniFile, fileName: String): ArtifactManifestProbe =
            ChapterStoreOpener.probeArtifactManifest(parent, fileName)

        /** Opens an artifact and installs only the state reconstructed from its ACKed journal prefix. */
        internal suspend fun openArtifactSuspend(
            parent: UniFile,
            fileName: String,
            privateStorageRoot: File? = null,
            privateStorageIdentity: String? = null,
        ): ChapterTranslationStore = ChapterStoreOpener.openArtifactSuspend(
            parent,
            fileName,
            privateStorageRoot,
            privateStorageIdentity,
        )

        internal suspend fun <T> withArtifactOpenLock(
            parent: UniFile?,
            fileName: String,
            block: suspend (ChapterStoreOpener.ArtifactOpenLock) -> T,
        ): T = ChapterStoreOpener.withArtifactOpenLock(parent, fileName, block)

        /**
         * Creates a store whose artifact manifest is created lazily on the first
         * artifact-backed [updatePage]/[replaceAll] write. When
         * [artifactParent] is null (chapter never translated), the optional
         * [artifactParentResolver] resolves the parent directory on the first mutation.
         */
        fun lazy(
            artifactParent: UniFile? = null,
            artifactFileName: String? = null,
            privateStorageRoot: File? = null,
            privateStorageIdentity: String? = null,
            artifactParentResolver: (() -> UniFile)? = null,
        ): ChapterTranslationStore = ChapterStoreOpener.lazy(
            artifactParent = artifactParent,
            artifactFileName = artifactFileName,
            privateStorageRoot = privateStorageRoot,
            privateStorageIdentity = privateStorageIdentity,
            artifactParentResolver = artifactParentResolver,
        )
    }
}

/** Shared OCR merge for fenced and attempt writes. */

/**
 * Explicit storage configuration for a chapter store.
 *
 * The mode is deliberately separate from the public [ChapterTranslationStore]
 * seam: memory-only stores are a supported test/runtime mode, while durable
 * stores may be eager or lazy.  Keeping the mode explicit lets the merge
 * remove nullable engine state without changing the durable artifact schema.
 */
internal sealed interface ChapterStoreEngineMode {
    /** No filesystem target; mutations remain in memory and retain dirty probes. */
    data object Memory : ChapterStoreEngineMode

    /** Filesystem target known, but the artifact engine is opened on first write. */
    data class LazyDurable(
        val artifactParent: UniFile?,
        val artifactFileName: String?,
        val artifactParentResolver: (() -> UniFile)?,
    ) : ChapterStoreEngineMode

    /** Eagerly opened artifact engine owned by the facade. */
    data class Durable(val artifact: ChapterArtifactEngine) : ChapterStoreEngineMode
}

