package eu.kanade.translation.persistence

import eu.kanade.translation.model.StageFingerprints

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
 * generalized over the document type for persisted sidecar documents.
 */
typealias RunRecordRead = ChapterProgressRecords.RunRecordRead
typealias OcrCheckpointRead = ChapterProgressRecords.OcrCheckpointRead
typealias RecordOutcome = ChapterProgressRecords.RecordOutcome
typealias CheckpointRecoveryRead = ChapterProgressRecords.CheckpointRecoveryRead
typealias CandidateOpenState = ChapterCandidateTransactions.CandidateOpenState

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
    internal val documents: AtomicChapterDocuments,
    internal val layout: ChapterArtifactLayout,
) {
    internal val io: ChapterDocumentIo get() = documents.rawIo()

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

    val progress: ChapterProgressRecords = ChapterProgressRecords(documents, layout, this)
    val candidates: ChapterCandidateTransactions = ChapterCandidateTransactions(documents, layout, this)


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
            val recoverInterruptedStage = ReaderEntryTrace.begin("store.recoverInterrupted", null)
            val recovered = candidates.recoverInterruptedStages(existing)
            recoverInterruptedStage.end()
            // P3A: the open-time backup deletion was removed. The retained
            // `.bak` is the chapter's crash-recovery copy and repair/retry
            // evidence; the next manifest rotation replaces it, and explicit
            // chapter teardown remains the only other remover. Interrupted-
            // stage recovery (when it published) already rotated a fresh
            // backup into place.
            return LoadResult(recovered)
        }

        val created = ChapterArtifactManifest(
            chapterKey = layout.chapterKey,
            updatedAtEpochMs = System.currentTimeMillis(),
        )
        when (val receipt = publishDestructiveManifest(created)) {
            is DestructiveManifestReceipt.Complete -> {}
            is DestructiveManifestReceipt.PrimaryAdvancedBackupIncomplete -> logcat(LogPriority.WARN) {
                "TachiyomiAT artifact manifest created without a verified backup: " +
                    "chapter=${layout.chapterKey} reason=${receipt.reason}"
            }
            is DestructiveManifestReceipt.NotPublished -> logcat(LogPriority.WARN) {
                "TachiyomiAT artifact manifest creation failed: " +
                    "chapter=${layout.chapterKey} reason=${receipt.reason}"
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
     * P3A receipt of a destructive or initialization manifest publication:
     * the publication must leave a verified same-state backup, because
     * restoring a stale previous generation after a later primary corruption
     * would resurrect state the destructive operation removed.
     */
    sealed interface DestructiveManifestReceipt {
        /** Primary and backup verified same-state. */
        data class Complete(val manifest: ChapterArtifactManifest) : DestructiveManifestReceipt

        /**
         * Primary advanced but the same-state backup copy is unverified —
         * typed incomplete, never rolled back, never silent. The previous
         * generation may still occupy the backup name.
         */
        data class PrimaryAdvancedBackupIncomplete(
            val manifest: ChapterArtifactManifest,
            val reason: String,
        ) : DestructiveManifestReceipt

        /** Nothing published; the prior manifest remains authoritative. */
        data class NotPublished(val reason: String) : DestructiveManifestReceipt
    }

    /**
     * Checked destructive/initialization publication: same future-primary and
     * future-backup guards as [publishManifestInternal], then ONE
     * [AtomicChapterDocuments.publishVerifiedJson] publication that leaves a
     * verified same-state backup. Never routed through the coalescing stash —
     * stash acceptance is never durable success — but while a window is open
     * the stash baseline is refreshed to the published state so the owning
     * window cannot later overwrite it with an older intended manifest (the
     * store monitor serializes the whole chain).
     */
    internal fun publishDestructiveManifest(
        manifest: ChapterArtifactManifest,
        syncToDisk: Boolean = false,
    ): DestructiveManifestReceipt {
        if (readManifestDocument(layout.manifestFileName)?.schemaVersion
                ?.let { it > ChapterArtifactManifest.SCHEMA_VERSION } == true
        ) {
            return DestructiveManifestReceipt.NotPublished(
                "future-schema primary preserved: chapter=${layout.chapterKey}",
            )
        }
        if (futureBackupPresent()) {
            return DestructiveManifestReceipt.NotPublished(
                "future-schema backup preserved: chapter=${layout.chapterKey}",
            )
        }
        val stamped = stampChapterKey(manifest)
        return when (val verified = documents.publishVerifiedJson(layout.manifestFileName, stamped, syncToDisk)) {
            is AtomicChapterDocuments.VerifiedPublishReceipt.Complete ->
                DestructiveManifestReceipt.Complete(stamped)
            is AtomicChapterDocuments.VerifiedPublishReceipt.BackupNotVerified ->
                DestructiveManifestReceipt.PrimaryAdvancedBackupIncomplete(stamped, verified.reason)
            AtomicChapterDocuments.VerifiedPublishReceipt.NotPublished ->
                DestructiveManifestReceipt.NotPublished(
                    "manifest publication failed; prior manifest remains authoritative",
                )
        }
    }

    /**
     * P3A: re-presents a recorded [IncompletePublicationReceipt] for one
     * checked retry. The hard guarantee is the store's stale-manifest CAS in
     * whatever ordinary transaction [retry] runs; this entry adds the honest
     * fast path — when the durable manifest has moved past the receipt's
     * [IncompletePublicationReceipt.actualAdvancedManifest], a NEWER state
     * owns the chapter and the older receipt is retired WITHOUT any write,
     * because finishing it would overwrite that newer state. The retry
     * receives the receipt's durably restored candidate manifest when one
     * exists, else the actually-advanced manifest, so the CAS baseline it
     * presents is the one the receipt left behind.
     */
    fun finishIncompletePublication(
        receipt: IncompletePublicationReceipt,
        retry: (ChapterArtifactManifest) -> TransactionOutcome,
    ): TransactionOutcome {
        val durable = readManifest()
        if (durable == null) {
            return TransactionOutcome.Rejected("manifest is not durable: chapter=${layout.chapterKey}")
        }
        val presented = receipt.recoverableCandidateManifest ?: receipt.actualAdvancedManifest
        if (receipt.actualAdvancedManifest != null && durable != receipt.actualAdvancedManifest) {
            return TransactionOutcome.Rejected(
                "newer artifact state owns the chapter; older publication receipt retired without write: " +
                    "chapter=${layout.chapterKey}",
            )
        }
        return retry(presented ?: durable)
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
            val candidateOpenState: CandidateOpenState? = null,
            /**
             * P3A destructive-checked publications only: set when the manifest
             * publication physically succeeded but the same-state backup copy
             * could not be verified. Null keeps the plain Committed contract.
             */
            val unverifiedBackupReason: String? = null,
        ) : TransactionOutcome

        data class Rejected(
            val reason: String,
            val candidateOpenState: CandidateOpenState? = null,
            /** Candidate state durably restored when an immediate terminal publication failed. */
            val recoverableCandidateManifest: ChapterArtifactManifest? = null,
            /**
             * P3A: typed primary-advanced/replica-failed evidence when durable
             * sidecar artifacts exist without the checked manifest commit
             * (null = unchanged rejection: nothing advanced).
             */
            val incompletePublication: IncompletePublicationReceipt? = null,
        ) : TransactionOutcome
    }

    /**
     * P3A typed evidence for the P1 three-way outcome's incomplete branch:
     * durable sidecar files (or a durably restored candidate manifest)
     * advanced while the checked manifest commit for the intended state did
     * not complete. Never a rollback and never a success — orphan sidecar
     * files remain until retention reclaims them, and a caller may re-present
     * the receipt through [finishIncompletePublication]. [baselineManifest]
     * is the CAS baseline the failed attempt ran against and
     * [actualAdvancedManifest] is the manifest actually durable when the
     * receipt was produced (may be a candidate-restored state, not the
     * intended one); finishing after the durable state moved past
     * [actualAdvancedManifest] is refused — a newer state is never
     * overwritten to finish an older receipt.
     */
    data class IncompletePublicationReceipt(
        val baselineManifest: ChapterArtifactManifest?,
        val actualAdvancedManifest: ChapterArtifactManifest?,
        /** Sidecar files durably written by the attempt without their pointers. */
        val advancedFiles: List<String>,
        val failureReason: String,
        /** Durably restored candidate state a retry may re-present, when one exists. */
        val recoverableCandidateManifest: ChapterArtifactManifest? = null,
    )


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
        // P3A: every durably written sidecar is recorded so a later failure
        // carries the actual orphan evidence, not a bare rejection.
        val advancedFiles = mutableListOf<String>()
        sidecars.forEach { sidecar ->
            if (!sidecar.publish()) {
                val reason = "sidecar publication failed: file=${sidecar.fileName}"
                return if (advancedFiles.isEmpty()) {
                    TransactionOutcome.Rejected(reason)
                } else {
                    TransactionOutcome.Rejected(
                        reason,
                        incompletePublication = IncompletePublicationReceipt(
                            baselineManifest = manifest,
                            actualAdvancedManifest = readManifest(),
                            advancedFiles = advancedFiles.toList(),
                            failureReason = reason,
                        ),
                    )
                }
            }
            advancedFiles += sidecar.fileName
        }
        val updated = updatePointers(manifest).copy(updatedAtEpochMs = nowEpochMs)
        if (!publishManifestInternal(updated)) {
            val reason = "manifest publication failed; prior manifest remains authoritative"
            return TransactionOutcome.Rejected(
                reason,
                incompletePublication = IncompletePublicationReceipt(
                    baselineManifest = manifest,
                    actualAdvancedManifest = readManifest(),
                    advancedFiles = advancedFiles.toList(),
                    failureReason = reason,
                ),
            )
        }
        return TransactionOutcome.Committed(updated)
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
        seam: String,
    ): TransactionOutcome = retryOnStaleManifest<TransactionOutcome>(
        firstAttempt = publishSidecarPointers(
            manifest = manifest,
            sidecars = sidecars,
            updatePointers = updatePointers,
            nowEpochMs = nowEpochMs,
        ),
        callerManifest = manifest,
        seam = seam,
    ) { fresh ->
        publishSidecarPointers(
            manifest = fresh,
            sidecars = sidecars,
            updatePointers = updatePointers,
            nowEpochMs = nowEpochMs,
        )
    }

    // ------------------------------------------------------------------
    // Generic sidecar reading and publication support. Mirrors the
    // readOcrCheckpoint/readRunRecord idioms exactly (quarantine on corrupt,
    // unknown-version preservation) and the publishActiveRun
    // sidecar-then-pointer pattern.
    // ------------------------------------------------------------------

    /**
     * Content-addressed `EnvelopePlan` sidecar name under `envelopes/`.
     */
    internal fun envelopePlanSidecarName(contentFingerprint: String): String =
        layout.envelopePlanFile(contentFingerprint)

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

    /** Verify the bytes of a referenced legacy-companion cleaned image through its identity sidecar. */
    fun hasVerifiedCleanedImageIdentity(imageName: String, expectedContentSha256: String): Boolean =
        CleanedImageIdentity.verify(io, layout.chapterKey, imageName, expectedContentSha256)


    internal fun newGenerationId(pageKey: String, nowEpochMs: Long): String =
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

    internal fun staleManifestRejection(manifest: ChapterArtifactManifest): String? {
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
    internal inline fun <T> retryOnStaleManifest(
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

    internal fun futureBackupPresent(): Boolean =
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

    /**
     * Manifest CAS baseline — the durable file. Must only be called while
     * the facade Mutex is held by the owning ChapterTranslationStore.
     */
    internal fun casBaselineManifest(): ChapterArtifactManifest? = readManifest()

    // Callers hold the owning ChapterTranslationStore facade Mutex.
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
