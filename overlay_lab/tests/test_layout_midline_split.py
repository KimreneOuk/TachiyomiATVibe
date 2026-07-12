"""Pure unit tests for the mask-sole-limit overlay layout planner.

Covers the mask-sole-limit policy in ``companion_server/render/layout_planner.py``:
  1. Text is centred on the CHILD OCR box (where source text was detected), not
     on the detector-v4 parent box.
  2. The segmentation mask bbox is the SOLE strict limit on text growth — the
     detector-v4 parent bbox is NOT consulted.
  3. Text + stroke outline must stay STRICTLY inside the mask: the clip rect is
     the mask shrunk inward by strokeWidth/2.
  4. Distinct bubbles (separate mask polygons) never collide — the mask already
     separates them; no parent-bbox separator is needed.

These are pure (no model, no image bytes) and mirror the JVM-testable style of
the Android planner tests.
"""
from __future__ import annotations

import sys
from pathlib import Path

import pytest
from PIL import Image, ImageDraw

# companion_server is a sibling of overlay_lab at the repo root.
_REPO = Path(__file__).resolve().parents[2]
if str(_REPO / "companion_server") not in sys.path:
    sys.path.insert(0, str(_REPO / "companion_server"))

from render import layout_planner as lp  # noqa: E402


# ── Fixtures ──────────────────────────────────────────────────────────

def _blank_draw(page_w: int = 800, page_h: int = 600) -> ImageDraw.ImageDraw:
    return ImageDraw.Draw(Image.new("RGB", (page_w, page_h)))


def _block(text: str, bbox: dict, parent: dict | None = None,
           score: float = 0.9, label: int = 1, direction: str = "LTR",
           mask: list | None = None) -> dict:
    """Build a planner block dict with the minimum required fields."""
    b: dict = {
        "bbox": bbox,
        "text": text,
        "translation": text,
        "label": label,
        "score": score,
        "direction": direction,
        "parentW": 0,
        "parentH": 0,
    }
    if parent is not None:
        b["parentW"] = parent["x2"] - parent["x1"]
        b["parentH"] = parent["y2"] - parent["y1"]
        b["parentBbox"] = parent
    if mask is not None:
        b["segmentation_mask"] = mask
    return b


# ── 1. Mask-sole-limit: parent bbox is NOT the limit ──────────────────

def test_mask_is_sole_limit_parent_bbox_ignored():
    """A parent box LARGER than the mask must NOT expand the text region. The
    mask bbox is the limit, so the origin must lie inside the mask, not the
    parent box."""
    parent = {"x1": 100, "y1": 100, "x2": 500, "y2": 400}  # 400x300 loose box
    # Mask bbox 200x200 — much smaller than the parent.
    mask = [[200, 150], [400, 150], [400, 350], [200, 350]]
    block = _block(
        "Hi",  # short text — no growth
        {"x1": 240, "y1": 180, "x2": 360, "y2": 320},  # child box inside mask
        parent,
        mask=mask,
    )
    layouts = lp.plan([block], 800.0, 600.0, None, _blank_draw())
    assert len(layouts) == 1
    l = layouts[0]
    # Clip (the strict interior) must be inside the mask bbox.
    assert l.clip_rect is not None
    assert l.clip_rect.left >= 200 - 0.5
    assert l.clip_rect.right <= 400 + 0.5
    assert l.clip_rect.top >= 150 - 0.5
    assert l.clip_rect.bottom <= 350 + 0.5


def test_no_splitRegion_assigned_when_mask_is_limit():
    """plan() no longer runs the parent-bbox midline-split. Blocks must not get
    a ``splitRegion`` key even when two children share a parent."""
    parent = {"x1": 100, "y1": 100, "x2": 400, "y2": 300}
    blocks = [
        _block("Hello", {"x1": 120, "y1": 130, "x2": 220, "y2": 250}, parent, score=0.9),
        _block("World", {"x1": 260, "y1": 130, "x2": 360, "y2": 250}, parent, score=0.8),
    ]
    layouts = lp.plan(blocks, 800.0, 600.0, None, _blank_draw())
    for l in layouts:
        assert "splitRegion" not in l.block


# ── 2. Centering: text origin is the child OCR box centre ─────────────

def test_text_origin_is_child_box_center():
    """The text origin must be the child OCR box centre, NOT the parent box
    centre. This is the fix for the upward-shift bug."""
    parent = {"x1": 100, "y1": 100, "x2": 300, "y2": 400}
    # Child box deliberately offset from the parent centre.
    child = {"x1": 150, "y1": 150, "x2": 170, "y2": 170}
    block = _block("X", child, parent)
    layouts = lp.plan([block], 800.0, 600.0, None, _blank_draw())
    assert len(layouts) == 1
    l = layouts[0]
    expected_cx = (150 + 170) / 2  # 160
    expected_cy = (150 + 170) / 2  # 160
    assert l.origin_x == pytest.approx(expected_cx, abs=1.0)
    assert l.origin_y == pytest.approx(expected_cy, abs=1.0)


def test_text_origin_not_parent_center():
    """When child and parent centres differ, origin must follow the CHILD
    centre, proving parent-centre is not used."""
    parent = {"x1": 0, "y1": 0, "x2": 400, "y2": 400}
    child = {"x1": 300, "y1": 300, "x2": 320, "y2": 320}  # centre 310,310
    block = _block("A", child, parent)
    layouts = lp.plan([block], 800.0, 600.0, None, _blank_draw())
    l = layouts[0]
    parent_cx = 200
    parent_cy = 200
    # Origin must be near the child centre (310,310), far from parent (200,200).
    assert abs(l.origin_x - 310) < abs(l.origin_x - parent_cx)
    assert abs(l.origin_y - 310) < abs(l.origin_y - parent_cy)


# ── 3. Strict mask containment: text + outline never exceed the mask ──

def test_clip_is_mask_shrunk_by_stroke_half():
    """The clip rect must be the mask bbox shrunk inward by strokeWidth/2 so
    the stroke outline cannot bleed past the mask edge."""
    mask = [[100, 100], [300, 100], [300, 300], [100, 300]]  # 200x200 mask
    child = {"x1": 140, "y1": 140, "x2": 260, "y2": 260}
    block = _block("Text", child, mask=mask)
    layouts = lp.plan([block], 800.0, 600.0, None, _blank_draw())
    l = layouts[0]
    assert l.clip_rect is not None
    stroke_half = l.stroke_width / 2.0
    # Clip left must be mask.left + stroke_half (100 + stroke_half).
    assert l.clip_rect.left == pytest.approx(100 + stroke_half, abs=0.5)
    assert l.clip_rect.right == pytest.approx(300 - stroke_half, abs=0.5)
    assert l.clip_rect.top == pytest.approx(100 + stroke_half, abs=0.5)
    assert l.clip_rect.bottom == pytest.approx(300 - stroke_half, abs=0.5)


def test_text_extent_does_not_exceed_mask():
    """The rendered text extent must not exceed the mask bbox in any
    dimension."""
    mask = [[100, 100], [260, 100], [260, 260], [100, 260]]  # 160x160 mask
    child = {"x1": 120, "y1": 120, "x2": 240, "y2": 240}
    block = _block("Hello world", child, mask=mask)
    draw = _blank_draw()
    layouts = lp.plan([block], 800.0, 600.0, None, draw)
    l = layouts[0]
    ext = lp.extent_of(l, None, draw)
    assert ext.left >= 100 - 0.5, f"extent left {ext.left} < mask left 100"
    assert ext.right <= 260 + 0.5, f"extent right {ext.right} > mask right 260"
    assert ext.top >= 100 - 0.5, f"extent top {ext.top} < mask top 100"
    assert ext.bottom <= 260 + 0.5, f"extent bottom {ext.bottom} > mask bottom 260"


# ── 4. Distinct masks separate bubbles (no parent-bbox separator needed) ─

def test_distinct_masks_do_not_collide():
    """Two bubbles with separate mask polygons must not collide, even without
    any parent-bbox separator. The masks themselves keep them apart."""
    mask_a = [[100, 100], [200, 100], [200, 200], [100, 200]]
    mask_b = [[300, 100], [400, 100], [400, 200], [300, 200]]
    blocks = [
        _block("Left", {"x1": 120, "y1": 120, "x2": 180, "y2": 180},
               mask=mask_a, score=0.9),
        _block("Right", {"x1": 320, "y1": 120, "x2": 380, "y2": 180},
               mask=mask_b, score=0.8),
    ]
    draw = _blank_draw()
    layouts = lp.plan(blocks, 800.0, 600.0, None, draw)
    assert len(layouts) == 2
    e0 = lp.extent_of(layouts[0], None, draw)
    e1 = lp.extent_of(layouts[1], None, draw)
    assert not e0.overlaps(e1), f"distinct-mask bubbles collided:\n  {e0}\n  {e1}"


def test_distinct_masks_keep_text_inside_their_own_mask():
    """Each bubble's text must stay inside its OWN mask, not bleed into the
    other's region."""
    mask_a = [[100, 100], [200, 100], [200, 200], [100, 200]]
    mask_b = [[300, 100], [400, 100], [400, 200], [300, 200]]
    blocks = [
        _block("Left bubble text", {"x1": 120, "y1": 120, "x2": 180, "y2": 180},
               mask=mask_a, score=0.9),
        _block("Right bubble text", {"x1": 320, "y1": 120, "x2": 380, "y2": 180},
               mask=mask_b, score=0.8),
    ]
    draw = _blank_draw()
    layouts = lp.plan(blocks, 800.0, 600.0, None, draw)
    for l in layouts:
        ext = lp.extent_of(l, None, draw)
        # Must be inside [100,200] or [300,400] x-range — never span across.
        assert not (ext.left < 200 and ext.right > 300), (
            f"text spanned the gap between two masks: {ext}"
        )

# ── 4b. Fused mask: two children sharing ONE mask get split ───────────

def test_fused_mask_two_children_get_split_and_dont_collide():
    """The screenshot case: the seg model fuses two close bubbles into ONE mask
    polygon. Both children get assigned that same mask. Without a separator,
    both texts grow into the full merged mask and collide. The split must
    partition the shared mask bbox so each child gets its own slice."""
    # One fused mask covering both bubbles (the SAME list object on both blocks,
    # mirroring pipeline.render_overlay which assigns the identical polygon).
    fused_mask = [[100, 100], [400, 100], [400, 300], [100, 300]]
    child_a = {"x1": 120, "y1": 130, "x2": 220, "y2": 250}  # centre ~170,190
    child_b = {"x1": 280, "y1": 130, "x2": 380, "y2": 250}  # centre ~330,190
    blocks = [
        _block("Left text here", child_a, score=0.9),
        _block("Right text here", child_b, score=0.8),
    ]
    # Assign the SAME mask object to both (the fused-mask case).
    for b in blocks:
        b["segmentation_mask"] = fused_mask

    draw = _blank_draw()
    layouts = lp.plan(blocks, 800.0, 600.0, None, draw)
    assert len(layouts) == 2

    # Both must have a splitMask (the split fired on the shared mask).
    assert "splitMask" in layouts[0].block
    assert "splitMask" in layouts[1].block

    # The two rendered extents must not collide.
    e0 = lp.extent_of(layouts[0], None, draw)
    e1 = lp.extent_of(layouts[1], None, draw)
    assert not e0.overlaps(e1), (
        f"fused-mask children collided:\n  {layouts[0].text} {e0}\n  {layouts[1].text} {e1}"
    )


def test_fused_mask_split_regions_partition_the_mask_bbox():
    """The splitMask regions must be non-overlapping and together cover the
    full fused mask bbox (no gaps, no overlaps)."""
    fused_mask = [[0, 0], [600, 0], [600, 200], [0, 200]]
    blocks = [
        _block("A", {"x1": 20, "y1": 50, "x2": 120, "y2": 150}, score=0.9),
        _block("B", {"x1": 220, "y1": 50, "x2": 320, "y2": 150}, score=0.8),
        _block("C", {"x1": 420, "y1": 50, "x2": 520, "y2": 150}, score=0.7),
    ]
    for b in blocks:
        b["segmentation_mask"] = fused_mask

    layouts = lp.plan(blocks, 800.0, 600.0, None, _blank_draw())
    regions = [lp._dict_to_rect(l.block["splitMask"]) for l in layouts]

    # Pairwise non-overlap.
    for i in range(len(regions)):
        for j in range(i + 1, len(regions)):
            assert not regions[i].overlaps(regions[j]), f"{i},{j} overlap"
    # Union covers the mask width.
    assert regions[0].left == pytest.approx(0)
    assert regions[-1].right == pytest.approx(600)


def test_fused_mask_stacked_children_split_horizontally():
    """Two stacked bubbles fused into one mask → horizontal cut."""
    fused_mask = [[100, 100], [300, 100], [300, 500], [100, 500]]
    blocks = [
        _block("Top", {"x1": 120, "y1": 130, "x2": 250, "y2": 220}, score=0.9),
        _block("Bottom", {"x1": 120, "y1": 350, "y2": 450, "x2": 250}, score=0.8),
    ]
    for b in blocks:
        b["segmentation_mask"] = fused_mask

    layouts = lp.plan(blocks, 800.0, 600.0, None, _blank_draw())
    sr0 = lp._dict_to_rect(layouts[0].block["splitMask"])
    sr1 = lp._dict_to_rect(layouts[1].block["splitMask"])
    assert not sr0.overlaps(sr1)
    # Horizontal cut (bottom of top region == top of bottom region).
    assert sr0.bottom == pytest.approx(sr1.top)


def test_single_child_mask_not_split():
    """A mask with only one child must NOT get a splitMask."""
    mask = [[100, 100], [300, 100], [300, 300], [100, 300]]
    block = _block("Solo", {"x1": 150, "y1": 130, "x2": 250, "y2": 250}, mask=mask)
    layouts = lp.plan([block], 800.0, 600.0, None, _blank_draw())
    assert "splitMask" not in layouts[0].block


# ── 5. No-mask fallback (parentless free-text) ────────────────────────

def test_no_mask_falls_back_to_child_box():
    """A block with no mask and no parent must use the child box as the region
    and still produce a valid layout (no crash, clip optional)."""
    block = _block("Free text", {"x1": 100, "y1": 100, "x2": 300, "y2": 150},
                   parent=None, label=2)
    layouts = lp.plan([block], 800.0, 600.0, None, _blank_draw())
    assert len(layouts) == 1
    l = layouts[0]
    assert l.origin_x == pytest.approx(200, abs=1.0)  # child centre x
    assert l.origin_y == pytest.approx(125, abs=1.0)  # child centre y


# ── 6. Mask bbox helper ───────────────────────────────────────────────

def test_mask_bbox_rect_returns_bbox():
    block = {"segmentation_mask": [[10, 20], [30, 20], [30, 50], [10, 50]]}
    r = lp._mask_bbox_rect(block)
    assert r is not None
    assert (r.left, r.top, r.right, r.bottom) == (10, 20, 30, 50)


def test_mask_bbox_rect_none_when_no_mask():
    assert lp._mask_bbox_rect({}) is None
    assert lp._mask_bbox_rect({"segmentation_mask": []}) is None
    assert lp._mask_bbox_rect({"segmentation_mask": [[1, 2]]}) is None  # < 3 pts
