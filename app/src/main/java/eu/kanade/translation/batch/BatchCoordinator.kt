package eu.kanade.translation.batch

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.translator.TranslatorComputeClass
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * TachiyomiAT: testable batch schedule extracted from
 * [eu.kanade.translation.TranslationPipeline.translateBatch]. Owns ONE
 * serialized native lane, ONE serialized translator/provider lane, a bounded
 * translation channel, and a per-page render join. The schedule is expressed
 * entirely in terms of the worker interfaces below, so it is unit-testable with
 * fakes + virtual time and no Android/network.
 *
 * Scheduling invariants enforced here (Checkpoint 2 §1, §2, §5):
 *  - Native OCR + inpaint never overlap (single [nativeLanePermit]).
 *  - For REMOTE_IO translators, the translation request starts AFTER OCR
 *    publication and BEFORE same-page inpaint completes (overlap): the native
 *    worker splits OCR (returns the work item) from inpaint, and the coordinator
 *    offers the item to the channel between the two.
 *  - For LOCAL_COMPUTE (ML Kit), translation stays serialized inside the native
 *    lane so it never overlaps native work.
 *  - The translation channel is bounded (capacity 2). If full, the native lane
 *    finishes same-page inpainting, releases the bitmap + native permit, THEN
 *    performs the suspending send. Never suspends holding a bitmap + permit.
 *  - Render waits for BOTH the translation branch and the inpaint/render branch.
 *  - Pass 2 starts once every eligible Pass-1 translation is terminal, even if
 *    inpaint/render still running (Pass-1 barrier separated from inpaint).
 *  - Final completion joins Pass 2 + inpaint + render.
 */
class BatchCoordinator(
    private val nativeWorker: NativeLaneWorker,
    private val translatorWorker: TranslatorLaneWorker,
    private val renderJoin: RenderJoinWorker,
    private val listener: BatchScheduleListener = BatchScheduleListener.NOOP,
    /** Bounded translation channel capacity. */
    private val channelCapacity: Int = DEFAULT_CHANNEL_CAPACITY,
) {

    /**
     * Run the full Pass-1 schedule for [orderedPages]. Returns when every page
     * has a terminal Pass-1 translation AND its render join has completed (the
     * Pass-1 barrier). Pass 2 is launched separately by [runPass2].
     *
     * @param computeClass drives lane routing (overlap vs serialized).
     */
    suspend fun runPass1(
        orderedPages: List<PageKey>,
        computeClass: TranslatorComputeClass,
    ): Pass1Outcome = coroutineScope {
        val translationChannel = Channel<TranslationWorkItem>(capacity = channelCapacity)
        val nativeLanePermit = Semaphore(1) // serialized native OCR + inpaint
        val remote = computeClass.mayOverlapNative

        // Per-page render join gates: completed when BOTH branches finish.
        val nativeBranchDone = HashMap<String, CompletableDeferred<Unit>>()
        val translationBranchDone = HashMap<String, CompletableDeferred<Unit>>()
        fun nativeGate(pageKey: String) = nativeBranchDone.getOrPut(pageKey) { CompletableDeferred() }
        fun translationGate(pageKey: String) = translationBranchDone.getOrPut(pageKey) { CompletableDeferred() }

        val needsTranslation = mutableSetOf<String>()

        // Translator lane: ONE serialized provider lane. Reads work items and
        // runs Pass-1 translation. Render join fires after both branches.
        val translatorJob = async {
            for (item in translationChannel) {
                listener.translationRequested(item.pageKey)
                translatorWorker.translate(item)
                listener.translationFinished(item.pageKey)
                renderJoin.onTranslationBranchDone(item.pageKey)
                translationGate(item.pageKey).complete(Unit)
                // Render join: wait for both branches then render.
                nativeGate(item.pageKey).await()
                translationGate(item.pageKey).await()
                listener.renderStarted(item.pageKey)
                renderJoin.awaitAndRender(item.pageKey)
                listener.renderFinished(item.pageKey)
            }
        }

        // Native lane: ONE serialized lane. For REMOTE_IO it offers the work
        // item to the channel right after OCR (translation overlaps inpaint);
        // for LOCAL_COMPUTE it translates inline (serialized with native).
        val nativeJobs = mutableListOf<Deferred<Unit>>()
        for ((pageKey, pageIndex) in orderedPages) {
            val job = async {
                nativeLanePermit.withPermit {
                    listener.nativeLaneEntered(pageKey)
                    try {
                        val ocrResult = nativeWorker.runOcr(pageKey, pageIndex)
                        if (ocrResult == null) return@withPermit // textless/skip
                        val item = ocrResult.item
                        listener.ocrPublished(pageKey)

                        // Pending inpaint to run while the translation overlaps
                        // (REMOTE) or after inline translation (LOCAL).
                        suspend fun doInpaint() {
                            listener.inpaintStarted(pageKey)
                            nativeWorker.runInpaint(ocrResult)
                            listener.inpaintFinished(pageKey)
                        }

                        if (remote) {
                            needsTranslation += pageKey
                            // Offer AFTER OCR, BEFORE inpaint completes -> overlap.
                            val sendResult = translationChannel.trySend(item)
                            if (sendResult.isFailure) {
                                // Channel full: must NOT suspend holding bitmap +
                                // native permit. Finish inpaint, release bitmap,
                                // leave native lane, THEN suspending send.
                                listener.channelSendSuspendedBeforeBitmapRelease(pageKey)
                                doInpaint()
                                nativeWorker.releaseNativeResources(pageKey)
                                renderJoin.onNativeBranchDone(pageKey)
                                nativeGate(pageKey).complete(Unit)
                                translationChannel.send(item)
                                return@withPermit
                            }
                            // Offer succeeded without suspending: run inpaint while
                            // translation overlaps, then release.
                            doInpaint()
                            nativeWorker.releaseNativeResources(pageKey)
                        } else {
                            // LOCAL_COMPUTE: translate inline on the native lane so
                            // ML Kit inference never overlaps native OCR/inpaint.
                            needsTranslation += pageKey
                            doInpaint()
                            nativeWorker.releaseNativeResources(pageKey)
                            listener.translationRequested(pageKey)
                            translatorWorker.translate(item)
                            listener.translationFinished(pageKey)
                            renderJoin.onTranslationBranchDone(pageKey)
                            translationGate(pageKey).complete(Unit)
                            listener.renderStarted(pageKey)
                            renderJoin.awaitAndRender(pageKey)
                            listener.renderFinished(pageKey)
                        }
                        renderJoin.onNativeBranchDone(pageKey)
                        nativeGate(pageKey).complete(Unit)
                    } finally {
                        listener.nativeLaneLeft(pageKey)
                    }
                }
            }
            nativeJobs += job
        }

        nativeJobs.awaitAll()
        translationChannel.close()
        translatorJob.await()

        listener.pass1BarrierReleased()
        Pass1Outcome(needsTranslation = needsTranslation.toList())
    }

    /**
     * Run Pass 2 (revision). Separated from the inpaint/render barrier: Pass 2
     * starts once Pass 1 is terminal, even if inpaint/render still finishing.
     */
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

    companion object {
        const val DEFAULT_CHANNEL_CAPACITY = 2
    }
}

/** Page key + reading-order index pair. */
typealias PageKey = Pair<String, Int>
