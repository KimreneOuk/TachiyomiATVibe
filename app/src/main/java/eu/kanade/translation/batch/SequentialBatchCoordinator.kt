package eu.kanade.translation.batch

import eu.kanade.translation.translator.ProviderFailureException
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
        val completedPassPageKeys = linkedSetOf<String>()
        var cursor = 0
        var retainedProbe: ChunkPage? = null
        var stoppingOutcome: ChunkCompletionOutcome? = null

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
            } catch (e: BatchPersistenceRejectedException) {
                throw e
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                BatchTranslationDiagnostics.failure(
                    stage = BatchDiagnosticStage.OCR,
                    pageKey = pageKey,
                    errorClass = e::class.java.simpleName,
                )
                throw UnexpectedBatchStageException(pageKey, BatchDiagnosticStage.OCR)
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
        ): ChunkCompletionOutcome = coroutineScope {
            if (chunk.isEmpty()) return@coroutineScope ChunkCompletionOutcome.Completed()

            BatchTranslationDiagnostics.memorySnapshot(
                stage = "chunk_ocr_barrier",
                queueDepth = 0,
                activePages = chunk.size,
            )

            val nativeDone = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
            val translationDone = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
            val nonRenderablePages = ConcurrentHashMap.newKeySet<String>()
            var inlineTranslationOutcome: ChunkCompletionOutcome = ChunkCompletionOutcome.Completed()

            fun nativeGate(pageKey: String): CompletableDeferred<Unit> =
                nativeDone.getOrPut(pageKey) { CompletableDeferred() }

            fun translationGate(pageKey: String): CompletableDeferred<Unit> =
                translationDone.getOrPut(pageKey) { CompletableDeferred() }

            val renderJob = async {
                chunk.forEach { page ->
                    nativeGate(page.pageKey).await()
                    translationGate(page.pageKey).await()
                    val nonRenderable = nonRenderablePages.contains(page.pageKey)
                    if (nonRenderable) {
                        renderJoin.awaitAndSettle(page.pageKey)
                        nativeDone.remove(page.pageKey)
                        translationDone.remove(page.pageKey)
                        return@forEach
                    }
                    listener.renderStarted(page.pageKey)
                    val startedAt = System.nanoTime()
                    try {
                        renderJoin.awaitAndRender(page.pageKey)
                    } catch (e: BatchPersistenceRejectedException) {
                        throw e
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        BatchTranslationDiagnostics.failure(
                            stage = BatchDiagnosticStage.RENDER,
                            pageKey = page.pageKey,
                            errorClass = e::class.java.simpleName,
                        )
                        throw UnexpectedBatchStageException(page.pageKey, BatchDiagnosticStage.RENDER)
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

            fun failureOutcome(
                pageKey: String?,
                error: Throwable,
                stage: BatchDiagnosticStage,
            ): ChunkCompletionOutcome =
                if (error is BatchPersistenceRejectedException) {
                    ChunkCompletionOutcome.PersistenceRejected(
                        anchorPageKey = error.pageKey ?: pageKey ?: chunk.firstOrNull()?.pageKey ?: "<chunk>",
                        stage = error.stage ?: stage,
                    )
                } else if (error is ProviderFailureException) {
                    if (pageKey != null) {
                        error.toChunkCompletionOutcome(pageKey)
                    } else {
                        ChunkCompletionOutcome.Failed(
                            failure = error.failure,
                            reason = error.failure.safeSummary,
                        )
                    }
                } else {
                    // Keep unexpected worker failures distinct from provider outcomes. Only
                    // the affected page is terminal; the coordinator stops this pass so later
                    // pages remain pending rather than being reported as successful.
                    ChunkCompletionOutcome.Unexpected(
                        anchorPageKey = pageKey ?: chunk.firstOrNull()?.pageKey ?: "<chunk>",
                        stage = stage,
                    )
                }

            fun pausedPages(outcome: ChunkCompletionOutcome): Set<String> {
                val anchor = when (outcome) {
                    is ChunkCompletionOutcome.Paused -> outcome.anchorPageKey
                    is ChunkCompletionOutcome.Failed -> outcome.anchorPageKey
                    is ChunkCompletionOutcome.Unexpected -> outcome.anchorPageKey
                    is ChunkCompletionOutcome.PersistenceRejected -> outcome.anchorPageKey
                    is ChunkCompletionOutcome.Completed -> null
                } ?: return emptySet()
                val anchorIndex = chunk.indexOfFirst { it.pageKey == anchor }.takeIf { it >= 0 }
                if (anchorIndex == null) return emptySet()
                return chunk.asSequence()
                    .filter { page ->
                        val explicitlyCompleted = when (outcome) {
                            is ChunkCompletionOutcome.Paused -> page.pageKey in outcome.completedPageKeys
                            is ChunkCompletionOutcome.Failed -> page.pageKey in outcome.completedPageKeys
                            is ChunkCompletionOutcome.Unexpected -> page.pageKey in outcome.completedPageKeys
                            is ChunkCompletionOutcome.PersistenceRejected -> page.pageKey in outcome.completedPageKeys
                            is ChunkCompletionOutcome.Completed -> false
                        }
                        !explicitlyCompleted &&
                            (page.pageKey == anchor || chunk.indexOf(page) >= anchorIndex)
                    }
                    .map { it.pageKey }
                    .toSet()
            }

            suspend fun settleTranslationBranches(outcome: ChunkCompletionOutcome) {
                val paused = pausedPages(outcome)
                nonRenderablePages.addAll(paused)
                chunk.forEach { page ->
                    if (page.pageKey in paused) {
                        renderJoin.onTranslationBranchPaused(page.pageKey)
                    } else {
                        renderJoin.onTranslationBranchDone(page.pageKey)
                    }
                    translationGate(page.pageKey).complete(Unit)
                }
            }

            fun normalizeCompletedOutcome(outcome: ChunkCompletionOutcome): ChunkCompletionOutcome {
                if (outcome !is ChunkCompletionOutcome.Completed || outcome.completedPageKeys.isNotEmpty()) {
                    return outcome
                }
                return outcome.copy(
                    completedPageKeys = chunk.asSequence()
                        .mapNotNull { page -> page.ref?.let { page.pageKey } }
                        .toSet(),
                )
            }

            val translationJob = if (remote) {
                async {
                    if (translatorWorker.usesChunkAdmission) {
                        chunk.forEach { page ->
                            if (page.ref != null) listener.translationRequested(page.pageKey)
                        }
                        val outcome = normalizeCompletedOutcome(
                            try {
                                translatorWorker.completeChunkOutcome(finalChunk)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                val pageKey = chunk.firstOrNull { it.ref != null }?.pageKey
                                BatchTranslationDiagnostics.failure(
                                    stage = BatchDiagnosticStage.TRANSLATION,
                                    pageKey = pageKey ?: "<chunk>",
                                    errorClass = e::class.java.simpleName,
                                )
                                failureOutcome(pageKey, e, BatchDiagnosticStage.TRANSLATION)
                            },
                        )
                        chunk.forEach { page ->
                            if (page.ref != null) listener.translationFinished(page.pageKey)
                        }
                        settleTranslationBranches(outcome)
                        outcome
                    } else {
                        var outcome: ChunkCompletionOutcome = ChunkCompletionOutcome.Completed()
                        chunk.forEach { page ->
                            if (outcome !is ChunkCompletionOutcome.Completed) return@forEach
                            val ref = page.ref
                            if (ref == null) {
                                return@forEach
                            }
                            listener.translationRequested(page.pageKey)
                            val startedAt = System.nanoTime()
                            try {
                                outcome = translatorWorker.translateOutcome(ref)
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                BatchTranslationDiagnostics.failure(
                                    stage = BatchDiagnosticStage.TRANSLATION,
                                    pageKey = page.pageKey,
                                    errorClass = e::class.java.simpleName,
                                )
                                outcome = failureOutcome(page.pageKey, e, BatchDiagnosticStage.TRANSLATION)
                            } finally {
                                BatchTranslationDiagnostics.timing(
                                    stage = BatchDiagnosticStage.TRANSLATION,
                                    pageKey = page.pageKey,
                                    durationMs = elapsedMs(startedAt),
                                    itemCount = ref.blockFingerprints.size,
                                )
                                listener.translationFinished(page.pageKey)
                            }
                        }
                        if (outcome is ChunkCompletionOutcome.Completed) {
                            outcome = normalizeCompletedOutcome(
                                try {
                                    translatorWorker.completeChunkOutcome(finalChunk)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    BatchTranslationDiagnostics.failure(
                                        stage = BatchDiagnosticStage.TRANSLATION,
                                        pageKey = chunk.firstOrNull { it.ref != null }?.pageKey ?: "<chunk>",
                                        errorClass = e::class.java.simpleName,
                                    )
                                    failureOutcome(
                                        chunk.firstOrNull { it.ref != null }?.pageKey,
                                        e,
                                        BatchDiagnosticStage.TRANSLATION,
                                    )
                                },
                            )
                        }
                        settleTranslationBranches(outcome)
                        outcome
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
                        val outcome = normalizeCompletedOutcome(
                            try {
                                translatorWorker.completeChunkOutcome(finalChunk)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                BatchTranslationDiagnostics.failure(
                                    stage = BatchDiagnosticStage.TRANSLATION,
                                    pageKey = chunk.firstOrNull { it.ref != null }?.pageKey ?: "<chunk>",
                                    errorClass = e::class.java.simpleName,
                                )
                                failureOutcome(
                                    chunk.firstOrNull { it.ref != null }?.pageKey,
                                    e,
                                    BatchDiagnosticStage.TRANSLATION,
                                )
                            },
                        )
                        chunk.forEach { page ->
                            if (page.ref != null) listener.translationFinished(page.pageKey)
                        }
                        settleTranslationBranches(outcome)
                        inlineTranslationOutcome = outcome
                    } else {
                        var outcome: ChunkCompletionOutcome = ChunkCompletionOutcome.Completed()
                        chunk.forEach { page ->
                            if (outcome !is ChunkCompletionOutcome.Completed) return@forEach
                            val ref = page.ref
                            if (ref == null) {
                                return@forEach
                            }
                            listener.translationRequested(page.pageKey)
                            val startedAt = System.nanoTime()
                            try {
                                outcome = translatorWorker.translateOutcome(ref)
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                BatchTranslationDiagnostics.failure(
                                    stage = BatchDiagnosticStage.TRANSLATION,
                                    pageKey = page.pageKey,
                                    errorClass = e::class.java.simpleName,
                                )
                                outcome = failureOutcome(page.pageKey, e, BatchDiagnosticStage.TRANSLATION)
                            } finally {
                                BatchTranslationDiagnostics.timing(
                                    stage = BatchDiagnosticStage.TRANSLATION,
                                    pageKey = page.pageKey,
                                    durationMs = elapsedMs(startedAt),
                                    itemCount = ref.blockFingerprints.size,
                                )
                                listener.translationFinished(page.pageKey)
                            }
                        }
                        if (outcome is ChunkCompletionOutcome.Completed) {
                            outcome = normalizeCompletedOutcome(
                                try {
                                    translatorWorker.completeChunkOutcome(finalChunk)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    BatchTranslationDiagnostics.failure(
                                        stage = BatchDiagnosticStage.TRANSLATION,
                                        pageKey = chunk.firstOrNull { it.ref != null }?.pageKey ?: "<chunk>",
                                        errorClass = e::class.java.simpleName,
                                    )
                                    failureOutcome(
                                        chunk.firstOrNull { it.ref != null }?.pageKey,
                                        e,
                                        BatchDiagnosticStage.TRANSLATION,
                                    )
                                },
                            )
                        }
                        settleTranslationBranches(outcome)
                        inlineTranslationOutcome = outcome
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
                    } catch (e: BatchPersistenceRejectedException) {
                        throw e
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        BatchTranslationDiagnostics.failure(
                            stage = BatchDiagnosticStage.INPAINT,
                            pageKey = page.pageKey,
                            errorClass = e::class.java.simpleName,
                        )
                        throw UnexpectedBatchStageException(page.pageKey, BatchDiagnosticStage.INPAINT)
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

                val translationOutcome = translationJob?.await() ?: inlineTranslationOutcome
                renderJob.await()
                translationOutcome
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
                    val entry = try {
                        runOcr(page)
                    } catch (e: UnexpectedBatchStageException) {
                        chunk.forEach { previous -> previous.ref?.let(nativeWorker::releaseNativeHandoff) }
                        throw e
                    } catch (e: BatchPersistenceRejectedException) {
                        chunk.forEach { previous -> previous.ref?.let(nativeWorker::releaseNativeHandoff) }
                        throw e
                    }
                    val admission = try {
                        if (adaptiveChunks && entry.ref != null) {
                            try {
                                translatorWorker.admit(entry.ref)
                            } catch (e: BatchPersistenceRejectedException) {
                                entry.ref?.let(nativeWorker::releaseNativeHandoff)
                                chunk.forEach { page -> page.ref?.let(nativeWorker::releaseNativeHandoff) }
                                throw e
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                BatchTranslationDiagnostics.failure(
                                    stage = BatchDiagnosticStage.TRANSLATION,
                                    pageKey = entry.pageKey,
                                    errorClass = e::class.java.simpleName,
                                )
                                entry.ref?.let(nativeWorker::releaseNativeHandoff)
                                chunk.forEach { page -> page.ref?.let(nativeWorker::releaseNativeHandoff) }
                                throw UnexpectedBatchStageException(entry.pageKey, BatchDiagnosticStage.TRANSLATION)
                            }
                        } else {
                            ChunkAdmission.ACCEPT
                        }
                    } catch (e: CancellationException) {
                        entry.ref?.let(nativeWorker::releaseNativeHandoff)
                        chunk.forEach { page -> page.ref?.let(nativeWorker::releaseNativeHandoff) }
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
                val outcome = processChunk(chunk, finalChunk)
                completedPassPageKeys += outcome.completedPageKeysForPass()
                if (outcome !is ChunkCompletionOutcome.Completed) {
                    stoppingOutcome = outcome
                    break
                }
            }

            val stopped = stoppingOutcome
            if (stopped != null) {
                val paused = stopped as? ChunkCompletionOutcome.Paused
                val failed = stopped as? ChunkCompletionOutcome.Failed
                val unexpected = stopped as? ChunkCompletionOutcome.Unexpected
                val persistenceRejected = stopped as? ChunkCompletionOutcome.PersistenceRejected
                val outcome = BatchPass1Outcome(
                    needsTranslation = needsTranslation.toList(),
                    status = when {
                        paused != null -> BatchPass1Status.PAUSED
                        persistenceRejected != null -> BatchPass1Status.PERSISTENCE_REJECTED
                        else -> BatchPass1Status.FAILED
                    },
                    anchorPageKey = paused?.anchorPageKey ?: failed?.anchorPageKey
                        ?: unexpected?.anchorPageKey ?: persistenceRejected?.anchorPageKey,
                    completedPageKeys = completedPassPageKeys.toSet(),
                    retryablePageKeys = paused?.retryablePageKeys.orEmpty(),
                    terminalPageKeys = failed?.terminalPageKeys.orEmpty() + unexpected?.terminalPageKeys.orEmpty(),
                    failure = paused?.failure ?: failed?.failure,
                    nextEligibleRetryAtEpochMs = paused?.nextEligibleRetryAtEpochMs,
                    reason = paused?.reason ?: failed?.reason ?: unexpected?.reason ?: persistenceRejected?.reason,
                    unexpectedStage = unexpected?.stage,
                    persistenceRejectedStage = persistenceRejected?.stage,
                )
                if (paused != null) listener.batchPaused(outcome)
                BatchTranslationDiagnostics.memorySnapshot(
                    stage = "pass1",
                    queueDepth = 0,
                    activePages = 0,
                )
                return@coroutineScope outcome
            }

            listener.pass1BarrierReleased()
            BatchTranslationDiagnostics.memorySnapshot(
                stage = "pass1",
                queueDepth = 0,
                activePages = 0,
            )
            BatchPass1Outcome(
                needsTranslation = needsTranslation.toList(),
                completedPageKeys = completedPassPageKeys.toSet(),
            )
        } catch (e: BatchPersistenceRejectedException) {
            val outcome = BatchPass1Outcome(
                needsTranslation = needsTranslation.toList(),
                status = BatchPass1Status.PERSISTENCE_REJECTED,
                anchorPageKey = e.pageKey,
                completedPageKeys = completedPassPageKeys.toSet(),
                reason = "Batch persistence publication rejected",
                persistenceRejectedStage = e.stage,
            )
            BatchTranslationDiagnostics.failure(
                stage = e.stage ?: BatchDiagnosticStage.ARTIFACT,
                pageKey = e.pageKey ?: "<batch>",
                errorClass = e::class.java.simpleName,
            )
            return@coroutineScope outcome
        } catch (e: UnexpectedBatchStageException) {
            val outcome = BatchPass1Outcome(
                needsTranslation = needsTranslation.toList(),
                status = BatchPass1Status.FAILED,
                anchorPageKey = e.pageKey,
                completedPageKeys = completedPassPageKeys.toSet(),
                terminalPageKeys = setOf(e.pageKey),
                reason = e.reason,
                unexpectedStage = e.stage,
            )
            BatchTranslationDiagnostics.failure(
                stage = e.stage,
                pageKey = e.pageKey,
                errorClass = e::class.java.simpleName,
            )
            return@coroutineScope outcome
        } finally {
            retainedProbe?.ref?.let(nativeWorker::releaseNativeHandoff)
            retainedProbe = null
        }
    }

    private fun elapsedMs(startedAt: Long): Long =
        ((System.nanoTime() - startedAt) / 1_000_000L).coerceAtLeast(0L)

    private fun ChunkCompletionOutcome.completedPageKeysForPass(): Set<String> = when (this) {
        is ChunkCompletionOutcome.Completed -> completedPageKeys
        is ChunkCompletionOutcome.Paused -> completedPageKeys
        is ChunkCompletionOutcome.Failed -> completedPageKeys
        is ChunkCompletionOutcome.Unexpected -> completedPageKeys
        is ChunkCompletionOutcome.PersistenceRejected -> completedPageKeys
    }

    private data class ChunkPage(
        val pageKey: String,
        val pageIndex: Int,
        val ref: OcrReadyPageRef?,
    )

    private class UnexpectedBatchStageException(
        val pageKey: String,
        val stage: BatchDiagnosticStage,
    ) : RuntimeException("Unexpected ${stage.name.lowercase()} stage failure") {
        val reason: String = "Unexpected ${stage.name.lowercase()} stage failure"
    }

    companion object {
        /** Native work remains bounded for non-contextual translators. */
        const val MAX_NATIVE_LOOKAHEAD_PAGES = 6
    }
}
