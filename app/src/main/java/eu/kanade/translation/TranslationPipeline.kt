package eu.kanade.translation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import coil3.imageLoader
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import eu.kanade.translation.artifact.ArtifactStage
import eu.kanade.translation.artifact.StageFingerprints
import eu.kanade.translation.batch.BatchProgressReconciler
import eu.kanade.translation.batch.BatchResumeGateDecider
import eu.kanade.translation.batch.ChunkBatchCoordinator
import eu.kanade.translation.batch.NativeLaneWorker
import eu.kanade.translation.batch.OcrReadyPageRef
import eu.kanade.translation.batch.RenderJoinWorker
import eu.kanade.translation.batch.TranslationBatchProgressTracker
import eu.kanade.translation.batch.TranslatorLaneWorker
import eu.kanade.translation.data.TranslationProvider
import eu.kanade.translation.inpainting.InpaintingMode
import eu.kanade.translation.model.BatchContextCheckpoint
import eu.kanade.translation.model.BatchExpectedFingerprints
import eu.kanade.translation.model.BatchPlannerInput
import eu.kanade.translation.model.BatchStage
import eu.kanade.translation.model.ContextCheckpointState
import eu.kanade.translation.model.PageStage
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.PageWorkPlanner
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.detachedCopy
import eu.kanade.translation.model.hasCurrentInpaintMask
import eu.kanade.translation.model.hasCurrentInpaintResult
import eu.kanade.translation.model.isCleanedImageReady
import eu.kanade.translation.model.prepareForcedRetry
import eu.kanade.translation.model.recordAttemptFailure
import eu.kanade.translation.model.resetAttemptCharge
import eu.kanade.translation.model.stableFingerprint
import eu.kanade.translation.ocr.OcrModelCatalog
import eu.kanade.translation.ocr.TextRecognizerLanguage
import eu.kanade.translation.recognition.PageRecognitionEngine
import eu.kanade.translation.recognition.RoiPageRecognitionEngine
import eu.kanade.translation.rendering.RenderColorEstimator
import eu.kanade.translation.scheduling.NativeRunQuarantine
import eu.kanade.translation.scheduling.PreparedPage
import eu.kanade.translation.scheduling.TranslationExecutor
import eu.kanade.translation.scheduling.TranslationStageEvent
import eu.kanade.translation.scheduling.TranslationStageListener
import eu.kanade.translation.scheduling.TranslationStreamRegistry
import eu.kanade.translation.scheduling.isPreparedPageTerminal
import eu.kanade.translation.scheduling.publishPreparedPageFromOcr
import eu.kanade.translation.translator.AiTranslationRetryPlanner
import eu.kanade.translation.translator.AiTranslatorKind
import eu.kanade.translation.translator.ChapterGlossaryBuilder
import eu.kanade.translation.translator.ContextualStructuralFailureException
import eu.kanade.translation.translator.ContextualTextTranslator
import eu.kanade.translation.translator.DeepSeekTranslator
import eu.kanade.translation.translator.GeminiTranslator
import eu.kanade.translation.translator.InactivityFlusher
import eu.kanade.translation.translator.LmStudioTranslator
import eu.kanade.translation.translator.OpenRouterTranslator
import eu.kanade.translation.translator.StableBlockIds
import eu.kanade.translation.translator.StreamingChunkPlanner
import eu.kanade.translation.translator.TextTranslator
import eu.kanade.translation.translator.TextTranslatorLanguage
import eu.kanade.translation.translator.TranslationBlockValidation
import eu.kanade.translation.translator.TranslationContextChunk
import eu.kanade.translation.translator.TranslationContextChunkPlanner
import eu.kanade.translation.translator.TranslationEngineBuilder
import eu.kanade.translation.translator.TranslatorComputeClass
import eu.kanade.translation.translator.translateAiChunkWithAdaptiveRetry
import eu.kanade.translation.util.ShortHash
import eu.kanade.translation.util.TranslationMemoryBudget
import eu.kanade.translation.util.TranslationMemoryBudget.DecodeDecision
import eu.kanade.translation.util.TranslationMemoryBudget.DecodeDecisionKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import mihon.core.archive.archiveReader
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.translation.AiEngine
import tachiyomi.domain.translation.OcrModel
import tachiyomi.domain.translation.TranslationEngineCategory
import tachiyomi.domain.translation.TranslationPreferences
import tachiyomi.domain.translation.pools.BitmapPool
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

class LayoutFailureException(val blockIds: List<String>, message: String) : Exception(message)

class TranslationPipeline(
    private val context: Context,
    private val provider: TranslationProvider,
    private val downloadProvider: DownloadProvider = Injekt.get(),
    private val translationPreferences: TranslationPreferences = Injekt.get(),
    private val streamRegistry: TranslationStreamRegistry = Injekt.get(),
) : TranslationExecutor, java.io.Closeable {

    override fun close() {
        nativeRunScope.cancel()
    }

    companion object {
        /**
         * Maximum wall-clock time a single page may hold the sole
         * native lane during [translateSinglePage]. Bounds the damage of
         * a hung ONNX inference or a stalled AI/HTTP call so it can't starve
         * every other page's translation for the whole session. Generous
         * (on-device OCR + inpaint + render of one page can take tens of seconds
         * on a large image), but finite.
         */
        const val SINGLE_PAGE_TIMEOUT_MS = 120_000L

        /**
         * Maximum number of local retries the single-page path performs when a
         * translation comes back PARTIAL (some blocks translated, some not).
         * Each retry re-requests ONLY the still-untranslated blocks via
         * [AiTranslationRetryPlanner.untranslatedBlocks]. Mirrors the batch
         * path's adaptive retry behaviour so the interactive reader path
         * benefits from the same second-chance logic. Does NOT bump the page's
         * persistent retryCount — PARTIAL must not burn the permanent retry
         * budget (contract #14b); this is a local loop counter only.
         */
        const val SINGLE_PAGE_PARTIAL_MAX_RETRIES = 2

        /**
         * Tighter timeout for the permit-held ONNX phase (decode → OCR → inpaint →
         * persist .cleaned). The HTTP translate + Canvas render phase runs outside
         * the permit and has its own timeout (OkHttp timeouts + fast Canvas). Keeping
         * the ONNX phase shorter ensures the permit is released promptly so the next
         * prefetch page's ONNX work can overlap this page's network call.
         */
        const val ONNX_PHASE_TIMEOUT_MS = 90_000L

        /**
         * Hard cap on the number of in-memory cleaned bitmaps the 3-lane batch
         * pipeline holds for render reuse at once. A pure count cap is unsafe for
         * memory (3 tiny pages say nothing about 3x a 48MB webtoon strip), so it
         * is enforced together with [HELD_BITMAP_BYTE_CEILING]; the byte ceiling is
         * the real bound and this count just prevents runaway concurrency on many
         * tiny pages.
         */
        const val HELD_BITMAP_MAX_COUNT = 4

        /**
         * Approximate byte ceiling for the held cleaned-bitmap registry in the
         * 3-lane batch pipeline (~48 MB; a worst-case webtoon long-strip page).
         * Pages that would push the registry past this spill to disk (their
         * versioned .cleaned.jpg is already durable at inpaint) and reload on render —
         * today's behavior. Keeps peak held memory provable against the ceiling
         * regardless of page or chunk size.
         */
        const val HELD_BITMAP_BYTE_CEILING = 48L * 1024L * 1024L

        const val UNKNOWN_SOURCE_FINGERPRINT = "source-fingerprint-unavailable"
    }

    /** Per-page resume decision in the 3-lane batch pipeline (Lane A). Lives at
     *  class scope because Kotlin forbids local enum classes. */
    private enum class BatchResumeGate { SKIP_ALL, INPAINT_ONLY, FULL }

    /** Native admission is owned by [nativeRunQuarantine]. */

    private data class PermitHolder(val pageKey: String)

    @Volatile
    private var permitHolder: PermitHolder? = null

    internal fun permitHolderPageKeySnapshot(): String? = permitHolder?.pageKey

    private val engineRebuildMutex = kotlinx.coroutines.sync.Mutex()

    /**
     * pageKeys currently mid-flight in [translateSinglePage]. Guards against
     * the same page being queued behind itself (e.g. auto-mode re-enqueue on
     * scroll, or a user double-tapping the per-page button). Thread-safe
     * because native quarantine and [closeEngines] touch this outside
     * the native lane.
     */
    private val inFlightPageKeys = ConcurrentHashMap.newKeySet<String>()

    // Native work runs in an independent scope so caller cancellation cannot
    // falsely signal native exit. The quarantine owns admission until real exit.
    private val nativeRunScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val nativeRunQuarantine = NativeRunQuarantine(nativeRunScope)

    /**
     * Listener notified by [withNativeLane] when a page overruns its deadline,
     * after the real native invocation exits quarantine. Wired by
     * [TranslationManager] so it can also evict the dead job from
     * `activePageJobs`, otherwise the `existing.isActive` dedup keeps silently
     * dropping every retry of that page forever.
     */
    @Volatile
    var onPageStuck: ((chapterId: Long?, pageKey: String) -> Unit)? = null

    /**
     * TachiyomiAT: factory that creates a [TranslationBatchProgressTracker] for a
     * batch and registers it in the manager's tracker map so the UI can observe
     * it. Mirrors the [activeStoreResolver] pattern. Set by [TranslationManager].
     */
    @Volatile
    var batchTrackerFactory: ((chapterId: Long, store: ChapterTranslationStore, orderedPageKeys: List<String>) -> TranslationBatchProgressTracker?)? = null

    private suspend fun <T> withNativeLane(
        timeoutMs: Long,
        chapterId: Long?,
        chapterName: String,
        pageKey: String,
        onTimeout: suspend () -> Unit,
        block: suspend () -> T,
    ): T? {
        val holder = PermitHolder(pageKey)
        permitHolder = holder
        return try {
            when (
                val outcome = nativeRunQuarantine.run(
                    chapter = chapterName,
                    pageKey = pageKey,
                    timeoutMs = timeoutMs,
                    onTimeout = {
                        onTimeout()
                        onPageStuck?.invoke(chapterId, pageKey)
                    },
                    block = block,
                )
            ) {
                is NativeRunQuarantine.Outcome.Accepted -> outcome.value
                is NativeRunQuarantine.Outcome.TimedOut -> null
            }
        } finally {
            if (permitHolder === holder) permitHolder = null
        }
    }

    @Volatile
    private var currentFromLang: TextRecognizerLanguage

    @Volatile
    private var currentOcrModel: OcrModel

    // RoiPageRecognitionEngine caches the resolved reading order once per instance,
    // so a runtime flip requires a recognition rebuild — same logic as fromLang/ocrModel.
    @Volatile
    private var currentReadingOrder: tachiyomi.domain.translation.TranslationReadingOrder

    // @Volatile: these are reassigned from a translation coroutine (language change) and
    // read/closed from closeEngines() WITHOUT the permit (stop() on the main thread), so a
    // race must read a consistent reference, not a half-published one.
    @Volatile
    private var textTranslator: TextTranslator

    @Volatile
    private var recognitionEngine: PageRecognitionEngine

    @Volatile
    private var currentInpaintingMode: InpaintingMode

    // Snapshot of EVERY config dimension used to build textTranslator. The rebuild gate
    // compares a fresh signature so changing engine category, provider, key, model, temp,
    // max-tokens, analytical-mode, reading-order, or languages forces a rebuild — not just
    // language changes. Without this the cached AI translator (which captures key/model/temp
    // at construction and never re-reads prefs) survives a stop+reconfigure+restart. analyticalMode
    // and readingOrder MUST be included because they're cached at construction too. apiKeyHash is a
    // short non-reversible digest so secrets are never stored/logged.
    @Volatile
    private var currentTranslatorSignature: EngineSignature

    /**
     * Captures the full set of preferences that determine which [TextTranslator]
     * gets built. Two equal signatures guarantee the cached translator reflects
     * exactly this configuration; any difference means a rebuild is required.
     */
    private data class EngineSignature(
        val category: tachiyomi.domain.translation.TranslationEngineCategory,
        val standardEngine: tachiyomi.domain.translation.StandardEngine,
        val aiEngine: tachiyomi.domain.translation.AiEngine,
        val apiKeyHash: String,
        val baseUrl: String,
        val modelName: String,
        val temperature: String,
        val maxTokens: String,
        val analyticalMode: Boolean,
        val readingOrder: tachiyomi.domain.translation.TranslationReadingOrder,
        val fromLang: TextRecognizerLanguage,
        val toLang: TextTranslatorLanguage,
    )

    /**
     * Reads every engine-selection preference live and folds it into an
     * [EngineSignature]. Called at the top of each translate path so the rebuild
     * gate sees the user's current configuration, not whatever was selected when
     * the singleton was first constructed.
     */
    private fun computeTranslatorSignature(
        fromLang: TextRecognizerLanguage,
        toLang: TextTranslatorLanguage,
    ): EngineSignature {
        val aiEngine = translationPreferences.translationAiEngine().get()
        return EngineSignature(
            category = translationPreferences.translationEngineCategory().get(),
            standardEngine = translationPreferences.translationStandardEngine().get(),
            aiEngine = aiEngine,
            apiKeyHash = ShortHash.hash(translationPreferences.translationAiApiKey(aiEngine).get()),
            baseUrl = translationPreferences.translationAiBaseUrlLmStudio().get(),
            modelName = translationPreferences.translationAiModel(aiEngine).get(),
            temperature = translationPreferences.translationAiTemperature().get(),
            maxTokens = translationPreferences.translationAiOutputTokens().get(),
            analyticalMode = translationPreferences.translationAnalyticalMode().get(),
            readingOrder = translationPreferences.translationReadingOrder().get(),
            fromLang = fromLang,
            toLang = toLang,
        )
    }

    init {
        // fromPref/build THROW on invalid config (intended on the translate path). But this
        // object is constructed eagerly as a field initializer in TranslationManager, so an
        // invalid pref at startup must NOT crash here: build defensively and let the first
        // translate's fromPref re-throw and surface the error. ensureEnginesBuiltFor overwrites
        // these once config is valid.
        try {
            val fromLang = TextRecognizerLanguage.fromPref(translationPreferences.translateFromLanguage())
            val toLang = TextTranslatorLanguage.fromPref(translationPreferences.translateToLanguage())
            val ocrModel = OcrModelCatalog.selectedModel(translationPreferences, fromLang)
            currentFromLang = fromLang
            currentOcrModel = ocrModel
            currentInpaintingMode = inpaintingModeFromPref()
            currentReadingOrder = translationPreferences.translationReadingOrder().get()
            recognitionEngine = createRecognitionEngine(fromLang, ocrModel, currentInpaintingMode)
            textTranslator = TranslationEngineBuilder.build(translationPreferences, fromLang, toLang)
            currentTranslatorSignature = computeTranslatorSignature(fromLang, toLang)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) {
                "TachiyomiAT pipeline init: invalid translation config, deferring to first translate"
            }
            currentFromLang = TextRecognizerLanguage.JAPANESE
            currentOcrModel = OcrModel.MLKIT
            currentInpaintingMode = InpaintingMode.FAST
            currentReadingOrder = tachiyomi.domain.translation.TranslationReadingOrder.AUTO
            recognitionEngine = createRecognitionEngine(
                TextRecognizerLanguage.JAPANESE,
                OcrModel.MLKIT,
                InpaintingMode.FAST,
            )
            // Throws on use; entry-point fromPref throws first. Guarantees the field
            // is never null without a lateinit crash.
            textTranslator = object : TextTranslator {
                override val fromLang = TextRecognizerLanguage.JAPANESE
                override val toLang = TextTranslatorLanguage.ENGLISH
                override suspend fun translate(pages: MutableMap<String, PageTranslation>) {
                    throw IllegalStateException("Translation pipeline not initialized (invalid config)")
                }
                override fun close() {}
            }
            currentTranslatorSignature = computeTranslatorSignature(
                TextRecognizerLanguage.JAPANESE,
                TextTranslatorLanguage.ENGLISH,
            )
        }
    }

    private fun inpaintingModeFromPref(): InpaintingMode {
        return when (translationPreferences.translationInpaintingMode().get()) {
            "FAST" -> InpaintingMode.FAST
            else -> InpaintingMode.QUALITY
        }
    }

    private fun createRecognitionEngine(
        lang: TextRecognizerLanguage,
        ocrModel: OcrModel,
        mode: InpaintingMode,
    ): PageRecognitionEngine {
        val onnx = RoiPageRecognitionEngine(context, lang, ocrModel, mode)
        if (onnx.isAvailable) {
            logcat(LogPriority.INFO) { "Using ONNX recognition engine for $lang with OCR model $ocrModel" }
            return onnx
        }
        onnx.close()
        throw IllegalStateException("ONNX recognition unavailable for $lang/$ocrModel")
    }

    fun closeEngines() {
        // A stop invalidates cached engines immediately, but actual teardown may
        // only happen while no admitted native call is alive. If the lane is
        // occupied, the next admitted call rebuilds after the real native exit.
        inFlightPageKeys.clear()
        enginesClosed = true
        val closedNow = nativeRunQuarantine.tryRunExclusive {
            try {
                recognitionEngine.close()
            } catch (_: Exception) {}
            try {
                textTranslator.close()
            } catch (_: Exception) {}
        }
        if (!closedNow) {
            logcat(LogPriority.WARN) {
                "TachiyomiAT engine close deferred: reason=native invocation still alive"
            }
        }
    }

    @Volatile
    private var consecutiveOomCount = 0

    @Volatile
    private var enginesClosed = false

    /**
     * Listener that lets the translator share the same [ChapterTranslationStore]
     * with the [TranslationManager] (and the reader observing it). If no
     * listener is provided, the translator falls back to opening a local store.
     */
    @Volatile
    var activeStoreResolver: ((Translation) -> ChapterTranslationStore?)? = null

    private fun peekReaderPageStream(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        pageKey: String,
    ): (() -> InputStream)? = streamRegistry.peek(manga, chapter, source, pageKey)

    private fun createFailedPagePlaceholder(
        fileName: String,
        errorMessage: String?,
        imgWidth: Float = 0f,
        imgHeight: Float = 0f,
        originalImgWidth: Float = 0f,
        originalImgHeight: Float = 0f,
        decodeSampleSize: Int = 1,
        retryCount: Int = 0,
        // Per-attempt exhaustion counter. Merge callers pass (existing.attemptCount + 1);
        // standalone first-failure callers pass the default 1.
        attemptCount: Int = 1,
    ): PageTranslation {
        return PageTranslation(
            sourceFileName = fileName,
            imgWidth = imgWidth,
            imgHeight = imgHeight,
            originalImgWidth = originalImgWidth,
            originalImgHeight = originalImgHeight,
            decodeSampleSize = decodeSampleSize,
            ocrStatus = StageStatus.FAILED,
            translationStatus = StageStatus.PENDING,
            inpaintStatus = StageStatus.FAILED,
            renderStatus = StageStatus.PENDING,
            updatedAt = System.currentTimeMillis(),
            retryCount = retryCount,
        ).apply {
            this.attemptCount = attemptCount
            this.translationError = errorMessage
            this.ocrError = errorMessage
            this.inpaintError = errorMessage
        }
    }

    /**
     * Writes a FAILED placeholder for [pageKey] into the chapter's shared store
     * when the single-page translation times out. This is the single-page path's
     * counterpart to the batch path's timeout handling: without it the page is
     * stranded as ocrStatus=RUNNING (the first thing [translateSinglePageOnnx]
     * writes) for the rest of the session, which keeps the reader's
     * anyRunning flag true — pinning the TRANSLATING state, disabling the
     * translate icon, and making auto-translate skip the page forever.
     *
     * Resolves the store through [activeStoreResolver] (the shared instance the
     * reader is observing); if none is registered for this chapter the page's
     * RUNNING status will be cleared on the next chapter open instead. Never
     * clobbers an already-completed page — only overwrites entries that are still
     * in a non-terminal (RUNNING/PENDING) state.
     */
    private fun resolveActiveStore(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
    ): ChapterTranslationStore? {
        val syntheticTranslation = Translation(
            source,
            manga,
            chapter,
            TextRecognizerLanguage.JAPANESE,
            TextTranslatorLanguage.ENGLISH,
        )
        return activeStoreResolver?.invoke(syntheticTranslation)
    }

    private suspend fun markPageTimedOut(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        pageKey: String,
    ) {
        // Use SAFE language fallbacks, not the throwing fromPref: this runs in an
        // error/timeout path, so re-throwing here would mask the original failure.
        // The store is keyed on manga/chapter (not language).
        val syntheticTranslation = Translation(
            source,
            manga,
            chapter,
            TextRecognizerLanguage.JAPANESE,
            TextTranslatorLanguage.ENGLISH,
        )
        val store = activeStoreResolver?.invoke(syntheticTranslation) ?: return
        store.invalidateGeneration("timeout chapter=${chapter.name} pageKey=$pageKey")
        val snapshot = store.snapshot(pageKey)
        store.patchPage(pageKey, snapshot.toPrecondition(), "mark page timed out") { existing ->
            // Don't overwrite a page that already produced a durable result.
            if (existing?.cleanedImageName != null) {
                existing
            } else {
                createFailedPagePlaceholder(
                    pageKey,
                    "Translation timed out after ${SINGLE_PAGE_TIMEOUT_MS / 1000}s",
                    imgWidth = existing?.imgWidth ?: 0f,
                    imgHeight = existing?.imgHeight ?: 0f,
                    originalImgWidth = existing?.originalImgWidth ?: 0f,
                    originalImgHeight = existing?.originalImgHeight ?: 0f,
                    decodeSampleSize = existing?.decodeSampleSize ?: 1,
                    retryCount = (existing?.retryCount ?: 0) + 1,
                    attemptCount = (existing?.attemptCount ?: 0) + 1,
                )
            }
        }
    }

    private fun ChapterTranslationStore.PageSnapshot.toPrecondition() =
        ChapterTranslationStore.PatchPrecondition(
            generation = generation,
            pageVersion = pageVersion,
            blockFingerprints = blockFingerprints,
            leaseToken = leaseToken,
            candidateGenerationId = candidateGenerationId,
            dependencyFingerprint = dependencyFingerprint,
            artifactPageVersion = artifactPageVersion,
        )

    private suspend fun updatePageFromCurrentSnapshot(
        store: ChapterTranslationStore,
        pageKey: String,
        description: String,
        expectedGeneration: Long? = null,
        update: (PageTranslation?) -> PageTranslation,
    ): ChapterTranslationStore.PatchResult =
        store.snapshot(pageKey).let { snapshot ->
            store.updatePageGuarded(
                pageKey,
                snapshot.toPrecondition().copy(generation = expectedGeneration ?: snapshot.generation),
                description,
                update,
            )
        }

    private suspend fun markPageFailed(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        pageKey: String,
        error: Throwable,
    ) {
        // SAFE language fallbacks (see markPageTimedOut); re-throwing here would mask the cause.
        val syntheticTranslation = Translation(
            source,
            manga,
            chapter,
            TextRecognizerLanguage.JAPANESE,
            TextTranslatorLanguage.ENGLISH,
        )
        val store = activeStoreResolver?.invoke(syntheticTranslation) ?: return
        updatePageFromCurrentSnapshot(store, pageKey, "single-page failure") { existing ->
            // Don't overwrite a page that already produced a result (rendered/
            // cleaned) — a late error after a successful persist would erase it.
            if (existing != null &&
                existing.cleanedImageName != null
            ) {
                existing
            } else {
                val errorMsg = when (error) {
                    is OutOfMemoryError -> "Out of memory: ${error.message ?: "low memory"}"
                    else -> error.message ?: error.javaClass.simpleName
                }
                createFailedPagePlaceholder(
                    pageKey,
                    errorMsg,
                    imgWidth = existing?.imgWidth ?: 0f,
                    imgHeight = existing?.imgHeight ?: 0f,
                    originalImgWidth = existing?.originalImgWidth ?: 0f,
                    originalImgHeight = existing?.originalImgHeight ?: 0f,
                    decodeSampleSize = existing?.decodeSampleSize ?: 1,
                    retryCount = (existing?.retryCount ?: 0) + 1,
                    attemptCount = (existing?.attemptCount ?: 0) + 1,
                )
            }
        }
    }

    /**
     * Translates a single page identified by [pageKey] within [chapter] of [manga].
     *
     * Phase ONNX (under the permit): setup, decode, recognize (OCR+inpaint), persist
     * .cleaned. Phase translation/render-metadata (outside the permit): cooperative
     * cancel check, textTranslator.translatePage, color estimation, and page-state
     * persistence. The reader draws translated text live over the cleaned image.
     * Splitting the permit-held ONNX work from the network-bound HTTP translate lets the next
     * prefetch page's ONNX overlap this page's network call — the same asymmetry
     * benefit the batch path already derives.
     *
     * Scheduling scope: this single-page path bypasses the batch coordinator's
     * all-OCR barrier (`BatchCoordinator.runPass1`) by design — it processes
     * exactly one page, so there is no chapter-wide OCR set to wait for. The
     * chapter-level "no inpaint before all OCR terminal" invariant only applies
     * to batch/pre-translation; here OCR and inpaint of the same single page run
     * sequentially inside this function.
     */
    override suspend fun translateSinglePage(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        pageKey: String,
        force: Boolean,
        stageListener: TranslationStageListener?,
    ) {
        runSinglePageBoundary(
            manga = manga,
            chapter = chapter,
            source = source,
            pageKey = pageKey,
            streamFn = null,
            force = force,
            stageListener = stageListener,
        )
    }

    override suspend fun translateSinglePageFromStream(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        pageKey: String,
        streamFn: () -> InputStream,
        force: Boolean,
        stageListener: TranslationStageListener?,
    ) {
        runSinglePageBoundary(
            manga = manga,
            chapter = chapter,
            source = source,
            pageKey = pageKey,
            streamFn = streamFn,
            force = force,
            stageListener = stageListener,
        )
    }

    /**
     * Shared single-page driver: native phase under the permit, durable
     * cleaned-image publication, then HTTP translate + render outside the
     * permit. Used by the legacy composing wrappers above and by the prepared-
     * page boundary methods below. [streamFn] is null for the reader-stream
     * peek path.
     *
     * Phase 3: the whole boundary runs under a `READER_ADHOC` page lease. When
     * a batch run owns the page, the request attaches to the batch result
     * (observes store emissions) instead of opening a competing writer.
     */
    private suspend fun runSinglePageBoundary(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        pageKey: String,
        streamFn: (() -> InputStream)?,
        force: Boolean,
        stageListener: TranslationStageListener?,
    ) {
        val leaseStore = resolveActiveStore(manga, chapter, source)
        if (!acquireReaderPageLease(leaseStore, chapter, pageKey)) return
        try {
            val onnxResult = withNativeLane(
                timeoutMs = ONNX_PHASE_TIMEOUT_MS,
                chapterId = chapter.id,
                chapterName = chapter.name,
                pageKey = pageKey,
                onTimeout = { markPageTimedOut(manga, chapter, source, pageKey) },
            ) {
                if (!inFlightPageKeys.add(pageKey)) {
                    logcat(LogPriority.WARN) { "TachiyomiAT native admission rejected: chapter=${chapter.name} pageKey=$pageKey reason=already in flight" }
                    return@withNativeLane null
                }
                try {
                    val store = resolveActiveStore(manga, chapter, source)
                    val generation = store?.snapshot(pageKey)?.generation
                    if (store != null && generation != null) {
                        store.withGeneration(generation) {
                            translateSinglePageOnnx(manga, chapter, source, pageKey, streamFn, force, stageListener)
                        }
                    } else {
                        translateSinglePageOnnx(manga, chapter, source, pageKey, streamFn, force, stageListener)
                    }
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    logcat(LogPriority.ERROR, t) { "TachiyomiAT ONNX phase failed: pageKey=$pageKey" }
                    markPageFailed(manga, chapter, source, pageKey, t)
                    throw t
                } finally {
                    inFlightPageKeys.remove(pageKey)
                }
            } ?: return

            val publishedResult = persistOnnxCleanedImage(manga, chapter, source, pageKey, onnxResult) ?: return

            try {
                withTimeoutOrNull(SINGLE_PAGE_TIMEOUT_MS) {
                    translateSinglePageHttpRender(manga, chapter, source, pageKey, publishedResult, stageListener)
                } ?: run {
                    logcat(LogPriority.WARN) {
                        "TachiyomiAT HTTP+render phase timed out after ${SINGLE_PAGE_TIMEOUT_MS}ms: " +
                            "pageKey=$pageKey chapter=${chapter.name}"
                    }
                    markPageTimedOut(manga, chapter, source, pageKey)
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                logcat(LogPriority.ERROR, t) {
                    "TachiyomiAT HTTP+render phase failed: pageKey=$pageKey"
                }
                markPageFailed(manga, chapter, source, pageKey, t)
                throw t
            }
        } finally {
            releaseReaderPageLease(leaseStore, pageKey)
        }
    }

    /**
     * Phase 3 lease admission for reader-originated single-page work. Returns
     * false when another origin (a batch run) owns the page — the request then
     * attaches to the owner's result through store emissions rather than
     * opening a competing writer.
     */
    private suspend fun acquireReaderPageLease(
        store: ChapterTranslationStore?,
        chapter: Chapter,
        pageKey: String,
    ): Boolean {
        if (store == null) return true
        return when (val acquisition = store.tryAcquirePageStageLease(pageKey, PageStage.Ocr, PageWriteOrigin.READER_ADHOC)) {
            is LeaseAcquisition.Granted -> true
            is LeaseAcquisition.Denied -> {
                logcat(LogPriority.INFO) {
                    "TachiyomiAT reader single-page request attaches to ${acquisition.owner} owner: " +
                        "chapter=${chapter.name} pageKey=$pageKey reason=${acquisition.reason}"
                }
                false
            }
        }
    }

    private suspend fun releaseReaderPageLease(store: ChapterTranslationStore?, pageKey: String) {
        if (store == null) return
        store.releasePageStageLease(pageKey, PageWriteOrigin.READER_ADHOC)
    }

    /** Releases the batch's page lease at an atomic stage boundary (terminal render/failure). */
    private suspend fun releaseBatchPageLease(store: ChapterTranslationStore, pageKey: String) {
        store.releasePageStageLease(pageKey, PageWriteOrigin.BATCH)
    }

    /**
     * Deletes cleaned-image files retained by the store after newer committed
     * display bundles promoted over them. Draining the complete set prevents a
     * rapid sequence of promotions from orphaning an earlier superseded file.
     */
    private suspend fun deleteRetiredCleanedFile(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        pageKey: String,
        store: ChapterTranslationStore,
    ) {
        val chapterId = chapter.id ?: return
        val retired = store.drainRetiredCleanedImages(pageKey)
        if (retired.isEmpty()) return
        retired.forEach { name ->
            streamRegistry.retireCleanedImage(
                sourceId = source.id,
                mangaId = manga.id,
                chapterId = chapterId,
                pageKey = pageKey,
                imageName = name,
            ) {
                if (!store.mayDeleteCleanedImage(pageKey, name)) return@retireCleanedImage
                val deleted = provider.findPageCleanedImage(
                    manga.title,
                    source,
                    chapter.name,
                    chapter.scanlator,
                    name,
                )?.delete() == true
                logcat(if (deleted) LogPriority.INFO else LogPriority.WARN) {
                    "TachiyomiAT retired cleaned image drain: pageKey=$pageKey file=$name deleted=$deleted"
                }
            }
        }
    }

    /**
     * Prepared-page boundary (ticket 02): runs the native phase (decode →
     * detect/OCR → inpaint → persist cleaned image) under the sole native
     * permit and returns a lightweight [PreparedPage] carrying durable
     * identifiers only. The decoded/cleaned bitmap is recycled before return
     * and never crosses the boundary.
     *
     * Returns null on failure before handoff (decode/recognition/persist
     * failure, missing chapter files, generation loss) so a caller cannot
     * mistake a failed page for a prepared one. Returns a terminal
     * [PreparedPage] (isTerminal=true, cleanedImageName may be null) when the
     * page needs no further translation/render work — textless pages,
     * render-only resume that already completed, or an already-fully-rendered
     * page resumed to a no-op.
     *
     * Stage events fire on [stageListener] at actual execution entry:
     * READING before the fused recognize() / analyze() call, CLEANING before a
     * standalone inpaint() (resume-inpaint-only path).
     */
    override suspend fun prepareSinglePage(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        pageKey: String,
        streamFn: (() -> InputStream)?,
        force: Boolean,
        stageListener: TranslationStageListener?,
    ): PreparedPage? {
        val leaseStore = resolveActiveStore(manga, chapter, source)
        if (!acquireReaderPageLease(leaseStore, chapter, pageKey)) return null
        try {
            val onnxResult = withNativeLane(
                timeoutMs = ONNX_PHASE_TIMEOUT_MS,
                chapterId = chapter.id,
                chapterName = chapter.name,
                pageKey = pageKey,
                onTimeout = { markPageTimedOut(manga, chapter, source, pageKey) },
            ) {
                if (!inFlightPageKeys.add(pageKey)) {
                    logcat(LogPriority.WARN) { "TachiyomiAT prepare admission rejected: chapter=${chapter.name} pageKey=$pageKey reason=already in flight" }
                    return@withNativeLane null
                }
                try {
                    val store = resolveActiveStore(manga, chapter, source)
                    val generation = store?.snapshot(pageKey)?.generation
                    if (store != null && generation != null) {
                        store.withGeneration(generation) {
                            translateSinglePageOnnx(manga, chapter, source, pageKey, streamFn, force, stageListener)
                        }
                    } else {
                        translateSinglePageOnnx(manga, chapter, source, pageKey, streamFn, force, stageListener)
                    }
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    logcat(LogPriority.ERROR, t) { "TachiyomiAT prepare ONNX phase failed: pageKey=$pageKey" }
                    markPageFailed(manga, chapter, source, pageKey, t)
                    throw t
                } finally {
                    inFlightPageKeys.remove(pageKey)
                }
            }

            // Failure before handoff: a null native result with no terminal store
            // state cannot be mistaken for a prepared page. Inspect the store to
            // distinguish a genuine terminal page (render-only resume, textless)
            // from a soft skip / decode failure.
            if (onnxResult == null) {
                return buildTerminalPreparedPage(manga, chapter, source, pageKey)
            }

            // Durability gate: persist the cleaned image BEFORE publishing the
            // prepared reference. If publication fails the page is not prepared.
            val published = persistOnnxCleanedImage(manga, chapter, source, pageKey, onnxResult)
            if (published == null) {
                logcat(LogPriority.WARN) {
                    "TachiyomiAT prepare handoff aborted: cleaned image not durable pageKey=$pageKey"
                }
                return null
            }

            // P0 correctness: the in-memory OCR result (blocks, geometry,
            // ocrStatus=READY) must be made durable BEFORE publishing the prepared
            // reference. Delegates to [publishPreparedPageFromOcr], the production
            // boundary helper that is also exercised by regression tests. The merge
            // carries the post-publish precondition so a stale worker loses to a
            // newer page owner.
            return publishPreparedPageFromOcr(
                store = published.store,
                pageKey = pageKey,
                ocrResult = published.pageTranslation,
                chapterId = chapter.id,
                mangaId = manga.id,
                sourceId = source.id,
                expectedPageVersion = published.commitPrecondition?.pageVersion
                    ?: published.store.snapshot(pageKey).pageVersion,
                expectedGeneration = published.commitPrecondition?.generation
                    ?: published.store.snapshot(pageKey).generation,
                expectedLeaseToken = published.commitPrecondition?.leaseToken,
            )
        } finally {
            releaseReaderPageLease(leaseStore, pageKey)
        }
    }

    /**
     * Inspects the durable store after a null native-phase result and returns a
     * terminal [PreparedPage] when the page is genuinely complete (render-only
     * resume that rendered, textless page, or fully-translated resume skip),
     * or null when the page is in a non-terminal soft-skip / failed state.
     */
    private suspend fun buildTerminalPreparedPage(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        pageKey: String,
    ): PreparedPage? {
        val store = resolveActiveStore(manga, chapter, source) ?: return null
        val page = store.state.value[pageKey] ?: return null
        val snapshot = store.snapshot(pageKey)
        if (!isPreparedPageTerminal(page)) return null
        return PreparedPage(
            pageKey = pageKey,
            chapterId = chapter.id,
            mangaId = manga.id,
            sourceId = source.id,
            cleanedImageName = page.cleanedImageName,
            generation = snapshot.generation,
            pageVersion = snapshot.pageVersion,
            blockFingerprints = snapshot.blockFingerprints,
            isTerminal = true,
        )
    }

    /**
     * Prepared-page boundary (ticket 02): loads the durable cleaned image
     * produced by [prepareSinglePage] and runs translate + render outside the
     * native permit so a caller's other native page may overlap this page's
     * remote translation.
     *
     * Returns true when translate/render work was performed or attempted
     * (including textless terminal no-ops). Returns false ONLY when the
     * prepared reference no longer matches the durable store (generation /
     * pageVersion / fingerprint mismatch, missing page entry, or missing
     * cleaned image) — a stale/race outcome the caller treats as "try again"
     * rather than a failure.
     *
     * A genuine translate/render failure or timeout is thrown (after durable
     * failure writes via [markPageFailed] / [markPageTimedOut]), matching the
     * legacy [runSinglePageBoundary] propagation so callers can attribute it
     * correctly instead of mistaking it for a race loss.
     *
     * Stage events fire TRANSLATING before the provider request and RENDERING
     * before compositing the translated result.
     */
    override suspend fun translatePreparedPage(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        prepared: PreparedPage,
        stageListener: TranslationStageListener?,
    ): Boolean {
        if (prepared.isTerminal) {
            logcat(LogPriority.INFO) {
                "TachiyomiAT translatePreparedPage: terminal skip pageKey=${prepared.pageKey} " +
                    "cleaned=${prepared.cleanedImageName}"
            }
            return true
        }
        val store = resolveActiveStore(manga, chapter, source) ?: return false
        // prepareSinglePage owns the reader lease only through the native
        // handoff. Re-admit the translate/render half here so a batch cannot
        // acquire the page in the handoff gap and then race the prepared
        // reference's writes.
        if (!acquireReaderPageLease(store, chapter, prepared.pageKey)) return false
        try {
            val snapshot = store.snapshot(prepared.pageKey)
            // Stale-reference rejection: generation, pageVersion, and the OCR block
            // fingerprints must all match. A mismatch means another caller won the
            // race or the page was re-OCR'd — the caller may retry, not fail.
            if (snapshot.generation != prepared.generation ||
                snapshot.pageVersion != prepared.pageVersion ||
                (prepared.blockFingerprints.isNotEmpty() && snapshot.blockFingerprints != prepared.blockFingerprints)
            ) {
                logcat(LogPriority.WARN) {
                    "TachiyomiAT translatePreparedPage: stale prepared reference pageKey=${prepared.pageKey} " +
                        "preparedGen=${prepared.generation} currentGen=${snapshot.generation} " +
                        "preparedVer=${prepared.pageVersion} currentVer=${snapshot.pageVersion} " +
                        "fingerprintMatch=${snapshot.blockFingerprints == prepared.blockFingerprints}"
                }
                return false
            }
            val pageState = store.state.value[prepared.pageKey] ?: return false
            val cleanedImageName = prepared.cleanedImageName ?: pageState.cleanedImageName
            if (cleanedImageName == null) {
                logcat(LogPriority.WARN) {
                    "TachiyomiAT translatePreparedPage: no cleaned image for pageKey=${prepared.pageKey}"
                }
                return false
            }

            val fromLang = TextRecognizerLanguage.fromPref(translationPreferences.translateFromLanguage())
            val toLang = TextTranslatorLanguage.fromPref(translationPreferences.translateToLanguage())
            val syntheticTranslation = Translation(source, manga, chapter, fromLang, toLang)
            syntheticTranslation.status = Translation.State.TRANSLATING
            // The translate lane resolves its store through resolveActiveStore
            // above and is a consumer of the prepared reference. It does not own or
            // mutate chapter-level translation state — the dead
            // currentChapterTranslation pointer was removed entirely (R3 cleanup).

            val pageTranslation = pageState.detachedCopy()
            pageTranslation.cleanedBitmap = loadPersistedCleanedBitmap(manga, chapter, source, cleanedImageName)
            if (pageTranslation.cleanedBitmap == null) {
                logcat(LogPriority.WARN) {
                    "TachiyomiAT translatePreparedPage: cleaned image unreadable pageKey=${prepared.pageKey} " +
                        "cleaned=$cleanedImageName"
                }
                pageTranslation.inpaintStatus = StageStatus.FAILED
                pageTranslation.renderStatus = StageStatus.FAILED
                pageTranslation.cleanedImageName = null
                pageTranslation.recordAttemptFailure()
                pageTranslation.errorMessage = "Cleaned image is missing or unreadable; retry inpainting"
                persistPageWithOomRecovery(
                    store,
                    prepared.pageKey,
                    pageTranslation,
                    expectedPrecondition = snapshot.toPrecondition(),
                )
                throw java.io.IOException(pageTranslation.errorMessage)
            }

            // The prepared-page boundary does not re-decode the source on the
            // translation side: the cleaned image is the durable artifact. An empty
            // streams list steers the render-retry path away from source re-decode.
            val streams = emptyList<Pair<String, () -> InputStream>>()
            val loadedBitmap = pageTranslation.cleanedBitmap!!
            val decoded = DecodedPage(
                bitmap = loadedBitmap,
                sampleSize = pageTranslation.decodeSampleSize.coerceAtLeast(1),
                originalWidth = (if (pageTranslation.originalImgWidth > 0f) pageTranslation.originalImgWidth.toInt() else loadedBitmap.width),
                originalHeight = (if (pageTranslation.originalImgHeight > 0f) pageTranslation.originalImgHeight.toInt() else loadedBitmap.height),
                decodeDecision = DecodeDecision(
                    kind = DecodeDecisionKind.FULL,
                    sampleSize = pageTranslation.decodeSampleSize.coerceAtLeast(1),
                    rawBitmapBytes = loadedBitmap.byteCount.toLong(),
                    sampledBitmapBytes = loadedBitmap.byteCount.toLong(),
                    sourcePixels = 0L,
                    sampledPixels = 0L,
                    snapshot = eu.kanade.translation.util.TranslationMemoryBudget.snapshot(),
                ),
                sourceBytesSize = 0L,
            )
            val ctx = OnnxPhaseResult(
                pageTranslation = pageTranslation,
                store = store,
                fromLang = fromLang,
                syntheticTranslation = syntheticTranslation,
                streams = streams,
                decoded = decoded,
                commitPrecondition = snapshot.toPrecondition(),
            )
            return try {
                val completed = withTimeoutOrNull(SINGLE_PAGE_TIMEOUT_MS) {
                    translateSinglePageHttpRender(manga, chapter, source, prepared.pageKey, ctx, stageListener)
                }
                if (completed == null) {
                    logcat(LogPriority.WARN) {
                        "TachiyomiAT translatePreparedPage timed out after ${SINGLE_PAGE_TIMEOUT_MS}ms: " +
                            "pageKey=${prepared.pageKey} chapter=${chapter.name}"
                    }
                    markPageTimedOut(manga, chapter, source, prepared.pageKey)
                    throw java.io.IOException("translatePreparedPage timed out for ${prepared.pageKey}")
                } else {
                    true
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: java.io.IOException) {
                // A genuine failure (unreadable cleaned image, timeout) — propagate
                // so the caller attributes it as a failure, not a race loss.
                throw e
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                logcat(LogPriority.ERROR, t) {
                    "TachiyomiAT translatePreparedPage failed: pageKey=${prepared.pageKey}"
                }
                markPageFailed(manga, chapter, source, prepared.pageKey, t)
                throw t
            }
        } finally {
            releaseReaderPageLease(store, prepared.pageKey)
        }
    }

    /**
     * TachiyomiAT: STAGED BATCH translation — the pre-translate path used by the
     * manga-screen "translate chapter" action (and anything that wants to
     * prepare a whole chapter before the reader opens). Replaces the old
     * page-1-first sequential loop.
     *
     * Stages (per the staged-batch design):
     *   1. DETECT + OCR batch  — [analyzePage] for each page in [orderedStreams],
     *      serialized by native quarantine (one page's bitmap/tensor set
     *      alive at a time). Persists ocrStatus=READY + blocks per page, so this
     *      stage is resumable (skips pages already analyzed).
     *   2. INPAINT ‖ TRANSLATE — for each page with blocks: inpaint (re-decoded
     *      bitmap, serialized under the permit) runs concurrently with
     *      textTranslator.translatePage (HTTP-only, no permit). The HTTP work
     *      overlaps the ONNX work — free parallelism, no extra peak memory.
     *   3. RENDER               — for each page with translated text + a cleaned
     *      image, recompute render colors and persist page state. The reader
     *      draws translated text live over the cleaned image.
     *
     * Memory model: one page bitmap is alive at a time (recycled after analyze,
     * re-decoded for inpaint, recycled after inpaint). Stage 2 persists each
     * cleaned image to disk (.cleaned.jpg) and releases the in-memory cleaned
     * bitmap immediately — stage 3 reloads one at a time — so the batch holds at
     * most one cleaned bitmap at any instant regardless of chapter length.
     * (Previously stage 2 kept every cleaned bitmap live across the whole chapter
     * until stage 3, which OOM'd on large chapters.) The reader is NOT open on
     * this path, so there is no concurrent display decode to race.
     *
     * [orderedStreams] is already in natural page order (1..N). Resume is a
     * per-stage decision; reader viewport and last-read position never rotate
     * the chapter batch.
     */
    suspend fun translateBatch(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        store: ChapterTranslationStore,
        orderedStreams: List<Pair<String, () -> InputStream>>,
        tracker: TranslationBatchProgressTracker? = null,
        naturalPageIndexes: Map<String, Int> = emptyMap(),
    ) {
        if (orderedStreams.isEmpty()) return
        val resolvedNaturalPageIndexes = if (naturalPageIndexes.isNotEmpty()) {
            naturalPageIndexes
        } else {
            orderedStreams.map { it.first }
                .distinct()
                .sortedWith { left, right -> left.compareToCaseInsensitiveNaturalOrder(right) }
                .mapIndexed { index, pageKey -> pageKey to index }
                .toMap()
        }
        val batchGeneration = store.beginGeneration("batch start chapter=${chapter.name}")
        val fromLang = TextRecognizerLanguage.fromPref(translationPreferences.translateFromLanguage())
        val toLang = TextTranslatorLanguage.fromPref(translationPreferences.translateToLanguage())
        store.withGeneration(batchGeneration) {
            try {
                withNativeLane(
                    timeoutMs = ONNX_PHASE_TIMEOUT_MS,
                    chapterId = chapter.id,
                    chapterName = chapter.name,
                    pageKey = "<engine-setup>",
                    onTimeout = {
                        store.invalidateGeneration("engine setup timeout chapter=${chapter.name}")
                    },
                ) {
                    engineRebuildMutex.withLock {
                        ensureEnginesBuiltFor(fromLang, toLang)
                    }
                } ?: run {
                    // Phase 3: no batch page lease outlives its run, whatever exit
                    // path the batch takes.
                    store.releaseAllPageLeases(PageWriteOrigin.BATCH)
                    return@withGeneration
                }

                val ensureCompanionDir: suspend () -> UniFile? = {
                    provider.getCompanionImageDir(manga.title, source, chapter.name, chapter.scanlator)
                }

                logcat(LogPriority.INFO) {
                    "TachiyomiAT batch START chapter=${chapter.name} pages=${orderedStreams.size} " +
                        "engine=${recognitionEngine::class.simpleName} translator=${textTranslator::class.simpleName}"
                }

                // Lane A (OCR+inpaint, permit-bound) feeds Lane B (translate, HTTP-bound)
                // through an UNLIMITED channel; render is a join (tryRender) that reuses the
                // in-memory cleaned bitmap when it fits the byte budget.
                val isAi = translationPreferences.translationEngineCategory().get() == TranslationEngineCategory.AI_MODEL &&
                    textTranslator is ContextualTextTranslator
                val contextualTranslator = textTranslator as? ContextualTextTranslator
                val requestedOutputTokens = translationPreferences.translationAiOutputTokens().get().toIntOrNull()
                    ?: TranslationContextChunkPlanner.MAX_CONTEXT_TOKENS
                val chunkProfile = if (contextualTranslator is LmStudioTranslator) {
                    TranslationContextChunkPlanner.Profile.LM_STUDIO
                } else {
                    TranslationContextChunkPlanner.Profile.DEFAULT
                }

                // Chapter-level glossary accumulator for cross-chunk name/pronoun continuity.
                // Seeded from already-translated pairs on batch resume so recurring terms
                // established before a restart still feed the glossary.
                val glossaryStats = ChapterGlossaryBuilder.Stats()
                if (isAi) {
                    store.translatedPairs().forEach { (s, t) -> glossaryStats.add(s, t) }
                }

                // Held-cleaned-bitmap registry: render reuses the in-memory bitmap instead of
                // reloading from disk. Bounded by BOTH a byte ceiling and a count cap; a page
                // exceeding either spills (its .cleaned.jpg is already durable, so the bitmap
                // recycles immediately and render reloads it).
                val heldBitmapBytes = AtomicLong(0L)
                val countSlots = Semaphore(HELD_BITMAP_MAX_COUNT)
                val bitmapRegistry = ConcurrentHashMap<String, Bitmap>()
                val translationRegistry = ConcurrentHashMap<String, PageTranslation>()
                data class BatchWriteIdentity(
                    val generation: Long,
                    var pageVersion: Long,
                    val leaseToken: Long,
                    var candidateGenerationId: String?,
                    var dependencyFingerprint: String?,
                    var artifactPageVersion: Long?,
                )
                val batchWriteIdentities = ConcurrentHashMap<String, BatchWriteIdentity>()
                val renderMutexes = ConcurrentHashMap<String, Mutex>()
                val aborted = AtomicBoolean(false)
                val expectedBatchFingerprints = batchExpectedFingerprints(fromLang, toLang)

                // Source identity is a direct input to detection and inpaint.
                // Hash the downloaded bytes before planning so replacing a
                // page under the same natural key cannot reuse old artifacts.
                // This is an I/O-only preflight; no detector/OCR/inpaint or
                // translator work is invoked for a matching completed page.
                val sourceFingerprints = buildMap {
                    orderedStreams.forEach { (pageKey, streamFn) ->
                        put(pageKey, computeSourceFingerprint(streamFn) ?: UNKNOWN_SOURCE_FINGERPRINT)
                    }
                }

                fun stampBatchProvenance(page: PageTranslation, description: String): PageTranslation = page.apply {
                    when {
                        description.contains("ocr", ignoreCase = true) ||
                            description.contains("decode", ignoreCase = true) -> {
                            detectionFingerprint = expectedBatchFingerprints.detection
                            ocrFingerprint = expectedBatchFingerprints.ocr
                        }
                        description.contains("inpaint", ignoreCase = true) -> {
                            inpaintFingerprint = expectedBatchFingerprints.inpaint
                        }
                        description.contains("translation", ignoreCase = true) -> {
                            translationFingerprint = expectedBatchFingerprints.translation
                            translationOrigin = PageWriteOrigin.BATCH.name
                            if (translationStatus == StageStatus.READY ||
                                translationStatus == StageStatus.PARTIAL ||
                                translationStatus == StageStatus.SKIPPED
                            ) {
                                batchContextComplete = true
                                batchContextCheckpointHash = StageFingerprints.pageSnapshot(this)
                            }
                        }
                        description.contains("render", ignoreCase = true) -> {
                            layoutFingerprint = expectedBatchFingerprints.layout
                        }
                    }
                }

                // Plan the complete chapter once, in the same natural order
                // passed to the coordinator.  Native resume gates consume this
                // snapshot; they never derive work from lastPageRead or the
                // reader viewport.
                val batchPagePlans = PageWorkPlanner.planChapter(
                    orderedStreams.map { (pageKey, _) ->
                        val persisted = store.state.value[pageKey]
                        BatchPlannerInput(
                            pageKey = pageKey,
                            page = persisted,
                            expectedFingerprints = expectedBatchFingerprints,
                            sourceFingerprint = sourceFingerprints[pageKey],
                            contextCheckpoint = if (persisted?.batchContextComplete == true) {
                                BatchContextCheckpoint(ContextCheckpointState.TRUSTED, persisted.batchContextCheckpointHash)
                            } else {
                                BatchContextCheckpoint(ContextCheckpointState.NOT_REQUIRED)
                            },
                        )
                    },
                ).pages.associateBy { it.pageKey }

                fun plannedTranslationDecision(pageKey: String) =
                    batchPagePlans[pageKey]?.stages?.firstOrNull { it.stage == BatchStage.TRANSLATION }?.decision

                fun plannedTranslationNeedsWork(pageKey: String): Boolean =
                    plannedTranslationDecision(pageKey) == eu.kanade.translation.model.StageDecision.RUN

                fun plannedRenderNeedsWork(pageKey: String): Boolean =
                    batchPagePlans[pageKey]?.let { plan ->
                        val translation = plan.stages.first { it.stage == BatchStage.TRANSLATION }
                        val ocr = plan.stages.first { it.stage == BatchStage.OCR }
                        if (translation.decision == eu.kanade.translation.model.StageDecision.FAILED ||
                            ocr.decision == eu.kanade.translation.model.StageDecision.FAILED ||
                            translation.decision == eu.kanade.translation.model.StageDecision.WAIT_FOR_DEPENDENCY &&
                            translation.reason == eu.kanade.translation.model.StageReasonCode.PRIOR_PAGE_INCOMPLETE
                        ) {
                            false
                        } else {
                            plan.stages.any { decision ->
                                decision.stage in setOf(BatchStage.TRANSLATION, BatchStage.INPAINT, BatchStage.LAYOUT) &&
                                    (
                                        decision.decision == eu.kanade.translation.model.StageDecision.RUN ||
                                            decision.decision == eu.kanade.translation.model.StageDecision.WAIT_FOR_DEPENDENCY &&
                                            decision.reason == eu.kanade.translation.model.StageReasonCode.DEPENDENCY_INCOMPLETE
                                        )
                            }
                        }
                    } == true

                suspend fun guardedBatchUpdate(
                    pageKey: String,
                    description: String,
                    update: (PageTranslation?) -> PageTranslation,
                ): ChapterTranslationStore.PatchResult {
                    val identity = batchWriteIdentities[pageKey]
                        ?: return ChapterTranslationStore.PatchResult.Rejected("batch page lease missing")
                    val result = store.updatePageGuarded(
                        pageKey = pageKey,
                        expected = ChapterTranslationStore.PatchPrecondition(
                            generation = identity.generation,
                            pageVersion = identity.pageVersion,
                            leaseToken = identity.leaseToken,
                            candidateGenerationId = identity.candidateGenerationId,
                            dependencyFingerprint = identity.dependencyFingerprint,
                            artifactPageVersion = identity.artifactPageVersion,
                        ),
                        description = description,
                        update = { current -> stampBatchProvenance(update(current), description) },
                    )
                    if (result is ChapterTranslationStore.PatchResult.Accepted) {
                        identity.pageVersion = result.snapshot.pageVersion
                        identity.candidateGenerationId = result.snapshot.candidateGenerationId
                        identity.dependencyFingerprint = result.snapshot.dependencyFingerprint
                        identity.artifactPageVersion = result.snapshot.artifactPageVersion
                    }
                    return result
                }

                suspend fun releaseBatchLease(pageKey: String) {
                    batchWriteIdentities.remove(pageKey)
                    releaseBatchPageLease(store, pageKey)
                }

                fun refreshBatchIdentity(pageKey: String, snapshot: ChapterTranslationStore.PageSnapshot) {
                    batchWriteIdentities[pageKey]?.let { identity ->
                        identity.pageVersion = snapshot.pageVersion
                        identity.candidateGenerationId = snapshot.candidateGenerationId
                        identity.dependencyFingerprint = snapshot.dependencyFingerprint
                        identity.artifactPageVersion = snapshot.artifactPageVersion
                    }
                }

                fun batchWritePrecondition(pageKey: String): ChapterTranslationStore.PatchPrecondition? =
                    batchWriteIdentities[pageKey]?.let { identity ->
                        ChapterTranslationStore.PatchPrecondition(
                            generation = identity.generation,
                            pageVersion = identity.pageVersion,
                            leaseToken = identity.leaseToken,
                            candidateGenerationId = identity.candidateGenerationId,
                            dependencyFingerprint = identity.dependencyFingerprint,
                            artifactPageVersion = identity.artifactPageVersion,
                        )
                    }

                suspend fun persistBatchPageWithOomRecovery(
                    pageKey: String,
                    pageTranslation: PageTranslation,
                ) {
                    val expected = batchWritePrecondition(pageKey) ?: return
                    persistPageWithOomRecovery(
                        store,
                        pageKey,
                        pageTranslation,
                        expectedPrecondition = expected,
                    )
                }

                suspend fun resumeGate(page: PageTranslation?): BatchResumeGate {
                    val planned = page?.sourceFileName?.let(batchPagePlans::get)
                    if (planned != null) {
                        val ocr = planned.stages.first { it.stage == BatchStage.OCR }
                        val inpaint = planned.stages.first { it.stage == BatchStage.INPAINT }
                        val ocrNeedsWork = ocr.decision == eu.kanade.translation.model.StageDecision.RUN ||
                            ocr.decision == eu.kanade.translation.model.StageDecision.WAIT_FOR_DEPENDENCY ||
                            ocr.decision == eu.kanade.translation.model.StageDecision.FAILED
                        if (inpaint.decision == eu.kanade.translation.model.StageDecision.REUSE &&
                            page.cleanedImageName != null
                        ) {
                            val physicallyPresent = withContext(Dispatchers.IO) {
                                provider.findPageCleanedImage(
                                    manga.title,
                                    source,
                                    chapter.name,
                                    chapter.scanlator,
                                    page.cleanedImageName!!,
                                )?.let { it.exists() && it.length() > 0L } == true
                            }
                            if (!physicallyPresent) {
                                logcat(LogPriority.WARN) {
                                    "TachiyomiAT planned resume invalidated metadata-only cleaned image: " +
                                        "pageKey=${page.sourceFileName} cleaned=${page.cleanedImageName}"
                                }
                                return if (!ocrNeedsWork && page.hasCurrentInpaintMask) {
                                    BatchResumeGate.INPAINT_ONLY
                                } else {
                                    BatchResumeGate.FULL
                                }
                            }
                        }
                        return when {
                            ocrNeedsWork ->
                                BatchResumeGate.FULL
                            inpaint.decision == eu.kanade.translation.model.StageDecision.RUN ->
                                BatchResumeGate.INPAINT_ONLY
                            else -> BatchResumeGate.SKIP_ALL
                        }
                    }
                    // Null inpaintingModeUsed = legacy page persisted before this field; treat
                    // as a match so existing chapters are not mass re-translated on first open.
                    val desiredMode = inpaintingModeFromPref().name
                    val inpaintModeMatches = page?.inpaintingModeUsed == null || page.inpaintingModeUsed == desiredMode
                    val decision = BatchResumeGateDecider.decide(
                        page,
                        cleanedFileValid = true,
                        inpaintModeMatches = inpaintModeMatches,
                    )
                    if (decision == BatchResumeGateDecider.Decision.SKIP_ALL && page?.cleanedImageName != null) {
                        val physicallyPresent = withContext(Dispatchers.IO) {
                            provider.findPageCleanedImage(
                                manga.title,
                                source,
                                chapter.name,
                                chapter.scanlator,
                                page.cleanedImageName!!,
                            )?.let { it.exists() && it.length() > 0L } == true
                        }
                        if (!physicallyPresent) {
                            logcat(LogPriority.WARN) {
                                "TachiyomiAT resume invalidated metadata-only cleaned image: pageKey=${page.sourceFileName} cleaned=${page.cleanedImageName}"
                            }
                            return if (page.hasCurrentInpaintMask) {
                                BatchResumeGate.INPAINT_ONLY
                            } else {
                                BatchResumeGate.FULL
                            }
                        }
                    }
                    if (!inpaintModeMatches) {
                        logcat(LogPriority.INFO) {
                            "TachiyomiAT resume re-inpainting for mode change: pageKey=${page?.sourceFileName} " +
                                "was=${page?.inpaintingModeUsed} now=$desiredMode"
                        }
                    }
                    return when (decision) {
                        BatchResumeGateDecider.Decision.SKIP_ALL -> BatchResumeGate.SKIP_ALL
                        BatchResumeGateDecider.Decision.INPAINT_ONLY -> BatchResumeGate.INPAINT_ONLY
                        BatchResumeGateDecider.Decision.FULL -> BatchResumeGate.FULL
                    }
                }

                fun holdCleaned(pageKey: String, cleaned: Bitmap?) {
                    if (cleaned == null) return
                    val acquired = countSlots.tryAcquire()
                    val fits = acquired && heldBitmapBytes.get() + cleaned.byteCount <= HELD_BITMAP_BYTE_CEILING
                    if (fits) {
                        heldBitmapBytes.addAndGet(cleaned.byteCount.toLong())
                        bitmapRegistry[pageKey] = cleaned
                    } else {
                        if (acquired) countSlots.release()
                        try {
                            cleaned.recycle()
                        } catch (_: Exception) {}
                    }
                }

                fun recycleHeld(pageKey: String) {
                    val b = bitmapRegistry.remove(pageKey) ?: return
                    heldBitmapBytes.addAndGet(-b.byteCount.toLong())
                    try {
                        b.recycle()
                    } catch (_: Exception) {}
                    countSlots.release()
                }

                suspend fun tryRender(pageKey: String) {
                    val mutex = renderMutexes.computeIfAbsent(pageKey) { Mutex() }
                    mutex.withLock {
                        val page = translationRegistry[pageKey] ?: return@withLock
                        if (page.ocrStatus == StageStatus.FAILED) {
                            recycleHeld(pageKey)
                            translationRegistry.remove(pageKey)
                            releaseBatchLease(pageKey)
                            return@withLock
                        }
                        if (page.renderStatus == StageStatus.READY && !plannedRenderNeedsWork(pageKey)) {
                            recycleHeld(pageKey)
                            translationRegistry.remove(pageKey)
                            releaseBatchLease(pageKey)
                            tracker?.markRenderDone(pageKey)
                            return@withLock
                        }
                        val status = page.translationStatus
                        if (status != StageStatus.READY && status != StageStatus.PARTIAL) {
                            if (status == StageStatus.FAILED) {
                                recycleHeld(pageKey)
                                translationRegistry.remove(pageKey)
                                releaseBatchLease(pageKey)
                            }
                            return@withLock
                        }
                        val inpaintStatus = page.inpaintStatus
                        if (inpaintStatus != StageStatus.READY && inpaintStatus != StageStatus.PARTIAL && inpaintStatus != StageStatus.TEXTLESS) {
                            if (inpaintStatus == StageStatus.FAILED) {
                                recycleHeld(pageKey)
                                translationRegistry.remove(pageKey)
                                releaseBatchLease(pageKey)
                            }
                            // Inpaint isn't ready yet, wait for inpaintWorker to call tryRender
                            return@withLock
                        }
                        // READY/PARTIAL -> render. Consume the held bitmap on the happy path
                        // (no disk reload); spill/SKIP_ALL pages reload .cleaned.jpg instead.
                        val held = bitmapRegistry.remove(pageKey)
                        if (held != null) {
                            heldBitmapBytes.addAndGet(-held.byteCount.toLong())
                            countSlots.release()
                        }
                        val bitmap = held ?: page.cleanedImageName?.let { loadPersistedCleanedBitmap(manga, chapter, source, it) }
                        if (bitmap == null && inpaintStatus != StageStatus.TEXTLESS) {
                            page.inpaintStatus = StageStatus.FAILED
                            page.renderStatus = StageStatus.FAILED
                            page.recordAttemptFailure()
                            page.errorMessage = "Cleaned image is missing or unreadable; retry inpainting"
                            tracker?.markInpaintFailed(pageKey, page.errorMessage!!)
                            tracker?.markRenderFailed(pageKey, page.errorMessage!!)
                            persistBatchPageWithOomRecovery(pageKey, page)
                            translationRegistry.remove(pageKey)
                            releaseBatchLease(pageKey)
                            return@withLock
                        }
                        var renderPersisted = false
                        try {
                            tracker?.markRenderRunning(pageKey)
                            val running = guardedBatchUpdate(pageKey, "batch render running") {
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
                                return@withLock
                            }
                            val renderInput = store.snapshot(pageKey)
                            RenderColorEstimator.recomputeFor(bitmap, page.blocks)
                            page.renderStatus = StageStatus.READY
                            page.updatedAt = System.currentTimeMillis()
                            val patch = RenderStagePatch(
                                pageKey = pageKey,
                                generation = renderInput.generation,
                                expectedPageVersion = renderInput.pageVersion,
                                expectedLeaseToken = renderInput.leaseToken,
                                expectedCleanedImageName = renderInput.page?.cleanedImageName ?: "",
                                expectedInpaintRevision = renderInput.page?.inpaintRevision ?: 0,
                                expectedOcrBlockFingerprints = renderInput.blockFingerprints,
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
                            renderPersisted = store.mergeRender(patch) is StagePatchResult.Accepted
                            if (renderPersisted) {
                                // A newer committed display bundle may have just
                                // promoted; the file it superseded is now deletable.
                                deleteRetiredCleanedFile(manga, chapter, source, pageKey, store)
                            }
                            tracker?.markRenderDone(pageKey)
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
                                persistBatchPageWithOomRecovery(pageKey, page)
                            }
                            translationRegistry.remove(pageKey)
                            releaseBatchLease(pageKey)
                        }
                    }
                }

                val chunkCounter = AtomicLong(0L)
                var dynamicGlossary = ""
                var dynamicGlossaryExtracted = false

                suspend fun translateChunkAi(
                    chunk: TranslationContextChunk,
                    completedPages: Set<String>,
                    rolling: String,
                    pastTranslations: String = "",
                    analyticalMode: Boolean = false,
                ): Pair<String, String> {
                    coroutineContext.ensureActive()
                    val ct = contextualTranslator ?: return rolling to pastTranslations

                    if (!dynamicGlossaryExtracted) {
                        dynamicGlossaryExtracted = true
                        dynamicGlossary = eu.kanade.translation.translator.GlossaryExtractor.extractGlossary(ct, chunk.pages.values.toList())
                    }

                    val glossaryText = dynamicGlossary + "\n" + ChapterGlossaryBuilder.formatGlossary(glossaryStats.build())
                    // Build future OCR context from pages already OCR'd but not yet
                    // translated (in the registry, no translation yet). Empty when
                    // Analytical Mode is off or no upcoming pages exist.
                    val futureContext = if (analyticalMode) {
                        val upcoming = translationRegistry.values
                            .filter { pg ->
                                pg.blocks.any { it.translation.isBlank() && it.text.isNotBlank() }
                            }
                        TranslationContextChunkPlanner.buildFutureContext(upcoming)
                    } else {
                        ""
                    }
                    val withRolling = TranslationContextChunkPlanner.withRollingContext(
                        chunk = chunk,
                        rollingContext = rolling,
                        requestedOutputTokens = requestedOutputTokens,
                        profile = chunkProfile,
                        glossary = glossaryText,
                    )
                    val contextualChunk = if (analyticalMode) {
                        TranslationContextChunkPlanner.withSlidingContext(
                            chunk = withRolling,
                            pastTranslations = pastTranslations,
                            futureContext = futureContext,
                            requestedOutputTokens = requestedOutputTokens,
                            profile = chunkProfile,
                        )
                    } else {
                        withRolling
                    }
                    chunk.pages.keys.forEach { pk ->
                        val p = translationRegistry[pk] ?: return@forEach
                        guardedBatchUpdate(pk, "batch translation running") {
                            (it ?: p).apply {
                                translationStatus = StageStatus.RUNNING
                                errorMessage = null
                                updatedAt = System.currentTimeMillis()
                            }
                        }
                        tracker?.markTranslateRunning(pk)
                    }
                    try {
                        translateAiChunkWithAdaptiveRetry(
                            translator = ct,
                            chunk = contextualChunk,
                            requestedOutputTokens = requestedOutputTokens,
                            profile = chunkProfile,
                            allowFailureSplit = ct is LmStudioTranslator,
                            label = "stream-${chunkCounter.incrementAndGet()}",
                            retryDepth = 0,
                        )
                        var newRolling = TranslationContextChunkPlanner.updateRollingContext(
                            rolling,
                            contextualChunk.pages,
                        )
                        // Accumulate target-side past translations for the Analytical-Mode sliding
                        // window. Left as-is when off so the non-analytical path is unchanged.
                        var newPast = if (analyticalMode) {
                            val combined = if (pastTranslations.isBlank()) {
                                TranslationContextChunkPlanner.buildPastTranslations(contextualChunk.pages)
                            } else {
                                pastTranslations + "\n" +
                                    TranslationContextChunkPlanner.buildPastTranslations(contextualChunk.pages)
                            }
                            // Bound to the last N lines (MAX_PAST_TRANSLATION_PAIRS) by
                            // taking the tail after splitting on newlines.
                            val lines = combined.lineSequence().filter { it.isNotBlank() }.toList()
                            lines.takeLast(TranslationContextChunkPlanner.MAX_PAST_TRANSLATION_PAIRS)
                                .joinToString("\n")
                        } else {
                            pastTranslations
                        }
                        val estimatedRollingTokens = TranslationContextChunkPlanner.estimateTokens(newRolling)
                        val maxTokens = TranslationContextChunkPlanner.constraintsFor(chunkProfile).maxRollingContextTokens
                        if (estimatedRollingTokens > maxTokens) {
                            val summaryPrompt = "Summarize the following manga dialogue context into a dense 2-3 sentence paragraph focusing on current plot and speakers:\n\n$newRolling"
                            val summary = ct.promptText(summaryPrompt)
                            if (summary.isNotBlank()) {
                                logcat(LogPriority.INFO) { "Summarized rolling context ($estimatedRollingTokens tokens -> ${TranslationContextChunkPlanner.estimateTokens(summary)} tokens)" }
                                newRolling = "[SUMMARY] $summary"
                            }
                        }
                        // Accumulate this chunk's translated pairs into the chapter glossary and
                        // persist it. Keep the metric tied to the current pipe-delimited protocol;
                        // legacy speech-role tags are no longer part of the prompt contract.
                        var translatedPairs = 0
                        contextualChunk.pages.values.forEach { page ->
                            page.blocks.forEach { b ->
                                glossaryStats.add(b.text, b.translation)
                                if (b.translation.isNotBlank()) translatedPairs++
                            }
                        }
                        val newGlossary = glossaryStats.build()
                        store.updateGlossary(newGlossary)
                        logcat(LogPriority.INFO) {
                            "TachiyomiAT batch stage2-AI chunk: translatedPairs=$translatedPairs " +
                                "glossaryEntries=${newGlossary.size}"
                        }
                        completedPages.forEach { pk ->
                            val p = translationRegistry[pk] ?: return@forEach
                            val status = TranslationBlockValidation.applyTo(p)
                            when (status) {
                                StageStatus.READY -> tracker?.markTranslateDone(pk)
                                StageStatus.PARTIAL -> tracker?.markTranslatePartial(pk)
                                StageStatus.FAILED -> tracker?.markTranslateFailed(pk, p.errorMessage ?: "Validation failed")
                            }
                            guardedBatchUpdate(pk, "batch translation chunk commit") {
                                (it ?: p).apply {
                                    translationStatus = p.translationStatus
                                    if (status == StageStatus.FAILED) {
                                        translationError = p.translationError
                                        retryCount = p.retryCount
                                        attemptCount = p.attemptCount
                                    }
                                    if (it != null && it !== p) {
                                        blocks = p.blocks.toMutableList()
                                    }
                                    updatedAt = System.currentTimeMillis()
                                }
                            }
                            tryRender(pk)
                        }
                        return newRolling to newPast
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        val reason = e.message ?: e.javaClass.simpleName
                        chunk.pages.keys.forEach { pk ->
                            val p = translationRegistry[pk] ?: return@forEach
                            markBatchTranslationFailed(
                                store,
                                pk,
                                p,
                                "AI chunk failed: $reason",
                                expectedPrecondition = batchWritePrecondition(pk) ?: return@forEach,
                            )
                            tracker?.markTranslateFailed(pk, "AI chunk failed: $reason")
                        }
                        if (e is ContextualStructuralFailureException) {
                            logcat(LogPriority.WARN) {
                                "TachiyomiAT contextual batch rejected: pages=${chunk.pages.size} " +
                                    e.failure.safeSummary()
                            }
                        } else {
                            logcat(LogPriority.ERROR, e) {
                                "TachiyomiAT contextual batch translate failed: chunk pages=${chunk.pages.keys}"
                            }
                        }
                        return rolling to pastTranslations
                    }
                }

                suspend fun completeChunklessPage(pk: String) {
                    val p = translationRegistry[pk] ?: return
                    TranslationBlockValidation.applyTo(p)
                    p.translationStatus = StageStatus.READY
                    guardedBatchUpdate(pk, "batch chunkless translation commit") {
                        (it ?: p).apply {
                            translationStatus = StageStatus.READY
                            translationError = null
                            if (it != null && it !== p) {
                                blocks = p.blocks.toMutableList()
                            }
                            updatedAt = System.currentTimeMillis()
                        }
                    }
                    tracker?.markTranslateDone(pk)
                    tryRender(pk)
                }

                // ---- TachiyomiAT Checkpoint 2 integration: coordinated Pass-1 ----
                // The batch schedule is now driven by [BatchCoordinator] (bounded channel
                // cap 2, serialized native lane, REMOTE_IO translation overlapping same-page
                // inpaint, LOCAL_COMPUTE translation serialized with native, per-page render
                // join, Pass-1 barrier). The pipeline supplies real adapter implementations of
                // [NativeLaneWorker] / [TranslatorLaneWorker] / [RenderJoinWorker] that reuse
                // the existing OCR/inpaint/persist/translate/render helpers above, so the heavy
                // Android/ONNX/HTTP logic is unchanged — only the schedule is centralized.
                //
                // TranslatorComputeClass drives lane routing: ML Kit (LOCAL_COMPUTE) is kept
                // inline on the native lane so its on-device inference never overlaps native
                // OCR/inpaint; remote providers (REMOTE_IO) overlap their network wait with
                // same-page inpaint because the work item is offered to the bounded channel
                // AFTER OCR persistence and BEFORE inpaint completes.
                val computeClass = TranslatorComputeClass.forTranslator(textTranslator)

                val streamsByKey = LinkedHashMap(orderedStreams.toMap())
                val nativeWorker = object : NativeLaneWorker {
                    override suspend fun runOcrStage(pageKey: String, pageIndex: Int): OcrReadyPageRef? {
                        coroutineContext.ensureActive()
                        if (aborted.get()) return null
                        val streamFn = streamsByKey[pageKey] ?: return null
                        // Phase 3: the batch owns this page until it reaches a
                        // terminal render/failure boundary. A reader-owned page is
                        // skipped this pass and rescanned later — never a
                        // competing writer.
                        val batchLease = when (val acquisition = store.tryAcquirePageStageLease(pageKey, PageStage.Ocr, PageWriteOrigin.BATCH)) {
                            is LeaseAcquisition.Denied -> {
                                logcat(LogPriority.INFO) {
                                    "TachiyomiAT batch defers ${acquisition.owner}-owned page: " +
                                        "chapter=${chapter.name} pageKey=$pageKey reason=${acquisition.reason}"
                                }
                                return null
                            }
                            is LeaseAcquisition.Granted -> acquisition.lease
                        }
                        batchWriteIdentities[pageKey] = BatchWriteIdentity(
                            generation = batchLease.generation,
                            pageVersion = batchLease.pageVersion,
                            leaseToken = batchLease.token,
                            candidateGenerationId = batchLease.candidateGenerationId,
                            dependencyFingerprint = batchLease.dependencyFingerprint,
                            artifactPageVersion = batchLease.artifactPageVersion,
                        )
                        val existing = store.state.value[pageKey]
                        val gate = resumeGate(existing)
                        if (gate == BatchResumeGate.SKIP_ALL) {
                            // Fully durable (OCR+inpaint done): no decode/slot; render reloads disk.
                            val p = existing!!
                            translationRegistry[pageKey] = p
                            val translationNeedsWork = plannedTranslationNeedsWork(pageKey)
                            if (!translationNeedsWork) tryRender(pageKey)
                            val translationTerminal = p.translationStatus == StageStatus.READY ||
                                p.translationStatus == StageStatus.PARTIAL ||
                                p.translationStatus == StageStatus.SKIPPED
                            if (translationTerminal && !translationNeedsWork) return null
                            val persisted = store.snapshot(pageKey)
                            refreshBatchIdentity(pageKey, persisted)
                            return OcrReadyPageRef(
                                pageKey = pageKey,
                                pageIndex = pageIndex,
                                generation = batchGeneration,
                                blockFingerprints = persisted.blockFingerprints,
                                leaseToken = batchWriteIdentities[pageKey]?.leaseToken,
                                candidateGenerationId = persisted.candidateGenerationId,
                                dependencyFingerprint = persisted.dependencyFingerprint,
                                artifactPageVersion = persisted.artifactPageVersion,
                            )
                        }
                        var producedTarget: PageTranslation? = null
                        var producedDecoded: DecodedPage? = null
                        withNativeLane(
                            timeoutMs = SINGLE_PAGE_TIMEOUT_MS,
                            chapterId = chapter.id,
                            chapterName = chapter.name,
                            pageKey = pageKey,
                            onTimeout = { markPageTimedOut(manga, chapter, source, pageKey) },
                        ) {
                            val latest = store.state.value[pageKey]
                            val innerGate = resumeGate(latest)
                            if (innerGate == BatchResumeGate.SKIP_ALL) {
                                val p = latest!!
                                translationRegistry[pageKey] = p
                                if (!plannedTranslationNeedsWork(pageKey)) tryRender(pageKey)
                                producedTarget = p
                                return@withNativeLane
                            }
                            try {
                                val decoded = try {
                                    decodePageBitmapForTranslation(pageKey, streamFn)
                                } catch (deferred: LowMemoryDecodeDeferredException) {
                                    tracker?.markOcrFailed(pageKey, deferred.message ?: "Decode deferred")
                                    guardedBatchUpdate(pageKey, "batch decode deferred") {
                                        (it ?: PageTranslation()).apply {
                                            sourceFileName = pageKey
                                            ocrStatus = StageStatus.FAILED
                                            errorMessage = deferred.message
                                            retryCount = (it?.retryCount ?: 0) + 1
                                            attemptCount = (it?.attemptCount ?: 0) + 1
                                            updatedAt = System.currentTimeMillis()
                                        }
                                    }
                                    return@withNativeLane
                                } ?: run {
                                    tracker?.markOcrFailed(pageKey, "Failed to decode page: null bitmap")
                                    guardedBatchUpdate(pageKey, "batch decode failed") {
                                        (it ?: PageTranslation()).apply {
                                            sourceFileName = pageKey
                                            ocrStatus = StageStatus.FAILED
                                            errorMessage = "Failed to decode page: null bitmap"
                                            retryCount = (it?.retryCount ?: 0) + 1
                                            attemptCount = (it?.attemptCount ?: 0) + 1
                                            updatedAt = System.currentTimeMillis()
                                        }
                                    }
                                    return@withNativeLane
                                }
                                producedDecoded = decoded
                                // Decode succeeds: the bitmap is owned by this handle until
                                // [releaseNativeResources]. OCR persistence runs here (analyzePage
                                // persists blocks + ocrStatus BEFORE inpaint), closing the OCR crash
                                // window first and producing the immutable work item offered to the
                                // translation lane below.
                                if (innerGate == BatchResumeGate.INPAINT_ONLY) {
                                    translationRegistry[pageKey] = latest ?: PageTranslation(sourceFileName = pageKey)
                                } else {
                                    tracker?.markOcrRunning(pageKey)
                                    val analyzed = analyzePage(
                                        pageKey,
                                        decoded.bitmap,
                                        decoded,
                                        store,
                                        expectedBatchFingerprints,
                                    )
                                    tracker?.markOcrDone(pageKey)
                                    translationRegistry[pageKey] = analyzed
                                }
                                producedTarget = translationRegistry[pageKey]
                            } catch (deferred: LowMemoryRecognitionDeferredException) {
                                val t = translationRegistry[pageKey]
                                if (t != null) {
                                    t.inpaintStatus = StageStatus.FAILED
                                    t.errorMessage = deferred.message
                                }
                                tracker?.markInpaintFailed(pageKey, deferred.message ?: "Recognition deferred")
                            }
                        }
                        val target = producedTarget ?: return null

                        // Recycle bitmap immediately for OCR-first barrier (Checkpoint 3)
                        if (producedDecoded != null) {
                            try {
                                producedDecoded!!.bitmap.recycle()
                            } catch (_: Exception) {}
                        }
                        BitmapPool.releaseAll()
                        try {
                            recognitionEngine.reclaimPooledMemory()
                        } catch (_: Exception) {}

                        val persisted = store.snapshot(pageKey)
                        refreshBatchIdentity(pageKey, persisted)
                        return OcrReadyPageRef(
                            pageKey = pageKey,
                            pageIndex = pageIndex,
                            generation = batchGeneration,
                            blockFingerprints = persisted.blockFingerprints,
                            leaseToken = batchWriteIdentities[pageKey]?.leaseToken,
                            candidateGenerationId = persisted.candidateGenerationId,
                            dependencyFingerprint = persisted.dependencyFingerprint,
                            artifactPageVersion = persisted.artifactPageVersion,
                        )
                    }

                    override suspend fun runInpaintStage(pageKey: String) {
                        val streamFn = streamsByKey[pageKey] ?: return
                        val latest = store.state.value[pageKey] ?: return
                        val target = translationRegistry[pageKey] ?: latest
                        val plannedInpaint = batchPagePlans[pageKey]?.stages?.firstOrNull {
                            it.stage == BatchStage.INPAINT
                        }
                        val plannedCleanedPresent = if (
                            plannedInpaint?.decision == eu.kanade.translation.model.StageDecision.REUSE &&
                            latest.cleanedImageName != null
                        ) {
                            withContext(Dispatchers.IO) {
                                provider.findPageCleanedImage(
                                    manga.title,
                                    source,
                                    chapter.name,
                                    chapter.scanlator,
                                    latest.cleanedImageName!!,
                                )?.let { it.exists() && it.length() > 0L } == true
                            }
                        } else {
                            false
                        }
                        if (plannedInpaint?.decision == eu.kanade.translation.model.StageDecision.TERMINAL_COMPLETE ||
                            plannedInpaint?.decision == eu.kanade.translation.model.StageDecision.REUSE &&
                            plannedCleanedPresent
                        ) {
                            target.cleanedImageName = latest.cleanedImageName
                            target.inpaintingModeUsed = latest.inpaintingModeUsed
                            target.inpaintStatus = latest.inpaintStatus
                            target.inpaintRevision = latest.inpaintRevision
                            target.inpaintFingerprint = latest.inpaintFingerprint
                            target.inpaintMaskBoxes = latest.inpaintMaskBoxes
                            target.cleanedBitmap = null
                            return
                        }
                        // SKIP_ALL-resume durable cleaned image shortcut: keep existing result.
                        val hasDurableCleaned = latest.cleanedImageName != null &&
                            latest.inpaintStatus == StageStatus.READY &&
                            latest.hasCurrentInpaintResult &&
                            resumeGate(latest) == BatchResumeGate.SKIP_ALL
                        if (hasDurableCleaned) {
                            target.cleanedImageName = latest.cleanedImageName
                            target.inpaintingModeUsed = latest.inpaintingModeUsed
                            target.inpaintStatus = StageStatus.READY
                            target.cleanedBitmap = null
                            return
                        }
                        withNativeLane(
                            timeoutMs = SINGLE_PAGE_TIMEOUT_MS,
                            chapterId = chapter.id,
                            chapterName = chapter.name,
                            pageKey = pageKey,
                            onTimeout = { markPageTimedOut(manga, chapter, source, pageKey) },
                        ) {
                            var decoded: DecodedPage? = null
                            try {
                                tracker?.markInpaintRunning(pageKey)
                                decoded = decodePageBitmapForTranslation(pageKey, streamFn)
                                if (decoded == null) {
                                    tracker?.markInpaintFailed(pageKey, "Null bitmap on re-decode")
                                    return@withNativeLane
                                }
                                preflightInpaintGate(decoded.bitmap, pageKey)
                                inpaintPage(
                                    fileName = pageKey,
                                    bitmap = decoded.bitmap,
                                    pageTranslation = target,
                                    batchFingerprint = expectedBatchFingerprints.inpaint,
                                    guardedWrite = { description, update ->
                                        guardedBatchUpdate(pageKey, description, update)
                                    },
                                )
                                if (target.inpaintStatus == StageStatus.FAILED &&
                                    target.cleanedBitmap == null &&
                                    target.blocks.isNotEmpty()
                                ) {
                                    // One bounded recovery pass reclaims memory and re-runs
                                    // recognition/inpaint at a larger sample size. It never
                                    // changes OCR engines or models.
                                    retryInpaintDownscaled(
                                        manga,
                                        chapter,
                                        source,
                                        pageKey,
                                        orderedStreams,
                                        decoded,
                                        target,
                                    )
                                }
                                if (target.inpaintStatus == StageStatus.READY) {
                                    tracker?.markInpaintDone(pageKey)
                                } else {
                                    tracker?.markInpaintFailed(pageKey, target.errorMessage ?: "Inpaint failed")
                                }
                            } catch (deferred: LowMemoryRecognitionDeferredException) {
                                target.inpaintStatus = StageStatus.FAILED
                                target.errorMessage = deferred.message
                                tracker?.markInpaintFailed(pageKey, deferred.message ?: "Recognition deferred")
                            } finally {
                                if (decoded != null) {
                                    try {
                                        decoded.bitmap.recycle()
                                    } catch (_: Exception) {}
                                }
                                BitmapPool.releaseAll()
                                try {
                                    recognitionEngine.reclaimPooledMemory()
                                } catch (_: Exception) {}
                            }
                        }
                        // Persist the cleaned bitmap off the native permit (matches the prior
                        // producer's post-inpaint publication step) and hand it to render.
                        val cleaned = target.cleanedBitmap
                        if (cleaned != null) {
                            val companionDir = ensureCompanionDir()
                            val published = persistCleanedBitmap(
                                target,
                                cleaned,
                                companionDir,
                                pageKey,
                                chapter.name,
                                store,
                                source.id,
                                manga.id,
                                chapter.id,
                                expectedPrecondition = batchWritePrecondition(pageKey),
                            )
                            published?.let { refreshBatchIdentity(pageKey, it) }
                            if (published == null) {
                                // Never render an in-memory cleaned bitmap whose durable
                                // publication failed. The reader must remain on the original
                                // and receive a retryable failure instead of a transient overlay.
                                target.cleanedBitmap = null
                            }
                        } else {
                            guardedBatchUpdate(pageKey, "batch inpaint terminal state") {
                                (it ?: target).apply {
                                    inpaintStatus = target.inpaintStatus
                                    errorMessage = target.errorMessage
                                    updatedAt = System.currentTimeMillis()
                                }
                            }
                        }
                        holdCleaned(pageKey, target.cleanedBitmap)
                        target.cleanedBitmap = null
                    }
                }

                // The translator lane owns the streaming AI chunk planner (contextual path) or
                // performs per-page translation (standard path). Either way it is a SINGLE
                // serialized lane so only one provider request is in flight at a time. For the
                // AI path, pages complete on chunk flush and render through tryRender; for the
                // standard path, each page translates, validates, and renders per item.
                val translatorWorker = object : TranslatorLaneWorker {
                    // AI contextual streaming state, owned by this lane.
                    val planner: StreamingChunkPlanner? =
                        if (isAi) {
                            StreamingChunkPlanner(
                                requestedOutputTokens = requestedOutputTokens,
                                profile = chunkProfile,
                                naturalPageIndexes = resolvedNaturalPageIndexes,
                            )
                        } else {
                            null
                        }
                    var rollingContext = ""
                    val analyticalMode = runCatching {
                        Injekt.get<tachiyomi.domain.translation.TranslationPreferences>()
                            .translationAnalyticalMode().get()
                    }.getOrDefault(false)
                    var pastTranslations = ""

                    // InactivityFlusher (Checkpoint 2 §3): a trailing partial AI chunk flushes
                    // after 250ms with no new page accepted, so a slow producer never strands the
                    // last pages. The flush holds the translate mutex so it never races a
                    // size-driven flush (one provider request at a time). Preserves the planner's
                    // token/page limits because it delegates to [StreamingChunkPlanner.flushRemaining].
                    val inactivityFlusher: InactivityFlusher? = if (isAi) {
                        InactivityFlusher(
                            onFlush = { flushResult ->
                                if (flushResult.finalChunk != null) {
                                    val (newRolling, newPast) = translateChunkAi(
                                        flushResult.finalChunk,
                                        flushResult.completedPages,
                                        rollingContext,
                                        pastTranslations,
                                        analyticalMode,
                                    )
                                    rollingContext = newRolling
                                    pastTranslations = newPast
                                } else {
                                    flushResult.completedPages.forEach { pk -> completeChunklessPage(pk) }
                                }
                                flushResult.rejectedPages.forEach { (pk, reason) ->
                                    val rp = translationRegistry[pk]
                                    if (rp != null) {
                                        markBatchTranslationFailed(
                                            store,
                                            pk,
                                            rp,
                                            reason,
                                            expectedPrecondition = batchWritePrecondition(pk) ?: return@forEach,
                                        )
                                        tracker?.markTranslateFailed(pk, reason)
                                    }
                                }
                            },
                        )
                    } else {
                        null
                    }

                    override suspend fun translate(ref: OcrReadyPageRef) {
                        val pageKey = ref.pageKey
                        val identity = batchWriteIdentities[pageKey]
                        if (identity == null ||
                            (ref.leaseToken != null && identity.leaseToken != ref.leaseToken) ||
                            (ref.candidateGenerationId != null && identity.candidateGenerationId != ref.candidateGenerationId) ||
                            (ref.dependencyFingerprint != null && identity.dependencyFingerprint != ref.dependencyFingerprint)
                        ) {
                            logcat(LogPriority.WARN) {
                                "TachiyomiAT batch translation reference rejected (stale lease): pageKey=$pageKey"
                            }
                            return
                        }
                        val p = translationRegistry[pageKey] ?: store.state.value[pageKey]?.detachedCopy()?.also {
                            translationRegistry[pageKey] = it
                        } ?: return
                        val plannedTranslation = batchPagePlans[pageKey]?.stages?.firstOrNull {
                            it.stage == BatchStage.TRANSLATION
                        }
                        val dependencyReadyAfterNative = p.ocrStatus == StageStatus.READY ||
                            p.ocrStatus == StageStatus.TEXTLESS
                        val shouldSkipTranslation = plannedTranslation?.decision ==
                            eu.kanade.translation.model.StageDecision.REUSE ||
                            plannedTranslation?.decision == eu.kanade.translation.model.StageDecision.TERMINAL_COMPLETE ||
                            plannedTranslation?.decision == eu.kanade.translation.model.StageDecision.FAILED ||
                            plannedTranslation?.decision == eu.kanade.translation.model.StageDecision.WAIT_FOR_DEPENDENCY &&
                            (
                                plannedTranslation.reason == eu.kanade.translation.model.StageReasonCode.PRIOR_PAGE_INCOMPLETE ||
                                    !dependencyReadyAfterNative
                                )
                        if (shouldSkipTranslation) {
                            // Native/layout-only resume, an ordered context wait,
                            // or a failed page never invokes the provider. The
                            // render join still receives its branch completion.
                            return
                        }
                        // Persisted OCR order is the stable identity source. Assign IDs before the
                        // user-selected reading-order sort so RTL/LTR changes never rename a block.
                        StableBlockIds.assign(p, ref.pageIndex)
                        val readingOrder = translationPreferences.translationReadingOrder().get()
                        p.blocks = eu.kanade.translation.util.TranslationBlockSorter.sort(p.blocks, fromLang, readingOrder)
                        translationRegistry[pageKey] = p
                        val sourceBlocks = p.blocks.count { it.text.isNotBlank() }
                        if (sourceBlocks == 0) {
                            p.translationStatus = StageStatus.SKIPPED
                            p.renderStatus = StageStatus.SKIPPED
                            guardedBatchUpdate(pageKey, "batch textless translation commit") { p }
                            tracker?.markTranslateSkipped(pageKey)
                            tracker?.markRenderSkipped(pageKey)
                            recycleHeld(pageKey)
                            translationRegistry.remove(pageKey)
                            releaseBatchLease(pageKey)
                            return
                        }
                        if (isAi && planner != null) {
                            // Contextual path: accept this page into the streaming planner and
                            // process every emission (chunk flush / chunkless completion). A page
                            // may complete on a chunk that flushes when a LATER page is accepted,
                            // so completed pages are rendered here via tryRender as chunks flush.
                            eu.kanade.translation.SharedProviderRequestAdmission.withRequest {
                                inactivityFlusher?.recordActivity()
                                val emission = planner.accept(pageKey, p)
                                if (emission != null && emission.chunk != null) {
                                    val (newRolling, newPast) = translateChunkAi(
                                        emission.chunk!!,
                                        emission.completedPages,
                                        rollingContext,
                                        pastTranslations,
                                        analyticalMode,
                                    )
                                    rollingContext = newRolling
                                    pastTranslations = newPast
                                } else if (emission != null) {
                                    emission.completedPages.forEach { pk -> completeChunklessPage(pk) }
                                }
                            }
                        } else {
                            // Standard (per-page) path: translate, validate, persist, render.
                            try {
                                eu.kanade.translation.SharedProviderRequestAdmission.withRequest {
                                    tracker?.markTranslateRunning(pageKey)
                                    textTranslator.translatePage(pageKey, p)
                                }
                                TranslationBlockValidation.applyTo(p)
                                val s = p.translationStatus
                                when (s) {
                                    StageStatus.READY -> tracker?.markTranslateDone(pageKey)
                                    StageStatus.PARTIAL -> tracker?.markTranslatePartial(pageKey)
                                    else -> tracker?.markTranslateFailed(pageKey, p.errorMessage ?: "Translate unknown state")
                                }
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                p.translationStatus = StageStatus.FAILED
                                p.errorMessage = e.message
                                tracker?.markTranslateFailed(pageKey, e.message ?: e::class.java.simpleName)
                                logcat(LogPriority.ERROR, e) { "TachiyomiAT batch translate failed: $pageKey" }
                            } finally {
                                guardedBatchUpdate(pageKey, "batch translation final commit") {
                                    (it ?: p).apply {
                                        translationStatus = p.translationStatus
                                        translationError = p.translationError
                                        updatedAt = System.currentTimeMillis()
                                        if (it != null && it !== p) {
                                            blocks = p.blocks.toMutableList()
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Render join: per-page join of the translation result with its inpaint/render
                // prerequisites. tryRender is idempotent (READY short-circuit) so it is safe to
                // call both at chunk-completion (AI) and here; the per-page render mutex keeps
                // it serialized. This is the join the coordinator awaits for each page.
                val nativeRenderSignals = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
                val translationRenderSignals = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

                fun signalFor(
                    signals: ConcurrentHashMap<String, CompletableDeferred<Unit>>,
                    pageKey: String,
                ): CompletableDeferred<Unit> = signals.getOrPut(pageKey) { CompletableDeferred() }

                val renderJoin = object : RenderJoinWorker {
                    override fun onNativeBranchDone(pageKey: String) {
                        signalFor(nativeRenderSignals, pageKey).complete(Unit)
                    }

                    override fun onTranslationBranchDone(pageKey: String) {
                        signalFor(translationRenderSignals, pageKey).complete(Unit)
                    }

                    override suspend fun awaitAndRender(pageKey: String) {
                        signalFor(nativeRenderSignals, pageKey).await()
                        signalFor(translationRenderSignals, pageKey).await()
                        tryRender(pageKey)
                        nativeRenderSignals.remove(pageKey)
                        translationRenderSignals.remove(pageKey)
                    }
                }

                val coordinator = ChunkBatchCoordinator(
                    nativeWorker = nativeWorker,
                    translatorWorker = translatorWorker,
                    renderJoin = renderJoin,
                )

                try {
                    coroutineScope {
                        // Start the inactivity flusher for the AI lane so a trailing partial
                        // chunk does not wait forever for the next page (Checkpoint 2 §3). It
                        // shares the translator lane's lifetime: cancelled when this scope ends.
                        translatorWorker.inactivityFlusher?.start(this) {
                            translatorWorker.planner?.flushRemaining() ?: eu.kanade.translation.translator.StreamingChunkPlanner.FlushResult(null, emptySet(), emptyMap())
                        }
                        val orderedPages = orderedStreams.mapIndexed { index, (pageKey, _) ->
                            pageKey to (resolvedNaturalPageIndexes[pageKey] ?: index)
                        }
                        coordinator.runPass1(orderedPages, computeClass)
                        // Pass-1 barrier reached: every page's native work + translator-lane
                        // accept is complete. Flush the AI planner's tail chunk (Pass-1
                        // completion) and render any pages completed by it. This MUST finish
                        // before Pass 2 starts (Checkpoint 2 §5 — Pass 2 starts after the
                        // Pass-1 translation barrier, not after inpaint/render).
                        if (isAi) {
                            val planner = translatorWorker.planner
                            if (planner != null) {
                                translatorWorker.inactivityFlusher?.cancelTimer()
                                eu.kanade.translation.SharedProviderRequestAdmission.withRequest {
                                    val flush = planner.flushRemaining()
                                    if (flush.finalChunk != null) {
                                        val (newRolling, newPast) = translateChunkAi(
                                            flush.finalChunk,
                                            flush.completedPages,
                                            translatorWorker.rollingContext,
                                            translatorWorker.pastTranslations,
                                            translatorWorker.analyticalMode,
                                        )
                                        translatorWorker.rollingContext = newRolling
                                        translatorWorker.pastTranslations = newPast
                                    } else {
                                        flush.completedPages.forEach { pk -> completeChunklessPage(pk) }
                                    }
                                    flush.rejectedPages.forEach { (pk, reason) ->
                                        val rp = translationRegistry[pk]
                                        if (rp != null) {
                                            markBatchTranslationFailed(
                                                store,
                                                pk,
                                                rp,
                                                reason,
                                                expectedPrecondition = batchWritePrecondition(pk)
                                                    ?: return@forEach,
                                            )
                                            tracker?.markTranslateFailed(pk, reason)
                                        }
                                    }
                                }
                            }
                        }
                        // Render sweep for any page whose translation completed but render was
                        // deferred (e.g. an AI page whose chunk flushed late).
                    }
                } finally {
                    translatorWorker.inactivityFlusher?.stop()
                    // Only the registry's REMAINING entries need a release here: consumed/
                    // recycled/spilled bitmaps already balanced themselves. Releasing exactly
                    // `leaked` slots restores countSlots with no double-release. This is the
                    // ONLY release site that observes pages whose render never ran.
                    val leaked = bitmapRegistry.size
                    bitmapRegistry.values.forEach {
                        try {
                            it.recycle()
                        } catch (_: Exception) {}
                    }
                    bitmapRegistry.clear()
                    heldBitmapBytes.set(0L)
                    repeat(leaked) { countSlots.release() }
                }

                // OOM abort: markChapterError already recorded the abort reason on every
                // still-pending page, so skip the reconciler finish.
                if (aborted.get()) {
                    logcat(LogPriority.WARN) {
                        "TachiyomiAT batch aborted (OOM), skipping reconciler finish: chapter=${chapter.name}"
                    }
                    store.releaseAllPageLeases(PageWriteOrigin.BATCH)
                    store.flush()
                    return@withGeneration
                }
                logcat(LogPriority.INFO) {
                    "TachiyomiAT batch first pass complete chapter=${chapter.name} pages=${orderedStreams.size}"
                }

                val reconciliation = BatchProgressReconciler.reconcile(
                    pageMap = store.state.value,
                    orderedKeys = orderedStreams.map { it.first },
                    activeGeneration = store.currentGeneration,
                )
                // Persist every expected stranded page before emitting terminal progress. This
                // ensures callers that observe the terminal state can also inspect retryable failures.
                reconciliation.strandedPages.forEach { (pageKey, reason) ->
                    logcat(LogPriority.WARN) {
                        "TachiyomiAT batch stranded page: chapter=${chapter.name} pageKey=$pageKey reason=$reason"
                    }
                    guardedBatchUpdate(pageKey, "batch stranded page") { existing ->
                        (existing ?: PageTranslation(sourceFileName = pageKey)).apply {
                            ocrStatus = StageStatus.FAILED
                            errorMessage = reason
                            updatedAt = System.currentTimeMillis()
                        }
                    }
                }
                store.flush()
                val summaryPublished = store.publishSummary(
                    ChapterTranslationSummary(
                        expectedPageCount = orderedStreams.map { it.first }.distinct().size,
                        terminalOutcome = reconciliation.chapterStatus.value,
                        updatedAtMillis = System.currentTimeMillis(),
                    ),
                )
                if (!summaryPublished) {
                    logcat(LogPriority.ERROR) {
                        "TachiyomiAT batch terminal summary unavailable: chapter=${chapter.name} reason=sidecar publication failed"
                    }
                }
                tracker?.finish(reconciliation)
                logcat(LogPriority.INFO) {
                    "TachiyomiAT batch complete chapter=${chapter.name} pages=${orderedStreams.size} outcome=${reconciliation.chapterStatus}"
                }
                store.releaseAllPageLeases(PageWriteOrigin.BATCH)
            } finally {
                // Cancellation, an unexpected worker exception, or a provider
                // failure must not strand a BATCH lease for the next run.
                store.releaseAllPageLeases(PageWriteOrigin.BATCH)
            }
        }
    }

    private suspend fun markBatchTranslationFailed(
        store: ChapterTranslationStore,
        pageKey: String,
        pageTranslation: PageTranslation,
        reason: String,
        expectedPrecondition: ChapterTranslationStore.PatchPrecondition,
    ) {
        pageTranslation.translationStatus = StageStatus.FAILED
        pageTranslation.errorMessage = reason
        // Translate is typically the first terminal stage, so it owns the attempt charge.
        // recordAttemptFailure is idempotent if a prior stage already failed.
        pageTranslation.recordAttemptFailure()
        store.updatePageGuarded(pageKey, expectedPrecondition, "batch translation failure") {
            (it ?: pageTranslation).apply {
                translationStatus = StageStatus.FAILED
                errorMessage = reason
                attemptCount = pageTranslation.attemptCount
                retryCount = pageTranslation.retryCount
                updatedAt = System.currentTimeMillis()
            }
        }
    }

    /**
     * TachiyomiAT: rebuild the recognition engine + text translator when the
     * current configuration (languages, OCR model, engine signature, or a prior
     * closeEngines()) differs from the cached instances. Shared by the per-page
     * path and the staged batch path so both honor the same rebuild gate without
     * duplicating the (subtle) signature/OcrModel logic.
     *
     * Reads every engine-selection preference live and is idempotent: a no-op
     * when the cached instances already match. Call it at the top of each
     * translation entry point before touching [recognitionEngine] / [textTranslator].
     */
    private suspend fun ensureEnginesBuiltFor(
        fromLang: TextRecognizerLanguage,
        toLang: TextTranslatorLanguage,
    ) {
        val selectedOcrModel = OcrModelCatalog.selectedModel(translationPreferences, fromLang)
        val desiredInpaintingMode = inpaintingModeFromPref()
        val desiredReadingOrder = translationPreferences.translationReadingOrder().get()
        val rebuildClosedEngines = enginesClosed
        // Include inpainting mode AND reading order so FAST<->QUALITY or AUTO/RTL/LTR
        // changes take effect without a language/OCR change or restart.
        if (rebuildClosedEngines ||
            fromLang != currentFromLang ||
            selectedOcrModel != currentOcrModel ||
            desiredInpaintingMode != currentInpaintingMode ||
            desiredReadingOrder != currentReadingOrder
        ) {
            recognitionEngine.close()
            currentFromLang = fromLang
            currentOcrModel = selectedOcrModel
            currentInpaintingMode = desiredInpaintingMode
            currentReadingOrder = desiredReadingOrder
            recognitionEngine = createRecognitionEngine(fromLang, currentOcrModel, currentInpaintingMode)
        }
        // Rebuild the translator whenever the full engine configuration differs — not just
        // on language change. The AI translators capture these at construction and never re-read.
        val desiredSignature = computeTranslatorSignature(fromLang, toLang)
        if (rebuildClosedEngines || desiredSignature != currentTranslatorSignature) {
            withContext(Dispatchers.IO) { textTranslator.close() }
            textTranslator = TranslationEngineBuilder.build(translationPreferences, fromLang, toLang)
            currentTranslatorSignature = desiredSignature
        }
        if (rebuildClosedEngines) {
            enginesClosed = false
        }
    }

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
     * returns regardless of the result, so long-running resume paths
     * ([renderResumedPage], [resumeInpaintAndRender]) also run under the permit
     * — they are edge cases and the ONNX overlap benefit applies only to the
     * fresh translate path.
     */
    private suspend fun translateSinglePageOnnx(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        pageKey: String,
        readerStreamFn: (() -> InputStream)? = null,
        force: Boolean = true,
        stageListener: TranslationStageListener? = null,
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
            val file = mangaDir?.createFile(saveFile) ?: return null
            ChapterTranslationStore.open(file).also { ownStore = it }
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

            if (!workPlan.runOcr && !workPlan.runTranslation && !workPlan.runInpaint && !workPlan.runRender) {
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
                        )
                        return null
                    } else {
                        logcat(LogPriority.INFO) {
                            "TachiyomiAT single-page resume: inpaint + translate + render pageKey=$pageKey"
                        }
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
                store.flush()
                chapter.id?.let { chapterId ->
                    streamRegistry.clearPage(source.id, manga.id, chapterId, pageKey)
                }
                try {
                    recognitionEngine.reclaimPooledMemory()
                } catch (_: Exception) {}
            }
        }
    }

    /**
     * Phase HTTP+Render of the reader single-page path: runs OUTSIDE the permit.
     *
     * Takes the [OnnxPhaseResult] from [translateSinglePageOnnx] (cleanedBitmap
     * alive on [PageTranslation]), translates text blocks via HTTP, renders
     * translated text onto the cleaned bitmap via Canvas, and persists the result.
     *
     * Captures a local [activeTranslator] reference at entry so a concurrent
     * [closeEngines] from a language/config change does not race the in-flight
     * HTTP call. The old translator may be closed mid-flight, causing this page
     * to fail and retry with the new instance — an accepted trade-off without
     * the complexity of drain logic.
     */
    private suspend fun translateSinglePageHttpRender(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        pageKey: String,
        ctx: OnnxPhaseResult,
        stageListener: TranslationStageListener? = null,
    ) {
        val pageTranslation = ctx.pageTranslation
        // Reader-ad-hoc output is displayable, but never advances ordered
        // chapter context. A later batch must retranslate it in sequence.
        pageTranslation.translationOrigin = PageWriteOrigin.READER_ADHOC.name
        pageTranslation.batchContextComplete = false
        pageTranslation.batchContextCheckpointHash = null
        val store = ctx.store
        val fromLang = ctx.fromLang
        val syntheticTranslation = ctx.syntheticTranslation
        val streams = ctx.streams
        val decoded = ctx.decoded
        val batchFingerprints = batchExpectedFingerprints(
            fromLang,
            TextTranslatorLanguage.fromPref(translationPreferences.translateToLanguage()),
        )
        var commitPrecondition = requireNotNull(ctx.commitPrecondition) {
            "single-page commit precondition missing for $pageKey"
        }

        // A fresh reader decode has just run the native stages under the current
        // configuration. Record that provenance so a later ordered batch can
        // reuse detection/OCR/inpaint while still retranslating this ad-hoc
        // result. Cleaned-only resume paths retain their existing provenance;
        // unknown legacy evidence is intentionally not upgraded here.
        if (decoded.sourceBytesSize > 0L) {
            pageTranslation.sourceFingerprint = decoded.sourceFingerprint
            pageTranslation.detectionFingerprint = batchFingerprints.detection
            pageTranslation.ocrFingerprint = batchFingerprints.ocr
            pageTranslation.inpaintFingerprint = batchFingerprints.inpaint
        }
        pageTranslation.translationFingerprint = batchFingerprints.translation
        pageTranslation.layoutFingerprint = batchFingerprints.layout

        val activeTranslator = textTranslator

        // AI translators use translateContextual with the chapter glossary so on-demand
        // single-page translation reuses established terms/pronouns (same continuity the
        // batch path gets). Standard translators keep plain translatePage.
        val requestedOutputTokens = translationPreferences.translationAiOutputTokens().get().toIntOrNull()
            ?: TranslationContextChunkPlanner.MAX_CONTEXT_TOKENS
        val singlePageProfile = if (activeTranslator is LmStudioTranslator) {
            TranslationContextChunkPlanner.Profile.LM_STUDIO
        } else {
            TranslationContextChunkPlanner.Profile.DEFAULT
        }

        suspend fun runTranslate(targetPage: PageTranslation) {
            val ct = activeTranslator as? ContextualTextTranslator
            if (ct != null) {
                val glossaryText = ChapterGlossaryBuilder.formatGlossary(store.glossarySnapshot())
                val estPrompt = TranslationContextChunkPlanner.PROMPT_OVERHEAD_TOKENS +
                    targetPage.blocks.sumOf { TranslationContextChunkPlanner.estimateTokens(it.text) }
                val baseChunk = TranslationContextChunk(
                    pages = linkedMapOf(pageKey to targetPage),
                    blockCount = targetPage.blocks.count { it.text.isNotBlank() },
                    rollingContext = "",
                    estimatedPromptTokens = estPrompt,
                    maxOutputTokens = requestedOutputTokens,
                    protocol = eu.kanade.translation.translator.ContextualRequestProtocol.LEGACY,
                )
                val withRolling = TranslationContextChunkPlanner.withRollingContext(
                    chunk = baseChunk,
                    rollingContext = "",
                    requestedOutputTokens = requestedOutputTokens,
                    profile = singlePageProfile,
                    glossary = glossaryText,
                )
                // Analytical Mode single-page path: past translations come from the chapter
                // store so on-demand translation gets voice/speaker continuity. Future context
                // is empty (single page has no queue). Skipped entirely when off.
                val analyticalMode = translationPreferences.translationAnalyticalMode().get()
                val chunk = if (analyticalMode) {
                    val pastPairs = store.translatedPairs()
                        .let { pairs ->
                            val mapped = pairs.mapNotNull { (src, tgt) ->
                                val t = tgt.trim()
                                if (t.isBlank() || t == src.trim()) null else t
                            }
                            mapped.takeLast(TranslationContextChunkPlanner.MAX_PAST_TRANSLATION_PAIRS)
                                .joinToString("\n")
                        }
                    TranslationContextChunkPlanner.withSlidingContext(
                        chunk = withRolling,
                        pastTranslations = pastPairs,
                        futureContext = "",
                        requestedOutputTokens = requestedOutputTokens,
                        profile = singlePageProfile,
                    )
                } else {
                    withRolling
                }
                ct.translateContextual(chunk)
            } else {
                activeTranslator.translatePage(pageKey, targetPage)
            }
        }

        try {
            coroutineContext.ensureActive()

            if (pageTranslation.blocks.isNotEmpty()) {
                val transDiag = translationPreferences.translationDiagnostics().get()
                val nonEmptyBlocks = pageTranslation.blocks.count { it.text.isNotBlank() }
                logcat(LogPriority.INFO) {
                    "TachiyomiAT translate step START: pageKey=$pageKey " +
                        "translator=${activeTranslator::class.simpleName} " +
                        "blocks=${pageTranslation.blocks.size} nonEmptyText=$nonEmptyBlocks"
                }
                if (transDiag) {
                    pageTranslation.blocks.forEachIndexed { idx, b ->
                        logcat(LogPriority.INFO) {
                            "TachiyomiAT translate INPUT [$idx] text=\"${b.text}\""
                        }
                    }
                }
                try {
                    val readingOrder = translationPreferences.translationReadingOrder().get()
                    pageTranslation.blocks = eu.kanade.translation.util.TranslationBlockSorter.sort(
                        pageTranslation.blocks,
                        fromLang,
                        readingOrder,
                    )
                    pageTranslation.translationStatus = StageStatus.RUNNING
                    stageListener?.onStageEntered(pageKey, TranslationStageEvent.TRANSLATING)
                    runTranslate(pageTranslation)
                    TranslationBlockValidation.applyTo(pageTranslation)
                    var singlePageRetry = 0
                    while (pageTranslation.translationStatus == StageStatus.PARTIAL &&
                        singlePageRetry < SINGLE_PAGE_PARTIAL_MAX_RETRIES
                    ) {
                        val missing = AiTranslationRetryPlanner.untranslatedBlocks(pageTranslation)
                        if (missing.isEmpty()) break
                        singlePageRetry++
                        logcat(LogPriority.INFO) {
                            "TachiyomiAT single-page PARTIAL retry $singlePageRetry/$SINGLE_PAGE_PARTIAL_MAX_RETRIES: " +
                                "pageKey=$pageKey missing=${missing.size}"
                        }
                        pageTranslation.translationStatus = StageStatus.RUNNING
                        val retryPage = PageTranslation(blocks = missing.toMutableList())
                        runTranslate(retryPage)
                        TranslationBlockValidation.applyTo(pageTranslation)
                    }
                    // Fold this page's translated pairs into the chapter glossary so later
                    // on-demand/batch translations reuse its established terms.
                    if (activeTranslator is ContextualTextTranslator) {
                        val stats = ChapterGlossaryBuilder.Stats().also { s ->
                            store.translatedPairs().forEach { (src, tgt) -> s.add(src, tgt) }
                        }
                        pageTranslation.blocks.forEach { b -> stats.add(b.text, b.translation) }
                        store.updateGlossary(stats.build())
                    }
                    val translatedCount = pageTranslation.blocks.count { !it.translation.isNullOrBlank() }
                    logcat(LogPriority.INFO) {
                        "TachiyomiAT translate step DONE: pageKey=$pageKey " +
                            "translated=$translatedCount/${pageTranslation.blocks.size} " +
                            "status=${pageTranslation.translationStatus}" +
                            (if (singlePageRetry > 0) " partialRetries=$singlePageRetry" else "")
                    }
                    if (transDiag) {
                        pageTranslation.blocks.forEachIndexed { idx, b ->
                            logcat(LogPriority.INFO) {
                                "TachiyomiAT translate OUTPUT [$idx] \"${b.text}\" -> \"${b.translation}\""
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    pageTranslation.translationStatus = StageStatus.FAILED
                    pageTranslation.errorMessage = e.message
                    logcat(LogPriority.ERROR, e) { "Failed to translate text for single page $pageKey" }
                }
            }

            coroutineContext.ensureActive()

            if (pageTranslation.blocks.isNotEmpty() &&
                (
                    pageTranslation.translationStatus == StageStatus.READY ||
                        pageTranslation.translationStatus == StageStatus.PARTIAL
                    )
            ) {
                val hasCleaned = pageTranslation.cleanedBitmap != null
                if (hasCleaned) {
                    val cleanedBitmap = pageTranslation.cleanedBitmap!!
                    try {
                        pageTranslation.renderStatus = StageStatus.RUNNING
                        stageListener?.onStageEntered(pageKey, TranslationStageEvent.RENDERING)
                        RenderColorEstimator.recomputeFor(cleanedBitmap, pageTranslation.blocks)
                        pageTranslation.renderStatus = StageStatus.READY
                        pageTranslation.updatedAt = System.currentTimeMillis()
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        pageTranslation.renderStatus = StageStatus.FAILED
                        pageTranslation.recordAttemptFailure()
                        pageTranslation.errorMessage = e.message
                        logcat(LogPriority.ERROR, e) { "Failed to render text for single page $pageKey" }
                    } finally {
                        try {
                            cleanedBitmap.recycle()
                        } catch (_: Exception) {}
                    }
                } else {
                    logcat(LogPriority.WARN) {
                        "TachiyomiAT render blocked for $pageKey: inpaint produced no cleaned bitmap; " +
                            "retrying at lower resolution"
                    }
                    val retryResult = retryInpaintDownscaled(
                        manga = manga,
                        chapter = chapter,
                        source = source,
                        pageKey = pageKey,
                        streams = streams,
                        decoded = decoded,
                        pageTranslation = pageTranslation,
                    )
                    val retriedCleaned = retryResult.cleanedBitmap
                    if (retriedCleaned != null) {
                        val companionDir = provider.getCompanionImageDir(
                            manga.title,
                            source,
                            chapter.name,
                            chapter.scanlator,
                        )
                        val published = persistCleanedBitmap(
                            pageTranslation,
                            retriedCleaned,
                            companionDir,
                            pageKey,
                            chapter.name,
                            store,
                            source.id,
                            manga.id,
                            chapter.id,
                            expectedPrecondition = commitPrecondition,
                        )
                        if (published != null) {
                            commitPrecondition = published.toPrecondition()
                        }
                        try {
                            if (published == null) {
                                pageTranslation.renderStatus = StageStatus.FAILED
                                pageTranslation.errorMessage =
                                    "Cleaned image could not be published; translated text was not rendered."
                            } else {
                                pageTranslation.renderStatus = StageStatus.RUNNING
                                stageListener?.onStageEntered(pageKey, TranslationStageEvent.RENDERING)
                                RenderColorEstimator.recomputeFor(retriedCleaned, pageTranslation.blocks)
                                pageTranslation.renderStatus = StageStatus.READY
                                pageTranslation.updatedAt = System.currentTimeMillis()
                            }
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            pageTranslation.renderStatus = StageStatus.FAILED
                            pageTranslation.recordAttemptFailure()
                            pageTranslation.errorMessage = e.message
                            logcat(LogPriority.ERROR, e) { "Failed to render text for single page (retry path) $pageKey" }
                        } finally {
                            try {
                                retriedCleaned.recycle()
                            } catch (_: Exception) {}
                        }
                    } else {
                        pageTranslation.renderStatus = StageStatus.FAILED
                        pageTranslation.recordAttemptFailure()
                        val reason = pageTranslation.errorMessage ?: "inpaint unavailable"
                        pageTranslation.errorMessage =
                            "Inpainting unavailable ($reason) — original text would show through, so the " +
                            "translated text was not rendered. Retry, or switch recognition mode."
                        logcat(LogPriority.WARN) {
                            "TachiyomiAT single-page render BLOCKED for $pageKey: inpaint unavailable after retry " +
                                "(reason=$reason). Showing original image with error instead of a half-translated overlay."
                        }
                    }
                }
            } else {
                pageTranslation.cleanedBitmap?.let {
                    try {
                        it.recycle()
                    } catch (_: Exception) {}
                }
            }
            pageTranslation.cleanedBitmap = null
            pageTranslation.updatedAt = System.currentTimeMillis()
            val commit = store.patchPage(
                pageKey = pageKey,
                expected = commitPrecondition,
                description = "commit single-page translation and render",
            ) { pageTranslation }
            if (commit is ChapterTranslationStore.PatchResult.Rejected) {
                logcat(LogPriority.WARN) {
                    "TachiyomiAT single-page late result rejected: chapter=${chapter.name} " +
                        "pageKey=$pageKey reason=${commit.reason}"
                }
            } else {
                // A newer committed display bundle may have just promoted; the
                // file it superseded is now deletable.
                deleteRetiredCleanedFile(manga, chapter, source, pageKey, store)
                // CP4: Publish backward-compatible chapter summary after every accepted
                // manual/auto commit so cold manga-screen eligibility discovery does not
                // require a prior batch run. Published as READY_WITH_WARNINGS (partial
                // chapter). Non-fatal if the sidecar write fails.
                val pageCount = store.state.value.size
                runCatching {
                    store.publishSummary(
                        ChapterTranslationSummary(
                            expectedPageCount = pageCount.coerceAtLeast(1),
                            terminalOutcome = eu.kanade.translation.model.Translation.State.READY_WITH_WARNINGS.value,
                            updatedAtMillis = System.currentTimeMillis(),
                        ),
                    )
                }.onFailure { e ->
                    logcat(LogPriority.WARN, e) {
                        "TachiyomiAT single-page summary publication failed (non-fatal): " +
                            "chapter=${chapter.name} pageKey=$pageKey"
                    }
                }
            }
        } finally {
            store.flush()
            // Defensive recycle: a cancel/timeout can unwind here from before render, where
            // cleanedBitmap (the inpainted full-page bitmap, ~10–48 MB) was never recycled.
            pageTranslation.cleanedBitmap?.let {
                try {
                    it.recycle()
                } catch (_: Exception) {}
            }
            pageTranslation.cleanedBitmap = null
            chapter.id?.let { chapterId ->
                streamRegistry.clearPage(source.id, manga.id, chapterId, pageKey)
            }
            try {
                recognitionEngine.reclaimPooledMemory()
            } catch (_: Exception) {}
        }
    }

    private fun PageTranslation.copyForResume(): PageTranslation {
        return copy(blocks = blocks.map { it.copy() }.toMutableList()).also {
            it.cleanedBitmap = null
            it.allTextDetections = emptyList()
        }
    }

    private suspend fun loadPersistedCleanedBitmap(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        cleanedImageName: String,
    ): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val file = provider.findPageCleanedImage(
                manga.title,
                source,
                chapter.name,
                chapter.scanlator,
                cleanedImageName,
            )?.takeIf { it.exists() && it.length() > 0L }
                ?: return@withContext null
            file.openInputStream().use { BitmapFactory.decodeStream(it) }
        } catch (e: Throwable) {
            logcat(LogPriority.WARN, e) {
                "TachiyomiAT failed to load cleaned image for resume: cleaned=$cleanedImageName"
            }
            null
        }
    }

    suspend fun tryRenderStandalone(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        pageKey: String,
        store: ChapterTranslationStore,
    ) {
        val page = store.state.value[pageKey] ?: return
        val capturedGeneration = store.currentGeneration
        if (page.translationStatus != StageStatus.READY && page.translationStatus != StageStatus.PARTIAL) {
            return
        }
        val cleanedName = page.cleanedImageName ?: return
        val bitmap = loadPersistedCleanedBitmap(manga, chapter, source, cleanedName) ?: return
        try {
            store.withGeneration(capturedGeneration) {
                updatePageFromCurrentSnapshot(
                    store,
                    pageKey,
                    "standalone render running",
                    expectedGeneration = capturedGeneration,
                ) { existing ->
                    (existing ?: PageTranslation(sourceFileName = pageKey)).apply {
                        renderStatus = StageStatus.RUNNING
                    }
                }
            }
            RenderColorEstimator.recomputeFor(bitmap, page.blocks)
            store.withGeneration(capturedGeneration) {
                updatePageFromCurrentSnapshot(
                    store,
                    pageKey,
                    "standalone render commit",
                    expectedGeneration = capturedGeneration,
                ) { existing ->
                    (existing ?: PageTranslation(sourceFileName = pageKey)).apply {
                        renderStatus = StageStatus.READY
                        blocks.forEachIndexed { index, b ->
                            if (index < page.blocks.size) {
                                b.textColor = page.blocks[index].textColor
                                b.strokeColor = page.blocks[index].strokeColor
                            }
                        }
                        updatedAt = System.currentTimeMillis()
                    }
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            store.withGeneration(capturedGeneration) {
                updatePageFromCurrentSnapshot(
                    store,
                    pageKey,
                    "standalone render failure",
                    expectedGeneration = capturedGeneration,
                ) { existing ->
                    (existing ?: PageTranslation(sourceFileName = pageKey)).apply {
                        renderStatus = StageStatus.FAILED
                        errorMessage = e.message
                    }
                }
            }
            logcat(LogPriority.ERROR, e) { "Failed to render standalone page $pageKey" }
        } finally {
            try {
                bitmap.recycle()
            } catch (_: Exception) {}
        }
    }

    suspend fun getContextualTranslator(engine: AiEngine, model: String): ContextualTextTranslator? {
        val fromLang = TextRecognizerLanguage.fromPref(translationPreferences.translateFromLanguage())
        val toLang = TextTranslatorLanguage.fromPref(translationPreferences.translateToLanguage())

        val kind = AiTranslatorKind.entries.firstOrNull { it.engine == engine } ?: return null
        val apiKey = translationPreferences.translationAiApiKey(engine).get()
        val maxOutputTokens = translationPreferences.translationAiOutputTokens().get().toIntOrNull() ?: 8192
        val temperature = translationPreferences.translationAiTemperature().get().toFloatOrNull() ?: 0.3f

        val translator = when (kind) {
            AiTranslatorKind.GEMINI -> GeminiTranslator(fromLang, toLang, apiKey, model, maxOutputTokens, temperature)
            AiTranslatorKind.OPENROUTER -> OpenRouterTranslator(fromLang, toLang, apiKey, model, maxOutputTokens, temperature)
            AiTranslatorKind.DEEPSEEK -> DeepSeekTranslator(fromLang, toLang, apiKey, model, maxOutputTokens, temperature)
            AiTranslatorKind.LMSTUDIO -> LmStudioTranslator(
                fromLang = fromLang,
                toLang = toLang,
                baseUrl = translationPreferences.translationAiBaseUrlLmStudio().get(),
                modelName = model,
                maxOutputToken = maxOutputTokens,
                temperature = temperature,
            )
        }
        return translator as? ContextualTextTranslator
    }

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
    ): ChapterTranslationStore.PageSnapshot? = withContext(Dispatchers.IO) {
        val directory = companionDir
        val previousName = pageTranslation.cleanedImageName
        val precondition = expectedPrecondition ?: store.snapshot(pageKey).let { snapshot ->
            ChapterTranslationStore.PatchPrecondition(
                generation = snapshot.generation,
                pageVersion = snapshot.pageVersion,
                blockFingerprints = snapshot.blockFingerprints,
                leaseToken = snapshot.leaseToken,
                candidateGenerationId = snapshot.candidateGenerationId,
                dependencyFingerprint = snapshot.dependencyFingerprint,
                artifactPageVersion = snapshot.artifactPageVersion,
            )
        }
        val publisher = CleanedImagePublisher(object : CleanedImagePublisher.Files {
            override fun writeVerifiedVersionedFile(): String {
                check(directory != null) { "translation output folder is unavailable" }
                val safeName = pageKey.substringAfterLast('/').replace(Regex("[^a-zA-Z0-9._-]"), "_")
                val version = System.currentTimeMillis().toString(36) + "-" + System.nanoTime().toString(36).takeLast(6)
                val finalName = "$safeName.cleaned.$version.jpg"
                val finalFile = directory.findFile(finalName) ?: directory.createFile(finalName)
                check(finalFile != null) { "could not create final cleaned image" }
                finalFile.openOutputStream().use { output ->
                    check(cleanedBitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)) { "JPEG encoding returned false" }
                }
                check(finalFile.exists() && finalFile.length() > 0L) { "published cleaned image is unavailable" }
                return finalName
            }

            override fun delete(name: String): Boolean = directory?.findFile(name)?.delete() ?: true
        })
        when (
            val result = publisher.publish(
                chapterName,
                pageKey,
                previousName,
                commit = { newName ->
                    store.patchPage(pageKey, precondition, "publish cleaned image") { current ->
                        (current ?: pageTranslation).apply {
                            cleanedImageName = newName
                            inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION
                            inpaintingModeUsed = currentInpaintingMode.name
                            inpaintFingerprint = pageTranslation.inpaintFingerprint
                            inpaintStatus = StageStatus.READY
                            errorMessage = null
                        }
                    }
                },
                mayDeletePrevious = { name -> store.mayDeleteCleanedImage(pageKey, name) },
                retirePrevious = { name, delete ->
                    chapterId?.let { stableChapterId ->
                        streamRegistry.retireCleanedImage(
                            sourceId = sourceId,
                            mangaId = mangaId,
                            chapterId = stableChapterId,
                            pageKey = pageKey,
                            imageName = name,
                        ) {
                            if (store.mayDeleteCleanedImage(pageKey, name)) delete()
                        }
                    }
                },
            )
        ) {
            is CleanedImagePublisher.Result.Published -> {
                pageTranslation.cleanedImageName = result.name
                pageTranslation.inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION
                pageTranslation.inpaintingModeUsed = currentInpaintingMode.name
                pageTranslation.inpaintStatus = StageStatus.READY
                pageTranslation.errorMessage = null
                result.snapshot
            }
            is CleanedImagePublisher.Result.Rejected -> null
            is CleanedImagePublisher.Result.WriteFailed -> {
                pageTranslation.inpaintStatus = StageStatus.FAILED
                pageTranslation.recordAttemptFailure()
                pageTranslation.errorMessage = "Could not save cleaned image — translation output folder is unavailable. Grant storage permission to the app and retry."
                null
            }
        }
    }

    private suspend fun persistOnnxCleanedImage(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        pageKey: String,
        result: OnnxPhaseResult,
    ): OnnxPhaseResult? {
        val page = result.pageTranslation
        val cleaned = page.cleanedBitmap
        if (cleaned == null) {
            val snapshot = result.store.snapshot(pageKey)
            return result.copy(commitPrecondition = snapshot.toPrecondition())
        }
        val companionDir = provider.getCompanionImageDir(
            manga.title,
            source,
            chapter.name,
            chapter.scanlator,
        )
        val published = persistCleanedBitmap(
            page,
            cleaned,
            companionDir,
            pageKey,
            chapter.name,
            result.store,
            source.id,
            manga.id,
            chapter.id,
            expectedPrecondition = result.commitPrecondition,
        )
        if (published == null) {
            // Do not let HTTP/render consume an in-memory result when the reader
            // cannot reopen it after publication or a newer page won the race.
            try {
                cleaned.recycle()
            } catch (_: Exception) {}
            page.cleanedBitmap = null
            return null
        }
        return result.copy(commitPrecondition = published.toPrecondition())
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
        if (persistCleanedBitmap(
                pageTranslation,
                cleaned,
                companionDir,
                pageKey,
                chapter.name,
                store,
                source.id,
                manga.id,
                chapter.id,
            ) == null
        ) {
            try {
                cleaned.recycle()
            } catch (_: Exception) {}
            pageTranslation.renderStatus = StageStatus.FAILED
            persistPageWithOomRecovery(store, pageKey, pageTranslation)
            return
        }
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
    private suspend fun retryInpaintDownscaled(
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
    private suspend fun analyzePage(
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
        applyExplicitTextlessSemantics(pageTranslation)

        pageTranslation.originalImgWidth = decoded.originalWidth.toFloat()
        pageTranslation.originalImgHeight = decoded.originalHeight.toFloat()
        pageTranslation.sourceFileName = fileName
        pageTranslation.sourceFingerprint = decoded.sourceFingerprint
        pageTranslation.detectionFingerprint = batchFingerprints.detection
        pageTranslation.ocrFingerprint = batchFingerprints.ocr
        // analyze() sets ocrStatus=READY; inpaint is still pending until stage 2.
        pageTranslation.inpaintStatus = StageStatus.PENDING
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
    private suspend fun inpaintPage(
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
            if (runningWrite is ChapterTranslationStore.PatchResult.Rejected) return pageTranslation
            pageTranslation.cleanedBitmap = recognitionEngine.inpaint(bitmap, pageTranslation)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
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

    private fun applyExplicitTextlessSemantics(page: PageTranslation) {
        if (page.ocrStatus != StageStatus.READY || page.blocks.any { it.text.isNotBlank() }) return
        page.translationStatus = StageStatus.SKIPPED
        page.renderStatus = StageStatus.SKIPPED
        // Detector-only masks still require inpainting; a genuinely empty mask does not.
        if (page.inpaintMaskBoxes.isEmpty()) {
            page.inpaintStatus = StageStatus.SKIPPED
            page.cleanedBitmap?.let { bitmap -> runCatching { bitmap.recycle() } }
            page.cleanedBitmap = null
            page.cleanedImageName = null
        }
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
        applyExplicitTextlessSemantics(pageTranslation)

        pageTranslation.originalImgWidth = decoded.originalWidth.toFloat()
        pageTranslation.originalImgHeight = decoded.originalHeight.toFloat()
        pageTranslation.sourceFileName = fileName
        pageTranslation.updatedAt = System.currentTimeMillis()
        logcat(LogPriority.INFO) {
            "[translation_page] $fileName blocks=${pageTranslation.blocks.size} " +
                "engine=${pageTranslation.recognitionEngine} sample=${pageTranslation.decodeSampleSize} " +
                "cleaned=${pageTranslation.cleanedBitmap != null} " +
                "elapsedMs=${(System.nanoTime() - pageStart) / 1_000_000}"
        }

        // Cooperative cancellation checkpoint: recognize()/inpaint() internals (ONNX native
        // calls) can't observe a cancel, so one issued mid-call only lands at the next suspend
        // point. Drop out here so we don't render/translate/persist a page the caller no longer wants.
        coroutineContext.ensureActive()

        return pageTranslation
    }

    fun forceReleaseNativeBuffers() {
        try {
            recognitionEngine.forceReleaseNativeBuffers()
        } catch (_: Exception) {}
    }

    private fun preflightAnalyzeGate(bitmap: Bitmap, fileName: String) {
        if (recognitionEngine !is RoiPageRecognitionEngine) return
        val decision = TranslationMemoryBudget.canStartAnalyze(bitmap.width, bitmap.height)
        if (decision is TranslationMemoryBudget.MemoryPreflightDecision.Defer) {
            TranslationMemoryBudget.logSnapshot(
                tag = "onnx_analyze_preflight_defer",
                width = bitmap.width,
                height = bitmap.height,
                extra = "file=$fileName reason=${decision.reason}",
            )
            throw LowMemoryRecognitionDeferredException(fileName, bitmap.width, bitmap.height, decision.reason)
        }
    }

    private fun preflightInpaintGate(bitmap: Bitmap, fileName: String) {
        if (recognitionEngine !is RoiPageRecognitionEngine) return
        val decision = TranslationMemoryBudget.canStartInpaint(bitmap.width, bitmap.height)
        if (decision is TranslationMemoryBudget.MemoryPreflightDecision.Defer) {
            TranslationMemoryBudget.logSnapshot(
                tag = "onnx_inpaint_preflight_defer",
                width = bitmap.width,
                height = bitmap.height,
                extra = "file=$fileName reason=${decision.reason}",
            )
            throw LowMemoryRecognitionDeferredException(fileName, bitmap.width, bitmap.height, decision.reason)
        }
    }

    private fun reclaimTranslationMemory(reason: String, trimImageCache: Boolean) {
        val before = TranslationMemoryBudget.snapshot()
        BitmapPool.releaseAll()
        try {
            recognitionEngine.forceReleaseNativeBuffers()
        } catch (e: Throwable) {
            logcat(LogPriority.WARN, e) { "forceReleaseNativeBuffers threw during memory reclaim: $reason" }
        }
        if (trimImageCache) {
            try {
                context.imageLoader.memoryCache?.trimToSize(0)
            } catch (e: Throwable) {
                logcat(LogPriority.WARN, e) { "Coil memory-cache trim failed during memory reclaim: $reason" }
            }
        }
        System.gc()
        val after = TranslationMemoryBudget.snapshot()
        logcat(LogPriority.WARN) {
            "[translation_reclaim] reason=$reason trimImageCache=$trimImageCache " +
                "heapBefore=${before.usedHeapBytes.toMiB()}MiB/${before.maxHeapBytes.toMiB()}MiB " +
                "availBefore=${before.availableHeapBytes.toMiB()}MiB " +
                "heapAfter=${after.usedHeapBytes.toMiB()}MiB/${after.maxHeapBytes.toMiB()}MiB " +
                "availAfter=${after.availableHeapBytes.toMiB()}MiB"
        }
    }

    private fun logDecodeDecision(
        fileName: String,
        width: Int,
        height: Int,
        beforeReclaim: DecodeDecision?,
        afterReclaim: DecodeDecision,
    ) {
        val before = beforeReclaim?.let {
            " before=${it.kind}/sample=${it.sampleSize}/avail=${it.snapshot.availableHeapBytes.toMiB()}MiB"
        } ?: ""
        logcat(LogPriority.INFO) {
            "[translation_decode_decision] file=$fileName page=${width}x$height " +
                "decision=${afterReclaim.kind} sample=${afterReclaim.sampleSize} " +
                "raw=${afterReclaim.rawBitmapBytes.toMiB()}MiB " +
                "sampled=${afterReclaim.sampledBitmapBytes.toMiB()}MiB " +
                "avail=${afterReclaim.snapshot.availableHeapBytes.toMiB()}MiB$before"
        }
    }

    private fun Long.toMiB(): Long = this / (1024L * 1024L)

    private fun handleCriticalTranslationOom(stage: String, oom: OutOfMemoryError) {
        BitmapPool.releaseAll()
        forceReleaseNativeBuffers()
        System.gc()
        TranslationMemoryBudget.logSnapshot(tag = "oom_recovery", extra = "stage=$stage message=${oom.message}")
    }

    private suspend fun persistPageWithOomRecovery(
        store: ChapterTranslationStore,
        fileName: String,
        pageTranslation: PageTranslation,
        expectedPrecondition: ChapterTranslationStore.PatchPrecondition? = null,
    ) {
        suspend fun persist(
            description: String,
            update: (PageTranslation?) -> PageTranslation,
        ) {
            if (expectedPrecondition != null) {
                store.updatePageGuarded(fileName, expectedPrecondition, description, update)
            } else {
                updatePageFromCurrentSnapshot(store, fileName, description, update = update)
            }
        }
        try {
            if (TranslationMemoryBudget.isCriticalHeap()) {
                handleCriticalTranslationOom("persisting $fileName", OutOfMemoryError("critical heap before persist"))
            }
            persist("persist page metadata") { pageTranslation }
        } catch (oom: OutOfMemoryError) {
            handleCriticalTranslationOom("persisting $fileName", oom)
            pageTranslation.cleanedBitmap = null
            try {
                persist("retry persist page metadata") { pageTranslation }
            } catch (retryOom: OutOfMemoryError) {
                handleCriticalTranslationOom("persisting lightweight failure $fileName", retryOom)
                persist("persist page OOM failure") {
                    createFailedPagePlaceholder(
                        fileName,
                        "OOM while saving translation metadata: ${retryOom.message}",
                        imgWidth = pageTranslation.imgWidth,
                        imgHeight = pageTranslation.imgHeight,
                        originalImgWidth = pageTranslation.originalImgWidth,
                        originalImgHeight = pageTranslation.originalImgHeight,
                        decodeSampleSize = pageTranslation.decodeSampleSize,
                    )
                }
            }
        }
    }

    private fun decodePageBitmapAtSize(fileName: String, sampleSize: Int, streams: List<Pair<String, () -> InputStream>>): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = sampleSize
        }
        return try {
            val entry = streams.find { it.first == fileName } ?: return null
            entry.second().use { BitmapFactory.decodeStream(it, null, options) }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun decodePageBitmapForTranslation(fileName: String, streamFn: () -> InputStream): DecodedPage? = withContext(Dispatchers.IO) {
        val buffered: ByteArray = try {
            streamFn().use { it.readBytes() }
        } catch (oom: OutOfMemoryError) {
            reclaimTranslationMemory("decode bounds $fileName", trimImageCache = true)
            logcat(LogPriority.ERROR, oom) { "Out of memory reading page bytes for $fileName" }
            return@withContext null
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Failed reading page bytes for $fileName" }
            return@withContext null
        }
        val sourceFingerprint = MessageDigest.getInstance("SHA-256")
            .digest(buffered)
            .joinToString("") { byte -> "%02x".format(byte) }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            java.io.ByteArrayInputStream(buffered).use { BitmapFactory.decodeStream(it, null, bounds) }
        } catch (oom: OutOfMemoryError) {
            reclaimTranslationMemory("decode bounds $fileName", trimImageCache = true)
            logcat(LogPriority.ERROR, oom) { "Out of memory reading page bounds for $fileName" }
            return@withContext null
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Failed reading page bounds for $fileName" }
            return@withContext null
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null

        var decision = TranslationMemoryBudget.chooseDecodeDecision(bounds.outWidth, bounds.outHeight, buffered.size.toLong())
        if (decision.kind == DecodeDecisionKind.HEAP_CONSTRAINED) {
            val before = decision
            reclaimTranslationMemory("decode preflight $fileName", trimImageCache = true)
            decision = TranslationMemoryBudget.chooseDecodeDecision(bounds.outWidth, bounds.outHeight, buffered.size.toLong())
            logDecodeDecision(fileName, bounds.outWidth, bounds.outHeight, before, decision)
        } else {
            logDecodeDecision(fileName, bounds.outWidth, bounds.outHeight, null, decision)
        }

        if (decision.kind == DecodeDecisionKind.HEAP_CONSTRAINED) {
            throw LowMemoryDecodeDeferredException(fileName, bounds.outWidth, bounds.outHeight, decision)
        }

        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = decision.sampleSize
        }
        val bitmap = try {
            java.io.ByteArrayInputStream(buffered).use { BitmapFactory.decodeStream(it, null, options) }
        } catch (oom: OutOfMemoryError) {
            reclaimTranslationMemory("decode bitmap $fileName", trimImageCache = true)
            logcat(LogPriority.ERROR, oom) {
                "Out of memory decoding accepted bitmap for $fileName " +
                    "sample=${decision.sampleSize} reason=${decision.kind}"
            }
            throw LowMemoryDecodeDeferredException(fileName, bounds.outWidth, bounds.outHeight, decision)
        } ?: return@withContext null

        DecodedPage(
            bitmap = bitmap,
            sampleSize = decision.sampleSize,
            originalWidth = bounds.outWidth,
            originalHeight = bounds.outHeight,
            decodeDecision = decision,
            sourceBytesSize = buffered.size.toLong(),
            sourceFingerprint = sourceFingerprint,
        )
    }

    private suspend fun computeSourceFingerprint(streamFn: () -> InputStream): String? = withContext(Dispatchers.IO) {
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            streamFn().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read > 0) digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Failed hashing translation source bytes" }
            null
        }
    }

    private fun batchExpectedFingerprints(
        fromLang: TextRecognizerLanguage,
        toLang: TextTranslatorLanguage,
    ): BatchExpectedFingerprints = BatchExpectedFingerprints(
        detection = StageFingerprints.configuration(
            ArtifactStage.DETECTION,
            currentOcrModel.name,
            currentReadingOrder.name,
        ),
        ocr = StageFingerprints.configuration(
            ArtifactStage.OCR,
            currentOcrModel.name,
            fromLang.name,
        ),
        inpaint = StageFingerprints.configuration(
            ArtifactStage.INPAINT,
            currentInpaintingMode.name,
            PageTranslation.CURRENT_INPAINT_REVISION,
        ),
        translation = StageFingerprints.configuration(
            ArtifactStage.TRANSLATION,
            currentTranslatorSignature.toString(),
            fromLang.name,
            toLang.name,
        ),
        layout = StageFingerprints.configuration(
            ArtifactStage.LAYOUT,
            currentReadingOrder.name,
        ),
    )

    private data class DecodedPage(
        val bitmap: Bitmap,
        val sampleSize: Int,
        val originalWidth: Int,
        val originalHeight: Int,
        val decodeDecision: DecodeDecision,
        val sourceBytesSize: Long,
        val sourceFingerprint: String? = null,
    )

    /**
     * Result of the permit-held ONNX phase ([translateSinglePageOnnx])
     * in the reader single-page path. Carries the [PageTranslation] (with its
     * cleanedBitmap alive across the permit boundary) and the state needed by
     * the permit-free HTTP+render phase ([translateSinglePageHttpRender]).
     */
    private data class OnnxPhaseResult(
        val pageTranslation: PageTranslation,
        val store: ChapterTranslationStore,
        val fromLang: TextRecognizerLanguage,
        val syntheticTranslation: Translation,
        val streams: List<Pair<String, () -> InputStream>>,
        val decoded: DecodedPage,
        val commitPrecondition: ChapterTranslationStore.PatchPrecondition? = null,
    )

    private class LowMemoryDecodeDeferredException(
        fileName: String,
        val width: Int,
        val height: Int,
        val decision: DecodeDecision,
    ) : RuntimeException(
        "Low memory translating $fileName: released caches, but full-quality decode is still unsafe " +
            "(page=${width}x$height raw=${decision.rawBitmapBytes / (1024L * 1024L)}MiB " +
            "available=${decision.snapshot.availableHeapBytes / (1024L * 1024L)}MiB). Retry when memory recovers.",
    )

    private class LowMemoryRecognitionDeferredException(
        val fileName: String,
        val width: Int,
        val height: Int,
        val reason: String,
    ) : RuntimeException(
        "Low memory translating $fileName: $reason (page=${width}x$height). Retry when memory recovers.",
    )

    private fun getChapterPages(chapterPath: UniFile): List<Pair<String, () -> InputStream>> {
        if (chapterPath.isFile) {
            chapterPath.archiveReader(context).use { reader ->
                return reader.useEntries { entries ->
                    entries.filter { entry ->
                        // ImageUtil.isImage handles a null name; throw on unreadable entries
                        // instead of NPE'ing on the `!!` that used to be here.
                        entry.isFile &&
                            ImageUtil.isImage(entry.name) {
                                reader.getInputStream(entry.name)
                                    ?: throw java.io.IOException("Archive entry '${entry.name}' could not be opened")
                            }
                    }
                        .sortedWith { f1, f2 -> f1.name.compareToCaseInsensitiveNaturalOrder(f2.name) }.map { entry ->
                            Pair(entry.name) {
                                chapterPath.archiveReader(context).use { archive ->
                                    // Throw an explicit IOException instead of an NPE so the caller's
                                    // try/catch reports the real cause on a corrupt/vanished entry.
                                    val stream = archive.getInputStream(entry.name)
                                        ?: throw java.io.IOException(
                                            "Archive entry '${entry.name}' could not be opened",
                                        )
                                    stream.use { it.readBytes() }.inputStream()
                                }
                            }
                        }.toList()
                }
            }
        } else {
            // listFiles() returns null on I/O error or a revoked SAF tree URI; return empty
            // (the caller treats "no pages" as a no-op) instead of NPE'ing.
            val files = chapterPath.listFiles() ?: run {
                logcat(LogPriority.WARN) {
                    "TachiyomiAT getChapterPages: listFiles() returned null for ${chapterPath.filePath}"
                }
                return emptyList()
            }
            return files.mapNotNull { entry ->
                // entry.name is nullable on some SAF providers; skip nameless entries.
                val name = entry.name ?: return@mapNotNull null
                if (!ImageUtil.isImage(name)) return@mapNotNull null
                Pair(name) { entry.openInputStream() }
            }.sortedWith { f1, f2 -> f1.first.compareToCaseInsensitiveNaturalOrder(f2.first) }.toList()
        }
    }
}
