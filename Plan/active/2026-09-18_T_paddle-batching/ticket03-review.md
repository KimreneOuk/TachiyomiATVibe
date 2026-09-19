# Ticket 03 Review — Paddle v6 bucket microbatch execution

- Reviewer: Reviewer agent (independent audit)
- Commit under review: `38b3f7e` "feat(ocr): add Paddle v6 bucket microbatch execution"
  (branch `research/paddle-ocr-batching-03`, parent `e99e279`)
- Worktree: `research-paddle-ocr-batching-03` (clean at review start/end)
- Verdict: **PASS** — no retry needed (1 follow-up requirement for the integration
  ticket, non-blocking here)
- Method: full engine diff read line-by-line against parent; all 4 collaborator
  files + 325-line test file read fully; tests executed by me.

## File boundary — VERIFIED

`git show --stat 38b3f7e`: 6 files, 1218 insertions, 8 deletions — 4 new main
collaborators, 1 new test, and `PaddleOcrV6SmallEngine.kt` (+112/−8). No
planner/page/provider-policy files touched (ticket 02's files live on a separate
branch and are absent here, as expected). No binaries; all files are text.

## Criterion 1 — EXACT B1 parity: VERIFIED (the hard gate)

- Preprocessing is shared *by construction*: `preprocess(crop, out, baseOffset = 0)`
  gained one additive parameter; the pixel-write loop adds `baseOffset` to each
  absolute put (engine lines 253-255). `recognizeWithConf` still calls
  `preprocess(crop, pixelBuffer)` → baseOffset 0 → byte-identical writes to the
  parent commit. The batch writeSample lambda calls the SAME function
  (engine lines 173-178) and additionally `check(actualWidth == widthBucket)`
  (175-177) so a bucket mismatch cannot silently produce wrong shapes.
  Resize (BitmapPool ARGB8888 + FILTER_BITMAP_FLAG), PAD_GRAY erase, NCHW
  layout, normalize(), alignWidth() are all the same code paths.
- Tensor shape parity: single-crop path runs [1,3,48,W] (line 129); batch path
  runs [B,3,48,widthBucket] with B actual slot count (executor 190-198); for B1
  these are identical.
- CTC decode parity: `PaddleOcrV6BatchCtcDecoder` slices each [T,C] row and calls
  the PRE-EXISTING `PaddleCtcDecoder.argmaxWithProbs` + `decodeWithConf`
  (BatchCtcDecoder.kt:30-35) — the exact two functions recognizeWithConf uses
  (engine 137-138). No reimplementation of CTC semantics anywhere.
- Result parity: for B1 the row slice is the whole output; values identical
  (pooled copy vs direct floatBuffer — same floats).
- recognizeWithConf body itself: untouched by the diff (verified in the full
  diff hunk list; only surrounding members changed).

## Criterion 2 — One ORT call per microbatch incl. partial: VERIFIED

- Executor loop: `actualBatch = min(currentBatchSize, remaining)` (Executor:68);
  `runAttempt` performs exactly ONE `session.run` per attempt (187-198), counted
  via `sessionRunCount`/`runs`.
- FakeSession records every call shape; test 1 asserts `[1]×10` / `[4,4,2]` /
  `[8,2]` call sizes AND `sessionRunCount == calls.size`
  (ExecutorTest:13-39). Final partial runs at actual size.

## Criterion 3 — Output rows map to input leaf order: VERIFIED (real assertion)

- writeMarker writes the crop id into slot base offset (Test:260-267); FakeSession
  reads slot i's marker from the input buffer and emits logits keyed by it
  (Test:292-296); test 1 asserts decoded texts follow input order
  (Test:31-37), and the provider-failure test asserts order preservation across
  the 8→4→1 downgrade (Test:64-66). A row permutation would fail these.

## Criterion 4 — Bounded, observable allocation: VERIFIED

- Hard output cap in code: `maxOutputElements = maxBatch × (maxWidth/8) ×
  (dict+2)` (Pool:30-32); `acquireOutput` throws beyond it (66-74). Production
  value: 8 × 200 × 18710 × 4 B = 119,744,000 B ≈ 114.2 MiB — matches the handoff.
- Input bound: max 2 retained buffers, ≤ 8×3×48×1600 floats each (~7.2 MiB);
  output: 1 retained buffer. Idle-retained buffers are discarded before
  reallocation (175-187).
- Observable: `snapshot()` exposes allocation counts, failures, retained/active
  buffers, maxOutputBytes (89-99); executor telemetry carries input/output/peak
  bytes, sessionRunCount, downgradeReasons (285-311). Test 9 asserts reuse
  (1 allocation across two B4 runs) and byte math (Test:235-241).
- Allocation failure → downgrade, not crash: BufferAllocationException →
  PaddleOcrV6BatchFailure(ALLOCATION_FAILURE) → 8→4→1 → at B1 surfaced as
  PaddleOcrV6BatchExecutionException with telemetry → engine falls back to
  per-crop path (Engine:181-188). Test 6 asserts B8→B4 with reason.

## Criterion 5 — No silent CPU fallback in strict mode: VERIFIED

- Engine.initialize rejects a cpu-like provider BEFORE session assignment and
  closes the created session (Engine:75-80); strict executor also rejects
  before anything runs (Executor:26-28).
- Test 8: strict + provider "cpu" → PaddleOcrStrictProviderException with
  `session.calls.size == 0` (Test:195-203) — rejection strictly precedes the
  session call.
- The B1 allocation-failure fallback calls the unchanged `recognizeWithConf` on
  the ALREADY-constructed (strict-gated) session — it cannot create a CPU
  session or reroute providers (Engine:186-188 + comment). Provider failures are
  rethrown at B1, never converted to CPU (Executor:103-119; Engine:189-190).

## Criterion 6 — Single-crop path untouched / universal fallback: VERIFIED

- `recognizeWithConf` diff: zero changes. `initialize` gained a default param
  (`strictProviderMode = false`) — all existing callers (incl. the Ticket 01
  benchmark) compile unchanged. `close`/`forceReleaseNativeBuffers` only extended;
  `reclaimPooledMemory` overrides the pre-existing RoiOcrEngine no-op
  (RoiOcrEngine.kt:36). Fallback = the per-crop path itself.

## Criterion 7 — God-file check: VERIFIED

- Engine growth is wiring: fields (8), initialize strict-gate + collaborator
  construction (~20), recognizeBucketBatch + KDoc (~40), close/release/reclaim
  (~8), preprocess offset (3 lines + signature). All bulk logic lives in
  PaddleOcrV6BatchExecutor (393), BufferPool (266), BatchSession (86),
  BatchCtcDecoder (44). Comments are sparse and purposeful throughout.

## Criterion 8 — Ticket-02 observations carried through

- (a) Direct cancel/release tests: PRESENT and meaningful in ticket 03's scope —
  failed-copy lease release (Test:69-93: active in/out = 0), lease idempotence
  (Test:95-105: double close safe), cancellation propagation with in-flight
  input release (Test:107-126: CancellationException rethrown raw, active = 0).
  Note: ticket 02's planner.cancel()/release() tests remain on the 02 branch;
  the integration ticket should carry them as planned there.
- (b) Threading confinement: DOCUMENTED explicitly (Executor KDoc:6-10 "owning
  engine must serialize calls… intentionally confined to that caller"; Pool
  KDoc:14-15 "one executor owns a pool for one recognizer session") and
  mechanically supported at the seam where it matters: pool methods are
  @Synchronized, leases are idempotent and disjoint, bounds are hard. See the
  follow-up requirement below — serialization of `recognizeBucketBatch` itself
  is NOT mechanically guarded, which is acceptable for this ticket but must be
  decided at integration.

## Criterion 9 — Hygiene: VERIFIED

- Text-only commit; no model/chapter binaries; minimal comments; no ORT imports
  in the decoder/pool test seam beyond the thin Ort adapter (session interface
  keeps executor/decoder JVM-pure and fake-driven).

## Test execution (my own run)

`gradlew :app:testDevDebugUnitTest --tests PaddleOcrV6BatchExecutorTest --tests
PaddleOcrV6SmallEngineTest --tests PaddleCtcDecoderTest` (JAVA_HOME = Android
Studio JBR): **BUILD SUCCESSFUL in 1m58s**; JUnit XML: PaddleCtcDecoderTest 5,
PaddleOcrV6BatchExecutorTest 9, PaddleOcrV6SmallEngineTest 5 → **19 tests,
0 failures, 0 errors, 0 skipped**. Matches the handoff exactly.

## Non-blocking follow-up (REQUIREMENT for the integration/wiring ticket)

Concurrency posture: the pool tolerates 2 concurrent executors on the input side
(maxInputBuffers=2, mirroring inputPixelPool maxPoolSize=2) but has
`maxOutputBuffers = 1`. Two overlapping `recognizeBucketBatch` calls are
functionally safe (synchronized pool, disjoint leases, ORT session thread-safe)
but the second caller will hit the output-buffer bound → allocation-failure
downgrade → per-crop fallback for its entire crop list: correct results and
honest telemetry, but a large latency cliff. The wiring ticket MUST either
(a) serialize `recognizeBucketBatch` calls (mutex/confined dispatcher), or
(b) consciously size maxOutputBuffers to the supported OCR parallelism, and
record the decision in the task README. Do not leave this to runtime chance.

## Gate statements

- B1 parity: PASS (shared preprocess + shared CTC functions, additive offset only).
- Memory bound: PASS (hard caps in code, downgrade on breach, telemetry).
- Provider no-fallback: PASS (strict gate before session.run; B1 fallback
  reuses the gated session; provider failure rethrown).
- Production isolation: PASS (recognizeWithConf untouched; single-crop path
  remains the universal fallback).

Verdict: **PASS — no retry needed.**
