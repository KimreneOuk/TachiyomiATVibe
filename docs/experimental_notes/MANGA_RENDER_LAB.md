# Manga Render Lab

Manga Render Lab is the experimental sandbox for investigating manga translation rendering, inpainting, segmentation, and overlay behavior outside the Android app.

This lab lives in:

```text
overlay_lab/
```

It is a Python FastAPI backend plus a vanilla JavaScript Canvas frontend. It exists for investigation only. It is not production Android code.

## Purpose

The lab is used to test the full visual pipeline for manga translation:

1. Load a manga page.
2. Detect speech bubbles, free text, and panels.
3. Segment bubbles with a bubble segmentation model.
4. Inpaint/clean only the detected erase regions.
5. Translate OCR text using a local OpenAI-compatible/LM Studio backend.
6. Render the final translation as an overlay, not baked into the image.

The important rule is:

> We are not baking the inpainting onto the original images. The lab shows a cleaned overlay on top of the original image with precise mask/position tracking.

The final view is composed like this:

```text
original image
  + cleaned/inpainted overlay clipped to erase masks
  + live Canvas2D translated text overlay
```

The original page remains the base image.

## Current Project Structure

```text
overlay_lab/
  backend/
    main.py                         FastAPI app and API routes
    pipeline.py                     Orchestrates detection, OCR, segmentation, inpaint, render
    llm.py                          LM Studio/OpenAI-compatible translation bridge
    config.py                       Model registry and model path resolution
    inference/
      paddle_det.py                 PaddleOCR v6 DET Python port for free-text erase masks
      inpaint_aot.py                AOT-GAN free-text inpainting path, Android-faithful port
      panel_detector.py             Panel detector ONNX wrapper
      reading_order.py              Reading-order helpers
    inpaint/
      bubble_segmentation.py        Bubble segmentation + smart solid fill path
      strategies.py                 Median/Telea/push-pull/NS/hybrid strategy dispatcher
      ns_solver.py                  Laplace/NS-style inpaint experiment
  frontend/
    index.html                      Lab UI
    app.js                          Canvas state, API calls, tabs, overlay render
    style.css                       Lab styling
  tests/                            Backend/unit/regression tests
  samples/                          Sample manga pages used by the lab
```

## UI Flow

The lab exposes a three-tab view:

```text
Original   Detected   Translated
```

### Original

Shows the untouched input image.

### Detected

Shows detection/debug layers:

- Bubble boxes
- Bubble text boxes
- Free-text boxes
- Segmentation masks
- Panel frame boxes

Panel model text boxes are filtered out. The panel layer is frames only.

### Translated

Shows the final overlay composition:

```text
original page
  + cleaned overlay clipped to segmentation/free-text masks
  + live Canvas2D translation text
```

No bounding boxes should appear in the Translated view.

## Backend API Summary

Important endpoints:

```text
GET  /                         Frontend
GET  /api/models                Available model registry
GET  /api/samples               Sample image list
POST /api/process               Run detector + segmentation + panel + OCR
POST /api/inpaint               Produce cleaned image for overlay
POST /api/render/overlay        Produce text layout JSON for live Canvas render
GET  /api/llm/models            Fetch LM Studio/OpenAI-compatible model list
POST /api/translate             Translate OCR blocks with LM Studio/OpenAI-compatible API
POST /api/metrics               Per-stage timings, no baked comparison
```

Removed legacy endpoints/paths:

```text
/api/bake
/api/render/baked
run_bake
render_baked
```

The lab no longer keeps baked-render comparison code.

## Models Involved

### 1. Bubble Segmentation Model

Source:

```text
huyvux3005/manga109-segmentation-bubble
```

Available local variants include:

```text
best.onnx          Dynamic ONNX export, current default
best_int8.onnx     Fixed 640x640 int8 export, kept as selectable fallback
best_fp32.onnx     Older fixed-shape fp32 ONNX
best.pt            Original PyTorch model
```

Current default:

```text
best.onnx
```

Current balanced segmentation settings:

```text
imgsz: auto, capped at 1280 for dynamic best.onnx/best.pt
conf: 0.2
iou: 0.7
```

Fixed-shape ONNX exports such as `best_int8.onnx` still force `imgsz=640`, because ONNX Runtime rejects larger shapes for those exports.

Reason for the default change:

- The old fixed 640 int8 model missed small/distant/dark/gray bubbles.
- The model was trained around 1600x1600.
- Dynamic `best.onnx` can infer at larger sizes.
- 1280 is the current balanced cap: better recall than 640 without the full 1600 cost.

Measured on `page-005.jpg`:

```text
640 / conf 0.4     -> 3 masks, ~58 ms
960 / conf 0.2     -> 4 masks, ~104 ms
1280 / conf 0.2    -> 4 masks, ~173 ms
1280 / conf 0.1    -> 5 masks, ~219 ms, more false-positive risk
1600 / conf 0.2    -> heavier, not chosen as default
```

### 2. Detector-v4 Text/Bubble Detector

Asset:

```text
app/src/main/assets/models/detection/detector-v4-s_int8.onnx
```

Used for:

- Bubble boxes
- Bubble-text boxes
- Free-text boxes
- OCR region generation

Detector boxes are not the final erase boundary for bubbles when segmentation is enabled. The segmentation mask is the preferred erase boundary.

### 3. Panel Detector

Asset:

```text
app/src/main/assets/models/detection/manga_panel_detector_int8.onnx
```

Used for:

- Panel/frame boxes only

The model can emit panel text boxes, but the lab filters those out. Only frame boxes are shown.

### 4. MangaOCR / OCR Models

Existing OCR path uses the companion server OCR components through the lab pipeline.

OCR data becomes editable in the frontend. Real translation is a separate explicit step, not part of detection.

### 5. PaddleOCR v6 DET Model

Asset:

```text
app/src/main/assets/models/ocr/paddle-v6-small/det/inference.onnx
```

Lab port:

```text
overlay_lab/backend/inference/paddle_det.py
```

Used for Android-faithful free-text erase masks.

Purpose:

1. Start from a coarse detector-v4 free-text box.
2. Crop around it with padding.
3. Run PaddleOCR v6 detection.
4. Get tighter text-line boxes.
5. Build a pill mask from those line boxes.

Current thresholds in the Android-faithful erase path:

```text
PADDLE_THRESH = 0.18
PADDLE_BOX_THRESH = 0.34
PADDLE_CROP_PAD = 12
```

This is important because Android used Paddle DET to drive free-text erasing. The earlier lab implementation did not, so free-text AOT was not faithful.

### 6. AOT-GAN Inpainting Model

Asset:

```text
app/src/main/assets/models/inpainting/aot.onnx
```

Lab port:

```text
overlay_lab/backend/inference/inpaint_aot.py
```

Used for:

- Free-text inpainting in `aot` mode

Current Android-faithful free-text pipeline:

```text
detector-v4 free-text box
  -> PaddleOCR v6 DET line refinement
  -> pill mask from line boxes
  -> 512x512 centered context crop
  -> AOT-GAN inpaint
  -> output guard
  -> Android-designed push-pull fallback if guard rejects or AOT fails
```

Important constants:

```text
REPORT_AOT_CONTEXT = 512
REPORT_FREE_TEXT_PAD = 16
REPORT_FREE_TEXT_DILATE = 8
REPORT_PUSH_PULL_CONTEXT = 64
REPORT_FREE_TEXT_FEATHER = 3
```

Output guard rejects suspicious uniform AOT output:

```text
maskedCount >= 16
variance < 9.0
channelDelta < 8.0
mean <= 24 OR 96 <= mean <= 160 OR mean >= 238
```

Guard rejection routes to the Android-designed push-pull fast fallback and is logged.

### 7. LM Studio / OpenAI-Compatible LLM

Existing companion server translator is reused; it was not rewritten.

Bridge:

```text
overlay_lab/backend/llm.py
```

Frontend lets the user configure:

```text
engine: lmstudio / openai_compatible
base_url: e.g. http://localhost:1234/v1
model: fetched from /v1/models
from_lang
to_lang
```

Translation route:

```text
POST /api/translate
```

No fallback is used. If LM Studio is unavailable, an error is returned/logged and no fake translation is substituted.

## Inpainting Strategies

Bubble segmentation fill default:

```text
median
```

This is the Android-faithful smart solid fill path, not Telea.

Available bubble/free-text strategy options:

```text
median      Smart solid fill, Android-faithful default for bubbles
telea       Pure-numpy fast-marching experiment, no cv2.inpaint
pushpull    Gradient push-pull fill experiment
ns          Laplace/NS-style smoothing experiment
hybrid      Experimental strategy router
aot         Neural AOT-GAN path for free-text only
```

OpenCV `cv2.inpaint` is intentionally not used, because Android does not have OpenCV in the target implementation. cv2 is only used for trivial operations such as resize, contours, thresholding, morphology, and distance transforms.

## Current Mask Options

Frontend mask options include:

```text
skip_px
ring_w
luma_floor
feather
dilate
inset_px
bubble strategy
free-text strategy
```

Important defaults:

```text
strategy: median
free_text_strategy: median
skip_px: 3
ring_w: 4
luma_floor: 128
feather: 0
dilate: 0
inset_px: 5
```

`inset_px` was added to avoid erasing the bubble outline. The segmentation mask can cut through the original bubble stroke, especially on gray/dark bubbles. The fill now stays inside an eroded interior of the mask, leaving a buffer so the original ink line remains visible. The default was increased from 2 to 5 to provide a more generous buffer, heavily protecting thick manga ink strokes from being accidentally erased by the inpaint fill.

## Major Work Done So Far

### 1. Removed baked-render path

Removed stale/legacy paths that contradicted the overlay design:

```text
/api/bake
/api/render/baked
render_baked
run_bake
bakedImage frontend state
compare/baked UI
```

The final result is now shown as an overlay.

### 2. Added three-tab view

Replaced old view/compare behavior with:

```text
Original / Detected / Translated
```

### 3. Added live overlay rendering

Translated view now draws:

```text
original image
  + cleaned overlay clipped to masks
  + live text drawn on Canvas2D
```

### 4. Wired LM Studio translation

Added:

```text
overlay_lab/backend/llm.py
GET  /api/llm/models
POST /api/translate
```

Translation uses the existing companion server translator stack.

### 5. Pruned legacy bbox/fill-mode paths

Removed old bbox legacy fill and `fill_mode` behavior that contradicted segmentation-mask investigation.

### 6. Filtered panel model text boxes

Panel model layer now shows frames only. Text boxes from the panel model are filtered out.

### 7. Corrected default inpainting method

Bubble inpainting default is now `median`, the smart solid fill matching Android's bubble fill behavior. Telea and other methods remain optional experiments.

### 8. Added mask inset buffer

Added `inset_px` so fill does not reach the segmentation mask border. This protects original bubble line art when the segmentation mask clips through the stroke.

### 9. Ported Android-faithful AOT free-text path

Added:

```text
overlay_lab/backend/inference/paddle_det.py
overlay_lab/backend/inference/inpaint_aot.py
```

The lab now matches Android's free-text erase structure much more closely:

```text
Paddle DET refinement -> pill mask -> 512 context crop -> AOT -> guard -> push-pull fallback
```

### 10. Fixed inference package shadowing

There are two `inference` packages:

```text
companion_server/inference/
overlay_lab/backend/inference/
```

`companion_server` can shadow lab inference modules. Lab-specific modules now use unambiguous imports:

```python
backend.inference.paddle_det
backend.inference.inpaint_aot
```

### 11. Re-exported dynamic segmentation ONNX

Exported from `best.pt`:

```text
best.onnx
```

Settings:

```text
format=onnx
imgsz=1600
dynamic=True
opset=12
simplify=True
```

This dynamic model is now the default segmentation model in the lab.

### 12. Balanced segmentation defaults

The lab originally moved to 1600 for maximum quality, but that was expensive. Current balanced default:

```text
best.onnx dynamic
imgsz auto capped at 1280
conf 0.2
iou 0.7
```

This is the current quality/performance compromise.

## Important Constraints

- This is purely investigation.
- Do not modify production Android behavior unless explicitly requested.
- No silent fallback. Log every failure.
- Do not use `cv2.inpaint`.
- Do not bake the final translation into the original image.
- Segmentation masks are the preferred bubble erase boundary.
- Panel model should show frames only.
- Default bubble fill is smart solid median fill, not Telea.

## Known Open Investigation Areas

### Segmentation recall

Even with dynamic ONNX and 1280 cap, some pages may still miss bubbles. Page 010 was reported as likely having around 14+ expected masks.

Possible next steps:

1. Add a segmentation quality preset:

```text
Fast:     int8 640, conf 0.4
Balanced: dynamic 1280, conf 0.2
Quality:  dynamic 1600, conf 0.1
```

2. Add tiling inference for pages with many small bubbles.
3. Add detector-box geometric fallback for bubbles that detector-v4 finds but segmentation misses.
4. Add mask count diagnostics in the frontend.

### Text containment

The intended future fix is to make translated text fit inside the segmentation mask area, not only detector boxes. This would align:

```text
erase boundary
cleaned overlay clip
text containment region
```

all to the same segmentation source.

## Test Status

At the time of writing:

```text
102 tests passed
```

Relevant new/updated tests cover:

- Paddle DET DB postprocess behavior
- AOT pill mask, crop, guard, and fast fallback helpers
- Dynamic segmentation model default
- Auto image size selection for dynamic vs fixed-shape ONNX
- Mask inset behavior

## Running the Lab

From:

```text
overlay_lab/
```

Run:

```bash
python -m uvicorn backend.main:app --host 127.0.0.1 --port 8765 --app-dir .
```

Open:

```text
http://127.0.0.1:8765
```

If the background server task times out or dies, confirm with:

```bash
curl http://127.0.0.1:8765/
netstat -ano | grep 8765
```

A valid running server should return HTTP 200 and show a LISTENING PID on port 8765.
