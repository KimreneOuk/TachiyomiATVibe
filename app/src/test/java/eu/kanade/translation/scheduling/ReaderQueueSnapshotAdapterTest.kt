package eu.kanade.translation.scheduling

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.SinglePageOutcome
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.pipeline.execution.TranslationStageEvent
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * Snapshot adapter correctness vs queue truth: live demand phases and stage
 * events project directly, terminal outcomes project per their typed shape,
 * and Completed is never readiness proof without durable store truth.
 */
class ReaderQueueSnapshotAdapterTest {

    private val identity = AutoChapterIdentity(chapterId = 1L, sessionKey = "adapter-session")

    private fun blank(pageKey: String) = PageTranslation(sourceFileName = pageKey)

    private fun displayReady(pageKey: String) = PageTranslation(
        sourceFileName = pageKey,
        renderStatus = StageStatus.READY,
        translationStatus = StageStatus.READY,
        ocrStatus = StageStatus.READY,
        inpaintStatus = StageStatus.READY,
        cleanedImageName = "$pageKey.cleaned.png",
        inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION,
        blocks = mutableListOf(
            TranslationBlock(
                text = "源",
                translation = "source",
                width = 10f,
                height = 10f,
                x = 0f,
                y = 0f,
                symHeight = 1f,
                symWidth = 1f,
                angle = 0f,
            ),
        ),
    )

    private fun spec(
        visible: Int = 0,
        ahead: Int = 1,
        pageCount: Int = 3,
        ownerVersion: Long = 7L,
        windowVersion: Long = 3L,
    ) = ReaderQueueWindowSpec(
        identity = identity,
        session = mockSession(),
        token = ReaderSessionToken("adapter-session", 1L),
        visiblePageIndex = visible,
        configuredAheadTarget = ahead,
        pageCount = pageCount,
        ownerVersion = ownerVersion,
        windowVersion = windowVersion,
        pageKeysByIndex = (0 until pageCount).associate { it to "p$it" },
    )

    private fun mockSession(): TranslationSession {
        val manga = mockk<tachiyomi.domain.manga.model.Manga>(relaxed = true)
        val chapter = mockk<tachiyomi.domain.chapter.model.Chapter>(relaxed = true)
        val source = mockk<eu.kanade.tachiyomi.source.online.HttpSource>(relaxed = true)
        return TranslationSession("adapter-session", manga, chapter, source, mockk(relaxed = true))
    }

    private fun live(
        pageKey: String,
        phase: ReaderSlotPhase,
        stage: TranslationStageEvent? = null,
        deferral: AutoDeferralReason? = null,
    ) = pageKey to ReaderSlotObservation(
        pageKey = pageKey,
        readerPageIndex = 0,
        explicit = false,
        phase = phase,
        stage = stage,
        deferral = deferral,
    )

    @Test
    fun `queued demand projects Queued for the foreground and ahead slots`() {
        val snapshot = ReaderQueueSnapshotAdapter.project(
            spec = spec(),
            view = ReaderQueueView(mapOf(live("p0", ReaderSlotPhase.QUEUED)), emptyMap()),
            storePages = mapOf("p0" to blank("p0"), "p1" to blank("p1")),
            nowEpochMs = 1_000L,
        )
        snapshot.foreground?.state shouldBe AutoSlotState.Queued
        snapshot.aheadSlots.map { it.state } shouldBe listOf(AutoSlotState.Queued)
        snapshot.readyAheadCount shouldBe 0
    }

    @Test
    fun `running stage events project the live pipeline stage`() {
        val snapshot = ReaderQueueSnapshotAdapter.project(
            spec = spec(),
            view = ReaderQueueView(mapOf(live("p0", ReaderSlotPhase.RUNNING, TranslationStageEvent.TRANSLATING)), emptyMap()),
            storePages = emptyMap(),
            nowEpochMs = 1_000L,
        )
        snapshot.foreground?.state shouldBe AutoSlotState.Translating
    }

    @Test
    fun `deferred demand projects its real deferral reason`() {
        val snapshot = ReaderQueueSnapshotAdapter.project(
            spec = spec(visible = 0, ahead = 1),
            view = ReaderQueueView(
                mapOf(
                    live("p0", ReaderSlotPhase.DEFERRED, deferral = AutoDeferralReason.SourceUnavailable),
                    live("p1", ReaderSlotPhase.DEFERRED, deferral = AutoDeferralReason.Memory),
                ),
                emptyMap(),
            ),
            storePages = emptyMap(),
            nowEpochMs = 1_000L,
        )
        snapshot.foreground?.state shouldBe AutoSlotState.Deferred(AutoDeferralReason.SourceUnavailable)
        snapshot.activeDeferral shouldBe AutoDeferralReason.Memory
        snapshot.status shouldBe AutoActivityStatus.Paused
    }

    @Test
    fun `terminal failure projects Failed and is not replaced by store readiness`() {
        val snapshot = ReaderQueueSnapshotAdapter.project(
            spec = spec(),
            view = ReaderQueueView(
                emptyMap(),
                mapOf("p1" to SinglePageOutcome.Failed("p1", "provider exploded")),
            ),
            storePages = mapOf("p1" to displayReady("p1")),
            nowEpochMs = 1_000L,
        )
        snapshot.aheadSlots.single().state shouldBe AutoSlotState.Failed(retryable = true)
        snapshot.failedAheadCount shouldBe 1
    }

    @Test
    fun `paused terminal with a future retry projects a Network deferral`() {
        val snapshot = ReaderQueueSnapshotAdapter.project(
            spec = spec(),
            view = ReaderQueueView(
                emptyMap(),
                mapOf("p1" to SinglePageOutcome.Paused(nextEligibleRetryAtEpochMs = 5_000L)),
            ),
            storePages = emptyMap(),
            nowEpochMs = 1_000L,
        )
        snapshot.aheadSlots.single().state shouldBe AutoSlotState.Deferred(AutoDeferralReason.Network)
    }

    @Test
    fun `completed outcome without store readiness is never Ready`() {
        val snapshot = ReaderQueueSnapshotAdapter.project(
            spec = spec(),
            view = ReaderQueueView(emptyMap(), mapOf("p1" to SinglePageOutcome.Completed)),
            storePages = mapOf("p1" to blank("p1")),
            nowEpochMs = 1_000L,
        )
        snapshot.aheadSlots.single().state shouldBe AutoSlotState.Queued
        snapshot.readyAheadCount shouldBe 0
    }

    @Test
    fun `durable display-ready page projects Ready for ahead slots`() {
        val snapshot = ReaderQueueSnapshotAdapter.project(
            spec = spec(),
            view = ReaderQueueView(emptyMap(), emptyMap()),
            storePages = mapOf("p1" to displayReady("p1")),
            nowEpochMs = 1_000L,
        )
        snapshot.aheadSlots.single().state shouldBe AutoSlotState.Ready
        snapshot.readyAheadCount shouldBe 1
    }

    @Test
    fun `a display-ready visible page synthesizes no foreground slot`() {
        val snapshot = ReaderQueueSnapshotAdapter.project(
            spec = spec(visible = 0, ahead = 0, pageCount = 1),
            view = ReaderQueueView(emptyMap(), emptyMap()),
            storePages = mapOf("p0" to displayReady("p0")),
            nowEpochMs = 1_000L,
        )
        snapshot.foreground shouldBe null
        snapshot.status shouldBe AutoActivityStatus.FullyReady
    }

    @Test
    fun `window bounds clamp ahead slots at the chapter end`() {
        val snapshot = ReaderQueueSnapshotAdapter.project(
            spec = spec(visible = 2, ahead = 3, pageCount = 3),
            view = ReaderQueueView(emptyMap(), emptyMap()),
            storePages = emptyMap(),
            nowEpochMs = 1_000L,
        )
        snapshot.availableAheadTarget shouldBe 0
        snapshot.aheadSlots shouldBe emptyList()
    }

    @Test
    fun `owner and window versions pass through for reader fencing`() {
        val snapshot = ReaderQueueSnapshotAdapter.project(
            spec = spec(ownerVersion = 41L, windowVersion = 12L),
            view = ReaderQueueView(emptyMap(), emptyMap()),
            storePages = emptyMap(),
            nowEpochMs = 1_000L,
        )
        snapshot.ownerVersion shouldBe 41L
        snapshot.windowVersion shouldBe 12L
        snapshot.identity shouldBe identity
    }
}
