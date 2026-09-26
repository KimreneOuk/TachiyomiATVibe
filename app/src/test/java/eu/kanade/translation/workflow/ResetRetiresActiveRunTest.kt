package eu.kanade.translation.workflow

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.persistence.artifact.ArtifactSeed
import eu.kanade.translation.persistence.artifact.AtomicChapterDocuments
import eu.kanade.translation.persistence.artifact.ChapterArtifactEngine
import eu.kanade.translation.persistence.artifact.ChapterArtifactLayout
import eu.kanade.translation.persistence.artifact.ChapterRunRecord
import eu.kanade.translation.persistence.artifact.ChapterRunState
import eu.kanade.translation.persistence.artifact.FakeChapterDocumentIo
import eu.kanade.translation.persistence.artifact.loadArtifact
import eu.kanade.translation.persistence.chapter.ActiveChapterStoreRegistry
import eu.kanade.translation.persistence.chapter.ChapterTranslationStore
import eu.kanade.translation.persistence.chapter.TranslationProvider
import eu.kanade.translation.pipeline.execution.TranslationStreamRegistry
import eu.kanade.translation.scheduling.TranslationScheduler
import eu.kanade.translation.workflow.ChapterTranslator
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.security.MessageDigest

/**
 *   a user reset must retire the recorded run — the reset paths
 * demote committed displays and clear pages, but a left-behind COMPLETE run
 * record let a flag-ON re-dispatch return the zero-work finished outcome
 * (RESUME_COMPLETE_REASON) over pages with nothing to show. Drives the REAL
 * [ChapterDataResetController.resetChapterTranslationData] (the shared
 * chapter-level reset body all three chapter reset paths delegate to) over a
 * real artifact-authoritative store whose manifest owns a durably published
 * COMPLETE run record.
 */
class ResetRetiresActiveRunTest {

    private fun hex64(tag: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(tag.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    private fun translatedPage(pageKey: String) = PageTranslation(
        sourceFileName = pageKey,
        blocks = mutableListOf(
            TranslationBlock(
                blockId = "b1",
                text = "source",
                translation = "translated",
                width = 10f,
                height = 10f,
                x = 0f,
                y = 0f,
                symHeight = 1f,
                symWidth = 1f,
                angle = 0f,
            ),
        ),
        imgWidth = 100f,
        imgHeight = 160f,
        decodeSampleSize = 1,
        ocrStatus = StageStatus.READY,
        translationStatus = StageStatus.READY,
        inpaintStatus = StageStatus.READY,
        inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION,
        sourceFingerprint = hex64("source-$pageKey"),
        detectionFingerprint = hex64("detection-$pageKey"),
        ocrFingerprint = hex64("ocr-$pageKey"),
    )

    /** Real artifact store with authority flipped and a durably COMPLETE run record. */
    private fun artifactStoreWithCompleteRun(): ChapterArtifactEngine {
        val artifact = ChapterArtifactEngine(
            AtomicChapterDocuments(FakeChapterDocumentIo()),
            ChapterArtifactLayout("Chapter 1"),
        )
        var manifest = artifact
            .loadArtifact(ArtifactSeed(migratedAtEpochMs = 1L))
            .manifest
        manifest = manifest.copy(
            cutoverAtEpochMs = 1L,
            migratedFromLegacyAtEpochMs = 1L,
            updatedAtEpochMs = 1L,
        )
        check(artifact.publishManifest(manifest)) { "fixture: authority flip publish failed" }
        val frozen = eu.kanade.translation.pipeline.batch.ChapterProfileBatchCoordinator.frozenRunConfig(
            sourceLang = "ja",
            targetLang = "en",
            ocrEngine = "FakeOcrEngine",
            inpaintMode = "OFF",
            providerKey = "fake:provider",
        )
        val completeRecord = ChapterRunRecord(
            runId = "run-li2-reset-1",
            state = ChapterRunState.COMPLETE,
            frozenConfig = frozen,
            frozenRunConfigFingerprint =
            eu.kanade.translation.pipeline.batch.ChapterProfileBatchCoordinator.runConfigFingerprint(frozen),
            orderedSourceDigest = hex64("ordered-source"),
            analysisPolicyFingerprint = hex64("analysis-policy"),
            envelopePolicyFingerprint = hex64("envelope-policy"),
            createdAtEpochMs = 1L,
            updatedAtEpochMs = 1L,
        )
        artifact.publishActiveRun(
            manifest = artifact.readManifest().shouldNotBeNull(),
            record = completeRecord,
            contentFingerprint = hex64("complete-run-record"),
        ).shouldBeInstanceOf<ChapterArtifactEngine.TransactionOutcome.Committed>()
        return artifact
    }

    @Test
    fun `chapter translation reset retires the recorded COMPLETE run`() = runTest {
        val artifact = artifactStoreWithCompleteRun()
        val store = ChapterTranslationStore(
            translationFile = null as UniFile?,
            fileCreator = null,
            initialPages = mapOf(
                "p0" to translatedPage("p0"),
                "p1" to translatedPage("p1"),
            ),
            artifactStore = artifact,
            initialArtifactManifest = artifact.readManifest().shouldNotBeNull(),
        )

        val controller = ChapterDataResetController(
            findTranslationDocumentFn = { _, _, _, _ -> null },
            schedulerProvider = { mockk<TranslationScheduler>(relaxed = true) },
            cancelPageTranslationsFn = {},
            cancelPageTranslationFn = { _, _ -> true },
            removeFromTranslationQueueFn = {},
            translatorProvider = { mockk<ChapterTranslator>(relaxed = true) },
            disposeBatchTrackerFn = {},
            unregisterActiveTranslationStoreFn = {},
            streamRegistryProvider = { mockk<TranslationStreamRegistry>(relaxed = true) },
            providerProvider = { mockk<TranslationProvider>(relaxed = true) },
            retireChapterCompanionImagesFn = { _, _, _ -> },
            retirePageCompanionImageFn = { _, _, _, _, _ -> },
            durableStatusResolverProvider = { mockk<DurableChapterStatusResolver>(relaxed = true) },
            activeStoresProvider = {
                mockk<ActiveChapterStoreRegistry> {
                    every { get(42L) } returns store
                }
            },
            openExistingChapterTranslationStoreFn = { _, _, _, _, _ -> null },
        )
        val chapter = mockk<tachiyomi.domain.chapter.model.Chapter> {
            every { id } returns 42L
            every { name } returns "Chapter 1"
            every { scanlator } returns null
        }
        val manga = mockk<tachiyomi.domain.manga.model.Manga> {
            every { id } returns 7L
            every { title } returns "Manga"
        }
        val source = mockk<HttpSource>(relaxed = true)

        controller.resetChapterTranslationData(
            chapter = chapter,
            manga = manga,
            source = source as Source,
            preserveEdits = false,
        )

        // The recorded run must be retired: a future flag-ON dispatch can
        // never short-circuit on its COMPLETE record after a user reset.
        artifact.readManifest().shouldNotBeNull().activeRun.shouldBeNull()

        // The reset demoted the live pages as before (no behavior regression).
        store.state.value.getValue("p0").translationStatus shouldBe StageStatus.PENDING
        store.state.value.getValue("p1").translationStatus shouldBe StageStatus.PENDING
    }
}
