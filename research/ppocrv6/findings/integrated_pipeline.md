# PP-OCRv6 integrated DET → REC findings

Status: **bounded research evidence; production change not authorized**

Raw evidence:

- [benchmark harness](../benchmark_pipeline.py)
- [bounded B=1 JSON](../results/integrated_b1_bucketed/integrated_pipeline.json)
- [bounded B=1 page CSV](../results/integrated_b1_bucketed/integrated_pipeline_pages.csv)
- [bounded B=1 batch CSV](../results/integrated_b1_bucketed/integrated_pipeline_batches.csv)
- [bounded B=1 parity CSV](../results/integrated_b1_bucketed/integrated_pipeline_parity.csv)
- [bounded B=8 JSON](../results/integrated_b8_bucketed/integrated_pipeline.json)
- [bounded B=8 page CSV](../results/integrated_b8_bucketed/integrated_pipeline_pages.csv)
- [bounded B=8 batch CSV](../results/integrated_b8_bucketed/integrated_pipeline_batches.csv)
- [bounded B=8 parity CSV](../results/integrated_b8_bucketed/integrated_pipeline_parity.csv)
- [interrupted 76-page sweep](../results/integrated_pipeline_interrupted.json)
- [interrupted B=4 run](../results/integrated_b4_bucketed_interrupted.json)

## Corpus and audit

The manifest declares 76 pages and 486 annotated regions across three chapters.
The harness performed a real PIL `verify()` plus RGB decode/load audit for every
manifest page: **76/76 pages decoded, 0 failures**. The bounded timing run was
deliberately limited to the first 12 audited pages (the host was concurrently
running other model sweeps); it measured 214 actual PP-OCRv6 DET regions and
never used annotation boxes as detector substitutes. No page binaries were
copied into the repository.

## Contract exercised

The harness mirrors the checked-in Android path: DET long-side resize to 736
with PIL bilinear, black padding, ImageNet normalization, DB threshold 0.20 and
box threshold 0.45, fragment merge, Kotlin-style integer back-projection, 12px
REC crop context, 90° CCW rotation for tall crops, 48px height, gray-128 pad,
RGB `[-1,1]` normalization, and production 640/1600 width bucketing. REC is
fed only crops produced by the real DET output.

## B=1 bucketed result

| scope | value |
| --- | ---: |
| audited pages / measured pages | 76 / 12 |
| detected regions / REC crops | 214 / 214 |
| DET wall time | 45,332 ms |
| independent B=1 REC parity reference | 214 crops, 19,518 ms |
| integrated warm REC wall time | 161,630 ms |
| warm pages/sec | 0.07 |
| warm regions/sec | **1.31** |
| page p50 / p95 | 11,411 / 29,298 ms |
| maximum per-page RSS delta | 47.95 MB |

Cold session initialization was 852 ms for DET and 1,497 ms for REC. The first
page is marked `cold_first_page`; remaining pages are warm. All 214 integrated
REC outputs matched their independent B=1 reference text and token IDs, with
zero mismatches and zero shared-logit difference. The fresh-session DET replay
passed on the eight replay pages.

## B=8 bucketed bounded probe

The independent B=8 process used the same 12 audited pages and 214 detected
regions, with the RSS guard set to 3,072 MB. It completed without crossing the
guard. Warm REC wall time was 28,402 ms (7.46 regions/sec, 0.39 pages/sec;
page p50/p95 2,939/4,161 ms). Cold session initialization was 372 ms for DET
and 667 ms for REC. Maximum per-page RSS delta was 229.61 MB. Independent
parity was exact for all 214 crops (zero text/token mismatches and zero shared
logit difference), and the fresh-session DET replay passed.

Compared with the B=1 warm baseline (1.31 regions/sec), B=8 was 5.7× faster on
this bounded CPU probe. This is a throughput observation only; it does not
establish Android/NPU behavior or a safe production batch size.

## Resource-limited sweep outcome

An earlier single-process 76-page sweep retained too much state while sweeping
B=1/4/8/16 and bucketed/tight policies. It reached approximately 6.0 GB
committed with approximately 1.1 GB free and was stopped before any complete
configuration result. This is recorded as a **FAILED_RESOURCE_LIMIT** result,
not as a performance claim. The subsequent B=4 bounded process was also
stopped during host memory contention before producing a complete result.

The complete policy measurements in this handoff are bounded B=1 and B=8 with
production bucketed width. B=4 and all tight-width results are explicitly
unmeasured rather than inferred. The bounded harness accepts one policy per
process, a page cap, and an RSS guard so future runs can compare policies
without repeating the unsafe sweep.

## Recommendation

Use B=1 bucketed as the correctness and stability baseline, and carry B=8
bucketed forward as the best measured host-throughput candidate pending a
target-device RSS/latency gate. Do not select dynamic tight-width from this
host run: it was not measured in the bounded handoff. All measurements are
host CPU evidence only and must not be read as Android latency promises.
