"""Logic-parity validation for text_render.py vs the Kotlin TextLayoutPlanner.

Ports the input→expected-output cases from ``TextLayoutPlannerTest.kt`` so the
Python render port is pinned to the SAME invariants the device-side planner is.
A deterministic ``FakeMeasurer`` (identical definition to the Kotlin test's
``FakeMeasurer`` at TextLayoutPlannerTest.kt:23-28) is used on the Python side,
eliminating PIL-vs-Paint metric noise — so a passing suite means the layout
*math* is byte-for-byte faithful, which is the whole point of Phase 0.

Each test below maps 1:1 to a Kotlin case (cited in its docstring) and asserts
the same documented expectation (origin, fontSize, drawAlign, clipRect,
isVertical, and the structural no-overlap invariant).
"""

import math

import text_render as tr
from text_render import Block, FakeMeasurer, FloatRect, TextAlign, extent_of


def _block(x, y, w, h, text, score=1.0, direction="LTR", label=1):
    return Block(
        text="",
        translation=text,
        width=w, height=h, x=x, y=y,
        sym_height=1.0, sym_width=1.0, angle=0.0,
        label=label, score=score, direction=direction,
    )


def _rect_eq(a: FloatRect, b) -> bool:
    return (abs(a.left - b[0]) < 0.5 and abs(a.top - b[1]) < 0.5
            and abs(a.right - b[2]) < 0.5 and abs(a.bottom - b[3]) < 0.5)


def test_empty_block_list_returns_empty_plan():
    # TextLayoutPlannerTest.kt:57-59
    assert tr.plan([], 1000.0, 1000.0, 1, False, FakeMeasurer()) == []


def test_blank_translation_blocks_are_dropped_and_excluded_from_obstacle_set():
    # TextLayoutPlannerTest.kt:62-70
    blank = _block(100, 100, 100, 40, "", score=0.9)
    other = _block(110, 100, 100, 40, "Hi", score=0.5)
    plan = tr.plan([blank, other], 1000.0, 1000.0, 1, False, FakeMeasurer())
    assert len(plan) == 1
    assert plan[0].text == "Hi"


def test_single_isolated_block_keeps_box_centre():
    # TextLayoutPlannerTest.kt:73-84
    b = _block(200, 200, 120, 60, "Hi", score=0.8)
    plan = tr.plan([b], 1000.0, 1000.0, 1, False, FakeMeasurer())
    assert len(plan) == 1
    l = plan[0]
    assert l.clip_rect is None
    assert l.origin_x == (200.0 + 120.0 / 2.0)
    assert l.origin_y == (200.0 + 60.0 / 2.0)


def test_two_adjacent_blocks_do_not_overlap_after_planning():
    # TextLayoutPlannerTest.kt:87-102 (Defect 1)
    left = _block(100, 900, 140, 70, "Hello world text", score=0.9)
    right = _block(360, 900, 140, 70, "Other long line", score=0.8)
    plan = tr.plan([left, right], 800.0, 1000.0, 1, False, FakeMeasurer())
    assert len(plan) == 2
    m = FakeMeasurer()
    a = extent_of(plan[0], m)
    b = extent_of(plan[1], m)
    assert not a.overlaps(b)


def test_tall_box_shifts_away_from_neighbour():
    # TextLayoutPlannerTest.kt:105-131 (Defect 2)
    tall = _block(170, 100, 50, 300, "A reasonably long line", score=0.5)
    neighbour = _block(240, 100, 120, 60, "Neighbour bubble text", score=0.95)
    plan = tr.plan([tall, neighbour], 800.0, 600.0, 1, False, FakeMeasurer())
    tall_layout = next(it for it in plan if it.text == "A reasonably long line")
    neighbour_layout = next(it for it in plan if it.text == "Neighbour bubble text")
    m = FakeMeasurer()
    tall_ext = extent_of(tall_layout, m)
    neighbour_ext = extent_of(neighbour_layout, m)
    assert not tall_ext.overlaps(neighbour_ext)
    assert (tall_ext.left + tall_ext.right) / 2.0 < 195.0


def test_tall_isolated_box_keeps_original_centre():
    # TextLayoutPlannerTest.kt:134-145
    tall = _block(375, 100, 50, 300, "Line", score=0.7)
    plan = tr.plan([tall], 800.0, 600.0, 1, False, FakeMeasurer())
    l = plan[0]
    assert l.origin_x == 400.0
    assert l.clip_rect is None


def test_free_text_stays_anchored_to_ocr_center_when_reshaped():
    # User-visible policy: free text/SFX may grow or reshape for legibility, but
    # its draw origin stays attached to the original OCR box center.
    free = _block(170, 100, 50, 300, "A reasonably long line", score=0.8, label=2)
    plan = tr.plan([free], 800.0, 600.0, 1, False, FakeMeasurer())
    l = plan[0]
    assert l.origin_x == 195.0
    assert l.origin_y == 250.0
    assert l.draw_align == TextAlign.CENTER
    assert l.ocr_anchored is True


def test_vertical_source_latin_parented_block_stays_anchored_to_ocr_center():
    # A tall Japanese source box translated to Latin should not drift to the
    # parent/bubble center; it stays centered on the OCR box.
    parented = Block(
        text="", translation="English translation", width=50.0, height=300.0, x=200.0, y=100.0,
        sym_height=1.0, sym_width=1.0, angle=0.0, label=1, score=0.8,
        parent_x=100.0, parent_y=50.0, parent_width=400.0, parent_height=400.0,
        direction="TTB",
    )
    plan = tr.plan([parented], 800.0, 600.0, 1, False, FakeMeasurer())
    l = plan[0]
    assert l.is_vertical is False
    assert l.origin_x == 225.0
    assert l.origin_y == 250.0
    assert l.draw_align == TextAlign.CENTER
    assert l.ocr_anchored is True


def test_long_text_below_legibility_floor_is_contained_to_initial_box():
    # TextLayoutPlannerTest.kt:148-162 (Defect 3 + containment).
    # Parity spec fields (plan Task 0.5): clipRect is set to the hard containment
    # box [400,400,430,430] (TextLayoutPlanner.kt:410-424), so the rendered
    # extent stays within the initial 30x30 box. The containment clip reassigns
    # safeW/safeH to the clip rect width (TextLayoutPlanner.kt:421-422), so
    # safeW is NOT asserted here — only the parity-specified clipRect + the
    # containment invariant.
    small = _block(400, 400, 30, 30, "A very long translation sentence", score=0.8)
    plan = tr.plan([small], 1500.0, 1500.0, 1, False, FakeMeasurer())
    l = plan[0]
    assert l.clip_rect is not None
    assert _rect_eq(l.clip_rect, (400.0, 400.0, 430.0, 430.0))
    # Containment is enforced by the clip at DRAW time (extent_of reflects the
    # unclipped text footprint, which overflows the box — that is precisely why
    # the clip is set). Parity = the clipRect above; fontSize is lifted to the
    # legibility floor inside the clip (TextLayoutPlanner.kt:423).
    assert l.font_size_px == 21.0


def test_parented_block_never_bleeds_past_parent():
    # TextLayoutPlannerTest.kt:165-197
    px, py, pw, ph = 100.0, 200.0, 200.0, 80.0
    parented = Block(
        text="", translation="A somewhat longer text that may try to expand past the parent box boundary",
        width=30.0, height=30.0, x=px, y=py,
        sym_height=1.0, sym_width=1.0, angle=0.0, label=1, score=0.8,
        parent_x=px, parent_y=py, parent_width=pw, parent_height=ph, direction="LTR",
    )
    m = FakeMeasurer()
    plan = tr.plan([parented], 800.0, 600.0, 1, False, m)
    l = plan[0]
    e = extent_of(l, m)
    parent = FloatRect(px, py, px + pw, py + ph)
    assert e.left >= parent.left - 0.5
    assert e.top >= parent.top - 0.5
    assert e.right <= parent.right + 0.5
    assert e.bottom <= parent.bottom + 0.5


def test_parentless_non_reshape_clamps_but_tall_grows():
    # TextLayoutPlannerTest.kt:200-232
    m = FakeMeasurer()
    non_tall = _block(100, 100, 60, 40, "Long text that gets clamped to its initial box", score=0.8)
    tall = _block(300, 200, 50, 300, "Tall reshaping box grows toward page", score=0.7)
    plan = tr.plan([non_tall, tall], 800.0, 600.0, 1, False, m)
    non_tall_layout = next(it for it in plan if it.text == "Long text that gets clamped to its initial box")
    tall_layout = next(it for it in plan if it.text == "Tall reshaping box grows toward page")
    initial_box = FloatRect(100.0, 100.0, 160.0, 140.0)
    non_tall_clip = non_tall_layout.clip_rect
    if non_tall_clip is not None:
        assert non_tall_clip.left >= initial_box.left - 0.5
        assert non_tall_clip.top >= initial_box.top - 0.5
        assert non_tall_clip.right <= initial_box.right + 0.5
        assert non_tall_clip.bottom <= initial_box.bottom + 0.5
    else:
        non_tall_extent = extent_of(non_tall_layout, m)
        assert non_tall_extent.right <= initial_box.right + 0.5
    tall_extent = extent_of(tall_layout, m)
    assert tall_extent.width() > 50.0


def test_page_edge_clamping_keeps_growing_box_on_page():
    # TextLayoutPlannerTest.kt:235-246
    edge = _block(1450, 400, 30, 30, "Overflowing long text", score=0.8)
    plan = tr.plan([edge], 1500.0, 800.0, 1, False, FakeMeasurer())
    l = plan[0]
    right_edge = l.origin_x + l.safe_w / 2.0
    assert right_edge <= 1500.0 + 0.5


def test_clip_safety_net_engages_when_block_cannot_avoid_neighbour():
    # TextLayoutPlannerTest.kt:249-261
    a = _block(100, 100, 200, 80, "First", score=0.95)
    b = _block(200, 100, 200, 80, "Second longer text here", score=0.5)
    plan = tr.plan([a, b], 1000.0, 1000.0, 1, False, FakeMeasurer())
    ea = extent_of(next(it for it in plan if it.text == "First"), FakeMeasurer())
    eb = extent_of(next(it for it in plan if it.text == "Second longer text here"), FakeMeasurer())
    assert not ea.overlaps(eb)


def test_higher_score_block_places_first_and_constrains_lower():
    # TextLayoutPlannerTest.kt:264-277
    high = _block(400, 400, 80, 80, "High", score=0.99)
    low = _block(420, 410, 80, 80, "Low", score=0.2)
    plan = tr.plan([high, low], 1000.0, 1000.0, 1, False, FakeMeasurer())
    high_layout = next(it for it in plan if it.text == "High")
    assert high_layout.origin_x == (400.0 + 80.0 / 2.0)
    assert high_layout.clip_rect is None


def test_cjk_ratio_and_should_render_vertical_delegated_consistently():
    # TextLayoutPlannerTest.kt:280-286
    assert tr.should_render_vertical("\u3053\u3093\u306b\u3061\u306f") is True
    assert tr.should_render_vertical("What's up?") is False
    assert tr.cjk_ratio("(\u7b11)") == (1.0 / 3.0)


def test_compute_rects_reshapes_tall_parentless_but_not_parented():
    # TextLayoutPlannerTest.kt:289-320
    tall_parentless = _block(0, 0, 50, 300, "x")
    r1 = tr.compute_rects(tall_parentless, 1)
    assert r1.reshaped is True
    assert r1.base_w > 50.0
    assert r1.base_h < 300.0
    parented = Block(
        text="", translation="x", width=50.0, height=300.0, x=0.0, y=0.0,
        sym_height=1.0, sym_width=1.0, angle=0.0, label=1, score=1.0,
        parent_x=0.0, parent_y=0.0, parent_width=200.0, parent_height=300.0, direction="LTR",
    )
    r2 = tr.compute_rects(parented, 1)
    assert r2.reshaped is False
    assert r2.base_w == 200.0
    assert r2.base_h == 300.0


def test_min_legible_font_scales_and_never_drops_below_floor():
    # TextLayoutPlannerTest.kt:323-332
    small = tr.min_legible_font(400.0, 400.0, 1.0)
    large = tr.min_legible_font(2000.0, 2000.0, 1.0)
    assert small == 14.0
    assert large > 20.0 and large < 40.0
    assert large == (2000.0 * 0.014)


def test_cjk_wrap_atomic_latin_and_breakable_cjk():
    # TextLayoutPlannerTest.kt:335-344
    m = FakeMeasurer()
    lines = tr.cjk_wrap("Hello world", 10.0, 1.0, m)
    assert len(lines) == 2
    cjk_lines = tr.cjk_wrap("\u3053\u3093\u306b\u3061\u306f", 100.0, 1.0 * 0.6 * 2, m)
    assert len(cjk_lines) >= 1


def test_binary_search_font_size_ceiling_and_floor():
    # TextLayoutPlannerTest.kt:347-359
    m = FakeMeasurer()
    big = tr.binary_search_font_size("Hi", 800.0, 800.0, 800.0, False, 1.0, m)
    assert big == 72.0
    tiny = tr.binary_search_font_size("A very long sentence that cannot fit", 10.0, 10.0, 10.0, False, 1.0, m)
    assert tiny <= 8.0


def test_crowded_page_yields_no_overlaps():
    # TextLayoutPlannerTest.kt:362-389
    blocks = [
        _block(100 + (i % 3) * 90, 100 + (i // 3) * 90, 120, 60, f"Block number {i} with text", score=1.0 - i * 0.1)
        for i in range(6)
    ]
    m = FakeMeasurer()
    plan = tr.plan(blocks, 500.0, 500.0, 1, False, m)
    for i in range(len(plan)):
        for j in range(i + 1, len(plan)):
            ei = extent_of(plan[i], m)
            ej = extent_of(plan[j], m)
            assert not ei.overlaps(ej)
    assert len(plan) >= 1


def test_near_right_edge_text_never_drifts_to_centre():
    # TextLayoutPlannerTest.kt:392-417
    pw, ph = 1500.0, 600.0
    wall_top = _block(0, 0, pw, 242, "W", score=0.99)
    wall_bot = _block(0, 358, pw, ph - 358, "W", score=0.99)
    sfx = _block(1300, 100, 30, 400, "A very long sound effect line that must grow wide not tall", score=0.5)
    m = FakeMeasurer()
    plan = tr.plan([wall_top, wall_bot, sfx], pw, ph, 1, False, m)
    l = next(it for it in plan if it.text != "W")
    e = extent_of(l, m)
    assert (e.left + e.right) / 2.0 > pw / 2.0
    assert e.right <= pw + 0.5
    assert e.left >= 0.0


def test_near_left_edge_text_never_drifts_to_centre():
    # TextLayoutPlannerTest.kt:420-439
    pw, ph = 1500.0, 600.0
    wall_top = _block(0, 0, pw, 242, "W", score=0.99)
    wall_bot = _block(0, 358, pw, ph - 358, "W", score=0.99)
    sfx = _block(170, 100, 30, 400, "A very long sound effect line that must grow wide not tall", score=0.5)
    m = FakeMeasurer()
    plan = tr.plan([wall_top, wall_bot, sfx], pw, ph, 1, False, m)
    l = next(it for it in plan if it.text != "W")
    e = extent_of(l, m)
    assert (e.left + e.right) / 2.0 < pw / 2.0
    assert e.left >= -0.5
    assert e.right <= pw + 0.5


def test_horizontal_growth_alone_does_not_force_edge_align_without_overlap():
    # TextLayoutPlannerTest.kt:442-456
    edge = _block(1410, 100, 30, 220, "SupercalifragilisticexpialidociousSoundEffectWord", score=0.8)
    plan = tr.plan([edge], 1500.0, 800.0, 1, False, FakeMeasurer())
    layout = plan[0]
    assert layout.draw_align == TextAlign.CENTER


def test_long_text_in_box_with_headroom_wraps_into_multiple_lines():
    # TextLayoutPlannerTest.kt:459-479
    tall = _block(200, 100, 40, 400, "Quite a long translated sentence that should wrap onto multiple rendered lines", score=0.8)
    m = FakeMeasurer()
    plan = tr.plan([tall], 1500.0, 1500.0, 1, False, m)
    l = plan[0]
    lines = tr.cjk_wrap(l.text, l.font_size_px, l.safe_w, m)
    assert len(lines) > 1
    e = extent_of(l, m)
    assert e.right <= 1500.0 + 0.5
    assert e.left >= -0.5


# ── Parent-selection parity (RoiPageRecognitionEngine.kt:1113-1138) ──────────

def test_parent_selection_smallest_containing_bubble():
    bubble_outer = {"bbox": [0, 0, 200, 200], "score": 0.9}
    bubble_inner = {"bbox": [60, 60, 140, 140], "score": 0.8}
    parent = tr.select_parent_bubble([100, 100, 110, 110], [bubble_outer, bubble_inner], 100.0, 100.0)
    assert parent is bubble_inner


def test_parent_selection_no_containing_falls_back_to_overlap():
    # Text box center (105,105) is outside the bubble [0,0,100,200] (x>100), but
    # the overlap = 20*50 = 1000 px >= 20% of the 2500 px text area -> chosen by
    # max overlap. (RoiPageRecognitionEngine.kt:1131-1138.)
    bubble = {"bbox": [0, 0, 100, 200], "score": 0.9}
    other = {"bbox": [500, 500, 600, 600], "score": 0.99}
    parent = tr.select_parent_bubble([80, 80, 130, 130], [bubble, other], 105.0, 105.0)
    assert parent is bubble


def test_parent_selection_no_bubble_returns_none():
    assert tr.select_parent_bubble([0, 0, 10, 10], [], 5.0, 5.0) is None


# ── Diagnostics contract (Task 0.4) ──────────────────────────────────────────

def test_diagnostics_contract_keys_present():
    import numpy as np
    img = np.full((300, 300, 3), 255, np.uint8)
    b = _block(40, 40, 120, 60, "Hello", score=0.8)
    _, diag = tr.render_page(img, [b])
    d = diag[0]
    for key in ("hasParent", "parentBox", "ocrBox", "safeBox", "fontSize", "lineCount",
                "direction", "origin", "drawAlign", "shiftFromOcrCenter", "flags",
                "maskBox", "maskArea", "lineArtOverlapPx"):
        assert key in d, f"missing diagnostics key: {key}"
    for flag in ("reshaped", "grew", "clipped", "wordBroken", "ocrAnchored"):
        assert flag in d["flags"]
