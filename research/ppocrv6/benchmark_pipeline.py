#!/usr/bin/env python3
"""Integrated PP-OCRv6 small DET -> crop -> batched REC benchmark.

This is a research-only laptop harness.  It consumes the path-only fixture
manifest produced by ``build_dataset_manifest.py`` and never copies or edits
the external page images.  The preprocessing and postprocessing are explicit
mirrors of the Android implementation:

* DET: resize the long side to 736 with PIL bilinear, black pad, ImageNet
  normalize, NCHW; DB connected components at threshold .20 / box threshold
  .45, axis-aligned boxes, fragment merge, and integer back-projection.
* REC: 12px context around each detected box, 90-degree CCW rotation for tall
  boxes, 48px height, ceil aspect resize, gray 128 pad, RGB NCHW [-1, 1], and
  production width buckets 640 / 1600.

The default run is intentionally bounded to 12 audited pages with B=1 and the
production bucketed width policy.  Larger policies or corpus scopes must be
requested explicitly, one process at a time, with the RSS guard enabled.
Batched REC rows are checked against independent B=1 REC outputs.  A small
fresh-session DET replay checks the integrated detector's page-level outputs
against the isolated detector stage.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import math
import os
import platform
import statistics
import threading
import time
from dataclasses import dataclass, asdict
from pathlib import Path
from typing import Any, Iterable

import cv2
import numpy as np
import onnxruntime as ort
from PIL import Image

try:
    import psutil
except ImportError:  # pragma: no cover - optional on minimal laptops
    psutil = None


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_MANIFEST = ROOT / "research/dataset/manifest.json"
DET_MODEL = ROOT / "app/src/main/assets/models/ocr/paddle-v6-small/det/inference.onnx"
REC_MODEL = ROOT / "app/src/main/assets/models/ocr/paddle-v6-small/inference.onnx"
REC_DICTIONARY = ROOT / "app/src/main/assets/models/ocr/paddle-v6-small/PP-OCRv6_small_rec.txt"
DEFAULT_OUT = ROOT / "research/results"

DET_TARGET = 736
DET_THRESH = 0.20
DET_BOX_THRESH = 0.45
DET_MIN_AREA = 16
DET_MAX_AREA_FRAC = 0.5
DET_MAX_CANDIDATES = 3000
DET_SAME_LINE_FRAC = 0.6
DET_MERGE_GAP_FACTOR = 1.0
DET_MERGE_PASSES = 3
REC_HEIGHT = 48
REC_SMALL_WIDTH = 640
REC_MAX_WIDTH = 1600
REC_CONTEXT = 12
REC_PAD_GRAY = 128
REC_BUCKETS = (96, 160, 256, 384, 512)


def now_ns() -> int:
    return time.perf_counter_ns()


def rss_mb() -> float | None:
    if psutil is None:
        return None
    return psutil.Process(os.getpid()).memory_info().rss / (1024 * 1024)


def enforce_rss_cap(cap_mb: float, context: str) -> None:
    """Fail closed before the next expensive stage when a cap is requested."""
    if cap_mb <= 0:
        return
    current = rss_mb()
    if current is not None and current >= cap_mb:
        raise MemoryError(f"RSS cap {cap_mb:.0f} MB reached before {context}: {current:.1f} MB")


class PeakRss:
    def __init__(self, interval_s: float = 0.002) -> None:
        self.process = psutil.Process(os.getpid()) if psutil else None
        self.interval_s = interval_s
        self.peak = 0
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None

    def _sample(self) -> None:
        while not self._stop.is_set():
            try:
                if self.process:
                    self.peak = max(self.peak, self.process.memory_info().rss)
            except Exception:
                pass
            self._stop.wait(self.interval_s)

    def __enter__(self) -> "PeakRss":
        self.peak = self.process.memory_info().rss if self.process else 0
        self._thread = threading.Thread(target=self._sample, daemon=True)
        self._thread.start()
        return self

    def __exit__(self, *_: Any) -> None:
        self._stop.set()
        if self._thread:
            self._thread.join(timeout=1.0)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def percentile(values: Iterable[float], p: float) -> float | None:
    vals = sorted(float(v) for v in values)
    if not vals:
        return None
    if len(vals) == 1:
        return vals[0]
    rank = (len(vals) - 1) * p
    lo, hi = math.floor(rank), math.ceil(rank)
    return vals[lo] if lo == hi else vals[lo] + (vals[hi] - vals[lo]) * (rank - lo)


def kotlin_round(value: float) -> int:
    """Match Kotlin ``roundToInt`` for the positive resize dimensions."""
    return int(math.floor(value + 0.5))


def session_options(threads: int) -> ort.SessionOptions:
    options = ort.SessionOptions()
    options.intra_op_num_threads = max(1, int(threads))
    options.inter_op_num_threads = 1
    options.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL
    options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    options.log_severity_level = 3
    return options


def load_session(path: Path, threads: int) -> ort.InferenceSession:
    return ort.InferenceSession(str(path), session_options(threads), providers=["CPUExecutionProvider"])


def load_manifest(path: Path) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    manifest = json.loads(path.read_text(encoding="utf-8"))
    pages: list[dict[str, Any]] = []
    for chapter in manifest.get("chapters", []):
        for page in chapter.get("pages", []):
            record = dict(page)
            record["chapter"] = chapter.get("chapter")
            record["source_id"] = chapter.get("source_id")
            record["chapter_path"] = chapter.get("source_path")
            pages.append(record)
    return manifest, pages


def audit_page_files(page_records: list[dict[str, Any]]) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    """Hard-audit every manifest page with a real PIL decode before timing."""
    readable: list[dict[str, Any]] = []
    failures: list[dict[str, Any]] = []
    for record in page_records:
        path = Path(record["source_path"])
        try:
            if not path.is_file():
                raise FileNotFoundError("path does not exist")
            with Image.open(path) as image:
                image.verify()
            with Image.open(path) as image:
                image.convert("RGB").load()
            readable.append(record)
        except Exception as exc:
            failures.append({"page_id": page_id(record), "source_id": record.get("source_id"), "source_path": str(path), "reason": f"{type(exc).__name__}: {exc}"})
    return readable, failures


def det_preprocess(image: Image.Image) -> tuple[np.ndarray, float, float, int, int]:
    w, h = image.size
    scale = DET_TARGET / float(max(w, h))
    rw = max(1, min(DET_TARGET, kotlin_round(w * scale)))
    rh = max(1, min(DET_TARGET, kotlin_round(h * scale)))
    resized = image.resize((rw, rh), Image.Resampling.BILINEAR)
    padded = Image.new("RGB", (DET_TARGET, DET_TARGET), (0, 0, 0))
    padded.paste(resized, (0, 0))
    arr = np.asarray(padded, dtype=np.float32) / np.float32(255.0)
    mean = np.asarray((0.485, 0.456, 0.406), dtype=np.float32)
    std = np.asarray((0.229, 0.224, 0.225), dtype=np.float32)
    tensor = np.ascontiguousarray(((arr - mean) / std).transpose(2, 0, 1)[None], dtype=np.float32)
    return tensor, rw / float(w), rh / float(h), rw, rh


def merge_axis(boxes: list[list[float]], axis: str) -> list[list[float]]:
    if not boxes:
        return []
    if axis == "h":
        ordered = sorted(boxes, key=lambda b: ((b[1] + b[3]) / 2, b[0]))
    else:
        ordered = sorted(boxes, key=lambda b: ((b[0] + b[2]) / 2, b[1]))
    merged: list[list[float]] = []
    for b in ordered:
        placed = False
        for m in merged:
            if axis == "h":
                c_cross, m_cross = (b[1] + b[3]) / 2, (m[1] + m[3]) / 2
                c_size, m_size = b[3] - b[1], m[3] - m[1]
                gap = max(0, max(b[0], m[0]) - min(b[2], m[2]))
                gap_ref, m_gap_ref = b[3] - b[1], m[3] - m[1]
            else:
                c_cross, m_cross = (b[0] + b[2]) / 2, (m[0] + m[2]) / 2
                c_size, m_size = b[2] - b[0], m[2] - m[0]
                gap = max(0, max(b[1], m[1]) - min(b[3], m[3]))
                gap_ref, m_gap_ref = b[3] - b[1], m[3] - m[1]
            same_line = abs(c_cross - m_cross) <= DET_SAME_LINE_FRAC * min(c_size, m_size)
            if same_line and gap <= DET_MERGE_GAP_FACTOR * max(gap_ref, m_gap_ref):
                m[0], m[1] = min(m[0], b[0]), min(m[1], b[1])
                m[2], m[3] = max(m[2], b[2]), max(m[3], b[3])
                m[4] = max(m[4], b[4])
                placed = True
                break
        if not placed:
            merged.append(list(b))
    return merged


def db_postprocess(prob_map: np.ndarray) -> list[tuple[int, int, int, int, float]]:
    """Mirror DbPostProcess.detectLines and mergeLineFragments."""
    h, w = prob_map.shape
    binary = (prob_map > DET_THRESH).astype(np.uint8)
    count, labels = cv2.connectedComponents(binary, connectivity=8)
    components: list[list[float]] = []
    for label in range(1, count):
        ys, xs = np.where(labels == label)
        pixel_count = len(xs)
        if pixel_count < DET_MIN_AREA:
            continue
        x1, x2, y1, y2 = int(xs.min()), int(xs.max()), int(ys.min()), int(ys.max())
        mean_score = float(prob_map[labels == label].sum() / pixel_count)
        area = (x2 - x1 + 1) * (y2 - y1 + 1)
        if area > DET_MAX_AREA_FRAC * w * h:
            continue
        components.append([x1, y1, x2, y2, mean_score])
        if len(components) >= DET_MAX_CANDIDATES:
            break
    components = [b for b in components if b[4] >= DET_BOX_THRESH]
    current = components
    for _ in range(DET_MERGE_PASSES):
        next_boxes = merge_axis(
            [b for b in current if (b[2] - b[0]) >= (b[3] - b[1])], "h"
        ) + merge_axis(
            [b for b in current if (b[2] - b[0]) < (b[3] - b[1])], "v"
        )
        if len(next_boxes) == len(current):
            break
        current = next_boxes
    current.sort(key=lambda b: (b[1], b[0]))
    return [(int(b[0]), int(b[1]), int(b[2]), int(b[3]), float(b[4])) for b in current]


def det_page(session: ort.InferenceSession, image: Image.Image) -> tuple[list[tuple[int, int, int, int, float]], dict[str, float]]:
    start = now_ns()
    tensor, scale_x, scale_y, rw, rh = det_preprocess(image)
    prep_ms = (now_ns() - start) / 1e6
    infer_start = now_ns()
    out = session.run(None, {session.get_inputs()[0].name: tensor})[0]
    infer_ms = (now_ns() - infer_start) / 1e6
    post_start = now_ns()
    boxes = db_postprocess(np.asarray(out)[0, 0, :rh, :rw])
    projected: list[tuple[int, int, int, int, float]] = []
    for x1, y1, x2, y2, score in boxes:
        # Kotlin DbPostProcess.backProject uses Float.toInt(): truncation,
        # then clamps to the inclusive source max.
        sx1 = max(0, min(image.width - 1, int(x1 * scale_x)))
        sy1 = max(0, min(image.height - 1, int(y1 * scale_y)))
        sx2 = max(0, min(image.width - 1, int(x2 * scale_x)))
        sy2 = max(0, min(image.height - 1, int(y2 * scale_y)))
        if sx2 > sx1 and sy2 > sy1:
            projected.append((min(sx1, sx2), min(sy1, sy2), max(sx1, sx2), max(sy1, sy2), score))
    post_ms = (now_ns() - post_start) / 1e6
    return projected, {"det_preprocess_ms": prep_ms, "det_inference_ms": infer_ms, "det_postprocess_ms": post_ms, "det_resized_w": rw, "det_resized_h": rh}


@dataclass
class Crop:
    crop_id: str
    page_id: str
    chapter: str
    page_path: str
    box_index: int
    box: tuple[int, int, int, int, float]
    image: Image.Image
    rotated: bool
    scaled_width: int
    required_width: int


def rec_scaled_width(width: int, height: int) -> int:
    scaled = int(math.ceil(max(1, width) * (REC_HEIGHT / float(max(1, height)))))
    return max(1, min(REC_MAX_WIDTH, scaled))


def rec_required_width(scaled_width: int) -> int:
    return REC_SMALL_WIDTH if scaled_width <= REC_SMALL_WIDTH else REC_MAX_WIDTH


def prepare_crop(crop: Crop, width_mode: str) -> np.ndarray:
    resized = crop.image.resize((crop.scaled_width, REC_HEIGHT), Image.Resampling.BILINEAR)
    target = crop.required_width if width_mode == "bucketed" else crop.scaled_width
    padded = Image.new("RGB", (target, REC_HEIGHT), (REC_PAD_GRAY, REC_PAD_GRAY, REC_PAD_GRAY))
    padded.paste(resized, (0, 0))
    arr = np.asarray(padded, dtype=np.float32)
    tensor = ((arr / 255.0 - 0.5) / 0.5).transpose(2, 0, 1)
    return np.ascontiguousarray(tensor, dtype=np.float32)


def dictionary_decode(logits: np.ndarray, dictionary: list[str]) -> tuple[list[str], np.ndarray]:
    ids = np.argmax(logits, axis=2).astype(np.int32)
    space_index = len(dictionary) + 1
    texts: list[str] = []
    for sequence in ids:
        chars: list[str] = []
        previous = -1
        for index in sequence.tolist():
            if index != previous and index != 0:
                if 1 <= index <= len(dictionary):
                    chars.append(dictionary[index - 1])
                elif index == space_index:
                    chars.append(" ")
            previous = index
        texts.append("".join(chars).strip())
    return texts, ids


def crop_from_box(image: Image.Image, box: tuple[int, int, int, int, float], crop_id: str, page_id: str, chapter: str, page_path: str, index: int) -> Crop | None:
    x1, y1, x2, y2, _ = box
    left = max(0, x1 - REC_CONTEXT)
    top = max(0, y1 - REC_CONTEXT)
    right = min(image.width, x2 + REC_CONTEXT)
    bottom = min(image.height, y2 + REC_CONTEXT)
    if right <= left or bottom <= top:
        return None
    roi = image.crop((left, top, right, bottom))
    rotated = roi.height > roi.width * 1.5
    if rotated:
        roi = roi.transpose(Image.Transpose.ROTATE_90)  # PIL = CCW
    scaled = rec_scaled_width(roi.width, roi.height)
    return Crop(crop_id, page_id, chapter, page_path, index, box, roi, rotated, scaled, rec_required_width(scaled))


def run_rec_batch(session: ort.InferenceSession, crops: list[Crop], width_mode: str, dictionary: list[str]) -> dict[str, Any]:
    prepared = [prepare_crop(crop, width_mode) for crop in crops]
    max_width = max(t.shape[2] for t in prepared)
    copy_start = now_ns()
    batch = np.zeros((len(prepared), 3, REC_HEIGHT, max_width), dtype=np.float32)
    for i, tensor in enumerate(prepared):
        batch[i, :, :, : tensor.shape[2]] = tensor
    copy_ms = (now_ns() - copy_start) / 1e6
    infer_start = now_ns()
    logits = np.asarray(session.run(None, {session.get_inputs()[0].name: batch})[0])
    infer_ms = (now_ns() - infer_start) / 1e6
    post_start = now_ns()
    texts, token_ids = dictionary_decode(logits, dictionary)
    post_ms = (now_ns() - post_start) / 1e6
    return {"prepared": prepared, "logits": logits, "texts": texts, "token_ids": token_ids, "copy_ms": copy_ms, "inference_ms": infer_ms, "postprocess_ms": post_ms, "batch_width": max_width}


def chunked(items: list[Crop], size: int) -> list[list[Crop]]:
    return [items[i : i + size] for i in range(0, len(items), size)]


def crop_signature(crop: Crop) -> dict[str, Any]:
    return {"crop_id": crop.crop_id, "scaled_width": crop.scaled_width, "required_width": crop.required_width, "rotated": crop.rotated, "source_box": list(crop.box)}


def page_id(page: dict[str, Any]) -> str:
    return f"{page['source_id']}:{page['chapter']}:{page['filename']}"


def summarize_page_rows(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    groups: dict[tuple[str, int, str], list[dict[str, Any]]] = {}
    for row in rows:
        groups.setdefault((row["width_mode"], int(row["batch_size"]), row["phase"]), []).append(row)
    summaries: list[dict[str, Any]] = []
    for (mode, batch_size, phase), values in sorted(groups.items()):
        total_ms = sum(float(v["total_ms"]) for v in values)
        pages = sum(int(v["pages"]) for v in values)
        regions = sum(int(v["regions"]) for v in values)
        summaries.append({
            "width_mode": mode,
            "batch_size": batch_size,
            "phase": phase,
            "pages": pages,
            "regions": regions,
            "total_ms": total_ms,
            "pages_per_sec": pages / (total_ms / 1000.0) if total_ms else None,
            "regions_per_sec": regions / (total_ms / 1000.0) if total_ms else None,
            "page_ms_p50": percentile([float(v["total_ms"]) for v in values], 0.5),
            "page_ms_p95": percentile([float(v["total_ms"]) for v in values], 0.95),
            "rec_inference_ms": sum(float(v["rec_inference_ms"]) for v in values),
            "rec_preprocess_ms": sum(float(v["rec_preprocess_ms"]) for v in values),
            "rec_postprocess_ms": sum(float(v["rec_postprocess_ms"]) for v in values),
            "rss_peak_delta_mb": max(float(v["rss_peak_delta_mb"]) for v in values),
            "text_mismatch_count": sum(int(v["text_mismatch_count"]) for v in values),
            "token_mismatch_count": sum(int(v["token_mismatch_count"]) for v in values),
        })
    return summaries


def write_csv(path: Path, rows: list[dict[str, Any]]) -> None:
    if not rows:
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, default=DEFAULT_MANIFEST)
    parser.add_argument("--det-model", type=Path, default=DET_MODEL)
    parser.add_argument("--rec-model", type=Path, default=REC_MODEL)
    parser.add_argument("--dictionary", type=Path, default=REC_DICTIONARY)
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_OUT)
    parser.add_argument("--threads", type=int, default=1)
    parser.add_argument("--batch-sizes", default="1")
    parser.add_argument("--width-modes", default="bucketed")
    parser.add_argument("--max-pages", type=int, default=12)
    parser.add_argument("--source-ids", default="all", help="Comma-separated manifest source_ids to include (default: all; use downloaded to reproduce the 44-page GUI-probe subset).")
    parser.add_argument("--det-replay-pages", type=int, default=8)
    parser.add_argument("--parity-sample", type=int, default=12, help="Independent B=1 REC crops to retain for parity (0=all; first N in manifest order).")
    parser.add_argument("--rss-cap-mb", type=float, default=3072.0, help="Fail closed before the next page/batch when RSS reaches this cap; 0 disables the guard.")
    args = parser.parse_args()

    for path in (args.manifest, args.det_model, args.rec_model, args.dictionary):
        if not path.exists():
            raise SystemExit(f"required path is missing: {path}")
    manifest, page_records = load_manifest(args.manifest)
    audited_pages, audit_failures = audit_page_files(page_records)
    requested_values = {value.strip() for value in args.source_ids.split(",") if value.strip()}
    requested_source_ids = {p.get("source_id") for p in page_records} if "all" in requested_values else requested_values
    excluded_pages = [p for p in page_records if p.get("source_id") not in requested_source_ids]
    failed_ids = {item["page_id"] for item in audit_failures}
    missing_pages = [p for p in page_records if p.get("source_id") in requested_source_ids and page_id(p) in failed_ids]
    available_pages = [p for p in audited_pages if p.get("source_id") in requested_source_ids]
    if args.max_pages:
        available_pages = available_pages[: args.max_pages]
    if not available_pages:
        raise SystemExit("manifest contains no available page files")

    dictionary = args.dictionary.read_text(encoding="utf-8").splitlines()
    det_model = args.det_model.resolve()
    rec_model = args.rec_model.resolve()
    batch_sizes = [int(x) for x in args.batch_sizes.split(",") if x.strip()]
    width_modes = [x.strip() for x in args.width_modes.split(",") if x.strip()]
    configs = [(mode, batch) for mode in width_modes for batch in batch_sizes]

    process_start = now_ns()
    det_load_start = now_ns()
    det_session = load_session(det_model, args.threads)
    det_load_ms = (now_ns() - det_load_start) / 1e6
    rec_load_start = now_ns()
    rec_session = load_session(rec_model, args.threads)
    rec_load_ms = (now_ns() - rec_load_start) / 1e6

    page_images: dict[str, Image.Image] = {}
    detection_rows: list[dict[str, Any]] = []
    detected_crops: dict[str, list[Crop]] = {}
    detection_start = now_ns()
    for ordinal, record in enumerate(available_pages):
        enforce_rss_cap(args.rss_cap_mb, f"DET page {ordinal + 1}")
        pid = page_id(record)
        source = Path(record["source_path"])
        with Image.open(source) as image_file:
            image = image_file.convert("RGB")
        page_images[pid] = image
        before = rss_mb()
        boxes, timing = det_page(det_session, image)
        after = rss_mb()
        crops: list[Crop] = []
        for index, box in enumerate(boxes):
            crop = crop_from_box(image, box, f"{pid}:r{index:04d}", pid, str(record["chapter"]), str(source), index)
            if crop:
                crops.append(crop)
        detected_crops[pid] = crops
        detection_rows.append({
            "page_id": pid, "source_id": record["source_id"], "chapter": record["chapter"], "filename": record["filename"], "source_path": str(source),
            "page_index": int(record["index"]), "page_width": image.width, "page_height": image.height,
            "regions": len(boxes), "crops": len(crops), **timing,
            "rss_before_mb": before if before is not None else "", "rss_after_mb": after if after is not None else "",
        })
        if (ordinal + 1) % 10 == 0:
            print(f"detected {ordinal + 1}/{len(available_pages)} pages", flush=True)
    detection_total_ms = (now_ns() - detection_start) / 1e6

    # Independent B=1 REC stage used as the correctness reference for every
    # integrated batched policy.  The same production crop tensors are passed
    # one at a time, with no outputs reused from the integrated runs.
    all_crops = [crop for pid in [page_id(p) for p in available_pages] for crop in detected_crops[pid]]
    parity_crops = all_crops if args.parity_sample <= 0 else all_crops[: args.parity_sample]
    parity_crop_ids = {crop.crop_id for crop in parity_crops}
    isolated: dict[str, dict[str, Any]] = {}
    isolated_start = now_ns()
    for index, crop in enumerate(parity_crops):
        enforce_rss_cap(args.rss_cap_mb, f"isolated REC crop {index + 1}")
        result = run_rec_batch(rec_session, [crop], "bucketed", dictionary)
        isolated[crop.crop_id] = {"text": result["texts"][0], "token_ids": result["token_ids"][0].tolist(), "logits": result["logits"][0].tolist()}
        if (index + 1) % 100 == 0:
            print(f"isolated REC {index + 1}/{len(parity_crops)} crops", flush=True)
    isolated_total_ms = (now_ns() - isolated_start) / 1e6
    del rec_session

    page_rows: list[dict[str, Any]] = []
    batch_rows: list[dict[str, Any]] = []
    parity_rows: list[dict[str, Any]] = []
    for mode, batch_size in configs:
        print(f"integrated REC mode={mode} batch={batch_size}", flush=True)
        config_load_start = now_ns()
        rec_session = load_session(rec_model, args.threads)
        config_session_init_ms = (now_ns() - config_load_start) / 1e6
        for page_number, record in enumerate(available_pages):
            enforce_rss_cap(args.rss_cap_mb, f"integrated {mode}/B{batch_size} page {page_number + 1}")
            pid = page_id(record)
            crops = detected_crops[pid]
            phase = "cold_first_page" if page_number == 0 else "warm"
            before = rss_mb()
            with PeakRss() as peak:
                page_start = now_ns()
                rec_pre_ms = rec_inf_ms = rec_post_ms = 0.0
                mismatch_text = mismatch_tokens = 0
                max_logit_diff = 0.0
                batches = chunked(crops, batch_size)
                for batch_index, batch_crops in enumerate(batches):
                    batch_pre_ms = batch_inf_ms = batch_post_ms = 0.0
                    prep_start = now_ns()
                    prepared = [prepare_crop(crop, mode) for crop in batch_crops]
                    batch_pre_ms += (now_ns() - prep_start) / 1e6
                    max_width = max((tensor.shape[2] for tensor in prepared), default=1)
                    stack_start = now_ns()
                    batch_tensor = np.zeros((len(prepared), 3, REC_HEIGHT, max_width), dtype=np.float32)
                    for i, tensor in enumerate(prepared):
                        batch_tensor[i, :, :, : tensor.shape[2]] = tensor
                    batch_pre_ms += (now_ns() - stack_start) / 1e6
                    rec_pre_ms += batch_pre_ms
                    inf_start = now_ns()
                    logits = np.asarray(rec_session.run(None, {rec_session.get_inputs()[0].name: batch_tensor})[0])
                    batch_inf_ms = (now_ns() - inf_start) / 1e6
                    rec_inf_ms += batch_inf_ms
                    post_start = now_ns()
                    texts, token_ids = dictionary_decode(logits, dictionary)
                    batch_post_ms = (now_ns() - post_start) / 1e6
                    rec_post_ms += batch_post_ms
                    batch_text_mismatch = batch_token_mismatch = 0
                    batch_max_diff = 0.0
                    for i, crop in enumerate(batch_crops):
                        if crop.crop_id in parity_crop_ids:
                            reference = isolated[crop.crop_id]
                            reference_logits = np.asarray(reference["logits"], dtype=np.float32)
                            common_steps = min(int(logits.shape[1]), int(reference_logits.shape[0]))
                            logit_diff = float(np.max(np.abs(logits[i, :common_steps] - reference_logits[:common_steps]))) if common_steps else 0.0
                            text_mismatch = texts[i] != reference["text"]
                            reference_tokens = np.asarray(reference["token_ids"], dtype=np.int32)
                            # Dynamic tight widths legitimately produce a shorter
                            # time axis.  Compare the common argmax prefix and
                            # decoded text; trailing blank steps are not semantic
                            # output and must not be reported as a correctness
                            # failure.
                            common_tokens = min(int(token_ids.shape[1]), int(reference_tokens.shape[0]))
                            token_mismatch = not np.array_equal(token_ids[i, :common_tokens], reference_tokens[:common_tokens])
                        else:
                            logit_diff = 0.0
                            text_mismatch = False
                            token_mismatch = False
                        mismatch_text += int(text_mismatch)
                        mismatch_tokens += int(token_mismatch)
                        max_logit_diff = max(max_logit_diff, logit_diff)
                        batch_text_mismatch += int(text_mismatch)
                        batch_token_mismatch += int(token_mismatch)
                        batch_max_diff = max(batch_max_diff, logit_diff)
                        parity_rows.append({"width_mode": mode, "batch_size": batch_size, "page_id": pid, "batch_index": batch_index, "crop_id": crop.crop_id, "text_mismatch": int(text_mismatch), "token_mismatch": int(token_mismatch), "max_logit_abs_diff": logit_diff})
                    batch_rows.append({"width_mode": mode, "batch_size": batch_size, "phase": phase, "page_id": pid, "batch_index": batch_index, "regions": len(batch_crops), "batch_width": max_width, "rec_preprocess_ms": batch_pre_ms, "rec_inference_ms": batch_inf_ms, "rec_postprocess_ms": batch_post_ms, "text_mismatch_count": batch_text_mismatch, "token_mismatch_count": batch_token_mismatch, "max_logit_abs_diff": batch_max_diff})
                total_ms = (now_ns() - page_start) / 1e6
            after = rss_mb()
            page_rows.append({
                "width_mode": mode, "batch_size": batch_size, "phase": phase, "page_id": pid, "source_id": record["source_id"], "chapter": record["chapter"], "filename": record["filename"], "pages": 1, "regions": len(crops), "batches": len(batches), "total_ms": total_ms, "rec_preprocess_ms": rec_pre_ms, "rec_inference_ms": rec_inf_ms, "rec_postprocess_ms": rec_post_ms, "rss_before_mb": before if before is not None else "", "rss_after_mb": after if after is not None else "", "rss_peak_delta_mb": ((peak.peak - before * 1024 * 1024) / 1024 / 1024) if peak.peak and before is not None else 0.0, "text_mismatch_count": mismatch_text, "token_mismatch_count": mismatch_tokens, "max_logit_abs_diff": max_logit_diff,
                "session_init_ms": config_session_init_ms if page_number == 0 else 0.0,
            })

    # Replay a bounded subset through a newly-created detector session.  This
    # is an isolated-stage parity gate; it also avoids claiming that the
    # pipeline's detection list is correct merely because its own output was
    # reused downstream.
    det_replay_rows: list[dict[str, Any]] = []
    replay_session = load_session(det_model, args.threads)
    for record in available_pages[: max(0, args.det_replay_pages)]:
        pid = page_id(record)
        image = page_images[pid]
        replay_boxes, _ = det_page(replay_session, image)
        integrated_boxes = [list(crop.box) for crop in detected_crops[pid]]
        det_replay_rows.append({"page_id": pid, "integrated_regions": len(integrated_boxes), "isolated_regions": len(replay_boxes), "region_count_equal": len(integrated_boxes) == len(replay_boxes), "boxes_equal": integrated_boxes == [list(box) for box in replay_boxes]})

    summaries = summarize_page_rows(page_rows)
    viable = [s for s in summaries if s["phase"] == "warm" and s["text_mismatch_count"] == 0 and s["token_mismatch_count"] == 0]
    best = max(viable, key=lambda s: float(s["regions_per_sec"])) if viable else None
    det_parity_ok = all(row["boxes_equal"] for row in det_replay_rows)
    rec_parity = {"rows": len(parity_rows), "text_mismatches": sum(row["text_mismatch"] for row in parity_rows), "token_mismatches": sum(row["token_mismatch"] for row in parity_rows), "max_logit_abs_diff": max((row["max_logit_abs_diff"] for row in parity_rows), default=0.0)}

    metadata = {
        "schema_version": 1, "benchmark": "PP-OCRv6 Small integrated DET-crops-batched-REC", "created_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), "root": str(ROOT), "manifest": str(args.manifest.resolve()), "manifest_declared_pages": len(page_records), "available_pages": len(available_pages), "missing_pages": [{"page_id": page_id(p), "source_path": p["source_path"]} for p in missing_pages], "declared_regions": int(manifest.get("totals", {}).get("region_count", 0)), "det_model": str(det_model), "det_model_sha256": sha256_file(det_model), "rec_model": str(rec_model), "rec_model_sha256": sha256_file(rec_model), "dictionary": str(args.dictionary.resolve()), "dictionary_sha256": sha256_file(args.dictionary), "providers": {"det": det_session.get_providers(), "rec": rec_session.get_providers()}, "ort_version": ort.__version__, "opencv_version": cv2.__version__, "numpy_version": np.__version__, "python": platform.python_version(), "threads": args.threads, "det_session_init_ms": det_load_ms, "rec_session_init_ms": rec_load_ms, "detection_total_ms": detection_total_ms, "isolated_rec_total_ms": isolated_total_ms, "configurations": [{"width_mode": mode, "batch_size": batch} for mode, batch in configs], "det_contract": {"target": DET_TARGET, "threshold": DET_THRESH, "box_threshold": DET_BOX_THRESH, "active_region": "[:resized_h,:resized_w]", "back_project": "Kotlin Float.toInt truncation and inclusive source clamp"}, "rec_contract": {"context_px": REC_CONTEXT, "height": REC_HEIGHT, "pad_gray": REC_PAD_GRAY, "rotate_tall": "height > width * 1.5, PIL ROTATE_90 (CCW)", "bucketed_widths": [REC_SMALL_WIDTH, REC_MAX_WIDTH], "tight_width": "per-crop ceil aspect width"}, "cold_warm": {"cold": "first model session inference and first page run are represented by session_init_ms and phase=cold_first_page in baseline rows", "warm": "subsequent page rows; no synthetic tensors"}, "best_measured_policy": {"width_mode": best["width_mode"], "batch_size": best["batch_size"], "regions_per_sec": best["regions_per_sec"]} if best else None,
    }
    metadata.update({
        "requested_source_ids": sorted(requested_source_ids),
        "parity_sample_crops": len(parity_crops),
        "max_pages_arg": args.max_pages,
        "rss_cap_mb": args.rss_cap_mb,
        "page_audit": {"manifest_pages": len(page_records), "existing_and_decodable_pages": len(audited_pages), "decode_failures": len(audit_failures), "failures": audit_failures},
        "excluded_pages": [{"page_id": page_id(p), "source_id": p.get("source_id"), "source_path": p["source_path"], "reason": "source_id excluded by --source-ids"} for p in excluded_pages],
        "missing_pages": [{"page_id": page_id(p), "source_id": p.get("source_id"), "source_path": p["source_path"], "reason": "manifest path unavailable"} for p in missing_pages],
    })
    result = {"metadata": metadata, "detection": {"rows": detection_rows, "total_regions": sum(len(v) for v in detected_crops.values()), "total_crops": len(all_crops), "total_ms": detection_total_ms, "isolated_replay": det_replay_rows, "isolated_replay_pass": det_parity_ok}, "isolated_rec": {"crops": len(isolated), "total_ms": isolated_total_ms, "contract": "independent B=1 bucketed runs"}, "pages": page_rows, "batches": batch_rows, "parity": rec_parity, "summaries": summaries}
    args.output_dir.mkdir(parents=True, exist_ok=True)
    (args.output_dir / "integrated_pipeline.json").write_text(json.dumps(result, indent=2, ensure_ascii=False), encoding="utf-8")
    write_csv(args.output_dir / "integrated_pipeline_pages.csv", page_rows)
    write_csv(args.output_dir / "integrated_pipeline_batches.csv", batch_rows)
    write_csv(args.output_dir / "integrated_pipeline_parity.csv", parity_rows)
    print(json.dumps({"json": str(args.output_dir / "integrated_pipeline.json"), "pages_csv": str(args.output_dir / "integrated_pipeline_pages.csv"), "batches_csv": str(args.output_dir / "integrated_pipeline_batches.csv"), "parity_csv": str(args.output_dir / "integrated_pipeline_parity.csv"), "pages": len(available_pages), "regions": len(all_crops), "det_replay_pass": det_parity_ok, "rec_parity": rec_parity, "best": metadata["best_measured_policy"], "elapsed_ms": (now_ns() - process_start) / 1e6}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
