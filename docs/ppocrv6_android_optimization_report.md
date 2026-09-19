# PP-OCRv6 Android Optimization Engineering Report

Date: 2026-09-18  
Scope: existing Wave-1/Wave-2 artifacts only; no production code, model asset,
or page binary was modified for this report.  
Evidence rule: `CONFIRMED` means directly measured or read from a checked-in
artifact; `LIKELY` is an engineering inference; `UNTESTED` means the required
runtime/device was unavailable; `FAILED` means an explicit negative result.

## Executive decision

Keep the current CTD-style detector as the page/region detector and retain
PP-OCRv6 DET only as an optional ROI line-refinement stage. Keep the validated
FP32 CPU path as the Android baseline and fallback. The only optimization that
is ready to carry forward as a correctness-preserving experiment is production
REC width bucketing, with B=1 as the release baseline, B=4 as the first staged
device candidate, and B=8 as the second staged candidate. Do not ship PP-OCRv6 DET as a CTD replacement, INT8/QDQ DET or REC,
FP16 DET, dynamic tight-width REC batching, or any GPU/NPU/QNN route based on
the current evidence.

The exact recommended Android architecture is therefore:

1. CTD detector -> bounded ROI queue -> PP-OCRv6 DET line refinement where
   useful -> PP-OCRv6 REC FP32.
2. One long-lived session per model, direct-buffer and bitmap pools, and
   `nativeGuard`-compatible serialization until Android PSS/native-heap data
   justifies more concurrency.
3. Fixed REC width buckets (640/1600) and B=1 for manual/current-page work;
   coalesce background work only after on-device parity, memory, and thermal
   gates pass.
4. CPU as the honest fallback. Treat OpenVINO results as Intel-host evidence,
   and treat QNN/HTP as a separate fixed-shape QDQ investigation, not as a
   property of the current FP32 graphs.

## Evidence scopes and corpus boundaries

These scopes must not be conflated:

| Scope | What it contains | What it does not prove |
|---|---|---|
| 76-page/486-region manifest | Three chapters, 76 readable pages and 486 annotated regions on this Windows host. Used by detector, CTD comparison, REC audit, and page audit. | It is not an Android run, not independent ground truth for all detector semantics, and external paths may not resolve on another host. |
| 12-page bounded integrated run | Real DET output on the first 12 audited pages; 214 detected regions/crops. B=1 and B=8 bucketed runs completed. | It is not a chapter benchmark or 76-page integrated result. |
| 4-page / 4-crop FP16 smoke | Four real pages/crops plus deterministic tensor probes; 8 FP16-vs-FP32 comparisons. | It is not full-manifest parity or Android validation. The attempted 76-page follow-up was stopped under resource pressure. |
| 1-fixture INT8/QDQ run | One real detector-mask fixture for conversion, load, latency, and parity. | It is not representative calibration, the 76-page manifest, or an accuracy benchmark. |
| 48 real crops + 4 controls REC batching | Production preprocessing and fixed-width B=1/2/4/8/16 host CPU comparison, plus tight dynamic-width exploratory mode. | It is not Android throughput or memory evidence. |
| 44-page/312-region and 70-page/455-region simulations | Discrete-event scheduling models and host control studies. | They are not measured Android timing, thermal, or memory. |

The fixed manifest audit is the strongest common coverage statement: 76/76
pages and 486/486 regions were readable in the recorded host environment. The
integrated timing scope remains 12 pages/214 DET-produced crops. Resource-
limited and interrupted runs remain visible and are not silently promoted to
successes.

## Model architecture and sizes

The shipped assets are valid ONNX graphs and are byte-identified in
[`research/findings/bootstrap.md`](../research/findings/bootstrap.md) and
[`research/findings/graph_analysis.md`](../research/findings/graph_analysis.md).

| Model | Role and Android contract | File size | Hash prefix | ONNX |
|---|---|---:|---|---|
| PP-OCRv6 Small DET | DB text-line map; Android fixes input to `[1,3,736,736]`, RGB NCHW, ImageNet normalization, DB thresholds 0.20/0.45 | 9,880,512 B | `d73e0058…9410e` | IR 10 / opset 14 |
| PP-OCRv6 Small REC | CTC recognizer; height 48, Android production buckets width to 640 or 1600, RGB NCHW, gray-128 padding | 21,159,378 B | `5435fd74…24634` | IR 6 / opset 11 |
| Existing CTD detector-v4-s | Page/region detector; fixed 640 input, `orig_target_sizes`, bubble/text classes | 11,120,765 B | `5fe9e4f5…1ea79` | existing app asset |

DET has 169 FLOAT initializers and 2,453,368 parameter elements. REC has 202
FLOAT plus 39 INT64 shape/index initializers and 5,267,732 parameter elements.
Both graph inputs/outputs are FLOAT. There are no Q/DQ nodes in either current
PP-OCRv6 graph. The PP-OCRv6 DET file is byte-identical to the pinned upstream
detector repository revision used by the bootstrap.

Graph I/O is dynamic in the serialized files: DET batch/height/width and REC
batch/width are symbolic. CPU smoke execution succeeded for DET 32x32 and
736x736, and REC B=1/B=8 with representative widths. Dynamic acceptance by
CPU is not accelerator compatibility.

## Operator analysis and portability

DET has 242 nodes: 83 `Conv`, 2 `ConvTranspose`, 39 `Mul`, 36 `Add`, 13
`Div`, 13 `Erf`, 13 `HardSigmoid`, 20 `Relu`, 6 `Resize`, pooling and one
`Sigmoid`. REC has 481 nodes: 57 `Conv`, 13 `MatMul`, 135 `Identity`, 103
`Add`, 43 `Mul`, 18 `Div`, dynamic shape/index operators (`Shape`, `Slice`,
`Squeeze`, `Unsqueeze`, `Reshape`, `Transpose`), 13 `Erf`, 5 `HardSigmoid`,
and three `Softmax` nodes.

The CPU EP executes both graphs. GPU/NPU support is not established on Android.
For QNN/HTP, dynamic dimensions are a first blocker; HTP normally needs a
quantized fixed-shape graph. `Erf`, `HardSigmoid`, DET `ConvTranspose`/`Resize`,
and REC's dynamic shape subgraph are partition risks. These are compatibility
risks, not measured QNN failures: there was no QNN SDK, QAIRT, adb device, or
QNN-enabled ORT in this worktree.

## CTD versus PP-OCRv6 DET

The 76-page host comparison is recorded in
[`research/findings/ctd_vs_ppocr.md`](../research/findings/ctd_vs_ppocr.md) and
[`research/results/ctd_vs_ppocr/summary.json`](../research/results/ctd_vs_ppocr/summary.json).

| Host CPU result | CTD / detector-v4 | PP-OCRv6 Small DET |
|---|---:|---:|
| Pages | 76/76 | 76/76 |
| Predicted boxes | 984 | 1,174 |
| TP / FP / FN at IoU 0.50 | 477 / 507 / 9 | 0 / 1,174 / 486 |
| Aggregate precision / recall at IoU 0.50 | 0.485 / 0.981 | 0 / 0 |
| Mean wall time/page | 6,880 ms | 3,274 ms |
| Model bytes | 11.12 MB | 9.88 MB |

PP-OCRv6 is about 2.10x faster and about 11% smaller in this host run, but the
strict metric compares region annotations with line boxes. At IoU >= 0.10,
PP-OCRv6 reaches 416 TP / 758 FP / 70 FN (precision 0.354, recall 0.856),
showing that compact line interiors are being found. These numbers are a
granularity/extent diagnostic, not detector accuracy or mAP. The corpus does
not label SFX, furigana, vertical/rotated text, noise, or low-resolution strata.

Decision: **do not replace CTD**. The defensible composition is CTD for page/
region recall, with PP-OCRv6 DET as a best-effort line splitter/refiner inside
localized ROIs. A replacement decision requires independently labeled,
category-stratified line and region polygons and end-to-end translation-quality
non-regression.

## Detector resolution

The corrected 76-page host DET run and the Wave-2 resolution adjudication are in
[`research/findings/det_benchmark.md`](../research/findings/det_benchmark.md),
[`research/findings/resolution_adjudication.md`](../research/findings/resolution_adjudication.md),
and [`research/results/det_benchmark.json`](../research/results/det_benchmark.json).
It is CPU-only, upstream DB postprocess with `unclip_ratio=1.4`, and its Studio
box comparison is explicitly only a resolution-quality proxy.

| max side | warm median total | pages/s | mean detections/page | Studio-box IoU50 proxy |
|---:|---:|---:|---:|---:|
| 640 | 1,934 ms | 0.517 | 14.47 | 0.165 |
| 768 | 2,532 ms | 0.395 | 15.47 | 0.155 |
| 960 | 3,247 ms | 0.308 | 18.29 | 0.102 |
| 1024 | 3,185 ms | 0.314 | 19.05 | 0.104 |
| 1280 | 3,214 ms | 0.311 | 21.54 | 0.069 |

All 380 warm records completed with zero errors. The resolution adjudication
recomputed the scored-page proxy correctly: four manifest pages have no
regions, so timing includes all 76 pages but proxy denominators use 72 scored
pages/486 regions. At IoU50, 640 has the best proxy ranking (88 matches,
pooled P/R 0.083/0.181) and is the fastest setting; 768 has 81 matches and
0.072/0.167, while 960/1024/1280 are slower, use more RSS, and have fewer
matches. The proxy is still region-vs-line granularity and must not be called
accuracy. The first detector run had 73 retained `TypeError` failures; it is a
failed attempt and is excluded from the corrected aggregate.

Wave-2 disposition: **640 is the current host working candidate for the
composed CTD->PP-OCR line-refinement path**, not a CTD replacement and not an
Android default. Keep the production 736 contract as the first Android
baseline; test 640 as a reduced-resolution A/B with line-level and end-to-end
quality gates. True line recall, missing text categories, and Android behavior
remain untested.

## Recognition accuracy and batching

### FP32 baseline and production preprocessing

The Android-shaped REC path uses height 48, ceil aspect-ratio resize, 640/1600
width buckets capped at 1600, RGB NCHW, `(x/255 - 0.5)/0.5` normalization,
gray-128 padding, vertical-line rotation, and CTC argmax/repeat-collapse/blank
removal. The 48-real-crop plus four-control batching run and its Wave-2
adjudication are documented in
[`research/findings/rec_batching.md`](../research/findings/rec_batching.md)
and [`research/findings/batching_adjudication.md`](../research/findings/batching_adjudication.md).

### Fixed-width batching

The pristine CPU graph accepted B=1/2/4/8/16. Bucketed B=2/4/8/16 had zero
text and token mismatches against B=1 across 240 real comparisons; controls were
also parity-clean. Aggregate host throughput was 1.354 crops/s at B=1,
1.405 at B=4, 1.616 at B=8, and 1.717 at B=16. Peak RSS deltas were 6.9,
50.0, 54.3, and 57.9 MiB respectively in the sampled host process. B=16 is
outside the declared TensorRT profile's maximum batch of 8 and is not an
Android recommendation.

### Tight dynamic-width batching — FAILED

The exploratory mode padded each batch only to its maximum dynamic width. It
caused 85 text mismatches, 137 token mismatches, and 135 output-shape mismatches
across 240 real comparisons. The model's time axis/logits depend on the batch
width, so a crop's result changes when another crop is wider. This is a direct
quality regression, not an acceptable padding trade-off. Do not use tight mode
without a new graph/export and a new parity gate.

### Bounded integrated DET -> REC

The completed integrated artifacts are
[`integrated_pipeline.md`](../research/findings/integrated_pipeline.md),
[`integrated_b1_bucketed/integrated_pipeline.json`](../research/results/integrated_b1_bucketed/integrated_pipeline.json),
and [`integrated_b8_bucketed/integrated_pipeline.json`](../research/results/integrated_b8_bucketed/integrated_pipeline.json).
They audit all 76 pages but time only the first 12 pages and 214 actual DET
regions. B=1 measured 1.31 regions/s; B=8 measured 7.46 regions/s (5.7x in
this bounded host CPU probe), with exact integrated-vs-independent B=1 parity
for all 214 crops. B=8 peak per-page RSS delta was 229.61 MiB versus 47.95 MiB
at B=1. These are host results, not device capacity claims.

The 76-page sweep reached approximately 6.0 GB committed with approximately
1.1 GB free and was stopped before a complete configuration result. The B=4
follow-up was also stopped during host memory contention. Both are
`FAILED_RESOURCE_LIMIT`, not speed results. Earlier smoke artifacts that claim
chapter-scale integration are not valid chapter benchmarks unless they carry
the 12-page/214-crop or full-corpus scope above.

## CPU findings

The canonical CPU thread sweep is in
[`research/findings/cpu.md`](../research/findings/cpu.md). On the Windows host,
four intra-op threads gave the best DET p50 (1,166 ms) and two threads gave the
best REC p50 (477 ms) in the two-sample sweep. Eight threads regressed versus
four on both components. Parallel/inter-op-2 settings were directional only
(one warm sample per setting); I/O binding was a negative result. Arena-off
reduced observed RSS but slowed DET. The conservative Android starting point
is sequential graph-all, arena-on, memory-pattern-on, four intra-op threads,
then a separately measured REC-specific policy if recognition dominates.

These are host CPU observations. They do not establish ARM speed, big.LITTLE
thread placement, thermal behavior, or Android RSS.

## OpenVINO CPU/GPU/NPU

OpenVINO evidence is Intel laptop evidence only, in
[`research/findings/openvino.md`](../research/findings/openvino.md) and
[`research/results/openvino_ppocrv6.json`](../research/results/openvino_ppocrv6.json).
The host had Intel CPU and Iris Xe GPU. Direct OpenVINO CPU and GPU compilation
reported zero unsupported ops. Warm DET was 1,741 ms CPU versus 1,016 ms GPU;
warm REC was 754 ms CPU versus 202 ms GPU in the small real-crop run. GPU DET
max absolute difference from ORT was 0.0478 and GPU REC 0.0277, while sampled
boxes/text matched. The connected GPU-DET/CPU-REC route was 6,995 ms for two
iterations versus 19,603 ms CPU/CPU.

OpenVINO `AUTO` compiled and ran on `(CPU)` in this build; it is not evidence of
GPU selection. OpenVINO NPU compilation failed with Intel's `0x78000004 -
[NPU_VCL] Unrecognized device ID!`. That failure says nothing about a
Qualcomm NPU. None of these measurements is Android, ARM, Snapdragon GPU, or
HTP evidence.

## FP16

FP16 conversion and load are **CONFIRMED on host**. DET and REC files are about
49.7–49.8% of FP32 storage. ORT BASIC and OpenVINO load/inference succeeded;
ORT `ORT_ENABLE_ALL` failed for converted REC because of a missing
`InsertedPrecisionFreeCast...` optimizer input, so the timing run used BASIC.

REC CTC sequence parity was exact on the four real crops and deterministic
probes. DET was stable on the four real pages, but a deterministic probe changed
40 FP32 boxes to 44/43 boxes, with mean best-match IoU 0.9382 and one unmatched
box IoU 0.00016. Host timing was backend-dependent: ORT FP16 helped DET but
slowed REC; OpenVINO FP16 helped in different model/I/O combinations. RSS did
not decrease in the short host run. Full 76-page parity, Android ARM latency,
PSS, thermal behavior, and end-to-end DB/crop-order parity are untested.

The Wave-2 precision adjudication is in
[`research/findings/precision_adjudication.md`](../research/findings/precision_adjudication.md).
Decision: DET FP16 is **HOLD / not production-approved** with a known host
counterexample; REC FP16 is **CONDITIONAL / keep FP32 by default**. Neither is
a production default from this report.

## INT8 and QDQ

The bounded host conversion run used one real detector-mask fixture, not the
76-page manifest. Dynamic per-channel, static QOperator, and static QDQ
variants converted and loaded on ORT CPU and shrank to roughly 26–27% of FP32
bytes. On that one fixture every completed REC variant changed the decoded
sequence/text. Every completed DET variant changed one FP32 box to zero boxes;
DET probability-map max absolute differences were approximately 0.975–0.979.

Therefore INT8/QDQ DET and REC are **FAILED for the bounded accuracy gate** and
**UNTESTED for Android/QNN compatibility**. Do not infer that every possible
calibration or QAT model will fail; representative calibration, layer
exclusions/QAT, full-corpus parity, and QNN fixed-shape testing remain required.
Raw artifacts are [`int8_qdq.md`](../research/findings/int8_qdq.md),
[`int8_results.json`](../research/results/int8_results.json), and
[`qdq_results.json`](../research/results/qdq_results.json).

## QNN / HTP

Current PP-OCRv6 DET/REC are **UNSUPPORTED / UNTESTED** for HTP in the precise
sense supported by this evidence: the graphs are dynamic FP32 with no Q/DQ,
and no Qualcomm runtime/device was present. This is not a compile failure and
not a claim that every QNN version rejects them. Fixed-shape rewrites remove a
shape blocker but do not create an HTP-ready model; representative QDQ export
and no-fallback device validation are still needed.

The dry-run/validation procedure and exact runner shape staging are in
[`research/findings/qnn_htp.md`](../research/findings/qnn_htp.md),
[`research/validate_qnn_android.sh`](../research/validate_qnn_android.sh), and
[`research/qnn_compatibility_raw.json`](../research/qnn_compatibility_raw.json).
Successful provider registration or host model loading is not QNN execution
evidence.

## Memory and Android preprocessing

The source-level memory audit is in
[`research/findings/android_memory.md`](../research/findings/android_memory.md).
The largest caller-owned PP-OCRv6 buffer is a detector FP32 direct input:
`3*736*736*4 = 6,500,352 B` (6.20 MiB) per buffer, with a two-entry pool
capacity of 12.40 MiB. REC max-width input is 0.88 MiB per buffer, 1.76 MiB
for two. A one-call DET arithmetic estimate, excluding source crop and ORT
workspace, is about 16.55 MiB including padded/resized ARGB, scratch, output,
and direct input. Retained caller pools total about 23.54 MiB before ORT arenas,
model mappings, page bitmaps, and provider working memory.

The page OCR path holds `nativeGuard` over detection/segmentation/ROI OCR,
reuses long-lived sessions, closes tensors/results in `finally`, and recycles
one crop at a time on the Paddle path. This is a sound stability baseline.
The default non-horizontal batch interface can materialize a page crop list,
so any future batching must cap both crop count and bytes.

These are source arithmetic and lifetime findings, not Android PSS/native-heap
measurements. Direct-buffer lifetime is conservatively held through tensor and
result close, but ORT/provider hidden copies are unmeasured. Keep one in-flight
page and one in-flight ROI per engine until a target-device memory gate passes.

Preprocessing must remain contract-exact: Android ARGB channel extraction to
RGB, no BGR staging, detector ImageNet mean/std, REC `[-1,1]` normalization,
black detector padding, gray-128 recognition padding, vertical rotation, and
bounded 640/1600 width buckets. Any native crop-to-tensor rewrite needs golden
parity tests for interpolation, padding, channel order, rotation, and crop
coordinates.

## Pipeline concurrency

The host discrete-event model in [`research/findings/pipeline.md`](../research/findings/pipeline.md)
shows the direction of overlap: serial 12.271 s versus modeled overlapped,
width-bucketed REC 3.319 s over its 44-page/312-region modeled scope. Queues
reached configured high-water marks and backpressure was exercised. Cancellation,
checkpoint, resume, priority, and CPU fallback are represented in the model.

The model uses synthetic stage costs and a synthetic 39 °C thermal threshold.
It is not Android timing or thermal evidence. Keep manual/current-page work at
B=1 and high priority; allow bounded background coalescing only after measured
device limits. Decode -> DET -> crop -> REC overlap is **LIKELY architecturally
useful**, but production queue sizes and batch caps are **UNTESTED**.

## Decision table

`Speed` is deliberately scoped: host numbers are not Android numbers. `Accuracy`
means parity/proxy evidence, not an unsupported ground-truth claim.

| Candidate | Status | Evidence | Speed | Accuracy | Difficulty | Blocker |
|---|---|---|---|---|---|---|
| PP-OCRv6 DET replacement for CTD | FAILED for replacement / LIKELY useful as ROI refiner | 76-page host comparison; CTD 477/486 strict TP vs PP 0/486 under region-vs-line protocol | PP host ~2.10x faster | Granularity mismatch; no replacement-quality proof | Medium | independent line/region labels and end-to-end categories |
| DET FP16 | UNTESTED | Precision adjudication: hold/not production-approved; conversion/load works, 4-page smoke stable, deterministic probe 40 -> 44/43 boxes | Host ORT/OpenVINO backend-dependent | Known box-drift counterexample; full corpus/device absent | Medium | full DB parity, Android PSS/latency/thermal |
| DET INT8 | FAILED bounded accuracy / UNTESTED Android | One-fixture dynamic/QOperator/QDQ all changed one box to zero | Host timing not cleanly rankable | Strong negative fixture result; not representative calibration | High | representative calibration/QAT and device validation |
| REC FP16 | LIKELY | Precision adjudication: conditional, keep FP32 by default; 4 real crops + probes exact CTC parity | ORT host slower; OpenVINO mix-dependent | Limited exact sequence parity only | Medium | full crop parity and Android runtime |
| REC INT8 | FAILED bounded accuracy / UNTESTED Android | All completed variants changed the one-fixture text | Host QOperator/QDQ faster than dynamic, but not a clean baseline | Text mismatch for every completed variant | High | representative calibration/QAT and full parity |
| REC batching | CONFIRMED host for fixed buckets; UNTESTED Android | B=2/4/8/16 bucketed: zero text/token mismatches on 240 real comparisons | 1.354 crops/s B=1 vs 1.616 B=8 host; integrated B=8 5.7x bounded | Bucketed parity clean; tight mode failed | Medium | Android PSS/thermal/parity gate; B=16 outside TRT profile |
| GPU DET/REC | CONFIRMED Intel-host only / UNTESTED Snapdragon | OpenVINO GPU compiled and ran on Iris Xe; sampled text/boxes matched | GPU DET 1,016 ms, REC 202 ms warm host | Non-bit-identical outputs; tiny sample | High | Qualcomm/Android backend and end-to-end parity |
| NPU DET/REC | FAILED Intel NPU compile / UNTESTED Qualcomm NPU | Intel NPU failed unknown device ID | None | No inference | High | target NPU runtime/device; Intel failure is not Qualcomm evidence |
| QNN HTP | UNTESTED (current graphs have likely unsupported shape/dtype/ops) | No SDK/device; current graphs dynamic FP32/no QDQ | None measured | None measured | Very high | fixed-shape QDQ, no-fallback compile/run, QAIRT/SoC |
| Reduced DET resolution | LIKELY host candidate / UNTESTED device-quality | Wave-2 adjudication selects 640 for the composed ROI-refinement experiment; proxy is not ground truth | 640 host fastest (1.934 s median) | Best available region proxy, but line quality/device quality unknown | Medium | Android latency/memory plus labeled line/region quality |
| REC width bucketing | CONFIRMED host / UNTESTED device | Production 640/1600 preprocessing and parity-clean bucketed batches | Avoids tight-mode drift; host batching benefit | Exact sampled parity | Low/medium | Android byte/PSS cap and crop-order tests |
| Pipeline overlap | LIKELY direction / UNTESTED Android | Discrete-event model: 3.70x modeled speedup; cancellation/resume modeled | Not a measured speed claim | No model accuracy effect measured | High | real device timings, bounded queues, thermal/backpressure |

## Recommended Android architecture

Implement the smallest device-gated path consistent with stability:

- Keep CTD as the first-stage page detector. Invoke PP-OCRv6 DET only inside a
  localized ROI or as a best-effort line refinement; preserve the existing
  fallback when the asset, provider, or per-ROI call fails.
- Use one reused ORT environment/session per model and the existing direct
  buffer/bitmap pool contracts. Do not pool mutable `OnnxTensor` wrappers until
  synchronous ownership is proven on the target EP.
- Start at DET 736x736, REC B=1, sequential CPU, graph optimization `all`,
  arena on, memory pattern on, four intra-op threads. A/B REC two-thread and
  accelerator candidates only with instrumentation.
- Use fixed 640/1600 REC buckets. Manual/current-page requests bypass waiting
  for a batch. Release B=1 first; stage B=4 as the first device candidate and
  B=8 as the second only if PSS, thermal slope, cancellation, and exact
  decoded-text parity pass. B=4's interrupted host run is not evidence that it
  is safer than B=8.
- Bound queues by both item count and bytes. Begin with one page and one ROI
  in flight; permit overlap only after device measurements. On critical memory
  trim, stop admission, finish at a safe guard boundary, clear discretionary
  pools, and requeue safely.
- Keep FP32 CPU fallback always available. Do not silently label provider
  registration as acceleration; record actual execution provider/device and
  fallback reason per run.

## Exact Snapdragon tests remaining

The following is the minimum reproducible Snapdragon gate. It is intentionally
specific enough to distinguish configuration failure, compile failure, CPU
fallback, numerical drift, and useful acceleration.

### 1. Device/runtime inventory

On at least one 6-GB-class Snapdragon Android 8+ device and one modern
Snapdragon target, record SoC, Android API, ABI, RAM, thermal mode, QAIRT/QNN
version, ORT build, and all registered providers. Capture `adb shell getprop`,
`dumpsys meminfo`, and provider initialization logs. No result is valid without
the exact device/runtime tuple.

### 2. CPU reference on-device

Run the current FP32 Android-shaped path with CPU only for the same fixed IDs:

- DET `[1,3,736,736]` and, only as an explicitly separate resolution test,
  `[1,3,640,640]` and `[1,3,960,960]` if memory permits;
- REC `[1,3,48,640]`, `[1,3,48,1600]`, then B=2/4/8 fixed-width batches;
- cold session, five warm repetitions, 12-page/214-crop bounded replay, and
  a memory-pressure/cancellation replay.

Record per-stage wall time, p50/p90/p95, Java heap, native heap, PSS/RSS,
allocator deltas, peak in-flight crop bytes, thermal/clock/throttling samples,
and exact decoded text/token/box parity against the B=1 CPU reference.

### 3. QNN current-graph no-fallback probe

Use `research/validate_qnn_android.sh` to stage fixed-shape fixtures and run
the current FP32 models with `session.disable_cpu_ep_fallback=1` and detailed
HTP profiling. Test DET B=1 at 640x640 and 736x736; test REC B=1 at widths
640/1600. Record model-load, graph-compile, provider-configuration, and
runtime errors separately. A successful run must show actual HTP execution in
the profile; a provider registration alone is insufficient.

### 4. Fixed-shape QDQ HTP candidate

Only after representative calibration is available, generate fixed-shape QDQ
DET/REC variants with calibration covering bubble/free text, vertical/tall
lines, small text, low contrast, and the full selected manifest—not the single
mask fixture. Test per-tensor and per-channel candidates as applicable; retain
FP32 CPU as reference. Run every intended signature with CPU fallback disabled:

- DET B=1 at 640x640 and 736x736;
- REC B=1/B=2/B=4/B=8 at 640 and 1600 widths;
- any reduced-resolution or alternate width signature proposed for shipping.

Compare DB postprocessed boxes and CTC text/tokens to CPU on all selected
pages/crops, with explicit tolerances and mismatch counts. Reject any model
that silently partitions to CPU, changes text, drops boxes, or exceeds the
memory/thermal budget.

### 5. Provider and pipeline gate

For CPU, NNAPI/GPU if available, and QNN/HTP candidates, run CTD -> PP DET
refinement -> REC on the bounded 12-page/214-crop scope, then a larger
representative subset. Measure B=1, B=4, and B=8 fixed buckets, page-order and
crop-order stability, cancellation/resume, background overlap, queue
high-water marks, and manual-page preemption. Replace the synthetic pipeline
timings and 39 °C threshold with measured device traces before selecting queue
caps.

### 6. Promotion gates

Promote only a configuration that passes all of: zero unexplained provider
fallback; exact or explicitly approved output parity; no CTD region-recall or
translation-quality regression in stratified labels; bounded PSS/native heap on
the 6-GB target; acceptable sustained thermal slope; cancellation/recovery;
and a demonstrated end-to-end latency improvement over the FP32 CPU baseline.

## Final recommendation (release boundary)

Keep CTD upstream and keep the production PP-OCRv6 DET input at 736x736 until
an Android A/B proves that 640 preserves line/refinement and translation
quality. Treat 640 only as the next host-informed experiment. Release REC with
fixed width buckets and B=1; stage B=4 first and B=8 second behind the device
parity, memory, thermal, lifecycle, and stability gates. Continue to ship the
FP32 CPU fallback and do not enable FP16, INT8/QDQ, GPU/NPU, or QNN/HTP by
default from this evidence.

## Raw artifacts and commands

Primary findings: [`bootstrap.md`](../research/findings/bootstrap.md),
[`graph_analysis.md`](../research/findings/graph_analysis.md),
[`det_benchmark.md`](../research/findings/det_benchmark.md),
[`ctd_vs_ppocr.md`](../research/findings/ctd_vs_ppocr.md),
[`rec_batching.md`](../research/findings/rec_batching.md),
[`cpu.md`](../research/findings/cpu.md),
[`fp16.md`](../research/findings/fp16.md),
[`int8_qdq.md`](../research/findings/int8_qdq.md),
[`openvino.md`](../research/findings/openvino.md),
[`qnn_htp.md`](../research/findings/qnn_htp.md),
[`android_memory.md`](../research/findings/android_memory.md),
[`integrated_pipeline.md`](../research/findings/integrated_pipeline.md), and
[`pipeline.md`](../research/findings/pipeline.md).

Representative reproduction commands (PowerShell):

```powershell
python research/benchmark_det.py --manifest research/dataset/manifest.json --output-dir research/results/det_benchmark
python research/compare_ctd_ppocr.py --manifest research/dataset/manifest.json --out research/results/ctd_vs_ppocr
python research/benchmark_rec_batching.py `
  --dataset-manifest research/dataset/manifest.json `
  --batch-sizes 1,2,4,8,16 --width-modes bucketed,tight --max-crops 48 `
  --output-dir research/findings/rec_batching
python research/test_fp16.py --help
python research/test_int8.py --help
python research/test_qdq.py --help
python research/benchmark_openvino.py --help
python research/benchmark_cpu.py --help
```

The QNN runner procedure is intentionally dry-run by default:

```sh
RUN=1 bash research/validate_qnn_android.sh
```

The exact raw JSON/CSV paths linked above are authoritative. The final Wave-2
adjudications are included in this report and remain available for audit:
[`resolution_adjudication.md`](../research/findings/resolution_adjudication.md),
[`precision_adjudication.md`](../research/findings/precision_adjudication.md),
and [`batching_adjudication.md`](../research/findings/batching_adjudication.md).
Their scope is preserved: they are host evidence and do not replace the
required Android/QNN validation gates.
