package eu.kanade.translation.scheduling

import eu.kanade.translation.model.PageTranslationView
import eu.kanade.translation.model.SinglePageOutcome
import eu.kanade.translation.model.isTranslationDisplayReady
import eu.kanade.translation.pipeline.execution.TranslationStageEvent

/** Live phase of one queue-owned demand, as projected for the reader snapshot. */
enum class ReaderSlotPhase { QUEUED, DEFERRED, RUNNING }

/**
 * Typed observation of one live queue demand. Terminal truth is carried
 * separately by the queue's outcome map; this type only describes work that is
 * queued, deferred, or executing right now.
 */
data class ReaderSlotObservation(
    val pageKey: String,
    val readerPageIndex: Int,
    val explicit: Boolean,
    val phase: ReaderSlotPhase,
    val stage: TranslationStageEvent?,
    val deferral: AutoDeferralReason?,
)

/** Atomic view of the queue for one bound session: live demands plus retained terminal outcomes. */
data class ReaderQueueView(
    val live: Map<String, ReaderSlotObservation>,
    val terminals: Map<String, SinglePageOutcome>,
)

/**
 * Window parameters the scheduler captures per [TranslationScheduler.updateAutoWindow]
 * call so the temporary queue-outcome adapter can project an
 * [AutoTranslationSnapshot] without touching the retired rolling coordinator.
 */
data class ReaderQueueWindowSpec(
    val identity: AutoChapterIdentity,
    val session: TranslationSession,
    val token: ReaderSessionToken,
    val visiblePageIndex: Int,
    val configuredAheadTarget: Int,
    val pageCount: Int,
    val ownerVersion: Long,
    val windowVersion: Long,
    /** Resolved pageKey per window page index; filled by the viewport resolver wrapper. */
    val pageKeysByIndex: Map<Int, String>,
)

/**
 * Temporary S2 adapter: projects queue typed demand/outcome truth plus durable
 * store truth into the [AutoTranslationSnapshot] the un-migrated reader UI
 * observes. Removed in S4 together with `observeAutoSnapshot`.
 *
 * Truth rules (handoff settled decision 7):
 * - a live demand projects its real phase, stage, and deferral reason;
 * - [SinglePageOutcome.Completed] alone is never readiness proof — the durable
 *   store's display-ready gate decides Ready;
 * - a page with no demand and no store readiness projects Queued when the
 *   window still targets it, mirroring the rolling coordinator's contract.
 */
object ReaderQueueSnapshotAdapter {

    fun project(
        spec: ReaderQueueWindowSpec,
        view: ReaderQueueView,
        storePages: Map<String, PageTranslationView>,
        nowEpochMs: Long,
    ): AutoTranslationSnapshot {
        val bounds = AutoWindowBounds(
            visiblePageIndex = spec.visiblePageIndex,
            configuredAheadTarget = spec.configuredAheadTarget,
            pageCount = spec.pageCount,
        )
        fun pageKeyAt(index: Int): String? = spec.pageKeysByIndex[index]

        fun slotState(pageKey: String?): AutoSlotState? {
            if (pageKey == null) return null
            view.live[pageKey]?.let { live ->
                return when (live.phase) {
                    ReaderSlotPhase.QUEUED -> AutoSlotState.Queued
                    ReaderSlotPhase.DEFERRED ->
                        AutoSlotState.Deferred(live.deferral ?: AutoDeferralReason.SourceUnavailable)
                    ReaderSlotPhase.RUNNING ->
                        when (live.stage) {
                            TranslationStageEvent.READING -> AutoSlotState.ReadingText
                            TranslationStageEvent.CLEANING -> AutoSlotState.Cleaning
                            TranslationStageEvent.TRANSLATING -> AutoSlotState.Translating
                            TranslationStageEvent.RENDERING -> AutoSlotState.Rendering
                            null -> AutoSlotState.Queued
                        }
                }
            }
            when (val terminal = view.terminals[pageKey]) {
                is SinglePageOutcome.Failed -> return AutoSlotState.Failed(retryable = true)
                is SinglePageOutcome.Stalled -> return AutoSlotState.Failed(retryable = true)
                is SinglePageOutcome.Paused -> {
                    val eligibleNow = (terminal.nextEligibleRetryAtEpochMs ?: 0L) <= nowEpochMs
                    return if (eligibleNow) AutoSlotState.Queued else AutoSlotState.Deferred(AutoDeferralReason.Network)
                }
                else -> Unit
            }
            val storePage = storePages[pageKey]
            if (storePage != null && storePage.isTranslationDisplayReady) return AutoSlotState.Ready
            return null
        }

        val foreground = if (!bounds.hasVisiblePage) {
            null
        } else {
            val visibleKey = pageKeyAt(spec.visiblePageIndex)
            val live = visibleKey?.let { view.live[it] }
            when {
                live != null -> AutoWindowSlot(
                    spec.visiblePageIndex,
                    when (live.phase) {
                        ReaderSlotPhase.QUEUED -> AutoSlotState.Queued
                        ReaderSlotPhase.DEFERRED ->
                            AutoSlotState.Deferred(live.deferral ?: AutoDeferralReason.SourceUnavailable)
                        ReaderSlotPhase.RUNNING ->
                            when (live.stage) {
                                TranslationStageEvent.READING -> AutoSlotState.ReadingText
                                TranslationStageEvent.CLEANING -> AutoSlotState.Cleaning
                                TranslationStageEvent.TRANSLATING -> AutoSlotState.Translating
                                TranslationStageEvent.RENDERING -> AutoSlotState.Rendering
                                null -> AutoSlotState.Queued
                            }
                    },
                )
                visibleKey != null && view.terminals[visibleKey] is SinglePageOutcome.Failed ->
                    AutoWindowSlot(spec.visiblePageIndex, AutoSlotState.Failed(retryable = true))
                visibleKey != null && view.terminals[visibleKey] is SinglePageOutcome.Stalled ->
                    AutoWindowSlot(spec.visiblePageIndex, AutoSlotState.Failed(retryable = true))
                else -> {
                    val storePage = visibleKey?.let { storePages[it] }
                    val ready = storePage?.isTranslationDisplayReady == true
                    if (ready) null else AutoWindowSlot(spec.visiblePageIndex, AutoSlotState.Queued)
                }
            }
        }

        val aheadSlots = bounds.aheadPageIndices.map { index ->
            val key = pageKeyAt(index)
            AutoWindowSlot(index, slotState(key) ?: AutoSlotState.Queued)
        }

        return AutoTranslationSnapshot(
            identity = spec.identity,
            visiblePageIndex = spec.visiblePageIndex,
            configuredAheadTarget = spec.configuredAheadTarget,
            availableAheadTarget = bounds.availableAheadTarget,
            foreground = foreground,
            aheadSlots = aheadSlots,
            ownerVersion = spec.ownerVersion,
            windowVersion = spec.windowVersion,
        )
    }
}
