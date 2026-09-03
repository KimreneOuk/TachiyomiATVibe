# PP-OCRv6 small det ONNX — integration design

> Date: 2026-06-23
> Status: Design (pending approval)
> Branch: `Pre-translation-feature`
> Related: [`docs/ocr-engine-notes.md`](../../ocr-engine-notes.md) (vertical-text limitation)

---

## 1. Goal

Improve vertical-text OCR for the PaddleOCR v6 small recognition (rec) engine by
replacing the **heuristic** vertical-column splitter with a **learned** text-line
detector: the PP-OCRv6 small **det** ONNX model.

### Why

`docs/ocr-engine-notes.md` documents that PP-OCRv6 small **rec** reads horizontal
text well but fails on vertical manga columns — a documented model limitation.
The current workaround (`RoiPageRecognitionEngine.detectVerticalColumns`) is an
ink-gap heuristic (dark-pixel fraction per x-column, merged across narrow gaps).
It is brittle: it merges multi-column bubbles, splits on inter-character gaps,
and cannot see tilted/curved text. A learned detector finds each text line
precisely, so rec gets one clean line at a time.

This does **not** fix the upstream PP-OCRv6-small rec limitation on *rotated*
text (the model still reads horizontal lines). It fixes the **line-segmentation**
problem that feeds it.

---

## 2. Model facts (from `inference.yml` + HF repo)

**Repo:** `PaddlePaddle/PP-OCRv6_small_det_onnx` — 4 files, all checked in under
`app/src/main/assets/models/ocr/paddle-v6-small/det/`:

| File | Size | Notes |
|------|------|-------|
| `inference.onnx` | 9,880,512 B | Valid ONNX (header `08 0a`, ir_version 10), 2.48M params |
| `inference.yml` | 885 B | Pre/postprocess contract (below) |
| `README.md` | 16,076 B | Model card |
| `.gitattributes` | 1,519 B | LFS rules |

**Architecture:** DB (Differentiable Binarization) text-line detector.

**Preprocess (`inference.yml` → `PreProcess.transform_ops`):**
1. `DecodeImage` — `img_mode: BGR`, `channel_first: false`
2. `DetResizeForTest` — dynamic resize to `736×736` (TRT shape hints: `1×3×736×736`)
3. `NormalizeImage` — mean `[0.485, 0.456, 0.406]`, std `[0.229, 0.224, 0.225]`, scale `1/255`, `order: hwc`
4. `ToCHWImage` — HWC → CHW
5. `KeepKeys` — keep `image`, `shape`

**Output:** a single probability (saliency) map `[1, 1, H, W]` (same H×W as the
resized input). **Not** boxes — the model is a heatmap emitter.

**Postprocess (`PostProcess`):**
- name: `DBPostProcess`
- `thresh: 0.2` — binarization threshold on the prob map
- `box_thresh: 0.45` — per-region score threshold (mean prob inside a contour)
- `max_candidates: 3000`
- `unclip_ratio: 1.4` — expand each detected polygon outward (text is slightly
  larger than the prob-map region)

DB postprocess = threshold → find contours → for each contour compute a min-area
polygon → score it (mean prob) → drop below `box_thresh` → unclip → keep up to
`max_candidates`.

---

## 3. Integration scope (decided with user)

**Stage 1 (bubble/text-region detection) is unchanged.** `detector-v4-s_int8.onnx`
(`OnnxPageTextDetector`) continues to find bubble / text_bubble / text_free
regions with labels 0/1/2. All downstream inpaint-mask + parent-bubble logic in
`RoiPageRecognitionEngine` stays exactly as-is.

**PP-OCRv6 det runs *inside* each detected ROI crop**, replacing the
`detectVerticalColumns` ink-gap heuristic. It finds individual text lines
(polygons) within the crop; each line is then fed to the rec engine (rotated for
vertical CJK), exactly as the current per-column path does today.

```
detector-v4-s (Stage 1, unchanged)
   │  → Detection{bbox, label 0/1/2}
   ▼
for each text Detection (label 1/2):
   crop = bitmap[bbox]
   if (vertical CJK && engine.prefersHorizontalText):
      ┌── OLD: detectVerticalColumns(crop)   [ink-gap heuristic]
      │   NEW: PaddleOcrV6Det.detect(crop)   [learned DB model]
      │       → List<LinePolygon>
      └── for each line (manga order, right→left):
             rotate 90° CCW → rec.recognize → concatenate
   else:
      rec.recognize(crop)
```

The det model is **only** invoked on the `splitVerticalColumns` path. Horizontal
text and native-vertical engines (ML Kit, MangaOcr) are untouched.

---

## 4. Components

### 4.1 `PaddleOcrV6DetPaths` (in `OnnxModelStore.kt`)

Mirror `PaddleOcrV6SmallPaths`:

```kotlin
data class PaddleOcrV6DetPaths(
    val detectionModel: File,
)
```

### 4.2 `OnnxModelStore` additions

- `paddleOcrV6DetAvailable()` / `paddleOcrV6DetAssetsAvailable()` — mirror the
  rec equivalents, checking `models/ocr/paddle-v6-small/det/inference.onnx`.
- `ensurePaddleOcrV6Det(): PaddleOcrV6DetPaths` — copies the det asset to
  `noBackupFilesDir/tachiyomiat-models/paddle-v6-small/det/inference.onnx` via
  the existing `copyIfNeeded` + `looksLikeValidOnnx` integrity path.
- Asset path: `models/ocr/paddle-v6-small/det/inference.onnx` (matches the
  downloaded layout).

### 4.3 `PaddleOcrV6DetEngine` (new file: `ocr/PaddleOcrV6DetEngine.kt`)

A self-contained ONNX det engine. Single responsibility: **Bitmap → text-line
polygons**.

Public API:
```kotlin
class PaddleOcrV6DetEngine : Closeable {
    fun initialize(modelFile: File)
    fun detectLines(crop: Bitmap): List<TextLine>   // polygons in crop pixel coords
    override fun close()
    fun reclaimPooledMemory()                        // mirror RoiOcrEngine contract
}

/** A detected text line: a min-area polygon (crop pixel coords) + axis bbox. */
data class TextLine(
    val polygon: FloatArray,   // 8 floats: x0,y0,x1,y1,x2,y2,x3,y3 (crop px)
    val bbox: IntArray,        // axis-aligned [x1,y1,x2,y2] (crop px)
)
```

Internals:
- **Preprocess:** resize crop to `736×736` (record scale factors for back-projection),
  BGR plane order, ImageNet normalize (mean/std from `inference.yml`), CHW layout.
  Reuse the single-pass pixel scan pattern from `OnnxPageTextDetector.preprocess`.
- **Inference:** one CPU ONNX run (`OnnxRuntimeProvider.createSessionOptions(forceCpu = true)`),
  consistent with all other translation ONNX sessions.
- **Postprocess (`DBPostProcess`):**
  1. Threshold prob map at `thresh=0.2` → binary mask.
  2. Contour extraction on the binary mask. **Contour finding on Android without
     OpenCV:** implement via connected-components (4- or 8-connectivity flood fill
     / two-pass label) over the binary `FloatArray`. This is the one non-trivial
     piece — see §5.
  3. For each component: min-area bounding box (rotated rectangle) → score =
     mean prob inside the contour → drop if `< box_thresh=0.45`.
  4. Unclip polygons by `unclip_ratio=1.4` (offset each edge outward; clip to crop).
  5. Cap at `max_candidates=3000`.
  6. Back-project polygons to crop pixel coords via the 736×736 scale factors.

### 4.4 `DbPostProcess` helper (new file: `ocr/DbPostProcess.kt`)

The DB postprocessing math (contours → min-area rect → score → unclip), isolated
as pure functions operating on `FloatArray` prob maps + dimensions. Pure so it is
unit-testable without a device or ONNX runtime — this is exactly the kind of code
that has been broken twice before by untested "fixes" (per
`docs/ocr-engine-notes.md`).

### 4.5 `RoiPageRecognitionEngine` wiring

- Add `private var paddleDet: PaddleOcrV6DetEngine? = null`.
- In `initialize()`: when `ocrModel == PADDLEOCR_V6_SMALL`, also
  `ensurePaddleOcrV6Det()` and `paddleDet.initialize(...)`.
- In `close()` / `reclaimPooledMemory()` / `forceReleaseNativeBuffers()`:
  forward to `paddleDet` alongside the existing sub-engines.
- **Replace `recognizeVerticalColumns`'s line source:** swap
  `detectVerticalColumns(crop)` (ink-gap heuristic) for
  `paddleDet?.detectLines(crop)`, with a **graceful fallback** to the existing
  heuristic if the det engine is null/uninitialized or returns no lines (so a
  det-model failure degrades to current behavior instead of empty OCR).
- **Polygon → line crop:** for each `TextLine`, take its axis-aligned `bbox`,
  crop that sub-region from the ROI crop, rotate 90° CCW, `engine.recognize(...)`,
  concatenate in manga reading order (right→left).
- The existing `MIN_COLUMN_WIDTH_PX` / `MIN_COLUMN_GAP_PX` constants and the
  `detectVerticalColumns` body are **removed** (replaced, not duplicated) — but
  kept as a documented fallback if the det engine is unavailable.

### 4.6 Native/memory guard

`detectLines` runs inside the existing `nativeGuard.withLock { ... }` critical
section in `analyze()` (it is a native ONNX call). No new lock needed — it sits
between `detect()` (Stage 1) and the per-ROI rec loop, both already under
`nativeGuard`. `close()` already acquires `nativeGuard`, so a det run cannot be
freed mid-flight.

---

## 5. The hard part: contour finding without OpenCV

Android has no built-in contour finder. Options:

| Approach | Verdict |
|----------|---------|
| **A. Connected-components on the binary mask** (two-pass or flood fill) | ✅ Chosen. Pure-Kotlin, ~50–80 LOC, well-understood. O(W·H) which is fine on a 736×736 mask (540K px). |
| B. Pull in OpenCV Android SDK | ❌ Rejected. +40 MB+ dependency, blows the memory budget on a 6 GB device. |
| C. Row/column projection instead of contours | ❌ Rejected. That *is* the ink-gap heuristic we're replacing. |
| D. Border-following (Suzuki-Abe) | Overkill; components → min-area-rect is sufficient for axis-aligned-ish text lines. |

DB's reference uses OpenCV `findContours` + `minAreaRect`. We implement the
equivalent with connected-components → per-component pixel covariance → principal
axis → oriented bounding box. For nearly-horizontal and nearly-vertical manga
lines this is equivalent to `minAreaRect` to within a pixel; for curved text we
fall back to the axis-aligned bbox (acceptable — rec resamples to 48px height
anyway).

**Unclip** (expand polygon by `unclip_ratio`): for an axis-aligned bbox this is a
simple uniform scale about the centroid; for an oriented box, offset along the
box's normal. Start with axis-aligned unclip (simplest correct behavior), upgrade
to oriented only if on-device A/B shows axis-aligned is losing glyph edges.

---

## 6. What does NOT change

- Stage-1 detection (`OnnxPageTextDetector`, `detector-v4-s`).
- The `Detection` data class and its label semantics (0/1/2).
- `PageInpaintingPlanner`, `PageInpaintingEngine`, all inpaint masking.
- `RenderColorEstimator`, `TranslationOverlayView`.
- Horizontal-text OCR path and native-vertical engines (ML Kit, MangaOcr).
- The rec engine itself (`PaddleOcrV6SmallEngine`) — unchanged.
- Memory reclamation contracts (`reclaimPooledMemory`, `forceReleaseNativeBuffers`).

---

## 7. Memory & performance constraints (per AGENT.md)

- **Det is CPU-only**, one 736×737 pass per ROI crop. On a page with N text
  regions, that's N det runs (was 0). Mitigation: the det model is tiny (2.48M
  params, ~10 MB); each run is one conv forward. Profile on-device with
  `translation_diagnostics` on; if N det runs per page is too slow on a 6 GB
  device, fall back to the heuristic for pages with many small ROIs.
- **Peak memory:** the 736×736 float tensor is `3·736·736·4 B ≈ 6.5 MB`. The prob
  map output is `736·736·4 B ≈ 2.2 MB`. Both are transient (freed in `finally`).
  No persistent native buffers like MangaOcr's KV cache.
- **Session lifecycle:** det session is created once in `initialize()` and reused
  across ROIs and pages, same as rec. Closed in `close()`.

---

## 8. Error handling (per AGENT.md — never suppress)

- Det session init failure → log at ERROR, leave `paddleDet = null`, fall back to
  the ink-gap heuristic. The page still gets OCR'd; the user sees current behavior
  rather than a crash. The failure is visible in logcat.
- Det run throws mid-ROI → catch at the per-ROI level, fall back to heuristic for
  that ROI, log at WARN. Other ROIs on the page use the det model.
- Det returns zero lines → fall back to heuristic for that ROI (a true negative is
  preferable to a heuristic false positive, but zero lines on a real text region
  is almost always a det-threshold issue — degrade rather than silently produce
  nothing).

---

## 9. Testing

Per AGENT.md testing policy + the process lesson in `docs/ocr-engine-notes.md`
("verify every change on-device with `translation_diagnostics` on"):

1. **Unit tests** for `DbPostProcess` (pure functions): synthetic prob maps with
   known rectangles → expected polygons/scores. This is the part that is
   testable without a device.
2. **Engine smoke test:** `PaddleOcrV6DetEngine.detectLines` on a fixture crop
   returns a non-empty list with plausible bboxes (instrumented test, needs the
   ONNX asset + runtime — may live in `androidTest`).
3. **On-device verification (mandatory, not optional):** translate a vertical-manga
   page with `translation_diagnostics` on; confirm the `[ocr_block]` log shows
   det-detected lines (not heuristic columns) and rec output is non-empty /
   correct for vertical columns. Compare against the current heuristic output on
   the same page. **This is the acceptance criterion** — per ocr-engine-notes,
   unit tests alone have twice masked on-device regressions in the rec decoder.

---

## 10. Out of scope (YAGNI)

- Replacing Stage-1 detection (explicitly declined by user).
- Using det polygons as direct rec input (explicitly declined).
- Curved-text oriented bounding boxes (axis-aligned first; revisit only if A/B
  shows glyph clipping).
- A settings toggle for det on/off (no evidence it's needed yet).
- Replacing the rec engine or changing its preprocessing.

---

## 11. Open questions to resolve during implementation

1. **Resize strategy for the det input.** `DetResizeForTest` in the reference pads
   to a multiple of 32 keeping aspect ratio, NOT a hard 736×736. The TRT shape
   hints list `736×736` as the middle of a dynamic range. Confirm exact behavior
   by inspecting the ONNX graph's input shape on first load; default to
   aspect-preserving resize to max-side 736 (round to 32).
2. **Connected-component connectivity (4 vs 8).** 8-connectivity is standard for
   DB; confirm against a real prob map on-device.
3. **Fallback threshold.** How many det lines must be found before trusting det
   over the heuristic? Start with "≥1 line → use det; 0 lines → heuristic".

These are implementation details to be settled in the plan / during coding with
on-device checks, not design blockers.
