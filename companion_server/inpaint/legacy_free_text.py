"""Legacy free-text inpainter port.

Matches Android's LegacyFreeTextInpainter.kt logic.
"""
from __future__ import annotations

import numpy as np
from PIL import Image

from . import push_pull as pp
from . import bubble_mask as bm

RING = 8
STROKE_GRAY_THRESH = 160
STROKE_BOX_PAD = 2
STROKE_DILATE = 5
FEATHER_RAMP = 3
FREE_MIN_SIDE = 18
FREE_LONG_FLOOR = 22

def inpaint_legacy_free_text(image: Image.Image, box: list[int], box_pad: int = STROKE_BOX_PAD, dilate: int = STROKE_DILATE, solid_mask: bool = False, mask_mode: str = "grad") -> Image.Image:
    """Inpaint a free-text box using legacy local ring median and push-pull fill.
    
    Matches LegacyFreeTextInpainter.inpaint behavior exactly.
    """
    w, h = image.size
    
    if len(box) < 4:
        return image
        
    bx1, by1, bx2, by2 = _expand_tiny_free(box, w, h)
    
    if bx2 - bx1 <= 0 or by2 - by1 <= 0:
        return image

    margin = RING + FEATHER_RAMP + box_pad + 2
    cx1 = max(0, bx1 - margin)
    cy1 = max(0, by1 - margin)
    cx2 = min(w, bx2 + margin)
    cy2 = min(h, by2 + margin)

    if cx2 - cx1 <= 0 or cy2 - cy1 <= 0:
        return image

    pixels = np.array(image.crop((cx1, cy1, cx2, cy2)).convert("RGB"), dtype=np.uint8)

    # Stroke mask
    if solid_mask:
        ch, cw = pixels.shape[:2]
        stroke = np.zeros((ch, cw), dtype=np.uint8)
        x1 = max(0, bx1 - cx1 - box_pad)
        y1 = max(0, by1 - cy1 - box_pad)
        x2 = min(cw, bx2 - cx1 + box_pad)
        y2 = min(ch, by2 - cy1 + box_pad)
        if x2 > x1 and y2 > y1:
            stroke[y1:y2, x1:x2] = 1
    else:
        stroke = _build_stroke_mask(pixels, bx1 - cx1, by1 - cy1, bx2 - cx1, by2 - cy1, box_pad, mask_mode=mask_mode)
        
    if not np.any(stroke):
        return image

    # Dilate mask using disk kernel from bm
    erase = bm.dilate_mask_disk(stroke, dilate)

    # Local ring color + push-pull
    bg_color = pp.local_ring_median(pixels, erase, RING)
    filled = pp.push_pull_fill(pixels, erase, bg_color)

    # Feather composite
    alpha = bm.feather_alpha_field(erase, FEATHER_RAMP)
    
    # Blend: result = orig * (1 - a) + filled * a
    a = alpha[:, :, np.newaxis]
    orig_f = pixels.astype(np.float32)
    fill_f = filled.astype(np.float32)
    result = orig_f * (1.0 - a) + fill_f * a
    result_pixels = np.clip(result, 0, 255).astype(np.uint8)

    res_img = Image.fromarray(result_pixels, "RGB")
    out = image.copy()
    out.paste(res_img, (cx1, cy1))
    return out

def _build_stroke_mask(pixels: np.ndarray, lx1: int, ly1: int, lx2: int, ly2: int, box_pad: int, mask_mode: str = "stroke") -> np.ndarray:
    """Gray-threshold ink mask inside the localized box."""
    import cv2
    ch, cw = pixels.shape[:2]
    mask = np.zeros((ch, cw), dtype=np.uint8)
    
    x1 = max(0, lx1 - box_pad)
    y1 = max(0, ly1 - box_pad)
    x2 = min(cw, lx2 + box_pad)
    y2 = min(ch, ly2 + box_pad)
    
    if x2 <= x1 or y2 <= y1:
        return mask
        
    crop = pixels[y1:y2, x1:x2]
    gray = (0.299 * crop[:, :, 0] + 0.587 * crop[:, :, 1] + 0.114 * crop[:, :, 2]).astype(np.uint8)
    
    if mask_mode == "stroke":
        mask_crop = (gray < STROKE_GRAY_THRESH).astype(np.uint8)
    elif mask_mode == "adapt":
        mask_adapt_dark = cv2.adaptiveThreshold(gray, 1, cv2.ADAPTIVE_THRESH_GAUSSIAN_C, cv2.THRESH_BINARY_INV, 11, 2)
        mask_adapt_light = cv2.adaptiveThreshold(255 - gray, 1, cv2.ADAPTIVE_THRESH_GAUSSIAN_C, cv2.THRESH_BINARY_INV, 11, 2)
        mask_crop = cv2.bitwise_or(mask_adapt_dark, mask_adapt_light)
    elif mask_mode == "edge":
        gray_blur = cv2.GaussianBlur(gray, (5, 5), 0)
        edges = cv2.Canny(gray_blur, 30, 100)
        mask_crop = (edges > 0).astype(np.uint8)
    elif mask_mode == "grad":
        kernel_morph = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (3, 3))
        gray_blur = cv2.GaussianBlur(gray, (5, 5), 0)
        grad = cv2.morphologyEx(gray_blur, cv2.MORPH_GRADIENT, kernel_morph)
        _, thresh = cv2.threshold(grad, 15, 1, cv2.THRESH_BINARY)
        mask_crop = thresh.astype(np.uint8)
    else:
        mask_crop = (gray < STROKE_GRAY_THRESH).astype(np.uint8)
        
    mask[y1:y2, x1:x2] = mask_crop
    
    return mask

def _expand_tiny_free(box: list[int], w: int, h: int) -> list[int]:
    """Free-only tiny-box expansion: grow to the size floor, symmetric, page-clipped."""
    x1, y1, x2, y2 = box[0], box[1], box[2], box[3]
    bw = x2 - x1
    bh = y2 - y1
    short_side = min(bw, bh)
    long_side = max(bw, bh)
    
    if short_side >= FREE_MIN_SIDE and long_side >= FREE_LONG_FLOOR:
        return [x1, y1, x2, y2]
        
    if short_side < FREE_MIN_SIDE:
        grow = FREE_MIN_SIDE - short_side
        half = grow // 2
        other = grow - half
        if bw <= bh:
            x1 = max(0, x1 - half)
            x2 = min(w, x2 + other)
        else:
            y1 = max(0, y1 - half)
            y2 = min(h, y2 + other)
            
    bw2 = x2 - x1
    bh2 = y2 - y1
    new_long = max(bw2, bh2)
    
    if new_long < FREE_LONG_FLOOR:
        grow = FREE_LONG_FLOOR - new_long
        half = grow // 2
        other = grow - half
        if bw2 <= bh2:
            y1 = max(0, y1 - half)
            y2 = min(h, y2 + other)
        else:
            x1 = max(0, x1 - half)
            x2 = min(w, x2 + other)
            
    return [x1, y1, x2, y2]
