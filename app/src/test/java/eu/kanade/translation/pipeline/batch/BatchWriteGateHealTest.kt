package eu.kanade.translation.pipeline.batch

import com.hippo.unifile.FakeUniFile
import com.hippo.unifile.UniFile
import eu.kanade.translation.model.BatchExpectedFingerprints
import eu.kanade.translation.model.BatchStage
import eu.kanade.translation.model.PageStage
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.persistence.artifact.AtomicChapterDocuments
import eu.kanade.translation.persistence.artifact.ChapterArtifactEngine
import eu.kanade.translation.persistence.artifact.ChapterArtifactLayout
import eu.kanade.translation.persistence.artifact.UniFileChapterDocumentIo
import eu.kanade.translation.persistence.chapter.ChapterTranslationStore
import eu.kanade.translation.pipeline.LeaseAcquisition
import eu.kanade.translation.pipeline.PageWriteOrigin
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldNotBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 *  device fix (Chapter-21 batch failure): the batch write gate's cached
 * identity went stale behind an ungated store write, and the resulting CAS
 * rejection escalated into a whole-envelope TERMINAL provider failure without
 * a single provider call. These tests pin the gate's new same-lease drift
 * heal: a precondition miss while the batch STILL HOLDS the page lease
 * re-syncs from the live snapshot and retries ONCE; a lease owned by another
 * origin ( manual fence) must keep rejecting.
 */
class BatchWriteGateHealTest {

    @TempDir
    lateinit var mangaDir: File

    private fun root(): UniFile = FakeUniFile(parent = null, backing = mangaDir)

    private fun artifactStore(): ChapterArtifactEngine =
        ChapterArtifactEngine(
            AtomicChapterDocuments(UniFileChapterDocumentIo(root())),
            ChapterArtifactLayout("Chapter 1"),
        )

    private fun lazyStore(): ChapterTranslationStore {
        artifactStore() // establish the artifact layout under the temp root
        return ChapterTranslationStore.lazy(
            fileCreator = { root().createFile("Chapter 1.json")!! },
            artifactParent = root(),
            artifactFileName = "Chapter 1.json",
        )
    }

    private fun newGate(
        store: ChapterTranslationStore,
        identities: ConcurrentHashMap<String, BatchWriteIdentity>,
    ) = BatchWriteGate(
        store = store,
        batchWriteIdentities = identities,
        durableFailurePageKeys = mutableSetOf(),
        expectedBatchFingerprints = BatchExpectedFingerprints(),
        stampBatchProvenance = { page, _ -> page },
        releaseBatchPageLeaseFn = { s, pageKey -> s.releasePageStageLease(pageKey, PageWriteOrigin.BATCH) },
        persistPageWithOomRecoveryFn = { _, _, _, _ -> error("not used by the heal tests") },
    )

    /** Acquires the BATCH translation lease and registers the gate identity exactly like the lane does. */
    private suspend fun acquireBatchIdentity(
        store: ChapterTranslationStore,
        identities: ConcurrentHashMap<String, BatchWriteIdentity>,
        pageKey: String,
    ) {
        store.preRegisterPages(listOf(pageKey))
        val lease = store.tryAcquirePageStageLease(pageKey, PageStage.Translation, PageWriteOrigin.BATCH)
            .shouldBeInstanceOf<LeaseAcquisition.Granted>().lease
        identities[pageKey] = BatchWriteIdentity(
            generation = lease.generation,
            pageVersion = lease.pageVersion,
            leaseToken = lease.token,
            candidateGenerationId = lease.candidateGenerationId,
            dependencyFingerprint = lease.dependencyFingerprint,
            artifactPageVersion = lease.artifactPageVersion,
        )
    }

    @Test
    fun `same-lease identity drift heals on the next guarded write`() = runTest {
        val store = lazyStore()
        val identities = ConcurrentHashMap<String, BatchWriteIdentity>()
        acquireBatchIdentity(store, identities, "001.jpg")
        val gate = newGate(store, identities)

        gate.guardedBatchUpdate("001.jpg", "seed running", BatchStage.TRANSLATION) { page ->
            (page ?: PageTranslation(sourceFileName = "001.jpg")).apply {
                translationStatus = StageStatus.RUNNING
            }
        }.shouldBeInstanceOf<ChapterTranslationStore.PatchResult.Accepted>()

        // The drift source from the device failure: an UNGATED writer (e.g.
        // the reader stranded-page sweep) bumps the store version without the
        // gate's knowledge while the batch lease is untouched.
        store.updatePageFromCurrentSnapshot("001.jpg", "reader stranded-page sweep") { page ->
            page ?: PageTranslation(sourceFileName = "001.jpg")
        }.shouldBeInstanceOf<ChapterTranslationStore.PatchResult.Accepted>()
        store.snapshot("001.jpg").pageVersion shouldBe (identities["001.jpg"]!!.pageVersion + 1)

        // Before the fix this rejected with "pageVersion expected=X actual=X+1"
        // and the translate admission poisoned the whole envelope TERMINAL.
        val healed = gate.guardedBatchUpdate("001.jpg", "batch translation running", BatchStage.TRANSLATION) { page ->
            page!!.apply { translationStatus = StageStatus.RUNNING }
        }
        healed.shouldBeInstanceOf<ChapterTranslationStore.PatchResult.Accepted>()
        // The healed accept also re-syncs the cached identity.
        store.snapshot("001.jpg").pageVersion shouldBe identities["001.jpg"]!!.pageVersion
    }

    @Test
    fun `a foreign lease owner still rejects - the manual fence is never preempted`() = runTest {
        val store = lazyStore()
        val identities = ConcurrentHashMap<String, BatchWriteIdentity>()
        acquireBatchIdentity(store, identities, "001.jpg")
        val gate = newGate(store, identities)

        // The batch releases its lease and the MANUAL lane takes the page
        // ( coexistence: batch must never preempt a manual owner).
        store.releasePageStageLease("001.jpg", PageWriteOrigin.BATCH)
        store.tryAcquirePageStageLease("001.jpg", PageStage.Translation, PageWriteOrigin.MANUAL)
            .shouldBeInstanceOf<LeaseAcquisition.Granted>()

        val rejected = gate.guardedBatchUpdate("001.jpg", "batch translation running", BatchStage.TRANSLATION) { page ->
            page!!.apply { translationStatus = StageStatus.RUNNING }
        }
        rejected.shouldNotBeInstanceOf<ChapterTranslationStore.PatchResult.Accepted>()
        // The heal must not have silently re-registered a foreign identity.
        store.snapshot("001.jpg").pageVersion shouldBe identities["001.jpg"]!!.pageVersion
    }

    @Test
    fun `a missing identity heals when the page has no active owner`() = runTest {
        val store = lazyStore()
        val identities = ConcurrentHashMap<String, BatchWriteIdentity>()
        val gate = newGate(store, identities)
        store.preRegisterPages(listOf("001.jpg"))

        val healed = gate.guardedBatchUpdate("001.jpg", "batch translation running", BatchStage.TRANSLATION) { page ->
            page ?: PageTranslation(sourceFileName = "001.jpg")
        }
        healed.shouldBeInstanceOf<ChapterTranslationStore.PatchResult.Accepted>()
        identities["001.jpg"].shouldNotBeNull()
        store.pageLeaseOwner("001.jpg") shouldBe PageWriteOrigin.BATCH

        store.releasePageStageLease("001.jpg", PageWriteOrigin.BATCH)
        store.closeAndFlush()
    }
}
