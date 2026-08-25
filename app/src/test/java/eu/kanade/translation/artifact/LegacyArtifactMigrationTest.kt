package eu.kanade.translation.artifact

import eu.kanade.translation.model.PageDisplayState
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TranslationBlock
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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

    private fun supportedMetadata(
        sourcePreservation: LegacyPreservationState = LegacyPreservationState.INTENT,
        glossaryPreservation: LegacyPreservationState = LegacyPreservationState.NONE,
        health: LegacyMigrationHealth = LegacyMigrationHealth.INITIAL_CUTOVER,
    ) = LegacyMigrationMetadata(
        sourceFileName = "Chapter 1.json",
        sourcePreservation = sourcePreservation,
        requestedSourceFileName = "Chapter 1.json.migrated",
        resolvedSourceFileName = when (sourcePreservation) {
            LegacyPreservationState.PRESERVED,
            LegacyPreservationState.DELETED,
            -> "Chapter 1.json.migrated"
            else -> null
        },
        sourcePreservedAtEpochMs = when (sourcePreservation) {
            LegacyPreservationState.PRESERVED,
            LegacyPreservationState.DELETED,
            -> 43L
            else -> null
        },
        glossaryPreservation = glossaryPreservation,
        requestedGlossaryFileName = glossaryPreservation.takeUnless {
            it == LegacyPreservationState.NONE
        }?.let { "Chapter 1.glossary.json" },
        resolvedGlossaryFileName = when (glossaryPreservation) {
            LegacyPreservationState.PRESERVED,
            LegacyPreservationState.DELETED,
            -> "Chapter 1.glossary.json"
            else -> null
        },
        sourceIdentity = LegacySourceIdentity("a".repeat(64), 12L, 3L),
        glossaryIdentity = glossaryPreservation.takeUnless {
            it == LegacyPreservationState.NONE
        }?.let { LegacySourceIdentity("b".repeat(64), 8L, 4L) },
        sourcePageCount = 1,
        sourcePageKeyDigest = "a".repeat(64),
        migratedByVersionCode = 63L,
        migratedAtEpochMs = 42L,
        health = health,
        lastVerifiedByVersionCode = health.takeUnless {
            it == LegacyMigrationHealth.INITIAL_CUTOVER
        }?.let { 64L },
        lastVerifiedAtEpochMs = health.takeUnless {
            it == LegacyMigrationHealth.INITIAL_CUTOVER
        }?.let { 43L },
    )

    private fun legacySnapshot(identity: LegacySourceIdentity?, corrupt: Boolean = false) =
        LegacyChapterSnapshot(
            pages = mapOf("page.jpg" to LegacyPageFacts(PageTranslation())),
            translationFileCorrupt = corrupt,
            legacyIdentity = identity,
            sourceFileName = identity?.let { "Chapter 1.json" },
            migratedByVersionCode = 63L,
            migratedAtEpochMs = 42L,
        )

    private fun PageArtifactRecord.shouldHaveProvisionalLegacyCommit(): CommittedBundleMetadata {
        val committed = committed.shouldNotBeNull()
        committed.origin shouldBe ArtifactOrigin.LEGACY
        committed.provisional shouldBe true
        return committed
    }

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
        // Compatibility committed data is not the strict DISPLAY_READY state.
        record.shouldHaveProvisionalLegacyCommit()
        record.displayState shouldBe PageDisplayState.ORIGINAL_ONLY
        // Compatibility visibility is explicit and separate.
        val visible = record.legacyVisible.shouldNotBeNull()
        visible.fileName shouldBe "page.cleaned.abc.jpg"
        visible.legacyLayout shouldBe true
        // Incomplete translation stays diagnostic/retryable.
        record.translation?.status shouldBe ArtifactStageStatus.PARTIAL
        record.candidate.shouldBeNull()
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
        record.shouldHaveProvisionalLegacyCommit()
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
        record.shouldHaveProvisionalLegacyCommit()
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
        record.shouldHaveProvisionalLegacyCommit()
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
        record.shouldHaveProvisionalLegacyCommit()
        LegacyArtifactMigration.blockGeometryIsValid(page) shouldBe false
    }

    @Test
    fun `recognized blocks without display stages stay original-only with compatibility data`() {
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
        record.shouldHaveProvisionalLegacyCommit()
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
        record.shouldHaveProvisionalLegacyCommit()
        record.translation?.status shouldBe ArtifactStageStatus.PARTIAL
    }

    @Test
    fun `persisted running stage becomes retryable with a compatibility commit`() {
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
        record.shouldHaveProvisionalLegacyCommit()
        record.candidate.shouldBeNull()
    }

    @Test
    fun `missing cleaned file never points the reader at a missing file`() {
        val page = displayablePage()
        val record = LegacyArtifactMigration.migratePage(
            "page.jpg",
            LegacyPageFacts(page, CleanedFileState.MISSING),
        )
        val committed = record.shouldHaveProvisionalLegacyCommit()
        committed.displayBase.kind shouldBe DisplayBaseKind.ORIGINAL_SOURCE
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
        record.shouldHaveProvisionalLegacyCommit()
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
        record.shouldHaveProvisionalLegacyCommit()
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
        record.shouldHaveProvisionalLegacyCommit()
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
        record.shouldHaveProvisionalLegacyCommit()
        record.legacyVisible.shouldBeNull()
        record.displayState shouldBe PageDisplayState.FAILED_NO_RESULT
    }

    @Test
    fun `migration metadata round trips identities digest and health`() {
        val sourceIdentity = LegacySourceIdentity("a".repeat(64), 12L, 3L)
        val glossaryIdentity = LegacySourceIdentity("b".repeat(64), 8L, 4L)
        val snapshot = LegacyChapterSnapshot(
            pages = mapOf(
                "b.jpg" to LegacyPageFacts(PageTranslation(), CleanedFileState.NONE_RECORDED),
                "a.jpg" to LegacyPageFacts(PageTranslation(), CleanedFileState.NONE_RECORDED),
            ),
            glossary = mapOf("sensei" to "teacher"),
            legacyIdentity = sourceIdentity,
            sourceFileName = "Chapter 1.json",
            glossaryFileName = "Chapter 1.glossary.json",
            glossaryIdentity = glossaryIdentity,
            migratedByVersionCode = 63L,
            migratedAtEpochMs = 42L,
        )
        val manifest = LegacyArtifactMigration.migrateChapter(snapshot)
        val metadata = manifest.legacyMigration.shouldNotBeNull()

        metadata.formatVersion shouldBe LegacyMigrationMetadata.FORMAT_VERSION
        metadata.sourceIdentity shouldBe sourceIdentity
        metadata.glossaryIdentity shouldBe glossaryIdentity
        metadata.sourceFileName shouldBe "Chapter 1.json"
        metadata.requestedGlossaryFileName shouldBe "Chapter 1.glossary.json"
        metadata.sourcePageCount shouldBe 2
        metadata.sourcePageKeyDigest shouldBe LegacyArtifactMigration.legacyPageKeyDigest(
            listOf("a.jpg", "b.jpg"),
        )
        metadata.migratedByVersionCode shouldBe 63L
        metadata.migratedAtEpochMs shouldBe 42L
        metadata.health shouldBe LegacyMigrationHealth.INITIAL_CUTOVER
        metadata.sourcePreservation shouldBe LegacyPreservationState.INTENT
        metadata.glossaryPreservation shouldBe LegacyPreservationState.INTENT
        metadata.isSupported shouldBe true

        val json = Json { encodeDefaults = true }
        val decoded = json.decodeFromString<ChapterArtifactManifest>(json.encodeToString(manifest))
        decoded.legacyMigration shouldBe metadata
    }

    @Test
    fun `migration metadata accepts complete preservation lifecycle states`() {
        listOf(
            supportedMetadata(
                sourcePreservation = LegacyPreservationState.INTENT,
                glossaryPreservation = LegacyPreservationState.NONE,
            ),
            supportedMetadata(
                sourcePreservation = LegacyPreservationState.PRESERVED,
                glossaryPreservation = LegacyPreservationState.PRESERVED,
                health = LegacyMigrationHealth.VERIFIED_WITH_WARNINGS,
            ),
            supportedMetadata(
                sourcePreservation = LegacyPreservationState.DELETED,
                glossaryPreservation = LegacyPreservationState.DELETED,
                health = LegacyMigrationHealth.VERIFIED,
            ),
        ).forEach { metadata ->
            metadata.isSupported shouldBe true
        }
    }

    @Test
    fun `migration metadata fails closed for malformed preservation combinations`() {
        val malformed = listOf(
            "missing source metadata" to LegacyMigrationMetadata(),
            "unsupported format" to supportedMetadata().copy(
                formatVersion = LegacyMigrationMetadata.FORMAT_VERSION + 1,
            ),
            "source none" to supportedMetadata().copy(
                sourcePreservation = LegacyPreservationState.NONE,
            ),
            "source intent without requested name" to supportedMetadata().copy(
                requestedSourceFileName = null,
            ),
            "source intent with resolved name" to supportedMetadata().copy(
                resolvedSourceFileName = "Chapter 1.json.migrated",
            ),
            "source intent with preservation timestamp" to supportedMetadata().copy(
                sourcePreservedAtEpochMs = 43L,
            ),
            "source preserved without resolved name" to supportedMetadata(
                sourcePreservation = LegacyPreservationState.PRESERVED,
            ).copy(resolvedSourceFileName = null),
            "source preserved without timestamp" to supportedMetadata(
                sourcePreservation = LegacyPreservationState.PRESERVED,
            ).copy(sourcePreservedAtEpochMs = null),
            "source deleted before verified" to supportedMetadata(
                sourcePreservation = LegacyPreservationState.DELETED,
                health = LegacyMigrationHealth.VERIFIED_WITH_WARNINGS,
            ),
            "glossary none with identity" to supportedMetadata().copy(
                glossaryIdentity = LegacySourceIdentity("b".repeat(64), 8L, 4L),
            ),
            "glossary intent without requested name" to supportedMetadata(
                glossaryPreservation = LegacyPreservationState.INTENT,
            ).copy(requestedGlossaryFileName = null),
            "glossary intent without identity" to supportedMetadata(
                glossaryPreservation = LegacyPreservationState.INTENT,
            ).copy(glossaryIdentity = null),
            "glossary preserved without resolved name" to supportedMetadata(
                glossaryPreservation = LegacyPreservationState.PRESERVED,
            ).copy(resolvedGlossaryFileName = null),
            "glossary deleted before verified" to supportedMetadata(
                glossaryPreservation = LegacyPreservationState.DELETED,
                health = LegacyMigrationHealth.VERIFIED_WITH_WARNINGS,
            ),
            "verification fields on initial health" to supportedMetadata().copy(
                lastVerifiedByVersionCode = 64L,
                lastVerifiedAtEpochMs = 43L,
            ),
            "verified without complete verification fields" to supportedMetadata(
                health = LegacyMigrationHealth.VERIFIED,
            ).copy(lastVerifiedAtEpochMs = null),
            "preservation before migration" to supportedMetadata(
                sourcePreservation = LegacyPreservationState.PRESERVED,
            ).copy(sourcePreservedAtEpochMs = 41L),
            "verification before migration" to supportedMetadata(
                health = LegacyMigrationHealth.VERIFIED,
            ).copy(lastVerifiedAtEpochMs = 41L),
            "invalid source identity hash" to supportedMetadata().copy(
                sourceIdentity = LegacySourceIdentity("bad", 12L, 3L),
            ),
            "invalid source identity length" to supportedMetadata().copy(
                sourceIdentity = LegacySourceIdentity("a".repeat(64), -1L, 3L),
            ),
            "invalid source identity timestamp" to supportedMetadata().copy(
                sourceIdentity = LegacySourceIdentity("a".repeat(64), 12L, -1L),
            ),
            "invalid glossary identity" to supportedMetadata(
                glossaryPreservation = LegacyPreservationState.INTENT,
            ).copy(
                glossaryIdentity = LegacySourceIdentity("bad", 8L, 4L),
            ),
        )

        malformed.forEach { (_, metadata) ->
            metadata.isSupported shouldBe false
        }
    }

    @Test
    fun `legacy page key digest is independent of input order`() {
        val forward = LegacyArtifactMigration.legacyPageKeyDigest(listOf("b.jpg", "a.jpg"))
        val reverse = LegacyArtifactMigration.legacyPageKeyDigest(listOf("a.jpg", "b.jpg"))
        forward shouldBe reverse
        forward.length shouldBe LegacyMigrationMetadata.SHA256_HEX_LENGTH
    }
}
