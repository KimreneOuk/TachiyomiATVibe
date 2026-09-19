package eu.kanade.translation.pipeline.batch

import eu.kanade.translation.ChapterTranslationStore
import eu.kanade.translation.CheckpointOcrResult
import eu.kanade.translation.LeaseAcquisition
import eu.kanade.translation.OcrStagePatch
import eu.kanade.translation.context.SeriesProfileRegistry
import eu.kanade.translation.model.PageStage
import eu.kanade.translation.PageWriteOrigin
import eu.kanade.translation.StagePatchResult
import eu.kanade.translation.artifact.AnalysisChunkCoverage
import eu.kanade.translation.artifact.AnalysisChunkResult
import eu.kanade.translation.artifact.AnalyzerProvenance
import eu.kanade.translation.artifact.EvidenceStrength
import eu.kanade.translation.artifact.FactConflictState
import eu.kanade.translation.artifact.FactProvenance
import eu.kanade.translation.artifact.FactScope
import eu.kanade.translation.artifact.FactType
import eu.kanade.translation.artifact.ProfileFact
import eu.kanade.translation.artifact.ArtifactDocumentJson
import eu.kanade.translation.artifact.ChapterArtifactManifest
import eu.kanade.translation.artifact.ArtifactStage
import eu.kanade.translation.artifact.ArtifactStageStatus
import eu.kanade.translation.artifact.ChapterArtifactEngine
import eu.kanade.translation.artifact.ChapterAttemptLedgerDocument
import eu.kanade.translation.artifact.ChapterRunRecord
import eu.kanade.translation.artifact.ChapterRunState
import eu.kanade.translation.artifact.ChapterTranslationProfile
import eu.kanade.translation.artifact.DurableFailureMetadata
import eu.kanade.translation.artifact.EnvelopePlan
import eu.kanade.translation.artifact.ExtractedEntity
import eu.kanade.translation.artifact.ExtractedRelationship
import eu.kanade.translation.artifact.ExtractedTerm
import eu.kanade.translation.artifact.ExtractedTermKind
import eu.kanade.translation.artifact.FailureCategory
import eu.kanade.translation.artifact.OcrCheckpointMode
import eu.kanade.translation.artifact.PageRange
import eu.kanade.translation.artifact.ProfilePointer
import eu.kanade.translation.artifact.ProfileScene
import eu.kanade.translation.artifact.RunConfigSnapshot
import eu.kanade.translation.artifact.SceneRegister
import eu.kanade.translation.artifact.SidecarPointer
import eu.kanade.translation.artifact.SidecarRead
import eu.kanade.translation.artifact.StageFingerprints
import eu.kanade.translation.artifact.ToneFlag
import eu.kanade.translation.artifact.isSha256Hex
import eu.kanade.translation.model.PageDisplayState
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.hasCommittedDisplay
import eu.kanade.translation.model.hasRecognizedTranslation
import eu.kanade.translation.model.hasRenderedResult
import eu.kanade.translation.model.isTextlessTerminal
import eu.kanade.translation.model.recordAttemptFailure
import eu.kanade.translation.ocrBlockFingerprints
import eu.kanade.translation.ocrFingerprint
import eu.kanade.translation.translator.ProviderFailure
import eu.kanade.translation.translator.TextTranslator
import eu.kanade.translation.translator.contextual.ContextualTextTranslator
import eu.kanade.translation.translator.SharedBatchRequestSublimitGate
import eu.kanade.translation.translator.BatchRequestSublimitGate
import eu.kanade.translation.translator.TranslatorComputeClass
import eu.kanade.translation.translator.analysis.AnalysisChunkRunner
import eu.kanade.translation.translator.analysis.AnalysisChunkRunOutcome
import eu.kanade.translation.translator.analysis.AnalysisCoverageKind
import eu.kanade.translation.translator.analysis.AnalysisEvidenceTexts
import eu.kanade.translation.translator.analysis.AnalysisRequestBuilder
import eu.kanade.translation.translator.analysis.AnalysisRunIdentity
import eu.kanade.translation.translator.analysis.GlossaryEntryKind
import eu.kanade.translation.translator.analysis.GlossarySynthesizer
import eu.kanade.translation.translator.analysis.GlossarySynthesisOutcome
import eu.kanade.translation.translator.analysis.AnalyzerProvenanceFactory
import eu.kanade.translation.translator.contextual.AnalysisChunkPlanResult
import eu.kanade.translation.translator.contextual.AnalysisChunkPlanner
import eu.kanade.translation.translator.contextual.AnalysisChunkPolicy
import eu.kanade.translation.translator.contextual.ChunkPlannerPage
import eu.kanade.translation.translator.contextual.EnvelopePlannerBlock
import eu.kanade.translation.translator.contextual.EnvelopePlannerPage
import eu.kanade.translation.translator.contextual.EnvelopePlannerPolicy
import eu.kanade.translation.translator.contextual.EnvelopePlanResult
import eu.kanade.translation.translator.contextual.GlobalEnvelopePlanner
import eu.kanade.translation.translator.contextual.OcrCorpusManifest
import eu.kanade.translation.translator.contextual.OcrCorpusPageEntry
import eu.kanade.translation.translator.contextual.PlannedAnalysisChunk
import eu.kanade.translation.translator.contextual.TranslationContextChunkPlanner
import eu.kanade.translation.util.ShortHash
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.security.MessageDigest

/**
 * T924 — the chapter-profile Batch coordinator: THE Batch coordinator for
 * BOTH engine lanes since the zero-legacy wave (the FF-01 A/B flag completed
 * its lifecycle and the legacy SequentialBatchCoordinator was deleted).
 * STANDARD engines run its per-page standard tail; AI engines run the
 * analysis/profile/envelope phases below. Stage 3 landed the durable machine
 * through OCR_PREFLIGHT (T924-ST-02..06); Stage-5 slice A continues after a
 * COMPLETE preflight into:
 *
 *   ANALYSIS_PLAN (ST-07: corpus re-derived from the durable checkpoints,
 *   skip rules recorded) -> ANALYSIS_CHUNKS (ST-08: resume skips the
 *   persisted pointer prefix; each chunk runs through the typed analysis
 *   runner and persists crash-safely via [AnalysisChunkPublication]) ->
 *   PROFILE_RECONCILE (ST-09: pure deterministic reconciler over the durable
 *   chunks) -> PROFILE_FROZEN (ST-10: one atomic TX-22 publication) ->
 *   STOP with a PAUSED diagnostic (envelope/translation are Stage 6).
 *
 * Stage-5 slice B also owns the ST-05/OCR_PLAN skip rule: at run start, a
 * compatible frozen profile (valid sidecar + matching FP-04 input identity +
 * recomputed FP-05) skips the ENTIRE run through analysis with zero OCR and
 * zero provider calls.
 *
 * Invariants kept by the loop (gates §2.3 + §2.4):
 *  - ONE decoded page at a time: the preflight loop is strictly serial, the
 *    OCR worker's native handoff is released before the next page is admitted,
 *    and the page lease is released strictly AFTER the checkpoint committed
 *    (T924-TX-06).
 *  - No inpaint, no translation, no display promotion in the preflight;
 *    provider calls happen inside the analysis phase (AI lane, through the
 *    typed runner — never `promptText`) and inside the standard translate
 *    tail (standard lane, through the injected per-page seam), both gated by
 *    the 15-RPM Batch sub-limit + shared provider bucket discipline
 *    (T924-AP-08, DR-C/DR-D; the standard tail additionally brackets every
 *    per-page call with the overlap window so native inpaint never overlaps
 *    translation — ALL standard engines, MLKit included).
 *  - Resume re-enters OCR_PREFLIGHT (checkpoint reuse by content identity) or
 *    ANALYSIS_CHUNKS (never re-sends persisted chunks, ST-08).
 *  - The run record ([ChapterRunRecord]) is published at run start (FF-01d)
 *    and advanced at phase transitions + chunk completions; counter
 *    publications are best-effort progress carriers — the manifest's
 *    checkpoints and `analysisChunks` pointers stay authoritative
 *    (T924-ST-06/ST-08).
 *
 * T924 Phase 4 Wave A ([standardLane]): the DIRECTOR design — both AI and
 * standard engines do the same OCR, and the standard engine continues with
 * batch translation exactly like AI minus everything AI-specific: NO
 * frozen-profile reuse probe, NO analysis/profile/envelope phases or
 * pointers, NO glossary reads/writes. Translation runs IN ORDER per page
 * through the injected seam (LEGACY per-page commit machinery), with the
 * Stage-7 overlap windows + FINALIZE (completion = translation-terminal
 * WITHOUT in-pass render, exactly like the AI lane).
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

/**
 * T934 R2a.5: the typed reason a page's durable checkpoint could not back
 * this run at consumption. Each reason is a bounded run-record counter key
 * (the `ocrAdopt*` family, mirroring the bounded `ocrPages*` keys) and a
 * WARN log field — the re-OCR cliff is now observable per cause instead of
 * one untyped WARN. [NO_POINTER] doubles as the quiet "nothing to adopt
 * yet" answer of the reuse probe on a never-checkpointed page: only the
 * adoption ATTEMPT sites (a pointer existed and was lost, or the probe
 * failed on an existing checkpoint) count and warn.
 */
internal enum class CheckpointAdoptionFailure(val counterKey: String) {
    /** No checkpoint pointer for the page (or the manifest record vanished mid-walk). */
    NO_POINTER("ocrAdoptNoPointer"),

    /** The checkpoint sidecar is missing, corrupt, or an unsupported schema. */
    SIDE_CAR_UNREADABLE("ocrAdoptSideCarUnreadable"),

    /** Source identity mismatch — checkpoint, recorded digest, and/or decoded bytes disagree. */
    SHA_MISMATCH("ocrAdoptShaMismatch"),

    /** The BATCH OCR stage lease could not be acquired for the hydration merge. */
    LEASE_DENIED("ocrAdoptLeaseDenied"),

    /** The identity-fenced `mergeOcr` rejected the hydration merge. */
    MERGE_REJECTED("ocrAdoptMergeRejected"),

    /** The checkpoint's OCR page-snapshot bundle is unreadable. */
    BUNDLE_MISSING("ocrAdoptBundleMissing"),
}

/** Typed outcome of the checkpoint-reuse probe for one page (T934 R2a). */
internal sealed interface CheckpointReuse {
    /** The checkpoint's OCR content fingerprint; source identity was proven. */
    data class Reusable(val ocrContentFingerprint: String) : CheckpointReuse

    /** Why the checkpoint cannot back this run (typed; the page re-runs — fail closed). */
    data class Unavailable(val failure: CheckpointAdoptionFailure) : CheckpointReuse
}

/** Typed outcome of one checkpoint-adoption (hydration) attempt (T934 R2a). */
internal sealed interface CheckpointAdoption {
    data class Adopted(val snapshot: ChapterTranslationStore.PageSnapshot) : CheckpointAdoption

    data class Failed(
        val failure: CheckpointAdoptionFailure,
        val detail: String? = null,
    ) : CheckpointAdoption
}

internal class ChapterProfileBatchCoordinator(
    private val store: ChapterTranslationStore,
    private val nativeWorker: NativeLaneWorker,
    private val frozenConfig: RunConfigSnapshot,
    /** Ordered (pageKey, sourceSha256) pairs; the run's source digest input. */
    private val orderedSourcePairs: List<Pair<String, String>>,
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
    /**
     * Director decision (summary-glossary redesign): the one-shot chapter
     * glossary builder over the durable chunk summaries. `null` is a typed
     * CONFIGURATION-class gate exactly like [analysisChunkRunner] — the run
     * pauses at PROFILE_RECONCILE instead of synthesizing without a transport.
     */
    private val glossarySynthesizer: GlossarySynthesizer? = null,
    /**
     * Stage-6 slice A: the typed AI text translator for the envelope phase
     * (ST-11/ST-12). `null` is a typed CONFIGURATION-class gate: the run
     * plans nothing provider-bound and pauses at TRANSLATE — exactly like
     * the analysis runner seam above. Production wiring of BOTH seams is
     * the provider package's acceptance condition; tests drive the seam
     * with fakes. Wave A: widened to [TextTranslator] so the standard lane
     * can carry its plain per-page translator; the envelope path still
     * requires the contextual type through a local cast (a non-contextual
     * translator on the AI lane takes the same CONFIGURATION pause).
     */
    private val textTranslator: TextTranslator? = null,
    /**
     * Stage-6 slice A: the Batch sub-limit gate every translation envelope
     * must ride (wave-4 F-W4-2: ONE allowance per credential for ALL Batch
     * traffic). Defaults to the process-wide shared gate.
     */
    private val translationSublimitGate: BatchRequestSublimitGate =
        SharedBatchRequestSublimitGate.instance,
    /**
     * T924 Stage 7 (D1): the inpaint-overlap scheduler. When present, the
     * TRANSLATE phase wraps [translationSublimitGate] so every provider
     * envelope window drives serial inpaint of committed pages (ST-13
     * overlap), and the FINALIZE drains remaining pages serially (the
     * gate-6.5 "keep serial" arm — semantics identical). `null` keeps the
     * pre-Stage-7 machine shape exactly.
     */
    private val overlapScheduler: OverlapScheduler? = null,
    /**
     * T924 Stage 7 (D2): the render join that owns the T924-TX-23
     * persisted-layout publication transaction. When present (and FF-02 ON),
     * every inpaint-committed page publishes its draw plan per page, and the
     * FINALIZE sweeps any page the overlap hook missed. `null` keeps the
     * async-planner fallback for every page (reader display never breaks).
     */
    private val renderJoin: BatchRenderJoin? = null,
    /**
     * T924 Phase 4 Wave A: the STANDARD-engine lane discriminator. `false`
     * (default) preserves the AI coordinator behavior exactly; `true` runs
     * the same OCR preflight and then — instead of the AI
     * analysis/profile/envelope phases — the per-page standard translate
     * tail ([runStandardTranslateAndFinalize]) and the shared FINALIZE.
     */
    private val standardLane: Boolean = false,
    /**
     * T924 Phase 4 Wave A: the typed standard translate seam, injected by
     * the shell so the coordinator never touches the legacy worker graph
     * directly. Mirrors `TranslatorLaneWorker.translateOutcome(ref)` (the
     * SBC per-page bridge): one page in, one typed [ChunkCompletionOutcome]
     * out — commits ride the LEGACY per-page machinery, never the envelope
     * provenance ladder. `null` with [standardLane] is a typed
     * CONFIGURATION-class pause (same discipline as the analysis runner).
     */
    private val standardTranslateOutcome: (suspend (OcrReadyPageRef) -> ChunkCompletionOutcome)? = null,
    private val envelopePlannerPolicy: EnvelopePlannerPolicy? = null,
    private val seriesKey: String? = null,
) {

    private val sourceShaByPageKey: Map<String, String> = orderedSourcePairs.toMap()

    /**
     * T934 R2a: the per-page source digests RECORDED durably at first
     * admission ([ChapterArtifactManifest.sourceShaByPageKey], stamped by the
     * checkpoint transaction). Read once at run start and held stable for the
     * whole run, exactly like the constructor pairs.
     */
    private val recordedSourceShaByPageKey: Map<String, String> by lazy {
        store.artifactManifest?.sourceShaByPageKey.orEmpty()
    }

    /**
     * T934 R2a: the per-page ADMISSION identity — the recorded digest wins
     * when the dispatch-time observation is absent or a non-hex placeholder
     * (the shell's UNKNOWN_SOURCE_FINGERPRINT on a hash failure — a value
     * that previously poisoned reuse identity forever and forced re-OCR); a
     * well-formed dispatch observation (a fresh sha of the current bytes)
     * still wins over the record so a changed source is DETECTED at
     * consumption and fails closed; a page with neither keeps its (null)
     * constructor value for the store's own fallback.
     */
    private fun admissionSourceSha(pageKey: String): String? {
        val offered = sourceShaByPageKey[pageKey]
        if (offered != null && offered.isSha256Hex()) return offered
        return recordedSourceShaByPageKey[pageKey] ?: offered
    }

    /**
     * T934 R2a.2: the run's ordered (pageKey, admission sha) pairs — the
     * SINGLE orderedSourceDigest input for every record of this run. Derived
     * from the RECORDED digests wherever they exist, so run-start identity no
     * longer re-reads page bytes and a dispatch hash failure can no longer
     * break runId/ST-14 continuity across a resume. Evaluated once (the same
     * run-start read that feeds the reuse probes), then stable.
     */
    private val effectiveSourcePairs: List<Pair<String, String>> by lazy {
        orderedSourcePairs.map { (pageKey, sha) ->
            pageKey to (admissionSourceSha(pageKey) ?: sha)
        }
    }

    /**
     * The batch pass-1 entry the shell's dispatch point calls (formerly
     * call-shape-compatible with the deleted legacy coordinator's
     * `runPass1`). [computeClass] is accepted for call-shape parity only —
     * this stage never dispatches a provider lane.
     */
    suspend fun runPass1(
        orderedPages: List<PageKey>,
        computeClass: TranslatorComputeClass,
    ): BatchPass1Outcome {
        if (orderedPages.isEmpty()) {
            return BatchPass1Outcome(needsTranslation = emptyList())
        }
        currentCoroutineContext().ensureActive()
        // The coordinator retains this compatibility token while each actual
        // engine operation is routed through the facade's locked seams below.
        val artifact = store.withArtifactEngineLocked { it }
        if (artifact == null || store.artifactManifest == null) {
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
        val sourceDigest = orderedSourceDigest(effectiveSourcePairs)
        val priorRecord = (existingActiveRecord(artifact) as? ChapterArtifactEngine.RunRecordRead.Usable)?.record
        // ST-15: settings apply next run — a resume continues the recorded run
        // only while the frozen configuration fingerprint still matches; a
        // mismatch starts a NEW run id under the current configuration.
        val runId = priorRecord
            ?.takeIf { it.frozenRunConfigFingerprint == frozenFingerprint }
            ?.runId
            ?: newRunId(sourceDigest, frozenFingerprint)

        // ---- ST-14 resume gates: a record already past TRANSLATE never ----
        // ---- steps the durable state BACKWARD to RUN_SNAPSHOT.         ----
        resumeFinalizeOrComplete(
            artifact = artifact,
            priorRecord = priorRecord,
            frozenFingerprint = frozenFingerprint,
            sourceDigest = sourceDigest,
            orderedPages = orderedPages,
        )?.let { resumed -> return resumed }

        val total = orderedPages.size
        var reusedPages = 0
        var checkpointedPages = 0
        val corpusFingerprints = mutableListOf<Pair<String, String>>()

        // T934 R2a.5: per-run typed adoption-failure tally. Emitted as bounded
        // `ocrAdopt*` counters ONLY when nonzero, so steady-state record
        // counter maps stay byte-identical and the 32-key phaseCounters bound
        // is never pressured on healthy runs.
        val adoptionFailures = mutableMapOf<CheckpointAdoptionFailure, Int>()

        fun recordAdoptionFailure(pageKey: String, failure: CheckpointAdoptionFailure, detail: String?) {
            adoptionFailures.merge(failure, 1, Int::plus)
            logcat(LogPriority.WARN) {
                "TachiyomiAT t924 preflight checkpoint adoption failed pageHash=${pageHash(pageKey)} " +
                    "reason=${failure.name}" +
                    (detail?.let { " detail=$it" } ?: "") +
                    " — re-OCRing"
            }
        }

        fun counters(): Map<String, Int> = mapOf(
            COUNTER_TOTAL to total,
            COUNTER_DONE to (reusedPages + checkpointedPages),
            COUNTER_REUSED to reusedPages,
            // Kept for pre-field record compatibility; the authoritative
            // freeze is frozenConfig.flagProfilePipeline (participates in the
            // run-config fingerprint; counters never do, per FP-01). The
            // FF-01 A/B flag completed its lifecycle — the profile pipeline
            // is the only pipeline — so the frozen state is always ON.
            COUNTER_FLAG to 1,
        ) + buildMap {
            // Appended AFTER the fixed keys so the publishRecord over-bound
            // trim (takeLast) drops these first, never the phase-critical
            // `ocrPages*` keys.
            if (adoptionFailures.isNotEmpty()) {
                put(COUNTER_ADOPT_FAILED, adoptionFailures.values.sum())
                adoptionFailures.forEach { (failure, count) -> put(failure.counterKey, count) }
            }
        }

        // ---- ST-05/OCR_PLAN skip rule (contracts-state-transactions :114): ----
        // when a compatible frozen profile already exists (its sidecar reads
        // back valid, the pointer identities match, and the current run's
        // FP-04 input fingerprint equals the pointer's), the plan records
        // skip-to-phase PROFILE_FROZEN reuse and the ENTIRE run through
        // analysis is skipped: zero OCR, zero provider calls (the T924
        // fast-feedback core). The probe is LOCAL reads only (durable
        // checkpoints + profile sidecar), never decode/native work.
        // Wave A: AI-ONLY — the standard lane produces no frozen profile, so
        // the probe is fenced off (it would short-circuit into the envelope
        // phase, which requires one).
        val reusableProfile = if (standardLane) {
            null
        } else {
            frozenProfileReuse(artifact, orderedPages, total)
        }

        // ST-03: run start — RUN_SNAPSHOT record with the frozen configuration,
        // the ordered source digest, and the frozen flag state (FF-01d).
        publishRecord(
            artifact,
            record(runId, ChapterRunState.RUN_SNAPSHOT, frozenFingerprint, sourceDigest, counters()),
        )
        if (reusableProfile != null) {
            logcat(LogPriority.INFO) {
                "TachiyomiAT t924 profile reuse: compatible frozen profile, " +
                    "skipping OCR+analysis (skip-to-phase PROFILE_FROZEN)"
            }
            publishRecord(
                artifact,
                record(
                    runId,
                    ChapterRunState.PROFILE_FROZEN,
                    frozenFingerprint,
                    sourceDigest,
                    counters() + mapOf(
                        COUNTER_STOP to 1,
                        COUNTER_PROFILE_REUSED to 1,
                    ),
                    ocrCorpusFingerprint = reusableProfile.corpusFingerprint,
                    profilePointer = reusableProfile.pointer,
                ),
            )
            // Stage-6 slice A: the reuse path CONTINUES into the envelope
            // phase — the whole point of the frozen-profile skip is
            // translating under the reused profile with zero re-OCR and
            // zero provider analysis (D5).
            return runEnvelopePlanAndTranslate(
                artifact = artifact,
                runId = runId,
                orderedPages = orderedPages,
                corpusFingerprint = reusableProfile.corpusFingerprint,
                baseCounters = counters() + mapOf(COUNTER_PROFILE_REUSED to 1),
            )
        }

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

            when (val reusable = checkpointReuse(artifact, pageKey)) {
                is CheckpointReuse.Reusable -> {
                    // ST-06 resume rule: the page's origin-neutral checkpoint matches
                    // the current source identity — no re-OCR, no lease, no decode.
                    //
                    // T924 zero-legacy (D1): a reused checkpoint must also BACK the
                    // live store page. A reopened store (real restart, or the
                    // memory-only artifact-authority fixture) holds only a
                    // placeholder page record — its ocrStatus/blocks live in the
                    // durable checkpoint sidecar. Without adoption the translate
                    // tail's dependency gate reads WAIT_FOR_DEPENDENCY /
                    // DEPENDENCY_INCOMPLETE against the placeholder and silently
                    // skips the page's paid translation — the run then "completes"
                    // without paying (D9's resumed-death cycle). Adopt the
                    // checkpointed OCR snapshot into the live store — the SAME
                    // hydration idiom the envelope lane's resume uses — and only
                    // then count the page as reused. If the adoption cannot back
                    // the page (unreadable sidecar, racing owner), fall through to
                    // a fresh OCR run: never plan against fabricated content.
                    val before = store.snapshot(pageKey)
                    val hydrated = before.page != null &&
                        before.page.ocrStatus == StageStatus.READY &&
                        before.page.blocks.isNotEmpty()
                    val adoption = if (hydrated) {
                        CheckpointAdoption.Adopted(before)
                    } else {
                        adoptCheckpointSnapshot(artifact, pageKey, before)
                    }
                    if (adoption is CheckpointAdoption.Adopted) {
                        reusedPages++
                        corpusFingerprints += pageKey to reusable.ocrContentFingerprint
                        // Same live-progress marks a fresh OCR page emits: without
                        // them the tracker's snapshot stays frozen at the pre-resume
                        // counts for the entire revalidation and the drawer looks
                        // unresponsive (2026-09-15/16 field report).
                        listener.ocrStarted(pageKey)
                        listener.ocrPublished(pageKey)
                        stampAdoptedRenderTerminal(pageKey)
                        logcat(LogPriority.INFO) {
                            "TachiyomiAT t924 preflight reused checkpoint pageHash=${pageHash(pageKey)}"
                        }
                        // M3: Batched advisory progress records: publish on first page, every 5 pages, or last page
                        val shouldPublishProgress = (pageIndex == 0) || ((pageIndex + 1) % 5 == 0) || ((pageIndex + 1) == total)
                        if (shouldPublishProgress) {
                            publishRecord(
                                artifact,
                                record(runId, ChapterRunState.OCR_PLAN, frozenFingerprint, sourceDigest, counters()),
                            )
                        }
                        continue
                    }
                    // T934 R2a.5: the re-OCR cliff is now TYPED (counter + WARN
                    // reason) instead of one untyped warning. The sealed
                    // hierarchy has exactly two cases, so the early-continue
                    // above leaves only Failed — spelled as a `when` because
                    // the compiler does not narrow a sealed type from a
                    // negated `is` after an if-statement.
                    when (adoption) {
                        is CheckpointAdoption.Failed ->
                            recordAdoptionFailure(pageKey, adoption.failure, adoption.detail)
                        is CheckpointAdoption.Adopted -> Unit
                    }
                }
                is CheckpointReuse.Unavailable -> {
                    // NO_POINTER is the quiet never-checkpointed answer (a normal
                    // fresh OCR below); every other reason means a checkpoint
                    // EXISTS but cannot back this run — the typed cliff.
                    if (reusable.failure != CheckpointAdoptionFailure.NO_POINTER) {
                        recordAdoptionFailure(pageKey, reusable.failure, null)
                    }
                }
            }

            listener.ocrStarted(pageKey)
            val pageStartedAt = System.currentTimeMillis()
            var ref: OcrReadyPageRef? = null
            // Set when THIS page's attempt ended unresolved (REJECTED checkpoint
            // or worker exception): the ledger record is written in `finally`,
            // AFTER the B0 candidate teardown — cancelCandidate strips
            // candidate-owned stage records, so the record must outlive it.
            var pendingFailure: PreflightStageFailure? = null
            try {
                ref = try {
                    // Per-page watchdog: lease acquisition, bitmap-budget
                    // permits, and SAF decode sit BEFORE the native lane's own
                    // 120s timeout, and a wedge in any of them used to stall
                    // the pass silently forever (2026-09-15 incident). Bound
                    // the whole page; the failure ledger path below makes the
                    // timeout visible and the pass continues.
                    withTimeout(PREFLIGHT_PAGE_TIMEOUT_MS) {
                        nativeWorker.runOcrStage(pageKey, pageIndex)
                    }
                } catch (e: TimeoutCancellationException) {
                    logcat(LogPriority.WARN) {
                        "TachiyomiAT t924 preflight page watchdog timeout pageHash=${pageHash(pageKey)} " +
                            "afterMs=${System.currentTimeMillis() - pageStartedAt}"
                    }
                    pendingFailure = PreflightStageFailure(
                        pageKey = pageKey,
                        kind = PreflightFailureKind.OCR_WORKER_FAILED,
                        reason = "preflight page watchdog timeout after ${PREFLIGHT_PAGE_TIMEOUT_MS}ms",
                    )
                    null
                }
                if (ref == null) {
                    // Lease-denied or otherwise unresolved: no checkpoint was
                    // published. The final diagnostic counts every
                    // uncheckpointed page as a gap.
                    logcat(LogPriority.INFO) {
                        "TachiyomiAT t924 preflight skipped pageHash=${pageHash(pageKey)} (denied or unresolved)"
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
                    // M3: Batched advisory progress records: publish on first page, every 5 pages, or last page
                    val shouldPublishProgress = (pageIndex == 0) || ((pageIndex + 1) % 5 == 0) || ((pageIndex + 1) == total)
                    if (shouldPublishProgress) {
                        publishRecord(
                            artifact,
                            record(runId, ChapterRunState.OCR_PLAN, frozenFingerprint, sourceDigest, counters()),
                        )
                    }
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
        store.flush()
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
            // Incomplete corpus (reused/unresolved gaps): analysis needs the
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

        // ---- T924 Phase 4 Wave A: the standard lane branches to its ----
        // ---- per-page translate tail; the AI lane continues into the   ----
        // ---- analysis phases (Stage 5 slices A+B). Both share the     ----
        // ---- engine-agnostic Stage-7 FINALIZE/COMPLETE.               ----
        if (standardLane) {
            return runStandardTranslateAndFinalize(
                artifact = artifact,
                runId = runId,
                orderedPages = orderedPages,
                corpusFingerprint = corpusFingerprint,
                baseCounters = finalCounters,
            )
        }

        // Milestone M5 (S6 / P8): Series-scoped profile carry-over (drift-gated).
        // If a series profile is registered and passes drift gating, adopt it
        // and skip the expensive analysis phase (ANALYSIS_PLAN -> ANALYSIS_CHUNKS -> PROFILE_RECONCILE).
        if (seriesKey != null) {
            val carried = SeriesProfileRegistry.get(seriesKey)
            if (carried != null && SeriesProfileRegistry.isDriftSafe(carried, frozenConfig)) {
                val adopted = SeriesProfileRegistry.adoptForChapter(
                    carried = carried,
                    runId = runId,
                    profileInputFingerprint = profileInputFingerprintOf(corpusFingerprint),
                    nowEpochMs = nowEpochMs(),
                )
                val manifestForPublish = store.artifactManifest
                if (manifestForPublish != null) {
                    val publication = ProfileFreezePublication.publish(
                        store = store,
                        manifest = manifestForPublish,
                        profile = adopted,
                        nowEpochMs = nowEpochMs(),
                    )
                    if (publication is ChapterArtifactEngine.TransactionOutcome.Committed) {
                        store.artifactManifest = publication.manifest
                        logcat(LogPriority.INFO) {
                            "TachiyomiAT M5 series profile carry-over adopted: version=${adopted.version} " +
                                "entities=${adopted.entities.size} terms=${adopted.terms.size}"
                        }
                        publishRecord(
                            artifact,
                            record(
                                runId,
                                ChapterRunState.PROFILE_FROZEN,
                                frozenFingerprint,
                                sourceDigest,
                                finalCounters + mapOf(
                                    COUNTER_PROFILE_FROZEN to 1,
                                    COUNTER_SERIES_PROFILE_CARRIED_OVER to 1,
                                    COUNTER_STOP to 1,
                                ),
                                ocrCorpusFingerprint = corpusFingerprint,
                                profilePointer = publication.manifest.profile,
                            ),
                        )
                        return runEnvelopePlanAndTranslate(
                            artifact = artifact,
                            runId = runId,
                            orderedPages = orderedPages,
                            corpusFingerprint = corpusFingerprint,
                            baseCounters = finalCounters + mapOf(COUNTER_SERIES_PROFILE_CARRIED_OVER to 1),
                        )
                    }
                }
            }
        }

        // ---- T924 Stage 5 slices A+B: ANALYSIS_PLAN -> ANALYSIS_CHUNKS ----
        // ---- -> PROFILE_RECONCILE -> PROFILE_FROZEN. The chapter stays   ----
        // ---- PAUSED (envelope/translation are Stage 6; completion        ----
        // ---- semantics are still NOT redefined).                         ----
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
     * durable and is never re-sent). When every chunk is durable, slice B's
     * [runProfileReconcileAndFreeze] continues into ST-09/ST-10; nothing
     * here publishes `COMPLETE` (wave-2 F1).
     */
    private suspend fun runAnalysisPhase(
        artifact: ChapterArtifactEngine,
        runId: String,
        orderedPages: List<PageKey>,
        corpusFingerprint: String,
        baseCounters: Map<String, Int>,
    ): BatchPass1Outcome {
        val total = orderedPages.size
        val frozenFingerprint = runConfigFingerprint(frozenConfig)
        val sourceDigest = orderedSourceDigest(effectiveSourcePairs)

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

        // Wave-4 F-W4-1: the persisted pointer prefix is resume-authoritative
        // ONLY when it came from THIS plan. A cross-run corpus change
        // (re-download → new checkpoints → re-planned windows) must never be
        // silently skipped into a mixed-plan chunk list — mismatch is a typed
        // PAUSED, exactly like the within-run drift gate above.
        validatePersistedPrefix(artifact, plan.chunks)?.let { staleReason ->
            return BatchPass1Outcome(
                needsTranslation = emptyList(),
                status = BatchPass1Status.PAUSED,
                completedPageKeys = corpus.entries.mapTo(mutableSetOf()) { it.storagePageKey },
                reason = staleReason,
            )
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
        // order, identity-validated above) is never re-sent; execution
        // restarts at the first missing chunk.
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
                        store = store,
                        manifest = store.artifactManifest ?: return BatchPass1Outcome(
                            needsTranslation = emptyList(),
                            status = BatchPass1Status.PERSISTENCE_REJECTED,
                            reason = "T924 analysis chunk publication: manifest unavailable",
                        ),
                        result = result,
                        nowEpochMs = nowEpochMs(),
                    )
                    when (publication) {
                        is ChapterArtifactEngine.TransactionOutcome.Committed -> {
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
                        is ChapterArtifactEngine.TransactionOutcome.Rejected -> {
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

        // ---- Stage-5 slice B: the validated chunk set is durable; run the ----
        // ---- ST-09 reconcile over the DURABLE chunk list, then ST-10 freeze.
        return runProfileReconcileAndFreeze(
            artifact = artifact,
            runId = runId,
            orderedPages = orderedPages,
            corpusFingerprint = corpusFingerprint,
            baseCounters = analysisCounters(
                mapOf(
                    COUNTER_CHUNKS_TOTAL to plan.chunks.size,
                    COUNTER_CHUNKS_DONE to done,
                    COUNTER_CHUNKS_PENDING to pending,
                ),
            ),
        )
    }

    /**
     * Stage-5 slice B (T924-ST-09 → ST-10, T924-TX-22): reconcile + freeze.
     *
     *  1. The durable chunk list is RE-READ from the manifest pointers
     *     (identity-consistent with the run: the wave-4 prefix validation
     *     above proved the persisted list IS this run's plan). Any
     *     unreadable/invalid sidecar is treated as ABSENT (T924-ST-30) — a
     *     typed pause, never a partial reconcile.
     *  2. PROFILE_RECONCILE phase record published, then the one-shot
     *     glossary synthesis (synthesizeProfileContent) runs over the
     *     durable chunk summaries — characters and places only (Director
     *     decision, summary-glossary redesign). A typed synthesis pause
     *     defers the freeze; the summaries stay durable for a later run.
     *  3. The profile DTO is assembled around the reconciled CONTENT with
     *     this run's FP-04 input fingerprint and the next monotonic chapter
     *     version; its FP-05 content fingerprint is computed over the DTO.
     *  4. [ProfileFreezePublication.publish] performs the ONE atomic
     *     TX-22 publication; on success the PROFILE_FROZEN record carries
     *     the new pointer and the run stops PAUSED (envelope/translation are
     *     Stage 6). On rejection the prior manifest stays authoritative.
     *
     * NOTHING here publishes `COMPLETE` (wave-2 F1 still owed to the first
     * COMPLETE-publishing stage) and no page display state is touched.
     */
    private suspend fun runProfileReconcileAndFreeze(
        artifact: ChapterArtifactEngine,
        runId: String,
        orderedPages: List<PageKey>,
        corpusFingerprint: String,
        baseCounters: Map<String, Int>,
    ): BatchPass1Outcome {
        val frozenFingerprint = runConfigFingerprint(frozenConfig)
        val sourceDigest = orderedSourceDigest(effectiveSourcePairs)

        fun freezeCounters(extra: Map<String, Int>): Map<String, Int> = baseCounters + extra

        // ST-09 entry: re-read the durable chunk list from the manifest.
        val manifestAtEntry = store.artifactManifest
        if (manifestAtEntry == null || manifestAtEntry.analysisChunks.isEmpty()) {
            return BatchPass1Outcome(
                needsTranslation = emptyList(),
                status = BatchPass1Status.PERSISTENCE_REJECTED,
                reason = "T924 profile reconcile deferred: durable chunk list unavailable",
            )
        }
        val chunks = mutableListOf<AnalysisChunkResult>()
        for (index in manifestAtEntry.analysisChunks.indices) {
            val read = store.withArtifactEngineLocked { artifact ->
                artifact.readSidecarDocument(
                    pointer = manifestAtEntry.analysisChunks[index],
                    serializer = AnalysisChunkResult.serializer(),
                    currentSchemaVersion = AnalysisChunkResult.SCHEMA_VERSION,
                    expectedKind = AnalysisChunkResult.KIND,
                    schemaVersionOf = { it.schemaVersion },
                    kindOf = { it.kind },
                    isValid = { it.isSemanticallyValid },
                )
            } ?: SidecarRead.Absent
            if (read !is SidecarRead.Usable) {
                // ST-30: unreadable/invalid target = absent, never partially
                // trusted. Resume re-validates the prefix (typed pause there).
                return BatchPass1Outcome(
                    needsTranslation = emptyList(),
                    status = BatchPass1Status.PAUSED,
                    reason = "T924 profile reconcile deferred: persisted chunk $index " +
                        "unreadable or invalid ($read)",
                )
            }
            chunks += read.document
        }

        // PROFILE_RECONCILE phase record (entered).
        publishRecord(
            artifact,
            record(
                runId,
                ChapterRunState.PROFILE_RECONCILE,
                frozenFingerprint,
                sourceDigest,
                freezeCounters(
                    mapOf(
                        COUNTER_PROFILE_CHUNKS_TOTAL to chunks.size,
                        COUNTER_PROFILE_CHUNKS_RECONCILED to 0,
                        COUNTER_PROFILE_CHUNKS_PENDING to 0,
                    ),
                ),
                ocrCorpusFingerprint = corpusFingerprint,
            ),
        )

        // Director decision (summary-glossary redesign): the profile's
        // content is ONE small identity sheet synthesized from the durable
        // chunk summaries — characters and places only, capped hard, because
        // anything beyond identity anchors is noise for the translation
        // envelopes. The deterministic cross-chunk reconcile of structured
        // extraction records is retired with the strict response contract.
        val synthesis = when (val source = synthesizeProfileContent(chunks)) {
            is ProfileContentSource.Content -> source
            is ProfileContentSource.Pause -> {
                logcat(LogPriority.WARN) {
                    "TachiyomiAT t924 glossary synthesis paused: ${source.reason}"
                }
                publishRecord(
                    artifact,
                    record(
                        runId,
                        ChapterRunState.PROFILE_RECONCILE,
                        frozenFingerprint,
                        sourceDigest,
                        freezeCounters(mapOf(COUNTER_PROFILE_RECONCILE_REJECTED to 1)),
                        ocrCorpusFingerprint = corpusFingerprint,
                    ),
                )
                return BatchPass1Outcome(
                    needsTranslation = emptyList(),
                    status = BatchPass1Status.PAUSED,
                    failure = source.failure,
                    reason = source.reason,
                )
            }
        }

        publishRecord(
            artifact,
            record(
                runId,
                ChapterRunState.PROFILE_RECONCILE,
                frozenFingerprint,
                sourceDigest,
                freezeCounters(
                    mapOf(
                        COUNTER_PROFILE_CHUNKS_TOTAL to chunks.size,
                        COUNTER_PROFILE_CHUNKS_RECONCILED to synthesis.summarizedChunks,
                        COUNTER_PROFILE_CHUNKS_PENDING to 0,
                    ),
                ),
                ocrCorpusFingerprint = corpusFingerprint,
            ),
        )

        // Assemble the DTO: operational envelope + the FP-05 content hash
        // (computed over the DTO with the operational fields zeroed, so the
        // hash is computed BEFORE the field is set — FP-05 cannot contain
        // itself). The input identity is the SAME FP-04 the reuse probe uses.
        val inputFingerprint = profileInputFingerprintOf(corpusFingerprint)
        val nextVersion = (store.artifactManifest?.profile?.version ?: 0) + 1
        val draft = ChapterTranslationProfile(
            version = nextVersion,
            contentFingerprint = "",
            profileInputFingerprint = inputFingerprint,
            sourceRunId = runId,
            analyzerProvenance = synthesis.analyzerProvenance,
            entities = synthesis.entities,
            terms = synthesis.terms,
            scenes = emptyList(),
            unresolvedFacts = emptyList(),
            seriesUpdateCandidates = emptyList(),
            correctionCandidates = emptyList(),
            frozenAtEpochMs = nowEpochMs(),
        )
        val profile = draft.copy(
            contentFingerprint = StageFingerprints.profileContentFingerprint(draft),
        )

        val manifestForPublish = store.artifactManifest ?: return BatchPass1Outcome(
            needsTranslation = emptyList(),
            status = BatchPass1Status.PERSISTENCE_REJECTED,
            reason = "T924 profile freeze deferred: manifest unavailable",
        )
        return when (
            val publication = ProfileFreezePublication.publish(
                store = store,
                manifest = manifestForPublish,
                profile = profile,
                nowEpochMs = nowEpochMs(),
            )
        ) {
            is ChapterArtifactEngine.TransactionOutcome.Committed -> {
                store.artifactManifest = publication.manifest
                seriesKey?.let { key ->
                    SeriesProfileRegistry.register(
                        seriesKey = key,
                        profile = profile,
                        sourceLang = frozenConfig.sourceLang,
                        targetLang = frozenConfig.targetLang,
                        providerKey = frozenConfig.providerKey,
                        nowEpochMs = nowEpochMs(),
                    )
                }
                publishRecord(
                    artifact,
                    record(
                        runId,
                        ChapterRunState.PROFILE_FROZEN,
                        frozenFingerprint,
                        sourceDigest,
                        freezeCounters(
                            mapOf(
                                COUNTER_PROFILE_CHUNKS_TOTAL to chunks.size,
                                COUNTER_PROFILE_CHUNKS_RECONCILED to synthesis.summarizedChunks,
                                COUNTER_PROFILE_CHUNKS_PENDING to 0,
                                COUNTER_PROFILE_FROZEN to 1,
                                COUNTER_STOP to 1,
                            ),
                        ),
                        ocrCorpusFingerprint = corpusFingerprint,
                        profilePointer = publication.manifest.profile,
                    ),
                )
                logcat(LogPriority.INFO) {
                    "TachiyomiAT t924 profile frozen version=$nextVersion " +
                        "synthesized=${synthesis.summarizedChunks} entries=${synthesis.entities.size + synthesis.terms.size}"
                }
                // Stage-6 slice A: PROFILE_FROZEN no longer terminates the
                // run — the coordinator CONTINUES into ENVELOPE_PLAN (ST-11)
                // and TRANSLATE (ST-12). The terminal stays PAUSED (native /
                // render are Stage 7; NEVER COMPLETE in this slice).
                return runEnvelopePlanAndTranslate(
                    artifact = artifact,
                    runId = runId,
                    orderedPages = orderedPages,
                    corpusFingerprint = corpusFingerprint,
                    baseCounters = freezeCounters(
                        mapOf(
                            COUNTER_PROFILE_CHUNKS_TOTAL to chunks.size,
                            COUNTER_PROFILE_CHUNKS_RECONCILED to synthesis.summarizedChunks,
                            COUNTER_PROFILE_CHUNKS_PENDING to 0,
                            COUNTER_PROFILE_FROZEN to 1,
                        ),
                    ),
                )
            }
            is ChapterArtifactEngine.TransactionOutcome.Rejected -> {
                // Prior manifest authoritative (TX-22); the run pauses; the
                // next attempt re-reconciles deterministically (ST-09 resume).
                logcat(LogPriority.WARN) {
                    "TachiyomiAT t924 profile freeze rejected: ${publication.reason}"
                }
                publishRecord(
                    artifact,
                    record(
                        runId,
                        ChapterRunState.PROFILE_RECONCILE,
                        frozenFingerprint,
                        sourceDigest,
                        freezeCounters(mapOf(COUNTER_PROFILE_FREEZE_REJECTED to 1)),
                        ocrCorpusFingerprint = corpusFingerprint,
                    ),
                )
                BatchPass1Outcome(
                    needsTranslation = emptyList(),
                    status = BatchPass1Status.PERSISTENCE_REJECTED,
                    reason = "T924 profile freeze rejected: ${publication.reason}",
                )
            }
        }
    }

    /** [synthesizeProfileContent] outcome: freezeable content or a typed pause. */
    private sealed interface ProfileContentSource {
        data class Content(
            val analyzerProvenance: AnalyzerProvenance,
            val entities: List<ProfileFact>,
            val terms: List<ProfileFact>,
            val summarizedChunks: Int,
        ) : ProfileContentSource

        data class Pause(val failure: ProviderFailure?, val reason: String) : ProfileContentSource
    }

    /**
     * ONE synthesis call over the durable chunk summaries builds the
     * profile's identity sheet. Characters become ENTITY_IDENTITY facts,
     * places become TERM facts; every fact is chapter-wide and deliberately
     * WEAK-strength — the summary path carries no per-block evidence, and
     * the sheet's job is consistent renderings, not auditable provenance.
     * An empty or missing glossary is still freezeable content (translation
     * proceeds without the sheet).
     */
    private suspend fun synthesizeProfileContent(
        chunks: List<AnalysisChunkResult>,
    ): ProfileContentSource {
        val synthesizer = glossarySynthesizer
            ?: return ProfileContentSource.Pause(
                failure = null,
                reason = GLOSSARY_SYNTHESIS_NO_TRANSPORT_REASON,
            )
        val summaries = chunks.mapNotNull { chunk ->
            chunk.narrativeSummary?.takeIf(String::isNotBlank)
        }
        return when (
            val outcome = synthesizer.synthesize(
                sourceLanguage = frozenConfig.sourceLang,
                targetLanguage = frozenConfig.targetLang,
                summaries = summaries,
            )
        ) {
            is GlossarySynthesisOutcome.Glossary -> {
                var entityOrdinal = 0
                var termOrdinal = 0
                val entities = outcome.entries
                    .filter { it.kind == GlossaryEntryKind.CHARACTER }
                    .map { entry ->
                        ProfileFact(
                            factId = "e%03d".format(++entityOrdinal),
                            type = FactType.ENTITY_IDENTITY,
                            canonicalSourceForm = entry.source,
                            canonicalTargetForm = entry.target,
                            aliases = entry.aliases,
                            evidenceStrength = EvidenceStrength.WEAK,
                            scope = FactScope.CANONICAL_CHAPTER_WIDE,
                            provenance = FactProvenance.CHAPTER_ANALYSIS,
                            conflictState = FactConflictState.RESOLVED,
                        )
                    }
                val terms = outcome.entries
                    .filter { it.kind == GlossaryEntryKind.PLACE }
                    .map { entry ->
                        ProfileFact(
                            factId = "t%03d".format(++termOrdinal),
                            type = FactType.TERM,
                            canonicalSourceForm = entry.source,
                            canonicalTargetForm = entry.target,
                            aliases = entry.aliases,
                            evidenceStrength = EvidenceStrength.WEAK,
                            scope = FactScope.CANONICAL_CHAPTER_WIDE,
                            provenance = FactProvenance.CHAPTER_ANALYSIS,
                            conflictState = FactConflictState.RESOLVED,
                        )
                    }
                ProfileContentSource.Content(
                    analyzerProvenance = chunks.last().analyzerProvenance,
                    entities = entities,
                    terms = terms,
                    summarizedChunks = summaries.size,
                )
            }
            is GlossarySynthesisOutcome.Paused -> ProfileContentSource.Pause(
                failure = outcome.failure,
                reason = "T924 glossary synthesis paused: ${outcome.reason}",
            )
        }
    }

    /**
     * Stage-6 slice A (T924-ST-11 + ST-12, TX-21/TX-20, DR-A Option 1):
     * ENVELOPE_PLAN -> TRANSLATE, entered from PROFILE_FROZEN (fresh freeze)
     * or from the ST-05 frozen-profile reuse branch.
     *
     *  1. The pending dispatch work is REBUILT from durable state (durable
     *     checkpoints + live per-page translation state; committed, skipped,
     *     manual-authoritative pages and user-edited blocks are never
     *     planned) and the pure [GlobalEnvelopePlanner] runs over it —
     *     deterministic, so a resume with unchanged inputs re-derives the
     *     SAME plan fingerprint and REUSES the published plan (ST-11 resume:
     *     re-plan if any input changed, else reuse).
     *  2. The [EnvelopePlan] sidecar + manifest pointer publish in ONE SC-20
     *     transaction when the fingerprint changed (superseding plan = new
     *     content-addressed file; TX-21 suffix re-plans ride the same path).
     *  3. TRANSLATE: the serial [ProfileEnvelopeExecutor] dispatches one
     *     envelope at a time through the legacy typed retry machinery under
     *     the shared Batch sub-limit gate, revalidating every page (TX-21)
     *     and committing through the TX-20 provenance ladder. Progress is
     *     durable per-page store state; the terminal stays PAUSED
     *     (TRANSLATE_STOP_REASON — native/render are Stage 7; this slice
     *     NEVER publishes COMPLETE).
     */
    private suspend fun runEnvelopePlanAndTranslate(
        artifact: ChapterArtifactEngine,
        runId: String,
        orderedPages: List<PageKey>,
        corpusFingerprint: String,
        baseCounters: Map<String, Int>,
    ): BatchPass1Outcome {
        val frozenFingerprint = runConfigFingerprint(frozenConfig)
        val sourceDigest = orderedSourceDigest(effectiveSourcePairs)
        val allPageKeys = orderedPages.mapTo(mutableSetOf()) { it.first }

        fun envelopeCounters(extra: Map<String, Int>): Map<String, Int> =
            baseCounters + extra

        fun envelopeRecord(state: ChapterRunState, counters: Map<String, Int>): ChapterRunRecord =
            record(
                runId,
                state,
                frozenFingerprint,
                sourceDigest,
                counters,
                ocrCorpusFingerprint = corpusFingerprint,
                profilePointer = store.artifactManifest?.profile,
            )

        // ST-11 entry: phase record, then plan (pure re-derivation).
        publishRecord(
            artifact,
            envelopeRecord(ChapterRunState.ENVELOPE_PLAN, envelopeCounters(emptyMap())),
        )

        val manifest = store.artifactManifest ?: return BatchPass1Outcome(
            needsTranslation = emptyList(),
            status = BatchPass1Status.PERSISTENCE_REJECTED,
            reason = "T924 envelope plan deferred: manifest unavailable",
        )
        val frozenProfilePointer = manifest.profile ?: return BatchPass1Outcome(
            needsTranslation = emptyList(),
            status = BatchPass1Status.PAUSED,
            completedPageKeys = allPageKeys,
            reason = "T924 envelope plan deferred: frozen profile pointer absent",
        )

        // Stage-6 slice B (design §7): load the frozen profile DTO for prompt
        // enrichment. The SAME ST-05/ST-30 reuse discipline applies — a
        // sidecar that does not read back fully valid and identity-matched is
        // treated as ABSENT and the executor keeps the LEGACY prompt shape
        // (degraded-but-correct, never partially trusted).
        val frozenProfile = when (
            val profileRead = ProfileFreezePublication.readReusableFrozenProfile(
                store = store,
                manifest = manifest,
                expectedInputFingerprint = profileInputFingerprintOf(corpusFingerprint),
            )
        ) {
            is ProfileFreezePublication.FrozenProfileRead.Reusable -> {
                logcat(LogPriority.INFO) {
                    "TachiyomiAT t924 envelope prompt shape=enriched " +
                        "(frozen profile v${profileRead.profile.version} loaded)"
                }
                profileRead.profile
            }
            ProfileFreezePublication.FrozenProfileRead.NotReusable -> {
                logcat(LogPriority.WARN) {
                    "TachiyomiAT t924 envelope prompt shape=legacy " +
                        "(frozen profile sidecar unreadable — degraded-but-correct)"
                }
                null
            }
        }

        // ST-12 entry needs a typed AI transport; the plan still publishes so
        // a later wired run resumes directly into TRANSLATE. Wave A: the
        // constructor widened to TextTranslator for the standard lane, so the
        // envelope path re-narrows here — a non-contextual translator on the
        // AI lane takes the SAME typed CONFIGURATION pause as a missing one
        // (the dispatch gate makes this unreachable in production).
        val translator = textTranslator as? ContextualTextTranslator

        // ---- ENVELOPE_PLAN: build + plan + publish (or reuse). ----
        when (val build = buildEnvelopeDispatchWork(artifact, orderedPages, corpusFingerprint)) {
            is EnvelopeWorkBuild.CorpusDrift -> return BatchPass1Outcome(
                needsTranslation = emptyList(),
                status = BatchPass1Status.PAUSED,
                completedPageKeys = allPageKeys,
                reason = build.reason,
            )
            is EnvelopeWorkBuild.NothingPending -> {
                // Skip rule (ST-11): no translatable work — textless chapters
                // never reach TRANSLATE. Typed PAUSED no-work terminal.
                publishRecord(
                    artifact,
                    envelopeRecord(
                        ChapterRunState.ENVELOPE_PLAN,
                        envelopeCounters(
                            mapOf(
                                COUNTER_ENVELOPES_TOTAL to 0,
                                COUNTER_ENVELOPES_DONE to 0,
                                COUNTER_SKIPPED_NO_WORK to 1,
                                COUNTER_STOP to 1,
                            ),
                        ),
                    ),
                )
                return BatchPass1Outcome(
                    needsTranslation = emptyList(),
                    status = BatchPass1Status.PAUSED,
                    completedPageKeys = allPageKeys,
                    reason = ENVELOPE_NO_WORK_REASON,
                )
            }
            is EnvelopeWorkBuild.PlannerRejected -> {
                // ST-11 terminal: a page that cannot fit any legal envelope
                // is rejected whole (page atomicity); it takes a durable
                // structural failure and the phase pauses at it.
                build.namedPageKeys.forEach { pageKey ->
                    persistEnvelopeStructuralFailure(pageKey, build.reasons.joinToString("; "))
                }
                publishRecord(
                    artifact,
                    envelopeRecord(
                        ChapterRunState.ENVELOPE_PLAN,
                        envelopeCounters(
                            mapOf(
                                COUNTER_ENVELOPE_PLAN_REJECTED to 1,
                                COUNTER_STOP to 1,
                            ),
                        ),
                    ),
                )
                return BatchPass1Outcome(
                    needsTranslation = emptyList(),
                    status = BatchPass1Status.PAUSED,
                    anchorPageKey = build.namedPageKeys.firstOrNull(),
                    reason = "T924 envelope plan rejected: ${build.reasons.joinToString("; ")}",
                )
            }
            is EnvelopeWorkBuild.Ready -> {
                val fresh = build.plan
                // ST-11 resume rule: identical inputs re-derive an identical
                // plan fingerprint — reuse the published plan, no write.
                val reuse = manifest.envelopePlan != null &&
                    EnvelopePlanPublication.readValidatedPlan(store, manifest).let { read ->
                        read is EnvelopePlanPublication.EnvelopePlanRead.Usable &&
                            read.plan.planFingerprint == fresh.planFingerprint
                    }
                if (!reuse) {
                    val manifestForPublish = store.artifactManifest ?: return BatchPass1Outcome(
                        needsTranslation = emptyList(),
                        status = BatchPass1Status.PERSISTENCE_REJECTED,
                        reason = "T924 envelope plan deferred: manifest unavailable",
                    )
                    when (
                        val publication = EnvelopePlanPublication.publish(
                            store = store,
                            manifest = manifestForPublish,
                            plan = fresh,
                            nowEpochMs = nowEpochMs(),
                        )
                    ) {
                        is ChapterArtifactEngine.TransactionOutcome.Committed ->
                            store.artifactManifest = publication.manifest
                        is ChapterArtifactEngine.TransactionOutcome.Rejected ->
                            // Prior manifest stays authoritative (SC-20/22).
                            return BatchPass1Outcome(
                                needsTranslation = emptyList(),
                                status = BatchPass1Status.PERSISTENCE_REJECTED,
                                reason = "T924 envelope plan publication rejected: ${publication.reason}",
                            )
                    }
                }
                // T934 LI-4: the plan is durable again (published, or an
                // identical fingerprint was reused) — end the rebuild window.
                listener.envelopePlanCommitted()

                // ---- ST-12 TRANSLATE. ----
                if (translator == null) {
                    publishRecord(
                        artifact,
                        envelopeRecord(
                            ChapterRunState.TRANSLATE,
                            envelopeCounters(
                                mapOf(
                                    COUNTER_ENVELOPES_TOTAL to fresh.envelopes.size,
                                    COUNTER_ENVELOPES_DONE to 0,
                                    COUNTER_SKIPPED_NO_TRANSPORT to 1,
                                    COUNTER_STOP to 1,
                                ),
                            ),
                        ),
                    )
                    return BatchPass1Outcome(
                        needsTranslation = emptyList(),
                        status = BatchPass1Status.PAUSED,
                        completedPageKeys = allPageKeys,
                        reason = TRANSLATE_NO_TRANSPORT_REASON,
                    )
                }

                publishRecord(
                    artifact,
                    envelopeRecord(
                        ChapterRunState.TRANSLATE,
                        envelopeCounters(
                            mapOf(
                                COUNTER_ENVELOPES_TOTAL to fresh.envelopes.size,
                                COUNTER_ENVELOPES_DONE to 0,
                                COUNTER_ENVELOPES_PENDING to fresh.envelopes.size,
                            ),
                        ),
                    ),
                )

                val work = build.work
                // T924 Stage 7 (D1): when the overlap scheduler is present,
                // every provider envelope dispatch opens a remote window that
                // drives serial inpaint of committed pages (ST-13). Admission
                // semantics are unchanged — the wrapper delegates to the SAME
                // process-wide sub-limit gate.
                val dispatchGate = overlapScheduler?.let { scheduler ->
                    OverlapScheduler.WindowSignallingGate(translationSublimitGate, scheduler)
                } ?: translationSublimitGate
                // D2: publish the persisted layout right after each inpaint
                // commits (per page, never blocking the envelope loop — the
                // hook runs inside the overlap scheduler's coroutine).
                overlapScheduler?.onInpaintCommitted = { pageKey ->
                    renderJoin?.publishPersistedLayoutForCompletedPage(pageKey)
                    Unit
                }
                val executor = ProfileEnvelopeExecutor(
                    store = store,
                    textTranslator = translator,
                    profileContentFingerprint = frozenProfilePointer.contentFingerprint,
                    frozenProfile = frozenProfile,
                    replan = { reason ->
                        rebuildDispatchWork(artifact, orderedPages, corpusFingerprint, reason)
                    },
                    sublimitGate = dispatchGate,
                    providerProfile = providerChunkProfile(),
                    nowEpochMs = nowEpochMs,
                    // T934 track V: freed write slots wake the overlap lane at
                    // the commit settle — deferred inpaint candidates no longer
                    // wait a whole envelope cycle for the next window's open.
                    onCommitSettled = { overlapScheduler?.notifyCandidatesChanged() },
                )
                val overlapLoop: suspend (suspend () -> ProfileEnvelopeExecutor.PhaseOutcome) -> ProfileEnvelopeExecutor.PhaseOutcome =
                    { runPhase ->
                        if (overlapScheduler == null) {
                            runPhase()
                        } else {
                            kotlinx.coroutines.coroutineScope {
                                val loop = launch { overlapScheduler.runOverlapLoop() }
                                val outcome = runPhase()
                                // No further windows: stop the loop between pages
                                // (a running inpaint finishes through the lane).
                                overlapScheduler.stopOverlap()
                                loop.join()
                                outcome
                            }
                        }
                    }
                return overlapLoop { executor.run(work) }.let { outcome ->
                    when (outcome) {
                        is ProfileEnvelopeExecutor.PhaseOutcome.Drained -> {
                            publishRecord(
                                artifact,
                                envelopeRecord(
                                    ChapterRunState.TRANSLATE,
                                    envelopeCounters(
                                        outcome.counters.toMap() + mapOf(COUNTER_STOP to 1),
                                    ),
                                ),
                            )
                            // T924 Stage 7 (D4): TRANSLATE drained — the
                            // ST-14 FINALIZE phase completes the run and
                            // publishes its single COMPLETE.
                            runFinalizeAndComplete(
                                artifact = artifact,
                                runId = runId,
                                orderedPages = orderedPages,
                                corpusFingerprint = corpusFingerprint,
                                baseCounters = envelopeCounters(
                                    outcome.counters.toMap() + mapOf(COUNTER_STOP to 1),
                                ),
                            )
                        }
                        is ProfileEnvelopeExecutor.PhaseOutcome.Paused -> {
                            overlapScheduler?.stopOverlap()
                            publishRecord(
                                artifact,
                                envelopeRecord(
                                    ChapterRunState.TRANSLATE,
                                    envelopeCounters(
                                        outcome.counters.toMap() +
                                            mapOf(
                                                COUNTER_STOP to 1,
                                                COUNTER_ENVELOPES_PENDING to outcome.counters.envelopesPending,
                                            ),
                                    ),
                                ),
                            )
                            logcat(LogPriority.WARN) {
                                "TachiyomiAT t924 translate paused: ${outcome.reason}"
                            }
                            BatchPass1Outcome(
                                needsTranslation = emptyList(),
                                status = BatchPass1Status.PAUSED,
                                anchorPageKey = outcome.anchorPageKey,
                                failure = outcome.failure,
                                nextEligibleRetryAtEpochMs = outcome.nextEligibleRetryAtEpochMs,
                                reason = outcome.reason,
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * T924 Stage 7 (D4) — ST-14 FINALIZE, entered exactly when TRANSLATE
     * drained (every planned envelope dispatched, committed, or skipped):
     *
     *  1. `FINALIZE` phase record (ST-14 durable write: activePhase pointer).
     *  2. Serial inpaint drain through the overlap scheduler (the legacy
     *     post-translate serial schedule — the gate-6.5 keep-serial arm; when
     *     the scheduler is absent this is a no-op and the run keeps the
     *     pre-Stage-7 completion semantics).
     *  2b. T934 display-tail drain: pages whose translate+inpaint work is
     *     done but whose committed display never landed are finished here
     *     (bounded), or given a SPECIFIC typed terminal — the run still
     *     completes, as a warning. See [drainFinalizeAndComplete] step 2b.
     *  3. Per-page persisted-layout publication sweep (D2): any page with
     *     committed translation + committed inpaint that the overlap hook did
     *     not publish gets its TX-23 plan publication here; failures keep the
     *     async planner fallback (reader display never breaks).
     *  4. Stranded-page reconciliation — the VERIFIED legacy idiom
     *     (`BatchChapterTranslator.kt:747-760`): every page that is not
     *     durably terminal gets a durable failure so callers that observe the
     *     terminal state can also inspect retryable failures.
     *  5. Final `store.flush()` under [kotlinx.coroutines.NonCancellable]
     *     (:781-793 idiom) + artifact retention reconciliation (:794).
     *  6. Final run record: `state=COMPLETE` — the run's FIRST and ONLY
     *     COMPLETE publication (wave-2 F1: the OFF+COMPLETE resume decision
     *     tree keys on exactly this state).
     *
     * Completion semantics are the LEGACY translation-committed ones. The
     * DISPLAY_READY redefinition is gate-7.8-gated and MUST stay OFF until
     * gate 7.5 (restart/LRU rehydrate without planner invocation) has passed
     * on device for Pager AND Webtoon — see
     * [Companion.GATE_7_8_DISPLAY_READY_COMPLETION_ENABLED].
     */
    private suspend fun runFinalizeAndComplete(
        artifact: ChapterArtifactEngine,
        runId: String,
        orderedPages: List<PageKey>,
        corpusFingerprint: String,
        baseCounters: Map<String, Int>,
    ): BatchPass1Outcome {
        val frozenFingerprint = runConfigFingerprint(frozenConfig)
        val sourceDigest = orderedSourceDigest(effectiveSourcePairs)

        // ST-14 entry: the FINALIZE phase pointer (resume re-runs finalize —
        // every step below is an idempotent re-run).
        publishRecord(
            artifact,
            record(
                runId,
                ChapterRunState.FINALIZE,
                frozenFingerprint,
                sourceDigest,
                baseCounters + mapOf(COUNTER_FINALIZE to 1) +
                    // Gate-6.5 evidence rides the FINALIZE record: the schema
                    // bounds phaseCounters at 32 keys, and baseCounters + the
                    // finalize keys + the full overlap snapshot would push the
                    // COMPLETE record past it (36 > 32 — the COMPLETE
                    // publication would be silently rejected, wave-7b fix).
                    (overlapScheduler?.let { scheduler ->
                        scheduler.counters.snapshot().mapValues { it.value.toInt() }
                    } ?: emptyMap()),
                ocrCorpusFingerprint = corpusFingerprint,
                profilePointer = store.artifactManifest?.profile,
            ),
        )
        return drainFinalizeAndComplete(
            artifact = artifact,
            runId = runId,
            orderedPages = orderedPages,
            corpusFingerprint = corpusFingerprint,
            baseCounters = baseCounters,
        )
    }

    /**
     * Steps 2-6 of the ST-14 FINALIZE phase — the idempotent drain shared by
     * the fresh entry ([runFinalizeAndComplete]) and the ST-14 FINALIZE
     * resume ([resumeFinalizeOrComplete]):
     *
     *  2. Serial inpaint drain through the overlap scheduler — pages whose
     *     inpaint already committed are skipped by the scheduler's candidate
     *     rule (never re-inpainted); with no scheduler this is a no-op.
     *  2b. T934 display-tail drain: pages whose translate+inpaint work is
     *     done but whose committed display (render-terminal stamp →
     *     promotion) never landed are drained to completion here, bounded;
     *     a page the drain cannot finish takes a SPECIFIC typed terminal and
     *     the run still completes (as a warning).
     *  3. Persisted-layout publication sweep (idempotent per page).
     *  4. Stranded-page reconciliation (safe re-run: terminal pages skip).
     *  5. NonCancellable flush + retention reconciliation.
     *  6. The run's FIRST and ONLY `COMPLETE` publication.
     */
    private suspend fun drainFinalizeAndComplete(
        artifact: ChapterArtifactEngine,
        runId: String,
        orderedPages: List<PageKey>,
        corpusFingerprint: String,
        baseCounters: Map<String, Int>,
    ): BatchPass1Outcome {
        val frozenFingerprint = runConfigFingerprint(frozenConfig)
        val sourceDigest = orderedSourceDigest(effectiveSourcePairs)
        val allPageKeys = orderedPages.mapTo(mutableSetOf()) { it.first }

        // 2. Serial post-translate inpaint drain (overlap-fallback arm).
        overlapScheduler?.drainSerial()

        // 2b. T934 display-tail drain: COMPLETE means "every page readable",
        //     not "every ingredient done". The inpaint lane's render-terminal
        //     stamp only fires when the page's translation was ALREADY
        //     terminal at inpaint time, so order-inverted pages (inpaint
        //     committed before the envelope translation — the decoupled
        //     candidacy norm) stay render-PENDING with ALL work done and are
        //     invisible to the stranded sweep below (translation READY is
        //     terminal there). Drain that tail to completion here — bounded
        //     passes, each a fresh snapshot so a stale-write rejection
        //     retries — and give a page the drain genuinely cannot finish a
        //     SPECIFIC typed terminal instead of publishing COMPLETE over a
        //     silently frozen ORIGINAL_ONLY page.
        val displayTail = drainDisplayTailBeforeComplete(orderedPages.map { it.first })

        // 3. Persisted-layout publication sweep for any page the per-page
        //    hook missed (idempotent — pages with a published plan skip).
        var layoutsPublished = 0
        if (renderJoin != null) {
            for ((pageKey, _) in orderedPages) {
                if (renderJoin.publishPersistedLayoutForCompletedPage(pageKey)) {
                    layoutsPublished++
                }
            }
        }

        // 4. Stranded-page reconciliation: pages that are not durably
        //    terminal get a durable failure + a reported reason. The T924
        //    terminal predicate is NOT the legacy reconciler's one: the legacy
        //    schedule renders + promotes display in-pass (hasRenderedResult),
        //    while the flagged pipeline's committed-translation state is
        //    terminal WITHOUT an in-pass render — reusing the legacy
        //    definition here would mark every healthy page stranded.
        val stateNow = store.state.value
        var strandedReconciled = 0
        for (pageKey in orderedPages.map { it.first }) {
            val page = stateNow[pageKey]
            if (t924PageTerminalAtFinalize(page, store.currentGeneration)) continue
            // T934 stranded-page fix: the reason must name the page's actual
            // work state (all-translated, textless, user-owned, or genuinely
            // unfinished) — a bare status is what made these pages show as a
            // generic "Unknown error" class in the progress sheet.
            val reason = strandedPageReason(page)
            logcat(LogPriority.WARN) {
                "TachiyomiAT t924 stranded page at FINALIZE pageHash=${ShortHash.hash(pageKey)} reason=$reason"
            }
            persistEnvelopeStructuralFailure(
                pageKey,
                reason,
                carrier = "stranded page reconciled at FINALIZE",
            )
            strandedReconciled++
        }

        // 5. Durable teardown: NonCancellable flush + retention reconciliation
        //    (the BatchChapterTranslator :781-794 idiom). The sweep itself runs
        //    fire-and-forget: its crawl is minutes of SAF round-trips and must
        //    neither delay run closure nor hold the store mutex (2026-09-15
        //    jdb-proven 20+ minute stall).
        withContext(NonCancellable) {
            store.flush()
        }
        store.reconcileArtifactRetentionAsync()

        // 6. Run closure: the single COMPLETE publication of the run. The
        //    overlap counters live on the FINALIZE record (see above — the
        //    32-key phaseCounters bound). The outcome is NOT advisory here:
        //    a rejected (or unpublishable) closure leaves the run durably at
        //    FINALIZE, so the coordinator pauses instead of reporting a
        //    completion the record disagrees with (RUN_CLOSURE_REJECTED_REASON).
        //
        //    T934 round 3: the publication CASes against the façade manifest
        //    snapshot while the drain steps above move durable state through
        //    their own store transactions. One fresh-baseline retry — re-read
        //    the durable manifest into the façade, then re-publish — keeps
        //    the closure off the typed pause when the snapshot is merely
        //    stale (the same one-shot rebase every artifact-store seam gets
        //    from retryOnStaleManifest); a genuine rejection still pauses.
        fun completeRecord() = record(
            runId,
            ChapterRunState.COMPLETE,
            frozenFingerprint,
            sourceDigest,
            baseCounters +
                mapOf(
                    COUNTER_FINALIZE to 1,
                    COUNTER_RUN_COMPLETE to 1,
                    COUNTER_LAYOUTS_PUBLISHED to layoutsPublished,
                    COUNTER_STRANDED_RECONCILED to strandedReconciled,
                    COUNTER_DISPLAY_TAIL_DRAINED to displayTail.drained,
                    COUNTER_DISPLAY_TAIL_FAILED to displayTail.failed.size,
                ),
            ocrCorpusFingerprint = corpusFingerprint,
            profilePointer = store.artifactManifest?.profile,
        )
        var closure = publishRecord(artifact, completeRecord())
        if (closure !is ChapterArtifactEngine.TransactionOutcome.Committed) {
            store.withArtifactEngineLocked { artifact ->
                artifact.readManifest()
            }?.let { store.artifactManifest = it }
            closure = publishRecord(artifact, completeRecord())
        }
        when (closure) {
            is ChapterArtifactEngine.TransactionOutcome.Committed -> {
                logcat(LogPriority.INFO) {
                    "TachiyomiAT t924 run COMPLETE pages=${allPageKeys.size} stranded=$strandedReconciled " +
                        "layouts=$layoutsPublished displayTailDrained=${displayTail.drained} " +
                        "displayTailFailed=${displayTail.failed.size} " +
                        "overlap=${overlapScheduler?.counters?.snapshot() ?: emptyMap()}"
                }
                return BatchPass1Outcome(
                    needsTranslation = emptyList(),
                    status = BatchPass1Status.COMPLETED,
                    completedPageKeys = allPageKeys,
                    reason = TRANSLATE_COMPLETE_REASON,
                )
            }
            else -> {
                logcat(LogPriority.WARN) {
                    "TachiyomiAT t924 run COMPLETE publication rejected " +
                        "(outcome=${closure?.javaClass?.simpleName ?: "no-manifest"}); " +
                        "pausing at FINALIZE"
                }
                return BatchPass1Outcome(
                    needsTranslation = emptyList(),
                    status = BatchPass1Status.PAUSED,
                    completedPageKeys = allPageKeys,
                    reason = RUN_CLOSURE_REJECTED_REASON,
                )
            }
        }
    }

    /**
     * T924 Phase 4 Wave A — the STANDARD-engine translate tail, entered
     * exactly when the OCR preflight published a COMPLETE corpus (the
     * whole-corpus gap gate above is unchanged). The DIRECTOR design: the
     * standard engine continues batch translation just like the AI lane,
     * without the glossary and without anything AI-specific:
     *
     *  1. `TRANSLATE` phase record carrying the run's OCR corpus fingerprint
     *     and NO analysis/profile/envelope pointers (the standard lane never
     *     produces them; the ST-14 resume gate reads the fingerprint from
     *     the FINALIZE record this tail leads to).
     *  2. IN-ORDER per-page translation through the injected
     *     [standardTranslateOutcome] seam — the LEGACY per-page machinery
     *     (BatchLaneWorkers idiom), never the envelope provenance ladder.
     *     Pages already translation-terminal (READY/PARTIAL, textless, or
     *     rendered — resume-reuse parity) are never re-paid. Every seam call
     *     is bracketed by the overlap window (open before, close after) for
     *     ALL standard engines: native inpaint must never overlap
     *     translation.
     *  3. Typed seam outcomes map to the SAME chapter-level semantics the
     *     legacy schedule uses (SBC): Completed → next page; Paused → typed
     *     PAUSE; Failed/Unexpected → FAILED; PersistenceRejected →
     *     PERSISTENCE_REJECTED.
     *  4. When the translate loop drains → [runFinalizeAndComplete] verbatim
     *     — the engine-agnostic Stage-7 finalize (serial inpaint drain,
     *     layout sweep, stranded reconciliation, single COMPLETE
     *     publication). Completion is translation-terminal WITHOUT an
     *     in-pass render, exactly like the AI lane (display rides the live
     *     overlay + candidate snapshots; renderStatus stays PENDING).
     */
    private suspend fun runStandardTranslateAndFinalize(
        artifact: ChapterArtifactEngine,
        runId: String,
        orderedPages: List<PageKey>,
        corpusFingerprint: String?,
        baseCounters: Map<String, Int>,
        hasGaps: Boolean = false,
    ): BatchPass1Outcome {
        val frozenFingerprint = runConfigFingerprint(frozenConfig)
        val sourceDigest = orderedSourceDigest(effectiveSourcePairs)
        val allPageKeys = orderedPages.mapTo(mutableSetOf()) { it.first }

        fun translateCounters(extra: Map<String, Int>): Map<String, Int> = baseCounters + extra

        fun translateRecord(state: ChapterRunState, counters: Map<String, Int>): ChapterRunRecord =
            record(
                runId,
                state,
                frozenFingerprint,
                sourceDigest,
                counters,
                ocrCorpusFingerprint = corpusFingerprint,
            )

        // Phase entry: TRANSLATE (no profile pointer — the standard lane's
        // records carry only the schema-required policy fingerprints).
        publishRecord(artifact, translateRecord(ChapterRunState.TRANSLATE, translateCounters(emptyMap())))

        val seam = standardTranslateOutcome
        if (seam == null) {
            // Typed CONFIGURATION-class gate, mirroring the analysis runner
            // seam: never run provider-bound work without a typed transport.
            publishRecord(
                artifact,
                translateRecord(
                    ChapterRunState.TRANSLATE,
                    translateCounters(mapOf(COUNTER_SKIPPED_NO_TRANSPORT to 1, COUNTER_STOP to 1)),
                ),
            )
            logcat(LogPriority.WARN) {
                "TachiyomiAT t924 standard translate paused: no typed standard seam wired (CONFIGURATION gate)"
            }
            return BatchPass1Outcome(
                needsTranslation = emptyList(),
                status = BatchPass1Status.PAUSED,
                completedPageKeys = allPageKeys,
                reason = STANDARD_NO_SEAM_REASON,
            )
        }

        // D2: publish the persisted layout right after each inpaint commits
        // (the same per-page hook the AI envelope lane installs).
        overlapScheduler?.onInpaintCommitted = { pageKey ->
            renderJoin?.publishPersistedLayoutForCompletedPage(pageKey)
            Unit
        }
        // T924 Stage 7 (D1) idiom: the overlap loop runs BESIDE the serial
        // translate loop and is stopped between pages once the tail ends.
        val overlapLoop: suspend (suspend () -> BatchPass1Outcome) -> BatchPass1Outcome =
            { runTail ->
                if (overlapScheduler == null) {
                    runTail()
                } else {
                    coroutineScope {
                        val loop = launch { overlapScheduler.runOverlapLoop() }
                        val outcome = runTail()
                        // No further windows: stop the loop between pages
                        // (a running inpaint finishes through the lane).
                        overlapScheduler.stopOverlap()
                        loop.join()
                        outcome
                    }
                }
            }

        return overlapLoop {
            var translatedPages = 0
            for (page in orderedPages) {
                val (pageKey, pageIndex) = page
                currentCoroutineContext().ensureActive()
                // Reader-priority courtesy between pages (preflight idiom).
                yield()

                // Resume-reuse parity: an already-terminal page never re-pays
                // the provider (READY/PARTIAL committed, textless, or the
                // cross-schedule rendered safety).
                val live = store.state.value[pageKey]
                if (live != null && standardPageTerminalAtTranslate(live)) continue

                // D1: bracket the per-page translate call with the SAME
                // remote-window mechanism the AI lane rides — the overlap
                // scheduler runs serial inpaint ONLY inside the window, so
                // native work never overlaps translation (ALL engines).
                overlapScheduler?.onRemoteWindowOpened()
                val outcome = try {
                    seam(
                        OcrReadyPageRef(
                            pageKey = pageKey,
                            pageIndex = pageIndex,
                            generation = store.currentGeneration,
                            blockFingerprints = emptyList(),
                        ),
                    )
                } finally {
                    overlapScheduler?.onRemoteWindowClosed()
                }
                when (outcome) {
                    is ChunkCompletionOutcome.Completed -> translatedPages += outcome.completedPageKeys.size
                    is ChunkCompletionOutcome.Paused -> {
                        publishRecord(
                            artifact,
                            translateRecord(
                                ChapterRunState.TRANSLATE,
                                translateCounters(
                                    mapOf(
                                        COUNTER_PAGES_TRANSLATED to translatedPages,
                                        COUNTER_STOP to 1,
                                    ),
                                ),
                            ),
                        )
                        logcat(LogPriority.WARN) {
                            "TachiyomiAT t924 standard translate paused: ${outcome.reason}"
                        }
                        return@overlapLoop BatchPass1Outcome(
                            needsTranslation = emptyList(),
                            status = BatchPass1Status.PAUSED,
                            anchorPageKey = outcome.anchorPageKey,
                            completedPageKeys = allPageKeys,
                            retryablePageKeys = outcome.retryablePageKeys,
                            failure = outcome.failure,
                            nextEligibleRetryAtEpochMs = outcome.nextEligibleRetryAtEpochMs,
                            reason = outcome.reason,
                        )
                    }
                    is ChunkCompletionOutcome.Failed -> {
                        publishRecord(
                            artifact,
                            translateRecord(
                                ChapterRunState.TRANSLATE,
                                translateCounters(
                                    mapOf(
                                        COUNTER_PAGES_TRANSLATED to translatedPages,
                                        COUNTER_STOP to 1,
                                    ),
                                ),
                            ),
                        )
                        logcat(LogPriority.WARN) {
                            "TachiyomiAT t924 standard translate failed: ${outcome.reason}"
                        }
                        return@overlapLoop BatchPass1Outcome(
                            needsTranslation = emptyList(),
                            status = BatchPass1Status.FAILED,
                            anchorPageKey = outcome.anchorPageKey,
                            completedPageKeys = allPageKeys,
                            terminalPageKeys = outcome.terminalPageKeys,
                            failure = outcome.failure,
                            reason = outcome.reason,
                        )
                    }
                    is ChunkCompletionOutcome.Unexpected -> {
                        publishRecord(
                            artifact,
                            translateRecord(
                                ChapterRunState.TRANSLATE,
                                translateCounters(
                                    mapOf(
                                        COUNTER_PAGES_TRANSLATED to translatedPages,
                                        COUNTER_STOP to 1,
                                    ),
                                ),
                            ),
                        )
                        return@overlapLoop BatchPass1Outcome(
                            needsTranslation = emptyList(),
                            status = BatchPass1Status.FAILED,
                            anchorPageKey = outcome.anchorPageKey,
                            completedPageKeys = allPageKeys,
                            terminalPageKeys = outcome.terminalPageKeys,
                            reason = outcome.reason,
                            unexpectedStage = outcome.stage,
                        )
                    }
                    is ChunkCompletionOutcome.PersistenceRejected -> {
                        publishRecord(
                            artifact,
                            translateRecord(
                                ChapterRunState.TRANSLATE,
                                translateCounters(
                                    mapOf(
                                        COUNTER_PAGES_TRANSLATED to translatedPages,
                                        COUNTER_STOP to 1,
                                    ),
                                ),
                            ),
                        )
                        return@overlapLoop BatchPass1Outcome(
                            needsTranslation = emptyList(),
                            status = BatchPass1Status.PERSISTENCE_REJECTED,
                            anchorPageKey = outcome.anchorPageKey,
                            reason = outcome.reason,
                            persistenceRejectedStage = outcome.stage,
                        )
                    }
                }
            }

            // Drained: every ordered page reached its terminal. The drained
            // TRANSLATE record mirrors the AI lane's (counters + stop), then
            // the SHARED engine-agnostic Stage-7 finalize publishes the run's
            // single COMPLETE.
            publishRecord(
                artifact,
                translateRecord(
                    ChapterRunState.TRANSLATE,
                    translateCounters(
                        mapOf(
                            COUNTER_PAGES_TRANSLATED to translatedPages,
                            COUNTER_STOP to 1,
                        ),
                    ),
                ),
            )
            // X6 invariant: "PARTIAL-corpus COMPLETE" is unrepresentable.
            // If hasGaps == true (corpusGaps > 0) or corpusFingerprint == null,
            // we stop at TRANSLATE and return PAUSED, never COMPLETE.
            if (hasGaps || corpusFingerprint == null) {
                logcat(LogPriority.INFO) {
                    "TachiyomiAT M4 standard translate finished available checkpoints but chapter has gaps; returning PAUSED"
                }
                return@overlapLoop BatchPass1Outcome(
                    needsTranslation = emptyList(),
                    status = BatchPass1Status.PAUSED,
                    completedPageKeys = allPageKeys,
                    reason = STOP_REASON,
                )
            }
            runFinalizeAndComplete(
                artifact = artifact,
                runId = runId,
                orderedPages = orderedPages,
                corpusFingerprint = corpusFingerprint,
                baseCounters = translateCounters(mapOf(COUNTER_PAGES_TRANSLATED to translatedPages)),
            )
        }
    }

    /**
     * ST-14 resume gates (contracts-state-transactions :188-197) for a
     * durable record already past TRANSLATE, consulted at dispatch entry
     * BEFORE any RUN_SNAPSHOT republication:
     *
     *  - `FINALIZE` (ST-14 Resume clause): a process death after the FINALIZE
     *    record but before the COMPLETE publication resumes by RE-RUNNING the
     *    idempotent finalize drain ([drainFinalizeAndComplete]) — never by
     *    stepping the durable state BACKWARD to RUN_SNAPSHOT and re-entering
     *    OCR/analysis/translate ("run FINALIZE to completion; do not
     *    re-enter TRANSLATE/NATIVE/RENDER from FINALIZE"). The drain skips
     *    already-committed pages and COMPLETE stays the run's FIRST/ONLY
     *    closure record. Run identity (corpus fingerprint, progress counters)
     *    rides the durable FINALIZE record — the same run, not a new one.
     *  - `COMPLETE`: the run already closed — an idempotent finished outcome
     *    with zero work and NO new record publication. Since the zero-legacy
     *    wave (D1) removed the FF-01 flag and its shell-level OFF+COMPLETE
     *    decision tree, this ST-14 path is the ONLY COMPLETE-resume route
     *    for both lanes.
     *
     * T924 LI-2 (COMPLETE work-product evidence gate): the `COMPLETE` fast path
     * additionally demands per-page evidence that the run's page results are
     * still durably addressable — a committed bundle, an open candidate
     * snapshot, or a committed/textless display state in the manifest record
     * — or a durable textless terminal in the live store. This supersedes a
     * recorded COMPLETE over pages whose translated state is gone (e.g. after
     * a user reset demoted committed displays and cleared the candidate
     * pointers): any page lacking evidence returns null so the normal
     * run-start path publishes a fresh RUN_SNAPSHOT and re-derives the
     * missing page state.
     *
     * Returns null — the normal run-start path proceeds unchanged — when the
     * prior record is in any other state, or its frozen configuration
     * fingerprint / ordered source digest no longer match this dispatch
     * (ST-15: a mismatch starts a NEW run exactly as before), or the recorded
     * COMPLETE lacks per-page display evidence (LI-2 supersession above).
     */
    private suspend fun resumeFinalizeOrComplete(
        artifact: ChapterArtifactEngine,
        priorRecord: ChapterRunRecord?,
        frozenFingerprint: String,
        sourceDigest: String,
        orderedPages: List<PageKey>,
    ): BatchPass1Outcome? {
        val resumable = priorRecord
            ?.takeIf {
                (it.state == ChapterRunState.FINALIZE || it.state == ChapterRunState.COMPLETE) &&
                    it.frozenRunConfigFingerprint == frozenFingerprint &&
                    it.orderedSourceDigest == sourceDigest
            }
            ?: return null
        if (resumable.state == ChapterRunState.COMPLETE) {
            // T924 LI-2 (the mirror of the flag-OFF F-4 gate in
            // deleted shell-level F-4 gate): a recorded
            // COMPLETE is NOT enough to retire the chapter — the zero-work
            // finished outcome is authorized only when every ordered page's
            // translated result is still durably addressable. The flagged lane
            // keeps the translated page snapshot under the page record's
            // committed bundle or (before reader adoption) its candidate
            // pointer; a user reset overwrites the candidate snapshot with the
            // cleared PENDING page and demotes committed displays without
            // (previously) retiring the run record, so pointer PRESENCE alone
            // is not evidence — the snapshot CONTENT must still be a
            // translation-terminal page. Any page whose record and snapshot
            // carry no readable translated/textless result must behave the
            // same way: instead of short-circuiting, the run STARTS FRESH
            // (ST-15-style supersession — the normal run-start path publishes
            // a new RUN_SNAPSHOT and re-derives the missing page state).
            // Evidence is judged against the DURABLE manifest (same read the
            // pointer above came from), never the facade's mutable cache.
            val durableManifest = store.withArtifactEngineLocked { artifact ->
                artifact.readManifest()
            }
            val allPagesWorkProductEvidenced = orderedPages.all { (pageKey, _) ->
                pageWorkProductResolvable(artifact, pageKey, durableManifest)
            }
            if (!allPagesWorkProductEvidenced) {
                logcat(LogPriority.INFO) {
                    "TachiyomiAT t924 resume: recorded COMPLETE lacks display evidence; " +
                        "superseding with a fresh run"
                }
                return null
            }
            logcat(LogPriority.INFO) {
                "TachiyomiAT t924 resume: recorded run already COMPLETE; finished without re-running work"
            }
            return BatchPass1Outcome(
                needsTranslation = emptyList(),
                status = BatchPass1Status.COMPLETED,
                completedPageKeys = orderedPages.mapTo(mutableSetOf()) { it.first },
                reason = RESUME_COMPLETE_REASON,
            )
        }
        // A FINALIZE record always carries the run's corpus fingerprint (both
        // drain entry paths publish it); a record without one is not this
        // coordinator's shape — fall through to the normal start.
        val corpusFingerprint = resumable.ocrCorpusFingerprint ?: return null
        logcat(LogPriority.INFO) {
            "TachiyomiAT t924 resume: FINALIZE record durable; re-running the idempotent finalize drain (ST-14)"
        }
        return drainFinalizeAndComplete(
            artifact = artifact,
            runId = resumable.runId,
            orderedPages = orderedPages,
            corpusFingerprint = corpusFingerprint,
            baseCounters = resumable.phaseCounters,
        )
    }

    /**
     * T924 LI-2 helper for the COMPLETE resume gate: true when [pageKey]'s
     * translated result under the recorded COMPLETE run is still durably
     * addressable — a committed bundle, a committed/textless display state, or
     * a candidate snapshot whose CONTENT is still a translation-terminal page
     * (a user reset persists the cleared PENDING page OVER that snapshot
     * without clearing the pointer, so pointer presence alone is not
     * evidence). Falls back to the live store's durable textless terminal for
     * pages with no readable record sidecar.
     */
    private suspend fun pageWorkProductResolvable(
        artifact: ChapterArtifactEngine,
        pageKey: String,
        durableManifest: ChapterArtifactManifest?,
    ): Boolean {
        // The durable no-text terminal (the legacy worker's textless commit
        // AND the OCR-side finalizePostOcrStage both set it): a committed
        // snapshot carrying no translatable text is exactly as durable as a
        // translated one (the standard lane's textless evidence shape).
        fun isNoTextTerminal(page: PageTranslation): Boolean =
            page.isTextlessTerminal || page.translationStatus == StageStatus.SKIPPED
        val liveTextless = store.state.value[pageKey]?.let(::isNoTextTerminal) == true
        val record = durableManifest?.pages?.get(pageKey) ?: return liveTextless
        if (record.committed != null) return true
        if (record.displayState.hasCommittedDisplay ||
            record.displayState == PageDisplayState.TEXTLESS_COMPLETE
        ) {
            return true
        }
        val snapshotFile = record.candidate?.pageSnapshotFileName
        if (snapshotFile != null) {
            val snapshot = store.withArtifactEngineLocked { artifact ->
                artifact.readPageSnapshot(snapshotFile)
            }
            if (snapshot != null &&
                (
                    snapshot.hasRenderedResult ||
                        snapshot.isTextlessTerminal ||
                        snapshot.hasRecognizedTranslation ||
                        isNoTextTerminal(snapshot)
                    )
            ) {
                return true
            }
        }
        return liveTextless
    }

    /**
     * T924 terminal predicate for the FINALIZE stranded sweep. A page is
     * terminal when its translation work reached a durable outcome the reader
     * or a later run can act on:
     *
     *  - committed translation (READY/PARTIAL) — the flagged pipeline's
     *    normal terminal (inpaint failures carry their own durable records
     *    from the lane; they never invalidate the translation);
     *  - already durably FAILED / SKIPPED / TEXTLESS;
     *  - legacy rendered display or textless terminal (cross-schedule safety);
     *  - PENDING but every block user-edited — the reader owns the page
     *    (TX-21.3 user authority; marking it failed would overwrite nothing
     *    but would lie about the page's state).
     *
     * A page that is none of these (PENDING/RUNNING/CANCELLED with
     * translatable work) is stranded ONLY while it is owned by the active
     * generation's expectations — an OPEN page from an older generation is
     * likewise stranded, while any COMMITTED terminal state above is durable
     * across generations and never stranded (T924 zero-legacy D1).
     */
    private fun t924PageTerminalAtFinalize(page: PageTranslation?, activeGeneration: Long): Boolean {
        if (page == null) return false
        if (page.hasRenderedResult || page.isTextlessTerminal) return true
        // T924 zero-legacy (D1): a COMMITTED terminal stage is durable
        // regardless of which generation wrote it — a restart after a cancel
        // or process death must never strand (and durable-fail) a prior
        // run's committed work (ST-14/LI-2 reuse). Only OPEN states
        // (PENDING/RUNNING/CANCELLED, below) are generation-owned: a page
        // still mid-write from a dead run is genuinely stranded.
        when (page.translationStatus) {
            StageStatus.READY,
            StageStatus.PARTIAL,
            StageStatus.FAILED,
            StageStatus.SKIPPED,
            StageStatus.TEXTLESS,
            -> return true
            else -> {}
        }
        if (page.runGeneration != activeGeneration) return false
        return page.blocks.isNotEmpty() && page.blocks.all { it.userEditedAt != null }
    }

    /**
     * TX-21.4 deterministic suffix re-plan: rebuilds the pending work from
     * fresh store state with the SAME pure planner and publishes the
     * superseding plan (SC-20) before dispatch resumes. An unchanged
     * fingerprint reuses the published plan (identical content-addressed
     * bytes, idempotent). Never mutates committed history — committed pages
     * are simply no longer pending.
     */
    private suspend fun rebuildDispatchWork(
        artifact: ChapterArtifactEngine,
        orderedPages: List<PageKey>,
        corpusFingerprint: String,
        reason: String,
    ): ReplanResult {
        return when (val rebuilt = buildEnvelopeDispatchWork(artifact, orderedPages, corpusFingerprint)) {
            is EnvelopeWorkBuild.Ready -> {
                val manifestNow = store.artifactManifest
                    ?: return ReplanResult.Failed("manifest unavailable for superseding plan")
                val alreadyPublished =
                    EnvelopePlanPublication.readValidatedPlan(store, manifestNow)
                        .let { read ->
                            read is EnvelopePlanPublication.EnvelopePlanRead.Usable &&
                                read.plan.planFingerprint == rebuilt.plan.planFingerprint
                        }
                if (alreadyPublished) {
                    // T934 LI-4: the identical plan is already durable — the
                    // re-derivation (rebuild) window ends here.
                    listener.envelopePlanCommitted()
                    ReplanResult.Ready(rebuilt.work)
                } else {
                    when (
                        val publication = EnvelopePlanPublication.publish(
                            store = store,
                            manifest = manifestNow,
                            plan = rebuilt.plan,
                            nowEpochMs = nowEpochMs(),
                        )
                    ) {
                        is ChapterArtifactEngine.TransactionOutcome.Committed -> {
                            store.artifactManifest = publication.manifest
                            // T934 LI-4: the superseding plan committed — the
                            // rebuild window ends.
                            listener.envelopePlanCommitted()
                            ReplanResult.Ready(rebuilt.work)
                        }
                        is ChapterArtifactEngine.TransactionOutcome.Rejected ->
                            ReplanResult.Failed("superseding plan publication rejected: ${publication.reason}")
                    }
                }
            }
            is EnvelopeWorkBuild.NothingPending -> ReplanResult.NothingPending
            is EnvelopeWorkBuild.CorpusDrift -> ReplanResult.Failed(rebuilt.reason)
            is EnvelopeWorkBuild.PlannerRejected ->
                ReplanResult.Failed("re-plan rejected: ${rebuilt.reasons.joinToString("; ")} ($reason)")
        }
    }

    /** Analysis-corpus entries rebuilt; null when a checkpoint vanished (drift). */
    private sealed interface EnvelopeWorkBuild {
        data class Ready(
            val work: EnvelopeDispatchWork,
            val plan: EnvelopePlan,
        ) : EnvelopeWorkBuild

        data object NothingPending : EnvelopeWorkBuild

        data class CorpusDrift(val reason: String) : EnvelopeWorkBuild

        data class PlannerRejected(
            val reasons: List<String>,
            val namedPageKeys: Set<String>,
        ) : EnvelopeWorkBuild
    }

    /**
     * Rebuilds the pending dispatch work from FRESH store state (T924-ST-11
     * "re-plan from fresh store state"): durable checkpoints provide the OCR
     * corpus identity; the live store provides the plan-time page identities
     * TX-21 revalidates against. Committed/skipped pages, manual-authoritative
     * pages, user-edited blocks and already-translated blocks are never
     * planned — durable progress is the STORE's per-page translation state,
     * never a coordinator list.
     */
    private suspend fun buildEnvelopeDispatchWork(
        artifact: ChapterArtifactEngine,
        orderedPages: List<PageKey>,
        corpusFingerprint: String,
    ): EnvelopeWorkBuild {
        // T934 LI-4: announce the rebuild window before the silent work starts
        // — this build used to run for MINUTES with zero progress events (the
        // progress sheet sat frozen on a stale snapshot). The listener maps
        // this to a tracker emission; the tracker flips the snapshot's batch
        // phase to REBUILDING and recomputes its store-derived counters.
        listener.envelopePlanStarted(orderedPages.size)
        // T934 LI-x: the resume-hydration adoption below (adoptCheckpointSnapshot
        // per pending page) used to rewrite the FULL manifest JSON once or more
        // PER PAGE (~340 rewrites of a ~360KB document on a 206-page chapter,
        // ~1.3s apart — a main-thread ANR contributor). Coalesce those manifest
        // publications: inside the window each publication only stages the
        // intended manifest (durable rewrite at most every 32 staged
        // publications), and the try/finally guarantees the mandatory final
        // flush before this function returns — on every early-return path — so
        // the plan publication afterwards sees the fully adopted durable state
        // and the façade equals the durable manifest. On any doubt the store
        // flushes (fail-safe); correctness of the plan publish itself no longer
        // depends on the window (T934 LI-x rebase-retry).
        store.withArtifactEngineLocked { artifact ->
            artifact.beginManifestCoalescing()
        }
        try {
            return buildEnvelopeDispatchWorkLocked(artifact, orderedPages, corpusFingerprint)
        } finally {
            store.withArtifactEngineLocked { artifact ->
                artifact.endManifestCoalescing()
            }
        }
    }

    private suspend fun buildEnvelopeDispatchWorkLocked(
        artifact: ChapterArtifactEngine,
        orderedPages: List<PageKey>,
        corpusFingerprint: String,
    ): EnvelopeWorkBuild {
        val corpus = corpusEntriesFromCheckpoints(artifact, orderedPages, orderedPages.size)
            ?: return EnvelopeWorkBuild.CorpusDrift(
                "T924 envelope plan deferred: corpus checkpoints changed under the run",
            )
        val sceneStarts = frozenProfileSceneStartIndexes(artifact)
        // Director decision (2026-09-16): ~5-page batch translation to cut API
        // calls. The scene-break PREFERENCE would otherwise close an envelope
        // at nearly every manhwa page (pages mark scene starts), collapsing
        // batching to 1 page per call; scene crossing inside an envelope is
        // already flagged (crossesScene) and the glossary subset rides the
        // call, so packing through scene starts is safe. Only the structural
        // caps come from the frozen config; token budgets keep the planner
        // defaults so oversized groups still split adaptively.
        val policy = envelopePlannerPolicy ?: EnvelopePlannerPolicy(
            maxBlocksPerEnvelope = frozenConfig.envelopePolicy.maxBlocks,
            maxContributingPages = frozenConfig.envelopePolicy.maxPages,
            preferSceneBreaks = false,
        )
        val workPages = linkedMapOf<String, PageDispatchWork>()
        val plannerPages = mutableListOf<EnvelopePlannerPage>()
        // T934 LI-4: per-page progress for the (potentially minutes-long)
        // resume-hydration loop — one listener call per corpus entry, mapped
        // to a Channel trySend in the tracker; trivially cheap per page.
        val rebuildTotal = corpus.entries.size
        corpus.entries.forEachIndexed { rebuildIndex, entry ->
            listener.envelopePlanProgress(rebuildIndex + 1, rebuildTotal)
            val snapshot = store.snapshot(entry.storagePageKey)
            val page = snapshot.page ?: return EnvelopeWorkBuild.CorpusDrift(
                "T924 envelope plan deferred: live page state missing for ${entry.storagePageKey}",
            )
            if (pageEnvelopeDone(entry.storagePageKey, page)) return@forEachIndexed
            // Resume hydration: a page restored from the artifact store after
            // process death is a synthesized placeholder WITHOUT blocks — the
            // durable OCR content lives in the checkpoint's page-snapshot
            // sidecar (checkpoint CLOSE re-owns it and clears the candidate).
            // Adopt it into the live store under the M1 lease+merge idiom so
            // planning, TX-21 revalidation and TX-20 commits all operate on
            // real block state. Fresh in-session pages carry blocks and skip
            // this entirely; committed/skipped/manual pages are never adopted.
            val (effectiveSnapshot, effectivePage) = if (page.blocks.isEmpty()) {
                val adopted = when (val outcome = adoptCheckpointSnapshot(artifact, entry.storagePageKey, snapshot)) {
                    is CheckpointAdoption.Adopted -> outcome.snapshot
                    is CheckpointAdoption.Failed -> null
                }
                if (adopted?.page == null) {
                    return EnvelopeWorkBuild.CorpusDrift(
                        "T924 envelope plan deferred: checkpoint adoption failed for ${entry.storagePageKey}",
                    )
                }
                adopted to adopted.page
            } else {
                snapshot to page
            }
            val dispatchBlocks = mutableListOf<PlannedBlock>()
            val plannerBlocks = mutableListOf<EnvelopePlannerBlock>()
            effectivePage.blocks.forEachIndexed { index, block ->
                if (block.text.isBlank()) return@forEachIndexed
                // TX-21.3 / INV-07: user edits are authoritative — never planned.
                if (block.userEditedAt != null) return@forEachIndexed
                // Blocks the provider already translated validly (manual or a
                // prior partial candidate) are not requestable — mirrors the
                // retry controller's requestability rule so page completeness
                // stays achievable.
                if (block.translation.isNotBlank() &&
                    block.translation.trim() != block.text.trim()
                ) {
                    return@forEachIndexed
                }
                val stableId = wireBlockId(block.blockId, entry.wirePageKey, index)
                dispatchBlocks += PlannedBlock(
                    stableBlockId = stableId,
                    sourceText = block.text,
                    ocrFingerprint = block.ocrFingerprint(),
                    blockIndex = index,
                )
                plannerBlocks += EnvelopePlannerBlock(stableBlockId = stableId, sourceText = block.text)
            }
            if (dispatchBlocks.isEmpty()) {
                // T934 stranded-page fix: a non-terminal page with NO
                // dispatchable blocks used to be silently skipped EVERY
                // planning round — a self-perpetuating deadlock (its blocks
                // all carry translations or user edits from an earlier
                // interrupted run, or an adopted checkpoint yielded no
                // translatable text) that FINALIZE then stamped with a
                // generic stranded failure. Route it to an explicit
                // terminal state instead.
                stampUnplannablePageTerminal(entry.storagePageKey, effectivePage)
                return@forEachIndexed
            }
            workPages[entry.storagePageKey] = PageDispatchWork(
                pageKey = entry.storagePageKey,
                naturalPageIndex = entry.naturalPageIndex,
                ocrContentFingerprint = entry.contentFingerprint,
                sourceFingerprint = effectivePage.sourceFingerprint,
                planPageVersion = effectiveSnapshot.pageVersion,
                planCandidateGenerationId = effectiveSnapshot.candidateGenerationId,
                planDependencyFingerprint = effectiveSnapshot.dependencyFingerprint,
                planArtifactPageVersion = effectiveSnapshot.artifactPageVersion,
                blocks = dispatchBlocks,
            )
            plannerPages += EnvelopePlannerPage(
                pageKey = entry.storagePageKey,
                naturalPageIndex = entry.naturalPageIndex,
                contentFingerprint = entry.contentFingerprint,
                blocks = plannerBlocks,
                sceneBoundaryBefore = entry.naturalPageIndex in sceneStarts,
            )
        }
        if (plannerPages.isEmpty()) return EnvelopeWorkBuild.NothingPending
        return when (
            val result = GlobalEnvelopePlanner.plan(
                pages = plannerPages,
                corpusFingerprint = corpusFingerprint,
                policy = policy,
                createdAtEpochMs = nowEpochMs(),
            )
        ) {
            is EnvelopePlanResult.Rejected -> EnvelopeWorkBuild.PlannerRejected(
                reasons = result.reasons,
                namedPageKeys = result.reasons.mapNotNull { reason ->
                    oversizedPageRegex.find(reason)?.groupValues?.getOrNull(1)
                }.toSet(),
            )
            is EnvelopePlanResult.Success -> EnvelopeWorkBuild.Ready(
                work = EnvelopeDispatchWork(
                    plan = result.plan,
                    planFingerprint = result.plan.planFingerprint,
                    pages = workPages,
                    providerBackend = frozenConfig.providerKey.substringBefore(':'),
                    providerModel = frozenConfig.providerKey
                        .substringAfter(':', missingDelimiterValue = "")
                        .ifEmpty { null },
                    credentialScope = frozenConfig.credentialId.ifBlank { "default" },
                ),
                plan = result.plan,
            )
        }
    }

    /** ST-11 whole-page done rule: committed, skipped, rendered, or manual-authoritative. */
    private fun pageEnvelopeDone(pageKey: String, page: PageTranslation): Boolean {
        if (page.translationStatus == StageStatus.READY ||
            page.translationStatus == StageStatus.SKIPPED ||
            page.hasRenderedResult
        ) {
            return true
        }
        val committed = store.artifactManifest?.pages?.get(pageKey)?.committed ?: return false
        return committed.hasManualEdits
    }

    /**
     * T934 stranded-page fix: explicit terminal routing for a page that is
     * not envelope-done yet yields no dispatchable blocks (the dispatch-work
     * build used to skip it silently every round). Two honest terminal
     * classes reach here:
     *  - every text block already carries a valid translation or a user edit
     *    (an earlier interrupted run wrote block-level translations whose
     *    page-level commit never landed): adopt the page as
     *    translation-terminal (READY). It is NOT a failure — the reader
     *    already draws these block translations — and READY routes the page
     *    to the display/compose tail like any other completed page;
     *  - no translatable text at all (an adopted block-less checkpoint
     *    page): the [finalizePostOcrStage] textless idiom — translation and
     *    render SKIPPED — so the page becomes durably textless-terminal
     *    instead of PENDING forever.
     * Best-effort: a rejected stamp leaves the page pending, where the
     * FINALIZE sweep's specific per-page reasons still name it — never a
     * silent drop.
     */
    private suspend fun stampUnplannablePageTerminal(pageKey: String, page: PageTranslation) {
        val textBlocks = page.blocks.filter { it.text.isNotBlank() }
        val completeBlocks = textBlocks.count { block ->
            block.userEditedAt != null ||
                (
                    block.translation.isNotBlank() &&
                        block.translation.trim() != block.text.trim()
                    )
        }
        if (textBlocks.isNotEmpty() && completeBlocks < textBlocks.size) {
            // Defensive: a requestable text block would have been dispatched;
            // the caller only routes empty dispatch sets here. Never stamp.
            return
        }
        val textless = textBlocks.isEmpty()
        val reason = when {
            textless -> "page has no translatable text (blocks=${page.blocks.size})"
            textBlocks.all { it.userEditedAt != null } ->
                "all ${textBlocks.size} text blocks are user-authoritative"
            else -> "all ${textBlocks.size} text blocks already carry translations"
        }
        when (store.tryAcquirePageStageLease(pageKey, PageStage.Translation, PageWriteOrigin.BATCH)) {
            is LeaseAcquisition.Granted -> Unit
            else -> {
                logcat(LogPriority.WARN) {
                    "TachiyomiAT t934 terminal adoption lease denied pageHash=${pageHash(pageKey)} reason=$reason"
                }
                return
            }
        }
        try {
            // Snapshot AFTER the (re)acquire: the fence compares the token the
            // lease table holds right now (same-origin re-acquire reuses the
            // run's token; a fresh acquire mints the one this write owns).
            val before = store.snapshot(pageKey)
            val outcome = store.updatePageGuarded(
                pageKey = pageKey,
                expected = ChapterTranslationStore.PatchPrecondition(
                    generation = before.generation,
                    pageVersion = before.pageVersion,
                    leaseToken = before.leaseToken,
                ),
                description = "t934 stranded fix: adopt unplannable page terminal",
            ) { current ->
                (current ?: page).apply {
                    if (textless) {
                        if (translationStatus == StageStatus.PENDING ||
                            translationStatus == StageStatus.RUNNING ||
                            translationStatus == StageStatus.CANCELLED
                        ) {
                            translationStatus = StageStatus.SKIPPED
                        }
                        if (renderStatus == StageStatus.PENDING ||
                            renderStatus == StageStatus.RUNNING ||
                            renderStatus == StageStatus.CANCELLED
                        ) {
                            renderStatus = StageStatus.SKIPPED
                        }
                        // The finalizePostOcrStage parity rule: no mask boxes
                        // means nothing to erase — the scheduler's own
                        // empty-block preservation skip never inpaints this
                        // page, so stamp it SKIPPED to complete the textless
                        // terminal shape.
                        if (inpaintStatus == StageStatus.PENDING && inpaintMaskBoxes.isEmpty()) {
                            inpaintStatus = StageStatus.SKIPPED
                        }
                    } else {
                        if (translationStatus == StageStatus.PENDING ||
                            translationStatus == StageStatus.RUNNING ||
                            translationStatus == StageStatus.CANCELLED
                        ) {
                            translationStatus = StageStatus.READY
                        }
                    }
                    errorMessage = null
                    updatedAt = nowEpochMs()
                }
            }
            when (outcome) {
                is ChapterTranslationStore.PatchResult.Accepted ->
                    logcat(LogPriority.INFO) {
                        "TachiyomiAT t934 page adopted terminal pageHash=${pageHash(pageKey)} reason=$reason"
                    }
                is ChapterTranslationStore.PatchResult.Rejected ->
                    logcat(LogPriority.WARN) {
                        "TachiyomiAT t934 page terminal adoption rejected pageHash=${pageHash(pageKey)} " +
                            "reason=${outcome.reason}"
                    }
            }
        } finally {
            store.releasePageStageLease(pageKey, PageWriteOrigin.BATCH)
        }
    }

    /**
     * Resume hydration for a synthesized (block-less) page: loads the page's
     * durable OCR checkpoint snapshot sidecar and merges it into the live
     * store under the standard BATCH OCR lease (M1 idiom), fenced by the
     * placeholder page's identity. Returns [CheckpointAdoption.Adopted] with
     * the post-merge page snapshot, or [CheckpointAdoption.Failed] with the
     * TYPED reason (T934 R2a.5) — the caller defers the phase or re-runs the
     * page (fail closed), never plans against fabricated content.
     */
    private suspend fun adoptCheckpointSnapshot(
        artifact: ChapterArtifactEngine,
        pageKey: String,
        before: ChapterTranslationStore.PageSnapshot,
    ): CheckpointAdoption {
        val manifest = store.artifactManifest
            ?: return CheckpointAdoption.Failed(CheckpointAdoptionFailure.NO_POINTER)
        val pointer = manifest.ocrCheckpoints[pageKey]
            ?: return CheckpointAdoption.Failed(CheckpointAdoptionFailure.NO_POINTER)
        val checkpoint = when (val read = store.withArtifactEngineLocked { engine ->
            engine.readOcrCheckpoint(pointer)
        }) {
            is ChapterArtifactEngine.OcrCheckpointRead.Usable -> read.checkpoint
            else -> return CheckpointAdoption.Failed(CheckpointAdoptionFailure.SIDE_CAR_UNREADABLE)
        }
        val ocrSnapshot = store.withArtifactEngineLocked { engine ->
            engine.readPageSnapshot(checkpoint.ocrPageSnapshotPointer.fileName)
        }
            ?: return CheckpointAdoption.Failed(CheckpointAdoptionFailure.BUNDLE_MISSING)
        val lease = when (
            val acquisition = store.tryAcquirePageStageLease(pageKey, PageStage.Ocr, PageWriteOrigin.BATCH)
        ) {
            is LeaseAcquisition.Granted -> acquisition.lease
            else -> return CheckpointAdoption.Failed(CheckpointAdoptionFailure.LEASE_DENIED)
        }
        try {
            val outcome = store.mergeOcr(
                OcrStagePatch(
                    pageKey = pageKey,
                    generation = before.generation,
                    expectedPageVersion = before.pageVersion,
                    expectedPriorOcrFingerprints = before.page?.ocrBlockFingerprints().orEmpty(),
                    ocrResult = ocrSnapshot,
                    expectedLeaseToken = lease.token,
                ),
                description = "t924 envelope resume: adopt checkpointed OCR into the live store",
            )
            return when (outcome) {
                is StagePatchResult.Accepted -> CheckpointAdoption.Adopted(store.snapshot(pageKey))
                is StagePatchResult.Rejected -> {
                    logcat(LogPriority.WARN) {
                        "TachiyomiAT t924 checkpoint adoption rejected pageHash=${pageHash(pageKey)} " +
                            "reason=${outcome.reason}"
                    }
                    CheckpointAdoption.Failed(CheckpointAdoptionFailure.MERGE_REJECTED, outcome.reason)
                }
            }
        } finally {
            store.releasePageStageLease(pageKey, PageWriteOrigin.BATCH)
        }
    }

    /**
     * T924 no-render design (2026-09-16 field report): the batch never runs an
     * in-pass render stage — the reader draws the text overlay on demand — but
     * pages completed BEFORE the render-terminal stamp existed (or whose stamp
     * write was rejected) carry renderStatus PENDING forever. hasRenderedResult
     * then never fires: the page never promotes to a committed display bundle
     * (no pageSnapshotFileName), the reader's displayImageName gate stays null
     * and the chapter shows ORIGINALS after reopen, while the reader's
     * stranded-page sweep heals these healthy pages one by one. An adopted
     * preflight page that already carries the full display evidence gets the
     * render-terminal stamp here — the durable write carries hasRenderedResult,
     * which fires the committed promotion and flips the reader gate. Idempotent;
     * a rejection only skips the stamp (the page re-plans as before).
     */
    private suspend fun stampAdoptedRenderTerminal(pageKey: String) {
        stampRenderTerminalIfDisplayComplete(
            pageKey,
            "t924 resume: stamp adopted display-complete page render-terminal",
        )
    }

    /**
     * One [stampRenderTerminalIfDisplayComplete] attempt's result. [Committed]
     * leaves the page display-committed (already stamped, or stamped now).
     * [PublicationRejected] means the lease was held and the display evidence
     * was complete, yet the guarded write still lost — the generic store-side
     * publication rejection ([REJECTED_ARTIFACT_PUBLICATION], a CAS drift the
     * next fresh-snapshot attempt heals, or a display base the store could not
     * validate). That page is HEALTHY: its work is intact and the write may
     * land on a later attempt, so it stays pending for a re-drain — typed-
     * failing it would mislabel good work (the 2026-09-17 D10 regression).
     * [Blocked] carries the specific lease/evidence reason the drain cannot
     * resolve this run.
     */
    private sealed interface RenderStampOutcome {
        data object Committed : RenderStampOutcome
        data object PublicationRejected : RenderStampOutcome
        data class Blocked(val reason: String) : RenderStampOutcome
    }

    /**
     * T934 display-tail drain: stamps [pageKey] render-terminal when it
     * carries the full display evidence (translation READY/PARTIAL, inpaint
     * READY, cleaned image, a translated block) and renderStatus is still
     * PENDING — the exact durable shape of an order-inverted page whose
     * inpaint committed before its envelope translation (the in-lane stamp
     * only fires when translation was ALREADY terminal at inpaint time).
     * The guarded write carries hasRenderedResult, which fires the committed
     * display promotion ([ChapterTranslationStore.promoteDisplayIfReadyLocked]
     * via publishLocked) and flips the reader gate. Idempotent.
     */
    private suspend fun stampRenderTerminalIfDisplayComplete(
        pageKey: String,
        description: String,
    ): RenderStampOutcome {
        when (store.tryAcquirePageStageLease(pageKey, PageStage.Render, PageWriteOrigin.BATCH)) {
            is LeaseAcquisition.Granted -> Unit
            else -> return RenderStampOutcome.Blocked(
                "render-terminal stamp could not acquire the Render lease " +
                    "(owned by MANUAL/native; never preempted)",
            )
        }
        try {
            // The guarded write's lease fence is checked against the CURRENT
            // page lease (render-stage token just acquired), so the page is
            // snapshotted AFTER the acquire — the same idiom the overlap
            // scheduler's drain-side stamp uses (a pre-acquire snapshot would
            // fence on a null token and reject every free page).
            val held = store.snapshot(pageKey)
            val page = held.page
                ?: return RenderStampOutcome.Blocked("expected page is missing from the store")
            if (page.renderStatus == StageStatus.READY) return RenderStampOutcome.Committed
            val displayComplete = (page.translationStatus == StageStatus.READY || page.translationStatus == StageStatus.PARTIAL) &&
                page.inpaintStatus == StageStatus.READY &&
                page.cleanedImageName != null &&
                page.renderStatus == StageStatus.PENDING &&
                page.blocks.any { it.translation.isNotBlank() }
            if (!displayComplete) return RenderStampOutcome.Blocked(displayTailFailureReason(page))
            val expected = ChapterTranslationStore.PatchPrecondition(
                generation = held.generation,
                pageVersion = held.pageVersion,
                blockFingerprints = held.blockFingerprints,
                leaseToken = held.leaseToken,
                candidateGenerationId = held.candidateGenerationId,
                dependencyFingerprint = held.dependencyFingerprint,
                artifactPageVersion = held.artifactPageVersion,
            )
            val outcome = store.updatePageGuarded(
                pageKey = pageKey,
                expected = expected,
                description = description,
            ) { current ->
                (current ?: page).apply {
                    if (renderStatus == StageStatus.PENDING) {
                        renderStatus = StageStatus.READY
                        updatedAt = System.currentTimeMillis()
                    }
                }
            }
            if (outcome is ChapterTranslationStore.PatchResult.Rejected) {
                logcat(LogPriority.WARN) {
                    "TachiyomiAT t924 render stamp rejected pageHash=${pageHash(pageKey)} reason=${outcome.reason}"
                }
                return if (outcome.reason == REJECTED_ARTIFACT_PUBLICATION) {
                    RenderStampOutcome.PublicationRejected
                } else {
                    RenderStampOutcome.Blocked("render-terminal write rejected: ${outcome.reason}")
                }
            }
            return RenderStampOutcome.Committed
        } finally {
            store.releasePageStageLease(pageKey, PageWriteOrigin.BATCH)
        }
    }

    private class DisplayTailDrain(
        val drained: Int,
        val failed: List<String>,
    )

    /**
     * The exact durable shape of a display-tail page: translate+inpaint work
     * DONE (translation READY/PARTIAL, inpaint READY, cleaned image, a
     * translated block) with the display commit still missing (renderStatus
     * PENDING — the render-terminal stamp never landed). Textless terminals,
     * genuinely unfinished pages and already-stamped pages are NOT tail: they
     * belong to the stranded sweep / the healthy COMPLETE path.
     */
    private fun displayTailPending(page: PageTranslation?): Boolean =
        page != null &&
            page.renderStatus == StageStatus.PENDING &&
            (page.translationStatus == StageStatus.READY || page.translationStatus == StageStatus.PARTIAL) &&
            page.inpaintStatus == StageStatus.READY &&
            page.cleanedImageName != null &&
            page.blocks.any { it.translation.isNotBlank() }

    /**
     * T934: drains the display/compose tail before the run may publish
     * COMPLETE — every page whose translate+inpaint work is DONE but whose
     * display commit (render-terminal stamp → committed promotion) has not
     * landed. The overlap scheduler's drain-side sweep
     * ([OverlapScheduler.drainSerial] → stampRenderTerminalOrphans) heals the
     * tail in one pass; a lease denial or a rejected write silently skips a
     * page there, and nothing re-checks — that silent skip is the frozen-52
     * signature of the 2026-09-17 run (ready trailed cleaning all pass long,
     * then froze at COMPLETE).
     *
     * Bounded: at most [MAX_DISPLAY_TAIL_DRAIN_PASSES] passes over the
     * remaining tail, each recomputed from FRESH snapshots (a stale-write
     * rejection heals on the retry; a MANUAL Render owner does not). A page
     * still pending after the last pass takes the typed terminal
     * ([persistDisplayTailFailure]) — the run still completes, surfacing the
     * page in the pages-need-attention UI with its specific reason instead of
     * publishing a clean COMPLETE over a frozen ORIGINAL_ONLY page. A page
     * whose attempts failed ONLY with publication rejections is the exception:
     * it is work-complete and healthy, so it stays pending (invisible to the
     * stranded sweep — translation READY is terminal there) for the next run's
     * re-drain instead of taking a false typed failure. All progress is store
     * state — a run killed mid-drain re-enters FINALIZE and re-runs this
     * idempotently.
     */
    private suspend fun drainDisplayTailBeforeComplete(orderedPageKeys: List<String>): DisplayTailDrain {
        var drained = 0
        var pending = orderedPageKeys
            .map { pageKey -> pageKey to store.snapshot(pageKey).page }
            .filter { (_, page) -> displayTailPending(page) }
            .map { (pageKey, _) -> pageKey }
        // Pages whose LAST attempt failed only with a publication rejection,
        // and the specific blocker of every other pending page (a later
        // attempt of either kind supersedes the earlier classification).
        val publicationRejected = mutableSetOf<String>()
        val blockedReasons = mutableMapOf<String, String>()
        repeat(MAX_DISPLAY_TAIL_DRAIN_PASSES) {
            if (pending.isEmpty()) return DisplayTailDrain(drained, emptyList())
            pending = pending.filter { pageKey ->
                when (
                    val outcome = stampRenderTerminalIfDisplayComplete(
                        pageKey,
                        "t934 finalize: drain display-complete page tail to its committed display",
                    )
                ) {
                    is RenderStampOutcome.Committed -> {
                        drained++
                        publicationRejected.remove(pageKey)
                        blockedReasons.remove(pageKey)
                        false
                    }
                    is RenderStampOutcome.PublicationRejected -> {
                        publicationRejected += pageKey
                        true
                    }
                    is RenderStampOutcome.Blocked -> {
                        publicationRejected.remove(pageKey)
                        blockedReasons[pageKey] = outcome.reason
                        true
                    }
                }
            }
        }
        val blocked = pending.filter { it !in publicationRejected }
        if (publicationRejected.isNotEmpty()) {
            logcat(LogPriority.INFO) {
                "TachiyomiAT t934 display tail left pending on publication rejections: " +
                    "count=${publicationRejected.size}"
            }
        }
        blocked.forEach { pageKey ->
            val reason = blockedReasons[pageKey]
                ?: displayTailFailureReason(store.snapshot(pageKey).page)
            logcat(LogPriority.WARN) {
                "TachiyomiAT t934 display tail page not committed at FINALIZE " +
                    "pageHash=${ShortHash.hash(pageKey)} reason=$reason"
            }
            persistDisplayTailFailure(pageKey, reason)
        }
        return DisplayTailDrain(drained, blocked)
    }

    /**
     * T934 display-tail companion of [strandedPageReason]: the SPECIFIC reason
     * a translate+inpaint-complete page still lacks its display commit after
     * the bounded drain. Names the blocking evidence so the durable failure is
     * actionable instead of a bare stage status.
     */
    private fun displayTailFailureReason(page: PageTranslation?): String {
        if (page == null) return "expected page is missing from the store"
        return when {
            page.renderStatus == StageStatus.READY -> "display commit landed after the drain gave up"
            page.inpaintStatus != StageStatus.READY ->
                "inpaint no longer terminal (status=${page.inpaintStatus})"
            page.cleanedImageName == null -> "cleaned image reference missing"
            page.translationStatus != StageStatus.READY && page.translationStatus != StageStatus.PARTIAL ->
                "translation no longer terminal (status=${page.translationStatus})"
            else ->
                "display evidence incomplete: no translated block to show " +
                    "(translation=${page.translationStatus}, inpaint=${page.inpaintStatus}, " +
                    "render=${page.renderStatus})"
        }
    }

    /**
     * T934: the typed terminal for a page whose display work genuinely cannot
     * finish this run — a durable retryable LAYOUT-stage failure (the
     * render-terminal stamp's stage) plus the live renderStatus FAILED flip,
     * mirroring the stranded-page sweep's carrier/reason style. The run still
     * completes: the reconciled outcome carries the page as a warning
     * (BatchProgressReconciler.reconcileFlaggedCompleted) and the next run's
     * drain (or the reader's manual retry) re-attempts the stamp. Best-effort:
     * a rejected record keeps the page pending, where the next FINALIZE
     * re-drains it — never a silent drop. Only [RenderStampOutcome.Blocked]
     * pages route here — a page rejected merely on its publication
     * ([RenderStampOutcome.PublicationRejected]) is work-complete and stays
     * pending, never typed-failed.
     */
    private suspend fun persistDisplayTailFailure(
        pageKey: String,
        reason: String,
    ) {
        try {
            val carrier = "display commit did not land at FINALIZE"
            val metadata = DurableFailureMetadata(
                pageKey = pageKey,
                stage = ArtifactStage.LAYOUT,
                status = ArtifactStageStatus.FAILED_RETRYABLE,
                category = FailureCategory.TRANSIENT,
                retryCount = 1,
                lastFailureMessage = "$carrier: $reason",
                lastFailedAtEpochMs = nowEpochMs(),
                nextEligibleRetryAtEpochMs = null,
            )
            suspend fun patch(expected: ChapterTranslationStore.PageSnapshot) =
                store.persistDurableStageFailure(
                    pageKey = pageKey,
                    expected = ChapterTranslationStore.PatchPrecondition(
                        generation = expected.generation,
                        pageVersion = expected.pageVersion,
                        leaseToken = expected.leaseToken,
                    ),
                    failure = metadata,
                    description = "t934 finalize display tail failure",
                ) { current ->
                    (current ?: PageTranslation(sourceFileName = pageKey)).apply {
                        sourceFileName = pageKey
                        renderStatus = StageStatus.FAILED
                        errorMessage = metadata.lastFailureMessage
                        updatedAt = nowEpochMs()
                    }
                }
            // T934 round 3: result-aware (the persistDurablePreflightFailure
            // idiom) — a Rejected must surface its store reason, never vanish.
            // One fresh-snapshot retry heals a fence drifted between the
            // classification and this persist (the stamp attempts of the
            // intervening drain passes move page/lease state); the page flip
            // and the durable failure record share ONE publication per
            // attempt, so a retry that lands keeps them atomic.
            var result = patch(store.snapshot(pageKey))
            if (result is ChapterTranslationStore.PatchResult.Rejected) {
                result = patch(store.snapshot(pageKey))
            }
            if (result is ChapterTranslationStore.PatchResult.Rejected) {
                logcat(LogPriority.WARN) {
                    "TachiyomiAT t934 display tail failure record rejected " +
                        "pageHash=${ShortHash.hash(pageKey)} reason=${result.reason}"
                }
            }
        } catch (e: Exception) {
            logcat(LogPriority.WARN) {
                "TachiyomiAT t934 display tail failure record rejected pageHash=" +
                    "${ShortHash.hash(pageKey)} error=${e::class.java.simpleName}"
            }
        }
    }

    /** Frozen-profile scene starts (page-preference input for the pure planner). */
    private suspend fun frozenProfileSceneStartIndexes(artifact: ChapterArtifactEngine): Set<Int> {
        val manifest = store.artifactManifest ?: return emptySet()
        val pointer = manifest.profile ?: return emptySet()
        val profile = when (
            val read = store.withArtifactEngineLocked { engine ->
                engine.readSidecarDocument(
                    pointer = pointer.toSidecarPointer(),
                    serializer = ChapterTranslationProfile.serializer(),
                    currentSchemaVersion = ChapterTranslationProfile.SCHEMA_VERSION,
                    expectedKind = ChapterTranslationProfile.KIND,
                    schemaVersionOf = { it.schemaVersion },
                    kindOf = { it.kind },
                    isValid = { it.isSemanticallyValid },
                )
            } ?: SidecarRead.Absent
        ) {
            is SidecarRead.Usable -> read.document
            else -> return emptySet()
        }
        return profile.scenes.map { it.pageRange.firstNaturalPageIndex }.toSet()
    }

    /** The contextual-chunk provider profile derived from the frozen provider key. */
    private fun providerChunkProfile(): TranslationContextChunkPlanner.Profile =
        if (frozenConfig.providerKey.startsWith("lmstudio:", ignoreCase = true)) {
            TranslationContextChunkPlanner.Profile.LM_STUDIO
        } else {
            TranslationContextChunkPlanner.Profile.DEFAULT
        }

    /**
     * T924 terminal predicate companion: the SPECIFIC reason a non-terminal
     * page was stranded at FINALIZE. Names the page's actual work state so
     * the durable failure (and the progress sheet's failure groups) carries
     * something actionable instead of a bare stage status.
     */
    private fun strandedPageReason(page: PageTranslation?): String {
        if (page == null) return "expected page is missing from the store"
        val textBlocks = page.blocks.filter { it.text.isNotBlank() }
        val resolved = textBlocks.count { block ->
            block.userEditedAt != null ||
                (
                    block.translation.isNotBlank() &&
                        block.translation.trim() != block.text.trim()
                    )
        }
        return when {
            textBlocks.isEmpty() ->
                "no translatable text (status=${page.translationStatus}, blocks=${page.blocks.size})"
            resolved == textBlocks.size ->
                "all ${textBlocks.size} text blocks already translated or user-edited " +
                    "(status=${page.translationStatus})"
            else ->
                "translation left non-terminal (status=${page.translationStatus}, " +
                    "textBlocks=${textBlocks.size}, unfinished=${textBlocks.size - resolved})"
        }
    }

    /**
     * ST-11 terminal: a page that cannot fit any legal envelope takes a
     * durable structural failure (SOURCE category — the page content, not
     * the transport, cannot fit the policy) so later runs do not re-plan it
     * silently. Best-effort: a rejected record keeps the typed pause.
     * The [carrier] names the sweep that failed the page (planner rejection
     * vs the FINALIZE stranded sweep) so the recorded reason is accurate.
     */
    private suspend fun persistEnvelopeStructuralFailure(
        pageKey: String,
        reason: String,
        carrier: String = "envelope planner rejected the page",
    ) {
        try {
            val snapshot = store.snapshot(pageKey)
            val metadata = DurableFailureMetadata(
                pageKey = pageKey,
                stage = ArtifactStage.TRANSLATION,
                status = ArtifactStageStatus.FAILED_RETRYABLE,
                category = FailureCategory.SOURCE,
                retryCount = 1,
                lastFailureMessage = "$carrier: $reason",
                lastFailedAtEpochMs = nowEpochMs(),
                nextEligibleRetryAtEpochMs = null,
            )
            store.persistDurableStageFailure(
                pageKey = pageKey,
                expected = ChapterTranslationStore.PatchPrecondition(
                    generation = snapshot.generation,
                    pageVersion = snapshot.pageVersion,
                    leaseToken = snapshot.leaseToken,
                ),
                failure = metadata,
                description = "t924 envelope structural failure",
            ) { current ->
                (current ?: PageTranslation(sourceFileName = pageKey)).apply {
                    sourceFileName = pageKey
                    translationStatus = StageStatus.FAILED
                    errorMessage = metadata.lastFailureMessage
                    updatedAt = nowEpochMs()
                }
            }
        } catch (e: Exception) {
            logcat(LogPriority.WARN) {
                "TachiyomiAT t924 envelope structural failure record rejected pageHash=" +
                    "${ShortHash.hash(pageKey)} error=${e::class.java.simpleName}"
            }
        }
    }

    /** A reusable frozen profile plus the corpus identity it was probed with. */
    private data class FrozenProfileReuse(
        val pointer: ProfilePointer,
        val corpusFingerprint: String,
    )

    /**
     * ST-05 skip-rule probe: computes the current run's corpus identity from
     * the DURABLE checkpoints (local reads only) and, when a frozen profile
     * pointer exists whose FP-04 input fingerprint matches AND whose sidecar
     * fully validates (content, version, recomputed FP-05), returns the
     * reusable pointer. Any gap returns null — the normal path runs.
     */
    private suspend fun frozenProfileReuse(
        artifact: ChapterArtifactEngine,
        orderedPages: List<PageKey>,
        expectedPageCount: Int,
    ): FrozenProfileReuse? {
        val manifest = store.artifactManifest ?: return null
        val pointer = manifest.profile ?: return null
        if (!pointer.isWellFormed()) return null
        // The FP-04 corpus identity must come from checkpoints whose source
        // identity STILL matches the current source (ST-04 resume: identities
        // are revalidated against current files). A changed/missing page
        // makes the frozen profile NOT reusable — the normal path re-OCRs it
        // and the corpus drift gates downstream (wave-4 F-W4-1 discipline).
        val corpusPairs = mutableListOf<Pair<String, String>>()
        for ((pageKey, _) in orderedPages) {
            val reusable = checkpointReuse(artifact, pageKey)
            val fingerprint = (reusable as? CheckpointReuse.Reusable)?.ocrContentFingerprint ?: return null
            corpusPairs += pageKey to fingerprint
        }
        val naturalOrderProven =
            orderedPages.map { it.second }.toSet() == (0 until expectedPageCount).toSet()
        val corpusFingerprint = StageFingerprints.ocrCorpusFingerprint(
            pages = corpusPairs,
            expectedPageCount = expectedPageCount,
            expectedPageCountTrusted = true,
            naturalOrderProven = naturalOrderProven,
        )
        val inputFingerprint = profileInputFingerprintOf(corpusFingerprint)
        return when (
            val read = ProfileFreezePublication.readReusableFrozenProfile(
                store = store,
                manifest = manifest,
                expectedInputFingerprint = inputFingerprint,
            )
        ) {
            is ProfileFreezePublication.FrozenProfileRead.Reusable ->
                FrozenProfileReuse(pointer, corpusFingerprint)
            is ProfileFreezePublication.FrozenProfileRead.NotReusable -> null
        }
    }

    /**
     * T924-FP-04 for this run — computed with the SAME policy-fingerprint
     * helper the analysis identity uses, so the freeze-time input identity
     * and the reuse-probe identity are consistent by construction. Absent
     * user/series authority is the explicit ABSENT literal inside
     * [StageFingerprints.profileInputFingerprint] (absence is a value).
     */
    private fun profileInputFingerprintOf(corpusFingerprint: String): String =
        StageFingerprints.profileInputFingerprint(
            ocrCorpusFingerprint = corpusFingerprint,
            sourceLanguage = frozenConfig.sourceLang,
            targetLanguage = frozenConfig.targetLang,
            analysisSchemaVersion = AnalyzerProvenanceFactory.ANALYSIS_SCHEMA_VERSION,
            analysisPromptVersion = AnalyzerProvenanceFactory.PROMPT_VERSION,
            analyzerProvider = frozenConfig.providerKey.substringBefore(':'),
            analyzerModel = frozenConfig.providerKey.substringAfter(':', missingDelimiterValue = ""),
            analyzerCredentialSignature = frozenConfig.credentialId.takeIf { it.isNotBlank() },
            analyzerPolicyFingerprint = policyFingerprint(
                "analysis-policy-v1",
                frozenConfig.analysisPolicy.overlapPages,
            ),
            userAuthorityFingerprint = null,
            seriesAuthorityFingerprint = null,
        )

    /**
     * Wave-4 F-W4-1: the ST-08 resume prefix is only valid when the persisted
     * chunks ARE the re-planned chunks. Every persisted ordinal i is read back
     * and compared against planned chunk i (chunkId + core page keys +
     * contributing corpus fingerprint — the chunkId alone already embeds the
     * ordinal and corpus8, but all three are compared explicitly); an
     * unreadable/corrupt sidecar or a prefix longer than the plan counts as a
     * mismatch. Returns the typed pause reason, or null when the prefix is
     * empty or fully consistent with the current plan.
     */
    private suspend fun validatePersistedPrefix(
        artifact: ChapterArtifactEngine,
        plannedChunks: List<PlannedAnalysisChunk>,
    ): String? {
        val pointers = store.artifactManifest?.analysisChunks ?: return null
        if (pointers.isEmpty()) return null
        for (index in pointers.indices) {
            val planned = plannedChunks.getOrNull(index)
                ?: return "T924 analysis prefix stale: persisted ${pointers.size} chunks " +
                    "but the re-planned corpus yields ${plannedChunks.size}"
            val persisted = when (
                val read = store.withArtifactEngineLocked { engine ->
                    engine.readSidecarDocument(
                        pointer = pointers[index],
                        serializer = AnalysisChunkResult.serializer(),
                        currentSchemaVersion = AnalysisChunkResult.SCHEMA_VERSION,
                        expectedKind = AnalysisChunkResult.KIND,
                        schemaVersionOf = { it.schemaVersion },
                        kindOf = { it.kind },
                        isValid = { it.isSemanticallyValid },
                    )
                } ?: SidecarRead.Absent
            ) {
                is SidecarRead.Usable -> read.document
                else -> null
            }
            if (persisted == null ||
                persisted.chunkId != planned.chunkId ||
                persisted.corePageKeys != planned.corePageKeys ||
                persisted.contributingCorpusFingerprint != planned.contributingCorpusFingerprint
            ) {
                return "T924 analysis prefix stale: persisted chunk $index " +
                    "(${persisted?.chunkId ?: "unreadable"}) does not match the re-planned " +
                    "chunk ${planned.chunkId} — the OCR corpus changed under the chunk list"
            }
        }
        return null
    }

    /**
     * Rebuilds the analysis corpus from the durable checkpoints. Each entry
     * carries the wire identities the analysis request/evidence universe uses
     * (`p<N>` pages, `p<N>_b<M>` blocks) alongside the persisted identities.
     */
    private suspend fun corpusEntriesFromCheckpoints(
        artifact: ChapterArtifactEngine,
        orderedPages: List<PageKey>,
        expectedPageCount: Int,
    ): AnalysisCorpus? {
        val manifest = store.artifactManifest ?: return null
        val entries = mutableListOf<AnalysisCorpusEntry>()
        for ((pageKey, pageIndex) in orderedPages) {
            val pointer = manifest.ocrCheckpoints[pageKey] ?: return null
            val checkpoint = when (val read = store.withArtifactEngineLocked { engine ->
                engine.readOcrCheckpoint(pointer)
            }) {
                is ChapterArtifactEngine.OcrCheckpointRead.Usable -> read.checkpoint
                else -> return null
            }
            val snapshot = store.withArtifactEngineLocked { engine ->
                engine.readPageSnapshot(checkpoint.ocrPageSnapshotPointer.fileName)
            }
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
                // CL100K on the real text (CJK-aware — chars/4 undercounts
                // CJK ~2-4x) + the wire envelope's per-block/per-page overhead,
                // so chunk windowing budgets the dispatch payload, not the
                // raw OCR text (T924 analysis under-8k dispatch contract).
                estimatedInputTokens = TranslationContextChunkPlanner.estimateTokens(
                    blocks.joinToString("\n") { it.text },
                ) + blocks.size * AnalysisRequestBuilder.PER_BLOCK_ENVELOPE_TOKENS +
                    AnalysisRequestBuilder.PER_PAGE_ENVELOPE_TOKENS,
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
                // Wave-4 F-W4-3: the DR-A coverage classification is durable
                // so slice-B reconcile can treat MISSING_ONLY as pending.
                coverage = when (outcome.coverage.kind) {
                    AnalysisCoverageKind.COMPLETE -> AnalysisChunkCoverage.COMPLETE
                    AnalysisCoverageKind.MISSING_ONLY -> AnalysisChunkCoverage.MISSING_ONLY
                },
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
     * The per-page checkpoint transaction: CLOSE the BATCH candidate (TX-03 default).
     *
     * T934 R2a: the checkpoint stamps the page's ADMISSION identity (the
     * recorded digest, or the fresh dispatch observation for a first
     * admission) — the store publishes it into
     * [ChapterArtifactManifest.sourceShaByPageKey] in the SAME atomic
     * transaction, so the digest is recorded at write time, never at run end.
     */
    private suspend fun checkpointPage(
        artifact: ChapterArtifactEngine,
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
        val admissionSha = admissionSourceSha(pageKey)
        return store.checkpointOcr(
            pageKey = pageKey,
            generation = snapshot.generation,
            expectedPageVersion = snapshot.pageVersion,
            expectedLeaseToken = leaseToken,
            expectedCandidateGenerationId = ref.candidateGenerationId,
            expectedArtifactPageVersion = snapshot.artifactPageVersion,
            expectedDependencyFingerprint = ref.dependencyFingerprint,
            sourceSha256 = admissionSha,
            sourceOrientation = orientationOf(snapshot),
            mode = OcrCheckpointMode.CLOSE,
            description = "t924 ocr preflight checkpoint",
        )
    }

    /**
     * ST-06 reuse rule (T934 R2a typed): a usable checkpoint whose recorded
     * source identity still matches the page's ADMISSION identity. The
     * admission identity prefers the fresh dispatch observation, so a changed
     * source is DETECTED here at consumption and fails closed (the page
     * re-runs — never stale reuse); with no fresh observation it falls back
     * to the RECORDED digest (written at first admission), so a dispatch
     * hash failure can no longer force a full re-OCR of a proven-unchanged
     * chapter. A checkpoint that cannot prove source equality is typed
     * [CheckpointAdoptionFailure], not silently dropped.
     */
    private suspend fun checkpointReuse(
        artifact: ChapterArtifactEngine,
        pageKey: String,
    ): CheckpointReuse {
        val manifest = store.artifactManifest
            ?: return CheckpointReuse.Unavailable(CheckpointAdoptionFailure.NO_POINTER)
        val pointer = manifest.ocrCheckpoints[pageKey]
            ?: return CheckpointReuse.Unavailable(CheckpointAdoptionFailure.NO_POINTER)
        val read = store.withArtifactEngineLocked { engine ->
            engine.readOcrCheckpoint(pointer)
        }
        if (read !is ChapterArtifactEngine.OcrCheckpointRead.Usable) {
            return CheckpointReuse.Unavailable(CheckpointAdoptionFailure.SIDE_CAR_UNREADABLE)
        }
        val admissionSha = admissionSourceSha(pageKey)
        if (admissionSha == null || read.checkpoint.sourceIdentity.sha256 != admissionSha) {
            return CheckpointReuse.Unavailable(CheckpointAdoptionFailure.SHA_MISMATCH)
        }
        return CheckpointReuse.Reusable(read.checkpoint.ocrContentFingerprint)
    }

    private suspend fun readCheckpointFingerprint(
        artifact: ChapterArtifactEngine,
        pageKey: String,
    ): String? {
        val manifest = store.artifactManifest ?: return null
        val pointer = manifest.ocrCheckpoints[pageKey] ?: return null
        val read = store.withArtifactEngineLocked { engine ->
            engine.readOcrCheckpoint(pointer)
        }
        return (read as? ChapterArtifactEngine.OcrCheckpointRead.Usable)?.checkpoint?.ocrContentFingerprint
    }

    private suspend fun existingActiveRecord(
        artifact: ChapterArtifactEngine,
    ): ChapterArtifactEngine.RunRecordRead? {
        // ST-14 dispatch reads the DURABLE manifest, not the facade's cached
        // snapshot: the cache is a CAS optimization with many writers, while
        // this decision must never re-run paid work (or drain a stale
        // FINALIZE) behind what the artifact tree actually records. One
        // sidecar read per dispatch — negligible next to the preflight.
        return store.withArtifactEngineLocked { engine ->
            val pointer = engine.readManifest()?.activeRun ?: return@withArtifactEngineLocked null
            engine.readRunRecord(pointer)
        }
    }

    /**
     * Best-effort run-record publication. The record is identity/progress
     * state; per-page checkpoints in the manifest are the authoritative
     * durable state (T924-ST-06), so a rejected publication never fails the
     * preflight — it only loses advisory progress.
     *
     * Returns the publication outcome so callers that publish a NON-advisory
     * record can act on it: the run-closure COMPLETE in
     * [drainFinalizeAndComplete] MUST inspect it (a rejected closure leaves
     * the run durably at FINALIZE — reporting finished would desynchronize
     * the shell from the record), while the advisory preflight callers may
     * ignore the return value.
     *
     * The phase pointer itself MUST never be lost to the T924-SC-02
     * phaseCounters bound (32 keys): D6's executor counters re-blew the
     * wave-7b budget, silently dropping the ST-14 FINALIZE record and with
     * it the durable state a crash resume needs. Counters are best-effort
     * progress carriers, so an over-bound record publishes with the OLDEST
     * counter keys trimmed (insertion order) — the phase transition always
     * lands.
     */
    private suspend fun publishRecord(
        artifact: ChapterArtifactEngine,
        record: ChapterRunRecord,
    ): ChapterArtifactEngine.TransactionOutcome? {
        val bounded = if (record.phaseCounters.size > ChapterRunRecord.MAX_PHASE_COUNTER_KEYS) {
            record.copy(
                phaseCounters = record.phaseCounters.entries
                    .toList()
                    .takeLast(ChapterRunRecord.MAX_PHASE_COUNTER_KEYS)
                    .associate { it.key to it.value },
            )
        } else {
            record
        }
        val json = ArtifactDocumentJson.encodeToString(bounded)
        val outcome = store.withArtifactEngineLocked { engine ->
            val manifest = engine.readManifest() ?: return@withArtifactEngineLocked null
            engine.publishActiveRun(
                manifest = manifest,
                record = bounded,
                contentFingerprint = sha256Hex(json.encodeToByteArray()),
                nowEpochMs = nowEpochMs(),
            )
        }
        when (outcome) {
            is ChapterArtifactEngine.TransactionOutcome.Committed -> {
                // Keep the facade's manifest snapshot current — a stale
                // snapshot would fail the next checkpoint's whole-manifest CAS.
                store.artifactManifest = outcome.manifest
            }
            is ChapterArtifactEngine.TransactionOutcome.Rejected -> {
                logcat(LogPriority.WARN) {
                    "TachiyomiAT t924 preflight run record publication rejected: ${outcome.reason}"
                }
            }
            null -> Unit
        }
        return outcome
    }

    private fun record(
        runId: String,
        state: ChapterRunState,
        frozenFingerprint: String,
        sourceDigest: String,
        counters: Map<String, Int>,
        ocrCorpusFingerprint: String? = null,
        profilePointer: ProfilePointer? = null,
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
            profilePointer = profilePointer,
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

    enum class BatchCoordinatorKind {
        /** T924 chapter-profile coordinator — the AI-model lane. */
        PROFILE_PIPELINE,

        /**
         * T924 Phase 4 Wave A: the STANDARD-engine lane — the same
         * coordinator with [standardLane] set: pure FULL OCR
         * preflight, then per-page legacy-machinery batch translation
         * (no glossary, no analysis/profile/envelope work) and the shared
         * engine-agnostic FINALIZE.
         */
        STANDARD_PIPELINE,
    }

    companion object {

        /**
         * Per-page preflight watchdog bound. Deliberately larger than the
         * native lane's SINGLE_PAGE_TIMEOUT_MS (120s) so the lane's own
         * timeout handles native wedges; this bound catches the UNGUARDED
         * suspensions before the lane — lease acquisition, bitmap-budget
         * permits, SAF decode — where a wedge used to stall the pass
         * silently forever.
         */
        private const val PREFLIGHT_PAGE_TIMEOUT_MS = 240_000L

        /**
         * The standard tail's translate-time terminal predicate: a page with a
         * committed translation (READY/PARTIAL), a durable no-text terminal
         * (SKIPPED — the legacy worker's textless commit) or full textless
         * terminal, or a rendered result (cross-schedule safety, matching
         * [t924PageTerminalAtFinalize]) never re-enters the provider. Deliberately
         * narrower than the finalize predicate — FAILED/PENDING pages re-attempt
         * (resume-with-retry semantics, the legacy per-page idiom).
         *
         * Internal (not private) because the standard seam in
         * [BatchChapterTranslator] must re-evaluate the SAME predicate AFTER the
         * page lease is granted: a concurrent owner can commit its terminal
         * stage in the window between the tail's pre-check and the lease
         * acquisition, and an already-terminal page is never re-paid (T917
         * exactly-once).
         */
        internal fun standardPageTerminalAtTranslate(page: PageTranslation): Boolean =
            page.hasRenderedResult ||
                page.isTextlessTerminal ||
                page.translationStatus == StageStatus.READY ||
                page.translationStatus == StageStatus.PARTIAL ||
                page.translationStatus == StageStatus.SKIPPED

        /** Stopped-not-finished diagnostic carried in the paused outcome. */
        const val STOP_REASON =
            "T924 OCR preflight complete; analysis/profile/translation arrive in later stages"

        /** Stage-5 slice A terminals (still PAUSED — slice B owns the freeze). */
        const val ANALYSIS_NO_WORK_REASON =
            "T924 analysis skipped: no chunkable OCR work in this chapter"
        const val ANALYSIS_NO_TRANSPORT_REASON =
            "T924 analysis paused: no typed analysis transport wired (CONFIGURATION gate)"
        const val GLOSSARY_SYNTHESIS_NO_TRANSPORT_REASON =
            "T924 glossary synthesis paused: no synthesis transport wired (CONFIGURATION gate)"

        /** Stage-5 slice B terminals (STILL PAUSED — envelope/translation are Stage 6). */
        const val PROFILE_FROZEN_STOP_REASON =
            "T924 profile frozen; envelope plan/translation arrive in Stage 6"
        const val PROFILE_FROZEN_REUSE_REASON =
            "T924 compatible frozen profile reused (ST-05 skip-to-phase); " +
                "envelope plan/translation arrive in Stage 6"

        /**
         * Stage-6 slice A terminals. The PROFILE_FROZEN_* reasons above are
         * retained only for record-history compatibility — slice A runs no
         * longer return them: PROFILE_FROZEN now CONTINUES into the envelope
         * phase and the run terminates at one of the terminals below (still
         * PAUSED — native/render are Stage 7 and COMPLETE is never published).
         */
        const val ENVELOPE_NO_WORK_REASON =
            "T924 envelope plan skipped: no translatable OCR work in this chapter"
        const val TRANSLATE_NO_TRANSPORT_REASON =
            "T924 translation paused: no typed AI text translator wired (CONFIGURATION gate)"
        const val TRANSLATE_STOP_REASON =
            "T924 translation envelopes drained; native/render arrive in Stage 7"

        /**
         * T924 Phase 4 Wave A: the standard lane's typed CONFIGURATION pause —
         * the coordinator was constructed with [ChapterProfileBatchCoordinator.standardLane]
         * but no [ChapterProfileBatchCoordinator.standardTranslateOutcome]
         * seam. Same discipline as [ANALYSIS_NO_TRANSPORT_REASON]: never run
         * provider-bound work without a typed transport.
         */
        const val STANDARD_NO_SEAM_REASON =
            "T924 standard translation paused: no typed standard translate seam wired (CONFIGURATION gate)"

        /**
         * T924 Stage 7 (D4): the terminal of a drained run. The completion
         * semantics are the LEGACY translation-committed ones — the
         * DISPLAY_READY redefinition below is still gate-7.8-gated OFF.
         */
        const val TRANSLATE_COMPLETE_REASON =
            "T924 run complete: every page reached its durable terminal state " +
                "(legacy completion semantics; DISPLAY_READY redefinition is gate-7.8-gated OFF)"

        /**
         * ST-14 resume: the durable record already reads COMPLETE — the
         * re-dispatch finishes idempotently with zero work and no new record
         * publication.
         */
        const val RESUME_COMPLETE_REASON =
            "T924 recorded run already COMPLETE; treated as finished (ST-14 idempotent resume)"

        /**
         * Stage-7 review F-3: the run-closure COMPLETE publication was
         * rejected by the artifact store (whole-manifest CAS conflict), so
         * the run is still durably at FINALIZE. Reporting finished here
         * would let the shell mark the chapter done while the durable
         * record disagrees; the typed PAUSE instead makes the shell pause
         * the run, and a later dispatch re-enters the ST-14 FINALIZE resume
         * ([resumeFinalizeOrComplete] → [drainFinalizeAndComplete]), which
         * re-attempts the run's single COMPLETE publication.
         */
        const val RUN_CLOSURE_REJECTED_REASON =
            "T924 run-closure COMPLETE publication rejected; run stays at FINALIZE (ST-14 resume re-attempts closure)"

        /**
         * T924 gate 7.8 ENCODED GATE (never activated on device-gated
         * authority): redefining Batch completion as DISPLAY_READY (all reader
         * paths hydrate durable plans instead of translating committed) is
         * allowed ONLY after gate 7.5 — restart/LRU rehydrate with ZERO
         * [TextLayoutPlanner][eu.kanade.translation.rendering.TextLayoutPlanner]
         * invocations — has passed ON DEVICE for Pager AND Webtoon (evidence
         * rows owed per `evidence/stage2/wp9-report.md` §6 + Stage-7 device
         * evidence). It MUST remain `false` in this slice: runs complete under
         * the legacy semantics regardless of hydration coverage.
         */
        const val GATE_7_8_DISPLAY_READY_COMPLETION_ENABLED = false

        /** Stage-7 typed counters (operational only, never fingerprinted). */
        const val COUNTER_FINALIZE = "finalizeEntered"
        const val COUNTER_RUN_COMPLETE = "runComplete"
        const val COUNTER_LAYOUTS_PUBLISHED = "layoutPlansPublished"
        const val COUNTER_STRANDED_RECONCILED = "strandedPagesReconciled"

        /**
         * T934 display-tail drain counters: pages whose committed display was
         * produced by the FINALIZE drain, and pages left without one (typed
         * terminal, run completes as a warning). Pages left pending on
         * publication rejections count in neither bucket.
         */
        const val COUNTER_DISPLAY_TAIL_DRAINED = "displayTailDrained"
        const val COUNTER_DISPLAY_TAIL_FAILED = "displayTailFailed"

        /**
         * T934 display-tail drain bound: the first pass retries the overlap
         * scheduler's single orphan sweep (a stale-write rejection heals on a
         * fresh snapshot); the third exists so one transient rejection never
         * typed-fails a healthy page. A MANUAL Render owner persists across
         * all passes by design — that page takes the typed terminal.
         */
        const val MAX_DISPLAY_TAIL_DRAIN_PASSES = 3

        /**
         * The guarded page write's generic whole-publication rejection reason
         * ([ChapterTranslationStore.updatePageGuarded] maps every internal
         * publish failure to it). The drain's classification boundary: a stamp
         * rejected with it is a HEALTHY page left pending, never a typed
         * failure.
         */
        private const val REJECTED_ARTIFACT_PUBLICATION = "ARTIFACT_PUBLICATION_FAILED"

        /**
         * Analysis output budget (T924-AP-03 `outputBudget`): free-form
         * chunk summaries are ~120 words, so the reservation is small and
         * the input side keeps the 8k window. Mirrors
         * [eu.kanade.translation.translator.analysis.AnalysisEngineTransport
         * .ANALYSIS_MAX_OUTPUT_TOKENS].
         */
        const val ANALYSIS_MAX_OUTPUT_TOKENS = 512

        /** FF-01d flag state, frozen as an operational (never fingerprinted) counter. */
        const val COUNTER_FLAG = "flagProfilePipeline"
        const val COUNTER_TOTAL = "ocrPagesTotal"
        const val COUNTER_DONE = "ocrPagesDone"
        const val COUNTER_REUSED = "ocrPagesReused"
        const val COUNTER_STOP = "preflightStop"
        const val COUNTER_GAPS = "preflightCheckpointGaps"

        /**
         * T934 R2a.5: typed checkpoint-adoption failures. The aggregate is
         * emitted only when nonzero, alongside one bounded `ocrAdopt<Reason>`
         * key per observed reason ([CheckpointAdoptionFailure.counterKey]) —
         * appended AFTER the fixed keys so the publishRecord over-bound trim
         * can never drop the phase-critical `ocrPages*` keys.
         */
        const val COUNTER_ADOPT_FAILED = "ocrPagesAdoptFailed"
        const val COUNTER_ANALYSIS_PLAN = "analysisPlanPublished"
        const val COUNTER_CHUNKS_TOTAL = "analysisChunksTotal"
        const val COUNTER_CHUNKS_DONE = "analysisChunksDone"
        const val COUNTER_CHUNKS_PENDING = "analysisChunksPending"
        const val COUNTER_CHUNKS_FAILURES = "analysisChunkFailures"
        const val COUNTER_SKIPPED_NO_WORK = "analysisSkippedNoWork"
        const val COUNTER_SKIPPED_NO_TRANSPORT = "analysisSkippedNoTransport"

        /** Stage-5 slice B typed counters (operational only, never fingerprinted). */
        const val COUNTER_PROFILE_CHUNKS_TOTAL = "profileChunksTotal"
        const val COUNTER_PROFILE_CHUNKS_RECONCILED = "profileChunksReconciled"
        const val COUNTER_PROFILE_CHUNKS_PENDING = "profileChunksPending"
        const val COUNTER_PROFILE_FROZEN = "profileFrozen"
        const val COUNTER_PROFILE_REUSED = "profileReused"
        const val COUNTER_SERIES_PROFILE_CARRIED_OVER = "seriesProfileCarriedOver"
        const val COUNTER_PROFILE_RECONCILE_REJECTED = "profileReconcileRejected"
        const val COUNTER_PROFILE_FREEZE_REJECTED = "profileFreezeRejected"

        /** Stage-6 slice A typed counters (operational only, never fingerprinted). */
        const val COUNTER_ENVELOPES_TOTAL = "envelopesTotal"
        const val COUNTER_ENVELOPES_DONE = "envelopesDone"
        const val COUNTER_ENVELOPES_PENDING = "envelopesPending"
        const val COUNTER_ENVELOPES_FAILURES = "envelopeFailures"
        const val COUNTER_ENVELOPES_SKIPPED = "envelopesSkipped"
        const val COUNTER_PAGES_TRANSLATED = "pagesTranslated"
        const val COUNTER_ENVELOPE_REPLANS = "envelopeReplans"
        const val COUNTER_ENVELOPE_PLAN_REJECTED = "envelopePlanRejected"

        /** Matches the planner's oversized-page reason prefix ("page <key> oversized: ..."). */
        private val oversizedPageRegex = Regex("""^page (\S+) oversized""")

        /**
         * T924-FF-01a dispatch decision, zero-legacy form (D1): the FF-01 A/B
         * flag completed its lifecycle and was removed — the engine category
         * alone picks the lane at the `BatchChapterTranslator` coordinator
         * construction; this pure function carries the mapping.
         *
         * A STANDARD engine dispatches STANDARD_PIPELINE (the same
         * coordinator, standard tail); every other engine (AI_MODEL,
         * contextual or not) dispatches PROFILE_PIPELINE — the degenerate
         * non-contextual AI config takes the coordinator's typed
         * CONFIGURATION pause at the envelope seam.
         */
        fun dispatchKind(
            engineCategoryIsStandard: Boolean = false,
        ): BatchCoordinatorKind =
            if (engineCategoryIsStandard) {
                BatchCoordinatorKind.STANDARD_PIPELINE
            } else {
                BatchCoordinatorKind.PROFILE_PIPELINE
            }

        /**
         * RUN_SNAPSHOT freeze (ST-03). Identity values that have no stable
         * engine accessor yet are pinned to explicit shell placeholders —
         * recorded, stable, and never silently empty.
         *
         * The `flagProfilePipeline` snapshot field keeps its schema position
         * and fingerprint basis, but the parameter is gone: the FF-01 A/B
         * flag completed its lifecycle and the field is hardcoded `true`
         * (flag-ON records — including the A/B evidence records — keep
         * matching fingerprints and resume correctly). A leftover pref key in
         * a device DataStore is a harmless orphan.
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
            flagProfilePipeline = true,
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
                is ChapterTranslationStore.PatchResult.Rejected -> {
                    // T934: the run is already failing — the durable RECORD of
                    // that failure is best-effort and must never escalate or
                    // throw. Log WARN and continue so the ORIGINAL failure
                    // surfaces cleanly (what counts as a run failure is
                    // unchanged); [recordPageFailure]'s catch remains as
                    // defense in depth for the whole recorder seam.
                    logcat(LogPriority.WARN) {
                        "TachiyomiAT t924 preflight durable failure record rejected " +
                            "pageHash=${pageHash(failure.pageKey)} reason=${result.reason}"
                    }
                }
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
