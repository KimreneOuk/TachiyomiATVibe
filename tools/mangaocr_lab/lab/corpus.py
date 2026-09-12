"""Regression corpus discovery (S0)."""
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

IMAGE_EXTS = {".png", ".jpg", ".jpeg", ".webp", ".bmp"}

CATEGORIES = [
    "real", "digits", "kana", "kanji", "punctuation",
    "latin", "mixed", "long", "noisy", "empty", "previous_failures",
]


@dataclass
class Crop:
    crop_id: str        # "<category>/<stem>" — unique within the corpus
    path: Path
    category: str
    expected: str | None

    @property
    def stem(self) -> str:
        return self.path.stem


def discover(root: Path, category: str | None = None, limit: int | None = None) -> list[Crop]:
    """Enumerate crops recursively. crop_id is the path relative to root."""
    root = Path(root)
    if not root.exists():
        raise SystemExit(f"corpus root does not exist: {root}")
    crops: list[Crop] = []
    for p in sorted(root.rglob("*")):
        if p.suffix.lower() not in IMAGE_EXTS or not p.is_file():
            continue
        rel = p.relative_to(root)
        cat = rel.parts[0] if len(rel.parts) > 1 else "uncategorized"
        if category and cat != category:
            continue
        exp_path = p.with_suffix(".expected.txt")
        expected = exp_path.read_text(encoding="utf-8").strip() if exp_path.exists() else None
        crops.append(Crop(crop_id=rel.as_posix(), path=p, category=cat, expected=expected))
    if limit is not None:
        crops = crops[:limit]
    return crops


def category_counts(crops: list[Crop]) -> dict[str, int]:
    counts: dict[str, int] = {}
    for c in crops:
        counts[c.category] = counts.get(c.category, 0) + 1
    return dict(sorted(counts.items()))
