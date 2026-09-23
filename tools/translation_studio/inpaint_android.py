"""Android-parity inpainting path for Translation Studio.

This module keeps the legacy desktop implementation in ``aot_inpaint.py``
available for side-by-side comparison.  Planner and mask constants follow the
current Kotlin sources in app/src/main/java/eu/kanade/translation/inpainting.
"""
from __future__ import annotations

import math
import hashlib
import time
from collections import deque
from pathlib import Path

import numpy as np
from PIL import Image
from scipy import ndimage

import aot_inpaint
from detection_artifacts import (iter_rle_row_spans,
                                 resolve_mask_component)

REPO_ROOT = Path(__file__).resolve().parents[2]
AOT_DYNAMIC_MODEL = REPO_ROOT / "app/src/main/assets/models/inpainting/aot.onnx"

DETECTOR_PAD = 3
DETECTOR_OCR_IOU = 0.4
BUBBLE_OVERLAP_FRACTION = 0.12
PARENT_BUBBLE_OVERLAP_FRACTION = 0.20
PADDLE_CROP_PAD = 12
PADDLE_THRESH = 0.18
PADDLE_BOX_THRESH = 0.34
FREE_TEXT_CONTEXT = 64
FREE_TEXT_PAD = 1
FREE_TEXT_DILATE = 2
FREE_TEXT_FEATHER = 3
BUBBLE_SEG_EROSION = 5
BUBBLE_BOX_PAD = 8
BUBBLE_SMOOTH_PASSES = 12
BUBBLE_FEATHER = 12
AOT_REPORT_CONTEXT = 512
AOT_DYNAMIC_MARGIN = 32
AOT_MAX_DIM = 768
AOT_ALIGN = 8
AOT_FIXED_SIZE = 512
AOT_GUARD_MASKED_PIXELS = 16
AOT_GUARD_NEAR_BLACK = 24.0
AOT_GUARD_MID_GRAY = (96.0, 160.0)
AOT_GUARD_NEAR_WHITE = 238.0
AOT_GUARD_MAX_LUMA_VARIANCE = 9.0
AOT_GUARD_MAX_CHANNEL_DELTA = 8.0

PRESETS = {
    "android-fast": {"bubble_leg": "android-fill", "free_leg": "opencv"},
    "android-quality": {"bubble_leg": "android-fill", "free_leg": "aot"},
}
BUBBLE_LEGS = {"android-fill", "opencv", "aot", "pushpull"}
FREE_LEGS = {"opencv", "aot", "pushpull"}

_STRUCT_8 = np.ones((3, 3), dtype=bool)


def _valid_box(box) -> list[int] | None:
    if not isinstance(box, (list, tuple, np.ndarray)) or len(box) < 4:
        return None
    try:
        b = [int(box[0]), int(box[1]), int(box[2]), int(box[3])]
    except (TypeError, ValueError, OverflowError):
        return None
    return b if b[2] > b[0] and b[3] > b[1] else None


def _area(box) -> int:
    return max(0, box[2] - box[0]) * max(0, box[3] - box[1])


def _intersection(a, b) -> int:
    return max(0, min(a[2], b[2]) - max(a[0], b[0])) * max(
        0, min(a[3], b[3]) - max(a[1], b[1]))


def _iou(a, b) -> float:
    inter = _intersection(a, b)
    union = _area(a) + _area(b) - inter
    return inter / union if union > 0 else 0.0


def _raw_detections(raw_detections, min_confidence: float | None = None) -> list[dict]:
    out = []
    for index, row in enumerate(raw_detections or []):
        artifact_id = None
        source = None
        assignment = None
        try:
            if isinstance(row, dict):
                attrs = row.get("attrs", {})
                geometry = row.get("geometry", {})
                if all(key in geometry for key in ("x1", "y1", "x2", "y2")):
                    raw_box = [geometry[key] for key in ("x1", "y1", "x2", "y2")]
                else:
                    raw_box = row.get("box")
                label = int(attrs.get("label", row.get("label")))
                score = float(attrs.get("score", row.get("score")))
                artifact_id = row.get("artifact_id") or row.get("id")
                source = row.get("source")
                assignment = row.get("segmenter_assignment")
            elif isinstance(row, (list, tuple)) and len(row) >= 6:
                label, score, raw_box = int(row[0]), float(row[1]), row[2:6]
            else:
                continue
        except (TypeError, ValueError, OverflowError):
            continue
        box = _valid_box(raw_box)
        if box is None or not math.isfinite(score):
            continue
        if min_confidence is not None and score < float(min_confidence):
            continue
        out.append({"id": str(artifact_id or f"d{index:03d}"),
                    "artifact_id": artifact_id, "label": label,
                    "score": score, "box": box,
                    "source": dict(source) if isinstance(source, dict) else None,
                    "segmenter_assignment": assignment})
    return out


def _trim_parent(parent: list[int], text_box: list[int], siblings: list[list[int]]) -> list[int]:
    """OcrBlockDeduplication.trimParentBbox, kept local to inpainting."""
    px1, py1, px2, py2 = parent
    tx1, ty1, tx2, ty2 = text_box
    for sib in siblings:
        if sib == parent:
            continue
        sx1, sy1, sx2, sy2 = sib
        sib_area = max(0, sx2 - sx1) * max(0, sy2 - sy1)
        if sib_area == 0 or _intersection([px1, py1, px2, py2], sib) < 0.45 * sib_area:
            continue
        scx, scy = (sx1 + sx2) / 2.0, (sy1 + sy2) / 2.0
        tcx, tcy = (tx1 + tx2) / 2.0, (ty1 + ty2) / 2.0
        overlap_x = min(px2, sx2) - max(px1, sx1)
        overlap_y = min(py2, sy2) - max(py1, sy1)
        if overlap_x <= 0 or overlap_y <= 0:
            continue
        if overlap_x < overlap_y:
            if scx < tcx:
                new_x1 = sx2 + 2
                pad = max(4, int(0.05 * (px2 - new_x1)))
                if new_x1 + pad <= tx1:
                    px1 = new_x1
            elif scx > tcx:
                new_x2 = sx1 - 2
                pad = max(4, int(0.05 * (new_x2 - px1)))
                if new_x2 - pad >= tx2:
                    px2 = new_x2
        elif scy < tcy:
            new_y1 = sy2 + 2
            pad = max(4, int(0.05 * (py2 - new_y1)))
            if new_y1 + pad <= ty1:
                py1 = new_y1
        elif scy > tcy:
            new_y2 = sy1 - 2
            pad = max(4, int(0.05 * (new_y2 - py1)))
            if new_y2 - pad >= ty2:
                py2 = new_y2
    if px1 >= px2 or py1 >= py2:
        return parent
    if px1 > tx1 + 1 or py1 > ty1 + 1 or px2 < tx2 - 1 or py2 < ty2 - 1:
        return parent
    return [px1, py1, px2, py2]


def _select_parent(text_box: list[int], label: int,
                   bubble_detections: list[dict]) -> list[int] | None:
    """OcrBlockDeduplication.selectParentBubble + trimParentBbox."""
    return _select_parent_with_source(text_box, label, bubble_detections)[0]


def _select_parent_with_source(text_box: list[int], label: int,
                               bubble_detections: list[dict]
                               ) -> tuple[list[int] | None, dict | None]:
    if label not in (1, 2) or not bubble_detections:
        return None, None
    cx, cy = (text_box[0] + text_box[2]) / 2.0, (text_box[1] + text_box[3]) / 2.0
    containing = [d for d in bubble_detections
                  if cx >= d["box"][0] and cx <= d["box"][2]
                  and cy >= d["box"][1] and cy <= d["box"][3]]
    parent = min(containing, key=lambda d: _area(d["box"])) if containing else None
    if parent is None:
        text_area = max(1, text_box[2] - text_box[0]) * max(1, text_box[3] - text_box[1])
        overlaps = [(d, _intersection(text_box, d["box"])) for d in bubble_detections]
        overlaps = [(d, n) for d, n in overlaps
                    if n >= text_area * PARENT_BUBBLE_OVERLAP_FRACTION]
        if overlaps:
            parent = max(overlaps, key=lambda pair: (pair[1], pair[0]["score"]))[0]
    if parent is None:
        return None, None
    siblings = [d["box"] for d in bubble_detections if d is not parent]
    return _trim_parent(parent["box"], text_box, siblings), parent


def plan_erase_regions(regions: list[dict], raw_detections,
                       min_confidence: float | None = None) -> tuple[list[dict], list[dict]]:
    """PageInpaintingPlanner.computeMask port, retaining audit records.

    Kotlin truncates block geometry to Int and stores the detector bbox in
    TranslationBlock.x/y/width/height. ``ocr_box`` is only the padded OCR crop
    in this studio, so the planner deliberately uses ``box`` here.
    """
    detections = _raw_detections(raw_detections, min_confidence)
    bubble_dets = [d for d in detections if d["label"] == 0]
    detection_by_artifact = {d["artifact_id"]: d for d in detections
                             if d.get("artifact_id")}
    ocr_boxes = []
    region_entries = []
    records = []

    for index, region in enumerate(regions or []):
        rid = str(region.get("id") or f"r{index:02d}")
        box = _valid_box(region.get("box"))
        label = int(region.get("label", 2))
        text = region.get("text") or ""
        readable = bool(str(text).strip())
        detector_source = detection_by_artifact.get(region.get("artifact_id"), {})
        source_meta = detector_source.get("source") or {}
        if box is not None:
            ocr_boxes.append(box)
        rec = {
            "id": rid,
            "artifact_id": region.get("artifact_id"),
            "kind": "ocr-region",
            "text": str(text),
            "label": label,
            "score": region.get("score"),
            "source_boxes": ([{"source": "detector", "role": "ocr-origin",
                               "box": box, "label": label,
                               "score": region.get("score"),
                               "artifact_id": region.get("artifact_id"),
                               "model": source_meta.get("model"),
                               "asset": source_meta.get("asset"),
                               "asset_sha": source_meta.get("asset_sha"),
                               "window": source_meta.get("window")},
                              {"source": "ocr", "role": "text-rect",
                               "box": box}] if box else []),
            "route_taken": "skipped:blank-ocr" if not readable else "",
            "mask_component_id": None,
            "segmenter_component_id": None,
            "erase_mask_component_id": None,
            "_segmenter_assignment": (region.get("segmenter_assignment")
                                      or detector_source.get("segmenter_assignment")),
            "context_crop_bbox": None,
            "timing_ms": {"planning": 0.0, "refinement": 0.0,
                          "fill": 0.0, "total": 0.0},
            "timing_scope": "region",
            "_mask_boxes": [box] if box else [],
            "_readable": readable,
            "_segmentation_indices": [],
        }
        records.append(rec)
        region_entries.append({"region": region, "record": rec, "box": box,
                               "label": label, "readable": readable})

    parent_items = []
    parent_by_key = {}
    text_items = []
    for entry in region_entries:
        if not entry["readable"] or entry["box"] is None:
            continue
        parent, parent_detection = _select_parent_with_source(
            entry["box"], entry["label"], bubble_dets)
        if parent:
            entry["record"]["parent_bubble"] = parent
            if parent_detection and parent_detection.get("artifact_id"):
                entry["record"]["parent_bubble_artifact_id"] = parent_detection["artifact_id"]
            entry["record"]["source_boxes"].append({
                "source": "detector", "role": "parent-bubble", "box": parent,
                "artifact_id": (parent_detection or {}).get("artifact_id"),
                "score": (parent_detection or {}).get("score"),
                "model": ((parent_detection or {}).get("source") or {}).get("model"),
                "asset": ((parent_detection or {}).get("source") or {}).get("asset"),
                "asset_sha": ((parent_detection or {}).get("source") or {}).get(
                    "asset_sha"),
                "window": ((parent_detection or {}).get("source") or {}).get("window")})
            key = tuple(parent)
            if key not in parent_by_key:
                item = {"box": parent, "label": 0, "kind": "parent-bubble",
                        "record_ids": []}
                parent_by_key[key] = item
                parent_items.append(item)
            parent_by_key[key]["record_ids"].append(entry["record"]["id"])
        item = {"box": entry["box"], "label": entry["label"],
                "kind": "ocr-text", "record_ids": [entry["record"]["id"]],
                "source": "ocr"}
        text_items.append(item)

    # PageInpaintingPlanner checks expanded detector boxes against every OCR
    # block, including blank blocks, but only readable OCR blocks enter output.
    extra_items = []
    seen_detector_boxes = set()
    for detection in detections:
        if detection["label"] not in (1, 2):
            continue
        raw_box = detection["box"]
        key = tuple(raw_box)
        if key in seen_detector_boxes:
            continue
        seen_detector_boxes.add(key)
        expanded = [max(0, raw_box[0] - DETECTOR_PAD),
                    max(0, raw_box[1] - DETECTOR_PAD),
                    raw_box[2] + DETECTOR_PAD,
                    raw_box[3] + DETECTOR_PAD]
        if any(_iou(expanded, ocr_box) > DETECTOR_OCR_IOU for ocr_box in ocr_boxes):
            continue
        rec = {
            "id": f"detector-{detection['id']}",
            "artifact_id": detection.get("artifact_id"),
            "kind": "detector-only",
            "label": 2,
            "score": detection["score"],
            "text": "",
            "source_boxes": [
                {"source": "detector", "role": "proposal", "box": raw_box,
                 "label": detection["label"], "score": detection["score"],
                 "artifact_id": detection.get("artifact_id"),
                 "model": ((detection.get("source") or {}).get("model")),
                 "asset": ((detection.get("source") or {}).get("asset")),
                 "asset_sha": ((detection.get("source") or {}).get("asset_sha")),
                 "window": ((detection.get("source") or {}).get("window"))},
                {"source": "detector", "role": "erase-box-plus-3px", "box": expanded},
            ],
            "route_taken": "",
            "mask_component_id": None,
            "segmenter_component_id": None,
            "erase_mask_component_id": None,
            "_segmenter_assignment": detection.get("segmenter_assignment"),
            "context_crop_bbox": None,
            "timing_ms": {"planning": 0.0, "refinement": 0.0,
                          "fill": 0.0, "total": 0.0},
            "timing_scope": "region",
            "_mask_boxes": [expanded],
            "_readable": True,
            "_segmentation_indices": [],
        }
        records.append(rec)
        extra_items.append({"box": expanded, "label": 2, "kind": "detector-text",
                            "record_ids": [rec["id"]], "source": "detector"})

    for rec in records:
        rec["timing_ms"]["planning"] = 0.0
    return parent_items + text_items + extra_items, records


def partition_erase_items(items: list[dict], labels: list[int] | None = None) -> tuple[list[dict], list[dict]]:
    """AOTInpainting.inpaintRegions' label partition and 12% bubble test."""
    if labels is not None and len(labels) == len(items):
        labels_now = [int(v) for v in labels]
    else:
        labels_now = [2] * len(items)
    normalized = []
    bubbles = []
    for item, label in zip(items, labels_now):
        clone = dict(item)
        clone["label"] = label
        if label == 0:
            bubbles.append(clone["box"])
        else:
            normalized.append(clone)
    bubble_text, free_text = [], []
    for item in normalized:
        box = item["box"]
        cx, cy = (box[0] + box[2]) / 2.0, (box[1] + box[3]) / 2.0
        center_inside = any(cx >= b[0] and cx <= b[2] and cy >= b[1] and cy <= b[3]
                            for b in bubbles)
        area = max(1, box[2] - box[0]) * max(1, box[3] - box[1])
        overlaps = any(_intersection(box, b) / area >= BUBBLE_OVERLAP_FRACTION
                       for b in bubbles)
        if center_inside or overlaps or item["label"] != 2:
            bubble_text.append(item)
        else:
            free_text.append(item)
    return bubble_text, free_text


def _mask_array(mask_obj, shape: tuple[int, int]) -> np.ndarray | None:
    candidate = getattr(mask_obj, "mask", None)
    if candidate is None:
        candidate = getattr(mask_obj, "labels", None)
        if candidate is not None:
            candidate = np.asarray(candidate) > 0
    if candidate is None and isinstance(mask_obj, np.ndarray):
        candidate = mask_obj
    if candidate is None:
        return None
    arr = np.asarray(candidate, dtype=bool)
    if arr.shape != shape:
        return None
    return arr


def _mask_bounds(mask_obj, array: np.ndarray) -> list[int] | None:
    bounds = getattr(mask_obj, "bounds", None)
    if bounds is not None and len(bounds) >= 4:
        b = _valid_box(bounds)
        if b:
            return b
    ys, xs = np.where(array)
    return [int(xs.min()), int(ys.min()), int(xs.max()) + 1, int(ys.max()) + 1] if xs.size else None


def _assigned_segmentations(regions, records, seg_masks, shape):
    arrays = [_mask_array(m, shape) for m in (seg_masks or [])]
    bounds = [_mask_bounds(m, arr) if arr is not None else None
              for m, arr in zip(seg_masks or [], arrays)]
    by_id = {r["id"]: r for r in records}
    assigned: list[int] = []
    for index, region in enumerate(regions or []):
        box = _valid_box(region.get("box"))
        if box is None:
            continue
        cx = int((box[0] + box[2]) / 2.0)
        cy = int((box[1] + box[3]) / 2.0)
        rid = str(region.get("id") or f"r{index:02d}")
        rec = by_id.get(rid)
        for mask_index, arr in enumerate(arrays):
            if arr is not None and 0 <= cy < arr.shape[0] and 0 <= cx < arr.shape[1] and arr[cy, cx]:
                assigned.append(mask_index)
                if rec is not None:
                    rec["_segmentation_indices"].append(mask_index)
                    rec["source_boxes"].append({
                        "source": "bubble-segmenter", "role": "assigned-mask",
                        "mask_ref": f"s{mask_index:03d}", "box": bounds[mask_index]})
    return arrays, bounds, assigned


def _captured_segmenter_union(records, mask_cache, segmenter_outputs,
                              shape: tuple[int, int], enabled: bool,
                              selected_record_ids: set[str]):
    """Resolve bubble-plan assignments into one union, without per-component pages."""
    height, width = shape
    raw_union = np.zeros((height, width), dtype=bool)
    output_bounds = []
    seen = set()
    selected = []
    resolved = {}
    for rec in records:
        assignment = rec.get("_segmenter_assignment")
        if not assignment:
            rec["mask_resolution"] = {
                "status": "no-assignment" if enabled else "disabled"}
            continue
        mask_ref = assignment.get("mask_ref")
        component_id = assignment.get("mask_component_id")
        source = assignment.get("source") or {}
        if not enabled:
            rec["segmenter_component_id"] = component_id
            rec["mask_resolution"] = {
                "status": "disabled", "mask_ref": mask_ref,
                "segmenter_component_id": component_id,
            }
            rec["source_boxes"].append({
                "source": "bubble-segmenter", "role": "assigned-mask-disabled",
                "artifact_id": mask_ref, "mask_ref": mask_ref,
                "segmenter_component_id": component_id,
                "model": source.get("model"), "asset": source.get("asset"),
                "asset_sha": source.get("asset_sha"),
                "window": source.get("window"),
            })
            continue
        if str(rec.get("id")) not in selected_record_ids:
            rec["segmenter_component_id"] = component_id
            rec["mask_resolution"] = {
                "status": "not-selected-for-bubble-leg",
                "mask_ref": mask_ref,
                "segmenter_component_id": component_id,
            }
            rec["source_boxes"].append({
                "source": "bubble-segmenter", "role": "assigned-mask-unused",
                "artifact_id": mask_ref, "mask_ref": mask_ref,
                "segmenter_component_id": component_id,
                "model": source.get("model"), "asset": source.get("asset"),
                "asset_sha": source.get("asset_sha"),
                "window": source.get("window"),
            })
            continue
        cache_key = (mask_ref, component_id)
        if cache_key not in resolved:
            resolved[cache_key] = resolve_mask_component(
                mask_ref, component_id, mask_cache, segmenter_outputs)
        view, error = resolved[cache_key]
        if view is None:
            rec["segmenter_component_id"] = component_id
            rec["mask_resolution"] = {
                "status": error or "missing-reference", "degraded": True,
                "mask_ref": mask_ref, "segmenter_component_id": component_id,
            }
            rec["source_boxes"].append({
                "source": "bubble-segmenter", "role": "assigned-mask",
                "artifact_id": mask_ref, "mask_ref": mask_ref,
                "segmenter_component_id": component_id,
                "model": source.get("model"), "asset": source.get("asset"),
                "asset_sha": source.get("asset_sha"),
                "window": source.get("window"), "resolution": error,
            })
            continue
        if view.width != width or view.height != height:
            rec["segmenter_component_id"] = component_id
            rec["mask_resolution"] = {
                "status": "page-dimension-mismatch", "degraded": True,
                "mask_ref": mask_ref, "segmenter_component_id": component_id,
            }
            continue
        rec["segmenter_component_id"] = view.component_id
        rec["mask_resolution"] = {
            "status": "resolved", "mask_ref": view.mask_ref,
            "segmenter_component_id": view.component_id,
        }
        rec["source_boxes"].append({
            "source": "bubble-segmenter", "role": "assigned-mask",
            "artifact_id": view.mask_ref, "mask_ref": view.mask_ref,
            "segmenter_component_id": view.component_id,
            "model": view.source.get("model"), "asset": view.source.get("asset"),
            "asset_sha": view.source.get("asset_sha"),
            "window": view.source.get("window"),
            "box": list(view.bounds),
        })
        if view.key in seen:
            continue
        seen.add(view.key)
        selected.append({"mask_ref": view.mask_ref,
                         "segmenter_component_id": view.component_id,
                         "source": dict(view.source)})
        output_bounds.append(list(view.bounds))
        # Split any flat run at page-row boundaries while writing directly to
        # the one page union required by Android's erosion operation.
        for y, x1, x2 in iter_rle_row_spans(view.runs, width, height):
            raw_union[y, x1:x2] = True
    for rec in records:
        rec.pop("_segmenter_assignment", None)
    return raw_union, output_bounds, selected


def _disk(radius: int) -> np.ndarray:
    yy, xx = np.mgrid[-radius:radius + 1, -radius:radius + 1]
    return (xx * xx + yy * yy) <= radius * radius


def _erode_segmentation_union(raw_union: np.ndarray, bounds_list: list[list[int]],
                              width: int, height: int) -> np.ndarray:
    """AOTInpainting.erodeBinaryMask: disk 5, then disk 2, else raw pixels."""
    if not raw_union.any():
        return np.zeros((height, width), dtype=bool)
    eroded5 = ndimage.binary_erosion(raw_union, structure=_disk(BUBBLE_SEG_EROSION),
                                     border_value=0)
    eroded2 = None
    out = np.zeros((height, width), dtype=bool)
    for box in bounds_list:
        if not box:
            continue
        x1, y1, x2, y2 = box
        x1, y1 = max(0, x1), max(0, y1)
        x2, y2 = min(width, x2), min(height, y2)
        if x2 <= x1 or y2 <= y1:
            continue
        local5 = eroded5[y1:y2, x1:x2]
        if local5.any():
            out[y1:y2, x1:x2] |= local5
            continue
        if eroded2 is None:
            eroded2 = ndimage.binary_erosion(raw_union, structure=_disk(max(1, BUBBLE_SEG_EROSION // 2)),
                                             border_value=0)
        local2 = eroded2[y1:y2, x1:x2]
        if local2.any():
            out[y1:y2, x1:x2] |= local2
        else:
            raw_local = raw_union[y1:y2, x1:x2]
            if raw_local.any():
                out[y1:y2, x1:x2] |= raw_local
    return out


def _bubble_erase_mask(bubble_text: list[dict], assigned_masks, all_masks,
                       mask_bounds, height: int, width: int,
                       raw_union_override: np.ndarray | None = None) -> np.ndarray:
    raw_union = (raw_union_override if raw_union_override is not None
                 else np.zeros((height, width), dtype=bool))
    seg_bounds = []
    if raw_union_override is None:
        for idx in assigned_masks:
            arr = all_masks[idx]
            if arr is None:
                continue
            raw_union |= arr
            if mask_bounds[idx] is not None:
                seg_bounds.append(mask_bounds[idx])
    else:
        seg_bounds = list(mask_bounds or [])
    out = _erode_segmentation_union(raw_union, seg_bounds, width, height)

    fallback_boxes = []
    for item in bubble_text:
        box = item["box"]
        x1, y1 = max(0, box[0]), max(0, box[1])
        x2, y2 = min(width, box[2]), min(height, box[3])
        if x2 <= x1 or y2 <= y1:
            continue
        # Android excludes a box if any OCR-assigned RLE mask overlaps even
        # one pixel, before the segmentation union is eroded.
        if raw_union[y1:y2, x1:x2].any():
            continue
        fallback_boxes.append(box)
    if fallback_boxes:
        out |= aot_inpaint.build_dynamic_pill_mask(
            fallback_boxes, width, height, pad=BUBBLE_BOX_PAD)
    return out


def _free_mask_for_boxes(boxes, width: int, height: int) -> np.ndarray:
    return aot_inpaint.build_fixed_pill_mask(
        boxes, width, height, pad=FREE_TEXT_PAD, dilate_radius=FREE_TEXT_DILATE)


def _cluster_refined_groups(groups: list[list[dict]], max_size: int = AOT_REPORT_CONTEXT):
    """AotBoxGeometry.clusterFreeTextGroups with source IDs retained."""
    clusters = []
    for group in groups:
        if not group:
            continue
        boxes = [m["box"] for m in group]
        clusters.append({"members": list(group), "min_x": min(b[0] for b in boxes),
                        "min_y": min(b[1] for b in boxes),
                        "max_x": max(b[2] for b in boxes),
                        "max_y": max(b[3] for b in boxes)})
    while True:
        best_i = best_j = -1
        best_area = None
        for i in range(len(clusters)):
            for j in range(i + 1, len(clusters)):
                a, b = clusters[i], clusters[j]
                x1, y1 = min(a["min_x"], b["min_x"]), min(a["min_y"], b["min_y"])
                x2, y2 = max(a["max_x"], b["max_x"]), max(a["max_y"], b["max_y"])
                if x2 - x1 <= max_size and y2 - y1 <= max_size:
                    area = (x2 - x1) * (y2 - y1)
                    if best_area is None or area < best_area:
                        best_i, best_j, best_area = i, j, area
        if best_i < 0:
            break
        a, b = clusters[best_i], clusters[best_j]
        a["members"].extend(b["members"])
        a["min_x"] = min(a["min_x"], b["min_x"])
        a["min_y"] = min(a["min_y"], b["min_y"])
        a["max_x"] = max(a["max_x"], b["max_x"])
        a["max_y"] = max(a["max_y"], b["max_y"])
        clusters.pop(best_j)
    return [c["members"] for c in clusters]


def _refine_free_text(page: Image.Image, free_items: list[dict], paddle_det,
                      record_map: dict[str, dict]) -> tuple[list[list[dict]], dict[str, float]]:
    w, h = page.size
    groups = []
    per_record_ms = {}
    for item in free_items:
        box = item["box"]
        ids = item["record_ids"]
        rec_id = ids[0] if ids else None
        started = time.perf_counter()
        refined = []
        crop_box = [max(0, box[0] - PADDLE_CROP_PAD),
                    max(0, box[1] - PADDLE_CROP_PAD),
                    min(w, box[2] + PADDLE_CROP_PAD),
                    min(h, box[3] + PADDLE_CROP_PAD)]
        if paddle_det is not None and crop_box[2] > crop_box[0] and crop_box[3] > crop_box[1]:
            try:
                crop = page.crop(tuple(crop_box))
                lines = paddle_det.detect_lines(crop, thresh=PADDLE_THRESH,
                                                box_thresh=PADDLE_BOX_THRESH)
                for line in lines or []:
                    line_box = getattr(line, "bbox", line)
                    local = _valid_box(line_box)
                    if local is None:
                        continue
                    projected = [max(0, min(w, crop_box[0] + local[0])),
                                 max(0, min(h, crop_box[1] + local[1])),
                                 max(0, min(w, crop_box[0] + local[2])),
                                 max(0, min(h, crop_box[1] + local[3]))]
                    if projected[2] > projected[0] and projected[3] > projected[1]:
                        refined.append(projected)
            except Exception:
                refined = []
        if not refined:
            refined = [box]
        duration = round((time.perf_counter() - started) * 1000, 3)
        if rec_id:
            per_record_ms[rec_id] = duration
            rec = record_map.get(rec_id)
            if rec is not None:
                rec["timing_ms"]["refinement"] = duration
                if refined != [box]:
                    rec["source_boxes"].extend(
                        {"source": "paddle-refined", "parent_region": rec_id,
                         "box": b} for b in refined)
                rec["_mask_boxes"] = list(refined)
        groups.append([{"box": b, "record_ids": ids, "source": "paddle-refined"
                        if refined != [box] else "detector-fallback"} for b in refined])
    return _cluster_refined_groups(groups), per_record_ms


def _clamp_crop(box, width: int, height: int) -> list[int] | None:
    x1, y1 = max(0, int(box[0])), max(0, int(box[1]))
    x2, y2 = min(width, int(box[2])), min(height, int(box[3]))
    return [x1, y1, x2, y2] if x2 > x1 and y2 > y1 else None


def _component_crop_bbox(component: np.ndarray, margin: int,
                         width: int, height: int) -> list[int] | None:
    ys, xs = np.where(component)
    if not xs.size:
        return None
    return _clamp_crop([int(xs.min()) - margin, int(ys.min()) - margin,
                        int(xs.max()) + 1 + margin, int(ys.max()) + 1 + margin],
                       width, height)


def _blend(original: np.ndarray, reconstructed: np.ndarray,
           alpha: np.ndarray) -> np.ndarray:
    a = np.asarray(alpha, dtype=np.float32)[..., None]
    values = original.astype(np.float32) * (1.0 - a) + reconstructed.astype(np.float32) * a
    return np.clip(np.floor(values + 0.5), 0, 255).astype(np.uint8)


def _mask_feather(mask: np.ndarray, ramp: int) -> np.ndarray:
    # aot_inpaint's two-pass (3,4) chamfer field mirrors BubbleMaskBuilder.
    return aot_inpaint.feather_alpha_field(mask.astype(bool), max(2, int(ramp)))


def _fast_reconstruct(page: Image.Image, boxes, method: str,
                      mask_override: np.ndarray | None = None) -> tuple[Image.Image, np.ndarray, str, list[int] | None]:
    """Android FAST crop/mask/Telea path, or the parity PushPull alternative."""
    w, h = page.size
    bounds = (aot_inpaint.padded_union_bounds(boxes, w, h, FREE_TEXT_CONTEXT)
              if boxes else _component_crop_bbox(mask_override, FREE_TEXT_CONTEXT, w, h))
    if bounds is None:
        return page, np.zeros((h, w), dtype=bool), method, None
    x1, y1, x2, y2 = bounds
    local_boxes = [[max(0, b[0] - x1), max(0, b[1] - y1),
                    min(x2 - x1, b[2] - x1), min(y2 - y1, b[3] - y1)]
                   for b in (boxes or [])]
    original = np.asarray(page.crop(tuple(bounds)).convert("RGB"), dtype=np.uint8)
    if mask_override is not None:
        local_mask = mask_override[y1:y2, x1:x2].astype(bool)
    else:
        local_mask = _free_mask_for_boxes(local_boxes, x2 - x1, y2 - y1)
    page_mask = np.zeros((h, w), dtype=bool)
    page_mask[y1:y2, x1:x2] = local_mask
    if not local_mask.any():
        return page, page_mask, method, bounds
    work = original.copy()
    route = method
    if method == "opencv":
        try:
            import cv2
            work = cv2.inpaint(original, local_mask.astype(np.uint8) * 255,
                               3.0, cv2.INPAINT_TELEA)
        except Exception:
            route = "opencv->pushpull"
            bg = aot_inpaint.local_ring_median(work, local_mask, aot_inpaint.DEFAULT_RING)
            aot_inpaint.push_pull_fill(work, local_mask, bg)
    elif method == "pushpull":
        bg = aot_inpaint.local_ring_median(work, local_mask, aot_inpaint.DEFAULT_RING)
        aot_inpaint.push_pull_fill(work, local_mask, bg)
    else:
        raise ValueError(f"unsupported FAST reconstruction method: {method}")
    alpha = _mask_feather(local_mask, FREE_TEXT_FEATHER)
    output = _blend(original, work, alpha)
    page.paste(Image.fromarray(output), (x1, y1))
    return page, page_mask, route, bounds


def _centered_report_crop(boxes, width: int, height: int) -> list[int] | None:
    return aot_inpaint.centered_report_crop(boxes, width, height, AOT_REPORT_CONTEXT)


def _guard_rejects(candidate: np.ndarray, mask: np.ndarray) -> bool:
    luma = (0.299 * candidate[..., 0] + 0.587 * candidate[..., 1]
            + 0.114 * candidate[..., 2])
    masked_luma = luma[mask]
    unmasked_luma = luma[~mask]
    if masked_luma.size < AOT_GUARD_MASKED_PIXELS:
        return False
    mean = float(masked_luma.mean())
    variance = float(masked_luma.var())
    channel_delta_map = (np.abs(candidate[..., 0] - candidate[..., 1])
                         + np.abs(candidate[..., 1] - candidate[..., 2]))
    channel_delta = float(channel_delta_map[mask].mean())
    uniform = variance < AOT_GUARD_MAX_LUMA_VARIANCE and channel_delta < AOT_GUARD_MAX_CHANNEL_DELTA
    if not uniform:
        return False
    unmasked_white = unmasked_luma.size > 0 and float(unmasked_luma.mean()) >= 200.0
    uniform_white = mean >= AOT_GUARD_NEAR_WHITE and not unmasked_white
    unmasked_black = unmasked_luma.size > 0 and float(unmasked_luma.mean()) <= 40.0
    uniform_black = mean <= AOT_GUARD_NEAR_BLACK and not unmasked_black
    uniform_gray = AOT_GUARD_MID_GRAY[0] <= mean <= AOT_GUARD_MID_GRAY[1]
    return uniform_white or uniform_black or uniform_gray


def _fixed_aot_candidate(source: np.ndarray, mask: np.ndarray, aot) -> tuple[np.ndarray, bool]:
    """Fixed AOT-512 candidate; delegates tensor/session setup to legacy class."""
    session = aot._session()
    inputs, offset, grayscale = aot._prepare_fixed_input(source, mask.astype(np.uint8))
    out = session.run(None, inputs)[0][0]
    reconstructed = np.clip(np.floor(out.transpose(1, 2, 0) * 255.0 + 0.5),
                            0, 255).astype(np.uint8)
    side = source.shape[0]
    reconstructed = reconstructed[offset:offset + side, offset:offset + side]
    if grayscale:
        luma = 0.299 * reconstructed[..., 0] + 0.587 * reconstructed[..., 1] + 0.114 * reconstructed[..., 2]
        gray = np.clip(np.floor(luma + 0.5), 0, 255).astype(np.uint8)
        reconstructed = np.stack([gray, gray, gray], axis=2)
    rejected = aot._guard_rejects(reconstructed.astype(np.float32), mask)
    return reconstructed, rejected


def _dynamic_session(aot):
    cached = getattr(aot, "_android_dynamic_session", None)
    if cached is not None:
        return cached
    if not AOT_DYNAMIC_MODEL.exists():
        return None
    import onnxruntime as ort
    opts = ort.SessionOptions()
    opts.log_severity_level = 3
    cached = ort.InferenceSession(str(AOT_DYNAMIC_MODEL), opts,
                                  providers=["CPUExecutionProvider"])
    setattr(aot, "_android_dynamic_session", cached)
    return cached


def _dynamic_feed(session, image: np.ndarray, mask: np.ndarray) -> dict:
    names = {i.name for i in session.get_inputs()}
    if names == {"image", "mask"}:
        return {"image": image, "mask": mask}
    if names == {"input_image", "input_mask"}:
        return {"input_image": image, "input_mask": mask}
    if names == {"input", "mask"}:
        return {"input": image, "mask": mask}
    raise ValueError(f"unsupported AOT input names: {sorted(names)}")


def _dynamic_aot_candidate(source: np.ndarray, mask: np.ndarray, session) -> tuple[np.ndarray, bool]:
    """Dynamic AOT candidate with Android NCHW [-1,1] input and zeroed holes.

    The report path supplies an already-local centered crop, so its 32 px
    generic margin is not added. Shapes are still aligned to 8; the generic
    implementation also caps oversized input at 768 before inference.
    """
    height, width = source.shape[:2]
    scale = min(1.0, AOT_MAX_DIM / float(max(width, height)))
    if scale < 1.0:
        infer_w = max(8, int(width * scale))
        infer_h = max(8, int(height * scale))
        infer_w = infer_w + (AOT_ALIGN - infer_w % AOT_ALIGN) % AOT_ALIGN
        infer_h = infer_h + (AOT_ALIGN - infer_h % AOT_ALIGN) % AOT_ALIGN
        resized = np.asarray(Image.fromarray(source).resize((infer_w, infer_h), Image.Resampling.BILINEAR))
        resized_mask = np.asarray(Image.fromarray(mask.astype(np.uint8) * 255).resize(
            (infer_w, infer_h), Image.Resampling.NEAREST)) > 127
    else:
        infer_w = width + (AOT_ALIGN - width % AOT_ALIGN) % AOT_ALIGN
        infer_h = height + (AOT_ALIGN - height % AOT_ALIGN) % AOT_ALIGN
        resized = np.zeros((infer_h, infer_w, 3), dtype=np.uint8)
        resized[:height, :width] = source
        resized_mask = np.zeros((infer_h, infer_w), dtype=bool)
        resized_mask[:height, :width] = mask
    normalized = resized.astype(np.float32) / 127.5 - 1.0
    normalized[resized_mask] = 0.0
    image_tensor = np.ascontiguousarray(normalized.transpose(2, 0, 1)[None], dtype=np.float32)
    mask_tensor = np.ascontiguousarray(resized_mask[None, None].astype(np.float32))
    out = session.run(None, _dynamic_feed(session, image_tensor, mask_tensor))[0][0]
    out = np.clip(np.floor((out.transpose(1, 2, 0) + 1.0) * 127.5 + 0.5),
                  0, 255).astype(np.uint8)
    out = out[:infer_h, :infer_w]
    if scale < 1.0:
        out = np.asarray(Image.fromarray(out).resize((width, height), Image.Resampling.BILINEAR))
    out = out[:height, :width]
    # Same sampled-chroma grayscale decision used by Android's fixed and
    # dynamic AOT decoder.
    flat = source.reshape(-1, 3)
    stride = max(1, flat.shape[0] // 400)
    sampled = flat[::stride].astype(np.int16)
    grayscale = int((sampled.max(axis=1) - sampled.min(axis=1)).sum()) // max(1, sampled.shape[0]) < 15
    if grayscale:
        gray = np.clip(np.floor(0.299 * out[..., 0] + 0.587 * out[..., 1]
                                + 0.114 * out[..., 2] + 0.5), 0, 255).astype(np.uint8)
        out = np.stack([gray, gray, gray], axis=2)
    return out, _guard_rejects(out.astype(np.float32), mask)


def _aot_reconstruct(page: Image.Image, boxes, aot,
                     mask_override: np.ndarray | None = None) -> tuple[Image.Image, np.ndarray, str, list[int] | None]:
    w, h = page.size
    if boxes:
        crop = _centered_report_crop(boxes, w, h)
    else:
        component_bounds = _component_crop_bbox(mask_override, 0, w, h)
        crop = (_centered_report_crop([component_bounds], w, h)
                if component_bounds else None)
    if crop is None:
        return page, np.zeros((h, w), dtype=bool), "aot->opencv", None
    x1, y1, x2, y2 = crop
    side = x2 - x1
    if side <= 0 or y2 - y1 != side:
        return page, np.zeros((h, w), dtype=bool), "aot->opencv", crop
    local_boxes = [[max(0, b[0] - x1), max(0, b[1] - y1),
                    min(side, b[2] - x1), min(side, b[3] - y1)]
                   for b in (boxes or [])]
    if mask_override is None:
        local_mask = _free_mask_for_boxes(local_boxes, side, side)
    else:
        local_mask = mask_override[y1:y2, x1:x2].astype(bool)
    page_mask = np.zeros((h, w), dtype=bool)
    page_mask[y1:y2, x1:x2] = local_mask
    if not local_mask.any() or aot is None:
        page, fast_mask, fast_route, fast_bounds = _fast_reconstruct(page, boxes, "opencv", mask_override)
        page_mask |= fast_mask
        return page, page_mask, "aot-unavailable->" + fast_route, fast_bounds or crop

    original = np.asarray(page.crop(tuple(crop)).convert("RGB"), dtype=np.uint8)
    attempts = []
    fixed_oom = False
    fixed_model = getattr(aot_inpaint, "AOT_MODEL", None)
    try:
        if fixed_model is not None and fixed_model.exists():
            candidate, rejected = _fixed_aot_candidate(original, local_mask, aot)
            attempts.append("aot-fixed512")
            if not rejected:
                route = "aot-fixed512"
                alpha = _mask_feather(local_mask, FREE_TEXT_FEATHER)
                page.paste(Image.fromarray(_blend(original, candidate, alpha)), (x1, y1))
                return page, page_mask, route, crop
            attempts[-1] += "-uniformity-rejected"
        else:
            attempts.append("aot-fixed512-unavailable")
    except MemoryError:
        fixed_oom = True
        attempts.append("aot-fixed512-oom")
    except Exception as exc:
        attempts.append(f"aot-fixed512-failed:{type(exc).__name__}")

    if not fixed_oom:
        try:
            dynamic = _dynamic_session(aot)
            if dynamic is not None:
                candidate, rejected = _dynamic_aot_candidate(original, local_mask, dynamic)
                attempts.append("aot-dynamic")
                if not rejected:
                    alpha = _mask_feather(local_mask, FREE_TEXT_FEATHER)
                    page.paste(Image.fromarray(_blend(original, candidate, alpha)), (x1, y1))
                    return page, page_mask, "->".join(attempts), crop
                attempts[-1] += "-uniformity-rejected"
            else:
                attempts.append("aot-dynamic-unavailable")
        except MemoryError:
            attempts.append("aot-dynamic-oom")
        except Exception as exc:
            attempts.append(f"aot-dynamic-failed:{type(exc).__name__}")
    else:
        attempts.append("aot-dynamic-skipped-after-fixed-oom")

    page, fast_mask, fast_route, fast_bounds = _fast_reconstruct(
        page, boxes, "opencv", mask_override)
    page_mask |= fast_mask
    attempts.append(fast_route)
    return page, page_mask, "->".join(attempts), fast_bounds or crop


def _connected_components(mask: np.ndarray):
    return ndimage.label(mask, structure=_STRUCT_8)


def _mask_component_for_boxes(labels: np.ndarray, boxes) -> int | None:
    h, w = labels.shape
    counts = {}
    for box in boxes or []:
        clipped = _clamp_crop(box, w, h)
        if clipped is None:
            continue
        x1, y1, x2, y2 = clipped
        local = labels[y1:y2, x1:x2]
        vals, n = np.unique(local[local > 0], return_counts=True)
        for value, count in zip(vals, n):
            counts[int(value)] = counts.get(int(value), 0) + int(count)
    if not counts:
        return None
    return max(counts, key=lambda value: (counts[value], -value))


def _bubble_non_android(page: Image.Image, mask: np.ndarray, leg: str, aot):
    """Run one experimental bubble fill leg over 8-connected mask components."""
    height, width = mask.shape
    labels, count = _connected_components(mask)
    result = page
    comp_routes, comp_crops = {}, {}
    for component_id in range(1, count + 1):
        component = labels == component_id
        if leg == "opencv":
            result, _, route, crop = _fast_reconstruct(result, [], "opencv", component)
        elif leg == "pushpull":
            result, _, route, crop = _fast_reconstruct(result, [], "pushpull", component)
        elif leg == "aot":
            result, _, route, crop = _aot_reconstruct(result, [], aot, component)
        else:
            raise ValueError(f"unsupported bubble leg {leg}")
        comp_routes[component_id] = f"bubble/{route}"
        comp_crops[component_id] = crop
    return result, comp_routes, comp_crops


def inpaint_page_android(page: Image.Image, regions: list[dict],
                         raw_detections=None, seg_masks=None,
                         bubble_leg: str = "android-fill",
                         free_leg: str = "opencv", paddle_det=None,
                         aot=None, mask_cache=None, segmenter_outputs=None,
                         current_capture: bool = False,
                         segmenter_enabled: bool = True,
                         execution_confidence: float | None = None
                         ) -> tuple[Image.Image, np.ndarray, list[dict], dict]:
    """Planner, partition, refinement, mask and composable Android legs."""
    if bubble_leg not in BUBBLE_LEGS:
        raise ValueError(f"invalid bubble inpaint leg: {bubble_leg}")
    if free_leg not in FREE_LEGS:
        raise ValueError(f"invalid free-text inpaint leg: {free_leg}")
    started_all = time.perf_counter()
    image = page.convert("RGB").copy()
    width, height = image.size
    plan_started = time.perf_counter()
    items, records = plan_erase_regions(
        regions, raw_detections,
        min_confidence=(execution_confidence if current_capture else None))
    record_map = {r["id"]: r for r in records}
    # All input labels are carried with items; this explicit argument also
    # preserves Android's all-label-2 behavior for a malformed/missing label list.
    labels = [item["label"] for item in items]
    bubble_items, free_items = partition_erase_items(items, labels)
    bubble_record_ids = {rid for item in bubble_items for rid in item["record_ids"]}
    segmenter_selected = []
    if current_capture:
        captured_union, bounds, segmenter_selected = _captured_segmenter_union(
            records, mask_cache or {}, segmenter_outputs or [],
            (height, width), segmenter_enabled, bubble_record_ids)
        arrays, assigned = [], []
    else:
        arrays, bounds, assigned = _assigned_segmentations(
            regions, records, seg_masks, (height, width))
        captured_union = None
    plan_ms = round((time.perf_counter() - plan_started) * 1000, 3)
    for rec in records:
        rec["timing_ms"]["planning"] = plan_ms

    bubble_mask = _bubble_erase_mask(
        bubble_items, assigned, arrays, bounds, height, width,
        raw_union_override=captured_union)
    free_groups, _ = _refine_free_text(image, free_items, paddle_det, record_map)
    free_mask = np.zeros((height, width), dtype=bool)
    for group in free_groups:
        boxes = [member["box"] for member in group]
        bounds_group = aot_inpaint.padded_union_bounds(boxes, width, height, FREE_TEXT_CONTEXT)
        if bounds_group is None:
            continue
        x1, y1, x2, y2 = bounds_group
        local = [[max(0, b[0] - x1), max(0, b[1] - y1),
                  min(x2 - x1, b[2] - x1), min(y2 - y1, b[3] - y1)] for b in boxes]
        local_mask = _free_mask_for_boxes(local, x2 - x1, y2 - y1)
        free_mask[y1:y2, x1:x2] |= local_mask
        for member in group:
            for rid in member["record_ids"]:
                rec = record_map.get(rid)
                if rec is not None:
                    rec["_mask_boxes"].append(member["box"])

    # Record the pre-fill union as the visible erase mask.
    combined_mask = bubble_mask | free_mask
    component_labels, component_count = _connected_components(combined_mask)
    bubble_component_labels, _ = _connected_components(bubble_mask)
    for rec in records:
        erase_component_id = _mask_component_for_boxes(
            component_labels, rec.get("_mask_boxes", []))
        rec["_bubble_component_id"] = _mask_component_for_boxes(
            bubble_component_labels, rec.get("_mask_boxes", []))
        if erase_component_id is not None:
            erase_component_id = f"c{erase_component_id:03d}"
        rec["erase_mask_component_id"] = erase_component_id
        # Older consumers read this field as the final erase-mask component.
        rec["mask_component_id"] = erase_component_id

    # Bubble leg is applied first, as in AOTInpainting.inpaintRegions.
    bubble_started = time.perf_counter()
    bubble_crop_by_comp = {}
    bubble_route_by_comp = {}
    if bubble_mask.any():
        if bubble_leg == "android-fill":
            pixels = np.asarray(image, dtype=np.uint8).copy()
            aot_inpaint.fill_and_blend(pixels, bubble_mask, BUBBLE_SMOOTH_PASSES,
                                       BUBBLE_FEATHER)
            image = Image.fromarray(pixels)
            labels_b, n_b = _connected_components(bubble_mask)
            for cid in range(1, n_b + 1):
                bubble_route_by_comp[cid] = "bubble/android-fill"
                # Android's report bubble routine processes the page mask
                # directly; there is no cropped bubble inference window.
                bubble_crop_by_comp[cid] = [0, 0, width, height]
        else:
            image, bubble_route_by_comp, bubble_crop_by_comp = _bubble_non_android(
                image, bubble_mask, bubble_leg, aot)
    bubble_ms = round((time.perf_counter() - bubble_started) * 1000, 3)
    for rec in records:
        if rec["id"] not in bubble_record_ids:
            continue
        local_cid = rec.get("_bubble_component_id")
        rec["route_taken"] = bubble_route_by_comp.get(local_cid, f"bubble/{bubble_leg}")
        rec["context_crop_bbox"] = bubble_crop_by_comp.get(local_cid)
        rec["timing_ms"]["fill"] = bubble_ms
        rec["timing_scope"] = "bubble-leg-shared"

    # Free-text leg consumes the Paddle-refined groups and uses Android's
    # 64 px crop even when the selected fill implementation is AOT.
    for group in free_groups:
        boxes = [member["box"] for member in group]
        ids = sorted({rid for member in group for rid in member["record_ids"]})
        if not boxes:
            continue
        t0 = time.perf_counter()
        if free_leg == "aot":
            image, _, route, crop = _aot_reconstruct(image, boxes, aot)
        else:
            image, _, route, crop = _fast_reconstruct(image, boxes, free_leg)
        fill_ms = round((time.perf_counter() - t0) * 1000, 3)
        route = f"freetext/{route}"
        for rid in ids:
            rec = record_map.get(rid)
            if rec is None:
                continue
            rec["route_taken"] = route
            rec["context_crop_bbox"] = crop
            rec["timing_ms"]["fill"] = fill_ms
            if len(ids) > 1:
                rec["timing_scope"] = "free-text-cluster-shared"

    # Any readable record not assigned by partition is explicit in provenance.
    for rec in records:
        if not rec["route_taken"]:
            rec["route_taken"] = "skipped:no-inpaint-route"
        tm = rec["timing_ms"]
        tm["total"] = round(tm.get("planning", 0.0) + tm.get("refinement", 0.0)
                             + tm.get("fill", 0.0), 3)
        for private in ("_mask_boxes", "_readable", "_segmentation_indices",
                        "_segmenter_assignment"):
            rec.pop(private, None)
        rec.pop("_bubble_component_id", None)
    elapsed = round((time.perf_counter() - started_all) * 1000, 3)
    stats = {
        "bubble_leg": bubble_leg,
        "free_leg": free_leg,
        "bubble_boxes": len(bubble_items),
        "free_boxes": len(free_items),
        "free_groups": len(free_groups),
        "bubble_components": int(ndimage.label(bubble_mask, structure=_STRUCT_8)[1]),
        "combined_components": int(component_count),
        "mask_pixels": int(combined_mask.sum()),
        "segmenter_union_pixels": (int(captured_union.sum())
                                   if captured_union is not None else None),
        "segmenter_union_sha256": (
            hashlib.sha256(memoryview(captured_union)).hexdigest()
            if captured_union is not None else None),
        "segmenter_components": segmenter_selected,
        "total_ms": elapsed,
    }
    return image, combined_mask, records, stats
