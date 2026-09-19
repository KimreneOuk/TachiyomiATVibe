#!/usr/bin/env python3
"""Build a fixed, path-only PP-OCRv6 chapter manifest from external fixtures."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any


IMAGE_SUFFIXES = {".png", ".jpg", ".jpeg", ".webp", ".avif"}


def load_annotations(chapter: Path) -> dict[str, Any]:
    path = chapter / ".studio" / "ocr.json"
    if not path.is_file():
        return {}
    return json.loads(path.read_text(encoding="utf-8"))


def categories(annotation: dict[str, Any]) -> list[str]:
    pages = annotation.values()
    classes = {str(region.get("class")) for page in pages for region in page.get("regions", [])}
    values = ["ppocrv6-det-rec", "japanese-text", "studio-ocr-annotated"]
    if "text_bubble" in classes:
        values.append("bubble-text")
    if "text_free" in classes:
        values.append("free-text")
    return values


def chapter_manifest(source_id: str, chapter: Path) -> dict[str, Any]:
    annotation = load_annotations(chapter)
    pages = []
    for index, page in enumerate(
        sorted((p for p in chapter.iterdir() if p.is_file() and p.suffix.lower() in IMAGE_SUFFIXES), key=lambda p: p.name),
        start=1,
    ):
        page_annotation = annotation.get(page.name, {})
        regions = []
        for region in page_annotation.get("regions", []):
            regions.append(
                {
                    "id": region.get("id"),
                    "class": region.get("class"),
                    "label": region.get("label"),
                    "score": region.get("score"),
                    "box": region.get("box"),
                    "ocr_box": region.get("ocr_box"),
                    "source_annotation": str(chapter / ".studio" / "ocr.json"),
                }
            )
        pages.append(
            {
                "index": index,
                "filename": page.name,
                "source_path": str(page),
                "fixture_categories": categories({page.name: page_annotation}),
                "regions": regions,
                "region_count": len(regions),
                "annotation_engine": page_annotation.get("engine"),
            }
        )
    return {
        "source_id": source_id,
        "chapter": chapter.name,
        "source_path": str(chapter),
        "annotation_path": str(chapter / ".studio" / "ocr.json") if annotation else None,
        "page_count": len(pages),
        "fixture_categories": categories(annotation),
        "pages": pages,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--supplied", type=Path, required=True)
    parser.add_argument("--downloads", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    chapters = [chapter_manifest("supplied", args.supplied)]
    chapters.extend(
        chapter_manifest("downloaded", chapter)
        for chapter in sorted(args.downloads.iterdir(), key=lambda p: p.name)
        if chapter.is_dir() and chapter.name != ".studio"
    )
    result = {
        "schema_version": 1,
        "manifest_kind": "fixed-ppocrv6-page-region-fixtures",
        "provenance": {
            "generated_by": "research/scripts/build_dataset_manifest.py",
            "page_files_are_external": True,
            "images_copied_into_worktree": False,
        },
        "chapters": chapters,
        "totals": {
            "chapter_count": len(chapters),
            "page_count": sum(chapter["page_count"] for chapter in chapters),
            "region_count": sum(page["region_count"] for chapter in chapters for page in chapter["pages"]),
        },
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
