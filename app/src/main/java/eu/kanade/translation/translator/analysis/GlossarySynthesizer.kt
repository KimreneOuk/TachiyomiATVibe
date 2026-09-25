package eu.kanade.translation.translator.analysis

import eu.kanade.translation.translator.AdmissionPriority
import eu.kanade.translation.translator.BatchRequestSublimitGate
import eu.kanade.translation.translator.ProviderFailure
import eu.kanade.translation.translator.ProviderFailureException
import eu.kanade.translation.translator.ProviderFailureKind
import eu.kanade.translation.translator.ProviderFailureRetryability
import eu.kanade.translation.translator.ProviderRequestKey
import eu.kanade.translation.translator.ProviderRequestMetadata
import eu.kanade.translation.translator.SharedBatchRequestSublimitGate
import eu.kanade.translation.translator.contextual.TranslationResponseFaithfulness
import eu.kanade.translation.translator.providers.AiTranslator
import eu.kanade.translation.translator.providers.OcrArtifactSanitizer
import eu.kanade.translation.translator.retry.RequestRetryBudget
import eu.kanade.translation.translator.retry.withTranslationRetry
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * Director decision (summary-glossary redesign): the frozen profile's
 * operative content is a SMALL identity sheet — recurring characters and
 * places ONLY. One synthesis call over the durable chunk summaries produces
 * it; anything beyond identity anchors is noise and is dropped here, never
 * by the model's good behavior alone.
 */
sealed interface GlossarySynthesisOutcome {

    /** A parsed (and capped) glossary; possibly empty. */
    data class Glossary(val entries: List<GlossaryEntry>) : GlossarySynthesisOutcome

    /** Typed pause — the run resumes here without re-paying the summaries. */
    data class Paused(val failure: ProviderFailure, val reason: String) : GlossarySynthesisOutcome
}

enum class GlossaryEntryKind { CHARACTER, PLACE }

data class GlossaryEntry(
    val kind: GlossaryEntryKind,
    val source: String,
    val target: String,
    val aliases: List<String> = emptyList(),
)

/** Test/production seam: the coordinator's one-shot glossary builder. */
fun interface GlossarySynthesizer {
    suspend fun synthesize(
        sourceLanguage: String,
        targetLanguage: String,
        summaries: List<String>,
    ): GlossarySynthesisOutcome
}

/**
 * Engine-backed synthesis: rides the same Batch sub-limit discipline and
 * retry driver as the chunk executor (one allowance per credential for ALL
 * Batch traffic), frames the prompts through [AnalysisEngineTransport], and
 * parses the answer tolerantly — a bad entry is dropped, never fatal.
 */
class AnalysisEngineGlossarySynthesizer(
    engine: AiTranslator,
    private val sublimitGate: BatchRequestSublimitGate = SharedBatchRequestSublimitGate.instance,
) : GlossarySynthesizer {

    private val transport = AnalysisEngineTransport(engine)

    private val requestKey: ProviderRequestKey = ProviderRequestKey(
        backend = transport.providerId,
        model = transport.modelId,
        credentialScope = transport.credentialSignature,
    )

    override suspend fun synthesize(
        sourceLanguage: String,
        targetLanguage: String,
        summaries: List<String>,
    ): GlossarySynthesisOutcome {
        val cappedSummaries = capSummaries(summaries)
        if (cappedSummaries.isEmpty()) {
            return GlossarySynthesisOutcome.Glossary(emptyList())
        }
        val metadata = ProviderRequestMetadata(
            key = requestKey,
            estimatedInputTokens = (cappedSummaries.sumOf { it.length } + 3) / 4,
            reservedOutputTokens = AnalysisEngineTransport.SYNTHESIS_MAX_OUTPUT_TOKENS,
            operation = "glossary_synthesis",
            priority = AdmissionPriority.BACKGROUND,
        )
        return try {
            sublimitGate.executeBatch(metadata) {
                withTranslationRetry(
                    maxAttempts = 3,
                    baseDelayMs = 1_000L,
                    logTag = transport.providerId,
                    retryBudget = RequestRetryBudget(),
                ) {
                    transport.postGlossarySynthesis(
                        sourceLanguage = sourceLanguage,
                        targetLanguage = targetLanguage,
                        summaries = cappedSummaries,
                    )
                }
            }.let { raw ->
                GlossarySynthesisOutcome.Glossary(GlossarySynthesisParser.parse(raw))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProviderFailureException) {
            GlossarySynthesisOutcome.Paused(
                failure = e.failure,
                reason = "glossary synthesis paused: ${e.failure.safeSummary}",
            )
        } catch (e: Exception) {
            GlossarySynthesisOutcome.Paused(
                failure = ProviderFailure(
                    kind = ProviderFailureKind.NETWORK,
                    retryability = ProviderFailureRetryability.PAUSE,
                    safeSummary = "glossary synthesis failed: ${e::class.java.simpleName}",
                ),
                reason = "glossary synthesis paused: ${e::class.java.simpleName}",
            )
        }
    }

    /**
     * Context diet (Director: "limit the amount of context"): per-summary
     * chars are already bounded at storage; the TOTAL input is bounded here
     * by shrinking every summary proportionally, preserving each chunk's
     * voice rather than dropping whole chunks.
     */
    private fun capSummaries(summaries: List<String>): List<String> {
        val nonBlank = summaries.mapNotNull { s -> s.trim().takeIf { it.isNotEmpty() } }
        if (nonBlank.isEmpty()) return emptyList()
        val total = nonBlank.sumOf { it.length }
        if (total <= AnalysisEngineTransport.MAX_SYNTHESIS_INPUT_CHARS) return nonBlank
        val scale = AnalysisEngineTransport.MAX_SYNTHESIS_INPUT_CHARS.toDouble() / total
        return nonBlank.map { s -> s.take((s.length * scale).toInt().coerceAtLeast(80)) }
    }
}

/**
 * Tolerant parser for the synthesis answer: strips thinking tags and code
 * fences, takes the first JSON array, keeps only well-formed CHARACTER/PLACE
 * entries, and enforces the noise caps (≤16 characters, ≤8 places, ≤4
 * aliases, 64-char fields) client-side. An unparsable body yields an EMPTY
 * glossary (translation proceeds without the sheet) — the summaries are
 * durable and the call is retryable, so failing the whole run over one bad
 * answer is worse than translating without anchors.
 */
object GlossarySynthesisParser {

    private const val MAX_CHARACTERS = 16
    private const val MAX_PLACES = 8
    private const val MAX_ALIASES = 4
    private const val MAX_FIELD_CHARS = 64

    private val lenientJson = Json { ignoreUnknownKeys = true }

    fun parse(rawText: String): List<GlossaryEntry> {
        val stripped = OcrArtifactSanitizer.stripThinkingTags(rawText).trim()
        if (TranslationResponseFaithfulness.isStructuralRefusal(stripped)) return emptyList()
        val body = stripped.removePrefix("```").removePrefix("json").removePrefix("JSON")
            .substringBeforeLast("```").trim()
        val start = body.indexOf('[')
        val end = body.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        val array = runCatching {
            lenientJson.parseToJsonElement(body.substring(start, end + 1))
        }.getOrNull() as? JsonArray ?: return emptyList()

        val entries = mutableListOf<GlossaryEntry>()
        var characters = 0
        var places = 0
        for (element in array) {
            val obj = element as? JsonObject ?: continue
            val kind = when ((obj["kind"] as? JsonPrimitive)?.content?.uppercase()) {
                "CHARACTER" -> GlossaryEntryKind.CHARACTER
                "PLACE" -> GlossaryEntryKind.PLACE
                else -> continue
            }
            if (kind == GlossaryEntryKind.CHARACTER && characters >= MAX_CHARACTERS) continue
            if (kind == GlossaryEntryKind.PLACE && places >= MAX_PLACES) continue
            val source = ((obj["source"] as? JsonPrimitive)?.content ?: "").trim().take(MAX_FIELD_CHARS)
            val target = ((obj["target"] as? JsonPrimitive)?.content ?: "").trim().take(MAX_FIELD_CHARS)
            if (source.isEmpty() || target.isEmpty() || source == target) continue
            val aliases = (obj["aliases"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.content }
                ?.map { it.trim().take(MAX_FIELD_CHARS) }
                ?.filter { it.isNotEmpty() && it != source }
                ?.take(MAX_ALIASES)
                ?: emptyList()
            if (kind == GlossaryEntryKind.CHARACTER) characters++ else places++
            entries += GlossaryEntry(kind = kind, source = source, target = target, aliases = aliases)
        }
        if (entries.size < array.size) {
            logcat(LogPriority.WARN) {
                "TachiyomiAT t924 glossary synthesis dropped ${array.size - entries.size} " +
                    "malformed or capped entries"
            }
        }
        return entries
    }
}
