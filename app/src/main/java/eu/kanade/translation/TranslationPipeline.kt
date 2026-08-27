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
import eu.kanade.translation.artifact.ArtifactStageStatus
import eu.kanade.translation.artifact.DurableFailureMetadata
import eu.kanade.translation.artifact.FailureCategory
import eu.kanade.translation.artifact.StageFingerprints
import eu.kanade.translation.batch.BatchContextFrontier
import eu.kanade.translation.batch.BatchDiagnosticDecision
import eu.kanade.translation.batch.BatchDiagnosticReason
import eu.kanade.translation.batch.BatchDiagnosticStage
import eu.kanade.translation.batch.BatchEnvelopeLifecycle
import eu.kanade.translation.batch.BatchPass1Outcome
import eu.kanade.translation.batch.BatchPass1Status
import eu.kanade.translation.batch.BatchProgressReconciler
import eu.kanade.translation.batch.BatchResumeGateDecider
import eu.kanade.translation.batch.BatchTranslationDiagnostics
import eu.kanade.translation.batch.ChunkAdmission
import eu.kanade.translation.batch.ChunkCompletionOutcome
import eu.kanade.translation.batch.NativeLaneWorker
import eu.kanade.translation.batch.OcrReadyPageRef
import eu.kanade.translation.batch.RenderJoinWorker
import eu.kanade.translation.batch.SequentialBatchCoordinator
import eu.kanade.translation.batch.TranslationBatchProgressTracker
import eu.kanade.translation.batch.TranslatorLaneWorker
import eu.kanade.translation.data.TranslationProvider
import eu.kanade.translation.inpainting.InpaintingMode
import eu.kanade.translation.model.BatchExpectedFingerprints
import eu.kanade.translation.model.BatchPlannerInput
import eu.kanade.translation.model.BatchStage
import eu.kanade.translation.model.PageStage
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.PageWorkPlanner
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.detachedCopy
import eu.kanade.translation.model.hasCurrentInpaintMask
import eu.kanade.translation.model.hasCurrentInpaintResult
import eu.kanade.translation.model.isCleanedImageReady
import eu.kanade.translation.model.isTextlessTerminal
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
import eu.kanade.translation.translator.AdmissionPriority
import eu.kanade.translation.translator.AiTranslationRetryPlanner
import eu.kanade.translation.translator.AiTranslatorKind
import eu.kanade.translation.translator.ChapterGlossaryBuilder
import eu.kanade.translation.translator.ContextualTextTranslator
import eu.kanade.translation.translator.DeepSeekTranslator
import eu.kanade.translation.translator.GeminiTranslator
import eu.kanade.translation.translator.LmStudioTranslator
import eu.kanade.translation.translator.OpenRouterTranslator
import eu.kanade.translation.translator.ProviderFailure
import eu.kanade.translation.translator.ProviderFailureException
import eu.kanade.translation.translator.ProviderFailureKind
import eu.kanade.translation.translator.ProviderFailureRetryability
import eu.kanade.translation.translator.StableBlockIds
import eu.kanade.translation.translator.StreamingChunkPlanner
import eu.kanade.translation.translator.TextTranslator
import eu.kanade.translation.translator.TextTranslatorLanguage
import eu.kanade.translation.translator.TranslationBlockValidation
import eu.kanade.translation.translator.TranslationContextChunk
import eu.kanade.translation.translator.TranslationContextChunkPlanner
import eu.kanade.translation.translator.TranslationEngineBuilder
import eu.kanade.translation.translator.TranslationResponseFaithfulness
import eu.kanade.translation.translator.TranslatorComputeClass
import eu.kanade.translation.translator.applyAiChunkOutcomeToPages
import eu.kanade.translation.translator.classifyProviderFailure
import eu.kanade.translation.translator.translateAiChunkWithAdaptiveRetry
import eu.kanade.translation.translator.withProviderRequestPriority
import eu.kanade.translation.util.ShortHash
import eu.kanade.translation.util.TranslationMemoryBudget
import eu.kanade.translation.util.TranslationMemoryBudget.DecodeDecision
import eu.kanade.translation.util.TranslationMemoryBudget.DecodeDecisionKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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

private fun ProviderFailure.toFailureCategory(): FailureCategory = when (kind) {
    ProviderFailureKind.NETWORK,
    ProviderFailureKind.RATE_LIMIT,
    ProviderFailureKind.QUOTA_EXHAUSTED,
    ProviderFailureKind.SERVER,
    -> FailureCategory.TRANSIENT
    ProviderFailureKind.REFUSAL -> FailureCategory.PROVIDER_REFUSAL
    ProviderFailureKind.AUTHENTICATION,
    ProviderFailureKind.CONFIGURATION,
    -> FailureCategory.CONFIGURATION
    ProviderFailureKind.SOURCE -> FailureCategory.SOURCE
    ProviderFailureKind.PROTOCOL -> FailureCategory.PROTOCOL
}

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
    // max-tokens, reading-order, or languages forces a rebuild — not just
    // language changes. Without this the cached AI translator (which captures key/model/temp
    // at construction and never re-reads prefs) survives a stop+reconfigure+restart. readingOrder
    // MUST be included because it's cached at construction too. apiKeyHash is a
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

    /** Chapter-close hook for bounded storage cleanup owned by TranslationManager. */
    @Volatile
    var onBatchClosed: (suspend (Manga, Chapter, HttpSource, ChapterTranslationStore) -> Unit)? = null

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
     * Scheduling scope: this single-page path bypasses the batch coordinator
     * (`SequentialBatchCoordinator.runPass1`) by design — it processes
     * exactly one page, so there is no chapter-wide pass to join. The
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
        withProviderRequestPriority(AdmissionPriority.INTERACTIVE) {
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
            pageTranslation.cleanedBitmap = null

            // The prepared-page boundary does not re-decode the source on the
            // translation side: the cleaned image is the durable artifact. An empty
            // streams list steers the render-retry path away from source re-decode.
            val streams = emptyList<Pair<String, () -> InputStream>>()
            val origW = if (pageTranslation.originalImgWidth > 0f) pageTranslation.originalImgWidth.toInt() else (pageTranslation.imgWidth.toInt().coerceAtLeast(1))
            val origH = if (pageTranslation.originalImgHeight > 0f) pageTranslation.originalImgHeight.toInt() else (pageTranslation.imgHeight.toInt().coerceAtLeast(1))
            val dummyBitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ALPHA_8)
            val decoded = DecodedPage(
                bitmap = dummyBitmap,
                sampleSize = pageTranslation.decodeSampleSize.coerceAtLeast(1),
                originalWidth = origW,
                originalHeight = origH,
                decodeDecision = DecodeDecision(
                    kind = DecodeDecisionKind.FULL,
                    sampleSize = pageTranslation.decodeSampleSize.coerceAtLeast(1),
                    rawBitmapBytes = 0L,
                    sampledBitmapBytes = 0L,
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
            val batchWriteIdentities = ConcurrentHashMap<String, BatchWriteIdentity>()
            // Paused/terminal durable failures retain their candidate and
            // manifest metadata until the next explicit retry/reset. The
            // outer teardown releases these leases rather than cancelling
            // the candidate that explains the durable state.
            val durableFailurePageKeys = ConcurrentHashMap.newKeySet<String>()
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

                // SequentialBatchCoordinator owns the phase barriers. AI admission feeds
                // the token planner without provider calls; one overflow page may be kept
                // as an OCR-only probe. Each actual chunk OCRs completely before its AI
                // request and native inpaint branches overlap, and render joins both.
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
                val renderMutexes = ConcurrentHashMap<String, Mutex>()
                val aborted = AtomicBoolean(false)
                val expectedBatchFingerprints = batchExpectedFingerprints(fromLang, toLang)

                // Resume context is a natural-order frontier, never a chapter-wide
                // unordered snapshot. Pages after the first missing/failed predecessor
                // remain durable and reusable, but cannot become context until traversal
                // reaches them; a non-textless terminal gap blocks later AI admission.
                val contextFrontier = BatchContextFrontier(resolvedNaturalPageIndexes)
                var rollingContext = contextFrontier.rollingContext

                fun recordContextPage(
                    pageKey: String,
                    page: PageTranslation,
                    terminalFailure: Boolean = false,
                ) {
                    contextFrontier.record(pageKey, page, terminalFailure)
                    rollingContext = contextFrontier.rollingContext
                }

                // Source identity is a direct input to detection and inpaint.
                // Hash the downloaded bytes before planning so replacing a
                // page under the same natural key cannot reuse old artifacts.
                // This is an I/O-only preflight; no detector/OCR/inpaint or
                // translator work is invoked for a matching completed page.
                val sourceFingerprints = coroutineScope {
                    orderedStreams.map { (pageKey, streamFn) ->
                        async(Dispatchers.IO) {
                            pageKey to (computeSourceFingerprint(streamFn) ?: UNKNOWN_SOURCE_FINGERPRINT)
                        }
                    }.awaitAll().toMap()
                }

                fun stampBatchProvenance(page: PageTranslation, stage: BatchStage?): PageTranslation = page.apply {
                    when (stage) {
                        BatchStage.OCR -> {
                            detectionFingerprint = expectedBatchFingerprints.detection
                            ocrFingerprint = expectedBatchFingerprints.ocr
                        }
                        BatchStage.INPAINT -> inpaintFingerprint = expectedBatchFingerprints.inpaint
                        BatchStage.TRANSLATION -> {
                            translationFingerprint = expectedBatchFingerprints.translation
                            translationOrigin = PageWriteOrigin.BATCH.name
                        }
                        BatchStage.LAYOUT -> layoutFingerprint = expectedBatchFingerprints.layout
                        else -> {}
                    }
                }

                // Plan the complete chapter once, in the same natural order
                // passed to the coordinator.  Native resume gates consume this
                // snapshot; they never derive work from lastPageRead or the
                // reader viewport.
                fun buildBatchPagePlans() = PageWorkPlanner.planChapter(
                    orderedStreams.map { (pageKey, _) ->
                        BatchPlannerInput(
                            pageKey = pageKey,
                            page = store.state.value[pageKey],
                            expectedFingerprints = expectedBatchFingerprints,
                            sourceFingerprint = sourceFingerprints[pageKey],
                            durableFailure = store.durableFailure(pageKey),
                        )
                    },
                ).pages.associateBy { it.pageKey }

                val batchPagePlans = buildBatchPagePlans()

                fun translationFailureFence(pageKey: String): Boolean =
                    batchPagePlans[pageKey]
                        ?.stages
                        ?.firstOrNull { it.stage == BatchStage.TRANSLATION }
                        ?.decision in setOf(
                        eu.kanade.translation.model.StageDecision.FAILED,
                        eu.kanade.translation.model.StageDecision.FAILED_RETRYABLE,
                        eu.kanade.translation.model.StageDecision.FAILED_TERMINAL,
                    )

                // Only seed from pages whose current translation plan still proves that
                // their persisted result is reusable/terminal. A stale page snapshot must
                // not become a context predecessor merely because its old status is READY.
                contextFrontier.seed(
                    pages = store.state.value,
                    eligible = { pageKey, _ ->
                        batchPagePlans[pageKey]
                            ?.stages
                            ?.firstOrNull { it.stage == BatchStage.TRANSLATION }
                            ?.decision in setOf(
                            eu.kanade.translation.model.StageDecision.REUSE,
                            eu.kanade.translation.model.StageDecision.TERMINAL_COMPLETE,
                        )
                    },
                    terminalFailure = { pageKey, page ->
                        val durableRetryable = store.durableFailure(pageKey)?.status ==
                            eu.kanade.translation.artifact.ArtifactStageStatus.FAILED_RETRYABLE
                        val plannedTerminal = batchPagePlans[pageKey]
                            ?.stages
                            ?.firstOrNull { it.stage == BatchStage.TRANSLATION }
                            ?.decision in setOf(
                            eu.kanade.translation.model.StageDecision.FAILED,
                            eu.kanade.translation.model.StageDecision.FAILED_TERMINAL,
                        )
                        (page.translationStatus == StageStatus.FAILED && !durableRetryable) ||
                            (plannedTerminal && !page.isTextlessTerminal)
                    },
                )
                rollingContext = contextFrontier.rollingContext

                fun plannedTranslationDecision(pageKey: String) =
                    batchPagePlans[pageKey]?.stages?.firstOrNull { it.stage == BatchStage.TRANSLATION }?.decision

                fun recordReusableContextPage(pageKey: String, page: PageTranslation) {
                    if (!isAi ||
                        plannedTranslationDecision(pageKey) !in setOf(
                            eu.kanade.translation.model.StageDecision.REUSE,
                            eu.kanade.translation.model.StageDecision.TERMINAL_COMPLETE,
                        )
                    ) {
                        return
                    }
                    if (page.ocrStatus in setOf(StageStatus.READY, StageStatus.TEXTLESS) &&
                        page.translationStatus in setOf(
                            StageStatus.READY,
                            StageStatus.PARTIAL,
                            StageStatus.SKIPPED,
                        )
                    ) {
                        // A reused page becomes context only when natural traversal reaches it.
                        // Pages after a missing predecessor remain retained by the frontier until
                        // that predecessor resolves, so they cannot leak into an earlier request.
                        recordContextPage(pageKey, page)
                    }
                }

                fun plannedTranslationNeedsWork(pageKey: String): Boolean =
                    plannedTranslationDecision(pageKey) == eu.kanade.translation.model.StageDecision.RUN ||
                        batchPagePlans[pageKey]
                            ?.stages
                            ?.firstOrNull { it.stage == BatchStage.TRANSLATION }
                            ?.let { decision ->
                                decision.decision == eu.kanade.translation.model.StageDecision.FAILED_RETRYABLE &&
                                    decision.retryEligible
                            } == true

                fun plannedRenderNeedsWork(pageKey: String): Boolean =
                    batchPagePlans[pageKey]?.let { plan ->
                        val translation = plan.stages.first { it.stage == BatchStage.TRANSLATION }
                        val ocr = plan.stages.first { it.stage == BatchStage.OCR }
                        if (translation.decision in setOf(
                                eu.kanade.translation.model.StageDecision.FAILED,
                                eu.kanade.translation.model.StageDecision.FAILED_RETRYABLE,
                                eu.kanade.translation.model.StageDecision.FAILED_TERMINAL,
                            ) ||
                            ocr.decision in setOf(
                                eu.kanade.translation.model.StageDecision.FAILED,
                                eu.kanade.translation.model.StageDecision.FAILED_RETRYABLE,
                                eu.kanade.translation.model.StageDecision.FAILED_TERMINAL,
                            ) ||
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
                    stage: BatchStage?,
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
                        update = { current -> stampBatchProvenance(update(current), stage) },
                    )
                    if (result is ChapterTranslationStore.PatchResult.Accepted) {
                        identity.pageVersion = result.snapshot.pageVersion
                        identity.candidateGenerationId = result.snapshot.candidateGenerationId
                        identity.dependencyFingerprint = result.snapshot.dependencyFingerprint
                        identity.artifactPageVersion = result.snapshot.artifactPageVersion
                    }
                    return result
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

                suspend fun persistAiFailure(
                    pageKey: String,
                    page: PageTranslation,
                    failure: ProviderFailure,
                    retryable: Boolean,
                    partialCandidate: Boolean,
                    envelopeId: String?,
                    missingBlockIds: Set<String>,
                ): ChapterTranslationStore.PatchResult {
                    val expected = batchWritePrecondition(pageKey)
                        ?: return ChapterTranslationStore.PatchResult.Rejected("batch page lease missing")
                    val now = System.currentTimeMillis()
                    val failureStatus = if (retryable) {
                        ArtifactStageStatus.FAILED_RETRYABLE
                    } else {
                        ArtifactStageStatus.FAILED_TERMINAL
                    }
                    val liveStatus = if (retryable && partialCandidate) {
                        StageStatus.PARTIAL
                    } else {
                        StageStatus.FAILED
                    }
                    val candidate = page.detachedCopy().apply {
                        translationStatus = liveStatus
                        translationError = failure.safeSummary
                        errorMessage = failure.safeSummary
                        recordAttemptFailure()
                        updatedAt = now
                    }
                    val durable = DurableFailureMetadata(
                        pageKey = pageKey,
                        stage = ArtifactStage.TRANSLATION,
                        status = failureStatus,
                        category = failure.toFailureCategory(),
                        retryCount = candidate.retryCount,
                        lastFailureMessage = failure.safeSummary,
                        lastFailedAtEpochMs = now,
                        nextEligibleRetryAtEpochMs = failure.retryAfterAtEpochMs,
                        failureFingerprint = expectedBatchFingerprints.translation,
                        envelopeId = envelopeId,
                        missingBlockIds = missingBlockIds,
                    )
                    val result = store.persistDurableStageFailure(
                        pageKey = pageKey,
                        expected = expected,
                        failure = durable,
                        description = "batch durable translation failure",
                    ) { current ->
                        (current ?: candidate).apply {
                            blocks = candidate.blocks.toMutableList()
                            translationStatus = candidate.translationStatus
                            translationError = candidate.translationError
                            errorMessage = candidate.errorMessage
                            retryCount = candidate.retryCount
                            attemptCount = candidate.attemptCount
                            updatedAt = now
                        }
                    }
                    if (result is ChapterTranslationStore.PatchResult.Accepted) {
                        refreshBatchIdentity(pageKey, result.snapshot)
                        durableFailurePageKeys += pageKey
                    }
                    return result
                }

                suspend fun releaseBatchLease(pageKey: String) {
                    batchWriteIdentities.remove(pageKey)
                    releaseBatchPageLease(store, pageKey)
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

                suspend fun abortBatchCandidate(pageKey: String, reason: String) {
                    recycleHeld(pageKey)
                    translationRegistry.remove(pageKey)
                    batchWriteIdentities.remove(pageKey)
                    if (pageKey in durableFailurePageKeys) {
                        releaseBatchPageLease(store, pageKey)
                        logcat(LogPriority.INFO) {
                            "TachiyomiAT batch durable failure retained: pageKey=$pageKey reason=$reason"
                        }
                        return
                    }
                    val cancelled = store.cancelPageStageWork(pageKey, PageWriteOrigin.BATCH)
                    logcat(if (cancelled) LogPriority.INFO else LogPriority.WARN) {
                        "TachiyomiAT batch candidate aborted: pageKey=$pageKey " +
                            "cancelled=$cancelled reason=$reason"
                    }
                }

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
                            abortBatchCandidate(pageKey, page.errorMessage!!)
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
                            val renderResult = store.mergeRender(patch)
                            renderPersisted = renderResult is StagePatchResult.Accepted
                            if (renderResult is StagePatchResult.Rejected) {
                                abortBatchCandidate(pageKey, "render commit rejected: ${renderResult.reason}")
                                return@withLock
                            }
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
                                abortBatchCandidate(pageKey, page.activeError ?: "render did not commit")
                            } else {
                                translationRegistry.remove(pageKey)
                                releaseBatchLease(pageKey)
                            }
                        }
                    }
                }

                val chunkCounter = AtomicLong(0L)

                /**
                 * Translates one AI envelope and commits its completed pages in
                 * natural order. The rolling context is the bounded window of
                 * recent source=>target pairs (voice/terminology continuity);
                 * a page whose output is a structural refusal fails alone — it
                 * never cascades to later pages of the envelope.
                 */
                suspend fun translateChunkAi(
                    chunk: TranslationContextChunk,
                    rolling: String,
                ): ChunkCompletionOutcome {
                    coroutineContext.ensureActive()
                    val ct = contextualTranslator ?: return ChunkCompletionOutcome.Completed()

                    fun firstUnresolved(completed: Set<String>): String? = chunk.pages.keys
                        .sortedBy { resolvedNaturalPageIndexes[it] ?: Int.MAX_VALUE }
                        .firstOrNull { it !in completed }

                    fun protocolFailure(reason: String): ProviderFailure = ProviderFailure(
                        kind = ProviderFailureKind.PROTOCOL,
                        retryability = ProviderFailureRetryability.TERMINAL,
                        safeSummary = reason,
                        requestId = BatchTranslationDiagnostics.envelopeId(chunk.pages.keys),
                    )

                    contextFrontier.gapIndex?.let { gapIndex ->
                        val blocked = chunk.pages.keys.sortedBy { resolvedNaturalPageIndexes[it] ?: Int.MAX_VALUE }.filter { pageKey ->
                            contextFrontier.blocksLaterAi(pageKey)
                        }
                        if (blocked.isNotEmpty()) {
                            val reason = "blocked by non-textless terminal context gap at index $gapIndex"
                            val anchor = blocked.first()
                            // The terminal predecessor already owns the durable error. This
                            // later page is only the first blocked admission; recording another
                            // failure here would synthesize a tail error and make the user fix a
                            // page that was never sent to the provider.
                            return ChunkCompletionOutcome.Failed(
                                anchorPageKey = anchor,
                                terminalPageKeys = emptySet(),
                                failure = protocolFailure(reason),
                                reason = reason,
                            )
                        }
                    }

                    val glossaryText = ChapterGlossaryBuilder.formatGlossary(glossaryStats.build())
                    val contextualChunk = TranslationContextChunkPlanner.withRollingContext(
                        chunk = chunk,
                        rollingContext = rolling,
                        requestedOutputTokens = requestedOutputTokens,
                        profile = chunkProfile,
                        glossary = glossaryText,
                    )
                    var admissionFailure: String? = null
                    chunk.pages.keys.forEach { pk ->
                        val p = translationRegistry[pk] ?: return@forEach
                        val running = guardedBatchUpdate(pk, "batch translation running", BatchStage.TRANSLATION) {
                            (it ?: p).apply {
                                translationStatus = StageStatus.RUNNING
                                errorMessage = null
                                updatedAt = System.currentTimeMillis()
                            }
                        }
                        if (running is ChapterTranslationStore.PatchResult.Rejected) {
                            admissionFailure = "translation admission rejected for $pk: ${running.reason}"
                        }
                        tracker?.markTranslateRunning(pk)
                        tracker?.markAiRunning(pk)
                    }
                    admissionFailure?.let { reason ->
                        val failure = protocolFailure(reason)
                        val anchor = firstUnresolved(emptySet()) ?: return ChunkCompletionOutcome.Failed(
                            failure = failure,
                            reason = reason,
                        )
                        val page = translationRegistry[anchor]
                        if (page != null) {
                            persistAiFailure(
                                pageKey = anchor,
                                page = page,
                                failure = failure,
                                retryable = false,
                                partialCandidate = false,
                                envelopeId = null,
                                missingBlockIds = emptySet(),
                            )
                        }
                        tracker?.markTranslateFailed(anchor, reason)
                        tracker?.markAiFailed(anchor, reason)
                        return ChunkCompletionOutcome.Failed(
                            anchorPageKey = anchor,
                            terminalPageKeys = setOf(anchor),
                            failure = failure,
                            reason = reason,
                        )
                    }
                    BatchTranslationDiagnostics.envelopeLifecycle(
                        phase = BatchEnvelopeLifecycle.ADMITTED,
                        pageKeys = chunk.pages.keys,
                        expectedItemCount = chunk.blockCount,
                    )
                    try {
                        val adaptiveOutcome = translateAiChunkWithAdaptiveRetry(
                            translator = ct,
                            chunk = contextualChunk,
                            requestedOutputTokens = requestedOutputTokens,
                            profile = chunkProfile,
                            label = "stream-${chunkCounter.incrementAndGet()}",
                            retryDepth = 0,
                        )
                        val orderedChunkKeys = chunk.pages.keys.sortedBy {
                            resolvedNaturalPageIndexes[it] ?: Int.MAX_VALUE
                        }
                        val reportedCompletedKeys = adaptiveOutcome.completedPageKeys
                            .filter { it in chunk.pages }
                            .toSet()
                        // The provider outcome may contain accepted blocks from a later page,
                        // but natural context and durable promotion are prefix-only. Never
                        // publish a later page across the first unresolved anchor.
                        val completedKeys = orderedChunkKeys
                            .takeWhile(reportedCompletedKeys::contains)
                            .toSet()
                        val anchor = when (adaptiveOutcome) {
                            is eu.kanade.translation.translator.AiChunkOutcome.Complete -> null
                            is eu.kanade.translation.translator.AiChunkOutcome.Paused,
                            is eu.kanade.translation.translator.AiChunkOutcome.Terminal,
                            -> firstUnresolved(completedKeys)
                        }
                        val applyKeys = if (anchor == null) {
                            completedKeys
                        } else {
                            completedKeys + anchor
                        }
                        applyAiChunkOutcomeToPages(
                            outcome = adaptiveOutcome,
                            pages = translationRegistry,
                            pageIndexes = resolvedNaturalPageIndexes,
                            pageKeys = applyKeys,
                        )
                        val orderedCompletion = orderedChunkKeys.filter { it in completedKeys }
                        val committedPages = linkedMapOf<String, PageTranslation>()
                        for (pk in orderedCompletion) {
                            val p = translationRegistry[pk] ?: continue
                            val refused = p.blocks.any { block ->
                                block.text.isNotBlank() &&
                                    TranslationResponseFaithfulness.isStructuralRefusal(block.translation)
                            }
                            if (refused) {
                                val failure = ProviderFailure(
                                    kind = ProviderFailureKind.REFUSAL,
                                    retryability = ProviderFailureRetryability.TERMINAL,
                                    safeSummary = "provider refused translation",
                                    requestId = adaptiveOutcome.envelopeId,
                                )
                                persistAiFailure(
                                    pageKey = pk,
                                    page = p,
                                    failure = failure,
                                    retryable = false,
                                    partialCandidate = false,
                                    envelopeId = adaptiveOutcome.envelopeId,
                                    missingBlockIds = emptySet(),
                                )
                                tracker?.markTranslateFailed(pk, "provider refusal")
                                tracker?.markAiFailed(pk, "provider refusal")
                                return ChunkCompletionOutcome.Failed(
                                    anchorPageKey = pk,
                                    completedPageKeys = committedPages.keys,
                                    terminalPageKeys = setOf(pk),
                                    failure = failure,
                                    reason = failure.safeSummary,
                                )
                            }
                            val status = TranslationBlockValidation.applyTo(p)
                            when (status) {
                                StageStatus.READY -> {
                                    tracker?.markTranslateDone(pk)
                                }
                                StageStatus.PARTIAL -> {
                                    val failure = protocolFailure("translation output remained partial")
                                    persistAiFailure(
                                        pageKey = pk,
                                        page = p,
                                        failure = failure,
                                        retryable = true,
                                        partialCandidate = true,
                                        envelopeId = adaptiveOutcome.envelopeId,
                                        missingBlockIds = adaptiveOutcome.missingBlockIds,
                                    )
                                    tracker?.markTranslatePaused(pk, failure.safeSummary)
                                    tracker?.markAiPaused(pk, failure.safeSummary)
                                    return ChunkCompletionOutcome.Paused(
                                        anchorPageKey = pk,
                                        completedPageKeys = committedPages.keys,
                                        retryablePageKeys = setOf(pk),
                                        failure = failure,
                                        reason = failure.safeSummary,
                                    )
                                }
                                StageStatus.FAILED -> {
                                    // Validation details may include provider/user text. Keep the
                                    // durable diagnostic provider-neutral and safe to persist.
                                    val failure = protocolFailure("translation output failed validation")
                                    persistAiFailure(
                                        pageKey = pk,
                                        page = p,
                                        failure = failure,
                                        retryable = false,
                                        partialCandidate = false,
                                        envelopeId = adaptiveOutcome.envelopeId,
                                        missingBlockIds = adaptiveOutcome.missingBlockIds,
                                    )
                                    tracker?.markTranslateFailed(pk, failure.safeSummary)
                                    tracker?.markAiFailed(pk, failure.safeSummary)
                                    return ChunkCompletionOutcome.Failed(
                                        anchorPageKey = pk,
                                        completedPageKeys = committedPages.keys,
                                        terminalPageKeys = setOf(pk),
                                        failure = failure,
                                        reason = failure.safeSummary,
                                    )
                                }
                            }
                            val persisted = guardedBatchUpdate(pk, "batch translation chunk commit", BatchStage.TRANSLATION) {
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
                            if (persisted is ChapterTranslationStore.PatchResult.Rejected) {
                                val reason = "translation commit rejected: ${persisted.reason}"
                                tracker?.markTranslateFailed(pk, reason)
                                tracker?.markAiFailed(pk, reason)
                                abortBatchCandidate(pk, "translation commit rejected: ${persisted.reason}")
                                throw BatchPersistenceRejectedException(
                                    "translation commit rejected for $pk: ${persisted.reason}",
                                )
                            }
                            if (status == StageStatus.READY || status == StageStatus.PARTIAL) {
                                tracker?.markAiSucceeded(pk)
                            }
                            if (status == StageStatus.READY) recordContextPage(pk, p)
                            committedPages[pk] = p
                            tryRender(pk)
                        }
                        // Accumulate this envelope's committed pairs into the chapter
                        // glossary and persist it.
                        var translatedPairs = 0
                        committedPages.values.forEach { page ->
                            page.blocks.forEach { b ->
                                glossaryStats.add(b.text, b.translation)
                                if (b.translation.isNotBlank()) translatedPairs++
                            }
                        }
                        val newGlossary = glossaryStats.build()
                        store.updateGlossary(newGlossary)
                        logcat(LogPriority.INFO) {
                            "TachiyomiAT batch stage2-AI chunk: translatedPairs=$translatedPairs " +
                                "glossaryEntries=${newGlossary.size} committed=${committedPages.size} " +
                                "of ${orderedCompletion.size}"
                        }
                        BatchTranslationDiagnostics.envelopeLifecycle(
                            phase = when (adaptiveOutcome) {
                                is eu.kanade.translation.translator.AiChunkOutcome.Complete ->
                                    BatchEnvelopeLifecycle.SUCCEEDED
                                is eu.kanade.translation.translator.AiChunkOutcome.Paused,
                                is eu.kanade.translation.translator.AiChunkOutcome.Terminal,
                                -> BatchEnvelopeLifecycle.FAILED
                            },
                            pageKeys = chunk.pages.keys,
                            expectedItemCount = chunk.blockCount,
                            receivedItemCount = committedPages.values.sumOf { page ->
                                page.blocks.count { block -> block.translation.isNotBlank() }
                            },
                            reason = when (adaptiveOutcome) {
                                is eu.kanade.translation.translator.AiChunkOutcome.Complete ->
                                    BatchDiagnosticReason.SUCCESS
                                is eu.kanade.translation.translator.AiChunkOutcome.Paused ->
                                    BatchDiagnosticReason.TRANSIENT_FAILURE
                                is eu.kanade.translation.translator.AiChunkOutcome.Terminal ->
                                    BatchDiagnosticReason.TERMINAL_FAILURE
                            },
                        )
                        when (adaptiveOutcome) {
                            is eu.kanade.translation.translator.AiChunkOutcome.Complete -> {
                                return ChunkCompletionOutcome.Completed(committedPages.keys)
                            }
                            is eu.kanade.translation.translator.AiChunkOutcome.Paused -> {
                                val unresolved = anchor ?: firstUnresolved(committedPages.keys)
                                if (unresolved == null) return ChunkCompletionOutcome.Completed(committedPages.keys)
                                val page = translationRegistry[unresolved]
                                if (page != null) {
                                    persistAiFailure(
                                        pageKey = unresolved,
                                        page = page,
                                        failure = adaptiveOutcome.failure,
                                        retryable = true,
                                        partialCandidate = adaptiveOutcome.partialCandidate,
                                        envelopeId = adaptiveOutcome.envelopeId,
                                        missingBlockIds = adaptiveOutcome.missingBlockIds,
                                    )
                                    tracker?.markTranslatePaused(unresolved, adaptiveOutcome.failure.safeSummary)
                                    tracker?.markAiPaused(unresolved, adaptiveOutcome.failure.safeSummary)
                                }
                                return ChunkCompletionOutcome.Paused(
                                    anchorPageKey = unresolved,
                                    completedPageKeys = committedPages.keys,
                                    retryablePageKeys = setOf(unresolved),
                                    failure = adaptiveOutcome.failure,
                                    nextEligibleRetryAtEpochMs = adaptiveOutcome.nextEligibleRetryAtEpochMs,
                                    reason = adaptiveOutcome.failure.safeSummary,
                                )
                            }
                            is eu.kanade.translation.translator.AiChunkOutcome.Terminal -> {
                                val unresolved = anchor ?: firstUnresolved(committedPages.keys)
                                if (unresolved == null) return ChunkCompletionOutcome.Completed(committedPages.keys)
                                val page = translationRegistry[unresolved]
                                if (page != null) {
                                    persistAiFailure(
                                        pageKey = unresolved,
                                        page = page,
                                        failure = adaptiveOutcome.failure,
                                        retryable = false,
                                        partialCandidate = adaptiveOutcome.acceptedBlockIds.isNotEmpty(),
                                        envelopeId = adaptiveOutcome.envelopeId,
                                        missingBlockIds = adaptiveOutcome.missingBlockIds,
                                    )
                                    tracker?.markTranslateFailed(unresolved, adaptiveOutcome.failure.safeSummary)
                                    tracker?.markAiFailed(unresolved, adaptiveOutcome.failure.safeSummary)
                                }
                                return ChunkCompletionOutcome.Failed(
                                    anchorPageKey = unresolved,
                                    completedPageKeys = committedPages.keys,
                                    terminalPageKeys = setOf(unresolved),
                                    failure = adaptiveOutcome.failure,
                                    reason = adaptiveOutcome.failure.safeSummary,
                                )
                            }
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        val failure = if (e is ProviderFailureException) {
                            e.failure
                        } else {
                            eu.kanade.translation.translator.classifyProviderFailure(e)
                        }
                        val reason = failure.safeSummary
                        val anchor = firstUnresolved(emptySet())
                        val page = anchor?.let(translationRegistry::get)
                        if (page != null) {
                            val retryable = failure.retryability != ProviderFailureRetryability.TERMINAL
                            persistAiFailure(
                                pageKey = anchor,
                                page = page,
                                failure = failure,
                                retryable = retryable,
                                partialCandidate = false,
                                envelopeId = null,
                                missingBlockIds = emptySet(),
                            )
                            if (retryable) {
                                tracker?.markTranslatePaused(anchor, reason)
                                tracker?.markAiPaused(anchor, reason)
                            } else {
                                tracker?.markTranslateFailed(anchor, reason)
                                tracker?.markAiFailed(anchor, reason)
                            }
                        }
                        logcat(LogPriority.ERROR, e) {
                            "TachiyomiAT contextual batch translate failed: envelope=${
                                BatchTranslationDiagnostics.envelopeId(chunk.pages.keys)
                            }"
                        }
                        BatchTranslationDiagnostics.envelopeLifecycle(
                            phase = BatchEnvelopeLifecycle.FAILED,
                            pageKeys = chunk.pages.keys,
                            expectedItemCount = chunk.blockCount,
                            reason = if (failure.retryability == ProviderFailureRetryability.TERMINAL) {
                                BatchDiagnosticReason.TERMINAL_FAILURE
                            } else {
                                BatchDiagnosticReason.TRANSIENT_FAILURE
                            },
                        )
                        if (anchor != null && failure.retryability != ProviderFailureRetryability.TERMINAL) {
                            return ChunkCompletionOutcome.Paused(
                                anchorPageKey = anchor,
                                retryablePageKeys = setOf(anchor),
                                failure = failure,
                                reason = reason,
                            )
                        }
                        return ChunkCompletionOutcome.Failed(
                            anchorPageKey = anchor,
                            terminalPageKeys = anchor?.let(::setOf).orEmpty(),
                            failure = failure,
                            reason = reason,
                        )
                    }
                }

                suspend fun completeChunklessPage(pk: String): ChunkCompletionOutcome {
                    val p = translationRegistry[pk] ?: return ChunkCompletionOutcome.Completed()
                    TranslationBlockValidation.applyTo(p)
                    p.translationStatus = StageStatus.READY
                    val persisted = guardedBatchUpdate(pk, "batch chunkless translation commit", BatchStage.TRANSLATION) {
                        (it ?: p).apply {
                            translationStatus = StageStatus.READY
                            translationError = null
                            if (it != null && it !== p) {
                                blocks = p.blocks.toMutableList()
                            }
                            updatedAt = System.currentTimeMillis()
                        }
                    }
                    if (persisted is ChapterTranslationStore.PatchResult.Rejected) {
                        val reason = "chunkless translation commit rejected: ${persisted.reason}"
                        tracker?.markTranslateFailed(pk, reason)
                        tracker?.markAiFailed(pk, reason)
                        abortBatchCandidate(pk, reason)
                        return ChunkCompletionOutcome.Failed(
                            anchorPageKey = pk,
                            terminalPageKeys = setOf(pk),
                            reason = reason,
                        )
                    }
                    tracker?.markTranslateDone(pk)
                    tracker?.markAiSucceeded(pk)
                    recordContextPage(pk, p)
                    tryRender(pk)
                    return ChunkCompletionOutcome.Completed(setOf(pk))
                }

                // ---- TachiyomiAT Phase 5: consolidated sequential coordinator ----
                // The batch schedule is driven by [SequentialBatchCoordinator] (one
                // serialized native lane, token-adaptive whole-page AI chunks with one
                // retained OCR-only probe, one ordered translation lane, and a per-page
                // render join). The pipeline supplies the adapter implementations of
                // [NativeLaneWorker] / [TranslatorLaneWorker] / [RenderJoinWorker] that
                // reuse the existing OCR/inpaint/persist/translate/render helpers above,
                // so the heavy Android/ONNX/HTTP logic is unchanged — only the schedule
                // is centralized. Both AI and standard translators use this schedule.
                //
                // TranslatorComputeClass drives lane routing: ML Kit (LOCAL_COMPUTE) is kept
                // inline on the native lane so its on-device inference never overlaps native
                // OCR/inpaint; remote providers (REMOTE_IO) overlap their network wait with
                // the current chunk's native inpaint after the OCR barrier has released.
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
                            if (!translationNeedsWork && !translationFailureFence(pageKey)) {
                                tryRender(pageKey)
                                recordReusableContextPage(pageKey, p)
                            }
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
                                if (!plannedTranslationNeedsWork(pageKey) && !translationFailureFence(pageKey)) {
                                    tryRender(pageKey)
                                    recordReusableContextPage(pageKey, p)
                                }
                                producedTarget = p
                                return@withNativeLane
                            }
                            if (innerGate == BatchResumeGate.INPAINT_ONLY) {
                                // OCR artifacts are planned for reuse: no decode or
                                // recognition runs here. The page's single remaining
                                // decode happens in the inpaint stage.
                                translationRegistry[pageKey] = latest ?: PageTranslation(sourceFileName = pageKey)
                                producedTarget = translationRegistry[pageKey]
                                return@withNativeLane
                            }
                            try {
                                val decoded = try {
                                    decodePageBitmapForTranslation(pageKey, streamFn)
                                } catch (deferred: LowMemoryDecodeDeferredException) {
                                    tracker?.markOcrFailed(pageKey, deferred.message ?: "Decode deferred")
                                    val deferredWrite = guardedBatchUpdate(pageKey, "batch decode deferred", BatchStage.OCR) {
                                        (it ?: PageTranslation()).apply {
                                            sourceFileName = pageKey
                                            ocrStatus = StageStatus.FAILED
                                            errorMessage = deferred.message
                                            retryCount = (it?.retryCount ?: 0) + 1
                                            attemptCount = (it?.attemptCount ?: 0) + 1
                                            updatedAt = System.currentTimeMillis()
                                        }
                                    }
                                    if (deferredWrite is ChapterTranslationStore.PatchResult.Rejected) {
                                        abortBatchCandidate(pageKey, "decode deferral rejected: ${deferredWrite.reason}")
                                    }
                                    return@withNativeLane
                                } ?: run {
                                    tracker?.markOcrFailed(pageKey, "Failed to decode page: null bitmap")
                                    val failedWrite = guardedBatchUpdate(pageKey, "batch decode failed", BatchStage.OCR) {
                                        (it ?: PageTranslation()).apply {
                                            sourceFileName = pageKey
                                            ocrStatus = StageStatus.FAILED
                                            errorMessage = "Failed to decode page: null bitmap"
                                            retryCount = (it?.retryCount ?: 0) + 1
                                            attemptCount = (it?.attemptCount ?: 0) + 1
                                            updatedAt = System.currentTimeMillis()
                                        }
                                    }
                                    if (failedWrite is ChapterTranslationStore.PatchResult.Rejected) {
                                        abortBatchCandidate(pageKey, "decode failure rejected: ${failedWrite.reason}")
                                    }
                                    return@withNativeLane
                                }
                                producedDecoded = decoded
                                // Decode succeeds: the bitmap is owned by this handle until
                                // [releaseNativeResources]. OCR persistence runs here (analyzePage
                                // persists blocks + ocrStatus BEFORE inpaint), closing the OCR crash
                                // window first and producing the immutable work item offered to the
                                // translation lane below.
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
                                producedTarget = translationRegistry[pageKey]
                            } catch (deferred: LowMemoryRecognitionDeferredException) {
                                val t = translationRegistry[pageKey]
                                if (t != null) {
                                    t.inpaintStatus = StageStatus.FAILED
                                    t.errorMessage = deferred.message
                                }
                                tracker?.markInpaintFailed(pageKey, deferred.message ?: "Recognition deferred")
                            } catch (rejected: BatchPersistenceRejectedException) {
                                tracker?.markOcrFailed(pageKey, rejected.message ?: "OCR persistence rejected")
                                abortBatchCandidate(pageKey, rejected.message ?: "OCR persistence rejected")
                            }
                        }
                        val target = producedTarget ?: run {
                            releaseDecodedPage(producedDecoded)
                            return null
                        }

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
                            nativeHandoff = producedDecoded,
                        )
                    }

                    override suspend fun runInpaintStage(pageKey: String) {
                        runInpaintStage(pageKey, null)
                    }

                    override suspend fun runInpaintStage(pageKey: String, nativeHandoff: Any?) {
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
                        try {
                            withNativeLane(
                                timeoutMs = SINGLE_PAGE_TIMEOUT_MS,
                                chapterId = chapter.id,
                                chapterName = chapter.name,
                                pageKey = pageKey,
                                onTimeout = { markPageTimedOut(manga, chapter, source, pageKey) },
                            ) {
                                val handedOffDecoded = nativeHandoff as? DecodedPage
                                var decoded: DecodedPage? = handedOffDecoded
                                try {
                                    tracker?.markInpaintRunning(pageKey)
                                    if (decoded == null) {
                                        val streamFn = streamsByKey[pageKey] ?: return@withNativeLane
                                        decoded = decodePageBitmapForTranslation(pageKey, streamFn)
                                    }
                                    if (decoded == null) {
                                        tracker?.markInpaintFailed(pageKey, "Null bitmap for inpaint")
                                        return@withNativeLane
                                    }
                                    preflightInpaintGate(decoded.bitmap, pageKey)
                                    inpaintPage(
                                        fileName = pageKey,
                                        bitmap = decoded.bitmap,
                                        pageTranslation = target,
                                        batchFingerprint = expectedBatchFingerprints.inpaint,
                                        guardedWrite = { description, update ->
                                            val result = guardedBatchUpdate(pageKey, description, BatchStage.INPAINT, update)
                                            if (result is ChapterTranslationStore.PatchResult.Rejected) {
                                                throw BatchPersistenceRejectedException(
                                                    "$description rejected for $pageKey: ${result.reason}",
                                                )
                                            }
                                            result
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
                                    if (handedOffDecoded == null) releaseDecodedPage(decoded)
                                }
                            }
                        } catch (rejected: BatchPersistenceRejectedException) {
                            tracker?.markInpaintFailed(pageKey, rejected.message ?: "Inpaint persistence rejected")
                            abortBatchCandidate(pageKey, rejected.message ?: "Inpaint persistence rejected")
                            return
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
                                tracker?.markInpaintFailed(pageKey, "Cleaned image publication rejected")
                                abortBatchCandidate(pageKey, "cleaned image publication rejected")
                                return
                            }
                        } else {
                            val terminal = guardedBatchUpdate(pageKey, "batch inpaint terminal state", BatchStage.INPAINT) {
                                (it ?: target).apply {
                                    inpaintStatus = target.inpaintStatus
                                    errorMessage = target.errorMessage
                                    updatedAt = System.currentTimeMillis()
                                }
                            }
                            if (terminal is ChapterTranslationStore.PatchResult.Rejected) {
                                abortBatchCandidate(pageKey, "inpaint terminal write rejected: ${terminal.reason}")
                                return
                            }
                        }
                        holdCleaned(pageKey, target.cleanedBitmap)
                        target.cleanedBitmap = null
                    }

                    override fun releaseNativeHandoff(ref: OcrReadyPageRef) {
                        val decoded = ref.nativeHandoff as? DecodedPage ?: return
                        releaseDecodedPage(decoded)
                    }

                    private fun releaseDecodedPage(decoded: DecodedPage?) {
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

                // The translator lane owns the streaming AI chunk planner (contextual path) or
                // performs per-page translation (standard path). Either way it is a SINGLE
                // serialized lane so only one provider request is in flight at a time. AI pages
                // are admitted into the token planner during OCR, but planner emissions remain
                // buffered until the coordinator's chunk barrier calls completeChunk.
                val translatorWorker = object : TranslatorLaneWorker {
                    override val usesChunkAdmission: Boolean = isAi

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

                    private val pendingAiEmissions = mutableListOf<StreamingChunkPlanner.Emission>()
                    private val handledRejectedPages = mutableSetOf<String>()
                    private val contextBlockedPages = linkedSetOf<String>()
                    private var lastAdmission: ChunkAdmission = ChunkAdmission.ACCEPT
                    private var standardOutcome: ChunkCompletionOutcome? = null

                    override suspend fun admit(ref: OcrReadyPageRef): ChunkAdmission {
                        lastAdmission = ChunkAdmission.ACCEPT
                        translate(ref)
                        return lastAdmission
                    }

                    override suspend fun translateOutcome(ref: OcrReadyPageRef): ChunkCompletionOutcome {
                        standardOutcome = null
                        translate(ref)
                        return standardOutcome ?: ChunkCompletionOutcome.Completed(setOf(ref.pageKey))
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
                        val priorPageBlocksStandardTranslation = !isAi &&
                            plannedTranslation?.reason == eu.kanade.translation.model.StageReasonCode.PRIOR_PAGE_INCOMPLETE
                        val completedAiPageAfterPriorGap = isAi &&
                            plannedTranslation?.decision == eu.kanade.translation.model.StageDecision.WAIT_FOR_DEPENDENCY &&
                            plannedTranslation?.reason == eu.kanade.translation.model.StageReasonCode.PRIOR_PAGE_INCOMPLETE &&
                            p.translationStatus in setOf(StageStatus.READY, StageStatus.SKIPPED) &&
                            (
                                expectedBatchFingerprints.translation == null ||
                                    p.translationFingerprint == expectedBatchFingerprints.translation
                                )
                        val retryableTranslation = plannedTranslation?.decision ==
                            eu.kanade.translation.model.StageDecision.FAILED_RETRYABLE
                        val shouldSkipTranslation = plannedTranslation?.decision ==
                            eu.kanade.translation.model.StageDecision.REUSE ||
                            plannedTranslation?.decision == eu.kanade.translation.model.StageDecision.TERMINAL_COMPLETE ||
                            plannedTranslation?.decision == eu.kanade.translation.model.StageDecision.FAILED ||
                            plannedTranslation?.decision == eu.kanade.translation.model.StageDecision.FAILED_TERMINAL ||
                            retryableTranslation &&
                            plannedTranslation?.retryEligible != true ||
                            completedAiPageAfterPriorGap ||
                            plannedTranslation?.decision == eu.kanade.translation.model.StageDecision.WAIT_FOR_DEPENDENCY &&
                            (
                                priorPageBlocksStandardTranslation ||
                                    !dependencyReadyAfterNative
                                )
                        if (shouldSkipTranslation) {
                            // Native/layout-only resume, an ordered context wait,
                            // or a failed page never invokes the provider. The
                            // render join still receives its branch completion.
                            when {
                                plannedTranslation?.decision in setOf(
                                    eu.kanade.translation.model.StageDecision.FAILED,
                                    eu.kanade.translation.model.StageDecision.FAILED_TERMINAL,
                                ) ||
                                    p.translationStatus == StageStatus.FAILED &&
                                    store.durableFailure(pageKey)?.status != ArtifactStageStatus.FAILED_RETRYABLE -> tracker?.markAiFailed(
                                    pageKey,
                                    p.activeError ?: "Translation stage failed",
                                )
                                p.translationStatus == StageStatus.READY ||
                                    p.translationStatus == StageStatus.PARTIAL ||
                                    p.translationStatus == StageStatus.SKIPPED -> tracker?.markAiSucceeded(pageKey)
                            }
                            if (isAi &&
                                plannedTranslation?.decision !in setOf(
                                    eu.kanade.translation.model.StageDecision.FAILED_RETRYABLE,
                                    eu.kanade.translation.model.StageDecision.FAILED_TERMINAL,
                                    eu.kanade.translation.model.StageDecision.FAILED,
                                )
                            ) {
                                recordContextPage(
                                    pageKey,
                                    p,
                                    terminalFailure = false,
                                )
                            }
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
                            val textless = guardedBatchUpdate(pageKey, "batch textless translation commit", BatchStage.TRANSLATION) { p }
                            if (textless is ChapterTranslationStore.PatchResult.Rejected) {
                                abortBatchCandidate(pageKey, "textless commit rejected: ${textless.reason}")
                                return
                            }
                            tracker?.markTranslateSkipped(pageKey)
                            tracker?.markAiSucceeded(pageKey)
                            tracker?.markRenderSkipped(pageKey)
                            if (p.inpaintStatus == StageStatus.SKIPPED || p.inpaintStatus == StageStatus.READY) {
                                recycleHeld(pageKey)
                                translationRegistry.remove(pageKey)
                                releaseBatchLease(pageKey)
                            }
                            return
                        }
                        if (isAi && planner != null) {
                            if (contextFrontier.blocksLaterAi(pageKey)) {
                                val gapIndex = contextFrontier.gapIndex
                                val reason = "blocked by non-textless terminal context gap at index $gapIndex"
                                // Keep this page as the first unresolved anchor.
                                // The coordinator will process the planner's
                                // rejection at the chunk barrier, after the
                                // already-admitted prefix has settled.
                                contextBlockedPages += pageKey
                                lastAdmission = ChunkAdmission.PROBE
                                tracker?.markAiPaused(pageKey, reason)
                                tracker?.markTranslatePaused(pageKey, reason)
                                return
                            }
                            // Contextual path: accept this page into the streaming planner,
                            // but retain every emission until the coordinator releases the
                            // current OCR barrier. Buffering is not translation completion.
                            val emission = planner.accept(pageKey, p)
                            planner.rejectedPages[pageKey]?.let { reason ->
                                tracker?.markAiFailed(pageKey, reason)
                            } ?: run {
                                tracker?.markAiBuffered(pageKey)
                            }
                            emission?.let { emission ->
                                pendingAiEmissions += emission
                                if (emission.chunk != null) {
                                    // The page that caused the prior whole-page envelope to
                                    // flush is the one allowed OCR-only probe. It has already
                                    // been admitted to the planner, but cannot enter any later
                                    // stage until the coordinator starts the next chunk.
                                    lastAdmission = ChunkAdmission.PROBE
                                }
                            }
                        } else {
                            // Standard (per-page) path: translate, validate, persist, render.
                            var succeeded = false
                            var failedOutcome: ChunkCompletionOutcome? = null
                            try {
                                tracker?.markAiRunning(pageKey)
                                tracker?.markTranslateRunning(pageKey)
                                textTranslator.translatePage(pageKey, p)
                                TranslationBlockValidation.applyTo(p)
                                val s = p.translationStatus
                                when (s) {
                                    StageStatus.READY -> tracker?.markTranslateDone(pageKey)
                                    StageStatus.PARTIAL -> {
                                        val failure = ProviderFailure(
                                            kind = ProviderFailureKind.PROTOCOL,
                                            retryability = ProviderFailureRetryability.PAUSE,
                                            safeSummary = "translation output is partial",
                                        )
                                        persistAiFailure(
                                            pageKey = pageKey,
                                            page = p,
                                            failure = failure,
                                            retryable = true,
                                            partialCandidate = true,
                                            envelopeId = null,
                                            missingBlockIds = emptySet(),
                                        )
                                        tracker?.markTranslatePaused(pageKey, failure.safeSummary)
                                        tracker?.markAiPaused(pageKey, failure.safeSummary)
                                        failedOutcome = ChunkCompletionOutcome.Paused(
                                            anchorPageKey = pageKey,
                                            retryablePageKeys = setOf(pageKey),
                                            failure = failure,
                                            reason = failure.safeSummary,
                                        )
                                    }
                                    else -> {
                                        val failure = ProviderFailure(
                                            kind = ProviderFailureKind.PROTOCOL,
                                            retryability = ProviderFailureRetryability.TERMINAL,
                                            safeSummary = "translation returned an unknown state",
                                        )
                                        persistAiFailure(
                                            pageKey = pageKey,
                                            page = p,
                                            failure = failure,
                                            retryable = false,
                                            partialCandidate = false,
                                            envelopeId = null,
                                            missingBlockIds = emptySet(),
                                        )
                                        tracker?.markTranslateFailed(pageKey, failure.safeSummary)
                                        tracker?.markAiFailed(pageKey, failure.safeSummary)
                                        failedOutcome = ChunkCompletionOutcome.Failed(
                                            anchorPageKey = pageKey,
                                            terminalPageKeys = setOf(pageKey),
                                            failure = failure,
                                            reason = failure.safeSummary,
                                        )
                                    }
                                }
                                succeeded = s == StageStatus.READY
                                if (succeeded) tracker?.markAiSucceeded(pageKey)
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                val failure = if (e is ProviderFailureException) e.failure else classifyProviderFailure(e)
                                val retryable = failure.retryability != ProviderFailureRetryability.TERMINAL
                                persistAiFailure(
                                    pageKey = pageKey,
                                    page = p,
                                    failure = failure,
                                    retryable = retryable,
                                    partialCandidate = false,
                                    envelopeId = null,
                                    missingBlockIds = emptySet(),
                                )
                                if (retryable) {
                                    tracker?.markTranslatePaused(pageKey, failure.safeSummary)
                                    tracker?.markAiPaused(pageKey, failure.safeSummary)
                                    failedOutcome = ChunkCompletionOutcome.Paused(
                                        anchorPageKey = pageKey,
                                        retryablePageKeys = setOf(pageKey),
                                        failure = failure,
                                        reason = failure.safeSummary,
                                    )
                                } else {
                                    tracker?.markTranslateFailed(pageKey, failure.safeSummary)
                                    tracker?.markAiFailed(pageKey, failure.safeSummary)
                                    failedOutcome = ChunkCompletionOutcome.Failed(
                                        anchorPageKey = pageKey,
                                        terminalPageKeys = setOf(pageKey),
                                        failure = failure,
                                        reason = failure.safeSummary,
                                    )
                                }
                                logcat(LogPriority.ERROR, e) { "TachiyomiAT batch translate failed: $pageKey" }
                            }
                            if (failedOutcome != null) {
                                standardOutcome = failedOutcome
                            } else if (succeeded) {
                                val persisted = guardedBatchUpdate(pageKey, "batch translation final commit", BatchStage.TRANSLATION) {
                                    (it ?: p).apply {
                                        translationStatus = p.translationStatus
                                        translationError = null
                                        blocks = p.blocks.toMutableList()
                                        updatedAt = System.currentTimeMillis()
                                    }
                                }
                                if (persisted is ChapterTranslationStore.PatchResult.Rejected) {
                                    val reason = "translation commit rejected: ${persisted.reason}"
                                    tracker?.markTranslateFailed(pageKey, reason)
                                    tracker?.markAiFailed(pageKey, reason)
                                    standardOutcome = ChunkCompletionOutcome.Failed(
                                        anchorPageKey = pageKey,
                                        terminalPageKeys = setOf(pageKey),
                                        reason = reason,
                                    )
                                } else {
                                    standardOutcome = ChunkCompletionOutcome.Completed(setOf(pageKey))
                                }
                            }
                        }
                    }

                    private suspend fun processAiEmission(
                        emission: StreamingChunkPlanner.Emission,
                    ): ChunkCompletionOutcome {
                        if (emission.chunk != null) {
                            return translateChunkAi(
                                emission.chunk,
                                rollingContext,
                            )
                        } else {
                            val completed = linkedSetOf<String>()
                            for (pk in emission.completedPages.sortedBy {
                                resolvedNaturalPageIndexes[it] ?: Int.MAX_VALUE
                            }) {
                                when (val result = completeChunklessPage(pk)) {
                                    is ChunkCompletionOutcome.Completed -> completed += result.completedPageKeys
                                    is ChunkCompletionOutcome.Paused -> return result.copy(
                                        completedPageKeys = completed + result.completedPageKeys,
                                    )
                                    is ChunkCompletionOutcome.Failed -> return result.copy(
                                        completedPageKeys = completed + result.completedPageKeys,
                                    )
                                }
                            }
                            return ChunkCompletionOutcome.Completed(completed)
                        }
                    }

                    override suspend fun completeChunkOutcome(finalChunk: Boolean): ChunkCompletionOutcome {
                        val planner = planner ?: return ChunkCompletionOutcome.Completed()
                        fun ChunkCompletionOutcome.completedKeys(): Set<String> = when (this) {
                            is ChunkCompletionOutcome.Completed -> completedPageKeys
                            is ChunkCompletionOutcome.Paused -> completedPageKeys
                            is ChunkCompletionOutcome.Failed -> completedPageKeys
                        }
                        var completed = linkedSetOf<String>()
                        var result: ChunkCompletionOutcome = ChunkCompletionOutcome.Completed()
                        try {
                            pendingAiEmissions.toList().forEach { emission ->
                                if (result !is ChunkCompletionOutcome.Completed) return@forEach
                                val processed = processAiEmission(emission)
                                result = processed
                                completed += processed.completedKeys()
                            }
                        } finally {
                            pendingAiEmissions.clear()
                        }

                        if (result is ChunkCompletionOutcome.Completed && finalChunk) {
                            val flush = planner.flushRemaining()
                            if (flush.finalChunk != null) {
                                val processed = processAiEmission(
                                    StreamingChunkPlanner.Emission(
                                        chunk = flush.finalChunk,
                                        completedPages = flush.completedPages,
                                    ),
                                )
                                result = processed
                                completed += processed.completedKeys()
                            } else {
                                val completedFlush = linkedSetOf<String>()
                                flush.completedPages
                                    .sortedBy { pk -> resolvedNaturalPageIndexes[pk] ?: Int.MAX_VALUE }
                                    .forEach { pk ->
                                        if (result !is ChunkCompletionOutcome.Completed) return@forEach
                                        val processed = completeChunklessPage(pk)
                                        result = processed
                                        if (processed is ChunkCompletionOutcome.Completed) {
                                            completedFlush += processed.completedPageKeys
                                        }
                                    }
                                completed += completedFlush
                            }
                        }

                        if (result is ChunkCompletionOutcome.Completed && contextBlockedPages.isNotEmpty()) {
                            val blocked = contextBlockedPages.minByOrNull {
                                resolvedNaturalPageIndexes[it] ?: Int.MAX_VALUE
                            }!!
                            contextBlockedPages.clear()
                            result = ChunkCompletionOutcome.Failed(
                                anchorPageKey = blocked,
                                completedPageKeys = completed,
                                terminalPageKeys = emptySet(),
                                reason = "blocked by non-textless terminal context gap",
                            )
                        }

                        if (result is ChunkCompletionOutcome.Completed) {
                            planner.rejectedPages
                                .filterKeys { it !in handledRejectedPages }
                                .forEach { (pk, reason) ->
                                    handledRejectedPages += pk
                                    val rejectedPage = translationRegistry[pk] ?: return@forEach
                                    val failure = ProviderFailure(
                                        kind = ProviderFailureKind.PROTOCOL,
                                        retryability = ProviderFailureRetryability.TERMINAL,
                                        safeSummary = reason,
                                        requestId = BatchTranslationDiagnostics.envelopeId(setOf(pk)),
                                    )
                                    persistAiFailure(
                                        pageKey = pk,
                                        page = rejectedPage,
                                        failure = failure,
                                        retryable = false,
                                        partialCandidate = false,
                                        envelopeId = failure.requestId,
                                        missingBlockIds = emptySet(),
                                    )
                                    tracker?.markTranslateFailed(pk, reason)
                                    tracker?.markAiFailed(pk, reason)
                                    result = ChunkCompletionOutcome.Failed(
                                        anchorPageKey = pk,
                                        completedPageKeys = completed,
                                        terminalPageKeys = setOf(pk),
                                        failure = failure,
                                        reason = reason,
                                    )
                                }
                        }
                        val finalResult = result
                        return when (finalResult) {
                            is ChunkCompletionOutcome.Completed -> finalResult.copy(completedPageKeys = completed)
                            is ChunkCompletionOutcome.Paused -> finalResult.copy(completedPageKeys = completed + finalResult.completedPageKeys)
                            is ChunkCompletionOutcome.Failed -> finalResult.copy(completedPageKeys = completed + finalResult.completedPageKeys)
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

                val coordinator = SequentialBatchCoordinator(
                    nativeWorker = nativeWorker,
                    translatorWorker = translatorWorker,
                    renderJoin = renderJoin,
                )

                var pass1Outcome: BatchPass1Outcome? = null
                try {
                    coroutineScope {
                        val orderedPages = orderedStreams.mapIndexed { index, (pageKey, _) ->
                            pageKey to (resolvedNaturalPageIndexes[pageKey] ?: index)
                        }

                        pass1Outcome = coordinator.runPass1(orderedPages, computeClass)
                    }
                } finally {
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
                val stoppedOutcome = pass1Outcome
                if (stoppedOutcome != null && stoppedOutcome.status != BatchPass1Status.COMPLETED) {
                    val reconciliation = BatchProgressReconciler.reconcile(
                        pageMap = store.state.value,
                        orderedKeys = orderedStreams.map { it.first },
                        activeGeneration = store.currentGeneration,
                        pauseOutcome = stoppedOutcome,
                    )
                    store.flush()
                    if (stoppedOutcome.status == BatchPass1Status.PAUSED) {
                        tracker?.pause(stoppedOutcome, orderedStreams.size)
                    } else {
                        tracker?.finish(reconciliation)
                    }
                    logcat(LogPriority.WARN) {
                        "TachiyomiAT batch stopped before tail reconciliation chapter=${chapter.name} " +
                            "status=${stoppedOutcome.status} anchor=${stoppedOutcome.anchorPageKey?.let(ShortHash::hash)}"
                    }
                    store.releaseAllPageLeases(PageWriteOrigin.BATCH)
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
                    guardedBatchUpdate(pageKey, "batch stranded page", null) { existing ->
                        (existing ?: PageTranslation(sourceFileName = pageKey)).apply {
                            ocrStatus = StageStatus.FAILED
                            errorMessage = reason
                            updatedAt = System.currentTimeMillis()
                        }
                    }
                }
                store.flush()
                tracker?.finish(reconciliation)
                logcat(LogPriority.INFO) {
                    "TachiyomiAT batch complete chapter=${chapter.name} pages=${orderedStreams.size} outcome=${reconciliation.chapterStatus}"
                }
                store.releaseAllPageLeases(PageWriteOrigin.BATCH)
            } finally {
                // Cancellation, an unexpected worker exception, or a provider
                // failure must not strand a BATCH lease for the next run.
                batchWriteIdentities.keys.toList().forEach { pageKey ->
                    if (pageKey in durableFailurePageKeys) {
                        store.releasePageStageLease(pageKey, PageWriteOrigin.BATCH)
                    } else {
                        store.cancelPageStageWork(pageKey, PageWriteOrigin.BATCH)
                    }
                }
                batchWriteIdentities.clear()
                store.releaseAllPageLeases(PageWriteOrigin.BATCH)
                withContext(NonCancellable) {
                    store.flush()
                }
                store.reconcileArtifactRetention()
                onBatchClosed?.invoke(manga, chapter, source, store)
            }
        }
    }

    private suspend fun markBatchTranslationFailed(
        store: ChapterTranslationStore,
        pageKey: String,
        pageTranslation: PageTranslation,
        reason: String,
        expectedPrecondition: ChapterTranslationStore.PatchPrecondition,
    ): ChapterTranslationStore.PatchResult {
        pageTranslation.translationStatus = StageStatus.FAILED
        pageTranslation.errorMessage = reason
        // Translate is typically the first terminal stage, so it owns the attempt charge.
        // recordAttemptFailure is idempotent if a prior stage already failed.
        pageTranslation.recordAttemptFailure()
        return store.updatePageGuarded(pageKey, expectedPrecondition, "batch translation failure") {
            (it ?: pageTranslation).apply {
                translationStatus = StageStatus.FAILED
                errorMessage = reason
                attemptCount = pageTranslation.attemptCount
                retryCount = pageTranslation.retryCount
                updatedAt = System.currentTimeMillis()
            }
        }
    }

    private class BatchPersistenceRejectedException(message: String) : IllegalStateException(message)

    private data class BatchWriteIdentity(
        val generation: Long,
        var pageVersion: Long,
        val leaseToken: Long,
        var candidateGenerationId: String?,
        var dependencyFingerprint: String?,
        var artifactPageVersion: Long?,
    )

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
        // Reader-ad-hoc output is displayable; a later batch reuses it when
        // its fingerprints still match.
        pageTranslation.translationOrigin = PageWriteOrigin.READER_ADHOC.name
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
                // Recent translated pairs give on-demand single-page translation the
                // same voice/speaker continuity the batch path gets.
                val recentPairs = store.translatedPairs()
                    .mapNotNull { (src, tgt) ->
                        val t = tgt.trim()
                        if (t.isBlank() || t == src.trim()) null else "$src => $t"
                    }
                    .takeLast(TranslationContextChunkPlanner.MAX_ROLLING_PAIRS)
                    .joinToString("\n")
                val chunk = TranslationContextChunkPlanner.withRollingContext(
                    chunk = baseChunk,
                    rollingContext = recentPairs,
                    requestedOutputTokens = requestedOutputTokens,
                    profile = singlePageProfile,
                    glossary = glossaryText,
                )
                ct.translateContextual(chunk)
            } else {
                activeTranslator.translatePage(pageKey, targetPage)
            }
        }

        try {
            coroutineContext.ensureActive()

            if (pageTranslation.blocks.isNotEmpty()) {
                val nonEmptyBlocks = pageTranslation.blocks.count { it.text.isNotBlank() }
                logcat(LogPriority.INFO) {
                    "TachiyomiAT translate step START: pageHash=${ShortHash.hash(pageKey)} " +
                        "translator=${activeTranslator::class.simpleName} " +
                        "blocks=${pageTranslation.blocks.size} nonEmptyText=$nonEmptyBlocks"
                }
                BatchTranslationDiagnostics.stageDecision(
                    stage = BatchDiagnosticStage.TRANSLATION,
                    pageKey = pageKey,
                    decision = BatchDiagnosticDecision.EXECUTE,
                    reason = BatchDiagnosticReason.REFERENCE_READY,
                    itemCount = pageTranslation.blocks.size,
                )
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
                        "TachiyomiAT translate step DONE: pageHash=${ShortHash.hash(pageKey)} " +
                            "translated=$translatedCount/${pageTranslation.blocks.size} " +
                            "status=${pageTranslation.translationStatus}" +
                            (if (singlePageRetry > 0) " partialRetries=$singlePageRetry" else "")
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
                val hasCleanedBitmap = pageTranslation.cleanedBitmap != null
                val hasCleanedOnDisk = pageTranslation.cleanedImageName != null && pageTranslation.inpaintStatus == StageStatus.READY
                if (hasCleanedBitmap || hasCleanedOnDisk) {
                    val cleanedBitmap = pageTranslation.cleanedBitmap
                    try {
                        pageTranslation.renderStatus = StageStatus.RUNNING
                        stageListener?.onStageEntered(pageKey, TranslationStageEvent.RENDERING)
                        if (cleanedBitmap != null) {
                            RenderColorEstimator.recomputeFor(cleanedBitmap, pageTranslation.blocks)
                        }
                        pageTranslation.renderStatus = StageStatus.READY
                        pageTranslation.updatedAt = System.currentTimeMillis()
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        pageTranslation.renderStatus = StageStatus.FAILED
                        pageTranslation.recordAttemptFailure()
                        pageTranslation.errorMessage = e.message
                        logcat(LogPriority.ERROR, e) { "Failed to render text for single page $pageKey" }
                    } finally {
                        if (cleanedBitmap != null) {
                            try {
                                cleanedBitmap.recycle()
                            } catch (_: Exception) {}
                        }
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
            AiTranslatorKind.GEMINI -> GeminiTranslator(
                fromLang,
                toLang,
                apiKey,
                model,
                maxOutputTokens,
                temperature,
                translationPreferences.translationGeminiThinkingMode().get(),
            )
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
                "OCR persistence rejected for $fileName: ${ocrPatch.reason}",
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
    ): BatchExpectedFingerprints {
        val translationInput = currentTranslatorSignature.toString()
        return BatchExpectedFingerprints(
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
                translationInput,
                fromLang.name,
                toLang.name,
            ),
            layout = StageFingerprints.configuration(
                ArtifactStage.LAYOUT,
                currentReadingOrder.name,
            ),
        )
    }

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
