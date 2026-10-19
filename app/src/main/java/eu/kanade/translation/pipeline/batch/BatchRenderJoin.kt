package eu.kanade.translation.pipeline.batch

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.isCleanedImageReady
import eu.kanade.translation.model.isTextlessTerminal
import eu.kanade.translation.pipeline.batch.progress.TranslationBatchProgressTracker
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Releases batch-owned cleaned-image buffers and leases after translation
 * artifacts are committed. Display layout and drawing are reader-owned.
 */
internal class BatchRenderJoin(
    private val tracker: TranslationBatchProgressTracker?,
    private val translationRegistry: ConcurrentHashMap<String, PageTranslation>,
    private val heldBitmapRegistry: HeldBitmapRegistry,
    private val writeGate: BatchWriteGate,
    private val abortBatchCandidateFn: suspend (String, String) -> Unit,
) {
    private val pageMutexes = ConcurrentHashMap<String, Mutex>()

    private suspend fun releaseBatchLease(pageKey: String) =
        writeGate.releaseBatchLease(pageKey)

    private suspend fun abortBatchCandidate(pageKey: String, reason: String) =
        abortBatchCandidateFn(pageKey, reason)

    private fun releasePageBuffers(pageKey: String) {
        heldBitmapRegistry.recycleHeld(pageKey)
        translationRegistry.remove(pageKey)
    }

    suspend fun tryRender(pageKey: String) {
        pageMutexes.computeIfAbsent(pageKey) { Mutex() }.withLock {
            val page = translationRegistry[pageKey] ?: return@withLock
            val terminalFailure = when {
                page.ocrStatus == StageStatus.FAILED -> page.ocrError
                page.translationStatus == StageStatus.FAILED -> page.translationError
                page.inpaintStatus == StageStatus.FAILED -> page.inpaintError
                else -> null
            }
            if (terminalFailure != null) {
                abortBatchCandidate(pageKey, terminalFailure)
                return@withLock
            }

            if (page.isTextlessTerminal) {
                tracker?.markTranslateSkipped(pageKey)
                tracker?.markRenderSkipped(pageKey)
                releasePageBuffers(pageKey)
                releaseBatchLease(pageKey)
                return@withLock
            }

            val translationCommitted =
                page.translationStatus in setOf(StageStatus.READY, StageStatus.PARTIAL) &&
                    page.blocks.any { it.translation.isNotBlank() }
            val displayImageCommitted = page.isCleanedImageReady || page.originalImageFallback
            if (!translationCommitted || !displayImageCommitted) return@withLock

            // There is no pipeline render stage. Mark it skipped for legacy
            // progress consumers, then release the inpaint buffer and lease.
            tracker?.markRenderSkipped(pageKey)
            releasePageBuffers(pageKey)
            releaseBatchLease(pageKey)
        }
    }
}