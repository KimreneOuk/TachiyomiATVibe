package eu.kanade.translation.batch

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.translator.TranslatorComputeClass
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

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
        val translationQueue = Channel<OcrReadyPageRef>(capacity = Channel.UNLIMITED)
        val nativeLanePermit = Semaphore(1)
        val remote = computeClass.mayOverlapNative

        val nativeBranchDone = HashMap<String, CompletableDeferred<Unit>>()
        val translationBranchDone = HashMap<String, CompletableDeferred<Unit>>()
        fun nativeGate(pageKey: String) = nativeBranchDone.getOrPut(pageKey) { CompletableDeferred() }
        fun translationGate(pageKey: String) = translationBranchDone.getOrPut(pageKey) { CompletableDeferred() }

        val needsTranslation = mutableSetOf<String>()

        val translatorJob = async {
            for (ref in translationQueue) {
                listener.translationRequested(ref.pageKey)
                translatorWorker.translate(ref)
                listener.translationFinished(ref.pageKey)
                renderJoin.onTranslationBranchDone(ref.pageKey)
                translationGate(ref.pageKey).complete(Unit)
                if (remote) {
                    nativeGate(ref.pageKey).await()
                    listener.renderStarted(ref.pageKey)
                    renderJoin.awaitAndRender(ref.pageKey)
                    listener.renderFinished(ref.pageKey)
                }
            }
        }

        val ocrJobs = mutableListOf<Deferred<Unit>>()
        val localRefs = mutableMapOf<String, OcrReadyPageRef>()
        val remoteRefs = mutableListOf<OcrReadyPageRef>()
        val validPages = mutableSetOf<String>()

        for ((pageKey, pageIndex) in orderedPages) {
            val job = async {
                nativeLanePermit.withPermit {
                    listener.ocrStarted(pageKey)
                    val ref = nativeWorker.runOcrStage(pageKey, pageIndex)
                    listener.ocrFinished(pageKey)
                    if (ref != null) {
                        validPages += pageKey
                        listener.ocrPublished(pageKey)
                        if (remote) {
                            needsTranslation += pageKey
                            remoteRefs.add(ref)
                        } else {
                            localRefs[pageKey] = ref
                        }
                    }
                }
            }
            ocrJobs += job
        }
        ocrJobs.awaitAll()
        listener.allOcrBarrierReleased()
        
        if (remote) {
            for (ref in remoteRefs) {
                translationQueue.send(ref)
            }
            translationQueue.close()
        }

        val inpaintJobs = mutableListOf<Deferred<Unit>>()
        for ((pageKey, _) in orderedPages) {
            if (!validPages.contains(pageKey)) continue

            val job = async {
                nativeLanePermit.withPermit {
                    if (!remote) {
                        val ref = localRefs[pageKey]!!
                        listener.translationRequested(pageKey)
                        translatorWorker.translate(ref)
                        listener.translationFinished(pageKey)
                        renderJoin.onTranslationBranchDone(pageKey)
                        translationGate(pageKey).complete(Unit)
                    }

                    listener.inpaintStarted(pageKey)
                    nativeWorker.runInpaintStage(pageKey)
                    listener.inpaintFinished(pageKey)
                    renderJoin.onNativeBranchDone(pageKey)
                    nativeGate(pageKey).complete(Unit)

                    if (!remote) {
                        listener.renderStarted(pageKey)
                        renderJoin.awaitAndRender(pageKey)
                        listener.renderFinished(pageKey)
                    }
                }
            }
            inpaintJobs += job
        }
        inpaintJobs.awaitAll()
        if (!remote) {
            translationQueue.close()
        }
        translatorJob.await()

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
