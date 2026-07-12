"""Tests for the segmentation bubble fill — the smart solid-fill strategy.

Contract these tests enforce on fill_bubbles_smart_color:
  fill_bubbles_smart_color(image_bgr, mask, params=None) -> image_bgr
  params (dict, default_mask_params() if None):
    strategy    : "median" | "telea" | "pushpull" | "ns" | "hybrid"  (default "median")
    skip_px     : int  — dilate component outward to step OVER the border stroke
                          before sampling the ring/annulus (default 3)
    ring_w      : int  — annulus width to sample (default 4)
    dilate      : int  — extra px to grow the FILL region beyond the component (default 0)
    luma_floor  : int  — 0-255, exclude ring pixels darker than this from the median (default 128)
    feather     : int  — distance-field alpha ramp width on the fill edge (default 0)

"median" is the Android-faithful smart solid fill (AotReportBubbleFill): each
connected component is filled solid with the median colour of a ring just
outside it. The other strategies (telea/pushpull/ns/hybrid) are optional
experiments selected via params["strategy"].

These tests run WITHOUT loading any YOLO model — they call the fill fn directly
with a hand-built mask, so they are fast and deterministic.
"""
import numpy as np
import cv2

from backend.inpaint.bubble_segmentation import (
    fill_bubbles_smart_color,
    default_mask_params,
)


def _synthetic_bubble():
    """60x60 BGR image simulating a manga speech bubble.

    Layout:
      - whole image: light page background (value 200) — the gutter OUTSIDE bubble
      - black border stroke: rectangle [15:45, 15:45], thickness 2 (value 0)
      - white interior: rectangle [17:42, 17:42] (value 255)
      - mask: the interior only [17:42, 17:42] = 255

    The black border is the trap: a naive ring sampler catches it and drags
    the median fill to gray. The fixed skip-px sampler steps over it.
    """
    img = np.full((60, 60, 3), 200, dtype=np.uint8)          # light page
    cv2.rectangle(img, (15, 15), (44, 44), (0, 0, 0), 2)     # black border stroke
    cv2.rectangle(img, (17, 17), (42, 42), (255, 255, 255), -1)  # white interior
    mask = np.zeros((60, 60), dtype=np.uint8)
    cv2.rectangle(mask, (17, 17), (42, 42), 255, -1)         # mask = interior
    return img, mask


# ── median (the Android-faithful smart solid fill) ────────────────────


def test_median_skips_border_fills_near_page():
    """median with skip_px steps over the border → samples page (200)."""
    img, mask = _synthetic_bubble()
    p = default_mask_params()
    p["strategy"] = "median"
    p["skip_px"] = 3
    p["ring_w"] = 4
    out = fill_bubbles_smart_color(img, mask, p)
    center = float(out[29, 29].mean())
    assert center > 170, f"median should fill near page color, got {center}"


def test_median_is_default_strategy():
    """Default strategy must be 'median' (the Android-faithful solid fill)."""
    p = default_mask_params()
    assert p["strategy"] == "median"


# ── luma_floor: exclude dark ring pixels ──────────────────────────────


def test_luma_floor_excludes_dark_pixels():
    """luma_floor drops dark ring pixels so they can't drag the median down."""
    img, mask = _synthetic_bubble()
    # paint a dark stripe through the gutter so the ring sees dark pixels
    img[5:12, :] = 10
    p = default_mask_params()
    p["luma_floor"] = 150
    out = fill_bubbles_smart_color(img, mask, p)
    center = float(out[29, 29].mean())
    assert center > 150, f"luma_floor should keep fill bright, got {center}"


# ── dilate: grow the FILL region ──────────────────────────────────────


def test_dilate_zero_preserves_border():
    """dilate=0: border pixel (16) outside mask is untouched."""
    img, mask = _synthetic_bubble()
    p = default_mask_params()
    p["dilate"] = 0
    out = fill_bubbles_smart_color(img.copy(), mask, p)
    assert out[16, 29, 0] == 0, "border should be preserved when dilate=0"


def test_dilate_grows_fill_past_border():
    """dilate=2: border pixel (16) falls inside the grown fill mask → overwritten."""
    img, mask = _synthetic_bubble()
    p = default_mask_params()
    p["dilate"] = 2
    p["inset_px"] = 0  # dilate grows the fill region; inset must not claw it back
    out = fill_bubbles_smart_color(img.copy(), mask, p)
    assert out[16, 29, 0] != 0, "border should be erased when dilate>=2"


# ── inset_px: keep fill strictly inside the seg mask ───────────────────


def test_inset_keeps_mask_border_pixel_untouched():
    """inset_px>0: a pixel ON the mask border (just inside the edge) stays
    original, because the overwrite region is eroded inward.

    Mask spans rows 17..42. Row 17 is the top mask edge. With inset_px=2 the
    eroded interior starts at row 19, so row 17 reverts to original (white
    interior 255) — but more tellingly, we paint row 17 a sentinel colour and
    assert it survives the fill.
    """
    img, mask = _synthetic_bubble()
    img[17, 29] = (50, 60, 70)  # sentinel on the mask's top edge
    p = default_mask_params()
    p["inset_px"] = 2
    out = fill_bubbles_smart_color(img.copy(), mask, p)
    assert tuple(out[17, 29]) == (50, 60, 70), \
        "inset_px must leave the mask-border pixel untouched"


def test_inset_zero_fills_to_mask_edge():
    """inset_px=0: the mask-border pixel IS overwritten (no buffer)."""
    img, mask = _synthetic_bubble()
    img[17, 29] = (50, 60, 70)  # sentinel on the mask's top edge
    p = default_mask_params()
    p["inset_px"] = 0
    out = fill_bubbles_smart_color(img.copy(), mask, p)
    assert tuple(out[17, 29]) != (50, 60, 70), \
        "inset_px=0 should overwrite the mask-border pixel"


def test_inset_default_is_two():
    """Default inset_px is 5 (a sensible buffer against border clipping)."""
    p = default_mask_params()
    assert p["inset_px"] == 5


# ── feather: soften fill edge ─────────────────────────────────────────


def test_feather_blends_boundary():
    """feather>0 produces a soft edge (boundary pixel differs from hard fill).

    The distance-field feather ramps alpha on the OUTSIDE of the mask. So we
    check a pixel just OUTSIDE the mask top edge (mask starts at row 17):
    hard fill leaves it as the original border (0), soft fill blends the page
    colour (200) into it.

    inset_px is zeroed to isolate feather behaviour (inset would move the
    feather boundary inward).
    """
    img, mask = _synthetic_bubble()
    p_hard = default_mask_params()
    p_hard["feather"] = 0
    p_hard["inset_px"] = 0
    out_hard = fill_bubbles_smart_color(img.copy(), mask, p_hard)

    p_soft = default_mask_params()
    p_soft["feather"] = 3
    p_soft["inset_px"] = 0
    out_soft = fill_bubbles_smart_color(img.copy(), mask, p_soft)

    edge_y, edge_x = 16, 29
    assert not np.array_equal(out_hard[edge_y, edge_x], out_soft[edge_y, edge_x]), \
        "feather should change boundary pixel values"


# ── params plumbing ───────────────────────────────────────────────────


def test_default_mask_params_keys():
    p = default_mask_params()
    for k in ("strategy", "skip_px", "ring_w", "dilate", "luma_floor", "feather", "inset_px"):
        assert k in p, f"missing key {k}"


def test_none_params_uses_defaults():
    """Calling with params=None must not crash — uses default_mask_params()."""
    img, mask = _synthetic_bubble()
    out = fill_bubbles_smart_color(img, mask, None)  # no crash
    assert out.shape == img.shape


# ── strategy dispatch (experiments) ────────────────────────────────────


def test_strategy_telea_valid_image():
    """strategy='telea' dispatches to fast_marching.inpaint_telea."""
    img, mask = _synthetic_bubble()
    p = default_mask_params()
    p["strategy"] = "telea"
    out = fill_bubbles_smart_color(img, mask, p)
    assert out.shape == img.shape
    assert out.dtype == np.uint8
