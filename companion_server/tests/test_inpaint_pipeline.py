"""Tests for the honest, no-fallback inpainting pipeline (Cleaner).

These pin the core behavior: classical tier-1 always runs, neural QUALITY
tier failures are reported (never silently substituted), and status is honest.
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from inference.inpaint_pipeline import Cleaner


def _solid_page(color=(220, 220, 220), size=(400, 500)):
    return Image.new("RGB", size, color)


def _draw_box(image, box, color):
    arr = np.array(image)
    x1, y1, x2, y2 = box
    arr[y1:y2, x1:x2] = color
    return Image.fromarray(arr)


def _mask_box(x1, y1, x2, y2, label):
    return {"x1": x1, "y1": y1, "x2": x2, "y2": y2, "label": label}


def test_no_mask_boxes_is_skipped_not_ready():
    """A page with nothing to erase is SKIPPED, and the image is untouched."""
    page = _solid_page()
    cleaner = Cleaner(neural=None)
    outcome = cleaner.clean(page, [], [], mode="QUALITY")
    assert outcome.status == "SKIPPED"
    assert outcome.engine == "skipped"
    assert outcome.failures == []


def test_neural_unavailable_quality_textured_box_is_partial_not_classical_swap():
    """QUALITY + a textured free-text box + no neural ⇒ PARTIAL, box FAILED.

    This is the key no-fallback guarantee: the box is NOT silently cleaned
    with classical and reported READY.
    """
    # A large label-2 box on a noisy/textured background ⇒ classified as
    # neural candidate (not flat, not small).
    page = _solid_page(color=(120, 120, 120))
    arr = np.array(page)
    rng = np.random.default_rng(0)
    arr[100:300, 100:300] = rng.integers(0, 255, size=(200, 200, 3), dtype=np.uint8)
    page = Image.fromarray(arr)
    original_pixels = np.array(page).copy()

    mask = [_mask_box(110, 110, 290, 290, label=2)]
    cleaner = Cleaner(neural=None)
    outcome = cleaner.clean(page, mask, [], mode="QUALITY")

    assert outcome.status == "PARTIAL"
    assert outcome.engine == "partial:neural-unavailable"
    assert len(outcome.failures) == 1
    assert outcome.failures[0]["stage"] == "inpaint/neural"
    # The failing box keeps its original pixels (visually obvious failure).
    assert np.array_equal(np.array(outcome.image), original_pixels)


def test_bubble_box_is_cleaned_classically_without_neural():
    """A bubble (label 0) is routed through the classical tier even when neural
    is unavailable — classical is the spec for bubbles, not a fallback.

    Asserts routing + honest READY status (no PARTIAL, no failure entries),
    not the classical cleaner's internal pixel math.
    """
    page = _solid_page(color=(255, 255, 255))
    page = _draw_box(page, (40, 40, 200, 160), (0, 0, 0))  # dark text inside bubble

    mask = [_mask_box(20, 20, 220, 180, label=0)]
    cleaner = Cleaner(neural=None)
    outcome = cleaner.clean(page, mask, [], mode="QUALITY")

    assert outcome.status == "READY"
    assert outcome.engine.startswith("classical")
    assert outcome.failures == []


def test_fast_mode_uses_classical_fast_engine():
    page = _solid_page(color=(255, 255, 255))
    page = _draw_box(page, (40, 40, 120, 120), (0, 0, 0))
    mask = [_mask_box(20, 20, 160, 160, label=0)]
    outcome = Cleaner(neural=None).clean(page, mask, [], mode="FAST")
    assert outcome.engine == "classical-fast"
    assert outcome.status == "READY"


def test_neural_runtime_failure_is_logged_and_partial():
    """If neural raises on a box, it is logged + the box is PARTIAL
    (no classical substitution)."""
    page = _solid_page(color=(120, 120, 120))
    arr = np.array(page)
    rng = np.random.default_rng(1)
    arr[100:300, 100:300] = rng.integers(0, 255, size=(200, 200, 3), dtype=np.uint8)
    page = Image.fromarray(arr)

    class BoomNeural:
        def inpaint_free_text_box(self, image, box):
            raise RuntimeError("model exploded")

    mask = [_mask_box(110, 110, 290, 290, label=2)]
    outcome = Cleaner(neural=BoomNeural()).clean(page, mask, [], mode="QUALITY")
    assert outcome.status == "PARTIAL"
    assert outcome.engine == "partial:neural-failed"
    assert outcome.failures[0]["reason"].startswith("RuntimeError")


def test_bubble_group_is_cleaned_via_containment_fill_with_real_change():
    """A parented bubble (label 0) + its text box (label 1) is cleaned by the
    boundary-aware containment fill, and the text region genuinely changes.

    Regression guard: an earlier uint8-overflow bug in boundary.collect_stats
    made fill_contained throw (silently swallowed by a try/except), so bubble
    cleaning was a no-op. This asserts the real fix end-to-end.
    """
    page = Image.new("RGB", (400, 500), (200, 200, 200))
    draw = ImageDraw.Draw(page)
    # White bubble (the parent) with dark text strokes inside.
    draw.ellipse([120, 200, 280, 380], fill=(255, 255, 255), outline=(60, 60, 60), width=2)
    arr = np.array(page)
    arr[240:340, 160:240] = 20  # dark text region
    page = Image.fromarray(arr)
    original = np.array(page).copy()

    mask = [
        _mask_box(120, 200, 280, 380, label=0),  # bubble
        _mask_box(160, 240, 240, 340, label=1),  # text inside
    ]
    outcome = Cleaner(neural=None).clean(page, mask, [], mode="QUALITY")
    assert outcome.status == "READY"
    assert outcome.failures == []
    text_region = np.array(outcome.image)[240:340, 160:240]
    assert text_region.mean() > original[240:340, 160:240].mean()

