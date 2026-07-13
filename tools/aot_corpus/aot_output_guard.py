"""
Faithful numpy reimplementation of AotOutputGuard.kt for offline corpus testing.

The Kotlin guard lives in `app/src/main/java/eu/kanade/translation/inpainting/
AotOutputGuard.kt` and is the production rejector for uniform-fill AOT failures
(uniform near-black, mid-gray, near-white with low variance and low chroma).
It runs in the Android app after each inference.

This module reimplements its exact math so the offline corpus harness can apply
the SAME verdict the production guard would, without needing an Android device
or the Android-coupled `AotOutputGuard` object.

If AotOutputGuard.kt changes, update this file in the same change. The unit
test (test_aot_output_guard_parity.py) asserts parity on synthetic inputs.
"""
from __future__ import annotations

from dataclasses import dataclass

import numpy as np

# Constants — MUST match AotOutputGuard.kt private companion.
MASK_THRESHOLD = 127
MIN_MASKED_PIXELS = 16
NEAR_BLACK_MAX = 24.0
MID_GRAY_MIN = 96.0
MID_GRAY_MAX = 160.0
NEAR_WHITE_MIN = 238.0
MAX_LUMA_VARIANCE = 9.0
MAX_CHANNEL_DELTA = 8.0


@dataclass
class GuardStats:
    mean: float
    variance: float
    channel_delta: float
    masked_count: int


def _mask_value(pixel: int) -> int:
    """Mirrors AotOutputGuard.maskValue: max(byte0, alpha)."""
    return max(pixel & 0xFF, (pixel >> 24) & 0xFF)


def inspect(inpainted_argb: np.ndarray, mask_argb: np.ndarray) -> GuardStats:
    """
    Compute masked-region statistics over ARGB-packed IntArray-equivalents.

    Args:
        inpainted_argb: 1D int array (ARGB packed, same as Kotlin IntArray).
        mask_argb:     1D int array, same length. Mask value is max(byte0, alpha).
    """
    n = min(inpainted_argb.size, mask_argb.size)
    if n <= 0:
        return GuardStats(0.0, 0.0, 0.0, 0)

    # Vectorize the Kotlin loop. maskValue = max(px & 0xFF, (px >> 24) & 0xFF).
    # numpy int32 emulates Kotlin Int.
    m32 = mask_argb[:n].astype(np.int32)
    # Interpret bytes: numpy & 0xFF on int32 gives the low byte (channel B in ARGB).
    mask_byte0 = (m32 & 0xFF).astype(np.int64)
    mask_alpha = ((m32 >> 24) & 0xFF).astype(np.int64)
    mask_value = np.maximum(mask_byte0, mask_alpha)

    active = mask_value > MASK_THRESHOLD
    count = int(active.sum())
    if count < MIN_MASKED_PIXELS:
        return GuardStats(0.0, 0.0, 0.0, count)

    p = inpainted_argb[:n].astype(np.int32)
    r = (p >> 16) & 0xFF
    g = (p >> 8) & 0xFF
    b = p & 0xFF
    # Kotlin uses 0.299/0.587/0.114 (Rec.601) — exact.
    luma = 0.299 * r + 0.587 * g + 0.114 * b
    luma_active = luma[active]
    sum_luma = float(luma_active.sum())
    sum_luma_sq = float((luma_active * luma_active).sum())
    # |r-g| + |g-b| over active.
    ch_delta = (np.abs(r - g) + np.abs(g - b))[active]
    sum_channel_delta = float(ch_delta.sum())

    mean = sum_luma / count
    variance = (sum_luma_sq / count) - (mean * mean)
    channel_delta = sum_channel_delta / count
    return GuardStats(mean, variance, channel_delta, count)


def classify(stats: GuardStats) -> bool:
    """Mirrors AotOutputGuard.classify — True if the output is a uniform-fill failure."""
    if stats.masked_count < MIN_MASKED_PIXELS:
        return False
    uniform = stats.variance < MAX_LUMA_VARIANCE and stats.channel_delta < MAX_CHANNEL_DELTA
    if not uniform:
        return False
    uniform_near_black = stats.mean <= NEAR_BLACK_MAX
    uniform_mid_gray = MID_GRAY_MIN <= stats.mean <= MID_GRAY_MAX
    uniform_near_white = stats.mean >= NEAR_WHITE_MIN
    return uniform_near_black or uniform_mid_gray or uniform_near_white


def is_suspicious_uniform_fill(inpainted_argb: np.ndarray, mask_argb: np.ndarray) -> bool:
    return classify(inspect(inpainted_argb, mask_argb))
