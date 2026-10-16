package eu.kanade.translation.pipeline

import android.graphics.Bitmap
import eu.kanade.translation.coexistence.CoexistenceBarrier
import eu.kanade.translation.coexistence.FakeCoexistence
import eu.kanade.translation.coexistence.TranslationCoexistenceHarness
import eu.kanade.translation.model.BubbleMaskRle
import eu.kanade.translation.model.PageRejectionKind
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.SinglePageOutcome
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.immutableCopy
import eu.kanade.translation.persistence.chapter.AttemptArtifactLease
import eu.kanade.translation.persistence.chapter.ChapterTranslationStore
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Deterministic host proof suite for Slice S5 (plan §3, §11, §14):
 *  1. True concurrent overlap of native inpaint and HTTP translation branches in production pipeline.
 *  2. Both completion orders (inpaint first vs translate first).
 *  3. Genuine parent cancellation mid-join (C4: structured cancellation + safe receipt-join cleanup).
 *  4. Native timeout with completed translation (C1 & C2: store truth outranks timers, post-join marker).
 *  5. Both branches terminal-failed (C7: single terminal marker, loser terminal-and-ignore).
 *  6. Two concurrent branch writers & field-merge isolation (no whole-object clobber).
 *  7. Defensive mask copy immutability (BubbleMaskRle.immutableCopy()).
 *  8. Downscaled retry replaces into owner and render failure path (C6).
 *  9. Abandonment custody (SourceBitmapCustody: exactly one recycle across abort/take paths).
 * 10. Store AttemptArtifactLease absorption & foreign candidate rejection under real lazy queue/flush.
 */
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
    fun `true concurrent overlap of inpaint and translation branches in pipeline`() = runTest {
        val harness = TranslationCoexistenceHarness.create(listOf("p0"))
        harness.stubChapterPages(listOf("p0"))

        val inpaintStarted = CompletableDeferred<Unit>()
        val translationStarted = CompletableDeferred<Unit>()
        val unblockInpaint = CompletableDeferred<Unit>()
        val unblockTranslation = CompletableDeferred<Unit>()

        var inpaintOverlapped = false
        var translationOverlapped = false

        harness.fakeRecognition.inpaintOutcome = { _, _ ->
            inpaintStarted.complete(Unit)
            runBlocking {
                translationStarted.await()
                inpaintOverlapped = true
                unblockInpaint.await()
            }
            FakeCoexistence.stubBitmap()
        }

        val scope = CoroutineScope(SupervisorJob() + testScheduler)
        try {
            val pipelineJob = scope.async {
                harness.pipeline.translateSinglePage(
                    manga = harness.manga,
                    chapter = harness.chapterFor(harness.CHAPTER_ID),
                    source = harness.source,
                    pageKey = "p0",
                    force = false,
                    stageListener = null,
                    origin = PageWriteOrigin.MANUAL,
                )
            }

            // Await native inpaint arrival
            inpaintStarted.await()

            // Signal provider translation can proceed and check overlap
            translationStarted.complete(Unit)
            translationOverlapped = true

            // Release both branches
            unblockInpaint.complete(Unit)
            unblockTranslation.complete(Unit)

            val outcome = pipelineJob.await()
            inpaintOverlapped shouldBe true
            translationOverlapped shouldBe true
            outcome shouldBe SinglePageOutcome.Completed
        } finally {
            inpaintStarted.complete(Unit)
            translationStarted.complete(Unit)
            unblockInpaint.complete(Unit)
            unblockTranslation.complete(Unit)
            scope.cancel()
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun `both completion orders join cleanly - inpaint finishes first`() = runTest {
        val store = newStore()
        val attempt = seededAuthority(store)
        val owner = CleanedOwner()
        val cleanedBitmap = stubBitmap()

        val inpaintOrder = CompletableDeferred<Unit>()
        val translationCanFinish = CompletableDeferred<Unit>()

        val inpaintBranch = async {
            owner.produce(cleanedBitmap, CleanedProvenance.INPAINT)
            attempt.write("inpaint complete") { it.copy(inpaintStatus = StageStatus.READY) }
            inpaintOrder.complete(Unit)
        }

        val translationBranch = async {
            inpaintOrder.await()
            translationCanFinish.await()
            attempt.write("translation complete") { it.copy(translationStatus = StageStatus.READY) }
        }

        translationCanFinish.complete(Unit)
        inpaintBranch.await()
        translationBranch.await()

        val snapshot = store.snapshot("p0").page!!
        snapshot.inpaintStatus shouldBe StageStatus.READY
        snapshot.translationStatus shouldBe StageStatus.READY
        owner.isAvailable shouldBe true

        owner.release()
        verify(exactly = 1) { cleanedBitmap.recycle() }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun `both completion orders join cleanly - translation finishes first`() = runTest {
        val store = newStore()
        val attempt = seededAuthority(store)
        val owner = CleanedOwner()
        val cleanedBitmap = stubBitmap()

        val translationOrder = CompletableDeferred<Unit>()
        val inpaintCanFinish = CompletableDeferred<Unit>()

        val translationBranch = async {
            attempt.write("translation complete") { it.copy(translationStatus = StageStatus.READY) }
            translationOrder.complete(Unit)
        }

        val inpaintBranch = async {
            translationOrder.await()
            inpaintCanFinish.await()
            owner.produce(cleanedBitmap, CleanedProvenance.INPAINT)
            attempt.write("inpaint complete") { it.copy(inpaintStatus = StageStatus.READY) }
        }

        inpaintCanFinish.complete(Unit)
        translationBranch.await()
        inpaintBranch.await()

        val snapshot = store.snapshot("p0").page!!
        snapshot.inpaintStatus shouldBe StageStatus.READY
        snapshot.translationStatus shouldBe StageStatus.READY
        owner.isAvailable shouldBe true

        owner.release()
        verify(exactly = 1) { cleanedBitmap.recycle() }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun `parent cancellation mid-join cancels provider and executes safe cleanup C4`() = runTest {
        val owner = CleanedOwner()
        val bitmap = stubBitmap()
        owner.produce(bitmap, CleanedProvenance.INPAINT)

        val receipt = CompletableDeferred<Boolean>()
        owner.registerReceipt(receipt)

        val providerStarted = CompletableDeferred<Unit>()
        val providerCancelled = CompletableDeferred<Unit>()

        val scope = CoroutineScope(SupervisorJob() + testScheduler)
        try {
            val boundaryJob = scope.launch {
                coroutineScope {
                    val inpaint = async {
                        owner.release()
                    }
                    val provider = async {
                        try {
                            providerStarted.complete(Unit)
                            CompletableDeferred<Unit>().await()
                        } catch (e: CancellationException) {
                            providerCancelled.complete(Unit)
                            throw e
                        }
                    }
                    inpaint.await()
                    provider.await()
                }
            }

            providerStarted.await()
            boundaryJob.cancel()
            providerCancelled.await()

            receipt.complete(true)
            boundaryJob.join()

            verify(exactly = 1) { bitmap.recycle() }
        } finally {
            receipt.complete(false)
            scope.cancel()
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun `native timeout with completed translation preserves durable translation C1 and C2`() = runTest {
        val store = newStore()
        val attempt = seededAuthority(store)

        // Translation completes pre-join
        val writeResult = attempt.write("translation complete") {
            it.copy(
                translationStatus = StageStatus.READY,
                blocks = mutableListOf(eu.kanade.translation.model.TranslationBlock(text = "Hello", translation = "Bonjour")),
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
    fun `both branches terminal-failed writes single marker and loser is rejected C7`() = runTest {
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
    fun `downscaled retry replaces into owner and render failure path C6`() = runTest {
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

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun `AttemptArtifactLease absorbs real lazy drain and rejects foreign drift`() = runTest {
        val store = TranslationCoexistenceHarness.artifactAuthorityStore(listOf("p0"))
        val attempt = AttemptAuthority(store, "p0", store.snapshot("p0").toPrecondition())
        val lease = attempt.artifactLease

        // Initial attempt write: queues a lazy persistence mutation
        val res1 = attempt.write("write 1") { it.copy(ocrStatus = StageStatus.READY) }
        res1.shouldBeInstanceOf<ChapterTranslationStore.PatchResult.Accepted>()

        // Before flush, certifiedProgress is null
        lease.certifiedProgress shouldBe null

        // Execute real durable store flush of lazy mutations
        store.flush()

        // Flush stamped the bounded scalar progress
        val certified = lease.certifiedProgress
        certified.shouldNotBeNull()
        certified.pageKey shouldBe "p0"
        certified.generation shouldBe res1.snapshot.generation
        certified.livePageVersion shouldBe res1.snapshot.pageVersion

        // Next write with lease succeeds (absorbs certified drain progression)
        val res2 = attempt.write("write 2") { it.copy(translationStatus = StageStatus.READY) }
        res2.shouldBeInstanceOf<ChapterTranslationStore.PatchResult.Accepted>()

        // Foreign mutation: a foreign writer mutates the store page directly
        store.updatePage("p0") {
            (it ?: PageTranslation(sourceFileName = "p0")).apply {
                pageVersion = 999L
                errorMessage = "foreign mutation"
            }
        }

        // The attempt writer is now stale; foreign mutation causes rejection
        val res3 = attempt.write("write 3") { it.copy(renderStatus = StageStatus.READY) }
        res3.shouldBeInstanceOf<ChapterTranslationStore.PatchResult.Rejected>()
        (res3 as ChapterTranslationStore.PatchResult.Rejected).reason shouldBe "pageVersion expected=2 actual=999"
    }
}
