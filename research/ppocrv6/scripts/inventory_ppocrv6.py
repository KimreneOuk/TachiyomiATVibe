#!/usr/bin/env python3
"""Inventory PP-OCRv6 assets and external chapter fixtures without copying pages."""

from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
from pathlib import Path
from typing import Any


IMAGE_SUFFIXES = {".png", ".jpg", ".jpeg", ".webp", ".avif"}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def file_record(path: Path, root: Path) -> dict[str, Any]:
    return {
        "name": path.name,
        "relative_path": path.relative_to(root).as_posix(),
        "bytes": path.stat().st_size,
        "sha256": sha256(path),
    }


def annotation_record(chapter: Path) -> dict[str, Any]:
    studio = chapter / ".studio"
    return {
        "studio_exists": studio.is_dir(),
        "ocr_json": file_record(studio / "ocr.json", chapter) if (studio / "ocr.json").is_file() else None,
        "detections_json": file_record(studio / "detections.json", chapter) if (studio / "detections.json").is_file() else None,
        "settings_json": file_record(studio / "settings.json", chapter) if (studio / "settings.json").is_file() else None,
        "studio_file_count": sum(1 for item in studio.rglob("*") if item.is_file()) if studio.is_dir() else 0,
    }


def chapter_record(source_id: str, chapter: Path) -> dict[str, Any]:
    pages = sorted(
        (item for item in chapter.iterdir() if item.is_file() and item.suffix.lower() in IMAGE_SUFFIXES),
        key=lambda item: item.name,
    )
    return {
        "source_id": source_id,
        "chapter": chapter.name,
        "source_path": str(chapter),
        "page_count": len(pages),
        "page_files": [file_record(page, chapter) for page in pages],
        "annotations": annotation_record(chapter),
    }


def chapters_under(source_id: str, root: Path, include_root: bool) -> list[dict[str, Any]]:
    if not root.is_dir():
        return []
    if include_root:
        return [chapter_record(source_id, root)]
    return [
        chapter_record(source_id, chapter)
        for chapter in sorted(root.iterdir(), key=lambda item: item.name)
        if chapter.is_dir() and chapter.name != ".studio"
    ]


def model_record(path: Path, root: Path) -> dict[str, Any]:
    return file_record(path, root) if path.is_file() else {"relative_path": path.relative_to(root).as_posix(), "missing": True}


def git_revision(path: Path) -> str | None:
    try:
        return subprocess.check_output(["git", "-C", str(path), "rev-parse", "HEAD"], text=True).strip()
    except (OSError, subprocess.CalledProcessError):
        return None


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--supplied", type=Path, required=True)
    parser.add_argument("--downloads", type=Path, required=True)
    parser.add_argument("--requested-downloads", type=Path)
    parser.add_argument("--repo", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    worktree = Path.cwd()
    model_root = worktree / "app" / "src" / "main" / "assets" / "models" / "ocr" / "paddle-v6-small"
    repo_files = [".gitattributes", "README.md", "inference.json", "inference.onnx", "inference.yml"]
    chapters = chapters_under("supplied", args.supplied, True) + chapters_under("downloaded", args.downloads, False)
    result: dict[str, Any] = {
        "schema_version": 1,
        "inventory_kind": "ppocrv6-bootstrap",
        "worktree": str(worktree),
        "branch": subprocess.check_output(["git", "branch", "--show-current"], text=True).strip(),
        "sources": {
            "supplied_chapter": str(args.supplied),
            "downloads_root": str(args.downloads),
            "requested_downloads_root": str(args.requested_downloads) if args.requested_downloads else None,
        },
        "chapters": chapters,
        "totals": {
            "chapter_count": len(chapters),
            "page_count": sum(item["page_count"] for item in chapters),
        },
        "model_repository": {
            "source": "https://huggingface.co/PaddlePaddle/PP-OCRv6_small_det_onnx",
            "revision": git_revision(args.repo),
            "files": [model_record(args.repo / name, args.repo) for name in repo_files],
        },
        "in_app_models": {
            "ctd_detector": model_record(worktree / "app" / "src" / "main" / "assets" / "models" / "detection" / "detector-v4-s_int8.onnx", worktree),
            "panel_detector": model_record(worktree / "app" / "src" / "main" / "assets" / "models" / "detection" / "manga_panel_detector_int8.onnx", worktree),
            "detector": model_record(model_root / "det" / "inference.onnx", worktree),
            "detector_metadata": model_record(model_root / "det" / "inference.yml", worktree),
            "recognizer": model_record(model_root / "inference.onnx", worktree),
            "recognizer_metadata": model_record(model_root / "inference.yml", worktree),
            "recognizer_dictionary": model_record(model_root / "PP-OCRv6_small_rec.txt", worktree),
        },
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
