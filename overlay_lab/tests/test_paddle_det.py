"""Tests for the PaddleOCR v6 DET faithful port (DbPost + PaddleOcrV6Det).

These verify the pure-numpy DbPostProcess port against the exact Android
invariants, plus an end-to-end smoke test of the ONNX engine when the model
is available (skipped otherwise).
"""
import numpy as np
import pytest

from backend import config
from backend.inference.paddle_det import DbPost, TextLine


# ── binarize: strict greater-than ──────────────────────────────────────


def test_dbpost_binarize_strict_gt():
    """A pixel exactly == thresh must NOT be foreground (Kotlin uses `>`)."""
    prob = np.array([[0.2, 0.2, 0.2], [0.2, 0.2, 0.2]], dtype=np.float32)
    # 6 pixels, all == 0.2 thresh → none foreground → empty.
    lines = DbPost.detect_lines(prob, thresh=0.2, box_thresh=0.0)
    assert lines == [], "strict-gt: ==thresh pixels must not be foreground"


def test_dbpost_above_thresh_detected():
    """Pixels > thresh form one component."""
    # Use a large map so the 4x4 block's bbox area (16) stays < 0.5*W*H.
    prob = np.zeros((20, 20), dtype=np.float32)
    prob[5:9, 5:9] = 0.9  # 16 px block
    lines = DbPost.detect_lines(prob, thresh=0.2, box_thresh=0.5)
    assert len(lines) == 1
    assert lines[0].bbox == [5, 5, 8, 8]  # inclusive max


# ── MIN_AREA_PX filter ─────────────────────────────────────────────────


def test_dbpost_drops_small_components():
    """Components < 16 px are dropped."""
    prob = np.zeros((10, 10), dtype=np.float32)
    prob[0:3, 0:3] = 0.9  # 9 px < 16
    lines = DbPost.detect_lines(prob, thresh=0.2, box_thresh=0.5)
    assert lines == [], "<16px component must be dropped"


# ── meanScore is foreground mean, not bbox mean ────────────────────────


def test_dbpost_mean_score_is_foreground_mean():
    """meanScore = mean(prob over foreground pixels), not over the bbox."""
    # Large map so the block passes the area filter.
    prob = np.zeros((20, 20), dtype=np.float32)
    prob[5:9, 5:9] = 0.9        # 16 px foreground
    prob[5, 5] = 0.3            # one dim foreground pixel
    lines = DbPost.detect_lines(prob, thresh=0.2, box_thresh=0.0)
    assert len(lines) == 1
    # foreground mean = (0.3 + 15*0.9)/16 = 13.8/16 = 0.8625
    assert abs(lines[0].mean_score - (0.3 + 15 * 0.9) / 16) < 1e-5


# ── area filter ────────────────────────────────────────────────────────


def test_dbpost_area_filter_drops_huge_component():
    """A component whose bbox area > 0.5*W*H is dropped."""
    prob = np.full((6, 6), 0.9, dtype=np.float32)  # whole image = 36 px
    # bbox area = 6*6 = 36 > 0.5*6*6 = 18 → dropped
    lines = DbPost.detect_lines(prob, thresh=0.2, box_thresh=0.5)
    assert lines == [], "component filling >50% of the map must be dropped"


# ── merge_line_fragments ───────────────────────────────────────────────


def test_merge_same_line_horizontal():
    """Two close horizontal fragments on the same line merge."""
    # Two boxes at y=[2,5], same row, small gap.
    a = TextLine(bbox=[0, 2, 4, 5], mean_score=0.9)
    b = TextLine(bbox=[6, 2, 10, 5], mean_score=0.8)  # gap = 6-4 = 2 <= max(h)
    merged = DbPost.merge_line_fragments([a, b])
    assert len(merged) == 1, "close same-line horizontal fragments should merge"
    assert merged[0].bbox == [0, 2, 10, 5]


def test_merge_no_merge_far_apart():
    """Two boxes far apart on the row axis stay separate."""
    a = TextLine(bbox=[0, 2, 4, 5], mean_score=0.9)
    b = TextLine(bbox=[100, 2, 104, 5], mean_score=0.8)  # gap 96 >> height
    merged = DbPost.merge_line_fragments([a, b])
    assert len(merged) == 2, "far-apart boxes should not merge"


def test_merge_no_merge_different_rows():
    """Two boxes on different rows (vertical offset > 0.6*min height) stay separate."""
    a = TextLine(bbox=[0, 0, 10, 3], mean_score=0.9)
    b = TextLine(bbox=[0, 20, 10, 23], mean_score=0.8)  # center diff 20 >> 0.6*3
    merged = DbPost.merge_line_fragments([a, b])
    assert len(merged) == 2


# ── back_project ───────────────────────────────────────────────────────


def test_back_project_clamps_to_bounds():
    """Out-of-bounds coordinates clamp to [0, dim-1]."""
    # scale_x such that 100*5 = 500 → clamp to w-1=49.
    out = DbPost.back_project([0, 0, 100, 100], scale_x=5.0, scale_y=5.0, w=50, h=50)
    assert out == [0, 0, 49, 49]


def test_back_project_reorders_min_max():
    """A box whose scaled x2 < x1 gets reordered to (min, max)."""
    # scale 0.5: box [4,4,2,2] → x1=2,x2=1 → reorder [1,1,2,2]
    out = DbPost.back_project([4, 4, 2, 2], scale_x=0.5, scale_y=0.5, w=20, h=20)
    assert out[0] <= out[2] and out[1] <= out[3], "back_project must reorder to min/max"


def test_back_project_zero_scale_returns_zero():
    """scale <= 0 maps everything to 0 (guard against div-by-zero upstream)."""
    out = DbPost.back_project([5, 5, 10, 10], scale_x=0.0, scale_y=0.0, w=20, h=20)
    assert out == [0, 0, 0, 0]


# ── end-to-end ONNX smoke (skipped if model/ort missing) ───────────────

_ort = pytest.importorskip("onnxruntime")
_skip_no_model = pytest.mark.skipif(
    not config.PADDLE_DET_MODEL.exists(),
    reason="Paddle DET model not present",
)


@_skip_no_model
def test_paddle_det_end_to_end_shape():
    """The ONNX engine returns line boxes within the input image bounds."""
    from backend.inference.paddle_det import PaddleOcrV6Det
    from PIL import Image

    # Synthetic image: white background, one horizontal black bar (fake line).
    img = Image.new("RGB", (200, 60), (255, 255, 255))
    arr = np.array(img)
    arr[20:40, 10:190] = 0
    img = Image.fromarray(arr)

    det = PaddleOcrV6Det(config.PADDLE_DET_MODEL)
    lines = det.detect_lines(img)
    assert isinstance(lines, list)
    for tl in lines:
        assert len(tl.bbox) == 4
        x1, y1, x2, y2 = tl.bbox
        assert 0 <= x1 <= x2 <= 200, f"x out of bounds: {tl.bbox}"
        assert 0 <= y1 <= y2 <= 60, f"y out of bounds: {tl.bbox}"
        assert 0.0 <= tl.mean_score <= 1.0
