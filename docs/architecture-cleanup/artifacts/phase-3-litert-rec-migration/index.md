---
title: "Phase 3: Manga-Specific PP-OCRv6 LiteRT Recognition Migration"
kind: ticket
status: 2
assignee: ""
comments: none
---

# Phase 3: Manga-Specific PP-OCRv6 LiteRT Recognition Migration

## Overview

Convert the fine-tuned SVTR manga recognition model (`manga_rec_v0.2_fp16.onnx`) to Google LiteRT FlatBuffers (`.tflite`) and integrate it into the Android vision pipeline, targeting Adreno 750 OpenCL GPU acceleration and ARM NEON XNNPACK CPU execution.

## Critical Technical Contracts (from Phase 1 Feasibility Study)

1. **GELU Fusion (Zero `Erf` Custom Op Fallback)**:
  - The original graph contains 13 `Erf` nodes from decomposed GELU operations (`p2o.pd_op.gelu.*.*`).
  - Raw conversion creates `FlexErf`, causing GPU delegate rejection or 13 CPU-GPU partition context switches (+65ms to +195ms latency penalty per crop).
  - **Requirement**: Fuse `Erf` subgraphs to native `BuiltinOperator_GELU` or exact tanh approximation to achieve a **100% monolithic GPU graph with zero CPU fallbacks**.
2. **Tensor Layout & Memory Synergy (NHWC)**:
  - Export convolution weights transposed offline to `NHWC` (`[1, 48, W, 3]`).
  - Android `Bitmap` pixels feed directly into NHWC float buffers, eliminating costly CPU NCHW planar transposition loops.
3. **Discrete Static Width Bucketing**:
  - LiteRT GPU delegate requires static tensor shapes.
  - Export 5 discrete static width buckets:
    - `160 px` (aspect ratio $\le 3.33$)
    - `320 px` (aspect ratio $\le 6.67$)
    - `480 px` (aspect ratio $\le 10.0$)
    - `640 px` (aspect ratio $\le 13.3$)
    - `1600 px` (aspect ratio $\le 33.3$)
4. **Parity Tolerance Contract (Zero Regression)**:
  - Numerical Parity: $L_\infty \le 0.05$ (FP16), $\text{MSE} \le 1.0 \times 10^{-4}$, Cosine Similarity $\ge 0.9999$ on golden tensors.
  - Decoded Text: **$\text{CER} = 0.0%$ (Zero Divergence)** on clean reference crops.

## Experiment 1 — Erf Pseudo-Operator Polynomial Candidate (2026-10-13)

### 1. Hypothesis & Expected Mechanism

Use `onnxslim` to fix static input shape (`[1, 3, 48, 640]`) and `onnx2tf -rtpo erf` (replace to pseudo-operator polynomial approximation: $0.5 \cdot x \cdot (1 + \text{sign}(x)(1 - \sum a_i t^i e^{-x^2}))$) to eliminate `FlexErf` without requiring select-tf-ops, testing whether polynomial Erf maintains recognition accuracy.

### 2. Measured Results vs Tolerance Gate

Evaluated via `audit/baseline_ocr/compare_litert.py` across the Phase 1 golden NPZ crops:

| Metric | Target Tolerance Gate | Measured FP16 (`manga_rec_640_fp16.tflite`) | Measured FP32 (`manga_rec_640_fp32.tflite`) | Verdict |
| --- | --- | --- | --- | --- |
| **Max $L_\\infty$** | $\\le 0.05$ | **0.8567** | **0.8565** | **FAIL** |
| **Max MSE** | $\\le 1.0 \\times 10^{-4}$ | $2.12 \\times 10^{-6}$ | $2.13 \\times 10^{-6}$ | **PASS** |
| **Min Cosine Similarity** | $\\ge 0.9999$ | **0.9793** | **0.9791** | **FAIL** |
| **Decoded CER** | **$0.0%$** | **$28.57%$ (6 edits / 21 chars)** | **$28.57%$ (6 edits / 21 chars)** | **FAIL** |
| **Median Invoke Latency** | Baseline reference | 99.48 ms (P95: 106.50 ms) | 99.25 ms (P95: 100.87 ms) | Recorded |

### 3. Root Cause Analysis

1. Both FP16 and FP32 LiteRT models show the identical error signature ($L_\infty \approx 0.856$, $\text{CER} = 28.57%$), proving the failure is **not** quantization noise.
2. The drift stems entirely from the **Chebyshev polynomial approximation of Erf** compounding across 13 deep Transformer self-attention mixing blocks, shifting CTC logit boundaries on 4 out of 7 crops.

### 4. Classification & Decision: **REVISE / REJECT CANDIDATE 1**

- **Decision**: Reject candidate model generated with `-rtpo erf`.
- **Next Iteration (Experiment 2)**: Perform true ONNX graph surgery on the 13 decomposed `p2o.pd_op.gelu.*.*` subgraphs (`Div` $\to$ `Erf` $\to$ `Add` $\to$ `Mul` $\to$ `Mul`) by replacing them with native ONNX `Gelu(approximate="none")` opset 20 nodes.
- **Verified Feasibility**: Confirmed that `onnx2tf` converts ONNX `Gelu` to `tf.nn.gelu`, which compiles directly to LiteRT's native `BuiltinOperator.GELU` (Opcode 150) with **zero custom ops, zero CPU fallbacks, and exact mathematical precision**.

## Experiment 2 — Native BuiltinOperator GELU Surgery & FusionGemm Fix (2026-10-13)

### 1. Hypothesis & Surgical Mechanism

1. **Native GELU Graph Surgery**:
  - Upgrade static ONNX model to **Opset 20** via `onnx.version_converter.convert_version`.
  - Identify each of the 13 decomposed GELU subgraphs ending in `p2o.pd_op.gelu.0.0` through `p2o.pd_op.gelu.12.0`.
  - Remove the 5-node clusters (`Div` -> `Erf` -> `Add` -> `Mul` -> `Mul`) and insert a single standard ONNX `Gelu(approximate="none")` node per block, keeping all initializers and downstream connections intact.
  - During `onnx2tf` conversion, standard ONNX `Gelu` translates to `tf.nn.gelu(approximate=False)`, which compiles directly into LiteRT native `BuiltinOperator.GELU` (Opcode 150) with **zero custom/Flex operators**.
2. **Root Cause Discovery (The `onnxslim` `FusionGemm` Regression)**:
  - Systematic layer-by-layer diffing revealed that `onnxslim`'s default `FusionGemm` pass erroneously fused MatMul operations in SVTR's attention head, silently introducing an $L_\infty \approx 0.684$ error drift and 28.57% CER *before conversion ever reached LiteRT*.
  - By adding `--skip-fusion-patterns FusionGemm` to `onnxslim`, the static ONNX graph matched reference ONNX Runtime logits to within $3.93 \times 10^{-6}$ ($0.00000393$).

### 2. FlatBuffer Census & Operator Validation

Validated on `audit/models/litert/manga_rec_640_fp16.tflite` (10,620,416 bytes in the Agent A re-run below; the earlier handoff recorded 10,618,976 bytes):

- **Native GELU Count**: 13 (`BuiltinOperator.GELU`, Opcode 150)
- **Custom / Flex Opcodes**: `[]` (**0 Flex ops, 0 Select-TF-Ops**)
- **Top Operator Distribution**: `DEQUANTIZE` (145), `CONV_2D` (53), `MUL` (27), `ADD` (27), `MEAN` (15), `GELU` (13), `RESHAPE` (12), `FULLY_CONNECTED` (9), `STRIDED_SLICE` (8)
- **Tensor I/O Signatures**:
  - Input: `name="x"`, shape `[1, 48, 640, 3]`, dtype `float32` (Native NHWC layout)
  - Output: `name="Identity"`, shape `[1, 80, 18710]`, dtype `float32`

### 3. Measured Results vs Tolerance Gate

Evaluated via `audit/baseline_ocr/compare_litert.py` across Phase 1 golden NPZ test crops:

| Metric | Target Tolerance Gate | Exp 1 Candidate (Erf Approx) | Exp 2 Candidate (Native GELU + Skip FusionGemm) | Verdict |
| --- | --- | --- | --- | --- |
| **Max $L_\\infty$** | $\\le 0.05$ | 0.8567 (FAIL) | **0.002617** | **PASS** |
| **Max MSE** | $\\le 1.0 \\times 10^{-4}$ | $2.12 \\times 10^{-6}$ (PASS) | **$1.72 \\times 10^{-11}$** | **PASS** |
| **Min Cosine Similarity** | $\\ge 0.9999$ | 0.9793 (FAIL) | **0.99999984** | **PASS** |
| **Decoded CER** | **$0.0%$ (Zero Divergence)** | $28.57%$ (6 edits / 21 chars) | **$0.0%$ (0 edits / 21 chars)** | **PASS** |
| **Median Invoke Latency** | Baseline reference | 99.48 ms | **87.65 ms** in the formal re-run (P95 87.78 ms) | **PASS** |

### 4. Classification & Decision: **RETAIN CANDIDATE 2 / EXPERIMENT COMPLETE**

- **Decision**: **PASS**. Candidate 2 satisfies all numerical parity contracts, zero CER divergence, and eliminates CPU fallback risks for GPU delegation.
- **Conversion Utility**: Consolidated end-to-end into `audit/scripts/convert_ppocrv6_rec_litert.py`.
- **Agent A re-run**: Candidate FlatBuffer parity is recorded in `audit/baseline_ocr/results/phase3_litert_rec_exp2`; Android GPU behavior remains device validation work.

## Formal Experiment 2 Re-run & Android Integration Seam (2026-10-13)

### 1. Reference Parity Command and Result

Command run from the repository root:

```powershell
python audit/baseline_ocr/compare_litert.py --model audit/models/litert/manga_rec_640_fp16.tflite --baseline-dir audit/baseline_ocr/results/phase1_onnx_cpu --output-dir audit/baseline_ocr/results/phase3_litert_rec_exp2 --threads 1 --warmup-runs 5 --repeats 30
```

Raw command result:

```text
INFO: Created TensorFlow Lite XNNPACK delegate for CPU.
{
  "max_linf": 0.0026170015335083008,
  "max_mse": 1.7241544874344867e-11,
  "min_cosine_similarity": 0.9999998384643449,
  "reference_edit_distance": 0,
  "reference_character_count": 21,
  "reference_cer": 0.0,
  "median_litert_invoke_ms": 87.65240000138874,
  "p95_litert_invoke_ms": 87.78007000291836,
  "gate": "PASS",
  "recommendation": "retain candidate for the next experiment"
}
```

The comparison covered 7 saved width-640 crops, with 5 warmups and 30 timed invokes per crop. Detailed per-crop logits, CTC strings, edit distances, and latencies are in `audit/baseline_ocr/results/phase3_litert_rec_exp2/comparison.json`.

### 2. Remaining Static-Width FlatBuffers

The command `python audit/scripts/convert_ppocrv6_rec_litert.py --width <W>` completed with exit code 0 for widths 160, 320, 480, and 1600. Each conversion ran static-shape preparation, native GELU graph surgery, LiteRT conversion, allocation validation, and reported 13 GELU operators with no custom opcodes. Width 640 was also present and parity checked above.

| Width | FlatBuffer | Bytes | Input | Output | SHA-256 |
| --- | --- | --- | --- | --- | --- |
| 160 | `manga_rec_160_fp16.tflite` | 10,619,916 | `[1,48,160,3]` | `[1,20,18710]` | `B9AE67CB213900E39656D0697B0174AA70E65A67296E1C78802E95CE487C0BBD` |
| 320 | `manga_rec_320_fp16.tflite` | 10,620,960 | `[1,48,320,3]` | `[1,40,18710]` | `AF67748DB02425FF74CBE6F93559C2FE9240D9C8B3B62F5AB722A48176523110` |
| 480 | `manga_rec_480_fp16.tflite` | 10,620,416 | `[1,48,480,3]` | `[1,60,18710]` | `DF7DBAE2E0B563A0BF19DAABD73078C05D48B4CDB6C0B7FA95CE7DDCA08C9A60` |
| 640 | `manga_rec_640_fp16.tflite` | 10,620,416 | `[1,48,640,3]` | `[1,80,18710]` | `C0023777E6DE7CB0B399039687B7C001D131F21CEA77EAB2F1C406C9559A2772` |
| 1600 | `manga_rec_1600_fp16.tflite` | 10,620,972 | `[1,48,1600,3]` | `[1,200,18710]` | `BE8288C21BB8D8AAF495B83EEEF4AEB966E5BB5F5B61011C43A49011CD29B125` |

All files are under `audit/models/litert/`. The conversion utility's zero exit status confirms LiteRT allocation succeeded for each width; only width 640 has been compared against the saved ONNX logits so far.

A separate read-only validation loop ran the converter's `validate_litert_model` against all five FlatBuffers and asserted `GELU == 13` and an empty custom-opcode list for each. Runtime allocation succeeded and each input/output shape matched the table; stdout ended with `ALL_WIDTHS_VALIDATED`.

### 3. Android Engine Seam

`PaddleOcrV6SmallEngine.initializeLiteRt(modelFilesByWidth, dictionaryFile, preferGpu, allowXnnpackFallback, numThreads)` is now an opt-in path in `PaddleOcrV6SmallEngine.kt`:

- Requires files for all five static widths and validates file presence before setup.
- Resizes and gray-pads using the existing Bitmap preprocessing, then writes interleaved RGB floats with the established normalization directly into a direct NHWC buffer for `[1,48,W,3]`.
- Tries `CompatibilityList` GPU delegation first. Initialization or invocation failure can retry with an XNNPACK CPU interpreter; `allowXnnpackFallback=false` disables that fallback.
- Keeps at most two width interpreters resident, and releases delegates and buffers on close/reclaim.
- Current FlatBuffers are batch-1, so the coordinator's existing batch entry point uses sequential B1 LiteRT invokes and does not emit misleading ORT batch telemetry.

The existing ROI engine factory still calls the ONNX initializer. This seam is opt-in until production model provisioning is wired to the `.tflite` files and on-device results are checked. `adb devices -l` could not run because `adb` is not installed or on `PATH`, so GPU delegate initialization and Android-device CER/latency remain unverified here.

### 4. Build Verification

`.\gradlew.bat :app:compileDebugKotlin --console=plain` is ambiguous because the app has Dev and Standard variants. The explicit build command succeeded:

```text
> Task :app:compileStandardDebugKotlin
BUILD SUCCESSFUL in 1m 21s
174 actionable tasks: 6 executed, 168 up-to-date
```

No Android unit tests or on-device benchmarks were run for this seam.

### 5. Independent Review Follow-up (2026-10-13)

The independent [LiteRT seam review](review-litert-rec-seam) found a GPU invoke fallback issue and several lifecycle/performance improvements. The implementation now:

- Re-runs Bitmap preprocessing after switching to the CPU interpreter, so the retry uses the original crop rather than a fresh zeroed buffer.
- Latches GPU preference off after the first allowed GPU initialization or invocation fallback; later width runners start directly with XNNPACK.
- Groups LiteRT B1 calls by their selected static width inside the batch API, preserving result order while avoiding mixed-width LRU churn within a page batch. The resident interpreter limit remains two to bound memory.
- Publishes backend selection and the configured model map with volatile fields; uses a pre-sorted static width list; and writes NHWC through a reusable scratch array and bulk FloatBuffer put.
- Maps LiteRT XNNPACK and GPU execution labels to bounded trace provider tokens (`xnnpack` and `litert_gpu`).

After these corrections, ` .\gradlew.bat :app:compileStandardDebugKotlin --console=plain` completed with `BUILD SUCCESSFUL`. Android device/GPU verification remains outstanding because `adb` is unavailable in this environment.


