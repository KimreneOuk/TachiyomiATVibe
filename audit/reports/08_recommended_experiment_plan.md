# 08. Sequenced Experiment Plan for LiteRT Mobile GPU Deployment

**Document ID:** AUDIT-REP-08  
**Project:** Manga LaMa Google LiteRT Mobile GPU Optimization  
**Date:** October 2026  
**Auditor Status:** ML Inference & Optimization Forensic Engineer  

---

## 1. Objective & Phase Transition

**Phase 0 (Completed):** Forensic Audit, Architecture Analysis, Root Cause Identification, and Baseline Establishment.  
**Phase 1 (Next Phase):** Graph Rewriting, LiteRT Flatbuffer Conversion, Strict On-Device GPU Verification, and Android Application Integration.

Our goal in Phase 1 is to convert the manga-specific LaMa model into an optimized Google LiteRT (`.tflite`) model that runs **$100\%$ of its neural inference on mobile GPUs (Qualcomm Adreno and MediaTek Mali/Immortalis) with zero CPU fallbacks** and a target warm latency of **$< 150\text{ ms}$**.

---

## 2. Sequenced Implementation Phases

```text
Phase 1A: Architecture Rewriting & Numerical Parity (Desktop Python)
  ├── 1. Replace dynamic FourierUnit with precomputed 64x64/64x33 DFT MatMuls
  ├── 2. Fuse all 75 BatchNorm layers into preceding Conv weights (eliminating 675k variance)
  ├── 3. Fix input signature permanently to [1, 4, 512, 512] (or [1, 512, 512, 4])
  └── 4. Verify numerical equivalence against authoritative FP32 reference tensors (PSNR > 45 dB)
                                    │
                                    ▼
Phase 1B: Google LiteRT Conversion & Operator Whitelist Audit
  ├── 1. Primary Route: Convert via Google litert-torch (ai_edge_torch)
  ├── 2. Fallback Route: Convert via onnx2tf with NHWC graph optimization
  └── 3. Static Flatbuffer Audit: Verify all ops are in the LiteRT GPU delegate whitelist
                                    │
                                    ▼
Phase 1C: Strict On-Device Mobile GPU Verification (Android Devices)
  ├── 1. Deploy Google's standalone benchmark_model binary to Snapdragon & MediaTek hardware
  ├── 2. Run adb benchmark with --use_gpu=true --enable_op_profiling=true
  ├── 3. Verify zero CPU fallback (100% of nodes mapped to TfLiteGpuDelegateV2 / ML Drift)
  └── 4. Measure warm inference latency, cold initialization time, and thermal throttling
                                    │
                                    ▼
Phase 1D: Production Integration into Tachiyomi / Android Translation Pipeline
  ├── 1. Integrate CompiledModel API into eu.kanade.translation.engines.inpainting
  ├── 2. Implement zero-copy buffer interop with HardwareBuffer
  └── 3. Wire into PageInpaintingEngine with 12px distance-field feathering
```

---

## 3. Detailed Work Breakdown by Milestone

### Milestone 1A: Graph Rewriting & Numerical Equivalence (Desktop)
- **Step 1A.1:** Load dreMaz's original PyTorch checkpoint `lama_large_512px.ckpt` or perform graph surgery on `lama-manga.onnx`.
- **Step 1A.2:** Replace the 18 dynamic `FourierUnitJIT` blocks with `FourierUnitMatMul`:
  - Precompute constant height DFT matrix $F_h \in \mathbb{R}^{64 \times 64}$.
  - Precompute constant width DFT matrix $F_w \in \mathbb{R}^{64 \times 33}$.
  - Compute forward 2D RFFT as: $X_{\text{spectral}} = F_h \times X \times F_w^T$.
  - Compute inverse 2D IRFFT as: $X = F_h^T \times X_{\text{spectral}} \times F_w$.
- **Step 1A.3:** Fold all BatchNorm layers into preceding Conv layers:
  $$W_{\text{fused}} = \frac{\gamma}{\sqrt{\sigma^2 + \epsilon}} W_{\text{conv}}, \quad B_{\text{fused}} = \frac{\gamma}{\sqrt{\sigma^2 + \epsilon}} (B_{\text{conv}} - \mu) + \beta$$
- **Step 1A.4:** Numerical Regression Gate: Run inference across all 5 reference test patterns. Compare against saved reference tensors (`audit/reference_data/reference_outputs/*_ref_fp32.npy`).
  - **Gate Criteria:** MAE $< 1 \times 10^{-4}$, PSNR $> 45\text{ dB}$, SSIM $> 0.999$, Zero visible artifacts.

---

### Milestone 1B: Conversion to Google LiteRT (`.tflite`)
- **Step 1B.1 (Preferred):** Export directly from PyTorch via `litert-torch`:
  ```python
  import litert_torch
  edge_model = litert_torch.convert(model, (sample_input,))
  edge_model.export("manga_lama_fp16.tflite")
  ```
- **Step 1B.2 (Fallback):** Export clean ONNX model, then run `onnx2tf`:
  ```bash
  onnx2tf -i manga_lama_clean.onnx -o manga_lama_litert/ -ois "input:1,512,512,4"
  ```
- **Step 1B.3:** Inspect generated flatbuffer schema (`tflite_schema`) to verify:
  - Total operators $< 1,600$.
  - Tensor shapes are strictly static `[1, 512, 512, 4]`.
  - Zero unsupported operators (`Einsum`, `Cos`, `Sin`, dynamic `Range` are 100% eliminated).

---

### Milestone 1C: Strict On-Device GPU Verification
- **Target Hardware Test Matrix:**
  1. **Device Tier 1 (Flagship Snapdragon):** Snapdragon 8 Gen 2 / Gen 3 (Adreno 740 / 750) — e.g. Samsung Galaxy S23/S24, Xiaomi 13/14.
  2. **Device Tier 2 (Mid-to-High MediaTek):** MediaTek Dimensity 8200 / 9200 (ARM Mali-G610 / Immortalis-G715).
  3. **Device Tier 3 (Mainstream / Budget):** Snapdragon 7+ Gen 2 (Adreno 725) or Dimensity 7200.
- **Verification Command:**
  ```bash
  adb shell /data/local/tmp/benchmark_model \
      --graph=/data/local/tmp/manga_lama_fp16.tflite \
      --use_gpu=true \
      --gpu_precision_loss_allowed=true \
      --enable_op_profiling=true \
      --num_runs=50
  ```
- **Gate Criteria:**
  - `TFLiteInterpreter (CPU) node count == 0` (100% GPU delegation).
  - Warm inference median latency $\le 150\text{ ms}$ on Tier 1 hardware.
  - Warm inference median latency $\le 300\text{ ms}$ on Tier 2 hardware.
  - Peak resident memory $\le 180\text{ MB}$.

---

### Milestone 1D: Android Integration in `tachiyomiATVIBE`
- **Step 1D.1:** Add LiteRT Android dependency to `app/build.gradle.kts`:
  ```kotlin
  implementation("com.google.ai.edge.litert:litert:2.2.0")
  ```
- **Step 1D.2:** Implement `LiteRtInpaintingEngine.kt` implementing `CompiledModel`:
  - Configure `Accelerator.GPU`.
  - Enable shader compilation caching (`setCacheDir`).
  - Pre-allocate reusable input/output buffers.
- **Step 1D.3:** Update `PageInpaintingPlanner.kt` and `PageInpaintingEngine.kt` to route inpainting requests to the GPU engine with 12px distance-field alpha feathering.
- **Step 1D.4:** Validate end-to-end user experience in the manga reader: translation and inpainting must complete smoothly without UI stutter or memory pressure.

---

## 4. Measurable Success Criteria & Quantitative Gates

| Metric | Target Acceptance Threshold | Hard Fail / Rejection Threshold |
| :--- | :--- | :--- |
| **GPU Operator Delegation** | **100.0% of nodes on GPU** | Any node falling back to CPU ($<100\%$) |
| **Warm Latency (Adreno 740+)** | **$\le 120\text{ ms}$** | $> 250\text{ ms}$ |
| **Warm Latency (Mali-G715+)** | **$\le 180\text{ ms}$** | $> 350\text{ ms}$ |
| **PSNR vs FP32 Reference** | **$\ge 45.0\text{ dB}$** | $< 38.0\text{ dB}$ |
| **SSIM vs FP32 Reference** | **$\ge 0.9990$** | $< 0.9900$ |
| **Max Pixel Deviation** | **$\le 5 / 255$** | $> 25 / 255$ |
| **Model Asset Size** | **$\le 105\text{ MB}$ (FP16 flatbuffer)**| $> 150\text{ MB}$ |
| **Cold Start with Shader Cache**| **$\le 60\text{ ms}$** | $> 300\text{ ms}$ |

---

## 5. Rollback & Contingency Plan

If any critical blocker is encountered during Phase 1:
1. **If `litert-torch` fails on specific PyTorch constructs:** Fall back immediately to Pathway 1B (`onnx2tf` with graph surgery).
2. **If a target Mali GPU driver exhibits shader compilation errors on complex MatMuls:** Fall back to 2D Depthwise Separable convolutions approximating the spectral frequency kernel, or fall back to the existing optimized AOT-GAN (`aot-512.onnx`).
3. **If LiteRT cannot achieve zero CPU fallback under any conversion path:** The audit proves that LiteRT GPU deployment is blocked by runtime operator support; in that scenario, the app continues using its existing ONNX Runtime pipeline (`AOTInpainting.kt`).
