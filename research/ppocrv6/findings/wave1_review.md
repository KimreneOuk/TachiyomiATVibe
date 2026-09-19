# PP-OCRv6 Wave-1 evidence review

Date: 2026-09-18  
Scope: bootstrap, graph inventory, detector benchmark, REC batching/smoke, CPU,
OpenVINO/FP16, INT8/QDQ, QNN, CTD comparison, integrated pipeline, scheduling,
and Android-memory artifacts in this worktree.

## Executive verdict

The Wave-1 work establishes a useful host baseline, but it is not yet a
device-acceleration or production-default decision. The strongest evidence is:

- the checked-in PP-OCRv6 graphs are valid, byte-identified, and are **DET
  opset 14 / REC opset 11** with dynamic dimensions;
- the fixed manifest currently resolves **76/76 pages and 486/486 regions** on
  this Windows host for the detector, CTD comparison, and REC-batching audit;
- fixed production-width REC batching is parity-clean on the host for the
  sampled 48 real crops plus four controls, while the exploratory dynamic-width
  batching is not parity-clean;
- CTD has better strict region-level recall on the current labels, but the
  comparison is a granularity mismatch and does not decide line detection;
- all Android/NPU/QNN claims remain untested, and the scheduling/memory results
  are models or source arithmetic rather than Android measurements.

The report prose has several stale or over-broad references. In particular,
the first detector run contains 73 retained `TypeError` failures, the current
INT8/QDQ raw result files are absent, and the integrated pipeline artifacts are
one-page smoke runs rather than a chapter benchmark.

## Evidence grades

`A` = direct raw result plus independent cross-check; `B` = reproducible host
measurement with a bounded corpus/protocol; `C` = model, source arithmetic, or
inference; `D` = blocked, stale, or incomplete artifact. Host `A/B` evidence is
not Android performance evidence.

## Claims needing correction or qualification

| ID | Claim or implied conclusion | Evidence and correction | Grade | Exact follow-up experiment |
|---|---|---|---|---|
| W1-01 | DET/REC opsets were described as 19/11 in an earlier handoff. | Direct `onnx.ModelProto` read, `graph_analysis`, and `qnn_compatibility_raw.json` agree: DET IR10/opset14; REC IR6/opset11. Keep the 19/11 statement out of later reports. | A | Add a small manifest-time assertion that records IR/opset/hash beside every benchmark result. |
| W1-02 | The fixed corpus is only 44 pages / 312 regions. | `dataset/manifest.json`, detector benchmark, CTD comparison, and REC audit all resolve 76/76 pages and 486/486 regions. `pipeline_simulation.json` is an intentional 44-page model subset (312 regions); `_70.json` is a separate 70-page model (455 regions). `android_memory.md`'s 64-page/447-region reference is stale. | A for coverage; C for scheduling subsets | Regenerate scheduling inputs from the 76-page manifest and record `requested`, `audited`, `selected`, and `modeled` counts in every output. |
| W1-03 | Detector benchmark is a clean 76-page result. | `det_benchmark_20260918T044157Z.{json,csv}` has 73 `TypeError('cannot unpack non-iterable numpy.float32 object')` rows and only 4 successes. The later `04:57` files and `det_benchmark.{json,csv}` are 76/76 `ok`; use only the latter and retain the failed run as a failed attempt. | A for failure audit; B for corrected run | Re-run from a fresh output directory with a run ID and assert `failed_pages == 0` before emitting summary statistics. |
| W1-04 | Detector timing is an Android-shaped PP-OCR timing. | Latest host detector median is 3,196 ms/page (76 warm pages), CPU-only. `benchmark_det.py` uses the pinned upstream graph and DB `unclip_ratio=1.4`; the Kotlin path documents raw boxes without unclip. This is not a direct Android contract. | B, protocol-qualified | Benchmark both postprocess modes with the exact Kotlin-equivalent path, then measure on Android CPU/NNAPI/QNN with provider provenance. |
| W1-05 | CTD vs PP-OCR strict metrics establish that PP-OCR cannot detect text. | Raw CTD/PP run covers 76 pages, but truth is region-level `ocr_box` while PP-OCR emits line-level boxes. PP-OCR's 0/486 strict TP is therefore a granularity/extent failure, not a no-text result; lower IoU gives 416 TP/486 at 0.10. | A for protocol; C for replacement decision | Create line-level and region-level labels on a stratified subset (bubble, free text, vertical, furigana, SFX/noise), score CTD, PP-OCR, and the composed CTD→PP path. |
| W1-06 | REC batching has one parity result. | The full raw result contains 480 real comparisons and 40 fixture comparisons. Bucketed B=2/4/8/16 has zero text/token mismatches; tight mode has 85 text, 137 token, and 135 output-shape mismatches on real crops. The prose's 48-real + 4-control sample is accurate; aggregate parity must keep bucketed and tight separated. | A/B | On-device, repeat bucketed B=1/2/4/8 with all crops in a fixed 76-page sample; record decoded text/token parity, PSS/native heap, and thermal slope. Do not promote tight mode. |
| W1-07 | B=4/B=8 is a deployment recommendation. | It is only a host CPU recommendation from 48 real crops plus four controls. B=16 is CPU-loadable but outside the model's declared TensorRT batch-max profile of 8. No Android provider or memory evidence exists. | B for host direction; D for deployment | Gate each batch size on Android parity, peak PSS, sustained latency, and cancellation behavior; default to B=1 until a device gate passes. |
| W1-08 | The integrated pipeline demonstrates chapter-level DET→REC batching. | `pipeline_smoke2` and `pipeline_smoke3` metadata both say `available_pages: 1`, `requested_source_ids: downloaded`; each is one page with 18 detected crops. Smoke2 tight mode has 12/18 text and token mismatches; smoke3 is bucketed-only with parity sampled on only 2 crops. | B for one-page smoke; D for chapter claim | Run integrated DET→REC on all 76 pages (or explicitly selected source subsets), retain all-crop parity, and publish warm/cold summaries per configuration. |
| W1-09 | CPU option sweep selects a production thread/provider policy. | `cpu.md` correctly identifies the canonical thread sweep and the final option sweep as directional. `cpu_options_final` has one warm sample per configuration; `cpu_threads` has two warm samples per thread. The old `results/cpu` run has false warm parity rows and must not be treated as the canonical sweep. | B for direction; C for policy | Repeat 5–10 warm samples per candidate on the same host, then A/B the candidates on representative Android devices with PSS and thermal traces. |
| W1-10 | FP16 conversion/runtime evidence is complete in `fp16-results`. | `fp16-results/results.json` is marked `CONFIRMED` and has 4 pages/4 crops, but `conversion` is empty (models were reused with `--skip-convert`). Its synthetic runtime plus limited real parity rows are useful, not a conversion audit. `fp16-results-openvino/results.json` records conversion and OpenVINO CPU runtime, but has zero real pages/crops. | B for limited host parity; D for complete conversion/real-corpus claim | Re-run ORT and OpenVINO into fresh directories without `--skip-convert`, record conversion errors/hashes, and run at least 76 pages plus a documented crop sample. |
| W1-11 | OpenVINO is an acceleration candidate. | No `results/openvino_ppocrv6.json` or `findings/openvino.md` is present. The available FP16 OpenVINO artifact is a host CPU backend run; it contains no Android/ARM/NPU result and no real-image parity. | B for host conversion/runtime; D for device acceleration | Run a dedicated OpenVINO report with device inventory, execution-device metadata, real-crop parity, and separate CPU/GPU/NPU rows; keep it explicitly host-only. |
| W1-12 | INT8/QDQ results are available for comparison. | `findings/int8_qdq.md` explicitly says “in progress,” but `research/results/int8_results.{json,csv}` and `qdq_results.{json,csv}` are absent. Cache contains dynamic/qoperator files, but no QDQ files and no complete measurement record. | D | Complete fresh dynamic, QOperator, and QDQ conversions; emit raw results even on conversion/load failure, check all graphs, and report per-model parity before any recommendation. |
| W1-13 | Current PP-OCRv6 files can be routed to QNN/HTP after shape work. | QNN evidence is an offline inventory only: no SDK, device, `adb`, QNN EP, or HTP compiler. Current DET/REC are dynamic FP32 with no Q/DQ. “UNSUPPORTED/UNTESTED” is an appropriate risk classification, not a compile result. | D | Produce fixed-shape DET/REC QDQ variants, run QNN with CPU fallback disabled for every intended signature, capture compile/load/runtime errors separately, and compare outputs to CPU. |
| W1-14 | Scheduling speedups and thermal thresholds are measured. | `pipeline.md` correctly labels Android timings as modeled. Raw 44-page model: serial 12.271 s vs bucketed 3.319 s; raw 70-page model: 18.558 s vs 4.991 s. The host timing JSON is CPU-only and uses a different shape set. Thermal 39 °C and NNAPI fallback are synthetic control-flow values. | A for model reproducibility; C for Android behavior | Feed measured per-stage Android timings, queue high-water marks, PSS, thermal samples, and provider status into the simulator, then rerun 76-page serial/bucketed/naive/cancel-resume comparisons. |
| W1-15 | Android memory estimates establish safe caps. | `android_memory.md`'s byte arithmetic and source lifetime audit are useful; no Android PSS/native-heap/ORT allocator trace exists. Its 64/447 simulation reference is stale relative to the raw 44/312 and 70/455 artifacts. | B for source arithmetic; C/D for device caps | Measure Java heap, native heap, PSS/RSS, allocator deltas, and thermal slope for one page, 6–12 ROIs, and bucketed B=1/2/4 on 6 GB-class devices. |

## Confirmed cross-artifact facts

| Area | Current defensible statement |
|---|---|
| Model identity | In-app DET is 9,880,512 bytes, SHA-256 `d73e0058…9410e`; REC is 21,159,378 bytes, SHA-256 `5435fd74…24634`; direct ONNX checks pass. |
| Runtime inventory | The host exposes only `CPUExecutionProvider` and `AzureExecutionProvider`; no CUDA, NNAPI, QNN, vendor NPU, or Android device was available. |
| Corpus | The manifest has 3 chapters, 76 pages, and 486 annotated regions; all are readable from the current host's recorded external paths. This does not guarantee portability to another machine. |
| REC bucketing | Production 640/1600-width bucketing is parity-clean in the sampled host run. Dynamic tight-width batching is not safe under the current graph/preprocess contract. |
| Memory | Current source serializes native OCR work and closes tensors/results per call; estimated pool/scratch sizes are not measurements of ORT native arenas or provider working sets. |

## Proposed Wave-2 plan

### W2-0 — Reproducibility and artifact hygiene

1. Establish one canonical output directory per experiment and record git
   revision, model/dictionary hashes, command line, host/provider inventory,
   selected pages/regions, and failure counts.
2. Regenerate stale summaries from raw rows. Keep failed detector and absent
   INT8/QDQ runs visible, but exclude them from success aggregates.
3. Add machine-readable coverage fields: `manifest_pages`, `audited_pages`,
   `selected_pages`, `selected_regions`, and `skipped_reason`.

### W2-1 — Android baseline and memory gate

Run the existing FP32 DET/REC path on representative Android 8+ devices,
including a 6 GB-class baseline. For CPU, NNAPI, and QNN where available,
record provider execution provenance, cold/warm stage times, PSS, Java/native
heap, peak RSS, allocator deltas, and thermal samples. Use the same fixed
page/crop IDs and retain failures.

### W2-2 — Fidelity and detector composition

Build the stratified line/region evaluation described in W1-05. Keep CTD as
the page-level detector unless the composed CTD→PP-OCR line path demonstrates
non-regression for region recall and translation-relevant categories. Validate
the exact Kotlin no-unclip and merge behavior separately from the upstream DB
benchmark.

### W2-3 — REC batching decision

Measure only fixed production buckets on-device at B=1/2/4/8. Require zero
decoded-text mismatches against B=1 on the selected corpus, a documented peak
PSS budget, and acceptable sustained thermal slope. Keep dynamic tight mode
rejected unless a new graph/export and masking strategy passes the same gate.

### W2-4 — Precision and accelerator tracks

Complete FP16, QOperator, and QDQ host conversion/parity matrices in clean
output directories. Then create fixed-shape QDQ variants for QNN/HTP, test with
CPU fallback disabled, and retain the FP32 CPU path as the normal fallback.
Treat successful host load or provider registration as insufficient without a
successful target-device run.

### W2-5 — Scheduling and memory model update

Replace modeled stage budgets with W2-1 measurements, rerun the full 76-page
manifest and cancellation/resume cases, and set queue/batch caps only after
the W2-3 memory/thermal gate. Keep manual/current-page work at B=1 unless the
device data proves coalescing safe.

## Sanity checks performed

- `python -m py_compile` passed for all research harnesses and manifest/QNN
  scripts.
- All available JSON artifacts and CSV files parsed successfully with no
  malformed CSV row widths.
- Direct ONNX checker passed for the four FP16 variants and all five cached
  dynamic/QOperator quantized models. ORT CPU session loading passed for those
  five quantized models; this is not QNN evidence.
- Manifest totals and external file audit independently reproduced 76 pages,
  486 regions, and zero missing paths on this host.

## Bottom line

Wave-1 supports a host-only baseline and a cautious direction: preserve the
current FP32 CPU fallback, use fixed-width REC buckets, and investigate
device-specific acceleration with instrumentation. It does not support a
production QNN/NNAPI route, a chapter-level batching speed claim, an INT8/QDQ
recommendation, or a CTD replacement decision yet.
