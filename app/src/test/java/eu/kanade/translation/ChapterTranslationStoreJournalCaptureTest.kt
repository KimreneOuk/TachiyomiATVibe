package eu.kanade.translation

import com.hippo.unifile.FakeUniFile
import eu.kanade.translation.model.PageStage
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.persistence.artifact.StageFingerprints
import eu.kanade.translation.persistence.chapter.ChapterTranslationStore
import eu.kanade.translation.persistence.chapter.LeaseAcquisition
import eu.kanade.translation.persistence.chapter.PageWriteOrigin
import eu.kanade.translation.persistence.journal.ChapterJournalBulkRecord
import eu.kanade.translation.persistence.journal.ChapterJournalFormat
import eu.kanade.translation.persistence.journal.ChapterJournalInventoryRecord
import eu.kanade.translation.persistence.journal.ChapterJournalRecord
import eu.kanade.translation.persistence.journal.ChapterJournalSink
import eu.kanade.translation.persistence.journal.ChapterJournalStorage
import eu.kanade.translation.persistence.journal.ChapterJournalWriter
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.util.TreeMap
import java.util.UUID

class ChapterTranslationStoreJournalCaptureTest {

    @TempDir
    lateinit var chapterDir: File

    @Test
    fun `successful legacy artifact commit is handed to the shadow journal`() = runTest {
        val root = FakeUniFile(parent = null, backing = chapterDir)
        val store = ChapterTranslationStore.openArtifact(root, "Chapter 1.json")
        val storage = MemoryStorage()
        val journalJson = Json {
            encodeDefaults = true
            explicitNulls = true
        }
        val writer = ChapterJournalWriter(
            storage = storage,
            dispatcher = StandardTestDispatcher(testScheduler),
            encodeRecord = { record -> journalJson.encodeToString(record).encodeToByteArray() },
            encodeInventory = { inventory -> journalJson.encodeToString(inventory).encodeToByteArray() },
            encodeTerminalLag = { count, commitSeq -> "lag:$count:$commitSeq".encodeToByteArray() },
        )
        store.attachJournalWriterForTests(writer)
        var recordedPageHash: String? = null

        try {
            store.updatePage("page.jpg") {
                PageTranslation(
                    sourceFileName = "page.jpg",
                    blocks = mutableListOf(
                        TranslationBlock(
                            text = "source",
                            translation = "manual edit",
                            width = 10f,
                            height = 10f,
                            x = 0f,
                            y = 0f,
                            symHeight = 1f,
                            symWidth = 1f,
                            angle = 0f,
                            userEditedAt = 1L,
                        ),
                    ),
                )
            }
            runCurrent()

            val scanned = ChapterJournalFormat.scanSegment(
                bytes = storage.readSegment(0L),
                expectedSegmentIndex = 0L,
                expectedGeneration = 0L,
                expectedEpochOrdinal = 0L,
                expectedSessionId = UUID(0L, 0L),
                firstExpectedFrameSeq = 1L,
                firstExpectedCommitSeq = 1L,
            )
            scanned.validHeader shouldBe true
            scanned.frames.map { it.commitSeq } shouldContainExactly listOf(null, 1L)
            scanned.frames.map { it.kind } shouldContainExactly listOf(
                ChapterJournalFormat.RecordKind.INVENTORY,
                ChapterJournalFormat.RecordKind.FREE_STATE,
            )
            val inventory = journalJson.decodeFromString<ChapterJournalInventoryRecord>(
                scanned.frames.first().payload.decodeToString(),
            )
            inventory.expectedPageKeys shouldContainExactly listOf("page.jpg")
            inventory.expectedPageCount shouldBe 1
            inventory.sourceFingerprint shouldBe StageFingerprints.canonicalFingerprint(
                listOf("journal-source-inventory-v1", 1, "page.jpg", ""),
            )
            val record = journalJson.decodeFromString<ChapterJournalRecord>(
                scanned.frames.last().payload.decodeToString(),
            )
            record.pageKey shouldBe "page.jpg"
            record.generation shouldBe 0L
            record.state?.blocks?.single()?.translation shouldBe "manual edit"
            record.artifactContentHash shouldBe StageFingerprints.pageSnapshot(record.state!!)
            recordedPageHash = record.artifactContentHash
        } finally {
            store.closeAndFlush()
        }

        writer.ackedHighWaterSeq shouldBe 2L

        // Reopen the persisted legacy artifact and derive the semantic snapshot hash again,
        // without consulting the live store instance that performed the original write.
        val reopenedStore = ChapterTranslationStore.openArtifact(root, "Chapter 1.json")
        try {
            val persistedPage = reopenedStore.state.value.getValue("page.jpg")
            StageFingerprints.pageSnapshot(persistedPage) shouldBe recordedPageHash
        } finally {
            reopenedStore.closeAndFlush()
        }
    }

    @Test
    fun `large replace and rekey operations remain one shadow event each`() = runTest {
        val root = FakeUniFile(parent = null, backing = chapterDir)
        val store = ChapterTranslationStore.openArtifact(root, "Bulk chapter.json")
        val storage = MemoryStorage()
        val journalJson = Json {
            encodeDefaults = true
            explicitNulls = true
        }
        val writer = ChapterJournalWriter(
            storage = storage,
            dispatcher = StandardTestDispatcher(testScheduler),
            encodeRecord = { record -> journalJson.encodeToString(record).encodeToByteArray() },
            encodeBulkRecord = { record -> journalJson.encodeToString(record).encodeToByteArray() },
            encodeInventory = { inventory -> journalJson.encodeToString(inventory).encodeToByteArray() },
            encodeTerminalLag = { count, commitSeq -> "lag:$count:$commitSeq".encodeToByteArray() },
        )
        store.attachJournalWriterForTests(writer)
        val pages = (1..12).associate { index ->
            val key = "page-$index.jpg"
            key to PageTranslation(
                sourceFileName = key,
                blocks = mutableListOf(
                    TranslationBlock(
                        text = "source-$index",
                        translation = "manual-$index",
                        width = 10f,
                        height = 10f,
                        x = 0f,
                        y = 0f,
                        symHeight = 1f,
                        symWidth = 1f,
                        angle = 0f,
                        userEditedAt = index.toLong(),
                    ),
                ),
            )
        }

        try {
            store.replaceAll(pages)
            runCurrent()

            val oldKeys = pages.keys.toList()
            val newKeys = oldKeys.map { "disk-$it" }
            store.rekeyPages(oldKeys, newKeys) shouldContainExactly oldKeys.zip(newKeys)
            runCurrent()

            val frames = ChapterJournalFormat.scanSegment(
                bytes = storage.readSegment(0L),
                expectedSegmentIndex = 0L,
                expectedGeneration = 0L,
                expectedEpochOrdinal = 0L,
                expectedSessionId = UUID(0L, 0L),
                firstExpectedFrameSeq = 1L,
                firstExpectedCommitSeq = 1L,
            ).frames
            frames.filter { it.commitSeq != null }.map { it.kind } shouldContainExactly listOf(
                ChapterJournalFormat.RecordKind.BULK_REPLACE,
                ChapterJournalFormat.RecordKind.BULK_REKEY,
            )
            frames.filter { it.commitSeq != null }.map { it.commitSeq } shouldContainExactly listOf(1L, 2L)

            val replace = journalJson.decodeFromString<ChapterJournalBulkRecord>(
                frames.single { it.kind == ChapterJournalFormat.RecordKind.BULK_REPLACE }.payload.decodeToString(),
            )
            replace.mapping.size shouldBe 12
            replace.mutations.size shouldBe 12
            val rekey = journalJson.decodeFromString<ChapterJournalBulkRecord>(
                frames.single { it.kind == ChapterJournalFormat.RecordKind.BULK_REKEY }.payload.decodeToString(),
            )
            rekey.mapping shouldBe oldKeys.zip(newKeys).toMap()
            rekey.mutations.size shouldBe 24
            writer.shadowLaggedCount shouldBe 0L
            writer.inFlightCount shouldBe 0
        } finally {
            store.closeAndFlush()
            writer.drainAndClose()
        }
    }

    @Test
    fun `replace aggregate contains only successful per-page outcomes`() = runTest {
        val root = FakeUniFile(parent = null, backing = chapterDir)
        val store = ChapterTranslationStore.openArtifact(root, "Partial bulk chapter.json")
        val storage = MemoryStorage()
        val journalJson = Json {
            encodeDefaults = true
            explicitNulls = true
        }
        val writer = ChapterJournalWriter(
            storage = storage,
            dispatcher = StandardTestDispatcher(testScheduler),
            encodeRecord = { record -> journalJson.encodeToString(record).encodeToByteArray() },
            encodeBulkRecord = { record -> journalJson.encodeToString(record).encodeToByteArray() },
            encodeInventory = { inventory -> journalJson.encodeToString(inventory).encodeToByteArray() },
            encodeTerminalLag = { count, commitSeq -> "lag:$count:$commitSeq".encodeToByteArray() },
        )
        store.attachJournalWriterForTests(writer)
        val pages = (1..4).associate { index ->
            val key = "partial-$index.jpg"
            key to PageTranslation(
                sourceFileName = key,
                blocks = mutableListOf(
                    TranslationBlock(
                        text = "source-$index",
                        translation = "manual-$index",
                        width = 10f,
                        height = 10f,
                        x = 0f,
                        y = 0f,
                        symHeight = 1f,
                        symWidth = 1f,
                        angle = 0f,
                        userEditedAt = index.toLong(),
                    ),
                ),
            )
        }
        val rejectedPageKey = "partial-2.jpg"
        var leaseAcquired = false

        try {
            store.replaceAll(pages)
            runCurrent()
            store.tryAcquirePageStageLease(rejectedPageKey, PageStage.Ocr, PageWriteOrigin.BATCH)
                .shouldBeInstanceOf<LeaseAcquisition.Granted>()
            leaseAcquired = true

            // replaceAll deletes the old durable page, then its unfenced update
            // is rejected because the test still owns a live stage lease.
            // Other per-page commits succeed and are the only replacement states
            // admitted to the aggregate frame.
            store.replaceAll(pages)
            runCurrent()

            val frames = ChapterJournalFormat.scanSegment(
                bytes = storage.readSegment(0L),
                expectedSegmentIndex = 0L,
                expectedGeneration = 0L,
                expectedEpochOrdinal = 0L,
                expectedSessionId = UUID(0L, 0L),
                firstExpectedFrameSeq = 1L,
                firstExpectedCommitSeq = 1L,
            ).frames
            val bulkFrame = frames.single {
                it.kind == ChapterJournalFormat.RecordKind.BULK_REPLACE && it.commitSeq == 2L
            }
            val bulk = journalJson.decodeFromString<ChapterJournalBulkRecord>(bulkFrame.payload.decodeToString())
            bulk.mutations.filter { it.state != null }.map { it.pageKey }.toSet() shouldBe pages.keys - rejectedPageKey
            bulk.mutations.single { it.pageKey == rejectedPageKey }.state shouldBe null
            bulk.mapping shouldBe pages.keys.associateWith { pageKey ->
                pageKey.takeUnless { pageKey == rejectedPageKey }
            }
        } finally {
            if (leaseAcquired) store.releasePageStageLease(rejectedPageKey, PageWriteOrigin.BATCH)
            store.closeAndFlush()
            writer.drainAndClose()
        }
    }

    private class MemoryStorage : ChapterJournalStorage {
        private val segments = TreeMap<Long, ByteArray>()

        override fun segmentIndexes(): List<Long> = segments.keys.toList()

        override fun readSegment(index: Long): ByteArray = segments.getValue(index).copyOf()

        override fun truncateSegment(index: Long, byteCount: Long) {
            segments[index] = segments.getValue(index).copyOf(byteCount.toInt())
        }

        override fun discardSegmentsAfter(index: Long) {
            segments.keys.filter { it > index }.forEach(segments::remove)
        }

        override fun openSegment(index: Long, create: Boolean): ChapterJournalSink {
            if (!create && index !in segments) throw IOException("missing segment $index")
            if (create) segments.putIfAbsent(index, byteArrayOf())
            return object : ChapterJournalSink {
                override val size: Long get() = segments.getValue(index).size.toLong()

                override fun write(bytes: ByteArray, offset: Int, length: Int): Int {
                    val current = segments.getValue(index)
                    segments[index] = current + bytes.copyOfRange(offset, offset + length)
                    return length
                }

                override fun flush() = Unit

                override fun sync() = Unit

                override fun close() = Unit
            }
        }

        override fun syncDirectory() = Unit
    }
}
