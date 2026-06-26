"""Tests for Poisson seamless blend (pure numpy, no cv2/scipy)."""

import numpy as np
from cci_inpaint import (
    poisson_seamless_blend, feather_blend_fallback, PoissonBorderError,
)


# ---------------------------------------------------------------------------
# Constant-source Poisson
# ---------------------------------------------------------------------------

def test_constant_source_reconstruction():
    """If dst = src = constant C, Poisson should keep C in the hole."""
    h, w = 40, 40
    C = np.array([128, 128, 128], dtype=np.uint8)
    dst = np.full((h, w, 3), C, dtype=np.uint8)
    src = np.full((h, w, 3), C, dtype=np.uint8)
    mask = np.zeros((h, w), dtype=np.uint8)
    mask[10:30, 10:30] = 255

    result = poisson_seamless_blend(dst, src, mask, iterations=100, tol=1e-4)
    mean_hole = result[10:30, 10:30].mean(axis=(0, 1))
    assert np.allclose(mean_hole, C.astype(np.float64), atol=2), \
        f"hole mean {mean_hole} != {C}"


# ---------------------------------------------------------------------------
# No-glow property
# ---------------------------------------------------------------------------

def test_poisson_anti_glow():
    """Poisson should reconstruct the dst gradient inside the hole rather than
    preserving a flat src fill (the "glow" artifact).

    dst = uniform 255 (bright page), src = flat 128 (a poor uniform inpaint).
    The Poisson blend should reconstruct the dst boundary value of 255
    inside the hole, not preserve the flat 128 src fill.
    """
    h, w = 30, 30
    dst = np.full((h, w, 3), 255, dtype=np.uint8)

    mask = np.zeros((h, w), dtype=np.uint8)
    mask[5:25, 10:20] = 255

    src = np.full((h, w, 3), 128, dtype=np.uint8)

    result = poisson_seamless_blend(
        dst, src, mask, iterations=500, mixing=False, tol=1e-4,
    )
    filled = result[5:25, 10:20]

    # 1) Filled mean must be closer to dst boundary (255) than to src fill (128)
    filled_mean = filled.mean(axis=(0, 1))
    dst_boundary = np.array([255.0, 255.0, 255.0])
    src_fill = np.array([128.0, 128.0, 128.0])

    dist_to_dst = np.linalg.norm(filled_mean - dst_boundary)
    dist_to_src = np.linalg.norm(filled_mean - src_fill)
    assert dist_to_dst < dist_to_src, \
        f"filled {filled_mean.round(1)}: nearer src ({dist_to_src:.1f}) " \
        f"than dst ({dist_to_dst:.1f})"

    # 2) Filled mean should be > 200 (very close to boundary)
    assert filled_mean[0] > 200, \
        f"filled mean {filled_mean[0]:.0f} not pulled enough toward dst"


# ---------------------------------------------------------------------------
# PoissonBorderError
# ---------------------------------------------------------------------------

def test_mask_touches_border_raises():
    h, w = 20, 20
    dst = np.full((h, w, 3), 128, dtype=np.uint8)
    src = np.full((h, w, 3), 255, dtype=np.uint8)
    mask = np.zeros((h, w), dtype=np.uint8)
    mask[0, :] = 255  # touches top border
    try:
        poisson_seamless_blend(dst, src, mask)
        assert False, "should have raised PoissonBorderError"
    except PoissonBorderError:
        pass


def test_empty_mask_raises():
    h, w = 20, 20
    dst = np.full((h, w, 3), 128, dtype=np.uint8)
    src = np.full((h, w, 3), 255, dtype=np.uint8)
    mask = np.zeros((h, w), dtype=np.uint8)
    try:
        poisson_seamless_blend(dst, src, mask)
        assert False, "should have raised PoissonBorderError"
    except PoissonBorderError:
        pass


def test_mask_touches_left_border_raises():
    h, w = 20, 20
    dst = np.full((h, w, 3), 128, dtype=np.uint8)
    src = np.full((h, w, 3), 255, dtype=np.uint8)
    mask = np.zeros((h, w), dtype=np.uint8)
    mask[:, 0] = 255  # touches left border
    try:
        poisson_seamless_blend(dst, src, mask)
        assert False
    except PoissonBorderError:
        pass


# ---------------------------------------------------------------------------
# feather_blend_fallback
# ---------------------------------------------------------------------------

def test_feather_blend_fallback_valid():
    dst = np.full((20, 20, 3), 200, dtype=np.uint8)
    src = np.full((20, 20, 3), 50, dtype=np.uint8)
    mask = np.zeros((20, 20), dtype=np.uint8)
    mask[5:15, 5:15] = 255
    result = feather_blend_fallback(dst, src, mask, 4)
    assert result.shape == (20, 20, 3)
    assert result.dtype == np.uint8
    assert np.all(result <= 255) and np.all(result >= 0)


def test_feather_blend_ring_equals_dst():
    dst = np.full((20, 20, 3), 200, dtype=np.uint8)
    src = np.full((20, 20, 3), 50, dtype=np.uint8)
    mask = np.zeros((20, 20), dtype=np.uint8)
    mask[5:15, 5:15] = 255
    result = feather_blend_fallback(dst, src, mask, 4)
    # Far pixels should equal dst
    assert np.array_equal(result[0, 0], dst[0, 0])


# ---------------------------------------------------------------------------
# mixing runs without error
# ---------------------------------------------------------------------------

def test_poisson_mixing_runs():
    h, w = 30, 30
    dst = np.full((h, w, 3), 100, dtype=np.uint8)
    src = np.full((h, w, 3), 200, dtype=np.uint8)
    mask = np.zeros((h, w), dtype=np.uint8)
    mask[5:25, 5:25] = 255
    result = poisson_seamless_blend(
        dst, src, mask, iterations=50, mixing=True, tol=1e-3,
    )
    assert result.dtype == np.uint8
    assert not np.any(np.isnan(result))
    assert np.all(result <= 255) and np.all(result >= 0)


# ---------------------------------------------------------------------------
# Regression: text inpainting must IMPORT the reconstruction gradients, never
# preserve the destination's stroke gradients. mixing=True re-reconstructs the
# dark text "hair"; mixing=False (the default) erases it. See CCIOptions.
# ---------------------------------------------------------------------------

def test_poisson_default_erases_destination_strokes():
    h, w = 60, 60
    dst = np.full((h, w, 3), 200, dtype=np.uint8)
    dst[:, 28:32] = 20  # dark text stroke inside the mask region
    src = np.full((h, w, 3), 200, dtype=np.uint8)  # reconstruction: stroke gone
    mask = np.zeros((h, w), dtype=np.uint8)
    mask[10:50, 10:50] = 255

    # Default (mixing=False): the stroke must NOT survive.
    out_import = poisson_seamless_blend(dst, src, mask, iterations=400, tol=0)
    stroke_import = out_import[20:40, 28:32, 0].mean()
    assert stroke_import > 140, f"non-mixing should erase stroke, got {stroke_import}"

    # mixing=True preserves the destination stroke (the bug we avoid by default).
    out_mix = poisson_seamless_blend(dst, src, mask, iterations=400, mixing=True, tol=0)
    stroke_mix = out_mix[20:40, 28:32, 0].mean()
    assert stroke_mix < 80, f"mixing should preserve stroke, got {stroke_mix}"
