#!/usr/bin/env python3
"""Fetch pinned public colorizer model inputs and verify known file digests."""

import argparse
import hashlib
import json
import sys
from pathlib import Path

from huggingface_hub import snapshot_download

ROOT = Path(__file__).resolve().parents[1]
SOURCES = ROOT / "sources.json"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def verify(source_name: str, entry: dict, local_dir: Path) -> None:
    for relative in entry["required_files"]:
        path = local_dir / relative
        if not path.is_file():
            raise FileNotFoundError(f"{source_name}: required file missing: {path}")
    for relative, expected in entry.get("checks", {}).items():
        path = local_dir / relative
        if "size_bytes" in expected and path.stat().st_size != expected["size_bytes"]:
            raise ValueError(
                f"{source_name}: size mismatch for {relative}: "
                f"{path.stat().st_size} != {expected['size_bytes']}"
            )
        if "sha256" in expected:
            actual = sha256(path)
            if actual != expected["sha256"]:
                raise ValueError(f"{source_name}: SHA-256 mismatch for {relative}: {actual}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("models", nargs="*", choices=("sana_color_v2", "sana_base", "mcv2_onnx"))
    parser.add_argument("--root", type=Path, default=ROOT, help="workbench root (default: parent of scripts)")
    args = parser.parse_args()

    with (args.root / "sources.json").open(encoding="utf-8") as handle:
        sources = json.load(handle)
    selected = args.models or list(sources)
    for name in selected:
        entry = sources[name]
        target = args.root / entry["target"]
        target.mkdir(parents=True, exist_ok=True)
        print(f"FETCH {name} repo={entry['repo_id']} revision={entry['revision']}", flush=True)
        snapshot_download(
            repo_id=entry["repo_id"],
            revision=entry["revision"],
            allow_patterns=entry["allow_patterns"],
            local_dir=str(target),
        )
        verify(name, entry, target)
        print(f"VERIFIED {name} files={len(entry['required_files'])} target={target}", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
