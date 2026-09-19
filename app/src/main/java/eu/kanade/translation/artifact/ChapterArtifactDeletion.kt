package eu.kanade.translation.artifact

import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
/** Result of a chapter artifact deletion attempt. */
data class ChapterArtifactDeletionResult(
    val manifestRemoved: Boolean,
    val artifactTreeRemoved: Boolean,
    val deletedLegacyNames: List<String>,
    val retainedLegacyNames: List<String>,
    val failures: List<String>,
) {
    val complete: Boolean get() = failures.isEmpty()
}

/**
 * Captures the exact owned paths and migration identities before chapter teardown.
 * The plan is deliberately read-only until [delete] is called after all workers have
 * been cancelled and joined.
 */
class ChapterArtifactDeletionPlan private constructor(
    private val io: ChapterDocumentIo,
    val layout: ChapterArtifactLayout,
    private val manifestSiblingNames: List<String>,
) {
    /**
     * Removes the authority document before the owned tree. If any authority copy
     * cannot be removed, the tree is retained to avoid partial data loss and the
     * caller receives a retryable failure. Once all authority copies are gone,
     * leftover payloads are orphaned rather than readable if tree deletion fails.
     */
    fun delete(): ChapterArtifactDeletionResult {
        val failures = mutableListOf<String>()
        var manifestRemoved = true

        manifestSiblingNames.forEach { name ->
            if (!deleteIfPresent(name, failures)) manifestRemoved = false
        }
        if (!manifestRemoved) {
            logcat(LogPriority.ERROR) {
                "TachiyomiAT chapter artifact deletion retained owned tree: " +
                    "manifest removal failed chapter=${layout.chapterKey}"
            }
            return ChapterArtifactDeletionResult(
                manifestRemoved = false,
                artifactTreeRemoved = false,
                deletedLegacyNames = emptyList(),
                retainedLegacyNames = emptyList(),
                failures = failures,
            )
        }

        val artifactTreeRemoved = deleteIfPresent(layout.artifactRootDirectoryName, failures)
        return ChapterArtifactDeletionResult(
            manifestRemoved = true,
            artifactTreeRemoved = artifactTreeRemoved,
            deletedLegacyNames = emptyList(),
            retainedLegacyNames = emptyList(),
            failures = failures,
        )
    }

    private fun deleteIfPresent(name: String, failures: MutableList<String>): Boolean {
        if (!io.exists(name)) return true
        if (io.delete(name) || !io.exists(name)) return true
        failures += name
        return false
    }

    companion object {
        /** Captures a deletion plan without opening or migrating a translation store. */
        fun capture(io: ChapterDocumentIo, translationFileName: String): ChapterArtifactDeletionPlan? {
            val layout = ChapterArtifactLayout.fromTranslationFileName(translationFileName)
            val documents = AtomicChapterDocuments(io)
            val manifest = runCatching {
                documents.readValidated<ChapterArtifactManifest>(layout.manifestFileName)
            }.getOrNull()
            // Never destroy a document owned by a newer schema version.
            if (manifest?.schemaVersion?.let { it > ChapterArtifactManifest.SCHEMA_VERSION } == true) {
                return null
            }
            val siblings = ownedManifestSiblingNames(io, layout.manifestFileName)
            if (manifest == null &&
                !io.exists(layout.artifactRootDirectoryName) &&
                siblings.none { io.exists(it) }
            ) {
                return null
            }
            return ChapterArtifactDeletionPlan(
                io = io,
                layout = layout,
                manifestSiblingNames = siblings,
            )
        }

        private fun ownedManifestSiblingNames(io: ChapterDocumentIo, manifestName: String): List<String> {
            val corruptBase = AtomicChapterDocuments.corruptNameFor(manifestName)
            return buildList {
                add(manifestName)
                add(AtomicChapterDocuments.tempNameFor(manifestName))
                add(AtomicChapterDocuments.backupNameFor(manifestName))
                add(corruptBase)
                io.list("")
                    .orEmpty()
                    .filter { it.startsWith("$corruptBase.") }
                    .forEach(::add)
            }.distinct()
        }

    }
}
