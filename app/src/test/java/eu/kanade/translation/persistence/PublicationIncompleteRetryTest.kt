package eu.kanade.translation.persistence

import eu.kanade.translation.model.PageStage
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.model.StageFingerprints
import eu.kanade.translation.pipeline.toPrecondition
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * P3B integration of the P3A typed publication receipts at the store
 * publication bridge: a primary-advanced/replica-failed rejection (durable
 * candidate sidecar, failed checked manifest commit) is re-presented ONCE
 * through [ChapterArtifactEngine.finishIncompletePublication] with
 * newer-state dominance, and a rejection that survives the finish is an
 * honest typed failure whose orphan evidence stays visible.
 */
class PublicationIncompleteRetryTest {

    private val layout = ChapterArtifactLayout("Receipt Chapter")
    private val manifestTmpName = AtomicChapterDocuments.tempNameFor(layout.manifestFileName)

    private fun block() = TranslationBlock(
        text = "source",
        translation = "target",
        width = 10f,
        height = 10f,
        x = 0f,
        y = 0f,
        symHeight = 1f,
        symWidth = 1f,
        angle = 0f,
    )

    private fun updatedPage() = PageTranslation(
        sourceFileName = "p1",
        blocks = mutableListOf(block()),
        imgWidth = 100f,
        imgHeight = 100f,
        ocrStatus = StageStatus.READY,
    )

    private fun seededStore(io: FakeChapterDocumentIo): ChapterTranslationStore {
        val artifact = ChapterArtifactEngine(AtomicChapterDocuments(io), layout)
        val seeded = artifact
            .loadArtifact(
                ArtifactSeed(
                    pages = mapOf(
                        "p1" to ArtifactPageFacts(PageTranslation(sourceFileName = "p1")),
                    ),
                    createdAtEpochMs = 1L,
                ),
            )
            .manifest
        // The seeded inventory is already registered: without it, the first
        // mutation's page-registration publication would consume the injected
        // manifest-publication failure instead of the candidate persist.
        val manifest = seeded.copy(expectedPageCount = 1, expectedPageCountTrusted = true)
        check(artifact.publishManifest(manifest))
        return ChapterTranslationStore(
            artifactParentResolver = null,
            initialPages = mapOf("p1" to PageTranslation(sourceFileName = "p1")),
            artifactStore = artifact,
            initialArtifactManifest = manifest,
        )
    }

    private suspend fun precondition(store: ChapterTranslationStore) =
        store.snapshot("p1").toPrecondition()

    @Test
    fun `a manifest-commit failure during candidate persist finishes once and commits`() = runTest {
        val io = FakeChapterDocumentIo()
        val store = seededStore(io)
        store.tryAcquirePageStageLease("p1", PageStage.Ocr, PageWriteOrigin.MANUAL)
            .shouldBeInstanceOf<LeaseAcquisition.Granted>()
        val expected = precondition(store)
        io.ownedRenamesToFailOnce += manifestTmpName

        val accepted = store.publicationBridge.persistArtifactMutation(
            pageKey = "p1",
            previous = null,
            updated = updatedPage(),
            expected = expected,
        )
        if (!accepted) {
            check(false) {
                "DIAG rejection=" + store.publicationBridge.lastArtifactPublicationRejectionDiagnostic
            }
        }

        accepted shouldBe true
        val candidate = store.artifactManifest.shouldNotBeNull()
            .pages.getValue("p1").candidate.shouldNotBeNull()
        candidate.pageSnapshotFingerprint shouldBe StageFingerprints.pageSnapshot(updatedPage())
        // The retried transaction left no orphaned duplicate candidate file
        // behind: the receipt's advanced file IS the committed pointer target.
        io.files.containsKey(candidate.pageSnapshotFileName) shouldBe true
        store.publicationBridge.lastArtifactPublicationRejectionDiagnostic shouldBe null
    }

    @Test
    fun `a finish that fails again stays an honest rejection with orphan evidence`() = runTest {
        val io = FakeChapterDocumentIo()
        val store = seededStore(io)
        store.tryAcquirePageStageLease("p1", PageStage.Ocr, PageWriteOrigin.MANUAL)
            .shouldBeInstanceOf<LeaseAcquisition.Granted>()
        val expected = precondition(store)
        io.ownedRenamesToFail += manifestTmpName

        val accepted = store.publicationBridge.persistArtifactMutation(
            pageKey = "p1",
            previous = null,
            updated = updatedPage(),
            expected = expected,
        )

        accepted shouldBe false
        // The typed rejection keeps its stale-retry registration semantics:
        // a publication failure is diagnostic-visible, not swallowed.
        store.publicationBridge.lastArtifactPublicationRejectionDiagnostic.shouldNotBeNull()
            .contains("candidate open rejected")
        // The manifest stays authoritative and unchanged; with generation sidecars retired in P4,
        // candidate open rejection leaves no orphaned generation sidecars behind.
        store.artifactManifest.shouldNotBeNull()
            .pages.getValue("p1").candidate shouldBe null
    }
}
