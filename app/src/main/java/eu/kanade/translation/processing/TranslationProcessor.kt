package eu.kanade.translation.processing

import android.content.Context
import android.graphics.Bitmap
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.translation.engines.inpainting.InpaintingHardwareOverride
import eu.kanade.translation.engines.translator.NativeStallWatchdog
import eu.kanade.translation.engines.translator.TextTranslator
import eu.kanade.translation.engines.vision.ocr.PageRecognitionEngine
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.isTextlessTerminal
import eu.kanade.translation.persistence.TranslationFileProvider
import eu.kanade.translation.processing.CleanedPublication
import eu.kanade.translation.processing.DecodedPage
import eu.kanade.translation.processing.EngineLane
import eu.kanade.translation.processing.PageDecode
import eu.kanade.translation.processing.adaptive.DevicePagePermitGate
import eu.kanade.translation.processing.adaptive.DevicePermitPriority
import eu.kanade.translation.processing.execution.NativeRunQuarantine
import eu.kanade.translation.processing.execution.TranslationStreamRegistry
import eu.kanade.translation.processing.memory.MemoryGovernance
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.translation.TranslationPreferences
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * TachiyomiAT: reader-pipeline stage vocabulary for live feedback. These are
 * the real boundaries [processSinglePage] traverses — no stage is invented
 * that does not exist in this processor. Deliberately NOT a persistence
 * concern: signals are transient, wired reader-side only.
 */
enum class TranslationStageSignal {
    PREPARING,
    READING,
    TRANSLATING,
    CLEANING,
    FINISHING,
}

/** Executes one page's decode, OCR, translation, and inpaint operations. */
internal class TranslationProcessor(
    private val context: Context,
    private val provider: TranslationFileProvider? = null,
    private val translationPreferences: TranslationPreferences? = null,
    private val streamRegistry: TranslationStreamRegistry? = null,
    private val suppliedCleanedPublication: CleanedPublication? = null,
) : AutoCloseable {

    sealed interface ProcessResult {
        data class Success(val page: PageTranslation) : ProcessResult
        data class Failure(
            val pageKey: String,
            val failedStage: String,
            val errorMessage: String,
            val cause: Throwable? = null,
            val page: PageTranslation? = null,
        ) : ProcessResult
    }

    private val nativeRunScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val nativeStallWatchdog = NativeStallWatchdog(
        scope = nativeRunScope,
        thresholdMs = TranslationProcessingTimeouts.NATIVE_STALL_THRESHOLD_MS,
    )
    private val nativeRunQuarantine = NativeRunQuarantine(
        scope = nativeRunScope,
        occupancyObserver = object : NativeRunQuarantine.OccupancyObserver {
            override fun onLaneOccupied(token: Long, pageKey: String, startedAtEpochMs: Long) {
                nativeStallWatchdog.onLaneOccupied(token, pageKey, startedAtEpochMs)
            }

            override fun onLaneReleased(token: Long) {
                nativeStallWatchdog.onLaneReleased(token)
            }
        },
    )
    private val inFlightPageKeys = ConcurrentHashMap.newKeySet<String>()
    private val engineLane: EngineLane? = if (translationPreferences != null) {
        EngineLane(
            context = context,
            translationPreferences = translationPreferences,
            nativeRunQuarantine = nativeRunQuarantine,
            inFlightPageKeys = inFlightPageKeys,
            onPageStuck = { onPageStuck },
            drainScope = nativeRunScope,
        )
    } else {
        null
    }
    private val devicePagePermitGate = DevicePagePermitGate()
    private val cleanedPublication: CleanedPublication? by lazy {
        suppliedCleanedPublication ?: if (provider != null && streamRegistry != null && engineLane != null) {
            CleanedPublication(
                provider = provider,
                streamRegistry = streamRegistry,
                currentInpaintingMode = { engineLane.currentInpaintingMode },
                currentNeuralInpaintModel = { engineLane.currentNeuralInpaintModel },
            )
        } else {
            null
        }
    }

    @Volatile
    var onPageStuck: ((chapterId: Long?, pageKey: String) -> Unit)? = null

    val nativeStall get() = nativeStallWatchdog.state
    fun permitHolderPageKeySnapshot(): String? = engineLane?.permitHolderPageKeySnapshot()
    val recognitionEngine: PageRecognitionEngine get() = requireEngineLane().recognitionEngine
    val textTranslator: TextTranslator get() = requireEngineLane().textTranslator

    private fun requireEngineLane(): EngineLane = checkNotNull(engineLane) {
        "TranslationProcessor was created without production engine configuration"
    }

    suspend fun prepareEngines(
        from: eu.kanade.translation.model.TextRecognizerLanguage,
        to: eu.kanade.translation.model.TextTranslatorLanguage,
    ) = requireEngineLane().ensureEnginesBuiltFor(from, to)

    suspend fun warmUp() = requireEngineLane().warmUp()

    fun setInpaintingHardwareOverride(override: InpaintingHardwareOverride) =
        requireEngineLane().setInpaintingHardwareOverride(override)

    fun closeEngines() = requireEngineLane().closeEngines()

    fun forceReleaseNativeBuffers() {
        engineLane?.let { lane -> MemoryGovernance.forceReleaseNativeBuffers { lane.recognitionEngine } }
    }

    fun companionDirectory(manga: Manga, chapter: Chapter, source: HttpSource): UniFile? =
        provider?.getCompanionImageDir(manga.title, source, chapter.name, chapter.scanlator)

    /** Decodes a source image under the shared memory budget. */
    suspend fun decodeImage(
        fileName: String,
        streamFn: () -> InputStream,
        recognitionEngine: () -> PageRecognitionEngine,
    ): DecodedPage? = PageDecode.decodePageBitmapForTranslation(
        context = context,
        recognitionEngine = recognitionEngine,
        fileName = fileName,
        streamFn = streamFn,
    )

    /** Releases a decoded source bitmap when a caller's OCR/inpaint stages end. */
    fun releaseDecoded(decoded: DecodedPage?) {
        decoded?.bitmap?.let(::recycleIfNeeded)
    }

    fun releasePageBitmaps(page: PageTranslation) {
        page.cleanedBitmap?.let(::recycleIfNeeded)
        page.cleanedBitmap = null
    }

    /** Executes OCR on the serialized native lane. */
    suspend fun analyze(
        pageKey: String,
        bitmap: Bitmap,
        recognitionEngine: PageRecognitionEngine,
        chapterId: Long? = null,
        chapterName: String = "",
        priority: DevicePermitPriority = DevicePermitPriority.BACKGROUND,
    ): PageTranslation = withContext(Dispatchers.Default) {
        var timedOut = false
        var admissionFailed = false
        val result = withNativeLane(
            chapterId = chapterId,
            chapterName = chapterName,
            pageKey = pageKey,
            priority = priority,
            onTimeout = { timedOut = true },
            onAdmissionFailure = { admissionFailed = true },
        ) { recognitionEngine.analyze(bitmap) }
        val page = result ?: PageTranslation(sourceFileName = pageKey).apply {
            ocrStatus = StageStatus.FAILED
            translationStatus = StageStatus.PENDING
            inpaintStatus = StageStatus.PENDING
            errorMessage = when {
                timedOut -> "OCR/native operation timed out"
                admissionFailed -> "OCR/native lane admission failed"
                else -> "OCR/native operation returned no result"
            }
        }
        page.sourceFileName = pageKey
        page.imgWidth = bitmap.width.toFloat()
        page.imgHeight = bitmap.height.toFloat()
        page.updatedAt = System.currentTimeMillis()
        if (page.ocrStatus != StageStatus.FAILED) page.ocrStatus = StageStatus.READY
        if (page.blocks.isEmpty() && page.ocrStatus == StageStatus.READY) {
            logcat(LogPriority.INFO) { "TranslationProcessor: page $pageKey has no text detected (textless)" }
            page.translationStatus = StageStatus.SKIPPED
            page.inpaintStatus = StageStatus.SKIPPED
        } else if (page.ocrStatus == StageStatus.READY) {
            page.translationStatus = StageStatus.PENDING
            page.inpaintStatus = StageStatus.PENDING
        }
        page
    }

    /** Translates text blocks outside the native lane. */
    suspend fun translatePage(pageKey: String, page: PageTranslation, translator: TextTranslator) =
        withContext(Dispatchers.IO) {
            if (page.blocks.isEmpty() || page.isTextlessTerminal) return@withContext
            page.translationStatus = StageStatus.RUNNING
            engineLane?.beginTranslatorUse()
            try {
                translator.translatePage(pageKey, page)
                page.translationStatus = StageStatus.READY
                page.translationError = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                page.translationStatus = StageStatus.FAILED
                page.translationError = t.message ?: "Translation failed"
                throw t
            } finally {
                engineLane?.endTranslatorUse()
            }
        }

    /** Batch translation shares the same translator but does not add retry policy. */
    suspend fun translateBatch(pages: MutableMap<String, PageTranslation>, translator: TextTranslator) =
        withContext(Dispatchers.IO) {
            val targets = pages.filterValues { it.blocks.isNotEmpty() && !it.isTextlessTerminal }
            if (targets.isEmpty()) return@withContext
            targets.values.forEach { it.translationStatus = StageStatus.RUNNING }
            engineLane?.beginTranslatorUse()
            try {
                translator.translate(targets.toMutableMap())
                targets.values.forEach {
                    it.translationStatus = StageStatus.READY
                    it.translationError = null
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                targets.values.forEach {
                    it.translationStatus = StageStatus.FAILED
                    it.translationError = t.message ?: "Batch translation failed"
                }
                throw t
            } finally {
                engineLane?.endTranslatorUse()
            }
        }

    /** Inpaints on the native lane and persists a verified cleaned image when possible. */
    suspend fun inpaint(
        pageKey: String,
        bitmap: Bitmap,
        page: PageTranslation,
        recognitionEngine: PageRecognitionEngine,
        companionDir: UniFile? = null,
        chapterId: Long? = null,
        chapterName: String = "",
        priority: DevicePermitPriority = DevicePermitPriority.BACKGROUND,
    ): Bitmap? = withContext(Dispatchers.Default) {
        if (page.blocks.isEmpty() || page.isTextlessTerminal) return@withContext null
        page.inpaintStatus = StageStatus.RUNNING
        var timedOut = false
        var admissionFailed = false
        val cleaned = try {
            withNativeLane(
                chapterId,
                chapterName,
                pageKey,
                priority,
                onTimeout = { timedOut = true },
                onAdmissionFailure = { admissionFailed = true },
            ) { recognitionEngine.inpaint(bitmap, page) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (oom: OutOfMemoryError) {
            logcat(LogPriority.ERROR, oom) { "TranslationProcessor: OOM during inpaint for page $pageKey" }
            recognitionEngine.reclaimPooledMemory()
            recognitionEngine.forceReleaseNativeBuffers()
            page.inpaintStatus = StageStatus.FAILED
            page.inpaintError = "Out of memory during inpainting"
            page.originalImageFallback = true
            return@withContext null
        } catch (t: Throwable) {
            logcat(LogPriority.WARN, t) { "TranslationProcessor: inpaint failed for page $pageKey" }
            page.inpaintStatus = StageStatus.FAILED
            page.inpaintError = t.message ?: "Inpainting failed"
            page.originalImageFallback = true
            return@withContext null
        }

        if (timedOut) {
            page.inpaintStatus = StageStatus.FAILED
            page.inpaintError = "Inpainting/native operation timed out"
            page.originalImageFallback = true
            return@withContext null
        }
        if (admissionFailed) {
            page.inpaintStatus = StageStatus.FAILED
            page.inpaintError = "Inpainting was not admitted to the native lane"
            page.originalImageFallback = true
            return@withContext null
        }
        if (cleaned == null) {
            if (page.inpaintStatus != StageStatus.FAILED) page.inpaintStatus = StageStatus.READY
            page.originalImageFallback = true
            return@withContext null
        }

        page.cleanedBitmap = cleaned
        page.inpaintStatus = StageStatus.READY
        page.inpaintError = null
        page.inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION
        try {
            if (companionDir != null) {
                val publication = cleanedPublication
                if (publication != null) {
                    val written = withContext(Dispatchers.IO) {
                        publication.writeVerifiedCleanedImage(companionDir, pageKey, cleaned)
                    }
                    page.cleanedImageName = written.name
                    page.cleanedImageContentHash = written.contentSha256
                    page.inpaintingModeUsed = publication.currentInpaintingStamp()
                }
            }
            if (page.cleanedImageName == null) page.originalImageFallback = true
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                if (page.cleanedBitmap === cleaned) {
                    recycleIfNeeded(cleaned)
                    page.cleanedBitmap = null
                }
            }
            throw cancelled
        } catch (t: Throwable) {
            logcat(LogPriority.WARN, t) { "TranslationProcessor: cleaned image publication failed for $pageKey" }
            page.cleanedImageName = null
            page.cleanedImageContentHash = null
            page.originalImageFallback = true
        }
        cleaned
    }

    /** Decode → OCR → translate → inpaint, with operation and native-lane timeouts. */
    suspend fun processSinglePage(
        pageKey: String,
        streamFn: () -> InputStream,
        recognitionEngine: PageRecognitionEngine,
        translator: TextTranslator,
        companionDir: UniFile? = null,
        inpaintingEnabled: Boolean = true,
        chapterId: Long? = null,
        chapterName: String = "",
        priority: DevicePermitPriority = DevicePermitPriority.BACKGROUND,
        stageReporter: ((TranslationStageSignal) -> Unit)? = null,
    ): ProcessResult {
        var sourceBitmap: Bitmap? = null
        var page: PageTranslation? = null
        var activeStage = "DECODE"
        return try {
            // TachiyomiAT: publish the transient Preparing boundary before any
            // work starts so feedback can distinguish "queued" from "running".
            stageReporter?.invoke(TranslationStageSignal.PREPARING)
            withTimeout(TranslationProcessingTimeouts.SINGLE_PAGE_TIMEOUT_MS) {
                val decoded = decodeImage(pageKey, streamFn) { recognitionEngine }
                    ?: return@withTimeout ProcessResult.Failure(
                        pageKey,
                        "DECODE",
                        "Failed to decode page image for $pageKey",
                        page = PageTranslation(sourceFileName = pageKey).apply {
                            ocrStatus = StageStatus.FAILED
                            errorMessage = "Failed to decode page image for $pageKey"
                        },
                    )
                sourceBitmap = decoded.bitmap
                activeStage = "OCR"
                // TachiyomiAT: decodeImage performs BOTH text detection and OCR
                // in recognitionEngine.analyze for the reader pipeline, so the
                // combined signal after decode is Reading-text. It must NEVER
                // claim a render/cleaning state that has not started yet.
                stageReporter?.invoke(TranslationStageSignal.READING)
                page = analyze(pageKey, decoded.bitmap, recognitionEngine, chapterId, chapterName, priority).apply {
                    originalImgWidth = decoded.originalWidth.toFloat()
                    originalImgHeight = decoded.originalHeight.toFloat()
                    decodeSampleSize = decoded.sampleSize
                    sourceFingerprint = decoded.sourceFingerprint
                }
                if (page!!.ocrStatus == StageStatus.FAILED) {
                    return@withTimeout ProcessResult.Failure(
                        pageKey,
                        "OCR",
                        page!!.ocrError ?: page!!.errorMessage ?: "OCR failed",
                        page = page,
                    )
                }
                if (page!!.isTextlessTerminal) return@withTimeout ProcessResult.Success(page!!)
                activeStage = "TRANSLATION"
                stageReporter?.invoke(TranslationStageSignal.TRANSLATING)
                try {
                    translatePage(pageKey, page!!, translator)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (t: Throwable) {
                    return@withTimeout ProcessResult.Failure(
                        pageKey,
                        "TRANSLATION",
                        page!!.translationError ?: "Translation failed",
                        t,
                        page,
                    )
                }
                if (inpaintingEnabled) {
                    activeStage = "INPAINT"
                    stageReporter?.invoke(TranslationStageSignal.CLEANING)
                    inpaint(pageKey, decoded.bitmap, page!!, recognitionEngine, companionDir, chapterId, chapterName, priority)
                } else {
                    activeStage = "INPAINT"
                    page!!.inpaintStatus = StageStatus.READY
                    page!!.originalImageFallback = true
                }
                // TachiyomiAT: the candidate is assembled; remaining work is
                // persistence of the final record by the caller. Signal this so
                // the status pill can distinguish last-mile latency.
                stageReporter?.invoke(TranslationStageSignal.FINISHING)
                ProcessResult.Success(page!!)
            }
        } catch (cancelled: CancellationException) {
            if (cancelled is TimeoutCancellationException) {
                val current = page
                if (current != null) {
                    when (activeStage) {
                        "DECODE", "OCR" -> {
                            current.ocrStatus = StageStatus.FAILED
                            current.ocrError = "Single-page OCR operation timed out"
                        }
                        "INPAINT" -> {
                            current.inpaintStatus = StageStatus.FAILED
                            current.inpaintError = "Single-page inpainting timed out"
                            current.originalImageFallback = true
                        }
                        else -> {
                            current.translationStatus = StageStatus.FAILED
                            current.translationError = "Single-page translation timed out"
                        }
                    }
                }
                ProcessResult.Failure(pageKey, activeStage, "Single-page operation timed out", cancelled, current)
            } else {
                throw cancelled
            }
        } catch (t: Throwable) {
            logcat(LogPriority.ERROR, t) { "TranslationProcessor: processSinglePage failed for $pageKey" }
            page?.let {
                if (it.translationStatus == StageStatus.RUNNING) {
                    it.translationStatus = StageStatus.FAILED
                    it.translationError = t.message ?: "Translation processing failed"
                }
            }
            ProcessResult.Failure(pageKey, "PROCESSING", t.message ?: "Translation processing failed", t, page)
        } finally {
            withContext(NonCancellable) {
                page?.let(::releasePageBitmaps)
                sourceBitmap?.let(::recycleIfNeeded)
            }
        }
    }

    private suspend fun <T> withNativeLane(
        chapterId: Long?,
        chapterName: String,
        pageKey: String,
        priority: DevicePermitPriority,
        onTimeout: suspend () -> Unit,
        onAdmissionFailure: () -> Unit,
        block: suspend () -> T,
    ): T? {
        val engineLane = engineLane ?: return block()
        if (!inFlightPageKeys.add(pageKey)) {
            onAdmissionFailure()
            return null
        }
        return try {
            val permit = devicePagePermitGate.acquire(priority) ?: run {
                onAdmissionFailure()
                return null
            }
            try {
                engineLane.withNativeLane(
                    timeoutMs = TranslationProcessingTimeouts.ONNX_PHASE_TIMEOUT_MS,
                    chapterId = chapterId,
                    chapterName = chapterName,
                    pageKey = pageKey,
                    onTimeout = onTimeout,
                ) { block() }
            } finally {
                permit.release()
            }
        } finally {
            inFlightPageKeys.remove(pageKey)
        }
    }

    private fun recycleIfNeeded(bitmap: Bitmap) {
        if (!bitmap.isRecycled) bitmap.recycle()
    }

    override fun close() {
        engineLane?.closeEngines()
        nativeRunScope.cancel()
    }
}
