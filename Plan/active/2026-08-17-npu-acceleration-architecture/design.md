# NPU Hardware Acceleration & Batching Pipeline Design

## Objective
Accelerate the TachiyomiAT on-device translation pipeline by transitioning from sequential single-crop CPU/NNAPI inference to a high-throughput **Hybrid NPU (Qualcomm QNN HTP) + CPU (XNNPACK) architecture with multi-crop tensor batching, pre-compiled context caching, and zero-latency hardware discovery latching**.

## Problem & Motivation
1. **Sequential CPU Bottleneck:** On pages with 15–20 text bubbles, running the ViT encoder and PaddleOCR detection/recognition sequentially on CPU takes 400ms–1,200ms.
2. **AOT Inpainting Stall:** AOT-GAN 512x512 requires ~18–25 GFLOPs. Because NNAPI frequently drops to CPU/XNNPACK due to memory pressure or operator gates, free-text inpainting takes 1.5s–3.5s per patch on CPU.
3. **NNAPI Deprecation:** Android NNAPI is deprecated in Android 15. The modern standard for Snapdragon chips is Qualcomm QNN (`libQnnHtp.so`) with pre-compiled hardware context binaries (`.bin`).
4. **Fallback Latency Risk:** Attempting hardware accelerator calls and falling back per-turn/per-page adds 100–300ms of driver exception overhead. Hardware selection must be determined and latched once at initialization.

## Architecture & Design

```
                                [Original Page Bitmap]
                                          │
            ┌─────────────────────────────┴─────────────────────────────┐
            ▼                                                           ▼
┌───────────────────────────────┐                       ┌───────────────────────────────┐
│     Stage 1: Page Vision      │                       │     Stage 2: Batched OCR      │
│   (Qualcomm QNN HTP / NPU)    │                       │   (Qualcomm QNN HTP / NPU)    │
├───────────────────────────────┤                       ├───────────────────────────────┤
│ 1. Text Detector v4           │                       │ 1. ViT OCR Encoder            │
│    [1, 3, 640, 640] (INT8)    │                       │    Batched: [B, 3, 224, 224]  │
│ 2. Panel Detector             │ ──► [Extracted BBoxes] ──► (All B bubbles in 1 pass)  │
│    [1, 3, 640, 640] (INT8)    │                       │ 2. PaddleOCR Line Det (DBNet) │
│ 3. Bubble Segmenter           │                       │    Batched: [B, 3, 736, 736]  │
│    [1, 3, 640, 640] (INT8)    │                       │ 3. PaddleOCR Rec (Bucketed)   │
└───────────────────────────────┘                       │    Batched: [B, 3, 48, 1600]  │
                                                        └───────────────┬───────────────┘
                                                                        │
                                                                        ▼
                                                        ┌───────────────────────────────┐
                                                        │   Stage 2b: Decoder Loop      │
                                                        │   (ARM CPU / XNNPACK)         │
                                                        ├───────────────────────────────┤
                                                        │ MangaOCR Autoregressive Step  │
                                                        │ (Token-by-token loop in CPU   │
                                                        │  with zero dispatch latency)  │
                                                        └───────────────┬───────────────┘
                                                                        │
                                                                        ▼
┌───────────────────────────────┐                       ┌───────────────────────────────┐
│     Stage 4: Inpainting       │                       │     Stage 3: Translation      │
│   (Qualcomm QNN HTP / NPU)    │                       │   (Cloud / Local Contextual)  │
├───────────────────────────────┤                       ├───────────────────────────────┤
│ • Bubble Interior Solid Fill  │                       │ • Gemini / DeepSeek / API     │
│   (Fast CPU algorithm)        │                       │ • Streaming Context Chunks    │
│ • AOT Neural Inpainting       │                       └───────────────────────────────┘
│   (Qualcomm AOT-GAN)          │
│   Batched: [B, 3, 512, 512]   │
└───────────────────────────────┘
```

---

## Tiered Hardware Discovery & Latching Circuit Breaker

To eliminate per-page fallback penalties, hardware selection is governed by a **Session-Scoped Latching Circuit Breaker** (`HardwareDiscoveryEngine`):

```
[App Launch / Model Initialization]
                 │
                 ▼
┌─────────────────────────────────────────────────────────────┐
│ 1. Static Pre-Flight (Instant CPU Check)                    │
│    - Is it an emulator? (DeviceCapability.isEmulator)       │
│    - Is Android SDK < 29 or ABI not arm64-v8a?              │
│    ──► If YES: Immediately latch CPU_XNNPACK (Never try NPU)│
└─────────────────────────────┬───────────────────────────────┘
                              │ NO (Physical ARM64 device)
                              ▼
┌─────────────────────────────────────────────────────────────┐
│ 2. Single-Probe Hardware Latch                              │
│    - Is it Qualcomm Snapdragon?                             │
│       ├── Try QNN HTP (libQnnHtp.so) once                   │
│       │    ├── Success ──► Permanently LATCH: QUALCOMM_QNN  │
│       │    └── Fails   ──► Permanently LATCH: CPU_XNNPACK   │
│       └── Not Qualcomm (MediaTek / Tensor / Exynos):        │
│            ├── Try NNAPI once                               │
│            │    ├── Success ──► Permanently LATCH: NNAPI    │
│            │    └── Fails   ──► Permanently LATCH: CPU_XNNPACK
└─────────────────────────────┬───────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│ 3. Zero-Overhead Live Execution (Per-Page Reading)          │
│    - Every page calls session.run() on the latched route.   │
│    - No runtime driver checks, no try-catch fallback delay. │
│    - Fallback latency per turn: 0.0 ms.                     │
└─────────────────────────────────────────────────────────────┘
```

### Key Fallback Tenets
1. **Single-Probe Latch (Cold Boot Only):** Probing and compilation happen once when the session initializes. The resolved `HardwareRoute` is stored in an `@Volatile` field.
2. **Zero Runtime Check Latency:** Live page translations (`translateSinglePage`, `RollingAutoCoordinator`, `BatchCoordinator`) execute directly against the latched route. No exceptions are thrown and caught on a per-page basis.
3. **Emergency Circuit Breaker:** If an NPU driver suffers a fatal segmentation fault or runtime timeout during a reading session, the circuit breaker permanently demotes `activeRoute = CPU_XNNPACK` for the remainder of the app's process lifetime, preventing repeated stalls.
4. **User Override:** In Settings $\to$ Translation $\to$ Hardware Acceleration, users can force `AUTO`, `QUALCOMM_NPU`, `NNAPI`, or `CPU_XNNPACK`.

---

## Core Tenets
1. **Hybrid Execution Partitioning:**
   - **NPU (HTP):** Heavy static matrix ops (Detectors 640x640, ViT Encoder 224x224, DBNet 736x736, AOT Inpainting 512x512).
   - **CPU (XNNPACK):** Autoregressive sequential token loops (MangaOCR Decoder Step) and morphological flood fills.
2. **Multi-Crop Batching:** Convert sequential `for (box in detections)` loops into batched tensors `[B, 3, 224, 224]` and `[B, 3, 736, 736]`.
3. **Context Binary Caching:** Enable `qnn_context_cache_enable = "1"` to store `.bin` graph caches in app storage, dropping cold start model initialization from seconds to under 10ms.
4. **Official Qualcomm AOT-GAN Replacement:** Replace the unoptimized PyTorch conversion with the official `qualcomm/AOT-GAN` export from Qualcomm AI Hub.
