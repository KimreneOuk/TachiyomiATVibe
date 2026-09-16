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
 *  - Inpaint overlaps ONLY the remote wait. The scheduler runs inpaint work
 *    exclusively while [onRemoteWindowOpened] has been signalled (the
 *    coordinator wraps the Batch sub-limit gate so every provider envelope
 *    dispatch opens exactly one window) or in the serial post-translate drain
 *    ([drainSerial] — the legacy post-translate serial semantics, the gate-6.5
 *    "keep serial" arm). Detector/OCR NEVER run here: the OCR preflight phase
 *    is fully terminal before TRANSLATE, and the native lane itself stays
 *    strictly one-native-job-at-a-time process-wide.
 *  - Strictly ONE native inpaint at a time ([inpaintMutex]); memory stays
 *    within the legacy one-decoded-bitmap envelope (gate 6.4 budget: overlap
 *    adds no second concurrent bitmap, it only re-times the same lane).
 *  - Native admission is unchanged: the page's BATCH inpaint lease is
 *    acquired with `tryAcquirePageStageLease` (attaches behind MANUAL, never
 *    preempts); a denied lease skips the page (counted) — MANUAL/native
 *    quarantine rules are never preempted.
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
        // [inpaintOne]'s overlap lease-denial branch), not per window: a window
        // that both deferred a MANUAL-owned page and committed another page
        // still produced overlap progress.
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
                            InpaintOutcome.Committed, InpaintOutcome.Failed, InpaintOutcome.NoWork ->
                                progressedInWindow = true
                            // Yielded to a concurrent writer — the page is
                            // settled for this pass (progress = the loop moved
                            // past it, so the window can close cleanly).
                            InpaintOutcome.Deferred -> progressedInWindow = true
                            // Lease-denial accounting is per deferred PAGE
                            // (serialFallbacks inside inpaintOne); the loop
                            // keeps trying the remaining candidates.
                            InpaintOutcome.LeaseDenied -> Unit
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
     * page (translation committed, inpaint still pending) one at a time
     * through the SAME lane — semantics identical to the pre-overlap
     * schedule. Called by the coordinator's FINALIZE (ST-14) before the
     * stranded-page reconciliation.
     *
     * A page that does NOT commit (lane failure, or a T925 yield to a
     * concurrent writer) is DEFERRED for the rest of this drain — never
     * retried in-pass, which livelocked the ordered drain when a live reader
     * lane kept invalidating the page. FINALIZE's reconciliation (and a
     * later run, or the owner itself) settles deferred pages.
     */
    suspend fun drainSerial() {
        stopOverlap()
        while (true) {
            val pageKey = nextInpaintCandidate() ?: break
            when (inpaintOne(pageKey, overlap = false)) {
                InpaintOutcome.Committed -> Unit // counted inside inpaintOne
                // Deferred/failed/denied pages are reconciled at FINALIZE,
                // not retried forever inside this drain.
                else -> deferredByLeaseOwner += pageKey
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

    private fun nextInpaintCandidate(): String? {
        val state = store.state.value
        for (pageKey in orderedPageKeys) {
            if (pageKey == inFlightPage) continue
            if (pageKey in deferredByLeaseOwner) continue
            val page = state[pageKey] ?: continue
            if (page.isTextlessTerminal) continue
            if (page.hasRenderedResult) continue
            val translation = page.translationStatus
            if (translation != StageStatus.READY && translation != StageStatus.PARTIAL) continue
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

    private enum class InpaintOutcome { Committed, Failed, NoWork, LeaseDenied, Deferred }

    private suspend fun inpaintOne(pageKey: String, overlap: Boolean): InpaintOutcome {
        return inpaintMutex.withLock {
            inFlightPage = pageKey
            try {
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
                if (live == null ||
                    (live.inpaintStatus == StageStatus.READY || live.isTextlessTerminal) ||
                    (live.translationStatus != StageStatus.READY && live.translationStatus != StageStatus.PARTIAL)
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
