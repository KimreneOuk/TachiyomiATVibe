"""
Parity test: the numpy reimplementation in aot_output_guard.py MUST match the
Kotlin AotOutputGuard.kt on the same inputs.

This is NOT a test of the Kotlin guard — that's covered by AotOutputGuardTest
in the Kotlin suite. This test asserts the PYTHON mirror agrees with the
Kotlin constants + logic so the offline corpus harness applies the same
verdict the production guard would.

Run:  python tools/aot_corpus/test_aot_output_guard_parity.py
Exits non-zero on any mismatch.
"""
from __future__ import annotations

import sys

import numpy as np

from aot_output_guard import (
    inspect, classify, is_suspicious_uniform_fill,
    NEAR_BLACK_MAX, MID_GRAY_MIN, MID_GRAY_MAX, NEAR_WHITE_MIN,
    MAX_LUMA_VARIANCE, MAX_CHANNEL_DELTA, MIN_MASKED_PIXELS,
)


def argb(r: int, g: int, b: int) -> int:
    """
    Mirrors AotOutputGuardTest.argb — opaque ARGB packed as a SIGNED 32-bit int
    (Kotlin Int is signed; 0xFF<<24 overflows to negative). We compute unsigned
    then cast through uint32 -> int32 so the bits match what the Kotlin guard
    sees in production.
    """
    unsigned = (0xFF << 24) | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF)
    return int(np.uint32(unsigned).astype(np.int32).item())


def make_pixels(fn) -> np.ndarray:
    """Build a 64-pixel IntArray-equivalent from an index -> ARGB function."""
    return np.array([fn(i) for i in range(64)], dtype=np.int32)


def _as_signed_int32(unsigned_val: int) -> int:
    """Cast an unsigned 32-bit value to signed int32 (Kotlin Int semantics)."""
    return int(np.uint32(unsigned_val).astype(np.int32).item())


def make_mask_all_active() -> np.ndarray:
    # AotOutputGuardTest uses 0xFFFFFFFF (opaque white) -> maskValue = max(255, 255) = 255.
    # 0xFFFFFFFF overflows signed int32 to -1; cast explicitly so numpy accepts it.
    return np.full(64, _as_signed_int32(0xFFFFFFFF), dtype=np.int32)


# Each test below MIRRORS a test in AotOutputGuardTest.kt and asserts the same
# verdict the Kotlin test asserts. If Kotlin says "rejected", Python must say
# "rejected" on the same input.

def test_uniform_mid_gray_rejected():
    pixels = np.full(64, argb(128, 128, 128), dtype=np.int32)
    mask = make_mask_all_active()
    assert is_suspicious_uniform_fill(pixels, mask) is True


def test_varied_grayscale_accepted():
    pixels = make_pixels(lambda i: argb(80 + (i % 8) * 16, 80 + (i % 8) * 16, 80 + (i % 8) * 16))
    mask = make_mask_all_active()
    assert is_suspicious_uniform_fill(pixels, mask) is False


def test_uniform_colored_accepted():
    pixels = np.full(64, argb(140, 112, 96), dtype=np.int32)
    mask = make_mask_all_active()
    assert is_suspicious_uniform_fill(pixels, mask) is False


def test_uniform_near_black_rejected():
    pixels = np.full(64, argb(10, 10, 10), dtype=np.int32)
    mask = make_mask_all_active()
    assert is_suspicious_uniform_fill(pixels, mask) is True


def test_uniform_near_white_rejected():
    pixels = np.full(64, argb(245, 245, 245), dtype=np.int32)
    mask = make_mask_all_active()
    assert is_suspicious_uniform_fill(pixels, mask) is True


def test_below_min_masked_pixels_accepted():
    """Fewer than 16 masked pixels — guard returns false (not enough data)."""
    pixels = np.full(64, argb(128, 128, 128), dtype=np.int32)  # uniform mid-gray
    mask = np.zeros(64, dtype=np.int32)  # all black: maskValue = max(0,0) = 0
    # Set only 8 pixels active.
    active = _as_signed_int32(0xFFFFFFFF)
    for i in range(8):
        mask[i] = active
    assert is_suspicious_uniform_fill(pixels, mask) is False


def test_stats_match_expected_values():
    """GuardStats.mean for uniform 128-gray must be exactly 128.0 (Rec.601 on gray)."""
    pixels = np.full(64, argb(128, 128, 128), dtype=np.int32)
    mask = make_mask_all_active()
    stats = inspect(pixels, mask)
    assert stats.masked_count == 64
    assert abs(stats.mean - 128.0) < 1e-9
    assert stats.variance < 1e-9  # uniform -> variance 0
    assert stats.channel_delta < 1e-9


def test_luma_weights_rec601():
    """
    Pure green (0,255,0) has Rec.601 luma 0.587*255 = 149.685, which is ABOVE
    the MID_GRAY band (96..160)? No — 149.685 IS in the band, so uniform green
    with low variance + low chroma would be flagged. But channel delta for pure
    green: |0-255|+|255-0| = 510 -> channel_delta 510/64 per pixel = high, so
    NOT uniform, so NOT flagged. Mirrors the production invariant.
    """
    pixels = np.full(64, argb(0, 255, 0), dtype=np.int32)
    mask = make_mask_all_active()
    stats = inspect(pixels, mask)
    # mean luma is 149.685 (in mid-gray band) BUT channel delta is huge.
    assert stats.channel_delta > MAX_CHANNEL_DELTA
    assert classify(stats) is False


def main() -> int:
    tests = [v for k, v in sorted(globals().items()) if k.startswith("test_")]
    failures = 0
    for t in tests:
        try:
            t()
            print(f"PASS  {t.__name__}")
        except AssertionError as e:
            failures += 1
            print(f"FAIL  {t.__name__}: {e}")
        except Exception as e:
            failures += 1
            print(f"ERROR {t.__name__}: {type(e).__name__}: {e}")
    print(f"\n{len(tests) - failures}/{len(tests)} passed")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
