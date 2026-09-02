package eu.kanade.translation.ui

import eu.kanade.translation.PageWriteOrigin
import eu.kanade.translation.TranslationPipeline
import eu.kanade.translation.model.PageDisplayProjection
import eu.kanade.translation.model.PageDisplayState
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationProgressSnapshot
import eu.kanade.translation.model.TranslationProgressStage
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
 * Whether a state transition may be announced on an attention surface
 * (spec §3.1 visibility budget): SILENCE is reserved for self-healing
 * scheduler maintenance and ordinary coalescing — never for a user
 * decision, pause, stall, durable failure, partial result, or the
 * cancellation of paid work.
 */
enum class SurfaceVisibility { SURFACE, SILENCE }

/**
 * The visibility verdict for a chapter-level transition, carrying the truth
 * every surface must agree on when [visibility] is [SurfaceVisibility.SURFACE].
 */
data class ChapterSurfaceDecision(
    val visibility: SurfaceVisibility,
    val reason: String,
    val truth: PageUiTruth?,
)

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

    /**
     * T917 Phase 5 D12 (spec §3.2): timeout copy names the ACTUAL result timer
     * that fired and omits unmeasured durations. The native lane and the
     * HTTP+render lane run DIFFERENT timers; a generic "Translation timed out"
     * tells the user nothing about which half of the pipeline stalled.
     */
    fun timeoutCopy(nativeTimer: Boolean): String =
        if (nativeTimer) {
            "ONNX/native result timer expired; translation failed."
        } else {
            "HTTP+render result timer expired; translation failed."
        }

    /**
     * One chapter's partial-download admission facts (T917 D10/N2).
     * [expectedSourceTotal] is null when the source page count is still
     * unknown (download in progress).
     */
    data class PartialDecision(val downloaded: Int, val expectedSourceTotal: Int?)

    /**
     * T917 Phase 5 N2 (spec §2, §3.3): the finish-first/translate-subset
     * decision body must describe EVERY chapter in the group — the phase-4
     * dialog showed only the first chapter's counts.
     */
    fun partialDownloadBody(decisions: Array<PartialDecision>): String {
        val choice = "Translate the pages that exist now, or finish the download first?"
        return if (decisions.any { it.expectedSourceTotal == null }) {
            "The download is still in progress and the page total is unknown. $choice"
        } else {
            val parts = decisions.map { (downloaded, expected) ->
                "$downloaded of $expected pages are downloaded"
            }
            val statement = if (parts.size == 1) parts[0] else parts.joinToString(" and ")
            "$statement. $choice"
        }
    }

    /**
     * Chapter-level indicator truth (spec §3.1): READY_WITH_WARNINGS must be
     * conveyed by label, not by tint alone.
     */
    fun forChapterIndicator(
        state: Translation.State,
        snapshot: TranslationProgressSnapshot?,
    ): PageUiTruth = when (state) {
        Translation.State.TRANSLATED -> PageUiTruth(
            label = TRANSLATED.label,
            severity = UiSeverity.INFO,
            retryMode = UiRetryMode.NONE,
            retryAtEpochMs = null,
            actions = setOf(UiAction.DETAILS),
            terminalSuccess = true,
            contentDescription = "Chapter translation ready",
        )
        Translation.State.READY_WITH_WARNINGS -> PageUiTruth(
            label = "Translated with warnings.",
            severity = UiSeverity.WARNING,
            retryMode = UiRetryMode.EXPLICIT,
            retryAtEpochMs = null,
            actions = setOf(UiAction.RETRY, UiAction.DETAILS),
            terminalSuccess = false,
            contentDescription =
                "Chapter translation ready with warnings; some pages need attention.",
        )
        Translation.State.PAUSED -> PageUiTruth(
            label = "Paused.",
            severity = UiSeverity.WARNING,
            retryMode = UiRetryMode.AUTOMATIC,
            retryAtEpochMs = snapshot?.nextEligibleRetryAtEpochMs,
            actions = setOf(UiAction.DETAILS),
            terminalSuccess = false,
            contentDescription = snapshot?.pauseReason
                ?.let { "Chapter translation paused: $it." }
                ?: "Chapter translation paused.",
        )
        Translation.State.ERROR -> PageUiTruth(
            label = "Translation failed — retry available.",
            severity = UiSeverity.ERROR,
            retryMode = UiRetryMode.EXPLICIT,
            retryAtEpochMs = null,
            actions = setOf(UiAction.RETRY, UiAction.DETAILS),
            terminalSuccess = false,
            contentDescription = "Chapter translation failed; retry is available.",
        )
        Translation.State.QUEUE, Translation.State.TRANSLATING -> PageUiTruth(
            label = "Translating.",
            severity = UiSeverity.PROGRESS,
            retryMode = UiRetryMode.NONE,
            retryAtEpochMs = null,
            actions = setOf(UiAction.DETAILS, UiAction.CANCEL),
            terminalSuccess = false,
            contentDescription = if (snapshot != null && snapshot.expectedPageCountTrusted) {
                "Chapter translation in progress; " +
                    "${snapshot.donePages.coerceAtLeast(0)} of ${snapshot.totalPages} pages translated."
            } else {
                "Chapter translation in progress."
            },
        )
        Translation.State.NOT_TRANSLATED -> PageUiTruth(
            label = "Not translated.",
            severity = UiSeverity.INFO,
            retryMode = UiRetryMode.NONE,
            retryAtEpochMs = null,
            actions = setOf(UiAction.DETAILS),
            terminalSuccess = false,
            contentDescription = "Chapter is not translated.",
        )
    }

    /**
     * Drawer page-overview mini chip truth (spec §3.1): failed/partial/queued
     * state is conveyed by a semantic label, never by icon or tint alone.
     */
    fun pageMiniChipLabel(page: TranslationProgressSnapshot.Page): String = when {
        page.stage == TranslationProgressStage.DONE && page.partial -> PARTIAL.label
        page.stage == TranslationProgressStage.DONE -> TRANSLATED.label
        page.stage == TranslationProgressStage.FAILED -> failedRetryable(UiSeverity.ERROR).label
        page.stage == TranslationProgressStage.QUEUED -> "Queued."
        page.stage == TranslationProgressStage.OCR -> "Reading text."
        page.stage == TranslationProgressStage.TRANSLATE -> "Translating text."
        page.stage == TranslationProgressStage.INPAINT -> "Cleaning bubbles."
        page.stage == TranslationProgressStage.RENDER -> "Finishing page."
        else -> "Translation pending."
    }

    /**
     * T917 Phase 5 (spec §1.2 rule 6, §3.1): the single chapter-level
     * visibility gate. [previous] is the last surfaced snapshot for the same
     * chapter (may be null); [current] is the fresher durable state. The
     * CURRENT state is always the truth source — [previous] is used only to
     * detect deltas (new failure, new partial) and repeated identical
     * emissions (coalescing). A newer durable terminal state supersedes an
     * older failure snapshot; an old callback can never replace it.
     */
    fun chapterSurfaceDecision(
        previous: TranslationProgressSnapshot?,
        current: TranslationProgressSnapshot?,
    ): ChapterSurfaceDecision {
        if (current == null) {
            return ChapterSurfaceDecision(
                visibility = SurfaceVisibility.SILENCE,
                reason = "no current durable truth",
                truth = null,
            )
        }
        return when {
            current.aborted -> surface(
                "explicit cancellation of paid work must be acknowledged",
                PageUiTruth(
                    label = "Translation cancelled.",
                    severity = UiSeverity.INFO,
                    retryMode = UiRetryMode.EXPLICIT,
                    retryAtEpochMs = null,
                    actions = setOf(UiAction.RETRY, UiAction.DETAILS),
                    terminalSuccess = false,
                    contentDescription = "Batch translation cancelled; saved pages were kept.",
                ),
            )

            current.nonDurableFailure -> surface(
                "publication rejection suppresses all success copy",
                NOT_SAVED,
            )

            current.state == Translation.State.PAUSED || current.nextEligibleRetryAtEpochMs != null ->
                surface(
                    "persistent pause with reason",
                    forChapterIndicator(Translation.State.PAUSED, current),
                )

            current.state == Translation.State.TRANSLATED ||
                current.state == Translation.State.READY_WITH_WARNINGS -> surface(
                "newer durable terminal state supersedes any stale callback",
                forChapterIndicator(current.state, current),
            )

            current.failedCount > (previous?.failedCount ?: 0) ||
                (
                    current.state == Translation.State.ERROR &&
                        previous?.state != Translation.State.ERROR
                    ) -> {
                val failureTruth = forChapterIndicator(Translation.State.ERROR, current)
                val truth = if (current.displayReadyPages > 0) {
                    // Committed readable display + failed candidate refresh:
                    // "ready with warnings", never a red error over the
                    // readable image (spec §3.1, PageTranslation
                    // .shouldSurfaceError discipline).
                    failureTruth.copy(
                        severity = UiSeverity.WARNING,
                        label = "Translated with warnings.",
                        contentDescription =
                            "Chapter translation ready with warnings; a page failed but readable results remain.",
                    )
                } else {
                    failureTruth
                }
                surface("durable failure", truth)
            }

            partialPageCount(current) > partialPageCount(previous) -> surface(
                "partial result is visible, never silently counted as complete",
                PARTIAL,
            )

            else -> ChapterSurfaceDecision(
                visibility = SurfaceVisibility.SILENCE,
                reason = "self-healing scheduler transition or ordinary coalescing",
                truth = null,
            )
        }
    }

    private fun surface(reason: String, truth: PageUiTruth) = ChapterSurfaceDecision(
        visibility = SurfaceVisibility.SURFACE,
        reason = reason,
        truth = truth,
    )

    private fun partialPageCount(snapshot: TranslationProgressSnapshot?): Int =
        snapshot?.pages?.count { it.stage == TranslationProgressStage.DONE && it.partial } ?: 0

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
