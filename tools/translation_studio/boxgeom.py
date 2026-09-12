"""Exact port of the app's BoxGeometry (recognition/BoxGeometry.kt) plus the
detection/OCR-stage dedup policies that consume it (OnnxPageTextDetector.kt,
OcrBlockDeduplication.kt). Boxes are [x1, y1, x2, y2] ints, top-left/bottom-right.
"""
from __future__ import annotations

from dataclasses import dataclass


@dataclass
class Box:
    x1: int
    y1: int
    x2: int
    y2: int

    def as_list(self) -> list[int]:
        return [self.x1, self.y1, self.x2, self.y2]

    @property
    def w(self) -> int:
        return max(1, self.x2 - self.x1)

    @property
    def h(self) -> int:
        return max(1, self.y2 - self.y1)

    @property
    def cx(self) -> float:
        return (self.x1 + self.x2) / 2.0

    @property
    def cy(self) -> float:
        return (self.y1 + self.y2) / 2.0

    @property
    def area(self) -> int:
        return max(0, self.x2 - self.x1) * max(0, self.y2 - self.y1)


def intersection_area(a: Box, b: Box) -> int:
    ix1, iy1 = max(a.x1, b.x1), max(a.y1, b.y1)
    ix2, iy2 = min(a.x2, b.x2), min(a.y2, b.y2)
    if ix2 <= ix1 or iy2 <= iy1:
        return 0
    return (ix2 - ix1) * (iy2 - iy1)


def iou(a: Box, b: Box) -> float:
    inter = intersection_area(a, b)
    if inter <= 0:
        return 0.0
    union = a.area + b.area - inter
    return inter / union if union > 0 else 0.0


@dataclass
class DedupThresholds:
    iou: float
    containment: float
    center: float
    size: float


# OnnxPageTextDetector.DEDUP_THRESHOLDS (detection stage)
DET_THRESHOLDS = DedupThresholds(iou=0.75, containment=0.88, center=0.12, size=0.18)
# BoxGeometry.TEXT_DEDUP_THRESHOLDS (OCR-stage text dedup)
TEXT_THRESHOLDS = DedupThresholds(iou=0.62, containment=0.86, center=0.12, size=0.20)


def is_geometric_duplicate(a: Box, b: Box, t: DedupThresholds) -> bool:
    if iou(a, b) > t.iou:
        return True
    min_area = min(a.area, b.area)
    if min_area > 0 and intersection_area(a, b) / min_area > t.containment:
        return True
    cdx = abs((a.x1 + a.x2) - (b.x1 + b.x2)) / 2.0
    cdy = abs((a.y1 + a.y2) - (b.y1 + b.y2)) / 2.0
    return (cdx <= t.center * min(a.w, b.w) and cdy <= t.center * min(a.h, b.h)
            and abs(a.w - b.w) <= t.size * max(a.w, b.w)
            and abs(a.h - b.h) <= t.size * max(a.h, b.h))


def greedy_dedup(boxes: list[Box], scores: list[float], t: DedupThresholds) -> list[int]:
    """Greedy score-descending dedup; returns indices of kept boxes
    (mirrors OnnxPageTextDetector.deduplicateLabels)."""
    order = sorted(range(len(boxes)), key=lambda i: -scores[i])
    kept: list[int] = []
    for i in order:
        if not any(is_geometric_duplicate(boxes[i], boxes[k], t) for k in kept):
            kept.append(i)
    return sorted(kept)


def select_parent(text: Box, bubbles: list[Box]) -> Box | None:
    """Smallest bubble containing the text center
    (OcrBlockDeduplication parent map)."""
    containing = [b for b in bubbles
                  if b.x1 <= text.cx <= b.x2 and b.y1 <= text.cy <= b.y2]
    return min(containing, key=lambda b: b.area) if containing else None


def suppress_cross_label(texts: list[Box], labels: list[int], scores: list[float],
                         bubbles: list[Box]) -> list[int]:
    """Port of OcrBlockDeduplication.suppressCrossLabelDuplicates: two text
    detections sharing the same parent bubble, with different labels and
    IoU > 0.3 — drop the lower-scored one. Returns kept indices."""
    if len(texts) < 2:
        return list(range(len(texts)))
    parents = [select_parent(t, bubbles) for t in texts]
    removed: set[int] = set()
    for i in range(len(texts)):
        if i in removed or parents[i] is None:
            continue
        for j in range(i + 1, len(texts)):
            if j in removed or parents[j] is not parents[i]:
                continue
            if labels[i] == labels[j]:
                continue
            if iou(texts[i], texts[j]) > 0.3:
                removed.add(i if scores[i] < scores[j] else j)
    return [i for i in range(len(texts)) if i not in removed]


def dedupe_within_parents(texts: list[Box], labels: list[int], scores: list[float],
                          bubbles: list[Box]) -> list[int]:
    """Text-stage dedup: within same-parent groups drop geometric duplicates
    (TEXT_THRESHOLDS), keeping higher parent-containment then higher score."""
    parents = [select_parent(t, bubbles) for t in texts]

    def containment(i: int) -> float:
        p = parents[i]
        if p is None or p.area == 0:
            return 0.0
        return intersection_area(texts[i], p) / p.area

    kept: list[int] = []
    for i in sorted(range(len(texts)),
                    key=lambda i: (-containment(i), -scores[i])):
        group = [k for k in kept if parents[k] is parents[i] and labels[k] == labels[i]]
        if not any(is_geometric_duplicate(texts[i], texts[k], TEXT_THRESHOLDS)
                   for k in group):
            kept.append(i)
    return sorted(kept)


def reading_order_rtl(texts: list[Box], page_height: int) -> list[int]:
    """Manga RTL order: top-to-bottom bands, right-to-left within a band."""
    if not texts:
        return []
    n_bands = 6
    band_h = max(1, page_height // n_bands)
    def key(i: int) -> tuple:
        b = texts[i]
        band = min(int(b.cy // band_h), n_bands - 1)
        return (band, -b.cx)
    return sorted(range(len(texts)), key=key)
