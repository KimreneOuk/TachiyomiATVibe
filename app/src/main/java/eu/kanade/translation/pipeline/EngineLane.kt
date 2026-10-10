package eu.kanade.translation.pipeline

import android.content.Context
import androidx.annotation.VisibleForTesting
import eu.kanade.translation.engines.inpainting.InpaintStampDecision
import eu.kanade.translation.engines.inpainting.InpaintingMode
import eu.kanade.translation.engines.translator.TextTranslator
import eu.kanade.translation.engines.translator.TranslationEngineBuilder
import eu.kanade.translation.engines.vision.ocr.OcrModelCatalog
import eu.kanade.translation.engines.vision.ocr.PageRecognitionEngine
import eu.kanade.translation.engines.vision.ocr.RoiPageRecognitionEngine
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.TextRecognizerLanguage
import eu.kanade.translation.model.TextTranslatorLanguage
import eu.kanade.translation.pipeline.execution.NativeRunQuarantine
import eu.kanade.translation.util.ShortHash
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.translation.NeuralInpaintModel
import tachiyomi.domain.translation.OcrModel
import tachiyomi.domain.translation.PaddleOcrExecutionProvider
import tachiyomi.domain.translation.PaddleOcrRecognitionBatch
import tachiyomi.domain.translation.TranslationPreferences
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Owns native-engine creation, cached instances, native-permit admission, and
 * engine lifecycle. Translator borrow tracking lets the stop path observe
 * in-flight reader work. [closeEngines] snapshots exact engine references and
 * closes those under the permit after a bounded grace period; a rebuild must
 * never observe the drain closing its new engines. The pipeline owns the
 * native run scope, quarantine, in-flight page set, and [onPageStuck] callback.
 */
internal class EngineLane(
    private val context: Context,
    private val translationPreferences: TranslationPreferences,
    private val nativeRunQuarantine: NativeRunQuarantine,
    private val inFlightPageKeys: MutableSet<String>,
    private val onPageStuck: () -> ((chapterId: Long?, pageKey: String) -> Unit)?,
    // Bounds the translator borrow drain; production uses the native run scope.
    private val drainGraceMs: Long = ENGINE_DRAIN_GRACE_MS,
    private val drainScope: CoroutineScope? = null,
    private val translatorFactory: (TextRecognizerLanguage, TextTranslatorLanguage) -> TextTranslator =
        { fromLang, toLang -> TranslationEngineBuilder.build(translationPreferences, fromLang, toLang) },
    private val testState: TestState? = null,
) {

    @VisibleForTesting
    internal data class TestState(
        val fromLang: TextRecognizerLanguage,
        val ocrModel: OcrModel,
        val readingOrder: tachiyomi.domain.translation.TranslationReadingOrder,
        val inpaintingMode: InpaintingMode,
        val translator: TextTranslator,
        val recognitionEngine: PageRecognitionEngine,
        val translatorSignature: EngineSignature,
        val paddleOcrProvider: PaddleOcrExecutionProvider = PaddleOcrExecutionProvider.CPU,
        val paddleOcrBatch: PaddleOcrRecognitionBatch = PaddleOcrRecognitionBatch.B1,
        val neuralInpaintModel: NeuralInpaintModel = NeuralInpaintModel.DEFAULT,
    )

    internal companion object {
        @VisibleForTesting
        internal fun createForTesting(
            context: Context,
            translationPreferences: TranslationPreferences,
            nativeRunQuarantine: NativeRunQuarantine,
            inFlightPageKeys: MutableSet<String>,
            onPageStuck: () -> ((chapterId: Long?, pageKey: String) -> Unit)?,
            drainGraceMs: Long,
            drainScope: CoroutineScope,
            translatorFactory: (TextRecognizerLanguage, TextTranslatorLanguage) -> TextTranslator,
            state: TestState,
        ): EngineLane = EngineLane(
            context = context,
            translationPreferences = translationPreferences,
            nativeRunQuarantine = nativeRunQuarantine,
            inFlightPageKeys = inFlightPageKeys,
            onPageStuck = onPageStuck,
            drainGraceMs = drainGraceMs,
            drainScope = drainScope,
            translatorFactory = translatorFactory,
            testState = state,
        )

        @VisibleForTesting
        internal fun shouldRebuildRecognitionForInpainting(
            oldMode: InpaintingMode,
            newMode: InpaintingMode,
            oldModel: NeuralInpaintModel,
            newModel: NeuralInpaintModel,
        ): Boolean = oldMode != newMode || oldModel != newModel

        /**
         * Engine teardown uses a short drain grace. If it expires, the epoch
         * guard retries the racing page against a rebuilt translator exactly
         * once. Waiting for the full provider-call budget after Stop would delay
         * native-memory release, so bounded memory takes priority over the rare
         * extra paid call. Provider draining uses a longer budget aligned with
         * its call chain.
         */
        const val ENGINE_DRAIN_GRACE_MS = 5_000L
    }

    private data class PermitHolder(val pageKey: String)

    @Volatile
    private var permitHolder: PermitHolder? = null

    // ------------------------------------------------------------------
    // Engine epoch and translator borrow registry.
    // ------------------------------------------------------------------

    /**
     * Bumped ONLY by [closeEngines] — the moment the cached instances are (or may
     * soon be) torn down. Rebuilds do NOT bump it: they are permit-ordered under
     * the rebuild mutex and produce a coherent new pair. Distinct from the
     * quarantine's `generation` and the scheduler's `chapterCancellationEpochs`.
     */
    private val engineEpoch = AtomicLong(0L)

    /** Current engine epoch; captured next to `textTranslator` at the borrow site. */
    internal fun currentEngineEpoch(): Long = engineEpoch.get()

    /**
     * Number of in-flight translator borrows (the single page boundary
     * [eu.kanade.translation.pipeline.SinglePageHttpRenderPhase]
     * `translateSinglePageHttpRender`). This is what makes "in-flight reader
     * work" observable to the stop path; bounded to one int.
     */
    private val translatorUseCount = AtomicInteger(0)

    /** Signalled (event-driven, no polling) whenever the count drops back to zero. */
    @Volatile
    private var translatorUseDrained: CompletableDeferred<Unit>? = null

    internal fun beginTranslatorUse() {
        translatorUseCount.incrementAndGet()
    }

    internal fun endTranslatorUse() {
        if (translatorUseCount.decrementAndGet() == 0) {
            translatorUseDrained?.complete(Unit)
        }
    }

    internal fun permitHolderPageKeySnapshot(): String? = permitHolder?.pageKey

    internal suspend fun <T> withNativeLane(
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
                        onPageStuck()?.invoke(chapterId, pageKey)
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
    internal var currentFromLang: TextRecognizerLanguage
        private set

    @Volatile
    internal var currentOcrModel: OcrModel
        private set

    // RoiPageRecognitionEngine caches the resolved reading order once per instance,
    // so a runtime flip requires a recognition rebuild — same logic as fromLang/ocrModel.
    @Volatile
    internal var currentReadingOrder: tachiyomi.domain.translation.TranslationReadingOrder
        private set

    // @Volatile: these are reassigned from a translation coroutine (language change) and
    // read/closed from closeEngines() WITHOUT the permit (stop() on the main thread), so a
    // race must read a consistent reference, not a half-published one.
    @Volatile
    internal var textTranslator: TextTranslator
        private set

    @Volatile
    internal var recognitionEngine: PageRecognitionEngine
        private set

    @Volatile
    internal var currentInpaintingMode: InpaintingMode
        private set

    @Volatile
    internal var currentNeuralInpaintModel: NeuralInpaintModel
        private set

    @Volatile
    internal var currentPaddleOcrProvider: PaddleOcrExecutionProvider = PaddleOcrExecutionProvider.CPU
        private set

    @Volatile
    internal var currentPaddleOcrBatch: PaddleOcrRecognitionBatch = PaddleOcrRecognitionBatch.B1
        private set

    @Volatile
    internal var currentVisionGpu: Boolean = false
        private set

    // Snapshot of EVERY config dimension used to build textTranslator. The rebuild gate
    // compares a fresh signature so changing engine category, provider, key, model, temp,
    // max-tokens, reading-order, or languages forces a rebuild — not just
    // language changes. Without this the cached AI translator (which captures key/model/temp
    // at construction and never re-reads prefs) survives a stop+reconfigure+restart. readingOrder
    // MUST be included because it's cached at construction too. apiKeyHash is a
    // short non-reversible digest so secrets are never stored/logged.
    @Volatile
    internal var currentTranslatorSignature: EngineSignature
        private set

    /**
     * Captures the full set of preferences that determine which [TextTranslator]
     * gets built. Two equal signatures guarantee the cached translator reflects
     * exactly this configuration; any difference means a rebuild is required.
     */
    internal data class EngineSignature(
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
        val testState = testState
        if (testState != null) {
            currentFromLang = testState.fromLang
            currentOcrModel = testState.ocrModel
            currentReadingOrder = testState.readingOrder
            currentInpaintingMode = testState.inpaintingMode
            currentNeuralInpaintModel = testState.neuralInpaintModel
            textTranslator = testState.translator
            recognitionEngine = testState.recognitionEngine
            currentTranslatorSignature = testState.translatorSignature
            currentPaddleOcrProvider = testState.paddleOcrProvider
            currentPaddleOcrBatch = testState.paddleOcrBatch
        } else {
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
                currentNeuralInpaintModel = neuralInpaintModelFromPref()
                currentReadingOrder = translationPreferences.translationReadingOrder().get()
                currentPaddleOcrProvider = translationPreferences.paddleOcrExecutionProvider().get()
                currentPaddleOcrBatch = translationPreferences.paddleOcrRecognitionBatch().get()
                recognitionEngine = createRecognitionEngine(
                    fromLang,
                    ocrModel,
                    currentInpaintingMode,
                    currentNeuralInpaintModel,
                )
                textTranslator = TranslationEngineBuilder.build(translationPreferences, fromLang, toLang)
                currentTranslatorSignature = computeTranslatorSignature(fromLang, toLang)
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) {
                    "TachiyomiAT pipeline init: invalid translation config, deferring to first translate"
                }
                currentFromLang = TextRecognizerLanguage.JAPANESE
                currentOcrModel = OcrModel.MLKIT
                currentInpaintingMode = InpaintingMode.FAST
                currentNeuralInpaintModel = NeuralInpaintModel.DEFAULT
                currentReadingOrder = tachiyomi.domain.translation.TranslationReadingOrder.AUTO
                currentPaddleOcrProvider = PaddleOcrExecutionProvider.CPU
                currentPaddleOcrBatch = PaddleOcrRecognitionBatch.B1
                recognitionEngine = object : PageRecognitionEngine {
                    override suspend fun analyze(bitmap: android.graphics.Bitmap): PageTranslation {
                        throw IllegalStateException("Recognition engine not initialized (models not installed)")
                    }
                    override fun close() {}
                }
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
    }

    internal fun inpaintingModeFromPref(): InpaintingMode =
        InpaintingMode.fromPref(translationPreferences.translationInpaintingMode().get())

    internal fun neuralInpaintModelFromPref(): NeuralInpaintModel =
        translationPreferences.translationInpaintingNeuralModel().get()

    /** Neural availability is unknown until the recognition engine is built. */
    internal fun inpaintingStampDecision(): InpaintStampDecision {
        val mode = inpaintingModeFromPref()
        val neuralReady = (recognitionEngine as? RoiPageRecognitionEngine)?.neuralInpaintAvailable()
        return InpaintStampDecision(mode, neuralReady)
    }

    private fun createRecognitionEngine(
        lang: TextRecognizerLanguage,
        ocrModel: OcrModel,
        mode: InpaintingMode,
        neuralModel: NeuralInpaintModel,
    ): PageRecognitionEngine {
        if (testState != null) {
            return testState.recognitionEngine
        }
        val onnx = RoiPageRecognitionEngine(context, lang, ocrModel, mode, neuralModel)
        if (onnx.isAvailable) {
            logcat(LogPriority.INFO) { "Using ONNX recognition engine for $lang with OCR model $ocrModel" }
            return onnx
        }
        onnx.close()
        throw IllegalStateException("ONNX recognition unavailable for $lang/$ocrModel")
    }

    /**
     * Warms up the recognition engine before it enters page processing.
     */
    internal suspend fun warmUp() {
        (recognitionEngine as? RoiPageRecognitionEngine)?.warmUp()
    }

    internal fun closeEngines() {
        // A stop invalidates cached engines immediately, but actual teardown may
        // only happen while no admitted native call is alive. If the lane is
        // occupied, the next admitted call rebuilds after the real native exit.
        inFlightPageKeys.clear()
        enginesClosed = true
        engineEpoch.incrementAndGet()
        // Snapshot the engine references at close time and close those objects —
        // a one-shot drain that
        // fires after a rebuild must never kill the NEW engines. The [enginesClosed]
        // flag stays the rebuild authority.
        val recognitionToClose = recognitionEngine
        val translatorToClose = textTranslator
        if (translatorUseCount.get() == 0 || drainGraceMs <= 0L) {
            // Fast path (idle lane, today's behavior) or a zero/expired-by-config
            // grace: close synchronously. A grace of 0 is a test-only configuration;
            // production's 5 s always takes the drain path below when busy.
            closeEnginesNow(recognitionToClose, translatorToClose)
            return
        }
        val scope = drainScope
        if (scope == null) {
            closeEnginesNow(recognitionToClose, translatorToClose)
            return
        }
        // Bounded NON-BLOCKING borrow drain (stop callers are on the main thread;
        // nothing joins): await the borrow release event, then close the SNAPSHOTTED
        // engines. Grace expiry closes anyway.
        val drained = CompletableDeferred<Unit>()
        translatorUseDrained = drained
        scope.launch {
            var drainedInTime = true
            try {
                withTimeout(drainGraceMs) { drained.await() }
            } catch (_: TimeoutCancellationException) {
                drainedInTime = false
                logcat(LogPriority.WARN) {
                    "TachiyomiAT engine drain grace expired: graceMs=$drainGraceMs — closing the " +
                        "engines under the in-flight borrow (the epoch guard retries the racing page)"
                }
            }
            if (drainedInTime) {
                logcat(LogPriority.INFO) {
                    "TachiyomiAT engine close drained: borrow ended, closing the snapshotted engines"
                }
            }
            closeEnginesNow(recognitionToClose, translatorToClose)
        }
    }

    /** Closes the given engine references when the native lane is momentarily idle. */
    private fun closeEnginesNow(recognitionToClose: PageRecognitionEngine, translatorToClose: TextTranslator) {
        val closedNow = nativeRunQuarantine.tryRunExclusive {
            try {
                recognitionToClose.close()
            } catch (_: Exception) {}
            try {
                translatorToClose.close()
            } catch (_: Exception) {}
        }
        if (!closedNow) {
            logcat(LogPriority.WARN) {
                "TachiyomiAT engine close deferred: reason=native invocation still alive"
            }
        }
    }

    /**
     * Rebuilds only the translator for the epoch guard's single retry. The HTTP
     * translate phase runs outside the native permit and has no native needs,
     * so this repairs only the closed translator. The full recognition and
     * translator rebuild remains the authority for the next admitted invocation;
     * [enginesClosed] is intentionally not reset here. The replacement comes
     * from [translatorFactory] (production:
     * the same `TranslationEngineBuilder` the rebuild gate uses), so the retry
     * never re-uses the closed instance.
     */
    internal suspend fun ensureTranslatorRebuiltForEpochRetry(
        fromLang: TextRecognizerLanguage,
        toLang: TextTranslatorLanguage,
    ) {
        if (!enginesClosed) return
        val retired = textTranslator
        withContext(Dispatchers.IO) {
            try {
                retired.close()
            } catch (_: Exception) {}
        }
        textTranslator = translatorFactory(fromLang, toLang)
        currentTranslatorSignature = computeTranslatorSignature(fromLang, toLang)
        logcat(LogPriority.INFO) {
            "TachiyomiAT D7 epoch retry: rebuilt the closed translator for the in-flight HTTP phase"
        }
    }

    @Volatile
    private var enginesClosed = false

    /**
     * rebuild the recognition engine + text translator when the
     * current configuration (languages, OCR model, engine signature, or a prior
     * closeEngines()) differs from the cached instances. Shared by the per-page
     * path and the staged batch path so both honor the same rebuild gate without
     * duplicating the (subtle) signature/OcrModel logic.
     *
     * Reads every engine-selection preference live and is idempotent: a no-op
     * when the cached instances already match. Call it at the top of each
     * translation entry point before touching [recognitionEngine] / [textTranslator].
     */
    internal suspend fun ensureEnginesBuiltFor(
        fromLang: TextRecognizerLanguage,
        toLang: TextTranslatorLanguage,
        runScopedOcrModel: OcrModel? = null,
    ) {
        val selectedOcrModel = runScopedOcrModel ?: OcrModelCatalog.selectedModel(translationPreferences, fromLang)
        val desiredInpaintingMode = inpaintingModeFromPref()
        val desiredNeuralInpaintModel = neuralInpaintModelFromPref()
        val desiredReadingOrder = translationPreferences.translationReadingOrder().get()
        val rebuildClosedEngines = enginesClosed
        val livePaddleProvider = translationPreferences.paddleOcrExecutionProvider().get()
        val livePaddleBatch = translationPreferences.paddleOcrRecognitionBatch().get()
        val liveVisionGpu = translationPreferences.translationVisionGpuAcceleration().get()
        // Include inpainting mode, reading order, paddle provider/batch, and vision GPU changes
        // so setting adjustments take effect immediately without restart.
        val shouldRebuildRecognition = rebuildClosedEngines ||
            fromLang != currentFromLang ||
            selectedOcrModel != currentOcrModel ||
            shouldRebuildRecognitionForInpainting(
                oldMode = currentInpaintingMode,
                newMode = desiredInpaintingMode,
                oldModel = currentNeuralInpaintModel,
                newModel = desiredNeuralInpaintModel,
            ) ||
            desiredReadingOrder != currentReadingOrder ||
            liveVisionGpu != currentVisionGpu ||
            (
                selectedOcrModel == OcrModel.PADDLEOCR_V6_SMALL &&
                    (
                        livePaddleProvider != currentPaddleOcrProvider ||
                            livePaddleBatch != currentPaddleOcrBatch
                        )
                )
        if (shouldRebuildRecognition) {
            recognitionEngine.close()
            currentFromLang = fromLang
            currentOcrModel = selectedOcrModel
            currentInpaintingMode = desiredInpaintingMode
            currentNeuralInpaintModel = desiredNeuralInpaintModel
            currentReadingOrder = desiredReadingOrder
            currentPaddleOcrProvider = livePaddleProvider
            currentPaddleOcrBatch = livePaddleBatch
            currentVisionGpu = liveVisionGpu
            recognitionEngine = createRecognitionEngine(
                fromLang,
                currentOcrModel,
                currentInpaintingMode,
                currentNeuralInpaintModel,
            )
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
}
