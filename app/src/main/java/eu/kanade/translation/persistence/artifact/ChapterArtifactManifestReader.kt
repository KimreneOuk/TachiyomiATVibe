package eu.kanade.translation.persistence.artifact

import com.hippo.unifile.UniFile
import kotlinx.serialization.json.decodeFromStream

internal data class ArtifactManifestProbe(
    val exists: Boolean,
    val manifest: ChapterArtifactManifest?,
)

/**
 * Reads chapter artifact manifest metadata without opening page snapshots or
 * referenced sidecars.
 */
internal object ChapterArtifactManifestReader {

    /** Reads only the small manifest header; page snapshots stay unopened. */
    internal fun probeArtifactManifest(parent: UniFile, fileName: String): ArtifactManifestProbe {
        val layout = ChapterArtifactLayout.fromArtifactFileName(fileName)
        val primary = parent.findFile(layout.manifestFileName)
        if (primary != null && primary.exists()) {
            return ArtifactManifestProbe(exists = true, manifest = readManifest(primary))
        }

        val backup = parent.findFile(AtomicChapterDocuments.backupNameFor(layout.manifestFileName))
        if (backup == null || !backup.exists()) return ArtifactManifestProbe(exists = false, manifest = null)
        // A backup is persisted metadata too. If it exists but cannot be read,
        // propagate that failure instead of treating the chapter as cleanly new.
        return ArtifactManifestProbe(exists = true, manifest = readManifest(backup))
    }

    private fun readManifest(file: UniFile): ChapterArtifactManifest =
        file.openInputStream().use { input ->
            ArtifactDocumentJson.decodeFromStream<ChapterArtifactManifest>(input)
        }
}
