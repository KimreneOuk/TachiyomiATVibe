package eu.kanade.translation.translator
import eu.kanade.translation.translator.providers.DeepLTranslator
import eu.kanade.translation.translator.providers.DeepSeekTranslator
import eu.kanade.translation.translator.providers.GeminiTranslator
import eu.kanade.translation.translator.providers.GoogleTranslator
import eu.kanade.translation.translator.providers.LmStudioTranslator
import eu.kanade.translation.translator.providers.MLKitTranslator
import eu.kanade.translation.translator.providers.OpenRouterTranslator
import tachiyomi.domain.translation.TranslationEngineCategory

/**
 * TachiyomiAT: coarse compute classification that drives batch lane routing.
 *
 * REMOTE_IO translators (Gemini, OpenRouter, DeepSeek, LM Studio, DeepL,
 * Google Translate) wait on network round-trips, so the batch coordinator may
 * overlap them with same-page native inpaint/render work — the CPU is idle
 * during the HTTP wait.
 *
 * LOCAL_COMPUTE translators (on-device ML Kit) run inference on the same SoC
 * budget as native OCR/inpaint, so the coordinator keeps them serialized with
 * native work to avoid oversubscribing low-end devices.
 */
enum class TranslatorComputeClass {
    REMOTE_IO,
    LOCAL_COMPUTE,
    ;

    /**
     * True when the translator's wait is dominated by I/O and may safely overlap
     * native inpaint/render work. LOCAL_COMPUTE returns false so the coordinator
     * keeps it on the native lane.
     */
    val mayOverlapNative: Boolean get() = this == REMOTE_IO

    companion object {
        /**
         * Classify a built [TextTranslator] instance. Falls back to REMOTE_IO for
         * unknown/future translators: an unknown remote translator overlapping is
         * safe (the CPU is still free), whereas misclassifying a heavy local
         * engine as LOCAL_COMPUTE would block legitimate overlap. ML Kit is the
         * only known LOCAL_COMPUTE engine.
         */
        fun forTranslator(translator: TextTranslator): TranslatorComputeClass =
            when (translator) {
                is MLKitTranslator -> LOCAL_COMPUTE
                is GeminiTranslator,
                is OpenRouterTranslator,
                is DeepSeekTranslator,
                is LmStudioTranslator,
                is DeepLTranslator,
                is GoogleTranslator,
                -> REMOTE_IO
                else -> REMOTE_IO
            }

        /**
         * Classify by the configured engine selection without instantiating the
         * translator. Used by the coordinator's lane router so it can pick a lane
         * before building the translator.
         */
        fun forConfiguration(
            category: TranslationEngineCategory,
            standardEngineName: String?,
            aiEngineName: String?,
        ): TranslatorComputeClass = when (category) {
            TranslationEngineCategory.STANDARD ->
                if (standardEngineName == StandardTranslatorKind.MLKIT.name) LOCAL_COMPUTE else REMOTE_IO
            TranslationEngineCategory.AI_MODEL -> REMOTE_IO
        }
    }
}
