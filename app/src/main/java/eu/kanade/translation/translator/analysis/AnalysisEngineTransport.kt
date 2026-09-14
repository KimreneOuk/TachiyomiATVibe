package eu.kanade.translation.translator.analysis

import eu.kanade.translation.translator.ProviderFailure
import eu.kanade.translation.translator.ProviderFailureException
import eu.kanade.translation.translator.ProviderFailureKind
import eu.kanade.translation.translator.ProviderFailureRetryability
import eu.kanade.translation.translator.providers.AiTranslator
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.IOException

/**
 * T924 wave-7c: adapts an AI engine's raw text completion
 * ([AiTranslator.postStructuredAnalysisRaw]) to the T924-AP-02
 * [AnalysisTextTransport] seam. ONE raw attempt per call — admission and
 * retry live in [AnalysisChunkExecutor] (shared 15-RPM Batch sub-limit gate +
 * shared provider bucket, T924-AP-08); this class only frames the prompt and
 * guarantees typed failures.
 *
 * The identity triple (providerId/modelId/credentialSignature) comes from the
 * engine itself, so the durable analyzer provenance and the Batch admission
 * keys use the SAME governor backend spelling as the translation envelopes
 * (DR-C one allowance per credential; wave-6 F-W6-4 alignment).
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
                maxOutputTokens = ANALYSIS_MAX_OUTPUT_TOKENS,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProviderFailureException) {
            throw e
        } catch (e: IOException) {
            // A raw network failure must stay typed (T924-AP-08): the
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

    companion object {
        /**
         * T924-AP-03 output budget default. Mirrors the coordinator's
         * [eu.kanade.translation.pipeline.batch.ChapterProfileBatchCoordinator
         * .ANALYSIS_MAX_OUTPUT_TOKENS].
         */
        const val ANALYSIS_MAX_OUTPUT_TOKENS = 3072

        /**
         * The analysis framing prompt (T924-AP-01): chapter-local structured
         * extraction with verbatim echo discipline. The response schema is
         * owned by the request envelope; the model returns ONE raw JSON
         * document and nothing else.
         */
        const val ANALYSIS_SYSTEM_PROMPT =
            "You are a manga-chapter analysis engine. You receive one JSON " +
                "request envelope describing OCR text blocks of a chapter " +
                "(pages p0, p1, ...; blocks p0_b0, p0_b1, ...). Return exactly " +
                "ONE JSON document with the extracted reading facts: terms, " +
                "entities, relationships, scenes, unresolved questions and " +
                "candidate equivalences, each anchored with evidence " +
                "references. Rules: use ONLY the text in this request as " +
                "evidence; every evidence anchor must copy the cited block's " +
                "\"excerptHash\" value VERBATIM from the request; never invent " +
                "ids, entities or canon; never output series-wide or user " +
                "authority content; if a page yields nothing extractable, " +
                "return it with an empty record set. Reply with the raw JSON " +
                "document only — no markdown fences, no commentary."

        /** The per-request user framing above the JSON envelope itself. */
        const val ANALYSIS_USER_INSTRUCTIONS =
            "Analyze the following chapter chunk request and respond with the " +
                "JSON analysis document (protocol tachiyomiat-analysis, " +
                "schemaVersion 1). Request envelope:"
    }
}
