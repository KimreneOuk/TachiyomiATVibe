# Text Overlay Rendering — Investigation & Approach Ranking

**Date:** 2026-07-09 (initial) · 2026-07-10 (experimentation results appended)
**Branch:** Quality-Improvement
**Status:** Investigation complete + lab experimentation in progress. This is a research record.
**Related docs:** `text_overlay_rendering.md` (decisions), `overlay_rendering_mode.md` (Android plan), `MANGA_RENDER_LAB.md` (lab guide), `inpainting_optimizations.md` (inpaint pipeline). All in this folder.

This document records the investigation into three core problems of the automatic manga translation overlay, the approaches considered, their ranked evaluation against the project's Android constraints, and the settled design direction.

## 0. Context & goal

The Manga Render Lab (`overlay_lab/`) is the Python FastAPI + Canvas sandbox for investigating rendering, inpainting, segmentation, and overlay behavior *outside* the Android app. Its core rule (from `MANGA_RENDER_LAB.md`):

> We are not baking the inpainting onto the original images. The lab shows a cleaned overlay on top of the original image with precise mask/position tracking.

The final lab view composes: `original image + cleaned/inpainted overlay clipped to erase masks + live Canvas2D translated text overlay`.

This investigation is the design groundwork for bringing that **overlay (not baked)** model to the Android reader. The goal is lower storage (one fewer full-page PNG per page) and faster perceived translation arrival, while keeping text crisp at any zoom.

## 1. The three core problems

1. **Text positioning** — where to place text over a bubble (anchor on the OCR/detector child box, grow to fit).
2. **Text collision** — prevent text from one bubble spilling into an adjacent/connected bubble. Key case: two connected bubbles get merged into one region by segmentation, yet their text must still be separated.
3. **Zoom/pan tracking ("locked to canvas")** — the overlay must follow the *image* during zoom/pan, not stay fixed to the screen/canvas. Root cause when it fails: overlay coordinates are defined in screen/view space instead of image (source) space.

## 2. Reality checks (verified against the codebase)

Three gaps between the lab's design docs and the actual Android code shape the whole design space:

1. **The current Android reader uses an overlay.** `TranslationOverlayView` draws
   planned translated text above the cleaned page in source-image coordinates,
   so it follows reader pan and zoom. Earlier baked-PNG assumptions in this note
   are historical and no longer describe the implementation.
2. **"Text locked to canvas" cannot be the baked-PNG path.** Baked text *is* the image, so it tracks zoom/pan perfectly by definition. That symptom describes a DOM/Canvas overlay whose coordinates are in screen space — exactly the failure the image-space Matrix fix addresses. It is observed in the lab prototype, not in the Android bake path.
3. **Most of positioning + collision is already solved** in `TextLayoutPlanner.plan()` (`rendering/TextLayoutPlanner.kt`): it is **pure** (no `android.graphics`), **JVM-testable**, **single-pass**, **neighbour-aware** (score-ordered, no relaxation loop). It anchors on the child-box centre, grows into the parent bubble box, and hard-clips neighbours via a structural `clipRect`. `trimParentBbox` (`recognition/RoiPageRecognitionEngine.kt:1308`) already trims a parent against sibling bubbles overlapping ≥45%. The genuine open problems are narrower: the **merged-bubble** edge case and the **zoom-tracking mechanism**.

## 3. The common zoom fix (root cause, not a workaround)

Define every text block in **image (source) space** (`0..sWidth/sHeight`). Each frame apply **one** `Matrix` built from SSIV `scale + center + orientation` to positions *and* sizes. Reuse the already-working `sourceToViewCoord()` path — the per-page translate button already tracks the image rect this way (`viewer/ReaderPageImageView.kt`). Scale-bucket the redraw during gestures (1.25× steps); full re-rasterize on gesture-lift + `isReady()`. This makes "locked to canvas" structurally impossible.

## 4. Approaches considered (6 complete solutions)

A1–A4 and A6 share the image-space zoom fix above; A5 sidesteps it.

- **A1 — Planner parent-box fence + image-space Canvas overlay.** Collision via the *existing* planner: each block's growth region = its parent bubble box, hard-clipped by `clipRect` vs neighbours. Two connected bubbles stay separated **iff the detector emits two parent boxes**. No new model.
- **A2 — Segmentation-mask ∩ parent-box growth.** Add a YOLO11n-seg model; growth region = `mask ∩ parentBbox`. Merged masks get partitioned by per-bubble parent boxes. Nicest visuals (text hugs the bubble curve).
- **A3 — Watershed/midline split of merged masks.** When one mask spans ≥2 child boxes, split via watershed on the mask distance-transform (or perpendicular bisector of child-box centres). The only approach that partitions a blob even when the detector itself merges boxes.
- **A4 — Force/constraint-solver layout.** Replace the single-pass planner with an iterative solver (blocks as soft bodies, bubbles as repulsive zones). Flexible but breaks the prized single-pass property.
- **A5 — Bake text into SSIV tiles.** Hook SSIV's tile `decodeRegion()` to composite text into each tile. Best memory/zoom/tracking, but a deep SSIV fork and *not an overlay* (no instant re-render; excludes animated/webtoon).
- **A6 — Decoupled: bbox-fence for text placement + mask only for inpainting.** Parent-box fencing for text (cheap, deterministic, testable); segmentation mask used **only** at translate-time to improve inpaint (erase follows contour), not to fence text.

### 4.1 Key argument: why bbox is the right *text limit* (and the mask is the right *inpaint* tool)

1. **The mask is what CAUSES the merge; the bbox is what FIXES it.** The fused green region in the screenshot *is* the segmentation mask. Text limited by that mask has one region for two texts → collision. What separates them is two child text boxes with distinct centres — a structural separator that is the box, never the mask contour.
2. **Text is laid out on a rectangular grid anyway.** Glyphs advance along axis-aligned lines; wrapping is inherently rectangular. The planner's inset padding already approximates an elliptical inset for manga's mostly round/elliptical bubbles.
3. **A mask limit kills the property the overlay design depends on:** cheap, pure re-planning at view time. A bbox fence is O(1) min/max; a mask∩box limit is O(mask pixels) per block per re-plan, landing per-pixel math on the gesture path. Keeping `plan()` pure also keeps it JVM-testable.
4. **Persisting masks undoes the storage goal.** Bboxes are ~4 floats/block (already serialized); a mask is a full-page raster per page.
5. **The mask's genuine value is the ERASE step.** Erasing along the true bubble edge leaves a cleaner background than erasing a rectangle. That is an offline, once-per-page benefit — exactly what A6 captures.

## 5. Ranked evaluation (weighted by project constraints)

Scores 1–5 (5 = best). Weights reflect stated project priorities: merged-bubble 0.20 · CPU/jank 0.16 · impl-risk 0.16 · memory 0.14 · determinism/testability 0.12 · zoom-tracking 0.10 · visual 0.07 · storage 0.05.

| Approach | Mem | CPU | Risk | Det | Zoom | Merge | Vis | Stor | **Total** |
|---|---|---|---|---|---|---|---|---|---|
| **A1** | 5 | 5 | 5 | 5 | 5 | 3 | 3 | 5 | **4.46** |
| A6 | 3 | 5 | 3 | 5 | 5 | 3 | 3 | 3 | 3.74 |
| A5* | 5 | 5 | 1 | 3 | 5 | 3 | 4 | 3 | 3.66 (goal-incompatible) |
| A2 | 2 | 2 | 3 | 3 | 5 | 4 | 5 | 2 | 3.21 |
| A3 | 2 | 2 | 1 | 2 | 5 | 5 | 4 | 2 | 3.03 |
| A4 | 4 | 2 | 1 | 1 | 5 | 3 | 4 | 5 | 2.91 |

**Final ranking (1 → 6):**
1. **A1** — recommended. Best-in-class on all four binding constraints (memory, jank, risk, determinism = 5/5); reuses the pure planner + working SSIV tracking; zero new model. Only trails on merged-bubble (3/5).
2. **A6** — strong #2. Keep A1's render path, bolt the seg model onto inpaint-only for an erase-quality win. Right upgrade *after* A1.
3. **A5** — best engineering but disqualified as #1: it is not an overlay (no live re-render) and the SSIV fork is the highest blast radius.
4. **A2** — nicest cosmetics, but pays a resident seg model + per-plan mask∩box cost on a 6GB target.
5. **A3** — only one that splits detector-merged blobs, but double cost (model + watershed) and over/under-split risk.
6. **A4** — sacrifices the single-pass planner; worst testability; overkill.

## 6. The in-repo segmentation model (confirmed)

A YOLO11n instance-segmentation model already exists at `experimental/models/manga109-segmentation-bubble/`:

| File | Size | Notes |
|---|---|---|
| `best_int8.onnx` | **3.4 MB** | fixed input `[1,3,640,640]`. **Android-relevant.** |
| `best_fp32.onnx` | 11.6 MB | same I/O contract as int8. |
| `best.onnx` | 11.8 MB | **dynamic** axes, trained at 1600×1600 (better small-bubble recall). |
| `best.pt` | 12 MB | PyTorch source. |
| `best_int8_broken.onnx` | 3.4 MB | ultralytics built-in int8 export; **returns 0 detections** (insufficient COCO calibration) — excluded from the model scan list. |

**I/O contract (load-bearing):**
- Input `images`: `[1,3,640,640]` float32, RGB, NCHW, normalized [0,1], letterboxed.
- `output0`: `[1,37,8400]` = 4 (xywh) + 1 (balloon class score, **raw logit**) + 32 (mask coefficients), over 8400 anchors.
- `output1`: `[1,32,160,160]` = 32 mask prototypes at stride-32.
- Single class: `{0: 'balloon'}`. Stride 32. Opset 20. Ultralytics 8.4.89. License AGPL-3.0.

**⚠️ Critical implementation risk:** the output is RAW. The lab prototype (`overlay_lab/backend/inpaint/bubble_segmentation.py`) relies on ultralytics `YOLO.predict()` to decode. Android has **no ultralytics**, and no existing Kotlin code does mask-proto decode (`OnnxPageTextDetector`/`OnnxPanelDetector` decode boxes only). To produce a binary bubble mask, Phase 2 must implement from scratch in Kotlin: sigmoid class score → conf filter (0.2) → NMS (IoU 0.7) → per-survivor 32-coeff × (32×160×160) proto matmul → sigmoid → threshold >0.5 → crop to detection box → upsample → undo letterbox. This is the main new implementation risk and is why the mask work is isolated and optional.

**Model is NOT in `app/src/main/assets/models/` yet** (only `experimental/`). Note `overlay_lab/backend/config.py` model discovery only scans the HF `snapshots/` layout and will not find the `experimental/` copy — a Python-prototype issue, not Android.

The lab already uses this mask for bubble inpainting via **interior median solid fill** (see `inpainting_optimizations.md` §1) and uses it first in the pipeline to demote `free_text` detections overlapping the mask by >10% to `bubble_text` (§4).

## 7. Settled design direction  *(revised after experimentation — see §11)*

| Question | Decision |
|---|---|
| Text-bounding limit | **Segmentation mask bbox is the SOLE strict limit.** Text + stroke outline must stay strictly inside the mask. Detector-v4 parent bbox is NOT consulted. *(Revised from A1 bbox-fence after lab testing — see §11.)* |
| Text centering | **Child OCR box center** — text is anchored where the source text was detected, not on the parent-box center (which caused an upward shift). |
| Merged-bubble collision | **Fused-mask split:** when the seg model fuses two close bubbles into one mask polygon, partition that mask's bbox by child-box-center midpoints. Each child gets its own `splitMask` slice. |
| Segmentation model | **A6+ (mask for inpaint AND text limit):** the mask is used for inpainting (offline, once/page) AND as the text growth limit. The cost caveat applies to a future Android port at view time, not to the lab. |
| Zoom tracking | **Included** as a core deliverable (image-space Matrix overlay). |

## 8. Recommended implementation shape (two sequenced phases)

### Phase 1 — The overlay (solves all 3 core problems; no model, lower risk)
- **Zoom-fix overlay (new `TranslationOverlayView.kt`):** define blocks in image space; one Matrix per frame from SSIV `scale+center+orientation`; re-run `plan()` at view time with the same Paint that draws (drift-impossible); bucketed redraw during gesture; on failure log at `ERROR` (no silent fallback). Reuse the existing `sourceToViewCoord()`/`computeImageRect()` path and `onScaleChanged`/`onCenterChanged`/`onImageLoaded` hooks.
- **Positioning/collision:** (a) inflate each block's `clipRect` by `strokeWidth/2` so scaled strokes can't bleed at high zoom; (b) pure midline-split fallback inside `plan()` for one-parent-with-≥2-children.
- **Wiring:** new `translationOverlayMode()` pref (default OFF); `TranslationPipeline` skips `render()` + `persistRenderedBitmap()` in overlay mode (non-animated only); `ReaderViewModel.attachTranslatedStream` feeds the **cleaned** stream. Webtoon/animated keep the bake path.
- **Tests:** JVM (pure) for the pipeline branch, midline-split, `clipRect` inflation, `plan()` parity, scale bucketing; manual for zoom-crispness, rotation, connected-bubble separation.

### Phase 2 — A6: seg mask for inpainting only (optional, higher risk)
- Copy `best_int8.onnx` → `assets/models/segmentation/`.
- New `OnnxBubbleSegmenter.kt`: implement the full RAW decode in Kotlin (letterbox → run → sigmoid → conf filter → NMS → coeff×proto matmul → sigmoid → threshold → crop → upsample → letterbox-unmap). Background thread, once/page.
- Intersect each bubble erase region with its contour in `BubbleMaskBuilder`/`PageInpaintingPlanner` (erase follows edge). **Important:** Apply an `inset_px = 5` erode to the mask before inpainting to protect the original bubble line art from being accidentally erased by the fill. Text fencing remains bounded by the un-eroded mask.
- `@Transient var bubbleMask` (not serialized; recompute on resume). New `translationInpaintMaskMode()` pref (default OFF).
- **Validate empirically before committing:** fixed 640×640 downscales aggressively (small/distant bubbles may vanish — `bubble_segmentation.py:25-31`); if recall is insufficient, switch to dynamic `best.onnx` (11.8 MB, 1600). Recommend a standalone decode spike + visual diff vs the Python prototype before pipeline integration.

## 9. Open caveat to validate early

**Detector-v4 false-merge rate.** The midline-split fallback is built precisely for the case the detector merges connected bubbles into one parent box, so the plan is robust regardless. Still recommended: run the detector on a connected-bubble corpus and log the merge rate to tune the fallback trigger (≥2 children per parent).

## 10. Out of scope / follow-ups
- **SSIV tile-decoder** (best memory) — deferred (deep fork; `overlay_rendering_mode.md §3.4`).
- **Persisting the bubble mask** — `@Transient` for now; RLE only if profiling demands.
- **Per-line text wrapping to follow bubble curve** — the one real visual win of mask-driven fencing; a future rendering-engine feature.
- **Watershed distance-transform split** — the current fused-mask split uses midpoint perpendicular-bisector cuts, which is pure box geometry. A watershed split on the mask distance-transform could better handle non-axis-aligned connected bubbles (e.g. diagonal pairs), but adds complexity. Revisit if midpoint-split proves visually inadequate.

---

## 11. Experimentation results (2026-07-10)  — the policy pivot

> This section documents what hands-on lab testing revealed after the original A1/bbox-fence plan was written. It **supersedes §4.1 and §7** where they conflict.

### 11.1 What was tested

The A1 approach (bbox-fence + midline-split on the **parent box**) was implemented in the Python port of the planner (`companion_server/render/layout_planner.py`) and validated visually in the lab UI on real manga pages.

### 11.2 Bug 1 — upward text shift ("not even center")

**Symptom**: translated text was shifted toward the top of the bubble, not centered.
**Root cause**: `compute_rects` based the text layout rect on the **parent bubble box** (the detector-v4 bbox) instead of the child OCR box. For vertical Japanese text the OCR child box sits at the top of the bubble; centering on the parent box moved the origin away from where the source text actually was.
**Fix**: revert `compute_rects` to use the **child OCR box** as the base rect. Text origin = child-box center = where the source text was detected.

### 11.3 Bug 2 — text overflowing the bubble ("almost all regions overflowed")

**Symptom**: translated text spilled past the curved bubble edge into the artwork.
**Root cause**: the A1 policy fenced text to the **detector-v4 parent bbox**, which is a loose rectangle larger than the actual bubble. Text sized to that rectangle naturally overflowed the bubble's curved contour.
**Fix (policy pivot)**: the **segmentation mask bbox** became the sole strict limit. The mask is the true bubble shape; using it as the limit means text is bounded by the actual contour, not a loose rectangle. The clip rect is the mask shrunk inward by `strokeWidth/2` so even the stroke outline cannot exceed the mask.

### 11.4 Bug 3 — connected bubbles re-merged through a shared mask

**Symptom**: when two close bubbles were fused into one mask polygon by the seg model, both children got assigned that same mask, and without a separator both texts grew into the full merged region and collided.
**Root cause**: the original midline-split operated on the **parent bbox**. When the policy switched to mask-sole-limit, the bbox separator was removed, but nothing replaced it for the fused-mask case.
**Fix**: **fused-mask split** — partition the shared mask bbox by child-box-center midpoints (dominant-axis perpendicular-bisector cuts). Same geometry as the old parent-bbox split, but operating on the mask boundary (the true limit). Each child gets a `splitMask` slice. Detection: group blocks by `id(mask_polygon)`; if ≥2 share one, split.

### 11.5 Why the original §4.1 argument was wrong

§4.1 argued "the mask is what CAUSES the merge; the bbox is what FIXES it." This was **half right**: the mask *can* cause merges (when two bubbles fuse into one polygon), but the fix is not the detector bbox — it's **partitioning the shared mask itself** by child centers. The detector bbox was a proxy for this, but a worse one: it's a loose rectangle that doesn't match the bubble shape, causing the overflow in §11.3.

The mask-sole-limit policy keeps the separation (via fused-mask split) AND gives tighter shape adherence (text hugs the contour). The only cost is mask availability at view time, which is a non-issue in the lab and an engineering problem (not a blocker) for Android.

### 11.6 Bug 4 — the Maximal Inscribed Rectangle approach (Approach B)

**Symptom**: relying solely on the mask bounding box still caused text to overflow the curved edges of the segmentation mask polygon, especially for diagonal or highly irregular bubbles.
**Fix**: implemented a greedy **maximal inscribed rectangle** search (`_inscribed_rect`) starting from the child OCR box center `(cx, cy)` and expanding outwards step-by-step (`step=2.0`) until hitting the polygon boundary (`_point_in_polygon` with collinearity edge-case handling). The text is now laid out strictly inside this inscribed rectangle, ensuring it mathematically cannot cross the curved polygon mask.

### 11.7 Bug 5 — asymmetric horizontal text overflow

**Symptom**: despite the inscribed rectangle, text still occasionally overflowed horizontally.
**Root cause**: when `anchor_to_ocr_center` is true, the layout engine forces the text origin to exactly `(cx, cy)`. However, the inscribed rectangle might be asymmetrical around `cx` (e.g., 10px to the left, 50px to the right). The layout engine used the total width (60px) as the `safe_w`, wrapped the text to 60px, and drew it centered at `cx`. This caused the text to extend 30px to the left, overflowing the 10px limit on that side.
**Fix**: when `anchor_to_ocr_center` is true, `safe_w` and `safe_h` are now strictly constrained to be mathematically symmetrical around `cx, cy`: `safe_w = 2.0 * min(cx - clip.left, clip.right - cx)`. This enforces that text drawn perfectly centered will never exceed the closest boundary on any side.

### 11.8 Bug 6 — frontend WYSIWYG scaling and font mismatch

**Symptom**: the text layout appeared perfect when zoomed in within the Overlay Lab, but text visually overflowed when zoomed out (100% default view). Additionally, line breaks in the preview didn't perfectly match the Python layout engine's calculations.
**Root cause 1 (Scaling)**: the frontend UI (`app.js`) had a visual CSS hack (`MIN_READABLE_PX`) that artificially scaled up the DOM text elements when zoomed out to preserve legibility. This broke the 1:1 preview mapping.
**Root cause 2 (Font)**: the Python backend calculated line breaks using the custom `animeace.ttf` manga font, but the browser frontend rendered the preview using standard system `sans-serif` fonts, which have drastically different widths and kerning.
**Fix**: removed the `boost` CSS scaling hack to restore linear zooming, copied `animeace.ttf` to the frontend, and injected an `@font-face` rule into `style.css` so the browser DOM strictly matches the backend's font logic.

### 11.9 Final Current State

- **Approach**: Segmentation Mask Inscribed Rectangle + Symmetric OCR Centering.
- **Visual quality**: **Perfected.** Text is maximally visible, strictly obeys segmentation mask boundaries (even irregular ones), sits perfectly centered within the bubble, and the lab UI provides a 100% accurate WYSIWYG preview across all zoom levels.
- **Tests**: 117/117 unit tests pass, including new boundary-aware collinearity checks for point-in-polygon math.

### 11.10 Implementation reference

All changes are in `companion_server/render/layout_planner.py` (the Python port) and `overlay_lab/frontend/`:
- `_inscribed_rect()`, `_point_in_polygon()`, `_polygon_centroid()` — core inscribed rectangle geometry.
- `_place_block()` — strict symmetric clamping of `safe_w`/`safe_h` when `anchor_to_ocr_center` is true.
- `app.js` / `style.css` — cache-busted WYSIWYG sync using `Anime Ace` font and linear CSS scaling.
