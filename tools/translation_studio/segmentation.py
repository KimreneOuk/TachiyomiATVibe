"""Bubble segmentation, shape-aware cleaning, render color estimation.

Constant-for-constant desktop port of the Android bubble path so studio
renders mirror app output. Sources (app/src/main/java/eu/kanade/translation/):
  * segmentation/OnnxBubbleSegmenter.kt       -- ONNX protocol: "images"
    [1,3,640,640], /255 CHW (no mean/std), letterbox fill 0xFF727272,
    output0 [1,37,8400] + output1 [1,32,160,160] (validated, else error).
    Model: models/segmentation/manga109_bubble_int8.onnx (OnnxModelStore
    copies it as bubble_segmenter.onnx; production is CPU-EP primary).
  * segmentation/BubbleSegmentationDecoder.kt -- YOLO11-seg decode: xywh +
    1 balloon confidence + 32 mask coefficients; conf >= 0.35, w/h > 1,
    greedy NMS IoU > 0.5, mask = sigmoid(coef . prototypes) >= 0.5 sampled
    at nearest prototype pixel, thresholded in page space inside the
    candidate box only.
  * segmentation/MaskGeometry.kt              -- 8-connectivity components,
    MAX_COMPONENTS_PER_MASK = 64 (over -> geometry unusable),
    componentForRectangleDeterministic (max overlap -> nearest bounds
    center -> lower id; zero overlap -> nearest bounds center).
  * inpainting/bubble/SmartBubbleTextCleaner.kt (+ BubbleMaskBuilder,
    BubbleCleanerMath) -- shape-aware bubble interior cleaning: ring stats,
    seeded 2-means text mask, contrast mask union, faint recovery, disk
    dilation, distance-field feather, local (kernel) background fill.
  * rendering/RenderColorEstimator.kt         -- seeded 2-means over padded
    crop (parented pad >= 16, unparented tight), Rec.601 luma < 85 -> pure
    white fill else pure black; stroke = luma-inverse of fill.
  * rendering/TextLayoutPlanner.kt            -- stroke width =
    max(2 * scale, fontSizePx * 0.12).

Deviations (documented where they occur):
  * Dense numpy masks replace BubbleMaskRle (RLE exists for Android memory
    budgets; component/assignment math is unchanged).
  * scipy distance_transform_edt replaces the (3,4) chamfer field in
    BubbleMaskBuilder.featherAlphaField (~8% metric difference, same ramp).
Deps: numpy + scipy + onnxruntime only (no torch, no cv2).
"""
from __future__ import annotations

import math
from dataclasses import dataclass
from pathlib import Path

import numpy as np
from PIL import Image
from scipy import ndimage

REPO_ROOT = Path(__file__).resolve().parents[2]
SEGMENTER_MODEL = (REPO_ROOT / "app/src/main/assets/models/segmentation"
                   / "manga109_bubble_int8.onnx")

# ---- BubbleSegmentationDecoder.kt ----
INPUT_SIZE = 640
PROTOTYPE_CHANNELS = 32
CONFIDENCE_INDEX = 4
COEFFICIENT_START = 5
CONFIDENCE_THRESHOLD = 0.35        # decode() default
NMS_IOU_THRESHOLD = 0.5            # decode() default
MASK_THRESHOLD = 0.5               # decode() default
LETTERBOX_GRAY = 114               # OnnxBubbleSegmenter drawColor 0xFF727272

# ---- MaskGeometry.kt ----
MAX_COMPONENTS_PER_MASK = 64

# ---- SmartBubbleTextCleaner.kt constructor defaults ----
CONTEXT_PAD = 10
TEXT_MASK_PAD = 2
COLOR_DISTANCE_THRESHOLD = 45.0
DILATION_ITERATIONS = 3            # 2->3: cover anti-aliased faint strokes
FEATHER_RADIUS = 6
MIN_OCR_TEXT_MASK_COVERAGE = 3.0   # percent
RING_PAD_EXTRA = 6                 # ring box inflation = textMaskPad + 6
FAINT_FLOOR_THRESHOLD = 12.0       # recoverFaintTextMask
TIGHT_DIFFERENCE_EPSILON = 8       # tightDifferenceMask (L1)

# ---- BubbleCleanerMath ----
FEATHER_MIN_DIV = 12               # scaledMorphology: minDim / 12
DILATION_MIN_DIV = 20              # scaledMorphology: minDim / 12 / 20 caps

# ---- RenderColorEstimator.kt ----
DARK_BG_LUMA = 85.0                # Rec.601 background luma threshold
COLOR_SAMPLE_BUDGET = 1200         # step = max(1, pixels / 1200)
PARENT_PAD_MIN = 12                # parented basePad floor
PARENT_PAD_MIN_FINAL = 16          # parented final pad floor
UNPARENTED_PAD_DIV = 8             # max(2, minDim / 8)
BUBBLE_ERODE_DIV = 10              # bubbleInteriorMask: max(2, minDim / 10)

# ---- TextLayoutPlanner.kt ----
STROKE_WIDTH_FRACTION = 0.12
MIN_STROKE_PX = 2.0

_STRUCT_8 = np.ones((3, 3), bool)


def compute_stroke_width(font_px: float, scale: float = 1.0) -> float:
    """TextLayoutPlanner.computeStrokeWidth: fixed fraction of the fitted
    font size, floored so it never collapses below a visible pixel."""
    return max(MIN_STROKE_PX * scale, font_px * STROKE_WIDTH_FRACTION)


# ===================================================================== model
class BubbleSegmenter:
    """OnnxBubbleSegmenter: lazy session, one page -> list[BubbleMask]."""

    def __init__(self):
        self._sess = None

    @property
    def ready(self) -> bool:
        return SEGMENTER_MODEL.exists()

    def _session(self):
        if self._sess is None:
            import onnxruntime as ort
            opts = ort.SessionOptions()
            opts.log_severity_level = 3
            self._sess = ort.InferenceSession(
                str(SEGMENTER_MODEL), opts, providers=["CPUExecutionProvider"])
            ins = {i.name for i in self._sess.get_inputs()}
            outs = self._sess.get_outputs()
            if ins != {"images"} or len(outs) != 2:
                raise RuntimeError(
                    f"bubble segmenter contract changed: in={ins} "
                    f"outs={[o.name for o in outs]}")
        return self._sess

    # -- BubbleSegmentationDecoder.letterboxFor --
    @staticmethod
    def letterbox_for(width: int, height: int) -> tuple[float, int, int]:
        ratio = min(INPUT_SIZE / width, INPUT_SIZE / height)
        pad_x = (INPUT_SIZE - int(width * ratio)) // 2
        pad_y = (INPUT_SIZE - int(height * ratio)) // 2
        return ratio, pad_x, pad_y

    def segment(self, img: Image.Image) -> list["BubbleMask"]:
        """Full decode for one page. Raises on contract violations; the
        caller degrades to rect-fill, never fails the page."""
        sess = self._session()
        page_w, page_h = img.size
        ratio, pad_x, pad_y = self.letterbox_for(page_w, page_h)

        # Letterbox onto gray 114 (drawColor 0xFF727272), bilinear (the app
        # draws with FILTER_BITMAP_FLAG), /255 CHW.
        canvas = Image.new("RGB", (INPUT_SIZE, INPUT_SIZE),
                           (LETTERBOX_GRAY,) * 3)
        rw, rh = int(page_w * ratio), int(page_h * ratio)
        canvas.paste(img.convert("RGB").resize((rw, rh), Image.BILINEAR),
                     (pad_x, pad_y))
        arr = np.asarray(canvas, np.float32) / np.float32(255.0)
        x = np.ascontiguousarray(arr.transpose(2, 0, 1))[None]

        out0, out1 = sess.run(None, {"images": x})
        # OrtSessionHandle contract: exact shapes, else the app throws.
        if out0.shape != (1, 37, 8400) or out1.shape != (1, 32, 160, 160):
            raise RuntimeError(
                f"unsupported bubble output shapes {out0.shape}/{out1.shape};"
                f" expected [1,37,8400]/[1,32,160,160]")
        predictions = out0[0]                      # [37, 8400] channel-first
        prototypes = out1[0]                       # [32, 160, 160]

        candidates = _select_candidates(
            predictions, CONFIDENCE_THRESHOLD, NMS_IOU_THRESHOLD)
        masks = []
        for cx, cy, w, h, score, coef in candidates:
            m = _reconstruct_mask(cx=cx, cy=cy, w=w, h=h,
                                  score=score, coef=coef,
                                  prototypes=prototypes,
                                  ratio=ratio, pad_x=pad_x, pad_y=pad_y,
                                  page_w=page_w, page_h=page_h)
            if m is not None:
                masks.append(m)
        return masks


def _select_candidates(predictions: np.ndarray, conf_thresh: float,
                       nms_iou: float) -> list[tuple]:
    """BubbleSegmentationDecoder.selectCandidates: confidence gate + greedy
    score-ordered NMS. Prediction layout is channel-first: [0]=cx, [1]=cy,
    [2]=w, [3]=h, [4]=score, [5..36]=mask coefficients."""
    count = predictions.shape[1]
    cx = predictions[0]
    cy = predictions[1]
    w = predictions[2]
    h = predictions[3]
    score = predictions[CONFIDENCE_INDEX]
    keep = np.isfinite(score) & (score >= conf_thresh) \
        & np.isfinite(w) & np.isfinite(h) & (w > 1.0) & (h > 1.0)
    idx = np.where(keep)[0]
    coef = predictions[COEFFICIENT_START:COEFFICIENT_START + PROTOTYPE_CHANNELS]
    cands = sorted(
        ((float(cx[i]), float(cy[i]), float(w[i]), float(h[i]),
          float(score[i]), coef[:, i].copy()) for i in idx),
        key=lambda c: -c[4])
    # Greedy NMS: keep unless a kept candidate overlaps with IoU > threshold.
    kept: list[tuple] = []
    for cand in cands:
        if all(_candidate_iou(cand, k) <= nms_iou for k in kept):
            kept.append(cand)
    return kept


def _candidate_iou(a: tuple, b: tuple) -> float:
    ax1, ay1 = a[0] - a[2] / 2.0, a[1] - a[3] / 2.0
    ax2, ay2 = a[0] + a[2] / 2.0, a[1] + a[3] / 2.0
    bx1, by1 = b[0] - b[2] / 2.0, b[1] - b[3] / 2.0
    bx2, by2 = b[0] + b[2] / 2.0, b[1] + b[3] / 2.0
    iw = min(ax2, bx2) - max(ax1, bx1)
    ih = min(ay2, by2) - max(ay1, by1)
    if iw <= 0 or ih <= 0:
        return 0.0
    inter = iw * ih
    return inter / (a[2] * a[3] + b[2] * b[3] - inter)


def _reconstruct_mask(cx: float, cy: float, w: float, h: float, score: float,
                      coef: np.ndarray, prototypes: np.ndarray,
                      ratio: float, pad_x: int, pad_y: int,
                      page_w: int, page_h: int) -> "BubbleMask | None":
    """BubbleSegmentationDecoder.reconstructMask, vectorized per row strip.

    For each page pixel (x, y) inside the candidate box:
      inputX = x * ratio + padX; protoX = int(inputX / 640 * 160) clamped;
      masked when sigmoid(sum_c coef[c] * prototypes[c, protoY, protoX])
      >= MASK_THRESHOLD. Strips bound the [32, stripH, stripW] gather so a
      full-page box cannot spike memory.
    """
    # Candidate box is in 640-input space; project back to page space by
    # dividing by the letterbox ratio (reconstructMask: / letterbox.ratio).
    x1 = min(max(int((cx - w / 2.0 - pad_x) / ratio), 0), page_w)
    y1 = min(max(int((cy - h / 2.0 - pad_y) / ratio), 0), page_h)
    x2 = min(max(int((cx + w / 2.0 - pad_x) / ratio), 0), page_w)
    y2 = min(max(int((cy + h / 2.0 - pad_y) / ratio), 0), page_h)
    if x2 <= x1 or y2 <= y1:
        return None

    proto_h, proto_w = prototypes.shape[1], prototypes.shape[2]
    xs = np.arange(x1, x2, dtype=np.float64)
    # isMasked: protoX = int(inputX / 640 * protoW) coerced into range.
    proto_x = np.clip(
        ((xs * ratio + pad_x) / INPUT_SIZE * proto_w).astype(np.int64),
        0, proto_w - 1)
    mask = np.zeros((page_h, page_w), bool)
    strip = 64
    for sy in range(y1, y2, strip):
        ey = min(sy + strip, y2)
        ys = np.arange(sy, ey, dtype=np.float64)
        proto_y = np.clip(
            ((ys * ratio + pad_y) / INPUT_SIZE * proto_h).astype(np.int64),
            0, proto_h - 1)
        # nearest-prototype gather -> [32, stripH, stripW], then coef dot.
        sampled = prototypes[:, proto_y[:, None], proto_x[None, :]]
        logits = np.tensordot(coef, sampled, axes=([0], [0]))
        mask[sy:ey, x1:x2] = _expit(logits) >= MASK_THRESHOLD
    if not mask.any():
        return None
    return BubbleMask.build(mask, score)


def _expit(x: np.ndarray) -> np.ndarray:
    return 1.0 / (1.0 + np.exp(-np.clip(x, -60.0, 60.0)))


# ====================================================== MaskGeometry port
@dataclass
class MaskComponent:
    id: int
    bounds: tuple[int, int, int, int]   # (x1, y1, x2, y2), half-open
    area: int


class BubbleMask:
    """One decoded bubble instance: dense page-space mask + components.

    Component labeling mirrors MaskGeometry (8-connectivity: the Android
    union-find joins spans of adjacent rows on any horizontal overlap).
    Over MAX_COMPONENTS_PER_MASK the app discards the geometry
    (OrderedMaskFallbackReason.BUDGET_EXCEEDED) -- here components is left
    empty so regions fall back to the rect fill, same observable behavior.
    """

    def __init__(self, mask: np.ndarray, labels: np.ndarray,
                 components: list[MaskComponent], score: float):
        self.mask = mask
        self.labels = labels
        self.components = components
        self.score = score
        if mask.any():
            ys, xs = np.where(mask)
            self.bounds = (int(xs.min()), int(ys.min()),
                           int(xs.max()) + 1, int(ys.max()) + 1)
        else:
            self.bounds = (0, 0, 0, 0)

    @classmethod
    def build(cls, mask: np.ndarray, score: float) -> "BubbleMask":
        labels, n = ndimage.label(mask, structure=_STRUCT_8)
        components: list[MaskComponent] = []
        if 0 < n <= MAX_COMPONENTS_PER_MASK:
            areas = np.bincount(labels.ravel(), minlength=n + 1)
            slices = ndimage.find_objects(labels)
            for i, sl in enumerate(slices):
                components.append(MaskComponent(
                    id=i + 1,
                    bounds=(sl[1].start, sl[0].start,
                            sl[1].stop, sl[0].stop),
                    area=int(areas[i + 1])))
        else:
            labels = np.zeros_like(labels)
        return cls(mask, labels, components, score)

    def component_mask(self, component_id: int) -> np.ndarray:
        return self.labels == component_id

    def component_for_rectangle_deterministic(
            self, rect: tuple[int, int, int, int]) -> int | None:
        """MaskGeometry.componentForRectangleDeterministic: (1) unique
        max-overlap component; (2) tie -> nearest integer bounds center;
        (3) distance tie -> lower id; (4) zero overlap -> nearest bounds
        center over all components."""
        left, top, right, bottom = rect
        h, w = self.labels.shape
        l, t = max(0, left), max(0, top)
        r, b = min(w, right), min(h, bottom)
        n = len(self.components)
        if right <= left or bottom <= top or n == 0 or r <= l or b <= t:
            return None
        overlap = np.bincount(
            self.labels[t:b, l:r].ravel(), minlength=n + 1)
        overlap[0] = 0
        best = int(overlap.max())
        best_id = int(overlap.argmax()) if best > 0 else -1
        tied = best > 0 and int((overlap == best).sum()) > 1
        if best > 0 and not tied:
            return best_id if best_id > 0 else None

        tied_overlap = best
        center_x = (left + right) / 2.0
        center_y = (top + bottom) / 2.0
        nearest_id = -1
        nearest_dist = math.inf
        for comp in self.components:          # ascending id -> lower id wins
            if overlap[comp.id] != tied_overlap:
                continue
            bx1, by1, bx2, by2 = comp.bounds
            dx = (bx1 + bx2) / 2.0 - center_x
            dy = (by1 + by2) / 2.0 - center_y
            dist = dx * dx + dy * dy
            if dist < nearest_dist:
                nearest_dist = dist
                nearest_id = comp.id
        return nearest_id if nearest_id > 0 else None

    def component_overlap(self, component_id: int,
                          rect: tuple[int, int, int, int]) -> int:
        """Pixel count of component ∩ rect (BubbleMaskRle.overlapPixels
        analog, per component)."""
        l, t, r, b = rect
        h, w = self.labels.shape
        l, t = max(0, l), max(0, t)
        r, b = min(w, r), min(h, b)
        if r <= l or b <= t:
            return 0
        return int((self.labels[t:b, l:r] == component_id).sum())


# A region counts as parented only when a component covers a meaningful
# part of it (the app parents a block to the bubble CONTAINING it; a stray
# SFX grazing a bubble edge must stay unparented or its erase/layout would
# collapse onto the bubble boundary).
MIN_REGION_OVERLAP_FRACTION = 0.30


def assign_region_component(
        masks: list[BubbleMask],
        rect: tuple[int, int, int, int],
) -> tuple[BubbleMask, int] | None:
    """Pick the bubble instance whose deterministically-assigned component
    covers at least MIN_REGION_OVERLAP_FRACTION of `rect` (the app parents
    a block to its containing bubble). Returns None when no component does
    — the region stays unparented (rect erase / unparented color pad)."""
    left, top, right, bottom = rect
    area = max(1, (right - left) * (bottom - top))
    min_overlap = max(1, int(area * MIN_REGION_OVERLAP_FRACTION))
    best: tuple[BubbleMask, int] | None = None
    best_overlap = 0
    for m in masks:
        comp = m.component_for_rectangle_deterministic(rect)
        if comp is None:
            continue
        ov = m.component_overlap(comp, rect)
        if ov > best_overlap and ov >= min_overlap:
            best_overlap = ov
            best = (m, comp)
    return best


# ================================================ SmartBubbleTextCleaner
@dataclass
class _BackgroundStats:
    median_rgb: tuple[int, int, int]
    gray_mean: float
    gray_std: float
    near_white_ratio: float
    dark_pixel_ratio: float
    edge_density: float
    sample_count: int


def classify_background(s: _BackgroundStats) -> str:
    """BubbleCleanerMath.classifyBackground (thresholds pinned by tests)."""
    if s.dark_pixel_ratio > 0.5 or s.gray_mean < 80.0:
        if s.gray_std < 18 and s.edge_density < 0.06:
            return "dark_flat"
        if s.gray_std < 35 and s.edge_density < 0.12:
            return "dark_lightly_varying"
        return "dark_textured"
    if s.near_white_ratio > 0.7 and s.gray_std < 20:
        return "flat_white"
    if s.gray_std < 15 and s.near_white_ratio <= 0.7:
        return "flat_colored"
    if s.gray_std < 35 and s.edge_density < 0.08:
        return "lightly_varying"
    return "textured"


def should_use_solid_flat_fill(bg_type: str, s: _BackgroundStats) -> bool:
    """BubbleCleanerMath.shouldUseSolidFlatFill."""
    return bg_type == "flat_white" or (
        bg_type in ("flat_colored", "dark_flat")
        and s.gray_std < 10 and s.edge_density < 0.04)


def scaled_morphology(min_dim: int, feather_radius: int,
                      dilation_iterations: int) -> tuple[int, int]:
    """BubbleCleanerMath.scaledMorphology: caps floored at 2/1, scaled by
    minDim/12 and minDim/20 so small bubbles are not over-feathered."""
    feather = max(min(feather_radius, max(2, min_dim // 12)), 2)
    dilation = max(min(dilation_iterations, max(1, min_dim // 20)), 1)
    return feather, dilation


def scaled_text_mask_pad(min_dim: int, text_mask_pad: int) -> int:
    """BubbleCleanerMath.scaledTextMaskPad."""
    return max(text_mask_pad, min(8, min_dim // 8))


def remove_edge_touching_components(mask: np.ndarray) -> np.ndarray:
    """BubbleMaskBuilder.removeEdgeTouchingComponents: drop components that
    come within a 2px margin of the canvas border (8-connectivity)."""
    h, w = mask.shape
    labels, n = ndimage.label(mask, structure=_STRUCT_8)
    if n == 0:
        return np.zeros_like(mask)
    out = np.zeros_like(mask)
    slices = ndimage.find_objects(labels)
    for i, sl in enumerate(slices):
        y0, y1 = sl[0].start, sl[0].stop - 1
        x0, x1 = sl[1].start, sl[1].stop - 1
        if x0 <= 1 or y0 <= 1 or x1 >= w - 2 or y1 >= h - 2:
            continue
        out[sl] |= labels[sl] == (i + 1)
    return out


def dilate_mask_disk(mask: np.ndarray, radius: int) -> np.ndarray:
    """BubbleMaskBuilder.dilateMaskDisk: isotropic disk structuring element."""
    if radius <= 0:
        return mask.copy()
    yy, xx = np.ogrid[-radius:radius + 1, -radius:radius + 1]
    disk = (xx * xx + yy * yy) <= radius * radius
    return ndimage.binary_dilation(mask, structure=disk)


def feather_alpha(mask: np.ndarray, ramp_width: int) -> np.ndarray:
    """BubbleMaskBuilder.featherAlpha(Field): 1 inside the mask, linear ramp
    to 0 over ramp_width px outside. Deviation: Euclidean EDT instead of the
    (3,4) chamfer field (~8% distance difference, same monotonic ramp)."""
    alpha = np.zeros(mask.shape, np.float32)
    if not mask.any():
        return alpha
    ramp = max(2, ramp_width)
    dist = ndimage.distance_transform_edt(~mask)
    alpha = np.clip(1.0 - dist / ramp, 0.0, 1.0).astype(np.float32)
    alpha[mask] = 1.0
    return alpha


def _percentile_gray(hist: np.ndarray, count: int, percentile: float) -> int:
    """BubbleCleanerMath.percentileGray (256-bin histogram walk)."""
    target = int(round(count * percentile))
    target = min(max(target, 1), count)
    seen = 0
    for value in range(256):
        seen += int(hist[value])
        if seen >= target:
            return value
    return 255


def is_dominant_light_background(gray: np.ndarray,
                                 rect: tuple[int, int, int, int]) -> bool:
    """BubbleCleanerMath.isDominantLightBackground: median > 220 and
    p75 > 238 inside the rectangle."""
    x1, y1, x2, y2 = rect
    vals = gray[y1:y2, x1:x2].ravel()
    count = vals.size
    if count <= 0:
        return False
    hist = np.bincount(vals, minlength=256)
    return _percentile_gray(hist, count, 0.50) > 220 \
        and _percentile_gray(hist, count, 0.75) > 238


def _gray_of(px: np.ndarray) -> np.ndarray:
    """Android Rec.601 integer luma (r*299 + g*587 + b*114) / 1000."""
    return (px[..., 0].astype(np.int32) * 299
            + px[..., 1].astype(np.int32) * 587
            + px[..., 2].astype(np.int32) * 114) // 1000


def _seeded_two_means(samples: np.ndarray, iterations: int = 5
                      ) -> tuple[np.ndarray, np.ndarray, int, int]:
    """The shared 2-means of RenderColorEstimator.extractClusters /
    SmartBubbleTextCleaner.findBackgroundCluster: seeded black vs white,
    fixed iteration count, nearest-center by squared distance; ties go to
    cluster 0. Returns (center0, center1, count0, count1)."""
    center0 = np.zeros(3, np.float64)
    center1 = np.full(3, 255.0)
    count0 = count1 = 0
    for _ in range(iterations):
        d0 = ((samples - center0) ** 2).sum(axis=1)
        d1 = ((samples - center1) ** 2).sum(axis=1)
        sel0 = d0 < d1
        count0 = int(sel0.sum())
        count1 = int(samples.shape[0]) - count0
        if count0 > 0:
            center0 = samples[sel0].mean(axis=0)
        if count1 > 0:
            center1 = samples[~sel0].mean(axis=0)
    return center0, center1, count0, count1


def _sample_background_stats(px: np.ndarray, ring: np.ndarray
                             ) -> _BackgroundStats:
    """SmartBubbleTextCleaner.sampleBackgroundStats over the ring mask.
    Medians use the Android upper-middle element (sorted[count/2]), not the
    interpolated numpy median."""
    sel = px[ring]
    if sel.shape[0] == 0:
        return _BackgroundStats((255, 255, 255), 250.0, 5.0, 1.0, 0.0, 0.0, 0)
    gray = _gray_of(px)
    cand = ring.copy()
    cand[:, 0] = False
    cand[0, :] = False
    dl = np.zeros_like(gray)
    du = np.zeros_like(gray)
    dl[1:, 1:] = np.abs(gray[1:, 1:] - gray[1:, :-1])
    du[1:, 1:] = np.abs(gray[1:, 1:] - gray[:-1, 1:])
    edge_count = int((((dl + du) > 80) & cand)[ring & cand].sum())
    count = sel.shape[0]
    means = sel.reshape(-1, 3).astype(np.float64).mean(axis=0)
    gsel = gray[ring].astype(np.float64)
    gray_mean = float(gsel.mean())
    gray_std = math.sqrt(max(0.0, float((gsel ** 2).mean()) - gray_mean ** 2))
    return _BackgroundStats(
        median_rgb=tuple(int(np.sort(sel.reshape(-1, 3)[:, c])[count // 2])
                         for c in range(3)),
        gray_mean=gray_mean,
        gray_std=gray_std,
        near_white_ratio=float((gsel > 230).sum()) / count,
        dark_pixel_ratio=float((gsel < 60).sum()) / count,
        edge_density=edge_count / count,
        sample_count=count,
    )


def _generate_text_mask(px: np.ndarray, stats: _BackgroundStats,
                        zone: tuple[int, int, int, int]
                        ) -> np.ndarray:
    """SmartBubbleTextCleaner.generateTextMask: 2-means in-box background
    cluster; a pixel is text when it differs from the cluster OR the ring
    median beyond the threshold (the OR catches faint strokes the drifting
    in-box centroid misses). Low-contrast boxes lower the threshold to
    max(12, fgDistance * 0.4). Edge-touching components are dropped."""
    x1, y1, x2, y2 = zone
    zw, zh = x2 - x1, y2 - y1
    if zw <= 0 or zh <= 0:
        return np.zeros((0, 0), bool)
    zone_px = px[y1:y2, x1:x2].reshape(-1, 3).astype(np.float64)
    step = max(1, zone_px.shape[0] // 1000)     # findBackgroundCluster step
    c0, c1, n0, n1 = _seeded_two_means(zone_px[::step])
    bg_is_zero = n0 >= n1
    bg = c0 if bg_is_zero else c1
    fg = c1 if bg_is_zero else c0
    fg_dist = float(np.linalg.norm(fg - bg))
    thresh = COLOR_DISTANCE_THRESHOLD
    if fg_dist < thresh * 1.5:
        thresh = max(12.0, fg_dist * 0.4)
    ring = np.array(stats.median_rgb, np.float64)
    dist_cluster = np.sqrt(((zone_px - bg) ** 2).sum(axis=1))
    dist_ring = np.sqrt(((zone_px - ring) ** 2).sum(axis=1))
    mask = ((dist_cluster > thresh) | (dist_ring > thresh)).reshape(zh, zw)
    return remove_edge_touching_components(mask)


def _local_contrast_text_mask(px: np.ndarray, gray: np.ndarray,
                              rect: tuple[int, int, int, int],
                              aggressive: bool = False) -> np.ndarray:
    """SmartBubbleTextCleaner.buildLocalContrastTextMask: integral-image
    local mean/std stroke detector + component shape filters."""
    h, w = gray.shape
    x1, y1, x2, y2 = rect
    zw, zh = x2 - x1, y2 - y1
    if zw <= 0 or zh <= 0:
        return np.zeros((h, w), bool)
    radius = min(max(min(zw, zh) // 7, 4), 14 if aggressive else 10)
    dominant_light = is_dominant_light_background(gray, rect)

    # Sliding-window mean/std via summed-area tables (BubbleCleanerMath
    # .rectSum), evaluated for every pixel's own clamped window.
    sat = np.zeros((h + 1, w + 1), np.int64)
    sat[1:, 1:] = np.cumsum(np.cumsum(gray, axis=0), axis=1)
    ys = np.arange(y1, y2)[:, None]
    xs = np.arange(x1, x2)[None, :]
    sy1 = np.clip(ys - radius, 0, h)
    sy2 = np.clip(ys + radius + 1, 0, h)
    sx1 = np.clip(xs - radius, 0, w)
    sx2 = np.clip(xs + radius + 1, 0, w)
    win = (sy2 - sy1) * (sx2 - sx1)
    s = (sat[sy2, sx2] - sat[sy1, sx2] - sat[sy2, sx1] + sat[sy1, sx1])
    sq = np.zeros((h + 1, w + 1), np.int64)
    g64 = gray.astype(np.int64)
    sq[1:, 1:] = np.cumsum(np.cumsum(g64 * g64, axis=0), axis=1)
    s2 = (sq[sy2, sx2] - sq[sy1, sx2] - sq[sy2, sx1] + sq[sy1, sx1])
    mean = s / np.maximum(win, 1)
    var = s2 / np.maximum(win, 1) - mean * mean
    std = np.sqrt(np.maximum(var, 0.0))
    value = gray[y1:y2, x1:x2].astype(np.float64)
    delta = np.abs(value - mean)
    threshold = (np.maximum(10.0, np.minimum(34.0, std * 0.70 + 7.0))
                 if aggressive else
                 np.maximum(14.0, np.minimum(42.0, std * 0.85 + 10.0)))
    dark = (value < mean - threshold) | ((value < 82.0) & (mean > 110.0))
    light = (~dominant_light) & (value > mean + threshold) \
        & (value > 172.0) & (mean < 205.0)
    zone = dark | light
    strong = (value < 92.0) | (value > 232.0)

    zone = _suppress_long_thin_bands(zone, strong)
    zone = _filter_text_like_components(zone, aggressive)
    out = np.zeros((h, w), bool)
    out[y1:y2, x1:x2] = zone
    return out


def _suppress_long_thin_bands(zone: np.ndarray, strong: np.ndarray
                              ) -> np.ndarray:
    """SmartBubbleTextCleaner.suppressLongThinBands: in rows/cols wider than
    55% (floor 12px), keep only strong-text pixels (panel/screentone guard)."""
    out = zone.copy()
    h, w = zone.shape
    row_thresh = max(int(round(w * 0.55)), 12)
    col_thresh = max(int(round(h * 0.55)), 12)
    row_counts = zone.sum(axis=1)
    for y in np.where(row_counts >= row_thresh)[0]:
        out[y, :] &= strong[y, :]
    col_counts = zone.sum(axis=0)
    for x in np.where(col_counts >= col_thresh)[0]:
        out[:, x] &= strong[:, x]
    return out


def _filter_text_like_components(zone: np.ndarray, aggressive: bool
                                 ) -> np.ndarray:
    """SmartBubbleTextCleaner.filterTextLikeComponents: drop texture dots,
    panel/hatch spans, band artifacts and oversized blobs."""
    h, w = zone.shape
    min_area = 2 if aggressive else max(3, int(round(w * h * 0.00008)))
    max_area = max(16, int(round(w * h * 0.60)))
    labels, n = ndimage.label(zone, structure=_STRUCT_8)
    if n == 0:
        return zone
    out = np.zeros_like(zone)
    areas = np.bincount(labels.ravel())
    slices = ndimage.find_objects(labels)
    for i, sl in enumerate(slices):
        area = int(areas[i + 1])
        cw = sl[1].stop - sl[1].start
        ch = sl[0].stop - sl[0].start
        x0, y0 = sl[1].start, sl[0].start
        x1, y1 = sl[1].stop - 1, sl[0].stop - 1
        touches_edge = x0 <= 1 or y0 <= 1 or x1 >= w - 2 or y1 >= h - 2
        texture_dot = area < min_area and cw <= 3 and ch <= 3
        panel_hatch = touches_edge and (cw > w * 0.65 or ch > h * 0.65)
        band = (ch <= 3 and cw > w * 0.55) or (cw <= 3 and ch > h * 0.55)
        too_large = area > max_area or (cw > w * 0.95 and ch > h * 0.75)
        if not (texture_dot or panel_hatch or band or too_large):
            out[sl] |= labels[sl] == (i + 1)
    return out


def _directional_background(px: np.ndarray, fill_mask: np.ndarray,
                            bg_source: np.ndarray | None) -> np.ndarray:
    """SmartBubbleTextCleaner.buildDirectionalBackground: per-pixel blend of
    the nearest unmasked anchor in each of the 4 directions, weight 1/dist.
    Anchors respect bgSourceMask so the scan cannot pull colors across the
    bubble boundary. Pixels with no anchor on any side stay 0 (the caller
    falls back to the ring median, the Android degenerate branch).
    Vectorized as a running-max anchor-index scan per axis."""
    h, w = fill_mask.shape
    anchor = ~fill_mask if bg_source is None else (~fill_mask) & bg_source

    def scan(axis: int, flip: bool) -> tuple[np.ndarray, np.ndarray,
                                             np.ndarray]:
        if axis == 1:                       # along columns, per row
            n = w
            a = anchor[:, ::-1] if flip else anchor
            base = np.arange(n, dtype=np.int64)
            ai = np.where(a, (n - 1) - base if flip else base, -1)
            run = np.maximum.accumulate(ai, axis=1)
            if flip:
                run = (n - 1) - run[:, ::-1]
        else:                               # along rows, per column
            n = h
            a = anchor[::-1, :] if flip else anchor
            base = np.arange(n, dtype=np.int64)
            ai = np.where(a, (n - 1) - base if flip else base, -1)
            run = np.maximum.accumulate(ai, axis=0)
            if flip:
                run = (n - 1) - run[::-1, :]
        has = run >= 0
        grid = (np.arange(w)[None, :] if axis == 1 else np.arange(h)[:, None])
        dist = np.abs(grid - np.where(has, run, 0))
        return run, dist, has

    left = scan(1, False)
    right = scan(1, True)
    top = scan(0, False)
    bottom = scan(0, True)

    out = np.zeros((h, w, 3), np.float64)
    weight = np.zeros((h, w), np.float64)
    rows = np.arange(h)[:, None]
    cols = np.arange(w)[None, :]
    for run_i, dist, has in (left, right, top, bottom):
        if run_i.ndim == 2:                 # horizontal: per-row column idx
            colors = px[rows, np.clip(run_i, 0, w - 1)]
        else:                               # vertical: per-column row idx
            colors = px[np.clip(run_i, 0, h - 1), cols]
        wgt = np.where(has, 1.0 / np.maximum(dist, 1), 0.0)
        out += colors * wgt[..., None]
        weight += wgt
    ok = weight > 1e-3
    filled = np.zeros_like(out)
    filled[ok] = np.clip(np.rint(out[ok] / weight[ok, None]), 0, 255)
    return filled


class SmartBubbleCleaner:
    """SmartBubbleTextCleaner.cleanSingleRegion for bubble text erase.

    The erase mask is TEXT-SHAPED (heuristic + contrast masks), never the
    solid box; `bg_source_mask` constrains background sampling to the bubble
    interior (buildLocalBackground.bgSourceMask -- the color-bleed fix).
    """

    def __init__(self):
        self.context_pad = CONTEXT_PAD
        self.text_mask_pad = TEXT_MASK_PAD
        self.color_distance_threshold = COLOR_DISTANCE_THRESHOLD
        self.dilation_iterations = DILATION_ITERATIONS
        self.feather_radius = FEATHER_RADIUS

    def clean_region(self, page: np.ndarray, box: tuple[int, int, int, int],
                     component_mask: np.ndarray | None = None) -> str:
        """Clean one region box on the page RGB array (mutated in place).
        `component_mask` (page-space bool) bounds the erase to this bubble's
        component and seeds the background source. Returns a log line."""
        h, w = page.shape[:2]
        x1, y1, x2, y2 = (max(0, box[0]), max(0, box[1]),
                          min(w, box[2]), min(h, box[3]))
        if x2 <= x1 or y2 <= y1:
            return "clean skip: empty box"
        pad = self.context_pad
        cx1, cy1 = max(0, x1 - pad), max(0, y1 - pad)
        cx2, cy2 = min(w, x2 + pad), min(h, y2 + pad)
        ctx = page[cy1:cy2, cx1:cx2].copy()
        ch, cw = ctx.shape[:2]

        lx1, ly1, lx2, ly2 = x1 - cx1, y1 - cy1, x2 - cx1, y2 - cy1
        min_dim = max(1, min(lx2 - lx1, ly2 - ly1))
        mp = scaled_text_mask_pad(min_dim, self.text_mask_pad)
        ex1, ey1 = max(0, lx1 - mp), max(0, ly1 - mp)
        ex2, ey2 = min(cw, lx2 + mp), min(ch, ly2 + mp)
        if ex2 <= ex1 or ey2 <= ey1:
            return "clean skip: empty zone"

        # Ring stats: everything except a 2px border and the box inflated by
        # textMaskPad + 6 (real background, not the text-filled box).
        ring_mp = self.text_mask_pad + RING_PAD_EXTRA
        ring = np.ones((ch, cw), bool)
        ring[:2, :] = False
        ring[-2:, :] = False
        ring[:, :2] = False
        ring[:, -2:] = False
        ring[max(0, ly1 - ring_mp):min(ch, ly2 + ring_mp),
             max(0, lx1 - ring_mp):min(cw, lx2 + ring_mp)] = False
        stats = _sample_background_stats(ctx, ring)
        bg_type = classify_background(stats)

        combined = np.zeros((ch, cw), bool)
        zone_mask = _generate_text_mask(ctx, stats, (ex1, ey1, ex2, ey2))
        combined[ey1:ey2, ex1:ex2] = zone_mask
        if not combined.any():
            # Faint recovery keyed off the ring median (threshold 45 -> 12),
            # then the tight per-pixel difference fallback (epsilon 8).
            recovered = _recover_faint_text_mask(ctx, stats,
                                                 (ex1, ey1, ex2, ey2))
            if recovered.any():
                combined[ey1:ey2, ex1:ex2] = recovered
            else:
                combined[ey1:ey2, ex1:ex2] = _tight_difference_mask(
                    ctx, stats, (ex1, ey1, ex2, ey2))

        gray = _gray_of(ctx)
        combined |= _local_contrast_text_mask(ctx, gray, (ex1, ey1, ex2, ey2))

        feather, dilation = scaled_morphology(
            min_dim, self.feather_radius, self.dilation_iterations)
        final_mask = dilate_mask_disk(combined, dilation)
        coverage = 100.0 * float(final_mask.sum()) / final_mask.size
        if coverage < MIN_OCR_TEXT_MASK_COVERAGE:
            final_mask |= _local_contrast_text_mask(
                ctx, gray, (ex1, ey1, ex2, ey2), aggressive=False)
            coverage = 100.0 * float(final_mask.sum()) / final_mask.size
            if coverage < MIN_OCR_TEXT_MASK_COVERAGE:
                # The app logs and STILL fills (no early return).
                pass

        # Bubble-shape constraint: the erase can never leave the component
        # (the dilated text mask may spill past a bubble that hugs its text).
        comp_crop = None
        if component_mask is not None:
            comp_crop = component_mask[cy1:cy2, cx1:cx2]
            final_mask &= comp_crop

        alpha = feather_alpha(final_mask, feather)
        fill_mask = alpha > 0.0

        # Background source: component eroded by max(2, minDim/10)
        # (BoundaryAwarePipeline BUBBLE_ERODE_FRACTION) so background
        # interpolation stays inside the bubble.
        bg_source = None
        if comp_crop is not None and comp_crop.any():
            erode = max(2, min(comp_crop.shape[0], comp_crop.shape[1])
                        // BUBBLE_ERODE_DIV)
            yy, xx = np.ogrid[-erode:erode + 1, -erode:erode + 1]
            disk = (xx * xx + yy * yy) <= erode * erode
            bg_source = ndimage.binary_erosion(comp_crop, structure=disk)

        bg = _build_local_background(
            ctx, fill_mask, stats.median_rgb,
            prefer_flat=should_use_solid_flat_fill(bg_type, stats),
            bg_source=bg_source, feather_radius=self.feather_radius)

        inv = (1.0 - alpha)[..., None]
        blended = np.clip(np.rint(ctx * inv + bg * alpha[..., None]),
                          0, 255).astype(np.uint8)
        sel = alpha > 0.0
        ctx[sel] = blended[sel]
        page[cy1:cy2, cx1:cx2] = ctx
        return (f"clean bg={bg_type} std={stats.gray_std:.1f} "
                f"mask={coverage:.1f}% roi={cw}x{ch}")


def _recover_faint_text_mask(px: np.ndarray, stats: _BackgroundStats,
                             zone: tuple[int, int, int, int]) -> np.ndarray:
    """SmartBubbleTextCleaner.recoverFaintTextMask: lowered threshold (45 ->
    12) keyed off the ring median; edge-touching components dropped."""
    x1, y1, x2, y2 = zone
    zone_px = px[y1:y2, x1:x2].astype(np.float64)
    ring = np.array(stats.median_rgb, np.float64)
    dist = np.sqrt(((zone_px - ring) ** 2).sum(axis=2))
    return remove_edge_touching_components(dist > FAINT_FLOOR_THRESHOLD)


def _tight_difference_mask(px: np.ndarray, stats: _BackgroundStats,
                           zone: tuple[int, int, int, int]) -> np.ndarray:
    """SmartBubbleTextCleaner.tightDifferenceMask: per-pixel L1 > 8 vs the
    ring median (never the whole box; preserves inter-stroke background)."""
    x1, y1, x2, y2 = zone
    zone_px = px[y1:y2, x1:x2].astype(np.int32)
    ring = np.array(stats.median_rgb, np.int32)
    l1 = np.abs(zone_px - ring).sum(axis=2)
    return l1 > TIGHT_DIFFERENCE_EPSILON


def _build_local_background(px: np.ndarray, fill_mask: np.ndarray,
                            median_rgb: tuple[int, int, int],
                            prefer_flat: bool,
                            bg_source: np.ndarray | None,
                            feather_radius: int) -> np.ndarray:
    """SmartBubbleTextCleaner.buildLocalBackground: per-pixel Gaussian-
    kernel (r = max(2, featherRadius), sigma r/2) background interpolation
    from unmasked source pixels; directional scan when a pixel's kernel has
    no source; ring median as the degenerate last resort. The kernel
    correlation is separable, so two 1-D passes replace the O(k^2) loop."""
    h, w = fill_mask.shape
    median = np.array(median_rgb, np.float64)
    if prefer_flat:
        bg = px.astype(np.float64)
        bg[fill_mask] = median
        return bg

    r = max(2, feather_radius)
    pos = np.arange(-r, r + 1, dtype=np.float64)
    kernel = np.exp(-(pos ** 2) / (2.0 * (r / 2.0) * (r / 2.0)))

    source = ~fill_mask if bg_source is None else (~fill_mask) & bg_source
    src_f = source.astype(np.float64)
    acc = np.zeros((h, w, 3), np.float64)
    weight = np.zeros((h, w), np.float64)
    for c in range(3):
        chan = px[..., c].astype(np.float64) * src_f
        tmp = ndimage.correlate1d(chan, kernel, axis=1, mode="constant")
        acc[..., c] = ndimage.correlate1d(tmp, kernel, axis=0, mode="constant")
    wtmp = ndimage.correlate1d(src_f, kernel, axis=1, mode="constant")
    weight = ndimage.correlate1d(wtmp, kernel, axis=0, mode="constant")

    bg = px.astype(np.float64)
    ok = weight > 1e-3
    bg[ok] = np.clip(np.rint(acc[ok] / weight[ok, None]), 0, 255)
    need = fill_mask & ~ok
    if need.any():
        # Directional 4-way nearest-anchor blend; its zero-weight pixels fall
        # back to the ring median (the Android degenerate branch).
        dir_bg = _directional_background(px, fill_mask, bg_source)
        bg[need] = dir_bg[need]
        degenerate = need & (dir_bg.sum(axis=2) == 0)
        bg[degenerate] = median
    return bg


# ================================================== RenderColorEstimator
def estimate_text_color(page: np.ndarray, box: tuple[int, int, int, int],
                        parent_bounds: tuple[int, int, int, int] | None = None
                        ) -> tuple[tuple[int, int, int], tuple[int, int, int],
                                   float]:
    """RenderColorEstimator.estimate + colorPolicy + renderer stroke rule.

    Seeded 2-means over the padded crop (optionally restricted to the eroded
    parent-bubble interior); fill snapped to pure white on dark background
    (Rec.601 luma < 85) else pure black; stroke = luma-inverse of fill.
    Returns (fill_rgb, stroke_rgb, bg_luma).
    """
    h, w = page.shape[:2]
    x1, y1, x2, y2 = box
    box_w, box_h = max(1, x2 - x1), max(1, y2 - y1)
    if parent_bounds is not None:
        base_pad = max(PARENT_PAD_MIN, min(box_w, box_h) // 2)
        pad = max(base_pad, PARENT_PAD_MIN_FINAL)
    else:
        pad = max(2, min(box_w, box_h) // UNPARENTED_PAD_DIV)
    left = min(max(x1 - pad, 0), w)
    top = min(max(y1 - pad, 0), h)
    right = min(max(x2 + pad, left), w)
    bottom = min(max(y2 + pad, top), h)
    if right <= left or bottom <= top:
        return (0, 0, 0), (255, 255, 255), 255.0

    crop = page[top:bottom, left:right].reshape(-1, 3).astype(np.float64)

    sample_mask = None
    if parent_bounds is not None:
        bw = parent_bounds[2] - parent_bounds[0]
        bh = parent_bounds[3] - parent_bounds[1]
        erode = max(2, min(bw, bh) // BUBBLE_ERODE_DIV)
        sx1 = max(0, parent_bounds[0] + erode - left)
        sy1 = max(0, parent_bounds[1] + erode - top)
        sx2 = min(right - left, parent_bounds[2] - erode - left)
        sy2 = min(bottom - top, parent_bounds[3] - erode - top)
        if sx2 > sx1 and sy2 > sy1:
            sample_mask = np.zeros((bottom - top, right - left), bool)
            sample_mask[sy1:sy2, sx1:sx2] = True

    if sample_mask is not None:
        flat_mask = sample_mask.reshape(-1)
        if not flat_mask.any():
            flat_mask = None
    else:
        flat_mask = None
    # extractClusters: stride over ALL crop indices, mask filter inside the
    # loop (not a stride over the masked subset).
    step = max(1, (bottom - top) * (right - left) // COLOR_SAMPLE_BUDGET)
    samples = crop[::step]
    if flat_mask is not None:
        samples = samples[flat_mask[::step]]
        if samples.shape[0] == 0:
            samples = crop
    c0, c1, n0, n1 = _seeded_two_means(samples)
    bg, fg = (c0, c1) if n0 >= n1 else (c1, c0)

    bg_luma = 0.299 * bg[0] + 0.587 * bg[1] + 0.114 * bg[2]
    if bg_luma < DARK_BG_LUMA:
        fill, stroke = (255, 255, 255), (0, 0, 0)
    else:
        fill, stroke = (0, 0, 0), (255, 255, 255)
    return fill, stroke, bg_luma
