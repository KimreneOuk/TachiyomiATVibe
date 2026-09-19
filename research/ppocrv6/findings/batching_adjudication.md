# PP-OCRv6 REC batching adjudication

Date: 2026-09-18  
Status: bounded host evidence adjudicated; no production change authorized

## Decision

Adopt **fixed-width, width-homogeneous B=1 as the production correctness and
stability baseline**. Carry **bucketed B=8 forward as the best measured host
throughput candidate**, but only as a staged Android experiment after it clears
the device gates below. B=4 remains an unmeasured/interrupted comparison, not a
proven safer middle point. Do not ship B=8 by inference from this host-only
work. Reject tight dynamic-width batching and do not select B=16 for the
current deployment profile.

This recommendation is deliberately conservative: the isolated REC harness
proves bucketed parity through B=16, while the bounded integrated probes now
cover bucketed B=1 and B=8. B=4 was stopped by host resource limits, and no
Android provider, PSS/native-heap, thermal, or latency trace exists.

## Evidence boundary

- `rec_batching.md`, `rec_batching_raw.csv`, `rec_batching_parity.csv`,
  `rec_batching_summary.json`, and `rec_batching_metadata.json` use the pinned
  pristine REC model (SHA-256 `5435fd...24634`), production preprocessing,
  48 real crops plus four controlled fixtures, and CPUExecutionProvider.
- The REC batch harness loaded all 76 manifest pages and all 486 regions on
  this host. The measured sample is 48 real crops; it is not a 486-region
  timing claim.
- `integrated_pipeline.md` and
  `results/integrated_b1_bucketed/integrated_pipeline.json` plus
  `results/integrated_b8_bucketed/integrated_pipeline.json` measure real DET
  output feeding REC on 12 pages / 214 regions. The manifest-wide 76-page
  audit passed, but only 12 pages were timed per bounded probe.
- CPU measurements are Windows host observations (ORT 1.24.1, four-thread
  recommendation from the component sweep), not Android performance claims.

## Fixed-width adjudication

"Pass" below means exact text/token parity against same-crop B=1 in the
*isolated host REC harness*, not production approval. Each fixed-width batch
size has 48 real and four fixture rows in `rec_batching_parity.csv`.

| Policy | Host parity | Host signal | Adjudication | Production status |
| --- | --- | --- | --- | --- |
| Bucketed B=1 | 48/48 real and 4/4 fixtures; zero text/token/shape mismatches | 1.354 crops/s aggregate; p50/p90/p95 727.8/958.6/1032.9 ms; peak RSS delta 6.9 MiB | Correctness/stability reference; integrated evidence exists | **Green baseline** |
| Bucketed B=2 | 48/48 real and 4/4 fixtures; zero mismatches | 1.408 crops/s; p50/p90/p95 693.1/921.3/1021.5 ms; RSS 25.9 MiB | Correct, but small host throughput gain and no integrated/device evidence | **Yellow; do not default** |
| Bucketed B=4 | 48/48 real and 4/4 fixtures; zero mismatches | 1.405 crops/s; p50/p90/p95 687.9/1043.7/1177.5 ms; RSS 50.0 MiB | Correct in isolation; meaningful memory increase; bounded integrated B=4 run was interrupted before a result | **Yellow; unmeasured** |
| Bucketed B=8 | 48/48 real and 4/4 fixtures; zero mismatches | 1.616 crops/s isolated; p50/p90/p95 651.8/976.3/1148.6 ms; RSS 54.3 MiB | Also exact in bounded integrated DET→REC (214/214); best measured host candidate, but integrated peak RSS and Android behavior remain gates | **Yellow; staged candidate** |
| Bucketed B=16 | 48/48 real and 4/4 fixtures; zero mismatches on pristine CPU ONNX | 1.717 crops/s; p50/p90/p95 568.1/705.2/732.8 ms; RSS 57.9 MiB | Outside checked-in TensorRT profile max batch 8; larger memory envelope; no integrated/device evidence | **Red for current profile** |

The apparent throughput ordering is not a universal device result. In some
width buckets batching flattened or regressed CPU throughput, and measured
padding waste remained approximately 74–92% because production exposes 640 or
1600 widths. Device provider scheduling, allocator behavior, thermals, and
crop distribution can change the ranking.

## Tight dynamic-width adjudication

Tight width is not a safe drop-in batch optimization. B=1 tight is internally
parity-clean, but it changes the current production tensor-width contract and
has no integrated or Android validation. Once crops with different widths are
batched, the gray-padded width changes the REC time axis/logits:

| Tight batch | Real rows | Text mismatches | Token mismatches | Shape mismatches |
| ---: | ---: | ---: | ---: | ---: |
| B=1 | 48 | 0 | 0 | 0 |
| B=2 | 48 | 13 | 20 | 20 |
| B=4 | 48 | 19 | 33 | 32 |
| B=8 | 48 | 26 | 41 | 40 |
| B=16 | 48 | 27 | 43 | 43 |
| **B>1 total** | **192** | **85** | **137** | **135** |

All 40 controlled fixture rows were clean, so fixtures do not expose the
real-crop failure. The real-crop failures are sufficient to classify tight
B>1 as a quality failure, not a performance trade. A future tight design
would require a separately validated graph/export and a masking or equivalent
semantics-preserving strategy, followed by full parity coverage.

## Integrated DET → REC adjudication

The complete bounded integrated results are bucketed B=1 and B=8:

- 12 measured pages and 214 detector-produced crops (all from real DET output);
- 76/76 manifest pages passed audit, but the timing run was capped at 12;
- integrated REC text, token IDs, and logits matched independent B=1 REC for
  all 214 crops (zero mismatches);
- eight fresh-session DET replay pages passed;
- warm REC throughput was 1.31 regions/s, with page p50/p95 of
  11,411/29,298 ms and maximum per-page RSS delta 47.95 MB;
- cold session initialization was 852 ms for DET and 1,497 ms for REC.

- The B=8 bounded probe used the same 12 pages and 214 detector-produced crops;
  its warm REC wall time was 28,402 ms, or 7.46 regions/s and 0.39 pages/s,
  versus 1.31 regions/s for B=1 (5.7x higher host throughput).
- B=8 exact parity held for all 214 crops (zero text/token mismatches and zero
  shared-logit difference), and the fresh-session DET replay again passed.
- B=8 maximum per-page RSS delta was 229.61 MB, versus 47.95 MB for B=1; this
  is a material resource cost even though the process stayed below its 3,072 MB
  RSS guard.

This establishes B=1 as the end-to-end correctness/stability baseline and B=8
as a bounded host-throughput candidate, not an Android latency or memory
promise. There is no completed integrated B=2, B=4, or B=16 result. The
recorded B=4 run was stopped before any configuration result, and the 76-page
multi-policy sweep was stopped at approximately 6.0 GB committed / 1.1 GB
free. Both are **resource-limit failures**, not evidence for or against
batching speed or parity. Tight-width integrated policies were not part of the
completed bounded handoff; their isolated B>1 parity failures still reject
them.

The pipeline simulator's 3.70x overlap result and its width-bucket preference
are model direction only; its Android timing and thermal values are assumptions
until replaced by device traces.

## Memory and resource limits

The source/memory report supports one serialized PP-OCR page worker and one
in-flight ROI on the current engine. It estimates a PP-OCRv6 REC input buffer
at 0.88 MiB each (pool cap two), but that excludes ORT arenas, model mappings,
source/crop bitmaps, Java/native overhead, provider workspaces, and allocator
fragmentation. The isolated host RSS deltas increase from 6.9 MiB at B=1 to
25.9/50.0/54.3/57.9 MiB at B=2/4/8/16. In the integrated probe, B=8 reached a
229.61 MB maximum per-page RSS delta versus 47.95 MB for B=1. These are
comparative host harness values, not Android PSS limits, but the integrated B=8
delta makes a device memory gate especially important.

The failed sweep shows that broad multi-policy concurrency can exhaust the
host before producing evidence. It does not prove that any one production
batch will OOM, but it rules out using that sweep as a performance result.
Future runs must be one policy per process with an RSS guard and bounded page,
crop-count, and byte queues.

## Android gates before enabling batching

1. **Provider/shape gate:** run B=8 (and optionally B=2/B=4 for comparison) on
   Android 8+ representative devices with the actual ORT artifact and provider (CPU,
   NNAPI/QNN where supported). Verify the fixed 640/1600 shape buckets and
   reject B=16 unless the accelerator profile is explicitly expanded and
   revalidated.
2. **Parity gate:** compare every batched crop to serial B=1 on real pages,
   including vertical/tall rotations, narrow and wide boxes, empty/near-empty
   boxes, and controlled fixtures. Require zero text/token mismatches and
   zero output-shape mismatches; capture max logit drift as a diagnostic.
3. **Memory gate:** record Java heap, native heap, PSS/RSS, direct-buffer pool
   occupancy, ORT/provider allocations, and peak page/crop bytes over at least
   60–70 real pages. Enforce a byte cap in addition to a crop-count cap; do
   not infer safety from the host RSS deltas.
4. **Latency/thermal gate:** collect cold and warm per-page/per-crop p50/p95,
   queue high-water marks, sustained throughput, skin/SoC temperature, and
   throttling over a representative chapter. Require no regression for manual
   current-page work and keep background work preemptible.
5. **Lifecycle gate:** prove cancellation, trim-memory, provider failure, and
   session recreation release tensors, direct buffers, bitmaps, and queued crops
   without use-after-close. Critical memory pressure must fall back to serial
   B=1 and requeue safely.
6. **Stability gate:** run repeated chapters and long sessions with no native
   heap growth, leaked direct buffers, crash, ANR, or normal-manga regression.
   Keep the existing CPU fallback and honest provider status reporting.

## Final recommendation

Keep **bucketed B=1** as the release baseline now. If Android gates are met,
trial **bucketed B=8** behind instrumentation and a feature flag: it is the
best measured host-throughput candidate, but its integrated 229.61 MB peak RSS
delta must be proven acceptable on target devices. B=4 may be measured later as
a fallback comparison, but its interrupted run provides no evidence that it is
safer or faster. Do not ship tight B>1 or current-profile B=16.
