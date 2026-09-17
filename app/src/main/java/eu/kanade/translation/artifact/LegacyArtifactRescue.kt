package eu.kanade.translation.artifact

import eu.kanade.translation.artifact.ChapterArtifactStore.LoadResult
import eu.kanade.translation.artifact.ChapterArtifactStore.TransactionOutcome
import eu.kanade.translation.model.PageDisplayState
import eu.kanade.translation.model.PageTranslation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.security.MessageDigest

private const val MAX_PRESERVATION_RENAME_ATTEMPTS = 8

/**
 * TachiyomiAT: the legacy flat-file rescue/preservation/health machine moved
 * verbatim from `ChapterArtifactStore` (T909 Phase 2). It owns the one-way
 * legacy-to-artifact rescue, the INTENT preservation retry, and the
 * later-version cleanup gate. Store-side helpers it leans on are delegated
 * through same-name private stubs; locking stays at the
 * [ChapterArtifactStore] `@Synchronized` entry points.
 */
internal class LegacyArtifactRescue(
    private val io: ChapterDocumentIo,
    private val layout: ChapterArtifactLayout,
    private val store: ChapterArtifactStore,
) {

    /**
     * Performs the one-way legacy rescue transaction. The LEGACY manifest is
     * only a durable staging record while immutable page/glossary documents
     * are published and re-read. The final manifest publication is the sole
     * authority switch; source preservation is deliberately best-effort and
     * retryable after that switch.
     */
    internal fun rescueLegacy(
        prior: ChapterArtifactManifest,
        legacy: LegacyChapterSnapshot,
    ): LoadResult {
        if (legacy.translationFileCorrupt || legacy.glossaryFileCorrupt) {
            return LoadResult(prior, migratedFromLegacy = false, resyncedFromLegacy = false)
        }
        val sourceIdentity = legacy.legacyIdentity
            ?: return LoadResult(prior, migratedFromLegacy = false, resyncedFromLegacy = false)
        val sourceName = legacy.sourceFileName?.takeIf { it.isNotBlank() }
            ?: return LoadResult(prior, migratedFromLegacy = false, resyncedFromLegacy = false)

        val mapped = stampChapterKey(LegacyArtifactMigration.migrateChapter(legacy))
        var staging = mapped.copy(
            glossary = null,
            authority = ManifestAuthority.LEGACY,
            cutoverAtEpochMs = null,
            migratedFromLegacyAtEpochMs = mapped.migratedFromLegacyAtEpochMs
                ?: legacy.migratedAtEpochMs.takeIf { it > 0L },
        )
        val glossaryPointer = attachGlossaryIfNeeded(staging, legacy, priorPointer = null)
        staging = glossaryPointer.copy(updatedAtEpochMs = System.currentTimeMillis())
        if (!publishManifestInternal(staging)) {
            logcat(LogPriority.WARN) {
                "TachiyomiAT legacy rescue staging publish failed; source retained: " +
                    "chapter=${layout.chapterKey}"
            }
            return LoadResult(prior, migratedFromLegacy = false, resyncedFromLegacy = false)
        }

        var materialized = staging
        legacy.pages.forEach { (pageKey, facts) ->
            when (val outcome = materializeLegacyCommittedSnapshot(materialized, pageKey, facts.page)) {
                is TransactionOutcome.Committed -> materialized = outcome.manifest
                is TransactionOutcome.Rejected -> {
                    logcat(LogPriority.WARN) {
                        "TachiyomiAT legacy rescue page publication failed; source retained: " +
                            "chapter=${layout.chapterKey} pageKey=$pageKey reason=${outcome.reason}"
                    }
                    return LoadResult(materialized, migratedFromLegacy = false, resyncedFromLegacy = false)
                }
            }
        }

        if (!artifactGraphIsComplete(materialized, legacy)) {
            logcat(LogPriority.WARN) {
                "TachiyomiAT legacy rescue graph validation failed; source retained: " +
                    "chapter=${layout.chapterKey}"
            }
            return LoadResult(materialized, migratedFromLegacy = false, resyncedFromLegacy = false)
        }

        val now = System.currentTimeMillis()
        val metadata = materialized.legacyMigration?.copy(
            sourceFileName = sourceName,
            sourcePreservation = LegacyPreservationState.INTENT,
            requestedSourceFileName = preservationTargetName(sourceName, sourceIdentity, 0),
            resolvedSourceFileName = null,
            sourcePreservedAtEpochMs = null,
            glossaryPreservation = if (legacy.glossaryIdentity != null) {
                LegacyPreservationState.INTENT
            } else {
                LegacyPreservationState.NONE
            },
            requestedGlossaryFileName = legacy.glossaryIdentity?.let { identity ->
                legacy.glossaryFileName?.let { name -> preservationTargetName(name, identity, 0) }
            },
            resolvedGlossaryFileName = null,
            health = LegacyMigrationHealth.INITIAL_CUTOVER,
            lastVerifiedByVersionCode = null,
            lastVerifiedAtEpochMs = null,
        )
        if (metadata == null) {
            logcat(LogPriority.WARN) {
                "TachiyomiAT legacy rescue metadata unavailable; source retained: chapter=${layout.chapterKey}"
            }
            return LoadResult(materialized, migratedFromLegacy = false, resyncedFromLegacy = false)
        }
        val cutover = materialized.copy(
            authority = ManifestAuthority.ARTIFACTS,
            cutoverAtEpochMs = materialized.cutoverAtEpochMs ?: now,
            migratedFromLegacyAtEpochMs = materialized.migratedFromLegacyAtEpochMs ?: now,
            legacyMigration = metadata,
            updatedAtEpochMs = now,
        )
        if (!publishManifestInternal(cutover)) {
            logcat(LogPriority.WARN) {
                "TachiyomiAT legacy rescue cutover publish failed; source retained: chapter=${layout.chapterKey}"
            }
            return LoadResult(materialized, migratedFromLegacy = false, resyncedFromLegacy = false)
        }
        val preserved = reconcileLegacyPreservation(cutover)
        if (preserved.pages.size <= 8) {
            reconcileRetention(preserved)
        } else {
            @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
            kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { reconcileRetention(preserved) }
            }
        }
        return LoadResult(preserved, migratedFromLegacy = true, resyncedFromLegacy = false)
    }

    internal fun reconcileLegacyPreservation(manifest: ChapterArtifactManifest): ChapterArtifactManifest {
        if (manifest.authority != ManifestAuthority.ARTIFACTS) return manifest
        val metadata = manifest.legacyMigration ?: return manifest
        if (!metadata.isSupported) return manifest
        var updated = manifest
        if (metadata.sourcePreservation == LegacyPreservationState.INTENT) {
            val result = preserveLegacyInput(
                sourceName = metadata.sourceFileName,
                requestedName = metadata.requestedSourceFileName,
                identity = metadata.sourceIdentity,
            )
            updated = updated.copy(
                legacyMigration = updated.legacyMigration?.copy(
                    sourcePreservation = result.state,
                    requestedSourceFileName = result.requestedName,
                    resolvedSourceFileName = result.resolvedName,
                    sourcePreservedAtEpochMs = result.preservedAtEpochMs,
                ),
            )
        }
        val currentMetadata = updated.legacyMigration ?: return updated
        if (currentMetadata.glossaryPreservation == LegacyPreservationState.INTENT) {
            val result = preserveLegacyInput(
                sourceName = currentMetadata.sourceFileName.substringBeforeLast('.') + ".glossary.json",
                requestedName = currentMetadata.requestedGlossaryFileName,
                identity = currentMetadata.glossaryIdentity,
            )
            updated = updated.copy(
                legacyMigration = updated.legacyMigration?.copy(
                    glossaryPreservation = result.state,
                    requestedGlossaryFileName = result.requestedName,
                    resolvedGlossaryFileName = result.resolvedName,
                ),
            )
        }
        if (updated != manifest && !publishManifestInternal(updated)) return manifest
        return updated
    }

    /**
     * Verifies one already-open chapter before making preserved legacy inputs
     * eligible for deletion. This is intentionally chapter-scoped and
     * synchronized with every manifest/source operation; it is never a
     * library-startup scan.
     */
    internal fun verifyLegacyArtifactHealth(
        manifest: ChapterArtifactManifest,
        currentVersionCode: Long,
        hasActiveLease: Boolean = false,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): LegacyArtifactHealthResult {
        fun rejected(reason: String): LegacyArtifactHealthResult =
            LegacyArtifactHealthResult(manifest, verified = false, reason = reason)

        if (manifest.authority != ManifestAuthority.ARTIFACTS) {
            return rejected("manifest is not artifact-authoritative")
        }
        val metadata = manifest.legacyMigration ?: return rejected("migration metadata missing")
        if (!metadata.isSupported) return rejected("migration metadata unsupported")
        if (currentVersionCode <= metadata.migratedByVersionCode) {
            return rejected("verification requires a later app version")
        }
        if (hasActiveLease) return rejected("chapter has an active writer lease")
        if (metadata.sourcePreservation == LegacyPreservationState.INTENT ||
            metadata.glossaryPreservation == LegacyPreservationState.INTENT
        ) {
            return rejected("legacy preservation is unresolved")
        }
        if (metadata.sourcePageCount != manifest.pages.size ||
            metadata.sourcePageKeyDigest != LegacyArtifactMigration.legacyPageKeyDigest(manifest.pages.keys)
        ) {
            return rejected("legacy page baseline does not match the artifact graph")
        }
        if (manifest.activeCandidateGenerationIds.isNotEmpty() ||
            manifest.pages.values.any { it.candidate != null }
        ) {
            return rejected("artifact candidate is active")
        }
        if (manifest.durableFailures.isNotEmpty()) return rejected("durable failures remain")
        if (manifest.glossary != null && readGlossary(manifest.glossary) == null) {
            return rejected("artifact glossary pointer is invalid")
        }
        val invalidPage = manifest.pages.values.firstOrNull { page ->
            page.displayState != PageDisplayState.DISPLAY_READY &&
                page.displayState != PageDisplayState.TEXTLESS_COMPLETE ||
                page.committed == null ||
                page.committed?.pageSnapshotFileName?.let { readPageSnapshot(it) } == null ||
                page.committed?.let { committed ->
                    committed.displayBase.kind == DisplayBaseKind.CLEANED_IMAGE &&
                        (
                            committed.displayBase.fileName == null ||
                                !displayBaseIsValid(
                                    if (committed.displayBase.legacyLayout) {
                                        layout.legacyCompanionImageFile(committed.displayBase.fileName)
                                    } else {
                                        committed.displayBase.fileName
                                    },
                                    page.source,
                                )
                            )
                } == true ||
                ArtifactStage.entries.any { stage ->
                    val record = page.stage(stage)
                    record?.status in setOf(
                        ArtifactStageStatus.RUNNING,
                        ArtifactStageStatus.FAILED_RETRYABLE,
                        ArtifactStageStatus.FAILED_TERMINAL,
                        ArtifactStageStatus.STALE,
                        ArtifactStageStatus.CORRUPT,
                        ArtifactStageStatus.PARTIAL,
                    ) ||
                        (record?.artifactFileName != null && !stagePayloadIsValid(record))
                }
        }
        if (invalidPage != null) return rejected("artifact page graph is incomplete or unhealthy")

        // Publish VERIFIED first. Physical cleanup is never allowed to make a
        // chapter appear health-verified if this durable marker did not land.
        val verifiedMetadata = metadata.copy(
            health = LegacyMigrationHealth.VERIFIED,
            lastVerifiedByVersionCode = currentVersionCode,
            lastVerifiedAtEpochMs = nowEpochMs,
        )
        // T934 LI-6: the health probe above validated the OPEN-TIME manifest,
        // but between store open and this background verify a concurrent
        // writer (the batch resume-hydration adoption path) can move the
        // durable manifest forward. Publishing the open-time snapshot here
        // silently REVERTED that concurrent write (lost update — the verified
        // stamp also invited later clobbers). Re-read the durable manifest
        // once and apply the VERIFIED stamp onto THAT fresh manifest instead;
        // if nothing durable is readable, fall back to the open-time manifest
        // exactly as before. Deliberately one-shot, best-effort: the verify is
        // retriable background work, not a transaction.
        val stampBase = store.readManifest() ?: manifest
        val verifiedManifest = stampBase.copy(
            legacyMigration = verifiedMetadata,
            updatedAtEpochMs = nowEpochMs,
        )
        if (!publishManifestInternal(verifiedManifest)) {
            return rejected("verification marker publication failed")
        }

        var cleanedMetadata = verifiedMetadata
        val deleted = mutableListOf<String>()
        if (verifiedMetadata.sourcePreservation == LegacyPreservationState.PRESERVED &&
            deletePreservedLegacyInput(
                verifiedMetadata.resolvedSourceFileName,
                verifiedMetadata.sourceIdentity,
                deleted,
            )
        ) {
            cleanedMetadata = cleanedMetadata.copy(sourcePreservation = LegacyPreservationState.DELETED)
        }
        if (verifiedMetadata.glossaryPreservation == LegacyPreservationState.PRESERVED &&
            deletePreservedLegacyInput(
                verifiedMetadata.resolvedGlossaryFileName,
                verifiedMetadata.glossaryIdentity,
                deleted,
            )
        ) {
            cleanedMetadata = cleanedMetadata.copy(glossaryPreservation = LegacyPreservationState.DELETED)
        }
        val cleanedManifest = verifiedManifest.copy(
            legacyMigration = cleanedMetadata,
            updatedAtEpochMs = nowEpochMs,
        )
        if (cleanedManifest != verifiedManifest && !publishManifestInternal(cleanedManifest)) {
            // The exact source was already deleted only after identity proof;
            // retaining VERIFIED/PRESERVED metadata is safe and makes the
            // physical operation retryable without claiming a false deletion.
            logcat(LogPriority.WARN) {
                "TachiyomiAT legacy cleanup marker publication failed; verified graph retained: " +
                    "chapter=${layout.chapterKey}"
            }
            return LegacyArtifactHealthResult(
                verifiedManifest,
                verified = true,
                reason = "cleanup marker publication failed",
                deletedLegacyNames = deleted,
            )
        }
        val resultManifest = if (cleanedManifest != verifiedManifest) cleanedManifest else verifiedManifest
        cleanupOpenedChapterJunk()
        reconcileRetention(resultManifest)
        return LegacyArtifactHealthResult(resultManifest, verified = true, deletedLegacyNames = deleted)
    }

    /** Re-reads bytes and identity immediately before deleting one exact path. */
    private fun deletePreservedLegacyInput(
        name: String?,
        expected: LegacySourceIdentity?,
        deleted: MutableList<String>,
    ): Boolean {
        if (name.isNullOrBlank() || expected == null) return false
        val actual = identityOf(name) ?: return false
        if (!identitiesMatch(expected, actual)) return false
        if (!io.delete(name)) return false
        deleted += name
        return true
    }

    /** Deletes only opened-chapter junk whose meaning is provable. */
    private fun cleanupOpenedChapterJunk() {
        val summaryName = "${layout.chapterKey}.summary.json"
        if (io.exists(summaryName)) io.delete(summaryName)
        val legacyName = "${layout.chapterKey}.json"
        if (io.exists(legacyName) && io.length(legacyName) == 0L) io.delete(legacyName)
    }

    private data class PreservationResult(
        val state: LegacyPreservationState,
        val requestedName: String?,
        val resolvedName: String?,
        val preservedAtEpochMs: Long? = null,
    )

    private fun preserveLegacyInput(
        sourceName: String,
        requestedName: String?,
        identity: LegacySourceIdentity?,
    ): PreservationResult {
        if (identity == null || sourceName.isBlank()) {
            return PreservationResult(LegacyPreservationState.INTENT, requestedName, null)
        }
        val baseName = sourceName + ".migrated"
        val digest = identity.sha256.take(8)
        val first = requestedName?.takeIf { it.isNotBlank() } ?: baseName
        val candidates = buildList {
            add(first)
            add(baseName)
            (1..MAX_PRESERVATION_RENAME_ATTEMPTS).forEach { suffix ->
                add("$baseName.$digest-$suffix")
            }
        }.distinct()
        candidates.forEach { candidate ->
            val targetIdentity = identityOf(candidate)
            if (targetIdentity != null && identitiesMatch(identity, targetIdentity)) {
                return preservedResult(candidate)
            }
            val sourceIdentity = identityOf(sourceName)
            if (sourceIdentity == null || !identitiesMatch(identity, sourceIdentity)) return@forEach
            when (io.renameNoReplace(sourceName, candidate)) {
                RenameResult.MOVED -> {
                    val renamedIdentity = identityOf(candidate)
                    if (renamedIdentity != null && identitiesMatch(identity, renamedIdentity)) {
                        return preservedResult(candidate)
                    }
                }
                RenameResult.DESTINATION_EXISTS -> {
                    // A target may have appeared after the admission probe;
                    // adopt it only after recomputing its content identity.
                    val observed = identityOf(candidate)
                    if (observed != null && identitiesMatch(identity, observed)) {
                        return preservedResult(candidate)
                    }
                    return@forEach
                }
                RenameResult.UNSUPPORTED,
                RenameResult.FAILED,
                -> return@forEach
            }
        }
        return PreservationResult(LegacyPreservationState.INTENT, first, null)
    }

    private fun preservedResult(candidate: String): PreservationResult = PreservationResult(
        LegacyPreservationState.PRESERVED,
        candidate,
        candidate,
        System.currentTimeMillis(),
    )

    private fun artifactGraphIsComplete(
        manifest: ChapterArtifactManifest,
        legacy: LegacyChapterSnapshot,
    ): Boolean {
        if (manifest.pages.keys != legacy.pages.keys) return false
        if (legacy.glossary.isNotEmpty()) {
            val pointer = manifest.glossary ?: return false
            if (readGlossary(pointer) == null) return false
        }
        return manifest.pages.all { (pageKey, page) ->
            val pointer = page.committed?.pageSnapshotFileName ?: return false
            readPageSnapshot(pointer) != null
        }
    }

    private fun identityOf(name: String): LegacySourceIdentity? {
        val bytes = io.read(name) ?: return null
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }
        return LegacySourceIdentity(digest, bytes.size.toLong(), io.lastModified(name))
    }

    private fun preservationTargetName(
        sourceName: String,
        identity: LegacySourceIdentity,
        suffix: Int,
    ): String {
        val base = "$sourceName.migrated"
        return if (suffix == 0) base else "$base.${identity.sha256.take(8)}-$suffix"
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

    internal fun refuseFutureDocument(which: String, document: ChapterArtifactManifest): LoadResult {
        logcat(LogPriority.WARN) {
            "TachiyomiAT artifact manifest schema unsupported; left untouched: " +
                "chapter=${layout.chapterKey} $which schema=${document.schemaVersion}"
        }
        return LoadResult(document, migratedFromLegacy = false, resyncedFromLegacy = false)
    }

    // Same-name delegations to the store-side helpers shared with the live path.
    private fun stampChapterKey(manifest: ChapterArtifactManifest) = store.stampChapterKey(manifest)

    private fun publishManifestInternal(manifest: ChapterArtifactManifest) = store.publishManifestInternal(manifest)

    private fun materializeLegacyCommittedSnapshot(
        manifest: ChapterArtifactManifest,
        pageKey: String,
        pageSnapshot: PageTranslation,
    ) = store.materializeLegacyCommittedSnapshot(manifest, pageKey, pageSnapshot)

    private fun reconcileRetention(manifest: ChapterArtifactManifest) = store.reconcileRetention(manifest)

    private fun readGlossary(pointer: GlossaryPointer) = store.readGlossary(pointer)

    private fun readPageSnapshot(fileName: String?) = store.readPageSnapshot(fileName)

    private fun displayBaseIsValid(fileName: String, source: SourceIdentity?) =
        store.displayBaseIsValid(fileName, source)

    private fun stagePayloadIsValid(record: StageArtifactRecord?) = store.stagePayloadIsValid(record)

    private fun publishGlossary(entries: Map<String, String>) = store.publishGlossary(entries)
}

/**
 * Result of the later-version cleanup gate. A failed gate is deliberately
 * indistinguishable from an unverified chapter to callers: preserved
 * legacy inputs remain the recovery source and can be retried on the next
 * chapter open.
 */
data class LegacyArtifactHealthResult(
    val manifest: ChapterArtifactManifest,
    val verified: Boolean,
    val reason: String? = null,
    val deletedLegacyNames: List<String> = emptyList(),
)
