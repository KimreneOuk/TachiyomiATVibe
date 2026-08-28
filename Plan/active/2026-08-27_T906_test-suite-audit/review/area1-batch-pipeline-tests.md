# T906 Area 1 — Batch pipeline tests audit

Auditor: T906 Batch Pipeline Test Auditor (reviewer role).
Baseline: commit `56179d7` on `t904/integration` (worktree HEAD equals the
baseline snapshot at audit time; verified via `git rev-parse HEAD` →
`56179d7452e857585ab516f98a556ea83c277bb5`).
Scope: `app/src/test/java/eu/kanade/translation/batch/*Test.kt` (15 files),
`eu/kanade/translation/model/PageWorkPlannerTest.kt`, and
`TranslationRetryTest`, `AiTranslationRetryControllerTest`,
`AiTranslationRetryPlannerSinglePageTest`, `ProviderRequestGovernorTest`.

Method: each test read in full and compared line-by-line against the current
production source (`app/src/main/java/eu/kanade/translation/...`).

---

## SequentialBatchCoordinatorTest.kt

Production reference: `SequentialBatchCoordinator.kt` (whole file),
`BatchCoordinatorInterfaces.kt:63-115` (lane worker defaults),
`BatchCoordinatorInterfaces.kt:148-195` (typed outcomes).

Seed-evidence check: the region formerly at
`SequentialBatchCoordinatorTest.kt:408-437` that codified
`RuntimeException -> Completed` is now the test
`unexpected stage failure stops the pass without completing later pages`
(SequentialBatchCoordinatorTest.kt:409-442). It asserts
`BatchPass1Status.FAILED`, `unexpectedStage = OCR`,
`terminalPageKeys = {p0}`, empty `completedPageKeys`, and no barrier release —
matching production `SequentialBatchCoordinator.kt:189-197`
(`ChunkCompletionOutcome.Unexpected` for non-provider exceptions),
`SequentialBatchCoordinator.kt:600-615` (FAILED outcome), and
`BatchCoordinatorInterfaces.kt:176-182` (only the affected page terminal).
The round-2 pattern (terminal-provider-failure fixture expecting later pages
to render after an untyped exception) is likewise absent from the current
file: the `typed pause` and `unexpected` tests at lines 184-235 / 444-498 all
assert later pages stop/pending.

Per-test verification (all 17 tests):

| Test (line) | Verdict | Notes |
|---|---|---|
| `same visit hands one source decode` (27) | VALID | handoff passed via `runInpaintStage(pageKey, nativeHandoff)` (SequentialBatchCoordinator.kt:430), released exactly once in finally (:448) |
| `every page invokes ocr and inpaint exactly once` (65) | VALID | needsTranslation accumulated per non-null ref (:97); barrier release only on success (:575) |
| `adaptive probe is the only native lookahead` (101) | VALID | probe retained, not re-admitted (:466-474, comment confirms planner ownership); boundary before p3; admission count for probe = 1 (:488-516) |
| `adaptive AI completion overlaps inpaint only after full chunk OCR` (127) | VALID | inpaint runs inline on caller while translation lane is async (:249-331 vs :420-453) |
| `unexpected translation failure stops before admitting next OCR` (160) | VALID | untyped chunk failure → `Unexpected` → break (:536-538); p2 OCR never started |
| `typed pause settles unresolved chunk` (185) | VALID | fallback chunk = 7 pages (remote, :47); `pausedPages` (:199-223) settles p1..p6 via `awaitAndSettle`, renders only p0 |
| `typed pause propagates through local lane` (238) | VALID | local lane is page-serial, chunk size 1 (:47), inline translation (:337-417) |
| `translation commits sequentially in natural order` (278) | VALID | ordered `chunk.forEach` in the translation lane (:277-304) |
| `local compute runs inline, never overlaps native` (302) | VALID | `remote=false` → translation inline before inpaint (:337), sequential |
| `remote translation overlaps same-page inpaint` (348) | VALID | render join waits for both gates (:132-167) |
| `page without OCR reference skips inpaint/translation` (373) | VALID | null-ref skips inpaint (:422-425), skips translation (:280-282), render gate still settled (:232-235) |
| `unexpected stage failure stops the pass` (409) | VALID | the fixed replacement of the pre-round-1 defect; matches :600-615 |
| `unexpected inpaint failure is not reported as completed` (444) | VALID | inpaint throw → `UnexpectedBatchStageException` (:440) → FAILED/INPAINT |
| `unexpected translation failure is not reported as completed` (472) | VALID | per-page lane catch → `failureOutcome` → `Unexpected` (:287-294); `Unexpected.completedPageKeys` defaults empty (BatchCoordinatorInterfaces.kt:179) |
| `persistence rejection remains non-durable` (500) | VALID | `BatchPersistenceRejectedException` → `PersistenceRejected`, distinct status (:175-179, :552), no terminal pages (:559) |
| `cancellation stops the pass promptly` (527) | VALID | CancellationException rethrown at every catch site (:71, :150, :258, :434) |
| `long chapter keeps in-flight pages bounded` (553) | VALID | bound `<= MAX_NATIVE_LOOKAHEAD_PAGES + 1` is exactly the fallback chunk size (:47, :648); deterministic under `runTest` |

### Finding A1-01 — VALID (summary)

All 17 tests in `SequentialBatchCoordinatorTest.kt` are **VALID** at `56179d7`:
assertions match current typed-outcome + pause contracts. No WRONG/STALE/
FLAKY findings in this file. The two seed-evidence regressions were already
repaired here; the current file is the fixed state, not the defective one.

---

## BatchContextFrontierTest.kt

Production reference: `BatchContextFrontier.kt` (whole file); helpers
`PageTranslationState.kt:161` (`isTextlessTerminal`),
`TranslationContextChunkPlanner.kt:122` (`updateRollingContext`).

| Test (line) | Verdict | Notes |
|---|---|---|
| `resume seeds only the contiguous prefix` (13) | VALID | seed loop breaks at missing p1 (BatchContextFrontier.kt:42); p2 never folded |
| `non-textless terminal gap blocks later AI` (29) | VALID | failure gap recorded only at frontier+1 (:62-64); `blocksLaterAi` indexes past gap (:88-91) |
| `later reused pages fold only after traversal` (42) | VALID | `completed` map defers fold until contiguous (:72-85); out-of-order record cannot move frontier backward |
| `textless terminal advances without blocking` (60) | VALID | `isContextReady` treats TEXTLESS as ready (:106-108); no failure recorded (:60) |
| `partial page never advances frontier` (82) | VALID | PARTIAL not context-ready and not terminal-failure → dropped (:59-67) |

### Finding A1-02 — VALID (summary)

All 5 tests in `BatchContextFrontierTest.kt` are **VALID**; they encode the
current fragmented-resume frontier contract (contiguous-prefix seeding, gap
fencing for later AI pages, textless advance).

---

## BatchResumeGateDeciderTest.kt

Production reference: `BatchResumeGateDecider.kt:32-48` (delegates to
`TranslationLifecyclePolicy.nextStage`, `TranslationLifecyclePolicy.kt:93-117`),
predicates in `PageTranslationState.kt` (`hasCurrentInpaintMask` :67,
`hasCurrentInpaintResult` :23, `isCleanedImageReady` :31,
`isTextlessTerminal` :161).

| Test (line) | Verdict | Notes |
|---|---|---|
| `null page returns FULL` (13) | VALID | `nextStage(null) -> FULL` (TranslationLifecyclePolicy.kt:98) |
| `ocr not ready returns FULL even with mask and cleaned` (18) | VALID | stranded-RUNNING guard (BatchResumeGateDecider.kt:40) + mask clause requires `ocrStatus == READY` (:108) |
| `ocr ready with mask but no cleaned` (25) | VALID | `isCleanedImageReady` false (no file) → INPAINT clause (:113) |
| `mask + cleaned but inpaint not ready` (32) | VALID | `inpaintStatus == RUNNING` blocks `isCleanedImageReady` (PageTranslationState.kt:31-34) |
| `mask + durable cleaned returns SKIP_ALL` (42) | VALID | RENDER branch (:103-112) maps to SKIP_ALL (BatchResumeGateDecider.kt:42-44) |
| `mode mismatch on durable cleaned returns INPAINT_ONLY` (49) | VALID | FAST->QUALITY fencing via `inpaintModeMatches` (:96, :105); matches both assertions |
| `stale inpaint result returns INPAINT_ONLY` (76) | VALID | `hasCurrentInpaintResult` false when `inpaintRevision=0` + blocks (PageTranslationState.kt:23-24) |
| `ocr ready without mask returns FULL` (101) | VALID | `hasCurrentInpaintMask` false (non-empty blocks + empty mask) → FULL (:116) |
| `textless page with mask + durable cleaned returns SKIP_ALL` (124) | VALID | empty blocks ⇒ mask+result by definition (PageTranslationState.kt:68, :24) |

### Finding A1-03 — VALID (summary)

All 9 tests in `BatchResumeGateDeciderTest.kt` are **VALID**; they match the
current `nextStage` decision table including the mode-mismatch fencing added
for FAST/QUALITY switches.

---

## BatchProgressReconcilerTest.kt

Production reference: `BatchProgressReconciler.kt` — `reconcilePaused`
(:122-199), status mapping (:180-184), non-durable persistence handling
(:143-147, :193, :197).

| Test (line) | Verdict | Notes |
|---|---|---|
| `retryable pause preserves prefix...` (13) | VALID | prefix done, PARTIAL anchor partial, tail (index > anchorIndex) pending (:152-164); `paused` requires zero terminal (:190); retry/next-eligible propagated (:193, :195) |
| `persistence rejection is reconciled as an in-memory warning` (49) | VALID | empty retryablePageKeys falls back to anchor (:128-130); rejected anchor stays pending, never retryable (:143-147, :193); READY_WITH_WARNINGS + `nonDurableFailure` (:181, :197) |

### Finding A1-04 — VALID (summary)

Both tests in `BatchProgressReconcilerTest.kt` are **VALID**; they pin the
current typed pause/persistence-rejected reconciliation contract.

---

## BatchOomPolicyTest.kt

Production reference: `BatchOomPolicy.kt:9-27` (threshold 3, `>=` semantics,
reason only at/above threshold).

All 4 tests (`BatchOomPolicyTest.kt:10,17,23,31`) are **VALID**: they mirror
`shouldAbort` exactly, including the custom-threshold branch (:14).

## BatchEnvelopeLimitsTest.kt

Production reference: `StreamingChunkPlanner.kt` (greedy token admission
:110-134, flush :147-180), constraints `TranslationContextChunkPlanner.kt:20-33`
(`MAX_CONTEXT_TOKENS=8192`, overhead constants), batch reserve (:178-185).

Verified arithmetically: 10 pages × 8 short blocks ≈ 2.5k estimated prompt
tokens vs. `promptBudget ≈ 7302 - 88·pages`; no flush boundary is crossed, so
one envelope with `blockCount = 80` is the correct current behavior.

| Test (line) | Verdict | Notes |
|---|---|---|
| `dense chapter packs complete pages without static caps` (15) | VALID | token-based packing only; "provider-safety ceiling, not a page/block batching limit" (TranslationContextChunkPlanner.kt:17-19) |
| `envelopes preserve natural page order` (25) | VALID | refs appended in feed order; grouped into LinkedHashMap (:187-192) |
| `single dense page remains one envelope` (35) | VALID | pages are atomic (StreamingChunkPlanner.kt:15-16) |

### Finding A1-05 — VALID (summary)

All 3 tests in `BatchEnvelopeLimitsTest.kt` are **VALID**; they pin the T904
token-budget envelope contract (no static page/block caps, page atomicity).

---

## BatchTranslateBlockMergeTest.kt

Production reference: `ChapterTranslationStore.kt:86-98` (constructor used by
the test), `ChapterTranslationStore.kt:1100` (`updatePage`),
`PageTranslation.kt:285` (`blockId` defaults to null). The real batch merge
paths are index/wholesale-based, not blockId-keyed:
`ContextualResponseParser.kt:191-206` (`applyBatchToChunk` maps batch ids to
`pageKey + blockIndex` locations) and wholesale `blocks = ...toMutableList()`
copies in `TranslationPipeline.kt:1544, 2139, 2327, 3014`.

### Finding A1-06 — REDUNDANT (LOW)

- **Test**: `legacy blockId-keyed merge collapses every region to the last
  translation` (`BatchTranslateBlockMergeTest.kt:77-103`).
- **What it asserts today**: that an algorithm defined *inline in the test*
  (:92-96, the pre-fix `associateBy { blockId }` merge) corrupts blocks to
  `["THREE","THREE","THREE"]`.
- **Why redundant**: the only production code exercised is
  `ChapterTranslationStore.updatePage` (a plain map replacement); the
  blockId-keyed merge no longer exists anywhere in production, and the actual
  batch merge paths (`applyBatchToChunk`, pipeline wholesale copies) are never
  invoked by this test. It therefore adds no production coverage; it is
  defect documentation expressed as a test. The sibling test at :52-74 is
  likewise store-semantics-only but does pin the wholesale-copy shape the
  production merge relies on, so it retains regression value.
- **Risk if it rots**: none today (no production coupling); only risk is
  reader confusion if the class doc drifts from production.
- **Recommended action**: keep as documentation, or (better) delete and
  replace with a test that drives the real `applyBatchToChunk` merge and pins
  per-block translation identity at that boundary. Low priority.

### Finding A1-07 — VALID (summary)

`wholesale block copy preserves each block translation in the store`
(`BatchTranslateBlockMergeTest.kt:52-74`) is **VALID** as a store-semantics
regression guard for the wholesale-copy merge shape.

---

## BatchTranslationDiagnosticsTest.kt

Production reference: `BatchTranslationDiagnostics.kt` — `opaque()` hashing
(:176-177), clamped counters (:117, :128, :148, :157-159, :170-174),
allow-listed stages (:181-184).

| Test (line) | Verdict | Notes |
|---|---|---|
| `diagnostic messages hash page and fingerprint inputs` (10) | VALID | all five message builders route raw inputs through `opaque()`/`safeClass()`; enum names only |
| `schema contains only bounded counters and reason codes` (60) | VALID | negative values clamped by `coerceAtLeast(0)`; exact-string assertion matches template (:157-159) |
| `envelope lifecycle message has stable bounded fields` (73) | VALID | template match (:169-174); `envelopeId` is deterministic ShortHash, recomputed via the same function in the assertion |

### Finding A1-08 — VALID (summary)

All 3 tests in `BatchTranslationDiagnosticsTest.kt` are **VALID** and
deterministic (no timing/iterator dependence).

---

## ChunkTranslationPayloadTest.kt

Production reference: `ContextualRequestBuilder.kt:24-72` (`build`, stable
page-anchored ids via `BatchTranslationProtocol.blockId`),
`TranslationPrompts.kt:13-19` (`idMappedSourceLine`), `:36-50`
(`contextPrefix` order: glossary then pairs, empty when both blank), `:75-106`
(`pass1SystemPrompt`, batch examples use `p0_b0`/`p0_b1`),
`BaseTranslator.kt:28-47` (`translate` → flat mapping, blank blocks excluded).

| Test (line) | Verdict | Notes |
|---|---|---|
| `batch prompt carries stable block ids...` (21) | VALID | id format + system-prompt batch examples match; no bare `\\nb0\\|` in batch mode |
| `context prefix injects glossary and pairs first` (53) | VALID | header text/order matches `contextPrefix` (:41-48); prefix precedes body (:123-125) |
| `context prefix empty when blank` (74) | VALID | `contextPrefix` early-return (:39) |
| `standard engine pathway maps flat lists` (80) | **REDUNDANT (LOW)** — see A1-09 |
| `standard engine translate applies flat translations in place` (105) | VALID | drives real `BaseTranslator.translate` (:28-47) incl. blank-block exclusion and blank-translation drop (:42-45) |

### Finding A1-09 — REDUNDANT (LOW)

- **Test**: `standard engine translation pathway correctly maps flat string
  lists without prompt template overhead`
  (`ChunkTranslationPayloadTest.kt:80-102`).
- **What it asserts today**: that an anonymous mock's own
  `translateFlat` override returns the fixed list the override hard-codes
  (:85-95 vs :99-101). No production code path is exercised beyond object
  construction; `BaseTranslator.translate` / `translateFlat` defaults are
  never invoked.
- **Current contract**: the meaningful flat-mapping behavior (page walking,
  blank exclusion, write-back) is already covered by the sibling test at
  :105-132 through the real `BaseTranslator.translate` implementation
  (BaseTranslator.kt:28-47).
- **Likelihood**: n/a (documentation/tautology, not a runtime defect).
- **Recommended action**: delete, or rewrite to exercise a real engine's
  `translateFlat` contract if per-engine batching behavior ever becomes
  testable without network.

---

## DisplayReadyStageCountTest.kt

Production reference: `TranslationBatchProgressTracker.count()`
(`TranslationBatchProgressTracker.kt:369-409`) — DISPLAY success derives from
`toPageDisplayProjection(displayPages[key]).displayReady` (:382-384), DISPLAY
failed = stage-failed and not display-ready (:385-387), skipped =
`isTextlessTerminal` (:388); RENDER keyed on `renderStatus`
(:391-408). `hasRenderedResult` == `toPageDisplayProjection().displayReady`
(`PageTranslationState.kt:41-42, 70-71`).

### Finding A1-10 — REDUNDANT (MEDIUM): the whole file tests a private copy of production logic

- **Tests**: all four (`DisplayReadyStageCountTest.kt:54, 72, 100, 112`) call
  the test-local `countPages` helper (:26-51), which the file comment admits
  "mirror[s] TranslationBatchProgressTracker.count() without standing up the
  full tracker harness" (:27-29).
- **What is actually verified**: the mirror's behavior, not the tracker's.
  Every assertion passes against an in-test copy; a regression in production
  `count()` would leave this file green.
- **Divergence already present**: production DISPLAY counting is
  committed-display-aware — `toPageDisplayProjection(displayPages[key])`
  (:382-386, wired from `store.display` at :187-189, :280) — while the mirror
  only evaluates `hasRenderedResult` on the live page (:31-32). The two differ
  whenever a committed display bundle exists for a page whose candidate state
  regressed, so the mirror is not even a faithful copy of today's contract.
- **Why redundant**: the production entry point is already pure and public —
  `TranslationBatchProgressTracker.computeSnapshot(...)` (:260-279) returns
  `stageCounts` via `count()` (:309-311) with `displayPageMap = null`
  defaulting to the live map. No tracker harness is needed to test the real
  logic.
- **Likelihood**: n/a (coverage gap, not a runtime defect). Risk: future
  `count()` changes ship with this file falsely "passing".
- **Recommended action**: rewrite the four scenarios to call
  `computeSnapshot(pageMap = ..., chapterState = TRANSLATING)` and assert
  `stageCounts[BatchPhase.DISPLAY]` / `[BatchPhase.RENDER]`; delete the
  `countPages` mirror. The scenario data itself (color-estimated-but-not-
  displayable, displayable-with-cleaned, failed, textless) remains correct
  and current — only the mechanism is redundant.

### Finding A1-11 — VALID (summary)

The four scenario expectations themselves (asserted values per phase) match
current production semantics for the no-committed-display case; no WRONG or
STALE assertions were found in the file.

---

## TranslationBatchEventContractTest.kt

Production reference: `TranslationBatchEvent.kt:19-51` (sealed surface:
PagePhase, AiPageProgress, BatchAborted, BatchPaused, BatchFinished;
PagePhase defaults :25-27).

### Finding A1-12 — VALID (summary), with a LOW redundancy note

All 8 tests compile- and shape-match the current sealed surface:
`sealed subclass set is exactly the post-removal surface` (:20-30) is an exact
match. Note: the three negative tests (`BatchStarted is not a member`
:33-37, `BatchResumed is not a member` :40-44, `Revision events are not
members` :47-54) are logically **subsumed** by the exact-set assertion at
:20-30 — they add only failure-message clarity, not coverage. Acceptable as
documentation guards (LOW, keep).

## TranslationBatchProgressReducerTest.kt

Production reference: `TranslationBatchProgressTracker.kt` — reducer (:218-249),
projection-only contract (:27-29), `snapshotFor` fold (:167-202),
`runningStages` (:410-415), `progressStage` (:416-424), DISPLAY counting
(:380-389), `TranslationProgressSnapshot.kt:8-18` (StageCount.processed),
:56-73 (doneStages/totalStages/fraction), `ChapterTranslationStore.kt:1265`
(`preRegisterPages`).

| Test (line) | Verdict | Notes |
|---|---|---|
| `independent active page stages remain concurrent` (14) | VALID | PagePhase events fold to RUNNING per phase; store stays PENDING (projection-only); activeStages = {INPAINT, TRANSLATE} via runningStages |
| `stage counts expose exact counts` (36) | VALID | StageCount.processed = succeeded+failed+skipped (TranslationProgressSnapshot.kt:16); READY/FAILED/SKIPPED/PENDING → 1/1/1/0 |
| `terminal failure and textless skip are processed terminal work` (57) | VALID | failed: 5 phases × processed 1 = doneStages 5 / totalStages 5 → fraction 1f (doc "Failures are processed", :72-73); textless → DONE stage + TRANSLATE skipped |

### Finding A1-13 — VALID (summary)

All 3 reducer tests are **VALID** and deterministic (`runCurrent`, no
real time).

---

## TranslationBatchProgressTrackerTest.kt

Production reference: `TranslationBatchProgressTracker.kt` — terminal
drain/complete (:52-69), `rebuildFromStore` (:73-75), `pause` (:139-152),
`finish` (:126-137), `awaitTerminalSnapshot` (:160), reducer BatchPaused/
Finished mapping (:231-247), `inferAiState` (:426-433), `nextAiState`
(:435-446); `TranslationProgressSnapshot.kt:39` (aiProgress.processed).

All 8 tests verified against the tracker:

| Test (line) | Verdict | Notes |
|---|---|---|
| `rebuild from store publishes durable ready page progress` (42) | VALID | empty snapshot totals 0 (:71); fully-ready page → DONE, perStage all 5 succeeded |
| `phase events are projection only` (68) | VALID | fold writes projection copies only (:170-186); store PENDING preserved |
| `terminal event retains failure in projection` (82) | VALID | FAILED stage (:418); OCR failed count |
| `AI progress distinguishes five states` (96) | VALID | explicit states win over inference (:228); processed = succ+fail = 2 (TranslationProgressSnapshot.kt:39) |
| `AI progress rebuild derives durable states` (125) | VALID | inferAiState READY→SUCCEEDED, FAILED→FAILED, else PENDING (:426-433) |
| `retryable pause is a terminal projection` (147) | VALID | reducer PAUSED/FINISHED + anchor/cooldown passthrough (:235-241, :196-202) |
| `finish emits immutable terminal snapshot` (173) | VALID | BatchFinished mapping (:231-234) |
| `terminal snapshot available without later emissions` (192) | VALID | terminal drain completes deferred before events.close() (:57-67); deterministic under runTest |

### Finding A1-14 — VALID (summary)

All 8 tests in `TranslationBatchProgressTrackerTest.kt` are **VALID**.

---

## TranslationBatchTrackerRegistryTest.kt

Production reference: `TranslationBatchTrackerRegistry.kt` — access-ordered
bounded cache (:20-28, default 20 :140), `complete` (:76-81),
`completeIfCurrent` identity fence (:89-100), `replace` closes prior owner
(:67-72), `detachedCopy` (:132-137); tracker terminal drain
(`TranslationBatchProgressTracker.kt:42-67`).

| Test (line) | Verdict | Notes |
|---|---|---|
| `normal completion caches terminal snapshot before disposing` (15) | VALID | completeIfCurrent caches, removes live, closes (:95-99) |
| `cancellation caches terminal snapshot` (28) | VALID | BatchAborted → aborted + reason (tracker reducer :242-247) |
| `terminal cache is access ordered bounded and detached` (41) | VALID | LRU access-order get() then evict eldest (chapter 2) ✓; `detachedCopy` copies lists so later source mutation is not observed (:132-137, :53-63) |
| `late terminal from replaced tracker cannot close or cache over newer owner` (67) | VALID | queued terminal survives close, is drained, and is identity-rejected (:94) — pins the T904 late-terminal fix |

### Finding A1-15 — VALID (summary)

All 4 tests in `TranslationBatchTrackerRegistryTest.kt` are **VALID**;
deterministic under `runTest`/`runCurrent`.

---

## PageWorkPlannerTest.kt (`eu.kanade.translation.model`)

Production reference: `PageWorkPlanner.kt` — stage evidence (:356-425),
decision table (:193-306), dependency graph (:323-330: INPAINT depends only
on DETECTION; LAYOUT on TRANSLATION+INPAINT), provenance gate (:271-275,
:413-415), durable-failure handling (:201-227), chapter-level translation
fencing (:112-157); `PageWorkPlan.kt:23-45` (stage/decision enums),
:110-115 (`firstWorkPageKey` derivation).

| Test (line) | Verdict | Notes |
|---|---|---|
| `complete chapter reuses every stage` (15) | VALID | all-REUSE → `firstWorkPageKey` null (PageWorkPlan.kt:113-114); 5 stages in dependency order (BatchStage enum :23-29) |
| `target language change reruns translation...` (36) | VALID | mismatch → RUN; LAYOUT blocked by TRANSLATION (:329, :294-302) |
| `inpaint change reruns inpaint...` (56) | VALID | INPAINT RUN, TRANSLATION REUSE (independent branch), LAYOUT waits |
| `ocr change reruns ocr, preserves mask` (77) | VALID | INPAINT deps = DETECTION only (:327) with explicit mask-preservation comment |
| `source change invalidates detection + inpaint` (98) | VALID | source fingerprint gated to DETECTION/INPAINT (:387-389); cascading WAIT decisions |
| `required provenance reports unknown` (120) | VALID | UNKNOWN_PROVENANCE on null fingerprint (:271-275); RUN (no deps) |
| `layout-only change invokes layout only` (137) | VALID | LAYOUT RUN, all others REUSE |
| `reader ad hoc translation reused by batch` (158) | VALID | origin parsed but does not block REUSE when fingerprints current |
| `failed page blocks later translation` (176) | VALID | OCR FAILED (:241-242); page-2 translation WAIT via dependency; page-3 translation fenced to PRIOR_PAGE_INCOMPLETE (:121-146); `firstWorkPageKey` = page-2 |
| `retryable and terminal durable failures distinct` (193) | VALID | cooldown boundary at `nowEpochMs == nextEligible` (:220-225); terminal never retry-eligible |

### Finding A1-16 — VALID (summary)

All 10 tests in `PageWorkPlannerTest.kt` are **VALID** and pin the current
fingerprint/provenance/dependency planner contract, including the durable
failure model introduced by T903/T904.

---

## TranslationRetryTest.kt

Production reference: `TranslationRetry.kt:125-264` (`withTranslationRetry`:
terminal rethrow :201-215, pause-vs-retry :218-242, budget exempt in these
tests since `retryBudget` defaults null), classifier
`ProviderRequestGovernor.kt:763-821` (`classifyProviderFailure`) and
:665-728 + :730-760 (HTTP/429-quota classification; 429+RESOURCE_EXHAUSTED
with `retryAfterMillis == 0` → RETRY_AFTER :686-689),
`GeminiTranslator.kt:292-306` (`GeminiApiException` as
`ProviderFailureException`).

| Test (line) | Verdict | Notes |
|---|---|---|
| `success on first attempt` (14) | VALID | |
| `transient IOException retried` (25) | VALID | NETWORK/RETRY_NOW (:774-777); 1ms delays virtual |
| `rate limit message retried` (37) | VALID | "429"/"too many requests" → RETRY_NOW (:782-788) |
| `Gemini 429 uses retry hint` (49) | VALID | 429+RESOURCE_EXHAUSTED+retryAfter=0 → RETRY_AFTER, hinted delay 0 ≤ maxRetryDelay → in-loop retry, no pause |
| `exhaustion throws last transient` (62) | VALID | attempt >= maxAttempts → rethrow original (:201-215) |
| `non-transient rethrows immediately` (76) | VALID | IAE → CONFIGURATION/TERMINAL (:805-808) |
| `CancellationException propagates` (90) | VALID | rethrow before classification (:184-185) |
| `isTransientRateOrServerError detects` (104) | VALID | RETRY_NOW/RETRY_AFTER mapping (:275-279) |
| `isTransientRateOrServerError rejects` (114) | VALID | TERMINAL for IAE/auth/else (:805-812) |

### Finding A1-17 — VALID (summary)

All 9 tests in `TranslationRetryTest.kt` are **VALID**. They exercise the
no-budget legacy path (`retryBudget = null` → governor never engaged since
`requestMetadata` is null, TranslationRetry.kt:141, :164-171), which is the
documented compatibility surface.

---

## AiTranslationRetryControllerTest.kt

Production reference: `AiTranslationRetryController.kt` — frozen envelope +
detached snapshots (:538-603), budget accounting for governor-less doubles
(:507-536), typed outcome fan-out (:246-499), accumulator merge fence
(:168-198), refusal detection (:679-687), user-edit fence (:571-573, :662-667),
natural-prefix completion (:835-861), rolling delta only for Complete
(:749-780).

| Test (line) | Verdict | Notes |
|---|---|---|
| `first pass returns complete detached translations` (18) | VALID | live page blocks stay `translation=""`, `blockId=null` (freeze on detached copy :544-551); rolling delta non-empty (:769) |
| `transient failure gets one whole envelope retry` (41) | VALID | IOException → RETRY_NOW → WHOLE retry (:329-354); identical request ids + byte-identical context/glossary (:127-134) |
| `missing blocks use bounded missing-only request` (61) | VALID | missing-only targeted request keeps stable ids (:441-444, :112-135) |
| `missing budget exhaustion returns paused partial` (85) | VALID | policy gates exhausted → paused protocol failure PAUSE (:471-491); `partialCandidate=false` (:803) |
| `nested transport + semantic retries inside one budget` (110) | VALID | inner `withTranslationRetry` inherits outer budget (:141, :516); exhaustion → `RequestRetryBudgetExhaustedException` → PAUSE (:147-149, :104-113); transportCalls exactly 5 = budget |
| `duplicate and conflicting IDs terminal` (142) | VALID | first accepted value fenced, no overwrite (:180-187); terminal protocol summary contains `duplicate=1` (:877-879) |
| `malformed unknown ID terminal` (178) | VALID | `unknownCount` → protocolIssue (:646-651, :697-700) |
| `provider refusal terminal, never retried` (215) | VALID | structural refusal → TERMINAL (:679-687) |
| `terminal provider failure without semantic retry` (236) | VALID | ProviderFailureException passes through classifier (:768), TERMINAL branch (:307-317) |
| `user edit fence` (256) | VALID | edited block not requestable (:571-573); live edit preserved |
| `cancellation propagates` (273) | VALID | CancellationException rethrown at :303-304 and :497-499; no provisional publish |
| `stable identity survives whole retry` (290) | VALID | frozen ids across attempts |

### Finding A1-18 — VALID (summary)

All 12 tests in `AiTranslationRetryControllerTest.kt` are **VALID** and pin
the current T904 typed-outcome contract (typed Complete/Paused/Terminal, hard
request budget, stable ids, user-edit fence). This is the file family where
the seed-evidence "untyped exception" defects were fixed; the current tests
assert the fixed, typed behavior.

---

## AiTranslationRetryPlannerSinglePageTest.kt

Production reference: `AiTranslationRetryPlanner.kt:20-24`
(`untranslatedBlocks`: non-blank source AND (blank OR trim-equal translation)).

All 7 tests (`:19, :29, :41, :55, :65, :78, :87`) are **VALID**: each scenario
maps 1:1 onto the predicate, including whitespace-only translation (isBlank),
trim-equal source echo, and blank-source exclusion.

### Finding A1-19 — VALID (summary)

## ProviderRequestGovernorTest.kt

Production reference: `ProviderRequestGovernor.kt` — policy defaults
(:64-74), `estimatedTokens` (:56-61), admission/evaluate (:269-449),
`waitOrDefer` foreground budget (:451-470), `nextEligibleAt` (:472-498),
usage reconciliation (:521-540), cooldowns (:387-401, :542-553),
`RetryAfterParser` (:642-660), budget consumption only after admission
(:349-359); transport integration `TranslationRetry.kt:164-176`.

| Test (line) | Verdict | Notes |
|---|---|---|
| `rolling request and token reservations pace a shared bucket` (12) | VALID | admissions at t=0/100/200 under 100ms spacing; 20 polls × 10ms = 200ms; token cost 5 ≤ 20 cap |
| `different provider keys do not consume one another quota` (34) | VALID | per-key buckets (:266, :414) |
| `provider usage reconciles the reserved token cost` (47) | VALID | `release` records actualTokens=5 from `ProviderUsage.totalTokens` (:530); last event is the "completed" emission |
| `retry after parser accepts fractional seconds and http dates` (68) | VALID | ceil(fraction), negatives → 0, garbage → null, RFC-1123 delta (:644-659) |
| `long cooldown is deferred instead of sleeping` (76) | VALID | waitMs 3.6M > remaining 100 → Deferred, no sleeps (:460-468) |
| `transport retries reenter the governor for every attempt` (99) | VALID | admissions at 0/25/50 (25ms spacing, 5ms polls) + transport delays 10/20 → `clock.now == 50`; 3 "admitted" events |
| `transport helper turns a long retry hint into a typed pause` (128) | VALID | 3.6M hint > `maxRetryDelayMs` 30s → `ProviderRequestPausedException` (TranslationRetry.kt:226-242); 1 call, no sleeps |

### Finding A1-20 — VALID (summary)

All 7 tests in `ProviderRequestGovernorTest.kt` are **VALID** and
deterministic (FakeClock, virtual delays).

---

# Executive summary

**Scope covered**: 20 test files / 122 test methods, every file read in full
and compared against production sources at `56179d7`.

| Classification | Count | Findings |
|---|---|---|
| WRONG | **0** | — |
| STALE | **0** | — |
| REDUNDANT | **3 (+1 note)** | A1-06 (LOW), A1-09 (LOW), A1-10 (MEDIUM); A1-12 note (LOW, subsumed negative guards) |
| FLAKY | **0** | — |
| VALID | 115 tests across 17 files (+ the scenario data inside A1-10's file) | A1-01…A1-05, A1-07, A1-08, A1-11, A1-13…A1-20 |

## Seed-evidence verdicts

1. **`SequentialBatchCoordinatorTest.kt:408-437` pre-pause defect** —
   **repaired at `56179d7`**. The region is now
   `unexpected stage failure stops the pass` (:409-442) asserting
   FAILED/OCR-stage terminal semantics that match
   `SequentialBatchCoordinator.kt:189-197, 600-615`. The analogous sweep found
   **no remaining** test in area 1 that codifies `RuntimeException ->
   Completed`, terminal-provider-failure-later-pages-render, or untyped-
   exception smuggling: the coordinator, reconciler, tracker-registry, and AI
   controller suites all assert the current typed
   (`Paused`/`Failed`/`Unexpected`/`PersistenceRejected`) contracts.
2. **T904 round-2 stale assertion (terminal-provider-failure fixture)** —
   current fixtures assert the fixed behavior (see
   `AiTranslationRetryControllerTest` terminal tests,
   `SequentialBatchCoordinatorTest` unexpected-failure tests). No stale
   residue found.
3. `AotReportBubbleFillTest` — outside area 1 (assigned to another auditor);
   not audited here.

## Highest-value recommendations (all LOW effort)

1. **A1-10 (MEDIUM)**: replace `DisplayReadyStageCountTest`'s in-test mirror
   of `TranslationBatchProgressTracker.count()` with direct calls to the
   public pure `computeSnapshot(...)`, asserting `perStage` — restores real
   regression protection and eliminates the already-present committed-display
   divergence.
2. **A1-09 (LOW)**: delete or rewrite the tautological
   `ChunkTranslationPayloadTest:80` test (its sibling at :105 already covers
   the wiring through real `BaseTranslator.translate`).
3. **A1-06 (LOW)**: keep `BatchTranslateBlockMergeTest:77` only as
   documentation, or replace it with a test of the real `applyBatchToChunk`
   merge boundary (index-keyed, `ContextualResponseParser.kt:191-206`).

## Audit integrity notes

- All test expectations were derived by reading production implementations,
  not by executing the suite; arithmetic-sensitive assertions (envelope
  budgets, governor pacing, tracker fractions) were verified by hand-computed
  traces shown inline above.
- The worktree HEAD matched `56179d7` at audit time, so no `git show`
  snapshot reconstruction was required.

---

# Dispositions (fix round, branch `t906/fix-area1`)

Commit: `1a0db7b` — "test(translation): drive real display counter, prune
redundant batch tests" (single commit on top of `56179d7`, worktree
`t906-fix-a1`, 3 files, +90/−129).

## A1-10 (MEDIUM) — DisplayReadyStageCountTest — REWROTE (delete was wrong option)

A genuine invariant remains: RENDER (color estimation, `renderStatus`-keyed)
must be reported separately from DISPLAY (reader gate, shared
`PageDisplayProjection`), and DISPLAY must follow the committed display
pointer. The file was rewritten to drive the real production counter —
`TranslationBatchProgressTracker.computeSnapshot(...)` (public, pure) — so the
private `count()` logic under test is the shipped one, not a copy. The four
original scenarios were preserved with identical expectations, and a fifth
scenario was added that the mirror structurally could not express:
`regressed candidate with committed display still counts as DISPLAY
succeeded` (failed candidate + committed ready bundle → DISPLAY succeeded 1 /
failed 0, while OCR reports the live failure and RENDER the live pending
state). The `countPages` mirror and its imports were deleted.

## A1-09 (LOW) — ChunkTranslationPayloadTest:80 tautological test — DELETED

The test asserted its own anonymous mock's hard-coded `translateFlat` return
value; no production path was exercised. The sibling in-place test (now
:79-106) already covers the flat-mapping wiring through the real
`BaseTranslator.translate`. The now-unused `shouldContainExactly` import was
removed; the remaining 4 tests are unchanged and green.

## A1-06 (LOW) — BatchTranslateBlockMergeTest:77 legacy-merge test — DELETED

It does not guard a real invariant: the blockId-keyed algorithm it asserted
was defined inline inside the test itself and no longer exists anywhere in
production (batch merges are index/wholesale-based:
`ContextualResponseParser.applyBatchToChunk`, wholesale copies in
`TranslationPipeline`). Deleting it loses documentation, not coverage. The
surviving test keeps the real invariant (wholesale copy preserves per-block
translations through `ChapterTranslationStore.updatePage`); the class doc was
updated accordingly and the unused `shouldBe` import removed.

## Gates (all green)

- `spotlessApply` → clean; `spotlessCheck` → EXIT=0.
- `:app:compileStandardDebugKotlin` → BUILD SUCCESSFUL (pre-existing
  warnings only, none in touched files).
- Focused `:app:testStandardDebugUnitTest --tests` run, 39 tests, 0 failures:
  - touched: DisplayReadyStageCountTest (5, incl. new committed-pointer
    scenario), ChunkTranslationPayloadTest (4), BatchTranslateBlockMergeTest (1)
  - production counterparts: TranslationBatchProgressTrackerTest (8),
    TranslationBatchProgressReducerTest (3), TranslationPromptsTest (9),
    ChapterTranslationStorePersistenceTest (1),
    ChapterTranslationStorePhase3Test (8).
- Full suite intentionally not run (assembly runs it later).

---

# Unnecessary-test pass (audit only — no code changed)

Second review pass over area 1 on `t906/fix-area1` (`1a0db7b`), hunting for
tests that assert no real invariant, duplicate another test's coverage, test
trivial constants, or pin production contracts that no longer exist. Evidence
re-verified by grep against `app/src/main` on the fixed branch.

## U1 — BatchOomPolicyTest (whole file, 4 tests) — DELETE (with production pairing)

- **Location**: `app/src/test/java/eu/kanade/translation/batch/BatchOomPolicyTest.kt:10-34`
  (tests at :10, :17, :23, :31).
- **Why it adds no value**: the tested production object
  `BatchOomPolicy.shouldAbort` (`BatchOomPolicy.kt:12`) has **zero production
  callers** — grep over `app/src/main` finds the symbol only in its own file
  plus a style reference in `MemoryPressurePolicy.kt:18` ("how every other
  policy object in this package … is structured"). The tests pin a contract
  nothing consumes; the OOM abort decision actually used by the app lives in
  the memory-pressure/trim path (`MemoryPressurePolicy`), which has its own
  test (`MemoryPressurePolicyTest`).
- **Category**: production contract no longer exists in any form.
- **Recommendation**: DELETE the test file **together with** the dead
  `BatchOomPolicy.kt` + `AbortDecision` — the production deletion needs
  Director approval (out of audit-only scope here); deleting the tests alone
  would be safe but leaves the dead object unguarded by anything.

## U2 — TranslationRetryTest.kt:104-111 and :114-118 (2 tests) — DELETE (with production pairing)

- **Location**: `isTransientRateOrServerError detects rate limit phrasings`
  (:104) and `isTransientRateOrServerError rejects non-transient` (:114).
- **Why it adds no value**: the tested function
  `Throwable.isTransientRateOrServerError` (`TranslationRetry.kt:275`) is a
  self-described "Compatibility classifier for callers that still need a
  boolean retry check" — grep finds **zero production callers**. All real
  classification now flows through `classifyProviderFailure` (covered
  indirectly by the retry-loop tests in the same file). The two tests pin a
  dead shim's behavior.
- **Category**: production contract no longer exists in any form.
- **Recommendation**: DELETE both tests together with the shim
  (`TranslationRetry.kt:274-279`); production deletion needs Director
  approval.

## U3 — TranslationBatchEventContractTest.kt:33-37, :40-44, :47-54 (3 tests) — DELETE

- **Location**: `BatchStarted is not a member of the event surface` (:33),
  `BatchResumed is not a member` (:40), `Revision events are not members`
  (:47).
- **Why it adds no value**: the exact-set assertion at :20-30
  (`sealed subclass set is exactly the post-removal surface`,
  `shouldContainExactly` on the full name set) already fails for ANY addition,
  including these five names. The three negative tests are strictly weaker
  restatements — they can only pass when :20-30 passes. (Originally noted as
  LOW redundancy in A1-12; under the "duplicates another test's coverage"
  criterion they qualify for removal.)
- **Category**: duplicates another test's coverage.
- **Recommendation**: DELETE all three; keep :20-30 as the single surface
  guard (optionally keep one negative as a documentation guard if the team
  values the named-defect framing).

## U4 — BatchEnvelopeLimitsTest.kt:25-32 — DELETE

- **Location**: `envelopes preserve natural page order` (:25).
- **Why it adds no value**: with 6 pages × 10 short blocks the planner emits
  exactly ONE envelope (≈2.2k estimated tokens vs. ≈6.9k budget — same
  arithmetic as A1-05), and within a single envelope the test's monotonic
  natural-index assertion is equivalent to test 1's
  `chunks.single().pages.keys.toList() shouldContainExactly pages.keys` at
  :21 (10 pages, same single-envelope situation). No multi-chunk ordering
  path is exercised by either test, so :25 adds no distinct coverage.
- **Category**: duplicates another test's coverage.
- **Recommendation**: DELETE; if cross-envelope ordering ever becomes a
  concern, add a scenario that actually forces a flush boundary (token budget
  small enough to split).

## U5 — AiTranslationRetryPlannerSinglePageTest.kt:55-62 and :78-84 (2 tests) — DELETE

- **Location**: `all blank translations returns every non-blank-source block`
  (:55) and `whitespace-only translation is treated as untranslated` (:78).
- **Why it adds no value**: the predicate under test is a one-line filter
  (`AiTranslationRetryPlanner.kt:20-24`) with exactly two translation-side
  clauses: `isBlank()` and trim-equality. :29-38 already covers the
  `isBlank()` clause (""), and `isBlank()` is true for whitespace-only
  strings by definition, so :78 is the same branch with different input text;
  :55 is the same branch again with cardinality 2. The remaining tests keep
  one representative per distinct clause: :19 (no false positives when
  complete), :29 (blank clause), :65 (blank-source clause), :87 (trim-equal
  clause).
- **Category**: duplicates another test's coverage.
- **Recommendation**: DELETE :55-62 and :78-84.

## Keep-with-note (examined, not recommended for deletion)

- `PageWorkPlannerTest.kt:158` (`reader ad hoc translation ... reused by
  batch`): `origin` is populated (PageWorkPlanner.kt:419) but never read by
  any decision branch today, so the test passes "for free" — however it pins
  the real invariant that reuse is fingerprint-driven and that provenance
  origin must not block reuse if an origin gate is ever added. KEEP.
- `BatchResumeGateDeciderTest.kt:13` (`null page returns FULL`): one line,
  but pins a real consumed input contract (never-tracked page → full work).
  KEEP.
- `AiTranslationRetryControllerTest.kt:138`: `(transportCalls <= 5) shouldBe
  true` is an assertion-level duplicate of the exact `transportCalls shouldBe
  5` at :136 — not a test-level finding; the redundant line can be dropped
  opportunistically. KEEP test.
- `BatchResumeGateDeciderTest`, `SequentialBatchCoordinatorTest`,
  `BatchContextFrontierTest`, `BatchProgressReconcilerTest`,
  `BatchTranslationDiagnosticsTest`, `TranslationBatchProgressReducerTest`,
  `TranslationBatchProgressTrackerTest`, `TranslationBatchTrackerRegistryTest`,
  `PageWorkPlannerTest` (remaining), `AiTranslationRetryControllerTest`,
  `ProviderRequestGovernorTest`, and the post-fix
  `DisplayReadyStageCountTest`/`ChunkTranslationPayloadTest`/
  `BatchTranslateBlockMergeTest`: every remaining test maps to a distinct
  production branch or contract; no further unnecessary tests identified.

**Unnecessary-pass tally**: 12 tests across 5 files recommended for deletion
(U1: 4, U2: 2, U3: 3, U4: 1, U5: 2). No code was changed in this pass.
