# 07. Graph & Precision Optimization Research & Opportunity Ranking

**Document ID:** AUDIT-REP-07  
**Project:** Manga LaMa Google LiteRT Mobile GPU Optimization  
**Date:** October 2026  
**Auditor Status:** ML Inference & Optimization Forensic Engineer  

---

## 1. Summary of Optimization Landscape

Deploying Manga LaMa on Android mobile GPUs requires addressing two distinct challenges:
1. **The Structural Challenge:** The raw model contains 17,472 nodes, including unsupported `Einsum`, `Range`, and trigonometric functions, that trigger fatal CPU fallbacks.
2. **The Numerical Challenge:** The raw model contains BatchNorm running variances up to $675,607.12$, which exceed the FP16 maximum value ($65,504$) and cause catastrophic numerical overflow (`+inf` / `NaN`).

The following table ranks all identified optimization opportunities by impact, feasibility, and risk:

---

## 2. Ranked Optimization Opportunities

| Rank | Optimization Strategy | Target Component | Expected Speedup | Technical Risk | Feasibility Status |
| :---: | :--- | :--- | :--- | :--- | :--- |
| **1** | **Fourier-to-MatMul Replacement** | 18 FFC Spectral Units | **Enabler (100% GPU)** | Low (Deterministic math) | **PROVEN (g-ronimo)** |
| **2** | **BatchNorm Conv Fusion / Reparam**| 75 BatchNorm Nodes | **Enabler (FP16 Stability)**| Zero (Exact affine math) | **PROVEN (g-ronimo)** |
| **3** | **End-to-End Genuine FP16 Execution**| Complete Neural Graph | **~2.0× – 2.5× on Mobile GPU**| Low (post-BN fix) | **HIGH** |
| **4** | **Static Shape Fixing (`1,512,512,4`)**| Input & Slicing Nodes | **~10% – 15%** | Zero | **HIGH** |
| **5** | **LiteRT `CompiledModel` Zero-Copy**| Android Memory Boundary | **~20ms – 40ms per frame** | Low | **HIGH** |
| **6** | **GPU Shader Compilation Caching** | Driver Initialization | **Drop init: 2s $\to$ 50ms** | Zero | **HIGH** |
| **7** | **Static INT8 Quantization** | Full Graph / NPU | **1.2× – 1.5× (NPU only)** | High (Quality deg.) | **RESEARCH / DEFERRED**|

---

## 3. Deep Dive: Technical Analysis by Opportunity

### Rank 1: Precomputed Fourier-to-MatMul Graph Replacement
- **Category:** Architecture Transformation / Hardware Enabler.
- **Why It Matters:** The raw `lama-manga.onnx` cannot run on LiteRT GPU because it contains $216$ `Einsum` nodes, $180$ dynamic `Range` nodes, and $288$ trigonometric `Cos`/`Sin` nodes. These operators are completely absent from the mobile GPU delegate whitelist.
- **Technical Mechanism:**
  - Because the input resolution is static ($512 \times 512$), the bottleneck feature map is strictly $64 \times 64$ across all 18 blocks.
  - A 2D Real-to-Complex FFT on a $64 \times 64$ tensor decomposes into two 1D matrix multiplications:
    $$X_{\text{spectral}} = F_h \times X \times F_w^T$$
    where $F_h$ is a precomputed $64 \times 64$ matrix and $F_w$ is a precomputed $64 \times 33$ matrix.
  - By precomputing these matrices offline and storing them as static constant initializers, the entire dynamic trigonometric subgraph collapses into standard matrix multiplications (`BATCH_MATMUL`).
- **Evidence:** 
  - *Measured locally:* `lama_512_fp16.onnx` uses this exact approach and operates with only $1,580$ nodes (a $91\%$ node reduction) with zero `Einsum` or `Cos`/`Sin` nodes.
  - *Upstream:* g-ronimo reported a **5× speedup on WebGPU** (from 2.18s down to 0.43s) purely from eliminating CPU fallbacks in this branch.

---

### Rank 2: BatchNorm Reparameterization / Convolution Fusion
- **Category:** Numerical Stability & Operator Elimination.
- **Why It Matters:** In `lama-manga.onnx`, `/model/model.5/conv1/bn_l/BatchNormalization` has a running variance of **$675,607.12$**. Because IEEE 754 FP16 maximum is $65,504.0$, naïve FP16 conversion immediately overflows to `+inf`, zeroing out activations and outputting corrupted or black images.
- **Technical Mechanism:**
  - In evaluation mode, BatchNorm is linear: $y = \frac{\gamma}{\sqrt{\sigma^2 + \epsilon}} (x - \mu) + \beta$.
  - Fusing BatchNorm directly into the preceding convolution weights:
    $$W_{\text{fused}} = \frac{\gamma}{\sqrt{\sigma^2 + \epsilon}} W_{\text{conv}}, \quad B_{\text{fused}} = \frac{\gamma}{\sqrt{\sigma^2 + \epsilon}} (B_{\text{conv}} - \mu) + \beta$$
  - This eliminates all $75$ `BatchNormalization` nodes, reduces memory traffic, and resets the effective variance to $1.0$, completely eliminating the FP16 overflow hazard.
- **Evidence:** *VERIFIED.* g-ronimo successfully stabilized `lama_512_fp16.onnx` by reparameterizing all BatchNorm variances to $1.00$.

---

### Rank 3: Genuine End-to-End FP16 Execution
- **Category:** Hardware Arithmetic Acceleration.
- **Why It Matters:** Mobile GPUs (Qualcomm Adreno 6xx/7xx/8xx, ARM Mali-G7xx/G9xx, Immortalis) feature dual-issue FP16 ALUs that compute two FP16 operations per cycle compared to one FP32 operation. In addition, FP16 halves texture cache footprint and memory bus bandwidth.
- **Storage vs Compute:**
  - `lama-manga_fp16.onnx` only stores weights in FP16 and computes in FP32 (providing zero GPU compute speedup).
  - Genuine FP16 executes all convolutions, activations, and matrix multiplications natively in 16-bit half precision.
- **Numerical Risk:** Once BatchNorm is fused (Rank 2), convolution weights are in $[-0.38, +0.37]$, well within FP16 limits. Our numerical tests show max absolute deviation against FP32 is $< 0.0016$ ($< 0.42 / 255$).
- **Evidence:** *VERIFIED.* Quality metrics show $100\%$ perceptual identity ($0$ pixels differing by $>5/255$).

---

### Rank 4: Static Shape Fixing & Graph Pruning
- **Category:** Driver Optimization.
- **Why It Matters:** LiteRT GPU shader compilers (both Adreno OpenCL compiler and Mali Vulkan pipeline cache) require static tensor dimensions to pre-allocate texture memory layouts (`RGBA` packing) and unroll loops. Dynamic shapes force runtime texture reallocation.
- **Action:** Fix graph input permanently to `[1, 512, 512, 4]` (or `[1, 4, 512, 512]`). Eliminate all dynamic `Shape`, `Gather`, and dynamic `Slice` operators.

---

### Rank 5: Zero-Copy GPU Buffer Interoperability via `CompiledModel`
- **Category:** Android Pipeline Architecture.
- **Why It Matters:** In standard Android JNI, passing a `Bitmap` to a neural runtime involves:
  1. `Bitmap.getPixels` (CPU RAM copy).
  2. Allocating a Java/Direct `FloatBuffer`.
  3. `FloatBuffer.put` pixel-by-pixel (CPU RAM copy).
  4. GPU delegate driver copy: `glTexSubImage2D` (RAM to VRAM copy).
  - Across $512 \times 512 \times 4$ floats ($4\text{ MB}$), this copy pipeline costs **$15\text{ ms} – 30\text{ ms}$**.
- **Action:** Using LiteRT `CompiledModel.createInputBuffers()` or Android `HardwareBuffer` (AHardwareBuffer) backed by `EGLImage` allows the GPU to read decoded image textures directly without CPU memory round-trips.

---

### Rank 6: GPU Shader Compilation Caching (`gpu_cache_dir`)
- **Category:** Cold Start Optimization.
- **Why It Matters:** On mobile GPUs, the first inference execution compiles OpenCL kernels or Vulkan SPIR-V shaders into device machine code for the target Adreno/Mali GPU architecture. For a model with 222 convolutions, this can take **$1.5\text{ s} – 3.0\text{ s}$**.
- **Action:** Configure `gpu_cache_dir` in delegate options:
  ```kotlin
  val options = CompiledModel.Options.builder()
      .setAccelerator(Accelerator.GPU)
      .setCacheDir(context.cacheDir.absolutePath)
      .setModelToken("manga_lama_v1")
      .build()
  ```
  Subsequent launches load cached binary shaders, dropping initialization time to **$< 50\text{ ms}$**.

---

### Rank 7: Full Static INT8 Quantization (DEFERRED / RESEARCH)
- **Category:** Integer Quantization.
- **Why It Matters:** INT8 provides massive speedups on dedicated NPUs (Hexagon / NPU-7xx). However, on mobile GPUs:
  - Adreno and Mali GPUs do not always have native INT8 dot-product shader instructions for all convolutions; they frequently unpack INT8 to FP16 in shader registers.
  - Furthermore, manga artwork contains ultra-fine screentone dot grids ($60\text{ lpi}$) and subtle tone gradients. Quantizing activations to 8-bit integers introduces high risk of moiré patterns, screentone clumping, and boundary artifacts.
- **Recommendation:** Defer INT8 until FP16 LiteRT is successfully established on GPU.
