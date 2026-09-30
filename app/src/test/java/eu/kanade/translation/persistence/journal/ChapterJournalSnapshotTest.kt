package eu.kanade.translation.persistence.journal

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.toPublishedPage
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.TreeMap
import java.util.UUID

class ChapterJournalSnapshotTest {

    @Test
    fun `snapshot envelope detects incomplete trailer and corrupted payload`() {
        val encoded = ChapterJournalSnapshotFormat.encode(payload(generation = 1L))

        ChapterJournalSnapshotFormat.decode(encoded)?.payload shouldBe payload(generation = 1L)
        ChapterJournalSnapshotFormat.decode(encoded.copyOf(encoded.size - 1)) shouldBe null

        val corrupted = encoded.copyOf()
        corrupted[corrupted.lastIndex - 8] = (corrupted[corrupted.lastIndex - 8].toInt() xor 1).toByte()
        ChapterJournalSnapshotFormat.decode(corrupted) shouldBe null
    }

    @Test
    fun `snapshot write follows synced data and atomic durable marker protocol`() {
        val storage = MemorySnapshotStorage()
        val manager = ChapterJournalSnapshotManager(storage)
        val frontier = listOf(coverage(3, 8, acked = 5, closed = false))
        storage.epochDirectories += ChapterJournalEpochKey(3, 8)

        val result = manager.writeSnapshot(frontier, mapOf("page-a" to page("page-a")))

        result.state shouldBe ChapterJournalSnapshotState.DURABLE
        manager.candidates().single().frontier shouldBe frontier
        storage.events shouldContainExactly listOf(
            "create-snapshot:1",
            "sync-snapshot:1",
            "sync-directory",
            "write-marker-temp:1",
            "sync-marker-temp:1",
            "publish-marker:1",
            "sync-directory",
        )
        storage.epochKeys() shouldBe setOf(ChapterJournalEpochKey(3, 8)) // Shadow GC is dormant.
    }

    @Test
    fun `partial snapshot is writing and markerless valid snapshot cannot authorize gc`() {
        val tornStorage = MemorySnapshotStorage().apply { tearSnapshotOnWrite = true }
        val tornManager = ChapterJournalSnapshotManager(tornStorage)
        assertThrows(IOException::class.java) {
            tornManager.writeSnapshot(emptyList(), mapOf("page-a" to page("page-a")))
        }
        tornManager.candidates().single().state shouldBe ChapterJournalSnapshotState.WRITING

        val markerlessStorage = MemorySnapshotStorage().apply { failOn = "write-marker-temp:1" }
        val markerlessManager = ChapterJournalSnapshotManager(markerlessStorage)
        assertThrows(IOException::class.java) {
            markerlessManager.writeSnapshot(emptyList(), mapOf("page-a" to page("page-a")))
        }
        val valid = markerlessManager.candidates().single()
        valid.state shouldBe ChapterJournalSnapshotState.VALID
        assertThrows(IllegalStateException::class.java) {
            markerlessManager.executeAuthorizedGc(valid, emptyList())
        }
    }

    @Test
    fun `a visible final marker follows a synced temporary marker`() {
        val storage = MemorySnapshotStorage().apply { failOn = "sync-directory:2" }
        val manager = ChapterJournalSnapshotManager(storage)

        assertThrows(IOException::class.java) {
            manager.writeSnapshot(emptyList(), mapOf("page-a" to page("page-a")))
        }

        manager.candidates().single().state shouldBe ChapterJournalSnapshotState.DURABLE
        storage.events.takeLast(4) shouldContainExactly listOf(
            "write-marker-temp:1",
            "sync-marker-temp:1",
            "publish-marker:1",
            "sync-directory",
        )
    }

    @Test
    fun `strict frontier monotonicity is enforced before writing a successor`() {
        val storage = MemorySnapshotStorage()
        val manager = ChapterJournalSnapshotManager(storage)
        val frontier = listOf(coverage(1, 1, acked = 4, closed = true))
        manager.writeSnapshot(frontier, mapOf("page-a" to page("page-a")))

        assertThrows(IllegalStateException::class.java) {
            manager.writeSnapshot(frontier, mapOf("page-a" to page("page-a")))
        }
        manager.writeSnapshot(frontier + coverage(2, 2, acked = 0, closed = false), emptyMap())
        manager.candidates().map { it.generation }.sorted() shouldContainExactly listOf(1L, 2L)
    }

    @Test
    fun `epoch gc requires durable coverage and retains late append beyond snapshot frontier`() {
        val keyCovered = ChapterJournalEpochKey(4, 7)
        val keyLate = ChapterJournalEpochKey(5, 8)
        val keyOpen = ChapterJournalEpochKey(6, 9)
        val storage = MemorySnapshotStorage().apply {
            epochDirectories += setOf(keyCovered, keyLate, keyOpen)
        }
        val manager = ChapterJournalSnapshotManager(storage)
        val covered = coverage(keyCovered.storeGeneration, keyCovered.epochOrdinal, acked = 5, closed = true)
        val durable = manager.writeSnapshot(
            frontier = listOf(covered, coverage(keyLate.storeGeneration, keyLate.epochOrdinal, acked = 4, closed = true)),
            pages = emptyMap(),
            gcMode = ChapterJournalGcMode.AUTHORITATIVE,
            epochStatesAtGc = listOf(
                ChapterJournalEpochGcState(keyCovered, ackedFrameSeq = 5, terminallyClosed = true, coverageVerified = true),
                // A late append occurred after snapshot coverage, so this epoch must survive GC.
                ChapterJournalEpochGcState(keyLate, ackedFrameSeq = 5, terminallyClosed = true, coverageVerified = true),
                ChapterJournalEpochGcState(keyOpen, ackedFrameSeq = 0, terminallyClosed = false),
            ),
        )

        durable.state shouldBe ChapterJournalSnapshotState.DURABLE
        storage.deletedEpochs shouldBe listOf(keyCovered)
        storage.epochKeys() shouldBe setOf(keyLate, keyOpen)
        val markerPublished = storage.events.indexOf("publish-marker:1")
        val markerDirectorySync = storage.events.indices.first { index ->
            index > markerPublished && storage.events[index] == "sync-directory"
        }
        val firstDelete = storage.events.indexOfFirst { it.startsWith("delete-epoch:") }
        (markerDirectorySync < firstDelete) shouldBe true
    }

    @Test
    fun `disk scan reconstructs only the contiguous CRC-valid epoch prefix`() {
        val durableRoot = Files.createTempDirectory("journal-coverage").toFile()
        try {
            val chapterDirectory = File(durableRoot, "chapter-hash").apply { mkdirs() }
            val generation = 12L
            val ordinal = 31L
            val session = UUID.randomUUID()
            val epochDirectory = File(
                chapterDirectory,
                "epoch-${ordinal.toString().padStart(20, '0')}-g$generation-$session",
            ).apply { mkdirs() }
            val validFrame = ChapterJournalFormat.encodeFrame(
                frameSeq = 1L,
                commitSeq = null,
                kind = ChapterJournalFormat.RecordKind.INVENTORY,
                payload = "inventory".encodeToByteArray(),
            )
            val tornFrame = ChapterJournalFormat.encodeFrame(
                frameSeq = 2L,
                commitSeq = 1L,
                kind = ChapterJournalFormat.RecordKind.PAID_STATE,
                payload = "state".encodeToByteArray(),
            ).copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            File(epochDirectory, "segment-00000000.tjr").writeBytes(
                ChapterJournalFormat.segmentHeader(0L, generation, ordinal, session) + validFrame + tornFrame,
            )

            val reconstructed = FileChapterJournalSnapshotStorage(chapterDirectory, durableRoot)
                .scanEpochCoverages()

            reconstructed shouldBe listOf(
                ChapterJournalEpochCoverage(
                    storeGeneration = generation,
                    epochOrdinal = ordinal,
                    ackedFrameSeq = 1L,
                    terminallyClosed = false,
                ),
            )
        } finally {
            durableRoot.deleteRecursively()
        }
    }

    @Test
    fun unknownDiskScanRetainsPredecessorCoveredEpochWhileFreshScanCanAuthorizeDeletion() {
        val key = ChapterJournalEpochKey(12, 31)
        val predecessor = coverage(12, 31, acked = 5, closed = true)
        val newEpoch = coverage(13, 32, acked = 0, closed = false)

        val unknownStorage = MemorySnapshotStorage().apply { epochDirectories += key }
        val unknownManager = ChapterJournalSnapshotManager(unknownStorage)
        unknownManager.writeSnapshot(listOf(predecessor), emptyMap())
        val unknownScan = unknownManager.scanEpochCoverages()
        val unknownFrontier = ChapterJournalCompaction.mergeFrontiers(
            listOf(predecessor),
            ChapterJournalCompaction.markDiskEpochsClosedOutsideLiveSet(unknownScan, emptySet()),
            listOf(newEpoch),
        )
        val unknownVerifiedKeys = unknownScan.mapTo(mutableSetOf()) { it.key }.apply { add(newEpoch.key) }
        val retained = unknownManager.writeSnapshot(
            frontier = unknownFrontier,
            pages = emptyMap(),
            epochStatesAtGc = ChapterJournalCompaction.gcStatesAtCapture(unknownFrontier, unknownVerifiedKeys),
            gcMode = ChapterJournalGcMode.AUTHORITATIVE,
        )

        retained.state shouldBe ChapterJournalSnapshotState.DURABLE
        unknownStorage.deletedEpochs shouldBe emptyList()
        unknownStorage.epochDirectories shouldBe setOf(key)

        val verifiedStorage = MemorySnapshotStorage().apply {
            epochDirectories += key
            scannedCoverages = listOf(coverage(12, 31, acked = 7, closed = false))
        }
        val verifiedManager = ChapterJournalSnapshotManager(verifiedStorage)
        verifiedManager.writeSnapshot(listOf(predecessor), emptyMap())
        val freshScan = verifiedManager.scanEpochCoverages()
        val verifiedDisk = ChapterJournalCompaction.markDiskEpochsClosedOutsideLiveSet(freshScan, emptySet())
        val verifiedFrontier = ChapterJournalCompaction.mergeFrontiers(
            listOf(predecessor),
            verifiedDisk,
            listOf(newEpoch),
        )
        val verifiedKeys = freshScan.mapTo(mutableSetOf()) { it.key }.apply { add(newEpoch.key) }
        verifiedManager.writeSnapshot(
            frontier = verifiedFrontier,
            pages = emptyMap(),
            epochStatesAtGc = ChapterJournalCompaction.gcStatesAtCapture(verifiedFrontier, verifiedKeys),
            gcMode = ChapterJournalGcMode.AUTHORITATIVE,
        )

        verifiedStorage.deletedEpochs shouldBe listOf(key)
        verifiedStorage.epochDirectories shouldBe emptySet()
    }

    @Test
    fun unknownFilesMakeEpochCoverageScanFailRetained() {
        val durableRoot = Files.createTempDirectory("journal-unknown-epoch").toFile()
        try {
            val chapterDirectory = File(durableRoot, "chapter-hash").apply { mkdirs() }
            val generation = 12L
            val ordinal = 31L
            val session = UUID.randomUUID()
            val key = ChapterJournalEpochKey(generation, ordinal)
            val epochDirectory = File(
                chapterDirectory,
                "epoch-" + ordinal.toString().padStart(20, '0') + "-g" + generation + "-" + session,
            ).apply { mkdirs() }
            File(epochDirectory, "segment-00000000.tjr").writeBytes(
                ChapterJournalFormat.segmentHeader(0L, generation, ordinal, session),
            )
            File(epochDirectory, "unrecognized-tail.bin").writeBytes(byteArrayOf(1, 2, 3))

            val storage = FileChapterJournalSnapshotStorage(chapterDirectory, durableRoot)
            storage.epochKeys() shouldBe setOf(key)
            storage.scanEpochCoverages() shouldBe emptyList()
        } finally {
            durableRoot.deleteRecursively()
        }
    }

    private fun payload(generation: Long) = ChapterJournalSnapshotPayload(
        generation = generation,
        frontier = emptyList(),
        pages = mapOf("page-a" to page("page-a")),
    )

    private fun page(key: String) = PageTranslation(sourceFileName = key, pageVersion = 4L).toPublishedPage()

    private fun coverage(
        storeGeneration: Long,
        epochOrdinal: Long,
        acked: Long,
        closed: Boolean,
    ) = ChapterJournalEpochCoverage(storeGeneration, epochOrdinal, acked, closed)

    private class MemorySnapshotStorage : ChapterJournalSnapshotStorage {
        private val snapshots = TreeMap<Long, ByteArray>()
        private val markerTemps = mutableMapOf<Long, ByteArray>()
        private val markers = mutableMapOf<Long, ByteArray>()
        val events = mutableListOf<String>()
        val deletedEpochs = mutableListOf<ChapterJournalEpochKey>()
        val epochDirectories = mutableSetOf<ChapterJournalEpochKey>()
        var scannedCoverages: List<ChapterJournalEpochCoverage> = emptyList()
        var failOn: String? = null
        var tearSnapshotOnWrite = false

        override fun snapshotGenerations(): List<Long> = snapshots.keys.toList()

        override fun readSnapshot(generation: Long): ByteArray? = snapshots[generation]?.copyOf()

        override fun createSnapshot(generation: Long, bytes: ByteArray) {
            event("create-snapshot:$generation")
            if (tearSnapshotOnWrite) {
                snapshots[generation] = bytes.copyOf(bytes.size / 2)
                throw IOException("injected torn snapshot write")
            }
            snapshots[generation] = bytes.copyOf()
        }

        override fun syncSnapshot(generation: Long) = event("sync-snapshot:$generation")

        override fun syncDirectory() {
            val ordinal = events.count { it.startsWith("sync-directory") } + 1
            event("sync-directory:$ordinal", recorded = "sync-directory")
        }

        override fun readDurableMarker(generation: Long): ByteArray? = markers[generation]?.copyOf()

        override fun writeDurableMarkerTemp(generation: Long, bytes: ByteArray) {
            event("write-marker-temp:$generation")
            markerTemps[generation] = bytes.copyOf()
        }

        override fun syncDurableMarkerTemp(generation: Long) = event("sync-marker-temp:$generation")

        override fun publishDurableMarker(generation: Long) {
            event("publish-marker:$generation")
            markers[generation] = checkNotNull(markerTemps.remove(generation))
        }

        override fun epochKeys(): Set<ChapterJournalEpochKey> = epochDirectories.toSet()

        override fun scanEpochCoverages(): List<ChapterJournalEpochCoverage> = scannedCoverages

        override fun deleteEpoch(key: ChapterJournalEpochKey) {
            event("delete-epoch:$key")
            epochDirectories.remove(key)
            deletedEpochs += key
        }

        override fun deleteSnapshot(generation: Long) {
            event("delete-snapshot:$generation")
            snapshots.remove(generation)
            markers.remove(generation)
        }

        private fun event(value: String, recorded: String = value) {
            events += recorded
            if (failOn == value) throw IOException("injected failure at $value")
        }
    }
}
