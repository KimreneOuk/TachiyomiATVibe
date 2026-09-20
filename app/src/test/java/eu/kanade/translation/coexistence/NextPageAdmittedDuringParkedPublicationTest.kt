package eu.kanade.translation.coexistence

import com.hippo.unifile.UniFile
import eu.kanade.translation.storage.ChapterTranslationStore
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.pipeline.toPrecondition
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test

/**
 * T917 Phase 4 — D11 safe slice (phase4-design §4.4): release the native
 * permit BEFORE storage publication.
 *
 * Drives the REAL manual single-page boundary over the REAL quarantine: a
 * page resumes at the inpaint stage (OCR + translation already committed,
 * cleaned image missing), so its path runs the resume tail whose
 * cleaned-image publication is a real store commit. The test parks that
 * publication at a COMMIT barrier MID-COMMIT, then taps a second page.
 *
 * GREEN (§4.4): the second page's native admission must arrive while the
 * first page's publication is still parked — the permit covers ONLY native
 * compute. RED (today): the permit spans the publication
 * (SinglePageOnnxPhase resume tail runs inside `withNativeLane`), so the
 * second admission cannot arrive and the negative probe is converted into a
 * NAMED assertion (never a raw choreography timeout).
 *
 * Fail-closed is pinned too: after the release, the parked publication must
 * actually commit (a moved persist that silently drops its work would be a
 * worse defect than the latency one).
 */
class NextPageAdmittedDuringParkedPublicationTest {

    companion object {
        const val AWAIT_TIMEOUT_MS = TranslationCoexistenceHarness.AWAIT_TIMEOUT_MS
        const val NEGATIVE_PROBE_MS = TranslationCoexistenceHarness.NEGATIVE_PROBE_MS
    }

    /** p0 resumes at inpaint: OCR + translation committed, cleaned image missing. */
    private fun resumeInpaintP0(): PageTranslation = PageTranslation(sourceFileName = "p0").apply {
        ocrStatus = StageStatus.READY
        translationStatus = StageStatus.READY
        inpaintStatus = StageStatus.PENDING
        renderStatus = StageStatus.PENDING
        blocks += FakeCoexistence.textBlock("hello-p0")
    }

    @Test
    fun `second page is admitted while the first page's cleaned publication is still parked`() =
        runBlocking<Unit> {
            // p0 IS pre-registered (the resume-inpaint fixture is the parked
            // work); p1 is deliberately ABSENT (D8 harness note: a
            // pre-registered PENDING page projects WAIT_FOR_DEPENDENCY for
            // every stage and resume-skips before the decode seam — an absent
            // record plans a fresh native run).
            val store = ChapterTranslationStore(
                translationFile = null as UniFile?,
                fileCreator = null,
                initialPages = mapOf("p0" to resumeInpaintP0()),
            )
            val harness = TranslationCoexistenceHarness.create(
                listOf("p0", "p1"),
                storeOverride = store,
            )
            try {
                harness.installGraphicsShims()
                harness.registerReaderStream(TranslationCoexistenceHarness.CHAPTER_ID, "p0")
                harness.registerReaderStream(TranslationCoexistenceHarness.CHAPTER_ID, "p1")

                // The D11 seam: the resume tail's cleaned-image publication is
                // a REAL store commit that parks at the COMMIT barrier
                // mid-publication (fake only at the sanctioned disk/graphics
                // seam — the commit itself goes through the real store).
                coEvery {
                    harness.cleanedPublicationMock.persistCleanedBitmap(
                        any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                    )
                } coAnswers {
                    val page = arg<PageTranslation>(0)
                    val pageKey = arg<String>(3)
                    val publicationStore = arg<ChapterTranslationStore>(5)
                    harness.barrier.arrive(CoexistenceBarrier.BarrierPoint.COMMIT, pageKey)
                    page.cleanedImageName = "$pageKey.cleaned.jpg"
                    page.inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION
                    page.inpaintingModeUsed = "FAST"
                    page.inpaintStatus = StageStatus.READY
                    page.errorMessage = null
                    val precondition = publicationStore.snapshot(pageKey).toPrecondition()
                    val published = publicationStore.updatePageGuarded(
                        pageKey,
                        precondition,
                        "publish cleaned image (D11 stub)",
                    ) { current ->
                        (current ?: page).apply {
                            cleanedImageName = page.cleanedImageName
                            inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION
                            inpaintingModeUsed = page.inpaintingModeUsed
                            inpaintStatus = StageStatus.READY
                            errorMessage = null
                        }
                    }
                    check(published is ChapterTranslationStore.PatchResult.Accepted) {
                        "D11 stub commit rejected: " +
                            "${(published as? ChapterTranslationStore.PatchResult.Rejected)?.reason}"
                    }
                    published.snapshot
                }

                // p0's manual tap reaches the cleaned publication and parks there.
                harness.barrier.arm(CoexistenceBarrier.BarrierPoint.COMMIT, "p0")
                harness.tapManual("p0")
                runBlocking {
                    harness.barrier.awaitArrivalWithin(
                        CoexistenceBarrier.BarrierPoint.COMMIT,
                        "p0",
                        AWAIT_TIMEOUT_MS,
                    )
                }
                withClue("D11 precondition: p0's cleaned publication is parked BEFORE its commit") {
                    store.snapshot("p0").page?.cleanedImageName.shouldBeNull()
                }

                // The second page taps NOW: GREEN admits it while p0's
                // publication is still parked; RED holds it behind the first
                // page's commit. The bound converts the RED hang into a NAMED
                // defect (design note discipline: never a choreography timeout).
                harness.tapManual("p1")
                try {
                    runBlocking {
                        withTimeout(NEGATIVE_PROBE_MS) {
                            harness.barrier.awaitArrival(
                                CoexistenceBarrier.BarrierPoint.NATIVE_ACQUIRE,
                                "p1",
                            )
                        }
                    }
                } catch (timedOut: TimeoutCancellationException) {
                    val p1Page = store.snapshot("p1").page
                    val p1Job = runCatching { harness.capturedManualJob("p1") }.getOrNull()
                    val outcomes = runCatching {
                        val f = harness.scheduler::class.java.getDeclaredField("manualOutcomes")
                        f.isAccessible = true
                        @Suppress("UNCHECKED_CAST")
                        (f.get(harness.scheduler) as Map<String, Any>).entries
                            .filter { it.key.endsWith(":p1") }
                            .joinToString { "${it.key}=${it.value}" }
                    }.getOrDefault("?")
                    throw AssertionError(
                        "T917 D11 RED defect: the second page's native admission never arrived " +
                            "while the first page's cleaned-image publication was still parked — " +
                            "the native permit spans the storage commit (phase4-design §4.4); " +
                            "arrivals=${harness.barrier.arrivals.value} " +
                            "permitHolder=${harness.engineLane.permitHolderPageKeySnapshot()} " +
                            "p1JobActive=${p1Job?.isActive} " +
                            "p1Outcomes=[$outcomes] " +
                            "p1Store=${p1Page?.let { "ocr=${it.ocrStatus} tr=${it.translationStatus} " +
                                "inp=${it.inpaintStatus} err=${it.errorMessage}" }}",
                        timedOut,
                    )
                }

                // Release the publication; the parked commit lands (fail-closed:
                // the moved publication must not lose its work) and both pages
                // finish their phases.
                harness.barrier.release(CoexistenceBarrier.BarrierPoint.COMMIT, "p0")
                val p0Job = harness.capturedManualJob("p0")
                withTimeout(AWAIT_TIMEOUT_MS) { p0Job.join() }
                withClue("D11: the parked publication commits after the release") {
                    val p0 = store.snapshot("p0").page.shouldNotBeNull()
                    p0.cleanedImageName shouldBe "p0.cleaned.jpg"
                    p0.inpaintStatus shouldBe StageStatus.READY
                }
                val p1Job = harness.capturedManualJob("p1")
                withTimeout(AWAIT_TIMEOUT_MS) { p1Job.join() }
                withClue("D11: the admitted second page actually finishes its work") {
                    val p1 = store.snapshot("p1").page.shouldNotBeNull()
                    p1.translationStatus shouldBe StageStatus.READY
                }
            } finally {
                harness.removeGraphicsShims()
                harness.close()
            }
        }
}
