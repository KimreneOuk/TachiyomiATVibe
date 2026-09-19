package eu.kanade.translation.ui

import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageFeedbackState
import eu.kanade.tachiyomi.ui.reader.viewer.readerManualOutcomeFeedback
import eu.kanade.translation.ChapterTranslationStore
import eu.kanade.translation.PageWriteOrigin
import eu.kanade.translation.artifact.AtomicChapterDocuments
import eu.kanade.translation.artifact.ChapterArtifactLayout
import eu.kanade.translation.artifact.ChapterArtifactManifest
import eu.kanade.translation.artifact.ChapterArtifactEngine
import eu.kanade.translation.artifact.CleanedImageProbe
import eu.kanade.translation.artifact.FakeChapterDocumentIo
import eu.kanade.translation.artifact.GroupCommitConfiguration
import eu.kanade.translation.artifact.PageArtifactRecord
import eu.kanade.translation.artifact.ProbedImage
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.pipeline.toPrecondition
import eu.kanade.translation.pipeline.batch.ChunkCompletionOutcome
import eu.kanade.translation.scheduling.PreparedPage
import eu.kanade.translation.scheduling.SinglePageOutcome
import eu.kanade.translation.scheduling.TranslationExecutor
import eu.kanade.translation.scheduling.TranslationScheduler
import eu.kanade.translation.scheduling.TranslationStageListener
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureTimeMillis

class GroupCommitSliceCTest {

    private val tempDir = File(System.getProperty("java.io.tmpdir"), "slice_c_test_${System.nanoTime()}")
    private val layout = ChapterArtifactLayout("chapter_c_test")

    @BeforeEach
    fun setUp() {
        GroupCommitConfiguration.enabled = true
        ChapterTranslationStore.artifactImageProbe = CleanedImageProbe { ProbedImage(100, 100) }
    }

    @AfterEach
    fun tearDown() {
        // Restore the suite-wide default-off baseline (499a7c9): leaking the
        // flag ON re-choreographs every later test class's store persistence
        // (staged writes + debounce flush on a real dispatcher), which
        // deterministically broke 20+ unrelated pipeline/coexistence tests.
        GroupCommitConfiguration.enabled = false
        ChapterTranslationStore.artifactImageProbe = eu.kanade.translation.artifact.BitmapFactoryCleanedImageProbe
        tempDir.deleteRecursively()
    }

    private class TestManualExecutor : TranslationExecutor {
        val gate = CompletableDeferred<Unit>()
        val callCount = AtomicInteger(0)

        override suspend fun translateSinglePage(
            manga: tachiyomi.domain.manga.model.Manga,
            chapter: tachiyomi.domain.chapter.model.Chapter,
            source: HttpSource,
            pageKey: String,
            force: Boolean,
            stageListener: TranslationStageListener?,
            origin: PageWriteOrigin,
        ): SinglePageOutcome {
            callCount.incrementAndGet()
            gate.await()
            return SinglePageOutcome.Completed
        }

        override suspend fun prepareSinglePage(
            manga: tachiyomi.domain.manga.model.Manga,
            chapter: tachiyomi.domain.chapter.model.Chapter,
            source: HttpSource,
            pageKey: String,
            streamFn: (() -> InputStream)?,
            force: Boolean,
            stageListener: TranslationStageListener?,
        ): PreparedPage? = null

        override suspend fun translatePreparedPage(
            manga: tachiyomi.domain.manga.model.Manga,
            chapter: tachiyomi.domain.chapter.model.Chapter,
            source: HttpSource,
            prepared: PreparedPage,
            stageListener: TranslationStageListener?,
        ): ChunkCompletionOutcome? = null

        override suspend fun translateSinglePageFromStream(
            manga: tachiyomi.domain.manga.model.Manga,
            chapter: tachiyomi.domain.chapter.model.Chapter,
            source: HttpSource,
            pageKey: String,
            streamFn: () -> InputStream,
            force: Boolean,
            stageListener: TranslationStageListener?,
        ) {}
    }

    private fun mockSession(chapterId: Long = 42L): Triple<
        tachiyomi.domain.manga.model.Manga,
        tachiyomi.domain.chapter.model.Chapter,
        HttpSource,
    > {
        val manga = mockk<tachiyomi.domain.manga.model.Manga>(relaxed = true)
        every { manga.id } returns chapterId
        val chapter = mockk<tachiyomi.domain.chapter.model.Chapter>(relaxed = true)
        every { chapter.id } returns chapterId
        val source = mockk<HttpSource>(relaxed = true)
        every { source.id } returns chapterId
        return Triple(manga, chapter, source)
    }

    @Test
    fun `R1 manual admission signal stamps tap to Queued truth under 100ms when flag is ON`() = runBlocking {
        GroupCommitConfiguration.withFlag(true) {
            val executor = TestManualExecutor()
            val scheduler = TranslationScheduler(executor, { null })
            val (manga, chapter, source) = mockSession(42L)

            try {
                // Measure tap -> Queued latency
                val elapsedMs = measureTimeMillis {
                    scheduler.translatePage(manga, chapter, source, "0001.jpg")
                }

                // Sub-100ms requirement (typically < 5ms)
                (elapsedMs < 100L) shouldBe true

                // In-memory outcome is immediately Admitted
                val outcome = scheduler.manualOutcomeFor(42L, "0001.jpg")
                outcome shouldBe SinglePageOutcome.Admitted

                // TranslationUiTruth maps Admitted -> QUEUED
                val truth = TranslationUiTruth.forManualOutcome(outcome, null)
                truth.shouldNotBeNull()
                truth.label shouldBe "Queued."
                truth shouldBe TranslationUiTruth.QUEUED

                // Reader chip feedback correctly joins to ManualTruth(QUEUED)
                val feedback = readerManualOutcomeFeedback(
                    chapterId = 42L,
                    pageKey = "0001.jpg",
                    attemptActive = false,
                    lookup = { c, k -> scheduler.manualOutcomeFor(c, k) },
                    nativeStall = null,
                    durable = null,
                )
                feedback shouldBe ReaderPageFeedbackState.ManualTruth(TranslationUiTruth.QUEUED)
            } finally {
                executor.gate.complete(Unit)
                scheduler.close()
            }
        }
    }

    @Test
    fun `Flag OFF does not emit Admitted outcome on tap`() = runBlocking {
        GroupCommitConfiguration.withFlag(false) {
            val executor = TestManualExecutor()
            val scheduler = TranslationScheduler(executor, { null })
            val (manga, chapter, source) = mockSession(42L)

            try {
                scheduler.translatePage(manga, chapter, source, "0001.jpg")
                // When flag is OFF, legacy behavior leaves outcome null until execution ends
                val outcome = scheduler.manualOutcomeFor(42L, "0001.jpg")
                outcome.shouldBeNull()
            } finally {
                executor.gate.complete(Unit)
                scheduler.close()
            }
        }
    }

    @Test
    fun `M1 silent death repair records Failed outcome when flag is ON`() {
        GroupCommitConfiguration.withFlag(true) {
            val executor = TestManualExecutor()
            val scheduler = TranslationScheduler(executor, { null })

            try {
                // Record failed outcome for lazy download error or missing stream
                scheduler.recordManualOutcome(42L, "0001.jpg", SinglePageOutcome.Failed("0001.jpg", "no stream available"))

                val outcome = scheduler.manualOutcomeFor(42L, "0001.jpg")
                outcome shouldBe SinglePageOutcome.Failed("0001.jpg", "no stream available")

                val feedback = readerManualOutcomeFeedback(
                    chapterId = 42L,
                    pageKey = "0001.jpg",
                    attemptActive = false,
                    lookup = { c, k -> scheduler.manualOutcomeFor(c, k) },
                    nativeStall = null,
                    durable = null,
                )
                feedback.shouldNotBeNull()
                (feedback as ReaderPageFeedbackState.ManualTruth).truth.severity shouldBe eu.kanade.translation.ui.UiSeverity.ERROR
            } finally {
                scheduler.close()
            }
        }
    }

    @Test
    fun `R2 restricted UI-before-persist publishes transient updates before staging`() = runBlocking {
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
                initialPages = mapOf("0001.jpg" to PageTranslation(sourceFileName = "0001.jpg")),
                artifactStore = artifactStore,
                initialArtifactManifest = initialManifest,
            )

            // Transient update: flip ocrStatus to RUNNING (shouldPersistUpdate == false)
            val patchOutcome = store.updatePageGuarded(
                pageKey = "0001.jpg",
                expected = store.snapshot("0001.jpg").toPrecondition(),
                description = "transient OCR flip",
            ) { prev ->
                (prev ?: PageTranslation(sourceFileName = "0001.jpg")).copy(
                    ocrStatus = StageStatus.RUNNING,
                )
            }

            // Patch accepted
            (patchOutcome is ChapterTranslationStore.PatchResult.Accepted) shouldBe true

            // StateFlow observed OCR as RUNNING immediately
            store.state.value["0001.jpg"]?.ocrStatus shouldBe StageStatus.RUNNING

            // Mutation is staged in memory (not published directly to disk manifest)
            store.hasStagedMutations() shouldBe true
        }
    }
}
