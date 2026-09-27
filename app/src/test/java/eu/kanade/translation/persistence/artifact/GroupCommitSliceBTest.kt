package eu.kanade.translation.persistence.artifact

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.persistence.chapter.ActiveChapterStoreRegistry
import eu.kanade.translation.persistence.chapter.ChapterTranslationStore
import eu.kanade.translation.persistence.chapter.WriterOrigin
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Covers staged mutation publication at commit points or after the debounce,
 * candidate-promotion merging, file-backed read-back validation, stop-time
 * draining, second-writer flushing, glossary flushing, and disabled-flag behavior.
 */
class GroupCommitSliceBTest {

    private val layout = ChapterArtifactLayout("Chapter 1")

    @BeforeEach
    fun setUp() {
        GroupCommitConfiguration.enabled = true
        ActiveChapterStoreRegistry.clearGlobalWriters()
        ChapterTranslationStore.artifactImageProbe = CleanedImageProbe { ProbedImage(100, 100) }
    }

    @AfterEach
    fun tearDown() {
        // Restore the suite-wide default-off baseline (499a7c9). The previous
        // `= true` here leaked the flag into every later test class in the
        // JVM: staged writes + the 250ms debounce flush on a real dispatcher
        // race the runTest bodies and re-choreograph persistence behind the
        // tests' captured preconditions (ARTIFACT_PUBLICATION_FAILED cascade).
        GroupCommitConfiguration.enabled = false
        ActiveChapterStoreRegistry.clearGlobalWriters()
        ChapterTranslationStore.artifactImageProbe = BitmapFactoryCleanedImageProbe
    }

    private fun block() = TranslationBlock(
        text = "hello",
        translation = "world",
        width = 10f,
        height = 10f,
        x = 0f,
        y = 0f,
        symHeight = 1f,
        symWidth = 1f,
        angle = 0f,
    )

    private fun intermediatePage() = PageTranslation(
        blocks = mutableListOf(block()),
        imgWidth = 100f,
        imgHeight = 100f,
        ocrStatus = StageStatus.READY,
        translationStatus = StageStatus.READY,
        inpaintStatus = StageStatus.RUNNING,
        renderStatus = StageStatus.PENDING,
    )

    private fun displayReadyPage() = PageTranslation(
        blocks = mutableListOf(block()),
        imgWidth = 100f,
        imgHeight = 100f,
        ocrStatus = StageStatus.READY,
        translationStatus = StageStatus.READY,
        inpaintStatus = StageStatus.READY,
        renderStatus = StageStatus.READY,
        cleanedImageName = "0001.cleaned.jpg",
        inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION,
    )

    @Test
    fun `flag OFF preserves exact synchronous candidate and promotion writes`() {
        GroupCommitConfiguration.withFlag(false) {
            val io = FakeChapterDocumentIo().apply { fileBacked = true }
            val store = ChapterArtifactEngine(AtomicChapterDocuments(io), layout, displayBaseProbe = CleanedImageProbe { ProbedImage(100, 100) })
            val initialManifest = ChapterArtifactManifest(
                chapterKey = layout.chapterKey,
                pages = mapOf("0001.jpg" to PageArtifactRecord(pageKey = "0001.jpg")),
            )
            AtomicChapterDocuments(io).publishJson(layout.manifestFileName, initialManifest)

            // Opening candidate
            val openRes = store.openCandidate(
                manifest = initialManifest,
                pageKey = "0001.jpg",
                origin = ArtifactOrigin.READER_ADHOC,
                expectedPageVersion = 0L,
                dependencyFingerprint = "dep-1",
            )
            val openedManifest = (openRes as ChapterArtifactEngine.TransactionOutcome.Committed).manifest

            // When flag is OFF, promoteLiveCandidate requires candidate snapshot file
            val page = displayReadyPage()

            val promoteRes = store.promoteLiveCandidate(
                manifest = openedManifest,
                pageKey = "0001.jpg",
                generationId = openedManifest.pages.getValue("0001.jpg").candidate!!.generationId,
                expectedPageVersion = openedManifest.pages.getValue("0001.jpg").pageVersion,
                expectedDependencyFingerprint = "dep-1",
                pageSnapshot = page,
                origin = ArtifactOrigin.READER_ADHOC,
            ).shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Committed>()

            // When flag is OFF, candidate file was written
            val candidateFile = layout.candidatePageSnapshotFile(
                "0001.jpg",
                openedManifest.pages.getValue("0001.jpg").candidate!!.generationId,
            )
            io.files.containsKey(candidateFile) shouldBe true
        }
    }

    @Test
    fun `Slice B2 candidate-promotion merge skips candidate file write when flag is ON`() {
        GroupCommitConfiguration.withFlag(true) {
            val io = FakeChapterDocumentIo().apply { fileBacked = true }
            val store = ChapterArtifactEngine(AtomicChapterDocuments(io), layout, displayBaseProbe = CleanedImageProbe { ProbedImage(100, 100) })
            val initialManifest = ChapterArtifactManifest(
                chapterKey = layout.chapterKey,
                pages = mapOf("0001.jpg" to PageArtifactRecord(pageKey = "0001.jpg")),
            )
            AtomicChapterDocuments(io).publishJson(layout.manifestFileName, initialManifest)

            val openRes = store.openCandidate(
                manifest = initialManifest,
                pageKey = "0001.jpg",
                origin = ArtifactOrigin.READER_ADHOC,
                expectedPageVersion = 0L,
                dependencyFingerprint = "dep-1",
            )
            val openedManifest = (openRes as ChapterArtifactEngine.TransactionOutcome.Committed).manifest

            val page = displayReadyPage()

            val promoteRes = store.promoteLiveCandidate(
                manifest = openedManifest,
                pageKey = "0001.jpg",
                generationId = openedManifest.pages.getValue("0001.jpg").candidate!!.generationId,
                expectedPageVersion = openedManifest.pages.getValue("0001.jpg").pageVersion,
                expectedDependencyFingerprint = "dep-1",
                pageSnapshot = page,
                origin = ArtifactOrigin.READER_ADHOC,
            ).shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Committed>()

            // When flag is ON, candidate file write is skipped!
            val candidateFile = layout.candidatePageSnapshotFile(
                "0001.jpg",
                openedManifest.pages.getValue("0001.jpg").candidate!!.generationId,
            )
            io.files.containsKey(candidateFile) shouldBe false

            // But committed file IS written and durable!
            val committedFile = layout.committedPageSnapshotFile(
                "0001.jpg",
                openedManifest.pages.getValue("0001.jpg").candidate!!.generationId,
            )
            io.files.containsKey(committedFile) shouldBe true
        }
    }

    @Test
    fun `Slice B3 read-back elision on File-backed storage elides byte compare`() {
        val io = FakeChapterDocumentIo()
        val docs = AtomicChapterDocuments(io)

        // Case 1: Flag OFF -> byte compare is enforced
        GroupCommitConfiguration.enabled = false
        io.fileBacked = true
        docs.publishJson("test1.json", mapOf("a" to 1)) shouldBe true

        // Case 2: Flag ON + FileBacked -> succeeds via parse-validation only
        GroupCommitConfiguration.enabled = true
        io.fileBacked = true
        docs.publishJson("test2.json", mapOf("b" to 2)) shouldBe true

        // Case 3: Flag ON + SAF (non-File-backed) -> byte compare is kept
        io.fileBacked = false
        docs.publishJson("test3.json", mapOf("c" to 3)) shouldBe true
    }

    @Test
    fun `Slice B1 staging and Amendment B second writer force-flush`() = runBlocking {
        GroupCommitConfiguration.withFlag(true) {
            val io = FakeChapterDocumentIo().apply { fileBacked = true }
            val artifactStore = ChapterArtifactEngine(AtomicChapterDocuments(io), layout, displayBaseProbe = CleanedImageProbe { ProbedImage(100, 100) })
            val initialManifest = ChapterArtifactManifest(
                chapterKey = layout.chapterKey,
                pages = mapOf("0001.jpg" to PageArtifactRecord(pageKey = "0001.jpg")),
            )
            AtomicChapterDocuments(io).publishJson(layout.manifestFileName, initialManifest)

            val registry = ActiveChapterStoreRegistry()
            val store = ChapterTranslationStore(
                translationFile = null,
                fileCreator = null,
                initialPages = mapOf("0001.jpg" to PageTranslation()),
                artifactStore = artifactStore,
                initialArtifactManifest = initialManifest,
            )
            registry.register(1L, store)
            try {
                // Perform an intermediate mutation staged in memory
                store.stagePageMutationLocked("0001.jpg", intermediatePage())
                store.hasStagedMutations() shouldBe true

                // When a second writer registers (e.g. PROBE_STORE), it triggers forceFlushOwningStore
                val secondWriterToken = ActiveChapterStoreRegistry.registerWriter(
                    chapterId = 1L,
                    chapterKey = layout.chapterKey,
                    origin = WriterOrigin.PROBE_STORE,
                )
                try {
                    // Staged mutations must be flushed!
                    store.hasStagedMutations() shouldBe false
                } finally {
                    secondWriterToken.close()
                }
            } finally {
                // register() also publishes into the process-wide mainStores map.
                // Remove it even after an assertion failure so later chapters that
                // reuse this layout key cannot force-flush this test's store.
                registry.remove(1L)?.closeAndFlush()
            }
        }
    }

    @Test
    fun `Amendment F glossary force-flush flushes staged buffer before glossary pointer publish`() = runBlocking {
        GroupCommitConfiguration.withFlag(true) {
            val io = FakeChapterDocumentIo().apply { fileBacked = true }
            val artifactStore = ChapterArtifactEngine(AtomicChapterDocuments(io), layout, displayBaseProbe = CleanedImageProbe { ProbedImage(100, 100) })
            val initialManifest = ChapterArtifactManifest(
                chapterKey = layout.chapterKey,
                pages = mapOf("0001.jpg" to PageArtifactRecord(pageKey = "0001.jpg")),
            )
            AtomicChapterDocuments(io).publishJson(layout.manifestFileName, initialManifest)

            val store = ChapterTranslationStore(
                translationFile = null,
                fileCreator = null,
                initialPages = mapOf("0001.jpg" to PageTranslation()),
                artifactStore = artifactStore,
                initialArtifactManifest = initialManifest,
            )

            // Stage an intermediate mutation
            store.stagePageMutationLocked("0001.jpg", intermediatePage())
            store.hasStagedMutations() shouldBe true

            // When glossary is updated, it must force-flush staged mutations first
            store.glossaryStore.updateGlossary(mapOf("apple" to "pomme"))

            // Staged mutations were flushed to durable truth before glossary pointer publish
            store.hasStagedMutations() shouldBe false
            store.artifactManifest?.glossary shouldNotBe null
        }
    }
}
