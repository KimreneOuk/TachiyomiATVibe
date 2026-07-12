"""Navier-Stokes-style inpainting via pure-numpy Laplace relaxation.

Solves ∇²u = 0 inside the masked region with Dirichlet boundary conditions
taken from the known (unmasked) pixels. This is the smooth-field character of
cv2.INPAINT_NS, but implemented WITHOUT OpenCV so it is portable to Android
(which has no OpenCV).

Vectorized Jacobi iteration: each pass replaces every masked pixel with the
average of its 4-neighbours, leaving known pixels fixed (Dirichlet BCs).
Iteration count is capped, with an early-stop when the per-pass change falls
below a convergence threshold.

This is the only genuinely NEW inpaint algorithm in the lab — median, telea
and push-pull all reuse the existing companion_server numpy ports. NS is a
candidate for Android adoption (Android currently has only Telea).
"""
from __future__ import annotations

import numpy as np

# Convergence: stop when the worst masked pixel changes by less than this per
# pass. Plain Jacobi on an N-wide hole needs O(N²) passes to fully settle;
# the cap below is generous and the delta gate ends small-hole solves early.
CONVERGENCE_DELTA = 0.25
DEFAULT_ITERATIONS = 3000


def inpaint_ns(
    image_bgr: np.ndarray,
    mask: np.ndarray,
    iterations: int = DEFAULT_ITERATIONS,
) -> np.ndarray:
    """Laplace relaxation inpaint.

    Args:
        image_bgr: HxWx3 uint8 BGR image (NOT modified).
        mask: HxW uint8 (nonzero = hole to reconstruct).
        iterations: max Jacobi iterations (capped; early-stops on convergence).

    Returns a new HxWx3 uint8 BGR with masked pixels smoothly reconstructed.
    Only masked pixels change; known pixels are bit-exact copies of the input.
    """
    if iterations <= 0:
        return image_bgr.copy()

    h, w = mask.shape[:2]
    hole = mask != 0
    if not hole.any():
        return image_bgr.copy()

    # Work in float for accumulation; BCs come from the original known pixels.
    u = image_bgr.astype(np.float32).copy()

    for _ in range(iterations):
        prev = u.copy()
        # 4-neighbour average with edge clamping (replicate boundary).
        up = np.clip(np.arange(h) - 1, 0, h - 1)
        down = np.clip(np.arange(h) + 1, 0, h - 1)
        left = np.clip(np.arange(w) - 1, 0, w - 1)
        right = np.clip(np.arange(w) + 1, 0, w - 1)
        avg = (
            u[up, :] + u[down, :] + u[:, left] + u[:, right]
        ) * 0.25
        # Apply only inside the hole (Dirichlet BCs elsewhere).
        u[hole] = avg[hole]
        # Convergence: stop only when the WORST masked pixel has settled
        # (mean-delta stops too early — edge pixels converge fast and mask a
        # still-drifting centre on large holes).
        delta = float(np.abs(u[hole] - prev[hole]).max())
        if delta < CONVERGENCE_DELTA:
            break

    return np.clip(u, 0, 255).astype(np.uint8)
