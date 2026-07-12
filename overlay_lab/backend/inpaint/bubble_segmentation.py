"""Segmentation-mask bubble inpainting.

Replaces the classical pill-mask + reportBubbleFill path with a pixel-accurate
segmentation mask from the huyvux3005/manga109-segmentation-bubble YOLO model.
Each detected bubble's connected component is filled with the median color of
a thin ring just outside its bounding box (port of solid_fill_smart_color from
tools/prototype_quantized.py).

Only the BUBBLE erase path changes. Free-text regions are handled by the
caller via freetext_aot / freetext_classical.
"""
from __future__ import annotations

import logging
from typing import Any

import cv2
import numpy as np
from PIL import Image

logger = logging.getLogger("overlay_lab")

_YOLO = None

# The seg model was trained at 1600×1600. Running it at the legacy 640 export
# size downscales pages so aggressively that small/distant bubbles vanish from
# the model's proposals entirely (confirmed: page-005 yields 3 masks at 640,
# 4 at 960/1280, and 5 at 1280 with conf=0.1). 1280 is the balanced lab cap:
# it recovers missed bubbles at roughly 3× the 640 cost instead of jumping to
# the much heavier 1600 path.
SEG_BALANCED_SIZE = 1280
SEG_STRIDE = 32


def _auto_imgsz(image_rgb: Image.Image, requested: int, model_path: str | None) -> int:
    """Resolve the inference imgsz.

    requested > 0 → use it verbatim (explicit override).
    requested == 0 (auto):
      - fixed-shape legacy ONNX exports (int8/fp32) → 640
      - dynamic best.onnx / best.pt → min(longest_side, 1280), stride-rounded
    """
    if requested > 0:
        return requested
    name = "" if model_path is None else str(model_path).replace("\\", "/").split("/")[-1]
    if name in {"best_int8.onnx", "best_fp32.onnx", "best_int8_broken.onnx"}:
        return 640
    w, h = image_rgb.size
    long_side = max(w, h)
    size = min(long_side, SEG_BALANCED_SIZE)
    # Round to the model's stride (32) — ultralytics does this internally too,
    # but we do it here so the value is deterministic for logging.
    return max(SEG_STRIDE, int(round(size / SEG_STRIDE)) * SEG_STRIDE)


def _get_yolo(model_path: str | None) -> Any:
    global _YOLO
    if _YOLO is not None:
        return _YOLO
    if model_path is None:
        raise RuntimeError(
            "Segmentation model not found. Expected best.onnx (fp16) or best.pt "
            "in HF cache (models--huyvux3005--manga109-segmentation-bubble)."
        )
    from ultralytics import YOLO
    logger.info("Loading segmentation model: %s", model_path)
    _YOLO = YOLO(model_path, task="segment")
    return _YOLO


def segment_bubbles(
    image_rgb: Image.Image,
    model_path: str | None,
    imgsz: int = 0,
    conf: float = 0.2,
    iou: float = 0.7,
) -> np.ndarray:
    """Run YOLO segmentation, return a uint8 mask (255=bubble, 0=clear).

    Uses masks.data (letterbox-space tensors) resized to original image shape
    with INTER_NEAREST. Falls back to masks.xy polygons if masks.data is None.

    imgsz=0 (default) → auto: infer near native resolution capped at 1280.
    This recovers small/distant bubbles that the old fixed-640 downscale lost
    without paying the full 1600 cost by default.
    """
    yolo = _get_yolo(model_path)
    arr = np.asarray(image_rgb)
    h, w = arr.shape[:2]
    infer_size = _auto_imgsz(image_rgb, imgsz, model_path)
    results = yolo.predict(
        arr,
        verbose=False,
        imgsz=infer_size,
        conf=conf,
        iou=iou,
        retina_masks=True,
    )
    mask = np.zeros((h, w), dtype=np.uint8)
    for r in results:
        if r.masks is None:
            continue
        if r.masks.data is not None:
            # Primary path: masks.data in letterbox space (N, 640, 640)
            masks_data = r.masks.data.cpu().numpy()
            for i in range(masks_data.shape[0]):
                mask_resized = cv2.resize(
                    masks_data[i], (w, h), interpolation=cv2.INTER_NEAREST,
                )
                mask[mask_resized > 0.5] = 255
        elif r.masks.xy is not None:
            # Fallback: masks.xy polygons (original image coordinates)
            logger.info("masks.data unavailable, falling back to masks.xy")
            for xy in r.masks.xy:
                if xy is None or len(xy) < 3:
                    continue
                contour = xy.round().astype(np.int32).reshape(-1, 1, 2)
                cv2.fillPoly(mask, [contour], 255)
    return mask


def default_mask_params() -> dict[str, Any]:
    """Return default mask fill parameters.

    The `strategy` and `free_text_strategy` keys select the inpaint algorithm:
      - "median"   : per-component ring-median SOLID fill (Android-faithful default)
      - "telea"    : Fast Marching Method (experimental)
      - "pushpull" : push-pull gradient (experimental)
      - "ns"       : Laplace relaxation (experimental candidate)
      - "hybrid"   : per-component auto (experimental)
    Default is "median" — the smart solid fill that mirrors Android's
    AotReportBubbleFill.reportBubbleFill. The others are optional experiments
    for A/B comparison in the sandbox.
    """
    return {
        "strategy": "median",
        "free_text_strategy": "median",
        "skip_px": 3,
        "ring_w": 4,
        "dilate": 0,
        "luma_floor": 128,
        "feather": 0,
        "inset_px": 5,
    }


def fill_bubbles_smart_color(
    image_bgr: np.ndarray,
    mask: np.ndarray,
    params: dict[str, Any] | None = None,
) -> np.ndarray:
    """Fill bubble regions per the selected strategy.

    Strategy dispatch (params["strategy"], default "median"):
      - "median"  : per-component ring-median solid fill (Android-faithful default)
      - "telea"   : Fast Marching Method structure propagation (experiment)
      - "pushpull": push-pull gradient fill (experiment)
      - "ns"      : Laplace relaxation (experiment candidate)
      - "hybrid"  : per-component auto: flat→median, textured→telea (experiment)

    The `feather` key applies a distance-field alpha blend after the fill
    (matches Android's chamfer featherAlphaField).

    The `inset_px` key (default 2) shrinks the region actually filled so the
    fill never reaches the segmentation mask's outer edge. The seg mask often
    clips through the bubble's ink outline; filling right to the edge would
    erase part of that stroke. inset_px erodes the overwrite region inward by
    N pixels, leaving the original ink untouched. The strategy still samples
    the full mask (surrounding context preserved) — only the pixels actually
    overwritten are constrained to the inset interior.

    Parameters
    ----------
    image_bgr : np.ndarray
        Input BGR image.
    mask : np.ndarray
        Binary uint8 mask (255=bubble, 0=clear).
    params : dict or None
        Override defaults. See default_mask_params() for keys.

    Returns
    -------
    np.ndarray
        Filled BGR image.
    """
    if params is None:
        params = default_mask_params()

    strategy = params.get("strategy") or "median"
    feather = int(params.get("feather", 0))
    inset_px = int(params.get("inset_px", 5))

    from backend.inpaint import strategies
    filled = strategies.run_strategy(strategy, image_bgr, mask, params)

    # ── inset: keep the fill strictly inside the seg mask ────────────────
    # The seg mask frequently clips through the bubble's ink outline. Filling
    # right to the mask edge would erase part of that stroke. We let the
    # strategy sample the full mask (it needs the surrounding context), then
    # restore every original pixel outside an eroded interior so the visible
    # fill never reaches the mask border.
    if inset_px > 0 and mask.any():
        kern = cv2.getStructuringElement(
            cv2.MORPH_ELLIPSE,
            (inset_px * 2 + 1, inset_px * 2 + 1),
        )
        interior = cv2.erode(mask, kern, iterations=1)
        # Only the eroded interior keeps the fill; everything else reverts.
        revert = interior != 255
        filled[revert] = image_bgr[revert]

    # ── distance-field feather ───────────────────────────────────────────
    if feather > 0:
        # Feather against the inset interior, not the raw mask, so the soft
        # ramp also respects the buffer.
        feather_mask = interior if (inset_px > 0 and mask.any()) else mask
        filled = strategies.feather_blend(filled, image_bgr, feather_mask, feather)
    return filled


def segment_bubbles_polygons(
    image_rgb: Image.Image,
    model_path: str | None,
    conf: float = 0.2,
    iou: float = 0.7,
    imgsz: int = 0,
) -> list[list[list[float]]]:
    """Return per-bubble polygons from ``r.masks.xy`` (original-image coords).

    Each polygon is a list of ``[x, y]`` float points. No flat mask, no resize.

    Polygons are in original-image coordinates natively (ultralytics
    ``retina_masks=True``); no letterbox correction needed.

    The confidence threshold (default 0.2) is deliberately lower than typical
    YOLO seg defaults (0.85) because dark/gray-tinted bubbles are
    out-of-distribution for this model and score low; a high bar drops them
    entirely. 0.2 recovers faint bubbles while avoiding the more false-positive
    prone 0.1 default.

    imgsz=0 (default) → auto: infer near native resolution capped at 1280. The
    old fixed-640 downscale lost small/distant bubbles; 1280 is the balanced
    quality/performance cap.
    """
    yolo = _get_yolo(model_path)
    arr = np.asarray(image_rgb)
    infer_size = _auto_imgsz(image_rgb, imgsz, model_path)
    results = yolo.predict(
        arr,
        verbose=False,
        imgsz=infer_size,
        conf=conf,
        iou=iou,
        retina_masks=True,
    )

    polygons: list[list[list[float]]] = []
    for r in results:
        if r.masks is None:
            continue
        if r.masks.xy is not None:
            for xy in r.masks.xy:
                if xy is None or len(xy) < 3:
                    continue
                poly = [[float(pt[0]), float(pt[1])] for pt in xy]
                polygons.append(poly)
        elif r.masks.data is not None:
            # Fallback: masks.data is available but masks.xy is None (rare).
            # Rasterize the mask at original resolution, find contours.
            logger.warning(
                "masks.xy unavailable, falling back to findContours on masks.data "
                "(this is unexpected with retina_masks=True)"
            )
            import cv2
            h, w = arr.shape[:2]
            masks_data = r.masks.data.cpu().numpy()
            for i in range(masks_data.shape[0]):
                # Resize from letterbox (640x640) to original image size
                mask_uint8 = (masks_data[i] * 255).astype(np.uint8)
                mask_resized = cv2.resize(mask_uint8, (w, h), interpolation=cv2.INTER_NEAREST)
                contours, _ = cv2.findContours(mask_resized, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
                for contour in contours:
                    if len(contour) < 3:
                        continue
                    poly = [[float(pt[0][0]), float(pt[0][1])] for pt in contour]
                    polygons.append(poly)

    return polygons


def inpaint_bubbles_segmentation(
    image: Image.Image,
    bubble_boxes: list[list[int]],
    model_path: str | None,
    mask_params: dict[str, Any] | None = None,
    imgsz: int = 0,
    conf: float = 0.2,
    iou: float = 0.7,
) -> tuple[Image.Image, dict[str, Any]]:
    """Erase all bubble regions using segmentation masks.

    bubble_boxes (label 0 + parented-text parent rects from detector v4) are
    used only to filter the YOLO segmentation to relevant components — the
    actual erase boundary comes from the pixel mask, not the box.

    NOTE: bubble_boxes is currently unused (filtering not yet implemented).
    """
    import time
    t0 = time.perf_counter()
    rgb = image.convert("RGB")
    mask = segment_bubbles(rgb, model_path, imgsz=imgsz, conf=conf, iou=iou)
    bgr = cv2.cvtColor(np.asarray(rgb), cv2.COLOR_RGB2BGR)
    filled = fill_bubbles_smart_color(bgr, mask, params=mask_params)
    out = Image.fromarray(cv2.cvtColor(filled, cv2.COLOR_BGR2RGB))
    elapsed_ms = (time.perf_counter() - t0) * 1000
    info = {
        "method": "segmentation",
        "ms": round(elapsed_ms, 1),
        "mask_pixels": int((mask > 0).sum()),
        "components": int(len(cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)[0])),
    }
    return out, info