package eu.kanade.translation.pipeline.batch

import eu.kanade.translation.ChapterTranslationStore
import eu.kanade.translation.CheckpointOcrResult
import eu.kanade.translation.PageWriteOrigin
import eu.kanade.translation.artifact.AnalysisChunkResult
import eu.kanade.translation.artifact.ArtifactDocumentJson
import eu.kanade.translation.artifact.ArtifactStage
import eu.kanade.translation.artifact.ArtifactStageStatus
import eu.kanade.translation.artifact.ChapterArtifactStore
import eu.kanade.translation.artifact.ChapterAttemptLedgerDocument
import eu.kanade.translation.artifact.ChapterRunRecord
import eu.kanade.translation.artifact.ChapterRunState
import eu.kanade.translation.artifact.DurableFailureMetadata
import eu.kanade.translation.artifact.ExtractedEntity
import eu.kanade.translation.artifact.ExtractedRelationship
import eu.kanade.translation.artifact.ExtractedTerm
import eu.kanade.translation.artifact.ExtractedTermKind
import eu.kanade.translation.artifact.FailureCategory
import eu.kanade.translation.artifact.ManifestAuthority
import eu.kanade.translation.artifact.OcrCheckpointMode
import eu.kanade.translation.artifact.PageRange
import eu.kanade.translation.artifact.ProfileScene
import eu.kanade.translation.artifact.RunConfigSnapshot
import eu.kanade.translation.artifact.SceneRegister
import eu.kanade.translation.artifact.SidecarPointer
import eu.kanade.translation.artifact.StageFingerprints
import eu.kanade.translation.artifact.ToneFlag
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.recordAttemptFailure
import eu.kanade.translation.translator.TranslatorComputeClass
import eu.kanade.translation.translator.analysis.AnalysisChunkRunner
import eu.kanade.translation.translator.analysis.AnalysisChunkRunOutcome
import eu.kanade.translation.translator.analysis.AnalysisCoverageKind
import eu.kanade.translation.translator.analysis.AnalysisEvidenceTexts
import eu.kanade.translation.translator.analysis.AnalysisRunIdentity
import eu.kanade.translation.translator.contextual.AnalysisChunkPlanResult
import eu.kanade.translation.translator.contextual.AnalysisChunkPlanner
import eu.kanade.translation.translator.contextual.AnalysisChunkPolicy
import eu.kanade.translation.translator.contextual.ChunkPlannerPage
import eu.kanade.translation.translator.contextual.OcrCorpusManifest
import eu.kanade.translation.translator.contextual.OcrCorpusPageEntry
import eu.kanade.translation.translator.contextual.PlannedAnalysisChunk
import eu.kanade.translation.util.ShortHash
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.security.MessageDigest

/**
 * T924 Stage 3 + Stage 5 slice A (WP4 + WP5a) — the FF-01 flagged
 * chapter-profile Batch coordinator. Stage 3 landed the durable machine
 * through OCR_PREFLIGHT (T924-ST-02..06); Stage-5 slice A continues after a
 * COMPLETE preflight into:
 *
 *   ANALYSIS_PLAN (ST-07: corpus re-derived from the durable checkpoints,
 *   skip rules recorded) -> ANALYSIS_CHUNKS (ST-08: resume skips the
 *   persisted pointer prefix; each chunk runs through the typed analysis
 *   runner and persists crash-safely via [AnalysisChunkPublication]) ->
 *   STOP with a PAUSED diagnostic (profile reconcile/freeze are slice B).
 *
 * Invariants kept by the loop (gates §2.3 + §2.4):
 *  - ONE decoded page at a time: the preflight loop is strictly serial, the
 *    OCR worker's native handoff is released before the next page is admitted,
 *    and the page lease is released strictly AFTER the checkpoint committed
 *    (T924-TX-06).
 *  - No inpaint, no translation, no display promotion; provider calls happen
 *    ONLY inside the analysis phase, through the typed runner (never
 *    `promptText`), gated by the 15-RPM Batch sub-limit + shared provider
 *    bucket (T924-AP-08, DR-C/DR-D).
 *  - Resume re-enters OCR_PREFLIGHT (checkpoint reuse by content identity) or
 *    ANALYSIS_CHUNKS (never re-sends persisted chunks, ST-08).
 *  - The run record ([ChapterRunRecord]) is published at run start (FF-01d)
 *    and advanced at phase transitions + chunk completions; counter
 *    publications are best-effort progress carriers — the manifest's
 *    checkpoints and `analysisChunks` pointers stay authoritative
 *    (T924-ST-06/ST-08).
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
    /**
     * Stage-5 slice A: the typed analysis runner (executor + transport, or a
     * test fake). `null` keeps the slice-A shell behavior: the run pauses at
     * ANALYSIS_CHUNKS with a typed CONFIGURATION-class skip counter instead of
     * producing provider calls without a typed transport.
     */
    private val analysisChunkRunner: AnalysisChunkRunner? = null,
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

        // ---- OCR_PREFLIGHT complete durably; continue into the analysis ----
        // ---- phase when the corpus is complete (T924-ST-07/08).        ----
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
        if (corpusFingerprint == null) {
            // Incomplete corpus (reused/deferred gaps): analysis needs the
            // whole OCR corpus — stop exactly like the S3 shell did.
            logcat(LogPriority.INFO) {
                "TachiyomiAT t924 preflight stopped-not-finished: ocr=$total reused=$reusedPages gaps=$corpusGaps " +
                    "analysis deferred until the corpus is complete"
            }
            return BatchPass1Outcome(
                needsTranslation = emptyList(),
                status = BatchPass1Status.PAUSED,
                completedPageKeys = corpusFingerprints.mapTo(mutableSetOf()) { it.first },
                reason = STOP_REASON,
            )
        }

        // ---- T924 Stage 5 slice A: ANALYSIS_PLAN -> ANALYSIS_CHUNKS. ----
        // The chapter stays PAUSED after the chunks (profile reconcile/freeze
        // is slice B); completion semantics are still NOT redefined.
        return runAnalysisPhase(
            artifact = artifact,
            runId = runId,
            orderedPages = orderedPages,
            corpusFingerprint = corpusFingerprint,
            baseCounters = finalCounters,
        )
    }

    /**
     * Stage-5 slice A analysis phase (T924-ST-07/ST-08):
     *
     *  1. ANALYSIS_PLAN — the OCR corpus manifest is a pure, recomputable
     *     planner output re-derived from the durable checkpoints (ST-01.4 /
     *     ST-05 analogy: only the phase transition persists). Skip rules
     *     (no-work/textless) and the chunk plan are recorded via the record.
     *  2. ANALYSIS_CHUNKS — resume skips the persisted pointer prefix
     *     (chunk-ordinal order, WP1 deviation note); each remaining chunk is
     *     executed through the typed analysis runner and persisted through
     *     the crash-safe sidecar-then-pointer transaction (T924-SC-20).
     *
     * Typed failures pause the run at the failing chunk (ST-08: resume
     * restarts at the first unpersisted chunk; the validated prefix stays
     * durable and is never re-sent). The run NEVER advances to
     * PROFILE_RECONCILE here (slice B), so nothing in this slice publishes
     * `COMPLETE` and [decideResume] wiring stays untouched (wave-2 F1).
     */
    private suspend fun runAnalysisPhase(
        artifact: ChapterArtifactStore,
        runId: String,
        orderedPages: List<PageKey>,
        corpusFingerprint: String,
        baseCounters: Map<String, Int>,
    ): BatchPass1Outcome {
        val total = orderedPages.size
        val frozenFingerprint = runConfigFingerprint(frozenConfig)
        val sourceDigest = orderedSourceDigest(orderedSourcePairs)

        fun analysisCounters(extra: Map<String, Int>): Map<String, Int> =
            baseCounters + mapOf(COUNTER_ANALYSIS_PLAN to 1) + extra

        // ST-07: phase transition only — the corpus manifest is recomputed
        // from the checkpoints below (pure planner output, never stale).
        publishRecord(
            artifact,
            record(
                runId,
                ChapterRunState.ANALYSIS_PLAN,
                frozenFingerprint,
                sourceDigest,
                analysisCounters(mapOf(COUNTER_ANALYSIS_PLAN to 1)),
                ocrCorpusFingerprint = corpusFingerprint,
            ),
        )

        // Rebuild the corpus entries from the DURABLE checkpoints (never from
        // the in-memory pass): idempotent against the ST-07 postcondition.
        val corpus = corpusEntriesFromCheckpoints(artifact, orderedPages, total)
        if (corpus == null) {
            // A checkpoint vanished between the preflight barrier and here
            // (concurrent invalidation): stop; resume re-plans honestly.
            return BatchPass1Outcome(
                needsTranslation = emptyList(),
                status = BatchPass1Status.PAUSED,
                completedPageKeys = orderedPages.mapTo(mutableSetOf()) { it.first },
                reason = "T924 analysis plan deferred: corpus checkpoints changed under the run",
            )
        }
        if (corpus.corpusFingerprint != corpusFingerprint) {
            return BatchPass1Outcome(
                needsTranslation = emptyList(),
                status = BatchPass1Status.PAUSED,
                completedPageKeys = orderedPages.mapTo(mutableSetOf()) { it.first },
                reason = "T924 analysis plan deferred: corpus fingerprint drifted between preflight and plan",
            )
        }

        val chunkPages = corpus.entries.map { entry ->
            ChunkPlannerPage(
                pageKey = entry.storagePageKey,
                naturalPageIndex = entry.naturalPageIndex,
                contentFingerprint = entry.contentFingerprint,
                blockIds = entry.wireBlockIds,
                estimatedInputTokens = entry.estimatedInputTokens,
            )
        }
        val policy = AnalysisChunkPolicy(
            overlapPages = frozenConfig.analysisPolicy.overlapPages
                .coerceIn(0, AnalysisChunkResult.MAX_OVERLAP_PAGES),
        )
        val plan = when (val planned = AnalysisChunkPlanner.plan(chunkPages, policy)) {
            is AnalysisChunkPlanResult.Success -> planned
            is AnalysisChunkPlanResult.Rejected -> {
                logcat(LogPriority.WARN) {
                    "TachiyomiAT t924 analysis plan rejected: ${planned.reason}"
                }
                return BatchPass1Outcome(
                    needsTranslation = emptyList(),
                    status = BatchPass1Status.FAILED,
                    reason = "T924 analysis plan rejected: ${planned.reason}",
                )
            }
        }

        // Skip rules (provider-analysis contract §5): no chunkable work
        // (textless / no translatable blocks) skips provider analysis.
        if (plan.chunks.isEmpty()) {
            publishRecord(
                artifact,
                record(
                    runId,
                    ChapterRunState.ANALYSIS_CHUNKS,
                    frozenFingerprint,
                    sourceDigest,
                    analysisCounters(
                        mapOf(
                            COUNTER_CHUNKS_TOTAL to 0,
                            COUNTER_CHUNKS_DONE to 0,
                            COUNTER_SKIPPED_NO_WORK to 1,
                        ),
                    ),
                    ocrCorpusFingerprint = corpusFingerprint,
                ),
            )
            return BatchPass1Outcome(
                needsTranslation = emptyList(),
                status = BatchPass1Status.PAUSED,
                completedPageKeys = corpus.entries.mapTo(mutableSetOf()) { it.storagePageKey },
                reason = ANALYSIS_NO_WORK_REASON,
            )
        }

        val runner = analysisChunkRunner
        if (runner == null) {
            // Slice-A shell: no typed analysis transport is wired yet. A
            // missing transport is a typed CONFIGURATION-class gate failure
            // (provider-analysis contract §2.1) — never garbage chunks.
            publishRecord(
                artifact,
                record(
                    runId,
                    ChapterRunState.ANALYSIS_CHUNKS,
                    frozenFingerprint,
                    sourceDigest,
                    analysisCounters(
                        mapOf(
                            COUNTER_CHUNKS_TOTAL to plan.chunks.size,
                            COUNTER_CHUNKS_DONE to 0,
                            COUNTER_SKIPPED_NO_TRANSPORT to 1,
                        ),
                    ),
                    ocrCorpusFingerprint = corpusFingerprint,
                ),
            )
            return BatchPass1Outcome(
                needsTranslation = emptyList(),
                status = BatchPass1Status.PAUSED,
                completedPageKeys = corpus.entries.mapTo(mutableSetOf()) { it.storagePageKey },
                reason = ANALYSIS_NO_TRANSPORT_REASON,
            )
        }

        // ST-08 resume rule: the persisted pointer prefix (chunk-ordinal
        // order) is never re-sent; execution restarts at the first missing
        // chunk. An invalid/corrupt sidecar reads back as ABSENT and its
        // ordinal is re-executed by the size-based prefix rule only when it
        // was never appended; a corrupt POINTERED chunk surfaces via the
        // read-back gate below.
        val manifestNow = store.artifactManifest
        val persistedPrefix = manifestNow?.analysisChunks?.size ?: 0

        val identity = AnalysisRunIdentity(
            runId = runId,
            mangaKeyHash = AnalysisRunIdentity.SCOPE_ABSENT,
            chapterKeyHash = "sha256:$sourceDigest",
            sourceLanguage = frozenConfig.sourceLang,
            targetLanguage = frozenConfig.targetLang,
            analysisPolicyFingerprint = policyFingerprint(
                "analysis-policy-v1",
                frozenConfig.analysisPolicy.overlapPages,
            ),
            ocrCorpusFingerprint = corpusFingerprint,
            maxOutputTokens = ANALYSIS_MAX_OUTPUT_TOKENS,
        )

        publishRecord(
            artifact,
            record(
                runId,
                ChapterRunState.ANALYSIS_CHUNKS,
                frozenFingerprint,
                sourceDigest,
                analysisCounters(
                    mapOf(
                        COUNTER_CHUNKS_TOTAL to plan.chunks.size,
                        COUNTER_CHUNKS_DONE to persistedPrefix,
                        COUNTER_CHUNKS_PENDING to 0,
                    ),
                ),
                ocrCorpusFingerprint = corpusFingerprint,
            ),
        )

        var done = persistedPrefix
        var pending = 0
        for (chunk in plan.chunks) {
            currentCoroutineContext().ensureActive()
            if (chunk.chunkOrdinal < persistedPrefix) continue
            yield() // reader-priority courtesy between paid provider calls
            val evidence = corpus.evidenceTextsFor(chunk)
            when (val outcome = runner.executeChunk(chunk, identity, evidence)) {
                is AnalysisChunkRunOutcome.Completed -> {
                    val input = corpus.publicationInput(chunk, outcome)
                        ?: return BatchPass1Outcome(
                            needsTranslation = emptyList(),
                            status = BatchPass1Status.PAUSED,
                            reason = "T924 analysis chunk ${chunk.chunkId} deferred: " +
                                "contributing OCR snapshots no longer readable",
                        )
                    val result = AnalysisChunkPublication.buildResult(chunk, input, nowEpochMs())
                    val publication = AnalysisChunkPublication.publish(
                        artifact = artifact,
                        manifest = store.artifactManifest ?: return BatchPass1Outcome(
                            needsTranslation = emptyList(),
                            status = BatchPass1Status.PERSISTENCE_REJECTED,
                            reason = "T924 analysis chunk publication: manifest unavailable",
                        ),
                        result = result,
                        nowEpochMs = nowEpochMs(),
                    )
                    when (publication) {
                        is ChapterArtifactStore.TransactionOutcome.Committed -> {
                            store.artifactManifest = publication.manifest
                            done++
                            if (outcome.coverage.kind == AnalysisCoverageKind.MISSING_ONLY) {
                                // DR-A Option 1: the independently complete
                                // subset committed; the remainder is pending
                                // and never blocks the chapter.
                                pending++
                            }
                            publishRecord(
                                artifact,
                                record(
                                    runId,
                                    ChapterRunState.ANALYSIS_CHUNKS,
                                    frozenFingerprint,
                                    sourceDigest,
                                    analysisCounters(
                                        mapOf(
                                            COUNTER_CHUNKS_TOTAL to plan.chunks.size,
                                            COUNTER_CHUNKS_DONE to done,
                                            COUNTER_CHUNKS_PENDING to pending,
                                        ),
                                    ),
                                    ocrCorpusFingerprint = corpusFingerprint,
                                ),
                            )
                        }
                        is ChapterArtifactStore.TransactionOutcome.Rejected -> {
                            // Prior manifest stays authoritative; the chunk
                            // stays unpersisted; resume re-executes it.
                            logcat(LogPriority.WARN) {
                                "TachiyomiAT t924 analysis chunk publication rejected " +
                                    "chunk=${chunk.chunkId}: ${publication.reason}"
                            }
                            return BatchPass1Outcome(
                                needsTranslation = emptyList(),
                                status = BatchPass1Status.PERSISTENCE_REJECTED,
                                anchorPageKey = chunk.corePageKeys.firstOrNull(),
                                reason = "T924 analysis chunk publication rejected: ${publication.reason}",
                            )
                        }
                    }
                }
                is AnalysisChunkRunOutcome.Paused -> {
                    publishRecord(
                        artifact,
                        record(
                            runId,
                            ChapterRunState.ANALYSIS_CHUNKS,
                            frozenFingerprint,
                            sourceDigest,
                            analysisCounters(
                                mapOf(
                                    COUNTER_CHUNKS_TOTAL to plan.chunks.size,
                                    COUNTER_CHUNKS_DONE to done,
                                    COUNTER_CHUNKS_PENDING to pending,
                                    COUNTER_CHUNKS_FAILURES to 1,
                                ),
                            ),
                            ocrCorpusFingerprint = corpusFingerprint,
                        ),
                    )
                    logcat(LogPriority.WARN) {
                        "TachiyomiAT t924 analysis paused at chunk=${chunk.chunkId}: ${outcome.reason}"
                    }
                    return BatchPass1Outcome(
                        needsTranslation = emptyList(),
                        status = BatchPass1Status.PAUSED,
                        anchorPageKey = chunk.corePageKeys.firstOrNull(),
                        failure = outcome.failure,
                        nextEligibleRetryAtEpochMs = outcome.failure.retryAfterAtEpochMs,
                        reason = outcome.reason,
                    )
                }
                is AnalysisChunkRunOutcome.Refused -> {
                    publishRecord(
                        artifact,
                        record(
                            runId,
                            ChapterRunState.ANALYSIS_CHUNKS,
                            frozenFingerprint,
                            sourceDigest,
                            analysisCounters(
                                mapOf(
                                    COUNTER_CHUNKS_TOTAL to plan.chunks.size,
                                    COUNTER_CHUNKS_DONE to done,
                                    COUNTER_CHUNKS_PENDING to pending,
                                    COUNTER_CHUNKS_FAILURES to 1,
                                ),
                            ),
                            ocrCorpusFingerprint = corpusFingerprint,
                        ),
                    )
                    logcat(LogPriority.WARN) {
                        "TachiyomiAT t924 analysis refusal at chunk=${chunk.chunkId}: ${outcome.reason}"
                    }
                    return BatchPass1Outcome(
                        needsTranslation = emptyList(),
                        status = BatchPass1Status.PAUSED,
                        anchorPageKey = chunk.corePageKeys.firstOrNull(),
                        failure = outcome.failure,
                        reason = outcome.reason,
                    )
                }
            }
        }

        // Stop-after-chunks terminal for slice A: the validated chunk set is
        // durable; reconcile/freeze are slice B. PAUSED — never COMPLETED.
        return BatchPass1Outcome(
            needsTranslation = emptyList(),
            status = BatchPass1Status.PAUSED,
            completedPageKeys = corpus.entries.mapTo(mutableSetOf()) { it.storagePageKey },
            reason = ANALYSIS_STOP_REASON,
        )
    }

    /**
     * Rebuilds the analysis corpus from the durable checkpoints. Each entry
     * carries the wire identities the analysis request/evidence universe uses
     * (`p<N>` pages, `p<N>_b<M>` blocks) alongside the persisted identities.
     */
    private fun corpusEntriesFromCheckpoints(
        artifact: ChapterArtifactStore,
        orderedPages: List<PageKey>,
        expectedPageCount: Int,
    ): AnalysisCorpus? {
        val manifest = store.artifactManifest ?: return null
        val entries = mutableListOf<AnalysisCorpusEntry>()
        for ((pageKey, pageIndex) in orderedPages) {
            val pointer = manifest.ocrCheckpoints[pageKey] ?: return null
            val checkpoint = when (val read = artifact.readOcrCheckpoint(pointer)) {
                is ChapterArtifactStore.OcrCheckpointRead.Usable -> read.checkpoint
                else -> return null
            }
            val snapshot = artifact.readPageSnapshot(checkpoint.ocrPageSnapshotPointer.fileName)
                ?: return null
            val wirePageKey = "p$pageIndex"
            val blocks = snapshot.blocks
            val wireBlockIds = blocks.mapIndexed { index, block ->
                wireBlockId(block.blockId, wirePageKey, index)
            }
            entries += AnalysisCorpusEntry(
                storagePageKey = pageKey,
                naturalPageIndex = pageIndex,
                contentFingerprint = checkpoint.ocrContentFingerprint,
                snapshotPointer = checkpoint.ocrPageSnapshotPointer,
                wirePageKey = wirePageKey,
                wireBlockIds = wireBlockIds,
                blockTexts = blocks.map { it.text },
                estimatedInputTokens = blocks.sumOf { (it.text.length + 3) / 4 }.coerceAtLeast(1),
            )
        }
        val corpusManifest = OcrCorpusManifest.assemble(
            pages = entries.map { entry ->
                OcrCorpusPageEntry(
                    pageKey = entry.storagePageKey,
                    naturalPageIndex = entry.naturalPageIndex,
                    contentFingerprint = entry.contentFingerprint,
                    trusted = true,
                )
            },
            expectedPageCount = expectedPageCount,
            expectedPageCountTrusted = true,
        )
        return AnalysisCorpus(entries = entries, corpusFingerprint = corpusManifest.corpusFingerprint)
    }

    /** `p<N>_b<M>` canonicalization: trusted as stored, else synthesized. */
    private fun wireBlockId(storedBlockId: String?, wirePageKey: String, index: Int): String {
        val id = storedBlockId.orEmpty()
        if (id.matches(Regex("p\\d+_b\\d+"))) return id
        val local = Regex("^(?:p\\d+_)?b(\\d+)$").find(id)?.groupValues?.getOrNull(1)
        return "${wirePageKey}_b${local ?: index.toString()}"
    }

    /** One rebuilt-corpus OCR page entry (durable checkpoint + wire identity). */
    private class AnalysisCorpusEntry(
        val storagePageKey: String,
        val naturalPageIndex: Int,
        val contentFingerprint: String,
        val snapshotPointer: SidecarPointer,
        val wirePageKey: String,
        val wireBlockIds: List<String>,
        val blockTexts: List<String>,
        val estimatedInputTokens: Int,
    )

    private class AnalysisCorpus(
        val entries: List<AnalysisCorpusEntry>,
        val corpusFingerprint: String,
    ) {
        /** Chunks are planned over STORAGE page keys; look them up here. */
        private val byStorageKey = entries.associateBy { it.storagePageKey }

        /** Wire evidence universe + source texts for one planned chunk. */
        fun evidenceTextsFor(chunk: PlannedAnalysisChunk): AnalysisEvidenceTexts {
            val blockIdsByPage = mutableMapOf<String, List<String>>()
            val textByBlockId = mutableMapOf<String, String>()
            val wirePageKeyByStorageKey = mutableMapOf<String, String>()
            for (pageKey in chunk.contributingPageKeys) {
                val entry = byStorageKey[pageKey] ?: continue
                wirePageKeyByStorageKey[pageKey] = entry.wirePageKey
                blockIdsByPage[entry.wirePageKey] = entry.wireBlockIds
                entry.wireBlockIds.forEachIndexed { index, blockId ->
                    textByBlockId[blockId] = entry.blockTexts[index]
                }
            }
            return AnalysisEvidenceTexts(
                blockIdsByPage = blockIdsByPage,
                textByBlockId = textByBlockId,
                wirePageKeyByStorageKey = wirePageKeyByStorageKey,
            )
        }

        /**
         * Maps a validated response onto the durable publication input:
         * persistable subset only, evidence page keys translated from wire
         * `p<N>` to the persisted page keys, and OCR artifact pointers in
         * contributing (core-then-context) order.
         */
        fun publicationInput(
            chunk: PlannedAnalysisChunk,
            outcome: AnalysisChunkRunOutcome.Completed,
        ): AnalysisChunkPublication.AnalysisChunkPublicationInput? {
            val refs = mutableListOf<SidecarPointer>()
            for (pageKey in chunk.contributingPageKeys) {
                val entry = byStorageKey[pageKey] ?: return null
                refs += entry.snapshotPointer
            }

            fun storageKey(wireKey: String): String =
                entries.firstOrNull { it.wirePageKey == wireKey }?.storagePageKey ?: wireKey

            val scenes = outcome.response.scenes.map { scene ->
                ProfileScene(
                    sceneId = scene.sceneId,
                    pageRange = PageRange(
                        firstNaturalPageIndex = naturalIndexOrZero(scene.fromPageWireKey),
                        lastNaturalPageIndex = naturalIndexOrZero(scene.toPageWireKey),
                    ),
                    participants = scene.participants,
                    toneFlags = (scene.tone + scene.contentTags).mapNotNull { name ->
                        when (name) {
                            "EXPLICIT", "INTIMATE", "VIOLENT", "COMEDIC", "SERIOUS", "ACTION" ->
                                ToneFlag.valueOf(name)
                            else -> ToneFlag.OTHER
                        }
                    }.toSet(),
                    register = SceneRegister.entries.firstOrNull { it.name == scene.register }
                        ?: SceneRegister.OTHER,
                    narrativeContext = scene.narrative,
                )
            }
            return AnalysisChunkPublication.AnalysisChunkPublicationInput(
                provenance = outcome.provenance,
                ocrArtifactRefs = refs,
                terms = outcome.response.terms.map { term ->
                    ExtractedTerm(
                        termId = term.termId,
                        sourceForm = term.sourceForm,
                        canonicalTarget = term.canonicalTarget,
                        aliases = term.aliases,
                        kind = ExtractedTermKind.entries.firstOrNull { it.name == term.kind }
                            ?: ExtractedTermKind.TERM,
                    )
                },
                entities = outcome.response.entities.map { entity ->
                    ExtractedEntity(
                        entityId = entity.entityId,
                        canonicalSourceName = entity.canonicalSourceName,
                        proposedTargetName = entity.proposedTargetName,
                        sourceNames = entity.sourceNames,
                        titles = entity.titles,
                    )
                },
                relationships = outcome.response.entities.flatMap { entity ->
                    entity.relationships.map { relationship ->
                        ExtractedRelationship(
                            type = relationship.type,
                            sourceEntityId = relationship.sourceEntityId,
                            targetEntityId = relationship.targetEntityId,
                        )
                    }
                },
                scenes = scenes,
                narrativeSummary = outcome.response.narrativeSummary,
                conflictNotes = outcome.response.conflictNotes,
                evidenceRefs = outcome.response.evidenceRefs.map { ref ->
                    ref.copy(pageKey = storageKey(ref.pageKey))
                },
            )
        }

        private fun naturalIndexOrZero(wirePageKey: String): Int =
            wirePageKey.removePrefix("p").toIntOrNull()?.coerceAtLeast(0) ?: 0
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

        /** Stage-5 slice A terminals (still PAUSED — slice B owns the freeze). */
        const val ANALYSIS_STOP_REASON =
            "T924 analysis chunks persisted; profile reconcile/freeze arrive in slice B"
        const val ANALYSIS_NO_WORK_REASON =
            "T924 analysis skipped: no chunkable OCR work in this chapter"
        const val ANALYSIS_NO_TRANSPORT_REASON =
            "T924 analysis paused: no typed analysis transport wired (CONFIGURATION gate)"

        /** Analysis output budget default (T924-AP-03 `outputBudget`). */
        const val ANALYSIS_MAX_OUTPUT_TOKENS = 8192

        /** FF-01d flag state, frozen as an operational (never fingerprinted) counter. */
        const val COUNTER_FLAG = "flagProfilePipeline"
        const val COUNTER_TOTAL = "ocrPagesTotal"
        const val COUNTER_DONE = "ocrPagesDone"
        const val COUNTER_REUSED = "ocrPagesReused"
        const val COUNTER_STOP = "preflightStop"
        const val COUNTER_GAPS = "preflightCheckpointGaps"
        const val COUNTER_ANALYSIS_PLAN = "analysisPlanPublished"
        const val COUNTER_CHUNKS_TOTAL = "analysisChunksTotal"
        const val COUNTER_CHUNKS_DONE = "analysisChunksDone"
        const val COUNTER_CHUNKS_PENDING = "analysisChunksPending"
        const val COUNTER_CHUNKS_FAILURES = "analysisChunkFailures"
        const val COUNTER_SKIPPED_NO_WORK = "analysisSkippedNoWork"
        const val COUNTER_SKIPPED_NO_TRANSPORT = "analysisSkippedNoTransport"

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
            /** Opaque credential signature (one-way hash); never a raw key. */
            credentialId: String = "",
        ): RunConfigSnapshot = RunConfigSnapshot(
            sourceLang = sourceLang,
            targetLang = targetLang,
            ocrEngine = ocrEngine,
            ocrModelHash = ocrModelHash,
            detectorModelHash = detectorModelHash,
            inpaintMode = inpaintMode,
            providerKey = providerKey,
            credentialId = credentialId,
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
