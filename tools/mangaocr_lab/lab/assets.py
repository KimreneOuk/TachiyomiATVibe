"""Model asset location, hashing, and lock verification (S0)."""
from __future__ import annotations

import hashlib
import json
import sys
from pathlib import Path

LAB_ROOT = Path(__file__).resolve().parents[1]
REPO_ROOT = Path(__file__).resolve().parents[3]
ASSET_DIR = REPO_ROOT / "app" / "src" / "main" / "assets" / "models" / "ocr"
LOCK_PATH = LAB_ROOT / "models.lock.json"

MODEL_NAMES = ("encoder.onnx", "decoder_init.onnx", "decoder_step.onnx", "vocab.txt")


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def load_lock() -> dict:
    with open(LOCK_PATH, encoding="utf-8") as f:
        return json.load(f)


def verify(allow_unlocked: bool = False) -> dict:
    """Returns {name: sha256}. Raises SystemExit on mismatch unless unlocked."""
    lock = load_lock()
    found = {}
    problems = []
    for name in MODEL_NAMES:
        p = ASSET_DIR / name
        if not p.exists():
            problems.append(f"missing asset: {p}")
            continue
        digest = sha256(p)
        found[name] = digest
        expected = lock["files"].get(name)
        status = "OK " if expected == digest else "UNLOCKED"
        print(f"  {name:20s} {digest[:16]}…  {status}")
        if expected != digest:
            problems.append(f"{name}: expected {expected}, found {digest}")
    if problems:
        for p in problems:
            print(f"  !! {p}", file=sys.stderr)
        if not allow_unlocked:
            raise SystemExit(
                "model assets do not match models.lock.json "
                "(pass --allow-unlocked-models to proceed, the run will be stamped)"
            )
    return found


def load_vocab() -> list[str]:
    vocab: list[str] = []
    with open(ASSET_DIR / "vocab.txt", encoding="utf-8") as f:
        for line in f:
            vocab.append(line.rstrip("\n"))
    return vocab


def model_paths() -> dict[str, Path]:
    return {name: ASSET_DIR / name for name in MODEL_NAMES}
