---
title: "Phase 5: On-Device Hardware Telemetry & Benchmarking Calibration"
kind: ticket
status: 2
assignee: ""
comments: none
---

# Phase 5: On-Device Hardware Telemetry & Benchmarking Calibration

## Device Environment Specification

- **Target Device**: `PKG110` (OnePlus 12 / Ace 3 Pro series)
- **SoC**: Qualcomm Snapdragon 8 Gen 3 (`SM8650`, Platform `pineapple`)
- **CPU**: ARMv9 Kryo (Cortex-X4 @ 3.3GHz + Cortex-A720 @ 3.15GHz/2.96GHz + Cortex-A520 @ 2.27GHz)
- **GPU**: Qualcomm Adreno 750 (OpenGL ES 3.2 [V@0762.41](mailto:V@0762.41), OpenCL 3.0)
- **Runtime Environment**: Official standalone LiteRT benchmarking binary `/data/local/tmp/litert_benchmark_model`
- **Transport**: Wireless ADB connected at `192.168.100.223:36539`

## Measured Hardware Benchmarks (30 Runs, 5 Warmups)

### 1. Manga PP-OCRv6 SVTR Recognizer Multi-Bucket Suite

| Bucket Width | Input Tensor Shape | Kryo XNNPACK CPU (4T) | OpenCL GPU Delegate | Speedup vs Baseline ONNX CPU (~99.5 ms) | Memory Footprint |
| --- | --- | --- | --- | --- | --- |
| **160 px** | `[1, 48, 160, 3]` | **11.62 ms** (min 11.40 ms) | N/A | **8.56x faster** | 44.07 MB |
| **320 px** | `[1, 48, 320, 3]` | **19.28 ms** (min 18.62 ms) | N/A | **5.16x faster** | 43.91 MB |
| **480 px** | `[1, 48, 480, 3]` | **29.19 ms** (min 27.76 ms) | N/A | **3.41x faster** | 46.27 MB |
| **640 px** | `[1, 48, 640, 3]` | **35.59 ms** (min 29.45 ms) | 129.82 ms (12 partition stalls) | **2.80x faster** | 50.82 MB |
| **1600 px** | `[1, 48, 1600, 3]` | **114.96 ms** (min 94.86 ms) | N/A | ~0.87x | 72.44 MB |

### 2. Manga PP-OCRv6 DBNet Text Detector Suite

| Model / Resolution | Input Tensor Shape | Kryo XNNPACK CPU (4T) | OpenCL GPU Delegate | Baseline PC CPU |
| --- | --- | --- | --- | --- |
| **DBNet 960x960 FP16** | `[1, 960, 960, 3]` | **81.05 ms** (min 77.88 ms) | 276.24 ms (15 partition stalls) | ~176.85 ms |

## Hardware Architecture Insights & Production Policy

1. **Kryo XNNPACK CPU is the Optimal Execution Target for PP-OCRv6**:
  - Both SVTR and DBNet exhibit significant partition splits when pushed through the mobile OpenCL GPU delegate:
    - SVTR: Multi-dimensional `StridedSlice` / 5D `Reshape` ops in the SVTR self-attention head yield 12 GPU-CPU context switches, degrading latency to 129.8 ms.
    - DBNet: Neck `TransposeConv` (v4) and `Relu0To1` ops force 15 GPU-CPU context switches, degrading latency to 276.2 ms.
  - In stark contrast, **LiteRT XNNPACK delegate compiles 350+ out of 355 ops seamlessly on Kryo ARM NEON**, achieving:
    - **35.59 ms on 640-width crops** (down from ~99.5 ms baseline).
    - **11.62 ms on 160-width dialogue exclamations**.
    - **81.05 ms on full 960x960 page text detection**.
2. **End-to-End Page OCR Latency Impact**:
  - On a typical manga page with 15 crops (mostly 160–480 px width):
    - Historical baseline: ~1.5 to 2.2 seconds total recognition time.
    - Optimized LiteRT multi-bucket XNNPACK execution: $81\text{ ms (det)} + 15 \times \sim 22\text{ ms (rec)} \approx \mathbf{410\text{ ms}}$ total neural execution time (~4x to 5x faster overall page reading throughput).
