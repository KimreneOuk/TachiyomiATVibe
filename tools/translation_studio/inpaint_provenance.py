"""Lossless, page-scoped crop artifacts for inpaint provenance records."""
from __future__ import annotations

import hashlib
import re
import shutil
import time
from pathlib import Path
from urllib.parse import urlencode

import numpy as np
from PIL import Image
from scipy import ndimage


CROP_SCHEMA_VERSION = 1
REGION_CROP_PADDING = 12
_COMPONENT_STRUCTURE = np.ones((3, 3), dtype=bool)


def _safe_token(value: str) -> str:
    token = "".join(ch if ch.isascii() and (ch.isalnum() or ch in "-_.")
                    else "_" for ch in str(value))
    return (token if token not in ("", ".", "..") else
            "item_" + hashlib.sha256(str(value).encode("utf-8")).hexdigest()[:12])


def page_key(page: str) -> str:
    """Return a flat, stable directory component for a chapter-relative page."""
    return _safe_token(str(page).replace("\\", "/").strip("/").replace("/", "__"))


def _component_label(component_id) -> int | None:
    if component_id is None:
        return None
    value = str(component_id)
    if value.startswith("c"):
        value = value[1:]
    try:
        result = int(value)
    except (TypeError, ValueError):
        return None
    return result if result > 0 else None


def _bbox_for_mask(mask: np.ndarray, width: int, height: int,
                   padding: int) -> list[int] | None:
    ys, xs = np.where(mask)
    if not xs.size:
        return None
    x1 = max(0, int(xs.min()) - padding)
    y1 = max(0, int(ys.min()) - padding)
    x2 = min(width, int(xs.max()) + 1 + padding)
    y2 = min(height, int(ys.max()) + 1 + padding)
    return [x1, y1, x2, y2] if x2 > x1 and y2 > y1 else None


def _valid_bbox(value, width: int, height: int) -> list[int] | None:
    if not isinstance(value, (list, tuple)) or len(value) != 4:
        return None
    try:
        x1, y1, x2, y2 = [int(part) for part in value]
    except (TypeError, ValueError, OverflowError):
        return None
    x1, y1 = max(0, x1), max(0, y1)
    x2, y2 = min(width, x2), min(height, y2)
    return [x1, y1, x2, y2] if x2 > x1 and y2 > y1 else None


def _artifact_entry(path: Path, chapter: Path, page: str, region_id: str,
                    kind: str, representation: str, mode: str) -> dict:
    relative = path.relative_to(chapter).as_posix()
    query = urlencode({"p": page, "id": region_id, "kind": kind,
                       "representation": representation})
    return {"path": relative, "url": f"/img/inpaint_crop?{query}",
            "format": "image/png", "mode": mode,
            "pixels": "lossless-uint8"}


def save_region_crop_artifacts(source_image: Image.Image,
                               output_image: Image.Image,
                               page_mask: np.ndarray,
                               records: list[dict],
                               studio_dir: Path,
                               page: str,
                               run_fingerprint: str) -> None:
    """Attach current-run crop paths and write lossless RGB/L PNG triples.

    ``context_crop_bbox`` is A4 route provenance and is never changed here.
    Route implementations with a genuine cropped input window use that exact
    window. Android's report-style bubble fill is page-space, so its preview is
    the associated final erase-mask component plus bounded context padding.
    """
    if not re.fullmatch(r"[0-9a-f]{64}", str(run_fingerprint)):
        raise ValueError("inpaint crop run fingerprint must be a SHA-256 hex digest")

    source = source_image.convert("RGB")
    output = output_image.convert("RGB")
    mask = np.asarray(page_mask, dtype=bool)
    width, height = source.size
    if output.size != source.size or mask.shape != (height, width):
        raise ValueError("inpaint crop source, output, and mask dimensions must match")

    component_labels, _ = ndimage.label(mask, structure=_COMPONENT_STRUCTURE)
    chapter = studio_dir.parent
    page_dir = studio_dir / "inpaint_crops" / page_key(page)

    for record in records:
        started = time.perf_counter()
        route = str(record.get("route_taken") or "")
        region_id = str(record.get("id") or record.get("artifact_id") or "region")
        if not route or route.startswith("skipped:"):
            record["crops"] = {"schema_version": CROP_SCHEMA_VERSION,
                               "status": "not-inpainted",
                               "run_fingerprint": run_fingerprint}
            continue

        component_id = (record.get("erase_mask_component_id")
                        or record.get("mask_component_id"))
        component_index = _component_label(component_id)
        if component_index is not None and np.any(component_labels == component_index):
            region_mask = component_labels == component_index
        else:
            region_mask = mask

        route_bbox = _valid_bbox(record.get("context_crop_bbox"), width, height)
        if route != "bubble/android-fill" and route_bbox is not None:
            crop_bbox = route_bbox
            basis = "route-context"
            padding_px = 0
        else:
            crop_bbox = _bbox_for_mask(region_mask, width, height,
                                       REGION_CROP_PADDING)
            basis = "region-mask-union"
            padding_px = REGION_CROP_PADDING
            if crop_bbox is None:
                crop_bbox = route_bbox
                basis = "route-context"
                padding_px = 0
        if crop_bbox is None:
            record["crops"] = {"schema_version": CROP_SCHEMA_VERSION,
                               "status": "unavailable:no-mask-window",
                               "run_fingerprint": run_fingerprint}
            continue

        x1, y1, x2, y2 = crop_bbox
        crop_dir = (page_dir / _safe_token(region_id) / run_fingerprint)
        crop_dir.mkdir(parents=True, exist_ok=True)
        images = {
            "input": (source.crop(tuple(crop_bbox)), "RGB"),
            "output": (output.crop(tuple(crop_bbox)), "RGB"),
            "mask": (Image.fromarray(
                region_mask[y1:y2, x1:x2].astype(np.uint8) * 255), "L"),
        }
        crop_entries = {}
        for kind, (image, mode) in images.items():
            path = crop_dir / f"{kind}.png"
            image.save(path, format="PNG")
            debug = _artifact_entry(path, chapter, page, region_id,
                                    kind, "debug", mode)
            exact = _artifact_entry(path, chapter, page, region_id,
                                    kind, "exact", mode)
            crop_entries[kind] = {"debug": debug, "exact": exact}

        mask_resolution = record.get("mask_resolution")
        if not isinstance(mask_resolution, dict):
            mask_resolution = {}
        note = None
        if route == "bubble/android-fill":
            note = ("android-fill operates on the page-space mask; these files "
                    "show the associated region component in a compact window")
        record["crops"] = {
            "schema_version": CROP_SCHEMA_VERSION,
            "status": "ready",
            "run_fingerprint": run_fingerprint,
            "basis": basis,
            "crop_bbox": crop_bbox,
            "context_crop_bbox": record.get("context_crop_bbox"),
            "padding_px": padding_px,
            "mask_component_id": component_id,
            "mask_ref": mask_resolution.get("mask_ref"),
            "note": note,
            "capture_ms": round((time.perf_counter() - started) * 1000, 3),
            **crop_entries,
        }


def crop_artifacts_are_current(provenance: dict, run_fingerprint: str,
                               studio_dir: Path) -> bool:
    """Return whether all current inpainted regions have readable crop triples."""
    if provenance.get("provenance_schema_version") != 3:
        return False
    chapter = studio_dir.parent.resolve()
    crop_root = (studio_dir / "inpaint_crops").resolve()
    for record in provenance.get("regions", []):
        route = str(record.get("route_taken") or "")
        if not route or route.startswith("skipped:"):
            continue
        crops = record.get("crops")
        if (not isinstance(crops, dict)
                or crops.get("status") != "ready"
                or crops.get("run_fingerprint") != run_fingerprint):
            return False
        for kind in ("input", "output", "mask"):
            values = crops.get(kind)
            if not isinstance(values, dict):
                return False
            for representation in ("debug", "exact"):
                entry = values.get(representation)
                if not isinstance(entry, dict) or not entry.get("path"):
                    return False
                candidate = (chapter / Path(entry["path"])).resolve()
                try:
                    candidate.relative_to(crop_root)
                except ValueError:
                    return False
                if not candidate.is_file():
                    return False
    return True


def remove_page_crop_artifacts(studio_dir: Path, page: str) -> bool:
    """Remove one page's derived crop tree after checking its resolved parent."""
    root = (studio_dir / "inpaint_crops").resolve()
    target = (root / page_key(page)).resolve()
    try:
        target.relative_to(root)
    except ValueError:
        return False
    if target.is_dir():
        shutil.rmtree(target)
        return True
    return False


def remove_all_crop_artifacts(studio_dir: Path) -> bool:
    """Remove all derived crop artifacts after constraining the target to .studio."""
    studio = Path(studio_dir).resolve()
    root = (studio / "inpaint_crops").resolve()
    if root.parent != studio:
        return False
    if root.is_dir():
        shutil.rmtree(root)
        return True
    return False


def prune_stale_crop_runs(studio_dir: Path, page: str,
                          keep_fingerprint: str) -> None:
    """Delete previous run directories after the new provenance is committed."""
    page_dir = (studio_dir / "inpaint_crops" / page_key(page)).resolve()
    root = (studio_dir / "inpaint_crops").resolve()
    try:
        page_dir.relative_to(root)
    except ValueError:
        return
    if not page_dir.is_dir():
        return
    for region_dir in list(page_dir.iterdir()):
        if not region_dir.is_dir():
            region_dir.unlink(missing_ok=True)
            continue
        for run_dir in list(region_dir.iterdir()):
            if run_dir.name != keep_fingerprint:
                if run_dir.is_dir():
                    shutil.rmtree(run_dir)
                else:
                    run_dir.unlink(missing_ok=True)
        if not any(region_dir.iterdir()):
            region_dir.rmdir()
