#!/usr/bin/env python3
"""Reproducible CTD (detector-v4) vs PP-OCRv6 small DET comparison.

The preferred input is a directory of real page images.  If a page's
``.studio/ocr.json`` (or a nearby ``ocr.json``) is present, its boxes are used
as ground truth.  The script also accepts the AOT ``real_corpus_faithful``
layout used by the acceleration research.  That fallback contains page crops
and binary masks, not full pages or human OCR labels; it is therefore reported
as a weak-mask protocol and must not be read as a replacement decision.

This file intentionally keeps the two models' preprocessing/postprocessing
close to the production Kotlin contracts:
* detector-v4: RGB [0,1], 640x640 stretch, score >= .45, same-label geometric
  duplicate suppression omitted only from the raw count and applied in the
  ``ctd_dedup`` count.
* PP-OCRv6 small DET: long-side resize to 736, right/bottom zero pad, ImageNet
  normalization, threshold .2, mean score .45, connected components and the
  production line-fragment merge.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import math
import os
import platform
import re
import sys
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
except ImportError:  # pragma: no cover - diagnostics still work without psutil
    psutil = None


ROOT = Path(__file__).resolve().parents[1]
CTD_DEFAULT = ROOT / "app/src/main/assets/models/detection/detector-v4-s_int8.onnx"
PP_DEFAULT = ROOT / "app/src/main/assets/models/ocr/paddle-v6-small/det/inference.onnx"
DEFAULT_EXTERNAL = [
    Path(r"C:\Users\User\Downloads\manga_test_chapters\ore-ni-trauma-wo-ataeta-joshitachi-ga-chirachira-mitekuru-kedo-zannen-desu-ga-teokure_desu_ch16"),
    Path(r"C:\Users\User\Documents\TachiyomiAT-1.16.8-dev\TachiyomiAT-1.16.8-dev\TachiyomiAT-1.16.8-dev_gui_probe\dl_out"),
]


@dataclass
class Box:
    x1: float
    y1: float
    x2: float
    y2: float
    score: float = 1.0
    label: str = "unknown"
    source: str = "model"

    @property
    def area(self) -> float:
        return max(0.0, self.x2 - self.x1) * max(0.0, self.y2 - self.y1)


@dataclass
class Page:
    page_id: str
    image: Path
    annotation: Path | None
    mask: Path | None
    category: str
    protocol: str
    manifest_truth: list[Box] | None = None


def rss_bytes() -> int | None:
    if psutil is None:
        return None
    return int(psutil.Process(os.getpid()).memory_info().rss)


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def model_inventory(path: Path) -> dict[str, Any]:
    return {"path": str(path), "exists": path.is_file(), "bytes": path.stat().st_size if path.is_file() else None,
            "sha256": sha256(path) if path.is_file() else None}


def image_files(root: Path) -> list[Path]:
    return sorted(p for p in root.rglob("*") if p.is_file() and p.suffix.lower() in {".jpg", ".jpeg", ".png", ".webp"})


def find_annotation(image: Path) -> Path | None:
    candidates = [image.with_suffix(".studio/ocr.json"), image.parent / ".studio" / "ocr.json",
                  image.parent / "ocr.json", image.parent.parent / ".studio" / "ocr.json"]
    return next((p for p in candidates if p.is_file()), None)


def discover_pages(root: Path) -> list[Page]:
    """Discover full pages, or AOT faithful crop pages when present."""
    pages: list[Page] = []
    faithful = root / "real_corpus_faithful"
    if faithful.is_dir():
        for page_dir in sorted(p for p in faithful.iterdir() if p.is_dir()):
            for kind in ("bubble", "free_text"):
                image = page_dir / kind / "page.jpg"
                if image.is_file():
                    mask = page_dir / kind / "mask.png"
                    pages.append(Page(f"{page_dir.name}/{kind}", image, find_annotation(image),
                                      mask if mask.is_file() else None, kind, "weak_mask"))
        return pages
    for image in image_files(root):
        # Avoid treating masks/thumbnails as pages when a named page exists.
        if image.name.lower() in {"mask.png", "overlay.png", "annotated.png"}:
            continue
        pages.append(Page(str(image.relative_to(root)), image, find_annotation(image), None,
                          "unknown", "human_or_unlabeled"))
    return pages


def discover_manifest(path: Path) -> list[Page]:
    """Load the fixed 3-chapter/76-page manifest produced by dataset bootstrap."""
    data = json.loads(path.read_text(encoding="utf-8"))
    pages: list[Page] = []
    for chapter in data.get("chapters", []):
        chapter_name = chapter.get("chapter", "unknown")
        for item in chapter.get("pages", []):
            image = Path(item.get("source_path", ""))
            regions = item.get("regions", [])
            truth: list[Box] = []
            classes: set[str] = set()
            for region in regions:
                coords = region.get("ocr_box", region.get("box"))
                if isinstance(coords, list) and len(coords) >= 4:
                    truth.append(Box(*map(float, coords[:4]), score=float(region.get("score", 1.0)),
                                      label=str(region.get("class", "unknown")), source="manifest_ocr_box"))
                    classes.add(str(region.get("class", "unknown")))
            if image.is_file():
                pages.append(Page(f"{chapter_name}/{item.get('filename', image.name)}", image,
                                  Path(chapter.get("annotation_path", "")) if Path(chapter.get("annotation_path", "")).is_file() else find_annotation(image),
                                  None, "+".join(sorted(classes)) or "unlabeled", "manifest_ocr_box", truth))
    return pages


def json_boxes(value: Any) -> list[Box]:
    """Tolerate common studio box encodings while preserving an audit trail."""
    out: list[Box] = []
    if isinstance(value, dict):
        for key in ("boxes", "regions", "lines", "detections", "annotations", "items", "text"):
            if key in value:
                out.extend(json_boxes(value[key]))
        if not out:
            coords = value.get("bbox", value.get("box", value.get("rect")))
            if coords is not None:
                out.extend(json_boxes(coords))
        return out
    if isinstance(value, list):
        if len(value) >= 4 and all(isinstance(x, (int, float)) for x in value[:4]):
            x1, y1, x2, y2 = map(float, value[:4])
            # Some tools encode x,y,w,h. Prefer explicit xyxy in annotations;
            # this conservative conversion is only for the fallback protocol.
            if x2 <= x1 or y2 <= y1:
                x2, y2 = x1 + max(0.0, x2), y1 + max(0.0, y2)
            out.append(Box(x1, y1, x2, y2, source="annotation"))
        else:
            for item in value:
                out.extend(json_boxes(item))
    return out


def mask_boxes(mask: Path) -> list[Box]:
    arr = cv2.imread(str(mask), cv2.IMREAD_GRAYSCALE)
    if arr is None:
        return []
    _, binary = cv2.threshold(arr, 1, 255, cv2.THRESH_BINARY)
    n, labels, stats, _ = cv2.connectedComponentsWithStats(binary, 8)
    out: list[Box] = []
    for i in range(1, n):
        x, y, w, h, area = stats[i]
        if area >= 8:
            out.append(Box(float(x), float(y), float(x + w), float(y + h), source="weak_mask"))
    return out


def read_ground_truth(page: Page) -> tuple[list[Box], str]:
    if page.manifest_truth is not None:
        return page.manifest_truth, "manifest_ocr_box"
    if page.annotation:
        try:
            boxes = json_boxes(json.loads(page.annotation.read_text(encoding="utf-8")))
            if boxes:
                return boxes, "human_or_studio_json"
        except (OSError, json.JSONDecodeError):
            pass
    if page.mask:
        return mask_boxes(page.mask), "weak_mask_components"
    return [], "unlabeled"


def load_rgb(path: Path) -> np.ndarray:
    bgr = cv2.imread(str(path), cv2.IMREAD_COLOR)
    if bgr is None:
        raise ValueError(f"unreadable image: {path}")
    return cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB)


def ctd_infer(session: ort.InferenceSession, rgb: np.ndarray) -> tuple[list[Box], list[Box], dict[str, float]]:
    h, w = rgb.shape[:2]
    t0 = time.perf_counter()
    inp = cv2.resize(rgb, (640, 640), interpolation=cv2.INTER_LINEAR).astype(np.float32) / 255.0
    inp = np.transpose(inp, (2, 0, 1))[None, ...]
    t1 = time.perf_counter()
    outputs = session.run(None, {"images": inp, "orig_target_sizes": np.array([[w, h]], dtype=np.int64)})
    t2 = time.perf_counter()
    labels, boxes, scores = outputs
    raw: list[Box] = []
    for label, box, score in zip(labels[0], boxes[0], scores[0]):
        score = float(score)
        if not math.isfinite(score) or score < 0.45:
            continue
        raw.append(Box(float(box[0]), float(box[1]), float(box[2]), float(box[3]), score, str(int(label)), "ctd"))
    dedup = dedup_same_label(raw)
    t3 = time.perf_counter()
    return raw, dedup, {"preprocess_ms": (t1 - t0) * 1000, "inference_ms": (t2 - t1) * 1000,
                         "postprocess_ms": (t3 - t2) * 1000, "total_ms": (t3 - t0) * 1000}


def iou(a: Box, b: Box) -> float:
    ix1, iy1, ix2, iy2 = max(a.x1, b.x1), max(a.y1, b.y1), min(a.x2, b.x2), min(a.y2, b.y2)
    inter = max(0.0, ix2 - ix1) * max(0.0, iy2 - iy1)
    union = a.area + b.area - inter
    return inter / union if union > 0 else 0.0


def duplicate(a: Box, b: Box) -> bool:
    inter = iou(a, b)
    contain = min(a.area, b.area) and (inter * max(a.area, b.area) / min(a.area, b.area))
    acx, acy = (a.x1 + a.x2) / 2, (a.y1 + a.y2) / 2
    bcx, bcy = (b.x1 + b.x2) / 2, (b.y1 + b.y2) / 2
    center = math.hypot(acx - bcx, acy - bcy) / max(math.hypot(a.x2 - a.x1, a.y2 - a.y1),
                                                       math.hypot(b.x2 - b.x1, b.y2 - b.y1), 1.0)
    size = max(abs(a.x2 - a.x1) / max(abs(b.x2 - b.x1), 1.0), abs(b.x2 - b.x1) / max(abs(a.x2 - a.x1), 1.0),
               abs(a.y2 - a.y1) / max(abs(b.y2 - b.y1), 1.0), abs(b.y2 - b.y1) / max(abs(a.y2 - a.y1), 1.0)) - 1
    return inter >= .75 or contain >= .88 or center <= .12 and size <= .18


def dedup_same_label(boxes: list[Box]) -> list[Box]:
    removed: set[int] = set()
    for label in {b.label for b in boxes if b.label in {"1", "2"}}:
        keep: list[int] = []
        for i in sorted((i for i, b in enumerate(boxes) if b.label == label), key=lambda i: boxes[i].score, reverse=True):
            if any(duplicate(boxes[i], boxes[j]) for j in keep):
                removed.add(i)
            else:
                keep.append(i)
    return [b for i, b in enumerate(boxes) if i not in removed]


def pp_preprocess(rgb: np.ndarray) -> tuple[np.ndarray, tuple[int, int, float]]:
    h, w = rgb.shape[:2]
    scale = 736.0 / max(w, h)
    rw, rh = max(1, min(736, round(w * scale))), max(1, min(736, round(h * scale)))
    resized = cv2.resize(rgb, (rw, rh), interpolation=cv2.INTER_LINEAR)
    padded = np.zeros((736, 736, 3), dtype=np.uint8)
    padded[:rh, :rw] = resized
    x = padded.astype(np.float32) / 255.0
    x = (x - np.array([.485, .456, .406], np.float32)) / np.array([.229, .224, .225], np.float32)
    return np.transpose(x, (2, 0, 1))[None, ...], (rw, rh, scale)


def merge_lines(lines: list[Box]) -> list[Box]:
    if len(lines) < 2:
        return lines
    current = lines
    for _ in range(3):
        horiz = [b for b in current if b.x2 - b.x1 >= b.y2 - b.y1]
        vert = [b for b in current if b.x2 - b.x1 < b.y2 - b.y1]
        next_lines: list[Box] = []
        for group, axis in ((horiz, "h"), (vert, "v")):
            group = sorted(group, key=lambda b: ((b.y1 + b.y2) / 2, b.x1) if axis == "h" else ((b.x1 + b.x2) / 2, b.y1))
            merged: list[Box] = []
            for b in group:
                placed = False
                for i, m in enumerate(merged):
                    if axis == "h":
                        cross, mcross = (b.y1 + b.y2) / 2, (m.y1 + m.y2) / 2
                        cross_size, mcross_size = b.y2 - b.y1, m.y2 - m.y1
                        gap = max(0.0, max(b.x1, m.x1) - min(b.x2, m.x2))
                        gap_size, mgap_size = b.y2 - b.y1, m.y2 - m.y1
                    else:
                        cross, mcross = (b.x1 + b.x2) / 2, (m.x1 + m.x2) / 2
                        cross_size, mcross_size = b.x2 - b.x1, m.x2 - m.x1
                        gap = max(0.0, max(b.y1, m.y1) - min(b.y2, m.y2))
                        gap_size, mgap_size = b.y2 - b.y1, m.y2 - m.y1
                    if abs(cross - mcross) <= .6 * min(cross_size, mcross_size) and gap <= max(gap_size, mgap_size):
                        merged[i] = Box(min(m.x1, b.x1), min(m.y1, b.y1), max(m.x2, b.x2), max(m.y2, b.y2),
                                        max(m.score, b.score), "ppocr", "ppocr")
                        placed = True
                        break
                if not placed:
                    merged.append(b)
            next_lines.extend(merged)
        if len(next_lines) == len(current):
            break
        current = next_lines
    return sorted(current, key=lambda b: (b.y1, b.x1))


def pp_infer(session: ort.InferenceSession, rgb: np.ndarray) -> tuple[list[Box], dict[str, float]]:
    h, w = rgb.shape[:2]
    t0 = time.perf_counter()
    x, (rw, rh, scale) = pp_preprocess(rgb)
    t1 = time.perf_counter()
    output = session.run(None, {session.get_inputs()[0].name: x})[0]
    prob = np.asarray(output).squeeze()
    prob = prob[:rh, :rw]
    binary = (prob > .2).astype(np.uint8)
    n, labels, stats, _ = cv2.connectedComponentsWithStats(binary, 8)
    candidates: list[Box] = []
    for i in range(1, n):
        x0, y0, ww, hh, area = stats[i]
        if area < 16 or area > .5 * rw * rh:
            continue
        score = float(prob[labels == i].mean())
        if score < .45:
            continue
        candidates.append(Box(x0 / scale, y0 / scale, (x0 + ww) / scale, (y0 + hh) / scale, score, "ppocr", "ppocr"))
    lines = merge_lines(candidates)
    t2 = time.perf_counter()
    return lines, {"preprocess_ms": (t1 - t0) * 1000, "inference_postprocess_ms": (t2 - t1) * 1000,
                   "total_ms": (t2 - t0) * 1000}


def score_boxes(pred: list[Box], truth: list[Box], threshold: float = .5) -> dict[str, Any]:
    used: set[int] = set()
    matches: list[float] = []
    for p in sorted(pred, key=lambda b: b.score, reverse=True):
        choices = [(iou(p, t), i) for i, t in enumerate(truth) if i not in used]
        if not choices:
            continue
        best, idx = max(choices)
        if best >= threshold:
            used.add(idx)
            matches.append(best)
    tp = len(matches)
    return {"tp": tp, "fp": max(0, len(pred) - tp), "fn": max(0, len(truth) - tp),
            "precision": tp / len(pred) if pred else None, "recall": tp / len(truth) if truth else None,
            "mean_iou_matched": sum(matches) / len(matches) if matches else None}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--corpus", type=Path, action="append", help="page root; may be repeated")
    ap.add_argument("--ctd-model", type=Path, default=CTD_DEFAULT)
    ap.add_argument("--pp-model", type=Path, default=PP_DEFAULT)
    ap.add_argument("--manifest", type=Path, default=ROOT / "research/dataset/manifest.json")
    ap.add_argument("--out", type=Path, default=ROOT / "research/results/ctd_vs_ppocr")
    ap.add_argument("--max-pages", type=int, default=0)
    args = ap.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)

    requested = args.corpus or DEFAULT_EXTERNAL
    roots = [p for p in requested if p.is_dir()]
    missing = [{"path": str(p), "status": "MISSING"} for p in requested if not p.is_dir()]
    pages = discover_manifest(args.manifest) if args.manifest.is_file() else []
    manifest_status = "USED" if pages else ("MISSING" if not args.manifest.is_file() else "EMPTY_OR_UNREADABLE")
    if not roots and not pages:
        sibling = ROOT.parent / "research-mangaocr-acceleration" / "tools/aot_corpus"
        if sibling.is_dir():
            roots = [sibling]
    if not pages:
        pages = [page for root in roots for page in discover_pages(root)]
    pages = pages[:args.max_pages] if args.max_pages else pages
    inventory = {"python": sys.version, "platform": platform.platform(), "onnxruntime": ort.__version__,
                 "opencv": cv2.__version__, "numpy": np.__version__, "psutil": getattr(psutil, "__version__", None),
                 "providers": ort.get_available_providers(), "ctd_model": model_inventory(args.ctd_model),
                 "ppocr_det_model": model_inventory(args.pp_model)}
    ctd = ort.InferenceSession(str(args.ctd_model), providers=["CPUExecutionProvider"])
    pp = ort.InferenceSession(str(args.pp_model), providers=["CPUExecutionProvider"])
    rows: list[dict[str, Any]] = []
    ctd_rss = rss_bytes() or 0
    pp_rss = rss_bytes() or 0
    for page in pages:
        try:
            rgb = load_rgb(page.image)
            truth, truth_protocol = read_ground_truth(page)
            before = rss_bytes()
            ctd_raw, ctd_dedup, ctd_timing = ctd_infer(ctd, rgb)
            ctd_rss = max(ctd_rss, rss_bytes() or 0)
            pp_lines, pp_timing = pp_infer(pp, rgb)
            pp_rss = max(pp_rss, rss_bytes() or 0)
            row = {"page_id": page.page_id, "image": str(page.image), "category": page.category,
                   "protocol": page.protocol, "truth_protocol": truth_protocol, "width": int(rgb.shape[1]),
                   "height": int(rgb.shape[0]), "truth_boxes": len(truth), "ctd_raw_boxes": len(ctd_raw),
                   "ctd_dedup_boxes": len(ctd_dedup), "ppocr_boxes": len(pp_lines),
                   "ctd_metrics": score_boxes(ctd_dedup, truth), "ppocr_metrics": score_boxes(pp_lines, truth),
                   "ctd_timing": ctd_timing, "ppocr_timing": pp_timing, "rss_before": before,
                   "rss_after": rss_bytes()}
            (args.out / (page.page_id.replace("/", "__") + ".json")).write_text(json.dumps({"page": row,
                "ctd_boxes": [asdict(b) for b in ctd_dedup], "ppocr_boxes": [asdict(b) for b in pp_lines],
                "truth_boxes": [asdict(b) for b in truth]}, indent=2), encoding="utf-8")
            rows.append(row)
        except Exception as exc:  # retain failures as evidence, do not hide a bad page
            rows.append({"page_id": page.page_id, "image": str(page.image), "status": "FAILED",
                         "error": f"{type(exc).__name__}: {exc}"})
    summary = {"status": "OK" if rows else "BLOCKED", "missing_requested_roots": missing,
               "manifest": {"path": str(args.manifest), "status": manifest_status},
               "roots_used": [str(p) for p in roots], "page_count": len(rows), "pages_with_results": sum("status" not in r for r in rows),
               "inventory": inventory, "peak_rss_bytes": {"ctd": ctd_rss, "ppocr": pp_rss},
               "protocols": sorted({r.get("truth_protocol") for r in rows if r.get("truth_protocol")}),
               "notes": ["Fixed manifest pages were used; source images were external to the worktree." if pages and manifest_status == "USED" else "External fixed multi-chapter roots were unavailable; fallback is weak-mask AOT crops." if missing else ""]}
    (args.out / "summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    with (args.out / "per_page.csv").open("w", newline="", encoding="utf-8") as f:
        fields = ["page_id", "image", "category", "protocol", "truth_protocol", "width", "height", "truth_boxes",
                  "ctd_raw_boxes", "ctd_dedup_boxes", "ppocr_boxes", "ctd_timing", "ppocr_timing", "ctd_metrics", "ppocr_metrics", "status", "error"]
        writer = csv.DictWriter(f, fieldnames=fields, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(rows)
    return 0 if rows else 2


if __name__ == "__main__":
    raise SystemExit(main())
