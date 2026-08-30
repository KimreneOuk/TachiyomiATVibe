package eu.kanade.translation.artifact

import com.hippo.unifile.UniFile
import eu.kanade.translation.artifact.LegacyFlatFileDecoder
import kotlinx.serialization.json.decodeFromStream

internal data class ArtifactManifestProbe(
    val exists: Boolean,
    val manifest: ChapterArtifactManifest?,
)

/**
 * Reads only the chapter artifact manifest header (T909 Phase 3b), moved
 * verbatim from the `ChapterTranslationStore` companion. The
 * `ChapterTranslationStore.probeArtifactManifest` seams stay in place for
 * callers and tests.
 */
internal object ChapterArtifactManifestReader {

    /**
     * The flat page map predates the artifact schema and may contain fields
     * removed by a later refactor. Unknown keys are additive compatibility
     * data here, so they must not make an otherwise valid page unreadable.
     */
    private val legacyPageJson = LegacyFlatFileDecoder.legacyPageJson

    /** Reads only the small manifest header; page snapshots stay unopened. */
    internal fun probeArtifactManifest(translationFile: UniFile): ArtifactManifestProbe {
        val parent = translationFile.parentFile ?: return ArtifactManifestProbe(exists = false, manifest = null)
        val fileName = translationFile.name ?: return ArtifactManifestProbe(exists = false, manifest = null)
        return probeArtifactManifest(parent, fileName)
    }

    /** Reads only the manifest header when the legacy flat document is absent. */
    internal fun probeArtifactManifest(parent: UniFile, fileName: String): ArtifactManifestProbe {
        val layout = ChapterArtifactLayout.fromTranslationFileName(fileName)
        val manifestFile = parent.findFile(layout.manifestFileName)
            ?: return ArtifactManifestProbe(exists = false, manifest = null)
        if (!manifestFile.exists()) return ArtifactManifestProbe(exists = false, manifest = null)
        val manifest = runCatching {
            manifestFile.openInputStream().use { input ->
                legacyPageJson.decodeFromStream<ChapterArtifactManifest>(input)
            }
        }.getOrNull()
        return ArtifactManifestProbe(exists = true, manifest = manifest)
    }
}
