package eu.kanade.translation

import eu.kanade.translation.orchestration.*

import eu.kanade.translation.storage.*

import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.translation.model.PageStage
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.pipeline.batch.ChunkCompletionOutcome
import eu.kanade.translation.scheduling.PreparedPage
import eu.kanade.translation.scheduling.SinglePageOutcome
import eu.kanade.translation.scheduling.TranslationExecutor
import eu.kanade.translation.scheduling.TranslationScheduler
import eu.kanade.translation.scheduling.TranslationStageListener
import eu.kanade.translation.scheduling.TranslationStoreResolver
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import java.io.InputStream

/**
 * T924 LI-3: reader/manual cancel writes must not punch through BATCH-held
 * page-stage leases. While a batch run (legacy OR flagged) holds a page's
 * lease, the batch owns that page's stage state; a durable cancel write built
 * from the CURRENT snapshot (`updatePageFromCurrentSnapshot`) carries the
 * batch's own lease token and sails through the write fence, bumping the page
 * version mid-stage — the flagged lane's `checkpointOcr` then rejects
 * (CHECKPOINT_REJECTED) and a healthy page is charged to the failure ledger,
 * or a mid-TRANSLATE write fails `mergeTranslation` and pauses the whole run
 * after the provider call was paid.
 *
 * The chapter-level "stop translation" action is a DIFFERENT path: it cancels
 * the batch translator job itself (`ChapterTranslator.stop` →
 * `translationJob.cancel`), whose teardown releases every BATCH lease — so
 * skipping the scheduler's per-page cancel write for batch-leased pages never
 * strands state.
 */
class CancelBatchLeaseSkipTest {

    private fun newStore(): ChapterTranslationStore = ChapterTranslationStore(
        translationFile = null,
        fileCreator = null,
        initialPages = emptyMap(),
    )

    private fun scheduler(store: ChapterTranslationStore, chapterId: Long = 1L): TranslationScheduler {
        val executor = object : TranslationExecutor {
            override suspend fun translateSinglePage(
                manga: Manga,
                chapter: Chapter,
                source: HttpSource,
                pageKey: String,
                force: Boolean,
                stageListener: TranslationStageListener?,
                origin: PageWriteOrigin,
            ): SinglePageOutcome = SinglePageOutcome.Completed
            override suspend fun translateSinglePageFromStream(
                manga: Manga,
                chapter: Chapter,
                source: HttpSource,
                pageKey: String,
                streamFn: () -> InputStream,
                force: Boolean,
                stageListener: TranslationStageListener?,
            ) {}
            override suspend fun prepareSinglePage(
                manga: Manga,
                chapter: Chapter,
                source: HttpSource,
                pageKey: String,
                streamFn: (() -> InputStream)?,
                force: Boolean,
                stageListener: TranslationStageListener?,
            ): PreparedPage? = null
            override suspend fun translatePreparedPage(
                manga: Manga,
                chapter: Chapter,
                source: HttpSource,
                prepared: PreparedPage,
                stageListener: TranslationStageListener?,
            ): ChunkCompletionOutcome? = null
        }
        val resolver = TranslationStoreResolver { id -> if (id == chapterId) store else null }
        val immediate = { id: Long -> if (id == chapterId) store else null }
        return TranslationScheduler(executor, resolver, immediate)
    }

    /** Acquires a real BATCH-origin page-stage lease on [pageKey]. */
    private suspend fun ChapterTranslationStore.holdBatchLease(pageKey: String): PageStageLease =
        tryAcquirePageStageLease(pageKey, PageStage.Ocr, PageWriteOrigin.BATCH)
            .shouldBeInstanceOf<LeaseAcquisition.Granted>()
            .lease

    // ------------------------------------------------------------------
    // T1: the scheduler cancel writer skips BATCH-leased pages.
    // ------------------------------------------------------------------

    @Test
    fun `markChapterCancelledSync leaves a BATCH-leased page untouched and cancels the lease-free page`() = runTest {
        val store = newStore()
        store.updatePage("leased") { PageTranslation(ocrStatus = StageStatus.RUNNING) }
        store.updatePage("free") {
            PageTranslation(
                ocrStatus = StageStatus.READY,
                translationStatus = StageStatus.RUNNING,
            )
        }
        store.holdBatchLease("leased")
        val s = scheduler(store)

        s.markChapterCancelledSync(chapterId = 1L)

        // The batch-owned page is UNCHANGED — no in-memory cancel, no durable
        // write; the batch run keeps its version expectations intact.
        store.state.value["leased"]?.ocrStatus shouldBe StageStatus.RUNNING
        // The lease-free page is cancelled exactly as before (legacy behavior).
        store.state.value["free"]?.translationStatus shouldBe StageStatus.CANCELLED
    }

    @Test
    fun `per-page cancel leaves a BATCH-leased page untouched`() = runTest {
        val store = newStore()
        store.updatePage("leased") { PageTranslation(ocrStatus = StageStatus.RUNNING) }
        store.holdBatchLease("leased")
        val s = scheduler(store)

        s.cancelPageTranslation(chapterId = 1L, pageKey = "leased")

        store.state.value["leased"]?.ocrStatus shouldBe StageStatus.RUNNING
    }

    @Test
    fun `a released (expired) lease no longer blocks the cancel write`() = runTest {
        val store = newStore()
        store.updatePage("released") { PageTranslation(ocrStatus = StageStatus.RUNNING) }
        store.holdBatchLease("released")
        store.releasePageStageLease("released", PageWriteOrigin.BATCH)
        val s = scheduler(store)

        s.markChapterCancelledSync(chapterId = 1L)

        // Legacy behavior fully preserved once the batch no longer owns the page.
        store.state.value["released"]?.ocrStatus shouldBe StageStatus.CANCELLED
    }

    @Test
    fun `markChapterCancelledAsync leaves a BATCH-leased page untouched`() = runTest {
        val store = newStore()
        store.updatePage("leased") { PageTranslation(ocrStatus = StageStatus.RUNNING) }
        store.updatePage("free") { PageTranslation(ocrStatus = StageStatus.RUNNING) }
        store.holdBatchLease("leased")
        val s = scheduler(store)

        s.markChapterCancelledAsync(chapterId = 1L)

        store.state.value["leased"]?.ocrStatus shouldBe StageStatus.RUNNING
        store.state.value["free"]?.ocrStatus shouldBe StageStatus.CANCELLED
    }

    // ------------------------------------------------------------------
    // T2: the in-memory fast cancel skips BATCH-leased pages too.
    // ------------------------------------------------------------------

    @Test
    fun `fastCancelInFlightStagesInMemory flips only the lease-free page`() = runTest {
        val store = newStore()
        store.updatePage("leased") { PageTranslation(ocrStatus = StageStatus.RUNNING) }
        store.updatePage("free") { PageTranslation(ocrStatus = StageStatus.RUNNING) }
        store.holdBatchLease("leased")

        val flipped = store.fastCancelInFlightStagesInMemory()

        flipped shouldBe 1
        store.state.value["leased"]?.ocrStatus shouldBe StageStatus.RUNNING
        store.state.value["free"]?.ocrStatus shouldBe StageStatus.CANCELLED
    }
}
