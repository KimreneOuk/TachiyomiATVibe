"""Faithful Python port of ReadingOrderSorter.kt + PanelAssignment.kt.

Stage A of the Android app's two-stage reading-order pipeline:
  1. ``reading_order_panels`` — XY-cut recursive gutter sort of PANEL boxes.
     Horizontal cut (top/bottom split) is tried first, then vertical cut
     (left/right split). On a vertical cut the RTL branch recurses
     right-then-left (manga), else left-then-right (comic/webtoon).
  2. ``assign_to_panels`` — max-containment mapping of a text/bubble box to
     the panel holding the largest fraction of its area. Categorises the
     result (owned / spanning / free_floating / orphan / invalid) so the
     caller never silently attaches text to a panel it doesn't belong to.

Both are pure geometry — operate on [x1, y1, x2, y2] float lists. No image,
no Android types, no I/O. Mirrors the Kotlin sources 1:1 so JVM parity tests
hold. Constants (OWNED_THRESHOLD=0.80, SPAN_LO=0.10, gutter>=1px) match.
"""
from __future__ import annotations

import math
from dataclasses import dataclass
from typing import Literal


# ── Panel XY-cut sort (ReadingOrderSorter.kt) ────────────────────────

def reading_order_panels(panels: list[list[float]], rtl: bool) -> list[list[float]]:
    """Return *panels* sorted in reading order via recursive XY-cut.

    Each panel is ``[x1, y1, x2, y2]``. ``rtl=True`` → right column first
    (Japanese manga); ``rtl=False`` → left column first (LTR comic/webtoon).
    """
    if len(panels) <= 1:
        return [list(p) for p in panels]
    idx_list = list(range(len(panels)))
    order = _xy_cut_order(panels, idx_list, rtl)
    return [list(panels[i]) for i in order]


def _xy_cut_order(panels: list[list[float]], idx_list: list[int], rtl: bool) -> list[int]:
    if len(idx_list) <= 1:
        return list(idx_list)
    # Horizontal cut first (split top / bottom).
    h_cut = _widest_gutter_split(panels, idx_list, axis="y")
    if h_cut is not None:
        top, bottom = h_cut
        return _xy_cut_order(panels, top, rtl) + _xy_cut_order(panels, bottom, rtl)
    # Vertical cut next (split left / right).
    v_cut = _widest_gutter_split(panels, idx_list, axis="x")
    if v_cut is not None:
        left, right = v_cut
        if rtl:
            return _xy_cut_order(panels, right, rtl) + _xy_cut_order(panels, left, rtl)
        return _xy_cut_order(panels, left, rtl) + _xy_cut_order(panels, right, rtl)
    # No gutter >= 1px → column fallback.
    return _column_fallback_order(panels, idx_list, rtl)


def _widest_gutter_split(
    panels: list[list[float]],
    idx_list: list[int],
    axis: Literal["x", "y"],
) -> tuple[list[int], list[int]] | None:
    """Find the widest empty gutter along *axis*; return the two index groups.

    Mirrors ``widestGutterSplit``: sweep-line over open/close events, track
    the widest gap where no panel is present (count==0). A gutter must be
    >= 1px wide to count (zero-width touches don't split).
    """
    # events: (coord, delta) — +1 on interval open, -1 on close.
    events: list[list[float]] = []
    for i in idx_list:
        p = panels[i]
        lo = p[1] if axis == "y" else p[0]
        hi = p[3] if axis == "y" else p[2]
        events.append([float(lo), 1.0])
        events.append([float(hi), -1.0])
    # Kotlin sorts by `coord * 2 - delta`: open (delta=+1) sorts before close
    # (delta=-1) at the same coord, so touching intervals keep count>0 across
    # the boundary (no spurious zero-width gutter).
    events.sort(key=lambda e: e[0] * 2.0 - e[1])

    best_start = math.nan
    best_width = 1.0
    count = 0
    prev = math.nan
    found = False
    for coord, delta in events:
        if count == 0 and not math.isnan(prev):
            w = coord - prev
            if w >= 1.0 and w > best_width:
                best_width = w
                best_start = prev
                found = True
        count += int(delta)
        prev = coord

    if not found:
        return None
    cut = best_start + best_width / 2.0
    a: list[int] = []
    b: list[int] = []
    for i in idx_list:
        p = panels[i]
        c = (p[1] + p[3]) / 2.0 if axis == "y" else (p[0] + p[2]) / 2.0
        if c <= cut:
            a.append(i)
        else:
            b.append(i)
    if not a or not b:
        return None
    return a, b


def _column_fallback_order(
    panels: list[list[float]], idx_list: list[int], rtl: bool,
) -> list[int]:
    """No gutter found → sort by centre-x (desc for RTL), tie-break by y."""
    def cx(i: int) -> float:
        return (panels[i][0] + panels[i][2]) / 2.0

    # Stable sort: primary by cx (reverse for rtl), secondary by y.
    return sorted(
        idx_list,
        key=lambda i: (-cx(i) if rtl else cx(i), panels[i][1]),
    )


# ── Panel assignment (PanelAssignment.kt) ────────────────────────────

OWNED_THRESHOLD = 0.80
SPAN_LO = 0.10

Category = Literal["owned", "spanning", "free_floating", "orphan", "invalid", "none"]


@dataclass
class PanelAssignmentResult:
    """Result of assigning one text/bubble box to the page's panels."""
    panel_index: int | None       # reading-order index, None if not owned
    best_panel_idx_raw: int | None  # advisory raw index of best candidate
    best_containment: float
    category: Category


def is_valid_box(x1: float, y1: float, x2: float, y2: float) -> bool:
    if any(math.isnan(v) for v in (x1, y1, x2, y2)):
        return False
    if x2 - x1 <= 0:
        return False
    if y2 - y1 <= 0:
        return False
    return True


def _area(x1: float, y1: float, x2: float, y2: float) -> float:
    w, h = x2 - x1, y2 - y1
    return w * h if (w > 0 and h > 0) else 0.0


def _intersection_area(
    ax1: float, ay1: float, ax2: float, ay2: float,
    bx1: float, by1: float, bx2: float, by2: float,
) -> float:
    ix1, iy1 = max(ax1, bx1), max(ay1, by1)
    ix2, iy2 = min(ax2, bx2), min(ay2, by2)
    w, h = ix2 - ix1, iy2 - iy1
    return w * h if (w > 0 and h > 0) else 0.0


def assign_to_panels(
    text_x1: float, text_y1: float, text_x2: float, text_y2: float,
    panels: list[list[float]],
) -> PanelAssignmentResult:
    """Assign a text box to the panel with max containment fraction.

    Conservative — no nearest-panel guessing. Containment >= OWNED_THRESHOLD
    → owned (panel_index set); SPAN_LO..OWNED → spanning; < SPAN_LO with
    panels present → free_floating; no panels → orphan; bad geometry → invalid.
    """
    if not is_valid_box(text_x1, text_y1, text_x2, text_y2):
        return PanelAssignmentResult(None, None, 0.0, "invalid")
    if not panels:
        return PanelAssignmentResult(None, None, 0.0, "orphan")

    text_area = _area(text_x1, text_y1, text_x2, text_y2)
    best_idx = -1
    best_cont = 0.0
    for i, p in enumerate(panels):
        if not is_valid_box(p[0], p[1], p[2], p[3]):
            continue
        inter = _intersection_area(text_x1, text_y1, text_x2, text_y2,
                                   p[0], p[1], p[2], p[3])
        if inter <= 0:
            continue
        cont = inter / text_area if text_area > 0 else 0.0
        if cont > best_cont:
            best_cont = cont
            best_idx = i
    if best_idx < 0:
        return PanelAssignmentResult(None, None, 0.0, "free_floating")

    if best_cont >= OWNED_THRESHOLD:
        cat: Category = "owned"
    elif best_cont >= SPAN_LO:
        cat = "spanning"
    else:
        cat = "free_floating"
    return PanelAssignmentResult(
        panel_index=best_idx if cat == "owned" else None,
        best_panel_idx_raw=best_idx,
        best_containment=best_cont,
        category=cat,
    )
