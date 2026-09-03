package eu.kanade.tachiyomi.ui.reader.viewer

import eu.kanade.tachiyomi.ui.reader.ReaderAutoTranslationSlot
import eu.kanade.tachiyomi.ui.reader.projectReaderAutoTranslationUiState
import eu.kanade.translation.PageWriteOrigin
import eu.kanade.translation.TranslationPipeline
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.model.toPageDisplayProjection
import eu.kanade.translation.scheduling.AutoChapterIdentity
import eu.kanade.translation.scheduling.AutoDeferralReason
import eu.kanade.translation.scheduling.AutoSlotState
import eu.kanade.translation.scheduling.AutoTranslationSnapshot
import eu.kanade.translation.scheduling.AutoWindowSlot
import eu.kanade.translation.scheduling.SinglePageOutcome
import eu.kanade.translation.translator.NativeStallState
import eu.kanade.translation.ui.TranslationUiTruth
import eu.kanade.translation.ui.UiRetryMode
import eu.kanade.translation.ui.UiSeverity
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * T917 P5 reader-hop (review finding P5-1, spec §0.2.2, §6.2.8, §6.2.10):
 * the reader surfaces must consume the Phase 5 truth layer. These tests target
 * the JOIN, not the pure mapper (already covered by the P5 suites): the typed
 * manual outcome must reach the page chip wrapped verbatim from
 * [TranslationUiTruth.forManualOutcome], identity-fenced by chapter+pageKey,
 * and the rolling-auto slot projection must carry [TranslationUiTruth.forAutoSlot].
 */
class ReaderManualOutcomeTruthTest {

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun displayReadyPage(): PageTranslation = PageTranslation(
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

    /** Fake of the bounded scheduler map: keyed "$chapterId:$pageKey". */
    private fun lookupFor(
        chapterId: Long,
        pageKey: String,
        outcome: SinglePageOutcome?,
    ): (Long, String) -> SinglePageOutcome? = { c, k ->
        if (c == chapterId && k == pageKey) outcome else null
    }

    /** The record the join must return: the pure mapper's truth, wrapped. */
    private fun manualTruthOf(outcome: SinglePageOutcome, durable: PageTranslation? = null) =
        ReaderPageFeedbackState.ManualTruth(
            TranslationUiTruth.forManualOutcome(
                outcome = outcome,
                durable = durable?.toPageDisplayProjection(),
            )!!,
        )

    // ------------------------------------------------------------------
    // Typed outcome → mapper copy on the reader chip
    // ------------------------------------------------------------------

    @Test
    fun `T917 P5 reader-hop RED defect - paused outcome carries the pure mapper paused copy`() {
        val paused = SinglePageOutcome.Paused(nextEligibleRetryAtEpochMs = 1_724_000_000_000L)

        val feedback = readerManualOutcomeFeedback(
            chapterId = 1L,
            pageKey = "009.jpg",
            attemptActive = false,
            lookup = lookupFor(1L, "009.jpg", paused),
            nativeStall = null,
            durable = null,
        )

        // The join must wrap the mapper's record verbatim — reader code owns
        // no copy and no precedence.
        feedback shouldBe manualTruthOf(paused)
        val truth = (feedback as ReaderPageFeedbackState.ManualTruth).truth
        truth.label shouldBe "Paused; explicit retry available."
        truth.retryMode shouldBe UiRetryMode.EXPLICIT
        truth.retryAtEpochMs shouldBe 1_724_000_000_000L
    }

    @Test
    fun `T917 P5 reader-hop RED defect - failed outcome carries failure truth and committed display downgrades to warning`() {
        val failed = SinglePageOutcome.Failed("009.jpg", "provider error")

        val withoutDisplay = readerManualOutcomeFeedback(
            chapterId = 1L,
            pageKey = "009.jpg",
            attemptActive = false,
            lookup = lookupFor(1L, "009.jpg", failed),
            nativeStall = null,
            durable = null,
        )
        withoutDisplay shouldBe manualTruthOf(failed)
        (withoutDisplay as ReaderPageFeedbackState.ManualTruth).truth.severity shouldBe UiSeverity.ERROR

        // A committed readable result keeps the readable image: the mapper
        // downgrades the failure truth to warning — the join must pass the
        // durable projection through so the mapper owns that precedence.
        val ready = displayReadyPage()
        val withDisplay = readerManualOutcomeFeedback(
            chapterId = 1L,
            pageKey = "009.jpg",
            attemptActive = false,
            lookup = lookupFor(1L, "009.jpg", failed),
            nativeStall = null,
            durable = ready,
        )
        withDisplay shouldBe manualTruthOf(failed, ready)
        (withDisplay as ReaderPageFeedbackState.ManualTruth).truth.severity shouldBe UiSeverity.WARNING
    }

    @Test
    fun `T917 P5 reader-hop RED defect - exhausted failed outcome carries manual-retry-required truth`() {
        val failed = SinglePageOutcome.Failed("009.jpg", "interrupted")
        val exhausted = PageTranslation(translationStatus = StageStatus.FAILED).apply {
            attemptCount = StageStatus.MAX_STAGE_RETRIES
        }

        val feedback = readerManualOutcomeFeedback(
            chapterId = 1L,
            pageKey = "009.jpg",
            attemptActive = false,
            lookup = lookupFor(1L, "009.jpg", failed),
            nativeStall = null,
            durable = exhausted,
        )

        // The D9 fact rides with the durable page: the mapper record must be
        // the MANUAL_REQUIRED row, not a plain retryable failure.
        val expected = ReaderPageFeedbackState.ManualTruth(
            TranslationUiTruth.forManualOutcome(
                outcome = failed,
                durable = exhausted.toPageDisplayProjection(),
                exhausted = true,
            )!!,
        )
        feedback shouldBe expected
        (feedback as ReaderPageFeedbackState.ManualTruth).truth.retryMode shouldBe
            UiRetryMode.MANUAL_REQUIRED
    }

    @Test
    fun `T917 P5 reader-hop RED defect - stalled outcome carries the mapper stalled copy`() {
        val stalled = SinglePageOutcome.Stalled("009.jpg", stalledSinceEpochMs = 50L)

        val feedback = readerManualOutcomeFeedback(
            chapterId = 1L,
            pageKey = "009.jpg",
            attemptActive = false,
            lookup = lookupFor(1L, "009.jpg", stalled),
            nativeStall = null,
            durable = null,
        )

        feedback shouldBe manualTruthOf(stalled)
        val truth = (feedback as ReaderPageFeedbackState.ManualTruth).truth
        truth.label shouldBe "Translation stalled."
        truth.severity shouldBe UiSeverity.ERROR
    }

    @Test
    fun `T917 P5 reader-hop RED defect - rejected outcomes carry not-started and not-saved truth`() {
        val busy = SinglePageOutcome.Rejected(null, "page already translating")
        val busyFeedback = readerManualOutcomeFeedback(
            chapterId = 1L,
            pageKey = "009.jpg",
            attemptActive = false,
            lookup = lookupFor(1L, "009.jpg", busy),
            nativeStall = null,
            durable = null,
        )
        busyFeedback shouldBe manualTruthOf(busy)
        (busyFeedback as ReaderPageFeedbackState.ManualTruth).truth.label shouldBe
            "Translation not started — page already translating."

        val notSaved = SinglePageOutcome.Rejected(
            null,
            TranslationPipeline.REASON_TRANSLATION_NOT_SAVED,
        )
        val notSavedFeedback = readerManualOutcomeFeedback(
            chapterId = 1L,
            pageKey = "009.jpg",
            attemptActive = false,
            lookup = lookupFor(1L, "009.jpg", notSaved),
            nativeStall = null,
            durable = null,
        )
        notSavedFeedback shouldBe manualTruthOf(notSaved)
        (notSavedFeedback as ReaderPageFeedbackState.ManualTruth).truth.label shouldBe
            "Translation not saved — retry required."
    }

    @Test
    fun `T917 P5 reader-hop RED defect - attached and unresolved outcomes carry waiting-on-owner truth`() {
        val attached = SinglePageOutcome.Attached(PageWriteOrigin.BATCH)
        val attachedFeedback = readerManualOutcomeFeedback(
            chapterId = 1L,
            pageKey = "009.jpg",
            attemptActive = false,
            lookup = lookupFor(1L, "009.jpg", attached),
            nativeStall = null,
            durable = null,
        )
        attachedFeedback shouldBe manualTruthOf(attached)
        val attachedTruth = (attachedFeedback as ReaderPageFeedbackState.ManualTruth).truth
        attachedTruth.label shouldBe "Translating · batch job."
        attachedTruth.severity shouldBe UiSeverity.PROGRESS

        val unresolved = SinglePageOutcome.AttachedUnresolved(PageWriteOrigin.AUTO, "wait bound")
        val unresolvedFeedback = readerManualOutcomeFeedback(
            chapterId = 1L,
            pageKey = "009.jpg",
            attemptActive = false,
            lookup = lookupFor(1L, "009.jpg", unresolved),
            nativeStall = null,
            durable = null,
        )
        unresolvedFeedback shouldBe manualTruthOf(unresolved)
        (unresolvedFeedback as ReaderPageFeedbackState.ManualTruth).truth.label shouldBe
            "Background translation did not finish yet."
    }

    // ------------------------------------------------------------------
    // Identity fencing (chapter + pageKey) and attempt boundary
    // ------------------------------------------------------------------

    @Test
    fun `T917 P5 reader-hop RED defect - outcome for a foreign chapter or pageKey never renders on this page`() {
        val paused = SinglePageOutcome.Paused(null)
        // Fake of the bounded scheduler map: the outcome belongs to chapter 1 page 008.
        val map = mapOf("1:008.jpg" to paused)
        val lookup = { chapterId: Long, pageKey: String -> map["$chapterId:$pageKey"] }

        // A late outcome for page 008 must never render on page 009's holder.
        readerManualOutcomeFeedback(1L, "009.jpg", false, lookup, null, null) shouldBe null
        // ...nor on the same pageKey of another chapter.
        readerManualOutcomeFeedback(2L, "008.jpg", false, lookup, null, null) shouldBe null
        // The owning identity renders the mapper's truth.
        readerManualOutcomeFeedback(1L, "008.jpg", false, lookup, null, null) shouldBe
            manualTruthOf(paused)
    }

    @Test
    fun `T917 P5 reader-hop RED defect - native stall renders only on the stalled pageKey`() {
        // The D8 stall flow is fenced by the same pageKey comparison: the
        // stalled page renders stall truth even without a scheduler outcome...
        val ownStall = NativeStallState(
            token = 1L,
            pageKey = "009.jpg",
            startedAtEpochMs = 10L,
            stalledAtEpochMs = 999L,
        )
        val ownFeedback = readerManualOutcomeFeedback(
            chapterId = 1L,
            pageKey = "009.jpg",
            attemptActive = false,
            lookup = { _, _ -> null },
            nativeStall = ownStall,
            durable = null,
        )
        ownFeedback shouldBe manualTruthOf(SinglePageOutcome.Stalled("009.jpg", 999L))
        val truth = (ownFeedback as ReaderPageFeedbackState.ManualTruth).truth
        truth.label shouldBe "Translation stalled."

        // ...and a stall on any other page never renders here.
        val foreignStall = NativeStallState(
            token = 2L,
            pageKey = "010.jpg",
            startedAtEpochMs = 10L,
            stalledAtEpochMs = 999L,
        )
        readerManualOutcomeFeedback(1L, "009.jpg", false, { _, _ -> null }, foreignStall, null) shouldBe
            null
    }

    @Test
    fun `T917 P5 reader-hop RED defect - live attempt and missing identity keep the existing rendered state`() {
        val paused = SinglePageOutcome.Paused(null)

        // A live durable attempt always owns the chip (Ticket-04 precedence):
        // a previous intent's truth must not paint over live stages.
        readerManualOutcomeFeedback(
            chapterId = 1L,
            pageKey = "009.jpg",
            attemptActive = true,
            lookup = lookupFor(1L, "009.jpg", paused),
            nativeStall = null,
            durable = null,
        ) shouldBe null

        // Unknown identity (no chapter id / page key) projects nothing.
        readerManualOutcomeFeedback(null, "009.jpg", false, { _, _ -> paused }, null, null) shouldBe null
        readerManualOutcomeFeedback(1L, null, false, { _, _ -> paused }, null, null) shouldBe null
    }

    @Test
    fun `T917 P5 reader-hop RED defect - completed outcome leaves the existing rendered state standing`() {
        // Completed is already rendered truthfully from the durable display;
        // the join must not wrap it in a second truth layer.
        readerManualOutcomeFeedback(
            chapterId = 1L,
            pageKey = "009.jpg",
            attemptActive = false,
            lookup = lookupFor(1L, "009.jpg", SinglePageOutcome.Completed),
            nativeStall = null,
            durable = displayReadyPage(),
        ) shouldBe null
        readerManualOutcomeFeedback(1L, "009.jpg", false, { _, _ -> null }, null, null) shouldBe null
    }

    @Test
    fun `T917 P5 reader-hop RED defect - typed truth defers to a newer terminal auto slot`() {
        // Spec §1.2 rule 6: transient/typed states are shown only while no
        // newer durable terminal result supersedes them — an auto Ready slot
        // for the page outranks a paused manual truth in the existing select.
        val pausedTruth = manualTruthOf(SinglePageOutcome.Paused(null))
        selectReaderPageFeedback(
            durableFeedback = pausedTruth,
            autoFeedback = ReaderPageFeedbackState.Translated,
            durableAttemptActive = false,
        ) shouldBe ReaderPageFeedbackState.Translated
        // Without a terminal auto slot, the truth stands.
        selectReaderPageFeedback(
            durableFeedback = pausedTruth,
            autoFeedback = null,
            durableAttemptActive = false,
        ) shouldBe pausedTruth
    }

    // ------------------------------------------------------------------
    // Coalescer participation
    // ------------------------------------------------------------------

    @Test
    fun `T917 P5 reader-hop RED defect - typed outcome truth is not dropped by stage-rank coalescing`() {
        val coalescer = ReaderTranslationFeedbackCoalescer()
        val pausedTruth = manualTruthOf(SinglePageOutcome.Paused(null))

        coalescer.submit(pausedTruth, nowMs = 0L) shouldBe pausedTruth
        // Repeated identical truth must not re-render.
        coalescer.submit(pausedTruth, nowMs = 1L) shouldBe null
        // A terminal truth closes the attempt: stale stage callbacks cannot
        // regress the pill to an earlier stage.
        coalescer.submit(ReaderPageFeedbackState.ReadingText, nowMs = 2L) shouldBe null
        // A NEWER typed outcome supersedes the displayed terminal truth.
        val newer = manualTruthOf(SinglePageOutcome.Rejected(null, "page already translating"))
        coalescer.submit(newer, nowMs = 3L) shouldBe newer
    }

    @Test
    fun `T917 P5 reader-hop RED defect - progress truth displays immediately without closing the attempt`() {
        val coalescer = ReaderTranslationFeedbackCoalescer()
        val attachedTruth = manualTruthOf(SinglePageOutcome.Attached(PageWriteOrigin.MANUAL))

        coalescer.submit(attachedTruth, nowMs = 0L) shouldBe attachedTruth
        // The owner's stage progression continues after an attached truth.
        coalescer.submit(ReaderPageFeedbackState.ReadingText, nowMs = 1L) shouldBe null
        coalescer.flush(nowMs = 121L) shouldBe ReaderPageFeedbackState.ReadingText
    }

    // ------------------------------------------------------------------
    // Rolling-auto slot truth (forAutoSlot)
    // ------------------------------------------------------------------

    @Test
    fun `T917 P5 reader-hop RED defect - rolling auto slots carry the shared forAutoSlot truth`() {
        val identity = AutoChapterIdentity(chapterId = 7L, sessionKey = "s")
        val snapshot = AutoTranslationSnapshot(
            identity = identity,
            visiblePageIndex = 0,
            configuredAheadTarget = 2,
            availableAheadTarget = 2,
            foreground = AutoWindowSlot(0, AutoSlotState.Ready),
            aheadSlots = listOf(
                AutoWindowSlot(1, AutoSlotState.Deferred(AutoDeferralReason.Memory)),
                AutoWindowSlot(2, AutoSlotState.Failed(retryable = false)),
            ),
        )

        val projected = projectReaderAutoTranslationUiState(snapshot, identity)
        // The projection must fill the truth from the slot's own AutoSlotState
        // via the pure mapper — not a constant.
        projected.orderedSlots.first { it.pageIndex == 0 }.truth shouldBe
            TranslationUiTruth.forAutoSlot(AutoSlotState.Ready)
        projected.orderedSlots.first { it.pageIndex == 1 }.truth shouldBe
            TranslationUiTruth.forAutoSlot(AutoSlotState.Deferred(AutoDeferralReason.Memory))
        projected.orderedSlots.first { it.pageIndex == 2 }.truth.retryMode shouldBe
            UiRetryMode.NONE
        projected.orderedSlots.first { it.pageIndex == 2 }.truth.label shouldBe
            "Auto failed — action required."
    }

    @Test
    fun `T917 P5 reader-hop RED defect - auto failure truth keeps retryable and action-required wording distinct`() {
        val identity = AutoChapterIdentity(chapterId = 7L, sessionKey = "s")

        fun slotFor(state: AutoSlotState): ReaderAutoTranslationSlot {
            val snapshot = AutoTranslationSnapshot(
                identity = identity,
                visiblePageIndex = 0,
                configuredAheadTarget = 1,
                availableAheadTarget = 1,
                foreground = null,
                aheadSlots = listOf(AutoWindowSlot(1, state)),
            )
            return projectReaderAutoTranslationUiState(snapshot, identity).orderedSlots.single()
        }

        slotFor(AutoSlotState.Failed(retryable = true)).truth.label shouldBe
            "Auto failed — will retry when available."
        slotFor(AutoSlotState.Failed(retryable = false)).truth.label shouldBe
            "Auto failed — action required."
        slotFor(AutoSlotState.Failed(retryable = true)).truth.retryMode shouldBe
            UiRetryMode.AUTOMATIC
        slotFor(AutoSlotState.Failed(retryable = false)).truth.retryMode shouldBe
            UiRetryMode.NONE
    }
}
