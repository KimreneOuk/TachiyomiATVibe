package eu.kanade.translation.store

import eu.kanade.translation.artifact.loadArtifact

import com.hippo.unifile.UniFile
import eu.kanade.translation.storage.ChapterTranslationStore
import eu.kanade.translation.artifact.ArtifactStageStatus
import eu.kanade.translation.artifact.AtomicChapterDocuments
import eu.kanade.translation.artifact.ChapterArtifactLayout
import eu.kanade.translation.artifact.ChapterArtifactEngine
import eu.kanade.translation.artifact.ChapterArtifactManifest
import eu.kanade.translation.artifact.ChapterRunRecord
import eu.kanade.translation.artifact.ChapterRunState
import eu.kanade.translation.artifact.CommittedBundleMetadata
import eu.kanade.translation.artifact.DisplayBaseKind
import eu.kanade.translation.artifact.DisplayBaseReference
import eu.kanade.translation.artifact.FakeChapterDocumentIo
import eu.kanade.translation.artifact.ArtifactSeed
import eu.kanade.translation.artifact.PageArtifactRecord
import eu.kanade.translation.artifact.SidecarPointer
import eu.kanade.translation.artifact.StageArtifactRecord
import eu.kanade.translation.model.PageDisplayState
import eu.kanade.translation.model.Translation
import eu.kanade.translation.pipeline.batch.ChapterProfileBatchCoordinator
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.security.MessageDigest

/**
 * T924 LI-1 (durable half): when a durably COMPLETE run record owns the
 * chapter (manifest `activeRun` readable at `ChapterRunState.COMPLETE`), the
 * chapter status projects from the MANIFEST PAGE RECORDS, not the legacy
 * live-page reconcile — a flagged-lane run commits translations WITHOUT an
 * in-pass render, so the legacy done-predicate (`hasRenderedResult`) turned
 * every healthy flagged COMPLETED chapter into a durable ERROR on the manga
 * screen. A missing/unreadable record or a non-COMPLETE state keeps the
 * existing legacy projection unchanged (legacy chapters have no activeRun).
 */
class StoreStatusProjectorRunRecordTest {

    private fun hex64(tag: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(tag.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    /** A committed display-ready page record (validated base + committed state). */
    private fun committedPageRecord(pageKey: String) = PageArtifactRecord(
        pageKey = pageKey,
        pageVersion = 1L,
        committed = CommittedBundleMetadata(
            generationId = "gen-$pageKey",
            displayBase = DisplayBaseReference(
                kind = DisplayBaseKind.ORIGINAL_SOURCE,
                validated = true,
            ),
            origin = eu.kanade.translation.artifact.ArtifactOrigin.BATCH,
            promotedAtEpochMs = 1L,
        ),
        displayState = PageDisplayState.FAILED_WITH_COMMITTED_RESULT,
    )

    /** A textless-terminal page record: processed done without a translation. */
    private fun textlessPageRecord(pageKey: String) = PageArtifactRecord(
        pageKey = pageKey,
        pageVersion = 1L,
        displayState = PageDisplayState.TEXTLESS_COMPLETE,
    )

    /**
     * ONE artifact store over one in-memory document IO (D5 fresh-chapter
     * recipe): authority flipped to ARTIFACTS over the given page records.
     * Every later publication MUST ride this same instance so reads share its
     * document IO.
     */
    private fun artifactsAuthority(
        pages: Map<String, PageArtifactRecord>,
        expectedPageCount: Int? = null,
    ): ChapterArtifactEngine {
        val artifact = ChapterArtifactEngine(
            AtomicChapterDocuments(FakeChapterDocumentIo()),
            ChapterArtifactLayout("Chapter 1"),
        )
        var manifest = artifact
            .loadArtifact(ArtifactSeed(migratedAtEpochMs = 1L))
            .manifest
        manifest = manifest.copy(
            pages = pages,
            expectedPageCount = expectedPageCount,
            expectedPageCountTrusted = expectedPageCount != null,
            cutoverAtEpochMs = 1L,
            migratedFromLegacyAtEpochMs = 1L,
            updatedAtEpochMs = 1L,
        )
        check(artifact.publishManifest(manifest)) { "fixture: authority flip publish failed" }
        return artifact
    }

    /** Publishes a durably COMPLETE active run record; returns the store to project. */
    private fun storeUnderCompleteRun(
        pages: Map<String, PageArtifactRecord>,
        expectedPageCount: Int? = null,
    ): ChapterTranslationStore {
        val artifact = artifactsAuthority(pages, expectedPageCount)
        val frozen = ChapterProfileBatchCoordinator.frozenRunConfig(
            sourceLang = "ja",
            targetLang = "en",
            ocrEngine = "FakeOcrEngine",
            inpaintMode = "OFF",
            providerKey = "fake:provider",
        )
        val completeRecord = ChapterRunRecord(
            runId = "run-li1-durable-1",
            state = ChapterRunState.COMPLETE,
            frozenConfig = frozen,
            frozenRunConfigFingerprint = ChapterProfileBatchCoordinator.runConfigFingerprint(frozen),
            orderedSourceDigest = hex64("ordered-source"),
            analysisPolicyFingerprint = hex64("analysis-policy"),
            envelopePolicyFingerprint = hex64("envelope-policy"),
            createdAtEpochMs = 1L,
            updatedAtEpochMs = 1L,
        )
        val publication = artifact.publishActiveRun(
            manifest = artifact.readManifest().shouldNotBeNull(),
            record = completeRecord,
            contentFingerprint = hex64("complete-run-record"),
        ).shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Committed>()
        return ChapterTranslationStore(
            translationFile = null as UniFile?,
            fileCreator = null,
            initialPages = emptyMap(),
            artifactStore = artifact,
            initialArtifactManifest = publication.manifest,
        )
    }

    private fun storeWithManifest(
        artifact: ChapterArtifactEngine,
        manifest: ChapterArtifactManifest,
    ): ChapterTranslationStore = ChapterTranslationStore(
        translationFile = null as UniFile?,
        fileCreator = null,
        initialPages = emptyMap(),
        artifactStore = artifact,
        initialArtifactManifest = manifest,
    )

    @Test
    fun `complete run record over committed page records projects TRANSLATED`() {
        val store = storeUnderCompleteRun(
            pages = mapOf(
                "p0" to committedPageRecord("p0"),
                "p1" to committedPageRecord("p1"),
            ),
        )

        // The flagged lane's healthy COMPLETED chapter: every page committed,
        // none rendered — previously the legacy reconcile projected ERROR.
        store.artifactStatus() shouldBe Translation.State.TRANSLATED
    }

    @Test
    fun `partial translation stage among committed records yields READY_WITH_WARNINGS`() {
        val store = storeUnderCompleteRun(
            pages = mapOf(
                "p0" to committedPageRecord("p0").copy(
                    translation = StageArtifactRecord(status = ArtifactStageStatus.PARTIAL),
                ),
                "p1" to textlessPageRecord("p1"),
            ),
        )

        store.artifactStatus() shouldBe Translation.State.READY_WITH_WARNINGS
    }

    @Test
    fun `page without committed or textless evidence under a COMPLETE record is ERROR`() {
        val store = storeUnderCompleteRun(
            pages = mapOf(
                "p0" to committedPageRecord("p0"),
                "p1" to PageArtifactRecord(
                    pageKey = "p1",
                    pageVersion = 1L,
                    displayState = PageDisplayState.ORIGINAL_ONLY,
                ),
            ),
        )

        store.artifactStatus() shouldBe Translation.State.ERROR
    }

    @Test
    fun `trusted baseline shortfall under a COMPLETE record is ERROR`() {
        val store = storeUnderCompleteRun(
            pages = mapOf("p0" to committedPageRecord("p0")),
            expectedPageCount = 3,
        )

        store.artifactStatus() shouldBe Translation.State.ERROR
    }

    @Test
    fun `no activeRun keeps the existing legacy projection unchanged`() {
        val artifact = artifactsAuthority(
            pages = mapOf(
                "p0" to committedPageRecord("p0"),
                "p1" to committedPageRecord("p1"),
            ),
        )
        val store = storeWithManifest(artifact, artifact.readManifest().shouldNotBeNull())

        // Legacy projection for an empty live store with registered pages:
        // partial artifact, no failures — the pre-existing READY_WITH_WARNINGS.
        store.artifactStatus() shouldBe Translation.State.READY_WITH_WARNINGS
    }

    @Test
    fun `unreadable run record keeps the existing legacy projection unchanged`() {
        val artifact = artifactsAuthority(
            pages = mapOf(
                "p0" to committedPageRecord("p0"),
                "p1" to committedPageRecord("p1"),
            ),
        )
        val durable = artifact.readManifest().shouldNotBeNull()
        val dangling = durable.copy(
            activeRun = SidecarPointer(
                fileName = "Chapter 1_artifacts/runs/f-missing.json",
                schemaVersion = 1,
                contentFingerprint = hex64("missing-run-record"),
            ),
            updatedAtEpochMs = 2L,
        )
        check(artifact.publishManifest(dangling)) { "fixture: dangling pointer publish failed" }
        val store = storeWithManifest(artifact, artifact.readManifest().shouldNotBeNull())

        store.artifactStatus() shouldBe Translation.State.READY_WITH_WARNINGS
    }
}
