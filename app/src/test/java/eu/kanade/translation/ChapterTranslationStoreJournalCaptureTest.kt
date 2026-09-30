package eu.kanade.translation

import com.hippo.unifile.FakeUniFile
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.persistence.artifact.StageFingerprints
import eu.kanade.translation.persistence.chapter.ChapterTranslationStore
import eu.kanade.translation.persistence.journal.ChapterJournalFormat
import eu.kanade.translation.persistence.journal.ChapterJournalInventoryRecord
import eu.kanade.translation.persistence.journal.ChapterJournalRecord
import eu.kanade.translation.persistence.journal.ChapterJournalSink
import eu.kanade.translation.persistence.journal.ChapterJournalStorage
import eu.kanade.translation.persistence.journal.ChapterJournalWriter
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
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
