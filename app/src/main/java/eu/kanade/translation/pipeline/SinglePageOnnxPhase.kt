package eu.kanade.translation.pipeline

import android.content.Context
import android.graphics.Bitmap
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.translation.storage.ChapterTranslationStore
import eu.kanade.translation.pipeline.OcrStagePatch
import eu.kanade.translation.pipeline.StagePatchResult
import eu.kanade.translation.pipeline.batch.BatchDiagnosticStage
import eu.kanade.translation.pipeline.batch.BatchPersistenceRejectedException
import eu.kanade.translation.data.TranslationProvider
import eu.kanade.translation.diagnostics.TranslationTrace
import eu.kanade.translation.diagnostics.TranslationTraceOutcome
import eu.kanade.translation.diagnostics.TranslationTracePlan
import eu.kanade.translation.diagnostics.TranslationTraceStage
import eu.kanade.translation.pipeline.finalizePostOcrStage
import eu.kanade.translation.inpainting.InpaintingMode
import eu.kanade.translation.model.BatchExpectedFingerprints
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.isCleanedImageReady
import eu.kanade.translation.pipeline.ocrBlockFingerprints
import eu.kanade.translation.model.prepareForcedRetry
import eu.kanade.translation.model.recordAttemptFailure
import eu.kanade.translation.model.resetAttemptCharge
import eu.kanade.translation.ocr.TextRecognizerLanguage
import eu.kanade.translation.rendering.RenderColorEstimator
import eu.kanade.translation.scheduling.TranslationStageEvent
import eu.kanade.translation.scheduling.TranslationStageListener
import eu.kanade.translation.scheduling.TranslationStreamRegistry
import eu.kanade.translation.translator.TextTranslatorLanguage
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.translation.TranslationPreferences
import tachiyomi.domain.translation.pools.BitmapPool
import java.io.InputStream

/**
 * Permit-held ONNX phase of the reader single-page path moved from
 * `TranslationPipeline` (T909 Phase 14), with [OnnxPhaseResult], the resume
 * paths (`renderResumedPage`, `resumeInpaintAndRender`), the downscaled-inpaint
 * retry, and the batch native stages (`analyzePage`, `inpaintPage`,
 * `processSinglePage`). Bitmap recycle/ownership points moved verbatim:
 * recycle-in-finally discipline, CancellationException rethrow at every catch,
 * and the caller's held-bitmap registry balance are unchanged.
 */
internal class SinglePageOnnxPhase(
    private val context: Context,
    private val translationPreferences: TranslationPreferences,
    private val provider: TranslationProvider,
    private val downloadProvider: DownloadProvider,
    private val streamRegistry: TranslationStreamRegistry,
    private val engines: EngineLane,
    private val cleanedPublication: CleanedPublication,
    private val pageStoreWriter: PageStoreWriter,
    private val activeStoreResolverProvider: () -> ((Translation) -> ChapterTranslationStore?)?,
    // Shared with the pipeline so rebuild ordering against the caller's
    // native-quarantine admission is exactly the pre-move mutex.
    private val engineRebuildMutex: Mutex,
) {

    // Same-name reads the moved bodies use; resolved through the shared lane
    // and writer so engine rebuilds and store writes behave identically.
    private val activeStoreResolver get() = activeStoreResolverProvider()

    private val recognitionEngine get() = engines.recognitionEngine

    private val currentInpaintingMode get() = engines.currentInpaintingMode

    private fun inpaintingModeFromPref(): InpaintingMode = engines.inpaintingModeFromPref()

    private suspend fun ensureEnginesBuiltFor(
        fromLang: TextRecognizerLanguage,
        toLang: TextTranslatorLanguage,
    ) {
        engines.ensureEnginesBuiltFor(fromLang, toLang)
    }

    @Volatile
    private var consecutiveOomCount = 0

    private fun peekReaderPageStream(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        pageKey: String,
    ): (() -> InputStream)? = pageStoreWriter.peekReaderPageStream(manga, chapter, source, pageKey)

    private fun createFailedPagePlaceholder(
        fileName: String,
        errorMessage: String?,
        imgWidth: Float = 0f,
        imgHeight: Float = 0f,
        originalImgWidth: Float = 0f,
        originalImgHeight: Float = 0f,
        decodeSampleSize: Int = 1,
        retryCount: Int = 0,
        attemptCount: Int = 1,
    ): PageTranslation = pageStoreWriter.createFailedPagePlaceholder(
        fileName,
        errorMessage,
        imgWidth,
        imgHeight,
        originalImgWidth,
        originalImgHeight,
        decodeSampleSize,
        retryCount,
        attemptCount,
    )

    private suspend fun updatePageFromCurrentSnapshot(
        store: ChapterTranslationStore,
        pageKey: String,
        description: String,
        expectedGeneration: Long? = null,
        update: (PageTranslation?) -> PageTranslation,
    ): ChapterTranslationStore.PatchResult =
        pageStoreWriter.updatePageFromCurrentSnapshot(store, pageKey, description, expectedGeneration, update)

    private suspend fun persistPageWithOomRecovery(
        store: ChapterTranslationStore,
        fileName: String,
        pageTranslation: PageTranslation,
        expectedPrecondition: ChapterTranslationStore.PatchPrecondition? = null,
    ): ChapterTranslationStore.PatchResult =
        pageStoreWriter.persistPageWithOomRecovery(store, fileName, pageTranslation, expectedPrecondition)

    private suspend fun loadPersistedCleanedBitmap(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        cleanedImageName: String,
    ): Bitmap? = cleanedPublication.loadPersistedCleanedBitmap(manga, chapter, source, cleanedImageName)

    private suspend fun persistCleanedBitmap(
        pageTranslation: PageTranslation,
        cleanedBitmap: Bitmap,
        companionDir: UniFile?,
        pageKey: String,
        chapterName: String,
        store: ChapterTranslationStore,
        sourceId: Long,
        mangaId: Long,
        chapterId: Long?,
        expectedPrecondition: ChapterTranslationStore.PatchPrecondition? = null,
    ): ChapterTranslationStore.PageSnapshot? = cleanedPublication.persistCleanedBitmap(
        pageTranslation,
        cleanedBitmap,
        companionDir,
        pageKey,
        chapterName,
        store,
        sourceId,
        mangaId,
        chapterId,
        expectedPrecondition,
    )

    private fun decodePageBitmapAtSize(fileName: String, sampleSize: Int, streams: List<Pair<String, () -> InputStream>>): Bitmap? =
        PageDecode.decodePageBitmapAtSize(fileName, sampleSize, streams)

    /**
     * T922 Phase 3: correlated `source_decode` stage boundary. The run arrives
     * through the installed [TranslationTrace] element; outside a traced
     * coroutine this is a fail-open NO_OP span. Both call sites (fresh decode
     * and inpaint-resume re-decode) are measured; repeated intervals accumulate
     * in the run's stage map.
     */
    private suspend fun decodePageBitmapForTranslation(fileName: String, streamFn: () -> InputStream): DecodedPage? {
        val decodeSpan = TranslationTrace.beginStage(TranslationTraceStage.SOURCE_DECODE)
        val decoded = try {
            PageDecode.decodePageBitmapForTranslation(context, { recognitionEngine }, fileName, streamFn)
        } catch (t: Throwable) {
            decodeSpan.end(TranslationTraceOutcome.FAILURE, error = t)
            throw t
        }
        decodeSpan.end(
            if (decoded == null) TranslationTraceOutcome.FAILURE else TranslationTraceOutcome.SUCCESS,
        )
        return decoded
    }

    private fun preflightAnalyzeGate(bitmap: Bitmap, fileName: String) {
        MemoryGovernance.preflightAnalyzeGate({ recognitionEngine }, bitmap, fileName)
    }

    private fun preflightInpaintGate(bitmap: Bitmap, fileName: String) {
        MemoryGovernance.preflightInpaintGate({ recognitionEngine }, bitmap, fileName)
    }

    private fun forceReleaseNativeBuffers() {
        MemoryGovernance.forceReleaseNativeBuffers { recognitionEngine }
    }

    private fun handleCriticalTranslationOom(stage: String, oom: OutOfMemoryError) {
        MemoryGovernance.handleCriticalTranslationOom(this::forceReleaseNativeBuffers, stage, oom)
    }

    private fun getChapterPages(chapterPath: UniFile): List<Pair<String, () -> InputStream>> =
        eu.kanade.translation.util.getChapterPages(context, chapterPath)

    /**
     * Phase ONNX of the reader single-page path: runs under native quarantine.
     *
     * Sets up the store, resolves the page stream, decodes the bitmap, runs
     * [processSinglePage] (fused detect+OCR+inpaint), and persists .cleaned.
     * Returns [OnnxPhaseResult] with the [PageTranslation] (its cleanedBitmap
     * alive) for the caller to continue with HTTP translate + render OUTSIDE the
     * permit — the key asymmetry: ONNX for the next prefetch page overlaps this
     * page's network call.
     *
     * Returns null for resume/completed/error paths where the page is already
     * handled (no further work needed). The caller releases the permit after this
     * returns regardless of the result.
     *
     * T917 D11 (phase4-design §4.4): when the caller supplies
     * [deferredPublications], the resume paths' storage publication
     * ([resumeInpaintAndRender]'s cleaned-image persist + render tail, the
     * [renderResumedPage]-only resume, and the `finally` store flush + stream
     * clear) is ENQUEUED there instead of running under the permit — the
     * boundary drains it after the permit is released. Null (legacy callers)
     * keeps the pre-D11 inline behavior.
     */
    suspend fun translateSinglePageOnnx(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        pageKey: String,
        readerStreamFn: (() -> InputStream)? = null,
        force: Boolean = true,
        stageListener: TranslationStageListener? = null,
        deferredPublications: DeferredPagePublications? = null,
    ): OnnxPhaseResult? {
        val streamFromReader = readerStreamFn ?: peekReaderPageStream(manga, chapter, source, pageKey)
        val fromLang = TextRecognizerLanguage.fromPref(translationPreferences.translateFromLanguage())
        val toLang = TextTranslatorLanguage.fromPref(translationPreferences.translateToLanguage())
        val syntheticTranslation = Translation(source, manga, chapter, fromLang, toLang)
        syntheticTranslation.status = Translation.State.TRANSLATING

        // The caller already owns native quarantine. Rebuild is therefore ordered
        // after any timed-out predecessor's real exit and cannot race closeEngines.
        engineRebuildMutex.withLock {
            ensureEnginesBuiltFor(fromLang, toLang)
        }

        var ownStore: ChapterTranslationStore? = null
        val store = activeStoreResolver?.invoke(syntheticTranslation).also {
            ownStore = if (it == null) null else syntheticTranslation.let { _ -> it }
        } ?: run {
            val mangaDir = provider.getMangaDir(manga.title, source)
            val saveFile = provider.getTranslationFileName(chapter.name, chapter.scanlator)
            val parent = mangaDir ?: return null
            ChapterTranslationStore.openArtifact(parent, saveFile).also { ownStore = it }
        }

        // Cleanup runs here for resume/error paths; deferred to the fresh path
        // where the cleaned bitmap crosses the permit boundary.
        var needsHttpRender = false
        try {
            val resumeTranslation = store.state.value[pageKey]?.copyForResume()
            val desiredModeName = inpaintingModeFromPref().name
            val modeMatches = resumeTranslation?.inpaintingModeUsed == null || resumeTranslation.inpaintingModeUsed == desiredModeName
            val adjustedResume = if (resumeTranslation != null && !modeMatches && resumeTranslation.isCleanedImageReady) {
                resumeTranslation.copy(inpaintStatus = StageStatus.PENDING, cleanedImageName = null)
            } else {
                resumeTranslation
            }

            val workPlan = eu.kanade.translation.model.PageWorkPlanner.plan(adjustedResume, force)

            // T922 Phase 3: resolve the resume plan onto the run trace (run_end
            // carries the resolved plan; run_start held the initial default).
            val traceRun = TranslationTrace.currentRun()
            if (traceRun != null) {
                val resolvedPlan = when {
                    workPlan.runOcr -> TranslationTracePlan.FRESH
                    !workPlan.runOcr && !workPlan.runInpaint && !workPlan.runTranslation ->
                        TranslationTracePlan.RENDER_ONLY
                    else -> TranslationTracePlan.RESUME
                }
                traceRun.updatePlan(resolvedPlan)
            }

            if (!workPlan.runOcr && !workPlan.runTranslation && !workPlan.runInpaint && !workPlan.runRender) {
                traceRun?.updatePlan(TranslationTracePlan.SKIP)
                logcat(LogPriority.INFO) {
                    "TachiyomiAT single-page resume skip: pageKey=$pageKey already has final output"
                }
                return null
            }

            if (!workPlan.runOcr && !workPlan.runInpaint) {
                val cleanedBitmap = loadPersistedCleanedBitmap(manga, chapter, source, adjustedResume!!.cleanedImageName!!)
                if (cleanedBitmap != null) {
                    if (!workPlan.runTranslation) {
                        logcat(LogPriority.INFO) {
                            "TachiyomiAT single-page resume: render from cleaned image pageKey=$pageKey cleaned=${adjustedResume.cleanedImageName}"
                        }
                        // T917 D11 (§4.4): the render tail persists the page —
                        // defer it OUTSIDE the native permit when the boundary
                        // supplied a deferral queue (the cleaned bitmap already
                        // crosses the permit boundary by design).
                        val renderTail: suspend () -> Unit = {
                            renderResumedPage(
                                manga,
                                chapter,
                                source,
                                pageKey,
                                store,
                                adjustedResume,
                                cleanedBitmap,
                                " (resume cleaned)",
                                stageListener,
                            )
                        }
                        if (deferredPublications != null) {
                            deferredPublications.enqueue(renderTail)
                        } else {
                            renderTail()
                        }
                        return null
                    } else {
                        logcat(LogPriority.INFO) {
                            "TachiyomiAT single-page resume: translate + render from cleaned image pageKey=$pageKey"
                        }

                        // Fake a decoded page for the sake of the result
                        val stream = streamFromReader ?: peekReaderPageStream(manga, chapter, source, pageKey)
                        val streams = if (stream != null) listOf(pageKey to stream) else emptyList()

                        val fakeDecoded = DecodedPage(
                            bitmap = cleanedBitmap,
                            sampleSize = adjustedResume.decodeSampleSize,
                            originalWidth = adjustedResume.originalImgWidth.toInt(),
                            originalHeight = adjustedResume.originalImgHeight.toInt(),
                            decodeDecision = eu.kanade.translation.util.TranslationMemoryBudget.DecodeDecision(
                                kind = eu.kanade.translation.util.TranslationMemoryBudget.DecodeDecisionKind.FULL,
                                sampleSize = adjustedResume.decodeSampleSize,
                                rawBitmapBytes = 0L,
                                sampledBitmapBytes = 0L,
                                sourcePixels = 0L,
                                sampledPixels = 0L,
                                snapshot = eu.kanade.translation.util.TranslationMemoryBudget.snapshot(),
                            ),
                            sourceBytesSize = 0L,
                        )

                        adjustedResume.cleanedBitmap = cleanedBitmap
                        if (force) clearAttemptCapForManualRetry(store, pageKey)
                        if (force) adjustedResume.prepareForcedRetry()
                        adjustedResume.resetAttemptCharge()
                        adjustedResume.translationStatus = StageStatus.PENDING
                        updatePageFromCurrentSnapshot(store, pageKey, "single-page resume translation reset") { adjustedResume }

                        needsHttpRender = true
                        return OnnxPhaseResult(
                            pageTranslation = adjustedResume,
                            store = store,
                            fromLang = fromLang,
                            syntheticTranslation = syntheticTranslation,
                            streams = streams,
                            decoded = fakeDecoded,
                            commitPrecondition = store.snapshot(pageKey).toPrecondition(),
                        )
                    }
                } else {
                    logcat(LogPriority.WARN) { "Cleaned image missing for $pageKey, falling through to re-decode" }
                }
            }

            val streams = if (streamFromReader != null) {
                logcat(LogPriority.INFO) {
                    "TachiyomiAT single-page translation using reader stream: pageKey=$pageKey " +
                        "chapter=${chapter.name} manga=${manga.title} source=${source.id}"
                }
                listOf(pageKey to streamFromReader)
            } else {
                val chapterPath = downloadProvider.findChapterDir(
                    chapter.name,
                    chapter.scanlator,
                    manga.title,
                    source,
                ) ?: run {
                    logcat(LogPriority.WARN) {
                        "TachiyomiAT single-page translation cannot start (soft skip, no store write): " +
                            "chapter files not found pageKey=$pageKey chapter=${chapter.name} " +
                            "manga=${manga.title} source=${source.id}"
                    }
                    return null
                }
                getChapterPages(chapterPath)
            }

            val entry = streams.find { it.first == pageKey } ?: run {
                logcat(LogPriority.WARN) {
                    "TachiyomiAT single-page translation cannot find requested page: pageKey=$pageKey " +
                        "available=${streams.map { it.first }.take(5)} total=${streams.size}"
                }
                updatePageFromCurrentSnapshot(store, pageKey, "single-page page lookup failure") {
                    (it ?: PageTranslation()).apply {
                        sourceFileName = pageKey
                        ocrStatus = StageStatus.FAILED
                        errorMessage = "Page $pageKey not found in chapter files"
                        retryCount = (it?.retryCount ?: 0) + 1
                        attemptCount = (it?.attemptCount ?: 0) + 1
                        updatedAt = System.currentTimeMillis()
                    }
                }
                return null
            }

            val decoded = try {
                decodePageBitmapForTranslation(pageKey, entry.second)
            } catch (deferred: LowMemoryDecodeDeferredException) {
                updatePageFromCurrentSnapshot(store, pageKey, "single-page decode deferred") {
                    (it ?: PageTranslation()).apply {
                        sourceFileName = pageKey
                        originalImgWidth = deferred.width.toFloat()
                        originalImgHeight = deferred.height.toFloat()
                        decodeSampleSize = 1
                        ocrStatus = StageStatus.FAILED
                        translationStatus = StageStatus.PENDING
                        inpaintStatus = StageStatus.PENDING
                        renderStatus = StageStatus.PENDING
                        errorMessage = deferred.message
                        retryCount = (it?.retryCount ?: 0) + 1
                        attemptCount = (it?.attemptCount ?: 0) + 1
                        updatedAt = System.currentTimeMillis()
                    }
                }
                return null
            }

            if (decoded == null) {
                updatePageFromCurrentSnapshot(store, pageKey, "single-page decode failed") {
                    (it ?: PageTranslation()).apply {
                        sourceFileName = pageKey
                        ocrStatus = StageStatus.FAILED
                        errorMessage = "Failed to decode page: null bitmap"
                        retryCount = (it?.retryCount ?: 0) + 1
                        attemptCount = (it?.attemptCount ?: 0) + 1
                        updatedAt = System.currentTimeMillis()
                    }
                }
                return null
            }

            val bitmap = decoded.bitmap

            if (!workPlan.runOcr && workPlan.runInpaint) {
                try {
                    if (!workPlan.runTranslation) {
                        logcat(LogPriority.INFO) {
                            "TachiyomiAT single-page resume: inpaint + render from translated blocks pageKey=$pageKey"
                        }
                        if (force) clearAttemptCapForManualRetry(store, pageKey)
                        if (force) adjustedResume!!.prepareForcedRetry()
                        adjustedResume!!.resetAttemptCharge()
                        stageListener?.onStageEntered(pageKey, TranslationStageEvent.CLEANING)
                        resumeInpaintAndRender(
                            manga,
                            chapter,
                            source,
                            pageKey,
                            store,
                            decoded,
                            bitmap,
                            adjustedResume,
                            stageListener,
                            deferredPublications,
                        )
                        return null
                    } else {
                        logcat(LogPriority.INFO) {
                            "TachiyomiAT single-page resume: inpaint + translate + render pageKey=$pageKey"
                        }
                        if (force) clearAttemptCapForManualRetry(store, pageKey)
                        if (force) adjustedResume!!.prepareForcedRetry()
                        adjustedResume!!.resetAttemptCharge()
                        adjustedResume.inpaintStatus = StageStatus.RUNNING
                        updatePageFromCurrentSnapshot(store, pageKey, "single-page inpaint resume start") { adjustedResume }
                        stageListener?.onStageEntered(pageKey, TranslationStageEvent.CLEANING)
                        adjustedResume.cleanedBitmap = recognitionEngine.inpaint(bitmap, adjustedResume)

                        needsHttpRender = true
                        return OnnxPhaseResult(
                            pageTranslation = adjustedResume,
                            store = store,
                            fromLang = fromLang,
                            syntheticTranslation = syntheticTranslation,
                            streams = streams,
                            decoded = decoded,
                            commitPrecondition = store.snapshot(pageKey).toPrecondition(),
                        )
                    }
                } finally {
                    try {
                        bitmap.recycle()
                    } catch (_: Exception) {}
                    BitmapPool.releaseAll()
                }
            }

            if (force) clearAttemptCapForManualRetry(store, pageKey)
            updatePageFromCurrentSnapshot(store, pageKey, "single-page OCR start") {
                (it ?: PageTranslation()).apply {
                    sourceFileName = pageKey
                    if (force || ocrStatus != StageStatus.READY) {
                        ocrStatus = StageStatus.RUNNING
                    }
                    if (force) {
                        prepareForcedRetry()
                    }
                    resetAttemptCharge()
                    errorMessage = null
                    updatedAt = System.currentTimeMillis()
                }
            }

            val pageTranslation: PageTranslation
            try {
                stageListener?.onStageEntered(pageKey, TranslationStageEvent.READING)
                pageTranslation = processSinglePage(
                    pageKey,
                    bitmap,
                    decoded,
                    store,
                    stageListener,
                )
            } finally {
                try {
                    bitmap.recycle()
                } catch (_: Exception) {}
                BitmapPool.releaseAll()
            }

            needsHttpRender = true
            return OnnxPhaseResult(
                pageTranslation = pageTranslation,
                store = store,
                fromLang = fromLang,
                syntheticTranslation = syntheticTranslation,
                streams = streams,
                decoded = decoded,
                commitPrecondition = store.snapshot(pageKey).toPrecondition(),
            )
        } finally {
            if (!needsHttpRender) {
                // T917 D11 (§4.4): the store flush + stream-registry clear are
                // storage publication — defer them OUTSIDE the native permit
                // (enqueued AFTER the resume tails, so publication order is
                // identical to the inline sequence). The engine-pool reclaim
                // stays inline: it is memory work, not storage publication.
                val flushTail: suspend () -> Unit = {
                    store.flush()
                    chapter.id?.let { chapterId ->
                        streamRegistry.clearPage(source.id, manga.id, chapterId, pageKey)
                    }
                }
                if (deferredPublications != null) {
                    deferredPublications.enqueue(flushTail)
                } else {
                    flushTail()
                }
                try {
                    recognitionEngine.reclaimPooledMemory()
                } catch (_: Exception) {}
            }
        }
    }

    private suspend fun renderResumedPage(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        pageKey: String,
        store: ChapterTranslationStore,
        pageTranslation: PageTranslation,
        cleanedBitmap: Bitmap,
        successMessageSuffix: String,
        stageListener: TranslationStageListener? = null,
    ) {
        pageTranslation.sourceFileName = pageKey
        pageTranslation.ocrStatus = StageStatus.READY
        pageTranslation.translationStatus = StageStatus.READY
        pageTranslation.renderStatus = StageStatus.RUNNING
        pageTranslation.errorMessage = null
        updatePageFromCurrentSnapshot(store, pageKey, "resumed render running") {
            (it ?: pageTranslation.copyForResume()).apply {
                sourceFileName = pageKey
                renderStatus = StageStatus.RUNNING
                errorMessage = null
                updatedAt = System.currentTimeMillis()
            }
        }
        try {
            stageListener?.onStageEntered(pageKey, TranslationStageEvent.RENDERING)
            RenderColorEstimator.recomputeFor(cleanedBitmap, pageTranslation.blocks)
            pageTranslation.renderStatus = StageStatus.READY
            pageTranslation.updatedAt = System.currentTimeMillis()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            pageTranslation.renderStatus = StageStatus.FAILED
            // Render failed on a cleaned bitmap — first terminal stage.
            pageTranslation.recordAttemptFailure()
            pageTranslation.errorMessage = e.message
            logcat(LogPriority.ERROR, e) { "Failed to render resumed page $pageKey" }
        } finally {
            try {
                cleanedBitmap.recycle()
            } catch (_: Exception) {}
            pageTranslation.cleanedBitmap = null
            pageTranslation.updatedAt = System.currentTimeMillis()
            persistPageWithOomRecovery(store, pageKey, pageTranslation)
        }
    }

    private suspend fun resumeInpaintAndRender(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        pageKey: String,
        store: ChapterTranslationStore,
        decoded: DecodedPage,
        bitmap: Bitmap,
        pageTranslation: PageTranslation,
        stageListener: TranslationStageListener? = null,
        deferredPublications: DeferredPagePublications? = null,
    ) {
        pageTranslation.sourceFileName = pageKey
        pageTranslation.ocrStatus = StageStatus.READY
        pageTranslation.translationStatus = StageStatus.READY
        pageTranslation.inpaintStatus = StageStatus.RUNNING
        pageTranslation.renderStatus = StageStatus.PENDING
        pageTranslation.errorMessage = null
        updatePageFromCurrentSnapshot(store, pageKey, "resumed inpaint running") {
            (it ?: pageTranslation.copyForResume()).apply {
                sourceFileName = pageKey
                inpaintStatus = StageStatus.RUNNING
                renderStatus = StageStatus.PENDING
                errorMessage = null
                updatedAt = System.currentTimeMillis()
            }
        }

        val cleaned = try {
            recognitionEngine.inpaint(bitmap, pageTranslation)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            pageTranslation.inpaintStatus = StageStatus.FAILED
            // First terminal stage — owns the charge.
            pageTranslation.recordAttemptFailure()
            pageTranslation.errorMessage = e.message
            logcat(LogPriority.ERROR, e) { "Failed to resume inpaint for $pageKey" }
            null
        }

        if (cleaned == null) {
            pageTranslation.renderStatus = StageStatus.FAILED
            // Consequence of the inpaint failure above; recordAttemptFailure no-ops once
            // a stage is already FAILED, so this doesn't double-count.
            pageTranslation.recordAttemptFailure()
            val reason = pageTranslation.errorMessage ?: "inpaint unavailable"
            pageTranslation.errorMessage =
                "Inpainting unavailable ($reason) — translated text was not rendered."
            persistPageWithOomRecovery(store, pageKey, pageTranslation)
            return
        }

        val companionDir = provider.getCompanionImageDir(
            manga.title,
            source,
            chapter.name,
            chapter.scanlator,
        )
        // T917 D11 (§4.4): cleaned-image persistence and the render tail are
        // storage publication — defer them OUTSIDE the native permit when the
        // boundary supplied a deferral queue. The tail stays fail-closed: a
        // persist failure still recycles the bitmap, marks render FAILED and
        // persists that state; it fails the page, never silently.
        val persistAndRenderTail: suspend () -> Unit = {
            val persisted = persistCleanedBitmap(
                pageTranslation,
                cleaned,
                companionDir,
                pageKey,
                chapter.name,
                store,
                source.id,
                manga.id,
                chapter.id,
            )
            if (persisted == null) {
                try {
                    cleaned.recycle()
                } catch (_: Exception) {}
                pageTranslation.renderStatus = StageStatus.FAILED
                persistPageWithOomRecovery(store, pageKey, pageTranslation)
            } else {
                renderResumedPage(
                    manga,
                    chapter,
                    source,
                    pageKey,
                    store,
                    pageTranslation,
                    cleaned,
                    successMessageSuffix = " (resume inpaint)",
                    stageListener = stageListener,
                )
            }
        }
        if (deferredPublications != null) {
            deferredPublications.enqueue(persistAndRenderTail)
        } else {
            persistAndRenderTail()
        }
    }

    /**
     * TachiyomiAT: retry-then-block for inpainting. When the first recognize()
     * produced no cleaned bitmap (inpaint failed/unavailable), re-run the full
     * recognize() pipeline on a half-sampled decode before giving up.
     *
     * Why half-sample: the dominant inpaint-failure cause is heap pressure
     * (neural inpaint allocates ~WxH float buffers). Re-decoding at sampleSize*2
     * quarters the pixel count and usually lets the inpainter succeed — a much
     * better outcome than either overlaying text on the original (the old,
     * deceptive fallback) or refusing outright.
     *
     * Returns a [PageTranslation] whose [PageTranslation.cleanedBitmap] is set
     * when the retry succeeded, or the original [pageTranslation] (unchanged)
     * when it also failed. Never renders over the original image — the caller
     * is responsible for surfacing a FAILED render when this returns without a
     * cleaned bitmap, so the user sees an honest error instead of a half-
     * translated page.
     */
    suspend fun retryInpaintDownscaled(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        pageKey: String,
        streams: List<Pair<String, () -> InputStream>>,
        decoded: DecodedPage,
        pageTranslation: PageTranslation,
    ): PageTranslation {
        if (pageTranslation.cleanedBitmap != null) return pageTranslation
        val retrySampleSize = (pageTranslation.decodeSampleSize * 2).coerceAtMost(8)
        if (retrySampleSize == pageTranslation.decodeSampleSize) {
            return pageTranslation
        }
        val retryBitmap = try {
            decodePageBitmapAtSize(pageKey, retrySampleSize, streams)
        } catch (oom: OutOfMemoryError) {
            BitmapPool.releaseAll()
            System.gc()
            logcat(LogPriority.WARN, oom) { "Inpaint-retry decode OOM: $pageKey" }
            return pageTranslation
        } ?: return pageTranslation

        try {
            logcat(LogPriority.INFO) {
                "TachiyomiAT inpaint retry at sampleSize=$retrySampleSize for $pageKey (first attempt produced no cleaned bitmap)"
            }
            // Re-run on the smaller bitmap; only the cleaned bitmap is needed — the
            // original pageTranslation's OCR/translation results are already good.
            val retryTranslation = try {
                recognitionEngine.recognize(retryBitmap)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                logcat(LogPriority.WARN, e) { "Inpaint-retry recognize failed: $pageKey" }
                return pageTranslation
            }
            val cleaned = retryTranslation.cleanedBitmap
            if (cleaned != null) {
                // Scale back to the original decode dimensions: block coordinates are in that
                // space, so drawing the smaller bitmap then original-scale text would misalign.
                // Use decoded.originalWidth/Height (the bitmap is recycled by render time).
                val targetW = decoded.originalWidth / pageTranslation.decodeSampleSize
                val targetH = decoded.originalHeight / pageTranslation.decodeSampleSize
                val scaledCleaned = if (cleaned.width == targetW && cleaned.height == targetH) {
                    cleaned
                } else {
                    val s = Bitmap.createScaledBitmap(cleaned, targetW, targetH, true)
                    if (s !== cleaned) {
                        try {
                            cleaned.recycle()
                        } catch (_: Exception) {}
                    }
                    s
                }
                pageTranslation.cleanedBitmap = scaledCleaned
                pageTranslation.inpaintingModeUsed = currentInpaintingMode.name
                pageTranslation.inpaintStatus = StageStatus.READY
                pageTranslation.errorMessage = null
            }
            retryTranslation.cleanedBitmap = null // we own it now
        } finally {
            try {
                retryBitmap.recycle()
            } catch (_: Exception) {}
            BitmapPool.releaseAll()
        }
        return pageTranslation
    }

    /**
     * TachiyomiAT: STAGE 1 of the staged batch pipeline — detect + OCR only.
     *
     * Splits the fused [processSinglePage] (which calls recognize = analyze then
     * inpaint back-to-back) so the batch path can run DETECT+OCR across a batch
     * of pages first (persisting blocks + ocrStatus=READY), then inpaint them in
     * a later stage. analyze() is detect+OCR; it populates [PageTranslation.blocks]
     * and stashes [PageTranslation.allTextDetections] for inpaint to read back,
     * and leaves cleanedBitmap null (inpaint's job). See ResumeOrdering /
     * translateBatchInternal for the orchestration.
     *
     * The bitmap is recycled by the CALLER (the batch loop) after this returns —
     * one page's bitmap is alive at a time, matching the existing memory model.
     * For the inpaint stage the page is re-decoded (decodePageBitmapForTranslation
     * already buffers source bytes into a private ByteArray, so no shared-stream
     * race with a reader display decode).
     *
     * Returns a PageTranslation with ocrStatus=READY (or a FAILED placeholder on
     * OOM/exception). Persists blocks to [store] so the stage is resumable.
     */
    suspend fun analyzePage(
        fileName: String,
        bitmap: Bitmap,
        decoded: DecodedPage,
        store: ChapterTranslationStore,
        batchFingerprints: BatchExpectedFingerprints,
    ): PageTranslation {
        val pageStart = System.nanoTime()
        var pageTranslation: PageTranslation
        val finalSampleSize = decoded.sampleSize
        // Phase 3 OCR-write precondition: the page version and prior OCR
        // identity observed before the native pass. Anything that touched the
        // page during recognition makes this writer stale and the merge is
        // rejected instead of clobbering the newer work.
        val preNative = store.snapshot(fileName)
        var oomDiagnosticMessage: String? = null
        try {
            preflightAnalyzeGate(bitmap, fileName)
            pageTranslation = recognitionEngine.analyze(bitmap)
            consecutiveOomCount = 0
        } catch (deferred: LowMemoryRecognitionDeferredException) {
            logcat(LogPriority.WARN) {
                "Low memory deferred analyzing $fileName: ${deferred.message}"
            }
            pageTranslation = createFailedPagePlaceholder(
                fileName,
                "Recognition deferred: ${deferred.message}",
                imgWidth = bitmap.width.toFloat(),
                imgHeight = bitmap.height.toFloat(),
                originalImgWidth = decoded.originalWidth.toFloat(),
                originalImgHeight = decoded.originalHeight.toFloat(),
                decodeSampleSize = decoded.sampleSize,
                retryCount = 1,
            )
        } catch (oom: OutOfMemoryError) {
            handleCriticalTranslationOom("analyzing $fileName", oom)
            consecutiveOomCount++
            logcat(LogPriority.ERROR, oom) {
                "Out of memory analyzing $fileName (oomCount=$consecutiveOomCount)"
            }
            pageTranslation = createFailedPagePlaceholder(
                fileName,
                "Low memory during recognition. Released translation caches; retry when memory recovers.",
                imgWidth = bitmap.width.toFloat(),
                imgHeight = bitmap.height.toFloat(),
                originalImgWidth = decoded.originalWidth.toFloat(),
                originalImgHeight = decoded.originalHeight.toFloat(),
                decodeSampleSize = decoded.sampleSize,
                retryCount = 1,
            )
            if (consecutiveOomCount >= 2) {
                oomDiagnosticMessage = "ONNX recognition failed due to memory pressure. Retry after memory recovers."
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logcat(LogPriority.ERROR, e) {
                "ONNX analyze failed for $fileName"
            }
            pageTranslation = createFailedPagePlaceholder(
                fileName,
                "ONNX recognition failed: ${e.message ?: e::class.java.simpleName}",
                imgWidth = bitmap.width.toFloat(),
                imgHeight = bitmap.height.toFloat(),
                originalImgWidth = decoded.originalWidth.toFloat(),
                originalImgHeight = decoded.originalHeight.toFloat(),
                decodeSampleSize = decoded.sampleSize,
                retryCount = 1,
            )
        }
        pageTranslation.decodeSampleSize = finalSampleSize

        finalizePostOcrStage(pageTranslation, inpaintAlreadyRan = false)

        pageTranslation.originalImgWidth = decoded.originalWidth.toFloat()
        pageTranslation.originalImgHeight = decoded.originalHeight.toFloat()
        pageTranslation.sourceFileName = fileName
        pageTranslation.sourceFingerprint = decoded.sourceFingerprint
        pageTranslation.detectionFingerprint = batchFingerprints.detection
        pageTranslation.ocrFingerprint = batchFingerprints.ocr
        pageTranslation.updatedAt = System.currentTimeMillis()
        logcat(LogPriority.INFO) {
            "[translation_analyze] $fileName blocks=${pageTranslation.blocks.size} " +
                "engine=${pageTranslation.recognitionEngine} sample=${pageTranslation.decodeSampleSize} " +
                "elapsedMs=${(System.nanoTime() - pageStart) / 1_000_000}"
        }
        // Persist blocks + the durable inpaint mask so this stage is resumable: a later
        // run skips pages whose ocrStatus is READY with non-empty blocks AND a mask matching
        // the current inpaint revision. The mask lets detector-only + watermark regions
        // still get erased after a resume/reopen. Phase 3: preconditioned merge — a stale
        // worker cannot overwrite a page a newer writer owns, and a failed recognition
        // keeps the existing reusable blocks while recording the failure diagnostics.
        val ocrPatch = store.mergeOcr(
            OcrStagePatch(
                pageKey = fileName,
                generation = preNative.generation,
                expectedPageVersion = preNative.pageVersion,
                expectedLeaseToken = preNative.leaseToken,
                expectedPriorOcrFingerprints = preNative.page?.ocrBlockFingerprints().orEmpty(),
                ocrResult = pageTranslation,
                errorMessage = oomDiagnosticMessage,
                expectedCandidateGenerationId = preNative.candidateGenerationId,
                expectedDependencyFingerprint = preNative.dependencyFingerprint,
                expectedArtifactPageVersion = preNative.artifactPageVersion,
            ),
            description = "batch detection/ocr persist",
        )
        if (ocrPatch is StagePatchResult.Rejected) {
            logcat(LogPriority.WARN) {
                "TachiyomiAT batch OCR persist rejected (stale writer): pageKey=$fileName reason=${ocrPatch.reason}"
            }
            throw BatchPersistenceRejectedException(
                pageKey = fileName,
                stage = BatchDiagnosticStage.OCR,
            )
        }
        return pageTranslation
    }

    /**
     * TachiyomiAT: STAGE 2 of the staged batch pipeline — inpaint only.
     *
     * Re-decoded [bitmap] + the analyzed [pageTranslation] (blocks +
     * allTextDetections) → cleaned bitmap, returned for downstream JPEG
     * persistence + render. Mirrors the inpaint half of [processSinglePage]
     * and the standalone resume path [resumeInpaintAndRender]. The caller
     * recycles the bitmap.
     *
     * Sets inpaintStatus=RUNNING, then FAILED (with [recordAttemptFailure] +
     * errorMessage) on a thrown exception. Does NOT set inpaintStatus=READY or
     * cleanedImageName — those are written by the caller in
     * [persistCleanedBitmap] after the JPEG encode moves off the translation
     * permit. The cleanedBitmap on the returned translation stays alive for
     * the downstream render stage (caller draws translated text onto it).
     */
    suspend fun inpaintPage(
        fileName: String,
        bitmap: Bitmap,
        pageTranslation: PageTranslation,
        batchFingerprint: String?,
        guardedWrite: suspend (String, (PageTranslation?) -> PageTranslation) -> ChapterTranslationStore.PatchResult,
    ): PageTranslation {
        try {
            pageTranslation.inpaintFingerprint = batchFingerprint
            pageTranslation.inpaintStatus = StageStatus.RUNNING
            pageTranslation.updatedAt = System.currentTimeMillis()
            val runningWrite = guardedWrite("batch inpaint running") {
                (it ?: pageTranslation).apply {
                    inpaintStatus = StageStatus.RUNNING
                    updatedAt = System.currentTimeMillis()
                }
            }
            if (runningWrite is ChapterTranslationStore.PatchResult.Rejected) {
                throw BatchPersistenceRejectedException(
                    pageKey = fileName,
                    stage = BatchDiagnosticStage.INPAINT,
                )
            }
            pageTranslation.cleanedBitmap = recognitionEngine.inpaint(bitmap, pageTranslation)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (e is BatchPersistenceRejectedException) throw e
            pageTranslation.inpaintStatus = StageStatus.FAILED
            // First terminal stage — owns the charge.
            pageTranslation.recordAttemptFailure()
            pageTranslation.errorMessage = e.message
            logcat(LogPriority.ERROR, e) { "Failed to inpaint page $fileName" }
            guardedWrite("batch inpaint failed") {
                (it ?: pageTranslation).apply {
                    inpaintStatus = StageStatus.FAILED
                    updatedAt = System.currentTimeMillis()
                }
            }
            return pageTranslation
        }

        // JPEG persistence is deliberately performed after the permit-held ONNX
        // phase. The cleaned bitmap remains available to the downstream render,
        // while the next page can start native work during this CPU-only encode.
        pageTranslation.updatedAt = System.currentTimeMillis()
        return pageTranslation
    }

    private suspend fun processSinglePage(
        fileName: String,
        bitmap: Bitmap,
        decoded: DecodedPage,
        store: ChapterTranslationStore,
        stageListener: TranslationStageListener? = null,
    ): PageTranslation {
        val pageStart = System.nanoTime()
        var pageTranslation: PageTranslation
        val finalSampleSize = decoded.sampleSize
        // P2 alignment: split the fused recognize() into analyze() + inpaint()
        // so CLEANING fires at the actual inpaint entry, not coalesced into
        // READING. The recognition engine's recognize() default is literally
        // analyze() then inpaint(), so the split is semantically identical on
        // the happy path but makes the Cleaning stage structurally observable
        // for the rolling coordinator's slot model.
        try {
            preflightAnalyzeGate(bitmap, fileName)
            pageTranslation = recognitionEngine.analyze(bitmap)
            consecutiveOomCount = 0
        } catch (deferred: LowMemoryRecognitionDeferredException) {
            logcat(LogPriority.WARN) {
                "Low memory deferred recognizing $fileName: ${deferred.message}"
            }
            pageTranslation = createFailedPagePlaceholder(
                fileName,
                "Recognition/Inpainting deferred: ${deferred.message}",
                imgWidth = bitmap.width.toFloat(),
                imgHeight = bitmap.height.toFloat(),
                originalImgWidth = decoded.originalWidth.toFloat(),
                originalImgHeight = decoded.originalHeight.toFloat(),
                decodeSampleSize = decoded.sampleSize,
                retryCount = 1,
            )
        } catch (oom: OutOfMemoryError) {
            handleCriticalTranslationOom("recognizing $fileName", oom)
            consecutiveOomCount++
            logcat(LogPriority.ERROR, oom) {
                "Out of memory recognizing $fileName (oomCount=$consecutiveOomCount)"
            }
            pageTranslation = createFailedPagePlaceholder(
                fileName,
                "Low memory during recognition. Released translation caches; retry when memory recovers.",
                imgWidth = bitmap.width.toFloat(),
                imgHeight = bitmap.height.toFloat(),
                originalImgWidth = decoded.originalWidth.toFloat(),
                originalImgHeight = decoded.originalHeight.toFloat(),
                decodeSampleSize = decoded.sampleSize,
                retryCount = 1,
            )
            if (consecutiveOomCount >= 2) {
                updatePageFromCurrentSnapshot(store, fileName, "single-page recognition OOM diagnostic") {
                    (it ?: PageTranslation()).apply {
                        errorMessage = "ONNX recognition failed due to memory pressure. Retry after memory recovers."
                    }
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logcat(LogPriority.ERROR, e) {
                "ONNX recognition failed for $fileName; not falling back to full-page ML Kit"
            }
            pageTranslation = createFailedPagePlaceholder(
                fileName,
                "ONNX recognition failed: ${e.message ?: e::class.java.simpleName}",
                imgWidth = bitmap.width.toFloat(),
                imgHeight = bitmap.height.toFloat(),
                originalImgWidth = decoded.originalWidth.toFloat(),
                originalImgHeight = decoded.originalHeight.toFloat(),
                decodeSampleSize = decoded.sampleSize,
                retryCount = 1,
            )
        }

        // If OCR itself failed, skip inpaint — the placeholder carries the
        // failure and there is nothing to clean.
        val ocrFailed = pageTranslation.ocrStatus == StageStatus.FAILED
        if (!ocrFailed) {
            stageListener?.onStageEntered(fileName, TranslationStageEvent.CLEANING)
            try {
                preflightInpaintGate(bitmap, fileName)
                pageTranslation.cleanedBitmap = recognitionEngine.inpaint(bitmap, pageTranslation)
                pageTranslation.cleanedBitmap?.let { cleaned ->
                    try {
                        RenderColorEstimator.recomputeFor(cleaned, pageTranslation.blocks)
                    } catch (_: Exception) {}
                }
            } catch (deferred: LowMemoryRecognitionDeferredException) {
                logcat(LogPriority.WARN) {
                    "Low memory deferred inpainting $fileName: ${deferred.message}"
                }
                pageTranslation.inpaintStatus = StageStatus.FAILED
                pageTranslation.errorMessage = deferred.message
            } catch (oom: OutOfMemoryError) {
                handleCriticalTranslationOom("inpainting $fileName", oom)
                consecutiveOomCount++
                pageTranslation.inpaintStatus = StageStatus.FAILED
                logcat(LogPriority.ERROR, oom) {
                    "Out of memory inpainting $fileName (oomCount=$consecutiveOomCount)"
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                pageTranslation.inpaintStatus = StageStatus.FAILED
                pageTranslation.errorMessage = e.message
                logcat(LogPriority.ERROR, e) {
                    "ONNX inpaint failed for $fileName"
                }
            }
        }

        pageTranslation.decodeSampleSize = finalSampleSize
        finalizePostOcrStage(pageTranslation, inpaintAlreadyRan = true)

        pageTranslation.originalImgWidth = decoded.originalWidth.toFloat()
        pageTranslation.originalImgHeight = decoded.originalHeight.toFloat()
        pageTranslation.sourceFileName = fileName
        pageTranslation.updatedAt = System.currentTimeMillis()
        // T922 Phase 3: when the page runs inside a correlated trace, the
        // ambiguous local pageStart total is replaced by the correlated run
        // total (parity: both are wall-clock ms for the page work). Outside a
        // trace the legacy local total is kept.
        val traceTotalMs = TranslationTrace.currentRun()?.totalMsAt(System.nanoTime())
        logcat(LogPriority.INFO) {
            "[translation_page] $fileName blocks=${pageTranslation.blocks.size} " +
                "engine=${pageTranslation.recognitionEngine} sample=${pageTranslation.decodeSampleSize} " +
                "cleaned=${pageTranslation.cleanedBitmap != null} " +
                (
                    traceTotalMs?.let { "traceTotalMs=$it" }
                        ?: "elapsedMs=${(System.nanoTime() - pageStart) / 1_000_000}"
                    )
        }

        // Cooperative cancellation checkpoint: recognize()/inpaint() internals (ONNX native
        // calls) can't observe a cancel, so one issued mid-call only lands at the next suspend
        // point. Drop out here so we don't render/translate/persist a page the caller no longer wants.
        coroutineContext.ensureActive()

        return pageTranslation
    }

    /**
     * T917 Phase 3 (D9, design §3.2): an explicit user force clears the
     * crash-loop cap bookkeeping (consecutive counter + INTERRUPTED durable
     * failure) so the user's retry is admitted — the cap binds auto-retry
     * loops, never the user. Fail-open: a failed clear never blocks the retry.
     */
    private suspend fun clearAttemptCapForManualRetry(
        store: ChapterTranslationStore,
        pageKey: String,
    ) {
        runCatching { store.clearAttemptCapForManualRetry(pageKey) }.onFailure {
            logcat(LogPriority.WARN) {
                "TachiyomiAT D9: attempt-cap clear failed (fail-open): pageKey=$pageKey"
            }
        }
    }
}

/**
 * Result of the permit-held ONNX phase ([translateSinglePageOnnx])
 * in the reader single-page path. Carries the [PageTranslation] (with its
 * cleanedBitmap alive across the permit boundary) and the state needed by
 * the permit-free HTTP+render phase ([translateSinglePageHttpRender]).
 */
internal data class OnnxPhaseResult(
    val pageTranslation: PageTranslation,
    val store: ChapterTranslationStore,
    val fromLang: TextRecognizerLanguage,
    val syntheticTranslation: Translation,
    val streams: List<Pair<String, () -> InputStream>>,
    val decoded: DecodedPage,
    val commitPrecondition: ChapterTranslationStore.PatchPrecondition? = null,
)
