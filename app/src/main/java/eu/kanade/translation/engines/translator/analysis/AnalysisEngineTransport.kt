package eu.kanade.translation.engines.translator.analysis

import eu.kanade.translation.engines.translator.ProviderFailure
import eu.kanade.translation.engines.translator.ProviderFailureException
import eu.kanade.translation.engines.translator.ProviderFailureKind
import eu.kanade.translation.engines.translator.ProviderFailureRetryability
import eu.kanade.translation.engines.translator.providers.AiTranslator
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.IOException

/**
 * Adapts an AI engine's raw text completion
 * ([AiTranslator.postStructuredAnalysisRaw]) to [AnalysisTextTransport]. It
 * performs one raw attempt per call; admission and retry live in
 * [AnalysisChunkExecutor]. This class frames the prompt and guarantees typed
 * failures.
 *
 * The identity triple (providerId/modelId/credentialSignature) comes from the
 * engine itself, so the durable analyzer provenance and the Batch admission
 * keys use the same governor backend spelling as translation envelopes.
 *
 * Each chunk request asks for a free-form bounded summary. Provider responses
 * do not have a response schema, block-id pattern, or hash-echo requirement;
 * that strict contract repeatedly failed with real providers. One structured
 * glossary is produced per chapter by [postGlossarySynthesis] over the stored
 * summaries.
 */
class AnalysisEngineTransport(
    private val engine: AiTranslator,
) : AnalysisTextTransport {

    override val providerId: String =
        engine.analysisBackendId
            ?: throw IllegalArgumentException("engine has no analysis transport")

    override val modelId: String = engine.analysisModelId ?: "unspecified"

    override val credentialSignature: String? = engine.analysisCredentialScope

    override suspend fun postStructuredAnalysis(requestJson: String, chunkId: String): String =
        try {
            engine.postStructuredAnalysisRaw(
                systemPrompt = ANALYSIS_SYSTEM_PROMPT,
                userPrompt = ANALYSIS_USER_INSTRUCTIONS + "\n\n" + requestJson,
                maxOutputTokens = clampedOutputBudget(
                    systemPrompt = ANALYSIS_SYSTEM_PROMPT,
                    userPrompt = ANALYSIS_USER_INSTRUCTIONS + "\n\n" + requestJson,
                    requestedOutputTokens = ANALYSIS_MAX_OUTPUT_TOKENS,
                    driftReserveTokens = ENGINE_FRAMING_DRIFT_TOKENS,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProviderFailureException) {
            throw e
        } catch (e: IOException) {
            // A raw network failure must stay typed: the
            // executor's attempt policy classifies on [ProviderFailure].
            logcat(LogPriority.WARN) {
                "TachiyomiAT t924 analysis transport IO failure chunk=$chunkId " +
                    "error=${e::class.java.simpleName}"
            }
            throw ProviderFailureException(
                ProviderFailure(
                    kind = ProviderFailureKind.NETWORK,
                    retryability = ProviderFailureRetryability.PAUSE,
                    safeSummary = "analysis transport network failure",
                ),
                e,
            )
        }

    /**
     * ONE chapter-level synthesis call: the durable chunk summaries in, a
     * small CHARACTER/PLACE glossary out. Same transport guarantees (typed
     * failures, one raw attempt); the caller owns admission/retry.
     */
    suspend fun postGlossarySynthesis(
        sourceLanguage: String,
        targetLanguage: String,
        summaries: List<String>,
    ): String {
        val userPrompt = buildString {
            append(SYNTHESIS_USER_INSTRUCTIONS_HEADER)
            append("Source language: ").append(sourceLanguage).append('\n')
            append("Target language: ").append(targetLanguage).append("\n\n")
            summaries.forEachIndexed { index, summary ->
                append("Chunk ").append(index + 1).append(":\n")
                append(summary.trim()).append("\n\n")
            }
        }
        return try {
            engine.postStructuredAnalysisRaw(
                systemPrompt = SYNTHESIS_SYSTEM_PROMPT,
                userPrompt = userPrompt,
                maxOutputTokens = clampedOutputBudget(
                    systemPrompt = SYNTHESIS_SYSTEM_PROMPT,
                    userPrompt = userPrompt,
                    requestedOutputTokens = SYNTHESIS_MAX_OUTPUT_TOKENS,
                    driftReserveTokens = ENGINE_FRAMING_DRIFT_TOKENS,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProviderFailureException) {
            throw e
        } catch (e: IOException) {
            logcat(LogPriority.WARN) {
                "TachiyomiAT t924 glossary synthesis IO failure: ${e::class.java.simpleName}"
            }
            throw ProviderFailureException(
                ProviderFailure(
                    kind = ProviderFailureKind.NETWORK,
                    retryability = ProviderFailureRetryability.PAUSE,
                    safeSummary = "glossary synthesis network failure",
                ),
                e,
            )
        }
    }

    /**
     * Execution-time output-budget clamp (the translation envelopes'
     * `StreamingChunkPlanner.effectiveOutputCap` idiom): the certified
     * [eu.kanade.translation.engines.translator.InputAccountingContract] counts the
     * REAL framed prompt (CJK tokenization and the wire envelope inflate it
     * well beyond any raw-text estimate). When the honest count leaves less
     * room than requested, shrink the output reservation to what actually
     * fits under the 8k window instead of letting the engine's dispatch
     * guard refuse the call; only an input that cannot host even the
     * minimum output pauses (typed, PAUSE retryability).
     */
    private fun clampedOutputBudget(
        systemPrompt: String,
        userPrompt: String,
        requestedOutputTokens: Int,
        driftReserveTokens: Int,
    ): Int {
        val contract = engine.inputAccountingContract
        if (contract == null || !contract.isCertified) {
            // The engine's own guard refuses uncertified dispatch; no clamp here.
            return requestedOutputTokens
        }
        val inputTokens = contract.countFinalTokens(systemPrompt + userPrompt)
        val available = CONTEXT_LIMIT_TOKENS - SAFETY_MARGIN_TOKENS - driftReserveTokens - inputTokens
        if (available >= requestedOutputTokens) return requestedOutputTokens
        if (available >= MIN_ANALYSIS_OUTPUT_TOKENS) return available
        throw ProviderFailureException(
            ProviderFailure(
                kind = ProviderFailureKind.CONFIGURATION,
                retryability = ProviderFailureRetryability.PAUSE,
                safeSummary = "analysis request token-oversized: input ($inputTokens) " +
                    "leaves under $MIN_ANALYSIS_OUTPUT_TOKENS output tokens under 8k",
            ),
        )
    }

    companion object {
        /**
         * Free-form chunk summaries are short by construction (~120 words);
         * the reservation stays deliberately small so the input side keeps
         * the window. Mirrors the coordinator's
         * [eu.kanade.translation.pipeline.batch.ChapterProfileBatchCoordinator
         * .ANALYSIS_MAX_OUTPUT_TOKENS].
         */
        const val ANALYSIS_MAX_OUTPUT_TOKENS = 512

        /** The 8k dispatch window every certified contract enforces. */
        const val CONTEXT_LIMIT_TOKENS = 8_192

        /** Same safety margin the engine dispatch guards reserve. */
        const val SAFETY_MARGIN_TOKENS = 512

        /**
         * The contract above counts the system+user text; the engine's REST
         * payload re-frames it (chat template / REST JSON), which the guard
         * recounts. This drift reserve keeps the clamp strictly below the
         * engine's own admission arithmetic.
         */
        const val ENGINE_FRAMING_DRIFT_TOKENS = 128

        /** A usable analysis/synthesis answer cannot be shorter than this. */
        const val MIN_ANALYSIS_OUTPUT_TOKENS = 256

        /** The chapter-level synthesis answer is a small capped glossary. */
        const val SYNTHESIS_MAX_OUTPUT_TOKENS = 768

        /** Hard per-chunk summary cap (chars) — also the durable bound. */
        const val MAX_SUMMARY_CHARS = 1_200

        /** Total summaries fed to one synthesis call (chars) — context diet. */
        const val MAX_SYNTHESIS_INPUT_CHARS = 8_000

        /**
         * The per-chunk framing prompt: free-form, bounded, no response
         * schema. The strict structured-extraction contract
         * was model-hostile — two real providers failed it on shape and
         * id-pattern violations, each failure costing a paid reissue and a
         * run pause — so the extraction now happens once, at synthesis.
         */
        const val ANALYSIS_SYSTEM_PROMPT =
            "You are a manga-chapter analysis engine. You receive one JSON " +
                "request envelope describing OCR text blocks of one chapter " +
                "chunk (pages p0, p1, ...; blocks p0_b0, p0_b1, ...). Reply " +
                "with a plain-text summary of at most 120 words — no JSON, no " +
                "markdown — listing ONLY: (1) the characters that appear, " +
                "each name written exactly as it appears in the text (list " +
                "spelling variants separately); (2) recurring places or " +
                "settings; (3) one short line about the situation. Use only " +
                "names and places actually present in the text; never invent " +
                "any. If the chunk has no meaningful content, reply exactly: " +
                "nothing"

        /** The per-request user framing above the JSON envelope itself. */
        const val ANALYSIS_USER_INSTRUCTIONS =
            "Summarize the following chapter chunk request (protocol " +
                "tachiyomiat-analysis, schemaVersion 1, free-form summary " +
                "response). Request envelope:"

        /** The one-shot chapter glossary synthesis prompts. */
        const val SYNTHESIS_SYSTEM_PROMPT =
            "You are a manga translation consistency engine. You receive " +
                "ordered plain-text summaries of one chapter's chunks. Reply " +
                "with ONE raw JSON array — no markdown fences, no commentary " +
                "— of the chapter's recurring identity anchors ONLY, shaped " +
                "exactly: [{\"kind\":\"CHARACTER\",\"source\":\"<name exactly " +
                "as written in the source language>\",\"target\":\"<one " +
                "consistent rendering in the target language>\"," +
                "\"aliases\":[\"<other source spellings>\"]},{\"kind\":" +
                "\"PLACE\",\"source\":\"...\",\"target\":\"...\"," +
                "\"aliases\":[\"...\"]}] . Rules: include only characters " +
                "that appear in more than one chunk or clearly matter; at " +
                "most 16 CHARACTER entries and 8 PLACE entries; keep every " +
                "name short (no titles or honorifics inside the name); no " +
                "plot facts, no relationships, no terms beyond characters " +
                "and places — extra kinds or entries are noise and will be " +
                "dropped. If nothing recurs, reply exactly: []"

        const val SYNTHESIS_USER_INSTRUCTIONS_HEADER =
            "Build the chapter glossary from these chunk summaries:\n"
    }
}
