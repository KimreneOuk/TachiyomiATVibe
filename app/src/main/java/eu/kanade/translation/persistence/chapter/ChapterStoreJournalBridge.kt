package eu.kanade.translation.persistence.chapter

import eu.kanade.translation.diagnostics.TranslationTrace
import eu.kanade.translation.diagnostics.TranslationTraceLane
import eu.kanade.translation.diagnostics.TranslationTraceMode
import eu.kanade.translation.diagnostics.TranslationTraceOutcome
import eu.kanade.translation.diagnostics.TranslationTraceStage
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.PageTranslationView
import eu.kanade.translation.model.PublishedPageTranslation
import eu.kanade.translation.model.hasRenderedResult
import eu.kanade.translation.model.isTextlessTerminal
import eu.kanade.translation.persistence.artifact.ChapterArtifactEngine
import eu.kanade.translation.persistence.artifact.ChapterArtifactManifest
import eu.kanade.translation.persistence.artifact.DurableFailureMetadata
import eu.kanade.translation.persistence.artifact.StageFingerprints
import eu.kanade.translation.persistence.journal.ChapterJournalBulkRecord
import eu.kanade.translation.persistence.journal.ChapterJournalCaptureCoordinator
import eu.kanade.translation.persistence.journal.ChapterJournalCaptureCoordinators
import eu.kanade.translation.persistence.journal.ChapterJournalCompaction
import eu.kanade.translation.persistence.journal.ChapterJournalCredit
import eu.kanade.translation.persistence.journal.ChapterJournalEpochCoverage
import eu.kanade.translation.persistence.journal.ChapterJournalEpochGcState
import eu.kanade.translation.persistence.journal.ChapterJournalFormat
import eu.kanade.translation.persistence.journal.ChapterJournalGcMode
import eu.kanade.translation.persistence.journal.ChapterJournalInventorySnapshot
import eu.kanade.translation.persistence.journal.ChapterJournalPageOutcome
import eu.kanade.translation.persistence.journal.ChapterJournalRecord
import eu.kanade.translation.persistence.journal.ChapterJournalReplayReducer
import eu.kanade.translation.persistence.journal.ChapterJournalReplayResult
import eu.kanade.translation.persistence.journal.ChapterJournalSnapshotCandidate
import eu.kanade.translation.persistence.journal.ChapterJournalSnapshotManager
import eu.kanade.translation.persistence.journal.ChapterJournalStorage
import eu.kanade.translation.persistence.journal.ChapterJournalWriter
import eu.kanade.translation.persistence.journal.FileChapterJournalSnapshotStorage
import eu.kanade.translation.persistence.journal.FileChapterJournalStorage
import kotlinx.collections.immutable.PersistentMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.util.UUID

/**
 * The page's first staged mutation owns the retained credit. A later lazy
 * mutation for that same page may carry a separately retained credit; when
 * the earlier credit wins, release the superseded retained permit here.
 */
internal fun retainStagedJournalCredit(
    existing: ChapterJournalCredit?,
    incoming: ChapterJournalCredit?,
): ChapterJournalCredit? {
    if (existing == null) return incoming?.takeIf { it.retain() }
    if (incoming !== existing) incoming?.releaseIfRetained()
    return existing
}

/** Diagnostic for the one-time manifest-to-journal bootstrap performed before an opened store is published. */
internal data class ChapterJournalSeedDiagnostics(
    val inventoryCaptureRequested: Boolean = false,
    val pageKeysCaptureRequested: Set<String> = emptySet(),
    val firstBarrierFrameSeq: Long? = null,
    val trustedInventoryCaptureRequested: Boolean = false,
    val trustedBarrierFrameSeq: Long? = null,
    val completed: Boolean = false,
    val failure: Throwable? = null,
)

/** Collects this flush's accepted journal work for one final force; rollover may force a closed segment first. */
internal class JournalFlushBatch {
    var hasJournalWork = false
}

/** Bulk store mutations collect their successful page commits into one journal frame. Store mutex only. */
internal data class BulkJournalCapture(
    val kind: ChapterJournalFormat.RecordKind,
    val operation: String,
    /** Rekey request mapping; replace mappings are derived from successful captured mutations. */
    val rekeyMapping: Map<String, String?> = emptyMap(),
    val mutations: LinkedHashMap<String, ChapterJournalRecord> = LinkedHashMap(),
)

internal data class BulkJournalCaptureResult(
    val writer: ChapterJournalWriter,
    val accepted: Boolean,
)

private val JOURNAL_JSON = Json {
    encodeDefaults = true
    explicitNulls = true
}

/** Fallback artifact file name for the journal chapter identity when the store has none. */
private const val DEFAULT_FILE_NAME = "translation.json"

/**
 * Journal capture collaborator (Phase 7 S5). Owns one chapter's shadow-journal
 * capture mechanics: credit reservation, writer/epoch lifecycle, the page
 * capture frames (the `capturePage*` family), bulk capture frames, inventory
 * snapshots, and the producer capture permit/barrier. The owning store
 * delegates every call here under the store mutex except the two seams that
 * predate the extraction: [reserveJournalCredit] and [ensureJournalWriter]
 * run strictly BEFORE the store mutex in every write path, and epoch
 * allocation happens on the persistence dispatcher under
 * [journalWriterCreationLock], never under the store mutex (Phase 7 S2 lock
 * order). The capture permit is taken before the store mutex; a capture
 * barrier parks producers, never the reverse.
 */
internal class ChapterStoreJournalBridge(
    private val store: ChapterTranslationStore,
    private val persistenceDispatcher: CoroutineDispatcher,
    private val privateStorageRoot: File?,
    private val privateStorageIdentity: String?,
    private val journalStorageFactoryForOpen: ((File, File) -> ChapterJournalStorage)?,
) {

    /**
     * Accepted-mutation journal, placed below app-private files even when
     * artifact documents live behind SAF. Recovery replays its prefix to seed
     * this store; artifact snapshots retain immutable page payloads and display
     * pointers rather than serving as the active page-state projection.
     */
    @Volatile
    private var productionJournalWriter: ChapterJournalWriter? = null
    private val journalWriterCreationLock = Any()

    @Volatile
    private var productionJournalSnapshotManager: ChapterJournalSnapshotManager? = null

    @Volatile
    private var journalSnapshotManagerForTests: ChapterJournalSnapshotManager? = null

    private val journalChapterIdentity: String by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        privateStorageIdentity ?: buildString {
            append(store.artifactParent?.filePath ?: store.artifactParent?.uri?.toString() ?: "<no-parent>")
            append(':')
            append(store.artifactFileName ?: DEFAULT_FILE_NAME)
        }
    }

    private val journalCaptureCoordinator: ChapterJournalCaptureCoordinator by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        if (privateStorageRoot != null) {
            val chapterIdentityHash = StageFingerprints.sha256Hex(journalChapterIdentity.toByteArray(Charsets.UTF_8))
            ChapterJournalCaptureCoordinators.forChapter(chapterIdentityHash)
        } else {
            ChapterJournalCaptureCoordinator()
        }
    }

    @Volatile
    private var journalWriterForTests: ChapterJournalWriter? = null

    /** Bulk store mutations collect their successful page commits into one journal frame. Store mutex only. */
    private var activeBulkJournalCapture: BulkJournalCapture? = null

    /** Captures bootstrap failures and durable progress instead of hiding a failed open-time seed. */
    @Volatile
    internal var journalSeedDiagnostics = ChapterJournalSeedDiagnostics()
        private set

    /** Injects a virtual-storage writer before the first page write in JVM tests. */
    internal fun attachJournalWriterForTests(writer: ChapterJournalWriter) {
        check(journalWriterForTests == null) { "journal writer already attached" }
        journalWriterForTests = writer
        journalCaptureCoordinator.register(writer)
    }

    internal fun attachJournalSnapshotManagerForTests(manager: ChapterJournalSnapshotManager) {
        check(journalSnapshotManagerForTests == null) { "journal snapshot manager already attached" }
        journalSnapshotManagerForTests = manager
    }

    internal val journalWriter: ChapterJournalWriter?
        get() = journalWriterForTests ?: productionJournalWriter

    internal suspend fun <T> withJournalCapturePermit(block: suspend () -> T): T =
        journalCaptureCoordinator.gate.withProducer(block)

    /** Parks chapter journal producers and runs [capture] as one capture barrier. */
    internal suspend fun <T> withCaptureBarrier(capture: suspend () -> T): T =
        journalCaptureCoordinator.gate.withBarrier(capture)

    /**
     * Explicit, dormant shadow-era compaction entry point for E16b certification. Capture closes
     * the chapter-wide producer gate, drains each known epoch's queued frames, snapshots the
     * immutable published map under the store mutex, then reopens before file serialization/writes.
     * Shadow payloads may contain store-ahead-of-journal state; E16c owns ACK-purity activation.
     */
    internal suspend fun captureJournalSnapshotForCompaction(
        gcMode: ChapterJournalGcMode = ChapterJournalGcMode.SHADOW_DORMANT,
    ): ChapterJournalSnapshotCandidate? = journalCaptureCoordinator.withSnapshotOperation {
        val writer = ensureJournalWriter() ?: return@withSnapshotOperation null
        val manager = journalSnapshotManagerForTests ?: productionJournalSnapshotManager
            ?: return@withSnapshotOperation null
        // These are read-only metadata scans. The snapshot-operation mutex prevents a concurrent
        // snapshot publisher; old epochs cannot append, and any epoch opened during the scan is
        // represented by its registered live writer when the producer gate closes below.
        val (predecessorCoverage, scannedDiskCoverage) = withContext(persistenceDispatcher) {
            manager.newestUsableCandidate()?.frontier.orEmpty() to manager.scanEpochCoverages()
        }
        val captured = journalCaptureCoordinator.gate.withBarrier {
            val writers = journalCaptureCoordinator.writersSnapshot().ifEmpty { listOf(writer) }
            val liveWriterKeys = writers.mapTo(mutableSetOf()) { it.epochCoverage.key }
            val liveWriterWatermarks = writers.map { epochWriter ->
                val ackedFrameSeq = epochWriter.flushToCaptureBarrier()
                epochWriter.epochCoverage.copy(ackedFrameSeq = ackedFrameSeq)
            }
            // A previous usable snapshot carries coverage for epochs already removed from disk.
            // The framing scan discovers epochs opened since then without adding replay semantics.
            val diskCoverage = ChapterJournalCompaction.markDiskEpochsClosedOutsideLiveSet(
                scannedDiskCoverage,
                liveWriterKeys,
            )
            val coordinatorCoverage = journalCaptureCoordinator.coverageSnapshot()
            val frontier = ChapterJournalCompaction.mergeFrontiers(
                predecessorCoverage,
                diskCoverage,
                coordinatorCoverage,
                liveWriterWatermarks,
            )
            // One immutable persistent-map reference read: fast-cancel may race off-mutex,
            // but this captures either complete map version without traversing live state.
            val publishedPages = capturePublishedPagesForCompaction()
            val verifiedCoverageKeys = scannedDiskCoverage.mapTo(mutableSetOf()) { it.key }
            coordinatorCoverage.forEach { verifiedCoverageKeys += it.key }
            liveWriterWatermarks.forEach { verifiedCoverageKeys += it.key }
            val epochsAtCapture = ChapterJournalCompaction.gcStatesAtCapture(frontier, verifiedCoverageKeys)
            CapturedJournalSnapshot(frontier, publishedPages, epochsAtCapture)
        }
        // The producer gate is open again before serialization or filesystem I/O. This separate
        // chapter-scoped mutex prevents concurrent captures from choosing the same next generation.
        val snapshot = withContext(persistenceDispatcher) {
            manager.writeSnapshot(
                frontier = captured.frontier,
                pages = captured.pages,
                epochStatesAtGc = captured.epochStates,
                gcMode = gcMode,
            )
        }
        if (gcMode == ChapterJournalGcMode.AUTHORITATIVE) {
            journalCaptureCoordinator.retainTerminalCoverageFor(manager.epochKeys())
        }
        snapshot
    }

    /**
     * Captures one immutable map reference under the store mutex. `pages` is a persistent map and
     * the fast-cancel path replaces the volatile reference atomically, so a racing cancel yields a
     * whole pre- or post-cancel version. E16c must bring fast-cancel under the journal gate before
     * it can claim snapshot ACK-purity.
     */
    internal suspend fun capturePublishedPagesForCompaction(): PersistentMap<String, PublishedPageTranslation> =
        store.mutex.withLock { store.pages }

    /**
     * Explicit E16b recovery seam. Shadow-era store opening remains
     * store-authoritative; callers that are certifying recovery (and E16c's future
     * cutover path) opt into this reducer and decide how to install its result.
     * File reads happen on the persistence dispatcher and never retain a file
     * handle across reducer application.
     */
    internal suspend fun replayJournalForRecovery(): ChapterJournalReplayResult? {
        val root = privateStorageRoot ?: return null
        val engine = store.artifactEngine
        val manifest = store.artifactManifest
        val chapterIdentityHash = StageFingerprints.sha256Hex(journalChapterIdentity.toByteArray(Charsets.UTF_8))
        return withContext(persistenceDispatcher) {
            ChapterJournalReplayReducer.replay(
                epochs = ChapterJournalReplayReducer.readAppPrivateEpochs(root, journalChapterIdentity),
                artifactResolver = if (engine != null && manifest != null) {
                    ChapterJournalReplayReducer.artifactResolver(engine, manifest)
                } else {
                    null
                },
                expectedChapterIdentityHash = chapterIdentityHash,
            )
        }
    }

    private data class CapturedJournalSnapshot(
        val frontier: List<ChapterJournalEpochCoverage>,
        val pages: PersistentMap<String, PublishedPageTranslation>,
        val epochStates: List<ChapterJournalEpochGcState>,
    )

    /**
     * Epoch allocation scans and creates app-private paths, so do it on the
     * persistence dispatcher before acquiring the store mutex. Concurrent first
     * writes on this store share one writer and one allocated epoch.
     */
    internal suspend fun ensureJournalWriter(): ChapterJournalWriter? {
        journalWriter?.let { return it }
        val root = privateStorageRoot ?: return null
        val writer = withJournalCapturePermit {
            withContext(persistenceDispatcher) {
                synchronized(journalWriterCreationLock) {
                    journalWriterForTests ?: productionJournalWriter ?: run {
                        val identity = journalChapterIdentity
                        // A new store object owns a fresh epoch even if a stale writer is
                        // still completing writes in its own epoch after eviction.
                        val epochGeneration = store.currentGeneration
                        val sessionId = UUID.randomUUID()
                        val epoch = ChapterJournalWriter.allocateAppPrivateEpoch(
                            filesDir = root,
                            chapterIdentity = identity,
                            generation = epochGeneration,
                            sessionId = sessionId,
                        )
                        ChapterJournalWriter(
                            storage = journalStorageFactoryForOpen?.invoke(epoch.directory, root)
                                ?: FileChapterJournalStorage(epoch.directory, durableRoot = root),
                            dispatcher = persistenceDispatcher,
                            encodeRecord = { record -> JOURNAL_JSON.encodeToString(record).encodeToByteArray() },
                            encodeBulkRecord = { record -> JOURNAL_JSON.encodeToString(record).encodeToByteArray() },
                            encodeInventory = { record -> JOURNAL_JSON.encodeToString(record).encodeToByteArray() },
                            encodeTerminalLag = { count, starvedAtCommitSeq ->
                                JOURNAL_JSON.encodeToString(
                                    ChapterJournalRecord(
                                        pageKey = "",
                                        generation = epochGeneration,
                                        fencingToken = 0L,
                                        pageVersion = 0L,
                                        state = null,
                                        terminalReason = "${ChapterJournalRecord.TERMINAL_LAG_REASON_CREDIT_WINDOW}:$count",
                                        terminalCommitSeq = starvedAtCommitSeq,
                                    ),
                                ).encodeToByteArray()
                            },
                            encodeTerminalDefunct = { sequence ->
                                JOURNAL_JSON.encodeToString(
                                    ChapterJournalRecord(
                                        pageKey = "",
                                        generation = epochGeneration,
                                        fencingToken = 0L,
                                        pageVersion = sequence,
                                        state = null,
                                        terminalReason = "store_evicted",
                                        terminalCommitSeq = sequence,
                                    ),
                                ).encodeToByteArray()
                            },
                            encodeTerminalPayload = { pageKey, observedBytes, failedCommitSeq ->
                                JOURNAL_JSON.encodeToString(
                                    ChapterJournalRecord(
                                        pageKey = pageKey,
                                        generation = epochGeneration,
                                        fencingToken = 0L,
                                        pageVersion = 0L,
                                        state = null,
                                        terminalReason = ChapterJournalRecord.TERMINAL_PAYLOAD_REASON_SIZE_LIMIT,
                                        terminalCommitSeq = failedCommitSeq,
                                        terminalObservedPayloadBytes = observedBytes,
                                    ),
                                ).encodeToByteArray()
                            },
                            storeGeneration = epochGeneration,
                            epochOrdinal = epoch.ordinal,
                            sessionId = sessionId,
                            chapterIdentityHash = epoch.chapterIdentityHash,
                        ).also {
                            productionJournalWriter = it
                            productionJournalSnapshotManager = ChapterJournalSnapshotManager(
                                storage = FileChapterJournalSnapshotStorage(
                                    chapterDirectory = epoch.directory.parentFile ?: root,
                                    durableRoot = root,
                                ),
                                json = JOURNAL_JSON,
                            )
                            journalCaptureCoordinator.register(it)
                        }
                    }
                }
            }
        }
        // markDefunct may have won the store mutex while this first-writer
        // allocation was in flight and observed no writer to mark. Recheck after
        // publication so the late-created writer still gets its terminal record.
        if (store.isDefunct) writer.requestDefunctMarker()
        return writer
    }

    /** Reserve before taking [ChapterTranslationStore.mutex]; shadow uses only a non-blocking semaphore tryAcquire. */
    internal suspend fun reserveJournalCredit(
        pageKey: String,
        onFailure: ((Throwable) -> Unit)? = null,
    ): ChapterJournalCredit? {
        val span = TranslationTrace.beginStage(
            stage = TranslationTraceStage.JOURNAL_CREDIT_WAIT,
            lane = TranslationTraceLane.SCHEDULER,
            items = 1,
        )
        return try {
            val writer = try {
                ensureJournalWriter()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                onFailure?.invoke(failure)
                span.end(TranslationTraceOutcome.FAILURE, error = failure)
                logcat(LogPriority.WARN, failure) {
                    "TachiyomiAT shadow journal initialization failed; artifact-only persistence continues"
                }
                return null
            }
            if (writer == null) {
                span.end(TranslationTraceOutcome.SKIP)
                return null
            }
            if (store.isDefunct) {
                writer.requestDefunctMarker()
                span.end(TranslationTraceOutcome.EVICTED)
                return null
            }
            // Shadow reserves the foreground permit for interactive manual
            // work; rolling-auto and batch producers use regular permits.
            val manualForeground =
                TranslationTrace.currentRun()?.identity?.mode == TranslationTraceMode.MANUAL
            writer.tryAcquireShadowCredit(foreground = manualForeground).also { credit ->
                span.end(
                    outcome = if (credit == null) {
                        TranslationTraceOutcome.PERSISTENCE_REJECTED
                    } else {
                        TranslationTraceOutcome.SUCCESS
                    },
                )
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            span.end(TranslationTraceOutcome.CANCELLED, error = cancelled)
            throw cancelled
        } catch (failure: Throwable) {
            onFailure?.invoke(failure)
            span.end(TranslationTraceOutcome.FAILURE, error = failure)
            logcat(LogPriority.WARN, failure) {
                "TachiyomiAT shadow journal credit reservation failed"
            }
            null
        }
    }

    /** Non-suspending lock-held snapshot handoff after the artifact transaction commits. */
    internal fun capturePageMutationLocked(
        pageKey: String,
        page: PageTranslationView,
        expected: ChapterTranslationStore.PatchPrecondition?,
        durableFailure: DurableFailureMetadata?,
        credit: ChapterJournalCredit?,
        artifactContentHash: String? = null,
        journalFlushBatch: JournalFlushBatch? = null,
    ) {
        // Capture only the exact immutable value already installed in the store map.
        // The draft argument documents which successful page mutation reached this point;
        // it is intentionally never retained by the asynchronous writer.
        val published = store.pages[pageKey] ?: return
        val record = ChapterJournalRecord(
            pageKey = pageKey,
            generation = store.currentGeneration,
            fencingToken = expected?.leaseToken ?: store.pageLeases[pageKey]?.token ?: 0L,
            pageVersion = published.pageVersion,
            state = published,
            artifactContentHash = artifactContentHash
                ?: pageArtifactContentHash(store.artifactManifest, pageKey, published),
            cleanedImageName = published.cleanedImageName,
            cleanedImageContentHash = published.cleanedImageContentHash,
            durableFailure = durableFailure,
        )
        activeBulkJournalCapture?.let { bulk ->
            bulk.mutations[pageKey] = record
            credit?.releaseIfUnqueued()
            return
        }
        val writer = journalWriter ?: run {
            credit?.releaseIfUnqueued()
            return
        }
        val wasAccepting = writer.isShadowCaptureActive
        journalFlushBatch?.hasJournalWork = true
        val paid = durableFailure != null || published.hasRenderedResult || published.isTextlessTerminal
        writer.capturePageMutation(
            commitSeq = writer.nextCommitSeq(),
            credit = credit,
            pageKey = pageKey,
            generation = record.generation,
            fencingToken = record.fencingToken,
            page = published,
            durableFailure = durableFailure,
            paid = paid,
            artifactContentHash = record.artifactContentHash,
            cleanedImageName = record.cleanedImageName,
            cleanedImageContentHash = record.cleanedImageContentHash,
            inventory = journalInventorySnapshot(store.artifactManifest),
            deferSyncUntilBarrier = journalFlushBatch != null,
        )
        if (credit == null && wasAccepting) {
            reportTerminalJournalLag()
        }
    }

    /** A deletion is a page-level store commit and receives its own fenced tombstone record. */
    internal fun capturePageDeletionLocked(
        pageKey: String,
        pageVersion: Long,
        credit: ChapterJournalCredit?,
        artifactContentHash: String? = null,
    ) {
        val record = ChapterJournalRecord(
            pageKey = pageKey,
            generation = store.currentGeneration,
            fencingToken = store.pageLeases[pageKey]?.token ?: 0L,
            pageVersion = pageVersion,
            state = null,
            artifactContentHash = artifactContentHash,
        )
        activeBulkJournalCapture?.let { bulk ->
            bulk.mutations[pageKey] = record
            credit?.releaseIfUnqueued()
            return
        }
        val writer = journalWriter ?: run {
            credit?.releaseIfUnqueued()
            return
        }
        val wasAccepting = writer.isShadowCaptureActive
        writer.capturePageDeletion(
            commitSeq = writer.nextCommitSeq(),
            credit = credit,
            pageKey = pageKey,
            generation = record.generation,
            fencingToken = record.fencingToken,
            pageVersion = record.pageVersion,
            artifactContentHash = record.artifactContentHash,
            inventory = journalInventorySnapshot(store.artifactManifest),
        )
        if (credit == null && wasAccepting) reportTerminalJournalLag()
    }

    /** Caller holds [ChapterTranslationStore.mutex] and an active bulk capture; null hash means retryable invalidation. */
    internal fun captureRekeyStateLocked(
        oldPageKey: String,
        newPageKey: String,
        mutation: ChapterArtifactEngine.PreparedRekeyJournalMutation,
    ) {
        val state = mutation.state
        check(state.sourceFileName == newPageKey) { "re-key journal snapshot has the old source key" }
        val record = ChapterJournalRecord(
            pageKey = newPageKey,
            generation = store.currentGeneration,
            fencingToken = synchronized(store.pageLeases) {
                store.pageLeases[oldPageKey]?.token ?: store.pageLeases[newPageKey]?.token ?: 0L
            },
            pageVersion = state.pageVersion,
            state = state,
            artifactContentHash = mutation.artifactContentHash,
            cleanedImageName = state.cleanedImageName,
            cleanedImageContentHash = state.cleanedImageContentHash,
        )
        val bulk = checkNotNull(activeBulkJournalCapture) { "re-key state escaped its bulk journal frame" }
        bulk.mutations[newPageKey] = record
    }

    /** Caller holds [ChapterTranslationStore.mutex]; opens one bulk API operation's journal frame. */
    internal fun openBulkJournalFrameLocked(
        kind: ChapterJournalFormat.RecordKind,
        operation: String,
        rekeyMapping: Map<String, String?> = emptyMap(),
    ): BulkJournalCapture {
        check(activeBulkJournalCapture == null) { "nested bulk journal capture" }
        return BulkJournalCapture(
            kind = kind,
            operation = operation,
            rekeyMapping = rekeyMapping,
        ).also { activeBulkJournalCapture = it }
    }

    /** Caller holds [ChapterTranslationStore.mutex]; abandons an unfinished frame without emitting it. */
    internal fun cancelBulkJournalFrameLocked(bulk: BulkJournalCapture) {
        if (activeBulkJournalCapture === bulk) activeBulkJournalCapture = null
    }

    /** Caller holds [ChapterTranslationStore.mutex]. One bulk API operation consumes one writer credit and one commit sequence. */
    internal fun finishBulkJournalCaptureLocked(
        bulk: BulkJournalCapture,
        credit: ChapterJournalCredit?,
        inventory: ChapterJournalInventorySnapshot? = null,
    ): BulkJournalCaptureResult? {
        check(activeBulkJournalCapture === bulk) { "bulk journal capture context changed while store mutex was held" }
        activeBulkJournalCapture = null
        if (bulk.mutations.isEmpty()) {
            credit?.releaseIfHeld()
            return null
        }
        val writer = journalWriter ?: run {
            credit?.releaseIfHeld()
            return null
        }
        val wasAccepting = writer.isShadowCaptureActive
        val laggedBefore = writer.shadowLaggedCount
        val rejectedBefore = writer.offersRejectedAfterTerminalCount
        // The aggregate describes the subset that actually committed.
        // replaceAll can partially commit bulk page operations before a later
        // page is rejected, so its emitted mapping comes from captured outcomes,
        // not the requested input map. A successful prior deletion remains a
        // null mapping when the replacement write for that key was rejected.
        // Rekey mappings summarize only pairs represented by a source tombstone and
        // destination state in this bulk. Pointerless destinations still carry a null-hash
        // retryable state, so mapping membership does not imply artifact completion.
        val successfulMapping = when (bulk.kind) {
            ChapterJournalFormat.RecordKind.BULK_REPLACE -> bulk.mutations.mapValues { (_, record) ->
                record.pageKey.takeUnless { record.state == null }
            }
            ChapterJournalFormat.RecordKind.BULK_REKEY -> bulk.rekeyMapping.filter { (oldKey, newKey) ->
                newKey != null &&
                    bulk.mutations[oldKey]?.let { it.state == null } == true &&
                    bulk.mutations[newKey]?.state != null
            }
            else -> error("unsupported bulk journal kind: ${bulk.kind}")
        }
        writer.captureBulkMutation(
            commitSeq = writer.nextCommitSeq(),
            credit = credit,
            kind = bulk.kind,
            record = ChapterJournalBulkRecord(
                operation = bulk.operation,
                mapping = successfulMapping,
                mutations = bulk.mutations.values.toList(),
            ),
            inventory = inventory ?: journalInventorySnapshot(store.artifactManifest),
        )
        if (credit == null && wasAccepting) reportTerminalJournalLag()
        return BulkJournalCaptureResult(
            writer = writer,
            accepted = writer.shadowLaggedCount == laggedBefore &&
                writer.offersRejectedAfterTerminalCount == rejectedBefore,
        )
    }

    private fun reportTerminalJournalLag() {
        TranslationTrace.beginStage(
            stage = TranslationTraceStage.JOURNAL_TERMINAL_LAG,
            lane = TranslationTraceLane.STORAGE,
            items = 1,
        ).end(TranslationTraceOutcome.PERSISTENCE_REJECTED)
        logcat(LogPriority.WARN) {
            "TachiyomiAT shadow journal capture ended: terminal credit-window lag recorded"
        }
    }

    /** Snapshot artifact identity from the already-published manifest; capture never rehashes under mutex. */
    internal fun pageArtifactContentHash(
        manifest: ChapterArtifactManifest?,
        pageKey: String,
        fallbackSnapshot: PageTranslationView? = null,
    ): String? = manifest?.pages?.get(pageKey)?.candidate?.pageSnapshotFingerprint
        ?.takeIf(String::isNotBlank)
        ?: fallbackSnapshot?.let(StageFingerprints::pageSnapshot)

    /** Capture the current inventory; sorting and fingerprinting remain on the writer dispatcher. */
    internal fun journalInventorySnapshot(
        manifest: ChapterArtifactManifest?,
        pendingRegistrations: Set<String> = store.pendingArtifactPageRegistrations,
    ): ChapterJournalInventorySnapshot {
        if (manifest == null) return ChapterJournalInventorySnapshot.EMPTY
        // Snapshot the key union under the mutex so later registrations cannot mutate this offer.
        // Source identities also declare real chapter pages. Keep them in the
        // inventory if a page row is temporarily absent (for example, a
        // pointerless page moved in the same BULK_REKEY frame).
        val pageKeys = (manifest.pages.keys + manifest.sourceShaByPageKey.keys + pendingRegistrations).toSet()
        return ChapterJournalInventorySnapshot(
            expectedPageKeys = pageKeys,
            expectedPageCount = maxOf(
                manifest.expectedPageCount ?: 0,
                store.statusLedger.pendingExpectedPageCountLocked() ?: 0,
                pageKeys.size,
            ),
            sourceShaByPageKey = manifest.sourceShaByPageKey,
            expectedPageCountTrusted = store.statusLedger.inventoryTrustedLocked(manifest.expectedPageCountTrusted),
        )
    }

    /**
     * Idempotently seeds hash-verified manifest page snapshots while the store is still private to open.
     * An untrusted inventory precedes seed frames; a trusted v2 inventory is the commit point and
     * is written only after every seed frame is synced. Trust is earned only by hash-verified
     * content; metadata-only fallbacks keep the chapter in union mode until real content exists.
     */
    internal suspend fun seedJournalFromManifest(
        seedPages: Map<String, PageTranslation>,
        inventory: ChapterJournalInventorySnapshot,
    ) {
        if (privateStorageRoot == null) return
        journalSeedDiagnostics = ChapterJournalSeedDiagnostics(inventoryCaptureRequested = true)
        val capturedPageKeys = linkedSetOf<String>()
        try {
            val writer = ensureJournalWriter() ?: return
            val untrustedInventory = inventory.copy(expectedPageCountTrusted = false)
            writer.captureInventory(untrustedInventory)
            seedPages.forEach { (pageKey, _) ->
                val credit = writer.acquireCredit(foreground = false)
                var handedOff = false
                try {
                    store.mutex.withLock {
                        val page = store.pages[pageKey] ?: return@withLock
                        val durableFailure = store.artifactManifest?.durableFailures?.values
                            ?.firstOrNull { it.pageKey == pageKey }
                        writer.capturePageMutation(
                            commitSeq = writer.nextCommitSeq(),
                            credit = credit,
                            pageKey = pageKey,
                            generation = store.currentGeneration,
                            fencingToken = 0L,
                            page = page,
                            durableFailure = durableFailure,
                            paid = durableFailure != null || page.hasRenderedResult || page.isTextlessTerminal,
                            artifactContentHash = StageFingerprints.pageSnapshot(page),
                            inventory = untrustedInventory,
                        )
                        handedOff = true
                        capturedPageKeys += pageKey
                        journalSeedDiagnostics = journalSeedDiagnostics.copy(
                            pageKeysCaptureRequested = capturedPageKeys.toSet(),
                        )
                    }
                } finally {
                    if (!handedOff) credit.releaseIfUnqueued()
                }
            }

            val firstBarrierFrameSeq = writer.flushToCaptureBarrier()
            journalSeedDiagnostics = journalSeedDiagnostics.copy(
                pageKeysCaptureRequested = capturedPageKeys.toSet(),
                firstBarrierFrameSeq = firstBarrierFrameSeq,
            )
            val seededPrefix = replayJournalForRecovery() ?: return
            if (!seedPages.keys.all { seededPrefix.pageOutcomes[it] == ChapterJournalPageOutcome.RECORDED }) return
            store.mutex.withLock {
                store.statusLedger.adoptReplayedPageOutcomesLocked(seededPrefix.pageOutcomes.toMap())
            }
            if (!inventory.expectedPageCountTrusted) {
                journalSeedDiagnostics = journalSeedDiagnostics.copy(completed = true)
                return
            }

            // The trust bit is the migration-complete marker. It follows the synced page prefix.
            journalSeedDiagnostics = journalSeedDiagnostics.copy(trustedInventoryCaptureRequested = true)
            writer.captureInventory(inventory.copy(expectedPageCountTrusted = true))
            val trustedBarrierFrameSeq = writer.flushToCaptureBarrier()
            journalSeedDiagnostics = journalSeedDiagnostics.copy(trustedBarrierFrameSeq = trustedBarrierFrameSeq)
            val completedPrefix = replayJournalForRecovery() ?: return
            val completedInventory = completedPrefix.inventory ?: return
            if (!completedInventory.hasTrustedExpectedPageCount ||
                completedPrefix.expectedPageKeys != inventory.expectedPageKeys ||
                completedPrefix.expectedPageCount != inventory.expectedPageCount
            ) {
                return
            }
            store.mutex.withLock {
                store.statusLedger.establishTrustedInventoryLocked(
                    expectedPageKeys = inventory.expectedPageKeys,
                    expectedPageCount = inventory.expectedPageCount,
                    pageOutcomes = completedPrefix.pageOutcomes.toMap(),
                )
            }
            journalSeedDiagnostics = journalSeedDiagnostics.copy(completed = true)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            journalSeedDiagnostics = journalSeedDiagnostics.copy(failure = failure)
            logcat(LogPriority.WARN, failure) {
                "TachiyomiAT journal manifest seed stayed in union mode"
            }
        }
    }
}
