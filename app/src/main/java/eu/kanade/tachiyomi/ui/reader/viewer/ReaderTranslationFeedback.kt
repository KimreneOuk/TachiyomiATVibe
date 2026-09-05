package eu.kanade.tachiyomi.ui.reader.viewer

import android.content.Context
import eu.kanade.tachiyomi.ui.reader.ReaderAutoTranslationSlotState
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.hasExhaustedRetries
import eu.kanade.translation.model.isCleanedImageReady
import eu.kanade.translation.model.isStageCancelled
import eu.kanade.translation.model.isStageFailed
import eu.kanade.translation.model.toPageDisplayProjection
import eu.kanade.translation.scheduling.AutoDeferralReason
import eu.kanade.translation.scheduling.AutoSlotState
import eu.kanade.translation.scheduling.SinglePageOutcome
import eu.kanade.translation.translator.NativeStallState
import eu.kanade.translation.ui.PageUiTruth
import eu.kanade.translation.ui.TranslationUiTruth
import eu.kanade.translation.ui.UiSeverity
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.at.ATMR

/**
 * Small, reader-only vocabulary for page feedback. It deliberately does not
 * expose scheduler internals or percentages: the page either has a real
 * stage, a displayable result, or a terminal failure.
 */
sealed interface ReaderPageFeedbackState {
    data object Queued : ReaderPageFeedbackState
    data object ReadingText : ReaderPageFeedbackState
    data object CleaningBubbles : ReaderPageFeedbackState
    data object TranslatingText : ReaderPageFeedbackState
    data object FinishingPage : ReaderPageFeedbackState
    data object Translated : ReaderPageFeedbackState
    data class Deferred(val reason: AutoDeferralReason) : ReaderPageFeedbackState
    data class Failed(val retryable: Boolean) : ReaderPageFeedbackState

    /**
     * T917 P5 (spec §0.2.2, §6.2.8): a shared pure-truth projection from
     * [TranslationUiTruth.forManualOutcome]. The record is produced by the
     * mapper — the reader renders its label/content description verbatim and
     * never reinterprets precedence or wording.
     */
    data class ManualTruth(val truth: PageUiTruth) : ReaderPageFeedbackState
}

/**
 * Maps the Ticket 04 reader contract to page presentation without allowing a
 * background slot to overwrite the visible page's stage.
 */
fun ReaderAutoTranslationSlotState.toReaderPageFeedback(): ReaderPageFeedbackState = when (this) {
    ReaderAutoTranslationSlotState.Queued -> ReaderPageFeedbackState.Queued
    ReaderAutoTranslationSlotState.ReadingText -> ReaderPageFeedbackState.ReadingText
    ReaderAutoTranslationSlotState.Cleaning -> ReaderPageFeedbackState.CleaningBubbles
    ReaderAutoTranslationSlotState.Translating -> ReaderPageFeedbackState.TranslatingText
    ReaderAutoTranslationSlotState.Rendering -> ReaderPageFeedbackState.FinishingPage
    ReaderAutoTranslationSlotState.Ready -> ReaderPageFeedbackState.Translated
    is ReaderAutoTranslationSlotState.Deferred -> ReaderPageFeedbackState.Deferred(reason)
    is ReaderAutoTranslationSlotState.Failed -> ReaderPageFeedbackState.Failed(retryable)
}

fun AutoSlotState.toReaderPageFeedback(): ReaderPageFeedbackState = when (this) {
    AutoSlotState.Queued -> ReaderPageFeedbackState.Queued
    AutoSlotState.ReadingText -> ReaderPageFeedbackState.ReadingText
    AutoSlotState.Cleaning -> ReaderPageFeedbackState.CleaningBubbles
    AutoSlotState.Translating -> ReaderPageFeedbackState.TranslatingText
    AutoSlotState.Rendering -> ReaderPageFeedbackState.FinishingPage
    AutoSlotState.Ready -> ReaderPageFeedbackState.Translated
    is AutoSlotState.Deferred -> ReaderPageFeedbackState.Deferred(reason)
    is AutoSlotState.Failed -> ReaderPageFeedbackState.Failed(retryable)
}

/**
 * Durable page state is still useful for manual translation and for the short
 * interval before the live Auto snapshot reaches a holder. Display readiness
 * wins over stage state so a late RUNNING emission cannot cover a translated
 * result.
 */
fun PageTranslation.toReaderPageFeedback(): ReaderPageFeedbackState? = when {
    toPageDisplayProjection().displayReady -> ReaderPageFeedbackState.Translated
    isStageFailed -> ReaderPageFeedbackState.Failed(!hasExhaustedRetries)
    renderStatus == StageStatus.RUNNING -> ReaderPageFeedbackState.FinishingPage
    inpaintStatus == StageStatus.RUNNING -> ReaderPageFeedbackState.CleaningBubbles
    translationStatus == StageStatus.RUNNING -> ReaderPageFeedbackState.TranslatingText
    ocrStatus == StageStatus.RUNNING && !isCleanedImageReady && inpaintStatus != StageStatus.READY -> ReaderPageFeedbackState.ReadingText
    isStageCancelled -> null
    else -> null
}

/**
 * Selects the visible page's owner before the coalescer sees a value. A
 * nonterminal durable attempt belongs to the manual/batch pipeline, so a stale
 * Auto slot cannot install a terminal fence over it. Durable terminal values
 * retain precedence even after the attempt has stopped running.
 */
internal fun selectReaderPageFeedback(
    durableFeedback: ReaderPageFeedbackState?,
    autoFeedback: ReaderPageFeedbackState?,
    durableAttemptActive: Boolean,
): ReaderPageFeedbackState? = when {
    durableAttemptActive -> durableFeedback
    durableFeedback is ReaderPageFeedbackState.Translated -> durableFeedback
    durableFeedback is ReaderPageFeedbackState.Failed -> durableFeedback
    else -> autoFeedback ?: durableFeedback
}

/**
 * T917 P5 (spec §0.2.2, §6.2.8, §6.2.10): identity-fenced join between the
 * scheduler's typed manual single-page outcome and THIS holder's chip truth.
 *
 * Identity fencing lives in the lookup: [lookup] must be the scheduler's
 * read-only accessor keyed by the holder's own (chapterId, pageKey), so an
 * outcome recorded for any other page or chapter is never visible here. The
 * D8 stall flow ([nativeStall]) is fenced by the same pageKey comparison and
 * only fills the stalled page when no scheduler outcome exists yet.
 *
 * All copy and precedence decisions stay in the pure
 * [TranslationUiTruth.forManualOutcome] mapper: this function wraps the
 * mapper's record verbatim. A `Completed` outcome keeps the existing durable
 * rendered state (returns null — nothing manual to project), and a live
 * durable attempt ([attemptActive]) always owns the chip.
 */
internal fun readerManualOutcomeFeedback(
    chapterId: Long?,
    pageKey: String?,
    attemptActive: Boolean,
    lookup: (chapterId: Long, pageKey: String) -> SinglePageOutcome?,
    nativeStall: NativeStallState?,
    durable: PageTranslation?,
): ReaderPageFeedbackState? {
    if (chapterId == null || pageKey == null) return null
    // A live durable attempt always owns the chip: the scheduler outcome being
    // projected belongs to a PREVIOUS intent, never to the stages on screen.
    if (attemptActive) return null
    // Identity fence: the accessor is keyed by THIS page's own identity, so a
    // late outcome for a foreign page/chapter is never visible here.
    val outcome = lookup(chapterId, pageKey) ?: run {
        // D8 stall consumption: the stall flow reflects the stalled pageKey;
        // fenced by the same key comparison it can only fill THIS page, and
        // only while no scheduler outcome exists yet.
        val stalled = nativeStall?.takeIf { it.pageKey == pageKey } ?: return null
        SinglePageOutcome.Stalled(stalled.pageKey, stalled.stalledAtEpochMs)
    }
    // Completed is already rendered truthfully from the durable display.
    if (outcome is SinglePageOutcome.Completed) return null
    val truth = TranslationUiTruth.forManualOutcome(
        outcome = outcome,
        durable = durable?.toPageDisplayProjection(),
        // The D9 repeated-interruption fact rides with the durable page.
        exhausted = durable?.hasExhaustedRetries == true,
    ) ?: return null
    return ReaderPageFeedbackState.ManualTruth(truth)
}

/** A delayed UI update; scheduling and image delivery remain immediate. */
class ReaderTranslationFeedbackCoalescer(
    private val minimumDisplayDurationMs: Long = DEFAULT_MINIMUM_DISPLAY_DURATION_MS,
) {
    private var displayed: ReaderPageFeedbackState? = null
    private var pending: ReaderPageFeedbackState? = null
    private var pendingSinceMs: Long = 0L
    private var highestStageRank = -1
    private var terminalDisplayed = false

    val hasPending: Boolean
        get() = pending != null

    fun submit(next: ReaderPageFeedbackState?, nowMs: Long): ReaderPageFeedbackState? {
        // A null emission is an explicit clear. It is intentionally different
        // from dismissing a terminal label: callers use this boundary to start
        // accepting a fresh attempt again.
        if (next == null) {
            reset()
            return null
        }

        // T917 P5: a typed manual outcome is a scheduler fact, not a stage —
        // it never participates in stage ranking and it supersedes an earlier
        // displayed truth (the map only ever holds the LATEST outcome for the
        // identity). A non-progress truth closes the attempt against stale
        // stage callbacks, exactly like a terminal failure.
        if (next is ReaderPageFeedbackState.ManualTruth) {
            if (next == displayed && pending == null) return null
            pending = null
            displayed = next
            if (next.truth.severity != UiSeverity.PROGRESS) terminalDisplayed = true
            return next
        }

        // Once a translated result or a terminal failure has been shown, a
        // delayed callback from the old attempt must not put an earlier stage
        // back on screen. The owner calls [beginAttempt] when a new attempt is
        // explicitly admitted.
        if (terminalDisplayed) return null

        // Deferrals are truthful immediate transitions, but they do not close
        // the attempt: a coordinator may resume the same attempt after a
        // memory/network/source pause. Stage ordering still applies when it
        // resumes.
        if (next is ReaderPageFeedbackState.Deferred) {
            if (next == displayed && pending == null) return null
            pending = null
            displayed = next
            return next
        }

        // A display-ready result or failure is never delayed. This keeps image
        // delivery and retry affordances independent of the UI coalescing timer.
        if (next is ReaderPageFeedbackState.Translated || next is ReaderPageFeedbackState.Failed) {
            if (next == displayed) return null
            pending = null
            displayed = next
            terminalDisplayed = true
            return next
        }

        val nextRank = next.stageRank
        // The live coordinator can deliver late callbacks from an earlier
        // stage. Keep the highest stage observed in this attempt, including a
        // pending stage, so neither displayed nor pending feedback can regress.
        if (nextRank <= highestStageRank) return null
        highestStageRank = nextRank

        if (displayed == null) {
            displayed = next
            pending = null
            return next
        }

        pending = next
        pendingSinceMs = nowMs
        return null
    }

    fun flush(nowMs: Long): ReaderPageFeedbackState? {
        val next = pending ?: return null
        if (nowMs - pendingSinceMs < minimumDisplayDurationMs) return null
        pending = null
        displayed = next
        return next
    }

    fun pendingDelayMs(nowMs: Long): Long? {
        if (pending == null) return null
        return (minimumDisplayDurationMs - (nowMs - pendingSinceMs)).coerceAtLeast(0L)
    }

    fun reset() {
        displayed = null
        pending = null
        pendingSinceMs = 0L
        highestStageRank = -1
        terminalDisplayed = false
    }

    /** Starts a new monotonic stage sequence after an explicit lifecycle boundary. */
    fun beginAttempt() = reset()

    /** Hides a terminal pill without reopening the old attempt to stale stages. */
    internal fun dismissTerminal() {
        displayed = null
        pending = null
        pendingSinceMs = 0L
    }

    private companion object {
        const val DEFAULT_MINIMUM_DISPLAY_DURATION_MS = 120L
    }
}

private val ReaderPageFeedbackState.stageRank: Int
    get() = when (this) {
        ReaderPageFeedbackState.Queued -> 0
        ReaderPageFeedbackState.ReadingText -> 1
        ReaderPageFeedbackState.CleaningBubbles -> 2
        ReaderPageFeedbackState.TranslatingText -> 3
        ReaderPageFeedbackState.FinishingPage -> 4
        ReaderPageFeedbackState.Translated,
        is ReaderPageFeedbackState.Deferred,
        is ReaderPageFeedbackState.Failed,
        is ReaderPageFeedbackState.ManualTruth,
        -> -1
    }

/**
 * Identity fence shared by delayed reader callbacks. Generation protects a
 * recycled holder; identity protects a replaced view within the same holder
 * generation.
 */
internal data class ReaderImageCallbackFence<T : Any>(
    val generation: Long,
    val view: T,
) {
    fun isCurrent(currentGeneration: Long, currentView: T?): Boolean =
        generation == currentGeneration && view === currentView

    /**
     * Dispatches a delayed callback only while its captured generation and
     * registered view are still current. Returning the dispatch result makes
     * this the production seam for callback tests rather than a predicate-only
     * assertion.
     */
    fun dispatchIfCurrent(currentGeneration: Long, currentView: T?, action: () -> Unit): Boolean {
        if (!isCurrent(currentGeneration, currentView)) return false
        action()
        return true
    }
}

fun ReaderPageFeedbackState.localizedLabel(context: Context): String = when (this) {
    ReaderPageFeedbackState.Queued -> context.stringResource(ATMR.strings.reader_auto_stage_queued)
    ReaderPageFeedbackState.ReadingText -> context.stringResource(ATMR.strings.reader_auto_stage_reading)
    ReaderPageFeedbackState.CleaningBubbles -> context.stringResource(ATMR.strings.reader_auto_stage_cleaning)
    ReaderPageFeedbackState.TranslatingText -> context.stringResource(ATMR.strings.reader_auto_stage_translating)
    ReaderPageFeedbackState.FinishingPage -> context.stringResource(ATMR.strings.reader_auto_stage_finishing)
    ReaderPageFeedbackState.Translated -> context.stringResource(ATMR.strings.reader_auto_stage_translated)
    is ReaderPageFeedbackState.Deferred -> context.stringResource(reason.localizedResource)
    is ReaderPageFeedbackState.Failed -> context.stringResource(ATMR.strings.reader_auto_stage_failed)
    // T917 P5: the shared mapper owns the copy; render its English source
    // label verbatim (the accepted pure-copy tradeoff, i18n debt carried).
    is ReaderPageFeedbackState.ManualTruth -> truth.label
}

private val AutoDeferralReason.localizedResource
    get() = when (this) {
        AutoDeferralReason.Memory -> ATMR.strings.reader_auto_reason_memory
        AutoDeferralReason.Network -> ATMR.strings.reader_auto_reason_network
        AutoDeferralReason.SourceUnavailable -> ATMR.strings.reader_auto_reason_source
        AutoDeferralReason.LocalComputeBusy -> ATMR.strings.reader_auto_reason_compute
    }
