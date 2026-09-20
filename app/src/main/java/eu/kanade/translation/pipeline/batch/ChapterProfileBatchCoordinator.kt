package eu.kanade.translation.pipeline.batch

import eu.kanade.translation.storage.ChapterTranslationStore
import eu.kanade.translation.storage.CheckpointOcrResult
import eu.kanade.translation.pipeline.LeaseAcquisition
import eu.kanade.translation.pipeline.OcrStagePatch
import eu.kanade.translation.context.SeriesProfileRegistry
import eu.kanade.translation.model.PageStage
import eu.kanade.translation.pipeline.PageWriteOrigin
import eu.kanade.translation.pipeline.StagePatchResult
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
import eu.kanade.translation.pipeline.ocrBlockFingerprints
import eu.kanade.translation.pipeline.ocrFingerprint
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
 * Coordinates durable batch translation for the AI and standard lanes.
 *
 * The AI lane advances from OCR_PREFLIGHT through analysis planning, chunk
 * publication, profile reconciliation, profile freeze, and envelope
 * translation. The standard lane shares OCR preflight and then performs
 * ordered per-page translation through the injected seam.
 *
 * OCR preflight admits one decoded page at a time, releases the native OCR
 * handoff before admitting the next page, and releases the page lease only
 * after its checkpoint is committed. It performs no inpaint, translation, or
 * display promotion. Provider calls are gated by the batch sub-limit and
 * shared provider bucket; standard translation also uses the overlap window
 * so native inpaint never overlaps translation.
 *
 * Resume reuses content-identity OCR checkpoints or persisted analysis
 * chunks without re-sending completed work. A compatible frozen profile can
 * skip the run through analysis without OCR or provider calls. The run record
 * is published at start and advanced at phase transitions and chunk
 * completion; durable manifest checkpoints and analysis pointers remain
 * authoritative.
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
 * Typed reason a page's durable checkpoint could not back
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

/** Typed outcome of the checkpoint-reuse probe for one page. */
internal sealed interface CheckpointReuse {
    /** The checkpoint's OCR content fingerprint; source identity was proven. */
    data class Reusable(val ocrContentFingerprint: String) : CheckpointReuse

    /** Why the checkpoint cannot back this run (typed; the page re-runs — fail closed). */
    data class Unavailable(val failure: CheckpointAdoptionFailure) : CheckpointReuse
}

/** Typed outcome of one checkpoint-adoption (hydration) attempt. */
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
    /** Releases the BATCH page lease strictly after the checkpoint. */
    private val releaseBatchLease: suspend (String) -> Unit,
    private val listener: BatchScheduleListener = BatchScheduleListener.NOOP,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
    /**
     * Wave-2 review R2: the durable failure-ledger writer for an unresolved
     * preflight page. The DEFAULT writer mirrors the legacy
     * `persistUnexpectedBatchStageFailure` idiom: the
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
     * Typed AI text translator for the envelope phase. `null` is a typed
     * CONFIGURATION-class gate: the run
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
     * Optional inpaint-overlap scheduler. When present, each provider
     * envelope window drives serial inpaint of committed pages and FINALIZE
     * drains the remaining pages serially. `null` keeps the asynchronous
     * planner fallback and the pre-overlap completion semantics.
     */
    private val overlapScheduler: OverlapScheduler? = null,
    /**
     * Optional render join for persisted-layout publication. Inpaint-committed
     * pages publish their draw plan through it, and FINALIZE sweeps pages the
     * overlap hook missed. `null` keeps the async planner fallback.
     */
    private val renderJoin: BatchRenderJoin? = null,
    /**
     * STANDARD-engine lane discriminator. `false`
     * (default) preserves the AI coordinator behavior exactly; `true` runs
     * the same OCR preflight and then — instead of the AI
     * analysis/profile/envelope phases — the per-page standard translate
     * tail ([runStandardTranslateAndFinalize]) and the shared FINALIZE.
     */
    private val standardLane: Boolean = false,
    /**
     * Typed standard translate seam, injected by
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
     * Per-page source digests recorded durably at first
     * admission ([ChapterArtifactManifest.sourceShaByPageKey], stamped by the
     * checkpoint transaction). Read once at run start and held stable for the
     * whole run, exactly like the constructor pairs.
     */
    private val recordedSourceShaByPageKey: Map<String, String> by lazy {
        store.artifactManifest?.sourceShaByPageKey.orEmpty()
    }

    /**
     * Per-page admission identity — the recorded digest wins
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
     * The run's ordered (pageKey, admission sha) pairs — the
     * SINGLE orderedSourceDigest input for every record of this run. Derived
     * from the RECORDED digests wherever they exist, so run-start identity no
     * longer re-reads page bytes and a dispatch hash failure can no longer
     * break run identity continuity across a resume. Evaluated once (the same
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
    private fun preflightWorker() = PreflightWorker(
        PreflightWorkerContext(
            store = store,
            nativeWorker = nativeWorker,
            frozenConfig = frozenConfig,
            effectiveSourcePairs = effectiveSourcePairs,
            releaseBatchLease = releaseBatchLease,
            listener = listener,
            nowEpochMs = nowEpochMs,
            failureRecorder = { failure -> recordPageFailure(failure) },
            standardLane = standardLane,
            seriesKey = seriesKey,
            publishRecord = { recordArtifact, runRecord ->
                publishRecord(recordArtifact, runRecord)
            },
            record = { id, state, fingerprint, digest, counters, ocrFingerprint, profile ->
                record(id, state, fingerprint, digest, counters, ocrFingerprint, profile)
            },
            admissionSourceSha = { pageKey -> admissionSourceSha(pageKey) },
            orientationOf = { snapshot -> orientationOf(snapshot) },
            resumeFinalizeOrComplete = { recordArtifact, prior, fingerprint, digest, pages ->
                resumeFinalizeOrComplete(recordArtifact, prior, fingerprint, digest, pages)
            },
            runEnvelopePlanAndTranslate = { recordArtifact, id, pages, fingerprint, counters ->
                runEnvelopePlanAndTranslate(recordArtifact, id, pages, fingerprint, counters)
            },
            runStandardTranslateAndFinalize = { recordArtifact, id, pages, fingerprint, counters ->
                runStandardTranslateAndFinalize(recordArtifact, id, pages, fingerprint, counters)
            },
            runAnalysisPhase = { recordArtifact, id, pages, fingerprint, counters ->
                runAnalysisPhase(recordArtifact, id, pages, fingerprint, counters)
            },
            adoptCheckpointSnapshot = { recordArtifact, pageKey, before ->
                adoptCheckpointSnapshot(recordArtifact, pageKey, before)
            },
            stampAdoptedRenderTerminal = { pageKey ->
                stampAdoptedRenderTerminal(pageKey)
            },
            profileInputFingerprintOf = { fingerprint ->
                profileInputFingerprintOf(fingerprint)
            },
        ),
    )

    suspend fun runPass1(
        orderedPages: List<PageKey>,
        computeClass: TranslatorComputeClass,
    ): BatchPass1Outcome = preflightWorker().runPhase(orderedPages, computeClass)

    /**
     * Runs analysis from a recomputed OCR corpus plan and durable chunk
     * checkpoints. Resume skips the persisted prefix and never re-sends a
     * validated chunk. Typed failures pause at the first missing chunk so a
     * later run can continue without weakening the durable prefix; this phase
     * does not publish [ChapterRunState.COMPLETE].
     */
    private suspend fun runAnalysisPhase(
        artifact: ChapterArtifactEngine,
        runId: String,
        orderedPages: List<PageKey>,
        corpusFingerprint: String,
        baseCounters: Map<String, Int>,
    ): BatchPass1Outcome = AnalysisWorker(
        AnalysisWorkerContext(
            store = store,
            frozenConfig = frozenConfig,
            effectiveSourcePairs = effectiveSourcePairs,
            analysisChunkRunner = analysisChunkRunner,
            nowEpochMs = nowEpochMs,
            publishRecord = { recordArtifact, runRecord ->
                publishRecord(recordArtifact, runRecord)
            },
            record = { id, state, fingerprint, digest, counters, ocrFingerprint, profile ->
                record(id, state, fingerprint, digest, counters, ocrFingerprint, profile)
            },
            corpusEntriesFromCheckpoints = { recordArtifact, pages, expectedCount ->
                corpusEntriesFromCheckpoints(recordArtifact, pages, expectedCount)
            },
            validatePersistedPrefix = { recordArtifact, chunks ->
                validatePersistedPrefix(recordArtifact, chunks)
            },
            runProfileReconcileAndFreeze = { recordArtifact, id, pages, fingerprint, counters ->
                runProfileReconcileAndFreeze(recordArtifact, id, pages, fingerprint, counters)
            },
        ),
    ).runPhase(
        artifact = artifact,
        runId = runId,
        orderedPages = orderedPages,
        corpusFingerprint = corpusFingerprint,
        baseCounters = baseCounters,
    )

    /**
     * Re-reads durable chunks, reconciles their profile content, and freezes
     * the resulting profile with one atomic publication. Invalid or missing
     * sidecars cause a typed pause rather than a partial reconcile. A freeze
     * publishes the profile pointer and leaves the run paused for the envelope
     * and translation phases; this method does not publish COMPLETE.
     */
    private suspend fun runProfileReconcileAndFreeze(
        artifact: ChapterArtifactEngine,
        runId: String,
        orderedPages: List<PageKey>,
        corpusFingerprint: String,
        baseCounters: Map<String, Int>,
    ): BatchPass1Outcome = ProfileReconciler(
        ProfileReconcilerContext(
            store = store,
            frozenConfig = frozenConfig,
            effectiveSourcePairs = effectiveSourcePairs,
            glossarySynthesizer = glossarySynthesizer,
            seriesKey = seriesKey,
            nowEpochMs = nowEpochMs,
            publishRecord = { recordArtifact, runRecord ->
                publishRecord(recordArtifact, runRecord)
            },
            record = { id, state, fingerprint, digest, counters, ocrFingerprint, profile ->
                record(id, state, fingerprint, digest, counters, ocrFingerprint, profile)
            },
            profileInputFingerprintOf = { fingerprint ->
                profileInputFingerprintOf(fingerprint)
            },
            runEnvelopePlanAndTranslate = { recordArtifact, id, pages, fingerprint, counters ->
                runEnvelopePlanAndTranslate(recordArtifact, id, pages, fingerprint, counters)
            },
        ),
    ).runPhase(
        artifact = artifact,
        runId = runId,
        orderedPages = orderedPages,
        corpusFingerprint = corpusFingerprint,
        baseCounters = baseCounters,
    )

    /**
     * Rebuilds envelope work from durable checkpoints and live page state,
     * excluding committed, skipped, manual-authoritative, and user-edited
     * pages. The deterministic plan is reused when its fingerprint is still
     * valid; otherwise the replacement sidecar and manifest pointer publish in
     * one transaction. Translation dispatch is serial, provider-gated, and
     * page-revalidated; completion remains paused until finalization.
     */
    private suspend fun runEnvelopePlanAndTranslate(
        artifact: ChapterArtifactEngine,
        runId: String,
        orderedPages: List<PageKey>,
        corpusFingerprint: String,
        baseCounters: Map<String, Int>,
    ): BatchPass1Outcome = EnvelopeDispatcher(
        EnvelopeDispatcherContext(
            store = store,
            frozenConfig = frozenConfig,
            effectiveSourcePairs = effectiveSourcePairs,
            listener = listener,
            textTranslator = textTranslator,
            translationSublimitGate = translationSublimitGate,
            overlapScheduler = overlapScheduler,
            renderJoin = renderJoin,
            envelopePlannerPolicy = envelopePlannerPolicy,
            nowEpochMs = nowEpochMs,
            publishRecord = { recordArtifact, runRecord ->
                publishRecord(recordArtifact, runRecord)
            },
            record = { id, state, fingerprint, digest, counters, ocrFingerprint, profile ->
                record(id, state, fingerprint, digest, counters, ocrFingerprint, profile)
            },
            profileInputFingerprintOf = { fingerprint ->
                profileInputFingerprintOf(fingerprint)
            },
            buildEnvelopeDispatchWork = { recordArtifact, pages, fingerprint ->
                buildEnvelopeDispatchWork(recordArtifact, pages, fingerprint)
            },
            rebuildDispatchWork = { recordArtifact, pages, fingerprint, reason ->
                rebuildDispatchWork(recordArtifact, pages, fingerprint, reason)
            },
            persistEnvelopeStructuralFailure = { pageKey, reason ->
                persistEnvelopeStructuralFailure(pageKey, reason)
            },
            providerChunkProfile = {
                providerChunkProfile()
            },
            runFinalizeAndComplete = { recordArtifact, id, pages, fingerprint, counters ->
                runFinalizeAndComplete(recordArtifact, id, pages, fingerprint, counters)
            },
        ),
    ).runPhase(
        artifact = artifact,
        runId = runId,
        orderedPages = orderedPages,
        corpusFingerprint = corpusFingerprint,
        baseCounters = baseCounters,
    )

    /**
     * Builds the recovery/finalization worker used after translation drains.
     * Finalization records its phase, drains overlap inpaint and pending
     * display work, publishes any missing layout plans, reconciles stranded
     * pages into durable failures, flushes under NonCancellable, and publishes
     * the single COMPLETE run record. Display-ready semantics remain separate
     * from translation-terminal completion.
     */
    private fun recoveryWorker() = RecoveryWorker(
        RecoveryWorkerContext(
            store = store,
            nowEpochMs = nowEpochMs,
            drainFinalize = { recordArtifact, id, pages, fingerprint, counters ->
                drainFinalizeAndComplete(recordArtifact, id, pages, fingerprint, counters)
            },
        ),
    )

    private fun finalizeWorker() = FinalizeWorker(
        FinalizeWorkerContext(
            store = store,
            frozenConfig = frozenConfig,
            effectiveSourcePairs = effectiveSourcePairs,
            overlapScheduler = overlapScheduler,
            renderJoin = renderJoin,
            publishRecord = { recordArtifact, runRecord ->
                publishRecord(recordArtifact, runRecord)
            },
            record = { id, state, fingerprint, digest, counters, ocrFingerprint, profile ->
                record(id, state, fingerprint, digest, counters, ocrFingerprint, profile)
            },
            drainDisplayTailBeforeComplete = { pageKeys ->
                drainDisplayTailBeforeComplete(pageKeys)
            },
            t924PageTerminalAtFinalize = { page, generation ->
                t924PageTerminalAtFinalize(page, generation)
            },
            strandedPageReason = { page -> strandedPageReason(page) },
            persistEnvelopeStructuralFailure = { pageKey, reason, carrier ->
                persistEnvelopeStructuralFailure(pageKey, reason, carrier)
            },
        ),
    )

    private suspend fun runFinalizeAndComplete(
        artifact: ChapterArtifactEngine,
        runId: String,
        orderedPages: List<PageKey>,
        corpusFingerprint: String,
        baseCounters: Map<String, Int>,
    ): BatchPass1Outcome = finalizeWorker().runPhase(
        artifact = artifact,
        runId = runId,
        orderedPages = orderedPages,
        corpusFingerprint = corpusFingerprint,
        baseCounters = baseCounters,
    )

    private suspend fun drainFinalizeAndComplete(
        artifact: ChapterArtifactEngine,
        runId: String,
        orderedPages: List<PageKey>,
        corpusFingerprint: String,
        baseCounters: Map<String, Int>,
    ): BatchPass1Outcome = finalizeWorker().drainPhase(
        artifact = artifact,
        runId = runId,
        orderedPages = orderedPages,
        corpusFingerprint = corpusFingerprint,
        baseCounters = baseCounters,
    )

    private suspend fun runStandardTranslateAndFinalize(
        artifact: ChapterArtifactEngine,
        runId: String,
        orderedPages: List<PageKey>,
        corpusFingerprint: String?,
        baseCounters: Map<String, Int>,
        hasGaps: Boolean = false,
    ): BatchPass1Outcome = StandardLaneWorker(
        StandardLaneWorkerContext(
            store = store,
            frozenConfig = frozenConfig,
            effectiveSourcePairs = effectiveSourcePairs,
            standardTranslateOutcome = standardTranslateOutcome,
            overlapScheduler = overlapScheduler,
            renderJoin = renderJoin,
            publishRecord = { recordArtifact, runRecord ->
                publishRecord(recordArtifact, runRecord)
            },
            record = { id, state, fingerprint, digest, counters, ocrFingerprint, profile ->
                record(id, state, fingerprint, digest, counters, ocrFingerprint, profile)
            },
            runFinalizeAndComplete = { recordArtifact, id, pages, fingerprint, counters ->
                runFinalizeAndComplete(recordArtifact, id, pages, fingerprint, counters)
            },
        ),
    ).runPhase(
        artifact = artifact,
        runId = runId,
        orderedPages = orderedPages,
        corpusFingerprint = corpusFingerprint,
        baseCounters = baseCounters,
        hasGaps = hasGaps,
    )

    /**
     * FINALIZE resume gates for a
     * durable record already past TRANSLATE, consulted at dispatch entry
     * BEFORE any RUN_SNAPSHOT republication:
     *
     *  - `FINALIZE`: a process death after the FINALIZE
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
     *    The shell-level OFF+COMPLETE decision tree is gone; this path is the
     *    only COMPLETE-resume route
     *    for both lanes.
     *
     *  The COMPLETE work-product evidence gate: the `COMPLETE` fast path
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
     * A mismatch starts a new run exactly as before, or the recorded COMPLETE
     * lacks per-page display evidence.
     */
    private suspend fun resumeFinalizeOrComplete(
        artifact: ChapterArtifactEngine,
        priorRecord: ChapterRunRecord?,
        frozenFingerprint: String,
        sourceDigest: String,
        orderedPages: List<PageKey>,
    ): BatchPass1Outcome? = recoveryWorker().resumePhase(
        artifact = artifact,
        priorRecord = priorRecord,
        frozenFingerprint = frozenFingerprint,
        sourceDigest = sourceDigest,
        orderedPages = orderedPages,
    )

    private suspend fun pageWorkProductResolvable(
        artifact: ChapterArtifactEngine,
        pageKey: String,
        durableManifest: ChapterArtifactManifest?,
    ): Boolean = recoveryWorker().pageWorkProductResolvable(artifact, pageKey, durableManifest)

    private fun t924PageTerminalAtFinalize(
        page: PageTranslation?,
        activeGeneration: Long,
    ): Boolean = recoveryWorker().t924PageTerminalAtFinalize(page, activeGeneration)

    private suspend fun stampAdoptedRenderTerminal(pageKey: String) =
        recoveryWorker().stampAdoptedRenderTerminal(pageKey)

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
                    //   the identical plan is already durable — the
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
                            //   the superseding plan committed — the
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
    internal sealed interface EnvelopeWorkBuild {
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
     * Rebuilds pending dispatch work from fresh store state. Durable
     * checkpoints provide the OCR
     * corpus identity; the live store provides the plan-time page identities
     * state revalidates against. Committed/skipped pages, manual-authoritative
     * pages, user-edited blocks and already-translated blocks are never
     * planned — durable progress is the STORE's per-page translation state,
     * never a coordinator list.
     */
    private suspend fun buildEnvelopeDispatchWork(
        artifact: ChapterArtifactEngine,
        orderedPages: List<PageKey>,
        corpusFingerprint: String,
    ): EnvelopeWorkBuild {
        // Announce the rebuild window before the silent work starts.
        // — this build used to run for MINUTES with zero progress events (the
        // progress sheet sat frozen on a stale snapshot). The listener maps
        // this to a tracker emission; the tracker flips the snapshot's batch
        // phase to REBUILDING and recomputes its store-derived counters.
        listener.envelopePlanStarted(orderedPages.size)
        // The resume-hydration adoption below (adoptCheckpointSnapshot
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
        // depends on the window and its one-shot stale-manifest retry.
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
        //   per-page progress for the (potentially minutes-long)
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
            // planning, revalidation, and commits all operate on
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
                // User-edited blocks are authoritative and are never planned.
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
                //  stranded-page fix: a non-terminal page with NO
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

    /** Whole-page done rule: committed, skipped, rendered, or manual-authoritative. */
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
     * Stranded-page fix: explicit terminal routing for a page that is
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
     * TYPED reason — the caller defers the phase or re-runs the
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
     * Terminal predicate companion: the SPECIFIC reason a non-terminal
     * page was stranded at FINALIZE. Names the page's actual work state so
     * the durable failure (and the progress sheet's failure groups) carries
     * something actionable instead of a bare stage status.
     */
    private suspend fun drainDisplayTailBeforeComplete(
        orderedPageKeys: List<String>,
    ): RecoveryWorker.DisplayTailDrain = recoveryWorker().drainDisplayTailBeforeComplete(orderedPageKeys)

    private fun strandedPageReason(page: PageTranslation?): String =
        recoveryWorker().strandedPageReason(page)

    private suspend fun persistEnvelopeStructuralFailure(
        pageKey: String,
        reason: String,
        carrier: String = "envelope planner rejected the page",
    ) = recoveryWorker().persistEnvelopeStructuralFailure(pageKey, reason, carrier)

    /**
     * The profile input fingerprint for this run is computed with the SAME
     * policy-fingerprint
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
     * The resume prefix is only valid when the persisted
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
                // raw OCR text (analysis under-8k dispatch contract).
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
    internal class AnalysisCorpusEntry(
        val storagePageKey: String,
        val naturalPageIndex: Int,
        val contentFingerprint: String,
        val snapshotPointer: SidecarPointer,
        val wirePageKey: String,
        val wireBlockIds: List<String>,
        val blockTexts: List<String>,
        val estimatedInputTokens: Int,
    )

    internal class AnalysisCorpus(
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
     * checkpoint attempt, strictly before the `finally` release.
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
     * Best-effort run-record publication. The record is identity/progress
     * state; per-page checkpoints in the manifest are the authoritative
     * durable state, so a rejected publication never fails the
     * preflight — it only loses advisory progress.
     *
     * Returns the publication outcome so callers that publish a NON-advisory
     * record can act on it: the run-closure COMPLETE in
     * [drainFinalizeAndComplete] MUST inspect it (a rejected closure leaves
     * the run durably at FINALIZE — reporting finished would desynchronize
     * the shell from the record), while the advisory preflight callers may
     * ignore the return value.
     *
     * The phase pointer itself MUST never be lost to the
     * phaseCounters bound (32 keys): 's executor counters re-blew the
     * wave-7b budget, silently dropping the  FINALIZE record and with
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
        /**  chapter-profile coordinator — the AI-model lane. */
        PROFILE_PIPELINE,

        /**
         *  Phase 4 Wave A: the STANDARD-engine lane — the same
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
        internal const val PREFLIGHT_PAGE_TIMEOUT_MS = 240_000L

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
         * acquisition, and an already-terminal page is never re-paid (
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
         * The standard lane's typed CONFIGURATION pause —
         * the coordinator was constructed with [ChapterProfileBatchCoordinator.standardLane]
         * but no [ChapterProfileBatchCoordinator.standardTranslateOutcome]
         * seam. Same discipline as [ANALYSIS_NO_TRANSPORT_REASON]: never run
         * provider-bound work without a typed transport.
         */
        const val STANDARD_NO_SEAM_REASON =
            "T924 standard translation paused: no typed standard translate seam wired (CONFIGURATION gate)"

        /**
         * The terminal of a drained run. The completion
         * semantics are the LEGACY translation-committed ones — the
         * DISPLAY_READY redefinition below is still gate-7.8-gated OFF.
         */
        const val TRANSLATE_COMPLETE_REASON =
            "T924 run complete: every page reached its durable terminal state " +
                "(legacy completion semantics; DISPLAY_READY redefinition is gate-7.8-gated OFF)"

        /**
         * COMPLETE resume: the durable record already reads COMPLETE — the
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
         * the run, and a later dispatch re-enters the  FINALIZE resume
         * ([resumeFinalizeOrComplete] → [drainFinalizeAndComplete]), which
         * re-attempts the run's single COMPLETE publication.
         */
        const val RUN_CLOSURE_REJECTED_REASON =
            "T924 run-closure COMPLETE publication rejected; run stays at FINALIZE (ST-14 resume re-attempts closure)"

        /**
         * Gate 7.8 ENCODED GATE (never activated on device-gated
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
          * Display-tail drain counters: pages whose committed display was
         * produced by the FINALIZE drain, and pages left without one (typed
         * terminal, run completes as a warning). Pages left pending on
         * publication rejections count in neither bucket.
         */
        const val COUNTER_DISPLAY_TAIL_DRAINED = "displayTailDrained"
        const val COUNTER_DISPLAY_TAIL_FAILED = "displayTailFailed"

        /**
          * Display-tail drain bound: the first pass retries the overlap
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
        internal const val REJECTED_ARTIFACT_PUBLICATION = "ARTIFACT_PUBLICATION_FAILED"

        /**
          * Analysis output budget (`outputBudget`): free-form
         * chunk summaries are ~120 words, so the reservation is small and
         * the input side keeps the 8k window. Mirrors
         * [eu.kanade.translation.translator.analysis.AnalysisEngineTransport
         * .ANALYSIS_MAX_OUTPUT_TOKENS].
         */
        const val ANALYSIS_MAX_OUTPUT_TOKENS = 512

        /** Feature-flag state, frozen as an operational (never fingerprinted) counter. */
        const val COUNTER_FLAG = "flagProfilePipeline"
        const val COUNTER_TOTAL = "ocrPagesTotal"
        const val COUNTER_DONE = "ocrPagesDone"
        const val COUNTER_REUSED = "ocrPagesReused"
        const val COUNTER_STOP = "preflightStop"
        const val COUNTER_GAPS = "preflightCheckpointGaps"

        /**
         * Typed checkpoint-adoption failures. The aggregate is
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
         * Dispatch decision: the profile pipeline is selected by engine
         * category; the old A/B flag is no longer consulted. The engine category
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
         * RUN_SNAPSHOT freeze. Identity values that have no stable
         * engine accessor yet are pinned to explicit shell placeholders —
         * recorded, stable, and never silently empty.
         *
         * The `flagProfilePipeline` snapshot field keeps its schema position
         * and fingerprint basis, but the parameter is gone: the  A/B
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
                    //  the run is already failing — the durable RECORD of
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

        /** style length-prefixed hash over the canonical config JSON. */
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
