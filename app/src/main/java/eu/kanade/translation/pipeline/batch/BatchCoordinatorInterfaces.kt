package eu.kanade.translation.pipeline.batch

import eu.kanade.translation.PageWriteOrigin
import eu.kanade.translation.translator.ProviderFailure

/**
 * TachiyomiAT: testable batch coordinator interfaces.
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
    suspend fun translateOutcome(ref: OcrReadyPageRef): ChunkCompletionOutcome =
        try {
            translate(ref)
            ChunkCompletionOutcome.Completed(setOf(ref.pageKey))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: eu.kanade.translation.translator.ProviderFailureException) {
            e.toChunkCompletionOutcome(ref.pageKey)
        }
}

/** Result of the only live batch coordinator's first pass. */
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

/** Typed result returned by one translator chunk or one per-page request. */
sealed interface ChunkCompletionOutcome {
    data class Completed(
        val completedPageKeys: Set<String> = emptySet(),
    ) : ChunkCompletionOutcome

    data class Paused(
        val anchorPageKey: String,
        val completedPageKeys: Set<String> = emptySet(),
        val retryablePageKeys: Set<String> = setOf(anchorPageKey),
        val failure: ProviderFailure? = null,
        val nextEligibleRetryAtEpochMs: Long? = failure?.retryAfterAtEpochMs,
        val reason: String = failure?.safeSummary ?: "Translation paused; retryable provider work remains",
    ) : ChunkCompletionOutcome

    data class Failed(
        val anchorPageKey: String? = null,
        val completedPageKeys: Set<String> = emptySet(),
        val terminalPageKeys: Set<String> = anchorPageKey?.let(::setOf).orEmpty(),
        val failure: ProviderFailure? = null,
        val reason: String = failure?.safeSummary ?: "Translation failed",
    ) : ChunkCompletionOutcome

    /**
     * A worker failed outside the typed provider-failure contract. This is kept
     * distinct from [Failed] so an unexpected programming/persistence error can
     * never be mistaken for a successfully completed page or a durable provider
     * failure. Only the affected page is terminal; later pages remain pending.
     */
    data class Unexpected(
        val anchorPageKey: String,
        val stage: BatchDiagnosticStage,
        val completedPageKeys: Set<String> = emptySet(),
        val terminalPageKeys: Set<String> = setOf(anchorPageKey),
        val reason: String = "Unexpected ${stage.name.lowercase()} stage failure",
    ) : ChunkCompletionOutcome

    /**
     * A guarded artifact publication was rejected. The affected page is not
     * terminal and must not be reported as a durable provider failure: the
     * in-memory pass stops so a later run can re-read the current store state.
     */
    data class PersistenceRejected(
        val anchorPageKey: String,
        val stage: BatchDiagnosticStage,
        val completedPageKeys: Set<String> = emptySet(),
        val reason: String = "Batch persistence publication rejected",
    ) : ChunkCompletionOutcome
}

/** Result of the only live batch coordinator's first pass. */
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
     * T917 D3 defer-and-rescan: the page's stage lease was DENIED (another
     * origin — e.g. a manual reader tap — owns the page), so the pass skipped
     * it instead of doing paid work. The coordinator records the deferral and
     * re-runs the page within the same pass once its lease is free.
     */
    open fun ocrDeferred(pageKey: String, owner: PageWriteOrigin?) {}

    companion object {
        val NOOP: BatchScheduleListener = BatchScheduleListener()
    }
}

internal fun eu.kanade.translation.translator.ProviderFailureException.toChunkCompletionOutcome(
    pageKey: String,
): ChunkCompletionOutcome = when (failure.retryability) {
    eu.kanade.translation.translator.ProviderFailureRetryability.PAUSE,
    eu.kanade.translation.translator.ProviderFailureRetryability.RETRY_AFTER,
    -> ChunkCompletionOutcome.Paused(
        anchorPageKey = pageKey,
        failure = failure,
        nextEligibleRetryAtEpochMs = failure.retryAfterAtEpochMs,
    )
    eu.kanade.translation.translator.ProviderFailureRetryability.RETRY_NOW,
    eu.kanade.translation.translator.ProviderFailureRetryability.TERMINAL,
    -> ChunkCompletionOutcome.Failed(
        anchorPageKey = pageKey,
        failure = failure,
    )
}
