package eu.kanade.translation.scheduling

import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Focused unit coverage for the pure rolling auto-window state contract
 * ([AutoWindowBounds], [AutoSlotState], [AutoTranslationSnapshot]). No Android,
 * coroutines, or storage — the contract is deterministic and transient.
 */
class AutoWindowStateTest {

    private val identity = AutoChapterIdentity(chapterId = 1L, sessionKey = "session-1")

    // ---- AutoWindowBounds: pure desired-window geometry ----

    @Test
    fun `normal window targets the next N pages excluding the visible page`() {
        val bounds = AutoWindowBounds(visiblePageIndex = 4, configuredAheadTarget = 3, pageCount = 10)

        bounds.aheadPageIndices shouldBe listOf(5, 6, 7)
        bounds.availableAheadTarget shouldBe 3
        bounds.hasVisiblePage shouldBe true
    }

    @Test
    fun `first page window starts at page 1`() {
        val bounds = AutoWindowBounds(visiblePageIndex = 0, configuredAheadTarget = 3, pageCount = 10)

        bounds.aheadPageIndices shouldBe listOf(1, 2, 3)
        bounds.availableAheadTarget shouldBe 3
    }

    @Test
    fun `last page window has no ahead target and reports zero available`() {
        val bounds = AutoWindowBounds(visiblePageIndex = 9, configuredAheadTarget = 3, pageCount = 10)

        bounds.aheadPageIndices shouldBe emptyList()
        bounds.availableAheadTarget shouldBe 0
        bounds.hasVisiblePage shouldBe true
    }

    @Test
    fun `penultimate page clamps to the single remaining ahead page`() {
        val bounds = AutoWindowBounds(visiblePageIndex = 8, configuredAheadTarget = 3, pageCount = 10)

        bounds.aheadPageIndices shouldBe listOf(9)
        bounds.availableAheadTarget shouldBe 1
    }

    @Test
    fun `chapter shorter than N clamps to the remaining pages without error`() {
        val bounds = AutoWindowBounds(visiblePageIndex = 0, configuredAheadTarget = 5, pageCount = 3)

        bounds.aheadPageIndices shouldBe listOf(1, 2)
        bounds.availableAheadTarget shouldBe 2
    }

    @Test
    fun `N of zero targets no ahead pages`() {
        val bounds = AutoWindowBounds(visiblePageIndex = 4, configuredAheadTarget = 0, pageCount = 10)

        bounds.aheadPageIndices shouldBe emptyList()
        bounds.availableAheadTarget shouldBe 0
    }

    @Test
    fun `empty chapter has no visible page and no ahead target`() {
        val bounds = AutoWindowBounds(visiblePageIndex = 0, configuredAheadTarget = 3, pageCount = 0)

        bounds.aheadPageIndices shouldBe emptyList()
        bounds.availableAheadTarget shouldBe 0
        bounds.hasVisiblePage shouldBe false
    }

    @Test
    fun `out-of-bounds visible page reports no ahead target`() {
        val bounds = AutoWindowBounds(visiblePageIndex = 12, configuredAheadTarget = 3, pageCount = 10)

        bounds.aheadPageIndices shouldBe emptyList()
        bounds.availableAheadTarget shouldBe 0
        bounds.hasVisiblePage shouldBe false
    }

    @Test
    fun `bounds rejects negative inputs`() {
        assertThrows<IllegalArgumentException> {
            AutoWindowBounds(visiblePageIndex = -1, configuredAheadTarget = 3, pageCount = 10)
        }
        assertThrows<IllegalArgumentException> {
            AutoWindowBounds(visiblePageIndex = 4, configuredAheadTarget = -1, pageCount = 10)
        }
        assertThrows<IllegalArgumentException> {
            AutoWindowBounds(visiblePageIndex = 4, configuredAheadTarget = 3, pageCount = -1)
        }
    }

    // ---- readyAheadCount: only display-ready ahead slots ----

    @Test
    fun `readyAheadCount counts only Ready ahead slots`() {
        val snapshot = AutoTranslationSnapshot(
            identity = identity,
            visiblePageIndex = 4,
            configuredAheadTarget = 3,
            availableAheadTarget = 3,
            foreground = AutoWindowSlot(4, AutoSlotState.Ready),
            aheadSlots = listOf(
                AutoWindowSlot(5, AutoSlotState.Ready),
                AutoWindowSlot(6, AutoSlotState.Translating),
                AutoWindowSlot(7, AutoSlotState.Queued),
            ),
        )

        snapshot.readyAheadCount shouldBe 1
    }

    @Test
    fun `readyAheadCount never counts the visible foreground page`() {
        val snapshot = AutoTranslationSnapshot(
            identity = identity,
            visiblePageIndex = 4,
            configuredAheadTarget = 3,
            availableAheadTarget = 3,
            foreground = AutoWindowSlot(4, AutoSlotState.Ready),
            aheadSlots = listOf(
                AutoWindowSlot(5, AutoSlotState.Queued),
                AutoWindowSlot(6, AutoSlotState.ReadingText),
                AutoWindowSlot(7, AutoSlotState.Cleaning),
            ),
        )

        snapshot.readyAheadCount shouldBe 0
    }

    @Test
    fun `failed and deferred ahead slots are not counted as ready`() {
        val snapshot = AutoTranslationSnapshot(
            identity = identity,
            visiblePageIndex = 4,
            configuredAheadTarget = 3,
            availableAheadTarget = 3,
            foreground = AutoWindowSlot(4, AutoSlotState.Ready),
            aheadSlots = listOf(
                AutoWindowSlot(5, AutoSlotState.Ready),
                AutoWindowSlot(6, AutoSlotState.Deferred(AutoDeferralReason.Memory)),
                AutoWindowSlot(7, AutoSlotState.Failed(retryable = true)),
            ),
        )

        snapshot.readyAheadCount shouldBe 1
        snapshot.failedAheadCount shouldBe 1
    }

    // ---- foreground separate from ordered ahead slots ----

    @Test
    fun `foreground is represented separately from the ahead slots`() {
        val snapshot = AutoTranslationSnapshot(
            identity = identity,
            visiblePageIndex = 4,
            configuredAheadTarget = 3,
            availableAheadTarget = 3,
            foreground = AutoWindowSlot(4, AutoSlotState.Translating),
            aheadSlots = listOf(
                AutoWindowSlot(5, AutoSlotState.Queued),
                AutoWindowSlot(6, AutoSlotState.Queued),
                AutoWindowSlot(7, AutoSlotState.Queued),
            ),
        )

        snapshot.foreground?.pageIndex shouldBe 4
        snapshot.aheadSlots.map { it.pageIndex } shouldBe listOf(5, 6, 7)
        snapshot.aheadSlots.map { it.pageIndex } shouldNotContain snapshot.visiblePageIndex
    }

    @Test
    fun `orderedSlots is foreground first then ahead by index`() {
        val snapshot = AutoTranslationSnapshot(
            identity = identity,
            visiblePageIndex = 4,
            configuredAheadTarget = 3,
            availableAheadTarget = 3,
            foreground = AutoWindowSlot(4, AutoSlotState.Queued),
            aheadSlots = listOf(
                AutoWindowSlot(5, AutoSlotState.Queued),
                AutoWindowSlot(6, AutoSlotState.Queued),
                AutoWindowSlot(7, AutoSlotState.Queued),
            ),
        )

        snapshot.orderedSlots.map { it.pageIndex } shouldBe listOf(4, 5, 6, 7)
    }

    @Test
    fun `orderedSlots omits a null foreground`() {
        val snapshot = AutoTranslationSnapshot(
            identity = identity,
            visiblePageIndex = 0,
            configuredAheadTarget = 0,
            availableAheadTarget = 0,
            foreground = null,
            aheadSlots = emptyList(),
        )

        snapshot.orderedSlots shouldBe emptyList()
    }

    @Test
    fun `snapshot rejects a foreground whose page is not the visible page`() {
        assertThrows<IllegalArgumentException> {
            AutoTranslationSnapshot(
                identity = identity,
                visiblePageIndex = 4,
                configuredAheadTarget = 3,
                availableAheadTarget = 3,
                foreground = AutoWindowSlot(5, AutoSlotState.Queued),
                aheadSlots = listOf(
                    AutoWindowSlot(6, AutoSlotState.Queued),
                    AutoWindowSlot(7, AutoSlotState.Queued),
                    AutoWindowSlot(8, AutoSlotState.Queued),
                ),
            )
        }
    }

    @Test
    fun `snapshot rejects an ahead slot that includes the visible page`() {
        assertThrows<IllegalArgumentException> {
            AutoTranslationSnapshot(
                identity = identity,
                visiblePageIndex = 4,
                configuredAheadTarget = 3,
                availableAheadTarget = 3,
                foreground = AutoWindowSlot(4, AutoSlotState.Queued),
                aheadSlots = listOf(
                    AutoWindowSlot(4, AutoSlotState.Queued),
                    AutoWindowSlot(6, AutoSlotState.Queued),
                    AutoWindowSlot(7, AutoSlotState.Queued),
                ),
            )
        }
    }

    @Test
    fun `snapshot rejects unsorted ahead slots`() {
        assertThrows<IllegalArgumentException> {
            AutoTranslationSnapshot(
                identity = identity,
                visiblePageIndex = 4,
                configuredAheadTarget = 3,
                availableAheadTarget = 3,
                foreground = AutoWindowSlot(4, AutoSlotState.Queued),
                aheadSlots = listOf(
                    AutoWindowSlot(7, AutoSlotState.Queued),
                    AutoWindowSlot(6, AutoSlotState.Queued),
                    AutoWindowSlot(5, AutoSlotState.Queued),
                ),
            )
        }
    }

    @Test
    fun `snapshot rejects more ahead slots than the available target`() {
        assertThrows<IllegalArgumentException> {
            AutoTranslationSnapshot(
                identity = identity,
                visiblePageIndex = 4,
                configuredAheadTarget = 2,
                availableAheadTarget = 2,
                foreground = AutoWindowSlot(4, AutoSlotState.Queued),
                aheadSlots = listOf(
                    AutoWindowSlot(5, AutoSlotState.Queued),
                    AutoWindowSlot(6, AutoSlotState.Queued),
                    AutoWindowSlot(7, AutoSlotState.Queued),
                ),
            )
        }
    }

    // ---- stable equality for StateFlow / Compose observation ----

    @Test
    fun `equivalent snapshots are structurally equal and hash-equal`() {
        val a = fullyReadySnapshot()
        val b = fullyReadySnapshot()

        a shouldBe b
        a.hashCode() shouldBe b.hashCode()
        a.orderedSlots shouldBe b.orderedSlots
    }

    @Test
    fun `snapshots differing only in a slot state are not equal`() {
        val ready = fullyReadySnapshot()
        val queued = ready.copy(
            aheadSlots = ready.aheadSlots.map { it.copy(state = AutoSlotState.Queued) },
        )

        ready shouldNotBe queued
        ready.readyAheadCount shouldBe 3
        queued.readyAheadCount shouldBe 0
    }

    @Test
    fun `snapshots with different identity are not equal`() {
        val a = fullyReadySnapshot()
        val b = a.copy(identity = a.identity.copy(sessionKey = "session-2"))

        a shouldNotBe b
    }

    // ---- aggregate status + deferral derivation ----

    @Test
    fun `status is Working when any slot is queued or executing`() {
        val snapshot = AutoTranslationSnapshot(
            identity = identity,
            visiblePageIndex = 4,
            configuredAheadTarget = 3,
            availableAheadTarget = 3,
            foreground = AutoWindowSlot(4, AutoSlotState.ReadingText),
            aheadSlots = listOf(
                AutoWindowSlot(5, AutoSlotState.Ready),
                AutoWindowSlot(6, AutoSlotState.Queued),
                AutoWindowSlot(7, AutoSlotState.Deferred(AutoDeferralReason.Memory)),
            ),
        )

        snapshot.status shouldBe AutoActivityStatus.Working
        snapshot.activeDeferral shouldBe AutoDeferralReason.Memory
    }

    @Test
    fun `status is Paused when nothing is active and a slot is deferred`() {
        val snapshot = AutoTranslationSnapshot(
            identity = identity,
            visiblePageIndex = 4,
            configuredAheadTarget = 3,
            availableAheadTarget = 3,
            foreground = AutoWindowSlot(4, AutoSlotState.Ready),
            aheadSlots = listOf(
                AutoWindowSlot(5, AutoSlotState.Ready),
                AutoWindowSlot(6, AutoSlotState.Deferred(AutoDeferralReason.Memory)),
                AutoWindowSlot(7, AutoSlotState.Deferred(AutoDeferralReason.Network)),
            ),
        )

        snapshot.status shouldBe AutoActivityStatus.Paused
        snapshot.activeDeferral shouldBe AutoDeferralReason.Memory
    }

    @Test
    fun `status is FullyReady when foreground and every available ahead slot are ready`() {
        val snapshot = fullyReadySnapshot()

        snapshot.status shouldBe AutoActivityStatus.FullyReady
        snapshot.readyAheadCount shouldBe 3
        snapshot.activeDeferral shouldBe null
    }

    @Test
    fun `status is FullyReady at the last page with no ahead target`() {
        val snapshot = AutoTranslationSnapshot(
            identity = identity,
            visiblePageIndex = 9,
            configuredAheadTarget = 3,
            availableAheadTarget = 0,
            foreground = AutoWindowSlot(9, AutoSlotState.Ready),
            aheadSlots = emptyList(),
        )

        snapshot.status shouldBe AutoActivityStatus.FullyReady
        snapshot.readyAheadCount shouldBe 0
    }

    @Test
    fun `status is Idle when only failed slots remain and nothing is active`() {
        val snapshot = AutoTranslationSnapshot(
            identity = identity,
            visiblePageIndex = 4,
            configuredAheadTarget = 3,
            availableAheadTarget = 3,
            foreground = AutoWindowSlot(4, AutoSlotState.Ready),
            aheadSlots = listOf(
                AutoWindowSlot(5, AutoSlotState.Ready),
                AutoWindowSlot(6, AutoSlotState.Ready),
                AutoWindowSlot(7, AutoSlotState.Failed(retryable = true)),
            ),
        )

        snapshot.status shouldBe AutoActivityStatus.Idle
        snapshot.readyAheadCount shouldBe 2
    }

    @Test
    fun `deferral precedence picks memory over network and local compute`() {
        val snapshot = AutoTranslationSnapshot(
            identity = identity,
            visiblePageIndex = 4,
            configuredAheadTarget = 3,
            availableAheadTarget = 3,
            foreground = AutoWindowSlot(4, AutoSlotState.Ready),
            aheadSlots = listOf(
                AutoWindowSlot(5, AutoSlotState.Deferred(AutoDeferralReason.LocalComputeBusy)),
                AutoWindowSlot(6, AutoSlotState.Deferred(AutoDeferralReason.Network)),
                AutoWindowSlot(7, AutoSlotState.Deferred(AutoDeferralReason.Memory)),
            ),
        )

        snapshot.activeDeferral shouldBe AutoDeferralReason.Memory
    }

    @Test
    fun `slot state equality makes deferred and failed variants structural`() {
        AutoSlotState.Deferred(AutoDeferralReason.Memory) shouldBe AutoSlotState.Deferred(AutoDeferralReason.Memory)
        AutoSlotState.Failed(retryable = true) shouldBe AutoSlotState.Failed(retryable = true)
        AutoSlotState.Queued shouldBe AutoSlotState.Queued
        AutoSlotState.Ready.isReady shouldBe true
        AutoSlotState.Queued.isReady shouldBe false
        AutoSlotState.Queued.isActive shouldBe true
    }

    private fun fullyReadySnapshot(): AutoTranslationSnapshot = AutoTranslationSnapshot(
        identity = identity,
        visiblePageIndex = 4,
        configuredAheadTarget = 3,
        availableAheadTarget = 3,
        foreground = AutoWindowSlot(4, AutoSlotState.Ready),
        aheadSlots = listOf(
            AutoWindowSlot(5, AutoSlotState.Ready),
            AutoWindowSlot(6, AutoSlotState.Ready),
            AutoWindowSlot(7, AutoSlotState.Ready),
        ),
    )
}
