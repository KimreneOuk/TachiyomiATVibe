package eu.kanade.translation.coexistence

import eu.kanade.translation.model.PageStage
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.pipeline.LeaseAcquisition
import eu.kanade.translation.pipeline.PageWriteOrigin
import eu.kanade.translation.storage.ChapterTranslationStore
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Lease priority and fencing between rolling auto work and manual reader work.
 *
 * Pins the one new acquisition rule and its fencing consequences on the REAL
 * store the coexistence harness wires:
 *
 *  (a) AUTO holds the page lease; a MANUAL (reader-tap boundary) acquisition
 *      EVICTS it — new record, new token, owner becomes MANUAL;
 *  (b) the evicted AUTO side's next guarded write fails CLOSED on its stale
 *      lease token (rejected, page state untouched, never written over
 *      MANUAL's ownership);
 *  (c) the evicted AUTO side's release/cancel is origin-checked and can NOT
 *      remove MANUAL's lease.
 *
 * Every call used here is exactly what the production paths issue after
 * `tryAcquirePageStageLease(AUTO)` is the rolling auto boundary's acquisition
 * (`TranslationPipeline.prepareSinglePage`), `tryAcquirePageStageLease(MANUAL)`
 * is the manual single-page boundary's acquisition
 * (`TranslationPipeline.translateSinglePage`), and `patchPage` with a lease
 * token is the guarded write every stage writer uses.
 *
 * Fixture note: store-level because the behavior lives in the lease table and
 * token fencing; driving it through the full harness would add no coverage.
 */
class ReaderManualPreemptsAutoLeaseTest {

    @Test
    fun `manual boundary evicts an auto lease whose later writes fail closed and cannot release the manual lease`() = runTest {
        val store = store()

        // (a) AUTO owns the page (what the rolling auto boundary acquires).
        val auto = store.tryAcquirePageStageLease(PAGE, PageStage.Ocr, PageWriteOrigin.AUTO)
            .shouldBeInstanceOf<LeaseAcquisition.Granted>().lease
        withClue("auto must own the page before the manual tap") {
            store.pageLeaseOwner(PAGE) shouldBe PageWriteOrigin.AUTO
        }

        // (a) MANUAL (the reader-tap boundary origin) evicts the AUTO lease:
        // granted with a fresh record and a NEW token, owner becomes MANUAL.
        val manual = store.tryAcquirePageStageLease(PAGE, PageStage.Ocr, PageWriteOrigin.MANUAL)
            .shouldBeInstanceOf<LeaseAcquisition.Granted>().lease
        withClue("D1: manual boundary must evict the in-flight auto lease (owner becomes MANUAL)") {
            store.pageLeaseOwner(PAGE) shouldBe PageWriteOrigin.MANUAL
        }
        withClue("D1: eviction must mint a new fencing token so the evicted holder's writes fail closed") {
            manual.token shouldBe auto.token + 1
        }

        // (b) The evicted AUTO holder's next guarded write is rejected on its
        // stale token: typed rejection, page state unchanged, MANUAL ownership
        // intact — fail-closed, never corrupted.
        val beforeEvictedWrite = store.snapshot(PAGE)
        val evictedWrite = store.patchPage(
            PAGE,
            ChapterTranslationStore.PatchPrecondition(
                generation = auto.generation,
                pageVersion = auto.pageVersion,
                leaseToken = auto.token,
            ),
            "evicted auto stage write after manual eviction",
        ) { page -> page!!.apply { errorMessage = "evicted auto wrote over the manual owner" } }
        withClue("D1: the evicted auto side's guarded write must fail closed on its stale lease token") {
            evictedWrite.shouldBeInstanceOf<ChapterTranslationStore.PatchResult.Rejected>()
        }
        withClue("D1: the rejected evicted-auto write must not touch the page state") {
            store.snapshot(PAGE).page?.errorMessage.shouldBeNull()
        }
        withClue("D1: the failed evicted write must leave MANUAL's ownership intact") {
            store.pageLeaseOwner(PAGE) shouldBe PageWriteOrigin.MANUAL
        }
        beforeEvictedWrite.pageVersion shouldBe store.snapshot(PAGE).pageVersion

        // Matrix: an AUTO retry while MANUAL owns is denied (AUTO never
        // preempts MANUAL); the denial names the owner.
        val autoRetry = store.tryAcquirePageStageLease(PAGE, PageStage.Ocr, PageWriteOrigin.AUTO)
            .shouldBeInstanceOf<LeaseAcquisition.Denied>()
        withClue("D1: an auto request must be denied while MANUAL owns the page") {
            autoRetry.owner shouldBe PageWriteOrigin.MANUAL
        }

        // (c) The evicted AUTO side's release/cancel is origin-checked: it can
        // NOT remove MANUAL's lease.
        store.releasePageStageLease(PAGE, PageWriteOrigin.AUTO)
        withClue("D1: the evicted auto side's release must not remove the manual lease") {
            store.pageLeaseOwner(PAGE) shouldBe PageWriteOrigin.MANUAL
        }
        store.cancelPageStageWork(PAGE, PageWriteOrigin.AUTO) shouldBe false
        withClue("D1: the evicted auto side's cancel must not remove the manual lease") {
            store.pageLeaseOwner(PAGE) shouldBe PageWriteOrigin.MANUAL
        }

        // Sanity: MANUAL still writes under its own token, and its release
        // frees the page normally.
        val manualWrite = store.patchPage(
            PAGE,
            ChapterTranslationStore.PatchPrecondition(
                generation = manual.generation,
                pageVersion = manual.pageVersion,
                leaseToken = manual.token,
            ),
            "manual boundary write after eviction",
        ) { page -> page!!.apply { errorMessage = null } }
        manualWrite.shouldBeInstanceOf<ChapterTranslationStore.PatchResult.Accepted>()
        store.releasePageStageLease(PAGE, PageWriteOrigin.MANUAL)
        store.pageLeaseOwner(PAGE).shouldBeNull()
    }

    private fun store(): ChapterTranslationStore = ChapterTranslationStore(
        translationFile = null,
        fileCreator = null,
        initialPages = mapOf(PAGE to PageTranslation(sourceFileName = PAGE)),
    )

    private companion object {
        const val PAGE = "p0"
    }
}
