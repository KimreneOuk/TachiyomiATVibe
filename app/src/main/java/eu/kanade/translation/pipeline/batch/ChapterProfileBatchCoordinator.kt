package eu.kanade.translation.pipeline.batch

import eu.kanade.translation.ChapterTranslationStore
import eu.kanade.translation.CheckpointOcrResult
import eu.kanade.translation.PageWriteOrigin
import eu.kanade.translation.artifact.ArtifactDocumentJson
import eu.kanade.translation.artifact.ArtifactStage
import eu.kanade.translation.artifact.ArtifactStageStatus
import eu.kanade.translation.artifact.ChapterArtifactStore
import eu.kanade.translation.artifact.ChapterAttemptLedgerDocument
import eu.kanade.translation.artifact.ChapterRunRecord
import eu.kanade.translation.artifact.ChapterRunState
import eu.kanade.translation.artifact.DurableFailureMetadata
import eu.kanade.translation.artifact.FailureCategory
import eu.kanade.translation.artifact.ManifestAuthority
import eu.kanade.translation.artifact.OcrCheckpointMode
import eu.kanade.translation.artifact.RunConfigSnapshot
import eu.kanade.translation.artifact.StageFingerprints
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.recordAttemptFailure
import eu.kanade.translation.translator.TranslatorComputeClass
import eu.kanade.translation.util.ShortHash
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.security.MessageDigest

/**
 * T924 Stage 3 (WP4) — the FF-01 flagged chapter-profile Batch coordinator
 * SHELL. This slice implements the durable machine ONLY through OCR_PREFLIGHT
 * (T924-ST-02..06):
 *
 *   RUN_SNAPSHOT (record published) -> OCR_PLAN (transition published) ->
 *   OCR_PREFLIGHT (serial per page: plan -> OCR -> `checkpointOcr` CLOSE ->
 *   release lease -> next page) -> STOP with a durable diagnostic summary.
 *
 * Analysis / profile / envelope / translation / inpaint are LATER stages and
 * are deliberately NOT implemented here: after the last page checkpoints, the
 * coordinator reports STOPPED-NOT-FINISHED (a paused, fully resumable pass —
 * never a COMPLETED pass, which would strand the untranslated pages as
 * failed) and publishes a final run record whose state shows OCR_PREFLIGHT
 * complete plus a diagnostic counter summary. Completion semantics are NOT
 * redefined; committed display is never touched (T924-TX-07 — checkpointOcr
 * preserves it by construction), so no page visually regresses.
 *
 * Invariants kept by the loop (gates §2.3):
 *  - ONE decoded page at a time: the loop is strictly serial, the OCR worker's
 *    native handoff is released before the next page is admitted, and the page
 *    lease is released strictly AFTER the checkpoint committed (T924-TX-06).
 *  - No inpaint, no analysis, no translation, no provider calls.
 *  - Resume re-enters OCR_PREFLIGHT and skips pages whose origin-neutral
 *    `PageOcrCheckpoint` matches the current source identity (T924-ST-06);
 *    only the remainder is re-OCR'd.
 *  - The run record ([ChapterRunRecord]) is published at run start (FF-01d:
 *    the FF-01 flag value is frozen into it) and advanced as pages checkpoint;
 *    counter publications are best-effort progress carriers — per-page
 *    checkpoints in the manifest stay authoritative (T924-ST-06).
 */
/**
 * Typed identity of ONE unresolved preflight page failure (wave-2 review R2):
 * the durable-failure-ledger input for a page whose OCR_PREFLIGHT attempt
 * could not be resolved — a REJECTED `checkpointOcr` CLOSE transaction or a
 * thrown OCR-lane worker exception.
 */
internal data class PreflightStageFailure(
    val pageKey: String,
    val kind: PreflightFailureKind,
    /** The typed checkpoint rejection reason or exception identity, verbatim. */
    val reason: String,
)

internal enum class PreflightFailureKind {
    /** `checkpointOcr` rejected the CLOSE transaction with a typed reason. */
    CHECKPOINT_REJECTED,

    /** The OCR lane worker threw before a checkpoint could be attempted. */
    OCR_WORKER_FAILED,
}

internal class ChapterProfileBatchCoordinator(
    private val store: ChapterTranslationStore,
    private val nativeWorker: NativeLaneWorker,
    private val frozenConfig: RunConfigSnapshot,
    /** Ordered (pageKey, sourceSha256) pairs; the run's source digest input. */
    private val orderedSourcePairs: List<Pair<String, String>>,
    /** FF-01d: the flag value read ONCE at dispatch, frozen into the record. */
    private val flagProfilePipeline: Boolean,
    /** Releases the BATCH page lease (strictly after the checkpoint, TX-06). */
    private val releaseBatchLease: suspend (String) -> Unit,
    private val listener: BatchScheduleListener = BatchScheduleListener.NOOP,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
    /**
     * Wave-2 review R2: the durable failure-ledger writer for an unresolved
     * preflight page. The DEFAULT writer mirrors the legacy
     * `persistUnexpectedBatchStageFailure` idiom (T924 wave-3 slice B): the
     * page patch (OCR FAILED + one `recordAttemptFailure()` charge per
     * attempt) and the `manifest.durableFailures` record share ONE atomic
     * store publication, with the consecutive-unresolved count bounded by
     * [ChapterAttemptLedgerDocument.MAX_CONSECUTIVE_UNRESOLVED] — never
     * unlimited. Invoked AFTER the checkpoint attempt and BEFORE the lease
     * release (the legacy order: record precedes teardown).
     */
    private val failureRecorder: suspend (PreflightStageFailure) -> Unit =
        { failure -> persistDurablePreflightFailure(store, failure, nowEpochMs) },
) {

    private val sourceShaByPageKey: Map<String, String> = orderedSourcePairs.toMap()

    /**
     * Same call shape as [SequentialBatchCoordinator.runPass1] so the FF-01a
     * dispatch point can branch between the two coordinators without any
     * other shell change. [computeClass] is accepted for call-shape parity
     * only — this stage never dispatches a provider lane.
     */
    suspend fun runPass1(
        orderedPages: List<PageKey>,
        computeClass: TranslatorComputeClass,
    ): BatchPass1Outcome {
        if (orderedPages.isEmpty()) {
            return BatchPass1Outcome(needsTranslation = emptyList())
        }
        currentCoroutineContext().ensureActive()
        val artifact = store.artifactStore
        if (artifact == null || store.artifactManifest?.authority != ManifestAuthority.ARTIFACTS) {
            // The preflight writes origin-neutral checkpoints; without artifact
            // authority the flagged path cannot do its one job. Fail fast
            // WITHOUT burning any OCR work (the legacy path remains available).
            logcat(LogPriority.WARN) {
                "TachiyomiAT t924 preflight refused: chapter artifact authority not established"
            }
            return BatchPass1Outcome(
                needsTranslation = emptyList(),
                status = BatchPass1Status.FAILED,
                anchorPageKey = orderedPages.first().first,
                reason = "T924 OCR preflight requires chapter artifact authority",
            )
        }

        val frozenFingerprint = runConfigFingerprint(frozenConfig)
        val sourceDigest = orderedSourceDigest(orderedSourcePairs)
        val priorRecord = (existingActiveRecord(artifact) as? ChapterArtifactStore.RunRecordRead.Usable)?.record
        // ST-15: settings apply next run — a resume continues the recorded run
        // only while the frozen configuration fingerprint still matches; a
        // mismatch starts a NEW run id under the current configuration.
        val runId = priorRecord
            ?.takeIf { it.frozenRunConfigFingerprint == frozenFingerprint }
            ?.runId
            ?: newRunId(sourceDigest, frozenFingerprint)

        val total = orderedPages.size
        var reusedPages = 0
        var checkpointedPages = 0
        val corpusFingerprints = mutableListOf<Pair<String, String>>()

        fun counters(): Map<String, Int> = mapOf(
            COUNTER_TOTAL to total,
            COUNTER_DONE to (reusedPages + checkpointedPages),
            COUNTER_REUSED to reusedPages,
            // Kept for pre-field record compatibility; the authoritative FF-01d
            // freeze is frozenConfig.flagProfilePipeline (participates in the
            // run-config fingerprint; counters never do, per FP-01).
            COUNTER_FLAG to if (flagProfilePipeline) 1 else 0,
        )

        // ST-03: run start — RUN_SNAPSHOT record with the frozen configuration,
        // the ordered source digest, and the frozen flag state (FF-01d).
        publishRecord(
            artifact,
            record(runId, ChapterRunState.RUN_SNAPSHOT, frozenFingerprint, sourceDigest, counters()),
        )
        // ST-05: the OCR plan is recomputed in-memory (pure function of the
        // ordered pages + store state); only the phase transition persists.
        publishRecord(
            artifact,
            record(runId, ChapterRunState.OCR_PLAN, frozenFingerprint, sourceDigest, counters()),
        )

        for (page in orderedPages) {
            val (pageKey, pageIndex) = page
            currentCoroutineContext().ensureActive()
            // Reader-priority yield between pages: a suspension point (never a
            // sleep) that lets interactive native demand win the lane.
            yield()

            val reusable = reusableCheckpointFingerprint(artifact, pageKey)
            if (reusable != null) {
                // ST-06 resume rule: the page's origin-neutral checkpoint matches
                // the current source identity — no re-OCR, no lease, no decode.
                reusedPages++
                corpusFingerprints += pageKey to reusable
                logcat(LogPriority.INFO) {
                    "TachiyomiAT t924 preflight reused checkpoint pageHash=${pageHash(pageKey)}"
                }
                publishRecord(
                    artifact,
                    record(runId, ChapterRunState.OCR_PLAN, frozenFingerprint, sourceDigest, counters()),
                )
                continue
            }

            listener.ocrStarted(pageKey)
            var ref: OcrReadyPageRef? = null
            // Set when THIS page's attempt ended unresolved (REJECTED checkpoint
            // or worker exception): the ledger record is written in `finally`,
            // AFTER the B0 candidate teardown — cancelCandidate strips
            // candidate-owned stage records, so the record must outlive it.
            var pendingFailure: PreflightStageFailure? = null
            try {
                ref = nativeWorker.runOcrStage(pageKey, pageIndex)
                if (ref == null) {
                    // Lease-deferred / externally completed: another origin owns
                    // the page's outcome. Not a failure; the final diagnostic
                    // counts every uncheckpointed page as a gap.
                    logcat(LogPriority.INFO) {
                        "TachiyomiAT t924 preflight skipped pageHash=${pageHash(pageKey)} (deferred or externally completed)"
                    }
                } else {
                    listener.ocrPublished(pageKey)
                    val outcome = checkpointPage(artifact, pageKey, ref)
                    when (outcome) {
                        is CheckpointOcrResult.Committed -> {
                            checkpointedPages++
                            readCheckpointFingerprint(artifact, pageKey)?.let { fingerprint ->
                                corpusFingerprints += pageKey to fingerprint
                            }
                        }
                        is CheckpointOcrResult.Rejected -> {
                            // ST-06 terminal: any unresolved checkpoint failure stops
                            // the phase before any later (paid) stage. The lease is
                            // released in `finally`; the shell teardown reconciles
                            // the candidate per the legacy durability rules.
                            logcat(LogPriority.WARN) {
                                "TachiyomiAT t924 preflight checkpoint rejected pageHash=${pageHash(pageKey)} " +
                                    "reason=${outcome.reason}"
                            }
                            // R2: record the failure for the `finally` writer,
                            // which persists it AFTER the B0 teardown (a
                            // pre-teardown write would be stripped by
                            // cancelCandidate's candidate-owned record sweep),
                            // still strictly after the checkpoint attempt.
                            pendingFailure = PreflightStageFailure(
                                pageKey = pageKey,
                                kind = PreflightFailureKind.CHECKPOINT_REJECTED,
                                reason = outcome.reason,
                            )
                            return BatchPass1Outcome(
                                needsTranslation = emptyList(),
                                status = BatchPass1Status.FAILED,
                                anchorPageKey = pageKey,
                                completedPageKeys = corpusFingerprints.mapTo(mutableSetOf()) { it.first },
                                reason = "T924 OCR preflight checkpoint rejected: ${outcome.reason}",
                            )
                        }
                    }
                    // Advisory progress counters ride their own M1 pointer move.
                    publishRecord(
                        artifact,
                        record(runId, ChapterRunState.OCR_PLAN, frozenFingerprint, sourceDigest, counters()),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.WARN) {
                    "TachiyomiAT t924 preflight ocr failed pageHash=${pageHash(pageKey)} error=${e::class.java.simpleName}"
                }
                // R2: same durable ledger as a REJECTED checkpoint, carrying the
                // exception identity as the typed reason.
                pendingFailure = PreflightStageFailure(
                    pageKey = pageKey,
                    kind = PreflightFailureKind.OCR_WORKER_FAILED,
                    reason = "${e::class.java.simpleName}: ${e.message ?: "no message"}",
                )
                return BatchPass1Outcome(
                    needsTranslation = emptyList(),
                    status = BatchPass1Status.FAILED,
                    anchorPageKey = pageKey,
                    completedPageKeys = corpusFingerprints.mapTo(mutableSetOf()) { it.first },
                    reason = "T924 OCR preflight failed: ${e.message ?: e::class.java.simpleName}",
                )
            } finally {
                // The decoded handoff NEVER crosses a page boundary and the
                // lease is released strictly after the checkpoint attempt
                // (T924-TX-06; one-decoded-page invariant).
                ref?.let(nativeWorker::releaseNativeHandoff)
                if (pendingFailure != null) {
                    // B0 teardown idiom: the unresolved page's candidate-held OCR
                    // never committed (no checkpoint), so cancel it — the page
                    // re-OCRs next attempt. The durable failure record is
                    // written AFTER the cancellation: `cancelCandidate` strips
                    // candidate-owned stage records, so the ledger record must
                    // be installed once no candidate owns it (the legacy
                    // persistUnexpectedBatchStageFailure order — the shell
                    // persists after the coordinator's teardown).
                    store.cancelPageStageWork(pageKey, PageWriteOrigin.BATCH)
                    recordPageFailure(pendingFailure)
                }
                releaseBatchLease(pageKey)
                listener.ocrFinished(pageKey)
            }
        }

        // ---- STOP with a durable diagnostic (this slice's terminal). ----
        // OCR_PREFLIGHT is COMPLETE durably (state + corpus fingerprint when
        // every page carries a usable checkpoint); the chapter stays paused
        // and resumable — analysis/profile/translation are later stages.
        val corpusGaps = total - corpusFingerprints.size
        val corpusFingerprint = if (corpusGaps == 0 && corpusFingerprints.isNotEmpty()) {
            val naturalOrderProven = orderedPages.map { it.second }.toSet() == (0 until total).toSet()
            StageFingerprints.ocrCorpusFingerprint(
                pages = corpusFingerprints,
                expectedPageCount = total,
                expectedPageCountTrusted = true,
                naturalOrderProven = naturalOrderProven,
            )
        } else {
            null
        }
        val finalCounters = counters() + mapOf(
            COUNTER_STOP to 1,
            COUNTER_GAPS to corpusGaps,
        )
        publishRecord(
            artifact,
            record(
                runId,
                ChapterRunState.OCR_PREFLIGHT,
                frozenFingerprint,
                sourceDigest,
                finalCounters,
                ocrCorpusFingerprint = corpusFingerprint,
            ),
        )
        logcat(LogPriority.INFO) {
            "TachiyomiAT t924 preflight stopped-not-finished: ocr=$total reused=$reusedPages gaps=$corpusGaps " +
                "analysis/translation arrive in later T924 stages"
        }
        return BatchPass1Outcome(
            needsTranslation = emptyList(),
            status = BatchPass1Status.PAUSED,
            completedPageKeys = corpusFingerprints.mapTo(mutableSetOf()) { it.first },
            reason = STOP_REASON,
        )
    }

    /**
     * R2: route one unresolved page failure into the durable failure ledger.
     * Best-effort: a rejected ledger publication is logged and the honest
     * FAILED outcome still stands (the pass semantics are unchanged) — the
     * record is a durability ADDITION, never a new failure source. The write
     * happens while the page lease is still held (strictly after the
     * checkpoint attempt, strictly before the `finally` release — TX-06).
     */
    private suspend fun recordPageFailure(failure: PreflightStageFailure) {
        try {
            failureRecorder(failure)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.WARN) {
                "TachiyomiAT t924 preflight durable failure record rejected pageHash=${pageHash(failure.pageKey)} " +
                    "error=${e::class.java.simpleName}"
            }
        }
    }

    /**
     * The per-page checkpoint transaction: CLOSE the BATCH candidate (TX-03 default). */
    private suspend fun checkpointPage(
        artifact: ChapterArtifactStore,
        pageKey: String,
        ref: OcrReadyPageRef,
    ): CheckpointOcrResult {
        val snapshot = store.snapshot(pageKey)
        val leaseToken = ref.leaseToken
        if (leaseToken == null) {
            return CheckpointOcrResult.Rejected(
                "preflight ocr reference without a lease token",
            )
        }
        return store.checkpointOcr(
            pageKey = pageKey,
            generation = snapshot.generation,
            expectedPageVersion = snapshot.pageVersion,
            expectedLeaseToken = leaseToken,
            expectedCandidateGenerationId = ref.candidateGenerationId,
            expectedArtifactPageVersion = snapshot.artifactPageVersion,
            expectedDependencyFingerprint = ref.dependencyFingerprint,
            sourceSha256 = sourceShaByPageKey[pageKey],
            sourceOrientation = orientationOf(snapshot),
            mode = OcrCheckpointMode.CLOSE,
            description = "t924 ocr preflight checkpoint",
        )
    }

    /**
     * ST-06 reuse rule: a usable checkpoint whose source identity still
     * matches the current source digest input. A checkpoint that cannot
     * prove source equality (changed file, unknown current hash) is re-run.
     */
    private fun reusableCheckpointFingerprint(
        artifact: ChapterArtifactStore,
        pageKey: String,
    ): String? {
        val manifest = store.artifactManifest ?: return null
        val pointer = manifest.ocrCheckpoints[pageKey] ?: return null
        val read = artifact.readOcrCheckpoint(pointer)
        if (read !is ChapterArtifactStore.OcrCheckpointRead.Usable) return null
        val currentSha = sourceShaByPageKey[pageKey]
        if (currentSha == null || read.checkpoint.sourceIdentity.sha256 != currentSha) return null
        return read.checkpoint.ocrContentFingerprint
    }

    private fun readCheckpointFingerprint(
        artifact: ChapterArtifactStore,
        pageKey: String,
    ): String? {
        val manifest = store.artifactManifest ?: return null
        val pointer = manifest.ocrCheckpoints[pageKey] ?: return null
        val read = artifact.readOcrCheckpoint(pointer)
        return (read as? ChapterArtifactStore.OcrCheckpointRead.Usable)?.checkpoint?.ocrContentFingerprint
    }

    private fun existingActiveRecord(
        artifact: ChapterArtifactStore,
    ): ChapterArtifactStore.RunRecordRead? {
        val pointer = store.artifactManifest?.activeRun ?: return null
        return artifact.readRunRecord(pointer)
    }

    /**
     * Best-effort run-record publication. The record is identity/progress
     * state; per-page checkpoints in the manifest are the authoritative
     * durable state (T924-ST-06), so a rejected publication never fails the
     * preflight — it only loses advisory progress.
     */
    private fun publishRecord(
        artifact: ChapterArtifactStore,
        record: ChapterRunRecord,
    ) {
        val manifest = store.artifactManifest ?: return
        val json = ArtifactDocumentJson.encodeToString(record)
        val outcome = artifact.publishActiveRun(
            manifest = manifest,
            record = record,
            contentFingerprint = sha256Hex(json.encodeToByteArray()),
            nowEpochMs = nowEpochMs(),
        )
        when (outcome) {
            is ChapterArtifactStore.TransactionOutcome.Committed -> {
                // Keep the facade's manifest snapshot current — a stale
                // snapshot would fail the next checkpoint's whole-manifest CAS.
                store.artifactManifest = outcome.manifest
            }
            is ChapterArtifactStore.TransactionOutcome.Rejected -> {
                logcat(LogPriority.WARN) {
                    "TachiyomiAT t924 preflight run record publication rejected: ${outcome.reason}"
                }
            }
        }
    }

    private fun record(
        runId: String,
        state: ChapterRunState,
        frozenFingerprint: String,
        sourceDigest: String,
        counters: Map<String, Int>,
        ocrCorpusFingerprint: String? = null,
    ): ChapterRunRecord {
        val now = nowEpochMs()
        return ChapterRunRecord(
            runId = runId,
            state = state,
            frozenConfig = frozenConfig,
            frozenRunConfigFingerprint = frozenFingerprint,
            orderedSourceDigest = sourceDigest,
            ocrCorpusFingerprint = ocrCorpusFingerprint,
            analysisPolicyFingerprint = policyFingerprint(
                "analysis-policy-v1",
                frozenConfig.analysisPolicy.overlapPages,
            ),
            envelopePolicyFingerprint = policyFingerprint(
                "envelope-policy-v1",
                frozenConfig.envelopePolicy.maxBlocks,
                frozenConfig.envelopePolicy.maxPages,
            ),
            phaseCounters = counters,
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
        )
    }

    private fun orientationOf(snapshot: ChapterTranslationStore.PageSnapshot): String? {
        val page = snapshot.page ?: return null
        val width = page.imgWidth.takeIf { it > 0f } ?: return null
        val height = page.imgHeight.takeIf { it > 0f } ?: return null
        return if (height > width) "PORTRAIT" else "LANDSCAPE"
    }

    sealed interface FlaggedRunResumeDecision {
        /** Current flag ON: run (or continue) the flagged path. */
        data class RunFlaggedPath(val priorRecord: ChapterRunRecord?) : FlaggedRunResumeDecision

        /**
         * FF-01e.2b: flag now OFF, no final new-path display committed — drop
         * to the legacy path using only legacy artifacts; new-path sidecars
         * stay untouched for a later re-enable.
         */
        data object DropToLegacy : FlaggedRunResumeDecision

        /**
         * FF-01e.2a: flag now OFF and the recorded run already finished on the
         * new path (final per-page displays committed) — treat as finished.
         * Unreachable in this slice (preflight never commits final displays);
         * encoded for the later stages that publish `COMPLETE` runs.
         */
        data object TreatAsFinished : FlaggedRunResumeDecision
    }

    enum class BatchCoordinatorKind {
        /** Legacy progressive coordinator — the FF-01 OFF construction (FF-01b). */
        LEGACY_SEQUENTIAL,

        /** T924 chapter-profile coordinator — the FF-01 ON construction. */
        PROFILE_PIPELINE,
    }

    companion object {
        /** Stopped-not-finished diagnostic carried in the paused outcome. */
        const val STOP_REASON =
            "T924 OCR preflight complete; analysis/profile/translation arrive in later stages"

        /** FF-01d flag state, frozen as an operational (never fingerprinted) counter. */
        const val COUNTER_FLAG = "flagProfilePipeline"
        const val COUNTER_TOTAL = "ocrPagesTotal"
        const val COUNTER_DONE = "ocrPagesDone"
        const val COUNTER_REUSED = "ocrPagesReused"
        const val COUNTER_STOP = "preflightStop"
        const val COUNTER_GAPS = "preflightCheckpointGaps"

        /**
         * T924-FF-01a dispatch decision. The flag is consulted exactly ONCE per
         * run at the `BatchChapterTranslator` coordinator construction; this
         * pure function carries the mapping so the OFF path is provably the
         * unchanged legacy coordinator (FF-01b).
         */
        fun dispatchKind(translationBatchProfilePipeline: Boolean): BatchCoordinatorKind =
            if (translationBatchProfilePipeline) {
                BatchCoordinatorKind.PROFILE_PIPELINE
            } else {
                BatchCoordinatorKind.LEGACY_SEQUENTIAL
            }

        /**
         * FF-01e resume case analysis (contract §1.3 FF-01e.2/3): the recorded
         * run's flag provenance is honored only while the CURRENT flag is ON;
         * flag OFF never re-enters the new path. Takes no queue input — flag
         * state is re-derived from the run record + current preference only
         * (T924-FF-10), and queue restore never auto-starts a run.
         */
        fun decideResume(
            record: ChapterRunRecord?,
            currentFlagOn: Boolean,
        ): FlaggedRunResumeDecision = when {
            !currentFlagOn && record?.state == ChapterRunState.COMPLETE ->
                FlaggedRunResumeDecision.TreatAsFinished
            !currentFlagOn -> FlaggedRunResumeDecision.DropToLegacy
            else -> FlaggedRunResumeDecision.RunFlaggedPath(record)
        }

        /**
         * RUN_SNAPSHOT freeze (ST-03). Identity values that have no stable
         * engine accessor yet are pinned to explicit shell placeholders —
         * recorded, stable, and never silently empty.
         */
        fun frozenRunConfig(
            sourceLang: String,
            targetLang: String,
            ocrEngine: String,
            inpaintMode: String,
            providerKey: String,
            ocrModelHash: String = MODEL_HASH_UNSPECIFIED,
            detectorModelHash: String = MODEL_HASH_UNSPECIFIED,
            protocolVersion: Int = 1,
            readingOrderVersion: Int = 1,
            flagProfilePipeline: Boolean? = null,
        ): RunConfigSnapshot = RunConfigSnapshot(
            sourceLang = sourceLang,
            targetLang = targetLang,
            ocrEngine = ocrEngine,
            ocrModelHash = ocrModelHash,
            detectorModelHash = detectorModelHash,
            inpaintMode = inpaintMode,
            providerKey = providerKey,
            protocolVersion = protocolVersion,
            readingOrderVersion = readingOrderVersion,
            flagProfilePipeline = flagProfilePipeline,
        )

        const val MODEL_HASH_UNSPECIFIED = "unspecified"

        /** `run-<epochMs>-<hash8>` per the schemas contract §1.1. */
        fun newRunId(sourceDigest: String, frozenFingerprint: String): String =
            "run-${System.currentTimeMillis()}-${sha256Hex(
                "$sourceDigest:$frozenFingerprint".encodeToByteArray(),
            ).take(8)}"

        /**
         * Wave-2 review R2 (slice B): the DEFAULT durable failure-ledger
         * writer, mirroring the legacy `persistUnexpectedBatchStageFailure`
         * idiom through the store's ATOMIC
         * [ChapterTranslationStore.persistDurableStageFailure] publication:
         * the page patch and the `manifest.durableFailures` record are ONE
         * manifest write, so a restart can never observe one without the other.
         *
         * Cap semantics (mirrored from the legacy attempt ledger,
         * `ChapterAttemptLedgerDocument.MAX_CONSECUTIVE_UNRESOLVED`): the
         * record's `retryCount` counts CONSECUTIVE unresolved preflight
         * attempts and stops at the bound — at the cap the record is
         * re-stamped as an INTERRUPTED-class, manual-retry-only failure (the
         * `applyAttemptCapPause` vocabulary) instead of charging forever.
         */
        suspend fun persistDurablePreflightFailure(
            store: ChapterTranslationStore,
            failure: PreflightStageFailure,
            nowEpochMs: () -> Long = System::currentTimeMillis,
        ) {
            val existing = store.durableFailuresSnapshot()[durableFailureKey(failure.pageKey)]
            val consecutive = existing?.retryCount ?: 0
            val capped = consecutive >= ChapterAttemptLedgerDocument.MAX_CONSECUTIVE_UNRESOLVED
            val retryCount = if (capped) consecutive else consecutive + 1
            val message = if (capped) {
                "${failure.reason}; attempt cap reached " +
                    "($retryCount consecutive unresolved preflight attempts); manual retry required"
            } else {
                failure.reason
            }
            val snapshot = store.snapshot(failure.pageKey)
            val metadata = DurableFailureMetadata(
                pageKey = failure.pageKey,
                stage = ArtifactStage.OCR,
                status = ArtifactStageStatus.FAILED_RETRYABLE,
                category = when {
                    capped -> FailureCategory.INTERRUPTED
                    failure.kind == PreflightFailureKind.CHECKPOINT_REJECTED -> FailureCategory.PROTOCOL
                    else -> FailureCategory.TRANSIENT
                },
                retryCount = retryCount,
                lastFailureMessage = message,
                lastFailedAtEpochMs = nowEpochMs(),
                nextEligibleRetryAtEpochMs = null,
            )
            val result = store.persistDurableStageFailure(
                pageKey = failure.pageKey,
                expected = ChapterTranslationStore.PatchPrecondition(
                    generation = snapshot.generation,
                    pageVersion = snapshot.pageVersion,
                    leaseToken = snapshot.leaseToken,
                ),
                failure = metadata,
                description = "t924 preflight durable failure record",
            ) { current ->
                (current ?: PageTranslation(sourceFileName = failure.pageKey)).apply {
                    sourceFileName = failure.pageKey
                    ocrStatus = StageStatus.FAILED
                    errorMessage = message
                    // At most ONE exhaustion charge per attempt (the
                    // attemptCharged guard), exactly like the legacy
                    // persistUnexpectedBatchStageFailure path.
                    recordAttemptFailure()
                    updatedAt = nowEpochMs()
                }
            }
            when (result) {
                is ChapterTranslationStore.PatchResult.Accepted -> Unit
                is ChapterTranslationStore.PatchResult.Rejected -> throw IllegalStateException(
                    "t924 preflight durable failure record rejected: ${result.reason}",
                )
            }
        }

        /** The manifest `durableFailures` key for the preflight OCR stage. */
        private fun durableFailureKey(pageKey: String): String = "$pageKey:${ArtifactStage.OCR.name}"

        /** T924-SC-08-style length-prefixed hash over the canonical config JSON. */
        fun runConfigFingerprint(config: RunConfigSnapshot): String = sha256Hex(
            lengthPrefixed("run-config-v1", ArtifactDocumentJson.encodeToString(config)),
        )

        fun policyFingerprint(tag: String, vararg values: Int): String = sha256Hex(
            lengthPrefixed(
                tag,
                values.joinToString(separator = ",") { it.toString() },
            ),
        )

        /** SHA-256 over the ordered (pageKey, sourceSha256) pairs, length-prefixed. */
        fun orderedSourceDigest(pairs: List<Pair<String, String>>): String = sha256Hex(
            pairs.joinToString(separator = "") { (pageKey, sha) ->
                lengthPrefixed(pageKey, sha)
            }.let { lengthPrefixed("ordered-source-digest-v1", it) },
        )

        private fun lengthPrefixed(vararg parts: String): String = parts.joinToString(separator = "") { part ->
            "${part.encodeToByteArray().size}:$part"
        }

        fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }

        fun sha256Hex(text: String): String = sha256Hex(text.encodeToByteArray())

        private fun pageHash(pageKey: String): String = ShortHash.hash(pageKey)
    }
}
