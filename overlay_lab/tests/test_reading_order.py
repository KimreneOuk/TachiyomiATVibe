"""Parity tests for reading_order.py — mirror ReadingOrderSorterTest.kt (11 cases)
plus PanelAssignment geometry cases.

These MUST match the JVM tests exactly so the Python port is a faithful Stage A.
"""
import math

import pytest

from backend.inference.reading_order import (
    reading_order_panels,
    assign_to_panels,
    OWNED_THRESHOLD,
    SPAN_LO,
)


# ── ReadingOrderSorter parity (11 JVM cases) ─────────────────────────


def test_single_panel_passthrough():
    panels = [[0, 0, 10, 10]]
    assert reading_order_panels(panels, rtl=False) == [[0, 0, 10, 10]]


def test_empty_list_passthrough():
    assert reading_order_panels([], rtl=False) == []


def test_two_stacked_top_then_bottom_regardless_of_rtl():
    top = [0, 0, 100, 50]
    bottom = [0, 60, 100, 110]
    out = reading_order_panels([bottom, top], rtl=False)
    assert out[0] == top
    assert out[1] == bottom
    out_rtl = reading_order_panels([bottom, top], rtl=True)
    assert out_rtl[0] == top
    assert out_rtl[1] == bottom


def test_two_side_by_side_ltr_left_first():
    left = [0, 0, 50, 100]
    right = [60, 0, 110, 100]
    out = reading_order_panels([right, left], rtl=False)
    assert out[0] == left
    assert out[1] == right


def test_two_side_by_side_rtl_right_first():
    left = [0, 0, 50, 100]
    right = [60, 0, 110, 100]
    out = reading_order_panels([left, right], rtl=True)
    assert out[0] == right
    assert out[1] == left


def test_touching_intervals_not_split_zero_gutter():
    """Touching at x=50 (no gap >=1px) → column fallback → left first (LTR)."""
    left = [0, 0, 50, 100]
    right = [50, 0, 100, 100]
    out = reading_order_panels([right, left], rtl=False)
    assert out[0] == left
    assert out[1] == right


def test_mutually_overlapping_column_fallback_ltr():
    a = [40, 0, 60, 100]   # cx=50
    b = [20, 0, 55, 100]   # cx=37.5
    c = [45, 0, 80, 100]   # cx=62.5
    out = reading_order_panels([a, c, b], rtl=False)
    assert out[0] == b
    assert out[1] == a
    assert out[2] == c


def test_mutually_overlapping_column_fallback_rtl():
    a = [40, 0, 60, 100]
    b = [20, 0, 55, 100]
    c = [45, 0, 80, 100]
    out = reading_order_panels([a, b, c], rtl=True)
    assert out[0] == c
    assert out[1] == a
    assert out[2] == b


def test_column_fallback_tiebreak_top_to_bottom():
    top = [40, 0, 60, 40]
    bottom = [40, 50, 60, 90]
    out_ltr = reading_order_panels([bottom, top], rtl=False)
    assert out_ltr[0] == top
    assert out_ltr[1] == bottom
    out_rtl = reading_order_panels([bottom, top], rtl=True)
    assert out_rtl[0] == top
    assert out_rtl[1] == bottom


def test_nested_grid_ltr():
    tl = [0, 0, 40, 40]
    tr = [60, 0, 100, 40]
    bl = [0, 60, 40, 100]
    br = [60, 60, 100, 100]
    out = reading_order_panels([br, bl, tr, tl], rtl=False)
    assert out == [tl, tr, bl, br]


def test_nested_grid_rtl_right_column_first():
    tl = [0, 0, 40, 40]
    tr = [60, 0, 100, 40]
    bl = [0, 60, 40, 100]
    br = [60, 60, 100, 100]
    out = reading_order_panels([tl, bl, tr, br], rtl=True)
    assert out == [tr, tl, br, bl]


# ── PanelAssignment parity ───────────────────────────────────────────


def test_assignment_invalid_geometry():
    r = assign_to_panels(10, 10, 5, 5, [[0, 0, 100, 100]])  # x2<x1
    assert r.category == "invalid"
    assert r.panel_index is None


def test_assignment_no_panels_orphan():
    r = assign_to_panels(10, 10, 20, 20, [])
    assert r.category == "orphan"
    assert r.panel_index is None


def test_assignment_owned():
    # text fully inside panel 0
    r = assign_to_panels(10, 10, 20, 20, [[0, 0, 100, 100], [200, 200, 300, 300]])
    assert r.category == "owned"
    assert r.panel_index == 0
    assert r.best_containment == pytest.approx(1.0)


def test_assignment_spanning():
    # text crosses gutter between two panels — containment ~0.5
    r = assign_to_panels(40, 0, 60, 10, [[0, 0, 50, 10], [50, 0, 100, 10]])
    assert r.category == "spanning"
    assert r.panel_index is None
    assert SPAN_LO <= r.best_containment < OWNED_THRESHOLD


def test_assignment_free_floating():
    # text outside every panel
    r = assign_to_panels(500, 500, 510, 510, [[0, 0, 100, 100]])
    assert r.category == "free_floating"
    assert r.panel_index is None


def test_assignment_max_containment_picks_best():
    # text overlaps two panels; panel 1 contains more of it.
    r = assign_to_panels(40, 0, 90, 10, [[0, 0, 50, 10], [50, 0, 100, 10]])
    assert r.best_panel_idx_raw in (0, 1)
    # the larger-overlap panel wins
    assert r.best_containment > 0.5


def test_owned_threshold_constant():
    assert OWNED_THRESHOLD == 0.80


def test_span_lo_constant():
    assert SPAN_LO == 0.10
