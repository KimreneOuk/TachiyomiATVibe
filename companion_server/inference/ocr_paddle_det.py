from __future__ import annotations

from pathlib import Path

import cv2
import numpy as np
from PIL import Image

try:
    import onnxruntime as ort
except Exception:
    ort = None


class PaddleOcrV6DetEngine:
    """PaddleOCR v6 small text detection engine using ONNX."""

    def __init__(self, model_path: str | Path, providers: list[str] | None = None) -> None:
        if ort is None:
            raise RuntimeError("onnxruntime is not installed")
        
        self.session = ort.InferenceSession(
            str(model_path), providers=providers or ["CPUExecutionProvider"]
        )
        self.input_name = self.session.get_inputs()[0].name
        
        # PP-OCR v6 det uses 736x736 by default
        self.target_size = 736

    def _preprocess(self, image: Image.Image) -> tuple[np.ndarray, float, float]:
        img_w, img_h = image.size
        
        # Resize longer side to 736, keep aspect ratio
        ratio = 1.0
        if max(img_w, img_h) > self.target_size:
            ratio = self.target_size / max(img_w, img_h)
        else:
            ratio = self.target_size / max(img_w, img_h) # usually we scale up or down to 736
            
        resize_w = int(img_w * ratio)
        resize_h = int(img_h * ratio)
        
        # Pad to target_size x target_size
        img = image.resize((resize_w, resize_h), Image.Resampling.BILINEAR)
        img = img.convert("RGB")
        
        img_arr = np.array(img, dtype=np.float32)
        
        # Normalize
        img_arr = img_arr / 255.0
        mean = np.array([0.485, 0.456, 0.406], dtype=np.float32)
        std = np.array([0.229, 0.224, 0.225], dtype=np.float32)
        img_arr = (img_arr - mean) / std
        
        # Pad
        padded = np.zeros((self.target_size, self.target_size, 3), dtype=np.float32)
        padded[:resize_h, :resize_w, :] = img_arr
        
        # HWC -> CHW
        padded = padded.transpose(2, 0, 1)
        
        # Add batch dimension
        padded = np.expand_dims(padded, axis=0)
        
        return padded, ratio, ratio

    def detect_lines(self, crop: Image.Image, thresh: float = 0.2, box_thresh: float = 0.45) -> list[dict[str, float]]:
        """Detect text lines in a crop via DB postprocess.

        Port of Android DbPostProcess: threshold the prob map, find connected
        components, drop noise (min-area floor, max-area cap, score gate), and
        merge per-character fragments into full lines. Without these filters the
        raw contours fragment into per-pixel noise boxes on textured regions.
        Returns line boxes in crop pixel coords as {x1,y1,x2,y2,score}.
        """
        img_tensor, ratio_w, ratio_h = self._preprocess(crop)
        outputs = self.session.run(None, {self.input_name: img_tensor})
        prob_map = outputs[0][0, 0]  # (H, W) map-space

        map_h, map_w = prob_map.shape
        binary = (prob_map > thresh).astype(np.uint8)

        # Connected components → per-component bbox, pixel count, mean score.
        # cv2.connectedComponentsWithStats is the OpenCV equivalent of the
        # Android flood-fill component pass; background label 0 is skipped.
        num, labels, stats, _ = cv2.connectedComponentsWithStats(binary, connectivity=8)

        map_area = map_w * map_h
        raw: list[dict[str, float]] = []
        for comp in range(1, num):
            cx, cy, cw, ch, area = stats[comp]
            if area < _MIN_AREA_PX:
                continue  # noise speck
            if cw * ch > _MAX_COMPONENT_AREA_FRAC * map_area:
                continue  # covers too much of the map (whole-region bleed)
            comp_mask = labels == comp
            score = float(prob_map[comp_mask].mean()) if comp_mask.any() else 0.0
            if score < box_thresh:
                continue
            raw.append({"x1": float(cx), "y1": float(cy),
                        "x2": float(cx + cw), "y2": float(cy + ch),
                        "score": score})

        merged = _merge_line_fragments(raw)
        return [_back_project(b, ratio_w, ratio_h, crop.size[0], crop.size[1]) for b in merged]


# ── DbPostProcess constants (port of Android DbPostProcess.kt) ─────────

_MIN_AREA_PX = 16  # drop noise specks below this component pixel count
_MAX_COMPONENT_AREA_FRAC = 0.5  # drop components covering > 50% of the map
_SAME_LINE_FRAC = 0.6
_MERGE_GAP_FACTOR = 1.0
_MERGE_MAX_PASSES = 3


def _merge_line_fragments(lines: list[dict[str, float]]) -> list[dict[str, float]]:
    """Merge per-character fragments the connected-components step splits.

    Port of DbPostProcess.mergeLineFragments: wide boxes (w >= h) merge along
    the horizontal axis (same row), tall boxes merge along the vertical axis
    (same column), iterated to a fixed point.
    """
    if len(lines) < 2:
        return lines
    current = lines
    for _ in range(_MERGE_MAX_PASSES):
        horiz = [b for b in current if (b["x2"] - b["x1"]) >= (b["y2"] - b["y1"])]
        vert = [b for b in current if (b["x2"] - b["x1"]) < (b["y2"] - b["y1"])]
        next_lines = _merge_along_axis(horiz, "h") + _merge_along_axis(vert, "v")
        if len(next_lines) == len(current):
            break
        current = next_lines
    return current


def _merge_along_axis(group: list[dict[str, float]], axis: str) -> list[dict[str, float]]:
    if not group:
        return group
    if axis == "h":
        key = lambda b: ((b["y1"] + b["y2"]) / 2, b["x1"])
    else:
        key = lambda b: ((b["x1"] + b["x2"]) / 2, b["y1"])
    sorted_group = sorted(group, key=key)

    merged: list[dict[str, float]] = []
    for b in sorted_group:
        if axis == "h":
            cross = (b["y1"] + b["y2"]) / 2
            cross_size = b["y2"] - b["y1"]
            gap_size = b["y2"] - b["y1"]
        else:
            cross = (b["x1"] + b["x2"]) / 2
            cross_size = b["x2"] - b["x1"]
            gap_size = b["y2"] - b["y1"]
        placed = False
        for m in merged:
            if axis == "h":
                m_cross = (m["y1"] + m["y2"]) / 2
                m_cross_size = m["y2"] - m["y1"]
                m_gap_size = m["y2"] - m["y1"]
                same_line = abs(cross - m_cross) <= _SAME_LINE_FRAC * min(cross_size, m_cross_size)
                gap = max(0, max(b["x1"], m["x1"]) - min(b["x2"], m["x2"]))
            else:
                m_cross = (m["x1"] + m["x2"]) / 2
                m_cross_size = m["x2"] - m["x1"]
                m_gap_size = m["y2"] - m["y1"]
                same_line = abs(cross - m_cross) <= _SAME_LINE_FRAC * min(cross_size, m_cross_size)
                gap = max(0, max(b["y1"], m["y1"]) - min(b["y2"], m["y2"]))
            if not same_line:
                continue
            if gap <= _MERGE_GAP_FACTOR * max(gap_size, m_gap_size):
                m["x1"] = min(m["x1"], b["x1"])
                m["y1"] = min(m["y1"], b["y1"])
                m["x2"] = max(m["x2"], b["x2"])
                m["y2"] = max(m["y2"], b["y2"])
                m["score"] = max(m["score"], b["score"])
                placed = True
                break
        if not placed:
            merged.append(dict(b))
    return merged


def _back_project(b: dict[str, float], ratio_w: float, ratio_h: float,
                  crop_w: int, crop_h: int) -> dict[str, float]:
    """Map-space bbox → crop pixel coords (DbPostProcess.backProject port)."""
    x1 = min(max(int(round(b["x1"] / ratio_w)), 0), crop_w - 1)
    y1 = min(max(int(round(b["y1"] / ratio_h)), 0), crop_h - 1)
    x2 = min(max(int(round(b["x2"] / ratio_w)), 0), crop_w - 1)
    y2 = min(max(int(round(b["y2"] / ratio_h)), 0), crop_h - 1)
    return {"x1": float(min(x1, x2)), "y1": float(min(y1, y2)),
            "x2": float(max(x1, x2)), "y2": float(max(y1, y2)),
            "score": b["score"]}
