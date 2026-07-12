from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

import numpy as np
from PIL import Image

try:  # Optional so tests run without ONNX Runtime installed.
    import onnxruntime as ort
except Exception:  # pragma: no cover - environment dependent
    ort = None


@dataclass(frozen=True)
class DetectionBox:
    x1: float
    y1: float
    x2: float
    y2: float
    label: int
    score: float

    def to_json(self) -> dict[str, float | int]:
        return {
            "x1": round(self.x1, 3),
            "y1": round(self.y1, 3),
            "x2": round(self.x2, 3),
            "y2": round(self.y2, 3),
            "label": self.label,
            "score": round(self.score, 3),
        }


def area(box: DetectionBox) -> float:
    return max(0.0, box.x2 - box.x1) * max(0.0, box.y2 - box.y1)


def iou(left: DetectionBox, right: DetectionBox) -> float:
    ix1 = max(left.x1, right.x1)
    iy1 = max(left.y1, right.y1)
    ix2 = min(left.x2, right.x2)
    iy2 = min(left.y2, right.y2)
    intersection = max(0.0, ix2 - ix1) * max(0.0, iy2 - iy1)
    union = area(left) + area(right) - intersection
    return 0.0 if union <= 0.0 else intersection / union


def containment(inner: DetectionBox, outer: DetectionBox) -> float:
    inner_area = area(inner)
    if inner_area <= 0.0:
        return 0.0
    ix1 = max(inner.x1, outer.x1)
    iy1 = max(inner.y1, outer.y1)
    ix2 = min(inner.x2, outer.x2)
    iy2 = min(inner.y2, outer.y2)
    intersection = max(0.0, ix2 - ix1) * max(0.0, iy2 - iy1)
    return intersection / inner_area


def non_max_suppression(
    boxes: list[DetectionBox],
    score_threshold: float = 0.45,
    iou_threshold: float = 0.75,
    containment_threshold: float = 0.88,
    center_threshold: float = 0.12,
    size_threshold: float = 0.18,
) -> list[DetectionBox]:
    candidates = sorted(
        (box for box in boxes if box.score >= score_threshold),
        key=lambda box: box.score,
        reverse=True,
    )
    kept: list[DetectionBox] = []
    for box in candidates:
        duplicate = any(
            iou(box, kept_box) >= iou_threshold
            or containment(box, kept_box) >= containment_threshold
            or close_center_and_size(box, kept_box, center_threshold, size_threshold)
            for kept_box in kept
        )
        if not duplicate:
            kept.append(box)
    return kept


def close_center_and_size(
    left: DetectionBox,
    right: DetectionBox,
    center_threshold: float,
    size_threshold: float,
) -> bool:
    left_w = max(1.0, left.x2 - left.x1)
    left_h = max(1.0, left.y2 - left.y1)
    right_w = max(1.0, right.x2 - right.x1)
    right_h = max(1.0, right.y2 - right.y1)
    center_dx = abs((left.x1 + left.x2) - (right.x1 + right.x2)) / 2.0
    center_dy = abs((left.y1 + left.y2) - (right.y1 + right.y2)) / 2.0
    return (
        center_dx <= center_threshold * min(left_w, right_w)
        and center_dy <= center_threshold * min(left_h, right_h)
        and abs(left_w - right_w) <= size_threshold * max(left_w, right_w)
        and abs(left_h - right_h) <= size_threshold * max(left_h, right_h)
    )


def deterministic_detect(width: int, height: int) -> list[DetectionBox]:
    box_width = max(20.0, width * 0.25)
    box_height = max(16.0, height * 0.08)
    return [
        DetectionBox(
            x1=width * 0.1,
            y1=height * 0.1,
            x2=width * 0.1 + box_width,
            y2=height * 0.1 + box_height,
            label=1,
            score=0.95,
        )
    ]


class OnnxPageTextDetector:
    class_names = {
        0: "bubble",
        1: "text_bubble",
        2: "text_free",
    }

    def __init__(self, model_path: str | Path, providers: list[str] | None = None) -> None:
        if ort is None:
            raise RuntimeError("onnxruntime is not installed")
        path = Path(model_path)
        if not path.exists():
            raise FileNotFoundError(path)
        self.session = ort.InferenceSession(
            str(path),
            providers=providers or ["CPUExecutionProvider"],
        )

    def detect(self, image: Image.Image) -> list[DetectionBox]:
        width, height = image.size
        inputs = {
            "images": self._preprocess(image),
            "orig_target_sizes": np.array([[width, height]], dtype=np.int64),
        }
        labels, boxes, scores = self.session.run(None, inputs)
        raw = self._postprocess(labels[0], boxes[0], scores[0])
        text_labels = [box for box in raw if box.label in (1, 2)]
        non_text = [box for box in raw if box.label not in (1, 2)]
        return non_text + non_max_suppression(text_labels)

    def _preprocess(self, image: Image.Image) -> np.ndarray:
        resized = image.convert("RGB").resize((640, 640), Image.Resampling.BILINEAR)
        data = np.asarray(resized, dtype=np.float32) / 255.0
        return np.transpose(data, (2, 0, 1))[None, :, :, :]

    def _postprocess(
        self,
        labels: np.ndarray,
        boxes: np.ndarray,
        scores: np.ndarray,
        score_threshold: float = 0.45,
    ) -> list[DetectionBox]:
        detections: list[DetectionBox] = []
        for label, box, score in zip(labels, boxes, scores):
            score_value = float(score)
            if np.isnan(score_value) or score_value < score_threshold:
                continue
            detections.append(
                DetectionBox(
                    x1=float(box[0]),
                    y1=float(box[1]),
                    x2=float(box[2]),
                    y2=float(box[3]),
                    label=int(label),
                    score=round(score_value, 4),
                )
            )
        return detections
