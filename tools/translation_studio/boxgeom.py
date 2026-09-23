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


def threshold_record(t: DedupThresholds) -> dict[str, float]:
    return {"iou": t.iou, "containment": t.containment,
            "center": t.center, "size": t.size}


def greedy_dedup_with_suppressions(
        boxes: list[Box], scores: list[float], ids: list[str],
        t: DedupThresholds, groups: list[object] | None = None,
        rule: str = "det-dedup",
) -> tuple[list[int], list[dict]]:
    """Greedy geometric dedup with winner IDs for the filter trace.

    ``groups`` scopes comparisons. Android's detector stage supplies one group
    for each (window, label), while its OCR stage uses parent and label groups.
    Input order breaks score ties, matching Kotlin's stable descending sort.
    """
    if len(boxes) != len(scores) or len(boxes) != len(ids):
        raise ValueError("boxes, scores, and ids must have equal lengths")
    if groups is not None and len(groups) != len(boxes):
        raise ValueError("groups must have the same length as boxes")
    order = sorted(range(len(boxes)), key=lambda i: -scores[i])
    kept: list[int] = []
    suppressed: list[dict] = []
    for i in order:
        winner = next((k for k in kept
                       if (groups is None or groups[i] == groups[k])
                       and is_geometric_duplicate(boxes[i], boxes[k], t)), None)
        if winner is None:
            kept.append(i)
        else:
            suppressed.append({"loser_id": ids[i], "winner_id": ids[winner],
                               "rule": rule, "threshold": threshold_record(t)})
    return sorted(kept), suppressed


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


def suppress_cross_label_with_suppressions(
        texts: list[Box], labels: list[int], scores: list[float],
        ids: list[str], bubbles: list[Box],
) -> tuple[list[int], list[dict]]:
    """Same-parent, cross-label IoU suppression with decision provenance."""
    if not (len(texts) == len(labels) == len(scores) == len(ids)):
        raise ValueError("texts, labels, scores, and ids must have equal lengths")
    if len(texts) < 2:
        return list(range(len(texts))), []
    parents = [select_parent(t, bubbles) for t in texts]
    removed: set[int] = set()
    suppressed: list[dict] = []
    for i in range(len(texts)):
        if i in removed or parents[i] is None:
            continue
        for j in range(i + 1, len(texts)):
            if j in removed or parents[j] is not parents[i] or labels[i] == labels[j]:
                continue
            if iou(texts[i], texts[j]) > 0.3:
                loser, winner = (i, j) if scores[i] < scores[j] else (j, i)
                removed.add(loser)
                suppressed.append({"loser_id": ids[loser], "winner_id": ids[winner],
                                   "rule": "xlabel", "threshold": 0.3})
    return [i for i in range(len(texts)) if i not in removed], suppressed


def dedupe_within_parents(texts: list[Box], labels: list[int], scores: list[float],
                          bubbles: list[Box]) -> list[int]:
    """OCR-stage dedup drops same-label geometric duplicates across the page.

    Candidate priority is parent-bubble containment, then confidence; parent
    identity does not restrict the duplicate comparison in Android.
    """
    parents = [select_parent(t, bubbles) for t in texts]

    def containment(i: int) -> float:
        p = parents[i]
        if p is None or texts[i].area == 0:
            return 0.0
        return intersection_area(texts[i], p) / texts[i].area

    kept: list[int] = []
    for i in sorted(range(len(texts)),
                    key=lambda i: (-containment(i), -scores[i])):
        group = [k for k in kept if labels[k] == labels[i]]
        if not any(is_geometric_duplicate(texts[i], texts[k], TEXT_THRESHOLDS)
                   for k in group):
            kept.append(i)
    return sorted(kept)


def dedupe_within_parents_with_suppressions(
        texts: list[Box], labels: list[int], scores: list[float],
        ids: list[str], bubbles: list[Box],
) -> tuple[list[int], list[dict]]:
    """OCR-stage same-label dedup plus auditable loser/winner records."""
    if not (len(texts) == len(labels) == len(scores) == len(ids)):
        raise ValueError("texts, labels, scores, and ids must have equal lengths")
    parents = [select_parent(t, bubbles) for t in texts]

    def containment(i: int) -> float:
        parent = parents[i]
        if parent is None or texts[i].area == 0:
            return 0.0
        return intersection_area(texts[i], parent) / texts[i].area

    kept: list[int] = []
    suppressed: list[dict] = []
    for i in sorted(range(len(texts)), key=lambda i: (-containment(i), -scores[i])):
        winner = next((k for k in kept
                       if labels[k] == labels[i]
                       and is_geometric_duplicate(texts[i], texts[k], TEXT_THRESHOLDS)), None)
        if winner is None:
            kept.append(i)
        else:
            suppressed.append({"loser_id": ids[i], "winner_id": ids[winner],
                               "rule": "ocr-dedup",
                               "threshold": threshold_record(TEXT_THRESHOLDS)})
    return sorted(kept), suppressed


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


def overlaps_any_bubble(text: Box, bubbles: list[Box], min_overlap_fraction: float = 0.12) -> bool:
    """Port of AotBoxGeometry.overlapsAnyBubble: checks if text box overlaps
    any bubble by at least min_overlap_fraction of text's area."""
    if not bubbles or text.area <= 0:
        return False
    for b in bubbles:
        ia = intersection_area(text, b)
        if ia / text.area >= min_overlap_fraction:
            return True
    return False


find_parent_bubble = select_parent
