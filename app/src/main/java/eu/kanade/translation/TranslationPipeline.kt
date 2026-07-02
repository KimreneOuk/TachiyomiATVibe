package eu.kanade.translation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import coil3.imageLoader
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.translation.data.TranslationProvider
import eu.kanade.translation.inpainting.InpaintingMode
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.RenderQuality
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.hasCurrentInpaintResult
import eu.kanade.translation.model.hasCurrentInpaintMask
import eu.kanade.translation.model.hasRecognizedTranslation
import eu.kanade.translation.model.hasRenderedResult
import eu.kanade.translation.model.prepareForcedRetry
import eu.kanade.translation.model.recordAttemptFailure
import eu.kanade.translation.model.resetAttemptCharge
import eu.kanade.translation.rendering.PageTextRenderer
import eu.kanade.translation.rendering.RenderColorEstimator
import eu.kanade.translation.ocr.OcrModelCatalog
import eu.kanade.translation.ocr.TextRecognizerLanguage
import eu.kanade.translation.recognition.PageRecognitionEngine
import eu.kanade.translation.recognition.RoiPageRecognitionEngine
import eu.kanade.translation.translator.AiTranslationRetryPlanner
import eu.kanade.translation.translator.ChapterGlossaryBuilder
import eu.kanade.translation.translator.ContextualTextTranslator
import eu.kanade.translation.translator.LmStudioTranslator
import eu.kanade.translation.translator.TextTranslator
import eu.kanade.translation.translator.TextTranslatorLanguage
import eu.kanade.translation.translator.TranslationContextChunkPlanner
import eu.kanade.translation.translator.TranslationContextChunk
import eu.kanade.translation.translator.StreamingChunkPlanner
import eu.kanade.translation.translator.TranslationEngineBuilder
import eu.kanade.translation.translator.TranslationBlockValidation
import eu.kanade.translation.util.ShortHash
import eu.kanade.translation.util.TranslationMemoryBudget
import eu.kanade.translation.util.TranslationMemoryBudget.DecodeDecision
import eu.kanade.translation.util.TranslationMemoryBudget.DecodeDecisionKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import mihon.core.archive.archiveReader
import mihon.core.archive.ArchiveReader
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchUI
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.translation.OcrModel
import tachiyomi.domain.translation.TranslationEngineCategory
import tachiyomi.domain.translation.TranslationPreferences
import tachiyomi.domain.translation.pools.BitmapPool
import tachiyomi.i18n.at.ATMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import eu.kanade.translation.batch.BatchProgressReconciler
import eu.kanade.translation.batch.BatchOomPolicy
import eu.kanade.translation.batch.TranslationBatchProgressTracker
import eu.kanade.translation.scheduling.TranslationStreamRegistry
import eu.kanade.translation.scheduling.TranslationExecutor

class TranslationPipeline(
    private val context: Context,
    private val provider: TranslationProvider,
    private val downloadProvider: DownloadProvider = Injekt.get(),
    private val translationPreferences: TranslationPreferences = Injekt.get(),
    private val streamRegistry: TranslationStreamRegistry = Injekt.get(),
) : TranslationExecutor {

    companion object {
        /**
         * Maximum wall-clock time a single page may hold the sole
         * [translatorPermit] during [translateSinglePage]. Bounds the damage of
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
         * .cleaned.png is already durable at inpaint) and reload on render —
         * today's behavior. Keeps peak held memory provable against the ceiling
         * regardless of page or chunk size.
         */
        const val HELD_BITMAP_BYTE_CEILING = 48L * 1024L * 1024L
    }

    /** Per-page resume decision in the 3-lane batch pipeline (Lane A). Lives at
     *  class scope because Kotlin forbids local enum classes. */
    private enum class BatchResumeGate { SKIP_ALL, INPAINT_ONLY, FULL }

    /**
     * TachiyomiAT: serializes access to the shared translation engines
     * (textTranslator + recognitionEngine). Both the single-page path
     * ([translateSinglePage]) and the batch path ([translateChapter]) acquire
     * this permit, so:
     *  - only one page's bitmap/tensor set is alive at a time (fixes the
     *    ~20-page OOM), and
     *  - a language-change rebuild or stop()/close() in one path cannot run
     *    concurrently with an in-flight translate() in the other path (fixes
     *    the "Translator has been closed" IllegalStateException).
     */
    private val translatorPermit = Semaphore(1)

    /**
     * pageKeys currently mid-flight in [translateSinglePage]. Guards against
     * the same page being queued behind itself (e.g. auto-mode re-enqueue on
     * scroll, or a user double-tapping the per-page button). Thread-safe
     * because [withLeakProofPermit] watchdog and [closeEngines] touch this
     * outside [translatorPermit].
     */
    private val inFlightPageKeys = ConcurrentHashMap.newKeySet<String>()

    /**
     * TachiyomiAT: independent scope for the permit watchdog. It uses a
     * [SupervisorJob] on purpose: a child launched here is NOT cancelled when
     * the translation coroutine that owns the permit is cancelled/torn down.
     * That is the whole point — if the worker is stuck inside uncancellable
     * native JNI code (ONNX detect/recognize) or [runBlocking] HTTP code,
     * coroutine cancellation is queued but never delivered, so the standard
     * [withPermit] finally never runs and the singleton [translatorPermit] is
     * leaked forever, deadlocking ALL translation (auto + manual) for the rest
     * of the process. This watchdog fires a real wall-clock deadline that runs
     * independently of the hung coroutine and force-releases the permit.
     */
    private val permitWatchdogScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Listener notified by [withLeakProofPermit] when a page overruns its
     * deadline, after the permit has been force-released. Wired by
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

    /**
     * TachiyomiAT: leak-proof equivalent of `translatorPermit.withPermit { }`.
     *
     * Guarantees [permit] is released within [timeoutMs] of acquisition even
     * when [block] enters uncancellable native/HTTP code that the coroutine
     * machinery can't interrupt. Achieves this with an independent watchdog
     * coroutine (on [permitWatchdogScope], so it survives cancellation of the
     * calling coroutine) that fires the deadline on a wall-clock [delay] and
     * force-releases. Release is guarded by an [AtomicBoolean] so it happens
     * exactly once whether the watchdog or the normal finally wins the race.
     *
     * On timeout, [onTimeout] runs (on the watchdog dispatcher) before
     * release so the page is marked FAILED and the dead job is evicted; the
     * still-hung native coroutine is abandoned to finish (or not) on its own —
     * it no longer holds the permit, so other pages can proceed.
     *
     * [onForceRelease] runs on the watchdog path right before the permit is
     * freed. It exists so callers can clear bookkeeping that their `block`
     * would otherwise only clear in its own `finally` — and that `finally`
     * never runs while the worker is stuck in uncancellable native code. The
     * canonical case is [inFlightPageKeys]: without clearing it here, a
     * force-released page's key stays in the set forever, so the dedup gate
     * (`if (!inFlightPageKeys.add(pageKey)) return`) silently drops every
     * retry of that page for the rest of the process — the "pipeline stops
     * working entirely" symptom. Clearing it BEFORE the permit is released
     * means the next request that acquires the permit sees a clean key set
     * and can re-translate the page.
     */
    private suspend fun <T> withLeakProofPermit(
        permit: Semaphore,
        timeoutMs: Long,
        chapterId: Long?,
        pageKey: String,
        onTimeout: suspend () -> Unit,
        onForceRelease: () -> Unit = {},
        block: suspend () -> T,
    ): T {
        permit.acquire()
        val released = AtomicBoolean(false)
        fun releaseOnce() {
            if (released.compareAndSet(false, true)) {
                permit.release()
            }
        }
        val watchdog = permitWatchdogScope.launch {
            delay(timeoutMs)
            // Deadline fired while block was still holding the permit — the
            // worker is almost certainly stuck in uncancellable code. Run the
            // timeout callback, then force-release so the rest of the app
            // isn't deadlocked behind this one page.
            try {
                onTimeout()
            } catch (e: Throwable) {
                logcat(LogPriority.WARN, e) {
                    "TachiyomiAT permit-watchdog onTimeout threw: pageKey=$pageKey"
                }
            }
            logcat(LogPriority.ERROR) {
                "TachiyomiAT permit-watchdog FORCE-RELEASED after ${timeoutMs}ms " +
                    "(worker stuck in uncancellable code): pageKey=$pageKey chapterId=$chapterId"
            }
            onPageStuck?.invoke(chapterId, pageKey)
            // Clear caller bookkeeping (e.g. inFlightPageKeys) BEFORE freeing
            // the permit so the next acquirer observes a clean state. See the
            // doc comment for why this must not be deferred to the worker's
            // own finally — that finally is unreachable while the worker is
            // stuck in native code.
            try {
                onForceRelease()
            } catch (e: Throwable) {
                logcat(LogPriority.WARN, e) {
                    "TachiyomiAT permit-watchdog onForceRelease threw: pageKey=$pageKey"
                }
            }
            releaseOnce()
        }
        return try {
            block()
        } finally {
            watchdog.cancel()
            releaseOnce()
        }
    }

    private var currentFromLang: TextRecognizerLanguage
    private var currentOcrModel: OcrModel
    // TachiyomiAT: these engine references are reassigned from a translation
    // coroutine (on language change) and read/closed from closeEngines() which
    // runs WITHOUT the permit (called from stop() on the main thread). Mark them
    // @Volatile so a stop()/language-change race reads a consistent reference
    // instead of a stale value — closing the wrong client or seeing a half-
    // published field. See closeEngines() for the full lifecycle note.
    @Volatile
    private var textTranslator: TextTranslator
    @Volatile
    private var recognitionEngine: PageRecognitionEngine
    private var currentInpaintingMode: InpaintingMode

    // TachiyomiAT: snapshot of EVERY config dimension used to build the current
    // textTranslator, captured at every build site. The rebuild gates below
    // compare a freshly-computed signature against this one so that changing the
    // engine category, provider, API key, model, temperature, max-tokens, or
    // languages at runtime forces a rebuild — not just language changes. Without
    // this, the cached AI translator instance (which captures key/model/temp as
    // constructor fields and never re-reads prefs) survived a stop+reconfigure+restart
    // cycle, leaving the run pinned to the original config. apiKeyHash is a short
    // non-reversible digest so secrets are never stored in this struct or logged.
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
            fromLang = fromLang,
            toLang = toLang,
        )
    }

    /** Stable, non-reversible short digest for secret comparison (API keys). */
    private fun shortHash(value: String): String = ShortHash.hash(value)

    init {
        // TachiyomiAT: STRICT no-fallback. fromPref/build now THROW on invalid
        // config (see TextRecognizerLanguage / TextTranslatorLanguage /
        // StandardTranslatorKind). The throw is the intended behavior on the
        // translate path (the entry points catch it and mark the page FAILED
        // with a "reconfigure settings" message). But this object is
        // constructed eagerly as a field initializer in TranslationManager —
        // an invalid pref at startup must NOT crash the app here. So build the
        // engines defensively: on a config error, fall back to the safest
        // defaults so the object constructs, and let the first translate
        // attempt's fromPref re-throw and surface the real error via the
        // pipeline's try/catch. ensureEnginesBuiltFor (called at every
        // translate) overwrites whatever is built here once config is valid.
        try {
            val fromLang = TextRecognizerLanguage.fromPref(translationPreferences.translateFromLanguage())
            val toLang = TextTranslatorLanguage.fromPref(translationPreferences.translateToLanguage())
            val ocrModel = OcrModelCatalog.selectedModel(translationPreferences, fromLang)
            currentFromLang = fromLang
            currentOcrModel = ocrModel
            currentInpaintingMode = inpaintingModeFromPref()
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
            recognitionEngine = createRecognitionEngine(
                TextRecognizerLanguage.JAPANESE,
                OcrModel.MLKIT,
                InpaintingMode.FAST,
            )
            // A no-op translator that throws on use — nothing should call it
            // because the entry-point fromPref throws first, but it guarantees
            // the field is never null without a lateinit crash.
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
        if (!translatorPermit.tryAcquire()) {
            enginesClosed = true
            return
        }
        try {
            enginesClosed = true
            // TachiyomiAT: drop the in-flight dedup set so a stuck page from the
            // previous engine lifetime can't keep it permanently blacklisted.
            // Without this, a page wedged in uncancellable native code when
            // closeEngines ran stays in inFlightPageKeys forever, and the dedup
            // gate silently drops every future translate request for it — the
            // "pipeline stops working entirely" symptom after a stop()/rebuild.
            inFlightPageKeys.clear()
            try { recognitionEngine.close() } catch (_: Exception) {}
            try { textTranslator.close() } catch (_: Exception) {}
        } finally {
            translatorPermit.release()
        }
    }

    private var consecutiveOomCount = 0
    private var currentChapterTranslation: Translation? = null
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

    private fun registerActiveStore(translation: Translation) {
        currentChapterTranslation = translation
    }

    private fun unregisterActiveStore(translation: Translation) {
        // Clear only the translator-local pointer. We intentionally do NOT call
        // activeStoreUnregister here: that callback removes the shared
        // ChapterTranslationStore from TranslationManager's cache, but the reader
        // captured this store's StateFlow once at observe time and never
        // re-resolves it. Evicting it after every single-page translation meant
        // the next translate created a fresh store the reader never saw — so
        // only the first page after opening the reader ever updated live.
        // The shared store is now managed by TranslationManager's own lifecycle
        // (chapter change / reader exit), not per-translation.
        if (currentChapterTranslation?.chapter?.id == translation.chapter.id) {
            currentChapterTranslation = null
        }
    }

    private fun createFailedPagePlaceholder(
        fileName: String,
        errorMessage: String?,
        imgWidth: Float = 0f,
        imgHeight: Float = 0f,
        originalImgWidth: Float = 0f,
        originalImgHeight: Float = 0f,
        decodeSampleSize: Int = 1,
        retryCount: Int = 0,
        // TachiyomiAT: mirrors retryCount for the per-attempt exhaustion counter.
        // A freshly-built FAILED placeholder represents one failed attempt, so
        // callers that merge with existing state pass (existing.attemptCount + 1)
        // and standalone first-failure callers pass the default 1. See
        // PageTranslation.attemptCount / recordAttemptFailure.
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
            errorMessage = errorMessage,
            updatedAt = System.currentTimeMillis(),
            retryCount = retryCount,
        ).apply { this.attemptCount = attemptCount }
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
    private suspend fun markPageTimedOut(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        pageKey: String,
    ) {
        // TachiyomiAT: resolve store with SAFE language fallbacks, not the
        // throwing fromPref. This helper runs in an error/timeout path; if the
        // CAUSE was invalid config, calling fromPref here would throw AGAIN
        // and mask the original failure, leaving the page unmarked. The store
        // is keyed on manga/chapter (not language), so the language here only
        // satisfies the Translation constructor — any valid value works.
        val syntheticTranslation = Translation(
            source, manga, chapter,
            TextRecognizerLanguage.JAPANESE,
            TextTranslatorLanguage.ENGLISH,
        )
        val store = activeStoreResolver?.invoke(syntheticTranslation) ?: return
        store.updatePage(pageKey) { existing ->
            // Don't overwrite a page that already produced a result (rendered/
            // cleaned) — a late timeout after a successful persist would erase it.
            if (existing != null &&
                (existing.renderedImageName != null || existing.cleanedImageName != null)
            ) {
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

    private suspend fun markPageFailed(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        pageKey: String,
        error: Throwable,
    ) {
        // TachiyomiAT: resolve store with SAFE language fallbacks (see
        // markPageTimedOut). This runs in the catch path; if invalid config
        // caused the failure, fromPref here would re-throw and mask it.
        val syntheticTranslation = Translation(
            source, manga, chapter,
            TextRecognizerLanguage.JAPANESE,
            TextTranslatorLanguage.ENGLISH,
        )
        val store = activeStoreResolver?.invoke(syntheticTranslation) ?: return
        store.updatePage(pageKey) { existing ->
            // Don't overwrite a page that already produced a result (rendered/
            // cleaned) — a late error after a successful persist would erase it.
            if (existing != null &&
                (existing.renderedImageName != null || existing.cleanedImageName != null)
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
     * .cleaned. Phase HTTP+Render (outside the permit): cooperative cancel check,
     * textTranslator.translatePage, Canvas render, persist rendered. Splitting the
     * permit-held ONNX work from the network-bound HTTP translate lets the next
     * prefetch page's ONNX overlap this page's network call — the same asymmetry
     * benefit the batch path already derives.
     */
    override suspend fun translateSinglePage(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        pageKey: String,
        force: Boolean,
    ) {
        // ── Phase ONNX (permit-held) ──────────────────────────────────────────
        // Stream resolution → decode → recognize (OCR+inpaint) → persist .cleaned.
        // Returns null for resume/completed/error paths (already handled).
        val onnxResult = withLeakProofPermit(
            permit = translatorPermit,
            timeoutMs = ONNX_PHASE_TIMEOUT_MS,
            chapterId = chapter.id,
            pageKey = pageKey,
            onTimeout = { markPageTimedOut(manga, chapter, source, pageKey) },
            onForceRelease = { inFlightPageKeys.remove(pageKey) },
        ) {
            if (!inFlightPageKeys.add(pageKey)) return@withLeakProofPermit null
            try {
                withTimeoutOrNull(ONNX_PHASE_TIMEOUT_MS) {
                    translateSinglePageOnnx(manga, chapter, source, pageKey, force = force)
                } ?: run {
                    logcat(LogPriority.WARN) {
                        "TachiyomiAT ONNX phase timed out after ${ONNX_PHASE_TIMEOUT_MS}ms: " +
                            "pageKey=$pageKey chapter=${chapter.name}"
                    }
                    markPageTimedOut(manga, chapter, source, pageKey)
                    null
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                logcat(LogPriority.ERROR, t) {
                    "TachiyomiAT ONNX phase failed: pageKey=$pageKey"
                }
                markPageFailed(manga, chapter, source, pageKey, t)
                throw t
            } finally {
                inFlightPageKeys.remove(pageKey)
            }
        } ?: return  // timed out, failed, or resume-completed

        // ── Phase HTTP+Render (permit-free) ───────────────────────────────────
        try {
            withTimeoutOrNull(SINGLE_PAGE_TIMEOUT_MS) {
                translateSinglePageHttpRender(manga, chapter, source, pageKey, onnxResult)
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
    }

    override suspend fun translateSinglePageFromStream(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        pageKey: String,
        streamFn: () -> InputStream,
        force: Boolean,
    ) {
        // ── Phase ONNX (permit-held) ──────────────────────────────────────────
        val onnxResult = withLeakProofPermit(
            permit = translatorPermit,
            timeoutMs = ONNX_PHASE_TIMEOUT_MS,
            chapterId = chapter.id,
            pageKey = pageKey,
            onTimeout = { markPageTimedOut(manga, chapter, source, pageKey) },
            onForceRelease = { inFlightPageKeys.remove(pageKey) },
        ) {
            if (!inFlightPageKeys.add(pageKey)) return@withLeakProofPermit null
            try {
                withTimeoutOrNull(ONNX_PHASE_TIMEOUT_MS) {
                    translateSinglePageOnnx(manga, chapter, source, pageKey, streamFn, force)
                } ?: run {
                    logcat(LogPriority.WARN) {
                        "TachiyomiAT ONNX phase timed out after ${ONNX_PHASE_TIMEOUT_MS}ms: " +
                            "pageKey=$pageKey chapter=${chapter.name}"
                    }
                    markPageTimedOut(manga, chapter, source, pageKey)
                    null
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                logcat(LogPriority.ERROR, t) {
                    "TachiyomiAT ONNX phase failed: pageKey=$pageKey"
                }
                markPageFailed(manga, chapter, source, pageKey, t)
                throw t
            } finally {
                inFlightPageKeys.remove(pageKey)
            }
        } ?: return

        // ── Phase HTTP+Render (permit-free) ───────────────────────────────────
        try {
            withTimeoutOrNull(SINGLE_PAGE_TIMEOUT_MS) {
                translateSinglePageHttpRender(manga, chapter, source, pageKey, onnxResult)
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
    }

    /**
     * TachiyomiAT: STAGED BATCH translation — the pre-translate path used by the
     * manga-screen "translate chapter" action (and anything that wants to
     * prepare a whole chapter before the reader opens). Replaces the old
     * page-1-first sequential loop.
     *
     * Stages (per the staged-batch design):
     *   1. DETECT + OCR batch  — [analyzePage] for each page in [orderedStreams],
     *      serialized under [translatorPermit] (one page's bitmap/tensor set
     *      alive at a time). Persists ocrStatus=READY + blocks per page, so this
     *      stage is resumable (skips pages already analyzed).
     *   2. INPAINT ‖ TRANSLATE — for each page with blocks: inpaint (re-decoded
     *      bitmap, serialized under the permit) runs concurrently with
     *      textTranslator.translatePage (HTTP-only, no permit). The HTTP work
     *      overlaps the ONNX work — free parallelism, no extra peak memory.
     *   3. RENDER               — for each page with translated text + a cleaned
     *      image, render translated text onto the cleaned bitmap (Canvas only,
     *      no permit) and persist.
     *
     * Memory model: one page bitmap is alive at a time (recycled after analyze,
     * re-decoded for inpaint, recycled after inpaint). Stage 2 persists each
     * cleaned image to disk (.cleaned.png) and releases the in-memory cleaned
     * bitmap immediately — stage 3 reloads one at a time — so the batch holds at
     * most one cleaned bitmap at any instant regardless of chapter length.
     * (Previously stage 2 kept every cleaned bitmap live across the whole chapter
     * until stage 3, which OOM'd on large chapters.) The reader is NOT open on
     * this path, so there is no concurrent display decode to race.
     *
     * [orderedStreams] is already in the desired processing order (forward-first
     * from the resume page, then backfill) — see [ResumeOrdering].
     */
    suspend fun translateBatch(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        store: ChapterTranslationStore,
        orderedStreams: List<Pair<String, () -> InputStream>>,
        tracker: TranslationBatchProgressTracker? = null,
    ) {
        if (orderedStreams.isEmpty()) return
        val fromLang = TextRecognizerLanguage.fromPref(translationPreferences.translateFromLanguage())
        val toLang = TextTranslatorLanguage.fromPref(translationPreferences.translateToLanguage())
        ensureEnginesBuiltFor(fromLang, toLang)

        val ensureCompanionDir: suspend () -> UniFile? = {
            provider.getCompanionImageDir(manga.title, source, chapter.name, chapter.scanlator)
        }

        logcat(LogPriority.INFO) {
            "TachiyomiAT batch START chapter=${chapter.name} pages=${orderedStreams.size} " +
                "engine=${recognitionEngine::class.simpleName} translator=${textTranslator::class.simpleName}"
        }

        // ── 3-lane bounded pipeline setup ───────────────────────────────────────
        // Lane A (OCR+inpaint, permit-bound) feeds Lane B (translate, HTTP-bound)
        // through an UNLIMITED channel; render is a join (tryRender) that reuses the
        // in-memory cleaned bitmap when it fits the byte budget. See
        // .kilo/plans/1782485204862-inpaint-translate-pipeline.md.
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

        // TachiyomiAT: chapter-level term glossary accumulator for cross-chunk
        // name/pronoun continuity. Seeded from already-translated pairs (batch
        // resume) so recurring terms established before a restart still feed the
        // glossary. Built incrementally per chunk and persisted on the store.
        val glossaryStats = ChapterGlossaryBuilder.Stats()
        if (isAi) {
            store.translatedPairs().forEach { (s, t) -> glossaryStats.add(s, t) }
        }

        // Held-cleaned-bitmap registry: render reuses the in-memory cleaned bitmap
        // instead of reloading .cleaned.png from disk. Memory is bounded by BOTH a
        // byte ceiling (HELD_BITMAP_BYTE_CEILING) and a hard count cap
        // (HELD_BITMAP_MAX_COUNT): a page that would exceed either spills — its
        // .cleaned.png is already durable on disk, so the bitmap is recycled
        // immediately and render reloads it (today's behavior).
        val heldBitmapBytes = AtomicLong(0L)
        val countSlots = Semaphore(HELD_BITMAP_MAX_COUNT)
        val bitmapRegistry = ConcurrentHashMap<String, Bitmap>()
        val translationRegistry = ConcurrentHashMap<String, PageTranslation>()
        val renderMutexes = ConcurrentHashMap<String, Mutex>()
        val aborted = AtomicBoolean(false)

        fun resumeGate(page: PageTranslation?): BatchResumeGate {
            if (page == null) return BatchResumeGate.FULL
            val ocrReady = page.ocrStatus == StageStatus.READY
            val hasMask = page.hasCurrentInpaintMask
            val durableCleaned = page.cleanedImageName != null &&
                page.inpaintStatus == StageStatus.READY &&
                page.hasCurrentInpaintResult
            return when {
                ocrReady && hasMask && durableCleaned -> BatchResumeGate.SKIP_ALL
                ocrReady && hasMask -> BatchResumeGate.INPAINT_ONLY
                else -> BatchResumeGate.FULL
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
                try { cleaned.recycle() } catch (_: Exception) {}
            }
        }

        fun recycleHeld(pageKey: String) {
            val b = bitmapRegistry.remove(pageKey) ?: return
            heldBitmapBytes.addAndGet(-b.byteCount.toLong())
            try { b.recycle() } catch (_: Exception) {}
            countSlots.release()
        }

        suspend fun tryRender(pageKey: String) {
            val mutex = renderMutexes.computeIfAbsent(pageKey) { Mutex() }
            mutex.withLock {
                val page = translationRegistry[pageKey] ?: return@withLock
                if (page.renderStatus == StageStatus.READY && page.renderedImageName != null) {
                    recycleHeld(pageKey)
                    translationRegistry.remove(pageKey)
                    tracker?.markRenderDone(pageKey)
                    return@withLock
                }
                val status = page.translationStatus
                if (status != StageStatus.READY && status != StageStatus.PARTIAL) {
                    if (status == StageStatus.FAILED) {
                        recycleHeld(pageKey)
                        translationRegistry.remove(pageKey)
                    }
                    return@withLock
                }
                // READY/PARTIAL -> render. Consume the held bitmap on the happy path
                // (no disk reload); spill/SKIP_ALL pages reload .cleaned.png instead.
                val held = bitmapRegistry.remove(pageKey)
                if (held != null) {
                    heldBitmapBytes.addAndGet(-held.byteCount.toLong())
                    countSlots.release()
                }
                val bitmap = held ?: page.cleanedImageName?.let { loadPersistedCleanedBitmap(manga, chapter, source, it) }
                if (bitmap == null) {
                    translationRegistry.remove(pageKey)
                    return@withLock
                }
                try {
                    tracker?.markRenderRunning(pageKey)
                    store.updatePage(pageKey) {
                        (it ?: page).apply {
                            renderStatus = StageStatus.RUNNING
                            updatedAt = System.currentTimeMillis()
                        }
                    }
                    RenderColorEstimator.recomputeFor(bitmap, page.blocks)
                    val rendered = PageTextRenderer(context).render(bitmap, page.blocks, page.decodeSampleSize)
                    try {
                        val companionDir = provider.getCompanionImageDir(
                            manga.title, source, chapter.name, chapter.scanlator,
                        )
                        persistRenderedBitmap(page, rendered, companionDir, pageKey)
                        tracker?.markRenderDone(pageKey)
                    } finally {
                        if (rendered !== bitmap) try { rendered.recycle() } catch (_: Exception) {}
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    page.renderStatus = StageStatus.FAILED
                    page.recordAttemptFailure()
                    page.errorMessage = e.message
                    tracker?.markRenderFailed(pageKey, e.message ?: e::class.java.simpleName)
                    logcat(LogPriority.ERROR, e) { "TachiyomiAT batch render failed: $pageKey" }
                } finally {
                    try { bitmap.recycle() } catch (_: Exception) {}
                    page.cleanedBitmap = null
                    persistPageWithOomRecovery(store, pageKey, page)
                    translationRegistry.remove(pageKey)
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
        ): String {
            coroutineContext.ensureActive()
            val ct = contextualTranslator ?: return rolling
            
            if (!dynamicGlossaryExtracted) {
                dynamicGlossaryExtracted = true
                dynamicGlossary = eu.kanade.translation.translator.GlossaryExtractor.extractGlossary(ct, chunk.pages.values.toList())
            }
            
            val glossaryText = dynamicGlossary + "\n" + ChapterGlossaryBuilder.formatGlossary(glossaryStats.build())
            val contextualChunk = TranslationContextChunkPlanner.withRollingContext(
                chunk = chunk,
                rollingContext = rolling,
                requestedOutputTokens = requestedOutputTokens,
                profile = chunkProfile,
                glossary = glossaryText,
            )
            chunk.pages.keys.forEach { pk ->
                val p = translationRegistry[pk] ?: return@forEach
                store.updatePage(pk) {
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
                    rolling, contextualChunk.pages,
                )
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
                // TachiyomiAT: accumulate this chunk's freshly translated pairs
                // into the chapter glossary and persist it. Also log [SPEECH]
                // tag coverage so a regression to a non-parenting recognition
                // engine (which would leave every block untagged) is visible.
                var tagged = 0
                var untagged = 0
                contextualChunk.pages.values.forEach { page ->
                    page.blocks.forEach { b ->
                        glossaryStats.add(b.text, b.translation)
                        if (b.parentWidth > 0f && b.parentHeight > 0f) tagged++ else untagged++
                    }
                }
                val newGlossary = glossaryStats.build()
                store.updateGlossary(newGlossary)
                logcat(LogPriority.INFO) {
                    "TachiyomiAT batch stage2-AI chunk: tag-coverage tagged=$tagged untagged=$untagged " +
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
                    store.updatePage(pk) {
                        (it ?: p).apply {
                            translationStatus = p.translationStatus
                            if (status == StageStatus.FAILED) {
                                errorMessage = p.errorMessage
                                retryCount = p.retryCount
                                attemptCount = p.attemptCount
                            }
                            updatedAt = System.currentTimeMillis()
                        }
                    }
                    tryRender(pk)
                }
                return newRolling
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                val reason = e.message ?: e.javaClass.simpleName
                chunk.pages.keys.forEach { pk ->
                    val p = translationRegistry[pk] ?: return@forEach
                    markBatchTranslationFailed(store, pk, p, "AI chunk failed: $reason")
                    tracker?.markTranslateFailed(pk, "AI chunk failed: $reason")
                    tryRender(pk)
                }
                logcat(LogPriority.ERROR, e) {
                    "TachiyomiAT contextual batch translate failed: chunk pages=${chunk.pages.keys}"
                }
                return rolling
            }
        }

        suspend fun completeChunklessPage(pk: String) {
            val p = translationRegistry[pk] ?: return
            TranslationBlockValidation.applyTo(p)
            p.translationStatus = StageStatus.READY
            store.updatePage(pk) {
                (it ?: p).apply {
                    translationStatus = StageStatus.READY
                    errorMessage = null
                    updatedAt = System.currentTimeMillis()
                }
            }
            tracker?.markTranslateDone(pk)
            tryRender(pk)
        }

        // ── 3-lane pipeline: Lane A (OCR+inpaint, permit-bound) produces pages into
        // an UNLIMITED channel; Lane B (this coroutine, HTTP-bound) drains it,
        // translates, and triggers render via tryRender. UNLIMITED is deliberate —
        // Lane A's send never blocks on capacity (a bounded/RENDEZVOUS channel would
        // deadlock once Lane B is mid-HTTP), and send stays cancellable so a cancel
        // or chapter-switch still propagates. produce{} auto-closes the channel on
        // cancel/complete, so neither lane can wedge the other.
        try {
            coroutineScope {
                val ocrChannel: ReceiveChannel<Pair<String, PageTranslation>> = produce(
                    capacity = Channel.UNLIMITED,
                ) {
                    for ((pageKey, streamFn) in orderedStreams) {
                        ensureActive()
                        if (aborted.get()) break
                        val existing = store.state.value[pageKey]
                        val gate = resumeGate(existing)
                        if (gate == BatchResumeGate.SKIP_ALL) {
                            // Fully durable (OCR+inpaint done): no decode, no slot.
                            // Register + forward for translate; render reloads disk.
                            val p = existing!!
                            translationRegistry[pageKey] = p
                            send(pageKey to p)
                            tryRender(pageKey)
                            continue
                        }
                        withLeakProofPermit(
                            permit = translatorPermit,
                            timeoutMs = SINGLE_PAGE_TIMEOUT_MS,
                            chapterId = chapter.id,
                            pageKey = pageKey,
                            onTimeout = { markPageTimedOut(manga, chapter, source, pageKey) },
                            onForceRelease = {},
                        ) {
                            val latest = store.state.value[pageKey]
                            val innerGate = resumeGate(latest)
                            if (innerGate == BatchResumeGate.SKIP_ALL) {
                                val p = latest!!
                                translationRegistry[pageKey] = p
                                send(pageKey to p)
                                tryRender(pageKey)
                                return@withLeakProofPermit
                            }
                            try {
                                val decoded = try {
                                    decodePageBitmapForTranslation(pageKey, streamFn)
                                } catch (deferred: LowMemoryDecodeDeferredException) {
                                    tracker?.markOcrFailed(pageKey, deferred.message ?: "Decode deferred")
                                    store.updatePage(pageKey) {
                                        (it ?: PageTranslation()).apply {
                                            sourceFileName = pageKey
                                            ocrStatus = StageStatus.FAILED
                                            errorMessage = deferred.message
                                            retryCount = (it?.retryCount ?: 0) + 1
                                            attemptCount = (it?.attemptCount ?: 0) + 1
                                            updatedAt = System.currentTimeMillis()
                                        }
                                    }
                                    return@withLeakProofPermit
                                } ?: run {
                                    tracker?.markOcrFailed(pageKey, "Failed to decode page: null bitmap")
                                    store.updatePage(pageKey) {
                                        (it ?: PageTranslation()).apply {
                                            sourceFileName = pageKey
                                            ocrStatus = StageStatus.FAILED
                                            errorMessage = "Failed to decode page: null bitmap"
                                            retryCount = (it?.retryCount ?: 0) + 1
                                            attemptCount = (it?.attemptCount ?: 0) + 1
                                            updatedAt = System.currentTimeMillis()
                                        }
                                    }
                                    return@withLeakProofPermit
                                }
                                val bitmap = decoded.bitmap
                                try {
                                    if (innerGate == BatchResumeGate.INPAINT_ONLY) {
                                        // Reuse the durable OCR result; carry it forward.
                                        translationRegistry[pageKey] = latest ?: PageTranslation(sourceFileName = pageKey)
                                    } else {
                                        tracker?.markOcrRunning(pageKey)
                                        // analyzePage persists blocks + mask durable
                                        // (ocrStatus READY) BEFORE inpaint — the OCR crash
                                        // window is closed before the merged inpaint runs.
                                        val analyzed = analyzePage(pageKey, bitmap, decoded, store)
                                        tracker?.markOcrDone(pageKey)
                                        translationRegistry[pageKey] = analyzed
                                    }
                                    val target = translationRegistry[pageKey]!!
                                    val hasDurableCleaned = latest != null &&
                                        latest.cleanedImageName != null &&
                                        latest.inpaintStatus == StageStatus.READY &&
                                        latest.hasCurrentInpaintResult
                                    if (hasDurableCleaned) {
                                        target.cleanedImageName = latest!!.cleanedImageName
                                        target.inpaintStatus = StageStatus.READY
                                        target.cleanedBitmap = null
                                    } else {
                                        tracker?.markInpaintRunning(pageKey)
                                        preflightInpaintGate(bitmap, pageKey)
                                        inpaintPage(pageKey, bitmap, target, store, ensureCompanionDir)
                                        if (target.inpaintStatus == StageStatus.READY) tracker?.markInpaintDone(pageKey)
                                        else tracker?.markInpaintFailed(pageKey, target.errorMessage ?: "Inpaint failed")
                                    }
                                    holdCleaned(pageKey, target.cleanedBitmap)
                                    target.cleanedBitmap = null
                                    send(pageKey to target)
                                    tryRender(pageKey)
                                } finally {
                                    try { bitmap.recycle() } catch (_: Exception) {}
                                    BitmapPool.releaseAll()
                                    try { recognitionEngine.reclaimPooledMemory() } catch (_: Exception) {}
                                }
                            } catch (deferred: LowMemoryRecognitionDeferredException) {
                                val t = translationRegistry[pageKey]
                                if (t != null) {
                                    t.inpaintStatus = StageStatus.FAILED
                                    t.errorMessage = deferred.message
                                }
                                tracker?.markInpaintFailed(pageKey, deferred.message ?: "Recognition deferred")
                            }
                        }
                        val oomDecision = BatchOomPolicy.shouldAbort(consecutiveOomCount)
                        if (oomDecision.abort) {
                            logcat(LogPriority.ERROR) {
                                "TachiyomiAT batch ABORT (OOM after $consecutiveOomCount consecutive): pageKey=$pageKey"
                            }
                            aborted.set(true)
                            tracker?.markChapterError(oomDecision.reason ?: "Memory exhausted")
                            break
                        }
                    }
                }

                if (isAi) {
                    val planner = StreamingChunkPlanner(requestedOutputTokens, chunkProfile)
                    var rollingContext = ""
                    for ((pageKey, page) in ocrChannel) {
                        ensureActive()
                        if (aborted.get()) break
                        page.blocks = eu.kanade.translation.util.TranslationBlockSorter.sort(page.blocks, fromLang)
                        translationRegistry[pageKey] = page
                        val sourceBlocks = page.blocks.count { it.text.isNotBlank() }
                        if (sourceBlocks == 0) {
                            recycleHeld(pageKey)
                            translationRegistry.remove(pageKey)
                            continue
                        }
                        val emission = planner.accept(pageKey, page)
                        if (emission != null && emission.chunk != null) {
                            rollingContext = translateChunkAi(emission.chunk!!, emission.completedPages, rollingContext)
                        } else if (emission != null) {
                            emission.completedPages.forEach { pk -> completeChunklessPage(pk) }
                        }
                        tryRender(pageKey)
                    }
                    val flush = planner.flushRemaining()
                    if (flush.finalChunk != null) {
                        rollingContext = translateChunkAi(flush.finalChunk, flush.completedPages, rollingContext)
                    } else {
                        flush.completedPages.forEach { pk -> completeChunklessPage(pk) }
                    }
                    flush.rejectedPages.forEach { (pk, reason) ->
                        val p = translationRegistry[pk]
                        if (p != null) {
                            markBatchTranslationFailed(store, pk, p, reason)
                            tracker?.markTranslateFailed(pk, reason)
                            tryRender(pk)
                        }
                    }
                } else {
                    for ((pageKey, page) in ocrChannel) {
                        ensureActive()
                        if (aborted.get()) break
                        page.blocks = eu.kanade.translation.util.TranslationBlockSorter.sort(page.blocks, fromLang)
                        translationRegistry[pageKey] = page
                        val sourceBlocks = page.blocks.count { it.text.isNotBlank() }
                        if (sourceBlocks == 0) {
                            recycleHeld(pageKey)
                            translationRegistry.remove(pageKey)
                            continue
                        }
                        try {
                            tracker?.markTranslateRunning(pageKey)
                            textTranslator.translatePage(pageKey, page)
                            TranslationBlockValidation.applyTo(page)
                            val s = page.translationStatus
                            when (s) {
                                StageStatus.READY -> tracker?.markTranslateDone(pageKey)
                                StageStatus.PARTIAL -> tracker?.markTranslatePartial(pageKey)
                                else -> tracker?.markTranslateFailed(pageKey, page.errorMessage ?: "Translate unknown state")
                            }
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            page.translationStatus = StageStatus.FAILED
                            page.errorMessage = e.message
                            tracker?.markTranslateFailed(pageKey, e.message ?: e::class.java.simpleName)
                            logcat(LogPriority.ERROR, e) { "TachiyomiAT batch translate failed: $pageKey" }
                        } finally {
                            store.updatePage(pageKey) {
                                (it ?: page).apply {
                                    translationStatus = page.translationStatus
                                    errorMessage = page.errorMessage
                                    updatedAt = System.currentTimeMillis()
                                }
                            }
                        }
                        tryRender(pageKey)
                    }
                }
            }
        } finally {
            // Slot/byte balance on ANY exit (normal, cancel, throw): a slot is
            // acquired ONLY when a bitmap actually enters the registry (holdCleaned
            // iff it fits), and released exactly once when that bitmap leaves it —
            // via recycleHeld, tryRender's happy-path consume, or this loop. Bitmaps
            // already consumed/recycled/spilled are gone from the registry and
            // balanced themselves, so only the registry's REMAINING entries need a
            // release here: releasing exactly `leaked` slots restores countSlots to
            // HELD_BITMAP_MAX_COUNT with no double-release. This is the ONLY release
            // site that observes pages whose render never ran (cancel/OOM mid-flight).
            val leaked = bitmapRegistry.size
            bitmapRegistry.values.forEach { try { it.recycle() } catch (_: Exception) {} }
            bitmapRegistry.clear()
            heldBitmapBytes.set(0L)
            repeat(leaked) { countSlots.release() }
        }

        // OOM abort (consecutiveOomCount >= 3): skip the reconciler finish,
        // mirroring the prior serial pipeline's early return — markChapterError
        // already recorded the abort reason on every still-pending page.
        if (aborted.get()) {
            logcat(LogPriority.WARN) {
                "TachiyomiAT batch aborted (OOM), skipping reconciler finish: chapter=${chapter.name}"
            }
            store.flush()
            return
        }
        logcat(LogPriority.INFO) {
            "TachiyomiAT batch DONE chapter=${chapter.name} pages=${orderedStreams.size}"
        }

        val pageMap = store.state.value
        val reconciliation = BatchProgressReconciler.reconcile(
            pageMap = pageMap,
            orderedKeys = orderedStreams.map { it.first },
        )
        if (tracker != null) {
            tracker.finish(reconciliation)
        }
        // Apply stranded pages from reconciler — mark them FAILED with the
        // reconciler's reasons so the store reflects reality for the caller.
        reconciliation.strandedPages.forEach { (pageKey, reason) ->
            store.updatePage(pageKey) { existing ->
                (existing ?: PageTranslation(sourceFileName = pageKey)).apply {
                    ocrStatus = StageStatus.FAILED
                    errorMessage = reason
                    updatedAt = System.currentTimeMillis()
                }
            }
        }
        store.flush()
    }

    private suspend fun translateAiChunkWithAdaptiveRetry(
        translator: ContextualTextTranslator,
        chunk: TranslationContextChunk,
        requestedOutputTokens: Int,
        profile: TranslationContextChunkPlanner.Profile,
        allowFailureSplit: Boolean,
        label: String,
        retryDepth: Int,
    ) {
        coroutineContext.ensureActive()
        try {
            logcat(LogPriority.INFO) {
                "TachiyomiAT batch stage2-AI request $label pass=$retryDepth: " +
                    "pages=${chunk.pages.size} blocks=${chunk.blockCount} " +
                    "promptTokens=${chunk.estimatedPromptTokens} maxOutput=${chunk.maxOutputTokens}"
            }
            translator.translateContextual(chunk)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (!allowFailureSplit || chunk.blockCount <= 1) {
                if (allowFailureSplit || retryDepth > 0) {
                    logcat(LogPriority.WARN, e) {
                        "TachiyomiAT batch stage2-AI terminal chunk failure $label pass=$retryDepth: " +
                            "pages=${chunk.pages.keys} blocks=${chunk.blockCount}"
                    }
                    return
                }
                throw e
            }
            val split = AiTranslationRetryPlanner.planFailureSplit(
                chunk = chunk,
                requestedOutputTokens = requestedOutputTokens,
                profile = profile,
            )
            if (split.chunks.isEmpty()) {
                logcat(LogPriority.WARN, e) {
                    "TachiyomiAT batch stage2-AI failed and produced no retry chunks $label pass=$retryDepth"
                }
                return
            }
            logcat(LogPriority.WARN, e) {
                "TachiyomiAT batch stage2-AI splitting failed chunk $label pass=$retryDepth: " +
                    "blocks=${chunk.blockCount} retryChunks=${split.chunks.size}"
            }
            split.chunks.forEachIndexed { index, retryChunk ->
                translateAiChunkWithAdaptiveRetry(
                    translator = translator,
                    chunk = retryChunk,
                    requestedOutputTokens = requestedOutputTokens,
                    profile = profile,
                    allowFailureSplit = allowFailureSplit,
                    label = "$label.${index + 1}",
                    retryDepth = retryDepth + 1,
                )
            }
            return
        }

        val missingPages = AiTranslationRetryPlanner.untranslatedPages(chunk)
        val missingBlocks = missingPages.values.sumOf { it.blocks.size }
        if (missingBlocks == 0) return

        if (chunk.blockCount <= 1) {
            logcat(LogPriority.WARN) {
                "TachiyomiAT batch stage2-AI terminal partial $label pass=$retryDepth: " +
                    "remainingBlocks=$missingBlocks"
            }
            return
        }

        val missingPlan = AiTranslationRetryPlanner.planMissingRetry(
            chunk = chunk,
            requestedOutputTokens = requestedOutputTokens,
            profile = profile,
        )
        if (missingPlan.chunks.isEmpty()) return
        logcat(LogPriority.WARN) {
            "TachiyomiAT batch stage2-AI partial $label pass=$retryDepth: " +
                "remainingBlocks=$missingBlocks retryChunks=${missingPlan.chunks.size}"
        }
        missingPlan.chunks.forEachIndexed { index, retryChunk ->
            translateAiChunkWithAdaptiveRetry(
                translator = translator,
                chunk = retryChunk,
                requestedOutputTokens = requestedOutputTokens,
                profile = profile,
                allowFailureSplit = allowFailureSplit,
                label = "$label.missing${index + 1}",
                retryDepth = retryDepth + 1,
            )
        }
    }

    private suspend fun markBatchTranslationFailed(
        store: ChapterTranslationStore,
        pageKey: String,
        pageTranslation: PageTranslation,
        reason: String,
    ) {
        pageTranslation.translationStatus = StageStatus.FAILED
        pageTranslation.errorMessage = reason
        // TachiyomiAT: translation is typically the first terminal stage in the
        // batch flow (OCR succeeded, translate failed), so it owns the attempt
        // charge. recordAttemptFailure is idempotent if a prior stage already
        // failed. Replaces bare retryCount++ for the one-failure-one-attempt
        // invariant (see PageTranslationState.recordAttemptFailure).
        pageTranslation.recordAttemptFailure()
        store.updatePage(pageKey) {
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
        val rebuildClosedEngines = enginesClosed
        // TachiyomiAT: include inpainting mode in the rebuild gate so switching
        // FAST<->QUALITY from the reader sheet takes effect on the next translate
        // without requiring a language/OCR model change or app restart.
        if (rebuildClosedEngines ||
            fromLang != currentFromLang ||
            selectedOcrModel != currentOcrModel ||
            desiredInpaintingMode != currentInpaintingMode
        ) {
            recognitionEngine.close()
            currentFromLang = fromLang
            currentOcrModel = selectedOcrModel
            currentInpaintingMode = desiredInpaintingMode
            recognitionEngine = createRecognitionEngine(fromLang, currentOcrModel, currentInpaintingMode)
        }
        // Rebuild the text translator whenever the full engine configuration
        // differs from what the cached instance was built with — not just when
        // the language changes. The signature covers engine category, provider,
        // API key, model, temperature, and max-tokens, all of which the AI
        // translators capture at construction time and never re-read.
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
     * Phase ONNX of the reader single-page path: runs under [translatorPermit].
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
    ): OnnxPhaseResult? {
        val streamFromReader = readerStreamFn ?: peekReaderPageStream(manga, chapter, source, pageKey)
        val fromLang = TextRecognizerLanguage.fromPref(translationPreferences.translateFromLanguage())
        val toLang = TextTranslatorLanguage.fromPref(translationPreferences.translateToLanguage())
        val syntheticTranslation = Translation(source, manga, chapter, fromLang, toLang)
        syntheticTranslation.status = Translation.State.TRANSLATING

        ensureEnginesBuiltFor(fromLang, toLang)

        var ownStore: ChapterTranslationStore? = null
        val store = activeStoreResolver?.invoke(syntheticTranslation).also {
            ownStore = if (it == null) null else syntheticTranslation.let { _ -> it }
        } ?: run {
            val mangaDir = provider.getMangaDir(manga.title, source)
            val saveFile = provider.getTranslationFileName(chapter.name, chapter.scanlator)
            val file = mangaDir?.createFile(saveFile) ?: return null
            ChapterTranslationStore.open(file).also { ownStore = it }
        }

        // Tracks whether the fresh path was taken; cleanup runs here for resume/
        // error paths, and is deferred to translateSinglePageHttpRender for the
        // fresh path where the cleaned bitmap crosses the permit boundary.
        var needsHttpRender = false
        try {
            registerActiveStore(syntheticTranslation)

            val resumeTranslation = if (!force) {
                store.state.value[pageKey]?.copyForResume()
            } else {
                null
            }
            if (resumeTranslation?.hasRenderedResult == true) {
                logcat(LogPriority.INFO) {
                    "TachiyomiAT single-page resume skip: pageKey=$pageKey already has final output"
                }
                return null
            }
            val resumeFromTranslatedBlocks = resumeTranslation
                ?.takeIf { it.hasRecognizedTranslation && it.renderedImageName == null }
            if (resumeFromTranslatedBlocks?.cleanedImageName != null &&
                resumeFromTranslatedBlocks.hasCurrentInpaintResult
            ) {
                val cleanedBitmap = loadPersistedCleanedBitmap(
                    manga,
                    chapter,
                    source,
                    resumeFromTranslatedBlocks.cleanedImageName!!,
                )
                if (cleanedBitmap != null) {
                    logcat(LogPriority.INFO) {
                        "TachiyomiAT single-page resume: render from cleaned image pageKey=$pageKey " +
                            "cleaned=${resumeFromTranslatedBlocks.cleanedImageName}"
                    }
                    renderResumedPage(
                        manga,
                        chapter,
                        source,
                        pageKey,
                        store,
                        resumeFromTranslatedBlocks,
                        cleanedBitmap,
                        successMessageSuffix = " (resume cleaned)",
                    )
                    return null
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
                    chapter.name, chapter.scanlator, manga.title, source,
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
                store.updatePage(pageKey) {
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

            store.updatePage(pageKey) {
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

            val decoded = try {
                decodePageBitmapForTranslation(pageKey, entry.second)
            } catch (deferred: LowMemoryDecodeDeferredException) {
                store.updatePage(pageKey) {
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
                store.updatePage(pageKey) {
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
            if (resumeFromTranslatedBlocks != null) {
                try {
                    logcat(LogPriority.INFO) {
                        "TachiyomiAT single-page resume: inpaint + render from translated blocks pageKey=$pageKey"
                    }
                    resumeInpaintAndRender(
                        manga,
                        chapter,
                        source,
                        pageKey,
                        store,
                        decoded,
                        bitmap,
                        resumeFromTranslatedBlocks,
                    )
                } finally {
                    try { bitmap.recycle() } catch (_: Exception) {}
                    BitmapPool.releaseAll()
                }
                return null
            }
            store.updatePage(pageKey) {
                (it ?: PageTranslation()).apply {
                    sourceFileName = pageKey
                    ocrStatus = StageStatus.RUNNING
                    updatedAt = System.currentTimeMillis()
                }
            }

            val pageTranslation: PageTranslation
            try {
                pageTranslation = processSinglePage(
                    pageKey, bitmap, decoded, store,
                ) {
                    provider.getCompanionImageDir(
                        manga.title, source,
                        chapter.name, chapter.scanlator,
                    )
                }
            } finally {
                try { bitmap.recycle() } catch (_: Exception) {}
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
            )
        } finally {
            if (!needsHttpRender) {
                store.flush()
                chapter.id?.let { chapterId ->
                    streamRegistry.clearPage(source.id, manga.id, chapterId, pageKey)
                }
                unregisterActiveStore(syntheticTranslation)
                try { recognitionEngine.reclaimPooledMemory() } catch (_: Exception) {}
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
    ) {
        val pageTranslation = ctx.pageTranslation
        val store = ctx.store
        val fromLang = ctx.fromLang
        val syntheticTranslation = ctx.syntheticTranslation
        val streams = ctx.streams
        val decoded = ctx.decoded

        val activeTranslator = textTranslator

        // TachiyomiAT: AI translators translate through translateContextual with
        // the chapter glossary as context, so on-demand single-page translation
        // reuses the chapter's established terms/pronouns (same continuity the
        // batch path gets). Standard translators keep the plain translatePage.
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
                )
                val chunk = TranslationContextChunkPlanner.withRollingContext(
                    chunk = baseChunk,
                    rollingContext = "",
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
                    pageTranslation.blocks = eu.kanade.translation.util.TranslationBlockSorter.sort(
                        pageTranslation.blocks,
                        fromLang
                    )
                    pageTranslation.translationStatus = StageStatus.RUNNING
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
                    // TachiyomiAT: fold this page's freshly translated pairs into
                    // the chapter glossary so later on-demand/batch translations
                    // reuse its established terms.
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
                (pageTranslation.translationStatus == StageStatus.READY ||
                    pageTranslation.translationStatus == StageStatus.PARTIAL)
            ) {
                val hasCleaned = pageTranslation.cleanedBitmap != null
                if (hasCleaned) {
                    val cleanedBitmap = pageTranslation.cleanedBitmap!!
                    try {
                        pageTranslation.renderStatus = StageStatus.RUNNING
                        val renderer = PageTextRenderer(context)
                        RenderColorEstimator.recomputeFor(cleanedBitmap, pageTranslation.blocks)
                        val renderedBitmap = renderer.render(cleanedBitmap, pageTranslation.blocks, pageTranslation.decodeSampleSize)
                        try {
                            val companionDir = provider.getCompanionImageDir(
                                manga.title, source,
                                chapter.name, chapter.scanlator,
                            )
                            persistRenderedBitmap(pageTranslation, renderedBitmap, companionDir, pageKey)
                        } finally {
                            if (renderedBitmap !== cleanedBitmap) {
                                try { renderedBitmap.recycle() } catch (_: Exception) {}
                            }
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        pageTranslation.renderStatus = StageStatus.FAILED
                        pageTranslation.recordAttemptFailure()
                        pageTranslation.errorMessage = e.message
                        logcat(LogPriority.ERROR, e) { "Failed to render text for single page $pageKey" }
                    } finally {
                        try { cleanedBitmap.recycle() } catch (_: Exception) {}
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
                        try {
                            pageTranslation.renderStatus = StageStatus.RUNNING
                            val renderer = PageTextRenderer(context)
                            RenderColorEstimator.recomputeFor(retriedCleaned, pageTranslation.blocks)
                            val renderedBitmap = renderer.render(retriedCleaned, pageTranslation.blocks, pageTranslation.decodeSampleSize)
                            try {
                                val companionDir = provider.getCompanionImageDir(
                                    manga.title, source, chapter.name, chapter.scanlator,
                                )
                                persistRenderedBitmap(
                                    pageTranslation = pageTranslation,
                                    renderedBitmap = renderedBitmap,
                                    companionDir = companionDir,
                                    pageKey = pageKey,
                                    successMessageSuffix = " (retry path)",
                                    successErrorMessage = "Inpainted on retry (downscaled decode)",
                                )
                            } finally {
                                if (renderedBitmap !== retriedCleaned) {
                                    try { renderedBitmap.recycle() } catch (_: Exception) {}
                                }
                            }
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            pageTranslation.renderStatus = StageStatus.FAILED
                            pageTranslation.recordAttemptFailure()
                            pageTranslation.errorMessage = e.message
                            logcat(LogPriority.ERROR, e) { "Failed to render text for single page (retry path) $pageKey" }
                        } finally {
                            try { retriedCleaned.recycle() } catch (_: Exception) {}
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
                    try { it.recycle() } catch (_: Exception) {}
                }
            }
            pageTranslation.cleanedBitmap = null
            pageTranslation.updatedAt = System.currentTimeMillis()
            persistPageWithOomRecovery(store, pageKey, pageTranslation)
        } finally {
            store.flush()
            // Defensive recycle: a cancel/timeout can unwind straight here from
            // before render, where cleanedBitmap (the inpainted full-page bitmap,
            // ~10–48 MB) was never recycled. On the normal path it is already
            // nulled above, so this is a no-op there.
            pageTranslation.cleanedBitmap?.let { try { it.recycle() } catch (_: Exception) {} }
            pageTranslation.cleanedBitmap = null
            chapter.id?.let { chapterId ->
                streamRegistry.clearPage(source.id, manga.id, chapterId, pageKey)
            }
            unregisterActiveStore(syntheticTranslation)
            try { recognitionEngine.reclaimPooledMemory() } catch (_: Exception) {}
        }
    }

    private fun PageTranslation.copyForResume(): PageTranslation {
        return copy(blocks = blocks.map { it.copy() }.toMutableList()).also {
            it.cleanedBitmap = null
            it.allTextDetections = emptyList()
        }
    }

    private fun loadPersistedCleanedBitmap(
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
        cleanedImageName: String,
    ): Bitmap? {
        return try {
            provider.findPageCleanedImage(
                manga.title,
                source,
                chapter.name,
                chapter.scanlator,
                cleanedImageName,
            )?.openInputStream()?.use { BitmapFactory.decodeStream(it) }
        } catch (e: Throwable) {
            logcat(LogPriority.WARN, e) {
                "TachiyomiAT failed to load cleaned image for resume: cleaned=$cleanedImageName"
            }
            null
        }
    }

    private fun persistCleanedBitmap(
        pageTranslation: PageTranslation,
        cleanedBitmap: Bitmap,
        companionDir: UniFile?,
        pageKey: String,
    ): Boolean {
        val safeName = pageKey.substringAfterLast('/').replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val cleanedFileName = "${safeName}.cleaned.png"
        val cleanedFile = companionDir?.createFile(cleanedFileName)
        return if (cleanedFile != null) {
            cleanedFile.openOutputStream().use { os ->
                // Log PNG encode time to inform a possible encode-format switch.
                val encodeStart = System.nanoTime()
                cleanedBitmap.compress(Bitmap.CompressFormat.PNG, 100, os)
                logcat(LogPriority.INFO) {
                    "[inpaint_encode] $pageKey elapsedMs=${(System.nanoTime() - encodeStart) / 1_000_000}"
                }
            }
            pageTranslation.cleanedImageName = cleanedFileName
            pageTranslation.inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION
            pageTranslation.inpaintStatus = StageStatus.READY
            pageTranslation.errorMessage = null
            true
        } else {
            pageTranslation.inpaintStatus = StageStatus.FAILED
            // TachiyomiAT: storage failure on the cleaned-image write — the
            // inpaint itself succeeded (we have the bitmap) but could not be
            // persisted, so this is the first terminal stage. recordAttemptFailure
            // charges the attempt once (replaces bare retryCount++).
            pageTranslation.recordAttemptFailure()
            pageTranslation.errorMessage =
                "Could not save cleaned image — translation output folder is unavailable. " +
                    "Grant storage permission to the app and retry."
            logcat(LogPriority.ERROR) {
                "Could not create cleaned image file for $pageKey " +
                    "(companionDir=${companionDir == null}); inpaint marked FAILED"
            }
            false
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
    ) {
        pageTranslation.sourceFileName = pageKey
        pageTranslation.ocrStatus = StageStatus.READY
        pageTranslation.translationStatus = StageStatus.READY
        pageTranslation.renderStatus = StageStatus.RUNNING
        pageTranslation.errorMessage = null
        store.updatePage(pageKey) {
            (it ?: pageTranslation.copyForResume()).apply {
                sourceFileName = pageKey
                renderStatus = StageStatus.RUNNING
                errorMessage = null
                updatedAt = System.currentTimeMillis()
            }
        }
        try {
            val renderer = PageTextRenderer(context)
            RenderColorEstimator.recomputeFor(cleanedBitmap, pageTranslation.blocks)
            val renderedBitmap = renderer.render(cleanedBitmap, pageTranslation.blocks, pageTranslation.decodeSampleSize)
            try {
                val companionDir = provider.getCompanionImageDir(
                    manga.title,
                    source,
                    chapter.name,
                    chapter.scanlator,
                )
                persistRenderedBitmap(
                    pageTranslation = pageTranslation,
                    renderedBitmap = renderedBitmap,
                    companionDir = companionDir,
                    pageKey = pageKey,
                    successMessageSuffix = successMessageSuffix,
                )
            } finally {
                if (renderedBitmap !== cleanedBitmap) {
                    try { renderedBitmap.recycle() } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            pageTranslation.renderStatus = StageStatus.FAILED
            // TachiyomiAT: render failed on a cleaned bitmap (inpaint succeeded),
            // so this is the first terminal stage — owns the attempt charge.
            pageTranslation.recordAttemptFailure()
            pageTranslation.errorMessage = e.message
            logcat(LogPriority.ERROR, e) { "Failed to render resumed page $pageKey" }
        } finally {
            try { cleanedBitmap.recycle() } catch (_: Exception) {}
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
    ) {
        pageTranslation.sourceFileName = pageKey
        pageTranslation.ocrStatus = StageStatus.READY
        pageTranslation.translationStatus = StageStatus.READY
        pageTranslation.inpaintStatus = StageStatus.RUNNING
        pageTranslation.renderStatus = StageStatus.PENDING
        pageTranslation.renderedImageName = null
        pageTranslation.errorMessage = null
        store.updatePage(pageKey) {
            (it ?: pageTranslation.copyForResume()).apply {
                sourceFileName = pageKey
                inpaintStatus = StageStatus.RUNNING
                renderStatus = StageStatus.PENDING
                renderedImageName = null
                errorMessage = null
                updatedAt = System.currentTimeMillis()
            }
        }

        val cleaned = try {
            recognitionEngine.inpaint(bitmap, pageTranslation)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            pageTranslation.inpaintStatus = StageStatus.FAILED
            // TachiyomiAT: first terminal stage in this attempt — owns the
            // charge. recordAttemptFailure (replaces bare retryCount++) keeps
            // one failure = one attempt, so a transient inpaint failure no
            // longer double-counts with the render-block path below.
            pageTranslation.recordAttemptFailure()
            pageTranslation.errorMessage = e.message
            logcat(LogPriority.ERROR, e) { "Failed to resume inpaint for $pageKey" }
            null
        }

        if (cleaned == null) {
            pageTranslation.renderStatus = StageStatus.FAILED
            // TachiyomiAT: consequence of the inpaint failure above; the attempt
            // was already charged. recordAttemptFailure no-ops once a stage is
            // already FAILED, preserving one-failure-one-attempt.
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
        if (!persistCleanedBitmap(pageTranslation, cleaned, companionDir, pageKey)) {
            try { cleaned.recycle() } catch (_: Exception) {}
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
        // If the page already has a cleaned bitmap there is nothing to retry.
        if (pageTranslation.cleanedBitmap != null) return pageTranslation
        val retrySampleSize = (pageTranslation.decodeSampleSize * 2).coerceAtMost(8)
        if (retrySampleSize == pageTranslation.decodeSampleSize) {
            // Already at the cap; can't downscale further.
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
            // Re-run the full pipeline on the smaller bitmap. analyze()+inpaint
            // both run; we only need the cleaned bitmap from this, the original
            // pageTranslation's OCR/translation results are already good.
            val retryTranslation = try {
                recognitionEngine.recognize(retryBitmap)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                logcat(LogPriority.WARN, e) { "Inpaint-retry recognize failed: $pageKey" }
                return pageTranslation
            }
            val cleaned = retryTranslation.cleanedBitmap
            if (cleaned != null) {
                // Scale the cleaned bitmap back up to the original decode
                // dimensions. render() sizes text from the block coordinates,
                // which are in the original decode's coordinate space; drawing
                // the smaller cleaned bitmap then rendering original-scale text
                // on it would misalign. Use decoded.originalWidth/Height (not
                // decoded.bitmap.width — by the time the batch render step runs,
                // the original bitmap has already been recycled by its finally).
                val targetW = decoded.originalWidth / pageTranslation.decodeSampleSize
                val targetH = decoded.originalHeight / pageTranslation.decodeSampleSize
                val scaledCleaned = if (cleaned.width == targetW && cleaned.height == targetH) {
                    cleaned
                } else {
                    val s = Bitmap.createScaledBitmap(cleaned, targetW, targetH, true)
                    if (s !== cleaned) try { cleaned.recycle() } catch (_: Exception) {}
                    s
                }
                pageTranslation.cleanedBitmap = scaledCleaned
                pageTranslation.inpaintStatus = StageStatus.READY
                pageTranslation.errorMessage = null
            }
            retryTranslation.cleanedBitmap = null // we own it now
        } finally {
            try { retryBitmap.recycle() } catch (_: Exception) {}
            BitmapPool.releaseAll()
        }
        return pageTranslation
    }

    private fun persistRenderedBitmap(
        pageTranslation: PageTranslation,
        renderedBitmap: Bitmap,
        companionDir: UniFile?,
        pageKey: String,
        successMessageSuffix: String = "",
        successErrorMessage: String? = null,
    ) {
        val safeName = pageKey.substringAfterLast('/').replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val quality = when (pageTranslation.renderQuality) {
            RenderQuality.SIZE_LIMITED -> RenderQuality.SIZE_LIMITED
            else -> RenderQuality.FULL
        }
        if (pageTranslation.decodeSampleSize > 1 && quality != RenderQuality.SIZE_LIMITED) {
            pageTranslation.renderStatus = StageStatus.FAILED
            // TachiyomiAT: render-stage refusal on a heap-downsampled image —
            // inpaint succeeded, so this is the first terminal stage.
            pageTranslation.recordAttemptFailure()
            pageTranslation.errorMessage =
                "Refused to save heap-downsampled translated image. Retry when memory recovers."
            logcat(LogPriority.ERROR) {
                "Refused rendered save for $pageKey: sample=${pageTranslation.decodeSampleSize} " +
                    "quality=${pageTranslation.renderQuality}"
            }
            return
        }
        val renderedFileName = "${safeName}.rendered.png"
        val renderedFile = companionDir?.createFile(renderedFileName)
        if (renderedFile != null) {
            renderedFile.openOutputStream().use { os ->
                val encodeStart = System.nanoTime()
                renderedBitmap.compress(Bitmap.CompressFormat.PNG, 100, os)
                logcat(LogPriority.INFO) {
                    "[render_encode] $pageKey elapsedMs=${(System.nanoTime() - encodeStart) / 1_000_000}"
                }
            }
            pageTranslation.renderedImageName = renderedFileName
            pageTranslation.renderQuality = quality
            pageTranslation.renderedWidth = renderedBitmap.width
            pageTranslation.renderedHeight = renderedBitmap.height
            if (pageTranslation.cleanedImageName != null || pageTranslation.blocks.isEmpty()) {
                pageTranslation.inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION
            }
            pageTranslation.renderRevision++
            pageTranslation.renderStatus = StageStatus.READY
            if (successErrorMessage != null) {
                pageTranslation.errorMessage = successErrorMessage
            }
            logcat(LogPriority.INFO) {
                "Saved rendered image: $renderedFileName for $pageKey$successMessageSuffix " +
                    "dims=${renderedBitmap.width}x${renderedBitmap.height} quality=$quality"
            }
        } else {
            pageTranslation.renderStatus = StageStatus.FAILED
            // TachiyomiAT: rendered-image storage failure — inpaint succeeded,
            // so render is the first terminal stage. recordAttemptFailure
            // replaces bare retryCount++.
            pageTranslation.recordAttemptFailure()
            pageTranslation.errorMessage =
                "Could not save translated image — translation output folder is unavailable. " +
                    "Grant storage permission to the app and retry."
            logcat(LogPriority.ERROR) {
                "Could not create rendered image file for $pageKey " +
                    "(companionDir=${companionDir == null}); render marked FAILED"
            }
        }
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
    ): PageTranslation {
        val pageStart = System.nanoTime()
        var pageTranslation: PageTranslation
        val finalSampleSize = decoded.sampleSize
        try {
            preflightAnalyzeGate(bitmap, fileName)
            // analyze = detect + OCR only (no inpaint). Populates blocks +
            // allTextDetections; leaves cleanedBitmap null.
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
                store.updatePage(fileName) {
                    (it ?: PageTranslation()).apply {
                        errorMessage = "ONNX recognition failed due to memory pressure. Retry after memory recovers."
                    }
                }
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
        pageTranslation.renderQuality = when (decoded.decodeDecision.kind) {
            DecodeDecisionKind.SOURCE_TOO_LARGE -> RenderQuality.SIZE_LIMITED
            else -> RenderQuality.FULL
        }
        pageTranslation.originalImgWidth = decoded.originalWidth.toFloat()
        pageTranslation.originalImgHeight = decoded.originalHeight.toFloat()
        pageTranslation.sourceFileName = fileName
        // analyze() sets ocrStatus=READY; inpaint is still pending until stage 2.
        pageTranslation.inpaintStatus = StageStatus.PENDING
        pageTranslation.updatedAt = System.currentTimeMillis()
        logcat(LogPriority.INFO) {
            "[translation_analyze] $fileName blocks=${pageTranslation.blocks.size} " +
                "engine=${pageTranslation.recognitionEngine} sample=${pageTranslation.decodeSampleSize} " +
                "elapsedMs=${(System.nanoTime() - pageStart) / 1_000_000}"
        }
        // Persist the recognized blocks + the durable inpaint mask so the stage
        // is resumable: a later run skips pages whose ocrStatus is already READY
        // with non-empty blocks AND a mask matching the current inpaint revision.
        // The mask is what makes detector-only + watermark regions still get
        // erased after a resume/reopen (see PageInpaintingPlanner + contract #14).
        store.updatePage(fileName) { existing ->
            (existing ?: pageTranslation).apply {
                sourceFileName = fileName
                blocks = pageTranslation.blocks
                allTextDetections = pageTranslation.allTextDetections
                inpaintMaskBoxes = pageTranslation.inpaintMaskBoxes
                ocrStatus = pageTranslation.ocrStatus
                inpaintStatus = pageTranslation.inpaintStatus
                decodeSampleSize = pageTranslation.decodeSampleSize
                renderQuality = pageTranslation.renderQuality
                originalImgWidth = pageTranslation.originalImgWidth
                originalImgHeight = pageTranslation.originalImgHeight
                updatedAt = System.currentTimeMillis()
            }
        }
        return pageTranslation
    }

    /**
     * TachiyomiAT: STAGE 2 of the staged batch pipeline — inpaint only.
     *
     * Re-decoded [bitmap] + the analyzed [pageTranslation] (blocks +
     * allTextDetections) → cleaned bitmap, persisted to the companion image dir.
     * Mirrors the inpaint half of [processSinglePage] and the standalone
     * resume path [resumeInpaintAndRender]. The caller recycles the bitmap.
     *
     * Sets inpaintStatus=READY + cleanedImageName on success; FAILED on storage
     * failure. The cleanedBitmap on the returned translation stays alive for the
     * downstream render stage (caller draws translated text onto it).
     */
    private suspend fun inpaintPage(
        fileName: String,
        bitmap: Bitmap,
        pageTranslation: PageTranslation,
        store: ChapterTranslationStore,
        ensureCompanionDir: suspend () -> UniFile?,
    ): PageTranslation {
        try {
            pageTranslation.inpaintStatus = StageStatus.RUNNING
            pageTranslation.updatedAt = System.currentTimeMillis()
            store.updatePage(fileName) {
                (it ?: pageTranslation).apply {
                    inpaintStatus = StageStatus.RUNNING
                    updatedAt = System.currentTimeMillis()
                }
            }
            // inpaint reads allTextDetections + blocks from pageTranslation and
            // returns the text-removed (cleaned) bitmap, or null on failure /
            // closed engine.
            pageTranslation.cleanedBitmap = recognitionEngine.inpaint(bitmap, pageTranslation)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            pageTranslation.inpaintStatus = StageStatus.FAILED
            // TachiyomiAT: inpaint exception in the batch stage-2 path — first
            // terminal stage, owns the attempt charge. Replaces bare retryCount++
            // (one failure = one attempt; downstream render is gated out by the
            // translation/READY/PARTIAL check so there is no double-count here).
            pageTranslation.recordAttemptFailure()
            pageTranslation.errorMessage = e.message
            logcat(LogPriority.ERROR, e) { "Failed to inpaint page $fileName" }
            store.updatePage(fileName) {
                (it ?: pageTranslation).apply {
                    inpaintStatus = StageStatus.FAILED
                    updatedAt = System.currentTimeMillis()
                }
            }
            return pageTranslation
        }

        if (pageTranslation.cleanedBitmap != null) {
            val cDir = ensureCompanionDir()
            val safeName = fileName.substringAfterLast('/').replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val cleanedFileName = "${safeName}.cleaned.png"
            val cleanedFile = cDir?.createFile(cleanedFileName)
            if (cleanedFile != null) {
                cleanedFile.openOutputStream().use { os ->
                    val encodeStart = System.nanoTime()
                    pageTranslation.cleanedBitmap!!.compress(Bitmap.CompressFormat.PNG, 100, os)
                    logcat(LogPriority.INFO) {
                        "[inpaint_encode] $fileName elapsedMs=${(System.nanoTime() - encodeStart) / 1_000_000}"
                    }
                }
                pageTranslation.cleanedImageName = cleanedFileName
                pageTranslation.inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION
                pageTranslation.inpaintStatus = StageStatus.READY
            } else {
                pageTranslation.inpaintStatus = StageStatus.FAILED
                // TachiyomiAT: batch inpaint produced a bitmap but the cleaned
                // image could not be persisted — first terminal stage. Charges
                // the attempt once (idempotent if a prior stage already failed).
                pageTranslation.recordAttemptFailure()
                pageTranslation.errorMessage =
                    "Could not save cleaned image — translation output folder is unavailable. " +
                        "Grant storage permission to the app and retry."
                logcat(LogPriority.ERROR) {
                    "Could not create cleaned image file for $fileName (cDir=${cDir == null}); inpaint FAILED"
                }
            }
        } else {
            // No cleaned bitmap (inpaint returned null): textless page or failure
            // already recorded on pageTranslation. Leave inpaintStatus as set by
            // inpaint() (READY for textless, FAILED otherwise).
        }
        pageTranslation.updatedAt = System.currentTimeMillis()
        store.updatePage(fileName) {
            (it ?: pageTranslation).apply {
                cleanedImageName = pageTranslation.cleanedImageName
                inpaintRevision = pageTranslation.inpaintRevision
                inpaintStatus = pageTranslation.inpaintStatus
                errorMessage = pageTranslation.errorMessage
                updatedAt = System.currentTimeMillis()
            }
        }
        return pageTranslation
    }

    private suspend fun processSinglePage(
        fileName: String,
        bitmap: Bitmap,
        decoded: DecodedPage,
        store: ChapterTranslationStore,
        ensureCompanionDir: suspend () -> UniFile?,
    ): PageTranslation {
        val pageStart = System.nanoTime()
        var pageTranslation: PageTranslation
        val finalSampleSize = decoded.sampleSize
        try {
            preflightAnalyzeGate(bitmap, fileName)
            preflightInpaintGate(bitmap, fileName)
            pageTranslation = recognitionEngine.recognize(bitmap)
            consecutiveOomCount = 0
        } catch (deferred: LowMemoryRecognitionDeferredException) {
            logcat(LogPriority.WARN) {
                "Low memory deferred recognizing/inpainting $fileName: ${deferred.message}"
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
            handleCriticalTranslationOom("recognizing/inpainting $fileName", oom)
            consecutiveOomCount++
            logcat(LogPriority.ERROR, oom) {
                "Out of memory recognizing/inpainting $fileName (oomCount=$consecutiveOomCount)"
            }
            pageTranslation = createFailedPagePlaceholder(
                fileName,
                "Low memory during recognition/inpainting. Released translation caches; retry when memory recovers.",
                imgWidth = bitmap.width.toFloat(),
                imgHeight = bitmap.height.toFloat(),
                originalImgWidth = decoded.originalWidth.toFloat(),
                originalImgHeight = decoded.originalHeight.toFloat(),
                decodeSampleSize = decoded.sampleSize,
                retryCount = 1,
            )
            // Repeated OOMs should stop this page instead of changing the block
            // detector for later pages.
            if (consecutiveOomCount >= 2) {
                logcat(LogPriority.WARN) {
                    "ONNX recognition hit ${consecutiveOomCount} consecutive OOMs; not switching geometry engines"
                }
                store.updatePage(fileName) {
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
        pageTranslation.decodeSampleSize = finalSampleSize
        pageTranslation.renderQuality = when (decoded.decodeDecision.kind) {
            DecodeDecisionKind.SOURCE_TOO_LARGE -> RenderQuality.SIZE_LIMITED
            else -> RenderQuality.FULL
        }
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

        // TachiyomiAT: cooperative cancellation checkpoint. recognize()/inpaint()
        // are suspending but their internals (ONNX native calls) can't observe a
        // cancel, so a chapter-switch or reader-exit cancel issued during those
        // calls only lands at the next suspend point. Drop out here if the job
        // was cancelled mid-recognition so we don't burn cycles rendering/text-
        // translating/persisting a page the caller no longer wants — the
        // stranded-status reset in translatePage's finally will clean up the store.
        coroutineContext.ensureActive()

        if (pageTranslation.cleanedBitmap != null) {
            // Save the cleaned companion image but keep cleanedBitmap alive
            // so the render stage downstream can draw translated text onto it.
            val cDir = ensureCompanionDir()
            val safeName = fileName.substringAfterLast('/').replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val cleanedFileName = "${safeName}.cleaned.png"
            val cleanedFile = cDir?.createFile(cleanedFileName)
            if (cleanedFile != null) {
                cleanedFile.openOutputStream().use { os ->
                    pageTranslation.cleanedBitmap!!.compress(Bitmap.CompressFormat.PNG, 100, os)
                }
                pageTranslation.cleanedImageName = cleanedFileName
                pageTranslation.inpaintRevision = PageTranslation.CURRENT_INPAINT_REVISION
                pageTranslation.inpaintStatus = StageStatus.READY
            } else {
                // TachiyomiAT: a null cDir/createFile failure used to leave
                // inpaintStatus non-terminal and cleanedImageName unset, so the
                // downstream render had no cleaned bitmap to draw on and the
                // page silently showed the original. Surface the storage failure
                // so it's distinguishable from "no text detected".
                pageTranslation.inpaintStatus = StageStatus.FAILED
                pageTranslation.errorMessage =
                    "Could not save cleaned image — translation output folder is unavailable. " +
                    "Grant storage permission to the app and retry."
                logcat(LogPriority.ERROR) {
                    "Could not create cleaned image file for $fileName " +
                        "(cDir=${cDir == null}); inpaint marked FAILED"
                }
            }
        }

        return pageTranslation
    }

    fun forceReleaseNativeBuffers() {
        try { recognitionEngine.forceReleaseNativeBuffers() } catch (_: Exception) {}
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

    private fun recoverHeapAfterOnnxPressure(fileName: String) {
        BitmapPool.releaseAll()
        try { recognitionEngine.forceReleaseNativeBuffers() } catch (e: Throwable) {
            logcat(LogPriority.WARN, e) { "forceReleaseNativeBuffers threw during ONNX heap recovery for $fileName" }
        }
        System.gc()
        logcat(LogPriority.WARN) {
            "Released bitmap pools + engine native buffers after ONNX memory pressure for $fileName"
        }
    }

    private fun reclaimTranslationMemory(reason: String, trimImageCache: Boolean) {
        val before = TranslationMemoryBudget.snapshot()
        BitmapPool.releaseAll()
        try { recognitionEngine.forceReleaseNativeBuffers() } catch (e: Throwable) {
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
    ) {
        try {
            if (TranslationMemoryBudget.isCriticalHeap()) {
                handleCriticalTranslationOom("persisting $fileName", OutOfMemoryError("critical heap before persist"))
            }
            store.updatePage(fileName) { pageTranslation }
        } catch (oom: OutOfMemoryError) {
            handleCriticalTranslationOom("persisting $fileName", oom)
            pageTranslation.cleanedBitmap = null
            try {
                store.updatePage(fileName) { pageTranslation }
            } catch (retryOom: OutOfMemoryError) {
                handleCriticalTranslationOom("persisting lightweight failure $fileName", retryOom)
                store.updatePage(fileName) {
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

    private fun decodePageBitmapForTranslation(fileName: String, streamFn: () -> InputStream): DecodedPage? {
        val buffered: ByteArray = try {
            streamFn().use { it.readBytes() }
        } catch (oom: OutOfMemoryError) {
            reclaimTranslationMemory("decode bounds $fileName", trimImageCache = true)
            logcat(LogPriority.ERROR, oom) { "Out of memory reading page bytes for $fileName" }
            return null
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Failed reading page bytes for $fileName" }
            return null
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            java.io.ByteArrayInputStream(buffered).use { BitmapFactory.decodeStream(it, null, bounds) }
        } catch (oom: OutOfMemoryError) {
            reclaimTranslationMemory("decode bounds $fileName", trimImageCache = true)
            logcat(LogPriority.ERROR, oom) { "Out of memory reading page bounds for $fileName" }
            return null
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Failed reading page bounds for $fileName" }
            return null
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

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
        } ?: return null

        return DecodedPage(
            bitmap = bitmap,
            sampleSize = decision.sampleSize,
            originalWidth = bounds.outWidth,
            originalHeight = bounds.outHeight,
            decodeDecision = decision,
            sourceBytesSize = buffered.size.toLong(),
        )
    }

    private fun decodePageBitmap(fileName: String, streamFn: () -> InputStream): DecodedPage? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val buffered: ByteArray = try {
            streamFn().use { it.readBytes() }
        } catch (oom: OutOfMemoryError) {
            BitmapPool.releaseAll()
            forceReleaseNativeBuffers()
            System.gc()
            logcat(LogPriority.ERROR, oom) { "Out of memory buffering page bytes for $fileName" }
            return null
        }
        java.io.ByteArrayInputStream(buffered).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val sampleSize = TranslationMemoryBudget.chooseDecodeSampleSize(buffered.size.toLong(), bounds.outWidth, bounds.outHeight)
        TranslationMemoryBudget.logSnapshot(
            tag = "decode",
            width = bounds.outWidth,
            height = bounds.outHeight,
            extra = "sample=$sampleSize file=$fileName",
        )
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = sampleSize
        }
        val bitmap = try {
            java.io.ByteArrayInputStream(buffered).use { BitmapFactory.decodeStream(it, null, options) }
        } catch (oom: OutOfMemoryError) {
            BitmapPool.releaseAll()
            forceReleaseNativeBuffers()
            System.gc()
            logcat(LogPriority.ERROR, oom) { "Out of memory decoding bitmap for $fileName" }
            return null
        } ?: return null
        return DecodedPage(
            bitmap = bitmap,
            sampleSize = sampleSize,
            originalWidth = bounds.outWidth,
            originalHeight = bounds.outHeight,
            decodeDecision = TranslationMemoryBudget.chooseDecodeDecision(bounds.outWidth, bounds.outHeight, buffered.size.toLong()),
            sourceBytesSize = buffered.size.toLong(),
        )
    }

    private data class DecodedPage(
        val bitmap: Bitmap,
        val sampleSize: Int,
        val originalWidth: Int,
        val originalHeight: Int,
        val decodeDecision: DecodeDecision,
        val sourceBytesSize: Long,
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
        "Low memory translating $fileName: $reason (page=${width}x$height). Retry when memory recovers."
    )

    private fun getChapterPages(chapterPath: UniFile): List<Pair<String, () -> InputStream>> {
        if (chapterPath.isFile) {
            chapterPath.archiveReader(context).use { reader ->
                return reader.useEntries { entries ->
                    entries.filter { entry ->
                        // getInputStream return null; ImageUtil.isImage itself
                        // handles a null name. Skip unreadable entries instead
                        // of NPE'ing on the `!!` that used to be here.
                        entry.isFile &&
                            ImageUtil.isImage(entry.name) {
                                reader.getInputStream(entry.name)
                                    ?: throw java.io.IOException("Archive entry '${entry.name}' could not be opened")
                            }
                    }
                        .sortedWith { f1, f2 -> f1.name.compareToCaseInsensitiveNaturalOrder(f2.name) }.map { entry ->
                            Pair(entry.name) {
                                chapterPath.archiveReader(context).use { archive ->
                                    // Null-safe stream: if the entry vanished or
                                    // the archive is corrupt, throw an explicit,
                                    // loggable IOException instead of an NPE so
                                    // the caller's try/catch reports the real cause.
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
            // listFiles() returns null on I/O error or a revoked SAF tree URI;
            // return an empty list (the caller treats "no pages" as a clean
            // no-op) instead of NPE'ing.
            val files = chapterPath.listFiles() ?: run {
                logcat(LogPriority.WARN) {
                    "TachiyomiAT getChapterPages: listFiles() returned null for ${chapterPath.filePath}"
                }
                return emptyList()
            }
            return files.mapNotNull { entry ->
                // entry.name is nullable on some SAF providers; skip nameless
                // entries instead of NPE'ing on entry.name!!.
                val name = entry.name ?: return@mapNotNull null
                if (!ImageUtil.isImage(name)) return@mapNotNull null
                Pair(name) { entry.openInputStream() }
            }.sortedWith { f1, f2 -> f1.first.compareToCaseInsensitiveNaturalOrder(f2.first) }.toList()
        }
    }
}
