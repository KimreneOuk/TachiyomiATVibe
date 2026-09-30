package eu.kanade.translation.persistence.journal

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.persistence.artifact.StageFingerprints
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.TreeMap
import java.util.UUID

class ChapterJournalLagCrashTest {

    @Test
    fun `terminal shadow lag leaves legacy outcome intact and crash image is a valid prefix`() = runTest {
        val storage = CrashImageStorage()
        val json = Json {
            encodeDefaults = true
            explicitNulls = true
        }
        val writer = ChapterJournalWriter(
            storage = storage,
            dispatcher = StandardTestDispatcher(testScheduler),
            encodeRecord = { json.encodeToString(it).encodeToByteArray() },
            encodeInventory = { "inventory:${it.expectedPageCount}".encodeToByteArray() },
            encodeTerminalLag = { count, sequence -> "lag:$count:$sequence".encodeToByteArray() },
            regularCreditLimit = 1,
            foregroundCreditLimit = 1,
            durabilityIntervalMs = 1L,
        )
        val legacyPages = linkedMapOf<String, PageTranslation>()
        try {
            val firstCredit = writer.tryAcquireShadowCredit(foreground = false)!!
            legacyPages["a.jpg"] = PageTranslation(sourceFileName = "a.jpg", pageVersion = 1L)
            writer.captureLegacyPersisted(
                writer.nextCommitSeq(),
                firstCredit,
                "a.jpg",
                0L,
                0L,
                legacyPages.getValue("a.jpg"),
                artifactContentHash = StageFingerprints.pageSnapshot(legacyPages.getValue("a.jpg")),
            )

            // This commit has no shadow credit. Shadow ends with a terminal marker,
            // while the legacy artifact still accepts and publishes the page.
            legacyPages["b.jpg"] = PageTranslation(sourceFileName = "b.jpg", pageVersion = 2L)
            writer.noteLegacyPersistedWithoutCredit(writer.nextCommitSeq())
            legacyPages["c.jpg"] = PageTranslation(sourceFileName = "c.jpg", pageVersion = 3L)
            writer.noteLegacyPersistedWithoutCredit(writer.nextCommitSeq())
            writer.shadowLaggedCount shouldBe 2L
            legacyPages.keys.toList() shouldContainExactly listOf("a.jpg", "b.jpg", "c.jpg")

            runCurrent()
            advanceTimeBy(1L)
            runCurrent()

            // Snapshot only bytes that crossed the simulated fsync boundary, as
            // if the process died now without running closeAndFlush().
            val crashImage = storage.crashImage()
            val scanned = scanImage(crashImage)
            scanned.validHeader shouldBe true
            scanned.stoppedAtInvalidFrame shouldBe false
            scanned.frames.map { it.frameSeq } shouldContainExactly listOf(1L, 2L, 3L)
            scanned.frames.map { it.kind } shouldContainExactly listOf(
                ChapterJournalFormat.RecordKind.INVENTORY,
                ChapterJournalFormat.RecordKind.FREE_STATE,
                ChapterJournalFormat.RecordKind.TERMINAL_LAG,
            )
            scanned.frames.last().commitSeq shouldBe null
            scanned.frames.last().payload.decodeToString() shouldBe "lag:1:2"
        } finally {
            writer.drainAndClose()
        }
    }

    private fun scanImage(segments: Map<Long, ByteArray>): ChapterJournalFormat.ScanResult {
        var frameSeq = 1L
        var commitSeq = 1L
        var final: ChapterJournalFormat.ScanResult? = null
        for ((index, bytes) in segments.toSortedMap()) {
            val scanned = ChapterJournalFormat.scanSegment(
                bytes = bytes,
                expectedSegmentIndex = index,
                expectedGeneration = 0L,
                expectedEpochOrdinal = 0L,
                expectedSessionId = UUID(0L, 0L),
                firstExpectedFrameSeq = frameSeq,
                firstExpectedCommitSeq = commitSeq,
            )
            final = scanned
            frameSeq = scanned.nextFrameSeq
            commitSeq = scanned.nextCommitSeq
        }
        return checkNotNull(final)
    }

    private class CrashImageStorage : ChapterJournalStorage {
        private val written = TreeMap<Long, ByteArray>()
        private val durable = TreeMap<Long, ByteArray>()

        override fun segmentIndexes(): List<Long> = written.keys.toList()
        override fun readSegment(index: Long): ByteArray = written.getValue(index).copyOf()
        override fun truncateSegment(index: Long, byteCount: Long) {
            written[index] = written.getValue(index).copyOf(byteCount.toInt())
        }
        override fun discardSegmentsAfter(index: Long) {
            written.keys.filter { it > index }.forEach(written::remove)
        }

        override fun openSegment(index: Long, create: Boolean): ChapterJournalSink {
            if (!create && index !in written) throw IOException("missing segment")
            if (create) written.putIfAbsent(index, byteArrayOf())
            return object : ChapterJournalSink {
                override val size: Long get() = written.getValue(index).size.toLong()
                override fun write(bytes: ByteArray, offset: Int, length: Int): Int {
                    written[index] = written.getValue(index) + bytes.copyOfRange(offset, offset + length)
                    return length
                }
                override fun flush() = Unit
                override fun sync() {
                    durable[index] = written.getValue(index).copyOf()
                }
                override fun close() = Unit
            }
        }

        override fun syncDirectory() = Unit

        fun crashImage(): Map<Long, ByteArray> = durable.mapValues { (_, bytes) -> bytes.copyOf() }
    }
}
