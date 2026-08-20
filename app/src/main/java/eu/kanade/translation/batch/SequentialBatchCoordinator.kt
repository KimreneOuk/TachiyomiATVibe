package eu.kanade.translation.batch

import eu.kanade.translation.translator.TranslatorComputeClass
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.util.concurrent.ConcurrentHashMap

/**
 * TachiyomiAT: the single production chapter-batch coordinator.
 *
 * Pages are processed strictly in the natural order given; reader viewport and
 * last-read position are never scheduling inputs. One serialized native lane
 * runs each page's OCR stage and then its inpaint stage, so at most one native
 * worker is in flight. Remote (I/O-bound) translators run on a single ordered
 * translation lane that consumes one entry per page through a bounded channel,
 * so translation and context commits stay sequential even while native work
 * runs ahead. Native lookahead is bounded: the native lane may not start page
 * `i` until the translation lane has settled page `i - MAX_NATIVE_LOOKAHEAD_PAGES`,
 * which keeps decoded frames and candidate work independent of chapter length.
 *
 * A page whose OCR stage yields no reference (reused, reader-owned, or failed)
 * skips the inpaint stage entirely — no second decode or duplicate native
 * inference for a page this batch does not own.
 *
 * LOCAL_COMPUTE translators (on-device ML Kit) translate inline on the native
 * lane so their inference never overlaps native OCR/inpaint.
 */
class SequentialBatchCoordinator(
    private val nativeWorker: NativeLaneWorker,
    private val translatorWorker: TranslatorLaneWorker,
    private val renderJoin: RenderJoinWorker,
    private val listener: BatchScheduleListener = BatchScheduleListener.NOOP,
) {

    suspend fun runPass1(
        orderedPages: List<PageKey>,
        computeClass: TranslatorComputeClass,
    ): BatchCoordinator.Pass1Outcome = coroutineScope {
        if (orderedPages.isEmpty()) {
            return@coroutineScope BatchCoordinator.Pass1Outcome(emptyList())
        }
        val remote = computeClass.mayOverlapNative

        val translationEntries = Channel<TranslationPageEntry>(capacity = MAX_NATIVE_LOOKAHEAD_PAGES)
        val lookahead = Semaphore(permits = MAX_NATIVE_LOOKAHEAD_PAGES + 1)

        val nativeDone = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
        val translationDone = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
        fun nativeGate(pageKey: String) = nativeDone.getOrPut(pageKey) { CompletableDeferred() }
        fun translationGate(pageKey: String) = translationDone.getOrPut(pageKey) { CompletableDeferred() }

        val needsTranslation = ConcurrentHashMap.newKeySet<String>()

        fun completeTranslationBranch(pageKey: String) {
            renderJoin.onTranslationBranchDone(pageKey)
            translationGate(pageKey).complete(Unit)
        }

        val translatorJob = if (remote) {
            async {
                for (entry in translationEntries) {
                    try {
                        val ref = entry.ref
                        if (ref != null) {
                            listener.translationRequested(entry.pageKey)
                            try {
                                translatorWorker.translate(ref)
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                logcat(LogPriority.ERROR, e) {
                                    "TachiyomiAT batch translation failed: ${entry.pageKey}"
                                }
                            }
                            listener.translationFinished(entry.pageKey)
                        }
                    } finally {
                        completeTranslationBranch(entry.pageKey)
                        lookahead.release()
                    }
                }
            }
        } else {
            null
        }

        val renderJob = async {
            for ((pageKey, _) in orderedPages) {
                nativeGate(pageKey).await()
                translationGate(pageKey).await()
                listener.renderStarted(pageKey)
                try {
                    renderJoin.awaitAndRender(pageKey)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    logcat(LogPriority.ERROR, e) { "TachiyomiAT batch render failed: $pageKey" }
                } finally {
                    listener.renderFinished(pageKey)
                    nativeDone.remove(pageKey)
                    translationDone.remove(pageKey)
                }
            }
        }

        try {
            for ((pageKey, pageIndex) in orderedPages) {
                currentCoroutineContext().ensureActive()
                lookahead.acquire()
                var ref: OcrReadyPageRef? = null
                listener.ocrStarted(pageKey)
                try {
                    ref = nativeWorker.runOcrStage(pageKey, pageIndex)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    logcat(LogPriority.ERROR, e) { "TachiyomiAT batch OCR failed: $pageKey" }
                } finally {
                    listener.ocrFinished(pageKey)
                }

                if (ref != null) {
                    listener.ocrPublished(pageKey)
                    needsTranslation += pageKey
                }

                if (remote) {
                    translationEntries.send(TranslationPageEntry(pageKey, ref))
                } else {
                    if (ref != null) {
                        listener.translationRequested(pageKey)
                        try {
                            translatorWorker.translate(ref)
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            logcat(LogPriority.ERROR, e) {
                                "TachiyomiAT batch translation failed: $pageKey"
                            }
                        }
                        listener.translationFinished(pageKey)
                    }
                    completeTranslationBranch(pageKey)
                    lookahead.release()
                }

                if (ref != null) {
                    listener.inpaintStarted(pageKey)
                    try {
                        nativeWorker.runInpaintStage(pageKey)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        logcat(LogPriority.ERROR, e) { "TachiyomiAT batch inpaint failed: $pageKey" }
                    } finally {
                        listener.inpaintFinished(pageKey)
                    }
                }
                renderJoin.onNativeBranchDone(pageKey)
                nativeGate(pageKey).complete(Unit)
            }
        } finally {
            if (remote) translationEntries.close()
        }

        translatorJob?.await()
        renderJob.await()

        listener.pass1BarrierReleased()
        BatchCoordinator.Pass1Outcome(needsTranslation = needsTranslation.toList())
    }

    private data class TranslationPageEntry(
        val pageKey: String,
        val ref: OcrReadyPageRef?,
    )

    companion object {
        /** Native work may run at most this many pages beyond the translation frontier. */
        const val MAX_NATIVE_LOOKAHEAD_PAGES = 3

        /** Post-OCR AI request envelopes cover at most this many consecutive pages. */
        const val MAX_AI_ENVELOPE_PAGES = 4

        /** Post-OCR AI request envelopes carry at most this many accepted blocks. */
        const val MAX_AI_ENVELOPE_BLOCKS = 24
    }
}
