# T924 Stage 6 Slice B — Profile-Aware Prompt Enrichment + Gap-Free Rolling Context + Gates 5.1-5.8 (in-repo portion)

Implementer report (wave 7a). Worktree `TachiyomiAT-t924-impl`, branch `t924/batch-profile-pipeline`, base HEAD `f2ccb95`. No commits made (orchestrator commits). Targeted 4-package suite after the change: **613 tests, 0 failures, 0 errors, 0 skipped** (baseline 598/0; +15 new tests). Python regex tally over `app/build/test-results/testStandardDebugUnitTest/*.xml`.

All main-source paths below are relative to `app/src/main/java/eu/kanade/translation/` (tests: `app/src/test/java/eu/kanade/translation/`) unless a Plan path is given.

## 1. Contract/design anchors (file:line)

Under `Plan/active/2026-09-05_T924_chunk-sizing-and-fast-feedback/`:

- `design/chapter-profile-batch-design.md`
  - **§7 :354** — translation request contract: (1) relevant frozen-profile subset (scan current source text for canonical forms/aliases/titles/terms; add scene participants + directly related facts; include entity IDs; CAP the subset); (2) range-safe scene context (current scene/range + chapter-wide canonical facts; lexical guidance, never a global replacement rule); (3) gap-free rolling history (recent source text, accepted target text, resolved entity IDs, compact unresolved reference state; prior target-language pronouns marked as translations, NEVER canonical gender evidence); (4) identity-vs-gender prompt rules (resolve referent first; profile gender only when established; else strong current/rolling source evidence; if unresolved prefer name/title, restructuring, natural singular "they").
  - **§8 tail :390** — execution-time recompute: "At execution, attach actual rolling context and recompute token fit. If it no longer fits, split at a whole-page boundary before sending. A single token-oversized page remains rejected because page atomicity is invariant."
  - **§9 :400** — malformed/partial response strategy (unchanged by this slice; DR-A Option 1 remains the accepted taxonomy).
- `stage0/feature-flags-stage-gates.md` — gates table :293-300 (5.1 page atomicity, 5.2 mixed-response, 5.3 stale commits, 5.4 envelope-policy reuse, 5.5 manual skip, 5.6 gap-free frontier, 5.7 provider measurements RECORD, 5.8 one-envelope-in-flight); invariants :407-409; :432 gate 5.2 default note.
- `implementation-sequence.md` §S6 :215 — slice B obligations (profile-subset + scene-context + gap-free history in `TranslationPrompts.kt`, identity/gender evidence rules per §7). FP-06 durable home stays OWED (NOT this slice); F-W6-3 store strict-mode is Stage 7.

## 2. Design per deliverable

### D1 — Profile-subset matcher (new `translator/contextual/ProfileSubsetMatcher.kt`, pure)

`internal`-free public object; **pure function of (frozen `ChapterTranslationProfile`, envelope source texts)** — no I/O, no clocks, no store access. `match(...)` (:89):

- Scans the envelope's CURRENT source text (the revalidated dispatch-block texts of the contributing pages, joined per page) for every fact's canonical source form + aliases/titles. CJK forms match exactly; other scripts match case-insensitively.
- Adds **scene participants**: every entity whose `factId` participates in a scene overlapping the envelope's natural page range rides in even without a text hit.
- Adds **directly related facts**: GENDER/PRONOUN/RELATIONSHIP/NARRATIVE_STATE facts linked to an included entity by the SAME canonical source form (case-folded for non-CJK), plus any usable non-entity/term fact that matched the text directly. Weak/retained ambiguity rides as notes, never averaged.
- **Entity IDs**: every entry carries `factId` (the reconciler's canonical `f-N` ids) — rendered `[f-1] カイル -> Kail (aliases: …)` so the model can link aliases to one character.
- **Caps** (bounded constants, deterministic order = the frozen profile's own fact order): `MAX_SUBSET_FACTS = 24` (:35), `MAX_SCENE_CONTEXTS = 4` (:38); `truncated` flag set when a cap fired.
- GENDER facts surface their `ProfileGender`; CONFLICTING gender renders an explicit "do not guess" note.

### D2 — Range-safe scene context (same file, `usableAt` :263)

- `CANONICAL_CHAPTER_WIDE` facts: always usable.
- `RANGE_SCOPED` facts: only when `applicableRange` overlaps the envelope's natural page span.
- `AVAILABLE_FROM` facts: only when `availableFrom.naturalPageIndex <= envelopeFirstPage` (the earliest point the prompt is used).
- Scenes: ONLY scenes overlapping the envelope range contribute `narrativeContext`/register/tone/participants; out-of-range scenes contribute NOTHING (their participants are NOT pulled in). The prompt states the fence verbatim: "guides tone and word choice for its page range ONLY — it is never a global replacement rule."

### D3 — Gap-free rolling history (executor + `TranslationPrompts.profileAwareRollingPrefix` :212)

- The slice-A `BatchContextFrontier` is UNCHANGED — it remains the sole rolling-context source (`record()` only on Accepted commits; contiguous terminal prefix only), so gates 5.6/2.4 discipline is inherited, not reimplemented.
- The frontier's `rollingContext` pair lines (`source => target`) are carried into the enriched rolling slot TOGETHER WITH:
  - **resolved entity IDs**: `ProfileSubsetMatcher.resolvedEntityLines` (:177) scans the committed rolling text for entity forms → `[f-1] カイル -> Kail` lines (capped 8);
  - **compact unresolved reference state**: `unresolvedReferenceLines` (:204) renders the frozen profile's `unresolvedFacts` notes (capped 4) as "background only — do not guess";
  - **the pronoun-marking rule, stated verbatim in every enriched rolling block**: "On each `source => target` line the RIGHT side is a PRIOR TRANSLATION: pronouns in it are translated renderings, NOT canonical gender evidence." — prior target-language pronouns are marked as translations and never presented as gender evidence.

### D4 — Enriched prompt assembly (`TranslationPrompts.kt`, strictly ADDITIVE)

- `profileIdentityGenderRules()` (:131) — the §7.4 decision rules in pinned order: resolve referent first → profile gender ONLY when established → else strong current/rolling source evidence → else name/title, restructuring, or natural singular "they". (Order pinned textually by `TranslationPromptsProfileTest`.)
- `profileAwareGlossaryPrefix(subset, includeScenes)` (:144) — decision rules + entity/term sheet with ids + range-safe scene contexts, for the glossary wire slot.
- `profileAwareRollingPrefix(pairs, resolved, unresolved)` (:212) — the pronoun-marking header + resolved ids + unresolved state + recent pairs, for the rolling wire slot.
- **Zero provider/builder changes**: both providers already render `contextPrefix(chunk.rollingContext, chunk.glossary)` before the request body (`GeminiTranslator.kt:98-101`, `OpenAiCompatibleTranslator.kt:169-172`), so the enriched text rides the EXISTING `TranslationContextChunk` fields — no new carry field, no read-only file touched. The legacy `contextPrefix` headers still frame the two slots ("Established terms…" / "Previous context / recent translated pairs…"); the enriched sections are self-describing beneath them (documented deviation, §5).
- **Usage gate**: the enriched builders are called ONLY from `ProfileEnvelopeExecutor` when its `frozenProfile` is non-null; every pre-existing `TranslationPrompts` function is byte-identical (diff = 1 import + 128 added lines; legacy suites green).

### D5 — Execution-time token recompute + whole-page split (`ProfileEnvelopeExecutor`)

- `splitForTokenFit(held, rollingContext)` (:434) runs AFTER TX-21 revalidation, BEFORE any dispatch, ONLY on the enriched path (`frozenProfile == null` → identity split, slice-A behavior):
  - per-page ACTUAL source-line estimate via the production estimator (`TranslationContextChunkPlanner.estimateTokens` over the exact wire `id|text` lines, newline-flattened like `idMappedSourceLine`);
  - context reserve = the FULL-range enriched context (subset + scenes + rolling) estimated once — an upper bound for any sub-batch (a smaller page range matches fewer scenes/facts), so every sub-batch fits a fortiori;
  - fit = planner idiom: `maxContextTokens − safetyMargin − minOutputTokens − batchResponseOverheadTokens(blocks, pages)` (`promptAvailableTokens` :482);
  - greedy prefix packing in plan order — pages are regrouped only at WHOLE-PAGE boundaries (never a block split).
- Sub-batches dispatch **sequentially** through `dispatchSingleHeldBatch` (:532) — each provider request fully returns before the next, so the one-envelope-in-flight invariant (gate 5.8) is structural, unchanged.
- **Single token-oversized page** (`SplitPlan.oversized`): REJECTED, never sent — after any fitted pages commit (slice-A commit-then-pause idiom), a typed PROTOCOL/PAUSE fires with reason "… token-oversized under the execution-time enriched-context recompute; page atomicity kept — the page(s) were NOT translated" (:396-411). No unbounded re-splitting: fitted batches are produced once per envelope by one deterministic pass; the split loop dispatches each batch exactly once.
- `buildEnvelopeChunk` (:826) enriched branch recomputes `estimatedPromptTokens` over the ACTUAL payload (lines + context) and caps output via the shared `StreamingChunkPlanner.effectiveOutputCap`; the metadata `estimatedInputTokens` is the honest recomputed value. A deterministic bounded trim (scenes → unresolved → resolved lines → halve recent pairs → drop pairs → halve subset tail) keeps the context within `maxRollingContextTokens`; trimming affects the CURRENT prompt only — the frontier keeps the full history.

### D6 — Observability for the Director's device A/B (no device work)

- Executor `Counters` (:125-156) gains: `envelopeSplits`, `promptShapeEnriched`, `promptShapeLegacy`, `profileSubsetFactsMax`, `rollingContextPagesMax` — all surfaced through the existing `counters.toMap()` → run-record `phaseCounters` path (additive keys; readers use map lookup).
- Per-envelope INFO log (executor :930): `t924 envelope prompt shape=enriched facts=N scenes=M rollingPairs=K rollingPages=P contextTokens=T envelopeId=…` — plus coordinator logs at profile load (`ChapterProfileBatchCoordinator.kt:1143-1172`): `shape=enriched (frozen profile vN loaded)` or `shape=legacy (frozen profile sidecar unreadable — degraded-but-correct)`. A same-chapter flag-off vs flag-on comparison is readable from records + logcat alone.

### D7 — Tests (15 new, all green)

`translator/contextual/ProfileSubsetMatcherTest.kt` (6):
- canonical form + alias + title matching, entity-id + alias carry (Latin case-insensitive path pinned);
- determinism (two runs equal) + cap at `MAX_SUBSET_FACTS` + frozen-profile-order truncation;
- scene range safety: overlapping scene only; out-of-range scene's participants/narrative excluded; RANGE_SCOPED in/out; AVAILABLE_FROM after envelope start excluded; chapter-wide GENDER included;
- AVAILABLE_FROM at the envelope first page usable;
- scene participant added without a text hit;
- resolvedEntityLines/unresolvedReferenceLines rendering + caps + blank input.

`translator/contextual/TranslationPromptsProfileTest.kt` (4):
- identity rule precedes profile-gender rule precedes source-evidence rule precedes singular-they fallback (index ordering);
- glossary prefix carries `[f-id]`, gender value, scene block, and the never-global-replacement fence; scene toggle drops scenes, keeps the sheet;
- rolling prefix carries "NOT canonical gender evidence" verbatim + resolved ids + unresolved state + pairs;
- rolling prefix empty when nothing to carry.

`pipeline/batch/ProfileEnvelopePromptEnrichmentTest.kt` (5; coordinator-level through the REAL planner/freeze/publications/retry controller, plus one direct-executor case):
- enriched prompt reaches the provider: `[f-1]`, matched source form, canonical target, "Resolve the referent first", "never a global replacement rule" in `chunk.glossary`; empty frontier → empty rolling slot; counters `promptShapeEnriched=1`, `promptShapeLegacy=0`, `profileSubsetFactsMax=2`, `envelopeSplits=0`; pipeline outcome unchanged (PAUSED `TRANSLATE_STOP_REASON`, 3 pages READY);
- enriched rolling history advances GAP-FREE across planned envelopes: request 2 carries the committed page-1 pair + the pronoun-marking line; `rollingContextPagesMax=1`;
- execution-time recompute SPLITS an oversized envelope at WHOLE-PAGE boundaries: 3 pages × ~4.2k ACTUAL tokens (kana text sized by the production estimator) → every provider request exactly one page, one-in-flight, `envelopeSplits ≥ 1`, all 3 READY, `promptShapeEnriched=3`, 0 failures;
- single token-oversized page (~9k ACTUAL tokens) → typed pause containing "token-oversized", ZERO provider calls, page stays PENDING, `envelopeFailures=1`, prompt-shape counters 0;
- legacy shape WITHOUT a frozen profile (direct executor, real profile+plan pointers published so TX-20 accepts): `chunk.glossary == ""`, `chunk.rollingContext == ""`, `estimatedPromptTokens == plan estimate`, `promptShapeLegacy=1`, page commits READY.

Gate mapping (in-repo portion): 5.1 page atomicity — split only at page boundaries + oversized-page rejection pin (slice-A fault-matrix unchanged); 5.2/5.3/5.5 — untouched slice-A paths, all suites green; 5.4 — split is execution-time only, commits still carry the plan-level `envelopePlanFingerprint` (no new plan identity); 5.6 — frontier untouched + enriched rolling carry pinned; 5.7 — RECORD counters now per-envelope (`promptShape`, facts, rolling pages, splits) for the flag-off/on A/B; 5.8 — sequential sub-batch dispatch, `maxObservedInFlight=1` pinned on the split path.

## 3. Diff summary

```
translator/contextual/ProfileSubsetMatcher.kt      NEW  (~300) pure matcher: match/usableAt/resolved/unresolved, caps
translator/contextual/TranslationPrompts.kt        +128 (1 import + 4 additive functions; existing bodies byte-identical)
pipeline/batch/ProfileEnvelopeExecutor.kt          +429/−65 (frozenProfile param, splitForTokenFit + dispatchSingleHeldBatch,
                                                    enriched buildEnvelopeChunk/PreparedChunk, counters, logging)
pipeline/batch/ChapterProfileBatchCoordinator.kt   +29  (load frozen profile via readReusableFrozenProfile; pass to executor; shape logs)
tests: ProfileSubsetMatcherTest.kt NEW (6), TranslationPromptsProfileTest.kt NEW (4),
       ProfileEnvelopePromptEnrichmentTest.kt NEW (5)
```

No read-only file modified: artifact DTOs, `StageFingerprints`, `GlobalEnvelopePlanner` + goldens, `AiTranslationRetryController`/retry, `ContextualRequestBuilder`/`ContextualResponseParser`, `TranslationContextChunk` (no new field needed), `BatchContextFrontier`, rendering/*, `SequentialBatchCoordinator`, domain/ui, `ChapterTranslationStore`/`TranslationStageContracts` — zero diff. Legacy Batch/Manual/Auto paths: zero diff.

## 4. Deviations

1. **Enriched text rides the legacy `contextPrefix` framing headers** (read-only `ContextualRequestBuilder.renderPrompt` + `TranslationPrompts.contextPrefix`): the glossary slot header still reads "Established terms (reuse these exact English renderings…)" and the rolling slot "Previous context / recent translated pairs…", with the enriched sheet/rules/history self-describing beneath. Avoided a new `TranslationContextChunk` carry field + provider changes; flagged as agreed-loud alternative (brief: "prefer adding an optional carry field ADDITIVELY" only if the existing shape could not carry it — it could).
2. **GENDER/PRONOUN facts link to entities by canonical source form equality** — the `ProfileFact` DTO has no subject-reference field, so "directly related facts" is realized as same-form linkage (+ direct text hits). Deterministic and pure; a future schema field would make it exact. `profileSubsetRefs` on `PlannedEnvelope` remains planner-side (empty) — the executor computes the real subset at execution time against revalidated text.
3. **Token-fit estimates use the production jtokkit estimator over wire lines** while plan admission uses the planner's char-based estimates — intentional: the recompute exists precisely because the two disagree. The split decision uses the FULL-envelope context as an upper bound so every sub-batch fits a fortiori.
4. **Slice-A exactness preserved on the legacy path**: with `frozenProfile == null` the executor performs the identity split and the identical `withRollingContext` call; the only behavioral deltas are additive counters/log lines and a `#batchIndex` suffix in the retry-driver log label.
5. **Plan-level `envelopeSplits` counter counts dispatched sub-batches beyond the first per envelope** (`fitted.size − 1`); oversized-page rejections are counted under `envelopeFailures` + typed pause reason instead. The exact split count can therefore vary with how the plan grouped pages (test pins `≥ 1` + all requests single-page, not an exact number).

## 5. Risks

- **Provider-window sizing is calibration-dependent**: `MAX_CONTEXT_TOKENS = 8192` remains the DEFAULT profile's provider-independent assumption (design §8 "ASSUMPTIONS requiring provider tests"). The recompute makes oversized envelopes degrade GRACEFULLY (trim → split → reject single page) instead of silently flooring the output cap as slice A did — strictly better, but real-provider evidence (gate 5.7/4.7) is still owed.
- **Trim-to-budget can drop the rolling history entirely** on a giant-pair envelope (bounded degradation, deterministic; the frontier keeps full history). The Director's A/B counters show this as low `profileSubsetFactsMax`/empty rolling on affected envelopes.
- **Prompt-shape change may shift provider behavior** (better identity/gender handling; unknown token-latency cost) — exactly what the flag-off/on A/B is for; FF-01 stays the kill switch.
- **Kana/word tokenization variance in tests**: the two oversized-path tests size their fixtures by MEASURING with the production estimator (sampled rate + convergence), not by hard-coded lengths, so cl100k drift degrades gracefully rather than flipping the fixtures.

## 6. Cuts

- None from the required core (D1-D4 landed). D5 landed in full (recompute + page-boundary split + single-page rejection — the cut-order fallback "typed pause on misfit" was not needed). D6 landed the full counter set + per-envelope logs (extra counters were the first cut; they fit).
- NOT done (unchanged obligations): FP-06 durable home (owed, not this slice); F-W6-3 store strict-mode (Stage 7); device/provider A/B measurement (Director, gate 5.7 evidence); gate 5.7's flag-off baseline timing capture itself (needs device work).
- Wave-6 F-W6-1 (lease release on drift/lost-page early returns) was already fixed in `f2ccb95`; untouched here. F-W6-2 (ST-11 reuse branch pin) and F-W6-4 (`lmstudio` spelling) remain owed to their named owners.
