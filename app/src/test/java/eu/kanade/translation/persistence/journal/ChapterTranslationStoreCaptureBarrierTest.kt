package eu.kanade.translation.persistence.journal

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.persistence.artifact.AtomicChapterDocuments
import eu.kanade.translation.persistence.artifact.ChapterArtifactEngine
import eu.kanade.translation.persistence.artifact.ChapterArtifactLayout
import eu.kanade.translation.persistence.artifact.ChapterArtifactManifest
import eu.kanade.translation.persistence.artifact.FakeChapterDocumentIo
import eu.kanade.translation.persistence.artifact.PageArtifactRecord
import eu.kanade.translation.persistence.chapter.ChapterTranslationStore
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.TreeMap

class ChapterTranslationStoreCaptureBarrierTest {

    @Test
    fun `snapshot holds one published map reference and parks a later producer until reopen`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val io = FakeChapterDocumentIo()
        val layout = ChapterArtifactLayout("capture-barrier")
        val documents = AtomicChapterDocuments(io)
        val manifest = ChapterArtifactManifest(
            chapterKey = layout.chapterKey,
            pages = mapOf("p0" to PageArtifactRecord(pageKey = "p0")),
        )
        documents.publishJson(layout.manifestFileName, manifest) shouldBe true
        val store = ChapterTranslationStore(
            artifactParentResolver = null,
            initialPages = mapOf("p0" to PageTranslation(sourceFileName = "p0")),
            artifactStore = ChapterArtifactEngine(documents, layout),
            initialArtifactManifest = manifest,
            persistenceDispatcher = dispatcher,
        )
        val journalStorage = MemoryJournalStorage()
        val writer = ChapterJournalWriter(
            storage = journalStorage,
            dispatcher = dispatcher,
            encodeRecord = { "${it.pageKey}:${it.pageVersion}".encodeToByteArray() },
            encodeTerminalLag = { count, sequence -> "lag:$count:$sequence".encodeToByteArray() },
            storeGeneration = 1L,
            epochOrdinal = 1L,
        )
        val snapshotStorage = MemorySnapshotStorage()
        store.attachJournalWriterForTests(writer)
        store.attachJournalSnapshotManagerForTests(ChapterJournalSnapshotManager(snapshotStorage))

        try {
            val initialMapReference = store.pages
            assertSame(initialMapReference, store.capturePublishedPagesForCompaction())
            writer.captureInventory(
                ChapterJournalInventorySnapshot(
                    expectedPageKeys = setOf("p0"),
                    expectedPageCount = 1,
                    sourceShaByPageKey = emptyMap(),
                ),
            )
            runCurrent()

            val activeProducerEntered = CompletableDeferred<Unit>()
            val releaseActiveProducer = CompletableDeferred<Unit>()
            val activeProducer = async(start = CoroutineStart.UNDISPATCHED) {
                store.withJournalCapturePermit {
                    activeProducerEntered.complete(Unit)
                    releaseActiveProducer.await()
                }
            }
            try {
                activeProducerEntered.await()
                val snapshotJob = async(start = CoroutineStart.UNDISPATCHED) {
                    store.captureJournalSnapshotForCompaction()
                }
                // Metadata scans run before the gate closes. Advancing them lets the snapshot park
                // at the barrier on the active producer below, making the window deterministic.
                runCurrent()
                val laterProducer = async(start = CoroutineStart.UNDISPATCHED) {
                    store.preRegisterPages(listOf("p0", "p1"))
                }
                store.state.value.keys shouldBe setOf("p0")
                laterProducer.isCompleted shouldBe false

                releaseActiveProducer.complete(Unit)
                activeProducer.await()
                runCurrent()
                val snapshot = checkNotNull(snapshotJob.await())
                laterProducer.await() shouldBe ChapterTranslationStore.PagePreRegistration.Accepted

                val payload = ChapterJournalSnapshotFormat.decode(
                    checkNotNull(snapshotStorage.readSnapshot(snapshot.generation)),
                )?.payload ?: error("snapshot should have a valid frame")
                check(payload.frontier.single().ackedFrameSeq >= snapshot.frontier.single().ackedFrameSeq)
                payload.pages.keys shouldBe setOf("p0")
                store.state.value.keys shouldBe setOf("p0", "p1")

                val postSnapshotAck = async(start = CoroutineStart.UNDISPATCHED) {
                    writer.flushToCaptureBarrier()
                }
                runCurrent()
                check(postSnapshotAck.await() > snapshot.frontier.single().ackedFrameSeq)
            } finally {
                releaseActiveProducer.complete(Unit)
                activeProducer.cancel()
                activeProducer.join()
            }
        } finally {
            store.closeAndFlush()
        }
    }

    private class MemorySnapshotStorage : ChapterJournalSnapshotStorage {
        private val snapshots = TreeMap<Long, ByteArray>()
        private val markerTemps = mutableMapOf<Long, ByteArray>()
        private val markers = mutableMapOf<Long, ByteArray>()

        override fun snapshotGenerations(): List<Long> = snapshots.keys.toList()
        override fun readSnapshot(generation: Long): ByteArray? = snapshots[generation]?.copyOf()
        override fun createSnapshot(generation: Long, bytes: ByteArray) {
            snapshots[generation] = bytes.copyOf()
        }
        override fun syncSnapshot(generation: Long) = Unit
        override fun syncDirectory() = Unit
        override fun readDurableMarker(generation: Long): ByteArray? = markers[generation]?.copyOf()
        override fun writeDurableMarkerTemp(generation: Long, bytes: ByteArray) {
            markerTemps[generation] = bytes.copyOf()
        }
        override fun syncDurableMarkerTemp(generation: Long) = Unit
        override fun publishDurableMarker(generation: Long) {
            markers[generation] = checkNotNull(markerTemps.remove(generation))
        }
        override fun epochKeys(): Set<ChapterJournalEpochKey> = emptySet()
        override fun deleteEpoch(key: ChapterJournalEpochKey) = Unit
        override fun deleteSnapshot(generation: Long) {
            snapshots.remove(generation)
            markers.remove(generation)
        }
    }

    private class MemoryJournalStorage : ChapterJournalStorage {
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
            segments.putIfAbsent(index, byteArrayOf())
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
