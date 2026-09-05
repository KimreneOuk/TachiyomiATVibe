package eu.kanade.translation.pipeline.batch

import android.graphics.Bitmap
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.translation.ChapterTranslationStore
import eu.kanade.translation.LayoutFailureException
import eu.kanade.translation.RenderBlockPatch
import eu.kanade.translation.RenderStagePatch
import eu.kanade.translation.StagePatchResult
import eu.kanade.translation.ocrBlockFingerprints
import eu.kanade.translation.diagnostics.TranslationTrace
import eu.kanade.translation.diagnostics.TranslationTraceLane
import eu.kanade.translation.diagnostics.TranslationTraceOutcome
import eu.kanade.translation.diagnostics.TranslationTraceStage
import eu.kanade.translation.model.BatchExpectedFingerprints
import eu.kanade.translation.model.BatchStage
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.isTextlessTerminal
import eu.kanade.translation.model.recordAttemptFailure
import eu.kanade.translation.model.stableFingerprint
import eu.kanade.translation.rendering.RenderColorEstimator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import java.util.concurrent.ConcurrentHashMap

/**
 * T909 Phase 20.4: the batch render join moved verbatim from
 * `TranslationPipeline.translateBatch` (T909 phase 20).
 *
 * Render join: per-page join of the translation result with its inpaint/render
 * prerequisites. tryRender is idempotent (READY short-circuit) so it is safe to
 * call both at chunk-completion (AI) and here; the per-page render mutex keeps
 * it serialized. This is the join the coordinator awaits for each page.
 * Bitmap recycle sites stay with tryRender.
 */
internal class BatchRenderJoin(
    private val store: ChapterTranslationStore,
    private val manga: Manga,
    private val chapter: Chapter,
    private val source: HttpSource,
    private val tracker: TranslationBatchProgressTracker?,
    private val translationRegistry: ConcurrentHashMap<String, PageTranslation>,
    private val heldBitmapRegistry: HeldBitmapRegistry,
    private val resumePlanner: BatchResumePlanner,
    private val writeGate: BatchWriteGate,
    private val expectedBatchFingerprints: BatchExpectedFingerprints,
    private val loadPersistedCleanedBitmapFn: suspend (Manga, Chapter, HttpSource, String) -> Bitmap?,
    private val deleteRetiredCleanedFileFn: suspend (Manga, Chapter, HttpSource, String, ChapterTranslationStore) -> Unit,
    private val abortBatchCandidateFn: suspend (String, String) -> Unit,
) : RenderJoinWorker {

    private val renderMutexes = ConcurrentHashMap<String, Mutex>()

    private val nativeRenderSignals = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val translationRenderSignals = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    // Same-name wiring for the injected collaborators: the moved bodies call
    // these as plain named functions / property-style reads.
    private fun plannedRenderNeedsWork(pageKey: String): Boolean =
        resumePlanner.plannedRenderNeedsWork(pageKey)

    private fun translationFailureFence(pageKey: String): Boolean =
        resumePlanner.translationFailureFence(pageKey)

    private suspend fun releaseBatchLease(pageKey: String) =
        writeGate.releaseBatchLease(pageKey)

    private suspend fun guardedBatchUpdate(
        pageKey: String,
        description: String,
        stage: BatchStage?,
        update: (PageTranslation?) -> PageTranslation,
    ) = writeGate.guardedBatchUpdate(pageKey, description, stage, update)

    private suspend fun persistBatchPageWithOomRecovery(
        pageKey: String,
        pageTranslation: PageTranslation,
    ) = writeGate.persistBatchPageWithOomRecovery(pageKey, pageTranslation)

    private suspend fun loadPersistedCleanedBitmap(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        cleanedImageName: String,
    ): Bitmap? = loadPersistedCleanedBitmapFn(manga, chapter, source, cleanedImageName)

    private suspend fun deleteRetiredCleanedFile(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        pageKey: String,
        store: ChapterTranslationStore,
    ) = deleteRetiredCleanedFileFn(manga, chapter, source, pageKey, store)

    private suspend fun abortBatchCandidate(pageKey: String, reason: String) =
        abortBatchCandidateFn(pageKey, reason)

    suspend fun tryRender(pageKey: String) {
        val mutex = renderMutexes.computeIfAbsent(pageKey) { Mutex() }
        mutex.withLock {
            val page = translationRegistry[pageKey] ?: return@withLock
            if (page.ocrStatus == StageStatus.FAILED ||
                page.translationStatus == StageStatus.FAILED ||
                page.inpaintStatus == StageStatus.FAILED ||
                page.renderStatus == StageStatus.FAILED
            ) {
                abortBatchCandidate(pageKey, page.activeError ?: "terminal stage failure")
                return@withLock
            }
            if (page.isTextlessTerminal) {
                tracker?.markTranslateSkipped(pageKey)
                tracker?.markRenderSkipped(pageKey)
                heldBitmapRegistry.recycleHeld(pageKey)
                translationRegistry.remove(pageKey)
                releaseBatchLease(pageKey)
                return@withLock
            }
            if (page.renderStatus == StageStatus.READY && !plannedRenderNeedsWork(pageKey)) {
                heldBitmapRegistry.recycleHeld(pageKey)
                translationRegistry.remove(pageKey)
                releaseBatchLease(pageKey)
                tracker?.markRenderDone(pageKey)
                return@withLock
            }
            val status = page.translationStatus
            if (status != StageStatus.READY && status != StageStatus.PARTIAL) {
                if (status == StageStatus.FAILED) {
                    heldBitmapRegistry.recycleHeld(pageKey)
                    translationRegistry.remove(pageKey)
                    releaseBatchLease(pageKey)
                }
                return@withLock
            }
            val inpaintStatus = page.inpaintStatus
            if (inpaintStatus != StageStatus.READY && inpaintStatus != StageStatus.PARTIAL && inpaintStatus != StageStatus.TEXTLESS) {
                if (inpaintStatus == StageStatus.FAILED) {
                    heldBitmapRegistry.recycleHeld(pageKey)
                    translationRegistry.remove(pageKey)
                    releaseBatchLease(pageKey)
                }
                // Inpaint isn't ready yet, wait for inpaintWorker to call tryRender
                return@withLock
            }
            // READY/PARTIAL -> render. Consume the held bitmap on the happy path
            // (no disk reload); spill/SKIP_ALL pages reload .cleaned.jpg instead.
            val held = heldBitmapRegistry.bitmapRegistry.remove(pageKey)
            if (held != null) {
                heldBitmapRegistry.heldBitmapBytes.addAndGet(-held.byteCount.toLong())
                heldBitmapRegistry.countSlots.release()
            }
            val bitmap = held ?: page.cleanedImageName?.let { loadPersistedCleanedBitmap(manga, chapter, source, it) }
            if (bitmap == null && inpaintStatus != StageStatus.TEXTLESS) {
                page.inpaintStatus = StageStatus.FAILED
                page.renderStatus = StageStatus.FAILED
                page.recordAttemptFailure()
                page.errorMessage = "Cleaned image is missing or unreadable; retry inpainting"
                tracker?.markInpaintFailed(pageKey, page.errorMessage!!)
                tracker?.markRenderFailed(pageKey, page.errorMessage!!)
                val failurePersisted = persistBatchPageWithOomRecovery(pageKey, page)
                abortBatchCandidate(pageKey, page.errorMessage!!)
                if (failurePersisted is ChapterTranslationStore.PatchResult.Rejected) {
                    throw BatchPersistenceRejectedException(
                        pageKey = pageKey,
                        stage = BatchDiagnosticStage.RENDER,
                    )
                }
                return@withLock
            }
            var renderPersisted = false
            try {
                tracker?.markRenderRunning(pageKey)
                val running = guardedBatchUpdate(pageKey, "batch render running", BatchStage.LAYOUT) {
                    (it ?: page).apply {
                        renderStatus = StageStatus.RUNNING
                        updatedAt = System.currentTimeMillis()
                    }
                }
                if (running is ChapterTranslationStore.PatchResult.Rejected) {
                    logcat(LogPriority.WARN) {
                        "TachiyomiAT batch render running rejected (stale writer): " +
                            "pageKey=$pageKey reason=${running.reason}"
                    }
                    abortBatchCandidate(pageKey, "render admission rejected: ${running.reason}")
                    throw BatchPersistenceRejectedException(
                        pageKey = pageKey,
                        stage = BatchDiagnosticStage.RENDER,
                    )
                }
                val renderInput = store.snapshot(pageKey)
                // T922 Phase 4: layout stage outcome for the page run. The
                // span settles even when the estimator throws (layout failure
                // path below keeps its existing handling).
                val layoutSpan = TranslationTrace.beginStage(
                    TranslationTraceStage.LAYOUT,
                    lane = TranslationTraceLane.RENDER,
                )
                val patch: RenderStagePatch = try {
                    RenderColorEstimator.recomputeFor(bitmap, page.blocks)
                    layoutSpan.end(TranslationTraceOutcome.SUCCESS)
                    page.renderStatus = StageStatus.READY
                    page.updatedAt = System.currentTimeMillis()
                    val renderSpan = TranslationTrace.beginStage(
                        TranslationTraceStage.RENDER,
                        lane = TranslationTraceLane.RENDER,
                    )
                    try {
                        RenderStagePatch(
                    pageKey = pageKey,
                    generation = renderInput.generation,
                    expectedPageVersion = renderInput.pageVersion,
                    expectedLeaseToken = renderInput.leaseToken,
                    expectedCleanedImageName = renderInput.page?.cleanedImageName ?: "",
                    expectedInpaintRevision = renderInput.page?.inpaintRevision ?: 0,
                    expectedOcrBlockFingerprints = renderInput.page?.ocrBlockFingerprints().orEmpty(),
                    expectedCandidateGenerationId = renderInput.candidateGenerationId,
                    expectedDependencyFingerprint = renderInput.dependencyFingerprint,
                    expectedArtifactPageVersion = renderInput.artifactPageVersion,
                    layoutFingerprint = expectedBatchFingerprints.layout,
                    blocks = page.blocks.mapIndexed { index, block ->
                        RenderBlockPatch(
                            blockIndex = index,
                            expectedBlockFingerprint = renderInput.page?.blocks?.getOrNull(index)?.stableFingerprint() ?: "",
                            textColor = block.textColor,
                            strokeColor = block.strokeColor,
                            strokeWidth = block.strokeWidth,
                        )
                    },
                    renderStatus = StageStatus.READY,
                    )
                    } finally {
                        renderSpan.end()
                    }
                } catch (t: Throwable) {
                    layoutSpan.end(
                        if (t is CancellationException) TranslationTraceOutcome.CANCELLED else TranslationTraceOutcome.FAILURE,
                        error = t,
                    )
                    throw t
                }
                // T922 Phase 4: store commit outcome for the render stage patch.
                // Phase 4 review N3: the span settles in try/catch so a throw
                // from mergeRender (cancellation while suspended, store error)
                // cannot leave stage_start(store_commit) dangling.
                val commitSpan = TranslationTrace.beginStage(
                    TranslationTraceStage.STORE_COMMIT,
                    lane = TranslationTraceLane.STORAGE,
                )
                val renderResult = try {
                    val result = store.mergeRender(patch)
                    commitSpan.end(
                        if (result is StagePatchResult.Accepted) {
                            TranslationTraceOutcome.SUCCESS
                        } else {
                            TranslationTraceOutcome.FAILURE
                        },
                    )
                    result
                } catch (t: Throwable) {
                    commitSpan.end(
                        if (t is CancellationException) TranslationTraceOutcome.CANCELLED else TranslationTraceOutcome.FAILURE,
                        error = t,
                    )
                    throw t
                }
                renderPersisted = renderResult is StagePatchResult.Accepted
                if (renderResult is StagePatchResult.Rejected) {
                    abortBatchCandidate(pageKey, "render commit rejected: ${renderResult.reason}")
                    throw BatchPersistenceRejectedException(
                        pageKey = pageKey,
                        stage = BatchDiagnosticStage.RENDER,
                    )
                }
                if (renderPersisted) {
                    // A newer committed display bundle may have just
                    // promoted; the file it superseded is now deletable.
                    deleteRetiredCleanedFile(manga, chapter, source, pageKey, store)
                }
                tracker?.markRenderDone(pageKey)
            } catch (e: BatchPersistenceRejectedException) {
                throw e
            } catch (e: LayoutFailureException) {
                page.renderStatus = StageStatus.FAILED
                page.recordAttemptFailure()
                val msg = if (e.blockIds.isNotEmpty()) "${e.message} (blocks: ${e.blockIds.joinToString()})" else e.message
                page.errorMessage = msg
                tracker?.markRenderFailed(pageKey, msg ?: "Layout failed")
                logcat(LogPriority.ERROR, e) { "TachiyomiAT batch render layout failed: $pageKey blocks=${e.blockIds}" }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                page.renderStatus = StageStatus.FAILED
                page.recordAttemptFailure()
                page.errorMessage = e.message
                tracker?.markRenderFailed(pageKey, e.message ?: e::class.java.simpleName)
                logcat(LogPriority.ERROR, e) { "TachiyomiAT batch render failed: $pageKey" }
            } finally {
                try {
                    bitmap?.recycle()
                } catch (_: Exception) {}
                page.cleanedBitmap = null
                if (!renderPersisted) {
                    val failurePersisted = persistBatchPageWithOomRecovery(pageKey, page)
                    abortBatchCandidate(pageKey, page.activeError ?: "render did not commit")
                    if (failurePersisted is ChapterTranslationStore.PatchResult.Rejected) {
                        throw BatchPersistenceRejectedException(
                            pageKey = pageKey,
                            stage = BatchDiagnosticStage.RENDER,
                        )
                    }
                } else {
                    translationRegistry.remove(pageKey)
                    releaseBatchLease(pageKey)
                }
            }
        }
    }

    fun signalFor(
        signals: ConcurrentHashMap<String, CompletableDeferred<Unit>>,
        pageKey: String,
    ): CompletableDeferred<Unit> = signals.getOrPut(pageKey) { CompletableDeferred() }

    override fun onNativeBranchDone(pageKey: String) {
        signalFor(nativeRenderSignals, pageKey).complete(Unit)
    }

    override fun onTranslationBranchDone(pageKey: String) {
        signalFor(translationRenderSignals, pageKey).complete(Unit)
    }

    override suspend fun awaitAndRender(pageKey: String) {
        signalFor(nativeRenderSignals, pageKey).await()
        signalFor(translationRenderSignals, pageKey).await()
        // A resumed retryable/terminal translation candidate owns no
        // display publication. Its committed bundle remains the reader
        // authority until an eligible retry succeeds, so never route a
        // failed candidate through the normal render path.
        if (!translationFailureFence(pageKey)) {
            tryRender(pageKey)
        }
        nativeRenderSignals.remove(pageKey)
        translationRenderSignals.remove(pageKey)
    }

    override suspend fun awaitAndSettle(pageKey: String) {
        signalFor(nativeRenderSignals, pageKey).await()
        signalFor(translationRenderSignals, pageKey).await()
        // A paused/terminal anchor retains the prior committed
        // display. Do not invoke tryRender on its provisional
        // candidate or overwrite that display pointer.
        nativeRenderSignals.remove(pageKey)
        translationRenderSignals.remove(pageKey)
    }
}
