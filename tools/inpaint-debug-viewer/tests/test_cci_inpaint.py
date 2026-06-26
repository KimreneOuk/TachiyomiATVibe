"""Pure-logic tests for cci_inpaint (numpy only, no cv2/scipy)."""

import math
import numpy as np
from cci_inpaint import (
    CCIOptions, Tier, BackgroundStats,
    dilate_disk, chamfer_distance, feather_alpha_field,
    clamp_box, expand_tiny_free_boxes, build_cluster_mask,
    sample_background, detect_screentone, classify_tier,
    local_background_fill, telea_inpaint,
    feather_blend_fallback,
    is_uniform_output,
    inpaint_coherent,
)


# ---------------------------------------------------------------------------
# expand_tiny_free_boxes
# ---------------------------------------------------------------------------

def test_expand_free_below_floor():
    opts = CCIOptions(free_min_side=18, free_long_floor=22)
    boxes = [[10, 10, 20, 20]]  # 10x10, both sides below floor
    is_free = [True]
    result = expand_tiny_free_boxes(boxes, is_free, 100, 100, opts)
    x1, y1, x2, y2 = result[0]
    w, h = x2 - x1, y2 - y1
    assert min(w, h) >= 18, f"short side {min(w,h)} < 18"
    assert max(w, h) >= 22, f"long side {max(w,h)} < 22"


def test_expand_clipped_to_page():
    opts = CCIOptions(free_min_side=18, free_long_floor=22)
    boxes = [[0, 0, 2, 2]]
    is_free = [True]
    result = expand_tiny_free_boxes(boxes, is_free, 20, 20, opts)
    x1, y1, x2, y2 = result[0]
    assert x1 >= 0 and y1 >= 0 and x2 <= 20 and y2 <= 20


def test_parented_box_not_expanded():
    opts = CCIOptions(free_min_side=18, free_long_floor=22)
    boxes = [[5, 5, 10, 10]]
    is_free = [False]
    result = expand_tiny_free_boxes(boxes, is_free, 100, 100, opts)
    assert result[0] == [5, 5, 10, 10], "parented box should not expand"


def test_box_already_at_floor_unchanged():
    opts = CCIOptions(free_min_side=18, free_long_floor=22)
    boxes = [[20, 20, 40, 50]]  # 20x30, both above floor
    is_free = [True]
    result = expand_tiny_free_boxes(boxes, is_free, 200, 200, opts)
    assert result[0] == [20, 20, 40, 50]


def test_expand_multiple_boxes():
    opts = CCIOptions(free_min_side=18, free_long_floor=22)
    boxes = [[0, 0, 5, 5], [10, 10, 30, 30], [0, 0, 50, 50]]
    is_free = [True, False, True]
    result = expand_tiny_free_boxes(boxes, is_free, 200, 200, opts)
    assert len(result) == 3
    # First: expanded
    assert result[0] != [0, 0, 5, 5]
    # Second: unchanged (not free)
    assert result[1] == [10, 10, 30, 30]
    # Third: already >= floor, unchanged
    assert result[2] == [0, 0, 50, 50]


# ---------------------------------------------------------------------------
# classify_tier
# ---------------------------------------------------------------------------

def _stats(
    gray_mean=128.0, gray_std=5.0, near_white=0.0, dark=0.0,
    edge=0.0, sat=0.0, count=100,
) -> BackgroundStats:
    return BackgroundStats(
        median_color=(128, 128, 128),
        gray_mean=gray_mean,
        gray_std=gray_std,
        near_white_ratio=near_white,
        dark_ratio=dark,
        edge_density=edge,
        saturation_mean=sat,
        sample_count=count,
    )


def test_classify_flat_white():
    s = _stats(near_white=0.8, gray_std=10.0)
    assert classify_tier(s, False) == Tier.FLAT


def test_classify_flat_colored():
    s = _stats(gray_std=10.0, near_white=0.0)
    assert classify_tier(s, False) == Tier.FLAT


def test_classify_dark_flat():
    s = _stats(dark=0.6, gray_std=12.0)
    assert classify_tier(s, False) == Tier.FLAT


def test_classify_screentone_forces_textured():
    s = _stats(gray_std=25.0, sat=10.0, edge=0.1)
    assert classify_tier(s, True) == Tier.TEXTURED


def test_classify_color():
    s = _stats(gray_std=30.0, sat=40.0)
    assert classify_tier(s, False) == Tier.COLOR


def test_classify_high_std_textured():
    s = _stats(gray_std=40.0, sat=5.0, edge=0.15)
    assert classify_tier(s, False) == Tier.TEXTURED


def test_classify_low_edge_flat():
    s = _stats(gray_std=20.0, sat=25.0, edge=0.04)
    assert classify_tier(s, False) == Tier.FLAT


# ---------------------------------------------------------------------------
# is_uniform_output
# ---------------------------------------------------------------------------

def test_uniform_white_block():
    rgb = np.full((20, 20, 3), 255, dtype=np.uint8)
    mask = np.ones((20, 20), dtype=np.uint8) * 255
    assert is_uniform_output(rgb, mask) is True


def test_uniform_mid_gray():
    rgb = np.full((20, 20, 3), 128, dtype=np.uint8)
    mask = np.ones((20, 20), dtype=np.uint8) * 255
    assert is_uniform_output(rgb, mask) is True


def test_gradient_not_uniform():
    rgb = np.zeros((20, 20, 3), dtype=np.uint8)
    for x in range(20):
        rgb[:, x] = x * 12
    mask = np.ones((20, 20), dtype=np.uint8) * 255
    assert is_uniform_output(rgb, mask) is False


def test_too_few_pixels():
    rgb = np.full((10, 10, 3), 255, dtype=np.uint8)
    mask = np.zeros((10, 10), dtype=np.uint8)
    mask[0, 0] = 255  # only 1 pixel
    assert is_uniform_output(rgb, mask) is False


def test_uniform_black_not_suspicious():
    rgb = np.full((20, 20, 3), 50, dtype=np.uint8)
    mask = np.ones((20, 20), dtype=np.uint8) * 255
    # Low variance, low channel delta, but mean=50 which is not in [96,160] and < 238
    assert is_uniform_output(rgb, mask) is False


# ---------------------------------------------------------------------------
# dilate_disk
# ---------------------------------------------------------------------------

def test_dilate_disk_single_pixel():
    mask = np.zeros((21, 21), dtype=np.uint8)
    mask[10, 10] = 255
    r = 3
    result = dilate_disk(mask, r)
    # Pixel at distance <= r should be set
    assert result[10, 10] == 255, "center should be set"
    assert result[10, 13] == 255, "dx=3 should be set"
    assert result[10, 14] == 0, "dx=4 should be unset"
    # Check disk shape: (dx,dy) with dx²+dy² ≤ 9
    for dy in range(-r, r + 1):
        for dx in range(-r, r + 1):
            if dx * dx + dy * dy <= r * r:
                assert result[10 + dy, 10 + dx] == 255, \
                    f"px ({dx},{dy}) within r={r} should be set"
            elif abs(dx) <= r and abs(dy) <= r:
                assert result[10 + dy, 10 + dx] == 0, \
                    f"px ({dx},{dy}) outside r={r} should be unset"


def test_dilate_disk_radius_zero():
    mask = np.zeros((10, 10), dtype=np.uint8)
    mask[5, 5] = 255
    result = dilate_disk(mask, 0)
    assert np.array_equal(result, mask)


# ---------------------------------------------------------------------------
# feather_alpha_field
# ---------------------------------------------------------------------------

def test_feather_alpha_inside_mask():
    mask = np.zeros((20, 20), dtype=np.uint8)
    mask[5:15, 5:15] = 255
    alpha = feather_alpha_field(mask, 4)
    assert np.all(alpha[mask > 0] == 1.0), "inside mask should be 1"


def test_feather_alpha_outside_ramp():
    mask = np.zeros((30, 30), dtype=np.uint8)
    mask[10:20, 10:20] = 255
    alpha = feather_alpha_field(mask, 4)
    # Far away pixels should be 0
    assert alpha[0, 0] == 0.0, "far pixel should be 0"
    # A pixel just outside mask should have alpha between 0 and 1
    assert 0 < alpha[9, 15] <= 1.0, "edge pixel should ramp"


def test_feather_alpha_empty_mask():
    mask = np.zeros((10, 10), dtype=np.uint8)
    alpha = feather_alpha_field(mask, 4)
    assert np.all(alpha == 0.0), "empty mask should give all zeros"


# ---------------------------------------------------------------------------
# build_cluster_mask
# ---------------------------------------------------------------------------

def test_build_cluster_mask_single_box():
    crop = np.zeros((50, 50, 3), dtype=np.uint8)
    boxes = [[10, 10, 30, 30]]
    opts = CCIOptions(mask_pad_min=2, mask_pad_max=8, dilate_radius=1)
    mask = build_cluster_mask(crop, boxes, opts)
    # Box area should be filled
    assert np.any(mask > 0)
    # Box interior dilated
    assert mask[10, 10] == 255 or mask[11, 11] == 255


def test_build_cluster_mask_clips_to_crop():
    crop = np.zeros((30, 30, 3), dtype=np.uint8)
    boxes = [[-10, -10, 50, 50]]  # extends beyond crop
    opts = CCIOptions(mask_pad_min=0, mask_pad_max=0, dilate_radius=0)
    mask = build_cluster_mask(crop, boxes, opts)
    assert mask.shape == (30, 30)
    assert mask[0, 0] == 255
    assert mask[29, 29] == 255


def test_build_cluster_mask_multiple_boxes():
    crop = np.zeros((60, 60, 3), dtype=np.uint8)
    boxes = [[5, 5, 15, 15], [40, 40, 55, 55]]
    opts = CCIOptions(mask_pad_min=0, mask_pad_max=0, dilate_radius=0)
    mask = build_cluster_mask(crop, boxes, opts)
    assert mask[10, 10] == 255
    assert mask[48, 48] == 255
    assert mask[30, 30] == 0  # between boxes


# ---------------------------------------------------------------------------
# chamfer_distance
# ---------------------------------------------------------------------------

def test_chamfer_distance_inside_zero():
    mask = np.zeros((20, 20), dtype=np.uint8)
    mask[10, 10] = 255
    dist = chamfer_distance(mask)
    assert dist[10, 10] == 0.0, "inside distance is 0"


def test_chamfer_distance_monotonic():
    mask = np.zeros((30, 30), dtype=np.uint8)
    mask[15, 15] = 255
    dist = chamfer_distance(mask)
    # Distance should increase as we move away
    assert dist[15, 15] == 0.0
    assert dist[15, 16] > 0
    assert dist[15, 18] > dist[15, 16]


# ---------------------------------------------------------------------------
# detect_screentone (simple check)
# ---------------------------------------------------------------------------

def test_detect_screentone_noisy():
    rng = np.random.default_rng(42)
    noise = rng.normal(128, 30, 200)
    assert detect_screentone(noise) is False


def test_detect_screentone_too_short():
    arr = np.array([128, 130, 125])
    assert detect_screentone(arr) is False


# ---------------------------------------------------------------------------
# sample_background
# ---------------------------------------------------------------------------

def test_sample_background_ring():
    crop = np.zeros((30, 30, 3), dtype=np.uint8)
    crop[5:25, 5:25] = 100  # inner region
    mask = np.ones((30, 30), dtype=np.uint8)
    mask[10:20, 10:20] = 0  # hole
    stats = sample_background(crop, mask)
    assert stats.sample_count > 0


def test_sample_background_all_masked():
    crop = np.full((10, 10, 3), 255, dtype=np.uint8)
    mask = np.ones((10, 10), dtype=np.uint8) * 255
    stats = sample_background(crop, mask)
    assert stats.sample_count == 0
    assert stats.median_color == (255, 255, 255)


# ---------------------------------------------------------------------------
# local_background_fill (smoke test)
# ---------------------------------------------------------------------------

def test_local_background_fill_basic():
    crop = np.zeros((20, 20, 3), dtype=np.uint8)
    crop[:, :] = [100, 100, 100]
    mask = np.zeros((20, 20), dtype=np.uint8)
    mask[5:15, 5:15] = 255  # hole
    fill = local_background_fill(crop, mask)
    assert fill.shape == crop.shape
    assert fill.dtype == np.uint8
    # Non-masked pixels unchanged
    assert np.array_equal(fill[:5, :5], crop[:5, :5])


# ---------------------------------------------------------------------------
# feather_blend_fallback
# ---------------------------------------------------------------------------

def test_feather_blend_fallback_shape():
    dst = np.full((20, 20, 3), 255, dtype=np.uint8)
    src = np.full((20, 20, 3), 0, dtype=np.uint8)
    mask = np.zeros((20, 20), dtype=np.uint8)
    mask[5:15, 5:15] = 255
    result = feather_blend_fallback(dst, src, mask, 4)
    assert result.shape == (20, 20, 3)
    assert result.dtype == np.uint8


def test_feather_blend_fallback_ring_zero_alpha():
    dst = np.full((20, 20, 3), 200, dtype=np.uint8)
    src = np.full((20, 20, 3), 50, dtype=np.uint8)
    mask = np.zeros((20, 20), dtype=np.uint8)
    mask[5:15, 5:15] = 255
    result = feather_blend_fallback(dst, src, mask, 4)
    # Far away pixels should equal dst
    assert np.array_equal(result[0, 0], dst[0, 0])


# ---------------------------------------------------------------------------
# Orchestration: inpaint_coherent returns (result, diagnostics, union_mask) and
# the union mask matches the regions actually changed, and dst strokes are
# erased (not preserved) under the default non-mixing Poisson blend.
# ---------------------------------------------------------------------------

def test_inpaint_coherent_returns_union_mask_and_erases_strokes():
    h, w = 80, 80
    rgb = np.full((h, w, 3), 200, dtype=np.uint8)
    # dark text strokes inside a free-text box (the "hair"/body ink)
    rgb[30:50, 30:50] = 20
    orig = rgb.copy()
    clusters = [{
        "boxes": [[30, 30, 50, 50]],
        "is_free": [True],
        "parent_rect": None,
    }]

    result, diag, union_mask = inpaint_coherent(
        rgb, clusters, opts=CCIOptions(), quality=False,
    )
    # 3-tuple contract + shapes/dtypes
    assert isinstance(diag, list) and len(diag) == 1
    assert result.shape == rgb.shape and result.dtype == np.uint8
    assert union_mask.shape == (h, w) and union_mask.dtype == np.uint8

    # The union mask must cover the text box region (the actual erase target).
    assert union_mask[35:45, 35:45].max() > 0

    # The masked region must have changed AND the dark stroke must be erased
    # (mean raised well above the ink value 20) — i.e. no preserved "hair".
    region = result[35:45, 35:45, 0].astype(int)
    assert not np.array_equal(region, orig[35:45, 35:45, 0].astype(int))
    assert region.mean() > 120, f"stroke should be erased, mean={region.mean()}"

    # Pixels OUTSIDE the union mask must be untouched.
    outside = (union_mask == 0)
    assert np.array_equal(result[outside], orig[outside])
