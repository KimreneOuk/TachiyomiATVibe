"""Crop preprocessing — mirrors MangaOcrEngine.preprocess + writeNormalizedChw.

Android algorithm (MangaOcrEngine.kt:339-380, 443-463):
  1. draw crop with saturation-0 ColorMatrix (luma weights 0.213/0.715/0.072)
  2. ratio = 224.0f / max(w,h); newW/newH = (dim * ratio).toInt() (truncate), min 1
  3. white 224x224 canvas, paste centered at ((224-new)/2) integer division
  4. ARGB pixels -> per-channel/255 -> (v-0.5)/0.5, CHW, grayscale replicated x3

Known acceptable deviation: the desktop resize filter (PIL) is not bit-identical
to Skia's drawBitmap scaling. This does not affect lab self-consistency, because
ground truth is this lab's own reference decoder; the later JVM parity probe
quantifies any residual difference.
"""
from __future__ import annotations

import numpy as np
from PIL import Image

SIZE = 224
# Android ColorMatrix.setSaturation(0f) luminance weights
_LUMA = (0.213, 0.715, 0.072)


def preprocess(pil_image: Image.Image) -> np.ndarray | None:
    """Returns float32 [3,224,224] normalized, or None for an empty crop."""
    w, h = pil_image.size
    if max(w, h) == 0:
        return None

    rgb = np.asarray(pil_image.convert("RGB"), dtype=np.float32)
    # Android luma, rounded to nearest integer pixel value (Skia color filter)
    lum = (
        _LUMA[0] * rgb[:, :, 0] + _LUMA[1] * rgb[:, :, 1] + _LUMA[2] * rgb[:, :, 2]
    )
    lum8 = np.clip(np.rint(lum), 0, 255).astype(np.uint8)

    # Kotlin: ratio = 224.0f / max(w,h) (float32); new = (dim * ratio).toInt() truncation
    f32 = np.float32
    ratio = f32(SIZE) / f32(max(w, h))
    new_w = max(1, int(f32(w) * ratio))
    new_h = max(1, int(f32(h) * ratio))

    canvas = Image.new("L", (SIZE, SIZE), 255)
    resized = Image.fromarray(lum8, mode="L").resize((new_w, new_h), Image.NEAREST)
    canvas.paste(resized, ((SIZE - new_w) // 2, (SIZE - new_h) // 2))

    arr = np.asarray(canvas, dtype=np.float32) / np.float32(255.0)
    x = (arr - np.float32(0.5)) / np.float32(0.5)
    return np.stack([x, x, x]).astype(np.float32)  # CHW, replicated x3


def preprocess_file(path) -> np.ndarray | None:
    with Image.open(path) as im:
        return preprocess(im)
