package eu.kanade.translation.persistence.artifact

import eu.kanade.translation.model.PageTranslation
import kotlinx.serialization.Serializable

/**
 *  Stage 1 (schemas contract §1.2, DTO only): origin-neutral durable
 * evidence that one page's OCR stage completed, publishable by any lane. The
 * `checkpointOcr` transaction semantics (CAS ordering, close-vs-rebase, lease
 * release order) belong to the state/transactions contract  and are
 * deliberately NOT implemented here.
 *
 * Serialized only through the shared [ArtifactDocumentJson] instance
 *
 */

/**
 * The committed display that must stay visible through the checkpoint
 * (preserve rule; semantics ). Pure identity reference.
 */
@Serializable
data class CommittedDisplayRef(
    val generationId: String,
    val bundleFingerprint: String? = null,
    val pageSnapshotFileName: String? = null,
)

/**
 * The per-page OCR checkpoint sidecar document (schemas contract §1.2). Field
 * declaration order is the canonical byte order.
 */
@Serializable
data class PageOcrCheckpoint(
    val schemaVersion: Int = SCHEMA_VERSION,
    val kind: String = KIND,
    val pageKey: String,
    /**
     * Null only when unprovable from the key set (legacy rule,
     * `PageArtifactRecord.naturalPageIndex` precedent). Required for corpus
     * ordering; a checkpoint without an index and without provable order is
     * rejected at planning time — the DTO alone cannot prove chapter order.
     */
    val naturalPageIndex: Int? = null,
    /** Complete source identity: sha256 + width + height + orientation. */
    val sourceIdentity: SourceIdentity,
    /** Null only when detection was skipped legitimately. */
    val detectionFingerprint: String? = null,
    /** Existing `StageFingerprints.ocr(...)` value. */
    val ocrFingerprint: String,
    /** The semantic `PageOcrContentFingerprint`. */
    val ocrContentFingerprint: String,
    /** Immutable OCR-complete `PageTranslation` snapshot sidecar. */
    val ocrPageSnapshotPointer: SidecarPointer,
    /** Revision gate precedent (`PageTranslation.CURRENT_INPAINT_REVISION`). */
    val inpaintMaskRevision: Int = PageTranslation.CURRENT_INPAINT_REVISION,
    /** The committed display preserved through the checkpoint. */
    val priorCommittedDisplay: CommittedDisplayRef? = null,
    /** Origin-neutral consumption with origin-recorded provenance. */
    val producedByOrigin: ArtifactOrigin,
    /** Transaction identity; never fingerprinted. */
    val producerGenerationId: String? = null,
    /** Operational only. */
    val checkpointedAtEpochMs: Long,
) {
    /** 01/SC-02 semantic validation; null when the document is usable. */
    fun validationError(): String? = when {
        schemaVersion != SCHEMA_VERSION -> "unsupported schemaVersion: $schemaVersion"
        kind != KIND -> "wrong kind: $kind"
        pageKey.isBlank() -> "blank pageKey"
        naturalPageIndex != null && naturalPageIndex < 0 -> "negative naturalPageIndex"
        !sourceIdentity.isComplete -> "incomplete sourceIdentity"
        detectionFingerprint?.isSha256Hex() == false -> "detectionFingerprint is not sha256 hex"
        !ocrFingerprint.isSha256Hex() -> "ocrFingerprint is not sha256 hex"
        !ocrContentFingerprint.isSha256Hex() -> "ocrContentFingerprint is not sha256 hex"
        !ocrPageSnapshotPointer.isWellFormed() -> "ocrPageSnapshotPointer malformed"
        inpaintMaskRevision < PageTranslation.CURRENT_INPAINT_REVISION ->
            "stale inpaintMaskRevision: $inpaintMaskRevision"
        priorCommittedDisplay?.generationId?.isBlank() == true -> "blank priorCommittedDisplay generationId"
        producedByOrigin != ArtifactOrigin.BATCH && producedByOrigin != ArtifactOrigin.READER_ADHOC ->
            "producedByOrigin must be BATCH or READER_ADHOC"
        checkpointedAtEpochMs <= 0L -> "non-positive checkpointedAtEpochMs"
        else -> null
    }

    val isSemanticallyValid: Boolean
        get() = validationError() == null

    companion object {
        const val SCHEMA_VERSION = 1
        const val KIND = "PAGE_OCR_CHECKPOINT"
    }
}
