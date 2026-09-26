package eu.kanade.translation.engines.translator.providers
import eu.kanade.translation.engines.translator.InputAccountingContract
import eu.kanade.translation.engines.translator.ProviderFailure
import eu.kanade.translation.engines.translator.ProviderFailureException
import eu.kanade.translation.engines.translator.ProviderFailureKind
import eu.kanade.translation.engines.translator.ProviderFailureRetryability
import eu.kanade.translation.engines.translator.contextual.ContextualRequestProtocol
import eu.kanade.translation.engines.translator.contextual.ContextualTextTranslator
import eu.kanade.translation.engines.translator.contextual.ContextualTranslationBatch
import eu.kanade.translation.engines.translator.contextual.TranslationContextChunk
import eu.kanade.translation.engines.translator.contextual.applyBatchToChunk
import eu.kanade.translation.model.PageTranslation

/**
 * TachiyomiAT: Base abstraction for AI / LLM translators.
 */
abstract class AiTranslator : BaseTranslator(), ContextualTextTranslator {

    /**  Certified accounting contract for final model request input tokens. */
    open val inputAccountingContract: InputAccountingContract? get() = null

    // ------------------------------------------------------------------
    //  Stage-7/WP5 (wave-7c): the typed structured-analysis transport.
    // Engines that expose a raw text completion opt in by overriding these
    // members; [AnalysisEngineTransport] adapts them to the
    // [eu.kanade.translation.engines.translator.analysis.AnalysisTextTransport] seam
    // (typed failures only — `promptText` is forbidden for analysis because
    // it swallows every failure into an empty string, ).
    // ------------------------------------------------------------------

    /** The governor backend spelling for Batch admission keys, or null when the engine has no analysis transport. */
    open val analysisBackendId: String? get() = null

    /** The model identity frozen into the analyzer provenance. */
    open val analysisModelId: String? get() = null

    /** Opaque credential signature; never a raw credential. */
    open val analysisCredentialScope: String? get() = null

    /**
     * ONE raw text completion (single attempt — the caller owns admission and
     * retry through the shared governor + root budget). Failures are typed
     * ([ProviderFailureException]); raw transport errors must be mapped.
     */
    open suspend fun postStructuredAnalysisRaw(
        systemPrompt: String,
        userPrompt: String,
        maxOutputTokens: Int,
    ): String = throw ProviderFailureException(
        ProviderFailure(
            kind = ProviderFailureKind.CONFIGURATION,
            retryability = ProviderFailureRetryability.TERMINAL,
            safeSummary = "engine has no structured-analysis transport",
        ),
    )

    override suspend fun translate(pages: MutableMap<String, PageTranslation>) {
        val linkedPages = LinkedHashMap(pages)
        val blockCount = linkedPages.values.sumOf { it.blocks.size }
        val chunk = TranslationContextChunk(
            pages = linkedPages,
            blockCount = blockCount,
            rollingContext = "",
            glossary = "",
            estimatedPromptTokens = 0,
            maxOutputTokens = 8192,
            protocol = ContextualRequestProtocol.LEGACY,
        )
        translateContextual(chunk)
    }

    override suspend fun translateContextual(chunk: TranslationContextChunk): ContextualTranslationBatch {
        val batch = translateContextualStructured(chunk)
        applyBatchToChunk(chunk, batch)
        return batch
    }
}
