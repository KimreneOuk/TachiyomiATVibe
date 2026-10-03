package eu.kanade.translation.persistence.journal

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.PublishedPageTranslation
import eu.kanade.translation.model.toPublishedPage
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.TreeMap
import java.util.UUID

class ChapterJournalWriterTest {

    @Test
    fun `regular work cannot consume the reserved foreground credit`() = runTest {
        val storage = MemoryStorage()
        val writer = writer(
            storage = storage,
            dispatcher = StandardTestDispatcher(testScheduler),
            regularCreditLimit = 2,
            foregroundCreditLimit = 1,
        )
        try {
            val regular = listOfNotNull(
                writer.tryAcquireShadowCredit(foreground = false),
                writer.tryAcquireShadowCredit(foreground = false),
            )
            writer.tryAcquireShadowCredit(foreground = false) shouldBe null
            val foreground = writer.tryAcquireShadowCredit(foreground = true)
            foreground?.releaseIfUnqueued() shouldBe true
            regular.forEach { it.releaseIfUnqueued() shouldBe true }
        } finally {
            writer.drainAndClose()
        }
    }

    @Test
    fun `frames tolerate short writes by completing every byte`() = runTest {
        val storage = MemoryStorage(maxWriteBytes = 3)
        val writer = writer(storage, StandardTestDispatcher(testScheduler))
        try {
            val credit = writer.tryAcquireShadowCredit(foreground = false)!!
            val sequence = writer.nextCommitSeq()
            writer.captureLegacyPersisted(sequence, credit, "page-a", 2L, 9L, page())
            runCurrent()

            writer.ackedHighWaterSeq shouldBe 0L
            scan(storage).frames.mapNotNull { it.commitSeq } shouldContainExactly listOf(1L)
            writer.inFlightCount shouldBe 0
            advanceTimeBy(ChapterJournalWriter.DEFAULT_FREE_DURABILITY_INTERVAL_MS)
            runCurrent()
            writer.ackedHighWaterSeq shouldBe 2L
        } finally {
            writer.drainAndClose()
        }
    }

    @Test
    fun `capture detaches page state before delayed writer serialization`() = runTest {
        val storage = MemoryStorage()
        val encodedRevisions = mutableListOf<Int?>()
        val writer = writer(
            storage = storage,
            dispatcher = StandardTestDispatcher(testScheduler),
            encodeRecord = { record ->
                encodedRevisions += record.state?.inpaintRevision
                (record.state?.inpaintRevision?.toString() ?: "tombstone").encodeToByteArray()
            },
        )
        try {
            val source = draftPage().apply { inpaintRevision = 7 }
            val published = source.toPublishedPage()
            val credit = writer.tryAcquireShadowCredit(foreground = false)!!
            writer.captureLegacyPersisted(writer.nextCommitSeq(), credit, "page-a", 1L, 1L, published)

            // Mutate the pipeline draft after the writer accepted the immutable publication.
            // The historical record must keep revision 7 without a deferred defensive copy.
            source.inpaintRevision = 8
            runCurrent()

            encodedRevisions shouldContainExactly listOf(7)
            scan(storage).frames.last().payload.decodeToString() shouldBe "7"
        } finally {
            writer.drainAndClose()
        }
    }

    @Test
    fun `bulk replace and rekey each use one frame and one legacy sequence`() = runTest {
        val storage = MemoryStorage()
        val encodedBulk = mutableListOf<ChapterJournalBulkRecord>()
        val writer = writer(
            storage = storage,
            dispatcher = StandardTestDispatcher(testScheduler),
            encodeBulkRecord = { record ->
                encodedBulk += record
                "${record.operation}:${record.mutations.size}".encodeToByteArray()
            },
        )
        try {
            val replaceKeys = (1..12).map { "page-$it" }
            val replaceCredit = writer.tryAcquireShadowCredit(foreground = false)!!
            writer.captureLegacyBulkMutation(
                commitSeq = writer.nextCommitSeq(),
                credit = replaceCredit,
                kind = ChapterJournalFormat.RecordKind.BULK_REPLACE,
                record = ChapterJournalBulkRecord(
                    operation = "replace_all",
                    mapping = replaceKeys.associateWith { it },
                    mutations = replaceKeys.mapIndexed { index, key ->
                        ChapterJournalRecord(
                            pageKey = key,
                            generation = 1L,
                            fencingToken = 0L,
                            pageVersion = index.toLong() + 1L,
                            state = page(key),
                        )
                    },
                ),
            )

            val rekeyMapping = replaceKeys.associateWith { "disk-$it" }
            val rekeyCredit = writer.tryAcquireShadowCredit(foreground = false)!!
            writer.captureLegacyBulkMutation(
                commitSeq = writer.nextCommitSeq(),
                credit = rekeyCredit,
                kind = ChapterJournalFormat.RecordKind.BULK_REKEY,
                record = ChapterJournalBulkRecord(
                    operation = "rekey_pages",
                    mapping = rekeyMapping,
                    mutations = replaceKeys.flatMapIndexed { index, oldKey ->
                        val newKey = rekeyMapping.getValue(oldKey)
                        listOf(
                            ChapterJournalRecord(
                                pageKey = oldKey,
                                generation = 1L,
                                fencingToken = 0L,
                                pageVersion = index.toLong() + 1L,
                                state = null,
                            ),
                            ChapterJournalRecord(
                                pageKey = newKey,
                                generation = 1L,
                                fencingToken = 0L,
                                pageVersion = index.toLong() + 2L,
                                state = page(newKey),
                            ),
                        )
                    },
                ),
            )
            runCurrent()

            val frames = scan(storage).frames
            frames.map { it.kind } shouldContainExactly listOf(
                ChapterJournalFormat.RecordKind.INVENTORY,
                ChapterJournalFormat.RecordKind.BULK_REPLACE,
                ChapterJournalFormat.RecordKind.BULK_REKEY,
            )
            frames.map { it.commitSeq } shouldContainExactly listOf(null, 1L, 2L)
            encodedBulk.map { it.mutations.size } shouldContainExactly listOf(12, 24)
            encodedBulk.first().mapping.size shouldBe 12
            encodedBulk.last().mapping shouldBe rekeyMapping
            writer.shadowLaggedCount shouldBe 0L
            writer.inFlightCount shouldBe 0
        } finally {
            writer.drainAndClose()
        }
    }

    @Test
    fun `oversize bulk payload becomes a terminal payload record`() = runTest {
        val storage = MemoryStorage()
        var terminalDiagnostic: String? = null
        val writer = writer(
            storage = storage,
            dispatcher = StandardTestDispatcher(testScheduler),
            encodeBulkRecord = { ByteArray(ChapterJournalFormat.MAX_PAYLOAD_BYTES + 1) },
            encodeTerminalPayload = { pageKey, bytes, seq ->
                terminalDiagnostic = "$pageKey:$bytes:$seq"
                "terminal_payload:$pageKey:$bytes:$seq".encodeToByteArray()
            },
        )
        try {
            val credit = writer.tryAcquireShadowCredit(foreground = false)!!
            writer.captureLegacyBulkMutation(
                commitSeq = writer.nextCommitSeq(),
                credit = credit,
                kind = ChapterJournalFormat.RecordKind.BULK_REPLACE,
                record = ChapterJournalBulkRecord(
                    operation = "replace_all",
                    mapping = emptyMap(),
                    mutations = emptyList(),
                ),
            )
            runCurrent()

            scan(storage).frames.map { it.kind } shouldContainExactly listOf(
                ChapterJournalFormat.RecordKind.INVENTORY,
                ChapterJournalFormat.RecordKind.TERMINAL_PAYLOAD,
            )
            terminalDiagnostic shouldBe "<replace_all>:${ChapterJournalFormat.MAX_PAYLOAD_BYTES + 1}:1"
            writer.terminalPayloadCount shouldBe 1L
            writer.shadowLaggedCount shouldBe 0L
        } finally {
            writer.drainAndClose()
        }
    }

    @Test
    fun `lost ending records have a counter separate from credit starvation`() = runTest {
        val storage = MemoryStorage(failOnSync = 1)
        val writer = writer(storage, StandardTestDispatcher(testScheduler))
        val credit = writer.tryAcquireShadowCredit(foreground = false)!!
        writer.captureLegacyPersisted(
            commitSeq = writer.nextCommitSeq(),
            credit = credit,
            pageKey = "paid-page",
            generation = 1L,
            fencingToken = 0L,
            page = page(),
            paid = true,
        )
        runCurrent()
        writer.requestDefunctMarker()

        writer.writerFailureCount shouldBe 1L
        writer.lostTerminalRecordCount shouldBe 1L
        writer.shadowLaggedCount shouldBe 0L
        writer.drainAndClose()
    }

    @Test
    fun `channel capacity must reserve every outstanding credit and control command`() {
        assertThrows(IllegalArgumentException::class.java) {
            writer(
                storage = MemoryStorage(),
                dispatcher = StandardTestDispatcher(kotlinx.coroutines.test.TestCoroutineScheduler()),
                regularCreditLimit = 2,
                foregroundCreditLimit = 1,
                channelCapacity = 6,
            )
        }
    }

    @Test
    fun `registration inventory is the first durable frame without consuming commit sequence`() = runTest {
        val storage = MemoryStorage()
        val writer = writer(storage, StandardTestDispatcher(testScheduler))
        try {
            writer.captureInventory(
                ChapterJournalInventorySnapshot(
                    expectedPageKeys = setOf("page-a", "page-b"),
                    expectedPageCount = 2,
                    sourceShaByPageKey = mapOf("page-a" to "sha-a", "page-b" to "sha-b"),
                ),
            )
            runCurrent()

            val beforeSync = scan(storage)
            beforeSync.frames.map { it.kind } shouldContainExactly listOf(ChapterJournalFormat.RecordKind.INVENTORY)
            beforeSync.frames.single().commitSeq shouldBe null
            writer.ackedHighWaterSeq shouldBe 0L

            advanceTimeBy(ChapterJournalWriter.DEFAULT_FREE_DURABILITY_INTERVAL_MS)
            runCurrent()
            writer.ackedHighWaterSeq shouldBe 1L
            scan(storage).terminalLagSeen shouldBe false
        } finally {
            writer.drainAndClose()
        }
    }

    @Test
    fun `free sync timer does not split a deferred inventory batch`() = runTest {
        val storage = MemoryStorage()
        val writer = writer(
            storage = storage,
            dispatcher = StandardTestDispatcher(testScheduler),
            durabilityIntervalMs = 10L,
        )
        try {
            writer.captureInventory(
                ChapterJournalInventorySnapshot(
                    expectedPageKeys = setOf("page-a"),
                    expectedPageCount = 1,
                    sourceShaByPageKey = mapOf("page-a" to "sha-a"),
                ),
            )
            runCurrent()
            val syncCountAfterInitialization = storage.syncCount

            writer.captureInventory(
                ChapterJournalInventorySnapshot(
                    expectedPageKeys = setOf("page-a", "page-b"),
                    expectedPageCount = 2,
                    sourceShaByPageKey = mapOf("page-a" to "sha-a", "page-b" to "sha-b"),
                ),
                deferSyncUntilBarrier = true,
            )
            writer.captureInventory(
                ChapterJournalInventorySnapshot(
                    expectedPageKeys = setOf("page-a", "page-b", "page-c"),
                    expectedPageCount = 3,
                    sourceShaByPageKey = mapOf("page-a" to "sha-a", "page-b" to "sha-b", "page-c" to "sha-c"),
                ),
            )
            runCurrent()

            advanceTimeBy(10L)
            runCurrent()
            storage.syncCount shouldBe syncCountAfterInitialization

            writer.flushToCaptureBarrier()
            storage.syncCount shouldBe syncCountAfterInitialization + 1
        } finally {
            writer.drainAndClose()
        }
    }

    @Test
    fun `torn tail is truncated before the next contiguous append`() = runTest {
        val storage = MemoryStorage()
        val first = writer(storage, StandardTestDispatcher(testScheduler))
        val firstCredit = first.tryAcquireShadowCredit(foreground = false)!!
        first.captureLegacyPersisted(first.nextCommitSeq(), firstCredit, "page-a", 1L, 1L, page())
        runCurrent()
        first.drainAndClose()

        storage.appendRaw(0L, byteArrayOf(0, 0, 0, 12, 0, 0))
        val reopened = writer(storage, StandardTestDispatcher(testScheduler))
        try {
            val credit = reopened.tryAcquireShadowCredit(foreground = false)!!
            // Recovery resumes this epoch after the scanner establishes the next durable
            // commit position. Production store sessions allocate a fresh epoch instead.
            val sequence = 2L
            reopened.captureLegacyPersisted(sequence, credit, "page-b", 1L, 2L, page())
            runCurrent()

            val frames = scan(storage).frames
            frames.mapNotNull { it.commitSeq } shouldContainExactly listOf(1L, 2L)
            frames.map { it.kind } shouldContainExactly listOf(
                ChapterJournalFormat.RecordKind.INVENTORY,
                ChapterJournalFormat.RecordKind.FREE_STATE,
                ChapterJournalFormat.RecordKind.INVENTORY,
                ChapterJournalFormat.RecordKind.FREE_STATE,
            )
        } finally {
            reopened.drainAndClose()
        }
    }

    @Test
    fun `duplicate append halts the writer and releases both credits`() = runTest {
        val storage = MemoryStorage()
        val writer = writer(storage, StandardTestDispatcher(testScheduler))
        try {
            val firstCredit = writer.tryAcquireShadowCredit(foreground = false)!!
            val sequence = writer.nextCommitSeq()
            writer.captureLegacyPersisted(sequence, firstCredit, "page-a", 1L, 1L, page())

            val duplicateCredit = writer.tryAcquireShadowCredit(foreground = false)!!
            writer.captureLegacyPersisted(sequence, duplicateCredit, "page-a", 1L, 1L, page())
            runCurrent()

            writer.ackedHighWaterSeq shouldBe 0L
            writer.writerFailureCount shouldBe 1L
            writer.inFlightCount shouldBe 0
            scan(storage).frames.mapNotNull { it.commitSeq } shouldContainExactly listOf(1L)
        } finally {
            writer.drainAndClose()
        }
    }

    @Test
    fun `offers racing writer failure are rejected counted and release every credit`() = runTest {
        val storage = MemoryStorage()
        val writer = writer(storage, StandardTestDispatcher(testScheduler))
        try {
            val first = writer.tryAcquireShadowCredit(foreground = false)!!
            val firstSeq = writer.nextCommitSeq()
            writer.captureLegacyPersisted(firstSeq, first, "page-a", 1L, 1L, page())

            val duplicate = writer.tryAcquireShadowCredit(foreground = false)!!
            writer.captureLegacyPersisted(firstSeq, duplicate, "duplicate", 1L, 2L, page())
            val heldAcrossFailure = writer.tryAcquireShadowCredit(foreground = false)!!
            val retainedAcrossFailure = writer.tryAcquireShadowCredit(foreground = false)!!
            retainedAcrossFailure.retain() shouldBe true

            runCurrent()
            writer.writerFailureCount shouldBe 1L
            writer.ackedHighWaterSeq shouldBe 0L
            // The writer also resolves permits retained outside its command queue.
            writer.inFlightCount shouldBe 0

            // This credit was reserved before the writer failed, then offered after
            // the failure transition. It must be rejected and resolved, never crash.
            writer.captureLegacyPersisted(2L, heldAcrossFailure, "page-after-failure", 1L, 3L, page())
            writer.offersRejectedAfterTerminalCount shouldBe 1L
            writer.inFlightCount shouldBe 0
            writer.isWriterStopped shouldBe true
        } finally {
            writer.drainAndClose()
        }
    }

    @Test
    fun `oversize page payload ends capture with a terminal diagnostic record`() = runTest {
        val storage = MemoryStorage()
        val writer = writer(
            storage = storage,
            dispatcher = StandardTestDispatcher(testScheduler),
            encodeRecord = { ByteArray(ChapterJournalFormat.MAX_PAYLOAD_BYTES + 1) },
            encodeTerminalPayload = { pageKey, bytes, sequence ->
                "terminal_payload:$pageKey:$bytes:$sequence".encodeToByteArray()
            },
        )
        try {
            val credit = writer.tryAcquireShadowCredit(foreground = false)!!
            writer.captureLegacyPersisted(writer.nextCommitSeq(), credit, "page-oversize", 1L, 1L, page())
            runCurrent()

            val scanned = scan(storage)
            scanned.frames.map { it.kind } shouldContainExactly listOf(
                ChapterJournalFormat.RecordKind.INVENTORY,
                ChapterJournalFormat.RecordKind.TERMINAL_PAYLOAD,
            )
            scanned.frames.last().payload.decodeToString() shouldBe
                "terminal_payload:page-oversize:${ChapterJournalFormat.MAX_PAYLOAD_BYTES + 1}:1"
            scanned.terminalPayloadSeen shouldBe true
            scanned.stoppedAtInvalidFrame shouldBe false
            writer.terminalPayloadCount shouldBe 1L
            writer.writerFailureCount shouldBe 0L
            writer.inFlightCount shouldBe 0
            writer.ackedHighWaterSeq shouldBe 2L
            writer.isWriterStopped shouldBe true
        } finally {
            writer.drainAndClose()
        }
    }

    @Test
    fun `rollover sync failure halts before a new segment is created`() = runTest {
        val storage = MemoryStorage(failOnSync = 2)
        val writer = writer(
            storage = storage,
            dispatcher = StandardTestDispatcher(testScheduler),
            // Keep INVENTORY + first state in one segment; the second state must roll over.
            segmentByteLimit = 200L,
        )
        try {
            val first = writer.tryAcquireShadowCredit(foreground = false)!!
            writer.captureLegacyPersisted(writer.nextCommitSeq(), first, "page-a", 1L, 1L, page())
            runCurrent()
            val second = writer.tryAcquireShadowCredit(foreground = false)!!
            writer.captureLegacyPersisted(writer.nextCommitSeq(), second, "page-b", 1L, 2L, page())
            runCurrent()

            writer.writerFailureCount shouldBe 1L
            storage.segmentIndexes() shouldContainExactly listOf(0L)
            writer.inFlightCount shouldBe 0
        } finally {
            writer.drainAndClose()
        }
    }

    @Test
    fun `rollover syncs only the new segment directory after epoch tree creation`() = runTest {
        val durableRoot = Files.createTempDirectory("chapter-journal-directory-sync").toFile()
        val epochDirectory = File(durableRoot, "journal/chapter/epoch-00000000000000000001-g1")
        val syncedDirectories = mutableListOf<Path>()
        val storage = FileChapterJournalStorage(
            directory = epochDirectory,
            durableRoot = durableRoot,
            syncDirectoryEntry = { path ->
                syncedDirectories.add(path.toPath().toAbsolutePath().normalize())
            },
        )
        val writer = ChapterJournalWriter(
            storage = storage,
            dispatcher = StandardTestDispatcher(testScheduler),
            encodeRecord = { "${it.pageKey}:${it.pageVersion}".encodeToByteArray() },
            encodeInventory = { "inventory:${it.sourceFingerprint}".encodeToByteArray() },
            encodeTerminalLag = { count, sequence -> "lag:$count:$sequence".encodeToByteArray() },
            segmentByteLimit = 200L,
        )
        try {
            val first = writer.tryAcquireShadowCredit(foreground = false)!!
            writer.captureLegacyPersisted(writer.nextCommitSeq(), first, "page-a", 1L, 1L, page())
            runCurrent()

            val second = writer.tryAcquireShadowCredit(foreground = false)!!
            writer.captureLegacyPersisted(writer.nextCommitSeq(), second, "page-b", 1L, 2L, page())
            runCurrent()

            storage.segmentIndexes() shouldContainExactly listOf(0L, 1L)
            val epochPath = epochDirectory.toPath().toAbsolutePath().normalize()
            val chapterPath = epochDirectory.parentFile.toPath().toAbsolutePath().normalize()
            val journalPath = epochDirectory.parentFile.parentFile.toPath().toAbsolutePath().normalize()
            val durableRootPath = durableRoot.toPath().toAbsolutePath().normalize()
            syncedDirectories shouldContainExactly listOf(
                epochPath,
                chapterPath,
                journalPath,
                durableRootPath,
                epochPath,
            )
        } finally {
            writer.drainAndClose()
            durableRoot.deleteRecursively()
        }
    }

    @Test
    fun `rollover keeps durable acknowledgement monotonic across free work`() = runTest {
        val storage = MemoryStorage()
        val writer = writer(
            storage = storage,
            dispatcher = StandardTestDispatcher(testScheduler),
            segmentByteLimit = 64L,
        )
        try {
            val paid = writer.tryAcquireShadowCredit(foreground = false)!!
            writer.captureLegacyPersisted(writer.nextCommitSeq(), paid, "page-a", 1L, 1L, page(), paid = true)
            runCurrent()
            writer.ackedHighWaterSeq shouldBe 2L

            val free = writer.tryAcquireShadowCredit(foreground = false)!!
            writer.captureLegacyPersisted(writer.nextCommitSeq(), free, "page-b", 1L, 2L, page())
            runCurrent()
            writer.ackedHighWaterSeq shouldBe 2L

            advanceTimeBy(ChapterJournalWriter.DEFAULT_FREE_DURABILITY_INTERVAL_MS)
            runCurrent()
            writer.ackedHighWaterSeq shouldBe 3L
        } finally {
            writer.drainAndClose()
        }
    }

    @Test
    fun `free work syncs no later than the configured deadline`() = runTest {
        val storage = MemoryStorage()
        val writer = writer(
            storage = storage,
            dispatcher = StandardTestDispatcher(testScheduler),
            durabilityIntervalMs = 1_000L,
        )
        try {
            val credit = writer.tryAcquireShadowCredit(foreground = false)!!
            writer.captureLegacyPersisted(writer.nextCommitSeq(), credit, "page-a", 1L, 1L, page())
            runCurrent()
            storage.syncCount shouldBe 1 // New segment header durability.

            advanceTimeBy(999L)
            runCurrent()
            storage.syncCount shouldBe 1

            advanceTimeBy(1L)
            runCurrent()
            storage.syncCount shouldBe 2
            writer.ackedHighWaterSeq shouldBe 2L
        } finally {
            writer.drainAndClose()
        }
    }

    @Test
    fun `capture barrier syncs accepted prefix without consuming parked credits`() = runTest {
        val storage = MemoryStorage()
        val writer = writer(
            storage = storage,
            dispatcher = StandardTestDispatcher(testScheduler),
            regularCreditLimit = 2,
        )
        try {
            val accepted = writer.tryAcquireShadowCredit(foreground = false)!!
            writer.captureLegacyPersisted(
                writer.nextCommitSeq(),
                accepted,
                "accepted-page",
                1L,
                1L,
                page("accepted-page"),
            )
            val parked = writer.tryAcquireShadowCredit(foreground = false)!!

            val coveredFrameSeq = writer.flushToCaptureBarrier()

            coveredFrameSeq shouldBe 2L // INVENTORY plus the accepted page frame.
            writer.ackedHighWaterSeq shouldBe 2L
            writer.inFlightCount shouldBe 1
            parked.releaseIfUnqueued() shouldBe true
        } finally {
            writer.drainAndClose()
        }
    }

    @Test
    fun `deferred flush forces rolled segment before close and final segment at barrier`() = runTest {
        val storage = MemoryStorage()
        val writer = writer(
            storage = storage,
            dispatcher = StandardTestDispatcher(testScheduler),
            encodeRecord = { "x".repeat(128).encodeToByteArray() },
            segmentByteLimit = 384L,
        )
        try {
            val first = writer.tryAcquireShadowCredit(foreground = false)!!
            writer.captureLegacyPersisted(
                commitSeq = writer.nextCommitSeq(),
                credit = first,
                pageKey = "page-a",
                generation = 1L,
                fencingToken = 1L,
                page = page("page-a"),
                deferSyncUntilBarrier = true,
            )
            runCurrent()
            storage.events.clear()

            val second = writer.tryAcquireShadowCredit(foreground = false)!!
            writer.captureLegacyPersisted(
                commitSeq = writer.nextCommitSeq(),
                credit = second,
                pageKey = "page-b",
                generation = 1L,
                fencingToken = 2L,
                page = page("page-b"),
                deferSyncUntilBarrier = true,
            )
            runCurrent()

            storage.segmentIndexes() shouldContainExactly listOf(0L, 1L)
            val firstSegmentSync = storage.events.indexOf("sync:0")
            val firstSegmentClose = storage.events.indexOf("close:0")
            (firstSegmentSync >= 0 && firstSegmentSync < firstSegmentClose) shouldBe true

            writer.flushToCaptureBarrier() shouldBe 3L
            val lastSecondSegmentWrite = storage.events.indexOfLast { it == "write:1" }
            val finalSegmentSync = storage.events.indexOfLast { it == "sync:1" }
            (lastSecondSegmentWrite >= 0 && finalSegmentSync > lastSecondSegmentWrite) shouldBe true
        } finally {
            writer.drainAndClose()
        }
    }

    @Test
    fun `shadow credit exhaustion records a terminal prefix without blocking legacy commits`() = runTest {
        val storage = MemoryStorage()
        val writer = writer(
            storage = storage,
            dispatcher = StandardTestDispatcher(testScheduler),
            regularCreditLimit = 2,
            foregroundCreditLimit = 1,
        )
        var legacyCommitCount = 0
        try {
            val first = writer.tryAcquireShadowCredit(foreground = false)!!
            legacyCommitCount++
            writer.captureLegacyPersisted(writer.nextCommitSeq(), first, "page-a", 1L, 1L, page())

            val second = writer.tryAcquireShadowCredit(foreground = false)!!
            legacyCommitCount++
            writer.captureLegacyPersisted(writer.nextCommitSeq(), second, "page-b", 1L, 2L, page())

            val third = writer.tryAcquireShadowCredit(foreground = false)
            third shouldBe null
            legacyCommitCount++ // Legacy persistence continues without waiting for the journal.
            writer.noteLegacyPersistedWithoutCredit(writer.nextCommitSeq())
            legacyCommitCount++ // Later legacy writes continue after the terminal prefix marker.
            writer.noteLegacyPersistedWithoutCredit(writer.nextCommitSeq())
            writer.isShadowCaptureActive shouldBe false
            writer.shadowLaggedCount shouldBe 2L
            legacyCommitCount shouldBe 4

            runCurrent()
            val frames = scan(storage).frames
            frames.map { it.commitSeq } shouldContainExactly listOf(null, 1L, 2L, null)
            frames.map { it.kind } shouldContainExactly listOf(
                ChapterJournalFormat.RecordKind.INVENTORY,
                ChapterJournalFormat.RecordKind.FREE_STATE,
                ChapterJournalFormat.RecordKind.FREE_STATE,
                ChapterJournalFormat.RecordKind.TERMINAL_LAG,
            )
            writer.ackedHighWaterSeq shouldBe 4L
        } finally {
            writer.drainAndClose()
        }
    }

    @Test
    fun `defunct marker closes new credit admission but preserves capture of completed work`() = runTest {
        val storage = MemoryStorage()
        val writer = writer(storage, StandardTestDispatcher(testScheduler))
        try {
            val credit = writer.tryAcquireShadowCredit(foreground = false)!!
            writer.captureLegacyPersisted(writer.nextCommitSeq(), credit, "page-a", 1L, 4L, page())
            writer.requestDefunctMarker()
            writer.requestDefunctMarker()
            runCurrent()

            writer.tryAcquireShadowCredit(foreground = false) shouldBe null
            writer.isShadowCaptureActive shouldBe false
            writer.droppedControlRecordCount shouldBe 0L
            writer.isWriterStopped shouldBe true
            storage.closedSinkCount shouldBe 1
            scan(storage).frames.map { it.kind } shouldContainExactly listOf(
                ChapterJournalFormat.RecordKind.INVENTORY,
                ChapterJournalFormat.RecordKind.FREE_STATE,
                ChapterJournalFormat.RecordKind.DEFUNCT,
            )
            writer.ackedHighWaterSeq shouldBe 3L
        } finally {
            writer.drainAndClose()
        }
    }

    @Test
    fun `defunct writer closes exactly once after every preheld credit resolves`() = runTest {
        val storage = MemoryStorage()
        val writer = writer(
            storage = storage,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        try {
            val first = writer.tryAcquireShadowCredit(foreground = false)!!
            val second = writer.tryAcquireShadowCredit(foreground = false)!!
            val firstSeq = writer.nextCommitSeq()
            val secondSeq = writer.nextCommitSeq()

            writer.requestDefunctMarker()
            writer.requestDefunctMarker()
            runCurrent()

            writer.isWriterStopped shouldBe false
            storage.closedSinkCount shouldBe 0
            writer.inFlightCount shouldBe 2

            writer.captureLegacyPersisted(firstSeq, first, "late-a", 1L, 2L, page())
            runCurrent()
            writer.isWriterStopped shouldBe false
            storage.closedSinkCount shouldBe 0
            writer.inFlightCount shouldBe 1

            writer.captureLegacyPersisted(secondSeq, second, "late-b", 1L, 3L, page())
            runCurrent()
            writer.isWriterStopped shouldBe true
            writer.isShadowCaptureActive shouldBe false
            writer.inFlightCount shouldBe 0
            storage.closedSinkCount shouldBe 1

            writer.captureLegacyPersisted(secondSeq + 1L, second, "post-close", 1L, 4L, page())
            writer.offersRejectedAfterTerminalCount shouldBe 1L
            writer.drainAndClose()
            storage.closedSinkCount shouldBe 1
        } finally {
            writer.drainAndClose()
        }
    }

    @Test
    fun `live coordinator keeps a defunct epoch open while a credited append can still arrive`() = runTest {
        val storage = MemoryStorage()
        val writer = writer(storage, StandardTestDispatcher(testScheduler))
        val coordinator = ChapterJournalCaptureCoordinator()
        coordinator.register(writer)
        try {
            val initial = writer.tryAcquireShadowCredit(foreground = false)!!
            writer.captureLegacyPersisted(writer.nextCommitSeq(), initial, "page-a", 1L, 1L, page())
            runCurrent()

            val linger = writer.tryAcquireShadowCredit(foreground = false)!!
            writer.requestDefunctMarker()
            writer.flushToCaptureBarrier() shouldBe writer.ackedHighWaterSeq
            writer.isWriterStopped shouldBe false
            coordinator.coverageSnapshot().single().terminallyClosed shouldBe false
            scan(storage).frames.last().kind shouldBe ChapterJournalFormat.RecordKind.DEFUNCT

            linger.releaseIfHeld() shouldBe true
            runCurrent()
            writer.isWriterStopped shouldBe true
            coordinator.coverageSnapshot().single().terminallyClosed shouldBe true
        } finally {
            writer.drainAndClose()
        }
    }

    @Test
    fun `empty defunct epoch creates no journal files`() = runTest {
        val storage = MemoryStorage()
        val writer = writer(storage, StandardTestDispatcher(testScheduler))

        writer.requestDefunctMarker()
        writer.drainAndClose()

        storage.segmentIndexes() shouldBe emptyList()
        writer.droppedControlRecordCount shouldBe 0L
    }

    @Test
    fun `preheld credit completes after defunct frame and remains replayable`() = runTest {
        val storage = MemoryStorage()
        val writer = writer(storage, StandardTestDispatcher(testScheduler))
        try {
            val credit = writer.tryAcquireShadowCredit(foreground = false)!!
            val sequence = writer.nextCommitSeq()

            writer.requestDefunctMarker()
            storage.segmentIndexes() shouldBe emptyList()
            writer.tryAcquireShadowCredit(foreground = false) shouldBe null

            // The store accepted this legacy operation before eviction. Its already-held
            // credit must be written after DEFUNCT so replay observes the accepted commit.
            writer.captureLegacyPersisted(sequence, credit, "page-after-defunct", 1L, 7L, page())
            runCurrent()

            val frames = scan(storage).frames
            frames.map { it.kind } shouldContainExactly listOf(
                ChapterJournalFormat.RecordKind.INVENTORY,
                ChapterJournalFormat.RecordKind.DEFUNCT,
                ChapterJournalFormat.RecordKind.FREE_STATE,
            )
            frames.last().commitSeq shouldBe sequence
            frames.last().payload.decodeToString() shouldBe "page-after-defunct:1"

            // A replay reducer applies committed state frames even when they follow the
            // eviction boundary; DEFUNCT is a marker, not a replay cutoff.
            val replayedPageKeys = frames.dropWhile { it.kind != ChapterJournalFormat.RecordKind.DEFUNCT }
                .drop(1)
                .filter { it.commitSeq != null }
                .map { it.payload.decodeToString().substringBefore(':') }
            replayedPageKeys shouldContainExactly listOf("page-after-defunct")

            writer.captureLegacyPersisted(sequence + 1L, credit, "impossible-late-offer", 1L, 8L, page())
            writer.offersRejectedAfterTerminalCount shouldBe 1L
            writer.inFlightCount shouldBe 0
            writer.isWriterStopped shouldBe true
            writer.drainAndClose()
            writer.ackedHighWaterSeq shouldBe 3L
            storage.closedSinkCount shouldBe 1
        } finally {
            writer.drainAndClose()
        }
    }

    @Test
    fun `defunct and terminal lag retain control slots at a full credit window`() = runTest {
        val storage = MemoryStorage()
        val writer = writer(
            storage = storage,
            dispatcher = StandardTestDispatcher(testScheduler),
            regularCreditLimit = 1,
            foregroundCreditLimit = 1,
        )
        try {
            val firstCredit = writer.tryAcquireShadowCredit(foreground = false)!!
            val secondCredit = writer.tryAcquireShadowCredit(foreground = true)!!
            writer.requestDefunctMarker()

            val firstSequence = writer.nextCommitSeq()
            writer.captureLegacyPersisted(firstSequence, firstCredit, "page-a", 1L, 1L, page())
            val secondSequence = writer.nextCommitSeq()
            writer.captureLegacyPersisted(secondSequence, secondCredit, "page-b", 1L, 2L, page())
            val starvedSequence = writer.nextCommitSeq()
            writer.noteLegacyPersistedWithoutCredit(starvedSequence)
            runCurrent()

            val frames = scan(storage).frames
            frames.map { it.kind } shouldContainExactly listOf(
                ChapterJournalFormat.RecordKind.INVENTORY,
                ChapterJournalFormat.RecordKind.DEFUNCT,
                ChapterJournalFormat.RecordKind.FREE_STATE,
                ChapterJournalFormat.RecordKind.FREE_STATE,
                ChapterJournalFormat.RecordKind.TERMINAL_LAG,
            )
            frames.mapNotNull { it.commitSeq } shouldContainExactly listOf(1L, 2L)
            writer.shadowLaggedCount shouldBe 1L
            writer.droppedControlRecordCount shouldBe 0L
            writer.ackedHighWaterSeq shouldBe 5L
        } finally {
            writer.drainAndClose()
        }
    }

    @Test
    fun `segment header fences generation ordinal and session epoch`() = runTest {
        val storage = MemoryStorage()
        val sessionId = UUID.fromString("d951852d-c449-45d9-8730-c4393a42d052")
        val writer = writer(
            storage = storage,
            dispatcher = StandardTestDispatcher(testScheduler),
            storeGeneration = 37L,
            epochOrdinal = 12L,
            sessionId = sessionId,
        )
        try {
            val credit = writer.tryAcquireShadowCredit(foreground = false)!!
            writer.captureLegacyPersisted(writer.nextCommitSeq(), credit, "page-a", 37L, 9L, page())
            runCurrent()
            val valid = ChapterJournalFormat.scanSegment(
                bytes = storage.readSegment(0L),
                expectedSegmentIndex = 0L,
                expectedGeneration = 37L,
                expectedEpochOrdinal = 12L,
                expectedSessionId = sessionId,
                firstExpectedFrameSeq = 1L,
                firstExpectedCommitSeq = 1L,
            )
            valid.validHeader shouldBe true
            valid.generation shouldBe 37L
            valid.epochOrdinal shouldBe 12L
            valid.sessionId shouldBe sessionId

            val otherEpoch = ChapterJournalFormat.scanSegment(
                bytes = storage.readSegment(0L),
                expectedSegmentIndex = 0L,
                expectedGeneration = 38L,
                expectedEpochOrdinal = 12L,
                expectedSessionId = sessionId,
                firstExpectedFrameSeq = 1L,
                firstExpectedCommitSeq = 1L,
            )
            otherEpoch.validHeader shouldBe false

            val wrongOrdinal = ChapterJournalFormat.scanSegment(
                bytes = storage.readSegment(0L),
                expectedSegmentIndex = 0L,
                expectedGeneration = 37L,
                expectedEpochOrdinal = 13L,
                expectedSessionId = sessionId,
                firstExpectedFrameSeq = 1L,
                firstExpectedCommitSeq = 1L,
            )
            wrongOrdinal.validHeader shouldBe false
        } finally {
            writer.drainAndClose()
        }
    }

    @Test
    fun `app private epoch allocation persists monotonic ordinals in sortable paths`() {
        val filesDir = Files.createTempDirectory("chapter-journal-epochs").toFile()
        try {
            val first = ChapterJournalWriter.allocateAppPrivateEpoch(
                filesDir = filesDir,
                chapterIdentity = "source:manga:chapter.json",
                generation = 4L,
                sessionId = UUID.fromString("d951852d-c449-45d9-8730-c4393a42d052"),
            )
            val second = ChapterJournalWriter.allocateAppPrivateEpoch(
                filesDir = filesDir,
                chapterIdentity = "source:manga:chapter.json",
                generation = 4L,
                sessionId = UUID.fromString("470bfde4-5812-423e-9f2e-43f91c0693c7"),
            )

            first.ordinal shouldBe 1L
            second.ordinal shouldBe 2L
            first.directory.name shouldBe "epoch-00000000000000000001-g4-d951852d-c449-45d9-8730-c4393a42d052"
            second.directory.name shouldBe "epoch-00000000000000000002-g4-470bfde4-5812-423e-9f2e-43f91c0693c7"
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun `epoch order prefers generation then persisted ordinal and rejects duplicate identity`() {
        val olderGeneration = ChapterJournalFormat.EpochOrderKey(4L, 99L, UUID(0L, 1L))
        val newerGeneration = ChapterJournalFormat.EpochOrderKey(5L, 1L, UUID(0L, 2L))
        ChapterJournalFormat.compareEpochOrder(olderGeneration, newerGeneration) shouldBe -1

        val earlierSameGeneration = ChapterJournalFormat.EpochOrderKey(5L, 9L, UUID(0L, 3L))
        val laterSameGeneration = ChapterJournalFormat.EpochOrderKey(5L, 10L, UUID(0L, 4L))
        ChapterJournalFormat.compareEpochOrder(earlierSameGeneration, laterSameGeneration) shouldBe -1

        assertThrows(IllegalStateException::class.java) {
            ChapterJournalFormat.compareEpochOrder(
                earlierSameGeneration,
                earlierSameGeneration.copy(sessionId = UUID(0L, 5L)),
            )
        }
    }

    private fun writer(
        storage: MemoryStorage,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher,
        encodeRecord: (ChapterJournalRecord) -> ByteArray = {
            "${it.pageKey}:${it.pageVersion}".encodeToByteArray()
        },
        encodeBulkRecord: (ChapterJournalBulkRecord) -> ByteArray = {
            "${it.operation}:${it.mutations.size}".encodeToByteArray()
        },
        encodeTerminalPayload: (String, Int, Long) -> ByteArray = { pageKey, bytes, seq ->
            "terminal_payload:$pageKey:$bytes:$seq".encodeToByteArray()
        },
        regularCreditLimit: Int = 8,
        foregroundCreditLimit: Int = 1,
        channelCapacity: Int = regularCreditLimit + foregroundCreditLimit + 5,
        segmentByteLimit: Long = ChapterJournalFormat.SEGMENT_BYTE_LIMIT,
        durabilityIntervalMs: Long = ChapterJournalWriter.DEFAULT_FREE_DURABILITY_INTERVAL_MS,
        storeGeneration: Long = 0L,
        epochOrdinal: Long = 0L,
        sessionId: UUID = UUID(0L, 0L),
    ) = ChapterJournalWriter(
        storage = storage,
        dispatcher = dispatcher,
        encodeRecord = encodeRecord,
        encodeBulkRecord = encodeBulkRecord,
        encodeInventory = { "inventory:${it.sourceFingerprint}".encodeToByteArray() },
        encodeTerminalLag = { count, commitSeq -> "terminal:$count:$commitSeq".encodeToByteArray() },
        encodeTerminalPayload = encodeTerminalPayload,
        storeGeneration = storeGeneration,
        epochOrdinal = epochOrdinal,
        sessionId = sessionId,
        regularCreditLimit = regularCreditLimit,
        foregroundCreditLimit = foregroundCreditLimit,
        channelCapacity = channelCapacity,
        segmentByteLimit = segmentByteLimit,
        durabilityIntervalMs = durabilityIntervalMs,
    )

    private fun scan(storage: MemoryStorage): ChapterJournalFormat.ScanResult {
        var expectedFrameSeq = 1L
        var expectedCommitSeq = 1L
        var lastCommitSeq = 0L
        var terminalLagSeen = false
        var terminalPayloadSeen = false
        val indexes = storage.segmentIndexes()
        var result: ChapterJournalFormat.ScanResult? = null
        indexes.forEach { index ->
            result = ChapterJournalFormat.scanSegment(
                bytes = storage.readSegment(index),
                expectedSegmentIndex = index,
                expectedGeneration = 0L,
                expectedEpochOrdinal = 0L,
                expectedSessionId = UUID(0L, 0L),
                firstExpectedFrameSeq = expectedFrameSeq,
                firstExpectedCommitSeq = expectedCommitSeq,
                lastCommitSeq = lastCommitSeq,
                terminalLagSeen = terminalLagSeen,
                terminalPayloadSeen = terminalPayloadSeen,
            )
            expectedFrameSeq = result!!.nextFrameSeq
            expectedCommitSeq = result!!.nextCommitSeq
            lastCommitSeq = result!!.frames.asReversed().firstOrNull { it.commitSeq != null }?.commitSeq ?: lastCommitSeq
            terminalLagSeen = result!!.terminalLagSeen
            terminalPayloadSeen = result!!.terminalPayloadSeen
        }
        return checkNotNull(result)
    }

    private fun draftPage() = PageTranslation(sourceFileName = "page.jpg", pageVersion = 1L)

    private fun page(pageKey: String = "page.jpg"): PublishedPageTranslation =
        PageTranslation(sourceFileName = pageKey, pageVersion = 1L).toPublishedPage()

    private class MemoryStorage(
        private val maxWriteBytes: Int = Int.MAX_VALUE,
        private val failOnSync: Int? = null,
    ) : ChapterJournalStorage {
        private val files = TreeMap<Long, ByteArray>()
        var syncCount = 0
            private set
        var closedSinkCount = 0
            private set
        val events = mutableListOf<String>()

        override fun segmentIndexes(): List<Long> = files.keys.toList()

        override fun readSegment(index: Long): ByteArray = files.getValue(index).copyOf()

        override fun truncateSegment(index: Long, byteCount: Long) {
            files[index] = files.getValue(index).copyOf(byteCount.toInt())
        }

        override fun discardSegmentsAfter(index: Long) {
            files.keys.filter { it > index }.forEach(files::remove)
        }

        override fun openSegment(index: Long, create: Boolean): ChapterJournalSink {
            if (!create && index !in files) throw IOException("missing segment $index")
            files.putIfAbsent(index, byteArrayOf())
            return MemorySink(index)
        }

        override fun syncDirectory() = Unit

        fun appendRaw(index: Long, bytes: ByteArray) {
            val current = files.getValue(index)
            files[index] = current + bytes
        }

        private inner class MemorySink(private val index: Long) : ChapterJournalSink {
            private var position = files.getValue(index).size

            override val size: Long get() = files.getValue(index).size.toLong()

            override fun write(bytes: ByteArray, offset: Int, length: Int): Int {
                events += "write:$index"
                val count = minOf(length, maxWriteBytes)
                val before = files.getValue(index)
                val after = before.copyOf(position + count)
                bytes.copyInto(after, destinationOffset = position, startIndex = offset, endIndex = offset + count)
                files[index] = after
                position += count
                return count
            }

            override fun flush() = Unit

            override fun sync() {
                syncCount++
                events += "sync:$index"
                if (syncCount == failOnSync) throw IOException("injected sync failure")
            }

            override fun close() {
                closedSinkCount++
                events += "close:$index"
            }
        }
    }
}
