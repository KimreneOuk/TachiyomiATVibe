package eu.kanade.translation.persistence.journal

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.model.toPublishedPage
import eu.kanade.translation.persistence.artifact.StageFingerprints
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
    fun `real compacted journal selection agrees with full replay and preserves post-coverage append`() {
        val json = Json {
            encodeDefaults = true
            explicitNulls = true
            ignoreUnknownKeys = true
        }
        val oldEpochOrder = ChapterJournalFormat.EpochOrderKey(4L, 21L, UUID(0L, 21L))
        val successorEpochOrder = ChapterJournalFormat.EpochOrderKey(5L, 22L, UUID(0L, 22L))
        val oldInitial = replayPage("page.jpg", "old-epoch initial", pageVersion = 1L)
        val oldLate = replayPage("page.jpg", "old-epoch late append", pageVersion = 2L)
        val successor = replayPage("page.jpg", "successor epoch winner", pageVersion = 3L)
        val initialOldEpoch = replayEpoch(
            order = oldEpochOrder,
            expectedKeys = listOf("page.jpg"),
            records = listOf(
                ChapterJournalRecord(
                    pageKey = "page.jpg",
                    generation = 0L,
                    fencingToken = 0L,
                    pageVersion = 1L,
                    state = oldInitial,
                    artifactContentHash = StageFingerprints.pageSnapshot(oldInitial),
                ),
            ),
            json = json,
        )
        val oracleAtFirstCapture = ChapterJournalReplayReducer.replay(
            epochs = listOf(initialOldEpoch),
            artifactResolver = semanticResolver(),
            json = json,
        )
        val oldEpochWithLateAppend = appendRecord(
            epoch = initialOldEpoch,
            record = ChapterJournalRecord(
                pageKey = "page.jpg",
                generation = 0L,
                fencingToken = 0L,
                pageVersion = 2L,
                state = oldLate,
                artifactContentHash = StageFingerprints.pageSnapshot(oldLate),
            ),
            commitSeq = 2L,
            json = json,
        )
        val successorEpoch = replayEpoch(
            order = successorEpochOrder,
            expectedKeys = listOf("page.jpg"),
            records = listOf(
                ChapterJournalRecord(
                    pageKey = "page.jpg",
                    generation = 0L,
                    fencingToken = 0L,
                    pageVersion = 3L,
                    state = successor,
                    artifactContentHash = StageFingerprints.pageSnapshot(successor),
                ),
            ),
            json = json,
        )

        val storage = MemorySnapshotStorage()
        val oldEpochKey = ChapterJournalEpochKey(4L, 21L)
        val successorEpochKey = ChapterJournalEpochKey(5L, 22L)
        storage.epochDirectories += setOf(oldEpochKey, successorEpochKey)
        val manager = ChapterJournalSnapshotManager(storage)
        val firstFrontier = listOf(coverage(4L, 21L, acked = 2L, closed = false))
        val firstSnapshot = manager.writeSnapshot(firstFrontier, oracleAtFirstCapture.pages)
        firstSnapshot.state shouldBe ChapterJournalSnapshotState.DURABLE
        snapshotPayload(storage, checkNotNull(manager.newestUsableCandidate())).pages shouldBe oracleAtFirstCapture.pages

        // A later valid append in the covered epoch is above the snapshot's stamped frameSeq.
        // The successor epoch is not yet in that vector and has unknown coverage for this GC.
        manager.executeAuthorizedGc(
            durableSnapshot = firstSnapshot,
            epochStates = listOf(
                ChapterJournalEpochGcState(
                    key = oldEpochKey,
                    ackedFrameSeq = 3L,
                    terminallyClosed = true,
                    coverageVerified = true,
                ),
                ChapterJournalEpochGcState(
                    key = successorEpochKey,
                    ackedFrameSeq = 2L,
                    terminallyClosed = true,
                    coverageVerified = false,
                ),
            ),
        ) shouldBe emptyList()
        storage.epochKeys() shouldBe setOf(oldEpochKey, successorEpochKey)

        val fullReplayOracle = ChapterJournalReplayReducer.replay(
            epochs = listOf(oldEpochWithLateAppend, successorEpoch),
            artifactResolver = semanticResolver(),
            json = json,
        )
        fullReplayOracle.pages.getValue("page.jpg").blocks.single().translation shouldBe "successor epoch winner"

        val completeFrontier = listOf(
            coverage(4L, 21L, acked = 3L, closed = true),
            coverage(5L, 22L, acked = 2L, closed = false),
        )
        val compacted = manager.writeSnapshot(
            frontier = completeFrontier,
            pages = fullReplayOracle.pages,
            gcMode = ChapterJournalGcMode.SHADOW_DORMANT,
        )
        compacted.state shouldBe ChapterJournalSnapshotState.DURABLE
        compacted.frontier shouldBe completeFrontier
        val selectedAtHighestGeneration = checkNotNull(manager.newestUsableCandidate())
        selectedAtHighestGeneration.generation shouldBe compacted.generation
        snapshotPayload(storage, selectedAtHighestGeneration).pages shouldBe fullReplayOracle.pages

        // Equal usable vectors resolve deterministically to the highest generation.
        storage.installSnapshot(snapshotPayload(storage, compacted).copy(generation = compacted.generation + 1L))
        checkNotNull(manager.newestUsableCandidate()).generation shouldBe compacted.generation + 1L

        // A torn successor is WRITING and selection falls back to the latest durable candidate.
        storage.tearSnapshotOnWrite = true
        assertThrows(IOException::class.java) {
            manager.writeSnapshot(
                frontier = completeFrontier + coverage(6L, 23L, acked = 0L, closed = false),
                pages = fullReplayOracle.pages,
            )
        }
        storage.tearSnapshotOnWrite = false
        manager.newestUsableCandidate()?.generation shouldBe compacted.generation + 1L

        // E16b production replay consumes epochs only. Snapshot presence, corruption, or deletion
        // cannot seed, mask, or change the full-prefix replay result.
        val replayWithSnapshotsPresent = ChapterJournalReplayReducer.replay(
            epochs = listOf(oldEpochWithLateAppend, successorEpoch),
            artifactResolver = semanticResolver(),
            json = json,
        )
        replayWithSnapshotsPresent shouldBe fullReplayOracle
        storage.corruptSnapshot(compacted.generation + 1L)
        val replayWithCorruptSnapshot = ChapterJournalReplayReducer.replay(
            epochs = listOf(oldEpochWithLateAppend, successorEpoch),
            artifactResolver = semanticResolver(),
            json = json,
        )
        replayWithCorruptSnapshot shouldBe fullReplayOracle
        manager.newestUsableCandidate()?.generation shouldBe compacted.generation
        storage.snapshotGenerations().toList().forEach(storage::deleteSnapshot)
        val replayWithSnapshotsDeleted = ChapterJournalReplayReducer.replay(
            epochs = listOf(oldEpochWithLateAppend, successorEpoch),
            artifactResolver = semanticResolver(),
            json = json,
        )
        replayWithSnapshotsDeleted shouldBe fullReplayOracle
    }

    @Test
    fun `A6 rekey and schema v2 clean image records require snapshot coverage before epoch gc`() {
        val json = Json {
            encodeDefaults = true
            explicitNulls = true
            ignoreUnknownKeys = true
        }
        val oldKey = "source-page.jpg"
        val newKey = "rekeyed-page.jpg"
        val cleanedImageName = "page.cleaned.content-addressed.jpg"
        val cleanedImageHash = StageFingerprints.sha256Hex("cleaned-v2-image".encodeToByteArray())
        val oldState = replayPage(oldKey, "pre-rekey state", pageVersion = 4L)
        val newState = PageTranslation(
            sourceFileName = newKey,
            pageVersion = 5L,
            cleanedImageName = cleanedImageName,
            cleanedImageContentHash = cleanedImageHash,
            blocks = mutableListOf(
                TranslationBlock(
                    text = "source",
                    translation = "post-rekey state",
                    width = 10f,
                    height = 10f,
                    x = 0f,
                    y = 0f,
                    symHeight = 1f,
                    symWidth = 1f,
                    angle = 0f,
                ),
            ),
        ).toPublishedPage()
        val oldMutation = ChapterJournalRecord(
            schemaVersion = ChapterJournalRecord.LEGACY_SCHEMA_VERSION,
            pageKey = oldKey,
            generation = 0L,
            fencingToken = 0L,
            pageVersion = oldState.pageVersion,
            state = oldState,
            artifactContentHash = StageFingerprints.pageSnapshot(oldState),
        )
        val oldTombstone = ChapterJournalRecord(
            schemaVersion = ChapterJournalRecord.SCHEMA_VERSION,
            pageKey = oldKey,
            generation = 0L,
            fencingToken = 1L,
            pageVersion = oldState.pageVersion,
            state = null,
        )
        val newMutation = ChapterJournalRecord(
            schemaVersion = ChapterJournalRecord.SCHEMA_VERSION,
            pageKey = newKey,
            generation = 0L,
            fencingToken = 1L,
            pageVersion = newState.pageVersion,
            state = newState,
            cleanedImageName = cleanedImageName,
            cleanedImageContentHash = cleanedImageHash,
            artifactContentHash = StageFingerprints.pageSnapshot(newState),
        )
        val rekey = ChapterJournalBulkRecord(
            operation = "rekey_pages",
            mapping = mapOf(oldKey to newKey),
            mutations = listOf(oldTombstone, newMutation),
        )
        val generation = 8L
        val epochOrdinal = 31L
        val sessionId = UUID(0L, 31L)
        val epochOrder = ChapterJournalFormat.EpochOrderKey(generation, epochOrdinal, sessionId)
        val firstInventory = ChapterJournalInventoryRecord(
            chapterIdentityHash = "test-chapter-identity",
            expectedPageKeys = listOf(oldKey),
            expectedPageCount = 1,
            sourceFingerprint = StageFingerprints.canonicalFingerprint(listOf(oldKey)),
        )
        val rekeyInventory = ChapterJournalInventoryRecord(
            chapterIdentityHash = "test-chapter-identity",
            expectedPageKeys = listOf(newKey),
            expectedPageCount = 1,
            sourceFingerprint = StageFingerprints.canonicalFingerprint(listOf(newKey)),
        )
        fun frame(frameSeq: Long, commitSeq: Long?, kind: ChapterJournalFormat.RecordKind, payload: ByteArray) =
            ChapterJournalFormat.encodeFrame(frameSeq, commitSeq, kind, payload)

        val segmentHeader = ChapterJournalFormat.segmentHeader(0L, generation, epochOrdinal, sessionId)
        val initialInventoryFrame = frame(
            frameSeq = 1L,
            commitSeq = null,
            kind = ChapterJournalFormat.RecordKind.INVENTORY,
            payload = json.encodeToString(firstInventory).encodeToByteArray(),
        )
        val oldStateFrame = frame(
            frameSeq = 2L,
            commitSeq = 1L,
            kind = ChapterJournalFormat.RecordKind.FREE_STATE,
            payload = json.encodeToString(oldMutation).encodeToByteArray(),
        )
        val rekeyInventoryFrame = frame(
            frameSeq = 3L,
            commitSeq = null,
            kind = ChapterJournalFormat.RecordKind.INVENTORY,
            payload = json.encodeToString(rekeyInventory).encodeToByteArray(),
        )
        val bulkRekeyFrame = frame(
            frameSeq = 4L,
            commitSeq = 2L,
            kind = ChapterJournalFormat.RecordKind.BULK_REKEY,
            payload = json.encodeToString(rekey).encodeToByteArray(),
        )
        val prefixBytes = segmentHeader + initialInventoryFrame + oldStateFrame
        val epochKey = ChapterJournalEpochKey(generation, epochOrdinal)
        val storage = MemorySnapshotStorage().apply { epochDirectories += epochKey }
        val manager = ChapterJournalSnapshotManager(storage)
        val beforeRekeySnapshot = manager.writeSnapshot(
            frontier = listOf(coverage(generation, epochOrdinal, acked = 2L, closed = false)),
            pages = mapOf(oldKey to oldState),
        )

        val completeEpoch = ChapterJournalReplayEpoch(
            order = epochOrder,
            segments = listOf(
                ChapterJournalReplaySegment(
                    index = 0L,
                    bytes = prefixBytes + rekeyInventoryFrame + bulkRekeyFrame,
                ),
            ),
        )
        val scanned = ChapterJournalFormat.scanSegment(
            bytes = completeEpoch.segments.single().bytes,
            expectedSegmentIndex = 0L,
            expectedGeneration = generation,
            expectedEpochOrdinal = epochOrdinal,
            expectedSessionId = sessionId,
            firstExpectedFrameSeq = 1L,
            firstExpectedCommitSeq = 1L,
        )
        scanned.stoppedAtInvalidFrame shouldBe false
        scanned.frames.map { it.kind } shouldContainExactly listOf(
            ChapterJournalFormat.RecordKind.INVENTORY,
            ChapterJournalFormat.RecordKind.FREE_STATE,
            ChapterJournalFormat.RecordKind.INVENTORY,
            ChapterJournalFormat.RecordKind.BULK_REKEY,
        )
        scanned.nextFrameSeq shouldBe 5L
        scanned.nextCommitSeq shouldBe 3L
        val decodedBulk = json.decodeFromString<ChapterJournalBulkRecord>(
            bulkRekeyFrame.copyOfRange(
                ChapterJournalFormat.FRAME_HEADER_BYTES,
                bulkRekeyFrame.size - ChapterJournalFormat.FRAME_TRAILER_BYTES,
            ).decodeToString(),
        )
        decodedBulk.schemaVersion shouldBe ChapterJournalBulkRecord.SCHEMA_VERSION
        decodedBulk.mapping shouldBe mapOf(oldKey to newKey)
        decodedBulk.mutations.single { it.pageKey == newKey }.apply {
            schemaVersion shouldBe ChapterJournalRecord.SCHEMA_VERSION
            this.cleanedImageName shouldBe cleanedImageName
            this.cleanedImageContentHash shouldBe cleanedImageHash
        }

        val replayed = ChapterJournalReplayReducer.replay(
            epochs = listOf(completeEpoch),
            artifactResolver = ChapterJournalArtifactIdentityResolver { key, hash, state ->
                StageFingerprints.pageSnapshot(state) == hash &&
                    when (key) {
                        oldKey -> state.cleanedImageName == null && state.cleanedImageContentHash == null
                        newKey ->
                            state.cleanedImageName == cleanedImageName &&
                                state.cleanedImageContentHash == cleanedImageHash
                        else -> false
                    }
            },
            json = json,
        )
        replayed.pages.keys shouldBe setOf(newKey)
        replayed.expectedPageKeys shouldBe setOf(newKey)
        replayed.expectedPageCount shouldBe 1
        replayed.invalidPageKeys shouldBe emptySet()
        replayed.missingPageKeys shouldBe emptySet()
        replayed.pages.getValue(newKey).cleanedImageName shouldBe cleanedImageName
        replayed.pages.getValue(newKey).cleanedImageContentHash shouldBe cleanedImageHash

        // The v2 bulk frame at frameSeq 4 is beyond the first snapshot's ACKed frontier (2),
        // so E18 GC must retain it even though the writer is now closed and its coverage was scanned.
        manager.executeAuthorizedGc(
            durableSnapshot = beforeRekeySnapshot,
            epochStates = listOf(
                ChapterJournalEpochGcState(
                    key = epochKey,
                    ackedFrameSeq = scanned.nextFrameSeq - 1L,
                    terminallyClosed = true,
                    coverageVerified = true,
                ),
            ),
        ) shouldBe emptyList()
        storage.epochKeys() shouldBe setOf(epochKey)

        // Once a durable snapshot contains the rekey winner and its identity envelope, full v2
        // coverage can authorize GC; selection must return that exact oracle-equivalent snapshot.
        val covered = coverage(generation, epochOrdinal, acked = scanned.nextFrameSeq - 1L, closed = true)
        val compacted = manager.writeSnapshot(
            frontier = listOf(covered),
            pages = replayed.pages,
            epochStatesAtGc = listOf(
                ChapterJournalEpochGcState(
                    key = epochKey,
                    ackedFrameSeq = scanned.nextFrameSeq - 1L,
                    terminallyClosed = true,
                    coverageVerified = true,
                ),
            ),
            gcMode = ChapterJournalGcMode.AUTHORITATIVE,
        )
        compacted.state shouldBe ChapterJournalSnapshotState.DURABLE
        checkNotNull(manager.newestUsableCandidate()).generation shouldBe compacted.generation
        snapshotPayload(storage, compacted).pages shouldBe replayed.pages
        storage.deletedEpochs shouldBe listOf(epochKey)
        storage.epochKeys() shouldBe emptySet()
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

    private fun replayPage(key: String, translation: String, pageVersion: Long) = PageTranslation(
        sourceFileName = key,
        pageVersion = pageVersion,
        blocks = mutableListOf(
            TranslationBlock(
                text = "source",
                translation = translation,
                width = 10f,
                height = 10f,
                x = 0f,
                y = 0f,
                symHeight = 1f,
                symWidth = 1f,
                angle = 0f,
            ),
        ),
    ).toPublishedPage()

    private fun replayEpoch(
        order: ChapterJournalFormat.EpochOrderKey,
        expectedKeys: List<String>,
        records: List<ChapterJournalRecord>,
        json: Json,
    ): ChapterJournalReplayEpoch {
        val bytes = buildList {
            add(ChapterJournalFormat.segmentHeader(0L, order.storeGeneration, order.epochOrdinal, order.sessionId))
            add(
                ChapterJournalFormat.encodeFrame(
                    frameSeq = 1L,
                    commitSeq = null,
                    kind = ChapterJournalFormat.RecordKind.INVENTORY,
                    payload = json.encodeToString(
                        ChapterJournalInventoryRecord(
                            chapterIdentityHash = "test-chapter-identity",
                            expectedPageKeys = expectedKeys,
                            expectedPageCount = expectedKeys.size,
                            sourceFingerprint = StageFingerprints.canonicalFingerprint(expectedKeys),
                        ),
                    ).encodeToByteArray(),
                ),
            )
            records.forEachIndexed { index, record ->
                add(
                    ChapterJournalFormat.encodeFrame(
                        frameSeq = index + 2L,
                        commitSeq = index + 1L,
                        kind = ChapterJournalFormat.RecordKind.FREE_STATE,
                        payload = json.encodeToString(record).encodeToByteArray(),
                    ),
                )
            }
        }.fold(byteArrayOf()) { result, frame -> result + frame }
        return ChapterJournalReplayEpoch(order, listOf(ChapterJournalReplaySegment(0L, bytes)))
    }

    private fun appendRecord(
        epoch: ChapterJournalReplayEpoch,
        record: ChapterJournalRecord,
        commitSeq: Long,
        json: Json,
    ): ChapterJournalReplayEpoch {
        val segment = epoch.segments.single()
        val appended = ChapterJournalFormat.encodeFrame(
            frameSeq = 3L,
            commitSeq = commitSeq,
            kind = ChapterJournalFormat.RecordKind.FREE_STATE,
            payload = json.encodeToString(record).encodeToByteArray(),
        )
        return epoch.copy(segments = listOf(segment.copy(bytes = segment.bytes + appended)))
    }

    private fun semanticResolver() = ChapterJournalArtifactIdentityResolver { _, hash, state ->
        StageFingerprints.pageSnapshot(state) == hash
    }

    private fun snapshotPayload(
        storage: MemorySnapshotStorage,
        candidate: ChapterJournalSnapshotCandidate,
    ): ChapterJournalSnapshotPayload = checkNotNull(
        ChapterJournalSnapshotFormat.decode(checkNotNull(storage.readSnapshot(candidate.generation))),
    ).payload

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

        fun installSnapshot(payload: ChapterJournalSnapshotPayload) {
            val bytes = ChapterJournalSnapshotFormat.encode(payload)
            val decoded = checkNotNull(ChapterJournalSnapshotFormat.decode(bytes))
            snapshots[payload.generation] = bytes
            markers[payload.generation] = ChapterJournalSnapshotFormat.encodeDurableMarker(
                payload.generation,
                decoded.payloadCrc,
            )
        }

        fun corruptSnapshot(generation: Long) {
            val bytes = snapshots[generation] ?: return
            snapshots[generation] = bytes.copyOf().also { corrupted ->
                corrupted[corrupted.lastIndex - 8] = (corrupted[corrupted.lastIndex - 8].toInt() xor 1).toByte()
            }
        }

        private fun event(value: String, recorded: String = value) {
            events += recorded
            if (failOn == value) throw IOException("injected failure at $value")
        }
    }
}
