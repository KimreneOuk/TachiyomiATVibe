"""Neighbour-aware text layout planner.

Port of TextLayoutPlanner.kt — plans the WHOLE page at once so blocks
don't collide. Key features:
  - Score-sorted placement (highest confidence first)
  - Tall-box reshape (parentless boxes >2:1 ratio widened)
  - Free-space growth (overflow redirected into open space)
  - Clip safety-net (structural no-overlap guarantee)
  - Legibility floor (prevents collapse to ~8px)
"""
from __future__ import annotations

import math
from dataclasses import dataclass
from typing import Any

from PIL import ImageDraw, ImageFont

# ── Constants (mirror TextLayoutPlanner.kt) ───────────────────────────

RESHAPE_TALL_RATIO = 2.0
RESHAPE_MIN_WIDTH_FACTOR = 1.5
RESHAPE_MAX_WIDTH_FACTOR = 3.5
VERTICAL_CHAR_STEP = 1.05
VERTICAL_COL_STEP = 1.25
FIT_MAX_FONT_PX = 72.0
FIT_MIN_FONT_PX = 8.0
FIT_START_WIDTH_FACTOR = 1.5
LEGIBLE_FONT_FRACTION = 0.014
LEGIBLE_FONT_ABS_PX = 14.0
MIN_GAP_PX = 2.0


@dataclass
class FloatRect:
    left: float
    top: float
    right: float
    bottom: float

    def width(self) -> float:
        return self.right - self.left

    def height(self) -> float:
        return self.bottom - self.top

    def overlaps(self, other: FloatRect) -> bool:
        return (self.left < other.right and other.left < self.right and
                self.top < other.bottom and other.top < self.bottom)

    def intersection(self, other: FloatRect) -> FloatRect:
        return FloatRect(
            max(self.left, other.left),
            max(self.top, other.top),
            min(self.right, other.right),
            min(self.bottom, other.bottom),
        )


class TextAlign:
    CENTER = "center"
    LEFT = "left"
    RIGHT = "right"


@dataclass
class BlockLayout:
    block: dict[str, Any]
    text: str
    is_vertical: bool
    origin_x: float
    origin_y: float
    safe_w: float
    safe_h: float
    font_size_px: float
    stroke_width: float
    draw_align: str
    clip_rect: FloatRect | None


# ── CJK detection ─────────────────────────────────────────────────────

_CJK_RANGES = [
    (0x4E00, 0x9FFF), (0x3400, 0x4DBF), (0x20000, 0x2A6DF),
    (0x2A700, 0x2B73F), (0x2B740, 0x2B81F), (0xF900, 0xFAFF),
    (0x2F800, 0x2FA1F), (0x3000, 0x303F), (0x3040, 0x309F),
    (0x30A0, 0x30FF), (0x31F0, 0x31FF), (0xAC00, 0xD7AF),
    (0xFF00, 0xFFEF), (0xFE30, 0xFE4F),
]


def is_cjk(ch: str) -> bool:
    cp = ord(ch)
    return any(lo <= cp <= hi for lo, hi in _CJK_RANGES)


def cjk_ratio(text: str) -> float:
    total = sum(1 for ch in text if not ch.isspace())
    if total == 0:
        return 0.0
    cjk = sum(1 for ch in text if not ch.isspace() and is_cjk(ch))
    return cjk / total


def should_render_vertical(text: str) -> bool:
    return cjk_ratio(text) > 0.5


# ── Text measurement ──────────────────────────────────────────────────

def measure_text_width(text: str, font: ImageFont.FreeTypeFont, draw: ImageDraw.ImageDraw) -> float:
    return float(draw.textlength(text, font=font))


def line_height(font: ImageFont.FreeTypeFont) -> float:
    ascent, descent = font.getmetrics()
    return float(ascent + descent)


# ── CJK-aware word wrap ───────────────────────────────────────────────

def cjk_wrap(text: str, font: ImageFont.FreeTypeFont, max_width: float,
             draw: ImageDraw.ImageDraw) -> list[str]:
    tokens: list[str] = []
    i = 0
    while i < len(text):
        ch = text[i]
        if ch == '\n':
            tokens.append('\n')
            i += 1
        elif ch.isspace():
            tokens.append(' ')
            i += 1
        elif is_cjk(ch):
            tokens.append(ch)
            i += 1
        else:
            start = i
            while (i < len(text) and not is_cjk(text[i]) and
                   not text[i].isspace() and text[i] != '\n'):
                i += 1
            tokens.append(text[start:i])

    lines: list[str] = []
    current = ''
    for token in tokens:
        if token == '\n':
            lines.append(current)
            current = ''
            continue
        candidate = current + token
        width = measure_text_width(candidate, font, draw)
        if width > max_width and current:
            lines.append(current.rstrip())
            current = '' if token == ' ' else token
        else:
            current = candidate
    if current:
        lines.append(current.rstrip())
    return lines if lines else [text]


# ── Binary search font size ───────────────────────────────────────────

def binary_search_font_size(
    text: str,
    safe_w: float,
    safe_h: float,
    container_w: float,
    is_vertical: bool,
    draw: ImageDraw.ImageDraw,
    font_path: str | None,
) -> float:
    start_size = int(max(container_w * FIT_START_WIDTH_FACTOR, FIT_MIN_FONT_PX * 4.5))
    high = min(max(start_size, int(FIT_MIN_FONT_PX * 4.5)), int(FIT_MAX_FONT_PX))
    low = max(2, int(FIT_MIN_FONT_PX))
    best = float(low)

    while low <= high:
        mid = (low + high) // 2
        font = _load_font(font_path, mid)
        if is_vertical:
            char_step = mid * VERTICAL_CHAR_STEP
            col_step = mid * VERTICAL_COL_STEP
            chars = sum(1 for c in text if c not in '\r\n ')
            max_chars = max(1, int(safe_h / char_step))
            num_cols = max(1, math.ceil(chars / max_chars)) if chars > 0 else 1
            total_w = num_cols * col_step
            max_col_h = max_chars * char_step
            if total_w <= safe_w and max_col_h <= safe_h:
                best = float(mid)
                low = mid + 1
            else:
                high = mid - 1
        else:
            wrapped = cjk_wrap(text, font, safe_w, draw)
            lh = line_height(font)
            total_h = len(wrapped) * lh
            max_line_w = max((measure_text_width(line, font, draw) for line in wrapped), default=0)
            if total_h <= safe_h and max_line_w <= safe_w:
                best = float(mid)
                low = mid + 1
            else:
                high = mid - 1
    return best


def _load_font(font_path: str | None, size: int) -> ImageFont.FreeTypeFont:
    size = max(8, int(size))
    if font_path:
        try:
            return ImageFont.truetype(font_path, size)
        except Exception:
            pass
    return ImageFont.load_default()


# ── Compute rects ─────────────────────────────────────────────────────

@dataclass
class RectResult:
    base_x: float
    base_y: float
    base_w: float
    base_h: float
    safe_w: float
    safe_h: float
    reshaped: bool
    orig_left: float
    orig_right: float


def compute_rects(block: dict[str, Any]) -> RectResult:
    bbox = block["bbox"]
    x1, y1 = float(bbox["x1"]), float(bbox["y1"])
    x2, y2 = float(bbox["x2"]), float(bbox["y2"])
    bw = x2 - x1
    bh = y2 - y1

    has_parent = block.get("parentW", 0) > 0 and block.get("parentH", 0) > 0
    if has_parent:
        parent = block.get("parentBbox", {})
        base_x = float(parent.get("x1", x1))
        base_y = float(parent.get("y1", y1))
        base_w = float(block.get("parentW", bw))
        base_h = float(block.get("parentH", bh))
        text_pad = max(12.0, 0.15 * min(base_w, base_h))
    else:
        base_x = x1
        base_y = y1
        base_w = bw
        base_h = bh
        text_pad = max(4.0, 0.03 * min(base_w, base_h))

    orig_left = base_x
    orig_right = base_x + base_w
    reshaped = False

    if not has_parent and base_h > 0 and base_w > 0 and base_h / base_w > RESHAPE_TALL_RATIO:
        area = base_w * base_h
        new_h = math.sqrt(area)
        new_w = new_h
        new_w = max(base_w * RESHAPE_MIN_WIDTH_FACTOR,
                    min(base_w * RESHAPE_MAX_WIDTH_FACTOR, new_w))
        new_h = area / new_w
        base_x = (orig_left + orig_right) / 2 - new_w / 2
        base_y = base_y + (base_h - new_h) / 2
        base_w = new_w
        base_h = new_h
        reshaped = True

    safe_pad = min(text_pad, min(base_w, base_h) / 3)
    safe_w = max(1.0, base_w - safe_pad * 2)
    safe_h = max(1.0, base_h - safe_pad * 2)
    return RectResult(base_x, base_y, base_w, base_h, safe_w, safe_h, reshaped, orig_left, orig_right)


# ── Extent / overflow helpers ─────────────────────────────────────────

def columns_for(text: str, safe_h: float, char_step: float) -> int:
    chars = sum(1 for c in text if c not in '\r\n ')
    if chars == 0:
        return 1
    max_chars = max(1, int(safe_h / char_step))
    return max(1, math.ceil(chars / max_chars))


def stripped_length(text: str) -> int:
    return sum(1 for c in text if c not in '\r\n ')


def overflows(text: str, font_size: float, is_vertical: bool,
              safe_w: float, safe_h: float, font_path: str | None,
              draw: ImageDraw.ImageDraw) -> bool:
    if is_vertical:
        char_step = font_size * VERTICAL_CHAR_STEP
        col_step = font_size * VERTICAL_COL_STEP
        cols = columns_for(text, safe_h, char_step)
        return cols * col_step > safe_w + 0.5
    font = _load_font(font_path, int(font_size))
    wrapped = cjk_wrap(text, font, safe_w, draw)
    lh = line_height(font)
    if len(wrapped) * lh > safe_h + 0.5:
        return True
    return any(measure_text_width(line, font, draw) > safe_w + 0.5 for line in wrapped)


def extent_of(layout: BlockLayout, font_path: str | None,
              draw: ImageDraw.ImageDraw) -> FloatRect:
    cx = layout.origin_x
    cy = layout.origin_y
    if layout.is_vertical:
        char_step = layout.font_size_px * VERTICAL_CHAR_STEP
        col_step = layout.font_size_px * VERTICAL_COL_STEP
        cols = columns_for(layout.text, layout.safe_h, char_step)
        total_w = cols * col_step
        col_h = min(layout.safe_h, stripped_length(layout.text) * char_step)
        return FloatRect(cx - total_w / 2, cy - col_h / 2, cx + total_w / 2, cy + col_h / 2)
    else:
        font = _load_font(font_path, int(layout.font_size_px))
        wrapped = cjk_wrap(layout.text, font, layout.safe_w, draw)
        lh = line_height(font)
        total_h = len(wrapped) * lh
        max_line_w = max((measure_text_width(line, font, draw) for line in wrapped), default=0)
        if layout.draw_align == TextAlign.LEFT:
            return FloatRect(cx, cy - total_h / 2, cx + max_line_w, cy + total_h / 2)
        elif layout.draw_align == TextAlign.RIGHT:
            return FloatRect(cx - max_line_w, cy - total_h / 2, cx, cy + total_h / 2)
        else:
            return FloatRect(cx - max_line_w / 2, cy - total_h / 2,
                             cx + max_line_w / 2, cy + total_h / 2)


# ── Stroke width ──────────────────────────────────────────────────────

def compute_stroke_width(block: dict[str, Any], font_size: float) -> float:
    sw = block.get("strokeWidth", 0)
    if sw and sw > 0:
        bbox = block["bbox"]
        bw = float(bbox["x2"] - bbox["x1"])
        start_estimate = max(bw * FIT_START_WIDTH_FACTOR, FIT_MIN_FONT_PX * 4.5)
        if start_estimate > 0 and font_size < start_estimate:
            scaled = sw * (font_size / start_estimate)
        else:
            scaled = sw
        return max(1.0, scaled)
    return max(1.5, font_size * 0.07)


# ── Main planner ──────────────────────────────────────────────────────

def plan(
    blocks: list[dict[str, Any]],
    page_width: float,
    page_height: float,
    font_path: str | None,
    draw: ImageDraw.ImageDraw,
) -> list[BlockLayout]:
    if not blocks:
        return []

    min_legible = max(LEGIBLE_FONT_ABS_PX, min(page_width, page_height) * LEGIBLE_FONT_FRACTION)

    # Sort by score descending (most confident first), ties keep order
    ordered = sorted(enumerate(blocks), key=lambda pair: (-pair[1].get("score", 0), pair[0]))

    placed: list[BlockLayout] = []
    for _, block in ordered:
        translation = block.get("translation", "")
        if not translation or not translation.strip():
            continue

        rect = compute_rects(block)
        if rect.safe_w < 1 or rect.safe_h < 1:
            continue

        is_vertical = block.get("direction") == "TTB" and should_render_vertical(translation)
        obstacles = [extent_of(it, font_path, draw) for it in placed]

        layout = _place_block(block, translation, is_vertical, rect, obstacles,
                              page_width, page_height, min_legible, font_path, draw)
        placed.append(layout)
    return placed


def _place_block(
    block: dict[str, Any],
    text: str,
    is_vertical: bool,
    rect: RectResult,
    obstacles: list[FloatRect],
    page_width: float,
    page_height: float,
    min_legible: float,
    font_path: str | None,
    draw: ImageDraw.ImageDraw,
) -> BlockLayout:
    base_x = rect.base_x
    base_y = rect.base_y
    base_w = rect.base_w
    base_h = rect.base_h
    safe_pad = max(0.0, (base_w - rect.safe_w) / 2)

    has_parent = block.get("parentW", 0) > 0 and block.get("parentH", 0) > 0
    anchor_to_ocr_center = block.get("label") == 2 or (block.get("direction") == "TTB" and not is_vertical)

    # Region for containment clip
    if has_parent:
        parent = block.get("parentBbox", {})
        region = FloatRect(float(parent.get("x1", 0)), float(parent.get("y1", 0)),
                           float(parent.get("x2", 0)), float(parent.get("y2", 0)))
    elif rect.reshaped:
        region = FloatRect(0, 0, page_width, page_height)
    else:
        bbox = block["bbox"]
        region = FloatRect(float(bbox["x1"]), float(bbox["y1"]),
                           float(bbox["x2"]), float(bbox["y2"]))

    # Re-anchor reshaped box by minimal displacement
    if rect.reshaped:
        orig_center_x = rect.orig_left + (rect.orig_right - rect.orig_left) / 2
        base_x = _resolve_minimal_displacement_x(
            orig_center_x - base_w / 2, base_w, page_width, obstacles, orig_center_x,
        )

    # Fit font
    safe_w = max(1.0, base_w - safe_pad * 2)
    safe_h = max(1.0, base_h - safe_pad * 2)
    font_size = binary_search_font_size(text, safe_w, safe_h, base_w, is_vertical, draw, font_path)

    # Grow if undersized or overflowing
    if font_size < min_legible or overflows(text, font_size, is_vertical, safe_w, safe_h, font_path, draw):
        base_x, base_y, base_w, base_h, safe_w, safe_h, font_size = _grow_into_free_space(
            text, is_vertical, base_x, base_y, base_w, base_h, safe_pad,
            obstacles, page_width, page_height, min_legible, font_path, draw,
        )
        base_w = min(base_w, region.width())
        base_h = min(base_h, region.height())
        base_x = max(region.left, min(base_x, region.right - base_w))
        base_y = max(region.top, min(base_y, region.bottom - base_h))
        safe_w = max(1.0, base_w - safe_pad * 2)
        safe_h = max(1.0, base_h - safe_pad * 2)
        font_size = binary_search_font_size(text, safe_w, safe_h, base_w, is_vertical, draw, font_path)

    stroke_w = compute_stroke_width(block, font_size)
    origin_y = base_y + base_h / 2

    # Determine alignment
    centered_origin_x = base_x + base_w / 2
    draw_align = TextAlign.CENTER
    origin_x = centered_origin_x

    clip_rect = None

    # Containment clip: if text overflows region, clip
    if overflows(text, font_size, is_vertical, region.width(), region.height(), font_path, draw):
        clip = FloatRect(base_x + safe_pad, base_y + safe_pad,
                         base_x + base_w - safe_pad, base_y + base_h - safe_pad)
        clip = FloatRect(
            max(clip.left, region.left), max(clip.top, region.top),
            min(clip.right, region.right), min(clip.bottom, region.bottom),
        )
        if clip.width() > MIN_GAP_PX and clip.height() > MIN_GAP_PX:
            clip_rect = clip
            if is_vertical:
                origin_x = clip.left + clip.width() / 2
            else:
                origin_x = clip.left
                draw_align = TextAlign.LEFT
            origin_y = clip.top + clip.height() / 2
            safe_w = clip.width()
            safe_h = clip.height()
            fit_font = binary_search_font_size(text, safe_w, safe_h, safe_w, is_vertical, draw, font_path)
            font_size = max(fit_font, min_legible)

    # Free text / vertical-source: anchor at OCR center
    if anchor_to_ocr_center:
        bbox = block["bbox"]
        origin_x = float(bbox["x1"]) + float(bbox["x2"] - bbox["x1"]) / 2
        origin_y = float(bbox["y1"]) + float(bbox["y2"] - bbox["y1"]) / 2
        draw_align = TextAlign.CENTER

    return BlockLayout(
        block=block, text=text, is_vertical=is_vertical,
        origin_x=origin_x, origin_y=origin_y,
        safe_w=safe_w, safe_h=safe_h,
        font_size_px=font_size, stroke_width=stroke_w,
        draw_align=draw_align, clip_rect=clip_rect,
    )


def _resolve_minimal_displacement_x(
    preferred_x: float,
    width: float,
    page_width: float,
    obstacles: list[FloatRect],
    orig_center_x: float,
) -> float:
    min_allowed = orig_center_x - width
    max_allowed = orig_center_x
    max_x = max(0, page_width - width)
    x = max(min_allowed, min(max_allowed, min(max(0, preferred_x), max_x)))

    guard = 0
    while guard < len(obstacles) + 1:
        candidate = FloatRect(x, 0, x + width, float('inf'))
        colliding = next((o for o in obstacles if candidate.overlaps(o)), None)
        if colliding is None:
            return x
        center_x = x + width / 2
        obs_center_x = (colliding.left + colliding.right) / 2
        if center_x < obs_center_x:
            next_x = colliding.left - width
        else:
            next_x = colliding.right
        next_x = max(min_allowed, min(max_allowed, min(max(0, next_x), max_x)))
        if next_x == x:
            return x
        x = next_x
        guard += 1
    return x


def _grow_into_free_space(
    text: str,
    is_vertical: bool,
    base_x: float, base_y: float,
    base_w: float, base_h: float,
    safe_pad: float,
    obstacles: list[FloatRect],
    page_width: float, page_height: float,
    min_legible: float,
    font_path: str | None,
    draw: ImageDraw.ImageDraw,
) -> tuple[float, float, float, float, float, float, float]:
    bx, by, bw, bh = base_x, base_y, base_w, base_h
    sw = max(1.0, bw - safe_pad * 2)
    sh = max(1.0, bh - safe_pad * 2)

    target_font = max(min_legible, FIT_MIN_FONT_PX)
    font = _load_font(font_path, int(target_font))
    lh = line_height(font)

    if not is_vertical:
        # Grow height first (more wrap lines → narrower)
        center_y = by + bh / 2
        safe_top = by + safe_pad
        safe_bottom = by + bh - safe_pad
        free_up = _free_space_up(center_y, safe_top, obstacles)
        free_down = _free_space_down(center_y, safe_bottom, obstacles, page_height)

        base_lines = max(1, int(sh / lh))
        max_lines = max(base_lines, int((sh + max(free_up, free_down)) / lh))
        sw_before = sw
        fit_lines = None
        for n in range(base_lines, max_lines + 1):
            min_w = _min_width_for_lines(text, target_font, n, font_path, draw)
            if min_w <= sw_before:
                fit_lines = n
                break
        target_lines = fit_lines if fit_lines else max_lines
        height_needed = max(0.0, target_lines * lh - sh)
        if height_needed > 0:
            if free_down >= free_up:
                bh += min(free_down, height_needed)
            else:
                g = min(free_up, height_needed)
                by -= g
                bh += g
            sh = max(1.0, bh - safe_pad * 2)

        # Grow width for residual overflow
        max_lines_h = max(1, int(sh / lh))
        center_x = bx + bw / 2
        safe_left = bx + safe_pad
        safe_right = bx + bw - safe_pad
        free_left = _free_space_left(center_x, safe_left, obstacles, page_width)
        free_right = _free_space_right(center_x, safe_right, obstacles, page_width)
        needed_w = _min_width_for_lines(text, target_font, max_lines_h, font_path, draw)
        width_needed = max(0.0, needed_w - sw)
        if width_needed > 0:
            if free_right >= free_left:
                bw += min(free_right, width_needed)
            else:
                g = min(free_left, width_needed)
                bx -= g
                bw += g
            sw = max(1.0, bw - safe_pad * 2)
    else:
        # Vertical: grow height to fit more glyphs per column
        center_y = by + bh / 2
        safe_top = by + safe_pad
        safe_bottom = by + bh - safe_pad
        free_up = _free_space_up(center_y, safe_top, obstacles)
        free_down = _free_space_down(center_y, safe_bottom, obstacles, page_height)
        char_step = target_font * VERTICAL_CHAR_STEP
        cols_now = columns_for(text, sh, char_step)
        col_step = target_font * VERTICAL_COL_STEP
        if cols_now * col_step > sw:
            chars = stripped_length(text)
            cols_target = max(1, cols_now - 1)
            chars_per_col = max(1, math.ceil(chars / cols_target))
            growth = max(0.0, chars_per_col * char_step - sh)
            if growth > 0:
                if free_down >= free_up:
                    bh += min(free_down, growth)
                else:
                    g = min(free_up, growth)
                    by -= g
                    bh += g
                sh = max(1.0, bh - safe_pad * 2)

    font_size = binary_search_font_size(text, sw, sh, bw, is_vertical, draw, font_path)
    if font_size < min_legible and not overflows(text, min_legible, is_vertical, sw, sh, font_path, draw):
        font_size = min_legible
    return bx, by, bw, bh, sw, sh, font_size


def _free_space_left(center_x: float, orig_left: float, obstacles: list[FloatRect], page_w: float) -> float:
    bound = 0.0
    for obs in obstacles:
        if obs.right <= center_x and obs.right > bound:
            bound = obs.right
    return max(0.0, orig_left - bound)


def _free_space_right(center_x: float, orig_right: float, obstacles: list[FloatRect], page_w: float) -> float:
    bound = page_w
    for obs in obstacles:
        if obs.left >= center_x and obs.left < bound:
            bound = obs.left
    return max(0.0, bound - orig_right)


def _free_space_up(center_y: float, orig_top: float, obstacles: list[FloatRect]) -> float:
    bound = 0.0
    for obs in obstacles:
        if obs.bottom <= center_y and obs.bottom > bound:
            bound = obs.bottom
    return max(0.0, orig_top - bound)


def _free_space_down(center_y: float, orig_bottom: float, obstacles: list[FloatRect], page_h: float) -> float:
    bound = page_h
    for obs in obstacles:
        if obs.top >= center_y and obs.top < bound:
            bound = obs.top
    return max(0.0, bound - orig_bottom)


def _min_width_for_lines(
    text: str,
    font_size: float,
    max_lines: int,
    font_path: str | None,
    draw: ImageDraw.ImageDraw,
) -> float:
    font = _load_font(font_path, int(font_size))
    full_w = max(1.0, measure_text_width(text, font, draw))
    if max_lines <= 1:
        return full_w
    # Token widths
    tokens = []
    i = 0
    while i < len(text):
        ch = text[i]
        if ch == '\n' or ch.isspace():
            i += 1
        elif is_cjk(ch):
            tokens.append(ch)
            i += 1
        else:
            start = i
            while (i < len(text) and not is_cjk(text[i]) and
                   not text[i].isspace() and text[i] != '\n'):
                i += 1
            tokens.append(text[start:i])
    min_word_w = max((measure_text_width(t, font, draw) for t in tokens), default=full_w) if tokens else full_w
    min_word_w = max(1.0, min_word_w)
    if len(cjk_wrap(text, font, min_word_w, draw)) <= max_lines:
        return min_word_w
    # Binary search
    lo, hi = min_word_w, full_w
    ans = full_w
    for _ in range(24):
        if lo > hi or hi - lo < 1:
            break
        mid = (lo + hi) / 2
        if len(cjk_wrap(text, font, mid, draw)) <= max_lines:
            ans = mid
            hi = mid
        else:
            lo = mid
    return ans
