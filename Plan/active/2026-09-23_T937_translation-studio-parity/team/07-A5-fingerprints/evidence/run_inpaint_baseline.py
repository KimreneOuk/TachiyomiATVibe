"""Run the Studio inpaint baseline without touching the demo's saved cache."""
from __future__ import annotations

import shutil
import sys
import tempfile
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[6]
STUDIO_DIR = REPO_ROOT / "tools" / "translation_studio"
sys.path.insert(0, str(STUDIO_DIR))

import pipeline  # noqa: E402
import selftest_inpaint  # noqa: E402


def main() -> None:
    with tempfile.TemporaryDirectory(prefix="t937-a5-inpaint-") as temp:
        temp_root = Path(temp)
        chapter = temp_root / "chapter"
        chapter.mkdir()
        for source in (STUDIO_DIR / "demo_chapter").glob("*"):
            if source.is_file():
                shutil.copy2(source, chapter / source.name)

        selftest_inpaint.DEMO_CHAPTER = chapter
        selftest_inpaint.EVIDENCE = Path(__file__).resolve().parent / "inpaint_baseline"
        pipeline.RECENT_FILE = temp_root / "recent.json"
        selftest_inpaint.main()


if __name__ == "__main__":
    main()
