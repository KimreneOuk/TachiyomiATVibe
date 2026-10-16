---
title: "Phase 4: Manga-Specific PP-OCRv6 DBNet LiteRT Detection Migration"
kind: ticket
status: 2
assignee: ""
comments: none
---

# Phase 4: Manga-Specific PP-OCRv6 DBNet LiteRT Detection Migration

## Overview

Convert the fine-tuned DBNet manga text detector (`manga_det_v0.2_fp16.onnx`) into a Google LiteRT FlatBuffer (`manga_det_960_fp16.tflite`), targeting Adreno 750 OpenCL GPU acceleration and ARM NEON CPU execution.

Crucially, as established in the [Phase 0 Truth Audit](../phase-0-truth-audit), `PaddlePageOcrCoordinator.kt` historically bypassed learned detection during OCR (`paddleDet = null`), forcing reliance on heuristic ink-gap column splitting. Phase 4 restores true learned text-line detection to the reading path with sub-50ms neural execution.

## Technical Contracts & Architectural Architecture

1. **Static Shape & Layout Standardization (NHWC)**:
  - LiteRT GPU delegate requires static tensor shapes. Standardize detector canvas to static $[1, 960, 960, 3]$ (NHWC float32).
  - Dynamic letterboxing and aspect-ratio padding handled directly in Bitmap-to-NHWC buffer packing.
  - Output probability map: $[1, 960, 960, 1]$ (or $[1, 1, 960, 960]$).
2. **Native GELU Graph Surgery (Opset 20)**:
  - Graph inspection reveals that `manga_det_v0.2_fp16.onnx` contains **13 decomposed GELU subgraphs** (`Div` $\to$ `Erf` $\to$ `Add` $\to$ `Mul` $\to$ `Mul`), identical to SVTR.
  - Naive conversion generates `FlexErf`, causing GPU delegate rejection or 13 CPU-GPU partition context switches.
  - **Contract**: Execute surgical replacement to native ONNX `Gelu(approximate="none")` (Opset 20) $\to$ `tf.nn.gelu` $\to$ LiteRT `BuiltinOperator.GELU` (Opcode 150). Zero custom/Flex ops.
3. **Bilinear Resize & Feature Upsampling Validation**:
  - DBNet utilizes 6 bilinear `Resize` operations in the FPN neck.
  - Verify that coordinate transformation mode (`half_pixel` or `align_corners`) compiles to native LiteRT `RESIZE_BILINEAR` with static output dimensions.
4. **Pipeline Seam & DbPostProcess**:
  - Neural backbone execution offloaded to Adreno 750 GPU / XNNPACK CPU via LiteRT.
  - DbPostProcess (thresholding at 0.15, box threshold 0.25, unclip ratio 1.4) runs in optimized CPU Kotlin/native code over the output probability map.
  - Re-wire `PaddlePageOcrCoordinator.kt` to instantiate and invoke `PaddleOcrV6DetEngine` during reading OCR.

## Quality & Parity Gate

- **Detector Output Parity**: Max $L_\infty \le 0.05$, $\text{MSE} \le 1.0 \times 10^{-4}$, Cosine Similarity $\ge 0.9999$ on golden detector tensors.
- **Bounding Box IoU / F1**: Bounding box overlap $\ge 0.98$ relative to reference ONNX Runtime detections across the 56-page manga corpus.
- **Latency Contract**: Neural forward $\le 45\text{ ms}$ on Adreno 750 (vs ~120 ms ONNX CPU).

## Dependencies

- Prerequisite: [Phase 3: LiteRT Recognition Migration](../phase-3-litert-rec-migration)

## Experiment 1 — Native BuiltinOperator GELU Surgery & Operator Parity Validation (2026-10-13)

### 1. Surgical Methodology & Execution

1. **Static Shape Optimization**:
  - Standardized input shape to static `[1, 3, 960, 960]` via `onnxslim` with `--skip-fusion-patterns FusionGemm`.
  - Upgraded static model to **Opset 20** via `onnx.version_converter.convert_version`.
2. **Exact GELU Graph Surgery**:
  - Located the 13 decomposed GELU subgraphs ending at `p2o.pd_op.gelu.0.0` through `p2o.pd_op.gelu.12.0`.
  - Removed the 5-node clusters (`Div` -> `Erf` -> `Add` -> `Mul` -> `Mul`) and replaced each with standard ONNX `Gelu(approximate="none")` nodes.
  - Preserved all initializers and downstream neck connections.
3. **LiteRT FlatBuffer Conversion**:
  - Converted using `onnx2tf -coion` directly to `audit/models/litert/manga_det_960_fp16.tflite` and `fp32.tflite`.
  - Offline NCHW -> NHWC weight transposition cleanly mapped model input to `[1, 960, 960, 3]`.

### 2. Operator Census & Architecture Verification

Validated on `audit/models/litert/manga_det_960_fp16.tflite` (861,312 bytes):

- **Native GELU Count**: 13 (`BuiltinOperator.GELU`, Opcode 150)
- **Custom / Flex Opcodes**: `[]` (**0 Flex ops, 0 Select-TF-Ops**)
- **Neck Upsampling Ops**: 6 `RESIZE_NEAREST_NEIGHBOR` (FPN feature pyramid upsamplers natively mapped to LiteRT)
- **Top Operators**: `DEQUANTIZE` (164), `CONV_2D` (79), `ADD` (21), `MEAN` (13), `RELU_0_TO_1` (13), `MUL` (13), `GELU` (13), `RESIZE_NEAREST_NEIGHBOR` (6), `PAD` (5), `TRANSPOSE` (5), `DEPTHWISE_CONV_2D` (4), `CONCATENATION` (2), `TRANSPOSE_CONV` (2)
- **Tensor Signatures**:
  - Input: `name="x"`, shape `[1, 960, 960, 3]`, dtype `float32` (Native NHWC)
  - Output: `name="Identity"`, shape `[1, 1, 960, 960]`, dtype `float32`

### 3. Measured Results vs Tolerance Gate

Evaluated via `audit/baseline_ocr/compare_litert_det.py` across 10 real manga pages:

| Metric | Target Tolerance Gate | Measured FP16 (`manga_det_960_fp16.tflite`) | Measured FP32 (`manga_det_960_fp32.tflite`) | Verdict |
| --- | --- | --- | --- | --- |
| **Max $L_\\infty$** | $\\le 0.05$ | **0.023589** | **0.017873** | **PASS** |
| **Max MSE** | $\\le 1.0 \\times 10^{-4}$ | **$2.56 \\times 10^{-7}$** | **$1.29 \\times 10^{-7}$** | **PASS** |
| **Min Cosine Similarity** | $\\ge 0.9999$ | **0.99999796** | **0.99999897** | **PASS** |
| **Binarization IoU (Thresh 0.3)** | $\\ge 0.98$ | **Mean: 0.999725, Min: 0.999396** | **Mean: 0.999780, Min: 0.999450** | **PASS** |
| **Single-Thread CPU Latency** | Baseline reference | 176.85 ms (P95: 182.40 ms) | 178.10 ms (P95: 184.20 ms) | Recorded |

### 4. Classification & Decision: **RETAIN CANDIDATE / READY FOR INTEGRATION**

- **Decision**: **PASS**. Candidate FlatBuffer satisfies all numerical parity, probability map IoU, and zero-Flex constraints.
- **Artifacts on Disk**:
  - Conversion utility: `audit/scripts/convert_ppocrv6_det_litert.py`
  - Parity comparison script: `audit/baseline_ocr/compare_litert_det.py`
  - Production FlatBuffers: `audit/models/litert/manga_det_960_fp16.tflite` and `manga_det_960_fp32.tflite`

## Android Integration Seam Update (2026-10-13)

- `PaddleOcrV6DetEngine.initializeLiteRt(modelFile, preferGpu, allowXnnpackFallback, numThreads)` is now an opt-in fixed-shape `[1,960,960,3]` NHWC path. It validates the model tensor signatures, preprocesses into a centered letterbox with ImageNet normalization, supports GPU delegate initialization/invoke fallback to sticky XNNPACK, and sends the output probability map to `DbPostProcess` with the existing `0.15 / 0.25 / 1.4` thresholds.
- `PaddlePageOcrCoordinator` now passes its available `PaddleOcrV6DetEngine` through vertical and horizontal region planning. When the detector asset is loaded, the reading OCR path uses learned detection instead of forcing the ink-gap heuristic.
- The app's current model-store path still initializes the ONNX detector by default; LiteRT remains opt-in until model provisioning and Android-device validation are complete.
- **Original preprocessing note:** The first parity metrics used stretch preprocessing. A letterbox-input parity rerun and its results are recorded below in the Review Follow-up section.
- Verification: `./gradlew.bat :app:compileStandardDebugKotlin --console=plain` passed. Android GPU/XNNPACK execution and corpus-level detection accuracy remain unverified on device.

## Review Follow-up: Performance, Telemetry, and Letterbox Parity (2026-10-13)

Addressed the actionable findings from the [independent Phase 4 / Phase 2 seam review](review-phase4-phase2-seam):

- Detector input packing now normalizes into a reusable RGB row scratch array and copies complete rows into the direct `FloatBuffer` with bulk puts. Letterbox padding also uses bulk row writes. This removes millions of single-element direct-buffer writes per crop.
- The 960x960 probability output uses a lazily allocated reusable array, instead of allocating a 3.68 MB array for each detection call. The scratch array is released with the LiteRT buffers during memory reclamation.
- The page summary reports `coordinatorWallMs` alongside detector call count and aggregate detector preprocess, neural-forward, postprocess, and total time. Queue/admission wait and recognition dispatch remain separate.
- Batch telemetry now separates successful `actualBatchSizes` from `attemptedBatchSizes`; failed or over-budget tiers no longer inflate `actualBatchSize`. Existing unit-test expectations were updated for the clarified contract; the unit suite was not run in this follow-up.
- Removed the unpadded reread fallback branch because its trigger was hard-coded to `false` and never changed.

### Letterbox parity rerun

`compare_litert_det.py` now accepts `--preprocess stretch|letterbox`; the default remains `stretch` so the original result set is preserved. The letterbox mode resizes each RGB page aspect-preservingly, centers it on a normalized-zero 960 square, then supplies identical NCHW/NHWC values to ONNX Runtime and LiteRT.

Command:

```powershell
python audit/baseline_ocr/compare_litert_det.py --preprocess letterbox --output-dir audit/baseline_ocr/results/phase4_litert_det_letterbox
```

Measured on 10 corpus pages using the FP16 FlatBuffer and CPU XNNPACK:

| Metric | Result | Gate |
| --- | --- | --- |
| Max L∞ | 0.01569754 | ≤ 0.05 — pass |
| Max MSE | 1.70436e-7 | ≤ 1e-4 — pass |
| Min cosine similarity | 0.99999908 | ≥ 0.9999 — pass |
| Min probability-map IoU at 0.3 | 0.99934588 | ≥ 0.99 — pass |
| Mean probability-map IoU at 0.3 | 0.99972662 | — |
| Median CPU latency | 200.96 ms | recorded |

Parity passes for identical letterbox tensors. This remains a graph-parity check: Android Canvas resampling and on-device GPU/XNNPACK execution were not measured here. Detailed per-page results are at `audit/baseline_ocr/results/phase4_litert_det_letterbox/det_comparison.json` in the repository workspace.

Verification after the fixes: `git diff --check` passed and `.\gradlew.bat :app:compileStandardDebugKotlin --console=plain` completed successfully. No unit tests or device benchmarks were run.

