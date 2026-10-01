package eu.kanade.translation.pipeline.batch

import com.hippo.unifile.FakeUniFile
import com.hippo.unifile.UniFile
import eu.kanade.translation.model.AiPageProgressState
import eu.kanade.translation.model.PageStage
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.model.TranslationProgressStage
import eu.kanade.translation.persistence.chapter.ChapterTranslationStore
import eu.kanade.translation.persistence.chapter.LeaseAcquisition
import eu.kanade.translation.persistence.chapter.PageWriteOrigin
import eu.kanade.translation.pipeline.batch.progress.TranslationBatchProgressTracker
import eu.kanade.translation.pipeline.planning.BatchExpectedFingerprints
import eu.kanade.translation.pipeline.planning.BatchStage
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class BatchStaleTranslationPublicationTest {

    @TempDir
    lateinit var chapterDir: File

    private fun root(): UniFile = FakeUniFile(parent = null, backing = chapterDir)

    private fun store(dispatcher: CoroutineDispatcher): ChapterTranslationStore {
        val parent = root()
        return ChapterTranslationStore(
            translationFile = null,
            fileCreator = { parent.createFile("Chapter 1.json")!! },
            artifactParent = parent,
            artifactFileName = "Chapter 1.json",
            persistenceDispatcher = dispatcher,
        )
    }

    private fun gate(
        store: ChapterTranslationStore,
        identities: ConcurrentHashMap<String, BatchWriteIdentity>,
        beforeRetry: (suspend (String, ChapterTranslationStore.PageSnapshot) -> Unit)? = null,
    ) = BatchWriteGate(
        store = store,
        batchWriteIdentities = identities,
        durableFailurePageKeys = mutableSetOf(),
        expectedBatchFingerprints = BatchExpectedFingerprints(),
        stampBatchProvenance = { page, _ -> page },
        releaseBatchPageLeaseFn = { s, pageKey -> s.releasePageStageLease(pageKey, PageWriteOrigin.BATCH) },
        persistPageWithOomRecoveryFn = { _, _, _, _ -> error("translation completion uses guarded update") },
        beforeSameLeaseRetryHook = beforeRetry,
    )

    private suspend fun seedDurablePageAtVersion4(
        store: ChapterTranslationStore,
        gate: BatchWriteGate,
        identities: ConcurrentHashMap<String, BatchWriteIdentity>,
        durableTranslation: String,
    ) {
        store.preRegisterPages(listOf(PAGE))
        val lease = store.tryAcquirePageStageLease(PAGE, PageStage.Translation, PageWriteOrigin.BATCH)
            .shouldBeInstanceOf<LeaseAcquisition.Granted>().lease
        identities[PAGE] = BatchWriteIdentity(
            generation = lease.generation,
            pageVersion = lease.pageVersion,
            leaseToken = lease.token,
            candidateGenerationId = lease.candidateGenerationId,
            dependencyFingerprint = lease.dependencyFingerprint,
            artifactPageVersion = lease.artifactPageVersion,
        )

        gate.guardedBatchUpdate(PAGE, "seed durable translation", BatchStage.TRANSLATION) { current ->
            (current ?: PageTranslation(sourceFileName = PAGE)).apply {
                translationStatus = StageStatus.READY
                translationError = null
                blocks = mutableListOf(block(durableTranslation))
            }
        }.shouldBeInstanceOf<ChapterTranslationStore.PatchResult.Accepted>()

        // preRegister starts at version 1; the ready publication and these
        // two fenced writes make the stale cached identity exactly version 4.
        while (store.snapshot(PAGE).pageVersion < 4L) {
            gate.guardedBatchUpdate(PAGE, "advance page to v4", BatchStage.TRANSLATION) { current ->
                current!!.apply { inpaintRevision++ }
            }.shouldBeInstanceOf<ChapterTranslationStore.PatchResult.Accepted>()
        }
        store.flush()
        val snapshot = store.snapshot(PAGE)
        snapshot.pageVersion shouldBe 4L
        gate.refreshBatchIdentity(PAGE, snapshot)
        identities.getValue(PAGE).pageVersion shouldBe 4L
        // Production reader stores publish the live page first and coalesce the
        // artifact write. That permits pageVersion to move while the artifact
        // identity remains unchanged, which is the reconciler's allowed case.
        store.enableLazyPersistence()
    }

    private suspend fun injectVersion4To6(store: ChapterTranslationStore) {
        val artifactVersion = store.snapshot(PAGE).artifactPageVersion
        repeat(2) {
            store.updatePageFromCurrentSnapshot(PAGE, "same-run timestamp drift") { current ->
                current!!
            }.shouldBeInstanceOf<ChapterTranslationStore.PatchResult.Accepted>()
        }
        val snapshot = store.snapshot(PAGE)
        snapshot.pageVersion shouldBe 6L
        snapshot.artifactPageVersion shouldBe artifactVersion
    }

    private suspend fun completeWithVersion4To6To7Race(
        store: ChapterTranslationStore,
        identities: ConcurrentHashMap<String, BatchWriteIdentity>,
        candidate: PageTranslation,
        makeVersion7IdentityDrift: Boolean = false,
    ): BatchTranslationPublication {
        injectVersion4To6(store)
        val gate = gate(store, identities) { pageKey, refreshed ->
            pageKey shouldBe PAGE
            refreshed.pageVersion shouldBe 6L
            refreshed.leaseToken shouldBe identities.getValue(PAGE).leaseToken
            store.updatePageFromCurrentSnapshot(PAGE, "same-token retry version drift") { current ->
                current!!
            }.shouldBeInstanceOf<ChapterTranslationStore.PatchResult.Accepted>()
            val snapshot = store.snapshot(PAGE)
            snapshot.pageVersion shouldBe 7L
            snapshot.artifactPageVersion shouldBe refreshed.artifactPageVersion
            if (makeVersion7IdentityDrift) {
                store.releasePageStageLease(PAGE, PageWriteOrigin.BATCH)
                store.tryAcquirePageStageLease(PAGE, PageStage.Translation, PageWriteOrigin.MANUAL)
                    .shouldBeInstanceOf<LeaseAcquisition.Granted>()
            }
        }
        return gate.publishTranslationCompletion(PAGE, candidate)
    }

    @Test
    fun `version 4 to 6 to 7 race recognizes identical durable output as superseded success`(): Unit = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = store(dispatcher)
        val identities = ConcurrentHashMap<String, BatchWriteIdentity>()
        try {
            seedDurablePageAtVersion4(store, gate(store, identities), identities, durableTranslation = "same")
            val pageVersionBeforeCompletion = store.snapshot(PAGE).pageVersion

            completeWithVersion4To6To7Race(store, identities, page("same"))
                .shouldBeInstanceOf<BatchTranslationPublication.Superseded>()

            store.snapshot(PAGE).page!!.blocks.single().translation shouldBe "same"
            store.snapshot(PAGE).pageVersion shouldBe pageVersionBeforeCompletion + 3L
        } finally {
            store.closeAndFlush()
        }
    }

    @Test
    fun `identical in-memory output without a durable manifest record is rebased`(): Unit = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = store(dispatcher)
        val identities = ConcurrentHashMap<String, BatchWriteIdentity>()
        try {
            seedDurablePageAtVersion4(store, gate(store, identities), identities, durableTranslation = "old")
            store.updatePageFromCurrentSnapshot(PAGE, "stage candidate output in memory") { current ->
                current!!.apply { blocks.single().translation = "new" }
            }.shouldBeInstanceOf<ChapterTranslationStore.PatchResult.Accepted>()
            val staged = store.snapshot(PAGE)
            staged.page!!.blocks.single().translation shouldBe "new"
            staged.artifactPageVersion shouldBe identities.getValue(PAGE).artifactPageVersion

            val gate = gate(store, identities) { pageKey, refreshed ->
                pageKey shouldBe PAGE
                refreshed.pageVersion shouldBe staged.pageVersion
                store.updatePageFromCurrentSnapshot(PAGE, "same-token retry version drift") { current ->
                    current!!
                }.shouldBeInstanceOf<ChapterTranslationStore.PatchResult.Accepted>()
                store.snapshot(PAGE).pageVersion shouldBe staged.pageVersion + 1L
            }
            gate.publishTranslationCompletion(PAGE, page("new"))
                .shouldBeInstanceOf<BatchTranslationPublication.Rebased>()
            store.snapshot(PAGE).page!!.blocks.single().translation shouldBe "new"
            store.flush()
        } finally {
            store.closeAndFlush()
        }
    }

    @Test
    fun `version 4 to 6 to 7 race rebases translation fields over the current page`(): Unit = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = store(dispatcher)
        val identities = ConcurrentHashMap<String, BatchWriteIdentity>()
        try {
            seedDurablePageAtVersion4(store, gate(store, identities), identities, durableTranslation = "old")

            completeWithVersion4To6To7Race(store, identities, page("new"))
                .shouldBeInstanceOf<BatchTranslationPublication.Rebased>()

            store.snapshot(PAGE).page!!.blocks.single().translation shouldBe "new"
            store.flush()
        } finally {
            store.closeAndFlush()
        }
    }

    @Test
    fun `version drift with a changed lease owner stays deferred and pending`(): Unit = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = store(dispatcher)
        val identities = ConcurrentHashMap<String, BatchWriteIdentity>()
        try {
            seedDurablePageAtVersion4(store, gate(store, identities), identities, durableTranslation = "old")

            val result = completeWithVersion4To6To7Race(
                store = store,
                identities = identities,
                candidate = page("new"),
                makeVersion7IdentityDrift = true,
            )
            result.shouldBeInstanceOf<BatchTranslationPublication.Deferred>()

            val storePage = store.snapshot(PAGE).page!!
            storePage.translationStatus shouldBe StageStatus.READY
            storePage.blocks.single().translation shouldBe "old"
            storePage.inpaintRevision shouldBe 2

            val tracker = TranslationBatchProgressTracker(1L, store, listOf(PAGE), this)
            val outcome = recordTranslationPublicationOutcome(PAGE, result, tracker)
            runCurrent()
            outcome.shouldBeInstanceOf<eu.kanade.translation.pipeline.execution.TranslationCompletionOutcome.PersistenceRejected>()
            tracker.snapshot.value.pages.single().stage shouldBe TranslationProgressStage.QUEUED
            tracker.snapshot.value.pages.single().aiState shouldBe AiPageProgressState.PAUSED
            tracker.close()
        } finally {
            store.closeAndFlush()
        }
    }

    @Test
    fun `typed artifact rejection fails but the same untyped wording defers`(): Unit = runTest {
        val store = ChapterTranslationStore(null, null)
        store.preRegisterPages(listOf(PAGE))
        val tracker = TranslationBatchProgressTracker(1L, store, listOf(PAGE), this)

        val publication = classifyTranslationPublicationRejection(
            ChapterTranslationStore.PatchResult.Rejected(
                "artifact write refused",
                ChapterTranslationStore.PatchResult.Rejected.Detail.ArtifactPublicationFailed,
            ),
        )
        publication.shouldBeInstanceOf<BatchTranslationPublication.Failed>()
        val outcome = recordTranslationPublicationOutcome(PAGE, publication, tracker)
        runCurrent()

        outcome.shouldBeInstanceOf<eu.kanade.translation.pipeline.execution.TranslationCompletionOutcome.PersistenceRejected>()
        tracker.snapshot.value.pages.single().stage shouldBe TranslationProgressStage.FAILED
        tracker.snapshot.value.pages.single().aiState shouldBe AiPageProgressState.FAILED

        classifyTranslationPublicationRejection(
            ChapterTranslationStore.PatchResult.Rejected("ARTIFACT_PUBLICATION_FAILED"),
        ).shouldBeInstanceOf<BatchTranslationPublication.Deferred>()
        tracker.close()
    }

    private fun page(translation: String): PageTranslation = PageTranslation(
        sourceFileName = PAGE,
        translationStatus = StageStatus.READY,
        blocks = mutableListOf(block(translation)),
    )

    private fun block(translation: String) = TranslationBlock(
        blockId = "block-1",
        text = "source",
        translation = translation,
        width = 10f,
        height = 10f,
        x = 1f,
        y = 1f,
        symHeight = 10f,
        symWidth = 10f,
        angle = 0f,
    )

    private companion object {
        const val PAGE = "001.jpg"
    }
}
