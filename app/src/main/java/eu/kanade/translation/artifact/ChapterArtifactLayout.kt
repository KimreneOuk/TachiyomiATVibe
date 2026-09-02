package eu.kanade.translation.artifact

import java.security.MessageDigest

/**
 * TachiyomiAT: chapter-scoped artifact storage layout (lifecycle contract §15).
 *
 * Given a chapter translation file base name `X` (e.g. `Group_Chapter 1`), the
 * manifest is the sibling document `X.manifest.json` — matching the existing
 * `X.glossary.json` / `X.summary.json` sidecar convention — and all immutable
 * payloads live under the chapter directory `X_artifacts/`, next to the legacy
 * `X_images/` companion directory:
 *
 * ```
 * X_artifacts/
 *   artifacts/<pageIdentity>/<stage>/<fingerprint>.json
 *   images/<pageIdentity>/<generation>-<fingerprint>.<ext>
 *   context/<naturalPageIndex>-<checkpointHash>.json
 *   generations/<generationId>.json
 *   glossary/chapter.glossary.<version>.json
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
 * The layout is additive: the legacy flat translation file and companion image
 * directory remain authoritative for the live reader until the store
 * transaction and UI phases switch consumption.
 */
class ChapterArtifactLayout(chapterBaseName: String) {

    val chapterKey: String = chapterBaseName
    val manifestFileName: String = "$chapterBaseName.manifest.json"
    val artifactRootDirectoryName: String = "${chapterBaseName}_artifacts"

    private val artifactDirectoryName = "$artifactRootDirectoryName/artifacts"
    private val imageDirectoryName = "$artifactRootDirectoryName/images"
    private val pageSnapshotDirectoryName = "$artifactRootDirectoryName/pages"

    // Legacy scene-checkpoint sidecar directory: no longer written, still swept
    // by the retention reconciler so pre-refactor files get reclaimed.
    private val contextDirectoryName = "$artifactRootDirectoryName/context"
    private val generationDirectoryName = "$artifactRootDirectoryName/generations"
    private val glossaryDirectoryName = "$artifactRootDirectoryName/glossary"

    // T917 Phase 3 (D9): durable attempt-ledger sidecar directory. One bounded
    // document per chapter — not versioned sidecars — so a single fixed name.
    private val attemptsDirectoryName = "$artifactRootDirectoryName/attempts"

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

    /** Relative path for a legacy companion image still produced by the live pipeline. */
    fun legacyCompanionImageFile(fileName: String): String = "${chapterKey}_images/$fileName"

    /** Durable complete page snapshots used by the live store bridge. */
    fun committedPageSnapshotFile(pageKey: String, generationId: String): String =
        "$artifactRootDirectoryName/pages/${pageSegment(pageKey)}/committed-${generationSegment(generationId)}.json"

    fun candidatePageSnapshotFile(pageKey: String, generationId: String): String =
        "$artifactRootDirectoryName/pages/${pageSegment(pageKey)}/candidate-${generationSegment(generationId)}.json"

    val imagesRootDirectory: String get() = imageDirectoryName
    val stageArtifactsRootDirectory: String get() = artifactDirectoryName
    val generationsRootDirectory: String get() = generationDirectoryName
    val glossaryDirectory: String get() = glossaryDirectoryName

    fun generationFile(generationId: String): String =
        "$generationDirectoryName/${generationSegment(generationId)}.json"

    fun glossaryFile(version: Int): String = "$glossaryDirectoryName/chapter.glossary.$version.json"

    /** T917 Phase 3 (D9): the chapter's single durable attempt-ledger document. */
    val attemptLedgerFileName: String get() = "$attemptsDirectoryName/ledger.json"

    /** Root-relative managed directories the retention reconciler may sweep. */
    val managedDirectories: List<String> = listOf(
        artifactDirectoryName,
        imageDirectoryName,
        pageSnapshotDirectoryName,
        contextDirectoryName,
        generationDirectoryName,
        glossaryDirectoryName,
        attemptsDirectoryName,
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

        /** Builds the layout from a translation file name such as `Group_Chapter 1.json`. */
        fun fromTranslationFileName(translationFileName: String): ChapterArtifactLayout =
            ChapterArtifactLayout(translationFileName.substringBeforeLast('.'))
    }
}
