# Adversarial Verification — Phase 1 "quick wins" (items 1.1, 1.2)

2026-09-14. Red-team of EXECUTION_ORDER.md Phase 1 against the actual sources.
Read-only; no Gradle/tests run. All paths relative to repo root
`app/src/main/java/eu/kanade/translation/` unless prefixed. Framing constraint:
manual + auto + batch lanes must coexist; nothing may regress normal manga.

---

## ATTACK 1.1 — Glossary incremental accumulator (O(P²·B) → O(B)), "independent of T930"

### VERDICT: NEEDS-ADJUSTMENT

The fold's cost claim is real and the Regex hoist is trivially safe, but the
item as specified (a) misattributes the stall to the batch lane, (b) is unsafe
against retries/re-translation as an add-only accumulator, (c) leaves resume
seeding unspecified with a misleading doc hint, and (d) has an unsynchronized
concurrency surface that today is masked by full recomputation. The headline
gate ("batch no longer stalls in glossary building") is untestable as written
because the batch lane never builds glossary.

### Evidence table

| # | Finding | Evidence (file:line) |
|---|---------|----------------------|
| A | `store.updateGlossary(...)` has exactly ONE production caller: the fold. Delegation only elsewhere. | `pipeline/SinglePageHttpRenderPhase.kt:444-450` (fold); `ChapterTranslationStore.kt:2473-2476` (pass-through); `store/ChapterGlossaryStore.kt:55` (impl). Repo-wide grep of `updateGlossary`/`translatedPairs` in app/src/main found no other call site. |
| B | The batch lanes NEVER build glossary. AI profile pipeline: envelope translation reads glossary for prompts only. Standard lane: "NO glossary reads/writes"; per-page tail uses the plain `translatePage`, not the contextual fold. | `pipeline/batch/ChapterProfileBatchCoordinator.kt:133` ("NO frozen-profile reuse probe … NO glossary reads/writes"); `pipeline/batch/ProfileEnvelopeExecutor.kt:516,884` (reads `profileAwareGlossaryPrefix` only); `pipeline/batch/BatchLaneWorkers.kt:934,956` (`textTranslator.translatePage(pageKey, p)`); `pipeline/batch/BatchChapterTranslator.kt:819-822` ("analysis/profile/envelope/glossary work never runs here"). |
| C | The fold runs ONLY on the MANUAL and AUTO lanes, and only for contextual AI translators. MANUAL: reader tap boundary → onnx → http render. AUTO: rolling-auto prepared boundary (two entry points). | `TranslationPipeline.kt:670` (MANUAL origin, inside `runGrantedSinglePageBoundary`); `TranslationPipeline.kt:1193` (`PageWriteOrigin.AUTO` inside `translatePreparedPage`); `scheduling/RollingAutoCoordinator.kt:68,500` (drives `translatePreparedPage`); `SinglePageHttpRenderPhase.kt:444` (`if (activeTranslator is ContextualTextTranslator)`). |
| D | Current fold is a full recompute-and-REPLACE: read all store pairs + this page's new blocks → `build()` → wholesale replacement. This is idempotent and self-healing; the code itself documents the concurrency stance as "rare, converging, accepted". | `SinglePageHttpRenderPhase.kt:445-449` (recompute), `ChapterGlossaryStore.kt:74` (`glossary = updated.toMap()`), `SinglePageHttpRenderPhase.kt:451-456` ("A concurrent mode's fold … rare, converging, accepted"). |
| E | In-call retries precede the single fold (so one call folds once), but page RE-translation (manual force re-tap, auto re-run) re-enters the phase with the page's OLD translation still in the store. Today the next fold replaces the map and the stale contribution decays; an add-only accumulator makes stale counts PERMANENT. | Partial-retry loop `SinglePageHttpRenderPhase.kt:405-420` and epoch retry `:335-359` both complete before the fold at `:444`; retranslate provenance comment `:189-193`; `store/ChapterGlossaryStore.kt:46-53` (`translatedPairs()` reads live `store.pages`). |
| F | Count arithmetic is threshold-sensitive: inflating counts spuriously promotes entries (MIN_RECURRENCE=3) and deflates other renderings' recall (count/total ≥ 0.8) → lost entries; counts are monotone in an add-only design. | `translator/contextual/ChapterGlossaryBuilder.kt:70` (count increment), `:84` (`>= MIN_RECURRENCE`), `:101-102` (`recall = count/total >= RECALL_THRESHOLD`), `:23,27` (constants). |
| G | Resume seeding is unspecified in the plan; the doc hint points at the WRONG seed. The persisted glossary is a capped `cand→rendering` map (max 30 entries), NOT pairs; seeding `add(cand, rendering)` injects 1 count per entry and loses all recurrence history for non-entry terms → glossary content becomes process-lifetime-dependent versus today's full re-derivation from store pages. | `EXECUTION_ORDER.md:29-34` (says only "incremental Stats accumulator at the fold"); `MILESTONES.md:21` ("the accumulator class already documents incremental seeding"); `ChapterGlossaryBuilder.kt:53-57` ("Safe to seed from an existing glossary's pairs on batch resume" — ambiguous/wrong); `ChapterGlossaryStore.kt:22` (`glossary: Map<String,String>`), `:24` cap `MAX_ENTRIES = 30` at `ChapterGlossaryBuilder.kt:24`. |
| H | Concurrency: `translatedPairs()` reads `store.pages` WITHOUT the store mutex; only `updateGlossary` locks. Two folds for one chapter can interleave (MANUAL page tap while AUTO translates a different page — different-page concurrent ownership is a designed state, see D1 leases). `Stats` is two plain `HashMap`s, not thread-safe. Today's read-build-replace race self-heals on the next fold; a shared incremental accumulator converts that into either data corruption (unsynchronized) or permanent lost updates (each pair added exactly once, by design). | `ChapterGlossaryStore.kt:46-53` (unlocked read), `:62` (`store.mutex.withLock` only inside `updateGlossary`); `ChapterGlossaryBuilder.kt:59-60` (`HashMap` fields); concurrent MANUAL/AUTO on distinct pages is the norm handled by lease logic in `BatchChapterTranslator.kt:675-695` ( Denied-lease = another owner owns the page). |
| I | Safety-net coupling: `D5GlossaryAwareReuseTest` (in `coexistence/`, the ~12% net) pins glossary-version cadence through the store's equality gate; a fold change that alters call cadence or map-equality semantics lands on a net test. Today it calls `store.updateGlossary(...)` directly (bypasses the fold), so it survives a pure fold optimization — but only if the store contract `if (glossary != updated)` and the post-fold version stamp ordering are preserved. | `app/src/test/java/eu/kanade/translation/coexistence/D5GlossaryAwareReuseTest.kt:239-253,294-298` (direct `updateGlossary`, version 1 pinned, "re-folding identical pairs must not bump the version"); `ChapterGlossaryStore.kt:63` (`if (glossary != updated)`); stamp ordering `SinglePageHttpRenderPhase.kt:451-459`. |
| J | Tests that pin the accumulator's semantics are pure-unit only; no test exercises the FOLD wiring end-to-end, so a semantic regression in the fold would not be caught by the suite. | `app/src/test/.../translator/contextual/ChapterGlossaryBuilderTest.kt:10-55` (pure Stats cases); grep for `updateGlossary`/`translatedPairs` in app/src/test hits only `ChapterTranslationStoreArtifactMigrationTest.kt` and `D5GlossaryAwareReuseTest.kt`. |
| K | Regex hoist: `Regex("[^\\p{L}\\p{M}\\-']+")` compiled per call inside `capitalizedTokens`, invoked per `add()`. Hoisting to a constant is pure and safe. No findings. | `ChapterGlossaryBuilder.kt:129-132`; called from `add()` at `:68`. |

### Attacks that survive (ranked by regression risk to lane coexistence)

1. **Lane misattribution — the "batch stall" is not in the batch lane.**
   Evidence B+C: the fold executes on MANUAL/AUTO only; both batch pipelines
   are glossary-read-only. EXECUTION_ORDER.md:29 claims 1.1 "kills the batch
   stall on huge chapters" and GATE P1 (EXECUTION_ORDER.md:41-42) demands
   "text-heavy chapter batch no longer stalls in glossary building" — a gate
   the batch lane cannot possibly exercise. Either the Director's "batch"
   means the rolling-AUTO chapter run (then say so; the fix is correctly
   placed) or the fix is pointed at the wrong code. Risk: the actual reported
   stall may survive Phase 1 while the plan declares victory.
2. **Retry/re-translation double-count (manual + auto quality regression).**
   Evidence E+F: add-only accumulation enshrines stale renderings forever and
   both promotes spurious entries and starves legitimate ones below the 0.8
   recall floor. Today's recompute converges; the replacement would not.
3. **Unspecified resume seeding with a doc hint pointing at the lossy seed.**
   Evidence G: an implementer following `Stats`'s own doc seeds from the
   30-entry glossary map and silently changes glossary content after every
   process restart (non-entry recurrence history lost). Correct seed is
   `store.translatedPairs()` once per store load — must be pinned in the plan.
4. **Concurrency: unsynchronized shared accumulator or permanent lost updates.**
   Evidence H: whichever way it is wired without the store mutex, it is worse
   than today: HashMap corruption (crash class) or non-self-healing lost
   contributions. This is the exact manual+auto coexistence window the framing
   says must not regress.
5. **Safety-net adjacency.** Evidence I: the fix is legal (D5 bypasses the
   fold), but only under the constraint that `updateGlossary`'s
   equal-map-no-op and the fold→stamp ordering are preserved verbatim. The
   plan should state this constraint explicitly so the implementer does not
   "improve" the store gate.

### Concrete spec fix (make 1.1 implementable without regression)

- Restate scope honestly: fold is MANUAL/AUTO-lane only; batch is
  glossary-read-only; the stall addressed is the AUTO rolling run / manual
  taps on huge chapters. Rewrite GATE P1 accordingly (e.g., "text-heavy
  chapter AUTO-lane run no longer re-mines the chapter per page").
- Specify the accumulator: per-`ChapterTranslationStore` long-lived counts
  object; ALL mutations (including `add`) happen under the store mutex;
  seeded once per store construction from `store.translatedPairs()` (never
  from the persisted glossary map); per-page dedup watermark (pageKey +
  translation identity) so a re-translated page's old pairs are subtracted or
  the accumulator falls back to full recompute for that page — never
  add-only.
- Pin the invariants: `build()` output for identical pair multisets is
  unchanged; `updateGlossary` equal-map no-op preserved (D5); fold-then-stamp
  ordering at `SinglePageHttpRenderPhase.kt:451-459` preserved.
- Note the independence caveat: the throughput half is independent of T930,
  but MILESTONES M1's full glossary item (publication batching into
  commit boundaries) is explicitly deferred to T930 Slice B — the plan already
  says this (EXECUTION_ORDER.md:33-34); the "independent of T930" label
  applies only to the compute half.

---

## ATTACK 1.2 — 8k lockdown compliance (every profile ≤ 8,192 total input+output)

### VERDICT: NEEDS-ADJUSTMENT

The binding directive is real (MILESTONES.md M0). The planner constant is a
one-line change and the DEFAULT profile is already compliant — but the plan's
scope misses the entire ANALYSIS stage, misstates where envelope packing reads
constants, specifies no input/output split, and its test gate cannot detect a
partial implementation.

### Evidence table

| # | Finding | Evidence (file:line) |
|---|---------|----------------------|
| A | Inventory of token-budget constants in main: planner DEFAULT is ALREADY 8,192 total; LM_STUDIO is the only planner violation (16,000). Semantics are TOTAL window (input+output), not input-only — the header and `effectiveOutputCap` both treat it so. | `translator/contextual/TranslationContextChunkPlanner.kt:21` (`MAX_CONTEXT_TOKENS = 8_192`), `:188-203` (`constraintsFor`: DEFAULT 8,192; LM_STUDIO 16,000), `:9-11` ("estimated input + context + output remains below MAX_CONTEXT_TOKENS"); `translator/contextual/StreamingChunkPlanner.kt:236-258` (`available = maxContextTokens − safetyMargin − promptTokens − protocolReserve`; result coerced into `[minOutputTokens, available]` with a 256 floor). |
| B | The arithmetic contradiction (output budget 8,192 leaves zero input) is RESOLVED BY EXISTING CLAMPS: the user "max output tokens" pref defaults to 8,192 but is an upper bound that `effectiveOutputCap` shrinks to fit. No zero-input state can occur in the planner paths. However the clamp has a 256-token FLOOR: an over-budget prompt still ships, with only 256 output. | `SinglePageHttpRenderPhase.kt:245-246`, `pipeline/batch/BatchChapterTranslator.kt:360`, `translator/AiTranslatorKind.kt:31`, `translator/providers/AiTranslator.kt:63` (defaults 8,192); `StreamingChunkPlanner.kt:250-256` (`.coerceAtMost(available).coerceAtLeast(minOutputTokens)`). |
| C | NO SPLIT IS SPECIFIED anywhere in the docs. MILESTONES M0 and EXECUTION_ORDER 1.2 say "re-derive packing under the cap" with no numbers; DIRECTOR_REPORT explicitly framed 8k as a "double loss" and recommended re-affirming 16k — the Director decided 8k anyway, and the accepted-cost note covers "less per-page context", not a split. | `MILESTONES.md:7-13`; `EXECUTION_ORDER.md:35-39`; `report/DIRECTOR_REPORT.md:88-96` ("Enforcing 8 k is a **double loss** … Recommendation: … re-affirm 16 k; enforce 8 k only if model stability demands it"). |
| D | Constraints a sane split must respect (all load-bearing today): prompt overhead 1,400; safety margin 512; min output 256; rolling+glossary budget 1,500 (DEFAULT) / 1,024 (LM_STUDIO); batch response overhead 32 + 24·pages + 8·blocks; output must cover up to 32 blocks of CJK→Latin expansion (the envelope planner reserves 8,192 output for exactly this). | `TranslationContextChunkPlanner.kt:22,23,27,31-34,39,201`; `translator/contextual/GlobalEnvelopePlanner.kt:45-51` (32 blocks / 8 pages / 16,384 in / 8,192 out, matching the plan's report). |
| E | OUT OF SCOPE of plan 1.2 and 3× OVER the binding cap: the ANALYSIS stage on the same local model plans 16,384 input tokens with 8,192 output (24,576 total). Its own design comment assumes a ≥32k provider context. Nothing in 1.2's text ("cap every profile … in TranslationContextChunkPlanner … re-derive GlobalEnvelopePlanner packing") covers it, and the analysis planner does not read the planner constant. | `translator/contextual/AnalysisChunkPlanner.kt:41-47` (`maxEstimatedInputTokens = 16_384`; "at least half the provider context stays reserved"); `pipeline/batch/ChapterProfileBatchCoordinator.kt:3130` and `translator/analysis/AnalysisEngineTransport.kt:72` (`ANALYSIS_MAX_OUTPUT_TOKENS = 8192`); scope text `EXECUTION_ORDER.md:35-39`, `MILESTONES.md:7-9`. |
| F | "Which wins at runtime" — two disjoint packing authorities. (1) SBC/manual/streaming: packing and output caps read `constraintsFor(profile)` → fixing the planner constant suffices. (2) PROFILE batch envelopes: sizes come from `EnvelopePlannerPolicy` DEFAULTS (16,384/8,192) — the coordinator overrides only maxBlocks/maxPages from the frozen run record, so the token budgets ALWAYS come from code defaults, and the executor re-splits envelopes at execution time against the SAME planner constraints (frozen-profile path) but ships as ONE batch with NO token check on the legacy (no-frozen-profile) shape. | `ChapterProfileBatchCoordinator.kt:2260-2263` (`EnvelopePlannerPolicy(maxBlocksPerEnvelope = frozenConfig.envelopePolicy.maxBlocks, maxContributingPages = …)` — token budgets defaulted); `pipeline/batch/ProfileEnvelopeExecutor.kt:434-439` (`splitForTokenFit`: identity split when `frozenProfile == null`), `:437,878` (`constraintsFor(providerProfile)`), `:903` (context trimmed against `maxRollingContextTokens` only), `:951-958` (`effectiveOutputCap`). |
| G | Oversized pages hard-pause the run, and the cliff moves ~3× closer under 8k. A single page whose source lines alone exceed `maxContextTokens − 512 − 256 − responseOverhead` (~5.9k tokens at 8,192 vs ~13.9k at 16,000) becomes `oversized` → typed PAUSE with `nextEligibleRetryAtEpochMs = null` (never auto-retries). The global planner likewise REJECTS whole pages over its budget (page atomicity), pausing the whole chapter at ENVELOPE_PLAN. So "less per-page context" is not the worst case: a page translatable yesterday becomes an untranslatable, run-pausing page tomorrow, while the MANUAL single-page path still translates it (it only drops context, never rejects). | `ProfileEnvelopeExecutor.kt:395-411` (oversized → `EnvelopeDispatchResult.Paused`, `nextEligibleRetryAtEpochMs = null`), `:460-470` (single-page oversized check); `GlobalEnvelopePlanner.kt:28-29` ("a single page that alone exceeds any budget is REJECTED whole"); `ChapterProfileBatchCoordinator.kt:1330-1347` (plan rejected → `BatchPass1Status.PAUSED`); manual-path tolerance: `TranslationContextChunkPlanner.kt:104-114` (drops context, never rejects the page). |
| H | Enforcement is PLANNER-ONLY; no request-build-time input guard. Providers transmit `chunk.maxOutputTokens` and the assembled prompt verbatim; LM Studio requests carry no context-length negotiation (`num_ctx`-style). On the legacy envelope shape (identity split, no token check) an over-window prompt still ships with a 256-token output floor → silent provider-side truncation → PARTIAL outcomes → pause loops. | `translator/providers/LmStudioTranslator.kt:64-75` (`max_tokens` only; no context options); `DeepSeekTranslator.kt:65`, `OpenRouterTranslator.kt:64`, `GeminiTranslator.kt:283` (same pattern); partial→pause semantics `SinglePageHttpRenderPhase.kt:422-441`. |
| I | Fingerprint/resume coupling of the envelope policy constants: `EnvelopePlannerPolicy.policyFingerprint()` includes the token budgets → `planInputFingerprint`; on resume the plan is re-derived and republished when the fingerprint differs (benign: committed pages are never re-planned). The run-record's `envelopePolicyFingerprint` hashes ONLY maxBlocks/maxPages and is explicitly excluded from translation validity — so NO durable invalidation, but in-flight chapters get one republication. | `GlobalEnvelopePlanner.kt:66-73`, `:268`, `:355-362`; `ChapterProfileBatchCoordinator.kt:1357-1360` (ST-11 reuse rule); `ChapterProfileBatchCoordinator.kt:2982-2986`; `artifact/ChapterRunRecord.kt:80-88` (`envelopePolicyFingerprint` "Excluded from translation validity"). |
| J | Tests pinning current numbers (complete list for `16_000|16_384|8192`-class greps in app/src/test): `translator/contextual/StreamingChunkPlannerTest.kt` (8,192 requested-output args throughout; LM_STUDIO packing expectations at :76-90 use tiny fixtures that still pass under 8,192); `translator/contextual/TranslationContextChunkPlannerTest.kt`; `translator/contextual/BatchEnvelopeLimitsTest.kt` (DEFAULT only); `translator/contextual/AnalysisChunkPlannerGoldenTest.kt` (policy passed explicitly — default-constant change is INVISIBLE to it); `translator/analysis/AnalysisChunkValidationTest.kt`; `translator/analysis/AnalysisRequestOrderTest.kt`; `translator/Checkpoint2IntegrationTest.kt`; `model/TranslationSettingsSummaryTest.kt` (user-facing "8192" string). NONE are in `coexistence/` (the ~12% safety net) — the only net file touching `estimatedInputTokens` is `D6ForegroundFairnessTest.kt:101`, a synthetic planner-input field, not a constant pin. | grep outputs, files listed; `app/src/test/java/eu/kanade/translation/coexistence/D6ForegroundFairnessTest.kt:101`. |
| K | The gate "planner tests assert the 8k cap" (EXECUTION_ORDER.md:41) is consistent with the no-safety-net-edit rule ONLY because no net test pins constants (evidence J) — but the plan never says explicitly that Phase 1.2 EDITS planner tests, and a green suite proves nothing: the existing LM_STUDIO tests (tiny fixtures) pass unchanged under 8,192, so nothing forces the new assertion unless the gate demands ADDING caps assertions. | `EXECUTION_ORDER.md:41-42`; `StreamingChunkPlannerTest.kt:76-90`. |

### Attacks that survive (ranked by regression risk to lane coexistence)

1. **The ANALYSIS stage keeps violating the binding cap after 1.2 "lands".**
   Evidence E: 16,384 in + 8,192 out to the same locked-down local model. The
   Director's decided directive ("every model locked down") is not implemented
   by the plan as written. Fixing translation while analysis still over-windows
   means the LM-stability problem motivating M0 persists.
2. **Unspecified split + hard-pause cliff on dense pages (batch lane).**
   Evidence D+G: `re-derive packing` with no numbers invites an implementer to
   lower `maxEstimatedInputTokens` toward ~8k or leave it at 16,384. Either
   way, the execution-time single-page budget shrinks to ~5.9k tokens and any
   single page over it PAUSES the whole chapter with no auto retry — a
   functional regression (previously-translatable pages become stallers), not
   the narrated "less context" cost. Requires an explicit overflow policy for
   single oversized pages (current page-atomicity forbids splitting; the
   manual path tolerates what batch rejects — an inter-lane inconsistency).
3. **Legacy-shape envelopes still ship over-window prompts.** Evidence F+H:
   when no frozen profile exists, `splitForTokenFit` is the identity split and
   no execution-time token check runs; only the output floor applies. Any 1.2
   that touches only constants leaves this hole; it needs either a token check
   on the legacy shape or an explicit statement that the shape is unreachable
   under the 8k regime (with evidence).
4. **Quality cliff via silent glossary/rolling drops (manual + auto).**
   Evidence D: halving the LM_STUDIO window while PROMPT_OVERHEAD (1,400) and
   rolling budgets (1,024/1,500) stay fixed roughly doubles the frequency at
   which `withRollingContext` drops the glossary first, then all context
   (`TranslationContextChunkPlanner.kt:104-114`) — cross-page term consistency
   (the glossary's entire purpose) degrades precisely on the dense chapters
   the roadmap targets. M0's accepted-cost sentence covers "less per-page
   context"; it does not cover more-frequent total glossary loss.
5. **Test-gate ambiguity.** Evidence J+K: no safety-net conflict (verified),
   but the gate as written cannot fail on a partial implementation; it must
   require NEW assertions (input+output+overhead ≤ 8,192 for BOTH profiles,
   plus an analysis-stage equivalent) rather than relying on existing tests
   to break.

### Concrete spec fix (make 1.2 implementable without regression)

- Widen scope: `TranslationContextChunkPlanner.constraintsFor` (LM_STUDIO
  16,000 → ≤ 8,192 total), `EnvelopePlannerPolicy` token budgets
  (16,384/8,192 → a split summing with overhead to ≤ 8,192),
  `AnalysisChunkPlanner.maxEstimatedInputTokens` + `ANALYSIS_MAX_OUTPUT_TOKENS`
  (own split, same ceiling).
- Specify the split numerically and derive it from the pinned overheads:
  source+context+overhead(1,400)+safety(512)+response-overhead+output ≤ 8,192,
  with output sized for the (reduced) per-envelope block count — and state the
  companion decision: either reduce `maxBlocksPerEnvelope`/`maxContributingPages`
  or accept that the token budget binds first.
- Define the oversized-single-page policy explicitly (batch PAUSE cliff at
  ProfileEnvelopeExecutor.kt:395-411 / GlobalEnvelopePlanner.kt:28-29 vs the
  tolerant manual path), and decide whether block-level page splitting is
  permitted under 8k or the pause is accepted and surfaced.
- Gate: require NEW tests asserting total ≤ 8,192 for both profiles across
  planner, envelope policy, and analysis policy; state plainly that existing
  planner tests may be edited (none are in the safety net — verified) and
  that the net is untouched.
- Note the benign fingerprint consequence (one envelope-plan republication on
  resume of in-flight chapters; no durable invalidation — evidence I).

---

## Cross-cutting verdict on "independent of T930"

- 1.1: only the compute half is independent; the publication-batching half is
  explicitly T930 Slice B (EXECUTION_ORDER.md:33-34), and the accumulator's
  durability/mutex story wants the same commit discipline. Label the item
  "compute-half independent".
- 1.2: genuinely independent of T930, but NOT independent of the analysis
  stage and envelope policy constants — the plan's own scope line is the defect.

## Summary verdicts

| Item | Verdict | One-line reason |
|------|---------|-----------------|
| 1.1 glossary accumulator | NEEDS-ADJUSTMENT | Real optimization on the wrong lane story; unsafe as add-only under retries/resume/concurrency; gate untestable as written. |
| 1.2 8k cap | NEEDS-ADJUSTMENT | Planner fix is trivial and DEFAULT is already compliant, but the plan misses the analysis stage, the legacy envelope shape hole, the dense-page pause cliff, and specifies no split. |

Neither item is BLOCKED: both are salvageable with the spec fixes above, and
neither, correctly specified, touches a safety-net test.
