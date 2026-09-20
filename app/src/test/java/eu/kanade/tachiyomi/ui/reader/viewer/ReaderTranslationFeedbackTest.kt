package eu.kanade.tachiyomi.ui.reader.viewer

import eu.kanade.presentation.reader.appbars.ReaderAutoTranslationStatusSnapshot
import eu.kanade.presentation.reader.appbars.toAutoTranslationStatusSnapshot
import eu.kanade.tachiyomi.ui.reader.ReaderAutoTranslationSlot
import eu.kanade.tachiyomi.ui.reader.ReaderAutoTranslationSlotState
import eu.kanade.tachiyomi.ui.reader.ReaderAutoTranslationUiState
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.ReaderHolderBindFence
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.scheduling.AutoActivityStatus
import eu.kanade.translation.scheduling.AutoDeferralReason
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ReaderTranslationFeedbackTest {

    @Test
    fun `manual page stage transitions remain observable at every stage`() {
        listOf(
            PageTranslation(
                ocrStatus = StageStatus.RUNNING,
                inpaintStatus = StageStatus.PENDING,
            ) to ReaderPageFeedbackState.ReadingText,
            PageTranslation(
                ocrStatus = StageStatus.READY,
                inpaintStatus = StageStatus.RUNNING,
            ) to ReaderPageFeedbackState.CleaningBubbles,
            PageTranslation(
                ocrStatus = StageStatus.READY,
                inpaintStatus = StageStatus.READY,
                translationStatus = StageStatus.RUNNING,
            ) to ReaderPageFeedbackState.TranslatingText,
            PageTranslation(
                ocrStatus = StageStatus.READY,
                inpaintStatus = StageStatus.READY,
                translationStatus = StageStatus.READY,
                renderStatus = StageStatus.RUNNING,
            ) to ReaderPageFeedbackState.FinishingPage,
        ).forEach { (page, expected) ->
            page.toReaderPageFeedback() shouldBe expected
        }
    }

    @Test
    fun `every live auto slot maps to a truthful page label`() {
        val expected = listOf(
            ReaderAutoTranslationSlotState.Queued to ReaderPageFeedbackState.Queued,
            ReaderAutoTranslationSlotState.ReadingText to ReaderPageFeedbackState.ReadingText,
            ReaderAutoTranslationSlotState.Cleaning to ReaderPageFeedbackState.CleaningBubbles,
            ReaderAutoTranslationSlotState.Translating to ReaderPageFeedbackState.TranslatingText,
            ReaderAutoTranslationSlotState.Rendering to ReaderPageFeedbackState.FinishingPage,
            ReaderAutoTranslationSlotState.Ready to ReaderPageFeedbackState.Translated,
            ReaderAutoTranslationSlotState.Deferred(AutoDeferralReason.Memory) to
                ReaderPageFeedbackState.Deferred(AutoDeferralReason.Memory),
            ReaderAutoTranslationSlotState.Deferred(AutoDeferralReason.Network) to
                ReaderPageFeedbackState.Deferred(AutoDeferralReason.Network),
            ReaderAutoTranslationSlotState.Deferred(AutoDeferralReason.SourceUnavailable) to
                ReaderPageFeedbackState.Deferred(AutoDeferralReason.SourceUnavailable),
            ReaderAutoTranslationSlotState.Deferred(AutoDeferralReason.LocalComputeBusy) to
                ReaderPageFeedbackState.Deferred(AutoDeferralReason.LocalComputeBusy),
            ReaderAutoTranslationSlotState.Failed(retryable = true) to
                ReaderPageFeedbackState.Failed(retryable = true),
        )

        expected.forEach { (source, reader) ->
            source.toReaderPageFeedback() shouldBe reader
        }
    }

    @Test
    fun `short stage emissions coalesce without delaying terminal result`() {
        val coalescer = ReaderTranslationFeedbackCoalescer(minimumDisplayDurationMs = 120L)

        coalescer.submit(ReaderPageFeedbackState.Queued, nowMs = 0L) shouldBe
            ReaderPageFeedbackState.Queued
        coalescer.submit(ReaderPageFeedbackState.ReadingText, nowMs = 20L) shouldBe null
        coalescer.submit(ReaderPageFeedbackState.CleaningBubbles, nowMs = 60L) shouldBe null
        coalescer.flush(nowMs = 179L) shouldBe null
        coalescer.flush(nowMs = 180L) shouldBe ReaderPageFeedbackState.CleaningBubbles

        coalescer.submit(ReaderPageFeedbackState.Translated, nowMs = 181L) shouldBe
            ReaderPageFeedbackState.Translated
        coalescer.hasPending shouldBe false

        coalescer.submit(ReaderPageFeedbackState.FinishingPage, nowMs = 200L) shouldBe null
        coalescer.submit(ReaderPageFeedbackState.Failed(retryable = false), nowMs = 201L) shouldBe null

        coalescer.beginAttempt()
        coalescer.submit(ReaderPageFeedbackState.FinishingPage, nowMs = 202L) shouldBe
            ReaderPageFeedbackState.FinishingPage
        coalescer.submit(ReaderPageFeedbackState.Failed(retryable = false), nowMs = 203L) shouldBe
            ReaderPageFeedbackState.Failed(retryable = false)
    }

    @Test
    fun `late lower stage cannot replace a higher pending or displayed stage`() {
        val coalescer = ReaderTranslationFeedbackCoalescer(minimumDisplayDurationMs = 120L)

        coalescer.submit(ReaderPageFeedbackState.Queued, nowMs = 0L) shouldBe
            ReaderPageFeedbackState.Queued
        coalescer.submit(ReaderPageFeedbackState.TranslatingText, nowMs = 0L) shouldBe null
        coalescer.submit(ReaderPageFeedbackState.ReadingText, nowMs = 10L) shouldBe null
        coalescer.flush(nowMs = 119L) shouldBe null
        coalescer.flush(nowMs = 120L) shouldBe ReaderPageFeedbackState.TranslatingText

        coalescer.submit(ReaderPageFeedbackState.FinishingPage, nowMs = 121L) shouldBe null
        coalescer.submit(ReaderPageFeedbackState.CleaningBubbles, nowMs = 122L) shouldBe null
        coalescer.flush(nowMs = 241L) shouldBe ReaderPageFeedbackState.FinishingPage
    }

    @Test
    fun `terminal clear and explicit new attempt preserve ordering boundaries`() {
        val coalescer = ReaderTranslationFeedbackCoalescer()

        coalescer.submit(ReaderPageFeedbackState.Queued, nowMs = 0L)
        coalescer.submit(ReaderPageFeedbackState.Translated, nowMs = 1L) shouldBe
            ReaderPageFeedbackState.Translated
        coalescer.submit(ReaderPageFeedbackState.ReadingText, nowMs = 2L) shouldBe null
        coalescer.hasPending shouldBe false

        coalescer.beginAttempt()
        coalescer.submit(ReaderPageFeedbackState.Queued, nowMs = 3L) shouldBe
            ReaderPageFeedbackState.Queued
        coalescer.submit(ReaderPageFeedbackState.Deferred(AutoDeferralReason.Memory), nowMs = 4L) shouldBe
            ReaderPageFeedbackState.Deferred(AutoDeferralReason.Memory)
        coalescer.submit(null, nowMs = 5L) shouldBe null
        coalescer.submit(ReaderPageFeedbackState.ReadingText, nowMs = 6L) shouldBe
            ReaderPageFeedbackState.ReadingText
    }

    @Test
    fun `durable display readiness wins and failures retain retryability`() {
        val ready = PageTranslation(
            blocks = mutableListOf(
                TranslationBlock(
                    text = "こんにちは",
                    translation = "Hello",
                    width = 10f,
                    height = 10f,
                    x = 0f,
                    y = 0f,
                    symHeight = 10f,
                    symWidth = 10f,
                    angle = 0f,
                ),
            ),
            cleanedImageName = "page.cleaned.webp",
            inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION,
            ocrStatus = StageStatus.FAILED,
            translationStatus = StageStatus.READY,
            inpaintStatus = StageStatus.READY,
            renderStatus = StageStatus.READY,
        )
        ready.toReaderPageFeedback() shouldBe ReaderPageFeedbackState.Translated

        PageTranslation(translationStatus = StageStatus.FAILED)
            .toReaderPageFeedback() shouldBe ReaderPageFeedbackState.Failed(retryable = true)

        val exhausted = PageTranslation(translationStatus = StageStatus.FAILED).apply {
            attemptCount = StageStatus.MAX_STAGE_RETRIES
        }
        exhausted.toReaderPageFeedback() shouldBe ReaderPageFeedbackState.Failed(retryable = false)
    }

    @Test
    fun `durable ocr running state is ignored if inpaint or cleaned image is already ready`() {
        val ocrStaleWithInpaintReady = PageTranslation(
            ocrStatus = StageStatus.RUNNING,
            inpaintStatus = StageStatus.READY,
        )
        ocrStaleWithInpaintReady.toReaderPageFeedback() shouldBe null

        val ocrStaleWithCleanedReady = PageTranslation(
            cleanedImageName = "page.cleaned.webp",
            inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION,
            ocrStatus = StageStatus.RUNNING,
            inpaintStatus = StageStatus.READY,
        )
        ocrStaleWithCleanedReady.toReaderPageFeedback() shouldBe null

        val genuineOcrRunning = PageTranslation(
            ocrStatus = StageStatus.RUNNING,
            inpaintStatus = StageStatus.PENDING,
        )
        genuineOcrRunning.toReaderPageFeedback() shouldBe ReaderPageFeedbackState.ReadingText
    }

    @Test
    fun `pager durable running state owns stale auto feedback and accepts its result`() {
        val durableRunning = ReaderPageFeedbackState.ReadingText

        selectReaderPageFeedback(
            durableFeedback = durableRunning,
            autoFeedback = ReaderPageFeedbackState.Translated,
            durableAttemptActive = true,
        ) shouldBe durableRunning
        selectReaderPageFeedback(
            durableFeedback = null,
            autoFeedback = ReaderPageFeedbackState.Translated,
            durableAttemptActive = true,
        ) shouldBe null
        selectReaderPageFeedback(
            durableFeedback = ReaderPageFeedbackState.TranslatingText,
            autoFeedback = ReaderPageFeedbackState.TranslatingText,
            durableAttemptActive = true,
        ) shouldBe ReaderPageFeedbackState.TranslatingText
        selectReaderPageFeedback(
            durableFeedback = ReaderPageFeedbackState.Translated,
            autoFeedback = ReaderPageFeedbackState.Failed(retryable = true),
            durableAttemptActive = false,
        ) shouldBe ReaderPageFeedbackState.Translated
    }

    @Test
    fun `webtoon durable retry outranks auto terminal and can finish`() {
        val coalescer = ReaderTranslationFeedbackCoalescer()
        val staleAutoTerminal = selectReaderPageFeedback(
            durableFeedback = null,
            autoFeedback = ReaderPageFeedbackState.Translated,
            durableAttemptActive = false,
        )
        coalescer.submit(staleAutoTerminal, nowMs = 0L) shouldBe ReaderPageFeedbackState.Translated

        val durableRetryStage = selectReaderPageFeedback(
            durableFeedback = ReaderPageFeedbackState.CleaningBubbles,
            autoFeedback = staleAutoTerminal,
            durableAttemptActive = true,
        )
        durableRetryStage shouldBe ReaderPageFeedbackState.CleaningBubbles
        coalescer.beginAttempt()
        coalescer.submit(durableRetryStage, nowMs = 1L) shouldBe ReaderPageFeedbackState.CleaningBubbles
        coalescer.submit(ReaderPageFeedbackState.Translated, nowMs = 2L) shouldBe
            ReaderPageFeedbackState.Translated

        selectReaderPageFeedback(
            durableFeedback = ReaderPageFeedbackState.CleaningBubbles,
            autoFeedback = ReaderPageFeedbackState.Failed(retryable = false),
            durableAttemptActive = true,
        ) shouldBe ReaderPageFeedbackState.CleaningBubbles
        selectReaderPageFeedback(
            durableFeedback = ReaderPageFeedbackState.FinishingPage,
            autoFeedback = ReaderPageFeedbackState.Translated,
            durableAttemptActive = true,
        ) shouldBe ReaderPageFeedbackState.FinishingPage
        selectReaderPageFeedback(
            durableFeedback = ReaderPageFeedbackState.Translated,
            autoFeedback = ReaderPageFeedbackState.Translated,
            durableAttemptActive = false,
        ) shouldBe ReaderPageFeedbackState.Translated
    }

    @Test
    fun `terminal dismissal rejects late old stage until explicit durable retry`() {
        val coalescer = ReaderTranslationFeedbackCoalescer()

        coalescer.submit(ReaderPageFeedbackState.Queued, nowMs = 0L)
        coalescer.submit(ReaderPageFeedbackState.Translated, nowMs = 1L) shouldBe
            ReaderPageFeedbackState.Translated
        coalescer.dismissTerminal()

        coalescer.submit(ReaderPageFeedbackState.FinishingPage, nowMs = 2L) shouldBe null
        coalescer.hasPending shouldBe false

        coalescer.beginAttempt()
        coalescer.submit(ReaderPageFeedbackState.ReadingText, nowMs = 3L) shouldBe
            ReaderPageFeedbackState.ReadingText
        coalescer.submit(ReaderPageFeedbackState.Translated, nowMs = 4L) shouldBe
            ReaderPageFeedbackState.Translated
    }

    @Test
    fun `delayed landscape zoom dispatch is fenced after recycle or replacement`() {
        val firstView = Any()
        val replacementView = Any()
        val fence = ReaderImageCallbackFence(generation = 7L, view = firstView)
        var currentGeneration = 7L
        var currentView: Any? = firstView
        var zoomStarts = 0
        val delayedZoom = {
            fence.dispatchIfCurrent(currentGeneration, currentView) { zoomStarts++ }
        }

        delayedZoom() shouldBe true
        currentGeneration = 8L
        currentView = null
        delayedZoom() shouldBe false
        currentGeneration = 9L
        currentView = replacementView
        delayedZoom() shouldBe false
        zoomStarts shouldBe 1
    }

    @Test
    fun `delayed crossfade cleanup cannot recycle a replacement view`() {
        val oldView = Any()
        val replacementView = Any()
        val fence = ReaderImageCallbackFence(generation = 3L, view = oldView)
        var currentGeneration = 3L
        var currentView: Any? = oldView
        var recycled = 0
        val delayedCleanup = {
            fence.dispatchIfCurrent(currentGeneration, currentView) { recycled++ }
        }

        delayedCleanup() shouldBe true
        currentGeneration = 4L
        currentView = replacementView
        delayedCleanup() shouldBe false
        recycled shouldBe 1
    }

    @Test
    fun `rebound webtoon holder collector dispatch rejects old page emissions`() {
        val pageA = Any()
        val pageB = Any()
        val oldBind = ReaderHolderBindFence(generation = 1, page = pageA)
        val newBind = ReaderHolderBindFence(generation = 2, page = pageB)
        var currentGeneration = 1
        var currentPage: Any? = pageA
        var applied = 0
        val oldEmission = {
            oldBind.dispatchIfCurrent(currentGeneration, currentPage) { applied++ }
        }

        oldEmission() shouldBe true
        currentGeneration = 2
        currentPage = pageB
        oldEmission() shouldBe false
        newBind.dispatchIfCurrent(currentGeneration, currentPage) { applied++ } shouldBe true
        applied shouldBe 2
    }

    @Test
    fun `auto status clamps ready count and retains ordered slot semantics`() {
        val state = ReaderAutoTranslationUiState.empty().copy(
            availableAheadTarget = 3,
            readyAheadCount = 9,
            activity = AutoActivityStatus.Working,
            orderedSlots = listOf(
                ReaderAutoTranslationSlot(8, ReaderAutoTranslationSlotState.Queued),
                ReaderAutoTranslationSlot(4, ReaderAutoTranslationSlotState.Translating),
                ReaderAutoTranslationSlot(6, ReaderAutoTranslationSlotState.Ready),
            ),
        )

        state.toAutoTranslationStatusSnapshot() shouldBe ReaderAutoTranslationStatusSnapshot(
            readyAheadCount = 3,
            availableAheadTarget = 3,
            orderedSlotPageIndices = listOf(8, 4, 6),
        )
    }

    @Test
    fun `reset clears a pending stage so recycled holders start empty`() {
        val coalescer = ReaderTranslationFeedbackCoalescer()
        coalescer.submit(ReaderPageFeedbackState.Queued, nowMs = 0L)
        coalescer.submit(ReaderPageFeedbackState.ReadingText, nowMs = 10L)

        coalescer.reset()

        coalescer.hasPending shouldBe false
        coalescer.submit(ReaderPageFeedbackState.CleaningBubbles, nowMs = 11L) shouldBe
            ReaderPageFeedbackState.CleaningBubbles
    }
}
