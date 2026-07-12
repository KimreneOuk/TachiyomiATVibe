"""Lab pipeline — thin orchestrator over companion_server's faithful ports.

The layout/render/inpaint/detect/OCR modules already exist as Python ports in
companion_server/. This module imports them and adds:
  - the segmentation bubble inpaint path (Track B)
  - overlay-vs-baked render branching (Track A)
  - metric collection (sizes, timings)

Block dict shape (matches companion_server's expectation):
  bbox: {x1,y1,x2,y2}   parentW/parentH: float   parentBbox: {x1,y1,x2,y2}|None
  label: int   score: float   direction: "LTR"|"TTB"
  text: str   translation: str   textColor/strokeColor: int (set by estimate_block_colors)
"""
from __future__ import annotations

import io
import json
import logging
import sys
import time
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any

import numpy as np
from PIL import Image, ImageDraw

from backend import config
from backend.inpaint import bubble_segmentation

logger = logging.getLogger("overlay_lab")

_CS = str(config.COMPANION_SERVER)
if _CS not in sys.path:
    sys.path.insert(0, _CS)


@dataclass
class OcrResult:
    blocks: list[dict[str, Any]]
    detections: list[dict[str, Any]]
    ms: float


@dataclass
class InpaintResult:
    image: Image.Image
    method: str
    ms: float
    info: dict[str, Any] = field(default_factory=dict)


@dataclass
class RenderResult:
    image: Image.Image | None
    layouts: list[dict[str, Any]] | None
    ms: float
    size_bytes: int
    method: str


_detector = None
_ocr_engine = None
_cleaner = None
_aot = None
_paddle_det = None
_renderer = None
_panel_detector = None


def _get_detector():
    global _detector
    if _detector is None:
        from inference.detector import OnnxPageTextDetector
        _detector = OnnxPageTextDetector(config.DETECTOR_MODEL)
    return _detector


def _get_ocr():
    global _ocr_engine
    if _ocr_engine is None:
        from inference.ocr_manga import MangaOcrEngine
        _ocr_engine = MangaOcrEngine(
            config.MANGA_OCR_ENCODER,
            config.MANGA_OCR_DECODER_INIT,
            config.MANGA_OCR_DECODER_STEP,
            config.MANGA_OCR_VOCAB,
        )
    return _ocr_engine


def _get_paddle_det():
    """Lazy-load the PaddleOCR v6 DET model (free-text line refinement)."""
    global _paddle_det
    if _paddle_det is None and config.PADDLE_DET_MODEL.exists():
        from backend.inference.paddle_det import PaddleOcrV6Det
        _paddle_det = PaddleOcrV6Det(config.PADDLE_DET_MODEL)
    return _paddle_det


def _get_aot():
    """Lazy-load the AOT-GAN inpainter with Paddle DET wired for refinement."""
    global _aot
    if _aot is None and config.AOT_MODEL.exists():
        from backend.inference.inpaint_aot import AotInpainter
        paddle = _get_paddle_det()  # None if model missing — logged downstream
        _aot = AotInpainter(config.AOT_MODEL, paddle_det=paddle)
    return _aot


def _get_cleaner():
    global _cleaner
    if _cleaner is None:
        from inference.inpaint_pipeline import Cleaner
        _cleaner = Cleaner(neural=_get_aot())
    return _cleaner


def _get_renderer():
    global _renderer
    if _renderer is None:
        from render.text_renderer import TextRenderer
        _renderer = TextRenderer()
    return _renderer


def _get_panel_detector():
    global _panel_detector
    if _panel_detector is None and config.PANEL_MODEL.exists():
        from backend.inference.panel_detector import OnnxPanelDetector
        _panel_detector = OnnxPanelDetector(config.PANEL_MODEL)
    return _panel_detector


# ── Public API ────────────────────────────────────────────────────────


def run_ocr(image: Image.Image) -> OcrResult:
    """Detect text/bubbles + OCR each text region."""
    t0 = time.perf_counter()
    detector = _get_detector()
    ocr = _get_ocr()
    detections = detector.detect(image)

    bubbles = [d for d in detections if d.label == 0]
    text_dets = [d for d in detections if d.label in (1, 2)]

    blocks: list[dict[str, Any]] = []
    for det in text_dets:
        crop = image.crop((int(det.x1), int(det.y1), int(det.x2), int(det.y2)))
        text = ocr.recognize(crop).strip()
        parent = _find_parent_bubble(det, bubbles)
        block: dict[str, Any] = {
            "bbox": {"x1": det.x1, "y1": det.y1, "x2": det.x2, "y2": det.y2},
            "text": text,
            "translation": text,
            "label": det.label,
            "score": det.score,
            "direction": "TTB" if _is_cjk_text(text) else "LTR",
            "parentW": 0,
            "parentH": 0,
        }
        if parent is not None:
            block["parentW"] = parent.x2 - parent.x1
            block["parentH"] = parent.y2 - parent.y1
            block["parentBbox"] = {
                "x1": parent.x1, "y1": parent.y1, "x2": parent.x2, "y2": parent.y2,
            }
        blocks.append(block)

    return OcrResult(
        blocks=blocks,
        detections=[d.to_json() for d in detections],
        ms=round((time.perf_counter() - t0) * 1000, 1),
    )


def run_inpaint(
    image: Image.Image,
    blocks: list[dict[str, Any]],
    detections: list[dict[str, Any]],
    bubble_source: str = "segmentation",
    free_text_source: str = "aot",
    seg_model: str | None = None,
    det_model: str = "detector-v4",
    mask_params: dict[str, Any] | None = None,
) -> InpaintResult:
    """Clean the page (erase source text).

    bubble_source: 'segmentation' (YOLO mask) or 'geometric' (companion_server Cleaner).
    free_text_source: 'aot' (neural) or 'classical' (cv2 Telea) — only affects
                      the free-text (label 2) regions when bubble_source=segmentation.
                      When bubble_source=geometric, the Cleaner handles everything
                      and free_text_source selects QUALITY vs FAST mode.
    seg_model: name of segmentation model to use (None=default).
    det_model: name of detection model (currently only used for logging).
    mask_params: override mask fill parameters (see bubble_segmentation.default_mask_params).
    """
    mask_boxes = _build_mask_boxes(detections)

    if bubble_source == "segmentation":
        return _inpaint_segmentation(image, blocks, detections, free_text_source,
                                     seg_model=seg_model, det_model=det_model,
                                     mask_params=mask_params)
    return _inpaint_geometric(image, blocks, mask_boxes, free_text_source)


def render_overlay(cleaned: Image.Image, blocks: list[dict[str, Any]], masks: list[list[list[float]]] | None = None) -> RenderResult:
    """Plan only — return layouts for the frontend to draw live via Canvas2D.

    No text bitmap is produced. The layout JSON is the artifact the browser
    uses to draw text on top of the cleaned image (proxy for Android Canvas).
    """
    from render import layout_planner as lp
    import cv2
    import numpy as np

    t0 = time.perf_counter()
    _coerce_block_ints(blocks)
    
    if masks:
        for block in blocks:
            bbox = block.get("bbox", {})
            if bbox:
                cx = (bbox.get("x1", 0) + bbox.get("x2", 0)) / 2
                cy = (bbox.get("y1", 0) + bbox.get("y2", 0)) / 2
                best_mask = None
                for mask in masks:
                    if not mask or len(mask) < 3:
                        continue
                    pts = np.array(mask, np.int32)
                    if cv2.pointPolygonTest(pts, (cx, cy), False) >= 0:
                        best_mask = mask
                        break
                if best_mask:
                    block["segmentation_mask"] = best_mask

    renderer = _get_renderer()
    renderer.estimate_block_colors(cleaned, blocks)

    font_path = renderer._font_path
    page_w, page_h = cleaned.size
    # plan() needs an ImageDraw for measurement; use a scratch image of the same size.
    scratch = Image.new("RGB", (max(1, page_w), max(1, page_h)))
    draw = ImageDraw.Draw(scratch)
    layouts = lp.plan(blocks, float(page_w), float(page_h), font_path, draw)

    layout_json = [
        {
            "text": l.text,
            "is_vertical": l.is_vertical,
            "origin_x": l.origin_x,
            "origin_y": l.origin_y,
            "safe_w": l.safe_w,
            "safe_h": l.safe_h,
            "font_size_px": l.font_size_px,
            "stroke_width": l.stroke_width,
            "draw_align": l.draw_align,
            "clip_rect": asdict(l.clip_rect) if l.clip_rect else None,
            "text_color": l.block.get("textColor", 0x000000),
        }
        for l in layouts
    ]
    size = len(json.dumps(layout_json).encode("utf-8"))
    return RenderResult(
        image=None,
        layouts=layout_json,
        ms=round((time.perf_counter() - t0) * 1000, 1),
        size_bytes=size,
        method="overlay",
    )


def run_process(
    image: Image.Image,
    seg_model: str | None = None,
) -> dict[str, Any]:
    """Run the full process pipeline: detector + segmentation + panel + OCR.

    Returns a dict matching the spec:
      image_dimensions, layers (detector, segmentation, panel), ocr_data, warnings.
    Every model call is wrapped in try/except -- on failure, that layer is null
    and a warning is appended. Never lets one model's failure crash the endpoint.
    """
    warnings: list[str] = []
    w, h = image.size

    # ── Segmentation layer ──────────────────────────────────────────────
    segmentation_layer: dict[str, Any] | None = None
    seg_mask: np.ndarray | None = None
    try:
        seg_path = config.find_seg_model(seg_model)
        if seg_path is None:
            segmentation_layer = None
            warnings.append(f"segmentation model not found: {seg_model}")
        else:
            from backend.inpaint.bubble_segmentation import segment_bubbles
            import cv2
            import numpy as np
            rgb = image.convert("RGB")
            seg_mask = segment_bubbles(rgb, str(seg_path))
            contours, _ = cv2.findContours(seg_mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
            bubble_masks = []
            for c in contours:
                if len(c) >= 3:
                    bubble_masks.append([[float(pt[0][0]), float(pt[0][1])] for pt in c])
            segmentation_layer = {"bubble_masks": bubble_masks}
    except Exception as e:
        logger.error("Segmentation failed: %s", e)
        segmentation_layer = None
        warnings.append(f"segmentation model failed: {e}")

    # ── Detector + OCR (reuse run_ocr) ──────────────────────────────────
    detector_layer: dict[str, Any] | None = None
    ocr_data: list[dict[str, Any]] = []
    try:
        ocr_result = run_ocr(image)
        detections = ocr_result.detections  # list of dict with x1,y1,x2,y2,label,score
        blocks = ocr_result.blocks

        # ── Apply Segmentation Priority ─────────────────────────────────────
        if seg_mask is not None:
            import numpy as np
            for d in detections:
                if d["label"] == 2:
                    x1, y1 = max(0, int(d["x1"])), max(0, int(d["y1"]))
                    x2, y2 = min(w, int(d["x2"])), min(h, int(d["y2"]))
                    if x2 > x1 and y2 > y1:
                        overlap = np.count_nonzero(seg_mask[y1:y2, x1:x2])
                        box_area = (x2 - x1) * (y2 - y1)
                        if overlap / box_area > 0.1:
                            d["label"] = 1
                            logger.info("Moved free_text to bubble_text due to segmentation overlap")

        # Split by label: 0=bubble, 1=bubble_text, 2=free_text
        bubbles: list[list[float]] = []
        bubble_texts: list[list[float]] = []
        free_texts: list[list[float]] = []
        for d in detections:
            box = [d["x1"], d["y1"], d["x2"], d["y2"]]
            if d["label"] == 0:
                bubbles.append(box)
            elif d["label"] == 1:
                bubble_texts.append(box)
            elif d["label"] == 2:
                free_texts.append(box)
        # Refine free text boxes with Paddle
        free_text_lines = []
        if free_texts:
            paddle = _get_paddle_det()
            if paddle is not None:
                from backend.inference.inpaint_aot import PADDLE_CROP_PAD, PADDLE_THRESH, PADDLE_BOX_THRESH
                for det in free_texts:
                    cx1 = max(0, int(det[0]) - PADDLE_CROP_PAD)
                    cy1 = max(0, int(det[1]) - PADDLE_CROP_PAD)
                    cx2 = min(w, int(det[2]) + PADDLE_CROP_PAD)
                    cy2 = min(h, int(det[3]) + PADDLE_CROP_PAD)
                    if cx2 > cx1 and cy2 > cy1:
                        crop = image.crop((cx1, cy1, cx2, cy2))
                        lines = paddle.detect_lines(crop, thresh=PADDLE_THRESH, box_thresh=PADDLE_BOX_THRESH)
                        for tl in lines:
                            b = tl.bbox
                            px1 = max(0, min(w, cx1 + b[0] - 6))
                            py1 = max(0, min(h, cy1 + b[1] - 6))
                            px2 = max(0, min(w, cx1 + b[2] + 6))
                            py2 = max(0, min(h, cy1 + b[3] + 6))
                            if px2 > px1 and py2 > py1:
                                free_text_lines.append([px1, py1, px2, py2])

        detector_layer = {
            "bubbles": bubbles,
            "bubble_text": bubble_texts,
            "free_text": free_texts,
            "free_text_lines": free_text_lines,
        }

        # Build ocr_data from blocks
        for i, block in enumerate(blocks):
            ocr_data.append({
                "id": i + 1,
                "box": [
                    block["bbox"]["x1"],
                    block["bbox"]["y1"],
                    block["bbox"]["x2"],
                    block["bbox"]["y2"],
                ],
                "label": block["label"],
                "raw_text": block["text"],
                "translated_text": block["translation"],
            })
    except Exception as e:
        logger.error("Detector/OCR failed: %s", e)
        detector_layer = None
        warnings.append(f"detector model failed: {e}")



    # ── Panel layer ─────────────────────────────────────────────────────
    panel_layer: dict[str, Any] | None = None
    try:
        panel_det = _get_panel_detector()
        if panel_det is None:
            panel_layer = None
            warnings.append(f"panel detector model not found: {config.PANEL_MODEL}")
        else:
            panel_boxes = panel_det.detect(image)
            frames: list[list[float]] = []
            for b in panel_boxes:
                # Frames only (label 0). The panel model also emits text boxes
                # (label 1), but we drop them — only frames are shown/used.
                if b.label == 0:
                    frames.append([b.x1, b.y1, b.x2, b.y2])
            panel_layer = {"frames": frames}
    except Exception as e:
        logger.error("Panel detection failed: %s", e)
        panel_layer = None
        warnings.append(f"panel detector failed: {e}")

    # ── Reading order: Stage A (panel XY-cut) + block panel-aware sort ──
    # Manga is RTL by default (Japanese). The panel frames are sorted via the
    # XY-cut recursive gutter split; each OCR block is assigned to its panel
    # by max containment; blocks are then ordered panel-first (reading order),
    # and within a panel by the row heuristic (top→bottom, RTL within row).
    # This mirrors the Android two-stage pipeline (ReadingOrderSorter +
    # TranslationBlockSorter) so the OCR list the lab returns matches the
    # translator input order the app would send.
    if panel_layer and panel_layer.get("frames") and ocr_data:
        try:
            from backend.inference.reading_order import (
                reading_order_panels, assign_to_panels,
            )
            ordered_panels = reading_order_panels(panel_layer["frames"], rtl=True)
            panel_layer["frames"] = ordered_panels
            ocr_data = _sort_ocr_panel_aware(ocr_data, ordered_panels, rtl=True)
        except Exception as e:
            logger.error("Reading-order sort failed: %s", e)
            warnings.append(f"reading-order sort failed: {e}")

    return {
        "image_dimensions": {"width": w, "height": h},
        "layers": {
            "detector": detector_layer,
            "segmentation": segmentation_layer,
            "panel": panel_layer,
        },
        "ocr_data": ocr_data,
        "warnings": warnings,
    }


def _sort_ocr_panel_aware(
    ocr_data: list[dict[str, Any]],
    ordered_panels: list[list[float]],
    rtl: bool,
) -> list[dict[str, Any]]:
    """Sort OCR blocks: primary by panel reading-order index, secondary by the
    row heuristic (top→bottom, within row right-to-left for RTL / left-to-right
    otherwise).

    Uses the ADVISORY best-panel index (best_panel_idx_raw) so blocks that
    merely SPAN a gutter (containment 0.10–0.80, e.g. a bubble poking past a
    panel edge) still get ordered by their best-match panel instead of dumped
    to the end. Only blocks with NO panel overlap (free_floating) go last.
    This is more forgiving than TranslationBlockSorter.kt (which only orders
    OWNED blocks) but gives an intuitive reading order for the lab's OCR list.
    """
    from backend.inference.reading_order import assign_to_panels
    annotated = []
    for entry in ocr_data:
        x1, y1, x2, y2 = entry["box"]
        res = assign_to_panels(x1, y1, x2, y2, ordered_panels)
        # advisory index: best_panel_idx_raw is set whenever any panel overlaps;
        # None only for free_floating / orphan / invalid.
        idx = res.best_panel_idx_raw if res.best_panel_idx_raw is not None else -1
        annotated.append((idx, entry))

    # Group by panel index; -1 (no overlap) goes last.
    by_panel: dict[int, list[dict[str, Any]]] = {}
    unowned: list[dict[str, Any]] = []
    for idx, entry in annotated:
        if idx < 0:
            unowned.append(entry)
        else:
            by_panel.setdefault(idx, []).append(entry)

    out: list[dict[str, Any]] = []
    for pidx in sorted(by_panel):
        out.extend(_row_sort(by_panel[pidx], rtl))
    out.extend(unowned)

    # Reassign sequential ids in the new reading order.
    for i, entry in enumerate(out):
        entry["id"] = i + 1
    return out


def _row_sort(entries: list[dict[str, Any]], rtl: bool) -> list[dict[str, Any]]:
    """Row-heuristic sort: rows top→bottom by vertical-centre overlap, within
    a row by centre-x (reversed for RTL). Mirrors TranslationBlockSorter rows."""
    def cy(e):
        _, y1, _, y2 = e["box"]
        return (y1 + y2) / 2.0

    def height(e):
        _, y1, _, y2 = e["box"]
        return max(1.0, y2 - y1)

    def cx(e):
        x1, _, x2, _ = e["box"]
        return (x1 + x2) / 2.0

    by_y = sorted(entries, key=cy)
    rows: list[list[dict[str, Any]]] = []
    for entry in by_y:
        placed = False
        recent = rows[-3:] if rows else []
        for row in reversed(recent):
            row_cy = sum(cy(e) for e in row) / len(row)
            if abs(cy(entry) - row_cy) <= height(entry) / 2:
                row.append(entry)
                placed = True
                break
        if not placed:
            rows.append([entry])
    out: list[dict[str, Any]] = []
    for row in rows:
        out.extend(sorted(row, key=cx, reverse=rtl))
    return out


# ── Internal helpers ──────────────────────────────────────────────────


def _coerce_block_ints(blocks: list[dict[str, Any]]) -> None:
    """Coerce float bbox/parent coords to int in place.

    The RT-DETR detector returns float pixel coords; the PIL/numpy crop + mask
    math in estimate_colors and layout_planner requires ints. Mutates blocks.
    """
    for block in blocks:
        bbox = block.get("bbox")
        if bbox:
            for k in ("x1", "y1", "x2", "y2"):
                if k in bbox:
                    bbox[k] = int(round(float(bbox[k])))
        parent = block.get("parentBbox")
        if parent:
            for k in ("x1", "y1", "x2", "y2"):
                if k in parent:
                    parent[k] = int(round(float(parent[k])))
        if "parentW" in block:
            block["parentW"] = int(round(float(block["parentW"])))
        if "parentH" in block:
            block["parentH"] = int(round(float(block["parentH"])))


def _find_parent_bubble(text_box, bubbles):
    cx = (text_box.x1 + text_box.x2) / 2.0
    cy = (text_box.y1 + text_box.y2) / 2.0
    best = None
    best_area = float("inf")
    for b in bubbles:
        if b.x1 <= cx <= b.x2 and b.y1 <= cy <= b.y2:
            a = (b.x2 - b.x1) * (b.y2 - b.y1)
            if a < best_area:
                best, best_area = b, a
    return best


def _is_cjk_text(text: str) -> bool:
    if not text:
        return False
    total = sum(1 for c in text if not c.isspace())
    if total == 0:
        return False
    cjk = sum(1 for c in text if not c.isspace() and _is_cjk_char(c))
    return cjk / total > 0.5


def _is_cjk_char(ch: str) -> bool:
    cp = ord(ch)
    return (
        0x3040 <= cp <= 0x309F
        or 0x30A0 <= cp <= 0x30FF
        or 0x4E00 <= cp <= 0x9FFF
        or 0x3400 <= cp <= 0x4DBF
        or 0xAC00 <= cp <= 0xD7AF
    )


def _build_mask_boxes(detections: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Build the Android InpaintMaskBox convention: label 0=bubble, 1/2=text."""
    boxes = []
    for d in detections:
        boxes.append({
            "x1": int(d["x1"]), "y1": int(d["y1"]),
            "x2": int(d["x2"]), "y2": int(d["y2"]),
            "label": int(d["label"]),
        })
    return boxes


def _inpaint_segmentation(image, blocks, detections, free_text_source,
                          seg_model=None, det_model=None, mask_params=None):
    """Segmentation-based inpaint: YOLO seg model for bubbles, plus
    free-text erase for label-2 detections.

    The free-text path respects free_text_source (aot/classical). If AOT is
    requested but unavailable, the boxes are left unerased with a logged error
    (no silent fallback to classical).
    """
    t0 = time.perf_counter()
    info = {}

    seg_path = config.find_seg_model(seg_model)
    if seg_path is None:
        logger.error("No segmentation model found for seg_model=%s", seg_model)
        raise RuntimeError(f"Segmentation model not found: {seg_model}")

    from backend.inpaint import bubble_segmentation
    import numpy as np
    import cv2
    rgb = image.convert("RGB")
    seg_mask = bubble_segmentation.segment_bubbles(rgb, str(seg_path))

    w, h = image.size
    free_boxes = []
    for d in detections:
        if isinstance(d, dict) and d.get("label") == 2:
            x1, y1 = max(0, int(d["x1"])), max(0, int(d["y1"]))
            x2, y2 = min(w, int(d["x2"])), min(h, int(d["y2"]))
            if x2 > x1 and y2 > y1:
                overlap = np.count_nonzero(seg_mask[y1:y2, x1:x2])
                box_area = (x2 - x1) * (y2 - y1)
                if overlap / box_area > 0.1:
                    continue
            free_boxes.append([int(d["x1"]), int(d["y1"]), int(d["x2"]), int(d["y2"])])
    if free_boxes:
        if det_model is not None and det_model != "detector-v4":
            logger.info(
                "Det model switching not yet wired for free-text; "
                "using %s boxes from current detections", det_model,
            )
        cleaned, ft_info = _inpaint_free_text(image, free_boxes, free_text_source)
        info["free_text"] = ft_info
    else:
        cleaned = image
        info["free_text"] = {"method": free_text_source, "ms": 0, "boxes": 0}

    # Run bubble inpainting on the cleaned image
    t_bubble = time.perf_counter()
    bgr = cv2.cvtColor(np.asarray(cleaned.convert("RGB")), cv2.COLOR_RGB2BGR)
    filled = bubble_segmentation.fill_bubbles_smart_color(bgr, seg_mask, params=mask_params)
    cleaned = Image.fromarray(cv2.cvtColor(filled, cv2.COLOR_BGR2RGB))
    
    info["bubble"] = {
        "method": "segmentation",
        "ms": round((time.perf_counter() - t_bubble) * 1000, 1),
        "mask_pixels": int((seg_mask > 0).sum()),
        "components": int(len(cv2.findContours(seg_mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)[0]))
    }

    return InpaintResult(
        image=cleaned,
        method="segmentation",
        ms=round((time.perf_counter() - t0) * 1000, 1),
        info=info,
    )


def _extract_free_text_boxes(detections):
    """Extract free-text (label 2) bounding boxes from detections."""
    boxes = []
    for d in detections:
        if isinstance(d, dict) and d.get("label") == 2:
            boxes.append([int(d["x1"]), int(d["y1"]), int(d["x2"]), int(d["y2"])])
    return boxes


def _has_parent(det: dict, detections: list[dict]) -> bool:
    cx = (det["x1"] + det["x2"]) / 2
    cy = (det["y1"] + det["y2"]) / 2
    for b in detections:
        if b["label"] != 0:
            continue
        if b["x1"] <= cx <= b["x2"] and b["y1"] <= cy <= b["y2"]:
            return True
    return False


def _inpaint_geometric(image, blocks, mask_boxes, free_text_source):
    """Geometric (companion_server Cleaner) — the prod baseline."""
    t0 = time.perf_counter()
    cleaner = _get_cleaner()
    mode = "QUALITY" if free_text_source == "aot" else "FAST"
    result = cleaner.clean(image, mask_boxes, blocks, mode=mode)
    return InpaintResult(
        image=result.image,
        method=f"geometric+{free_text_source}",
        ms=round((time.perf_counter() - t0) * 1000, 1),
        info={"engine": result.engine, "failures": result.failures},
    )


def _inpaint_free_text(image: Image.Image, free_boxes: list[list[int]], source: str) -> tuple[Image.Image, dict]:
    """Erase free-text boxes via the selected strategy.

    source selects the algorithm:
      - "aot"      : AOT-GAN neural reconstruction (no fallback if unavailable)
      - "telea"    : Fast Marching Method (reuse fast_marching.inpaint_telea)
      - "pushpull" : push-pull gradient (reuse push_pull.push_pull_fill)
      - "ns"       : Laplace relaxation (ns_solver.inpaint_ns)
      - "hybrid"   : telea (free-text is rarely flat; hybrid collapses to telea)
      - "median"   : per-box ring median (flat background only)
      - "classical": legacy alias for telea (kept for older callers)

    Returns (updated_image, info_dict). No silent fallback: if AOT is requested
    but unavailable, the failure is logged and boxes are left unerased.

    Pure numpy/ONNX only — cv2.inpaint is intentionally NOT used (Android has no
    OpenCV; the Telea path reuses the faithful pure-numpy fast_marching port).
    """
    if not free_boxes:
        return image, {"method": source, "ms": 0, "boxes": 0}

    # Normalize the legacy/alias spellings.
    norm = source if source != "classical" else "telea"
    if norm == "hybrid":
        norm = "telea"  # free-text rarely flat → hybrid collapses to telea

    t0 = time.perf_counter()

    if norm == "aot":
        # Faithful Android path: Paddle DET refine → 512 context crop → pill
        # mask → AOT-GAN → output guard → push-pull fast fallback. The AOT
        # module owns the whole pipeline (refine + crop + mask + guard).
        aot = _get_aot()
        if aot is not None:
            out_img, aot_info = aot.inpaint_free_text(
                image.convert("RGB"), free_boxes, mode="QUALITY",
            )
            aot_info["ms"] = round((time.perf_counter() - t0) * 1000, 1)
            return out_img, aot_info
        logger.error(
            "AOT model unavailable; %d free-text boxes NOT inpainted (no fallback)",
            len(free_boxes),
        )
        return image, {"method": "aot", "ms": 0, "boxes": len(free_boxes),
                       "error": "aot_unavailable"}

    # Refine free boxes into groups of Paddle line boxes
    paddle = _get_paddle_det()
    groups = []
    if paddle is not None:
        from backend.inference.inpaint_aot import PADDLE_CROP_PAD, PADDLE_THRESH, PADDLE_BOX_THRESH
        w, h = image.size
        for det in free_boxes:
            cx1 = max(0, int(det[0]) - PADDLE_CROP_PAD)
            cy1 = max(0, int(det[1]) - PADDLE_CROP_PAD)
            cx2 = min(w, int(det[2]) + PADDLE_CROP_PAD)
            cy2 = min(h, int(det[3]) + PADDLE_CROP_PAD)
            if cx2 > cx1 and cy2 > cy1:
                crop = image.crop((cx1, cy1, cx2, cy2))
                lines = paddle.detect_lines(crop, thresh=PADDLE_THRESH, box_thresh=PADDLE_BOX_THRESH)
                if not lines:
                    groups.append([det])
                    continue
                group = []
                for tl in lines:
                    b = tl.bbox
                    px1 = max(0, min(w, cx1 + b[0] - 6))
                    py1 = max(0, min(h, cy1 + b[1] - 6))
                    px2 = max(0, min(w, cx1 + b[2] + 6))
                    py2 = max(0, min(h, cy1 + b[3] + 6))
                    if px2 > px1 and py2 > py1:
                        group.append([px1, py1, px2, py2])
                if group:
                    groups.append(group)
                else:
                    groups.append([det])
    else:
        groups = [[b] for b in free_boxes]

    # Non-AOT experiment strategies with per-group ROI cropping
    import cv2
    from backend.inference.inpaint_aot import (
        build_fixed_pill_mask, feather_alpha_field, REPORT_FREE_TEXT_PAD, 
        REPORT_FREE_TEXT_DILATE, REPORT_FREE_TEXT_FEATHER, 
        padded_union_bounds, localize_box, REPORT_PUSH_PULL_CONTEXT
    )
    arr = cv2.cvtColor(np.asarray(image.convert("RGB")), cv2.COLOR_RGB2BGR)
    original_arr = arr.copy()
    img_h, img_w = arr.shape[:2]
    
    for group in groups:
        bounds = padded_union_bounds(group, img_w, img_h, REPORT_PUSH_PULL_CONTEXT)
        if bounds is None:
            continue
        bx1, by1, bx2, by2 = bounds
        crop_w = bx2 - bx1
        crop_h = by2 - by1
        
        local_boxes = []
        for b in group:
            lb = localize_box(b, bx1, by1, crop_w, crop_h)
            if lb is not None:
                local_boxes.append(lb)
                
        if not local_boxes:
            continue
            
        hole_bool = build_fixed_pill_mask(
            local_boxes, crop_w, crop_h, REPORT_FREE_TEXT_PAD, REPORT_FREE_TEXT_DILATE
        )
        if not hole_bool.any():
            continue
            
        crop = arr[by1:by2, bx1:bx2].copy()
        
        if norm == "telea":
            from inpaint import fast_marching  # companion_server pure-numpy port
            crop = fast_marching.inpaint_telea(crop, hole_bool, radius=3)
        elif norm == "pushpull":
            from inpaint import push_pull
            bg = push_pull.local_ring_median(crop, hole_bool, ring=8)
            crop = push_pull.push_pull_fill(crop, hole_bool, bg)
        elif norm == "ns":
            from backend.inpaint import ns_solver
            crop = ns_solver.inpaint_ns(crop, hole_bool)
        elif norm == "median":
            from backend.inpaint import strategies
            crop = strategies.fill_median(crop, hole_bool * 255, {})
        else:
            logger.error("Unknown free-text source %r", source)
            
        # Feather blend for this crop
        alpha = feather_alpha_field(hole_bool, crop_w, crop_h, REPORT_FREE_TEXT_FEATHER)
        a = alpha[:, :, None]
        orig_crop = arr[by1:by2, bx1:bx2].astype(np.float32)
        blended = orig_crop * (1.0 - a) + crop.astype(np.float32) * a
        arr[by1:by2, bx1:bx2] = np.clip(blended, 0, 255).astype(np.uint8)

    out = Image.fromarray(cv2.cvtColor(arr, cv2.COLOR_BGR2RGB))
    elapsed = (time.perf_counter() - t0) * 1000
    return out, {"method": norm, "ms": round(elapsed, 1), "boxes": len(free_boxes)}
