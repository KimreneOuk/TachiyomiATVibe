"""Manga Render Lab — FastAPI server.

Endpoints:
  GET  /                  → frontend
  GET  /api/samples       → list sample images
  POST /api/ocr           → detect + OCR
  POST /api/inpaint       → cleaned background (segmentation solid-fill)
  POST /api/render/overlay → plan only (layouts JSON for live Canvas draw)
  POST /api/translate     → LM Studio / OpenAI-compatible translation
  GET  /api/llm/models    → list models from an LLM server
  POST /api/metrics       → per-stage timings
"""
from __future__ import annotations

import io
import sys
from pathlib import Path
from typing import Any

from fastapi import FastAPI, UploadFile, File, Form, HTTPException
from fastapi.responses import JSONResponse, Response, FileResponse
from fastapi.staticfiles import StaticFiles
from PIL import Image
from pydantic import BaseModel

# Ensure the lab package + companion_server are importable
REPO_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO_ROOT))          # for `backend.*`
sys.path.insert(0, str(REPO_ROOT / "overlay_lab"))
sys.path.insert(0, str(REPO_ROOT / "companion_server"))

from backend import config, pipeline

app = FastAPI(title="Manga Render Lab")


# ── Models ────────────────────────────────────────────────────────────


class Block(BaseModel):
    bbox: dict[str, float]
    text: str = ""
    translation: str = ""
    label: int = 1
    score: float = 0.9
    direction: str = "LTR"
    parent_bbox: dict[str, float] | None = None


# ── Routes ────────────────────────────────────────────────────────────


@app.get("/api/models")
def list_models():
    """Return available segmentation and detection models."""
    def _serialize(models):
        return [
            {
                "name": m["name"],
                "path": str(m["path"]),
                "size_bytes": m["size_bytes"],
                "exists": m["path"].exists(),
            }
            for m in models
        ]
    return {
        "seg_models": _serialize(config.SEG_MODELS),
        "det_models": _serialize(config.DET_MODELS),
        "default_seg": config.default_seg_model_name(),
    }


@app.get("/api/samples")
def list_samples():
    samples = []
    for ext in ("*.png", "*.jpg", "*.jpeg", "*.webp"):
        samples.extend(p.name for p in config.SAMPLES_DIR.glob(ext))
    return {"samples": sorted(samples), "seg_model": config.find_segmentation_model() is not None}


@app.get("/api/sample/{name}")
def get_sample(name: str):
    path = config.SAMPLES_DIR / name
    if not path.exists():
        raise HTTPException(404, "sample not found")
    return FileResponse(path)


@app.post("/api/ocr")
async def ocr(
    file: UploadFile = File(...),
    det_model: str = Form("detector-v4"),
):
    import logging
    logger = logging.getLogger("overlay_lab")
    if det_model != "detector-v4":
        logger.info(
            "Model switching not yet wired for OCR; "
            "using detector-v4 (requested: %s)", det_model,
        )
    img = Image.open(io.BytesIO(await file.read())).convert("RGB")
    result = pipeline.run_ocr(img)
    return {
        "blocks": result.blocks,
        "detections": result.detections,
        "ms": result.ms,
    }


@app.post("/api/inpaint")
async def inpaint(
    file: UploadFile = File(...),
    blocks_json: str = Form("[]"),
    detections_json: str = Form("[]"),
    bubble_source: str = Form("segmentation"),
    free_text_source: str = Form("aot"),
    seg_model: str = Form(""),
    det_model: str = Form("detector-v4"),
    mask_params: str = Form(""),
):
    import json
    import logging
    logger = logging.getLogger("overlay_lab")
    img = Image.open(io.BytesIO(await file.read())).convert("RGB")
    blocks = json.loads(blocks_json)
    detections = json.loads(detections_json)
    mp = None
    if mask_params:
        try:
            mp = json.loads(mask_params)
        except json.JSONDecodeError:
            logger.error("Invalid mask_params JSON: %s", mask_params)
    result = pipeline.run_inpaint(
        img, blocks, detections,
        bubble_source, free_text_source,
        seg_model=seg_model or None,
        det_model=det_model,
        mask_params=mp,
    )
    buf = io.BytesIO()
    result.image.save(buf, format="PNG")
    return Response(
        content=buf.getvalue(),
        media_type="image/png",
        headers={
            "X-Method": result.method,
            "X-Ms": str(result.ms),
            "X-Info": json.dumps(result.info),
        },
    )


@app.post("/api/segmask")
async def segmask(
    file: UploadFile = File(...),
    seg_model: str = Form(""),
):
    """Return the raw YOLO segmentation mask overlaid on the original image.

    Green semi-transparent = mask region. Lets you verify the mask shape/position
    without any fill or detector-v4 boxes interfering.
    """
    import cv2
    import numpy as np
    img = Image.open(io.BytesIO(await file.read())).convert("RGB")
    seg_model_path = config.find_seg_model(seg_model or None)
    from backend.inpaint import bubble_segmentation
    mask = bubble_segmentation.segment_bubbles(img, str(seg_model_path) if seg_model_path else None)

    arr = np.asarray(img)
    bgr = cv2.cvtColor(arr, cv2.COLOR_RGB2BGR)
    overlay = bgr.copy()
    green = np.array([0, 255, 0], dtype=np.uint8)
    overlay[mask > 0] = (overlay[mask > 0].astype(np.float32) * 0.4 + green.astype(np.float32) * 0.6).astype(np.uint8)
    # Draw contour outlines for clarity
    contours, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    cv2.drawContours(overlay, contours, -1, (0, 255, 255), 2)

    out = Image.fromarray(cv2.cvtColor(overlay, cv2.COLOR_BGR2RGB))
    buf = io.BytesIO()
    out.save(buf, format="PNG")
    return Response(content=buf.getvalue(), media_type="image/png")


@app.post("/api/process")
async def process(file: UploadFile = File(...), seg_model: str = Form("")):
    import json
    import logging
    logger = logging.getLogger("overlay_lab")
    img = Image.open(io.BytesIO(await file.read())).convert("RGB")
    logger.info("/api/process: image=%dx%d seg_model=%s", img.width, img.height, seg_model)
    result = pipeline.run_process(img, seg_model=seg_model or None)
    return JSONResponse(result)


@app.post("/api/render/overlay")
async def render_overlay(file: UploadFile = File(...), blocks_json: str = Form("[]"), masks_json: str = Form("[]")):
    import json
    img = Image.open(io.BytesIO(await file.read())).convert("RGB")
    blocks = json.loads(blocks_json)
    masks = json.loads(masks_json)
    result = pipeline.render_overlay(img, blocks, masks)
    return JSONResponse({
        "layouts": result.layouts,
        "ms": result.ms,
        "size_bytes": result.size_bytes,
        "method": result.method,
    })


# ── LLM translation (LM Studio / OpenAI-compatible) ──────────────────


@app.get("/api/llm/models")
def llm_models(engine: str = "lmstudio", base_url: str = ""):
    """Fetch available models from an OpenAI-compatible / LM Studio server.

    Returns ``{"models": ["name", ...]}`` or ``{"error": "..."}`` (never
    raises — LM Studio being down is a config issue surfaced to the UI).
    """
    from backend import llm
    return llm.fetch_models(engine, base_url)


@app.post("/api/translate")
async def translate(
    blocks_json: str = Form("[]"),
    engine: str = Form("lmstudio"),
    base_url: str = Form(""),
    model: str = Form(""),
    temperature: float = Form(0.3),
    max_output_tokens: int = Form(8192),
    from_lang: str = Form("ja"),
    to_lang: str = Form("en"),
):
    """Translate each block's source text via the configured LLM provider.

    No fallback: on failure, returns ``{"error": "...", "translations": []}``
    and leaves the blocks' translations untouched (never substitutes the
    source text). Uses companion_server's faithful LM Studio translator.
    """
    import json
    from backend import llm
    blocks = json.loads(blocks_json)
    llm_config = {
        "engine": engine,
        "base_url": base_url,
        "model": model,
        "temperature": temperature,
        "max_output_tokens": max_output_tokens,
    }
    result = llm.translate_blocks(blocks, llm_config, from_lang, to_lang)
    return JSONResponse({
        "translations": result.get("translations", []),
        "blocks": blocks,  # echoed back with translation overwritten on success
        "ms": result.get("ms", 0),
        "model": result.get("model", ""),
        "block_count": result.get("block_count", len(blocks)),
        "translated_count": result.get("translated_count", 0),
        "error": result.get("error"),
    })


@app.post("/api/metrics")
async def metrics(
    file: UploadFile = File(...),
    blocks_json: str = Form("[]"),
    detections_json: str = Form("[]"),
    bubble_source: str = Form("segmentation"),
    free_text_source: str = Form("aot"),
):
    """Per-stage timings: OCR → inpaint → overlay plan. (No baked comparison —
    baking was removed; the lab overlays text live instead.)"""
    import json
    raw = await file.read()
    img = Image.open(io.BytesIO(raw)).convert("RGB")
    blocks = json.loads(blocks_json)
    detections = json.loads(detections_json)

    ocr_ms = None
    if not blocks:
        ocr_result = pipeline.run_ocr(img)
        blocks = ocr_result.blocks
        detections = ocr_result.detections
        ocr_ms = ocr_result.ms

    inpaint_result = pipeline.run_inpaint(img, blocks, detections, bubble_source, free_text_source)
    overlay = pipeline.render_overlay(inpaint_result.image, blocks)

    cleaned_buf = io.BytesIO()
    inpaint_result.image.save(cleaned_buf, format="PNG")
    cleaned_size = cleaned_buf.tell()

    return {
        "ocr_ms": ocr_ms,
        "inpaint": {"method": inpaint_result.method, "ms": inpaint_result.ms, "info": inpaint_result.info},
        "overlay": {"ms": overlay.ms, "size_bytes": overlay.size_bytes},
        "cleaned_size_bytes": cleaned_size,
        "block_count": len(blocks),
    }


# ── Frontend ──────────────────────────────────────────────────────────

FRONTEND = Path(__file__).resolve().parent.parent / "frontend"


@app.get("/")
def index():
    return FileResponse(FRONTEND / "index.html")


app.mount("/static", StaticFiles(directory=str(FRONTEND)), name="static")


# ── Helpers ───────────────────────────────────────────────────────────


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="127.0.0.1", port=8765)
