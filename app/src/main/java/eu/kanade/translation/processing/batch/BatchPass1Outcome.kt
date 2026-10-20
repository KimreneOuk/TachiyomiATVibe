package eu.kanade.translation.processing.batch

import eu.kanade.translation.diagnostics.BatchDiagnosticStage
import eu.kanade.translation.engines.translator.ProviderFailure

/** Batch terminal evidence consumed by progress projection and reconciliation. */
enum class BatchPass1Status {
    COMPLETED,
    PAUSED,
    FAILED,
    PERSISTENCE_REJECTED,
}

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
