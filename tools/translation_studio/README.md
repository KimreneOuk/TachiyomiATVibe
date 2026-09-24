# Translation Studio

Translation Studio is a local browser UI for running and inspecting the
translation pipeline on chapter page images. It supports detection, OCR,
translation, active background inpainting, rendering, cached artifact filters,
comparison views, and inpaint variants with provenance.

## Start

From this folder, run:

```powershell
python studio.py --chapter <folder-of-one-chapter> [--reference <goal-folder>]
```

Or run `python studio.py` and choose a chapter and optional reference folder
in the UI. The server opens at `http://127.0.0.1:8765`; stop it with Ctrl+C in
the terminal. No frontend build step is required.

`demo_chapter/` contains two synthetic pages for a basic UI check:

```powershell
python studio.py --chapter demo_chapter
```

## Pipeline

The sidebar groups the work by stage. Each stage shows its cached status and
timing, can be rerun for the current page, and exposes its main parameters.
Run Page and Run Chapter execute the configured page or chapter workflow.

The stage cards are presented in the requested Detect → OCR → Translate →
Inpaint → Render review order. Run Page follows the current production pipeline
sequence, which inpaints before translation after OCR; the individual stage
buttons can be run independently.

| Stage | Behavior |
|---|---|
| Detect | Uses the production `detector-v4-s_int8.onnx` model and caches raw candidates. The confidence control re-filters cached detections without loading the model again. Optional YOLO11-seg bubble masks are included when the segmenter is available. |
| OCR | Choose MangaOCR or PaddleOCR v6 small (detection and recognition). MangaOCR supports configurable batch size and a serial timing mode. OCR results and per-page timings are cached. |
| Translate | Edit text in the inspector or use the configured backend: cached Google Translate or an OpenAI-compatible LM Studio endpoint. The Translate toggle controls automatic translation during page and chapter runs. |
| Inpaint | Active. The legacy engine supports FAST classical fill and QUALITY AOT fill. The Android parity engine selects independent bubble-text and free-text legs from the supported leg matrix below. Outputs include an erase mask and route provenance. |
| Render | Draws cached translations over the cleaned page, using the selected erase fill, optional font file, and font scale. Re-rendering uses the existing OCR, translation, and inpaint caches. |

Detection, OCR, inpaint, and render outputs are cached under the chapter's
`.studio/` folder. Translation text can be edited and saved per region in the
inspector. When detections are refreshed, matching OCR and translations are
carried according to the pipeline's artifact and region mapping rules.

## Inpaint engines and variants

The Advanced Settings dialog selects `Legacy` or `Android parity`. In Android
parity mode, bubble and free-text regions use independent legs:

| Region type | Available legs |
|---|---|
| Bubble text | Android fill, OpenCV, AOT, PushPull |
| Free text | OpenCV, AOT, PushPull |

The Android FAST and Android QUALITY presets select the supported production
leg pairs and reset parameters to Android defaults. Other combinations are
available for experiments and are labeled non-parity in the UI. OpenCV uses
Telea by default; Navier–Stokes is available as an experimental method. The
variant controls include Telea radius, bubble-mask erosion, and an optional
feather override. Leaving feather blank uses the path-specific Android
defaults.

Variant output, erase masks, and JSON provenance are keyed by a settings
fingerprint under `.studio/inpaint_variants/`. Provenance records the selected
engine, leg matrix, normalized parameters, routes, and processing statistics.
Changing active inpaint parameters invalidates the active output while keeping
previous variants available for switching back.

## Visualization, filters, and comparison

The View sidebar shows the original, rendered, inpainted, and optional goal
images, with side-by-side and A/B comparison modes. Its layer tree controls
detection boxes, segmentation and erase masks, provenance labels, and OCR or
translation text badges. Clicking a region or artifact opens its inspector,
including the normalized artifact record and lifecycle or inpaint provenance
when available.

Artifact filters operate on cached data and do not trigger model or pipeline
requests. The filter panel includes Production, All raw, Diagnosis: suppressed,
and Inpaint audit presets; model and label toggles; score ranges; lifecycle and
suppression rules; geometry, OCR, inpaint route, window, and translation
dimensions. Filter choices persist in chapter settings. Export writes the
current filtered records and state to `.studio/exports/` as JSON. Controls for
translation edit or prune provenance are disabled when those fields are not
present in the source cache.

## Metrics and saved data

Chapter Overview includes per-stage elapsed time, model load time, and Studio
dispatch counts, plus cold model initialization and page-level timings. Stage
times and load times come from cached pipeline metrics. Dispatch counts record
stage requests sent by Studio in the current browser session; they are not
model-internal calls.

Typical chapter data includes:

```text
.studio/
  detections.json
  ocr.json
  translations.json
  settings.json
  inpaint/           page images and provenance
  inpaint_mask/      erase masks
  inpaint_variants/  cached parameter variants
  render/            rendered pages and assignments
  exports/           exported filter snapshots
```

The storage layout may contain additional cache and preview files created by
the pipeline. These files belong to the selected chapter, not the application
source tree.

## Settings

The stage cards expose the frequently used controls: detector confidence, OCR
engine, translation backend, inpaint mode and edge erosion, render font scale,
and mask preview opacity. Advanced Settings also exposes the inpaint engine
and leg matrix, OpenCV method and radii, feather override, OCR batch size and
serial timing, erase fill, font path, translation endpoint/model/language, and
other pipeline settings. Settings are saved through the Studio settings API;
the mask preview opacity is a browser display preference.

For a production comparison, use an Android FAST or QUALITY preset and keep
experimental methods or leg combinations clearly labeled in any exported
results.
