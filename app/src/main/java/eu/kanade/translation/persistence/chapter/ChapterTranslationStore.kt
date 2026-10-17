package eu.kanade.translation.persistence.chapter

import com.hippo.unifile.UniFile
import eu.kanade.translation.model.PageStage
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.PageTranslationView
import eu.kanade.translation.model.PublishedPageTranslation
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
import eu.kanade.translation.persistence.artifact.ArtifactManifestProbe
import eu.kanade.translation.persistence.artifact.ArtifactStage
import eu.kanade.translation.persistence.artifact.ArtifactStageStatus
import eu.kanade.translation.persistence.artifact.AttemptOrigin
import eu.kanade.translation.persistence.artifact.ChapterArtifactEngine
import eu.kanade.translation.persistence.artifact.ChapterArtifactManifest
import eu.kanade.translation.persistence.artifact.ChapterRunRecord
import eu.kanade.translation.persistence.artifact.ChapterTranslationProfile
import eu.kanade.translation.persistence.artifact.CommittedDisplayRef
import eu.kanade.translation.persistence.artifact.DurableFailureMetadata
import eu.kanade.translation.persistence.artifact.FailureCategory
import eu.kanade.translation.persistence.artifact.OcrCheckpointMode
import eu.kanade.translation.persistence.artifact.PageOcrCheckpoint
import eu.kanade.translation.persistence.artifact.SidecarPointer
import eu.kanade.translation.persistence.artifact.SidecarRead
import eu.kanade.translation.persistence.artifact.SourceIdentity
import eu.kanade.translation.persistence.artifact.StageFingerprints
import eu.kanade.translation.persistence.artifact.isSha256Hex
import eu.kanade.translation.persistence.internal.ChapterAttemptLedger
import eu.kanade.translation.persistence.internal.ChapterStoreEngineMode
import eu.kanade.translation.persistence.internal.PageStageLeaseTable
import eu.kanade.translation.persistence.internal.StoreWriteDrainCoordinator
import eu.kanade.translation.persistence.internal.formatWriteDiagnostic
import eu.kanade.translation.persistence.journal.ChapterJournalCredit
import eu.kanade.translation.persistence.journal.ChapterJournalFormat
import eu.kanade.translation.persistence.journal.ChapterJournalGcMode
import eu.kanade.translation.persistence.journal.ChapterJournalInventorySnapshot
import eu.kanade.translation.persistence.journal.ChapterJournalReplayResult
import eu.kanade.translation.persistence.journal.ChapterJournalSnapshotCandidate
import eu.kanade.translation.persistence.journal.ChapterJournalStorage
import eu.kanade.translation.persistence.journal.ChapterJournalWriter
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
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Bounded scalar record of an attempt's own verified artifact drain progress. */
internal data class CertifiedArtifactProgress(
    val pageKey: String,
    val generation: Long,
    val livePageVersion: Long,
    val artifactPageVersion: Long?,
    val candidateGenerationId: String?,
    val dependencyFingerprint: String?,
)

/** Binds one attempt's authority to its own lazy-drain artifact progressions on a specific page. */
internal class AttemptArtifactLease internal constructor(
    private val store: ChapterTranslationStore,
    val pageKey: String,
) {
    internal fun belongsTo(store: ChapterTranslationStore, pageKey: String): Boolean =
        this.store === store && this.pageKey == pageKey

    @Volatile
    internal var certifiedProgress: CertifiedArtifactProgress? = null

    internal fun stamp(
        store: ChapterTranslationStore,
        stampedPageKey: String,
        snapshot: ChapterTranslationStore.PageSnapshot,
    ) {
        if (!belongsTo(store, stampedPageKey)) return
        val manifestRecord = store.artifactManifest?.pages?.get(pageKey)
        val manifestArtifactPageVersion = manifestRecord?.pageVersion
        val manifestCandidateGen = manifestRecord?.candidate?.generationId
        val manifestDepFingerprint = manifestRecord?.candidate?.dependencyFingerprint
            ?: snapshot.dependencyFingerprint
        certifiedProgress = CertifiedArtifactProgress(
            pageKey = pageKey,
            generation = snapshot.generation,
            livePageVersion = snapshot.pageVersion,
            artifactPageVersion = manifestArtifactPageVersion,
            candidateGenerationId = manifestCandidateGen,
            dependencyFingerprint = manifestDepFingerprint,
        )
    }
}

/** Durable outcome of the [ChapterTranslationStore.checkpointOcr] transaction. */
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
    journalReplayResult: ChapterJournalReplayResult? = null,
    initialRecoveredPages: Map<String, PublishedPageTranslation>? = null,
    initialUnverifiedManifestFallbackPageKeys: Set<String> = emptySet(),
    private val journalStorageFactoryForOpen: ((File, File) -> ChapterJournalStorage)? = null,
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

    /** Journal capture writes to app-private storage; inventory trust then must be earned from the replay/seed prefix. */
    internal val hasPrivateJournalStorage: Boolean
        get() = privateStorageRoot != null

    /**
     * Monotonic store-side lock hierarchy (Phase 7 S2). Every acquisition in a
     * write path moves DOWN this list; nothing ever acquires a lock higher in
     * the list while holding a lower one:
     *
     * 1. journal credit reservation (`reserveJournalCredit` — before [mutex]);
     * 2. producer capture permit (`withJournalCapturePermit`);
     * 3. the store [mutex] — suspending, so holders may await lower locks;
     * 4. the per-artifact open lock (`withArtifactOpenLock`) — acquired only
     *    from inside [mutex] via [ensureArtifactStore], as a coroutine.
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
        (ChapterArtifactEngine.CandidateOpenState.Reused) -> Unit,
        >()

    /** Observe typed artifact reuse outcomes without coupling storage to batch diagnostics. */
    internal fun observeCandidateReuse(
        observer: (ChapterArtifactEngine.CandidateOpenState.Reused) -> Unit,
    ): AutoCloseable {
        candidateReuseObservers.add(observer)
        return AutoCloseable { candidateReuseObservers.remove(observer) }
    }

    internal fun reportCandidateReuse(state: ChapterArtifactEngine.CandidateOpenState?) {
        val reused = state as? ChapterArtifactEngine.CandidateOpenState.Reused ?: return
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
        return (artifact.readRunRecord(pointer) as? ChapterArtifactEngine.RunRecordRead.Usable)?.record
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

    /**
     * Status collaborator (Phase 7 S3). Owns the journal-status snapshot
     * seeded from the recovered prefix at open, the inventory trust/fallback
     * state, and the pending expected-page totals; the store delegates every
     * read and write under [mutex].
     */
    internal val statusLedger = ChapterStatusLedger(
        store = this,
        journalReplayResult = journalReplayResult,
        initialUnverifiedManifestFallbackPageKeys = initialUnverifiedManifestFallbackPageKeys,
    )

    /** Captures bootstrap failures and durable progress instead of hiding a failed open-time seed. */
    internal val journalSeedDiagnostics: ChapterJournalSeedDiagnostics
        get() = journalBridge.journalSeedDiagnostics

    /**
     * Cleaned-image names retained because the superseded committed bundle
     * still displays them; drained for deletion by the pipeline after a newer
     * bundles promote. Keeping all names until a drain prevents a rapid sequence
     * of promotions from losing an earlier retained file.
     */
    internal val retiredCleanedImages = ConcurrentHashMap<String, MutableSet<String>>()
    internal val pendingArtifactPageRegistrations = LinkedHashSet<String>()

    private val _display = MutableStateFlow<PersistentMap<String, PublishedPageTranslation>>(persistentMapOf())

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

    private class GenerationContext(
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
     * open/cancel/persist/promote/failure orchestration; the store delegates
     * under [mutex].
     */
    private val publicationBridge = ChapterStorePublicationBridge(this)

    // Lazy-write queue state is stored by the drain coordinator, but every
    // mutation is serialized by this store's mutex. Store-to-drain scheduling
    // is a synchronous handoff with no second state lock; drain paths re-enter
    // through mutex.withLock before reading or changing store state.
    internal val writeDrain = StoreWriteDrainCoordinator(this, persistenceDispatcher)

    /**
     * Journal capture collaborator (Phase 7 S5). Owns journal credit
     * reservation, writer/epoch lifecycle, the page capture frames, bulk
     * capture frames, inventory snapshots, and the capture permit; the store
     * delegates every call here. Epoch allocation runs on the persistence
     * dispatcher under the bridge's creation monitor, strictly outside
     * [mutex]. Module-internal so [ChapterStoreTestSeams] can reach its
     * injection seams without facade shells.
     */
    internal val journalBridge = ChapterStoreJournalBridge(
        store = this,
        persistenceDispatcher = persistenceDispatcher,
        privateStorageRoot = privateStorageRoot,
        privateStorageIdentity = privateStorageIdentity,
        journalStorageFactoryForOpen = journalStorageFactoryForOpen,
    )

    /**
     * Lazy-flush collaborator (Phase 7 S5). Owns the queued lazy-mutation drain
     * and its journal-barrier interleave; the write drain delegates here.
     */
    private val lazyFlusher = ChapterStoreLazyFlusher(this)

    /**
     * Bulk-mutation collaborator (Phase 7 S7). Owns [deletePage],
     * [replaceAll], [rekeyPages], [preRegisterPages],
     * [clearTransientQueuePages], and the two-phase re-key planning machinery;
     * the store delegates every public entry point here.
     */
    private val bulkMutator = ChapterBulkMutator(this)

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

    /** The active journal writer (test seam first, then the lazily created production writer). */
    internal val journalWriter: ChapterJournalWriter?
        get() = journalBridge.journalWriter

    /** Producer capture permit; taken before [mutex] in every write path (Phase 7 S2 lock order). */
    internal suspend fun <T> withJournalCapturePermit(block: suspend () -> T): T =
        journalBridge.withJournalCapturePermit(block)

    /** Parks chapter journal producers and runs [capture] as one capture barrier; delegates to the journal bridge. */
    internal suspend fun <T> withJournalCaptureBarrier(capture: suspend () -> T): T =
        journalBridge.withCaptureBarrier(capture)

    /**
     * Explicit, dormant shadow-era compaction entry point for E16b certification; delegates to the
     * journal bridge (Phase 7 S5), which owns the producer-gate close/drain/snapshot/reopen dance.
     */
    internal suspend fun captureJournalSnapshotForCompaction(
        gcMode: ChapterJournalGcMode = ChapterJournalGcMode.SHADOW_DORMANT,
    ): ChapterJournalSnapshotCandidate? = journalBridge.captureJournalSnapshotForCompaction(gcMode)

    /**
     * Captures one immutable published-map reference under the store mutex; delegates to the
     * journal bridge (Phase 7 S5).
     */
    internal suspend fun capturePublishedPagesForCompaction(): PersistentMap<String, PublishedPageTranslation> =
        journalBridge.capturePublishedPagesForCompaction()

    /**
     * Explicit E16b recovery seam; delegates to the journal bridge (Phase 7 S5). File reads happen
     * on the persistence dispatcher and never retain a file handle across reducer application.
     */
    internal suspend fun replayJournalForRecovery(): ChapterJournalReplayResult? =
        journalBridge.replayJournalForRecovery()

    /**
     * Epoch allocation scans and creates app-private paths on the persistence dispatcher before
     * the store mutex is acquired; delegates to the journal bridge (Phase 7 S5).
     */
    internal suspend fun ensureJournalWriter(): ChapterJournalWriter? = journalBridge.ensureJournalWriter()

    /** Reserve before taking [mutex]; delegates to the journal bridge (Phase 7 S5). */
    internal suspend fun reserveJournalCredit(
        pageKey: String,
        onFailure: ((Throwable) -> Unit)? = null,
    ): ChapterJournalCredit? = journalBridge.reserveJournalCredit(pageKey, onFailure)

    /** Non-suspending lock-held snapshot handoff after the store transaction commits; delegates to the journal bridge. */
    internal fun capturePageMutationLocked(
        pageKey: String,
        page: PageTranslationView,
        expected: PatchPrecondition?,
        durableFailure: DurableFailureMetadata?,
        credit: ChapterJournalCredit?,
        artifactContentHash: String? = null,
        journalFlushBatch: JournalFlushBatch? = null,
    ) = journalBridge.capturePageMutationLocked(
        pageKey = pageKey,
        page = page,
        expected = expected,
        durableFailure = durableFailure,
        credit = credit,
        artifactContentHash = artifactContentHash,
        journalFlushBatch = journalFlushBatch,
    )

    /** A deletion is a page-level store commit and receives its own fenced tombstone record; delegates to the journal bridge. */
    internal fun capturePageDeletionLocked(
        pageKey: String,
        pageVersion: Long,
        credit: ChapterJournalCredit?,
        artifactContentHash: String? = null,
    ) = journalBridge.capturePageDeletionLocked(
        pageKey = pageKey,
        pageVersion = pageVersion,
        credit = credit,
        artifactContentHash = artifactContentHash,
    )

    /** Caller holds [mutex] and an active bulk capture; null hash means retryable invalidation. Delegates to the journal bridge. */
    internal fun captureRekeyStateLocked(
        oldPageKey: String,
        newPageKey: String,
        mutation: ChapterArtifactEngine.PreparedRekeyJournalMutation,
    ) = journalBridge.captureRekeyStateLocked(oldPageKey, newPageKey, mutation)

    /** Caller holds [mutex]; opens one bulk API operation's journal frame. Delegates to the journal bridge. */
    internal fun openBulkJournalFrameLocked(
        kind: ChapterJournalFormat.RecordKind,
        operation: String,
        rekeyMapping: Map<String, String?> = emptyMap(),
    ) = journalBridge.openBulkJournalFrameLocked(kind, operation, rekeyMapping)

    /** Caller holds [mutex]; abandons an unfinished frame without emitting it. Delegates to the journal bridge. */
    internal fun cancelBulkJournalFrameLocked(bulk: BulkJournalCapture) =
        journalBridge.cancelBulkJournalFrameLocked(bulk)

    /** Caller holds [mutex]. One bulk operation consumes one credit and one commit sequence. Delegates to the journal bridge. */
    internal fun finishBulkJournalCaptureLocked(
        bulk: BulkJournalCapture,
        credit: ChapterJournalCredit?,
        inventory: ChapterJournalInventorySnapshot? = null,
    ) = journalBridge.finishBulkJournalCaptureLocked(bulk, credit, inventory)

    /** Snapshot artifact identity from the already-published manifest; never rehashes under [mutex]. Delegates to the journal bridge. */
    internal fun pageArtifactContentHash(
        manifest: ChapterArtifactManifest?,
        pageKey: String,
        fallbackSnapshot: PageTranslationView? = null,
    ) = journalBridge.pageArtifactContentHash(manifest, pageKey, fallbackSnapshot)

    /** Capture the current inventory; sorting and fingerprinting remain on the writer dispatcher. Delegates to the journal bridge. */
    internal fun journalInventorySnapshot(
        manifest: ChapterArtifactManifest?,
        pendingRegistrations: Set<String> = pendingArtifactPageRegistrations,
    ): ChapterJournalInventorySnapshot = journalBridge.journalInventorySnapshot(manifest, pendingRegistrations)

    /**
     * Idempotently seeds hash-verified manifest page snapshots while the store is still private to
     * open; delegates to the journal bridge (Phase 7 S5), which owns the untrusted-to-trusted
     * inventory commit point.
     */
    internal suspend fun seedJournalFromManifest(
        seedPages: Map<String, PageTranslation>,
        inventory: ChapterJournalInventorySnapshot,
    ) = journalBridge.seedJournalFromManifest(seedPages, inventory)

    /**
     * Active reader stores publish live state first.  Their durable artifact
     * transactions are coalesced by [writeDrain] instead of running
     * synchronously in the stage-emission path.  Test/probe stores keep the
     * historical synchronous default unless the manager explicitly enables
     * this mode when registering the live chapter store.
     */
    @Volatile
    private var lazyPersistenceEnabled = false

    /** Latest guarded page-write identity rejection, retained for failure diagnostics. */
    @Volatile
    internal var lastGuardedWriteRejectionDiagnostic: String? = null
        private set

    @Volatile
    internal var lastBatchWriteGateRejectionDiagnostic: String? = null
        private set

    /** Publication rejections live on the bridge; the facade only reads the latest diagnostic. */
    private val lastArtifactPublicationRejectionDiagnostic: String?
        get() = publicationBridge.lastArtifactPublicationRejectionDiagnostic

    internal fun recordBatchWriteGateRejectionDiagnostic(diagnostic: String) {
        lastBatchWriteGateRejectionDiagnostic = diagnostic
    }

    internal data class PendingLazyMutation(
        val pageKey: String,
        val previous: PageTranslation?,
        val updated: PageTranslation,
        val expected: PatchPrecondition,
        val generation: Long,
        val journalCredit: ChapterJournalCredit? = null,
        val artifactLease: AttemptArtifactLease? = null,
    )

    internal data class LazyPersistenceTask(
        val generation: Long,
        val work: suspend () -> Boolean,
        val result: CompletableDeferred<Boolean>,
    )

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
    ): Deferred<Boolean> {
        var queued = false
        try {
            mutex.withLock {
                val rejected = defunct ||
                    generation != expectedGeneration ||
                    !hasArtifactPersistenceTarget() ||
                    !writeDrain.isDrainScopeActive()
                if (rejected) {
                    receipt.complete(false)
                } else {
                    writeDrain.pendingLazyTasks.addLast(
                        LazyPersistenceTask(
                            generation = expectedGeneration,
                            work = work,
                            result = receipt,
                        ),
                    )
                    queued = true
                }
                // Keep the drain-job handoff under the same mutex as the queue
                // mutation so it shares the queue's single-writer boundary.
                writeDrain.scheduleDrain()
            }
        } finally {
            // Not queued (rejected above, or the caller was cancelled before
            // acquiring the lock) means no borrower exists: complete the
            // receipt false so no owner ever awaits a signal the store will
            // never send.
            if (!queued) receipt.complete(false)
        }
        return receipt
    }

    /** Caller holds [mutex]; the drain calls this only from mutex.withLock. */
    internal fun takeLazyPersistenceTaskLocked(): LazyPersistenceTask? =
        writeDrain.pendingLazyTasks.pollFirst()

    /** Caller holds [mutex]; the drain uses this to decide whether to reschedule. */
    internal fun hasPendingLazyPersistenceLocked(): Boolean =
        writeDrain.pendingLazyTasks.isNotEmpty() || writeDrain.pendingLazyMutations.isNotEmpty()

    /**
     * Owns the journal-capture interleave for queued lazy mutations; delegates
     * to the lazy flusher (Phase 7 S5). Reserve credits before taking the
     * capture permit, then acquire the store mutex inside that permit so lazy
     * frames cannot cross a capture barrier. The batch gets one final force,
     * plus required pre-close segment forces at rollover and a segment-header
     * force whenever a new segment is created (epoch start or rollover).
     */
    internal suspend fun flushPendingLazyMutationsWithJournalCapture() {
        lazyFlusher.flushPendingLazyMutationsWithJournalCapture()
    }

    /**
     * TachiyomiAT: set by [markDefunct] when the store is evicted
     * ([TranslationManager.unregisterActiveTranslationStore], on delete / chapter
     * change). Once defunct, every mutator becomes a no-op and logs at WARN. This
     * guards the delete-then-retranslate race: a batch worker mid-uncancellable
     * ONNX when cancel() was requested can still reach a suspension point AFTER
     * its store was evicted and the artifact tree and images deleted; without
     * this guard it could recreate artifact state or strand a page at RUNNING.
     * Volatile: the eviction transition is written under [mutex] after the bounded drain join;
     * mutators read it lock-free.
     */
    @Volatile
    internal var defunct = false

    /**
     * Eviction snapshots and joins the active drain job outside [mutex]. If
     * that job installs a replacement in its completion handler, preserve the
     * replacement reference and let its defunct-guarded flush finish; this
     * boundary deliberately does not loop to drain replacement jobs.
     */
    suspend fun markDefunct() {
        writeDrain.markDefunct(
            markGenerationDefunctLocked = {
                defunct = true
                generation++
            },
            requestDefunctMarkerLocked = {
                journalWriter?.requestDefunctMarker()
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
        if (initialRecoveredPages != null) {
            initialRecoveredPages.forEach { (pageKey, page) ->
                pages = pages.put(pageKey, page)
            }
            nextPageVersion = initialRecoveredPages.values.maxOfOrNull(PublishedPageTranslation::pageVersion) ?: 0L
        } else {
            initialPages.forEach { (pageKey, page) ->
                val version = nextVersion()
                val owned = page.detachedCopy().apply {
                    sourceFileName = sourceFileName ?: pageKey
                    runGeneration = generation
                    pageVersion = version
                }
                pages = pages.put(pageKey, publishPage(owned))
            }
        }
        // Seed the last-known-good display pointers from durable artifact
        // snapshots first. The mutable live map may be an incomplete candidate
        // after process death; it must never become the reader authority.
        initialCommittedPages.forEach { (pageKey, page) ->
            committedDisplay = committedDisplay.put(
                pageKey,
                CommittedPageDisplay(
                    page = publishPage(page),
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
                        page = page,
                        pageVersion = page.pageVersion,
                        displayFingerprint = displayFingerprintOf(page),
                        promotedAtEpochMs = page.updatedAt,
                    ),
                )
            }
        }
        stateFlow.value = snapshotPages()
        displaySnapshotLocked(pages.keys + committedDisplay.keys)
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
            journalStatus = statusLedger.currentStatusSnapshot(),
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
            statusLedger.clearFailureLocked(pageKey)
            stateFlow.value = snapshotPages()
            displaySnapshotLocked(emptyList())
        } else {
            logcat(LogPriority.WARN) {
                "TachiyomiAT D9: cap-failure clear publish failed (fail-open): pageKey=$pageKey"
            }
        }
    }

    /** Caller holds [mutex]; suspending while a lazy first write opens the artifact store. Delegates to the publication bridge. */
    internal suspend fun admitMutation(): MutationAdmission = publicationBridge.admitMutation()

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
    ): PatchResult = patchPage(pageKey, expected, description, artifactLease = null, patch = patch)

    internal suspend fun patchPage(
        pageKey: String,
        expected: PatchPrecondition,
        description: String,
        artifactLease: AttemptArtifactLease?,
        patch: (PageTranslation?) -> PageTranslation,
    ): PatchResult {
        if (defunct) return rejected(pageKey, description, "store is defunct")
        val journalCredit = reserveJournalCredit(pageKey)
        return try {
            withJournalCapturePermit {
                mutex.withLock {
                    when (val admission = admitMutation()) {
                        MutationAdmission.Granted -> Unit
                        is MutationAdmission.Rejected -> return@withLock rejected(
                            pageKey,
                            description,
                            "${admission.code}: ${admission.message}",
                        )
                    }
                    val current = pages[pageKey]?.toDraft()
                    if (expected.generation != generation) {
                        return@withLock rejected(pageKey, description, "generation expected=${expected.generation} actual=$generation")
                    }
                    val currentLiveVersion = current?.pageVersion ?: 0L
                    if (expected.pageVersion != currentLiveVersion) {
                        return@withLock rejected(pageKey, description, "pageVersion expected=${expected.pageVersion} actual=$currentLiveVersion")
                    }

                    val drained = artifactLease?.takeIf { it.belongsTo(this, pageKey) }?.certifiedProgress
                    val effective = if (
                        drained != null &&
                        expected.generation == drained.generation &&
                        expected.pageVersion == drained.livePageVersion &&
                        currentLiveVersion == drained.livePageVersion
                    ) {
                        val manifestRecord = artifactManifest?.pages?.get(pageKey)
                        val manifestArtifactPageVersion = manifestRecord?.pageVersion
                        val manifestCandidateGen = manifestRecord?.candidate?.generationId
                        val manifestDepFingerprint = manifestRecord?.candidate?.dependencyFingerprint
                            ?: pages[pageKey]?.let(StageFingerprints::pageSnapshot)
                        if (
                            manifestArtifactPageVersion == drained.artifactPageVersion &&
                            manifestCandidateGen == drained.candidateGenerationId &&
                            manifestDepFingerprint == drained.dependencyFingerprint
                        ) {
                            expected.copy(
                                artifactPageVersion = drained.artifactPageVersion,
                                candidateGenerationId = drained.candidateGenerationId,
                                dependencyFingerprint = drained.dependencyFingerprint,
                            )
                        } else {
                            expected
                        }
                    } else {
                        expected
                    }

                    val rejection = when {
                        effective.artifactPageVersion != null &&
                            effective.artifactPageVersion != artifactManifest?.pages?.get(pageKey)?.pageVersion ->
                            "artifact pageVersion changed"
                        effective.candidateGenerationId != null &&
                            effective.candidateGenerationId != artifactManifest?.pages?.get(pageKey)?.candidate?.generationId ->
                            "candidate generation changed"
                        // The same candidate-grace rule as persistArtifactMutation
                        // applies: `candidate != null` grace
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
                        effective.dependencyFingerprint != null &&
                            artifactManifest?.pages?.get(pageKey)?.candidate != null &&
                            effective.dependencyFingerprint != artifactManifest?.pages?.get(pageKey)?.candidate?.dependencyFingerprint ->
                            "candidate dependency fingerprint changed"
                        effective.blockFingerprints != null &&
                            effective.blockFingerprints != current?.blockFingerprints().orEmpty() ->
                            "block fingerprint changed"
                        effective.leaseToken != null && pageLeases[pageKey]?.token != effective.leaseToken ->
                            "page lease token changed"
                        effective.leaseToken == null && pageLeases[pageKey] != null ->
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
                        pages = pages.put(pageKey, publishPage(updated))
                        if (!publishLocked(current, updated, effective, journalCredit = journalCredit, artifactLease = artifactLease)) {
                            restorePageLocked(pageKey, current)
                            return@withLock rejected(pageKey, description, "ARTIFACT_PUBLICATION_FAILED")
                        }
                        PatchResult.Accepted(snapshotLocked(pageKey))
                    }
                }
            }
        } finally {
            journalCredit?.releaseIfHeld()
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
        val journalCredit = reserveJournalCredit(pageKey)
        return try {
            withJournalCapturePermit {
                mutex.withLock {
                    when (val admission = admitMutation()) {
                        MutationAdmission.Granted -> Unit
                        is MutationAdmission.Rejected -> return@withLock rejected(
                            pageKey,
                            description,
                            "${admission.code}: ${admission.message}",
                            detail = admission.toPatchRejectionDetail(),
                        )
                    }
                    val rejection = pageWriteRejection(pageKey, expected)
                    if (rejection != null) {
                        val actual = snapshotLocked(pageKey)
                        val actualLeaseToken = pageLeases[pageKey]?.token
                        lastGuardedWriteRejectionDiagnostic = formatWriteDiagnostic(
                            pageKey,
                            "description" to description,
                            "reason" to rejection.reason,
                            "expected" to "{generation=${expected.generation}, pageVersion=${expected.pageVersion}, " +
                                "artifactPageVersion=${expected.artifactPageVersion}, candidateGenerationId=${expected.candidateGenerationId}, " +
                                "dependencyFingerprint=${expected.dependencyFingerprint}, leaseToken=${expected.leaseToken}}",
                            "actual" to "{generation=${actual.generation}, pageVersion=${actual.pageVersion}, " +
                                "artifactPageVersion=${actual.artifactPageVersion}, candidateGenerationId=${actual.candidateGenerationId}, " +
                                "dependencyFingerprint=${actual.dependencyFingerprint}, leaseToken=${actual.leaseToken}}",
                        )
                        rejected(pageKey, description, rejection.reason, rejection.detail)
                    } else {
                        val previous = pages[pageKey]?.toDraft()
                        val updated = ownedPage(pageKey, update(previous?.detachedCopy()))
                        pages = pages.put(pageKey, publishPage(updated))
                        if (!publishLocked(previous, updated, expected, journalCredit = journalCredit)) {
                            restorePageLocked(pageKey, previous)
                            val actual = snapshotLocked(pageKey)
                            lastGuardedWriteRejectionDiagnostic = formatWriteDiagnostic(
                                pageKey,
                                "description" to description,
                                "reason" to "ARTIFACT_PUBLICATION_FAILED",
                                "artifactPublication" to (lastArtifactPublicationRejectionDiagnostic ?: "reason unavailable"),
                                "expected" to "{generation=${expected.generation}, pageVersion=${expected.pageVersion}, " +
                                    "artifactPageVersion=${expected.artifactPageVersion}, candidateGenerationId=${expected.candidateGenerationId}, " +
                                    "dependencyFingerprint=${expected.dependencyFingerprint}, leaseToken=${expected.leaseToken}}",
                                "actual" to "{generation=${actual.generation}, pageVersion=${actual.pageVersion}, " +
                                    "artifactPageVersion=${actual.artifactPageVersion}, candidateGenerationId=${actual.candidateGenerationId}, " +
                                    "dependencyFingerprint=${actual.dependencyFingerprint}, leaseToken=${actual.leaseToken}}",
                            )
                            return@withLock rejected(
                                pageKey,
                                description,
                                "ARTIFACT_PUBLICATION_FAILED",
                                PatchResult.Rejected.Detail.ArtifactPublicationFailed,
                            )
                        }
                        PatchResult.Accepted(snapshotLocked(pageKey))
                    }
                }
            }
        } finally {
            journalCredit?.releaseIfHeld()
        }
    }

    /**
     * Revalidates a BATCH translation result once after a strict page-version rejection. The
     * gate remains authoritative: this method never repairs a token mismatch and only rebases
     * translation-owned fields while the captured run, dependency, artifact version, and BATCH
     * lease still match under the store mutex.
     */
    internal suspend fun reconcileBatchTranslationPageVersionDrift(
        pageKey: String,
        expected: PatchPrecondition,
        candidate: PageTranslation,
    ): TranslationPublicationReconciliation {
        if (defunct) return TranslationPublicationReconciliation.Deferred("store is defunct")
        val journalCredit = reserveJournalCredit(pageKey)
        return try {
            withJournalCapturePermit {
                mutex.withLock {
                    when (val admission = admitMutation()) {
                        MutationAdmission.Granted -> Unit
                        is MutationAdmission.Rejected -> {
                            return@withLock TranslationPublicationReconciliation.Deferred(
                                "${admission.code}: ${admission.message}",
                            )
                        }
                    }

                    val actual = snapshotLocked(pageKey)
                    val lease = pageLeases[pageKey]
                    val identityDrift = when {
                        actual.generation != expected.generation || generation != expected.generation ->
                            "generation changed"
                        expected.leaseToken == null ||
                            lease == null ||
                            lease.origin != PageWriteOrigin.BATCH ||
                            lease.token != expected.leaseToken ||
                            lease.generation != expected.generation ->
                            "active BATCH lease changed"
                        actual.candidateGenerationId != expected.candidateGenerationId ->
                            "candidate generation changed"
                        actual.dependencyFingerprint != expected.dependencyFingerprint ->
                            "dependency fingerprint changed"
                        actual.artifactPageVersion != expected.artifactPageVersion ->
                            "artifact page version changed"
                        expected.blockFingerprints != null &&
                            actual.blockFingerprints != expected.blockFingerprints ->
                            "block fingerprint changed"
                        actual.pageVersion == expected.pageVersion ->
                            "page version no longer differs"
                        candidate.sourceFileName != pageKey ->
                            "candidate page identity changed"
                        candidate.translationStatus != StageStatus.READY ->
                            "candidate is not a completed translation"
                        else -> null
                    }
                    if (identityDrift != null) {
                        return@withLock TranslationPublicationReconciliation.Deferred(identityDrift)
                    }

                    val current = actual.page
                        ?: return@withLock TranslationPublicationReconciliation.Deferred("page missing")
                    if (sameTranslationOutput(current, candidate) && translationOutputIsDurableLocked(pageKey, current)) {
                        val stamped = current.detachedCopy()
                        if (stamped.stampRenderTerminalIfDisplayComplete()) {
                            stamped.updatedAt = System.currentTimeMillis()
                            val owned = ownedPage(pageKey, stamped)
                            val expectedCurrent = actual.toPrecondition()
                            pages = pages.put(pageKey, publishPage(owned))
                            if (!publishLocked(current, owned, expectedCurrent, journalCredit = journalCredit)) {
                                restorePageLocked(pageKey, current)
                                return@withLock TranslationPublicationReconciliation.PublicationFailed(
                                    lastArtifactPublicationRejectionDiagnostic ?: "ARTIFACT_PUBLICATION_FAILED",
                                )
                            }
                            return@withLock TranslationPublicationReconciliation.Rebased(snapshotLocked(pageKey))
                        }
                        return@withLock TranslationPublicationReconciliation.AlreadyDurable(actual)
                    }
                    val rebasedBlocks = rebaseTranslationBlocks(current, candidate)
                        ?: return@withLock TranslationPublicationReconciliation.Deferred(
                            "translation candidate block identity changed",
                        )

                    val updated = current.detachedCopy().apply {
                        blocks = rebasedBlocks
                        translationStatus = candidate.translationStatus
                        translationError = candidate.translationError
                        translationFingerprint = candidate.translationFingerprint
                        translationOrigin = candidate.translationOrigin
                        sourceFingerprint = candidate.sourceFingerprint ?: sourceFingerprint
                        if (stampRenderTerminalIfDisplayComplete()) updatedAt = System.currentTimeMillis()
                    }
                    val owned = ownedPage(pageKey, updated)
                    val expectedCurrent = actual.toPrecondition()
                    pages = pages.put(pageKey, publishPage(owned))
                    if (!publishLocked(current, owned, expectedCurrent, journalCredit = journalCredit)) {
                        restorePageLocked(pageKey, current)
                        TranslationPublicationReconciliation.PublicationFailed(
                            lastArtifactPublicationRejectionDiagnostic ?: "ARTIFACT_PUBLICATION_FAILED",
                        )
                    } else {
                        TranslationPublicationReconciliation.Rebased(snapshotLocked(pageKey))
                    }
                }
            }
        } finally {
            journalCredit?.releaseIfHeld()
        }
    }

    private fun sameTranslationOutput(current: PageTranslation, candidate: PageTranslation): Boolean =
        current.translationStatus == StageStatus.READY &&
            candidate.translationStatus == StageStatus.READY &&
            current.translationError == candidate.translationError &&
            current.translationFingerprint == candidate.translationFingerprint &&
            current.translationOrigin == candidate.translationOrigin &&
            current.blocks.map { it.stableFingerprint() } == candidate.blocks.map { it.stableFingerprint() }

    private fun rebaseTranslationBlocks(
        current: PageTranslation,
        candidate: PageTranslation,
    ): MutableList<TranslationBlock>? {
        if (current.blocks.size != candidate.blocks.size) return null
        val rebased = mutableListOf<TranslationBlock>()
        current.blocks.zip(candidate.blocks).forEach { (currentBlock, candidateBlock) ->
            // TranslationBlock equality verifies every OCR/layout/edit field while we ignore only
            // the translated string. Preserve the current block and overlay that one owned field.
            if (candidateBlock.copy(translation = currentBlock.translation) != currentBlock) return null
            rebased += currentBlock.detachedCopy().apply { translation = candidateBlock.translation }
        }
        return rebased
    }

    /** True only when the artifact manifest points at this exact semantic page snapshot. */
    private fun translationOutputIsDurableLocked(pageKey: String, page: PageTranslation): Boolean {
        val artifactPage = artifactManifest?.pages?.get(pageKey) ?: return false
        val fingerprint = StageFingerprints.pageSnapshot(page)
        // Candidate sidecars are persisted before their manifest pointer. The committed field's
        // historical name is translationFingerprint, but it stores this same page-snapshot hash.
        return artifactPage.candidate?.pageSnapshotFingerprint == fingerprint ||
            artifactPage.committed?.translationFingerprint == fingerprint
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
        val journalCredit = reserveJournalCredit(pageKey)
        return try {
            withJournalCapturePermit {
                mutex.withLock {
                    when (val admission = admitMutation()) {
                        MutationAdmission.Granted -> Unit
                        is MutationAdmission.Rejected -> return@withLock rejected(
                            pageKey,
                            description,
                            "${admission.code}: ${admission.message}",
                        )
                    }
                    val rejection = pageWriteRejection(pageKey, expected)
                    if (rejection != null) {
                        return@withLock rejected(pageKey, description, rejection.reason, rejection.detail)
                    }
                    val previous = pages[pageKey]?.toDraft()
                    val updated = try {
                        ownedPage(pageKey, update(previous?.detachedCopy()))
                    } catch (error: IllegalArgumentException) {
                        return@withLock rejected(pageKey, description, error.message ?: error::class.java.simpleName)
                    } catch (error: IllegalStateException) {
                        return@withLock rejected(pageKey, description, error.message ?: error::class.java.simpleName)
                    }
                    pages = pages.put(pageKey, publishPage(updated))
                    val persisted = persistArtifactMutation(
                        pageKey = pageKey,
                        previous = previous,
                        updated = updated,
                        expected = expected,
                        durableFailure = failure,
                        journalCredit = journalCredit,
                    )
                    if (!persisted) {
                        restorePageLocked(pageKey, previous)
                        return@withLock rejected(pageKey, description, "ARTIFACT_PUBLICATION_FAILED")
                    }
                    statusLedger.recordPageMutationLocked(pageKey, failure)
                    // A retryable/terminal candidate is deliberately not promoted. The
                    // prior committed display remains the reader authority.
                    stateFlow.value = snapshotPages()
                    displaySnapshotLocked(listOf(pageKey))
                    PatchResult.Accepted(snapshotLocked(pageKey))
                }
            }
        } finally {
            journalCredit?.releaseIfHeld()
        }
    }

    /** Compatibility-shaped convenience for non-stage callers; still fenced by a snapshot. */
    suspend fun updatePageFromCurrentSnapshot(
        pageKey: String,
        description: String,
        update: (PageTranslation?) -> PageTranslation,
    ): PatchResult = updatePageGuarded(pageKey, snapshot(pageKey).toPrecondition(), description, update)

    /**
     * TachiyomiAT: fast non-blocking cancellation of in-flight stages in memory.
     * Synchronously flips pages with [isStageRunning] to CANCELLED in [stateFlow] and [_display]
     * without acquiring [mutex] or performing disk I/O. This allows the reader UI (e.g.
     * auto-translation drawer toggle) to clear spinners and dim overlays instantly on the current
     * frame (<16ms) without blocking the Main thread. Durable persistence can follow
     * asynchronously.
     *
     *   pages holding an ACTIVE BATCH-origin stage lease are SKIPPED —
     * this method mutates in-memory state outside the mutex with no lease
     * check, and flipping a batch-owned page desyncs the run's version
     * expectations mid-stage (the flagged lane then fails `checkpointOcr`/
     * `mergeTranslation` on a healthy page). The batch cancel path owns those
     * pages until their leases release. The lease query only takes the lease
     * table's own monitor, never [mutex], so the fast path stays lock-cheap.
     */
    fun fastCancelInFlightStagesInMemory(
        origin: PageWriteOrigin? = null,
        cancellationReason: String = "Translation cancelled",
    ): Int {
        var flipped = 0
        val current = pages
        val hasRunning = current.values.any { it.isStageRunning }
        if (!hasRunning) return 0
        var updatedPages = current
        val changedPageKeys = mutableSetOf<String>()
        current.forEach { (pageKey, page) ->
            if (page.isStageRunning && !page.hasRenderedResult && !page.isStageFailed) {
                if (origin != null && pageLeaseOwner(pageKey)?.let { it != origin } == true) return@forEach
                if (hasActiveBatchStageLease(pageKey)) {
                    logcat(LogPriority.INFO) {
                        "TachiyomiAT cancel skipped: page holds an active BATCH stage lease " +
                            "writer=fastCancelInFlightStagesInMemory pageKey=$pageKey"
                    }
                    // Preserve the page UNCHANGED — the batch owns its state.
                } else {
                    flipped++
                    val updated = page.toDraft().apply {
                        cancelInFlightStages()
                        ocrError = cancellationReason
                        updatedAt = System.currentTimeMillis()
                    }
                    updatedPages = updatedPages.put(pageKey, publishPage(updated))
                    changedPageKeys += pageKey
                }
            }
        }
        // Every running page was batch-leased: nothing flipped, keep the
        // current map/state as-is instead of republishing identical values.
        if (flipped == 0) return 0
        pages = updatedPages
        stateFlow.value = snapshotPages()
        displaySnapshotLocked(changedPageKeys)
        return flipped
    }

    internal suspend fun patchBlock(
        pageKey: String,
        blockIndex: Int,
        expected: PatchPrecondition,
        expectedBlockFingerprint: String,
        expectedTranslation: String? = null,
        expectedUserEditedAt: Long? = null,
        description: String,
        patch: (TranslationBlock) -> TranslationBlock,
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
    private suspend fun applyStagePatch(
        patch: StagePatch,
        description: String,
    ): StagePatchResult {
        if (defunct) return rejectedStage(patch.pageKey, description, "store is defunct")
        val journalCredit = reserveJournalCredit(patch.pageKey)
        return try {
            withJournalCapturePermit {
                mutex.withLock {
                    when (val admission = admitMutation()) {
                        MutationAdmission.Granted -> Unit
                        is MutationAdmission.Rejected -> return@withLock rejectedStage(
                            patch.pageKey,
                            description,
                            "${admission.code}: ${admission.message}",
                        )
                    }
                    val current = pages[patch.pageKey]?.toDraft()
                    when (patch) {
                        is StagePatch.Ocr -> mergeOcrLocked(current, patch.value, description, journalCredit)
                        is StagePatch.Translation -> mergeTranslationLocked(current, patch.value, description, journalCredit)
                        is StagePatch.Render -> mergeRenderLocked(current, patch.value, description, journalCredit)
                    }
                }
            }
        } finally {
            journalCredit?.releaseIfHeld()
        }
    }

    /**
     * Detection/OCR stage merge. The patch must carry the page
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

    private suspend fun mergeOcrLocked(
        current: PageTranslation?,
        patch: OcrStagePatch,
        description: String,
        journalCredit: ChapterJournalCredit?,
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

        val updated = mergeOcrFields(current!!, patch.ocrResult, patch.errorMessage)
        val owned = ownedPage(patch.pageKey, updated)
        pages = pages.put(patch.pageKey, publishPage(owned))
        if (!publishLocked(current, owned, patch.toPrecondition(), journalCredit = journalCredit)) {
            restorePageLocked(patch.pageKey, current)
            return rejectedStage(patch.pageKey, description, "ARTIFACT_PUBLICATION_FAILED")
        }
        return StagePatchResult.Accepted(snapshotLocked(patch.pageKey))
    }

    suspend fun mergeTranslation(
        patch: TranslationStagePatch,
        description: String = "translation stage merge",
    ): StagePatchResult = applyStagePatch(StagePatch.Translation(patch), description)

    suspend fun mergeRender(
        patch: RenderStagePatch,
        description: String = "render stage merge",
    ): StagePatchResult = applyStagePatch(StagePatch.Render(patch), description)

    /**
     * Checkpoints the page's current (already merged) OCR state while the
     * caller still owns its writer lease. [expectedCandidateGenerationId]
     * non-null selects the standard CLOSE/REBASE branch against the active
     * BATCH candidate; null selects the.1 adopt-committed branch
     * (no active candidate; the committed bundle's OCR content fingerprint
     * must equal the live OCR state's). [sourceOrientation] and
     * [sourceSha256] complete the checkpoint's [SourceIdentity] — the sha
     * falls back to the live page's recorded source fingerprint; a checkpoint
     * without a provably complete source identity is rejected (fail closed).
     * Store generation, page version, and lease ownership are checked before
     * durable publication. The checkpoint pointer is installed only after a
     * committed artifact result; the caller releases its lease afterward.
     */
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
    ): CheckpointOcrResult {
        if (defunct) return rejectedCheckpoint(description, "store is defunct")
        val journalCredit = reserveJournalCredit(pageKey)
        return try {
            withJournalCapturePermit {
                mutex.withLock {
                    when (val admission = admitMutation()) {
                        MutationAdmission.Granted -> Unit
                        is MutationAdmission.Rejected -> return@withLock rejectedCheckpoint(
                            description,
                            "${admission.code}: ${admission.message}",
                        )
                    }
                    val artifact = artifactEngine
                    val manifest = artifactManifest
                    if (artifact == null || manifest == null) {
                        return@withLock rejectedCheckpoint(description, "artifact store was not established")
                    }
                    val current = pages[pageKey]
                    val artifactPage = manifest.pages[pageKey]
                    val rejection = when {
                        generation != this.generation -> "generation expected=$generation actual=${this.generation}"
                        current == null -> "page missing"
                        expectedPageVersion != current.pageVersion ->
                            "pageVersion expected=$expectedPageVersion actual=${current.pageVersion}"
                        // 02.1: the lease token is mandatory and must still
                        // fence this page — a released or stolen lease rejects.
                        pageLeases[pageKey] == null -> "page lease required for checkpoint"
                        pageLeases[pageKey]?.token != expectedLeaseToken -> "page lease token changed"
                        expectedArtifactPageVersion != null &&
                            expectedArtifactPageVersion != artifactPage?.pageVersion ->
                            "artifact pageVersion changed"
                        expectedCandidateGenerationId != null &&
                            expectedCandidateGenerationId != artifactPage?.candidate?.generationId ->
                            "candidate generation changed"
                        // 02.1 / C2: no grace clause on the checkpoint path —
                        // a missing dependency fingerprint is a programmer error.
                        expectedCandidateGenerationId != null && expectedDependencyFingerprint == null ->
                            "dependency fingerprint required for checkpoint"
                        expectedCandidateGenerationId != null &&
                            artifactPage?.candidate?.dependencyFingerprint != expectedDependencyFingerprint ->
                            "candidate dependency fingerprint changed"
                        expectedCandidateGenerationId == null && artifactPage?.candidate != null ->
                            "active candidate present; standard checkpoint branch required"
                        expectedPriorOcrFingerprints != null &&
                            expectedPriorOcrFingerprints != current.ocrBlockFingerprints() ->
                            "prior OCR identity changed"
                        else -> null
                    }
                    if (rejection != null) return@withLock rejectedCheckpoint(description, rejection)
                    val live = current?.toDraft() ?: return@withLock rejectedCheckpoint(description, "page missing")
                    // The checkpoint records which mask basis produced the OCR
                    // masks. It must not promote the cleaned-output revision:
                    // that revision is stamped only when inpaint publishes the
                    // corresponding cleaned image, and display readiness relies
                    // on it to reject stale cleaned bytes.
                    val inpaintMaskRevision = PageTranslation.CURRENT_INPAINT_REVISION
                    val resolvedSourceSha256 = sourceSha256 ?: live.sourceFingerprint
                    val sourceIdentity = SourceIdentity(
                        pageKey = pageKey,
                        sha256 = resolvedSourceSha256,
                        width = live.imgWidth.takeIf { it > 0f }?.toInt(),
                        height = live.imgHeight.takeIf { it > 0f }?.toInt(),
                        orientation = sourceOrientation,
                    )
                    if (!sourceIdentity.isComplete) {
                        return@withLock rejectedCheckpoint(description, "incomplete source identity for checkpoint")
                    }
                    val snapshotFingerprint = StageFingerprints.pageSnapshot(live)
                    val snapshotFileName = artifact.ocrStageSnapshotName(pageKey, snapshotFingerprint)
                    val naturalPageIndex = artifactPage?.naturalPageIndex
                    val checkpoint = PageOcrCheckpoint(
                        pageKey = pageKey,
                        naturalPageIndex = naturalPageIndex,
                        sourceIdentity = sourceIdentity,
                        detectionFingerprint = live.detectionFingerprint,
                        ocrFingerprint = live.ocrFingerprint.orEmpty(),
                        ocrContentFingerprint = pageOcrContentFingerprint(
                            pageKey,
                            live,
                            naturalPageIndex,
                            sourceOrientation,
                            inpaintMaskRevision,
                        ),
                        ocrPageSnapshotPointer = SidecarPointer(
                            fileName = snapshotFileName,
                            schemaVersion = 1,
                            contentFingerprint = snapshotFingerprint,
                        ),
                        inpaintMaskRevision = inpaintMaskRevision,
                        priorCommittedDisplay = artifactPage?.committed?.let { committed ->
                            CommittedDisplayRef(
                                generationId = committed.generationId,
                                bundleFingerprint = committed.bundleFingerprint,
                                pageSnapshotFileName = committed.pageSnapshotFileName,
                            )
                        },
                        producedByOrigin = pageLeases.getValue(pageKey).origin.toArtifactOrigin(),
                        producerGenerationId = expectedCandidateGenerationId,
                        checkpointedAtEpochMs = nowEpochMs,
                    )
                    val outcome = artifact.checkpointOcr(
                        manifest = manifest,
                        pageKey = pageKey,
                        expectedPageVersion = artifactPage?.pageVersion ?: 0L,
                        expectedDependencyFingerprint = expectedDependencyFingerprint,
                        ocrSnapshot = live,
                        checkpoint = checkpoint,
                        mode = mode,
                        nowEpochMs = nowEpochMs,
                    )
                    when (outcome) {
                        is ChapterArtifactEngine.TransactionOutcome.Committed -> {
                            artifactManifest = outcome.manifest
                            stateFlow.value = snapshotPages()
                            displaySnapshotLocked(listOf(pageKey))
                            capturePageMutationLocked(
                                pageKey = pageKey,
                                page = live,
                                expected = PatchPrecondition(
                                    generation = generation,
                                    pageVersion = expectedPageVersion,
                                    leaseToken = expectedLeaseToken,
                                    candidateGenerationId = expectedCandidateGenerationId,
                                    dependencyFingerprint = expectedDependencyFingerprint,
                                    artifactPageVersion = expectedArtifactPageVersion,
                                ),
                                durableFailure = null,
                                credit = journalCredit,
                                artifactContentHash = snapshotFingerprint,
                            )
                            CheckpointOcrResult.Committed(
                                snapshot = snapshotLocked(pageKey),
                                manifest = outcome.manifest,
                                ocrContentFingerprint = checkpoint.ocrContentFingerprint,
                            )
                        }
                        is ChapterArtifactEngine.TransactionOutcome.Rejected ->
                            rejectedCheckpoint(description, outcome.reason)
                    }
                }
            }
        } finally {
            journalCredit?.releaseIfHeld()
        }
    }

    /**
     * The semantic `PageOcrContentFingerprint` of the page's current OCR
     * state (  encoding). Transaction identities
     * (versions, generations, timestamps, file names) are excluded by the
     * builder.
     *
     * 1: [pageKey] MUST be the transaction pageKey, never
     * [PageTranslation.sourceFileName] — the.1 adopt side
     * ([ChapterArtifactEngine.committedOcrContentFingerprint]) canonicalizes
     * with `checkpoint.sourceIdentity.pageKey` (the transaction pageKey), so
     * both sides of the drift comparison must share one pageKey source.
     */
    private fun pageOcrContentFingerprint(
        pageKey: String,
        page: PageTranslationView,
        naturalPageIndex: Int?,
        sourceOrientation: String?,
        inpaintMaskRevision: Int,
    ): String = StageFingerprints.pageOcrContentFingerprint(
        pageKey = pageKey,
        naturalPageIndex = naturalPageIndex,
        sourceSha256 = page.sourceFingerprint.orEmpty(),
        sourceWidth = page.imgWidth.toInt(),
        sourceHeight = page.imgHeight.toInt(),
        sourceOrientation = sourceOrientation.orEmpty(),
        detectionFingerprint = page.detectionFingerprint,
        ocrFingerprint = page.ocrFingerprint.orEmpty(),
        textless = page.isTextlessTerminal,
        inpaintMaskRevision = inpaintMaskRevision,
        blocks = StageFingerprints.pageOcrContentBlocks(page),
        inpaintMaskBoxes = page.inpaintMaskBoxes,
    )

    private fun rejectedCheckpoint(description: String, reason: String): CheckpointOcrResult.Rejected {
        logcat(LogPriority.WARN) {
            "TachiyomiAT ocr checkpoint rejected: generation=$generation " +
                "operation=$description reason=$reason"
        }
        return CheckpointOcrResult.Rejected(reason)
    }

    private suspend fun mergeTranslationLocked(
        current: PageTranslation?,
        patch: TranslationStagePatch,
        description: String,
        journalCredit: ChapterJournalCredit?,
    ): StagePatchResult {
        if (patch.sourceFingerprint != null && !patch.sourceFingerprint.isSha256Hex()) {
            return rejectedStage(patch.pageKey, description, "invalid observed source fingerprint")
        }
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
            // Provenance preconditions are opt-in. Both fields default to null,
            // so older patches keep byte-identical behavior (no extra manifest
            // reads or new
            // rejection class). Non-null fields are validated against the
            // manifest's CURRENTLY frozen profile / envelope-plan pointers;
            // a mismatch rejects the WHOLE page patch (stale-profile and
            // stale-plan protection; a rejected commit never advances any
            // frontier or page state).
            ?: translationProvenanceRejection(patch)
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
        page.sourceFingerprint = patch.sourceFingerprint ?: page.sourceFingerprint
        page.errorMessage = patch.errorMessage
        if (page.stampRenderTerminalIfDisplayComplete()) page.updatedAt = System.currentTimeMillis()
        val updated = ownedPage(patch.pageKey, page)
        pages = pages.put(patch.pageKey, publishPage(updated))
        if (!publishLocked(current, updated, patch.toPrecondition(), journalCredit = journalCredit)) {
            restorePageLocked(patch.pageKey, current)
            return rejectedStage(patch.pageKey, description, "ARTIFACT_PUBLICATION_FAILED")
        }
        return StagePatchResult.Accepted(snapshotLocked(patch.pageKey), applied)
    }

    /**
     * The envelope-plan precondition is active only when the patch carries its
     * nullable plan fingerprint;
     * a fully-null patch returns `null` before touching the manifest, so
     * every caller without the fingerprint keeps byte-identical merge behavior.
     *
     *  - `envelopePlanFingerprint` must equal the manifest's current
     *    `envelopePlan` pointer content fingerprint  — a commit
     *    built from a superseded plan is rejected.
     *
     * A rejected patch never mutates page state or advances a frontier.
     */
    private fun translationProvenanceRejection(patch: TranslationStagePatch): String? {
        if (patch.envelopePlanFingerprint == null) return null
        val manifest = artifactManifest
        patch.envelopePlanFingerprint?.let { expected ->
            val plan = manifest?.envelopePlan?.contentFingerprint
            if (plan != expected) {
                return "translation provenance rejected: envelope plan changed " +
                    "(expected=$expected current=${plan ?: "<absent>"})"
            }
        }
        return null
    }

    private suspend fun mergeRenderLocked(
        current: PageTranslation?,
        patch: RenderStagePatch,
        description: String,
        journalCredit: ChapterJournalCredit?,
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
            ?: current?.takeIf {
                patch.renderStatus == StageStatus.READY &&
                    !it.isCleanedImageReady &&
                    !it.originalImageFallback
            }
                ?.let { "cleaned image is not current for render completion" }
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
        pages = pages.put(patch.pageKey, publishPage(updated))
        if (!publishLocked(current, updated, patch.toPrecondition(), journalCredit = journalCredit)) {
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

    private fun RenderStagePatch.toPrecondition() = PatchPrecondition(
        generation = generation,
        pageVersion = expectedPageVersion ?: 0L,
        leaseToken = expectedLeaseToken,
        candidateGenerationId = expectedCandidateGenerationId,
        dependencyFingerprint = expectedDependencyFingerprint,
        artifactPageVersion = expectedArtifactPageVersion,
    )

    private data class PageWriteRejection(
        val reason: String,
        val detail: PatchResult.Rejected.Detail? = null,
    )

    private fun pageWriteRejection(pageKey: String, expected: PatchPrecondition): PageWriteRejection? {
        val current = pages[pageKey]
        val artifactPage = artifactManifest?.pages?.get(pageKey)
        return when {
            expected.generation != generation ->
                PageWriteRejection("generation expected=${expected.generation} actual=$generation")
            expected.pageVersion != (current?.pageVersion ?: 0L) -> {
                val actualVersion = current?.pageVersion ?: 0L
                PageWriteRejection(
                    reason = "pageVersion expected=${expected.pageVersion} actual=$actualVersion",
                    detail = PatchResult.Rejected.Detail.PageVersionMismatch(expected.pageVersion, actualVersion),
                )
            }
            expected.artifactPageVersion != null && expected.artifactPageVersion != artifactPage?.pageVersion ->
                PageWriteRejection("artifact pageVersion expected=${expected.artifactPageVersion} actual=${artifactPage?.pageVersion}")
            expected.candidateGenerationId != null &&
                expected.candidateGenerationId != artifactPage?.candidate?.generationId ->
                PageWriteRejection(
                    "candidate generation expected=${expected.candidateGenerationId} " +
                        "actual=${artifactPage?.candidate?.generationId}",
                )
            expected.dependencyFingerprint != null &&
                artifactPage?.candidate?.let { candidate ->
                    expected.dependencyFingerprint != candidate.dependencyFingerprint
                } == true ->
                PageWriteRejection(
                    "candidate dependency fingerprint expected=${expected.dependencyFingerprint} " +
                        "actual=${artifactPage?.candidate?.dependencyFingerprint}",
                )
            expected.blockFingerprints != null &&
                expected.blockFingerprints != current?.blockFingerprints().orEmpty() ->
                PageWriteRejection("block fingerprint changed")
            expected.leaseToken != null && pageLeases[pageKey]?.token != expected.leaseToken -> {
                val actualToken = pageLeases[pageKey]?.token
                PageWriteRejection(
                    reason = "page lease token expected=${expected.leaseToken} actual=$actualToken",
                    detail = PatchResult.Rejected.Detail.PageLeaseTokenMismatch(expected.leaseToken, actualToken),
                )
            }
            expected.leaseToken == null && pageLeases[pageKey] != null ->
                PageWriteRejection("page lease token required")
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

    internal suspend fun updatePage(pageKey: String, update: (PageTranslation?) -> PageTranslation) {
        if (defunct) {
            rejected(pageKey, "updatePage", "store is defunct")
            return
        }
        val context = currentCoroutineContext()[GenerationContext]
        val expected = mutex.withLock {
            if (context?.store === this && context.generation != generation) {
                null
            } else if (artifactManifest != null && pageLeases[pageKey] != null) {
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
    private fun nextVersion(): Long = ++nextPageVersion

    internal fun ownedPage(pageKey: String, candidate: PageTranslation): PageTranslation =
        candidate.detachedCopy().apply {
            sourceFileName = sourceFileName ?: pageKey
            updatedAt = System.currentTimeMillis()
            runGeneration = generation
            pageVersion = nextVersion()
        }

    private suspend fun publishLocked(
        previous: PageTranslation?,
        updated: PageTranslation,
        expected: PatchPrecondition? = null,
        durableFailure: DurableFailureMetadata? = null,
        journalCredit: ChapterJournalCredit? = null,
        artifactLease: AttemptArtifactLease? = null,
    ): Boolean {
        val pageKey = updated.sourceFileName ?: ""
        // Active reader stores are display-first: publish the complete live
        // snapshot and enqueue the artifact transaction for the IO worker.
        // Durable failure records remain synchronous so cancellation/retry
        // semantics never report a failure before its ledger is recorded.
        if (lazyPersistenceEnabled && durableFailure == null) {
            val accepted = publishLazyLocked(pageKey, previous, updated, expected, journalCredit, artifactLease)
            if (accepted) statusLedger.recordPageMutationLocked(pageKey, durableFailure)
            return accepted
        }
        val isDurable = shouldPersistUpdate(previous, updated)
        var actuallyPersisted = false
        val artifactAccepted = if (isDurable || artifactManifest?.pages?.containsKey(pageKey) != true) {
            val ok = persistArtifactMutation(
                pageKey = pageKey,
                previous = previous,
                updated = updated,
                expected = expected,
                durableFailure = durableFailure,
                journalCredit = journalCredit,
            )
            if (ok) actuallyPersisted = true
            ok
        } else {
            true
        }
        if (!artifactAccepted) return false
        if (actuallyPersisted) {
            artifactLease?.stamp(this, pageKey, snapshotLocked(pageKey))
        }
        statusLedger.recordPageMutationLocked(pageKey, durableFailure)
        promoteDisplayIfReadyLocked(pageKey, updated)
        stateFlow.value = snapshotPages()
        displaySnapshotLocked(listOf(pageKey))
        return true
    }

    private fun publishLazyLocked(
        pageKey: String,
        previous: PageTranslation?,
        updated: PageTranslation,
        expected: PatchPrecondition?,
        journalCredit: ChapterJournalCredit?,
        artifactLease: AttemptArtifactLease? = null,
    ): Boolean {
        val snapshot = snapshotLocked(pageKey)
        // Preserve the pre-existing durability gate: transient RUNNING/PENDING
        // emissions update the live StateFlows but do not become durable page
        // candidates when the manifest already knows this page. A durable
        // publication already queued for this page remains authoritative until
        // the next durable update replaces it.
        val shouldQueueArtifact = shouldPersistUpdate(previous, updated) ||
            artifactManifest?.pages?.containsKey(pageKey) != true
        if (shouldQueueArtifact) {
            val existingCredit = writeDrain.pendingLazyMutations[pageKey]?.journalCredit
            val retainedCredit = existingCredit ?: journalCredit?.takeIf { it.retain() }
            writeDrain.pendingLazyMutations[pageKey] = PendingLazyMutation(
                pageKey = pageKey,
                previous = previous?.detachedCopy(),
                updated = updated.detachedCopy(),
                expected = PatchPrecondition(
                    generation = snapshot.generation,
                    pageVersion = snapshot.pageVersion,
                    blockFingerprints = snapshot.blockFingerprints,
                    leaseToken = snapshot.leaseToken ?: expected?.leaseToken,
                    candidateGenerationId = snapshot.candidateGenerationId,
                    dependencyFingerprint = snapshot.dependencyFingerprint,
                    artifactPageVersion = snapshot.artifactPageVersion,
                ),
                generation = snapshot.generation,
                journalCredit = retainedCredit,
                artifactLease = artifactLease,
            )
        }
        promoteDisplayIfReadyLocked(pageKey, updated)
        stateFlow.value = snapshotPages()
        displaySnapshotLocked(listOf(pageKey))
        if (shouldQueueArtifact) writeDrain.scheduleDrain()
        return true
    }

    private fun restorePageLocked(pageKey: String, previous: PageTranslation?) {
        pages = if (previous == null) pages.remove(pageKey) else pages.put(pageKey, publishPage(previous))
        stateFlow.value = snapshotPages()
        displaySnapshotLocked(listOf(pageKey))
    }

    /**
     * Production artifact publication for mutable page emissions. Delegates to
     * the publication bridge (Phase 7 S4), which owns the registration CAS and
     * the candidate open/cancel/persist/promote/failure orchestration. Caller
     * holds [mutex]; the lazy flusher (Phase 7 S5) re-enters this shell from
     * inside its barrier-held mutex.
     */
    internal suspend fun persistArtifactMutation(
        pageKey: String,
        previous: PageTranslation?,
        updated: PageTranslation,
        expected: PatchPrecondition? = null,
        durableFailure: DurableFailureMetadata? = null,
        journalCredit: ChapterJournalCredit? = null,
        journalFlushBatch: JournalFlushBatch? = null,
    ): Boolean = publicationBridge.persistArtifactMutation(
        pageKey = pageKey,
        previous = previous,
        updated = updated,
        expected = expected,
        durableFailure = durableFailure,
        journalCredit = journalCredit,
        journalFlushBatch = journalFlushBatch,
    )

    /**
     * Lazily creates the artifact store for a chapter that has no manifest
     * yet; delegates to the publication bridge. Suspending: acquires the
     * per-artifact open lock as a coroutine (store mutex -> artifact-open
     * lock, never reversed).
     */
    internal suspend fun ensureArtifactStore(): Boolean = publicationBridge.ensureArtifactStore()

    /**
     * Atomic committed-display promotion: when the page just reached a fully
     * displayable state (cleaned image file published and verified upstream by
     * [CleanedImagePublisher], translation READY/PARTIAL, render READY, some
     * translated text), the committed pointer swaps to a frozen snapshot of
     * exactly that state. A superseded bundle's cleaned file is retained until
     * the pipeline drains it, never deleted under a live committed pointer.
     */
    internal fun promoteDisplayIfReadyLocked(pageKey: String, updated: PageTranslation) {
        val published = pages[pageKey] ?: return
        if (pageKey.isEmpty() || (!published.hasRenderedResult && !published.isTextlessTerminal)) return
        val fingerprint = displayFingerprintOf(published)
        val existing = committedDisplay[pageKey]
        if (existing != null && existing.displayFingerprint == fingerprint) return
        if (existing != null) {
            existing.page.cleanedImageName
                ?.takeIf { it != published.cleanedImageName && it.isNotEmpty() }
                ?.let {
                    retiredCleanedImages
                        .computeIfAbsent(pageKey) { ConcurrentHashMap.newKeySet() }
                        .add(it)
                }
        }
        committedDisplay = committedDisplay.put(
            pageKey,
            CommittedPageDisplay(
                page = published,
                pageVersion = published.pageVersion,
                displayFingerprint = fingerprint,
                promotedAtEpochMs = published.updatedAt,
            ),
        )
    }

    /**
     * Atomically updates only changed display keys; all other immutable values are shared.
     * The fast-cancel path also calls this off-mutex, so the flow update must stay retry-safe.
     */
    internal fun displaySnapshotLocked(
        changedPageKeys: Iterable<String>,
    ) {
        val keys = changedPageKeys.toList()
        _display.update { current ->
            var updated = current
            keys.forEach { pageKey ->
                val page = pages[pageKey]
                updated = if (page == null) {
                    updated.remove(pageKey)
                } else {
                    updated.put(pageKey, committedDisplay[pageKey]?.page ?: page)
                }
            }
            updated
        }
    }

    private fun displayFingerprintOf(page: PageTranslationView): String {
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
    fun resolveDisplayPage(pageKey: String): PageTranslationView? =
        committedDisplay[pageKey]?.page ?: pages[pageKey]

    /**
     * Lazily loads full page snapshot (including text blocks) from durable storage
     * if the page is currently backed by a synthesized or empty placeholder.
     */
    suspend fun getOrLoadPageSnapshot(pageKey: String): PageTranslation? = withJournalCapturePermit {
        mutex.withLock {
            val current = pages[pageKey]
            if (current != null && current.blocks.isNotEmpty()) {
                return@withLock current.toDraft()
            }
            val record = artifactManifest?.pages?.get(pageKey)
            val snapshotFileName = record?.candidate?.pageSnapshotFileName
                ?: record?.committed?.pageSnapshotFileName
            val store = artifactEngine
            if (snapshotFileName != null && store != null) {
                val loaded = store.readPageSnapshot(snapshotFileName)
                if (loaded != null) {
                    val published = publishPage(loaded)
                    pages = pages.put(pageKey, published)
                    stateFlow.value = snapshotPages()
                    if (record?.committed != null) {
                        committedDisplay = committedDisplay.put(
                            pageKey,
                            CommittedPageDisplay(
                                page = published,
                                pageVersion = loaded.pageVersion,
                                displayFingerprint = displayFingerprintOf(loaded),
                                promotedAtEpochMs = loaded.updatedAt,
                            ),
                        )
                    }
                    displaySnapshotLocked(listOf(pageKey))
                    return@withLock loaded
                }
            }
            current?.toDraft()
        }
    }

    /** The frozen committed display bundle for [pageKey], if one exists. */
    internal fun committedDisplayPage(pageKey: String): PageTranslationView? =
        committedDisplay[pageKey]?.page

    /**
     * True while [name] still backs the committed display bundle (or its
     * retained predecessor) for [pageKey]; such files must not be deleted
     * before a newer bundle promotes.
     */
    fun mayDeleteCleanedImage(pageKey: String, name: String): Boolean {
        if (pages[pageKey]?.cleanedImageName == name || committedDisplay[pageKey]?.page?.cleanedImageName == name) {
            return false
        }
        // Rekey can leave an already-queued retirement callback carrying the old
        // pageKey after ownership has moved. Check every live/committed/retained
        // reference before deleting the shared image or its identity sidecar.
        return name !in referencedCleanedImageNames()
    }

    /**
     * Point-in-time store authorization to physically delete a cleaned image
     * name: the same predicate as [mayDeleteCleanedImage], evaluated under
     * [mutex]. The mutex protects ONLY this decision — the caller performs
     * the physical delete AFTER this returns, outside the store lock, so the
     * delete can interleave with later mutations that make [name] reachable.
     * An authorized delete is therefore safe only for names with INDEPENDENT
     * never-accepted provenance (a fresh private versioned name whose single
     * possible acceptance just returned Rejected); for any name whose
     * acceptance history is uncertain the caller must retain it regardless of
     * this answer.
     */
    suspend fun authorizeCleanedImageDeletion(pageKey: String, name: String): Boolean = mutex.withLock {
        mayDeleteCleanedImage(pageKey, name)
    }

    /** Protect a companion image still owned by a different page after page reset/deletion. */
    fun isCleanedImageReferencedByAnotherPage(pageKey: String, name: String): Boolean =
        pages.any { (owner, page) -> owner != pageKey && page.cleanedImageName == name } ||
            committedDisplay.any { (owner, page) -> owner != pageKey && page.page.cleanedImageName == name } ||
            retiredCleanedImages.any { (owner, names) -> owner != pageKey && name in names } ||
            artifactManifest?.pages?.any { (owner, record) ->
                owner != pageKey &&
                    (record.committed?.displayBase?.fileName == name || record.previousCommitted?.displayBase?.fileName == name)
            } == true

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
        withJournalCapturePermit {
            mutex.withLock {
                if (committedDisplay.containsKey(pageKey)) {
                    committedDisplay = committedDisplay.remove(pageKey)
                    retiredCleanedImages.remove(pageKey)
                    demoteArtifactPageLocked(pageKey)
                    logcat(LogPriority.INFO) {
                        "TachiyomiAT committed display demoted: pageKey=$pageKey reason=$reason"
                    }
                    displaySnapshotLocked(listOf(pageKey))
                }
            }
        }
    }

    /**
     *   retires the artifact manifest's `activeRun` pointer under the
     * store mutex (the store-side façade over
     * [ChapterArtifactEngine.retireActiveRun], mirroring the
     * [demoteCommittedDisplay] transaction idiom: CAS against the durable
     * manifest, façade snapshot refreshed only on Committed). Reserved for user
     * resets — a reset must retire the recorded run so a future dispatch can
     * never short-circuit on its COMPLETE record. Returns true when the pointer
     * is durably gone (or was never set); a rejected transaction keeps the run
     * record owned and returns false.
     */
    suspend fun retireActiveRun(reason: String): Boolean {
        if (defunct) return false
        return withJournalCapturePermit {
            mutex.withLock {
                val artifact = artifactEngine ?: return@withLock false
                val manifest = artifactManifest ?: return@withLock false
                when (val outcome = artifact.retireActiveRun(manifest, reason)) {
                    is ChapterArtifactEngine.TransactionOutcome.Committed -> {
                        artifactManifest = outcome.manifest
                        logcat(LogPriority.INFO) {
                            "TachiyomiAT artifact active run retired: chapter=${manifest.chapterKey} reason=$reason"
                        }
                        true
                    }
                    is ChapterArtifactEngine.TransactionOutcome.Rejected -> {
                        logcat(LogPriority.WARN) {
                            "TachiyomiAT artifact active run retirement rejected: " +
                                "chapter=${manifest.chapterKey} reject=${outcome.reason} resetReason=$reason"
                        }
                        false
                    }
                }
            }
        }
    }

    internal fun cancelArtifactCandidateLocked(pageKey: String): Boolean {
        val store = artifactEngine ?: return true
        val manifest = artifactManifest ?: return true
        val candidate = manifest.pages[pageKey]?.candidate ?: return true
        val outcome = store.cancelLiveCandidate(manifest, pageKey, candidate.generationId)
        if (outcome is ChapterArtifactEngine.TransactionOutcome.Committed) {
            artifactManifest = outcome.manifest
            return true
        } else if (outcome is ChapterArtifactEngine.TransactionOutcome.Rejected) {
            logcat(LogPriority.WARN) {
                "TachiyomiAT artifact candidate cancel rejected: pageKey=$pageKey reason=${outcome.reason}"
            }
        }
        return false
    }

    private fun demoteArtifactPageLocked(pageKey: String) {
        val store = artifactEngine ?: return
        val manifest = artifactManifest ?: return
        val outcome = store.demoteLivePage(manifest, pageKey)
        when (outcome) {
            is ChapterArtifactEngine.TransactionOutcome.Committed -> artifactManifest = outcome.manifest
            is ChapterArtifactEngine.TransactionOutcome.Rejected -> logcat(LogPriority.WARN) {
                "TachiyomiAT artifact display demotion rejected: pageKey=$pageKey reason=${outcome.reason}"
            }
        }
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

    private fun PageSnapshot.toPrecondition() = PatchPrecondition(
        generation = generation,
        pageVersion = pageVersion,
        blockFingerprints = blockFingerprints,
        leaseToken = leaseToken,
        candidateGenerationId = candidateGenerationId,
        dependencyFingerprint = dependencyFingerprint,
        artifactPageVersion = artifactPageVersion,
    )

    private fun rejected(
        pageKey: String,
        description: String,
        reason: String,
        detail: PatchResult.Rejected.Detail? = null,
    ): PatchResult.Rejected {
        logcat(LogPriority.WARN) {
            "TachiyomiAT store patch rejected: pageKey=$pageKey generation=$generation " +
                "operation=$description reason=$reason"
        }
        return PatchResult.Rejected(reason, detail)
    }

    private fun MutationAdmission.Rejected.toPatchRejectionDetail(): PatchResult.Rejected.Detail? =
        when (code) {
            MutationAdmission.Rejected.Code.ARTIFACT_PUBLICATION_FAILED ->
                PatchResult.Rejected.Detail.ArtifactPublicationFailed
            MutationAdmission.Rejected.Code.STORE_DEFUNCT,
            MutationAdmission.Rejected.Code.FENCE_REJECTED,
            -> null
        }

    /** The authoritative values are immutable, so StateFlow snapshots share the persistent map. */
    internal fun snapshotPages(): PersistentMap<String, PublishedPageTranslation> = pages

    internal fun publishPage(draft: PageTranslation): PublishedPageTranslation {
        testSeams.countPublishedPageCopy()
        return draft.toPublishedPage()
    }

    /** Durable translation status derived from the committed artifact state. */
    fun artifactStatus(): Translation.State? = statusProjector.artifactStatus()

    fun readReusableProfile(): ChapterTranslationProfile? {
        val artifact = artifactEngine ?: return null
        val manifest = artifact.readManifest() ?: return null
        val pointer = manifest.profile ?: return null
        if (!pointer.isWellFormed()) return null
        val read = artifact.readSidecarDocument(
            pointer = pointer.toSidecarPointer(),
            serializer = ChapterTranslationProfile.serializer(),
            currentSchemaVersion = ChapterTranslationProfile.SCHEMA_VERSION,
            expectedKind = ChapterTranslationProfile.KIND,
            schemaVersionOf = { it.schemaVersion },
            kindOf = { it.kind },
            isValid = { it.isSemanticallyValid },
        )
        val profile = (read as? SidecarRead.Usable<*>)?.document as? ChapterTranslationProfile ?: return null
        if (pointer.contentFingerprint != profile.contentFingerprint ||
            pointer.version != profile.version
        ) {
            return null
        }
        if (StageFingerprints.profileContentFingerprint(profile) != profile.contentFingerprint) {
            return null
        }
        return profile
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
        // publishes durable cancellation through the artifact bridge.
        // 1. Completed display output, including textless pages using the source image.
        if (updated.hasRenderedResult || updated.isTextlessTerminal) return true
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

    // These lifecycle methods preserve a single persistence boundary for
    // reset, resolver, and batch callers. The drain owns queued lazy image
    // writes; artifact snapshots are published by the artifact engine inline.

    suspend fun flush() = writeDrain.flush()

    suspend fun closeAndFlush() {
        try {
            writeDrain.closeAndFlush {
                journalWriter?.drainAndClose()
            }
        } finally {
            artifactEngine?.cancelRetentionWorkAndJoin()
        }
    }

    fun close() = writeDrain.close {
        try {
            journalWriter?.drainAndClose()
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
                        ?: eu.kanade.translation.persistence.artifact.RetentionResult(0, emptyList())
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
                        ?: eu.kanade.translation.persistence.artifact.RetentionResult(0, emptyList())
                }
            },
        )
    }

    /**
     * Store-to-drain queue boundary. Callers hold [mutex]; this handoff is
     * synchronous and does not acquire another state lock.
     */
    internal fun schedulePendingLazyDrain() = writeDrain.scheduleDrain()

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
            journalStorageFactory: ((File, File) -> ChapterJournalStorage)? = null,
        ): ChapterTranslationStore = ChapterStoreOpener.openArtifactSuspend(
            parent,
            fileName,
            privateStorageRoot,
            privateStorageIdentity,
            journalStorageFactory,
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
internal fun mergeOcrFields(
    current: PageTranslation,
    result: PageTranslation,
    errorMessage: String? = null,
): PageTranslation {
    val updated = current.detachedCopy()
    if (result.ocrStatus == StageStatus.FAILED) {
        // Failure updates diagnostics only; preserve reusable page data.
        updated.ocrStatus = StageStatus.FAILED
        updated.errorMessage = errorMessage ?: result.activeError
    } else {
        val preserveReusableInpaint =
            result.inpaintStatus == StageStatus.PENDING &&
                result.sourceFingerprint != null &&
                result.sourceFingerprint == current.sourceFingerprint &&
                result.detectionFingerprint != null &&
                result.detectionFingerprint == current.detectionFingerprint &&
                current.isCleanedImageReady
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
        if (result.ocrStatus == StageStatus.READY &&
            result.blocks.isEmpty() &&
            result.translationStatus == StageStatus.SKIPPED
        ) {
            updated.translationStatus = StageStatus.SKIPPED
        }
        updated.detectionCount = result.detectionCount
        updated.ocrBlockCount = result.ocrBlockCount
        updated.recognitionEngine = result.recognitionEngine
        updated.decodeSampleSize = result.decodeSampleSize
        updated.imgWidth = result.imgWidth
        updated.imgHeight = result.imgHeight
        updated.originalImgWidth = result.originalImgWidth
        updated.originalImgHeight = result.originalImgHeight
        if (result.cleanedImageName != null) {
            updated.cleanedImageName = result.cleanedImageName
            updated.cleanedImageContentHash = result.cleanedImageContentHash
        }
        updated.inpaintRevision = result.inpaintRevision
        updated.inpaintingModeUsed = result.inpaintingModeUsed
        updated.detectionFingerprint = result.detectionFingerprint
        updated.ocrFingerprint = result.ocrFingerprint
        if (preserveReusableInpaint) {
            // OCR-only updates retain cleaned data when source and detector match.
            updated.inpaintMaskBoxes = current.inpaintMaskBoxes
            updated.inpaintStatus = current.inpaintStatus
            updated.inpaintError = current.inpaintError
            updated.inpaintRevision = current.inpaintRevision
            updated.inpaintingModeUsed = current.inpaintingModeUsed
            updated.inpaintFingerprint = current.inpaintFingerprint
            updated.cleanedImageName = current.cleanedImageName
            updated.cleanedImageContentHash = current.cleanedImageContentHash
        }
        errorMessage?.let { updated.errorMessage = it }
    }
    return updated
}
