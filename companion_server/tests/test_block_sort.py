"""Tests for reading-order sort + post-OCR dedupe (block_sort)."""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from inference.block_sort import dedupe_post_ocr, sort_blocks_reading_order


def _block(x1, y1, x2, y2, text="t", label=1, score=0.9, parent=None, pw=0, ph=0):
    b = {
        "text": text, "bbox": {"x1": x1, "y1": y1, "x2": x2, "y2": y2},
        "label": label, "score": score, "direction": "LTR",
        "parentBbox": parent or {"x1": 0, "y1": 0, "x2": 0, "y2": 0},
        "parentW": pw, "parentH": ph,
    }
    return b


def test_japanese_reading_order_is_rtl_within_row():
    # Two boxes on the same row (overlapping y). Japanese ⇒ right box first.
    left = _block(10, 100, 60, 160, text="left")
    right = _block(300, 100, 360, 160, text="right")
    out = sort_blocks_reading_order([left, right], "JAPANESE")
    assert [b["text"] for b in out] == ["right", "left"]


def test_non_japanese_reading_order_is_ltr():
    left = _block(10, 100, 60, 160, text="left")
    right = _block(300, 100, 360, 160, text="right")
    out = sort_blocks_reading_order([left, right], "ENGLISH")
    assert [b["text"] for b in out] == ["left", "right"]


def test_rows_are_top_to_bottom():
    top = _block(10, 400, 60, 460, text="top")
    bottom = _block(10, 10, 60, 60, text="bottom")
    out = sort_blocks_reading_order([top, bottom], "ENGLISH")
    assert [b["text"] for b in out] == ["bottom", "top"]


def test_dedupe_collapses_identical_overlapping_text():
    a = _block(10, 10, 100, 40, text="こんにちは", score=0.9)
    b = _block(12, 12, 102, 42, text="こんにちは", score=0.7)
    out = dedupe_post_ocr([a, b])
    assert len(out) == 1
    # Keeps the higher-confidence duplicate.
    assert out[0]["score"] == 0.9


def test_dedupe_keeps_distinct_text():
    a = _block(10, 10, 100, 40, text="one")
    b = _block(12, 12, 102, 42, text="two")
    out = dedupe_post_ocr([a, b])
    assert len(out) == 2
