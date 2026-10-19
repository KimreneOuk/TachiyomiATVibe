package eu.kanade.translation.persistence

import eu.kanade.translation.model.PageStage
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.PageTranslationView
import eu.kanade.translation.model.TranslationBlockView
import eu.kanade.translation.model.StageFingerprints

/**
 * Lease-layer provenance of a page write. Priority on one page is
 * MANUAL > AUTO > BATCH.
 *
 * - [MANUAL]: a reader tap / foreground single-page intent. Evicts an
 *   in-flight [AUTO] lease at acquisition (fenced fail-closed for the evicted
 *   holder) and waits-and-attaches on [BATCH] (never preempts it).
 * - [AUTO]: reader-side automatic maintenance (rolling auto window,
 *   stranded-page sweep). Never preempts anything.
 * - [BATCH]: an ordered chapter batch run.
 *
 * This enum is for lease ownership only. Durable provenance keeps the
 * separate [ArtifactOrigin] vocabulary and the
 * `pageTranslationOrigin` string stamp — map through [toArtifactOrigin] and
 * never stamp `"MANUAL"`/`"AUTO"` strings (`PageWorkPlanner.stageEvidence`
 * parses the stamp with `ArtifactOrigin.valueOf`; unmapped names would
 * silently degrade provenance to UNKNOWN and shift reuse evidence).
 */
enum class PageWriteOrigin {
    MANUAL,
    AUTO,
    BATCH,
}

/**
 * Maps a lease origin to durable provenance:
 * MANUAL and AUTO both keep the stable reader-adhoc provenance so planner
 * parsing and reuse evidence stay unchanged.
 */
fun PageWriteOrigin?.toArtifactOrigin(): ArtifactOrigin = when (this) {
    PageWriteOrigin.MANUAL, PageWriteOrigin.AUTO, null -> ArtifactOrigin.READER_ADHOC
    PageWriteOrigin.BATCH -> ArtifactOrigin.BATCH
}

/**
 * Detection/OCR-owned fields and preconditions captured before recognition.
 * The live pipeline performs both in one pass. A stale worker — wrong
 * generation, wrong page version, or a changed prior OCR identity — is
 * rejected and cannot clobber newer work.
 */
data class OcrStagePatch(
    val pageKey: String,
    val generation: Long,
    /** Page version observed before the native pass started. */
    val expectedPageVersion: Long,
    /** OCR identity of the blocks being replaced, translation-independent. */
    val expectedPriorOcrFingerprints: List<String>,
    val ocrResult: PageTranslation,
    val errorMessage: String? = null,
    /** Lease fencing token captured before native recognition began. */
    val expectedLeaseToken: Long? = null,
    /** Artifact candidate generation captured before native recognition began. */
    val expectedCandidateGenerationId: String? = null,
    /** Artifact dependency fingerprint captured before native recognition began. */
    val expectedDependencyFingerprint: String? = null,
    /** Artifact-manifest page version captured before native recognition began. */
    val expectedArtifactPageVersion: Long? = null,
)

/** One detached Pass-1 target and the preconditions captured before the request. */
data class TranslationBlockPatch(
    val blockIndex: Int,
    val expectedOcrFingerprint: String,
    val expectedSourceText: String,
    val expectedTranslation: String,
    val expectedUserEditedAt: Long?,
    val translation: String,
)

/** Translation-owned fields and target-specific merge preconditions. */
data class TranslationStagePatch(
    val pageKey: String,
    val generation: Long,
    val expectedOcrBlockFingerprints: List<String>,
    val expectedSourceTexts: List<String>,
    val blocks: List<TranslationBlockPatch>,
    val translationStatus: String,
    val errorMessage: String? = null,
    val expectedPageVersion: Long? = null,
    val expectedLeaseToken: Long? = null,
    val expectedCandidateGenerationId: String? = null,
    val expectedDependencyFingerprint: String? = null,
    val expectedArtifactPageVersion: Long? = null,
    /**
     * Envelope-plan fingerprint used to plan the dispatch.
     * When present, the merge rejects if the manifest's `envelopePlan`
     * pointer has a different fingerprint. A rejected commit never advances
     * the context frontier.
     */
    val envelopePlanFingerprint: String? = null,
    /** Fresh source bytes observed by the shared per-page lazy fingerprint map. */
    val sourceFingerprint: String? = null,
)

/** Render-owned colors for one block. No image or bitmap is retained. */
data class RenderBlockPatch(
    val blockIndex: Int,
    val expectedBlockFingerprint: String,
    val textColor: Long,
    val strokeColor: Long,
    val strokeWidth: Float,
)

/** Render-owned fields and the cleaned-image identity they join against. */
data class RenderStagePatch(
    val pageKey: String,
    val generation: Long,
    val expectedCleanedImageName: String,
    val expectedInpaintRevision: Int,
    val expectedOcrBlockFingerprints: List<String>,
    val blocks: List<RenderBlockPatch>,
    val renderStatus: String,
    val errorMessage: String? = null,
    val layoutFingerprint: String? = null,
    val expectedPageVersion: Long? = null,
    val expectedLeaseToken: Long? = null,
    val expectedCandidateGenerationId: String? = null,
    val expectedDependencyFingerprint: String? = null,
    val expectedArtifactPageVersion: Long? = null,
)

internal sealed interface StagePatch {
    val pageKey: String
    val generation: Long

    data class Ocr(val value: OcrStagePatch) : StagePatch {
        override val pageKey: String get() = value.pageKey
        override val generation: Long get() = value.generation
    }

    data class Translation(val value: TranslationStagePatch) : StagePatch {
        override val pageKey: String get() = value.pageKey
        override val generation: Long get() = value.generation
    }

    data class Render(val value: RenderStagePatch) : StagePatch {
        override val pageKey: String get() = value.pageKey
        override val generation: Long get() = value.generation
    }
}

sealed interface StagePatchResult {
    data class Accepted(
        val snapshot: ChapterTranslationStore.PageSnapshot,
        val appliedBlockIndices: List<Int> = emptyList(),
    ) : StagePatchResult

    data class Rejected(val reason: String) : StagePatchResult
}

/**
 * One owner's exclusive work lease over a page (lifecycle contract §12). The
 * lease bundles the ownership token with the fencing preconditions the holder
 * must present on every write: the store generation and the page version at
 * acquisition. Batch and reader share this discipline — a page owned by one
 * origin cannot be opened by the other until the owner releases it at an
 * atomic stage boundary.
 */
data class PageStageLease(
    val pageKey: String,
    val stage: PageStage,
    val origin: PageWriteOrigin,
    val generation: Long,
    val pageVersion: Long,
    val token: Long,
    /** Artifact candidate generation observed at acquisition, when present. */
    val candidateGenerationId: String? = null,
    /** Artifact dependency fingerprint observed at acquisition, when present. */
    val dependencyFingerprint: String? = null,
    /** Artifact-manifest page version observed at acquisition, when present. */
    val artifactPageVersion: Long? = null,
)

/** Result of a lease acquisition; only [Granted] carries write ownership. */
sealed interface LeaseAcquisition {
    data class Granted(val lease: PageStageLease) : LeaseAcquisition

    data class Denied(val reason: String, val owner: PageWriteOrigin?) : LeaseAcquisition
}

/** OCR identity excludes mutable translation, render colors, revision flags, and edits. */
fun TranslationBlockView.ocrFingerprint(): String =
    StageFingerprints.canonicalFingerprint(
        buildList {
            add(text)
            add(width.toRawBits())
            add(height.toRawBits())
            add(x.toRawBits())
            add(y.toRawBits())
            add(symHeight.toRawBits())
            add(symWidth.toRawBits())
            add(angle.toRawBits())
            add(label)
            add(score.toRawBits())
            add(parentX.toRawBits())
            add(parentY.toRawBits())
            add(parentWidth.toRawBits())
            add(parentHeight.toRawBits())
            add(direction)
            add(panelIndex)
            add(panelAssignment)
            add(panelContainment.toRawBits())
            add(bubbleIndex)
            segmentationMask?.let { mask ->
                add(mask.width)
                add(mask.height)
                mask.bounds.forEach(::add)
                mask.runs.forEach(::add)
                add(mask.score.toRawBits())
            } ?: add(null)
        },
    )

fun PageTranslationView.ocrBlockFingerprints(): List<String> = blocks.map { it.ocrFingerprint() }

fun PageTranslationView.inpaintMaskFingerprint(): String =
    StageFingerprints.sha256Hex(
        inpaintMaskBoxes.joinToString("|") { box ->
            "${box.x1},${box.y1},${box.x2},${box.y2},${box.label}"
        }.toByteArray(Charsets.UTF_8),
    )
