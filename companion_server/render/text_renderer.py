"""Text renderer with neighbour-aware layout.

Ports from Android:
  - RenderColorEstimator: 2-means clustering with containment-aware sampling
  - TextLayoutPlanner: neighbour-aware placement, growth, clipping
  - PageTextRenderer: two-pass stroke+fill with correct baseline

Key improvements over the old Python renderer:
  - Uses bundled animeace font (manga-style, like Android)
  - Two-pass rendering: stroke outline first, then fill on top
  - Correct baseline via font.getmetrics() (ascent/descent)
  - Neighbour-aware layout prevents text collisions
  - Parent bubble support for proper text placement
  - Hard legibility floor
"""
from __future__ import annotations

from pathlib import Path
from typing import Any

import numpy as np
from PIL import Image, ImageDraw, ImageFont

from render import layout_planner as lp

# ── Font resolution ───────────────────────────────────────────────────

_FONT_DIR = Path(__file__).resolve().parent.parent / "web" / "static" / "fonts"

_FONT_CANDIDATES = [
    _FONT_DIR / "animeace.ttf",
    _FONT_DIR / "manga_master_bb.ttf",
    _FONT_DIR / "comic_book.otf",
    Path("C:/Windows/Fonts/msyhbd.ttc"),
    Path("C:/Windows/Fonts/arialbd.ttf"),
    Path("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"),
]

_FONT_CANDIDATES_REGULAR = [
    _FONT_DIR / "animeace.ttf",
    Path("C:/Windows/Fonts/msyh.ttc"),
    Path("C:/Windows/Fonts/arial.ttf"),
    Path("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"),
]


def _find_font() -> str | None:
    for path in _FONT_CANDIDATES:
        if path.exists():
            return str(path)
    for path in _FONT_CANDIDATES_REGULAR:
        if path.exists():
            return str(path)
    return None


# ── Color estimation (port of RenderColorEstimator.kt) ────────────────

DARK_BG_LUMA = 85.0


def estimate_colors(
    image: Image.Image,
    x1: int, y1: int, x2: int, y2: int,
    parent_bbox: dict[str, int] | None = None,
) -> tuple[int, int, float]:
    """Estimate (text_color, stroke_color, stroke_width) from background.

    Uses 2-means clustering seeded black vs white. When parent_bbox is given,
    samples only the eroded bubble interior for containment-aware estimation.
    """
    width, height = image.size
    box_w = max(1, x2 - x1)
    box_h = max(1, y2 - y1)
    base_pad = max(12, min(box_w, box_h) // 2)
    pad = max(base_pad, 16) if parent_bbox else base_pad

    left = max(0, x1 - pad)
    top = max(0, y1 - pad)
    right = min(width, x2 + pad)
    bottom = min(height, y2 + pad)
    crop_w = right - left
    crop_h = bottom - top
    if crop_w <= 0 or crop_h <= 0:
        return (0x000000, 0xFFFFFF, 3.0)

    crop = np.asarray(image.crop((left, top, right, bottom)).convert("RGB"), dtype=np.float32)
    pixels = crop.reshape(-1, 3)

    # Build sample mask for containment-aware sampling
    if parent_bbox is not None:
        bw = parent_bbox.get("x2", 0) - parent_bbox.get("x1", 0)
        bh = parent_bbox.get("y2", 0) - parent_bbox.get("y1", 0)
        erode = max(2, min(bw, bh) // 10)
        sx1 = max(0, parent_bbox.get("x1", 0) + erode - left)
        sy1 = max(0, parent_bbox.get("y1", 0) + erode - top)
        sx2 = min(crop_w, parent_bbox.get("x2", 0) - erode - left)
        sy2 = min(crop_h, parent_bbox.get("y2", 0) - erode - top)
        if sx2 > sx1 and sy2 > sy1:
            sample_mask = np.zeros(crop_h * crop_w, dtype=bool)
            for y in range(int(sy1), int(sy2)):
                for x in range(int(sx1), int(sx2)):
                    sample_mask[y * crop_w + x] = True
            sampled = pixels[sample_mask]
        else:
            step = max(1, len(pixels) // 1200)
            sampled = pixels[::step]
    else:
        step = max(1, len(pixels) // 1200)
        sampled = pixels[::step]

    if len(sampled) == 0:
        return (0x000000, 0xFFFFFF, 3.0)

    # 2-means seeded black vs white, 5 iterations
    center0 = np.array([0.0, 0.0, 0.0], dtype=np.float32)
    center1 = np.array([255.0, 255.0, 255.0], dtype=np.float32)
    mask0 = None
    for _ in range(5):
        d0 = np.sum((sampled - center0) ** 2, axis=1)
        d1 = np.sum((sampled - center1) ** 2, axis=1)
        mask0 = d0 < d1
        mask1 = ~mask0
        if np.any(mask0):
            center0 = sampled[mask0].mean(axis=0)
        if np.any(mask1):
            center1 = sampled[mask1].mean(axis=0)

    # Background = larger cluster
    count0 = int(np.sum(mask0)) if mask0 is not None else 0
    count1 = len(sampled) - count0
    if count0 >= count1:
        bg_color = center0
        fg_color = center1
    else:
        bg_color = center1
        fg_color = center0

    bg_luma = 0.299 * bg_color[0] + 0.587 * bg_color[1] + 0.114 * bg_color[2]

    # Contrast selection
    if bg_luma < DARK_BG_LUMA:
        text_color = 0xFFFFFF
        stroke_color = 0x000000
        stroke_width = 4.5
    else:
        text_color = 0x000000
        stroke_color = 0xFFFFFF
        stroke_width = 3.0

    return (text_color, stroke_color, stroke_width)


# ── Vertical text punctuation mapping ─────────────────────────────────

_VERTICAL_PUNCTUATION_MAP = {
    'ー': '︱', '―': '︱', '─': '︱', '-': '︱',
    '「': '﹁', '」': '﹂', '『': '﹃', '』': '﹄',
    '（': '︵', '）': '︶', '(': '︵', ')': '︶',
    '【': '︻', '】': '︼', '〔': '︹', '〕': '︺',
    '［': '﹇', '］': '﹈', '[': '﹇', ']': '﹈',
    '{': '︷', '}': '︸', '｛': '︷', '｝': '︸',
}

# ── Main renderer ─────────────────────────────────────────────────────


class TextRenderer:
    def __init__(self) -> None:
        self._font_path = _find_font()

    def estimate_block_colors(self, cleaned_image: Image.Image, blocks: list[dict[str, Any]]) -> None:
        """Re-derive text/stroke/strokeWidth for each block from the CLEANED image.

        Mirrors RenderColorEstimator.recomputeFor — must be called AFTER inpainting
        and BEFORE render(), so colors match what the user sees behind the text.
        """
        for block in blocks:
            translation = block.get("translation", "")
            ocr_text = block.get("text", "")
            if not translation.strip() and not ocr_text.strip():
                continue

            bbox = block["bbox"]
            parent = None
            if block.get("parentW", 0) > 0 and block.get("parentH", 0) > 0:
                parent = block.get("parentBbox")

            text_color, stroke_color, stroke_width = estimate_colors(
                cleaned_image,
                bbox["x1"], bbox["y1"], bbox["x2"], bbox["y2"],
                parent,
            )
            block["textColor"] = text_color
            block["strokeColor"] = stroke_color
            if block.get("strokeWidth", 0) <= 0:
                block["strokeWidth"] = stroke_width

    def render(self, cleaned_image: Image.Image, blocks: list[dict[str, Any]]) -> Image.Image:
        result = cleaned_image.copy().convert("RGB")
        width, height = result.size
        draw = ImageDraw.Draw(result)

        layouts = lp.plan(blocks, float(width), float(height), self._font_path, draw)

        for layout in layouts:
            self._draw_block(draw, layout)

        return result

    def _draw_block(self, draw: ImageDraw.ImageDraw, layout: lp.BlockLayout) -> None:
        block = layout.block
        text_color = block.get("textColor", 0x000000)
        stroke_color = block.get("strokeColor", 0xFFFFFF)

        # Hard outline invariant
        r = (text_color >> 16) & 0xFF
        g = (text_color >> 8) & 0xFF
        b = text_color & 0xFF
        luma = (r * 299 + g * 587 + b * 114) / 1000
        if luma < 128:
            stroke_color = 0xFFFFFF
        else:
            stroke_color = 0x000000

        stroke_w = layout.stroke_width
        if stroke_w <= 0:
            stroke_w = max(2.0, layout.font_size_px * 0.12)

        font = lp._load_font(self._font_path, int(layout.font_size_px))

        fill_tuple = ((text_color >> 16) & 0xFF, (text_color >> 8) & 0xFF, text_color & 0xFF)
        stroke_tuple = ((stroke_color >> 16) & 0xFF, (stroke_color >> 8) & 0xFF, stroke_color & 0xFF)

        # Clip if needed
        if layout.clip_rect is not None:
            cr = layout.clip_rect
            # PIL doesn't have canvas.save/restore like Android, so we just
            # pass the clip bounds and rely on text being within them
            pass

        if layout.is_vertical:
            self._draw_vertical(draw, layout, font, fill_tuple, stroke_tuple, int(stroke_w))
        else:
            self._draw_horizontal(draw, layout, font, fill_tuple, stroke_tuple, int(stroke_w))

    def _draw_horizontal(
        self,
        draw: ImageDraw.ImageDraw,
        layout: lp.BlockLayout,
        font: ImageFont.FreeTypeFont,
        fill: tuple[int, int, int],
        stroke: tuple[int, int, int],
        stroke_w: int,
    ) -> None:
        lines = lp.cjk_wrap(layout.text, font, layout.safe_w, draw)
        if not lines:
            return

        ascent, descent = font.getmetrics()
        line_h = float(ascent + descent)
        total_h = len(lines) * line_h
        start_y = layout.origin_y - total_h / 2

        for i, line in enumerate(lines):
            if not line:
                continue
            text_w = float(draw.textlength(line, font=font))

            if layout.draw_align == lp.TextAlign.LEFT:
                tx = layout.origin_x
            elif layout.draw_align == lp.TextAlign.RIGHT:
                tx = layout.origin_x - text_w
            else:
                tx = layout.origin_x - text_w / 2

            ty = start_y + i * line_h

            # Two-pass: stroke first, then fill (mirrors Android's strokePaint + fillPaint)
            draw.text((tx, ty), line, fill=stroke, font=font,
                      stroke_width=stroke_w, stroke_fill=stroke)
            draw.text((tx, ty), line, fill=fill, font=font)

    def _draw_vertical(
        self,
        draw: ImageDraw.ImageDraw,
        layout: lp.BlockLayout,
        font: ImageFont.FreeTypeFont,
        fill: tuple[int, int, int],
        stroke: tuple[int, int, int],
        stroke_w: int,
    ) -> None:
        char_step = layout.font_size_px * lp.VERTICAL_CHAR_STEP
        col_step = layout.font_size_px * lp.VERTICAL_COL_STEP
        chars = layout.text.replace('\r', '').replace('\n', '').replace(' ', '')
        if not chars:
            return

        max_chars_per_col = max(1, int(layout.safe_h / char_step))
        columns: list[str] = []
        current: list[str] = []
        for ch in chars:
            mapped = _VERTICAL_PUNCTUATION_MAP.get(ch, ch)
            if len(current) >= max_chars_per_col:
                columns.append(''.join(current))
                current = []
            current.append(mapped)
        if current:
            columns.append(''.join(current))
        if not columns:
            return

        total_w = len(columns) * col_step
        cols_right = layout.origin_x + total_w / 2

        for col_idx, col in enumerate(columns):
            col_cx = cols_right - col_idx * col_step - col_step / 2
            col_h = len(col) * char_step
            col_y_start = layout.origin_y - col_h / 2

            for char_idx, ch in enumerate(col):
                char_y = col_y_start + char_idx * char_step
                # Two-pass: stroke then fill
                draw.text((col_cx, char_y), ch, fill=stroke, font=font,
                          stroke_width=stroke_w, stroke_fill=stroke, anchor='mm')
                draw.text((col_cx, char_y), ch, fill=fill, font=font, anchor='mm')
