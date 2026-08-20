package eu.kanade.translation.artifact

import eu.kanade.translation.model.PageDisplayState
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TranslationBlock
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class LegacyArtifactMigrationTest {

    private fun block(translation: String = "hello", userEditedAt: Long? = null) = TranslationBlock(
        text = "source",
        translation = translation,
        width = 10f,
        height = 10f,
        x = 0f,
        y = 0f,
        symHeight = 1f,
        symWidth = 1f,
        angle = 0f,
        userEditedAt = userEditedAt,
    )

    private fun displayablePage(vararg blocks: TranslationBlock = arrayOf(block())) = PageTranslation(
        blocks = blocks.toMutableList(),
        imgWidth = 100f,
        imgHeight = 100f,
        ocrStatus = StageStatus.READY,
        translationStatus = StageStatus.READY,
        inpaintStatus = StageStatus.READY,
        renderStatus = StageStatus.READY,
        cleanedImageName = "page.cleaned.abc.jpg",
        inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION,
    )

    @Test
    fun `displayable legacy page becomes a provisional committed bundle`() {
        val page = displayablePage()
        val record = LegacyArtifactMigration.migratePage(
            "page.jpg",
            LegacyPageFacts(page, CleanedFileState.VALID),
        )
        record.displayState shouldBe PageDisplayState.DISPLAY_READY
        val committed = record.committed.shouldNotBeNull()
        committed.provisional shouldBe true
        committed.origin shouldBe ArtifactOrigin.LEGACY
        committed.bundleFingerprint.shouldBeNull()
        committed.translationFingerprint.shouldBeNull()
        committed.layoutFingerprint.shouldBeNull()
        committed.displayBase shouldBe DisplayBaseReference(
            kind = DisplayBaseKind.CLEANED_IMAGE,
            fileName = "page.cleaned.abc.jpg",
            validated = true,
            legacyLayout = true,
        )
        record.previousCommitted.shouldBeNull()
        // Strictly complete pages carry no compatibility-only visibility.
        record.legacyVisible.shouldBeNull()
        record.candidate.shouldBeNull()
    }

    @Test
    fun `legacy partial page stays recoverably visible but never strict-ready`() {
        val page = displayablePage(block(translation = "half"), block(translation = "")).copy(
            translationStatus = StageStatus.PARTIAL,
        )
        val record = LegacyArtifactMigration.migratePage(
            "page.jpg",
            LegacyPageFacts(page, CleanedFileState.VALID),
        )
        // Strict contract: not committed, not DISPLAY_READY.
        record.committed.shouldBeNull()
        record.displayState shouldBe PageDisplayState.ORIGINAL_ONLY
        // Compatibility visibility is explicit and separate.
        val visible = record.legacyVisible.shouldNotBeNull()
        visible.fileName shouldBe "page.cleaned.abc.jpg"
        visible.legacyLayout shouldBe true
        // Incomplete translation stays diagnostic/candidate.
        record.translation?.status shouldBe ArtifactStageStatus.PARTIAL
        record.candidate.shouldNotBeNull().origin shouldBe ArtifactOrigin.LEGACY
    }

    @Test
    fun `partial status never promotes even when every stored block has a target`() {
        val page = displayablePage(block(translation = "one"), block(translation = "two")).copy(
            translationStatus = StageStatus.PARTIAL,
        )
        val record = LegacyArtifactMigration.migratePage(
            "page.jpg",
            LegacyPageFacts(page, CleanedFileState.VALID),
        )
        record.committed.shouldBeNull()
        record.displayState shouldBe PageDisplayState.ORIGINAL_ONLY
        record.legacyVisible.shouldNotBeNull()
    }

    @Test
    fun `ready status with a blank target on a required block is not strict-complete`() {
        val page = displayablePage(block(translation = "done"), block(translation = ""))
        val record = LegacyArtifactMigration.migratePage(
            "page.jpg",
            LegacyPageFacts(page, CleanedFileState.VALID),
        )
        record.committed.shouldBeNull()
        record.displayState shouldBe PageDisplayState.ORIGINAL_ONLY
        record.translation?.status shouldBe ArtifactStageStatus.PARTIAL
        record.legacyVisible.shouldNotBeNull()
    }

    @Test
    fun `blank-source noise blocks impose no translation requirement`() {
        val noise = block().copy(text = "  ", translation = "")
        val page = displayablePage(block(), noise)
        val record = LegacyArtifactMigration.migratePage(
            "page.jpg",
            LegacyPageFacts(page, CleanedFileState.VALID),
        )
        record.committed.shouldNotBeNull()
        record.translation?.status shouldBe ArtifactStageStatus.READY
    }

    @Test
    fun `out-of-bounds block geometry blocks strict promotion but keeps visibility`() {
        val stray = block().copy(x = 500f, y = 500f, width = 10f, height = 10f)
        val page = displayablePage(block(), stray)
        val record = LegacyArtifactMigration.migratePage(
            "page.jpg",
            LegacyPageFacts(page, CleanedFileState.VALID),
        )
        record.committed.shouldBeNull()
        record.legacyVisible.shouldNotBeNull()
        LegacyArtifactMigration.blockGeometryIsValid(page) shouldBe false
    }

    @Test
    fun `unknown image and page bounds cannot validate strict layout geometry`() {
        val page = displayablePage().copy(imgWidth = 0f, imgHeight = 0f)
        val record = LegacyArtifactMigration.migratePage(
            "page.jpg",
            LegacyPageFacts(page, CleanedFileState.VALID),
        )
        record.committed.shouldBeNull()
        LegacyArtifactMigration.blockGeometryIsValid(page) shouldBe false
    }

    @Test
    fun `recognized blocks without display stages stay original-only with candidate data`() {
        val page = PageTranslation(
            blocks = mutableListOf(block()),
            ocrStatus = StageStatus.READY,
            translationStatus = StageStatus.READY,
            inpaintStatus = StageStatus.PENDING,
            renderStatus = StageStatus.PENDING,
        )
        val record = LegacyArtifactMigration.migratePage(
            "page.jpg",
            LegacyPageFacts(page, CleanedFileState.NONE_RECORDED),
        )
        record.displayState shouldBe PageDisplayState.ORIGINAL_ONLY
        record.committed.shouldBeNull()
        record.ocr?.status shouldBe ArtifactStageStatus.READY
        record.translation?.status shouldBe ArtifactStageStatus.READY
        record.inpaint.shouldBeNull()
    }

    @Test
    fun `partial translation without display shape maps to original-only diagnostics`() {
        val page = PageTranslation(
            blocks = mutableListOf(block(translation = "half")),
            ocrStatus = StageStatus.READY,
            translationStatus = StageStatus.PARTIAL,
            inpaintStatus = StageStatus.PENDING,
            renderStatus = StageStatus.PENDING,
        )
        val record = LegacyArtifactMigration.migratePage(
            "page.jpg",
            LegacyPageFacts(page, CleanedFileState.NONE_RECORDED),
        )
        record.displayState shouldBe PageDisplayState.ORIGINAL_ONLY
        record.committed.shouldBeNull()
        record.translation?.status shouldBe ArtifactStageStatus.PARTIAL
    }

    @Test
    fun `persisted running stage becomes a retryable candidate`() {
        val page = PageTranslation(
            blocks = mutableListOf(block()),
            ocrStatus = StageStatus.READY,
            translationStatus = StageStatus.RUNNING,
            inpaintStatus = StageStatus.PENDING,
            renderStatus = StageStatus.PENDING,
        )
        val record = LegacyArtifactMigration.migratePage(
            "page.jpg",
            LegacyPageFacts(page, CleanedFileState.NONE_RECORDED),
        )
        record.displayState shouldBe PageDisplayState.CANDIDATE_RUNNING
        record.translation?.status shouldBe ArtifactStageStatus.FAILED_RETRYABLE
        val candidate = record.candidate.shouldNotBeNull()
        candidate.origin shouldBe ArtifactOrigin.LEGACY
        candidate.generationId shouldBe "legacy-page.jpg"
    }

    @Test
    fun `missing cleaned file never points the reader at a missing file`() {
        val page = displayablePage()
        val record = LegacyArtifactMigration.migratePage(
            "page.jpg",
            LegacyPageFacts(page, CleanedFileState.MISSING),
        )
        record.committed.shouldBeNull()
        record.displayState shouldBe PageDisplayState.FAILED_NO_RESULT
        record.inpaint?.status shouldBe ArtifactStageStatus.CORRUPT
        val manifest = LegacyArtifactMigration.migrateChapter(
            LegacyChapterSnapshot(
                pages = mapOf("page.jpg" to LegacyPageFacts(page, CleanedFileState.MISSING)),
            ),
        )
        val failure = manifest.durableFailures["page.jpg:INPAINT"].shouldNotBeNull()
        failure.stage shouldBe ArtifactStage.INPAINT
        failure.status shouldBe ArtifactStageStatus.CORRUPT
    }

    @Test
    fun `failed legacy stage records durable retryable failure with unknown category`() {
        val page = PageTranslation(ocrStatus = StageStatus.FAILED)
        val manifest = LegacyArtifactMigration.migrateChapter(
            LegacyChapterSnapshot(
                pages = mapOf("page.jpg" to LegacyPageFacts(page, CleanedFileState.NONE_RECORDED)),
            ),
        )
        val failure = manifest.durableFailures["page.jpg:OCR"].shouldNotBeNull()
        failure.category shouldBe FailureCategory.LEGACY_UNKNOWN
        failure.status shouldBe ArtifactStageStatus.FAILED_RETRYABLE
        val record = manifest.pages.getValue("page.jpg")
        record.displayState shouldBe PageDisplayState.FAILED_NO_RESULT
        record.ocr?.status shouldBe ArtifactStageStatus.FAILED_RETRYABLE
    }

    @Test
    fun `cancelled legacy page maps to absent stages and original-only`() {
        val page = PageTranslation(
            ocrStatus = StageStatus.CANCELLED,
            translationStatus = StageStatus.CANCELLED,
            inpaintStatus = StageStatus.CANCELLED,
            renderStatus = StageStatus.CANCELLED,
        )
        val record = LegacyArtifactMigration.migratePage(
            "page.jpg",
            LegacyPageFacts(page, CleanedFileState.NONE_RECORDED),
        )
        record.displayState shouldBe PageDisplayState.ORIGINAL_ONLY
        record.ocr?.status shouldBe ArtifactStageStatus.ABSENT
        record.candidate.shouldBeNull()
    }

    @Test
    fun `textless terminal page maps to textless-complete`() {
        val page = PageTranslation(
            ocrStatus = StageStatus.READY,
            translationStatus = StageStatus.SKIPPED,
            inpaintStatus = StageStatus.SKIPPED,
            renderStatus = StageStatus.SKIPPED,
        )
        val record = LegacyArtifactMigration.migratePage(
            "page.jpg",
            LegacyPageFacts(page, CleanedFileState.NONE_RECORDED),
        )
        record.displayState shouldBe PageDisplayState.TEXTLESS_COMPLETE
        record.committed.shouldBeNull()
        record.ocr?.status shouldBe ArtifactStageStatus.READY
        record.translation?.status shouldBe ArtifactStageStatus.SKIPPED
        record.translation?.skipReason shouldBe LegacyArtifactMigration.SKIP_REASON_TEXTLESS
        record.layout?.skipReason shouldBe LegacyArtifactMigration.SKIP_REASON_TEXTLESS
    }

    @Test
    fun `manual edits are flagged on the committed bundle`() {
        val page = displayablePage(block(userEditedAt = 1234L))
        val record = LegacyArtifactMigration.migratePage(
            "page.jpg",
            LegacyPageFacts(page, CleanedFileState.VALID),
        )
        record.committed.shouldNotBeNull().hasManualEdits shouldBe true
    }

    @Test
    fun `unprovable provenance stays explicit with null fingerprints`() {
        val record = LegacyArtifactMigration.migratePage(
            "page.jpg",
            LegacyPageFacts(displayablePage(), CleanedFileState.VALID),
        )
        listOf(record.detection, record.ocr, record.inpaint, record.translation, record.layout).forEach { stage ->
            stage?.fingerprint.shouldBeNull()
            stage?.origin shouldBe ArtifactOrigin.LEGACY
        }
        record.source.shouldNotBeNull().let { source ->
            source.sha256.shouldBeNull()
            source.isComplete shouldBe false
        }
        record.naturalPageIndex.shouldBeNull()
    }

    @Test
    fun `legacy glossary migrates as versioned vocabulary hints`() {
        val manifest = LegacyArtifactMigration.migrateChapter(
            LegacyChapterSnapshot(
                pages = emptyMap(),
                glossary = mapOf("sensei" to "teacher"),
            ),
        )
        val pointer = manifest.glossary.shouldNotBeNull()
        pointer.version shouldBe 1
        pointer.versionFingerprint shouldBe StageFingerprints.glossaryVersion(mapOf("sensei" to "teacher"))
    }

    @Test
    fun `migration is deterministic for identical input`() {
        val pages = mapOf(
            "a.jpg" to LegacyPageFacts(displayablePage(), CleanedFileState.VALID),
            "b.jpg" to LegacyPageFacts(PageTranslation(ocrStatus = StageStatus.FAILED)),
        )
        val snapshot = LegacyChapterSnapshot(pages = pages, glossary = mapOf("k" to "v"), migratedAtEpochMs = 42L)
        LegacyArtifactMigration.migrateChapter(snapshot) shouldBe LegacyArtifactMigration.migrateChapter(snapshot)
    }

    @Test
    fun `cleaned file present but empty maps to corrupt`() {
        val page = displayablePage()
        val record = LegacyArtifactMigration.migratePage(
            "page.jpg",
            LegacyPageFacts(page, CleanedFileState.EMPTY),
        )
        record.inpaint?.status shouldBe ArtifactStageStatus.CORRUPT
        record.displayState shouldBe PageDisplayState.FAILED_NO_RESULT
    }

    @Test
    fun `stale-revision cleaned output is not committed and not legacy-visible`() {
        // Pre-current inpaint revision: the live resume gate re-inpaints these
        // pages and the live reader gate does not display them either.
        val page = displayablePage().copy(inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION - 1)
        val record = LegacyArtifactMigration.migratePage(
            "page.jpg",
            LegacyPageFacts(page, CleanedFileState.VALID),
        )
        record.committed.shouldBeNull()
        record.displayState shouldBe PageDisplayState.ORIGINAL_ONLY
        record.inpaint?.status shouldBe ArtifactStageStatus.STALE
        record.legacyVisible.shouldBeNull()
    }

    @Test
    fun `non-empty undecodable bytes map to corrupt-bytes`() {
        val page = displayablePage()
        val record = LegacyArtifactMigration.migratePage(
            "page.jpg",
            LegacyPageFacts(page, CleanedFileState.CORRUPT_BYTES),
        )
        record.inpaint?.status shouldBe ArtifactStageStatus.CORRUPT
        record.committed.shouldBeNull()
        record.legacyVisible.shouldBeNull()
        record.displayState shouldBe PageDisplayState.FAILED_NO_RESULT
    }

    @Test
    fun `decoded but wrong-dimension image maps to dimension-mismatch`() {
        val page = displayablePage()
        val record = LegacyArtifactMigration.migratePage(
            "page.jpg",
            LegacyPageFacts(page, CleanedFileState.DIMENSION_MISMATCH),
        )
        record.inpaint?.status shouldBe ArtifactStageStatus.CORRUPT
        record.committed.shouldBeNull()
        record.legacyVisible.shouldBeNull()
        record.displayState shouldBe PageDisplayState.FAILED_NO_RESULT
    }

    @Test
    fun `resync preserves durable failures for surviving pages and drops vanished ones`() {
        val prior = LegacyArtifactMigration.migrateChapter(
            LegacyChapterSnapshot(
                pages = mapOf(
                    "keep.jpg" to LegacyPageFacts(displayablePage(), CleanedFileState.VALID),
                    "gone.jpg" to LegacyPageFacts(PageTranslation(ocrStatus = StageStatus.FAILED)),
                ),
            ),
        ).let { manifest ->
            manifest.copy(
                durableFailures = mapOf(
                    "keep.jpg:TRANSLATION" to DurableFailureMetadata(
                        pageKey = "keep.jpg",
                        stage = ArtifactStage.TRANSLATION,
                        status = ArtifactStageStatus.FAILED_TERMINAL,
                        category = FailureCategory.PROVIDER_REFUSAL,
                        retryCount = 2,
                        lastFailedAtEpochMs = 7L,
                    ),
                    "gone.jpg:OCR" to DurableFailureMetadata(
                        pageKey = "gone.jpg",
                        stage = ArtifactStage.OCR,
                        status = ArtifactStageStatus.FAILED_RETRYABLE,
                        category = FailureCategory.LEGACY_UNKNOWN,
                        retryCount = 1,
                        lastFailedAtEpochMs = 8L,
                    ),
                ),
                migratedFromLegacyAtEpochMs = 5L,
            )
        }
        val fresh = LegacyArtifactMigration.migrateChapter(
            LegacyChapterSnapshot(
                pages = mapOf("keep.jpg" to LegacyPageFacts(displayablePage(), CleanedFileState.VALID)),
            ),
        )
        val merged = LegacyArtifactMigration.resyncManifest(prior, fresh)

        merged.durableFailures.keys shouldBe setOf("keep.jpg:TRANSLATION")
        // First-migration stamp survives a resync.
        merged.migratedFromLegacyAtEpochMs shouldBe 5L
    }

    @Test
    fun `resync keeps the prior glossary pointer when content is unchanged`() {
        val snapshot = LegacyChapterSnapshot(pages = emptyMap(), glossary = mapOf("a" to "b"))
        val prior = LegacyArtifactMigration.migrateChapter(snapshot).copy(
            glossary = GlossaryPointer(
                fileName = "c_artifacts/glossary/chapter.glossary.3.json",
                version = 3,
                versionFingerprint = StageFingerprints.glossaryVersion(mapOf("a" to "b")),
            ),
        )
        val fresh = LegacyArtifactMigration.migrateChapter(snapshot)
        val merged = LegacyArtifactMigration.resyncManifest(prior, fresh)
        merged.glossary shouldBe prior.glossary
    }
}
