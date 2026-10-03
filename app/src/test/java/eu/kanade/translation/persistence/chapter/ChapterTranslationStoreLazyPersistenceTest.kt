package eu.kanade.translation.persistence.chapter

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.model.hasRenderedResult
import eu.kanade.translation.model.toPublishedPage
import eu.kanade.translation.persistence.artifact.ArtifactOrigin
import eu.kanade.translation.persistence.artifact.AtomicChapterDocuments
import eu.kanade.translation.persistence.artifact.ChapterArtifactEngine
import eu.kanade.translation.persistence.artifact.ChapterArtifactLayout
import eu.kanade.translation.persistence.artifact.ChapterArtifactManifest
import eu.kanade.translation.persistence.artifact.FakeChapterDocumentIo
import eu.kanade.translation.persistence.artifact.GenerationLifecycle
import eu.kanade.translation.persistence.artifact.GenerationRecord
import eu.kanade.translation.persistence.artifact.PageArtifactRecord
import eu.kanade.translation.persistence.journal.ChapterJournalRecord
import eu.kanade.translation.persistence.journal.ChapterJournalSink
import eu.kanade.translation.persistence.journal.ChapterJournalStorage
import eu.kanade.translation.persistence.journal.ChapterJournalWriter
import eu.kanade.translation.pipeline.toPrecondition
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.TreeMap

/** Behavioral contract for active-store memory-first publication. */
class ChapterTranslationStoreLazyPersistenceTest {

    private val layout = ChapterArtifactLayout("lazy-persistence")

    private fun store(
        io: FakeChapterDocumentIo,
        pageKey: String = "p0.jpg",
    ): ChapterTranslationStore {
        val documents = AtomicChapterDocuments(io)
        val manifest = ChapterArtifactManifest(
            chapterKey = layout.chapterKey,
            pages = mapOf(pageKey to PageArtifactRecord(pageKey = pageKey)),
        )
        documents.publishJson(layout.manifestFileName, manifest) shouldBe true
        val artifact = ChapterArtifactEngine(documents, layout)
        return ChapterTranslationStore(
            artifactParentResolver = null,
            initialPages = mapOf(pageKey to PageTranslation(sourceFileName = pageKey)),
            artifactStore = artifact,
            initialArtifactManifest = manifest,
        ).also { it.enableLazyPersistence() }
    }

    @Test
    fun `live state is visible before lazy artifact publication`() = runTest {
        val io = FakeChapterDocumentIo().apply { fileBacked = true }
        val store = store(io)
        val before = io.files.toMap()

        val result = store.updatePageGuarded(
            pageKey = "p0.jpg",
            expected = store.snapshot("p0.jpg").toPrecondition(),
            description = "lazy OCR durable output",
        ) { current ->
            (current ?: PageTranslation(sourceFileName = "p0.jpg")).apply {
                ocrStatus = StageStatus.READY
                blocks = mutableListOf(
                    TranslationBlock(
                        text = "source",
                        width = 10f,
                        height = 10f,
                        x = 0f,
                        y = 0f,
                        symHeight = 1f,
                        symWidth = 1f,
                        angle = 0f,
                    ),
                )
            }
        }

        result shouldBe ChapterTranslationStore.PatchResult.Accepted(store.snapshot("p0.jpg"))
        store.state.value.getValue("p0.jpg").ocrStatus shouldBe StageStatus.READY
        io.files shouldBe before

        store.flush()
        val persisted = ChapterArtifactEngine(AtomicChapterDocuments(io), layout).readManifest()
            ?.pages?.getValue("p0.jpg")?.candidate
        persisted shouldNotBe null
    }

    @Test
    fun `transient stage remains live-only when the page is already registered`() = runTest {
        val io = FakeChapterDocumentIo().apply { fileBacked = true }
        val store = store(io)

        store.updatePageGuarded(
            pageKey = "p0.jpg",
            expected = store.snapshot("p0.jpg").toPrecondition(),
            description = "lazy OCR running",
        ) { current ->
            (current ?: PageTranslation(sourceFileName = "p0.jpg")).apply {
                ocrStatus = StageStatus.RUNNING
            }
        }

        store.state.value.getValue("p0.jpg").ocrStatus shouldBe StageStatus.RUNNING
        store.flush()
        ChapterArtifactEngine(AtomicChapterDocuments(io), layout).readManifest()
            ?.pages?.getValue("p0.jpg")?.candidate shouldBe null
    }

    @Test
    fun `generation change discards queued lazy task before it writes`() = runTest {
        val io = FakeChapterDocumentIo().apply { fileBacked = true }
        val store = store(io)
        var ran = false
        val handle = store.enqueueLazyPersistence(store.currentGeneration) {
            ran = true
            true
        }
        store.beginGeneration("lazy test invalidation")

        store.flush()

        handle.await() shouldBe false
        ran shouldBe false
        io.writtenNames.none { it.contains("page") } shouldBe true
    }

    @Test
    fun `defunct generation discards queued lazy task before it writes`() = runTest {
        val io = FakeChapterDocumentIo().apply { fileBacked = true }
        val store = store(io)
        var ran = false
        val handle = store.enqueueLazyPersistence(store.currentGeneration) {
            ran = true
            true
        }

        store.markDefunct()
        store.flush()

        handle.await() shouldBe false
        ran shouldBe false
        store.isDefunct shouldBe true
    }

    @Test
    fun `batch durability barrier waits for lazy worker completion`() = runTest {
        val io = FakeChapterDocumentIo().apply { fileBacked = true }
        val store = store(io)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val handle = store.enqueueLazyPersistence(store.currentGeneration) {
            started.complete(Unit)
            release.await()
            true
        }
        var flushed = false
        val barrier = launch {
            store.flush()
            flushed = true
        }

        started.await()
        flushed shouldBe false
        release.complete(Unit)
        barrier.join()

        flushed shouldBe true
        handle.await() shouldBe true
    }

    @Test
    fun `failed lazy artifact flush leaves prior manifest authoritative`() = runTest {
        val io = FakeChapterDocumentIo().apply { fileBacked = true }
        val store = store(io)
        io.failWrites = true
        store.updatePageGuarded(
            pageKey = "p0.jpg",
            expected = store.snapshot("p0.jpg").toPrecondition(),
            description = "lazy failed write",
        ) { current ->
            (current ?: PageTranslation(sourceFileName = "p0.jpg")).apply {
                ocrStatus = StageStatus.READY
            }
        }

        store.flush()

        ChapterArtifactEngine(AtomicChapterDocuments(io), layout).readManifest()
            ?.pages?.getValue("p0.jpg")?.candidate shouldBe null
    }

    @Test
    fun `zero padded page keeps one candidate dependency across repeated lazy flushes`() = runTest {
        val io = FakeChapterDocumentIo().apply { fileBacked = true }
        val store = store(io, pageKey = "001.jpg")
        val page = PageTranslation(
            sourceFileName = "001.jpg",
            blocks = mutableListOf(
                TranslationBlock(
                    text = "source",
                    width = 10f,
                    height = 10f,
                    x = 0f,
                    y = 0f,
                    symHeight = 1f,
                    symWidth = 1f,
                    angle = 0f,
                ),
            ),
            ocrStatus = StageStatus.READY,
        )

        store.updatePageGuarded(
            pageKey = "001.jpg",
            expected = store.snapshot("001.jpg").toPrecondition(),
            description = "lazy padded OCR publication",
        ) { page }
        store.flush()

        val first = ChapterArtifactEngine(AtomicChapterDocuments(io), layout)
            .readManifest()?.pages?.getValue("001.jpg")?.candidate
            ?: error("candidate was not published")
        first.dependencyFingerprint shouldNotBe null

        // A second submission of the same page content must reuse the same
        // content+generation identity; retries must not manufacture a new
        // dependency fingerprint and reject their own candidate.
        store.updatePageGuarded(
            pageKey = "001.jpg",
            expected = store.snapshot("001.jpg").toPrecondition(),
            description = "repeat lazy padded OCR publication",
        ) { current -> current ?: page }
        store.flush()

        val second = ChapterArtifactEngine(AtomicChapterDocuments(io), layout)
            .readManifest()?.pages?.getValue("001.jpg")?.candidate
            ?: error("candidate disappeared after repeat flush")
        second.generationId shouldBe first.generationId
        second.dependencyFingerprint shouldBe first.dependencyFingerprint
    }

    @Test
    fun `one store flush forces its accepted rendered journal pages once`() = runTest {
        val io = FakeChapterDocumentIo().apply { fileBacked = true }
        val documents = AtomicChapterDocuments(io)
        val pageKeys = listOf("p0.jpg", "p1.jpg", "p2.jpg")
        val manifest = ChapterArtifactManifest(
            chapterKey = layout.chapterKey,
            pages = pageKeys.associateWith { PageArtifactRecord(pageKey = it) },
            expectedPageCount = pageKeys.size,
        )
        documents.publishJson(layout.manifestFileName, manifest) shouldBe true
        val artifact = ChapterArtifactEngine(documents, layout)
        val storage = CountingJournalStorage()
        val writer = ChapterJournalWriter(
            storage = storage,
            dispatcher = StandardTestDispatcher(testScheduler),
            encodeRecord = { it.pageKey.encodeToByteArray() },
            encodeInventory = { "inventory:${it.expectedPageCount}".encodeToByteArray() },
            encodeTerminalLag = { count, sequence -> "lag:$count:$sequence".encodeToByteArray() },
        )
        try {
            // Initialize the epoch before measuring per-flush state forces.
            val primerCredit = writer.tryAcquireShadowCredit(foreground = false)!!
            writer.captureLegacyPersisted(
                commitSeq = writer.nextCommitSeq(),
                credit = primerCredit,
                pageKey = "primer",
                generation = 0L,
                fencingToken = 0L,
                page = PageTranslation(sourceFileName = "primer").toPublishedPage(),
            )
            runCurrent()
            writer.flushToCaptureBarrier()
            var expectedSyncCount = storage.syncCount

            val store = ChapterTranslationStore(
                initialPages = pageKeys.associateWith { PageTranslation(sourceFileName = it) },
                artifactStore = artifact,
                initialArtifactManifest = manifest,
                persistenceDispatcher = StandardTestDispatcher(testScheduler),
            ).also {
                it.attachJournalWriterForTests(writer)
                it.enableLazyPersistence()
            }

            fun renderedPage(pageKey: String) = PageTranslation(
                sourceFileName = pageKey,
                blocks = mutableListOf(
                    TranslationBlock(
                        text = "source",
                        translation = "translated",
                        width = 10f,
                        height = 10f,
                        x = 0f,
                        y = 0f,
                        symHeight = 1f,
                        symWidth = 1f,
                        angle = 0f,
                    ),
                ),
                ocrStatus = StageStatus.READY,
                translationStatus = StageStatus.READY,
                inpaintStatus = StageStatus.READY,
                renderStatus = StageStatus.READY,
                originalImageFallback = true,
                inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION,
            )

            pageKeys.take(2).forEach { pageKey ->
                val rendered = renderedPage(pageKey)
                rendered.hasRenderedResult shouldBe true
                store.updatePageGuarded(
                    pageKey = pageKey,
                    expected = store.snapshot(pageKey).toPrecondition(),
                    description = "batch rendered journal state $pageKey",
                ) { rendered } as ChapterTranslationStore.PatchResult.Accepted
            }

            store.flush()
            runCurrent()

            storage.syncCount - expectedSyncCount shouldBe 1
            expectedSyncCount = storage.syncCount

            val finalRendered = renderedPage(pageKeys.last())
            store.updatePageGuarded(
                pageKey = pageKeys.last(),
                expected = store.snapshot(pageKeys.last()).toPrecondition(),
                description = "next-flush rendered journal state",
            ) { finalRendered } as ChapterTranslationStore.PatchResult.Accepted
            store.flush()
            runCurrent()

            storage.syncCount - expectedSyncCount shouldBe 1
        } finally {
            writer.drainAndClose()
        }
    }

    @Test
    fun `immediately promoted rendered page uses three atomic artifact publications`() = runTest {
        val io = FakeChapterDocumentIo().apply { fileBacked = true }
        val documents = AtomicChapterDocuments(io)
        val manifest = ChapterArtifactManifest(
            chapterKey = layout.chapterKey,
            pages = mapOf("p0.jpg" to PageArtifactRecord(pageKey = "p0.jpg")),
            expectedPageCount = 1,
        )
        documents.publishJson(layout.manifestFileName, manifest) shouldBe true
        val artifact = ChapterArtifactEngine(documents, layout)
        val opened = artifact.openCandidate(
            manifest = manifest,
            pageKey = "p0.jpg",
            origin = ArtifactOrigin.BATCH,
            expectedPageVersion = 0L,
            dependencyFingerprint = "immediate-promotion-deps",
        ) as ChapterArtifactEngine.TransactionOutcome.Committed
        val store = ChapterTranslationStore(
            initialPages = mapOf("p0.jpg" to PageTranslation(sourceFileName = "p0.jpg")),
            artifactStore = artifact,
            initialArtifactManifest = opened.manifest,
        ).also { it.enableLazyPersistence() }

        io.writtenNames.clear()
        val rendered = PageTranslation(
            sourceFileName = "p0.jpg",
            blocks = mutableListOf(
                TranslationBlock(
                    text = "source",
                    translation = "translated",
                    width = 10f,
                    height = 10f,
                    x = 0f,
                    y = 0f,
                    symHeight = 1f,
                    symWidth = 1f,
                    angle = 0f,
                ),
            ),
            ocrStatus = StageStatus.READY,
            translationStatus = StageStatus.READY,
            inpaintStatus = StageStatus.READY,
            renderStatus = StageStatus.READY,
            originalImageFallback = true,
            inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION,
        )
        rendered.hasRenderedResult shouldBe true
        store.updatePageGuarded(
            pageKey = "p0.jpg",
            expected = store.snapshot("p0.jpg").toPrecondition(),
            description = "immediately promotable rendered page",
        ) { rendered } as ChapterTranslationStore.PatchResult.Accepted

        store.flush()

        io.writtenNames.count { it.endsWith(".tmp") } shouldBe 3
        val reopened = ChapterArtifactEngine(documents, layout)
        val committedPage = reopened.load().manifest.pages.getValue("p0.jpg")
        val committed = committedPage.committed
        committed shouldNotBe null
        committedPage.candidate shouldBe null
        val committedSnapshot = reopened.readPageSnapshot(committed!!.pageSnapshotFileName)!!
        committedSnapshot.copy(updatedAt = rendered.updatedAt, pageVersion = rendered.pageVersion) shouldBe rendered
        committedSnapshot.pageVersion shouldBe 2L
        val generationRecord = documents.readValidated<GenerationRecord>(layout.generationFile(committed.generationId))
        generationRecord?.lifecycle shouldBe GenerationLifecycle.COMMITTED
    }

    @Test
    fun `failed immediate terminal publication still hands accepted candidate state to journal`() = runTest {
        val io = FakeChapterDocumentIo().apply { fileBacked = true }
        val documents = AtomicChapterDocuments(io)
        val pageKey = "p0.jpg"
        val manifest = ChapterArtifactManifest(
            chapterKey = layout.chapterKey,
            pages = mapOf(pageKey to PageArtifactRecord(pageKey = pageKey)),
            expectedPageCount = 1,
        )
        documents.publishJson(layout.manifestFileName, manifest) shouldBe true
        val artifact = ChapterArtifactEngine(documents, layout)
        val opened = artifact.openCandidate(
            manifest = manifest,
            pageKey = pageKey,
            origin = ArtifactOrigin.BATCH,
            expectedPageVersion = 0L,
            dependencyFingerprint = "journal-fallback-deps",
        ) as ChapterArtifactEngine.TransactionOutcome.Committed
        val generationId = opened.generationId!!
        io.writeNamesToFail += layout.generationFile(generationId)

        val storage = CountingJournalStorage()
        val encodedRecords = mutableListOf<ChapterJournalRecord>()
        val writer = ChapterJournalWriter(
            storage = storage,
            dispatcher = StandardTestDispatcher(testScheduler),
            encodeRecord = { record ->
                encodedRecords += record
                record.pageKey.encodeToByteArray()
            },
            encodeInventory = { "inventory:${it.expectedPageCount}".encodeToByteArray() },
            encodeTerminalLag = { count, sequence -> "lag:$count:$sequence".encodeToByteArray() },
        )
        try {
            val primerCredit = writer.tryAcquireShadowCredit(foreground = false)!!
            writer.captureLegacyPersisted(
                commitSeq = writer.nextCommitSeq(),
                credit = primerCredit,
                pageKey = "primer",
                generation = 0L,
                fencingToken = 0L,
                page = PageTranslation(sourceFileName = "primer").toPublishedPage(),
            )
            runCurrent()
            writer.flushToCaptureBarrier()
            val initialHighWater = writer.ackedHighWaterSeq

            val store = ChapterTranslationStore(
                initialPages = mapOf(pageKey to PageTranslation(sourceFileName = pageKey)),
                artifactStore = artifact,
                initialArtifactManifest = opened.manifest,
                persistenceDispatcher = StandardTestDispatcher(testScheduler),
            ).also {
                it.attachJournalWriterForTests(writer)
                it.enableLazyPersistence()
            }
            val rendered = PageTranslation(
                sourceFileName = pageKey,
                blocks = mutableListOf(
                    TranslationBlock(
                        text = "source",
                        translation = "translated",
                        width = 10f,
                        height = 10f,
                        x = 0f,
                        y = 0f,
                        symHeight = 1f,
                        symWidth = 1f,
                        angle = 0f,
                    ),
                ),
                ocrStatus = StageStatus.READY,
                translationStatus = StageStatus.READY,
                inpaintStatus = StageStatus.READY,
                renderStatus = StageStatus.READY,
                originalImageFallback = true,
                inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION,
            )
            store.updatePageGuarded(
                pageKey = pageKey,
                expected = store.snapshot(pageKey).toPrecondition(),
                description = "immediately promotable page with journal fallback",
            ) { rendered } as ChapterTranslationStore.PatchResult.Accepted

            store.flush()
            runCurrent()

            (writer.ackedHighWaterSeq > initialHighWater) shouldBe true
            encodedRecords.single { it.pageKey == pageKey }.state shouldNotBe null
            writer.drainAndClose()
        } finally {
            writer.drainAndClose()
        }
    }

    private class CountingJournalStorage : ChapterJournalStorage {
        private val files = TreeMap<Long, ByteArray>()
        var syncCount = 0
            private set

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
            return object : ChapterJournalSink {
                private var position = files.getValue(index).size

                override val size: Long get() = files.getValue(index).size.toLong()

                override fun write(bytes: ByteArray, offset: Int, length: Int): Int {
                    val before = files.getValue(index)
                    val after = before.copyOf(position + length)
                    bytes.copyInto(after, destinationOffset = position, startIndex = offset, endIndex = offset + length)
                    files[index] = after
                    position += length
                    return length
                }

                override fun flush() = Unit

                override fun sync() {
                    syncCount++
                }

                override fun close() = Unit
            }
        }

        override fun syncDirectory() = Unit
    }
}
