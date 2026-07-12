# Manga Render Lab

An investigation prototype for two architectural decisions:

1. **Track A — Overlay rendering go/no-go**: Does live text overlay (Canvas2D) match or beat baked `.rendered.png` in visual quality at zoom, while saving encode-CPU and storage?
2. **Track B — Inpainting architecture**: Is segmentation-mask bubble inpainting better than the current geometric pill-mask? Does the 22MB AOT-GAN model earn its place for free-text regions?

## Quick start

```bash
cd overlay_lab
pip install -r requirements.txt
cd backend
python main.py
# → open http://127.0.0.1:8765
```

## Architecture

The lab is a **thin orchestrator** over `companion_server/`'s existing Python ports:
- `companion_server/render/layout_planner.py` — faithful port of `TextLayoutPlanner.kt`
- `companion_server/render/text_renderer.py` — port of `PageTextRenderer.kt` + `RenderColorEstimator.kt`
- `companion_server/inference/detector.py` — RT-DETR detector v4 wrapper
- `companion_server/inference/ocr_manga.py` — MangaOCR engine
- `companion_server/inference/inpaint_pipeline.py` — `Cleaner` (classical + AOT)
- `companion_server/inference/inpaint_aot.py` — `AotInpainter`
- `companion_server/inpaint/bubble_mask.py` — `BubbleMaskBuilder` port

The lab adds:
- `backend/inpaint/bubble_segmentation.py` — segmentation-mask bubble inpaint (reuses `solid_fill_smart_color` from `tools/prototype_quantized.py`)
- `backend/pipeline.py` — orchestration + overlay-vs-baked branching
- `frontend/` — interactive dual-canvas viewer

## The 5 experiments

| # | Experiment | What it settles |
|---|---|---|
| 1 | Quality@zoom | Baked vs overlay crispness at 1×/2×/4×/8× |
| 2 | Encode-CPU | Time saved by skipping one PNG encode |
| 3 | Draw-cost | Overlay draw time vs block count (jank test) |
| 4 | Bubble-mask | Segmentation vs geometric cleaned-bg quality |
| 5 | Free-text | AOT-GAN vs classical fill on textured backgrounds |

## Keyboard

- **E** — toggle experiment drawer

## Outputs

- Experiment results feed back into `Plan/overlay_rendering_mode.md` (Track A) and a future inpainting refactor (Track B).
- Export Report button saves a JSON snapshot of metrics + state.
