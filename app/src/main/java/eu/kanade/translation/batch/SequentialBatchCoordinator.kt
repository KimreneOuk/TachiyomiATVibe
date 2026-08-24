package eu.kanade.translation.batch

import eu.kanade.translation.translator.TranslatorComputeClass
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.ConcurrentHashMap

/**
 * The single production chapter-batch coordinator.
 *
 * OCR admission is sequential and bounded to one token-adaptive image chunk.
 * A contextual translator may return one OCR-only probe when the page being
 * inspected would flush the preceding whole-page envelope. That probe is held
 * as the first page of the next chunk; it cannot enter inpaint, rendering, or
 * another OCR admission until the preceding chunk is terminal.
 *
 * Once a chunk's OCR barrier is released, one ordered translation lane and the
 * serialized native inpaint lane run concurrently for remote translators. The
 * per-page render join remains the publication boundary, and the next chunk is
 * not admitted until every current page has rendered or reached its terminal
 * failure path.
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
        val adaptiveChunks = translatorWorker.usesChunkAdmission
        // The legacy standard remote lane keeps bounded native lookahead. Local
        // compute remains page-serial so OCR/inference/inpaint never overlap.
        val fallbackChunkPageCount = if (remote) MAX_NATIVE_LOOKAHEAD_PAGES + 1 else 1
        val needsTranslation = linkedSetOf<String>()
        var cursor = 0
        var retainedProbe: ChunkPage? = null

        BatchTranslationDiagnostics.memorySnapshot(
            stage = "pass1",
            queueDepth = 0,
            activePages = orderedPages.size,
        )

        suspend fun runOcr(page: PageKey): ChunkPage {
            val (pageKey, pageIndex) = page
            var ref: OcrReadyPageRef? = null
            listener.ocrStarted(pageKey)
            val startedAt = System.nanoTime()
            try {
                currentCoroutineContext().ensureActive()
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
                    durationMs = elapsedMs(startedAt),
                )
                listener.ocrFinished(pageKey)
            }

            if (ref != null) {
                BatchTranslationDiagnostics.stageDecision(
                    stage = BatchDiagnosticStage.OCR,
                    pageKey = pageKey,
                    decision = BatchDiagnosticDecision.EXECUTE,
                    reason = BatchDiagnosticReason.REFERENCE_READY,
                    fingerprint = ref!!.dependencyFingerprint,
                    itemCount = ref!!.blockFingerprints.size,
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
            return ChunkPage(pageKey = pageKey, pageIndex = pageIndex, ref = ref)
        }

        suspend fun processChunk(
            chunk: List<ChunkPage>,
            finalChunk: Boolean,
        ) = coroutineScope {
            if (chunk.isEmpty()) return@coroutineScope

            BatchTranslationDiagnostics.memorySnapshot(
                stage = "chunk_ocr_barrier",
                queueDepth = 0,
                activePages = chunk.size,
            )

            val nativeDone = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
            val translationDone = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

            fun nativeGate(pageKey: String): CompletableDeferred<Unit> =
                nativeDone.getOrPut(pageKey) { CompletableDeferred() }

            fun translationGate(pageKey: String): CompletableDeferred<Unit> =
                translationDone.getOrPut(pageKey) { CompletableDeferred() }

            fun completeTranslationBranch(pageKey: String) {
                renderJoin.onTranslationBranchDone(pageKey)
                translationGate(pageKey).complete(Unit)
            }

            val renderJob = async {
                chunk.forEach { page ->
                    nativeGate(page.pageKey).await()
                    translationGate(page.pageKey).await()
                    listener.renderStarted(page.pageKey)
                    val startedAt = System.nanoTime()
                    try {
                        renderJoin.awaitAndRender(page.pageKey)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        BatchTranslationDiagnostics.failure(
                            stage = BatchDiagnosticStage.RENDER,
                            pageKey = page.pageKey,
                            errorClass = e::class.java.simpleName,
                        )
                    } finally {
                        BatchTranslationDiagnostics.timing(
                            stage = BatchDiagnosticStage.RENDER,
                            pageKey = page.pageKey,
                            durationMs = elapsedMs(startedAt),
                        )
                        listener.renderFinished(page.pageKey)
                        nativeDone.remove(page.pageKey)
                        translationDone.remove(page.pageKey)
                    }
                }
            }

            val translationJob = if (remote) {
                async {
                    if (translatorWorker.usesChunkAdmission) {
                        chunk.forEach { page ->
                            if (page.ref != null) listener.translationRequested(page.pageKey)
                        }
                        try {
                            translatorWorker.completeChunk(finalChunk)
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            val pageKey = chunk.firstOrNull { it.ref != null }?.pageKey
                            BatchTranslationDiagnostics.failure(
                                stage = BatchDiagnosticStage.TRANSLATION,
                                pageKey = pageKey ?: "<chunk>",
                                errorClass = e::class.java.simpleName,
                            )
                        } finally {
                            chunk.forEach { page ->
                                if (page.ref != null) listener.translationFinished(page.pageKey)
                                completeTranslationBranch(page.pageKey)
                            }
                        }
                    } else {
                        chunk.forEach { page ->
                            val ref = page.ref
                            if (ref == null) {
                                completeTranslationBranch(page.pageKey)
                                return@forEach
                            }
                            listener.translationRequested(page.pageKey)
                            val startedAt = System.nanoTime()
                            try {
                                translatorWorker.translate(ref)
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                BatchTranslationDiagnostics.failure(
                                    stage = BatchDiagnosticStage.TRANSLATION,
                                    pageKey = page.pageKey,
                                    errorClass = e::class.java.simpleName,
                                )
                            } finally {
                                BatchTranslationDiagnostics.timing(
                                    stage = BatchDiagnosticStage.TRANSLATION,
                                    pageKey = page.pageKey,
                                    durationMs = elapsedMs(startedAt),
                                    itemCount = ref.blockFingerprints.size,
                                )
                                listener.translationFinished(page.pageKey)
                                completeTranslationBranch(page.pageKey)
                            }
                        }
                        try {
                            translatorWorker.completeChunk(finalChunk)
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            BatchTranslationDiagnostics.failure(
                                stage = BatchDiagnosticStage.TRANSLATION,
                                pageKey = chunk.firstOrNull { it.ref != null }?.pageKey ?: "<chunk>",
                                errorClass = e::class.java.simpleName,
                            )
                        }
                    }
                }
            } else {
                null
            }

            val unreleasedHandoffs = LinkedHashMap<String, OcrReadyPageRef>()
            chunk.forEach { page -> page.ref?.let { unreleasedHandoffs[page.pageKey] = it } }

            try {
                if (!remote) {
                    if (translatorWorker.usesChunkAdmission) {
                        chunk.forEach { page ->
                            if (page.ref != null) listener.translationRequested(page.pageKey)
                        }
                        try {
                            translatorWorker.completeChunk(finalChunk)
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            BatchTranslationDiagnostics.failure(
                                stage = BatchDiagnosticStage.TRANSLATION,
                                pageKey = chunk.firstOrNull { it.ref != null }?.pageKey ?: "<chunk>",
                                errorClass = e::class.java.simpleName,
                            )
                        } finally {
                            chunk.forEach { page ->
                                if (page.ref != null) listener.translationFinished(page.pageKey)
                                completeTranslationBranch(page.pageKey)
                            }
                        }
                    } else {
                        chunk.forEach { page ->
                            val ref = page.ref
                            if (ref == null) {
                                completeTranslationBranch(page.pageKey)
                                return@forEach
                            }
                            listener.translationRequested(page.pageKey)
                            val startedAt = System.nanoTime()
                            try {
                                translatorWorker.translate(ref)
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                BatchTranslationDiagnostics.failure(
                                    stage = BatchDiagnosticStage.TRANSLATION,
                                    pageKey = page.pageKey,
                                    errorClass = e::class.java.simpleName,
                                )
                            } finally {
                                BatchTranslationDiagnostics.timing(
                                    stage = BatchDiagnosticStage.TRANSLATION,
                                    pageKey = page.pageKey,
                                    durationMs = elapsedMs(startedAt),
                                    itemCount = ref.blockFingerprints.size,
                                )
                                listener.translationFinished(page.pageKey)
                                completeTranslationBranch(page.pageKey)
                            }
                        }
                        try {
                            translatorWorker.completeChunk(finalChunk)
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            BatchTranslationDiagnostics.failure(
                                stage = BatchDiagnosticStage.TRANSLATION,
                                pageKey = chunk.firstOrNull { it.ref != null }?.pageKey ?: "<chunk>",
                                errorClass = e::class.java.simpleName,
                            )
                        }
                    }
                }

                chunk.forEach { page ->
                    val ref = page.ref
                    if (ref == null) {
                        renderJoin.onNativeBranchDone(page.pageKey)
                        nativeGate(page.pageKey).complete(Unit)
                        return@forEach
                    }
                    listener.inpaintStarted(page.pageKey)
                    val startedAt = System.nanoTime()
                    try {
                        nativeWorker.runInpaintStage(page.pageKey, ref.nativeHandoff)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        BatchTranslationDiagnostics.failure(
                            stage = BatchDiagnosticStage.INPAINT,
                            pageKey = page.pageKey,
                            errorClass = e::class.java.simpleName,
                        )
                    } finally {
                        BatchTranslationDiagnostics.timing(
                            stage = BatchDiagnosticStage.INPAINT,
                            pageKey = page.pageKey,
                            durationMs = elapsedMs(startedAt),
                        )
                        listener.inpaintFinished(page.pageKey)
                        nativeWorker.releaseNativeHandoff(ref)
                        unreleasedHandoffs.remove(page.pageKey)
                        renderJoin.onNativeBranchDone(page.pageKey)
                        nativeGate(page.pageKey).complete(Unit)
                    }
                }

                translationJob?.await()
                renderJob.await()
            } finally {
                unreleasedHandoffs.values.forEach(nativeWorker::releaseNativeHandoff)
            }
        }

        try {
            while (cursor < orderedPages.size || retainedProbe != null) {
                currentCoroutineContext().ensureActive()
                val chunk = mutableListOf<ChunkPage>()
                retainedProbe?.let {
                    // The probe was already admitted to the planner while discovering the
                    // preceding boundary. Keep that planner ownership while the page waits
                    // for the prior chunk to become terminal; admitting it again would append
                    // its blocks twice to the next whole-page envelope.
                    chunk += it
                    retainedProbe = null
                }
                var boundaryReached = false

                while (cursor < orderedPages.size && !boundaryReached) {
                    val page = orderedPages[cursor++]
                    val entry = runOcr(page)
                    val admission = try {
                        if (adaptiveChunks && entry.ref != null) {
                            try {
                                translatorWorker.admit(entry.ref)
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                BatchTranslationDiagnostics.failure(
                                    stage = BatchDiagnosticStage.TRANSLATION,
                                    pageKey = entry.pageKey,
                                    errorClass = e::class.java.simpleName,
                                )
                                ChunkAdmission.ACCEPT
                            }
                        } else {
                            ChunkAdmission.ACCEPT
                        }
                    } catch (e: CancellationException) {
                        entry.ref?.let(nativeWorker::releaseNativeHandoff)
                        throw e
                    }

                    if (admission == ChunkAdmission.PROBE && chunk.isNotEmpty()) {
                        retainedProbe = entry
                        boundaryReached = true
                    } else {
                        // A probe as the first page is necessarily a one-page
                        // terminal envelope; there is no preceding chunk to hold.
                        chunk += entry
                        if (adaptiveChunks && admission == ChunkAdmission.PROBE && chunk.size == 1) {
                            boundaryReached = true
                        } else if (!adaptiveChunks && chunk.size >= fallbackChunkPageCount) {
                            boundaryReached = true
                        }
                    }
                }

                if (chunk.isEmpty()) continue
                listener.allOcrBarrierReleased()
                val finalChunk = cursor >= orderedPages.size && retainedProbe == null
                processChunk(chunk, finalChunk)
            }

            listener.pass1BarrierReleased()
            BatchTranslationDiagnostics.memorySnapshot(
                stage = "pass1",
                queueDepth = 0,
                activePages = 0,
            )
            BatchPass1Outcome(needsTranslation = needsTranslation.toList())
        } finally {
            retainedProbe?.ref?.let(nativeWorker::releaseNativeHandoff)
            retainedProbe = null
        }
    }

    private fun elapsedMs(startedAt: Long): Long =
        ((System.nanoTime() - startedAt) / 1_000_000L).coerceAtLeast(0L)

    private data class ChunkPage(
        val pageKey: String,
        val pageIndex: Int,
        val ref: OcrReadyPageRef?,
    )

    companion object {
        /** Native work remains bounded for non-contextual translators. */
        const val MAX_NATIVE_LOOKAHEAD_PAGES = 6
    }
}
