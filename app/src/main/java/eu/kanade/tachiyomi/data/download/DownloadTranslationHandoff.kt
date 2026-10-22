package eu.kanade.tachiyomi.data.download

import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.translation.TranslationManager
import eu.kanade.translation.coordinator.DownloadRekeyOutcome
import eu.kanade.translation.model.onlinePageTranslationKey
import eu.kanade.translation.diagnostics.BatchDownloadDiagnostics
import eu.kanade.translation.diagnostics.BatchDownloadResult
import eu.kanade.translation.diagnostics.BatchDownloadStage
import eu.kanade.translation.diagnostics.BatchDownloadTraceBoundary
import eu.kanade.translation.diagnostics.BatchDownloadTraceContext
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * Handles translation artifact rekeying and post-finalization handoff.
 */
internal class DownloadTranslationHandoff(
    private val translationManager: TranslationManager,
) {

    suspend fun handOffAfterFinalization(
        download: Download,
        pageList: List<Page>,
        onDiskKeys: List<String>,
        traceContext: BatchDownloadTraceContext = BatchDownloadTraceContext(download.chapter.id) {
            translationManager.pendingRequestGeneration(download.chapter.id)
        },
    ) {
        var stage = BatchDownloadStage.ADMISSION
        try {
            if (onDiskKeys.isNotEmpty()) {
                stage = BatchDownloadStage.REKEY
                traceContext.generation(BatchDownloadTraceBoundary.HANDOFF)?.let { generation ->
                    BatchDownloadDiagnostics.handoff(
                        download.chapter.id,
                        generation,
                        stage,
                        BatchDownloadResult.START,
                    )
                }
                val onlineKeys = pageList.map { page -> onlinePageTranslationKey(page.imageUrl, page.url) }
                when (
                    val outcome = translationManager.rekeyTranslationForCompletedDownload(
                        chapter = download.chapter,
                        manga = download.manga,
                        source = download.source,
                        onlineKeyByPageIndex = onlineKeys,
                        onDiskKeyByPageIndex = onDiskKeys,
                    )
                ) {
                    is DownloadRekeyOutcome.Performed -> {
                        if (outcome.skippedCollisions > 0) {
                            val reason =
                                "Rekey partial: ${outcome.moved} moved, " +
                                    "${outcome.skippedCollisions} destination collisions skipped"
                            traceContext.generation(BatchDownloadTraceBoundary.HANDOFF)?.let { generation ->
                                BatchDownloadDiagnostics.handoff(
                                    download.chapter.id,
                                    generation,
                                    stage,
                                    BatchDownloadResult.FAILED,
                                    RuntimeException(reason),
                                )
                            }
                            translationManager.markTranslationHandoffFailed(download.chapter.id, reason)
                            return
                        }
                        traceContext.generation(BatchDownloadTraceBoundary.HANDOFF)?.let { generation ->
                            BatchDownloadDiagnostics.handoff(
                                download.chapter.id,
                                generation,
                                stage,
                                BatchDownloadResult.SUCCESS,
                            )
                        }
                    }
                    DownloadRekeyOutcome.Noop -> {
                        traceContext.generation(BatchDownloadTraceBoundary.HANDOFF)?.let { generation ->
                            BatchDownloadDiagnostics.handoff(
                                download.chapter.id,
                                generation,
                                stage,
                                BatchDownloadResult.SUCCESS,
                            )
                        }
                    }
                    DownloadRekeyOutcome.NoTranslationRecords -> {
                        traceContext.generation(BatchDownloadTraceBoundary.HANDOFF)?.let { generation ->
                            BatchDownloadDiagnostics.handoff(
                                download.chapter.id,
                                generation,
                                stage,
                                BatchDownloadResult.FALSE,
                            )
                        }
                    }
                    is DownloadRekeyOutcome.Deferred,
                    is DownloadRekeyOutcome.Rejected,
                    -> {
                        if (
                            outcome is DownloadRekeyOutcome.Deferred &&
                            outcome.waitingForRegisteredStoreRelease
                        ) {
                            logcat(if (outcome.requestPreserved) LogPriority.INFO else LogPriority.WARN) {
                                "Translation handoff waiting for registered reader store release " +
                                    "for chapter ${download.chapter.id}; requestPreserved=${outcome.requestPreserved}"
                            }
                            return
                        }
                        val reason = when (outcome) {
                            is DownloadRekeyOutcome.Deferred ->
                                "Rekey deferred: ${outcome.reason}"
                            is DownloadRekeyOutcome.Rejected ->
                                "Rekey rejected: ${outcome.reason}"
                            else -> "Rekey not completed"
                        }
                        traceContext.generation(BatchDownloadTraceBoundary.HANDOFF)?.let { generation ->
                            BatchDownloadDiagnostics.handoff(
                                download.chapter.id,
                                generation,
                                stage,
                                BatchDownloadResult.FAILED,
                                RuntimeException(
                                    reason,
                                    (outcome as? DownloadRekeyOutcome.Rejected)?.cause,
                                ),
                            )
                        }
                        translationManager.markTranslationHandoffFailed(download.chapter.id, reason)
                        return
                    }
                }
            }
            stage = BatchDownloadStage.ADMISSION
            traceContext.generation(BatchDownloadTraceBoundary.HANDOFF)?.let { generation ->
                BatchDownloadDiagnostics.handoff(
                    download.chapter.id,
                    generation,
                    stage,
                    BatchDownloadResult.START,
                )
            }
            translationManager.startTranslationAfterDownloadIfRequested(download.manga, download.chapter)
            traceContext.generation(BatchDownloadTraceBoundary.HANDOFF)?.let { generation ->
                BatchDownloadDiagnostics.handoff(
                    download.chapter.id,
                    generation,
                    stage,
                    BatchDownloadResult.SUCCESS,
                )
            }
        } catch (error: Throwable) {
            if (error is CancellationException) {
                traceContext.generation(BatchDownloadTraceBoundary.HANDOFF)?.let { generation ->
                    BatchDownloadDiagnostics.handoff(
                        download.chapter.id,
                        generation,
                        stage,
                        BatchDownloadResult.CANCELLED,
                        error,
                    )
                }
                throw error
            }
            traceContext.generation(BatchDownloadTraceBoundary.HANDOFF)?.let { generation ->
                BatchDownloadDiagnostics.handoff(
                    download.chapter.id,
                    generation,
                    stage,
                    BatchDownloadResult.FAILED,
                    error,
                )
            }
            logcat(LogPriority.ERROR, error)
            translationManager.markTranslationHandoffFailed(
                download.chapter.id,
                "Translation could not start after the chapter download: " +
                    (error.message ?: error::class.java.simpleName),
            )
        }
    }
}
