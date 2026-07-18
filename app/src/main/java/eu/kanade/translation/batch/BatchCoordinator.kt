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

/**
 * TachiyomiAT: orchestrates one batch's per-page detect/OCR, remote translation,
 * inpaint, and render work while enforcing the chapter-level scheduling invariant
 * from Plan/active/2026-07-18-translation-stage-recovery/plan.md:
 *
 *   No inpaint job may start until every expected chapter page has reached a
 *   terminal detect/OCR result for the active generation.
 *
 * `runPass1` enforces this with an explicit set-equality barrier
 * (`terminalOcrKeys == expectedPageKeys`) after `ocrJobs.awaitAll()`, BEFORE the
 * inpaint loop starts. Remote translation is fed into the translation channel
 * inside each per-page OCR job, so it may overlap later OCR; this is correct
 * per plan rule 5 and avoids the prior regression where the queue was only fed
 * after `awaitAll()`.
 *
 * Scope note: this coordinator only governs the batch path
 * (`TranslationPipeline.translateBatch`). The manual/auto single-page path
 * (`TranslationPipeline.translateSinglePage`) bypasses the barrier by design —
 * it processes exactly one page, so there is no chapter-wide OCR set to wait
 * for. A multi-page chapter barrier is meaningful only for batch/pre-translation.
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

        val ocrJobs = mutableListOf<Deferred<Unit>>()
        val localRefs = java.util.concurrent.ConcurrentHashMap<String, OcrReadyPageRef>()
        val validPages = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

        val expectedPageKeys = orderedPages.map { it.first }.toSet()
        val terminalOcrKeys = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

        for ((pageKey, pageIndex) in orderedPages) {
            val job = async {
                val ref = nativeLanePermit.withPermit {
                    listener.ocrStarted(pageKey)
                    val r = nativeWorker.runOcrStage(pageKey, pageIndex)
                    listener.ocrFinished(pageKey)
                    r
                }

                terminalOcrKeys += pageKey

                if (ref != null) {
                    validPages += pageKey
                    listener.ocrPublished(pageKey)
                    if (remote) {
                        needsTranslation += pageKey
                        translationQueue.send(ref)
                    } else {
                        localRefs[pageKey] = ref
                    }
                }
            }
            ocrJobs += job
        }
        ocrJobs.awaitAll()

        check(terminalOcrKeys == expectedPageKeys) {
            "Barrier deadlock: terminal OCR keys $terminalOcrKeys do not match expected $expectedPageKeys"
        }
        listener.allOcrBarrierReleased()

        if (remote) {
            translationQueue.close()
        }

        for ((pageKey, _) in orderedPages) {
            if (!validPages.contains(pageKey)) continue

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
            }
        }
        if (!remote) {
            translationQueue.close()
        }

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
