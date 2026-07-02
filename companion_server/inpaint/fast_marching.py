"""Fast Marching Method (Telea 2004) inpainting.

Port of FastMarchingMethod.kt — reconstructs masked pixels from known
neighbours in fast-marching arrival order. Also provides adaptive thresholding
and morphological operations.
"""
from __future__ import annotations

import heapq
import math

import numpy as np


def adaptive_threshold_gaussian(
    pixels: np.ndarray,
    block_size: int = 15,
    c: int = 10,
) -> np.ndarray:
    """Adaptive Gaussian thresholding (THRESH_BINARY_INV).

    Args:
        pixels: HxWx3 uint8
        block_size: odd window size
        c: constant subtracted from mean

    Returns HxW uint8 mask (1 = pixel below threshold).
    """
    h, w = pixels.shape[:2]
    gray = (0.299 * pixels[:, :, 0] + 0.587 * pixels[:, :, 1] + 0.114 * pixels[:, :, 2]).astype(np.float32)

    half = block_size // 2
    sigma = 0.3 * ((block_size - 1) * 0.5 - 1.0) + 0.8
    kernel_1d = np.exp(-((np.arange(block_size) - half) ** 2) / (2 * sigma * sigma)).astype(np.float32)
    kernel_1d /= kernel_1d.sum()

    # Separable Gaussian blur with edge clamping
    padded = np.pad(gray, ((0, 0), (half, half)), mode='edge')
    blurred_h = np.zeros_like(gray)
    for i in range(block_size):
        blurred_h += padded[:, i:i + w] * kernel_1d[i]

    padded_h = np.pad(blurred_h, ((half, half), (0, 0)), mode='edge')
    blurred = np.zeros_like(gray)
    for i in range(block_size):
        blurred += padded_h[i:i + h, :] * kernel_1d[i]

    threshold = blurred - c
    return (gray < threshold).astype(np.uint8)


def dilate(mask: np.ndarray, radius: int) -> np.ndarray:
    """Circular dilation."""
    if radius <= 0:
        return mask.copy()
    h, w = mask.shape
    out = np.zeros_like(mask)
    for dy in range(-radius, radius + 1):
        for dx in range(-radius, radius + 1):
            if dx * dx + dy * dy <= radius * radius:
                shifted = np.zeros_like(mask)
                ys1 = max(0, dy)
                ys2 = min(h, h + dy)
                xs1 = max(0, dx)
                xs2 = min(w, w + dx)
                shifted[ys1:ys2, xs1:xs2] = mask[max(0, -dy):min(h, h - dy), max(0, -dx):min(w, w - dx)]
                out |= shifted
    return out


def erode(mask: np.ndarray, radius: int) -> np.ndarray:
    """Circular erosion."""
    if radius <= 0:
        return mask.copy()
    h, w = mask.shape
    out = np.ones_like(mask)
    for dy in range(-radius, radius + 1):
        for dx in range(-radius, radius + 1):
            if dx * dx + dy * dy <= radius * radius:
                shifted = np.ones_like(mask)
                ys1 = max(0, dy)
                ys2 = min(h, h + dy)
                xs1 = max(0, dx)
                xs2 = min(w, w + dx)
                shifted[ys1:ys2, xs1:xs2] = mask[max(0, -dy):min(h, h - dy), max(0, -dx):min(w, w - dx)]
                out &= shifted
    return out


def morphology_close(mask: np.ndarray, radius: int) -> np.ndarray:
    """Dilate then erode."""
    return erode(dilate(mask, radius), radius)


def inpaint_telea(
    pixels: np.ndarray,
    mask: np.ndarray,
    radius: int = 3,
) -> np.ndarray:
    """Telea Fast Marching Method inpainting.

    Args:
        pixels: HxWx3 uint8 (will NOT be modified)
        mask: HxW uint8 (1=hole to fill)

    Returns new HxWx3 uint8 with holes filled.
    """
    h, w = mask.shape
    result = pixels.copy().astype(np.float64)

    KNOWN = 0
    BAND = 1
    INSIDE = 2

    flag = np.where(mask != 0, INSIDE, KNOWN).astype(np.uint8)
    dist = np.where(mask != 0, 1e6, 0.0).astype(np.float64)

    pq: list[tuple[float, int, int]] = []
    dx4 = [-1, 1, 0, 0]
    dy4 = [0, 0, -1, 1]

    # Initialize narrow band
    for y in range(h):
        for x in range(w):
            if flag[y, x] == INSIDE:
                is_band = False
                for d in range(4):
                    nx, ny = x + dx4[d], y + dy4[d]
                    if 0 <= nx < w and 0 <= ny < h and flag[ny, nx] == KNOWN:
                        is_band = True
                        break
                if is_band:
                    flag[y, x] = BAND
                    dist[y, x] = 1.0
                    heapq.heappush(pq, (1.0, x, y))

    while pq:
        d_val, x, y = heapq.heappop(pq)
        if flag[y, x] == KNOWN:
            continue
        flag[y, x] = KNOWN

        # Inpaint pixel from known neighbours
        sum_r = sum_g = sum_b = sum_w = 0.0
        r2 = radius * radius
        for dyk in range(-radius, radius + 1):
            for dxk in range(-radius, radius + 1):
                if dxk * dxk + dyk * dyk <= r2:
                    nx, ny = x + dxk, y + dyk
                    if 0 <= nx < w and 0 <= ny < h and flag[ny, nx] == KNOWN:
                        dist_sq = float(dxk * dxk + dyk * dyk)
                        if dist_sq > 0:
                            wt = 1.0 / dist_sq
                            sum_r += result[ny, nx, 0] * wt
                            sum_g += result[ny, nx, 1] * wt
                            sum_b += result[ny, nx, 2] * wt
                            sum_w += wt
        if sum_w > 0:
            result[y, x, 0] = sum_r / sum_w
            result[y, x, 1] = sum_g / sum_w
            result[y, x, 2] = sum_b / sum_w

        # Propagate to neighbours
        for d in range(4):
            nx, ny = x + dx4[d], y + dy4[d]
            if 0 <= nx < w and 0 <= ny < h and flag[ny, nx] != KNOWN:
                dist1 = dist2 = 1e6
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
                        d_new = (dist1 + dist2 + math.sqrt(max(0.0, 2.0 - diff * diff))) / 2.0
                    else:
                        d_new = min(dist1, dist2) + 1.0
                else:
                    d_new = min(dist1, dist2) + 1.0

                if d_new < dist[ny, nx]:
                    dist[ny, nx] = d_new
                    flag[ny, nx] = BAND
                    heapq.heappush(pq, (d_new, nx, ny))

    return np.clip(result, 0, 255).astype(np.uint8)
