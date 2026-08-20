package eu.kanade.translation.artifact

import eu.kanade.translation.model.PageDisplayState
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.detachedCopy
import eu.kanade.translation.model.isTextlessTerminal
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromStream
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * TachiyomiAT: owns the chapter artifact manifest and the immutable artifact
 * tree for one chapter (lifecycle contract §15).
 *
 * Phase 2 delivered deterministic migration/resync of the legacy flat
 * translation record, crash-safe publication, and bounded retention. Phase 3
 * adds the store-transaction layer on top of the same primitives: candidate
 * generation lifecycle, preconditioned stage sidecar commits, atomic
 * committed-pointer promotion, cancel/failure semantics, and the
 * legacy-to-artifact authority cutover.
 *
 * Authority cutover: while [ManifestAuthority.LEGACY], the legacy flat file
 * stays authoritative and every load may resync the manifest from its bytes.
 * The first Phase 3 transaction ([openCandidate]) flips the manifest to
 * [ManifestAuthority.ARTIFACTS]; from then on loads never resync from legacy,
 * so open-time legacy identity changes cannot overwrite transactional
 * manifest writes. Rollback/recovery is preserved: a manifest lost on both
 * copies rebuilds conservatively from the legacy record (authority resets to
 * LEGACY), and backup/quarantine rotation is unchanged.
 */
class ChapterArtifactStore(
    private val documents: AtomicChapterDocuments,
    private val layout: ChapterArtifactLayout,
    private val displayBaseProbe: CleanedImageProbe = BitmapFactoryCleanedImageProbe,
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
     *
     * ARTIFACTS-authoritative manifests are never resynced from legacy bytes;
     * instead interrupted RUNNING stages are recovered to retryable (process
     * death, lifecycle contract §13).
     */
    @Synchronized
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
            if (existing.authority == ManifestAuthority.ARTIFACTS) {
                // Phase 3 cutover: transactional writes own this manifest.
                // Legacy bytes (still written by the live pipeline) must never
                // resync over committed pointers, candidates, or generations.
                val hadInterruptedStage = existing.pages.values.any { page ->
                    ArtifactStage.entries.any { stage -> page.stage(stage)?.status == ArtifactStageStatus.RUNNING }
                }
                val recovered = recoverInterruptedStages(existing)
                // If recovery publication failed, the backup is the last
                // crash-safe copy and must remain available for the next load.
                // A successful recovery (or a load with no RUNNING stage) can
                // safely discard the stale backup.
                if (!hadInterruptedStage || recovered != existing) {
                    io.delete(backupName())
                }
                return LoadResult(recovered, migratedFromLegacy = false, resyncedFromLegacy = false)
            }
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
    @Synchronized
    fun publishManifest(manifest: ChapterArtifactManifest): Boolean =
        publishManifestInternal(stampChapterKey(manifest))

    @Synchronized
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
    @Synchronized
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

    // ------------------------------------------------------------------
    // Phase 3 store transactions (lifecycle contract §§9, 12–13, 15).
    //
    // Every operation is crash-safe: immutable sidecars are published first
    // (temp/validate/rename), the manifest pointer moves second, and a crash
    // between the two leaves an orphan file that retention reconciliation
    // removes later — never a committed pointer at a missing file. Candidate
    // writes require the generation id, the expected page version, and the
    // dependency fingerprint, so a stale worker cannot commit over newer work.
    // ------------------------------------------------------------------

    /** Outcome of one store transaction; only [Committed] changed durable state. */
    sealed interface TransactionOutcome {
        data class Committed(
            val manifest: ChapterArtifactManifest,
            val generationId: String? = null,
            val deletedFiles: List<String> = emptyList(),
        ) : TransactionOutcome

        data class Rejected(val reason: String) : TransactionOutcome
    }

    /** Bundle inputs for [promoteCandidate] (lifecycle contract §9). */
    data class PromotionBundle(
        val displayBaseKind: DisplayBaseKind,
        /** Required for [DisplayBaseKind.CLEANED_IMAGE]; validated before promotion. */
        val displayBaseFileName: String? = null,
        val translationFingerprint: String? = null,
        val layoutFingerprint: String? = null,
        val hasManualEdits: Boolean = false,
        val textless: Boolean = false,
    )

    /** Reads a complete live-store page snapshot referenced by a manifest pointer. */
    fun readPageSnapshot(fileName: String?): PageTranslation? =
        fileName?.let { documents.readValidated<PageTranslation>(it) }

    /**
     * Materializes the legacy committed page into an immutable artifact
     * snapshot before authority cutover. The legacy flat file remains
     * authoritative until [openCandidate] flips the manifest, but reopening
     * after that flip can now reconstruct the last-known-good page without
     * consulting mutable candidate JSON.
     */
    @Synchronized
    fun materializeLegacyCommittedSnapshot(
        manifest: ChapterArtifactManifest,
        pageKey: String,
        pageSnapshot: PageTranslation,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): TransactionOutcome {
        staleManifestRejection(manifest)?.let { return TransactionOutcome.Rejected(it) }
        val page = manifest.pages[pageKey]
            ?: return TransactionOutcome.Rejected("page missing: pageKey=$pageKey")
        val committed = page.committed
            ?: return TransactionOutcome.Rejected("committed bundle missing: pageKey=$pageKey")
        committed.pageSnapshotFileName?.let { fileName ->
            if (readPageSnapshot(fileName) != null) {
                return TransactionOutcome.Committed(manifest, committed.generationId)
            }
        }
        val fileName = layout.committedPageSnapshotFile(pageKey, committed.generationId)
        if (!documents.publishJson(fileName, pageSnapshot.detachedCopy())) {
            return TransactionOutcome.Rejected("committed page snapshot publication failed: pageKey=$pageKey")
        }
        val updated = manifest.copy(
            pages = manifest.pages + (
                pageKey to page.copy(
                    committed = committed.copy(pageSnapshotFileName = fileName),
                    pageVersion = page.pageVersion + 1,
                )
                ),
            updatedAtEpochMs = nowEpochMs,
        )
        if (!publishManifestInternal(updated)) {
            return TransactionOutcome.Rejected("manifest publication failed; committed snapshot pointer unchanged")
        }
        return TransactionOutcome.Committed(updated, committed.generationId)
    }

    /**
     * Persists the mutable live candidate in its own immutable sidecar and
     * advances only the candidate pointer. The committed pointer is untouched
     * until [promoteLiveCandidate] succeeds.
     */
    @Synchronized
    fun persistLiveCandidate(
        manifest: ChapterArtifactManifest,
        pageKey: String,
        generationId: String,
        expectedPageVersion: Long,
        expectedDependencyFingerprint: String,
        pageSnapshot: PageTranslation,
        origin: ArtifactOrigin,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): TransactionOutcome {
        val rejection = candidateWriteRejection(
            manifest,
            pageKey,
            generationId,
            expectedPageVersion,
            expectedDependencyFingerprint,
        )
        if (rejection != null) return TransactionOutcome.Rejected(rejection)
        val page = manifest.pages.getValue(pageKey)
        if (page.candidate?.origin != origin) {
            return TransactionOutcome.Rejected(
                "candidate provenance mismatch: pageKey=$pageKey expected=${page.candidate?.origin} actual=$origin",
            )
        }
        val fileName = page.candidate?.pageSnapshotFileName
            ?: layout.candidatePageSnapshotFile(pageKey, generationId)
        if (!documents.publishJson(fileName, pageSnapshot.detachedCopy())) {
            return TransactionOutcome.Rejected("candidate page snapshot publication failed: pageKey=$pageKey")
        }
        val updated = manifest.copy(
            pages = manifest.pages + (
                pageKey to page.copy(
                    candidate = page.candidate.copy(pageSnapshotFileName = fileName),
                    pageVersion = page.pageVersion + 1,
                )
                ),
            updatedAtEpochMs = nowEpochMs,
        )
        if (!publishManifestInternal(updated)) {
            return TransactionOutcome.Rejected("manifest publication failed; candidate snapshot pointer unchanged")
        }
        return TransactionOutcome.Committed(updated, generationId)
    }

    /**
     * Commits the complete live candidate page and atomically moves the
     * committed pointer. Both snapshots are published before the manifest
     * pointer changes, so a crash can only leave an orphan candidate file,
     * never a committed pointer to a missing or partial page.
     */
    @Synchronized
    fun promoteLiveCandidate(
        manifest: ChapterArtifactManifest,
        pageKey: String,
        generationId: String,
        expectedPageVersion: Long,
        expectedDependencyFingerprint: String,
        pageSnapshot: PageTranslation,
        origin: ArtifactOrigin,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): TransactionOutcome {
        val rejection = candidateWriteRejection(
            manifest,
            pageKey,
            generationId,
            expectedPageVersion,
            expectedDependencyFingerprint,
        )
        if (rejection != null) return TransactionOutcome.Rejected(rejection)
        val page = manifest.pages.getValue(pageKey)
        val candidate = page.candidate
            ?: return TransactionOutcome.Rejected("candidate missing: pageKey=$pageKey")
        if (candidate.origin != origin) {
            return TransactionOutcome.Rejected(
                "candidate provenance mismatch: pageKey=$pageKey expected=${candidate.origin} actual=$origin",
            )
        }
        pageSnapshot.cleanedImageName?.let { cleanedName ->
            if (!ChapterArtifactLayout.isSafeSegment(cleanedName)) {
                return TransactionOutcome.Rejected("unsafe cleaned display file name: pageKey=$pageKey")
            }
            if (!displayBaseIsValid(layout.legacyCompanionImageFile(cleanedName), page.source)) {
                return TransactionOutcome.Rejected(
                    "cleaned display base file missing, corrupt, or wrong-sized: pageKey=$pageKey file=$cleanedName",
                )
            }
        }
        val candidateFile = candidate.pageSnapshotFileName
            ?: layout.candidatePageSnapshotFile(pageKey, generationId)
        if (!documents.publishJson(candidateFile, pageSnapshot.detachedCopy())) {
            return TransactionOutcome.Rejected("candidate page snapshot publication failed: pageKey=$pageKey")
        }
        val committedFile = layout.committedPageSnapshotFile(pageKey, generationId)
        if (!documents.publishJson(committedFile, pageSnapshot.detachedCopy())) {
            return TransactionOutcome.Rejected("committed page snapshot publication failed: pageKey=$pageKey")
        }
        val displayBase = DisplayBaseReference(
            kind = if (pageSnapshot.cleanedImageName != null) DisplayBaseKind.CLEANED_IMAGE else DisplayBaseKind.ORIGINAL_SOURCE,
            fileName = pageSnapshot.cleanedImageName,
            validated = pageSnapshot.cleanedImageName != null,
            legacyLayout = pageSnapshot.cleanedImageName != null,
        )
        val committed = CommittedBundleMetadata(
            generationId = generationId,
            bundleFingerprint = StageFingerprints.committedBundle(
                sourceIdentity = page.source,
                displayBase = displayBase,
                translationFingerprint = StageFingerprints.pageSnapshot(pageSnapshot),
                layoutFingerprint = null,
            ),
            displayBase = displayBase,
            translationFingerprint = StageFingerprints.pageSnapshot(pageSnapshot),
            origin = origin,
            provisional = false,
            hasManualEdits = pageSnapshot.blocks.any { it.userEditedAt != null },
            promotedAtEpochMs = nowEpochMs,
            pageSnapshotFileName = committedFile,
        )
        val generationRecord = GenerationRecord(
            generationId = generationId,
            pageKey = pageKey,
            origin = origin,
            lifecycle = GenerationLifecycle.COMMITTED,
            createdAtEpochMs = candidate.createdAtEpochMs,
            closedAtEpochMs = nowEpochMs,
        )
        if (!documents.publishJson(layout.generationFile(generationId), generationRecord)) {
            return TransactionOutcome.Rejected("generation record publication failed: generationId=$generationId")
        }
        val updated = manifest.copy(
            pages = manifest.pages + (
                pageKey to page.copy(
                    committed = committed,
                    previousCommitted = page.committed,
                    candidate = null,
                    displayState = if (pageSnapshot.isTextlessTerminal) {
                        PageDisplayState.TEXTLESS_COMPLETE
                    } else {
                        PageDisplayState.DISPLAY_READY
                    },
                    pageVersion = page.pageVersion + 1,
                )
                ),
            activeCandidateGenerationIds = manifest.activeCandidateGenerationIds - generationId,
            updatedAtEpochMs = nowEpochMs,
        )
        if (!publishManifestInternal(updated)) {
            return TransactionOutcome.Rejected("manifest publication failed; committed pointer unchanged")
        }
        val retention = reconcileRetention(updated)
        return TransactionOutcome.Committed(updated, generationId, retention.deletedNames)
    }

    /** Cancels a live candidate while retaining the committed pointer. */
    @Synchronized
    fun cancelLiveCandidate(
        manifest: ChapterArtifactManifest,
        pageKey: String,
        generationId: String,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): TransactionOutcome = cancelCandidate(manifest, pageKey, generationId, nowEpochMs)

    /** Explicit user reset: remove committed/candidate pointers durably. */
    @Synchronized
    fun demoteLivePage(
        manifest: ChapterArtifactManifest,
        pageKey: String,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): TransactionOutcome {
        staleManifestRejection(manifest)?.let { return TransactionOutcome.Rejected(it) }
        val page = manifest.pages[pageKey]
            ?: return TransactionOutcome.Rejected("page missing: pageKey=$pageKey")
        val candidateGenerationId = page.candidate?.generationId
        val generationRecord = candidateGenerationId?.let { generationId ->
            GenerationRecord(
                generationId = generationId,
                pageKey = pageKey,
                origin = page.candidate?.origin ?: ArtifactOrigin.BATCH,
                lifecycle = GenerationLifecycle.CANCELLED,
                createdAtEpochMs = page.candidate?.createdAtEpochMs ?: nowEpochMs,
                closedAtEpochMs = nowEpochMs,
            )
        }
        if (generationRecord != null && !documents.publishJson(layout.generationFile(generationRecord.generationId), generationRecord)) {
            return TransactionOutcome.Rejected("generation record publication failed: generationId=${generationRecord.generationId}")
        }
        val updated = manifest.copy(
            pages = manifest.pages + (
                pageKey to page.copy(
                    committed = null,
                    previousCommitted = null,
                    candidate = null,
                    displayState = PageDisplayState.ORIGINAL_ONLY,
                    pageVersion = page.pageVersion + 1,
                )
                ),
            activeCandidateGenerationIds = candidateGenerationId?.let { manifest.activeCandidateGenerationIds - it }
                ?: manifest.activeCandidateGenerationIds,
            updatedAtEpochMs = nowEpochMs,
        )
        if (!publishManifestInternal(updated)) {
            return TransactionOutcome.Rejected("manifest publication failed; reset pointers remain authoritative")
        }
        val retention = reconcileRetention(updated)
        return TransactionOutcome.Committed(updated, candidateGenerationId, retention.deletedNames)
    }

    /** Removes one page from the artifact-authoritative live manifest. */
    @Synchronized
    fun deleteLivePage(
        manifest: ChapterArtifactManifest,
        pageKey: String,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): TransactionOutcome {
        staleManifestRejection(manifest)?.let { return TransactionOutcome.Rejected(it) }
        if (pageKey !in manifest.pages) return TransactionOutcome.Committed(manifest)
        val updated = manifest.copy(
            pages = manifest.pages - pageKey,
            activeCandidateGenerationIds = manifest.activeCandidateGenerationIds -
                manifest.pages[pageKey]?.candidate?.generationId.orEmpty(),
            updatedAtEpochMs = nowEpochMs,
        )
        if (!publishManifestInternal(updated)) {
            return TransactionOutcome.Rejected("manifest publication failed; page remains authoritative: pageKey=$pageKey")
        }
        val retention = reconcileRetention(updated)
        return TransactionOutcome.Committed(updated, deletedFiles = retention.deletedNames)
    }

    /**
     * Opens (or idempotently reopens) a candidate generation for one page.
     * This is the authority cutover point: a LEGACY-authoritative manifest is
     * flipped to ARTIFACTS so later opens can no longer resync it from legacy
     * bytes. Preconditions bind the caller to the current page version and
     * dependency fingerprint (stale workers are rejected).
     */
    @Synchronized
    fun openCandidate(
        manifest: ChapterArtifactManifest,
        pageKey: String,
        origin: ArtifactOrigin,
        expectedPageVersion: Long,
        dependencyFingerprint: String,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): TransactionOutcome {
        staleManifestRejection(manifest)?.let { return TransactionOutcome.Rejected(it) }
        val page = manifest.pages[pageKey]
            ?: PageArtifactRecord(pageKey = pageKey)
        if (page.pageVersion != expectedPageVersion) {
            return TransactionOutcome.Rejected(
                "stale page version: pageKey=$pageKey expected=$expectedPageVersion actual=${page.pageVersion}",
            )
        }
        val existing = page.candidate
        val generationId = when {
            existing != null &&
                existing.origin == origin &&
                existing.origin != ArtifactOrigin.LEGACY &&
                existing.dependencyFingerprint == dependencyFingerprint -> existing.generationId
            existing != null && existing.origin == ArtifactOrigin.LEGACY -> newGenerationId(pageKey, nowEpochMs)
            existing != null ->
                return TransactionOutcome.Rejected(
                    "candidate already active with a different dependency fingerprint: pageKey=$pageKey",
                )
            else -> newGenerationId(pageKey, nowEpochMs)
        }
        val cutOver = manifest.authority == ManifestAuthority.LEGACY
        val candidate = CandidateGenerationMetadata(
            generationId = generationId,
            origin = origin,
            dependencyFingerprint = dependencyFingerprint,
            createdAtEpochMs = existing?.createdAtEpochMs ?: nowEpochMs,
            priorDisplayState = existing?.priorDisplayState ?: page.displayState,
        )
        val generationRecord = GenerationRecord(
            generationId = generationId,
            pageKey = pageKey,
            origin = origin,
            lifecycle = GenerationLifecycle.ACTIVE,
            createdAtEpochMs = candidate.createdAtEpochMs,
        )
        if (!documents.publishJson(layout.generationFile(generationId), generationRecord)) {
            return TransactionOutcome.Rejected("generation record publication failed: generationId=$generationId")
        }
        val updated = manifest.copy(
            pages = manifest.pages + (
                pageKey to page.copy(
                    candidate = candidate,
                    displayState = if (page.committed != null) {
                        PageDisplayState.REFRESHING_WITH_COMMITTED_RESULT
                    } else {
                        PageDisplayState.CANDIDATE_RUNNING
                    },
                    pageVersion = page.pageVersion + if (existing == null || existing.origin == ArtifactOrigin.LEGACY) 1L else 0L,
                )
                ),
            activeCandidateGenerationIds = (
                manifest.activeCandidateGenerationIds -
                    existing?.generationId.orEmpty()
                ) + generationId,
            authority = ManifestAuthority.ARTIFACTS,
            cutoverAtEpochMs = manifest.cutoverAtEpochMs ?: nowEpochMs.takeIf { cutOver },
            updatedAtEpochMs = nowEpochMs,
        )
        if (!publishManifestInternal(updated)) {
            return TransactionOutcome.Rejected("manifest publication failed; prior manifest remains authoritative")
        }
        return TransactionOutcome.Committed(updated, generationId)
    }

    /**
     * Commits one immutable stage payload sidecar and its manifest record.
     * The sidecar file is published and validated first; only then does the
     * manifest record point at it. A crash in between leaves an orphan sidecar
     * (swept by retention) and no committed stage.
     */
    @Synchronized
    fun commitStagePayload(
        manifest: ChapterArtifactManifest,
        pageKey: String,
        stage: ArtifactStage,
        generationId: String,
        expectedPageVersion: Long,
        expectedDependencyFingerprint: String,
        fingerprint: String,
        payload: JsonObject,
        origin: ArtifactOrigin,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): TransactionOutcome {
        val rejection = candidateWriteRejection(
            manifest,
            pageKey,
            generationId,
            expectedPageVersion,
            expectedDependencyFingerprint,
        )
        if (rejection != null) return TransactionOutcome.Rejected(rejection)
        val page = manifest.pages.getValue(pageKey)
        if (page.candidate?.origin != origin) {
            return TransactionOutcome.Rejected(
                "candidate provenance mismatch: pageKey=$pageKey expected=${page.candidate?.origin} actual=$origin",
            )
        }
        page.stage(stage)?.let { record ->
            if (record.status == ArtifactStageStatus.READY && record.fingerprint == fingerprint) {
                return if (stagePayloadIsValid(record)) {
                    TransactionOutcome.Committed(manifest, generationId)
                } else {
                    TransactionOutcome.Rejected("existing stage payload is missing or invalid: pageKey=$pageKey stage=$stage")
                }
            }
        }
        val artifactFile = layout.stageArtifactFile(pageKey, stage, fingerprint)
        if (io.exists(artifactFile) && documents.readValidated<JsonObject>(artifactFile) == null) {
            return TransactionOutcome.Rejected("existing stage payload is invalid: pageKey=$pageKey stage=$stage")
        }
        if (!documents.publishJson(artifactFile, payload)) {
            return TransactionOutcome.Rejected("stage sidecar publication failed: stage=$stage pageKey=$pageKey")
        }
        val record = StageArtifactRecord(
            status = ArtifactStageStatus.READY,
            fingerprint = fingerprint,
            origin = origin,
            artifactFileName = artifactFile,
            generationId = generationId,
            updatedAtEpochMs = nowEpochMs,
        )
        val updated = manifest.copy(
            pages = manifest.pages + (pageKey to page.withStage(stage, record).copy(pageVersion = page.pageVersion + 1)),
            updatedAtEpochMs = nowEpochMs,
        )
        if (!publishManifestInternal(updated)) {
            return TransactionOutcome.Rejected("manifest publication failed; prior manifest remains authoritative")
        }
        return TransactionOutcome.Committed(updated, generationId)
    }

    /**
     * Marks a candidate stage RUNNING before work begins. Persisting this marker
     * makes process death recoverable: [loadOrMigrate] can turn it into a
     * retryable stage while leaving the committed pointer untouched.
     */
    @Synchronized
    fun beginStage(
        manifest: ChapterArtifactManifest,
        pageKey: String,
        stage: ArtifactStage,
        generationId: String,
        expectedPageVersion: Long,
        expectedDependencyFingerprint: String,
        fingerprint: String,
        origin: ArtifactOrigin,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): TransactionOutcome {
        val rejection = candidateWriteRejection(
            manifest,
            pageKey,
            generationId,
            expectedPageVersion,
            expectedDependencyFingerprint,
        )
        if (rejection != null) return TransactionOutcome.Rejected(rejection)
        val page = manifest.pages.getValue(pageKey)
        if (page.candidate?.origin != origin) {
            return TransactionOutcome.Rejected(
                "candidate provenance mismatch: pageKey=$pageKey expected=${page.candidate?.origin} actual=$origin",
            )
        }
        val current = page.stage(stage)
        if (current?.status == ArtifactStageStatus.RUNNING && current.fingerprint == fingerprint) {
            return TransactionOutcome.Committed(manifest, generationId)
        }
        val running = StageArtifactRecord(
            status = ArtifactStageStatus.RUNNING,
            fingerprint = fingerprint,
            origin = origin,
            generationId = generationId,
            updatedAtEpochMs = nowEpochMs,
        )
        val updated = manifest.copy(
            pages = manifest.pages + (
                pageKey to page.withStage(stage, running).copy(pageVersion = page.pageVersion + 1)
                ),
            updatedAtEpochMs = nowEpochMs,
        )
        if (!publishManifestInternal(updated)) {
            return TransactionOutcome.Rejected("manifest publication failed; prior manifest remains authoritative")
        }
        return TransactionOutcome.Committed(updated, generationId)
    }

    /**
     * Atomically promotes a complete candidate into the committed display
     * bundle. The display-base file must exist and be non-empty before the
     * pointer moves; observers of the manifest see either the complete old
     * bundle or the complete new one, never a mix. The superseded committed
     * bundle is retained as `previousCommitted` (one generation), and bounded
     * retention reconciliation removes files only the canceled-in-place
     * candidate still owned.
     */
    @Synchronized
    fun promoteCandidate(
        manifest: ChapterArtifactManifest,
        pageKey: String,
        generationId: String,
        expectedPageVersion: Long,
        expectedDependencyFingerprint: String,
        bundle: PromotionBundle,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): TransactionOutcome {
        val rejection = candidateWriteRejection(
            manifest,
            pageKey,
            generationId,
            expectedPageVersion,
            expectedDependencyFingerprint,
        )
        if (rejection != null) return TransactionOutcome.Rejected(rejection)
        val page = manifest.pages.getValue(pageKey)
        if (!bundle.textless) {
            val candidate = page.candidate
                ?: return TransactionOutcome.Rejected("candidate missing: pageKey=$pageKey")
            val translationReady = page.translation?.status == ArtifactStageStatus.READY &&
                page.translation.fingerprint == bundle.translationFingerprint &&
                page.translation.generationId == generationId &&
                page.translation.origin == candidate.origin
            val layoutReady = page.layout?.status == ArtifactStageStatus.READY &&
                page.layout.fingerprint == bundle.layoutFingerprint &&
                page.layout.generationId == generationId &&
                page.layout.origin == candidate.origin
            if (!translationReady || !layoutReady) {
                return TransactionOutcome.Rejected(
                    "promotion requires READY translation and layout with matching fingerprints: pageKey=$pageKey",
                )
            }
            if (!stagePayloadIsValid(page.translation) || !stagePayloadIsValid(page.layout)) {
                return TransactionOutcome.Rejected(
                    "promotion requires valid translation/layout payloads: pageKey=$pageKey",
                )
            }
        }
        if (bundle.displayBaseKind == DisplayBaseKind.CLEANED_IMAGE) {
            val fileName = bundle.displayBaseFileName
                ?: return TransactionOutcome.Rejected("cleaned display base requires a file name: pageKey=$pageKey")
            if (!displayBaseIsValid(fileName, page.source)) {
                return TransactionOutcome.Rejected(
                    "cleaned display base file missing, corrupt, or wrong-sized: pageKey=$pageKey file=$fileName",
                )
            }
        }
        val candidateOrigin = page.candidate?.origin ?: ArtifactOrigin.BATCH
        val displayBase = DisplayBaseReference(
            kind = bundle.displayBaseKind,
            fileName = bundle.displayBaseFileName,
            validated = true,
            legacyLayout = false,
        )
        val committed = CommittedBundleMetadata(
            generationId = generationId,
            bundleFingerprint = StageFingerprints.committedBundle(
                sourceIdentity = page.source,
                displayBase = displayBase,
                translationFingerprint = bundle.translationFingerprint,
                layoutFingerprint = bundle.layoutFingerprint,
            ),
            displayBase = displayBase,
            translationFingerprint = bundle.translationFingerprint,
            layoutFingerprint = bundle.layoutFingerprint,
            origin = candidateOrigin,
            provisional = false,
            hasManualEdits = bundle.hasManualEdits,
            promotedAtEpochMs = nowEpochMs,
        )
        val generationRecord = GenerationRecord(
            generationId = generationId,
            pageKey = pageKey,
            origin = candidateOrigin,
            lifecycle = GenerationLifecycle.COMMITTED,
            createdAtEpochMs = page.candidate?.createdAtEpochMs ?: nowEpochMs,
            closedAtEpochMs = nowEpochMs,
        )
        if (!documents.publishJson(layout.generationFile(generationId), generationRecord)) {
            return TransactionOutcome.Rejected("generation record publication failed: generationId=$generationId")
        }
        val updated = manifest.copy(
            pages = manifest.pages + (
                pageKey to page.copy(
                    committed = committed,
                    previousCommitted = page.committed,
                    candidate = null,
                    displayState = if (bundle.textless) PageDisplayState.TEXTLESS_COMPLETE else PageDisplayState.DISPLAY_READY,
                    pageVersion = page.pageVersion + 1,
                )
                ),
            activeCandidateGenerationIds = manifest.activeCandidateGenerationIds - generationId,
            updatedAtEpochMs = nowEpochMs,
        )
        if (!publishManifestInternal(updated)) {
            return TransactionOutcome.Rejected("manifest publication failed; committed pointer unchanged")
        }
        val retention = reconcileRetention(updated)
        return TransactionOutcome.Committed(updated, generationId, retention.deletedNames)
    }

    /**
     * Cancels a candidate: removes only candidate-owned metadata/files, keeps
     * the committed bundle (and its retained previous generation) untouched
     * (lifecycle contract §13). Files are reclaimed exclusively through store
     * reachability after the manifest update — never by name pattern alone.
     */
    @Synchronized
    fun cancelCandidate(
        manifest: ChapterArtifactManifest,
        pageKey: String,
        generationId: String,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): TransactionOutcome {
        staleManifestRejection(manifest)?.let { return TransactionOutcome.Rejected(it) }
        val page = manifest.pages[pageKey]
            ?: return TransactionOutcome.Rejected("page missing: pageKey=$pageKey")
        if (page.candidate?.generationId != generationId) {
            return TransactionOutcome.Rejected(
                "candidate mismatch: pageKey=$pageKey expected=$generationId actual=${page.candidate?.generationId}",
            )
        }
        fun isCandidateOwned(record: StageArtifactRecord?): Boolean =
            record != null && record.generationId == generationId

        val candidateFailureKeys = ArtifactStage.entries
            .filter { stage -> isCandidateOwned(page.stage(stage)) }
            .map { stage -> "$pageKey:${stage.name}" }

        val cleaned = page.copy(
            detection = page.detection.takeUnless(::isCandidateOwned),
            ocr = page.ocr.takeUnless(::isCandidateOwned),
            inpaint = page.inpaint.takeUnless(::isCandidateOwned),
            translation = page.translation.takeUnless(::isCandidateOwned),
            layout = page.layout.takeUnless(::isCandidateOwned),
            candidate = null,
            displayState = when {
                page.candidate?.priorDisplayState == PageDisplayState.TEXTLESS_COMPLETE -> PageDisplayState.TEXTLESS_COMPLETE
                page.displayState == PageDisplayState.TEXTLESS_COMPLETE -> PageDisplayState.TEXTLESS_COMPLETE
                page.committed != null -> PageDisplayState.DISPLAY_READY
                else -> PageDisplayState.ORIGINAL_ONLY
            },
            pageVersion = page.pageVersion + 1,
        )
        val generationRecord = GenerationRecord(
            generationId = generationId,
            pageKey = pageKey,
            origin = page.candidate?.origin ?: ArtifactOrigin.BATCH,
            lifecycle = GenerationLifecycle.CANCELLED,
            createdAtEpochMs = page.candidate?.createdAtEpochMs ?: nowEpochMs,
            closedAtEpochMs = nowEpochMs,
        )
        if (!documents.publishJson(layout.generationFile(generationId), generationRecord)) {
            return TransactionOutcome.Rejected("generation record publication failed: generationId=$generationId")
        }
        val updated = manifest.copy(
            pages = manifest.pages + (pageKey to cleaned),
            activeCandidateGenerationIds = manifest.activeCandidateGenerationIds - generationId,
            durableFailures = manifest.durableFailures - candidateFailureKeys.toSet(),
            updatedAtEpochMs = nowEpochMs,
        )
        if (!publishManifestInternal(updated)) {
            return TransactionOutcome.Rejected("manifest publication failed; candidate remains recorded")
        }
        val retention = reconcileRetention(updated)
        return TransactionOutcome.Committed(updated, generationId, retention.deletedNames)
    }

    /**
     * Records a candidate stage failure durably while keeping the committed
     * bundle and candidate diagnostics (lifecycle contract §13).
     */
    @Synchronized
    fun markCandidateStageFailed(
        manifest: ChapterArtifactManifest,
        pageKey: String,
        stage: ArtifactStage,
        generationId: String,
        status: ArtifactStageStatus,
        category: FailureCategory,
        message: String?,
        retryCount: Int,
        failureFingerprint: String?,
        expectedPageVersion: Long,
        expectedDependencyFingerprint: String,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): TransactionOutcome {
        if (status != ArtifactStageStatus.FAILED_RETRYABLE && status != ArtifactStageStatus.FAILED_TERMINAL) {
            return TransactionOutcome.Rejected("failure status required: got=$status")
        }
        val page = manifest.pages[pageKey]
            ?: return TransactionOutcome.Rejected("page missing: pageKey=$pageKey")
        candidateWriteRejection(
            manifest,
            pageKey,
            generationId,
            expectedPageVersion,
            expectedDependencyFingerprint,
        )?.let { return TransactionOutcome.Rejected(it) }
        val failedRecord = StageArtifactRecord(
            status = status,
            fingerprint = failureFingerprint,
            origin = page.candidate?.origin ?: ArtifactOrigin.BATCH,
            generationId = generationId,
            updatedAtEpochMs = nowEpochMs,
        )
        val failure = DurableFailureMetadata(
            pageKey = pageKey,
            stage = stage,
            status = status,
            category = category,
            retryCount = retryCount,
            lastFailureMessage = message,
            lastFailedAtEpochMs = nowEpochMs,
            failureFingerprint = failureFingerprint,
        )
        val updated = manifest.copy(
            pages = manifest.pages + (
                pageKey to page.copy(
                    displayState = if (page.committed != null) {
                        PageDisplayState.FAILED_WITH_COMMITTED_RESULT
                    } else {
                        PageDisplayState.FAILED_NO_RESULT
                    },
                ).withStage(stage, failedRecord).copy(pageVersion = page.pageVersion + 1)
                ),
            durableFailures = manifest.durableFailures + ("$pageKey:${stage.name}" to failure),
            updatedAtEpochMs = nowEpochMs,
        )
        if (!publishManifestInternal(updated)) {
            return TransactionOutcome.Rejected("manifest publication failed; failure not durable")
        }
        return TransactionOutcome.Committed(updated, generationId)
    }

    /**
     * Publishes a page-scoped trusted context checkpoint. Reader ad-hoc
     * provenance is rejected outright: a `READER_ADHOC` result may be committed
     * and displayed, but it never advances ordered batch context (lifecycle
     * contract §12).
     */
    @Synchronized
    fun commitContextCheckpoint(
        manifest: ChapterArtifactManifest,
        pageKey: String,
        naturalPageIndex: Int,
        checkpointHash: String,
        payload: JsonObject,
        generationId: String,
        origin: ArtifactOrigin,
        expectedPageVersion: Long,
        expectedDependencyFingerprint: String,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): TransactionOutcome {
        if (origin == ArtifactOrigin.READER_ADHOC) {
            return TransactionOutcome.Rejected("READER_ADHOC writes never advance batch context: pageKey=$pageKey")
        }
        val page = manifest.pages[pageKey]
            ?: return TransactionOutcome.Rejected("page missing: pageKey=$pageKey")
        candidateWriteRejection(
            manifest,
            pageKey,
            generationId,
            expectedPageVersion,
            expectedDependencyFingerprint,
        )?.let { return TransactionOutcome.Rejected(it) }
        if (page.candidate?.origin != origin) {
            return TransactionOutcome.Rejected(
                "candidate provenance mismatch: pageKey=$pageKey expected=${page.candidate?.origin} actual=$origin",
            )
        }
        if (page.candidate?.origin == ArtifactOrigin.READER_ADHOC) {
            return TransactionOutcome.Rejected("reader-adhoc candidate cannot write batch context: pageKey=$pageKey")
        }
        val checkpointFile = layout.contextCheckpointFile(naturalPageIndex, checkpointHash)
        if (!documents.publishJson(checkpointFile, payload)) {
            return TransactionOutcome.Rejected("checkpoint sidecar publication failed: pageKey=$pageKey")
        }
        val updated = manifest.copy(
            pages = manifest.pages + (
                pageKey to page.copy(
                    contextCheckpointFileName = checkpointFile,
                    pageVersion = page.pageVersion + 1,
                )
                ),
            updatedAtEpochMs = nowEpochMs,
        )
        if (!publishManifestInternal(updated)) {
            return TransactionOutcome.Rejected("manifest publication failed; checkpoint not recorded")
        }
        return TransactionOutcome.Committed(updated, generationId)
    }

    private fun candidateWriteRejection(
        manifest: ChapterArtifactManifest,
        pageKey: String,
        generationId: String,
        expectedPageVersion: Long,
        expectedDependencyFingerprint: String,
    ): String? {
        staleManifestRejection(manifest)?.let { return it }
        val page = manifest.pages[pageKey] ?: return "page missing: pageKey=$pageKey"
        if (manifest.authority != ManifestAuthority.ARTIFACTS) {
            return "manifest is not artifact-authoritative: authority=${manifest.authority}"
        }
        if (page.candidate?.generationId != generationId) {
            return "candidate mismatch: pageKey=$pageKey expected=$generationId actual=${page.candidate?.generationId}"
        }
        if (page.pageVersion != expectedPageVersion) {
            return "stale page version: pageKey=$pageKey expected=$expectedPageVersion actual=${page.pageVersion}"
        }
        if (page.candidate?.dependencyFingerprint != expectedDependencyFingerprint) {
            return "dependency fingerprint changed: pageKey=$pageKey"
        }
        return null
    }

    /**
     * Process-death recovery (lifecycle contract §13): a stage persisted as
     * RUNNING without a complete commit marker becomes retryable. Committed
     * pointers are never touched.
     */
    private fun recoverInterruptedStages(manifest: ChapterArtifactManifest): ChapterArtifactManifest {
        var changed = false
        val now = System.currentTimeMillis()
        val pages = manifest.pages.mapValues { (_, page) ->
            var updated = page
            ArtifactStage.entries.forEach { stage ->
                val record = updated.stage(stage)
                if (record?.status == ArtifactStageStatus.RUNNING) {
                    changed = true
                    updated = updated.withStage(
                        stage,
                        record.copy(
                            status = ArtifactStageStatus.FAILED_RETRYABLE,
                            updatedAtEpochMs = now,
                        ),
                    )
                }
            }
            if (updated !== page) updated.copy(pageVersion = page.pageVersion + 1) else updated
        }
        if (!changed) return manifest
        val recovered = manifest.copy(pages = pages, updatedAtEpochMs = now)
        if (!publishManifestInternal(recovered)) {
            logcat(LogPriority.WARN) {
                "TachiyomiAT interrupted-stage recovery publish failed; prior manifest retained: " +
                    "chapter=${layout.chapterKey}"
            }
            return manifest
        }
        return recovered
    }

    private fun newGenerationId(pageKey: String, nowEpochMs: Long): String =
        "g-$nowEpochMs-${layout.pageSegment(pageKey).takeLast(24)}"

    private fun staleManifestRejection(manifest: ChapterArtifactManifest): String? {
        val durable = readManifest()
            ?: return "manifest is not durable: chapter=${layout.chapterKey}"
        return if (durable == manifest) {
            null
        } else {
            "stale manifest snapshot: chapter=${layout.chapterKey}"
        }
    }

    private fun stagePayloadIsValid(record: StageArtifactRecord?): Boolean {
        val fileName = record?.artifactFileName ?: return false
        return io.exists(fileName) &&
            io.length(fileName) > 0L &&
            documents.readValidated<JsonObject>(fileName) != null
    }

    private fun displayBaseIsValid(fileName: String, source: SourceIdentity?): Boolean {
        if (!io.exists(fileName) || io.length(fileName) <= 0L) return false
        val probed = runCatching {
            io.openInputStream(fileName)?.use(displayBaseProbe::probe)
        }.getOrNull() ?: return false
        if (probed.width <= 0 || probed.height <= 0) return false
        val expectedWidth = source?.width
        val expectedHeight = source?.height
        val matches = (expectedWidth == null || expectedWidth == probed.width) &&
            (expectedHeight == null || expectedHeight == probed.height)
        return matches
    }

    private fun PageArtifactRecord.stage(stage: ArtifactStage): StageArtifactRecord? = when (stage) {
        ArtifactStage.DETECTION -> detection
        ArtifactStage.OCR -> ocr
        ArtifactStage.INPAINT -> inpaint
        ArtifactStage.TRANSLATION -> translation
        ArtifactStage.LAYOUT -> layout
    }

    private fun PageArtifactRecord.withStage(
        stage: ArtifactStage,
        record: StageArtifactRecord?,
    ): PageArtifactRecord = when (stage) {
        ArtifactStage.DETECTION -> copy(detection = record)
        ArtifactStage.OCR -> copy(ocr = record)
        ArtifactStage.INPAINT -> copy(inpaint = record)
        ArtifactStage.TRANSLATION -> copy(translation = record)
        ArtifactStage.LAYOUT -> copy(layout = record)
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
    @Synchronized
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
                bundle.pageSnapshotFileName?.let(::add)
                bundle.displayBase.fileName?.takeIf { !bundle.displayBase.legacyLayout }?.let(::add)
            }
            page.candidate?.let {
                add(layout.generationFile(it.generationId))
                it.pageSnapshotFileName?.let(::add)
            }
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
        if (readManifestDocument(layout.manifestFileName)?.schemaVersion
                ?.let { it > ChapterArtifactManifest.SCHEMA_VERSION } == true
        ) {
            logcat(LogPriority.WARN) {
                "TachiyomiAT manifest publication skipped: reason=future-schema primary preserved " +
                    "chapter=${layout.chapterKey}"
            }
            return false
        }
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
