package eu.kanade.translation.pipeline.batch

import eu.kanade.translation.diagnostics.BatchDiagnosticStage
import eu.kanade.translation.engines.translator.ProviderFailure
import eu.kanade.translation.pipeline.execution.TranslationCompletionOutcome
import eu.kanade.translation.pipeline.execution.TranslationStageEvent

/**
 * Shared worker, progress-listener, and result contracts for batch execution.
 */

/** Natural-order page identity retained by the live sequential coordinator. */
typealias PageKey = Pair<String, Int>

data class OcrReadyPageRef(
    val pageKey: String,
    val pageIndex: Int,
    val generation: Long,
    val blockFingerprints: List<String>,
    /** Exact batch lease token held across the translation boundary. */
    val leaseToken: Long? = null,
    /** Artifact candidate generation observed at OCR publication. */
    val candidateGenerationId: String? = null,
    /** Artifact dependency fingerprint observed at OCR publication. */
    val dependencyFingerprint: String? = null,
    /** Artifact-manifest page version observed at OCR publication. */
    val artifactPageVersion: Long? = null,
    /**
     * Ephemeral native-lane value owned by [NativeLaneWorker]. It is only valid
     * until that worker receives [NativeLaneWorker.releaseNativeHandoff].
     */
    val nativeHandoff: Any? = null,
)

interface NativeLaneWorker {
    /**
     * Decode + OCR + persist OCR results. Returns the reference for translation,
     * or null if the page should be skipped. A returned reference may carry one
     * page-local native handoff for the immediately following inpaint stage.
     */
    suspend fun runOcrStage(pageKey: String, pageIndex: Int): OcrReadyPageRef?

    /**
     * Run inpaint on the page. The default bridge preserves workers that do not
     * have a native handoff.
     */
    suspend fun runInpaintStage(pageKey: String)

    suspend fun runInpaintStage(pageKey: String, nativeHandoff: Any?) {
        runInpaintStage(pageKey)
    }

    /** Releases a native handoff after inpaint or cancellation. */
    fun releaseNativeHandoff(ref: OcrReadyPageRef) {}
}

interface TranslatorLaneWorker {
    /**
     * Translate one page's Pass-1 work item.
     */
    suspend fun translate(ref: OcrReadyPageRef)

    /**
     * Typed translation entry point used by the coordinator. Existing workers
     * may keep implementing [translate]; provider-aware workers can override
     * this bridge to return a retryable pause without throwing it through the
     * lane. Unexpected exceptions still reach the coordinator's failure path.
     */
    suspend fun translateOutcome(ref: OcrReadyPageRef): TranslationCompletionOutcome =
        try {
            translate(ref)
            TranslationCompletionOutcome.Completed(setOf(ref.pageKey))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: eu.kanade.translation.engines.translator.ProviderFailureException) {
            e.toTranslationCompletionOutcome(ref.pageKey)
        }
}

/** Result of the chapter batch coordinator's first pass. */
enum class BatchPass1Status {
    COMPLETED,
    PAUSED,
    FAILED,

    /**
     * A stage result could not be published under its guarded precondition.
     * This is deliberately distinct from [FAILED]: no durable failure exists
     * to justify a terminal chapter error or a durable pause projection.
     */
    PERSISTENCE_REJECTED,
}

/** Result of the chapter batch coordinator's first pass. */
data class BatchPass1Outcome(
    val needsTranslation: List<String>,
    val status: BatchPass1Status = BatchPass1Status.COMPLETED,
    val anchorPageKey: String? = null,
    val completedPageKeys: Set<String> = emptySet(),
    val retryablePageKeys: Set<String> = emptySet(),
    val terminalPageKeys: Set<String> = emptySet(),
    val failure: ProviderFailure? = null,
    val nextEligibleRetryAtEpochMs: Long? = null,
    val reason: String? = null,
    val unexpectedStage: BatchDiagnosticStage? = null,
    val persistenceRejectedStage: BatchDiagnosticStage? = null,
) {
    val isPaused: Boolean get() = status == BatchPass1Status.PAUSED
    val isPersistenceRejected: Boolean get() = status == BatchPass1Status.PERSISTENCE_REJECTED
}

/**
 * Guarded stage publication failed after a worker had already produced an
 * in-memory result. The marker is shared with the coordinator so a rejected
 * write cannot be reclassified as an unexpected terminal stage failure.
 */
internal open class BatchPersistenceRejectedException(
    val pageKey: String? = null,
    val stage: BatchDiagnosticStage? = null,
) : IllegalStateException("Batch persistence publication rejected")

/**
 * The stage's guarded publication lost a PRECONDITION race with a concurrent
 * writer on the same page (typically the reader's live translate-on-view lane
 * committing blocks while the batch drain publishes) and a fresh-snapshot
 * retry was rejected too. This is CONTENTION, not corruption: the concurrent
 * owner's committed outcome is authoritative, so the worker yields the page —
 * the scheduler defers it for the rest of the pass and a later run (or the
 * owner itself) reconciles it. Retrying instead livelocks the drain.
 */
internal class BatchContentionRejectedException(
    yieldPageKey: String? = null,
    stage: BatchDiagnosticStage? = null,
) : BatchPersistenceRejectedException(yieldPageKey, stage)

open class BatchScheduleListener {
    open fun ocrStarted(pageKey: String) {}
    open fun ocrFinished(pageKey: String) {}
    open fun ocrPublished(pageKey: String) {}

    /**
     *   the coordinator began rebuilding the envelope dispatch work
     * (resume hydration / plan re-derivation). [totalPages] is the ordered
     * page-set size the rebuild iterates. The window used to run silently for
     * minutes with zero progress events, freezing the progress sheet on a
     * stale snapshot. No-op default so unaffected listeners stay no-ops.
     */
    open fun envelopePlanStarted(totalPages: Int) {}

    /**
     *   per-page progress within the envelope plan-build window.
     * [done] counts corpus entries processed so far out of [total]. Fired
     * every page; the tracker side is a Channel trySend plus a projection
     * recompute, deliberately trivial per call.
     */
    open fun envelopePlanProgress(done: Int, total: Int) {}

    /**
     *   the envelope plan is durable again (published, or an
     * identical fingerprint was reused) — the rebuild window has ended.
     */
    open fun envelopePlanCommitted() {}

    companion object {
        val NOOP: BatchScheduleListener = BatchScheduleListener()
    }
}

internal fun eu.kanade.translation.engines.translator.ProviderFailureException.toTranslationCompletionOutcome(
    pageKey: String,
): TranslationCompletionOutcome = when (failure.retryability) {
    eu.kanade.translation.engines.translator.ProviderFailureRetryability.PAUSE,
    eu.kanade.translation.engines.translator.ProviderFailureRetryability.RETRY_AFTER,
    -> TranslationCompletionOutcome.Paused(
        anchorPageKey = pageKey,
        failure = failure,
        nextEligibleRetryAtEpochMs = failure.retryAfterAtEpochMs,
    )
    eu.kanade.translation.engines.translator.ProviderFailureRetryability.RETRY_NOW,
    eu.kanade.translation.engines.translator.ProviderFailureRetryability.TERMINAL,
    -> TranslationCompletionOutcome.Failed(
        anchorPageKey = pageKey,
        failure = failure,
    )
}

internal fun TranslationStageEvent.toBatchDiagnosticStage(): BatchDiagnosticStage = when (this) {
    TranslationStageEvent.READING -> BatchDiagnosticStage.OCR
    TranslationStageEvent.CLEANING -> BatchDiagnosticStage.INPAINT
    TranslationStageEvent.TRANSLATING -> BatchDiagnosticStage.TRANSLATION
    TranslationStageEvent.RENDERING -> BatchDiagnosticStage.RENDER
}
