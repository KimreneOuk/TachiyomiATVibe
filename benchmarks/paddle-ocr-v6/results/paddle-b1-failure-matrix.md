# Paddle B1 parity and lifecycle gate

## Parity matrix

| Configuration | Cases | Exact assertions | Result |
| --- | --- | --- | --- |
| Detector present | horizontal line, vertical glyph split, empty, detector-empty heuristic fallback, unpadded reread, fallback-heavy mapping | text, confidence bits, `[1,3,48,W]` input, `[1,T,C]` output, IDs, order, rotation, parent region | **CONFIRMED** JVM |
| Detector absent | horizontal/heuristic line, vertical glyph split, empty, confidence-filtered region, unpadded reread, fallback-heavy mapping | same exact assertions | **CONFIRMED** JVM |
| SM8650 fixture run | committed 640 and 1600 crops | per-crop vs `recognizeBucketBatch(..., B1)` text and raw confidence bits | **CONFIRMED** 2/2; CPU actual provider |

The detector-present/absent distinction is a page-geometry fixture concern and
is not invoked by the crop-only Android benchmark. The benchmark records this
explicitly rather than claiming detector coverage from a crop run.

## Fault matrix

| Injected fault/stage | Expected behavior at the tested seam | Evidence |
| --- | --- | --- |
| Cancellation before submission | release the input lease; no ORT call; no OCR checkpoint; retry the same page generation | `PaddleOcrV6PageLifecycleTest.cancellation before submission...` **PASS** |
| Cancellation during ORT execution | propagate `CancellationException`; release input/output resources; no checkpoint; deterministic retry | `...during ORT execution...` **PASS** |
| Cancellation after ORT execution | propagate cancellation while reading the returned shape; close output/input; no checkpoint; deterministic retry | `...after execution...` **PASS** |
| Cancellation during result mapping | mapper may have partial in-memory rows, but the batch is released and no checkpoint is published; retry remaps all leaves once | `...during result mapping...` **PASS** |
| Provider failure at B8/B4 | retry the same rows at B4/B1; preserve input order and publish only after the page drains | `provider failure downgrades...` **PASS**; telemetry is `provider_failure, provider_failure` |
| Terminal provider failure at B1 | surface `PaddleOcrV6BatchExecutionException`; release the page; publish nothing; retry from the same generation | `terminal provider failure...` **PASS** |
| Allocation failure at B8/B4/B1 | surface the bounded executor failure without partial publication; page owner releases all admitted and unadmitted leaves; retry from the same generation | `terminal allocation failure...` **PASS** |
| Old page generation result | new planner rejects the old batch; no stale result enters the new page | `old-generation batch...` **PASS** |

The production engine's allocation-only terminal fallback remains the existing
unchanged `recognizeWithConf` path; direct Android Bitmap/ORT allocation-fault
injection is **UNTESTED** on the JVM. The deterministic executor/page seam proves
that no checkpoint is published before the fallback/retry result set is whole.

## Promotion decision

- **B1 correctness gate: PROMOTE** — exact parity is confirmed in both detector configurations on JVM fixtures and on both committed Android width fixtures.
- **B4/B8 rollout: BLOCK** — this ticket proves correctness/lifecycle only; no B4/B8 device gate was run.
- **Accelerator route: BLOCK** — the SM8650 run requested QNN HTP but honestly registered CPU, so no accelerator claim is made.
- **MangaOCR/MLKit: unchanged** — no production engine files were modified; the existing MangaOCR suite remains green and no MLKit JVM suite exists in this source set.
