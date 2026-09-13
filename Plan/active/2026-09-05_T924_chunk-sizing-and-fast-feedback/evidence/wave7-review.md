# T924 Wave 7a — Independent Review (S6 slice B: profile-aware prompt enrichment + gap-free rolling context + execution-time token recompute)

Reviewer: independent Reviewer (read-only; authored none of the reviewed diff)
Date: 2026-09-06
Worktree: `TachiyomiAT-t924-impl`, branch `t924/batch-profile-pipeline` (clean at review time)
Commit under review: `65e4a25` "feat(translation): T924-S6 slice B profile-aware prompt enrichment + gap-free rolling context (design §7)" (parent `f2ccb95`); 7 files, +1835/−65.
Controlling contracts: `design/chapter-profile-batch-design.md` §7 :354 (translation request contract), §8 tail :402-404 (execution-time recompute + whole-page split; single oversized page rejected), §9 :400; `stage0/feature-flags-stage-gates.md` gates 5.1-5.8 (:293-300) + invariants :407-409; prior: `evidence/wave6-review.md` (slice-A verdicts the enrichment extends), `stage0/traceability-ledger.md` Wave 6, `implementation-sequence.md` §S6 :215.
Main-source paths below relative to `app/src/main/java/eu/kanade/translation/`; tests to `app/src/test/java/eu/kanade/translation/`.

---

## Verdict

## ACCEPT

No CRITICAL or HIGH findings. Four LOW findings / notes (F-W7-1..4), none blocking; two are bounded-degradation notes already directionally disclosed in the implementer report's risk section. The commit's headline risk claims all verified in code: `TranslationPrompts.kt` is strictly additive (numstat **128 added / 0 deleted**; the only non-additive hunks in the commit live in the executor's own relocated dispatch block), the file set is exactly the claimed 7 (modified: coordinator, executor, TranslationPrompts; new: matcher + 3 test files), and the matcher is genuinely pure and deterministic with correct range fences.

Independent verification run: **BUILD SUCCESSFUL** (1m 55s); JUnit XML tally parsed over whole `<testsuite>` tags: **tests=613 failures=0 errors=0 skipped=0** — matches the implementer's 613/0 exactly (baseline 598 + 15 new). The timing-sensitive `D2ManualBatchInterleavingTest` did NOT flake in my run (green on the first full filtered run; `ProfileEnvelopePromptEnrichmentTest` also re-verified green isolated, 27s).

---

## 1. Findings

### F-W7-1 — LOW (a-fortiori context reserve is not a strict upper bound: AVAILABLE_FROM first-page shift + cap-tail asymmetry)

`splitForTokenFit` estimates the enriched context ONCE over the FULL envelope range (`estimateEnrichedContextTokens`, `ProfileEnvelopeExecutor.kt:496-510`) and packs sub-batches against that reserve. The report's a-fortiori argument ("a smaller page range matches fewer scenes/facts") holds for text-match, RANGE_SCOPED overlap, scene participants, and form-linkage — all monotone in the corpus. Two bounded exceptions:

1. **AVAILABLE_FROM is evaluated at the envelope's FIRST page** (`ProfileSubsetMatcher.kt:268`, `availableFrom <= firstPage`). A sub-batch whose first page is LATER can make additional facts usable that the full-envelope estimate excluded (fact available from page 5, envelope pages 3-9 → not counted; sub-batch pages 5-9 → counted and rendered).
2. **The 24-entry cap is applied in profile fact order per match call.** A sub-range subset may contain entries the full-range subset capped away (full range hits the cap with early-profile facts; the sub-range selects different ones), so rendered-entry length is not strictly dominated either (both sides are capped at 24 entries / 4 scenes, so the delta is bounded by per-entry length differences).

Consequence: an actual sub-batch prompt can exceed the packing budget by a bounded amount (a few fact lines). It is NOT a silent corruption path: the honest recomputed `estimatedPromptTokens` rides the metadata, the trim ladder still bounds context to `maxRollingContextTokens` (1500), and a genuinely oversized request fails through the existing typed provider-failure/retry/pause machinery. Severity LOW because exploiting it requires AVAILABLE_FROM facts staged strictly inside a splittable envelope plus near-zero packing headroom.

Fix direction: in `splitForTokenFit`, estimate the context per CANDIDATE sub-batch at flush time (the matcher is pure and cheap; dispatch is sequential so there is no added concurrency), or evaluate `usableAt` at each candidate sub-batch's first page. Either closes both exceptions.

### F-W7-2 — LOW (lenient null defaults in the range fence: malformed profile facts are treated as usable)

`ProfileSubsetMatcher.usableAt` (`ProfileSubsetMatcher.kt:263-269`):
- `RANGE_SCOPED` with `applicableRange == null` → usable (`?: true`);
- `AVAILABLE_FROM` with `availableFrom == null` → usable (`?: Int.MIN_VALUE`).

Both default in the PERMISSIVE direction: a sidecar fact whose range metadata is absent/ corrupt rides the prompt ahead of its range, against the §7.2 fence ("include only … facts available at that point"). The frozen profile is produced by the reconciler under ST-05/ST-30 read-back validation, so today this is a schema-hygiene exposure, not a live leak. Severity LOW.

Fix direction: default-DENY for scoped facts with missing scope payloads, or validate scope-payload presence at profile freeze/publication so an invalid fact can never reach the executor.

### F-W7-3 — LOW (prompt-shape counters count BUILT chunks and PLANNED splits, not sent requests)

- `promptShapeEnriched++` fires inside `buildEnvelopeChunk` (`ProfileEnvelopeExecutor.kt:944` region), which runs BEFORE `sublimitGate.executeBatch`; a QUOTA_EXHAUSTED defer or a transport exception still burns the counter for a request never placed. Same for `profileSubsetFactsMax`/log line.
- `envelopeSplits += fitted.size − 1` (`:381`) counts planned sub-batches even when a prior sub-batch PAUSES and later ones are never dispatched (the `return result` at `:388`).

For the Director's flag-off/on A/B this is directionally sound (per-envelope shape is still unambiguous), and report deviation 5 already declares the split-count semantics; recorded here so gate 5.7 device measurements are read with the "built vs sent" caveat. Fix direction: move the increments after outcome classification, or rename/document (`promptShapeEnrichedBuilt`, `envelopeSplitsPlanned`).

### F-W7-4 — NOTE (dense single-block pages can never contribute a rolling pair; empirically pinned by the split test)

The split test's three ~4.2k-token pages each commit as a pair line whose SOURCE side alone exceeds `MAX_ROLLING_CONTEXT_TOKENS = 1500`; the deterministic trim drops the pairs entirely, which is why `request.rollingContext shouldBe ""` holds for every sub-batch request (test `execution-time recompute splits an oversized envelope at whole-page boundaries`, `ProfileEnvelopePromptEnrichmentTest.kt:413`). This is the report's disclosed "trim can drop the rolling history entirely" risk, here pinned as real behavior: for chapters whose pages are one giant block, gap-free rolling history is structurally empty even when the frontier advances. Bounded, deterministic, frontier keeps full history, kill switch unaffected — no action required this slice. If the A/B shows identity drift on dense-page chapters, a per-line pair truncation (source excerpt + target) is the candidate remedy.

### Non-findings (adversarial checks run and PASSED)

- **A. Legacy safety (additive-only + no legacy surface).** `git show --numstat`: exactly 7 files; `TranslationPrompts.kt` **128/0** — every existing line byte-identical, diff = 1 import + appended section before the closing brace; coordinator **29/0**; matcher + 3 tests all new; the −65 deletions are confined to the executor's own relocated dispatch block (try/catch boundary moved into `dispatchSingleHeldBatch`, DR-A classification/commit lines carried as unchanged context). No diff in providers, `ContextualRequestBuilder`, `BatchContextFrontier`, retry, planner, DTOs, fingerprints, domain/ui, flags. Legacy Batch/Manual/Auto prompt shape pinned by the pre-existing `TranslationPromptsTest` (contextPrefix/system/pro-drop cases) — green in the 613/0 run.
- **B. Matcher purity and semantics.** No I/O, no clock, no store access; output order = profile fact/scene order with fixed group priority (entities → terms → related), caps `MAX_SUBSET_FACTS = 24` / `MAX_SCENE_CONTEXTS = 4` break deterministically with `truncated` flag (`ProfileSubsetMatcher.kt:146-166`); CJK exact / Latin case-insensitive via UnicodeBlock classification + corpus lowercasing (`containsForm`, `:279-287`); entity `factId` carried into every entry and rendered `[f-N] source -> target` with aliases (`TranslationPrompts.renderSubsetEntry`). Range fences verified: `participantIds` built ONLY from `overlappingScenes` (`:103-107`), so out-of-range scenes contribute nothing — including their participants (pinned by `scene context is range-safe…`, `ProfileSubsetMatcherTest.kt:198-202`); `overlaps` is the correct closed-interval test; AVAILABLE_FROM at the envelope first page usable (test `:208-215`); scene participant without a text hit added (`:219-230`).
- **C. Rolling history.** `BatchContextFrontier` untouched (absent from diff); `record()` only on Accepted commits (`ProfileEnvelopeExecutor.kt:775`), contiguous-prefix advance (`BatchContextFrontier.kt:72-85`), PARTIAL never advances (`isContextReady` gate) — slice-A discipline inherited, not reimplemented. The pronoun-marking rule is the UNCONDITIONAL first line of every non-empty enriched rolling block (`profileAwareRollingPrefix`, `TranslationPrompts.kt` — header appended before any section; when the builder returns "" there is no rolling content at all, so no unmarked pair can exist). Canonical-gender leak check: gender evidence enters the prompt ONLY via profile facts (`renderSubsetEntry`) and `resolvedEntityLines` (profile canonical forms); rolling target text is rendered solely under "Recent pairs" beneath the NOT-canonical-evidence header — a translated pronoun cannot surface as gender evidence.
- **D. Prompt rules.** The §7.4 order is enforced STRUCTURALLY (list order in `profileIdentityGenderRules`) and pinned by index assertions in `TranslationPromptsProfileTest.kt:33-44`; CONFLICTING gender renders "(CONFLICTING — do not guess; prefer name/title or singular \"they\")" (`renderSubsetEntry`); the scene fence "never a global replacement rule" rides the glossary prefix and is pinned.
- **E. Execution-time recompute.** `splitForTokenFit(held, frontier.rollingContext)` runs AFTER the TX-21 revalidation loop and before any dispatch (`dispatchEnvelope` :338-367 revalidation → :380 split), enriched path only (`frozenProfile == null` → identity split, `:448`). Whole-page boundaries only: greedy prefix packing over `HeldPage`s, never a block split (pinned: every provider request `pages.size == 1`). Sequential sub-batches: `forEachIndexed` suspend loop, each `dispatchSingleHeldBatch` fully returns (commit included) before the next; zero `async`/`launch`/`Deferred` in the class — one-in-flight structural. Single oversized page: never enters dispatch; typed PROTOCOL/PAUSE after fitted pages commit (`:396-411`); zero provider calls pinned (`translator.requests.size shouldBe 0`, page stays PENDING). Trim ladder (scenes → unresolved → resolved → halve pairs keeping LAST half → drop pairs → halve subset → drop subset) is bounded and terminating; it mutates only local builder state — the frontier object is never trimmed. The estimate mirrors the exact wire line (`estimateWireLineTokens` flattens \r\n|\r|\n exactly like `idMappedSourceLine`).
- **F. Degradation.** `frozenProfile == null` → identity split + the byte-identical slice-A `withRollingContext(glossary = "")` call, `estimatedInputTokens = plan estimate`, `promptShapeLegacy` pinned by the coordinator-anchored legacy test (real profile+plan pointers published, then executor constructed with `frozenProfile = null`). Unreadable sidecar → `readReusableFrozenProfile` NotReusable → `null` + WARN log in the coordinator (`ChapterProfileBatchCoordinator.kt:1159-1170`) — legacy shape, no crash, no partial enrichment (same ST-05/ST-30 reuse discipline as the freeze).
- **G. Observability.** `Counters` gains 5 additive keys via `toMap()` → run-record `phaseCounters` (`Map<String, Int>`; 12 keys < the 32-key schema bound, `ChapterRunRecord.kt:147`; sole writer is the coordinator `:2156`, readers are map-based). Per-envelope INFO line with shape/facts/scenes/pairs/pages/contextTokens/envelopeId (`:949-955` region) + coordinator load logs (enriched vN / legacy degraded). A same-chapter flag-off/on A/B is readable from records + logcat alone (with the F-W7-3 built-vs-sent caveat).
- **H. Tests.** The five `ProfileEnvelopePromptEnrichmentTest` cases run through the REAL coordinator (`runPass1` → real OCR patch ladder, freeze, SC-10 publication, real `translateAiChunkWithAdaptiveRetry` around the translator seam; the legacy-shape case is direct-executor but publishes REAL frozen-profile + envelope-plan pointers so TX-20 genuinely accepts the commit). The split fixture is sized by MEASURING the production jtokkit estimator (`textOfApproxTokens`, convergence loop) against the REAL window derived from planner constraints — not a rigged threshold; the oversized fixture likewise (~9k actual vs ~7.4k available). Oversized zero-provider-call pinned; no assertion-free tests anywhere in the 15 (every case asserts prompt text, store state, counters, and/or call transcripts).
- **Commit scope.** `git status` clean; HEAD = `65e4a25`, parent `f2ccb95`; exactly the 7 claimed files.

---

## 2. Dimension verdicts

- **A. Legacy safety: CONFORMANT.** 128/0 additive diff on `TranslationPrompts.kt`; exactly 7 files; no legacy surface touched; legacy prompt pin suite green.
- **B. Matcher: CONFORMANT** (pure, deterministic, capped, entity ids carried, fences correct, out-of-range scenes contribute nothing; F-W7-2 lenient-null note).
- **C. Rolling history: CONFORMANT** (frontier inherited; verbatim pronoun rule in every non-empty enriched rolling block; no canonical-gender leak path).
- **D. Prompt rules: CONFORMANT** (pinned order enforced in emitted text; CONFLICTING → do-not-guess).
- **E. Recompute/split: CONFORMANT** (after TX-21, before dispatch, enriched-only; whole-page; sequential; oversized typed pause with zero calls; trim bounded/deterministic; frontier keeps history; F-W7-1 reserve note).
- **F. Degradation: CONFORMANT** (null-profile legacy path pinned byte-level; unreadable sidecar degrades to legacy + WARN, never crashes).
- **G. Observability: CONFORMANT** (additive map counters within schema bound; per-envelope + per-load log lines; A/B feasible; F-W7-3 semantics caveat).
- **H. Tests: REAL** (coordinator-level flow through planner/freeze/publication/retry; real estimator pressure; zero-call pin; no assertion-free cases).
- **I. Deviations: all five RATIFIED** (below).

---

## 3. Deviation rulings (report §4)

| # | Deviation | Ruling | Basis |
|---|---|---|---|
| 1 | Enriched text rides the legacy `contextPrefix` framing headers | **RATIFIED** | Verified end-to-end in code: both providers render `chunk.rollingContext`/`chunk.glossary` through `ContextualRequestBuilder.renderPrompt`, which prepends `TranslationPrompts.contextPrefix` (`providers/GeminiTranslator.kt:98-101`, `ContextualRequestBuilder.kt:124-126`) — the existing wire shape genuinely carries the enrichment with zero provider/builder changes and zero new `TranslationContextChunk` fields. The sections are self-describing beneath the legacy headers, so the framing is not misleading. The agreed-loud alternative (optional carry field) remains available if a later A/B shows header-content mismatch confusing providers. |
| 2 | GENDER/PRONOUN linkage by same canonical source form | **RATIFIED** | `ProfileFact` has no subject-reference field; same-form linkage is deterministic, pure, and caps identically with the rest of the subset. Residual imprecision (two distinct entities sharing one surface form would both attract the fact) is bounded by the 24-entry cap and by the prompt's own "resolve the referent first" rule. A schema subject-ref field stays the correct Stage-7+ fix; `profileSubsetRefs` staying planner-side while the executor computes the real subset against revalidated text is the right trust boundary. |
| 3 | jtokkit estimator for recompute vs char-based plan admission | **RATIFIED** | The divergence between the two estimators is exactly what the execution-time recompute exists to absorb (design §8 "recompute token fit"); the split test proves the recompute fires precisely when the plan-time char estimate passes but actual tokens do not fit. F-W7-1 notes the one direction in which the reserve approximation is not strict. |
| 4 | Legacy-path exactness (identity split + identical `withRollingContext`; additive counters + `#batchIndex` label) | **RATIFIED** | Diff-verified: the null-profile branch reproduces the slice-A construction byte-for-byte; the only behavioral deltas are counters, log lines, and the retry-driver log label, none of which reach the provider payload or the store. Legacy test pins `glossary == ""`, `rollingContext == ""`, `estimatedPromptTokens == plan estimate`. |
| 5 | `envelopeSplits` counts `fitted.size − 1`; oversized rejections counted under `envelopeFailures` + typed pause | **RATIFIED** | The typed pause reason carries the oversized-page count and the atomicity statement verbatim; the test pins the meaningful invariants (≥1 split, every request single-page, zero failures) rather than a brittle exact count. F-W7-3 records the residual planned-vs-dispatched nuance. |

---

## 4. Gate mapping check (5.1-5.8, in-repo portion)

- 5.1 page atomicity: split only at whole-page boundaries + oversized-page rejection pin — CONFORMANT (page-atomic split; the store-level partial-apply note remains F-W6-3/Stage 7).
- 5.2/5.3/5.5: untouched slice-A paths, all suites green — CONFORMANT (no diff in the taxonomy/commit/CAS machinery).
- 5.4: split is execution-time only; commits still carry the plan-level `envelopePlanFingerprint` (`:766`) — CONFORMANT.
- 5.6: frontier untouched; enriched rolling carry pinned gap-free across envelopes (`rollingContextPagesMax = 1`) — CONFORMANT.
- 5.7: RECORD counters now per-envelope for the A/B; the device measurement itself remains the Director's — CONFORMANT in-repo portion.
- 5.8: sequential sub-batch dispatch, `maxObservedInFlight = 1` pinned on the split path — CONFORMANT.

## 5. Verification run (independent)

- `cd TachiyomiAT-t924-impl && JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:compileStandardDebugKotlin :app:testStandardDebugUnitTest --tests "eu.kanade.translation.translator.*" --tests "eu.kanade.translation.pipeline.batch.*" --tests "eu.kanade.translation.coexistence.*" --tests "eu.kanade.translation.artifact.*"` → **BUILD SUCCESSFUL** (1m 55s, exit 0).
- Robust JUnit XML tally (whole `<testsuite>` tag attributes): **tests=613 failures=0 errors=0 skipped=0** — matches the implementer's 613/0 (baseline 598 + 15 new).
- `D2ManualBatchInterleavingTest`: no flake observed (green first run). `ProfileEnvelopePromptEnrichmentTest` additionally re-run isolated → BUILD SUCCESSFUL (27s).
- `git status` clean at review time; HEAD = `65e4a25` (parent `f2ccb95`).

## 6. Owed-item status touched by this slice

- S6 slice B obligations (profile-subset + scene-context + gap-free history in `TranslationPrompts.kt`, identity/gender rules per §7): **LANDED** this commit, subject to nothing blocking.
- FP-06 durable home: OWED, unchanged (not this slice; wave-6 ruling stands).
- F-W6-3 store strict-mode: OWED to Stage 7; this slice's oversized/trim paths do not interact with it.
- F-W6-2 (ST-11 coordinator-branch pin), F-W6-4 (`lmstudio` spelling; the executor's DR-D bucket key now also feeds `#batchIndex`-labeled sub-batch requests — same single bucket, no new spelling site): remain owed to their named owners.
- Device/provider A/B (gate 5.7) + flag-off baseline timing: Director's verification; counters/log lines to read it are in place (F-W7-3 caveat).
