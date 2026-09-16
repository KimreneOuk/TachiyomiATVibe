package eu.kanade.translation.coexistence

import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.Translation
import io.kotest.assertions.withClue
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test

/**
 * T917 Phase 1 — normal-manga isolation gate (design note §3.4; draft §8.2).
 * GREEN at Phase 1 exit; it gates every later phase that touches arbitration,
 * storage observation, or decode paths.
 *
 * Scenario: a chapter with no reader session, no auto window, and no queued
 * batch (translation-disabled at the product level) sits next to an ACTIVE
 * batch chapter that fully completes through the real graph.
 *
 * Observable oracles, all event-driven (note §3.4):
 *  1. no coordinator: reconcileAutoWindow() never creates a
 *     RollingAutoCoordinator — scheduler.autoSnapshot stays null;
 *  2. no storage observation: the disabled chapter was never opened in the
 *     real ActiveChapterStoreRegistry — observeActiveDisplayStore is null and
 *     selectActiveStore only ever emits the empty map;
 *  3. no extra decode: the fake-decode arrival counter (NATIVE_ACQUIRE log)
     *  is zero for the disabled chapter while remaining a live counter for the
 *     active chapter's pages (positive control), and the paid provider lane
 *     saw exactly the active chapter's two pages.
 *
 * Note: the preference-level translation-enabled gate lives in
 * ReaderViewModel, outside this graph; at manager level the arbitration /
 * observation entry points asserted here ARE the gates (see
 * review/phase1-verification.md).
 */
class NormalMangaIsolationTest {

    @Test
    fun `translation-disabled chapter next to an active batch never enters arbitration observation or decode`() = runBlocking<Unit> {
        // The active batch chapter has a single page: the production
        // ordered-wait planner (WAIT_FOR_DEPENDENCY/PRIOR_PAGE_INCOMPLETE for
        // pages behind a needs-work predecessor) makes a one-page chapter the
        // deterministic "batch completes end-to-end" fixture.
        // T924 zero-legacy (D1): the batch pipeline requires artifact
        // authority, so the batch chapter's store is the durable
        // ARTIFACTS-authority recipe.
        val harness = TranslationCoexistenceHarness.create(
            listOf("p0"),
            storeOverride = TranslationCoexistenceHarness.artifactAuthorityStore(listOf("p0")),
        )
        harness.installGraphicsShims()
        harness.stubChapterPages(listOf("p0"))
        try {
            // The active neighbor: a full batch that completes end-to-end.
            val batch = harness.launchBatch()
            val reconciliation = checkNotNull(
                withTimeout(TranslationCoexistenceHarness.AWAIT_TIMEOUT_MS) { batch.reconciliation.await() },
            ) { "batch reconciliation missing" }
            println("DBG store=" + harness.store.state.value.mapValues { (_, p) ->
                "ocr=${p.ocrStatus}/tr=${p.translationStatus}/inp=${p.inpaintStatus}/rend=${p.renderStatus}/cl=${p.cleanedImageName}/blocks=${p.blocks.size}"
            })
            println("DBG recon=$reconciliation paid=${harness.fakeTransport.callsByPage}")
            withClue("active batch must complete normally for this gate to be meaningful") {
                batch.translation.status shouldBe Translation.State.TRANSLATED
                reconciliation.strandedPages shouldBe emptyMap()
                // T924 zero-legacy (D1) + 2026-09-16 E-fix: the batch still
                // runs NO in-pass render stage (no render lane work, display
                // re-derived when the reader opens the page), but the durable
                // page record is now STAMPED render-terminal at
                // inpaint-completion (BatchLaneWorkers render terminal stamp)
                // so hasRenderedResult fires and the page promotes to a
                // committed display bundle instead of showing ORIGINALS.
                harness.store.state.value.getValue("p0").translationStatus shouldBe StageStatus.READY
                harness.store.state.value.getValue("p0").renderStatus shouldBe StageStatus.READY
            }

            // 1. No arbitration: no reader window update was ever issued for the
            //    disabled chapter, so reconcileAutoWindow() must not create a
            //    coordinator and autoSnapshot must stay null.
            harness.manager.reconcileAutoWindow()
            val coordinatorAppeared = try {
                withTimeout(TranslationCoexistenceHarness.NEGATIVE_PROBE_MS) {
                    harness.scheduler.autoSnapshot.first { it != null }
                }
                true
            } catch (_: TimeoutCancellationException) {
                false
            }
            withClue("isolation: a rolling auto coordinator was created without any reader window update") {
                coordinatorAppeared shouldBe false
            }

            // 2. No storage observation: no store was ever opened for the
            //    disabled chapter in the real ActiveChapterStoreRegistry.
            withClue("isolation: observeActiveDisplayStore opened/observed a store for the disabled chapter") {
                harness.manager.observeActiveDisplayStore(TranslationCoexistenceHarness.DISABLED_CHAPTER_ID)
                    .shouldBeNull()
            }
            val observed = withTimeout(TranslationCoexistenceHarness.AWAIT_TIMEOUT_MS) {
                harness.manager.selectActiveStore(TranslationCoexistenceHarness.DISABLED_CHAPTER_ID).first()
            }
            withClue("isolation: selectActiveStore emitted a non-empty map for the disabled chapter") {
                observed.shouldBeEmpty()
            }

            // 3. No extra decode: the fake-decode counter for the disabled
            //    chapter is zero, while the counter is provably live for the
            //    active chapter's pages (positive control).
            withClue("isolation: the disabled chapter reached the native decode lane") {
                harness.barrier.arrivalsOf(CoexistenceBarrier.BarrierPoint.NATIVE_ACQUIRE, "disabled-0") shouldBe 0
                harness.barrier.arrivalsOf(CoexistenceBarrier.BarrierPoint.NATIVE_ACQUIRE, "disabled-1") shouldBe 0
            }
            withClue("positive control: the active chapter page was decoded (preflight OCR + inpaint re-decode)") {
                // T924 zero-legacy (D1): the pipeline decodes the page once
                // for the OCR preflight and once more for the native inpaint
                // drain — two real decodes, both on the active chapter.
                harness.barrier.arrivalsOf(CoexistenceBarrier.BarrierPoint.NATIVE_ACQUIRE, "p0") shouldBe 2
            }
            withClue("positive control: the paid provider call stayed on the active chapter") {
                harness.fakeTransport.totalCalls() shouldBe 1
                harness.fakeTransport.callsFor("p0") shouldBe 1
            }
        } finally {
            harness.removeGraphicsShims()
            harness.unstubChapterPages()
            harness.close()
        }
    }
}
