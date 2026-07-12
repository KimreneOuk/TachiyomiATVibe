"""Faithful Python port of OnnxPanelDetector.kt — YOLO26 manga panel detector.

Uses onnxruntime (CPU) to run the YOLO26 model exported from
leoxs22/manga-panel-detector-yolo26n. Single input `images [1,3,640,640]`,
single output `output0 [1,300,6]` where 6 = 4(xyxy) + 2 class confs.

NMS is NOT baked in — this class runs per-class greedy NMS.
"""
from __future__ import annotations

import logging
import sys
from pathlib import Path

import numpy as np
from PIL import Image

logger = logging.getLogger("overlay_lab")

# Ensure companion_server is importable (same pattern as pipeline.py)
from backend import config

_CS = str(config.COMPANION_SERVER)
if _CS not in sys.path:
    sys.path.insert(0, _CS)

try:
    import onnxruntime as ort
except Exception:
    ort = None

from inference.detector import DetectionBox

# ── Module-level constants ────────────────────────────────────────────────
# Per-class confidence thresholds. Frame needs a high bar (0.9) to avoid
# false panel boxes around text/art; text stays at the model's natural 0.5.
# NB: OnnxPanelDetector.kt uses a single 0.5 for class 0 only; this lab
# port raises the frame bar because the lab surfaces BOTH classes.
IMG_SIZE = 640
CONF_FRAME = 0.9
CONF_TEXT = 0.5
CONF_THRESHOLD = 0.5  # kept for test compatibility / general floor
IOU_THRESHOLD = 0.45
PAD_COLOR = 114  # grey, ultralytics default


class OnnxPanelDetector:
    """YOLO26-nano manga panel detector.

    Loads ``manga_panel_detector_int8.onnx`` and returns panel/text bounding
    boxes in original-image coordinates.

    Args:
        model_path: Path to the ONNX model file.
    """

    def __init__(self, model_path: str | Path) -> None:
        if ort is None:
            raise RuntimeError("onnxruntime is not installed")
        path = Path(model_path)
        if not path.exists():
            raise FileNotFoundError(f"Panel detector model not found: {path}")
        self.session = ort.InferenceSession(
            str(path),
            providers=["CPUExecutionProvider"],
        )
        logger.info(
            "PanelDetector loaded: %s (inputs=%s, outputs=%s)",
            path.name,
            [n.name for n in self.session.get_inputs()],
            [n.name for n in self.session.get_outputs()],
        )

    # ── Public API ───────────────────────────────────────────────────────

    def detect(self, image: Image.Image) -> list[DetectionBox]:
        """Run panel detection on *image*.

        Returns a list of :class:`DetectionBox` with label 0 (frame) or
        label 1 (text), in original-image coordinates, after per-class NMS.
        """
        orig_w, orig_h = image.size

        # 1. Letterbox: grey-114 pad, preserve aspect ratio
        ratio = min(IMG_SIZE / orig_w, IMG_SIZE / orig_h)
        new_w = int(orig_w * ratio)
        new_h = int(orig_h * ratio)
        pad_w = (IMG_SIZE - new_w) // 2
        pad_h = (IMG_SIZE - new_h) // 2

        letterboxed = Image.new("RGB", (IMG_SIZE, IMG_SIZE), (PAD_COLOR, PAD_COLOR, PAD_COLOR))
        resized = image.resize((new_w, new_h), Image.Resampling.BILINEAR)
        letterboxed.paste(resized, (pad_w, pad_h))

        # 2. NCHW float32 /255.0, shape [1,3,640,640]
        arr = np.asarray(letterboxed, dtype=np.float32) / 255.0
        nchw = np.transpose(arr, (2, 0, 1))[None, :, :, :]  # (1, 3, 640, 640)

        # 3. Run session
        input_name = self.session.get_inputs()[0].name
        outputs = self.session.run(None, {input_name: nchw})
        raw_output = outputs[0]  # (1, N, 6)

        # 4. Decode
        batch = raw_output[0]  # (N, 6)
        candidates_by_class: dict[int, list[tuple[float, float, float, float, float]]] = {
            0: [],
            1: [],
        }

        for row in batch:
            x1, y1, x2, y2 = float(row[0]), float(row[1]), float(row[2]), float(row[3])
            cls0_conf = float(row[4])
            cls1_conf = float(row[5])

            # Validate box (non-NaN, positive area)
            if not self._is_valid_box(x1, y1, x2, y2):
                continue

            for cls, conf in ((0, cls0_conf), (1, cls1_conf)):
                # Per-class confidence gate: frame strict (0.9), text loose (0.5).
                threshold = CONF_FRAME if cls == 0 else CONF_TEXT
                if conf >= threshold:
                    candidates_by_class[cls].append((x1, y1, x2, y2, conf))

        # 5. Per-class greedy NMS
        all_boxes: list[DetectionBox] = []
        for cls, candidates in candidates_by_class.items():
            kept = self._nms(candidates, IOU_THRESHOLD)
            for (x1, y1, x2, y2, score) in kept:
                # Map back from 640x640 letterbox space -> original
                ox1 = max(0.0, min(orig_w, (x1 - pad_w) / ratio))
                oy1 = max(0.0, min(orig_h, (y1 - pad_h) / ratio))
                ox2 = max(0.0, min(orig_w, (x2 - pad_w) / ratio))
                oy2 = max(0.0, min(orig_h, (y2 - pad_h) / ratio))

                if not self._is_valid_box(ox1, oy1, ox2, oy2):
                    continue

                all_boxes.append(DetectionBox(
                    x1=round(ox1, 3),
                    y1=round(oy1, 3),
                    x2=round(ox2, 3),
                    y2=round(oy2, 3),
                    label=cls,
                    score=round(score, 4),
                ))

        return all_boxes

    # ── Internal helpers ─────────────────────────────────────────────────

    @staticmethod
    def _is_valid_box(x1: float, y1: float, x2: float, y2: float) -> bool:
        """Return True if the box has non-NaN coords and positive area."""
        if any(np.isnan(v) for v in (x1, y1, x2, y2)):
            return False
        return (x2 - x1) > 0 and (y2 - y1) > 0

    @staticmethod
    def _nms(
        candidates: list[tuple[float, float, float, float, float]],
        iou_threshold: float,
    ) -> list[tuple[float, float, float, float, float]]:
        """Greedy NMS: sort by confidence descending, suppress overlaps."""
        if not candidates:
            return []

        sorted_cands = sorted(candidates, key=lambda c: c[4], reverse=True)
        keep: list[tuple[float, float, float, float, float]] = []

        for cand in sorted_cands:
            suppressed = False
            for kept in keep:
                if OnnxPanelDetector._iou(cand, kept) > iou_threshold:
                    suppressed = True
                    break
            if not suppressed:
                keep.append(cand)

        return keep

    @staticmethod
    def _iou(
        a: tuple[float, float, float, float, float],
        b: tuple[float, float, float, float, float],
    ) -> float:
        """Compute IoU between two boxes (x1, y1, x2, y2, ...)."""
        ix1 = max(a[0], b[0])
        iy1 = max(a[1], b[1])
        ix2 = min(a[2], b[2])
        iy2 = min(a[3], b[3])
        iw = max(0.0, ix2 - ix1)
        ih = max(0.0, iy2 - iy1)
        inter = iw * ih
        area_a = max(0.0, a[2] - a[0]) * max(0.0, a[3] - a[1])
        area_b = max(0.0, b[2] - b[0]) * max(0.0, b[3] - b[1])
        union = area_a + area_b - inter
        return inter / union if union > 0 else 0.0