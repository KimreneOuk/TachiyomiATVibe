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

    internal fun reconcileRetention(
        manifest: ChapterArtifactManifest,
        stagedReachable: Set<String> = emptySet(),
    ): RetentionResult = deleteVerifiedOrphans(
        collectOrphanCandidates(manifest, stagedReachable),
        manifest,
        stagedReachable,
    )

    /**
     * Phase 1 of the split retention sweep: enumerate deletion CANDIDATES
     * without mutating anything. Pure over its inputs — it takes NO store
     * monitor — so the minutes-long SAF crawl can run OFF the store mutex.
     * (JDB-proven 2026-09-15: the crawl held the store mutex for 20+ minutes
     * on a 70-page chapter and froze every page lease in the pipeline.) The
     * parent listing already proves a child exists, so no per-file `io.exists`
     * probe is paid during the crawl; deletions re-verify in phase 2.
     */
    internal fun collectOrphanCandidates(
        manifest: ChapterArtifactManifest,
        stagedReachable: Set<String> = emptySet(),
    ): Set<String> {
        val reachable = reachablePaths(manifest)
        val retainedImageGenerations = retainedImageGenerations(manifest)
        val candidates = mutableSetOf<String>()

        // Orphan temp sibling of the manifest itself (outside the managed tree).
        val manifestTemp = AtomicChapterDocuments.tempNameFor(layout.manifestFileName)
        if (manifestTemp !in stagedReachable) candidates += manifestTemp

        layout.managedDirectories.forEach { root ->
            collectDirectory(root, reachable, retainedImageGenerations, stagedReachable, candidates)
        }
        return candidates
    }

    /**
     * Recurses [directory]; returns true when every entry under it is itself a
     * candidate (a fully reclaimable directory, matching the old
     * became-empty rule). Managed roots are never candidates themselves.
     */
    private fun collectDirectory(
        directory: String,
        reachable: Set<String>,
        retainedImageGenerations: Set<String>,
        stagedReachable: Set<String>,
        candidates: MutableSet<String>,
    ): Boolean {
        val children = io.list(directory) ?: return false
        var allReclaimable = true
        children.forEach { child ->
            val path = "$directory/$child"
            val reclaimable = if (io.list(path) != null) {
                // Directory: reclaimable only when everything under it is.
                if (collectDirectory(path, reachable, retainedImageGenerations, stagedReachable, candidates) &&
                    path !in layout.managedDirectories
                ) {
                    candidates += path
                    true
                } else {
                    false
                }
            } else {
                // File — existence is proven by the parent listing.
                val orphan = path !in stagedReachable &&
                    !isRetained(path, reachable, retainedImageGenerations) &&
                    layout.isManagedPath(path)
                if (orphan) candidates += path
                orphan
            }
            if (!reclaimable) allReclaimable = false
        }
        return allReclaimable
    }

    /**
     * Phase 2: delete the collected candidates after re-verifying each against
     * [manifest] — the LIVE manifest when called under the store mutex — so a
     * file a concurrent publish made reachable mid-crawl is spared (the sweep
     * ran off-lock by design). Deleting the candidates takes bounded IO: only
     * genuinely orphaned files pay a binder round-trip here.
     */
    internal fun deleteVerifiedOrphans(
        candidateOrphans: Collection<String>,
        manifest: ChapterArtifactManifest,
        stagedReachable: Set<String> = emptySet(),
    ): RetentionResult {
        val deleted = mutableListOf<String>()
        val reachable = reachablePaths(manifest)
        val retainedImageGenerations = retainedImageGenerations(manifest)
        val manifestTemp = AtomicChapterDocuments.tempNameFor(layout.manifestFileName)
        if (
            manifestTemp in candidateOrphans &&
            manifestTemp !in stagedReachable &&
            io.exists(manifestTemp) &&
            io.delete(manifestTemp)
        ) {
            deleted += manifestTemp
        }
        // Children sort before their parent directories so a reclaimable
        // directory is emptied before it is removed.
        candidateOrphans.asSequence()
            .filter { it != manifestTemp }
            .sortedByDescending { it.length }
            .forEach { path ->
                if (path in stagedReachable) return@forEach
                // Re-verification against the live reachability graph.
                if (isRetained(path, reachable, retainedImageGenerations)) return@forEach
                if (!layout.isManagedPath(path)) return@forEach
                if (pathIsDirectory(path)) {
                    // Recursive deletes must not consume a child a concurrent
                    // publish made reachable: only remove a verified-empty dir.
                    if (io.list(path).isNullOrEmpty() && io.delete(path)) deleted += path
                } else if (io.exists(path) && io.delete(path)) {
                    deleted += path
                }
            }
        return RetentionResult(deleted.size, deleted)
    }

    private fun pathIsDirectory(path: String): Boolean = io.list(path) != null

    /**
     * T930 Slice A4 (Amendment D): event-driven known-orphan deletion.
     * Deletes explicitly known orphans (e.g. unlinked generation records or candidate
     * snapshots) without executing a full reachability crawl over the filesystem.
     * Race register #6: orphan must be unreachable from BOTH durable and staged state.
     *
     * When [durableManifest] is supplied (hot-path callers MUST supply it), every
     * candidate is additionally filtered through the full sweep's reachability
     * predicate, so the manifest-unreachability half of race register #6 is
     * enforced here instead of relying on caller discipline.
     */
    internal fun deleteKnownOrphans(
        candidateOrphans: Collection<String>,
        stagedReachable: Set<String> = emptySet(),
        durableManifest: ChapterArtifactManifest? = null,
    ): RetentionResult {
        val deleted = mutableListOf<String>()
        val reachable = durableManifest?.let { reachablePaths(it) } ?: emptySet()
        val retainedGenerations = durableManifest?.let { retainedImageGenerations(it) } ?: emptySet()
        val manifestTemp = AtomicChapterDocuments.tempNameFor(layout.manifestFileName)
        if (manifestTemp !in stagedReachable && io.exists(manifestTemp) && io.delete(manifestTemp)) {
            deleted += manifestTemp
        }
        for (path in candidateOrphans) {
            if (path in stagedReachable) continue
            if (durableManifest != null && isRetained(path, reachable, retainedGenerations)) continue
            if (layout.isManagedPath(path) && io.exists(path) && io.delete(path)) {
                deleted += path
            }
        }
        return RetentionResult(deleted.size, deleted)
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
                bundle.displayBase.fileName?.let(::add)
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
        manifest.context?.fileName?.let(::add)
    }
}

data class RetentionResult(
    val deletedCount: Int,
    val deletedNames: List<String>,
)
