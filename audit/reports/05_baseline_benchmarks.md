# 05. Baseline Desktop Benchmarks & Profiling Report

**Document ID:** AUDIT-REP-05  
**Project:** Manga LaMa Google LiteRT Mobile GPU Optimization  
**Date:** October 2026  
**Auditor Status:** ML Inference & Optimization Forensic Engineer  

---

## 1. Benchmark Environment & Methodology

All baseline measurements were conducted locally using the official Python ONNX Runtime engine. Desktop CPU benchmarks establish the numerical reference, baseline execution profile, and initialization bounds before any mobile LiteRT conversion.

> [!WARNING]
> **Desktop Baseline Disclaimer:** Desktop CPU measurements serve exclusively as regression baselines and architecture sanity checks. **They must NOT be extrapolated directly into Snapdragon (Adreno) or MediaTek (Mali/Immortalis) mobile GPU latency predictions.**

### System & Runtime Configuration
- **Operating System:** Windows 11 (64-bit)
- **Python Version:** Python 3.11.0
- **ONNX Runtime Engine:** ONNX Runtime `1.27.0` (CPUExecutionProvider)
- **Thread Configuration:** `intra_op_num_threads = 4`, `inter_op_num_threads = 1`
- **Execution Mode:** `ORT_SEQUENTIAL`
- **Graph Optimization Level:** `ORT_ENABLE_ALL`
- **Input Dimension:** Fixed evaluation shape $1 \times 512 \times 512$
- **Benchmark Sample Count:** $30$ warm iterations following $3$ warm-up iterations and $1$ cold start iteration

---

## 2. Comprehensive Benchmark Results (30 Warm Iterations)

| Model Name | Format & Precision | File Size | Model Init Time | Cold Start Latency | Warm Median Latency | Warm Mean Latency | Warm P95 Latency | Warm Min Latency | Memory Delta (Init) |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **`lama-manga.onnx`** | Dual-input FP32 (Original) | $197.87\text{ MB}$ | **$9,863.70\text{ ms}$** | $2,817.78\text{ ms}$ | **$2,674.79\text{ ms}$** | $2,685.73\text{ ms}$ | $2,808.65\text{ ms}$ | $2,561.93\text{ ms}$ | $+272.5\text{ MB}$ |
| **`lama-manga_fp16.onnx`** | Single-input Weight FP16 | $103.78\text{ MB}$ | **$1,863.50\text{ ms}$** | $2,645.65\text{ ms}$ | **$2,566.50\text{ ms}$** | $2,664.36\text{ ms}$ | $3,086.20\text{ ms}$ | $2,472.49\text{ ms}$ | $+260.7\text{ MB}$ |
| **`lama-manga_int8.onnx`** | Single-input Weight UINT8 | $57.04\text{ MB}$ | **$1,557.00\text{ ms}$** | $2,804.43\text{ ms}$ | **$2,702.10\text{ ms}$** | $2,626.65\text{ ms}$ | $2,760.88\text{ ms}$ | $2,216.93\text{ ms}$ | $+141.5\text{ MB}$ |
| **`lama_512_fp16.onnx`** | Single-input Full FP16 (g-ronimo)| $101.62\text{ MB}$ | **$954.88\text{ ms}$** | $2,217.24\text{ ms}$ | **$1,934.22\text{ ms}$** | $1,867.95\text{ ms}$ | $2,092.32\text{ ms}$ | $1,535.83\text{ ms}$ | $+221.5\text{ MB}$ |

---

## 3. Forensic Analysis of Benchmark Findings

### Finding 1: Massive Initialization Bottleneck in the Raw Model
- The authoritative `lama-manga.onnx` model requires **$9.86\text{ seconds}$** just to parse the ONNX graph and initialize the ONNX Runtime session.
- **Root Cause:** The un-optimized graph contains **$17,472$ nodes** ($7,051$ `Constant`, $1,360$ `Concat`, $1,348$ `Reshape`, $1,322$ `Slice`, $1,188$ `Shape`). ONNX Runtime's shape inference engine must build and validate a massive dependency graph during initialization.
- In contrast, the simplified `lama_512_fp16.onnx` model ($1,580$ nodes) initializes in **$954.88\text{ ms}$** ($10.3\times$ faster).

### Finding 2: "Smaller is NOT Faster" on CPU (Weight-Only Quantization Overhead)
- Despite reducing file size by $71.2\%$ ($57\text{ MB}$ vs $198\text{ MB}$), `lama-manga_int8.onnx` exhibits a median warm latency of **$2,702.10\text{ ms}$**, which is **slower** than the original FP32 model ($2,674.79\text{ ms}$) and slower than weight FP16 ($2,566.50\text{ ms}$).
- **Mechanism:** The INT8 model contains $222$ `DequantizeLinear` nodes. Before every single convolution, the quantized weights are upconverted to FP32 in software on the CPU. The CPU spends more compute cycles dequantizing weights than it saves in memory bandwidth.
- **Conclusion:** Weight-only INT8 provides zero execution advantage without specialized hardware (e.g. NPU/DSP) that supports native quantized dot products.

### Finding 3: Structural Pruning & MatMul Acceleration
- `lama_512_fp16.onnx` achieves a median latency of **$1,934.22\text{ ms}$** ($27.7\%$ faster on CPU than `lama-manga.onnx`).
- **Mechanism:** By replacing the dynamic Fourier Unit ($34$ FFT-math nodes and hundreds of slice/concat operations per block) with precomputed matrix multiplications, the graph is pruned from $17,472$ nodes down to $1,580$ nodes, eliminating CPU graph traversal overhead.

---

## 4. Node-Level Execution Profiling Breakdown

Extracted from ONNX Runtime profiling traces (`audit/reports/ort_profile_*.json`):

| Operation Category | Operator Types | Call Count (per inference) | Total CPU Duration (%) | Dominant Kernel |
| :--- | :--- | :--- | :--- | :--- |
| **Convolutions** | `Conv`, `ConvTranspose` | $225$ | **$71.4\%$** | OpenBLAS / MKL GEMM Conv |
| **Spectral MatMuls & Einsum**| `MatMul`, `Einsum` | $432$ | **$18.2\%$** | SGEMM MatMul |
| **Normalization** | `BatchNormalization` | $75$ | **$4.1\%$** | Elementwise affine transform |
| **Activations & Elementwise**| `Relu`, `Add`, `Mul`, `Sub` | $801$ | **$3.8\%$** | Vectorized SIMD loops |
| **Graph Slicing & Reshapes** | `Slice`, `Concat`, `Reshape`| $4,030$ | **$2.5\%$** | Memory copies & index calculations |

---

## 5. Memory Footprint During Inference

- **Process Memory Before Model Loading:** $62.8\text{ MB}$ RSS
- **Process Memory After Session Init (FP32):** $335.3\text{ MB}$ RSS (Delta: $+272.5\text{ MB}$)
- **Process Memory Peak During 30-Iter Benchmark:** $679.9\text{ MB}$ RSS
- **Tensor Allocation Analysis:**
  - Model weights: $194.52\text{ MB}$
  - Peak activation buffer for $1 \times 512 \times 512$ batch: $\approx 185\text{ MB}$ intermediate scratchpad
  - Direct tensor zero-copy buffer reuse will be essential on Android devices to stay within Tachiyomi's memory budget.

---

## 6. Commands to Reproduce Benchmarks

```bash
# Generate synthetic manga test cases
python audit/scripts/create_test_dataset.py

# Run 30-iteration benchmark across all four models
python audit/scripts/benchmark_baseline.py --iters 30

# Run 5-case numerical quality evaluation
python audit/scripts/evaluate_quality.py
```
