"""Optional Android-compatible panel detection and assignment context."""
from __future__ import annotations

import math
from pathlib import Path

import numpy as np
from PIL import Image

REPO_ROOT = Path(__file__).resolve().parents[2]
PANEL_MODEL = (REPO_ROOT / "app/src/main/assets/models/detection"
               / "manga_panel_detector_int8.onnx")
INPUT_SIZE = 640
RAW_SCORE_FLOOR = 0.05
CONFIDENCE_THRESHOLD = 0.5
NMS_IOU_THRESHOLD = 0.45


class PanelDetector:
    """YOLO26-nano panel wrapper: gray letterbox, NCHW /255, page-space xyxy."""

    def __init__(self, path: Path = PANEL_MODEL):
        self.path = path
        self._session = None
        self._input_name: str | None = None

    @property
    def ready(self) -> bool:
        return self.path.is_file()

    def _get_session(self):
        if self._session is None:
            if not self.ready:
                raise FileNotFoundError(self.path)
            import onnxruntime as ort
            options = ort.SessionOptions()
            options.log_severity_level = 3
            self._session = ort.InferenceSession(
                str(self.path), options, providers=["CPUExecutionProvider"])
            self._input_name = self._session.get_inputs()[0].name
        return self._session

    def detect_candidates(self, image: Image.Image) -> list[dict]:
        session = self._get_session()
        width, height = image.size
        if width <= 0 or height <= 0:
            return []
        ratio = min(INPUT_SIZE / width, INPUT_SIZE / height)
        resized_w, resized_h = int(width * ratio), int(height * ratio)
        pad_x, pad_y = (INPUT_SIZE - resized_w) // 2, (INPUT_SIZE - resized_h) // 2
        canvas = Image.new("RGB", (INPUT_SIZE, INPUT_SIZE), (114, 114, 114))
        resized = image.convert("RGB").resize((resized_w, resized_h), Image.BILINEAR)
        canvas.paste(resized, (pad_x, pad_y))
        data = np.asarray(canvas, dtype=np.float32) / np.float32(255.0)
        tensor = np.ascontiguousarray(data.transpose(2, 0, 1))[None]
        result = session.run(None, {self._input_name: tensor})[0]
        rows = np.asarray(result)[0]
        candidates = []
        for row in rows:
            if len(row) < 5:
                continue
            score = float(row[4])
            if not math.isfinite(score) or score < RAW_SCORE_FLOOR:
                continue
            x1, y1, x2, y2 = map(float, row[:4])
            if not _valid_box((x1, y1, x2, y2)):
                continue
            # Keep the unletterboxed page box for capture/assignment and the
            # model-space box for Android-order NMS (before clipping).
            model_box = [x1, y1, x2, y2]
            page_box = [
                min(float(width), max(0.0, (x1 - pad_x) / ratio)),
                min(float(height), max(0.0, (y1 - pad_y) / ratio)),
                min(float(width), max(0.0, (x2 - pad_x) / ratio)),
                min(float(height), max(0.0, (y2 - pad_y) / ratio)),
            ]
            candidates.append({"label": 0, "score": score,
                               "box": page_box, "page_box_valid": _valid_box(page_box),
                               "model_box": model_box})
        return candidates


def _valid_box(box) -> bool:
    return all(math.isfinite(float(v)) for v in box) and box[2] > box[0] and box[3] > box[1]


def _intersection(a, b) -> float:
    w = min(a[2], b[2]) - max(a[0], b[0])
    h = min(a[3], b[3]) - max(a[1], b[1])
    return max(0.0, w) * max(0.0, h)


def _iou(a, b) -> float:
    intersection = _intersection(a, b)
    union = ((a[2] - a[0]) * (a[3] - a[1])
             + (b[2] - b[0]) * (b[3] - b[1]) - intersection)
    return intersection / union if union > 0 else 0.0


def panel_nms(candidates: list[dict], ids: list[str]) -> tuple[list[int], list[dict]]:
    """Greedy class-0 NMS (strict IoU > .45) with auditable suppression."""
    if len(candidates) != len(ids):
        raise ValueError("candidates and ids must have equal lengths")
    eligible = [i for i, item in enumerate(candidates)
                if item["score"] >= CONFIDENCE_THRESHOLD]
    kept: list[int] = []
    suppressed: list[dict] = []
    for i in sorted(eligible, key=lambda ix: -candidates[ix]["score"]):
        winner = next((k for k in kept
                       if _iou(candidates[i]["model_box"],
                               candidates[k]["model_box"]) > NMS_IOU_THRESHOLD), None)
        if winner is None:
            kept.append(i)
        else:
            suppressed.append({"loser_id": ids[i], "winner_id": ids[winner],
                               "rule": "panel-nms",
                               "threshold": NMS_IOU_THRESHOLD})
    return sorted(kept, key=lambda ix: -candidates[ix]["score"]), suppressed


def reading_order_panel_indices(panels: list[list[float]], rtl: bool = True) -> list[int]:
    """Port of ReadingOrderSorter.readingOrderPanels (recursive XY-cut)."""
    def widest_gutter(indices: list[int], axis: str):
        events = []
        for idx in indices:
            panel = panels[idx]
            lo, hi = ((panel[1], panel[3]) if axis == "y"
                      else (panel[0], panel[2]))
            events.extend(((lo, 1), (hi, -1)))
        events.sort(key=lambda event: event[0] * 2 - event[1])
        best_start = None
        best_width = 1.0
        count = 0
        previous = None
        for coordinate, event in events:
            if count == 0 and previous is not None:
                gap = coordinate - previous
                if gap >= 1.0 and gap > best_width:
                    best_start, best_width = previous, gap
            count += event
            previous = coordinate
        if best_start is None:
            return None
        cut = best_start + best_width / 2.0
        first, second = [], []
        for idx in indices:
            panel = panels[idx]
            center = ((panel[1] + panel[3]) / 2.0 if axis == "y"
                      else (panel[0] + panel[2]) / 2.0)
            (first if center <= cut else second).append(idx)
        return (first, second) if first and second else None

    def fallback(indices: list[int]) -> list[int]:
        return sorted(indices, key=lambda idx: (
            -(panels[idx][0] + panels[idx][2]) / 2.0 if rtl
            else (panels[idx][0] + panels[idx][2]) / 2.0,
            panels[idx][1]))

    def order(indices: list[int]) -> list[int]:
        if len(indices) <= 1:
            return indices
        horizontal = widest_gutter(indices, "y")
        if horizontal is not None:
            return order(horizontal[0]) + order(horizontal[1])
        vertical = widest_gutter(indices, "x")
        if vertical is not None:
            first, second = vertical
            return order(second) + order(first) if rtl else order(first) + order(second)
        return fallback(indices)

    return order(list(range(len(panels))))


def assign_panel(box: list[int | float], ordered_panels: list[dict]) -> dict:
    """Port of PanelAssignment.assign, reporting advisory and owned context."""
    if not _valid_box(box):
        return {"panel_index": None, "panel_id": None,
                "best_panel_index": None, "best_panel_id": None,
                "best_containment": 0.0, "category": "invalid"}
    if not ordered_panels:
        return {"panel_index": None, "panel_id": None,
                "best_panel_index": None, "best_panel_id": None,
                "best_containment": 0.0, "category": "orphan"}
    area = (box[2] - box[0]) * (box[3] - box[1])
    best_index, best_containment = None, 0.0
    for index, panel in enumerate(ordered_panels):
        geometry = panel["box"]
        if not _valid_box(geometry):
            continue
        containment = _intersection(box, geometry) / area if area > 0 else 0.0
        if containment > best_containment:
            best_index, best_containment = index, containment
    if best_index is None:
        return {"panel_index": None, "panel_id": None,
                "best_panel_index": None, "best_panel_id": None,
                "best_containment": 0.0, "category": "free_floating"}
    if best_containment >= 0.80:
        category, panel_index = "owned", best_index
    elif best_containment >= 0.10:
        category, panel_index = "spanning", None
    else:
        category, panel_index = "free_floating", None
    panel_id = ordered_panels[best_index]["id"]
    return {"panel_index": panel_index,
            "panel_id": panel_id if category == "owned" else None,
            "best_panel_index": best_index,
            "best_panel_id": panel_id,
            "best_containment": best_containment,
            "category": category}
