package eu.kanade.translation.pipeline

import android.content.Context
import android.graphics.Bitmap
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.translation.diagnostics.BatchDiagnosticStage
import eu.kanade.translation.diagnostics.DeviceStageNormalization
import eu.kanade.translation.diagnostics.NativeVisionTelemetry
import eu.kanade.translation.diagnostics.TranslationTrace
import eu.kanade.translation.diagnostics.TranslationTraceLane
import eu.kanade.translation.diagnostics.TranslationTraceOutcome
import eu.kanade.translation.diagnostics.TranslationTracePlan
import eu.kanade.translation.diagnostics.TranslationTraceProvider
import eu.kanade.translation.diagnostics.TranslationTraceStage
import eu.kanade.translation.engines.inpainting.InpaintStampDecision
import eu.kanade.translation.engines.inpainting.stampName
import eu.kanade.translation.engines.inpainting.stampNeedsReinpaint
import eu.kanade.translation.engines.rendering.RenderColorEstimator
import eu.kanade.translation.engines.vision.ocr.RoiPageRecognitionEngine
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.TextRecognizerLanguage
import eu.kanade.translation.model.TextTranslatorLanguage
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.isCleanedImageReady
import eu.kanade.translation.model.prepareForcedRetry
import eu.kanade.translation.model.recordAttemptFailure
import eu.kanade.translation.model.resetAttemptCharge
import eu.kanade.translation.model.toDraft
import eu.kanade.translation.persistence.ChapterTranslationStore
import eu.kanade.translation.persistence.OcrStagePatch
import eu.kanade.translation.persistence.StagePatchResult
import eu.kanade.translation.persistence.TranslationFileProvider
import eu.kanade.translation.persistence.ocrBlockFingerprints
import eu.kanade.translation.persistence.ocrFingerprint
import eu.kanade.translation.pipeline.batch.BatchPersistenceRejectedException
import eu.kanade.translation.pipeline.execution.TranslationStageEvent
import eu.kanade.translation.pipeline.execution.TranslationStageListener
import eu.kanade.translation.pipeline.execution.TranslationStreamRegistry
import eu.kanade.translation.pipeline.finalizePostOcrStage
import eu.kanade.translation.pipeline.memory.MemoryGovernance
import eu.kanade.translation.pipeline.planning.BatchExpectedFingerprints
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
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
import kotlin.coroutines.coroutineContext

/**
 * Runs the reader's native ONNX work while holding the engine permit, including
 * resumed-page rendering, inpaint retry, and the batch detection/OCR/inpaint
 * stages. Bitmap recycling stays in `finally`, cancellation is rethrown at
 * each catch boundary, and the caller balances its held-bitmap registry.
 */
internal class SinglePageOnnxPhase(
    private val context: Context,
    private val translationPreferences: TranslationPreferences,
    private val provider: TranslationFileProvider,
    private val downloadProvider: DownloadProvider,
    private val streamRegistry: TranslationStreamRegistry,
    private val engines: EngineLane,
    private val cleanedPublication: CleanedPublication,
    private val pageStoreWriter: PageStoreWriter,
    private val activeStoreResolverProvider: () -> (suspend (Translation) -> ChapterTranslationStore?)?,
    // Shared with the pipeline so rebuild ordering against the caller's
    // native-quarantine admission is exactly the pre-move mutex.
    private val engineRebuildMutex: Mutex,
) {

    // Resolve engine and store state through the shared lane and writer.
    private val activeStoreResolver get() = activeStoreResolverProvider()

    private val recognitionEngine get() = engines.recognitionEngine

    private val currentInpaintingMode get() = engines.currentInpaintingMode

    private fun inpaintingStampDecision(): InpaintStampDecision = engines.inpaintingStampDecision()

    private suspend fun ensureEnginesBuiltFor(
        fromLang: TextRecognizerLanguage,
        toLang: TextTranslatorLanguage,
    ) {
        engines.ensureEnginesBuiltFor(fromLang, toLang, useReaderPaddleOverride = true)
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
        attempt: AttemptAuthority? = null,
        update: (PageTranslation?) -> PageTranslation,
    ): ChapterTranslationStore.PatchResult =
        if (attempt != null) {
            attempt.write(description) { current -> update(current) }
        } else {
            pageStoreWriter.updatePageFromCurrentSnapshot(store, pageKey, description, expectedGeneration, update)
        }

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
     * Correlated `source_decode` stage boundary via the installed [TranslationTrace]
     * element (fail-open NO_OP outside a traced coroutine). Both call sites (fresh
     * decode and inpaint-resume re-decode) are measured.
     */
    private suspend fun decodePageBitmapForTranslation(fileName: String, streamFn: () -> InputStream): DecodedPage? {
        val decodeSpan = TranslationTrace.beginStage(TranslationTraceStage.SOURCE_DECODE)
        val decoded = try {
            PageDecode.decodePageBitmapForTranslation(
                context = context,
                recognitionEngine = { recognitionEngine },
                fileName = fileName,
                streamFn = streamFn,
                onSourceBounds = { width, height ->
                    decodeSpan.setNormalizationUnits(DeviceStageNormalization.sourcePixels(width, height))
                },
            )
        } catch (t: Throwable) {
            decodeSpan.end(TranslationTraceOutcome.FAILURE, error = t)
            throw t
        }
        decodeSpan.end(if (decoded == null) TranslationTraceOutcome.FAILURE else TranslationTraceOutcome.SUCCESS)
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
     * Phase ONNX of the reader single-page path under native quarantine: decode,
     * [processSinglePage], persist .cleaned; result carries cleanedBitmap for the
     * caller's HTTP translate + render outside the permit; null when already handled.
     * With [deferredPublications], resume publication is enqueued and boundary-drained.
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
        sourceCustody: SourceBitmapCustody? = null,
        attempt: AttemptAuthority? = null,
        owner: CleanedOwner? = null,
        inlineInpaint: Boolean = false,
    ): OnnxPhaseResult? {
        val streamFromReader = readerStreamFn ?: peekReaderPageStream(manga, chapter, source, pageKey)
        val fromLang = TextRecognizerLanguage.fromPref(translationPreferences.translateFromLanguage())
        val toLang = TextTranslatorLanguage.fromPref(translationPreferences.translateToLanguage())
        val syntheticTranslation = Translation(source, manga, chapter, fromLang, toLang)
        syntheticTranslation.status = Translation.State.TRANSLATING

        var ownStore: ChapterTranslationStore? = null
        val store = activeStoreResolver?.invoke(syntheticTranslation).also {
            ownStore = if (it == null) null else syntheticTranslation.let { _ -> it }
        } ?: run {
            val mangaDir = provider.getMangaDir(manga.title, source)
            val saveFile = provider.getTranslationFileName(chapter.name, chapter.scanlator)
            val parent = mangaDir ?: return null
            ChapterTranslationStore.openArtifactSuspend(
                parent,
                saveFile,
                provider.privateJournalRoot,
                provider.privateJournalIdentity(source, manga.title, saveFile),
            ).also { ownStore = it }
        }

        // Cleanup runs here for resume/error paths; deferred to the fresh path
        // where the cleaned bitmap crosses the permit boundary.
        var needsHttpRender = false
        try {
            val resumeTranslation = store.state.value[pageKey]?.toDraft()?.copyForResume()
            val stampDecision = inpaintingStampDecision()
            val modeMatches = !stampDecision.stampNeedsReinpaint(resumeTranslation?.inpaintingModeUsed)
            val adjustedResume = if (resumeTranslation != null && !modeMatches && resumeTranslation.isCleanedImageReady) {
                resumeTranslation.copy(
                    inpaintStatus = StageStatus.PENDING,
                    cleanedImageName = null,
                    cleanedImageContentHash = null,
                )
            } else {
                resumeTranslation
            }

            val workPlan = eu.kanade.translation.pipeline.planning.PageWorkPlanner.plan(adjustedResume, force)

            // Resolve the resume plan onto the run trace (run_end
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

            // Stage admission is the user-visible event, not the first
            // post-admission side quest. Publish OCR RUNNING before chapter
            // stream enumeration, source decode, or ONNX work so a manual tap
            // reaches "Reading Text" as soon as the active store is available.
            if (workPlan.runOcr) {
                if (force) clearAttemptCapForManualRetry(store, pageKey)
                updatePageFromCurrentSnapshot(store, pageKey, "single-page OCR admission", attempt = attempt) {
                    (it ?: PageTranslation()).apply {
                        sourceFileName = pageKey
                        ocrStatus = StageStatus.RUNNING
                        if (force) prepareForcedRetry()
                        resetAttemptCharge()
                        errorMessage = null
                        updatedAt = System.currentTimeMillis()
                    }
                }
                stageListener?.onStageEntered(pageKey, TranslationStageEvent.READING)
            }

            // The caller already owns native quarantine. Rebuild is therefore
            // ordered after any timed-out predecessor's real exit and cannot
            // race closeEngines. It is deliberately after the live admission
            // emission above: engine/session checks are side work and must not
            // delay the first reader truth signal.
            try {
                engineRebuildMutex.withLock {
                    ensureEnginesBuiltFor(fromLang, toLang)
                }
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                if (workPlan.runOcr) {
                    updatePageFromCurrentSnapshot(store, pageKey, "single-page OCR admission failed", attempt = attempt) {
                        (it ?: PageTranslation(sourceFileName = pageKey)).apply {
                            sourceFileName = pageKey
                            ocrStatus = StageStatus.FAILED
                            errorMessage = "Translation engine initialization failed: " +
                                (failure.message ?: failure::class.java.simpleName)
                            updatedAt = System.currentTimeMillis()
                        }
                    }
                }
                throw failure
            }

            if (!workPlan.runOcr && !workPlan.runInpaint) {
                val cleanedBitmap = loadPersistedCleanedBitmap(manga, chapter, source, adjustedResume!!.cleanedImageName!!)
                if (cleanedBitmap != null) {
                    if (!workPlan.runTranslation) {
                        logcat(LogPriority.INFO) {
                            "TachiyomiAT single-page resume: render from cleaned image pageKey=$pageKey cleaned=${adjustedResume.cleanedImageName}"
                        }
                        // The render tail persists the page —
                        // defer it OUTSIDE the native permit when the boundary
                        // supplied a deferral queue (the cleaned bitmap already
                        // crosses the permit boundary by design).
                        owner?.produce(cleanedBitmap, CleanedProvenance.INPAINT)
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
                                owner,
                                attempt,
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
                            decodeDecision = eu.kanade.translation.pipeline.memory.TranslationMemoryBudget.DecodeDecision(
                                kind = eu.kanade.translation.pipeline.memory.TranslationMemoryBudget.DecodeDecisionKind.FULL,
                                sampleSize = adjustedResume.decodeSampleSize,
                                rawBitmapBytes = 0L,
                                sampledBitmapBytes = 0L,
                                sourcePixels = 0L,
                                sampledPixels = 0L,
                                snapshot = eu.kanade.translation.pipeline.memory.TranslationMemoryBudget.snapshot(),
                            ),
                            sourceBytesSize = 0L,
                        )

                        adjustedResume.cleanedBitmap = cleanedBitmap
                        if (cleanedBitmap != null) {
                            owner?.produce(cleanedBitmap, CleanedProvenance.INPAINT)
                        }
                        if (force) clearAttemptCapForManualRetry(store, pageKey)
                        if (force) adjustedResume.prepareForcedRetry()
                        adjustedResume.resetAttemptCharge()
                        adjustedResume.translationStatus = StageStatus.PENDING
                        updatePageFromCurrentSnapshot(store, pageKey, "single-page resume translation reset", attempt = attempt) { adjustedResume }

                        needsHttpRender = true
                        return OnnxPhaseResult(
                            pageTranslation = adjustedResume,
                            store = store,
                            fromLang = fromLang,
                            syntheticTranslation = syntheticTranslation,
                            streams = streams,
                            decoded = fakeDecoded,
                            commitPrecondition = attempt?.precondition ?: store.snapshot(pageKey).toPrecondition(),
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
                updatePageFromCurrentSnapshot(store, pageKey, "single-page page lookup failure", attempt = attempt) {
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
                updatePageFromCurrentSnapshot(store, pageKey, "single-page decode deferred", attempt = attempt) {
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
                updatePageFromCurrentSnapshot(store, pageKey, "single-page decode failed", attempt = attempt) {
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
            sourceCustody?.register(bitmap)

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
                            owner,
                            attempt,
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
                        updatePageFromCurrentSnapshot(store, pageKey, "single-page inpaint resume start", attempt = attempt) { adjustedResume }
                        stageListener?.onStageEntered(pageKey, TranslationStageEvent.CLEANING)
                        val cleaned = TranslationTrace.withStage(
                            stage = TranslationTraceStage.INPAINT,
                            lane = TranslationTraceLane.NATIVE,
                            provider = TranslationTraceProvider.CPU,
                            resultOutcome = { c ->
                                if (c == null) TranslationTraceOutcome.FAILURE else TranslationTraceOutcome.SUCCESS
                            },
                        ) { recognitionEngine.inpaint(bitmap, adjustedResume) }
                        if (cleaned != null) {
                            owner?.produce(cleaned, CleanedProvenance.INPAINT)
                            adjustedResume.cleanedBitmap = cleaned
                        }

                        needsHttpRender = true
                        return OnnxPhaseResult(
                            pageTranslation = adjustedResume,
                            store = store,
                            fromLang = fromLang,
                            syntheticTranslation = syntheticTranslation,
                            streams = streams,
                            decoded = decoded,
                            commitPrecondition = attempt?.precondition ?: store.snapshot(pageKey).toPrecondition(),
                        )
                    }
                } finally {
                    val toRecycle = if (sourceCustody != null) sourceCustody.take() else bitmap
                    if (toRecycle != null && (owner == null || !owner.owns(toRecycle))) {
                        try {
                            toRecycle.recycle()
                        } catch (_: Exception) {}
                    }
                    BitmapPool.releaseAll()
                }
            }

            val pageTranslation: PageTranslation
            try {
                pageTranslation = processSinglePage(
                    pageKey,
                    bitmap,
                    decoded,
                    store,
                    stageListener,
                    attempt,
                )
                if (inlineInpaint &&
                    (pageTranslation.blocks.isNotEmpty() || pageTranslation.inpaintMaskBoxes.isNotEmpty()) &&
                    pageTranslation.ocrStatus != StageStatus.FAILED
                ) {
                    val inpaintSpan = traceRun?.beginStage(
                        TranslationTraceStage.INPAINT,
                        lane = TranslationTraceLane.NATIVE,
                        provider = TranslationTraceProvider.CPU,
                    )
                    try {
                        val cleaned = recognitionEngine.inpaint(bitmap, pageTranslation)
                        if (cleaned != null) {
                            owner?.produce(cleaned, CleanedProvenance.INPAINT)
                            pageTranslation.cleanedBitmap = cleaned
                            pageTranslation.inpaintStatus = StageStatus.READY
                        }
                    } finally {
                        inpaintSpan?.end()
                    }
                }
            } finally {
                // OCR-only retains custody; boundary disposes it after native exit.
                if (inlineInpaint || sourceCustody == null) {
                    val toRecycle = if (sourceCustody != null) sourceCustody.take() else bitmap
                    if (toRecycle != null && (owner == null || !owner.owns(toRecycle))) {
                        toRecycle.recycle()
                    }
                }
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
                commitPrecondition = attempt?.precondition ?: store.snapshot(pageKey).toPrecondition(),
            )
        } finally {
            if (!needsHttpRender) {
                // The store flush and stream-registry clear are
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
        owner: CleanedOwner? = null,
        attempt: AttemptAuthority? = null,
    ) {
        pageTranslation.sourceFileName = pageKey
        pageTranslation.ocrStatus = StageStatus.READY
        pageTranslation.translationStatus = StageStatus.READY
        pageTranslation.renderStatus = StageStatus.RUNNING
        pageTranslation.errorMessage = null
        if (attempt != null) {
            attempt.write("resumed render running") { current ->
                (current ?: pageTranslation.copyForResume()).apply {
                    sourceFileName = pageKey
                    renderStatus = StageStatus.RUNNING
                    errorMessage = null
                    updatedAt = System.currentTimeMillis()
                }
            }
        } else {
            updatePageFromCurrentSnapshot(store, pageKey, "resumed render running", attempt = attempt) {
                (it ?: pageTranslation.copyForResume()).apply {
                    sourceFileName = pageKey
                    renderStatus = StageStatus.RUNNING
                    errorMessage = null
                    updatedAt = System.currentTimeMillis()
                }
            }
        }
        try {
            stageListener?.onStageEntered(pageKey, TranslationStageEvent.RENDERING)
            RenderColorEstimator.recomputeFor(cleanedBitmap, pageTranslation.blocks)
            pageTranslation.renderStatus = StageStatus.READY
            pageTranslation.updatedAt = System.currentTimeMillis()
            if (attempt != null) {
                attempt.write("resumed render ready$successMessageSuffix") { current ->
                    (current ?: pageTranslation).apply {
                        renderStatus = StageStatus.READY
                        errorMessage = null
                        updatedAt = System.currentTimeMillis()
                    }
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            e.printStackTrace()
            pageTranslation.renderStatus = StageStatus.FAILED
            // Render failed on a cleaned bitmap — first terminal stage.
            pageTranslation.recordAttemptFailure()
            pageTranslation.errorMessage = e.message
            logcat(LogPriority.ERROR, e) { "Failed to render resumed page $pageKey" }
            if (attempt != null) {
                attempt.write("resumed render failed") { current ->
                    (current ?: pageTranslation).apply {
                        renderStatus = StageStatus.FAILED
                        recordAttemptFailure()
                        errorMessage = e.message
                        updatedAt = System.currentTimeMillis()
                    }
                }
            }
        } finally {
            if (owner == null) {
                try {
                    cleanedBitmap.recycle()
                } catch (_: Exception) {}
            }
            pageTranslation.cleanedBitmap = null
            pageTranslation.updatedAt = System.currentTimeMillis()
            if (attempt == null) {
                persistPageWithOomRecovery(store, pageKey, pageTranslation)
            }
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
        owner: CleanedOwner? = null,
        attempt: AttemptAuthority? = null,
    ) {
        pageTranslation.sourceFileName = pageKey
        pageTranslation.ocrStatus = StageStatus.READY
        pageTranslation.translationStatus = StageStatus.READY
        pageTranslation.inpaintStatus = StageStatus.RUNNING
        pageTranslation.renderStatus = StageStatus.PENDING
        pageTranslation.errorMessage = null
        updatePageFromCurrentSnapshot(store, pageKey, "resumed inpaint running", attempt = attempt) {
            (it ?: pageTranslation.copyForResume()).apply {
                sourceFileName = pageKey
                inpaintStatus = StageStatus.RUNNING
                renderStatus = StageStatus.PENDING
                errorMessage = null
                updatedAt = System.currentTimeMillis()
            }
        }

        val cleaned = try {
            TranslationTrace.withStage(
                stage = TranslationTraceStage.INPAINT,
                lane = TranslationTraceLane.NATIVE,
                provider = TranslationTraceProvider.CPU,
                resultOutcome = { if (it == null) TranslationTraceOutcome.FAILURE else TranslationTraceOutcome.SUCCESS },
            ) { recognitionEngine.inpaint(bitmap, pageTranslation) }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            e.printStackTrace()
            pageTranslation.inpaintStatus = StageStatus.FAILED
            // First terminal stage — owns the charge.
            pageTranslation.recordAttemptFailure()
            pageTranslation.errorMessage = e.message
            logcat(LogPriority.ERROR, e) { "Failed to resume inpaint for $pageKey" }
            null
        }

        if (cleaned != null && owner != null) {
            owner.produce(cleaned, CleanedProvenance.INPAINT)
        }
        if (cleaned == null) {
            pageTranslation.renderStatus = StageStatus.FAILED
            // Consequence of the inpaint failure above; recordAttemptFailure no-ops once
            // a stage is already FAILED, so this doesn't double-count.
            pageTranslation.recordAttemptFailure()
            val reason = pageTranslation.errorMessage ?: "inpaint unavailable"
            pageTranslation.errorMessage =
                "Inpainting unavailable ($reason) — translated text was not rendered."
            if (attempt != null) {
                attempt.write("resumed inpaint unavailable") { current ->
                    current.apply {
                        inpaintStatus = pageTranslation.inpaintStatus
                        renderStatus = StageStatus.FAILED
                        recordAttemptFailure()
                        errorMessage = pageTranslation.errorMessage
                        updatedAt = System.currentTimeMillis()
                    }
                }
            } else {
                persistPageWithOomRecovery(store, pageKey, pageTranslation)
            }
            return
        }

        val companionDir = provider.getCompanionImageDir(
            manga.title,
            source,
            chapter.name,
            chapter.scanlator,
        )
        // Cleaned-image persistence and the render tail are
        // storage publication — defer them OUTSIDE the native permit when the
        // boundary supplied a deferral queue. The tail stays fail-closed: a
        // persist failure still recycles the bitmap, marks render FAILED and
        // persists that state; it fails the page, never silently.
        val persistAndRenderTail: suspend () -> Unit = {
            if (owner != null && attempt != null) {
                val enrolled = cleanedPublication.enrollCleanedPersistence(
                    manga,
                    chapter,
                    source,
                    pageKey,
                    store,
                    pageTranslation,
                    owner,
                    attempt,
                )
                if (enrolled) store.flush()
                if (enrolled && owner.awaitPersistence()) {
                    renderResumedPage(
                        manga, chapter, source, pageKey, store, pageTranslation, cleaned,
                        successMessageSuffix = " (resume inpaint)",
                        stageListener = stageListener,
                        owner = owner,
                        attempt = attempt,
                    )
                } else {
                    attempt.write("resumed cleaned publication failed") { current ->
                        current.apply {
                            renderStatus = StageStatus.FAILED
                            errorMessage = "Cleaned image publication failed"
                            updatedAt = System.currentTimeMillis()
                        }
                    }
                }
            } else {
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
                    if (owner != null) {
                        owner.release()
                    } else {
                        try {
                            cleaned.recycle()
                        } catch (_: Exception) {}
                    }
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
                        owner = owner,
                        attempt = attempt,
                    )
                }
            }
        }
        if (deferredPublications != null) {
            deferredPublications.enqueue(persistAndRenderTail)
        } else {
            persistAndRenderTail()
        }
    }

    /**
     * Retry-then-block for inpainting: on no cleaned bitmap, re-run recognize()
     * on a half-sampled decode (heap pressure is the dominant failure cause) rather
     * than overlaying text on the original. Returns cleanedBitmap set on success,
     * unchanged on failure - the caller surfaces an honest FAILED render.
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
                if (pageTranslation.inpaintingModeUsed == null) {
                    val decision = inpaintingStampDecision()
                    pageTranslation.inpaintingModeUsed = decision.mode.stampName(
                        neuralModel = decision.neuralModel,
                        neuralAvailable = decision.neuralAvailable ?: true,
                    )
                }
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
     * Batch preflight (detect + OCR only): analyze() fills blocks and stashes
     * detections for a later inpaint pass; cleanedBitmap stays null. The CALLER
     * recycles the bitmap; the page is re-decoded for inpainting (private byte
     * buffer, no shared-stream race). Returns READY (FAILED on OOM), persists blocks.
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
        // The OCR-write precondition captures the page version and prior OCR
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
            e.printStackTrace()
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

        finalizePostOcrStage(pageTranslation, inpaintAlreadyRan = pageTranslation.cleanedBitmap != null)

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
        // still get erased after a resume/reopen. The preconditioned merge ensures a stale
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
     * Batch inpainting on an analyzed page: re-decoded bitmap + blocks ->
     * cleaned bitmap for downstream persistence + render; caller recycles the
     * bitmap. Sets inpaintStatus=RUNNING then FAILED on throw; READY/cleanedImageName
     * are caller-written after JPEG encode; returned cleanedBitmap stays for render.
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
            e.printStackTrace()
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
        attempt: AttemptAuthority? = null,
    ): PageTranslation {
        val pageStart = System.nanoTime()
        var pageTranslation: PageTranslation
        val finalSampleSize = decoded.sampleSize
        try {
            preflightAnalyzeGate(bitmap, fileName)
            pageTranslation = TranslationTrace.withStage(
                stage = TranslationTraceStage.OCR,
                lane = TranslationTraceLane.NATIVE,
                provider = TranslationTraceProvider.CPU,
                resultOutcome = {
                    if (it.ocrStatus == StageStatus.FAILED) TranslationTraceOutcome.FAILURE else TranslationTraceOutcome.SUCCESS
                },
            ) { recognitionEngine.analyze(bitmap) }
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
                updatePageFromCurrentSnapshot(store, fileName, "single-page recognition OOM diagnostic", attempt = attempt) {
                    (it ?: PageTranslation()).apply {
                        errorMessage = "ONNX recognition failed due to memory pressure. Retry after memory recovers."
                    }
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            e.printStackTrace()
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

        val ocrFailed = pageTranslation.ocrStatus == StageStatus.FAILED
        if (!ocrFailed && pageTranslation.blocks.isNotEmpty()) {
            pageTranslation.inpaintStatus = StageStatus.PENDING
            pageTranslation.updatedAt = System.currentTimeMillis()
        }

        pageTranslation.decodeSampleSize = finalSampleSize
        finalizePostOcrStage(pageTranslation, inpaintAlreadyRan = pageTranslation.cleanedBitmap != null)

        pageTranslation.originalImgWidth = decoded.originalWidth.toFloat()
        pageTranslation.originalImgHeight = decoded.originalHeight.toFloat()
        pageTranslation.sourceFileName = fileName
        pageTranslation.updatedAt = System.currentTimeMillis()

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

        coroutineContext.ensureActive()

        val roi = recognitionEngine as? RoiPageRecognitionEngine
        val detMs = roi?.lastDetectMs ?: 0.0
        val segMs = roi?.lastSegmentMs ?: 0.0
        val ocrMs = roi?.lastOcrMs ?: 0.0
        val inpaintMs = roi?.lastInpaintMs ?: 0.0
        val leafCount = roi?.lastLeafCount ?: pageTranslation.blocks.size
        val boxCount = roi?.lastBoxCount ?: pageTranslation.blocks.size
        val provider = roi?.lastProvider ?: "cpu"
        val totalNativeMs = detMs + segMs + ocrMs + inpaintMs

        NativeVisionTelemetry.logBudgetSummary(
            pageKey = fileName,
            targetMs = 2000,
            detMs = detMs,
            segMs = segMs,
            ocrMs = ocrMs,
            inpaintMs = inpaintMs,
            leafCount = leafCount,
            boxCount = boxCount,
            provider = provider,
            totalNativeMs = totalNativeMs,
        )

        return pageTranslation
    }

    /**
     * An explicit user force clears the
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
    /** Completes after a lazy cleaned-image write reaches durable storage. */
    val pendingCleanedPublication: Deferred<Boolean>? = null,
)
