package eu.kanade.translation.persistence

import eu.kanade.translation.diagnostics.ReaderEntryTrace
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageFingerprints
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.isTextlessTerminal
import eu.kanade.translation.model.PageDisplayState
import eu.kanade.translation.model.detachedCopy
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import eu.kanade.translation.persistence.ChapterArtifactEngine.SidecarPublication
import eu.kanade.translation.persistence.ChapterArtifactEngine.TransactionOutcome
import kotlinx.serialization.json.decodeFromStream
class ChapterProgressRecords internal constructor(
    private val documents: AtomicChapterDocuments,
    private val layout: ChapterArtifactLayout,
    private val engine: ChapterArtifactEngine,
) {
    private val io: ChapterDocumentIo get() = documents.rawIo()

    /** Outcome of a durable-failure record attempt; only [Stored] is durable. */
    sealed interface RecordOutcome {
        data class Stored(val manifest: ChapterArtifactManifest) : RecordOutcome
        data class NotStored(val reason: String) : RecordOutcome
    }

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
    ): TransactionOutcome = engine.retryOnStaleManifest<TransactionOutcome>(
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
        return engine.publishSidecarPointers(
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
    ): TransactionOutcome = engine.retryOnStaleManifest<TransactionOutcome>(
        firstAttempt = retireActiveRunOnce(manifest, reason, nowEpochMs),
        callerManifest = manifest,
        seam = "retireActiveRun",
    ) { fresh -> retireActiveRunOnce(fresh, reason, nowEpochMs) }

    private fun retireActiveRunOnce(
        manifest: ChapterArtifactManifest,
        reason: String,
        nowEpochMs: Long,
    ): TransactionOutcome {
        engine.staleManifestRejection(manifest)?.let { return TransactionOutcome.Rejected(it) }
        if (manifest.activeRun == null) {
            return TransactionOutcome.Committed(manifest)
        }
        val updated = manifest.copy(activeRun = null, updatedAtEpochMs = nowEpochMs)
        if (!engine.publishManifestInternal(updated)) {
            logcat(LogPriority.WARN) {
                "TachiyomiAT artifact active run retirement publish failed; prior manifest retained: " +
                    "chapter=${layout.chapterKey} reason=$reason"
            }
            return TransactionOutcome.Rejected("manifest publication failed; active run pointer unchanged")
        }
        return TransactionOutcome.Committed(updated)
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
    ): TransactionOutcome = engine.retryOnStaleManifest<TransactionOutcome>(
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
        engine.staleManifestRejection(manifest)?.let { return TransactionOutcome.Rejected(it) }
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
                documents.publishJson<PageTranslation>(snapshotFileName, ocrSnapshotCopy)
            },
            SidecarPublication(checkpointFileName, checkpoint.ocrContentFingerprint) {
                documents.publishJson<PageOcrCheckpoint>(checkpointFileName, checkpoint)
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
                    val successorId = engine.newGenerationId(pageKey, nowEpochMs)
                    successorGenerationId = successorId
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
        val outcome = engine.publishSidecarPointers(
            manifest = manifest,
            sidecars = sidecars,
            updatePointers = { updated },
            nowEpochMs = nowEpochMs,
        )
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
                    val retention = engine.deleteKnownOrphans(orphans, durableManifest = outcome.manifest)
                    TransactionOutcome.Committed(
                        outcome.manifest,
                        successorGenerationId ?: outcome.generationId,
                        retention.deletedNames,
                    )
                } else {
                    outcome
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
        inpaintMaskRevision = checkpoint.inpaintMaskRevision,
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
        if (engine.futureBackupPresent()) {
            return RecordOutcome.NotStored("future-schema backup present; refusing to publish")
        }
        val key = "${failure.pageKey}:${failure.stage.name}"
        val updated = manifest.copy(
            durableFailures = manifest.durableFailures + (key to failure),
            updatedAtEpochMs = System.currentTimeMillis(),
        )
        if (!engine.publishManifestInternal(updated)) {
            return RecordOutcome.NotStored("manifest publication failed; prior manifest remains authoritative")
        }
        return RecordOutcome.Stored(updated)
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

    /**
     * P3B: the ONE classified checkpoint recovery read, shared by the store's
     * fenced [eu.kanade.translation.persistence.ChapterTranslationStore]
     * hydration and the opener's publication-free reader fallback. Verifies
     * the checkpoint's own provenance — readable sidecar, readable OCR page
     * snapshot whose semantic fingerprint equals the pointer's recorded
     * fingerprint, OCR content fingerprint recomputed over that snapshot,
     * complete source identity matching [observedSourceSha256], and a READY
     * checkpointed OCR state. Every rejection is classified with a stable
     * reason; nothing is written, quarantined, or deleted by this read.
     */
    sealed interface CheckpointRecoveryRead {
        data class Usable(
            val checkpoint: PageOcrCheckpoint,
            val ocrSnapshot: PageTranslation,
        ) : CheckpointRecoveryRead

        data class Unusable(val reason: String) : CheckpointRecoveryRead
    }

    fun readRecoveryCheckpoint(
        pointer: SidecarPointer,
        observedSourceSha256: String?,
    ): CheckpointRecoveryRead {
        val checkpoint = when (val read = readOcrCheckpoint(pointer)) {
            is OcrCheckpointRead.Usable -> read.checkpoint
            is OcrCheckpointRead.UnsupportedVersion ->
                return CheckpointRecoveryRead.Unusable("checkpoint schema unsupported: ${read.schemaVersion}")
            OcrCheckpointRead.Absent -> return CheckpointRecoveryRead.Unusable("checkpoint sidecar unreadable")
        }
        val ocrSnapshot = engine.readPageSnapshot(checkpoint.ocrPageSnapshotPointer.fileName)
            ?: return CheckpointRecoveryRead.Unusable("checkpoint OCR page snapshot missing")
        // OCR stage hash is not automatically the page snapshot hash: both
        // fingerprints are verified against their own recorded values.
        if (StageFingerprints.pageSnapshot(ocrSnapshot) != checkpoint.ocrPageSnapshotPointer.contentFingerprint) {
            return CheckpointRecoveryRead.Unusable("checkpoint page snapshot fingerprint mismatch")
        }
        val contentFingerprint = recoveryOcrContentFingerprint(ocrSnapshot, checkpoint)
        if (contentFingerprint != checkpoint.ocrContentFingerprint) {
            return CheckpointRecoveryRead.Unusable("checkpoint OCR content fingerprint mismatch")
        }
        if (!checkpoint.sourceIdentity.isComplete) {
            return CheckpointRecoveryRead.Unusable("incomplete source identity for checkpoint")
        }
        val observedSha = observedSourceSha256?.takeIf(String::isSha256Hex)
        if (observedSha == null || observedSha != checkpoint.sourceIdentity.sha256) {
            return CheckpointRecoveryRead.Unusable("checkpoint source identity mismatch")
        }
        if (ocrSnapshot.ocrStatus != StageStatus.READY) {
            return CheckpointRecoveryRead.Unusable("checkpointed OCR state is not READY")
        }
        return CheckpointRecoveryRead.Usable(checkpoint, ocrSnapshot)
    }

    /**
     * The semantic OCR content identity of a checkpoint's stored snapshot,
     * recomputed over the snapshot's own records. Shared by the store
     * hydration and the reader recovery fallback so both sides of any drift
     * comparison use one builder.
     */
    internal fun recoveryOcrContentFingerprint(
        ocrSnapshot: PageTranslation,
        checkpoint: PageOcrCheckpoint,
    ): String = StageFingerprints.pageOcrContentFingerprint(
        pageKey = checkpoint.pageKey,
        naturalPageIndex = checkpoint.naturalPageIndex,
        sourceSha256 = ocrSnapshot.sourceFingerprint.orEmpty(),
        sourceWidth = ocrSnapshot.imgWidth.toInt(),
        sourceHeight = ocrSnapshot.imgHeight.toInt(),
        sourceOrientation = checkpoint.sourceIdentity.orientation.orEmpty(),
        detectionFingerprint = ocrSnapshot.detectionFingerprint,
        ocrFingerprint = ocrSnapshot.ocrFingerprint.orEmpty(),
        textless = ocrSnapshot.isTextlessTerminal,
        inpaintMaskRevision = checkpoint.inpaintMaskRevision,
        blocks = StageFingerprints.pageOcrContentBlocks(ocrSnapshot),
        inpaintMaskBoxes = ocrSnapshot.inpaintMaskBoxes,
    )

    /** Content-addressed OCR-complete page snapshot sidecar name (existing stage layout). */
    internal fun ocrStageSnapshotName(pageKey: String, fingerprint: String): String =
        layout.stageArtifactFile(pageKey, ArtifactStage.OCR, fingerprint)

    /** Content-addressed `PageOcrCheckpoint` sidecar name under `ocr/`. */
    internal fun ocrCheckpointSidecarName(pageKey: String, contentFingerprint: String): String =
        layout.ocrCheckpointFile(pageKey, contentFingerprint)


}
