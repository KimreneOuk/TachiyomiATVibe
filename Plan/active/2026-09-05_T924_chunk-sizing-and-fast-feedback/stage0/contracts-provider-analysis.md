# T924 Stage 0 — Typed analysis/provider contract, error taxonomy, authority inputs, retention policy

Date: 2026-09-05 · Baseline verified at HEAD `adbe643` (`adbe643df9d99953202dbb8c93c5dd7e504bfd6c`)
Owner: Technical Lead, work item D · Status: Stage-0 contract specification only. **No production coding is authorized by this document.**

Scope: this file specifies (1) the provider-independent typed structured-analysis
protocol (T924-AP-*), (2) analyzer capability requirements and the
analyzer-vs-translator provider relationship options, (3) the typed provider
error contract for analysis, (4) authority inputs for the first release
(audit blocking decision 10), (5) the small-chapter / no-work analysis policy
(audit blocking decision 11), and (6-7) Director decision briefs T924-DR-A..D.
It does not define durable schemas (T924-SC-*), state machine/transactions
(T924-ST-*/T924-TX-*), or the requirements catalog (T924-R*); where this
contract needs a durable artifact it references those namespaces instead.

Evidence labels: VERIFIED = read at HEAD `adbe643` with file+symbol.
RECOMMENDATION = my technical proposal. PROPOSAL = open option for Director.
Precedence applied: Director decision > product invariants > final-target-migration > chapter-profile design > this stage spec.

---

## 1. Typed structured analysis provider API (T924-AP-01..)

### 1.0 Current-code grounding (why a new typed API is required)

- VERIFIED: the shared provider abstraction is `TextTranslator`
  (`app/src/main/java/eu/kanade/translation/translator/TextTranslator.kt`) →
  `BaseTranslator` (flat engines) → `AiTranslator : BaseTranslator(), ContextualTextTranslator`
  (`translator/providers/AiTranslator.kt:13`). `ContextualTextTranslator` is
  declared in `translator/contextual/TranslationContextChunkPlanner.kt:245-251`
  with `translateContextual`, `translateContextualStructured`, and `promptText`
  (comment: "used for glossary generation and summarization").
- VERIFIED: `promptText()` swallows every non-cancellation error and returns
  `""` in all four AI providers: `GeminiTranslator.kt:123-133`,
  `DeepSeekTranslator.kt:76-104`, `LmStudioTranslator.kt:81-110`,
  `OpenRouterTranslator.kt:75-103`. STRONG INFERENCE: `promptText` currently has
  **zero call sites** in `app/src/main` besides the interface declaration and
  these overrides (grep `.promptText(` → no callers), so the swallowing is
  latent today — but any future analysis built on `promptText` would inherit
  silent-empty-failure semantics. The analysis API must therefore be a new
  typed entry point, never `promptText`.
- VERIFIED: the structured path that does exist (`translateContextualStructured`)
  returns line-oriented `ID|text` batches parsed by
  `translator/contextual/ContextualResponseParser.kt:104-189`
  (`parseBatch`: canonical `pN_bN` normalization `:24-37`, unknown-ID /
  duplicate-ID / blank-output / missing-ID errors, `contentValid` gate) with
  protocol version `BatchTranslationProtocol.VERSION = 1`
  (`translator/contextual/BatchTranslationProtocol.kt:9`). There is no
  structured (JSON) response contract for arbitrary records.
- VERIFIED: HTTP failure classification already exists and is typed:
  `translator/retry/ProviderFailureClassification.kt:36-99`
  (`classifyHttpFailure`) and `:134-192` (`classifyProviderFailure`),
  producing `ProviderFailure` (`translator/ProviderRequestGovernor.kt:175-185`)
  with `ProviderFailureKind` (`:155-165`: NETWORK, RATE_LIMIT, QUOTA_EXHAUSTED,
  SERVER, AUTHENTICATION, REFUSAL, PROTOCOL, CONFIGURATION, SOURCE) and
  `ProviderFailureRetryability` (`:167-172`: RETRY_NOW, RETRY_AFTER, PAUSE,
  TERMINAL). Providers throw `ProviderFailureException` subclasses
  (`GeminiApiException` `GeminiTranslator.kt:312-326`;
  `OpenAiApiException` `OpenAiCompatibleTranslator.kt:217-219`).
- VERIFIED: every real HTTP attempt is admitted by
  `ProviderRequestGovernor.executeValue` (`GeminiTranslator.post` at
  `GeminiTranslator.kt:159-207`; `OpenAiCompatibleTranslator.postChatCompletion`
  at `OpenAiCompatibleTranslator.kt:57-145`), and retry wrapping is
  `withTranslationRetry` (`translator/retry/TranslationRetry.kt:136-275`,
  transport `maxAttempts=3`, exponential backoff 1s→30s cap, PAUSE beyond).

### T924-AP-01 Protocol principles

1. **Provider-independent wire protocol.** The analysis protocol is a single
   JSON document in the model's text output, identical for Gemini and
   OpenAI-compatible backends. Providers differ only in how they obtain the
   text (VERIFIED: Gemini extracts `candidates[0].content.parts` skipping
   `thought` parts — `GeminiTranslator.extractGeminiText`
   `GeminiTranslator.kt:339-359`; OpenAI-compatible reads
   `choices[0].message.content` — `OpenAiCompatibleTranslator.kt:123-126`).
   Thinking-tag stripping reuses the existing sanitizer
   (`OcrArtifactSanitizer.stripThinkingTags`, cited by
   `ContextualResponseParser.kt:108`).
2. **Versioned.** `schemaVersion` integer at the document root; v1 is the only
   accepted value in the first release; other values are
   `PROTOCOL_MALFORMED` (see §3) and are never forward-interpreted. Unknown-
   *field* tolerance inside v1 is allowed (additive evolution), matching the
   strict-on-version / lenient-on-extra-fields pattern.
3. **No free-form prompt channel.** Analysis calls use a new typed analyzer
   interface (T924-AP-02), not `promptText` (T924-AP-01 grounding above).
4. **Every request carries request identity and budget** so the governor and
   retry tree can attribute attempts (T924-AP-07).

### T924-AP-02 Analyzer entry point (interface shape, not implementation)

RECOMMENDATION: introduce one provider-side operation per backend, e.g.
`analyzeStructured(request: AnalysisRequest): AnalysisTextResult`, implemented
per backend next to the existing translation path and sharing:

- the same OkHttp client construction pattern and timeouts
  (VERIFIED 60s connect/read/write: `GeminiTranslator.kt:59-63`,
  `OpenAiCompatibleTranslator.kt:47-51`);
- the same governor admission with `ProviderRequestMetadata(
  operation = "analysis_chunk" | "analysis_master", priority = BACKGROUND)`;
- the same HTTP failure classification (`classifyHttpFailure`) and typed
  exception (`ProviderFailureException` subclass), so transport errors flow
  into the same taxonomy as translation (§3);
- the same `withTranslationRetry` + shared `RequestRetryBudget` semantics.

Native structured-output modes (Gemini `responseMimeType: application/json` +
`responseSchema`; OpenAI-compatible `response_format: json_schema`) are a
capability-gated optimization, not a protocol requirement: the contract is the
JSON document, whichever transport produced it. If a backend advertises
unreliable JSON mode, plain prompt-delimited JSON is the fallback. (PROPOSAL
for provider evaluation stage; not a product constant.)

### T924-AP-03 Analysis chunk request envelope

One extraction request covers whole pages, each marked `CORE` (records must be
extracted for it) or `CONTEXT` (overlap page: model may cite evidence from it,
must not emit primary records for it). Design basis: §6.1 of
`design/chapter-profile-batch-design.md` (whole-page chunks, ≤1 page / ≤10%
overlap, core-vs-context marking).

```json
{
  "envelope": {
    "protocol": "tachiyomiat-analysis",
    "schemaVersion": 1,
    "requestKind": "CHUNK",
    "chunkId": "ck-0007",
    "run": {
      "mangaKeyHash": "sha256:…",      // privacy-safe scope hash, never raw title
      "chapterKeyHash": "sha256:…",
      "runId": "uuid"
    },
    "languages": { "source": "ja", "target": "en" },
    "policy": {
      "analysisPolicyFingerprint": "sha256:…",   // prompt+schema+limits identity
      "ocrCorpusFingerprint": "sha256:…"          // whole-corpus identity (§4.4 of design)
    },
    "outputBudget": { "maxOutputTokens": 8192 }
  },
  "pages": [
    { "pageKey": "p12", "role": "CORE",    "blocks": [ { "blockId": "p12_b0", "text": "…" } ] },
    { "pageKey": "p13", "role": "CONTEXT", "blocks": [ { "blockId": "p13_b0", "text": "…" } ] }
  ],
  "existingCanon": {
    "userAuthority":   null,          // absent in first release (§4); never fabricated
    "seriesAuthority": null,
    "chapterFacts": [ /* canonical facts frozen from earlier chunks' pre-merge,
                         capped per T924-AP-06; empty on chunk 0 */ ]
  }
}
```

Rules:

- `pageKey` and `blockId` reuse the existing stable identity scheme VERIFIED at
  `BatchTranslationProtocol.kt:11-16` (`p<N>`, `p<N>_b<M>`, natural page index,
  stable block index assigned by `StableBlockIds` — VERIFIED used by the retry
  controller freeze at `AiTranslationRetryController.kt:568`).
- `existingCanon` never contains model-invented facts; only (a) deterministic
  pre-merge results of earlier chunks of this run and (b) user/series authority
  when present (absent in the first release, §4). This is the "existing-canon
  payload" the chunker inserts; its cap is T924-AP-06.
- Text is the sanitized OCR text exactly as persisted; the request is the sole
  authority for what evidence may reference (T924-AP-05 validation).

### T924-AP-04 Analysis chunk response schema (sketch)

```json
{
  "schemaVersion": 1,
  "chunkId": "ck-0007",
  "terms": [{
    "termId": "t001",
    "sourceForm": "魔剣",
    "canonicalTarget": "demon sword",
    "aliases": ["魔剣アルステラ"],
    "kind": "TERM",
    "applicability": "CANONICAL_CHAPTER_WIDE",
    "availableFrom": null,
    "evidence": [ { "pageKey": "p12", "blockId": "p12_b3",
                    "excerptHash": "e:9f2a1c77b04d5e33", "strength": "EXPLICIT" } ]
  }],
  "entities": [{
    "entityId": "e001",
    "sourceNames": ["レイナ", "レイナ・アルステラ"],
    "canonicalSourceName": "レイナ・アルステラ",
    "proposedTargetName": "Reina Alstella",
    "titles": ["剣姫"],
    "gender": { "value": "FEMALE", "strength": "EXPLICIT",
                "evidence": [ { "pageKey": "p12", "blockId": "p12_b7",
                                "excerptHash": "e:41c0…", "strength": "EXPLICIT" } ] },
    "pronounFacts": [ { "value": "彼女", "strength": "EXPLICIT",
                        "evidence": [ /* same shape */ ] } ],
    "relationships": [ { "type": "RIVAL_OF", "targetEntityId": "e002",
                         "evidence": [ /* same shape */ ] } ],
    "conflicts": [ { "topic": "GENDER", "note": "earlier masculine speech style",
                     "evidence": [ /* same shape, strength WEAK */ ] } ],
    "applicability": "CANONICAL_CHAPTER_WIDE"
  }],
  "scenes": [{
    "sceneId": "s001",
    "range": { "fromPage": "p12", "fromBlock": "p12_b0",
               "toPage": "p13", "toBlock": "p13_b18" },
    "participants": ["e001", "e002"],
    "tone": ["COMEDIC", "SERIOUS"],
    "contentTags": ["EXPLICIT", "VIOLENT", "INTIMATE"],
    "register": "CASUAL",
    "narrative": "concise situation of this range only, ≤ 600 chars"
  }],
  "narrative": {
    "summary": "range-scoped chapter-so-far synopsis ≤ 2000 chars",
    "revelations": [ { "text": "…", "availableFrom": "p12" } ]
  },
  "unresolvedQuestions": [ { "id": "u001", "question": "…",
                             "evidence": [ /* same shape */ ] } ],
  "candidateEquivalences": [ { "id": "c001", "sourceForms": ["レイナ", "ライナ"],
                               "hypothesis": "SAME_ENTITY", "confidence": "MEDIUM",
                               "evidence": [ /* same shape */ ] } ]
}
```

Closed enums (v1):

- `gender.value`: `MALE | FEMALE | UNKNOWN | CONFLICTING` (design §4.3).
- `strength`: `EXPLICIT | STRONG_CONTEXTUAL | WEAK` (design §4.3; master may
  promote gender only from EXPLICIT or corroborated STRONG_CONTEXTUAL — weak
  cues stay notes).
- `kind` (term): `NAME | PLACE | TERM | TITLE | ORG`.
- `applicability`: `CANONICAL_CHAPTER_WIDE | RANGE_SCOPED` and per-fact
  `availableFrom` for `AVAILABLE_FROM` semantics (design §4.3).
- `tone`: `COMEDIC | SERIOUS | ACTION | ROMANCE | HORROR | SLICE_OF_LIFE`;
  `contentTags`: `EXPLICIT | INTIMATE | VIOLENT | GORE` (open list, v2 growth).
- `hypothesis`: `SAME_ENTITY | SAME_TERM | NOT_EQUIVALENT | UNCLEAR`;
  `confidence`: `LOW | MEDIUM | HIGH`.

Summaries (`scenes.narrative`, `narrative.summary`) are supporting context only
and never replace structured records (product requirement,
`design/profile-preflight-requirements.md`: "Summaries complement structured
extraction, never replace it").

### T924-AP-05 Evidence syntax and validation failure rules

Evidence reference (every `evidence[]` element):

- `pageKey`: must equal a `pages[].pageKey` of this request (CORE or CONTEXT).
- `blockId`: must equal a `blocks[].blockId` inside a request page and must
  start with its `pageKey` (guards the common `p12_b4` cited under `p13`).
- `excerptHash`: `e:` + first 16 hex chars of SHA-256 over the normalized
  source text of the referenced block (NFC, CRLF→LF, whitespace-run collapse).
  The validator recomputes hashes locally from the chunk OCR corpus; a
  non-matching hash proves the model invented or paraphrased the anchor.
- `strength`: enum above; required on every reference.

**Hard-fail validation rules (v1 — all response-fatal, no partial retention):**

| # | Violation | Detection |
|---|---|---|
| V1 | Unknown reference (pageKey / blockId / entityId / termId / targetEntityId / recordRef not resolvable inside this request + `existingCanon` + already-emitted IDs) | referential check |
| V2 | Duplicate record ID (`termId`/`entityId`/`sceneId`/`u###`/`c###` repeated) | ID set check |
| V3 | Missing required field (e.g. `sourceForm`, `canonicalSourceName`, `range`, `evidence` on a gender fact) | schema check |
| V4 | Overlong field (caps in T924-AP-06) | length check |
| V5 | Invalid enum value | enum check |
| V6 | Non-JSON / truncated / fenced-but-unparseable body after sanitizer strip | parse check |
| V7 | `schemaVersion != 1` | version check |
| V8 | Evidence `excerptHash` mismatch (invented/paraphrased anchor) | hash recompute |
| V9 | Primary record anchored only on CONTEXT pages (core-page duty violation) | role check |

Rationale for response-fatal (vs record-level acceptance): analysis records are
cross-referential (entities ↔ terms ↔ scenes); partial acceptance can build a
silently incoherent candidate graph that later poisons the frozen profile. This
mirrors the verified translation stance that any unknown/duplicate ID makes the
whole batch attempt protocol-suspect (`AiTranslationRetryController.analyzeResponse`
sets `protocolIssue` on unknown/duplicate/conflict/malformed —
`AiTranslationRetryController.kt:709-726`), and design §6.2 which states these
failures fail validation.

Failure handling: the chunk result is discarded deterministically; exactly one
identical reissue is allowed under the shared root budget (T924-AP-07); a second
invalid response pauses the run at that chunk with a typed
`PROTOCOL_MALFORMED` failure. Resume restarts at the first invalid/missing
chunk (design §10 resume table row "Mid analysis chunk"; audit Stage 4 exit).

### T924-AP-06 Field-level caps (v1 defaults; tunable via analysis policy fingerprint)

| Field | Cap |
|---|---|
| `sourceForm`, `canonicalTarget`, `canonicalSourceName`, `proposedTargetName`, alias/title items | 120 chars each |
| aliases / titles per record | 8 items |
| entities per chunk | 48 |
| terms per chunk | 96 |
| scenes per chunk | 32 |
| unresolvedQuestions / candidateEquivalences per chunk | 24 each |
| evidence refs per fact | 8 |
| `scenes[].narrative` | 600 chars |
| `narrative.summary` | 2000 chars |
| relationships per entity | 12 |
| `existingCanon.chapterFacts` in request | 128 facts / 8K chars |
| `outputBudget.maxOutputTokens` | provider maxOutputToken setting |
| record ID pattern | `^[tesuc]\d{3,4}$` (scoped per chunk) |

All caps are part of the `analysisPolicyFingerprint`; changing a cap invalidates
the profile (invalidation matrix owner: T924-R*/SC-*), not merely future chunks.

### T924-AP-07 Master reconciliation request/response

Deterministic pre-merge runs locally first (Unicode form, exact alias match,
shared evidence IDs, compatible types — design §6.3). Only unresolvable
conflict sets go to the master model.

Request: `requestKind = "MASTER"`, same envelope head as T924-AP-03 plus:

```json
{
  "conflictSets": [{
    "conflictSetId": "cs-014",
    "kind": "GENDER | SPELLING | ALIAS | MERGE_ENTITIES | TERM_TARGET | TYPE",
    "candidates": [
      { "recordRef": "e001@ck-0003", "factSnapshot": { /* frozen fact JSON */ },
        "evidence": [ { "pageKey": "p9", "blockId": "p9_b2",
                        "excerptHash": "e:…", "strength": "EXPLICIT",
                        "excerpt": "≤ 80-char source snippet" } ] }
    ]
  }]
}
```

Response:

```json
{
  "schemaVersion": 1,
  "requestKind": "MASTER",
  "resolutions": [
    {
      "conflictSetId": "cs-014",
      "verdict": "CHOOSE",
      "chosenRecordRef": "e001@ck-0003",
      "mergedPatch": null,
      "rationale": "Explicit later identity evidence outranks the earlier weak masculine speech-style cue."
    },
    {
      "conflictSetId": "cs-016",
      "verdict": "RETAIN_AMBIGUITY",
      "chosenRecordRef": null,
      "mergedPatch": null,
      "rationale": "Two spellings with equally strong EXPLICIT evidence; keep both forms."
    }
  ],
  "unresolved": [ { "conflictSetId": "cs-015", "reason": "INSUFFICIENT_EVIDENCE" } ]
}
```

**Never-invent rule (normative):** every `evidence` element in the *request* is
the complete universe the master may cite; the response carries no new evidence
references at all — only `verdict` + `recordRef` + rationale. Validation (V1)
rejects any reference outside the request set. This implements design §6.3: the
master "must choose, retain ambiguity, or mark conflict; it cannot invent an
evidence reference." Evidence snippets are request-side (80-char cap) so the
master can judge without re-accessing pages.

Cap: ≤ 24 conflict sets / ≤ 8 candidates per set per master request; larger
graphs reconcile hierarchically by entity component plus a final index pass
(design §6.3).

### T924-AP-08 Reuse of governor/retry with typed errors (normative bridge)

1. Every analysis HTTP attempt is admitted through the same
   `ProviderRequestGovernor` bucket as translation (same
   `ProviderRequestKey(backend, model, credentialScope)`; `operation`
   distinguishes it in diagnostics). VERIFIED today's translation path already
   funnels through `executeValue` (`GeminiTranslator.kt:178`,
   `OpenAiCompatibleTranslator.kt:92`).
2. Priority: analysis is `BACKGROUND` via the default
   (`currentProviderRequestPriority()` defaults to BACKGROUND —
   `ProviderRequestGovernor.kt:131`; reader path wraps INTERACTIVE —
   `TranslationPipeline.kt:410-416`). Analysis needs no new priority value.
3. One `RequestRetryBudget` (default 8 attempts — VERIFIED
   `TranslationRetry.kt:72` `DEFAULT_MAX_ATTEMPTS = 8`) covers each analysis
   request *tree*: chunk requests are independent trees; split master runs
   share one root budget like the translation split tree (design §9.5).
   Accounting relation to translation: same `RequestRetryBudget` mechanism and
   `withTranslationRetry` transport wrapper (3 transport attempts nested in the
   root budget; governor consumes after admission — VERIFIED
   `ProviderRequestGovernor.kt:368-374`), but the *semantic* retry policy
   differs: no missing-only targeted retries exist for analysis (no per-ID
   contract), so the analysis policy is: 0-1 identical reissues on
   `PROTOCOL_MALFORMED`, then PAUSE. RECOMMENDED v1 policy:
   `maxIdenticalReissues = 1`, transport as today, root cap 8.
4. Errors surface as `ProviderFailure`/`ProviderFailureException` (typed), and
   analysis **schema** failures are classified `PROTOCOL` (T924-AP-05) instead
   of being swallowed. `promptText` (empty-string-on-error) is explicitly
   forbidden as the analysis transport (T924-AP-01/02).

---

## 2. Analyzer capability requirements and analyzer-vs-translator relationship

### 2.1 Capability requirements (gate inputs, not provider assumptions)

| Capability | Requirement (v1 gate) | Rationale |
|---|---|---|
| Structured-output reliability | Emits parseable JSON at the requested `maxOutputTokens` without truncation in ≥ an acceptance threshold measured in provider evaluation (audit decision 15 sets the number) | Truncated JSON = whole-chunk hard fail (V6); design §8 warns "larger context does not prove better structured-output reliability" |
| Context size | Usable input ≥ 32K tokens RECOMMENDED (hard floor 16K with reduced chunk sizes); analysis reserves ~50% of context for instructions + existing canon + output (design §6.1) | 8-page chunks + canon payload + output reserve |
| Language coverage | Reads OCR-noisy CJK (ja/ko/zh) source text; English field values with verbatim source forms | Primary corpus; `TextRecognizerLanguage` set VERIFIED includes JAPANESE/CHINESE/KOREAN (`TranslationPrompts.isProDrop`, `TranslationPrompts.kt:65-74`) |
| Instruction fidelity | Respects closed enums and caps; no fabricated evidence anchors (measurable via V8 hash recompute rate) | Validator is intentionally unforgiving |

These feed a **capability gate** function: `analyzerPolicy.supports(provider,
model)` evaluated before ANALYSIS_PLAN; a failing gate pauses with a typed
CONFIGURATION-class failure (§3) rather than producing garbage chunks.

### 2.2 Analyzer-vs-translator provider/model relationship — OPTIONS

Context (VERIFIED): today there is exactly one AI engine selected app-wide
(`AiTranslatorKind.kt:18-21`: GEMINI/OPENROUTER/DEEPSEEK/LMSTUDIO) with one
model per engine (`modelName` constructor param on each translator); model
discovery via `AiModelFetcher` keeps the selected model on failure. There is no
second "analyzer model" surface.

- **Option A — same provider+model as the translator.** Zero new settings; the
  analysis policy fingerprint reduces to the translation model. Cost: the
  translator model must then also be a good structured extractor (false for
  weak local LM Studio models); no cost/quality tuning freedom; a poor
  translator-but-good-analyzer or vice versa cannot be expressed.
- **Option B — independently configurable analyzer provider+model with
  capability gate; defaults to the translator's provider+model when unset.**
  One extra settings pair + capability gate; analysis (BACKGROUND, 15 RPM)
  can use a cheaper/stronger-structured model while translation keeps the
  user's choice; the unset default preserves today's single-config simplicity.
  Fingerprint cost already accounted: `ProfileInputFingerprint` includes
  "analysis schema/prompt/model/provider policy" (design §4.4).
- **Option C — separate analyzer fallback ladder** (ordered analyzer
  candidates, probe each). Maximum resilience, maximum complexity: probing
  costs paid calls, ordering UX, per-candidate fingerprints. Defer until
  provider measurements show single-model reliability is insufficient.

**RECOMMENDATION: Option B** (independently configurable, capability-gated,
default = translator's provider+model). Rationale: analysis and translation
have genuinely different optimal profiles (extraction vs fluency); the default-
equal fallback means zero behavior change for users who never touch the
setting; the capability gate prevents hopeless configs (small local models)
from burning the analysis budget; and the invalidation design (analysis policy
fingerprint) already prices the extra degree of freedom. Mapped decision
brief: **T924-DR-B** (README open decision 1, second half).

---

## 3. Typed provider error contract for analysis

### 3.1 Classification mapped from verified current behavior

The existing `ProviderFailureKind`/`ProviderFailureRetryability`
(`ProviderRequestGovernor.kt:155-172`) already form a typed spine. The analysis
contract reuses them verbatim and fixes two gaps. Mapping of the analysis-stage
classes (left) onto what current providers actually emit (right):

| Analysis contract class | Current emission (VERIFIED) | Retryability (analysis) |
|---|---|---|
| `TRANSPORT_RETRYABLE` | `NETWORK` — IOException/timeout (`classifyProviderFailure`, `ProviderFailureClassification.kt:145-148`, RETRY_NOW); `SERVER` — 5xx (`classifyHttpFailure:73-80`, RETRY_NOW, or RETRY_AFTER with header) | RETRY_NOW/RETRY_AFTER under `withTranslationRetry` (3 transport attempts) |
| `RATE_LIMITED` | `RATE_LIMIT` — 408/425/429 (`classifyHttpFailure:65-72`; RETRY_AFTER with header else RETRY_NOW) | RETRY_AFTER/RETRY_NOW; budget-aware |
| `QUOTA` (pause-class) | `QUOTA_EXHAUSTED` — 429 + quota/RESOURCE_EXHAUSTED signal (`classifyHttpFailure:57-64` → PAUSE or RETRY_AFTER); also `ProviderRequestPausedException` (admission defer, `ProviderRequestGovernor.kt:193-204`) | PAUSE; governor applies `quotaCooldownMs` (60s) cooldown (`recordFailure`, `ProviderRequestGovernor.kt:409-415`) |
| `AUTH` | `AUTHENTICATION` — 401/403 (`classifyHttpFailure:53-56`, TERMINAL) | TERMINAL; run pauses with user-actionable reason |
| `CONFIGURATION` | 4xx minus 401/403/408/429 (`classifyHttpFailure:81-84`, TERMINAL); `IllegalArgumentException` (`classifyProviderFailure:176-179`); capability-gate failure (new) | TERMINAL |
| `PROTOCOL_MALFORMED` | `PROTOCOL` — blank body / no completion content (`OpenAiCompatibleTranslator.kt:112-142`, TERMINAL today); Gemini empty text throws plain `GeminiEmptyResponseException` (`GeminiTranslator.kt:339-361`, currently an untyped `Exception` that falls through message classification to PROTOCOL/TERMINAL); batch validation errors → `protocolFailure` (`AiTranslationRetryController.kt:881-900`); **new:** analysis V1–V9 hard fails (T924-AP-05) | One identical reissue, then PAUSE (not TERMINAL: malformed output is model-state, not user-state) |
| `CONTENT_REFUSAL` | `REFUSAL` — in-protocol refusal markers via `TranslationResponseFaithfulness.isStructuralRefusal` (`TranslationResponseFaithfulness.kt:15-45`, wired at `AiTranslationRetryController.kt:697-705` → TERMINAL); message-keyword `refus|safety|policy` (`classifyProviderFailure:172-175`) | TERMINAL for the affected request; run pauses at that chunk (no silent skip) |
| (reserved) `SOURCE` | Enum member exists (`ProviderRequestGovernor.kt:164`) and is consumed by `pipeline/batch/BatchWriteGate.kt:30`, but no producer was found in the translator layer (grep). Recorded as-is; analysis does not introduce a producer. | n/a |

Gap fixes required by this contract:

1. **Normalize empty-response typing.** Gemini's `GeminiEmptyResponseException`
   should carry a `ProviderFailure(PROTOCOL, …)` payload like the
   OpenAI-compatible path already does, so analysis and translation see the
   same class. (RECOMMENDATION; aligns both backends on PROTOCOL_MALFORMED.)
2. **Retryability for malformed.** Translation classifies some protocol issues
   TERMINAL after bounded semantic retries (`AiTranslationRetryController` Paused
   vs Terminal outcomes, VERIFIED `:336-382, 465-509`). Analysis adopts the same
   *bounded* posture: PROTOCOL_MALFORMED is retryable exactly once (identical
   reissue), then PAUSE — never TERMINAL on first sight, never silently skipped.

### 3.2 Refusal/echo detection extension to analysis responses

- Refusal: `isStructuralRefusal` is applied to the raw analysis text before JSON
  parsing (whole-document refusal) — a refusing chunk is `CONTENT_REFUSAL`,
  retained nothing, PAUSE at that chunk after the single reissue.
- Echo (source↔response confusion): translation rejects targets equal to source
  (`AiTranslationRetryController.kt:691-695`). Analysis analogue (new, cheap,
  deterministic): response JSON containing a verbatim ≥ 40-char run of request
  block text inside a *narrative/summary* field is flagged; inside
  `sourceForm`/`canonicalSourceName` it is expected (verbatim quotation is the
  schema's job), elsewhere suspicious → advisory counter, promoted to hard fail
  (V8 covers the strong case: invented evidence anchors) — instrument first,
  tighten after provider data. (RECOMMENDATION; do not hard-fail on the
  advisory signal in v1, mirroring `coverageConcern`'s advisory stance —
  `TranslationResponseFaithfulness.kt:47-69`.)
- User-edit fence analogue: translation ignores results for user-edited blocks
  (`AiTranslationRetryController.kt:680-684`). Analysis has no per-block writes,
  so the fence is upstream: chunks are built only from the frozen OCR corpus
  (T924-AP-03), and the profile is immutable after freeze (design §3.1 state 9).

### 3.3 Retry accounting relation to the translation budget

- Same `RequestRetryBudget` class, same 8-attempt default, same
  consume-after-admission rule (T924-AP-08). One budget per chunk tree; one
  budget per master-reconciliation tree (split master = children share the
  root budget, exactly the translation split rule of design §9.5).
- Analysis and translation trees are **separate budgets** (separate semantic
  transactions) but share the same provider pacing (§1, DR-C/D): retries in
  either layer consume real quota and therefore compete inside the Batch
  sub-limit.
- Diagnostics: reuse `BatchTranslationDiagnostics.envelopeLifecycle` phases
  (PROVIDER_REQUEST/RETRY/FAILED — VERIFIED usage in
  `TranslationRetry.kt:162-267`) with analysis-specific `operation` labels, so
  the instrumentation required by §5 comes for free.

---

## 4. Authority inputs for the first release (audit blocking decision 10)

**RECOMMENDATION (already the design lean, made explicit for Director
acceptance): chapter-only profile.** No user-canon and no series-canon inputs
exist in the app today — VERIFIED: grep for `userGlossary|seriesGlossary|*Canon*`
over `app/src/main` returns nothing; the only glossary is the chapter-scoped
vocabulary map (`store/ChapterGlossaryStore.kt:17-22` — `Map<String,String>`,
numeric version gate `:37-40`; `translator/contextual/ChapterGlossaryBuilder.kt`
— advisory, `MIN_RECURRENCE=3`, `MAX_ENTRIES=30`). Building user/series
authority storage is a separate work package (design §6.4).

### 4.1 Absent-authority fingerprinting (normative)

`ProfileInputFingerprint` includes "input user/series authority fingerprints"
(design §4.4). Absent authorities must therefore fingerprint **as a stable
empty value, not as null/omission**, so that:

- the same run inputs always produce the same fingerprint (reproducibility —
  audit Stage 1 exit: "semantic-equivalent artifacts hash identically");
- introducing the first user/series entry later changes the fingerprint and
  correctly invalidates the profile.

Specification:

```text
emptyUserAuthorityFingerprint   = sha256("T924-AUTH|v1|USER|count=0")
emptySeriesAuthorityFingerprint = sha256("T924-AUTH|v1|SERIES|scope=" + mangaKeyHash + "|count=0")
```

- Both are computed by the same canonical-serialization routine that will
  serialize a populated authority artifact (same field order, same
  normalization), with `count=0` and empty entry arrays — not a special-case
  constant. This guarantees a smooth transition when authorities gain entries.
- The user authority is deliberately scope-free (user-wide canon intent); the
  series authority is manga-scoped (`mangaKeyHash` = the same privacy-safe hash
  as T924-AP-03). If the Director later decides series canon is source-scoped,
  that changes the fingerprint domain — recorded as an open question (§8).

### 4.2 Series-update candidates in the profile (normative)

The frozen `ChapterTranslationProfile` MAY carry:

```text
SeriesUpdateCandidate
  candidateId
  kind: GENDER | SPELLING | ALIAS | TERM_TARGET | RELATIONSHIP
  proposedContent        // the fact as the model derived it
  evidenceRefs           // validated chunk evidence (T924-AP-05 syntax)
  supportingEntityId / supportingTermId
  status: CANDIDATE      // the only legal status in v1
  sourceChapterKeyHash, analyzerProvenance
```

Rules: candidates are **never consulted** by the relevant-subset matcher, never
injected into `existingCanon`, and never auto-promoted (final-target-migration
§8.5 "Reject automatic permanent canon promotion"; design §6.4). Promotion is a
future workflow (user confirmation or cross-chapter corroboration) outside this
contract. Corroboration across chapters will be computable later because each
candidate records its chapter scope and evidence.

### 4.3 Display/UX implications (brief)

- Settings: no new UI in the first release. Where translation settings mention
  glossary/terminology, the state is implicitly "chapter analysis only"; no
  empty-state screens are required because no authority feature is advertised.
- Profile inspection (debug/diagnostics surfaces): facts carry provenance
  `chapter-profile` and candidates are labeled "candidate — not applied";
  nothing user-facing claims series knowledge.
- Manual/Auto behavior unchanged (they do not consume the profile in v1).

Maps to audit blocking decision 10; the UX/promotion workflow itself remains
README open decision 5 (Director), informed but not resolved here.

---

## 5. Small-chapter / no-translatable-work policy (audit blocking decision 11)

**Settled (no Director input needed):** no translatable work ⇒ skip provider
analysis. VERIFIED as the recorded design: ANALYSIS_PLAN skips when "there is
no translatable work" (`design/chapter-profile-batch-design.md` §3.1 state 6;
textless chapters likewise; design §12 "Skip provider analysis for textless
chapters, no remaining translation work, or a compatible frozen profile").

**Small-chapter bypass: measurement-gated.** Design §12 explicitly declines to
claim cost savings before data ("Consider a product threshold for very small
chapters after measurements"). Contract:

1. **Instrumentation (required before any threshold is accepted).** Per run,
   emit via the existing diagnostics channel (§3.3): `pageCount`,
   `pendingBlockCount`, `estimatedCorpusTokens`, `chunksPlanned (A)`,
   `masterCallsPlanned (M)`, `analysisInputTokens/analysisOutputTokens`
   (actual), `translationEnvelopes`, `retriesByTaxonomyClass`,
   `malformedRate`, `profileFreezeMs`, `firstAcceptedPageMs`, and — when a
   bypass would have triggered — `bypassWouldApply=true` with the counterfactual
   A+M and envelope counts. This is a pure logging contract; no behavior change.
2. **Proposed default rule (PROPOSAL, disabled until Stage-8 evidence per audit
   decision 16):** skip provider analysis when the *entire* translatable corpus
   fits inside a single translation envelope under the accepted global-planner
   budgets: `pendingBlocks ≤ envelopeBlockCap` AND
   `estimatedSourceTokens + maxProfileSubsetTokens + outputReserve ≤ envelopeInputBudget`.
   Rationale (principled, not numeric): if the whole chapter is visible in one
   envelope, the model already has global context during translation, and the
   profile premium `A+M` adds calls without adding information the envelope
   didn't have. With the 32-block structural experiment (design §8), the
   numeric face of this rule is "roughly ≤ 24-32 blocks" — consistent with the
   Director's 20-25/30-40 block intuition, but derived from envelope fit rather
   than a magic constant.
3. Behavior when bypassed: proceed to ENVELOPE_PLAN with an empty/absent
   profile (translation contract's profile-subset matcher receives the empty
   profile and is a no-op); rolling history and gap-free frontier unchanged.
   The run record records `analysisSkipped=BYPASS_SMALL` vs
   `analysisSkipped=NO_WORK` vs `analysisRan` for later measurement.
4. Manual/Auto are unaffected in all cases (they never waited on analysis).

Maps to README open decision 4 / audit decisions 11 and 16 (threshold value is
tunable during flagged evaluation; the *rule shape* is what this contract
fixes).

---

## 6. Decision brief T924-DR-A — mixed malformed-response retention (README open decision 6 = audit blocking decision 8)

Base taxonomy (design §9.2, normative input): `MISSING_ONLY` (no unknown IDs,
duplicates, conflicts, malformed lines, framing/version error, refusal or
ownership error), `AMBIGUOUS_PROTOCOL` (any of those), `TERMINAL_REFUSAL/CONTENT`.
Accepted blocks from an incomplete page never render or advance context
(design §9.2, product invariant "gap-free context" — README constraints).

**Option 1 — commit independently complete pages immediately** (their own
durable transactions, `MISSING_ONLY` case only).

**Option 2 — retain them as non-display candidates** until split/backoff
recovery for the whole original parent completes.

Analysis:

- *Context advancement.* Both options obey the frontier invariant: only
  contiguous fully committed pages advance `BatchContextFrontier` (design §9.7;
  VERIFIED current rule that rolling context is produced only for `Complete`
  outcomes and completed natural prefixes — `AiTranslationRetryController.kt:774-798`
  `completedNaturalPrefix`). Under Option 1, a page beyond a gap commits but
  cannot feed rolling history for later envelopes until the gap fills; those
  later envelopes were dispatched with context ending at the frontier either
  way. Net: no invariant difference — only *provenance* differences (Option-1
  pages carry "context ended earlier" provenance).
- *Cost.* Option 1 never re-pays tokens for pages already validated: a
  subsequent child failure cannot destroy paid work. Option 2 must define what
  happens to candidates when a sibling child fails terminally or the budget
  exhausts: either discard (pure rework of paid tokens) or promote-at-exhaustion
  (which converges to Option 1 at exhaustion anyway, plus candidate GC and
  crash-recovery complexity for attempt-scoped candidates — design §10 resume
  table already expects "resume at first unresolved gap without repaying valid
  fragments", which Option 1 gets for free from normal commits).
- *Display.* Option 1 shows scattered completed pages in a partially failed
  chapter (hole at the gap); Option 2 shows only the contiguous prefix until
  recovery resolves, then reveals a contiguous block. Candidate/committed
  display separation already exists (README constraints; store candidate
  semantics), so both are implementable; the difference is product taste:
  faster partial results vs contiguous reveal.
- *Determinism/recovery.* Option 1 keeps one commit shape (page commit) and
  needs no new "candidate held hostage to a parent attempt" state; Option 2
  adds a parent-attempt-to-candidate binding that must survive process death
  (more state machine surface for T924-ST-*/T924-TX-*).

**RECOMMENDATION: Option 1, with the design §9.2 guardrails — i.e., commit
independently complete pages immediately as independent transactions, but
only for `MISSING_ONLY`; `AMBIGUOUS_PROTOCOL` and `TERMINAL_REFUSAL` discard
every value of the parent attempt before any smaller retry (unknown extra
lines make the parent AMBIGUOUS_PROTOCOL and nothing survives it — design
§9.4). Commits beyond a gap do not advance the frontier and record their
context-frontier provenance.**

Rationale: it converts paid success into durable value at the earliest legal
point, matches the verified accumulator behavior that already fences accepted
values against overwrite/conflict (`AiTranslationAccumulator.merge`,
`AiTranslationRetryController.kt:186-217`), requires no new candidate-binding
state, and degrades to contiguous reveal anyway whenever the gap fills
quickly (the common case for split-recovery, which exists precisely because
smaller requests usually succeed). Sub-option for Director taste (does not
change the contract): whether, at *terminal exhaustion* of the gap page, the
run may promote already-committed post-gap pages for display while paused —
with Option 1 they are already committed, so this reduces to a display policy
question, not a data question.

Decision request: accept Option 1 (+ guardrails) for README open decision 6.

---

## 7. Additional decision briefs

### T924-DR-B — analyzer/translator relationship (README open decision 1, second half)

Options, analysis, and recommendation are in §2.2: **recommend Option B**
(independently configurable analyzer provider+model, capability-gated,
defaulting to the translator's provider+model). Schema dependency: the
analysis policy fingerprint must include the analyzer provider+model
(design §4.4) so switching analyzers invalidates profile and analysis
artifacts but not OCR. Decision request: accept Option B.

### T924-DR-C — provider/model quota table and TPM (README open decision 2)

Verified in-code today: one policy per `ProviderRequestKey(backend, model,
credentialScope)`; `ProviderQuotaPolicy` defaults 60 RPM / 60,000 TPM /
1000 ms spacing / maxInFlight 1 / 60 s window
(`ProviderRequestGovernor.kt:66-101`); `defaultPolicy` special-cases backend
`"desktop"` to 1000 RPM / unlimited TPM (`:655-664`); the shared singleton
applies `defaultPolicy` to all backends (`:669-673`). So **every real backend
(gemini, openrouter, deepseek, lm_studio — backend strings VERIFIED at
`GeminiTranslator.kt:168`, `OpenRouterTranslator.kt:29`, `DeepSeekTranslator.kt:29`,
`LmStudioTranslator.kt:31`) currently runs at 60 RPM/60K TPM**, and `lm_studio`
does not get the desktop relaxation despite being local.

Proposed default table (values are configuration data; all marked **tunable
per audit decision 12**; provider-external limits are design-provided, not
repo-verified — flagged):

| Provider key | Provider-wide policy (bucket 1) | Batch aggregate sub-limit (bucket 2, shared analysis+translation) | Notes |
|---|---|---|---|
| `gemini` (free credential) | **15 RPM** total; TPM: keep 60K in-code default initially, raise only after provider-verified docs | **15 RPM** (equals the total; reader INTERACTIVE priority consumes the shared total) | Design §12: "For a Gemini free credential whose total quota is 15 RPM, both limits are 15". Gemini free tier also has *daily* request quotas not representable in today's RPM/TPM windows — open question §8 |
| `gemini` (paid credential) | provider-verified paid limit (UNVERIFIED — configure from docs at setup) | 15 RPM | Sub-limit stays conservative regardless of paid headroom |
| `deepseek` | 60 RPM / 60K TPM (in-code default, verified) | 15 RPM | Provider-wide verified-limit upgrade allowed later |
| `openrouter` | 60 RPM / 60K TPM (in-code default, verified) | 15 RPM | OpenRouter per-model limits vary by account — treated as provider-wide conservative |
| `lm_studio` (local) | RECOMMEND desktop-class policy (high RPM / no effective TPM) since it is local and today mis-fits the 60 RPM default | 15 RPM initially; likely removable after measurement | Analysis+translation to a local box at 15 RPM is needlessly slow; tunable |
| `desktop` (existing special case) | 1000 RPM / unlimited (verified `:655-663`) | 15 RPM | Unchanged |

Normative: the Batch sub-limit is **one** allowance shared by analysis and
translation requests — never two pools of 15 (final-target-migration §8.6
"Reject independent analysis and translation RPM pools"); Manual/Auto stay
INTERACTIVE and never enter bucket 2.

Decision request: accept the table as flagged-evaluation defaults.

### T924-DR-D — 15-RPM rolling-window implementation (README open decision 2, mechanism)

**RECOMMENDATION: nested governor buckets, no forced sleeps, no new governor
code path.** Concretely:

- Bucket 1 = the existing provider bucket, exactly as verified
  (`ProviderRequestKey(backend, model, credentialScope)`; rolling window via
  `reservations` deque + `prune`, `windowMs=60_000`, VERIFIED
  `ProviderRequestGovernor.kt:263-270, 548-552`).
- Bucket 2 = a second `ProviderRequestGovernor` bucket keyed by
  `ProviderRequestKey(backend = "<backend>", model = null,
  credentialScope = <credential>)` — i.e., **credential-wide, model-agnostic**
  — with `ProviderQuotaPolicy(requestsPerMinute = 15, tokensPerMinute = …,
  maxInFlight = 1, windowMs = 60_000, minimumSpacingMs = ≤1000)`. Its rolling
  window yields burst tolerance (15 per any 60 s) instead of a forced 4-second
  spacing — matching design §12 ("rolling windows, not forced four-second
  sleeps").
- Batch analysis and translation requests admit through bucket 2 → bucket 1
  sequentially (all-or-nothing: if bucket 1 defers after bucket 2 granted,
  release bucket 2's permit before waiting — permits are short-lived because
  `maxInFlight=1` serializes Batch traffic anyway, so there is no hold-while-
  waiting deadlock surface). Interactive requests skip bucket 2 entirely, so
  reader latency is structurally unaffected; the verified interactive reserve
  (`evaluate`, `:443-468`: while an INTERACTIVE waiter waits, BACKGROUND sees
  `RPM-1` and `TPM×0.8`) continues to protect readers inside bucket 1, and the
  verified starvation guard (`selectWaiter`, `:533-546`: background waiter
  older than `interactiveMaxAgeMs`=30 s wins FIFO) prevents infinite Batch
  starvation in bucket 1.
- Why not forced sleeps / why not modify `evaluate()`: the existing bucket
  machinery already implements exactly the needed semantics (rolling window,
  spacing, in-flight cap, cooldown); a second policy instance is data, not
  code. Keep `minimumSpacingMs` small in bucket 2 (≤1000 ms) so pacing comes
  from the window, not from spacing.

Decision request: accept nested-bucket mechanism; exact TPM values per DR-C.

---

## 8. Conflicts / open questions

Recorded, not silently resolved:

1. **`promptText` is dead-but-dangerous surface.** VERIFIED: no production
   call sites (only the interface declaration and four swallowing overrides),
   yet the interface comment claims use "for glossary generation and
   summarization". Whether to deprecate/remove it in the analyzer work package
   is an implementation-scope question for the Main Leader (removal touches
   the provider interface contract; keeping it risks future silent-empty
   misuse). This contract only forbids its use for analysis.
2. **`GeminiEmptyResponseException` is untyped** (plain `Exception`,
   `GeminiTranslator.kt:361`) while the OpenAI-compatible path throws typed
   `PROTOCOL` failures for the same condition (`OpenAiCompatibleTranslator.kt:112-142`).
   The typed error contract (§3.1 gap fix 1) assumes normalization; the actual
   edit belongs to a provider work package.
3. **Daily quotas are unrepresentable.** Gemini free tier enforces per-day
   request ceilings; `ProviderQuotaPolicy` has only RPM/TPM windows. A
   long-running 200-page chapter can satisfy 15 RPM and still exhaust a daily
   budget mid-run. Needs either a daily window kind or documented "pause on
   QUOTA and resume next day" behavior — Director/product decision later.
4. **15 RPM is design intent, not a provider-verified constant.** For
   non-Gemini providers the real sustained limits are UNKNOWN from this repo;
   DR-C marks all provider-external numbers tunable pending verification.
5. **Series-canon scope domain** (manga vs source vs global) is undecided and
   affects the series authority fingerprint (§4.1). Chapter-only first release
   is unaffected.
6. **`ProviderFailureKind.SOURCE`** has a consumer (`BatchWriteGate.kt:30`)
   but no producer found in the translator layer; either dead or produced
   outside the read set. No action in this contract; flagged for the schema
   agent so the failure taxonomy doesn't double-define it.
7. **Analyzer structured-output threshold numbers** (acceptance rate in §2.1,
   TPM values in DR-C) are deliberately unset here — they belong to the
   provider-evaluation gate (audit decisions 12/15) and must not be invented
   by this contract.
8. **Product tension recorded for DR-A:** Option 1 trades contiguous reveal
   for earlier durable value. If the Director prefers contiguous reveal as a
   product invariant, Option 2 plus a promotion-at-exhaustion rule is the
   coherent alternative; the cost delta is bounded rework of post-gap pages
   already analyzed in §6.

---

## Verification appendix (symbols actually read at HEAD `adbe643`)

| Claim | File : symbol/lines |
|---|---|
| Provider base interfaces | `translator/TextTranslator.kt`; `translator/providers/BaseTranslator.kt`; `translator/providers/AiTranslator.kt:13`; `translator/contextual/TranslationContextChunkPlanner.kt:245-251` (`ContextualTextTranslator`) |
| promptText swallow | `GeminiTranslator.kt:123-133`; `DeepSeekTranslator.kt:76-104`; `LmStudioTranslator.kt:81-110`; `OpenRouterTranslator.kt:75-103` |
| Gemini HTTP/transport | `GeminiTranslator.kt:135-207` (`generateContent`, `post`), `:312-326` (`GeminiApiException`), `:339-361` (`extractGeminiText`, `GeminiEmptyResponseException`) |
| OpenAI-compatible HTTP/transport | `OpenAiCompatibleTranslator.kt:57-145` (`postChatCompletion`), `:153-203` (`parseContextualCompletion`), `:217-219` |
| Failure classification | `translator/retry/ProviderFailureClassification.kt:36-99` (`classifyHttpFailure`), `:101-132`, `:134-192` (`classifyProviderFailure`), `:13-34` (`RetryAfterParser`) |
| Failure types | `translator/ProviderRequestGovernor.kt:155-172` (kinds/retryability), `:175-190` (`ProviderFailure`, `ProviderFailureException`), `:193-204` (`ProviderRequestPausedException`) |
| Governor defaults/priority/window | `ProviderRequestGovernor.kt:66-101` (`ProviderQuotaPolicy`), `:443-482` (interactive reserve in `evaluate`), `:533-546` (`selectWaiter` starvation guard), `:548-552` (`prune` rolling window), `:655-664` (`defaultPolicy`), `:669-673` (`SharedProviderRequestGovernor`), `:368-374` (budget consume after admission), `:401-416` (`recordFailure` cooldown) |
| Retry budget/transport | `translator/retry/TranslationRetry.kt:40-74` (`RequestRetryBudget`, 8 default), `:136-275` (`withTranslationRetry`) |
| Semantic retry controller | `translator/retry/AiTranslationRetryController.kt:41-51` (`AiTranslationRetryPolicy`), `:62-110` (`AiChunkOutcome`), `:186-217` (`AiTranslationAccumulator.merge`), `:264-511` (`runEnvelope`), `:651-739` (`analyzeResponse` incl. echo `:691-695`, refusal `:697-705`, user-edit fence `:680-684`), `:853-879` (`completedNaturalPrefix`), `:881-907` (`protocolFailure`, `budgetFailure`) |
| Strict parser | `translator/contextual/ContextualResponseParser.kt:24-37` (`normalizeBatchId`), `:104-189` (`parseBatch`) |
| Protocol identity | `translator/contextual/BatchTranslationProtocol.kt:9-16` |
| Refusal/echo heuristics | `translator/contextual/TranslationResponseFaithfulness.kt:15-69` |
| Current prompt (no evidence model) | `translator/contextual/TranslationPrompts.kt:76-107` (`pass1SystemPrompt`), `:34-51` (`contextPrefix`) |
| Priorities | `TranslationPipeline.kt:410-416` (INTERACTIVE reader path); default BACKGROUND `ProviderRequestGovernor.kt:55,131` |
| Chapter glossary only | `store/ChapterGlossaryStore.kt:17-40`; `translator/contextual/ChapterGlossaryBuilder.kt` (constants at top); `artifact/ChapterArtifactManifest.kt:37,276` (`GlossaryPointer`); no user/series canon grep hits |
| Engine/model config surface | `translator/AiTranslatorKind.kt:18-21,42-58`; `translator/providers/AiModelFetcher.kt:16-27` |
