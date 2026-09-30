package eu.kanade.translation.persistence.journal

import android.system.Os
import android.system.OsConstants
import eu.kanade.translation.diagnostics.TranslationRunTrace
import eu.kanade.translation.diagnostics.TranslationStageSpan
import eu.kanade.translation.diagnostics.TranslationTrace
import eu.kanade.translation.diagnostics.TranslationTraceLane
import eu.kanade.translation.diagnostics.TranslationTraceOutcome
import eu.kanade.translation.diagnostics.TranslationTraceStage
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.detachedCopy
import eu.kanade.translation.model.hasRenderedResult
import eu.kanade.translation.model.isTextlessTerminal
import eu.kanade.translation.persistence.artifact.DurableFailureMetadata
import eu.kanade.translation.persistence.artifact.StageFingerprints
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.serialization.Serializable
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

private const val REQUIRED_CONTROL_COMMAND_SLOTS = 4

/** Immutable reference snapshot handed to the journal writer after a legacy commit succeeds. */
@Serializable
internal data class ChapterJournalRecord(
    val schemaVersion: Int = SCHEMA_VERSION,
    val pageKey: String,
    val generation: Long,
    val fencingToken: Long,
    val pageVersion: Long,
    val state: PageTranslation?,
    /**
     * Semantic SHA-256 identity of the page snapshot persisted by the legacy artifact; null only
     * when no snapshot exists. Replay verifies it by re-reading that artifact, re-deriving the
     * PageTranslation, and calling the same StageFingerprints.pageSnapshot function. It must not
     * hash serialized JSON bytes, whose harmless encoding changes are not semantic page changes.
     */
    val artifactContentHash: String? = null,
    val durableFailure: DurableFailureMetadata? = null,
    val terminalReason: String? = null,
    /** Logical legacy position associated with a metadata marker; this never consumes a commitSeq. */
    val terminalCommitSeq: Long? = null,
    /** Payload length rejected before a state frame was appended. */
    val terminalObservedPayloadBytes: Int? = null,
) {
    companion object {
        const val SCHEMA_VERSION = 2
        const val TERMINAL_LAG_REASON_CREDIT_WINDOW = "shadow_credit_window_exhausted"
        const val TERMINAL_PAYLOAD_REASON_SIZE_LIMIT = "shadow_payload_size_limit"
    }
}

@Serializable
internal data class ChapterJournalInventoryRecord(
    val schemaVersion: Int = SCHEMA_VERSION,
    val chapterIdentityHash: String,
    val expectedPageKeys: List<String>,
    val expectedPageCount: Int,
    val sourceFingerprint: String,
) {
    companion object {
        const val SCHEMA_VERSION = 1
    }
}

/** One atomic bulk legacy transition, represented by one journal frame and one commit sequence. */
@Serializable
internal data class ChapterJournalBulkRecord(
    val schemaVersion: Int = SCHEMA_VERSION,
    val operation: String,
    /** Applied mapping from successful legacy commits only; rejected updates are absent or remain tombstones. */
    val mapping: Map<String, String?>,
    /** Successful page states and tombstones that make up this legacy transaction. */
    val mutations: List<ChapterJournalRecord>,
) {
    companion object {
        const val SCHEMA_VERSION = 1
    }
}

/** Immutable map/set references handed off in O(1); normalization and hashing run on the writer dispatcher. */
internal data class ChapterJournalInventorySnapshot(
    val expectedPageKeys: Set<String>,
    val expectedPageCount: Int,
    val sourceShaByPageKey: Map<String, String>,
) {
    init {
        require(expectedPageCount >= expectedPageKeys.size) {
            "expected page count cannot be smaller than the declared page-key set"
        }
    }

    fun record(chapterIdentityHash: String): ChapterJournalInventoryRecord {
        val sortedKeys = expectedPageKeys.sorted()
        val fingerprintFields = buildList<Any?> {
            add("journal-source-inventory-v1")
            add(expectedPageCount)
            sortedKeys.forEach { key ->
                add(key)
                add(sourceShaByPageKey[key].orEmpty())
            }
        }
        return ChapterJournalInventoryRecord(
            chapterIdentityHash = chapterIdentityHash,
            expectedPageKeys = sortedKeys,
            expectedPageCount = expectedPageCount,
            sourceFingerprint = StageFingerprints.canonicalFingerprint(fingerprintFields),
        )
    }

    companion object {
        val EMPTY = ChapterJournalInventorySnapshot(emptySet(), 0, emptyMap())
    }
}

internal data class AppPrivateJournalEpoch(
    val directory: File,
    val ordinal: Long,
    val chapterIdentityHash: String,
)

/** Storage seam kept small so the writer's failure paths can be exercised without Android storage. */
internal interface ChapterJournalStorage {
    fun segmentIndexes(): List<Long>
    fun readSegment(index: Long): ByteArray
    fun truncateSegment(index: Long, byteCount: Long)
    fun discardSegmentsAfter(index: Long)
    fun openSegment(index: Long, create: Boolean): ChapterJournalSink
    fun syncDirectory()
}

internal interface ChapterJournalSink : Closeable {
    val size: Long
    fun write(bytes: ByteArray, offset: Int, length: Int): Int
    fun flush()
    fun sync()
}

/** App-private, one-file-per-segment storage. No SAF/UniFile object crosses this boundary. */
internal class FileChapterJournalStorage(
    private val directory: File,
    private val durableRoot: File? = null,
) : ChapterJournalStorage {
    override fun segmentIndexes(): List<Long> = directory.listFiles().orEmpty()
        .mapNotNull { file ->
            SEGMENT_NAME.matchEntire(file.name)?.groupValues?.get(1)?.toLongOrNull()
        }
        .sorted()

    override fun readSegment(index: Long): ByteArray = segmentFile(index).readBytes()

    override fun truncateSegment(index: Long, byteCount: Long) {
        RandomAccessFile(segmentFile(index), "rw").use { file ->
            file.setLength(byteCount)
            file.fd.sync()
        }
    }

    override fun discardSegmentsAfter(index: Long) {
        segmentIndexes().filter { it > index }.forEach { segmentFile(it).delete() }
        syncDirectory()
    }

    override fun openSegment(index: Long, create: Boolean): ChapterJournalSink {
        if (!directory.exists() && !directory.mkdirs()) throw IOException("unable to create journal directory")
        val file = segmentFile(index)
        if (!file.exists() && !create) throw IOException("journal segment is missing")
        val channel = FileChannel.open(
            file.toPath(),
            StandardOpenOption.CREATE,
            StandardOpenOption.READ,
            StandardOpenOption.WRITE,
        )
        channel.position(channel.size())
        return FileSink(channel)
    }

    override fun syncDirectory() {
        // Sync every newly-created directory entry up through app-private storage.
        val syncRoot = durableRoot ?: directory.parentFile
        var path: File? = directory
        while (path != null) {
            val fd = Os.open(path.absolutePath, OsConstants.O_RDONLY, 0)
            try {
                Os.fsync(fd)
            } finally {
                Os.close(fd)
            }
            if (path == syncRoot) break
            path = path.parentFile
        }
    }

    private fun segmentFile(index: Long): File = File(directory, "segment-${index.toString().padStart(8, '0')}.tjr")

    private class FileSink(private val channel: FileChannel) : ChapterJournalSink {
        override val size: Long get() = channel.size()

        override fun write(bytes: ByteArray, offset: Int, length: Int): Int =
            channel.write(ByteBuffer.wrap(bytes, offset, length))

        // FileChannel writes directly; there is no userspace buffer to drain.
        override fun flush() = Unit

        override fun sync() = channel.force(true)

        override fun close() = channel.close()
    }

    private companion object {
        val SEGMENT_NAME = Regex("segment-([0-9]{8})\\.tjr")
    }
}

/** One outstanding bounded writer credit. State transitions are single-use and exact-once. */
internal class ChapterJournalCredit internal constructor(
    private val releasePermit: () -> Unit,
) {
    private val state = AtomicInteger(HELD)

    /** Keep the permit with a staged/lazy mutation until its legacy commit runs. */
    internal fun retain(): Boolean {
        while (true) {
            when (val current = state.get()) {
                HELD -> if (state.compareAndSet(HELD, RETAINED)) return true
                RETAINED -> return true
                else -> return false
            }
        }
    }

    internal fun handOff(): Boolean =
        state.compareAndSet(HELD, QUEUED) || state.compareAndSet(RETAINED, QUEUED)

    internal fun releaseIfHeld(): Boolean {
        if (!state.compareAndSet(HELD, RELEASED)) return false
        releasePermit()
        return true
    }

    internal fun releaseIfRetained(): Boolean {
        if (!state.compareAndSet(RETAINED, RELEASED)) return false
        releasePermit()
        return true
    }

    internal fun releaseIfUnqueued(): Boolean = releaseIfHeld() || releaseIfRetained()

    /** Writer failure terminally resolves held, retained, and queued credits alike. */
    internal fun releaseForTerminal(): Boolean {
        while (true) {
            val current = state.get()
            if (current == RELEASED) return false
            if (state.compareAndSet(current, RELEASED)) {
                releasePermit()
                return true
            }
        }
    }

    internal fun releaseAfterTerminal(): Boolean {
        if (!state.compareAndSet(QUEUED, RELEASED)) return false
        releasePermit()
        return true
    }

    private companion object {
        const val HELD = 0
        const val RETAINED = 1
        const val QUEUED = 2
        const val RELEASED = 3
    }
}

/**
 * Single-writer CRC-framed journal. E16a only captures shadow records; the legacy artifact path stays authoritative.
 * In shadow mode credit acquisition is nonblocking: exhaustion writes one terminal marker and disables later
 * capture for this writer so a missing sequence can never be bridged. E16c owns producer backpressure.
 */
internal class ChapterJournalWriter(
    private val storage: ChapterJournalStorage,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val encodeRecord: (ChapterJournalRecord) -> ByteArray,
    private val encodeBulkRecord: (ChapterJournalBulkRecord) -> ByteArray = { it.toString().encodeToByteArray() },
    private val encodeInventory: (ChapterJournalInventoryRecord) -> ByteArray = { record ->
        (
            "inventory:${record.chapterIdentityHash}:${record.expectedPageCount}:${record.sourceFingerprint}:" +
                record.expectedPageKeys.joinToString(",")
            ).encodeToByteArray()
    },
    private val encodeTerminalLag: (Long, Long) -> ByteArray,
    private val encodeTerminalDefunct: (Long) -> ByteArray = { "defunct:$it".encodeToByteArray() },
    private val encodeTerminalPayload: (String, Int, Long) -> ByteArray = { pageKey, observedBytes, commitSeq ->
        "terminal_payload:$pageKey:$observedBytes:$commitSeq".encodeToByteArray()
    },
    private val storeGeneration: Long = 0L,
    private val epochOrdinal: Long = 0L,
    private val sessionId: UUID = DEFAULT_SESSION_ID,
    private val chapterIdentityHash: String = DEFAULT_CHAPTER_IDENTITY_HASH,
    private val regularCreditLimit: Int = DEFAULT_REGULAR_CREDITS,
    private val foregroundCreditLimit: Int = DEFAULT_FOREGROUND_CREDITS,
    private val channelCapacity: Int = regularCreditLimit + foregroundCreditLimit + REQUIRED_CONTROL_COMMAND_SLOTS,
    private val segmentByteLimit: Long = ChapterJournalFormat.SEGMENT_BYTE_LIMIT,
    private val durabilityIntervalMs: Long = DEFAULT_FREE_DURABILITY_INTERVAL_MS,
) {
    init {
        require(regularCreditLimit >= 1) { "regularCreditLimit must be at least 1" }
        require(foregroundCreditLimit >= 1) { "foregroundCreditLimit must be at least 1" }
        val worstCaseChannelCapacity = regularCreditLimit + foregroundCreditLimit + REQUIRED_CONTROL_COMMAND_SLOTS
        require(channelCapacity >= worstCaseChannelCapacity) {
            "channelCapacity=$channelCapacity must reserve all credits and $REQUIRED_CONTROL_COMMAND_SLOTS control commands"
        }
        require(epochOrdinal >= 0L) { "epochOrdinal must be non-negative" }
    }

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val ready = CompletableDeferred<Boolean>()
    private val regularCredits = Semaphore(regularCreditLimit)
    private val foregroundCredit = Semaphore(foregroundCreditLimit)

    // Linearizes a successful non-blocking reservation against eviction/close.
    // Semaphore tryAcquire remains outside this short critical section; if the
    // lifecycle transition wins, the permit is returned before any credit escapes.
    private val creditAdmissionLock = Any()

    // Appends are bounded by the 8+1 credit window. Inventory updates coalesce
    // to one queued wakeup; capacity also reserves room for DEFUNCT, TERMINAL_LAG,
    // and CLOSE, so producer paths never wait while holding the store mutex.
    private val commands = Channel<Command>(channelCapacity)
    private val timerWake = Channel<Unit>(Channel.CONFLATED)
    private val assignedCommitSequence = AtomicLong(0L)
    private val highWater = AtomicLong(0L)
    private val outstanding = AtomicInteger(0)
    private val outstandingCredits = LinkedHashSet<ChapterJournalCredit>()
    private val laggedRecords = AtomicLong(0L)
    private val droppedControlRecords = AtomicLong(0L)
    private val lostTerminalRecords = AtomicLong(0L)
    private val writerFailures = AtomicLong(0L)
    private val terminalPayloadRecords = AtomicLong(0L)
    private val offersRejectedAfterTerminal = AtomicLong(0L)

    // Eviction closes admission for new credits at the store boundary, but any
    // already-held credit may still be handed off after the DEFUNCT marker.
    // Terminal lag and writer failure close both switches.
    private val creditAdmissionOpen = AtomicBoolean(true)
    private val captureAcceptanceOpen = AtomicBoolean(true)
    private val initialized = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val defunctRequested = AtomicBoolean(false)
    private val defunctMarkerQueued = AtomicBoolean(false)
    private val asyncCloseQueued = AtomicBoolean(false)
    private val terminalPayloadSeen = AtomicBoolean(false)
    private val hasJournalEvent = AtomicBoolean(false)
    private val writerDone = CompletableDeferred<Unit>()
    private var pendingInventory: ChapterJournalInventorySnapshot? = null
    private var inventoryCommandQueued = false

    val ackedHighWaterSeq: Long get() = highWater.get()
    val shadowLaggedCount: Long get() = laggedRecords.get()
    val droppedControlRecordCount: Long get() = droppedControlRecords.get()

    /** Terminal endings that could not be queued or written, separate from credit-starvation counts. */
    val lostTerminalRecordCount: Long get() = lostTerminalRecords.get()
    val writerFailureCount: Long get() = writerFailures.get()
    val terminalPayloadCount: Long get() = terminalPayloadRecords.get()
    val offersRejectedAfterTerminalCount: Long get() = offersRejectedAfterTerminal.get()
    val inFlightCount: Int get() = outstanding.get()
    val isWriterStopped: Boolean get() = writerDone.isCompleted
    val isShadowCaptureActive: Boolean get() = captureAcceptanceOpen.get()

    /** Credit admission is non-blocking and does not create an empty epoch on disk. */
    suspend fun tryAcquireShadowCredit(foreground: Boolean): ChapterJournalCredit? {
        val acquiredForeground = foreground && foregroundCredit.tryAcquire()
        val acquiredRegular = !acquiredForeground && regularCredits.tryAcquire()
        if (!acquiredForeground && !acquiredRegular) return null

        return synchronized(creditAdmissionLock) {
            if (!creditAdmissionOpen.get() || closed.get()) {
                if (acquiredForeground) foregroundCredit.release() else regularCredits.release()
                null
            } else {
                newCredit(foreground = acquiredForeground)
            }
        }
    }

    /** Future cutover API. Call only outside ChapterTranslationStore.mutex. */
    suspend fun acquireCredit(foreground: Boolean): ChapterJournalCredit {
        check(!closed.get()) { "journal writer is closed" }
        if (foreground) {
            if (foregroundCredit.tryAcquire()) return newCredit(foreground = true)
            regularCredits.acquire()
            return newCredit(foreground = false)
        }
        regularCredits.acquire()
        return newCredit(foreground = false)
    }

    /** Caller holds the store mutex. This sequence belongs only to legacy durable events. */
    fun nextCommitSeq(): Long = assignedCommitSequence.incrementAndGet()

    /** Caller holds the store mutex. Hands off an already-sequenced legacy-persisted page state. */
    fun captureLegacyPersisted(
        commitSeq: Long,
        credit: ChapterJournalCredit?,
        pageKey: String,
        generation: Long,
        fencingToken: Long,
        page: PageTranslation?,
        durableFailure: DurableFailureMetadata? = null,
        paid: Boolean = false,
        artifactContentHash: String? = null,
        inventory: ChapterJournalInventorySnapshot = ChapterJournalInventorySnapshot.EMPTY,
    ): Long {
        val record = ChapterJournalRecord(
            pageKey = pageKey,
            generation = generation,
            fencingToken = fencingToken,
            pageVersion = page?.pageVersion ?: 0L,
            // The writer serializes on its own dispatcher later. Detach while the caller
            // still holds ChapterTranslationStore.mutex so later in-place edits cannot
            // rewrite the historical state associated with this commit sequence.
            state = page?.detachedCopy(),
            artifactContentHash = artifactContentHash,
            durableFailure = durableFailure,
        )
        offer(
            commitSeq,
            if (paid) ChapterJournalFormat.RecordKind.PAID_STATE else ChapterJournalFormat.RecordKind.FREE_STATE,
            encode = { encodeRecord(record) },
            inventory = inventory,
            credit = credit,
            pageKey = pageKey,
            traceRuns = TranslationTrace.currentRuns(),
            syncImmediately = paid,
        )
        return commitSeq
    }

    /** Caller holds the store mutex. Captures an atomic replace/rekey as a single bounded event. */
    fun captureLegacyBulkMutation(
        commitSeq: Long,
        credit: ChapterJournalCredit?,
        kind: ChapterJournalFormat.RecordKind,
        record: ChapterJournalBulkRecord,
        inventory: ChapterJournalInventorySnapshot = ChapterJournalInventorySnapshot.EMPTY,
    ): Long {
        require(kind == ChapterJournalFormat.RecordKind.BULK_REPLACE || kind == ChapterJournalFormat.RecordKind.BULK_REKEY) {
            "bulk mutation requires a bulk record kind"
        }
        val immutableRecord = record.copy(
            mapping = record.mapping.toMap(),
            mutations = record.mutations.map { mutation ->
                mutation.copy(state = mutation.state?.detachedCopy())
            },
        )
        offer(
            commitSeq,
            kind,
            encode = { encodeBulkRecord(immutableRecord) },
            inventory = inventory,
            credit = credit,
            pageKey = "<${record.operation}>",
            traceRuns = TranslationTrace.currentRuns(),
            syncImmediately = immutableRecord.mutations.any { mutation ->
                mutation.durableFailure != null || mutation.state?.hasRenderedResult == true ||
                    mutation.state?.isTextlessTerminal == true
            },
        )
        return commitSeq
    }

    /** Caller holds the store mutex. Deletions are absolute tombstones in the future replay schema. */
    fun captureLegacyDeletion(
        commitSeq: Long,
        credit: ChapterJournalCredit?,
        pageKey: String,
        generation: Long,
        fencingToken: Long,
        pageVersion: Long,
        artifactContentHash: String? = null,
        inventory: ChapterJournalInventorySnapshot = ChapterJournalInventorySnapshot.EMPTY,
    ): Long {
        val record = ChapterJournalRecord(
            pageKey = pageKey,
            generation = generation,
            fencingToken = fencingToken,
            pageVersion = pageVersion,
            state = null,
            artifactContentHash = artifactContentHash,
        )
        offer(
            commitSeq,
            ChapterJournalFormat.RecordKind.FREE_STATE,
            encode = { encodeRecord(record) },
            inventory = inventory,
            credit = credit,
            pageKey = pageKey,
            traceRuns = TranslationTrace.currentRuns(),
        )
        return commitSeq
    }

    /** Caller holds the store mutex; inventory is metadata and consumes no credit or commitSeq. */
    fun captureInventory(inventory: ChapterJournalInventorySnapshot) {
        synchronized(creditAdmissionLock) {
            if (closed.get() || !captureAcceptanceOpen.get()) {
                droppedControlRecords.incrementAndGet()
                return
            }
            if (!enqueueInventoryLocked(inventory)) {
                droppedControlRecords.incrementAndGet()
                return
            }
            hasJournalEvent.set(true)
            startWriter()
        }
    }

    /** Caller holds the store mutex. Subsequent calls only advance commitSeq/lag telemetry. */
    fun noteLegacyPersistedWithoutCredit(
        commitSeq: Long,
        credit: ChapterJournalCredit? = null,
        inventory: ChapterJournalInventorySnapshot = ChapterJournalInventorySnapshot.EMPTY,
        traceRuns: List<TranslationRunTrace> = TranslationTrace.currentRuns(),
    ): Long {
        credit?.releaseIfUnqueued()
        markShadowLag(commitSeq, inventory, traceRuns)
        return commitSeq
    }

    /**
     * Requests a non-blocking defunct marker. Eviction closes admission for new
     * credits, while any already-held credit remains eligible for capture
     * after the marker. An otherwise-empty epoch defers marker/file creation
     * until that accepted event arrives; with no later event it stays absent.
     */
    fun requestDefunctMarker() {
        synchronized(creditAdmissionLock) {
            if (!defunctRequested.compareAndSet(false, true)) return
            creditAdmissionOpen.set(false)
            if (closed.get() || !captureAcceptanceOpen.get()) {
                droppedControlRecords.incrementAndGet()
                lostTerminalRecords.incrementAndGet()
                return
            }
            // Empty sessions have no journal. A held credit means the marker may
            // be needed later, but file creation is deferred until that event arrives.
            if (!hasJournalEvent.get()) return
            enqueueDefunctMarker()
            startWriter()
            if (outstanding.get() == 0) queueAsyncDefunctCloseLocked()
        }
    }

    private fun enqueueDefunctMarker(): Boolean {
        if (defunctMarkerQueued.get()) return true
        if (!defunctMarkerQueued.compareAndSet(false, true)) return defunctMarkerQueued.get()
        if (closed.get()) {
            defunctMarkerQueued.set(false)
            droppedControlRecords.incrementAndGet()
            lostTerminalRecords.incrementAndGet()
            return false
        }
        if (commands.trySend(Command.Defunct(encodeTerminalDefunct)).isFailure) {
            defunctMarkerQueued.set(false)
            droppedControlRecords.incrementAndGet()
            lostTerminalRecords.incrementAndGet()
            return false
        }
        return true
    }

    /** Waits for queued records and closes the active segment; lifecycle callers only. */
    suspend fun drainAndClose() {
        // If eviction requested a marker before the writer had completed its
        // lazy initialization, make that epoch ready before setting closed so
        // the close command cannot race ahead of the queued DEFUNCT record.
        val shouldClose = synchronized(creditAdmissionLock) {
            if (closed.get()) {
                false
            } else {
                closed.set(true)
                creditAdmissionOpen.set(false)
                true
            }
        }
        if (!shouldClose) {
            writerDone.await()
            return
        }
        if (!started.get()) {
            writerDone.complete(Unit)
            return
        }
        if (!awaitReady()) {
            writerDone.await()
            return
        }
        val result = CompletableDeferred<Unit>()
        val sent = runCatching { commands.send(Command.Close(result)) }.isSuccess
        if (sent) result.await()
        writerDone.await()
    }

    private suspend fun awaitReady(): Boolean {
        if (!started.get()) {
            if (closed.get() || !hasJournalEvent.get()) return false
            startWriter()
        }
        return runCatching { ready.await() }.getOrDefault(false)
    }

    private fun newCredit(foreground: Boolean): ChapterJournalCredit {
        return synchronized(creditAdmissionLock) {
            if (!creditAdmissionOpen.get() || closed.get()) {
                if (foreground) foregroundCredit.release() else regularCredits.release()
                throw IllegalStateException("journal writer is closed")
            }
            lateinit var credit: ChapterJournalCredit
            outstanding.incrementAndGet()
            credit = ChapterJournalCredit {
                synchronized(creditAdmissionLock) {
                    if (outstandingCredits.remove(credit)) {
                        val remaining = outstanding.decrementAndGet()
                        if (foreground) foregroundCredit.release() else regularCredits.release()
                        if (remaining == 0 && defunctRequested.get() && defunctMarkerQueued.get()) {
                            queueAsyncDefunctCloseLocked()
                        }
                    }
                }
            }
            outstandingCredits += credit
            credit
        }
    }

    private fun offer(
        commitSeq: Long,
        kind: ChapterJournalFormat.RecordKind,
        encode: () -> ByteArray,
        inventory: ChapterJournalInventorySnapshot,
        credit: ChapterJournalCredit?,
        pageKey: String,
        traceRuns: List<TranslationRunTrace>,
        syncImmediately: Boolean = false,
    ) {
        synchronized(creditAdmissionLock) {
            if (closed.get() || !captureAcceptanceOpen.get()) {
                offersRejectedAfterTerminal.incrementAndGet()
                credit?.releaseIfUnqueued()
                return
            }
            if (credit == null) {
                markShadowLag(commitSeq, inventory, traceRuns)
                return
            }
            if (!credit.handOff()) {
                credit.releaseIfUnqueued()
                markShadowLag(commitSeq, inventory, traceRuns)
                return
            }
            // If eviction happened while this credit was still held, preserve the
            // boundary in the epoch before accepting the late legacy commit. This
            // also avoids creating a marker-only file when the credit is abandoned.
            if (defunctRequested.get() && !defunctMarkerQueued.get()) {
                if (!enqueueInventoryLocked(inventory) || !enqueueDefunctMarker()) {
                    credit.releaseAfterTerminal()
                    markShadowLag(commitSeq, inventory, traceRuns)
                    return
                }
            }
            if (commands.trySend(
                    Command.Append(commitSeq, kind, encode, inventory, credit, pageKey, traceRuns, syncImmediately),
                ).isFailure
            ) {
                credit.releaseAfterTerminal()
                markShadowLag(commitSeq, inventory, traceRuns)
                return
            }
            hasJournalEvent.set(true)
            startWriter()
        }
    }

    private fun markShadowLag(
        commitSeq: Long,
        inventory: ChapterJournalInventorySnapshot,
        traceRuns: List<TranslationRunTrace> = TranslationTrace.currentRuns(),
    ) {
        val count = laggedRecords.incrementAndGet()
        var spans: List<TranslationStageSpan> = emptyList()
        synchronized(creditAdmissionLock) {
            if (!captureAcceptanceOpen.compareAndSet(true, false)) {
                return
            }
            spans = traceRuns.map { run ->
                run.beginStage(
                    stage = TranslationTraceStage.JOURNAL_TERMINAL_LAG,
                    lane = TranslationTraceLane.STORAGE,
                    items = 1,
                )
            }
            creditAdmissionOpen.set(false)
            // If eviction arrived before this terminal transition, preserve its
            // marker before terminal lag. The bounded command queue reserves space for both.
            if (defunctRequested.get() && !defunctMarkerQueued.get()) {
                enqueueInventoryLocked(inventory)
                enqueueDefunctMarker()
            }
            val terminalLag = Command.TerminalLag(
                starvedAtCommitSeq = commitSeq,
                inventory = inventory,
                encode = { encodeTerminalLag(count, commitSeq) },
            )
            if (commands.trySend(terminalLag).isFailure) {
                droppedControlRecords.incrementAndGet()
                lostTerminalRecords.incrementAndGet()
            } else {
                hasJournalEvent.set(true)
                startWriter()
            }
            queueTerminalCloseLocked()
        }
        spans.forEach { it.end(TranslationTraceOutcome.PERSISTENCE_REJECTED) }
    }

    /** Caller holds creditAdmissionLock; replay only needs the latest inventory before a durable event. */
    private fun enqueueInventoryLocked(inventory: ChapterJournalInventorySnapshot): Boolean {
        if (inventoryCommandQueued) {
            pendingInventory = inventory
            return true
        }
        pendingInventory = inventory
        if (commands.trySend(Command.InventoryWake).isFailure) {
            pendingInventory = null
            return false
        }
        inventoryCommandQueued = true
        return true
    }

    private fun takePendingInventory(): ChapterJournalInventorySnapshot? = synchronized(creditAdmissionLock) {
        inventoryCommandQueued = false
        pendingInventory.also { pendingInventory = null }
    }

    /** The defunct epoch closes only after every pre-eviction credit resolved. */
    private fun queueAsyncDefunctCloseLocked() {
        if (closed.get() || !captureAcceptanceOpen.get() || !hasJournalEvent.get()) return
        if (!asyncCloseQueued.compareAndSet(false, true)) return
        closed.set(true)
        creditAdmissionOpen.set(false)
        captureAcceptanceOpen.set(false)
        commands.trySend(Command.Close(CompletableDeferred()))
        startWriter()
    }

    /** Terminal capture states drain/release queued commands, then close without a caller join. */
    private fun queueTerminalCloseLocked() {
        if (!asyncCloseQueued.compareAndSet(false, true)) return
        closed.set(true)
        creditAdmissionOpen.set(false)
        commands.trySend(Command.Close(CompletableDeferred()))
    }

    private fun startWriter() {
        if (started.compareAndSet(false, true)) scope.launch { runWriter() }
    }

    private suspend fun runWriter() {
        var sink: ChapterJournalSink? = null
        var segmentIndex = 0L
        var segmentBytes = 0L
        var nextFrameSeq = 1L
        var nextCommitSeq = 1L
        var lastCommitSeq = 0L
        var terminalLagSeen = false
        var terminalCaptureHalted = false
        var unsyncedHighWaterFrameSeq = 0L
        var lastInventoryFingerprint: String? = null
        var freeSyncTimer: Job? = null
        var hadUnsyncedFreeWork = false
        var terminalError: Throwable? = null
        var terminalEndingInProgress = false
        var activeCredit: ChapterJournalCredit? = null
        var activeClose: CompletableDeferred<Unit>? = null

        suspend fun syncFreePrefix() {
            if (!hadUnsyncedFreeWork) return
            sink?.flush()
            sink?.sync()
            highWater.set(unsyncedHighWaterFrameSeq)
            hadUnsyncedFreeWork = false
        }

        suspend fun prepareFrame(frameSize: Int) {
            if (segmentBytes + frameSize <= segmentByteLimit || segmentBytes <= ChapterJournalFormat.SEGMENT_HEADER_BYTES) {
                return
            }
            syncFreePrefix()
            freeSyncTimer?.cancel()
            freeSyncTimer = null
            sink?.close()
            segmentIndex++
            sink = createSegment(segmentIndex)
            segmentBytes = ChapterJournalFormat.SEGMENT_HEADER_BYTES.toLong()
        }

        suspend fun appendFrame(
            kind: ChapterJournalFormat.RecordKind,
            commitSeq: Long?,
            payload: ByteArray,
        ): Long {
            val encoded = ChapterJournalFormat.encodeFrame(nextFrameSeq, commitSeq, kind, payload)
            prepareFrame(encoded.size)
            val frameSeq = nextFrameSeq
            val target = sink ?: throw IOException("journal segment is not open")
            writeFully(target, encoded)
            segmentBytes += encoded.size
            nextFrameSeq++
            return frameSeq
        }

        fun scheduleFreeSync(frameSeq: Long) {
            unsyncedHighWaterFrameSeq = frameSeq
            hadUnsyncedFreeWork = true
            if (freeSyncTimer == null) {
                freeSyncTimer = scope.launch {
                    kotlinx.coroutines.delay(durabilityIntervalMs)
                    timerWake.trySend(Unit)
                }
            }
        }

        fun clearFreeSync() {
            hadUnsyncedFreeWork = false
            freeSyncTimer?.cancel()
            freeSyncTimer = null
        }

        suspend fun appendTerminalPayload(
            pageKey: String,
            observedBytes: Int,
            failedCommitSeq: Long,
            traceRuns: List<TranslationRunTrace> = emptyList(),
        ) {
            terminalEndingInProgress = true
            val payload = encodeTerminalPayload(pageKey, observedBytes, failedCommitSeq)
            if (payload.size > ChapterJournalFormat.MAX_PAYLOAD_BYTES) {
                throw IOException("terminal payload marker exceeded the journal payload limit")
            }
            val frameSeq = appendFrame(
                ChapterJournalFormat.RecordKind.TERMINAL_PAYLOAD,
                commitSeq = null,
                payload = payload,
            )
            sink?.flush()
            sink?.sync()
            terminalEndingInProgress = false
            highWater.set(frameSeq)
            clearFreeSync()
            terminalPayloadRecords.incrementAndGet()
            terminalCaptureHalted = true
            terminalPayloadSeen.set(true)
            synchronized(creditAdmissionLock) {
                captureAcceptanceOpen.set(false)
                creditAdmissionOpen.set(false)
                queueTerminalCloseLocked()
            }
            logcat(LogPriority.WARN) {
                "TachiyomiAT shadow journal capture ended: " +
                    "reason=${ChapterJournalRecord.TERMINAL_PAYLOAD_REASON_SIZE_LIMIT} " +
                    "pageHash=${eu.kanade.translation.util.ShortHash.hash(pageKey)} " +
                    "observedPayloadBytes=$observedBytes count=${terminalPayloadRecords.get()}"
            }
            traceRuns.forEach { run ->
                run.beginStage(
                    stage = TranslationTraceStage.JOURNAL_TERMINAL_PAYLOAD,
                    lane = TranslationTraceLane.STORAGE,
                    items = 1,
                ).end(TranslationTraceOutcome.PERSISTENCE_REJECTED)
            }
        }

        suspend fun appendInventory(
            inventory: ChapterJournalInventorySnapshot,
            failedCommitSeq: Long,
            traceRuns: List<TranslationRunTrace> = emptyList(),
        ): Boolean {
            val record = inventory.record(chapterIdentityHash)
            if (record.sourceFingerprint == lastInventoryFingerprint) return true
            val payload = encodeInventory(record)
            if (payload.size > ChapterJournalFormat.MAX_PAYLOAD_BYTES) {
                appendTerminalPayload(
                    pageKey = "<inventory>",
                    observedBytes = payload.size,
                    failedCommitSeq = failedCommitSeq,
                    traceRuns = traceRuns,
                )
                return false
            }
            val frameSeq = appendFrame(
                ChapterJournalFormat.RecordKind.INVENTORY,
                commitSeq = null,
                payload = payload,
            )
            lastInventoryFingerprint = record.sourceFingerprint
            scheduleFreeSync(frameSeq)
            return true
        }

        try {
            val initialized = initializeSegments()
            segmentIndex = initialized.segmentIndex
            segmentBytes = initialized.segmentBytes
            nextFrameSeq = initialized.nextFrameSeq
            nextCommitSeq = initialized.nextCommitSeq
            lastCommitSeq = nextCommitSeq - 1
            terminalLagSeen = initialized.terminalLagSeen
            terminalCaptureHalted = initialized.terminalLagSeen || initialized.terminalPayloadSeen
            terminalPayloadSeen.set(initialized.terminalPayloadSeen)
            assignedCommitSequence.updateAndGet { current -> maxOf(current, lastCommitSeq) }
            highWater.set(nextFrameSeq - 1)
            sink = initialized.sink
            if (terminalCaptureHalted) {
                synchronized(creditAdmissionLock) {
                    captureAcceptanceOpen.set(false)
                    creditAdmissionOpen.set(false)
                    queueTerminalCloseLocked()
                }
            }
            this.initialized.set(true)
            ready.complete(true)

            var done = false
            while (!done) {
                val command = selectNextCommand(freeSyncTimer)
                when (command) {
                    Command.FreeSyncDue -> {
                        syncFreePrefix()
                        freeSyncTimer = null
                    }

                    is Command.Append -> {
                        activeCredit = command.credit
                        if (terminalCaptureHalted) {
                            command.credit.releaseAfterTerminal()
                            activeCredit = null
                            continue
                        }
                        val nextExpected = if (terminalLagSeen) {
                            command.commitSeq > lastCommitSeq
                        } else {
                            command.commitSeq == nextCommitSeq
                        }
                        if (!nextExpected) {
                            throw IOException(
                                "legacy commit sequence gap: expected=$nextCommitSeq actual=${command.commitSeq}",
                            )
                        }
                        if (!appendInventory(command.inventory, command.commitSeq, command.traceRuns)) {
                            command.credit.releaseAfterTerminal()
                            activeCredit = null
                            continue
                        }
                        val statePayload = command.encode()
                        if (statePayload.size > ChapterJournalFormat.MAX_PAYLOAD_BYTES) {
                            appendTerminalPayload(
                                pageKey = command.pageKey,
                                observedBytes = statePayload.size,
                                failedCommitSeq = command.commitSeq,
                                traceRuns = command.traceRuns,
                            )
                            command.credit.releaseAfterTerminal()
                            activeCredit = null
                            continue
                        }
                        val stateFrameSeq = appendFrame(
                            command.kind,
                            commitSeq = command.commitSeq,
                            payload = statePayload,
                        )
                        lastCommitSeq = command.commitSeq
                        nextCommitSeq = command.commitSeq + 1
                        if (command.kind == ChapterJournalFormat.RecordKind.PAID_STATE || command.syncImmediately) {
                            sink?.flush()
                            sink?.sync()
                            highWater.set(stateFrameSeq)
                            clearFreeSync()
                        } else {
                            scheduleFreeSync(stateFrameSeq)
                        }
                        command.credit.releaseAfterTerminal()
                        activeCredit = null
                    }

                    Command.InventoryWake -> {
                        val inventory = takePendingInventory()
                        if (!terminalCaptureHalted) {
                            inventory?.let { appendInventory(it, lastCommitSeq + 1L) }
                        } else {
                            droppedControlRecords.incrementAndGet()
                        }
                    }

                    is Command.TerminalLag -> {
                        if (terminalCaptureHalted) {
                            droppedControlRecords.incrementAndGet()
                            lostTerminalRecords.incrementAndGet()
                            continue
                        }
                        terminalEndingInProgress = true
                        if (!terminalLagSeen && command.starvedAtCommitSeq != nextCommitSeq) {
                            throw IOException(
                                "terminal lag sequence mismatch: expected=$nextCommitSeq actual=${command.starvedAtCommitSeq}",
                            )
                        }
                        if (!appendInventory(command.inventory, command.starvedAtCommitSeq)) {
                            // The inventory path wrote its own terminal-payload ending.
                            terminalEndingInProgress = false
                            continue
                        }
                        val frameSeq = appendFrame(
                            ChapterJournalFormat.RecordKind.TERMINAL_LAG,
                            commitSeq = null,
                            payload = command.encode(),
                        )
                        sink?.flush()
                        sink?.sync()
                        terminalEndingInProgress = false
                        highWater.set(frameSeq)
                        terminalLagSeen = true
                        clearFreeSync()
                    }

                    is Command.Defunct -> {
                        if (terminalCaptureHalted) {
                            droppedControlRecords.incrementAndGet()
                            lostTerminalRecords.incrementAndGet()
                            continue
                        }
                        terminalEndingInProgress = true
                        val frameSeq = appendFrame(
                            ChapterJournalFormat.RecordKind.DEFUNCT,
                            commitSeq = null,
                            payload = command.encode(lastCommitSeq),
                        )
                        sink?.flush()
                        sink?.sync()
                        terminalEndingInProgress = false
                        highWater.set(frameSeq)
                        clearFreeSync()
                    }

                    is Command.Close -> {
                        activeClose = command.done
                        syncFreePrefix()
                        freeSyncTimer?.cancel()
                        sink?.close()
                        sink = null
                        command.done.complete(Unit)
                        activeClose = null
                        done = true
                    }
                }
            }
        } catch (failure: Throwable) {
            terminalError = failure
            writerFailures.incrementAndGet()
            if (terminalEndingInProgress) {
                droppedControlRecords.incrementAndGet()
                lostTerminalRecords.incrementAndGet()
                terminalEndingInProgress = false
            }
            val creditsToRelease = synchronized(creditAdmissionLock) {
                creditAdmissionOpen.set(false)
                captureAcceptanceOpen.set(false)
                closed.set(true)
                outstandingCredits.toList()
            }
            creditsToRelease.forEach(ChapterJournalCredit::releaseForTerminal)
            activeCredit?.releaseAfterTerminal()
            activeCredit = null
            activeClose?.completeExceptionally(failure)
            activeClose = null
            ready.complete(false)
            // Every accepted command still owns exactly one credit until this terminal disposition.
            while (true) {
                val pending = commands.tryReceive().getOrNull() ?: break
                if (pending is Command.Append) pending.credit.releaseAfterTerminal()
                if (pending is Command.Defunct || pending is Command.TerminalLag) {
                    droppedControlRecords.incrementAndGet()
                    lostTerminalRecords.incrementAndGet()
                }
                if (pending is Command.Close) pending.done.completeExceptionally(failure)
            }
        } finally {
            freeSyncTimer?.cancel()
            runCatching { sink?.close() }
            if (!ready.isCompleted) ready.complete(false)
            commands.close()
            writerDone.complete(Unit)
            if (terminalError != null) {
                logcat(LogPriority.ERROR, terminalError) {
                    "TachiyomiAT shadow journal writer halted: failures=${writerFailures.get()} " +
                        "ackedHighWaterSeq=${highWater.get()}"
                }
            }
        }
    }

    private suspend fun selectNextCommand(timer: Job?): Command = kotlinx.coroutines.selects.select {
        commands.onReceive { it }
        if (timer != null) {
            timerWake.onReceive { Command.FreeSyncDue }
        }
    }

    private fun initializeSegments(): Initialized {
        var expectedFrameSeq = 1L
        var expectedCommitSeq = 1L
        var lastCommitSeq = 0L
        var terminalLagSeen = false
        var terminalPayloadSeen = false
        val indexes = storage.segmentIndexes()
        if (indexes.isEmpty()) {
            val sink = createSegment(0L)
            return Initialized(
                segmentIndex = 0L,
                segmentBytes = ChapterJournalFormat.SEGMENT_HEADER_BYTES.toLong(),
                nextFrameSeq = expectedFrameSeq,
                nextCommitSeq = expectedCommitSeq,
                terminalLagSeen = false,
                terminalPayloadSeen = false,
                sink = sink,
            )
        }
        if (indexes.first() != 0L) throw IOException("journal does not begin with segment zero")
        var activeIndex = -1L
        var activeBytes = 0L
        for ((position, index) in indexes.withIndex()) {
            if (activeIndex >= 0 && index != activeIndex + 1) {
                storage.discardSegmentsAfter(activeIndex)
                break
            }
            val scan = ChapterJournalFormat.scanSegment(
                storage.readSegment(index),
                expectedSegmentIndex = index,
                expectedGeneration = storeGeneration,
                expectedEpochOrdinal = epochOrdinal,
                expectedSessionId = sessionId,
                firstExpectedFrameSeq = expectedFrameSeq,
                firstExpectedCommitSeq = expectedCommitSeq,
                lastCommitSeq = lastCommitSeq,
                terminalLagSeen = terminalLagSeen,
                terminalPayloadSeen = terminalPayloadSeen,
            )
            if (!scan.validHeader) {
                if (position == 0) throw IOException("unsupported or invalid journal segment header")
                storage.discardSegmentsAfter(index - 1)
                break
            }
            expectedFrameSeq = scan.nextFrameSeq
            expectedCommitSeq = scan.nextCommitSeq
            lastCommitSeq = scan.frames.asReversed().firstOrNull { it.commitSeq != null }?.commitSeq ?: lastCommitSeq
            terminalLagSeen = scan.terminalLagSeen
            terminalPayloadSeen = scan.terminalPayloadSeen
            activeIndex = index
            activeBytes = scan.validBytes.toLong()
            if (scan.stoppedAtInvalidFrame) {
                storage.truncateSegment(index, activeBytes)
                storage.discardSegmentsAfter(index)
                break
            }
        }
        if (activeIndex < 0) throw IOException("journal has no usable segment")
        val sink = if (activeBytes >= segmentByteLimit) {
            val nextIndex = activeIndex + 1
            syncExistingSegment(activeIndex)
            createSegment(nextIndex).also {
                activeIndex = nextIndex
                activeBytes = ChapterJournalFormat.SEGMENT_HEADER_BYTES.toLong()
            }
        } else {
            storage.openSegment(activeIndex, create = false).also { sink ->
                sink.flush()
                sink.sync()
            }
        }
        return Initialized(
            segmentIndex = activeIndex,
            segmentBytes = activeBytes,
            nextFrameSeq = expectedFrameSeq,
            nextCommitSeq = expectedCommitSeq,
            terminalLagSeen = terminalLagSeen,
            terminalPayloadSeen = terminalPayloadSeen,
            sink = sink,
        )
    }

    private fun createSegment(index: Long): ChapterJournalSink {
        val sink = storage.openSegment(index, create = true)
        try {
            writeFully(sink, ChapterJournalFormat.segmentHeader(index, storeGeneration, epochOrdinal, sessionId))
            sink.flush()
            sink.sync()
            storage.syncDirectory()
            return sink
        } catch (failure: Throwable) {
            runCatching { sink.close() }
            throw failure
        }
    }

    private fun syncExistingSegment(index: Long) {
        storage.openSegment(index, create = false).use { sink ->
            sink.flush()
            sink.sync()
        }
    }

    private fun writeFully(sink: ChapterJournalSink, bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val written = sink.write(bytes, offset, bytes.size - offset)
            if (written <= 0) throw EOFException("journal storage returned a short write")
            offset += written
        }
        sink.flush()
    }

    private data class Initialized(
        val segmentIndex: Long,
        val segmentBytes: Long,
        val nextFrameSeq: Long,
        val nextCommitSeq: Long,
        val terminalLagSeen: Boolean,
        val terminalPayloadSeen: Boolean,
        val sink: ChapterJournalSink,
    )

    private sealed interface Command {
        data class Append(
            val commitSeq: Long,
            val kind: ChapterJournalFormat.RecordKind,
            val encode: () -> ByteArray,
            val inventory: ChapterJournalInventorySnapshot,
            val credit: ChapterJournalCredit,
            val pageKey: String,
            val traceRuns: List<TranslationRunTrace>,
            val syncImmediately: Boolean,
        ) : Command

        data object InventoryWake : Command

        data class TerminalLag(
            val starvedAtCommitSeq: Long,
            val inventory: ChapterJournalInventorySnapshot,
            val encode: () -> ByteArray,
        ) : Command
        data class Defunct(val encode: (Long) -> ByteArray) : Command
        data class Close(val done: CompletableDeferred<Unit>) : Command
        data object FreeSyncDue : Command
    }

    companion object {
        const val DEFAULT_REGULAR_CREDITS = 8
        const val DEFAULT_FOREGROUND_CREDITS = 1
        const val DEFAULT_FREE_DURABILITY_INTERVAL_MS = 1_000L
        private val epochOrdinalReservations = java.util.concurrent.ConcurrentHashMap<String, AtomicLong>()
        private val DEFAULT_CHAPTER_IDENTITY_HASH = StageFingerprints.sha256Hex("test-chapter".encodeToByteArray())

        /**
         * Allocate one persisted epoch ordinal for a newly-opened store session.
         * The active-chapter registry guarantees one active store per chapter, and
         * the owning store serializes concurrent first writes before calling here.
         */
        fun allocateAppPrivateEpoch(
            filesDir: File,
            chapterIdentity: String,
            generation: Long,
            sessionId: UUID,
        ): AppPrivateJournalEpoch {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(chapterIdentity.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            val chapterDirectory = File(File(filesDir, "translation-journal-v1"), digest)
            if (chapterDirectory.exists() && !chapterDirectory.isDirectory) {
                throw IOException("chapter journal path is not a directory")
            }
            val siblings = if (chapterDirectory.exists()) {
                chapterDirectory.listFiles() ?: throw IOException("unable to list chapter journal epochs")
            } else {
                emptyArray()
            }
            val ordinalPattern = Regex("epoch-([0-9]{20})-g.+")
            val maxOrdinal = siblings.asSequence()
                .filter { it.isDirectory }
                .mapNotNull { child ->
                    val match = ordinalPattern.matchEntire(child.name) ?: return@mapNotNull null
                    match.groupValues[1].toLongOrNull()
                        ?: throw IOException("invalid persisted journal epoch ordinal: ${child.name}")
                }
                .maxOrNull() ?: 0L
            // One active store per chapter is guaranteed by ActiveChapterStoreRegistry. This
            // process-local reservation additionally fences a lingering old writer whose first
            // file is still being created while a replacement epoch is allocated.
            val reservation = epochOrdinalReservations.computeIfAbsent(chapterDirectory.absolutePath) {
                AtomicLong(maxOrdinal)
            }
            val ordinal = try {
                reservation.updateAndGet { current -> Math.addExact(maxOf(current, maxOrdinal), 1L) }
            } catch (overflow: ArithmeticException) {
                throw IOException("journal epoch ordinal exhausted", overflow)
            }
            val pathOrdinal = ordinal.toString().padStart(20, '0')
            val epochDirectory = File(chapterDirectory, "epoch-$pathOrdinal-g$generation-$sessionId")
            if (epochDirectory.exists()) throw IOException("journal epoch ordinal collision")
            return AppPrivateJournalEpoch(epochDirectory, ordinal, digest)
        }

        private val DEFAULT_SESSION_ID = UUID(0L, 0L)
    }
}
