package eu.kanade.tachiyomi.ui.reader.viewer

import android.content.Context
import eu.kanade.tachiyomi.ui.reader.ReaderAutoTranslationSlotState
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.hasExhaustedRetries
import eu.kanade.translation.model.isStageCancelled
import eu.kanade.translation.model.isStageFailed
import eu.kanade.translation.model.isTranslationDisplayReady
import eu.kanade.translation.scheduling.AutoDeferralReason
import eu.kanade.translation.scheduling.AutoSlotState
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
    isTranslationDisplayReady -> ReaderPageFeedbackState.Translated
    isStageFailed -> ReaderPageFeedbackState.Failed(!hasExhaustedRetries)
    renderStatus == StageStatus.RUNNING -> ReaderPageFeedbackState.FinishingPage
    inpaintStatus == StageStatus.RUNNING -> ReaderPageFeedbackState.CleaningBubbles
    translationStatus == StageStatus.RUNNING -> ReaderPageFeedbackState.TranslatingText
    ocrStatus == StageStatus.RUNNING -> ReaderPageFeedbackState.ReadingText
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
}

private val AutoDeferralReason.localizedResource
    get() = when (this) {
        AutoDeferralReason.Memory -> ATMR.strings.reader_auto_reason_memory
        AutoDeferralReason.Network -> ATMR.strings.reader_auto_reason_network
        AutoDeferralReason.SourceUnavailable -> ATMR.strings.reader_auto_reason_source
        AutoDeferralReason.LocalComputeBusy -> ATMR.strings.reader_auto_reason_compute
    }
