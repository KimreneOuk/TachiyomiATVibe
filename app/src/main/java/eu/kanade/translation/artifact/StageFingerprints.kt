package eu.kanade.translation.artifact

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.stableFingerprint
import java.security.MessageDigest

/**
 * TachiyomiAT: deterministic provenance fingerprints for the chapter artifact
 * manifest (artifact lifecycle contract §§4–8). Each builder canonicalizes its
 * direct inputs and configuration with length-prefixed fields, so no delimiter
 * ambiguity can make two different input sets collide.
 *
 * A stage is reusable only when its recorded fingerprint equals the newly
 * computed expected fingerprint; timestamps and queue state are never inputs.
 */
object StageFingerprints {

    /**
     * Fingerprint a stage configuration when the large content-addressed
     * artifact is not available yet.  Keeping configuration fields length
     * prefixed makes this safe for persisted page provenance and tests.
     */
    fun configuration(stage: ArtifactStage, vararg fields: Any?): String =
        fingerprintIndexed(listOf("configuration", stage.name) + fields.toList())

    /** Detection, segmentation, masks, and reading order (contract §4). */
    fun detection(
        sourceHash: String,
        detectorModelHash: String,
        segmenterModelHash: String,
        nativeProtocolVersion: Int,
        thresholds: String,
        maskPostprocessVersion: Int,
        panelAssignmentVersion: Int,
        readingOrderVersion: Int,
    ): String = fingerprint(
        "detection",
        sourceHash,
        detectorModelHash,
        segmenterModelHash,
        nativeProtocolVersion,
        thresholds,
        maskPostprocessVersion,
        panelAssignmentVersion,
        readingOrderVersion,
    )

    /** OCR source blocks (contract §5). */
    fun ocr(
        detectionArtifactId: String,
        ocrEngineVersion: String,
        ocrModelHash: String,
        sourceLanguage: String,
        preprocessingVersion: Int,
        textNormalizationVersion: Int,
    ): String = fingerprint(
        "ocr",
        detectionArtifactId,
        ocrEngineVersion,
        ocrModelHash,
        sourceLanguage,
        preprocessingVersion,
        textNormalizationVersion,
    )

    /** Cleaned image / inpaint artifact (contract §6). */
    fun inpaint(
        sourceHash: String,
        maskArtifactId: String,
        inpaintEngineVersion: String,
        inpaintModelHash: String,
        inpaintMode: String,
        inpaintSettings: String,
        cleanupRevision: Int,
    ): String = fingerprint(
        "inpaint",
        sourceHash,
        maskArtifactId,
        inpaintEngineVersion,
        inpaintModelHash,
        inpaintMode,
        inpaintSettings,
        cleanupRevision,
    )

    /** Translation artifact (contract §7). The context checkpoint is an input. */
    fun translation(
        orderedOcrBlockIdsAndTextHashes: List<String>,
        sourceLanguage: String,
        targetLanguage: String,
        provider: String,
        model: String,
        modelSettings: String,
        promptProtocolVersion: Int,
        contextInputCheckpointHash: String,
        glossaryVersion: String,
        profileLedgerVersion: String,
        batchRelationshipAmbiguityPriorValue: String,
        ambiguityPriorSchemaVersion: Int,
    ): String = fingerprintIndexed(
        "translation",
        orderedOcrBlockIdsAndTextHashes,
        sourceLanguage,
        targetLanguage,
        provider,
        model,
        modelSettings,
        promptProtocolVersion,
        contextInputCheckpointHash,
        glossaryVersion,
        profileLedgerVersion,
        batchRelationshipAmbiguityPriorValue,
        ambiguityPriorSchemaVersion,
    )

    /** Render layout artifact (contract §8). */
    fun layout(
        translationArtifactId: String,
        cleanedImageArtifactIdOrOriginalSourceId: String,
        layoutEngineVersion: String,
        fontIdentity: String,
        fontScalePreferences: String,
        stylePreferences: String,
        outputDimensions: String,
    ): String = fingerprint(
        "layout",
        translationArtifactId,
        cleanedImageArtifactIdOrOriginalSourceId,
        layoutEngineVersion,
        fontIdentity,
        fontScalePreferences,
        stylePreferences,
        outputDimensions,
    )

    /**
     * Chapter glossary version fingerprint: vocabulary hints only (contract
     * §14.8). Each key and value is fed as its own length-prefixed field in
     * sorted-key order, so no comma/equals composite can alias a different
     * key/value split.
     */
    fun glossaryVersion(entries: Map<String, String>): String {
        val fields = mutableListOf<Any?>("glossary")
        entries.keys.sorted().forEach { key ->
            fields += key
            fields += entries.getValue(key)
        }
        return fingerprintIndexed(fields)
    }

    /** Failure fingerprint: identifies the failing configuration/source shape (contract §13). */
    fun failure(stage: ArtifactStage, failureMessage: String?, stageStatuses: List<String>): String =
        fingerprintIndexed("failure", stage.name, failureMessage ?: "", stageStatuses)

    /** Bundle fingerprint of a committed display bundle (contract §9). */
    fun committedBundle(
        sourceIdentity: SourceIdentity?,
        displayBase: DisplayBaseReference,
        translationFingerprint: String?,
        layoutFingerprint: String?,
    ): String = fingerprint(
        "bundle",
        sourceIdentity?.sha256 ?: "",
        displayBase.kind.name,
        displayBase.fileName ?: "",
        translationFingerprint ?: "",
        layoutFingerprint ?: "",
    )

    /** Fingerprint of the complete live-store page snapshot used by Phase 3. */
    fun pageSnapshot(page: PageTranslation): String = fingerprintIndexed(
        "page-snapshot",
        page.sourceFileName,
        page.cleanedImageName,
        page.ocrStatus,
        page.translationStatus,
        page.inpaintStatus,
        page.renderStatus,
        page.inpaintRevision,
        page.sourceFingerprint,
        page.detectionFingerprint,
        page.ocrFingerprint,
        page.inpaintFingerprint,
        page.translationFingerprint,
        page.layoutFingerprint,
        page.translationOrigin,
        page.batchContextCheckpointHash,
        page.batchContextComplete,
        page.retryCount,
        page.attemptCount,
        page.errorMessage,
        page.blocks.map { it.stableFingerprint() },
    )

    private fun fingerprint(vararg fields: Any?): String = fingerprintIndexed(fields.toList())

    /**
     * Canonical encoding where every field is its own length-prefixed unit and
     * list elements carry their index, so no composite input can collide with
     * a different element split.
     */
    private fun fingerprintIndexed(vararg fields: Any?): String = fingerprintIndexed(fields.toList())

    private fun fingerprintIndexed(fields: List<Any?>): String {
        val canonical = buildString {
            fields.forEach { value ->
                when (value) {
                    is List<*> -> value.forEachIndexed { index, element ->
                        appendField("[$index]")
                        appendField(element?.toString() ?: "<null>")
                    }
                    else -> appendField(value?.toString() ?: "<null>")
                }
            }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun StringBuilder.appendField(value: String) {
        append(value.length).append(':').append(value).append('|')
    }
}
