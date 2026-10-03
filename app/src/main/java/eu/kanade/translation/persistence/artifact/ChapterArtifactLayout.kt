package eu.kanade.translation.persistence.artifact

import java.security.MessageDigest

/**
 * chapter-scoped artifact storage layout (lifecycle contract §15).
 *
 * Given a chapter artifact base name `X` (e.g. `Group_Chapter 1`), the
 * manifest is the sibling document `X.manifest.json` — matching the existing
 * `X.summary.json` sidecar convention — and all immutable
 * payloads live under the chapter directory `X_artifacts/`:
 *
 * ```
 * X_artifacts/
 *   artifacts/<pageIdentity>/<stage>/<fingerprint>.json
 *   images/<pageIdentity>/<generation>-<fingerprint>.<ext>
 *   context/<naturalPageIndex>-<checkpointHash>.json
 *   generations/<generationId>.json
 * ```
 *
 * Every name produced here is a `/`-separated path relative to the chapter's
 * manga directory, so callers can hand them straight to [ChapterDocumentIo].
 *
 * Path-identity rules:
 * - The page directory segment is injective: readable sanitized text plus a
 *   stable hash of the original page key, so `pg/1` and `pg_1` can never
 *   share a directory.
 * - Every dynamic segment (generation id, fingerprint, checkpoint hash) is
 *   validated against a safe charset; unsafe input is replaced by a hashed
 *   form, so separator/traversal/control input can neither escape the managed
 *   tree nor collide with a different value.
 * - File extensions are strict: `[A-Za-z0-9]{1,8}` or rejected.
 *
 * Artifact documents are the sole durable translation representation. Existing
 * flat-file documents are intentionally left untouched and are not part of this
 * layout.
 */
class ChapterArtifactLayout(chapterBaseName: String) {

    val chapterKey: String = chapterBaseName
    val manifestFileName: String = "$chapterBaseName.manifest.json"
    val artifactRootDirectoryName: String = "${chapterBaseName}_artifacts"

    private val artifactDirectoryName = "$artifactRootDirectoryName/artifacts"
    private val imageDirectoryName = "$artifactRootDirectoryName/images"
    private val pageSnapshotDirectoryName = "$artifactRootDirectoryName/pages"

    // Scene-checkpoint sidecar directory.
    private val contextDirectoryName = "$artifactRootDirectoryName/context"
    private val generationDirectoryName = "$artifactRootDirectoryName/generations"

    // Durable attempt-ledger sidecar directory. One bounded
    // document per chapter — not versioned sidecars — so a single fixed name.
    private val attemptsDirectoryName = "$artifactRootDirectoryName/attempts"

    // Versioned sidecar directories. File names are
    // content-addressed `f-<sha256(contentFingerprint)>.json`; page-scoped
    // kinds (OCR checkpoints, layout plans, color preparations) nest under the
    // injective pageSegment(pageKey). All are managed so retention bounds them.
    private val runRecordsDirectoryName = "$artifactRootDirectoryName/runs"
    private val ocrCheckpointDirectoryName = "$artifactRootDirectoryName/ocr"
    private val analysisChunkDirectoryName = "$artifactRootDirectoryName/analysis"
    private val profileDirectoryName = "$artifactRootDirectoryName/profiles"
    private val envelopePlanDirectoryName = "$artifactRootDirectoryName/envelopes"
    private val layoutPlanDirectoryName = "$artifactRootDirectoryName/layout"
    private val colorPreparationDirectoryName = "$artifactRootDirectoryName/color"

    fun stageArtifactFile(pageKey: String, stage: ArtifactStage, fingerprint: String): String =
        listOf(
            artifactDirectoryName,
            pageSegment(pageKey),
            stage.name.lowercase(),
            fingerprintSegment(fingerprint),
        ).joinToString("/").let { "$it.json" }

    fun imageFile(pageKey: String, generationId: String, fingerprint: String, extension: String): String =
        listOf(
            imageDirectoryName,
            pageSegment(pageKey),
            "${generationSegment(generationId)}-${fingerprintSegment(fingerprint)}.${fileExtension(extension)}",
        ).joinToString("/")

    fun imageDirectory(pageKey: String): String = "$imageDirectoryName/${pageSegment(pageKey)}"

    /** Durable complete page snapshots used by the live store bridge. */
    fun committedPageSnapshotFile(pageKey: String, generationId: String): String =
        "$artifactRootDirectoryName/pages/${pageSegment(pageKey)}/committed-${generationSegment(generationId)}.json"

    fun candidatePageSnapshotFile(pageKey: String, generationId: String): String =
        "$artifactRootDirectoryName/pages/${pageSegment(pageKey)}/candidate-${generationSegment(generationId)}.json"

    /**
     * Unique immutable snapshot path used while re-keying a page. The operation
     * token keeps concurrent/stale preparations from overwriting a path that a
     * different transaction may already have published; the manifest pointer
     * chooses the winner.
     */
    fun rekeyedPageSnapshotFile(
        pageKey: String,
        role: String,
        generationId: String,
        operationId: String,
    ): String {
        require(role in setOf("candidate", "committed", "previous"))
        return "$pageSnapshotDirectoryName/${pageSegment(pageKey)}/" +
            "rekey-$role-${generationSegment(generationId)}-${fingerprintSegment(operationId)}.json"
    }

    val imagesRootDirectory: String get() = imageDirectoryName
    val stageArtifactsRootDirectory: String get() = artifactDirectoryName
    val generationsRootDirectory: String get() = generationDirectoryName

    fun generationFile(generationId: String): String =
        "$generationDirectoryName/${generationSegment(generationId)}.json"

    /** The chapter's single durable attempt-ledger document. */
    val attemptLedgerFileName: String get() = "$attemptsDirectoryName/ledger.json"

    // Content-addressed sidecar names per kind.
    // Equal content maps to an equal name, so re-publication is idempotent and
    // first admission can use renameNoReplace. The caller supplies the
    // semantic content fingerprint; the name hashes it.

    val runRecordsRootDirectory: String get() = runRecordsDirectoryName
    val ocrCheckpointsRootDirectory: String get() = ocrCheckpointDirectoryName
    val analysisChunksRootDirectory: String get() = analysisChunkDirectoryName
    val profilesRootDirectory: String get() = profileDirectoryName
    val envelopePlansRootDirectory: String get() = envelopePlanDirectoryName
    val layoutPlansRootDirectory: String get() = layoutPlanDirectoryName
    val colorPreparationsRootDirectory: String get() = colorPreparationDirectoryName

    fun runRecordFile(contentFingerprint: String): String =
        contentAddressedFile(runRecordsDirectoryName, contentFingerprint)

    fun ocrCheckpointFile(pageKey: String, contentFingerprint: String): String =
        pageContentAddressedFile(ocrCheckpointDirectoryName, pageKey, contentFingerprint)

    fun analysisChunkFile(contentFingerprint: String): String =
        contentAddressedFile(analysisChunkDirectoryName, contentFingerprint)

    fun profileFile(contentFingerprint: String): String =
        contentAddressedFile(profileDirectoryName, contentFingerprint)

    fun envelopePlanFile(contentFingerprint: String): String =
        contentAddressedFile(envelopePlanDirectoryName, contentFingerprint)

    fun layoutPlanFile(pageKey: String, contentFingerprint: String): String =
        pageContentAddressedFile(layoutPlanDirectoryName, pageKey, contentFingerprint)

    fun colorPreparationFile(pageKey: String, contentFingerprint: String): String =
        pageContentAddressedFile(colorPreparationDirectoryName, pageKey, contentFingerprint)

    val contextRootDirectory: String get() = contextDirectoryName

    fun contextFile(contentFingerprint: String): String =
        contentAddressedFile(contextDirectoryName, contentFingerprint)

    private fun contentAddressedFile(directory: String, contentFingerprint: String): String =
        "$directory/${fingerprintSegment(contentFingerprint)}.json"

    private fun pageContentAddressedFile(
        directory: String,
        pageKey: String,
        contentFingerprint: String,
    ): String = "$directory/${pageSegment(pageKey)}/${fingerprintSegment(contentFingerprint)}.json"

    /** Root-relative managed directories the retention reconciler may sweep. */
    val managedDirectories: List<String> = listOf(
        artifactDirectoryName,
        imageDirectoryName,
        pageSnapshotDirectoryName,
        contextDirectoryName,
        generationDirectoryName,
        attemptsDirectoryName,
        runRecordsDirectoryName,
        ocrCheckpointDirectoryName,
        analysisChunkDirectoryName,
        profileDirectoryName,
        envelopePlanDirectoryName,
        layoutPlanDirectoryName,
        colorPreparationDirectoryName,
    )

    /** True when [path] is equal to or contained inside a managed directory. */
    fun isManagedPath(path: String): Boolean =
        managedDirectories.any { directory -> path == directory || path.startsWith("$directory/") }

    /**
     * Injective per-page directory segment: sanitized readable text plus a
     * stable hash of the original key. Sanitization alone is many-to-one, so
     * the hash discriminates keys that sanitize identically.
     */
    fun pageSegment(pageKey: String): String {
        val readable = pageKey.replace(Regex("[^a-zA-Z0-9.\\-_]"), "_").take(48).ifEmpty { "page" }
        return "$readable-${sha256Hex(pageKey)}"
    }

    /** Fixed-width identity avoids `g` retaining files owned by `g-other`. */
    fun generationSegment(generationId: String): String = "g-${sha256Hex(generationId)}"

    private fun fingerprintSegment(fingerprint: String): String = "f-${sha256Hex(fingerprint)}"

    private fun fileExtension(extension: String): String {
        require(extension.matches(Regex("^[A-Za-z0-9]{1,8}$"))) {
            "unsafe file extension: $extension"
        }
        return extension
    }

    companion object {
        private val SAFE_SEGMENT = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")

        /** A safe segment never contains separators and is never a traversal component. */
        fun isSafeSegment(value: String): Boolean =
            value.isNotEmpty() &&
                value != "." &&
                value != ".." &&
                !value.contains('/') &&
                !value.contains('\\') &&
                value.none { it.code < 0x20 || it.code == 0x7f } &&
                SAFE_SEGMENT.matches(value)

        private fun sha256Hex(value: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte) }

        /** Builds the layout from the established chapter artifact name, such as `Group_Chapter 1.json`. */
        fun fromArtifactFileName(artifactFileName: String): ChapterArtifactLayout =
            ChapterArtifactLayout(artifactFileName.substringBeforeLast('.'))
    }
}
