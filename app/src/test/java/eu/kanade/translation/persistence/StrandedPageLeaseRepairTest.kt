package eu.kanade.translation.persistence

import eu.kanade.translation.model.PageStage
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * P0B acquired-authority regression: the reader stranded-page sweep acquires
 * an AUTO lease, and a MANUAL reader tap is the one cross-origin preemption
 * (it evicts the AUTO record and mints a fresh token). The resumed sweep
 * mutation must fence on the EXACT evidence captured at acquisition — never a
 * fresh snapshot taken at mutation time, which after an eviction carries the
 * replacement MANUAL owner's token/version and would admit the stale AUTO
 * write with borrowed authority. The release must likewise remove only the
 * acquired token's record, never the MANUAL owner's lease.
 */
class StrandedPageLeaseRepairTest {

    private fun strandedPage(): PageTranslation = PageTranslation(
        sourceFileName = PAGE,
        imgWidth = 100f,
        imgHeight = 100f,
        ocrStatus = StageStatus.RUNNING,
        inpaintStatus = StageStatus.PENDING,
        translationStatus = StageStatus.PENDING,
        renderStatus = StageStatus.PENDING,
        // Long past the per-page timeout: every stranded shape is stale.
        updatedAt = 0L,
        runGeneration = 0L,
    )

    private fun store() = ChapterTranslationStore(
        artifactParentResolver = null,
        initialPages = mapOf(PAGE to strandedPage()),
    )

    private fun inFlightManualPage(): PageTranslation = strandedPage().apply {
        ocrStatus = StageStatus.RUNNING
        inpaintStatus = StageStatus.RUNNING
        translationStatus = StageStatus.RUNNING
        renderStatus = StageStatus.RUNNING
        errorMessage = "manual tap in flight"
        updatedAt = System.currentTimeMillis()
    }

    private fun ChapterTranslationStore.leasePrecondition(
        lease: PageStageLease,
    ): ChapterTranslationStore.PatchPrecondition = ChapterTranslationStore.PatchPrecondition(
        generation = lease.generation,
        pageVersion = lease.pageVersion,
        leaseToken = lease.token,
        candidateGenerationId = lease.candidateGenerationId,
        dependencyFingerprint = lease.dependencyFingerprint,
        artifactPageVersion = lease.artifactPageVersion,
    )

    /** The MANUAL preemption primitive: evict AUTO, take the record, write. */
    private suspend fun preemptWithManual(
        store: ChapterTranslationStore,
    ): PageStageLease {
        val manualLease = store.tryAcquirePageStageLease(PAGE, PageStage.Ocr, PageWriteOrigin.MANUAL)
            .shouldBeInstanceOf<LeaseAcquisition.Granted>().lease
        val accepted = store.updatePageGuarded(
            PAGE,
            store.leasePrecondition(manualLease),
            "manual tap preemption",
        ) { inFlightManualPage() }
        accepted.shouldBeInstanceOf<ChapterTranslationStore.PatchResult.Accepted>()
        return manualLease
    }

    @Test
    fun `MANUAL preemption between AUTO acquisition and mutation rejects the resumed sweep and preserves MANUAL page, version and lease`() =
        runTest {
            val store = store()
            // Sweep pause point: AUTO holds the page.
            val autoLease = acquireStrandedSweepLease(store, PAGE)
                .shouldBeInstanceOf<PageStageLease>()
            val autoToken = autoLease.token
            val manualLease = preemptWithManual(store)
            val manualVersion = store.snapshot(PAGE).pageVersion

            // Resumed sweep: mutation fenced on the ACQUIRED evidence.
            val repaired = mutatePageUnderAcquiredSweepLease(
                store = store,
                pageKey = PAGE,
                lease = autoLease,
                update = strandedSweepPageUpdate(store, strandedPage()),
            )

            repaired shouldBe false
            // MANUAL's page state, version and error are untouched.
            val page = store.snapshot(PAGE)
            page.pageVersion shouldBe manualVersion
            page.page?.errorMessage shouldBe "manual tap in flight"
            page.page?.ocrStatus shouldBe StageStatus.RUNNING
            // The stale AUTO holder released nothing: MANUAL still owns.
            store.pageLeaseOwner(PAGE) shouldBe PageWriteOrigin.MANUAL
            page.leaseToken shouldBe manualLease.token
            page.leaseToken shouldNotBe autoToken
        }

    @Test
    fun `MANUAL eviction whose record is already released still rejects the resumed sweep`() =
        runTest {
            val store = store()
            val autoLease = acquireStrandedSweepLease(store, PAGE)
                .shouldBeInstanceOf<PageStageLease>()
            preemptWithManual(store)
            store.releasePageStageLease(PAGE, PageWriteOrigin.MANUAL)
            val manualVersion = store.snapshot(PAGE).pageVersion

            val repaired = mutatePageUnderAcquiredSweepLease(
                store = store,
                pageKey = PAGE,
                lease = autoLease,
                update = strandedSweepPageUpdate(store, strandedPage()),
            )

            repaired shouldBe false
            val page = store.snapshot(PAGE)
            page.pageVersion shouldBe manualVersion
            page.page?.errorMessage shouldBe "manual tap in flight"
            page.page?.translationStatus shouldBe StageStatus.RUNNING
        }

    @Test
    fun `uncontested AUTO repair flips stranded stages and releases only its acquired token`() =
        runTest {
            val store = store()
            val beforeVersion = store.snapshot(PAGE).pageVersion

            val repaired = repairStrandedPageWithAcquiredAutoLease(
                store = store,
                pageKey = PAGE,
                stranded = strandedPage(),
            )

            repaired shouldBe true
            val page = store.snapshot(PAGE).page!!
            page.ocrStatus shouldBe StageStatus.CANCELLED
            page.inpaintStatus shouldBe StageStatus.CANCELLED
            page.translationStatus shouldBe StageStatus.CANCELLED
            page.renderStatus shouldBe StageStatus.CANCELLED
            page.errorMessage shouldBe "Page was stranded mid-translation; reset as cancelled on chapter reopen"
            page.pageVersion shouldBe (beforeVersion + 1L)
            // The acquired AUTO record is gone; no lease is stranded behind.
            store.pageLeaseOwner(PAGE) shouldBe null
        }

    @Test
    fun `sweep acquisition denied while MANUAL owns the page leaks no release`() =
        runTest {
            val store = store()
            val manualLease = store.tryAcquirePageStageLease(PAGE, PageStage.Ocr, PageWriteOrigin.MANUAL)
                .shouldBeInstanceOf<LeaseAcquisition.Granted>().lease
            val before = store.snapshot(PAGE)

            val repaired = repairStrandedPageWithAcquiredAutoLease(
                store = store,
                pageKey = PAGE,
                stranded = strandedPage(),
            )

            repaired shouldBe false
            store.pageLeaseOwner(PAGE) shouldBe PageWriteOrigin.MANUAL
            store.snapshot(PAGE).leaseToken shouldBe manualLease.token
            store.snapshot(PAGE).pageVersion shouldBe before.pageVersion
            store.snapshot(PAGE).page?.ocrStatus shouldBe StageStatus.RUNNING
        }

    /**
     * Reproduction of the pre-P0B hazard: the snapshot-shaped convenience
     * re-reads the page at MUTATION time. After a MANUAL eviction that fresh
     * snapshot carries the replacement MANUAL owner's token (or no token once
     * MANUAL released) plus MANUAL's version, so the stale AUTO write is
     * ADMITTED with borrowed authority — flipping MANUAL's in-flight stages.
     * The sweep must never use this shape; this test pins why.
     */
    @Test
    fun `reproduction - snapshot-shaped mutation after MANUAL preemption borrows the replacement authority`() =
        runTest {
            val store = store()
            store.tryAcquirePageStageLease(PAGE, PageStage.Ocr, PageWriteOrigin.AUTO)
                .shouldBeInstanceOf<LeaseAcquisition.Granted>()
            preemptWithManual(store)
            val manualVersion = store.snapshot(PAGE).pageVersion

            val borrowed = store.updatePageFromCurrentSnapshot(
                PAGE,
                "reader stranded-page sweep (old shape)",
            ) { existing -> strandedSweepPageUpdate(store, strandedPage()).invoke(existing) }

            // The hazard: accepted against MANUAL's freshly written state.
            borrowed.shouldBeInstanceOf<ChapterTranslationStore.PatchResult.Accepted>()
            store.snapshot(PAGE).page?.ocrStatus shouldBe StageStatus.CANCELLED
            store.snapshot(PAGE).pageVersion shouldBe (manualVersion + 1L)
        }

    companion object {
        private const val PAGE = "p1"
    }
}
