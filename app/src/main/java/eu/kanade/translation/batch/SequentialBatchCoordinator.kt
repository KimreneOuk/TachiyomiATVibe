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
    ): BatchPass1Outcome = coroutineScope {
        if (orderedPages.isEmpty()) {
            return@coroutineScope BatchPass1Outcome(emptyList())
        }
        val remote = computeClass.mayOverlapNative
        BatchTranslationDiagnostics.memorySnapshot(
            stage = "pass1",
            queueDepth = 0,
            activePages = orderedPages.size,
        )

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
                            val startedAt = System.nanoTime()
                            try {
                                translatorWorker.translate(ref)
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                BatchTranslationDiagnostics.failure(
                                    stage = BatchDiagnosticStage.TRANSLATION,
                                    pageKey = entry.pageKey,
                                    errorClass = e::class.java.simpleName,
                                )
                            } finally {
                                BatchTranslationDiagnostics.timing(
                                    stage = BatchDiagnosticStage.TRANSLATION,
                                    pageKey = entry.pageKey,
                                    durationMs = elapsedMs(startedAt),
                                    itemCount = ref.blockFingerprints.size,
                                )
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
                val startedAt = System.nanoTime()
                try {
                    renderJoin.awaitAndRender(pageKey)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    BatchTranslationDiagnostics.failure(
                        stage = BatchDiagnosticStage.RENDER,
                        pageKey = pageKey,
                        errorClass = e::class.java.simpleName,
                    )
                } finally {
                    BatchTranslationDiagnostics.timing(
                        stage = BatchDiagnosticStage.RENDER,
                        pageKey = pageKey,
                        durationMs = elapsedMs(startedAt),
                    )
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
                val ocrStartedAt = System.nanoTime()
                try {
                    ref = nativeWorker.runOcrStage(pageKey, pageIndex)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    BatchTranslationDiagnostics.failure(
                        stage = BatchDiagnosticStage.OCR,
                        pageKey = pageKey,
                        errorClass = e::class.java.simpleName,
                    )
                } finally {
                    BatchTranslationDiagnostics.timing(
                        stage = BatchDiagnosticStage.OCR,
                        pageKey = pageKey,
                        durationMs = elapsedMs(ocrStartedAt),
                    )
                    listener.ocrFinished(pageKey)
                }

                if (ref != null) {
                    BatchTranslationDiagnostics.stageDecision(
                        stage = BatchDiagnosticStage.OCR,
                        pageKey = pageKey,
                        decision = BatchDiagnosticDecision.EXECUTE,
                        reason = BatchDiagnosticReason.REFERENCE_READY,
                        fingerprint = ref.dependencyFingerprint,
                        itemCount = ref.blockFingerprints.size,
                    )
                    listener.ocrPublished(pageKey)
                    needsTranslation += pageKey
                } else {
                    BatchTranslationDiagnostics.stageDecision(
                        stage = BatchDiagnosticStage.OCR,
                        pageKey = pageKey,
                        decision = BatchDiagnosticDecision.SKIP,
                        reason = BatchDiagnosticReason.NO_REFERENCE,
                    )
                }

                if (remote) {
                    translationEntries.send(TranslationPageEntry(pageKey, ref))
                } else {
                    if (ref != null) {
                        listener.translationRequested(pageKey)
                        val startedAt = System.nanoTime()
                        try {
                            translatorWorker.translate(ref)
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            BatchTranslationDiagnostics.failure(
                                stage = BatchDiagnosticStage.TRANSLATION,
                                pageKey = pageKey,
                                errorClass = e::class.java.simpleName,
                            )
                        } finally {
                            BatchTranslationDiagnostics.timing(
                                stage = BatchDiagnosticStage.TRANSLATION,
                                pageKey = pageKey,
                                durationMs = elapsedMs(startedAt),
                                itemCount = ref.blockFingerprints.size,
                            )
                        }
                        listener.translationFinished(pageKey)
                    }
                    completeTranslationBranch(pageKey)
                    lookahead.release()
                }

                if (ref != null) {
                    listener.inpaintStarted(pageKey)
                    val startedAt = System.nanoTime()
                    try {
                        nativeWorker.runInpaintStage(pageKey)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        BatchTranslationDiagnostics.failure(
                            stage = BatchDiagnosticStage.INPAINT,
                            pageKey = pageKey,
                            errorClass = e::class.java.simpleName,
                        )
                    } finally {
                        BatchTranslationDiagnostics.timing(
                            stage = BatchDiagnosticStage.INPAINT,
                            pageKey = pageKey,
                            durationMs = elapsedMs(startedAt),
                        )
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
        BatchTranslationDiagnostics.memorySnapshot(
            stage = "pass1",
            queueDepth = 0,
            activePages = 0,
        )
        BatchPass1Outcome(needsTranslation = needsTranslation.toList())
    }

    private fun elapsedMs(startedAt: Long): Long =
        ((System.nanoTime() - startedAt) / 1_000_000L).coerceAtLeast(0L)

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
