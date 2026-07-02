# Remote Inference Companion Server

FastAPI companion server for the Android remote inference backend contract,
plus a browser web UI for chapter upload, per-stage processing, and export.

## Operating principle: no silent fallbacks

Every stage failure is **logged** (to `data/logs/server.log` and stderr) and
**surfaced honestly** in the page result as a structured `failures[]` entry
with a human-readable reason. A stage never silently substitutes a different
behavior and reports success.

- **Detection / OCR** require `onnxruntime` + the model files. If unavailable,
  the placeholder detector still runs (so the UI is explorable) but OCR text
  is empty — and because the render-aware inpaint mask excludes blank-OCR
  regions, those pages report cleaning as `SKIPPED`, not `READY`.
- **Inpainting (cleaning)** is split into two tiers:
  - **Classical** (Telea reconstruction) is the *intended* tier-1 algorithm
    for bubbles and flat/small text. It always runs and needs no ONNX.
  - **Neural AOT** is the explicit QUALITY tier for *textured* free-text. It
    runs only when `onnxruntime` + `aot.onnx` are loaded. If QUALITY is
    requested but neural is unavailable, the affected boxes are reported
    `PARTIAL` with their original pixels left in place — **never** silently
    cleaned with classical and reported as success. A clean that completes but
    produces no pixel change is also reported as a failure.
  - **Free-text box refinement**: every free-text (label 2) erase box is first
    refined by the **Paddle DB det model** into tight text-line boxes (port of
    Android `AOTInpainting.refineFreeTextBoxes`), so inpainting erases only the
    text and not the surrounding art. The Paddle det engine is loaded
    independently of the recognition engine (via `config.yaml → paddle_det`),
    matching the Android app which always keeps it available. When Paddle finds
    no text in a region, the coarse detector box is kept (conservative
    fallback). The DB postprocess (min-area, max-area, fragment merge) is a
    port of Android `DbPostProcess`.
- **Translation** uses the configured engine. An unknown/misconfigured engine
  **raises** (logged) rather than silently degrading to a placeholder. A
  translator runtime failure reports `translationStatus = FAILED`.

Status fields are truthful: `READY` / `PARTIAL` / `FAILED` / `SKIPPED` /
`PENDING`. The web UI shows a failure banner listing each `stage: reason`.

## Run

```bash
pip install -r requirements-cpu.txt
python server.py          # or: start.bat  (Windows)
```

The web UI opens at `http://localhost:8765/`. For real inference install
`onnxruntime` (CPU) or `onnxruntime-gpu` (GPU) and ensure the model paths in
`config.yaml` resolve.

## Model Source

The default `config.yaml` points at the Android app's bundled assets:

```text
../app/src/main/assets/models/detection/detector-v4-s_int8.onnx
../app/src/main/assets/models/ocr/encoder.onnx   (+ decoder_init, decoder_step, vocab.txt)
../app/src/main/assets/models/ocr/paddle-v6-small/det/inference.onnx   (free-text refine)
../app/src/main/assets/models/inpainting/aot.onnx
```

For a standalone server, copy `app/src/main/assets/models/` into
`companion_server/models/` and update `config.yaml` paths.

## Web UI pipeline (per page)

The reader exposes three stage actions plus a one-click full pipeline:

```text
Detect (detect+OCR)  ->  Clean (inpaint)  ->  Translate   [Auto = all three]
```

- **Detect** (`POST /web/{id}/page/{idx}/detect`): detect + OCR, writes blocks
  + the durable Android inpaint mask. No cleaning.
- **Clean** (`POST /web/{id}/page/{idx}/inpaint?mode=FAST|QUALITY`): runs the
  Cleaner (classical tier-1 always; neural QUALITY tier when available).
- **Translate** (`POST /web/{id}/page/{idx}/translate`): batch-translates all
  blocks in one call and re-renders onto the cleaned image.
- **Auto** (`POST /web/{id}/page/{idx}/auto`): the full single-page pipeline.
- **Translate All** (`POST /web/{id}/batch?translate=true`): whole chapter.

Keyboard: `D` detect, `C` clean, `T` translate, `A` auto, `1/2/3` switch
Original/Cleaned/Rendered view.

## Translator config

`config.yaml` uses `engine: openai_compatible` with a `base_url` (any
OpenAI-style `/chat/completions` endpoint: LM Studio, vLLM, Ollama `/v1`).
`build_translator` accepts either the `engine` key (UI) or `provider` (legacy).
Supported engines: `openai_compatible`, `lmstudio`, `gemini`, `openrouter`,
`deepseek`, `google`, `deepl`, `none` (OCR-only). Unknown engines raise.

## Test

```bash
pytest
```

Do not expose this LAN-oriented server to the public internet without adding
transport security and authentication hardening.

