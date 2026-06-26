"""Coherent Inpaint (CCI): pure NumPy inpainting for manga translation.

Ports Kotlin reference algorithms from the TachiyomiAT codebase:
  - FastMarchingMethod.inpaintTelea  → telea_inpaint
  - BubbleMaskBuilder.*             → dilate_disk, chamfer_distance, feather_alpha_field
  - AotOutputGuard.isSuspiciousGrayFill → is_uniform_output
  - BoundaryAwarePipeline.*         → sample_background, classify_tier
  - SmartBubbleTextCleaner.*        → local_background_fill

Every public function is a pure function (no I/O, no mutable globals).
"""

from __future__ import annotations

import enum
import heapq
import math
from dataclasses import dataclass, field
from typing import Callable

import numpy as np


# ---------------------------------------------------------------------------
# Exceptions
# ---------------------------------------------------------------------------

class PoissonBorderError(ValueError):
    """Raised when the Poisson blend mask touches the crop border or is empty."""


# ---------------------------------------------------------------------------
# Options and types
# ---------------------------------------------------------------------------

@dataclass
class CCIOptions:
    free_min_side: int = 18
    free_long_floor: int = 22
    mask_pad_min: int = 4
    mask_pad_max: int = 12
    dilate_radius: int = 2
    feather_ramp: int = 4
    telea_radius: int = 3
    poisson_iters: int = 300
    # IMPORTANT: default False. For text inpainting the Poisson blend must IMPORT
    # the reconstruction's gradients (NORMAL_CLONE semantics) so the erased text
    # stays erased. mixing=True picks the larger of source/dest gradient per
    # pixel, which preserves the destination's strong text-stroke gradients and
    # re-reconstructs the "hair"/strokes back into the result. mixing only helps
    # when you want to KEEP destination texture, which is never the case here.
    poisson_mixing: bool = False
    poisson_tol: float = 1e-3
    flat_near_white: float = 0.7
    flat_white_std: float = 20.0
    flat_colored_std: float = 15.0
    dark_ratio: float = 0.5
    dark_flat_std: float = 18.0
    screentone_std_lo: float = 14.0
    screentone_std_hi: float = 50.0
    screentone_sat: float = 20.0
    color_sat: float = 35.0
    uniform_min_pixels: int = 16
    uniform_max_var: float = 36.0
    uniform_max_chan_delta: float = 8.0
    mid_gray_lo: float = 96.0
    mid_gray_hi: float = 160.0
    near_white: float = 238.0


@dataclass
class BackgroundStats:
    median_color: tuple
    gray_mean: float
    gray_std: float
    near_white_ratio: float
    dark_ratio: float
    edge_density: float
    saturation_mean: float
    sample_count: int


class Tier(str, enum.Enum):
    FLAT = "FLAT"
    TEXTURED = "TEXTURED"
    COLOR = "COLOR"


# ---------------------------------------------------------------------------
# Low-level mask / feather helpers
# ---------------------------------------------------------------------------

def _normalize_mask(mask: np.ndarray) -> np.ndarray:
    """Return a boolean mask from any uint8/float/int mask."""
    return mask.astype(bool) if mask.dtype != bool else mask


def dilate_disk(mask: np.ndarray, radius: int) -> np.ndarray:
    """Disk-structuring-element morphological dilation.

    Port of ``BubbleMaskBuilder.dilateMaskDisk``.
    Input: uint8 (0/255) or bool.  Output: uint8 (0/255).
    """
    if radius <= 0:
        return (mask > 0).astype(np.uint8) * 255
    binary = (mask > 0)
    h, w = binary.shape
    # Precompute kernel offsets
    kernel = []
    r2 = radius * radius
    for dy in range(-radius, radius + 1):
        for dx in range(-radius, radius + 1):
            if dx * dx + dy * dy <= r2:
                kernel.append((dx, dy))
    out = np.zeros_like(binary)
    ys, xs = np.where(binary)
    for dx, dy in kernel:
        ny = ys + dy
        nx = xs + dx
        valid = (ny >= 0) & (ny < h) & (nx >= 0) & (nx < w)
        out[ny[valid], nx[valid]] = True
    return out.astype(np.uint8) * 255


def chamfer_distance(mask: np.ndarray) -> np.ndarray:
    """Two-pass (3,4) chamfer distance transform.

    Port of ``BubbleMaskBuilder.distanceToMask``.
    Returns float per-pixel distance to nearest set pixel (0 inside).
    """
    INF = 1_000_000_000
    H, D = 3, 4  # chamfer weights
    binary = (mask > 0)
    h, w = binary.shape
    dist = np.full((h, w), INF, dtype=np.int32)
    dist[binary] = 0

    # Forward pass (top-left to bottom-right)
    for y in range(h):
        row = y
        for x in range(w):
            if binary[y, x]:
                continue
            best = dist[y, x]
            if x > 0:
                v = dist[y, x - 1]
                if v < best - H:
                    best = v + H
            if y > 0:
                v = dist[y - 1, x]
                if v < best - H:
                    best = v + H
            if x > 0 and y > 0:
                v = dist[y - 1, x - 1]
                if v < best - D:
                    best = v + D
            if x < w - 1 and y > 0:
                v = dist[y - 1, x + 1]
                if v < best - D:
                    best = v + D
            dist[y, x] = best

    # Backward pass (bottom-right to top-left)
    for y in range(h - 1, -1, -1):
        for x in range(w - 1, -1, -1):
            if binary[y, x]:
                continue
            best = dist[y, x]
            if x < w - 1:
                v = dist[y, x + 1]
                if v < best - H:
                    best = v + H
            if y < h - 1:
                v = dist[y + 1, x]
                if v < best - H:
                    best = v + H
            if x < w - 1 and y < h - 1:
                v = dist[y + 1, x + 1]
                if v < best - D:
                    best = v + D
            if x > 0 and y < h - 1:
                v = dist[y + 1, x - 1]
                if v < best - D:
                    best = v + D
            dist[y, x] = best

    scale = 1.0 / H
    out = np.where(dist >= INF, np.inf, dist.astype(np.float64) * scale)
    return out


def feather_alpha_field(mask: np.ndarray, ramp_width: int) -> np.ndarray:
    """Distance-field feathered alpha (0..1).

    Port of ``BubbleMaskBuilder.featherAlphaField``.
    Returns float array (HxW) with 1 inside mask, linear ramp to 0.
    """
    if not np.any(mask):
        return np.zeros(mask.shape, dtype=np.float64)
    dist = chamfer_distance(mask)
    ramp = max(2, ramp_width)
    alpha = np.where(
        mask > 0,
        1.0,
        (1.0 - dist / ramp).clip(0.0, 1.0),
    )
    return alpha


# ---------------------------------------------------------------------------
# Box / mask building
# ---------------------------------------------------------------------------

def clamp_box(box, width: int, height: int):
    """Clamp [x1,y1,x2,y2] to [0,width] x [0,height] with x1≤x2,y1≤y2."""
    x1 = max(0, min(width, int(box[0])))
    y1 = max(0, min(height, int(box[1])))
    x2 = max(0, min(width, int(box[2])))
    y2 = max(0, min(height, int(box[3])))
    return [min(x1, x2), min(y1, y2), max(x1, x2), max(y1, y2)]


def expand_tiny_free_boxes(
    boxes, is_free, page_w: int, page_h: int, opts: CCIOptions,
    parent_rect=None,
):
    """Expand tiny free-text boxes to a minimum size floor.

    Only expands boxes where ``is_free[i]`` is True AND the box dimensions
    are below the floors in *opts*.  Non-free (parented) boxes pass through
    unchanged.  Expansion is symmetric around the box center, clipped to page.
    """
    out = []
    for i, box in enumerate(boxes):
        if not is_free[i]:
            out.append(list(box))
            continue
        x1, y1, x2, y2 = box
        bw, bh = x2 - x1, y2 - y1
        short_side = min(bw, bh)
        long_side = max(bw, bh)
        need_expand = False
        # dt = [left, top, right, bottom] (positive additions to each side)
        dt = [0, 0, 0, 0]
        if short_side < opts.free_min_side:
            deficit = opts.free_min_side - short_side
            half = deficit / 2.0
            if bw <= bh:
                dt[0] = int(math.ceil(half))
                dt[2] = int(math.floor(half))
            else:
                dt[1] = int(math.ceil(half))
                dt[3] = int(math.floor(half))
            need_expand = True
        if long_side < opts.free_long_floor:
            deficit = opts.free_long_floor - long_side
            half = deficit / 2.0
            if bw <= bh:
                dt[1] = int(math.ceil(half))
                dt[3] = int(math.floor(half))
            else:
                dt[0] = int(math.ceil(half))
                dt[2] = int(math.floor(half))
            need_expand = True
        if not need_expand:
            out.append([x1, y1, x2, y2])
        else:
            nx1 = max(0, x1 - dt[0])
            ny1 = max(0, y1 - dt[1])
            nx2 = min(page_w, x2 + dt[2])
            ny2 = min(page_h, y2 + dt[3])
            out.append([nx1, ny1, nx2, ny2])
    return out


def build_cluster_mask(
    crop_rgb: np.ndarray, boxes_local, opts: CCIOptions,
) -> np.ndarray:
    """Build a uint8 mask (0/255) for a cluster of boxes in crop coords.

    Adaptive padding: ``p = clip(round(short_side_of_union * 0.15),
    mask_pad_min, mask_pad_max)``.  Each box is solid-filled then dilated.
    """
    h, w = crop_rgb.shape[:2]
    if not boxes_local:
        return np.zeros((h, w), dtype=np.uint8)

    # Union short side
    xs = []
    ys = []
    for b in boxes_local:
        xs.extend([b[0], b[2]])
        ys.extend([b[1], b[3]])
    ux1, ux2 = min(xs), max(xs)
    uy1, uy2 = min(ys), max(ys)
    short_side = min(ux2 - ux1, uy2 - uy1)
    p = int(round(short_side * 0.15))
    p = max(opts.mask_pad_min, min(opts.mask_pad_max, p))

    mask = np.zeros((h, w), dtype=np.uint8)
    for box in boxes_local:
        x1 = max(0, box[0] - p)
        y1 = max(0, box[1] - p)
        x2 = min(w, box[2] + p)
        y2 = min(h, box[3] + p)
        if x2 > x1 and y2 > y1:
            mask[y1:y2, x1:x2] = 255

    return dilate_disk(mask, opts.dilate_radius)


# ---------------------------------------------------------------------------
# Background model + tier
# ---------------------------------------------------------------------------

def sample_background(
    crop_rgb: np.ndarray, mask: np.ndarray,
    parent_rect_local=None, max_samples: int = 2000,
) -> BackgroundStats:
    """Collect background statistics from the ring outside *mask*.

    Port of ``BoundaryAwarePipeline.collectStats`` sampling strategy.
    Returns degenerate white stats if no pixels found.
    """
    h, w = crop_rgb.shape[:2]
    ring = np.ones((h, w), dtype=bool)

    # Exclude mask pixels
    ring = ring & (mask <= 0)

    # Optionally constrain to an eroded parent rectangle
    if parent_rect_local is not None:
        pr = parent_rect_local
        rx1, ry1, rx2, ry2 = pr
        rx1 = max(0, rx1)
        ry1 = max(0, ry1)
        rx2 = min(w, rx2)
        ry2 = min(h, ry2)
        interior = np.zeros((h, w), dtype=bool)
        interior[ry1:ry2, rx1:rx2] = True
        ring = ring & interior

    indices = np.where(ring)
    count_total = len(indices[0])
    if count_total == 0:
        return BackgroundStats(
            median_color=(255, 255, 255),
            gray_mean=250.0,
            gray_std=5.0,
            near_white_ratio=1.0,
            dark_ratio=0.0,
            edge_density=0.0,
            saturation_mean=0.0,
            sample_count=0,
        )

    step = max(1, count_total // max_samples)
    sampled_indices = (indices[0][::step], indices[1][::step])
    sampled = crop_rgb[sampled_indices].astype(np.float64)

    r_vals = sampled[:, 0]
    g_vals = sampled[:, 1]
    b_vals = sampled[:, 2]
    gray = 0.299 * r_vals + 0.587 * g_vals + 0.114 * b_vals

    median_r = float(np.median(r_vals))
    median_g = float(np.median(g_vals))
    median_b = float(np.median(b_vals))

    gray_mean = float(np.mean(gray))
    gray_var = float(np.var(gray))
    gray_std = math.sqrt(max(0.0, gray_var))

    near_white_ratio = float(np.mean(gray > 230))
    dark_ratio = float(np.mean(gray < 60))

    # Edge density: |gray - left_gray| + |gray - up_gray| > 80
    y_idx = sampled_indices[0]
    x_idx = sampled_indices[1]
    edge_count = 0
    total_check = len(y_idx)
    for j in range(total_check):
        y, x = y_idx[j], x_idx[j]
        if x > 0 and y > 0:
            left_gray = (0.299 * crop_rgb[y, x - 1, 0] +
                         0.587 * crop_rgb[y, x - 1, 1] +
                         0.114 * crop_rgb[y, x - 1, 2])
            up_gray = (0.299 * crop_rgb[y - 1, x, 0] +
                       0.587 * crop_rgb[y - 1, x, 1] +
                       0.114 * crop_rgb[y - 1, x, 2])
            if abs(gray[j] - left_gray) + abs(gray[j] - up_gray) > 80:
                edge_count += 1
    edge_density = edge_count / max(1, total_check)

    max_rgb = np.max(sampled, axis=1)
    min_rgb = np.min(sampled, axis=1)
    sat_mean = float(np.mean(max_rgb - min_rgb))

    return BackgroundStats(
        median_color=(median_r, median_g, median_b),
        gray_mean=gray_mean,
        gray_std=gray_std,
        near_white_ratio=near_white_ratio,
        dark_ratio=dark_ratio,
        edge_density=edge_density,
        saturation_mean=sat_mean,
        sample_count=total_check,
    )


def detect_screentone(gray_ring_1d: np.ndarray) -> bool:
    """Check for repeating dot pattern via 1-D autocorrelation.

    Conservative — returns False on tiny/degenerate input.
    """
    if len(gray_ring_1d) < 64:
        return False
    # Deterministic subsample for speed (seeded → reproducible; portable to
    # Kotlin as a fixed-seed PRNG). The tier classifier has its own independent
    # screentone guard, so this is only a secondary signal.
    if len(gray_ring_1d) > 2000:
        rng = np.random.default_rng(0)
        idx = rng.choice(len(gray_ring_1d), 2000, replace=False)
        signal = gray_ring_1d[idx]
    else:
        signal = gray_ring_1d
    sig = signal - np.mean(signal)
    # Compute autocorrelation via FFT
    n = len(sig)
    fft = np.fft.rfft(sig, n=2 * n)
    acf = np.fft.irfft(fft * np.conj(fft), n=2 * n)[:n]
    # Guard degenerate input: a flat signal has acf[0] == 0 → avoid divide-by-zero
    # (would emit a RuntimeWarning and propagate NaNs). Conservative → False.
    if not np.isfinite(acf[0]) or acf[0] == 0:
        return False
    acf = acf / acf[0]  # normalize by lag-0
    # Look for secondary peak in lags 3..n//2
    lags = acf[3: n // 2]
    if len(lags) < 3:
        return False
    mean_acf = np.mean(lags)
    max_acf = np.max(lags)
    # A clear secondary peak > 0.3 above the mean indicates periodicity
    if max_acf - mean_acf > 0.3 and max_acf > 0.3:
        return True
    return False


def classify_tier(stats: BackgroundStats, is_screentone: bool) -> str:
    """Port of ``BoundaryAwarePipeline.classifyTier``.

    Returns one of ``Tier.*`` values.
    """
    flat_white = (
        stats.near_white_ratio > 0.7 and stats.gray_std < 20.0
    )
    flat_colored = (
        stats.gray_std < 15.0 and stats.near_white_ratio <= 0.7
    )
    dark_flat = stats.dark_ratio > 0.5 and stats.gray_std < 18.0

    if flat_white or flat_colored or dark_flat:
        return Tier.FLAT

    screentone_pattern = (
        14.0 <= stats.gray_std <= 50.0
        and stats.saturation_mean < 20.0
        and stats.edge_density < 0.20
    )
    saturated_color = stats.saturation_mean > 35.0

    if saturated_color:
        return Tier.COLOR
    if is_screentone or screentone_pattern:
        return Tier.TEXTURED
    if stats.gray_std < 22.0 and stats.edge_density < 0.08:
        return Tier.FLAT
    if stats.gray_std < 45.0:
        return Tier.TEXTURED
    if stats.edge_density < 0.12:
        return Tier.FLAT
    return Tier.TEXTURED


# ---------------------------------------------------------------------------
# Reconstruction
# ---------------------------------------------------------------------------

def _build_directional_background(
    rgb: np.ndarray, mask: np.ndarray, bg_source_mask: np.ndarray | None = None,
) -> tuple[np.ndarray, np.ndarray]:
    """Port of ``SmartBubbleTextCleaner.buildDirectionalBackground``.

    Returns (color_HxWx3_uint8, valid_HxW_bool).
    """
    h, w = rgb.shape[:2]
    left_color = np.zeros((h, w, 3), dtype=np.float64)
    left_dist = np.full((h, w), np.iinfo(np.int32).max, dtype=np.int32)
    right_color = np.zeros_like(left_color)
    right_dist = np.full((h, w), np.iinfo(np.int32).max, dtype=np.int32)
    top_color = np.zeros_like(left_color)
    top_dist = np.full((h, w), np.iinfo(np.int32).max, dtype=np.int32)
    bottom_color = np.zeros_like(left_color)
    bottom_dist = np.full((h, w), np.iinfo(np.int32).max, dtype=np.int32)

    def is_anchor(y, x):
        if mask[y, x]:
            return False
        if bg_source_mask is not None and not bg_source_mask[y, x]:
            return False
        return True

    for y in range(h):
        last_color = np.array([0, 0, 0], dtype=np.float64)
        last_x = -1
        for x in range(w):
            if is_anchor(y, x):
                last_color = rgb[y, x].astype(np.float64)
                last_x = x
            elif last_x >= 0:
                left_color[y, x] = last_color
                left_dist[y, x] = x - last_x
        last_color = np.array([0, 0, 0], dtype=np.float64)
        last_x = -1
        for x in range(w - 1, -1, -1):
            if is_anchor(y, x):
                last_color = rgb[y, x].astype(np.float64)
                last_x = x
            elif last_x >= 0:
                right_color[y, x] = last_color
                right_dist[y, x] = last_x - x

    for x in range(w):
        last_color = np.array([0, 0, 0], dtype=np.float64)
        last_y = -1
        for y in range(h):
            if is_anchor(y, x):
                last_color = rgb[y, x].astype(np.float64)
                last_y = y
            elif last_y >= 0:
                top_color[y, x] = last_color
                top_dist[y, x] = y - last_y
        last_color = np.array([0, 0, 0], dtype=np.float64)
        last_y = -1
        for y in range(h - 1, -1, -1):
            if is_anchor(y, x):
                last_color = rgb[y, x].astype(np.float64)
                last_y = y
            elif last_y >= 0:
                bottom_color[y, x] = last_color
                bottom_dist[y, x] = last_y - y

    # Combine
    out = np.zeros((h, w, 3), dtype=np.float64)
    weights = np.zeros((h, w), dtype=np.float64)

    def add_component(color_arr, dist_arr, out, weights):
        valid = dist_arr < np.iinfo(np.int32).max
        w_arr = np.where(valid, 1.0 / np.maximum(1, dist_arr).astype(np.float64), 0.0)
        out[:, :, :] += color_arr * w_arr[..., None]
        weights[:, :] += w_arr

    add_component(left_color, left_dist, out, weights)
    add_component(right_color, right_dist, out, weights)
    add_component(top_color, top_dist, out, weights)
    add_component(bottom_color, bottom_dist, out, weights)

    result = np.zeros((h, w, 3), dtype=np.uint8)
    valid_px = weights > 1e-3
    safe_weights = np.where(valid_px, weights, 1.0)
    for c in range(3):
        channel = np.where(valid_px, out[..., c] / safe_weights, 0.0)
        channel = channel.clip(0, 255).round().astype(np.uint8)
        result[..., c] = channel
    return result, valid_px


def local_background_fill(
    crop_rgb: np.ndarray, mask: np.ndarray,
    bg_source_mask: np.ndarray | None = None,
    feather_radius: int = 6,
) -> np.ndarray:
    """Per-pixel Gaussian-weighted background interpolation.

    Port of ``SmartBubbleTextCleaner.buildLocalBackground`` +
    ``buildDirectionalBackground``.
    """
    binary_mask = (mask > 0)
    h, w = crop_rgb.shape[:2]
    result = crop_rgb.copy()

    if not np.any(binary_mask):
        return result

    # Compute median color of background pixels for fallback
    bg_pixels = crop_rgb[~binary_mask]
    if len(bg_pixels) > 0:
        median_r = float(np.median(bg_pixels[:, 0]))
        median_g = float(np.median(bg_pixels[:, 1]))
        median_b = float(np.median(bg_pixels[:, 2]))
    else:
        median_r = median_g = median_b = 255.0

    # Precompute directional background (used when no bg pixels are in range)
    directional, directional_valid = _build_directional_background(
        crop_rgb, binary_mask, bg_source_mask,
    )

    r = max(2, feather_radius)
    # Gaussian kernel
    sigma = r / 2.0
    kernel_1d = np.exp(-0.5 * (np.arange(-r, r + 1) ** 2) / (sigma * sigma))

    # For each masked pixel, do Gaussian-weighted average of nearby bg pixels
    mask_ys, mask_xs = np.where(binary_mask)
    for idx in range(len(mask_ys)):
        y, x = mask_ys[idx], mask_xs[idx]
        sum_r = 0.0
        sum_g = 0.0
        sum_b = 0.0
        w_sum = 0.0

        for ky in range(-r, r + 1):
            ny = y + ky
            if ny < 0 or ny >= h:
                continue
            wy = kernel_1d[ky + r]
            for kx in range(-r, r + 1):
                nx = x + kx
                if nx < 0 or nx >= w:
                    continue
                if binary_mask[ny, nx]:
                    continue
                if bg_source_mask is not None and not bg_source_mask[ny, nx]:
                    continue
                wk = wy * kernel_1d[kx + r]
                px = crop_rgb[ny, nx]
                sum_r += px[0] * wk
                sum_g += px[1] * wk
                sum_b += px[2] * wk
                w_sum += wk

        if w_sum > 1e-3:
            result[y, x] = (
                max(0, min(255, round(sum_r / w_sum))),
                max(0, min(255, round(sum_g / w_sum))),
                max(0, min(255, round(sum_b / w_sum))),
            )
        else:
            # Fall back to directional estimate
            if directional_valid[y, x]:
                result[y, x] = directional[y, x]
            else:
                result[y, x] = (round(median_r), round(median_g), round(median_b))

    return result


def telea_inpaint(
    crop_rgb: np.ndarray, mask: np.ndarray, radius: int = 3,
) -> np.ndarray:
    """Fast Marching Method inpainting (Telea 2004).

    Port of ``FastMarchingMethod.inpaintTelea``.
    Only modifies masked pixels. Returns a copy.
    """
    binary_mask = (mask > 0)
    h, w = crop_rgb.shape[:2]
    result = crop_rgb.astype(np.float64).copy()

    if not np.any(binary_mask):
        return result.astype(np.uint8)

    KNOWN, BAND, INSIDE = 0, 1, 2

    flag = np.full((h, w), KNOWN, dtype=np.int32)
    dist = np.full((h, w), 1e6, dtype=np.float64)

    # Initialize flags
    flag[binary_mask] = INSIDE
    dist[~binary_mask] = 0.0

    # Initialize narrow band (INSIDE pixels adjacent to KNOWN)
    dy = np.array([-1, 1, 0, 0])
    dx = np.array([0, 0, -1, 1])
    pq = []

    for y in range(h):
        for x in range(w):
            if flag[y, x] == INSIDE:
                is_band = False
                for k in range(4):
                    ny = y + dy[k]
                    nx = x + dx[k]
                    if 0 <= ny < h and 0 <= nx < w and flag[ny, nx] == KNOWN:
                        is_band = True
                        break
                if is_band:
                    flag[y, x] = BAND
                    dist[y, x] = 1.0
                    heapq.heappush(pq, (1.0, y, x))

    r2 = radius * radius

    while pq:
        d, y, x = heapq.heappop(pq)
        if flag[y, x] == KNOWN:
            continue

        flag[y, x] = KNOWN

        # Inpaint this pixel: weighted average of nearby KNOWN pixels
        sum_r = 0.0
        sum_g = 0.0
        sum_b = 0.0
        sum_w = 0.0

        for dyk in range(-radius, radius + 1):
            ny = y + dyk
            if ny < 0 or ny >= h:
                continue
            for dxk in range(-radius, radius + 1):
                if dxk * dxk + dyk * dyk > r2:
                    continue
                nx = x + dxk
                if nx < 0 or nx >= w:
                    continue
                if flag[ny, nx] != KNOWN:
                    continue
                dist_sq = float(dxk * dxk + dyk * dyk)
                if dist_sq > 0:
                    wgt = 1.0 / dist_sq
                    sum_r += result[ny, nx, 0] * wgt
                    sum_g += result[ny, nx, 1] * wgt
                    sum_b += result[ny, nx, 2] * wgt
                    sum_w += wgt

        if sum_w > 0:
            result[y, x, 0] = sum_r / sum_w
            result[y, x, 1] = sum_g / sum_w
            result[y, x, 2] = sum_b / sum_w

        # Propagate distance to neighbors
        for k in range(4):
            nx = x + dx[k]
            ny = y + dy[k]
            if nx < 0 or nx >= w or ny < 0 or ny >= h:
                continue
            if flag[ny, nx] == KNOWN:
                continue

            dist1 = 1e6
            dist2 = 1e6

            if nx - 1 >= 0 and flag[ny, nx - 1] == KNOWN:
                dist1 = min(dist1, dist[ny, nx - 1])
            if nx + 1 < w and flag[ny, nx + 1] == KNOWN:
                dist1 = min(dist1, dist[ny, nx + 1])

            if ny - 1 >= 0 and flag[ny - 1, nx] == KNOWN:
                dist2 = min(dist2, dist[ny - 1, nx])
            if ny + 1 < h and flag[ny + 1, nx] == KNOWN:
                dist2 = min(dist2, dist[ny + 1, nx])

            if dist1 < 1e5 and dist2 < 1e5:
                diff = abs(dist1 - dist2)
                if diff < 1.0:
                    new_d = (dist1 + dist2 + math.sqrt(2.0 - diff * diff)) / 2.0
                else:
                    new_d = min(dist1, dist2) + 1.0
            else:
                new_d = min(dist1, dist2) + 1.0

            if new_d < dist[ny, nx]:
                dist[ny, nx] = new_d
                flag[ny, nx] = BAND
                heapq.heappush(pq, (new_d, ny, nx))

    result = result.clip(0, 255).round().astype(np.uint8)
    return result


def poisson_seamless_blend(
    dst: np.ndarray, src: np.ndarray, mask: np.ndarray,
    iterations: int = 300, mixing: bool = False, tol: float = 1e-3,
) -> np.ndarray:
    """Pure-NumPy Poisson seamless clone (Pérez et al. 2003).

    Ω = mask > 0 (interior).  Dirichlet boundary = dst.
    mixing=False (default) imports the src gradients so the reconstructed hole
    follows the fill (text erased). mixing=True keeps the larger of src/dst
    gradients per pixel, which PRESERVES destination text strokes — only use it
    when you intend to keep destination texture.
    Raises ``PoissonBorderError`` if mask touches crop border or is empty.
    """
    binary = (mask > 0)
    h, w = dst.shape[:2]

    if not np.any(binary):
        raise PoissonBorderError("Empty mask for Poisson blend")

    # Check mask touches border
    if np.any(binary[0, :]) or np.any(binary[-1, :]) or \
       np.any(binary[:, 0]) or np.any(binary[:, -1]):
        raise PoissonBorderError("Mask touches crop border")

    dst_f = dst.astype(np.float64)
    src_f = src.astype(np.float64)
    f = dst_f.copy()

    # Precompute guidance divergence per channel
    divG = np.zeros((h, w, 3), dtype=np.float64)

    # 2-D padded shifts for per-channel arrays
    def _sl2(arr):
        return np.pad(arr[:, :-1], ((0, 0), (1, 0)), mode='edge')

    def _sr2(arr):
        return np.pad(arr[:, 1:], ((0, 0), (0, 1)), mode='edge')

    def _su2(arr):
        return np.pad(arr[:-1, :], ((1, 0), (0, 0)), mode='edge')

    def _sd2(arr):
        return np.pad(arr[1:, :], ((0, 1), (0, 0)), mode='edge')

    # 3-D padded shifts for the full HxWx3 array
    def _sl3(arr):
        return np.pad(arr[:, :-1, :], ((0, 0), (1, 0), (0, 0)), mode='edge')

    def _sr3(arr):
        return np.pad(arr[:, 1:, :], ((0, 0), (0, 1), (0, 0)), mode='edge')

    def _su3(arr):
        return np.pad(arr[:-1, :, :], ((1, 0), (0, 0), (0, 0)), mode='edge')

    def _sd3(arr):
        return np.pad(arr[1:, :, :], ((0, 1), (0, 0), (0, 0)), mode='edge')

    for c in range(3):
        sc = src_f[:, :, c]
        dc = dst_f[:, :, c]

        if mixing:
            s_gx = _sr2(sc) - sc
            d_gx = _sr2(dc) - dc
            use_src_gx = np.abs(s_gx) >= np.abs(d_gx)
            gx = np.where(use_src_gx, s_gx, d_gx)

            s_gy = _sd2(sc) - sc
            d_gy = _sd2(dc) - dc
            use_src_gy = np.abs(s_gy) >= np.abs(d_gy)
            gy = np.where(use_src_gy, s_gy, d_gy)

            divGx = gx - _sl2(gx)
            divGy = gy - _su2(gy)
            divG[:, :, c] = divGx + divGy
        else:
            sl = _sl2(sc)
            sr = _sr2(sc)
            su = _su2(sc)
            sd = _sd2(sc)
            divG[:, :, c] = sl + sr + su + sd - 4.0 * sc

    omega = binary
    not_omega = ~omega

    for it in range(iterations):
        fl = _sl3(f)
        fr = _sr3(f)
        fu = _su3(f)
        fd = _sd3(f)

        f_new = (fl + fr + fu + fd - divG) / 4.0

        # Track max delta inside omega for convergence check
        if tol > 0:
            delta = np.abs(f_new[omega] - f[omega])
            max_delta = np.max(delta)
            f[omega] = f_new[omega]
            if max_delta < tol:
                break
        else:
            f[omega] = f_new[omega]

    # Ensure boundary pixels stay at dst values
    f[not_omega] = dst_f[not_omega]

    result = f.clip(0, 255).round().astype(np.uint8)
    return result


def feather_blend_fallback(
    dst: np.ndarray, src: np.ndarray, mask: np.ndarray, ramp: int,
) -> np.ndarray:
    """Alpha-blend with distance-field feathering.

    alpha = feather_alpha_field(mask, ramp).
    Returns uint8 (HxWx3).
    """
    alpha = feather_alpha_field(mask, ramp)
    alpha_3 = alpha[:, :, None]
    blended = (1.0 - alpha_3) * dst.astype(np.float64) + alpha_3 * src.astype(np.float64)
    return blended.clip(0, 255).round().astype(np.uint8)


# ---------------------------------------------------------------------------
# Guard
# ---------------------------------------------------------------------------

def is_uniform_output(rgb: np.ndarray, mask: np.ndarray) -> bool:
    """Port of ``AotOutputGuard.isSuspiciousGrayFill``.

    Returns True if the masked region is uniform and its mean luma
    falls in the suspicious range (mid-gray or near-white).
    """
    binary = (mask > 127)
    selected = rgb[binary]
    count = len(selected)
    if count < 16:
        return False

    r = selected[:, 0].astype(np.float64)
    g = selected[:, 1].astype(np.float64)
    b = selected[:, 2].astype(np.float64)

    luma = 0.299 * r + 0.587 * g + 0.114 * b
    mean_luma = float(np.mean(luma))
    variance = float(np.var(luma))

    rg_delta = np.abs(r - g)
    gb_delta = np.abs(g - b)
    channel_delta = float(np.mean(rg_delta + gb_delta))

    uniform = variance < 36.0 and channel_delta < 8.0
    if not uniform:
        return False

    uniform_mid_gray = 96.0 <= mean_luma <= 160.0
    uniform_near_white = mean_luma >= 238.0

    return uniform_mid_gray or uniform_near_white


# ---------------------------------------------------------------------------
# Orchestration
# ---------------------------------------------------------------------------

def cluster_to_crop_bounds(boxes_page, page_w, page_h, margin=None):
    """Union bounding box of boxes, padded by margin, clamped to page."""
    if not boxes_page:
        return [0, 0, 0, 0]
    xs = [b[0] for b in boxes_page] + [b[2] for b in boxes_page]
    ys = [b[1] for b in boxes_page] + [b[3] for b in boxes_page]
    ux1, ux2 = min(xs), max(xs)
    uy1, uy2 = min(ys), max(ys)
    long_side = max(ux2 - ux1, uy2 - uy1)
    if margin is None:
        margin = max(40, min(160, long_side))
    x1 = max(0, ux1 - margin)
    y1 = max(0, uy1 - margin)
    x2 = min(page_w, ux2 + margin)
    y2 = min(page_h, uy2 + margin)
    return [x1, y1, x2, y2]


def inpaint_coherent(
    rgb: np.ndarray,
    clusters: list,
    opts: CCIOptions | None = None,
    aot_fn: Callable | None = None,
    quality: bool = False,
):
    """Orchestrate coherent inpainting across clusters.

    *rgb*: page RGB uint8 (HxWx3).
    *clusters*: list of dicts, each with keys:
        ``boxes`` (page-coord [x1,y1,x2,y2] lists),
        ``is_free`` (list of bool),
        ``parent_rect`` (page-coords or None, optional).

    *aot_fn*: ``aot_fn(crop_rgb, mask_uint8) -> crop_rgb_uint8`` for quality path.
    Returns ``(result_rgb_uint8, diagnostics_list, union_mask_uint8)`` where
    ``union_mask`` is the page-sized union of the actual per-cluster erase masks
    (so callers can display a mask layer that matches the inpaint exactly).
    """
    if opts is None:
        opts = CCIOptions()
    result = rgb.copy()
    H, W = rgb.shape[:2]
    diagnostics = []
    union_mask = np.zeros((H, W), dtype=np.uint8)

    for cluster in clusters:
        boxes_page = cluster["boxes"]
        is_free = cluster.get("is_free", [False] * len(boxes_page))
        parent_rect = cluster.get("parent_rect", None)

        if not boxes_page:
            continue

        diag = {
            "tier": None,
            "method": None,
            "uniform_rejected": False,
            "blend": None,
            "mask_px": 0,
            "crop_size": None,
        }

        # 1. Expand tiny free boxes in page coords
        expanded = expand_tiny_free_boxes(
            boxes_page, is_free, W, H, opts, parent_rect,
        )

        # 2. Compute crop bounds, extract crop
        crop_bounds = cluster_to_crop_bounds(boxes_page, W, H)
        cx1, cy1, cx2, cy2 = crop_bounds
        if cx2 <= cx1 or cy2 <= cy1:
            continue

        crop = rgb[cy1:cy2, cx1:cx2].copy()
        ch, cw = crop.shape[:2]
        diag["crop_size"] = (cw, ch)

        # Localize boxes
        boxes_local = []
        for b in expanded:
            lx1 = max(0, b[0] - cx1)
            ly1 = max(0, b[1] - cy1)
            lx2 = min(cw, b[2] - cx1)
            ly2 = min(ch, b[3] - cy1)
            if lx2 > lx1 and ly2 > ly1:
                boxes_local.append([lx1, ly1, lx2, ly2])

        if not boxes_local:
            continue

        # Localize parent_rect
        parent_local = None
        if parent_rect is not None:
            px1 = max(0, parent_rect[0] - cx1)
            py1 = max(0, parent_rect[1] - cy1)
            px2 = min(cw, parent_rect[2] - cx1)
            py2 = min(ch, parent_rect[3] - cy1)
            if px2 > px1 and py2 > py1:
                parent_local = [px1, py1, px2, py2]

        # 3. Build cluster mask
        cluster_mask = build_cluster_mask(crop, boxes_local, opts)
        diag["mask_px"] = int(np.sum(cluster_mask > 0))

        if not np.any(cluster_mask):
            continue

        # Accumulate the actual erase mask (page coords) so the caller can show
        # a mask layer that matches the inpaint exactly (no bbox/mask/inpaint
        # mismatch).
        union_mask[cy1:cy2, cx1:cx2] = np.maximum(
            union_mask[cy1:cy2, cx1:cx2], cluster_mask,
        )

        # 4. Sample background + classify
        stats = sample_background(crop, cluster_mask, parent_local)
        # Gather ring grayscale for screentone check
        ring_mask = (cluster_mask <= 0)
        if parent_local is not None:
            parent_mask = np.zeros((ch, cw), dtype=bool)
            pm = parent_local
            parent_mask[pm[1]:pm[3], pm[0]:pm[2]] = True
            ring_mask = ring_mask & parent_mask
        ring_pixels = crop[ring_mask]
        is_st = detect_screentone(ring_pixels[:, 0].astype(np.float64)) if len(ring_pixels) > 0 else False
        tier = classify_tier(stats, is_st)
        diag["tier"] = tier

        # Build bg_source_mask for parent-constrained fill
        bg_source = None
        if parent_local is not None:
            bg_source = np.zeros((ch, cw), dtype=bool)
            pm = parent_local
            # Erode parent rect by max(2, minDim/10)
            bw = pm[2] - pm[0]
            bh = pm[3] - pm[1]
            erode = max(2, min(bw, bh) // 10)
            ex1 = max(0, pm[0] + erode)
            ey1 = max(0, pm[1] + erode)
            ex2 = min(cw, pm[2] - erode)
            ey2 = min(ch, pm[3] - erode)
            if ex2 > ex1 and ey2 > ey1:
                bg_source[ey1:ey2, ex1:ex2] = True

        # 5. Reconstruction
        fill = None
        uniform_rejected = False
        blend_method = "feather"

        if tier == Tier.FLAT:
            # FLAT: local background fill + feather blend
            fill = local_background_fill(crop, cluster_mask, bg_source, opts.feather_ramp)
            blend_method = "feather"
            diag["method"] = "flat"
        else:
            # TEXTURED or COLOR
            if quality and aot_fn is not None:
                try:
                    fill = aot_fn(crop, cluster_mask)
                    diag["method"] = "aot"
                    if is_uniform_output(fill, cluster_mask):
                        uniform_rejected = True
                        fill = telea_inpaint(crop, cluster_mask, opts.telea_radius)
                        diag["method"] = "telea"
                except Exception:
                    fill = telea_inpaint(crop, cluster_mask, opts.telea_radius)
                    diag["method"] = "telea"
            else:
                fill = telea_inpaint(crop, cluster_mask, opts.telea_radius)
                diag["method"] = "telea"
                if is_uniform_output(fill, cluster_mask):
                    uniform_rejected = True
                    fill = local_background_fill(crop, cluster_mask, bg_source, opts.feather_ramp)
                    diag["method"] = "flat_fallback"

            # Blend: try Poisson, fall back to feather
            try:
                blended = poisson_seamless_blend(
                    crop, fill, cluster_mask,
                    opts.poisson_iters, opts.poisson_mixing, opts.poisson_tol,
                )
                blend_method = "poisson"
            except PoissonBorderError:
                blended = feather_blend_fallback(crop, fill, cluster_mask, opts.feather_ramp)
                blend_method = "feather"

            diag["tier"] = tier

        # Apply blended result
        if tier == Tier.FLAT:
            blended = feather_blend_fallback(crop, fill, cluster_mask, opts.feather_ramp)

        diag["uniform_rejected"] = uniform_rejected
        diag["blend"] = blend_method

        result[cy1:cy2, cx1:cx2] = blended

        diagnostics.append(diag)

    return result, diagnostics, union_mask
