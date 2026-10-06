package eu.kanade.translation.pipeline.batch

import eu.kanade.translation.model.InpaintMaskBox
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.persistence.artifact.ArtifactOrigin
import eu.kanade.translation.pipeline.planning.BatchExpectedFingerprints
import eu.kanade.translation.pipeline.planning.BatchPlannerInput
import eu.kanade.translation.pipeline.planning.BatchStage
import eu.kanade.translation.pipeline.planning.PageWorkPlanner
import eu.kanade.translation.pipeline.planning.StageDecision
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class DurablePageKeyAliasResolverTest {

    @Test
    fun `persisted source identity resolves unique downloaded key after process restart`() {
        val oldKey = "https://example.test/page/1"
        val downloadedKey = "001.webp"
        val sourceSha = "a".repeat(64)
        val expected = BatchExpectedFingerprints(
            detection = "detection",
            ocr = "ocr",
            inpaint = "inpaint",
            translation = "translation",
            layout = "layout",
        )
        val persistedPage = completedPage(oldKey, expected, sourceSha)

        // Inputs represent disk-restored state. No process-lifetime re-key map is supplied.
        val aliases = DurablePageKeyAliasResolver.resolve(
            persistedPages = mapOf(oldKey to persistedPage),
            manifestSourceShaByPageKey = mapOf(oldKey to sourceSha),
            downloadedSourceShaByPageKey = mapOf(downloadedKey to sourceSha),
        )

        aliases shouldBe mapOf(oldKey to downloadedKey)
        val plan = PageWorkPlanner.planPage(
            BatchPlannerInput(
                pageKey = downloadedKey,
                page = persistedPage,
                expectedFingerprints = expected,
                sourceFingerprint = sourceSha,
            ),
        )
        plan.stages.single { it.stage == BatchStage.TRANSLATION }.decision shouldBe StageDecision.REUSE
    }

    @Test
    fun `ambiguous source identities never produce aliases`() {
        val sourceSha = "b".repeat(64)

        val ambiguousOldKey = DurablePageKeyAliasResolver.resolve(
            persistedPages = mapOf(
                "https://example.test/old-1" to emptyPage(sourceSha),
                "https://example.test/old-2" to emptyPage(sourceSha),
            ),
            manifestSourceShaByPageKey = emptyMap(),
            downloadedSourceShaByPageKey = mapOf("001.webp" to sourceSha),
        )
        val ambiguousDownloadedKey = DurablePageKeyAliasResolver.resolve(
            persistedPages = mapOf("https://example.test/old" to emptyPage(sourceSha)),
            manifestSourceShaByPageKey = emptyMap(),
            downloadedSourceShaByPageKey = mapOf(
                "001.webp" to sourceSha,
                "002.webp" to sourceSha,
            ),
        )

        ambiguousOldKey shouldBe emptyMap()
        ambiguousDownloadedKey shouldBe emptyMap()
    }

    private fun completedPage(
        pageKey: String,
        fingerprints: BatchExpectedFingerprints,
        sourceSha: String,
    ) = PageTranslation(
        sourceFileName = pageKey,
        sourceFingerprint = sourceSha,
        cleanedImageName = "$pageKey.cleaned.jpg",
        ocrStatus = StageStatus.READY,
        translationStatus = StageStatus.READY,
        inpaintStatus = StageStatus.READY,
        renderStatus = StageStatus.READY,
        inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION,
        inpaintMaskBoxes = listOf(InpaintMaskBox(0, 0, 8, 8, 1)),
        detectionFingerprint = fingerprints.detection,
        ocrFingerprint = fingerprints.ocr,
        inpaintFingerprint = fingerprints.inpaint,
        translationFingerprint = fingerprints.translation,
        layoutFingerprint = fingerprints.layout,
        translationOrigin = ArtifactOrigin.BATCH.name,
        blocks = mutableListOf(
            TranslationBlock(
                text = "source",
                translation = "translated",
                width = 8f,
                height = 8f,
                x = 0f,
                y = 0f,
                symHeight = 8f,
                symWidth = 8f,
                angle = 0f,
            ),
        ),
    )

    private fun emptyPage(sourceSha: String) = PageTranslation(
        sourceFingerprint = sourceSha,
    )
}
