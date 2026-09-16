package eu.kanade.translation.pipeline.batch

import eu.kanade.translation.ChapterTranslationStore
import eu.kanade.translation.LeaseAcquisition
import eu.kanade.translation.PageWriteOrigin
import eu.kanade.translation.model.PageStage
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.hasRenderedResult
import eu.kanade.translation.model.isTextlessTerminal
import eu.kanade.translation.translator.BatchRequestSublimitGate
import eu.kanade.translation.translator.ProviderRequestMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * T924 Stage 7 (WP7, implementation-sequence §S7 / ST-13): the inpaint-overlap
 * scheduler behind FF-01+Stage 7. Runs the EXISTING serial native inpaint lane
 * (`NativeLaneWorker.runInpaintStage` — re-decode + detector mask + durable
 * inpaint merge, unchanged admission rules) inside the window of the single
 * in-flight remote translation request.
 *
 * Binding rules (never-rules, enforced structurally):
 *  - Inpaint work runs EXCLUSIVELY while [onRemoteWindowOpened] has been
 *    signalled (the coordinator wraps the Batch sub-limit gate so every
 *    provider envelope dispatch opens exactly one window) or in the serial
 *    post-translate drain ([drainSerial] — the legacy post-translate serial
 *    semantics, the gate-6.5 "keep serial" arm). T934 track I decoupling
 *    (2026-09-16): candidate admission NO LONGER waits for the page's own
 *    translation — the inpaint artifact fingerprint
 *    (`StageFingerprints.inpaint`) has no translation input, so the data
 *    dependency is detection/OCR only and an OCR-final page is a candidate
 *    regardless of translation status (see [nextInpaintCandidate]). What is
 *    NOT relaxed: detector/OCR NEVER run here — the OCR preflight phase is
 *    fully terminal before TRANSLATE (the scheduler is only started for the
 *    TRANSLATE phase, below), and the native lane itself stays strictly
 *    one-native-job-at-a-time process-wide.
 *  - Strictly ONE native inpaint at a time ([inpaintMutex]); memory stays
 *    within the legacy one-decoded-bitmap envelope (gate 6.4 budget: overlap
 *    adds no second concurrent bitmap, it only re-times the same lane).
 *  - Native admission is unchanged: the page's BATCH inpaint lease is
 *    acquired with `tryAcquirePageStageLease` (attaches behind MANUAL, never
 *    preempts); a denied lease skips the page (counted) — MANUAL/native
 *    quarantine rules are never preempted. T934 track I round 2 adds a
 *    slot-free ADMISSION pre-check ahead of the acquire (see
 *    [inpaintOne]): a page whose BATCH write slot is CURRENTLY held by an
 *    in-flight same-origin writer (the standard translate tail or the
 *    profile envelope commit — both hold the page's one write slot for the
 *    whole dispatch→commit of the page's OWN translation) is deferred to
 *    the next window / the serial drain instead of sibling-attaching onto
 *    the live hold. Attaching would let the inpaint write race the
 *    translation commit's plan-time CAS, and the inpaint's (plain) release
 *    would retire the writer's lease record out from under it — the
 *    commit then fails closed ("Batch persistence publication rejected").
 *    The hold is transient (freed at the writer's commit), so this defers
 *    on WRITE EXCLUSIVITY only — it is the same-page replacement for the
 *    old translation-status gate, not a re-coupling to translation data.
 *  - Defer-retry discipline (T934 round 2): every unsuccessful attempt is
 *    retried only when something CHANGES, never spun on inside a still-
 *    open window. Lease-denied (foreign owner) pages are deferred for the
 *    rest of the pass (the owner's outcome is authoritative); slot-busy
 *    and lane-failed pages are deferred until the NEXT window opens (or
 *    the serial drain starts) — one attempt per page per window. The
 *    serial drain keeps its existing "deferred for the rest of this
 *    drain" rule. This replaces the previous behavior where a failed page
 *    was immediately re-selected inside the same window, which spun the
 *    loop hot (a hang under virtual time — StandardPipelineCoordinatorTest
 *    T5) and burned the lane on a page whose outcome had just settled.
 *  - Starts only AFTER profile freeze: the coordinator constructs/starts this
 *    scheduler only for the TRANSLATE phase, which is entered only after
 *    ST-10 PROFILE_FROZEN (contract ST-13 entry).
 *  - Cancel/teardown safe: [stopOverlap] stops window-driven work between
 *    pages; a running inpaint finishes or is cancelled through the lane's own
 *    timeout/cancellation idiom; the write identity is deregistered and the
 *    BATCH lease released in `finally` (TX-06 release discipline — the lease
 *    is released only after the inpaint attempt settled).
 *
 * The lane's durable write path is consumed as-is: the scheduler registers a
 * [BatchWriteIdentity] (fresh snapshot + the just-acquired lease token) into
 * the SAME identity map the legacy write gate reads, so
 * `NativeLaneWorker.runInpaintStage` performs its guarded INPAINT merges and
 * durable mask/cleaned-image publications exactly like the legacy schedule
 * (`mergeInpaint` mask-fingerprint identity per ST-13). If the lane is busy,
 * the lease is denied, or a page write is rejected, the page simply remains
 * for the serial post-translate drain — semantics identical to the legacy
 * serial schedule (fallback discipline).
 *
 * Gate 6.5 counters ([countersSnapshot]) feed the keep-or-revert decision
 * rule ("if median wall-time improvement < 5% on the 200-page reference run,
 * retain the simpler serial schedule without changing semantics") — evidence
 * goes to `evidence/stage6/overlap-vs-serial.md`.
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
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
) {

    /** Gate-6.5 evidence counters (operational only, never fingerprinted). */
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
     * D2 hook: invoked after a page's inpaint durably committed (READY) so the
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
        // One attempt per page per window (T934 round 2): a new window is a
        // state change — slot holds may have cleared, the lane may accept
        // again — so every deferred page gets exactly one fresh attempt.
        deferredUntilNextWindow.clear()
        counters.overlapWindowsCount.incrementAndGet()
        windowOpenedAtMs.set(nowEpochMs())
        progressedInWindow = false
        events.trySend(LoopEvent.WindowOpened)
    }

    fun onRemoteWindowClosed() {
        val openedAt = windowOpenedAtMs.getAndSet(0)
        if (openedAt > 0) {
            counters.overlapWindowsMs.addAndGet((nowEpochMs() - openedAt).coerceAtLeast(0))
        }
        windowOpen.set(false)
        // Gate-6.5 serial-fallback accounting is per deferred PAGE (counted in
        // [inpaintOne]'s overlap lease-denial and slot-busy branches), not per
        // window: a window that both deferred a MANUAL-owned page and
        // committed another page still produced overlap progress.
        events.trySend(LoopEvent.WindowClosed)
    }

    /**
     * The gate wrapper installed by the coordinator: delegates admission and
     * the request block unchanged to the process-wide sub-limit gate, and
     * signals the overlap windows around it (gate 6.5 timing basis).
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
     * Window-driven loop: runs while the TRANSLATE phase is dispatching.
     * Between pages it suspends on the event channel (no polling); when the
     * window closes mid-page the CURRENT page finishes (it owns the native
     * lane) and no new page starts. Exports nothing — all durable progress is
     * store state.
     */
    suspend fun runOverlapLoop() {
        for (event in events) {
            when (event) {
                LoopEvent.Stopped -> return
                LoopEvent.WindowClosed -> Unit
                LoopEvent.WindowOpened -> {
                    while (windowOpen.get() && !stopped.get()) {
                        val pageKey = nextInpaintCandidate() ?: break
                        when (inpaintOne(pageKey, overlap = true)) {
                            InpaintOutcome.Committed, InpaintOutcome.NoWork ->
                                progressedInWindow = true
                            // One attempt per window (T934 round 2): the lane
                            // just settled this page's outcome as failed — it
                            // is not re-attempted until the NEXT window opens
                            // (or the serial drain starts). Re-selecting it
                            // inside the still-open window spun the loop hot
                            // on virtual time (the T5 hang) and on real lanes.
                            InpaintOutcome.Failed -> {
                                deferredUntilNextWindow += pageKey
                                progressedInWindow = true
                            }
                            // Yielded to a concurrent writer — the page is
                            // settled for this pass (progress = the loop moved
                            // past it, so the window can close cleanly).
                            InpaintOutcome.Deferred -> progressedInWindow = true
                            // Lease-denial accounting is per deferred PAGE
                            // (serialFallbacks inside inpaintOne); the loop
                            // keeps trying the remaining candidates. SlotBusy
                            // is the same shape (the page went to
                            // [deferredUntilNextWindow] inside inpaintOne).
                            InpaintOutcome.LeaseDenied, InpaintOutcome.SlotBusy -> Unit
                        }
                    }
                    // Window still open but nothing left to inpaint right now:
                    // wait for the close (new candidates are picked up by the
                    // next window or the serial drain).
                }
            }
        }
    }

    /**
     * The legacy serial post-translate inpaint arm: drains every remaining
     * page (OCR-final, inpaint still pending — regardless of translation
     * status, T934 track I) one at a time through the SAME lane — semantics
     * identical to the pre-overlap serial schedule. Called by the
     * coordinator's FINALIZE (ST-14) before the stranded-page
     * reconciliation.
     *
     * A page that does NOT commit (lane failure, or a T925 yield to a
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
        // T934 track I round 3: settle the decoupling's order-inverted pages
        // (see [stampRenderTerminalOrphans]).
        stampRenderTerminalOrphans()
    }

    /**
     * T934 track I round 3 — the drain-side render-terminal adoption sweep
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
                (candidate.translationStatus == StageStatus.READY ||
                    candidate.translationStatus == StageStatus.PARTIAL) &&
                    candidate.inpaintStatus == StageStatus.READY &&
                    candidate.cleanedImageName != null &&
                    candidate.renderStatus == StageStatus.PENDING &&
                    candidate.blocks.any { it.translation.isNotBlank() }
            if (!displayComplete) continue
            when (store.tryAcquirePageStageLease(pageKey, PageStage.Render, PageWriteOrigin.BATCH)) {
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
                        if (renderStatus == StageStatus.PENDING) {
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
     * T934 round 2: pages deferred until the NEXT window opens — a lane-failed
     * attempt (the outcome is settled for this window) or a page whose BATCH
     * write slot was busy at admission (an in-flight same-origin translation
     * writer holds it; the hold clears at that writer's commit). Cleared in
     * [onRemoteWindowOpened] and at [drainSerial] start, so every page gets
     * exactly one attempt per window / per drain pass and nothing is retried
     * inside the window that just deferred it.
     */
    private val deferredUntilNextWindow = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private fun nextInpaintCandidate(): String? {
        val state = store.state.value
        for (pageKey in orderedPageKeys) {
            if (pageKey == inFlightPage) continue
            if (pageKey in deferredByLeaseOwner) continue
            if (pageKey in deferredUntilNextWindow) continue
            val page = state[pageKey] ?: continue
            if (page.isTextlessTerminal) continue
            if (page.hasRenderedResult) continue
            // T934 track I: the inpaint artifact fingerprint
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
            // Textless preservation: an OCR-final page with no blocks has no
            // mask to erase. Before the translation stage marks it
            // SKIPPED (making [isTextlessTerminal] true) this early skip keeps
            // the lane free for pages with real work.
            if (page.blocks.isEmpty()) continue
            val inpaint = page.inpaintStatus
            if (inpaint == StageStatus.READY ||
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

    private suspend fun inpaintOne(pageKey: String, overlap: Boolean): InpaintOutcome {
        return inpaintMutex.withLock {
            inFlightPage = pageKey
            try {
                // T934 round 2 — BATCH write-slot exclusivity (admission pre-
                // check). The relaxed candidacy gate admits a page whose own
                // translation is still in flight, and the translation lanes
                // hold the page's ONE write slot for the whole dispatch→commit
                // of that translation (the standard tail's lease in
                // `standardTranslateOutcome`, the profile envelope's commit).
                // Inpainting against a HELD slot would sibling-attach onto the
                // live hold: the inpaint's guarded write then races the
                // translation commit's plan-time CAS, and the inpaint's TX-06
                // (plain) release retires the writer's lease record out from
                // under it — the writer's commit fails closed
                // ("Batch persistence publication rejected", the D7/D2
                // signature). The hold is transient, so the page is deferred:
                // re-admitted at the NEXT window (overlap arm — the decoupled
                // overlap never waits for translation DATA, only for the
                // page's own in-flight batch WRITE to settle) or attempted
                // once by the serial drain (its existing defer-for-this-drain
                // discipline). The unsynchronized residual: a same-origin
                // writer acquiring between this snapshot and the acquire below
                // yields a sibling grant; the sibling's plain release then
                // retires the record per the R1 sibling contract and the
                // writer heals (guarded refresh) or pauses — a narrow race
                // versus the deterministic breakage this check removes.
                if (store.snapshot(pageKey).leaseToken != null) {
                    if (overlap) {
                        deferredUntilNextWindow += pageKey
                        // Gate-6.5 accounting: this page could not ride the
                        // overlap window and falls back to the serial arm.
                        counters.serialFallbacks.incrementAndGet()
                    } else {
                        deferredByLeaseOwner += pageKey
                    }
                    logcat(LogPriority.INFO) {
                        "TachiyomiAT t924 overlap inpaint deferred pageHash=${pageKey.hashCode()} " +
                            "(batch write slot busy; re-admitted at the next window or the serial drain)"
                    }
                    return@withLock InpaintOutcome.SlotBusy
                }
                // Native admission unchanged: BATCH attaches behind MANUAL,
                // never preempts (a denied lease skips the page — the serial
                // drain / a later run reconciles it).
                val lease = store.tryAcquirePageStageLease(pageKey, PageStage.Inpaint, PageWriteOrigin.BATCH)
                if (lease !is LeaseAcquisition.Granted) {
                    // Never preempt the owning origin: defer the page for the
                    // rest of the pass (a MANUAL/native owner's outcome is
                    // authoritative; a later run reconciles).
                    deferredByLeaseOwner += pageKey
                    if (overlap) {
                        // Gate-6.5 accounting: this page could not ride the
                        // overlap window and falls back to the serial arm.
                        counters.serialFallbacks.incrementAndGet()
                    }
                    logcat(LogPriority.INFO) {
                        "TachiyomiAT t924 overlap inpaint deferred pageHash=${pageKey.hashCode()} " +
                            "(lease owned by ${if (lease is LeaseAcquisition.Denied) lease.owner else "unknown"})"
                    }
                    return@withLock InpaintOutcome.LeaseDenied
                }
                val snapshot = store.snapshot(pageKey)
                val live = snapshot.page
                // Fresh-snapshot re-check mirroring [nextInpaintCandidate]'s
                // T934 relaxed gate: OCR-final + has mask, translation status
                // irrelevant (inpaint's data dependency is detection/OCR only).
                if (live == null ||
                    live.ocrStatus != StageStatus.READY ||
                    live.blocks.isEmpty() ||
                    live.inpaintStatus == StageStatus.READY ||
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
                        if (overlap) {
                            counters.overlapInpaintsExecuted.incrementAndGet()
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
                    // T925 coexistence: the page's guarded publication lost a
                    // precondition race with a concurrent writer (typically the
                    // reader's live translate-on-view lane) even after a
                    // fresh-snapshot retry. The concurrent owner's committed
                    // outcome is authoritative — defer the page for the rest
                    // of the pass; the owner (or a later run) reconciles it.
                    // Retrying here livelocked the whole ordered drain.
                    counters.overlapInpaintFailures.incrementAndGet()
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
                    // TX-06 discipline: the lease is released only after the
                    // inpaint attempt settled (commit, failure, or teardown).
                    runCatching { releaseBatchLease(pageKey) }
                }
            } finally {
                inFlightPage = null
            }
        }
    }

    companion object
}
