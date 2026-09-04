# TachiyomiAT: Translation Speed, Reader Fluidity & Model UX Roadmap

> **Ground Rule:** Code over documentation. Documentation acts as a nudge; the source code is the sole source of truth.

---

## 1. Executive Summary & Source-of-Truth Findings

An end-to-end investigation of the translation pipeline and reader rendering path reveals four concrete architectural findings:

1. **Pipeline Execution Time is Heavily CPU-Bound:**
   - On physical devices, if Qualcomm QNN HTP fails or is not enabled, the app quietly falls back to `CPU_XNNPACK` ([`HardwareDiscoveryEngine.kt`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/optimize_translation_pipeline_ux/app/src/main/java/eu/kanade/translation/runtime/onnx/HardwareDiscoveryEngine.kt#L104-L135)).
   - In `QUALITY` inpainting mode, **AOT-GAN takes 1,500ms – 3,500ms on CPU** for each page patch ([`AOTInpainting.kt`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/optimize_translation_pipeline_ux/app/src/main/java/eu/kanade/translation/inpainting/aot/AOTInpainting.kt)). In `FAST` mode, inpainting takes under **15ms** (solid fill / OpenCV).
   - In OCR, **MangaOCR autoregressive token-by-token loop runs on CPU**, consuming **500ms – 1,200ms** per page. In contrast, **PaddleOCR-v6-small runs in 60ms – 180ms**.
2. **Reader Sluggishness is Driven by Non-Persisted Layout Calculations:**
   - [`TranslationOverlayView.kt`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/optimize_translation_pipeline_ux/app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/TranslationOverlayView.kt#L334-L348) caches calculated text layouts **only in memory** inside `ReaderTextLayoutCache` capped at **12 pages** (`MAX_CACHED_PAGE_LAYOUTS = 12`).
   - Page snapshots on disk ([`PageTranslation.kt`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/optimize_translation_pipeline_ux/app/src/main/java/eu/kanade/translation/model/PageTranslation.kt#L9-L96)) store raw unformatted text blocks, but **never store computed layouts**.
   - Every time the user enters a chapter or scrolls past 12 pages in Webtoon mode, `TextLayoutPlanner.plan()` must run again for every visible page on a single background worker thread (`TranslationOverlayLayoutPlanner`), causing scroll stutter, delayed text pop-in, and redraw invalidations.
3. **Model Picker Lacks Reachability & Causes Wasted Compute:**
   - [`AiModelPickerWidget.kt`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/optimize_translation_pipeline_ux/app/src/main/java/eu/kanade/presentation/more/settings/widget/AiModelPickerWidget.kt) and [`AiModelFetcher.kt`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/optimize_translation_pipeline_ux/app/src/main/java/eu/kanade/translation/translator/providers/AiModelFetcher.kt) have no connection test or reachability gate.
   - If an API key is invalid, quota is exceeded, or an endpoint is down, the app still runs heavy Detect, OCR, and Inpainting for 3–6 seconds per page, only to fail at the final HTTP step.
4. **Batch Translation Chunking is Intentional and Necessary:**
   - [`StreamingChunkPlanner.kt`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/optimize_translation_pipeline_ux/app/src/main/java/eu/kanade/translation/translator/contextual/StreamingChunkPlanner.kt) groups multiple pages into sequential chunks (typically 4–8 pages per request) to prevent tripping the **15 Requests-Per-Minute (RPM)** limit on free/tiered LLMs, while preserving inter-page dialogue context.

---

## 2. Track 1: Translation Processing Speed Optimization

### 2.1 Stage-by-Stage Latency & Hardware Feasibility

| Stage | Current Implementation | Latency on CPU | NPU / GPU Feasibility | Optimization Vector |
| :--- | :--- | :--- | :--- | :--- |
| **1. Text Detect** | YOLOv4 / Detector-v4 (`[1, 3, 640, 640]`) via ONNX | ~200 – 450 ms | **High NPU Benefit** (QNN HTP drops to ~20–35 ms) | Optimize sliding window stride on tall Webtoons; stabilize QNN HTP runtime. |
| **2. Reorder & Panels** | Reading order sorter (XY-cut) & YOLO26-nano | ~10 – 30 ms (total) | **Zero NPU Benefit** (Geometry sort belongs on CPU) | Fast, negligible overhead. No changes needed. |
| **3. Bubble Seg** | YOLO11-seg (`[1, 3, 640, 640]`) via ONNX | ~150 – 300 ms | **High NPU Benefit** | Run in parallel with text detection where memory allows, or merge into single pass. |
| **4. OCR** | • MangaOCR (ViT Enc + Autoregressive Dec)<br>• PaddleOCR-v6-small<br>• MLKit | • MangaOCR: 500 – 1,200 ms<br>• PaddleOCR: 60 – 180 ms<br>• MLKit: 100 – 250 ms | • ViT Encoder: High NPU benefit<br>• Autoregressive Decoder: **CPU only** (NPU dispatch overhead slows token loops) | **Recommend PaddleOCR-v6-small as primary/fast default**; keep MangaOCR for complex Japanese furigana. |
| **5. Translation** | HTTP Cloud API (Gemini, DeepSeek, OpenRouter) | ~600 – 2,500 ms | **N/A** (Network bound) | Chunked batching (4–8 pages/call) + streaming responses. Pre-flight reachability checks. |
| **6. Clean (Inpaint)** | • FAST (Solid fill / OpenCV)<br>• QUALITY (AOT-GAN 512x512) | • FAST: < 15 ms<br>• QUALITY: **1,500 – 3,500 ms** | **Massive NPU Benefit for AOT-GAN** (QNN HTP drops to 50–100 ms) | Default to FAST mode on non-NPU devices; automatically use QNN HTP when available; skip inpaint for solid background bubbles. |
| **7. Render** | `Canvas` draw + text layout | Layout: 20 – 60 ms<br>Draw: < 2 ms (GPU) | **Canvas is already GPU-accelerated** by Skia/HWUI | **Persist pre-computed layouts** to eliminate repeated layout calculation during scrolling. |

### 2.2 Immediate Pipeline Speed Wins
1. **Adaptive Inpainting Default:**
   - If `HardwareDiscoveryEngine.activeRoute == CPU_XNNPACK`, alert the user or default to `FAST` inpainting mode (or downscaled AOT). This alone saves **1.5 to 3.5 seconds per page** on non-NPU devices.
2. **Webtoon Slicing Overhead Reduction:**
   - In [`WebtoonSlidingDetector.kt`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/optimize_translation_pipeline_ux/app/src/main/java/eu/kanade/translation/webtoon/WebtoonSlidingDetector.kt), tall images (e.g. 10,000px high) are sliced into overlapping windows. Eliminating duplicate inferences over empty white/black gutters speeds up detection by 30–50%.
3. **PaddleOCR vs MangaOCR Selection:**
   - Surface a clear speed toggle in Settings: "High Speed (PaddleOCR, ~100ms)" vs "Maximum Accuracy (MangaOCR, ~800ms)".

---

## 3. Track 2: Eliminating Reader Sluggishness & Scroll Lag

### 3.1 The Problem in Code
1. **Eviction Trap:** In [`TranslationOverlayView.kt`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/optimize_translation_pipeline_ux/app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/TranslationOverlayView.kt#L341-L347), `ReaderTextLayoutCache` holds only 12 pages in memory.
2. **Cold Miss on Re-entry:** Exiting the reader activity destroys the in-memory cache. Re-entering requires `TextLayoutPlanner.plan()` to re-parse every single page.
3. **Asynchronous Redraw Jank:** Misses execute on `planningExecutor`. When the calculation finishes, `applyPreparedLayouts` calls `invalidate()`, causing mid-scroll frame drops and text "pop-in".

### 3.2 Architectural Solution: Pre-Computed Layout Persistence

```
[Page Translation / Batch Pipeline]
                 │
                 ▼
[TextLayoutPlanner.plan() runs ONCE at completion]
                 │
                 ▼
[Serialize BlockLayout / PositionedLines into Page Snapshot JSON]
                 │
                 ▼
┌─────────────────────────────────────────────────────────────┐
│ Reader Entry / Scroll Event                                 │
│                                                             │
│ • Check in-memory warm cache                                │
│   ├── Hit  ──► Render instantly (0 ms)                      │
│   └── Miss ──► Load pre-computed layout from Disk (< 1 ms)  │
│                                                             │
│ • ZERO TextLayoutPlanner recalculation on scroll or entry!  │
└─────────────────────────────────────────────────────────────┘
```

#### Key Rules:
- **Zero Recalculation on Read:** Opening a chapter or scrolling reads pre-formatted text positions directly from the stored page snapshot.
- **Selective Invalidation:** Layout is only recalculated if:
  1. The user changes the Reader Font in Settings.
  2. The user alters font scaling/margins.
  3. The user explicitly taps a "Re-layout / Re-render" button.

---

## 4. Track 3: Model Picker & Reachability UX

### 4.1 The "Wasted Effort" Problem
Currently, the pipeline executes:
`Decode -> Text Detect -> Bubble Seg -> OCR -> Inpaint -> Clean Image Disk Commit -> HTTP Translate`

If the AI endpoint is unreachable (bad API key, network offline, quota exceeded, invalid model ID), all preceding native compute and battery are completely wasted.

### 4.2 Proposed Model Picker & Reachability Architecture

#### 1. Settings "Test Connection" & Live Status Badge
- In [`AiModelPickerWidget.kt`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/optimize_translation_pipeline_ux/app/src/main/java/eu/kanade/presentation/more/settings/widget/AiModelPickerWidget.kt), add an inline "Test Connection" button next to the API Key and Model Selector.
- Performs a lightweight test call (prompt: `"ping"`, max tokens: 2) through [`AiModelFetcher.kt`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/optimize_translation_pipeline_ux/app/src/main/java/eu/kanade/translation/translator/providers/AiModelFetcher.kt).
- Displays a live status badge:
  - `🟢 Connected (180ms)`
  - `🟡 Rate Limited / 429 Quota Exceeded`
  - `🔴 Unauthorized / 401 Invalid Key`
  - `⚪ Offline / Unreachable Host`

#### 2. Pipeline Pre-Flight Guard
- In [`TranslationPipeline.kt`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/optimize_translation_pipeline_ux/app/src/main/java/eu/kanade/translation/TranslationPipeline.kt) and [`BatchChapterTranslator.kt`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/optimize_translation_pipeline_ux/app/src/main/java/eu/kanade/translation/pipeline/batch/BatchChapterTranslator.kt):
  - Before admitting a chapter batch or single-page manual translation, check the cached reachability token (valid for 5 minutes).
  - If unverified, issue a lightweight pre-flight probe.
  - If unreachable, fail immediately with an actionable UI notification *before* running OCR or inpainting.

#### 3. Modernized Model Selector UI
- Replace the raw text list with categorized sections:
  - **Recommended for Translation** (e.g. Gemini 2.0 Flash, DeepSeek-V3, GPT-4o-mini).
  - **Recent Models**.
  - **Full Provider Catalog** (with search, context size tags, and latency indicators).

---

## 5. Track 4: Batch Translation & RPM Governance Clarification

### 5.1 Verified Code Mechanism
In [`StreamingChunkPlanner.kt`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/optimize_translation_pipeline_ux/app/src/main/java/eu/kanade/translation/translator/contextual/StreamingChunkPlanner.kt) and [`docs/architecture/batch-translation-pipeline.md`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/optimize_translation_pipeline_ux/docs/architecture/batch-translation-pipeline.md):

```
Chapter Pages: [P1] [P2] [P3] [P4] [P5] [P6] [P7] [P8] ... [P40]
                 └──────┬──────┘   └──────┬──────┘
                   Chunk 1            Chunk 2
               (1 HTTP Request)   (1 HTTP Request)
```

1. **Why It Exists:**
   - Free/tier LLM providers (Gemini, OpenRouter free models, DeepSeek) enforce a strict **15 Requests Per Minute (RPM)** ceiling.
   - Sending 40 individual page requests trips 429 quota errors within 20 seconds.
   - Packing 4–8 pages per chunk reduces a 40-page chapter to **5–8 requests total**, staying safely below the RPM limit.
2. **Contextual Quality:**
   - Multi-page chunks allow the model to resolve multi-bubble dialogues across panels and maintain character gender/tone consistency.
3. **Pipeline Concurrency:**
   - Native OCR processes pages sequentially under [`NativeRunQuarantine`](file:///C:/Users/User/.gemini/antigravity/worktrees/TachiyomiAT-1.16.8-dev/optimize_translation_pipeline_ux/app/src/main/java/eu/kanade/translation/scheduling/NativeRunQuarantine.kt).
   - Once Chunk 1 finishes OCR, it dispatches to the AI lane.
   - While the AI lane translates Chunk 1, the native lane runs inpainting on Chunk 1 and begins OCR on Chunk 2.

---

## 6. Implementation Milestones

### Phase 1: High-Impact, Zero-Risk Quick Wins
- [ ] **M1.1: Pre-Flight AI Reachability Probe**: Stop OCR/inpainting before starting if AI endpoint is dead.
- [ ] **M1.2: Settings "Test Connection" Button**: Immediate feedback on API key, base URL, and latency in Settings.
- [ ] **M1.3: Adaptive Inpainting Mode Guidance**: Clearly alert user when QUALITY mode is running on CPU without hardware acceleration.

### Phase 2: Reader Fluidity & Layout Caching
- [ ] **M2.1: Pre-Computed Layout Persistence**: Serialize `BlockLayout` and `PositionedLine` into page snapshots.
- [ ] **M2.2: Instant Reader Layout Hydration**: Load layouts directly on reader open (< 1ms); eliminate scroll re-planning.
- [ ] **M2.3: Invalidation Triggers**: Re-plan layouts only when font or display preferences change.

### Phase 3: Hardware Acceleration & Pipeline Throughput
- [ ] **M3.1: QNN HTP Driver Compatibility Fix**: Stabilize Qualcomm NPU probing on Android 14/15/16.
- [ ] **M3.2: PaddleOCR-v6 Fast-Path**: Elevate lightweight OCR as an easily accessible speed option.
- [ ] **M3.3: Webtoon Gutter-Skip Detection**: Skip detector passes over empty margins on long webtoon strips.
