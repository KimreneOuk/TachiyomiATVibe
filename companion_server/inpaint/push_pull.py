"""Push-pull gradient fill for free-text inpainting.

Port of PushPullGradient.kt — reconstructs a smooth background from the
surrounding pixels via box-downscale → bilinear-upscale → boundary diffusion.
"""
from __future__ import annotations

import numpy as np

DEFAULT_RING = 8
_DOWN_SAMPLE_DIV = 20
_DIFFUSION_PASSES = 15


def local_ring_median(
    pixels: np.ndarray,
    mask: np.ndarray,
    ring: int = DEFAULT_RING,
) -> int:
    """Median ARGB of the local annulus around the mask bbox.

    Args:
        pixels: HxWx3 uint8 array
        mask: HxW uint8 array (1=text, 0=background)

    Returns packed ARGB int of the ring median color.
    """
    h, w = mask.shape
    ys, xs = np.where(mask != 0)
    if len(ys) == 0:
        # Global median
        return _median_color(pixels.reshape(-1, 3))

    y1, y2 = int(ys.min()), int(ys.max())
    x1, x2 = int(xs.min()), int(xs.max())

    ry1 = max(0, y1 - ring)
    ry2 = min(h, y2 + ring + 1)
    rx1 = max(0, x1 - ring)
    rx2 = min(w, x2 + ring + 1)

    # Annulus = ring rectangle minus the mask bbox interior
    ring_mask = np.zeros((h, w), dtype=bool)
    ring_mask[ry1:ry2, rx1:rx2] = True
    ring_mask[y1:y2 + 1, x1:x2 + 1] = False

    ring_pixels = pixels[ring_mask]
    if len(ring_pixels) == 0:
        return _median_color(pixels.reshape(-1, 3))
    return _median_color(ring_pixels)


def _median_color(pixels: np.ndarray) -> int:
    """Compute median RGB from an Nx3 uint8 array, return packed ARGB."""
    if len(pixels) == 0:
        return 0xFFFFFFFF
    mr = int(np.median(pixels[:, 0]))
    mg = int(np.median(pixels[:, 1]))
    mb = int(np.median(pixels[:, 2]))
    return (0xFF << 24) | (mr << 16) | (mg << 8) | mb


def push_pull_fill(
    pixels: np.ndarray,
    mask: np.ndarray,
    bg_color: int,
) -> np.ndarray:
    """Push-pull gradient fill of masked pixels.

    Args:
        pixels: HxWx3 uint8 array (will NOT be modified)
        mask: HxW uint8 array (1=fill)
        bg_color: packed ARGB background color

    Returns a new HxWx3 uint8 array with masked pixels filled.
    """
    h, w = pixels.shape[:2]
    n = h * w
    if n == 0:
        return pixels.copy()

    work = pixels.copy()
    bg_r = (bg_color >> 16) & 0xFF
    bg_g = (bg_color >> 8) & 0xFF
    bg_b = bg_color & 0xFF

    # a. Erase ink with local bg
    mask_bool = mask != 0
    work[mask_bool] = [bg_r, bg_g, bg_b]

    # b. Box-average downscale
    small_w = max(2, w // _DOWN_SAMPLE_DIV)
    small_h = max(2, h // _DOWN_SAMPLE_DIV)
    small = _downsample_box_avg(work, small_w, small_h)

    # c. Bilinear upscale
    gradient = _upsample_bilinear(small, small_w, small_h, w, h)

    # d. Paint gradient into hole
    work[mask_bool] = gradient[mask_bool]

    # e. Boundary diffusion inside mask
    for _ in range(_DIFFUSION_PASSES):
        scratch = work.copy()
        # 4-neighbour average for masked pixels
        up = np.roll(work, 1, axis=0)
        down = np.roll(work, -1, axis=0)
        left = np.roll(work, 1, axis=1)
        right = np.roll(work, -1, axis=1)
        up[0] = work[0]
        down[-1] = work[-1]
        left[:, 0] = work[:, 0]
        right[:, -1] = work[:, -1]
        avg = (up.astype(np.int32) + down.astype(np.int32) +
               left.astype(np.int32) + right.astype(np.int32) + 2) >> 2
        scratch[mask_bool] = np.clip(avg[mask_bool], 0, 255).astype(np.uint8)
        work = scratch

    return work


def _downsample_box_avg(pixels: np.ndarray, small_w: int, small_h: int) -> np.ndarray:
    """Area-average downscale to smallW x smallH."""
    h, w = pixels.shape[:2]
    flat = pixels.reshape(-1, 3).astype(np.int64)
    sy = (np.arange(h) * small_h) // h
    sx = (np.arange(w) * small_w) // w
    si = np.outer(sy * small_w, np.ones(w, dtype=np.int64)) + np.outer(np.ones(h, dtype=np.int64), sx)
    si_flat = si.ravel()

    sum_r = np.zeros(small_w * small_h, dtype=np.int64)
    sum_g = np.zeros(small_w * small_h, dtype=np.int64)
    sum_b = np.zeros(small_w * small_h, dtype=np.int64)
    cnt = np.zeros(small_w * small_h, dtype=np.int64)

    np.add.at(sum_r, si_flat, flat[:, 0])
    np.add.at(sum_g, si_flat, flat[:, 1])
    np.add.at(sum_b, si_flat, flat[:, 2])
    np.add.at(cnt, si_flat, 1)

    cnt_safe = np.maximum(cnt, 1)
    out = np.zeros((small_h, small_w, 3), dtype=np.uint8)
    out_r = ((sum_r + cnt_safe // 2) // cnt_safe).reshape(small_h, small_w)
    out_g = ((sum_g + cnt_safe // 2) // cnt_safe).reshape(small_h, small_w)
    out_b = ((sum_b + cnt_safe // 2) // cnt_safe).reshape(small_h, small_w)
    out[..., 0] = np.clip(out_r, 0, 255)
    out[..., 1] = np.clip(out_g, 0, 255)
    out[..., 2] = np.clip(out_b, 0, 255)
    return out


def _upsample_bilinear(small: np.ndarray, sw: int, sh: int, w: int, h: int) -> np.ndarray:
    """Bilinear upsample from sw x sh to w x h."""
    out = np.zeros((h, w, 3), dtype=np.uint8)
    for c in range(3):
        ch = small[:, :, c].astype(np.float32)
        for y in range(h):
            fy = (y + 0.5) * sh / h - 0.5
            y0 = max(0, min(sh - 1, int(np.floor(fy))))
            ty = np.clip(fy - int(np.floor(fy)), 0.0, 1.0)
            y1 = min(sh - 1, y0 + 1)
            for x in range(w):
                fx = (x + 0.5) * sw / w - 0.5
                x0 = max(0, min(sw - 1, int(np.floor(fx))))
                tx = np.clip(fx - int(np.floor(fx)), 0.0, 1.0)
                x1 = min(sw - 1, x0 + 1)
                v = (ch[y0, x0] * (1 - tx) * (1 - ty) +
                     ch[y0, x1] * tx * (1 - ty) +
                     ch[y1, x0] * (1 - tx) * ty +
                     ch[y1, x1] * tx * ty)
                out[y, x, c] = max(0, min(255, int(v)))
    return out
