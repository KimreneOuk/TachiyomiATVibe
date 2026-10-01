package eu.kanade.translation.persistence.journal

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.toPublishedPage
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
            encodeInventory = { json.encodeToString(it).encodeToByteArray() },
            encodeTerminalLag = { _, sequence ->
                json.encodeToString(
                    ChapterJournalRecord(
                        pageKey = "__terminal_lag__",
                        generation = 0L,
                        fencingToken = 0L,
                        pageVersion = 0L,
                        state = null,
                        terminalReason = ChapterJournalRecord.TERMINAL_LAG_REASON_CREDIT_WINDOW,
                        terminalCommitSeq = sequence,
                    ),
                ).encodeToByteArray()
            },
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
                legacyPages.getValue("a.jpg").toPublishedPage(),
                artifactContentHash = StageFingerprints.pageSnapshot(legacyPages.getValue("a.jpg")),
                inventory = inventory("a.jpg"),
            )

            // This commit has no shadow credit. Shadow ends with a terminal marker,
            // while the legacy artifact still accepts and publishes the page.
            legacyPages["b.jpg"] = PageTranslation(sourceFileName = "b.jpg", pageVersion = 2L)
            writer.noteLegacyPersistedWithoutCredit(
                writer.nextCommitSeq(),
                inventory = inventory("a.jpg", "b.jpg"),
            )
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
            scanned.frames.map { it.frameSeq } shouldContainExactly listOf(1L, 2L, 3L, 4L)
            scanned.frames.map { it.kind } shouldContainExactly listOf(
                ChapterJournalFormat.RecordKind.INVENTORY,
                ChapterJournalFormat.RecordKind.FREE_STATE,
                ChapterJournalFormat.RecordKind.INVENTORY,
                ChapterJournalFormat.RecordKind.TERMINAL_LAG,
            )
            scanned.frames.last().commitSeq shouldBe null
            json.decodeFromString<ChapterJournalRecord>(scanned.frames.last().payload.decodeToString())
                .terminalCommitSeq shouldBe 2L

            val replayed = replayImage(crashImage, json)
            replayed.expectedPageKeys shouldBe setOf("a.jpg", "b.jpg")
            replayed.expectedPageCount shouldBe 2
            replayed.pages.keys shouldBe setOf("a.jpg")
            replayed.missingPageKeys shouldBe setOf("b.jpg")
            replayed.invalidPageKeys shouldBe emptySet()
            replayed.corruptEpochs shouldBe emptySet()
        } finally {
            writer.drainAndClose()
        }
    }

    @Test
    fun `named power loss before sync loses only the unsynced frame and after sync replays it`() = runTest {
        val storage = CrashImageStorage()
        val json = Json {
            encodeDefaults = true
            explicitNulls = true
        }
        val writer = ChapterJournalWriter(
            storage = storage,
            dispatcher = StandardTestDispatcher(testScheduler),
            encodeRecord = { json.encodeToString(it).encodeToByteArray() },
            encodeInventory = { json.encodeToString(it).encodeToByteArray() },
            encodeTerminalLag = { _, _ -> error("terminal lag is not part of this scenario") },
            regularCreditLimit = 2,
            foregroundCreditLimit = 1,
            durabilityIntervalMs = 1L,
        )
        try {
            val first = PageTranslation(sourceFileName = "a.jpg", pageVersion = 1L)
            writer.captureLegacyPersisted(
                commitSeq = writer.nextCommitSeq(),
                credit = checkNotNull(writer.tryAcquireShadowCredit(foreground = false)),
                pageKey = "a.jpg",
                generation = 0L,
                fencingToken = 0L,
                page = first.toPublishedPage(),
                artifactContentHash = StageFingerprints.pageSnapshot(first),
                inventory = inventory("a.jpg"),
            )
            runCurrent()
            advanceTimeBy(1L)
            runCurrent()
            val syncedFirst = storage.crashImage()
            replayImage(syncedFirst, json).pages.keys shouldBe setOf("a.jpg")

            val second = PageTranslation(sourceFileName = "b.jpg", pageVersion = 1L)
            writer.captureLegacyPersisted(
                commitSeq = writer.nextCommitSeq(),
                credit = checkNotNull(writer.tryAcquireShadowCredit(foreground = false)),
                pageKey = "b.jpg",
                generation = 0L,
                fencingToken = 0L,
                page = second.toPublishedPage(),
                artifactContentHash = StageFingerprints.pageSnapshot(second),
                inventory = inventory("a.jpg", "b.jpg"),
            )
            runCurrent()

            val beforeSecondSync = storage.crashImage()
            val beforeSyncScan = scanImage(beforeSecondSync)
            beforeSyncScan.frames.filter { it.commitSeq != null }.map { it.commitSeq } shouldContainExactly listOf(1L)
            replayImage(beforeSecondSync, json).pages.keys shouldBe setOf("a.jpg")

            advanceTimeBy(1L)
            runCurrent()
            val afterSecondSync = storage.crashImage()
            val afterSyncScan = scanImage(afterSecondSync)
            afterSyncScan.frames.filter { it.commitSeq != null }.map { it.commitSeq } shouldContainExactly listOf(1L, 2L)
            replayImage(afterSecondSync, json).pages.keys shouldBe setOf("a.jpg", "b.jpg")
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

    private fun replayImage(
        image: Map<Long, ByteArray>,
        json: Json,
    ): ChapterJournalReplayResult {
        if (image.isEmpty()) {
            return ChapterJournalReplayReducer.replay(emptyList())
        }
        val epoch = ChapterJournalReplayEpoch(
            order = ChapterJournalFormat.EpochOrderKey(0L, 0L, UUID(0L, 0L)),
            segments = image.toSortedMap().map { (index, bytes) -> ChapterJournalReplaySegment(index, bytes) },
        )
        return ChapterJournalReplayReducer.replay(
            epochs = listOf(epoch),
            artifactResolver = ChapterJournalArtifactIdentityResolver { _, hash, state ->
                StageFingerprints.pageSnapshot(state) == hash
            },
            json = json,
        )
    }

    private fun inventory(vararg keys: String) = ChapterJournalInventorySnapshot(
        expectedPageKeys = keys.toSet(),
        expectedPageCount = keys.size,
        sourceShaByPageKey = emptyMap(),
    )

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
