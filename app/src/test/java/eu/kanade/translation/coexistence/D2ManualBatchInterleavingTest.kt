package eu.kanade.translation.coexistence

import eu.kanade.translation.PageWriteOrigin
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.Translation
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test

/**
 * T917 Phase 1 — D2 coexistence contract (design note §3.1), RED on purpose.
 *
 * Target contract (draft §6 D2, wait-and-attach + defer-and-rescan):
 *  1. batch→manual: a manual tap on a batch-owned page must WAIT (and attach)
 *     while the batch owns the page — it must never complete silently.
 *  2. manual→batch: a batch reaching a manually-owned page must defer and
 *     re-run it within the same pass after the lease is free — the chapter
 *     must still reach TRANSLATED with exactly-once paid calls.
 *
 * Both tests run the REAL graph through TranslationCoexistenceHarness and are
 * deterministic: every wait is a CompletableDeferred/StateFlow gate; the only
 * timeouts are oracle bounds inside runBlocking { withTimeout(...) }.
 *
 * RED today (audit C-01): acquireReaderPageLease returns false on a denied
 * lease and the single-page boundary returns immediately, so test 1's
 * "manual still waiting" probe finds the manual job already completed; and the
 * batch records a plain skip for the manually-owned page, so test 2's
 * reconciliation strands it and the chapter projects ERROR.
 */
class D2ManualBatchInterleavingTest {

    @Test
    fun `batch to manual - tap while batch holds the page at provider start end and render`() = runBlocking<Unit> {
        for (point in listOf(
            CoexistenceBarrier.BarrierPoint.PROVIDER_START,
            CoexistenceBarrier.BarrierPoint.PROVIDER_END,
            CoexistenceBarrier.BarrierPoint.RENDER,
        )) {
            // Single-page chapter: the page the batch translates is the page
            // the reader taps (no ordered-wait planner involvement).
            val harness = TranslationCoexistenceHarness.create(listOf("p0"))
            harness.installGraphicsShims()
            harness.stubChapterPages(listOf("p0"))
            try {
                // Arm the barrier BEFORE launching the corresponding coroutine
                // (design note §2 determinism rules).
                harness.barrier.arm(point, "p0")

                val batch = harness.launchBatch()
                // Deterministic: the arrival log only advances when the real
                // pipeline reached the barrier, and the batch is parked there.
                harness.barrier.awaitArrivalWithin(
                    point,
                    "p0",
                    TranslationCoexistenceHarness.AWAIT_TIMEOUT_MS,
                )

                withClue("batch must own p0 while parked at $point") {
                    harness.store.pageLeaseOwner("p0") shouldBe PageWriteOrigin.BATCH
                }

                // The user taps the page the batch currently owns.
                harness.tapManual("p0")
                val manualJob = harness.capturedManualJob("p0")

                // Target contract: the manual job must still be running while
                // the batch owns the page. Event-driven probe: join() returns
                // the moment the manual job unwinds, so a silent early return
                // is detected exactly, with no sleeps.
                val manualWaited = try {
                    withTimeout(TranslationCoexistenceHarness.NEGATIVE_PROBE_MS) { manualJob.join() }
                    false
                } catch (_: TimeoutCancellationException) {
                    true
                }
                withClue(
                    "D2 batch→manual (C-01) at $point: manual tap on a batch-owned page finished without " +
                        "waiting for the batch owner (wait-and-attach not implemented)",
                ) {
                    manualWaited shouldBe true
                }

                // Let the batch finish p0; the manual intent must complete only
                // after the batch COMMIT + lease release, with exactly one paid
                // call for the page.
                harness.barrier.release(point, "p0")
                val reconciliation = checkNotNull(
                    withTimeout(TranslationCoexistenceHarness.AWAIT_TIMEOUT_MS) { batch.reconciliation.await() },
                ) { "batch reconciliation missing at $point" }
                withTimeout(TranslationCoexistenceHarness.AWAIT_TIMEOUT_MS) {
                    listOf(batch.job, manualJob).joinAll()
                }

                withClue("batch outcome at $point: p0 lease must be released after commit") {
                    harness.store.pageLeaseOwner("p0") shouldBe null
                }
                withClue("exactly-once paid-call oracle at $point") {
                    harness.fakeTransport.callsFor("p0") shouldBe 1
                }
                withClue("p0 terminal state at $point") {
                    harness.store.state.value.getValue("p0").renderStatus shouldBe StageStatus.READY
                }
            } finally {
                harness.removeGraphicsShims()
                harness.unstubChapterPages()
                harness.close()
            }
        }
    }

    @Test
    fun `manual to batch - batch must not start paid work on the manually-owned page`() = runBlocking<Unit> {
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
            // 1. The reader taps p1 (the page behind the batch's first page);
            //    the manual path parks at PROVIDER_END — native permit free,
            //    READER_ADHOC lease held (deterministic).
            harness.barrier.arm(CoexistenceBarrier.BarrierPoint.PROVIDER_END, "p1")
            harness.tapManual("p1")
            val manualJob = harness.capturedManualJob("p1")
            harness.barrier.awaitArrivalWithin(
                CoexistenceBarrier.BarrierPoint.PROVIDER_END,
                "p1",
                TranslationCoexistenceHarness.AWAIT_TIMEOUT_MS,
            )

            withClue("manual must own p1 while parked at PROVIDER_END") {
                harness.store.pageLeaseOwner("p1") shouldBe PageWriteOrigin.READER_ADHOC
            }

            // 2. Start the batch while the manual holds the page. The batch
            //    finishes p0 (free page) and reaches p1 (denied — manually
            //    owned). The reconciliation is deliberately NOT awaited here:
            //    the Phase-2 green path (defer-and-rescan) can only rescan p1
            //    after the lease is free, so the pass can only complete after
            //    the release below (Reviewer condition 1).
            val batch = harness.launchBatch(listOf("p0", "p1"))

            // 3. Release the manual; in the green state (Phase 2 defer-and-
            //    rescan) the batch rescans p1 only after the lease is free, so
            //    the pass can only complete after this release. The manual
            //    lease release — not the job join — is the event the rescan
            //    waits on (Reviewer condition 1).
            harness.barrier.release(CoexistenceBarrier.BarrierPoint.PROVIDER_END, "p1")

            // Target contract: the manually-owned page must be deferred and
            // re-run within the same pass once the lease is free — never
            // stranded, never double-paid. Today the pass already completed
            // with a plain skip, so this await returns the same stranded
            // result and the RED message is byte-identical, only later in the
            // test body.
            val reconciliation = checkNotNull(
                withTimeout(TranslationCoexistenceHarness.AWAIT_TIMEOUT_MS) { batch.reconciliation.await() },
            ) { "batch reconciliation missing" }
            withClue(
                "D2 manual→batch (C-02): batch stranded the manually-owned page instead of " +
                    "defer-and-rescan within the pass: stranded=${reconciliation.strandedPages}",
            ) {
                reconciliation.strandedPages shouldBe emptyMap()
            }
            withClue("D2 manual→batch: chapter must project TRANSLATED with the reader-owned page preserved") {
                reconciliation.chapterStatus shouldBe Translation.State.TRANSLATED
            }

            // 4. Both intents must settle; the manual must complete with a
            //    real render commit.
            withTimeout(TranslationCoexistenceHarness.AWAIT_TIMEOUT_MS) {
                listOf(batch.job, manualJob).joinAll()
            }
            withClue("manual must reach its terminal render state") {
                harness.store.state.value.getValue("p0").renderStatus shouldBe StageStatus.READY
                harness.store.state.value.getValue("p1").renderStatus shouldBe StageStatus.READY
            }
            withClue("exactly-once paid-call oracle: each page translated exactly once (batch p0, manual p1)") {
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
