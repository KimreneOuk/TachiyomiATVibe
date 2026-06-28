"""Faithful Python port of the Android render stack.

Mirrors the Kotlin text-layout + draw pipeline so the sandbox ``/api/render``
reproduces the on-device render (positions, sizes, wrapping, direction):

  - TextLayoutPlanner.kt   -> plan / place_block / compute_rects / cjk_wrap / ...
  - PageTextRenderer.kt    -> draw_horizontal / draw_vertical / FontMeasurer
  - RoiPageRecognitionEngine.kt:1120-1138 -> select_parent_bubble

Every ported function cites its Kotlin file:line in its docstring, the same way
``cci_inpaint.py`` does. The public entry point is ``render_page``.

Pure-metric parity is isolated behind ``TextMeasurer``: tests inject a
deterministic ``FakeMeasurer`` (identical to the Kotlin ``TextLayoutPlannerTest``
fake) so PIL-vs-Paint metric noise never reaches the parity assertions.
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable, Optional, Protocol

import numpy as np
from PIL import Image, ImageDraw, ImageFont

# ─────────────────────────────────────────────────────────────────────────────
# Constants — mirror TextLayoutPlanner.kt:151-178 exactly.
# ─────────────────────────────────────────────────────────────────────────────

RESHAPE_TALL_RATIO = 2.0          # TextLayoutPlanner.kt:153
RESHAPE_MIN_WIDTH_FACTOR = 1.5    # TextLayoutPlanner.kt:154
RESHAPE_MAX_WIDTH_FACTOR = 3.5    # TextLayoutPlanner.kt:155
VERTICAL_CHAR_STEP = 1.05         # TextLayoutPlanner.kt:158
VERTICAL_COL_STEP = 1.25          # TextLayoutPlanner.kt:159
FIT_MAX_FONT_PX = 72.0            # TextLayoutPlanner.kt:162
FIT_MIN_FONT_PX = 8.0             # TextLayoutPlanner.kt:163
FIT_START_WIDTH_FACTOR = 1.5      # TextLayoutPlanner.kt:164
LEGIBLE_FONT_FRACTION = 0.014     # TextLayoutPlanner.kt:174
LEGIBLE_FONT_ABS_PX = 14.0        # TextLayoutPlanner.kt:175
MIN_GAP_PX = 2.0                  # TextLayoutPlanner.kt:178

# RoiPageRecognitionEngine.kt:1155 — parent selection overlap floor.
MIN_PARENT_TEXT_OVERLAP_FRACTION = 0.20

# PageTextRenderer.kt:64-71 — vertical punctuation glyph remap.
VERTICAL_PUNCTUATION_MAP = {
    'ー': '︱', '―': '︱', '─': '︱', '-': '︱',
    '「': '﹁', '」': '﹂', '『': '﹃', '』': '﹄',
    '（': '︵', '）': '︶', '(': '︵', ')': '︶',
    '【': '︻', '】': '︼', '〔': '︹', '〕': '︺',
    '［': '﹇', '］': '﹈', '[': '﹇', ']': '﹈',
    '{': '︷', '}': '︸', '｛': '︷', '｝': '︸',
}

_ROOT = Path(__file__).resolve().parents[2]
_FONT_DIR = _ROOT / "app" / "src" / "main" / "res" / "font"
# PageTextRenderer.kt:16-18 loads animeace (bold); fall back along this chain.
_FONT_CHAIN = ["animeace.ttf", "manga_master_bb.ttf", "comic_book.otf"]


# ─────────────────────────────────────────────────────────────────────────────
# CJK detection — TextLayoutPlanner.kt:857-873
# ─────────────────────────────────────────────────────────────────────────────

def is_cjk(ch: str) -> bool:
    cp = ord(ch)
    return (
        0x4E00 <= cp <= 0x9FFF
        or 0x3400 <= cp <= 0x4DBF
        or 0x20000 <= cp <= 0x2A6DF
        or 0x2A700 <= cp <= 0x2B73F
        or 0x2B740 <= cp <= 0x2B81F
        or 0xF900 <= cp <= 0xFAFF
        or 0x2F800 <= cp <= 0x2FA1F
        or 0x3000 <= cp <= 0x303F
        or 0x3040 <= cp <= 0x309F
        or 0x30A0 <= cp <= 0x30FF
        or 0x31F0 <= cp <= 0x31FF
        or 0xAC00 <= cp <= 0xD7AF
        or 0xFF00 <= cp <= 0xFFEF
        or 0xFE30 <= cp <= 0xFE4F
    )


def _is_whitespace(ch: str) -> bool:
    # Kotlin Char.isWhitespace(): ord <= 0x20 OR Character.isWhitespace(ch).
    return ord(ch) <= 0x20 or ch.isspace()


def cjk_ratio(text: str) -> float:
    """TextLayoutPlanner.kt:876-881 — fraction of non-ws chars that are CJK."""
    total = sum(1 for c in text if not c.isspace())
    if total == 0:
        return 0.0
    cjk = sum(1 for c in text if not c.isspace() and is_cjk(c))
    return cjk / total


def should_render_vertical(text: str) -> bool:
    """TextLayoutPlanner.kt:884 — vertical only when CJK is the majority (>50%)."""
    return cjk_ratio(text) > 0.5


# ─────────────────────────────────────────────────────────────────────────────
# Data types
# ─────────────────────────────────────────────────────────────────────────────

@dataclass
class FloatRect:
    """TextLayoutPlanner.kt:43-62 — axis-aligned float rect (JVM-testable)."""
    left: float
    top: float
    right: float
    bottom: float

    def width(self) -> float:
        return self.right - self.left

    def height(self) -> float:
        return self.bottom - self.top

    def overlaps(self, other: "FloatRect") -> bool:
        # Touching edges (zero area) is NOT an overlap.
        return (self.left < other.right and other.left < self.right
                and self.top < other.bottom and other.top < self.bottom)

    def intersection(self, other: "FloatRect") -> "FloatRect":
        return FloatRect(
            max(self.left, other.left),
            max(self.top, other.top),
            min(self.right, other.right),
            min(self.bottom, other.bottom),
        )


class TextAlign:
    """TextLayoutPlanner.kt:75 — horizontal anchor for a BlockLayout."""
    CENTER = "CENTER"
    LEFT = "LEFT"
    RIGHT = "RIGHT"


@dataclass
class Block:
    """Python analogue of TranslationBlock (PageTranslation.kt:214-241),
    carrying only the fields the layout/draw stages consume."""
    text: str = ""
    translation: str = ""
    width: float = 0.0
    height: float = 0.0
    x: float = 0.0
    y: float = 0.0
    sym_width: float = 1.0
    sym_height: float = 1.0
    angle: float = 0.0
    label: int = 1
    score: float = 1.0
    parent_x: float = 0.0
    parent_y: float = 0.0
    parent_width: float = 0.0
    parent_height: float = 0.0
    text_color: int = 0xFF000000       # ARGB; default opaque black
    stroke_color: int = 0xFFFFFFFF     # ARGB; default opaque white
    stroke_width: float = 0.0
    direction: str = "LTR"


@dataclass
class BlockLayout:
    """TextLayoutPlanner.kt:93-105 resolved placement + diagnostic extras.

    The first fields mirror the Kotlin data class 1:1 so the parity test can
    assert identical values; the trailing ``*_diag`` fields carry the per-block
    diagnostics contract (Task 0.4) and do not affect layout equality.
    """
    block: Block
    text: str
    is_vertical: bool
    origin_x: float
    origin_y: float
    safe_w: float
    safe_h: float
    font_size_px: float
    stroke_width: float
    draw_align: str
    clip_rect: Optional[FloatRect] = None
    # diagnostics-only extras:
    reshaped: bool = False
    grew: bool = False
    clipped: bool = False
    word_broken: bool = False
    ocr_anchored: bool = False
    safe_box: Optional[list] = None       # [x1,y1,x2,y2] final safe region
    parent_box: Optional[list] = None
    ocr_box: Optional[list] = None
    line_count: int = 0


# ─────────────────────────────────────────────────────────────────────────────
# TextMeasurer — TextLayoutPlanner.kt:24-34
# ─────────────────────────────────────────────────────────────────────────────

class TextMeasurer(Protocol):
    def measure_text_width(self, text: str, font_size_px: float) -> float: ...
    def line_height(self, font_size_px: float) -> float: ...


class FontMeasurer:
    """PageTextRenderer.kt:44-60 — Paint-backed measurer, here via PIL.

    measureTextWidth -> ImageFont.getlength; lineHeight -> ascent+descent
    (maps Android ``fm.descent - fm.ascent`` since PIL ascent is the magnitude
    of Android's negative ``fm.ascent``).
    """
    def __init__(self, font_path: Optional[str] = None):
        self._path = font_path or _resolve_font_path()
        self._cache: dict[int, ImageFont.FreeTypeFont] = {}

    def _font(self, size: float) -> ImageFont.FreeTypeFont:
        key = int(round(size))
        f = self._cache.get(key)
        if f is None:
            f = ImageFont.truetype(self._path, max(1, key))
            self._cache[key] = f
        return f

    def measure_text_width(self, text: str, font_size_px: float) -> float:
        return float(self._font(font_size_px).getlength(text))

    def line_height(self, font_size_px: float) -> float:
        ascent, descent = self._font(font_size_px).getmetrics()
        return float(ascent + descent)


class FakeMeasurer:
    """Deterministic fake mirroring TextLayoutPlannerTest.kt:23-28 so the
    parity test shares one measurer definition across the Kotlin/Python ports."""
    def __init__(self, char_width: float = 0.6):
        self.char_width = char_width

    def measure_text_width(self, text: str, font_size_px: float) -> float:
        return len(text) * self.char_width * font_size_px

    def line_height(self, font_size_px: float) -> float:
        return font_size_px * 1.2


def _resolve_font_path() -> str:
    for name in _FONT_CHAIN:
        p = _FONT_DIR / name
        if p.is_file():
            return str(p)
    raise FileNotFoundError(f"No render font found under {_FONT_DIR}")


# ─────────────────────────────────────────────────────────────────────────────
# Pure per-block math — TextLayoutPlanner.kt:855-1056
# ─────────────────────────────────────────────────────────────────────────────

def min_legible_font(page_width: float, page_height: float, scale: float) -> float:
    """TextLayoutPlanner.kt:235-236."""
    return max(LEGIBLE_FONT_ABS_PX * scale, min(page_width, page_height) * LEGIBLE_FONT_FRACTION)


def _stripped_length(text: str) -> int:
    """TextLayoutPlanner.kt:743-744 — chars excluding \\r \\n and space."""
    return sum(1 for c in text if c not in "\r\n ")


def _columns_for(text: str, safe_h: float, char_step: float) -> int:
    """TextLayoutPlanner.kt:736-741."""
    chars = _stripped_length(text)
    if chars == 0:
        return 1
    max_chars = max(1, int(safe_h / char_step))
    return max(1, math.ceil(chars / max_chars))


@dataclass
class _RectResult:
    """TextLayoutPlanner.kt:886-896."""
    base_x: float
    base_y: float
    base_w: float
    base_h: float
    safe_w: float
    safe_h: float
    reshaped: bool
    orig_left: float
    orig_right: float


def compute_rects(block: Block, sample_size: int = 1) -> _RectResult:
    """TextLayoutPlanner.kt:906-939."""
    scale = 1.0 / sample_size
    has_parent = block.parent_width > 0.0 and block.parent_height > 0.0
    if has_parent:
        text_pad = max(12.0 * scale, 0.15 * min(block.parent_width, block.parent_height))
    else:
        text_pad = max(4.0 * scale, 0.03 * min(block.width, block.height))
    base_x = block.parent_x if has_parent else block.x
    base_y = block.parent_y if has_parent else block.y
    base_w = block.parent_width if has_parent else block.width
    base_h = block.parent_height if has_parent else block.height
    orig_left = base_x
    orig_right = base_x + base_w
    reshaped = False
    if (not has_parent) and base_h > 0.0 and base_w > 0.0 and base_h / base_w > RESHAPE_TALL_RATIO:
        area = base_w * base_h
        new_h = math.sqrt(area)
        new_w = new_h
        new_w = min(max(new_w, base_w * RESHAPE_MIN_WIDTH_FACTOR), base_w * RESHAPE_MAX_WIDTH_FACTOR)
        new_h = area / new_w
        base_x = (orig_left + orig_right) / 2.0 - new_w / 2.0
        base_y += (base_h - new_h) / 2.0
        base_w = new_w
        base_h = new_h
        reshaped = True
    safe_pad = min(text_pad, min(base_w, base_h) / 3.0)
    safe_w = max(1.0, base_w - safe_pad * 2.0)
    safe_h = max(1.0, base_h - safe_pad * 2.0)
    return _RectResult(base_x, base_y, base_w, base_h, safe_w, safe_h, reshaped, orig_left, orig_right)


def cjk_wrap(text: str, font_size_px: float, max_width_px: float, measurer: TextMeasurer) -> list[str]:
    """TextLayoutPlanner.kt:947-990 — greedy wrap; CJK glyph = token, Latin run atomic."""
    tokens: list[str] = []
    i = 0
    n = len(text)
    while i < n:
        ch = text[i]
        if ch == '\n':
            tokens.append("\n")
            i += 1
        elif _is_whitespace(ch):
            tokens.append(" ")
            i += 1
        elif is_cjk(ch):
            tokens.append(ch)
            i += 1
        else:
            start = i
            while i < n and not is_cjk(text[i]) and not _is_whitespace(text[i]) and text[i] != '\n':
                i += 1
            tokens.append(text[start:i])
    lines: list[str] = []
    current = ""
    for token in tokens:
        if token == "\n":
            lines.append(current)
            current = ""
            continue
        candidate = current + token
        width = measurer.measure_text_width(candidate, font_size_px)
        if width > max_width_px and current:
            lines.append(current.rstrip())
            current = "" if token == " " else token
        else:
            current += token
    if current:
        lines.append(current.rstrip())
    return lines if lines else [text]


def binary_search_font_size(
    text: str, safe_w: float, safe_h: float, container_w: float,
    is_vertical: bool, scale: float, measurer: TextMeasurer,
) -> float:
    """TextLayoutPlanner.kt:999-1042 — integer binary search over the fit range."""
    start_size = int(max(container_w * FIT_START_WIDTH_FACTOR, FIT_MIN_FONT_PX * scale * 4.5))
    high = min(max(start_size, int(FIT_MIN_FONT_PX * scale * 4.5)), int(FIT_MAX_FONT_PX * scale))
    low = max(2, int(FIT_MIN_FONT_PX * scale))
    best = float(low)
    while low <= high:
        mid = (low + high) // 2
        mid_f = float(mid)
        if is_vertical:
            char_step = mid_f * VERTICAL_CHAR_STEP
            col_step = mid_f * VERTICAL_COL_STEP
            chars = _stripped_length(text)
            max_chars = max(1, int(safe_h / char_step))
            num_cols = max(1, math.ceil(chars / max_chars))
            total_w = num_cols * col_step
            max_col_h = max_chars * char_step
            if total_w <= safe_w and max_col_h <= safe_h:
                best = mid_f
                low = mid + 1
            else:
                high = mid - 1
        else:
            wrapped = cjk_wrap(text, mid_f, safe_w, measurer)
            line_h = measurer.line_height(mid_f)
            total_height = len(wrapped) * line_h
            max_line_width = max((measurer.measure_text_width(w, mid_f) for w in wrapped), default=0.0)
            if total_height <= safe_h and max_line_width <= safe_w:
                best = mid_f
                low = mid + 1
            else:
                high = mid - 1
    return best


def compute_stroke_width(block: Block, font_size_px: float, scale: float) -> float:
    """TextLayoutPlanner.kt:1045-1055."""
    if block.stroke_width > 0.0:
        start_size_estimate = max(block.width * FIT_START_WIDTH_FACTOR, FIT_MIN_FONT_PX * scale * 4.5)
        if start_size_estimate > 0.0 and font_size_px < start_size_estimate:
            scaled = (block.stroke_width * scale) * (font_size_px / start_size_estimate)
        else:
            scaled = block.stroke_width * scale
        return max(1.0 * scale, scaled)
    return max(1.5 * scale, font_size_px * 0.07)


# ─────────────────────────────────────────────────────────────────────────────
# Placement — TextLayoutPlanner.kt:186-853
# ─────────────────────────────────────────────────────────────────────────────

def _free_space_left(center_x: float, orig_left: float, obstacles: list[FloatRect], page_width: float) -> float:
    """TextLayoutPlanner.kt:496-507."""
    bound = 0.0
    for obs in obstacles:
        if obs.right <= center_x and obs.right > bound:
            bound = obs.right
    return max(0.0, orig_left - bound)


def _free_space_right(center_x: float, orig_right: float, obstacles: list[FloatRect], page_width: float) -> float:
    """TextLayoutPlanner.kt:510-521."""
    bound = page_width
    for obs in obstacles:
        if obs.left >= center_x and obs.left < bound:
            bound = obs.left
    return max(0.0, bound - orig_right)


def _free_space_vertical_up(center_y: float, orig_top: float, obstacles: list[FloatRect]) -> float:
    """TextLayoutPlanner.kt:683-689."""
    bound = 0.0
    for obs in obstacles:
        if obs.bottom <= center_y and obs.bottom > bound:
            bound = obs.bottom
    return max(0.0, orig_top - bound)


def _free_space_vertical_down(center_y: float, orig_bottom: float, obstacles: list[FloatRect], page_height: float) -> float:
    """TextLayoutPlanner.kt:691-702."""
    bound = page_height
    for obs in obstacles:
        if obs.top >= center_y and obs.top < bound:
            bound = obs.top
    return max(0.0, bound - orig_bottom)


def _tokenize(text: str) -> list[str]:
    """TextLayoutPlanner.kt:781-797."""
    tokens: list[str] = []
    i = 0
    n = len(text)
    while i < n:
        ch = text[i]
        if ch == '\n' or _is_whitespace(ch):
            i += 1
        elif is_cjk(ch):
            tokens.append(ch)
            i += 1
        else:
            start = i
            while i < n and not is_cjk(text[i]) and not _is_whitespace(text[i]) and text[i] != '\n':
                i += 1
            tokens.append(text[start:i])
    return tokens


def _min_width_for_lines(text: str, font: float, max_lines: int, measurer: TextMeasurer) -> float:
    """TextLayoutPlanner.kt:752-779."""
    tokens = _tokenize(text)
    full_w = max(1.0, measurer.measure_text_width(text, font))
    if max_lines <= 1:
        return full_w
    min_word_w = max((measurer.measure_text_width(t, font) for t in tokens), default=full_w)
    min_word_w = max(1.0, min_word_w)
    if len(cjk_wrap(text, font, min_word_w, measurer)) <= max_lines:
        return min_word_w
    lo = min_word_w
    hi = full_w
    ans = full_w
    guard = 0
    while lo <= hi and guard < 24:
        mid = (lo + hi) / 2.0
        if len(cjk_wrap(text, font, mid, measurer)) <= max_lines:
            ans = mid
            hi = mid
        else:
            lo = mid
        if hi - lo < 1.0:
            break
        guard += 1
    return ans


def _overflows(text: str, font: float, is_vertical: bool, safe_w: float, safe_h: float, measurer: TextMeasurer) -> bool:
    """TextLayoutPlanner.kt:705-723."""
    if is_vertical:
        char_step = font * VERTICAL_CHAR_STEP
        col_step = font * VERTICAL_COL_STEP
        cols = _columns_for(text, safe_h, char_step)
        return cols * col_step > safe_w + 0.5
    lines = cjk_wrap(text, font, safe_w, measurer)
    line_h = measurer.line_height(font)
    if len(lines) * line_h > safe_h + 0.5:
        return True
    return any(measurer.measure_text_width(ln, font) > safe_w + 0.5 for ln in lines)


def _fits_at(text: str, font: float, is_vertical: bool, safe_w: float, safe_h: float, measurer: TextMeasurer) -> bool:
    """TextLayoutPlanner.kt:726-733."""
    return not _overflows(text, font, is_vertical, safe_w, safe_h, measurer)


@dataclass
class _GrownBox:
    """TextLayoutPlanner.kt:667-681."""
    base_x: float
    base_y: float
    base_w: float
    base_h: float
    safe_w: float
    safe_h: float
    font_size: float
    grew_right: bool
    grew_left: bool


def _grow_into_free_space_if_needed(
    text: str, is_vertical: bool, base_x: float, base_y: float, base_w: float, base_h: float,
    safe_pad: float, obstacles: list[FloatRect], page_width: float, page_height: float,
    min_legible: float, current_font: float, measurer: TextMeasurer,
) -> _GrownBox:
    """TextLayoutPlanner.kt:530-665."""
    bx, by, bw, bh = base_x, base_y, base_w, base_h
    sw = max(1.0, bw - safe_pad * 2.0)
    sh = max(1.0, bh - safe_pad * 2.0)
    font = current_font
    grew_right = False
    grew_left = False

    target_font = max(min_legible, current_font)
    needs_growth = font < min_legible or _overflows(text, font, is_vertical, sw, sh, measurer)
    if not needs_growth:
        return _GrownBox(bx, by, bw, bh, sw, sh, font, grew_right, grew_left)

    if not is_vertical:
        line_h = measurer.line_height(target_font)
        center_y = by + bh / 2.0
        safe_top = by + safe_pad
        safe_bottom = by + bh - safe_pad
        free_up = _free_space_vertical_up(center_y, safe_top, obstacles)
        free_down = _free_space_vertical_down(center_y, safe_bottom, obstacles, page_height)

        base_lines = max(1, int(sh / line_h))
        max_lines_by_height = max(base_lines, int((sh + max(free_up, free_down)) / line_h))
        sw_before_growth = sw
        fit_lines = None
        for n_lines in range(base_lines, max_lines_by_height + 1):
            if _min_width_for_lines(text, target_font, n_lines, measurer) <= sw_before_growth:
                fit_lines = n_lines
                break
        target_lines = fit_lines if fit_lines is not None else max_lines_by_height
        height_growth_needed = max(0.0, target_lines * line_h - sh)
        if height_growth_needed > 0.0:
            if free_down >= free_up:
                bh += min(free_down, height_growth_needed)
            else:
                g = min(free_up, height_growth_needed)
                by -= g
                bh += g
            sh = max(1.0, bh - safe_pad * 2.0)

        max_lines = max(1, int(sh / line_h))
        safe_left = bx + safe_pad
        safe_right = bx + bw - safe_pad
        center_x = bx + bw / 2.0
        free_left = _free_space_left(center_x, safe_left, obstacles, page_width)
        free_right = _free_space_right(center_x, safe_right, obstacles, page_width)
        needed_w = _min_width_for_lines(text, target_font, max_lines, measurer)
        width_growth_needed = max(0.0, needed_w - sw)
        if width_growth_needed > 0.0:
            if free_right >= free_left:
                bw += min(free_right, width_growth_needed)
                grew_right = True
            else:
                g = min(free_left, width_growth_needed)
                bx -= g
                bw += g
                grew_left = True
            sw = max(1.0, bw - safe_pad * 2.0)
    else:
        safe_top = by + safe_pad
        safe_bottom = by + bh - safe_pad
        center_y = by + bh / 2.0
        free_up = _free_space_vertical_up(center_y, safe_top, obstacles)
        free_down = _free_space_vertical_down(center_y, safe_bottom, obstacles, page_height)

        char_step = target_font * VERTICAL_CHAR_STEP
        col_step = target_font * VERTICAL_COL_STEP
        cols_at_current = _columns_for(text, sh, char_step)
        col_width_now = cols_at_current * col_step
        if col_width_now > sw:
            chars = _stripped_length(text)
            cols_target = max(1, cols_at_current - 1)
            chars_per_col = max(1, math.ceil(chars / cols_target))
            growth_needed = max(0.0, chars_per_col * char_step - sh)
        else:
            growth_needed = 0.0
        if growth_needed > 0.0:
            if free_down >= free_up:
                bh += min(free_down, growth_needed)
            else:
                g = min(free_up, growth_needed)
                by -= g
                bh += g
            sh = max(1.0, bh - safe_pad * 2.0)

    font = binary_search_font_size(text, sw, sh, bw, is_vertical, 1.0, measurer)
    if font < min_legible and _fits_at(text, target_font, is_vertical, sw, sh, measurer):
        font = target_font
    return _GrownBox(bx, by, bw, bh, sw, sh, font, grew_right, grew_left)


def _resolve_minimal_displacement_x(
    preferred_x: float, width: float, page_width: float,
    obstacles: list[FloatRect], orig_center_x: float,
) -> float:
    """TextLayoutPlanner.kt:458-490."""
    min_x_allowed = orig_center_x - width
    max_x_allowed = orig_center_x
    max_x = max(0.0, page_width - width)
    x = min(max(min(max(preferred_x, 0.0), max_x), min_x_allowed), max_x_allowed)
    guard = 0
    while guard < len(obstacles) + 1:
        candidate = FloatRect(x, 0.0, x + width, float("inf"))
        colliding = next((o for o in obstacles if candidate.overlaps(o)), None)
        if colliding is None:
            return x
        center_x = x + width / 2.0
        obs_center_x = (colliding.left + colliding.right) / 2.0
        if center_x < obs_center_x:
            next_x = colliding.left - width
        else:
            next_x = colliding.right
        next_x = min(max(min(max(next_x, 0.0), max_x), min_x_allowed), max_x_allowed)
        if next_x == x:
            return x
        x = next_x
        guard += 1
    return x


def extent_of(layout: BlockLayout, measurer: TextMeasurer) -> FloatRect:
    """TextLayoutPlanner.kt:804-826 — rendered pixel extent of a finalized layout.

    Public mirror of the planner's private ``extentOf`` (matches the test-visible
    ``extentOfPublic`` in TextLayoutPlannerTest.kt:487-510) so parity tests can
    assert the structural no-overlap invariant without reaching into internals.
    """
    cx = layout.origin_x
    cy = layout.origin_y
    if layout.is_vertical:
        char_step = layout.font_size_px * VERTICAL_CHAR_STEP
        col_step = layout.font_size_px * VERTICAL_COL_STEP
        cols = _columns_for(layout.text, layout.safe_h, char_step)
        total_w = cols * col_step
        col_h = min(layout.safe_h, _stripped_length(layout.text) * char_step)
        return FloatRect(cx - total_w / 2.0, cy - col_h / 2.0, cx + total_w / 2.0, cy + col_h / 2.0)
    lines = cjk_wrap(layout.text, layout.font_size_px, layout.safe_w, measurer)
    line_h = measurer.line_height(layout.font_size_px)
    total_h = len(lines) * line_h
    max_line_w = max((measurer.measure_text_width(ln, layout.font_size_px) for ln in lines), default=0.0)
    max_line_w = max(0.0, max_line_w)
    if layout.draw_align == TextAlign.LEFT:
        return FloatRect(cx, cy - total_h / 2.0, cx + max_line_w, cy + total_h / 2.0)
    if layout.draw_align == TextAlign.RIGHT:
        return FloatRect(cx - max_line_w, cy - total_h / 2.0, cx, cy + total_h / 2.0)
    return FloatRect(cx - max_line_w / 2.0, cy - total_h / 2.0, cx + max_line_w / 2.0, cy + total_h / 2.0)


def _centered_extent_overlaps_obstacle(
    text: str, font_size: float, safe_w: float, origin_x: float, origin_y: float,
    obstacles: list[FloatRect], measurer: TextMeasurer,
) -> bool:
    """TextLayoutPlanner.kt:828-853."""
    if not obstacles:
        return False
    lines = cjk_wrap(text, font_size, safe_w, measurer)
    line_h = measurer.line_height(font_size)
    total_h = len(lines) * line_h
    max_line_w = max((measurer.measure_text_width(ln, font_size) for ln in lines), default=0.0)
    max_line_w = max(0.0, max_line_w)
    centered = FloatRect(origin_x - max_line_w / 2.0, origin_y - total_h / 2.0,
                         origin_x + max_line_w / 2.0, origin_y + total_h / 2.0)
    for obs in obstacles:
        inter = centered.intersection(obs)
        if inter.width() > MIN_GAP_PX and inter.height() > MIN_GAP_PX:
            return True
    return False


def _place_block(
    block: Block, text: str, is_vertical: bool, rect: _RectResult,
    obstacles: list[FloatRect], page_width: float, page_height: float,
    min_legible: float, scale: float, measurer: TextMeasurer,
) -> BlockLayout:
    """TextLayoutPlanner.kt:255-440."""
    base_x = rect.base_x
    base_y = rect.base_y
    base_w = rect.base_w
    base_h = rect.base_h
    safe_pad = max(0.0, (base_w - rect.safe_w) / 2.0)

    has_parent = block.parent_width > 0.0 and block.parent_height > 0.0
    if has_parent:
        region = FloatRect(block.parent_x, block.parent_y,
                           block.parent_x + block.parent_width, block.parent_y + block.parent_height)
    elif rect.reshaped:
        region = FloatRect(0.0, 0.0, page_width, page_height)
    else:
        region = FloatRect(block.x, block.y, block.x + block.width, block.y + block.height)

    # (1) Re-anchor a reshaped tall box by minimal displacement.
    if rect.reshaped:
        orig_center_x = rect.orig_left + (rect.orig_right - rect.orig_left) / 2.0
        base_x = _resolve_minimal_displacement_x(
            preferred_x=orig_center_x - base_w / 2.0,
            width=base_w, page_width=page_width, obstacles=obstacles,
            orig_center_x=orig_center_x,
        )

    # (2) Fit, then grow into free space if undersized or overflowing.
    safe_w = max(1.0, base_w - safe_pad * 2.0)
    safe_h = max(1.0, base_h - safe_pad * 2.0)
    font_size = binary_search_font_size(text, safe_w, safe_h, base_w, is_vertical, scale, measurer)

    grown = _grow_into_free_space_if_needed(
        text, is_vertical, base_x, base_y, base_w, base_h, safe_pad,
        obstacles, page_width, page_height, min_legible, font_size, measurer,
    )
    base_w = min(grown.base_w, region.width())
    base_h = min(grown.base_h, region.height())
    base_x = min(max(grown.base_x, region.left), region.right - base_w)
    base_y = min(max(grown.base_y, region.top), region.bottom - base_h)
    safe_w = max(1.0, base_w - safe_pad * 2.0)
    safe_h = max(1.0, base_h - safe_pad * 2.0)
    font_size = binary_search_font_size(text, safe_w, safe_h, base_w, is_vertical, scale, measurer)

    stroke_width = compute_stroke_width(block, font_size, scale)
    origin_y = base_y + base_h / 2.0
    centered_origin_x = base_x + base_w / 2.0
    edge_anchor_would_avoid_overlap = (not is_vertical) and _centered_extent_overlaps_obstacle(
        text, font_size, safe_w, centered_origin_x, origin_y, obstacles, measurer,
    )
    if grown.grew_right and edge_anchor_would_avoid_overlap:
        origin_x = base_x
        draw_align = TextAlign.LEFT
    elif grown.grew_left and edge_anchor_would_avoid_overlap:
        origin_x = base_x + base_w
        draw_align = TextAlign.RIGHT
    else:
        origin_x = centered_origin_x
        draw_align = TextAlign.CENTER

    clip_rect: Optional[FloatRect] = None

    # (3) Clip safety-net.
    safe_left = base_x + safe_pad
    safe_top = base_y + safe_pad
    safe_right = base_x + base_w - safe_pad
    safe_bottom = base_y + base_h - safe_pad
    clip = FloatRect(safe_left, safe_top, safe_right, safe_bottom)
    needed_clip = False
    for obs in obstacles:
        if not clip.overlaps(obs):
            continue
        inter = clip.intersection(obs)
        center_x = base_x + base_w / 2.0
        if center_x < (obs.left + obs.right) / 2.0:
            clip = FloatRect(clip.left, clip.top, min(clip.right, inter.left), clip.bottom)
        else:
            clip = FloatRect(max(clip.left, inter.right), clip.top, clip.right, clip.bottom)
        needed_clip = True
    if needed_clip and clip.width() > MIN_GAP_PX and clip.height() > MIN_GAP_PX:
        clip_rect = clip
        if is_vertical:
            origin_x = clip.left + clip.width() / 2.0
        else:
            origin_x = clip.left
            draw_align = TextAlign.LEFT
        origin_y = clip.top + clip.height() / 2.0
        safe_w = clip.width()
        safe_h = clip.height()
        fit_font = binary_search_font_size(text, safe_w, safe_h, safe_w, is_vertical, scale, measurer)
        font_size = max(fit_font, min_legible)

    # (C) Containment clip.
    if _overflows(text, font_size, is_vertical, region.width(), region.height(), measurer):
        r = clip_rect.intersection(region) if clip_rect is not None else region
        if r.width() > MIN_GAP_PX and r.height() > MIN_GAP_PX:
            clip_rect = r
            if is_vertical:
                origin_x = r.left + r.width() / 2.0
            else:
                origin_x = r.left
                draw_align = TextAlign.LEFT
            origin_y = r.top + r.height() / 2.0
            safe_w = r.width()
            safe_h = r.height()
            font_size = max(font_size, min_legible)

    # Diagnostics: final safe box.
    if clip_rect is not None:
        final_safe_box = [clip_rect.left, clip_rect.top, clip_rect.right, clip_rect.bottom]
    else:
        final_safe_box = [base_x + safe_pad, base_y + safe_pad, base_x + base_w - safe_pad, base_y + base_h - safe_pad]

    ocr_cx = block.x + block.width / 2.0
    ocr_cy = block.y + block.height / 2.0
    anchor_to_ocr_center = block.label == 2 or (block.direction == "TTB" and not is_vertical)
    if anchor_to_ocr_center:
        origin_x = ocr_cx
        origin_y = ocr_cy
        draw_align = TextAlign.CENTER

    parent_box = None
    if has_parent:
        parent_box = [block.parent_x, block.parent_y, block.parent_x + block.parent_width, block.parent_y + block.parent_height]
    ocr_box = [block.x, block.y, block.x + block.width, block.y + block.height]

    line_count = len(cjk_wrap(text, font_size, safe_w, measurer)) if not is_vertical else _columns_for(text, safe_h, font_size * VERTICAL_CHAR_STEP)

    return BlockLayout(
        block=block, text=text, is_vertical=is_vertical,
        origin_x=origin_x, origin_y=origin_y, safe_w=safe_w, safe_h=safe_h,
        font_size_px=font_size, stroke_width=stroke_width, draw_align=draw_align,
        clip_rect=clip_rect,
        reshaped=rect.reshaped,
        grew=grown.grew_right or grown.grew_left,
        clipped=clip_rect is not None,
        word_broken=line_count > 1,
        ocr_anchored=anchor_to_ocr_center,
        safe_box=final_safe_box,
        parent_box=parent_box,
        ocr_box=ocr_box,
        line_count=line_count,
    )


def plan(
    blocks: list[Block], page_width: float, page_height: float, sample_size: int,
    render_source_text: bool, measurer: TextMeasurer,
) -> list[BlockLayout]:
    """TextLayoutPlanner.kt:186-232 — score-ordered, neighbour-aware placement."""
    if not blocks:
        return []
    scale = 1.0 / sample_size
    min_legible = min_legible_font(page_width, page_height, scale)

    # Stable order: highest score first, ties keep reading order.
    ordered = sorted(enumerate(blocks), key=lambda iv: (-iv[1].score, iv[0]))

    placed: list[BlockLayout] = []
    for _, block in ordered:
        text = _chosen_text(block, render_source_text)
        if not text.strip():
            continue
        rect = compute_rects(block, sample_size)
        if rect.safe_w < 1.0 or rect.safe_h < 1.0:
            continue
        is_vertical = block.direction == "TTB" and should_render_vertical(text)
        placed_obstacles = [extent_of(it, measurer) for it in placed]
        resolved = _place_block(
            block, text, is_vertical, rect, placed_obstacles,
            page_width, page_height, min_legible, scale, measurer,
        )
        placed.append(resolved)
    return placed


def _chosen_text(block: Block, render_source_text: bool) -> str:
    """TextLayoutPlanner.kt:238-239."""
    if render_source_text:
        return block.translation if block.translation.strip() else block.text
    return block.translation


# ─────────────────────────────────────────────────────────────────────────────
# Parent selection — RoiPageRecognitionEngine.kt:1113-1138
# ─────────────────────────────────────────────────────────────────────────────

def _intersection_area(a: list, b: list) -> int:
    """BoxGeometry.intersectionArea (BoxGeometry.kt:29-36)."""
    ix1 = max(a[0], b[0])
    iy1 = max(a[1], b[1])
    ix2 = min(a[2], b[2])
    iy2 = min(a[3], b[3])
    if ix2 <= ix1 or iy2 <= iy1:
        return 0
    return (ix2 - ix1) * (iy2 - iy1)


def select_parent_bubble(text_bbox, bubbles, center_x, center_y):
    """RoiPageRecognitionEngine.kt:1113-1138.

    ``bubbles`` is a list of dicts with ``bbox`` (and optional ``score``), the
    detector-v4 bubble (label 0) detections. Returns the chosen bubble dict or
    None. A text detection's center selects the SMALLEST containing bubble; if
    none contains it, the bubble with the largest overlap (>=20% of the text
    area) wins, ties broken by detection score.
    """
    containing = [
        b for b in bubbles
        if center_x >= b["bbox"][0] and center_x <= b["bbox"][2]
        and center_y >= b["bbox"][1] and center_y <= b["bbox"][3]
    ]
    if containing:
        return min(containing, key=lambda b: (b["bbox"][2] - b["bbox"][0]) * (b["bbox"][3] - b["bbox"][1]))

    text_area = max(1, text_bbox[2] - text_bbox[0]) * max(1, text_bbox[3] - text_bbox[1])
    candidates = []
    for b in bubbles:
        overlap = _intersection_area(text_bbox, b["bbox"])
        if overlap >= text_area * MIN_PARENT_TEXT_OVERLAP_FRACTION:
            candidates.append((b, overlap))
    if not candidates:
        return None
    best = max(candidates, key=lambda c: (c[1], c[0].get("score", 0.0)))
    return best[0]


# ─────────────────────────────────────────────────────────────────────────────
# Drawing — PageTextRenderer.kt:185-268
# ─────────────────────────────────────────────────────────────────────────────

def _argb_to_rgb(argb: int) -> tuple[int, int, int]:
    return ((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF)


def _resolve_colors(block: Block) -> tuple[tuple[int, int, int], tuple[int, int, int]]:
    """PageTextRenderer.kt:125-137 — hard outline invariant: every block has a
    contrasting stroke derived from the text luma, regardless of persisted value.
    """
    text_rgb = _argb_to_rgb(block.text_color)
    tr, tg, tb = text_rgb
    luma = (tr * 299 + tg * 587 + tb * 114) / 1000
    stroke = (255, 255, 255) if luma < 128 else (0, 0, 0)
    return text_rgb, stroke


def _draw_horizontal(draw, font_factory, text, font_size_px, safe_w, origin_x, origin_y, draw_align, fill, stroke, stroke_w, measurer):
    """PageTextRenderer.kt:185-220."""
    lines = cjk_wrap(text, font_size_px, safe_w, measurer)
    if not lines:
        return
    font = font_factory(font_size_px)
    ascent, descent = font.getmetrics()
    line_height = ascent + descent
    total_height = len(lines) * line_height
    # PIL anchor "a" = ascender (top of text) at y, matching Android
    # baseline lineY = originY - totalHeight/2 - fm.ascent  (fm.ascent<0).
    y_top = origin_y - total_height / 2.0
    h_anchor = {"LEFT": "l", "CENTER": "m", "RIGHT": "r"}[draw_align]
    anchor = h_anchor + "a"
    sw = max(1, int(round(stroke_w)))
    for line in lines:
        if line:
            draw.text((origin_x, y_top), line, font=font, fill=fill, anchor=anchor,
                      stroke_width=sw, stroke_fill=stroke)
        y_top += line_height


def _draw_vertical(draw, font_factory, text, font_size_px, origin_x, origin_y, safe_h, fill, stroke, stroke_w):
    """PageTextRenderer.kt:222-268."""
    char_step = font_size_px * VERTICAL_CHAR_STEP
    col_step = font_size_px * VERTICAL_COL_STEP
    chars = text.replace("\r", "").replace("\n", "").replace(" ", "")
    if not chars:
        return
    font = font_factory(font_size_px)
    ascent, _ = font.getmetrics()
    max_chars_per_col = max(1, int(safe_h / char_step))
    columns: list[str] = []
    current = ""
    for ch in chars:
        mapped = VERTICAL_PUNCTUATION_MAP.get(ch, ch)
        if len(current) >= max_chars_per_col:
            columns.append(current)
            current = ""
        current += mapped
    if current:
        columns.append(current)
    if not columns:
        return

    total_w = len(columns) * col_step
    cols_right = origin_x + total_w / 2.0
    sw = max(1, int(round(stroke_w)))
    for col_idx, col in enumerate(columns):
        col_cx = cols_right - col_idx * col_step - col_step / 2.0
        col_h = len(col) * char_step
        col_y_start = origin_y - col_h / 2.0
        for char_idx, ch in enumerate(col):
            char_y = col_y_start + char_idx * char_step
            # Android baselineY = charY - fm.ascent (= charY + ascent magnitude).
            baseline_y = char_y + ascent
            draw.text((col_cx, baseline_y), ch, font=font, fill=fill, anchor="mb",
                      stroke_width=sw, stroke_fill=stroke)


def _draw_block(main_img, overlay, font_factory, measurer, layout):
    """Draw one BlockLayout. ``main_img`` is the RGB canvas; ``overlay`` is an
    RGBA scratch layer used only for clipped blocks (canvas clip emulation)."""
    text_rgb, stroke_rgb = _resolve_colors(layout.block)
    if overlay is not None:
        fill = text_rgb + (255,)
        stroke = stroke_rgb + (255,)
        target = overlay
    else:
        fill = text_rgb
        stroke = stroke_rgb
        target = main_img
    draw = ImageDraw.Draw(target)
    if layout.is_vertical:
        _draw_vertical(draw, font_factory, layout.text, layout.font_size_px,
                       layout.origin_x, layout.origin_y, layout.safe_h, fill, stroke, layout.stroke_width)
    else:
        _draw_horizontal(draw, font_factory, layout.text, layout.font_size_px,
                         layout.safe_w, layout.origin_x, layout.origin_y, layout.draw_align,
                         fill, stroke, layout.stroke_width, measurer)


def _draw_layout(main_img, font_factory, measurer, layout):
    """PageTextRenderer.kt:148-179 — draw a layout, honouring its clip rect via a
    scratch RGBA overlay + crop paste (PIL has no canvas.clipRect)."""
    if layout.clip_rect is not None:
        cr = layout.clip_rect
        box = (int(math.floor(cr.left)), int(math.floor(cr.top)),
               int(math.ceil(cr.right)), int(math.ceil(cr.bottom)))
        w, h = main_img.size
        box = (max(0, box[0]), max(0, box[1]), min(w, box[2]), min(h, box[3]))
        if box[2] <= box[0] or box[3] <= box[1]:
            return
        overlay = Image.new("RGBA", (box[2] - box[0], box[3] - box[1]), (0, 0, 0, 0))
        # Translate layout origin into overlay-local coords.
        local = BlockLayout(
            block=layout.block, text=layout.text, is_vertical=layout.is_vertical,
            origin_x=layout.origin_x - box[0], origin_y=layout.origin_y - box[1],
            safe_w=layout.safe_w, safe_h=layout.safe_h, font_size_px=layout.font_size_px,
            stroke_width=layout.stroke_width, draw_align=layout.draw_align, clip_rect=None,
        )
        _draw_block(main_img, overlay, font_factory, measurer, local)
        main_img.paste(overlay, (box[0], box[1]), overlay)
    else:
        _draw_block(main_img, None, font_factory, measurer, layout)


# ─────────────────────────────────────────────────────────────────────────────
# Public entry point
# ─────────────────────────────────────────────────────────────────────────────

@dataclass
class RenderOptions:
    sample_size: int = 1
    render_source_text: bool = False
    font_path: Optional[str] = None


def render_page(
    rgb: np.ndarray,
    blocks: list[Block],
    sample_size: int | RenderOptions = 1,
    opts: Optional[RenderOptions] = None,
    measurer: Optional[TextMeasurer] = None,
) -> tuple[np.ndarray, list[dict]]:
    """Render translated blocks onto ``rgb`` and return (rendered_rgb, diagnostics).

    Mirrors PageTextRenderer.render (PageTextRenderer.kt:98-181): plan via
    TextLayoutPlanner, then draw each resolved layout with stroke+fill. The
    returned per-block diagnostics implement the Task 0.4 contract.
    """
    # Phase 0 plan public entry is render_page(rgb, blocks, sample_size, opts).
    # Accept the previous internal shorthand render_page(rgb, blocks, opts) too.
    if isinstance(sample_size, RenderOptions):
        opts = sample_size
        sample_size = opts.sample_size
    opts = opts or RenderOptions(sample_size=int(sample_size))
    opts.sample_size = int(sample_size)
    h, w = rgb.shape[:2]
    measurer = measurer if measurer is not None else FontMeasurer(opts.font_path)

    layouts = plan(blocks, float(w), float(h), opts.sample_size, opts.render_source_text, measurer)

    img = Image.fromarray(rgb.astype(np.uint8)).convert("RGB")

    def font_factory(size: float) -> ImageFont.FreeTypeFont:
        path = opts.font_path or _resolve_font_path()
        return ImageFont.truetype(path, max(1, int(round(size))))

    for layout in layouts:
        _draw_layout(img, font_factory, measurer, layout)

    rendered = np.array(img)
    diagnostics = [_layout_diagnostics(layout, w, h) for layout in layouts]
    return rendered, diagnostics


def _layout_diagnostics(layout: BlockLayout, page_w: int, page_h: int) -> dict:
    """Task 0.4 per-block diagnostics contract."""
    ocr_cx = layout.block.x + layout.block.width / 2.0
    ocr_cy = layout.block.y + layout.block.height / 2.0
    return {
        "hasParent": layout.parent_box is not None,
        "parentBox": layout.parent_box,
        "ocrBox": layout.ocr_box,
        "safeBox": layout.safe_box,
        "fontSize": round(layout.font_size_px, 3),
        "lineCount": layout.line_count,
        "direction": "vertical" if layout.is_vertical else "horizontal",
        "origin": [round(layout.origin_x, 2), round(layout.origin_y, 2)],
        "drawAlign": layout.draw_align,
        "shiftFromOcrCenter": [round(layout.origin_x - ocr_cx, 2), round(layout.origin_y - ocr_cy, 2)],
        "flags": {
            "reshaped": layout.reshaped,
            "grew": layout.grew,
            "clipped": layout.clipped,
            "wordBroken": layout.word_broken,
            "ocrAnchored": layout.ocr_anchored,
        },
        "clipRect": ([round(layout.clip_rect.left, 2), round(layout.clip_rect.top, 2),
                      round(layout.clip_rect.right, 2), round(layout.clip_rect.bottom, 2)]
                     if layout.clip_rect is not None else None),
        "strokeWidth": round(layout.stroke_width, 3),
        # Inpaint-specific fields — populated by /api/inpaint, null here.
        "maskBox": None,
        "maskArea": None,
        "lineArtOverlapPx": None,
        "pageSize": [page_w, page_h],
    }
