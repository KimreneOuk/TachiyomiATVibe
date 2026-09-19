"""Small, dependency-light array comparison helpers for model probes."""
from __future__ import annotations

from typing import Any

import numpy as np


def array_summary(value: Any) -> dict[str, Any]:
    """Return JSON-safe shape/dtype/size information for an array-like value."""
    arr = np.asarray(value)
    return {
        "shape": list(arr.shape),
        "dtype": str(arr.dtype),
        "elements": int(arr.size),
        "bytes": int(arr.nbytes),
    }


def diff_summary(reference: Any, candidate: Any) -> dict[str, Any]:
    """Compare two arrays and report shape plus absolute error statistics."""
    ref = np.asarray(reference)
    got = np.asarray(candidate)
    out: dict[str, Any] = {
        "reference": array_summary(ref),
        "candidate": array_summary(got),
        "shape_equal": bool(ref.shape == got.shape),
    }
    if ref.shape != got.shape:
        out.update({"max_abs": None, "mean_abs": None})
        return out
    delta = np.abs(ref.astype(np.float32) - got.astype(np.float32))
    out.update({
        "max_abs": float(delta.max()) if delta.size else 0.0,
        "mean_abs": float(delta.mean()) if delta.size else 0.0,
    })
    return out


def tensor_metrics(reference: Any, candidate: Any) -> dict[str, Any]:
    """Numerical comparison used by precision experiments."""
    ref = np.asarray(reference, dtype=np.float64)
    got = np.asarray(candidate, dtype=np.float64)
    if ref.shape != got.shape:
        return {"shape_match": False, "reference_shape": list(ref.shape), "candidate_shape": list(got.shape)}
    delta = got - ref
    denom = np.maximum(np.abs(ref), 1e-12)
    return {
        "shape_match": True,
        "reference_shape": list(ref.shape),
        "candidate_shape": list(got.shape),
        "reference_dtype": str(np.asarray(reference).dtype),
        "candidate_dtype": str(np.asarray(candidate).dtype),
        "reference_finite": bool(np.isfinite(ref).all()),
        "candidate_finite": bool(np.isfinite(got).all()),
        "max_abs": float(np.max(np.abs(delta))) if delta.size else 0.0,
        "mean_abs": float(np.mean(np.abs(delta))) if delta.size else 0.0,
        "rmse": float(np.sqrt(np.mean(delta * delta))) if delta.size else 0.0,
        "max_relative": float(np.max(np.abs(delta) / denom)) if delta.size else 0.0,
        "cosine": float(np.dot(ref.ravel(), got.ravel()) / max(np.linalg.norm(ref) * np.linalg.norm(got), 1e-12)) if delta.size else 1.0,
    }


def sequence_metrics(reference: Any, candidate: Any) -> dict[str, Any]:
    ref = [int(x) for x in reference]; got = [int(x) for x in candidate]
    common = min(len(ref), len(got)); prefix = 0
    while prefix < common and ref[prefix] == got[prefix]: prefix += 1
    return {"reference_length": len(ref), "candidate_length": len(got), "exact": ref == got, "common_prefix": prefix, "token_agreement": (sum(a == b for a, b in zip(ref, got)) / common) if common else float(len(ref) == len(got))}


def summarize_metrics(metrics: Any) -> dict[str, Any]:
    rows = list(metrics); out: dict[str, Any] = {"count": len(rows)}
    for key in ("max_abs", "mean_abs", "rmse", "max_relative", "cosine", "token_agreement"):
        values = [float(row[key]) for row in rows if key in row and isinstance(row[key], (int, float))]
        if values: out[key] = {"min": min(values), "median": float(np.median(values)), "max": max(values)}
    return out
