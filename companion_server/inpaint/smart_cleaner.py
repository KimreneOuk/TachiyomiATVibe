"""Smart bubble text cleaner — boundary-aware tiered fill.

Port of SmartBubbleTextCleaner.kt. Handles:
  - fill_contained: containment flood + tier classification → flat fill or Telea
  - clean_regions: classical Telea fill for individual boxes
  - fill_solid_boxes: solid padded box → Telea + feather blend
  - build_local_background: per-pixel Gaussian-weighted background interpolation
"""
from __future__ import annotations

import math

import numpy as np
from PIL import Image

from . import boundary as boundary_mod
from . import bubble_mask as bm
from . import fast_marching as fmm
from . import push_pull as pp

WHITE_ARGB = 0xFFFFFFFF
_CONTEXT_PAD = 10
_COLOR_DIST_THRESH = 45.0
_FEATHER_RADIUS = 6
_MASK_PAD = 8
_SOLID_PAD = 6
_MIN_DIM_DIVISOR_FEATHER = 12
_MIN_DIM_DIVISOR_DILATION = 20


def _scaled_feather(min_dim: int) -> int:
    return max(2, min(_FEATHER_RADIUS, max(2, min_dim // _MIN_DIM_DIVISOR_FEATHER)))


def _scaled_dilation(min_dim: int) -> int:
    return max(1, min(3, max(1, min_dim // _MIN_DIM_DIVISOR_DILATION)))


def fill_contained(
    image: Image.Image,
    x1: int, y1: int, x2: int, y2: int,
    parent_bubble: list[int] | None,
    text_boxes: list[list[int]],
) -> Image.Image:
    """Boundary-aware fill for one region using containment + tier.

    Mirrors SmartBubbleTextCleaner.fillContained:
      - Compute containment mask (bubble interior or connected flat region)
      - Classify tier (flat / textured / color)
      - FLAT → solid flat fill constrained to containment
      - TEXTURED/COLOR → paint exterior to median, Telea FMM, feather blend
    """
    w, h = image.size
    pad = _CONTEXT_PAD
    cx1 = max(0, x1 - pad)
    cy1 = max(0, y1 - pad)
    cx2 = min(w, x2 + pad)
    cy2 = min(h, y2 + pad)
    cw = cx2 - cx1
    ch = cy2 - cy1
    if cw <= 0 or ch <= 0:
        return image

    pixels = np.array(image.crop((cx1, cy1, cx2, cy2)).convert("RGB"), dtype=np.uint8)

    # Localize erase boxes
    erase_boxes = []
    for box in text_boxes:
        lx1 = max(0, box[0] - cx1)
        ly1 = max(0, box[1] - cy1)
        lx2 = min(cw, box[2] - cx1)
        ly2 = min(ch, box[3] - cy1)
        if lx2 > lx1 and ly2 > ly1:
            erase_boxes.append([lx1, ly1, lx2, ly2])
    if not erase_boxes:
        return image

    local_bubble = None
    if parent_bubble is not None:
        lbx1 = max(0, parent_bubble[0] - cx1)
        lby1 = max(0, parent_bubble[1] - cy1)
        lbx2 = min(cw, parent_bubble[2] - cx1)
        lby2 = min(ch, parent_bubble[3] - cy1)
        if lbx2 > lbx1 and lby2 > lby1:
            local_bubble = [lbx1, lby1, lbx2, lby2]

    # Containment
    cont_result = boundary_mod.compute_containment(pixels, local_bubble, erase_boxes)
    containment = cont_result.mask
    interior_median = cont_result.interior_median
    is_fallback = cont_result.is_fallback

    # Stats
    stats = boundary_mod.collect_stats(pixels, containment)
    tier = boundary_mod.classify_tier(stats)

    median_untrustworthy = is_fallback and interior_median == WHITE_ARGB
    use_flat_fill = (not median_untrustworthy and
                     tier == boundary_mod.Tier.FLAT and
                     (stats.near_white_ratio > 0.7 or
                      stats.dark_pixel_ratio > 0.5 or
                      (stats.gray_std < 10 and stats.edge_density < 0.04)))

    erase_mask = bm.build_rect_mask(
        boxes=[np.array(b) for b in erase_boxes],
        width=cw, height=ch,
        pad=_SOLID_PAD,
        dilate_radius=2,
    )
    if not np.any(erase_mask):
        return image

    region_dim = max(1, min(x2 - x1, y2 - y1))
    scaled_feather = _scaled_feather(region_dim)

    if use_flat_fill or (is_fallback and not median_untrustworthy):
        bg_source = containment if (local_bubble is not None and not is_fallback) else None
        local_bg = _build_local_background(pixels, erase_mask, interior_median, use_flat_fill, bg_source)
        alpha = bm.feather_alpha_field(erase_mask, scaled_feather)
        result_pixels = _apply_feathered_fill(pixels, local_bg, alpha)
    else:
        painted = pixels.copy()
        if not median_untrustworthy:
            painted = boundary_mod.paint_exterior(painted, containment, interior_median)
        reconstructed = fmm.inpaint_telea(painted, erase_mask, radius=3)
        alpha = bm.feather_alpha_field(erase_mask, scaled_feather)
        result_pixels = _apply_feathered_fill(pixels, reconstructed, alpha)

    result_image = Image.fromarray(result_pixels, "RGB")
    image.paste(result_image, (cx1, cy1))
    return image


def clean_regions(image: Image.Image, boxes: list[list[int]]) -> Image.Image:
    """Classical Telea fill for individual boxes."""
    for box in boxes:
        x1 = max(0, box[0])
        y1 = max(0, box[1])
        x2 = min(image.width, box[2])
        y2 = min(image.height, box[3])
        if x2 <= x1 or y2 <= y1:
            continue
        _clean_single_region(image, x1, y1, x2, y2)
    return image


def fill_solid_boxes(image: Image.Image, boxes: list[list[int]], mask_pad: int) -> Image.Image:
    """Solid padded box → Telea reconstruction + feather blend."""
    for box in boxes:
        x1 = max(0, box[0])
        y1 = max(0, box[1])
        x2 = min(image.width, box[2])
        y2 = min(image.height, box[3])
        if x2 <= x1 or y2 <= y1:
            continue
        _fill_solid_region(image, x1, y1, x2, y2, mask_pad)
    return image


def is_flat_background_region(image: Image.Image, box: list[int]) -> bool:
    """Quick check if a region has flat background."""
    x1 = max(0, box[0])
    y1 = max(0, box[1])
    x2 = min(image.width, box[2])
    y2 = min(image.height, box[3])
    if x2 <= x1 or y2 <= y1:
        return True

    pad = _CONTEXT_PAD
    cx1 = max(0, x1 - pad)
    cy1 = max(0, y1 - pad)
    cx2 = min(image.width, x2 + pad)
    cy2 = min(image.height, y2 + pad)
    pixels = np.array(image.crop((cx1, cy1, cx2, cy2)).convert("RGB"), dtype=np.uint8)
    cw, ch = pixels.shape[1], pixels.shape[0]

    # Ring mask
    ring_mask = np.ones((ch, cw), dtype=np.uint8)
    lx1 = max(0, x1 - cx1)
    ly1 = max(0, y1 - cy1)
    lx2 = min(cw, x2 - cx1)
    ly2 = min(ch, y2 - cy1)
    mp = 2
    ring_mask[max(0, ly1 - mp):min(ch, ly2 + mp), max(0, lx1 - mp):min(cw, lx2 + mp)] = 0

    ring_pixels = pixels[ring_mask != 0]
    if len(ring_pixels) == 0:
        return True

    gray = (0.299 * ring_pixels[:, 0] + 0.587 * ring_pixels[:, 1] + 0.114 * ring_pixels[:, 2])
    gray_std = float(np.std(gray))
    gray_mean = float(np.mean(gray))
    near_white = float(np.count_nonzero(gray > 230)) / len(gray)

    if near_white > 0.7 and gray_std < 20:
        return True
    if gray_std < 15:
        return True
    if gray_std < 35 and near_white > 0.5:
        return True
    return False


# ── Internal helpers ──────────────────────────────────────────────────

def _clean_single_region(image: Image.Image, x1: int, y1: int, x2: int, y2: int) -> None:
    """Telea fill for a single region with feather blend."""
    w, h = image.size
    pad = _CONTEXT_PAD
    cx1 = max(0, x1 - pad)
    cy1 = max(0, y1 - pad)
    cx2 = min(w, x2 + pad)
    cy2 = min(h, y2 + pad)
    cw, ch = cx2 - cx1, cy2 - cy1
    if cw <= 0 or ch <= 0:
        return

    pixels = np.array(image.crop((cx1, cy1, cx2, cy2)).convert("RGB"), dtype=np.uint8)

    min_dim = max(1, min(x2 - x1, y2 - y1))
    mp = max(2, min(8, min_dim // 8))
    lx1 = max(0, x1 - cx1 - mp)
    ly1 = max(0, y1 - cy1 - mp)
    lx2 = min(cw, x2 - cx1 + mp)
    ly2 = min(ch, y2 - cy1 + mp)

    erase_mask = np.zeros((ch, cw), dtype=np.uint8)
    if lx2 > lx1 and ly2 > ly1:
        erase_mask[ly1:ly2, lx1:lx2] = 1

    if not np.any(erase_mask):
        return

    erase_mask = bm.dilate_mask_disk(erase_mask, _scaled_dilation(min_dim))
    scaled_feather = _scaled_feather(min_dim)

    # Telea fill
    reconstructed = fmm.inpaint_telea(pixels, erase_mask, radius=3)
    alpha = bm.feather_alpha_field(erase_mask, scaled_feather)
    result_pixels = _apply_feathered_fill(pixels, reconstructed, alpha)

    result_image = Image.fromarray(result_pixels, "RGB")
    image.paste(result_image, (cx1, cy1))


def _fill_solid_region(image: Image.Image, x1: int, y1: int, x2: int, y2: int, mask_pad: int) -> None:
    """Telea reconstruction of a solid padded box with feather blend."""
    w, h = image.size
    pad = _CONTEXT_PAD
    cx1 = max(0, x1 - pad)
    cy1 = max(0, y1 - pad)
    cx2 = min(w, x2 + pad)
    cy2 = min(h, y2 + pad)
    cw, ch = cx2 - cx1, cy2 - cy1
    if cw <= 0 or ch <= 0:
        return

    pixels = np.array(image.crop((cx1, cy1, cx2, cy2)).convert("RGB"), dtype=np.uint8)

    fx1 = max(0, (x1 - cx1) - mask_pad)
    fy1 = max(0, (y1 - cy1) - mask_pad)
    fx2 = min(cw, (x2 - cx1) + mask_pad)
    fy2 = min(ch, (y2 - cy1) + mask_pad)

    solid_mask = np.zeros((ch, cw), dtype=np.uint8)
    if fx2 > fx1 and fy2 > fy1:
        solid_mask[fy1:fy2, fx1:fx2] = 1

    if not np.any(solid_mask):
        return

    min_dim = max(1, min(x2 - x1, y2 - y1))
    scaled_feather = _scaled_feather(min_dim)

    reconstructed = fmm.inpaint_telea(pixels, solid_mask, radius=3)
    alpha = bm.feather_alpha_field(solid_mask, scaled_feather)
    result_pixels = _apply_feathered_fill(pixels, reconstructed, alpha)

    result_image = Image.fromarray(result_pixels, "RGB")
    image.paste(result_image, (cx1, cy1))


def _build_local_background(
    pixels: np.ndarray,
    mask: np.ndarray,
    median_color: int,
    prefer_flat_fill: bool,
    bg_source_mask: np.ndarray | None,
) -> np.ndarray:
    """Per-pixel LOCAL background color — Gaussian-weighted average of surrounding bg pixels.

    Vectorized using PIL GaussianBlur for performance.
    """
    h, w = pixels.shape[:2]
    mr = (median_color >> 16) & 0xFF
    mg = (median_color >> 8) & 0xFF
    mb = median_color & 0xFF

    if prefer_flat_fill:
        bg = pixels.copy()
        bg[mask != 0] = [mr, mg, mb]
        return bg

    r = max(2, _FEATHER_RADIUS)

    # Create background-only image: set masked pixels to 0
    is_bg = mask == 0
    if bg_source_mask is not None:
        is_bg = is_bg & (bg_source_mask != 0)

    bg_weight = is_bg.astype(np.float32)
    bg_pixels = pixels.astype(np.float32) * bg_weight[:, :, np.newaxis]

    # Use PIL GaussianBlur for fast weighted averaging (C-implemented)
    from PIL import Image, ImageFilter

    # Blur the background-weighted pixel sum
    bg_sum_img = Image.fromarray(np.clip(bg_pixels, 0, 255).astype(np.uint8), "RGB")
    bg_sum_blurred = np.array(bg_sum_img.filter(ImageFilter.GaussianBlur(radius=r)), dtype=np.float32)

    # Blur the weight (count of background pixels in neighborhood)
    weight_img = Image.fromarray((bg_weight * 255).astype(np.uint8), "L")
    weight_blurred = np.array(weight_img.filter(ImageFilter.GaussianBlur(radius=r)), dtype=np.float32) / 255.0

    # Local average = weighted sum / weight count
    safe_weight = np.maximum(weight_blurred, 1e-6)
    local_avg = bg_sum_blurred / safe_weight[:, :, np.newaxis]

    # Build result: background pixels keep original, masked pixels get local average
    bg = pixels.copy().astype(np.float32)
    mask_sel = mask != 0
    bg[mask_sel] = local_avg[mask_sel]

    # Fallback: where weight is too low, use median
    low_weight = weight_blurred <= 0.01
    fallback_sel = mask_sel & low_weight
    bg[fallback_sel] = [mr, mg, mb]

    return np.clip(bg, 0, 255).astype(np.uint8)


def _apply_feathered_fill(
    original: np.ndarray,
    filled: np.ndarray,
    alpha: np.ndarray,
) -> np.ndarray:
    """Blend original and filled arrays using alpha."""
    a = alpha[:, :, np.newaxis]
    orig_f = original.astype(np.float32)
    fill_f = filled.astype(np.float32)
    result = orig_f * (1.0 - a) + fill_f * a
    return np.clip(result, 0, 255).astype(np.uint8)
