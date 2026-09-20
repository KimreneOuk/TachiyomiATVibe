package eu.kanade.translation.scheduling

import eu.kanade.translation.storage.ChapterTranslationStore
import eu.kanade.translation.pipeline.PageWriteOrigin
import eu.kanade.translation.orchestration.TranslationSession
import eu.kanade.translation.pipeline.batch.ChunkCompletionOutcome
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.translator.TranslatorComputeClass
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Ticket 03 deterministic coordinator tests.
 *
 * Most tests inject a [Dispatchers.Unconfined] coordination scope so the
 * coordinator coroutines run inline on the test thread; the lifecycle race
 * test deliberately uses [Dispatchers.Default]. There is no
 * [delay]/[Thread.sleep] polling — every gate point is a proper suspension
 * point, and every test calls [RollingAutoCoordinator.awaitTermination] so all
 * coordinator jobs/scope children are joined before the test exits.
 */
class RollingAutoCoordinatorTest {

    private val identity = AutoChapterIdentity(chapterId = 1L, sessionKey = "test-session")

    /**
     * Tracks the injected scope so each test cancels it in [tearDown]. The
     * coordinator does not own an injected scope, so without this the
     * SupervisorJob would outlive every test.
     */
    private var testScope: CoroutineScope? = null

    @AfterEach
    fun tearDown() {
        testScope?.cancel()
        testScope = null
    }

    private fun newStore(pages: List<Pair<String, PageTranslation>> = emptyList()): ChapterTranslationStore =
        ChapterTranslationStore(
            translationFile = null,
            fileCreator = null,
            initialPages = pages.associate { it.first to it.second },
        )

    /** A page that still needs the full pipeline (all stages PENDING). */
    private fun blank() = PageTranslation(sourceFileName = "")

    /** A durable, display-ready page (cleaned + translated + rendered). */
    private fun displayReady(pageKey: String) = PageTranslation(
        sourceFileName = pageKey,
        renderStatus = StageStatus.READY,
        translationStatus = StageStatus.READY,
        ocrStatus = StageStatus.READY,
        inpaintStatus = StageStatus.READY,
        cleanedImageName = "$pageKey.cleaned.png",
        inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION,
        blocks = mutableListOf(
            TranslationBlock(
                text = "源",
                translation = "source",
                width = 10f,
                height = 10f,
                x = 0f,
                y = 0f,
                symHeight = 1f,
                symWidth = 1f,
                angle = 0f,
            ),
        ),
    )

    private fun fakeSession(
        store: ChapterTranslationStore,
        chapterId: Long = 1L,
        sessionKey: String = "key",
    ): TranslationSession {
        val manga = mockk<tachiyomi.domain.manga.model.Manga>(relaxed = true)
        every { manga.id } returns chapterId
        val chapter = mockk<tachiyomi.domain.chapter.model.Chapter>(relaxed = true)
        every { chapter.id } returns chapterId
        val source = mockk<eu.kanade.tachiyomi.source.online.HttpSource>(relaxed = true)
        every { source.id } returns chapterId
        return TranslationSession(sessionKey, manga, chapter, source, store)
    }

    private fun newCoordinator(
        executor: TranslationExecutor,
        computeClass: TranslatorComputeClass,
        memoryGate: () -> Boolean = { true },
        dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
    ): RollingAutoCoordinator {
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        testScope = scope
        return RollingAutoCoordinator(
            executor = executor,
            computeClass = computeClass,
            memoryGate = memoryGate,
            injectedScope = scope,
        )
    }

    private fun resolver(prefix: String = "p"): (Int) -> RollingAutoCoordinator.PageWorkItem? = { idx ->
        RollingAutoCoordinator.PageWorkItem("$prefix$idx", null)
    }

    /**
     * REMOTE_IO: after page A is prepared and enters translation, page B's
     * native preparation starts concurrently. The maxConcurrent counter must
     * reach 2 during the overlap.
     */
    @Test
    @Timeout(60)
    fun `remote overlap - native B starts while translate A is in flight`() = runBlocking<Unit> {
        val executor = ControllableExecutor(autoComplete = false)
        val store = newStore(listOf("p0" to blank(), "p1" to blank()))
        val coordinator = newCoordinator(executor, TranslatorComputeClass.REMOTE_IO)
        val session = fakeSession(store)
        coordinator.updateWindow(identity, 0, 1, 2, session, resolver())

        // Page 0 prepares to completion, then hands off into the translate lane.
        executor.awaitAndCompletePrepare(0)
        // Page 0 translate starts (no compute gate for REMOTE_IO).
        executor.awaitTranslateStarted(0)
        // The native lane is now free for page 1, whose prepare starts while
        // translate(0) is still in flight.
        executor.awaitPrepareStarted(1)

        // Both translate(0) and prepare(1) are in flight -> overlap confirmed,
        // and the single-native / single-translate lane invariant holds (==2).
        executor.maxConcurrent.get() shouldBe 2

        executor.completeTranslate(0)
        executor.awaitAndCompletePrepare(1)
        executor.awaitTranslateStarted(1)
        executor.completeTranslate(1)

        coordinator.shutdown()
        coordinator.awaitTermination()
        executor.maxConcurrent.get() shouldBe 2
    }

    /**
     * LOCAL_COMPUTE: the shared compute gate serializes prepare and translate.
     * At no point do both run simultaneously.
     */
    /**
     * LOCAL_COMPUTE: the shared compute gate serializes prepare and translate.
     * At no point do both run simultaneously.
     *
     * Gate-release ordering note: the coordinator admits page 1's native
     * prepare as soon as page 0 hands off — the translate consumer is
     * asynchronous to the reconcile loop even on [Dispatchers.Unconfined]
     * (its channel-handoff resumption is queued to the runBlocking event
     * loop), so prepare(1) legally acquires the shared gate before the
     * consumer's translate(0) acquire is served. The gate still guarantees the
     * two never OVERLAP; it does not guarantee translate-lane priority. The
     * protocol below releases prepare(1) before waiting for translate(0);
     * completing translate(0) first would deadlock the fixture (prepare(1)
     * holds the permit while parked on a gate only this test can open, while
     * this test waits for translate(0), which is parked on that permit).
     */
    @Test
    @Timeout(60)
    fun `local compute serializes prepare and translate through a shared gate`() = runBlocking<Unit> {
        val executor = ControllableExecutor(autoComplete = false)
        val store = newStore(listOf("p0" to blank(), "p1" to blank()))
        val coordinator = newCoordinator(executor, TranslatorComputeClass.LOCAL_COMPUTE)
        val session = fakeSession(store)
        coordinator.updateWindow(identity, 0, 1, 2, session, resolver())

        // Page 0 prepares while holding the shared gate, then hands off to the
        // translate lane; the loop immediately admits prepare(1) behind the gate.
        executor.awaitAndCompletePrepare(0)
        executor.awaitAndCompletePrepare(1)
        // With the gate released by prepare(1), translate(0) starts.
        executor.awaitTranslateStarted(0)
        // prepare(1) and translate(0) never overlapped: max stays 1.
        executor.maxConcurrent.get() shouldBe 1

        executor.completeTranslate(0)
        executor.awaitTranslateStarted(1)
        executor.completeTranslate(1)

        coordinator.shutdown()
        coordinator.awaitTermination()
        executor.maxConcurrent.get() shouldBe 1
    }

    /**
     * Memory unavailable -> ahead prefetch pauses (Deferred Memory); the visible
     * page is display-ready so it needs no work. Memory recovers + reconcile()
     * -> the ahead page proceeds. No polling. (Foreground bypass of the gate is
     * covered by `foreground bypasses memory gate while ahead pages defer`.)
     */
    @Test
    fun `memory deferral publishes Deferred Memory and refills on reconcile`() = runBlocking<Unit> {
        val executor = ControllableExecutor(autoComplete = true)
        // p0 (visible) is already display-ready -> foreground is null, not desired.
        val store = newStore(listOf("p0" to displayReady("p0"), "p1" to blank()))
        var memoryAvailable = false
        val coordinator = newCoordinator(
            executor,
            TranslatorComputeClass.REMOTE_IO,
            memoryGate = { memoryAvailable },
        )
        val session = fakeSession(store)
        coordinator.updateWindow(identity, 0, 1, 2, session, resolver())

        // Memory unavailable: ahead page not admitted, snapshot truthfully paused.
        executor.prepareCount.get() shouldBe 0
        val deferred = coordinator.snapshot.value
        deferred shouldNotBe null
        deferred!!.foreground shouldBe null
        deferred.activeDeferral shouldBe AutoDeferralReason.Memory

        memoryAvailable = true
        coordinator.reconcile()

        executor.prepareCallsByPage["p1"] shouldBe 1
        executor.translateCallsByPage["p1"] shouldBe 1

        coordinator.shutdown()
        coordinator.awaitTermination()
    }

    /**
     * Prepare failure (null return) marks the slot Failed; the coordinator
     * proceeds to the next desired page without getting stuck or auto-looping.
     */
    @Test
    fun `prepare failure marks slot Failed and coordinator continues`() = runBlocking<Unit> {
        val executor = ControllableExecutor(autoComplete = true)
        executor.failPrepareFor.add("p0")
        val store = newStore(listOf("p0" to blank(), "p1" to blank()))
        val coordinator = newCoordinator(executor, TranslatorComputeClass.REMOTE_IO)
        val session = fakeSession(store)
        coordinator.updateWindow(identity, 0, 1, 2, session, resolver())

        // Page 0 failed; page 1 still proceeds and translates.
        executor.prepareCount.get() shouldBe 2
        (executor.prepareCallsByPage["p0"] ?: 0) shouldBe 1
        (executor.prepareCallsByPage["p1"] ?: 0) shouldBe 1
        (executor.translateCallsByPage["p1"] ?: 0) shouldBe 1
        (executor.translateCallsByPage["p0"] ?: 0) shouldBe 0

        coordinator.shutdown()
        coordinator.awaitTermination()
    }

    /**
     * A terminal PreparedPage (textless page or already-ready page) skips the
     * translate lane entirely and publishes Ready.
     */
    @Test
    fun `terminal prepared page is Ready without translate`() = runBlocking<Unit> {
        val executor = ControllableExecutor(autoComplete = true)
        executor.terminalPages.add("p0")
        val store = newStore(listOf("p0" to blank(), "p1" to blank()))
        val coordinator = newCoordinator(executor, TranslatorComputeClass.REMOTE_IO)
        val session = fakeSession(store)
        coordinator.updateWindow(identity, 0, 1, 2, session, resolver())

        executor.translateCount.get() shouldBe 1 // only page 1
        val snap = coordinator.snapshot.value!!
        snap.foreground shouldNotBe null
        snap.foreground!!.state shouldBe AutoSlotState.Ready

        coordinator.shutdown()
        coordinator.awaitTermination()
    }

    /**
     * Rapid anchors 4 -> 5 -> 6 converge on the newest desired set with no
     * duplicate execution and no reservation holes.
     */
    @Test
    fun `rapid anchor convergence without duplicate execution`() = runBlocking<Unit> {
        val executor = ControllableExecutor(autoComplete = true)
        val store = newStore(
            (0..9).map { "p$it" to blank() },
        )
        val coordinator = newCoordinator(executor, TranslatorComputeClass.REMOTE_IO)
        val session = fakeSession(store)
        coordinator.updateWindow(identity, 4, 2, 10, session, resolver())
        coordinator.updateWindow(identity, 5, 2, 10, session, resolver())
        coordinator.updateWindow(identity, 6, 2, 10, session, resolver())

        val snap = coordinator.snapshot.value!!
        snap.visiblePageIndex shouldBe 6
        snap.aheadSlots.map { it.pageIndex } shouldContainExactly listOf(7, 8)
        snap.foreground shouldNotBe null
        // Every page that was touched was touched exactly once; no duplicates.
        executor.prepareCallsByPage.values.all { it == 1 } shouldBe true
        executor.translateCallsByPage.values.all { it == 1 } shouldBe true

        coordinator.shutdown()
        coordinator.awaitTermination()
    }

    /**
     * A page queued at an old anchor that leaves the window before admission
     * never starts, while work the native lane already began is allowed to
     * finish and persist its result.
     */
    @Test
    fun `obsolete queued page never starts while started work finishes`() = runBlocking<Unit> {
        val executor = ControllableExecutor(autoComplete = false)
        val store = newStore((0..9).map { "p$it" to blank() })
        val coordinator = newCoordinator(executor, TranslatorComputeClass.REMOTE_IO)
        val session = fakeSession(store)
        // Anchor 4 -> page 4 enters the native lane and holds; pages 5,6 queued.
        coordinator.updateWindow(identity, 4, 2, 10, session, resolver())
        executor.awaitPrepareStarted(0) // prepare(p4) in flight, holding the lane

        // Anchor jumps to 6 before the native lane frees. Page 5 was only ever
        // queued; with the new window it is out of target and must never start.
        coordinator.updateWindow(identity, 6, 2, 10, session, resolver())
        executor.completePrepare(0) // let p4 (started obsolete) finish

        // Drain p4 through translate so it can persist, then advance the window.
        executor.awaitTranslateStarted(0)
        executor.completeTranslate(0)

        executor.prepareCallsByPage["p5"] shouldBe null
        executor.prepareCallsByPage["p4"] shouldBe 1
        executor.translateCallsByPage["p4"] shouldBe 1

        coordinator.shutdown()
        coordinator.awaitTermination()
    }

    /**
     * Snapshot ready-ahead count reflects durable display results: a page that
     * is display-ready in the store counts as Ready even though the coordinator
     * never processed it, and a coordinator-finished page counts too.
     */
    @Test
    fun `ready-ahead count reflects durable display results`() = runBlocking<Unit> {
        val executor = ControllableExecutor(autoComplete = true)
        // p5 is already display-ready in the store; p6, p7 need work.
        val store = newStore(
            listOf("p4" to displayReady("p4"), "p5" to displayReady("p5")) +
                (6..7).map { "p$it" to blank() },
        )
        val coordinator = newCoordinator(executor, TranslatorComputeClass.REMOTE_IO)
        val session = fakeSession(store)
        coordinator.updateWindow(identity, 4, 3, 8, session, resolver())

        val snap = coordinator.snapshot.value!!
        // Foreground (p4) is display-ready -> no auto work needed.
        snap.foreground shouldBe null
        // All three ahead pages (p5 ready in store, p6/p7 finished by executor).
        snap.readyAheadCount shouldBe 3
        snap.availableAheadTarget shouldBe 3
        snap.status shouldBe AutoActivityStatus.FullyReady

        coordinator.shutdown()
        coordinator.awaitTermination()
    }

    /**
     * foreground == null means the visible page needs no auto work (display-
     * ready), not merely that no foreground request was constructed.
     */
    @Test
    fun `foreground is null when visible page is display-ready`() = runBlocking<Unit> {
        val executor = ControllableExecutor(autoComplete = true)
        val store = newStore(listOf("p3" to displayReady("p3"), "p4" to blank()))
        val coordinator = newCoordinator(executor, TranslatorComputeClass.REMOTE_IO)
        val session = fakeSession(store)
        coordinator.updateWindow(identity, 3, 1, 5, session, resolver())

        val snap = coordinator.snapshot.value!!
        snap.foreground shouldBe null
        snap.aheadSlots.single().pageIndex shouldBe 4

        coordinator.shutdown()
        coordinator.awaitTermination()
    }

    /**
     * A page whose stream is not yet available defers with SourceUnavailable,
     * later pages continue, and the slot recovers when the stream appears and
     * reconcile() is signalled.
     */
    @Test
    fun `missing stream defers with SourceUnavailable and recovers on reconcile`() = runBlocking<Unit> {
        val executor = ControllableExecutor(autoComplete = true)
        val store = newStore((0..2).map { "p$it" to blank() })
        val coordinator = newCoordinator(executor, TranslatorComputeClass.REMOTE_IO)
        val session = fakeSession(store)
        var streamForPage1 = false
        val resolver: (Int) -> RollingAutoCoordinator.PageWorkItem? = { idx ->
            if (idx == 1 && !streamForPage1) null else RollingAutoCoordinator.PageWorkItem("p$idx", null)
        }
        coordinator.updateWindow(identity, 0, 2, 3, session, resolver)

        executor.prepareCallsByPage["p1"] shouldBe null
        val slot1 = coordinator.snapshot.value!!.aheadSlots.single { it.pageIndex == 1 }
        slot1.state shouldBe AutoSlotState.Deferred(AutoDeferralReason.SourceUnavailable)

        streamForPage1 = true
        coordinator.reconcile()

        executor.prepareCallsByPage["p1"] shouldBe 1
        executor.translateCallsByPage["p1"] shouldBe 1

        coordinator.shutdown()
        coordinator.awaitTermination()
    }

    /**
     * Cancellation stops admission promptly: the coordination loop and its
     * translate consumer terminate, and no further native work is admitted.
     */
    @Test
    fun `cancel stops admission and terminates the coordinator`() = runBlocking<Unit> {
        val executor = ControllableExecutor(autoComplete = false)
        val store = newStore((0..4).map { "p$it" to blank() })
        val coordinator = newCoordinator(executor, TranslatorComputeClass.REMOTE_IO)
        val session = fakeSession(store)
        coordinator.updateWindow(identity, 0, 2, 5, session, resolver())
        executor.awaitPrepareStarted(0) // prepare(p0) holding the native lane

        coordinator.cancel()
        coordinator.awaitTermination()

        // Only p0 ever entered the native lane; nothing else was admitted.
        executor.prepareCount.get() shouldBe 1
        // Snapshot is retained after cancel (last published value), per contract.
        coordinator.snapshot.value shouldNotBe null
        coordinator.snapshot.value!!.foreground!!.state shouldBe AutoSlotState.Queued

        // Re-arming on the same identity resumes coordination cleanly.
        coordinator.updateWindow(identity, 0, 2, 5, session, resolver())
        executor.awaitPrepareStarted(1)

        coordinator.shutdown()
        coordinator.awaitTermination()
    }

    /** shutdown clears the snapshot and tears down all lane work. */
    @Test
    @Timeout(60)
    fun `shutdown clears snapshot`() = runBlocking<Unit> {
        val executor = ControllableExecutor(autoComplete = true)
        val store = newStore(listOf("p0" to blank(), "p1" to blank()))
        val coordinator = newCoordinator(executor, TranslatorComputeClass.REMOTE_IO)
        val session = fakeSession(store)
        coordinator.updateWindow(identity, 0, 1, 2, session, resolver())

        coordinator.shutdown()
        coordinator.awaitTermination()
        coordinator.snapshot.value shouldBe null
    }

    /**
     * translatePreparedPage == null means stale/race (re-prepare, never Ready).
     * After two stale handoffs the third succeeds and the slot reaches Ready,
     * with the re-prepare counter reset. This is the contract documented on
     * [TranslationExecutor.translatePreparedPage].
     */
    @Test
    fun `stale translate handoff re-prepares then succeeds`() = runBlocking<Unit> {
        val executor = ControllableExecutor(autoComplete = true)
        executor.staleTranslateRemaining["p0"] = AtomicInteger(2)
        val store = newStore(listOf("p0" to blank(), "p1" to blank()))
        val coordinator = newCoordinator(executor, TranslatorComputeClass.REMOTE_IO)
        val session = fakeSession(store)
        coordinator.updateWindow(identity, 0, 1, 2, session, resolver())

        // 1 initial prepare + 2 re-prepares = 3 prepares; 3 translates (2 stale + 1 ok).
        (executor.prepareCallsByPage["p0"] ?: 0) shouldBe 3
        (executor.translateCallsByPage["p0"] ?: 0) shouldBe 3
        val snap = coordinator.snapshot.value!!
        snap.foreground shouldNotBe null
        snap.foreground!!.state shouldBe AutoSlotState.Ready

        coordinator.shutdown()
        coordinator.awaitTermination()
    }

    @Test
    fun `typed pause is not ready and retries on an eligible reconcile`() = runBlocking<Unit> {
        val executor = ControllableExecutor(autoComplete = true)
        executor.pausedTranslateRemaining["p0"] = AtomicInteger(1)
        val store = newStore(listOf("p0" to blank(), "p1" to blank()))
        val coordinator = newCoordinator(executor, TranslatorComputeClass.REMOTE_IO)
        val session = fakeSession(store)
        coordinator.updateWindow(identity, 0, 1, 2, session, resolver())

        val paused = coordinator.snapshot.value!!.foreground
        paused shouldNotBe null
        paused!!.state shouldBe AutoSlotState.Deferred(AutoDeferralReason.Network)
        (executor.translateCallsByPage["p0"] ?: 0) shouldBe 1

        // The completion poke does not reset the finite request budget. An
        // external reconcile re-admits the page and the second attempt can
        // complete normally.
        coordinator.reconcile()
        (executor.translateCallsByPage["p0"] ?: 0) shouldBe 2
        coordinator.snapshot.value!!.foreground!!.state shouldBe AutoSlotState.Ready

        coordinator.shutdown()
        coordinator.awaitTermination()
    }

    /**
     * A persistently stale handoff (always false) must not tight-loop the native
     * lane forever: past [RollingAutoCoordinator.MAX_REPREPARE_ATTEMPTS] the slot
     * flips to Failed(retryable) and the coordinator moves on to other pages.
     */
    @Test
    fun `persistently stale translate is bounded and flips to Failed`() = runBlocking<Unit> {
        val executor = ControllableExecutor(autoComplete = true)
        executor.staleTranslateRemaining["p0"] = AtomicInteger(Int.MAX_VALUE)
        val store = newStore(listOf("p0" to blank(), "p1" to blank()))
        val coordinator = newCoordinator(executor, TranslatorComputeClass.REMOTE_IO)
        val session = fakeSession(store)
        coordinator.updateWindow(identity, 0, 1, 2, session, resolver())

        // Bounded: 1 initial prepare + 3 re-prepares (attempts 1,2,3 stay Queued;
        // attempt 4 exceeds the cap and marks Failed). p1 then proceeds normally.
        (executor.prepareCallsByPage["p0"] ?: 0) shouldBe 4
        (executor.translateCallsByPage["p0"] ?: 0) shouldBe 4
        (executor.translateCallsByPage["p1"] ?: 0) shouldBe 1
        val snap = coordinator.snapshot.value!!
        snap.foreground shouldNotBe null
        snap.foreground!!.state shouldBe AutoSlotState.Failed(retryable = true)
        // p1 (the ahead page) reached Ready.
        snap.aheadSlots.single { it.pageIndex == 1 }.state shouldBe AutoSlotState.Ready

        coordinator.shutdown()
        coordinator.awaitTermination()
    }

    /**
     * A genuine translate failure (throw) marks the slot Failed(retryable) and
     * does not loop: the executor is called once for that page's translate.
     */
    @Test
    fun `translate throw marks slot Failed without retry`() = runBlocking<Unit> {
        val executor = ControllableExecutor(autoComplete = true)
        executor.failTranslateFor.add("p0")
        val store = newStore(listOf("p0" to blank(), "p1" to blank()))
        val coordinator = newCoordinator(executor, TranslatorComputeClass.REMOTE_IO)
        val session = fakeSession(store)
        coordinator.updateWindow(identity, 0, 1, 2, session, resolver())

        (executor.translateCallsByPage["p0"] ?: 0) shouldBe 1
        (executor.prepareCallsByPage["p0"] ?: 0) shouldBe 1
        val snap = coordinator.snapshot.value!!
        snap.foreground shouldNotBe null
        snap.foreground!!.state shouldBe AutoSlotState.Failed(retryable = true)
        // p1 still succeeds.
        (executor.translateCallsByPage["p1"] ?: 0) shouldBe 1

        coordinator.shutdown()
        coordinator.awaitTermination()
    }

    /**
     * Manual-job arbitration (as wired by [TranslationScheduler.updateAutoWindow]):
     * while the resolver hides a page (active manual job), the coordinator defers
     * it but keeps processing the rest of the window. When the page is revealed
     * and reconcile() is signalled (manual job completed), it is admitted.
     *
     * Distinct from the missing-stream test: it asserts the window makes
     * progress around the arbitrated page rather than stalling on it.
     */
    @Test
    fun `manual arbitration - window proceeds around a hidden page then admits it`() = runBlocking<Unit> {
        val executor = ControllableExecutor(autoComplete = true)
        val store = newStore((0..3).map { "p$it" to blank() })
        val coordinator = newCoordinator(executor, TranslatorComputeClass.REMOTE_IO)
        val session = fakeSession(store)
        var manualHoldsPage1 = true
        val resolver: (Int) -> RollingAutoCoordinator.PageWorkItem? = { idx ->
            if (idx == 1 && manualHoldsPage1) null else RollingAutoCoordinator.PageWorkItem("p$idx", null)
        }
        coordinator.updateWindow(identity, 0, 3, 4, session, resolver)

        // Page 1 is hidden (manual); pages 0, 2, 3 must still proceed.
        (executor.prepareCallsByPage["p1"] ?: 0) shouldBe 0
        (executor.prepareCallsByPage["p0"] ?: 0) shouldBe 1
        (executor.prepareCallsByPage["p2"] ?: 0) shouldBe 1
        (executor.prepareCallsByPage["p3"] ?: 0) shouldBe 1
        val slot1Before = coordinator.snapshot.value!!.aheadSlots.single { it.pageIndex == 1 }
        slot1Before.state shouldBe AutoSlotState.Deferred(AutoDeferralReason.SourceUnavailable)

        // Manual job completes: page 1 is revealed and reconcile re-admits it.
        manualHoldsPage1 = false
        coordinator.reconcile()
        (executor.prepareCallsByPage["p1"] ?: 0) shouldBe 1
        (executor.translateCallsByPage["p1"] ?: 0) shouldBe 1

        coordinator.shutdown()
        coordinator.awaitTermination()
    }

    /**
     * Memory pressure pauses background prefetch only: the visible foreground
     * page bypasses the headroom gate and may start, while every ahead page
     * reports Deferred(Memory). Hard per-page decode safety remains in the
     * pipeline (out of scope for the coordinator).
     */
    @Test
    fun `foreground bypasses memory gate while ahead pages defer`() = runBlocking<Unit> {
        val executor = ControllableExecutor(autoComplete = true)
        val store = newStore(listOf("p0" to blank(), "p1" to blank(), "p2" to blank()))
        val coordinator = newCoordinator(
            executor,
            TranslatorComputeClass.REMOTE_IO,
            memoryGate = { false },
        )
        val session = fakeSession(store)
        coordinator.updateWindow(identity, 0, 2, 3, session, resolver())

        // Foreground (p0) prepared + translated; ahead pages p1, p2 deferred.
        (executor.prepareCallsByPage["p0"] ?: 0) shouldBe 1
        (executor.translateCallsByPage["p0"] ?: 0) shouldBe 1
        (executor.prepareCallsByPage["p1"] ?: 0) shouldBe 0
        (executor.prepareCallsByPage["p2"] ?: 0) shouldBe 0
        val snap = coordinator.snapshot.value!!
        snap.foreground shouldNotBe null
        snap.foreground!!.state shouldBe AutoSlotState.Ready
        snap.aheadSlots.size shouldBe 2
        snap.aheadSlots.all { it.state == AutoSlotState.Deferred(AutoDeferralReason.Memory) } shouldBe true
        snap.activeDeferral shouldBe AutoDeferralReason.Memory

        coordinator.shutdown()
        coordinator.awaitTermination()
    }

    /**
     * An identity switch (chapter change) cancels the old chapter's loop, drops
     * its transient/reprepare state, and binds the new window — one active
     * coordination loop, no duplicate work for the new chapter.
     */
    @Test
    fun `identity switch resets transient state and binds the new chapter window`() = runBlocking<Unit> {
        val executor = ControllableExecutor(autoComplete = true)
        val store = newStore((0..9).map { "p$it" to blank() })
        val coordinator = newCoordinator(executor, TranslatorComputeClass.REMOTE_IO)
        val session = fakeSession(store)
        val id1 = AutoChapterIdentity(chapterId = 1L, sessionKey = "s1")
        val id2 = AutoChapterIdentity(chapterId = 2L, sessionKey = "s2")

        // Chapter 1: p0 accumulates stale reprepare attempts up to the cap.
        executor.staleTranslateRemaining["p0"] = AtomicInteger(Int.MAX_VALUE)
        coordinator.updateWindow(id1, 0, 1, 10, session, resolver())
        (executor.prepareCallsByPage["p0"] ?: 0) shouldBe 4 // capped → Failed

        // Switch to chapter 2 (visible 5). Transient state from chapter 1 —
        // including p0's stale-reprepare counter and Failed slot — is reset.
        coordinator.updateWindow(id2, 5, 1, 10, session, resolver())

        val snap = coordinator.snapshot.value!!
        snap.identity shouldBe id2
        snap.visiblePageIndex shouldBe 5
        snap.aheadSlots.single().pageIndex shouldBe 6
        // New chapter's pages processed exactly once.
        (executor.prepareCallsByPage["p5"] ?: 0) shouldBe 1
        (executor.prepareCallsByPage["p6"] ?: 0) shouldBe 1

        coordinator.shutdown()
        coordinator.awaitTermination()
    }

    /**
     * cancel() clears stale reprepare bookkeeping (and the Failed slot it
     * produced) so a re-arm on the same identity re-admits the page with a
     * fresh retry budget instead of staying stuck.
     */
    @Test
    fun `cancel clears stale reprepare bookkeeping so re-arm re-attempts`() = runBlocking<Unit> {
        val executor = ControllableExecutor(autoComplete = true)
        executor.staleTranslateRemaining["p0"] = AtomicInteger(Int.MAX_VALUE)
        val store = newStore(listOf("p0" to blank()))
        val coordinator = newCoordinator(executor, TranslatorComputeClass.REMOTE_IO)
        val session = fakeSession(store)
        // visible 0, target 0 -> only foreground p0 in the window.
        coordinator.updateWindow(identity, 0, 0, 1, session, resolver())
        (executor.prepareCallsByPage["p0"] ?: 0) shouldBe 4 // capped → Failed

        coordinator.cancel()
        coordinator.awaitTermination()

        // Re-arm same identity: reprepareAttempts + Failed slot cleared, so p0
        // is admissible again and re-runs the full stale budget.
        coordinator.updateWindow(identity, 0, 0, 1, session, resolver())
        (executor.prepareCallsByPage["p0"] ?: 0) shouldBe 8

        coordinator.shutdown()
        coordinator.awaitTermination()
    }

    /**
     * Immediate same-window re-arm must join the cancelled native owner before
     * admitting the replacement. This also proves awaitTermination has a real
     * cancelled job to join rather than an already-cleared field.
     */
    @Test
    fun `cancel immediate rearm waits for cancelled owner`() = runBlocking<Unit> {
        val executor = ControllableExecutor(autoComplete = false, ignoreCancellation = true)
        val store = newStore(listOf("p0" to blank()))
        val coordinator = newCoordinator(executor, TranslatorComputeClass.REMOTE_IO)
        val session = fakeSession(store)

        coordinator.updateWindow(identity, 0, 0, 1, session, resolver())
        executor.awaitPrepareStarted(0)

        coordinator.cancel()
        coordinator.updateWindow(identity, 0, 0, 1, session, resolver())
        executor.prepareCallsByPage["p0"] shouldBe 1

        executor.completePrepare(0)
        withTimeout(5_000) { executor.awaitPrepareStarted(1) }
        executor.completePrepare(1)
        withTimeout(5_000) { executor.awaitTranslateStarted(0) }

        executor.completeTranslate(0)
        coordinator.shutdown()
        coordinator.awaitTermination()
    }

    /**
     * A native call that ignores coroutine cancellation may return after a
     * chapter switch. The old prepared result must not be translated through
     * the new session, and its late stage callback must not rewrite the new
     * snapshot. The replacement loop waits for the cancelled owner first.
     */
    @Test
    fun `identity switch fences old session handoff and late callbacks`() = runBlocking<Unit> {
        val executor = ControllableExecutor(autoComplete = false, ignoreCancellation = true)
        val oldStore = newStore(listOf("old0" to blank()))
        val newStore = newStore(listOf("new0" to blank()))
        val coordinator = newCoordinator(executor, TranslatorComputeClass.REMOTE_IO)
        val oldIdentity = AutoChapterIdentity(chapterId = 11L, sessionKey = "old")
        val newIdentity = AutoChapterIdentity(chapterId = 22L, sessionKey = "new")
        val oldSession = fakeSession(oldStore, chapterId = 11L, sessionKey = "old-session")
        val newSession = fakeSession(newStore, chapterId = 22L, sessionKey = "new-session")

        coordinator.updateWindow(oldIdentity, 0, 0, 1, oldSession, resolver("old"))
        executor.awaitPrepareStarted(0)

        coordinator.updateWindow(newIdentity, 0, 0, 1, newSession, resolver("new"))
        // The replacement is a join gate; it cannot admit new work while the
        // old non-cooperative native call still owns the coordinator.
        executor.prepareCallsByPage["new0"] shouldBe null

        executor.completePrepare(0)
        withTimeout(5_000) { executor.awaitPrepareStarted(1) }
        executor.completePrepare(1)
        withTimeout(5_000) { executor.awaitTranslateStarted(0) }

        executor.translateCallsByPage["old0"] shouldBe null
        executor.translateChapterIdsByPage["new0"] shouldBe 22L

        // Callback retained by the obsolete old prepare must be ignored.
        executor.emitPrepareStage(0, TranslationStageEvent.READING)
        val snapshot = coordinator.snapshot.value!!
        snapshot.identity shouldBe newIdentity
        snapshot.foreground!!.state shouldBe AutoSlotState.Rendering

        executor.completeTranslate(0)
        coordinator.shutdown()
        coordinator.awaitTermination()
    }

    /**
     * Normal dispatcher callers may update the same window concurrently. The
     * lifecycle lock must still leave one owner and one execution per page;
     * the barrier and executor gates make the scheduling race deterministic
     * without sleeps or polling.
     */
    @Test
    fun `normal dispatcher concurrent updates keep one coordination owner`() = runBlocking<Unit> {
        val executor = ControllableExecutor(autoComplete = true)
        val store = newStore((0..2).map { "p$it" to blank() })
        val coordinator = newCoordinator(
            executor,
            TranslatorComputeClass.REMOTE_IO,
            dispatcher = Dispatchers.Default,
        )
        val session = fakeSession(store)
        val start = CompletableDeferred<Unit>()
        val callers = (0 until 8).map {
            async(Dispatchers.Default) {
                start.await()
                coordinator.updateWindow(identity, 0, 2, 3, session, resolver())
            }
        }
        start.complete(Unit)
        callers.awaitAll()

        withTimeout(5_000) {
            executor.awaitTranslateStarted(0)
            executor.awaitTranslateStarted(1)
            executor.awaitTranslateStarted(2)
        }
        (executor.prepareCallsByPage["p0"] ?: 0) shouldBe 1
        (executor.prepareCallsByPage["p1"] ?: 0) shouldBe 1
        (executor.prepareCallsByPage["p2"] ?: 0) shouldBe 1

        coordinator.shutdown()
        coordinator.awaitTermination()
    }

    @Test
    fun `older same-spec snapshot build cannot overwrite newer stage`() = runBlocking<Unit> {
        val executor = ControllableExecutor(autoComplete = false)
        // The visible slot's transient state is populated by prepare's stage
        // events, so snapshot builds resolve it from slotStates and never
        // consult pageResolver. The gate below is only reachable through an
        // ahead page, whose slot has no transient state and therefore must be
        // resolved through the resolver-backed store lookup.
        val store = newStore(listOf("p0" to blank(), "p1" to blank()))
        val coordinator = newCoordinator(
            executor,
            TranslatorComputeClass.REMOTE_IO,
            dispatcher = Dispatchers.Default,
        )
        val session = fakeSession(store)
        val buildStarted = CompletableDeferred<Unit>()
        val releaseBuild = CompletableDeferred<Unit>()
        val blockNextBuild = AtomicBoolean(false)
        val gatedResolver: (Int) -> RollingAutoCoordinator.PageWorkItem? = { idx ->
            if (blockNextBuild.compareAndSet(true, false)) {
                buildStarted.complete(Unit)
                runBlocking { releaseBuild.await() }
            }
            RollingAutoCoordinator.PageWorkItem("p$idx", null)
        }

        coordinator.updateWindow(identity, 0, 1, 2, session, gatedResolver)
        executor.awaitPrepareStarted(0)
        blockNextBuild.set(true)

        val olderBuild = async(Dispatchers.Default) {
            executor.emitPrepareStage(0, TranslationStageEvent.READING)
        }
        buildStarted.await()
        executor.emitPrepareStage(0, TranslationStageEvent.RENDERING)
        releaseBuild.complete(Unit)
        olderBuild.await()

        coordinator.snapshot.value!!.foreground!!.state shouldBe AutoSlotState.Rendering
        coordinator.shutdown()
        coordinator.awaitTermination()
    }

    @Test
    fun `scheduler replacement waits for a non cooperative retiring coordinator`() = runBlocking<Unit> {
        val executor = ControllableExecutor(autoComplete = false, ignoreCancellation = true)
        val oldStore = newStore(listOf("old0" to blank()))
        val newStore = newStore(listOf("new0" to blank()))
        val stores = mapOf(11L to oldStore, 22L to newStore)
        val scheduler = TranslationScheduler(
            executor = executor,
            storeResolver = TranslationStoreResolver { chapterId -> stores[chapterId] },
            immediateStoreResolver = { chapterId -> stores[chapterId] },
        )
        val oldIdentity = AutoChapterIdentity(11L, "old")
        val newIdentity = AutoChapterIdentity(22L, "new")
        val oldSession = fakeSession(oldStore, chapterId = 11L, sessionKey = "old-session")
        val newSession = fakeSession(newStore, chapterId = 22L, sessionKey = "new-session")

        try {
            scheduler.updateAutoWindow(oldIdentity, 0, 0, 1, oldSession, resolver("old"), TranslatorComputeClass.REMOTE_IO)
            executor.awaitPrepareStarted(0)

            scheduler.shutdownAutoCoordinator()
            scheduler.updateAutoWindow(newIdentity, 0, 0, 1, newSession, resolver("new"), TranslatorComputeClass.REMOTE_IO)
            executor.prepareCallsByPage["new0"] shouldBe null

            executor.completePrepare(0)
            withTimeout(5_000) { executor.awaitPrepareStarted(1) }
            executor.completePrepare(1)
            withTimeout(5_000) { executor.awaitTranslateStarted(0) }
            executor.completeTranslate(0)
        } finally {
            scheduler.close()
        }
    }

    @Test
    @Timeout(60)
    fun `chapter mismatched cancellation leaves the active scheduler owner running`() = runBlocking<Unit> {
        val executor = ControllableExecutor(autoComplete = false)
        val store = newStore(listOf("p0" to blank(), "p1" to blank()))
        val scheduler = TranslationScheduler(
            executor = executor,
            storeResolver = TranslationStoreResolver { store },
            immediateStoreResolver = { store },
        )
        val session = fakeSession(store, chapterId = 31L, sessionKey = "active")
        val identity = AutoChapterIdentity(31L, "active")

        try {
            scheduler.updateAutoWindow(identity, 0, 1, 2, session, resolver(), TranslatorComputeClass.REMOTE_IO)
            executor.awaitPrepareStarted(0)

            // Chapter-mismatched cancellation must be a no-op for the active owner.
            scheduler.cancelAutoTranslations(99L) shouldBe false
            scheduler.cancelPageTranslations(99L)
            executor.completePrepare(0)
            withTimeout(5_000) { executor.awaitTranslateStarted(0) }

            // Chapter-matched cancellation stops the active owner. Whether p1's
            // native prepare had already been admitted before the cancel is a
            // legal pre-cancel race (the loop is event-driven on
            // Dispatchers.Default); the cancelled owner can never drive p1
            // through translate though: autoComplete=false parks prepare(p1) on
            // a gate this test never opens, so cancellation unwinds it there.
            scheduler.cancelAutoTranslations(31L) shouldBe true
            executor.completeTranslate(0)
            executor.translateCallsByPage["p1"] shouldBe null
        } finally {
            scheduler.close()
        }
    }

    @Test
    @Timeout(60)
    fun `same-chapter replacement is suppressed while cancellation is signalling`() = runBlocking<Unit> {
        val executor = ControllableExecutor(autoComplete = false)
        val oldStore = newStore(listOf("old0" to blank()))
        val newStore = newStore(listOf("new0" to blank()))
        val stores = mapOf(71L to oldStore, 72L to newStore)
        val scheduler = TranslationScheduler(
            executor = executor,
            storeResolver = TranslationStoreResolver { chapterId -> stores[chapterId] },
            immediateStoreResolver = { chapterId -> stores[chapterId] },
        )
        val oldIdentity = AutoChapterIdentity(71L, "cancel-old")
        val newIdentity = AutoChapterIdentity(71L, "cancel-new")
        val oldSession = fakeSession(oldStore, chapterId = 71L, sessionKey = "cancel-old-session")
        val newSession = fakeSession(newStore, chapterId = 71L, sessionKey = "cancel-new-session")
        val cancelBuildEntered = CompletableDeferred<Unit>()
        val releaseCancelBuild = CompletableDeferred<Unit>()
        val blockNextResolver = AtomicBoolean(false)
        val oldResolver: (Int) -> RollingAutoCoordinator.PageWorkItem? = { idx ->
            if (blockNextResolver.compareAndSet(true, false)) {
                cancelBuildEntered.complete(Unit)
                runBlocking { releaseCancelBuild.await() }
            }
            RollingAutoCoordinator.PageWorkItem("old$idx", null)
        }

        try {
            scheduler.updateAutoWindow(
                oldIdentity,
                visiblePageIndex = 0,
                configuredAheadTarget = 0,
                pageCount = 1,
                session = oldSession,
                pageResolver = oldResolver,
                computeClass = TranslatorComputeClass.REMOTE_IO,
            )
            executor.awaitPrepareStarted(0)
            blockNextResolver.set(true)

            val cancellation = async(Dispatchers.Default) {
                scheduler.cancelAutoTranslations(71L)
            }
            cancelBuildEntered.await()

            // The chapter token is recorded before cancel() enters the resolver;
            // a replacement with the same chapter cannot install/admit a new loop.
            scheduler.updateAutoWindow(
                newIdentity,
                visiblePageIndex = 0,
                configuredAheadTarget = 0,
                pageCount = 1,
                session = newSession,
                pageResolver = resolver("new"),
                computeClass = TranslatorComputeClass.REMOTE_IO,
            )
            executor.prepareCallsByPage["new0"] shouldBe null

            releaseCancelBuild.complete(Unit)
            cancellation.await()
            executor.completePrepare(0)

            // Cancellation must leave the pointer detached, but a subsequent
            // explicit update must be able to install/re-arm the replacement
            // owner after the chapter epoch is released.
            scheduler.updateAutoWindow(
                newIdentity,
                visiblePageIndex = 0,
                configuredAheadTarget = 0,
                pageCount = 1,
                session = newSession,
                pageResolver = resolver("new"),
                computeClass = TranslatorComputeClass.REMOTE_IO,
            )
            withTimeout(5_000) {
                scheduler.autoSnapshot.first { it?.identity == newIdentity }
            }
        } finally {
            scheduler.close()
        }
    }

    @Test
    @Timeout(60)
    fun `global cancellation epoch rejects a concurrent update and permits post-cancel rearm`() = runBlocking<Unit> {
        val executor = ControllableExecutor(autoComplete = true)
        val oldStore = newStore(listOf("old0" to blank()))
        val newStore = newStore(listOf("new0" to blank()))
        val stores = mapOf(81L to oldStore, 82L to newStore)
        val scheduler = TranslationScheduler(
            executor = executor,
            storeResolver = TranslationStoreResolver { chapterId -> stores[chapterId] },
            immediateStoreResolver = { chapterId -> stores[chapterId] },
        )
        val oldIdentity = AutoChapterIdentity(81L, "global-old")
        val newIdentity = AutoChapterIdentity(82L, "global-new")
        val oldSession = fakeSession(oldStore, chapterId = 81L, sessionKey = "global-old-session")
        val newSession = fakeSession(newStore, chapterId = 82L, sessionKey = "global-new-session")

        try {
            scheduler.updateAutoWindow(
                oldIdentity,
                0,
                0,
                1,
                oldSession,
                resolver("old"),
                TranslatorComputeClass.REMOTE_IO,
            )
            withTimeout(5_000) { scheduler.autoSnapshot.first { it?.identity == oldIdentity } }

            // The two production calls intentionally overlap on the normal
            // dispatcher. If update observes the global epoch it is suppressed;
            // if it wins before cancellation begins, cancellation still fences
            // the old pointer. Either way no obsolete owner may survive.
            val cancellation = async(Dispatchers.Default) {
                scheduler.cancelAutoTranslations()
            }
            val concurrentUpdate = async(Dispatchers.Default) {
                scheduler.updateAutoWindow(
                    newIdentity,
                    0,
                    0,
                    1,
                    newSession,
                    resolver("new"),
                    TranslatorComputeClass.REMOTE_IO,
                )
            }
            awaitAll(cancellation, concurrentUpdate)
            // Both interleavings are legal:
            // - Update won: it installed the new identity (the overlapping
            //   global cancel may then have cancelled that new owner — still
            //   fenced from the old one).
            // - Cancel won: the global epoch rejected the concurrent update;
            //   the cancelled old owner keeps its RETAINED snapshot (cancel(),
            //   unlike shutdown(), never nulls it) until an explicit re-arm.
            //   The old owner can no longer publish or admit work.
            val observed = scheduler.autoSnapshot.value
            (observed == null || observed.identity == oldIdentity || observed.identity == newIdentity) shouldBe true

            // A post-cancel re-arm must always converge on the new identity.
            scheduler.updateAutoWindow(
                newIdentity,
                0,
                0,
                1,
                newSession,
                resolver("new"),
                TranslatorComputeClass.REMOTE_IO,
            )
            withTimeout(5_000) { scheduler.autoSnapshot.first { it?.identity == newIdentity } }
        } finally {
            scheduler.close()
        }
    }

    @Test
    fun `reentrant resolver cannot publish an obsolete snapshot`() = runBlocking<Unit> {
        val executor = ControllableExecutor(autoComplete = true)
        val oldStore = newStore(listOf("old0" to displayReady("old0")))
        val newStore = newStore(listOf("new0" to displayReady("new0")))
        val coordinator = newCoordinator(executor, TranslatorComputeClass.REMOTE_IO)
        val oldIdentity = AutoChapterIdentity(41L, "old")
        val newIdentity = AutoChapterIdentity(42L, "new")
        val oldSession = fakeSession(oldStore, chapterId = 41L, sessionKey = "old-session")
        val newSession = fakeSession(newStore, chapterId = 42L, sessionKey = "new-session")
        var resolverCalls = 0
        var reentered = false
        val newResolver: (Int) -> RollingAutoCoordinator.PageWorkItem? = { idx ->
            RollingAutoCoordinator.PageWorkItem("new$idx", null)
        }
        val oldResolver: (Int) -> RollingAutoCoordinator.PageWorkItem? = { idx ->
            resolverCalls++
            if (resolverCalls == 2 && !reentered) {
                reentered = true
                coordinator.updateWindow(newIdentity, 0, 0, 1, newSession, newResolver)
            }
            RollingAutoCoordinator.PageWorkItem("old$idx", null)
        }

        coordinator.updateWindow(oldIdentity, 0, 0, 1, oldSession, oldResolver)

        coordinator.snapshot.value!!.identity shouldBe newIdentity
        coordinator.shutdown()
        coordinator.awaitTermination()
    }

    @Test
    @Timeout(60)
    fun `stable scheduler snapshot switches pointer and rejects same-identity replay`() = runBlocking<Unit> {
        val executor = ControllableExecutor(autoComplete = true)
        val oldStore = newStore(listOf("old0" to blank(), "old1" to blank(), "old2" to blank()))
        val newStore = newStore(listOf("new0" to blank()))
        val stores = mapOf(51L to oldStore, 52L to newStore)
        val scheduler = TranslationScheduler(
            executor = executor,
            storeResolver = TranslationStoreResolver { chapterId -> stores[chapterId] },
            immediateStoreResolver = { chapterId -> stores[chapterId] },
        )
        val stable = scheduler.autoSnapshot
        val oldIdentity = AutoChapterIdentity(51L, "stable-old")
        val newIdentity = AutoChapterIdentity(52L, "stable-new")
        val oldSession = fakeSession(oldStore, chapterId = 51L, sessionKey = "stable-old-session")
        val newSession = fakeSession(newStore, chapterId = 52L, sessionKey = "stable-new-session")

        try {
            scheduler.updateAutoWindow(
                oldIdentity,
                visiblePageIndex = 0,
                configuredAheadTarget = 1,
                pageCount = 3,
                session = oldSession,
                pageResolver = resolver("old"),
                computeClass = TranslatorComputeClass.REMOTE_IO,
            )
            val first = withTimeout(5_000) { stable.first { it?.identity == oldIdentity } }
            val firstVersion = first!!.windowVersion
            val firstOwnerVersion = first.ownerVersion

            scheduler.updateAutoWindow(
                oldIdentity,
                visiblePageIndex = 2,
                configuredAheadTarget = 0,
                pageCount = 3,
                session = oldSession,
                pageResolver = resolver("old"),
                computeClass = TranslatorComputeClass.REMOTE_IO,
            )
            // The scheduler-level flow re-points through flatMapLatest on the
            // scheduler's IO scope, so projection delivery is asynchronous to
            // updateAutoWindow returning: await the switched projection instead
            // of reading stable.value inline ( the inline read raced).
            val second = withTimeout(5_000) {
                stable.first {
                    it?.identity == oldIdentity &&
                        it.visiblePageIndex == 2 &&
                        it.windowVersion == firstVersion + 1
                }
            }
            second!!.ownerVersion shouldBe firstOwnerVersion

            // A replacement store with the same textual identity is still a
            // new owner and must fence old Reader snapshots/handles.
            val replacementStore = newStore(listOf("old0" to blank(), "old1" to blank(), "old2" to blank()))
            val replacementSession = fakeSession(
                replacementStore,
                chapterId = 51L,
                sessionKey = "stable-old-session",
            )
            scheduler.updateAutoWindow(
                oldIdentity,
                visiblePageIndex = 0,
                configuredAheadTarget = 1,
                pageCount = 3,
                session = replacementSession,
                pageResolver = resolver("replacement"),
                computeClass = TranslatorComputeClass.REMOTE_IO,
            )
            val replacement = withTimeout(5_000) {
                stable.first { it != null && it.identity == oldIdentity && it.ownerVersion > firstOwnerVersion }
            }
            (replacement!!.ownerVersion > firstOwnerVersion) shouldBe true

            scheduler.updateAutoWindow(
                newIdentity,
                visiblePageIndex = 0,
                configuredAheadTarget = 0,
                pageCount = 1,
                session = newSession,
                pageResolver = resolver("new"),
                computeClass = TranslatorComputeClass.REMOTE_IO,
            )
            withTimeout(5_000) { stable.first { it?.identity == newIdentity } }

            scheduler.shutdownAutoCoordinator()
            withTimeout(5_000) { stable.first { it == null } }
        } finally {
            scheduler.close()
        }
    }

    @Test
    @Timeout(60)
    fun `reconcile admission guard suppresses batch or revision recovery poke`() = runBlocking<Unit> {
        val executor = ControllableExecutor(autoComplete = false)
        val store = newStore(listOf("p0" to blank()))
        val scheduler = TranslationScheduler(
            executor = executor,
            storeResolver = TranslationStoreResolver { store },
            immediateStoreResolver = { store },
        )
        val session = fakeSession(store, chapterId = 61L, sessionKey = "ownership")
        val ownershipBlocked = AtomicBoolean(true)

        try {
            scheduler.updateAutoWindow(
                AutoChapterIdentity(61L, "ownership"),
                visiblePageIndex = 0,
                configuredAheadTarget = 0,
                pageCount = 1,
                session = session,
                pageResolver = resolver(),
                computeClass = TranslatorComputeClass.REMOTE_IO,
            )
            executor.awaitPrepareStarted(0)
            scheduler.reconcileAutoWindow { !ownershipBlocked.get() }
            executor.prepareCount.get() shouldBe 1

            ownershipBlocked.set(false)
            scheduler.reconcileAutoWindow { !ownershipBlocked.get() }
            executor.prepareCount.get() shouldBe 1
        } finally {
            scheduler.close()
        }
    }

    /**
     * Fake executor with controllable gates. Each prepare/translate call blocks
     * on a [CompletableDeferred] until the test completes it (when
     * [autoComplete] is false) or completes instantly (when true). Stage events
     * fire through the listener exactly as the real pipeline does, so the
     * coordinator's snapshot follows the real lifecycle.
     */
    private class ControllableExecutor(
        val autoComplete: Boolean,
        private val ignoreCancellation: Boolean = false,
    ) : TranslationExecutor {
        val prepareCount = AtomicInteger(0)
        val translateCount = AtomicInteger(0)
        val currentConcurrent = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)
        val prepareCallsByPage = ConcurrentHashMap<String, Int>()
        val translateCallsByPage = ConcurrentHashMap<String, Int>()
        val translateChapterIdsByPage = ConcurrentHashMap<String, Long?>()
        val failPrepareFor = ConcurrentHashMap.newKeySet<String>()
        val terminalPages = ConcurrentHashMap.newKeySet<String>()

        // Pages whose translate handoff returns false (stale/race) until the
        // counter drains, then returns true. Models the re-prepare contract.
        val staleTranslateRemaining = ConcurrentHashMap<String, AtomicInteger>()

        // Pages whose translate handoff throws a genuine failure.
        val failTranslateFor = ConcurrentHashMap.newKeySet<String>()

        // Pages whose typed provider outcome pauses once before a later
        // external reconcile retries the prepared boundary.
        val pausedTranslateRemaining = ConcurrentHashMap<String, AtomicInteger>()

        private val prepareStarted = ConcurrentHashMap<Int, CompletableDeferred<Unit>>()
        private val prepareGate = ConcurrentHashMap<Int, CompletableDeferred<Unit>>()
        private val translateStarted = ConcurrentHashMap<Int, CompletableDeferred<Unit>>()
        private val translateGate = ConcurrentHashMap<Int, CompletableDeferred<Unit>>()
        private val prepareListeners = ConcurrentHashMap<Int, TranslationStageListener>()

        private fun gate(map: ConcurrentHashMap<Int, CompletableDeferred<Unit>>, key: Int) =
            map.getOrPut(key) { CompletableDeferred() }

        private fun incConcurrent() {
            val v = currentConcurrent.incrementAndGet()
            maxConcurrent.set(maxOf(maxConcurrent.get(), v))
        }

        private fun decConcurrent() {
            currentConcurrent.decrementAndGet()
        }

        private fun emit(listener: TranslationStageListener?, pageKey: String, stage: TranslationStageEvent) {
            listener?.onStageEntered(pageKey, stage)
        }

        override suspend fun prepareSinglePage(
            manga: tachiyomi.domain.manga.model.Manga,
            chapter: tachiyomi.domain.chapter.model.Chapter,
            source: eu.kanade.tachiyomi.source.online.HttpSource,
            pageKey: String,
            streamFn: (() -> InputStream)?,
            force: Boolean,
            stageListener: TranslationStageListener?,
        ): PreparedPage? {
            val callNum = prepareCount.getAndIncrement()
            prepareCallsByPage.merge(pageKey, 1) { a, b -> a + b }
            stageListener?.let { prepareListeners[callNum] = it }
            incConcurrent()
            try {
                emit(stageListener, pageKey, TranslationStageEvent.READING)
                emit(stageListener, pageKey, TranslationStageEvent.CLEANING)
                gate(prepareStarted, callNum).complete(Unit)
                if (!autoComplete) {
                    if (ignoreCancellation) {
                        withContext(kotlinx.coroutines.NonCancellable) {
                            gate(prepareGate, callNum).await()
                        }
                    } else {
                        gate(prepareGate, callNum).await()
                    }
                }
            } finally {
                decConcurrent()
            }
            if (pageKey in failPrepareFor) return null
            return PreparedPage(
                pageKey = pageKey,
                chapterId = chapter.id,
                mangaId = manga.id,
                sourceId = source.id,
                cleanedImageName = if (pageKey in terminalPages) null else "cleaned.jpg",
                generation = 0L,
                pageVersion = 0L,
                blockFingerprints = emptyList(),
                isTerminal = pageKey in terminalPages,
            )
        }

        override suspend fun translatePreparedPage(
            manga: tachiyomi.domain.manga.model.Manga,
            chapter: tachiyomi.domain.chapter.model.Chapter,
            source: eu.kanade.tachiyomi.source.online.HttpSource,
            prepared: PreparedPage,
            stageListener: TranslationStageListener?,
        ): ChunkCompletionOutcome? {
            val callNum = translateCount.getAndIncrement()
            translateCallsByPage.merge(prepared.pageKey, 1) { a, b -> a + b }
            translateChapterIdsByPage[prepared.pageKey] = chapter.id
            incConcurrent()
            try {
                emit(stageListener, prepared.pageKey, TranslationStageEvent.TRANSLATING)
                emit(stageListener, prepared.pageKey, TranslationStageEvent.RENDERING)
                gate(translateStarted, callNum).complete(Unit)
                if (!autoComplete) gate(translateGate, callNum).await()
            } finally {
                decConcurrent()
            }
            val remaining = staleTranslateRemaining[prepared.pageKey]
            if (remaining != null && remaining.getAndDecrement() > 0) return null
            if (prepared.pageKey in failTranslateFor) {
                throw RuntimeException("translate failed: ${prepared.pageKey}")
            }
            val paused = pausedTranslateRemaining[prepared.pageKey]
            if (paused != null && paused.getAndDecrement() > 0) {
                return ChunkCompletionOutcome.Paused(prepared.pageKey)
            }
            return ChunkCompletionOutcome.Completed(setOf(prepared.pageKey))
        }

        override suspend fun translateSinglePage(
            manga: tachiyomi.domain.manga.model.Manga,
            chapter: tachiyomi.domain.chapter.model.Chapter,
            source: eu.kanade.tachiyomi.source.online.HttpSource,
            pageKey: String,
            force: Boolean,
            stageListener: TranslationStageListener?,
            origin: PageWriteOrigin,
        ): SinglePageOutcome = SinglePageOutcome.Completed

        override suspend fun translateSinglePageFromStream(
            manga: tachiyomi.domain.manga.model.Manga,
            chapter: tachiyomi.domain.chapter.model.Chapter,
            source: eu.kanade.tachiyomi.source.online.HttpSource,
            pageKey: String,
            streamFn: () -> InputStream,
            force: Boolean,
            stageListener: TranslationStageListener?,
        ) {}

        /** Waits for prepare[callIndex] to start, then immediately completes it. */
        suspend fun awaitAndCompletePrepare(callIndex: Int) {
            gate(prepareStarted, callIndex).await()
            gate(prepareGate, callIndex).complete(Unit)
        }

        /** Waits for prepare[callIndex] to start without completing it. */
        suspend fun awaitPrepareStarted(callIndex: Int) {
            gate(prepareStarted, callIndex).await()
        }

        /** Completes a prepare gate (must have been started already). */
        fun completePrepare(callIndex: Int) {
            gate(prepareGate, callIndex).complete(Unit)
        }

        /** Waits for translate[callIndex] to start. */
        suspend fun awaitTranslateStarted(callIndex: Int) {
            gate(translateStarted, callIndex).await()
        }

        /** Completes a translate gate. */
        fun completeTranslate(callIndex: Int) {
            gate(translateGate, callIndex).complete(Unit)
        }

        fun emitPrepareStage(callIndex: Int, stage: TranslationStageEvent) {
            prepareListeners[callIndex]?.onStageEntered("late-$callIndex", stage)
        }
    }
}
