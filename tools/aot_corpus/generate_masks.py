"""
Generate faithful inpaint masks for the Tier 3 corpus by running the app's REAL
detection + mask-construction pipeline offline in Python.

This is the Option-B mask path (tools/aot_corpus/README.md). It mirrors prod
faithfully instead of inventing geometry:

  1. PaddleOCR PP-OCRv6 small DET model  -> text-line boxes
     (same model, same DB postprocess, same inpaint thresholds 0.18/0.34 as
      AOTInpainting.kt:33-34 / PaddleOcrV6DetEngine.kt:99-102)
  2. AotBoxGeometry.centeredReportCrop   -> 512^2 crop centered on box union
     (AotBoxGeometry.kt:26-38, contextSize=512 from AOTInpainting.kt:38)
  3. BubbleMaskBuilder.buildFixedPillMask -> fixed-pill erase mask
     (BubbleMaskBuilder.kt:355-364, pad=16, dilateRadius=8 from AOTInpainting.kt:36-37)

Outputs per page (written into <out>/<page_name>/):
  page.jpg       the centered 512^2 crop of the source (the region the AOT model
                 actually sees in inpaintReportFreeTextAot512). Cropped from the
                 source, NOT resized - the crop is already 512^2 when the source
                 is >= 512 on its min side (true for all real manga pages here).
  mask.png       white = erase, black = keep. Same 512^2 crop geometry as page.jpg.
  manifest.json  page metadata (category, expected_box_count, source page).
  boxes.json     detected boxes in crop-local coords (diagnostic only).

For sub-512 pages, the centered crop side = min(512, min(w,h)); page.jpg stores
that smaller crop as-is and emit_corpus_outputs.py pads/resizes to the model's
exact tensor dims downstream.

Usage:
  python tools/aot_corpus/generate_masks.py \\
      --src "<source chapter dir>" \\
      --out tools/aot_corpus/real_corpus \\
      --pages 001,007,014,019,024  # optional: comma list of page numbers

Category assignment is NOT done here - run assign_categories.py (or fill
manifests by hand) after masks are generated.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import cv2
import numpy as np
import onnxruntime as ort
from PIL import Image

REPO_ROOT = Path(__file__).resolve().parents[2]
DET_MODEL = REPO_ROOT / "app/src/main/assets/models/ocr/paddle-v6-small/det/inference.onnx"

# --- Prod constants (MUST match AOTInpainting.kt / PaddleOcrV6DetEngine.kt) ---
PADDLE_THRESH = 0.18          # AOTInpainting.kt:33 (inpaint path, not OCR-rec 0.2)
PADDLE_BOX_THRESH = 0.34      # AOTInpainting.kt:34 (inpaint path, not OCR-rec 0.45)
DET_TARGET = 736              # PaddleOcrV6DetEngine.kt:294
DET_MEAN = (0.485, 0.456, 0.406)   # R, G, B  (PaddleOcrV6DetEngine.kt:298-299)
DET_STD = (0.229, 0.224, 0.225)
DB_MAX_CANDIDATES = 3000      # DbPostProcess.Defaults.MAX_CANDIDATES
DB_MIN_AREA_PX = 16           # DbPostProcess.MIN_AREA_PX
DB_MAX_AREA_FRAC = 0.5        # DbPostProcess.MAX_COMPONENT_AREA_FRAC
DB_SAME_LINE_FRAC = 0.6       # DbPostProcess.SAME_LINE_FRAC
DB_MERGE_GAP_FACTOR = 1.0     # DbPostProcess.MERGE_GAP_FACTOR
DB_MERGE_MAX_PASSES = 3       # DbPostProcess.MERGE_MAX_PASSES

REPORT_AOT_CONTEXT = 512      # AOTInpainting.kt:38 (the crop side)
REPORT_FREE_TEXT_PAD = 16     # AOTInpainting.kt:36
REPORT_FREE_TEXT_DILATE = 8   # AOTInpainting.kt:37


# ============ PaddleOCR det inference (mirrors PaddleOcrV6DetEngine) ============

def load_det_model(path: Path) -> ort.InferenceSession:
    if not path.exists():
        print(f"ERROR: det model not found: {path}", file=sys.stderr)
        sys.exit(1)
    so = ort.SessionOptions()
    so.log_severity_level = 3
    return ort.InferenceSession(str(path), so, providers=["CPUExecutionProvider"])


def det_preprocess(crop: np.ndarray) -> tuple[np.ndarray, float, float, int, int]:
    """Mirror PaddleOcrV6DetEngine.preprocess: resize longer side to 736 keeping
    aspect, pad to 736x736 with black, ImageNet normalize, NCHW.

    Returns (tensor[1,3,736,736], cropToMapX, cropToMapY, resizedW, resizedH).
    """
    h, w = crop.shape[:2]
    scale = DET_TARGET / float(max(w, h))
    rw = max(1, min(DET_TARGET, round(w * scale)))
    rh = max(1, min(DET_TARGET, round(h * scale)))
    # PIL bilinear resize (PaddleOcrV6DetEngine uses FILTER_BITMAP_FLAG = bilinear)
    img = Image.fromarray(cv2.cvtColor(crop, cv2.COLOR_BGR2RGB))
    resized = img.resize((rw, rh), Image.BILINEAR)
    padded = Image.new("RGB", (DET_TARGET, DET_TARGET), (0, 0, 0))
    padded.paste(resized, (0, 0))
    arr = np.asarray(padded, dtype=np.float32) / np.float32(255.0)
    arr = (arr - np.asarray(DET_MEAN, dtype=np.float32)) / np.asarray(DET_STD, dtype=np.float32)
    chw = np.ascontiguousarray(arr.transpose(2, 0, 1)[None, ...], dtype=np.float32)  # 1,3,736,736
    cropToMapX = rw / float(w)
    cropToMapY = rh / float(h)
    return np.ascontiguousarray(chw), cropToMapX, cropToMapY, rw, rh


def run_det(sess: ort.InferenceSession, crop: np.ndarray) -> np.ndarray:
    """Run det, return prob map cropped to the active (pre-pad) region."""
    tensor, ctmx, ctmy, rw, rh = det_preprocess(crop)
    input_name = sess.get_inputs()[0].name
    out = sess.run(None, {input_name: tensor})[0]  # 1,1,736,736
    prob = out[0, 0]  # 736,736
    # activeRegion: crop to [:rh, :rw] (PaddleOcrV6DetEngine.activeRegion)
    return prob[:rh, :rw], ctmx, ctmy


# ============ DB postprocess (mirrors DbPostProcess.kt) ============

def detect_lines(
    prob_map: np.ndarray,
    threshold: float = PADDLE_THRESH,
    box_threshold: float = PADDLE_BOX_THRESH,
    max_candidates: int = DB_MAX_CANDIDATES,
) -> list[tuple[int, int, int, int, float]]:
    """DB postprocess. Returns list of (x1,y1,x2,y2,score) in map coords.

    Mirrors DbPostProcess.detectLines + finalize + mergeLineFragments.
    """
    h, w = prob_map.shape
    binary = prob_map > threshold
    # Connected components (8-conn) via cv2
    num, labels = cv2.connectedComponents(binary.astype(np.uint8), connectivity=8)
    components = []
    for lbl in range(1, num):
        ys, xs = np.where(labels == lbl)
        if len(xs) < DB_MIN_AREA_PX:
            continue
        x1, x2 = xs.min(), xs.max()
        y1, y2 = ys.min(), ys.max()
        prob_sum = prob_map[labels == lbl].sum()
        mean_score = prob_sum / len(xs)
        area = (x2 - x1 + 1) * (y2 - y1 + 1)
        if area > DB_MAX_AREA_FRAC * w * h:
            continue
        components.append((int(x1), int(y1), int(x2), int(y2), float(mean_score)))
        if len(components) >= max_candidates:
            break
    # box_thresh filter
    components = [c for c in components if c[4] >= box_threshold]
    # Merge fragments (DbPostProcess.mergeLineFragments)
    merged = _merge_fragments([(c[0], c[1], c[2], c[3], c[4]) for c in components])
    return merged


def _merge_fragments(boxes: list[tuple[int, int, int, int, float]]) -> list[tuple[int, int, int, int, float]]:
    if len(boxes) < 2:
        return boxes
    current = boxes
    for _ in range(DB_MERGE_MAX_PASSES):
        horiz = [b for b in current if (b[2] - b[0]) >= (b[3] - b[1])]
        vert = [b for b in current if (b[2] - b[0]) < (b[3] - b[1])]
        mh = _merge_along_axis(horiz, axis="h")
        mv = _merge_along_axis(vert, axis="v")
        nxt = mh + mv
        if len(nxt) == len(current):
            break
        current = nxt
    return current


def _merge_along_axis(group, axis):
    if not group:
        return []
    if axis == "h":
        key = lambda b: ((b[1] + b[3]) / 2, b[0])
    else:
        key = lambda b: ((b[0] + b[2]) / 2, b[1])
    sorted_g = sorted(group, key=key)
    merged = []
    for b in sorted_g:
        placed = False
        for m in merged:
            if axis == "h":
                cCross = (b[1] + b[3]) / 2
                mCross = (m[1] + m[3]) / 2
                cSize = b[3] - b[1]
                mSize = m[3] - m[1]
                gap = max(0, max(b[0], m[0]) - min(b[2], m[2]))
                gapRef = b[3] - b[1]
                mGapRef = m[3] - m[1]
            else:
                cCross = (b[0] + b[2]) / 2
                mCross = (m[0] + m[2]) / 2
                cSize = b[2] - b[0]
                mSize = m[2] - m[0]
                gap = max(0, max(b[1], m[1]) - min(b[3], m[3]))
                gapRef = b[3] - b[1]
                mGapRef = m[3] - m[1]
            same_line = abs(cCross - mCross) <= DB_SAME_LINE_FRAC * min(cSize, mSize)
            if same_line and gap <= DB_MERGE_GAP_FACTOR * max(gapRef, mGapRef):
                m[0], m[1], m[2], m[3] = min(m[0], b[0]), min(m[1], b[1]), max(m[2], b[2]), max(m[3], b[3])
                m[4] = max(m[4], b[4])
                placed = True
                break
        if not placed:
            merged.append(list(b))
    return [tuple(m) for m in merged]


# ============ Crop + mask geometry (mirrors AotBoxGeometry / BubbleMaskBuilder) ============

def centered_report_crop(boxes: list[tuple], width: int, height: int, context: int) -> tuple[int, int, int, int] | None:
    """AotBoxGeometry.centeredReportCrop: 512^2 (or min-side) crop centered on box union."""
    if not boxes:
        return None
    ux1 = min(b[0] for b in boxes)
    uy1 = min(b[1] for b in boxes)
    ux2 = max(b[2] for b in boxes)
    uy2 = max(b[3] for b in boxes)
    side = min(context, min(width, height))
    cx = round((ux1 + ux2) / 2)
    cy = round((uy1 + uy2) / 2)
    x1 = max(0, min(cx - side // 2, width - side))
    y1 = max(0, min(cy - side // 2, height - side))
    return (x1, y1, x1 + side, y1 + side)


def localize_box(box, originX, originY, width, height):
    """AotBoxGeometry.localizeBox."""
    x1 = max(0, min(width, box[0] - originX))
    y1 = max(0, min(height, box[1] - originY))
    x2 = max(0, min(width, box[2] - originX))
    y2 = max(0, min(height, box[3] - originY))
    if x2 > x1 and y2 > y1:
        return (x1, y1, x2, y2)
    return None


def build_rect_mask(boxes, width, height, pad, dilate_radius=2):
    """BubbleMaskBuilder.buildRectMask: solid padded rect per box."""
    if width <= 0 or height <= 0 or not boxes:
        return np.zeros((height, width), dtype=np.uint8)
    mask = np.zeros((height, width), dtype=np.uint8)
    for b in boxes:
        x1 = max(0, min(width, b[0] - pad))
        y1 = max(0, min(height, b[1] - pad))
        x2 = max(0, min(width, b[2] + pad))
        y2 = max(0, min(height, b[3] + pad))
        if x2 <= x1 or y2 <= y1:
            continue
        mask[y1:y2, x1:x2] = 1
    if dilate_radius > 0:
        mask = dilate_disk(mask, dilate_radius)
    return mask


def dilate_disk(mask: np.ndarray, radius: int) -> np.ndarray:
    """BubbleMaskBuilder.dilateMaskDisk: isotropic disk dilation."""
    if radius <= 0:
        return mask.copy()
    ksize = 2 * radius + 1
    kernel = np.zeros((ksize, ksize), dtype=np.uint8)
    for dy in range(-radius, radius + 1):
        for dx in range(-radius, radius + 1):
            if dx * dx + dy * dy <= radius * radius:
                kernel[dy + radius, dx + radius] = 1
    return cv2.dilate(mask, kernel, iterations=1)


def build_fixed_pill_mask(boxes, width, height, pad, dilate_radius):
    """BubbleMaskBuilder.buildFixedPillMask = buildRectMask with fixed dilation."""
    return build_rect_mask(boxes, width, height, pad, dilate_radius=dilate_radius)


# ============ Main ============

def process_page(
    sess: ort.InferenceSession,
    src_path: Path,
    out_dir: Path,
    page_name: str,
    source_label: str,
) -> dict | None:
    src = cv2.imread(str(src_path), cv2.IMREAD_COLOR)
    if src is None:
        print(f"  SKIP {page_name}: cannot read {src_path}")
        return None
    h, w = src.shape[:2]

    # 1. Det (inpaint thresholds 0.18/0.34)
    prob, ctmx, ctmy = run_det(sess, src)
    map_lines = detect_lines(prob)
    # Back-project map boxes to source coords (DbPostProcess.backProject)
    src_boxes = []
    for x1, y1, x2, y2, score in map_lines:
        bx1 = max(0, min(w - 1, round(x1 / ctmx))) if ctmx > 0 else x1
        by1 = max(0, min(h - 1, round(y1 / ctmy))) if ctmy > 0 else y1
        bx2 = max(0, min(w - 1, round(x2 / ctmx))) if ctmx > 0 else x2
        by2 = max(0, min(h - 1, round(y2 / ctmy))) if ctmy > 0 else y2
        if bx2 > bx1 and by2 > by1:
            src_boxes.append((bx1, by1, bx2, by2, score))

    if not src_boxes:
        print(f"  SKIP {page_name}: no text boxes detected")
        return None

    # 2. Centered 512^2 crop on box union
    crop = centered_report_crop(src_boxes, w, h, REPORT_AOT_CONTEXT)
    if crop is None:
        print(f"  SKIP {page_name}: no crop")
        return None
    cx1, cy1, cx2, cy2 = crop
    crop_w = cx2 - cx1
    crop_h = cy2 - cy1

    # 3. Localize boxes to crop coords + build fixed-pill mask
    local_boxes = []
    for b in src_boxes:
        lb = localize_box(b, cx1, cy1, crop_w, crop_h)
        if lb is not None:
            local_boxes.append(lb)
    mask = build_fixed_pill_mask(
        local_boxes, crop_w, crop_h,
        pad=REPORT_FREE_TEXT_PAD, dilate_radius=REPORT_FREE_TEXT_DILATE,
    )
    if not mask.any():
        print(f"  SKIP {page_name}: empty mask")
        return None

    # 4. Extract the crop (page.jpg) and write mask (mask.png)
    page_crop_bgr = src[cy1:cy2, cx1:cx2]
    page_out = out_dir / page_name
    page_out.mkdir(parents=True, exist_ok=True)
    # page.jpg as RGB (emit script reads RGB)
    cv2.imwrite(str(page_out / "page.jpg"), page_crop_bgr, [cv2.IMWRITE_JPEG_QUALITY, 92])
    # mask.png: white=erase (mask==1), black=keep
    mask_png = (mask * 255).astype(np.uint8)
    Image.fromarray(mask_png, mode="L").save(page_out / "mask.png")

    manifest = {
        "page": page_name,
        "category": "",  # filled by assign step
        "expected_box_count": len(local_boxes),
        "source": source_label,
        "source_dims": [w, h],
        "crop": [cx1, cy1, cx2, cy2],
        "crop_dims": [crop_w, crop_h],
        "det_thresh": PADDLE_THRESH,
        "det_box_thresh": PADDLE_BOX_THRESH,
        "mask_pad": REPORT_FREE_TEXT_PAD,
        "mask_dilate": REPORT_FREE_TEXT_DILATE,
    }
    with open(page_out / "manifest.json", "w") as f:
        json.dump(manifest, f, indent=2)
    # boxes.json diagnostic
    with open(page_out / "boxes.json", "w") as f:
        json.dump(
            {"source_boxes": [list(b) for b in src_boxes],
             "local_boxes": [list(b) for b in local_boxes]},
            f, indent=2,
        )
    return manifest


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", required=True, help="source dir of page-NNN.jpg files")
    ap.add_argument("--out", required=True, help="output corpus dir")
    ap.add_argument("--pages", default="", help="comma list of page numbers (e.g. 001,007); default = all")
    ap.add_argument("--prefix", default="real_", help="output page name prefix")
    args = ap.parse_args()

    src_dir = Path(args.src)
    if not src_dir.is_dir():
        print(f"ERROR: src dir not found: {src_dir}", file=sys.stderr)
        return 1
    out_dir = Path(args.out)

    seen = set()
    files = []
    for f in sorted(src_dir.glob("page-*.jpg")) + sorted(src_dir.glob("*.jpg")):
        if f.name not in seen:
            seen.add(f.name)
            files.append(f)
    if args.pages:
        wanted = {p.strip().zfill(3) for p in args.pages.split(",")}
        files = [f for f in files if any(f.name.endswith(f"-{w}.jpg") or f.stem.endswith(w) for w in wanted)]
    if not files:
        print(f"ERROR: no page files in {src_dir}", file=sys.stderr)
        return 1

    print(f"Loading det model {DET_MODEL.name} ...")
    sess = load_det_model(DET_MODEL)

    out_dir.mkdir(parents=True, exist_ok=True)
    n_ok = 0
    for i, f in enumerate(files, 1):
        # page-NNN.jpg -> NNN
        stem = f.stem
        num = "".join(c for c in stem if c.isdigit())[-3:] if any(c.isdigit() for c in stem) else stem
        page_name = f"{args.prefix}{num}"
        print(f"[{i}/{len(files)}] {f.name} -> {page_name}")
        m = process_page(sess, f, out_dir, page_name, source_label=src_dir.name)
        if m is not None:
            n_ok += 1
            print(f"    boxes={m['expected_box_count']} crop={m['crop_dims']}")

    print(f"\nDone. {n_ok}/{len(files)} page(s) emitted to {out_dir}")
    return 0 if n_ok > 0 else 1


if __name__ == "__main__":
    sys.exit(main())
