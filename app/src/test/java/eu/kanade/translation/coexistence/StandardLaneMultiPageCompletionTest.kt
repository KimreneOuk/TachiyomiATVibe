package eu.kanade.translation.coexistence

import eu.kanade.translation.ChapterTranslationStore
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.Translation
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test

/**
 * T917 Phase 6 on-device finding (phase6-multipage-strand-investigation.md):
 * a multi-page FRESH standard-lane batch translated only its first page.
 * `PageWorkPlanner.planChapter` bakes `WAIT_FOR_DEPENDENCY/PRIOR_PAGE_INCOMPLETE`
 * into the immutable batch-start plan for every page after the first
 * needs-translation page, and the standard lane's skip treated that static
 * reason as permanent, so pages 2..N never translated and were stranded.
 *
 * The D10 fixture note had already documented the behavior ("a later page's
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
class StandardLaneMultiPageCompletionTest {

    companion object {
        const val AWAIT_TIMEOUT_MS = TranslationCoexistenceHarness.AWAIT_TIMEOUT_MS
        const val NEGATIVE_PROBE_MS = TranslationCoexistenceHarness.NEGATIVE_PROBE_MS
    }

    @Test
    fun `fresh standard batch translates every page of a multi-page chapter`() = runBlocking<Unit> {
        val pageKeys = listOf("p0", "p1", "p2")
        val harness = TranslationCoexistenceHarness.create(pageKeys)
        try {
            harness.installGraphicsShims()
            harness.stubChapterPages(pageKeys)
            val batch = harness.launchBatch(pageKeys)

            // p0 must run (sanity marker for the harness itself).
            withTimeout(AWAIT_TIMEOUT_MS) { harness.transportStarted.getValue("p0").await() }

            // The defect probe: p1's translation transport must start while the
            // pass is still running. RED: it never does (static dependency
            // skip), so this bounded probe raises the named assertion instead
            // of a choreography timeout.
            try {
                withTimeout(NEGATIVE_PROBE_MS) { harness.transportStarted.getValue("p1").await() }
            } catch (e: TimeoutCancellationException) {
                throw AssertionError(
                    "multi-page standard batch never translates p1: static " +
                        "PRIOR_PAGE_INCOMPLETE skip is permanent within a pass",
                    e,
                )
            }

            val reconciliation = withTimeout(AWAIT_TIMEOUT_MS) { batch.reconciliation.await() }
            reconciliation.shouldNotBeNull()
            reconciliation!!.chapterStatus shouldBe Translation.State.TRANSLATED
            reconciliation.strandedPages.shouldBeEmpty()
            pageKeys.forEach { pageKey ->
                harness.transportCallsFor(pageKey) shouldBe 1
                val page = harness.store.state.value.getValue(pageKey)
                page.translationStatus shouldBe StageStatus.READY
                page.renderStatus shouldBe StageStatus.READY
            }
        } finally {
            harness.removeGraphicsShims()
            harness.unstubChapterPages()
            harness.close()
        }
    }

    @Test
    fun `failed predecessor keeps successors honestly stranded`() = runBlocking<Unit> {
        val store = ChapterTranslationStore(
            translationFile = null as com.hippo.unifile.UniFile?,
            fileCreator = null,
            initialPages = mapOf(
                "p0" to PageTranslation(sourceFileName = "p0").apply {
                    ocrStatus = StageStatus.READY
                    translationStatus = StageStatus.FAILED
                },
            ),
        )
        val harness = TranslationCoexistenceHarness.create(listOf("p0", "p1"), storeOverride = store)
        try {
            harness.installGraphicsShims()
            harness.stubChapterPages(listOf("p0", "p1"))
            harness.launchBatch(listOf("p0", "p1"))

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
            harness.store.state.value.getValue("p1").translationStatus shouldBe StageStatus.PENDING
        } finally {
            harness.removeGraphicsShims()
            harness.unstubChapterPages()
            harness.close()
        }
    }
}
