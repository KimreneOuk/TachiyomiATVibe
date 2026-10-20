package eu.kanade.translation.processing.batch

import eu.kanade.translation.diagnostics.BatchDiagnosticStage

/** Shared marker for a guarded write rejected after a stage produced a result. */
internal open class BatchPersistenceRejectedException(
    val pageKey: String? = null,
    val stage: BatchDiagnosticStage? = null,
) : IllegalStateException("Batch persistence publication rejected")
