package eu.kanade.translation.artifact

import kotlinx.serialization.json.decodeFromStream
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * TachiyomiAT: owns the chapter artifact manifest and the immutable artifact
 * tree for one chapter (lifecycle contract §15).
 *
 * Phase 2 scope: deterministic migration/resync of the legacy flat translation
 * record into the manifest, crash-safe manifest/glossary publication, and
 * bounded retention reconciliation. Stage sidecar writers and promotion
 * transactions arrive with the store-transaction phase; until then the legacy
 * flat file remains the live reader's authority and is never modified here.
 *
 * Because the legacy file stays authoritative, every load compares the stored
 * [LegacySourceIdentity] and conservatively resyncs the manifest when the
 * authoritative bytes changed; a failed resync keeps the prior manifest.
 */
class ChapterArtifactStore(
    private val documents: AtomicChapterDocuments,
    private val layout: ChapterArtifactLayout,
) {
    private val io: ChapterDocumentIo get() = documents.rawIo()

    data class LoadResult(
        val manifest: ChapterArtifactManifest,
        /** True when this load performed the initial legacy migration and published it. */
        val migratedFromLegacy: Boolean,
        /** True when this load detected a changed legacy identity and republished a resync. */
        val resyncedFromLegacy: Boolean,
    )

    /** Outcome of a durable-failure record attempt; only [Stored] is durable. */
    sealed interface RecordOutcome {
        data class Stored(val manifest: ChapterArtifactManifest) : RecordOutcome
        data class NotStored(val reason: String) : RecordOutcome
    }

    /**
     * Loads the manifest, resyncing from [legacy] when the authoritative
     * legacy identity changed. Future-schema documents (primary or backup) are
     * returned read-only and never renamed, deleted, quarantined, or
     * overwritten by this version.
     */
    fun loadOrMigrate(legacy: LegacyChapterSnapshot): LoadResult {
        val primary = readManifestDocument(layout.manifestFileName)
        val backup = readManifestDocument(backupName())

        if (primary?.schemaVersion != null && primary.schemaVersion > ChapterArtifactManifest.SCHEMA_VERSION) {
            return refuseFutureDocument("primary", primary)
        }
        if (backup?.schemaVersion != null && backup.schemaVersion > ChapterArtifactManifest.SCHEMA_VERSION) {
            // Never touch the future backup. A usable v1 primary stays in
            // charge; otherwise surface the future document read-only so the
            // caller knows a newer schema owns this chapter.
            logcat(LogPriority.WARN) {
                "TachiyomiAT artifact manifest backup has unsupported schema; preserved untouched: " +
                    "chapter=${layout.chapterKey} schema=${backup.schemaVersion}"
            }
            return if (primary != null) {
                LoadResult(primary, migratedFromLegacy = false, resyncedFromLegacy = false)
            } else {
                LoadResult(backup, migratedFromLegacy = false, resyncedFromLegacy = false)
            }
        }

        val existing = primary
            ?: recoverPrimaryFromBackupOrNull(backup)
        if (existing != null) {
            if (existing.legacySource != null &&
                existing.legacySource == legacy.legacyIdentity &&
                legacyGlossaryMatches(existing, legacy)
            ) {
                // Authoritative bytes unchanged: fast path. The retained
                // backup is stale by definition once the primary validates.
                io.delete(backupName())
                return LoadResult(existing, migratedFromLegacy = false, resyncedFromLegacy = false)
            }
            val (merged, published) = resyncAndPublish(existing, legacy)
            return LoadResult(merged, migratedFromLegacy = false, resyncedFromLegacy = published)
        }

        val migrated = stampChapterKey(LegacyArtifactMigration.migrateChapter(legacy))
        val withGlossary = attachGlossaryIfNeeded(migrated, legacy, priorPointer = null)
        val published = publishManifestInternal(withGlossary)
        if (!published) {
            logcat(LogPriority.WARN) {
                "TachiyomiAT artifact manifest migration publish failed: chapter=${layout.chapterKey}"
            }
        }
        if (legacy.translationFileCorrupt) {
            logcat(LogPriority.WARN) {
                "TachiyomiAT legacy translation file corrupt; migrated empty manifest and kept legacy file: " +
                    "chapter=${layout.chapterKey}"
            }
        }
        return LoadResult(withGlossary, migratedFromLegacy = published, resyncedFromLegacy = false)
    }

    fun readManifest(): ChapterArtifactManifest? {
        val primary = readManifestDocument(layout.manifestFileName)
        if (primary != null) return primary
        val backup = readManifestDocument(backupName()) ?: return null
        if (backup.schemaVersion > ChapterArtifactManifest.SCHEMA_VERSION) return backup
        return recoverPrimaryFromBackupOrNull(backup)
    }

    /** Crash-safe manifest publication: temp, validate, rotate backup, rename. */
    fun publishManifest(manifest: ChapterArtifactManifest): Boolean =
        publishManifestInternal(stampChapterKey(manifest))

    fun publishGlossary(entries: Map<String, String>): GlossaryPointer? {
        val existing = readManifest()
        val lastVersion = maxOf(
            existing?.glossary?.version ?: 0,
            latestGlossarySidecarVersion(),
        )
        val version = lastVersion + 1
        val fingerprint = StageFingerprints.glossaryVersion(entries)
        val glossary = ChapterGlossary(
            version = version,
            versionFingerprint = fingerprint,
            entries = entries,
        )
        val name = layout.glossaryFile(version)
        if (!documents.publishJson(name, glossary)) return null
        return GlossaryPointer(fileName = name, version = version, versionFingerprint = fingerprint)
    }

    fun readGlossary(pointer: GlossaryPointer): ChapterGlossary? =
        documents.readValidated<ChapterGlossary>(pointer.fileName) { glossary ->
            glossary.schemaVersion == ChapterGlossary.SCHEMA_VERSION &&
                glossary.kind == ChapterGlossary.KIND_VOCABULARY_HINTS
        }

    /**
     * Records durable terminal failure/retry metadata (lifecycle contract
     * §13) and persists it crash-safely. [RecordOutcome.NotStored] means the
     * write failed and the prior manifest stays authoritative — the caller
     * must not treat the failure metadata as durable.
     */
    fun recordDurableFailure(
        manifest: ChapterArtifactManifest,
        failure: DurableFailureMetadata,
    ): RecordOutcome {
        if (futureBackupPresent()) {
            return RecordOutcome.NotStored("future-schema backup present; refusing to publish")
        }
        val key = "${failure.pageKey}:${failure.stage.name}"
        val updated = manifest.copy(
            durableFailures = manifest.durableFailures + (key to failure),
            updatedAtEpochMs = System.currentTimeMillis(),
        )
        if (!publishManifestInternal(updated)) {
            return RecordOutcome.NotStored("manifest publication failed; prior manifest remains authoritative")
        }
        return RecordOutcome.Stored(updated)
    }

    data class RetentionResult(
        val deletedCount: Int,
        val deletedNames: List<String>,
    )

    /**
     * Bounded retention (lifecycle contract §15): preserves exactly the files
     * reachable from the manifest/generation graph — committed, candidate, and
     * one-previous bundles, their generation records, referenced stage
     * sidecars, and the pointed glossary version — and deletes every other
     * file under the managed artifact tree using store reachability, never
     * filename age alone. All comparisons use canonical root-relative paths;
     * only contained managed paths are ever deleted. Legacy documents outside
     * the artifact tree (flat JSON, legacy glossary, summary, companion
     * images) are never touched.
     */
    fun reconcileRetention(manifest: ChapterArtifactManifest): RetentionResult {
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
                bundle.displayBase.fileName?.takeIf { !bundle.displayBase.legacyLayout }?.let(::add)
            }
            page.candidate?.let { add(layout.generationFile(it.generationId)) }
            page.contextCheckpointFileName?.let(::add)
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
    }

    private fun readManifestDocument(name: String): ChapterArtifactManifest? {
        val bytes = io.read(name) ?: return null
        return parseManifest(bytes)
    }

    private fun parseManifest(bytes: ByteArray): ChapterArtifactManifest? = runCatching {
        documents.json.decodeFromStream<ChapterArtifactManifest>(bytes.inputStream())
    }.getOrNull()

    private fun recoverPrimaryFromBackupOrNull(backup: ChapterArtifactManifest?): ChapterArtifactManifest? {
        if (backup == null) return null
        documents.recoverPrimaryFromBackup(layout.manifestFileName, backupName())
        return readManifestDocument(layout.manifestFileName) ?: backup
    }

    private fun resyncAndPublish(
        prior: ChapterArtifactManifest,
        legacy: LegacyChapterSnapshot,
    ): Pair<ChapterArtifactManifest, Boolean> {
        val fresh = stampChapterKey(LegacyArtifactMigration.migrateChapter(legacy))
        val merged = LegacyArtifactMigration.resyncManifest(prior, fresh)
        val withGlossary = attachGlossaryIfNeeded(merged, legacy, priorPointer = prior.glossary)
        if (!publishManifestInternal(withGlossary)) {
            logcat(LogPriority.WARN) {
                "TachiyomiAT artifact manifest resync publish failed; prior manifest retained: " +
                    "chapter=${layout.chapterKey}"
            }
            return prior to false
        }
        return withGlossary to true
    }

    private fun attachGlossaryIfNeeded(
        manifest: ChapterArtifactManifest,
        legacy: LegacyChapterSnapshot,
        priorPointer: GlossaryPointer?,
    ): ChapterArtifactManifest {
        if (legacy.glossary.isEmpty()) return manifest.copy(glossary = priorPointer)
        val fingerprint = StageFingerprints.glossaryVersion(legacy.glossary)
        if (priorPointer?.versionFingerprint == fingerprint) return manifest.copy(glossary = priorPointer)
        val pointer = publishGlossary(legacy.glossary) ?: return manifest.copy(glossary = priorPointer)
        return manifest.copy(glossary = pointer)
    }

    private fun legacyGlossaryMatches(
        existing: ChapterArtifactManifest,
        legacy: LegacyChapterSnapshot,
    ): Boolean {
        if (legacy.glossary.isEmpty()) return true
        return existing.glossary?.versionFingerprint == StageFingerprints.glossaryVersion(legacy.glossary)
    }

    private fun futureBackupPresent(): Boolean =
        readManifestDocument(backupName())?.schemaVersion?.let { it > ChapterArtifactManifest.SCHEMA_VERSION }
            ?: false

    private fun refuseFutureDocument(which: String, document: ChapterArtifactManifest): LoadResult {
        logcat(LogPriority.WARN) {
            "TachiyomiAT artifact manifest schema unsupported; left untouched: " +
                "chapter=${layout.chapterKey} $which schema=${document.schemaVersion}"
        }
        return LoadResult(document, migratedFromLegacy = false, resyncedFromLegacy = false)
    }

    private fun publishManifestInternal(manifest: ChapterArtifactManifest): Boolean {
        if (futureBackupPresent()) {
            logcat(LogPriority.WARN) {
                "TachiyomiAT manifest publication skipped: reason=future-schema backup preserved " +
                    "chapter=${layout.chapterKey}"
            }
            return false
        }
        return documents.publishJson(layout.manifestFileName, manifest)
    }

    private fun stampChapterKey(manifest: ChapterArtifactManifest): ChapterArtifactManifest =
        if (manifest.chapterKey == layout.chapterKey) {
            manifest
        } else {
            manifest.copy(chapterKey = layout.chapterKey)
        }

    private fun latestGlossarySidecarVersion(): Int =
        io.list(layout.glossaryDirectory).orEmpty()
            .mapNotNull { name ->
                Regex("""chapter\.glossary\.(\d+)\.json$""").find(name)?.groupValues?.get(1)?.toIntOrNull()
            }
            .maxOrNull() ?: 0

    private fun backupName(): String = AtomicChapterDocuments.backupNameFor(layout.manifestFileName)
}
