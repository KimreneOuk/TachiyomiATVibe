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
        val manifestFile = parent.findFile(layout.manifestFileName)
            ?: return ArtifactManifestProbe(exists = false, manifest = null)
        if (!manifestFile.exists()) return ArtifactManifestProbe(exists = false, manifest = null)
        val manifest = runCatching {
            manifestFile.openInputStream().use { input ->
                ArtifactDocumentJson.decodeFromStream<ChapterArtifactManifest>(input)
            }
        }.getOrNull()
        return ArtifactManifestProbe(exists = true, manifest = manifest)
    }
}
