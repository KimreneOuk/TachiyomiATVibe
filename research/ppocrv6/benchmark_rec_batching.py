#!/usr/bin/env python3
"""Research-only PP-OCRv6 Small REC batching benchmark.

This harness intentionally mirrors the production Kotlin recognizer in
``PaddleOcrV6SmallEngine``:

* optional 12px context padding around detector boxes;
* 90-degree CCW rotation for tall boxes (the production vertical-line path);
* 48px height, ceil aspect-ratio resize, max width 1600;
* width buckets 640/1600, gray (128) padding, RGB NCHW ``[-1, 1]``;
* the production CTC argmax, repeat-collapse, blank removal and dictionary.

It is deliberately independent of the Android build and writes only research
artifacts.  The model itself is the pristine checked-in PP-OCRv6 small REC
ONNX.  ``--width-mode tight`` is a derived exploratory mode: it retains the
same per-crop resize/normalization, but uses the ONNX model's dynamic width to
reduce inter-crop padding inside a batch.  ``bucketed`` is the production
shape policy and is the primary evidence mode.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import math
import os
import pathlib
import statistics
import threading
import time
from dataclasses import dataclass
from typing import Any, Iterable

import cv2
import numpy as np
import onnxruntime as ort
import psutil


HEIGHT = 48
SMALL_WIDTH = 640
MAX_WIDTH = 1600
PAD_GRAY = 128
BUCKETS = (96, 160, 256, 384, 512)


def percentile(values: list[float], p: float) -> float | None:
    if not values:
        return None
    return float(np.percentile(np.asarray(values, dtype=np.float64), p))


def width_bucket(width: int) -> str:
    for limit in BUCKETS:
        if width <= limit:
            return f"<={limit}"
    return ">512"


def sha256_file(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


@dataclass(frozen=True)
class Crop:
    crop_id: str
    page_id: str
    page_path: pathlib.Path
    box_index: int
    source_width: int
    source_height: int
    rotated: bool
    image: np.ndarray
    width: int
    height: int
    scaled_width: int
    required_width: int
    width_bucket: str


@dataclass
class Prepared:
    crop: Crop
    tensor: np.ndarray


def production_resize_width(width: int, height: int) -> int:
    safe_width = max(1, width)
    safe_height = max(1, height)
    scaled = math.ceil(safe_width * (HEIGHT / safe_height))
    return max(1, min(MAX_WIDTH, scaled))


def production_align_width(scaled_width: int) -> int:
    return SMALL_WIDTH if scaled_width <= SMALL_WIDTH else MAX_WIDTH


def prepare_crop(crop: Crop, width_mode: str) -> Prepared:
    resized = cv2.resize(crop.image, (crop.scaled_width, HEIGHT), interpolation=cv2.INTER_LINEAR)
    # cv2 loads BGR. Android's production path writes R/G/B planes from its
    # ARGB bitmap, so swap channels before NCHW conversion.
    rgb = cv2.cvtColor(resized, cv2.COLOR_BGR2RGB).astype(np.float32)
    if width_mode == "bucketed":
        target_width = crop.required_width
    elif width_mode == "tight":
        # The caller repads this to the batch's max scaled width. Keeping a
        # per-crop array here makes the padding accounting explicit.
        target_width = crop.scaled_width
    else:
        raise ValueError(f"unknown width mode: {width_mode}")
    padded = np.full((HEIGHT, target_width, 3), PAD_GRAY, dtype=np.float32)
    padded[:, : crop.scaled_width, :] = rgb
    # Production formula: (value / 255 - .5) / .5.
    tensor = np.transpose((padded / 255.0 - 0.5) / 0.5, (2, 0, 1)).astype(np.float32)
    return Prepared(crop=crop, tensor=tensor)


def _make_crop(
    page: np.ndarray,
    page_path: pathlib.Path,
    page_id: str,
    box_index: int,
    box: list[float],
    context_pad: int,
    rotate_tall: bool,
    crop_id: str | None = None,
) -> Crop | None:
    if len(box) < 4:
        return None
    x1, y1, x2, y2 = (int(round(float(v))) for v in box[:4])
    left, right = sorted((x1, x2))
    top, bottom = sorted((y1, y2))
    left = max(0, left - context_pad)
    top = max(0, top - context_pad)
    right = min(page.shape[1], right + context_pad)
    bottom = min(page.shape[0], bottom + context_pad)
    if right <= left or bottom <= top:
        return None
    image = page[top:bottom, left:right].copy()
    source_width, source_height = image.shape[1], image.shape[0]
    rotated = rotate_tall and source_height > source_width * 1.5
    if rotated:
        image = cv2.rotate(image, cv2.ROTATE_90_COUNTERCLOCKWISE)
    width, height = image.shape[1], image.shape[0]
    scaled_width = production_resize_width(width, height)
    return Crop(
        crop_id=crop_id or f"{page_id}:{box_index}",
        page_id=page_id,
        page_path=page_path,
        box_index=box_index,
        source_width=source_width,
        source_height=source_height,
        rotated=rotated,
        image=image,
        width=width,
        height=height,
        scaled_width=scaled_width,
        required_width=production_align_width(scaled_width),
        width_bucket=width_bucket(width),
    )


def load_crops(corpus_root: pathlib.Path, context_pad: int, rotate_tall: bool) -> list[Crop]:
    crops: list[Crop] = []
    for boxes_path in sorted(corpus_root.glob("*/boxes.json")):
        page_dir = boxes_path.parent
        page_path = page_dir / "page.jpg"
        if not page_path.exists():
            continue
        page = cv2.imread(str(page_path), cv2.IMREAD_COLOR)
        if page is None:
            continue
        boxes = json.loads(boxes_path.read_text(encoding="utf-8")).get("source_boxes", [])
        page_id = page_dir.name
        for box_index, box in enumerate(boxes):
            crop = _make_crop(page, page_path, page_id, box_index, box, context_pad, rotate_tall)
            if crop is not None:
                crops.append(crop)
    return crops


def load_manifest_crops(manifest_path: pathlib.Path, rotate_tall: bool) -> tuple[list[Crop], dict[str, Any]]:
    """Load the bootstrap's 3-chapter/76-page annotated REC calibration set.

    ``ocr_box`` is already the production 12px context-expanded region, so no
    additional padding is applied.  The manifest deliberately keeps source
    paths external; missing pages are skipped and reported by the caller.
    """
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    crops: list[Crop] = []
    stats: dict[str, Any] = {
        "manifest_pages": 0,
        "readable_pages": 0,
        "skipped_pages": 0,
        "manifest_regions": 0,
        "loaded_regions": 0,
        "skipped_regions": 0,
        "skipped_page_sources": [],
        "readable_page_count": 0,
        "unreadable_page_count": 0,
        "readable_region_count": 0,
        "page_audit": [],
        "chapter_runtime": {},
    }
    for chapter in manifest.get("chapters", []):
        for page_entry in chapter.get("pages", []):
            stats["manifest_pages"] += 1
            chapter_name = str(chapter.get("chapter", "unknown"))
            chapter_runtime = stats["chapter_runtime"].setdefault(
                chapter_name,
                {"manifest_pages": 0, "readable_pages": 0, "unreadable_pages": 0, "manifest_regions": 0, "readable_regions": 0},
            )
            chapter_runtime["manifest_pages"] += 1
            region_count = len(page_entry.get("regions", []))
            chapter_runtime["manifest_regions"] += region_count
            stats["manifest_regions"] += region_count
            page_path = pathlib.Path(page_entry["source_path"])
            page_bytes = None
            file_hash = None
            try:
                page_bytes = page_path.stat().st_size
                file_hash = sha256_file(page_path)
            except OSError:
                pass
            page = cv2.imread(str(page_path), cv2.IMREAD_COLOR)
            audit = {
                "chapter": chapter_name,
                "page_index": page_entry.get("index"),
                "path": str(page_path),
                "exists": page_bytes is not None,
                "byte_size": page_bytes,
                "sha256": file_hash,
                "decoded_width": int(page.shape[1]) if page is not None else None,
                "decoded_height": int(page.shape[0]) if page is not None else None,
                "region_count": len(page_entry.get("regions", [])),
                "status": "readable" if page is not None else "unreadable",
            }
            stats["page_audit"].append(audit)
            if page is None:
                stats["skipped_pages"] += 1
                stats["unreadable_page_count"] += 1
                chapter_runtime["unreadable_pages"] += 1
                stats["skipped_page_sources"].append(str(page_path))
                stats["skipped_regions"] += region_count
                continue
            stats["readable_pages"] += 1
            stats["readable_page_count"] += 1
            chapter_runtime["readable_pages"] += 1
            page_id = f"{chapter.get('chapter', 'chapter')}:{page_entry.get('index', 0)}"
            for region_index, region in enumerate(page_entry.get("regions", [])):
                box = region.get("ocr_box") or region.get("box")
                region_id = str(region.get("id", f"r{region_index:02d}"))
                crop = _make_crop(
                    page,
                    page_path,
                    page_id,
                    region_index,
                    box,
                    context_pad=0,
                    rotate_tall=rotate_tall,
                    crop_id=f"{page_id}:{region_id}",
                )
                if crop is not None:
                    crops.append(crop)
                    stats["loaded_regions"] += 1
                    stats["readable_region_count"] += 1
                    chapter_runtime["readable_regions"] += 1
                else:
                    stats["skipped_regions"] += 1
    return crops, stats


def load_fixture_crops() -> list[Crop]:
    """Small deterministic fixtures for parity checks independent of corpus."""
    fixtures: list[Crop] = []
    patterns = {
        "blank": np.full((48, 96, 3), 128, dtype=np.uint8),
        "black": np.zeros((48, 96, 3), dtype=np.uint8),
        "gradient": np.tile(np.arange(96, dtype=np.uint8)[None, :, None], (48, 1, 3)),
        "checker": (np.indices((48, 96)).sum(axis=0) % 2 * 255).astype(np.uint8)[..., None].repeat(3, axis=2),
    }
    for name, image in patterns.items():
        scaled_width = production_resize_width(image.shape[1], image.shape[0])
        fixtures.append(
            Crop(
                crop_id=f"fixture:{name}", page_id="fixture", page_path=pathlib.Path("<fixture>"),
                box_index=0, source_width=image.shape[1], source_height=image.shape[0], rotated=False,
                image=image, width=image.shape[1], height=image.shape[0], scaled_width=scaled_width,
                required_width=production_align_width(scaled_width), width_bucket=width_bucket(image.shape[1]),
            )
        )
    return fixtures


class PeakRssSampler:
    def __init__(self, interval_s: float = 0.002) -> None:
        self.process = psutil.Process(os.getpid())
        self.interval_s = interval_s
        self.peak = 0
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None

    def _sample(self) -> None:
        while not self._stop.is_set():
            try:
                self.peak = max(self.peak, self.process.memory_info().rss)
            except psutil.Error:
                pass
            self._stop.wait(self.interval_s)

    def __enter__(self) -> "PeakRssSampler":
        self.peak = self.process.memory_info().rss
        self._thread = threading.Thread(target=self._sample, daemon=True)
        self._thread.start()
        return self

    def __exit__(self, *_: Any) -> None:
        self._stop.set()
        if self._thread:
            self._thread.join(timeout=1.0)


def decode_logits(logits: np.ndarray, dictionary: list[str]) -> tuple[list[str], np.ndarray, np.ndarray]:
    """Mirror PaddleCtcDecoder: blank=0, collapse repeats, trim spaces."""
    ids = np.argmax(logits, axis=2).astype(np.int32)
    max_values = np.max(logits, axis=2)
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
    return texts, ids, max_values


def run_batch(session: ort.InferenceSession, input_name: str, prepared: list[Prepared]) -> dict[str, Any]:
    max_width = max(item.tensor.shape[2] for item in prepared)
    batch = np.full((len(prepared), 3, HEIGHT, max_width), 0.0, dtype=np.float32)
    # The input tensors already contain normalized gray padding.  0.0 is the
    # normalized PAD_GRAY value (within 1/255 due to 128/255), matching the
    # production pool's initialized pad pixels.
    copy_start = time.perf_counter_ns()
    for index, item in enumerate(prepared):
        batch[index, :, :, : item.tensor.shape[2]] = item.tensor
    copy_ms = (time.perf_counter_ns() - copy_start) / 1e6
    run_start = time.perf_counter_ns()
    outputs = session.run(None, {input_name: batch})
    run_ms = (time.perf_counter_ns() - run_start) / 1e6
    logits = np.asarray(outputs[0])
    return {"batch": batch, "logits": logits, "copy_ms": copy_ms, "run_ms": run_ms}


def balanced_order(crops: list[Crop]) -> list[Crop]:
    by_bucket: dict[str, list[Crop]] = {}
    for crop in crops:
        by_bucket.setdefault(crop.width_bucket, []).append(crop)
    ordered: list[Crop] = []
    # Round-robin preserves all requested width buckets in each batch-size run.
    while any(by_bucket.values()):
        for key in ("<=96", "<=160", "<=256", "<=384", "<=512", ">512"):
            values = by_bucket.get(key, [])
            if values:
                ordered.append(values.pop(0))
    return ordered


def make_batches(crops: list[Crop], batch_size: int, width_mode: str) -> list[list[Prepared]]:
    # Keep each batch within one requested source-width bucket. This is the
    # practical bucketing policy under test and makes per-bucket latency,
    # throughput, RSS and padding waste directly observable in the raw data.
    by_bucket: dict[str, list[Crop]] = {}
    for crop in crops:
        by_bucket.setdefault(crop.width_bucket, []).append(crop)
    batches: list[list[Prepared]] = []
    for bucket in ("<=96", "<=160", "<=256", "<=384", "<=512", ">512"):
        values = by_bucket.get(bucket, [])
        prepared = [prepare_crop(crop, width_mode) for crop in values]
        batches.extend(prepared[i : i + batch_size] for i in range(0, len(prepared), batch_size))
    return batches


def summarize(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    groups: dict[tuple[Any, ...], list[dict[str, Any]]] = {}
    for row in rows:
        key = (row["width_mode"], row["batch_size"], row["width_bucket"])
        groups.setdefault(key, []).append(row)
    summaries: list[dict[str, Any]] = []
    for (mode, batch_size, bucket), values in groups.items():
        def vals(field: str) -> list[float]:
            return [float(v[field]) for v in values if v.get(field) is not None]

        total_crops = sum(int(v["crops"]) for v in values)
        total_ms = sum(float(v["total_ms"]) for v in values)
        summaries.append({
            "width_mode": mode,
            "batch_size": batch_size,
            "width_bucket": bucket,
            "batches": len(values),
            "crops": total_crops,
            "padding_waste_pct_mean": float(np.mean([v["padding_waste_pct"] for v in values])),
            "batch_ms_p50": percentile(vals("total_ms"), 50),
            "batch_ms_p90": percentile(vals("total_ms"), 90),
            "batch_ms_p95": percentile(vals("total_ms"), 95),
            "per_crop_ms_p50": percentile(vals("per_crop_ms"), 50),
            "per_crop_ms_p90": percentile(vals("per_crop_ms"), 90),
            "per_crop_ms_p95": percentile(vals("per_crop_ms"), 95),
            "throughput_crops_s_mean": float(total_crops / (total_ms / 1000.0)) if total_ms else None,
            "copy_ms_mean": float(np.mean(vals("copy_ms"))),
            "run_ms_mean": float(np.mean(vals("run_ms"))),
            "decode_ms_mean": float(np.mean(vals("decode_ms"))),
            "rss_peak_delta_mb": max(float(v["rss_peak_delta_mb"]) for v in values),
            "text_mismatch_count": sum(int(v["text_mismatch_count"]) for v in values),
            "token_mismatch_count": sum(int(v["token_mismatch_count"]) for v in values),
            "max_logit_abs_diff": max(float(v["max_logit_abs_diff"]) for v in values),
        })
    return sorted(summaries, key=lambda x: (x["width_mode"], x["batch_size"], x["width_bucket"]))


def write_csv(path: pathlib.Path, rows: Iterable[dict[str, Any]]) -> None:
    rows = list(rows)
    if not rows:
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    fields = list(rows[0].keys())
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", type=pathlib.Path, default=pathlib.Path("app/src/main/assets/models/ocr/paddle-v6-small/inference.onnx"))
    parser.add_argument("--dictionary", type=pathlib.Path, default=pathlib.Path("app/src/main/assets/models/ocr/paddle-v6-small/PP-OCRv6_small_rec.txt"))
    parser.add_argument("--corpus", type=pathlib.Path, default=pathlib.Path("tools/aot_corpus/real_corpus"))
    parser.add_argument("--dataset-manifest", type=pathlib.Path, default=pathlib.Path("research/dataset/manifest.json"), help="Annotated bootstrap manifest; used when present, otherwise --corpus is used.")
    parser.add_argument("--output-dir", type=pathlib.Path, default=pathlib.Path("research/findings"))
    parser.add_argument("--batch-sizes", default="1,2,4,8,16")
    parser.add_argument("--width-modes", default="bucketed,tight", help="Comma-separated: bucketed is production, tight is exploratory dynamic-width.")
    parser.add_argument("--context-pad", type=int, default=12)
    parser.add_argument("--no-rotate-tall", action="store_true")
    parser.add_argument("--max-crops", type=int, default=0, help="Optional deterministic cap after balanced ordering; 0 means all crops.")
    parser.add_argument("--warmup", type=int, default=1)
    args = parser.parse_args()

    model_path = args.model.resolve()
    dictionary_path = args.dictionary.resolve()
    corpus_path = args.corpus.resolve()
    out_dir = args.output_dir.resolve()
    out_dir.mkdir(parents=True, exist_ok=True)
    dictionary = dictionary_path.read_text(encoding="utf-8").splitlines()
    manifest_path = args.dataset_manifest.resolve()
    if manifest_path.exists():
        crops, dataset_stats = load_manifest_crops(manifest_path, not args.no_rotate_tall)
        crop_source = str(manifest_path)
        effective_context_pad = 0
    else:
        crops = load_crops(corpus_path, args.context_pad, not args.no_rotate_tall)
        dataset_stats = {
            "manifest_pages": None,
            "readable_pages": None,
            "skipped_pages": None,
            "manifest_regions": None,
            "loaded_regions": len(crops),
            "skipped_regions": None,
            "skipped_page_sources": [],
        }
        crop_source = str(corpus_path)
        effective_context_pad = args.context_pad
    available_real_crop_count = len(crops)
    if args.max_crops:
        crops = balanced_order(crops)[: args.max_crops]
    if not crops:
        raise SystemExit(f"No crops found under {corpus_path}")

    providers = ["CPUExecutionProvider"]
    session_start = time.perf_counter_ns()
    session = ort.InferenceSession(str(model_path), providers=providers)
    session_init_ms = (time.perf_counter_ns() - session_start) / 1e6
    input_meta = session.get_inputs()[0]
    input_name = input_meta.name
    model_shape = [str(x) for x in input_meta.shape]
    output_meta = session.get_outputs()[0]

    # Warm-up with a controlled fixture and one real crop. Warmup is outside
    # the measured rows but recorded in metadata.
    warmup_crops = load_fixture_crops()[:1] + crops[:1]
    warmup_batches = make_batches(warmup_crops, 1, "bucketed")
    for _ in range(max(0, args.warmup)):
        for batch in warmup_batches:
            run_batch(session, input_name, batch)

    parity_rows: list[dict[str, Any]] = []
    rows: list[dict[str, Any]] = []
    width_modes = [value.strip() for value in args.width_modes.split(",") if value.strip()]
    unknown_modes = sorted(set(width_modes) - {"bucketed", "tight"})
    if unknown_modes:
        raise SystemExit(f"Unknown width mode(s): {', '.join(unknown_modes)}")
    for width_mode in width_modes:
        mode_crops = load_fixture_crops() + crops
        mode_baseline: dict[str, tuple[str, np.ndarray, np.ndarray]] = {}
        for batch_size in [int(value) for value in args.batch_sizes.split(",") if value.strip()]:
            batches = make_batches(mode_crops, batch_size, width_mode)
            for batch_index, batch in enumerate(batches):
                bucket_counts: dict[str, int] = {}
                for item in batch:
                    bucket_counts[item.crop.width_bucket] = bucket_counts.get(item.crop.width_bucket, 0) + 1
                result_rss_before = psutil.Process(os.getpid()).memory_info().rss
                with PeakRssSampler() as rss_sampler:
                    total_start = time.perf_counter_ns()
                    result = run_batch(session, input_name, batch)
                    decode_start = time.perf_counter_ns()
                    texts, token_ids, maxima = decode_logits(result["logits"], dictionary)
                    decode_ms = (time.perf_counter_ns() - decode_start) / 1e6
                    total_ms = (time.perf_counter_ns() - total_start) / 1e6
                rss_after = psutil.Process(os.getpid()).memory_info().rss
                max_width = max(item.tensor.shape[2] for item in batch)
                required_pixels = sum(item.crop.scaled_width for item in batch)
                capacity_pixels = len(batch) * max_width
                padding_waste = 1.0 - (required_pixels / capacity_pixels)
                batch_text_mismatch = 0
                batch_token_mismatch = 0
                batch_max_logit_diff = 0.0
                for item_index, item in enumerate(batch):
                    if batch_size == 1:
                        # The measured B=1 run is the parity reference for
                        # this exact width mode. This avoids a duplicate
                        # full-corpus pass and makes tight-mode comparisons
                        # honest about its dynamic-width padding behavior.
                        mode_baseline[item.crop.crop_id] = (
                            texts[item_index], token_ids[item_index].copy(), result["logits"][item_index].copy()
                        )
                    reference = mode_baseline.get(item.crop.crop_id)
                    text_mismatch = reference is not None and texts[item_index] != reference[0]
                    token_diff = reference is not None and not np.array_equal(token_ids[item_index], reference[1])
                    if reference is not None:
                        candidate_logits = result["logits"][item_index]
                        reference_logits = reference[2]
                        overlap_steps = min(candidate_logits.shape[0], reference_logits.shape[0])
                        logit_diff = float(np.max(np.abs(candidate_logits[:overlap_steps] - reference_logits[:overlap_steps]))) if overlap_steps else 0.0
                        logit_shape_mismatch = candidate_logits.shape != reference_logits.shape
                    else:
                        logit_diff = 0.0
                        logit_shape_mismatch = False
                    batch_text_mismatch += int(text_mismatch)
                    batch_token_mismatch += int(token_diff)
                    batch_max_logit_diff = max(batch_max_logit_diff, logit_diff)
                    parity_rows.append({
                        "width_mode": width_mode,
                        "batch_size": batch_size,
                        "batch_index": batch_index,
                        "crop_id": item.crop.crop_id,
                        "text_b1": reference[0],
                        "text_batch": texts[item_index],
                        "text_mismatch": text_mismatch,
                        "token_mismatch": token_diff,
                        "max_logit_abs_diff": logit_diff,
                        "logit_shape_mismatch": logit_shape_mismatch,
                    })
                # A row per benchmark batch is useful for quantiles and raw
                # evidence; width_bucket is exact only for homogeneous batches.
                row_bucket = next(iter(bucket_counts)) if len(bucket_counts) == 1 else "mixed"
                rows.append({
                    "width_mode": width_mode,
                    "batch_size": batch_size,
                    "batch_index": batch_index,
                    "width_bucket": row_bucket,
                    "bucket_counts": json.dumps(bucket_counts, sort_keys=True),
                    "crops": len(batch),
                    "batch_width": max_width,
                    "padding_waste_pct": padding_waste * 100.0,
                    "copy_ms": result["copy_ms"],
                    "run_ms": result["run_ms"],
                    "decode_ms": decode_ms,
                    "total_ms": total_ms,
                    "per_crop_ms": total_ms / len(batch),
                    "throughput_crops_s": len(batch) / (total_ms / 1000.0),
                    "rss_before_mb": result_rss_before / 1024**2,
                    "rss_after_mb": rss_after / 1024**2,
                    "rss_peak_delta_mb": (rss_sampler.peak - result_rss_before) / 1024**2,
                    "text_mismatch_count": batch_text_mismatch,
                    "token_mismatch_count": batch_token_mismatch,
                    "max_logit_abs_diff": batch_max_logit_diff,
                })

    # Controlled fixture rows are embedded in parity CSV; record the exact
    # fixture subset separately so parity claims are visibly independent of
    # detector/crop data.
    fixture_ids = {fixture.crop_id for fixture in load_fixture_crops()}
    fixture_parity = [row for row in parity_rows if row["crop_id"] in fixture_ids]
    real_parity = [row for row in parity_rows if row["crop_id"] not in fixture_ids]
    metadata = {
        "benchmark": "PP-OCRv6 Small REC batching",
        "evidence_labels": {
            "E0_pristine_model": "checked-in app/src/main/assets/models/ocr/paddle-v6-small/inference.onnx",
            "E1_production_preprocess": "Python mirror of PaddleOcrV6SmallEngine plus RoiPageRecognitionEngine 12px pad/tall rotation",
            "E2_real_crops": "tools/aot_corpus/real_corpus source_boxes cropped from checked-in page.jpg files",
            "E3_controlled_fixtures": "deterministic blank/black/gradient/checker fixtures",
            "E4_exploratory": "tight width mode uses dynamic ONNX width and is not production behavior",
        },
        "model": str(model_path),
        "model_sha256": sha256_file(model_path),
        "dictionary": str(dictionary_path),
        "dictionary_sha256": sha256_file(dictionary_path),
        "session_init_ms": session_init_ms,
        "providers": session.get_providers(),
        "input_name": input_name,
        "input_shape": model_shape,
        "output_name": output_meta.name,
        "output_shape": [str(x) for x in output_meta.shape],
        "dynamic_batch_supported_by_shape": str(input_meta.shape[0]).lower().startswith("dynamic"),
        "dynamic_width_supported_by_shape": str(input_meta.shape[3]).lower().startswith("dynamic"),
        "corpus": str(crop_source),
        "dataset_manifest": str(manifest_path) if manifest_path.exists() else None,
        "real_crop_count": len(crops),
        "available_real_crop_count": available_real_crop_count,
        "sample_limit": args.max_crops or None,
        "dataset_stats": dataset_stats,
        "fixture_count": len(load_fixture_crops()),
        "context_pad_px": effective_context_pad,
        "rotate_tall": not args.no_rotate_tall,
        "height": HEIGHT,
        "production_width_buckets": [SMALL_WIDTH, MAX_WIDTH],
        "batch_sizes": [int(value) for value in args.batch_sizes.split(",") if value.strip()],
        "width_modes": [value.strip() for value in args.width_modes.split(",") if value.strip()],
        "ort_version": ort.__version__,
        "opencv_version": cv2.__version__,
        "numpy_version": np.__version__,
    }
    raw_path = out_dir / "rec_batching_raw.csv"
    parity_path = out_dir / "rec_batching_parity.csv"
    summary_path = out_dir / "rec_batching_summary.json"
    meta_path = out_dir / "rec_batching_metadata.json"
    page_audit_path = out_dir / "rec_batching_page_audit.csv"
    write_csv(raw_path, rows)
    write_csv(parity_path, parity_rows)
    write_csv(page_audit_path, metadata["dataset_stats"].get("page_audit", []))
    summary = {
        "metadata": metadata,
        "summaries": summarize(rows),
        "parity": {
            "fixture_rows": len(fixture_parity),
            "fixture_text_mismatches": sum(int(row["text_mismatch"]) for row in fixture_parity),
            "fixture_token_mismatches": sum(int(row["token_mismatch"]) for row in fixture_parity),
            "real_rows": len(real_parity),
            "real_text_mismatches": sum(int(row["text_mismatch"]) for row in real_parity),
            "real_token_mismatches": sum(int(row["token_mismatch"]) for row in real_parity),
            "real_logit_shape_mismatches": sum(int(row["logit_shape_mismatch"]) for row in real_parity),
            "real_max_logit_abs_diff": max((float(row["max_logit_abs_diff"]) for row in real_parity), default=0.0),
        },
    }
    summary_path.write_text(json.dumps(summary, indent=2, ensure_ascii=False), encoding="utf-8")
    meta_path.write_text(json.dumps(metadata, indent=2, ensure_ascii=False), encoding="utf-8")
    print(json.dumps({"raw": str(raw_path), "parity": str(parity_path), "summary": str(summary_path), "metadata": str(meta_path), "page_audit": str(page_audit_path), "real_crops": len(crops)}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
