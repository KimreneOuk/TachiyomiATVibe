package eu.kanade.translation.artifact

import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.security.MessageDigest

/** A recorded legacy name that may be removed only after content identity proof. */
data class ChapterLegacyDeletionCandidate(
    val name: String,
    val identity: LegacySourceIdentity,
)

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
    val authority: ManifestAuthority?,
    private val legacyCandidates: List<ChapterLegacyDeletionCandidate>,
    private val manifestSiblingNames: List<String>,
) {
    val isArtifactAuthoritative: Boolean get() = authority == ManifestAuthority.ARTIFACTS

    /**
     * Removes the authority document before the owned tree. If any authority copy
     * cannot be removed, the tree is retained to avoid partial data loss and the
     * caller receives a retryable failure. Once all authority copies are gone,
     * leftover payloads are orphaned rather than readable if tree deletion fails.
     */
    fun delete(): ChapterArtifactDeletionResult {
        val failures = mutableListOf<String>()
        val deletedLegacyNames = mutableListOf<String>()
        val retainedLegacyNames = mutableListOf<String>()
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
                deletedLegacyNames = deletedLegacyNames,
                retainedLegacyNames = retainedLegacyNames,
                failures = failures,
            )
        }

        val artifactTreeRemoved = deleteIfPresent(layout.artifactRootDirectoryName, failures)
        legacyCandidates.forEach { candidate ->
            val actual = identityOf(candidate.name)
            when {
                actual == null -> Unit
                !identitiesMatch(candidate.identity, actual) -> retainedLegacyNames += candidate.name
                deleteIfPresent(candidate.name, failures) -> deletedLegacyNames += candidate.name
            }
        }
        return ChapterArtifactDeletionResult(
            manifestRemoved = true,
            artifactTreeRemoved = artifactTreeRemoved,
            deletedLegacyNames = deletedLegacyNames,
            retainedLegacyNames = retainedLegacyNames,
            failures = failures,
        )
    }

    private fun deleteIfPresent(name: String, failures: MutableList<String>): Boolean {
        if (!io.exists(name)) return true
        if (io.delete(name) || !io.exists(name)) return true
        failures += name
        return false
    }

    private fun identityOf(name: String): LegacySourceIdentity? = runCatching {
        val bytes = io.read(name) ?: return null
        LegacySourceIdentity(
            sha256 = MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { byte -> "%02x".format(byte) },
            lengthBytes = bytes.size.toLong(),
            lastModifiedMs = io.lastModified(name),
        )
    }.getOrNull()

    private fun identitiesMatch(expected: LegacySourceIdentity, actual: LegacySourceIdentity): Boolean =
        expected.sha256.equals(actual.sha256, ignoreCase = true) &&
            expected.lengthBytes == actual.lengthBytes

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
                authority = manifest?.authority,
                legacyCandidates = candidatesFor(manifest),
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

        private fun candidatesFor(manifest: ChapterArtifactManifest?): List<ChapterLegacyDeletionCandidate> {
            if (manifest?.authority != ManifestAuthority.ARTIFACTS) return emptyList()
            val metadata = manifest.legacyMigration?.takeIf { it.isSupported } ?: return emptyList()
            val candidates = mutableListOf<ChapterLegacyDeletionCandidate>()
            metadata.sourceIdentity?.let { identity ->
                when (metadata.sourcePreservation) {
                    LegacyPreservationState.INTENT ->
                        addCandidate(candidates, metadata.sourceFileName, identity)
                    LegacyPreservationState.PRESERVED ->
                        addCandidate(candidates, metadata.resolvedSourceFileName, identity)
                    LegacyPreservationState.NONE,
                    LegacyPreservationState.DELETED,
                    -> Unit
                }
            }
            metadata.glossaryIdentity?.let { identity ->
                when (metadata.glossaryPreservation) {
                    LegacyPreservationState.INTENT ->
                        addCandidate(
                            candidates,
                            metadata.sourceFileName.substringBeforeLast('.') + ".glossary.json",
                            identity,
                        )
                    LegacyPreservationState.PRESERVED ->
                        addCandidate(candidates, metadata.resolvedGlossaryFileName, identity)
                    LegacyPreservationState.NONE,
                    LegacyPreservationState.DELETED,
                    -> Unit
                }
            }
            return candidates
        }

        private fun addCandidate(
            candidates: MutableList<ChapterLegacyDeletionCandidate>,
            name: String?,
            identity: LegacySourceIdentity,
        ) {
            if (name.isNullOrBlank() || !isSafeDocumentName(name)) return
            if (candidates.none { it.name == name }) {
                candidates += ChapterLegacyDeletionCandidate(name, identity)
            }
        }

        private fun isSafeDocumentName(name: String): Boolean =
            name.isNotBlank() &&
                !name.contains('/') &&
                !name.contains('\\') &&
                name.none { it.code < 0x20 || it.code == 0x7f }
    }
}
