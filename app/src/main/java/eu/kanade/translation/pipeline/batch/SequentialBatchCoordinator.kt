package eu.kanade.translation.pipeline.batch

import eu.kanade.translation.PageWriteOrigin
import eu.kanade.translation.diagnostics.TranslationPipelineDiagnostics
import eu.kanade.translation.diagnostics.TranslationRunTrace
import eu.kanade.translation.diagnostics.TranslationScheduleTrace
import eu.kanade.translation.diagnostics.TranslationTrace
import eu.kanade.translation.diagnostics.TranslationTraceLane
import eu.kanade.translation.diagnostics.TranslationTraceOutcome
import eu.kanade.translation.diagnostics.TranslationTracePlan
import eu.kanade.translation.diagnostics.TranslationTraceProvider
import eu.kanade.translation.diagnostics.TranslationTraceStage
import eu.kanade.translation.translator.ProviderFailureException
import eu.kanade.translation.translator.TranslatorComputeClass
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
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
 *
 * T922 Phase 4: when a [TranslationScheduleTrace] is supplied, every page gets
 * one correlated run trace sharing the batch schedule, stage timers replace
 * the isolated legacy [BatchTranslationDiagnostics.timing] calls, lane
 * enter/exit transitions feed the schedule overlap accumulator, and every run
 * receives exactly one terminal outcome on every exit path (amendment §10.2).
 * With a null schedule everything below fails open at zero cost.
 */
class SequentialBatchCoordinator(
    private val nativeWorker: NativeLaneWorker,
    private val translatorWorker: TranslatorLaneWorker,
    private val renderJoin: RenderJoinWorker,
    private val listener: BatchScheduleListener = BatchScheduleListener.NOOP,
    private val awaitLeaseHandback: (suspend (String) -> Boolean)? = null,
    private val deferredPages: MutableMap<String, PageWriteOrigin?>? = null,
    private val scheduleTrace: TranslationScheduleTrace? = null,
) {

    /** Admitted-but-unclosed page runs, keyed by page key (§10.2 sweep registry). */
    private val activeRuns = ConcurrentHashMap<String, TranslationRunTrace>()

    suspend fun runPass1(
        orderedPages: List<PageKey>,
        computeClass: TranslatorComputeClass,
    ): BatchPass1Outcome = coroutineScope {
        if (orderedPages.isEmpty()) {
            return@coroutineScope BatchPass1Outcome(emptyList())
        }

        val remote = computeClass.mayOverlapNative
        // T922 Phase 4: bounded provider token for translate stage events.
        val providerLabel = if (remote) TranslationTraceProvider.REMOTE else TranslationTraceProvider.LOCAL
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
            // T922 Phase 4: one page run per OCR admission, sharing the batch
            // schedule. Registered for the terminal sweep before any work so a
            // cancelled admission can never strand a run (amendment §10.2).
            val runTrace = scheduleTrace?.let {
                TranslationPipelineDiagnostics.startRun(
                    schedule = it,
                    pageRaw = pageKey,
                    pageIndex = pageIndex,
                    plan = TranslationTracePlan.FRESH,
                )
            }
            if (runTrace != null) activeRuns[pageKey] = runTrace
            val traceContext: kotlin.coroutines.CoroutineContext =
                runTrace?.let { TranslationTrace.elementFor(it) } ?: kotlin.coroutines.EmptyCoroutineContext
            val ocrSpan = runTrace?.beginStage(TranslationTraceStage.OCR)
            val nativeLaneToken = scheduleTrace?.enterLane(TranslationTraceLane.NATIVE)
            var ocrOutcome = TranslationTraceOutcome.SUCCESS
            try {
                currentCoroutineContext().ensureActive()
                ref = withContext(traceContext) {
                    nativeWorker.runOcrStage(pageKey, pageIndex)
                }
            } catch (e: BatchPersistenceRejectedException) {
                ocrOutcome = TranslationTraceOutcome.FAILURE
                // Phase 4 review N1: settle the stage span BEFORE the run
                // terminal — finishStage drops post-terminal stage ends, so
                // ending the run first would drop this stage_end. The finally
                // below re-ends the already-settled span (CAS no-op).
                ocrSpan?.end(ocrOutcome, error = e)
                runTrace?.end(TranslationTraceOutcome.PERSISTENCE_REJECTED, error = e)
                if (runTrace != null) activeRuns.remove(pageKey, runTrace)
                throw e
            } catch (e: Exception) {
                if (e is CancellationException) {
                    ocrOutcome = TranslationTraceOutcome.CANCELLED
                    // Phase 4 review N1: span before run terminal (see above).
                    ocrSpan?.end(ocrOutcome, error = e)
                    runTrace?.end(TranslationTraceOutcome.CANCELLED, error = e)
                    if (runTrace != null) activeRuns.remove(pageKey, runTrace)
                    throw e
                }
                ocrOutcome = TranslationTraceOutcome.FAILURE
                // Phase 4 review N1: span before run terminal (see above).
                ocrSpan?.end(ocrOutcome, error = e)
                runTrace?.end(TranslationTraceOutcome.FAILURE, error = e)
                if (runTrace != null) activeRuns.remove(pageKey, runTrace)
                throw UnexpectedBatchStageException(pageKey, BatchDiagnosticStage.OCR)
            } finally {
                ocrSpan?.end(ocrOutcome)
                nativeLaneToken?.close()
                listener.ocrFinished(pageKey)
            }

            if (ref != null) {
                // Legacy stage_decision(EXECUTE) parity: the correlated
                // stage=ocr stage_end above carries the execution fact and the
                // run's item count.
                listener.ocrPublished(pageKey)
                needsTranslation += pageKey
            } else {
                // No work item this pass: lease-deferred, externally completed,
                // or a durable resume-skip. Terminal is skip; a later rescan
                // admission creates a fresh run.
                runTrace?.updatePlan(TranslationTracePlan.SKIP)
                runTrace?.end(TranslationTraceOutcome.SKIP)
                if (runTrace != null) activeRuns.remove(pageKey, runTrace)
            }
            val nowNanos = System.nanoTime()
            return ChunkPage(
                pageKey = pageKey,
                pageIndex = pageIndex,
                ref = ref,
                // A skipped page's run already reached its terminal (skip);
                // nulling the trace here prevents post-terminal stage spans
                // from the render join on a closed run.
                trace = if (ref != null) runTrace else null,
                ocrReadyAtNanos = nowNanos,
                translateReadyAtNanos = nowNanos,
            )
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
                    val renderRun = page.trace
                    // T922 Phase 4: render-join wait = both branch gates.
                    // Phase 4 review N2: the span settles in a finally so a
                    // cancellation while suspended on either gate cannot leave
                    // stage_start(render_join) without its stage_end.
                    val joinSpan = renderRun?.beginStage(TranslationTraceStage.RENDER_JOIN)
                    try {
                        nativeGate(page.pageKey).await()
                        translationGate(page.pageKey).await()
                        page.renderJoinReadyAtNanos = System.nanoTime()
                    } finally {
                        joinSpan?.end()
                    }
                    val nonRenderable = nonRenderablePages.contains(page.pageKey)
                    if (nonRenderable) {
                        renderJoin.awaitAndSettle(page.pageKey)
                        nativeDone.remove(page.pageKey)
                        translationDone.remove(page.pageKey)
                        return@forEach
                    }
                    listener.renderStarted(page.pageKey)
                    val startedAt = System.nanoTime()
                    val renderSpan = renderRun?.beginStage(TranslationTraceStage.RENDER)
                    val renderLaneToken = scheduleTrace?.enterLane(TranslationTraceLane.RENDER)
                    var renderOutcome = TranslationTraceOutcome.SUCCESS
                    try {
                        if (renderRun != null) {
                            withContext(TranslationTrace.elementFor(renderRun)) {
                                renderJoin.awaitAndRender(page.pageKey)
                            }
                        } else {
                            renderJoin.awaitAndRender(page.pageKey)
                        }
                    } catch (e: BatchPersistenceRejectedException) {
                        renderOutcome = TranslationTraceOutcome.FAILURE
                        renderSpan?.end(renderOutcome, error = e)
                        throw e
                    } catch (e: Exception) {
                        if (e is CancellationException) {
                            renderOutcome = TranslationTraceOutcome.CANCELLED
                            renderSpan?.end(renderOutcome, error = e)
                            throw e
                        }
                        renderOutcome = TranslationTraceOutcome.FAILURE
                        renderSpan?.end(renderOutcome, error = e)
                        throw UnexpectedBatchStageException(page.pageKey, BatchDiagnosticStage.RENDER)
                    } finally {
                        renderSpan?.end(renderOutcome)
                        renderLaneToken?.close()
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
                    // T922 Phase 4: the provider lane feeds the schedule
                    // overlap accumulator for the whole chunk translation.
                    val providerLaneToken = scheduleTrace?.enterLane(TranslationTraceLane.PROVIDER)
                    val workStartNanos = System.nanoTime()
                    try {
                        if (translatorWorker.usesChunkAdmission) {
                            chunk.forEach { page ->
                                if (page.ref != null) {
                                    listener.translationRequested(page.pageKey)
                                    // Per-page provider WAIT: the span opens at
                                    // envelope work start and carries the page's
                                    // planner/assembly wait as queueMs. The
                                    // envelope duration itself is measured ONCE
                                    // at schedule scope in the lane worker.
                                    page.translateSpan = page.trace?.beginStage(
                                        TranslationTraceStage.TRANSLATE,
                                        lane = TranslationTraceLane.PROVIDER,
                                        provider = providerLabel,
                                        items = page.ref.blockFingerprints.size,
                                    )
                                }
                            }
                            val outcome = normalizeCompletedOutcome(
                                try {
                                    translatorWorker.completeChunkOutcome(finalChunk)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    val pageKey = chunk.firstOrNull { it.ref != null }?.pageKey
                                    failureOutcome(pageKey, e, BatchDiagnosticStage.TRANSLATION)
                                },
                            )
                            chunk.forEach { page ->
                                if (page.ref != null) listener.translationFinished(page.pageKey)
                            }
                            settleTranslationBranches(outcome)
                            // Translate WAIT spans close here; the RUN terminals
                            // wait until the render join so the run's stage facts
                            // (render_join/render) precede its terminal summary.
                            settleTranslateWaits(chunk, outcome, workStartNanos)
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
                            val translateSpan = page.trace?.beginStage(
                                TranslationTraceStage.TRANSLATE,
                                lane = TranslationTraceLane.PROVIDER,
                                provider = providerLabel,
                                items = ref.blockFingerprints.size,
                            )
                            try {
                                val pageRun = page.trace
                                if (pageRun != null) {
                                    withContext(TranslationTrace.elementFor(pageRun)) {
                                        outcome = translatorWorker.translateOutcome(ref)
                                    }
                                } else {
                                    outcome = translatorWorker.translateOutcome(ref)
                                }
                            } catch (e: Exception) {
                                if (e is CancellationException) {
                                    translateSpan?.end(TranslationTraceOutcome.CANCELLED, error = e)
                                    throw e
                                }
                                outcome = failureOutcome(page.pageKey, e, BatchDiagnosticStage.TRANSLATION)
                            } finally {
                                translateSpan?.end(
                                    outcome = mappedRunTerminal(page.pageKey, outcome),
                                    queueMs = (startedAt - (page.translateReadyAtNanos ?: startedAt))
                                        .coerceAtLeast(0L) / 1_000_000L,
                                )
                                page.translateSpan = null
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
                                    failureOutcome(
                                        chunk.firstOrNull { it.ref != null }?.pageKey,
                                        e,
                                        BatchDiagnosticStage.TRANSLATION,
                                    )
                                },
                            )
                        }
                        settleTranslationBranches(outcome)
                        settleTranslateWaits(chunk, outcome)
                        outcome
                        }
                    } finally {
                        // Phase 4 review N2: on cancellation mid-envelope the
                        // normal settle path above is never reached; sweep any
                        // still-open per-page translate WAIT span here. On the
                        // normal path every span is already settled and nulled,
                        // so this is a no-op.
                        sweepOpenTranslateWaits(chunk)
                        providerLaneToken?.close()
                    }
                }
            } else {
                null
            }

            val unreleasedHandoffs = LinkedHashMap<String, OcrReadyPageRef>()
            chunk.forEach { page -> page.ref?.let { unreleasedHandoffs[page.pageKey] = it } }

            try {
                if (!remote) {
                    // T922 Phase 4: inline translation still owns the provider
                    // lane token for the accumulator.
                    val inlineProviderLaneToken = scheduleTrace?.enterLane(TranslationTraceLane.PROVIDER)
                    val inlineWorkStartNanos = System.nanoTime()
                    try {
                        if (translatorWorker.usesChunkAdmission) {
                            chunk.forEach { page ->
                                if (page.ref != null) {
                                    listener.translationRequested(page.pageKey)
                                    page.translateSpan = page.trace?.beginStage(
                                        TranslationTraceStage.TRANSLATE,
                                        lane = TranslationTraceLane.PROVIDER,
                                        provider = providerLabel,
                                        items = page.ref.blockFingerprints.size,
                                    )
                                }
                            }
                            val outcome = normalizeCompletedOutcome(
                                try {
                                    translatorWorker.completeChunkOutcome(finalChunk)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
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
                            // Translate WAIT spans close here; run terminals
                            // wait for the render join (see remote branch).
                            settleTranslateWaits(chunk, outcome, inlineWorkStartNanos)
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
                                val translateSpan = page.trace?.beginStage(
                                    TranslationTraceStage.TRANSLATE,
                                    lane = TranslationTraceLane.PROVIDER,
                                    provider = providerLabel,
                                    items = ref.blockFingerprints.size,
                                )
                                try {
                                    val pageRun = page.trace
                                    if (pageRun != null) {
                                        withContext(TranslationTrace.elementFor(pageRun)) {
                                            outcome = translatorWorker.translateOutcome(ref)
                                        }
                                    } else {
                                        outcome = translatorWorker.translateOutcome(ref)
                                    }
                                } catch (e: Exception) {
                                    if (e is CancellationException) {
                                        translateSpan?.end(TranslationTraceOutcome.CANCELLED, error = e)
                                        throw e
                                    }
                                    outcome = failureOutcome(page.pageKey, e, BatchDiagnosticStage.TRANSLATION)
                                } finally {
                                    translateSpan?.end(
                                        outcome = mappedRunTerminal(page.pageKey, outcome),
                                        queueMs = (startedAt - (page.translateReadyAtNanos ?: startedAt))
                                            .coerceAtLeast(0L) / 1_000_000L,
                                    )
                                    page.translateSpan = null
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
                            settleTranslateWaits(chunk, outcome)
                        }
                    } finally {
                        // Phase 4 review N2: see the remote branch finally —
                        // settle any still-open translate WAIT span the normal
                        // path did not reach (cancellation mid-envelope).
                        sweepOpenTranslateWaits(chunk)
                        inlineProviderLaneToken?.close()
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
                    val inpaintSpan = page.trace?.beginStage(TranslationTraceStage.INPAINT)
                    val nativeLaneToken = scheduleTrace?.enterLane(TranslationTraceLane.NATIVE)
                    var inpaintOutcome = TranslationTraceOutcome.SUCCESS
                    try {
                        val pageRun = page.trace
                        if (pageRun != null) {
                            withContext(TranslationTrace.elementFor(pageRun)) {
                                nativeWorker.runInpaintStage(page.pageKey, ref.nativeHandoff)
                            }
                        } else {
                            nativeWorker.runInpaintStage(page.pageKey, ref.nativeHandoff)
                        }
                    } catch (e: BatchPersistenceRejectedException) {
                        inpaintOutcome = TranslationTraceOutcome.FAILURE
                        inpaintSpan?.end(inpaintOutcome, error = e)
                        throw e
                    } catch (e: Exception) {
                        if (e is CancellationException) {
                            inpaintOutcome = TranslationTraceOutcome.CANCELLED
                            inpaintSpan?.end(inpaintOutcome, error = e)
                            throw e
                        }
                        inpaintOutcome = TranslationTraceOutcome.FAILURE
                        inpaintSpan?.end(inpaintOutcome, error = e)
                        throw UnexpectedBatchStageException(page.pageKey, BatchDiagnosticStage.INPAINT)
                    } finally {
                        inpaintSpan?.end(inpaintOutcome)
                        nativeLaneToken?.close()
                        listener.inpaintFinished(page.pageKey)
                        nativeWorker.releaseNativeHandoff(ref)
                        unreleasedHandoffs.remove(page.pageKey)
                        renderJoin.onNativeBranchDone(page.pageKey)
                        nativeGate(page.pageKey).complete(Unit)
                    }
                }

                val translationOutcome = translationJob?.await() ?: inlineTranslationOutcome
                renderJob.await()
                // T922 Phase 4: any run still open after both branches joined
                // (e.g. a page whose translation branch was skipped) settles
                // here against the chunk outcome.
                settleChunkRuns(chunk, translationOutcome)
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

                    // T922 Phase 4: translation/inpaint-ready timestamp = lane
                    // admission completion (planner buffering for AI lanes).
                    entry.translateReadyAtNanos = System.nanoTime()
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

            // T917 D3 defer-and-rescan: pages whose stage lease was DENIED
            // mid-pass (another origin — e.g. a manual reader tap — owned the
            // page) are re-run WITHIN the same pass once their lease is handed
            // back, so a completed pass never strands a reader-owned page: the
            // pass can only finish after every deferred page reached a terminal
            // state. Runs ONLY on the COMPLETED path — a paused / failed /
            // persistence-rejected pass is never extended. On re-run the
            // worker's externally-completed gate routes the page into its
            // SKIP_ALL branch (terminal state already on disk), so a rescan
            // never repeats the provider call the other origin already paid.
            // Never runs on stop paths, and resume-skip SKIP_ALL pages (null
            // refs without a denial) are never recorded as deferrals.
            if (stoppingOutcome == null && deferredPages?.isNotEmpty() == true) {
                var rescanAttempt = 0
                while (!deferredPages.isEmpty() &&
                    stoppingOutcome == null &&
                    rescanAttempt < RESCAN_MAX_ATTEMPTS
                ) {
                    rescanAttempt++
                    currentCoroutineContext().ensureActive()
                    for (pageKey in deferredPages.keys.toList()) {
                        currentCoroutineContext().ensureActive()
                        val pageIndex = orderedPages.firstOrNull { it.first == pageKey }?.second ?: 0
                        val handedBack = awaitLeaseHandback?.invoke(pageKey) ?: true
                        if (!handedBack) {
                            // The lease never came back within the bound; the
                            // reconciler reports the page honestly.
                            continue
                        }
                        val entry = runOcr(pageKey to pageIndex)
                        val rescanOutcome = processChunk(listOf(entry), finalChunk = true)
                        completedPassPageKeys += rescanOutcome.completedPageKeysForPass()
                        if (rescanOutcome !is ChunkCompletionOutcome.Completed) {
                            stoppingOutcome = rescanOutcome
                        }
                    }
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
            // T922 Phase 4: anchor terminal + sweep of the co-chunk runs the
            // rejection cut short (replaces the legacy stage_failure line —
            // the run_end outcome + schedule summary carry the same facts).
            sweepUnsettledRuns(
                anchor = e.pageKey,
                anchorTerminal = TranslationTraceOutcome.PERSISTENCE_REJECTED,
                error = e,
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
            sweepUnsettledRuns(
                anchor = e.pageKey,
                anchorTerminal = TranslationTraceOutcome.FAILURE,
                error = null,
            )
            return@coroutineScope outcome
        } finally {
            retainedProbe?.ref?.let(nativeWorker::releaseNativeHandoff)
            retainedProbe = null
            // T922 Phase 4 (§10.2): unwind sweep — a cancelled pass still
            // gives every open run exactly one terminal.
            sweepUnsettledRuns(anchor = null, anchorTerminal = TranslationTraceOutcome.CANCELLED)
        }
    }

    // ---- T922 Phase 4: page-run terminal ownership (amendment §10.2) ----

    /** Typed terminal for one page under a chunk outcome. */
    private fun mappedRunTerminal(
        pageKey: String,
        outcome: ChunkCompletionOutcome,
    ): TranslationTraceOutcome {
        if (pageKey in outcome.completedPageKeysForPass()) return TranslationTraceOutcome.SUCCESS
        return when (outcome) {
            is ChunkCompletionOutcome.Paused -> TranslationTraceOutcome.PAUSE
            is ChunkCompletionOutcome.PersistenceRejected ->
                if (pageKey == outcome.anchorPageKey) {
                    TranslationTraceOutcome.PERSISTENCE_REJECTED
                } else {
                    TranslationTraceOutcome.CANCELLED
                }
            is ChunkCompletionOutcome.Failed ->
                if (pageKey == outcome.anchorPageKey || pageKey in outcome.terminalPageKeys) {
                    TranslationTraceOutcome.FAILURE
                } else {
                    TranslationTraceOutcome.CANCELLED
                }
            is ChunkCompletionOutcome.Unexpected ->
                if (pageKey == outcome.anchorPageKey || pageKey in outcome.terminalPageKeys) {
                    TranslationTraceOutcome.FAILURE
                } else {
                    TranslationTraceOutcome.CANCELLED
                }
            is ChunkCompletionOutcome.Completed -> TranslationTraceOutcome.CANCELLED
        }
    }

    /**
     * Phase 4 review N2: settles any still-open per-page translate WAIT span
     * when the translation branch unwinds without reaching
     * [settleTranslateWaits] (e.g. cancellation while suspended inside the
     * envelope call). On the normal path every span is already settled and
     * nulled, so this is a no-op. Never throws, never suspends.
     */
    private fun sweepOpenTranslateWaits(chunk: List<ChunkPage>) {
        for (page in chunk) {
            page.translateSpan?.let { span ->
                span.end(TranslationTraceOutcome.CANCELLED)
                page.translateSpan = null
            }
        }
    }

    /**
     * Settles one chunk page's open translate WAIT span exactly once when the
     * translation branch completes. Run TERMINALS are deliberately NOT closed
     * here: the render join still has to measure render_join/render on the
     * live run, and the chronological order stage_end...→run_end must hold.
     * The run terminal itself is issued by [settleChunkRuns] after the render
     * job joins (or by a sweep on unwind).
     */
    private fun settleTranslateWaits(
        chunk: List<ChunkPage>,
        outcome: ChunkCompletionOutcome,
        workStartNanos: Long? = null,
    ) {
        for (page in chunk) {
            val span = page.translateSpan ?: continue
            val queueMs = if (workStartNanos != null) {
                ((workStartNanos - (page.translateReadyAtNanos ?: workStartNanos)) / 1_000_000L)
                    .coerceAtLeast(0L)
            } else {
                0L
            }
            span.end(
                outcome = mappedRunTerminal(page.pageKey, outcome),
                queueMs = queueMs,
            )
            page.translateSpan = null
        }
    }

    /**
     * Settles every chunk page's run (and any still-open translate wait span)
     * exactly once after the chunk outcome AND the render join are complete.
     * The translate span's queueMs carries the planner/assembly wait measured
     * from the page's translation-ready timestamp to the envelope work start —
     * the envelope DURATION itself is never attributed per page (plan §9).
     */
    private fun settleChunkRuns(
        chunk: List<ChunkPage>,
        outcome: ChunkCompletionOutcome,
        workStartNanos: Long? = null,
    ) {
        for (page in chunk) {
            val run = page.trace ?: continue
            page.translateSpan?.let { span ->
                val queueMs = if (workStartNanos != null) {
                    ((workStartNanos - (page.translateReadyAtNanos ?: workStartNanos)) / 1_000_000L)
                        .coerceAtLeast(0L)
                } else {
                    0L
                }
                span.end(
                    outcome = mappedRunTerminal(page.pageKey, outcome),
                    queueMs = queueMs,
                )
                page.translateSpan = null
            }
            run.end(mappedRunTerminal(page.pageKey, outcome))
            page.trace = null
            activeRuns.remove(page.pageKey, run)
        }
    }

    /**
     * Sweeps runs left open by an unwinding pass (unexpected stage exception
     * or cancellation). The anchor page receives [anchorTerminal]; every
     * other open run is cancelled — the pass stopped before reaching it.
     */
    private fun sweepUnsettledRuns(
        anchor: String?,
        anchorTerminal: TranslationTraceOutcome,
        error: Throwable? = null,
    ) {
        for ((pageKey, run) in activeRuns) {
            if (pageKey == anchor) {
                run.end(anchorTerminal, error = error)
            } else {
                run.end(TranslationTraceOutcome.CANCELLED)
            }
            activeRuns.remove(pageKey, run)
        }
    }

    private fun ChunkCompletionOutcome.completedPageKeysForPass(): Set<String> = when (this) {
        is ChunkCompletionOutcome.Completed -> completedPageKeys
        is ChunkCompletionOutcome.Paused -> completedPageKeys
        is ChunkCompletionOutcome.Failed -> completedPageKeys
        is ChunkCompletionOutcome.Unexpected -> completedPageKeys
        is ChunkCompletionOutcome.PersistenceRejected -> completedPageKeys
    }

    /**
     * T922 Phase 4: one chunk page's live trace state. Carries the page run
     * plus the contract timestamps (OCR-ready, translation/inpaint-ready,
     * render-join-ready) used for queue attribution; the open translate wait
     * span is settled by [settleChunkRuns].
     */
    private data class ChunkPage(
        val pageKey: String,
        val pageIndex: Int,
        val ref: OcrReadyPageRef?,
        var trace: TranslationRunTrace? = null,
        val ocrReadyAtNanos: Long? = null,
        var translateReadyAtNanos: Long? = null,
        var renderJoinReadyAtNanos: Long? = null,
        var translateSpan: eu.kanade.translation.diagnostics.TranslationStageSpan? = null,
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

        /**
         * T917 D3: upper bound on same-pass rescan sweeps of deferred pages, so
         * a page that keeps getting re-denied cannot loop the pass forever.
         */
        const val RESCAN_MAX_ATTEMPTS = 2
    }
}
