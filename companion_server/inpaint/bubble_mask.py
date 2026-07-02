"""Pure mask-construction and morphology helpers.

Port of BubbleMaskBuilder.kt — operates on numpy uint8 arrays (1=mask, 0=clear)
and provides:
  - build_rect_mask: solid padded rectangle mask + disk dilation
  - dilate_mask_disk: circular structuring-element dilation
  - distance_to_mask: chamfer (3,4) distance transform
  - feather_alpha_field: smooth alpha ramp from distance field
  - compute_neural_crop: text box ~1/3 of 512px inference tensor
"""
from __future__ import annotations

import numpy as np

NEURAL_CROP_MIN = 384
NEURAL_CROP_MAX = 512


def build_rect_mask(
    boxes: list[np.ndarray],
    width: int,
    height: int,
    pad: int = 8,
    dilate_radius: int = 2,
) -> np.ndarray:
    """Build a solid rectangular erase mask over the union of padded boxes."""
    mask = np.zeros((height, width), dtype=np.uint8)
    for box in boxes:
        if len(box) < 4:
            continue
        x1 = max(0, int(box[0]) - pad)
        y1 = max(0, int(box[1]) - pad)
        x2 = min(width, int(box[2]) + pad)
        y2 = min(height, int(box[3]) + pad)
        if x2 <= x1 or y2 <= y1:
            continue
        mask[y1:y2, x1:x2] = 1
    if dilate_radius > 0:
        mask = dilate_mask_disk(mask, dilate_radius)
    return mask


def dilate_mask_disk(mask: np.ndarray, radius: int) -> np.ndarray:
    """Disk (circular) structuring-element dilation."""
    if radius <= 0:
        return mask.copy()
    h, w = mask.shape
    result = mask.copy()
    kernel = []
    for dy in range(-radius, radius + 1):
        for dx in range(-radius, radius + 1):
            if dx * dx + dy * dy <= radius * radius:
                kernel.append((dx, dy))
    ys, xs = np.where(mask != 0)
    for dy, dx in kernel:
        ny = np.clip(ys + dy, 0, h - 1)
        nx = np.clip(xs + dx, 0, w - 1)
        result[ny, nx] = 1
    return result


def distance_to_mask(mask: np.ndarray) -> np.ndarray:
    """Two-pass chamfer (3,4) distance transform.

    Returns approximate Euclidean distance to nearest non-zero mask pixel.
    Distance is 0 inside the mask, grows outward.
    """
    h, w = mask.shape
    n = h * w
    INF = 1_000_000_000
    dist = np.full(n, INF, dtype=np.int32)
    flat = mask.ravel()

    H = 3
    D = 4

    flat_mask = flat != 0

    # Forward pass
    dist_2d = dist.reshape(h, w)
    for y in range(h):
        for x in range(w):
            if flat_mask[y * w + x]:
                dist_2d[y, x] = 0
                continue
            best = dist_2d[y, x]
            if x > 0:
                v = dist_2d[y, x - 1]
                if v < best - H:
                    best = v + H
            if y > 0:
                v = dist_2d[y - 1, x]
                if v < best - H:
                    best = v + H
            if x > 0 and y > 0:
                v = dist_2d[y - 1, x - 1]
                if v < best - D:
                    best = v + D
            if x < w - 1 and y > 0:
                v = dist_2d[y - 1, x + 1]
                if v < best - D:
                    best = v + D
            dist_2d[y, x] = best

    # Backward pass
    for y in range(h - 1, -1, -1):
        for x in range(w - 1, -1, -1):
            if flat_mask[y * w + x]:
                continue
            best = dist_2d[y, x]
            if x < w - 1:
                v = dist_2d[y, x + 1]
                if v < best - H:
                    best = v + H
            if y < h - 1:
                v = dist_2d[y + 1, x]
                if v < best - H:
                    best = v + H
            if x < w - 1 and y < h - 1:
                v = dist_2d[y + 1, x + 1]
                if v < best - D:
                    best = v + D
            if x > 0 and y < h - 1:
                v = dist_2d[y + 1, x - 1]
                if v < best - D:
                    best = v + D
            dist_2d[y, x] = best

    out = dist_2d.astype(np.float32) / float(H)
    out[np.where(dist_2d >= INF)] = np.inf
    return out


def feather_alpha_field(mask: np.ndarray, ramp_width: int = 12) -> np.ndarray:
    """Distance-field feathered alpha (0..1).

    Uses iterative single-pixel dilation for O(ramp * n) performance,
    fully vectorized with numpy. Core pixels = 1.0; each ring of pixels
    outside the mask gets decreasing alpha.
    """
    h, w = mask.shape
    alpha = np.zeros((h, w), dtype=np.float32)
    if not np.any(mask):
        return alpha

    alpha[mask != 0] = 1.0
    ramp = max(2, ramp_width)

    current = (mask != 0)
    for i in range(1, ramp + 1):
        # Dilate by 1 pixel using shifted OR (4-connectivity + diagonals)
        dilated = np.zeros_like(current)
        dilated[1:, :] |= current[:-1, :]
        dilated[:-1, :] |= current[1:, :]
        dilated[:, 1:] |= current[:, :-1]
        dilated[:, :-1] |= current[:, 1:]
        dilated[1:, 1:] |= current[:-1, :-1]
        dilated[1:, :-1] |= current[:-1, 1:]
        dilated[:-1, 1:] |= current[1:, :-1]
        dilated[:-1, :-1] |= current[1:, 1:]
        dilated |= current

        ring = dilated & ~current
        a = max(0.0, 1.0 - float(i) / float(ramp))
        alpha[ring] = a
        if not np.any(ring):
            break
        current = dilated

    return alpha


def mask_coverage(mask: np.ndarray) -> float:
    """Percentage (0..100) of mask bytes that are non-zero."""
    if mask.size == 0:
        return 0.0
    return float(np.count_nonzero(mask)) * 100.0 / mask.size


def compute_neural_crop(box_long_side: int) -> int:
    """Target the neural crop so the text box occupies ~1/3 of the tensor."""
    if box_long_side <= 0:
        return NEURAL_CROP_MIN
    target = box_long_side * 3
    return max(NEURAL_CROP_MIN, min(NEURAL_CROP_MAX, target))
