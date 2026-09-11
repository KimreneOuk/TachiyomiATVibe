package eu.kanade.translation.coexistence

import eu.kanade.translation.model.BatchExpectedFingerprints
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationBatchPhase
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.pipeline.PageDecode
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

/**
 * T918 — batch retry affordance, pipeline leg (the exact ChapterTranslator.kt
 * CancellationException scenario: the queue entry is already gone when the
 * batch worker is cancelled mid-run).
 *
 * Contract pinned here (the facts the sheet Retry affordance and the screen
 * reconciliation rely on):
 *  1. the cancellation lands as a TYPED terminal aborted snapshot in the
 *     tracker registry ("Batch cancelled") — the honest observable the UI keys
 *     its restart affordance and its stranded-state reconciliation on;
 *  2. no queue entry survives the cancellation and the removed entry's own
 *     status settles restartable (NOT_TRANSLATED);
 *  3. a subsequent batch start SUCCEEDS and reuses completed work: paid
 *     transport calls run only for the remainder (the completed page gets no
 *     second call; the interrupted page gets exactly one retry), and no page
 *     is re-decoded.
 *
 * Choreography determinism (harness note §2): page 1's paid call parks at
 * PROVIDER_START AFTER it started (the counter increments before the park), so
 * the cancellation cuts REAL paid work with page 1's native invocation already
 * exited (a native invocation parked on a signal only a later stage can fire
 * is uncancellable by design — the NativeRunQuarantine NonCancellable drain —
 * and is not this defect's scenario). The queue entry is removed through the
 * REAL removeFromQueue path, then the batch job is cancelled at the parked
 * suspension point. No sleeps, no polling — barrier gates and bounded
 * state.first{} awaits only.
 *
 * T924 zero-legacy (D1): the fixture moved to the durable ARTIFACTS-authority
 * store, so run 1's work products (OCR checkpoints + committed translation
 * work products) are genuinely durable sidecars and the former re-seed
 * deviation is DELETED — the restart consults the real artifacts. The lane is
 * translation-terminal without an in-pass render, so the terminal render
 * expectations became translation-terminal ones. The cancellation itself, the
 * terminal snapshot, the queue settlement, and the restart's work routing are
 * all the REAL graph.
 */
class T918CancelledBatchRestartTest {

    companion object {
        private const val AWAIT_TIMEOUT_MS = TranslationCoexistenceHarness.AWAIT_TIMEOUT_MS
        private const val CHAPTER_ID = TranslationCoexistenceHarness.CHAPTER_ID

        /** The real engine identity the harness graph runs under (D10 recipe). */
        private fun expectedFingerprints(): BatchExpectedFingerprints {
            val probe = TranslationCoexistenceHarness.create(listOf("p0"))
            try {
                val lane = probe.engineLane
                val signature = lane.currentTranslatorSignature
                return PageDecode.batchExpectedFingerprints(
                    signature,
                    lane.currentOcrModel,
                    lane.currentReadingOrder,
                    lane.currentInpaintingMode,
                    signature.fromLang,
                    signature.toLang,
                )
            } finally {
                probe.close()
            }
        }

        /** The source fingerprint the REAL batch computes for the fixture stream (D10 recipe). */
        private suspend fun realSourceFingerprint(pageKey: String): String = checkNotNull(
            PageDecode.computeSourceFingerprint {
                ByteArrayInputStream("page-$pageKey".toByteArray())
            },
        ) { "T918 fixture: computeSourceFingerprint returned null" }
    }

    @Test
    fun `cancelled batch restarts from completed work with only the remainder re-run`() = runBlocking<Unit> {
        // cleanedImagesOnDisk models the DURABLE cleaned images run 1 persisted
        // (document IO is a sanctioned fake seam): the resume gate's
        // physical-presence check consults it (D10 precedent).
        // T924 zero-legacy (D1): the batch requires artifact authority — the
        // durable ARTIFACTS-authority store is also what makes run 1's work
        // products (OCR checkpoints + candidate work products) genuinely
        // resumable, so the legacy re-seed deviation is gone.
        val harness = TranslationCoexistenceHarness.create(
            listOf("p0", "p1"),
            storeOverride = TranslationCoexistenceHarness.artifactAuthorityStore(listOf("p0", "p1")),
            cleanedImagesOnDisk = setOf("p0.cleaned.jpg", "p1.cleaned.jpg"),
        )
        harness.installGraphicsShims()
        harness.stubChapterPages(listOf("p0", "p1"))
        try {
            // Queue membership mirrors the production mid-run entry. The public
            // queueState is a read-only projection; the private backing flow is
            // the SAME instance the CancellationException branch reads, so it is
            // mutated through reflection (harness setField precedent).
            val queueEntry = Translation(harness.source, harness.manga, harness.chapterFor(CHAPTER_ID))
                .apply { status = Translation.State.TRANSLATING }
            val backingQueue = harness.translator.javaClass.getDeclaredField("_queueState")
                .apply { isAccessible = true }
                .get(harness.translator) as MutableStateFlow<List<Translation>>
            backingQueue.value = listOf(queueEntry)

            // Park page 1's paid call AFTER it started (the counter increments
            // before the park), so the cancellation cuts REAL paid work.
            harness.barrier.arm(CoexistenceBarrier.BarrierPoint.PROVIDER_START, "p1")
            val batch = harness.launchBatch(pageKeys = listOf("p0", "p1"))
            harness.barrier.awaitArrivalWithin(
                CoexistenceBarrier.BarrierPoint.PROVIDER_START,
                "p1",
                AWAIT_TIMEOUT_MS,
            )
            // Event-driven: page 0's paid work settled before the cancel (its
            // render joins only at whole-chunk settlement).
            withTimeout(AWAIT_TIMEOUT_MS) {
                harness.store.state.first { state ->
                    state.getValue("p0").translationStatus == StageStatus.READY &&
                        state.getValue("p0").inpaintStatus == StageStatus.READY
                }
            }
            val decodeP0BeforeRestart =
                harness.barrier.arrivalsOf(CoexistenceBarrier.BarrierPoint.NATIVE_ACQUIRE, "p0")
            val decodeP1BeforeRestart =
                harness.barrier.arrivalsOf(CoexistenceBarrier.BarrierPoint.NATIVE_ACQUIRE, "p1")
            withClue("fixture precondition: page 0 completed with exactly one paid call") {
                harness.transportCallsFor("p0") shouldBe 1
            }
            withClue("fixture precondition: page 1's interrupted paid call started exactly once") {
                harness.transportCallsFor("p1") shouldBe 1
            }

            // The user cancel: the queue entry is removed through the REAL
            // removeFromQueue path (its status settles NOT_TRANSLATED), and the
            // batch job is then cancelled at page 1's parked suspension — the
            // exact state ChapterTranslator's CancellationException branch runs in.
            harness.translator.removeFromQueue(harness.chapterFor(CHAPTER_ID))
            batch.job.cancel()
            withTimeout(AWAIT_TIMEOUT_MS) { batch.job.join() }

            val terminal = withTimeout(AWAIT_TIMEOUT_MS) {
                harness.trackerRegistry.terminal.first { it.containsKey(CHAPTER_ID) }.getValue(CHAPTER_ID)
            }
            withClue("the cancelled batch must land a typed ABORTED terminal snapshot, not a live tracker") {
                terminal.aborted shouldBe true
                terminal.abortedReason shouldBe "Batch cancelled"
                terminal.state shouldBe Translation.State.ERROR
                terminal.batchPhase shouldBe TranslationBatchPhase.FINISHED
            }
            withClue("no queue entry may survive the cancellation (the UI reconciliation guard)") {
                harness.translator.queueState.value.none { it.chapter.id == CHAPTER_ID } shouldBe true
            }
            withClue("the removed entry's own status must settle restartable (NOT_TRANSLATED)") {
                queueEntry.status shouldBe Translation.State.NOT_TRANSLATED
            }

            // (No re-seed: run 1's OCR checkpoints and p0's committed work
            // product are DURABLY real under the T924 pipeline — the restart
            // consults the real sidecars.)

            // The restart (what the sheet Retry button invokes): same chapter,
            // same store — the resume gate reuses completed work.
            harness.barrier.disarm(CoexistenceBarrier.BarrierPoint.PROVIDER_START, "p1")
            val restart = harness.launchBatch(pageKeys = listOf("p0", "p1"))
            val reconciliation = checkNotNull(
                withTimeout(AWAIT_TIMEOUT_MS) { restart.reconciliation.await() },
            ) { "restart batch reconciliation missing" }
            withClue("the restart must complete the chapter") {
                restart.translation.status shouldBe Translation.State.TRANSLATED
                reconciliation.nonDurableFailure shouldBe false
                // T924 zero-legacy (D1): the lane commits translations
                // WITHOUT an in-pass render — both pages end
                // translation-terminal and the reader re-derives displays.
                harness.store.state.value.getValue("p0").translationStatus shouldBe StageStatus.READY
                harness.store.state.value.getValue("p1").translationStatus shouldBe StageStatus.READY
                harness.store.state.value.getValue("p0").renderStatus shouldBe StageStatus.PENDING
                harness.store.state.value.getValue("p1").renderStatus shouldBe StageStatus.PENDING
            }
            withClue("reuse: the completed page 0 may not be re-decoded/re-OCR'd by the restart") {
                harness.barrier.arrivalsOf(CoexistenceBarrier.BarrierPoint.NATIVE_ACQUIRE, "p0") shouldBe
                    decodeP0BeforeRestart
            }
            withClue("remainder work: page 1's pending inpaint is the restart's own native work") {
                // T924 zero-legacy (D1): with the re-seed deleted, run 1 parked
                // p1's paid call BEFORE its inpaint ran, so the restart's
                // remainder legitimately decodes p1 exactly once for that
                // inpaint (the translation tail's overlap drain). The durable
                // OCR checkpoint still prevents any re-OCR: the decode feeds
                // the inpaint lane, never the recognizer.
                harness.barrier.arrivalsOf(CoexistenceBarrier.BarrierPoint.NATIVE_ACQUIRE, "p1") shouldBe
                    decodeP1BeforeRestart + 1
            }
            withClue("remainder-only paid work: completed page 0 gets no second paid call") {
                harness.transportCallsFor("p0") shouldBe 1
            }
            withClue("remainder-only paid work: page 1 gets exactly its retry (interrupted + one more)") {
                harness.transportCallsFor("p1") shouldBe 2
            }
        } finally {
            harness.removeGraphicsShims()
            harness.unstubChapterPages()
            harness.close()
        }
    }
}
