"""Faithful Python port of PaddleOcrV6DetEngine.kt + DbPostProcess.kt.

Loads ``models/ocr/paddle-v6-small/det/inference.onnx`` (PP-OCRv6 small DET,
a DB / Differentiable Binarization text-line detector) and returns tight axis-
aligned text-line boxes in original-image coordinates.

This drives the free-text erase-mask in the lab exactly as it does on Android:
the det model emits a ``[1,1,H,W]`` probability map, which is binarized,
connected-component labeled, filtered by mean-score + area, and merged into
line/column groups. The result is a set of tight line rectangles — NOT pixel
masks — that the inpainter turns into a solid pill mask.

Pure numpy + onnxruntime (CPU). cv2 is used only for the bilinear resize and
trivial morphology — portable to the Android constraint (no cv2.inpaint).
"""
from __future__ import annotations

import logging
from dataclasses import dataclass
from pathlib import Path

import numpy as np
from PIL import Image

logger = logging.getLogger("overlay_lab")

try:
    import onnxruntime as ort
except Exception:  # pragma: no cover - env-dependent
    ort = None

try:
    import cv2
except Exception:  # pragma: no cover - env-dependent
    cv2 = None


# ── model constants (PaddleOcrV6DetEngine.kt companion) ────────────────
TARGET = 736
# ImageNet normalization, applied per RGB plane (channel-correct).
_MEAN = np.array([0.485, 0.456, 0.406], dtype=np.float32)  # R, G, B
_STD = np.array([0.229, 0.224, 0.225], dtype=np.float32)   # R, G, B

# ── DbPostProcess.kt constants ─────────────────────────────────────────
DEFAULT_THRESH = 0.2       # prob-map binarization threshold
DEFAULT_BOX_THRESH = 0.45  # min mean score per component
MAX_CANDIDATES = 3000
MAX_COMPONENT_AREA_FRAC = 0.5
MIN_AREA_PX = 16
SAME_LINE_FRAC = 0.6
MERGE_GAP_FACTOR = 1.0
MERGE_MAX_PASSES = 3

# Erase-path thresholds (AOTInpainting.kt: PADDLE_THRESH / PADDLE_BOX_THRESH)
# Lower than the OCR-rec defaults so faint SFX/narration text still gets erased.
ERASE_THRESH = 0.18
ERASE_BOX_THRESH = 0.34


@dataclass
class TextLine:
    """One detected text line.

    ``bbox`` is ``[x1, y1, x2, y2]`` axis-aligned with INCLUSIVE max, in the
    coordinate space of the image passed to ``detect_lines``.
    """
    bbox: list[int]
    mean_score: float


# ── DbPostProcess — pure numpy port ────────────────────────────────────


class DbPost:
    """Pure-numpy port of DbPostProcess.kt (postprocess + merge + backproject)."""

    @staticmethod
    def detect_lines(
        prob_map: np.ndarray,
        thresh: float = DEFAULT_THRESH,
        box_thresh: float = DEFAULT_BOX_THRESH,
        max_candidates: int = MAX_CANDIDATES,
    ) -> list[TextLine]:
        """Run the DB postprocess on a probability map.

        Args:
            prob_map: ``HxW`` float32 probability map in ``[0,1]``.
            thresh: binarization threshold (strict greater-than).
            box_thresh: minimum mean foreground score per component.
            max_candidates: early-exit cap on component count.

        Returns merged, clamped, sorted ``TextLine`` list (map space).
        """
        h, w = prob_map.shape
        if h <= 0 or w <= 0:
            return []

        # 1. Binarize (STRICT greater-than, matches Kotlin `probMap[i] > thresh`).
        binary = prob_map > thresh

        if not binary.any():
            return []

        # 2. Connected components (8-conn flood fill). Compute probSum inside
        #    the flood by indexing prob at each popped foreground pixel.
        components = DbPost._flood_with_prob(binary, prob_map, max_candidates)
        if not components:
            return []

        # 3. Finalize: mean-score gate + area gate + box extraction.
        out: list[TextLine] = []
        area_cap = MAX_COMPONENT_AREA_FRAC * w * h
        for minX, minY, maxX, maxY, count, prob_sum in components:
            if count < MIN_AREA_PX:
                continue
            mean_score = prob_sum / count if count > 0 else 0.0
            if mean_score < box_thresh:
                continue
            area = (maxX - minX + 1) * (maxY - minY + 1)
            if area > area_cap:
                continue
            out.append(TextLine(bbox=[minX, minY, maxX, maxY], mean_score=mean_score))

        if not out:
            return []

        # 4. Merge fragments, clamp to map bounds, sort by (y1, x1).
        out = DbPost.merge_line_fragments(out)
        clamped: list[TextLine] = []
        for tl in out:
            b = tl.bbox
            b[0] = max(0, min(w - 1, b[0]))
            b[1] = max(0, min(h - 1, b[1]))
            b[2] = max(0, min(w - 1, b[2]))
            b[3] = max(0, min(h - 1, b[3]))
            clamped.append(TextLine(bbox=b, mean_score=tl.mean_score))
        clamped.sort(key=lambda t: (t.bbox[1], t.bbox[0]))
        return clamped

    @staticmethod
    def _flood_with_prob(
        binary: np.ndarray, prob: np.ndarray, max_candidates: int,
    ) -> list[tuple[int, int, int, int, int, float]]:
        """8-conn flood-fill CCL that also accumulates per-component prob sum.

        Returns list of (minX, minY, maxX, maxY, count, probSum).
        """
        h, w = binary.shape
        labels = np.zeros((h, w), dtype=np.int32)
        components: list[tuple[int, int, int, int, int, float]] = []
        next_label = 1
        ys, xs = np.where(binary)
        for sy, sx in zip(ys.tolist(), xs.tolist()):
            if labels[sy, sx] != 0:
                continue
            minX = maxX = sx
            minY = maxY = sy
            count = 0
            prob_sum = 0.0
            stack = [(sx, sy)]
            labels[sy, sx] = next_label
            while stack:
                x, y = stack.pop()
                count += 1
                prob_sum += float(prob[y, x])
                if x < minX: minX = x
                elif x > maxX: maxX = x
                if y < minY: minY = y
                elif y > maxY: maxY = y
                x0 = x - 1 if x > 0 else 0
                x1 = x + 1 if x < w - 1 else w - 1
                y0 = y - 1 if y > 0 else 0
                y1 = y + 1 if y < h - 1 else h - 1
                for ny in range(y0, y1 + 1):
                    lab_row = labels[ny]
                    bin_row = binary[ny]
                    for nx in range(x0, x1 + 1):
                        if bin_row[nx] and lab_row[nx] == 0:
                            lab_row[nx] = next_label
                            stack.append((nx, ny))
            components.append((minX, minY, maxX, maxY, count, prob_sum))
            next_label += 1
            if len(components) >= max_candidates:
                break
        return components

    @staticmethod
    def merge_line_fragments(
        lines: list[TextLine],
        same_line_frac: float = SAME_LINE_FRAC,
        merge_gap_factor: float = MERGE_GAP_FACTOR,
    ) -> list[TextLine]:
        """Port of DbPostProcess.mergeLineFragments.

        Up to ``MERGE_MAX_PASSES`` passes. Each pass re-splits into horizontal
        (w>=h) and vertical (w<h) groups by current aspect, merges each group
        along its axis, and concatenates. Stops at a fixed point.
        """
        if len(lines) < 2:
            return lines

        current = list(lines)
        for _ in range(MERGE_MAX_PASSES):
            horiz = [t for t in current if (t.bbox[2] - t.bbox[0]) >= (t.bbox[3] - t.bbox[1])]
            vert = [t for t in current if (t.bbox[2] - t.bbox[0]) < (t.bbox[3] - t.bbox[1])]
            merged_h = DbPost._merge_along_axis(horiz, "HORIZONTAL", same_line_frac, merge_gap_factor)
            merged_v = DbPost._merge_along_axis(vert, "VERTICAL", same_line_frac, merge_gap_factor)
            nxt = merged_h + merged_v
            if len(nxt) == len(current):
                break
            current = nxt
        return current

    @staticmethod
    def _merge_along_axis(
        lines: list[TextLine], axis: str, same_line_frac: float, merge_gap_factor: float,
    ) -> list[TextLine]:
        """Port of DbPostProcess.mergeAlongAxis (greedy first-fit)."""
        if not lines:
            return []

        def sort_key(t: TextLine):
            b = t.bbox
            if axis == "HORIZONTAL":
                return ((b[1] + b[3]) // 2, b[0])
            return ((b[0] + b[2]) // 2, b[1])

        ordered = sorted(lines, key=sort_key)

        def descriptors(b: list[int]):
            """Returns (centerCross, crossSize, gapSize) per the Kotlin logic."""
            if axis == "HORIZONTAL":
                # centerCross = vertical center; crossSize = height; gapSize = height
                h = b[3] - b[1]
                return (b[1] + b[3]) / 2, h, h
            # VERTICAL: centerCross = horizontal center; crossSize = width;
            # gapSize = HEIGHT (intentional — see Kotlin comment).
            w = b[2] - b[0]
            h = b[3] - b[1]
            return (b[0] + b[2]) / 2, w, h

        merged: list[list] = []  # each entry: [bbox(list), score(float)]
        for t in ordered:
            b = list(t.bbox)
            cc, cs, gs = descriptors(b)
            placed = False
            for m in merged:
                mb = m[0]
                mcc, mcs, mgs = descriptors(mb)
                same_line = abs(cc - mcc) <= same_line_frac * min(cs, mcs)
                if not same_line:
                    continue
                # Along-axis gap.
                if axis == "HORIZONTAL":
                    gap = max(0, max(b[0], mb[0]) - min(b[2], mb[2]))
                else:
                    gap = max(0, max(b[1], mb[1]) - min(b[3], mb[3]))
                if gap <= merge_gap_factor * max(gs, mgs):
                    # Union bbox, max score.
                    mb[0] = min(mb[0], b[0])
                    mb[1] = min(mb[1], b[1])
                    mb[2] = max(mb[2], b[2])
                    mb[3] = max(mb[3], b[3])
                    m[1] = max(m[1], t.mean_score)
                    placed = True
                    break
            if not placed:
                merged.append([b, t.mean_score])
        return [TextLine(bbox=m[0], mean_score=m[1]) for m in merged]

    @staticmethod
    def back_project(
        bbox: list[int], scale_x: float, scale_y: float, w: int, h: int,
    ) -> list[int]:
        """Port of DbPostProcess.backProject.

        ``scale = crop/map``; truncate-toward-zero, clamp to ``[0, dim-1]``,
        reorder to ``(min, max)``.
        """
        def tp(v: int, s: float, dim: int) -> int:
            if s <= 0:
                return 0
            return max(0, min(dim - 1, int(v * s)))

        x1 = tp(bbox[0], scale_x, w)
        y1 = tp(bbox[1], scale_y, h)
        x2 = tp(bbox[2], scale_x, w)
        y2 = tp(bbox[3], scale_y, h)
        return [min(x1, x2), min(y1, y2), max(x1, x2), max(y1, y2)]


# ── PaddleOcrV6DetEngine port ──────────────────────────────────────────


class PaddleOcrV6Det:
    """Faithful port of PaddleOcrV6DetEngine.

    Args:
        model_path: path to ``det/inference.onnx``.
    """

    def __init__(self, model_path: str | Path) -> None:
        if ort is None:
            raise RuntimeError("onnxruntime is not installed")
        if cv2 is None:
            raise RuntimeError("cv2 is required for the bilinear resize")
        path = Path(model_path)
        if not path.exists():
            raise FileNotFoundError(f"Paddle DET model not found: {path}")
        self.session = ort.InferenceSession(
            str(path), providers=["CPUExecutionProvider"],
        )
        # Input name: first input, fallback "x" (matches Kotlin).
        self.input_name = self.session.get_inputs()[0].name or "x"
        self.output_name = self.session.get_outputs()[0].name
        logger.info(
            "PaddleOcrV6Det loaded: %s (in=%s, out=%s)",
            path.name, self.input_name, self.output_name,
        )

    def detect_lines(
        self,
        image: Image.Image,
        thresh: float = ERASE_THRESH,
        box_thresh: float = ERASE_BOX_THRESH,
    ) -> list[TextLine]:
        """Detect text lines in *image* (any size). Returns crop-space boxes.

        Defaults to the ERASE thresholds (0.18 / 0.34) used by the free-text
        erase path on Android — lower than OCR-rec (0.2 / 0.45) so faint SFX
        and narration still get erased.
        """
        w, h = image.size
        if w <= 0 or h <= 0:
            return []

        # 1. Resize: scale = 736 / max(w,h), round, clip to [1, 736].
        scale = TARGET / float(max(w, h))
        resized_w = max(1, min(TARGET, int(round(w * scale))))
        resized_h = max(1, min(TARGET, int(round(h * scale))))
        resized = image.resize((resized_w, resized_h), Image.Resampling.BILINEAR)

        # 2. Pad to 736x736 with BLACK bottom-right (Paddle DetResizeForTest).
        padded = Image.new("RGB", (TARGET, TARGET), (0, 0, 0))
        padded.paste(resized, (0, 0))

        # 3. NCHW float32, per-channel (v/255 - mean)/std, RGB plane order.
        arr = np.asarray(padded, dtype=np.float32) / 255.0
        arr = (arr - _MEAN) / _STD
        nchw = np.transpose(arr, (2, 0, 1))[None, :, :, :]  # (1, 3, 736, 736)

        # 4. Run session.
        outputs = self.session.run(None, {self.input_name: nchw})
        prob = outputs[0][0, 0]  # (736, 736) — squeeze batch + channel

        # 5. Active region: crop the [:resizedH, :resizedW] block (rest is pad).
        map_w = prob.shape[1]
        map_h = prob.shape[0]
        aw = min(resized_w, map_w)
        ah = min(resized_h, map_h)
        active = prob[:ah, :aw]

        # 6. Back-projection scale: crop/map = 1 / (resized/dim) = dim/resized.
        map_to_crop_x = (w / resized_w) if resized_w > 0 else 0.0
        map_to_crop_y = (h / resized_h) if resized_h > 0 else 0.0

        # 7. DbPost in map space.
        map_lines = DbPost.detect_lines(active, thresh=thresh, box_thresh=box_thresh)

        # 8. Back-project each line to crop coords, keep positive-area.
        crop_lines: list[TextLine] = []
        for ml in map_lines:
            cb = DbPost.back_project(ml.bbox, map_to_crop_x, map_to_crop_y, w, h)
            if cb[2] > cb[0] and cb[3] > cb[1]:
                crop_lines.append(TextLine(bbox=cb, mean_score=ml.mean_score))
        return crop_lines
