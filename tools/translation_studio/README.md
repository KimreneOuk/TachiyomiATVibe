# Translation Studio

A lightweight local web app for visual translation-quality work on a whole
chapter — Detect → OCR → Translate → Render, side by side with the original
and an optional "goal" reference.

Run (from this folder):

```
python studio.py --chapter <folder-of-one-chapter> [--reference <goal-folder>]
```

or just `python studio.py` and pick the folders **in the UI** — both header
fields have Browse… buttons that open a filesystem navigator (drives + Home
shortcuts, click to drill in, *Use this folder* to select). Picking a chapter
folder opens it immediately. The UI opens at http://127.0.0.1:8765 (Ctrl+C in
the terminal stops it).

## What you get

- **Pages rail** — every image under the chapter folder, in order.
- **Three collapsible panels**: Original | Goal (reference folder, optional) |
  Ours (render output, with an `overlay` tab showing detection boxes +
  scores).
- **Per-page controls**: Detect, OCR, Translate, Render, Process Page, and
  Process All (walks the chapter; tick *translate on process* to auto-fill
  translations as it goes).
- **Region inspector** — click a region: the exact OCR crop, OCR text,
  confidence, and an auto-saving translation box (editing re-renders
  immediately).
- **Processing console** at the bottom (timings, dispatch counts, errors).

## Stages

| Stage | Engine | Notes |
|---|---|---|
| Detect | the app's own `detector-v4-s_int8.onnx` | exact production protocol: 640×640, conf 0.45 default, production dedup geometry (BoxGeometry port). Raw outputs are cached, so the confidence slider re-filters without re-running the model. |
| OCR | T927 lab stack | the corrected decoder (KV slot `pos−1`, positions 2..127) on the derived batch-capable graphs — **all regions of a page decode in batched passes** (max batch configurable), with step-dispatch counts in the log. |
| Translate | cache-first | hand-fill in the inspector (auto-saved), or press *Translate* / tick *translate on process* to fill missing ones from an OpenAI-compatible endpoint (LM Studio default `http://127.0.0.1:1234/v1`; set model + target language in ⚙ settings). |
| Render | desktop renderer | erases each translated text box with a background-median fill (or plain white) and draws the translation auto-wrapped and auto-sized to the box. |

Everything caches under `<chapter>/.studio/` — `detections.json`,
`ocr.json`, `translations.json`, `settings.json`, `render/`. Re-testing a
render or a confidence change never re-runs the models; re-OCR preserves
translations of regions whose boxes survived (matched by IoU ≥ 0.7).

## Verifying quality

1. Open your chapter folder (+ the goal folder for comparison).
2. *Process All* with *translate on process* (endpoint running), or process
   page-by-page and fill translations by hand.
3. Compare Original | Goal | Ours; click any region whose rendered text looks
   wrong — the inspector shows whether the mistake came from detection
   (wrong box), OCR (wrong source text), or translation.

The **overlay** tab is the fastest way to audit detection: every text region
gets an id + score tag; bubbles are shown in gray during detection but only
text boxes (labels 1/2) are OCR'd and rendered, exactly like the app.

`demo_chapter/` contains two synthetic pages so the tool is testable on a
fresh clone: `python studio.py --chapter demo_chapter`.

## Not yet (deliberate v1 scope)

- No bubble segmentation mask or AOT inpainting (render is fill+text).
- No reading-order-aware batch translation (per-region calls).
- Desktop rendering fidelity ≠ the Android text layout engine; this tool
  judges detect/OCR/translate quality, not final on-device typesetting.
