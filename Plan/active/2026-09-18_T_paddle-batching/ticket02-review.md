# Ticket 02 Review — Paddle OCR leaf batch planner

- Reviewer: Reviewer agent (independent audit)
- Commit under review: `d26d57b` "Add Paddle OCR leaf batch planner"
  (branch `research/paddle-ocr-batching-02`, parent `e99e279`)
- Worktree: `research-paddle-ocr-batching-02` (clean at review start/end)
- Verdict: **PASS** — no retry needed (2 non-blocking observations)
- Method: full read of all 3 files; own test execution; own diff/boundary checks.
  No handoff claim taken on faith.

## File boundary — VERIFIED

`git show --stat d26d57b`: exactly 3 new files, 641 insertions, 0 deletions:

- `app/src/main/java/eu/kanade/translation/ocr/paddle/batch/PaddleOcrLeafWork.kt` (134)
- `app/src/main/java/eu/kanade/translation/ocr/paddle/batch/PaddleOcrBatchPlanner.kt` (215)
- `app/src/test/java/eu/kanade/translation/ocr/paddle/batch/PaddleOcrBatchPlannerTest.kt` (292)

Zero existing production/test files touched. No MangaOCR/MLKit/Detector-v4/
translation/lease/checkpoint code referenced or modified.

## Acceptance criteria audit

### 1. 10 compatible leaves → 8+2 at B8, 4+4+2 at B4 — VERIFIED (real assertions)

- `PaddleOcrBatchPlannerTest.kt:16-25`: asserts `assertEquals(listOf(8, 2), …sizes)`,
  bucket 640 for both, and leaf order `(0 until 10)` preserved across batches.
- `PaddleOcrBatchPlannerTest.kt:28-36`: asserts `listOf(4, 4, 2)` + order.
- Emission logic: full batch at exactly B on admit (PaddleOcrBatchPlanner.kt:118-122),
  remainder at finishPage (126-136); emit drains up to B (202-214).

### 2. Different width buckets never share a tensor/batch — VERIFIED

- Structural: per-bucket pending queues (Planner.kt:89-91); batch constructor
  rejects mixed buckets (Planner.kt:38-40) and mixed page generations (35-37).
- Test `different width buckets never share a batch…` (Test.kt:56-82): interleaved
  640/1600 admits produce 640/1600/640/1600 batches of 4/4/1/1, each batch
  single-bucket, per-bucket leaf order preserved.

### 3. Page/generation fence is real — VERIFIED

- `admit` requires `leaf.pageGeneration == pageGeneration` (Planner.kt:103-105);
  duplicate identity rejected via `admittedIdentities.add` (106-108).
- Test (Test.kt:196-218) rejects both a wrong pageId AND a stale generation,
  asserts zero state pollution (`inFlightLeafCount == 0`), then admits a valid
  leaf successfully. Duplicate identity test at Test.kt:221-229.
- `finishPage` seals the page; later admit throws (Test.kt:105-107, Planner.kt:102).

### 4. Deterministic JVM-only tests — VERIFIED (executed myself)

- JUnit Jupiter only; `CROP` instantiated as `String`; no Robolectric, no Android
  classes, no randomness, no timing, no coroutines.
- `useJUnitPlatform()` confirmed in buildSrc convention
  (`buildSrc/src/main/kotlin/mihon/buildlogic/ProjectExtensions.kt:115`).
- My run: `gradlew :app:testDevDebugUnitTest --tests …PaddleOcrBatchPlannerTest`
  with JAVA_HOME=Android Studio JBR → **BUILD SUCCESSFUL in 2m27s**;
  JUnit XML: `tests=13 skipped=0 failures=0 errors=0`. All 13 test names match
  the handoff list.
- The same task executed `:app:compileDevDebugKotlin` (production compile) —
  existing code compiles unchanged.

### 5. Existing non-Paddle OCR paths compile unchanged — VERIFIED

- Full production compile ran clean in my test execution; diff touches no
  existing file (see boundary section).

### 6. Hygiene — VERIFIED

- Pure data/planning: imports are `java.util.ArrayDeque/LinkedHashMap` and
  `java.util.concurrent.atomic.AtomicBoolean` only. No ONNX Runtime, no Android
  graphics, no bitmap geometry code anywhere in the new files.
- `PaddleOcrWidthBucket.forScaledWidth` (LeafWork.kt:48-51) mirrors the existing
  engine contract (≤640→640, >640→1600; cf. PaddleOcrV6SmallEngine alignWidth
  comment) without altering it; boundary case 640 and 641 asserted (Test.kt:232-241).
- Window bound enforced in code, not just claimed: `admit` throws
  `PaddleOcrBatchWindowFullException` when `pending + inFlight` for the bucket
  reaches `2 × batchSize` (Planner.kt:87, 111-114, 185-191); on rejection the
  leaf identity is un-registered so a retry can be admitted later (112) —
  correct non-poisoning semantics.
- Test (Test.kt:151-173) drives the bound to exactly 8 at B4, then completes a
  batch and verifies the window reopens; all 12 crops released exactly once.

### 7. Ownership released exactly once — VERIFIED

- `PaddleOcrCropOwnership.release()` is CAS-guarded (LeafWork.kt:83-87) —
  double-release is a no-op.
- `PaddleOcrBatch.mapResults` releases every crop in `finally` and seals the
  batch even if the mapper throws (Planner.kt:55-65); `releaseWithoutMapping`
  for cancellation (69-76); `complete`/`release` remove only the exact
  in-flight batch instance (reference equality, 144, 158).
- Tests assert `releaseCounts` == 1 for every crop on the happy path
  (Test.kt:188-191) and across the window-pressure path (171-172).

## Non-blocking observations

1. `planner.cancel()` and `planner.release(batch)` have no direct unit tests.
   The ownership mechanism makes them safe by construction, but when Ticket 03
   wires execution/cancellation, add coverage for the cancel path (pending +
   in-flight crops released once, page sealed).
2. The planner is intentionally unsynchronized — single-thread-per-page
   confinement is assumed. The Ticket 03 adapter owns threading; do not share a
   planner instance across threads.

## Gate statements

- B1 parity: not in scope for this ticket (planning only; no execution code).
- Memory/window bound: PASS (2B per bucket enforced + tested).
- No production regression: PASS (zero existing-file edits; full compile clean).

Verdict: **PASS — no retry needed.**
