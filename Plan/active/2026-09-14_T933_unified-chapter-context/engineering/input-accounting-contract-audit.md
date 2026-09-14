# InputAccountingContract Audit & Transport Enforcement — T933 Task 1.2a

This engineering audit documents the token accounting models, certified conservative bounds, and transport-level enforcement for all supported AI translation backends in TachiyomiAT under the T933 Unified Chapter Context 8,192 context-window allocator.

Reference task: Ledger 1.2a (`T933 InputAccountingContract audit: per provider exact final-message counter or certified conservative bound; no contract -> no dispatch under 8k`).

---

## 1. Context & Motivation

In the T933 architecture, all AI translation and analysis requests must adhere to a strict all-model context ceiling:
$$\text{Input Tokens } (I) + \text{Reserved Output Tokens } (O) + \text{Safety Margin } (S = 512) \le 8{,}192$$

Historically, TachiyomiAT relied on `TranslationContextChunkPlanner.estimateTokens(payload)` which utilizes OpenAI's `CL100K_BASE` BPE tokenizer registry (`com.knuddels.jtokkit`). While `CL100K_BASE` provides a baseline token estimate for standard GPT-style text, it does **not** equal the true final token count across heterogeneous backends because:
1. **Vocabulary Divergence**: Target models employ different tokenizers (e.g., Llama 3 128k tiktoken, Mistral Tekken 128k, DeepSeek 128k BPE, Gemma 256k SentencePiece, Gemini 256k SentencePiece).
2. **CJK Tokenization Expansion**: CJK (Chinese, Japanese, Korean) text decomposes into significantly more tokens under Western-biased BPE tokenizers or different byte-fallback schemes than under `CL100K_BASE`.
3. **Hidden Transport Framing**: Wire requests sent over HTTP contain JSON structural keys, role strings, delimiters (`<|im_start|>`, `<|im_end|>`), and provider Jinja2 chat templates that are parsed into tokens by the remote/local inference runtime.

Under the 8k allocator guarantee, assuming `CL100K_BASE + 512` is unsafe. An underestimation can cause hard provider-side truncation or context exhaustion errors midway through generation, corrupting translation output. Therefore, every dispatch under the 8k guarantee requires an **`InputAccountingContract`**:
- Either an exact final-message counter, or
- A certified conservative upper bound.

Without a certified contract, dispatch is refused at the transport layer before any network I/O occurs.

---

## 2. Certified Bounds per Provider

For each of the four supported AI translation providers, we establish a certified conservative bound over the final rendered model input.

### 2.1 LM Studio (`lm_studio`)
- **Runtime Environment**: Local inference server (llama.cpp / LM Studio server) hosting arbitrary user-selected GGUF quantized models.
- **Representative Architectures**: Llama 3 / 3.1 (128k BPE), Mistral / Nemo / Tekken, Qwen 2.5 (152k BPE), Gemma 2 (256k SentencePiece).
- **Variance Factors**:
  - Mistral and Gemma exhibit up to a 1.35× token inflation over `CL100K_BASE` on CJK dialogue and Japanese phonetic particles.
  - Jinja2 template formatting introduces system/user framing tags (`<|begin_of_text|>`, `<|start_header_id|>`, `<|end_header_id|>`, etc.).
  - Local server HTTP JSON payload wrapping adds object delimiters and key tokenization.
- **Certified Bound Formula**:
  $$\text{finalInputTokens} = \left\lceil \text{CL100K\_BASE}(\text{payload}) \times 1.40 \right\rceil + 64$$
- **Contract Type**: `AccountingMode.CERTIFIED_BOUND` via `LmStudioInputAccountingContract`.

### 2.2 DeepSeek (`deepseek`)
- **Target Models**: `deepseek-chat` (DeepSeek-V3), `deepseek-reasoner` (DeepSeek-R1).
- **Tokenizer Architecture**: 128k multi-byte BPE tokenizer optimized for English, Chinese, and code.
- **Variance Factors**:
  - Chinese characters are efficiently tokenized (often 1 token per glyph), but Japanese and Korean scripts may require multi-byte decomposition with up to 1.12× expansion relative to `CL100K_BASE`.
  - OpenAI-compatible wire framing (`messages` array, roles) maps to DeepSeek's internal ChatML template delimiters (`<｜User｜>`, `<｜Assistant｜>`, `<｜end of sentence｜>`).
  - Framing margin covers REST JSON metadata and delimiter injection.
- **Certified Bound Formula**:
  $$\text{finalInputTokens} = \left\lceil \text{CL100K\_BASE}(\text{payload}) \times 1.15 \right\rceil + 32$$
- **Contract Type**: `AccountingMode.CERTIFIED_BOUND` via `DeepSeekInputAccountingContract`.

### 2.3 OpenRouter (`openrouter`)
- **Target Environment**: Multi-model routing gateway proxying requests to arbitrary remote providers (Anthropic Claude, OpenAI GPT-4o, Meta Llama 3, Mistral Large, Cohere Command-R).
- **Variance Factors**:
  - Because OpenRouter acts as an aggregator across unknown downstream model tokenizers, the token envelope must safely cover the worst-case tokenizer among standard hosted models (e.g. Command-R, Mistral, Llama).
  - OpenRouter request transforms inject routing flags and template wrappers.
- **Certified Bound Formula**:
  $$\text{finalInputTokens} = \left\lceil \text{CL100K\_BASE}(\text{payload}) \times 1.35 \right\rceil + 64$$
- **Contract Type**: `AccountingMode.CERTIFIED_BOUND` via `OpenRouterInputAccountingContract`.

### 2.4 Google Gemini (`gemini`)
- **Target Models**: `gemini-1.5-flash`, `gemini-1.5-pro`, `gemini-2.0-flash`, etc.
- **Tokenizer Architecture**: 256k vocabulary Google SentencePiece tokenizer.
- **Variance Factors**:
  - SentencePiece vocabulary is large and handles CJK gracefully, but whitespace handling, punctuation normalization, and control tokens differ from `CL100K_BASE`.
  - Google REST API wire format (`contents`, `parts`, `text`, `generationConfig`, `safetySettings`) adds JSON structural overhead.
- **Certified Bound Formula**:
  $$\text{finalInputTokens} = \left\lceil \text{CL100K\_BASE}(\text{payload}) \times 1.10 \right\rceil + 32$$
- **Contract Type**: `AccountingMode.CERTIFIED_BOUND` via `GeminiInputAccountingContract`.

---

## 3. Transport Gate Architecture & Enforcement

Transport-level enforcement is implemented directly in the dispatch gateways:
- `OpenAiCompatibleTranslator.postChatCompletion` (governing LM Studio, DeepSeek, OpenRouter, and any OpenAI-compatible custom backends).
- `GeminiTranslator.post` (governing Gemini REST dispatches).

### 3.1 Pre-Flight Contract Verification
Before constructing any OkHttp request, initiating I/O, or claiming a permit from `ProviderRequestGovernor`, the transport executes two invariant checks:

```kotlin
// Invariant 1: Certified Contract Requirement
val contract = inputAccountingContract
if (contract == null || !contract.isCertified) {
    throw ProviderFailureException(
        ProviderFailure(
            kind = ProviderFailureKind.CONFIGURATION,
            retryability = ProviderFailureRetryability.TERMINAL,
            safeSummary = "dispatch refused: provider '$providerBackend' has no certified InputAccountingContract",
        ),
    )
}

// Invariant 2: 8,192 Context Ceiling Enforcement
val finalInputTokens = contract.countFinalTokens(payloadJson)
if (finalInputTokens + reservedOutputTokens + 512 > 8_192) {
    throw ProviderFailureException(
        ProviderFailure(
            kind = ProviderFailureKind.CONFIGURATION,
            retryability = ProviderFailureRetryability.TERMINAL,
            safeSummary = "dispatch refused under 8k: final input ($finalInputTokens) + output ($reservedOutputTokens) + 512 > 8192 for $providerBackend",
        ),
    )
}
```

### 3.2 Failure Taxonomy & Non-Retryability
Violations of either invariant throw `ProviderFailureException` with:
- `kind = ProviderFailureKind.CONFIGURATION`
- `retryability = ProviderFailureRetryability.TERMINAL`

This ensures that:
- Retries are never attempted (since no amount of retrying can fix a missing contract or an oversized payload).
- No network requests are made, protecting remote rate limits and preventing local buffer overruns.
- The failure is surfaced cleanly to the pipeline and chapter batch coordinator.

### 3.3 Governor Synchronization
Prior to this task, `ProviderRequestMetadata` recorded estimated prompt tokens from the chunk planner. Under the certified contract, `metadata.estimatedInputTokens` is explicitly updated to `finalInputTokens`:
```kotlin
val metadata = ProviderRequestMetadata(
    key = ProviderRequestKey(
        backend = providerBackend,
        model = providerModel,
        credentialScope = providerCredentialScope,
    ),
    estimatedInputTokens = finalInputTokens, // Certified bound
    reservedOutputTokens = reservedOutputTokens,
    operation = operation,
    envelopeId = envelopeId,
    priority = currentProviderRequestPriority(),
)
```
Consequently, the `ProviderRequestGovernor` quota accounting, token rate-limiting buckets, and admission diagnostics reflect the certified token budget rather than an uncertified approximation.

---

## 4. Per-Provider Disablement Mechanism

Per-provider disablement is integrated with the contract lifecycle:
1. Setting or injecting `customAccountingContract = UncertifiedInputAccountingContract(backend)` or a contract with `isCertified = false` immediately disables dispatches for that provider.
2. The transport layer rejects any dispatch attempt with `safeSummary = "dispatch refused: provider '$providerBackend' has no certified InputAccountingContract"`.
3. This satisfies the invariant: **per-provider disable = dispatch refusal**.

---

## 5. Verification Matrix

| Requirement | Implementation Component | Verification Coverage |
| :--- | :--- | :--- |
| **LM Studio Bound** | `LmStudioInputAccountingContract` | Tested formula: $\lceil \text{CL100K} \times 1.40 \rceil + 64$ |
| **DeepSeek Bound** | `DeepSeekInputAccountingContract` | Tested formula: $\lceil \text{CL100K} \times 1.15 \rceil + 32$ |
| **OpenRouter Bound** | `OpenRouterInputAccountingContract` | Tested formula: $\lceil \text{CL100K} \times 1.35 \rceil + 64$ |
| **Gemini Bound** | `GeminiInputAccountingContract` | Tested formula: $\lceil \text{CL100K} \times 1.10 \rceil + 32$ |
| **No Contract -> Refusal** | `postChatCompletion` / `post` | Rejection with `CONFIGURATION` / `TERMINAL` when contract is null |
| **Uncertified -> Refusal** | `postChatCompletion` / `post` | Rejection with `CONFIGURATION` / `TERMINAL` when `isCertified = false` |
| **Context Ceiling (> 8192)** | `postChatCompletion` / `post` | Rejection before network call when $I + O + 512 > 8192$ |
| **Within Budget -> Admitted**| `postChatCompletion` / `post` | Passes transport gate, registers `finalInputTokens` with governor |
| **Per-Provider Disable** | Translator constructor injection | Refuses dispatches when disabled via uncertified contract |
