package eu.kanade.translation.pipeline

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.persistence.ChapterTranslationStore
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Deterministic host coverage for the operation-scoped [AttemptAuthority]
 * (plan §4): mutex serialization of TWO concurrent branch writers, accepted
 * receipt advancement, foreign-writer rejection, caller-cancellation
 * suppression and idempotent first-terminal-wins markers — against the REAL
 * [ChapterTranslationStore] patch path and its receipts, so no test fake
 * duplicates the policy under test.
 */
class AttemptAuthorityTest {

    private fun newStore(): ChapterTranslationStore = ChapterTranslationStore(
        artifactParentResolver = null,
        initialPages = emptyMap(),
    )

    private suspend fun seededAuthority(
        store: ChapterTranslationStore,
        pageKey: String = PAGE,
        callerJob: Job? = null,
    ): AttemptAuthority {
        store.updatePage(pageKey) { PageTranslation(sourceFileName = pageKey) }
        return AttemptAuthority(store, pageKey, store.snapshot(pageKey).toPrecondition(), callerJob)
    }

    @Test
    fun `two concurrent branch writers serialize and merge field-specific`() = runTest {
        val store = newStore()
        val attempt = seededAuthority(store)
        val baseVersion = attempt.precondition.pageVersion

        val translationBranch = async {
            attempt.write("branch translation publication") { it.copy(translationStatus = StageStatus.READY) }
        }
        val nativeBranch = async {
            attempt.write("branch inpaint publication") { it.copy(inpaintStatus = StageStatus.READY) }
        }

        translationBranch.await().shouldBeInstanceOf<ChapterTranslationStore.PatchResult.Accepted>()
        nativeBranch.await().shouldBeInstanceOf<ChapterTranslationStore.PatchResult.Accepted>()

        // Both branch effects are durable and neither replaced the other:
        // the merged page carries BOTH field updates, advanced one version at a time.
        val page = store.snapshot(PAGE).page
        page?.translationStatus shouldBe StageStatus.READY
        page?.inpaintStatus shouldBe StageStatus.READY
        store.snapshot(PAGE).pageVersion shouldBe baseVersion + 2
        attempt.closed shouldBe false
    }

    @Test
    fun `accepted receipt advances the fenced precondition`() = runTest {
        val store = newStore()
        val attempt = seededAuthority(store)

        attempt.precondition.pageVersion shouldBe store.snapshot(PAGE).pageVersion

        attempt.write("first stage write") { it.copy(ocrStatus = StageStatus.READY) }
            .shouldBeInstanceOf<ChapterTranslationStore.PatchResult.Accepted>()
        attempt.precondition.pageVersion shouldBe store.snapshot(PAGE).pageVersion

        attempt.write("second stage write") { it.copy(inpaintStatus = StageStatus.READY) }
            .shouldBeInstanceOf<ChapterTranslationStore.PatchResult.Accepted>()
        attempt.precondition.pageVersion shouldBe store.snapshot(PAGE).pageVersion
    }

    @Test
    fun `foreign writer rejection freezes the precondition`() = runTest {
        val store = newStore()
        val attempt = seededAuthority(store)

        val foreign = store.patchPage(
            PAGE,
            store.snapshot(PAGE).toPrecondition(),
            "foreign writer",
        ) { it!!.copy(ocrStatus = StageStatus.READY) }
        foreign.shouldBeInstanceOf<ChapterTranslationStore.PatchResult.Accepted>()

        val stalePrecondition = attempt.precondition
        val result = attempt.write("stale attempt write") { it.copy(translationStatus = StageStatus.READY) }

        result.shouldBeInstanceOf<ChapterTranslationStore.PatchResult.Rejected>()
        attempt.precondition shouldBe stalePrecondition
        attempt.closed shouldBe false
    }

    @Test
    fun `cancelled caller job suppresses writes without touching the store`() = runTest {
        val store = newStore()
        val callerJob = Job()
        val attempt = seededAuthority(store, callerJob = callerJob)
        val baseVersion = store.snapshot(PAGE).pageVersion
        callerJob.cancel()

        val result = attempt.write("post-cancel branch write") { it.copy(ocrStatus = StageStatus.READY) }

        result.shouldBeInstanceOf<ChapterTranslationStore.PatchResult.Rejected>()
        (result as ChapterTranslationStore.PatchResult.Rejected).reason shouldBe
            "attempt closed (post-cancel branch write)"
        store.snapshot(PAGE).pageVersion shouldBe baseVersion
        attempt.closed shouldBe false
    }

    @Test
    fun `markTimeout is first-terminal-wins and idempotent`() = runTest {
        val store = newStore()
        val attempt = seededAuthority(store)
        val baseVersion = store.snapshot(PAGE).pageVersion

        val marker = attempt.markTimeout { it.copy(inpaintStatus = StageStatus.FAILED, inpaintError = "timed out") }
        marker.shouldBeInstanceOf<ChapterTranslationStore.PatchResult.Accepted>()
        attempt.closed shouldBe true
        // AA-01: the terminal Accepted receipt advances the fenced precondition too.
        attempt.precondition.pageVersion shouldBe store.snapshot(PAGE).pageVersion

        attempt.write("after close") { it.copy(translationStatus = StageStatus.READY) }
            .shouldBeInstanceOf<ChapterTranslationStore.PatchResult.Rejected>()

        val secondMarker = attempt.markTimeout { it.copy(inpaintStatus = StageStatus.FAILED) }
        secondMarker.shouldBeInstanceOf<ChapterTranslationStore.PatchResult.Rejected>()
        (secondMarker as ChapterTranslationStore.PatchResult.Rejected).reason shouldBe "attempt already closed"

        // Exactly ONE marker mutation reached the store; the idempotent loser touched nothing.
        store.snapshot(PAGE).pageVersion shouldBe baseVersion + 1
        store.snapshot(PAGE).page?.inpaintError shouldBe "timed out"
    }

    @Test
    fun `terminal marker runs even when the caller job is cancelled`() = runTest {
        val store = newStore()
        val callerJob = Job()
        val attempt = seededAuthority(store, callerJob = callerJob)
        callerJob.cancel()

        val marker = attempt.markTimeout { it.copy(inpaintStatus = StageStatus.FAILED) }

        marker.shouldBeInstanceOf<ChapterTranslationStore.PatchResult.Accepted>()
        attempt.closed shouldBe true
        store.snapshot(PAGE).page?.inpaintStatus shouldBe StageStatus.FAILED
    }

    @Test
    fun `markTimeout against a foreign-advanced page still closes the attempt`() = runTest {
        val store = newStore()
        val attempt = seededAuthority(store)

        store.patchPage(PAGE, store.snapshot(PAGE).toPrecondition(), "foreign writer") {
            it!!.copy(ocrStatus = StageStatus.READY)
        }.shouldBeInstanceOf<ChapterTranslationStore.PatchResult.Accepted>()

        val marker = attempt.markTimeout { it.copy(inpaintStatus = StageStatus.FAILED) }
        marker.shouldBeInstanceOf<ChapterTranslationStore.PatchResult.Rejected>()
        attempt.closed shouldBe true

        attempt.write("after lost marker") { it.copy(translationStatus = StageStatus.READY) }
            .shouldBeInstanceOf<ChapterTranslationStore.PatchResult.Rejected>()
    }

    @Test
    fun `cancelled caller closes its authority and cancels only running stages`() = runTest {
        val store = newStore()
        val caller = Job()
        store.updatePage(PAGE) {
            PageTranslation(
                sourceFileName = PAGE,
                ocrStatus = StageStatus.RUNNING,
                inpaintStatus = StageStatus.RUNNING,
                translationStatus = StageStatus.READY,
                renderStatus = StageStatus.PARTIAL,
                retryCount = 2,
            ).apply { attemptCount = 3 }
        }
        val attempt = AttemptAuthority(store, PAGE, store.snapshot(PAGE).toPrecondition(), caller)
        caller.cancel()
        val marker = attempt.markCancelled()
        marker.shouldBeInstanceOf<ChapterTranslationStore.PatchResult.Accepted>()
        val page = store.snapshot(PAGE).page
        page?.ocrStatus shouldBe StageStatus.CANCELLED
        page?.inpaintStatus shouldBe StageStatus.CANCELLED
        page?.translationStatus shouldBe StageStatus.READY
        page?.renderStatus shouldBe StageStatus.PARTIAL
        page?.retryCount shouldBe 2
        page?.attemptCount shouldBe 3
        attempt.closed shouldBe true
        attempt.write("late publication") { it.copy(ocrStatus = StageStatus.READY) }
            .shouldBeInstanceOf<ChapterTranslationStore.PatchResult.Rejected>()
    }

    @Test
    fun `cancel marker cannot overwrite a replacement and still closes old authority`() = runTest {
        val store = newStore()
        val caller = Job()
        val attempt = seededAuthority(store, callerJob = caller)
        store.patchPage(PAGE, store.snapshot(PAGE).toPrecondition(), "replacement") {
            it!!.copy(ocrStatus = StageStatus.RUNNING)
        }.shouldBeInstanceOf<ChapterTranslationStore.PatchResult.Accepted>()
        val replacement = store.snapshot(PAGE)
        caller.cancel()
        attempt.markCancelled().shouldBeInstanceOf<ChapterTranslationStore.PatchResult.Rejected>()
        store.snapshot(PAGE).pageVersion shouldBe replacement.pageVersion
        store.snapshot(PAGE).page?.ocrStatus shouldBe StageStatus.RUNNING
        attempt.closed shouldBe true
        attempt.write("late old publication") { it.copy(ocrStatus = StageStatus.READY) }
            .shouldBeInstanceOf<ChapterTranslationStore.PatchResult.Rejected>()
    }

    @Test
    fun `active caller cannot publish an operation cancellation marker`() = runTest {
        val store = newStore()
        val caller = Job()
        store.updatePage(PAGE) { PageTranslation(sourceFileName = PAGE, ocrStatus = StageStatus.RUNNING) }
        val snapshot = store.snapshot(PAGE)
        val attempt = AttemptAuthority(store, PAGE, snapshot.toPrecondition(), caller)
        attempt.markCancelled().shouldBeInstanceOf<ChapterTranslationStore.PatchResult.Rejected>()
        store.snapshot(PAGE).pageVersion shouldBe snapshot.pageVersion
        store.snapshot(PAGE).page?.ocrStatus shouldBe StageStatus.RUNNING
        attempt.closed shouldBe false
    }

    private companion object {
        const val PAGE = "p0"
    }
}
