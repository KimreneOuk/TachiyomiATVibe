package eu.kanade.translation

import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.translation.model.PageStage
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.persistence.chapter.ChapterTranslationStore
import eu.kanade.translation.persistence.chapter.LeaseAcquisition
import eu.kanade.translation.persistence.chapter.PageWriteOrigin
import eu.kanade.translation.pipeline.batch.ChunkCompletionOutcome
import eu.kanade.translation.pipeline.execution.PreparedPage
import eu.kanade.translation.pipeline.execution.SinglePageOutcome
import eu.kanade.translation.pipeline.execution.TranslationExecutor
import eu.kanade.translation.pipeline.execution.TranslationStageListener
import eu.kanade.translation.scheduling.TranslationScheduler
import eu.kanade.translation.scheduling.TranslationStoreResolver
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import java.io.InputStream

/**
 * B4 step 1 regression coverage: the auto/master-toggle-off cancel paths must
 * synchronously flip in-flight pages to CANCELLED so the reader clears its dim
 * and overlay immediately, without waiting on each cancelled job's finally
 * block (which may never run if the worker is past its suspension point in
 * uncancellable native/HTTP code).
 *
 * Also guards the [TranslationManager.cancelAllPageTranslations] ordering fix
 * (B3 step 3 / B4 step 3) indirectly: `clearTransientQueuePages` landing on a
 * defunct store is a no-op, so the durable write must happen before the store
 * is marked defunct. The store-level no-op-after-defunct behavior is covered by
 * [eu.kanade.translation.ChapterTranslationStoreDefunctTest]; here we verify
 * the scheduler's sync flip runs against a non-defunct store.
 */
class CancelSyncStoreWriteTest {

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

    @Test
    fun `markChapterCancelledSync flips every RUNNING page to CANCELLED synchronously`() = runTest {
        val store = newStore()
        store.updatePage("p0") { PageTranslation(ocrStatus = StageStatus.RUNNING) }
        store.updatePage("p1") {
            PageTranslation(
                ocrStatus = StageStatus.READY,
                translationStatus = StageStatus.RUNNING,
            )
        }
        // A page that already reached a rendered result must be preserved.
        store.updatePage("p2") {
            PageTranslation(
                cleanedImageName = "p2.cleaned.png",
                ocrStatus = StageStatus.READY,
                inpaintStatus = StageStatus.READY,
            )
        }
        val s = scheduler(store)

        val flipped = s.markChapterCancelledSync(chapterId = 1L)

        flipped shouldBe 2
        store.state.value["p0"]?.ocrStatus shouldBe StageStatus.CANCELLED
        store.state.value["p1"]?.translationStatus shouldBe StageStatus.CANCELLED
        store.state.value["p2"]?.ocrStatus shouldBe StageStatus.READY
        store.state.value["p2"]?.cleanedImageName shouldBe "p2.cleaned.png"
    }

    @Test
    fun `markChapterCancelledSync is a no-op when no store is registered`() {
        val s = scheduler(newStore(), chapterId = 99L)
        // Resolves a different chapter's store; the target chapter has none.
        s.markChapterCancelledSync(chapterId = 1L) shouldBe 0
    }

    @Test
    fun `markChapterCancelledSync returns zero when nothing is RUNNING`() = runTest {
        val store = newStore()
        store.updatePage("p0") { PageTranslation(ocrStatus = StageStatus.READY) }
        val s = scheduler(store)

        s.markChapterCancelledSync(chapterId = 1L) shouldBe 0
        store.state.value["p0"]?.ocrStatus shouldBe StageStatus.READY
    }

    @Test
    fun `fastCancelInFlightStagesInMemory flips RUNNING pages to CANCELLED in memory immediately`() = runTest {
        val store = newStore()
        store.updatePage("p0") { PageTranslation(ocrStatus = StageStatus.RUNNING) }
        store.updatePage("p1") {
            PageTranslation(
                ocrStatus = StageStatus.READY,
                translationStatus = StageStatus.RUNNING,
            )
        }
        store.updatePage("p2") {
            PageTranslation(
                cleanedImageName = "p2.cleaned.png",
                ocrStatus = StageStatus.READY,
            )
        }

        val flipped = store.fastCancelInFlightStagesInMemory()

        flipped shouldBe 2
        store.state.value["p0"]?.ocrStatus shouldBe StageStatus.CANCELLED
        store.state.value["p1"]?.translationStatus shouldBe StageStatus.CANCELLED
        store.state.value["p2"]?.ocrStatus shouldBe StageStatus.READY
    }

    @Test
    fun `cancelAutoTranslations flips in-memory stages immediately`() = runTest {
        val store = newStore()
        store.updatePage("p0") { PageTranslation(ocrStatus = StageStatus.RUNNING) }
        val s = scheduler(store)

        s.cancelAutoTranslations(chapterId = 1L)

        // Store state must be flipped to CANCELLED in memory immediately without waiting for disk I/O
        store.state.value["p0"]?.ocrStatus shouldBe StageStatus.CANCELLED
    }

    @Test
    fun `auto cancellation does not cancel a page owned by a manual lease`() = runTest {
        val store = newStore()
        store.updatePage("manual") { PageTranslation(ocrStatus = StageStatus.RUNNING) }
        store.tryAcquirePageStageLease("manual", PageStage.Ocr, PageWriteOrigin.MANUAL)
            .shouldBeInstanceOf<LeaseAcquisition.Granted>()
        try {
            scheduler(store).cancelAutoTranslations(chapterId = 1L)

            store.state.value["manual"]?.ocrStatus shouldBe StageStatus.RUNNING
        } finally {
            store.releasePageStageLease("manual", PageWriteOrigin.MANUAL)
        }
    }
}
