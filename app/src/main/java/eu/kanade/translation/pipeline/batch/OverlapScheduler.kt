package eu.kanade.translation.pipeline.batch

import eu.kanade.translation.diagnostics.TranslationTraceLeaseKind
import eu.kanade.translation.diagnostics.TranslationTraceReason
import eu.kanade.translation.diagnostics.TranslationTraceSite
import eu.kanade.translation.engines.translator.BatchRequestSublimitGate
import eu.kanade.translation.engines.translator.ProviderRequestMetadata
import eu.kanade.translation.model.PageStage
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.hasRenderedResult
import eu.kanade.translation.model.isCleanedImageReady
import eu.kanade.translation.model.isTextlessTerminal
import eu.kanade.translation.persistence.chapter.ChapterTranslationStore
import eu.kanade.translation.persistence.chapter.LeaseAcquisition
import eu.kanade.translation.persistence.chapter.PageWriteOrigin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Runs the existing serial native inpaint lane while batch translation is in
 * flight, then drains any remaining eligible pages after translation.
 *
 * OCR preflight finishes before this scheduler starts. It handles only
 * OCR-final pages and never reruns detection or OCR. Each drain attempts a
 * page at most once, and [inpaintMutex] keeps native inpainting exclusive.
 * Admission uses a BATCH lease and never preempts a MANUAL lease or native
 * engine quarantine. Before acquiring a lease, [inpaintOne] checks the page's
 * write slot; a same-origin writer holding that slot makes the page wait for a
 * later event or the serial drain. Attaching to that live hold could race the
 * writer's plan-time CAS and release its lease record before its commit.
 *
 * A drain is event-driven: lease denial defers a page for the rest of that
 * pass, while a busy write slot or failed lane attempt waits for a later
 * window or serial drain. This avoids retrying settled work in a hot loop.
 * The coordinator starts the scheduler only after OCR preflight. On teardown,
 * window-driven work stops between pages; each attempt deregisters its write
 * identity and releases its BATCH lease in `finally` after the attempt settles.
 * The existing guarded merge and durable mask/cleaned-image publication path
 * remains responsible for the write. Pages that cannot complete here remain
 * eligible for the serial post-translation drain.
 */
internal class OverlapScheduler(
    private val store: ChapterTranslationStore,
    private val nativeWorker: NativeLaneWorker,
    /** Natural page order used for deterministic candidate selection. */
    private val orderedPageKeys: List<String>,
    /**
     * The shell's shared batch write-identity map. The scheduler registers a
     * fresh identity for the page it inpaints so the EXISTING lane's guarded
     * merges fence correctly, and deregisters it in `finally`.
     */
    private val batchWriteIdentities: ConcurrentHashMap<String, BatchWriteIdentity>,
    /** Releases the BATCH page lease (the shell's `releaseBatchPageLease`). */
    private val releaseBatchLease: suspend (String) -> Unit,
    private val pageTraceRegistry: BatchPageTraceRegistry? = null,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
    private val plannedInpaintNeedsWork: (String) -> Boolean = { false },
) {

    /** Operational overlap and serial-fallback counters; never fingerprinted. */
    class Counters {
        val overlapInpaintsExecuted = AtomicLong(0)
        val overlapInpaintFailures = AtomicLong(0)
        val serialInpaintsExecuted = AtomicLong(0)
        val overlapWindowsCount = AtomicLong(0)
        val overlapWindowsMs = AtomicLong(0)

        /** Windows during which no inpaint could run (no work / lane busy / lease denied). */
        val serialFallbacks = AtomicLong(0)

        fun snapshot(): Map<String, Long> = mapOf(
            "overlapInpaintsExecuted" to overlapInpaintsExecuted.get(),
            "overlapInpaintFailures" to overlapInpaintFailures.get(),
            "serialInpaintsExecuted" to serialInpaintsExecuted.get(),
            "overlapWindowsCount" to overlapWindowsCount.get(),
            "overlapWindowsMs" to overlapWindowsMs.get(),
            "serialFallbacks" to serialFallbacks.get(),
        )
    }

    val counters = Counters()

    /**
     *  hook: invoked after a page's inpaint durably committed (READY) so the
     * coordinator can publish the persisted layout for that page immediately
     * (per page, never blocking the envelope loop). Failures inside the hook
     * never affect the scheduler.
     */
    @Volatile
    var onInpaintCommitted: (suspend (pageKey: String) -> Unit)? = null

    private val inpaintMutex = Mutex()

    /** Remote-window state: strictly alternating open/close from the gate wrapper. */
    private val windowOpen = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Set by [stopOverlap]: the overlap loop stops STARTING new pages. */
    private val stopped = java.util.concurrent.atomic.AtomicBoolean(false)

    /** The page currently inside the lane (visible to [nextInpaintCandidate]). */
    @Volatile
    private var inFlightPage: String? = null

    private val windowOpenedAtMs = AtomicLong(0)

    /** Loop wake events. The loop suspends on this channel — never busy-polls. */
    private sealed interface LoopEvent {
        data object WindowOpened : LoopEvent
        data object WindowClosed : LoopEvent

        /**  track V: a producer nudge (no window attached — see [notifyCandidatesChanged]). */
        data object Nudged : LoopEvent
        data object Stopped : LoopEvent
    }

    private val events = kotlinx.coroutines.channels.Channel<LoopEvent>(
        kotlinx.coroutines.channels.Channel.UNLIMITED,
    )

    /** Window progress marker, written by the loop thread (@Volatile). */
    @Volatile
    private var progressedInWindow = false

    // ------------------------------------------------------------------
    // Remote-window signalling (called from the sub-limit gate wrapper).
    // ------------------------------------------------------------------

    fun onRemoteWindowOpened() {
        windowOpen.set(true)
        // One attempt per page per window ( round 2): a new window is a
        // state change — slot holds may have cleared, the lane may accept
        // again — so every deferred page gets exactly one fresh attempt.
        deferredUntilNextWindow.clear()
        counters.overlapWindowsCount.incrementAndGet()
        windowOpenedAtMs.set(nowEpochMs())
        progressedInWindow = false
        events.trySend(LoopEvent.WindowOpened)
    }

    /**
     *  track V: producer nudge — schedules a drain pass WITHOUT opening a
     * window. The executor's window brackets are one producer among several;
     * any observer of a state change that may have freed a candidate (a slot
     * released, a deferral invalidated) can call this. Coalesces harmlessly:
     * a nudge arriving mid-drain queues one more bounded pass.
     */
    fun notifyCandidatesChanged() {
        events.trySend(LoopEvent.Nudged)
    }

    fun onRemoteWindowClosed() {
        val openedAt = windowOpenedAtMs.getAndSet(0)
        if (openedAt > 0) {
            counters.overlapWindowsMs.addAndGet((nowEpochMs() - openedAt).coerceAtLeast(0))
        }
        windowOpen.set(false)
        // Serial-fallback accounting is per deferred page (counted in
        // [inpaintOne]'s overlap lease-denial and slot-busy branches), not per
        // window: a window that both deferred a MANUAL-owned page and
        // committed another page still produced overlap progress.
        events.trySend(LoopEvent.WindowClosed)
    }

    /**
     * The gate wrapper installed by the coordinator: delegates admission and
     * the request block unchanged to the process-wide sub-limit gate, and
     * signals overlap windows around the request.
     */
    internal class WindowSignallingGate(
        private val delegate: BatchRequestSublimitGate,
        private val scheduler: OverlapScheduler,
    ) : BatchRequestSublimitGate() {
        override suspend fun <T> executeBatch(
            metadata: ProviderRequestMetadata,
            block: suspend () -> T,
        ): T {
            scheduler.onRemoteWindowOpened()
            return try {
                delegate.executeBatch(metadata, block)
            } finally {
                scheduler.onRemoteWindowClosed()
            }
        }
    }

    // ------------------------------------------------------------------
    // Loops.
    // ------------------------------------------------------------------

    /**
     *  track V continuous admission loop: runs while the TRANSLATE phase
     * is active. Every wake event — window open (the executor's per-dispatch
     * trigger, now just one producer among several) and
     * [notifyCandidatesChanged] nudges — triggers one FULL
     * [drainAvailableWork] pass that runs straight through the in-flight
     * provider round-trip and beyond (the window flag is never consulted),
     * so the native lane works DURING the LLM wait instead of only at the
     * envelope gaps. A backlog longer than one envelope cycle keeps the
     * drain running across cycles; a caught-up lane parks back on the event
     * channel (no polling, no timers — virtual-time safe). Even a bracket
     * that already closed before the loop was dispatched drains: on virtual
     * time that mid-run drain is exactly the pinned behavior of
     * StandardPipelineCoordinatorTest's onFirstInpaint test (first inpaint
     * lands while the run state is still TRANSLATE). The guards that keep a
     * pass safe everywhere are structural, not the window state: the
     * write-slot pre-check and ownership claim in [inpaintOne] defer any
     * page a live writer holds, and the pass ends the moment [stopOverlap]
     * sets the stop flag. The window-CLOSE event is deliberately not a
     * trigger: the envelope still holds its pages' write slots until its
     * commit settles, so the next window open (or a nudge) is the earliest
     * a freed page can actually be claimed.
     * Exports nothing — all durable progress is store state.
     */
    suspend fun runOverlapLoop() {
        for (event in events) {
            when (event) {
                LoopEvent.Stopped -> return
                LoopEvent.WindowClosed -> Unit
                LoopEvent.WindowOpened, LoopEvent.Nudged -> drainAvailableWork()
            }
        }
    }

    /**
     * ONE continuous-admission pass: every candidate the safety gates
     * currently allow is attempted back-to-back, independent of envelope
     * boundaries. A backlog longer than one envelope cycle keeps the lane
     * busy across cycles; a caught-up lane idles at the event channel instead
     * of the NPU idling between envelope gaps. Still strictly serialized:
     * [inpaintOne] holds [inpaintMutex], so even overlapping producers can
     * never run two native inpaints.
     */
    private suspend fun drainAvailableWork() {
        // A new drain is a state change (slot holds settle while the previous
        // pass ran): every deferred page gets exactly one fresh attempt per
        // pass — the  round-2 one-attempt-per-window rule, generalized to
        // one-attempt-per-drain.
        deferredUntilNextWindow.clear()
        while (!stopped.get()) {
            val pageKey = nextInpaintCandidate() ?: break
            when (inpaintOne(pageKey, overlap = true)) {
                // Telemetry fires on COMMIT only (a NoWork scan must not
                // re-log the same throughput milestone).
                InpaintOutcome.Committed -> {
                    progressedInWindow = true
                    logInpaintThroughput()
                }
                InpaintOutcome.NoWork -> progressedInWindow = true
                // One attempt per pass ( round 2): the lane just settled
                // this page's outcome as failed — it is not re-attempted
                // inside the same drain. Re-selecting it inside the same pass
                // spun the loop hot on virtual time (the T5 hang) and on real
                // lanes.
                InpaintOutcome.Failed -> {
                    deferredUntilNextWindow += pageKey
                    progressedInWindow = true
                }
                // Yielded to a concurrent writer — the page is settled for
                // this pass (progress = the drain moved past it).
                InpaintOutcome.Deferred -> progressedInWindow = true
                // Lease-denial accounting is per deferred PAGE (serialFallbacks
                // inside inpaintOne); the drain keeps trying the remaining
                // candidates. SlotBusy is the same shape (the page went to
                // [deferredUntilNextWindow] inside inpaintOne).
                InpaintOutcome.LeaseDenied, InpaintOutcome.SlotBusy -> Unit
            }
        }
    }

    /** Telemetry cadence: one INFO throughput line per this many overlap commits. */
    private val firstOverlapCommitAtMs = AtomicLong(0)

    /**
     *  track V telemetry: every [THROUGHPUT_LOG_EVERY_PAGES] overlap
     * commits, one concise INFO line in the existing `[translation_perf]`
     * shape so the next on-device run can verify cleaning throughput against
     * the envelope commit rate (target: ≥ translation throughput).
     */
    private fun logInpaintThroughput() {
        val committed = counters.overlapInpaintsExecuted.get()
        if (committed <= 0L || committed % THROUGHPUT_LOG_EVERY_PAGES != 0L) return
        val startedAt = firstOverlapCommitAtMs.get()
        val elapsedMs = (nowEpochMs() - startedAt).coerceAtLeast(1L)
        val ratePerMin = committed * 60_000.0 / elapsedMs
        val rateText = "%.1f".format(ratePerMin)
        val snapshot = counters.snapshot()
        logcat(LogPriority.INFO) {
            "[translation_perf] stage=inpainting overlapPages=$committed " +
                "rate=$rateText/min windows=${snapshot["overlapWindowsCount"]} " +
                "serialFallbacks=${snapshot["serialFallbacks"]} " +
                "failures=${snapshot["overlapInpaintFailures"]}"
        }
    }

    /**
     * The legacy serial post-translate inpaint arm: drains every remaining
     * page (OCR-final, inpaint still pending — regardless of translation
     * status,  track I) one at a time through the SAME lane — semantics
     * identical to the pre-overlap serial schedule. Called by the
     * coordinator's FINALIZE  before the stranded-page
     * reconciliation.
     *
     * A page that does NOT commit (lane failure, or a  yield to a
     * concurrent writer) is DEFERRED for the rest of this drain — never
     * retried in-pass, which livelocked the ordered drain when a live reader
     * lane kept invalidating the page. FINALIZE's reconciliation (and a
     * later run, or the owner itself) settles deferred pages.
     */
    suspend fun drainSerial() {
        stopOverlap()
        // Window-scoped deferrals expire here: the drain is the serial
        // schedule's own pass, so every page deferred inside the last window
        // (slot busy / lane failed) gets its one drain attempt now.
        deferredUntilNextWindow.clear()
        while (true) {
            val pageKey = nextInpaintCandidate() ?: break
            when (inpaintOne(pageKey, overlap = false)) {
                InpaintOutcome.Committed -> Unit // counted inside inpaintOne
                // Deferred/failed/denied pages are reconciled at FINALIZE,
                // not retried forever inside this drain.
                else -> deferredByLeaseOwner += pageKey
            }
        }
        //  track I round 3: settle the decoupling's order-inverted pages
        // (see [stampRenderTerminalOrphans]).
        stampRenderTerminalOrphans()
    }

    /**
     *  track I round 3 — the drain-side render-terminal adoption sweep
     * (the E2 stamp's post-translation twin). The decoupled candidacy
     * inverts a page's own stage order: an OCR-final page can be inpainted
     * while its translation is still PENDING (an earlier window of this
     * pass, or a window of a previous cancelled run). The inpaint lane's
     * render-terminal stamp (`NativeLaneWorker.runInpaintStage` → "batch
     * render terminal stamp") is gated on the page's translation being
     * ALREADY terminal, so an order-inverted page commits no stamp at
     * inpaint time; and because the durable inpaint artifact REUSEs, no
     * later run re-runs the inpaint — the stamp site is never visited again.
     * The preflight adoption stamp (the coordinator's
     * `stampAdoptedRenderTerminal`, E2) cannot heal it either: it runs at
     * OCR-preflight adoption, BEFORE the translation. Left unhandled,
     * renderStatus stays PENDING forever and the reader shows the original
     * image — the exact field-report signature the stamp exists for.
     *
     * The drain is the pass's serial settle point (after the last window
     * closed), so it owns the repair: every page that is durably
     * display-complete under the SAME predicate as the coordinator's
     * adopted-page stamp (translation terminal + inpaint READY + cleaned
     * image + a non-blank translated block + renderStatus PENDING) gets the
     * idempotent render-terminal stamp through the SAME guarded
     * fresh-snapshot write + Render-stage BATCH lease discipline. ONE
     * bounded pass — no waiting, no retries: a denied lease or a rejected
     * write skips the page (a later run's drain retries it), identical to
     * the E2 rejection semantics.
     */
    private suspend fun stampRenderTerminalOrphans() {
        for (pageKey in orderedPageKeys) {
            val candidate = store.snapshot(pageKey).page ?: continue
            val displayComplete =
                (
                    candidate.translationStatus == StageStatus.READY ||
                        candidate.translationStatus == StageStatus.PARTIAL
                    ) &&
                    candidate.isCleanedImageReady &&
                    candidate.renderStatus == StageStatus.PENDING &&
                    candidate.blocks.any { it.translation.isNotBlank() }
            if (!displayComplete) continue
            when (
                pageTraceRegistry?.withLeaseWait(
                    pageKey = pageKey,
                    site = TranslationTraceSite.BATCH_OVERLAP_RENDER,
                    leaseKind = TranslationTraceLeaseKind.RENDER,
                ) {
                    store.tryAcquirePageStageLease(pageKey, PageStage.Render, PageWriteOrigin.BATCH)
                } ?: store.tryAcquirePageStageLease(pageKey, PageStage.Render, PageWriteOrigin.BATCH)
            ) {
                is LeaseAcquisition.Granted -> Unit
                else -> continue
            }
            try {
                // The guarded write's lease fence is checked against the
                // CURRENT page lease (render-stage token just acquired), so
                // the precondition is snapshotted AFTER the acquire — the
                // same idiom the guarded stage seeds use.
                val held = store.snapshot(pageKey)
                val outcome = store.updatePageGuarded(
                    pageKey = pageKey,
                    expected = ChapterTranslationStore.PatchPrecondition(
                        generation = held.generation,
                        pageVersion = held.pageVersion,
                        blockFingerprints = held.blockFingerprints,
                        leaseToken = held.leaseToken,
                        candidateGenerationId = held.candidateGenerationId,
                        dependencyFingerprint = held.dependencyFingerprint,
                        artifactPageVersion = held.artifactPageVersion,
                    ),
                    description = "t934 track I: stamp order-inverted display-complete page render-terminal",
                ) { current ->
                    (current ?: candidate).apply {
                        if (renderStatus == StageStatus.PENDING && isCleanedImageReady) {
                            renderStatus = StageStatus.READY
                            updatedAt = System.currentTimeMillis()
                        }
                    }
                }
                if (outcome is ChapterTranslationStore.PatchResult.Rejected) {
                    logcat(LogPriority.WARN) {
                        "TachiyomiAT t934 render-terminal sweep skipped pageHash=${pageKey.hashCode()} " +
                            "reason=${outcome.reason}"
                    }
                }
            } finally {
                runCatching { releaseBatchLease(pageKey) }
            }
        }
    }

    /** Stops window-driven work between pages (pass end / pause). */
    fun stopOverlap() {
        if (stopped.getAndSet(true)) return
        events.trySend(LoopEvent.Stopped)
    }

    // ------------------------------------------------------------------
    // Candidate selection + one inpaint.
    // ------------------------------------------------------------------

    /**
     * Pages whose BATCH inpaint lease was DENIED this pass (a MANUAL/native
     * owner owns them). They are skipped for the rest of the pass — the lease
     * owner's committed outcome is authoritative and a later run reconciles.
     */
    private val deferredByLeaseOwner = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     *  round 2: pages deferred until the NEXT window opens — a lane-failed
     * attempt (the outcome is settled for this window) or a page whose BATCH
     * write slot was busy at admission (an in-flight same-origin translation
     * writer holds it; the hold clears at that writer's commit). Cleared in
     * [onRemoteWindowOpened] and at [drainSerial] start, so every page gets
     * exactly one attempt per window / per drain pass and nothing is retried
     * inside the window that just deferred it.
     */
    private val deferredUntilNextWindow = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val inpaintedThisRun = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private fun nextInpaintCandidate(): String? {
        val state = store.state.value
        for (pageKey in orderedPageKeys) {
            if (pageKey == inFlightPage) continue
            if (pageKey in deferredByLeaseOwner) continue
            if (pageKey in deferredUntilNextWindow) continue
            val page = state[pageKey] ?: continue
            if (page.isTextlessTerminal) continue
            if (page.hasRenderedResult) continue
            //  track I: the inpaint artifact fingerprint
            // (StageFingerprints.inpaint) has NO translation input — the data
            // dependency is detection/masks only. Candidacy therefore gates on
            // OCR being FINAL, not on the page's translation status: an
            // OCR-final page is a candidate whether its translation is
            // PENDING, RUNNING, READY, PARTIAL, or FAILED. (The scheduler only
            // runs inside the TRANSLATE phase, so OCR_PREFLIGHT is already
            // terminal process-wide; the READY check additionally keeps
            // non-final OCR pages — PENDING/RUNNING/FAILED, which have no
            // durable mask to erase — out of the lane.)
            if (page.ocrStatus != StageStatus.READY) continue
            // An OCR-final page with no blocks and no mask has no inpaint
            // work. Keep masked zero-block pages eligible so the existing
            // inpaint path can settle their PENDING status to READY.
            if (page.blocks.isEmpty() && page.inpaintMaskBoxes.isEmpty()) continue
            val inpaint = page.inpaintStatus
            if (inpaintedThisRun.contains(pageKey) ||
                inpaint == StageStatus.READY &&
                !plannedInpaintNeedsWork(pageKey) ||
                inpaint == StageStatus.FAILED ||
                inpaint == StageStatus.TEXTLESS
            ) {
                continue
            }
            return pageKey
        }
        return null
    }

    private enum class InpaintOutcome { Committed, Failed, NoWork, LeaseDenied, SlotBusy, Deferred }

    /**
     *  track V: the outcome of ONE ownership claim for a candidate page
     * (atomic acquire + post-acquire re-validation). Internal + sealed so the
     * 047/052 rule is unit-testable in isolation.
     */
    internal sealed interface InpaintOwnership {
        /**
         * A FRESH inpaint-stage BATCH grant — this scheduler owns the page's
         * write slot; [snapshot] was captured right after the grant.
         */
        data class Granted(
            val lease: LeaseAcquisition.Granted,
            val snapshot: ChapterTranslationStore.PageSnapshot,
        ) : InpaintOwnership

        /**
         * The grant rode an EXISTING BATCH hold (a same-origin SIBLING ATTACH
         * — the pre-check's snapshot went stale). The attach was already
         * undone ([store.detachPageStageLeaseIfAttached]); the caller defers
         * the page and moves on — never writes, never removes the record.
         */
        data object SiblingAttach : InpaintOwnership

        /** A foreign owner (MANUAL/native) holds the stage — never preempted. */
        data class LeaseDenied(val denial: LeaseAcquisition.Denied) : InpaintOwnership
    }

    /**
     *  track V ownership claim: acquire the page's BATCH inpaint lease and
     * RE-VALIDATE the grant. The acquire is atomic, so the 047/052 staleness
     * is detected, not raced: a SIBLING ATTACH grants the EXISTING record's
     * token AND stage (`PageStageLeaseTable.tryAcquirePageStageLease`), while
     * a fresh grant always carries [PageStage.Inpaint]. On a sibling detect,
     * the attach is undone (detach — never a plain release, which would remove
     * a live writer's record mid-dispatch and fail-close its commit) and the
     * page is skipped: rejected WITHOUT waste, logged at DEBUG (WARN if the
     * detach finds the record already moved on).
     */
    internal suspend fun tryClaimInpaintOwnership(pageKey: String): InpaintOwnership {
        val lease = pageTraceRegistry?.withLeaseWait(
            pageKey = pageKey,
            site = TranslationTraceSite.BATCH_OVERLAP_INPAINT,
            leaseKind = TranslationTraceLeaseKind.INPAINT,
        ) {
            store.tryAcquirePageStageLease(pageKey, PageStage.Inpaint, PageWriteOrigin.BATCH)
        } ?: store.tryAcquirePageStageLease(pageKey, PageStage.Inpaint, PageWriteOrigin.BATCH)
        if (lease is LeaseAcquisition.Denied) {
            return InpaintOwnership.LeaseDenied(lease)
        }
        val granted = lease as LeaseAcquisition.Granted
        if (granted.lease.stage != PageStage.Inpaint) {
            val undone = store.detachPageStageLeaseIfAttached(
                pageKey,
                PageWriteOrigin.BATCH,
                granted.lease.token,
            )
            logcat(if (undone) LogPriority.DEBUG else LogPriority.WARN) {
                "TachiyomiAT t934 overlap inpaint skipped pageHash=${pageKey.hashCode()} " +
                    "(sibling attach on a live BATCH ${granted.lease.stage} hold; " +
                    "attach ${if (undone) "detached — re-admitted at the next drain" else "already moved on"})"
            }
            return InpaintOwnership.SiblingAttach
        }
        return InpaintOwnership.Granted(granted, store.snapshot(pageKey))
    }

    private suspend fun inpaintOne(pageKey: String, overlap: Boolean): InpaintOutcome {
        return inpaintMutex.withLock {
            // A later retry takes ownership of the page again. If it is deferred
            // again below, that defer site will install the new terminal reason.
            pageTraceRegistry?.clearDeferred(pageKey)
            inFlightPage = pageKey
            try {
                //  round 2 — BATCH write-slot exclusivity (admission pre-
                // check). The relaxed candidacy gate admits a page whose own
                // translation is still in flight, and the translation lanes
                // hold the page's ONE write slot for the whole dispatch→commit
                // of that translation (the standard tail's lease in
                // `standardTranslateOutcome`, the profile envelope's commit).
                // Inpainting against a HELD slot would sibling-attach onto the
                // live hold: the inpaint's guarded write then races the
                // translation commit's plan-time CAS, and the inpaint's
                // (plain) release retires the writer's lease record out from
                // under it — the writer's commit fails closed
                // ("Batch persistence publication rejected", the /
                // signature). The hold is transient, so the page is deferred:
                // re-admitted at the NEXT drain pass (overlap arm —  track
                // V wakes the loop at every envelope commit boundary, so the
                // deferral no longer waits a whole window) or attempted once
                // by the serial drain (its existing defer-for-this-drain
                // discipline). The unsynchronized residual — a same-origin
                // writer acquiring between this snapshot and the claim below —
                // is now DETECTED and cleanly skipped by the claim's stage
                // proof (see [tryClaimInpaintOwnership]) instead of ridden.
                if (store.snapshot(pageKey).leaseToken != null) {
                    pageTraceRegistry?.markDeferred(pageKey, TranslationTraceReason.WRITE_SLOT_BUSY)
                    if (overlap) {
                        deferredUntilNextWindow += pageKey
                        // Count a serial fallback when the overlap window cannot
                        // own this page.
                        counters.serialFallbacks.incrementAndGet()
                    } else {
                        deferredByLeaseOwner += pageKey
                    }
                    logcat(LogPriority.INFO) {
                        "TachiyomiAT t924 overlap inpaint deferred pageHash=${pageKey.hashCode()} " +
                            "(batch write slot busy; re-admitted at the next drain or the serial drain)"
                    }
                    return@withLock InpaintOutcome.SlotBusy
                }
                when (val ownership = tryClaimInpaintOwnership(pageKey)) {
                    is InpaintOwnership.LeaseDenied -> {
                        pageTraceRegistry?.markDeferred(pageKey, TranslationTraceReason.LEASE_UNAVAILABLE)
                        // Never preempt the owning origin: defer the page for
                        // the rest of the pass (a MANUAL/native owner's outcome
                        // is authoritative; a later run reconciles).
                        deferredByLeaseOwner += pageKey
                        if (overlap) {
                            // Count a serial fallback after lease ownership is
                            // denied to the overlap worker.
                            counters.serialFallbacks.incrementAndGet()
                        }
                        logcat(LogPriority.INFO) {
                            "TachiyomiAT t924 overlap inpaint deferred pageHash=${pageKey.hashCode()} " +
                                "(lease owned by ${ownership.denial.owner ?: "unknown"})"
                        }
                        InpaintOutcome.LeaseDenied
                    }
                    //  track V: the acquire raced a same-origin writer's
                    // hold and the attach was cleanly undone — the page is
                    // deferred exactly like a pre-check slot-busy hit (no
                    // write, no record removal, no wasted pass).
                    InpaintOwnership.SiblingAttach -> {
                        pageTraceRegistry?.markDeferred(pageKey, TranslationTraceReason.SIBLING_ATTACH)
                        if (overlap) {
                            deferredUntilNextWindow += pageKey
                            counters.serialFallbacks.incrementAndGet()
                        } else {
                            deferredByLeaseOwner += pageKey
                        }
                        InpaintOutcome.SlotBusy
                    }
                    is InpaintOwnership.Granted -> {
                        val lease = ownership.lease
                        val snapshot = ownership.snapshot
                        val live = snapshot.page
                        // Fresh-snapshot re-check mirroring [nextInpaintCandidate]'s
                        //  relaxed gate: OCR-final + has mask, translation status
                        // irrelevant (inpaint's data dependency is detection/OCR only).
                        if (live == null ||
                            live.ocrStatus != StageStatus.READY ||
                            (live.blocks.isEmpty() && live.inpaintMaskBoxes.isEmpty()) ||
                            inpaintedThisRun.contains(pageKey) ||
                            live.inpaintStatus == StageStatus.READY &&
                            !plannedInpaintNeedsWork(pageKey) ||
                            live.isTextlessTerminal
                        ) {
                            return@withLock InpaintOutcome.NoWork
                        }
                        // Fresh identity so the EXISTING lane's guarded INPAINT merges
                        // (and the cleaned-image publication substage) fence against
                        // the lease THIS scheduler just acquired.
                        val identity = BatchWriteIdentity(
                            generation = snapshot.generation,
                            pageVersion = snapshot.pageVersion,
                            leaseToken = lease.lease.token,
                            candidateGenerationId = snapshot.candidateGenerationId,
                            dependencyFingerprint = snapshot.dependencyFingerprint,
                            artifactPageVersion = snapshot.artifactPageVersion,
                        )
                        batchWriteIdentities[pageKey] = identity
                        var committed = false
                        try {
                            nativeWorker.runInpaintStage(pageKey)
                            val after = store.snapshot(pageKey)
                            committed = after.page?.inpaintStatus == StageStatus.READY
                            if (committed) {
                                inpaintedThisRun += pageKey
                                if (overlap) {
                                    counters.overlapInpaintsExecuted.incrementAndGet()
                                    firstOverlapCommitAtMs.compareAndSet(0L, nowEpochMs())
                                } else {
                                    counters.serialInpaintsExecuted.incrementAndGet()
                                }
                                runCatching { onInpaintCommitted?.invoke(pageKey) }.onFailure { t ->
                                    logcat(LogPriority.WARN) {
                                        "TachiyomiAT t924 overlap post-inpaint hook failed pageHash=${pageKey.hashCode()}: $t"
                                    }
                                }
                            } else {
                                counters.overlapInpaintFailures.incrementAndGet()
                            }
                            if (committed) InpaintOutcome.Committed else InpaintOutcome.Failed
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: BatchContentionRejectedException) {
                            //  coexistence: the page's guarded publication lost a
                            // precondition race with a concurrent writer (typically the
                            // reader's live translate-on-view lane) even after a
                            // fresh-snapshot retry. The concurrent owner's committed
                            // outcome is authoritative — defer the page for the rest
                            // of the pass; the owner (or a later run) reconciles it.
                            // Retrying here livelocked the whole ordered drain.
                            counters.overlapInpaintFailures.incrementAndGet()
                            pageTraceRegistry?.markDeferred(pageKey, TranslationTraceReason.CONCURRENT_WRITER)
                            deferredByLeaseOwner += pageKey
                            logcat(LogPriority.WARN) {
                                "TachiyomiAT t924 overlap inpaint yielded pageHash=${pageKey.hashCode()} " +
                                    "(concurrent writer owns the page; owner outcome or a later run reconciles)"
                            }
                            InpaintOutcome.Deferred
                        } catch (t: Throwable) {
                            counters.overlapInpaintFailures.incrementAndGet()
                            logcat(LogPriority.WARN) {
                                "TachiyomiAT t924 overlap inpaint failed pageHash=${pageKey.hashCode()} " +
                                    "error=${t::class.java.simpleName}: ${t.message ?: "no message"}"
                            }
                            InpaintOutcome.Failed
                        } finally {
                            batchWriteIdentities.remove(pageKey)
                            //  discipline: the lease is released only after the
                            // inpaint attempt settled (commit, failure, or teardown).
                            runCatching { releaseBatchLease(pageKey) }
                        }
                    }
                }
            } finally {
                inFlightPage = null
            }
        }
    }

    companion object {

        /**
         *  track V telemetry cadence: one INFO throughput line per this
         * many overlap commits ([logInpaintThroughput]).
         */
        private const val THROUGHPUT_LOG_EVERY_PAGES = 10L
    }
}
