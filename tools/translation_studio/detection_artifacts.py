"""Detection-stage artifact capture helpers for Translation Studio."""
from __future__ import annotations

import hashlib
from functools import lru_cache
from bisect import bisect_right
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
