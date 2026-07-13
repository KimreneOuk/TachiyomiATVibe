"""Generate production-faithful, independently-addressable AOT free-text samples.

For every detector-v4 free-text detection that is not associated with a bubble,
this tool runs Paddle DET on that detection's 12-pixel-padded ROI. It applies the
same connected-component filtering and fragment merge as ``DbPostProcess.kt``,
then creates the same independent 512 crop and fixed-pill mask used by
``AOTInpainting.refineFreeTextGroups`` and ``inpaintReportFreeTextAot512``.

There is deliberately no detector-box fallback. A failed or empty Paddle ROI is
written to ``generation_report.json`` and makes the command fail. Outputs use
stable identities ``real_NNN__ft_NNN`` so two detections from one source page
cannot overwrite one another.
"""
from __future__ import annotations

import argparse
import json
import shutil
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import cv2
import numpy as np
import onnxruntime as ort
from PIL import Image

REPO_ROOT = Path(__file__).resolve().parents[2]
DETECTOR_MODEL = REPO_ROOT / "app/src/main/assets/models/detection/detector-v4-s_int8.onnx"
PADDLE_DET_MODEL = REPO_ROOT / "app/src/main/assets/models/ocr/paddle-v6-small/det/inference.onnx"

DET_INPUT_SIZE = 640
DET_CONFIDENCE = 0.45
DET_DEDUP_LABELS = {1, 2}
DET_DEDUP_THRESHOLDS = (0.75, 0.88, 0.12, 0.18)
BUBBLE_LABEL = 0
FREE_TEXT_LABEL = 2
PADDLE_THRESH = 0.18
PADDLE_BOX_THRESH = 0.34
PADDLE_TARGET = 736
PADDLE_CROP_PAD = 12
DB_MIN_AREA_PX = 16
DB_MAX_COMPONENT_AREA_FRAC = 0.5
DB_SAME_LINE_FRAC = 0.6
DB_MERGE_GAP_FACTOR = 1.0
DB_MERGE_MAX_PASSES = 3
DB_MAX_CANDIDATES = 3000
REPORT_AOT_CONTEXT = 512
REPORT_FREE_TEXT_PAD = 16
REPORT_FREE_TEXT_DILATE = 8


@dataclass(frozen=True)
class TextLine:
    bbox: tuple[int, int, int, int]
    score: float


def load_session(path: Path) -> ort.InferenceSession:
    if not path.is_file():
        raise FileNotFoundError(f"model not found: {path}")
    options = ort.SessionOptions()
    options.log_severity_level = 3
    return ort.InferenceSession(str(path), options, providers=["CPUExecutionProvider"])


def bbox_area(box: tuple[int, ...] | list[int]) -> int:
    return max(0, box[2] - box[0]) * max(0, box[3] - box[1])


def intersection_area(a: tuple[int, ...] | list[int], b: tuple[int, ...] | list[int]) -> int:
    width = min(a[2], b[2]) - max(a[0], b[0])
    height = min(a[3], b[3]) - max(a[1], b[1])
    return max(0, width) * max(0, height)


def iou(a: tuple[int, ...] | list[int], b: tuple[int, ...] | list[int]) -> float:
    intersection = intersection_area(a, b)
    union = bbox_area(a) + bbox_area(b) - intersection
    return intersection / union if union > 0 else 0.0


def is_geometric_duplicate(a: list[int], b: list[int]) -> bool:
    threshold_iou, threshold_containment, threshold_center, threshold_size = DET_DEDUP_THRESHOLDS
    if iou(a, b) > threshold_iou:
        return True
    minimum_area = min(bbox_area(a), bbox_area(b))
    if minimum_area > 0 and intersection_area(a, b) / minimum_area > threshold_containment:
        return True
    aw, ah = max(1, a[2] - a[0]), max(1, a[3] - a[1])
    bw, bh = max(1, b[2] - b[0]), max(1, b[3] - b[1])
    center_dx = abs((a[0] + a[2]) - (b[0] + b[2])) / 2.0
    center_dy = abs((a[1] + a[3]) - (b[1] + b[3])) / 2.0
    return (
        center_dx <= threshold_center * min(aw, bw)
        and center_dy <= threshold_center * min(ah, bh)
        and abs(aw - bw) <= threshold_size * max(aw, bw)
        and abs(ah - bh) <= threshold_size * max(ah, bh)
    )


def detector_v4_detect(session: ort.InferenceSession, image_bgr: np.ndarray) -> list[tuple[list[int], int, float]]:
    height, width = image_bgr.shape[:2]
    resized = cv2.resize(cv2.cvtColor(image_bgr, cv2.COLOR_BGR2RGB), (DET_INPUT_SIZE, DET_INPUT_SIZE))
    images = np.ascontiguousarray((resized.astype(np.float32) / 255.0).transpose(2, 0, 1)[None])
    target_sizes = np.array([[width, height]], dtype=np.int64)
    labels_out, boxes_out, scores_out = session.run(
        None,
        {"images": images, "orig_target_sizes": target_sizes},
    )
    detections: list[tuple[list[int], int, float]] = []
    for label, box, score in zip(labels_out[0], boxes_out[0], scores_out[0]):
        if not np.isfinite(score) or score < DET_CONFIDENCE:
            continue
        values = [int(value) for value in box]
        values = [
            max(0, min(width, values[0])),
            max(0, min(height, values[1])),
            max(0, min(width, values[2])),
            max(0, min(height, values[3])),
        ]
        if values[2] > values[0] and values[3] > values[1]:
            detections.append((values, int(label), float(score)))
    return deduplicate_detections(detections)


def deduplicate_detections(detections: list[tuple[list[int], int, float]]) -> list[tuple[list[int], int, float]]:
    kept: list[tuple[list[int], int, float]] = []
    for label in sorted(DET_DEDUP_LABELS):
        ranked = sorted(
            (item for item in detections if item[1] == label),
            key=lambda item: (-item[2], item[0]),
        )
        label_kept: list[tuple[list[int], int, float]] = []
        for detection in ranked:
            if not any(is_geometric_duplicate(detection[0], existing[0]) for existing in label_kept):
                label_kept.append(detection)
        kept.extend(label_kept)
    others = sorted(
        (item for item in detections if item[1] not in DET_DEDUP_LABELS),
        key=lambda item: (item[1], -item[2], item[0]),
    )
    return others + kept


def find_parent_bubble(box: list[int], bubbles: list[list[int]]) -> list[int] | None:
    center_x = (box[0] + box[2]) / 2.0
    center_y = (box[1] + box[3]) / 2.0
    containing = [
        bubble
        for bubble in bubbles
        if bubble[0] <= center_x <= bubble[2] and bubble[1] <= center_y <= bubble[3]
    ]
    return min(containing, key=bbox_area) if containing else None


def overlaps_any_bubble(box: list[int], bubbles: list[list[int]], minimum_fraction: float = 0.12) -> bool:
    area = max(1, bbox_area(box))
    return any(intersection_area(box, bubble) / area >= minimum_fraction for bubble in bubbles)


def kotlin_round_to_int(value: float) -> int:
    """Mirror Kotlin/JVM roundToInt: ties toward positive infinity."""
    if not np.isfinite(value):
        raise ValueError(f"cannot round non-finite value: {value}")
    return int(np.floor(value + 0.5))


def paddle_resized_dimensions(width: int, height: int) -> tuple[int, int]:
    if width <= 0 or height <= 0:
        raise ValueError(f"invalid crop dimensions: {width}x{height}")
    # Kotlin computes this as Float, not Double. Keep every operation float32 so
    # values near a .5 boundary choose the same output pixel as roundToInt().
    scale = np.float32(PADDLE_TARGET) / np.float32(max(width, height))
    resized_width = max(1, min(PADDLE_TARGET, kotlin_round_to_int(float(np.float32(width) * scale))))
    resized_height = max(1, min(PADDLE_TARGET, kotlin_round_to_int(float(np.float32(height) * scale))))
    return resized_width, resized_height


def paddle_preprocess(crop_bgr: np.ndarray) -> tuple[np.ndarray, int, int, float, float]:
    height, width = crop_bgr.shape[:2]
    resized_width, resized_height = paddle_resized_dimensions(width, height)
    resized = cv2.resize(cv2.cvtColor(crop_bgr, cv2.COLOR_BGR2RGB), (resized_width, resized_height))
    padded = np.zeros((PADDLE_TARGET, PADDLE_TARGET, 3), dtype=np.uint8)
    padded[:resized_height, :resized_width] = resized
    array = padded.astype(np.float32) / np.float32(255.0)
    array = (array - np.asarray([0.485, 0.456, 0.406], dtype=np.float32)) / np.asarray(
        [0.229, 0.224, 0.225], dtype=np.float32
    )
    tensor = np.ascontiguousarray(array.transpose(2, 0, 1)[None], dtype=np.float32)
    return tensor, resized_width, resized_height, resized_width / width, resized_height / height


def merge_line_fragments(lines: list[TextLine]) -> list[TextLine]:
    """Exact Python mirror of DbPostProcess.mergeLineFragments."""
    current = list(lines)
    for _ in range(DB_MERGE_MAX_PASSES):
        horizontal = [line for line in current if line.bbox[2] - line.bbox[0] >= line.bbox[3] - line.bbox[1]]
        vertical = [line for line in current if line.bbox[2] - line.bbox[0] < line.bbox[3] - line.bbox[1]]
        following = _merge_along_axis(horizontal, horizontal_axis=True) + _merge_along_axis(
            vertical, horizontal_axis=False
        )
        if len(following) == len(current):
            break
        current = following
    return sorted(current, key=lambda line: (line.bbox[1], line.bbox[0]))


def _merge_along_axis(lines: list[TextLine], horizontal_axis: bool) -> list[TextLine]:
    if horizontal_axis:
        ordered = sorted(lines, key=lambda line: ((line.bbox[1] + line.bbox[3]) // 2, line.bbox[0]))
    else:
        ordered = sorted(lines, key=lambda line: ((line.bbox[0] + line.bbox[2]) // 2, line.bbox[1]))
    merged: list[list[float]] = []
    for line in ordered:
        x1, y1, x2, y2 = line.bbox
        if horizontal_axis:
            center_cross, cross_size, gap_size = (y1 + y2) / 2.0, y2 - y1, y2 - y1
        else:
            center_cross, cross_size, gap_size = (x1 + x2) / 2.0, x2 - x1, y2 - y1
        for existing in merged:
            ex1, ey1, ex2, ey2, _ = existing
            if horizontal_axis:
                existing_center = (ey1 + ey2) / 2.0
                existing_cross = ey2 - ey1
                existing_gap = ey2 - ey1
                gap = max(0.0, max(x1, ex1) - min(x2, ex2))
            else:
                existing_center = (ex1 + ex2) / 2.0
                existing_cross = ex2 - ex1
                existing_gap = ey2 - ey1
                gap = max(0.0, max(y1, ey1) - min(y2, ey2))
            same_line = abs(center_cross - existing_center) <= DB_SAME_LINE_FRAC * min(cross_size, existing_cross)
            if same_line and gap <= DB_MERGE_GAP_FACTOR * max(gap_size, existing_gap):
                existing[:] = [min(ex1, x1), min(ey1, y1), max(ex2, x2), max(ey2, y2), max(existing[4], line.score)]
                break
        else:
            merged.append([float(x1), float(y1), float(x2), float(y2), line.score])
    return [TextLine(tuple(int(value) for value in item[:4]), item[4]) for item in merged]


def db_postprocess(probability_map: np.ndarray, max_candidates: int = DB_MAX_CANDIDATES) -> list[TextLine]:
    """Mirror DbPostProcess.detectLines, including cap, float sum, 8-CC, and merge."""
    if max_candidates <= 0:
        return []
    probability_map = np.asarray(probability_map, dtype=np.float32)
    height, width = probability_map.shape
    binary = probability_map > np.float32(PADDLE_THRESH)
    component_count, labels = cv2.connectedComponents(binary.astype(np.uint8), connectivity=8)
    lines: list[TextLine] = []
    retained_candidates = 0
    for label in range(1, component_count):
        ys, xs = np.where(labels == label)
        pixel_count = len(xs)
        if pixel_count < DB_MIN_AREA_PX:
            continue
        retained_candidates += 1
        # Kotlin's Component.probSum is a Float accumulated in flood traversal
        # order. NumPy's float32 reduction is the closest practical mirror while
        # avoiding the materially different float64 accumulation used previously.
        component_values = probability_map[labels == label]
        probability_sum = np.sum(component_values, dtype=np.float32)
        mean_score = float(np.float32(probability_sum / np.float32(pixel_count)))
        if mean_score >= PADDLE_BOX_THRESH:
            x1, x2, y1, y2 = int(xs.min()), int(xs.max()), int(ys.min()), int(ys.max())
            area = (x2 - x1 + 1) * (y2 - y1 + 1)
            if area <= int(DB_MAX_COMPONENT_AREA_FRAC * width * height):
                lines.append(TextLine((x1, y1, x2, y2), mean_score))
        if retained_candidates >= max_candidates:
            break
    return merge_line_fragments(lines)


def paddle_detect_roi(session: ort.InferenceSession, crop_bgr: np.ndarray) -> list[TextLine]:
    height, width = crop_bgr.shape[:2]
    tensor, resized_width, resized_height, crop_to_map_x, crop_to_map_y = paddle_preprocess(crop_bgr)
    output = session.run(None, {session.get_inputs()[0].name: tensor})[0]
    active_map = output[0, 0, :resized_height, :resized_width]
    map_lines = db_postprocess(active_map)
    lines: list[TextLine] = []
    for line in map_lines:
        x1, y1, x2, y2 = line.bbox
        projected = (
            max(0, min(width - 1, int(x1 / crop_to_map_x))),
            max(0, min(height - 1, int(y1 / crop_to_map_y))),
            max(0, min(width - 1, int(x2 / crop_to_map_x))),
            max(0, min(height - 1, int(y2 / crop_to_map_y))),
        )
        if projected[2] > projected[0] and projected[3] > projected[1]:
            lines.append(TextLine(projected, line.score))
    return lines


def centered_report_crop(boxes: list[tuple[int, int, int, int]], width: int, height: int) -> tuple[int, int, int, int]:
    side = min(REPORT_AOT_CONTEXT, width, height)
    union = (
        min(box[0] for box in boxes),
        min(box[1] for box in boxes),
        max(box[2] for box in boxes),
        max(box[3] for box in boxes),
    )
    center_x = kotlin_round_to_int((union[0] + union[2]) / 2.0)
    center_y = kotlin_round_to_int((union[1] + union[3]) / 2.0)
    x1 = max(0, min(center_x - side // 2, width - side))
    y1 = max(0, min(center_y - side // 2, height - side))
    return x1, y1, x1 + side, y1 + side


def build_fixed_pill_mask(boxes: list[tuple[int, int, int, int]], width: int, height: int) -> np.ndarray:
    mask = np.zeros((height, width), dtype=np.uint8)
    for x1, y1, x2, y2 in boxes:
        left = max(0, min(width, x1 - REPORT_FREE_TEXT_PAD))
        top = max(0, min(height, y1 - REPORT_FREE_TEXT_PAD))
        right = max(0, min(width, x2 + REPORT_FREE_TEXT_PAD))
        bottom = max(0, min(height, y2 + REPORT_FREE_TEXT_PAD))
        if right > left and bottom > top:
            mask[top:bottom, left:right] = 1
    radius = REPORT_FREE_TEXT_DILATE
    kernel_size = radius * 2 + 1
    yy, xx = np.ogrid[-radius : radius + 1, -radius : radius + 1]
    kernel = ((xx * xx + yy * yy) <= radius * radius).astype(np.uint8)
    return cv2.dilate(mask, kernel, iterations=1)


def write_sample(
    out_dir: Path,
    sample_id: str,
    image_bgr: np.ndarray,
    source_file: Path,
    source_page: str,
    detector_index: int,
    detector_box: list[int],
    detector_score: float,
    roi: tuple[int, int, int, int],
    page_lines: list[TextLine],
) -> dict[str, Any]:
    height, width = image_bgr.shape[:2]
    boxes = [line.bbox for line in page_lines]
    crop = centered_report_crop(boxes, width, height)
    cx1, cy1, cx2, cy2 = crop
    local_boxes = [
        (max(0, x1 - cx1), max(0, y1 - cy1), min(cx2 - cx1, x2 - cx1), min(cy2 - cy1, y2 - cy1))
        for x1, y1, x2, y2 in boxes
    ]
    local_boxes = [box for box in local_boxes if box[2] > box[0] and box[3] > box[1]]
    if not local_boxes:
        raise ValueError("Paddle lines do not intersect the independent 512 crop")
    mask = build_fixed_pill_mask(local_boxes, cx2 - cx1, cy2 - cy1)
    if int(mask.sum()) < 16:
        raise ValueError(f"mask has only {int(mask.sum())} erased pixels")
    sample_dir = out_dir / sample_id
    sample_dir.mkdir(parents=True, exist_ok=False)
    if not cv2.imwrite(str(sample_dir / "page.jpg"), image_bgr[cy1:cy2, cx1:cx2], [cv2.IMWRITE_JPEG_QUALITY, 92]):
        raise OSError("failed to write page.jpg")
    Image.fromarray(mask * 255, mode="L").save(sample_dir / "mask.png")
    manifest = {
        "identity": sample_id,
        "page": sample_id,
        "source_page": source_page,
        "source_file": source_file.name,
        "source_dims": [width, height],
        "category": "free_text_aot512",
        "inpaint_path": "inpaintReportFreeTextAot512",
        "detector_index": detector_index,
        "detector_box": detector_box,
        "detector_score": round(detector_score, 8),
        "paddle_roi": list(roi),
        "paddle_lines": [list(line.bbox) for line in page_lines],
        "paddle_line_count": len(page_lines),
        "crop": list(crop),
        "crop_dims": [cx2 - cx1, cy2 - cy1],
        "mask_pad": REPORT_FREE_TEXT_PAD,
        "mask_dilate": REPORT_FREE_TEXT_DILATE,
        "generator": "generate_masks_faithful.py",
        "fallback_used": False,
    }
    (sample_dir / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    return manifest


def source_page_id(path: Path, prefix: str) -> str:
    digits = "".join(character for character in path.stem if character.isdigit())
    if not digits:
        raise ValueError(f"source filename has no numeric page identity: {path.name}")
    return f"{prefix}{digits[-3:].zfill(3)}"


def selected_source_files(src_dir: Path, pages: str) -> list[Path]:
    files = sorted(src_dir.glob("page-*.jpg"))
    if not files:
        files = sorted(src_dir.glob("*.jpg"))
    if pages:
        wanted = {item.strip().zfill(3) for item in pages.split(",") if item.strip()}
        files = [path for path in files if source_page_id(path, "").zfill(3) in wanted]
    return files


def generate(args: argparse.Namespace) -> tuple[list[dict[str, Any]], list[dict[str, Any]], list[dict[str, Any]]]:
    src_dir = Path(args.src).resolve()
    out_dir = Path(args.out).resolve()
    if not src_dir.is_dir():
        raise FileNotFoundError(f"source directory not found: {src_dir}")
    files = selected_source_files(src_dir, args.pages)
    if not files:
        raise ValueError(f"no selected JPG pages in {src_dir}")
    if out_dir.exists():
        if not args.clean:
            raise FileExistsError(f"output already exists (pass --clean for staging regeneration): {out_dir}")
        shutil.rmtree(out_dir)
    out_dir.mkdir(parents=True)

    detector_session = load_session(DETECTOR_MODEL)
    paddle_session = load_session(PADDLE_DET_MODEL)
    samples: list[dict[str, Any]] = []
    failures: list[dict[str, Any]] = []
    pages: list[dict[str, Any]] = []
    for page_number, source_file in enumerate(files, 1):
        page_id = source_page_id(source_file, args.prefix)
        print(f"[{page_number}/{len(files)}] {source_file.name} -> {page_id}")
        image = cv2.imread(str(source_file), cv2.IMREAD_COLOR)
        if image is None:
            failure = {"source_page": page_id, "source_file": source_file.name, "stage": "read", "error": "OpenCV could not decode image"}
            failures.append(failure)
            print(f"  FAIL {json.dumps(failure, sort_keys=True)}", file=sys.stderr)
            continue
        height, width = image.shape[:2]
        try:
            detections = detector_v4_detect(detector_session, image)
        except Exception as error:  # every page-level inference failure is retained in the report
            failure = {"source_page": page_id, "source_file": source_file.name, "stage": "detector_v4", "error": repr(error)}
            failures.append(failure)
            print(f"  FAIL {json.dumps(failure, sort_keys=True)}", file=sys.stderr)
            continue
        bubbles = [box for box, label, _ in detections if label == BUBBLE_LABEL]
        free_text = [
            (box, score)
            for box, label, score in detections
            if label == FREE_TEXT_LABEL and find_parent_bubble(box, bubbles) is None and not overlaps_any_bubble(box, bubbles)
        ]
        pages.append({"source_page": page_id, "source_file": source_file.name, "detector_count": len(detections), "bubble_count": len(bubbles), "free_text_count": len(free_text)})
        for detector_index, (detector_box, detector_score) in enumerate(free_text, 1):
            sample_id = f"{page_id}__ft_{detector_index:03d}"
            roi = (
                max(0, detector_box[0] - PADDLE_CROP_PAD),
                max(0, detector_box[1] - PADDLE_CROP_PAD),
                min(width, detector_box[2] + PADDLE_CROP_PAD),
                min(height, detector_box[3] + PADDLE_CROP_PAD),
            )
            try:
                if roi[2] <= roi[0] or roi[3] <= roi[1]:
                    raise ValueError(f"invalid padded ROI {roi}")
                roi_lines = paddle_detect_roi(paddle_session, image[roi[1] : roi[3], roi[0] : roi[2]])
                if not roi_lines:
                    raise ValueError("Paddle DET returned no valid lines; detector fallback is forbidden")
                page_lines = [
                    TextLine(
                        (roi[0] + line.bbox[0], roi[1] + line.bbox[1], roi[0] + line.bbox[2], roi[1] + line.bbox[3]),
                        line.score,
                    )
                    for line in roi_lines
                ]
                manifest = write_sample(
                    out_dir,
                    sample_id,
                    image,
                    source_file,
                    page_id,
                    detector_index,
                    detector_box,
                    detector_score,
                    roi,
                    page_lines,
                )
                samples.append(manifest)
                print(f"  OK {sample_id}: paddleLines={len(page_lines)} mask/crop independent")
            except Exception as error:
                failure = {
                    "identity": sample_id,
                    "source_page": page_id,
                    "source_file": source_file.name,
                    "stage": "paddle_roi_or_emit",
                    "detector_box": detector_box,
                    "paddle_roi": list(roi),
                    "error": repr(error),
                }
                failures.append(failure)
                print(f"  FAIL {json.dumps(failure, sort_keys=True)}", file=sys.stderr)

    report = {
        "generator": "generate_masks_faithful.py",
        "source": src_dir.name,
        "selected_page_count": len(files),
        "sample_count": len(samples),
        "failure_count": len(failures),
        "minimum_samples": args.min_samples,
        "pages": pages,
        "samples": [sample["identity"] for sample in samples],
        "failures": failures,
    }
    (out_dir / "generation_report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    return samples, failures, pages


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--src", required=True, help="chapter directory containing page-NNN.jpg")
    parser.add_argument("--out", required=True, help="new staging output directory")
    parser.add_argument("--pages", default="", help="optional comma-separated source page numbers")
    parser.add_argument("--prefix", default="real_", help="stable source identity prefix")
    parser.add_argument("--min-samples", type=int, default=20, help="fail unless at least this many samples are emitted")
    parser.add_argument("--clean", action="store_true", help="remove an existing staging output before generation")
    return parser


def write_startup_failure_report(args: argparse.Namespace, error: Exception) -> None:
    """Persist startup/source/model failures whenever the requested output is writable."""
    out_dir = Path(args.out).resolve()
    try:
        if out_dir.exists() and not out_dir.is_dir():
            return
        if out_dir.exists() and any(out_dir.iterdir()) and not args.clean:
            return
        out_dir.mkdir(parents=True, exist_ok=True)
        failure = {"stage": "startup", "error": repr(error)}
        report = {
            "generator": "generate_masks_faithful.py",
            "source": str(Path(args.src).resolve()),
            "selected_page_count": 0,
            "sample_count": 0,
            "failure_count": 1,
            "minimum_samples": args.min_samples,
            "pages": [],
            "samples": [],
            "failures": [failure],
        }
        (out_dir / "generation_report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    except OSError as report_error:
        print(f"ERROR: could not write generation failure report: {report_error}", file=sys.stderr)


def main() -> int:
    args = build_parser().parse_args()
    try:
        samples, failures, pages = generate(args)
    except Exception as error:
        write_startup_failure_report(args, error)
        print(f"ERROR: {error}", file=sys.stderr)
        return 1
    source_pages = {sample["source_page"] for sample in samples}
    print(f"Done: samples={len(samples)} sourcePages={len(source_pages)} failures={len(failures)} out={Path(args.out).resolve()}")
    if failures:
        print("ERROR: generation had explicit failures; see generation_report.json", file=sys.stderr)
        return 1
    if len(samples) < args.min_samples:
        print(f"ERROR: emitted {len(samples)} samples, minimum is {args.min_samples}", file=sys.stderr)
        return 1
    if len(source_pages) < 2:
        print("ERROR: corpus must cover multiple source pages", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
