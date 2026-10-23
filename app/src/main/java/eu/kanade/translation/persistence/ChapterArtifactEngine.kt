package eu.kanade.translation.persistence

import eu.kanade.translation.diagnostics.ReaderEntryTrace
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.PublishedPageTranslation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
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
typealias CandidateOpenState = ChapterCandidateTransactions.CandidateOpenState

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

    data class LoadResult(
        val manifest: ChapterArtifactManifest,
    )

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
        ) : TransactionOutcome
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
        val updated = updatePointers(manifest).copy(updatedAtEpochMs = nowEpochMs)
        if (!publishManifestInternal(updated)) {
            return TransactionOutcome.Rejected("manifest publication failed; prior manifest remains authoritative")
        }
        return TransactionOutcome.Committed(updated)
    }

    // ------------------------------------------------------------------
    // Generic sidecar publication support.
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

    /** Reads a complete live-store page snapshot referenced by a manifest pointer. */
    fun readPageSnapshot(fileName: String?): PageTranslation? =
        fileName?.let { documents.readValidated<PageTranslation>(it) }

    /** Verify the bytes of a referenced legacy-companion cleaned image through its identity sidecar. */
    fun hasVerifiedCleanedImageIdentity(imageName: String, expectedContentSha256: String): Boolean =
        CleanedImageIdentity.verify(io, layout.chapterKey, imageName, expectedContentSha256)

    internal fun newGenerationId(pageKey: String, nowEpochMs: Long): String =
        "g-$nowEpochMs-${layout.pageSegment(pageKey).takeLast(24)}"

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

    internal fun isStaleManifestRejection(reason: String): Boolean =
        reason.split(": ").any { it == staleManifestRejectionReason }

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

    /** Resolves the live verification manifest under the publication mutex. */
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

    // Candidate transactions compare against the persisted manifest and return
    // a stale-snapshot rejection for callers to rebase on.

    /** Manifest CAS baseline. Call only while the owning store mutex is held. */
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
