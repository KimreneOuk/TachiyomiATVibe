package eu.kanade.translation.ui

import eu.kanade.translation.PageWriteOrigin
import eu.kanade.translation.TranslationPipeline
import eu.kanade.translation.model.PageDisplayProjection
import eu.kanade.translation.model.PageDisplayState
import eu.kanade.translation.scheduling.AutoSlotState
import eu.kanade.translation.scheduling.SinglePageOutcome

/**
 * T917 Phase 5 (spec §1.2, §2): bounded UI vocabulary projected FROM existing
 * typed outcomes, durable projections, and auto slot states. This is not a
 * new state machine and adds no scheduler state enum: every value here is a
 * presentation truth derived from store/scheduler facts, and every surface
 * (page chip, reader overlay, drawer, notification, accessibility) maps its
 * wording from this record so no surface can contradict another.
 *
 * Severity and retry-mode are presentation vocabulary, not scheduler states.
 * [PageUiTruth.terminalSuccess] is the ONLY flag a progress surface may use to
 * increment a translated-success count.
 */
enum class UiSeverity { PROGRESS, INFO, WARNING, ERROR }

/** Whether, and how, the user is invited to continue paused/failed work. */
enum class UiRetryMode {
    /** No continuation is offered or implied. */
    NONE,

    /** The system will continue by itself; no Retry button is implied. */
    AUTOMATIC,

    /** The user action starts a new attempt (explicit Retry). */
    EXPLICIT,

    /** The D9 repeated-interruption cap: manual retry required (force path). */
    MANUAL_REQUIRED,
}

/** Actions a surface may offer for a truth record. */
enum class UiAction { RETRY, CANCEL, DETAILS, REVIEW }

/**
 * One page's truthful presentation state. [label] values are the English
 * source wording from the state→surface matrix; surfaces localize them.
 */
data class PageUiTruth(
    val label: String,
    val severity: UiSeverity,
    val retryMode: UiRetryMode,
    val retryAtEpochMs: Long?,
    val actions: Set<UiAction>,
    val terminalSuccess: Boolean,
    val contentDescription: String,
)

/**
 * The single pure mapper owning the state→surface truth precedence
 * (spec §1.2). Precedence: explicit current typed outcome first (a live
 * request, pause, stall, or rejection is never replaced by an old disk
 * success), then current durable display, then silence for non-user-visible
 * transitions.
 */
object TranslationUiTruth {

    /**
     * Manual single-page outcome (page chip / reader overlay column).
     *
     * @param outcome   the scheduler's last typed outcome for this intent, if any.
     * @param durable   the durable display projection of the page.
     * @param partial   durable manifest fact: the committed result is partial.
     * @param exhausted the D9 ledger says this page exhausted its attempts.
     * @param cancelled an explicit paid-work cancellation was acknowledged.
     */
    fun forManualOutcome(
        outcome: SinglePageOutcome?,
        durable: PageDisplayProjection?,
        partial: Boolean = false,
        exhausted: Boolean = false,
        cancelled: Boolean = false,
    ): PageUiTruth? = when {
        cancelled -> CANCELLED
        outcome is SinglePageOutcome.Attached -> attached(outcome.owner)
        outcome is SinglePageOutcome.AttachedUnresolved -> attachedUnresolved(outcome)
        outcome is SinglePageOutcome.Rejected -> rejected(outcome)
        outcome is SinglePageOutcome.Stalled -> STALLED
        outcome is SinglePageOutcome.Paused -> paused(outcome.nextEligibleRetryAtEpochMs)
        outcome is SinglePageOutcome.Failed -> failed(durable, exhausted)
        else -> fromDurable(durable, outcome, partial)
    }

    /**
     * Rolling-auto slot (auto page/status column). [retryAtEpochMs] is the
     * coordinator's retry epoch for a paused slot, when it knows one; it is
     * carried separately so the bounded slot model keeps its shape.
     */
    fun forAutoSlot(
        slot: AutoSlotState,
        retryAtEpochMs: Long? = null,
    ): PageUiTruth = when (slot) {
        AutoSlotState.Queued -> PageUiTruth(
            label = "Queued.",
            severity = UiSeverity.PROGRESS,
            retryMode = UiRetryMode.NONE,
            retryAtEpochMs = null,
            actions = setOf(UiAction.DETAILS, UiAction.CANCEL),
            terminalSuccess = false,
            contentDescription = "Translation queued; work has not started.",
        )
        AutoSlotState.ReadingText -> stage("Reading text.", "reading text")
        AutoSlotState.Cleaning -> stage("Cleaning bubbles.", "cleaning bubbles")
        AutoSlotState.Translating -> stage("Translating text.", "translating text")
        AutoSlotState.Rendering -> stage("Finishing page.", "finishing page")
        AutoSlotState.Ready -> TRANSLATED
        is AutoSlotState.Deferred -> PageUiTruth(
            label = "Auto paused; retries automatically when available.",
            severity = UiSeverity.WARNING,
            retryMode = UiRetryMode.AUTOMATIC,
            retryAtEpochMs = retryAtEpochMs,
            actions = setOf(UiAction.DETAILS, UiAction.CANCEL),
            terminalSuccess = false,
            contentDescription = "Auto translation paused; automatic retry when available.",
        )
        is AutoSlotState.Failed ->
            if (slot.retryable) {
                PageUiTruth(
                    label = "Auto failed — will retry when available.",
                    severity = UiSeverity.ERROR,
                    retryMode = UiRetryMode.AUTOMATIC,
                    retryAtEpochMs = null,
                    actions = setOf(UiAction.DETAILS),
                    terminalSuccess = false,
                    contentDescription = "Auto translation failed; retry is available.",
                )
            } else {
                PageUiTruth(
                    label = "Auto failed — action required.",
                    severity = UiSeverity.ERROR,
                    retryMode = UiRetryMode.NONE,
                    retryAtEpochMs = null,
                    actions = setOf(UiAction.REVIEW, UiAction.DETAILS),
                    terminalSuccess = false,
                    contentDescription = "Auto translation failed and needs attention.",
                )
            }
    }

    // ------------------------------------------------------------------
    // Manual precedence internals
    // ------------------------------------------------------------------

    private fun fromDurable(
        durable: PageDisplayProjection?,
        outcome: SinglePageOutcome?,
        partial: Boolean,
    ): PageUiTruth? {
        val projection = durable ?: return null
        return when {
            projection.displayReady && partial -> PARTIAL
            projection.isTextless -> TEXTLESS
            projection.displayReady -> TRANSLATED
            // Stale-success guards (§1.2): a Completed the durable record cannot
            // confirm is never success. A durable failure keeps its failure truth.
            outcome is SinglePageOutcome.Completed && projection.state == PageDisplayState.FAILED_NO_RESULT ->
                failedRetryable(severity = UiSeverity.ERROR)
            outcome is SinglePageOutcome.Completed -> NOT_SAVED
            else -> null
        }
    }

    private fun stage(label: String, stageWords: String): PageUiTruth = PageUiTruth(
        label = label,
        severity = UiSeverity.PROGRESS,
        retryMode = UiRetryMode.NONE,
        retryAtEpochMs = null,
        actions = setOf(UiAction.DETAILS, UiAction.CANCEL),
        terminalSuccess = false,
        contentDescription = "Translation stage: $stageWords.",
    )

    private fun paused(retryAtEpochMs: Long?): PageUiTruth = PageUiTruth(
        label = "Paused; explicit retry available.",
        severity = UiSeverity.WARNING,
        retryMode = UiRetryMode.EXPLICIT,
        retryAtEpochMs = retryAtEpochMs,
        actions = setOf(UiAction.RETRY, UiAction.CANCEL, UiAction.DETAILS),
        terminalSuccess = false,
        contentDescription = "Translation paused; explicit retry is available.",
    )

    private fun failed(durable: PageDisplayProjection?, exhausted: Boolean): PageUiTruth =
        if (exhausted) {
            PageUiTruth(
                label = "Paused — repeated interruption before completion; manual retry required.",
                severity = UiSeverity.WARNING,
                retryMode = UiRetryMode.MANUAL_REQUIRED,
                retryAtEpochMs = null,
                actions = setOf(UiAction.RETRY, UiAction.DETAILS),
                terminalSuccess = false,
                contentDescription = "Translation paused after repeated interruption; manual retry required.",
            )
        } else {
            // A committed readable result stays readable: the failure surfaces
            // as a warning ("ready with warnings"), never a red error over it.
            failedRetryable(
                severity = if (durable?.displayReady == true) UiSeverity.WARNING else UiSeverity.ERROR,
            )
        }

    private fun failedRetryable(severity: UiSeverity): PageUiTruth = PageUiTruth(
        label = "Translation failed — retry available.",
        severity = severity,
        retryMode = UiRetryMode.EXPLICIT,
        retryAtEpochMs = null,
        actions = setOf(UiAction.RETRY, UiAction.DETAILS),
        terminalSuccess = false,
        contentDescription = "Page translation failed; retry is available.",
    )

    private fun attached(owner: PageWriteOrigin): PageUiTruth {
        val ownerWord = owner.name.lowercase()
        return PageUiTruth(
            label = "Translating · $ownerWord job.",
            severity = UiSeverity.PROGRESS,
            retryMode = UiRetryMode.NONE,
            retryAtEpochMs = null,
            actions = setOf(UiAction.DETAILS),
            terminalSuccess = false,
            contentDescription = "Waiting for the $ownerWord translation job; no duplicate request started.",
        )
    }

    private fun attachedUnresolved(@Suppress("UNUSED_PARAMETER") unresolved: SinglePageOutcome.AttachedUnresolved): PageUiTruth = ATTACHED_UNRESOLVED

    private fun rejected(rejection: SinglePageOutcome.Rejected): PageUiTruth =
        if (rejection.reason == TranslationPipeline.REASON_TRANSLATION_NOT_SAVED) {
            NOT_SAVED
        } else {
            PageUiTruth(
                label = "Translation not started — ${rejection.reason}.",
                severity = UiSeverity.INFO,
                retryMode = UiRetryMode.NONE,
                retryAtEpochMs = null,
                actions = setOf(UiAction.DETAILS),
                terminalSuccess = false,
                contentDescription = "Translation was not started because ${rejection.reason}.",
            )
        }

    // ------------------------------------------------------------------
    // Shared truths
    // ------------------------------------------------------------------

    private val TRANSLATED = PageUiTruth(
        label = "Translated.",
        severity = UiSeverity.INFO,
        retryMode = UiRetryMode.NONE,
        retryAtEpochMs = null,
        actions = setOf(UiAction.DETAILS),
        terminalSuccess = true,
        contentDescription = "Page translated and ready to read.",
    )

    private val TEXTLESS = PageUiTruth(
        label = "No translatable text.",
        severity = UiSeverity.INFO,
        retryMode = UiRetryMode.NONE,
        retryAtEpochMs = null,
        actions = setOf(UiAction.DETAILS),
        terminalSuccess = true,
        contentDescription = "Page processed; no translatable text.",
    )

    private val PARTIAL = PageUiTruth(
        label = "Partial translation — retry available.",
        severity = UiSeverity.WARNING,
        retryMode = UiRetryMode.EXPLICIT,
        retryAtEpochMs = null,
        actions = setOf(UiAction.RETRY, UiAction.DETAILS),
        terminalSuccess = false,
        contentDescription = "Partial translation; some text is missing and retry is available.",
    )

    private val STALLED = PageUiTruth(
        label = "Translation stalled.",
        severity = UiSeverity.ERROR,
        retryMode = UiRetryMode.EXPLICIT,
        retryAtEpochMs = null,
        actions = setOf(UiAction.CANCEL, UiAction.RETRY, UiAction.DETAILS),
        terminalSuccess = false,
        contentDescription =
            "Translation stalled; the ONNX/native result timer expired while work remains occupied.",
    )

    private val NOT_SAVED = PageUiTruth(
        label = "Translation not saved — retry required.",
        severity = UiSeverity.WARNING,
        retryMode = UiRetryMode.EXPLICIT,
        retryAtEpochMs = null,
        actions = setOf(UiAction.RETRY, UiAction.DETAILS),
        terminalSuccess = false,
        contentDescription =
            "Translation was produced but could not be saved; no durable result is available. Retry is required.",
    )

    private val ATTACHED_UNRESOLVED = PageUiTruth(
        label = "Background translation did not finish yet.",
        severity = UiSeverity.WARNING,
        retryMode = UiRetryMode.EXPLICIT,
        retryAtEpochMs = null,
        actions = setOf(UiAction.RETRY, UiAction.DETAILS),
        terminalSuccess = false,
        contentDescription = "Background translation did not finish within the wait; " +
            "retry is available after the owner releases the page.",
    )

    private val CANCELLED = PageUiTruth(
        label = "Translation cancelled.",
        severity = UiSeverity.INFO,
        retryMode = UiRetryMode.NONE,
        retryAtEpochMs = null,
        actions = setOf(UiAction.RETRY, UiAction.DETAILS),
        terminalSuccess = false,
        contentDescription = "Translation cancelled; saved translated pages were kept.",
    )
}
