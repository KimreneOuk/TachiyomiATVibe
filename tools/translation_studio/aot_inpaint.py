"""AOT free-text inpainting — desktop port of the Android report pipeline.

Constant-for-constant port of AOTInpainting.inpaintRegions' free-text leg
(app/src/main/java/eu/kanade/translation/inpainting/aot/AOTInpainting.kt):

  * refineFreeTextGroups           — per free-text detector box: crop
                                     +-PADDLE_CROP_PAD, Paddle det at the
                                     inpaint-path thresholds (PADDLE_THRESH
                                     =0.18 / PADDLE_BOX_THRESH=0.34), lines
                                     back-projected to page space; det finds
                                     nothing -> the raw detector box
                                     (fallbackCount).
  * AotBoxGeometry.clusterFreeTextGroups — agglomerative merging of the pair
                                     with the smallest union area while the
                                     merged bbox fits REPORT_AOT_CONTEXT=512
                                     on both axes.
  * inpaintReportFreeTextNeural    — centeredReportCrop(512) square,
                                     buildFixedPillMask(pad 16, disk dilate 8),
                                     fixed-512 AOT + AotOutputGuard; accepted
                                     -> featherAlphaField(ramp 3) composite.
  * inpaintReportFreeTextFast      — paddedUnionBounds(context 64) crop,
                                     fixed pill mask, PushPullGradient.
                                     localRingMedian(ring 8) + pushPullFill,
                                     feather-3 composite.

Neural exhaustion (guard rejection or failure) routes to the push/pull fast
path exactly like the app's AotFallbackCoordinator.Exhausted leg; FAST mode
routes every free-text group straight to push/pull
("[inpaint] route=push_pull reason=fast_mode|no_neural_session").
PushPullGradient.kt is ported in full (DEFAULT_RING=8, DOWN_SAMPLE_DIV=20
with floor max(2, ...), DIFFUSION_PASSES=15, histogram median acc > count/2,
truncating lerp, avg4 = (sum+2) >> 2). Pure numpy/onnxruntime + PIL/scipy.
"""
from __future__ import annotations

import time
from pathlib import Path

import numpy as np
from PIL import Image
from scipy import ndimage

REPO_ROOT = Path(__file__).resolve().parents[2]
AOT_MODEL = REPO_ROOT / "app/src/main/assets/models/inpainting/aot-512.onnx"

# ---- AOTInpainting.kt companion constants ----
AOT_SIZE = 512              # AotPadPath.SIZE (fixed-512 tensor contract)
GRAYSCALE_CHROMA = 15       # prepareFixedInput grayscale collapse (< 15)
PADDLE_CROP_PAD = 12        # AOTInpainting.PADDLE_CROP_PAD
PADDLE_THRESH = 0.18        # AOTInpainting.PADDLE_THRESH
PADDLE_BOX_THRESH = 0.34    # AOTInpainting.PADDLE_BOX_THRESH
REPORT_FREE_TEXT_PAD = 16       # AOTInpainting.REPORT_FREE_TEXT_PAD
REPORT_FREE_TEXT_DILATE = 8     # AOTInpainting.REPORT_FREE_TEXT_DILATE
REPORT_AOT_CONTEXT = 512        # AOTInpainting.REPORT_AOT_CONTEXT
REPORT_FREE_TEXT_FEATHER = 3    # AOTInpainting.REPORT_FREE_TEXT_FEATHER
REPORT_PUSH_PULL_CONTEXT = 64   # AOTInpainting.REPORT_PUSH_PULL_CONTEXT
MASK_PAD = 8                    # AOTInpainting.MASK_PAD (inpaintReportBubbles)
REPORT_BUBBLE_SMOOTH_PASSES = 12  # AOTInpainting.REPORT_BUBBLE_SMOOTH_PASSES
FEATHER_RAMP_PX = 12            # AOTInpainting.FEATHER_RAMP_PX
BUBBLE_SEG_MASK_EROSION = 5     # Edge erosion for bubble segmentation mask inpainting

# ---- PushPullGradient.kt ----
DEFAULT_RING = 8            # PushPullGradient.DEFAULT_RING
DOWN_SAMPLE_DIV = 20        # private DOWN_SAMPLE_DIV (small = dim / 20, floor 2)
DIFFUSION_PASSES = 15       # PushPullGradient.DIFFUSION_PASSES

# ---- AotOutputGuard (unchanged from the Phase-A port) ----
GUARD_MASK_THRESHOLD = 127
GUARD_MIN_MASKED_PIXELS = 16
GUARD_NEAR_BLACK_MAX = 24.0
GUARD_MID_GRAY_MIN = 96.0
GUARD_MID_GRAY_MAX = 160.0
GUARD_NEAR_WHITE_MIN = 238.0
GUARD_MAX_LUMA_VARIANCE = 9.0
GUARD_MAX_CHANNEL_DELTA = 8.0


def _log(msg: str) -> None:
    print(msg, flush=True)


# =========================================================== AotBoxGeometry
def localize_box(box, origin_x: int, origin_y: int, width: int, height: int):
    """AotBoxGeometry.localizeBox: box minus origin, clamped to the crop."""
    x1 = min(max(box[0] - origin_x, 0), width)
    y1 = min(max(box[1] - origin_y, 0), height)
    x2 = min(max(box[2] - origin_x, 0), width)
    y2 = min(max(box[3] - origin_y, 0), height)
    return [x1, y1, x2, y2] if (x2 > x1 and y2 > y1) else None


def padded_union_bounds(boxes, width: int, height: int, pad: int):
    """AotBoxGeometry.paddedUnionBounds."""
    if not boxes:
        return None
    x1 = max(0, min(b[0] for b in boxes) - pad)
    y1 = max(0, min(b[1] for b in boxes) - pad)
    x2 = min(width, max(b[2] for b in boxes) + pad)
    y2 = min(height, max(b[3] for b in boxes) + pad)
    return [x1, y1, x2, y2] if (x2 > x1 and y2 > y1) else None


def centered_report_crop(boxes, width: int, height: int,
                         context_size: int = REPORT_AOT_CONTEXT):
    """AotBoxGeometry.centeredReportCrop: square min(context, min(w, h))
    centered on the union of boxes."""
    if not boxes:
        return None
    ux1 = min(b[0] for b in boxes)
    uy1 = min(b[1] for b in boxes)
    ux2 = max(b[2] for b in boxes)
    uy2 = max(b[3] for b in boxes)
    side = min(context_size, min(width, height))
    cx = int(np.floor((ux1 + ux2) / 2.0 + 0.5))   # Double.roundToInt: ties +inf
    cy = int(np.floor((uy1 + uy2) / 2.0 + 0.5))
    x1 = min(max(cx - side // 2, 0), width - side)
    y1 = min(max(cy - side // 2, 0), height - side)
    return [x1, y1, x1 + side, y1 + side]


def cluster_free_text_groups(groups, max_context_size: int = REPORT_AOT_CONTEXT):
    """AotBoxGeometry.clusterFreeTextGroups, exact: repeatedly merge the
    cluster pair with the smallest union area while the merged bounding box
    fits max_context_size along BOTH dimensions."""
    valid_groups = [list(g) for g in groups if g]
    if len(valid_groups) <= 1:
        return valid_groups

    clusters = []
    for group in valid_groups:
        clusters.append({
            "boxes": [list(b) for b in group],
            "min_x": min(b[0] for b in group),
            "min_y": min(b[1] for b in group),
            "max_x": max(b[2] for b in group),
            "max_y": max(b[3] for b in group),
        })

    while True:
        best_i = best_j = -1
        min_union_area = None
        for i in range(len(clusters)):
            for j in range(i + 1, len(clusters)):
                a, b = clusters[i], clusters[j]
                nx1, ny1 = min(a["min_x"], b["min_x"]), min(a["min_y"], b["min_y"])
                nx2, ny2 = max(a["max_x"], b["max_x"]), max(a["max_y"], b["max_y"])
                if (nx2 - nx1) <= max_context_size and (ny2 - ny1) <= max_context_size:
                    area = (nx2 - nx1) * (ny2 - ny1)
                    if min_union_area is None or area < min_union_area:
                        min_union_area, best_i, best_j = area, i, j
        if best_i == -1:
            break
        a, b = clusters[best_i], clusters[best_j]
        a["boxes"].extend(b["boxes"])
        a["min_x"] = min(a["min_x"], b["min_x"])
        a["min_y"] = min(a["min_y"], b["min_y"])
        a["max_x"] = max(a["max_x"], b["max_x"])
        a["max_y"] = max(a["max_y"], b["max_y"])
        clusters.pop(best_j)

    return [c["boxes"] for c in clusters]


# ============================================================ BubbleMaskBuilder
def _disk_kernel(radius: int) -> np.ndarray:
    """BubbleMaskBuilder.dilateMaskDisk kernel: dx*dx + dy*dy <= r*r."""
    yy, xx = np.mgrid[-radius:radius + 1, -radius:radius + 1]
    return (yy * yy + xx * xx) <= radius * radius


def _rect_mask(boxes, width: int, height: int, pad: int,
               dilate_radius: int = 0) -> np.ndarray:
    """BubbleMaskBuilder.buildRectMask: each box padded, solid-filled, then
    ONE disk dilation of the whole mask (0 = none)."""
    if width <= 0 or height <= 0 or not boxes:
        return np.zeros((height, width), bool)
    mask = np.zeros((height, width), bool)
    for b in boxes:
        if len(b) < 4:
            continue
        x1 = min(max(b[0] - pad, 0), width)
        y1 = min(max(b[1] - pad, 0), height)
        x2 = min(max(b[2] + pad, 0), width)
        y2 = min(max(b[3] + pad, 0), height)
        if x2 <= x1 or y2 <= y1:
            continue
        mask[y1:y2, x1:x2] = True
    if dilate_radius > 0:
        mask = ndimage.binary_dilation(mask, structure=_disk_kernel(dilate_radius))
    return mask


def build_fixed_pill_mask(boxes, width: int, height: int,
                          pad: int = REPORT_FREE_TEXT_PAD,
                          dilate_radius: int = REPORT_FREE_TEXT_DILATE) -> np.ndarray:
    """BubbleMaskBuilder.buildFixedPillMask -> buildRectMask. Fixed radius —
    Paddle line boxes are uniformly narrow strips."""
    return _rect_mask(boxes, width, height, pad, dilate_radius)


def build_dynamic_pill_mask(boxes, width: int, height: int,
                            pad: int = MASK_PAD) -> np.ndarray:
    """BubbleMaskBuilder.buildDynamicPillMask: padded solid union, then each
    box OR'd in with its own disk dilation, radius = clamp(shortSide/8, 2, 16)
    (rounded corners scale with the box without bridging neighbours)."""
    if width <= 0 or height <= 0 or not boxes:
        return np.zeros((height, width), bool)
    combined = _rect_mask(boxes, width, height, pad, dilate_radius=0)
    for b in boxes:
        if len(b) < 4:
            continue
        bw = max(0, b[2] - b[0])
        bh = max(0, b[3] - b[1])
        if bw == 0 or bh == 0:
            continue
        radius = min(max(min(bw, bh) // 8, 2), 16)
        single = _rect_mask([b], width, height, pad, dilate_radius=0)
        combined |= ndimage.binary_dilation(single, structure=_disk_kernel(radius))
    return combined


def report_bubble_fill(px: np.ndarray, mask: np.ndarray,
                       smooth_passes: int = REPORT_BUBBLE_SMOOTH_PASSES) -> None:
    """AotReportBubbleFill.reportBubbleFill: per 8-connected mask component —
    Manhattan (4-neighbour BFS) distance to the component boundary, histogram
    median of pixels at distance >= 2 (skips stroke outlines), near-gray
    snapped to pure white/black (cmax-cmin < 30: luma > 220 -> white,
    < 60 -> black), inset fill (maxDist >= 16 -> inset 4, >= 8 -> 2, else 0),
    then smooth_passes edge-clamped avg4 diffusion written only where
    distance >= inset. Component loop + vectorized inner ops."""
    if not mask.any():
        return
    labels, n = ndimage.label(mask, structure=np.ones((3, 3), bool))
    # 4-connected BFS distance to the nearest boundary == taxicab CDT of the
    # mask (two-pass shortest path constrained to mask pixels)
    dist = ndimage.distance_transform_cdt(mask, metric="taxicab").astype(np.int32)
    for comp in range(1, n + 1):
        sel = labels == comp
        deep = sel & (dist >= 2)
        count = int(deep.sum())
        if count > 0:
            chan = []
            for c in range(3):
                chan.append(_histogram_median(
                    np.bincount(px[..., c][deep], minlength=256), count))
            r, g, b = chan
            # snap near-gray to pure white/black to match bubble paper/ink
            cmax, cmin = max(r, g, b), min(r, g, b)
            if cmax - cmin < 30:
                luma = (cmax + cmin) // 2
                if luma > 220:
                    r = g = b = 255
                elif luma < 60:
                    r = g = b = 0
            median = (r, g, b)
        else:
            sz = int(sel.sum())
            med = [int(px[..., c][sel].sum() // sz) for c in range(3)]
            median = tuple(med)
        max_dist = int(dist[sel].max())
        inset = 4 if max_dist >= 16 else (2 if max_dist >= 8 else 0)
        fill_sel = sel & (dist >= inset)
        if not fill_sel.any():
            continue
        px[fill_sel] = median
        if smooth_passes > 0:
            for _ in range(smooth_passes):
                p = px.astype(np.int32)
                up = np.concatenate((p[:1], p[:-1]), axis=0)
                down = np.concatenate((p[1:], p[-1:]), axis=0)
                left = np.concatenate((p[:, :1], p[:, :-1]), axis=1)
                right = np.concatenate((p[:, 1:], p[:, -1:]), axis=1)
                upd = ((up + down + left + right + 2) >> 2).astype(np.uint8)
                px[fill_sel] = upd[fill_sel]


def fill_and_blend(px: np.ndarray, mask: np.ndarray,
                   smooth_passes: int = REPORT_BUBBLE_SMOOTH_PASSES,
                   feather_ramp_px: int = FEATHER_RAMP_PX) -> None:
    """AotReportBubbleFill.fillAndBlend: fill in place, feather the result
    against a copy of the pre-fill pixels."""
    original = px.copy()
    report_bubble_fill(px, mask, smooth_passes)
    alpha = feather_alpha_field(mask, feather_ramp_px)
    px[:] = blend_pixel(original, px, alpha)


def inpaint_report_bubbles(page: Image.Image, boxes, seg_masks=None,
                           erosion: int = BUBBLE_SEG_MASK_EROSION) -> tuple[Image.Image, np.ndarray]:
    """AOTInpainting.inpaintReportBubbles matching Android:
    1. Rasterize bubble segmentation masks from manga109 YOLO11-seg model.
    2. Erode segmentation mask by `erosion` px (default 5) to protect bubble stroke boundaries.
    3. For bubble boxes not covered by existing segmentation masks, build dynamic pill mask (MASK_PAD=8).
    4. Run AotReportBubbleFill.fillAndBlend(smooth=12, feather=12).
    Returns (page, bubble_mask)."""
    w, h = page.size
    mask = np.zeros((h, w), bool)
    if seg_masks:
        for m in seg_masks:
            if hasattr(m, "labels"):
                mask |= (m.labels > 0)
            elif hasattr(m, "component_mask"):
                for c in getattr(m, "components", [1]):
                    mask |= m.component_mask(c)
            elif isinstance(m, np.ndarray):
                mask |= m.astype(bool)

    if mask.any() and erosion > 0:
        eroded = ndimage.binary_erosion(mask, iterations=erosion)
        if eroded.any():
            # If any individual component completely vanished, restore it with a milder erosion or original
            labels, n = ndimage.label(mask, structure=np.ones((3, 3), bool))
            eroded_labels = set(np.unique(labels[eroded]))
            for comp in range(1, n + 1):
                if comp not in eroded_labels:
                    comp_m = labels == comp
                    e = ndimage.binary_erosion(comp_m, iterations=max(1, erosion // 2))
                    eroded |= (e if e.any() else comp_m)
            mask = eroded

    remaining_boxes = []
    for b in (boxes or []):
        x1, y1 = max(0, int(b[0])), max(0, int(b[1]))
        x2, y2 = min(w, int(b[2])), min(h, int(b[3]))
        if x2 > x1 and y2 > y1:
            box_area = (x2 - x1) * (y2 - y1)
            if mask.any() and mask[y1:y2, x1:x2].sum() > 0.35 * box_area:
                continue
        remaining_boxes.append(b)

    if remaining_boxes:
        pill_mask = build_dynamic_pill_mask(remaining_boxes, w, h, MASK_PAD)
        mask |= pill_mask

    if not mask.any():
        return page, mask

    px = np.asarray(page).copy()
    fill_and_blend(px, mask, REPORT_BUBBLE_SMOOTH_PASSES, FEATHER_RAMP_PX)
    page.paste(Image.fromarray(px))
    return page, mask



def _scan_lr(row: np.ndarray, step: int = 3) -> np.ndarray:
    """Sequential row recurrence row[x] = min(row[x], row[x-1] + step) as a
    prefix-min: min_k<=x(row[k] + step*(x-k))."""
    idx = np.arange(row.shape[0], dtype=np.int64)
    t = np.minimum.accumulate(row.astype(np.int64) - step * idx)
    return np.minimum(row, t + step * idx).astype(np.int32)


def _scan_rl(row: np.ndarray, step: int = 3) -> np.ndarray:
    """Right-to-left twin of _scan_lr: min_k>=x(row[k] + step*(k-x))."""
    idx = np.arange(row.shape[0], dtype=np.int64)
    t = np.minimum.accumulate((row.astype(np.int64) + step * idx)[::-1])[::-1]
    return np.minimum(row, t - step * idx).astype(np.int32)


def chamfer_distance_to_mask(mask: np.ndarray) -> np.ndarray:
    """BubbleMaskBuilder.distanceToMask: two-pass chamfer(3,4) distance to the
    nearest mask pixel, in integer chamfer units (px = units / 3)."""
    h, w = mask.shape
    inf = np.int32(10 ** 9)
    dist = np.where(mask, np.int32(0), inf)
    for y in range(h):                       # forward: top-left -> bottom-right
        if y > 0:
            prev = dist[y - 1].astype(np.int64)
            cand = np.minimum(
                prev + 3,
                np.minimum(
                    np.concatenate(([inf], prev[:-1])) + 4,   # up-left
                    np.concatenate((prev[1:], (int(inf),))) + 4))  # up-right
            dist[y] = np.minimum(dist[y], cand.astype(np.int32))
        dist[y] = _scan_lr(dist[y])
    for y in range(h - 1, -1, -1):           # backward: bottom-right -> top-left
        if y < h - 1:
            nxt = dist[y + 1].astype(np.int64)
            cand = np.minimum(
                nxt + 3,
                np.minimum(
                    np.concatenate(([inf], nxt[:-1])) + 4,    # down-left
                    np.concatenate((nxt[1:], (int(inf),))) + 4))   # down-right
            dist[y] = np.minimum(dist[y], cand.astype(np.int32))
        dist[y] = _scan_rl(dist[y])
    return dist


def feather_alpha_field(mask: np.ndarray, ramp_width: int) -> np.ndarray:
    """BubbleMaskBuilder.featherAlphaField: 1.0 in mask, 1 - dist/ramp outside
    (ramp floored at 2), dist in px = chamfer units / 3."""
    if not mask.any():
        return np.zeros(mask.shape, np.float32)
    units = chamfer_distance_to_mask(mask).astype(np.float32)
    ramp = float(max(2, ramp_width))
    alpha = np.clip(np.float32(1.0) - (units / np.float32(3.0)) / np.float32(ramp),
                    np.float32(0.0), np.float32(1.0))
    alpha[mask] = np.float32(1.0)
    return alpha


# ============================================================ AotPixelOps
def blend_pixel(original: np.ndarray, inpainted: np.ndarray,
                alpha: np.ndarray) -> np.ndarray:
    """AotPixelOps.blendPixel vectorized: per-channel
    roundToInt(original*(1-a) + inpainted*a), clamped 0..255 (roundToInt:
    ties toward positive infinity -> floor(x + 0.5))."""
    a = alpha.astype(np.float32)[..., None]
    out = (original.astype(np.float32) * (np.float32(1.0) - a)
           + inpainted.astype(np.float32) * a)
    return np.clip(np.floor(out + np.float32(0.5)), 0, 255).astype(np.uint8)


# ========================================================= PushPullGradient
def _histogram_median(hist: np.ndarray, count: int) -> int:
    """PushPullGradient.histogramMedian: first value with cumsum > count/2."""
    half = count // 2
    cums = np.cumsum(hist)
    reached = cums > half
    return int(np.argmax(reached)) if reached.any() else 255


def _median_argb(flat_px: np.ndarray) -> tuple:
    """PushPullGradient.medianArgb (whole-buffer fallback); empty -> white."""
    if flat_px.shape[0] == 0:
        return (255, 255, 255)
    count = flat_px.shape[0]
    return tuple(_histogram_median(np.bincount(flat_px[:, c], minlength=256), count)
                 for c in range(3))


def local_ring_median(px: np.ndarray, mask: np.ndarray,
                      ring: int = DEFAULT_RING) -> tuple:
    """PushPullGradient.localRingMedian: histogram median of the ring
    rectangle around the mask bbox, EXCLUDING the bbox rectangle interior
    (not just mask pixels). Empty annulus / empty mask -> global crop median."""
    h, w = mask.shape
    ys, xs = np.where(mask)
    if ys.size == 0:
        return _median_argb(px.reshape(-1, 3))
    y1, y2 = int(ys.min()), int(ys.max())
    x1, x2 = int(xs.min()), int(xs.max())
    ry1, ry2 = max(0, y1 - ring), min(h, y2 + ring + 1)
    rx1, rx2 = max(0, x1 - ring), min(w, x2 + ring + 1)
    sub = px[ry1:ry2, rx1:rx2]
    in_y1, in_x1 = y1 - ry1, x1 - rx1
    in_y2, in_x2 = in_y1 + (y2 - y1 + 1), in_x1 + (x2 - x1 + 1)
    ly = np.arange(ry2 - ry1)[:, None]
    lx = np.arange(rx2 - rx1)[None, :]
    inner = (ly >= in_y1) & (ly < in_y2) & (lx >= in_x1) & (lx < in_x2)
    sel = sub[~inner].reshape(-1, 3)
    if sel.shape[0] == 0:
        return _median_argb(px.reshape(-1, 3))
    count = sel.shape[0]
    return tuple(_histogram_median(np.bincount(sel[:, c], minlength=256), count)
                 for c in range(3))


def _lerp(a: np.ndarray, b: np.ndarray, t) -> np.ndarray:
    """PushPullGradient.lerp: (a + (b - a) * t).toInt() truncation, clamped."""
    out = a.astype(np.float32) + (b.astype(np.float32) - a.astype(np.float32)) * t
    return np.clip(out.astype(np.int32), 0, 255)


def _bilerp(c00, c10, c01, c11, tx, ty) -> np.ndarray:
    """PushPullGradient.bilerp per channel (tx broadcasts over columns,
    ty over rows)."""
    chan = []
    for c in range(3):
        r0 = _lerp(c00[..., c], c10[..., c], tx)
        r1 = _lerp(c01[..., c], c11[..., c], tx)
        chan.append(_lerp(r0, r1, ty))
    return np.stack(chan, axis=-1)


def push_pull_fill(px: np.ndarray, mask: np.ndarray, bg) -> None:
    """PushPullGradient.pushPullFill: (a) erase mask with bg, (b) box-average
    downscale to max(2, dim/20), (c) bilinear upscale (half-pixel centres,
    Float math), (d) paint the gradient into the hole, (e) DIFFUSION_PASSES
    edge-clamped 4-neighbour diffusion inside the mask ((sum+2) >> 2)."""
    h, w = mask.shape
    if h == 0 or w == 0:
        return
    # a. erase the ink before downscaling so text does not pollute the gradient
    px[mask] = bg
    # b. box-average downscale, (sum + c/2) / c integer-rounded
    small_w = max(2, w // DOWN_SAMPLE_DIV)
    small_h = max(2, h // DOWN_SAMPLE_DIV)
    ys = (np.arange(h) * small_h) // h
    xs = (np.arange(w) * small_w) // w
    flat = (ys[:, None] * small_w + xs[None, :]).reshape(-1)
    sums = np.zeros((small_w * small_h, 3), np.int64)
    np.add.at(sums, flat, px.reshape(-1, 3))
    cnt = np.bincount(flat, minlength=small_w * small_h).astype(np.int64)
    c = np.maximum(cnt, 1)[:, None]
    small = ((sums + c // 2) // c).astype(np.float32).reshape(small_h, small_w, 3)
    # c. bilinear upscale back to full size (float32 like the JVM Float math)
    fy = ((np.arange(h, dtype=np.float32) + np.float32(0.5)) * np.float32(small_h)
          / np.float32(h) - np.float32(0.5))
    fx = ((np.arange(w, dtype=np.float32) + np.float32(0.5)) * np.float32(small_w)
          / np.float32(w) - np.float32(0.5))
    y0 = np.floor(fy).astype(np.int64)
    ty = np.clip(fy - y0.astype(np.float32), np.float32(0.0), np.float32(1.0))
    y0 = np.clip(y0, 0, small_h - 1)
    y1 = np.minimum(y0 + 1, small_h - 1)
    x0 = np.floor(fx).astype(np.int64)
    tx = np.clip(fx - x0.astype(np.float32), np.float32(0.0), np.float32(1.0))
    x0 = np.clip(x0, 0, small_w - 1)
    x1 = np.minimum(x0 + 1, small_w - 1)
    gradient = _bilerp(small[np.ix_(y0, x0)], small[np.ix_(y0, x1)],
                       small[np.ix_(y1, x0)], small[np.ix_(y1, x1)],
                       tx[None, :], ty[:, None]).astype(np.uint8)
    # d. paint the gradient into the hole only
    px[mask] = gradient[mask]
    # e. boundary diffusion inside the mask (edge-clamped 4-neighbour average)
    for _ in range(DIFFUSION_PASSES):
        p = px.astype(np.int32)
        up = np.concatenate((p[:1], p[:-1]), axis=0)
        down = np.concatenate((p[1:], p[-1:]), axis=0)
        left = np.concatenate((p[:, :1], p[:, :-1]), axis=1)
        right = np.concatenate((p[:, 1:], p[:, -1:]), axis=1)
        upd = ((up + down + left + right + 2) >> 2).astype(np.uint8)
        px[mask] = upd[mask]


# ======================================================= AOTInpainting.leg
def refine_free_text_groups(page: Image.Image, detector_boxes, paddle_det):
    """AOTInpainting.refineFreeTextGroups: per detector box, crop
    +-PADDLE_CROP_PAD clamped, detectLines(crop, thresh=0.18, boxThresh=0.34),
    back-project lines to page space; no lines -> the raw detector box
    (fallbackCount). Null detector -> every box its own fallback group.
    Returns (groups, paddle_line_count, fallback_count, det_ms)."""
    w, h = page.size
    if paddle_det is None:
        return [[list(b)] for b in detector_boxes], 0, len(detector_boxes), 0.0
    groups = []
    paddle_lines = 0
    fallback = 0
    det_ms = 0.0
    for det in detector_boxes:
        cx1 = min(max(det[0] - PADDLE_CROP_PAD, 0), w)
        cy1 = min(max(det[1] - PADDLE_CROP_PAD, 0), h)
        cx2 = min(max(det[2] + PADDLE_CROP_PAD, 0), w)
        cy2 = min(max(det[3] + PADDLE_CROP_PAD, 0), h)
        if cx2 <= cx1 or cy2 <= cy1:
            groups.append([list(det)])
            fallback += 1
            continue
        crop = page.crop((cx1, cy1, cx2, cy2))
        t0 = time.perf_counter()
        try:
            lines = paddle_det.detect_lines(crop, thresh=PADDLE_THRESH,
                                            box_thresh=PADDLE_BOX_THRESH)
        except Exception as e:
            _log(f"[inpaint] report Paddle DET failed; using detector "
                 f"free-text box ({e})")
            lines = []
        det_ms += (time.perf_counter() - t0) * 1000.0
        if not lines:
            groups.append([list(det)])
            fallback += 1
            continue
        group = []
        for line in lines:
            b = line[:4]
            if len(b) < 4:
                continue
            px1 = min(max(cx1 + int(b[0]), 0), w)
            py1 = min(max(cy1 + int(b[1]), 0), h)
            px2 = min(max(cx1 + int(b[2]), 0), w)
            py2 = min(max(cy1 + int(b[3]), 0), h)
            if px2 > px1 and py2 > py1:
                group.append([px1, py1, px2, py2])
                paddle_lines += 1
        if group:
            groups.append(group)
        else:
            groups.append([list(det)])
            fallback += 1
    return groups, paddle_lines, fallback, det_ms


def inpaint_report_free_text_fast(page: Image.Image, boxes) -> Image.Image:
    """AOTInpainting.inpaintReportFreeTextFast: paddedUnionBounds(context 64)
    crop -> buildFixedPillMask(pad 16, disk dilate 8) -> localRingMedian
    (ring 8) -> pushPullFill -> featherAlphaField(ramp 3) composite."""
    w, h = page.size
    bounds = padded_union_bounds(boxes, w, h, REPORT_PUSH_PULL_CONTEXT)
    if bounds is None:
        return page
    crop_w, crop_h = bounds[2] - bounds[0], bounds[3] - bounds[1]
    if crop_w <= 0 or crop_h <= 0:
        return page
    local = [lb for lb in (localize_box(b, bounds[0], bounds[1], crop_w, crop_h)
                           for b in boxes) if lb is not None]
    mask = build_fixed_pill_mask(local, crop_w, crop_h,
                                 REPORT_FREE_TEXT_PAD, REPORT_FREE_TEXT_DILATE)
    if not mask.any():
        return page
    original = np.asarray(page.crop((bounds[0], bounds[1],
                                     bounds[2], bounds[3])), np.uint8)
    work = original.copy()
    bg = local_ring_median(work, mask, DEFAULT_RING)
    push_pull_fill(work, mask, bg)
    alpha = feather_alpha_field(mask, REPORT_FREE_TEXT_FEATHER)
    # alpha == 0 blends back to the original exactly, matching the app's
    # "if (a > 0) work = blendPixel(original, work, a)" loop
    page.paste(Image.fromarray(blend_pixel(original, work, alpha)),
               (bounds[0], bounds[1]))
    return page


def _sampled_chroma_grayscale(source: np.ndarray) -> bool:
    """prepareFixedInput chroma sampling: stride max(1, n/400), integer-mean
    chroma < 15 -> grayscale."""
    n = source.shape[0] * source.shape[1]
    stride = max(1, n // 400)
    flat = source.reshape(-1, 3)[::stride]
    if flat.shape[0] == 0:
        return False
    total = int(np.sum(flat.max(axis=1).astype(np.int32)
                       - flat.min(axis=1).astype(np.int32)))
    return (total // flat.shape[0]) < GRAYSCALE_CHROMA


class AotInpainter:
    """Lazy neural session + the fixed-512 report path on a full page."""

    def __init__(self):
        self._sess = None

    @property
    def ready(self) -> bool:
        return AOT_MODEL.exists()

    def _session(self):
        if self._sess is None:
            import onnxruntime as ort
            opts = ort.SessionOptions()
            opts.log_severity_level = 3
            self._sess = ort.InferenceSession(
                str(AOT_MODEL), opts, providers=["CPUExecutionProvider"])
        return self._sess

    def _prepare_fixed_input(self, source: np.ndarray, mask: np.ndarray):
        """AOTInpainting.prepareFixedInput: background =
        localRingMedian(source, mask, ring 8); pad the side×side crop into the
        512 canvas at AotPadPath.centeredOffset; image /255, mask 1.0=hole."""
        side = source.shape[0]
        background = local_ring_median(source, mask, DEFAULT_RING)
        offset = (AOT_SIZE - side) // 2          # AotPadPath.centeredOffset
        canvas = np.empty((AOT_SIZE, AOT_SIZE, 3), np.uint8)
        canvas[:] = background
        canvas[offset:offset + side, offset:offset + side] = source
        mask_canvas = np.zeros((AOT_SIZE, AOT_SIZE), np.float32)
        mask_canvas[offset:offset + side, offset:offset + side] = mask
        image_in = np.ascontiguousarray(
            (canvas.astype(np.float32) / np.float32(255.0)).transpose(2, 0, 1)[None])
        return ({"image": image_in, "mask": mask_canvas[None, None]},
                offset, _sampled_chroma_grayscale(source))

    def _guard_rejects(self, candidate: np.ndarray, mask: np.ndarray) -> bool:
        """AotOutputGuard.inspect + classify (exact thresholds)."""
        luma = (0.299 * candidate[..., 0] + 0.587 * candidate[..., 1]
                + 0.114 * candidate[..., 2])
        masked = luma[mask]
        unmasked = luma[~mask]
        if masked.size < GUARD_MIN_MASKED_PIXELS:
            return False
        mean = float(masked.mean())
        variance = float(masked.var())
        channel_delta = float(np.mean(
            np.abs(candidate[..., 0] - candidate[..., 1])
            + np.abs(candidate[..., 1] - candidate[..., 2])))
        uniform = variance < GUARD_MAX_LUMA_VARIANCE and channel_delta < GUARD_MAX_CHANNEL_DELTA
        if not uniform:
            return False
        unmasked_is_white = unmasked.size > 0 and unmasked.mean() >= 200.0
        uniform_near_white = mean >= GUARD_NEAR_WHITE_MIN and not unmasked_is_white
        unmasked_is_black = unmasked.size > 0 and unmasked.mean() <= 40.0
        uniform_near_black = mean <= GUARD_NEAR_BLACK_MAX and not unmasked_is_black
        uniform_mid_gray = GUARD_MID_GRAY_MIN <= mean <= GUARD_MID_GRAY_MAX
        return uniform_near_black or uniform_mid_gray or uniform_near_white

    def inpaint_report_free_text_neural(self, page: Image.Image, boxes) -> Image.Image:
        """AOTInpainting.inpaintReportFreeTextNeural: centeredReportCrop(512),
        fixed pill mask, fixed-512 AOT; accepted -> feather-3 composite;
        exhausted (guard rejection / failure) -> log
        route=push_pull reason=neural_exhausted and inpaintReportFreeTextFast.
        (Desktop: no TranslationMemoryBudget, NNAPI/QNN routes, or dynamic
        session — CPU single session.)"""
        w, h = page.size
        crop = centered_report_crop(boxes, w, h, REPORT_AOT_CONTEXT)
        if crop is None:
            return page
        side = crop[2] - crop[0]
        if side <= 0 or crop[3] - crop[1] != side:
            return page
        local = [lb for lb in (localize_box(b, crop[0], crop[1], side, side)
                               for b in boxes) if lb is not None]
        mask = build_fixed_pill_mask(local, side, side,
                                     REPORT_FREE_TEXT_PAD, REPORT_FREE_TEXT_DILATE)
        if not mask.any():
            return page
        original = np.asarray(page.crop((crop[0], crop[1], crop[2], crop[3])),
                              np.uint8)
        try:
            inputs, offset, grayscale = self._prepare_fixed_input(original, mask)
            out = self._session().run(None, inputs)[0][0]     # [3,512,512]
            # AotPixelOps.decodeFixedChannel: roundToInt(value * 255)
            inner = np.clip(np.rint(out.transpose(1, 2, 0) * 255.0),
                            0, 255).astype(np.uint8)
            inner = inner[offset:offset + side, offset:offset + side]
            if grayscale:
                luma = (0.299 * inner[..., 0] + 0.587 * inner[..., 1]
                        + 0.114 * inner[..., 2])
                luma = np.floor(luma + 0.5).astype(np.uint8)
                inner = np.stack([luma] * 3, axis=2)
            if self._guard_rejects(inner.astype(np.float32), mask):
                _log(f"[inpaint] route=push_pull reason=neural_exhausted "
                     f"crop={side}x{side} offset={crop[0]},{crop[1]} "
                     f"tensor={side}x{side} boxes={len(boxes)} "
                     f"(guard rejected uniform fill)")
                return inpaint_report_free_text_fast(page, boxes)
            alpha = feather_alpha_field(mask, REPORT_FREE_TEXT_FEATHER)
            page.paste(Image.fromarray(blend_pixel(original, inner, alpha)),
                       (crop[0], crop[1]))
            return page
        except Exception as e:
            _log(f"[inpaint] route=push_pull reason=neural_exhausted "
                 f"crop={side}x{side} offset={crop[0]},{crop[1]} "
                 f"tensor={side}x{side} boxes={len(boxes)} (neural failed: {e})")
            return inpaint_report_free_text_fast(page, boxes)


def inpaint_free_text(page: Image.Image, detector_boxes, mode: str,
                      paddle_det, aot: "AotInpainter | None" = None) -> tuple[dict, np.ndarray]:
    """AOTInpainting.inpaintRegions free-text leg: refine -> cluster ->
    per group neural (QUALITY with a session) else push/pull. mode is
    "QUALITY" | "FAST" (the InpaintingMode enum name). Never raises.
    Returns (stats, free_text_mask)."""
    w, h = page.size
    free_mask = np.zeros((h, w), bool)
    if not detector_boxes:
        return {"freeDets": 0, "rawGroups": 0, "clusteredGroups": 0,
                "paddleLines": 0, "fallback": 0, "neural": 0, "push_pull": 0,
                "detMs": 0.0}, free_mask
    if paddle_det is not None:
        groups, paddle_lines, fallback, det_ms = refine_free_text_groups(
            page, detector_boxes, paddle_det)
    else:
        groups = [[list(b)] for b in detector_boxes]
        paddle_lines, fallback, det_ms = 0, len(detector_boxes), 0.0
        _log("[inpaint] report Paddle det unavailable — free-text groups use "
             "raw detector boxes")
    clustered = cluster_free_text_groups(groups, REPORT_AOT_CONTEXT)
    _log(f"[inpaint] pipeline=investigation_report freeDets={len(detector_boxes)} "
         f"rawGroups={len(groups)} clusteredGroups={len(clustered)} mode={mode} "
         f"fixed={aot is not None} dynamic=False")
    if paddle_det is not None:
        _log(f"[inpaint] report_paddle detectorText={len(detector_boxes)} "
             f"paddleLines={paddle_lines} fallback={fallback} "
             f"linePad={REPORT_FREE_TEXT_PAD} lineDilate={REPORT_FREE_TEXT_DILATE} "
             f"aotContext={REPORT_AOT_CONTEXT} thresh={PADDLE_THRESH} "
             f"boxThresh={PADDLE_BOX_THRESH} cropPad={PADDLE_CROP_PAD}")
        _log(f"[inpaint] report_paddle timing: det={det_ms:.0f}ms total, "
             f"{det_ms / max(1, len(detector_boxes)):.0f}ms per free-text box")
    neural = mode == "QUALITY" and aot is not None
    stats = {"freeDets": len(detector_boxes), "rawGroups": len(groups),
             "clusteredGroups": len(clustered), "paddleLines": paddle_lines,
             "fallback": fallback, "neural": 0, "push_pull": 0, "detMs": det_ms}
    for group in clustered:
        if not group:
            continue
        # Record mask for this group
        bnds = padded_union_bounds(group, w, h, REPORT_PUSH_PULL_CONTEXT)
        if bnds is not None:
            cw, ch = bnds[2] - bnds[0], bnds[3] - bnds[1]
            if cw > 0 and ch > 0:
                loc = [lb for lb in (localize_box(b, bnds[0], bnds[1], cw, ch) for b in group) if lb is not None]
                m = build_fixed_pill_mask(loc, cw, ch, REPORT_FREE_TEXT_PAD, REPORT_FREE_TEXT_DILATE)
                free_mask[bnds[1]:bnds[3], bnds[0]:bnds[2]] |= m
        if neural:
            stats["neural"] += len(group)
            aot.inpaint_report_free_text_neural(page, group)
        else:
            reason = "fast_mode" if mode != "QUALITY" else "no_neural_session"
            _log(f"[inpaint] route=push_pull reason={reason} boxes={len(group)}")
            stats["push_pull"] += len(group)
            inpaint_report_free_text_fast(page, group)
    return stats, free_mask


def inpaint_page_pipeline(
    page: Image.Image,
    regions: list[dict],
    raw_detections: list[list] | None = None,
    seg_masks: list | None = None,
    mode: str = "FAST",
    paddle_det = None,
    aot: "AotInpainter | None" = None,
    bubble_erosion: int = BUBBLE_SEG_MASK_EROSION,
) -> tuple[Image.Image, np.ndarray, dict]:
    """Constant-for-constant port of Android PageInpaintingEngine + AOTInpainting:
    1. Extracts bubble boxes (raw label 0 boxes + parent bubbles).
    2. Classifies text regions into bubble text vs free text using BoxGeometry.
    3. Inpaints speech bubbles with AotReportBubbleFill over segmentation masks (or dynamic pill masks).
    4. Refines free text with PaddleOCR det lines -> clusters -> PushPullGradient (FAST) or AOT-512 (QUALITY).
    Returns (cleaned_image, combined_mask, stats)."""
    import boxgeom
    result = page.copy()
    w, h = page.size

    raw_boxes = raw_detections or []
    bubble_dets = [boxgeom.Box(*[int(v) for v in r[2:]])
                   for r in raw_boxes if len(r) >= 6 and int(r[0]) == 0]

    bubble_boxes_to_erase = []
    free_boxes_to_erase = []

    # Also check overlap with seg_masks
    def overlaps_seg(b: boxgeom.Box) -> bool:
        if not seg_masks:
            return False
        for m in seg_masks:
            if hasattr(m, "labels"):
                gy1, gx1 = max(0, b.y1), max(0, b.x1)
                gy2, gx2 = min(h, b.y2), min(w, b.x2)
                if gy2 > gy1 and gx2 > gx1:
                    if (m.labels[gy1:gy2, gx1:gx2] > 0).any():
                        return True
        return False

    for r in regions:
        box = [int(v) for v in r["box"]]
        lbl = int(r.get("label", 2))
        b = boxgeom.Box(*box)
        parent = boxgeom.select_parent(b, bubble_dets)
        if parent is not None or boxgeom.overlaps_any_bubble(b, bubble_dets) or overlaps_seg(b):
            bubble_boxes_to_erase.append(box)
        elif lbl == 2 or lbl == 1:
            # Render-aware erase (PageInpaintingPlanner.kt:74-86):
            # If OCR has run and read blank/whitespace text, do not erase to preserve original art.
            if "text" in r and not (r["text"] or "").strip():
                continue
            free_boxes_to_erase.append(box)
        else:
            bubble_boxes_to_erase.append(box)

    # Extra detector bubbles
    for bb in bubble_dets:
        bubble_boxes_to_erase.append(bb.as_list())

    # 1. Inpaint bubbles via AotReportBubbleFill and segmentation masks (with edge erosion)
    result, bubble_mask = inpaint_report_bubbles(result, bubble_boxes_to_erase, seg_masks=seg_masks, erosion=bubble_erosion)

    # 2. Inpaint free text via PushPull / AOT-512
    free_stats, free_mask = inpaint_free_text(result, free_boxes_to_erase, mode=mode,
                                              paddle_det=paddle_det, aot=aot)

    total_mask = bubble_mask | free_mask
    stats = {
        "bubbleBoxes": len(bubble_boxes_to_erase),
        "freeBoxes": len(free_boxes_to_erase),
        "mode": mode,
        **free_stats,
    }
    return result, total_mask, stats

