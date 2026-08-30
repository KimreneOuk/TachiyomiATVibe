package eu.kanade.translation.pipeline.batch

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

/** Result of admitting an OCR-complete page to the current provider-sized chunk. */
enum class ChunkAdmission {
    /** The page belongs to the current image chunk. */
    ACCEPT,

    /** The page is an OCR-only probe for the next image chunk. */
    PROBE,
}

interface TranslatorLaneWorker {
    /** True when [admit] buffers page input for a later chunk completion. */
    val usesChunkAdmission: Boolean get() = false

    /**
     * Admit one OCR-complete page without releasing its chunk ownership.
     *
     * Standard translators use the default per-page path. Contextual AI workers
     * override this to feed their token planner without making a provider call;
     * returning [ChunkAdmission.PROBE] retains the page as the single allowed
     * OCR-only lookahead page.
     */
    suspend fun admit(ref: OcrReadyPageRef): ChunkAdmission {
        translate(ref)
        return ChunkAdmission.ACCEPT
    }

    /** Translate one page's Pass-1 work item. */
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

    /**
     * Complete the current image chunk. For a buffered AI lane this is the
     * first point at which queued planner emissions may make provider calls.
     */
    /**
     * Complete the current image chunk. This legacy hook intentionally keeps
     * its Unit return type so existing lane implementations remain source
     * compatible; provider-aware lanes override [completeChunkOutcome].
     */
    suspend fun completeChunk(finalChunk: Boolean) {}

    /** Typed chunk completion bridge used by the coordinator. */
    suspend fun completeChunkOutcome(finalChunk: Boolean): ChunkCompletionOutcome {
        completeChunk(finalChunk)
        return ChunkCompletionOutcome.Completed()
    }
}

interface RenderJoinWorker {
    fun onNativeBranchDone(pageKey: String)
    fun onTranslationBranchDone(pageKey: String)

    /** Closes the translation gate without making a paused page renderable. */
    fun onTranslationBranchPaused(pageKey: String) = onTranslationBranchDone(pageKey)
    suspend fun awaitAndRender(pageKey: String)

    /**
     * Settles a page whose translation branch is paused/terminal. A default
     * no-op keeps the committed display untouched; production joins may still
     * override it when they need to close an explicit gate.
     */
    suspend fun awaitAndSettle(pageKey: String) {}
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
internal class BatchPersistenceRejectedException(
    val pageKey: String? = null,
    val stage: BatchDiagnosticStage? = null,
) : IllegalStateException("Batch persistence publication rejected")

open class BatchScheduleListener {
    open fun ocrStarted(pageKey: String) {}
    open fun ocrFinished(pageKey: String) {}
    open fun ocrPublished(pageKey: String) {}
    open fun inpaintStarted(pageKey: String) {}
    open fun inpaintFinished(pageKey: String) {}
    open fun translationRequested(pageKey: String) {}
    open fun translationFinished(pageKey: String) {}
    open fun renderStarted(pageKey: String) {}
    open fun renderFinished(pageKey: String) {}
    open fun allOcrBarrierReleased() {}
    open fun pass1BarrierReleased() {}
    open fun pass2Started() {}
    open fun batchPaused(outcome: BatchPass1Outcome) {}

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
