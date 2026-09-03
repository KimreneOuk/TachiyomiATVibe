package eu.kanade.translation

import com.hippo.unifile.UniFile
import eu.kanade.translation.artifact.AtomicChapterDocuments
import eu.kanade.translation.artifact.ChapterArtifactLayout
import eu.kanade.translation.artifact.ChapterArtifactStore
import eu.kanade.translation.artifact.FakeChapterDocumentIo
import eu.kanade.translation.artifact.LegacyChapterSnapshot
import eu.kanade.translation.artifact.ManifestAuthority
import eu.kanade.translation.model.PageStage
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.pipeline.toPrecondition
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * T917 Phase 3 backlog fold-in (phase3-design §4, §5.4): `patchPage` must grant
 * the same `record.candidate != null` grace `persistArtifactMutationLocked`
 * already applies to the dependency-fingerprint check.
 *
 * Defect shape: a page registered in the artifact manifest WITHOUT a live
 * candidate (the only manifest mutation a batch start performs for a held
 * page — `PageArtifactRecord(pageKey)` with null candidate — or a record whose
 * candidate was cancelled after an aborted pass) is invisible to
 * `snapshotLocked.candidateGenerationId` (null) while `dependencyFingerprint`
 * falls back to the page-snapshot fingerprint (non-null). A manual writer that
 * captured its precondition in that state was then FALSELY rejected by
 * `patchPage`'s dependency clause ("candidate dependency fingerprint changed")
 * even though no real candidate ever moved — while the guarded-writer chain
 * (`pageWriteRejection`) and `persistArtifactMutationLocked` both waive the
 * check when `candidate == null`. A retried page, never corruption — but a
 * known false-reject under the D5 stamp traffic, so it aligns in Phase 3.
 *
 * The grace is fail-direction-preserving: the generation, pageVersion,
 * artifact-pageVersion, candidate-generation, block-fingerprint, and
 * lease-token fences all stay armed; only the dependency-fingerprint
 * comparison against a NONEXISTENT candidate stops rejecting.
 */
class ChapterTranslationStorePatchPageGraceTest {

    // ------------------------------------------------------------------
    // fixture: ARTIFACTS-authority memory store (production fresh-chapter
    // recipe — a pure memory store has no manifest, so the clause under test
    // could never arm there)
    // ------------------------------------------------------------------

    private fun artifactBackedStore(pageKey: String): ChapterTranslationStore {
        val artifactStore = ChapterArtifactStore(
            AtomicChapterDocuments(FakeChapterDocumentIo()),
            ChapterArtifactLayout("Grace Chapter"),
        )
        var manifest = artifactStore
            .loadOrMigrate(LegacyChapterSnapshot(migratedAtEpochMs = 1L))
            .manifest
        if (manifest.authority == ManifestAuthority.LEGACY &&
            manifest.legacyMigration == null &&
            manifest.pages.isEmpty()
        ) {
            manifest = manifest.copy(
                authority = ManifestAuthority.ARTIFACTS,
                cutoverAtEpochMs = manifest.cutoverAtEpochMs ?: 1L,
                migratedFromLegacyAtEpochMs = manifest.migratedFromLegacyAtEpochMs ?: 1L,
                updatedAtEpochMs = 1L,
            )
            check(artifactStore.publishManifest(manifest)) { "fixture: authority flip publish failed" }
        }
        check(manifest.authority == ManifestAuthority.ARTIFACTS)
        return ChapterTranslationStore(
            translationFile = null as UniFile?,
            fileCreator = null,
            initialPages = mapOf(pageKey to PageTranslation(sourceFileName = pageKey)),
            artifactStore = artifactStore,
            initialArtifactManifest = manifest,
        )
    }

    /**
     * The §5.4 choreography, entirely through real store APIs:
     * 1. a BATCH pass registers the page and opens a candidate, then aborts
     *    (`cancelPageStageWork`) — the record stays registered but
     *    candidate-less, exactly the state the candidate-less registration
     *    shape produces;
     * 2. the manual writer captures its precondition from that state
     *    (`candidateGenerationId = null`, `dependencyFingerprint` = the
     *    page-snapshot fallback, lease token held);
     * 3. commit → the dependency clause must NOT reject a comparison against a
     *    nonexistent candidate (grace), while every other fence stays armed.
     */
    private suspend fun captureAfterCandidateLessRegistration(
        store: ChapterTranslationStore,
        pageKey: String,
    ): ChapterTranslationStore.PatchPrecondition {
        val batchLease = store.tryAcquirePageStageLease(pageKey, PageStage.Translation, PageWriteOrigin.BATCH)
            .shouldBeInstanceOf<LeaseAcquisition.Granted>()
        val seeded = store.updatePageGuarded(
            pageKey = pageKey,
            expected = store.snapshot(pageKey).toPrecondition(),
            description = "seed batch candidate",
        ) { current ->
            (current ?: PageTranslation(sourceFileName = pageKey)).copy(
                ocrStatus = StageStatus.READY,
                blocks = mutableListOf(
                    TranslationBlock(
                        text = "text-$pageKey",
                        width = 10f,
                        height = 10f,
                        x = 0f,
                        y = 0f,
                        symHeight = 1f,
                        symWidth = 1f,
                        angle = 0f,
                    ),
                ),
            )
        }
        check(seeded is ChapterTranslationStore.PatchResult.Accepted) { "fixture seed rejected" }
        // The aborted pass leaves a registered, candidate-less record.
        store.cancelPageStageWork(pageKey, batchLease.lease.origin) shouldBe true
        withClue("fixture: the record must be candidate-less after the abort") {
            store.artifactManifest?.pages?.get(pageKey)?.candidate shouldBe null
        }

        // The manual writer acquires the page and captures its precondition.
        store.tryAcquirePageStageLease(pageKey, PageStage.Translation, PageWriteOrigin.MANUAL)
            .shouldBeInstanceOf<LeaseAcquisition.Granted>()
        val captured = store.snapshot(pageKey).toPrecondition()
        withClue("fixture: the captured precondition must arm the dependency clause") {
            captured.dependencyFingerprint.shouldNotBeNull()
        }
        captured.candidateGenerationId shouldBe null
        return captured
    }

    @Test
    fun `candidate-less registration between capture and commit is accepted while the lease is held`() = runTest {
        val store = artifactBackedStore("p1")
        val captured = captureAfterCandidateLessRegistration(store, "p1")

        val result = store.patchPage(
            pageKey = "p1",
            expected = captured,
            description = "commit manual translation over a candidate-less registration",
        ) { current ->
            (current ?: PageTranslation(sourceFileName = "p1")).copy(
                translationStatus = StageStatus.READY,
            )
        }

        withClue(
            "§4 grace: a candidate-less record has no dependency fingerprint to compare " +
                "against — patchPage must not reject with 'candidate dependency fingerprint " +
                "changed' (the guarded writer and publishLocked already waive this)",
        ) {
            (result is ChapterTranslationStore.PatchResult.Accepted) shouldBe true
        }
    }

    @Test
    fun `the same late patch stays rejected once the lease is lost`() = runTest {
        val store = artifactBackedStore("p1")
        val captured = captureAfterCandidateLessRegistration(store, "p1")

        // The writer lost the page before committing.
        store.releasePageStageLease("p1", PageWriteOrigin.MANUAL)

        val result = store.patchPage(
            pageKey = "p1",
            expected = captured,
            description = "late commit after lease loss",
        ) { current ->
            (current ?: PageTranslation(sourceFileName = "p1")).copy(
                translationStatus = StageStatus.READY,
            )
        }

        withClue(
            "§4 grace must stay fail-closed: the lease-token fence rejects the stale " +
                "writer even when the dependency clause is waived",
        ) {
            val rejected = result.shouldBeInstanceOf<ChapterTranslationStore.PatchResult.Rejected>()
            rejected.reason shouldContain "page lease token"
        }
    }
}
