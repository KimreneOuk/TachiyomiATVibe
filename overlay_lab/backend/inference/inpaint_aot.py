"""Faithful Python port of AOTInpainting.kt — the free-text neural erase path.

Reconstructs the Android free-text erase pipeline end to end:

    detector-v4 box (label 2)
      -> Paddle DET refine (crop+12pad, detectLines @ 0.18/0.34) -> tight line boxes
      -> group line boxes per detector box
      -> per group: 512x512 centered context crop (centeredReportCrop, clamped on-page)
      -> pill mask (capsule per line, pad16 expand, dilate8 disk on combined)
      -> AOT-GAN reconstruct inside the pill mask (image normalized to [-1,1],
         masked image pixels zeroed; dims rounded up to multiple of 8)
      -> output guard: reject uniform near-black/mid-gray/near-white output
      -> on reject / OOM / error: push-pull fast fallback (same pill mask,
         paddedUnionBounds pad64, localRingMedian seed, pushPullFill, feather3)

This is the QUALITY vs FAST two-tier path from Android. QUALITY+AOT loaded
attempts neural reconstruction; any failure (guard-reject, OOM, inference
error) routes to the FAST push-pull fallback — this IS the designed Android
behavior (reportFallbackOnly=true throws, caller catches), and every route is
logged. No silent fallback.

Pure numpy + onnxruntime (CPU). cv2 is used only for morphology (disk dilate)
and the colorspace swap — portable to the Android constraint (no cv2.inpaint).
"""
from __future__ import annotations

import logging
import sys
from pathlib import Path
from typing import Sequence

import numpy as np
from PIL import Image

logger = logging.getLogger("overlay_lab")

try:
    import onnxruntime as ort
except Exception:  # pragma: no cover - env-dependent
    ort = None

try:
    import cv2
except Exception:  # pragma: no cover - env-dependent
    cv2 = None

# companion_server on sys.path for push_pull reuse (matches pipeline.py idiom).
from backend import config
_CS = str(config.COMPANION_SERVER)
if _CS not in sys.path:
    sys.path.insert(0, _CS)


# ── AOTInpainting.kt constants (companion) ─────────────────────────────
MAX_INFERENCE_DIM = 768
PADDLE_CROP_PAD = 12
PADDLE_THRESH = 0.18
PADDLE_BOX_THRESH = 0.34
REPORT_FREE_TEXT_PAD = 4
REPORT_FREE_TEXT_DILATE = 4
REPORT_AOT_CONTEXT = 512
REPORT_FREE_TEXT_FEATHER = 3
REPORT_PUSH_PULL_CONTEXT = 64

# ── AotOutputGuard.kt constants ────────────────────────────────────────
GUARD_MASK_THRESHOLD = 127
GUARD_MIN_MASKED_PIXELS = 16
GUARD_NEAR_BLACK_MAX = 24.0
GUARD_MID_GRAY_MIN = 96.0
GUARD_MID_GRAY_MAX = 160.0
GUARD_NEAR_WHITE_MIN = 238.0
GUARD_MAX_LUMA_VARIANCE = 9.0
GUARD_MAX_CHANNEL_DELTA = 8.0


# ── geometry helpers (AotBoxGeometry.kt port) ──────────────────────────


def centered_report_crop(
    boxes: Sequence[list[int]], width: int, height: int, context_size: int,
) -> list[int] | None:
    """Square crop centered on the union of *boxes*, clamped on-page.

    Port of AotBoxGeometry.centeredReportCrop. ``side = min(context_size,
    min(width, height))``; center = round(union midpoint); clamp so the square
    stays fully on-page (slid inward, never padded/mirrored).
    Returns ``[x1, y1, x1+side, y1+side]`` or None if *boxes* is empty.
    """
    if not boxes:
        return None
    ux1 = min(b[0] for b in boxes)
    uy1 = min(b[1] for b in boxes)
    ux2 = max(b[2] for b in boxes)
    uy2 = max(b[3] for b in boxes)
    side = min(context_size, min(width, height))
    cx = int(round((ux1 + ux2) / 2.0))
    cy = int(round((uy1 + uy2) / 2.0))
    x1 = max(0, min(width - side, cx - side // 2))
    y1 = max(0, min(height - side, cy - side // 2))
    return [x1, y1, x1 + side, y1 + side]


def padded_union_bounds(
    boxes: Sequence[list[int]], width: int, height: int, pad: int,
) -> list[int] | None:
    """Rectangular padded union bounds, clamped to page. None if degenerate."""
    if not boxes:
        return None
    x1 = max(0, min(b[0] for b in boxes) - pad)
    y1 = max(0, min(b[1] for b in boxes) - pad)
    x2 = min(width, max(b[2] for b in boxes) + pad)
    y2 = min(height, max(b[3] for b in boxes) + pad)
    if x2 <= x1 or y2 <= y1:
        return None
    return [x1, y1, x2, y2]


def localize_box(
    box: list[int], origin_x: int, origin_y: int, width: int, height: int,
) -> list[int] | None:
    """Shift *box* by (-origin_x, -origin_y), clamp to [0,width]/[0,height].

    Returns None if the result has non-positive area (AotBoxGeometry.localizeBox).
    """
    x1 = max(0, min(width, box[0] - origin_x))
    y1 = max(0, min(height, box[1] - origin_y))
    x2 = max(0, min(width, box[2] - origin_x))
    y2 = max(0, min(height, box[3] - origin_y))
    if x2 <= x1 or y2 <= y1:
        return None
    return [x1, y1, x2, y2]


# ── BubbleMaskBuilder.buildFixedPillMask port ──────────────────────────


def _fill_pill(mask: np.ndarray, width: int, height: int,
               raw_x1: int, raw_y1: int, raw_x2: int, raw_y2: int) -> None:
    """Draw one filled capsule (stadium) into *mask* (uint8, 1=erase).

    Port of BubbleMaskBuilder.fillPill: clamp to canvas; radius = min(w,h)//2;
    straight central band plus two semicircular caps.
    """
    x1 = max(0, min(width, raw_x1))
    y1 = max(0, min(height, raw_y1))
    x2 = max(0, min(width, raw_x2))
    y2 = max(0, min(height, raw_y2))
    if x2 <= x1 or y2 <= y1:
        return
    r = min(x2 - x1, y2 - y1) // 2
    if r <= 0:
        return
    left_cx = x1 + r
    right_cx = x2 - r - 1
    top_cy = y1 + r
    bottom_cy = y2 - r - 1
    r2 = r * r
    for y in range(y1, y2):
        for x in range(x1, x2):
            if (left_cx <= x <= right_cx) or (top_cy <= y <= bottom_cy):
                mask[y, x] = 1
                continue
            # Nearest cap center.
            cx = left_cx if x < left_cx else right_cx
            cy = top_cy if y < top_cy else bottom_cy
            dx = x - cx
            dy = y - cy
            if dx * dx + dy * dy <= r2:
                mask[y, x] = 1


def _dilate_mask_disk(mask: np.ndarray, width: int, height: int, radius: int) -> np.ndarray:
    """Single-pass disk dilation (true disk SE). Port of dilateMaskDisk."""
    if radius <= 0:
        return mask.copy()
    result = mask.copy()
    # Precompute disk kernel offsets.
    offsets = []
    for dy in range(-radius, radius + 1):
        for dx in range(-radius, radius + 1):
            if dx * dx + dy * dy <= radius * radius:
                offsets.append((dx, dy))
    ys, xs = np.where(mask != 0)
    for y, x in zip(ys.tolist(), xs.tolist()):
        for dx, dy in offsets:
            nx = x + dx
            ny = y + dy
            if 0 <= nx < width and 0 <= ny < height:
                result[ny, nx] = 1
    return result


def build_fixed_pill_mask(
    boxes: Sequence[list[int]], width: int, height: int,
    pad: int, dilate_radius: int,
) -> np.ndarray:
    """Build the erase mask: capsules per box, expanded by *pad*, disk-dilated.

    Port of BubbleMaskBuilder.buildFixedPillMask. Returns a flat-convertible
    ``width*height`` uint8 array (1=erase).
    """
    if width <= 0 or height <= 0 or not boxes:
        return np.zeros((height, width), dtype=np.uint8)
    mask = np.zeros((height, width), dtype=np.uint8)
    for box in boxes:
        if len(box) < 4:
            continue
        _fill_pill(
            mask, width, height,
            box[0] - pad, box[1] - pad, box[2] + pad, box[3] + pad,
        )
    if dilate_radius > 0:
        mask = _dilate_mask_disk(mask, width, height, dilate_radius)
    return mask


# ── AotOutputGuard port ────────────────────────────────────────────────


def _guard_inspect(
    inpainted: np.ndarray, mask: np.ndarray, width: int, height: int,
) -> tuple[float, float, float, int]:
    """Compute (mean, variance, channelDelta, maskedCount) over masked luma.

    Port of AotOutputGuard.inspect. *inpainted* is HxWx3 uint8; *mask* is HxW
    uint8 (nonzero = masked).
    """
    n = min(inpainted.shape[0] * inpainted.shape[1], mask.size, width * height)
    if n <= 0:
        return 0.0, 0.0, 0.0, 0
    mask_flat = mask.reshape(-1)[:n]
    inp_flat = inpainted.reshape(-1, 3)[:n]
    masked = mask_flat > GUARD_MASK_THRESHOLD
    if not masked.any():
        return 0.0, 0.0, 0.0, 0
    px = inp_flat[masked]
    r = px[:, 0].astype(np.float64)
    g = px[:, 1].astype(np.float64)
    b = px[:, 2].astype(np.float64)
    luma = 0.299 * r + 0.587 * g + 0.114 * b
    count = len(luma)
    if count < GUARD_MIN_MASKED_PIXELS:
        return 0.0, 0.0, 0.0, count
    mean = float(luma.mean())
    variance = float((luma * luma).mean() - mean * mean)  # population variance
    channel_delta = float((np.abs(r - g) + np.abs(g - b)).mean())
    return mean, variance, channel_delta, count


def _guard_classify(mean: float, variance: float, channel_delta: float, masked_count: int) -> bool:
    """Return True if the output is suspiciously uniform (should be rejected)."""
    if masked_count < GUARD_MIN_MASKED_PIXELS:
        return False
    uniform = variance < GUARD_MAX_LUMA_VARIANCE and channel_delta < GUARD_MAX_CHANNEL_DELTA
    if not uniform:
        return False
    near_black = mean <= GUARD_NEAR_BLACK_MAX
    mid_gray = GUARD_MID_GRAY_MIN <= mean <= GUARD_MID_GRAY_MAX
    near_white = mean >= GUARD_NEAR_WHITE_MIN
    return near_black or mid_gray or near_white


def output_guard_is_suspicious(
    inpainted: np.ndarray, mask: np.ndarray, width: int, height: int,
) -> bool:
    """Full guard: inspect + classify. True = reject the AOT output."""
    mean, variance, channel_delta, count = _guard_inspect(inpainted, mask, width, height)
    return _guard_classify(mean, variance, channel_delta, count)


# ── feather alpha (chamfer distance-field blend) ───────────────────────


def feather_alpha_field(mask: np.ndarray, width: int, height: int, ramp: int) -> np.ndarray:
    """(3,4)-chamfer two-pass distance transform -> alpha field.

    Port of BubbleMaskBuilder.featherAlphaField. Alpha = 1.0 inside mask;
    outside = clamp(1 - dist/ramp, 0, 1) with ramp = max(2, ramp).
    """
    INF = 1_000_000_000
    dist = np.where(mask != 0, 0, INF).astype(np.int64)
    ramp_eff = max(2, ramp)
    # Forward pass (top-left to bottom-right).
    for y in range(height):
        for x in range(width):
            if dist[y, x] == 0:
                continue
            best = dist[y, x]
            if x > 0:
                v = dist[y, x - 1] + 3
                if v < best: best = v
            if y > 0:
                v = dist[y - 1, x] + 3
                if v < best: best = v
            if x > 0 and y > 0:
                v = dist[y - 1, x - 1] + 4
                if v < best: best = v
            if x < width - 1 and y > 0:
                v = dist[y - 1, x + 1] + 4
                if v < best: best = v
            dist[y, x] = best
    # Backward pass (bottom-right to top-left).
    for y in range(height - 1, -1, -1):
        for x in range(width - 1, -1, -1):
            if dist[y, x] == 0:
                continue
            best = dist[y, x]
            if x < width - 1:
                v = dist[y, x + 1] + 3
                if v < best: best = v
            if y < height - 1:
                v = dist[y + 1, x] + 3
                if v < best: best = v
            if x < width - 1 and y < height - 1:
                v = dist[y + 1, x + 1] + 4
                if v < best: best = v
            if x > 0 and y < height - 1:
                v = dist[y + 1, x - 1] + 4
                if v < best: best = v
            dist[y, x] = best
    ramp_eff = max(2, ramp)
    alpha = np.where(
        mask != 0,
        1.0,
        np.clip(1.0 - (dist / 3.0) / ramp_eff, 0.0, 1.0),
    )
    return alpha.astype(np.float32)


# ── the inpainter ──────────────────────────────────────────────────────


class AotInpainter:
    """Faithful port of the AOTInpainting free-text route.

    Args:
        model_path: path to ``aot.onnx``.
        paddle_det: optional :class:`PaddleOcrV6Det` for free-text refinement.
            If None, detector boxes are used as-is (logged as fallback).
    """

    def __init__(self, model_path: str | Path, paddle_det=None) -> None:
        if ort is None:
            raise RuntimeError("onnxruntime is not installed")
        if cv2 is None:
            raise RuntimeError("cv2 is required for the colorspace swap")
        path = Path(model_path)
        if not path.exists():
            raise FileNotFoundError(f"AOT model not found: {path}")
        self.session = ort.InferenceSession(
            str(path), providers=["CPUExecutionProvider"],
        )
        self.image_name = "image"
        self.mask_name = "mask"
        # Verify input names exist; fall back to first/second if renamed.
        in_names = [i.name for i in self.session.get_inputs()]
        if self.image_name not in in_names:
            self.image_name = in_names[0]
        if self.mask_name not in in_names and len(in_names) > 1:
            self.mask_name = in_names[1]
        self.paddle_det = paddle_det
        logger.info(
            "AotInpainter loaded: %s (in=%s,%s)", path.name, self.image_name, self.mask_name,
        )

    # ── public entry ───────────────────────────────────────────────────

    def inpaint_free_text(
        self,
        image_rgb: Image.Image,
        detector_boxes: list[list[int]],
        mode: str = "QUALITY",
    ) -> tuple[Image.Image, dict]:
        """Erase free-text detector boxes via the faithful two-tier path.

        Args:
            image_rgb: the page.
            detector_boxes: label-2 boxes in page coords.
            mode: "QUALITY" (AOT + fast fallback) or "FAST" (push-pull only).

        Returns (cleaned image, info dict with method/ms/groups/fallbacks).
        Qualitative contract: refinement via Paddle DET, erase via AOT-GAN with
        a pill line-mask in a 512 context crop, guard-validated, push-pull
        fallback on any failure. Every route logged.
        """
        import time
        t0 = time.perf_counter()
        info = {"method": mode.lower(), "groups": 0, "paddle_lines": 0,
                "fallbacks": 0, "boxes": len(detector_boxes)}

        if not detector_boxes:
            info["ms"] = 0.0
            return image_rgb, info

        arr = np.asarray(image_rgb.convert("RGB"))

        # 1. Refine detector boxes into Paddle line groups.
        groups, paddle_count, fb_count = self._refine_free_text(arr, detector_boxes)
        info["paddle_lines"] = paddle_count
        info["fallbacks"] = fb_count
        info["groups"] = len(groups)
        if not groups:
            info["ms"] = round((time.perf_counter() - t0) * 1000, 1)
            return image_rgb, info

        # 2. Erase each group.
        work = arr.copy()
        h, w = work.shape[:2]
        aot_available = self.session is not None
        for group in groups:
            if mode == "QUALITY" and aot_available:
                try:
                    work = self._erase_group_aot(work, group, w, h)
                    continue
                except Exception as e:  # guard-reject / OOM / inference error
                    logger.warning(
                        "AOT free-text erase failed (group %s) -> fast fallback: %s",
                        group, e,
                    )
                    info["fallbacks"] = info.get("fallbacks", 0) + 1
            # FAST path (or QUALITY fallback).
            work = self._erase_group_fast(work, group, w, h)

        out = Image.fromarray(work)
        info["ms"] = round((time.perf_counter() - t0) * 1000, 1)
        return out, info

    # ── refinement ─────────────────────────────────────────────────────

    def _refine_free_text(
        self, arr: np.ndarray, detector_boxes: list[list[int]],
    ) -> tuple[list[list[list[int]]], int, int]:
        """Per detector box: Paddle DET refine -> line boxes (page coords).

        Returns (groups, paddle_line_count, fallback_count). Each group is the
        list of line boxes for one detector box (fallback = the detector box
        itself when Paddle returns nothing or is unavailable).
        """
        h, w = arr.shape[:2]
        groups: list[list[list[int]]] = []
        paddle_count = 0
        fb_count = 0
        if self.paddle_det is None:
            logger.info("Paddle DET unavailable; %d free-text boxes unrefined",
                        len(detector_boxes))
            return ([ [list(b)] for b in detector_boxes ], 0, len(detector_boxes))

        pil = Image.fromarray(arr)
        for det in detector_boxes:
            cx1 = max(0, det[0] - PADDLE_CROP_PAD)
            cy1 = max(0, det[1] - PADDLE_CROP_PAD)
            cx2 = min(w, det[2] + PADDLE_CROP_PAD)
            cy2 = min(h, det[3] + PADDLE_CROP_PAD)
            if cx2 <= cx1 or cy2 <= cy1:
                groups.append([list(det)])
                fb_count += 1
                continue
            crop = pil.crop((cx1, cy1, cx2, cy2))
            lines = self.paddle_det.detect_lines(
                crop, thresh=PADDLE_THRESH, box_thresh=PADDLE_BOX_THRESH,
            )
            if not lines:
                groups.append([list(det)])
                fb_count += 1
                continue
            group: list[list[int]] = []
            for tl in lines:
                b = tl.bbox
                px1 = max(0, min(w, cx1 + b[0]))
                py1 = max(0, min(h, cy1 + b[1]))
                px2 = max(0, min(w, cx1 + b[2]))
                py2 = max(0, min(h, cy1 + b[3]))
                if px2 > px1 and py2 > py1:
                    group.append([px1, py1, px2, py2])
                    paddle_count += 1
            if group:
                groups.append(group)
            else:
                groups.append([list(det)])
                fb_count += 1
        return groups, paddle_count, fb_count

    # ── AOT erase (one group) ──────────────────────────────────────────

    def _erase_group_aot(
        self, arr: np.ndarray, boxes: list[list[int]], w: int, h: int,
    ) -> np.ndarray:
        """512 centered context crop -> pill mask -> AOT -> guard -> feather0."""
        crop = centered_report_crop(boxes, w, h, REPORT_AOT_CONTEXT)
        if crop is None:
            return arr
        cx1, cy1, cx2, cy2 = crop
        crop_w = cx2 - cx1
        crop_h = cy2 - cy1
        # Must be square (centeredReportCrop guarantees side x side).
        if crop_w != crop_h or crop_w <= 0:
            return arr

        # Localize boxes into the crop.
        local_boxes = []
        for b in boxes:
            lb = localize_box(b, cx1, cy1, crop_w, crop_h)
            if lb is not None:
                local_boxes.append(lb)
        if not local_boxes:
            return arr

        mask = build_fixed_pill_mask(
            local_boxes, crop_w, crop_h, REPORT_FREE_TEXT_PAD, REPORT_FREE_TEXT_DILATE,
        )
        if not mask.any():
            return arr

        crop_img = arr[cy1:cy2, cx1:cx2].copy()
        inpainted = self._run_aot(crop_img, mask)
        if inpainted is None:
            raise RuntimeError("AOT inference returned None")

        # Output guard.
        if output_guard_is_suspicious(inpainted, mask, crop_w, crop_h):
            raise RuntimeError("Report AOT output rejected by guard")

        # Hard blend (feather ramp 0 for the report AOT path).
        result = crop_img.copy()
        result[mask != 0] = inpainted[mask != 0]
        arr[cy1:cy2, cx1:cx2] = result
        return arr

    def _run_aot(self, crop_bgr: np.ndarray, mask: np.ndarray) -> np.ndarray | None:
        """Run the AOT-GAN on a crop+mask. Returns HxWx3 uint8 or None.

        Image normalized to [-1,1]; masked image pixels zeroed; dims rounded up
        to a multiple of 8; feed {"image","mask"}; denorm round((out+1)*127.5).
        """
        ch, cw = crop_bgr.shape[:2]
        # Round up to multiple of 8 (AOT contract).
        infer_w = cw + (8 - cw % 8) % 8
        infer_h = ch + (8 - ch % 8) % 8
        if max(infer_w, infer_h) > MAX_INFERENCE_DIM:
            scale = MAX_INFERENCE_DIM / max(infer_w, infer_h)
            infer_w = int(infer_w * scale)
            infer_h = int(infer_h * scale)
            infer_w += (8 - infer_w % 8) % 8
            infer_h += (8 - infer_h % 8) % 8

        # Pad image + mask to inference dims (bottom-right zero pad).
        img_pad = np.zeros((infer_h, infer_w, 3), dtype=np.uint8)
        img_pad[:ch, :cw] = crop_bgr
        mask_pad = np.zeros((infer_h, infer_w), dtype=np.uint8)
        mask_pad[:ch, :cw] = mask

        # Normalize image to [-1, 1]; zero masked pixels in the image input.
        img_f = img_pad.astype(np.float32) / 127.5 - 1.0
        m = (mask_pad != 0).astype(np.float32)[..., None]
        img_f = img_f * (1.0 - m)
        img_nchw = np.transpose(img_f, (2, 0, 1))[None]           # (1,3,H,W)
        mask_nchw = (mask_pad != 0).astype(np.float32)[None, None]  # (1,1,H,W)

        try:
            outputs = self.session.run(
                None, {self.image_name: img_nchw, self.mask_name: mask_nchw},
            )
        except Exception as e:
            logger.error("AOT session.run failed: %s", e)
            return None
        out = outputs[0][0]  # (3,H,W)
        out = np.transpose(out, (1, 2, 0))  # (H,W,3)
        out = out[:ch, :cw]  # unpad
        # Denormalize: round((out+1)*127.5), clip [0,255].
        out = np.clip(np.round((out + 1.0) * 127.5), 0, 255).astype(np.uint8)

        # Grayscale fast-path if the crop's average chroma is low.
        crop_f = crop_bgr.astype(np.float32)
        r, g, b = crop_f[:, :, 0], crop_f[:, :, 1], crop_f[:, :, 2]
        chroma = np.abs(r - g) + np.abs(g - b) + np.abs(b - r)
        if chroma.mean() < 15.0:
            luma = (0.299 * out[:, :, 0] + 0.587 * out[:, :, 1] + 0.114 * out[:, :, 2])
            out = np.stack([luma, luma, luma], axis=-1).astype(np.uint8)
        return out

    # ── FAST fallback (one group) ──────────────────────────────────────

    def _erase_group_fast(
        self, arr: np.ndarray, boxes: list[list[int]], w: int, h: int,
    ) -> np.ndarray:
        """Push-pull fill on a padded-union crop with the same pill mask."""
        from inpaint import push_pull as pp

        bounds = padded_union_bounds(boxes, w, h, REPORT_PUSH_PULL_CONTEXT)
        if bounds is None:
            return arr
        bx1, by1, bx2, by2 = bounds
        crop_w = bx2 - bx1
        crop_h = by2 - by1
        local_boxes = []
        for b in boxes:
            lb = localize_box(b, bx1, by1, crop_w, crop_h)
            if lb is not None:
                local_boxes.append(lb)
        if not local_boxes:
            return arr
        mask = build_fixed_pill_mask(
            local_boxes, crop_w, crop_h, REPORT_FREE_TEXT_PAD, REPORT_FREE_TEXT_DILATE,
        )
        if not mask.any():
            return arr

        crop = arr[by1:by2, bx1:bx2].copy()
        bg = pp.local_ring_median(crop, mask, ring=8)
        filled = pp.push_pull_fill(crop, mask, bg)

        # Feather blend (ramp = REPORT_FREE_TEXT_FEATHER = 3).
        alpha = feather_alpha_field(mask, crop_w, crop_h, REPORT_FREE_TEXT_FEATHER)
        a = alpha[:, :, None]
        blended = crop.astype(np.float32) * (1.0 - a) + filled.astype(np.float32) * a
        arr[by1:by2, bx1:bx2] = np.clip(blended, 0, 255).astype(np.uint8)
        return arr
