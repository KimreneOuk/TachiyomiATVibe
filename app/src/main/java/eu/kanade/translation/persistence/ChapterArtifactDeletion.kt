package eu.kanade.translation.persistence

import eu.kanade.translation.model.PageTranslationView
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat




/** Result of a chapter artifact deletion attempt. */
data class ChapterArtifactDeletionResult(
    val manifestRemoved: Boolean,
    val artifactTreeRemoved: Boolean,
    val failures: List<String>,
    /**
     * P3B: true when the destructive empty-manifest checkpoint could not be
     * published with a VERIFIED same-state backup before the authority
     * removal. Typed incomplete, never a rollback: the file removal still
     * happened (or failed for its own recorded reasons), but a crash mid-
     * deletion could restore the pre-deletion generation from the retained
     * backup until a retry completes the removal.
     */
    val destructiveCheckpointUnverified: Boolean = false,
) {
    val complete: Boolean get() = failures.isEmpty()
}

/**
 * Captures the exact artifact-owned paths before chapter teardown.
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
     *
     * P3B: the removal is preceded by ONE checked destructive publication of
     * an EMPTY current-format manifest through
     * [ChapterArtifactEngine.publishDestructiveManifest] — the same
     * verified-same-state-backup requirement every destructive operation
     * satisfies. A crash between that checkpoint and the file removals can
     * then only restore an EMPTY authority, never the deleted chapter's
     * generation state; re-running the idempotent plan finishes the removal.
     * A checkpoint refusal (future-schema primary/backup preserved, or the
     * publication itself failing) still attempts the plain removal and
     * reports the typed incompleteness.
     */
    fun delete(): ChapterArtifactDeletionResult {
        val failures = mutableListOf<String>()
        var checkpointUnverified = false
        val documents = AtomicChapterDocuments(io)
        val engine = ChapterArtifactEngine(documents, layout)
        when (val receipt = engine.publishDestructiveManifest(
            ChapterArtifactManifest(chapterKey = layout.chapterKey),
        )) {
            is ChapterArtifactEngine.DestructiveManifestReceipt.Complete -> Unit
            is ChapterArtifactEngine.DestructiveManifestReceipt.PrimaryAdvancedBackupIncomplete -> {
                checkpointUnverified = true
                logcat(LogPriority.WARN) {
                    "TachiyomiAT chapter artifact deletion checkpoint unverified: " +
                        "chapter=${layout.chapterKey} reason=${receipt.reason}"
                }
            }
            is ChapterArtifactEngine.DestructiveManifestReceipt.NotPublished -> logcat(LogPriority.WARN) {
                "TachiyomiAT chapter artifact deletion checkpoint not published: " +
                    "chapter=${layout.chapterKey} reason=${receipt.reason}"
            }
        }
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
                failures = failures,
                destructiveCheckpointUnverified = checkpointUnverified,
            )
        }

        val artifactTreeRemoved = deleteIfPresent(layout.artifactRootDirectoryName, failures)
        return ChapterArtifactDeletionResult(
            manifestRemoved = true,
            artifactTreeRemoved = artifactTreeRemoved,
            failures = failures,
            destructiveCheckpointUnverified = checkpointUnverified,
        )
    }

    private fun deleteIfPresent(name: String, failures: MutableList<String>): Boolean {
        if (!io.exists(name)) return true
        if (io.delete(name) || !io.exists(name)) return true
        failures += name
        return false
    }

    companion object {
        /** Captures a deletion plan without opening a translation store. */
        fun capture(io: ChapterDocumentIo, artifactFileName: String): ChapterArtifactDeletionPlan? {
            val layout = ChapterArtifactLayout.fromArtifactFileName(artifactFileName)
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


/**
 * Durable translation data that a chapter reset would affect. Counts are derived
 * from the store snapshot so callers do not infer artifacts from UI state.
 */
data class ChapterResetPreflight(
    val ocrPages: Int,
    val inpaintPages: Int,
    val translatedBlocks: Int,
    val manualEditBlocks: Int,
) {
    val hasTranslationData: Boolean
        get() = ocrPages > 0 || inpaintPages > 0 || translatedBlocks > 0
}

fun chapterResetPreflight(pages: Collection<PageTranslationView>): ChapterResetPreflight =
    ChapterResetPreflight(
        ocrPages = pages.count { it.blocks.isNotEmpty() || it.inpaintMaskBoxes.isNotEmpty() },
        inpaintPages = pages.count { it.cleanedImageName != null },
        translatedBlocks = pages.sumOf { page -> page.blocks.count { it.translation.isNotBlank() } },
        manualEditBlocks = pages.sumOf { page -> page.blocks.count { it.userEditedAt != null } },
    )

/**
 * P3B typed reset receipt. A reset is COMPLETE only when every destructive
 * publication behind it durably committed with verified same-state backups.
 * Any unverified backup, rejected publication, or failed authority removal
 * surfaces as typed incomplete — the caller must not present the chapter as
 * cleanly reset (and must not adopt orphan bytes) when [failures] is
 * non-empty.
 */
data class ChapterResetReceipt(
    val failures: List<String>,
) {
    val completed: Boolean get() = failures.isEmpty()

    companion object {
        val COMPLETE = ChapterResetReceipt(failures = emptyList())

        fun incomplete(failures: List<String>): ChapterResetReceipt =
            ChapterResetReceipt(failures = failures.filter { it.isNotBlank() }.distinct())
    }
}
