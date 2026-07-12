"""Boundary-aware tiered inpainting pipeline.

Port of BoundaryAwarePipeline.kt — computes the true flat interior of a bubble
via BFS flood-fill, classifies the region into FLAT/TEXTURED/COLOR tiers,
and provides the stats needed for tier-appropriate fill selection.
"""
from __future__ import annotations

import math
from collections import deque
from dataclasses import dataclass

import numpy as np

CONTAINMENT_DELTA = 35.0
FLOOD_MARGIN = 60
MIN_FLOOD_FRACTION = 0.80


class Tier:
    FLAT = "flat"
    TEXTURED = "textured"
    COLOR = "color"


@dataclass
class RegionStats:
    median_color: int  # packed ARGB
    gray_mean: float
    gray_std: float
    near_white_ratio: float
    dark_pixel_ratio: float
    edge_density: float
    saturation_mean: float
    sample_count: int


@dataclass
class ContainmentResult:
    mask: np.ndarray  # HxW uint8
    interior_median: int
    is_fallback: bool
    coverage: float


def compute_containment(
    pixels: np.ndarray,
    parent_bubble: list[int] | None,
    erase_boxes: list[list[int]],
) -> ContainmentResult:
    """BFS flood-fill to find the true flat interior.

    Args:
        pixels: HxWx3 uint8
        parent_bubble: [x1,y1,x2,y2] in local coords, or None for free text
        erase_boxes: list of [x1,y1,x2,y2] text boxes in local coords

    Returns ContainmentResult with the containment mask.
    """
    h, w = pixels.shape[:2]
    n = h * w

    seed_mask, interior_median = _build_seed_mask(pixels, w, h, parent_bubble, erase_boxes)

    if seed_mask is None or not np.any(seed_mask):
        fallback = _padded_union_mask(w, h, erase_boxes, 8)
        return ContainmentResult(fallback, interior_median, True, 0.0)

    seed_r = float((interior_median >> 16) & 0xFF)
    seed_g = float((interior_median >> 8) & 0xFF)
    seed_b = float(interior_median & 0xFF)

    # Flood bounds
    ys, xs = np.where(seed_mask != 0)
    min_sx, max_sx = int(xs.min()), int(xs.max())
    min_sy, max_sy = int(ys.min()), int(ys.max())
    fx1 = max(0, min_sx - FLOOD_MARGIN)
    fy1 = max(0, min_sy - FLOOD_MARGIN)
    fx2 = min(w, max_sx + FLOOD_MARGIN + 1)
    fy2 = min(h, max_sy + FLOOD_MARGIN + 1)

    containment = np.zeros((h, w), dtype=np.uint8)
    visited = np.zeros((h, w), dtype=bool)
    queue = deque()

    seed_coords = np.where(seed_mask != 0)
    for sy, sx in zip(seed_coords[0], seed_coords[1]):
        containment[sy, sx] = 1
        visited[sy, sx] = True
        queue.append((sx, sy))

    dx4 = [-1, 1, 0, 0]
    dy4 = [0, 0, -1, 1]

    while queue:
        cx, cy = queue.popleft()
        for d in range(4):
            nx = cx + dx4[d]
            ny = cy + dy4[d]
            if nx < fx1 or nx >= fx2 or ny < fy1 or ny >= fy2:
                continue
            if visited[ny, nx]:
                continue
            pr = float(pixels[ny, nx, 0])
            pg = float(pixels[ny, nx, 1])
            pb = float(pixels[ny, nx, 2])
            dr = pr - seed_r
            dg = pg - seed_g
            db = pb - seed_b
            if math.sqrt(dr * dr + dg * dg + db * db) <= CONTAINMENT_DELTA:
                visited[ny, nx] = True
                containment[ny, nx] = 1
                queue.append((nx, ny))

    flood_area = int(np.count_nonzero(containment))
    erase_area = sum(max(0, (b[2] - b[0])) * max(0, (b[3] - b[1])) for b in erase_boxes)

    if flood_area < erase_area * MIN_FLOOD_FRACTION:
        fallback = _padded_union_mask(w, h, erase_boxes, 8)
        return ContainmentResult(fallback, interior_median, True, flood_area * 100.0 / n)

    return ContainmentResult(containment, interior_median, False, flood_area * 100.0 / n)


def paint_exterior(pixels: np.ndarray, containment: np.ndarray, interior_median: int) -> np.ndarray:
    """Paint every pixel OUTSIDE containment to interior_median.

    Returns a new array (does not modify input).
    """
    result = pixels.copy()
    mr = (interior_median >> 16) & 0xFF
    mg = (interior_median >> 8) & 0xFF
    mb = interior_median & 0xFF
    outside = containment == 0
    result[outside] = [mr, mg, mb]
    return result


def collect_stats(pixels: np.ndarray, mask: np.ndarray) -> RegionStats:
    """Collect RegionStats over the non-zero pixels of mask."""
    h, w = pixels.shape[:2]
    n = h * w
    mask_sel = mask != 0

    if not np.any(mask_sel):
        return RegionStats(0xFFFFFFFF, 250.0, 5.0, 1.0, 0.0, 0.0, 0.0, 0)

    step = max(1, n // 2000)

    ys, xs = np.where(mask_sel)
    ys = ys[::step]
    xs = xs[::step]

    if len(ys) == 0:
        return RegionStats(0xFFFFFFFF, 250.0, 5.0, 1.0, 0.0, 0.0, 0.0, 0)

    sampled = pixels[ys, xs].astype(np.int32)
    r_ch = sampled[:, 0]
    g_ch = sampled[:, 1]
    b_ch = sampled[:, 2]
    gray = (r_ch * 299 + g_ch * 587 + b_ch * 114) // 1000
    max_c = np.maximum(np.maximum(r_ch, g_ch), b_ch)
    min_c = np.minimum(np.minimum(r_ch, g_ch), b_ch)
    sat = max_c - min_c

    count = len(sampled)
    median = (0xFF << 24) | (int(np.median(r_ch)) << 16) | (int(np.median(g_ch)) << 8) | int(np.median(b_ch))
    gray_mean = float(np.mean(gray))
    gray_std = float(np.std(gray))
    sat_mean = float(np.mean(sat))
    near_white = float(np.count_nonzero(gray > 230)) / count
    dark = float(np.count_nonzero(gray < 60)) / count

    # Edge density
    edge_count = 0
    for i in range(count):
        y, x = ys[i], xs[i]
        if x > 0 and y > 0:
            # pixels is uint8; cast to int before the weighted sum or the
            # uint8 * 299 multiplication overflows (>255).
            lp = pixels[y, x - 1]
            up = pixels[y - 1, x]
            left_gray = (int(lp[0]) * 299 + int(lp[1]) * 587 + int(lp[2]) * 114) // 1000
            up_gray = (int(up[0]) * 299 + int(up[1]) * 587 + int(up[2]) * 114) // 1000
            if abs(int(gray[i]) - left_gray) + abs(int(gray[i]) - up_gray) > 80:
                edge_count += 1
    edge_density = edge_count / count

    return RegionStats(median, gray_mean, gray_std, near_white, dark, edge_density, sat_mean, count)


def classify_tier(stats: RegionStats) -> str:
    """Classify region into FLAT / TEXTURED / COLOR."""
    flat_white = stats.near_white_ratio > 0.7 and stats.gray_std < 20
    flat_colored = stats.gray_std < 15 and stats.near_white_ratio <= 0.7
    dark_flat = stats.dark_pixel_ratio > 0.5 and stats.gray_std < 18

    if flat_white or flat_colored or dark_flat:
        return Tier.FLAT

    screentone = (14 <= stats.gray_std <= 50 and
                  stats.saturation_mean < 20 and
                  stats.edge_density < 0.20)
    saturated = stats.saturation_mean > 35

    if saturated:
        return Tier.COLOR
    if screentone:
        return Tier.TEXTURED
    if stats.gray_std < 22 and stats.edge_density < 0.08:
        return Tier.FLAT
    if stats.gray_std < 45:
        return Tier.TEXTURED
    if stats.edge_density < 0.12:
        return Tier.FLAT
    return Tier.TEXTURED


# ── Internal helpers ──────────────────────────────────────────────────

def _build_seed_mask(
    pixels: np.ndarray,
    w: int,
    h: int,
    parent_bubble: list[int] | None,
    erase_boxes: list[list[int]],
) -> tuple[np.ndarray | None, int]:
    if parent_bubble is not None:
        return _build_parent_bubble_seed(pixels, w, h, parent_bubble)
    return _build_free_text_seed(pixels, w, h, erase_boxes)


def _build_parent_bubble_seed(
    pixels: np.ndarray,
    w: int,
    h: int,
    bubble: list[int],
) -> tuple[np.ndarray, int]:
    bx1 = max(0, bubble[0])
    by1 = max(0, bubble[1])
    bx2 = min(w, bubble[2])
    by2 = min(h, bubble[3])
    bw = bx2 - bx1
    bh = by2 - by1
    erode = max(2, min(bw, bh) // 10)
    sx1 = max(0, bx1 + erode)
    sy1 = max(0, by1 + erode)
    sx2 = max(sx1, min(w, bx2 - erode))
    sy2 = max(sy1, min(h, by2 - erode))
    return _sample_rect_median(pixels, w, h, sx1, sy1, sx2, sy2)


def _build_free_text_seed(
    pixels: np.ndarray,
    w: int,
    h: int,
    erase_boxes: list[list[int]],
) -> tuple[np.ndarray, int]:
    if not erase_boxes:
        return np.zeros((h, w), dtype=np.uint8), 0xFFFFFFFF

    ring_width = 12
    ring_ex1 = max(0, min(b[0] for b in erase_boxes) - ring_width)
    ring_ey1 = max(0, min(b[1] for b in erase_boxes) - ring_width)
    ring_ex2 = min(w, max(b[2] for b in erase_boxes) + ring_width)
    ring_ey2 = min(h, max(b[3] for b in erase_boxes) + ring_width)

    inner_pad = 2
    inner_ex1 = max(0, min(b[0] for b in erase_boxes) - inner_pad)
    inner_ey1 = max(0, min(b[1] for b in erase_boxes) - inner_pad)
    inner_ex2 = min(w, max(b[2] for b in erase_boxes) + inner_pad)
    inner_ey2 = min(h, max(b[3] for b in erase_boxes) + inner_pad)

    seed_mask = np.zeros((h, w), dtype=np.uint8)
    seed_mask[ring_ey1:ring_ey2, ring_ex1:ring_ex2] = 1
    seed_mask[inner_ey1:inner_ey2, inner_ex1:inner_ex2] = 0

    ring_pixels = pixels[seed_mask != 0]
    if len(ring_pixels) == 0:
        return seed_mask, 0xFFFFFFFF

    mr = int(np.median(ring_pixels[:, 0]))
    mg = int(np.median(ring_pixels[:, 1]))
    mb = int(np.median(ring_pixels[:, 2]))
    median = (0xFF << 24) | (mr << 16) | (mg << 8) | mb

    # Filter seed to pixels close to the ring median
    filtered = np.zeros((h, w), dtype=np.uint8)
    mask_coords = np.where(seed_mask != 0)
    for i in range(len(mask_coords[0])):
        y, x = mask_coords[0][i], mask_coords[1][i]
        pr = float(pixels[y, x, 0])
        pg = float(pixels[y, x, 1])
        pb = float(pixels[y, x, 2])
        dist = math.sqrt((pr - mr) ** 2 + (pg - mg) ** 2 + (pb - mb) ** 2)
        if dist <= CONTAINMENT_DELTA * 1.5:
            filtered[y, x] = 1

    if np.any(filtered):
        return filtered, median
    return seed_mask, median


def _sample_rect_median(
    pixels: np.ndarray,
    w: int,
    h: int,
    sx1: int,
    sy1: int,
    sx2: int,
    sy2: int,
) -> tuple[np.ndarray, int]:
    if sx2 <= sx1 or sy2 <= sy1:
        return np.zeros((h, w), dtype=np.uint8), 0xFFFFFFFF

    n = w * h
    area = (sx2 - sx1) * (sy2 - sy1)
    step = max(1, area // 500)

    sampled = []
    idx = 0
    for y in range(sy1, sy2):
        for x in range(sx1, sx2):
            if idx % step == 0:
                sampled.append(pixels[y, x])
            idx += 1

    if not sampled:
        return np.zeros((h, w), dtype=np.uint8), 0xFFFFFFFF

    arr = np.array(sampled, dtype=np.int32)
    mr = int(np.median(arr[:, 0]))
    mg = int(np.median(arr[:, 1]))
    mb = int(np.median(arr[:, 2]))
    median = (0xFF << 24) | (mr << 16) | (mg << 8) | mb

    seed_mask = np.zeros((h, w), dtype=np.uint8)
    for y in range(sy1, sy2):
        for x in range(sx1, sx2):
            pr = int(pixels[y, x, 0])
            pg = int(pixels[y, x, 1])
            pb = int(pixels[y, x, 2])
            if (pr - mr) ** 2 + (pg - mg) ** 2 + (pb - mb) ** 2 <= int(CONTAINMENT_DELTA * CONTAINMENT_DELTA):
                seed_mask[y, x] = 1

    return seed_mask, median


def _padded_union_mask(w: int, h: int, boxes: list[list[int]], pad: int) -> np.ndarray:
    mask = np.zeros((h, w), dtype=np.uint8)
    for box in boxes:
        x1 = max(0, box[0] - pad)
        y1 = max(0, box[1] - pad)
        x2 = min(w, box[2] + pad)
        y2 = min(h, box[3] + pad)
        if x2 > x1 and y2 > y1:
            mask[y1:y2, x1:x2] = 1
    return mask
