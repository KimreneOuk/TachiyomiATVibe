"""Block ordering and deduplication — ports of Kotlin equivalents.

  - ``sort_blocks_reading_order`` mirrors ``TranslationBlockSorter.kt``:
    group blocks into rough rows by vertical overlap, sort rows top→bottom,
    sort within each row right-to-left for Japanese (manga), left-to-right
    otherwise. Applied before mask computation (so mask numbering follows
    reading order) and again before translation (so the LLM receives a
    natural order).

  - ``dedupe_post_ocr`` mirrors ``RoiPageRecognitionEngine.removePostOcrDuplicateBlocks``:
    collapse identical-text overlaps and near-duplicate boxes that share a
    parent bubble. Runs after OCR, before sort.
"""
from __future__ import annotations

from typing import Any


def _bbox(block: dict[str, Any]) -> tuple[float, float, float, float]:
    b = block["bbox"]
    return float(b["x1"]), float(b["y1"]), float(b["x2"]), float(b["y2"])


def _center_y(block: dict[str, Any]) -> float:
    _, y1, _, y2 = _bbox(block)
    return (y1 + y2) / 2.0


def _height(block: dict[str, Any]) -> float:
    _, y1, _, y2 = _bbox(block)
    return max(1.0, y2 - y1)


def _cx(block: dict[str, Any]) -> float:
    x1, _, x2, _ = _bbox(block)
    return (x1 + x2) / 2.0


def sort_blocks_reading_order(blocks: list[dict[str, Any]], from_lang: str) -> list[dict[str, Any]]:
    """Sort blocks into comic reading order.

    Groups by vertical overlap into rows; rows top→bottom; within a row,
    Japanese → right-to-left, else left-to-right.
    """
    if len(blocks) <= 1:
        return list(blocks)

    rtl = from_lang.upper() == "JAPANESE"

    # First pass: a stable top-to-bottom by vertical center.
    by_y = sorted(blocks, key=_center_y)

    rows: list[list[dict[str, Any]]] = []
    for block in by_y:
        cy = _center_y(block)
        h = _height(block)
        placed = False
        # A block belongs to a recent row if its center is within half its
        # height of that row's mean center (vertical overlap heuristic).
        recent = rows[-3:] if rows else []
        for row in reversed(recent):
            row_cy = sum(_center_y(b) for b in row) / len(row)
            if abs(cy - row_cy) <= h / 2:
                row.append(block)
                placed = True
                break
        if not placed:
            rows.append([block])

    # Sort each row horizontally by the reading direction.
    out: list[dict[str, Any]] = []
    for row in rows:
        row_sorted = sorted(row, key=_cx, reverse=rtl)
        out.extend(row_sorted)
    return out


def _box_iou(a: tuple[float, float, float, float], b: tuple[float, float, float, float]) -> float:
    ix1 = max(a[0], b[0])
    iy1 = max(a[1], b[1])
    ix2 = min(a[2], b[2])
    iy2 = min(a[3], b[3])
    inter = max(0.0, ix2 - ix1) * max(0.0, iy2 - iy1)
    area_a = max(0.0, a[2] - a[0]) * max(0.0, a[3] - a[1])
    area_b = max(0.0, b[2] - b[0]) * max(0.0, b[3] - b[1])
    union = area_a + area_b - inter
    return 0.0 if union <= 0 else inter / union


def dedupe_post_ocr(blocks: list[dict[str, Any]], iou_threshold: float = 0.6) -> list[dict[str, Any]]:
    """Collapse post-OCR duplicates.

    Two blocks are duplicates when they share the same (normalized) OCR text
    AND their boxes overlap above ``iou_threshold``. When they share a parent
    bubble the threshold is relaxed (0.3) because the OCR engine may emit
    near-identical fragments of one bubble.
    """
    if len(blocks) <= 1:
        return list(blocks)

    kept: list[dict[str, Any]] = []
    for block in blocks:
        text = _normalize(block.get("text", ""))
        box = _bbox(block)
        dup = False
        for k in kept:
            k_text = _normalize(k.get("text", ""))
            if not text or text != k_text:
                continue
            same_parent = _same_parent(block, k)
            thresh = 0.3 if same_parent else iou_threshold
            if _box_iou(box, _bbox(k)) >= thresh:
                # Prefer the higher-confidence duplicate.
                if block.get("score", 0) > k.get("score", 0):
                    kept[kept.index(k)] = block
                dup = True
                break
        if not dup:
            kept.append(block)
    return kept


def _normalize(text: str) -> str:
    return "".join(text.split()).strip()


def _same_parent(a: dict[str, Any], b: dict[str, Any]) -> bool:
    pa = a.get("parentBbox", {})
    pb = b.get("parentBbox", {})
    if a.get("parentW", 0) <= 0 or b.get("parentW", 0) <= 0:
        return False
    return (pa.get("x1"), pa.get("y1"), pa.get("x2"), pa.get("y2")) == \
           (pb.get("x1"), pb.get("y1"), pb.get("x2"), pb.get("y2"))
