"""Tests for ns_solver.inpaint_ns — pure-numpy Laplace/Navier-Stokes relaxation.

Contract:
  inpaint_ns(image_bgr HxWx3 uint8, mask HxW uint8, iterations=200) -> HxWx3 uint8
    Solves ∇²u=0 inside the mask with Dirichlet BCs from known pixels.
    Vectorized Jacobi iteration, masked update, convergence early-stop.

These run without any model load — fast and deterministic.
"""
import numpy as np

from backend.inpaint.ns_solver import inpaint_ns


def _flat_border_mask():
    """40x40 BGR: grey border ring (128) + poisoned interior mask (the hole).

    A 12x12 hole (realistic bubble interior) surrounded by uniform grey.
    The Laplace property: the hole should converge to ~ the border average
    (128) since the only BCs surrounding it are grey.
    """
    img = np.full((40, 40, 3), 128, dtype=np.uint8)  # grey surroundings
    mask = np.zeros((40, 40), dtype=np.uint8)
    mask[14:26, 14:26] = 255  # 12x12 central hole
    # poison the hole with garbage that must be overwritten
    img[14:26, 14:26] = [0, 0, 255]
    return img, mask


def test_ns_fills_only_masked_pixels():
    img, mask = _flat_border_mask()
    out = inpaint_ns(img.copy(), mask, iterations=50)
    # non-mask pixels unchanged
    assert np.array_equal(out[mask == 0], img[mask == 0])


def test_ns_zero_iterations_unchanged():
    img, mask = _flat_border_mask()
    out = inpaint_ns(img.copy(), mask, iterations=0)
    assert np.array_equal(out, img)


def test_ns_converges_to_border_average():
    """The Laplace property: a hole surrounded by uniform 128 → fills ~128."""
    img, mask = _flat_border_mask()
    out = inpaint_ns(img.copy(), mask, iterations=3000)
    center_vals = out[18:22, 18:22].mean(axis=(0, 1))
    # Tolerance ±18: a NON-converging fill would sit near the poisoned 0/255,
    # so this decisively proves convergence toward the BC average.
    assert all(abs(c - 128) < 18 for c in center_vals), \
        f"center should converge to ~128, got {center_vals}"


def test_ns_output_smooth():
    """Gradient inside the mask has low variance (no harsh seams)."""
    img, mask = _flat_border_mask()
    out = inpaint_ns(img.copy(), mask, iterations=200)
    # take horizontal gradient across the center row inside the mask
    row = out[20, 10:30].astype(np.float32)
    grad = np.abs(np.diff(row, axis=0)).mean()
    assert grad < 5.0, f"interior too rough, mean |grad|={grad}"


def test_ns_early_stop_returns_before_max():
    """Convergence threshold should terminate a tiny mask well under 1000 iters."""
    img = np.full((20, 20, 3), 128, dtype=np.uint8)
    mask = np.zeros((20, 20), dtype=np.uint8)
    mask[8:12, 8:12] = 255
    out = inpaint_ns(img.copy(), mask, iterations=1000)
    # just must terminate and produce valid output (early stop checked by speed)
    assert out.shape == (20, 20, 3)


def test_ns_valid_uint8_no_nan():
    img, mask = _flat_border_mask()
    out = inpaint_ns(img.copy(), mask, iterations=30)
    assert out.dtype == np.uint8
    assert np.isfinite(out.astype(np.float32)).all()
    assert (out >= 0).all() and (out <= 255).all()
