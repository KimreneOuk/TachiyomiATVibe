from __future__ import annotations

import base64
import io
import math
import time
from dataclasses import dataclass
from pathlib import Path

import cv2
import numpy as np
import onnxruntime as ort
from fastapi import FastAPI, File, Form, UploadFile
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles
from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "app" / "src" / "main" / "assets" / "models"
DETECTOR_MODEL = ASSETS / "detection" / "detector-v4-s_int8.onnx"
PADDLE_DET_MODEL = ASSETS / "ocr" / "paddle-v6-small" / "det" / "inference.onnx"
AOT_MODEL = ASSETS / "inpainting" / "aot.onnx"

DETECTOR_THRESHOLD = 0.45
PADDLE_THRESH = 0.2
PADDLE_BOX_THRESH = 0.45
PADDLE_TARGET = 736
MIN_DB_AREA = 16

app = FastAPI()
app.mount("/static", StaticFiles(directory=Path(__file__).parent), name="static")

detector_session: ort.InferenceSession | None = None
paddle_session: ort.InferenceSession | None = None
aot_session: ort.InferenceSession | None = None


@dataclass
class TextLine:
    bbox: list[int]
    mean_score: float


def get_detector() -> ort.InferenceSession:
    global detector_session
    if detector_session is None:
        detector_session = ort.InferenceSession(str(DETECTOR_MODEL), providers=["CPUExecutionProvider"])
    return detector_session


def get_paddle() -> ort.InferenceSession:
    global paddle_session
    if paddle_session is None:
        paddle_session = ort.InferenceSession(str(PADDLE_DET_MODEL), providers=["CPUExecutionProvider"])
    return paddle_session


def get_aot() -> ort.InferenceSession:
    global aot_session
    if aot_session is None:
        aot_session = ort.InferenceSession(str(AOT_MODEL), providers=["CPUExecutionProvider"])
    return aot_session


@app.get("/")
def index() -> FileResponse:
    return FileResponse(Path(__file__).parent / "index.html")


@app.get("/{name}")
def static_file(name: str) -> FileResponse:
    safe = Path(name).name
    return FileResponse(Path(__file__).parent / safe)


@app.post("/api/process")
async def process_image(
    image: UploadFile = File(...),
    paddle_crop_pad: int = Form(12),
    paddle_thresh: float = Form(PADDLE_THRESH),
    paddle_box_thresh: float = Form(0.34),
    mask_pad: int = Form(8),
    feather: int = Form(8),
    lowres_scale: int = Form(10),
    smooth_passes: int = Form(36),
    mode: str = Form("quality"),
) -> dict:
    t0 = time.perf_counter()
    raw = await image.read()
    pil = Image.open(io.BytesIO(raw)).convert("RGB")
    rgb = np.array(pil)
    height, width = rgb.shape[:2]

    detections = detect_page(rgb)
    t1 = time.perf_counter()
    text_dets = [d for d in detections if d["label"] in (1, 2)]
    paddle_boxes: list[dict] = []
    fallback_boxes: list[dict] = []
    for det in text_dets:
        x1, y1, x2, y2 = clamp_box(
            [
                det["bbox"][0] - paddle_crop_pad,
                det["bbox"][1] - paddle_crop_pad,
                det["bbox"][2] + paddle_crop_pad,
                det["bbox"][3] + paddle_crop_pad,
            ],
            width,
            height,
        )
        if x2 <= x1 or y2 <= y1:
            continue
        crop = rgb[y1:y2, x1:x2]
        lines = detect_paddle_lines(crop, thresh=paddle_thresh, box_thresh=paddle_box_thresh)
        
        paddle_area = 0
        if lines:
            for line in lines:
                paddle_area += (line.bbox[2] - line.bbox[0]) * (line.bbox[3] - line.bbox[1])
                
        crop_area = max(1, (det["bbox"][2] - det["bbox"][0]) * (det["bbox"][3] - det["bbox"][1]))
        
        if not lines or paddle_area < 0.10 * crop_area:
            fallback_boxes.append(
                {
                    "bbox": det["bbox"],
                    "score": det["score"],
                    "source": "safety_net_fallback",
                    "parent": det["bbox"],
                    "parent_label": det["label"],
                    "parent_class": det["className"],
                }
            )
            continue
        for line in lines:
            lx1, ly1, lx2, ly2 = line.bbox
            full = clamp_box([x1 + lx1, y1 + ly1, x1 + lx2, y1 + ly2], width, height)
            if full[2] > full[0] and full[3] > full[1]:
                paddle_boxes.append(
                    {
                        "bbox": full,
                        "score": round(line.mean_score, 4),
                        "source": "paddle_v6_det",
                        "parent": det["bbox"],
                        "parent_label": det["label"],
                        "parent_class": det["className"],
                    }
                )
    t2 = time.perf_counter()

    mask_items = paddle_boxes + fallback_boxes
    if not mask_items:
        mask_items = [
            {
                "bbox": d["bbox"],
                "score": d["score"],
                "source": "safety_net_fallback",
                "parent": d["bbox"],
                "parent_label": d["label"],
                "parent_class": d["className"],
            }
            for d in text_dets
        ]
    mask_boxes = [item["bbox"] for item in mask_items]

    bubble_items = [i for i in mask_items if i.get("parent_label", -1) in (0, 1)]
    free_items = [i for i in mask_items if i.get("parent_label", -1) == 2]

    # Both FAST and QUALITY modes use solid rectangular masks to avoid dotted noise/screentone artifacts
    bubble_mask = build_rect_mask([b["bbox"] for b in bubble_items], width, height, mask_pad)
    free_mask = build_rect_mask([b["bbox"] for b in free_items], width, height, mask_pad)

    # 1. Cheap/fast inpaint for bubbles using OpenCV Telea FMM
    inpaint = inpaint_image(rgb, bubble_mask, lowres_scale / 100.0, smooth_passes, feather, "telea")
    
    # 2. Quality mode uses AOT model for free text, while FAST mode uses Telea FMM
    free_method = "aot" if mode.lower() == "quality" else "telea"
    inpaint = inpaint_image(inpaint, free_mask, lowres_scale / 100.0, smooth_passes, feather, free_method)
    
    # Combined mask for visualization
    mask = cv2.bitwise_or(bubble_mask, free_mask)
    t3 = time.perf_counter()

    return {
        "width": width,
        "height": height,
        "detections": detections,
        "paddle_boxes": paddle_boxes,
        "fallback_boxes": fallback_boxes,
        "detector_backup_boxes": [],
        "mask_boxes": [{"bbox": b} for b in mask_boxes],
        "mask_png": png_data_url(mask_to_rgb(mask)),
        "inpaint_png": png_data_url(inpaint),
        "overlay_png": png_data_url(draw_overlay(rgb, detections, paddle_boxes)),
        "timings_ms": {
            "detector_v4": round((t1 - t0) * 1000, 1),
            "paddle_v6_det": round((t2 - t1) * 1000, 1),
            "fast_inpaint": round((t3 - t2) * 1000, 1),
            "total": round((t3 - t0) * 1000, 1),
        },
        "models": {
            "detector": str(DETECTOR_MODEL.relative_to(ROOT)),
            "paddle_det": str(PADDLE_DET_MODEL.relative_to(ROOT)),
            "aot": str(AOT_MODEL.relative_to(ROOT)),
        },
        "settings": {
            "mode": mode,
            "paddle_crop_pad": paddle_crop_pad,
            "paddle_thresh": paddle_thresh,
            "paddle_box_thresh": paddle_box_thresh,
            "mask_pad": mask_pad,
            "feather": feather,
        },
    }


def detect_page(rgb: np.ndarray) -> list[dict]:
    session = get_detector()
    height, width = rgb.shape[:2]
    resized = cv2.resize(rgb, (640, 640), interpolation=cv2.INTER_LINEAR)
    tensor = resized.astype(np.float32) / 255.0
    tensor = np.transpose(tensor, (2, 0, 1))[None, :, :, :]
    sizes = np.array([[width, height]], dtype=np.int64)
    labels, boxes, scores = session.run(None, {"images": tensor, "orig_target_sizes": sizes})
    labels = labels[0]
    boxes = boxes[0]
    scores = scores[0]
    out = []
    for label, box, score in zip(labels, boxes, scores):
        score = float(score)
        if math.isnan(score) or score < DETECTOR_THRESHOLD:
            continue
        bbox = clamp_box([int(box[0]), int(box[1]), int(box[2]), int(box[3])], width, height)
        out.append(
            {
                "bbox": bbox,
                "label": int(label),
                "className": {0: "bubble", 1: "text_bubble", 2: "text_free"}.get(int(label), f"class_{int(label)}"),
                "score": round(score, 4),
            }
        )
    return dedupe_detections(out)


def detect_paddle_lines(crop_rgb: np.ndarray, thresh: float = PADDLE_THRESH, box_thresh: float = PADDLE_BOX_THRESH) -> list[TextLine]:
    if crop_rgb.size == 0:
        return []
    session = get_paddle()
    crop_h, crop_w = crop_rgb.shape[:2]
    scale = PADDLE_TARGET / max(crop_w, crop_h)
    resized_w = max(1, min(PADDLE_TARGET, round(crop_w * scale)))
    resized_h = max(1, min(PADDLE_TARGET, round(crop_h * scale)))
    resized = cv2.resize(crop_rgb, (resized_w, resized_h), interpolation=cv2.INTER_LINEAR)
    padded = np.zeros((PADDLE_TARGET, PADDLE_TARGET, 3), dtype=np.uint8)
    padded[:resized_h, :resized_w, :] = resized

    arr = padded.astype(np.float32) / 255.0
    mean = np.array([0.485, 0.456, 0.406], dtype=np.float32)
    std = np.array([0.229, 0.224, 0.225], dtype=np.float32)
    arr = (arr - mean) / std
    tensor = np.transpose(arr, (2, 0, 1))[None, :, :, :]
    output = session.run(None, {"x": tensor})[0][0, 0]
    active = output[:resized_h, :resized_w]
    map_lines = db_postprocess(active, thresh=thresh, box_thresh=box_thresh)
    map_to_crop_x = crop_w / resized_w
    map_to_crop_y = crop_h / resized_h
    lines = []
    for line in map_lines:
        x1, y1, x2, y2 = line.bbox
        bbox = [
            int(math.floor(x1 * map_to_crop_x)),
            int(math.floor(y1 * map_to_crop_y)),
            int(math.ceil(x2 * map_to_crop_x)),
            int(math.ceil(y2 * map_to_crop_y)),
        ]
        bbox = clamp_box(bbox, crop_w, crop_h)
        if bbox[2] > bbox[0] and bbox[3] > bbox[1]:
            lines.append(TextLine(bbox=bbox, mean_score=line.mean_score))
    return lines


def db_postprocess(prob: np.ndarray, thresh: float = PADDLE_THRESH, box_thresh: float = PADDLE_BOX_THRESH) -> list[TextLine]:
    binary = (prob > thresh).astype(np.uint8)
    num, labels, stats, _ = cv2.connectedComponentsWithStats(binary, connectivity=8)
    lines: list[TextLine] = []
    for label in range(1, num):
        area = int(stats[label, cv2.CC_STAT_AREA])
        if area < MIN_DB_AREA:
            continue
        mask = labels == label
        score = float(prob[mask].mean()) if area else 0.0
        if score < box_thresh:
            continue
        x = int(stats[label, cv2.CC_STAT_LEFT])
        y = int(stats[label, cv2.CC_STAT_TOP])
        w = int(stats[label, cv2.CC_STAT_WIDTH])
        h = int(stats[label, cv2.CC_STAT_HEIGHT])
        lines.append(TextLine([x, y, x + w, y + h], score))
    return merge_line_fragments(lines)


def merge_line_fragments(lines: list[TextLine]) -> list[TextLine]:
    current = lines
    for _ in range(3):
        horiz = [l for l in current if box_w(l.bbox) >= box_h(l.bbox)]
        vert = [l for l in current if box_w(l.bbox) < box_h(l.bbox)]
        merged = merge_axis(horiz, horizontal=True) + merge_axis(vert, horizontal=False)
        if len(merged) == len(current):
            break
        current = merged
    return sorted(current, key=lambda l: (l.bbox[1], l.bbox[0]))


def merge_axis(lines: list[TextLine], horizontal: bool) -> list[TextLine]:
    if not lines:
        return []
    if horizontal:
        ordered = sorted(lines, key=lambda l: ((l.bbox[1] + l.bbox[3]) / 2, l.bbox[0]))
    else:
        ordered = sorted(lines, key=lambda l: ((l.bbox[0] + l.bbox[2]) / 2, l.bbox[1]))
    merged: list[TextLine] = []
    for line in ordered:
        b = line.bbox
        placed = False
        for m in merged:
            mb = m.bbox
            if horizontal:
                same = abs(center_y(b) - center_y(mb)) <= 0.6 * min(box_h(b), box_h(mb))
                gap = max(0, max(b[0], mb[0]) - min(b[2], mb[2]))
                allowed = gap <= 1.0 * max(box_h(b), box_h(mb))
            else:
                same = abs(center_x(b) - center_x(mb)) <= 0.6 * min(box_w(b), box_w(mb))
                gap = max(0, max(b[1], mb[1]) - min(b[3], mb[3]))
                allowed = gap <= 1.0 * max(box_w(b), box_w(mb))
            if same and allowed:
                m.bbox = [min(mb[0], b[0]), min(mb[1], b[1]), max(mb[2], b[2]), max(mb[3], b[3])]
                m.mean_score = max(m.mean_score, line.mean_score)
                placed = True
                break
        if not placed:
            merged.append(TextLine(b.copy(), line.mean_score))
    return merged


def build_rect_mask(boxes: list[list[int]], width: int, height: int, pad: int) -> np.ndarray:
    mask = np.zeros((height, width), dtype=np.uint8)
    for box in boxes:
        x1, y1, x2, y2 = clamp_box([box[0] - pad, box[1] - pad, box[2] + pad, box[3] + pad], width, height)
        if x2 > x1 and y2 > y1:
            mask[y1:y2, x1:x2] = 255
    kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (5, 5))
    return cv2.dilate(mask, kernel, iterations=1)






def inpaint_image(
    rgb: np.ndarray,
    mask: np.ndarray,
    scale: float,
    smooth_passes: int,
    feather: int,
    method: str,
) -> np.ndarray:
    if not np.any(mask):
        return rgb.copy()
    if method == "telea":
        filled = cv2.inpaint(rgb, mask, 3, cv2.INPAINT_TELEA)
        return feather_composite(rgb, filled, mask, feather)
    if method == "ns":
        filled = cv2.inpaint(rgb, mask, 3, cv2.INPAINT_NS)
        return feather_composite(rgb, filled, mask, feather)
    return aot_inpaint(rgb, mask, feather)


def aot_inpaint(rgb: np.ndarray, mask: np.ndarray, feather: int) -> np.ndarray:
    if not np.any(mask):
        return rgb.copy()
    session = get_aot()
    h, w = rgb.shape[:2]
    ys, xs = np.where(mask > 0)
    x1 = max(0, int(xs.min()) - 96)
    y1 = max(0, int(ys.min()) - 96)
    x2 = min(w, int(xs.max()) + 97)
    y2 = min(h, int(ys.max()) + 97)
    crop = rgb[y1:y2, x1:x2]
    crop_mask = mask[y1:y2, x1:x2]
    ch, cw = crop.shape[:2]

    max_dim = 512
    scale = min(1.0, max_dim / max(cw, ch))
    infer_w = max(8, int(round(cw * scale)))
    infer_h = max(8, int(round(ch * scale)))
    infer_w += (8 - infer_w % 8) % 8
    infer_h += (8 - infer_h % 8) % 8

    resized = cv2.resize(crop, (infer_w, infer_h), interpolation=cv2.INTER_LINEAR)
    resized_mask = cv2.resize(crop_mask, (infer_w, infer_h), interpolation=cv2.INTER_NEAREST)
    m = (resized_mask > 127).astype(np.float32)
    img = resized.astype(np.float32) / 127.5 - 1.0
    img = img * (1.0 - m[..., None])
    img_tensor = np.transpose(img, (2, 0, 1))[None, :, :, :].astype(np.float32)
    mask_tensor = m[None, None, :, :].astype(np.float32)
    out = session.run(None, {"image": img_tensor, "mask": mask_tensor})[0][0]
    out = np.transpose(out, (1, 2, 0))
    out = np.clip((out + 1.0) * 127.5, 0, 255).astype(np.uint8)
    out_crop = cv2.resize(out, (cw, ch), interpolation=cv2.INTER_CUBIC)
    blended_crop = feather_composite(crop, out_crop, crop_mask, feather)
    result = rgb.copy()
    result[y1:y2, x1:x2] = blended_crop
    return result


def feather_composite(rgb: np.ndarray, filled: np.ndarray, mask: np.ndarray, feather: int) -> np.ndarray:
    if feather <= 0:
        alpha = (mask > 0).astype(np.float32)
    else:
        k = feather * 2 + 1
        alpha = cv2.GaussianBlur((mask > 0).astype(np.float32), (k | 1, k | 1), 0)
        alpha = np.maximum(alpha, (mask > 0).astype(np.float32))
        alpha = np.clip(alpha, 0.0, 1.0)
    out = rgb.astype(np.float32) * (1.0 - alpha[..., None]) + filled.astype(np.float32) * alpha[..., None]
    return np.clip(out, 0, 255).astype(np.uint8)


def draw_overlay(rgb: np.ndarray, detections: list[dict], paddle_boxes: list[dict]) -> np.ndarray:
    pil = Image.fromarray(rgb).convert("RGB")
    draw = ImageDraw.Draw(pil)
    for det in detections:
        # Detector v4 text -> Bright Magenta (255, 0, 255), Bubble -> Blue (47, 112, 219)
        color = (255, 0, 255) if det["label"] in (1, 2) else (47, 112, 219)
        draw.rectangle(det["bbox"], outline=color, width=3)
    for box in paddle_boxes:
        # Paddle OCR -> Bright Green (0, 255, 0)
        draw.rectangle(box["bbox"], outline=(0, 255, 0), width=2)
    return np.array(pil)


def mask_to_rgb(mask: np.ndarray) -> np.ndarray:
    return np.repeat(mask[:, :, None], 3, axis=2)


def png_data_url(rgb: np.ndarray) -> str:
    pil = Image.fromarray(rgb)
    buf = io.BytesIO()
    pil.save(buf, format="PNG")
    b64 = base64.b64encode(buf.getvalue()).decode("ascii")
    return f"data:image/png;base64,{b64}"


def dedupe_detections(detections: list[dict]) -> list[dict]:
    keep: list[dict] = []
    for det in sorted(detections, key=lambda d: d["score"], reverse=True):
        if det["label"] in (1, 2) and any(k["label"] == det["label"] and iou(det["bbox"], k["bbox"]) > 0.75 for k in keep):
            continue
        keep.append(det)
    return sorted(keep, key=lambda d: (d["bbox"][1], d["bbox"][0]))


def clamp_box(box: list[int], width: int, height: int) -> list[int]:
    x1 = max(0, min(width, int(box[0])))
    y1 = max(0, min(height, int(box[1])))
    x2 = max(0, min(width, int(box[2])))
    y2 = max(0, min(height, int(box[3])))
    return [min(x1, x2), min(y1, y2), max(x1, x2), max(y1, y2)]


def box_w(box: list[int]) -> int:
    return box[2] - box[0]


def box_h(box: list[int]) -> int:
    return box[3] - box[1]


def center_x(box: list[int]) -> float:
    return (box[0] + box[2]) / 2


def center_y(box: list[int]) -> float:
    return (box[1] + box[3]) / 2


def iou(a: list[int], b: list[int]) -> float:
    ix1 = max(a[0], b[0])
    iy1 = max(a[1], b[1])
    ix2 = min(a[2], b[2])
    iy2 = min(a[3], b[3])
    iw = max(0, ix2 - ix1)
    ih = max(0, iy2 - iy1)
    inter = iw * ih
    area = box_w(a) * box_h(a) + box_w(b) * box_h(b) - inter
    return inter / area if area > 0 else 0.0
