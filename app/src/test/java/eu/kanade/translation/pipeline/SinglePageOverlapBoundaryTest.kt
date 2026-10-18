package eu.kanade.translation.pipeline

import eu.kanade.translation.model.toDraft

import android.graphics.Bitmap
import eu.kanade.translation.coexistence.CoexistenceBarrier
import eu.kanade.translation.coexistence.FakeCoexistence
import eu.kanade.translation.coexistence.TranslationCoexistenceHarness
import eu.kanade.translation.model.BubbleMaskRle
import eu.kanade.translation.model.InpaintMaskBox
import eu.kanade.translation.model.PageDisplayState
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.SinglePageOutcome
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.immutableCopy
import eu.kanade.translation.model.isTextlessTerminal
import eu.kanade.translation.persistence.artifact.AtomicChapterDocuments
import eu.kanade.translation.persistence.artifact.ChapterArtifactEngine
import eu.kanade.translation.persistence.artifact.ChapterArtifactLayout
import eu.kanade.translation.persistence.artifact.ChapterArtifactManifest
import eu.kanade.translation.persistence.artifact.FakeChapterDocumentIo
import eu.kanade.translation.persistence.artifact.PageArtifactRecord
import eu.kanade.translation.persistence.chapter.ChapterTranslationStore
import eu.kanade.translation.persistence.chapter.PageWriteOrigin
import eu.kanade.translation.pipeline.execution.TranslationStageEvent
import eu.kanade.translation.pipeline.execution.TranslationStageListener
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/** Pipeline overlap, cancellation, durability, and custody regressions. */
class SinglePageOverlapBoundaryTest {

    private fun stubBitmap(): Bitmap = mockk(relaxed = true)

    private fun newStore(): ChapterTranslationStore = ChapterTranslationStore(
        artifactParentResolver = null,
        initialPages = emptyMap(),
    )

    private suspend fun seededAuthority(
        store: ChapterTranslationStore,
        pageKey: String = "p0",
        callerJob: Job? = null,
    ): AttemptAuthority {
        store.updatePage(pageKey) { PageTranslation(sourceFileName = pageKey) }
        return AttemptAuthority(store, pageKey, store.snapshot(pageKey).toPrecondition(), callerJob)
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun `real provider and inpaint both arrive before either completes`() = runBlocking<Unit> {
        withTimeout(TranslationCoexistenceHarness.AWAIT_TIMEOUT_MS) { assertProductionOverlap(first = null) }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun `production render waits when inpaint finishes first`() = runBlocking<Unit> {
        withTimeout(TranslationCoexistenceHarness.AWAIT_TIMEOUT_MS) {
            assertProductionOverlap(CoexistenceBarrier.BarrierPoint.INPAINT)
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun `production render waits when translation finishes first`() = runBlocking<Unit> {
        withTimeout(TranslationCoexistenceHarness.AWAIT_TIMEOUT_MS) {
            assertProductionOverlap(CoexistenceBarrier.BarrierPoint.PROVIDER_START)
        }
    }

    private suspend fun assertProductionOverlap(first: CoexistenceBarrier.BarrierPoint?) = coroutineScope {
        val harness = TranslationCoexistenceHarness.create(listOf("p0"), preRegisterInStore = false)
        val barrier = harness.barrier
        val renderEntered = CompletableDeferred<Unit>()
        var pipelineJob: kotlinx.coroutines.Deferred<SinglePageOutcome>? = null
        try {
            harness.installGraphicsShims()
            harness.stubChapterPages(listOf("p0"))
            barrier.arm(CoexistenceBarrier.BarrierPoint.INPAINT)
            barrier.arm(CoexistenceBarrier.BarrierPoint.PROVIDER_START, "p0")
            pipelineJob = async(Dispatchers.Default) {
                harness.pipeline.translateSinglePage(
                    harness.manga,
                    harness.chapterFor(harness.CHAPTER_ID),
                    harness.source,
                    "p0",
                    force = false,
                    stageListener = TranslationStageListener { _, stage ->
                        if (stage == TranslationStageEvent.RENDERING) renderEntered.complete(Unit)
                    },
                    origin = PageWriteOrigin.MANUAL,
                )
            }
            barrier.awaitArrival(CoexistenceBarrier.BarrierPoint.INPAINT)
            barrier.awaitArrival(CoexistenceBarrier.BarrierPoint.PROVIDER_START, "p0")
            harness.fakeTransport.callsFor("p0") shouldBe 1
            harness.fakeRecognition.inpaintCalls.get() shouldBe 1
            renderEntered.isCompleted shouldBe false
            pipelineJob.isCompleted shouldBe false

            if (first != null) {
                barrier.release(first) shouldBe true
                harness.store.state.first { pages ->
                    val page = pages["p0"]
                    if (first == CoexistenceBarrier.BarrierPoint.INPAINT) {
                        page?.inpaintStatus == StageStatus.READY
                    } else {
                        page?.translationStatus == StageStatus.READY
                    }
                }
                renderEntered.isCompleted shouldBe false
                pipelineJob.isCompleted shouldBe false
            }
            barrier.disarm(CoexistenceBarrier.BarrierPoint.INPAINT)
            barrier.disarm(CoexistenceBarrier.BarrierPoint.PROVIDER_START)
            pipelineJob.await() shouldBe SinglePageOutcome.Completed
            renderEntered.isCompleted shouldBe true
            harness.store.state.value.getValue("p0").renderStatus shouldBe StageStatus.READY
            harness.fakeTransport.callsFor("p0") shouldBe 1
        } finally {
            barrier.disarm(CoexistenceBarrier.BarrierPoint.INPAINT)
            barrier.disarm(CoexistenceBarrier.BarrierPoint.PROVIDER_START)
            withContext(NonCancellable) { pipelineJob?.cancelAndJoin() }
            harness.close()
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun `caller cancellation cancels real provider and waits for native cleanup`() = runBlocking<Unit> {
        assertCallerCancellation(throughScheduler = false)
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun `scheduler cancellation uses boundary cleanup without a late snapshot reset`() = runBlocking<Unit> {
        assertCallerCancellation(throughScheduler = true)
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun `healthy caller propagates inner cancellation without a terminal marker`() = runBlocking<Unit> {
        val harness = TranslationCoexistenceHarness.create(listOf("p0"), preRegisterInStore = false)
        val inner = CancellationException("inner engine cancellation")
        var before: ChapterTranslationStore.PageSnapshot? = null
        try {
            harness.installGraphicsShims()
            harness.stubChapterPages(listOf("p0"))
            harness.fakeRecognition.analyzeOutcome = {
                before = runBlocking { harness.store.snapshot("p0") }
                throw inner
            }
            val caught = runCatching {
                harness.pipeline.translateSinglePage(
                    harness.manga,
                    harness.chapterFor(harness.CHAPTER_ID),
                    harness.source,
                    "p0",
                    force = false,
                    origin = PageWriteOrigin.MANUAL,
                )
            }.exceptionOrNull()
            caught shouldBe inner
            currentCoroutineContext().isActive shouldBe true
            val after = harness.store.snapshot("p0")
            val captured = before.shouldNotBeNull()
            after.pageVersion shouldBe captured.pageVersion
            after.page shouldBe captured.page
        } finally {
            harness.close()
        }
    }

    private suspend fun assertCallerCancellation(throughScheduler: Boolean) = coroutineScope {
        withTimeout(TranslationCoexistenceHarness.AWAIT_TIMEOUT_MS) {
            val harness = TranslationCoexistenceHarness.create(listOf("p0"), preRegisterInStore = false)
            val cleaned = stubBitmap()
            var pipelineJob: Job? = null
            val diagnostics = eu.kanade.translation.diagnostics.TranslationPipelineDiagnostics
            val previousSink = diagnostics.sink
            val previousGate = diagnostics.detailedTracingEnabled
            val lines = java.util.concurrent.CopyOnWriteArrayList<String>()
            if (throughScheduler) {
                diagnostics.sink = eu.kanade.translation.diagnostics.TranslationTraceSink { _, line -> lines += line }
                diagnostics.detailedTracingEnabled = true
            }
            try {
                harness.installGraphicsShims()
                harness.stubChapterPages(listOf("p0"))
                harness.fakeRecognition.inpaintOutcome = { _, _ -> cleaned }
                harness.barrier.arm(CoexistenceBarrier.BarrierPoint.INPAINT)
                harness.barrier.arm(CoexistenceBarrier.BarrierPoint.PROVIDER_START, "p0")
                pipelineJob = if (throughScheduler) {
                    harness.registerReaderStream(harness.CHAPTER_ID, "p0")
                    harness.tapManual("p0")
                    harness.capturedManualJob("p0")
                } else {
                    async(Dispatchers.Default) {
                        harness.pipeline.translateSinglePage(
                            harness.manga,
                            harness.chapterFor(harness.CHAPTER_ID),
                            harness.source,
                            "p0",
                            force = false,
                            stageListener = null,
                            origin = PageWriteOrigin.MANUAL,
                        )
                    }
                }
                harness.barrier.awaitArrival(CoexistenceBarrier.BarrierPoint.INPAINT)
                harness.barrier.awaitArrival(CoexistenceBarrier.BarrierPoint.PROVIDER_START, "p0")
                harness.fakeTransport.callsFor("p0") shouldBe 1
                val inpaintBeforeCancel = harness.store.state.value.getValue("p0").inpaintStatus
                inpaintBeforeCancel shouldBe StageStatus.PENDING
                if (throughScheduler) {
                    // Real scheduler cancel entry: queue-owned force-cancel of the
                    // explicit manual demand (the retired per-operation job handle
                    // has no replacement by design).
                    harness.scheduler.cancelPageTranslation(harness.CHAPTER_ID, "p0") shouldBe true
                } else {
                    pipelineJob.cancel()
                }
                // Quarantine owns parked native call until worker exits.
                pipelineJob.isCompleted shouldBe false
                verify(exactly = 0) { cleaned.recycle() }
                harness.barrier.disarm(CoexistenceBarrier.BarrierPoint.INPAINT)
                if (throughScheduler) {
                    // Queue force-cancel publishes no typed outcome for explicit
                    // demands; the attempt-fenced cleanup's CANCELLED store write
                    // is the terminal marker, and run_end lands in the runner's
                    // teardown after it.
                    while (harness.store.state.value.getValue("p0").translationStatus != StageStatus.CANCELLED) {
                        delay(25)
                    }
                    while (lines.count { it.contains("event=run_end ") && it.contains("outcome=cancelled") } == 0) {
                        delay(25)
                    }
                } else {
                    pipelineJob.join()
                }
                val cancelledPage = harness.store.state.value.getValue("p0")
                cancelledPage.translationStatus shouldBe StageStatus.CANCELLED
                cancelledPage.inpaintStatus shouldBe inpaintBeforeCancel
                cancelledPage.ocrError shouldBe null
                if (throughScheduler) {
                    lines.count { it.contains("event=run_end ") && it.contains("outcome=cancelled") } shouldBe 1
                }
                harness.transportShared.cancelledCalls.get() shouldBe 1
                verify(exactly = 1) { cleaned.recycle() }
                harness.store.state.value.getValue("p0").renderStatus shouldNotBe StageStatus.READY
            } finally {
                harness.barrier.disarm(CoexistenceBarrier.BarrierPoint.INPAINT)
                harness.barrier.disarm(CoexistenceBarrier.BarrierPoint.PROVIDER_START)
                withContext(NonCancellable) { pipelineJob?.cancelAndJoin() }
                harness.close()
                diagnostics.sink = previousSink
                diagnostics.detailedTracingEnabled = previousGate
            }
        }
    }

    @Test
    fun `masked textless reader cleans without provider call and reaches durable terminal`() = runBlocking<Unit> {
        withTimeout(TranslationCoexistenceHarness.AWAIT_TIMEOUT_MS) { assertTextless(hasMask = true) }
    }

    @Test
    fun `unmasked textless page needs no cleaned worker and reaches durable terminal`() = runBlocking<Unit> {
        withTimeout(TranslationCoexistenceHarness.AWAIT_TIMEOUT_MS) { assertTextless(hasMask = false) }
    }

    @Test
    fun `masked textless reader inpaint soft null fails attempt-fenced without provider calls`() = runBlocking<Unit> {
        withTimeout(TranslationCoexistenceHarness.AWAIT_TIMEOUT_MS) { assertMaskedTextlessSoftNullFailure() }
    }

    private suspend fun assertMaskedTextlessSoftNullFailure() {
        val store = TranslationCoexistenceHarness.artifactAuthorityStore(listOf("p0"), preRegisterInStore = false)
        val harness = TranslationCoexistenceHarness.create(listOf("p0"), storeOverride = store)
        val source = FakeCoexistence.stubBitmap()
        try {
            harness.installGraphicsShims()
            harness.stubChapterPages(listOf("p0"))
            coEvery { PageDecode.decodePageBitmapForTranslation(any(), any(), any(), any(), any()) } returns
                FakeCoexistence.decodedPage("p0").copy(bitmap = source)
            harness.fakeRecognition.analyzeOutcome = {
                PageTranslation(
                    ocrStatus = StageStatus.READY,
                    inpaintMaskBoxes = listOf(InpaintMaskBox(0, 0, 10, 10, 1)),
                )
            }
            harness.fakeRecognition.inpaintOutcome = { _, _ -> null }
            val outcome = harness.pipeline.translateSinglePage(
                harness.manga,
                harness.chapterFor(harness.CHAPTER_ID),
                harness.source,
                "p0",
                force = false,
                stageListener = null,
                origin = PageWriteOrigin.MANUAL,
            )
            store.flush()
            harness.fakeRecognition.analyzeCalls.get() shouldBe 1
            harness.fakeRecognition.inpaintCalls.get() shouldBe 1
            outcome shouldBe SinglePageOutcome.Failed("p0", "Masked textless inpainting produced no cleaned image")
            val page = store.snapshot("p0").page.shouldNotBeNull()
            page.ocrStatus shouldBe StageStatus.READY
            page.inpaintStatus shouldBe StageStatus.FAILED
            page.inpaintError shouldBe "Masked textless inpainting produced no cleaned image"
            harness.fakeTransport.totalCalls() shouldBe 0
            verify(exactly = 1) { source.recycle() }
            store.pageLeaseOwner("p0") shouldBe null
        } finally {
            try {
                harness.close()
            } finally {
                store.closeAndFlush()
            }
        }
    }

    private suspend fun assertTextless(hasMask: Boolean = true) {
        val store = TranslationCoexistenceHarness.artifactAuthorityStore(listOf("p0"), preRegisterInStore = false)
        val harness = TranslationCoexistenceHarness.create(listOf("p0"), storeOverride = store)
        val source = FakeCoexistence.stubBitmap()
        try {
            harness.installGraphicsShims()
            harness.stubChapterPages(listOf("p0"))
            coEvery { PageDecode.decodePageBitmapForTranslation(any(), any(), any(), any(), any()) } returns
                FakeCoexistence.decodedPage("p0").copy(bitmap = source)
            harness.fakeRecognition.analyzeOutcome = {
                PageTranslation(
                    ocrStatus = StageStatus.READY,
                    inpaintMaskBoxes = if (hasMask) listOf(InpaintMaskBox(0, 0, 10, 10, 1)) else emptyList(),
                )
            }
            harness.fakeRecognition.inpaintOutcome = { bitmap, _ -> bitmap }
            val readerOutcome = harness.pipeline.translateSinglePage(
                harness.manga,
                harness.chapterFor(harness.CHAPTER_ID),
                harness.source,
                "p0",
                force = false,
                stageListener = null,
                origin = PageWriteOrigin.MANUAL,
            )
            store.flush()
            harness.fakeRecognition.analyzeCalls.get() shouldBe 1
            val page = store.snapshot("p0").page
            if (page == null || page.ocrStatus != StageStatus.READY) {
                throw AssertionError(
                    "textless OCR result was not published: " + harness.failureDiagnostics(listOf("p0")),
                )
            }
            readerOutcome shouldBe SinglePageOutcome.Completed
            page.inpaintStatus shouldBe if (hasMask) StageStatus.READY else StageStatus.SKIPPED
            page.isTextlessTerminal shouldBe true
            harness.fakeRecognition.inpaintCalls.get() shouldBe if (hasMask) 1 else 0
            harness.barrier.arrivalsOf(CoexistenceBarrier.BarrierPoint.INPAINT) shouldBe if (hasMask) 1 else 0
            harness.fakeTransport.totalCalls() shouldBe 0
            store.artifactEngine!!.readManifest()!!.pages.getValue("p0").displayState shouldBe
                PageDisplayState.TEXTLESS_COMPLETE
            verify(exactly = 1) { source.recycle() }
            store.pageLeaseOwner("p0") shouldBe null
        } finally {
            try {
                harness.close()
            } finally {
                store.closeAndFlush()
            }
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun `authority timeout marker preserves an already accepted translation`() = runTest {
        val store = newStore()
        val attempt = seededAuthority(store)

        // Translation completes pre-join
        val writeResult = attempt.write("translation complete") {
            it.copy(
                translationStatus = StageStatus.READY,
                blocks = mutableListOf(FakeCoexistence.textBlock("Hello").copy(translation = "Bonjour")),
            )
        }
        writeResult.shouldBeInstanceOf<ChapterTranslationStore.PatchResult.Accepted>()

        // Post-join native timeout marker
        val timeoutResult = attempt.markTimeout { current ->
            val msg = PageStoreWriter.timeoutFailureMessage(nativeTimer = true)
            current.apply {
                inpaintStatus = StageStatus.FAILED
                inpaintError = msg
                // Translation is already READY: store truth outranks timers, translationStatus NOT failed!
                if (translationStatus != StageStatus.READY && translationStatus != StageStatus.PARTIAL) {
                    translationStatus = StageStatus.FAILED
                    translationError = msg
                }
                renderStatus = StageStatus.FAILED
                renderError = msg
                errorMessage = msg
                updatedAt = System.currentTimeMillis()
            }
        }
        timeoutResult.shouldBeInstanceOf<ChapterTranslationStore.PatchResult.Accepted>()

        val finalPage = store.snapshot("p0").page!!
        finalPage.translationStatus shouldBe StageStatus.READY
        finalPage.inpaintStatus shouldBe StageStatus.FAILED
        finalPage.renderStatus shouldBe StageStatus.FAILED
        finalPage.blocks.first().translation shouldBe "Bonjour"
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun `authority accepts one terminal marker and rejects the next`() = runTest {
        val store = newStore()
        val attempt = seededAuthority(store)

        val firstMarker = attempt.markTimeout { current ->
            current.apply {
                inpaintStatus = StageStatus.FAILED
                errorMessage = "native timeout"
            }
        }
        firstMarker.shouldBeInstanceOf<ChapterTranslationStore.PatchResult.Accepted>()

        // Losing marker (e.g. HTTP timeout or provider failure)
        val secondMarker = attempt.markTimeout { current ->
            current.apply {
                translationStatus = StageStatus.FAILED
                errorMessage = "HTTP timeout"
            }
        }
        secondMarker.shouldBeInstanceOf<ChapterTranslationStore.PatchResult.Rejected>()
        (secondMarker as ChapterTranslationStore.PatchResult.Rejected).reason shouldBe "attempt already closed"

        val page = store.snapshot("p0").page!!
        page.errorMessage shouldBe "native timeout"
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun `two concurrent branch writers isolate fields without clobbering`() = runTest {
        val store = newStore()
        val attempt = seededAuthority(store)

        val inpaintWrite = async {
            attempt.write("inpaint result") { current ->
                current.apply {
                    inpaintStatus = StageStatus.READY
                    inpaintFingerprint = "inpaint_fp_123"
                }
            }
        }

        val translationWrite = async {
            attempt.write("translation result") { current ->
                current.apply {
                    translationStatus = StageStatus.READY
                    translationFingerprint = "trans_fp_456"
                }
            }
        }

        inpaintWrite.await().shouldBeInstanceOf<ChapterTranslationStore.PatchResult.Accepted>()
        translationWrite.await().shouldBeInstanceOf<ChapterTranslationStore.PatchResult.Accepted>()

        val merged = store.snapshot("p0").page!!
        merged.inpaintStatus shouldBe StageStatus.READY
        merged.inpaintFingerprint shouldBe "inpaint_fp_123"
        merged.translationStatus shouldBe StageStatus.READY
        merged.translationFingerprint shouldBe "trans_fp_456"
    }

    @Test
    fun `defensive mask copy creates independent copy`() {
        val originalMask = BubbleMaskRle(
            width = 10,
            height = 10,
            bounds = listOf(0, 0, 10, 10),
            runs = listOf(0, 10),
            score = 0.95f,
        )

        val copy = originalMask.immutableCopy()

        copy.width shouldBe originalMask.width
        copy.height shouldBe originalMask.height
        copy.bounds shouldBe originalMask.bounds
        copy.runs shouldBe originalMask.runs
        copy.score shouldBe originalMask.score

        // Distinct instance verification
        (copy !== originalMask) shouldBe true
        (copy.bounds !== originalMask.bounds) shouldBe true
        (copy.runs !== originalMask.runs) shouldBe true
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun `retry provenance owner borrows and recycles once`() = runTest {
        val owner = CleanedOwner()
        owner.isAvailable shouldBe false

        // Inpaint soft-failed (null cleaned bitmap) -> retry succeeds:
        val retriedBitmap = stubBitmap()
        owner.replace(retriedBitmap, CleanedProvenance.RETRY)

        owner.isAvailable shouldBe true
        owner.owns(retriedBitmap) shouldBe true

        val borrowed = owner.borrow()
        borrowed shouldBe retriedBitmap
        owner.closeBorrow()

        owner.release()
        verify(exactly = 1) { retriedBitmap.recycle() }
    }

    @Test
    fun `abandonment custody recycles source bitmap if never taken`() {
        val custody = SourceBitmapCustody()
        val sourceBitmap = stubBitmap()

        custody.register(sourceBitmap)
        verify(exactly = 0) { sourceBitmap.recycle() }

        // Abort before Segment 2: close recycles exactly once
        custody.close()
        verify(exactly = 1) { sourceBitmap.recycle() }

        // Second close is no-op
        custody.close()
        verify(exactly = 1) { sourceBitmap.recycle() }
    }

    @Test
    fun `abandonment custody does not recycle if taken by producer`() {
        val custody = SourceBitmapCustody()
        val sourceBitmap = stubBitmap()

        custody.register(sourceBitmap)
        val taken = custody.take()
        taken shouldBe sourceBitmap

        // Taken by producer: boundary close does not recycle
        custody.close()
        verify(exactly = 0) { sourceBitmap.recycle() }
    }

    private fun lazyStore(
        scheduler: TestCoroutineScheduler,
        io: FakeChapterDocumentIo = FakeChapterDocumentIo().apply { fileBacked = true },
    ): ChapterTranslationStore {
        val layout = ChapterArtifactLayout("s5-attempt-lease")
        val documents = AtomicChapterDocuments(io)
        val manifest = ChapterArtifactManifest(
            chapterKey = layout.chapterKey,
            pages = mapOf("p0" to PageArtifactRecord(pageKey = "p0")),
        )
        check(documents.publishJson(layout.manifestFileName, manifest))
        return ChapterTranslationStore(
            artifactFileName = "${layout.chapterKey}.json",
            initialPages = mapOf("p0" to PageTranslation(sourceFileName = "p0")),
            artifactStore = ChapterArtifactEngine(documents, layout),
            initialArtifactManifest = manifest,
            persistenceDispatcher = StandardTestDispatcher(scheduler),
        ).also { it.enableLazyPersistence() }
    }

    private suspend fun publishOcr(attempt: AttemptAuthority): ChapterTranslationStore.PatchResult.Accepted =
        attempt.write("OCR result") {
            it.copy(
                ocrStatus = StageStatus.READY,
                blocks = mutableListOf(FakeCoexistence.textBlock("source")),
            )
        }.shouldBeInstanceOf<ChapterTranslationStore.PatchResult.Accepted>()

    @Test
    fun `lease absorbs real lazy drain then rejects artifact-only foreign cancellation`() = runTest {
        val io = FakeChapterDocumentIo().apply { fileBacked = true }
        val store = lazyStore(testScheduler, io)
        try {
            val attempt = AttemptAuthority(store, "p0", store.snapshot("p0").toPrecondition())
            val beforeFiles = io.files.toMap()
            val first = publishOcr(attempt)
            attempt.artifactLease.certifiedProgress shouldBe null
            store.mutex.withLock { store.hasPendingLazyPersistenceLocked() } shouldBe true
            io.files shouldBe beforeFiles

            store.flush()
            val certified = attempt.artifactLease.certifiedProgress.shouldNotBeNull()
            certified.livePageVersion shouldBe first.snapshot.pageVersion
            certified.candidateGenerationId.shouldNotBeNull()
            store.artifactEngine!!.readManifest()!!.pages.getValue("p0").candidate.shouldNotBeNull()
            store.snapshot("p0").pageVersion shouldBe first.snapshot.pageVersion

            attempt.write("after own drain") { it.copy(translationStatus = StageStatus.RUNNING) }
                .shouldBeInstanceOf<ChapterTranslationStore.PatchResult.Accepted>()
            val beforeForeign = store.snapshot("p0")
            // Cancellation changes artifact identity without advancing live pageVersion.
            store.mutex.withLock { store.cancelArtifactCandidateLocked("p0") } shouldBe true
            val afterForeign = store.snapshot("p0")
            afterForeign.pageVersion shouldBe beforeForeign.pageVersion
            afterForeign.artifactPageVersion shouldNotBe beforeForeign.artifactPageVersion
            afterForeign.candidateGenerationId shouldBe null
            val rejected = attempt.write("after foreign artifact cancellation") { it.copy(renderStatus = StageStatus.READY) }
                .shouldBeInstanceOf<ChapterTranslationStore.PatchResult.Rejected>()
            rejected.reason shouldBe "artifact pageVersion changed"
            store.snapshot("p0").pageVersion shouldBe beforeForeign.pageVersion
        } finally {
            store.closeAndFlush()
        }
    }

    @Test
    fun `lease cannot certify or absorb another store with identical scalar identity`() = runTest {
        val first = lazyStore(testScheduler)
        var second: ChapterTranslationStore? = null
        try {
            val attempt = AttemptAuthority(first, "p0", first.snapshot("p0").toPrecondition())
            publishOcr(attempt)
            first.flush()
            // Equal scalar fences must not transfer store-bound lease capability.
            val other = ChapterTranslationStore(
                artifactFileName = "s5-attempt-lease.json",
                artifactStore = first.artifactEngine,
                initialArtifactManifest = first.artifactManifest,
                initialPages = first.state.value.mapValues { it.value.toDraft() },
                persistenceDispatcher = StandardTestDispatcher(testScheduler),
            )
            second = other
            val lease = attempt.artifactLease
            val certified = lease.certifiedProgress.shouldNotBeNull()
            val otherSnapshot = other.snapshot("p0")
            otherSnapshot.pageVersion shouldBe certified.livePageVersion
            otherSnapshot.artifactPageVersion shouldBe certified.artifactPageVersion
            otherSnapshot.candidateGenerationId shouldBe certified.candidateGenerationId
            lease.stamp(other, "p0", otherSnapshot.copy(pageVersion = otherSnapshot.pageVersion + 1))
            lease.certifiedProgress shouldBe certified
            val rejected = other.patchPage(
                "p0",
                attempt.precondition,
                "foreign store lease",
                lease,
            ) { (it ?: PageTranslation()).copy(renderStatus = StageStatus.READY) }
                .shouldBeInstanceOf<ChapterTranslationStore.PatchResult.Rejected>()
            rejected.reason shouldBe "artifact pageVersion changed"
        } finally {
            try {
                first.closeAndFlush()
            } finally {
                second?.closeAndFlush()
            }
        }
    }

    @Test
    fun `coalesced own writes certify latest live version only after successful drain`() = runTest {
        val io = FakeChapterDocumentIo().apply { fileBacked = true }
        val store = lazyStore(testScheduler, io)
        try {
            val attempt = AttemptAuthority(store, "p0", store.snapshot("p0").toPrecondition())
            publishOcr(attempt)
            val latest = attempt.write("coalesced OCR update") { current ->
                current.apply { blocks.first().text = "latest source" }
            }.shouldBeInstanceOf<ChapterTranslationStore.PatchResult.Accepted>()
            io.failWrites = true
            store.flush()
            attempt.artifactLease.certifiedProgress shouldBe null
            store.mutex.withLock { store.hasPendingLazyPersistenceLocked() } shouldBe true
            io.failWrites = false
            store.flush()
            attempt.artifactLease.certifiedProgress.shouldNotBeNull().livePageVersion shouldBe latest.snapshot.pageVersion
            attempt.write("after retried own drain") { it.copy(translationStatus = StageStatus.RUNNING) }
                .shouldBeInstanceOf<ChapterTranslationStore.PatchResult.Accepted>()
        } finally {
            io.failWrites = false
            store.closeAndFlush()
        }
    }
}
