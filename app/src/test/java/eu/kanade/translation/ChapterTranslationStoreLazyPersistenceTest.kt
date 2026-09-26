package eu.kanade.translation

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.persistence.artifact.AtomicChapterDocuments
import eu.kanade.translation.persistence.artifact.ChapterArtifactEngine
import eu.kanade.translation.persistence.artifact.ChapterArtifactLayout
import eu.kanade.translation.persistence.artifact.ChapterArtifactManifest
import eu.kanade.translation.persistence.artifact.FakeChapterDocumentIo
import eu.kanade.translation.persistence.artifact.PageArtifactRecord
import eu.kanade.translation.persistence.chapter.ChapterTranslationStore
import eu.kanade.translation.pipeline.toPrecondition
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

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
            translationFile = null,
            fileCreator = null,
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
}
