package eu.kanade.translation.scheduling

import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.translation.engines.translator.AdmissionPriority
import eu.kanade.translation.engines.translator.TranslatorComputeClass
import eu.kanade.translation.model.PageRejectionKind
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.SinglePageOutcome
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.persistence.artifact.ArtifactSeed
import eu.kanade.translation.persistence.artifact.AtomicChapterDocuments
import eu.kanade.translation.persistence.artifact.ChapterArtifactEngine
import eu.kanade.translation.persistence.artifact.ChapterArtifactLayout
import eu.kanade.translation.persistence.artifact.FakeChapterDocumentIo
import eu.kanade.translation.persistence.artifact.loadArtifact
import eu.kanade.translation.persistence.chapter.ChapterTranslationStore
import eu.kanade.translation.persistence.chapter.PageWriteOrigin
import eu.kanade.translation.pipeline.execution.PreparedPage
import eu.kanade.translation.pipeline.execution.TranslationCompletionOutcome
import eu.kanade.translation.pipeline.execution.TranslationExecutor
import eu.kanade.translation.pipeline.execution.TranslationStageListener
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * S1 contract suite for the reader queue: demand/dedup/promotion/drain semantics
 * at the queue boundary, with a recording fake executor on virtual dispatchers
 * and the real house [ChapterTranslationStore] wherever admission is involved.
 */
class ReaderTranslationQueueTest {

    private val testScheduler = TestCoroutineScheduler()
    private val workerDispatcher: CoroutineDispatcher = StandardTestDispatcher(testScheduler)
    private val scope = CoroutineScope(workerDispatcher + SupervisorJob())
    private val manga = mockk<Manga>(relaxed = true)
    private val chapter = mockk<Chapter>(relaxed = true)
    private val source = mockk<HttpSource>(relaxed = true)
    private val translatorComputeClass = TranslatorComputeClass.REMOTE_IO

    private class RecordingExecutor : TranslationExecutor {
        data class Call(
            val pageKey: String,
            val force: Boolean,
            val origin: PageWriteOrigin,
            val streamFn: (() -> InputStream)?,
            val priorityFn: (() -> AdmissionPriority)?,
        )

        val paidPages = ConcurrentHashMap.newKeySet<String>()
        val paidEntries = ConcurrentHashMap<String, Boolean>()
        val jobs = ConcurrentHashMap<String, Job>()
        val calls = ConcurrentLinkedQueue<Call>()
        val attempts = ConcurrentHashMap<String, AtomicInteger>()
        val entered = ConcurrentHashMap<String, AtomicInteger>()
        val exited = ConcurrentHashMap<String, AtomicInteger>()
        val gates = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
        val outcomes = ConcurrentHashMap<String, SinglePageOutcome>()

        fun attemptsFor(pageKey: String): Int = attempts[pageKey]?.get() ?: 0

        fun enteredFor(pageKey: String): Int = entered[pageKey]?.get() ?: 0

        fun exitedFor(pageKey: String): Int = exited[pageKey]?.get() ?: 0

        fun hold(pageKey: String) {
            gates.getOrPut(pageKey) { CompletableDeferred() }
        }

        fun release(pageKey: String) {
            gates[pageKey]?.complete(Unit)
        }

        override suspend fun translateSinglePage(
            manga: Manga,
            chapter: Chapter,
            source: HttpSource,
            pageKey: String,
            force: Boolean,
            stageListener: TranslationStageListener?,
            origin: PageWriteOrigin,
            readerStreamFn: (() -> InputStream)?,
            readerPriority: (() -> AdmissionPriority)?,
        ): SinglePageOutcome {
            jobs[pageKey] = currentCoroutineContext()[Job]!!
            if (pageKey in paidPages) {
                paidEntries[pageKey] = currentCoroutineContext()[OperationPaidCallLifecycle]?.tryEnterPaidCall() == true
            }
            attempts.getOrPut(pageKey) { AtomicInteger(0) }.incrementAndGet()
            calls += Call(pageKey, force, origin, readerStreamFn, readerPriority)
            entered.getOrPut(pageKey) { AtomicInteger(0) }.incrementAndGet()
            try {
                gates[pageKey]?.let { gate -> withContext(NonCancellable) { gate.await() } }
            } finally {
                exited.getOrPut(pageKey) { AtomicInteger(0) }.incrementAndGet()
            }
            return outcomes[pageKey] ?: SinglePageOutcome.Completed
        }

        override suspend fun prepareSinglePage(
            manga: Manga,
            chapter: Chapter,
            source: HttpSource,
            pageKey: String,
            streamFn: (() -> InputStream)?,
            force: Boolean,
            stageListener: TranslationStageListener?,
        ): PreparedPage? = null

        override suspend fun translatePreparedPage(
            manga: Manga,
            chapter: Chapter,
            source: HttpSource,
            prepared: PreparedPage,
            stageListener: TranslationStageListener?,
        ): TranslationCompletionOutcome? = null
    }

    /** REAL house store over fake artifact IO — the production fresh-chapter recipe. */
    private fun houseStore(
        pageKeys: List<String>,
        rendered: Set<String> = emptySet(),
    ): ChapterTranslationStore {
        val artifactStore = ChapterArtifactEngine(
            AtomicChapterDocuments(FakeChapterDocumentIo()),
            ChapterArtifactLayout("S1 Reader Queue Chapter"),
        )
        val manifest = artifactStore
            .loadArtifact(ArtifactSeed(createdAtEpochMs = 1L))
            .manifest
        return ChapterTranslationStore(
            artifactParentResolver = null,
            initialPages = pageKeys.associateWith { key ->
                if (key in rendered) renderedPage(key) else PageTranslation(sourceFileName = key)
            },
            artifactStore = artifactStore,
            initialArtifactManifest = manifest,
        )
    }

    /** A page whose durable projection is display-ready (rendered translation). */
    private fun renderedPage(key: String): PageTranslation = PageTranslation(
        sourceFileName = key,
        cleanedImageName = "cleaned-$key.jpg",
        ocrStatus = StageStatus.READY,
        translationStatus = StageStatus.READY,
        inpaintStatus = StageStatus.READY,
        renderStatus = StageStatus.READY,
        inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION,
        blocks = mutableListOf(
            TranslationBlock(
                text = "source",
                translation = "translated",
                width = 1f,
                height = 1f,
                x = 0f,
                y = 0f,
                symHeight = 1f,
                symWidth = 1f,
                angle = 0f,
            ),
        ),
    )

    private fun newQueue(
        executor: RecordingExecutor = RecordingExecutor(),
        store: ChapterTranslationStore = houseStore((0..8).map { "p$it" }),
        memoryGate: () -> Boolean = { true },
    ): ReaderTranslationQueue =
        ReaderTranslationQueue(
            executor = executor,
            scope = scope,
            workerDispatcher = workerDispatcher,
            storeResolver = { store },
            nowEpochMs = { testScheduler.currentTime },
            memoryGate = memoryGate,
        )

    private val session = ReaderSessionToken("ch-identity-1", 1L)

    private fun bind(q: ReaderTranslationQueue) = q.bindSession(session, manga, chapter, source, translatorComputeClass)

    private fun work(pageKey: String, index: Int = 0) = ReaderPageWork(pageKey, index) { ByteArrayInputStream(byteArrayOf(1)) }

    @AfterEach
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `auto-off cancels running automatic work before paid entry`() = runTest(workerDispatcher) {
        val executor = RecordingExecutor()
        executor.hold("p0")
        val q = newQueue(executor)
        bind(q)
        try {
            q.updateViewport(session, 0, 1, true) { work("p0") }
            testScheduler.runCurrent()
            executor.enteredFor("p0") shouldBe 1
            q.setAutoEnabled(session, false)
            testScheduler.runCurrent()
            executor.jobs.getValue("p0").isCancelled shouldBe true
            executor.exitedFor("p0") shouldBe 0
        } finally {
            executor.release("p0")
            testScheduler.advanceUntilIdle()
        }
    }

    @Test
    fun `auto-off retains admitted automatic paid work and publishes its returned outcome`() = runTest(workerDispatcher) {
        val executor = RecordingExecutor()
        executor.paidPages += "p0"
        executor.hold("p0")
        val q = newQueue(executor)
        bind(q)
        try {
            q.updateViewport(session, 0, 1, true) { work("p0") }
            testScheduler.runCurrent()
            executor.enteredFor("p0") shouldBe 1
            executor.paidEntries["p0"] shouldBe true
            q.setAutoEnabled(session, false)
            testScheduler.runCurrent()
            executor.jobs.getValue("p0").isCancelled shouldBe false
            executor.exitedFor("p0") shouldBe 0
            executor.release("p0")
            testScheduler.advanceUntilIdle()
            executor.exitedFor("p0") shouldBe 1
            q.outcomes(session).value["p0"] shouldBe SinglePageOutcome.Completed
            executor.attemptsFor("p0") shouldBe 1
        } finally {
            executor.release("p0")
            testScheduler.advanceUntilIdle()
        }
    }

    @Test
    fun `automatic gate deferral keeps the demand paid lifecycle usable on a later event`() = runTest(workerDispatcher) {
        val executor = RecordingExecutor()
        executor.paidPages += "p1"
        executor.hold("p1")
        var memoryAvailable = false
        val q = newQueue(executor, houseStore(listOf("p0", "p1"), setOf("p0"))) { memoryAvailable }
        bind(q)
        try {
            q.updateViewport(session, 0, 2, true) { work("p$it", it) }
            testScheduler.runCurrent()
            executor.attemptsFor("p1") shouldBe 0
            memoryAvailable = true
            q.updateViewport(session, 0, 2, true) { work("p$it", it) }
            testScheduler.runCurrent()
            executor.enteredFor("p1") shouldBe 1
            executor.paidEntries["p1"] shouldBe true
        } finally {
            executor.release("p1")
            testScheduler.advanceUntilIdle()
        }
    }

    @Test
    fun `paid drain expiry cancels the exact worker but waits for physical exit`() = runTest(workerDispatcher) {
        val executor = RecordingExecutor()
        executor.paidPages += "p0"
        executor.hold("p0")
        val q = newQueue(executor)
        bind(q)
        try {
            q.updateViewport(session, 0, 1, true) { work("p0") }
            testScheduler.runCurrent()
            executor.paidEntries["p0"] shouldBe true
            q.setAutoEnabled(session, false)
            testScheduler.advanceTimeBy(210_000)
            testScheduler.runCurrent()
            executor.jobs.getValue("p0").isCancelled shouldBe true
            executor.exitedFor("p0") shouldBe 0
            val drain = q.closeAndDrain()
            drain.shouldBeInstanceOf<ReaderDrainResult.PendingNativeDrain>()
            drain.completion.isCompleted shouldBe false
            executor.release("p0")
            testScheduler.runCurrent()
            executor.exitedFor("p0") shouldBe 1
            drain.completion.isCompleted shouldBe true
            q.outcomes(session).value["p0"] shouldBe null
        } finally {
            executor.release("p0")
            testScheduler.advanceUntilIdle()
        }
    }

    @Test
    fun `explicit page cancellation stops an already retained paid worker`() = runTest(workerDispatcher) {
        val executor = RecordingExecutor()
        executor.paidPages += "p0"
        executor.hold("p0")
        val q = newQueue(executor)
        bind(q)
        try {
            q.updateViewport(session, 0, 1, true) { work("p0") }
            testScheduler.runCurrent()
            executor.paidEntries["p0"] shouldBe true
            q.setAutoEnabled(session, false)
            testScheduler.runCurrent()
            executor.jobs.getValue("p0").isCancelled shouldBe false
            q.cancelPage(session, "p0") shouldBe true
            testScheduler.runCurrent()
            executor.jobs.getValue("p0").isCancelled shouldBe true
            executor.exitedFor("p0") shouldBe 0
        } finally {
            executor.release("p0")
            testScheduler.advanceUntilIdle()
        }
    }

    @Test
    fun `rebind retains old paid work without publishing into the new binding`() = runTest(workerDispatcher) {
        val executor = RecordingExecutor()
        executor.paidPages += "p0"
        executor.hold("p0")
        val q = newQueue(executor)
        bind(q)
        val next = ReaderSessionToken(session.chapterIdentity, 2L)
        try {
            q.updateViewport(session, 0, 1, true) { work("p0") }
            testScheduler.runCurrent()
            executor.paidEntries["p0"] shouldBe true
            q.bindSession(next, manga, chapter, source, translatorComputeClass)
            testScheduler.runCurrent()
            executor.jobs.getValue("p0").isCancelled shouldBe false
            q.outcomes(next).value shouldBe emptyMap()
            executor.release("p0")
            testScheduler.advanceUntilIdle()
            executor.exitedFor("p0") shouldBe 1
            q.outcomes(next).value shouldBe emptyMap()
        } finally {
            executor.release("p0")
            testScheduler.advanceUntilIdle()
        }
    }

    // ---- session/store identity ----

    @Test
    fun `submission for an unbound session is a typed session rejection`() = runTest(workerDispatcher) {
        val executor = RecordingExecutor()
        val q = newQueue(executor)
        val submission = q.submitCurrent(session, work("p0"), ReaderTrigger.MANUAL_TAP)
        submission.shouldBeInstanceOf<ReaderSubmission.Rejected>()
        submission.outcome.kind shouldBe PageRejectionKind.SESSION_DENIED
        executor.attemptsFor("p0") shouldBe 0
    }

    @Test
    fun `stale token after rebind to a new reader generation is rejected`() = runTest(workerDispatcher) {
        val executor = RecordingExecutor()
        val q = newQueue(executor)
        bind(q)
        val next = ReaderSessionToken(session.chapterIdentity, 2L)
        q.bindSession(next, manga, chapter, source, translatorComputeClass)
        val submission = q.submitCurrent(session, work("p0"), ReaderTrigger.MANUAL_TAP)
        submission.shouldBeInstanceOf<ReaderSubmission.Rejected>()
        submission.outcome.kind shouldBe PageRejectionKind.SESSION_DENIED
    }

    // ---- dedup and force ordering (parity case 1) ----

    @Test
    fun `auto-off tap N runs once and repeated compatible taps return Existing`() = runTest(workerDispatcher) {
        val executor = RecordingExecutor()
        val q = newQueue(executor)
        bind(q)
        q.submitCurrent(session, work("p0"), ReaderTrigger.MANUAL_TAP)
        testScheduler.advanceUntilIdle()
        executor.attemptsFor("p0") shouldBe 1
        val repeat = q.submitCurrent(session, work("p0"), ReaderTrigger.MANUAL_TAP)
        repeat.shouldBeInstanceOf<ReaderSubmission.Existing>()
        testScheduler.advanceUntilIdle()
        executor.attemptsFor("p0") shouldBe 1
    }

    @Test
    fun `failed-stage force creates an ordered replacement attempt`() = runTest(workerDispatcher) {
        val executor = RecordingExecutor()
        val q = newQueue(executor)
        bind(q)
        executor.outcomes["p0"] = SinglePageOutcome.Failed("p0", "inpaint failed")
        q.submitCurrent(session, work("p0"), ReaderTrigger.MANUAL_TAP)
        testScheduler.advanceUntilIdle()
        executor.attemptsFor("p0") shouldBe 1
        q.submitCurrent(session, work("p0"), ReaderTrigger.MANUAL_TAP, force = true)
        testScheduler.advanceUntilIdle()
        executor.attemptsFor("p0") shouldBe 2
    }

    // ---- N/N+1 window (parity case 2) ----

    @Test
    fun `auto-on viewport demands exactly the visible page and the next page`() = runTest(workerDispatcher) {
        val executor = RecordingExecutor()
        val q = newQueue(executor)
        bind(q)
        q.updateViewport(session, 0, 4, isAutoEnabled = true, resolve = { i -> work("p$i", i) })
        testScheduler.advanceUntilIdle()
        executor.attemptsFor("p0") shouldBe 1
        executor.attemptsFor("p1") shouldBe 1
        executor.attemptsFor("p2") shouldBe 0
    }

    @Test
    fun `viewport on the last page manufactures no next page`() = runTest(workerDispatcher) {
        val executor = RecordingExecutor()
        val q = newQueue(executor)
        bind(q)
        q.updateViewport(session, 3, 4, isAutoEnabled = true, resolve = { i -> work("p$i", i) })
        testScheduler.advanceUntilIdle()
        executor.attemptsFor("p3") shouldBe 1
        executor.calls.none { it.pageKey != "p3" } shouldBe true
    }

    @Test
    fun `empty chapter viewport performs no work`() = runTest(workerDispatcher) {
        val executor = RecordingExecutor()
        val q = newQueue(executor)
        bind(q)
        q.updateViewport(session, 0, 0, isAutoEnabled = true, resolve = { null })
        testScheduler.advanceUntilIdle()
        executor.calls.isEmpty() shouldBe true
    }

    @Test
    fun `double-page spread shares one canonical key and runs once`() = runTest(workerDispatcher) {
        val executor = RecordingExecutor()
        val q = newQueue(executor)
        bind(q)
        // reader slots 1 and 2 are both visible in the same spread and map to one canonical page
        q.updateViewport(session, 1, 4, isAutoEnabled = true, resolve = { i ->
            if (i == 1 || i == 2) work("p1", 1) else work("p$i", i)
        })
        testScheduler.advanceUntilIdle()
        executor.attemptsFor("p1") shouldBe 1
        executor.calls.none { it.pageKey != "p1" } shouldBe true
    }

    // ---- viewport supersede / prune (parity case 4) ----

    @Test
    fun `a superseded viewport prunes undispatched speculative work and keeps explicit demand`() = runTest(workerDispatcher) {
        val executor = RecordingExecutor()
        val q = newQueue(executor)
        bind(q)
        q.submitCurrent(session, work("p0"), ReaderTrigger.MANUAL_TAP)
        testScheduler.advanceUntilIdle()
        executor.attemptsFor("p0") shouldBe 1
        // the 5/8 window is installed and superseded before its work can dispatch
        q.updateViewport(session, 5, 8, isAutoEnabled = true, resolve = { i -> work("p$i", i) })
        q.updateViewport(session, 0, 2, isAutoEnabled = true, resolve = { i -> work("p$i", i) })
        testScheduler.advanceUntilIdle()
        executor.attemptsFor("p5") shouldBe 0
        executor.attemptsFor("p6") shouldBe 0
        executor.attemptsFor("p0") shouldBe 1
        executor.attemptsFor("p1") shouldBe 1
    }

    // ---- promotion (partial parity case 3: admission-time states) ----

    @Test
    fun `promotion of a running prefetch rereads the live priority in place`() = runTest(workerDispatcher) {
        val executor = RecordingExecutor()
        val q = newQueue(executor)
        bind(q)
        executor.hold("p1")
        try {
            q.updateViewport(session, 0, 3, isAutoEnabled = true, resolve = { i -> work("p$i", i) })
            testScheduler.advanceUntilIdle()
            val calls = executor.calls.filter { it.pageKey == "p1" }
            calls.size shouldBe 1
            executor.enteredFor("p1") shouldBe 1
            val beforePromotion = calls.first().priorityFn?.invoke()
            beforePromotion shouldBe AdmissionPriority.BACKGROUND
            val promoted = q.submitCurrent(session, work("p1", 1), ReaderTrigger.MANUAL_TAP)
            testScheduler.advanceUntilIdle()
            promoted.shouldBeInstanceOf<ReaderSubmission.Promoted>()
            val afterPromotion = calls.first().priorityFn?.invoke()
            afterPromotion shouldBe AdmissionPriority.INTERACTIVE
            executor.calls.count { it.pageKey == "p1" } shouldBe 1
            executor.attemptsFor("p1") shouldBe 1
        } finally {
            executor.release("p1")
        }
    }

    // ---- fencing ----

    @Test
    fun `viewport revision fencing drops a superseded resolver install`() = runTest(workerDispatcher) {
        val executor = RecordingExecutor()
        val q = newQueue(executor)
        bind(q)
        var superseded = false
        q.updateViewport(session, 0, 4, isAutoEnabled = true, resolve = { i ->
            if (!superseded) {
                superseded = true
                q.updateViewport(session, 2, 4, isAutoEnabled = true, resolve = { next -> work("p$next", next) })
            }
            work("p$i", i)
        })
        testScheduler.advanceUntilIdle()
        superseded shouldBe true
        executor.attemptsFor("p2") shouldBe 1
        executor.attemptsFor("p3") shouldBe 1
        executor.attemptsFor("p0") shouldBe 0
        executor.attemptsFor("p1") shouldBe 0
    }

    @Test
    fun `resolver runs outside the control lock`() = runTest(workerDispatcher) {
        val executor = RecordingExecutor()
        var resolverRan = false
        val q = ReaderTranslationQueue(
            executor = executor,
            scope = scope,
            workerDispatcher = workerDispatcher,
            storeResolver = {
                resolverRan = true
                null
            },
            nowEpochMs = { testScheduler.currentTime },
        )
        bind(q)
        q.updateViewport(session, 0, 2, isAutoEnabled = true, resolve = { i -> work("p$i", i) })
        testScheduler.advanceUntilIdle()
        resolverRan shouldBe true
    }

    // ---- deferral policy (parity case 7) ----

    @Test
    fun `missing source stream demand records no fabricated success`() = runTest(workerDispatcher) {
        val executor = RecordingExecutor()
        val q = newQueue(executor)
        bind(q)
        q.updateViewport(session, 0, 2, isAutoEnabled = true, resolve = { null })
        testScheduler.advanceUntilIdle()
        executor.calls.isEmpty() shouldBe true
        q.outcomes(session).value["p0"].let { outcome ->
            outcome == null || outcome !is SinglePageOutcome.Completed
        } shouldBe true
    }

    @Test
    fun `repeated viewport updates do not re-fire fulfilled demand`() = runTest(workerDispatcher) {
        val executor = RecordingExecutor()
        val q = newQueue(executor)
        bind(q)
        repeat(5) {
            q.updateViewport(session, 0, 3, isAutoEnabled = true, resolve = { i -> work("p$i", i) })
            testScheduler.advanceUntilIdle()
        }
        executor.attemptsFor("p1") shouldBe 1
        executor.attemptsFor("p0") shouldBe 1
    }

    @Test
    fun `a Paused outcome is not re-fired by later viewport updates`() = runTest(workerDispatcher) {
        val executor = RecordingExecutor()
        val q = newQueue(executor)
        bind(q)
        executor.outcomes["p0"] = SinglePageOutcome.Paused(nextEligibleRetryAtEpochMs = 10_000L)
        q.submitCurrent(session, work("p0"), ReaderTrigger.MANUAL_TAP)
        testScheduler.advanceUntilIdle()
        executor.attemptsFor("p0") shouldBe 1
        repeat(3) {
            q.updateViewport(session, 0, 2, isAutoEnabled = true, resolve = { i -> work("p$i", i) })
            testScheduler.advanceUntilIdle()
        }
        executor.attemptsFor("p0") shouldBe 1
    }

    @Test
    fun `terminal Failed outcome is not re-fired by later viewport updates`() = runTest(workerDispatcher) {
        val executor = RecordingExecutor()
        val q = newQueue(executor)
        bind(q)
        executor.outcomes["p0"] = SinglePageOutcome.Failed("p0", "reprepare exhausted")
        repeat(5) {
            q.updateViewport(session, 0, 2, isAutoEnabled = true, resolve = { i -> work("p$i", i) })
            testScheduler.advanceUntilIdle()
        }
        executor.attemptsFor("p0") shouldBe 1
    }

    @Test
    fun `auto dispatch consults the house attempt gate before execution`() = runTest(workerDispatcher) {
        val store = spyk(houseStore(listOf("p0", "p1")))
        val gateConsults = AtomicInteger(0)
        coEvery { store.autoAttemptAllowed(any()) } answers {
            callOriginal().also { gateConsults.incrementAndGet() }
        }
        val executor = RecordingExecutor()
        val q = ReaderTranslationQueue(
            executor = executor,
            scope = scope,
            workerDispatcher = workerDispatcher,
            storeResolver = { store },
            nowEpochMs = { testScheduler.currentTime },
        )
        bind(q)
        q.updateViewport(session, 0, 2, isAutoEnabled = true, resolve = { i -> work("p$i", i) })
        testScheduler.advanceUntilIdle()
        gateConsults.get() shouldBe 2
    }

    // ---- admission gap closure ----

    @Test
    fun `attempt cap denial defers auto dispatch until the gate allows`() = runTest(workerDispatcher) {
        val store = spyk(houseStore(listOf("p0", "p1")))
        var allow = false
        coEvery { store.autoAttemptAllowed(any()) } answers { allow }
        val executor = RecordingExecutor()
        val q = ReaderTranslationQueue(
            executor = executor,
            scope = scope,
            workerDispatcher = workerDispatcher,
            storeResolver = { store },
            nowEpochMs = { testScheduler.currentTime },
        )
        bind(q)
        q.updateViewport(session, 0, 2, isAutoEnabled = true, resolve = { i -> work("p$i", i) })
        testScheduler.advanceUntilIdle()
        executor.attemptsFor("p0") shouldBe 0
        executor.attemptsFor("p1") shouldBe 0
        allow = true
        q.updateViewport(session, 0, 2, isAutoEnabled = true, resolve = { i -> work("p$i", i) })
        testScheduler.advanceUntilIdle()
        executor.attemptsFor("p0") shouldBe 1
        executor.attemptsFor("p1") shouldBe 1
    }

    @Test
    fun `display-ready pages skip execution until force re-demands`() = runTest(workerDispatcher) {
        val store = houseStore(listOf("p0"), rendered = setOf("p0"))
        val executor = RecordingExecutor()
        val q = ReaderTranslationQueue(
            executor = executor,
            scope = scope,
            workerDispatcher = workerDispatcher,
            storeResolver = { store },
            nowEpochMs = { testScheduler.currentTime },
        )
        bind(q)
        q.updateViewport(session, 0, 1, isAutoEnabled = true, resolve = { i -> work("p$i", i) })
        testScheduler.advanceUntilIdle()
        executor.attemptsFor("p0") shouldBe 0
        val forced = q.submitCurrent(session, work("p0"), ReaderTrigger.MANUAL_TAP, force = true)
        forced.shouldBeInstanceOf<ReaderSubmission.Queued>()
        testScheduler.advanceUntilIdle()
        executor.attemptsFor("p0") shouldBe 1
    }

    @Test
    fun `memory pressure defers prefetch and resumes when budget returns`() = runTest(workerDispatcher) {
        var headroom = false
        val executor = RecordingExecutor()
        val q = newQueue(executor, memoryGate = { headroom })
        bind(q)
        q.updateViewport(session, 0, 3, isAutoEnabled = true, resolve = { i -> work("p$i", i) })
        testScheduler.advanceUntilIdle()
        executor.attemptsFor("p0") shouldBe 1
        executor.attemptsFor("p1") shouldBe 0
        q.updateViewport(session, 0, 3, isAutoEnabled = true, resolve = { i -> work("p$i", i) })
        testScheduler.advanceUntilIdle()
        executor.attemptsFor("p1") shouldBe 0
        headroom = true
        q.updateViewport(session, 0, 3, isAutoEnabled = true, resolve = { i -> work("p$i", i) })
        testScheduler.advanceUntilIdle()
        executor.attemptsFor("p1") shouldBe 1
    }

    @Test
    fun `source-ready retry dispatches when the resolver returns work`() = runTest(workerDispatcher) {
        val executor = RecordingExecutor()
        val q = newQueue(executor)
        bind(q)
        q.updateViewport(session, 0, 2, isAutoEnabled = true, resolve = { null })
        testScheduler.advanceUntilIdle()
        executor.calls.isEmpty() shouldBe true
        q.updateViewport(session, 0, 2, isAutoEnabled = true, resolve = { i -> work("p$i", i) })
        testScheduler.advanceUntilIdle()
        executor.attemptsFor("p0") shouldBe 1
        executor.attemptsFor("p1") shouldBe 1
    }

    @Test
    fun `provider cooldown gates viewport re-demands until expiry`() = runTest(workerDispatcher) {
        val executor = RecordingExecutor()
        val q = newQueue(executor)
        bind(q)
        executor.outcomes["p0"] = SinglePageOutcome.Paused(nextEligibleRetryAtEpochMs = 1_000L)
        q.submitCurrent(session, work("p0"), ReaderTrigger.MANUAL_TAP)
        testScheduler.advanceUntilIdle()
        executor.attemptsFor("p0") shouldBe 1
        q.updateViewport(session, 0, 1, isAutoEnabled = true, resolve = { i -> work("p$i", i) })
        testScheduler.advanceUntilIdle()
        executor.attemptsFor("p0") shouldBe 1
        testScheduler.advanceTimeBy(2_000L)
        q.updateViewport(session, 0, 1, isAutoEnabled = true, resolve = { i -> work("p$i", i) })
        testScheduler.advanceUntilIdle()
        executor.attemptsFor("p0") shouldBe 2
    }

    // ---- execution lanes ----

    @Test
    fun `local compute serializes page runners while remote admits both`() = runTest(workerDispatcher) {
        val executor = RecordingExecutor()
        val q = newQueue(executor)
        q.bindSession(session, manga, chapter, source, TranslatorComputeClass.LOCAL_COMPUTE)
        executor.hold("p0")
        try {
            q.submitCurrent(session, work("p0"), ReaderTrigger.MANUAL_TAP)
            q.submitCurrent(session, work("p1", 1), ReaderTrigger.MANUAL_TAP)
            testScheduler.advanceUntilIdle()
            executor.enteredFor("p0") shouldBe 1
            executor.enteredFor("p1") shouldBe 0
            executor.release("p0")
            testScheduler.advanceUntilIdle()
            executor.enteredFor("p1") shouldBe 1
        } finally {
            executor.release("p0")
            executor.release("p1")
        }
    }

    @Test
    fun `auto-off prunes speculative demand and keeps explicit work running`() = runTest(workerDispatcher) {
        val executor = RecordingExecutor()
        val q = newQueue(executor)
        bind(q)
        executor.hold("p0")
        try {
            q.submitCurrent(session, work("p0"), ReaderTrigger.MANUAL_TAP)
            testScheduler.advanceUntilIdle()
            executor.enteredFor("p0") shouldBe 1
            q.updateViewport(session, 0, 2, isAutoEnabled = true, resolve = { i -> work("p$i", i) })
            q.setAutoEnabled(session, false)
            testScheduler.advanceUntilIdle()
            executor.attemptsFor("p1") shouldBe 0
            executor.enteredFor("p0") shouldBe 1
            executor.release("p0")
            testScheduler.advanceUntilIdle()
            executor.exitedFor("p0") shouldBe 1
        } finally {
            executor.release("p0")
        }
    }

    @Test
    fun `force behind an active predecessor runs after it exits`() = runTest(workerDispatcher) {
        val executor = RecordingExecutor()
        val q = newQueue(executor)
        bind(q)
        executor.hold("p0")
        try {
            q.submitCurrent(session, work("p0"), ReaderTrigger.MANUAL_TAP)
            testScheduler.advanceUntilIdle()
            executor.enteredFor("p0") shouldBe 1
            val forced = q.submitCurrent(session, work("p0"), ReaderTrigger.MANUAL_TAP, force = true)
            forced.shouldBeInstanceOf<ReaderSubmission.Queued>()
            testScheduler.advanceUntilIdle()
            executor.attemptsFor("p0") shouldBe 1
            executor.release("p0")
            testScheduler.advanceUntilIdle()
            executor.attemptsFor("p0") shouldBe 2
        } finally {
            executor.release("p0")
        }
    }

    // ---- bounded capacity ----

    @Test
    fun `explicit pending capacity is bounded with a typed rejection`() = runTest(workerDispatcher) {
        val executor = RecordingExecutor()
        val q = newQueue(executor)
        bind(q)
        repeat(9) { i -> executor.hold("p$i") }
        try {
            // EXPLICIT_BACKLOG_LIMIT is 8: nine held demands reject the ninth.
            val submissions = (0..8).map { i -> q.submitCurrent(session, work("p$i", i), ReaderTrigger.MANUAL_TAP) }
            val last = submissions.last()
            last.shouldBeInstanceOf<ReaderSubmission.Rejected>()
            last.outcome.owner shouldBe null
            last.outcome.kind shouldBe PageRejectionKind.OTHER
        } finally {
            repeat(9) { i -> executor.release("p$i") }
        }
    }

    @Test
    fun `terminal outcome retention is capped at thirty-two`() = runTest(workerDispatcher) {
        val executor = RecordingExecutor()
        val q = newQueue(executor)
        bind(q)
        for (i in 0 until 40) {
            q.submitCurrent(session, work("p$i", i), ReaderTrigger.MANUAL_TAP)
            testScheduler.advanceUntilIdle()
        }
        val outcomes = q.outcomes(session).value
        outcomes.size shouldBe 32
    }

    // ---- drain and cancellation ----

    @Test
    fun `cancelSession completes when no operations are outstanding`() = runTest(workerDispatcher) {
        val executor = RecordingExecutor()
        val q = newQueue(executor)
        bind(q)
        val result = q.cancelSession(session)
        result shouldBe ReaderDrainResult.Completed
    }

    @Test
    fun `cancelSession returns a native drain handle spanning every physical exit`() = runTest(workerDispatcher) {
        val executor = RecordingExecutor()
        val q = newQueue(executor)
        bind(q)
        executor.hold("p0")
        executor.hold("p1")
        try {
            q.submitCurrent(session, work("p0"), ReaderTrigger.MANUAL_TAP)
            q.submitCurrent(session, work("p1", 1), ReaderTrigger.MANUAL_TAP)
            testScheduler.advanceUntilIdle()
            executor.enteredFor("p0") shouldBe 1
            executor.enteredFor("p1") shouldBe 1
            val result = q.cancelSession(session)
            result.shouldBeInstanceOf<ReaderDrainResult.PendingNativeDrain>()
            executor.exitedFor("p0") shouldBe 0
            executor.exitedFor("p1") shouldBe 0
            result.completion.isCompleted shouldBe false
            executor.release("p0")
            testScheduler.advanceUntilIdle()
            executor.exitedFor("p0") shouldBe 1
            // the second straggler keeps the aggregate drain open
            result.completion.isCompleted shouldBe false
            executor.release("p1")
            testScheduler.advanceUntilIdle()
            executor.exitedFor("p1") shouldBe 1
            result.completion.isCompleted shouldBe true
        } finally {
            executor.release("p0")
            executor.release("p1")
        }
    }

    @Test
    fun `closeAndDrain reports a live aggregate until every physical exit`() = runTest(workerDispatcher) {
        val executor = RecordingExecutor()
        val q = newQueue(executor)
        bind(q)
        executor.hold("p0")
        executor.hold("p1")
        try {
            q.submitCurrent(session, work("p0"), ReaderTrigger.MANUAL_TAP)
            q.submitCurrent(session, work("p1", 1), ReaderTrigger.MANUAL_TAP)
            testScheduler.advanceUntilIdle()
            executor.enteredFor("p0") shouldBe 1
            executor.enteredFor("p1") shouldBe 1
            val result = q.closeAndDrain()
            result.shouldBeInstanceOf<ReaderDrainResult.PendingNativeDrain>()
            result.completion.isCompleted shouldBe false
            executor.release("p0")
            testScheduler.advanceUntilIdle()
            result.completion.isCompleted shouldBe false
            executor.release("p1")
            testScheduler.advanceUntilIdle()
            result.completion.isCompleted shouldBe true
            // no fabricated terminals for cancelled work
            q.outcomes(session).value.isEmpty() shouldBe true
        } finally {
            executor.release("p0")
            executor.release("p1")
        }
    }

    @Test
    fun `cancelPage retains the running job until physical completion`() = runTest(workerDispatcher) {
        val executor = RecordingExecutor()
        val q = newQueue(executor)
        bind(q)
        executor.hold("p0")
        try {
            q.submitCurrent(session, work("p0"), ReaderTrigger.MANUAL_TAP)
            testScheduler.advanceUntilIdle()
            executor.enteredFor("p0") shouldBe 1
            q.cancelPage(session, "p0") shouldBe true
            testScheduler.advanceUntilIdle()
            executor.exitedFor("p0") shouldBe 0
            executor.attemptsFor("p0") shouldBe 1
            executor.release("p0")
            testScheduler.advanceUntilIdle()
            executor.exitedFor("p0") shouldBe 1
            executor.attemptsFor("p0") shouldBe 1
        } finally {
            executor.release("p0")
        }
    }

    @Test
    fun `cancellation before body entry records exactly one typed terminal`() = runTest(workerDispatcher) {
        val executor = RecordingExecutor()
        val q = newQueue(executor)
        bind(q)
        q.submitCurrent(session, work("p0"), ReaderTrigger.MANUAL_TAP)
        q.cancelSession(session)
        testScheduler.advanceUntilIdle()
        val outcome = q.outcomes(session).value["p0"]
        outcome.shouldBeInstanceOf<SinglePageOutcome.Rejected>()
    }

    // ---- binding fence and liveness regressions ----

    @Test
    fun `stale generation completion publishes nothing into the replacement flow`() = runTest(workerDispatcher) {
        val executor = RecordingExecutor()
        val q = newQueue(executor)
        val first = session
        q.bindSession(first, manga, chapter, source, translatorComputeClass)
        executor.hold("p0")
        try {
            q.submitCurrent(first, work("p0"), ReaderTrigger.MANUAL_TAP)
            testScheduler.advanceUntilIdle()
            executor.attemptsFor("p0") shouldBe 1
            val second = ReaderSessionToken(first.chapterIdentity, 2L)
            q.bindSession(second, manga, chapter, source, translatorComputeClass)
            executor.release("p0")
            testScheduler.advanceUntilIdle()
            q.outcomes(second).value.isEmpty() shouldBe true
            q.outcomes(first).value.isEmpty() shouldBe true
            executor.attemptsFor("p0") shouldBe 1
            val fresh = q.submitCurrent(second, work("p0"), ReaderTrigger.MANUAL_TAP)
            fresh.shouldBeInstanceOf<ReaderSubmission.Queued>()
            testScheduler.advanceUntilIdle()
            executor.attemptsFor("p0") shouldBe 2
            q.outcomes(second).value.size shouldBe 1
        } finally {
            executor.release("p0")
        }
    }

    @Test
    fun `rebind during a suspended gate consult fences the suspended demand`() = runTest(workerDispatcher) {
        val store = spyk(houseStore(listOf("p0", "p1")))
        val consultGate = CompletableDeferred<Boolean>()
        coEvery { store.autoAttemptAllowed(any()) } coAnswers { consultGate.await() }
        val executor = RecordingExecutor()
        val q = ReaderTranslationQueue(
            executor = executor,
            scope = scope,
            workerDispatcher = workerDispatcher,
            storeResolver = { store },
            nowEpochMs = { testScheduler.currentTime },
        )
        val first = session
        q.bindSession(first, manga, chapter, source, translatorComputeClass)
        q.updateViewport(first, 0, 2, isAutoEnabled = true, resolve = { i -> work("p$i", i) })
        testScheduler.advanceUntilIdle()
        executor.attemptsFor("p0") shouldBe 0
        val second = ReaderSessionToken(first.chapterIdentity, 2L)
        q.bindSession(second, manga, chapter, source, translatorComputeClass)
        consultGate.complete(true)
        testScheduler.advanceUntilIdle()
        executor.attemptsFor("p0") shouldBe 0
        executor.attemptsFor("p1") shouldBe 0
        val submission = q.submitCurrent(second, work("p0"), ReaderTrigger.MANUAL_TAP)
        submission.shouldBeInstanceOf<ReaderSubmission.Queued>()
        testScheduler.advanceUntilIdle()
        executor.attemptsFor("p0") shouldBe 1
    }

    @Test
    fun `repeated events claim a deferred demand exactly once per event`() = runTest(workerDispatcher) {
        val store = spyk(houseStore(listOf("p0", "p1")))
        var allow = false
        val consults = AtomicInteger(0)
        coEvery { store.autoAttemptAllowed(any()) } answers {
            consults.incrementAndGet()
            allow
        }
        val executor = RecordingExecutor()
        val q = ReaderTranslationQueue(
            executor = executor,
            scope = scope,
            workerDispatcher = workerDispatcher,
            storeResolver = { store },
            nowEpochMs = { testScheduler.currentTime },
        )
        bind(q)
        q.updateViewport(session, 0, 2, isAutoEnabled = true, resolve = { i -> work("p$i", i) })
        testScheduler.advanceUntilIdle()
        q.updateViewport(session, 0, 2, isAutoEnabled = true, resolve = { i -> work("p$i", i) })
        testScheduler.advanceUntilIdle()
        consults.get() shouldBe 4
        executor.attemptsFor("p0") shouldBe 0
        allow = true
        q.updateViewport(session, 0, 2, isAutoEnabled = true, resolve = { i -> work("p$i", i) })
        testScheduler.advanceUntilIdle()
        consults.get() shouldBe 6
        executor.attemptsFor("p0") shouldBe 1
        executor.attemptsFor("p1") shouldBe 1
    }

    @Test
    fun `page cancel takes the whole force chain with an honest queued terminal`() = runTest(workerDispatcher) {
        val executor = RecordingExecutor()
        val q = newQueue(executor)
        bind(q)
        executor.hold("p0")
        try {
            q.submitCurrent(session, work("p0"), ReaderTrigger.MANUAL_TAP)
            testScheduler.advanceUntilIdle()
            executor.enteredFor("p0") shouldBe 1
            val forced = q.submitCurrent(session, work("p0"), ReaderTrigger.MANUAL_TAP, force = true)
            forced.shouldBeInstanceOf<ReaderSubmission.Queued>()
            q.cancelPage(session, "p0") shouldBe true
            testScheduler.advanceUntilIdle()
            executor.attemptsFor("p0") shouldBe 1
            val terminals = q.outcomes(session).value
            terminals.size shouldBe 1
            terminals["p0"].shouldBeInstanceOf<SinglePageOutcome.Rejected>()
            executor.release("p0")
            testScheduler.advanceUntilIdle()
            executor.attemptsFor("p0") shouldBe 1
            executor.exitedFor("p0") shouldBe 1
        } finally {
            executor.release("p0")
        }
    }

    @Test
    fun `pre-dispatch workers are covered by the drain handle`() = runTest(workerDispatcher) {
        val store = spyk(houseStore(listOf("p0")))
        val consultGate = CompletableDeferred<Unit>()
        coEvery { store.autoAttemptAllowed(any()) } coAnswers {
            withContext(NonCancellable) { consultGate.await() }
            true
        }
        val executor = RecordingExecutor()
        val q = ReaderTranslationQueue(
            executor = executor,
            scope = scope,
            workerDispatcher = workerDispatcher,
            storeResolver = { store },
            nowEpochMs = { testScheduler.currentTime },
        )
        bind(q)
        try {
            q.updateViewport(session, 0, 1, isAutoEnabled = true, resolve = { i -> work("p$i", i) })
            testScheduler.advanceUntilIdle()
            executor.attemptsFor("p0") shouldBe 0
            val result = q.cancelSession(session)
            result.shouldBeInstanceOf<ReaderDrainResult.PendingNativeDrain>()
            result.completion.isCompleted shouldBe false
            consultGate.complete(Unit)
            testScheduler.advanceUntilIdle()
            executor.attemptsFor("p0") shouldBe 0
            result.completion.isCompleted shouldBe true
        } finally {
            consultGate.complete(Unit)
        }
    }

    @Test
    fun `automatic physical operations stay capped across retained windows`() = runTest(workerDispatcher) {
        val executor = RecordingExecutor()
        val q = newQueue(executor)
        bind(q)
        executor.hold("p0")
        executor.hold("p1")
        executor.hold("p2")
        try {
            q.updateViewport(session, 0, 4, isAutoEnabled = true, resolve = { i -> work("p$i", i) })
            testScheduler.advanceUntilIdle()
            executor.enteredFor("p0") shouldBe 1
            executor.enteredFor("p1") shouldBe 1
            q.updateViewport(session, 2, 4, isAutoEnabled = true, resolve = { i -> work("p$i", i) })
            testScheduler.advanceUntilIdle()
            executor.attemptsFor("p2") shouldBe 0
            executor.attemptsFor("p3") shouldBe 0
            executor.release("p0")
            testScheduler.advanceUntilIdle()
            executor.attemptsFor("p2") shouldBe 1
            executor.attemptsFor("p3") shouldBe 0
            executor.release("p1")
            testScheduler.advanceUntilIdle()
            executor.attemptsFor("p3") shouldBe 1
        } finally {
            executor.release("p2")
            executor.release("p0")
            executor.release("p1")
        }
    }

    @Test
    fun `worker runs on the injected dispatcher`() = runTest {
        val schedulerA = TestCoroutineScheduler()
        val dispatcherA = StandardTestDispatcher(schedulerA)
        val scopeA = CoroutineScope(dispatcherA + SupervisorJob())
        val schedulerB = TestCoroutineScheduler()
        val dispatcherB = StandardTestDispatcher(schedulerB)
        val executor = RecordingExecutor()
        val q = ReaderTranslationQueue(
            executor = executor,
            scope = scopeA,
            workerDispatcher = dispatcherB,
            storeResolver = { null },
            nowEpochMs = { schedulerB.currentTime },
        )
        try {
            q.bindSession(session, manga, chapter, source, translatorComputeClass)
            q.submitCurrent(session, work("p0"), ReaderTrigger.MANUAL_TAP)
            schedulerA.advanceUntilIdle()
            executor.attemptsFor("p0") shouldBe 0
            schedulerB.advanceUntilIdle()
            executor.attemptsFor("p0") shouldBe 1
        } finally {
            scopeA.cancel()
        }
    }

    @Test
    fun `null captured factory stays pending and refreshes on a later tap`() = runTest(workerDispatcher) {
        val executor = RecordingExecutor()
        val q = newQueue(executor)
        bind(q)
        q.submitCurrent(session, ReaderPageWork("p0", 0, null), ReaderTrigger.MANUAL_TAP)
        testScheduler.advanceUntilIdle()
        executor.attemptsFor("p0") shouldBe 0
        q.outcomes(session).value.isEmpty() shouldBe true
        q.submitCurrent(session, work("p0"), ReaderTrigger.MANUAL_TAP)
        testScheduler.advanceUntilIdle()
        executor.attemptsFor("p0") shouldBe 1
    }

    @Test
    fun `null captured viewport factory refreshes when source is ready`() = runTest(workerDispatcher) {
        val executor = RecordingExecutor()
        val q = newQueue(executor)
        bind(q)
        q.updateViewport(session, 0, 1, true) { ReaderPageWork("p0", 0, null) }
        testScheduler.advanceUntilIdle()
        executor.attemptsFor("p0") shouldBe 0
        q.updateViewport(session, 0, 1, true) { work("p0") }
        testScheduler.advanceUntilIdle()
        executor.attemptsFor("p0") shouldBe 1
    }

    @Test
    fun `repeated force taps collapse into one replacement until physical exit`() = runTest(workerDispatcher) {
        val executor = RecordingExecutor()
        val q = newQueue(executor)
        bind(q)
        executor.hold("p0")
        try {
            q.submitCurrent(session, work("p0"), ReaderTrigger.MANUAL_TAP)
            testScheduler.advanceUntilIdle()
            repeat(20) { q.submitCurrent(session, work("p0"), ReaderTrigger.MANUAL_TAP, force = true) }
            testScheduler.advanceUntilIdle()
            executor.attemptsFor("p0") shouldBe 1
            executor.release("p0")
            testScheduler.advanceUntilIdle()
            executor.attemptsFor("p0") shouldBe 2
            executor.calls.last().force shouldBe true
        } finally {
            executor.release("p0")
        }
    }

    @Test
    fun `close invalidates a resolver install already in progress`() = runTest(workerDispatcher) {
        val executor = RecordingExecutor()
        val q = newQueue(executor)
        bind(q)
        q.updateViewport(session, 0, 1, true) {
            runBlocking<Unit> { q.closeAndDrain().shouldBeInstanceOf<ReaderDrainResult.Completed>() }
            work("p0")
        }
        testScheduler.advanceUntilIdle()
        executor.attemptsFor("p0") shouldBe 0
        q.submitCurrent(session, work("p0"), ReaderTrigger.MANUAL_TAP)
            .shouldBeInstanceOf<ReaderSubmission.Rejected>()
    }

    @Test
    fun `same token rebind gives an empty flow and fences previously handed out flow`() = runTest(workerDispatcher) {
        val executor = RecordingExecutor()
        val q = newQueue(executor)
        bind(q)
        val firstFlow = q.outcomes(session)
        q.submitCurrent(session, work("p0"), ReaderTrigger.MANUAL_TAP)
        testScheduler.advanceUntilIdle()
        firstFlow.value["p0"] shouldBe SinglePageOutcome.Completed
        bind(q)
        val secondFlow = q.outcomes(session)
        secondFlow.value.isEmpty() shouldBe true
        q.submitCurrent(session, work("p1"), ReaderTrigger.MANUAL_TAP)
        testScheduler.advanceUntilIdle()
        firstFlow.value.keys shouldBe setOf("p0")
        secondFlow.value.keys shouldBe setOf("p1")
    }

    @Test
    fun `stale session cancellation never waits for current session workers`() = runTest(workerDispatcher) {
        val executor = RecordingExecutor()
        val q = newQueue(executor)
        bind(q)
        val next = ReaderSessionToken(session.chapterIdentity, 2L)
        q.bindSession(next, manga, chapter, source, translatorComputeClass)
        executor.hold("p0")
        try {
            q.submitCurrent(next, work("p0"), ReaderTrigger.MANUAL_TAP)
            testScheduler.advanceUntilIdle()
            val before = testScheduler.currentTime
            q.cancelSession(session).shouldBeInstanceOf<ReaderDrainResult.Completed>()
            testScheduler.currentTime shouldBe before
            executor.exitedFor("p0") shouldBe 0
        } finally {
            executor.release("p0")
        }
    }

    @Test
    fun `cancelled parent cannot retry or strand a registered lazy worker`() = runTest(workerDispatcher) {
        val deadScope = CoroutineScope(workerDispatcher + SupervisorJob())
        deadScope.cancel()
        val executor = RecordingExecutor()
        val q = ReaderTranslationQueue(executor, deadScope, workerDispatcher, { null }, { testScheduler.currentTime })
        bind(q)
        q.submitCurrent(session, work("p0"), ReaderTrigger.MANUAL_TAP)
        testScheduler.advanceUntilIdle()
        executor.attemptsFor("p0") shouldBe 0
        q.closeAndDrain().shouldBeInstanceOf<ReaderDrainResult.Completed>()
        q.outcomes(session).value["p0"].shouldBeInstanceOf<SinglePageOutcome.Rejected>()
    }

    @Test
    fun `null timestamp pause retries only on the next external viewport event`() = runTest(workerDispatcher) {
        val executor = RecordingExecutor()
        executor.outcomes["p0"] = SinglePageOutcome.Paused()
        val q = newQueue(executor)
        bind(q)
        q.updateViewport(session, 0, 1, true) { work("p0") }
        testScheduler.advanceUntilIdle()
        executor.attemptsFor("p0") shouldBe 1
        testScheduler.advanceUntilIdle()
        executor.attemptsFor("p0") shouldBe 1
        q.updateViewport(session, 0, 1, true) { work("p0") }
        testScheduler.advanceUntilIdle()
        executor.attemptsFor("p0") shouldBe 2
    }

    @Test
    fun `pre-body cancellation closes one correlated trace terminal`() = runTest(workerDispatcher) {
        val diagnostics = eu.kanade.translation.diagnostics.TranslationPipelineDiagnostics
        val previousSink = diagnostics.sink
        val previousGate = diagnostics.detailedTracingEnabled
        val lines = java.util.concurrent.CopyOnWriteArrayList<String>()
        diagnostics.sink = eu.kanade.translation.diagnostics.TranslationTraceSink { _, line -> lines += line }
        diagnostics.detailedTracingEnabled = true
        try {
            val executor = RecordingExecutor()
            val q = newQueue(executor)
            bind(q)
            q.submitCurrent(session, work("p0"), ReaderTrigger.MANUAL_TAP)
            q.cancelPage(session, "p0") shouldBe true
            testScheduler.advanceUntilIdle()
            executor.attemptsFor("p0") shouldBe 0
            lines.count { it.contains("event=run_end ") } shouldBe 1
            lines.count { it.contains("event=schedule_end ") } shouldBe 1
        } finally {
            diagnostics.sink = previousSink
            diagnostics.detailedTracingEnabled = previousGate
        }
    }
}
