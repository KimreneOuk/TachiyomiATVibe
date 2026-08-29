package eu.kanade.translation.pipeline

import android.content.Context
import eu.kanade.translation.inpainting.InpaintingMode
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.ocr.OcrModelCatalog
import eu.kanade.translation.ocr.TextRecognizerLanguage
import eu.kanade.translation.recognition.PageRecognitionEngine
import eu.kanade.translation.recognition.RoiPageRecognitionEngine
import eu.kanade.translation.scheduling.NativeRunQuarantine
import eu.kanade.translation.translator.TextTranslator
import eu.kanade.translation.translator.TextTranslatorLanguage
import eu.kanade.translation.translator.TranslationEngineBuilder
import eu.kanade.translation.util.ShortHash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.translation.OcrModel
import tachiyomi.domain.translation.TranslationPreferences

/**
 * Native lane + engine cache moved from `TranslationPipeline` (T909 Phase 10).
 * Owns the sole native-permit admission wrapper, the 8 cached engine fields,
 * the full config signature, the engine factories, and the defensive `init`
 * (invalid config at construction must not crash the eagerly built pipeline).
 * The native run scope/quarantine, the in-flight page-key set, and the
 * [onPageStuck] callback stay pipeline-owned and are injected here.
 */
internal class EngineLane(
    private val context: Context,
    private val translationPreferences: TranslationPreferences,
    private val nativeRunQuarantine: NativeRunQuarantine,
    private val inFlightPageKeys: MutableSet<String>,
    private val onPageStuck: () -> ((chapterId: Long?, pageKey: String) -> Unit)?,
) {

    private data class PermitHolder(val pageKey: String)

    @Volatile
    private var permitHolder: PermitHolder? = null

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

    internal fun inpaintingModeFromPref(): InpaintingMode {
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

    internal fun closeEngines() {
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
    private var enginesClosed = false

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
    internal suspend fun ensureEnginesBuiltFor(
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
}
