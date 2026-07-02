"""Tests for Paddle det free-text refinement (port of refineFreeTextBoxes).

These pin the Android-faithful behavior: a coarse RT-DETR free-text box is
refined into tight text-line boxes via the Paddle DB det model, so inpainting
erases only the text and not the surrounding art.

Uses real rendered Japanese text because the DB model is trained on text
strokes — solid rectangles produce degenerate fragments that don't reflect
real behavior.
"""
from __future__ import annotations

import os
import sys
from pathlib import Path

import pytest
from PIL import Image, ImageDraw, ImageFont

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from inference.inpaint_pipeline import Cleaner
from inference.ocr_paddle_det import PaddleOcrV6DetEngine

_CJK_FONT_CANDIDATES = [
    "C:/Windows/Fonts/msyh.ttc",
    "C:/Windows/Fonts/YuGothM.ttc",
    "C:/Windows/Fonts/meiryo.ttc",
    "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
]


def _cjk_font():
    for p in _CJK_FONT_CANDIDATES:
        if os.path.exists(p):
            return ImageFont.truetype(p, 32)
    return None


def _paddle_det():
    from model_config import load_config, resolve_model_path
    cfg = load_config()
    path = cfg.get("paddle_det", {}).get("path") or cfg.get("ocr", {}).get("det_path")
    if not path or not Path(resolve_model_path(path)).exists():
        pytest.skip("paddle det model not available")
    try:
        return PaddleOcrV6DetEngine(resolve_model_path(path))
    except Exception:
        pytest.skip("onnxruntime/paddle det unavailable")


def _page_with_free_text():
    """A page with free SFX text on a non-flat background."""
    font = _cjk_font()
    if font is None:
        pytest.skip("no CJK font available for rendering test text")
    img = Image.new("RGB", (800, 600), (210, 205, 200))
    import numpy as np
    arr = np.array(img)
    arr[:] = np.clip(
        arr.astype(np.int16) + np.random.default_rng(1).integers(-8, 8, size=arr.shape),
        0, 255,
    ).astype(np.uint8)
    img = Image.fromarray(arr)
    ImageDraw.Draw(img).text((300, 260), "ドカン", fill=(20, 20, 20), font=font)
    # Coarse detector box loose around the text.
    return img, [290, 250, 460, 310]


def test_refine_free_text_boxes_tighter_than_coarse():
    """The refined line boxes must be smaller than the coarse detector box,
    so surrounding art is preserved (the core fidelity fix)."""
    paddle = _paddle_det()
    img, coarse = _page_with_free_text()
    cleaner = Cleaner(neural=None, paddle_det=paddle)

    refined = cleaner._refine_free_text_boxes(
        img, [coarse], chapter_id=None, page=None, failures=[],
    )
    assert len(refined) >= 1
    coarse_area = (coarse[2] - coarse[0]) * (coarse[3] - coarse[1])
    for r in refined:
        area = (r[2] - r[0]) * (r[3] - r[1])
        assert area < coarse_area, f"refined box {r} not tighter than coarse (area {area} >= {coarse_area})"


def test_refine_falls_back_to_coarse_when_paddle_unavailable():
    """Without paddle_det, the coarse detector box is returned unchanged
    (documented degraded mode — no failure logged for that)."""
    img, coarse = _page_with_free_text()
    cleaner = Cleaner(neural=None, paddle_det=None)
    refined = cleaner._refine_free_text_boxes(
        img, [coarse], chapter_id=None, page=None, failures=[],
    )
    assert refined == [coarse]


def test_detect_lines_returns_clean_text_lines_not_fragments():
    """The DB postprocess must consolidate per-glyph fragments into full lines
    on real text (regression guard for the raw-contour fragment flood)."""
    paddle = _paddle_det()
    font = _cjk_font()
    if font is None:
        pytest.skip("no CJK font")
    img = Image.new("RGB", (600, 200), (240, 240, 240))
    ImageDraw.Draw(img).text((30, 30), "こんにちは世界", fill=(20, 20, 20), font=font)
    ImageDraw.Draw(img).text((30, 100), "漫画翻訳テスト", fill=(20, 20, 20), font=font)

    lines = paddle.detect_lines(img, thresh=0.18, box_thresh=0.34)
    # Two clean text lines, not dozens of per-glyph fragments.
    assert 1 <= len(lines) <= 4
    for line in lines:
        assert (line["x2"] - line["x1"]) > 15
        assert (line["y2"] - line["y1"]) > 8
