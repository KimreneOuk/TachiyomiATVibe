"""Tests for legacy free-text inpaint: LOCAL color (no longer 'stuck on white')
and the bleed-free light feather. numpy/Pillow only, no cv2/scipy."""

import numpy as np
from server import (
    pil_inpaint_stroke,
    build_stroke_mask_local,
    inpaint_free_text,
    _local_ring_median,
)


def _page_with_panel(panel_color, ink_value=20):
    """200x200 mostly-white page with a large colored panel and a dark ink
    stroke (free text) inside it."""
    page = np.full((200, 200, 3), 255, np.uint8)
    page[40:160, 40:160] = np.array(panel_color, np.uint8)
    page[85:95, 60:140] = ink_value  # dark horizontal ink inside the panel
    box = [60, 85, 140, 95]
    return page, box


def test_local_ring_median_is_panel_color_not_white():
    page, box = _page_with_panel((70, 130, 200))
    mask = build_stroke_mask_local(page, box, pad=2)
    med = _local_ring_median(page, mask > 0)
    # Ring is the surrounding panel → blue, not the global page white.
    assert abs(med[0] - 70) < 6 and abs(med[2] - 200) < 6
    assert med[0] < 150  # definitely not white


def test_pil_inpaint_stroke_fills_with_local_color():
    page, box = _page_with_panel((70, 130, 200))
    mask = build_stroke_mask_local(page, box, pad=2)
    filled = pil_inpaint_stroke(page, mask)
    ink_region = filled[86:94, 62:138].reshape(-1, 3).mean(axis=0)
    # The former ink area now matches the blue panel, NOT white.
    assert ink_region[0] < 110, f"R should be ~70 (blue), got {ink_region[0]}"
    assert ink_region[2] > 180, f"B should be ~200 (blue), got {ink_region[2]}"


def test_inpaint_free_text_not_white_on_colored_panel():
    page, box = _page_with_panel((70, 130, 200))
    out, erase = inpaint_free_text(page, box, pad=2, feather_radius=3, tiny_expand=False)
    assert out.shape == page.shape and out.dtype == np.uint8
    assert erase.dtype == np.uint8 and int((erase > 0).sum()) > 0
    ink_region = out[86:94, 62:138].reshape(-1, 3).mean(axis=0)
    # Anti-regression: free text no longer 'stuck on white'.
    assert ink_region[0] < 120 and ink_region[2] > 175, f"got {ink_region}"


def test_inpaint_free_text_erases_dark_ink():
    page, box = _page_with_panel((70, 130, 200), ink_value=10)
    out, _ = inpaint_free_text(page, box, pad=2, feather_radius=3, tiny_expand=False)
    center = out[88:92, 90:110, 0]
    # The solid dark block must be erased (replaced by the panel color), not kept.
    assert center.mean() > 60, f"ink should be erased, mean={center.mean()}"


def test_inpaint_free_text_keeps_outside_untouched():
    page, box = _page_with_panel((70, 130, 200))
    # A marker pixel far from the box must survive unchanged.
    marker = (5, 5)
    out, _ = inpaint_free_text(page, box, pad=2, feather_radius=3, tiny_expand=False)
    assert np.array_equal(out[marker], page[marker])


def test_inpaint_free_text_tiny_expand_only_grows_small_boxes():
    # A tiny free-text box should be enlarged (so the stroke + AA is covered).
    page = np.full((100, 100, 3), 255, np.uint8)
    page[48:52, 40:46] = 20  # tiny ink
    tiny_box = [40, 48, 46, 52]  # short side = 4 < floor (18)
    out, erase = inpaint_free_text(page, tiny_box, pad=2, feather_radius=3, tiny_expand=True)
    # The erase mask must cover more than the raw 6x4 ink box would.
    assert int((erase > 0).sum()) > 40
