package eu.kanade.translation.batch

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.translator.TranslatorComputeClass
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * TachiyomiAT: orchestrates one batch's per-page detect/OCR, remote translation,
 * inpaint, and render work. Each page completes its OCR and inpaint stages on
 * the serialized native lane before the next page starts.
 *
 * Scope note: this coordinator only governs the batch path
 * (`TranslationPipeline.translateBatch`). The manual/auto single-page path
 * (`TranslationPipeline.translateSinglePage`) is not routed through this
 * coordinator.
 */
class BatchCoordinator(
    private val nativeWorker: NativeLaneWorker,
    private val translatorWorker: TranslatorLaneWorker,
    private val renderJoin: RenderJoinWorker,
    private val listener: BatchScheduleListener = BatchScheduleListener.NOOP,
) {

    suspend fun runPass1(
        orderedPages: List<PageKey>,
        computeClass: TranslatorComputeClass,
    ): Pass1Outcome = coroutineScope {
        val translationQueue = Channel<OcrReadyPageRef>(capacity = orderedPages.size.coerceAtLeast(1))
        val nativeLanePermit = Semaphore(1)
        val remote = computeClass.mayOverlapNative

        val nativeBranchDone = java.util.concurrent.ConcurrentHashMap<String, CompletableDeferred<Unit>>()
        val translationBranchDone = java.util.concurrent.ConcurrentHashMap<String, CompletableDeferred<Unit>>()
        fun nativeGate(pageKey: String) = nativeBranchDone.getOrPut(pageKey) { CompletableDeferred() }
        fun translationGate(pageKey: String) = translationBranchDone.getOrPut(pageKey) { CompletableDeferred() }

        val needsTranslation = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

        val translatorJob = async {
            for (ref in translationQueue) {
                listener.translationRequested(ref.pageKey)
                translatorWorker.translate(ref)
                listener.translationFinished(ref.pageKey)
                renderJoin.onTranslationBranchDone(ref.pageKey)
                translationGate(ref.pageKey).complete(Unit)
            }
        }

        val validPages = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

        for ((pageKey, pageIndex) in orderedPages) {
            nativeLanePermit.withPermit {
                listener.ocrStarted(pageKey)
                val ref = nativeWorker.runOcrStage(pageKey, pageIndex)
                listener.ocrFinished(pageKey)

                if (ref != null) {
                    validPages += pageKey
                    listener.ocrPublished(pageKey)
                    if (remote) {
                        needsTranslation += pageKey
                        translationQueue.send(ref)
                    } else {
                        listener.translationRequested(pageKey)
                        translatorWorker.translate(ref)
                        listener.translationFinished(pageKey)
                        renderJoin.onTranslationBranchDone(pageKey)
                        translationGate(pageKey).complete(Unit)
                    }
                }

                listener.inpaintStarted(pageKey)
                nativeWorker.runInpaintStage(pageKey)
                listener.inpaintFinished(pageKey)
                renderJoin.onNativeBranchDone(pageKey)
                nativeGate(pageKey).complete(Unit)
            }
        }
        translationQueue.close()

        val renderJob = async {
            for ((pageKey, _) in orderedPages) {
                if (!validPages.contains(pageKey)) continue
                translationGate(pageKey).await()
                nativeGate(pageKey).await()

                listener.renderStarted(pageKey)
                renderJoin.awaitAndRender(pageKey)
                listener.renderFinished(pageKey)
            }
        }

        translatorJob.await()
        renderJob.await()

        listener.pass1BarrierReleased()
        Pass1Outcome(needsTranslation = needsTranslation.toList())
    }

    suspend fun runPass2(
        flaggedPages: List<Pair<String, PageTranslation>>,
        pass2Worker: suspend (Map<String, PageTranslation>) -> Unit,
    ): Pass2Outcome = coroutineScope {
        if (flaggedPages.isEmpty()) return@coroutineScope Pass2Outcome(0)
        listener.pass2Started()
        val batch = flaggedPages.associate { it.first to it.second }
        pass2Worker(batch)
        Pass2Outcome(flaggedPages.size)
    }

    data class Pass1Outcome(val needsTranslation: List<String>)
    data class Pass2Outcome(val revisedPages: Int)
}

typealias PageKey = Pair<String, Int>
