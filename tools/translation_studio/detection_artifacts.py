"""Detection-stage artifact capture helpers for Translation Studio."""
from __future__ import annotations

import hashlib
from functools import lru_cache
from bisect import bisect_left, bisect_right
from dataclasses import dataclass
from pathlib import Path

import numpy as np


@lru_cache(maxsize=32)
def _asset_sha12_cached(path: str, modified_ns: int, size: int) -> str:
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()[:12]


def asset_sha12(path: Path | None) -> str | None:
    if path is None or not path.is_file():
        return None
    stat = path.stat()
    return _asset_sha12_cached(str(path.resolve()), stat.st_mtime_ns, stat.st_size)


def box_artifact(
        artifact_id: str, page: str, model: str, asset: str,
        asset_sha: str | None, window: int | None, box: list[float],
        score: float, label: int | str,
) -> dict:
    x1, y1, x2, y2 = box
    width, height = max(0.0, x2 - x1), max(0.0, y2 - y1)
    return {
        "id": artifact_id,
        "page": page,
        "kind": "box",
        "source": {"model": model, "asset": asset,
                   "asset_sha": asset_sha, "window": window},
        "geometry": {"x1": x1, "y1": y1, "x2": x2, "y2": y2},
        "attrs": {"label": label, "score": score,
                  "shape": {"w": width, "h": height,
                            "ar": width / height if height else None}},
        "lifecycle": {"state": "raw", "trace": []},
    }


def _rle_runs(mask: np.ndarray, row_offset: int, page_width: int) -> list[int]:
    """Row-major RLE pairs with an optional page-space vertical offset."""
    flat = np.asarray(mask, dtype=bool).reshape(-1)
    if flat.size == 0 or not flat.any():
        return []
    changes = np.flatnonzero(np.diff(np.concatenate((np.array([False]), flat,
                                                      np.array([False])))))
    starts, ends = changes[::2], changes[1::2]
    offset = row_offset * page_width
    runs: list[int] = []
    for start, end in zip(starts, ends):
        runs.extend((int(start) + offset, int(end - start)))
    return runs


def mask_artifact(
        artifact_id: str, page: str, asset: str, asset_sha: str | None,
        window: int | None, window_top: int, page_wh: tuple[int, int],
        mask,
) -> tuple[dict, dict]:
    """Encode one decoded segmenter instance and its component RLEs."""
    page_w, page_h = page_wh
    local_mask = np.asarray(mask.mask, dtype=bool)
    local_h, local_w = local_mask.shape
    if local_w != page_w or window_top < 0 or window_top + local_h > page_h:
        raise ValueError("segmenter mask does not map into the expected page window")
    x1, y1, x2, y2 = mask.bounds
    bounds = [int(x1), int(y1 + window_top), int(x2),
              min(page_h, int(y2 + window_top))]
    mask_ref = artifact_id
    components: dict[str, list[int]] = {}
    labels = np.asarray(mask.labels)
    for component in mask.components:
        component_id = int(component.id)
        components[str(component_id)] = _rle_runs(
            labels == component_id, window_top, page_w)

    record = {
        "id": artifact_id,
        "page": page,
        "kind": "mask",
        "source": {"model": "bubble-segmenter", "asset": asset,
                   "asset_sha": asset_sha, "window": window},
        "mask_ref": mask_ref,
        "attrs": {"label": 0, "score": float(mask.score),
                  "shape": {"w": bounds[2] - bounds[0],
                            "h": bounds[3] - bounds[1],
                            "ar": ((bounds[2] - bounds[0]) /
                                   max(1, bounds[3] - bounds[1]))},
                  "component_count": len(components)},
        "lifecycle": {"state": "kept",
                      "trace": [{"step": "segmenter", "kept": True}]},
    }
    cache_entry = {
        "width": page_w,
        "height": page_h,
        "bounds": bounds,
        "runs": _rle_runs(local_mask, window_top, page_w),
        "components": components,
    }
    return record, cache_entry


def _contains(runs: list[int], width: int, height: int,
              x: int, y: int) -> bool:
    if x < 0 or y < 0 or x >= width or y >= height or len(runs) < 2:
        return False
    pixel = y * width + x
    starts = runs[0::2]
    idx = bisect_right(starts, pixel) - 1
    return idx >= 0 and pixel < starts[idx] + runs[idx * 2 + 1]


def iter_rle_row_spans(runs, width: int, height: int):
    """Yield page-space (y, x1, x2) spans, splitting runs at row edges.

    The cache stores flat row-major RLE pairs. Adjacent foreground pixels at
    the end and start of consecutive rows can therefore be one flat run. Keep
    each yielded span inside one page row so consumers can write directly into
    a crop or a shared union without materializing one dense mask per component.
    """
    width, height = int(width), int(height)
    if width <= 0 or height <= 0 or not isinstance(runs, (list, tuple)):
        return
    total = width * height
    for index in range(0, len(runs) - 1, 2):
        try:
            start = int(runs[index])
            length = int(runs[index + 1])
        except (TypeError, ValueError, OverflowError):
            continue
        if length <= 0:
            continue
        end = min(total, start + length)
        start = max(0, start)
        while start < end:
            y, x1 = divmod(start, width)
            count = min(end - start, width - x1)
            if y >= height:
                break
            yield y, x1, x1 + count
            start += count


def rle_bounds(runs, width: int, height: int) -> tuple[int, int, int, int] | None:
    """Return the tight page-space bounds of flat row-major RLE pairs."""
    left, top, right, bottom = int(width), int(height), -1, -1
    for y, x1, x2 in iter_rle_row_spans(runs, width, height):
        left, top = min(left, x1), min(top, y)
        right, bottom = max(right, x2), max(bottom, y + 1)
    return (left, top, right, bottom) if right > left and bottom > top else None


@dataclass(frozen=True)
class RleComponentView:
    """A selected segmenter component backed by cached page-space RLE."""

    mask_ref: str
    component_id: int
    width: int
    height: int
    runs: tuple[int, ...]
    row_spans: tuple[tuple[int, int, int], ...]
    bounds: tuple[int, int, int, int]
    component_bounds: tuple[int, int, int, int]
    source: dict

    @property
    def key(self) -> tuple[str, int]:
        return self.mask_ref, self.component_id

    def rasterize_crop(self, box) -> np.ndarray:
        """Decode only the requested page-space crop of this component."""
        x1, y1, x2, y2 = (int(v) for v in box[:4])
        x1c, y1c = max(0, x1), max(0, y1)
        x2c, y2c = min(self.width, x2), min(self.height, y2)
        if x2c <= x1c or y2c <= y1c:
            return np.zeros((0, 0), dtype=bool)
        out = np.zeros((y2c - y1c, x2c - x1c), dtype=bool)
        start = bisect_left(self.row_spans, (y1c, -1, -1))
        end = bisect_left(self.row_spans, (y2c, -1, -1))
        for y, sx1, sx2 in self.row_spans[start:end]:
            left, right = max(sx1, x1c), min(sx2, x2c)
            if right > left:
                out[y - y1c, left - x1c:right - x1c] = True
        return out

    def contains_rect(self, box) -> bool:
        """Return whether the full non-empty page-space rectangle is inside."""
        x1, y1, x2, y2 = (int(v) for v in box[:4])
        if x1 < 0 or y1 < 0 or x2 > self.width or y2 > self.height:
            return False
        if x2 <= x1 or y2 <= y1:
            return False
        crop = self.rasterize_crop((x1, y1, x2, y2))
        return crop.shape == (y2 - y1, x2 - x1) and bool(crop.all())


def resolve_mask_component(mask_ref, component_id, mask_cache: dict,
                           outputs: list[dict]):
    """Resolve an A1 mask_ref/component pair without dense page allocation.

    Returns ``(view, None)`` on success, or ``(None, status)`` when the
    current capture has an incomplete reference. Callers must not substitute a
    fresh model run for a failed current-capture lookup.
    """
    if not isinstance(mask_ref, str) or not mask_ref:
        return None, "missing-mask-ref"
    entry = (mask_cache or {}).get(mask_ref)
    if not isinstance(entry, dict):
        return None, "missing-reference"
    try:
        component = int(component_id)
        width, height = int(entry["width"]), int(entry["height"])
    except (KeyError, TypeError, ValueError, OverflowError):
        return None, "invalid-reference"
    components = entry.get("components")
    if not isinstance(components, dict):
        return None, "missing-components"
    runs = components.get(str(component))
    if not isinstance(runs, (list, tuple)) or not runs:
        return None, "missing-component"
    row_spans = tuple(iter_rle_row_spans(runs, width, height))
    component_bounds = rle_bounds(runs, width, height)
    if component_bounds is None:
        return None, "empty-component"
    output = next((item for item in (outputs or [])
                   if item.get("mask_ref") == mask_ref), None)
    if not isinstance(output, dict) or not isinstance(output.get("source"), dict):
        return None, "missing-source-record"
    bounds = entry.get("bounds") or component_bounds
    try:
        bounds = tuple(int(v) for v in bounds[:4])
    except (TypeError, ValueError, OverflowError):
        return None, "invalid-bounds"
    if len(bounds) != 4:
        return None, "invalid-bounds"
    return RleComponentView(
        mask_ref=mask_ref, component_id=component, width=width, height=height,
        runs=tuple(int(v) for v in runs), bounds=bounds,
        row_spans=row_spans,
        component_bounds=component_bounds,
        source=dict(output["source"]),
    ), None


def assign_mask_center(
        outputs: list[dict], mask_cache: dict[str, dict],
        box: list[int],
) -> dict | None:
    """Match Android's ordered RLE overlap test at the block-center pixel."""
    x1, y1, x2, y2 = box
    # Kotlin Float.toInt() truncates toward zero; Python int() does too.
    center_x = int((x1 + x2) / 2.0)
    center_y = int((y1 + y2) / 2.0)
    for output in outputs:
        mask_ref = output.get("mask_ref")
        entry = mask_cache.get(mask_ref)
        if not entry:
            continue
        width, height = entry["width"], entry["height"]
        if not _contains(entry["runs"], width, height, center_x, center_y):
            continue
        component_id = next((int(cid) for cid, runs in entry["components"].items()
                             if _contains(runs, width, height, center_x, center_y)), None)
        return {"mask_ref": mask_ref,
                "mask_component_id": component_id,
                "source": dict(output["source"])}
    return None
