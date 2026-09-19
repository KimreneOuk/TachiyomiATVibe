package eu.kanade.translation.pipeline.batch

import com.hippo.unifile.FakeUniFile
import com.hippo.unifile.UniFile
import eu.kanade.translation.ChapterTranslationStore
import eu.kanade.translation.LeaseAcquisition
import eu.kanade.translation.OcrStagePatch
import eu.kanade.translation.PageWriteOrigin
import eu.kanade.translation.StagePatchResult
import eu.kanade.translation.artifact.AtomicChapterDocuments
import eu.kanade.translation.artifact.ChapterArtifactLayout
import eu.kanade.translation.artifact.ChapterArtifactEngine
import eu.kanade.translation.artifact.UniFileChapterDocumentIo
import eu.kanade.translation.model.BatchExpectedFingerprints
import eu.kanade.translation.model.BatchStage
import eu.kanade.translation.model.InpaintMaskBox
import eu.kanade.translation.model.PageStage
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.ocrBlockFingerprints
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldNotBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * T934 R1 (lease abort/re-work fix): the T1→T2 token flip observed on device
 * (pages 047/052). The envelope executor's `finally` released the shared BATCH
 * slot while the overlap inpaint still held its write identity on the SAME
 * token; the next acquire minted a fresh token and every admission write
 * fenced on the old one rejected ("page lease token changed") into a full
 * candidate abort + re-work. Pins both halves of the fix:
 *  - R1.2 the attach-aware envelope release: a release with a sibling
 *    same-origin attach keeps the record + token for the surviving writer;
 *  - R1.1 the owner-proof heal in [BatchWriteGate.guardedBatchUpdate]: a flip
 *    that still slips through re-acquires the BATCH lease and retries ONCE,
 *    never across a MANUAL/AUTO owner (the T917 fence) and never across a
 *    resumed-run identity change (candidateGenerationId mismatch).
 */
class BatchLeaseFlipHealTest {

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
        persistPageWithOomRecoveryFn = { _, _, _, _ -> error("not used by the flip tests") },
    )

    /** Acquires the BATCH lease and registers the identity exactly like the envelope/overlap lanes do. */
    private suspend fun registerBatchIdentity(
        store: ChapterTranslationStore,
        identities: ConcurrentHashMap<String, BatchWriteIdentity>,
        pageKey: String,
        stage: PageStage,
    ): Long {
        val lease = store.tryAcquirePageStageLease(pageKey, stage, PageWriteOrigin.BATCH)
            .shouldBeInstanceOf<LeaseAcquisition.Granted>().lease
        identities[pageKey] = BatchWriteIdentity(
            generation = lease.generation,
            pageVersion = lease.pageVersion,
            leaseToken = lease.token,
            candidateGenerationId = lease.candidateGenerationId,
            dependencyFingerprint = lease.dependencyFingerprint,
            artifactPageVersion = lease.artifactPageVersion,
        )
        return lease.token
    }

    private fun hex64(tag: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(tag.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    private fun block(text: String) = TranslationBlock(
        blockId = "b1",
        text = text,
        translation = "",
        width = 10f,
        height = 10f,
        x = 0f,
        y = 0f,
        symHeight = 1f,
        symWidth = 1f,
        angle = 0f,
    )

    private fun ocrPage(pageKey: String, text: String) = PageTranslation(
        sourceFileName = pageKey,
        blocks = mutableListOf(block(text)),
        imgWidth = 100f,
        imgHeight = 160f,
        decodeSampleSize = 1,
        ocrStatus = StageStatus.READY,
        inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION,
        sourceFingerprint = hex64("source-$pageKey"),
        detectionFingerprint = hex64("detection-$pageKey"),
        ocrFingerprint = hex64("ocr-$pageKey"),
        inpaintMaskBoxes = listOf(InpaintMaskBox(0, 0, 10, 10, 1)),
    )

    /** Seeds a real artifact candidate (the OCR merge creates it) so snapshots carry a candidateGenerationId. */
    private suspend fun seedOcrCandidate(store: ChapterTranslationStore, pageKey: String) {
        store.preRegisterPages(listOf(pageKey))
        val lease = store.tryAcquirePageStageLease(pageKey, PageStage.Ocr, PageWriteOrigin.BATCH)
            .shouldBeInstanceOf<LeaseAcquisition.Granted>().lease
        val before = store.snapshot(pageKey)
        store.mergeOcr(
            OcrStagePatch(
                pageKey = pageKey,
                generation = before.generation,
                expectedPageVersion = before.pageVersion,
                expectedPriorOcrFingerprints = before.page?.ocrBlockFingerprints().orEmpty(),
                ocrResult = ocrPage(pageKey, "source-$pageKey"),
                expectedLeaseToken = lease.token,
            ),
            description = "t934 flip test ocr seed",
        ).shouldBeInstanceOf<StagePatchResult.Accepted>()
        store.releasePageStageLease(pageKey, PageWriteOrigin.BATCH)
        store.snapshot(pageKey).candidateGenerationId.shouldNotBeNull()
    }

    // ------------------------------------------------------------------
    // R1.2 — the flip source: envelope completion must not invalidate a live
    // overlap identity.
    // ------------------------------------------------------------------

    @Test
    fun `t1-to-t2 reproduction - envelope release keeps a re-attached overlap identity alive`() = runTest {
        val store = lazyStore()
        val pageKey = "001.jpg"
        store.preRegisterPages(listOf(pageKey))
        val identities = ConcurrentHashMap<String, BatchWriteIdentity>()
        val gate = newGate(store, identities)

        // The envelope acquires the BATCH translation slot (T1) and the
        // overlap inpaint re-attaches to the SAME token, stamping its write
        // identity on it (OverlapScheduler admission).
        val envelopeToken = registerBatchIdentity(store, identities, pageKey, PageStage.Translation)
        val overlapToken = registerBatchIdentity(store, identities, pageKey, PageStage.Inpaint)
        overlapToken shouldBe envelopeToken

        // Envelope completion releases through the ATTACH-AWARE release: with
        // a live sibling attach the record (and token) must survive.
        store.releasePageStageLeaseIfUnattached(pageKey, PageWriteOrigin.BATCH, envelopeToken) shouldBe false
        store.pageLeaseOwner(pageKey) shouldBe PageWriteOrigin.BATCH
        store.snapshot(pageKey).leaseToken shouldBe overlapToken

        // The live overlap identity still fences writes — accepted WITHOUT
        // any heal; a sibling batch acquire re-grants the SAME token instead
        // of minting a flip token.
        gate.guardedBatchUpdate(pageKey, "overlap inpaint running", BatchStage.INPAINT) { page ->
            page!!.apply { inpaintStatus = StageStatus.RUNNING }
        }.shouldBeInstanceOf<ChapterTranslationStore.PatchResult.Accepted>()
        val sibling = store.tryAcquirePageStageLease(pageKey, PageStage.Translation, PageWriteOrigin.BATCH)
            .shouldBeInstanceOf<LeaseAcquisition.Granted>().lease
        sibling.token shouldBe overlapToken

        // The overlap settles and releases (plain release): the slot clears.
        store.releasePageStageLease(pageKey, PageWriteOrigin.BATCH)
        store.pageLeaseOwner(pageKey) shouldBe null

        // An envelope hold with NO attach still releases (no leak)...
        val loneToken = store.tryAcquirePageStageLease(pageKey, PageStage.Translation, PageWriteOrigin.BATCH)
            .shouldBeInstanceOf<LeaseAcquisition.Granted>().lease.token
        store.releasePageStageLeaseIfUnattached(pageKey, PageWriteOrigin.BATCH, loneToken) shouldBe true
        store.pageLeaseOwner(pageKey) shouldBe null
        // ...and a STALE envelope release (the slot moved on) is a no-op that
        // leaves the new holder's record intact.
        val freshToken = store.tryAcquirePageStageLease(pageKey, PageStage.Translation, PageWriteOrigin.BATCH)
            .shouldBeInstanceOf<LeaseAcquisition.Granted>().lease.token
        freshToken shouldNotBe loneToken
        store.releasePageStageLeaseIfUnattached(pageKey, PageWriteOrigin.BATCH, loneToken) shouldBe false
        store.snapshot(pageKey).leaseToken shouldBe freshToken
        store.releasePageStageLease(pageKey, PageWriteOrigin.BATCH)

        // T934 flake hardening (diagnosis §4): flush and cancel the store's
        // persistScope (debounced persist) so @TempDir's recursive delete
        // cannot race it on Windows.
        store.closeAndFlush()
    }

    // ------------------------------------------------------------------
    // R1.1 — the owner-proof heal for a flip that slipped through.
    // ------------------------------------------------------------------

    @Test
    fun `a flipped batch token heals via owner-proof re-acquire and the write succeeds`() = runTest {
        val store = lazyStore()
        val pageKey = "001.jpg"
        store.preRegisterPages(listOf(pageKey))
        val identities = ConcurrentHashMap<String, BatchWriteIdentity>()
        val gate = newGate(store, identities)

        // A writer holds T1 (its identity is registered), the envelope's
        // release dropped its co-hold, and a sibling batch component re-minted
        // the now-empty slot (T2, still held) — the residual flip.
        val staleToken = registerBatchIdentity(store, identities, pageKey, PageStage.Translation)
        store.releasePageStageLease(pageKey, PageWriteOrigin.BATCH)
        val siblingToken = store.tryAcquirePageStageLease(pageKey, PageStage.Inpaint, PageWriteOrigin.BATCH)
            .shouldBeInstanceOf<LeaseAcquisition.Granted>().lease.token
        siblingToken shouldNotBe staleToken

        // The stale-token write rejects ("page lease token changed"); the
        // heal's BATCH re-acquire is GRANTED with the SAME run identity, so
        // the identity re-arms on the granted token and the write retries once.
        gate.guardedBatchUpdate(pageKey, "overlap inpaint running", BatchStage.INPAINT) { page ->
            page!!.apply { inpaintStatus = StageStatus.RUNNING }
        }.shouldBeInstanceOf<ChapterTranslationStore.PatchResult.Accepted>()

        identities[pageKey]!!.leaseToken shouldBe siblingToken
        store.snapshot(pageKey).leaseToken shouldBe siblingToken

        // T934 flake hardening (diagnosis §4): flush and cancel the store's
        // persistScope (debounced persist) so @TempDir's recursive delete
        // cannot race it on Windows.
        store.closeAndFlush()
    }

    @Test
    fun `a manual owner denies the owner-proof heal - rejection kept with no write`() = runTest {
        val store = lazyStore()
        val pageKey = "001.jpg"
        store.preRegisterPages(listOf(pageKey))
        val identities = ConcurrentHashMap<String, BatchWriteIdentity>()
        val gate = newGate(store, identities)

        val staleToken = registerBatchIdentity(store, identities, pageKey, PageStage.Translation)
        // The batch slot was released and a MANUAL owner took the page (T917).
        store.releasePageStageLease(pageKey, PageWriteOrigin.BATCH)
        store.tryAcquirePageStageLease(pageKey, PageStage.Translation, PageWriteOrigin.MANUAL)
            .shouldBeInstanceOf<LeaseAcquisition.Granted>()
        val versionBefore = store.snapshot(pageKey).pageVersion

        val rejected = gate.guardedBatchUpdate(pageKey, "batch translation running", BatchStage.TRANSLATION) { page ->
            page!!.apply { translationStatus = StageStatus.RUNNING }
        }
        rejected.shouldNotBeInstanceOf<ChapterTranslationStore.PatchResult.Accepted>()

        // NO write, NO identity re-arm, the MANUAL owner untouched: the heal's
        // re-acquire was DENIED (real contention) and the rejection is kept.
        store.snapshot(pageKey).pageVersion shouldBe versionBefore
        identities[pageKey]!!.leaseToken shouldBe staleToken
        store.pageLeaseOwner(pageKey) shouldBe PageWriteOrigin.MANUAL

        // Today's typed surfacing is unchanged: the lane's guardedWrite
        // conversion (BatchLaneWorkers inpaint path) raises the typed
        // persistence rejection — the family OverlapScheduler yields on
        // through its BatchContentionRejectedException subtype.
        var typed: BatchPersistenceRejectedException? = null
        if (rejected is ChapterTranslationStore.PatchResult.Rejected) {
            try {
                throw BatchPersistenceRejectedException(
                    pageKey = pageKey,
                    stage = BatchDiagnosticStage.INPAINT,
                )
            } catch (e: BatchPersistenceRejectedException) {
                typed = e
            }
        }
        typed.shouldNotBeNull()
        typed.pageKey shouldBe pageKey

        // T934 flake hardening (diagnosis §4): flush and cancel the store's
        // persistScope (debounced persist) so @TempDir's recursive delete
        // cannot race it on Windows.
        store.closeAndFlush()
    }

    @Test
    fun `a resumed-run candidate identity denies the heal - no retry write`() = runTest {
        val store = lazyStore()
        val pageKey = "001.jpg"
        // A real artifact candidate exists, so the granted lease would carry
        // the LIVE candidateGenerationId.
        seedOcrCandidate(store, pageKey)

        val identities = ConcurrentHashMap<String, BatchWriteIdentity>()
        val gate = newGate(store, identities)
        // The writer's identity is a RESUMED run's: it references NO live
        // candidate (null) — it differs from the live candidate id by design.
        val lease = store.tryAcquirePageStageLease(pageKey, PageStage.Translation, PageWriteOrigin.BATCH)
            .shouldBeInstanceOf<LeaseAcquisition.Granted>().lease
        val staleToken = lease.token
        identities[pageKey] = BatchWriteIdentity(
            generation = lease.generation,
            pageVersion = lease.pageVersion,
            leaseToken = lease.token,
            candidateGenerationId = null,
            dependencyFingerprint = lease.dependencyFingerprint,
            artifactPageVersion = lease.artifactPageVersion,
        )
        // Flip: the writer's slot was released and a sibling re-minted it.
        store.releasePageStageLease(pageKey, PageWriteOrigin.BATCH)
        val siblingToken = store.tryAcquirePageStageLease(pageKey, PageStage.Inpaint, PageWriteOrigin.BATCH)
            .shouldBeInstanceOf<LeaseAcquisition.Granted>().lease.token
        siblingToken shouldNotBe staleToken
        val versionBefore = store.snapshot(pageKey).pageVersion

        // The token-changed rejection heals ONLY when the granted identity
        // matches: the granted lease carries the live candidateGenerationId,
        // which differs from the resumed-run identity — no re-arm, no retry.
        val rejected = gate.guardedBatchUpdate(pageKey, "overlap inpaint running", BatchStage.INPAINT) { page ->
            page!!.apply { inpaintStatus = StageStatus.RUNNING }
        }
        rejected.shouldNotBeInstanceOf<ChapterTranslationStore.PatchResult.Accepted>()

        store.snapshot(pageKey).pageVersion shouldBe versionBefore
        identities[pageKey]!!.leaseToken shouldBe staleToken
        // The sibling's hold is untouched by the declined heal.
        store.pageLeaseOwner(pageKey) shouldBe PageWriteOrigin.BATCH
        store.snapshot(pageKey).leaseToken shouldBe siblingToken

        // T934 flake hardening (diagnosis §4): flush and cancel the store's
        // persistScope (debounced persist) so @TempDir's recursive delete
        // cannot race it on Windows.
        store.closeAndFlush()
    }
}
