package eu.kanade.translation.batch

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.translator.TranslatorComputeClass
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.util.concurrent.ConcurrentHashMap

/**
 * TachiyomiAT: Orchestrates chapter-level batch translation in dynamic, density-bounded chunks.
 *
 * Each chunk executes sequentially through four structured phases:
 * - Phase 1: Native Lane OCR for all pages in the chunk.
 * - Phase 2: Concurrent execution of native inpainting and translation lane batch translation.
 * - Phase 3: Fused rendering and commit to the chapter translation store.
 * - Phase 4: Advances to the next chunk propagating updated rolling context (glossary & micro-summary).
 */
class ChunkBatchCoordinator(
    private val nativeWorker: NativeLaneWorker,
    private val translatorWorker: TranslatorLaneWorker,
    private val renderJoin: RenderJoinWorker,
    val rollingContextManager: RollingContextManager = RollingContextManager(),
    private val listener: BatchScheduleListener = BatchScheduleListener.NOOP,
) {
    suspend fun runChunkedBatch(
        orderedPages: List<PageKey>,
        computeClass: TranslatorComputeClass = TranslatorComputeClass.REMOTE_IO,
        bubbleCounts: Map<String, Int> = emptyMap(),
    ): ChunkBatchOutcome = coroutineScope {
        if (orderedPages.isEmpty()) {
            return@coroutineScope ChunkBatchOutcome(0, 0, emptyList(), emptyList())
        }

        val pageKeys = orderedPages.map { it.first }
        val pageIndexMap = orderedPages.toMap()
        val chunks = DynamicPageChunker.computeChunks(pageKeys, bubbleCounts)

        val processedPages = mutableListOf<String>()
        val needsTranslation = mutableListOf<String>()
        var completedChunks = 0

        for (chunk in chunks) {
            val chunkPageKeys = chunk.pageKeys
            val ocrRefs = mutableListOf<OcrReadyPageRef>()

            // Phase 1: Native Lane OCR for all pages in chunk
            for (pageKey in chunkPageKeys) {
                val pageIndex = pageIndexMap[pageKey] ?: 0
                listener.ocrStarted(pageKey)
                val ref = try {
                    nativeWorker.runOcrStage(pageKey, pageIndex)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    logcat(LogPriority.ERROR, e) { "TachiyomiAT: OCR failed for $pageKey" }
                    null
                }
                listener.ocrFinished(pageKey)
                if (ref != null) {
                    listener.ocrPublished(pageKey)
                    ocrRefs.add(ref)
                    needsTranslation.add(pageKey)
                }
            }

            // Phase 2: Concurrently executes inpainting on the native lane and batch translation via translation lane
            val nativeDone = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
            val translationDone = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
            fun nativeGate(key: String) = nativeDone.getOrPut(key) { CompletableDeferred() }
            fun translationGate(key: String) = translationDone.getOrPut(key) { CompletableDeferred() }

            suspend fun runInpaintBranch() {
                for (pageKey in chunkPageKeys) {
                    listener.inpaintStarted(pageKey)
                    try {
                        nativeWorker.runInpaintStage(pageKey)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        logcat(LogPriority.ERROR, e) { "TachiyomiAT: inpaint failed for $pageKey" }
                    } finally {
                        listener.inpaintFinished(pageKey)
                        renderJoin.onNativeBranchDone(pageKey)
                        nativeGate(pageKey).complete(Unit)
                    }
                }
            }

            suspend fun runTranslationBranch() {
                val currentRollingContext = rollingContextManager.getRollingContext()
                if (translatorWorker is ChunkTranslatorLaneWorker) {
                    val updatedContext = try {
                        translatorWorker.translateChunk(chunk, ocrRefs, currentRollingContext)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        logcat(LogPriority.ERROR, e) { "TachiyomiAT: chunk translation failed for chunk ${chunk.index}" }
                        null
                    }
                    if (updatedContext != null) {
                        rollingContextManager.updateContext(
                            newGlossary = updatedContext.glossary,
                            newMicroSummary = updatedContext.microSummary,
                        )
                    }
                    for (pageKey in chunkPageKeys) {
                        listener.translationFinished(pageKey)
                        renderJoin.onTranslationBranchDone(pageKey)
                        translationGate(pageKey).complete(Unit)
                    }
                } else {
                    val ocrPageKeySet = ocrRefs.map { it.pageKey }.toSet()
                    for (ref in ocrRefs) {
                        listener.translationRequested(ref.pageKey)
                        try {
                            translatorWorker.translate(ref)
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            logcat(LogPriority.ERROR, e) { "TachiyomiAT: translation failed for ${ref.pageKey}" }
                        } finally {
                            listener.translationFinished(ref.pageKey)
                            renderJoin.onTranslationBranchDone(ref.pageKey)
                            translationGate(ref.pageKey).complete(Unit)
                        }
                    }
                    for (pageKey in chunkPageKeys) {
                        if (pageKey !in ocrPageKeySet) {
                            renderJoin.onTranslationBranchDone(pageKey)
                            translationGate(pageKey).complete(Unit)
                        }
                    }
                }
            }

            if (computeClass.mayOverlapNative) {
                val inpaintJob = async { runInpaintBranch() }
                val translationJob = async { runTranslationBranch() }
                inpaintJob.await()
                translationJob.await()
            } else {
                runTranslationBranch()
                runInpaintBranch()
            }

            // Phase 3: Fused render and commit to ChapterTranslationStore
            val renderJobs = chunkPageKeys.map { pageKey ->
                async {
                    nativeGate(pageKey).await()
                    translationGate(pageKey).await()
                    listener.renderStarted(pageKey)
                    try {
                        renderJoin.awaitAndRender(pageKey)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        logcat(LogPriority.ERROR, e) { "TachiyomiAT: render failed for $pageKey" }
                    } finally {
                        listener.renderFinished(pageKey)
                    }
                }
            }
            renderJobs.awaitAll()

            processedPages.addAll(chunkPageKeys)
            completedChunks++
            // Phase 4: Advances to the next chunk while propagating updated rolling context
        }

        listener.pass1BarrierReleased()
        ChunkBatchOutcome(
            completedChunks = completedChunks,
            totalChunks = chunks.size,
            processedPages = processedPages,
            needsTranslation = needsTranslation,
        )
    }

    suspend fun runPass1(
        orderedPages: List<PageKey>,
        computeClass: TranslatorComputeClass = TranslatorComputeClass.REMOTE_IO,
        bubbleCounts: Map<String, Int> = emptyMap(),
    ): BatchCoordinator.Pass1Outcome {
        val outcome = runChunkedBatch(orderedPages, computeClass, bubbleCounts)
        return BatchCoordinator.Pass1Outcome(outcome.needsTranslation)
    }

    suspend fun runPass2(
        flaggedPages: List<Pair<String, PageTranslation>>,
        pass2Worker: suspend (Map<String, PageTranslation>) -> Unit,
    ): BatchCoordinator.Pass2Outcome = coroutineScope {
        if (flaggedPages.isEmpty()) return@coroutineScope BatchCoordinator.Pass2Outcome(0)
        listener.pass2Started()
        val batch = flaggedPages.associate { it.first to it.second }
        pass2Worker(batch)
        BatchCoordinator.Pass2Outcome(flaggedPages.size)
    }
}

data class ChunkBatchOutcome(
    val completedChunks: Int,
    val totalChunks: Int,
    val processedPages: List<String>,
    val needsTranslation: List<String>,
)
