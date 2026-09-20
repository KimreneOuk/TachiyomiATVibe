package eu.kanade.translation.coexistence

import eu.kanade.translation.artifact.ChapterArtifactEngine
import eu.kanade.translation.artifact.ChapterRunState
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.Translation
import eu.kanade.translation.pipeline.batch.ChapterProfileBatchCoordinator
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test

/**
 *  Phase 4 Wave B — the STANDARD_PIPELINE lane through the REAL shell
 * (Director contract for the coexistence harness,  ON + STANDARD engine).
 *
 * [TranslationCoexistenceHarness.createStandard] drives
 * ChapterTranslator → TranslationPipeline → BatchChapterTranslator → the
 * flagged [ChapterProfileBatchCoordinator] with the injected standard seam
 * over a durable ARTIFACTS-authority store; the standard translator is the
 * harness's [FakeTransportTranslator] resolved through the normal EngineLane
 * construction path. Pinned here (the Director's four points):
 *
 *  1. the legacy SequentialBatchCoordinator is NOT constructed — by behavior:
 *     the transport is called exactly once per page, and the artifact manifest
 *     gains an activeRun COMPLETE record with providerKey `standard:mlkit`
 *     (the legacy schedule never publishes run records);
 *  2. full OCR happens BEFORE any translate call — every batch decode lands
 *     before the first PROVIDER_START (the legacy page-serial standard lane
 *     interleaves decode → translate per page and shows exactly one);
 *  3. a single COMPLETE publication; pages end translationStatus READY with
 *     renderStatus READY — no in-pass render WORK ran, but the durable page
 *     record carries the render-terminal stamp (2026-09-16 E-fix) so the
 *     display bundle commits and the reader gate flips without a sweep;
 *  4. the store glossary is never written.
 */
class StandardPipelineCoexistenceTest {

    @Test
    fun `flagged standard lane runs the real shell end-to-end with full OCR before translate`() {
        val pageKeys = listOf("p0", "p1")
        val harness = TranslationCoexistenceHarness.createStandard(pageKeys)
        try {
            harness.stubChapterPages(pageKeys)
            val glossaryBefore = harness.store.glossarySnapshot()

            val batch = harness.launchBatch(pageKeys)
            val reconciliation = runBlocking {
                withTimeout(TranslationCoexistenceHarness.AWAIT_TIMEOUT_MS) {
                    batch.reconciliation.await().shouldNotBeNull()
                }
            }

            // ---- Contract 2: FULL OCR precedes the first paid call. ----
            val arrivals = harness.barrier.arrivals.value
            val firstProviderStart =
                arrivals.indexOfFirst { it.first == CoexistenceBarrier.BarrierPoint.PROVIDER_START }
            (firstProviderStart >= 0) shouldBe true
            //  authorized assertion conversion (diagnosis §3): per-page
            // decode MULTIPLICITY is not a schedule property; DISTINCT-page
            // coverage before the first paid call IS.
            // The flagged preflight still decodes EVERY page before any
            // translate, and the legacy page-serial standard schedule
            // (LOCAL_COMPUTE chunk of one) interleaves decode → translate →
            // inpaint per page and shows exactly ONE distinct page here —
            // this assertion remains the schedule discriminator.
            val distinctPagesDecodedBeforeFirstTranslate = arrivals
                .take(firstProviderStart)
                .filter { it.first == CoexistenceBarrier.BarrierPoint.NATIVE_ACQUIRE }
                .map { it.second }
                .distinct()
                .size
            distinctPagesDecodedBeforeFirstTranslate shouldBe pageKeys.size

            // ---- Contract 1 (behavioral): the legacy schedule never ran. ----
            // The transport resolved through the normal engine path was called
            // exactly once per page...
            pageKeys.forEach { key -> harness.transportCallsFor(key) shouldBe 1 }
            // ...and the artifact manifest owns the flagged run's COMPLETE
            // record with the standard provider identity — a sidecar the
            // legacy coordinator never publishes.
            val store = harness.store
            val artifact = store.artifactEngine.shouldNotBeNull()
            val pointer = store.artifactManifest?.activeRun.shouldNotBeNull()
            val record = (
                artifact.readRunRecord(pointer)
                    as ChapterArtifactEngine.RunRecordRead.Usable
                ).record
            record.state shouldBe ChapterRunState.COMPLETE
            record.frozenConfig.providerKey shouldBe "standard:mlkit"
            record.frozenConfig.flagProfilePipeline shouldBe true
            record.phaseCounters[ChapterProfileBatchCoordinator.COUNTER_RUN_COMPLETE] shouldBe 1

            // ---- Contract 3: single COMPLETE; translation-terminal pages. ----
            reconciliation.chapterStatus shouldBe Translation.State.TRANSLATED
            reconciliation.strandedPages shouldBe emptyMap()
            runBlocking {
                pageKeys.forEach { key ->
                    val page = store.snapshot(key).page.shouldNotBeNull()
                    page.translationStatus shouldBe StageStatus.READY
                    // 2026-09-16 E-fix: no in-pass render WORK, but the durable
                    // record is stamped render-terminal at inpaint-completion
                    // (BatchLaneWorkers render terminal stamp).
                    page.renderStatus shouldBe StageStatus.READY
                    page.blocks.forEach { block ->
                        block.translation shouldBe "tr-" + block.text
                    }
                }
            }
            // No in-pass render ever ran — the flagged lane renders later.
            harness.barrier.arrivalsOf(CoexistenceBarrier.BarrierPoint.RENDER) shouldBe 0

            // ---- Contract 4: the glossary is never written. ----
            store.glossarySnapshot() shouldBe glossaryBefore
            store.glossarySnapshot() shouldBe emptyMap()

            // Resume parity rides the preflight checkpoints: every page's OCR
            // evidence is durably checkpointed.
            store.artifactManifest?.ocrCheckpoints?.keys shouldBe pageKeys.toSet()

            batch.job.cancel()
            runBlocking {
                withTimeout(TranslationCoexistenceHarness.AWAIT_TIMEOUT_MS) { batch.job.join() }
            }
        } finally {
            harness.close()
        }
    }
}
