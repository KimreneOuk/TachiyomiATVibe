package eu.kanade.translation.presentation

import eu.kanade.translation.model.AiPageProgressState
import eu.kanade.translation.model.BatchHeroPhase
import eu.kanade.translation.model.BatchHeroProjection
import eu.kanade.translation.model.PageDisplayProjection
import eu.kanade.translation.model.PageDisplayState
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationBatchPhase
import eu.kanade.translation.model.TranslationProgressSnapshot
import eu.kanade.translation.model.TranslationProgressStage
import eu.kanade.translation.model.TranslationRequestPhase
import eu.kanade.translation.pipeline.PageStoreWriter
import eu.kanade.translation.pipeline.PageWriteOrigin
import eu.kanade.translation.pipeline.TranslationPipeline
import eu.kanade.translation.scheduling.AutoSlotState
import eu.kanade.translation.scheduling.SinglePageOutcome
import java.text.DateFormat
import java.util.Date

/**
 *  Phase 5 (spec §1.2, §2): bounded UI vocabulary projected FROM existing
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

    /** The  repeated-interruption cap: manual retry required (force path). */
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
     * @param exhausted the  ledger says this page exhausted its attempts.
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
        outcome is SinglePageOutcome.Admitted -> QUEUED
        outcome is SinglePageOutcome.Attached -> attached(outcome.owner)
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
        AutoSlotState.Queued -> QUEUED
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
     *  Phase 5  (spec §3.2): timeout copy names the ACTUAL result timer
     * that fired and omits unmeasured durations. The native lane and the
     * HTTP+render lane run DIFFERENT timers; a generic "Translation timed out"
     * tells the user nothing about which half of the pipeline stalled.
     */
    fun timeoutCopy(nativeTimer: Boolean): String =
        PageStoreWriter.timeoutFailureMessage(nativeTimer)

    /**
     * One chapter's partial-download admission facts ( /N2).
     * [expectedSourceTotal] is null when the source page count is still
     * unknown (download in progress).
     */
    data class PartialDecision(val downloaded: Int, val expectedSourceTotal: Int?)

    /**
     *  Phase 5 N2 (spec §2, §3.3): the finish-first/translate-subset
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
     *  the progress sheet's Retry affordance truth (the field defect: a
     * cancelled batch dead-ended in the sheet's aborted banner with no way
     * back). Offered ONLY when a restart is actually possible AND the caller
     * wired the restart callback: a terminal-aborted batch (explicit
     * cancellation of paid work), a terminal ERROR batch (FINISHED phase +
     * ERROR state), or a durable READY_WITH_WARNINGS batch that ENDED with
     * unresolved pages ( field fix, Chapter 21: a persistence-rejected
     * run surfaced as "Ready (Warnings)" with no way back — the same requeue
     * as Retry reuses committed pages and re-runs only the remainder).
     * A successfully FINISHED batch is completion, not a failure state —
     * never a Retry. Call sites without a callback keep today's banner-only
     * shape.
     */
    fun forSheetRetryAction(
        snapshot: TranslationProgressSnapshot,
        restartWired: Boolean,
    ): PageUiTruth? {
        if (!restartWired) return null
        val restartableTerminal = snapshot.aborted ||
            (
                (
                    snapshot.state == Translation.State.ERROR ||
                        snapshot.state == Translation.State.READY_WITH_WARNINGS
                    ) &&
                    snapshot.batchPhase == TranslationBatchPhase.FINISHED
                )
        if (!restartableTerminal) return null
        return PageUiTruth(
            label = "Retry translation",
            severity = if (snapshot.aborted) UiSeverity.INFO else UiSeverity.ERROR,
            retryMode = UiRetryMode.EXPLICIT,
            retryAtEpochMs = null,
            actions = setOf(UiAction.RETRY, UiAction.DETAILS),
            terminalSuccess = false,
            contentDescription = "Retry translation",
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
     *  Phase 5 (spec §1.2 rule 6, §3.1): the single chapter-level
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
        if (owner == PageWriteOrigin.BATCH) return BATCH_SESSION_SWITCH
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

    private fun rejected(rejection: SinglePageOutcome.Rejected): PageUiTruth =
        if (rejection.owner == PageWriteOrigin.BATCH) {
            BATCH_SESSION_SWITCH
        } else if (rejection.reason == TranslationPipeline.REASON_TRANSLATION_NOT_SAVED) {
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

    val QUEUED = PageUiTruth(
        label = "Queued.",
        severity = UiSeverity.PROGRESS,
        retryMode = UiRetryMode.NONE,
        retryAtEpochMs = null,
        actions = setOf(UiAction.DETAILS, UiAction.CANCEL),
        terminalSuccess = false,
        contentDescription = "Translation queued; work has not started.",
    )

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

    private val BATCH_SESSION_SWITCH = PageUiTruth(
        label = "Batch translation is pausing; confirm switch to reader.",
        severity = UiSeverity.WARNING,
        retryMode = UiRetryMode.NONE,
        retryAtEpochMs = null,
        actions = setOf(UiAction.REVIEW, UiAction.DETAILS),
        terminalSuccess = false,
        contentDescription = "Batch translation is pausing before reader translation can start; confirm the switch.",
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

    // ------------------------------------------------------------------
    //  U.3/U.6: chapter-level batch status-line truth.
    //
    // One priority chain (requestState → queuePosition → pauseReason →
    // coordinator phase+counters → batchPhase) resolved ONCE here, rendered
    // by every batch status surface. Each line carries a stable
    // [BatchStatusLineKind] (surfaces resolve NEW wording from string
    // resources through it) plus the English fallback/source wording
    // (legacy rendering stays byte-identical where no new resource exists
    // yet). The vocabulary + line type are TOP-LEVEL in this file so every
    // surface (sheet, reader bar, tests) imports them directly.
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    //  completion oracle: the celebratory "Completed / All pages
    // translated" state is a CLAIM about every page, so it is gated on the
    // run's failure/attention facts — never on the batch phase alone. The
    // device trace that motivated this  showed the sheet's green
    // "Completed" pill and "All pages translated" subtitle while the same
    // run's trace recorded outcome=failure and "1 pages need attention":
    // FINISHED (which ERROR/READY_WITH_WARNINGS chapters also map to) was
    // treated as completion regardless of unresolved pages. Every completed
    // surface keys on [isCompletedOutcome]; a run that fails the oracle
    // renders the attention/failed wording instead. Presentation truth only
    // — no pipeline state is added or changed.
    // ------------------------------------------------------------------

    /** One page's attention fact: a failed stage/AI attempt or a PARTIAL (retryable, never clean success) result. A page whose committed display is ready and non-partial is success, matching the sheet's page-chip precedence. */
    private fun pageNeedsAttention(page: TranslationProgressSnapshot.Page): Boolean = when {
        page.displayReady && !page.partial -> false
        page.stage == TranslationProgressStage.FAILED -> true
        page.aiState == AiPageProgressState.FAILED -> true
        page.partial -> true
        else -> false
    }

    /**
     *  completion oracle: whether any page in the snapshot needs user
     * attention — the batch failure counter, the failure groups, a
     * non-durable (unsaved) result, or a per-page failed/partial fact.
     */
    fun hasPagesNeedingAttention(snapshot: TranslationProgressSnapshot): Boolean =
        snapshot.failedCount > 0 ||
            snapshot.nonDurableFailure ||
            snapshot.groupedFailures.isNotEmpty() ||
            snapshot.pages.any(::pageNeedsAttention)

    /**
     *  completion oracle: the count behind the "N pages need attention"
     * wording. Never zero while attention exists, so an attention run whose
     * facts live outside the per-page list (unsaved result) still reads
     * honestly.
     */
    fun pagesNeedingAttentionCount(snapshot: TranslationProgressSnapshot): Int {
        if (!hasPagesNeedingAttention(snapshot)) return 0
        return maxOf(
            snapshot.pages.count(::pageNeedsAttention),
            snapshot.failedCount,
            1,
        )
    }

    /**
     *  completion oracle: the run may render "Completed / All pages
     * translated" ONLY when the batch phase finished AND zero pages need
     * attention AND the run outcome is not failed/paused/aborted/unsaved. A
     * run with failure or attention pages NEVER classifies as completed.
     */
    fun isCompletedOutcome(snapshot: TranslationProgressSnapshot): Boolean =
        snapshot.batchPhase == TranslationBatchPhase.FINISHED &&
            !snapshot.aborted &&
            !snapshot.nonDurableFailure &&
            snapshot.pauseReason == null &&
            snapshot.state != Translation.State.ERROR &&
            snapshot.state != Translation.State.PAUSED &&
            !hasPagesNeedingAttention(snapshot)

    /**
     * The single chapter-level status-line priority chain (U.3):
     * requestState → queuePosition → pauseReason → coordinator
     * rebuild/restore phase + counters → batchPhase. The sheet subtitle and
     * the reader bottom bar both derive their copy from this mapping (via
     * [readerBarLine] for the bar's short wording), so no surface can
     * contradict another or freeze on stale "Batch X/Y" copy during a
     * resume rebuild.
     */
    fun batchStatusLine(
        snapshot: TranslationProgressSnapshot,
        isResuming: Boolean = false,
    ): BatchStatusLine = when {
        isResuming -> BatchStatusLine(
            kind = BatchStatusLineKind.RESUMING,
            fallback = "Scanning completed pages & resuming batch...",
        )

        else -> requestStatusLine(snapshot)
            ?: queueStatusLine(snapshot)
            ?: pausedStatusLine(snapshot)
            ?: rebuildStatusLine(snapshot)
            ?: batchPhaseStatusLine(snapshot)
    }

    /** Priority 1: the immediate pre-tracker request acknowledgement. */
    private fun requestStatusLine(snapshot: TranslationProgressSnapshot): BatchStatusLine? {
        val request = snapshot.requestState ?: return null
        val base = when (request.phase) {
            TranslationRequestPhase.STARTING -> "Translation accepted — preparing batch..."
            TranslationRequestPhase.PREPARING -> "Preparing translation batch..."
            TranslationRequestPhase.WAITING_FOR_DOWNLOAD ->
                "Waiting for chapter download before translation"
            TranslationRequestPhase.DOWNLOAD_FAILED -> "Download failed — retry to continue"
            TranslationRequestPhase.CANCELLED ->
                "Translation cancelled — the chapter download was cancelled or removed"
            TranslationRequestPhase.ADMISSION_FAILED ->
                "Translation could not be queued — check the source and translation settings"
        }
        val kind = when (request.phase) {
            TranslationRequestPhase.STARTING -> BatchStatusLineKind.REQUEST_STARTING
            TranslationRequestPhase.PREPARING -> BatchStatusLineKind.REQUEST_PREPARING
            TranslationRequestPhase.WAITING_FOR_DOWNLOAD ->
                BatchStatusLineKind.REQUEST_WAITING_FOR_DOWNLOAD
            TranslationRequestPhase.DOWNLOAD_FAILED -> BatchStatusLineKind.REQUEST_DOWNLOAD_FAILED
            TranslationRequestPhase.CANCELLED -> BatchStatusLineKind.REQUEST_CANCELLED
            TranslationRequestPhase.ADMISSION_FAILED -> BatchStatusLineKind.REQUEST_ADMISSION_FAILED
        }
        val suffix = request.reason.orEmpty().takeIf { it.isNotBlank() }?.let { " — $it" }.orEmpty()
        return BatchStatusLine(kind = kind, fallback = base + suffix)
    }

    /** Priority 2: truthful queue position (never implies it can resume now). */
    private fun queueStatusLine(snapshot: TranslationProgressSnapshot): BatchStatusLine? {
        if (snapshot.state != Translation.State.QUEUE) return null
        val position = snapshot.queuePosition
        val total = snapshot.queueTotal
        return if (position != null && total != null && position > 1) {
            BatchStatusLine(
                kind = BatchStatusLineKind.QUEUE_POSITION,
                formatArgs = listOf(position, total),
                fallback = queuedPositionLabel(position, total),
            )
        } else {
            BatchStatusLine(
                kind = BatchStatusLineKind.QUEUED_READY,
                fallback = "Queued — ready to resume remaining pages",
            )
        }
    }

    /** Priority 3: a durable pause with its reason (and retry time, when known). */
    private fun pausedStatusLine(snapshot: TranslationProgressSnapshot): BatchStatusLine? {
        val paused = snapshot.state == Translation.State.PAUSED || snapshot.pauseReason != null
        if (!paused) return null
        val reason = snapshot.pauseReason?.takeIf { it.isNotBlank() }
            ?: "Provider work is temporarily unavailable"
        val next = snapshot.nextEligibleRetryAtEpochMs?.let { retryAt ->
            DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(retryAt))
        }
        return BatchStatusLine(
            kind = BatchStatusLineKind.PAUSED,
            fallback = if (next == null) "Paused — $reason" else "Paused — $reason · retry after $next",
        )
    }

    /** Priority 4: the coordinator's rebuild/restore phase with its counters. */
    private fun rebuildStatusLine(snapshot: TranslationProgressSnapshot): BatchStatusLine? =
        when (snapshot.batchPhase) {
            TranslationBatchPhase.REBUILDING -> BatchStatusLine(
                kind = BatchStatusLineKind.REBUILDING,
                fallback = "Rebuilding pipeline…",
            )
            TranslationBatchPhase.RESTORING -> {
                val payload = snapshot.rebuildProgress
                BatchStatusLine(
                    kind = BatchStatusLineKind.RESTORING,
                    formatArgs = listOf(
                        payload?.restoredPages ?: 0,
                        payload?.totalPages ?: 0,
                    ),
                    fallback = "Restoring ${payload?.restoredPages ?: 0} of " +
                        "${payload?.totalPages ?: 0} pages…",
                )
            }
            else -> null
        }

    /** Priority 5: the remaining batch phases (the legacy subtitle wording). */
    private fun batchPhaseStatusLine(snapshot: TranslationProgressSnapshot): BatchStatusLine =
        when (snapshot.batchPhase) {
            TranslationBatchPhase.IDLE -> when (snapshot.state) {
                Translation.State.TRANSLATING -> BatchStatusLine(
                    kind = BatchStatusLineKind.BUILDING_CONTEXT,
                    fallback = "Building context & scanning completed pages...",
                )
                else -> BatchStatusLine(
                    kind = BatchStatusLineKind.NO_ACTIVE_BATCH,
                    fallback = "No active batch in progress",
                )
            }
            TranslationBatchPhase.FIRST_PASS -> firstPassStatusLine(snapshot)
            TranslationBatchPhase.FINALIZING -> BatchStatusLine(
                kind = BatchStatusLineKind.FINALIZING,
                fallback = "Finalizing translated chapter...",
            )
            TranslationBatchPhase.FINISHED -> when {
                //  completion oracle: only a run with zero attention pages
                // and no failed/paused/aborted outcome may claim completion.
                isCompletedOutcome(snapshot) -> BatchStatusLine(
                    kind = BatchStatusLineKind.COMPLETED,
                    fallback = "All pages translated and ready to read",
                )
                else -> BatchStatusLine(
                    kind = BatchStatusLineKind.ATTENTION_REQUIRED,
                    formatArgs = listOf(pagesNeedingAttentionCount(snapshot)),
                    fallback = "${pagesNeedingAttentionCount(snapshot)} pages need attention",
                )
            }
            // The rebuild/restore kinds are resolved by [rebuildStatusLine]
            // (higher priority); this branch is unreachable through
            // [batchStatusLine] and exists only for exhaustiveness.
            TranslationBatchPhase.REBUILDING,
            TranslationBatchPhase.RESTORING,
            -> rebuildStatusLine(snapshot)
                ?: BatchStatusLine(
                    kind = BatchStatusLineKind.REBUILDING,
                    fallback = "Rebuilding pipeline…",
                )
        }

    /** FIRST_PASS stage detail — the legacy subtitle's stage-aware wording. */
    private fun firstPassStatusLine(snapshot: TranslationProgressSnapshot): BatchStatusLine = when {
        snapshot.activeStages.contains(TranslationProgressStage.TRANSLATE) -> {
            val progress = snapshot.aiProgress
            val detail = listOfNotNull(
                "${progress.pending} pending".takeIf { progress.pending > 0 },
                "${progress.buffered} buffered".takeIf { progress.buffered > 0 },
                "${progress.running} running/retrying".takeIf { progress.running > 0 },
                "${progress.failed} failed".takeIf { progress.failed > 0 },
            ).joinToString(", ")
            BatchStatusLine(
                kind = BatchStatusLineKind.FIRST_PASS_STAGES,
                fallback = if (detail.isBlank()) {
                    "Translating dialogue with AI model..."
                } else {
                    "AI translation: $detail"
                },
            )
        }
        snapshot.activeStages.contains(TranslationProgressStage.OCR) -> BatchStatusLine(
            kind = BatchStatusLineKind.FIRST_PASS_STAGES,
            fallback = "Reading and detecting page text...",
        )
        snapshot.activeStages.contains(TranslationProgressStage.INPAINT) -> BatchStatusLine(
            kind = BatchStatusLineKind.FIRST_PASS_STAGES,
            fallback = "Cleaning speech bubbles...",
        )
        snapshot.activeStages.contains(TranslationProgressStage.RENDER) -> BatchStatusLine(
            kind = BatchStatusLineKind.FIRST_PASS_STAGES,
            fallback = "Rendering English text overlays...",
        )
        else -> when (val hero = BatchHeroProjection.of(snapshot)) {
            is BatchHeroProjection.Numeric -> BatchStatusLine(
                kind = BatchStatusLineKind.FIRST_PASS_PAGES,
                formatArgs = listOf(snapshot.donePages, snapshot.totalPages),
                fallback = "Translating pages (${snapshot.donePages}/${snapshot.totalPages})",
            )
            is BatchHeroProjection.Phase -> BatchStatusLine(
                kind = BatchStatusLineKind.FIRST_PASS_STAGES,
                fallback = heroPhaseSubtitle(hero),
            )
        }
    }

    /**  slice 1 (post-review) subtitle copy for unknown-total phases. */
    private fun heroPhaseSubtitle(hero: BatchHeroProjection.Phase): String {
        val percent = hero.fraction?.let { " ${(it * 100).toInt()}%" }.orEmpty()
        return when (hero.phase) {
            BatchHeroPhase.ACCEPTED -> "Translation accepted — preparing batch..."
            BatchHeroPhase.WAITING_FOR_DOWNLOAD -> "Waiting for chapter download before translation"
            BatchHeroPhase.DOWNLOADING -> "Downloading chapter$percent..."
            BatchHeroPhase.DOWNLOAD_FAILED -> "Download failed — retry to continue"
            BatchHeroPhase.PREPARING -> "Preparing translation batch..."
            BatchHeroPhase.QUEUED -> "Queued — ready to resume remaining pages"
            BatchHeroPhase.PAUSED -> "Paused"
            BatchHeroPhase.FINALIZING -> "Finalizing translated chapter..."
            BatchHeroPhase.COMPLETED -> "All pages translated and ready to read"
            BatchHeroPhase.FAILED_NO_PAGES -> "Translation failed — chapter has no readable pages"
            BatchHeroPhase.CANCELLED ->
                "Translation cancelled — the chapter download was cancelled or removed"
            BatchHeroPhase.ADMISSION_FAILED ->
                "Translation could not be queued — check the source and translation settings"
            BatchHeroPhase.UNKNOWN_TOTAL ->
                hero.donePages?.let { "$it pages available · source total unknown" }
                    ?: "Source page total unknown"
        }
    }

    /**
     *  U.4: the reader bottom bar's short rendering of the SAME truth.
     * Returns null exactly when the bar must stay hidden (no request, no
     * pause, idle phase — the legacy visibility rule). The rebuild/restore
     * kinds replace the frozen "Batch X/Y" line during a resume rebuild;
     * every legacy branch keeps its historical bar wording byte-identically.
     */
    fun readerBarLine(
        snapshot: TranslationProgressSnapshot,
        isBatchSession: Boolean = true,
    ): BatchStatusLine? {
        if (!isBatchSession) return null
        val request = snapshot.requestState
        val isPaused = snapshot.state == Translation.State.PAUSED || snapshot.pauseReason != null
        val isVisible = request != null ||
            isPaused ||
            snapshot.batchPhase != TranslationBatchPhase.IDLE
        if (!isVisible) return null
        return when {
            request?.phase == TranslationRequestPhase.STARTING -> BatchStatusLine(
                kind = BatchStatusLineKind.REQUEST_STARTING,
                fallback = "Translation accepted — preparing",
            )
            request?.phase == TranslationRequestPhase.PREPARING -> BatchStatusLine(
                kind = BatchStatusLineKind.REQUEST_PREPARING,
                fallback = "Preparing translation batch",
            )
            request?.phase == TranslationRequestPhase.WAITING_FOR_DOWNLOAD -> BatchStatusLine(
                kind = BatchStatusLineKind.REQUEST_WAITING_FOR_DOWNLOAD,
                fallback = "Waiting for chapter download",
            )
            request?.phase == TranslationRequestPhase.DOWNLOAD_FAILED -> BatchStatusLine(
                kind = BatchStatusLineKind.REQUEST_DOWNLOAD_FAILED,
                fallback = "Download failed — retry to continue",
            )
            isPaused -> BatchStatusLine(
                kind = BatchStatusLineKind.PAUSED,
                fallback = "Translation paused",
            )
            snapshot.batchPhase == TranslationBatchPhase.REBUILDING -> BatchStatusLine(
                kind = BatchStatusLineKind.REBUILDING,
                fallback = "Rebuilding pipeline…",
            )
            snapshot.batchPhase == TranslationBatchPhase.RESTORING -> {
                val remaining = snapshot.rebuildProgress?.remainingPages ?: 0
                BatchStatusLine(
                    kind = BatchStatusLineKind.RESTORING,
                    formatArgs = listOf(remaining),
                    fallback = "Restoring $remaining pages…",
                )
            }
            snapshot.totalPages > 0 -> BatchStatusLine(
                kind = BatchStatusLineKind.FIRST_PASS_PAGES,
                formatArgs = listOf(snapshot.donePages, snapshot.totalPages),
                fallback = "Batch ${snapshot.donePages}/${snapshot.totalPages} pages",
            )
            else -> BatchStatusLine(
                kind = BatchStatusLineKind.REQUEST_PREPARING,
                fallback = "Preparing translation batch",
            )
        }
    }

    /** 1 -> "1st", 2 -> "2nd", 3 -> "3rd", 4 -> "4th", 11-13 -> "th". */
    fun ordinalSuffixOf(value: Int): String {
        val mod100 = value % 100
        val suffix = when {
            mod100 in 11..13 -> "th"
            else -> when (value % 10) {
                1 -> "st"
                2 -> "nd"
                3 -> "rd"
                else -> "th"
            }
        }
        return "$value$suffix"
    }

    /**  slice 2: truthful queue position, e.g. "Queued (2nd of 3) — ...". */
    fun queuedPositionLabel(position: Int, total: Int): String =
        "Queued (${ordinalSuffixOf(position)} of $total) — waiting for earlier batches"
}

/** Bounded vocabulary for chapter-level batch status lines ( U.3). */
enum class BatchStatusLineKind {
    RESUMING,
    REQUEST_STARTING,
    REQUEST_WAITING_FOR_DOWNLOAD,
    REQUEST_PREPARING,
    REQUEST_DOWNLOAD_FAILED,
    REQUEST_CANCELLED,
    REQUEST_ADMISSION_FAILED,
    QUEUE_POSITION,
    QUEUED_READY,
    PAUSED,

    /**  U.1: run record says the resumed run is re-validating sources. */
    REBUILDING,

    /**  U.1: run record says the resumed run is re-adopting durable page work. */
    RESTORING,
    BUILDING_CONTEXT,
    FIRST_PASS_STAGES,
    FIRST_PASS_PAGES,
    FINALIZING,
    COMPLETED,

    /**
     *  completion oracle: the batch phase finished but the run carries
     * failure/attention pages or a failed/paused/unsaved outcome — the
     * attention state that replaces the celebratory completed copy.
     * [formatArgs] carries the attention page count.
     */
    ATTENTION_REQUIRED,
    NO_ACTIVE_BATCH,
}

/**
 * One chapter-level batch status line ( U.3). [formatArgs] feeds the
 * surface's string-resource lookup for [kind]; [fallback] is the English
 * source wording for surfaces (or locales) that render without the resource.
 */
data class BatchStatusLine(
    val kind: BatchStatusLineKind,
    val formatArgs: List<Any> = emptyList(),
    val fallback: String,
)
