# Dynamic Page Batching — Design Spec

Date: 2026-10-09
Branch: `release/apk-6-candidate`
Status: approved by user ("Yes add dynamic. Because 2 second per page is really bad.")

## Problem

PaddleOCR recognition currently runs at a fixed microbatch size (user preference
`B1`/`B2`/`B4`, build-capped at 4 in debug/release-candidate variants). Every ORT
call pays a fixed dispatch overhead (provider submission, tensor marshaling, CTC
decode setup). On the Snapdragon 8 Gen 3 test device a typical manga page has
15–25 recognizer leaves, so the default `B1` path issues 15–25 sequential ORT
calls and the OCR stage alone lands around 2 seconds per page.

## Goal

Let one ORT call carry **the entire page's leaves for a width bucket** — a
dynamic batch dimension sized by the actual page content — instead of a fixed
chunk of 4. Measured OCR stage time should drop toward
`ceil(leaves / ceiling) × per-call-cost` with the per-call overhead amortized.

## Non-Goals

- No change to detection (single-pass DBNet), grouping, or the B1 single-crop
  correctness contract — `recognizeWithConf` stays the universal fallback.
- No new providers, no session rebuilds, no cross-page batching (batches remain
  page- and generation-scoped).
- No persistence of dynamic decisions; per-session only, like the existing
  governor.

## Current Architecture (contract we must preserve)

| Layer | File | Role |
|---|---|---|
| Preference | `TranslationPreferences.paddleOcrRecognitionBatch()` | user tier B1/B2/B4 |
| Activation | `PaddleOcrBatchActivationPolicy` → `PaddleOcrDevicePolicy` | staged flag, build cap, device matrix, thermal gate; failure ⇒ emergency CPU B1 |
| Governor | `PaddleOcrRollingP95HysteresisDowngradePolicy` | rolling P95 with hysteresis, enum ladder B8→B4→B2→B1, recovery capped at max |
| Coordinator | `PaddlePageOcrCoordinator` / `PaddlePageOcrBatchDispatcher` | page-scoped leaf mapping, mutex-seamed recognizer calls |
| Planner | `PaddleOcrBatchPlanner` | per-width-bucket queues, emits when `queue.size == batchSize.value`, remainder at `finishPage()` |
| Executor | `PaddleOcrV6BatchExecutor` | ORT loop, failure/latency downgrade ladder, telemetry; `normalizeBatch` caps at 8 |
| Buffers | `PaddleOcrV6BatchBufferPool` | lazy direct-buffer leases, `maxBatchSize` validation, output element bound |

## Design

### 1. New preference tier: `DYNAMIC`

`PaddleOcrRecognitionBatch` gains `DYNAMIC`. The activation policy maps it to
tier `B8` + `dynamicPageBatch = true` on `PaddleOcrBatchActivation`. The staged
build-config cap (`capAt`) does **not** clamp dynamic requests — the fixed cap
exists because B8-fixed was never device-validated, while dynamic mode carries
its own memory-derived ceiling and runtime failure halving. All other gates
(staged flag, provider selection, device matrix + debug provisional opt-in,
thermal guard) apply unchanged, so production safety behavior is identical to
today's B4 path.

### 2. Memory-derived per-bucket ceiling

New pure policy `PaddleOcrDynamicPageBatchPolicy(dictionarySize, …)`:

```
inputBytes(b)  = b × 3 × 48 × paddedWidth × 4          // NCHW float32
outputBytes(b) = b × (paddedWidth / 8) × (dict + 2) × 4 // B×T×C float32
ceiling(bucket) = min(hardMax, inputFit, outputFit), ≥ 1
```

The checked-in PP-OCRv6 dictionary has **18,708 lines → 18,710 classes**
(`PP-OCRv6_small_rec.txt`; the ONNX class dim is 18,710), so per-sample output
is 5.99 MB at WIDTH_640 and 14.97 MB at WIDTH_1600 — output bytes dominate.
The output budget is RAM-tiered via `ActivityManager.MemoryInfo.totalMem`:
≥ 5 GiB → 96 MiB (ceiling **16** at 640 / **6** at 1600), ≥ 3.5 GiB → 48 MiB
(8 / 3), otherwise 32 MiB (5 / 2). Input budget is a flat 16 MiB; hard max is
32. The output tensor is ORT-native and transient (freed after CTC decode),
and the vision lane is single-permit serialized, so the tier budget is a peak
transient, not resident. The stride (8) and extra classes (+2) are promoted to
public constants on the policy and referenced by the buffer pool so the model
contract lives in one place; a unit test pins the ceiling math to the shipped
asset's line count so the two cannot drift apart silently.

### 3. Planner: per-bucket chunk function

`PaddleOcrBatchPlanner` gains `chunkSizeFor: (PaddleOcrWidthBucket) -> Int`
(defaulting to `batchSize.value`). Window guard, admit-emit threshold, and
`emit()` all use `chunkSizeFor(bucket)`. `PaddlePageOcrPolicy` carries
`dynamicCeiling: ((PaddleOcrWidthBucket) -> Int)? = null`; the dispatcher feeds
it to the planner. A page with ≤ ceiling leaves in a bucket produces exactly one
batch (emitted at `finishPage()`), which is the "entire page batch" the user
asked for; denser pages produce the minimal number of full chunks.

### 4. Executor: raise the hard cap, halve dynamic failures

- `normalizeBatch`: `maxBatch.coerceIn(1, HARD_MAX_BATCH=32)`.
- `lowerBatch`: batches > 8 halve, then fall into the reviewed fixed ladder
  (`15 → 7 → 1`; anything ≤ 8 keeps `8 → 4 → 1`). Provider failures,
  allocation failures, and latency-budget overruns all ride the same ladder.
- The buffer pool is constructed with `maxBatchSize = HARD_MAX_BATCH`. Input
  leases stay lazily sized (a B15×640 lease ≈ 5.5 MiB; ≤2 retained), and ORT's
  natively-allocated output is bounded by the ceiling math above.

### 5. Governor interplay: tier = dynamic cap

In dynamic mode the governor is armed with `initial = maximum = B8`. `B8` means
"uncapped dynamic". A sustained P95 overrun downgrades to B4/B2/B1, and the
engine then caps the dynamic chunk at the tier value
(`chunk = if (tier == B8) ceiling(bucket) else min(ceiling(bucket), tier.value)`),
degrading gracefully to today's fixed behavior under thermal pressure. Recovery
climbs back to B8 = full dynamic. `RoiPageRecognitionEngine` already rebuilds
the coordinator on governor decisions; the rebuild re-applies the capped
ceiling.

### 6. Wiring

`RoiPageRecognitionEngine` resolves the activation (now possibly dynamic),
constructs `PaddleOcrDynamicPageBatchPolicy(dictionarySize = engine.dictionarySize)`
after engine init, and passes a governor-aware `dynamicCeiling` into
`PaddlePageOcrCoordinator`. The dispatcher's `recognizeBatch` passes the actual
chunk size to `recognizeBucketBatch` so the executor runs one call per planner
batch unless its own ladder splits it. Telemetry reports the tier plus the max
observed chunk; traces gain `requestedChunkSize`.

### 7. UX

Settings list gains `DYNAMIC` — "Auto — whole page (dynamic)". Summary updated.
Persisted enum values are name-based, so existing installs are unaffected.

## Risks & Mitigations

- **QNN HTP may reject batch > 8** → provider-failure halving ladder converges
  to the largest working size; worst case degrades to today's behavior.
- **Output tensor memory on dense pages** → the RAM-tiered output budget
  dominates the ceiling (96 MiB ≈ B16@640 / B6@1600 on ≥5 GiB devices, 48 MiB
  ≈ B8/B3 on mid devices); allocation failures halve further.
- **CTC decode cost grows with batch** → linear in leaves either way; argmax
  over the same total elements as N small calls.
- **Crop hoarding until finish()** → planner window guard still applies
  (chunk×2); crops are small recognition-height bitmaps, bounded by detections
  per page.

## Test Strategy

Pure-policy unit tests (ceiling math), planner chunking tests (single
whole-page batch, window guard, mixed buckets), executor tests (B15 single
call, halving ladder, hard-cap normalization), activation tests (DYNAMIC
mapping, staged-off emergency), coordinator test (dynamic policy end-to-end),
plus the existing suite stays green.
