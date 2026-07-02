from __future__ import annotations

from pathlib import Path
from typing import Any

try:
    import yaml
except Exception:  # pragma: no cover - optional dependency for local tests
    yaml = None


ROOT = Path(__file__).resolve().parent


def load_config(path: str | Path = ROOT / "config.yaml") -> dict[str, Any]:
    config_path = Path(path)
    if not config_path.is_absolute():
        config_path = ROOT / config_path
    if yaml is None or not config_path.exists():
        return default_config()
    with config_path.open("r", encoding="utf-8") as handle:
        loaded = yaml.safe_load(handle) or {}
    return loaded


def resolve_model_path(raw_path: str | Path) -> Path:
    path = Path(raw_path)
    if path.is_absolute():
        return path
    return (ROOT / path).resolve()


def default_config() -> dict[str, Any]:
    return {
        "detector": {"path": "../app/src/main/assets/models/detection/detector-v4-s_int8.onnx"},
        "server": {"max_concurrency": 4, "device": "cpu"},
    }
