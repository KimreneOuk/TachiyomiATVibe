"""Tests for the strategy dispatcher + feather_blend.

Contract:
  fill_median(image_bgr, mask, params) -> image_bgr
  fill_telea(image_bgr, mask, params) -> image_bgr
  fill_pushpull(image_bgr, mask, params) -> image_bgr
  fill_ns(image_bgr, mask, params) -> image_bgr
  fill_hybrid(image_bgr, mask, params) -> image_bgr
  feather_blend(result, original, mask, feather_px) -> image_bgr

  image_bgr: HxWx3 uint8 (BGR). mask: HxW uint8 (255=fill). params: dict.
"""
import numpy as np

from backend.inpaint.strategies import (
    fill_median,
    fill_telea,
    fill_pushpull,
    fill_hybrid,
    feather_blend,
)


def _two_bubbles_different_surroundings():
    """120x60 BGR: left half red surroundings, right half blue; two white holes."""
    img = np.zeros((60, 120, 3), dtype=np.uint8)
    img[:, :60] = [40, 40, 200]   # red-ish (BGR)
    img[:, 60:] = [200, 40, 40]   # blue-ish (BGR)
    mask = np.zeros((60, 120), dtype=np.uint8)
    mask[20:40, 20:40] = 255      # left bubble interior
    mask[20:40, 80:100] = 255     # right bubble interior
    return img, mask


def _single_bubble_with_border():
    """60x60 BGR: page grey + black border stroke + white interior (seg-tight mask)."""
    img = np.full((60, 60, 3), 200, dtype=np.uint8)
    img[15:45, 15:45] = 0  # black box (border stroke region)
    img[18:42, 18:42] = 255  # white interior
    mask = np.zeros((60, 60), dtype=np.uint8)
    mask[18:42, 18:42] = 255  # mask = white interior only (border preserved)
    return img, mask


# ── fill_median ───────────────────────────────────────────────────────


def test_fill_median_per_component_different_colors():
    img, mask = _two_bubbles_different_surroundings()
    out = fill_median(img.copy(), mask, {"skip_px": 3, "ring_w": 4, "luma_floor": 0, "dilate": 0})
    left_fill = out[30, 30].astype(int)
    right_fill = out[30, 90].astype(int)
    # the two bubbles must get DIFFERENT colors (per-component median)
    assert not np.array_equal(left_fill, right_fill), \
        f"per-component median failed: both={left_fill}"


def test_fill_median_preserves_border_stroke():
    img, mask = _single_bubble_with_border()
    out = fill_median(img.copy(), mask, {"skip_px": 3, "ring_w": 4, "luma_floor": 0, "dilate": 0})
    # the border stroke region [15:45,15:45] minus interior must stay black
    border_pixel = out[16, 16]
    assert (border_pixel < 30).all(), f"border stroke corrupted: {border_pixel}"


# ── fill_telea (reuse fast_marching) ──────────────────────────────────


def test_fill_telea_changes_only_masked_pixels():
    img, mask = _single_bubble_with_border()
    out = fill_telea(img.copy(), mask, {})
    assert np.array_equal(out[mask == 0], img[mask == 0])
    assert out.dtype == np.uint8
    assert np.isfinite(out.astype(np.float32)).all()


# ── fill_pushpull (reuse push_pull) ───────────────────────────────────


def test_fill_pushpull_valid_output():
    img, mask = _single_bubble_with_border()
    out = fill_pushpull(img.copy(), mask, {})
    assert out.shape == img.shape
    assert out.dtype == np.uint8
    assert (out >= 0).all() and (out <= 255).all()


# ── fill_hybrid ───────────────────────────────────────────────────────


def test_fill_hybrid_flat_matches_median():
    """A flat-white bubble (low ring variance) → hybrid takes the median fast path.

    Uses UNIFORM grey surroundings so the ring variance is ~0 (decisively flat).
    """
    img = np.full((60, 60, 3), 200, dtype=np.uint8)  # uniform grey page
    mask = np.zeros((60, 60), dtype=np.uint8)
    mask[20:40, 20:40] = 255  # hole in the middle of uniform surround
    out_hybrid = fill_hybrid(img.copy(), mask.copy(), {"skip_px": 3, "ring_w": 4, "luma_floor": 0, "dilate": 0})
    out_median = fill_median(img.copy(), mask.copy(), {"skip_px": 3, "ring_w": 4, "luma_floor": 0, "dilate": 0})
    assert np.allclose(out_hybrid[mask == 255], out_median[mask == 255], atol=2)


def test_fill_hybrid_textured_differs_from_median():
    """A textured surround (high ring variance) → hybrid takes the telea path."""
    img = np.zeros((60, 60, 3), dtype=np.uint8)
    # checkerboard surround (high variance)
    for y in range(60):
        for x in range(60):
            if (x // 4 + y // 4) % 2 == 0:
                img[y, x] = [255, 255, 255]
            else:
                img[y, x] = [0, 0, 0]
    mask = np.zeros((60, 60), dtype=np.uint8)
    mask[20:40, 20:40] = 255
    out_hybrid = fill_hybrid(img.copy(), mask.copy(), {"skip_px": 3, "ring_w": 4, "luma_floor": 0, "dilate": 0})
    out_median = fill_median(img.copy(), mask.copy(), {"skip_px": 3, "ring_w": 4, "luma_floor": 0, "dilate": 0})
    # textured → telea (structure propagation) should differ from flat median
    assert not np.allclose(out_hybrid[mask == 255], out_median[mask == 255], atol=2), \
        "hybrid should have taken telea path for textured surround"


# ── feather_blend ─────────────────────────────────────────────────────


def test_feather_blend_monotonic_alpha():
    """Alpha profile decreases 1→0 across feather_px (distance-field ramp)."""
    result = np.full((50, 50, 3), 100, dtype=np.uint8)
    original = np.full((50, 50, 3), 200, dtype=np.uint8)
    mask = np.zeros((50, 50), dtype=np.uint8)
    mask[20:30, 20:30] = 255
    out = feather_blend(result, original, mask, feather_px=8)
    # sample a horizontal line out from mask edge (row 25, cols 30→45)
    line = out[25, 30:46].astype(np.float32).mean(axis=1)
    # should trend from ~100 (result) toward ~200 (original)
    assert line[0] < line[-1], f"alpha should ramp result→original: {line}"


def test_feather_blend_zero_feather_hard_edge():
    result = np.zeros((20, 20, 3), dtype=np.uint8)
    original = np.full((20, 20, 3), 200, dtype=np.uint8)
    mask = np.zeros((20, 20), dtype=np.uint8)
    mask[5:15, 5:15] = 255
    out = feather_blend(result, original, mask, feather_px=0)
    # hard edge: inside mask = result (0), outside = original (200)
    assert (out[mask == 255] == 0).all()
    assert (out[mask == 0] == 200).all()
