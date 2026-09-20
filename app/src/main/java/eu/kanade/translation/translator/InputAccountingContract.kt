package eu.kanade.translation.translator

import eu.kanade.translation.translator.contextual.TranslationContextChunkPlanner
import kotlin.math.ceil

/**
 *  Accounting mode for final model request input tokens.
 */
enum class AccountingMode {
    EXACT,
    CERTIFIED_BOUND,
}

/**
 *  Transport-layer input accounting contract required for all AI dispatches under 8,192 context limit.
 * Dispatches without a certified contract are refused before network calls.
 */
interface InputAccountingContract {
    val providerBackend: String
    val model: String?
    val isCertified: Boolean
    val accountingMode: AccountingMode
    fun countFinalTokens(payload: String): Int
}

/**
 * Certified conservative bound for LM Studio local server hosting heterogeneous GGUF models.
 * Bound: ceil(CL100K_BASE(payload) * 1.40) + 64 (covers Mistral/Gemma CJK divergence and Jinja2 chat templates).
 */
class LmStudioInputAccountingContract(
    override val model: String? = null,
    override val isCertified: Boolean = true,
) : InputAccountingContract {
    override val providerBackend: String = "lm_studio"
    override val accountingMode: AccountingMode = AccountingMode.CERTIFIED_BOUND

    override fun countFinalTokens(payload: String): Int {
        val base = TranslationContextChunkPlanner.estimateTokens(payload)
        return ceil(base * 1.40).toInt() + 64
    }
}

/**
 * Certified conservative bound for DeepSeek-V3 / R1 (128k BPE vocabulary).
 * Bound: ceil(CL100K_BASE(payload) * 1.15) + 32 (ChatML template delimiters and CJK tokenization).
 */
class DeepSeekInputAccountingContract(
    override val model: String? = null,
    override val isCertified: Boolean = true,
) : InputAccountingContract {
    override val providerBackend: String = "deepseek"
    override val accountingMode: AccountingMode = AccountingMode.CERTIFIED_BOUND

    override fun countFinalTokens(payload: String): Int {
        val base = TranslationContextChunkPlanner.estimateTokens(payload)
        return ceil(base * 1.15).toInt() + 32
    }
}

/**
 * Certified conservative bound for OpenRouter multi-model gateway.
 * Bound: ceil(CL100K_BASE(payload) * 1.35) + 64 (conservative across multi-model routing envelope).
 */
class OpenRouterInputAccountingContract(
    override val model: String? = null,
    override val isCertified: Boolean = true,
) : InputAccountingContract {
    override val providerBackend: String = "openrouter"
    override val accountingMode: AccountingMode = AccountingMode.CERTIFIED_BOUND

    override fun countFinalTokens(payload: String): Int {
        val base = TranslationContextChunkPlanner.estimateTokens(payload)
        return ceil(base * 1.35).toInt() + 64
    }
}

/**
 * Certified conservative bound for Google Gemini (256k SentencePiece tokenizer).
 * Bound: ceil(CL100K_BASE(payload) * 1.10) + 32 (REST API JSON framing).
 */
class GeminiInputAccountingContract(
    override val model: String? = null,
    override val isCertified: Boolean = true,
) : InputAccountingContract {
    override val providerBackend: String = "gemini"
    override val accountingMode: AccountingMode = AccountingMode.CERTIFIED_BOUND

    override fun countFinalTokens(payload: String): Int {
        val base = TranslationContextChunkPlanner.estimateTokens(payload)
        return ceil(base * 1.10).toInt() + 32
    }
}

/**
 * Uncertified placeholder contract for testing or disabled providers.
 * Any dispatch under this contract is rejected at the transport gate.
 */
class UncertifiedInputAccountingContract(
    override val providerBackend: String,
    override val model: String? = null,
) : InputAccountingContract {
    override val isCertified: Boolean = false
    override val accountingMode: AccountingMode = AccountingMode.CERTIFIED_BOUND

    override fun countFinalTokens(payload: String): Int {
        return TranslationContextChunkPlanner.estimateTokens(payload)
    }
}
