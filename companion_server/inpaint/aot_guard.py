"""AOT output validation — detects suspicious uniform gray/white neural output.

Port of AotOutputGuard.kt. When the AOT model fails, it typically produces a
uniform mid-gray or near-white block. This module checks the inpainted pixels
within the mask region and returns True if the output is suspicious.
"""
from __future__ import annotations

import numpy as np

_MASK_THRESHOLD = 127
_MIN_MASKED_PIXELS = 16
_MID_GRAY_MIN = 96.0
_MID_GRAY_MAX = 160.0
_NEAR_WHITE_MIN = 238.0
_MAX_LUMA_VARIANCE = 36.0
_MAX_CHANNEL_DELTA = 8.0


def is_suspicious_gray_fill(
    inpainted: np.ndarray,
    mask: np.ndarray,
) -> bool:
    """Check if inpainted pixels within the mask are suspiciously uniform.

    Args:
        inpainted: HxWx3 uint8 array of inpainted output
        mask: HxW uint8 array (1=mask region to check)

    Returns True if the masked region is a uniform gray/white fill.
    """
    h, w = mask.shape[:2]
    mask_flat = mask.ravel()
    mask_sel = mask_flat > _MASK_THRESHOLD

    count = int(np.count_nonzero(mask_sel))
    if count < _MIN_MASKED_PIXELS:
        return False

    pixels = inpainted.reshape(-1, 3)[mask_sel].astype(np.float64)
    r, g, b = pixels[:, 0], pixels[:, 1], pixels[:, 2]
    luma = 0.299 * r + 0.587 * g + 0.114 * b

    mean = float(np.mean(luma))
    variance = float(np.var(luma))
    channel_delta = float(np.mean(np.abs(r - g) + np.abs(g - b)))

    if not (variance < _MAX_LUMA_VARIANCE and channel_delta < _MAX_CHANNEL_DELTA):
        return False

    uniform_mid_gray = _MID_GRAY_MIN <= mean <= _MID_GRAY_MAX
    uniform_near_white = mean >= _NEAR_WHITE_MIN
    return uniform_mid_gray or uniform_near_white
