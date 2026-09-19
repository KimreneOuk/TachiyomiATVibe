# Ticket 05 Review — Paddle page integration (critical wiring ticket)

- Reviewer: Reviewer agent (independent audit)
- Commit under review: `dd9abaf` "feat(ocr): integrate Paddle page microbatches"
  (branch `research/paddle-ocr-batching-05`, parent `2d63947`)
- Worktree: `research-paddle-ocr-batching-05` (clean at review start/end)
- Verdict: **PASS** — no retry needed (2 follow-up requirements, 1 observation)
- Method: full diffs of all 4 main files read line-by-line; planner mirror
  verified against the unchanged sequential logic; all tests run by me
  post-patch.

## Boundary — VERIFIED

`git show --stat dd9abaf`: exactly 6 files — task README, new
`PaddlePageOcrCoordinator.kt` (457), new `PaddleVerticalRecognitionPlan.kt` (33),
`RoiPageRecognitionEngine.kt` (+195/−47 of the commit's deletions),
`VerticalLineOcr.kt` (+296), new coordinator test (172). Zero touches to
NativeRunQuarantine, leases, PageOcrCheckpoint, BatchLaneWorkers, envelope, or
checkpoint files. MangaOCR/MLKit untouched.

## Mandatory concurrency decision — VERIFIED REAL (criterion 8)

- README (`Plan/active/2026-09-18_T_paddle-batching/README.md`): records the
  DECISION with rationale — serialize every batch call with one Mutex per
  Paddle coordinator/engine session; explicitly refuses to rely on the
  allocation-failure downgrade as concurrency design; explicitly no second
  nativeGuard per microbatch and no in-flight ORT preemption claim. This is a
  decision, not a deferral.
- Code matches the prose: `PaddlePageOcrCoordinator.batchCallMutex = Mutex()`
  (Coordinator:167) handed to the dispatcher, which wraps the actual
  `recognizeBucketBatch` call in `batchCallMutex.withLock { … }`
  (Coordinator:121-123, 301-303). One planner per dispatcher
  (Coordinator:81), and every planner call (admit/finishPage/complete/release/
  cancel) is confined to the single coroutine driving that dispatcher —
  consistent with the planner's single-thread contract. Concurrent pages get
  separate dispatchers/planners and share only the mutex-guarded engine seam.

## Criterion 1 — Count-checked drain, no early return: VERIFIED

- `PaddlePageOcrBatchDispatcher.finish()` drains `finishPage()` batches then
  `check(planner.isDrained)` (Coordinator:107-110).
- Coordinator checks `resolvedLeafCount == expectedLeafCount` for the initial
  phase (216-219) and the fallback phase (263-267), with expected counts derived
  from bound identities. `compose` errors on any missing result (424-425).
  A partial page cannot be returned.

## Criterion 2 — No cross-page/generation batches: VERIFIED (test found)

- One planner per `(pageId, generation)`; `submitPlan` binds every leaf to the
  coordinator call's single `pageGeneration` (Coordinator:382-395); the planner
  rejects foreign identities before queueing (Ticket-02 fence, re-verified).
- `different page generation cannot enter a visible page tensor`
  (CoordinatorTest:82-95): a look-ahead page's leaf throws
  `IllegalArgumentException` at `dispatcher.submit` — BEFORE any tensor
  submission (no recognizer call exists on that path) — the foreign crop is
  released exactly once (`releases == 1`), and no trace carries the foreign
  page. `manual auto and chapter modes…` (59-79) asserts every trace and every
  leaf identity carries the SAME pageGeneration across B4 batches.

## Criterion 3 — B1-equivalent output, ordering preserved: VERIFIED

- CoordinatorTest:29-56 drives the 8-leaf `fallbackHeavy` fixture (the
  Ticket-04 parity fixtures) through the dispatcher + REAL executor + fake
  session and asserts `assertIterableEquals(fixture.expectedRows, mappedRows)`
  — exact strings AND confidences, input order.
- VerticalLineOcr planner mirror verified branch-by-branch against the
  unchanged sequential functions: identical det filter thresholds, sort keys
  (`-(x0+x2)/2` / `(y0+y3)/2`), `h > w*1.5f` vertical test, CJK set, reversed
  heuristic column order, `MIN_COLUMN_WIDTH_PX` skips, glyph row split,
  separators (`joinSeparator`), and `filterConfidence` flags for each fallback
  shape (det-empty whole-crop and heuristic-single-column read WITHOUT
  confidence filter — matching the sequential paths at VerticalLineOcr:442-447,
  495-500).
- The only existing-code change in VerticalLineOcr is the
  `recognizeSingleLine` filter extraction — the inlined guard
  (`conf < OCR_MIN_CONFIDENCE && conf < 1f`) is textually identical inside
  `filterRecognizedText`. Zero behavior change to existing paths.

## Criterion 4 — Durable semantics preserved: VERIFIED

- The coordinator sits UPSTREAM of publication: it returns region text; the
  engine maps results through the unchanged `appendRecognizedBlock` →
  `lockedRecognizedBlocks` → `RecognizedAnalyzeResult` commit boundary — the
  exact same boundary the sequential path used. No new checkpoint/durable write
  exists in the commit.
- Cancellation/failure: dispatcher `execute` releases the batch, cancels the
  planner, clears state, and rethrows (Coordinator:140-150); `submitPlan`
  releases the failing leaf and recycles unsubmitted ones (400-408); the
  coordinator cancels both dispatchers and publishes nothing (281-287);
  ownership is idempotent end-to-end (CAS tokens). CoordinatorTest case 4
  proves: cancellation → all leaves released, zero results, fresh retry maps
  exactly.
- nativeGuard still covers the whole page: the handoff block sits inside the
  existing guard scope (engine diff context: "hold nativeGuard across detect +
  the per-ROI OCR loop"), unchanged coverage.

## Criterion 5 — Modes and wait telemetry: VERIFIED

- `PaddlePageOcrMode` MANUAL(0) < AUTO(1) < CHAPTER(2); engine maps
  TranslationTraceMode AUTO→AUTO, BATCH→CHAPTER, MANUAL/null→MANUAL
  (engine diff). Cross-page admission untouched (scheduler-owned; README).
- `PaddlePageBatchTrace` carries `queueWaitMs` + `admissionWaitMs`
  (Coordinator:53-61, 137-138); asserted nonnegative in tests (CoordinatorTest:76);
  the engine logs total waitMs per page. Mutex acquisition happens at the batch
  boundary — no in-flight preemption exists or is claimed.

## Criterion 6 — Non-Paddle behavior unchanged: VERIFIED

- The Paddle branch is strictly `engine is PaddleOcrV6SmallEngine &&
  coordinator != null`; every other engine (and Paddle with a null coordinator)
  falls through to the original sequential code, which is preserved verbatim
  below the inserted branch. `MangaOcrPreprocessTest` green (2/2 in my run).
- The removed inline TranslationBlock construction is extracted verbatim into
  `appendRecognizedBlock` (field-for-field identical: parent selection/trim,
  RenderColorEstimator, direction rule `boxHeight > boxWidth*1.2f`, mask
  overlap test) and is now SHARED by both paths — no divergence introduced.

## Criterion 7 — Engine diff is handoff-only: VERIFIED

- Engine additions: coordinator/counter fields, coordinator construction at
  Paddle init (B1), the guarded handoff block, the Paddle mapping branch,
  verbatim extraction, and `paddlePageOcrCoordinator = null` on close.
  Page generations come from an AtomicLong per attempt (retry = new
  generation ✓). RoiPageRecognitionEngine remains the orchestrator.

## Criterion 9 — Full selector post-patch: VERIFIED (my own run)

`gradlew :app:testDevDebugUnitTest` with all 8 suite selectors, JAVA_HOME =
Android Studio JBR: **BUILD SUCCESSFUL in 3m03s; TOTAL tests=50 skipped=0
failures=0 errors=0** (Parity 4, Lifecycle 8, Executor 9, SmallEngine 5, CTC 5,
Planner 13, MangaOcrPreprocess 2, Coordinator 4). The handoff's "pre-patch full
run" concern is closed — I re-ran the FULL set after the final patch.

## Criterion 10 — Hygiene: VERIFIED

Focused collaborators (dispatcher 155-line concern, coordinator, 33-line plan
type), engine stays orchestrator, comments minimal and purposeful.

## Follow-up requirements (binding, fold into Ticket 06+)

1. **Confidence-filter semantics on the det-missing degraded path.** For
   `paddleDet == null` and a non-tall region, the coordinator routes
   `planMultiLine(…, verticalFallback=false)` → `planWholeRegion`, which
   applies the 0.5 confidence filter (`filterConfidence=true`). The OLD engine
   inline `else` branch for that same case read `recognizeWithConf` + isUsable
   with NO confidence filter (RoiPageRecognitionEngine:589-592). The planner
   faithfully mirrors `recognizeMultiLine` → `recognizeSingleLine` (which DOES
   filter) — the old inline else was the internal outlier — but this is still a
   behavior change on a reachable degraded configuration (det asset missing):
   sub-0.5-confidence usable text is now dropped where it previously survived.
   Direction is conservative, healthy installs (det asset present) never hit
   it. REQUIRED: Ticket 06 must either align this case with the old inline
   behavior or record the tightening as an intended semantic in the task
   README. Do not leave it undocumented.
2. **B4/B8 promotion gate**: the coordinator accepts an injectable
   `validatedBatchSize`; the README and policy correctly hold production at B1.
   Per the Ticket-04 follow-up, any B4/B8 injection requires a fresh post-commit
   device gate with the anti-self-fallback guard active.

## Non-blocking observation

`rotatedForOcr` in the batch path is a region-level approximation
(`paddleVerticalHeuristic || tallVertical`, plus unconditional true on reread
entry) versus the sequential path's per-branch tracking. Verified
diagnostic-only: it feeds the `[ocr_block]` log line and nothing else (block
construction ignores it; `angle` is 0f in both paths). Cosmetic drift in one
log field; no action needed.

Verdict: **PASS — no retry needed.**
