"""
Faithful mask generator mirroring prod's FULL detection + routing chain.

The previous generate_masks.py ran PaddleOCR-det on the whole page and treated
every detection as free-text. That diverged from prod, which uses THREE models
and routes boxes to TWO different inpaint paths:

  1. detector-v4-s_int8.onnx  (RT-DETR) -> bubbles(0), text_bubble(1), text_free(2)
  2. best_int8.onnx           (YOLO11-seg) -> precise bubble masks (segmentationMask)
  3. paddle-v6-small det      -> refines free-text line boxes (no parent bubble)

Routing at inpaint time (AOTInpainting.inpaintRegions):
  - bubble boxes + text inside bubbles -> inpaintReportBubbles (uses seg mask)
  - free-text (label 2, no parent bubble) -> inpaintReportFreeTextAot512 (AOT model)
  - extra detector boxes (not OCR'd)      -> free-text path

This script reproduces that chain offline and emits BOTH paths per page so the
gate and QA can exercise each:

  <page>/free_text/  page.jpg, mask.png, manifest.json  (AOT-512 path input)
  <page>/bubble/     page.jpg, mask.png, manifest.json  (bubble path input, seg mask)

The free_text output is what AotCorpusGateTest consumes. The bubble output lets
qa_compare_modes.py validate the other inpaint path too.

DOCUMENTED APPROXIMATION: prod filters mask boxes by whether OCR-rec read
non-blank text (PageInpaintingPlanner.computeMask: "readable = text.isNotBlank()").
Running PaddleOCR rec faithfully (CNN+CTC + 18710-char dict) is out of scope here,
so ALL detector-v4 text detections are treated as readable. This slightly
over-erases vs prod (the rare blank-text region is erased here but preserved in
prod). Effect is minor: blank-text regions are uncommon at detector conf > 0.45.

Usage:
  python tools/aot_corpus/generate_masks_faithful.py \\
      --src "<chapter dir>" \\
      --out tools/aot_corpus/real_corpus_faithful \\
      --pages 002,008,025
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
DETECTOR_MODEL = REPO_ROOT / "app/src/main/assets/models/detection/detector-v4-s_int8.onnx"
SEGMENTER_MODEL = REPO_ROOT / "app/src/main/assets/models/segmentation/best_int8.onnx"
PADDLE_DET_MODEL = REPO_ROOT / "app/src/main/assets/models/ocr/paddle-v6-small/det/inference.onnx"

# --- prod constants (faithful mirrors) ---
# detector-v4 (OnnxPageTextDetector.kt)
DET_INPUT_SIZE = 640
DET_CONFIDENCE = 0.45
DET_DEDUP_LABELS = {1, 2}
DET_DEDUP_THRESHOLDS = (0.75, 0.88, 0.12, 0.18)  # iou, containment, center, size

# bubble segmenter (BubbleSegmentationDecoder.kt)
SEG_INPUT_SIZE = 640
SEG_PAD_COLOR = (0x72, 0x72, 0x72)
SEG_PREDICTION_CHANNELS = 37
SEG_PREDICTION_COUNT = 8400
SEG_CONFIDENCE = 0.35
SEG_NMS_IOU = 0.5
SEG_MASK_THRESHOLD = 0.5
SEG_PROTOTYPE_CHANNELS = 32
SEG_PROTOTYPE_SIZE = 160

# paddle det (PaddleOcrV6DetEngine.kt) - free-text refinement
PADDLE_THRESH = 0.18
PADDLE_BOX_THRESH = 0.34
PADDLE_TARGET = 736

# AOTInpainting routing + geometry
BUBBLE_LABEL = 0
DETECTOR_TEXT_LABEL = 2
REPORT_AOT_CONTEXT = 512
REPORT_FREE_TEXT_PAD = 16
REPORT_FREE_TEXT_DILATE = 8
REPORT_PUSH_PULL_CONTEXT = 64
DETECTOR_OVERLAP_PAD = 3
DETECTOR_OCR_IOU_THRESHOLD = 0.4


def load_session(path: Path) -> ort.InferenceSession:
    if not path.exists():
        print(f"ERROR: model not found: {path}", file=sys.stderr)
        sys.exit(1)
    so = ort.SessionOptions()
    so.log_severity_level = 3
    return ort.InferenceSession(str(path), so, providers=["CPUExecutionProvider"])


# ============ Box geometry (BoxGeometry.kt) ============

def bbox_area(b):
    return max(0, b[2] - b[0]) * max(0, b[3] - b[1])


def intersection_area(a, b):
    ix1, iy1 = max(a[0], b[0]), max(a[1], b[1])
    ix2, iy2 = min(a[2], b[2]), min(a[3], b[3])
    if ix2 <= ix1 or iy2 <= iy1:
        return 0
    return (ix2 - ix1) * (iy2 - iy1)


def iou(a, b):
    inter = intersection_area(a, b)
    if inter <= 0:
        return 0.0
    union = bbox_area(a) + bbox_area(b) - inter
    return inter / union if union > 0 else 0.0


def is_geometric_duplicate(a, b, thresholds=DET_DEDUP_THRESHOLDS):
    """BoxGeometry.isGeometricDuplicate. thresholds=(iou,containment,center,size)."""
    t_iou, t_cont, t_center, t_size = thresholds
    if iou(a, b) > t_iou:
        return True
    min_area = min(bbox_area(a), bbox_area(b))
    if min_area > 0 and intersection_area(a, b) / min_area > t_cont:
        return True
    aw, ah = max(1, a[2] - a[0]), max(1, a[3] - a[1])
    bw, bh = max(1, b[2] - b[0]), max(1, b[3] - b[1])
    cdx = abs((a[0] + a[2]) - (b[0] + b[2])) / 2.0
    cdy = abs((a[1] + a[3]) - (b[1] + b[3])) / 2.0
    return (cdx <= t_center * min(aw, bw) and cdy <= t_center * min(ah, bh)
            and abs(aw - bw) <= t_size * max(aw, bw) and abs(ah - bh) <= t_size * max(ah, bh))


# ============ Detector-v4 (OnnxPageTextDetector.kt) ============

def detector_v4_detect(sess, img_bgr):
    """RT-DETR inference. img_bgr HxWx3 uint8. Returns list of (bbox,label,score)."""
    h, w = img_bgr.shape[:2]
    img_rgb = cv2.cvtColor(img_bgr, cv2.COLOR_BGR2RGB)
    resized = cv2.resize(img_rgb, (DET_INPUT_SIZE, DET_INPUT_SIZE), interpolation=cv2.INTER_LINEAR)
    # NCHW /255 (OnnxPageTextDetector.preprocess: /255.0f)
    arr = resized.astype(np.float32) / 255.0
    chw = np.ascontiguousarray(arr.transpose(2, 0, 1)[None, ...], dtype=np.float32)
    sizes = np.array([[w, h]], dtype=np.int64)
    out = sess.run(None, {"images": chw, "orig_target_sizes": sizes})
    labels = out[0][0].astype(np.int64)
    boxes = out[1][0]
    scores = out[2][0]
    # postprocess (conf filter)
    dets = []
    for i in range(len(labels)):
        if np.isnan(scores[i]) or scores[i] < DET_CONFIDENCE:
            continue
        b = boxes[i].astype(np.int64)
        dets.append(([int(b[0]), int(b[1]), int(b[2]), int(b[3])], int(labels[i]), float(scores[i])))
    # dedupe labels {1,2} (deduplicateLabels)
    return _dedup_labels(dets)


def _dedup_labels(dets):
    kept = []
    for label in DET_DEDUP_LABELS:
        ranked = sorted([d for d in dets if d[1] == label], key=lambda d: -d[2])
        keep = []
        for d in ranked:
            if any(is_geometric_duplicate(d[0], k[0]) for k in keep):
                continue
            keep.append(d)
        kept.extend(keep)
    others = [d for d in dets if d[1] not in DET_DEDUP_LABELS]
    return others + kept


# ============ Bubble segmenter (OnnxBubbleSegmenter + BubbleSegmentationDecoder) ============

def letterbox_for(w, h):
    """BubbleSegmentationDecoder.letterboxFor."""
    ratio = min(SEG_INPUT_SIZE / w, SEG_INPUT_SIZE / h)
    pad_x = (SEG_INPUT_SIZE - int(w * ratio)) // 2
    pad_y = (SEG_INPUT_SIZE - int(h * ratio)) // 2
    return ratio, pad_x, pad_y


def segmenter_segment(sess, img_bgr):
    """YOLO11-seg. Returns list of (mask HxW bool, bounds [x1,y1,x2,y2], score)."""
    h, w = img_bgr.shape[:2]
    ratio, pad_x, pad_y = letterbox_for(w, h)
    img_rgb = cv2.cvtColor(img_bgr, cv2.COLOR_BGR2RGB)
    # letterbox with grey pad
    canvas = np.full((SEG_INPUT_SIZE, SEG_INPUT_SIZE, 3), SEG_PAD_COLOR, dtype=np.uint8)
    new_w, new_h = int(w * ratio), int(h * ratio)
    resized = cv2.resize(img_rgb, (new_w, new_h), interpolation=cv2.INTER_LINEAR)
    canvas[pad_y:pad_y + new_h, pad_x:pad_x + new_w] = resized
    arr = canvas.astype(np.float32) / 255.0
    chw = np.ascontiguousarray(arr.transpose(2, 0, 1)[None, ...], dtype=np.float32)
    out = sess.run(None, {"images": chw})
    predictions = out[0][0]   # 37, 8400
    prototypes = out[1][0]    # 32, 160, 160
    return _decode_seg(predictions, prototypes, w, h, ratio, pad_x, pad_y)


def _decode_seg(predictions, prototypes, page_w, page_h, ratio, pad_x, pad_y):
    """BubbleSegmentationDecoder.decode + reconstructMask (vectorized)."""
    n = SEG_PREDICTION_COUNT
    scores = predictions[4]  # confidence channel
    candidates = []
    for i in range(n):
        s = scores[i]
        if not np.isfinite(s) or s < SEG_CONFIDENCE:
            continue
        w = predictions[2, i]
        h = predictions[3, i]
        if not (np.isfinite(w) and np.isfinite(h)) or w <= 1 or h <= 1:
            continue
        cx, cy = predictions[0, i], predictions[1, i]
        coeffs = predictions[5:5 + SEG_PROTOTYPE_CHANNELS, i]
        candidates.append((cx, cy, w, h, float(s), coeffs))
    # NMS
    candidates.sort(key=lambda c: -c[4])
    kept = []
    for c in candidates:
        if all(_seg_iou(c, k) <= SEG_NMS_IOU for k in kept):
            kept.append(c)
    # reconstruct masks
    masks = []
    for cx, cy, bw, bh, score, coeffs in kept:
        x1 = int(np.clip((cx - bw / 2 - pad_x) / ratio, 0, page_w))
        y1 = int(np.clip((cy - bh / 2 - pad_y) / ratio, 0, page_h))
        x2 = int(np.clip((cx + bw / 2 - pad_x) / ratio, 0, page_w))
        y2 = int(np.clip((cy + bh / 2 - pad_y) / ratio, 0, page_h))
        if x2 <= x1 or y2 <= y1:
            continue
        mask = np.zeros((page_h, page_w), dtype=bool)
        # build proto coefficient product over the bbox region (vectorized)
        ys, xs = np.mgrid[y1:y2, x1:x2]
        input_x = xs * ratio + pad_x
        input_y = ys * ratio + pad_y
        proto_x = np.clip((input_x / SEG_INPUT_SIZE * SEG_PROTOTYPE_SIZE).astype(int), 0, SEG_PROTOTYPE_SIZE - 1)
        proto_y = np.clip((input_y / SEG_INPUT_SIZE * SEG_PROTOTYPE_SIZE).astype(int), 0, SEG_PROTOTYPE_SIZE - 1)
        proto_idx = proto_y * SEG_PROTOTYPE_SIZE + proto_x  # HxW in bbox
        # prototypes: 32 x 25600 (flattened 160x160). sum over channels of coeff*proto.
        protos_flat = prototypes.reshape(SEG_PROTOTYPE_CHANNELS, -1)  # 32, 25600
        # gather: for each pixel, sum_c coeffs[c] * protos_flat[c, proto_idx]
        gathered = protos_flat[:, proto_idx.ravel()]  # 32, npix
        raw = (coeffs[:, None] * gathered).sum(axis=0)  # npix
        sig = 1.0 / (1.0 + np.exp(-raw))
        region_mask = sig.reshape(y2 - y1, x2 - x1) >= SEG_MASK_THRESHOLD
        mask[y1:y2, x1:x2] = region_mask
        if mask.any():
            bys, bxs = np.where(mask)
            bounds = [int(bxs.min()), int(bys.min()), int(bxs.max()) + 1, int(bys.max()) + 1]
            masks.append((mask, bounds, score))
    return masks


def _seg_iou(a, b):
    """BubbleSegmentationDecoder.iou (box IoU on cx,cy,w,h)."""
    ax1, ay1 = a[0] - a[2] / 2, a[1] - a[3] / 2
    ax2, ay2 = a[0] + a[2] / 2, a[1] + a[3] / 2
    bx1, by1 = b[0] - b[2] / 2, b[1] - b[3] / 2
    bx2, by2 = b[0] + b[2] / 2, b[1] + b[3] / 2
    iw, ih = min(ax2, bx2) - max(ax1, bx1), min(ay2, by2) - max(ay1, by1)
    if iw <= 0 or ih <= 0:
        return 0.0
    inter = iw * ih
    return inter / (a[2] * a[3] + b[2] * b[3] - inter)


# ============ Parent-bubble matching + dedup (RoiPageRecognitionEngine.kt) ============

def find_parent_bubble(det_box, bubbles):
    """RoiPageRecognitionEngine.findParentBubble: smallest bubble containing the text center."""
    cx = (det_box[0] + det_box[2]) / 2.0
    cy = (det_box[1] + det_box[3]) / 2.0
    containing = [b for b in bubbles if cx >= b[0] and cx <= b[2] and cy >= b[1] and cy <= b[3]]
    if not containing:
        return None
    return min(containing, key=lambda b: (b[2] - b[0]) * (b[3] - b[1]))


def overlaps_any_bubble(det_box, bubbles, min_frac=0.12):
    """AotBoxGeometry.overlapsAnyBubble."""
    ta = max(1, bbox_area(det_box))
    for b in bubbles:
        inter = intersection_area(det_box, b)
        if inter / ta >= min_frac:
            return True
    return False


# ============ PaddleOCR det (refines free-text lines) ============
# Reuse from generate_masks.py - the free-text refinement step.

def paddle_det_preprocess(crop):
    h, w = crop.shape[:2]
    scale = PADDLE_TARGET / float(max(w, h))
    rw = max(1, min(PADDLE_TARGET, round(w * scale)))
    rh = max(1, min(PADDLE_TARGET, round(h * scale)))
    img = Image.fromarray(cv2.cvtColor(crop, cv2.COLOR_BGR2RGB))
    resized = img.resize((rw, rh), Image.BILINEAR)
    padded = Image.new("RGB", (PADDLE_TARGET, PADDLE_TARGET), (0, 0, 0))
    padded.paste(resized, (0, 0))
    arr = np.asarray(padded, dtype=np.float32) / np.float32(255.0)
    mean = np.asarray([0.485, 0.456, 0.406], dtype=np.float32)
    std = np.asarray([0.229, 0.224, 0.225], dtype=np.float32)
    arr = (arr - mean) / std
    chw = np.ascontiguousarray(arr.transpose(2, 0, 1)[None, ...], dtype=np.float32)
    return chw, rw / float(w), rh / float(h), rw, rh


def paddle_detect_lines(sess, img_bgr):
    """Run paddle det on whole image, return boxes in img coords."""
    h, w = img_bgr.shape[:2]
    tensor, ctmx, ctmy, rw, rh = paddle_det_preprocess(img_bgr)
    out = sess.run(None, {sess.get_inputs()[0].name: tensor})[0]
    prob = out[0, 0][:rh, :rw]
    binary = prob > PADDLE_THRESH
    num, labels = cv2.connectedComponents(binary.astype(np.uint8), connectivity=8)
    boxes = []
    for lbl in range(1, num):
        ys, xs = np.where(labels == lbl)
        if len(xs) < 16:
            continue
        x1, x2, y1, y2 = int(xs.min()), int(xs.max()), int(ys.min()), int(ys.max())
        prob_sum = prob[labels == lbl].sum()
        if prob_sum / len(xs) < PADDLE_BOX_THRESH:
            continue
        if (x2 - x1 + 1) * (y2 - y1 + 1) > 0.5 * rw * rh:
            continue
        bx1 = max(0, min(w - 1, round(x1 / ctmx))) if ctmx > 0 else x1
        by1 = max(0, min(h - 1, round(y1 / ctmy))) if ctmy > 0 else y1
        bx2 = max(0, min(w - 1, round(x2 / ctmx))) if ctmx > 0 else x2
        by2 = max(0, min(h - 1, round(y2 / ctmy))) if ctmy > 0 else y2
        if bx2 > bx1 and by2 > by1:
            boxes.append([bx1, by1, bx2, by2])
    return boxes


# ============ Mask geometry (BubbleMaskBuilder.kt) ============

def build_rect_mask(boxes, width, height, pad, dilate_radius=2):
    if width <= 0 or height <= 0 or not boxes:
        return np.zeros((height, width), dtype=np.uint8)
    mask = np.zeros((height, width), dtype=np.uint8)
    for b in boxes:
        x1 = max(0, min(width, b[0] - pad))
        y1 = max(0, min(height, b[1] - pad))
        x2 = max(0, min(width, b[2] + pad))
        y2 = max(0, min(height, b[3] + pad))
        if x2 > x1 and y2 > y1:
            mask[y1:y2, x1:x2] = 1
    if dilate_radius > 0:
        ksize = 2 * dilate_radius + 1
        kernel = np.zeros((ksize, ksize), dtype=np.uint8)
        for dy in range(-dilate_radius, dilate_radius + 1):
            for dx in range(-dilate_radius, dilate_radius + 1):
                if dx * dx + dy * dy <= dilate_radius * dilate_radius:
                    kernel[dy + dilate_radius, dx + dilate_radius] = 1
        mask = cv2.dilate(mask, kernel, iterations=1)
    return mask


def centered_report_crop(boxes, width, height, context):
    if not boxes:
        return None
    ux1 = min(b[0] for b in boxes)
    uy1 = min(b[1] for b in boxes)
    ux2 = max(b[2] for b in boxes)
    uy2 = max(b[3] for b in boxes)
    side = min(context, min(width, height))
    cx, cy = round((ux1 + ux2) / 2), round((uy1 + uy2) / 2)
    x1 = max(0, min(cx - side // 2, width - side))
    y1 = max(0, min(cy - side // 2, height - side))
    return (x1, y1, x1 + side, y1 + side)


def localize_box(box, ox, oy, width, height):
    x1 = max(0, min(width, box[0] - ox))
    y1 = max(0, min(height, box[1] - oy))
    x2 = max(0, min(width, box[2] - ox))
    y2 = max(0, min(height, box[3] - oy))
    return (x1, y1, x2, y2) if (x2 > x1 and y2 > y1) else None


# ============ Main per-page faithful pipeline ============

def process_page_faithful(det_sess, seg_sess, paddle_sess, src_path, out_dir, page_name, source_label):
    src = cv2.imread(str(src_path), cv2.IMREAD_COLOR)
    if src is None:
        print(f"  SKIP {page_name}: cannot read")
        return None
    h, w = src.shape[:2]

    # 1. detector-v4: bubbles + text
    dets = detector_v4_detect(det_sess, src)
    bubbles = [d[0] for d in dets if d[1] == 0]
    text_dets = [d for d in dets if d[1] in (1, 2)]

    # 2. segmenter: precise bubble masks
    seg_masks = segmenter_segment(seg_sess, src) if bubbles else []

    # 3. routing (AOTInpainting.inpaintRegions:195-209)
    bubble_text_boxes = []
    free_text_boxes = []
    for box, label, score in text_dets:
        parent = find_parent_bubble(box, bubbles)
        if parent is not None or overlaps_any_bubble(box, bubbles):
            bubble_text_boxes.append(box)
        elif label == 2:
            free_text_boxes.append(box)
        else:
            bubble_text_boxes.append(box)

    # 4. extra detector boxes (PageInpaintingPlanner.computeMask:109-123):
    #    text detections not overlapping any OCR'd text box. Here all text dets
    #    are "OCR'd" (rec skipped per docstring), so extras = text dets that
    #    don't overlap any bubble-text box. Use the prod IoU>0.4 rule.
    ocr_boxes = [d[0] for d in text_dets]
    extra_boxes = []
    for box, label, score in text_dets:
        expanded = [max(0, box[0] - DETECTOR_OVERLAP_PAD), max(0, box[1] - DETECTOR_OVERLAP_PAD),
                    box[2] + DETECTOR_OVERLAP_PAD, box[3] + DETECTOR_OVERLAP_PAD]
        if all(iou(expanded, ob) <= DETECTOR_OCR_IOU_THRESHOLD for ob in ocr_boxes):
            extra_boxes.append(box)
    free_text_boxes = list({tuple(b) for b in free_text_boxes + extra_boxes})

    page_out = out_dir / page_name
    page_out.mkdir(parents=True, exist_ok=True)
    result = {
        "page": page_name, "source": source_label, "source_dims": [w, h],
        "detector_bubbles": len(bubbles), "detector_text": len(text_dets),
        "seg_masks": len(seg_masks),
        "bubble_text_boxes": len(bubble_text_boxes), "free_text_boxes": len(free_text_boxes),
        "paths": {},
        "note": "OCR-rec filter skipped (PaddleOCR CTC not ported); all text dets treated as readable.",
    }

    # --- FREE-TEXT path (AOT-512): paddle-det refines, centeredReportCrop, fixed-pill ---
    # Prod only runs paddle-det on free-text detector boxes (refineFreeTextGroups).
    # For the corpus we run paddle-det on the whole page and keep boxes that fall
    # in free-text regions, mirroring the prod free-text line set.
    if free_text_boxes:
        paddle_lines = paddle_detect_lines(paddle_sess, src)
        # keep paddle lines whose center is inside a free-text detector box
        refined = []
        for pl in paddle_lines:
            cx, cy = (pl[0] + pl[2]) / 2, (pl[1] + pl[3]) / 2
            if any(cx >= fb[0] and cx <= fb[2] and cy >= fb[1] and cy <= fb[3] for fb in free_text_boxes):
                refined.append(pl)
        free_groups = refined if refined else free_text_boxes
        crop = centered_report_crop(free_groups, w, h, REPORT_AOT_CONTEXT)
        if crop is not None:
            cx1, cy1, cx2, cy2 = crop
            cw, ch = cx2 - cx1, cy2 - cy1
            local = [localize_box(b, cx1, cy1, cw, ch) for b in free_groups]
            local = [b for b in local if b]
            mask = build_rect_mask(local, cw, ch, REPORT_FREE_TEXT_PAD, REPORT_FREE_TEXT_DILATE)
            if mask.any():
                ft_dir = page_out / "free_text"
                ft_dir.mkdir(parents=True, exist_ok=True)
                cv2.imwrite(str(ft_dir / "page.jpg"), src[cy1:cy2, cx1:cx2], [cv2.IMWRITE_JPEG_QUALITY, 92])
                Image.fromarray((mask * 255).astype(np.uint8), mode="L").save(ft_dir / "mask.png")
                ft_man = {
                    "path": "free_text", "inpaint_path": "inpaintReportFreeTextAot512 (AOT model)",
                    "crop": [cx1, cy1, cx2, cy2], "crop_dims": [cw, ch],
                    "free_text_boxes": len(free_text_boxes), "paddle_refined_lines": len(refined),
                    "mask_pad": REPORT_FREE_TEXT_PAD, "mask_dilate": REPORT_FREE_TEXT_DILATE,
                }
                (ft_dir / "manifest.json").write_text(json.dumps(ft_man, indent=2))
                result["paths"]["free_text"] = ft_man

    # --- BUBBLE path: uses segmentation masks (inpaintReportBubbles). Emit per-bubble
    # crop + seg mask rasterized at page resolution for QA. ---
    if seg_masks:
        # combine all seg masks into one page-resolution mask (the bubble erase set)
        combined = np.zeros((h, w), dtype=np.uint8)
        for m, bounds, score in seg_masks:
            combined[m] = 1
        # also include bubble-text boxes that didn't get a seg mask (parent-bubble fallback)
        for b in bubble_text_boxes:
            combined[b[1]:b[3], b[0]:b[2]] = 1
        if combined.any():
            # crop to the union of bubbles (paddedUnionBounds with REPORT_PUSH_PULL_CONTEXT)
            ys, xs = np.where(combined > 0)
            ub = [max(0, int(xs.min()) - REPORT_PUSH_PULL_CONTEXT),
                  max(0, int(ys.min()) - REPORT_PUSH_PULL_CONTEXT),
                  min(w, int(xs.max()) + REPORT_PUSH_PULL_CONTEXT),
                  min(h, int(ys.max()) + REPORT_PUSH_PULL_CONTEXT)]
            bw, bh = ub[2] - ub[0], ub[3] - ub[1]
            if bw > 0 and bh > 0:
                local_mask = combined[ub[1]:ub[3], ub[0]:ub[2]]
                b_dir = page_out / "bubble"
                b_dir.mkdir(parents=True, exist_ok=True)
                cv2.imwrite(str(b_dir / "page.jpg"), src[ub[1]:ub[3], ub[0]:ub[2]], [cv2.IMWRITE_JPEG_QUALITY, 92])
                Image.fromarray((local_mask * 255).astype(np.uint8), mode="L").save(b_dir / "mask.png")
                b_man = {
                    "path": "bubble", "inpaint_path": "inpaintReportBubbles (segmentationMask-based)",
                    "crop": ub, "crop_dims": [bw, bh],
                    "seg_masks": len(seg_masks), "bubble_text_boxes": len(bubble_text_boxes),
                    "mask_source": "YOLO11-seg precise masks rasterized at page res",
                }
                (b_dir / "manifest.json").write_text(json.dumps(b_man, indent=2))
                result["paths"]["bubble"] = b_man

    (page_out / "manifest.json").write_text(json.dumps(result, indent=2))
    return result


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--pages", default="")
    ap.add_argument("--prefix", default="real_")
    args = ap.parse_args()

    src_dir = Path(args.src)
    if not src_dir.is_dir():
        print(f"ERROR: src not found: {src_dir}", file=sys.stderr)
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
        print(f"ERROR: no pages in {src_dir}", file=sys.stderr)
        return 1

    print("Loading detector-v4 ...")
    det_sess = load_session(DETECTOR_MODEL)
    print("Loading bubble segmenter ...")
    seg_sess = load_session(SEGMENTER_MODEL)
    print("Loading paddle det ...")
    paddle_sess = load_session(PADDLE_DET_MODEL)

    out_dir.mkdir(parents=True, exist_ok=True)
    n_ok = 0
    for i, f in enumerate(files, 1):
        stem = f.stem
        num = "".join(c for c in stem if c.isdigit())[-3:] if any(c.isdigit() for c in stem) else stem
        name = f"{args.prefix}{num}"
        print(f"[{i}/{len(files)}] {f.name} -> {name}")
        r = process_page_faithful(det_sess, seg_sess, paddle_sess, f, out_dir, name, src_dir.name)
        if r is not None:
            n_ok += 1
            paths = list(r.get("paths", {}).keys())
            print(f"    bubbles={r['detector_bubbles']} text={r['detector_text']} seg={r['seg_masks']} "
                  f"bubbleText={r['bubble_text_boxes']} freeText={r['free_text_boxes']} paths={paths}")

    print(f"\nDone. {n_ok}/{len(files)} page(s) processed to {out_dir}")
    return 0 if n_ok > 0 else 1


if __name__ == "__main__":
    sys.exit(main())
