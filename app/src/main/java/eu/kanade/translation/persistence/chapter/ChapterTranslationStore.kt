package eu.kanade.translation.persistence.chapter

import com.hippo.unifile.UniFile
import eu.kanade.translation.diagnostics.TranslationTrace
import eu.kanade.translation.diagnostics.TranslationTraceLane
import eu.kanade.translation.diagnostics.TranslationTraceMode
import eu.kanade.translation.diagnostics.TranslationTraceOutcome
import eu.kanade.translation.diagnostics.TranslationTraceStage
import eu.kanade.translation.model.PageDisplayState
import eu.kanade.translation.model.PageStage
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.model.blockFingerprints
import eu.kanade.translation.model.cancelInFlightStages
import eu.kanade.translation.model.detachedCopy
import eu.kanade.translation.model.hasRenderedResult
import eu.kanade.translation.model.isStageFailed
import eu.kanade.translation.model.isStageRunning
import eu.kanade.translation.model.isTextlessTerminal
import eu.kanade.translation.model.stableFingerprint
import eu.kanade.translation.persistence.artifact.ArtifactManifestProbe
import eu.kanade.translation.persistence.artifact.ArtifactOrigin
import eu.kanade.translation.persistence.artifact.ArtifactStage
import eu.kanade.translation.persistence.artifact.ArtifactStageStatus
import eu.kanade.translation.persistence.artifact.AtomicChapterDocuments
import eu.kanade.translation.persistence.artifact.AttemptOrigin
import eu.kanade.translation.persistence.artifact.BitmapFactoryCleanedImageProbe
import eu.kanade.translation.persistence.artifact.ChapterArtifactEngine
import eu.kanade.translation.persistence.artifact.ChapterArtifactLayout
import eu.kanade.translation.persistence.artifact.ChapterArtifactManifest
import eu.kanade.translation.persistence.artifact.ChapterArtifactManifestReader
import eu.kanade.translation.persistence.artifact.ChapterRunRecord
import eu.kanade.translation.persistence.artifact.ChapterTranslationProfile
import eu.kanade.translation.persistence.artifact.CleanedImageProbe
import eu.kanade.translation.persistence.artifact.CommitPoint
import eu.kanade.translation.persistence.artifact.CommittedDisplayRef
import eu.kanade.translation.persistence.artifact.DurableFailureMetadata
import eu.kanade.translation.persistence.artifact.FailureCategory
import eu.kanade.translation.persistence.artifact.GroupCommitConfiguration
import eu.kanade.translation.persistence.artifact.OcrCheckpointMode
import eu.kanade.translation.persistence.artifact.PageArtifactRecord
import eu.kanade.translation.persistence.artifact.PageOcrCheckpoint
import eu.kanade.translation.persistence.artifact.PartialBatchDetermination
import eu.kanade.translation.persistence.artifact.PartialBatchInfo
import eu.kanade.translation.persistence.artifact.SidecarPointer
import eu.kanade.translation.persistence.artifact.SidecarRead
import eu.kanade.translation.persistence.artifact.SourceIdentity
import eu.kanade.translation.persistence.artifact.StageFingerprints
import eu.kanade.translation.persistence.artifact.UniFileChapterDocumentIo
import eu.kanade.translation.persistence.internal.ChapterAttemptLedger
import eu.kanade.translation.persistence.internal.PageStageLeaseTable
import eu.kanade.translation.persistence.internal.StorePersistenceScheduler
import eu.kanade.translation.persistence.internal.StoreStatusInputs
import eu.kanade.translation.persistence.internal.StoreStatusProjector
import eu.kanade.translation.persistence.internal.formatWriteDiagnostic
import eu.kanade.translation.persistence.journal.ChapterJournalBulkRecord
import eu.kanade.translation.persistence.journal.ChapterJournalCredit
import eu.kanade.translation.persistence.journal.ChapterJournalFormat
import eu.kanade.translation.persistence.journal.ChapterJournalInventorySnapshot
import eu.kanade.translation.persistence.journal.ChapterJournalRecord
import eu.kanade.translation.persistence.journal.ChapterJournalWriter
import eu.kanade.translation.persistence.journal.FileChapterJournalStorage
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Durable outcome of the [ChapterTranslationStore.checkpointOcr] transaction. */
sealed interface CheckpointOcrResult {
    data class Committed(
        val snapshot: ChapterTranslationStore.PageSnapshot,
        val manifest: ChapterArtifactManifest,
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

/** The five persistence actions selected from a live page mutation. */
internal enum class ArtifactMutation {
    Failure,
    TerminalPromotion,
    ManualEdit,
    Intermediate,
    Legacy,
}

/** Selects the artifact persistence action while preserving the historical dispatch order. */
internal fun classifyMutation(
    updated: PageTranslation,
    durableFailure: DurableFailureMetadata?,
): ArtifactMutation = when {
    durableFailure != null -> ArtifactMutation.Failure
    GroupCommitConfiguration.enabled && (updated.hasRenderedResult || updated.isTextlessTerminal) ->
        ArtifactMutation.TerminalPromotion
    GroupCommitConfiguration.enabled && updated.blocks.any { it.userEditedAt != null } ->
        ArtifactMutation.ManualEdit
    GroupCommitConfiguration.enabled -> ArtifactMutation.Intermediate
    else -> ArtifactMutation.Legacy
}

private fun PageArtifactRecord.toArtifactPageFallback(): PageTranslation {
    val committed = committed
    val displayBase = committed?.displayBase
    val hasCommittedDisplay = committed != null &&
        (
            displayBase?.kind == eu.kanade.translation.persistence.artifact.DisplayBaseKind.ORIGINAL_SOURCE ||
                (
                    displayBase?.kind == eu.kanade.translation.persistence.artifact.DisplayBaseKind.CLEANED_IMAGE &&
                        displayBase.fileName != null &&
                        !displayBase.legacyLayout
                    )
            )
    val displayReady = displayState == PageDisplayState.DISPLAY_READY ||
        displayState == PageDisplayState.TEXTLESS_COMPLETE ||
        displayState == PageDisplayState.REFRESHING_WITH_COMMITTED_RESULT ||
        displayState == PageDisplayState.FAILED_WITH_COMMITTED_RESULT
    val ready = hasCommittedDisplay && displayReady
    val running = displayState == PageDisplayState.CANDIDATE_RUNNING
    val failed = displayState == PageDisplayState.FAILED_NO_RESULT
    val cleanedName = displayBase?.fileName?.takeIf {
        displayBase.kind == eu.kanade.translation.persistence.artifact.DisplayBaseKind.CLEANED_IMAGE &&
            !displayBase.legacyLayout
    }
    return PageTranslation(
        sourceFileName = pageKey,
        cleanedImageName = cleanedName,
        ocrStatus = when {
            ready -> eu.kanade.translation.model.StageStatus.READY
            running -> eu.kanade.translation.model.StageStatus.RUNNING
            failed -> eu.kanade.translation.model.StageStatus.FAILED
            else -> eu.kanade.translation.model.StageStatus.PENDING
        },
        translationStatus = when {
            ready -> eu.kanade.translation.model.StageStatus.READY
            running -> eu.kanade.translation.model.StageStatus.RUNNING
            failed -> eu.kanade.translation.model.StageStatus.FAILED
            else -> eu.kanade.translation.model.StageStatus.PENDING
        },
        inpaintStatus = when {
            ready && cleanedName != null -> eu.kanade.translation.model.StageStatus.READY
            running -> eu.kanade.translation.model.StageStatus.RUNNING
            failed -> eu.kanade.translation.model.StageStatus.FAILED
            else -> eu.kanade.translation.model.StageStatus.PENDING
        },
        renderStatus = when {
            ready && (cleanedName != null || displayBase?.kind == eu.kanade.translation.persistence.artifact.DisplayBaseKind.ORIGINAL_SOURCE) ->
                eu.kanade.translation.model.StageStatus.READY
            running -> eu.kanade.translation.model.StageStatus.RUNNING
            failed -> eu.kanade.translation.model.StageStatus.FAILED
            else -> eu.kanade.translation.model.StageStatus.PENDING
        },
        pageVersion = pageVersion,
        updatedAt = committed?.promotedAtEpochMs ?: 0L,
    )
}

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

class ChapterTranslationStore(
    // Retained as a source-compatible test seam; artifact-only persistence never
    // invokes this legacy flat-file creator.
    // The scheduler uses these fields to preserve the legacy lazy-file fallback
    // in tests and stores that have not opened artifact storage yet.
    internal var translationFile: UniFile?,
    internal val fileCreator: (() -> UniFile)?,
    initialPages: Map<String, PageTranslation> = emptyMap(),
    artifactStore: ChapterArtifactEngine? = null,
    initialCommittedPages: Map<String, PageTranslation> = emptyMap(),
    initialArtifactManifest: ChapterArtifactManifest? = null,
    initialRetiredCleanedImages: Map<String, Set<String>> = emptyMap(),
    internal val artifactParent: UniFile? = null,
    private val artifactFileName: String? = null,
    private val persistenceDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val privateStorageRoot: File? = null,
    private val privateStorageIdentity: String? = null,
) {
    /** Explicit memory, lazy-durable, or opened-durable storage mode. */
    internal var engineMode: eu.kanade.translation.persistence.internal.ChapterStoreEngineMode =
        artifactStore?.let { eu.kanade.translation.persistence.internal.ChapterStoreEngineMode.Durable(it) }
            ?: if (translationFile != null || fileCreator != null || artifactParent != null || artifactFileName != null) {
                eu.kanade.translation.persistence.internal.ChapterStoreEngineMode.LazyDurable(
                    artifactParent = artifactParent,
                    artifactFileName = artifactFileName,
                    fileCreator = fileCreator,
                )
            } else {
                eu.kanade.translation.persistence.internal.ChapterStoreEngineMode.Memory
            }

    /** The durable engine when this mode has been opened; memory and lazy modes are explicit. */
    internal val artifactEngine: ChapterArtifactEngine?
        get() = (engineMode as? eu.kanade.translation.persistence.internal.ChapterStoreEngineMode.Durable)?.artifact

    internal val mutex = Mutex()

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

    private fun reportCandidateReuse(state: ChapterArtifactEngine.CandidateOpenState?) {
        val reused = state as? ChapterArtifactEngine.CandidateOpenState.Reused ?: return
        candidateReuseObservers.forEach { observer ->
            // Diagnostics must not change whether an artifact transaction is accepted.
            runCatching { observer(reused) }
        }
    }

    internal val chapterKey: String?
        get() = artifactEngine?.layout?.chapterKey ?: translationFile?.name?.substringBeforeLast('.')

    /** Whether the facade has opened its durable engine. */
    internal fun hasArtifactEngine(): Boolean = artifactEngine != null

    /** Runs one external artifact transaction under the facade mutex. */
    internal suspend inline fun <T> withArtifactEngineLocked(
        crossinline block: (ChapterArtifactEngine) -> T,
    ): T? = mutex.withLock {
        artifactEngine?.let(block)
    }

    /** Read-only run-record projection used by progress/status consumers. */
    internal fun readActiveRunRecord(): ChapterRunRecord? {
        val pointer = artifactManifest?.activeRun ?: return null
        val artifact = artifactEngine ?: return null
        return (artifact.readRunRecord(pointer) as? ChapterArtifactEngine.RunRecordRead.Usable)?.record
    }

    /** Read-only manifest projection for context/status consumers. */
    internal fun readArtifactManifest(): ChapterArtifactManifest? = artifactEngine?.readManifest()

    /** Read-only chapter key projection without exposing the engine. */
    internal fun artifactChapterKey(): String? = artifactEngine?.layout?.chapterKey

    @Volatile
    internal var pages: PersistentMap<String, PageTranslation> = persistentMapOf()
    private val _state = MutableStateFlow<Map<String, PageTranslation>>(emptyMap())

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

    // Lease ownership stays in one table so the store and lease APIs share the
    // same map and synchronization boundary.
    private val pageStageLeaseTable = PageStageLeaseTable(this)

    /** Writer leases per page: one origin owns a page until it releases it. */
    private val pageLeases get() = pageStageLeaseTable.pageLeases

    @Volatile
    private var generation = 0L
    private var nextPageVersion = 0L

    /** Live candidate progress; readers that need display safety use [display]. */
    val state: StateFlow<Map<String, PageTranslation>> = _state.asStateFlow()

    /**
     * Committed-pointer display projection: each page resolves to
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

                data class PageLeaseTokenMismatch(
                    val expectedToken: Long,
                    val actualToken: Long?,
                ) : Detail
            }
        }
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

    // Persistence state is stored by the scheduler, but every mutation is
    // serialized by this store's mutex. Store-to-scheduler scheduling is a
    // synchronous handoff that does not take a scheduler lock; scheduler flush
    // paths come back through mutex.withLock before reading or changing state.
    private val persistenceScheduler = StorePersistenceScheduler(this, persistenceDispatcher)

    /**
     * Shadow-only page-state journal. It is placed below app-private files even
     * when legacy artifacts live behind SAF, and it never replaces the legacy
     * artifact bridge as the write authority in this phase.
     */
    @Volatile
    private var productionJournalWriter: ChapterJournalWriter? = null
    private val journalWriterCreationLock = Any()

    @Volatile
    private var journalWriterForTests: ChapterJournalWriter? = null

    /** Bulk store mutations collect their successful page commits into one journal frame. Store mutex only. */
    private var activeBulkJournalCapture: BulkJournalCapture? = null

    private data class BulkJournalCapture(
        val kind: ChapterJournalFormat.RecordKind,
        val operation: String,
        /** Rekey request mapping; replace mappings are derived from successful captured mutations. */
        val rekeyMapping: Map<String, String?> = emptyMap(),
        val mutations: LinkedHashMap<String, ChapterJournalRecord> = LinkedHashMap(),
    )

    /** Injects a virtual-storage writer before the first page write in JVM tests. */
    internal fun attachJournalWriterForTests(writer: ChapterJournalWriter) {
        check(journalWriterForTests == null) { "journal writer already attached" }
        journalWriterForTests = writer
    }

    private val journalWriter: ChapterJournalWriter?
        get() = journalWriterForTests ?: productionJournalWriter

    /**
     * Epoch allocation scans and creates app-private paths, so do it on the
     * persistence dispatcher before acquiring the store mutex. Concurrent first
     * writes on this store share one writer and one allocated epoch.
     */
    private suspend fun ensureJournalWriter(): ChapterJournalWriter? {
        journalWriter?.let { return it }
        val root = privateStorageRoot ?: return null
        val writer = withContext(persistenceDispatcher) {
            synchronized(journalWriterCreationLock) {
                journalWriterForTests ?: productionJournalWriter ?: run {
                    val identity = privateStorageIdentity ?: buildString {
                        append(artifactParent?.filePath ?: artifactParent?.uri?.toString() ?: "<no-parent>")
                        append(':')
                        append(artifactFileName ?: translationFile?.name ?: DEFAULT_FILE_NAME)
                    }
                    // A new store object owns a fresh epoch even if a stale writer is
                    // still completing writes in its own epoch after eviction.
                    val epochGeneration = generation
                    val sessionId = UUID.randomUUID()
                    val epoch = ChapterJournalWriter.allocateAppPrivateEpoch(
                        filesDir = root,
                        chapterIdentity = identity,
                        generation = epochGeneration,
                        sessionId = sessionId,
                    )
                    ChapterJournalWriter(
                        storage = FileChapterJournalStorage(epoch.directory, durableRoot = root),
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
                    ).also { productionJournalWriter = it }
                }
            }
        }
        // markDefunct may have won the store mutex while this first-writer
        // allocation was in flight and observed no writer to mark. Recheck after
        // publication so the late-created writer still gets its terminal record.
        if (isDefunct) writer.requestDefunctMarker()
        return writer
    }

    /** Shadow lag count is observable in tests and diagnostics without reading journal files. */
    internal val journalShadowLagCount: Long
        get() = journalWriter?.shadowLaggedCount ?: 0L

    /** Reserve before taking [mutex]; shadow uses only a non-blocking semaphore tryAcquire. */
    private suspend fun reserveJournalCredit(pageKey: String): ChapterJournalCredit? {
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
                span.end(TranslationTraceOutcome.FAILURE, error = failure)
                logcat(LogPriority.WARN, failure) {
                    "TachiyomiAT shadow journal initialization failed; legacy persistence continues"
                }
                return null
            }
            if (writer == null) {
                span.end(TranslationTraceOutcome.SKIP)
                return null
            }
            if (isDefunct) {
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
            span.end(TranslationTraceOutcome.FAILURE, error = failure)
            logcat(LogPriority.WARN, failure) {
                "TachiyomiAT shadow journal credit reservation failed"
            }
            null
        }
    }

    /** Non-suspending lock-held snapshot handoff after the legacy transaction commits. */
    private fun captureLegacyPersistedLocked(
        pageKey: String,
        page: PageTranslation,
        expected: PatchPrecondition?,
        durableFailure: DurableFailureMetadata?,
        credit: ChapterJournalCredit?,
        artifactContentHash: String? = null,
    ) {
        val record = ChapterJournalRecord(
            pageKey = pageKey,
            generation = generation,
            fencingToken = expected?.leaseToken ?: pageLeases[pageKey]?.token ?: 0L,
            pageVersion = page.pageVersion,
            state = page,
            artifactContentHash = artifactContentHash ?: pageArtifactContentHash(artifactManifest, pageKey, page),
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
        val paid = durableFailure != null || page.hasRenderedResult || page.isTextlessTerminal
        writer.captureLegacyPersisted(
            commitSeq = writer.nextCommitSeq(),
            credit = credit,
            pageKey = pageKey,
            generation = record.generation,
            fencingToken = record.fencingToken,
            page = page,
            durableFailure = durableFailure,
            paid = paid,
            artifactContentHash = record.artifactContentHash,
            inventory = journalInventorySnapshot(artifactManifest),
        )
        if (credit == null && wasAccepting) {
            reportTerminalJournalLag()
        }
    }

    /** A deletion is a page-level legacy commit and receives its own fenced tombstone record. */
    private fun captureLegacyDeletionLocked(
        pageKey: String,
        pageVersion: Long,
        credit: ChapterJournalCredit?,
        artifactContentHash: String? = null,
    ) {
        val record = ChapterJournalRecord(
            pageKey = pageKey,
            generation = generation,
            fencingToken = pageLeases[pageKey]?.token ?: 0L,
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
        writer.captureLegacyDeletion(
            commitSeq = writer.nextCommitSeq(),
            credit = credit,
            pageKey = pageKey,
            generation = record.generation,
            fencingToken = record.fencingToken,
            pageVersion = record.pageVersion,
            artifactContentHash = record.artifactContentHash,
            inventory = journalInventorySnapshot(artifactManifest),
        )
        if (credit == null && wasAccepting) reportTerminalJournalLag()
    }

    /** Caller holds [mutex]. One bulk API operation consumes one writer credit and one commit sequence. */
    private fun finishBulkJournalCaptureLocked(
        bulk: BulkJournalCapture,
        credit: ChapterJournalCredit?,
    ) {
        check(activeBulkJournalCapture === bulk) { "bulk journal capture context changed while store mutex was held" }
        activeBulkJournalCapture = null
        if (bulk.mutations.isEmpty()) {
            credit?.releaseIfHeld()
            return
        }
        val writer = journalWriter ?: run {
            credit?.releaseIfHeld()
            return
        }
        val wasAccepting = writer.isShadowCaptureActive
        // The aggregate describes the durable subset that actually committed.
        // replaceAll can partially commit legacy page operations before a later
        // page is rejected, so its emitted mapping comes from captured outcomes,
        // not the requested input map. A successful prior deletion remains a
        // null mapping when the replacement write for that key was rejected.
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
        writer.captureLegacyBulkMutation(
            commitSeq = writer.nextCommitSeq(),
            credit = credit,
            kind = bulk.kind,
            record = ChapterJournalBulkRecord(
                operation = bulk.operation,
                mapping = successfulMapping,
                mutations = bulk.mutations.values.toList(),
            ),
            inventory = journalInventorySnapshot(artifactManifest),
        )
        if (credit == null && wasAccepting) reportTerminalJournalLag()
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
    private fun pageArtifactContentHash(
        manifest: ChapterArtifactManifest?,
        pageKey: String,
        fallbackSnapshot: PageTranslation? = null,
    ): String? = manifest?.pages?.get(pageKey)?.candidate?.pageSnapshotFingerprint
        ?.takeIf(String::isNotBlank)
        ?: fallbackSnapshot?.let(StageFingerprints::pageSnapshot)

    /** Read-only references to immutable manifest collections keep the lock-held handoff O(1). */
    private fun journalInventorySnapshot(manifest: ChapterArtifactManifest?): ChapterJournalInventorySnapshot {
        if (manifest == null) return ChapterJournalInventorySnapshot.EMPTY
        val pageKeys = (manifest.pages.keys + pendingArtifactPageRegistrations).toSet()
        return ChapterJournalInventorySnapshot(
            expectedPageKeys = pageKeys,
            expectedPageCount = maxOf(
                manifest.expectedPageCount ?: 0,
                pendingExpectedPageCount ?: 0,
                pageKeys.size,
            ),
            sourceShaByPageKey = manifest.sourceShaByPageKey,
        )
    }

    /**
     * Active reader stores publish live state first.  Their durable artifact
     * transactions are coalesced by [persistenceScheduler] instead of running
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

    @Volatile
    private var lastArtifactPublicationRejectionDiagnostic: String? = null

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
     */
    internal suspend fun enqueueLazyPersistence(
        expectedGeneration: Long,
        work: suspend () -> Boolean,
    ): Deferred<Boolean> {
        val result = CompletableDeferred<Boolean>()
        mutex.withLock {
            if (defunct || generation != expectedGeneration) {
                result.complete(false)
            } else {
                persistenceScheduler.pendingLazyTasks.addLast(
                    LazyPersistenceTask(
                        generation = expectedGeneration,
                        work = work,
                        result = result,
                    ),
                )
            }
            // Keep this synchronous store-to-scheduler call under the same
            // mutex as the queue mutation, so the scheduler's job reference
            // has the same single-writer boundary as the queued task.
            persistenceScheduler.schedulePersist(markPageDirty = false)
        }
        return result
    }

    /** Caller holds [mutex]; scheduler flushes call this only from mutex.withLock. */
    internal fun takeLazyPersistenceTaskLocked(): LazyPersistenceTask? =
        persistenceScheduler.pendingLazyTasks.pollFirst()

    /** Caller holds [mutex]; scheduler uses this to decide whether to reschedule a flush. */
    internal fun hasPendingLazyPersistenceLocked(): Boolean =
        persistenceScheduler.pendingLazyTasks.isNotEmpty() || persistenceScheduler.pendingLazyMutations.isNotEmpty()

    /**
     * Retry mutations that were already durably captured before a later promotion
     * rejection. Their prior credit is consumed, so acquire a fresh nonblocking
     * reservation outside the store mutex before the next artifact attempt.
     */
    internal suspend fun reservePendingLazyJournalCredits() {
        val pending = mutex.withLock {
            persistenceScheduler.pendingLazyMutations.values
                .filter { it.journalCredit == null && isLazyGenerationCurrent(it.generation) }
                .map { it.pageKey to it.generation }
        }
        val reservations = pending.map { (pageKey, expectedGeneration) ->
            Triple(pageKey, expectedGeneration, reserveJournalCredit(pageKey))
        }
        try {
            mutex.withLock {
                reservations.forEach { (pageKey, expectedGeneration, credit) ->
                    val current = persistenceScheduler.pendingLazyMutations[pageKey]
                    if (current != null &&
                        current.generation == expectedGeneration &&
                        current.journalCredit == null &&
                        isLazyGenerationCurrent(expectedGeneration) &&
                        credit?.retain() == true
                    ) {
                        persistenceScheduler.pendingLazyMutations[pageKey] = current.copy(journalCredit = credit)
                    } else {
                        credit?.releaseIfUnqueued()
                    }
                }
            }
        } catch (failure: Throwable) {
            reservations.forEach { (_, _, credit) -> credit?.releaseIfUnqueued() }
            throw failure
        }
    }

    // Intermediate page mutations share a bounded buffer and debounce window.
    internal suspend fun hasStagedMutations(): Boolean = mutex.withLock {
        persistenceScheduler.stagedPageKeys.isNotEmpty()
    }

    /** Caller holds [mutex]; the scheduler-scope debounce re-enters through mutex.withLock. */
    internal fun stagePageMutationLocked(
        pageKey: String,
        updated: PageTranslation,
        journalCredit: ChapterJournalCredit? = null,
    ) {
        val existingCredit = persistenceScheduler.stagedJournalCredits[pageKey]
        val stagedCredit = retainStagedJournalCredit(existingCredit, journalCredit)
        if (existingCredit == null && stagedCredit != null) {
            persistenceScheduler.stagedJournalCredits[pageKey] = stagedCredit
        }
        persistenceScheduler.stagedPageKeys.add(pageKey)
        persistenceScheduler.schedulePersist(markPageDirty = false)
        if (persistenceScheduler.stagedPageKeys.size >= GroupCommitConfiguration.MAX_STAGED_PAGES) {
            persistenceScheduler.stagedDebounceJob?.cancel()
            persistenceScheduler.stagedDebounceJob = null
            flushStagedMutationsLocked(CommitPoint.BATCH_CHUNK)
        } else {
            persistenceScheduler.stagedDebounceJob?.cancel()
            persistenceScheduler.stagedDebounceJob = persistenceScheduler.persistScope.launch {
                kotlinx.coroutines.delay(GroupCommitConfiguration.DEBOUNCE_MS)
                // This scheduler-scope debounce job deliberately enters the
                // store mutex before touching staged state or the store.
                mutex.withLock {
                    flushStagedMutationsLocked(CommitPoint.EXPLICIT_FLUSH)
                }
            }
        }
    }

    internal fun flushStagedMutationsLocked(commitPoint: CommitPoint = CommitPoint.EXPLICIT_FLUSH): Boolean {
        if (defunct) return true
        persistenceScheduler.stagedDebounceJob?.cancel()
        persistenceScheduler.stagedDebounceJob = null
        if (persistenceScheduler.stagedPageKeys.isEmpty()) {
            if (commitPoint == CommitPoint.EXPLICIT_FLUSH) {
                artifactManifest?.let { manifest ->
                    artifactEngine?.publishManifestInternal(manifest, syncToDisk = true)
                }
            }
            return true
        }
        val keys = persistenceScheduler.stagedPageKeys.toList()
        persistenceScheduler.stagedPageKeys.clear()
        val store = artifactEngine ?: run {
            keys.forEach { persistenceScheduler.stagedJournalCredits.remove(it)?.releaseIfRetained() }
            return false
        }
        var current = artifactManifest ?: run {
            keys.forEach { persistenceScheduler.stagedJournalCredits.remove(it)?.releaseIfRetained() }
            return false
        }
        val sync = (commitPoint == CommitPoint.EXPLICIT_FLUSH || commitPoint == CommitPoint.BATCH_CHUNK)
        try {
            for (key in keys) {
                val credit = persistenceScheduler.stagedJournalCredits.remove(key)
                try {
                    val page = pages[key]
                    if (page == null) continue
                    val candidate = current.pages[key]?.candidate
                    if (candidate == null) continue
                    val res = store.persistLiveCandidate(
                        manifest = current,
                        pageKey = key,
                        generationId = candidate.generationId,
                        expectedPageVersion = current.pages[key]?.pageVersion ?: 0L,
                        expectedDependencyFingerprint = candidate.dependencyFingerprint.orEmpty(),
                        pageSnapshot = page,
                        origin = candidate.origin,
                        sourceIdentity = page.sourceIdentity(key),
                    )
                    if (res is ChapterArtifactEngine.TransactionOutcome.Committed) {
                        current = res.manifest
                        artifactManifest = current
                        captureLegacyPersistedLocked(
                            pageKey = key,
                            page = page,
                            expected = null,
                            durableFailure = null,
                            credit = credit,
                        )
                    }
                } finally {
                    // A successful capture is already QUEUED; skipped/rejected/throwing
                    // paths release the retained permit here exactly once.
                    credit?.releaseIfUnqueued()
                }
            }
        } catch (failure: Throwable) {
            keys.forEach { key ->
                persistenceScheduler.stagedJournalCredits.remove(key)?.releaseIfRetained()
            }
            throw failure
        }
        if (sync) {
            store.publishManifestInternal(current, syncToDisk = true)
        }
        artifactManifest = current
        return true
    }

    internal fun flushStagedMutationsBlocking() {
        runBlocking {
            persistenceScheduler.flush()
        }
    }

    /**
     * Drains memory-first page publications into the existing artifact bridge.
     * The bridge remains the sole owner of atomic sidecar/manifest writes; this
     * method only changes when that bridge is reached.  A generation mismatch
     * or defunct store discards the work without touching disk.
     *
     * Caller holds [mutex].
     */
    internal fun flushLazyMutationsLocked(): Boolean {
        val pendingLazyMutations = persistenceScheduler.pendingLazyMutations
        if (pendingLazyMutations.isEmpty()) return true
        val pending = pendingLazyMutations.values.toList()
        pendingLazyMutations.clear()
        var allAccepted = true
        pending.forEach { mutation ->
            if (!isLazyGenerationCurrent(mutation.generation)) {
                mutation.journalCredit?.releaseIfRetained()
                return@forEach
            }
            try {
                if (!persistArtifactMutationLocked(
                        pageKey = mutation.pageKey,
                        previous = mutation.previous,
                        updated = mutation.updated,
                        expected = mutation.expected,
                        journalCredit = mutation.journalCredit,
                    )
                ) {
                    allAccepted = false
                    if (isLazyGenerationCurrent(mutation.generation)) {
                        val stillOwnedCredit = mutation.journalCredit?.takeIf { it.retain() }
                        pendingLazyMutations[mutation.pageKey] = mutation.copy(journalCredit = stillOwnedCredit)
                    } else {
                        mutation.journalCredit?.releaseIfRetained()
                    }
                }
            } catch (failure: Throwable) {
                mutation.journalCredit?.releaseIfUnqueued()
                pending.dropWhile { it !== mutation }.drop(1).forEach { remaining ->
                    remaining.journalCredit?.releaseIfRetained()
                }
                throw failure
            }
        }
        return allAccepted
    }

    /**
     * TachiyomiAT: set by [markDefunct] when the store is evicted
     * ([TranslationManager.unregisterActiveTranslationStore], on delete / chapter
     * change). Once defunct, every mutator becomes a no-op and logs at WARN. This
     * guards the delete-then-retranslate race: a batch worker mid-uncancellable
     * ONNX when cancel() was requested can still reach a suspension point AFTER
     * its store was evicted and the on-disk file + images deleted; without this
     * guard it would recreate the deleted file or strand a page at RUNNING.
     * Volatile: the eviction transition is written under [mutex] after the persist join;
     * mutators read it lock-free.
     */
    @Volatile
    private var defunct = false

    /**
     * Eviction snapshots and joins the active persist job outside [mutex]. If
     * that job installs a replacement in its completion handler, preserve the
     * replacement reference and let its defunct-guarded flush finish; this
     * boundary deliberately does not loop to drain replacement jobs.
     */
    suspend fun markDefunct() = withContext(NonCancellable) {
        val pendingPersist = mutex.withLock { persistenceScheduler.persistJob }
        pendingPersist?.let { pending ->
            val completed = withTimeoutOrNull(PERSIST_JOIN_TIMEOUT_MS) { pending.join() }
            if (completed == null) {
                logcat(LogPriority.WARN) {
                    "TachiyomiAT store persist did not finish within ${PERSIST_JOIN_TIMEOUT_MS} ms before eviction"
                }
            }
            pending.cancel()
        }
        mutex.withLock {
            if (persistenceScheduler.persistJob === pendingPersist) {
                persistenceScheduler.persistJob = null
            }
            defunct = true
            generation++
            persistenceScheduler.stagedDebounceJob?.cancel()
            persistenceScheduler.stagedDebounceJob = null
            persistenceScheduler.stagedJournalCredits.values.forEach { it.releaseIfRetained() }
            persistenceScheduler.pendingLazyMutations.values.forEach { it.journalCredit?.releaseIfRetained() }
            journalWriter?.requestDefunctMarker()
            synchronized(pageLeases) { pageLeases.clear() }
        }
        logcat(LogPriority.WARN) { "TachiyomiAT store marked defunct: generation=$generation" }
    }
    val isDefunct: Boolean
        get() = defunct

    // Counts durable writes for persistence tests.
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

    // Durable status reads stay on the store API; the projector derives them
    // from the store's consistent manifest/state/display snapshot.
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
            _state.value = snapshotPages()
            _display.value = displaySnapshotLocked()
        } else {
            logcat(LogPriority.WARN) {
                "TachiyomiAT D9: cap-failure clear publish failed (fail-open): pageKey=$pageKey"
            }
        }
    }

    internal fun admitMutationLocked(): MutationAdmission {
        if (defunct) {
            return MutationAdmission.Rejected(
                code = MutationAdmission.Rejected.Code.STORE_DEFUNCT,
                message = "store is defunct",
            )
        }
        if (engineMode is eu.kanade.translation.persistence.internal.ChapterStoreEngineMode.Durable && artifactManifest != null) {
            return MutationAdmission.Granted
        }
        // Explicitly memory-only stores are used by pure reducer/unit tests;
        // they have no persistence target and therefore cannot accidentally
        // create a legacy document. Production stores always provide a parent
        // or an already-open artifact document and take the lazy creation path below.
        if (engineMode is eu.kanade.translation.persistence.internal.ChapterStoreEngineMode.Memory) {
            return MutationAdmission.Granted
        }
        if (!ensureArtifactStoreLocked()) {
            return MutationAdmission.Rejected(
                code = MutationAdmission.Rejected.Code.ARTIFACT_PUBLICATION_FAILED,
                message = "artifact store creation/publication failed",
            )
        }
        return if (engineMode is eu.kanade.translation.persistence.internal.ChapterStoreEngineMode.Durable && artifactManifest != null) {
            MutationAdmission.Granted
        } else {
            MutationAdmission.Rejected(
                code = MutationAdmission.Rejected.Code.ARTIFACT_PUBLICATION_FAILED,
                message = "artifact store was not established",
            )
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
    ): PatchResult {
        if (defunct) return rejected(pageKey, description, "store is defunct")
        val journalCredit = reserveJournalCredit(pageKey)
        return try {
            mutex.withLock {
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
                    // The same candidate-grace rule as persistArtifactMutationLocked
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
                    if (!publishLocked(current, updated, expected, journalCredit = journalCredit)) {
                        restorePageLocked(pageKey, current)
                        return@withLock rejected(pageKey, description, "ARTIFACT_PUBLICATION_FAILED")
                    }
                    PatchResult.Accepted(snapshotLocked(pageKey))
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
            mutex.withLock {
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
                    val actual = snapshotLocked(pageKey)
                    val expectedLeaseToken = expected.leaseToken
                    val actualLeaseToken = pageLeases[pageKey]?.token
                    val leaseTokenMismatch = expectedLeaseToken
                        ?.takeIf { it != actualLeaseToken }
                        ?.let { expectedToken ->
                            PatchResult.Rejected.Detail.PageLeaseTokenMismatch(
                                expectedToken = expectedToken,
                                actualToken = actualLeaseToken,
                            )
                        }
                        ?.takeIf { mismatch ->
                            rejection ==
                                "page lease token expected=${mismatch.expectedToken} " +
                                "actual=${mismatch.actualToken}"
                        }
                    lastGuardedWriteRejectionDiagnostic = formatWriteDiagnostic(
                        pageKey,
                        "description" to description,
                        "reason" to rejection,
                        "expected" to "{generation=${expected.generation}, pageVersion=${expected.pageVersion}, " +
                            "artifactPageVersion=${expected.artifactPageVersion}, candidateGenerationId=${expected.candidateGenerationId}, " +
                            "dependencyFingerprint=${expected.dependencyFingerprint}, leaseToken=${expected.leaseToken}}",
                        "actual" to "{generation=${actual.generation}, pageVersion=${actual.pageVersion}, " +
                            "artifactPageVersion=${actual.artifactPageVersion}, candidateGenerationId=${actual.candidateGenerationId}, " +
                            "dependencyFingerprint=${actual.dependencyFingerprint}, leaseToken=${actual.leaseToken}}",
                    )
                    rejected(pageKey, description, rejection, leaseTokenMismatch)
                } else {
                    val previous = pages[pageKey]
                    val updated = ownedPage(pageKey, update(previous?.detachedCopy()))
                    pages = pages.put(pageKey, updated)
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
                        return@withLock rejected(pageKey, description, "ARTIFACT_PUBLICATION_FAILED")
                    }
                    PatchResult.Accepted(snapshotLocked(pageKey))
                }
            }
        } finally {
            journalCredit?.releaseIfHeld()
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
        val journalCredit = reserveJournalCredit(pageKey)
        return try {
            mutex.withLock {
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
                    journalCredit = journalCredit,
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
     * Synchronously flips pages with [isStageRunning] to CANCELLED in [_state] and [_display]
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
        val current = _state.value
        val hasRunning = current.values.any { it?.isStageRunning == true }
        if (!hasRunning) return 0
        val updatedPages = buildMap {
            current.forEach { (pageKey, page) ->
                if (page != null && page.isStageRunning && !page.hasRenderedResult && !page.isStageFailed) {
                    if (origin != null && pageLeaseOwner(pageKey)?.let { it != origin } == true) {
                        put(pageKey, page)
                        return@forEach
                    }
                    if (hasActiveBatchStageLease(pageKey)) {
                        logcat(LogPriority.INFO) {
                            "TachiyomiAT cancel skipped: page holds an active BATCH stage lease " +
                                "writer=fastCancelInFlightStagesInMemory pageKey=$pageKey"
                        }
                        // Preserve the page UNCHANGED — the batch owns its state.
                        put(pageKey, page)
                    } else {
                        flipped++
                        put(
                            pageKey,
                            page.detachedCopy().apply {
                                cancelInFlightStages()
                                ocrError = cancellationReason
                                updatedAt = System.currentTimeMillis()
                            },
                        )
                    }
                } else if (page != null) {
                    put(pageKey, page)
                }
            }
        }
        // Every running page was batch-leased: nothing flipped, keep the
        // current map/state as-is instead of republishing identical values.
        if (flipped == 0) return 0
        pages = updatedPages.toPersistentMap()
        _state.value = snapshotPages()
        _display.value = displaySnapshotLocked()
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
    private suspend fun applyStagePatch(
        patch: StagePatch,
        description: String,
    ): StagePatchResult {
        if (defunct) return rejectedStage(patch.pageKey, description, "store is defunct")
        val journalCredit = reserveJournalCredit(patch.pageKey)
        return try {
            mutex.withLock {
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
                    is StagePatch.Ocr -> mergeOcrLocked(current, patch.value, description, journalCredit)
                    is StagePatch.Translation -> mergeTranslationLocked(current, patch.value, description, journalCredit)
                    is StagePatch.Render -> mergeRenderLocked(current, patch.value, description, journalCredit)
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

    private fun mergeOcrLocked(
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
            mutex.withLock {
                when (val admission = admitMutationLocked()) {
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
                val live = current ?: return@withLock rejectedCheckpoint(description, "page missing")
                // A never-inpainted page's revision stays at its 0 default (the OCR
                // preflight never inpaints), but the checkpoint gate requires
                // CURRENT_INPAINT_REVISION. The stamp must precede the snapshot
                // fingerprint, the content fingerprint, and the DTO build so the
                // published sidecar, the checkpoint claim, and the.1
                // committed-side comparison canonicalize on one value
                // (CleanedPublication stamps the same field at inpaint publication).
                if (live.inpaintRevision < PageTranslation.CURRENT_INPAINT_REVISION) {
                    live.inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION
                }
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
                    ocrContentFingerprint = pageOcrContentFingerprint(pageKey, live, naturalPageIndex, sourceOrientation),
                    ocrPageSnapshotPointer = SidecarPointer(
                        fileName = snapshotFileName,
                        schemaVersion = 1,
                        contentFingerprint = snapshotFingerprint,
                    ),
                    inpaintMaskRevision = live.inpaintRevision,
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
                        _state.value = snapshotPages()
                        _display.value = displaySnapshotLocked()
                        captureLegacyPersistedLocked(
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
                        CheckpointOcrResult.Committed(snapshotLocked(pageKey), outcome.manifest)
                    }
                    is ChapterArtifactEngine.TransactionOutcome.Rejected ->
                        rejectedCheckpoint(description, outcome.reason)
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
        page: PageTranslation,
        naturalPageIndex: Int?,
        sourceOrientation: String?,
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
        inpaintMaskRevision = page.inpaintRevision,
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

    private fun mergeTranslationLocked(
        current: PageTranslation?,
        patch: TranslationStagePatch,
        description: String,
        journalCredit: ChapterJournalCredit?,
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
        page.errorMessage = patch.errorMessage
        val updated = ownedPage(patch.pageKey, page)
        pages = pages.put(patch.pageKey, updated)
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
     * every legacy caller keeps byte-identical merge behavior.
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

    private fun mergeRenderLocked(
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

    private fun pageWriteRejection(pageKey: String, expected: PatchPrecondition): String? {
        val current = pages[pageKey]
        val artifactPage = artifactManifest?.pages?.get(pageKey)
        return when {
            expected.generation != generation ->
                "generation expected=${expected.generation} actual=$generation"
            expected.pageVersion != (current?.pageVersion ?: 0L) ->
                "pageVersion expected=${expected.pageVersion} actual=${current?.pageVersion ?: 0L}"
            expected.artifactPageVersion != null && expected.artifactPageVersion != artifactPage?.pageVersion ->
                "artifact pageVersion expected=${expected.artifactPageVersion} actual=${artifactPage?.pageVersion}"
            expected.candidateGenerationId != null &&
                expected.candidateGenerationId != artifactPage?.candidate?.generationId ->
                "candidate generation expected=${expected.candidateGenerationId} " +
                    "actual=${artifactPage?.candidate?.generationId}"
            expected.dependencyFingerprint != null &&
                artifactPage?.candidate?.let { candidate ->
                    expected.dependencyFingerprint != candidate.dependencyFingerprint
                } == true ->
                "candidate dependency fingerprint expected=${expected.dependencyFingerprint} " +
                    "actual=${artifactPage?.candidate?.dependencyFingerprint}"
            expected.blockFingerprints != null &&
                expected.blockFingerprints != current?.blockFingerprints().orEmpty() ->
                "block fingerprint changed"
            expected.leaseToken != null && pageLeases[pageKey]?.token != expected.leaseToken ->
                "page lease token expected=${expected.leaseToken} actual=${pageLeases[pageKey]?.token}"
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

    suspend fun deletePage(pageKey: String) {
        if (defunct) return
        val journalCredit = reserveJournalCredit(pageKey)
        try {
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
                    val priorPageVersion = pages[pageKey]?.pageVersion ?: 0L
                    val priorArtifactContentHash = pageArtifactContentHash(artifactManifest, pageKey, pages[pageKey])
                    val deleted = deleteArtifactPageLocked(pageKey)
                    if (deleted) {
                        captureLegacyDeletionLocked(
                            pageKey,
                            priorPageVersion,
                            journalCredit,
                            artifactContentHash = priorArtifactContentHash,
                        )
                    }
                    pages = pages.remove(pageKey)
                    committedDisplay = committedDisplay.remove(pageKey)
                    retiredCleanedImages.remove(pageKey)
                    pageLeases.remove(pageKey)
                    schedulePersist()
                    _state.value = snapshotPages()
                    _display.value = displaySnapshotLocked()
                }
            }
        } finally {
            journalCredit?.releaseIfHeld()
        }
    }

    suspend fun replaceAll(updatedPages: Map<String, PageTranslation>) {
        if (defunct) {
            logcat(LogPriority.WARN) {
                "TachiyomiAT store replaceAll rejected: store is defunct"
            }
            return
        }
        val bulkCredit = reserveJournalCredit("<bulk_replace>")
        try {
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
                // Keep earlier staged commits outside this aggregate record without
                // forcing an empty-staging manifest sync under the mutex.
                if (persistenceScheduler.stagedPageKeys.isNotEmpty()) {
                    flushStagedMutationsLocked(CommitPoint.EXPLICIT_FLUSH)
                }
                check(activeBulkJournalCapture == null) { "nested bulk journal capture" }
                val bulk = BulkJournalCapture(
                    kind = ChapterJournalFormat.RecordKind.BULK_REPLACE,
                    operation = "replace_all",
                )
                activeBulkJournalCapture = bulk
                try {
                    if (artifactManifest != null) {
                        artifactManifest?.pages?.keys?.toList().orEmpty().forEach { pageKey ->
                            val pageVersion = pages[pageKey]?.pageVersion ?: artifactManifest?.pages?.get(pageKey)?.pageVersion ?: 0L
                            val priorArtifactContentHash = pageArtifactContentHash(artifactManifest, pageKey, pages[pageKey])
                            if (deleteArtifactPageLocked(pageKey)) {
                                captureLegacyDeletionLocked(
                                    pageKey,
                                    pageVersion,
                                    credit = null,
                                    artifactContentHash = priorArtifactContentHash,
                                )
                            }
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
                    // Intermediate page writes are staged by the unchanged legacy classifier;
                    // flush only when present before the single aggregate shadow record is handed off.
                    if (persistenceScheduler.stagedPageKeys.isNotEmpty()) {
                        flushStagedMutationsLocked(CommitPoint.EXPLICIT_FLUSH)
                    }
                    persistenceScheduler.dirty = !persistLocked()
                    _state.value = snapshotPages()
                    _display.value = displaySnapshotLocked()
                } finally {
                    finishBulkJournalCaptureLocked(bulk, bulkCredit)
                }
            }
        } finally {
            bulkCredit?.releaseIfHeld()
        }
    }

    /** Moves source-keyed pages to their completed-download keys atomically. */
    suspend fun rekeyPages(
        onlineKeys: List<String>,
        onDiskKeys: List<String>,
    ): List<Pair<String, String>> {
        if (defunct || onlineKeys.size != onDiskKeys.size) return emptyList()
        val bulkCredit = reserveJournalCredit("<bulk_rekey>")
        return try {
            mutex.withLock {
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

                val previousPages = pages
                val moveByOldKey = moves.toMap()
                val oldArtifactContentHashes = moveByOldKey.keys.associateWith {
                    pageArtifactContentHash(artifactManifest, it, previousPages[it])
                }
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
                check(activeBulkJournalCapture == null) { "nested bulk journal capture" }
                val bulk = BulkJournalCapture(
                    kind = ChapterJournalFormat.RecordKind.BULK_REKEY,
                    operation = "rekey_pages",
                    rekeyMapping = moveByOldKey,
                )
                activeBulkJournalCapture = bulk
                try {
                    val rekeyed = rekeyArtifactPagesLocked(moveByOldKey)
                    if (rekeyed) {
                        moves.forEach { (oldKey, newKey) ->
                            captureLegacyDeletionLocked(
                                pageKey = oldKey,
                                pageVersion = previousPages[oldKey]?.pageVersion ?: 0L,
                                credit = null,
                                artifactContentHash = oldArtifactContentHashes[oldKey],
                            )
                            updatedPages[newKey]?.let { page ->
                                captureLegacyPersistedLocked(
                                    pageKey = newKey,
                                    page = page,
                                    expected = null,
                                    durableFailure = null,
                                    credit = null,
                                )
                            }
                        }
                    }
                    retiredCleanedImages.keys.toList().forEach { pageKey ->
                        if (pageKey !in updatedPages) retiredCleanedImages.remove(pageKey)
                    }
                    persistenceScheduler.dirty = true
                    schedulePersist()
                    _state.value = snapshotPages()
                    _display.value = displaySnapshotLocked()
                    moves
                } finally {
                    finishBulkJournalCaptureLocked(bulk, bulkCredit)
                }
            }
        } finally {
            bulkCredit?.releaseIfHeld()
        }
    }

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
     * the absence. Without context (both defaults) the legacy self-derived
     * stamp below is byte-identical to pre- behavior.
     */
    suspend fun preRegisterPages(
        pageKeys: List<String>,
        probedSourcePageCount: Int? = null,
        sourceCountKnown: Boolean = false,
    ): PagePreRegistration {
        if (pageKeys.isEmpty()) return PagePreRegistration.Accepted
        if (defunct) {
            logcat(LogPriority.WARN) {
                "TachiyomiAT store preRegisterPages rejected: store is defunct"
            }
            return PagePreRegistration.Rejected("store is defunct")
        }
        // Allocate the writer object outside the store mutex. The epoch remains
        // file-free until a legacy manifest commit offers its inventory record.
        val inventoryWriter = try {
            ensureJournalWriter()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            logcat(LogPriority.WARN, failure) {
                "TachiyomiAT shadow journal initialization failed before page registration; legacy continues"
            }
            null
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
            val foundCount = pageKeys.distinct().size
            val crossCheckKnown = sourceCountKnown && probedSourcePageCount != null
            val crossCheckUnknown = sourceCountKnown && probedSourcePageCount == null
            val targetCount = if (crossCheckKnown) maxOf(foundCount, probedSourcePageCount!!) else foundCount
            pendingExpectedPageCount = maxOf(pendingExpectedPageCount ?: 0, targetCount)
            pendingExpectedPageCountTrusted = !crossCheckUnknown
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
            if (engineMode !is eu.kanade.translation.persistence.internal.ChapterStoreEngineMode.Durable &&
                artifactParent != null &&
                artifactFileName != null
            ) {
                ensureArtifactStoreLocked()
            }
            val store = artifactEngine
            val manifest = artifactManifest
            val expected = pendingExpectedPageCount
            val expectedTrusted = when {
                crossCheckUnknown -> false
                else -> manifest?.expectedPageCountTrusted == true || pendingExpectedPageCountTrusted
            }
            // The partial delta rides the same publish as the totals; a full
            // cross-check (missing == 0) clears a previously recorded label.
            val partialInfo = when {
                crossCheckKnown -> {
                    val missing = maxOf(0, probedSourcePageCount!! - foundCount)
                    if (missing == 0) {
                        null
                    } else {
                        PartialBatchInfo(
                            expectedSourcePageCount = probedSourcePageCount,
                            missingPageCount = missing,
                            determinedFrom = PartialBatchDetermination.DOWNLOAD_CROSSCHECK,
                            recordedAtEpochMs = System.currentTimeMillis(),
                        )
                    }
                }
                crossCheckUnknown -> PartialBatchInfo(
                    expectedSourcePageCount = null,
                    missingPageCount = 0,
                    determinedFrom = PartialBatchDetermination.UNKNOWN,
                    recordedAtEpochMs = System.currentTimeMillis(),
                )
                else -> manifest?.partialBatchInfo
            }
            if (store != null && manifest != null && expected != null) {
                val updated = manifest.copy(
                    expectedPageCount = maxOf(expected, manifest.expectedPageCount ?: 0),
                    expectedPageCountTrusted = expectedTrusted,
                    partialBatchInfo = partialInfo,
                    updatedAtEpochMs = System.currentTimeMillis(),
                )
                if (
                    updated.expectedPageCount == manifest.expectedPageCount &&
                    updated.expectedPageCountTrusted == manifest.expectedPageCountTrusted &&
                    updated.partialBatchInfo == manifest.partialBatchInfo
                ) {
                    pendingExpectedPageCount = null
                    pendingExpectedPageCountTrusted = false
                } else if (store.publishManifest(updated)) {
                    artifactManifest = updated
                    pendingExpectedPageCount = null
                    pendingExpectedPageCountTrusted = false
                    inventoryWriter?.captureInventory(journalInventorySnapshot(updated))
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
                persistenceScheduler.dirty = !persistLocked()
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
        journalCredit: ChapterJournalCredit? = null,
    ): Boolean {
        val pageKey = updated.sourceFileName ?: ""
        // Active reader stores are display-first: publish the complete live
        // snapshot and enqueue the artifact transaction for the IO worker.
        // Durable failure records remain synchronous so cancellation/retry
        // semantics never report a failure before its ledger is recorded.
        if (lazyPersistenceEnabled && durableFailure == null) {
            return publishLazyLocked(pageKey, previous, updated, expected, journalCredit)
        }
        val isDurable = shouldPersistUpdate(previous, updated)
        //  R2 restricted UI-before-persist: transient non-durable updates publish StateFlows
        // before staging into memory; durable results keep persist-first.
        if (GroupCommitConfiguration.enabled && !isDurable) {
            _state.value = snapshotPages()
            _display.value = displaySnapshotLocked()
        }
        val artifactAccepted = if (isDurable || artifactManifest?.pages?.containsKey(pageKey) != true) {
            persistArtifactMutationLocked(
                pageKey = pageKey,
                previous = previous,
                updated = updated,
                expected = expected,
                durableFailure = durableFailure,
                journalCredit = journalCredit,
            )
        } else {
            true
        }
        if (!artifactAccepted) return false
        promoteDisplayIfReadyLocked(pageKey, updated)
        if (!GroupCommitConfiguration.enabled || isDurable) {
            _state.value = snapshotPages()
            _display.value = displaySnapshotLocked()
        }
        return true
    }

    private fun publishLazyLocked(
        pageKey: String,
        previous: PageTranslation?,
        updated: PageTranslation,
        expected: PatchPrecondition?,
        journalCredit: ChapterJournalCredit?,
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
            val existingCredit = persistenceScheduler.pendingLazyMutations[pageKey]?.journalCredit
            val retainedCredit = existingCredit ?: journalCredit?.takeIf { it.retain() }
            persistenceScheduler.pendingLazyMutations[pageKey] = PendingLazyMutation(
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
            )
        }
        promoteDisplayIfReadyLocked(pageKey, updated)
        if (shouldQueueArtifact) persistenceScheduler.dirty = true
        _state.value = snapshotPages()
        _display.value = displaySnapshotLocked()
        if (shouldQueueArtifact) persistenceScheduler.schedulePersist(markPageDirty = false)
        return true
    }

    private fun restorePageLocked(pageKey: String, previous: PageTranslation?) {
        pages = if (previous == null) pages.remove(pageKey) else pages.put(pageKey, previous)
        _state.value = snapshotPages()
        _display.value = displaySnapshotLocked()
    }

    /**
     * Production writer bridge for artifact authority. Every
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
        journalCredit: ChapterJournalCredit? = null,
    ): Boolean {
        lastArtifactPublicationRejectionDiagnostic = null
        fun reject(reason: String): Boolean {
            val page = artifactManifest?.pages?.get(pageKey)
            lastArtifactPublicationRejectionDiagnostic = formatWriteDiagnostic(
                pageKey,
                "reason" to reason,
                "expected" to "{generation=${expected?.generation}, pageVersion=${expected?.pageVersion}, " +
                    "artifactPageVersion=${expected?.artifactPageVersion}, candidateGenerationId=${expected?.candidateGenerationId}, " +
                    "dependencyFingerprint=${expected?.dependencyFingerprint}, leaseToken=${expected?.leaseToken}}",
                "localArtifact" to "{pageVersion=${page?.pageVersion}, " +
                    "candidateGenerationId=${page?.candidate?.generationId}, " +
                    "dependencyFingerprint=${page?.candidate?.dependencyFingerprint}}",
            )
            return false
        }
        if (engineMode is eu.kanade.translation.persistence.internal.ChapterStoreEngineMode.Memory) {
            // Pure in-memory stores have no persistence target and never
            // create a compatibility document as a side effect.
            // Preserve the historical dirty/flush contract for callers
            // that use an in-memory store as a persistence probe: an
            // explicit flush still attempts once and keeps the dirty bit
            // set because there is no durable artifact to publish.
            persistenceScheduler.dirty = true
            journalCredit?.releaseIfUnqueued()
            return true
        }
        if (engineMode !is eu.kanade.translation.persistence.internal.ChapterStoreEngineMode.Durable &&
            !ensureArtifactStoreLocked()
        ) {
            return reject("artifact store initialization failed")
        }
        val store = (engineMode as? eu.kanade.translation.persistence.internal.ChapterStoreEngineMode.Durable)?.artifact
            ?: return reject("durable artifact store unavailable")
        var manifest = artifactManifest ?: run {
            journalCredit?.releaseIfUnqueued()
            return true
        }
        if (pageKey.isEmpty()) {
            journalCredit?.releaseIfUnqueued()
            return true
        }
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
            //  LI-x: this publication used to blind-publish
            // (`publishManifest`) a manifest rebuilt from the FAÇADE snapshot,
            // silently reverting any newer durable field: the >8-page open
            // path's background manifest publication can race without
            // refreshing the façade, so the next resume-hydration adoption's
            // registration write must not clobber newer durable fields. Route
            // through the CAS'd sidecar-pointer transaction instead: the
            // registration mutation (page-set + expected count) is recomputed
            // against the FRESH durable manifest on a stale-manifest rejection
            // — one-shot, the same semantics as the envelope-plan seam — so
            // the mutation is pointer-set-only and every other durable field
            // (legacyMigration included) is carried forward by construction.
            when (
                val registration = store.publishSidecarPointersWithStaleRetry(
                    manifest = manifest,
                    sidecars = emptyList(),
                    updatePointers = { current ->
                        val keys = (pendingArtifactPageRegistrations + pageKey)
                            .filter { it.isNotEmpty() && it !in current.pages }
                        val currentFirstReaderBaseline =
                            if (current.expectedPageCount == null && pendingExpectedPageCount == null) {
                                pages.size
                            } else {
                                0
                            }
                        current.copy(
                            pages = current.pages + keys.associateWith {
                                eu.kanade.translation.persistence.artifact.PageArtifactRecord(pageKey = it)
                            },
                            expectedPageCount = maxOf(
                                current.expectedPageCount ?: 0,
                                pendingExpectedPageCount ?: 0,
                                currentFirstReaderBaseline,
                            ).takeIf { it > 0 },
                            expectedPageCountTrusted =
                            current.expectedPageCountTrusted || pendingExpectedPageCountTrusted,
                        )
                    },
                    nowEpochMs = System.currentTimeMillis(),
                    seam = "page-registration",
                )
            ) {
                is ChapterArtifactEngine.TransactionOutcome.Committed -> {
                    manifest = registration.manifest
                    artifactManifest = manifest
                    pendingArtifactPageRegistrations.removeAll(registrationKeys.toSet())
                    if (expectedCountChanged) {
                        pendingExpectedPageCount = null
                        pendingExpectedPageCountTrusted = false
                    }
                    journalWriter?.captureInventory(journalInventorySnapshot(manifest))
                }
                is ChapterArtifactEngine.TransactionOutcome.Rejected -> {
                    logcat(LogPriority.WARN) {
                        "TachiyomiAT artifact page registration failed: pageKey=$pageKey " +
                            "count=${registrationKeys.size} reason=${registration.reason}"
                    }
                    return reject("page registration rejected: ${registration.reason}")
                }
            }
        }
        val record = manifest.pages.getValue(pageKey)
        if (expected != null) {
            if (expected.artifactPageVersion != null && expected.artifactPageVersion != record.pageVersion) {
                logcat(LogPriority.WARN) {
                    "TachiyomiAT artifact candidate write rejected: pageKey=$pageKey " +
                        "reason=artifact pageVersion expected=${expected.artifactPageVersion} actual=${record.pageVersion}"
                }
                return reject("artifact pageVersion expected=${expected.artifactPageVersion} actual=${record.pageVersion}")
            }
            if (expected.candidateGenerationId != null && expected.candidateGenerationId != record.candidate?.generationId) {
                logcat(LogPriority.WARN) {
                    "TachiyomiAT artifact candidate write rejected: pageKey=$pageKey " +
                        "reason=candidate generation expected=${expected.candidateGenerationId} " +
                        "actual=${record.candidate?.generationId}"
                }
                return reject(
                    "candidate generation expected=${expected.candidateGenerationId} " +
                        "actual=${record.candidate?.generationId}",
                )
            }
            if (expected.dependencyFingerprint != null &&
                expected.dependencyFingerprint != record.candidate?.dependencyFingerprint &&
                record.candidate != null
            ) {
                logcat(LogPriority.WARN) {
                    "TachiyomiAT artifact candidate write rejected: pageKey=$pageKey " +
                        "reason=dependency fingerprint expected=${expected.dependencyFingerprint} " +
                        "actual=${record.candidate?.dependencyFingerprint}"
                }
                return reject(
                    "dependency fingerprint expected=${expected.dependencyFingerprint} " +
                        "actual=${record.candidate?.dependencyFingerprint}",
                )
            }
        } else if (pageLeases[pageKey] != null) {
            logcat(LogPriority.WARN) {
                "TachiyomiAT artifact candidate write rejected: pageKey=$pageKey reason=unfenced active lease"
            }
            return reject("unfenced active lease token=${pageLeases[pageKey]?.token}")
        }
        // Compatibility writers may resume an on-disk candidate after process
        // death before a new page lease is attached. Preserve the durable
        // candidate provenance in that narrow no-lease window; otherwise a
        // READER_ADHOC candidate would be misclassified as BATCH and its next
        // snapshot could never be persisted.
        //
        //  round 3: a durable-FAILURE publication is a page-level ledger
        // write, not work product — keep the live candidate's own provenance
        // when one exists. Deriving the origin from the CURRENT lease holder
        // routed the failure persist through cancelLiveCandidate +
        // openCandidate under a FOREIGN origin (the display-tail drain
        // persists its typed failure while a MANUAL reader lease holds the
        // page's Render stage), splitting the ONE atomic page+failure
        // publication the API documents into a cancel/reopen/persist chain
        // against a candidate the failure writer does not own.
        val leaseOrigin = pageLeases[pageKey]?.origin?.toArtifactOrigin()
        val origin = when {
            durableFailure != null && record.candidate != null -> record.candidate.origin
            leaseOrigin != null -> leaseOrigin
            else -> record.candidate?.origin ?: ArtifactOrigin.BATCH
        }
        val candidate = record.candidate
        val dependencyFingerprint = expected?.dependencyFingerprint
            ?: candidate?.dependencyFingerprint
            ?: StageFingerprints.pageSnapshot(previous ?: updated)
        if (candidate == null || candidate.origin != origin) {
            val baseManifest = if (candidate != null && candidate.origin != origin) {
                when (val cancelled = store.cancelLiveCandidate(manifest, pageKey, candidate.generationId)) {
                    is ChapterArtifactEngine.TransactionOutcome.Committed -> cancelled.manifest
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
                is ChapterArtifactEngine.TransactionOutcome.Committed -> {
                    reportCandidateReuse(opened.candidateOpenState)
                    opened.manifest
                }
                is ChapterArtifactEngine.TransactionOutcome.Rejected -> {
                    reportCandidateReuse(opened.candidateOpenState)
                    logcat(LogPriority.WARN) {
                        "TachiyomiAT artifact candidate open rejected: pageKey=$pageKey reason=${opened.reason}"
                    }
                    return reject("candidate open rejected: ${opened.reason}")
                }
            }
            artifactManifest = manifest
        }
        val currentCandidate = manifest.pages.getValue(pageKey).candidate
            ?: run { return reject("candidate missing after open") }
        val expectedPageVersion = manifest.pages.getValue(pageKey).pageVersion
        when (classifyMutation(updated, durableFailure)) {
            ArtifactMutation.Failure -> {
                val failure = checkNotNull(durableFailure)
                val persisted = store.persistLiveCandidateAndFailure(
                    manifest = manifest,
                    pageKey = pageKey,
                    generationId = currentCandidate.generationId,
                    expectedPageVersion = expectedPageVersion,
                    expectedDependencyFingerprint = currentCandidate.dependencyFingerprint.orEmpty(),
                    pageSnapshot = updated,
                    origin = origin,
                    failure = failure,
                    sourceIdentity = updated.sourceIdentity(pageKey),
                )
                manifest = when (persisted) {
                    is ChapterArtifactEngine.TransactionOutcome.Committed -> persisted.manifest
                    is ChapterArtifactEngine.TransactionOutcome.Rejected -> {
                        logcat(LogPriority.WARN) {
                            "TachiyomiAT artifact candidate persist rejected: pageKey=$pageKey reason=${persisted.reason}"
                        }
                        return reject("candidate persist rejected: ${persisted.reason}")
                    }
                }
                artifactManifest = manifest
                captureLegacyPersistedLocked(pageKey, updated, expected, failure, journalCredit)
            }
            ArtifactMutation.TerminalPromotion -> {
                // Promote the candidate at the terminal-page commit point.
                flushStagedMutationsLocked(CommitPoint.PAGE_TERMINAL_PROMOTION)
                val current = artifactManifest ?: manifest
                val currentCand = current.pages[pageKey]?.candidate ?: currentCandidate
                val promoted = store.promoteLiveCandidate(
                    manifest = current,
                    pageKey = pageKey,
                    generationId = currentCand.generationId,
                    expectedPageVersion = current.pages[pageKey]?.pageVersion ?: expectedPageVersion,
                    expectedDependencyFingerprint = currentCand.dependencyFingerprint.orEmpty(),
                    pageSnapshot = updated,
                    origin = origin,
                    sourceIdentity = updated.sourceIdentity(pageKey),
                )
                manifest = when (promoted) {
                    is ChapterArtifactEngine.TransactionOutcome.Committed -> promoted.manifest
                    is ChapterArtifactEngine.TransactionOutcome.Rejected -> {
                        logcat(LogPriority.WARN) {
                            "TachiyomiAT artifact candidate promotion rejected: pageKey=$pageKey reason=${promoted.reason}"
                        }
                        return reject("candidate promotion rejected: ${promoted.reason}")
                    }
                }
                artifactManifest = manifest
                captureLegacyPersistedLocked(pageKey, updated, expected, durableFailure, journalCredit)
            }
            ArtifactMutation.ManualEdit -> {
                // Tier 1: User manual edits are immediately persisted without staging
                flushStagedMutationsLocked(CommitPoint.EXPLICIT_FLUSH)
                val current = artifactManifest ?: manifest
                val currentCand = current.pages[pageKey]?.candidate ?: currentCandidate
                val persisted = store.persistLiveCandidate(
                    manifest = current,
                    pageKey = pageKey,
                    generationId = currentCand.generationId,
                    expectedPageVersion = current.pages[pageKey]?.pageVersion ?: expectedPageVersion,
                    expectedDependencyFingerprint = currentCand.dependencyFingerprint.orEmpty(),
                    pageSnapshot = updated,
                    origin = origin,
                    sourceIdentity = updated.sourceIdentity(pageKey),
                )
                manifest = when (persisted) {
                    is ChapterArtifactEngine.TransactionOutcome.Committed -> persisted.manifest
                    is ChapterArtifactEngine.TransactionOutcome.Rejected -> {
                        logcat(LogPriority.WARN) {
                            "TachiyomiAT artifact candidate persist rejected: pageKey=$pageKey reason=${persisted.reason}"
                        }
                        return reject("manual candidate persist rejected: ${persisted.reason}")
                    }
                }
                artifactManifest = manifest
                captureLegacyPersistedLocked(pageKey, updated, expected, durableFailure, journalCredit)
                return true
            }
            ArtifactMutation.Intermediate -> {
                // Buffer intermediate, non-terminal stage writes for a grouped commit.
                stagePageMutationLocked(pageKey, updated, journalCredit)
                return true
            }
            ArtifactMutation.Legacy -> {
                val persisted = store.persistLiveCandidate(
                    manifest = manifest,
                    pageKey = pageKey,
                    generationId = currentCandidate.generationId,
                    expectedPageVersion = expectedPageVersion,
                    expectedDependencyFingerprint = currentCandidate.dependencyFingerprint.orEmpty(),
                    pageSnapshot = updated,
                    origin = origin,
                    sourceIdentity = updated.sourceIdentity(pageKey),
                )
                manifest = when (persisted) {
                    is ChapterArtifactEngine.TransactionOutcome.Committed -> persisted.manifest
                    is ChapterArtifactEngine.TransactionOutcome.Rejected -> {
                        logcat(LogPriority.WARN) {
                            "TachiyomiAT artifact candidate persist rejected: pageKey=$pageKey reason=${persisted.reason}"
                        }
                        return reject("candidate persist rejected: ${persisted.reason}")
                    }
                }
                artifactManifest = manifest
                if (updated.hasRenderedResult || updated.isTextlessTerminal) {
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
                        is ChapterArtifactEngine.TransactionOutcome.Committed -> promoted.manifest
                        is ChapterArtifactEngine.TransactionOutcome.Rejected -> {
                            // The candidate persist above already committed durable legacy
                            // state. Preserve that event in the journal even though its
                            // follow-up promotion was rejected.
                            artifactManifest = manifest
                            captureLegacyPersistedLocked(
                                pageKey,
                                updated,
                                expected,
                                durableFailure,
                                journalCredit,
                            )
                            logcat(LogPriority.WARN) {
                                "TachiyomiAT artifact candidate promotion rejected: pageKey=$pageKey reason=${promoted.reason}"
                            }
                            return reject("candidate promotion rejected: ${promoted.reason}")
                        }
                    }
                    artifactManifest = manifest
                }
            }
        }
        artifactManifest = manifest
        captureLegacyPersistedLocked(pageKey, updated, expected, durableFailure, journalCredit)
        return true
    }

    /** Lazily creates the artifact store for a chapter that has no manifest yet. */
    private fun ensureArtifactStoreLocked(): Boolean {
        if (engineMode is eu.kanade.translation.persistence.internal.ChapterStoreEngineMode.Durable) {
            return artifactManifest != null
        }
        // Lazy production stores must provide an artifact parent/name. A store
        // opened before its document existed resolves the parent through
        // [fileCreator] here, so the first real write creates the artifact
        // manifest without recreating a flat compatibility file.
        val parent = artifactParent
            ?: translationFile?.parentFile
            ?: fileCreator?.let { creator -> runCatching { creator() }.getOrNull() }
            ?: return false
        val fileName = artifactFileName ?: translationFile?.name ?: return false
        val layout = ChapterArtifactLayout.fromTranslationFileName(fileName)
        val documents = AtomicChapterDocuments(UniFileChapterDocumentIo(parent))
        val store = ChapterArtifactEngine(documents, layout)
        val manifest = withArtifactOpenLock(parent, fileName) { openLock ->
            synchronized(openLock.monitor) { store.load().manifest }
        }
        engineMode = eu.kanade.translation.persistence.internal.ChapterStoreEngineMode.Durable(store)
        artifactManifest = manifest
        return true
    }

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

    /**
     * Lazily loads full page snapshot (including text blocks) from durable storage
     * if the page is currently backed by a synthesized or empty placeholder.
     */
    suspend fun getOrLoadPageSnapshot(pageKey: String): PageTranslation? = mutex.withLock {
        val current = pages[pageKey]
        if (current != null && current.blocks.isNotEmpty()) {
            return@withLock current
        }
        val record = artifactManifest?.pages?.get(pageKey)
        val snapshotFileName = record?.candidate?.pageSnapshotFileName
            ?: record?.committed?.pageSnapshotFileName
        val store = artifactEngine
        if (snapshotFileName != null && store != null) {
            val loaded = store.readPageSnapshot(snapshotFileName)
            if (loaded != null) {
                pages = pages.put(pageKey, loaded)
                _state.value = snapshotPages()
                if (record?.committed != null) {
                    committedDisplay = committedDisplay.put(
                        pageKey,
                        CommittedPageDisplay(
                            page = loaded.detachedCopy(),
                            pageVersion = loaded.pageVersion,
                            displayFingerprint = displayFingerprintOf(loaded),
                            promotedAtEpochMs = loaded.updatedAt,
                        ),
                    )
                    _display.value = displaySnapshotLocked()
                }
                return@withLock loaded
            }
        }
        current
    }

    /** The frozen committed display bundle for [pageKey], if one exists. */
    internal fun committedDisplayPage(pageKey: String): PageTranslation? =
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
    internal fun drainRetiredCleanedImage(pageKey: String): String? =
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
        return mutex.withLock {
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

    private fun deleteArtifactPageLocked(pageKey: String): Boolean {
        val store = artifactEngine ?: return false
        val manifest = artifactManifest ?: return false
        val outcome = store.deleteLivePage(manifest, pageKey)
        when (outcome) {
            is ChapterArtifactEngine.TransactionOutcome.Committed -> {
                artifactManifest = outcome.manifest
                return true
            }
            is ChapterArtifactEngine.TransactionOutcome.Rejected -> logcat(LogPriority.WARN) {
                "TachiyomiAT artifact page deletion rejected: pageKey=$pageKey reason=${outcome.reason}"
            }
        }
        return false
    }

    private fun rekeyArtifactPagesLocked(moveByOldKey: Map<String, String>): Boolean {
        val store = artifactEngine ?: return false
        val manifest = artifactManifest ?: return false
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
        if (updatedPages == manifest.pages && updatedFailures == manifest.durableFailures) return false
        val updated = manifest.copy(
            pages = updatedPages,
            durableFailures = updatedFailures,
            updatedAtEpochMs = System.currentTimeMillis(),
        )
        if (store.publishManifest(updated)) {
            artifactManifest = updated
            return true
        } else {
            logcat(LogPriority.WARN) { "TachiyomiAT artifact page re-key publication failed" }
            return false
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

    private fun snapshotPages(): Map<String, PageTranslation> =
        pages.entries.associate { (key, page) -> key to page.detachedCopy() }

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

    // These lifecycle methods preserve a single persistence boundary for
    // reset, resolver, and batch callers. The scheduler serializes file I/O;
    // markDefunct retains its bounded persist-job join.

    private fun persistLocked(): Boolean = persistenceScheduler.persistLocked()

    suspend fun flush() = persistenceScheduler.flush()

    suspend fun closeAndFlush() = persistenceScheduler.closeAndFlush {
        journalWriter?.drainAndClose()
    }

    fun close() = persistenceScheduler.close {
        journalWriter?.drainAndClose()
    }

    /** Performs the bounded artifact-tree sweep at a serialized chapter boundary. */
    suspend fun reconcileArtifactRetention() = persistenceScheduler.reconcileArtifactRetention()

    /**
     * Fire-and-forget boundary sweep for batch close paths — the caller never
     * waits on the minutes-long crawl and the scheduler gates double-crawls.
     */
    fun reconcileArtifactRetentionAsync() = persistenceScheduler.reconcileArtifactRetentionAsync()

    /**
     * Store-to-scheduler boundary. Callers hold [mutex]; this handoff is
     * synchronous and does not acquire a scheduler-side lock.
     */
    internal fun schedulePersist(markPageDirty: Boolean = true) =
        persistenceScheduler.schedulePersist(markPageDirty)

    companion object {
        private val JOURNAL_JSON = Json {
            encodeDefaults = true
            explicitNulls = true
        }

        /** Artifact cleaned-image probe retained for artifact validation tests. */
        internal var artifactImageProbe: CleanedImageProbe = BitmapFactoryCleanedImageProbe

        // markDefunct uses this bound when joining an active persistence job.
        private val PERSIST_JOIN_TIMEOUT_MS get() = StorePersistenceScheduler.PERSIST_JOIN_TIMEOUT_MS

        /** Fallback name for the rename target if [UniFile.getName] is null. */
        private const val DEFAULT_FILE_NAME = "translation.json"

        /** Reads only the small manifest header; page snapshots stay unopened. */
        internal fun probeArtifactManifest(translationFile: UniFile): ArtifactManifestProbe =
            ChapterArtifactManifestReader.probeArtifactManifest(translationFile)

        /** Reads only the manifest header when the legacy flat document is absent. */
        internal fun probeArtifactManifest(parent: UniFile, fileName: String): ArtifactManifestProbe =
            ChapterArtifactManifestReader.probeArtifactManifest(parent, fileName)

        /** Opens an existing artifact manifest; flat files are deliberately ignored. */
        fun open(translationFile: UniFile): ChapterTranslationStore =
            open(translationFile, privateStorageRoot = null, privateStorageIdentity = null)

        /** Opens a durable artifact and enables shadow capture in app-private files when provided. */
        internal fun open(
            translationFile: UniFile,
            privateStorageRoot: File?,
            privateStorageIdentity: String?,
        ): ChapterTranslationStore = openArtifact(
            translationFile.parentFile ?: error("translation file has no parent"),
            translationFile.name ?: DEFAULT_FILE_NAME,
            privateStorageRoot,
            privateStorageIdentity,
        )

        /** Opens an artifact chapter, creating an empty manifest when needed. */
        internal fun openArtifact(
            parent: UniFile,
            fileName: String,
            privateStorageRoot: File? = null,
            privateStorageIdentity: String? = null,
        ): ChapterTranslationStore {
            return withArtifactOpenLock(parent, fileName) { openLock ->
                openArtifactOnly(parent, fileName, openLock.monitor, privateStorageRoot, privateStorageIdentity)
            }
        }

        private class ArtifactOpenLock {
            val monitor = Any()
            var references = 0
        }

        private val ARTIFACT_OPEN_LOCKS = ConcurrentHashMap<String, ArtifactOpenLock>()

        private fun artifactOpenKey(parent: UniFile?, fileName: String): String {
            val parentKey = parent?.filePath ?: parent?.uri?.toString() ?: "<unknown>"
            return "$parentKey:$fileName"
        }

        private fun <T> withArtifactOpenLock(
            parent: UniFile?,
            fileName: String,
            block: (ArtifactOpenLock) -> T,
        ): T {
            val key = artifactOpenKey(parent, fileName)
            val openLock = checkNotNull(
                ARTIFACT_OPEN_LOCKS.compute(key) { _, current ->
                    (current ?: ArtifactOpenLock()).also { it.references++ }
                },
            )
            return try {
                block(openLock)
            } finally {
                ARTIFACT_OPEN_LOCKS.compute(key) { _, current ->
                    check(current === openLock)
                    openLock.references--
                    openLock.takeIf { it.references > 0 }
                }
            }
        }
        private fun openArtifactOnly(
            parent: UniFile?,
            fileName: String,
            openMonitor: Any,
            privateStorageRoot: File?,
            privateStorageIdentity: String?,
        ): ChapterTranslationStore {
            if (parent == null) {
                return ChapterTranslationStore(
                    translationFile = null,
                    fileCreator = null,
                    artifactParent = null,
                    artifactFileName = fileName,
                    privateStorageRoot = privateStorageRoot,
                    privateStorageIdentity = privateStorageIdentity,
                )
            }
            val layout = ChapterArtifactLayout.fromTranslationFileName(fileName)
            val documents = AtomicChapterDocuments(UniFileChapterDocumentIo(parent))
            val artifact = ChapterArtifactEngine(documents, layout)
            val manifest = synchronized(openMonitor) { artifact.load().manifest }
            val committedPages = manifest.pages.mapNotNull { (pageKey, record) ->
                record.committed?.pageSnapshotFileName
                    ?.let(artifact::readPageSnapshot)
                    ?.let { pageKey to it }
            }.toMap()
            val livePages = manifest.pages.mapNotNull { (pageKey, record) ->
                val snapshot = record.candidate?.pageSnapshotFileName?.let(artifact::readPageSnapshot)
                    ?: record.committed?.pageSnapshotFileName?.let(artifact::readPageSnapshot)
                    ?: record.toArtifactPageFallback()
                pageKey to snapshot
            }.toMap()
            val retiredCleanedImages = manifest.pages.mapNotNull { (pageKey, record) ->
                val previous = record.previousCommitted ?: return@mapNotNull null
                val name = previous.displayBase.fileName
                    ?.takeIf { it != record.committed?.displayBase?.fileName }
                    ?: return@mapNotNull null
                pageKey to setOf(name)
            }.toMap()
            return ChapterTranslationStore(
                translationFile = null,
                fileCreator = null,
                initialPages = livePages,
                artifactStore = artifact,
                initialCommittedPages = committedPages,
                initialArtifactManifest = manifest,
                initialRetiredCleanedImages = retiredCleanedImages,
                artifactParent = parent,
                artifactFileName = fileName,
                privateStorageRoot = privateStorageRoot,
                privateStorageIdentity = privateStorageIdentity,
            )
        }

        /**
         * Creates a store whose on-disk file is created lazily on the first
         * artifact-backed [updatePage]/[replaceAll] write. When
         * [artifactParent] is null (chapter never translated), the optional
         * [fileCreator] resolves the parent directory on the first mutation.
         */
        fun lazy(
            artifactParent: UniFile? = null,
            artifactFileName: String? = null,
            privateStorageRoot: File? = null,
            privateStorageIdentity: String? = null,
            fileCreator: (() -> UniFile)? = null,
        ): ChapterTranslationStore =
            ChapterTranslationStore(
                translationFile = null,
                fileCreator = fileCreator,
                initialPages = emptyMap(),
                artifactParent = artifactParent,
                artifactFileName = artifactFileName,
                privateStorageRoot = privateStorageRoot,
                privateStorageIdentity = privateStorageIdentity,
            )
    }
}
