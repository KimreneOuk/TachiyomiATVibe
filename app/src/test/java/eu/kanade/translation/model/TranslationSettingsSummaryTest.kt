package eu.kanade.translation.model

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.translation.AiEngine
import tachiyomi.domain.translation.OcrModel
import tachiyomi.domain.translation.StandardEngine
import tachiyomi.domain.translation.TranslationEngineCategory
import tachiyomi.domain.translation.TranslationPreferences

class TranslationSettingsSummaryTest {

    @Test
    fun `STANDARD engine produces no model id and no token row`() {
        val prefs = preferences(
            category = TranslationEngineCategory.STANDARD,
            standardEngine = StandardEngine.GOOGLE,
            from = "JAPANESE",
            to = "ENGLISH",
        )

        val summary = prefs.snapshotTranslationSummary()

        summary.category shouldBe TranslationEngineCategory.STANDARD
        summary.fromLanguageLabel shouldBe "Japanese"
        summary.toLanguageLabel shouldBe "English"
        summary.engineLabel shouldBe "Google Translate"
        summary.modelId shouldBe null
        summary.maxOutputTokens shouldBe null
    }

    @Test
    fun `STANDARD MLKIT label`() {
        val prefs = preferences(standardEngine = StandardEngine.MLKIT)

        prefs.snapshotTranslationSummary().engineLabel shouldBe "MlKit (On Device)"
    }

    @Test
    fun `AI_MODEL exposes model id and output tokens`() {
        val prefs = preferences(
            category = TranslationEngineCategory.AI_MODEL,
            aiEngine = AiEngine.GEMINI,
            aiModel = "gemini-1.5-pro",
            outputTokens = "8192",
        )

        val summary = prefs.snapshotTranslationSummary()

        summary.category shouldBe TranslationEngineCategory.AI_MODEL
        summary.engineLabel shouldBe "Gemini AI"
        summary.modelId shouldBe "gemini-1.5-pro"
        summary.maxOutputTokens shouldBe "8192"
    }

    @Test
    fun `AI_MODEL with blank model id yields null without failing`() {
        val prefs = preferences(
            category = TranslationEngineCategory.AI_MODEL,
            aiEngine = AiEngine.OPENROUTER,
            aiModel = "",
            outputTokens = "  ",
        )

        val summary = prefs.snapshotTranslationSummary()

        summary.engineLabel shouldBe "OpenRouter"
        summary.modelId shouldBe null
        summary.maxOutputTokens shouldBe null
    }

    @Test
    fun `AI_MODEL DeepSeek and LM Studio resolve`() {
        preferences(
            category = TranslationEngineCategory.AI_MODEL,
            aiEngine = AiEngine.DEEPSEEK,
            aiModel = "deepseek-chat",
        ).snapshotTranslationSummary().engineLabel shouldBe "DeepSeek"

        preferences(
            category = TranslationEngineCategory.AI_MODEL,
            aiEngine = AiEngine.LMSTUDIO,
            aiModel = "local-model",
        ).snapshotTranslationSummary().engineLabel shouldBe "LM Studio"
    }

    @Test
    fun `unknown source language falls back to Chinese label`() {
        val prefs = preferences(from = "DOES_NOT_EXIST")

        val summary = prefs.snapshotTranslationSummary()

        summary.fromLanguageLabel shouldBe "Chinese (trad/sim)"
    }

    @Test
    fun `unknown target language falls back to English label`() {
        val prefs = preferences(to = "NOPE")

        val summary = prefs.snapshotTranslationSummary()

        summary.toLanguageLabel shouldBe "English"
    }

    @Test
    fun `Japanese coerces an incompatible stored OCR model to its default label`() {
        val prefs = preferences(
            from = "JAPANESE",
            ocrModel = OcrModel.PADDLEOCR_V6_SMALL,
        )

        val summary = prefs.snapshotTranslationSummary()

        summary.ocrModelLabel shouldBe "MangaOCR"
    }

    @Test
    fun `incompatible OCR model for the source language coerces to the default label`() {
        // MangaOCR only supports Japanese; stored for Chinese it must coerce to ML Kit.
        val prefs = preferences(
            from = "CHINESE",
            ocrModel = OcrModel.MANGAOCR,
        )

        val summary = prefs.snapshotTranslationSummary()

        summary.ocrModelLabel shouldBe "ML Kit"
    }

    @Test
    fun `inpainting mode is carried through as the raw stored value`() {
        val prefs = preferences(inpaintingMode = "QUALITY")

        prefs.snapshotTranslationSummary().inpaintingMode shouldBe "QUALITY"
    }

    /**
     * Builds a [TranslationPreferences] backed by an [InMemoryPreferenceStore]
     * seeded with the given values. The store snapshots its initial values at
     * construction (its `set()` is not retained across calls), so every value
     * the resolver will read must be provided up front.
     *
     * Enum prefs go through `getEnum` → `getObject`, and the in-memory store
     * keeps objects as-is (no serialization), so enum keys must be seeded with
     * the actual Enum instance — not its `.name` string.
     */
    private fun preferences(
        category: TranslationEngineCategory = TranslationEngineCategory.STANDARD,
        standardEngine: StandardEngine = StandardEngine.MLKIT,
        aiEngine: AiEngine = AiEngine.GEMINI,
        from: String = "CHINESE",
        to: String = "ENGLISH",
        ocrModel: OcrModel = OcrModel.MLKIT,
        aiModel: String = "",
        outputTokens: String = "",
        inpaintingMode: String = "FAST",
    ): TranslationPreferences {
        val ocrKey = "translation_ocr_model_${from.lowercase()}"
        val aiModelKey = when (aiEngine) {
            AiEngine.GEMINI -> "translation_ai_model_gemini"
            AiEngine.OPENROUTER -> "translation_ai_model_openrouter"
            AiEngine.DEEPSEEK -> "translation_ai_model_deepseek"
            AiEngine.LMSTUDIO -> "translation_ai_model_lmstudio"
        }
        // Enum prefs must be seeded as their Enum instance; string prefs as String.
        val seeds: Map<String, Any> = mapOf(
            "translation_engine_category" to category,
            "translation_standard_engine" to standardEngine,
            "translation_ai_engine" to aiEngine,
            "translate_language_from" to from,
            "translate_language_to" to to,
            ocrKey to ocrModel,
            aiModelKey to aiModel,
            "translation_ai_output_tokens" to outputTokens,
            "translation_inpainting_mode" to inpaintingMode,
        )
        val store = InMemoryPreferenceStore(
            seeds.entries.map { (key, value) -> seed(key, value) }.asSequence(),
        )
        return TranslationPreferences(store)
    }

    private fun <T> seed(
        key: String,
        value: T,
    ): InMemoryPreferenceStore.InMemoryPreference<T> =
        InMemoryPreferenceStore.InMemoryPreference(key, value, value)
}
