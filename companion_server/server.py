from __future__ import annotations

import io
import json
import os
import time
import urllib.request
from pathlib import Path
from typing import Any

from fastapi import FastAPI, File, Form, HTTPException, Request, UploadFile
from starlette.middleware.base import BaseHTTPMiddleware
from fastapi.responses import FileResponse, Response, StreamingResponse
from fastapi.staticfiles import StaticFiles
from PIL import Image
from pydantic import BaseModel

from batch.scheduler import GpuScheduler
from chapter.processor import ChapterProcessor
from inference.detector import OnnxPageTextDetector, deterministic_detect
from inference.inpaint_aot import AotInpainter
from inference.inpaint_pipeline import Cleaner
from inference.ocr_manga import MangaOcrEngine
from inference.ocr_paddle_small import PaddleOcrV6SmallEngine
from inference.ocr_paddle_det import PaddleOcrV6DetEngine
from logging_config import configure_logging, log_failure
from model_config import load_config, resolve_model_path
from translate.translator import build_translator, fetch_ai_models, PlaceholderTranslator

PROTOCOL_VERSION = 10

# Configure logging before any stage tries to load a model — every model-load
# failure below must be recorded, never swallowed.
log = configure_logging()

config = load_config()
app = FastAPI(title="Remote Inference Companion Server")


class NoCacheStaticMiddleware(BaseHTTPMiddleware):
    async def dispatch(self, request: Request, call_next):
        response = await call_next(request)
        if request.url.path.startswith("/static/"):
            response.headers["Cache-Control"] = "no-cache, must-revalidate"
        return response


app.add_middleware(NoCacheStaticMiddleware)
scheduler = GpuScheduler(max_concurrency=int(config.get("server", {}).get("max_concurrency", 4)))

# Stage model handles + the human-readable reason each failed to load.
# Failures are captured explicitly (type + message), logged once at startup,
# and surfaced in /v1/health so the operator can act on them. A stage that
# cannot load degrades honestly downstream — it is never silently None.
detector: OnnxPageTextDetector | None = None
ocr: MangaOcrEngine | PaddleOcrV6SmallEngine | None = None
ocr_det: PaddleOcrV6DetEngine | None = None
inpainter: AotInpainter | None = None
model_load_errors: dict[str, str] = {}

translator_config = config.get("translator", {})
try:
    translator = build_translator(translator_config)
except Exception as exc:
    model_load_errors["translator"] = f"{type(exc).__name__}: {exc}"
    log.error("translator load failed: %r", exc)
    translator = PlaceholderTranslator()


def _try_load(stage: str, factory):
    """Load a stage model, recording the reason on failure instead of swallowing it."""
    try:
        return factory()
    except Exception as exc:
        model_load_errors[stage] = f"{type(exc).__name__}: {exc}"
        log.error("%s load failed: %r", stage, exc)
        return None


detector = _try_load("detector", lambda: OnnxPageTextDetector(
    resolve_model_path(config.get("detector", {}).get("path", ""))
))


def _build_ocr():
    ocr_config = config.get("ocr", {})
    if ocr_config.get("engine") == "manga_ocr":
        engine = MangaOcrEngine(
            resolve_model_path(ocr_config.get("encoder", "")),
            resolve_model_path(ocr_config.get("decoder_init", "")),
            resolve_model_path(ocr_config.get("decoder_step", "")),
            resolve_model_path(ocr_config.get("vocab", "")),
        )
    elif ocr_config.get("engine") == "paddle_ocr_v6_small":
        engine = PaddleOcrV6SmallEngine(
            resolve_model_path(ocr_config.get("path", "")),
            resolve_model_path(ocr_config.get("dict", "")),
        )
    else:
        raise ValueError(f"unknown ocr engine: {ocr_config.get('engine')!r}")
    if ocr_config.get("det_path"):
        # det engine is loaded alongside rec; both reported together.
        nonlocal_det = PaddleOcrV6DetEngine(resolve_model_path(ocr_config.get("det_path", "")))
        # Attach via attribute so the caller can recover it.
        engine.ocr_det = nonlocal_det  # type: ignore[attr-defined]
    return engine


ocr = _try_load("ocr", _build_ocr)
if ocr is not None:
    ocr_det = getattr(ocr, "ocr_det", None)

# Paddle det is loaded independently of the recognition engine — the Android
# app keeps it available for free-text mask refinement during inpainting even
# when manga-ocr is the recognition engine. Without it, free-text (label 2)
# erase regions are the coarse RT-DETR detector boxes instead of tight text-line
# boxes, which is the fidelity gap this closes.
paddle_det_path = config.get("paddle_det", {}).get("path") or config.get("ocr", {}).get("det_path")
paddle_det: PaddleOcrV6DetEngine | None = None
if paddle_det_path:
    paddle_det = _try_load("paddle_det", lambda: PaddleOcrV6DetEngine(resolve_model_path(paddle_det_path)))
elif ocr_det is not None:
    paddle_det = ocr_det

inpainter = _try_load("inpaint", lambda: AotInpainter(
    resolve_model_path(config.get("inpaint", {}).get("path", ""))
))

_batch_workers = min(int(config.get("server", {}).get("max_concurrency", 4)), os.cpu_count() or 1)
chapter_processor = ChapterProcessor(
    detector, ocr, inpainter, translator,
    max_workers=_batch_workers, ocr_det=ocr_det, paddle_det=paddle_det,
    model_load_errors=model_load_errors,
)

_WEB_STATIC = Path(__file__).resolve().parent / "web" / "static"
if _WEB_STATIC.is_dir():
    app.mount("/static", StaticFiles(directory=str(_WEB_STATIC)), name="static")


class TranslationBlockResponse(BaseModel):
    text: str
    translation: str
    x: float
    y: float
    width: float
    height: float
    sym_height: float
    sym_width: float
    angle: float
    label: int
    direction: str


class InpaintMaskBoxResponse(BaseModel):
    x1: int
    y1: int
    x2: int
    y2: int
    label: int


class TranslateResponse(BaseModel):
    protocol_version: int
    img_width: int
    img_height: int
    blocks: list[TranslationBlockResponse]
    inpaint_mask_boxes: list[InpaintMaskBoxResponse]


async def _read_image(upload: UploadFile) -> Image.Image:
    data = await upload.read()
    try:
        image = Image.open(io.BytesIO(data))
        image.load()
        return image.convert("RGBA")
    except Exception as exc:  # pragma: no cover - error text is enough for API callers
        raise HTTPException(status_code=400, detail="invalid image upload") from exc


def _translate_image(image: Image.Image, target_lang: str) -> TranslateResponse:
    width, height = image.size
    detections = detector.detect(image) if detector is not None else deterministic_detect(width, height)
    blocks: list[TranslationBlockResponse] = []
    masks: list[InpaintMaskBoxResponse] = []
    for index, box in enumerate(detections, start=1):
        crop = image.crop((max(0, box.x1), max(0, box.y1), min(width, box.x2), min(height, box.y2)))
        text = ocr.recognize(crop) if ocr is not None else f"detected text {index}"
        # Per-block translation. A translator failure is logged and the block
        # ships with an empty translation (the phone shows OCR-only) — the
        # failure is not swallowed silently, and the contract schema is stable.
        try:
            translation = translator.translate(text, target_lang)
        except Exception as exc:
            log_failure("translate/v1", f"{type(exc).__name__}: {exc}", exc=exc)
            translation = ""
        blocks.append(
            TranslationBlockResponse(
                text=text,
                translation=translation,
                x=box.x1,
                y=box.y1,
                width=box.x2 - box.x1,
                height=box.y2 - box.y1,
                sym_height=box.y2 - box.y1,
                sym_width=(box.x2 - box.x1) / max(1, len(text)),
                angle=0.0,
                label=box.label,
                direction="LTR",
            )
        )
        masks.append(
            InpaintMaskBoxResponse(
                x1=round(box.x1),
                y1=round(box.y1),
                x2=round(box.x2),
                y2=round(box.y2),
                label=box.label,
            )
        )
    return TranslateResponse(
        protocol_version=PROTOCOL_VERSION,
        img_width=width,
        img_height=height,
        blocks=blocks,
        inpaint_mask_boxes=masks,
    )


@app.get("/v1/health")
async def health() -> dict[str, Any]:
    # Surface the real reason a stage is unavailable so the operator can fix it
    # (install onnxruntime, point config at a real model path, etc.) instead of
    # seeing a generic "placeholder".
    errors = chapter_processor.model_load_errors
    return {
        "status": "ok",
        "protocol_version": PROTOCOL_VERSION,
        "gpu": "cpu",
        "max_concurrency": int(config.get("server", {}).get("max_concurrency", 4)),
        "models": {
            "detector": _health_model("detector", detector is not None, "detector-v4-s_int8", errors),
            "ocr": _health_model("ocr", ocr is not None, "manga-ocr", errors),
            "inpaint": _health_inpaint(errors),
            "translator": _health_translator(),
        },
    }


def _health_model(stage: str, loaded: bool, live_name: str, errors: dict[str, str]) -> str:
    if loaded:
        return live_name
    reason = errors.get(stage, "not configured")
    return f"unavailable ({reason})"


def _health_inpaint(errors: dict[str, str]) -> str:
    # Classical cleaning always works; neural AOT is the only ONNX-dependent tier.
    if inpainter is not None:
        return "classical+aot"
    reason = errors.get("inpaint", "not configured")
    return f"classical (neural unavailable: {reason})"


def _health_translator() -> str:
    if isinstance(translator, PlaceholderTranslator):
        return "placeholder (none/OCR-only)"
    return getattr(translator, "model", None) or translator.__class__.__name__


@app.post("/v1/translate")
async def translate(
    request: Request,
    image: UploadFile = File(...),
    target_lang: str = Form("ENGLISH"),
    mode: str = Form("FAST"),
) -> dict[str, Any]:
    source = await _read_image(image)

    async def work() -> TranslateResponse:
        return _translate_image(source, target_lang)

    result = await scheduler.run(work, request.is_disconnected)
    return result.model_dump()


@app.post("/v1/inpaint")
async def inpaint(
    image: UploadFile = File(...),
    mask_boxes: str = Form("[]"),
    mode: str = Form("FAST"),
) -> Response:
    """Clean a page using the durable Android mask boxes.

    Returns the cleaned PNG on success. On any stage failure the response is
    a non-200 with a structured reason (the Kotlin client marks the page
    FAILED on non-200) — never a swallowed 500, never the original image
    pretending to be cleaned.
    """
    source = await _read_image(image)
    parsed = json.loads(mask_boxes or "[]")
    mask = [
        {"x1": int(b.get("x1", 0)), "y1": int(b.get("y1", 0)),
         "x2": int(b.get("x2", 0)), "y2": int(b.get("y2", 0)),
         "label": int(b.get("label", 1))}
        for b in parsed
    ]
    cleaner_obj = Cleaner(chapter_processor.neural_cleaner)
    try:
        outcome = cleaner_obj.clean(source, mask, [], mode=mode)
    except Exception as exc:
        log_failure("inpaint/v1-endpoint", f"{type(exc).__name__}: {exc}", exc=exc)
        raise HTTPException(status_code=500, detail=f"inpaint failed: {type(exc).__name__}") from exc

    if outcome.status == "PARTIAL":
        # Return the partially cleaned image (best-effort) but signal partial
        # via header + a 207-style body so the client can mark FAILED.
        output = io.BytesIO()
        outcome.image.save(output, format="PNG")
        return Response(
            content=output.getvalue(),
            media_type="image/png",
            headers={
                "X-Inpaint-Status": "PARTIAL",
                "X-Inpaint-Failures": str(len(outcome.failures)),
            },
        )
    if outcome.status == "SKIPPED":
        output = io.BytesIO()
        outcome.image.save(output, format="PNG")
        return Response(content=output.getvalue(), media_type="image/png", headers={"X-Inpaint-Status": "SKIPPED"})

    output = io.BytesIO()
    outcome.image.save(output, format="PNG")
    return Response(content=output.getvalue(), media_type="image/png", headers={"X-Inpaint-Status": "OK"})


@app.post("/v1/translate_batch")
async def translate_batch(
    images: list[UploadFile] = File(...),
    target_lang: str = Form("ENGLISH"),
    mode: str = Form("FAST"),
) -> list[dict[str, Any]]:
    loaded = [await _read_image(image) for image in images]
    return [_translate_image(image, target_lang).model_dump() for image in loaded]


# ── Web UI ────────────────────────────────────────────────────────────────────


@app.get("/")
async def web_index():
    index_path = _WEB_STATIC / "index.html"
    if not index_path.exists():
        raise HTTPException(status_code=404, detail="Web UI not found")
    return FileResponse(str(index_path))


@app.post("/web/upload")
async def web_upload(
    file: UploadFile = File(...),
    target_lang: str = Form("ENGLISH"),
) -> dict[str, Any]:
    zip_bytes = await file.read()
    try:
        chapter_id, pages = chapter_processor.extract_zip(
            zip_bytes, target_lang, display_name=file.filename or ""
        )
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    return {"chapter_id": chapter_id, "pages": pages, "page_count": len(pages)}


@app.get("/web/chapters")
async def web_chapters() -> dict[str, Any]:
    return {"chapters": chapter_processor.list_chapters()}


@app.get("/web/chapters/{chapter_id}")
async def web_chapter_detail(chapter_id: str) -> dict[str, Any]:
    meta = chapter_processor.get_chapter_meta(chapter_id)
    if meta is None:
        raise HTTPException(status_code=404, detail="Chapter not found")
    return meta


@app.delete("/web/chapters/{chapter_id}")
async def web_delete_chapter(chapter_id: str) -> dict[str, Any]:
    deleted = chapter_processor.delete_chapter(chapter_id)
    if not deleted:
        raise HTTPException(status_code=404, detail="Chapter not found")
    return {"status": "deleted"}


@app.get("/web/settings")
async def web_settings() -> dict[str, Any]:
    settings = chapter_processor.get_settings()
    settings["translator"] = chapter_processor.get_translator_config()
    return settings


@app.put("/web/settings")
async def web_update_settings(request: Request) -> dict[str, Any]:
    body = await request.json()
    translator = body.get("translator")
    if translator:
        chapter_processor.update_translator_config(translator)
    lang = body.get("target_lang")
    mode = body.get("mode")
    if lang or mode:
        chapter_processor.update_settings(target_lang=lang, mode=mode)
    return {"status": "ok"}


@app.get("/web/{chapter_id}/page/{idx}")
async def web_page(chapter_id: str, idx: int) -> dict[str, Any]:
    """Return cached page result or {'processed': false} — does NOT auto-process."""
    try:
        return chapter_processor.get_page_result(chapter_id, idx)
    except KeyError:
        raise HTTPException(status_code=404, detail="Chapter not found")
    except IndexError:
        raise HTTPException(status_code=404, detail="Page not found")


@app.post("/web/{chapter_id}/page/{idx}/process")
async def web_process_page(chapter_id: str, idx: int, mode: str = "QUALITY") -> dict[str, Any]:
    """Explicitly process a page: detect + OCR + inpaint (no translation)."""
    try:
        return chapter_processor.process_page(chapter_id, idx, mode=mode)
    except KeyError:
        raise HTTPException(status_code=404, detail="Chapter not found")
    except IndexError:
        raise HTTPException(status_code=404, detail="Page not found")


@app.post("/web/{chapter_id}/page/{idx}/detect")
async def web_detect_page(chapter_id: str, idx: int) -> dict[str, Any]:
    """Detect + OCR only (no cleaning). Lets the user inspect boxes first."""
    try:
        return chapter_processor.detect_page(chapter_id, idx)
    except KeyError:
        raise HTTPException(status_code=404, detail="Chapter not found")
    except IndexError:
        raise HTTPException(status_code=404, detail="Page not found")


@app.post("/web/{chapter_id}/page/{idx}/inpaint")
async def web_inpaint_page(chapter_id: str, idx: int, mode: str = "QUALITY") -> dict[str, Any]:
    """Clean an already-detected page (detect first if needed)."""
    try:
        return chapter_processor.inpaint_page(chapter_id, idx, mode=mode)
    except KeyError:
        raise HTTPException(status_code=404, detail="Chapter not found")
    except IndexError:
        raise HTTPException(status_code=404, detail="Page not found")


@app.post("/web/{chapter_id}/page/{idx}/auto")
async def web_auto_page(chapter_id: str, idx: int, mode: str = "QUALITY") -> dict[str, Any]:
    """Full single-page pipeline: process (detect+OCR+inpaint) then translate."""
    try:
        result = chapter_processor.process_page(chapter_id, idx, mode=mode)
        result = chapter_processor.translate_page(chapter_id, idx)
        return result
    except KeyError:
        raise HTTPException(status_code=404, detail="Chapter not found")
    except IndexError:
        raise HTTPException(status_code=404, detail="Page not found")
    except RuntimeError as exc:
        raise HTTPException(status_code=409, detail=str(exc))


@app.get("/web/{chapter_id}/page/{idx}/image/{image_type}")
async def web_page_image(chapter_id: str, idx: int, image_type: str) -> Response:
    path = chapter_processor.get_image_path(chapter_id, idx, image_type)
    if path is None or not path.exists():
        raise HTTPException(status_code=404, detail="Image not found")
    media_type = "image/png" if path.suffix == ".png" else "image/jpeg"
    return FileResponse(str(path), media_type=media_type)


@app.post("/web/{chapter_id}/page/{idx}/translate")
async def web_translate_page(chapter_id: str, idx: int) -> dict[str, Any]:
    try:
        return chapter_processor.translate_page(chapter_id, idx)
    except KeyError:
        raise HTTPException(status_code=404, detail="Chapter not found")
    except IndexError:
        raise HTTPException(status_code=404, detail="Page not found")
    except RuntimeError as exc:
        raise HTTPException(status_code=409, detail=str(exc))


@app.post("/web/{chapter_id}/batch")
async def web_batch_start(chapter_id: str, translate: bool = False) -> dict[str, Any]:
    try:
        return chapter_processor.process_all(chapter_id, translate=translate)
    except KeyError:
        raise HTTPException(status_code=404, detail="Chapter not found")


@app.get("/web/{chapter_id}/batch/status")
async def web_batch_status(chapter_id: str) -> dict[str, Any]:
    return chapter_processor.get_batch_status(chapter_id)


@app.post("/web/{chapter_id}/batch/cancel")
async def web_batch_cancel(chapter_id: str) -> dict[str, Any]:
    cancelled = chapter_processor.cancel_batch(chapter_id)
    return {"cancelled": cancelled}


@app.get("/web/{chapter_id}/stats")
async def web_chapter_stats(chapter_id: str) -> dict[str, Any]:
    try:
        return chapter_processor.get_chapter_stats(chapter_id)
    except KeyError:
        raise HTTPException(status_code=404, detail="Chapter not found")


@app.get("/web/{chapter_id}/export")
async def web_export_chapter(chapter_id: str, type: str = "rendered") -> StreamingResponse:
    try:
        zip_bytes = chapter_processor.export_chapter(chapter_id, image_type=type)
    except KeyError:
        raise HTTPException(status_code=404, detail="Chapter not found")
    meta = chapter_processor.get_chapter_meta(chapter_id)
    name = (meta or {}).get("name", "chapter")
    safe_name = name.encode("ascii", "ignore").decode("ascii").strip() or "chapter"
    return StreamingResponse(
        io.BytesIO(zip_bytes),
        media_type="application/zip",
        headers={"Content-Disposition": f'attachment; filename="{safe_name}_{type}.zip"'},
    )


@app.get("/web/models")
async def web_models() -> dict[str, Any]:
    return {
        "available": chapter_processor.scan_available_models(),
        "selected": chapter_processor.get_model_config(),
    }


@app.put("/web/models")
async def web_set_model(request: Request) -> dict[str, Any]:
    body = await request.json()
    stage = body.get("stage", "")
    model_id = body.get("model_id", "")
    if not stage or not model_id:
        raise HTTPException(status_code=400, detail="stage and model_id required")
    success = chapter_processor.set_model(stage, model_id)
    if not success:
        raise HTTPException(status_code=400, detail="Failed to set model")
    return {"status": "ok", "stage": stage, "model_id": model_id}


@app.post("/web/llm/models")
async def web_llm_models(request: Request) -> dict[str, Any]:
    """Fetch available models from an AI provider (mirrors AiModelFetcher.kt)."""
    body = await request.json()
    engine = body.get("engine", "")
    api_key = body.get("api_key", "")
    base_url = body.get("base_url", "")
    if not engine:
        raise HTTPException(status_code=400, detail="engine required")
    result = fetch_ai_models(engine, api_key, base_url)
    if "error" in result:
        raise HTTPException(status_code=502, detail=result["error"])
    return result


@app.post("/web/llm/test")
async def web_llm_test(request: Request) -> dict[str, Any]:
    """Test a translator configuration with a sample translation."""
    body = await request.json()
    engine = body.get("engine", "none")
    target_lang = body.get("target_lang", "ENGLISH")
    t0 = time.monotonic()
    try:
        from translate.translator import build_translator
        translator = build_translator(body)
        result = translator.translate("hello", target_lang)
        latency = round((time.monotonic() - t0) * 1000)
        is_placeholder = result.startswith("hello [")
        return {
            "ok": not is_placeholder,
            "translation": result,
            "latency_ms": latency,
            "engine": engine,
        }
    except Exception as exc:
        latency = round((time.monotonic() - t0) * 1000)
        return {"ok": False, "error": str(exc), "latency_ms": latency, "engine": engine}


@app.put("/web/{chapter_id}/page/{idx}/block/{block_idx}")
async def web_update_block(chapter_id: str, idx: int, block_idx: int, request: Request) -> dict[str, Any]:
    body = await request.json()
    try:
        return chapter_processor.update_block(
            chapter_id, idx, block_idx,
            text=body.get("text"),
            translation=body.get("translation"),
        )
    except KeyError:
        raise HTTPException(status_code=404, detail="Chapter not found")
    except IndexError as exc:
        raise HTTPException(status_code=404, detail=str(exc))
    except RuntimeError as exc:
        raise HTTPException(status_code=409, detail=str(exc))


if __name__ == "__main__":
    import os

    import uvicorn

    _port = int(os.environ.get("MANGA_SERVER_PORT", "8765"))
    _host = os.environ.get("MANGA_SERVER_HOST", "0.0.0.0")
    uvicorn.run("server:app", host=_host, port=_port, reload=False)
