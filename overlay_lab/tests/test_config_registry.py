"""Tests for the model registries (B2): config.SEG_MODELS / DET_MODELS.

Contract:
  config.SEG_MODELS : list[dict] with keys {name, path, size_bytes}
  config.DET_MODELS : list[dict] with keys {name, path, size_bytes}
  config.find_seg_model(name: str | None) -> Path | None
      - None or "" → returns the default (best_int8.onnx — the Android-constrained
        int8 export that will ship on a 6GB device; the lab uses it so results
        are representative of the Android result)
      - known name → that model's path
      - unknown name → None
  config.default_seg_model_name() -> str
"""
from pathlib import Path

from backend import config


# ── registry shape ────────────────────────────────────────────────────


def test_seg_models_is_list_of_dicts():
    assert isinstance(config.SEG_MODELS, list)
    assert len(config.SEG_MODELS) >= 1
    for m in config.SEG_MODELS:
        assert {"name", "path", "size_bytes"} <= set(m.keys())
        assert isinstance(m["path"], Path)


def test_det_models_is_list_of_dicts():
    assert isinstance(config.DET_MODELS, list)
    assert len(config.DET_MODELS) >= 1
    for m in config.DET_MODELS:
        assert {"name", "path", "size_bytes"} <= set(m.keys())


def test_detector_v4_in_registry():
    names = [m["name"] for m in config.DET_MODELS]
    assert "detector-v4" in names


# ── find_seg_model ────────────────────────────────────────────────────


def test_find_seg_model_int8_present():
    """best_int8.onnx must be registered if it exists on disk."""
    names = [m["name"] for m in config.SEG_MODELS]
    assert "best_int8.onnx" in names, "int8 model must be in registry (user requirement)"


def test_find_seg_model_by_name():
    p = config.find_seg_model("best_int8.onnx")
    assert p is not None
    assert p.name == "best_int8.onnx"


def test_find_seg_model_unknown_returns_none():
    assert config.find_seg_model("does_not_exist.onnx") is None


def test_find_seg_model_default_is_int8():
    """Default must resolve to best_int8.onnx when present.

    The int8 export is the Android-constrained variant (3.4 MB, fixed 640×640)
    that will ship on a 6GB device. The lab defaults to it so its results are
    representative of the Android result. The dynamic best.onnx remains
    available via an explicit name override for higher small-bubble recall.
    """
    p = config.find_seg_model(None)
    if p is not None:
        assert p.name == "best_int8.onnx", \
            f"default seg model must be best_int8.onnx, got {p.name}"


def test_default_seg_model_name():
    name = config.default_seg_model_name()
    assert name == "best_int8.onnx"
