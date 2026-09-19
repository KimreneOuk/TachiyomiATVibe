package eu.kanade.translation.coexistence

import eu.kanade.translation.artifact.loadArtifact

import com.hippo.unifile.UniFile
import eu.kanade.translation.ChapterTranslationStore
import eu.kanade.translation.TranslationPipeline
import eu.kanade.translation.artifact.AtomicChapterDocuments
import eu.kanade.translation.artifact.ChapterArtifactLayout
import eu.kanade.translation.artifact.ChapterArtifactEngine
import eu.kanade.translation.artifact.CleanedImageProbe
import eu.kanade.translation.artifact.FakeChapterDocumentIo
import eu.kanade.translation.artifact.ArtifactSeed
import eu.kanade.translation.artifact.ProbedImage
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.pipeline.batch.ChunkCompletionOutcome
import eu.kanade.translation.scheduling.RollingAutoCoordinator
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.translation.pools.BitmapPool

/**
 * T917 Phase 4 — D7 engine-epoch stop race + drain-not-close (phase4-design §1).
 *
 * The only production `pipeline.closeEngines()` caller is `ChapterTranslator.stop`
 * (ACTION_STOP via `TranslationManager.clearQueue`, the reader Stop button, and the
 * translation toggle). While a single page is mid-PROVIDER-call the native lane is
 * idle, so today's close tears the captured translator down UNDER the in-flight
 * call — the accepted-trade-off comment at SinglePageHttpRenderPhase (:143-147).
 * The target contract (phase4-design §1.2):
 *  - the borrow is observable (`translatorUseCount`) and close DRAINS it within a
 *    bounded grace instead of killing in-flight work (a);
 *  - close happened only AFTER `endTranslatorUse` on the grace path (b, close-order
 *    event evidence);
 *  - on grace expiry the close proceeds under the call and the EPOCH GUARD transparently
 *    retries exactly once against the REBUILT translator; a second mid-retry close
 *    fails the page honestly with no loop (c);
 *  - a parked NATIVE call still blocks the close (tryRunExclusive — idle-lane contract,
 *    unchanged) (d);
 *  - the D6 provider drain grace can never be shorter than the chain's own legitimate
 *    budget (P3-finding-4 bound, §1.6) (e).
 *
 * RED (committed first, phase4-design §6 step 1): (a) fails because the parked call
 * FAILS when closeEngines closes the fake translator mid-call (the fake models the
 * production close defect: providers close their executors/pools in `close()`, audit
 * H-09); (b) fails because the close lands while the borrow is still held; (c) and
 * (d)'s epoch probe fail on the missing EngineLane seams (named assertions, bridge
 * pattern — never a timeout); (e) fails on the 90_000 < 210_000 bound. (d)'s
 * no-close guard is a green pin of the contract that must survive.
 *
 * Fixture: the REAL production graph over the REAL AUTO prepared-page boundary
 * (`pipeline.prepareSinglePage` + `pipeline.translatePreparedPage` — the two calls the
 * RollingAutoCoordinator makes, driven directly for determinism; the coordinator's own
 * drain/cancel semantics are already covered by D6DrainNotCancelTest) on an
 * ARTIFACT-authority store (D9 fresh-chapter recipe) so the D9 ledger is observable.
 * The stop analogue is the REAL `manager.clearQueue()` → `translator.stop()` →
 * `pipeline.closeEngines()` chain.
 */
class D7EngineEpochStopRaceTest {

    companion object {
        private const val AWAIT_TIMEOUT_MS = TranslationCoexistenceHarness.AWAIT_TIMEOUT_MS
        private const val NEGATIVE_PROBE_MS = TranslationCoexistenceHarness.NEGATIVE_PROBE_MS
        private const val CHAPTER_DIR = "D7 Epoch Chapter"

        /** The design-mandated ledger sidecar path for the fixture chapter. */
        private const val LEDGER_FILE = "${CHAPTER_DIR}_artifacts/attempts/ledger.json"
    }

    private val io = FakeChapterDocumentIo()
    private var harness: TranslationCoexistenceHarness? = null
    private var autoScope: CoroutineScope? = null

    @AfterEach
    fun tearDown() {
        autoScope?.cancel()
        autoScope = null
        harness?.let { h ->
            runCatching { h.removeGraphicsShims() }
            runCatching { h.close() }
        }
        harness = null
        runCatching { BitmapPool.releaseAll() }
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    /** Production fresh-chapter recipe over the shared IO (D9/D6 precedent), empty-start. */
    private fun freshStore(): ChapterTranslationStore {
        // The AUTO prepared page names "$pageKey.cleaned.jpg" but the harness's
        // publish shim (note §1.2.3) performs the guarded store write WITHOUT
        // Bitmap.compress, so the companion image file has no bytes on the fake
        // IO. Seed the one display-base file the promotion validates and give
        // the directly-constructed artifact store the bounded decode probe stub
        // (JVM has no BitmapFactory; the fixture builds the ChapterArtifactEngine
        // itself, so the companion-object seam does not apply) answering the
        // decoded page's 100x100 — TranslationManagerArtifactReadTest precedent.
        val layout = ChapterArtifactLayout(CHAPTER_DIR)
        val imageProbe = CleanedImageProbe { ProbedImage(100, 100) }
        val artifactStore = ChapterArtifactEngine(
            AtomicChapterDocuments(io),
            layout,
            imageProbe,
        )
        val manifest = artifactStore
            .loadArtifact(ArtifactSeed(migratedAtEpochMs = 1L))
            .manifest
        return ChapterTranslationStore(
            translationFile = null as UniFile?,
            fileCreator = null,
            initialPages = emptyMap(),
            artifactStore = artifactStore,
            initialArtifactManifest = manifest,
        )
    }

    private fun createHarness(drainGraceMs: Long?): ChapterTranslationStore {
        val store = freshStore()
        harness = TranslationCoexistenceHarness.create(
            pageKeys = listOf("p0"),
            preRegisterInStore = false,
            storeOverride = store,
            drainGraceMs = drainGraceMs,
        )
        // The AUTO prepared-page boundary resolves its source through the reader
        // stream peek (the coordinator passes streamFn = null); without a
        // registered stream the fresh path soft-skips at chapter-file lookup.
        harness!!.registerReaderStream(TranslationCoexistenceHarness.CHAPTER_ID, "p0")
        return store
    }

    private class AutoRun(val outcome: CompletableDeferred<ChunkCompletionOutcome?>) {
        /** Set when the boundary died before the paid call (fixture diagnosis). */
        @Volatile var earlyFailure: Throwable? = null

        @Volatile var job: Job? = null

        fun cancelJob() {
            job?.cancel()
        }
    }

    /**
     * Drives the REAL AUTO boundary pair the rolling coordinator issues:
     * prepareSinglePage (native, under the permit) then translatePreparedPage
     * (HTTP+render, OUTSIDE the permit — the phase D7 targets). The outcome
     * deferred converts every failure into a named assertion, never a timeout.
     */
    private fun launchAutoPage(pageKey: String): AutoRun {
        val h = checkNotNull(harness)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("d7-auto"))
        autoScope = scope
        val outcome = CompletableDeferred<ChunkCompletionOutcome?>()
        val run = AutoRun(outcome)
        run.job = scope.launch {
            try {
                val chapter = h.chapterFor(TranslationCoexistenceHarness.CHAPTER_ID)
                val prepared = h.pipeline.prepareSinglePage(h.manga, chapter, h.source, pageKey)
                if (prepared == null) {
                    run.earlyFailure = IllegalStateException("prepareSinglePage returned null")
                    outcome.complete(null)
                    return@launch
                }
                outcome.complete(h.pipeline.translatePreparedPage(h.manga, chapter, h.source, prepared))
            } catch (e: CancellationException) {
                outcome.cancel()
            } catch (t: Throwable) {
                run.earlyFailure = t
                outcome.completeExceptionally(t)
            }
        }
        return run
    }

    private suspend fun awaitProviderStart(auto: AutoRun, pageKey: String) {
        val h = checkNotNull(harness)
        try {
            h.barrier.awaitArrivalWithin(CoexistenceBarrier.BarrierPoint.PROVIDER_START, pageKey, AWAIT_TIMEOUT_MS)
        } catch (e: TimeoutCancellationException) {
            val early = runCatching {
                withTimeoutOrNull(250) { auto.outcome.await() }
            }.getOrNull()
            throw AssertionError(
                "D7 fixture: the AUTO page never reached PROVIDER_START — the prepared-page " +
                    "boundary did not reach the paid call (paidCalls=${h.transportCallsFor(pageKey)}, " +
                    "earlyOutcome=$early, earlyFailure=${auto.earlyFailure})",
                e,
            )
        }
    }

    // ------------------------------------------------------------------
    // ledger-file observation (D9 schema mirror)
    // ------------------------------------------------------------------

    @Serializable
    private data class LedgerMirror(
        val entries: List<kotlinx.serialization.json.JsonObject> = emptyList(),
        val consecutiveUnresolved: Map<String, Int> = emptyMap(),
    )

    private val json = Json { ignoreUnknownKeys = true }

    private fun readLedger(): LedgerMirror? =
        io.read(LEDGER_FILE)?.let { bytes -> json.decodeFromString<LedgerMirror>(bytes.decodeToString()) }

    /** Latent epoch probe: no-op until EngineLane exposes the accessor (GREEN commit). */
    private fun currentEngineEpochOrNull(): Long? = try {
        val method = Class.forName("eu.kanade.translation.pipeline.EngineLane")
            .getDeclaredMethod("currentEngineEpoch")
        method.isAccessible = true
        method.invoke(checkNotNull(harness).engineLane) as Long
    } catch (_: Throwable) {
        null
    }

    // ------------------------------------------------------------------
    // (e) P3-finding-4 bound (pure — runs before any graph work)
    // ------------------------------------------------------------------

    @Test
    fun `provider drain grace budget is at least the attach chain budget`() {
        val grace = runCatching {
            RollingAutoCoordinator::class.java.getField("PROVIDER_DRAIN_GRACE_MS").get(null) as Long
        }.getOrElse {
            throw AssertionError(
                "T917 D7 RED defect: PROVIDER_DRAIN_GRACE_MS is missing — the §1.6 alignment target does not exist",
                it,
            )
        }
        val attach = TranslationPipeline::class.java.getField("ATTACH_TIMEOUT_MS").get(null) as Long
        if (grace < attach) {
            throw AssertionError(
                "T917 P3-finding-4 defect: PROVIDER_DRAIN_GRACE_MS (${grace}ms) is SHORTER than the drained " +
                    "chain's own legitimate budget ATTACH_TIMEOUT_MS (${attach}ms) — ONNX (<=90s) plus " +
                    "HTTP+render (<=120s) run sequentially, so a healthy long call can be cut " +
                    "cancellation-class mid-chain and its D9 entry left unresolved " +
                    "(phase4-design §1.6; phase3-verification finding 4)",
            )
        }
    }

    // ------------------------------------------------------------------
    // (a) THE stop race: an AUTO page parked mid-paid-call must survive Stop
    // ------------------------------------------------------------------

    @Test
    fun `stop during an in flight auto page drains the call to a terminal commit`() = runBlocking<Unit> {
        val store = createHarness(drainGraceMs = null)
        val h = checkNotNull(harness)
        h.installGraphicsShims()
        h.barrier.arm(CoexistenceBarrier.BarrierPoint.PROVIDER_START, "p0")
        val auto = launchAutoPage("p0")
        try {
            awaitProviderStart(auto, "p0")

            // ACTION_STOP analogue: TranslationManager.clearQueue → translator.stop()
            // → pipeline.closeEngines(). The native lane is idle (the page is in its
            // HTTP phase), so today the close kills the captured translator mid-call.
            h.manager.clearQueue()

            h.barrier.release(CoexistenceBarrier.BarrierPoint.PROVIDER_START, "p0")
            val outcome = withTimeout(AWAIT_TIMEOUT_MS) { auto.outcome.await() }

            withClue(
                "T917 D7 §1.4.1a defect: the stopped-over AUTO call must complete (drain wins, or the " +
                    "epoch retry wins) — RED fails it mid-call because closeEngines closed the " +
                    "translator under the parked call",
            ) {
                if (outcome !is ChunkCompletionOutcome.Completed) {
                    throw AssertionError(
                        "T917 D7 §1.4.1a defect: the parked AUTO call FAILED across the stop — " +
                            "outcome was $outcome",
                    )
                }
            }
            withClue(
                "T917 D7 §1.4.1a defect: the drained/retried call must commit a terminal translation " +
                    "state even though Stop was pressed mid-call",
            ) {
                store.state.value.getValue("p0").translationStatus shouldBe StageStatus.READY
            }
            withClue(
                "T917 D7 §1.4.1a: paid calls bounded (1 when the drain wins, 2 when grace expired + retry)",
            ) {
                val paid = h.transportCallsFor("p0")
                if (paid < 1 || paid > 2) {
                    throw AssertionError("T917 D7 §1.4.1a defect: paid calls = $paid, expected 1..2")
                }
            }
            withClue("T917 D7 §1.4.1a: the stop must never CANCEL the in-flight call (drain-not-cancel)") {
                h.transportShared.cancelledCalls.get() shouldBe 0
            }
            withClue(
                "T917 D7 §1.4.1a: any SECOND call must land on the REBUILT translator instance — " +
                    "no call may reuse the closed instance",
            ) {
                val serving = h.transportShared.servingOrder["p0"].orEmpty()
                if (serving.size > 1 && serving.distinct().size != serving.size) {
                    throw AssertionError(
                        "T917 D7 §1.4.1a defect: a call reused a translator instance across the close — " +
                            "serving instances $serving",
                    )
                }
            }
            withClue(
                "T917 D7 §1.4.1a: the D9 attempt entry must be RESOLVED (a drained/retried call is a " +
                    "completed call, not a crash)",
            ) {
                val ledger = readLedger()
                    ?: throw AssertionError("T917 D7 fixture: the attempt ledger sidecar was never written")
                ledger.entries shouldBe emptyList()
            }
            // The drain (or retry) eventually releases the OLD engines to be closed.
            withTimeout(AWAIT_TIMEOUT_MS) { h.fakeTransport.closedSignal.await() }
        } finally {
            auto.cancelJob()
        }
    }

    // ------------------------------------------------------------------
    // (b) grace path: close waits for the borrow, in that order
    // ------------------------------------------------------------------

    @Test
    fun `engine close with a large grace waits for the translator borrow to end`() = runBlocking<Unit> {
        val store = createHarness(drainGraceMs = 60_000L)
        val h = checkNotNull(harness)
        h.installGraphicsShims()
        h.barrier.arm(CoexistenceBarrier.BarrierPoint.PROVIDER_START, "p0")
        val auto = launchAutoPage("p0")
        try {
            awaitProviderStart(auto, "p0")

            h.manager.clearQueue()

            // Negative probe: with a large grace the close must NOT have happened
            // while the page still borrows the translator. RED fails here today:
            // closeEngines closes immediately under the parked call.
            try {
                withTimeout(NEGATIVE_PROBE_MS) { h.fakeTransport.closedSignal.await() }
                throw AssertionError(
                    "T917 D7 §1.4.1b defect: closeEngines closed the translator while the page's " +
                        "borrow was still held — the engine-drain did not wait for endTranslatorUse",
                )
            } catch (_: TimeoutCancellationException) {
                // expected: close deferred behind the drain
            }

            h.barrier.release(CoexistenceBarrier.BarrierPoint.PROVIDER_START, "p0")
            val outcome = withTimeout(AWAIT_TIMEOUT_MS) { auto.outcome.await() }
            withClue(
                "T917 D7 §1.4.1b: with the grace large enough the call itself completes untouched",
            ) {
                if (outcome !is ChunkCompletionOutcome.Completed) {
                    throw AssertionError(
                        "T917 D7 §1.4.1b defect: the call failed even though the grace had not expired — " +
                            "outcome was $outcome",
                    )
                }
            }

            // Close-order evidence: the fake's merged event log must show the call
            // ending BEFORE the close of that instance.
            withTimeout(AWAIT_TIMEOUT_MS) { h.fakeTransport.closedSignal.await() }
            val log = h.transportShared.eventLog
            val callEnd = log.indexOf("call-end:p0:inst${h.fakeTransport.instanceId}")
            val close = log.indexOf("close:inst${h.fakeTransport.instanceId}")
            withClue("T917 D7 §1.4.1b: close-order event evidence (log=$log)") {
                if (callEnd < 0 || close < 0 || close < callEnd) {
                    throw AssertionError(
                        "T917 D7 §1.4.1b defect: close happened before (or without) the borrow ending — " +
                            "callEnd@$callEnd close@$close log=$log",
                    )
                }
            }
            withClue("T917 D7 §1.4.1b: the engines must actually close once the drain finishes") {
                h.fakeTransport.closeCalls.get() shouldBe 1
                h.fakeRecognition.closeCalls.get() shouldBe 1
            }
            withClue("T917 D7 §1.4.1b: the call commits its terminal translation state") {
                store.state.value.getValue("p0").translationStatus shouldBe StageStatus.READY
            }
        } finally {
            auto.cancelJob()
        }
    }

    // ------------------------------------------------------------------
    // (c) grace-expiry path: close under the call + exactly-one epoch retry
    // ------------------------------------------------------------------

    @Test
    fun `grace expiry closes under the call and the epoch retry lands on the rebuilt translator`() = runBlocking<Unit> {
        val store = createHarness(drainGraceMs = 0L)
        val h = checkNotNull(harness)
        h.installGraphicsShims()
        h.barrier.arm(CoexistenceBarrier.BarrierPoint.PROVIDER_START, "p0")
        val auto = launchAutoPage("p0")
        try {
            awaitProviderStart(auto, "p0")

            // grace 0: expiry is immediate — the close proceeds under the parked call.
            h.manager.clearQueue()
            withClue("T917 D7 §1.4.1c: grace expiry must close anyway (bounded memory outranks the rare retry)") {
                h.fakeTransport.closedFlag.get() shouldBe true
            }

            h.barrier.release(CoexistenceBarrier.BarrierPoint.PROVIDER_START, "p0")
            val outcome = withTimeout(AWAIT_TIMEOUT_MS) { auto.outcome.await() }
            withClue(
                "T917 D7 §1.4.1c defect: the epoch guard must transparently retry the racing page ONCE " +
                    "against the REBUILT translator — RED fails the page outright",
            ) {
                if (outcome !is ChunkCompletionOutcome.Completed) {
                    throw AssertionError(
                        "T917 D7 §1.4.1c defect: the grace-expired call was not recovered by the epoch " +
                            "retry — outcome was $outcome",
                    )
                }
            }
            withClue("T917 D7 §1.4.1c: the retried call commits the terminal translation state") {
                store.state.value.getValue("p0").translationStatus shouldBe StageStatus.READY
            }
            withClue("T917 D7 §1.4.1c: the epoch retry fires EXACTLY once — original + one retry") {
                h.transportCallsFor("p0") shouldBe 2
            }
            withClue(
                "T917 D7 §1.4.1c: the SECOND call must land on the REBUILT translator instance " +
                    "(serving=${h.transportShared.servingOrder["p0"]})",
            ) {
                val serving = h.transportShared.servingOrder["p0"].orEmpty()
                if (serving.size != 2 || serving.distinct().size != 2) {
                    throw AssertionError(
                        "T917 D7 §1.4.1c defect: the retry did not land on a fresh rebuilt instance — " +
                            "serving instances $serving",
                    )
                }
            }
            withClue("T917 D7 §1.4.1c: the retry is transparent — nothing may cancel the call") {
                h.transportShared.cancelledCalls.get() shouldBe 0
            }
            withClue("T917 D7 §1.4.1c: ONE D9 entry covers original + retry and is resolved") {
                readLedger()?.entries shouldBe emptyList()
            }
        } finally {
            auto.cancelJob()
        }
    }

    @Test
    fun `a second mid retry close fails the page honestly without looping`() = runBlocking<Unit> {
        createHarness(drainGraceMs = 0L)
        val h = checkNotNull(harness)
        h.installGraphicsShims()
        h.barrier.arm(CoexistenceBarrier.BarrierPoint.PROVIDER_START, "p0")
        val auto = launchAutoPage("p0")
        try {
            awaitProviderStart(auto, "p0")

            // Close #1 under the parked original call (grace 0), then release it so the
            // epoch retry starts and parks on the SECOND gate before close #2.
            h.manager.clearQueue()
            h.barrier.arm(CoexistenceBarrier.BarrierPoint.PROVIDER_START, "p0")
            h.barrier.release(CoexistenceBarrier.BarrierPoint.PROVIDER_START, "p0")
            // The arrivals log is append-only, so the FIRST call's arrival would
            // satisfy awaitArrival — await the SECOND PROVIDER_START arrival
            // (the rebuilt translator's park) event-driven instead. The second
            // arrival is logged after ensureTranslatorRebuiltForEpochRetry
            // published the fresh instance, so close #2 below snapshots IT.
            withTimeout(AWAIT_TIMEOUT_MS) {
                h.barrier.arrivals.first { list ->
                    list.count { (p, k) ->
                        p == CoexistenceBarrier.BarrierPoint.PROVIDER_START && k == "p0"
                    } >= 2
                }
            }

            // Close #2 mid-retry: the epoch moves AGAIN.
            h.manager.clearQueue()
            h.barrier.release(CoexistenceBarrier.BarrierPoint.PROVIDER_START, "p0")

            // The honest failure surfaces in TWO shapes: a typed non-Completed
            // outcome, or the boundary's rethrow (markPageFailed + throw — the
            // launchAutoPage wrapper completes the deferred exceptionally).
            val outcome = runCatching {
                withTimeout(AWAIT_TIMEOUT_MS) { auto.outcome.await() }
            }.getOrNull()
            withClue(
                "T917 D7 §1.4.1c defect: a SECOND epoch mismatch must fail the page honestly (typed " +
                    "failure) — never complete it and never loop",
            ) {
                if (outcome is ChunkCompletionOutcome.Completed) {
                    throw AssertionError(
                        "T917 D7 §1.4.1c defect: the twice-closed page completed instead of failing " +
                            "honestly — outcome was $outcome",
                    )
                }
            }
            withClue(
                "T917 D7 §1.4.1c: at most 2 retry-eligible attempts (original + one retry, no loop) — " +
                    "serving=${h.transportShared.servingOrder["p0"]}",
            ) {
                h.transportCallsFor("p0") shouldBe 2
            }
            withClue("T917 D7 §1.4.1c: the honest failure is not a cancellation") {
                h.transportShared.cancelledCalls.get() shouldBe 0
            }
            withClue("T917 D7 §1.4.1c: the typed failure still resolves its D9 entry (a completed call)") {
                readLedger()?.entries shouldBe emptyList()
            }
        } finally {
            auto.cancelJob()
        }
    }

    // ------------------------------------------------------------------
    // (d) native-safety guard: a parked NATIVE call blocks the close
    // ------------------------------------------------------------------

    @Test
    fun `close with a native call parked does not close the engines`() = runBlocking<Unit> {
        val store = createHarness(drainGraceMs = null)
        val h = checkNotNull(harness)
        h.installGraphicsShims()
        h.registerReaderStream(TranslationCoexistenceHarness.CHAPTER_ID, "p0")
        try {
            h.barrier.arm(CoexistenceBarrier.BarrierPoint.NATIVE_ACQUIRE, "p0")
            h.tapManual("p0")
            val manualJob = h.capturedManualJob("p0")
            h.barrier.awaitArrivalWithin(CoexistenceBarrier.BarrierPoint.NATIVE_ACQUIRE, "p0", AWAIT_TIMEOUT_MS)

            h.manager.clearQueue()

            // Negative probe: the native invocation holds the lane, so tryRunExclusive
            // must fail and NOTHING may close. This guard is green today (it pins the
            // idle-lane contract the drain must preserve).
            try {
                withTimeout(NEGATIVE_PROBE_MS) { h.fakeTransport.closedSignal.await() }
                throw AssertionError(
                    "T917 D7 §1.4.1d defect: closeEngines closed the engines while a native invocation " +
                        "held the lane — the idle-lane contract is broken",
                )
            } catch (_: TimeoutCancellationException) {
                // expected: close deferred, native lane protected
            }
            withClue("T917 D7 §1.4.1d: neither engine may be closed while the lane is occupied") {
                h.fakeTransport.closeCalls.get() shouldBe 0
                h.fakeRecognition.closeCalls.get() shouldBe 0
            }
            // Latent epoch probe: closeEngines always moves the epoch, even when the
            // close itself is deferred (no-op until the GREEN commit lands the accessor).
            currentEngineEpochOrNull()?.let { epoch ->
                withClue("T917 D7 §1.2: the engine epoch moves on every closeEngines, deferred or not") {
                    epoch shouldBe 1L
                }
            }

            h.barrier.release(CoexistenceBarrier.BarrierPoint.NATIVE_ACQUIRE, "p0")
            withTimeout(AWAIT_TIMEOUT_MS) { manualJob.join() }
        } finally {
            h.removeGraphicsShims()
        }
    }
}
