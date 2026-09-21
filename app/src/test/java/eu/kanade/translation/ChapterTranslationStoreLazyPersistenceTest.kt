package eu.kanade.translation

import eu.kanade.translation.artifact.AtomicChapterDocuments
import eu.kanade.translation.artifact.ChapterArtifactEngine
import eu.kanade.translation.artifact.ChapterArtifactLayout
import eu.kanade.translation.artifact.ChapterArtifactManifest
import eu.kanade.translation.artifact.FakeChapterDocumentIo
import eu.kanade.translation.artifact.PageArtifactRecord
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.storage.ChapterTranslationStore
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

    private fun store(io: FakeChapterDocumentIo): ChapterTranslationStore {
        val documents = AtomicChapterDocuments(io)
        val manifest = ChapterArtifactManifest(
            chapterKey = layout.chapterKey,
            pages = mapOf("p0.jpg" to PageArtifactRecord(pageKey = "p0.jpg")),
        )
        documents.publishJson(layout.manifestFileName, manifest) shouldBe true
        val artifact = ChapterArtifactEngine(documents, layout)
        return ChapterTranslationStore(
            translationFile = null,
            fileCreator = null,
            initialPages = mapOf("p0.jpg" to PageTranslation(sourceFileName = "p0.jpg")),
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
}
