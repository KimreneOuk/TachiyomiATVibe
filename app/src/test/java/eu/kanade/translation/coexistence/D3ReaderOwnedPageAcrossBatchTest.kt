package eu.kanade.translation.coexistence

import eu.kanade.translation.PageWriteOrigin
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationProgressSnapshot
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test

/**
 * T917 Phase 1 — D3 coexistence contract (design note §3.2), RED on purpose.
 *
 * Scenario: the reader owns p1 across a FULL batch (manual parked at
 * PROVIDER_END — lease held, native permit free so the batch runs); p0 is free
 * and must complete all real stages end-to-end (native → provider → real
 * mergeRender COMMIT). p1 is the page BEHIND the batch's first page, so the
 * ordered-wait planner marks it for the reader page position in this fixture.
 *
 * Target contract (draft §6 D3, defer-and-rescan): the batch defers the
 * reader-owned page and rescans it within the same pass after the lease is
 * free, so p1 ends in exactly one terminal state, the reconciliation strands
 * nothing, the chapter projects TRANSLATED, and the tracker terminal snapshot
 * shows 2/2 with no stranded or errored pages (draft §7 outcome contract).
 *
 * RED today (audit C-02): BatchLaneWorkers.runOcrStage returns null on a denied
 * lease ("rescanned later" is only the log text) and the coordinator records a
 * plain skip, so p1 is never rescanned; reconciliation strands it ("expected
 * page was stranded by a prior run") and the chapter projects ERROR.
 */
class D3ReaderOwnedPageAcrossBatchTest {

    @Test
    fun `reader-owned page across a full batch is re-translated in the same pass and never stranded`() = runBlocking<Unit> {
        // Production-faithful fresh-chapter state: no page records exist until
        // the batch pre-registers or the manual path creates them (see harness
        // create() doc — pre-registered PENDING entries would make the manual
        // path resume-skip before any barrier).
        val harness = TranslationCoexistenceHarness.create(preRegisterInStore = false)
        harness.installGraphicsShims()
        harness.stubChapterPages(listOf("p0", "p1"))
        harness.registerReaderStream(TranslationCoexistenceHarness.CHAPTER_ID, "p0")
        harness.registerReaderStream(TranslationCoexistenceHarness.CHAPTER_ID, "p1")
        try {
            // Reader owns p1 (the page behind the batch's first page): the
            // manual parks at PROVIDER_END — lease held, native permit free
            // (deterministic via the barrier arrival).
            harness.barrier.arm(CoexistenceBarrier.BarrierPoint.PROVIDER_END, "p1")
            harness.tapManual("p1")
            val manualJob = harness.capturedManualJob("p1")
            try {
                harness.barrier.awaitArrivalWithin(
                    CoexistenceBarrier.BarrierPoint.PROVIDER_END,
                    "p1",
                    TranslationCoexistenceHarness.AWAIT_TIMEOUT_MS,
                )
            } catch (t: Throwable) {
                println("DBG arrivals=" + harness.barrier.arrivals.value)
                println("DBG store=" + harness.store.state.value.mapValues { (_, p) ->
                    "ocr=${p.ocrStatus}/tr=${p.translationStatus}/inp=${p.inpaintStatus}/cl=${p.cleanedImageName}"
                })
                throw t
            }

            withClue("manual must own p1 while parked at PROVIDER_END") {
                harness.store.pageLeaseOwner("p1") shouldBe PageWriteOrigin.READER_ADHOC
            }

            // Full batch runs while the reader holds p1.
            val batch = harness.launchBatch(listOf("p0", "p1"))

            // p0 (batch-owned, first page) completes every real stage: native
            // decode, paid provider call, and the REAL durable render commit.
            // p1 must NOT be awaited here: the reader holds its lease at
            // PROVIDER_END, so its render can only land after the manual is
            // released below (its only pre-release "progress" is the batch's
            // denied-lease skip).
            harness.barrier.awaitArrivalWithin(
                CoexistenceBarrier.BarrierPoint.NATIVE_ACQUIRE,
                "p0",
                TranslationCoexistenceHarness.AWAIT_TIMEOUT_MS,
            )
            harness.barrier.awaitArrivalWithin(
                CoexistenceBarrier.BarrierPoint.PROVIDER_START,
                "p0",
                TranslationCoexistenceHarness.AWAIT_TIMEOUT_MS,
            )
            harness.barrier.awaitArrivalWithin(
                CoexistenceBarrier.BarrierPoint.PROVIDER_END,
                "p0",
                TranslationCoexistenceHarness.AWAIT_TIMEOUT_MS,
            )
            withTimeout(TranslationCoexistenceHarness.AWAIT_TIMEOUT_MS) {
                harness.store.state.first { it["p0"]?.renderStatus == StageStatus.READY }
            }

            // The reader releases p1 BEFORE the reconciliation is awaited: the
            // Phase-2 green path (defer-and-rescan) rescans the reader-owned
            // page only after the lease is free, so the pass — and its
            // reconciliation — can only complete after the release (Reviewer
            // condition 1). Today the batch already finished the pass with a
            // plain skip, so the same stranded result is observed and the RED
            // message below is byte-identical, only later in the test body.
            harness.barrier.release(CoexistenceBarrier.BarrierPoint.PROVIDER_END, "p1")

            val reconciliation = checkNotNull(
                withTimeout(TranslationCoexistenceHarness.AWAIT_TIMEOUT_MS) { batch.reconciliation.await() },
            ) { "batch reconciliation missing" }
            val tracker: TranslationProgressSnapshot =
                withTimeout(TranslationCoexistenceHarness.AWAIT_TIMEOUT_MS) {
                    harness.trackerRegistry.terminal.first { it.containsKey(TranslationCoexistenceHarness.CHAPTER_ID) }
                        .getValue(TranslationCoexistenceHarness.CHAPTER_ID)
                }

            withClue(
                "D3 (C-02): reader-owned page (p1) was skipped and never rescanned, so reconciliation stranded it: " +
                    "stranded=${reconciliation.strandedPages}",
            ) {
                reconciliation.strandedPages shouldBe emptyMap()
            }
            withClue("D3: chapter must project TRANSLATED, not ERROR") {
                reconciliation.chapterStatus shouldBe Translation.State.TRANSLATED
            }
            withClue(
                "D3: tracker terminal snapshot must be 2/2 done with no failures " +
                    "(done=${tracker.donePages}/${tracker.totalPages} failed=${tracker.failedCount})",
            ) {
                tracker.donePages shouldBe 2
                tracker.failedCount shouldBe 0
            }

            withTimeout(TranslationCoexistenceHarness.AWAIT_TIMEOUT_MS) {
                listOf(batch.job, manualJob).joinAll()
            }
            // Releasing the reader intent must still leave p1 in exactly one
            // terminal state.
            withClue("p1 must end in exactly one terminal state") {
                harness.store.state.value.getValue("p1").renderStatus shouldBe StageStatus.READY
            }
            withClue("exactly-once paid-call oracle") {
                harness.fakeTransport.callsFor("p0") shouldBe 1
                harness.fakeTransport.callsFor("p1") shouldBe 1
            }
        } finally {
            harness.removeGraphicsShims()
            harness.unstubChapterPages()
            harness.close()
        }
    }
}
