"""Tests for the AOT free-text inpainter faithful port.

Covers the pure-numpy helpers (pill mask, centered crop, geometry, output
guard) that do not require the ONNX session, plus a shape smoke test of the
full AotInpainter when the model is available.
"""
import numpy as np
import pytest

from backend import config
from backend.inference.inpaint_aot import (
    build_fixed_pill_mask,
    centered_report_crop,
    padded_union_bounds,
    localize_box,
    output_guard_is_suspicious,
    _fill_pill,
    _dilate_mask_disk,
)


# ── build_fixed_pill_mask (capsule shape) ──────────────────────────────


def test_pill_mask_capsule_has_rounded_caps():
    """A wide box produces a stadium: semicircular caps + central band."""
    w, h = 60, 30
    mask = build_fixed_pill_mask([[20, 10, 40, 20]], w, h, pad=0, dilate_radius=0)
    # The box is [20,10,40,20] (20x10). r = min(20,10)//2 = 5.
    # left_cx=25, right_cx=34, top_cy=15, bottom_cy=14.
    # Center of the box is in the horizontal band (left_cx<=x<=right_cx).
    assert mask[12, 30] == 1, "box center must be in the capsule (band)"
    # The cap center (left_cx=25, top_cy=15) is inside the disk (dx=dy=0 <= r2).
    assert mask[15, 25] == 1, "left cap center must be in the capsule"
    # The box corner (20,10): dx=-5,dy=-5 → 50 > r2(25) → OUTSIDE the disk,
    # and outside the band. A plain rectangle would set it; a capsule does not.
    assert mask[10, 20] == 0, "box corner outside the cap disk must NOT be set"


def test_pill_mask_empty_when_no_boxes():
    """No boxes → empty mask."""
    mask = build_fixed_pill_mask([], 50, 50, pad=10, dilate_radius=10)
    assert not mask.any()


def test_pill_mask_pad_expands():
    """pad>0 produces a superset of pad=0."""
    box = [[20, 20, 40, 30]]
    m0 = build_fixed_pill_mask(box, 60, 60, pad=0, dilate_radius=0)
    m_pad = build_fixed_pill_mask(box, 60, 60, pad=5, dilate_radius=0)
    # Every pixel set in m0 must also be set in m_pad.
    assert np.all(m_pad[m0 != 0] != 0), "pad must expand (superset)"


def test_pill_mask_dilate_grows():
    """dilate>0 produces a superset of dilate=0."""
    box = [[20, 20, 40, 30]]
    m0 = build_fixed_pill_mask(box, 60, 60, pad=0, dilate_radius=0)
    m_dil = build_fixed_pill_mask(box, 60, 60, pad=0, dilate_radius=5)
    assert np.all(m_dil[m0 != 0] != 0), "dilate must expand (superset)"
    assert m_dil.sum() > m0.sum(), "dilate must add pixels"


# ── centered_report_crop ───────────────────────────────────────────────


def test_centered_crop_square_and_clamped():
    """Crop is [x1,y1,x1+side,y1+side], side=min(512,min(W,H)), on-page."""
    # Image 1000x800, box near top-left → crop clamped to stay on-page.
    crop = centered_report_crop([[50, 50, 60, 60]], width=1000, height=800, context_size=512)
    assert crop is not None
    x1, y1, x2, y2 = crop
    side = min(512, min(1000, 800))
    assert x2 - x1 == side == 512
    assert y2 - y1 == side == 512
    assert 0 <= x1 and x2 <= 1000, "crop must stay on-page (x)"
    assert 0 <= y1 and y2 <= 800, "crop must stay on-page (y)"


def test_centered_crop_clamps_near_edge():
    """A box near the page edge slides the crop inward (no off-page)."""
    crop = centered_report_crop([[5, 5, 10, 10]], width=200, height=200, context_size=512)
    x1, y1, x2, y2 = crop
    assert x1 == 0 and y1 == 0, "crop anchored at origin when box near top-left"
    assert x2 - x1 == 200, "side = min(W,H) when 512 > page dim"


def test_centered_crop_uses_smaller_page_dim():
    """side = min(context, min(W,H))."""
    crop = centered_report_crop([[50, 50, 60, 60]], width=300, height=500, context_size=512)
    x1, y1, x2, y2 = crop
    assert x2 - x1 == 300, "side bounded by the smaller page dimension"
    assert y2 - y1 == 300


def test_centered_crop_empty():
    """No boxes → None."""
    assert centered_report_crop([], 100, 100, 512) is None


# ── padded_union_bounds + localize_box ─────────────────────────────────


def test_padded_union_bounds_clamps_and_pads():
    out = padded_union_bounds([[40, 40, 60, 60]], width=100, height=100, pad=10)
    assert out == [30, 30, 70, 70]


def test_padded_union_bounds_degenerate():
    assert padded_union_bounds([], 100, 100, 10) is None


def test_localize_box_shifts_and_clamps():
    # box [50,50,70,70], origin [40,40] → [10,10,30,30]
    assert localize_box([50, 50, 70, 70], 40, 40, 100, 100) == [10, 10, 30, 30]


def test_localize_box_none_if_degenerate():
    # box fully outside the crop region → all clamped to 0 → degenerate.
    assert localize_box([200, 200, 300, 300], 0, 0, 100, 100) is None


# ── output guard ───────────────────────────────────────────────────────


def test_guard_rejects_uniform_black():
    """Uniform near-black masked output → suspicious."""
    inpainted = np.zeros((20, 20, 3), dtype=np.uint8)  # all black (mean 0 <= 24)
    mask = np.zeros((20, 20), dtype=np.uint8)
    mask[5:15, 5:15] = 255  # 100 masked px >= MIN(16)
    assert output_guard_is_suspicious(inpainted, mask, 20, 20)


def test_guard_rejects_uniform_mid_gray():
    """Uniform mid-gray (mean 128 in [96,160]) → suspicious."""
    inpainted = np.full((20, 20, 3), 128, dtype=np.uint8)
    mask = np.zeros((20, 20), dtype=np.uint8)
    mask[5:15, 5:15] = 255
    assert output_guard_is_suspicious(inpainted, mask, 20, 20)


def test_guard_rejects_uniform_white():
    """Uniform near-white (mean 255 >= 238) → suspicious."""
    inpainted = np.full((20, 20, 3), 255, dtype=np.uint8)
    mask = np.zeros((20, 20), dtype=np.uint8)
    mask[5:15, 5:15] = 255
    assert output_guard_is_suspicious(inpainted, mask, 20, 20)


def test_guard_accepts_textured():
    """Varied luma (high variance) → not suspicious."""
    rng = np.random.RandomState(0)
    inpainted = rng.randint(0, 255, size=(20, 20, 3), dtype=np.uint8)
    mask = np.zeros((20, 20), dtype=np.uint8)
    mask[5:15, 5:15] = 255
    assert not output_guard_is_suspicious(inpainted, mask, 20, 20)


def test_guard_skips_when_too_few_masked():
    """< 16 masked pixels → never suspicious."""
    inpainted = np.zeros((20, 20, 3), dtype=np.uint8)
    mask = np.zeros((20, 20), dtype=np.uint8)
    mask[0, 0] = 255  # 1 px < 16
    assert not output_guard_is_suspicious(inpainted, mask, 20, 20)


# ── AotInpainter end-to-end smoke (skipped if model/ort missing) ───────

_ort = pytest.importorskip("onnxruntime")
_skip_no_model = pytest.mark.skipif(
    not config.AOT_MODEL.exists(),
    reason="AOT model not present",
)


@_skip_no_model
def test_aot_inpainter_fast_path_runs():
    """FAST mode (push-pull) runs without the ONNX session touching the disk path."""
    from backend.inference.inpaint_aot import AotInpainter
    from PIL import Image

    # White image with a black free-text bar.
    arr = np.full((200, 300, 3), 220, dtype=np.uint8)
    arr[80:120, 50:250] = 0  # black bar
    img = Image.fromarray(arr)

    # FAST mode never calls the AOT session; paddle_det=None is fine (unrefined).
    inp = AotInpainter(config.AOT_MODEL, paddle_det=None)
    out, info = inp.inpaint_free_text(img, [[50, 80, 250, 120]], mode="FAST")
    assert out.size == img.size
    assert info["method"] == "fast"
    assert info["boxes"] == 1
