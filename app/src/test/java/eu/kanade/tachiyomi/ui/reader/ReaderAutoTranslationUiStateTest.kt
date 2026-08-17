package eu.kanade.tachiyomi.ui.reader

import eu.kanade.translation.scheduling.AutoActivityStatus
import eu.kanade.translation.scheduling.AutoChapterIdentity
import eu.kanade.translation.scheduling.AutoDeferralReason
import eu.kanade.translation.scheduling.AutoSlotState
import eu.kanade.translation.scheduling.AutoTranslationSnapshot
import eu.kanade.translation.scheduling.AutoWindowBounds
import eu.kanade.translation.scheduling.AutoWindowSlot
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ReaderAutoTranslationUiStateTest {

    private val activeIdentity = AutoChapterIdentity(chapterId = 7L, sessionKey = "reader-a")

    @Test
    fun `every coordinator slot state maps to its matching reader state`() {
        val expected = listOf(
            AutoSlotState.Queued to ReaderAutoTranslationSlotState.Queued,
            AutoSlotState.ReadingText to ReaderAutoTranslationSlotState.ReadingText,
            AutoSlotState.Cleaning to ReaderAutoTranslationSlotState.Cleaning,
            AutoSlotState.Translating to ReaderAutoTranslationSlotState.Translating,
            AutoSlotState.Rendering to ReaderAutoTranslationSlotState.Rendering,
            AutoSlotState.Ready to ReaderAutoTranslationSlotState.Ready,
            AutoSlotState.Deferred(AutoDeferralReason.Memory) to
                ReaderAutoTranslationSlotState.Deferred(AutoDeferralReason.Memory),
            AutoSlotState.Deferred(AutoDeferralReason.LocalComputeBusy) to
                ReaderAutoTranslationSlotState.Deferred(AutoDeferralReason.LocalComputeBusy),
            AutoSlotState.Deferred(AutoDeferralReason.Network) to
                ReaderAutoTranslationSlotState.Deferred(AutoDeferralReason.Network),
            AutoSlotState.Deferred(AutoDeferralReason.SourceUnavailable) to
                ReaderAutoTranslationSlotState.Deferred(AutoDeferralReason.SourceUnavailable),
            AutoSlotState.Failed(retryable = true) to ReaderAutoTranslationSlotState.Failed(retryable = true),
        )

        expected.forEach { (sourceState, readerState) ->
            val uiState = projectReaderAutoTranslationUiState(
                snapshot = snapshot(
                    visiblePageIndex = 4,
                    foreground = AutoWindowSlot(4, sourceState),
                    aheadSlots = emptyList(),
                ),
                expectedIdentity = activeIdentity,
            )

            uiState.foreground?.pageIndex shouldBe 4
            uiState.foreground?.state shouldBe readerState
            uiState.orderedSlots.size shouldBe 1
            uiState.orderedSlots.single().pageIndex shouldBe 4
        }
    }

    @Test
    fun `queued ahead work does not replace visible stage or ready numerator`() {
        val uiState = projectReaderAutoTranslationUiState(
            snapshot = snapshot(
                visiblePageIndex = 4,
                foreground = AutoWindowSlot(4, AutoSlotState.Translating),
                aheadSlots = listOf(
                    AutoWindowSlot(5, AutoSlotState.Ready),
                    AutoWindowSlot(6, AutoSlotState.Queued),
                    AutoWindowSlot(7, AutoSlotState.ReadingText),
                ),
            ),
            expectedIdentity = activeIdentity,
        )

        uiState.foregroundStage shouldBe ReaderAutoTranslationSlotState.Translating
        uiState.readyAheadCount shouldBe 1
        uiState.availableAheadTarget shouldBe 3
        uiState.activity shouldBe AutoActivityStatus.Working
        uiState.orderedSlots.map { it.pageIndex } shouldBe listOf(4, 5, 6, 7)
    }

    @Test
    fun `window target keeps visible page separate from exactly N pages ahead`() {
        val bounds = AutoWindowBounds(
            visiblePageIndex = 4,
            configuredAheadTarget = 3,
            pageCount = 10,
        )

        bounds.aheadPageIndices shouldBe listOf(5, 6, 7)
        bounds.aheadPageIndices shouldNotContain bounds.visiblePageIndex
    }

    @Test
    fun `memory network and source deferrals stay distinguishable`() {
        listOf(
            AutoDeferralReason.Memory,
            AutoDeferralReason.Network,
            AutoDeferralReason.SourceUnavailable,
        ).forEach { reason ->
            val uiState = projectReaderAutoTranslationUiState(
                snapshot = snapshot(
                    visiblePageIndex = 4,
                    foreground = AutoWindowSlot(4, AutoSlotState.Ready),
                    aheadSlots = listOf(
                        AutoWindowSlot(5, AutoSlotState.Deferred(reason)),
                    ),
                    configuredAheadTarget = 1,
                ),
                expectedIdentity = activeIdentity,
            )

            uiState.pauseReason shouldBe reason
            uiState.activity shouldBe AutoActivityStatus.Paused
            uiState.aheadSlots.single().state shouldBe ReaderAutoTranslationSlotState.Deferred(reason)
        }
    }

    @Test
    fun `aggregate activity remains fully ready only when every available target is ready`() {
        val ready = projectReaderAutoTranslationUiState(
            snapshot = snapshot(
                visiblePageIndex = 4,
                foreground = AutoWindowSlot(4, AutoSlotState.Ready),
                aheadSlots = listOf(
                    AutoWindowSlot(5, AutoSlotState.Ready),
                    AutoWindowSlot(6, AutoSlotState.Ready),
                ),
                configuredAheadTarget = 3,
                availableAheadTarget = 2,
            ),
            expectedIdentity = activeIdentity,
        )
        val incomplete = projectReaderAutoTranslationUiState(
            snapshot = snapshot(
                visiblePageIndex = 4,
                foreground = AutoWindowSlot(4, AutoSlotState.Ready),
                aheadSlots = listOf(AutoWindowSlot(5, AutoSlotState.Ready)),
                configuredAheadTarget = 3,
            ),
            expectedIdentity = activeIdentity,
        )

        ready.activity shouldBe AutoActivityStatus.FullyReady
        ready.readyAheadCount shouldBe 2
        ready.availableAheadTarget shouldBe 2
        incomplete.activity shouldBe AutoActivityStatus.Idle
        incomplete.readyAheadCount shouldBe 1
        incomplete.availableAheadTarget shouldBe 3

        val failed = projectReaderAutoTranslationUiState(
            snapshot = snapshot(
                visiblePageIndex = 4,
                foreground = AutoWindowSlot(4, AutoSlotState.Failed(retryable = false)),
                aheadSlots = emptyList(),
            ),
            expectedIdentity = activeIdentity,
        )
        failed.activity shouldBe AutoActivityStatus.Idle
        failed.hasFailure shouldBe true
    }

    @Test
    fun `stale cross-chapter snapshot resets instead of replacing active window`() {
        val staleIdentity = AutoChapterIdentity(chapterId = 8L, sessionKey = "reader-b")
        val stale = projectReaderAutoTranslationUiState(
            snapshot = snapshot(
                identity = staleIdentity,
                visiblePageIndex = 1,
                foreground = AutoWindowSlot(1, AutoSlotState.ReadingText),
                aheadSlots = listOf(AutoWindowSlot(2, AutoSlotState.Queued)),
                configuredAheadTarget = 1,
            ),
            expectedIdentity = activeIdentity,
        )

        stale.identity shouldBe activeIdentity
        stale.visiblePageIndex shouldBe null
        stale.foreground shouldBe null
        stale.aheadSlots shouldBe emptyList()
        stale.activity shouldBe AutoActivityStatus.Idle
    }

    @Test
    fun `null snapshot resets transient state while retaining active identity`() {
        val reset = projectReaderAutoTranslationUiState(
            snapshot = null,
            expectedIdentity = activeIdentity,
        )

        reset.identity shouldBe activeIdentity
        reset.visiblePageIndex shouldBe null
        reset.orderedSlots shouldBe emptyList()
        reset.activity shouldBe AutoActivityStatus.Idle
    }

    @Test
    fun `rapid anchors retain only the newest identity-matching projection`() {
        val pageFour = projectReaderAutoTranslationUiState(
            snapshot = snapshot(
                visiblePageIndex = 4,
                foreground = AutoWindowSlot(4, AutoSlotState.Queued),
                aheadSlots = listOf(
                    AutoWindowSlot(5, AutoSlotState.Queued),
                    AutoWindowSlot(6, AutoSlotState.Queued),
                ),
                configuredAheadTarget = 2,
            ),
            expectedIdentity = activeIdentity,
        )
        val pageFive = projectReaderAutoTranslationUiState(
            snapshot = snapshot(
                visiblePageIndex = 5,
                foreground = AutoWindowSlot(5, AutoSlotState.Translating),
                aheadSlots = listOf(
                    AutoWindowSlot(6, AutoSlotState.Ready),
                    AutoWindowSlot(7, AutoSlotState.Queued),
                ),
                configuredAheadTarget = 2,
            ),
            expectedIdentity = activeIdentity,
        )
        val pageSix = projectReaderAutoTranslationUiState(
            snapshot = snapshot(
                visiblePageIndex = 6,
                foreground = AutoWindowSlot(6, AutoSlotState.Ready),
                aheadSlots = listOf(
                    AutoWindowSlot(7, AutoSlotState.Queued),
                    AutoWindowSlot(8, AutoSlotState.Queued),
                ),
                configuredAheadTarget = 2,
            ),
            expectedIdentity = activeIdentity,
        )

        pageFour.visiblePageIndex shouldBe 4
        pageFive.visiblePageIndex shouldBe 5
        pageSix.visiblePageIndex shouldBe 6
        pageSix.orderedSlots.map { it.pageIndex } shouldBe listOf(6, 7, 8)
    }

    @Test
    fun `projection carries scheduler owner and window fence`() {
        val uiState = projectReaderAutoTranslationUiState(
            snapshot = snapshot(
                visiblePageIndex = 4,
                foreground = AutoWindowSlot(4, AutoSlotState.Ready),
                aheadSlots = emptyList(),
                ownerVersion = 17L,
                windowVersion = 23L,
            ),
            expectedIdentity = activeIdentity,
        )

        uiState.ownerVersion shouldBe 17L
        uiState.windowVersion shouldBe 23L
    }

    private fun snapshot(
        identity: AutoChapterIdentity = activeIdentity,
        visiblePageIndex: Int,
        foreground: AutoWindowSlot?,
        aheadSlots: List<AutoWindowSlot>,
        configuredAheadTarget: Int = aheadSlots.size,
        availableAheadTarget: Int = configuredAheadTarget,
        ownerVersion: Long = 0L,
        windowVersion: Long = 0L,
    ): AutoTranslationSnapshot = AutoTranslationSnapshot(
        identity = identity,
        visiblePageIndex = visiblePageIndex,
        configuredAheadTarget = configuredAheadTarget,
        availableAheadTarget = availableAheadTarget,
        foreground = foreground,
        aheadSlots = aheadSlots,
        ownerVersion = ownerVersion,
        windowVersion = windowVersion,
    )
}
