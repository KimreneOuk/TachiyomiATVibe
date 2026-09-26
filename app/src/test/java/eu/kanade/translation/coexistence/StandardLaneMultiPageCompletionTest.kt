package eu.kanade.translation.coexistence

import eu.kanade.translation.model.BatchPhase
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.Translation
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 *  On-device finding:
 * a multi-page FRESH standard-lane batch translated only its first page.
 * `PageWorkPlanner.planChapter` bakes `WAIT_FOR_DEPENDENCY/PRIOR_PAGE_INCOMPLETE`
 * into the immutable batch-start plan for every page after the first
 * needs-translation page, and the standard lane's skip treated that static
 * reason as permanent, so pages 2..N never translated and were stranded.
 *
 * The  fixture note had already documented the behavior ("a later page's
 * translation is statically dependency-skipped (PRIOR_PAGE_INCOMPLETE)") while
 * attributing it to harness lane serialization — this test pins it as the
 * production defect it is.
 *
 * RED: p1's transport never starts (named negative probe, never a choreography
 * timeout). GREEN: every page translates exactly once in a single pass, the
 * chapter completes TRANSLATED with no stranded pages, and ordered-context
 * honesty is preserved when the predecessor terminally failed (negative
 * control: the skip must remain when p0 durably failed, not unblock).
 */
//  test-stability quarantine: load-ordering sensitive under full-suite JVM
// churn; tracked for stabilization. Runs with -PincludeQuarantinedTests.
@Tag("quarantined-flaky")
class StandardLaneMultiPageCompletionTest {

    companion object {
        const val AWAIT_TIMEOUT_MS = TranslationCoexistenceHarness.AWAIT_TIMEOUT_MS
        const val NEGATIVE_PROBE_MS = TranslationCoexistenceHarness.NEGATIVE_PROBE_MS
    }

    @Test
    fun `fresh standard batch translates every page of a multi-page chapter`() = runBlocking<Unit> {
        val pageKeys = listOf("p0", "p1", "p2")
        //  zero-legacy: the batch requires artifact authority.
        val harness = TranslationCoexistenceHarness.create(
            pageKeys,
            storeOverride = TranslationCoexistenceHarness.artifactAuthorityStore(pageKeys),
        )
        var batch: TranslationCoexistenceHarness.BatchRun? = null
        try {
            harness.installGraphicsShims()
            harness.stubChapterPages(pageKeys)
            val batchRun = harness.launchBatch(pageKeys).also { batch = it }

            // p0 must run (sanity marker for the harness itself).
            withTimeout(AWAIT_TIMEOUT_MS) { harness.transportStarted.getValue("p0").await() }

            // The defect probe: p1's translation transport must start while the
            // pass is still running. RED: it never does (static dependency
            // skip), so this bounded probe raises the named assertion instead
            // of a choreography timeout.
            //  flake stabilization: this is a POSITIVE probe (p1 must
            // start), not a negative oracle — the 2s NEGATIVE_PROBE_MS budget
            // was marginally tight for a fresh 3-page pipeline under full-suite
            // load (observed 2 misses in 6 runs). The full harness await budget
            // bounds it; the "never starts" defect still raises the same named
            // assertion on expiry.
            try {
                withTimeout(AWAIT_TIMEOUT_MS) { harness.transportStarted.getValue("p1").await() }
            } catch (e: TimeoutCancellationException) {
                throw AssertionError(
                    "multi-page standard batch never translates p1: static " +
                        "PRIOR_PAGE_INCOMPLETE skip is permanent within a pass",
                    e,
                )
            }

            val reconciliation = withTimeout(AWAIT_TIMEOUT_MS) {
                batchRun.reconciliation.await().shouldNotBeNull()
            }
            check(!reconciliation.nonDurableFailure) {
                "multi-page standard batch had a non-durable failure: " +
                    harness.failureDiagnostics(pageKeys)
            }
            val chapterStatus = reconciliation.chapterStatus
            if (chapterStatus != Translation.State.TRANSLATED) {
                throw AssertionError(
                    "multi-page standard batch ended as $chapterStatus: " +
                        harness.failureDiagnostics(pageKeys),
                )
            }
            chapterStatus shouldBe Translation.State.TRANSLATED
            reconciliation.strandedPages.shouldBeEmpty()
            pageKeys.forEach { pageKey ->
                harness.transportCallsFor(pageKey) shouldBe 1
                //  read-race hardening: reconciliation completes on its own
                // flow; under load the durable page-stamp publication can
                // land after terminal status is observed. Await the store's
                // terminal page state within the harness budget instead of
                // asserting an immediate snapshot.
                val page = withTimeout(AWAIT_TIMEOUT_MS) {
                    harness.store.state.first { s ->
                        s[pageKey]?.translationStatus == StageStatus.READY &&
                            s[pageKey]?.renderStatus == StageStatus.READY
                    }.getValue(pageKey)
                }
                page.translationStatus shouldBe StageStatus.READY
                page.renderStatus shouldBe StageStatus.READY
            }
            //  zero-legacy: the shell's COMPLETED path settles every
            // expected page's RENDER phase as skipped terminal work
            // (markRenderSkipped per expected page before the terminal finish),
            // so the terminal snapshot's render arm is processed even though no
            // lane rendered in-pass.
            val terminal = withTimeout(AWAIT_TIMEOUT_MS) {
                harness.trackerRegistry.terminal
                    .first { it.containsKey(harness.CHAPTER_ID) }
                    .getValue(harness.CHAPTER_ID)
            }
            terminal.perStage.getValue(BatchPhase.RENDER).processed shouldBe pageKeys.size
        } finally {
            // The batch job may still be unwinding when an observation times
            // out. Join it before removing global MockK shims or chapter-page
            // stubs; otherwise the next test can observe a half-torn graph.
            batch?.job?.cancelAndJoin()
            harness.removeGraphicsShims()
            harness.unstubChapterPages()
            harness.close()
        }
    }

    @Test
    fun `failed predecessor keeps successors honestly stranded`() = runBlocking<Unit> {
        //  zero-legacy: the batch requires artifact authority; the
        // FAILED predecessor is seeded into the durable store post-build.
        val store = TranslationCoexistenceHarness.artifactAuthorityStore(listOf("p0", "p1"))
        kotlinx.coroutines.runBlocking {
            store.updatePage("p0") { current ->
                (current ?: PageTranslation(sourceFileName = "p0")).apply {
                    ocrStatus = StageStatus.READY
                    translationStatus = StageStatus.FAILED
                }
            }
        }
        val harness = TranslationCoexistenceHarness.create(listOf("p0", "p1"), storeOverride = store)
        var batch: TranslationCoexistenceHarness.BatchRun? = null
        try {
            harness.installGraphicsShims()
            harness.stubChapterPages(listOf("p0", "p1"))
            batch = harness.launchBatch(listOf("p0", "p1"))

            // The live unblock must be predecessor-terminality, not merely the
            // absence of the static marker: a FAILED predecessor must NOT let
            // its successor translate (ordered-context honesty), so p1's
            // transport staying silent within the probe window is the PASS.
            val p1Started = try {
                withTimeout(NEGATIVE_PROBE_MS) { harness.transportStarted.getValue("p1").await() }
                true
            } catch (e: TimeoutCancellationException) {
                false
            }
            p1Started shouldBe false
            //  zero-legacy: the successor's ordered-context skip is
            // a TYPED durable failure (predecessor terminally failed), never
            // a paid call and never a fake success.
            harness.store.state.value.getValue("p1").translationStatus shouldBe StageStatus.FAILED
        } finally {
            // Do not tear down global mocks while the negative-control batch is
            // still unwinding; its real job is intentionally not awaited above.
            batch?.job?.cancelAndJoin()
            harness.removeGraphicsShims()
            harness.unstubChapterPages()
            harness.close()
        }
    }
}
