"""Tests for Telea FMM inpainting (pure numpy, no cv2/scipy)."""

import numpy as np
from cci_inpaint import telea_inpaint


# ---------------------------------------------------------------------------
# Dirichlet reconstruction
# ---------------------------------------------------------------------------

def test_constant_fill():
    """Fill a hole on a constant background with the same constant color."""
    h, w = 30, 40
    bg_color = np.array([150, 200, 100], dtype=np.uint8)
    rgb = np.full((h, w, 3), bg_color, dtype=np.uint8)
    mask = np.zeros((h, w), dtype=np.uint8)
    mask[10:20, 10:30] = 255

    result = telea_inpaint(rgb, mask, radius=3)
    hole = result[10:20, 10:30]
    mean_hole = hole.mean(axis=(0, 1))
    assert np.allclose(mean_hole, bg_color.astype(np.float64), atol=3), \
        f"hole mean {mean_hole} != background {bg_color}"


def test_linear_gradient_preserved():
    """Hole on a linear gradient should roughly follow the gradient."""
    h, w = 30, 40
    rgb = np.zeros((h, w, 3), dtype=np.uint8)
    for y in range(h):
        val = int(255 * y / (h - 1))
        rgb[y, :] = [val, val, val]

    mask = np.zeros((h, w), dtype=np.uint8)
    mask[10:20, 10:30] = 255

    result = telea_inpaint(rgb, mask, radius=3)
    # Check monotonicity in the filled region
    filled = result[10:20, 10:30, 0].astype(np.float64)
    for x in range(filled.shape[1]):
        col = filled[:, x]
        # Should be roughly monotonic from top to bottom
        diffs = np.diff(col)
        neg_ratio = np.sum(diffs < -3) / max(1, len(diffs))
        assert neg_ratio < 0.5, \
            f"column {x} has {neg_ratio*100:.0f}% pixels decreasing"


# ---------------------------------------------------------------------------
# radius param accepted
# ---------------------------------------------------------------------------

def test_different_radius():
    h, w = 30, 30
    rgb = np.full((h, w, 3), 100, dtype=np.uint8)
    mask = np.zeros((h, w), dtype=np.uint8)
    mask[12:18, 12:18] = 255

    r3 = telea_inpaint(rgb, mask, radius=3)
    r5 = telea_inpaint(rgb, mask, radius=5)
    assert r3.shape == (h, w, 3)
    assert r5.shape == (h, w, 3)


# ---------------------------------------------------------------------------
# masked pixels changed, non-masked unchanged
# ---------------------------------------------------------------------------

def test_non_masked_unchanged():
    h, w = 20, 20
    rgb = np.zeros((h, w, 3), dtype=np.uint8)
    for x in range(w):
        val = int(255 * x / (w - 1))
        rgb[:, x] = [val, val, val]
    mask = np.zeros((h, w), dtype=np.uint8)
    mask[5:15, 5:15] = 255

    result = telea_inpaint(rgb, mask, radius=3)
    assert np.array_equal(result[:5, :5], rgb[:5, :5]), \
        "non-masked pixels should be unchanged"
    assert not np.array_equal(result[5:15, 5:15], rgb[5:15, 5:15]), \
        "masked pixels should be modified"


def test_no_mask():
    h, w = 10, 10
    rgb = np.full((h, w, 3), 128, dtype=np.uint8)
    mask = np.zeros((h, w), dtype=np.uint8)
    result = telea_inpaint(rgb, mask, radius=3)
    assert np.array_equal(result, rgb)
