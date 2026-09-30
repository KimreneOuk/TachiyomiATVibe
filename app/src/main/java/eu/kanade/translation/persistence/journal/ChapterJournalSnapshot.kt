package eu.kanade.translation.persistence.journal

import android.system.Os
import android.system.OsConstants
import eu.kanade.translation.model.PublishedPageTranslation
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.zip.CRC32

/**
 * Immutable page map captured at the store barrier. In shadow mode this is the published legacy
 * state and can legitimately contain state beyond the journal frontier; frontier metadata is not
 * provenance. ACK-purity is a cutover-activated guarantee, owned by E16c's recovery contract.
 */
@Serializable
internal data class ChapterJournalSnapshotPayload(
    val schemaVersion: Int = SCHEMA_VERSION,
    val generation: Long,
    val frontier: List<ChapterJournalEpochCoverage>,
    val pages: Map<String, PublishedPageTranslation>,
) {
    init {
        require(schemaVersion == SCHEMA_VERSION) { "unsupported snapshot schema version: $schemaVersion" }
        require(generation > 0L) { "snapshot generation must be positive" }
        require(frontier == frontier.sortedWith(compareBy({ it.storeGeneration }, { it.epochOrdinal }))) {
            "snapshot frontier must be sorted by store generation and epoch ordinal"
        }
        require(frontier.map { it.key }.distinct().size == frontier.size) {
            "snapshot frontier cannot contain duplicate epochs"
        }
    }

    companion object {
        const val SCHEMA_VERSION = 1
    }
}

internal data class DecodedChapterJournalSnapshot(
    val payload: ChapterJournalSnapshotPayload,
    val payloadCrc: Int,
)

/** CRC-framed, versioned snapshot envelope. An incomplete trailer is always classified WRITING. */
internal object ChapterJournalSnapshotFormat {
    private const val MAGIC = 0x544A5331 // TJS1
    private const val TRAILER_MAGIC = 0x454E4453 // ENDS
    private const val MARKER_MAGIC = 0x544A444D // TJDM
    private const val FORMAT_VERSION = 1
    private const val HEADER_BYTES = 20
    private const val TRAILER_BYTES = 8
    private const val MARKER_BYTES = 24
    const val MAX_SNAPSHOT_BYTES = 128 * 1024 * 1024

    fun encode(payload: ChapterJournalSnapshotPayload, json: Json = DEFAULT_JSON): ByteArray {
        val encodedPayload = json.encodeToString(payload).encodeToByteArray()
        require(encodedPayload.size <= MAX_SNAPSHOT_BYTES) {
            "journal snapshot payload exceeds $MAX_SNAPSHOT_BYTES bytes"
        }
        val header = ByteBuffer.allocate(HEADER_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(MAGIC)
            .putInt(FORMAT_VERSION)
            .putLong(payload.generation)
            .putInt(encodedPayload.size)
            .array()
        val crc = CRC32().apply {
            update(header)
            update(encodedPayload)
        }.value.toInt()
        return ByteBuffer.allocate(HEADER_BYTES + encodedPayload.size + TRAILER_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .put(header)
            .put(encodedPayload)
            .putInt(crc)
            .putInt(TRAILER_MAGIC)
            .array()
    }

    fun decode(bytes: ByteArray, json: Json = DEFAULT_JSON): DecodedChapterJournalSnapshot? {
        if (bytes.size < HEADER_BYTES + TRAILER_BYTES || bytes.size > MAX_SNAPSHOT_BYTES + HEADER_BYTES + TRAILER_BYTES) {
            return null
        }
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val magic = input.int
        val version = input.int
        val generation = input.long
        val payloadLength = input.int
        if (magic != MAGIC ||
            version != FORMAT_VERSION ||
            generation <= 0L ||
            payloadLength < 0 ||
            payloadLength > MAX_SNAPSHOT_BYTES ||
            bytes.size != HEADER_BYTES + payloadLength + TRAILER_BYTES
        ) {
            return null
        }
        val expectedCrc = ByteBuffer.wrap(bytes, HEADER_BYTES + payloadLength, 4).order(ByteOrder.BIG_ENDIAN).int
        val trailer = ByteBuffer.wrap(bytes, HEADER_BYTES + payloadLength + 4, 4).order(ByteOrder.BIG_ENDIAN).int
        if (trailer != TRAILER_MAGIC) return null
        val actualCrc = CRC32().apply { update(bytes, 0, HEADER_BYTES + payloadLength) }.value.toInt()
        if (actualCrc != expectedCrc) return null
        val payload = runCatching {
            json.decodeFromString<ChapterJournalSnapshotPayload>(
                bytes.copyOfRange(HEADER_BYTES, HEADER_BYTES + payloadLength).decodeToString(),
            )
        }.getOrNull() ?: return null
        if (payload.generation != generation) return null
        return DecodedChapterJournalSnapshot(payload, expectedCrc)
    }

    fun encodeDurableMarker(generation: Long, snapshotCrc: Int): ByteArray {
        require(generation > 0L) { "snapshot generation must be positive" }
        val body = ByteBuffer.allocate(MARKER_BYTES - 4)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(MARKER_MAGIC)
            .putInt(FORMAT_VERSION)
            .putLong(generation)
            .putInt(snapshotCrc)
            .array()
        val crc = CRC32().apply { update(body) }.value.toInt()
        return ByteBuffer.allocate(MARKER_BYTES).order(ByteOrder.BIG_ENDIAN).put(body).putInt(crc).array()
    }

    fun hasValidDurableMarker(bytes: ByteArray?, snapshot: DecodedChapterJournalSnapshot): Boolean {
        if (bytes == null || bytes.size != MARKER_BYTES) return false
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val magic = input.int
        val version = input.int
        val generation = input.long
        val snapshotCrc = input.int
        val markerCrc = input.int
        val bodyCrc = CRC32().apply { update(bytes, 0, MARKER_BYTES - 4) }.value.toInt()
        return magic == MARKER_MAGIC &&
            version == FORMAT_VERSION &&
            generation == snapshot.payload.generation &&
            snapshotCrc == snapshot.payloadCrc &&
            markerCrc == bodyCrc
    }

    private val DEFAULT_JSON = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
}

/** Minimal file operations needed to prove snapshot durability ordering and GC authorization. */
internal interface ChapterJournalSnapshotStorage {
    fun snapshotGenerations(): List<Long>
    fun readSnapshot(generation: Long): ByteArray?
    fun createSnapshot(generation: Long, bytes: ByteArray)
    fun syncSnapshot(generation: Long)
    fun syncDirectory()
    fun readDurableMarker(generation: Long): ByteArray?
    fun writeDurableMarkerTemp(generation: Long, bytes: ByteArray)
    fun syncDurableMarkerTemp(generation: Long)
    fun publishDurableMarker(generation: Long)
    fun epochKeys(): Set<ChapterJournalEpochKey>

    /** CRC-valid frame-prefix metadata only; callers determine closure from the live-writer set. */
    fun scanEpochCoverages(): List<ChapterJournalEpochCoverage> = emptyList()
    fun deleteEpoch(key: ChapterJournalEpochKey)
    fun deleteSnapshot(generation: Long)
}

/** Filesystem snapshot storage under the chapter's app-private journal directory. */
internal class FileChapterJournalSnapshotStorage(
    private val chapterDirectory: File,
    private val durableRoot: File,
) : ChapterJournalSnapshotStorage {
    override fun snapshotGenerations(): List<Long> = chapterDirectory.listFiles().orEmpty()
        .mapNotNull { file ->
            SNAPSHOT_NAME.matchEntire(file.name)?.groupValues?.get(1)?.toLongOrNull()
        }
        .sorted()

    override fun readSnapshot(generation: Long): ByteArray? = snapshotFile(generation).takeIf(File::isFile)?.readBytes()

    override fun createSnapshot(generation: Long, bytes: ByteArray) {
        requireChapterDirectory()
        FileChannel.open(
            snapshotFile(generation).toPath(),
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
        ).use { channel -> writeFully(channel, bytes) }
    }

    override fun syncSnapshot(generation: Long) = syncFile(snapshotFile(generation))

    override fun syncDirectory() {
        var path: File? = chapterDirectory
        while (path != null) {
            val fd = Os.open(path.absolutePath, OsConstants.O_RDONLY, 0)
            try {
                Os.fsync(fd)
            } finally {
                Os.close(fd)
            }
            if (path == durableRoot) break
            path = path.parentFile
        }
        check(path == durableRoot) { "chapter journal directory must be under the durable app-private root" }
    }

    override fun readDurableMarker(generation: Long): ByteArray? = durableMarker(generation).takeIf(File::isFile)?.readBytes()

    override fun writeDurableMarkerTemp(generation: Long, bytes: ByteArray) {
        requireChapterDirectory()
        val temp = durableMarkerTemp(generation)
        if (temp.exists() && !temp.delete()) throw IOException("unable to replace stale durable marker temp")
        FileChannel.open(temp.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
            writeFully(channel, bytes)
        }
    }

    override fun syncDurableMarkerTemp(generation: Long) = syncFile(durableMarkerTemp(generation))

    override fun publishDurableMarker(generation: Long) {
        try {
            Files.move(
                durableMarkerTemp(generation).toPath(),
                durableMarker(generation).toPath(),
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (failure: AtomicMoveNotSupportedException) {
            throw IOException("atomic durable marker publication is not supported", failure)
        }
    }

    override fun epochKeys(): Set<ChapterJournalEpochKey> = chapterDirectory.listFiles().orEmpty()
        .asSequence()
        .filter(File::isDirectory)
        .mapNotNull { directory ->
            EPOCH_NAME.matchEntire(directory.name)?.let { match ->
                val ordinal = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
                val generation = match.groupValues[2].toLongOrNull() ?: return@mapNotNull null
                ChapterJournalEpochKey(generation, ordinal)
            }
        }
        .toSet()

    override fun scanEpochCoverages(): List<ChapterJournalEpochCoverage> {
        if (!chapterDirectory.exists()) return emptyList()
        val children = chapterDirectory.listFiles()
            ?: throw IOException("unable to list journal chapter directory for epoch coverage")
        return children.asSequence()
            .filter(File::isDirectory)
            .mapNotNull(::scanEpochCoverage)
            .toList()
    }

    /**
     * Reads framing metadata only; it never decodes or applies page records. Closure is deliberately
     * left false here: DEFUNCT is only an eviction boundary and may be followed by credited appends.
     * The chapter coordinator marks epochs closed only after their writer is absent from its live set.
     */
    private fun scanEpochCoverage(directory: File): ChapterJournalEpochCoverage? {
        val match = EPOCH_NAME.matchEntire(directory.name) ?: return null
        val epochOrdinal = match.groupValues[1].toLongOrNull() ?: return null
        val storeGeneration = match.groupValues[2].toLongOrNull() ?: return null
        val sessionId = runCatching { UUID.fromString(match.groupValues[3]) }.getOrNull() ?: return null
        if (!directory.canonicalFile.toPath().startsWith(chapterDirectory.canonicalFile.toPath())) return null
        val epochStorage = FileChapterJournalStorage(directory, durableRoot)
        val files = directory.listFiles() ?: return null
        // Any unrecognized entry makes the epoch scan unknown; never under-report and then GC it.
        val indexes = files.map { file ->
            SEGMENT_NAME.matchEntire(file.name)?.groupValues?.get(1)?.toLongOrNull() ?: return null
        }.sorted()
        if (indexes.isNotEmpty() && indexes.first() != 0L) return null
        var expectedSegmentIndex = 0L
        var expectedFrameSeq = 1L
        var expectedCommitSeq = 1L
        var lastCommitSeq = 0L
        var terminalLagSeen = false
        var terminalPayloadSeen = false

        for (index in indexes) {
            if (index != expectedSegmentIndex) break
            val scan = try {
                ChapterJournalFormat.scanSegment(
                    epochStorage.readSegment(index),
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
            } catch (failure: IOException) {
                if (expectedFrameSeq == 1L) return null
                break
            }
            if (!scan.validHeader) {
                if (expectedFrameSeq == 1L) return null
                break
            }
            expectedFrameSeq = scan.nextFrameSeq
            expectedCommitSeq = scan.nextCommitSeq
            lastCommitSeq = scan.frames.asReversed().firstNotNullOfOrNull { it.commitSeq } ?: lastCommitSeq
            terminalLagSeen = scan.terminalLagSeen
            terminalPayloadSeen = scan.terminalPayloadSeen
            if (scan.stoppedAtInvalidFrame) break
            expectedSegmentIndex++
        }
        return ChapterJournalEpochCoverage(
            storeGeneration = storeGeneration,
            epochOrdinal = epochOrdinal,
            ackedFrameSeq = expectedFrameSeq - 1L,
            terminallyClosed = false,
        )
    }

    override fun deleteEpoch(key: ChapterJournalEpochKey) {
        val prefix = "epoch-${key.epochOrdinal.toString().padStart(20, '0')}-g${key.storeGeneration}-"
        val targets = chapterDirectory.listFiles().orEmpty().filter { it.isDirectory && it.name.startsWith(prefix) }
        if (targets.size > 1) throw IOException("multiple journal epoch directories match $key")
        targets.singleOrNull()?.let { target ->
            Files.walk(target.toPath()).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    override fun deleteSnapshot(generation: Long) {
        val snapshot = snapshotFile(generation)
        val marker = durableMarker(generation)
        if (snapshot.exists() && !snapshot.delete()) throw IOException("unable to delete snapshot ${snapshot.name}")
        if (marker.exists() && !marker.delete()) throw IOException("unable to delete marker ${marker.name}")
    }

    private fun requireChapterDirectory() {
        if (!chapterDirectory.isDirectory && !chapterDirectory.mkdirs()) {
            throw IOException("unable to create chapter journal directory")
        }
        if (!chapterDirectory.canonicalFile.toPath().startsWith(durableRoot.canonicalFile.toPath())) {
            throw IOException("chapter journal directory escapes app-private storage")
        }
    }

    private fun snapshotFile(generation: Long): File = File(chapterDirectory, "snapshot-${generation.fileSuffix()}.tjs")
    private fun durableMarker(generation: Long): File = File(chapterDirectory, "snapshot-${generation.fileSuffix()}.durable")
    private fun durableMarkerTemp(generation: Long): File = File(chapterDirectory, "snapshot-${generation.fileSuffix()}.durable.tmp")

    private fun Long.fileSuffix(): String {
        require(this > 0L)
        return toString().padStart(20, '0')
    }

    private fun syncFile(file: File) {
        FileChannel.open(file.toPath(), StandardOpenOption.WRITE).use { it.force(true) }
    }

    private fun writeFully(channel: FileChannel, bytes: ByteArray) {
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) channel.write(buffer)
    }

    private companion object {
        val SNAPSHOT_NAME = Regex("snapshot-([0-9]{20})\\.tjs")
        val EPOCH_NAME = Regex("epoch-([0-9]{20})-g([0-9]+)-([0-9a-fA-F-]{36})")
        val SEGMENT_NAME = Regex("segment-([0-9]{8})\\.tjr")
    }
}

internal enum class ChapterJournalGcMode {
    /** Shadow mode retains every epoch for E16b replay certification. */
    SHADOW_DORMANT,

    /** Explicit cutover/test opt-in. No production caller enables this in E18. */
    AUTHORITATIVE,
}

/**
 * Writes a checksummed snapshot and establishes durability before any optional deletion. Automatic
 * invocation and GC remain dormant in shadow mode; tests and E16b may opt in explicitly.
 */
internal class ChapterJournalSnapshotManager(
    private val storage: ChapterJournalSnapshotStorage,
    private val json: Json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    },
) {
    fun candidates(): List<ChapterJournalSnapshotCandidate> = storage.snapshotGenerations().map { generation ->
        val decoded = storage.readSnapshot(generation)?.let { ChapterJournalSnapshotFormat.decode(it, json) }
        ChapterJournalSnapshotCandidate(
            generation = generation,
            frontier = decoded?.payload?.frontier.orEmpty(),
            trailerValid = decoded != null,
            durableMarkerValid = decoded != null &&
                ChapterJournalSnapshotFormat.hasValidDurableMarker(
                    storage.readDurableMarker(generation),
                    decoded,
                ),
        )
    }

    fun newestUsableCandidate(): ChapterJournalSnapshotCandidate? =
        ChapterJournalCompaction.selectNewestUsable(candidates())

    fun scanEpochCoverages(): List<ChapterJournalEpochCoverage> = storage.scanEpochCoverages()

    fun epochKeys(): Set<ChapterJournalEpochKey> = storage.epochKeys()

    fun writeSnapshot(
        frontier: List<ChapterJournalEpochCoverage>,
        pages: Map<String, PublishedPageTranslation>,
        epochStatesAtGc: Collection<ChapterJournalEpochGcState> = emptyList(),
        gcMode: ChapterJournalGcMode = ChapterJournalGcMode.SHADOW_DORMANT,
    ): ChapterJournalSnapshotCandidate {
        val orderedFrontier = frontier.sortedWith(compareBy({ it.storeGeneration }, { it.epochOrdinal }))
        val existing = candidates()
        val newestUsable = ChapterJournalCompaction.selectNewestUsable(existing)
        if (newestUsable != null) {
            check(ChapterJournalCompaction.strictlyDominates(orderedFrontier, newestUsable.frontier)) {
                "new snapshot frontier must strictly dominate generation ${newestUsable.generation}"
            }
        }
        val generation = try {
            Math.addExact(storage.snapshotGenerations().maxOrNull() ?: 0L, 1L)
        } catch (failure: ArithmeticException) {
            throw IOException("journal snapshot generation exhausted", failure)
        }
        val payload = ChapterJournalSnapshotPayload(
            generation = generation,
            frontier = orderedFrontier,
            // Persistent published values are deeply immutable and can be shared until encoding.
            // E18 shadow snapshots do not assert that this map contains only journal-ACKed state.
            pages = pages,
        )
        val snapshotBytes = ChapterJournalSnapshotFormat.encode(payload, json)
        val decoded = checkNotNull(ChapterJournalSnapshotFormat.decode(snapshotBytes, json))

        storage.createSnapshot(generation, snapshotBytes)
        storage.syncSnapshot(generation)
        storage.syncDirectory()

        // The final marker appears only after its temp contents are synced. A crash can leave an
        // ignored temp file, or a fully valid final marker; it cannot expose unsynced marker bytes.
        storage.writeDurableMarkerTemp(
            generation,
            ChapterJournalSnapshotFormat.encodeDurableMarker(generation, decoded.payloadCrc),
        )
        storage.syncDurableMarkerTemp(generation)
        storage.publishDurableMarker(generation)
        storage.syncDirectory()

        val durable = ChapterJournalSnapshotCandidate(
            generation = generation,
            frontier = orderedFrontier,
            trailerValid = true,
            durableMarkerValid = true,
        )
        if (gcMode == ChapterJournalGcMode.AUTHORITATIVE) {
            executeAuthorizedGc(durable, epochStatesAtGc)
        }
        return durable
    }

    /**
     * GC is explicitly opt-in. A valid marker-less snapshot may be selected for recovery but can
     * never authorize segment or snapshot deletion.
     */
    fun executeAuthorizedGc(
        durableSnapshot: ChapterJournalSnapshotCandidate,
        epochStates: Collection<ChapterJournalEpochGcState>,
    ): List<ChapterJournalEpochKey> {
        check(durableSnapshot.state == ChapterJournalSnapshotState.DURABLE) {
            "only a durable snapshot may authorize garbage collection"
        }
        val allEpochs = storage.epochKeys()
        val byKey = epochStates.associateBy { it.key }
        val deleted = allEpochs.mapNotNull { key ->
            val state = byKey[key] ?: return@mapNotNull null
            if (!ChapterJournalCompaction.mayDeleteEpoch(durableSnapshot, state)) return@mapNotNull null
            storage.deleteEpoch(key)
            key
        }
        var prunedSnapshot = false
        val newestDurableGeneration = candidates()
            .filter { it.state == ChapterJournalSnapshotState.DURABLE }
            .maxOfOrNull { it.generation }
        if (newestDurableGeneration != null) {
            val currentCandidates = candidates().associateBy { it.generation }
            storage.snapshotGenerations()
                .filter { generation ->
                    val snapshot = currentCandidates[generation] ?: return@filter false
                    ChapterJournalCompaction.mayPruneSnapshot(snapshot, newestDurableGeneration)
                }
                .forEach { generation ->
                    storage.deleteSnapshot(generation)
                    prunedSnapshot = true
                }
        }
        if (deleted.isNotEmpty() || prunedSnapshot) storage.syncDirectory()
        return deleted
    }
}
