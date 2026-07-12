"""Inpainting strategy dispatcher — selects the fill algorithm per call.

All strategies share the contract:
    fn(image_bgr HxWx3 uint8, mask HxW uint8, params dict) -> HxWx3 uint8

Reuses the existing pure-numpy implementations from companion_server/inpaint/
(Telea, push-pull) and the new ns_solver (Laplace). `fill_median` is the
improved per-component ring fill (vectorized). `fill_hybrid` auto-selects
median vs telea per connected component based on ring variance.

cv2 is used ONLY for distanceTransform (the feather alpha field) and trivial
morphology/contour ops — never for the inpaint solve itself, so everything
here is portable to Android (which has no OpenCV).
"""
from __future__ import annotations

import logging
from typing import Any

import cv2
import numpy as np

from backend import config
from backend.inpaint import ns_solver

# companion_server is a sibling package whose numpy inpaint ports (Telea,
# push-pull) we reuse. Put it on sys.path so `from inpaint import ...` works
# (matches the idiom in pipeline.py).
import sys as _sys
_CS = str(config.COMPANION_SERVER)
if _CS not in _sys.path:
    _sys.path.insert(0, _CS)

logger = logging.getLogger("overlay_lab")

# Ring-variance threshold below which a component is deemed "flat" (→ median
# fast path in hybrid mode). Tuned on manga bubbles: white-on-white interiors
# sit well under this; textured backgrounds sit well above.
HYBRID_VARIANCE_THRESHOLD = 12.0


# ── per-component ring median fill (improved, vectorized) ─────────────


def _ring_pixels_for_component(
    image_bgr: np.ndarray,
    comp_mask: np.ndarray,
    gray: np.ndarray | None,
    skip_px: int,
    ring_w: int,
    luma_floor: int,
) -> np.ndarray | None:
    """Sample the interior of the component, return the median color.

    Instead of an external ring, we sample the interior pixels of the bubble.
    Since text typically occupies < 50% of a bubble's area, the median pixel
    perfectly represents the true background color of the bubble.
    """
    # Slightly erode the mask to avoid sampling the black stroke border
    kern = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (5, 5))
    eroded = cv2.erode(comp_mask, kern, iterations=1)
    if not eroded.any():
        eroded = comp_mask
        
    pixels = image_bgr[eroded == 255]
    if len(pixels) == 0:
        return None
    
    median = np.median(pixels, axis=0).astype(np.uint8)
    # Snap grayish colors to pure white or pure black, but leave true grays alone.
    cmax = int(median.max())
    cmin = int(median.min())
    if cmax - cmin < 30:
        luma = (cmax + cmin) / 2
        if luma > 220:
            median = np.array([255, 255, 255], dtype=np.uint8)
        elif luma < 60:
            median = np.array([0, 0, 0], dtype=np.uint8)
            
    return median


def fill_median(image_bgr: np.ndarray, mask: np.ndarray, params: dict[str, Any]) -> np.ndarray:
    """Per-component ring-median fill.

    Each connected component gets its OWN median (sampled from the annulus
    just outside it), so neighbouring bubbles with different page tints are
    filled correctly. Optionally grows the fill region by `dilate` px.
    """
    skip_px = int(params.get("skip_px", 3))
    ring_w = int(params.get("ring_w", 4))
    dilate_px = int(params.get("dilate", 0))
    luma_floor = int(params.get("luma_floor", 128))

    result = image_bgr.copy()
    h, w = mask.shape
    gray = None
    if luma_floor > 0:
        gray = cv2.cvtColor(image_bgr, cv2.COLOR_BGR2GRAY)

    contours, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    for contour in contours:
        x, y, cw, ch = cv2.boundingRect(contour)
        
        # Calculate padding needed for ring and dilate
        pad = skip_px + ring_w + dilate_px
        x1 = max(0, x - pad)
        y1 = max(0, y - pad)
        x2 = min(w, x + cw + pad)
        y2 = min(h, y + ch + pad)
        
        # Create a small mask just for this ROI
        roi_w = x2 - x1
        roi_h = y2 - y1
        comp_mask_roi = np.zeros((roi_h, roi_w), dtype=np.uint8)
        
        # Shift contour to ROI coordinates
        shifted_contour = contour - [x1, y1]
        cv2.drawContours(comp_mask_roi, [shifted_contour], -1, 255, -1)
        
        # Extract small image regions
        img_roi = image_bgr[y1:y2, x1:x2]
        gray_roi = gray[y1:y2, x1:x2] if gray is not None else None
        
        # Calculate median color on the small ROI
        median_color = _ring_pixels_for_component(
            img_roi, comp_mask_roi, gray_roi, skip_px, ring_w, luma_floor
        )
        if median_color is None:
            continue
            
        if dilate_px > 0:
            ks = max(3, dilate_px * 2 + 1)
            kern = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (ks, ks))
            fill_region = cv2.dilate(comp_mask_roi, kern, iterations=1)
        else:
            fill_region = comp_mask_roi
            
        # Paste back into result
        result_roi = result[y1:y2, x1:x2]
        result_roi[fill_region == 255] = median_color
    return result


# ── Telea (reuse fast_marching) ───────────────────────────────────────


def fill_telea(image_bgr: np.ndarray, mask: np.ndarray, params: dict[str, Any]) -> np.ndarray:
    """Telea Fast Marching Method — reuse companion_server.inpaint.fast_marching.

    Structure-propagating fill: pixels are reconstructed from known
    neighbours in fast-marching arrival order. radius controls the
    neighbourhood (matches Android's FastMarchingMethod.kt default of 3).
    """
    from inpaint import fast_marching  # companion_server on sys.path

    hole = (mask != 0).astype(np.uint8)
    return fast_marching.inpaint_telea(image_bgr, hole, radius=int(params.get("radius", 3)))


# ── Push-pull gradient (reuse push_pull) ──────────────────────────────


def fill_pushpull(image_bgr: np.ndarray, mask: np.ndarray, params: dict[str, Any]) -> np.ndarray:
    """Push-pull gradient fill — reuse companion_server.inpaint.push_pull.

    Smooth gradient reconstruction from the surrounding colour: erase ink
    with the local ring median, then box-downscale → bilinear-upscale →
    boundary diffusion. The free-text star on Android.
    """
    from inpaint import push_pull as pp

    hole = (mask != 0).astype(np.uint8)
    bg = pp.local_ring_median(image_bgr, hole, ring=int(params.get("ring", 8)))
    return pp.push_pull_fill(image_bgr, hole, bg)


# ── Navier-Stokes / Laplace (the new candidate) ───────────────────────


def fill_ns(image_bgr: np.ndarray, mask: np.ndarray, params: dict[str, Any]) -> np.ndarray:
    """Pure-numpy Laplace relaxation — smooth-gradient fill, OpenCV-free.

    The one genuinely new algorithm in the lab (Android has only Telea).
    Solves ∇²u=0 inside the mask with Dirichlet BCs.
    """
    return ns_solver.inpaint_ns(
        image_bgr,
        (mask != 0).astype(np.uint8),
        iterations=int(params.get("iterations", ns_solver.DEFAULT_ITERATIONS)),
    )


# ── Hybrid: per-component auto-select ─────────────────────────────────


def fill_hybrid(image_bgr: np.ndarray, mask: np.ndarray, params: dict[str, Any]) -> np.ndarray:
    """Per-component auto-select: flat → median, textured → telea.

    For each connected component, sample the ring variance. Low-variance
    surroundings (flat-white bubbles) get the cheap, exact median fill;
    high-variance (textured artwork) get Telea structure propagation.
    """
    skip_px = int(params.get("skip_px", 3))
    ring_w = int(params.get("ring_w", 4))
    luma_floor = int(params.get("luma_floor", 128))
    threshold = float(params.get("hybrid_variance", HYBRID_VARIANCE_THRESHOLD))

    result = image_bgr.copy()
    h, w = mask.shape
    gray = cv2.cvtColor(image_bgr, cv2.COLOR_BGR2GRAY)

    contours, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    flat_mask = np.zeros((h, w), dtype=np.uint8)
    textured_mask = np.zeros((h, w), dtype=np.uint8)
    for contour in contours:
        comp_mask = np.zeros((h, w), dtype=np.uint8)
        cv2.drawContours(comp_mask, [contour], -1, 255, -1)
        # ring variance to classify
        ksize_skip = max(3, skip_px * 2 + 1)
        ksize_ring = max(3, ring_w * 2 + 1)
        kern_skip = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (ksize_skip, ksize_skip))
        kern_ring = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (ksize_ring, ksize_ring))
        dilated = cv2.dilate(comp_mask, kern_skip, iterations=1)
        dilated_wide = cv2.dilate(dilated, kern_ring, iterations=1)
        annulus = cv2.bitwise_and(dilated_wide, cv2.bitwise_not(dilated))
        ring_gray = gray[annulus == 255]
        if len(ring_gray) == 0:
            flat_mask = cv2.bitwise_or(flat_mask, comp_mask)
            continue
        if ring_gray.std() < threshold:
            flat_mask = cv2.bitwise_or(flat_mask, comp_mask)
        else:
            textured_mask = cv2.bitwise_or(textured_mask, comp_mask)

    # Flat components → median (exact, instant).
    if flat_mask.any():
        result = fill_median(result, flat_mask, params)
    # Textured remainder → Telea (one batched call).
    if textured_mask.any():
        result = fill_telea(result, textured_mask, params)
    return result


# ── distance-field feather blend ──────────────────────────────────────


def feather_blend(
    result: np.ndarray,
    original: np.ndarray,
    mask: np.ndarray,
    feather_px: int,
) -> np.ndarray:
    """Blend result into original with a distance-field alpha ramp.

    Uses cv2.distanceTransform (a trivial op, portable) on the inverted
    mask to build a true Euclidean distance field — matches Android's
    chamfer-based featherAlphaField. feather_px=0 → hard overwrite.

    The fill colour is first propagated outward into the feather band by
    blurring `result` with a kernel sized to feather_px (so the colour that
    meets the original at the edge is the fill colour, not the unchanged
    outside). Then the distance-field alpha blends blurred-fill → original.
    """
    if feather_px <= 0:
        out = original.copy()
        out[mask != 0] = result[mask != 0]
        return out

    hole = (mask != 0).astype(np.uint8)
    inv = (hole == 0).astype(np.uint8) * 255
    # distance from every outside pixel to the nearest hole edge.
    # (maskSize=5 — DIST_MASK_PRECISE returns sentinel garbage in this context.)
    dist_outside = cv2.distanceTransform(inv, cv2.DIST_L2, 5)
    # alpha = 1 deep inside hole, ramping to 0 over feather_px outside.
    alpha = np.ones(hole.shape, dtype=np.float32)
    ramp = np.clip(1.0 - dist_outside / float(feather_px), 0.0, 1.0)
    alpha[hole == 0] = ramp[hole == 0]

    # Propagate the fill colour outward so the blend meets fill, not original.
    ksize = max(3, feather_px * 2 + 1)
    result_blurred = cv2.GaussianBlur(result, (ksize, ksize), 0)
    # Inside the hole we want the crisp fill; outside, the propagated colour.
    fill_source = result.copy()
    fill_source[hole == 0] = result_blurred[hole == 0]

    a = alpha[:, :, np.newaxis]
    return np.clip(
        original.astype(np.float32) * (1.0 - a) + fill_source.astype(np.float32) * a,
        0, 255,
    ).astype(np.uint8)


# ── dispatcher ────────────────────────────────────────────────────────


_STRATEGIES = {
    "median": fill_median,
    "telea": fill_telea,
    "pushpull": fill_pushpull,
    "ns": fill_ns,
    "hybrid": fill_hybrid,
}


def run_strategy(
    strategy: str,
    image_bgr: np.ndarray,
    mask: np.ndarray,
    params: dict[str, Any],
) -> np.ndarray:
    """Dispatch to the named strategy. Unknown strategy → median + log."""
    fn = _STRATEGIES.get(strategy)
    if fn is None:
        logger.error("Unknown inpaint strategy %r — falling back to median", strategy)
        fn = fill_median
    return fn(image_bgr, mask, params)
