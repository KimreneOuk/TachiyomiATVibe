package eu.kanade.translation.artifact

/**
 * TachiyomiAT: bounded retention sweep moved verbatim from
 * `ChapterArtifactStore` (T909 Phase 2b). Stateless over the chapter's
 * document IO and artifact layout; locking stays at the
 * [ChapterArtifactStore.reconcileRetention] entry point.
 */
internal class ArtifactRetention(
    private val io: ChapterDocumentIo,
    private val layout: ChapterArtifactLayout,
) {

    internal fun reconcileRetention(manifest: ChapterArtifactManifest): RetentionResult {
        val reachable = reachablePaths(manifest)
        val retainedImageGenerations = retainedImageGenerations(manifest)
        val deleted = mutableListOf<String>()

        // Orphan temp sibling of the manifest itself (outside the managed tree).
        val manifestTemp = AtomicChapterDocuments.tempNameFor(layout.manifestFileName)
        if (io.exists(manifestTemp) && io.delete(manifestTemp)) deleted += manifestTemp

        layout.managedDirectories.forEach { root ->
            sweepDirectory(root, reachable, retainedImageGenerations, deleted)
        }
        return RetentionResult(deleted.size, deleted)
    }

    private fun sweepDirectory(
        directory: String,
        reachable: Set<String>,
        retainedImageGenerations: Set<String>,
        deleted: MutableList<String>,
    ) {
        val children = io.list(directory) ?: return
        children.forEach { child ->
            val path = "$directory/$child"
            when {
                io.list(path) != null -> {
                    sweepDirectory(path, reachable, retainedImageGenerations, deleted)
                    // Remove subdirectories that became empty, keeping the
                    // managed roots themselves.
                    if (io.list(path).isNullOrEmpty() && io.delete(path)) deleted += path
                }
                io.exists(path) && !isRetained(path, reachable, retainedImageGenerations) -> {
                    if (layout.isManagedPath(path) && io.delete(path)) deleted += path
                }
            }
        }
    }

    private fun isRetained(
        path: String,
        reachable: Set<String>,
        retainedImageGenerations: Set<String>,
    ): Boolean {
        if (path in reachable) return true
        if (path.endsWith(".tmp")) return false
        // Backups of reachable files survive one sweep.
        if (reachable.any { reachablePath -> path == "$reachablePath.bak" }) return true
        // T924-SC-17: a corrupt sidecar's quarantined `.corrupt` bytes are
        // reclaimed only when no manifest pointer references the sidecar.
        if (reachable.any { reachablePath -> path == "$reachablePath.corrupt" || path.startsWith("$reachablePath.corrupt.") }) {
            return true
        }
        if (!path.startsWith("${layout.imagesRootDirectory}/")) return false
        val fileName = path.removeSuffix(".bak").substringAfterLast('/')
        return retainedImageGenerations.any { generationSegment -> fileName.startsWith("$generationSegment-") }
    }

    /**
     * Image files under `images/<pageSegment>/` embed their generation id as
     * `<generationId>-<fingerprint>.<ext>`. A file is retained while its
     * generation belongs to the manifest/generation graph: a committed,
     * one-previous, or candidate bundle of any page, or any chapter-active
     * candidate generation. This bounds retention to exactly those
     * generations' files.
     */
    private fun retainedImageGenerations(manifest: ChapterArtifactManifest): Set<String> = buildSet {
        manifest.pages.values.forEach { page ->
            page.committed?.let { add(layout.generationSegment(it.generationId)) }
            page.previousCommitted?.let { add(layout.generationSegment(it.generationId)) }
            page.candidate?.let { add(layout.generationSegment(it.generationId)) }
        }
        manifest.activeCandidateGenerationIds.forEach { add(layout.generationSegment(it)) }
    }

    private fun reachablePaths(manifest: ChapterArtifactManifest): Set<String> = buildSet {
        manifest.pages.values.forEach { page ->
            listOfNotNull(page.committed, page.previousCommitted).forEach { bundle ->
                add(layout.generationFile(bundle.generationId))
                bundle.pageSnapshotFileName?.let(::add)
                bundle.displayBase.fileName?.takeIf { !bundle.displayBase.legacyLayout }?.let(::add)
            }
            page.candidate?.let {
                add(layout.generationFile(it.generationId))
                it.pageSnapshotFileName?.let(::add)
            }
            listOf(
                page.detection,
                page.ocr,
                page.inpaint,
                page.translation,
                page.layout,
            ).forEach { stage ->
                stage?.artifactFileName?.let(::add)
            }
        }
        manifest.activeCandidateGenerationIds.forEach { generationId ->
            add(layout.generationFile(generationId))
        }
        manifest.glossary?.fileName?.let(::add)
        // T917 Phase 3 (D9): the attempt-ledger sidecar is always reachable —
        // it is not manifest-pointed, so without this rule the retention sweep
        // would delete the crash-loop evidence it exists to preserve.
        add(layout.attemptLedgerFileName)
        // T924 Stage 1 (T924-SC-20): every new manifest pointer keeps its
        // sidecar reachable; sidecars no pointer references remain orphans
        // and are reclaimed here.
        manifest.activeRun?.fileName?.let(::add)
        manifest.ocrCheckpoints.values.forEach { pointer -> add(pointer.fileName) }
        manifest.analysisChunks.forEach { pointer -> add(pointer.fileName) }
        manifest.profile?.fileName?.let(::add)
        manifest.envelopePlan?.fileName?.let(::add)
        manifest.layoutPlans.values.forEach { pointer -> add(pointer.fileName) }
        manifest.colorPreparations.values.forEach { pointer -> add(pointer.fileName) }
    }
}

data class RetentionResult(
    val deletedCount: Int,
    val deletedNames: List<String>,
)
