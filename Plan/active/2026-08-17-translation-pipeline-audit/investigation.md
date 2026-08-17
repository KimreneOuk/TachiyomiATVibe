# Translation Pipeline Architecture & NPU Feasibility Audit — Technical Investigation

## 1. High-Level Orchestration & Modes

The TachiyomiAT translation system operates in three distinct modes, each wrapping the shared underlying 5-stage pipeline:

```
                  ┌────────────────────────────────────────┐
                  │           TranslationManager           │
                  └───────────────┬────────────────────────┘
                                  │
         ┌────────────────────────┼────────────────────────┐
         ▼                        ▼                        ▼
┌──────────────────┐    ┌──────────────────┐    ┌──────────────────┐
│   Manual Mode    │    │    Auto Mode     │    │    Batch Mode    │
│  (Single-Page)   │    │ (Rolling-Window) │    │ (Pre-Translate)  │
└────────┬─────────┘    └────────┬─────────┘    └────────┬─────────┘
         │                       │                       │
         │ translateSinglePage   │ RollingAutoCoordinator│ BatchCoordinator
         │                       │ (visible + N ahead)   │ (3-lane pipelining)
         ▼                       ▼                       ▼
┌──────────────────────────────────────────────────────────────────┐
│                   Translation Pipeline Stages                    │
│                                                                  │
│  [1. Decode] ──► [2. Detect/Seg] ──► [3. OCR] ──► [4. Translate] │
│                                         │                        │
│                                         ▼                        │
│                                   [5. Inpaint]                   │
│                                         │                        │
│                                         ▼                        │
│                                   [6. Render]                    │
└──────────────────────────────────────────────────────────────────┘
```

### Mode Comparison Matrix

| Aspect | Manual (Single-Page) | Auto (Rolling Prefetch) | Batch (Pre-Translation) |
|---|---|---|---|
| **Entry Point** | `TranslationPipeline.translateSinglePage` | `RollingAutoCoordinator.updateWindow` -> `prepareSinglePage` -> `translatePreparedPage` | `ChapterTranslator` -> `TranslationPipeline.translateBatch` -> `BatchCoordinator.runPass1` |
| **Concurrency** | Serialized per page via `withNativeLane` (90s timeout) | 2-lane pipelined: Native Prepare Lane (1 page) overlapped with Remote Translation/Render Lane (1 page) | 3-lane decoupled pipelining: Native Lane (OCR + Inpaint) ── Channel ──► Translator Lane (LLM) ──► Render Join Lane |
| **Trigger Mechanism** | User taps translate button on reader page | Reader scrolls page into view; coordinator monitors visible index + `aheadTarget` (e.g. 2-3 pages) | User queues chapter in manga detail screen / download queue / chapter list |
| **Memory Policy** | Immediate decode -> OCR -> Inpaint -> Render -> Persist | Gated by `TranslationMemoryBudget.hasHeadroomForPrefetch()`; holds at most 1 prepared page | Strict held-bitmap ceiling (`HELD_BITMAP_MAX_COUNT = 4`, `HELD_BITMAP_BYTE_CEILING = 48MB`); excess spills to disk |
| **Retry Behavior** | Single-page adaptive retry (max 2 retries for partial blocks) | Re-prepare bounded by `reprepareAttempts`; fails gracefully if native race lost | Adaptive chunk retry + 2-pass revision (`runPass2` for flagged blocks / consistency) |

---

## 2. Comprehensive Model & Stage Audit

### Model Inventory Summary

| Model Identifier | Asset Path | Task / Purpose | Input Shape & Type | Output Shape & Type | Static / Predictable? | Batchable? `[B, ...]` |
|---|---|---|---|---|---|---|
| **Text Detector v4** | `models/detection/detector-v4-s_int8.onnx` | Detect text bubbles & free text (RT-DETR) | `images`: `[1, 3, 640, 640]` FP32 (0..1)<br>`orig_target_sizes`: `[1, 2]` INT64 | `labels`: `[1, N]` INT64<br>`boxes`: `[1, N, 4]` FP32<br>`scores`: `[1, N]` FP32 | **STATIC (Fixed 640x640)** | **YES** (`[B, 3, 640, 640]`) |
| **Panel Detector** | `models/detection/manga_panel_detector_int8.onnx` | Detect comic panels (YOLO26-nano) | `images`: `[1, 3, 640, 640]` FP32 (0..1, pad 114) | `output0`: `[1, N, 5]` FP32 (xyxy + conf) | **STATIC (Fixed 640x640)** | **YES** (`[B, 3, 640, 640]`) |
| **Bubble Segmenter** | `models/segmentation/manga109_bubble_int8.onnx` | Instance mask segmentation of speech bubbles (YOLO11-seg) | `images`: `[1, 3, 640, 640]` FP32 (0..1, pad 114) | `output0`: `[1, 37, 8400]` FP32<br>`output1`: `[1, 32, 160, 160]` FP32 | **STATIC (Fixed 640x640)** | **YES** (`[B, 3, 640, 640]`) |
| **PaddleOCR Det** | `models/ocr/paddle-v6-small/det/inference.onnx` | Text-line detection inside ROIs & free-text masks (DBNet) | `x`: `[1, 3, 736, 736]` FP32 (ImageNet norm, black pad) | `sigmoid_0.tmp_0`: `[1, 1, 736, 736]` FP32 | **STATIC (Fixed 736x736)** | **YES** (`[B, 3, 736, 736]`) |
| **PaddleOCR Rec** | `models/ocr/paddle-v6-small/inference.onnx` | Multi-language text recognition (PP-OCRv6) | `x`: `[1, 3, 48, W]` FP32 (Norm [-1..1], pad gray 128) | `softmax_0.tmp_0`: `[1, T, 6625]` FP32 | **PREDICTABLE** (Fixed H=48, W aligned to 16, min 320, max 1600) | **YES** (by padding W to fixed bucket/max 1600: `[B, 3, 48, 1600]`) |
| **MangaOcr Encoder** | `models/ocr/encoder.onnx` | Japanese vision transformer encoder (ViT) | `pixel_values`: `[1, 3, 224, 224]` FP32 (mean 0.5, std 0.5) | `last_hidden_state`: `[1, 197, 768]` FP32 | **STATIC (Fixed 224x224)** | **YES** (`[B, 3, 224, 224]`) |
| **MangaOcr Dec Init** | `models/ocr/decoder_init.onnx` | Autoregressive decoder initializer | `encoder_hidden_states`: `[1, 197, 768]`<br>`input_ids`: `[1, 1]` INT64 | `logits`: `[1, 1, V]`<br>`self_k/v`: `[4, 1, 4, 1, 64]`<br>`cross_k/v`: `[4, 1, 4, 197, 64]` | **STATIC** | **YES** (`[B, 197, 768]`, `[B, 1]`) |
| **MangaOcr Dec Step** | `models/ocr/decoder_step.onnx` | Autoregressive single-token step | `encoder_hidden_states`: `[1, 197, 768]`<br>`input_ids`: `[1, 1]`<br>`position_ids`: `[1, 1]`<br>`self_k/v_cache`: `[4, 1, 4, 256, 64]`<br>`cross_k/v_cache`: `[4, 1, 4, 197, 64]` | `logits`: `[1, 1, V]`<br>`self_k/v_out`: updated cache | **STATIC shapes, DYNAMIC loop count** (max 128 steps) | Challenging for batching due to variable sequence lengths per bubble (needs token padding/masking) |
| **AOT Inpaint 512** | `models/inpainting/aot-512.onnx` | Neural image inpainting (Fixed shape) | `image`: `[1, 3, 512, 512]` FP32 (0..1)<br>`mask`: `[1, 1, 512, 512]` FP32 (0 or 1) | `output`: `[1, 3, 512, 512]` FP32 | **STATIC (Fixed 512x512)** | **YES** (`[B, 3, 512, 512]`) |
| **AOT Inpaint Dyn** | `models/inpainting/aot.onnx` | Neural image inpainting (Dynamic shape) | `image`: `[1, 3, H, W]` FP32 (H, W <= 768)<br>`mask`: `[1, 1, H, W]` FP32 | `output`: `[1, 3, H, W]` FP32 | **DYNAMIC** (Multiples of 8/32) | **NO** (unless padded to fixed tiles) |

---

## 3. NPU Implementation Analysis & Recommendations

### Key Architectural Findings for NPU

1. **Massive Static Shape Alignment:**
   - Out of 10 model execution stages, **7 models have 100% STATIC tensor shapes** (Text Detector v4: 640x640, Panel Detector: 640x640, Bubble Segmenter: 640x640, PaddleOCR Det: 736x736, MangaOcr ViT Encoder: 224x224, MangaOcr Decoder Init, and AOT Inpaint 512: 512x512).
   - This means **Qualcomm QNN / MediaTek NeuroPilot / Samsung ENPU / Android NNAPI / ONNX Execution Providers** can compile and execute these models with fixed graph topologies, optimal memory layout, and zero runtime shape recompilations.

2. **Crop Batching Potential (Multi-Box Parallelism):**
   - On a typical comic page, there are 5–25 text bubbles.
   - Currently, the CPU/ONNX runtime iterates through each ROI sequentially:
     - OCR ViT Encoder runs `N` times with `[1, 3, 224, 224]`
     - PaddleOCR Det runs `N` times with `[1, 3, 736, 736]`
     - PaddleOCR Rec runs `M` lines with `[1, 3, 48, W]`
   - **NPU Batching Opportunity:** All `N` text crops from a page can be stacked into a single batched tensor `[B, 3, 224, 224]` or `[B, 3, 736, 736]`! This turns sequential inference into a single parallel NPU burst, dropping latency from hundreds of milliseconds to under 20ms.

3. **Page-Level Batching in Batch Mode:**
   - In pre-translation / batch mode, Stage-1 detection (Text Detector 640x640 + Panel Detector 640x640 + Bubble Segmenter 640x640) can run across multiple pages simultaneously (e.g. batch size `B = 4`), saturating NPU compute pipelines while memory usage remains bounded.

4. **Making PaddleOCR Rec 100% Static:**
   - PaddleOCR recognition currently has variable width `[1, 3, 48, W]`.
   - By standardizing input width to a fixed `W = 1600` (or 2 fixed buckets: `W = 640` and `W = 1600`) and padding with gray 128 (which normalizes to `0.0`), the recognition model becomes **100% static**, unlocking full NPU hardware compilation.
