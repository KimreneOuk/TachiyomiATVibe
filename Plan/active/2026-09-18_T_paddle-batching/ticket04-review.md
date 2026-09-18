# Ticket 04 Review — B1 parity + lifecycle fault-injection

- Reviewer: Reviewer agent (independent audit)
- Commits under review: `765be24` "test(ocr): gate Paddle B1 parity and page lifecycle",
  `f07f465` "docs(ocr): record SM8650 B1 parity result"
  (branch `research/paddle-ocr-batching-04`, parent `d6a1685` merge of tickets 02+03)
- Worktree: `research-paddle-ocr-batching-04` (clean at review start/end)
- Verdict: **PASS** — no retry needed (4 non-blocking observations)

## Boundary — VERIFIED with one nuance

`git show --stat` on both commits: **zero `app/src/main` changes**, as claimed.
Nuance: `765be24` also modifies four `app/src/benchmark` files (runner +67,
serializer +51, result model +28, activity +2) to add an on-device `parityMode`.
This is NOT a boundary violation — the benchmark source set merges only into
`devBenchmark` (established in the Ticket 01 audit), so production behavior is
untouched. I reviewed the benchmark diff in full and separately verified
`:app:compileDevBenchmarkKotlin` **BUILD SUCCESSFUL** post-commit (Ticket 01's
deliverable remains healthy).

## Criterion 1 — Parity assertions are EXACT: VERIFIED

- `PaddleOcrV6B1ParityTest.assertExactB1Parity` (lines 48-83) is a **three-way
  exact comparison** per leaf: (a) single-crop reference via
  `recognizePaddleWithConfPath` (a faithful mirror of the production flow:
  [1,3,48,W] input → session → shape read → `PaddleCtcDecoder.argmaxWithProbs` +
  `decodeWithConf`), (b) the real batch executor path, (c) the fixture's declared
  expectation. Compared with `assertEquals` on **text strings** and
  **`toRawBits()` of confidences** (lines 77-81) — no lengths, no tolerances.
  Input `[1,3,48,W]` and output `[1,T,C]` shapes asserted per call (62-68).
- Both detector configurations covered by `detectorPresent()`/`detectorAbsent()`
  (identical leaf set, stage/fallback kind switched), plus `fallbackHeavy()`.
- Fixture meaningfulness (scrutinized as instructed):
  - Vertical glyph split **actually produces GLYPH leaves**: r1-glyph0/glyph1
    with `glyphIndex` 0/1, `rotation = CCW_90` (Fixtures:177-200) — and the
    mapping test asserts rotation/identity/parent order through the REAL
    planner (ParityTest:85-128).
  - Empty region flows an all-blank payload through real CTC → `"" to 0.0f`
    (Fixtures:38-46, 201-211).
  - Detector-empty heuristic fallback: r4 leaf with stage
    `detector-empty-heuristic-fallback` (Fixtures:212-222).
  - Confidence-filtered: represented as **absent region 3** — asserted at the
    mapping layer (`filteredRegionIndexes == [3]`, no mapped leaf in region 3,
    ParityTest:109-112). Thin but honest at this layer.
  - **Unpadded reread is a stage-labeled leaf, not an exercised reread path.**
    The actual empty-first-read bitmap logic lives in `RoiPageRecognitionEngine`
    and cannot run on JVM. The docs scope this honestly (see criterion 5), and
    the leaf-level parity claim (any crop the pipeline emits, both paths agree)
    is what B1 promotion needs. Not a relaxation — but see observation (b).

## Criterion 2 — Mismatch blocks promotion with identity: VERIFIED

- On-device runner: any mismatch → `error("B1 parity mismatch; B4/B8 and
  accelerator promotion remain blocked: …")` listing page/region/leaf/stage and
  BOTH texts and BOTH raw confidence bits (Runner diff).
- JVM: every assertion message carries `page=… region=… leaf=… stage=…`
  (`identityLabel()`, ParityTest:137-138).

## Criterion 3 — Fault outcomes asserted, not just "no exception": VERIFIED

- Cancellation at all 4 points (BEFORE_SUBMISSION in writeSample, DURING_ORT in
  `session.run`, AFTER_EXECUTION on shape read, DURING_MAPPING in the mapper —
  LifecycleTest:19-37, 198-208): each asserts `CancellationException`, **checkpoint
  == null** (no partial publication), full ownership release, then a fresh retry
  asserting exact rows + identity order + ownership released (89-105).
- Provider downgrade B8 page: call sequence `[8,4,1×8]`, reasons
  `[provider_failure, provider_failure]`, final rows exact + in order (40-58).
- Terminal provider / terminal allocation: `PaddleOcrV6BatchExecutionException`,
  nothing published, all admitted AND never-admitted leaves released (61-68,
  107-124, incl. explicit never-admitted release at 183).
- Stale generation: old batch rejected by the new planner
  (`IllegalArgumentException`), new planner state unpolluted (71-87).

## Criteria 4-7 — Host-runnable, doc/test agreement, MLKit, my own run: VERIFIED

- Suites are pure JVM (named fixture builders, deterministic fakes; no Android,
  no Robolectric).
- `paddle-b1-failure-matrix.md`: every row names the test that proves it; two
  explicit scoping disclaimers (detector matrix is a fixture concern, not
  claimed from the crop-only device run; direct Bitmap/ORT allocation-fault
  injection is **UNTESTED** on JVM). **No documented-but-untested rows.**
- MLKit claim verified by search: no MLKit JVM suite exists in the test source
  sets — only incidental `mlkit` string mentions in unrelated coexistence/
  settings/translator suites.
- **My own selector run** (all 7 suites): **BUILD SUCCESSFUL in 3m; TOTAL
  tests=46 skipped=0 failures=0 errors=0** (B1Parity 4, PageLifecycle 8,
  BatchExecutor 9, SmallEngine 5, CtcDecoder 5, Planner 13, MangaOcrPreprocess 2).

## Criterion 8 — Device artifact honesty: VERIFIED

- Report discloses the discrepancy verbatim: "The device run completed before
  the test/benchmark changes were committed. The APK reported `d6a1685` as its
  build SHA; the exact source tree used for this run is now committed as
  `765be24`" (report lines 28-30). JSON `"commit": "d6a1685"`, `parityMode:
  true`, `passed: true`, `comparedSamples: 2`, confidence bits 1065345780 /
  1065322600 — exactly as handed off.
- No user/chapter data: downloaded/external corpus `disabled`; CSV contains only
  the two committed fixture crops. PSS peak 246,530 KiB, 38 samples @75ms.
- Post-commit device rerun remains UNTESTED (ADB refused) — disclosed, not
  hidden; I verified the committed benchmark source compiles
  (`compileDevBenchmarkKotlin` green) and the committed JVM suite passes.

## Criterion 9 — Hygiene: VERIFIED

Named fixture builders and small focused test methods throughout; minimal,
purposeful comments; no binaries in either commit (all text files).

## Criterion 10 — Promotion verdict sanity: VERIFIED

PROMOTE B1 / **BLOCK** B4+B8 / **BLOCK** accelerator / MangaOCR-MLKit unchanged —
matches the evidence: exact parity on JVM (both detector configs) + both device
fixtures; honest CPU-actual-despite-QNN-request (no accelerator claim); no B4/B8
device gate run. Consistent with the story's non-negotiable gates.

## Non-blocking observations

1. **The on-device parity runner's anti-self-fallback guard is excellent and
   must be carried forward**: parity mode fails if
   `lastBatchTelemetry?.downgradeReason != null` (Runner diff), which closes the
   loophole where the batch path silently falls back to `recognizeWithConf` and
   parity passes trivially. Future B4/B8 device gates need the same guard.
2. Unpadded-reread and confidence-filter are identity/stage-level fixture
   representations, not exercises of the real reread/filter paths. If a later
   ticket claims end-to-end coverage of `RoiPageRecognitionEngine` behavior, it
   needs instrumentation-level tests — the current docs correctly do not.
3. Device parity evidence binds to the pre-commit dirty tree (now `765be24`).
   Given B4/B8 promotion is blocked anyway, require a fresh post-commit device
   run as part of that promotion gate rather than treating this artifact as final.
4. `batched=true` in parity-mode JSON describes the extra B1 batch-call mode
   (schema v2, separate `benchmarkName`), while per-crop sample rows keep
   `batched=false`. No ambiguity in practice; keep the schema-version pairing
   (`2` ↔ `paddle_ocr_v6_b1_parity`) stable for downstream tooling.

Verdict: **PASS — no retry needed.**
