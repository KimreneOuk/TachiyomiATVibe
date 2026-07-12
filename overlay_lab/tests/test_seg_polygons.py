"""Tests for segment_bubbles_polygons — native masks.xy exposure.

Contract:
  segment_bubbles_polygons(image_rgb, model_path) -> list[list[list[float]]]
    Returns per-bubble polygons from r.masks.xy (original-image coords).
    Each polygon = list of [x, y] points. No flat mask, no resize, no findContours.

  Why masks.xy: ultralytics returns these in original-image coordinates natively,
  lossless. The frontend draws them via Canvas path2D as filled semi-transparent
  overlays.
"""
import glob
from pathlib import Path

import pytest
from PIL import Image

from backend.inpaint.bubble_segmentation import segment_bubbles_polygons, _auto_imgsz
from backend import config


REPO_ROOT = Path(__file__).resolve().parents[2]
SAMPLE_PAGE = sorted(glob.glob(str(
    REPO_ROOT / "tools" / "Okiraku*" / "page-003.jpg"
)))


@pytest.fixture(scope="module")
def model_path():
    mp = config.find_seg_model("best_int8.onnx")
    if mp is None or not mp.exists():
        pytest.skip("int8 seg model not on disk")
    return str(mp)


@pytest.fixture(scope="module")
def sample_image():
    if not SAMPLE_PAGE:
        pytest.skip("Okiraku page-003.jpg not found")
    return Image.open(SAMPLE_PAGE[0]).convert("RGB")


# ── imgsz selection ───────────────────────────────────────────────────


def test_auto_imgsz_dynamic_caps_to_balanced_size():
    """Dynamic best.onnx uses native-ish resolution capped at balanced 1280."""
    img = Image.new("RGB", (1351, 1920))
    assert _auto_imgsz(img, 0, "best.onnx") == 1280


def test_auto_imgsz_fixed_int8_stays_640():
    """Fixed-shape int8 ONNX must stay 640 or ONNX Runtime rejects it."""
    img = Image.new("RGB", (1351, 1920))
    assert _auto_imgsz(img, 0, "best_int8.onnx") == 640


# ── return shape ──────────────────────────────────────────────────────


def test_returns_list_of_polygons(model_path, sample_image):
    polys = segment_bubbles_polygons(sample_image, model_path)
    assert isinstance(polys, list)
    for poly in polys:
        assert isinstance(poly, list)
        for pt in poly:
            assert isinstance(pt, list)
            assert len(pt) == 2


def test_polygons_have_at_least_3_points(model_path, sample_image):
    """A valid polygon needs >=3 points to enclose area."""
    polys = segment_bubbles_polygons(sample_image, model_path)
    for poly in polys:
        assert len(poly) >= 3, f"polygon with {len(poly)} points is degenerate"


# ── coords in original-image space ────────────────────────────────────


def test_coords_in_original_image_space(model_path, sample_image):
    """masks.xy are in original-image coords — no letterbox shift."""
    w, h = sample_image.size
    polys = segment_bubbles_polygons(sample_image, model_path)
    for poly in polys:
        for x, y in poly:
            assert 0 <= x <= w, f"x={x} outside image width {w}"
            assert 0 <= y <= h, f"y={y} outside image height {h}"


# ── detection count (page-003 has ~10 bubbles on int8) ────────────────


def test_finds_multiple_polygons(model_path, sample_image):
    polys = segment_bubbles_polygons(sample_image, model_path)
    assert len(polys) >= 3, f"expected >=3 bubble polygons, got {len(polys)}"
