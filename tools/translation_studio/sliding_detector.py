"""Tall-page text and bubble inference windows matching Android's runner."""
from __future__ import annotations

import math
from dataclasses import dataclass

from PIL import Image

TALL_ASPECT_RATIO_THRESHOLD = 2.0
DEFAULT_OVERLAP_PX = 250
MANGA_ASPECT_RATIO = 1.4


@dataclass(frozen=True)
class WindowSlice:
    index: int
    top: int
    bottom: int

    @property
    def height(self) -> int:
        return self.bottom - self.top


def is_tall_image(width: int, height: int) -> bool:
    return width > 0 and height > 0 and height / width >= TALL_ASPECT_RATIO_THRESHOLD


def calculate_windows(width: int, height: int) -> list[WindowSlice]:
    """Port WebtoonSlidingDetector.calculateWindows, including integer rules."""
    if width <= 0 or height <= 0:
        return []
    if not is_tall_image(width, height):
        return [WindowSlice(0, 0, height)]
    target_height = math.floor(width * MANGA_ASPECT_RATIO + 0.5)
    overlap_px = min(DEFAULT_OVERLAP_PX, target_height // 4)
    window_height = max(100, min(height, target_height))
    overlap = max(0, min(overlap_px, window_height // 2))
    step = max(1, window_height - overlap)
    count = max(1, math.ceil((height - overlap) / step))
    windows = []
    for index in range(count):
        top = index * step
        if top >= height:
            break
        bottom = min(height, top + window_height)
        windows.append(WindowSlice(index, top, bottom))
        if bottom >= height:
            break
    return windows


def window_images(image: Image.Image, windows: list[WindowSlice]):
    """Yield (slice, image) and close every temporary crop after its consumer."""
    for window in windows:
        full = window.top == 0 and window.height == image.height
        crop = image if full else image.crop((0, window.top, image.width, window.bottom))
        try:
            yield window, crop
        finally:
            if crop is not image:
                crop.close()


def run_text_detector(image: Image.Image, detect_fn):
    """Run the detector per Android window and return page-space raw outputs."""
    windows = calculate_windows(*image.size)
    tall = is_tall_image(*image.size)
    outputs = []
    window_meta = []
    for window, crop in window_images(image, windows):
        full = window.top == 0 and window.height == image.height
        window_index = None if full else window.index
        window_meta.append({"index": window_index, "top": window.top,
                            "bottom": window.bottom, "height": window.height})
        for detected in detect_fn(crop):
            box = [int(value) for value in detected["box"][:4]]
            box[1] += window.top
            box[3] += window.top
            outputs.append({"label": int(detected["label"]),
                            "score": float(detected["score"]),
                            "raw_score": float(detected.get("raw_score", detected["score"])),
                            "box": box, "window": window_index})
    return outputs, window_meta, tall


def merge_window_detections(candidates: list[dict]) -> tuple[list[dict], list[dict]]:
    """Android's same-label IoU/containment/seam union merge."""
    if len(candidates) <= 1:
        return [dict(item, merged_with=[]) for item in candidates], []
    ordered = sorted(candidates, key=lambda item: -item["score"])
    consumed: set[int] = set()
    merged, records = [], []
    thresholds = {"iou": 0.40, "containment": 0.70,
                  "horizontal_overlap": 0.60, "vertical_gap_px": 20}

    for i, candidate in enumerate(ordered):
        if i in consumed:
            continue
        current = dict(candidate, merged_with=[])
        consumed.add(i)
        for j in range(i + 1, len(ordered)):
            if j in consumed:
                continue
            other = ordered[j]
            if current["label"] != other["label"]:
                continue
            a, b = current["box"], other["box"]
            intersection = max(0, min(a[2], b[2]) - max(a[0], b[0])) * max(
                0, min(a[3], b[3]) - max(a[1], b[1]))
            area_a = max(0, a[2] - a[0]) * max(0, a[3] - a[1])
            area_b = max(0, b[2] - b[0]) * max(0, b[3] - b[1])
            union = area_a + area_b - intersection
            iou = intersection / union if union > 0 else 0.0
            min_area = min(area_a, area_b)
            containment = intersection / min_area if min_area > 0 else 0.0
            x_overlap = max(0, min(a[2], b[2]) - max(a[0], b[0]))
            min_width = min(a[2] - a[0], b[2] - b[0])
            x_overlap_ratio = x_overlap / min_width if min_width > 0 else 0.0
            y_overlap = max(0, min(a[3], b[3]) - max(a[1], b[1]))
            vertical_gap = max(0, max(a[1], b[1]) - min(a[3], b[3]))
            seam = x_overlap_ratio >= 0.60 and (y_overlap > 0 or vertical_gap <= 20)
            if iou >= 0.40:
                criterion = "iou>=0.40"
            elif containment >= 0.70:
                criterion = "containment>=0.70"
            elif seam:
                criterion = "horizontal-overlap>=0.60 and vertical-overlap-or-gap<=20px"
            else:
                continue

            consumed.add(j)
            root_id, other_id = current["id"], other["id"]
            current["box"] = [min(a[0], b[0]), min(a[1], b[1]),
                              max(a[2], b[2]), max(a[3], b[3])]
            current["score"] = max(current["score"], other["score"])
            current["raw_score"] = max(current.get("raw_score", current["score"]),
                                       other.get("raw_score", other["score"]))
            current["merged_with"].append(other_id)
            records.append({"loser_id": other_id, "winner_id": root_id,
                            "merged_with": root_id, "rule": "win-merge",
                            "criterion": criterion, "threshold": thresholds})
        merged.append(current)
    return merged, records
