package eu.kanade.translation.persistence.artifact

import eu.kanade.translation.diagnostics.ReaderEntryTrace
import eu.kanade.translation.model.PageDisplayState
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.PublishedPageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.detachedCopy
import eu.kanade.translation.model.isTextlessTerminal
import eu.kanade.translation.model.toDraft
import eu.kanade.translation.model.toPublishedPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromStream
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * Owns one chapter's artifact manifest and immutable sidecar tree. Candidate
 * generation transitions, preconditioned stage commits, committed-pointer
 * promotion, cancellation and failure state, crash recovery, and bounded
 * retention all operate on artifact documents. Flat translation files from
 * pre-artifact builds are not read or migrated.
 */
/**
 * Controls whether [ChapterArtifactEngine.checkpointOcr] closes the active
 * candidate or opens a successor.
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
 * has exactly two durable values, so neutrality lives in the
 * checkpoint sidecar, never in the candidate.
 */
enum class OcrCheckpointMode { CLOSE, REBASE }

/**
 * Outcome of reading a generic sidecar document through
 * its manifest pointer — same semantics as [RunRecordRead]/[OcrCheckpointRead],
 * generalized over the document type for persisted layouts
 * (`layoutPlans` / `colorPreparations`).
 */
sealed interface SidecarRead<out T : Any> {
    /** Parsed, schema-supported, and semantically valid. */
    data class Usable<T : Any>(val document: T) : SidecarRead<T>

    /**
     * A newer schema owns the semantics — unusable here, bytes
     * preserved untouched, never deleted, quarantined, or overwritten.
     */
    data class UnsupportedVersion(val schemaVersion: Int) : SidecarRead<Nothing>

    /** Missing, malformed pointer, corrupt (quarantined), or semantically invalid. */
    data object Absent : SidecarRead<Nothing>
}

class ChapterArtifactEngine(
    private val documents: AtomicChapterDocuments,
    internal val layout: ChapterArtifactLayout,
    @Suppress("UNUSED_PARAMETER")
    private val displayBaseProbe: CleanedImageProbe = BitmapFactoryCleanedImageProbe,
) {
    private val io: ChapterDocumentIo get() = documents.rawIo()

    /** Sidecars prepared off-lock before a store publishes the re-key pointers. */
    internal data class PreparedPageSnapshotRekey(
        val pages: Map<String, PageArtifactRecord>,
        /** Exact new-key state and identity to place in the BULK_REKEY journal record. */
        val journalMutations: Map<String, PreparedRekeyJournalMutation>,
    )

    internal data class PreparedRekeyJournalMutation(
        val state: PublishedPageTranslation,
        /** Null is an explicit retryable invalidation, never a completed state. */
        val artifactContentHash: String?,
    )

    /** Bounded retention sweep. */
    private val retentionSweep = ArtifactRetention(io, layout)
    private val retentionScopeJob = SupervisorJob()
    private val retentionScope = CoroutineScope(retentionScopeJob + Dispatchers.IO)
    private val retentionInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    data class LoadResult(
        val manifest: ChapterArtifactManifest,
    )

    /** Outcome of a durable-failure record attempt; only [Stored] is durable. */
    sealed interface RecordOutcome {
        data class Stored(val manifest: ChapterArtifactManifest) : RecordOutcome
        data class NotStored(val reason: String) : RecordOutcome
    }
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
            //  the recursive sweep must not run for large chapters — it
            // runs at the serialized reader boundary. Large-chapter cleanup
            // is owned by the deferred
            // maintenance path (follow-up: event-driven retention per
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
    fun publishManifest(manifest: ChapterArtifactManifest): Boolean =
        publishManifestInternal(stampChapterKey(manifest))

    /**
     * Reads the chapter's durable attempt-ledger document.
     * A future-schema document is returned read-only and never overwritten —
     * the same preservation rule as the manifest.
     */
    fun readAttemptLedger(): ChapterAttemptLedgerDocument? =
        documents.readValidated<ChapterAttemptLedgerDocument>(layout.attemptLedgerFileName) { ledger ->
            ledger.schemaVersion <= ChapterAttemptLedgerDocument.SCHEMA_VERSION &&
                ledger.kind == ChapterAttemptLedgerDocument.KIND_ATTEMPT_LEDGER
        }

    /** Crash-safe attempt-ledger publication: temp, validate, rename. */
    fun publishAttemptLedger(document: ChapterAttemptLedgerDocument): Boolean =
        documents.publishJson(layout.attemptLedgerFileName, document)

    // ------------------------------------------------------------------
    // Versioned sidecar publication.
    //
    // The ONLY publication mechanism for the new sidecar kinds is the
    // existing AtomicChapterDocuments publish path, and the ONLY
    // manifest-update mechanism is publishManifestInternal. Sidecars are
    // published first with content-addressed names, then one atomic
    // manifest publication installs their pointers SECOND — a crash between
    // the two leaves at most an orphan sidecar, never a dangling pointer.
    // On any precondition or publication failure the prior manifest stays
    // authoritative; orphan sidecars are reclaimed exclusively
    // by retention store-reachability sweeps.
    // ------------------------------------------------------------------

    /** Outcome of reading a versioned sidecar through a manifest pointer. */
    sealed interface RunRecordRead {
        /** Semantically valid at a supported schema version. */
        data class Usable(val record: ChapterRunRecord) : RunRecordRead

        /**
         * 13: a newer schema owns the semantics — unusable for
         * planning (artifact = ABSENT for decisions), bytes preserved
         * untouched, never deleted or overwritten by this version.
         */
        data class UnsupportedVersion(val schemaVersion: Int) : RunRecordRead

        /**
         * 17: missing, corrupt (parse failure, kind mismatch, bound
         * violation — quarantined as `.corrupt` by the document layer), or
         * otherwise invalid. Treated as ABSENT for planning; corrupt bytes
         * stay quarantined while a pointer references them.
         */
        data object Absent : RunRecordRead
    }

    /** Reads the pointed run record with unknown-version preservation. */
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
     * 20/SC-22 run-record publication: validates the record, publishes
     * the immutable sidecar into the content-addressed `runs/` directory
     * FIRST, then installs the [ChapterArtifactManifest.activeRun] pointer in
     * ONE atomic manifest publication. Any failure leaves the prior manifest
     * authoritative and at most an orphan sidecar behind.
     *
     *   a stale-manifest CAS rejection (the >8-page open path's
     * background health verify republishing after the façade cached its copy)
     * triggers ONE retry against the freshly re-read durable manifest — the
     * first-publication seam the flagged Batch lane hits must not surface a
     * spurious rejection on a healthy chapter. Every other rejection reason is
     * returned as-is.
     */
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
     *   retires the [ChapterArtifactManifest.activeRun] pointer in ONE
     * CAS'd manifest publication — [manifest.copy](activeRun = null) guarded by
     * [staleManifestRejection], so a concurrent writer's manifest wins and the
     * caller retries with fresh state. Retiring an already-null pointer is an
     * idempotent no-op [TransactionOutcome.Committed] (no publication). The
     * run-record SIDECAR FILE is left in place: retention owns deletion of the
     * now-orphaned record — [ArtifactRetention.reachablePaths] retains exactly
     * the manifest-pointed `activeRun` sidecar, so once the pointer is gone the
     * next reachability sweep reclaims the orphaned record file.
     *
     * A user reset clears the active-run pointer so the next batch starts fresh
     * instead of returning a zero-work finished outcome over demoted displays.
     *
     *   /  a stale-manifest CAS rejection (the >8-page open
     * path's background health verify republishing after the caller cached its
     * copy) triggers ONE retry against the freshly re-read durable manifest —
     * the batch resume teardown must not surface a spurious rejection on a
     * healthy chapter. Every other rejection reason is returned as-is.
     */
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
    // The checkpointOcr transaction owns ONLY
    // the durable publication; the caller keeps the page lease until after
    // a Committed outcome and releases it as a separate, strictly-later
    // step. Store-generation/pageVersion/leaseToken fencing
    // ( inputs 1-3) belongs to the ChapterTranslationStore façade;
    // this transaction compares the manifest-level identity (inputs 4-6)
    // against the durable manifest under the whole-manifest CAS.
    // ------------------------------------------------------------------

    /**
     * 01: atomically publishes the origin-neutral
     * [PageOcrCheckpoint] sidecar plus its immutable OCR page snapshot, and
     * closes or rebases the active BATCH candidate — all in ONE manifest
     * publication. Three branches:
     *
     * - **Standard CLOSE/REBASE** ([checkpoint.producerGenerationId] != null,
     *   active BATCH candidate): compares candidateGenerationId,
     *   artifact pageVersion, and dependency fingerprint against the
     *   durable manifest ( inputs 4-6, no grace clause —.1),
     *   then CLOSE clears the candidate (CANCELLED record) or REBASE opens a
     *   successor generation whose dependency fingerprint equals the
     *   checkpoint content fingerprint. The prior committed
     *   display pointer is never touched.
     * - **Adopt-committed** ([checkpoint.producerGenerationId] == null,
     *   no active candidate,.1): requires a committed bundle whose
     *   OCR content fingerprint equals the checkpoint's; publishes the
     *   checkpoint sidecar + pointer ONLY. A fingerprint mismatch is
     *   REJECTED as content drift — Batch must re-plan, not adopt.
     *
     * On any precondition or publication failure the prior manifest stays
     * authoritative ( BX) and at most an orphan sidecar exists (B1-B2).
     *
     *   a stale-manifest CAS rejection (the >8-page open path's
     * background health verify republishing after the façade cached its copy)
     * triggers ONE retry against the freshly re-read durable manifest — the
     * first-publication seam the flagged Batch lane hits must not surface a
     * spurious CHECKPOINT_REJECTED preflight failure on a healthy chapter. The
     * whole transaction (identity checks included) re-runs against the FRESH
     * manifest, so genuine drift still rejects — with a non-stale reason, on
     * the retry attempt. Every other rejection reason is returned as-is.
     */
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
            // 02.1 (fail closed): the store-level grace clause that
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
                            // 03(c): the successor's first write
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
            // 03.1 adopt-committed: no active candidate; a committed
            // bundle exists whose OCR content fingerprint equals the input.
            // Publish the checkpoint sidecar + pointer ONLY — the committed
            // display pointer is untouched by construction.
            if (candidate != null) {
                return TransactionOutcome.Rejected(
                    "active candidate present; standard checkpoint branch required: pageKey=$pageKey",
                )
            }
            val committed = page.committed
            if (committed == null) {
                // A blank page (OCR READY, zero blocks) never opens an artifact
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
            //  R2a.1 (write-time digests): the page's source SHA-256 is
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
            // chapter paused even though the page has recovered.
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
                    // The checkpoint runs once for each OCR'd page on the
                    // serialized batch lane; a full SAF tree
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
     * The committed bundle's OCR content fingerprint ( identity),
     * computed with the same source identity (including orientation) the
     * checkpoint claims, so both sides of the.1 comparison
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
    // Crash-safe store transactions.
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
            val candidateOpenState: CandidateOpenState? = null,
        ) : TransactionOutcome

        data class Rejected(
            val reason: String,
            val candidateOpenState: CandidateOpenState? = null,
            /** Candidate state durably restored when an immediate terminal publication failed. */
            val recoverableCandidateManifest: ChapterArtifactManifest? = null,
        ) : TransactionOutcome
    }

    /** Neutral outcome details for opening an artifact candidate. */
    sealed interface CandidateOpenState {
        data class Reused(
            val pageKey: String,
            val dependencyFingerprint: String,
        ) : CandidateOpenState

        data class FingerprintMismatch(
            val pageKey: String,
            val expectedFingerprint: String,
            val activeFingerprint: String?,
        ) : CandidateOpenState

        data class CandidateAlreadyActive(
            val pageKey: String,
            val requestedOrigin: ArtifactOrigin,
            val activeOrigin: ArtifactOrigin,
        ) : CandidateOpenState
    }

    /**
     * One immutable sidecar to publish before its manifest pointer may move
     *. [contentFingerprint] is the semantic content identity
     * the caller established for the sidecar (it must match the pointer's);
     * [publish] must go through [AtomicChapterDocuments.publish]/[AtomicChapterDocuments.publishJson].
     */
    class SidecarPublication(
        val fileName: String,
        val contentFingerprint: String,
        val publish: () -> Boolean,
    )

    /**
     * 20: the generic sidecar-then-pointer transaction. Every
     * [SidecarPublication] is durably published FIRST, then [updatePointers]
     * installs all pointers in ONE atomic manifest publication. Crash or
     * failure windows:
     *
     * - before a sidecar publish completes → nothing visible;
     * - between sidecar and manifest publication → orphan sidecar files only;
     * - during the manifest publication → `.bak` rotation semantics.
     *
     * On any precondition or publication failure the prior manifest stays
     * authoritative  and pointers never dangle.
     */
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
        val shouldSync = syncToDisk ||
            commitPoint == CommitPoint.EXPLICIT_FLUSH ||
            commitPoint == CommitPoint.CHAPTER_COMPLETE
        val updated = updatePointers(manifest).copy(updatedAtEpochMs = nowEpochMs)
        if (!publishManifestInternal(updated, syncToDisk = shouldSync)) {
            return TransactionOutcome.Rejected("manifest publication failed; prior manifest remains authoritative")
        }
        return TransactionOutcome.Committed(updated, commitPoint = commitPoint)
    }

    /**
     *  [publishSidecarPointers] with the store's standard ONE-shot
     * stale-manifest rebase-retry ([retryOnStaleManifest], seam-tagged), for
     * the resume-hydration seams whose caller can hold a snapshot that predates
     * durable publications performed outside the façade (the background health
     * concurrent manifest publication) — the page-registration write of the
     * adoption path. On a stale-manifest rejection ONLY, [updatePointers] is
     * re-run against the freshly re-read durable manifest — the mutation must
     * be a pure function of the base manifest so the rebase carries every
     * fresh durable field forward. Every other rejection reason is returned
     * as-is; a retry that also fails surfaces the retry's own
     * rejection, exactly like the store's other wrapped seams.
     */
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
         * 13: a newer schema owns the semantics — unusable for
         * planning (artifact = ABSENT for decisions), bytes preserved
         * untouched, never deleted or overwritten by this version.
         */
        data class UnsupportedVersion(val schemaVersion: Int) : OcrCheckpointRead

        /**
         * 17: missing, corrupt, or otherwise invalid. Treated as
         * ABSENT for planning; the page re-enters OCR planning (safe
         * re-derivation) per.
         */
        data object Absent : OcrCheckpointRead
    }

    /** Reads the pointed OCR checkpoint with unknown-version preservation. */
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

    /** Content-addressed `PageOcrCheckpoint` sidecar name under `ocr/`. */
    internal fun ocrCheckpointSidecarName(pageKey: String, contentFingerprint: String): String =
        layout.ocrCheckpointFile(pageKey, contentFingerprint)

    // ------------------------------------------------------------------
    // Generic sidecar reading and publication support for persisted layouts
    // (`layoutPlans` / `colorPreparations`
    // pointers). Mirrors the readOcrCheckpoint/readRunRecord idioms exactly
    // (quarantine on corrupt, unknown-version preservation) and the
    // publishActiveRun sidecar-then-pointer pattern.
    // ------------------------------------------------------------------

    /** Content-addressed `PageLayoutDrawPlan` sidecar name under `layout/`. */
    internal fun layoutPlanSidecarName(pageKey: String, contentFingerprint: String): String =
        layout.layoutPlanFile(pageKey, contentFingerprint)

    /** Content-addressed `AnalysisChunkResult` sidecar name under `analysis/`. */
    internal fun analysisChunkSidecarName(contentFingerprint: String): String =
        layout.analysisChunkFile(contentFingerprint)

    /**
     * Content-addressed `EnvelopePlan` sidecar name under `envelopes/`.
     */
    internal fun envelopePlanSidecarName(contentFingerprint: String): String =
        layout.envelopePlanFile(contentFingerprint)

    /** Content-addressed `ColorStylePreparation` sidecar name under `color/`. */
    internal fun colorPreparationSidecarName(pageKey: String, contentFingerprint: String): String =
        layout.colorPreparationFile(pageKey, contentFingerprint)

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

    /**
     * Builds one immutable JSON sidecar publication for
     * [publishSidecarPointers] through the shared [AtomicChapterDocuments]
     * Json, so callers outside this package can stage
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
     * Reads a generic pointer: well-formedness
     * gate, parse with quarantine on corruption, unknown-version preservation
     * (NEVER quarantined), kind check + semantic validation with quarantine on
     * invalid payloads.
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
     * Prepare re-keyed snapshots without moving manifest pointers. The caller
     * captures the source manifest/pages under its mutex, invokes this on its
     * persistence dispatcher, then revalidates those identities before
     * committing the returned pointer map through [publishSidecarPointers].
     * Every destination is a unique immutable name; an existing destination is
     * reusable only when its decoded value is exactly the same snapshot.
     */
    internal fun preparePageSnapshotRekey(
        manifest: ChapterArtifactManifest,
        moves: Map<String, String>,
        livePages: Map<String, PublishedPageTranslation>,
        operationId: String,
    ): PreparedPageSnapshotRekey? {
        if (moves.isEmpty()) return PreparedPageSnapshotRekey(manifest.pages, emptyMap())
        val movedOldKeys = moves.keys
        val movedNewKeys = moves.values.toSet()
        // Do not merge into an artifact record that is outside this re-key
        // operation. The store's in-memory collision check cannot see dormant
        // manifest entries left by a prior partial lifecycle.
        if (movedNewKeys.any { it in manifest.pages && it !in movedOldKeys }) return null

        val pages = LinkedHashMap<String, PageArtifactRecord>()
        val journalMutations = LinkedHashMap<String, PreparedRekeyJournalMutation>()
        manifest.pages.forEach { (oldKey, record) ->
            val newKey = moves[oldKey] ?: oldKey
            if (newKey in pages) return null
            val isMoved = oldKey in moves
            var candidate = record.candidate
            var committed = record.committed
            var previousCommitted = record.previousCommitted

            if (isMoved) {
                if (candidate != null) {
                    val live = livePages[oldKey] ?: return null
                    if (live.sourceFileName != oldKey) return null
                    val candidateSnapshot = live.toDraft().apply { sourceFileName = newKey }
                    val published = publishRekeySnapshot(
                        snapshot = candidateSnapshot,
                        oldKey = oldKey,
                        newKey = newKey,
                        role = "candidate",
                        generationId = candidate.generationId,
                        operationId = operationId,
                    ) ?: return null
                    candidate = candidate.copy(
                        pageSnapshotFileName = published.fileName,
                        pageSnapshotFingerprint = published.fingerprint,
                    )
                    journalMutations[newKey] = PreparedRekeyJournalMutation(
                        state = published.snapshot.toPublishedPage(),
                        artifactContentHash = published.fingerprint,
                    )
                }

                committed = committed?.let { bundle ->
                    rekeyCommittedSnapshot(
                        bundle = bundle,
                        oldKey = oldKey,
                        newKey = newKey,
                        role = "committed",
                        operationId = operationId,
                        source = record.source?.copy(pageKey = newKey),
                    ) ?: return null
                }
                previousCommitted = previousCommitted?.let { bundle ->
                    rekeyCommittedSnapshot(
                        bundle = bundle,
                        oldKey = oldKey,
                        newKey = newKey,
                        role = "previous",
                        operationId = operationId,
                        source = record.source?.copy(pageKey = newKey),
                    ) ?: return null
                }

                // A page with no active candidate remains on its committed
                // role. Do not synthesize a candidate or treat a pointerless
                // legacy record as a verifiable completed page.
                if (candidate == null && committed?.pageSnapshotFileName != null) {
                    val committedBundle = checkNotNull(committed)
                    val snapshot = readPageSnapshot(committedBundle.pageSnapshotFileName) ?: return null
                    if (snapshot.sourceFileName != newKey) return null
                    val fingerprint = StageFingerprints.pageSnapshot(snapshot)
                    val committedFingerprint = committedBundle.translationFingerprint
                    if (committedFingerprint != null && committedFingerprint != fingerprint) return null
                    journalMutations[newKey] = PreparedRekeyJournalMutation(
                        state = snapshot.toPublishedPage(),
                        artifactContentHash = fingerprint,
                    )
                }

                if (newKey !in journalMutations) {
                    val live = livePages[oldKey] ?: return null
                    if (live.sourceFileName != oldKey) return null
                    val retryableState = live.toDraft().apply { sourceFileName = newKey }
                    // Preserve inventory and the moved page's current logical state, but a
                    // pointerless manifest has no artifact proof. The reducer consumes this
                    // record as an invalid-page marker until a later verified publication.
                    journalMutations[newKey] = PreparedRekeyJournalMutation(
                        state = retryableState.toPublishedPage(),
                        artifactContentHash = null,
                    )
                }
            }

            pages[newKey] = record.copy(
                pageKey = newKey,
                pageVersion = record.pageVersion + if (isMoved) 1L else 0L,
                source = record.source?.let { source -> if (isMoved) source.copy(pageKey = newKey) else source },
                candidate = candidate,
                committed = committed,
                previousCommitted = previousCommitted,
            )
        }
        // Moves without a manifest page entry still need an explicit retryable mutation when
        // this transaction is published for another moved page in the same operation.
        moves.forEach { (oldKey, newKey) ->
            if (newKey !in journalMutations) {
                val live = livePages[oldKey] ?: return null
                if (live.sourceFileName != oldKey) return null
                val retryableState = live.toDraft().apply { sourceFileName = newKey }
                journalMutations[newKey] = PreparedRekeyJournalMutation(
                    state = retryableState.toPublishedPage(),
                    artifactContentHash = null,
                )
            }
        }
        return PreparedPageSnapshotRekey(pages, journalMutations)
    }

    private data class RekeyedSnapshot(
        val fileName: String,
        val fingerprint: String,
        val snapshot: PageTranslation,
    )

    private fun rekeyCommittedSnapshot(
        bundle: CommittedBundleMetadata,
        oldKey: String,
        newKey: String,
        role: String,
        operationId: String,
        source: SourceIdentity?,
    ): CommittedBundleMetadata? {
        val oldFileName = bundle.pageSnapshotFileName
        if (oldFileName == null || bundle.translationFingerprint == null) {
            // A pointer or claimed digest without the paired semantic proof is
            // not sufficient to authenticate a completed re-key destination.
            // Keep the committed role metadata, but leave its snapshot retryable.
            val bundleFingerprint = bundle.bundleFingerprint?.let {
                StageFingerprints.committedBundle(
                    sourceIdentity = source,
                    displayBase = bundle.displayBase,
                    translationFingerprint = null,
                    layoutFingerprint = bundle.layoutFingerprint,
                )
            }
            return bundle.copy(
                bundleFingerprint = bundleFingerprint,
                translationFingerprint = null,
                pageSnapshotFileName = null,
            )
        }
        val original = readPageSnapshot(oldFileName) ?: return null
        if (original.sourceFileName != oldKey) return null
        val originalFingerprint = StageFingerprints.pageSnapshot(original)
        if (bundle.translationFingerprint != originalFingerprint) return null
        val rewritten = original.detachedCopy().apply { sourceFileName = newKey }
        val published = publishRekeySnapshot(
            snapshot = rewritten,
            oldKey = oldKey,
            newKey = newKey,
            role = role,
            generationId = bundle.generationId,
            operationId = operationId,
        ) ?: return null
        val updatedBundleFingerprint = bundle.bundleFingerprint?.let {
            StageFingerprints.committedBundle(
                sourceIdentity = source,
                displayBase = bundle.displayBase,
                translationFingerprint = published.fingerprint,
                layoutFingerprint = bundle.layoutFingerprint,
            )
        }
        return bundle.copy(
            bundleFingerprint = updatedBundleFingerprint,
            translationFingerprint = published.fingerprint,
            pageSnapshotFileName = published.fileName,
        )
    }

    private fun publishRekeySnapshot(
        snapshot: PageTranslation,
        oldKey: String,
        newKey: String,
        role: String,
        generationId: String,
        operationId: String,
    ): RekeyedSnapshot? {
        if (snapshot.sourceFileName != newKey) return null
        val fingerprint = StageFingerprints.pageSnapshot(snapshot)
        val fileName = layout.rekeyedPageSnapshotFile(
            pageKey = newKey,
            role = role,
            generationId = generationId,
            operationId = "$operationId:$oldKey:$newKey:$role:$fingerprint",
        )
        if (!layout.isManagedPath(fileName)) return null
        if (documents.exists(fileName)) {
            // Never overwrite a path that another/stale re-key may already
            // reference. This also makes an exact retry idempotent.
            val existing = io.read(fileName)?.let { bytes ->
                runCatching { documents.json.decodeFromStream<PageTranslation>(bytes.inputStream()) }.getOrNull()
            } ?: return null
            if (existing != snapshot || StageFingerprints.pageSnapshot(existing) != fingerprint) return null
        } else {
            if (!documents.publishJson(fileName, snapshot.detachedCopy())) return null
            val readBack = readPageSnapshot(fileName) ?: return null
            if (readBack != snapshot || StageFingerprints.pageSnapshot(readBack) != fingerprint) return null
        }
        return RekeyedSnapshot(fileName, fingerprint, snapshot.detachedCopy())
    }

    /** Verify the bytes of a referenced legacy-companion cleaned image through its identity sidecar. */
    fun hasVerifiedCleanedImageIdentity(imageName: String, expectedContentSha256: String): Boolean =
        CleanedImageIdentity.verify(io, layout.chapterKey, imageName, expectedContentSha256)

    /**
     * Persists the mutable live candidate in its own immutable sidecar and
     * advances only the candidate pointer. The committed pointer is untouched
     * until [promoteLiveCandidate] succeeds.
     *
     *   /  a stale-manifest CAS rejection (the leading
     * [candidateWriteRejection] check) triggers ONE retry against the freshly
     * re-read durable manifest — this is the store transaction behind the
     * façade's stage-patch/candidate persistence, and the batch resume path
     * must not surface a spurious ARTIFACT_PUBLICATION_FAILED whole-batch
     * abort on a healthy chapter. The whole transaction re-runs against the
     * FRESH manifest, so genuine drift still rejects with its real reason.
     * Every other rejection reason is returned as-is.
     * [persistLiveCandidateAndFailure] delegates here and inherits the retry.
     */
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
        val pageSnapshotFingerprint = StageFingerprints.pageSnapshot(pageSnapshot)
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
            candidate = page.candidate.copy(
                pageSnapshotFileName = fileName,
                pageSnapshotFingerprint = pageSnapshotFingerprint,
            ),
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
     *   /  a stale-manifest CAS rejection (the leading
     * [candidateWriteRejection] check) triggers ONE retry against the freshly
     * re-read durable manifest — the batch resume's candidate-promotion
     * transaction must not surface a spurious ARTIFACT_PUBLICATION_FAILED
     * whole-batch abort on a healthy chapter. The whole transaction re-runs
     * against the FRESH manifest, so genuine drift still rejects with its real
     * reason. Every other rejection reason is returned as-is.
     */
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
    ): TransactionOutcome = promoteLiveCandidateTransaction(
        manifest = manifest,
        pageKey = pageKey,
        generationId = generationId,
        expectedPageVersion = expectedPageVersion,
        expectedDependencyFingerprint = expectedDependencyFingerprint,
        pageSnapshot = pageSnapshot,
        origin = origin,
        sourceIdentity = sourceIdentity,
        nowEpochMs = nowEpochMs,
        persistCandidateSnapshot = true,
        seam = "promoteLiveCandidate",
    )

    /**
     * Fuses the candidate snapshot and terminal promotion into one recoverable publication.
     * Used only when the store already knows this mutation is immediately promotable. The
     * transaction still publishes committed snapshot, generation record, and final manifest in
     * that order; the existing two-step candidate protocol remains for in-progress mutations.
     * If terminal publication fails, it restores the candidate snapshot and pointer so reopening
     * retains the same recoverable work as the two-step path.
     */
    fun promoteLiveCandidateImmediately(
        manifest: ChapterArtifactManifest,
        pageKey: String,
        generationId: String,
        expectedPageVersion: Long,
        expectedDependencyFingerprint: String,
        pageSnapshot: PageTranslation,
        origin: ArtifactOrigin,
        sourceIdentity: SourceIdentity? = null,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): TransactionOutcome = promoteLiveCandidateTransaction(
        manifest = manifest,
        pageKey = pageKey,
        generationId = generationId,
        expectedPageVersion = expectedPageVersion,
        expectedDependencyFingerprint = expectedDependencyFingerprint,
        pageSnapshot = pageSnapshot,
        origin = origin,
        sourceIdentity = sourceIdentity,
        nowEpochMs = nowEpochMs,
        persistCandidateSnapshot = false,
        seam = "promoteLiveCandidateImmediately",
    )

    private fun promoteLiveCandidateTransaction(
        manifest: ChapterArtifactManifest,
        pageKey: String,
        generationId: String,
        expectedPageVersion: Long,
        expectedDependencyFingerprint: String,
        pageSnapshot: PageTranslation,
        origin: ArtifactOrigin,
        sourceIdentity: SourceIdentity?,
        nowEpochMs: Long,
        persistCandidateSnapshot: Boolean,
        seam: String,
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
            persistCandidateSnapshot,
        ),
        callerManifest = manifest,
        seam = seam,
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
            persistCandidateSnapshot,
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
        persistCandidateSnapshot: Boolean,
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
        fun rejectAndRestoreCandidate(reason: String): TransactionOutcome.Rejected {
            if (persistCandidateSnapshot) return TransactionOutcome.Rejected(reason)

            val candidateFile = candidate.pageSnapshotFileName
                ?: layout.candidatePageSnapshotFile(pageKey, generationId)
            val candidateFingerprint = StageFingerprints.pageSnapshot(pageSnapshot)
            if (!documents.publishJson(candidateFile, pageSnapshot.detachedCopy())) {
                return TransactionOutcome.Rejected(
                    "$reason; recovery candidate snapshot publication failed: pageKey=$pageKey",
                )
            }
            val recoverablePage = page.copy(
                source = resolvedPage.source,
                candidate = candidate.copy(
                    pageSnapshotFileName = candidateFile,
                    pageSnapshotFingerprint = candidateFingerprint,
                ),
                pageVersion = page.pageVersion + 1,
            )
            val recoverableManifest = manifest.copy(
                pages = manifest.pages + (pageKey to recoverablePage),
                updatedAtEpochMs = nowEpochMs,
            )
            if (!publishManifestInternal(recoverableManifest)) {
                return TransactionOutcome.Rejected(
                    "$reason; recovery candidate manifest publication failed: pageKey=$pageKey",
                )
            }
            return TransactionOutcome.Rejected(
                reason = reason,
                recoverableCandidateManifest = recoverableManifest,
            )
        }
        if (persistCandidateSnapshot) {
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
            return rejectAndRestoreCandidate("committed page snapshot publication failed: pageKey=$pageKey")
        }
        val pageSnapshotFingerprint = StageFingerprints.pageSnapshot(pageSnapshot)
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
                translationFingerprint = pageSnapshotFingerprint,
                layoutFingerprint = null,
            ),
            displayBase = displayBase,
            translationFingerprint = pageSnapshotFingerprint,
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
            return rejectAndRestoreCandidate("generation record publication failed: generationId=$generationId")
        }
        val promotedTranslation = resolvedPage.translation?.copy(
            status = if (pageSnapshot.isTextlessTerminal) {
                ArtifactStageStatus.TEXTLESS
            } else {
                ArtifactStageStatus.READY
            },
            fingerprint = pageSnapshotFingerprint,
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
            fingerprint = pageSnapshotFingerprint,
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
                    // The legacy two-step path persisted the candidate (+1) before promotion
                    // (+1). Preserve that version transition while omitting the intermediate
                    // candidate snapshot and manifest publication.
                    pageVersion = page.pageVersion + if (persistCandidateSnapshot) 1 else 2,
                )
                ),
            activeCandidateGenerationIds = manifest.activeCandidateGenerationIds - generationId,
            durableFailures = manifest.durableFailures - "$pageKey:${ArtifactStage.TRANSLATION.name}",
            updatedAtEpochMs = nowEpochMs,
        )
        if (!publishManifestInternal(updated)) {
            return rejectAndRestoreCandidate("manifest publication failed; committed pointer unchanged")
        }
        return TransactionOutcome.Committed(updated, generationId, commitPoint = CommitPoint.PAGE_TERMINAL_PROMOTION)
    }

    /** Cancels a live candidate while retaining the committed pointer. */
    fun cancelLiveCandidate(
        manifest: ChapterArtifactManifest,
        pageKey: String,
        generationId: String,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): TransactionOutcome = cancelCandidate(manifest, pageKey, generationId, nowEpochMs)

    /** Explicit user reset: remove committed/candidate pointers durably. */
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
     * This records the candidate in the artifact manifest. Preconditions bind
     * the caller to the current page version and dependency fingerprint (stale
     * workers are rejected).
     *
     *   /  a stale-manifest CAS rejection (the >8-page open
     * path's background health verify republishing after the façade cached its
     * copy) triggers ONE retry against the freshly re-read durable manifest —
     * the batch resume's first candidate open must not surface a spurious
     * ARTIFACT_PUBLICATION_FAILED whole-batch abort on a healthy chapter. The
     * whole transaction (identity checks included) re-runs against the FRESH
     * manifest, so genuine drift still rejects — with a non-stale reason, on
     * the retry attempt. Every other rejection reason is returned as-is.
     */
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
        var candidateOpenState: CandidateOpenState? = null
        val generationId = when {
            existing != null &&
                existing.origin == origin &&
                existing.origin != ArtifactOrigin.LEGACY &&
                existing.dependencyFingerprint == dependencyFingerprint -> {
                candidateOpenState = CandidateOpenState.Reused(
                    pageKey = pageKey,
                    dependencyFingerprint = dependencyFingerprint,
                )
                existing.generationId
            }
            existing != null && existing.origin == ArtifactOrigin.LEGACY -> newGenerationId(pageKey, nowEpochMs)
            existing != null -> {
                candidateOpenState = if (existing.origin == origin) {
                    CandidateOpenState.FingerprintMismatch(
                        pageKey = pageKey,
                        expectedFingerprint = dependencyFingerprint,
                        activeFingerprint = existing.dependencyFingerprint,
                    )
                } else {
                    CandidateOpenState.CandidateAlreadyActive(
                        pageKey = pageKey,
                        requestedOrigin = origin,
                        activeOrigin = existing.origin,
                    )
                }
                return TransactionOutcome.Rejected(
                    "candidate already active with a different dependency fingerprint: pageKey=$pageKey",
                    candidateOpenState = candidateOpenState,
                )
            }
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
            return TransactionOutcome.Rejected(
                "generation record publication failed: generationId=$generationId",
                candidateOpenState = candidateOpenState,
            )
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
            return TransactionOutcome.Rejected(
                "manifest publication failed; prior manifest remains authoritative",
                candidateOpenState = candidateOpenState,
            )
        }
        return TransactionOutcome.Committed(
            updated,
            generationId,
            candidateOpenState = candidateOpenState,
        )
    }

    /**
     * Cancels a candidate: removes only candidate-owned metadata/files, keeps
     * the committed bundle (and its retained previous generation) untouched
     * (lifecycle contract §13). Files are reclaimed exclusively through store
     * reachability after the manifest update — never by name pattern alone.
     */
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
        // Delete only the known files unlinked by this cancellation. A full
        // SAF tree sweep per page made relaunch slow on large chapters;
        // cross-page orphans stay with the chapter-boundary sweep.
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
     *   stable reason prefix of the stale-manifest CAS rejection. The
     * one-shot retry on the first-publication seams ([publishActiveRun],
     * [checkpointOcr], [openCandidate], [retireActiveRun],
     * [persistLiveCandidate] — including [persistLiveCandidateAndFailure] —
     * and [promoteLiveCandidate]) keys on this prefix — every OTHER rejection
     * reason (identity drift, publication failure, future-schema guard) must
     * keep failing the caller exactly as before.
     */
    private val staleManifestRejectionReason = "stale manifest snapshot"

    private fun staleManifestRejection(manifest: ChapterArtifactManifest): String? {
        val durable = casBaselineManifest()
            ?: return "manifest is not durable: chapter=${layout.chapterKey}"
        return if (durable == manifest) {
            null
        } else {
            "$staleManifestRejectionReason: chapter=${layout.chapterKey}"
        }
    }

    /** The rejection reason iff [outcome] was rejected BY the stale-manifest CAS specifically. */
    private fun TransactionOutcome.staleManifestRejectionOrNull(): String? =
        (this as? TransactionOutcome.Rejected)
            ?.reason
            ?.takeIf { it.startsWith(staleManifestRejectionReason) }

    /**
     *  stale-manifest CAS detection exposed to the one seam whose
     * publication lives OUTSIDE this store ([EnvelopePlanPublication.publish]):
     * the CAS there keys on the same [staleManifestRejectionReason] prefix
     * so every other rejection reason keeps failing exactly as before
     *
     */
    internal fun isStaleManifestRejection(outcome: TransactionOutcome): Boolean =
        outcome.staleManifestRejectionOrNull() != null

    /**
     *   one-shot stale-manifest retry for the flagged Batch lane's
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
     *  the same race aborted the whole batch RESUME at its other
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
     * one-previous bundles, their generation records, and referenced stage
     * sidecars — and deletes every other
     * file under the managed artifact tree using store reachability, never
     * filename age alone. All comparisons use canonical root-relative paths;
     * only contained managed paths are ever deleted. Legacy documents outside
     * the artifact tree (flat JSON, summary, companion
     * images) are never touched.
     */
    fun reconcileRetention(
        manifest: ChapterArtifactManifest,
        stagedReachable: Set<String> = emptySet(),
    ): RetentionResult =
        retentionSweep.reconcileRetention(manifest, stagedReachable)

    /**
     * Collects retention candidates outside the facade mutex. It is pure over
     * its inputs (the passed
     * manifest + the immutable layout/IO) and takes minutes of SAF round-trips
     * on real storage; taking either the facade mutex or another state lock
     * during it froze every page lease in the pipeline (jdb thread dump,
     * 2026-09-15: 20+ minute batch stall on a 70-page chapter). Pair with
     * [deleteVerifiedRetentionCandidates], which re-verifies each candidate
     * against the live manifest under the facade Mutex.
     */
    fun collectRetentionCandidates(
        manifest: ChapterArtifactManifest,
        stagedReachable: Set<String> = emptySet(),
    ): Set<String> = retentionSweep.collectOrphanCandidates(manifest, stagedReachable)

    /**
     * Runs the long filesystem crawl without a store lock, then delegates the
     * bounded live-manifest recheck/deletion to the owning store boundary.
     * The store supplies those two boundaries because it owns the mutable
     * manifest snapshot and must serialize deletion against page publication.
     */
    suspend fun reconcileRetentionOffLock(
        manifestSnapshot: suspend () -> ChapterArtifactManifest?,
        deleteAgainstLiveManifest: suspend (Collection<String>) -> RetentionResult,
    ): RetentionResult? {
        val manifest = manifestSnapshot() ?: return null
        val startedAt = System.currentTimeMillis()
        val candidates = withContext(Dispatchers.IO) {
            collectRetentionCandidates(manifest)
        }
        val result = deleteAgainstLiveManifest(candidates)
        logRetentionResult(candidates.size, result, startedAt)
        return result
    }

    /** Deduplicated fire-and-forget boundary sweep; the store keeps the live recheck lock. */
    fun reconcileRetentionAsync(
        manifestSnapshot: suspend () -> ChapterArtifactManifest?,
        deleteAgainstLiveManifest: suspend (Collection<String>) -> RetentionResult,
    ) {
        if (!retentionInFlight.compareAndSet(false, true)) return
        retentionScope.launch {
            try {
                reconcileRetentionOffLock(manifestSnapshot, deleteAgainstLiveManifest)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                logcat(LogPriority.ERROR, failure) {
                    "TachiyomiAT retention sweep failed: chapter=${layout.chapterKey}"
                }
            } finally {
                retentionInFlight.set(false)
            }
        }
    }

    /** Cancels and joins every asynchronous sweep at a store teardown boundary. */
    suspend fun cancelRetentionWorkAndJoin() = retentionScopeJob.cancelAndJoin()

    private fun logRetentionResult(
        candidateCount: Int,
        result: RetentionResult,
        startedAt: Long,
    ) {
        logcat(LogPriority.INFO) {
            "TachiyomiAT retention sweep: candidates=$candidateCount " +
                "deleted=${result.deletedCount} in ${System.currentTimeMillis() - startedAt}ms"
        }
    }

    /** Re-verifies candidates and deletes them under the facade mutex. */
    fun deleteVerifiedRetentionCandidates(
        candidateOrphans: Collection<String>,
        manifest: ChapterArtifactManifest,
        stagedReachable: Set<String> = emptySet(),
    ): RetentionResult =
        retentionSweep.deleteVerifiedOrphans(candidateOrphans, manifest, stagedReachable)

    /**
     * Resolves the verification manifest under the facade mutex after the
     * off-lock candidate crawl. It reads the
     * verification manifest ITSELF, under the facade Mutex, from the durable
     * artifact tree. A caller-supplied manifest captured outside this monitor
     * (e.g. the facade's cached snapshot) can lag an in-flight publication by
     * its whole body — the crawl may have listed a sidecar whose file was
     * already written while its manifest pointer was not yet installed — and
     * verifying against that stale graph deletes a sidecar the just-completed
     * publication points at (observed: the  COMPLETE run record vanishing
     * between its publication and the next read, leaving a dangling
     * `activeRun`). This monitor serializes with every publication's manifest
     * rotation, so a manifest read here is never older than any installed
     * pointer. A chapter with no durable manifest verifies nothing.
     */
    fun deleteVerifiedRetentionCandidates(
        candidateOrphans: Collection<String>,
    ): RetentionResult {
        val live = readManifest() ?: return RetentionResult(0, emptyList())
        return retentionSweep.deleteVerifiedOrphans(candidateOrphans, live)
    }

    /**
     * Reclaims explicitly unlinked artifact files without a full tree crawl.
     * Files referenced by [stagedReachable] or [durableManifest] are preserved
     * so a concurrent publication cannot delete a still-reachable document.
     */
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
     * Caches whether a supported schema version needs normalization. Future
     * schema checks remain fresh so a newer document is never hidden by a
     * stale normalization decision.
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
     * Normalizes supported older manifests in memory so their next
     * publication uses the current schema version. This prevents new pointer
     * fields from being written into a schema that an older build might strip.
     * Future schemas remain untouched and are rejected by the schema guard.
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
    //  adoption-write manifest coalescing for the batch-resume
    // rebuild. The resume hydration loop (`buildEnvelopeDispatchWork` →
    // `adoptCheckpointSnapshot` → the façade's open/persist/promote candidate
    // transactions) republished the FULL manifest JSON once or more PER PAGE
    // (~340 rewrites of a ~360KB document on a 206-page chapter, ~1.3s apart),
    // contributing to a main-thread ANR. While a coalescing window is open
    // (the coordinator brackets the rebuild in begin/try/finally-end), each
    // manifest publication only STASHES the intended manifest (last
    // writer wins — the transaction chain is serialized by the store monitor,
    // so the stash is always the newest intended state); the durable rewrite
    // happens at most once per [manifestCoalescingFlushEvery] stashed
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
    // open the baseline is the durable file exactly as before.
    // ------------------------------------------------------------------

    /** Bounded unflushed state: a durable manifest rewrite at most every N stashed publications. */
    private val manifestCoalescingFlushEvery = 32

    private var manifestCoalescingDepth = 0
    private var coalescedManifest: ChapterArtifactManifest? = null
    private var coalescedSyncToDisk = false
    private var coalescedPublications = 0

    /**
     * Opens one coalescing window. Callers MUST close it with
     * [endManifestCoalescing] (a try/finally bracket), which performs the
     * mandatory final flush. Nesting is counted; the outermost end flushes.
     */
    fun beginManifestCoalescing() {
        manifestCoalescingDepth += 1
    }

    /**
     * Closes one coalescing window and flushes the stashed manifest when the
     * OUTERMOST window ends ( the rebuild always ends coherent —
     * `store.artifactManifest` tracks each Committed manifest, so after the
     * final flush the façade equals the durable file). A failed final flush is
     * logged and dropped: the transactions already reported Committed, and the
     * durable state heals on the next resume via the checkpoint sidecars.
     */
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
     *  the manifest CAS baseline — the coalesced (intended) manifest
     * while a window is open, else the durable file. Must only be called while
     * the facade Mutex is held by the owning ChapterTranslationStore.
     */
    private fun casBaselineManifest(): ChapterArtifactManifest? =
        coalescedManifest ?: readManifest()

    //  callers hold the owning ChapterTranslationStore facade Mutex.
    // The coalescing stash therefore shares the one mutable-state lock with
    // every transaction. Lock order is facade Mutex → per-name document lock;
    // document/open locks never acquire the facade Mutex.
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
            if (coalescedPublications % manifestCoalescingFlushEvery == 0) {
                //  bounded unflushed state — flush through the normal
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

    internal fun backupName(): String = AtomicChapterDocuments.backupNameFor(layout.manifestFileName)
}

/** Stage sidecar lookup for one page record. */
internal fun PageArtifactRecord.stage(stage: ArtifactStage): StageArtifactRecord? = when (stage) {
    ArtifactStage.DETECTION -> detection
    ArtifactStage.OCR -> ocr
    ArtifactStage.INPAINT -> inpaint
    ArtifactStage.TRANSLATION -> translation
    ArtifactStage.LAYOUT -> layout
}
