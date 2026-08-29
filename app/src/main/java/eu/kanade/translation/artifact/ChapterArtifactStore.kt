package eu.kanade.translation.artifact

import eu.kanade.translation.batch.BatchDiagnosticReason
import eu.kanade.translation.batch.BatchDiagnosticStage
import eu.kanade.translation.batch.BatchTranslationDiagnostics
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
 * Phase 2 delivered deterministic mapping of the legacy flat translation
 * record, crash-safe publication, and bounded retention. Phase 3 adds the
 * store-transaction layer on top of the same primitives: candidate
 * generation lifecycle, preconditioned stage sidecar commits, atomic
 * committed-pointer promotion, cancel/failure semantics, and the
 * legacy-to-artifact authority cutover.
 *
 * Authority cutover: while [ManifestAuthority.LEGACY], the manifest is a
 * rescue staging record and one load may materialize the legacy graph. The
 * final rescue publication flips it to [ManifestAuthority.ARTIFACTS]; from
 * then on loads never resync from legacy, so open-time legacy identity changes
 * cannot overwrite transactional manifest writes. Rollback/recovery is
 * preserved: a manifest lost on both copies rebuilds conservatively from the
 * legacy record (authority resets to LEGACY), and backup/quarantine rotation
 * remains crash-safe.
 */
class ChapterArtifactStore(
    private val documents: AtomicChapterDocuments,
    private val layout: ChapterArtifactLayout,
    private val displayBaseProbe: CleanedImageProbe = BitmapFactoryCleanedImageProbe,
) {
    private val io: ChapterDocumentIo get() = documents.rawIo()

    /** Legacy flat-file rescue/preservation/health machine (T909 Phase 2). */
    private val legacyRescue: LegacyArtifactRescue by lazy {
        LegacyArtifactRescue(io, layout, this)
    }

    /** Bounded retention sweep (T909 Phase 2b). */
    private val retentionSweep = ArtifactRetention(io, layout)

    data class LoadResult(
        val manifest: ChapterArtifactManifest,
        /** True when this load performed the initial legacy migration and published it. */
        val migratedFromLegacy: Boolean,
        /** Retained compatibility field; one-way rescue never performs resync. */
        val resyncedFromLegacy: Boolean,
    )

    /** Outcome of a durable-failure record attempt; only [Stored] is durable. */
    sealed interface RecordOutcome {
        data class Stored(val manifest: ChapterArtifactManifest) : RecordOutcome
        data class NotStored(val reason: String) : RecordOutcome
    }

    /**
     * Loads the manifest and performs one serialized legacy rescue when needed.
     * Future-schema documents (primary or backup) are
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
            return legacyRescue.refuseFutureDocument("primary", primary)
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
            // A backup can be parsed successfully even when the SAF rename
            // that promotes it to the primary document fails. Keep that
            // recoverable copy read-only until a valid primary is durable;
            // retention and recovery publications must not delete or replace
            // the only known-good manifest.
            if (readManifestDocument(layout.manifestFileName) == null) {
                logcat(LogPriority.WARN) {
                    "TachiyomiAT artifact manifest primary recovery incomplete; backup preserved: " +
                        "chapter=${layout.chapterKey}"
                }
                return LoadResult(existing, migratedFromLegacy = false, resyncedFromLegacy = false)
            }
            // Load is a reconciliation boundary: after a crash or cancelled
            // stream, remove only unreachable managed artifacts. Legacy
            // companion images remain outside the managed tree and are owned
            // by the reader stream registry.
            reconcileRetention(existing)
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
            // A legacy manifest is only a rescue staging state. Never keep an
            // identity-resync loop alive: map the currently readable source
            // once, materialize its complete graph, and switch authority only
            // after every referenced document validates.
            return legacyRescue.rescueLegacy(existing, legacy)
        }

        val migrated = stampChapterKey(LegacyArtifactMigration.migrateChapter(legacy)).copy(glossary = null)
        val published = publishManifestInternal(migrated)
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
        reconcileRetention(migrated)
        if (!published) return LoadResult(migrated, migratedFromLegacy = false, resyncedFromLegacy = false)
        return legacyRescue.rescueLegacy(migrated, legacy)
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
        val compatibilitySnapshot = pageSnapshot.detachedCopy().let { snapshot ->
            if (committed.displayBase.kind == DisplayBaseKind.CLEANED_IMAGE &&
                committed.displayBase.validated &&
                committed.displayBase.fileName == snapshot.cleanedImageName
            ) {
                snapshot
            } else {
                snapshot.copy(cleanedImageName = null)
            }
        }
        if (!documents.publishJson(fileName, compatibilitySnapshot)) {
            return TransactionOutcome.Rejected("committed page snapshot publication failed: pageKey=$pageKey")
        }
        val updated = manifest.copy(
            pages = manifest.pages + (
                pageKey to page.copy(
                    committed = committed.copy(pageSnapshotFileName = fileName),
                    // Attaching the immutable compatibility snapshot does not
                    // mutate the page itself. Keep the page version stable so
                    // a candidate opened from the rescued LEGACY record keeps
                    // the same optimistic-concurrency precondition.
                    pageVersion = page.pageVersion,
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
        sourceIdentity: SourceIdentity? = null,
        nowEpochMs: Long = System.currentTimeMillis(),
        durableFailure: DurableFailureMetadata? = null,
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
        val failureKey = durableFailure?.let { "${it.pageKey}:${it.stage.name}" }
        val failureRecord = durableFailure?.let { failure ->
            StageArtifactRecord(
                status = failure.status,
                fingerprint = expectedDependencyFingerprint,
                origin = origin,
                generationId = generationId,
                updatedAtEpochMs = nowEpochMs,
            )
        }
        val updatedPage = page.copy(
            source = sourceIdentity ?: page.source,
            candidate = page.candidate.copy(pageSnapshotFileName = fileName),
            pageVersion = page.pageVersion + 1,
        ).let { candidatePage ->
            if (durableFailure == null) candidatePage else candidatePage.withStage(durableFailure.stage, failureRecord)
        }
        val updated = manifest.copy(
            pages = manifest.pages + (
                pageKey to updatedPage
                ),
            durableFailures = if (failureKey == null) {
                manifest.durableFailures
            } else {
                manifest.durableFailures + (failureKey to durableFailure)
            },
            updatedAtEpochMs = nowEpochMs,
        )
        if (!publishManifestInternal(updated)) {
            return TransactionOutcome.Rejected("manifest publication failed; candidate snapshot pointer unchanged")
        }
        return TransactionOutcome.Committed(updated, generationId)
    }

    /**
     * Candidate snapshot plus retry/terminal metadata in one manifest
     * publication. The immutable candidate sidecar is written first, then a
     * single manifest pointer update makes both pieces visible together.
     */
    @Synchronized
    fun persistLiveCandidateAndFailure(
        manifest: ChapterArtifactManifest,
        pageKey: String,
        generationId: String,
        expectedPageVersion: Long,
        expectedDependencyFingerprint: String,
        pageSnapshot: PageTranslation,
        origin: ArtifactOrigin,
        failure: DurableFailureMetadata,
        sourceIdentity: SourceIdentity? = null,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): TransactionOutcome = persistLiveCandidate(
        manifest = manifest,
        pageKey = pageKey,
        generationId = generationId,
        expectedPageVersion = expectedPageVersion,
        expectedDependencyFingerprint = expectedDependencyFingerprint,
        pageSnapshot = pageSnapshot,
        origin = origin,
        sourceIdentity = sourceIdentity,
        nowEpochMs = nowEpochMs,
        durableFailure = failure,
    )

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
        sourceIdentity: SourceIdentity? = null,
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
        val resolvedPage = page.copy(source = sourceIdentity ?: page.source)
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
            if (!displayBaseIsValid(layout.legacyCompanionImageFile(cleanedName), resolvedPage.source)) {
                return TransactionOutcome.Rejected(
                    "cleaned display base file missing, corrupt, or wrong-sized: pageKey=$pageKey file=$cleanedName",
                )
            }
        }
        val candidateFile = candidate.pageSnapshotFileName
            ?: layout.candidatePageSnapshotFile(pageKey, generationId)
        val candidateMatches = documents.readValidated<PageTranslation>(candidateFile)?.let {
            it == pageSnapshot
        } == true
        if (!candidateMatches && !documents.publishJson(candidateFile, pageSnapshot.detachedCopy())) {
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
                sourceIdentity = resolvedPage.source,
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
        val promotedTranslation = resolvedPage.translation?.copy(
            status = if (pageSnapshot.isTextlessTerminal) {
                ArtifactStageStatus.TEXTLESS
            } else {
                ArtifactStageStatus.READY
            },
            fingerprint = StageFingerprints.pageSnapshot(pageSnapshot),
            origin = origin,
            generationId = null,
            artifactFileName = committedFile,
            updatedAtEpochMs = nowEpochMs,
        ) ?: StageArtifactRecord(
            status = if (pageSnapshot.isTextlessTerminal) {
                ArtifactStageStatus.TEXTLESS
            } else {
                ArtifactStageStatus.READY
            },
            fingerprint = StageFingerprints.pageSnapshot(pageSnapshot),
            origin = origin,
            artifactFileName = committedFile,
            updatedAtEpochMs = nowEpochMs,
        )
        val updated = manifest.copy(
            pages = manifest.pages + (
                pageKey to page.copy(
                    source = resolvedPage.source,
                    committed = committed,
                    previousCommitted = page.committed,
                    candidate = null,
                    displayState = if (pageSnapshot.isTextlessTerminal) {
                        PageDisplayState.TEXTLESS_COMPLETE
                    } else {
                        PageDisplayState.DISPLAY_READY
                    },
                    translation = promotedTranslation,
                    pageVersion = page.pageVersion + 1,
                )
                ),
            activeCandidateGenerationIds = manifest.activeCandidateGenerationIds - generationId,
            durableFailures = manifest.durableFailures - "$pageKey:${ArtifactStage.TRANSLATION.name}",
            updatedAtEpochMs = nowEpochMs,
        )
        if (!publishManifestInternal(updated)) {
            return TransactionOutcome.Rejected("manifest publication failed; committed pointer unchanged")
        }
        return TransactionOutcome.Committed(updated, generationId)
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
            durableFailures = manifest.durableFailures.filterValues { it.pageKey != pageKey },
            updatedAtEpochMs = nowEpochMs,
        )
        if (!publishManifestInternal(updated)) {
            return TransactionOutcome.Rejected("manifest publication failed; reset pointers remain authoritative")
        }
        return TransactionOutcome.Committed(updated, candidateGenerationId)
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
            durableFailures = manifest.durableFailures.filterValues { it.pageKey != pageKey },
            updatedAtEpochMs = nowEpochMs,
        )
        if (!publishManifestInternal(updated)) {
            return TransactionOutcome.Rejected("manifest publication failed; page remains authoritative: pageKey=$pageKey")
        }
        return TransactionOutcome.Committed(updated)
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
                existing.dependencyFingerprint == dependencyFingerprint -> {
                BatchTranslationDiagnostics.reuse(
                    stage = BatchDiagnosticStage.ARTIFACT,
                    pageKey = pageKey,
                    reason = BatchDiagnosticReason.CANDIDATE_ACTIVE,
                    fingerprint = dependencyFingerprint,
                )
                existing.generationId
            }
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
        val durableFailures = manifest.durableFailures.toMutableMap()
        val pages = manifest.pages.mapValues { (pageKey, page) ->
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
                    durableFailures["$pageKey:${stage.name}"] = DurableFailureMetadata(
                        pageKey = pageKey,
                        stage = stage,
                        status = ArtifactStageStatus.FAILED_RETRYABLE,
                        category = FailureCategory.LEGACY_UNKNOWN,
                        retryCount = 0,
                        lastFailureMessage = "process interrupted",
                        lastFailedAtEpochMs = now,
                        nextEligibleRetryAtEpochMs = now,
                        failureFingerprint = record.fingerprint,
                    )
                }
            }
            if (updated !== page) updated.copy(pageVersion = page.pageVersion + 1) else updated
        }
        if (!changed) return manifest
        val recovered = manifest.copy(
            pages = pages,
            durableFailures = durableFailures,
            updatedAtEpochMs = now,
        )
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

    internal fun stagePayloadIsValid(record: StageArtifactRecord?): Boolean {
        val fileName = record?.artifactFileName ?: return false
        return io.exists(fileName) &&
            io.length(fileName) > 0L &&
            documents.readValidated<JsonObject>(fileName) != null
    }

    internal fun displayBaseIsValid(fileName: String, source: SourceIdentity?): Boolean {
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
    fun reconcileRetention(manifest: ChapterArtifactManifest): RetentionResult =
        retentionSweep.reconcileRetention(manifest)

    internal fun readManifestDocument(name: String): ChapterArtifactManifest? {
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

    // T909 Phase 2: the legacy rescue/preservation/health machine moved to
    // LegacyArtifactRescue.kt; the synchronized entry points stay here.

    /** Retries INTENT preservation for an already ARTIFACTS-authoritative chapter. */
    @Synchronized
    fun reconcileLegacyPreservation(manifest: ChapterArtifactManifest): ChapterArtifactManifest =
        legacyRescue.reconcileLegacyPreservation(manifest)

    /**
     * Verifies one already-open chapter before making preserved legacy inputs
     * eligible for deletion. This is intentionally chapter-scoped and
     * synchronized with every manifest/source operation; it is never a
     * library-startup scan.
     */
    @Synchronized
    fun verifyLegacyArtifactHealth(
        manifest: ChapterArtifactManifest,
        currentVersionCode: Long,
        hasActiveLease: Boolean = false,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): LegacyArtifactHealthResult =
        legacyRescue.verifyLegacyArtifactHealth(manifest, currentVersionCode, hasActiveLease, nowEpochMs)

    private fun futureBackupPresent(): Boolean =
        readManifestDocument(backupName())?.schemaVersion?.let { it > ChapterArtifactManifest.SCHEMA_VERSION }
            ?: false

    internal fun publishManifestInternal(manifest: ChapterArtifactManifest): Boolean {
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

    internal fun stampChapterKey(manifest: ChapterArtifactManifest): ChapterArtifactManifest =
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

    internal fun backupName(): String = AtomicChapterDocuments.backupNameFor(layout.manifestFileName)
}

/** Stage sidecar lookup for one page record; shared with the legacy rescue machine. */
internal fun PageArtifactRecord.stage(stage: ArtifactStage): StageArtifactRecord? = when (stage) {
    ArtifactStage.DETECTION -> detection
    ArtifactStage.OCR -> ocr
    ArtifactStage.INPAINT -> inpaint
    ArtifactStage.TRANSLATION -> translation
    ArtifactStage.LAYOUT -> layout
}
