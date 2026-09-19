#!/usr/bin/env python3
"""Reproducible PP-OCRv6 Small detector benchmark.

This is deliberately a host-side research harness.  It follows the shipped
Paddle preprocessing contract (BGR, ImageNet normalization, CHW) and DB
postprocessing, while preserving the fixed external-page manifest.  Missing
external pages are reported, never replaced by synthetic data.

Examples:
  python research/benchmark_det.py --manifest research/dataset/manifest.json
  python research/benchmark_det.py --limits 640,768 --warm-repeats 1

The default run measures every available page in warm mode and three pages per
limit in cold mode (new ORT session, including session construction).  Use
--cold-pages 0 to disable cold measurements or increase it for a full cold
run.  Results are written as CSV and JSON under research/results unless
--output-dir is supplied.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import os
import platform
import statistics
import subprocess
import sys
import time
from pathlib import Path
from typing import Any, Iterable

import cv2
import numpy as np
import onnxruntime as ort
import psutil
import pyclipper


DEFAULT_MANIFEST = Path(__file__).parent / "dataset" / "manifest.json"
DEFAULT_MODEL = Path(__file__).parent / "cache" / "PP-OCRv6_small_det_onnx" / "inference.onnx"
LIMITS = (640, 768, 960, 1024, 1280)


def percentile(values: list[float], q: float) -> float | None:
    if not values:
        return None
    return float(np.percentile(np.asarray(values, dtype=np.float64), q, method="linear"))


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def git_revision(root: Path) -> str | None:
    try:
        return subprocess.check_output(["git", "-C", str(root), "rev-parse", "HEAD"], text=True).strip()
    except (OSError, subprocess.CalledProcessError):
        return None


def resize_for_test(image: np.ndarray, limit: int) -> tuple[np.ndarray, tuple[int, int, int, int]]:
    """Paddle DetResizeForTest limit=max, preserving aspect and 32 alignment.

    PP-OCR's detector does not upscale pages below max_side_len.  Dimensions
    are ceil'd to a multiple of 32, matching its dynamic-shape requirements.
    The returned tuple is (orig_h, orig_w, resized_h, resized_w).
    """
    h, w = image.shape[:2]
    ratio = min(1.0, float(limit) / max(h, w))
    rh = max(32, int(round(h * ratio / 32.0) * 32))
    rw = max(32, int(round(w * ratio / 32.0) * 32))
    # Keep the long side at or below the requested limit after alignment.
    while max(rh, rw) > limit and max(rh, rw) > 32:
        if rh >= rw:
            rh -= 32
        else:
            rw -= 32
    resized = cv2.resize(image, (rw, rh), interpolation=cv2.INTER_LINEAR)
    return resized, (h, w, rh, rw)


def preprocess(image: np.ndarray, limit: int) -> tuple[np.ndarray, tuple[int, int, int, int]]:
    resized, shape = resize_for_test(image, limit)
    x = resized.astype(np.float32) / 255.0
    x = (x - np.asarray([0.485, 0.456, 0.406], dtype=np.float32)) / np.asarray(
        [0.229, 0.224, 0.225], dtype=np.float32
    )
    return np.ascontiguousarray(x.transpose(2, 0, 1)[None, ...]), shape


def _order_clockwise(points: np.ndarray) -> np.ndarray:
    center = points.mean(axis=0)
    angles = np.arctan2(points[:, 1] - center[1], points[:, 0] - center[0])
    return points[np.argsort(angles)]


def _unclip(box: np.ndarray, ratio: float) -> np.ndarray | None:
    # DBPostProcess uses pyclipper with an offset proportional to area/perimeter.
    contour = box.astype(np.float64)
    perimeter = cv2.arcLength(contour.astype(np.float32), True)
    area = abs(cv2.contourArea(contour.astype(np.float32)))
    if perimeter <= 1e-6:
        return None
    distance = area * ratio / perimeter
    path = [(int(round(x)), int(round(y))) for x, y in contour]
    offset = pyclipper.PyclipperOffset()
    offset.AddPath(path, pyclipper.JT_ROUND, pyclipper.ET_CLOSEDPOLYGON)
    expanded = offset.Execute(distance)
    if not expanded:
        return None
    return np.asarray(max(expanded, key=lambda p: abs(cv2.contourArea(np.asarray(p, dtype=np.float32)))), dtype=np.float32)


def db_postprocess(
    pred: np.ndarray,
    shape: tuple[int, int, int, int],
    *,
    thresh: float = 0.2,
    box_thresh: float = 0.45,
    unclip_ratio: float = 1.4,
    max_candidates: int = 3000,
) -> list[dict[str, Any]]:
    """Decode DB probability map into quadrilateral boxes in source pixels."""
    oh, ow, rh, rw = shape
    bitmap = (pred[0, 0] > thresh).astype(np.uint8)
    contours, _ = cv2.findContours(bitmap, cv2.RETR_LIST, cv2.CHAIN_APPROX_SIMPLE)
    contours = contours[:max_candidates]
    out: list[dict[str, Any]] = []
    ph, pw = pred.shape[-2:]
    sx, sy = float(rw) / pw, float(rh) / ph
    for contour in contours:
        if len(contour) < 3:
            continue
        rect = cv2.minAreaRect(contour)
        points = cv2.boxPoints(rect).astype(np.float32)
        points[:, 0] *= sx
        points[:, 1] *= sy
        points[:, 0] = np.clip(points[:, 0], 0, rw - 1)
        points[:, 1] = np.clip(points[:, 1], 0, rh - 1)
        score_mask = np.zeros((rh, rw), dtype=np.uint8)
        cv2.fillPoly(score_mask, [np.round(points).astype(np.int32)], 1)
        score = float(cv2.mean(pred[0, 0], score_mask)[0])
        if score < box_thresh:
            continue
        expanded = _unclip(points, unclip_ratio)
        if expanded is None or len(expanded) < 3:
            continue
        rect = cv2.minAreaRect(expanded.astype(np.float32))
        box = cv2.boxPoints(rect).astype(np.float32)
        box[:, 0] = np.clip(box[:, 0] * ow / rw, 0, ow - 1)
        box[:, 1] = np.clip(box[:, 1] * oh / rh, 0, oh - 1)
        box = _order_clockwise(box)
        width = float(np.linalg.norm(box[0] - box[1]))
        height = float(np.linalg.norm(box[1] - box[2]))
        if min(width, height) < 3:
            continue
        out.append({
            "box": np.round(box, 3).tolist(),
            "score": score,
            "area": float(abs(cv2.contourArea(box.astype(np.float32)))),
            "width": width,
            "height": height,
        })
    out.sort(key=lambda b: b["score"], reverse=True)
    return out


def _box_points(value: Any) -> np.ndarray:
    """Accept Studio's xyxy boxes and polygon-style boxes."""
    points = np.asarray(value, dtype=np.float32)
    if points.ndim == 1 and points.size == 4:
        x0, y0, x1, y1 = points.tolist()
        points = np.asarray([[x0, y0], [x1, y0], [x1, y1], [x0, y1]], dtype=np.float32)
    elif points.ndim == 1 and points.size % 2 == 0:
        points = points.reshape(-1, 2)
    if points.ndim != 2 or points.shape[1] != 2:
        raise ValueError(f"unsupported box shape: {points.shape}")
    return points


def iou(a: Any, b: Any) -> float:
    pa = _box_points(a)
    pb = _box_points(b)
    xa0, ya0 = pa.min(axis=0); xa1, ya1 = pa.max(axis=0)
    xb0, yb0 = pb.min(axis=0); xb1, yb1 = pb.max(axis=0)
    inter = max(0.0, min(xa1, xb1) - max(xa0, xb0)) * max(0.0, min(ya1, yb1) - max(ya0, yb0))
    ua = max(0.0, xa1 - xa0) * max(0.0, ya1 - ya0)
    ub = max(0.0, xb1 - xb0) * max(0.0, yb1 - yb0)
    return float(inter / (ua + ub - inter)) if ua + ub - inter > 0 else 0.0


def proxy_metrics(predicted: list[dict[str, Any]], regions: list[dict[str, Any]] | None) -> dict[str, Any]:
    """Compare to Studio OCR rectangles, explicitly a quality proxy.

    Studio OCR boxes are not an independent detector ground truth.  These
    fields are useful for resolution sensitivity only and are labelled proxy
    throughout the emitted JSON/report.
    """
    if not regions:
        return {"annotation_region_count": None, "proxy_iou50_matches": None, "proxy_recall50": None, "proxy_precision50": None}
    refs = [r.get("ocr_box") or r.get("box") for r in regions if (r.get("ocr_box") or r.get("box"))]
    matches = sum(1 for ref in refs if any(iou(p["box"], ref) >= 0.5 for p in predicted))
    pmatches = sum(1 for p in predicted if any(iou(p["box"], ref) >= 0.5 for ref in refs))
    return {
        "annotation_region_count": len(refs),
        "proxy_iou50_matches": matches,
        "proxy_recall50": matches / len(refs) if refs else None,
        "proxy_precision50": pmatches / len(predicted) if predicted else None,
    }


def iter_manifest_pages(manifest: dict[str, Any]) -> Iterable[dict[str, Any]]:
    for chapter in manifest.get("chapters", []):
        ann_path = Path(chapter.get("annotation_path", ""))
        annotations: dict[str, Any] = {}
        if ann_path.is_file():
            try:
                annotations = json.loads(ann_path.read_text(encoding="utf-8"))
            except (OSError, json.JSONDecodeError):
                annotations = {}
        for page in chapter.get("pages", []):
            path = Path(page["source_path"])
            data = dict(page)
            data.update({"chapter": chapter.get("chapter"), "source_id": chapter.get("source_id"), "path": path})
            ann = annotations.get(page.get("filename"), {}) if isinstance(annotations, dict) else {}
            data["regions"] = ann.get("regions") or page.get("regions") or []
            yield data


def make_session(model: Path, threads: int, profile: str) -> ort.InferenceSession:
    opts = ort.SessionOptions()
    opts.intra_op_num_threads = threads
    opts.inter_op_num_threads = 1
    opts.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    providers = [profile] if profile != "CPUExecutionProvider" else ["CPUExecutionProvider"]
    return ort.InferenceSession(str(model), sess_options=opts, providers=providers)


def run_one(session: ort.InferenceSession, path: Path, limit: int, regions: list[dict[str, Any]]) -> dict[str, Any]:
    process = psutil.Process(os.getpid())
    rss_before = process.memory_info().rss
    t0 = time.perf_counter_ns()
    image = cv2.imread(str(path), cv2.IMREAD_COLOR)
    if image is None:
        raise RuntimeError(f"failed to decode image: {path}")
    t1 = time.perf_counter_ns()
    x, shape = preprocess(image, limit)
    t2 = time.perf_counter_ns()
    output = session.run(None, {session.get_inputs()[0].name: x})[0]
    t3 = time.perf_counter_ns()
    boxes = db_postprocess(output, shape)
    t4 = time.perf_counter_ns()
    rss_after = process.memory_info().rss
    proxy = proxy_metrics(boxes, regions)
    return {
        "image_decode_ms": (t1 - t0) / 1e6,
        "preprocess_ms": (t2 - t1) / 1e6,
        "inference_ms": (t3 - t2) / 1e6,
        "postprocess_ms": (t4 - t3) / 1e6,
        "total_ms": (t4 - t0) / 1e6,
        "rss_before_mb": rss_before / 1048576,
        "rss_after_mb": rss_after / 1048576,
        "input_height": shape[2],
        "input_width": shape[3],
        "original_height": shape[0],
        "original_width": shape[1],
        "detection_count": len(boxes),
        "detected_area_mean": statistics.mean([b["area"] for b in boxes]) if boxes else 0.0,
        "detected_area_median": statistics.median([b["area"] for b in boxes]) if boxes else 0.0,
        "detected_width_median": statistics.median([b["width"] for b in boxes]) if boxes else 0.0,
        "detected_height_median": statistics.median([b["height"] for b in boxes]) if boxes else 0.0,
        "boxes": boxes,
        **proxy,
    }


def aggregate(records: list[dict[str, Any]], mode: str, limit: int) -> dict[str, Any]:
    vals = [r["total_ms"] for r in records if r.get("status") == "ok"]
    infer = [r["inference_ms"] for r in records if r.get("status") == "ok"]
    rss = [r["rss_after_mb"] for r in records if r.get("status") == "ok"]
    counts = [r["detection_count"] for r in records if r.get("status") == "ok"]
    return {
        "mode": mode, "limit": limit, "pages": len(records), "ok_pages": len(vals),
        "failed_pages": len(records) - len(vals), "total_ms_median": percentile(vals, 50),
        "total_ms_p90": percentile(vals, 90), "total_ms_p95": percentile(vals, 95),
        "inference_ms_median": percentile(infer, 50), "inference_ms_p90": percentile(infer, 90),
        "rss_after_mb_max": max(rss) if rss else None, "detection_count_mean": statistics.mean(counts) if counts else None,
        "pages_per_second_median": 1000.0 / percentile(vals, 50) if vals else None,
    }


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--manifest", type=Path, default=DEFAULT_MANIFEST)
    ap.add_argument("--model", type=Path, default=DEFAULT_MODEL)
    ap.add_argument("--limits", default=",".join(map(str, LIMITS)))
    ap.add_argument("--warm-repeats", type=int, default=1)
    ap.add_argument("--cold-pages", type=int, default=3)
    ap.add_argument("--threads", type=int, default=0, help="ORT intra-op threads; 0 uses runtime default")
    ap.add_argument("--provider", default="CPUExecutionProvider")
    ap.add_argument("--output-dir", type=Path, default=Path(__file__).parent / "results")
    args = ap.parse_args()
    limits = [int(x) for x in args.limits.split(",") if x.strip()]
    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    pages = list(iter_manifest_pages(manifest))
    root = Path(__file__).resolve().parents[1]
    output_dir = args.output_dir
    output_dir.mkdir(parents=True, exist_ok=True)
    threads = args.threads or max(1, os.cpu_count() or 1)
    common = {
        "schema_version": 1, "created_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "git_revision": git_revision(root), "python": sys.version, "platform": platform.platform(),
        "cpu": platform.processor(), "onnxruntime": ort.__version__, "provider": args.provider,
        "threads": threads, "manifest": str(args.manifest.resolve()), "manifest_sha256": sha256(args.manifest),
        "model": str(args.model.resolve()), "model_sha256": sha256(args.model), "limits": limits,
        "requested_pages": len(pages),
        "note": "Studio OCR region comparisons are resolution quality proxies, not detector accuracy labels.",
    }
    source_checks: list[dict[str, Any]] = []
    for page in pages:
        file_exists = page["path"].is_file()
        decoded = False
        decode_error = None
        if file_exists:
            try:
                decoded = cv2.imread(str(page["path"]), cv2.IMREAD_COLOR) is not None
                if not decoded:
                    decode_error = "cv2.imread returned None"
            except Exception as exc:
                decode_error = repr(exc)
        else:
            decode_error = "source file not found"
        source_checks.append({"chapter": page.get("chapter"), "filename": page.get("filename"), "source_path": str(page["path"]),
                              "file_exists": file_exists, "decoded": decoded, "error": decode_error})
    common["readable_pages"] = sum(1 for p in source_checks if p["decoded"])
    common["file_missing_pages"] = sum(1 for p in source_checks if not p["file_exists"])
    common["decode_failed_pages"] = sum(1 for p in source_checks if p["file_exists"] and not p["decoded"])
    common["unreadable_pages"] = common["requested_pages"] - common["readable_pages"]
    common["source_checks"] = source_checks
    raw: list[dict[str, Any]] = []
    summaries: list[dict[str, Any]] = []
    fields = ["mode", "limit", "repeat", "chapter", "source_id", "filename", "source_path", "status", "error",
              "image_decode_ms", "preprocess_ms", "inference_ms", "postprocess_ms", "total_ms", "rss_before_mb", "rss_after_mb",
              "input_height", "input_width", "original_height", "original_width", "detection_count", "detected_area_mean", "detected_area_median",
              "detected_width_median", "detected_height_median", "annotation_region_count", "proxy_iou50_matches", "proxy_recall50", "proxy_precision50", "boxes"]
    rows: list[dict[str, Any]] = []
    for limit in limits:
        available = [p for p, check in zip(pages, source_checks) if check["decoded"]]
        for mode in ("warm", "cold"):
            if mode == "cold":
                selected = available[: max(0, args.cold_pages)]
                repeats = 1
            else:
                selected = available
                repeats = max(1, args.warm_repeats)
            if not selected:
                continue
            session = None
            if mode == "warm":
                session = make_session(args.model, threads, args.provider)
                # Load kernels and allocator before timing pages.
                warm_x, _ = preprocess(cv2.imread(str(selected[0]["path"]), cv2.IMREAD_COLOR), limit)
                session.run(None, {session.get_inputs()[0].name: warm_x})
            mode_records: list[dict[str, Any]] = []
            for repeat in range(repeats):
                for page in selected:
                    rec: dict[str, Any] = {"mode": mode, "limit": limit, "repeat": repeat, "chapter": page.get("chapter"),
                                           "source_id": page.get("source_id"), "filename": page.get("filename"), "source_path": str(page["path"]),
                                           "status": "ok", "error": None}
                    try:
                        if mode == "cold":
                            t0 = time.perf_counter_ns(); cold_session = make_session(args.model, threads, args.provider)
                            out = run_one(cold_session, page["path"], limit, page.get("regions", [])); out["total_ms"] += (time.perf_counter_ns() - t0) / 1e6
                            rec.update(out); del cold_session
                        else:
                            rec.update(run_one(session, page["path"], limit, page.get("regions", [])))
                    except Exception as exc:  # preserve a raw failed-page record
                        rec.update({"status": "error", "error": repr(exc)})
                    raw.append(rec); mode_records.append(rec)
                    row = {k: rec.get(k) for k in fields}; row["boxes"] = json.dumps(row.get("boxes"), separators=(",", ":")) if row.get("boxes") is not None else None
                    rows.append(row)
            summaries.append(aggregate(mode_records, mode, limit))
            if session is not None:
                del session
    payload = {**common, "summaries": summaries, "records": raw}
    stamp = time.strftime("%Y%m%dT%H%M%SZ", time.gmtime())
    json_path = output_dir / f"det_benchmark_{stamp}.json"
    csv_path = output_dir / f"det_benchmark_{stamp}.csv"
    latest_json = output_dir / "det_benchmark.json"; latest_csv = output_dir / "det_benchmark.csv"
    json_path.write_text(json.dumps(payload, indent=2, ensure_ascii=False), encoding="utf-8")
    latest_json.write_text(json.dumps(payload, indent=2, ensure_ascii=False), encoding="utf-8")
    with csv_path.open("w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=fields); writer.writeheader(); writer.writerows(rows)
    with latest_csv.open("w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=fields); writer.writeheader(); writer.writerows(rows)
    print(json.dumps({"json": str(latest_json), "csv": str(latest_csv), "requested_pages": common["requested_pages"], "readable_pages": common["readable_pages"], "unreadable_pages": common["unreadable_pages"], "summaries": summaries}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
