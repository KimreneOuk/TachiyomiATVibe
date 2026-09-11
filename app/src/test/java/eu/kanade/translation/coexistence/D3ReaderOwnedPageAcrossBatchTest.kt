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
 * T917 Phase 1 — D3 coexistence contract (design note §3.2), T924 zero-legacy
 * form (D1).
 *
 * Scenario: the reader owns p1 across a batch pass (manual parked at
 * PROVIDER_END — lease held); p0 is free. The reader page is BEHIND the
 * batch's first page.
 *
 * Zero-legacy contract: the batch NEVER preempts the reader-owned page and
 * NEVER runs paid work over an incomplete corpus. The preflight defers p1
 * (its lease is MANUAL-owned), records the corpus gap, and PAUSES the pass
 * before the translation tail — p0's OCR checkpoint is durable, zero paid
 * calls happen while the reader holds the page. When the reader's own intent
 * completes p1, a follow-up batch run resumes from the checkpoints: p0 gets
 * its exactly-once paid call, p1 is never re-paid, nothing is stranded, and
 * the chapter projects TRANSLATED with a 2/2 tracker terminal. (The legacy
 * in-pass defer-and-rescan re-run was SequentialBatchCoordinator machinery,
 * deleted with the legacy path; the surviving contract is reader priority +
 * durable resume.)
 */
class D3ReaderOwnedPageAcrossBatchTest {

    @Test
    fun `reader-owned page across a batch is never preempted and a follow-up run completes the chapter`() = runBlocking<Unit> {
        // Production-faithful fresh-chapter state: no page records exist until
        // the batch pre-registers or the manual path creates them (see harness
        // create() doc — pre-registered PENDING entries would make the manual
        // path resume-skip before any barrier).
        // T924 zero-legacy (D1): the batch requires artifact authority — and
        // that authority is what makes run 1's p0 OCR checkpoint resumable.
        val harness = TranslationCoexistenceHarness.create(
            preRegisterInStore = false,
            storeOverride = TranslationCoexistenceHarness.artifactAuthorityStore(
                listOf("p0", "p1"),
                preRegisterInStore = false,
            ),
        )
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
                harness.store.pageLeaseOwner("p1") shouldBe PageWriteOrigin.MANUAL
            }

            // The batch runs while the reader holds p1: the preflight decodes
            // p0 (NATIVE_ACQUIRE proves the run started), then defers the
            // leased p1 and pauses the pass on the corpus gap.
            val batch = harness.launchBatch(listOf("p0", "p1"))
            harness.barrier.awaitArrivalWithin(
                CoexistenceBarrier.BarrierPoint.NATIVE_ACQUIRE,
                "p0",
                TranslationCoexistenceHarness.AWAIT_TIMEOUT_MS,
            )
            val run1 = checkNotNull(
                withTimeout(TranslationCoexistenceHarness.AWAIT_TIMEOUT_MS) { batch.reconciliation.await() },
            ) { "run 1 reconciliation missing" }

            withClue(
                "D3 (reader priority): the pass must pause on the deferred page, never run paid work " +
                    "while the reader owns p1",
            ) {
                harness.transportCallsFor("p0") shouldBe 0
                harness.transportCallsFor("p1") shouldBe 1 // the reader's own parked call only
            }
            withClue("D3: run 1 strands nothing — the deferred page is not this run's expectation") {
                run1.strandedPages shouldBe emptyMap()
            }

            // The reader releases p1; the reader intent completes it (its own
            // paid call — the one parked above — commits READY).
            harness.barrier.release(CoexistenceBarrier.BarrierPoint.PROVIDER_END, "p1")
            withTimeout(TranslationCoexistenceHarness.AWAIT_TIMEOUT_MS) {
                listOf(batch.job, manualJob).joinAll()
            }
            withTimeout(TranslationCoexistenceHarness.AWAIT_TIMEOUT_MS) {
                harness.store.state.first { it["p1"]?.translationStatus == StageStatus.READY }
            }

            // The follow-up run (the resume trigger — the sheet Retry/auto
            // window): same chapter, same store — resumes from run 1's p0
            // checkpoint, pays p0 exactly once, skips the reader-owned p1.
            // The tracker's terminal StateFlow conflates: capture run 1's
            // snapshot BEFORE the run, await a NEWER (reference-different)
            // one, then read .value.
            val terminalBefore = harness.trackerRegistry.terminal.value[TranslationCoexistenceHarness.CHAPTER_ID]
            val restart = harness.launchBatch(listOf("p0", "p1"))
            val reconciliation = checkNotNull(
                withTimeout(TranslationCoexistenceHarness.AWAIT_TIMEOUT_MS) { restart.reconciliation.await() },
            ) { "follow-up batch reconciliation missing" }
            withTimeout(TranslationCoexistenceHarness.AWAIT_TIMEOUT_MS) { restart.job.join() }
            withTimeout(TranslationCoexistenceHarness.AWAIT_TIMEOUT_MS) {
                harness.trackerRegistry.terminal.first {
                    it[TranslationCoexistenceHarness.CHAPTER_ID] !== terminalBefore
                }
            }
            val tracker = checkNotNull(
                harness.trackerRegistry.terminal.value[TranslationCoexistenceHarness.CHAPTER_ID],
            ) { "tracker terminal snapshot missing after the follow-up run" }

            withClue(
                "D3: the follow-up run must complete the chapter with nothing stranded: " +
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
            withClue("D3: both pages end in exactly one terminal state") {
                harness.store.state.value.getValue("p0").translationStatus shouldBe StageStatus.READY
                harness.store.state.value.getValue("p1").translationStatus shouldBe StageStatus.READY
            }
            withClue("exactly-once paid-call oracle across both runs and the reader intent") {
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
