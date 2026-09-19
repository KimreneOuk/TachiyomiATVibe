package eu.kanade.translation.artifact

import eu.kanade.translation.ReaderEntryTrace
import eu.kanade.translation.pipeline.batch.BatchDiagnosticReason
import eu.kanade.translation.pipeline.batch.BatchDiagnosticStage
import eu.kanade.translation.pipeline.batch.BatchTranslationDiagnostics
import eu.kanade.translation.model.PageDisplayState
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.detachedCopy
import eu.kanade.translation.model.isTextlessTerminal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromStream
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * TachiyomiAT: owns the chapter artifact manifest and the immutable artifact
 * tree for one chapter (lifecycle contract §15).
 *
 * The store owns the chapter artifact manifest and immutable sidecar tree.
 * Candidate generation lifecycle, preconditioned stage commits, atomic
 * committed-pointer promotion, cancel/failure semantics, crash recovery, and
 * bounded retention all operate on artifact documents only. Flat translation
 * files from pre-artifact builds are intentionally not read or migrated.
 */
/**
 * T924-TX-03 close-vs-rebase decision for [ChapterArtifactStore.checkpointOcr].
 *
 * - [CLOSE] (default): close the active BATCH candidate (CANCELLED generation
 *   record, candidate pointer cleared, checkpoint pointer installed in ONE
 *   manifest publication). Used whenever the page's future ownership is not
 *   deterministically this Batch run.
 * - [REBASE]: close the BATCH generation G and open a named successor
 *   generation G′ (origin BATCH, dependency fingerprint = the checkpoint's
 *   content fingerprint) in the SAME manifest publication. Used only when the
 *   same run continues to a later phase for the page and will retain or
 *   deterministically re-acquire the lease.
 *
 * An "origin-neutral open candidate" is not representable: `ArtifactOrigin`
 * has exactly two durable values (T924-TX-03), so neutrality lives in the
 * checkpoint sidecar, never in the candidate.
 */
enum class OcrCheckpointMode { CLOSE, REBASE }

/**
 * T924 WP9 (additive): outcome of reading a generic sidecar document through
 * its manifest pointer — same semantics as [RunRecordRead]/[OcrCheckpointRead],
 * generalized over the document type for the persisted-layout track
 * (`layoutPlans` / `colorPreparations`).
 */
sealed interface SidecarRead<out T : Any> {
    /** Parsed, schema-supported, and semantically valid. */
    data class Usable<T : Any>(val document: T) : SidecarRead<T>

    /**
     * T924-SC-13: a newer schema owns the semantics — unusable here, bytes
     * preserved untouched, never deleted, quarantined, or overwritten.
     */
    data class UnsupportedVersion(val schemaVersion: Int) : SidecarRead<Nothing>

    /** Missing, malformed pointer, corrupt (quarantined), or semantically invalid. */
    data object Absent : SidecarRead<Nothing>
}

class ChapterArtifactStore(
    private val documents: AtomicChapterDocuments,
    internal val layout: ChapterArtifactLayout,
    @Suppress("UNUSED_PARAMETER")
    private val displayBaseProbe: CleanedImageProbe = BitmapFactoryCleanedImageProbe,
) {
    private val io: ChapterDocumentIo get() = documents.rawIo()

    /** Bounded retention sweep (T909 Phase 2b). */
    private val retentionSweep = ArtifactRetention(io, layout)

    data class LoadResult(
        val manifest: ChapterArtifactManifest,
    )

    /** Outcome of a durable-failure record attempt; only [Stored] is durable. */
    sealed interface RecordOutcome {
        data class Stored(val manifest: ChapterArtifactManifest) : RecordOutcome
        data class NotStored(val reason: String) : RecordOutcome
    }

    @Synchronized
    fun load(): LoadResult {
        val readManifestStage = ReaderEntryTrace.begin("store.readManifest", null)
        val primary = readManifestDocument(layout.manifestFileName)
        val backup = readManifestDocument(backupName())
        readManifestStage.end()

        if (primary?.schemaVersion != null && primary.schemaVersion > ChapterArtifactManifest.SCHEMA_VERSION) {
            return LoadResult(primary)
        }
        if (backup?.schemaVersion != null && backup.schemaVersion > ChapterArtifactManifest.SCHEMA_VERSION) {
            logcat(LogPriority.WARN) {
                "TachiyomiAT artifact manifest backup has unsupported schema; preserved untouched: " +
                    "chapter=${layout.chapterKey} schema=${backup.schemaVersion}"
            }
            return if (primary != null) {
                LoadResult(primary)
            } else {
                LoadResult(backup)
            }
        }

        val recoverBackupStage = ReaderEntryTrace.begin("store.recoverBackup", null)
        val existing = primary
            ?: recoverPrimaryFromBackupOrNull(backup)
        recoverBackupStage.end()
        if (existing != null) {
            if (primary == null && readManifestDocument(layout.manifestFileName) == null) {
                logcat(LogPriority.WARN) {
                    "TachiyomiAT artifact manifest primary recovery incomplete; backup preserved: " +
                        "chapter=${layout.chapterKey}"
                }
                return LoadResult(existing)
            }
            // T921: the recursive sweep must not run for large chapters — it
            // holds this store's monitor and serialized reader entry behind
            // the lock for its full crawl duration (measured 56.7s of a 57.1s
            // open). Large-chapter cleanup is owned by the deferred
            // maintenance path (follow-up: event-driven retention per T920
            // Recommendation 1). Small chapters keep the synchronous sweep:
            // their managed tree is a handful of SAF listings, and
            // crash/cancel garbage must not accumulate between passes.
            if (existing.pages.size <= 8) {
                val retentionStage = ReaderEntryTrace.begin("store.retention", null)
                reconcileRetention(existing)
                retentionStage.end()
            }
            val hadInterruptedStage = existing.pages.values.any { page ->
                ArtifactStage.entries.any { stage -> page.stage(stage)?.status == ArtifactStageStatus.RUNNING }
            }
            val recoverInterruptedStage = ReaderEntryTrace.begin("store.recoverInterrupted", null)
            val recovered = recoverInterruptedStages(existing)
            recoverInterruptedStage.end()
            if (!hadInterruptedStage || recovered != existing) {
                io.delete(backupName())
            }
            return LoadResult(recovered)
        }

        val created = ChapterArtifactManifest(
            chapterKey = layout.chapterKey,
            updatedAtEpochMs = System.currentTimeMillis(),
        )
        val published = publishManifestInternal(created)
        if (!published) {
            logcat(LogPriority.WARN) {
                "TachiyomiAT artifact manifest creation failed: chapter=${layout.chapterKey}"
            }
        }
        return LoadResult(created)
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
     * T917 Phase 3 (D9): reads the chapter's durable attempt-ledger document.
     * A future-schema document is returned read-only and never overwritten —
     * the same preservation rule as the manifest.
     */
    fun readAttemptLedger(): ChapterAttemptLedgerDocument? =
        documents.readValidated<ChapterAttemptLedgerDocument>(layout.attemptLedgerFileName) { ledger ->
            ledger.schemaVersion <= ChapterAttemptLedgerDocument.SCHEMA_VERSION &&
                ledger.kind == ChapterAttemptLedgerDocument.KIND_ATTEMPT_LEDGER
        }

    /** Crash-safe attempt-ledger publication: temp, validate, rename. */
    @Synchronized
    fun publishAttemptLedger(document: ChapterAttemptLedgerDocument): Boolean =
        documents.publishJson(layout.attemptLedgerFileName, document)

    // ------------------------------------------------------------------
    // T924 Stage 1 (T924-SC-19/20/22): versioned sidecar publication.
    //
    // The ONLY publication mechanism for the new sidecar kinds is the
    // existing AtomicChapterDocuments publish path, and the ONLY
    // manifest-update mechanism is publishManifestInternal. Sidecars are
    // published FIRST (content-addressed names, T924-SC-21), and one atomic
    // manifest publication installs their pointers SECOND — a crash between
    // the two leaves at most an orphan sidecar, never a dangling pointer.
    // On any precondition or publication failure the prior manifest stays
    // authoritative (T924-SC-22); orphan sidecars are reclaimed exclusively
    // by retention store-reachability sweeps.
    // ------------------------------------------------------------------

    /** Outcome of reading a versioned sidecar through a manifest pointer. */
    sealed interface RunRecordRead {
        /** Semantically valid at a supported schema version. */
        data class Usable(val record: ChapterRunRecord) : RunRecordRead

        /**
         * T924-SC-13: a newer schema owns the semantics — unusable for
         * planning (artifact = ABSENT for decisions), bytes preserved
         * untouched, never deleted or overwritten by this version.
         */
        data class UnsupportedVersion(val schemaVersion: Int) : RunRecordRead

        /**
         * T924-SC-17: missing, corrupt (parse failure, kind mismatch, bound
         * violation — quarantined as `.corrupt` by the document layer), or
         * otherwise invalid. Treated as ABSENT for planning; corrupt bytes
         * stay quarantined while a pointer references them.
         */
        data object Absent : RunRecordRead
    }

    /** Reads the pointed run record with unknown-version preservation (T924-SC-12/13). */
    fun readRunRecord(pointer: SidecarPointer): RunRecordRead {
        if (!pointer.isWellFormed()) return RunRecordRead.Absent
        val bytes = io.read(pointer.fileName) ?: return RunRecordRead.Absent
        val record = runCatching {
            documents.json.decodeFromStream<ChapterRunRecord>(bytes.inputStream())
        }.getOrNull() ?: run {
            documents.quarantineCorrupt(pointer.fileName)
            return RunRecordRead.Absent
        }
        if (record.schemaVersion > ChapterRunRecord.SCHEMA_VERSION) {
            return RunRecordRead.UnsupportedVersion(record.schemaVersion)
        }
        if (record.kind != ChapterRunRecord.KIND || !record.isSemanticallyValid) {
            documents.quarantineCorrupt(pointer.fileName)
            return RunRecordRead.Absent
        }
        return RunRecordRead.Usable(record)
    }

    /**
     * T924-SC-20/SC-22 run-record publication: validates the record, publishes
     * the immutable sidecar into the content-addressed `runs/` directory
     * FIRST, then installs the [ChapterArtifactManifest.activeRun] pointer in
     * ONE atomic manifest publication. Any failure leaves the prior manifest
     * authoritative and at most an orphan sidecar behind.
     *
     * T924 LI-4: a stale-manifest CAS rejection (the >8-page open path's
     * background health verify republishing after the façade cached its copy)
     * triggers ONE retry against the freshly re-read durable manifest — the
     * first-publication seam the flagged Batch lane hits must not surface a
     * spurious rejection on a healthy chapter. Every other rejection reason is
     * returned as-is.
     */
    @Synchronized
    fun publishActiveRun(
        manifest: ChapterArtifactManifest,
        record: ChapterRunRecord,
        contentFingerprint: String,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): TransactionOutcome = retryOnStaleManifest(
        firstAttempt = publishActiveRunOnce(manifest, record, contentFingerprint, nowEpochMs),
        callerManifest = manifest,
        seam = "publishActiveRun",
    ) { fresh -> publishActiveRunOnce(fresh, record, contentFingerprint, nowEpochMs) }

    private fun publishActiveRunOnce(
        manifest: ChapterArtifactManifest,
        record: ChapterRunRecord,
        contentFingerprint: String,
        nowEpochMs: Long,
    ): TransactionOutcome {
        record.validationError()?.let { reason ->
            return TransactionOutcome.Rejected("run record invalid: $reason")
        }
        if (!contentFingerprint.isSha256Hex()) {
            return TransactionOutcome.Rejected("run record content fingerprint is not sha256 hex")
        }
        val fileName = layout.runRecordFile(contentFingerprint)
        return publishSidecarPointers(
            manifest = manifest,
            sidecars = listOf(
                SidecarPublication(fileName, contentFingerprint) {
                    documents.publishJson(fileName, record)
                },
            ),
            updatePointers = { current ->
                current.copy(
                    activeRun = SidecarPointer(
                        fileName = fileName,
                        schemaVersion = ChapterRunRecord.SCHEMA_VERSION,
                        contentFingerprint = contentFingerprint,
                    ),
                )
            },
            nowEpochMs = nowEpochMs,
            commitPoint = CommitPoint.CHAPTER_PHASE_RECORD,
        )
    }


    /**
     * T924 LI-2: retires the [ChapterArtifactManifest.activeRun] pointer in ONE
     * CAS'd manifest publication — [manifest.copy](activeRun = null) guarded by
     * [staleManifestRejection], so a concurrent writer's manifest wins and the
     * caller retries with fresh state. Retiring an already-null pointer is an
     * idempotent no-op [TransactionOutcome.Committed] (no publication). The
     * run-record SIDECAR FILE is left in place: retention owns deletion of the
     * now-orphaned record — [ArtifactRetention.reachablePaths] retains exactly
     * the manifest-pointed `activeRun` sidecar, so once the pointer is gone the
     * next reachability sweep reclaims the orphaned record file.
     *
     * Reset semantics (T924 LI-2): a user reset means the recorded run must
     * never short-circuit a future dispatch — the COMPLETE fast path
     * ([eu.kanade.translation.pipeline.batch.ChapterProfileBatchCoordinator.resumeFinalizeOrComplete])
     * keys on this pointer, so clearing it forces the next run to start fresh
     * instead of returning a zero-work finished outcome over demoted displays.
     *
     * T924 LI-4 / T934: a stale-manifest CAS rejection (the >8-page open
     * path's background health verify republishing after the caller cached its
     * copy) triggers ONE retry against the freshly re-read durable manifest —
     * the batch resume teardown must not surface a spurious rejection on a
     * healthy chapter. Every other rejection reason is returned as-is.
     */
    @Synchronized
    fun retireActiveRun(
        manifest: ChapterArtifactManifest,
        reason: String,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): TransactionOutcome = retryOnStaleManifest(
        firstAttempt = retireActiveRunOnce(manifest, reason, nowEpochMs),
        callerManifest = manifest,
        seam = "retireActiveRun",
    ) { fresh -> retireActiveRunOnce(fresh, reason, nowEpochMs) }

    private fun retireActiveRunOnce(
        manifest: ChapterArtifactManifest,
        reason: String,
        nowEpochMs: Long,
    ): TransactionOutcome {
        staleManifestRejection(manifest)?.let { return TransactionOutcome.Rejected(it) }
        if (manifest.activeRun == null) {
            return TransactionOutcome.Committed(manifest, commitPoint = CommitPoint.CHAPTER_PHASE_RECORD)
        }
        val updated = manifest.copy(activeRun = null, updatedAtEpochMs = nowEpochMs)
        if (!publishManifestInternal(updated)) {
            logcat(LogPriority.WARN) {
                "TachiyomiAT artifact active run retirement publish failed; prior manifest retained: " +
                    "chapter=${layout.chapterKey} reason=$reason"
            }
            return TransactionOutcome.Rejected("manifest publication failed; active run pointer unchanged")
        }
        return TransactionOutcome.Committed(updated, commitPoint = CommitPoint.CHAPTER_PHASE_RECORD)
    }

    // ------------------------------------------------------------------
    // T924 Stage 1 Phase 2a: the checkpointOcr transaction (T924-TX-01..).
    // Contract: Plan/active/2026-09-05_T924_chunk-sizing-and-fast-feedback/
    // stage0/contracts-state-transactions.md §2. The transaction owns ONLY
    // the durable publication; the caller keeps the page lease until after
    // a Committed outcome and releases it as a separate, strictly-later
    // step (T924-TX-06). Store-generation/pageVersion/leaseToken fencing
    // (TX-02 inputs 1-3) belongs to the ChapterTranslationStore façade;
    // this transaction compares the manifest-level identity (inputs 4-6)
    // against the durable manifest under the whole-manifest CAS.
    // ------------------------------------------------------------------

    /**
     * T924-TX-01: atomically publishes the origin-neutral
     * [PageOcrCheckpoint] sidecar plus its immutable OCR page snapshot, and
     * closes or rebases the active BATCH candidate — all in ONE manifest
     * publication (T924-SC-20). Three branches:
     *
     * - **Standard CLOSE/REBASE** ([checkpoint.producerGenerationId] != null,
     *   active BATCH candidate): compares candidateGenerationId,
     *   artifact pageVersion, and dependency fingerprint against the
     *   durable manifest (TX-02 inputs 4-6, no grace clause — T924-TX-02.1),
     *   then CLOSE clears the candidate (CANCELLED record) or REBASE opens a
     *   successor generation whose dependency fingerprint equals the
     *   checkpoint content fingerprint (T924-TX-03). The prior committed
     *   display pointer is never touched (T924-TX-07).
     * - **Adopt-committed** ([checkpoint.producerGenerationId] == null,
     *   no active candidate, TX-03.1): requires a committed bundle whose
     *   OCR content fingerprint equals the checkpoint's; publishes the
     *   checkpoint sidecar + pointer ONLY. A fingerprint mismatch is
     *   REJECTED as content drift — Batch must re-plan, not adopt.
     *
     * On any precondition or publication failure the prior manifest stays
     * authoritative (TX-11 BX) and at most an orphan sidecar exists (B1-B2).
     *
     * T924 LI-4: a stale-manifest CAS rejection (the >8-page open path's
     * background health verify republishing after the façade cached its copy)
     * triggers ONE retry against the freshly re-read durable manifest — the
     * first-publication seam the flagged Batch lane hits must not surface a
     * spurious CHECKPOINT_REJECTED preflight failure on a healthy chapter. The
     * whole transaction (identity checks included) re-runs against the FRESH
     * manifest, so genuine drift still rejects — with a non-stale reason, on
     * the retry attempt. Every other rejection reason is returned as-is.
     */
    @Synchronized
    fun checkpointOcr(
        manifest: ChapterArtifactManifest,
        pageKey: String,
        expectedPageVersion: Long,
        expectedDependencyFingerprint: String?,
        ocrSnapshot: PageTranslation,
        checkpoint: PageOcrCheckpoint,
        mode: OcrCheckpointMode = OcrCheckpointMode.CLOSE,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): TransactionOutcome = retryOnStaleManifest(
        firstAttempt = checkpointOcrOnce(
            manifest,
            pageKey,
            expectedPageVersion,
            expectedDependencyFingerprint,
            ocrSnapshot,
            checkpoint,
            mode,
            nowEpochMs,
        ),
        callerManifest = manifest,
        seam = "checkpointOcr",
    ) { fresh ->
        checkpointOcrOnce(
            fresh,
            pageKey,
            expectedPageVersion,
            expectedDependencyFingerprint,
            ocrSnapshot,
            checkpoint,
            mode,
            nowEpochMs,
        )
    }

    private fun checkpointOcrOnce(
        manifest: ChapterArtifactManifest,
        pageKey: String,
        expectedPageVersion: Long,
        expectedDependencyFingerprint: String?,
        ocrSnapshot: PageTranslation,
        checkpoint: PageOcrCheckpoint,
        mode: OcrCheckpointMode = OcrCheckpointMode.CLOSE,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): TransactionOutcome {
        staleManifestRejection(manifest)?.let { return TransactionOutcome.Rejected(it) }
        checkpoint.validationError()?.let { reason ->
            return TransactionOutcome.Rejected("checkpoint invalid: $reason")
        }
        if (checkpoint.pageKey != pageKey) {
            return TransactionOutcome.Rejected(
                "checkpoint pageKey mismatch: checkpoint=${checkpoint.pageKey} transaction=$pageKey",
            )
        }
        val page = manifest.pages[pageKey]
            ?: return TransactionOutcome.Rejected("page missing: pageKey=$pageKey")
        if (page.pageVersion != expectedPageVersion) {
            return TransactionOutcome.Rejected(
                "stale page version: pageKey=$pageKey expected=$expectedPageVersion actual=${page.pageVersion}",
            )
        }
        val snapshotFileName = checkpoint.ocrPageSnapshotPointer.fileName
        if (checkpoint.ocrPageSnapshotPointer.contentFingerprint != StageFingerprints.pageSnapshot(ocrSnapshot)) {
            return TransactionOutcome.Rejected("ocr snapshot pointer fingerprint mismatch: pageKey=$pageKey")
        }
        val candidate = page.candidate
        val checkpointFileName = layout.ocrCheckpointFile(pageKey, checkpoint.ocrContentFingerprint)
        val ocrRecord = StageArtifactRecord(
            status = if (ocrSnapshot.isTextlessTerminal) ArtifactStageStatus.TEXTLESS else ArtifactStageStatus.READY,
            fingerprint = checkpoint.ocrFingerprint,
            origin = checkpoint.producedByOrigin,
            artifactFileName = snapshotFileName,
            updatedAtEpochMs = nowEpochMs,
        )
        val ocrSnapshotCopy = ocrSnapshot.detachedCopy()
        val sidecars = mutableListOf(
            SidecarPublication(snapshotFileName, checkpoint.ocrPageSnapshotPointer.contentFingerprint) {
                documents.publishJson(snapshotFileName, ocrSnapshotCopy)
            },
            SidecarPublication(checkpointFileName, checkpoint.ocrContentFingerprint) {
                documents.publishJson(checkpointFileName, checkpoint)
            },
        )
        val producerGenerationId = checkpoint.producerGenerationId
        var successorGenerationId: String? = null
        var sweepAfterCommit = false
        val updatedPage: PageArtifactRecord
        val updatedActiveCandidateIds: Set<String>
        if (producerGenerationId != null) {
            if (candidate == null) {
                return TransactionOutcome.Rejected("candidate missing: pageKey=$pageKey")
            }
            if (candidate.generationId != producerGenerationId) {
                return TransactionOutcome.Rejected(
                    "candidate mismatch: pageKey=$pageKey expected=$producerGenerationId actual=${candidate.generationId}",
                )
            }
            if (candidate.origin != ArtifactOrigin.BATCH) {
                return TransactionOutcome.Rejected(
                    "candidate provenance mismatch: pageKey=$pageKey expected=BATCH actual=${candidate.origin}",
                )
            }
            // T924-TX-02.1 (fail closed): the store-level grace clause that
            // disarms the dependency-fingerprint check without a candidate
            // (patchPage) must never be copied here — C2.
            if (expectedDependencyFingerprint == null) {
                return TransactionOutcome.Rejected("dependency fingerprint required for checkpoint: pageKey=$pageKey")
            }
            if (candidate.dependencyFingerprint != expectedDependencyFingerprint) {
                return TransactionOutcome.Rejected("dependency fingerprint changed: pageKey=$pageKey")
            }
            val cancelledName = layout.generationFile(candidate.generationId)
            val cancelledRecord = GenerationRecord(
                generationId = candidate.generationId,
                pageKey = pageKey,
                origin = candidate.origin,
                lifecycle = GenerationLifecycle.CANCELLED,
                createdAtEpochMs = candidate.createdAtEpochMs,
                closedAtEpochMs = nowEpochMs,
            )
            sidecars += SidecarPublication(cancelledName, checkpoint.ocrContentFingerprint) {
                documents.publishJson(cancelledName, cancelledRecord)
            }
            when (mode) {
                OcrCheckpointMode.CLOSE -> {
                    fun isCandidateOwned(record: StageArtifactRecord?): Boolean =
                        record != null && record.generationId == candidate.generationId
                    updatedPage = page.copy(
                        detection = page.detection.takeUnless(::isCandidateOwned),
                        // The OCR stage record is re-owned by the manifest:
                        // it now points at the checkpoint-published snapshot
                        // sidecar, so retention keeps the snapshot reachable
                        // through the page record after the candidate clears.
                        ocr = ocrRecord,
                        inpaint = page.inpaint.takeUnless(::isCandidateOwned),
                        translation = page.translation.takeUnless(::isCandidateOwned),
                        layout = page.layout.takeUnless(::isCandidateOwned),
                        candidate = null,
                        displayState = when {
                            candidate.priorDisplayState == PageDisplayState.TEXTLESS_COMPLETE ->
                                PageDisplayState.TEXTLESS_COMPLETE
                            page.displayState == PageDisplayState.TEXTLESS_COMPLETE ->
                                PageDisplayState.TEXTLESS_COMPLETE
                            page.committed != null -> PageDisplayState.DISPLAY_READY
                            else -> PageDisplayState.ORIGINAL_ONLY
                        },
                        pageVersion = page.pageVersion + 1,
                    )
                    updatedActiveCandidateIds = manifest.activeCandidateGenerationIds - candidate.generationId
                    sweepAfterCommit = true
                }
                OcrCheckpointMode.REBASE -> {
                    val successorId = newGenerationId(pageKey, nowEpochMs)
                    successorGenerationId = successorId
                    val successorName = layout.generationFile(successorId)
                    val successorRecord = GenerationRecord(
                        generationId = successorId,
                        pageKey = pageKey,
                        origin = ArtifactOrigin.BATCH,
                        lifecycle = GenerationLifecycle.ACTIVE,
                        createdAtEpochMs = nowEpochMs,
                    )
                    sidecars += SidecarPublication(successorName, checkpoint.ocrContentFingerprint) {
                        documents.publishJson(successorName, successorRecord)
                    }
                    updatedPage = page.copy(
                        ocr = ocrRecord,
                        candidate = CandidateGenerationMetadata(
                            generationId = successorId,
                            origin = ArtifactOrigin.BATCH,
                            // T924-TX-03(c): the successor's first write
                            // validates against the checkpoint content.
                            dependencyFingerprint = checkpoint.ocrContentFingerprint,
                            createdAtEpochMs = nowEpochMs,
                            priorDisplayState = candidate.priorDisplayState ?: page.displayState,
                        ),
                        displayState = if (page.committed != null) {
                            PageDisplayState.REFRESHING_WITH_COMMITTED_RESULT
                        } else {
                            PageDisplayState.CANDIDATE_RUNNING
                        },
                        pageVersion = page.pageVersion + 1,
                    )
                    updatedActiveCandidateIds =
                        (manifest.activeCandidateGenerationIds - candidate.generationId) + successorId
                    sweepAfterCommit = true
                }
            }
        } else {
            // T924-TX-03.1 adopt-committed: no active candidate; a committed
            // bundle exists whose OCR content fingerprint equals the input.
            // Publish the checkpoint sidecar + pointer ONLY — the committed
            // display pointer is untouched by construction (T924-TX-07).
            if (candidate != null) {
                return TransactionOutcome.Rejected(
                    "active candidate present; standard checkpoint branch required: pageKey=$pageKey",
                )
            }
            val committed = page.committed
            if (committed == null) {
                // T924 Phase 4 Wave B (blank-page CLOSE gap): a genuinely
                // blank page (OCR READY, ZERO blocks) never opens an artifact
                // candidate — `shouldPersistUpdate` treats the empty-block
                // write as transient — so the preflight CLOSE side arrives
                // with NEITHER a candidate NOR a committed bundle. The OCR
                // snapshot sidecar published by THIS transaction is the
                // page's complete OCR content, so adopting without a
                // committed bundle is exactly as durable as the candidate
                // CLOSE above (and reachable from BOTH flagged lanes — the
                // preflight is shared). Every content-bearing page still
                // requires the committed anchor: fail closed.
                val blankOcr = ocrSnapshot.ocrStatus == StageStatus.READY &&
                    ocrSnapshot.blocks.isEmpty()
                if (!blankOcr) {
                    return TransactionOutcome.Rejected(
                        "committed bundle missing for checkpoint adoption: pageKey=$pageKey",
                    )
                }
            } else {
                val committedSnapshotName = committed.pageSnapshotFileName
                    ?: return TransactionOutcome.Rejected("committed bundle snapshot missing: pageKey=$pageKey")
                val committedSnapshot = documents.readValidated<PageTranslation>(committedSnapshotName)
                    ?: return TransactionOutcome.Rejected("committed bundle snapshot unreadable: pageKey=$pageKey")
                val committedFingerprint =
                    committedOcrContentFingerprint(committedSnapshot, page.naturalPageIndex, checkpoint)
                if (committedFingerprint != checkpoint.ocrContentFingerprint) {
                    // Content drift: the durable OCR differs from what the reader
                    // committed under — Batch must re-plan the page, not adopt it.
                    return TransactionOutcome.Rejected("committed OCR content drift: pageKey=$pageKey")
                }
            }
            updatedPage = page.copy(ocr = ocrRecord, pageVersion = page.pageVersion + 1)
            updatedActiveCandidateIds = manifest.activeCandidateGenerationIds
        }
        val updated = manifest.copy(
            pages = manifest.pages + (pageKey to updatedPage),
            activeCandidateGenerationIds = updatedActiveCandidateIds,
            ocrCheckpoints = manifest.ocrCheckpoints + (
                pageKey to SidecarPointer(
                    fileName = checkpointFileName,
                    schemaVersion = PageOcrCheckpoint.SCHEMA_VERSION,
                    contentFingerprint = checkpoint.ocrContentFingerprint,
                )
                ),
            // T934 R2a.1 (write-time digests): the page's source SHA-256 is
            // recorded AT FIRST ADMISSION in the SAME atomic transaction that
            // installs the checkpoint pointer — never at run end. Only a
            // well-formed (lowercase 64-hex) sha is stamped; anything else
            // leaves the record untouched (the consumer falls back to the
            // dispatch-time observation). Run-start identity and checkpoint
            // reuse then read this map instead of re-reading page bytes.
            sourceShaByPageKey = checkpoint.sourceIdentity.sha256
                ?.takeIf { it.isSha256Hex() }
                ?.let { manifest.sourceShaByPageKey + (pageKey to it) }
                ?: manifest.sourceShaByPageKey,
            // Every checkpoint branch (CLOSE, REBASE, adopt) durably
            // publishes this page's OCR content, so any prior OCR durable-
            // failure entry is stale — clear it on success (mirror of the
            // TRANSLATION clear in promoteLiveCandidate). Without this a
            // recovered page keeps StoreStatusProjector projecting the
            // chapter PAUSED forever (wave-3 review F-W3-1).
            durableFailures = manifest.durableFailures - "$pageKey:${ArtifactStage.OCR.name}",
            updatedAtEpochMs = nowEpochMs,
        )
        val outcome = publishSidecarPointers(
            manifest = manifest,
            sidecars = sidecars,
            updatePointers = { updated },
            nowEpochMs = nowEpochMs,
        )
        val point = if (mode == OcrCheckpointMode.CLOSE) CommitPoint.OCR_CHECKPOINT_CLOSE else null
        return when (outcome) {
            is TransactionOutcome.Committed ->
                if (sweepAfterCommit) {
                    // T930 Slice A4 (Amendment D): the checkpoint runs once per
                    // OCR'd page on the serialized batch lane; a full SAF tree
                    // crawl here costs seconds-to-a-minute per page once the
                    // chapter accumulates sidecars (the same measured cost that
                    // removed the open-path sweep for >8-page chapters). Only
                    // the files THIS transaction unlinked are reclaim candidates;
                    // cross-page orphans stay owned by the chapter-boundary
                    // sweep (reconcileArtifactRetention) and the open-path sweep.
                    val orphans = buildList {
                        // The just-cancelled generation's CANCELLED record (and,
                        // for REBASE, nothing else: the successor record stays
                        // reachable via activeCandidateGenerationIds).
                        producerGenerationId?.let { add(layout.generationFile(it)) }
                        // This page's superseded checkpoint sidecar.
                        manifest.ocrCheckpoints[pageKey]?.fileName
                            ?.takeIf { it != checkpointFileName }
                            ?.let(::add)
                        // The OCR stage record replaced by this checkpoint.
                        page.ocr?.artifactFileName
                            ?.takeIf { it != snapshotFileName }
                            ?.let(::add)
                        // CLOSE drops every candidate-owned stage record; their
                        // sidecars left the reachability graph.
                        if (mode == OcrCheckpointMode.CLOSE && candidate != null) {
                            listOf(page.detection, page.inpaint, page.translation, page.layout)
                                .filter { it?.generationId == candidate.generationId }
                                .forEach { it?.artifactFileName?.let(::add) }
                        }
                    }
                    val retention = deleteKnownOrphans(orphans, durableManifest = outcome.manifest)
                    TransactionOutcome.Committed(
                        outcome.manifest,
                        successorGenerationId ?: outcome.generationId,
                        retention.deletedNames,
                        commitPoint = point,
                    )
                } else {
                    outcome.copy(commitPoint = point)
                }
            is TransactionOutcome.Rejected -> outcome
        }
    }

    /**
     * The committed bundle's OCR content fingerprint (T924-FP-02 identity),
     * computed with the same source identity (including orientation) the
     * checkpoint claims, so both sides of the T924-TX-03.1 comparison
     * canonicalize identically.
     */
    private fun committedOcrContentFingerprint(
        snapshot: PageTranslation,
        naturalPageIndex: Int?,
        checkpoint: PageOcrCheckpoint,
    ): String = StageFingerprints.pageOcrContentFingerprint(
        pageKey = checkpoint.sourceIdentity.pageKey,
        naturalPageIndex = naturalPageIndex,
        sourceSha256 = snapshot.sourceFingerprint.orEmpty(),
        sourceWidth = snapshot.imgWidth.toInt(),
        sourceHeight = snapshot.imgHeight.toInt(),
        sourceOrientation = checkpoint.sourceIdentity.orientation.orEmpty(),
        detectionFingerprint = snapshot.detectionFingerprint,
        ocrFingerprint = snapshot.ocrFingerprint.orEmpty(),
        textless = snapshot.isTextlessTerminal,
        inpaintMaskRevision = snapshot.inpaintRevision,
        blocks = StageFingerprints.pageOcrContentBlocks(snapshot),
        inpaintMaskBoxes = snapshot.inpaintMaskBoxes,
    )

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
            val commitPoint: CommitPoint? = null,
        ) : TransactionOutcome

        data class Rejected(val reason: String) : TransactionOutcome
    }

    /**
     * One immutable sidecar to publish before its manifest pointer may move
     * (T924-SC-19/20). [contentFingerprint] is the semantic content identity
     * the caller established for the sidecar (it must match the pointer's);
     * [publish] must go through [AtomicChapterDocuments.publish]/[AtomicChapterDocuments.publishJson].
     */
    class SidecarPublication(
        val fileName: String,
        val contentFingerprint: String,
        val publish: () -> Boolean,
    )

    /**
     * T924-SC-20: the generic sidecar-then-pointer transaction. Every
     * [SidecarPublication] is durably published FIRST, then [updatePointers]
     * installs all pointers in ONE atomic manifest publication. Crash or
     * failure windows:
     *
     * - before a sidecar publish completes → nothing visible;
     * - between sidecar and manifest publication → orphan sidecar files only;
     * - during the manifest publication → `.bak` rotation semantics.
     *
     * On any precondition or publication failure the prior manifest stays
     * authoritative (T924-SC-22) and pointers never dangle.
     */
    @Synchronized
    fun publishSidecarPointers(
        manifest: ChapterArtifactManifest,
        sidecars: List<SidecarPublication>,
        updatePointers: (ChapterArtifactManifest) -> ChapterArtifactManifest,
        nowEpochMs: Long = System.currentTimeMillis(),
        commitPoint: CommitPoint? = null,
        syncToDisk: Boolean = false,
    ): TransactionOutcome {
        staleManifestRejection(manifest)?.let { return TransactionOutcome.Rejected(it) }
        sidecars.forEach { sidecar ->
            if (!ChapterArtifactLayout.isSafeSegment(sidecar.fileName.substringAfterLast('/'))) {
                return TransactionOutcome.Rejected("unsafe sidecar file name: file=${sidecar.fileName}")
            }
            if (!layout.isManagedPath(sidecar.fileName)) {
                return TransactionOutcome.Rejected("sidecar outside the managed tree: file=${sidecar.fileName}")
            }
        }
        sidecars.forEach { sidecar ->
            if (!sidecar.publish()) {
                return TransactionOutcome.Rejected("sidecar publication failed: file=${sidecar.fileName}")
            }
        }
        val shouldSync = syncToDisk || commitPoint == CommitPoint.EXPLICIT_FLUSH || commitPoint == CommitPoint.CHAPTER_COMPLETE || commitPoint == CommitPoint.BATCH_CHUNK
        val updated = updatePointers(manifest).copy(updatedAtEpochMs = nowEpochMs)
        if (!publishManifestInternal(updated, syncToDisk = shouldSync)) {
            return TransactionOutcome.Rejected("manifest publication failed; prior manifest remains authoritative")
        }
        return TransactionOutcome.Committed(updated, commitPoint = commitPoint)
    }

    /**
     * T934 LI-x: [publishSidecarPointers] with the store's standard ONE-shot
     * stale-manifest rebase-retry ([retryOnStaleManifest], seam-tagged), for
     * the resume-hydration seams whose caller can hold a snapshot that predates
     * durable publications performed outside the façade (the background health
     * concurrent manifest publication) — the page-registration write of the
     * adoption path. On a stale-manifest rejection ONLY, [updatePointers] is
     * re-run against the freshly re-read durable manifest — the mutation must
     * be a pure function of the base manifest so the rebase carries every
     * fresh durable field forward. Every other rejection reason is returned
     * as-is (T924-SC-20/22); a retry that also fails surfaces the retry's own
     * rejection, exactly like the store's other wrapped seams.
     */
    @Synchronized
    internal fun publishSidecarPointersWithStaleRetry(
        manifest: ChapterArtifactManifest,
        sidecars: List<SidecarPublication>,
        updatePointers: (ChapterArtifactManifest) -> ChapterArtifactManifest,
        nowEpochMs: Long = System.currentTimeMillis(),
        commitPoint: CommitPoint? = null,
        seam: String,
    ): TransactionOutcome = retryOnStaleManifest(
        firstAttempt = publishSidecarPointers(
            manifest = manifest,
            sidecars = sidecars,
            updatePointers = updatePointers,
            nowEpochMs = nowEpochMs,
            commitPoint = commitPoint,
        ),
        callerManifest = manifest,
        seam = seam,
    ) { fresh ->
        publishSidecarPointers(
            manifest = fresh,
            sidecars = sidecars,
            updatePointers = updatePointers,
            nowEpochMs = nowEpochMs,
            commitPoint = commitPoint,
        )
    }

    /** Outcome of reading an OCR checkpoint through its manifest pointer. */
    sealed interface OcrCheckpointRead {
        /** Semantically valid at a supported schema version. */
        data class Usable(val checkpoint: PageOcrCheckpoint) : OcrCheckpointRead

        /**
         * T924-SC-13: a newer schema owns the semantics — unusable for
         * planning (artifact = ABSENT for decisions), bytes preserved
         * untouched, never deleted or overwritten by this version.
         */
        data class UnsupportedVersion(val schemaVersion: Int) : OcrCheckpointRead

        /**
         * T924-SC-17: missing, corrupt, or otherwise invalid. Treated as
         * ABSENT for planning; the page re-enters OCR planning (safe
         * re-derivation) per T924-SC-17.
         */
        data object Absent : OcrCheckpointRead
    }

    /** Reads the pointed OCR checkpoint with unknown-version preservation (T924-SC-12/13). */
    fun readOcrCheckpoint(pointer: SidecarPointer): OcrCheckpointRead {
        if (!pointer.isWellFormed()) return OcrCheckpointRead.Absent
        val bytes = io.read(pointer.fileName) ?: return OcrCheckpointRead.Absent
        val checkpoint = runCatching {
            documents.json.decodeFromStream<PageOcrCheckpoint>(bytes.inputStream())
        }.getOrNull() ?: run {
            documents.quarantineCorrupt(pointer.fileName)
            return OcrCheckpointRead.Absent
        }
        if (checkpoint.schemaVersion > PageOcrCheckpoint.SCHEMA_VERSION) {
            return OcrCheckpointRead.UnsupportedVersion(checkpoint.schemaVersion)
        }
        if (checkpoint.kind != PageOcrCheckpoint.KIND || !checkpoint.isSemanticallyValid) {
            documents.quarantineCorrupt(pointer.fileName)
            return OcrCheckpointRead.Absent
        }
        return OcrCheckpointRead.Usable(checkpoint)
    }

    /** Content-addressed OCR-complete page snapshot sidecar name (existing stage layout). */
    internal fun ocrStageSnapshotName(pageKey: String, fingerprint: String): String =
        layout.stageArtifactFile(pageKey, ArtifactStage.OCR, fingerprint)

    /** Content-addressed `PageOcrCheckpoint` sidecar name under `ocr/` (T924-SC-21). */
    internal fun ocrCheckpointSidecarName(pageKey: String, contentFingerprint: String): String =
        layout.ocrCheckpointFile(pageKey, contentFingerprint)

    // ------------------------------------------------------------------
    // T924 WP9 (additive): generic sidecar reading/publication support for
    // the persisted-layout track (`layoutPlans` / `colorPreparations`
    // pointers). Mirrors the readOcrCheckpoint/readRunRecord idioms exactly
    // (quarantine on corrupt, unknown-version preservation) and the
    // publishActiveRun sidecar-then-pointer pattern; nothing existing changed.
    // ------------------------------------------------------------------

    /** Content-addressed `PageLayoutDrawPlan` sidecar name under `layout/` (T924-SC-21). */
    internal fun layoutPlanSidecarName(pageKey: String, contentFingerprint: String): String =
        layout.layoutPlanFile(pageKey, contentFingerprint)

    /** Content-addressed `AnalysisChunkResult` sidecar name under `analysis/` (T924-SC-21). */
    internal fun analysisChunkSidecarName(contentFingerprint: String): String =
        layout.analysisChunkFile(contentFingerprint)

    /** Content-addressed `ChapterTranslationProfile` sidecar name under `profiles/` (T924-SC-21). */
    internal fun profileSidecarName(contentFingerprint: String): String =
        layout.profileFile(contentFingerprint)

    /**
     * T924 Stage-6 slice A (additive, WP9 idiom): content-addressed
     * `EnvelopePlan` sidecar name under `envelopes/` (T924-SC-21).
     */
    internal fun envelopePlanSidecarName(contentFingerprint: String): String =
        layout.envelopePlanFile(contentFingerprint)

    /** Content-addressed `ColorStylePreparation` sidecar name under `color/` (T924-SC-21). */
    internal fun colorPreparationSidecarName(pageKey: String, contentFingerprint: String): String =
        layout.colorPreparationFile(pageKey, contentFingerprint)

    /** T933 Increment 2: content-addressed `ChapterContextSnapshot` sidecar name under `context/`. */
    internal fun contextSidecarName(contentFingerprint: String): String =
        layout.contextFile(contentFingerprint)

    sealed interface ContextSnapshotRead {
        data class Usable(val snapshot: ChapterContextSnapshot) : ContextSnapshotRead
        data class UnsupportedVersion(val schemaVersion: Int) : ContextSnapshotRead
        data object Absent : ContextSnapshotRead
    }

    fun readContextSnapshot(pointer: SidecarPointer): ContextSnapshotRead {
        if (!pointer.isWellFormed()) return ContextSnapshotRead.Absent
        val bytes = io.read(pointer.fileName) ?: return ContextSnapshotRead.Absent
        val snapshot = runCatching {
            documents.json.decodeFromStream<ChapterContextSnapshot>(bytes.inputStream())
        }.getOrNull() ?: run {
            documents.quarantineCorrupt(pointer.fileName)
            return ContextSnapshotRead.Absent
        }
        if (snapshot.schemaVersion > ChapterContextSnapshot.SCHEMA_VERSION) {
            return ContextSnapshotRead.UnsupportedVersion(snapshot.schemaVersion)
        }
        if (!snapshot.isSemanticallyValid) {
            documents.quarantineCorrupt(pointer.fileName)
            return ContextSnapshotRead.Absent
        }
        return ContextSnapshotRead.Usable(snapshot)
    }

    fun publishContextSnapshot(
        manifest: ChapterArtifactManifest,
        snapshot: ChapterContextSnapshot,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): TransactionOutcome {
        val err = snapshot.validationError()
        if (err != null) return TransactionOutcome.Rejected("invalid context snapshot: $err")
        val fileName = contextSidecarName(snapshot.contentFingerprint)
        val sidecar = jsonSidecarPublication(
            fileName = fileName,
            contentFingerprint = snapshot.contentFingerprint,
            document = snapshot,
            serializer = ChapterContextSnapshot.serializer(),
        )
        val pointer = SidecarPointer(
            fileName = fileName,
            schemaVersion = ChapterContextSnapshot.SCHEMA_VERSION,
            contentFingerprint = snapshot.contentFingerprint,
        )
        return publishSidecarPointers(
            manifest = manifest,
            sidecars = listOf(sidecar),
            updatePointers = { it.copy(context = pointer) },
            nowEpochMs = nowEpochMs,
        )
    }

    /**
     * Builds one immutable JSON sidecar publication for
     * [publishSidecarPointers] through the shared [AtomicChapterDocuments]
     * Json (T924-SC-06), so callers outside this package can stage
     * sidecar-then-pointer transactions without touching the document layer.
     */
    fun <T : Any> jsonSidecarPublication(
        fileName: String,
        contentFingerprint: String,
        document: T,
        serializer: KSerializer<T>,
    ): SidecarPublication = SidecarPublication(fileName, contentFingerprint) {
        val bytes = documents.json.encodeToString(serializer, document).toByteArray(Charsets.UTF_8)
        documents.publish(fileName, bytes) { written ->
            runCatching { documents.json.decodeFromStream(serializer, written.inputStream()) }.isSuccess
        }
    }

    /**
     * Generic pointer read with the `readOcrCheckpoint` idiom: well-formedness
     * gate, parse with quarantine on corruption, unknown-version preservation
     * (NEVER quarantined), kind check + semantic validation with quarantine on
     * invalid payloads (T924-SC-12/13/17).
     */
    fun <T : Any> readSidecarDocument(
        pointer: SidecarPointer,
        serializer: KSerializer<T>,
        currentSchemaVersion: Int,
        expectedKind: String,
        schemaVersionOf: (T) -> Int,
        kindOf: (T) -> String,
        isValid: (T) -> Boolean,
    ): SidecarRead<T> {
        if (!pointer.isWellFormed()) return SidecarRead.Absent
        val bytes = io.read(pointer.fileName) ?: return SidecarRead.Absent
        val document = runCatching {
            documents.json.decodeFromStream(serializer, bytes.inputStream())
        }.getOrNull() ?: run {
            documents.quarantineCorrupt(pointer.fileName)
            return SidecarRead.Absent
        }
        val version = schemaVersionOf(document)
        if (version > currentSchemaVersion) {
            return SidecarRead.UnsupportedVersion(version)
        }
        if (kindOf(document) != expectedKind || !isValid(document)) {
            documents.quarantineCorrupt(pointer.fileName)
            return SidecarRead.Absent
        }
        return SidecarRead.Usable(document)
    }
    /** Reads a complete live-store page snapshot referenced by a manifest pointer. */
    fun readPageSnapshot(fileName: String?): PageTranslation? =
        fileName?.let { documents.readValidated<PageTranslation>(it) }

    /**
     * Persists the mutable live candidate in its own immutable sidecar and
     * advances only the candidate pointer. The committed pointer is untouched
     * until [promoteLiveCandidate] succeeds.
     *
     * T924 LI-4 / T934: a stale-manifest CAS rejection (the leading
     * [candidateWriteRejection] check) triggers ONE retry against the freshly
     * re-read durable manifest — this is the store transaction behind the
     * façade's stage-patch/candidate persistence, and the batch resume path
     * must not surface a spurious ARTIFACT_PUBLICATION_FAILED whole-batch
     * abort on a healthy chapter. The whole transaction re-runs against the
     * FRESH manifest, so genuine drift still rejects with its real reason.
     * Every other rejection reason is returned as-is.
     * [persistLiveCandidateAndFailure] delegates here and inherits the retry.
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
    ): TransactionOutcome = retryOnStaleManifest(
        firstAttempt = persistLiveCandidateOnce(
            manifest,
            pageKey,
            generationId,
            expectedPageVersion,
            expectedDependencyFingerprint,
            pageSnapshot,
            origin,
            sourceIdentity,
            nowEpochMs,
            durableFailure,
        ),
        callerManifest = manifest,
        seam = "persistLiveCandidate",
    ) { fresh ->
        persistLiveCandidateOnce(
            fresh,
            pageKey,
            generationId,
            expectedPageVersion,
            expectedDependencyFingerprint,
            pageSnapshot,
            origin,
            sourceIdentity,
            nowEpochMs,
            durableFailure,
        )
    }

    private fun persistLiveCandidateOnce(
        manifest: ChapterArtifactManifest,
        pageKey: String,
        generationId: String,
        expectedPageVersion: Long,
        expectedDependencyFingerprint: String,
        pageSnapshot: PageTranslation,
        origin: ArtifactOrigin,
        sourceIdentity: SourceIdentity?,
        nowEpochMs: Long,
        durableFailure: DurableFailureMetadata?,
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
                // Generation-less by intent: a durable failure is a page-level
                // ledger fact, not candidate work product. Candidate stamping
                // would make the NEXT attempt's teardown (cancelCandidate strips
                // candidate-owned records and their ledger keys) erase the
                // previous attempt's entry before the consecutive-unresolved
                // count is charged — the attempt cap could never advance across
                // attempts or restarts. Success-path cleanup stays explicit.
                generationId = null,
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
     *
     * T924 LI-4 / T934: a stale-manifest CAS rejection (the leading
     * [candidateWriteRejection] check) triggers ONE retry against the freshly
     * re-read durable manifest — the batch resume's candidate-promotion
     * transaction must not surface a spurious ARTIFACT_PUBLICATION_FAILED
     * whole-batch abort on a healthy chapter. The whole transaction re-runs
     * against the FRESH manifest, so genuine drift still rejects with its real
     * reason. Every other rejection reason is returned as-is.
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
    ): TransactionOutcome = retryOnStaleManifest(
        firstAttempt = promoteLiveCandidateOnce(
            manifest,
            pageKey,
            generationId,
            expectedPageVersion,
            expectedDependencyFingerprint,
            pageSnapshot,
            origin,
            sourceIdentity,
            nowEpochMs,
        ),
        callerManifest = manifest,
        seam = "promoteLiveCandidate",
    ) { fresh ->
        promoteLiveCandidateOnce(
            fresh,
            pageKey,
            generationId,
            expectedPageVersion,
            expectedDependencyFingerprint,
            pageSnapshot,
            origin,
            sourceIdentity,
            nowEpochMs,
        )
    }

    private fun promoteLiveCandidateOnce(
        manifest: ChapterArtifactManifest,
        pageKey: String,
        generationId: String,
        expectedPageVersion: Long,
        expectedDependencyFingerprint: String,
        pageSnapshot: PageTranslation,
        origin: ArtifactOrigin,
        sourceIdentity: SourceIdentity?,
        nowEpochMs: Long,
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
        }
        if (!GroupCommitConfiguration.enabled) {
            val candidateFile = candidate.pageSnapshotFileName
                ?: layout.candidatePageSnapshotFile(pageKey, generationId)
            val candidateMatches = documents.readValidated<PageTranslation>(candidateFile)?.let {
                it == pageSnapshot
            } == true
            if (!candidateMatches && !documents.publishJson(candidateFile, pageSnapshot.detachedCopy())) {
                return TransactionOutcome.Rejected("candidate page snapshot publication failed: pageKey=$pageKey")
            }
        }
        val committedFile = layout.committedPageSnapshotFile(pageKey, generationId)
        if (!documents.publishJson(committedFile, pageSnapshot.detachedCopy())) {
            return TransactionOutcome.Rejected("committed page snapshot publication failed: pageKey=$pageKey")
        }
        val displayBase = DisplayBaseReference(
            kind = if (pageSnapshot.cleanedImageName != null) DisplayBaseKind.CLEANED_IMAGE else DisplayBaseKind.ORIGINAL_SOURCE,
            fileName = pageSnapshot.cleanedImageName,
            validated = pageSnapshot.cleanedImageName != null,
            legacyLayout = false,
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
        return TransactionOutcome.Committed(updated, generationId, commitPoint = CommitPoint.PAGE_TERMINAL_PROMOTION)
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
     *
     * T924 LI-4 / T934: a stale-manifest CAS rejection (the >8-page open
     * path's background health verify republishing after the façade cached its
     * copy) triggers ONE retry against the freshly re-read durable manifest —
     * the batch resume's first candidate open must not surface a spurious
     * ARTIFACT_PUBLICATION_FAILED whole-batch abort on a healthy chapter. The
     * whole transaction (identity checks included) re-runs against the FRESH
     * manifest, so genuine drift still rejects — with a non-stale reason, on
     * the retry attempt. Every other rejection reason is returned as-is.
     */
    @Synchronized
    fun openCandidate(
        manifest: ChapterArtifactManifest,
        pageKey: String,
        origin: ArtifactOrigin,
        expectedPageVersion: Long,
        dependencyFingerprint: String,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): TransactionOutcome = retryOnStaleManifest(
        firstAttempt = openCandidateOnce(
            manifest,
            pageKey,
            origin,
            expectedPageVersion,
            dependencyFingerprint,
            nowEpochMs,
        ),
        callerManifest = manifest,
        seam = "openCandidate",
    ) { fresh ->
        openCandidateOnce(
            fresh,
            pageKey,
            origin,
            expectedPageVersion,
            dependencyFingerprint,
            nowEpochMs,
        )
    }

    private fun openCandidateOnce(
        manifest: ChapterArtifactManifest,
        pageKey: String,
        origin: ArtifactOrigin,
        expectedPageVersion: Long,
        dependencyFingerprint: String,
        nowEpochMs: Long,
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
        // Event-driven reclamation (T930 Slice A4): relaunch teardown cancels
        // every stale candidate left by a dead process — one full SAF tree
        // sweep per page there cost tens of seconds per page on large
        // chapters (the same measured crawl removed from the checkpoint and
        // open paths). Only the files THIS cancel unlinked are candidates;
        // cross-page orphans stay owned by the chapter-boundary sweep.
        val orphans = buildList {
            add(layout.generationFile(generationId))
            listOf(page.detection, page.ocr, page.inpaint, page.translation, page.layout)
                .filter { it?.generationId == generationId }
                .forEach { it?.artifactFileName?.let(::add) }
        }
        val retention = deleteKnownOrphans(orphans, durableManifest = updated)
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

    /**
     * T924 LI-4: stable reason prefix of the stale-manifest CAS rejection. The
     * one-shot retry on the first-publication seams ([publishActiveRun],
     * [checkpointOcr], [openCandidate], [retireActiveRun],
     * [persistLiveCandidate] — including [persistLiveCandidateAndFailure] —
     * and [promoteLiveCandidate]) keys on this prefix — every OTHER rejection
     * reason (identity drift, publication failure, future-schema guard) must
     * keep failing the caller exactly as before.
     */
    private val STALE_MANIFEST_REJECTION_REASON = "stale manifest snapshot"

    private fun staleManifestRejection(manifest: ChapterArtifactManifest): String? {
        val durable = casBaselineManifest()
            ?: return "manifest is not durable: chapter=${layout.chapterKey}"
        return if (durable == manifest) {
            null
        } else {
            "$STALE_MANIFEST_REJECTION_REASON: chapter=${layout.chapterKey}"
        }
    }

    /** The rejection reason iff [outcome] was rejected BY the stale-manifest CAS specifically. */
    private fun TransactionOutcome.staleManifestRejectionOrNull(): String? =
        (this as? TransactionOutcome.Rejected)
            ?.reason
            ?.takeIf { it.startsWith(STALE_MANIFEST_REJECTION_REASON) }

    /**
     * T934 LI-x: stale-manifest CAS detection exposed to the one seam whose
     * publication lives OUTSIDE this store ([EnvelopePlanPublication.publish]):
     * the CAS there keys on the same [STALE_MANIFEST_REJECTION_REASON] prefix
     * so every other rejection reason keeps failing exactly as before
     * (T924-SC-20/22).
     */
    internal fun isStaleManifestRejection(outcome: TransactionOutcome): Boolean =
        outcome.staleManifestRejectionOrNull() != null

    /**
     * T924 LI-4: one-shot stale-manifest retry for the flagged Batch lane's
     * first-publication seams. When the >8-page open path's background
     * artifact-health retry republishes a VERIFIED manifest after the
     * façade cached the pre-verification copy, a dispatch presenting that stale
     * copy to its FIRST durable publication was CAS-rejected — a spurious
     * CHECKPOINT_REJECTED preflight failure or PAUSED run on a healthy chapter.
     * On a stale-manifest rejection specifically: re-read the durable manifest
     * ONCE, rebuild the intended mutation against the FRESH manifest (never
     * re-publish the caller's stale object), and retry the publication once.
     * A retry that also fails — or a durable manifest that vanished — returns
     * the original Rejected outcome unchanged, so the caller-visible contract
     * stays "Committed or Rejected".
     *
     * T934: the same race aborted the whole batch RESUME at its other
     * first-publication seams (candidate open, stage-patch candidate
     * persist/promote, run-pointer retirement), so the wrapped set now covers
     * every seam the resume path hits. [recordDurableFailure]'s RecordOutcome
     * is intentionally OUT of scope — the failure-ledger write is best-effort
     * by contract (the coordinator logs and continues).
     */
    private fun retryOnStaleManifest(
        firstAttempt: TransactionOutcome,
        callerManifest: ChapterArtifactManifest,
        seam: String,
        retry: (ChapterArtifactManifest) -> TransactionOutcome,
    ): TransactionOutcome {
        firstAttempt.staleManifestRejectionOrNull() ?: return firstAttempt
        val fresh = casBaselineManifest() ?: return firstAttempt
        logcat(LogPriority.WARN) {
            "TachiyomiAT artifact stale manifest retried once: seam=$seam " +
                "chapter=${layout.chapterKey} " +
                "staleUpdatedAt=${callerManifest.updatedAtEpochMs} " +
                "freshUpdatedAt=${fresh.updatedAtEpochMs}"
        }
        return retry(fresh)
    }

    internal fun stagePayloadIsValid(record: StageArtifactRecord?): Boolean {
        val fileName = record?.artifactFileName ?: return false
        return io.exists(fileName) &&
            io.length(fileName) > 0L &&
            documents.readValidated<JsonObject>(fileName) != null
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
    fun reconcileRetention(
        manifest: ChapterArtifactManifest,
        stagedReachable: Set<String> = emptySet(),
    ): RetentionResult =
        retentionSweep.reconcileRetention(manifest, stagedReachable)

    /**
     * Retention phase 1 (candidate crawl) WITHOUT the store monitor — and
     * deliberately NOT @Synchronized. It is pure over its inputs (the passed
     * manifest + the immutable layout/IO) and takes minutes of SAF round-trips
     * on real storage; taking either the store monitor or the scheduler mutex
     * during it froze every page lease in the pipeline (jdb thread dump,
     * 2026-09-15: 20+ minute batch stall on a 70-page chapter). Pair with
     * [deleteVerifiedRetentionCandidates], which re-verifies each candidate
     * against the live manifest under the monitor.
     */
    fun collectRetentionCandidates(
        manifest: ChapterArtifactManifest,
        stagedReachable: Set<String> = emptySet(),
    ): Set<String> = retentionSweep.collectOrphanCandidates(manifest, stagedReachable)

    /** Retention phase 2: re-verify and delete under the store monitor. */
    @Synchronized
    fun deleteVerifiedRetentionCandidates(
        candidateOrphans: Collection<String>,
        manifest: ChapterArtifactManifest,
        stagedReachable: Set<String> = emptySet(),
    ): RetentionResult =
        retentionSweep.deleteVerifiedOrphans(candidateOrphans, manifest, stagedReachable)

    /**
     * Retention phase 2 for the split (off-lock crawl) sweep: resolves the
     * verification manifest ITSELF, under the store monitor, from the durable
     * artifact tree. A caller-supplied manifest captured outside this monitor
     * (e.g. the facade's cached snapshot) can lag an in-flight publication by
     * its whole body — the crawl may have listed a sidecar whose file was
     * already written while its manifest pointer was not yet installed — and
     * verifying against that stale graph deletes a sidecar the just-completed
     * publication points at (observed: the T924 COMPLETE run record vanishing
     * between its publication and the next read, leaving a dangling
     * `activeRun`). This monitor serializes with every publication's manifest
     * rotation, so a manifest read here is never older than any installed
     * pointer. A chapter with no durable manifest verifies nothing.
     */
    @Synchronized
    fun deleteVerifiedRetentionCandidates(
        candidateOrphans: Collection<String>,
    ): RetentionResult {
        val live = readManifest() ?: return RetentionResult(0, emptyList())
        return retentionSweep.deleteVerifiedOrphans(candidateOrphans, live)
    }

    /**
     * T930 Slice A4 (Amendment D): event-driven known-orphan deletion.
     * Reclaims explicitly unlinked artifact files without a full tree crawl.
     * Race register #6: files referenced in stagedReachable are spared; when
     * [durableManifest] is supplied, files still reachable from it are spared too.
     */
    @Synchronized
    fun deleteKnownOrphans(
        candidateOrphans: Collection<String>,
        stagedReachable: Set<String> = emptySet(),
        durableManifest: ChapterArtifactManifest? = null,
    ): RetentionResult =
        retentionSweep.deleteKnownOrphans(candidateOrphans, stagedReachable, durableManifest)

    internal fun readManifestDocument(name: String): ChapterArtifactManifest? {
        val bytes = io.read(name) ?: return null
        return parseManifest(bytes)
    }

    private fun parseManifest(bytes: ByteArray): ChapterArtifactManifest? = runCatching {
        documents.json.decodeFromStream<ChapterArtifactManifest>(bytes.inputStream())
    }.getOrNull()?.normalizeSupportedSchema()

    /**
     * T930 Slice A3 (Amendment A): cache for schema normalization decisions.
     * Maps schemaVersion -> boolean indicating whether schema normalization is required.
     * Future-schema guard reads at :130 and :230 (and CAS at :1642) are NEVER cached:
     * always fresh from disk.
     */
    private val schemaNormalizationDecisionCache = java.util.concurrent.ConcurrentHashMap<Int, Boolean>()

    internal fun isNormalizationRequired(schemaVersion: Int): Boolean =
        schemaNormalizationDecisionCache.getOrPut(schemaVersion) {
            schemaVersion in 1 until ChapterArtifactManifest.SCHEMA_VERSION
        }

    internal fun invalidateSchemaGuardCache() {
        schemaNormalizationDecisionCache.clear()
    }

    /**
     * T924-SC-04: new code reads manifest schema versions 2 and 3 and writes
     * v3. Older supported manifests load with the additive pointer fields
     * defaulted and are normalized in memory to the current schema version so
     * the next publication rewrites them as v3 — new pointers can never ride
     * inside an old-schema document a rolled-back build would strip. Future
     * schemas stay untouched (the `>` guard must still see and refuse them).
     */
    private fun ChapterArtifactManifest.normalizeSupportedSchema(): ChapterArtifactManifest =
        if (isNormalizationRequired(schemaVersion)) {
            copy(schemaVersion = ChapterArtifactManifest.SCHEMA_VERSION)
        } else {
            this
        }

    private fun recoverPrimaryFromBackupOrNull(backup: ChapterArtifactManifest?): ChapterArtifactManifest? {
        if (backup == null) return null
        documents.recoverPrimaryFromBackup(layout.manifestFileName, backupName())
        return readManifestDocument(layout.manifestFileName) ?: backup
    }

    private fun futureBackupPresent(): Boolean =
        readManifestDocument(backupName())?.schemaVersion?.let { it > ChapterArtifactManifest.SCHEMA_VERSION }
            ?: false

    // ------------------------------------------------------------------
    // T934 LI-x: adoption-write manifest coalescing for the batch-resume
    // rebuild. The resume hydration loop (`buildEnvelopeDispatchWork` →
    // `adoptCheckpointSnapshot` → the façade's open/persist/promote candidate
    // transactions) republished the FULL manifest JSON once or more PER PAGE
    // (~340 rewrites of a ~360KB document on a 206-page chapter, ~1.3s apart),
    // contributing to a main-thread ANR. While a coalescing window is open
    // (the coordinator brackets the rebuild in begin/try/finally-end), each
    // manifest publication only STASHES the intended manifest (last
    // writer wins — the transaction chain is serialized by the store monitor,
    // so the stash is always the newest intended state); the durable rewrite
    // happens at most once per [MANIFEST_COALESCING_FLUSH_EVERY] stashed
    // publications and ALWAYS on [endManifestCoalescing]. Failure semantics:
    // a mid-window flush failure fails the owning transaction exactly as a
    // direct publication failure would (callers see Rejected); the final flush
    // is best-effort with a WARN — resume re-derives adopted state from the
    // durable checkpoints. Crash safety is unchanged: the window only widens
    // the existing sidecar-then-pointer crash window (pointer moves delayed,
    // sidecar files content-addressed and idempotent); a crash before the
    // flush leaves the prior manifest authoritative and the resume re-adopts.
    //
    // The CAS baseline follows the stash ([casBaselineManifest]): while a
    // window is open the intended (stashed) manifest — not the lagging file —
    // is what [staleManifestRejection] compares against, so the serialized
    // transaction chain never stale-rejects against its own deferred writes
    // and never rebases onto a manifest that would drop them. With no window
    // open the baseline is the durable file exactly as before T934.
    // ------------------------------------------------------------------

    /** Bounded unflushed state: a durable manifest rewrite at most every N stashed publications. */
    private val MANIFEST_COALESCING_FLUSH_EVERY = 32

    private var manifestCoalescingDepth = 0
    private var coalescedManifest: ChapterArtifactManifest? = null
    private var coalescedSyncToDisk = false
    private var coalescedPublications = 0

    /**
     * Opens one coalescing window. Callers MUST close it with
     * [endManifestCoalescing] (a try/finally bracket), which performs the
     * mandatory final flush. Nesting is counted; the outermost end flushes.
     */
    @Synchronized
    fun beginManifestCoalescing() {
        manifestCoalescingDepth += 1
    }

    /**
     * Closes one coalescing window and flushes the stashed manifest when the
     * OUTERMOST window ends (T934 LI-x: the rebuild always ends coherent —
     * `store.artifactManifest` tracks each Committed manifest, so after the
     * final flush the façade equals the durable file). A failed final flush is
     * logged and dropped: the transactions already reported Committed, and the
     * durable state heals on the next resume via the checkpoint sidecars.
     */
    @Synchronized
    fun endManifestCoalescing() {
        if (manifestCoalescingDepth > 0) manifestCoalescingDepth -= 1
        if (manifestCoalescingDepth > 0) return
        val pending = coalescedManifest
        coalescedManifest = null
        val sync = coalescedSyncToDisk
        coalescedSyncToDisk = false
        coalescedPublications = 0
        if (pending != null && !documents.publishJson(layout.manifestFileName, pending, syncToDisk = sync)) {
            logcat(LogPriority.WARN) {
                "TachiyomiAT manifest coalescing final flush failed; prior manifest retained: " +
                    "chapter=${layout.chapterKey} pendingUpdatedAt=${pending.updatedAtEpochMs}"
            }
        }
    }

    /**
     * T934 LI-x: the manifest CAS baseline — the coalesced (intended) manifest
     * while a window is open, else the durable file. Must only be called under
     * the store monitor (every caller is a [Synchronized] transaction).
     */
    private fun casBaselineManifest(): ChapterArtifactManifest? =
        coalescedManifest ?: readManifest()

    // T934 LI-x: @Synchronized because this is also reached WITHOUT the store
    // monitor (the background legacy-health verifier's raw publications), and
    // the coalescing stash below must not race with the transaction chain.
    // Reentrant for the [Synchronized] transaction callers; lock order
    // (store monitor → per-name document lock) is the pre-existing order.
    @Synchronized
    internal fun publishManifestInternal(manifest: ChapterArtifactManifest, syncToDisk: Boolean = false): Boolean {
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
        if (manifestCoalescingDepth > 0) {
            coalescedManifest = manifest
            if (syncToDisk) coalescedSyncToDisk = true
            coalescedPublications += 1
            if (coalescedPublications % MANIFEST_COALESCING_FLUSH_EVERY == 0) {
                // T934 LI-x: bounded unflushed state — flush through the normal
                // path WITHOUT closing the window (depth stays > 0).
                val pending = coalescedManifest
                coalescedManifest = null
                val pendingSync = coalescedSyncToDisk
                coalescedSyncToDisk = false
                if (pending != null && !documents.publishJson(layout.manifestFileName, pending, syncToDisk = pendingSync)) {
                    logcat(LogPriority.WARN) {
                        "TachiyomiAT manifest coalescing flush failed: " +
                            "chapter=${layout.chapterKey} pendingUpdatedAt=${pending.updatedAtEpochMs}"
                    }
                    return false
                }
            }
            return true
        }
        return documents.publishJson(layout.manifestFileName, manifest, syncToDisk = syncToDisk)
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
