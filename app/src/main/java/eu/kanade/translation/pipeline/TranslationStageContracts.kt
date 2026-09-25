package eu.kanade.translation.pipeline

import eu.kanade.translation.artifact.ArtifactOrigin
import eu.kanade.translation.model.PageStage
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.storage.ChapterTranslationStore
import java.security.MessageDigest

/**
 * Lease-layer provenance of a page write (  three-origin model, lifecycle
 * contract §12). Priority on one page: MANUAL > AUTO > BATCH.
 *
 * - [MANUAL]: a reader tap / foreground single-page intent. Evicts an
 *   in-flight [AUTO] lease at acquisition (fenced fail-closed for the evicted
 *   holder) and waits-and-attaches on [BATCH] (never preempts it).
 * - [AUTO]: reader-side automatic maintenance (rolling auto window, legacy
 *   auto window, stranded-page sweep). Never preempts anything.
 * - [BATCH]: an ordered chapter batch run.
 *
 * Two-vocabulary rule: this enum is the LEASE vocabulary only. Durable
 * provenance keeps the stable two-value [ArtifactOrigin] vocabulary and the
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
 * Durable-provenance mapping for a lease origin (two-vocabulary rule above):
 * MANUAL and AUTO both keep the stable reader-adhoc provenance so planner
 * parsing and -adjacent reuse evidence stay unchanged.
 */
fun PageWriteOrigin?.toArtifactOrigin(): ArtifactOrigin = when (this) {
    PageWriteOrigin.MANUAL, PageWriteOrigin.AUTO, null -> ArtifactOrigin.READER_ADHOC
    PageWriteOrigin.BATCH -> ArtifactOrigin.BATCH
}

/** Small immutable reference queued after OCR has committed and native resources are released. */
data class OcrReadyPageRef(
    val pageKey: String,
    val pageIndex: Int,
    val generation: Long,
    val blockFingerprints: List<String>,
)

/**
 * Detection/OCR-owned fields and the preconditions captured before the native
 * recognition pass (Phase 3). The live pipeline fuses detection and OCR into
 * one recognition pass; this patch carries both. A stale worker — wrong
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
     * 20 (Stage-6 slice A): the frozen profile content fingerprint
     *  the translation request was built from. `null` keeps the
     * pre- merge behavior byte-identical (legacy callers); non-null makes
     * the merge REJECT when the manifest's currently frozen profile carries a
     * different content fingerprint (stale-profile protection).
     */
    val profileContentFingerprint: String? = null,
    /**
     * 20: the envelope-plan fingerprint  the dispatch was
     * planned under. `null` keeps the pre- merge behavior byte-identical;
     * non-null makes the merge REJECT when the manifest's `envelopePlan`
     * pointer carries a different content fingerprint (stale-plan commit
     * protection; rejected commits never advance any frontier).
     */
    val envelopePlanFingerprint: String? = null,
)

/** Inpaint-owned fields and the durable OCR/mask identity they were derived from. */
data class InpaintStagePatch(
    val pageKey: String,
    val generation: Long,
    val expectedOcrBlockFingerprints: List<String>,
    val expectedMaskFingerprint: String,
    val cleanedImageName: String,
    val inpaintRevision: Int,
    val inpaintingModeUsed: String?,
    val inpaintStatus: String,
    val errorMessage: String? = null,
    val expectedPageVersion: Long? = null,
    val expectedLeaseToken: Long? = null,
    val expectedCandidateGenerationId: String? = null,
    val expectedDependencyFingerprint: String? = null,
    val expectedArtifactPageVersion: Long? = null,
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

sealed interface StagePatch {
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

    data class Inpaint(val value: InpaintStagePatch) : StagePatch {
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
fun TranslationBlock.ocrFingerprint(): String {
    val canonical = buildString {
        appendField(text)
        appendField(width.toRawBits())
        appendField(height.toRawBits())
        appendField(x.toRawBits())
        appendField(y.toRawBits())
        appendField(symHeight.toRawBits())
        appendField(symWidth.toRawBits())
        appendField(angle.toRawBits())
        appendField(label)
        appendField(score.toRawBits())
        appendField(parentX.toRawBits())
        appendField(parentY.toRawBits())
        appendField(parentWidth.toRawBits())
        appendField(parentHeight.toRawBits())
        appendField(direction)
        appendField(panelIndex)
        appendField(panelAssignment)
        appendField(panelContainment.toRawBits())
        appendField(bubbleIndex)
        segmentationMask?.let { mask ->
            appendField(mask.width)
            appendField(mask.height)
            mask.bounds.forEach(::appendField)
            mask.runs.forEach(::appendField)
            appendField(mask.score.toRawBits())
        } ?: appendField(null)
    }
    return MessageDigest.getInstance("SHA-256")
        .digest(canonical.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}

fun PageTranslation.ocrBlockFingerprints(): List<String> = blocks.map { it.ocrFingerprint() }

fun PageTranslation.inpaintMaskFingerprint(): String =
    fingerprint(
        inpaintMaskBoxes.joinToString("|") { box ->
            "${box.x1},${box.y1},${box.x2},${box.y2},${box.label}"
        },
    )

private fun fingerprint(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte) }

private fun StringBuilder.appendField(value: Any?) {
    val text = value?.toString() ?: "<null>"
    append(text.length).append(':').append(text).append('|')
}
