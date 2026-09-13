#!/usr/bin/env python3
"""Translation Studio — local web app for visual translation-quality work.

Usage:
  python studio.py --chapter <folder-of-one-chapter> [--reference <goal-folder>]
  python studio.py                 (then pick folders in the UI)

Opens http://127.0.0.1:8765 in your browser. Stages per page:
  Detect (the app's detector-v4 ONNX) -> OCR (T927 corrected batched decoder)
  -> Translate (hand-edit + cache, or LM Studio / OpenAI-compatible endpoint)
  -> Render (erase + auto-fit text). Caches live in <chapter>/.studio/.
"""
from __future__ import annotations

import argparse
from pathlib import Path

from server import serve


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--chapter", help="chapter folder (pages anywhere below it)")
    ap.add_argument("--reference", help="goal/comparison folder with same page names")
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--no-browser", action="store_true")
    args = ap.parse_args()

    if args.chapter:
        from pipeline import PIPELINE
        PIPELINE.open_folders(args.chapter, args.reference)
    else:
        # Load most recent chapter (pre-seeded with DEFAULT_CHAPTER)
        from pipeline import PIPELINE, DEFAULT_CHAPTER
        recents = PIPELINE.get_recent()
        opened = False
        if recents:
            first = recents[0].get("chapter")
            if first and Path(first).is_dir():
                try:
                    PIPELINE.open_folders(first, recents[0].get("reference"))
                    opened = True
                except Exception as e:
                    print(f"Warning: could not open recent chapter {first}: {e}")
        if not opened and Path(DEFAULT_CHAPTER).is_dir():
            PIPELINE.open_folders(DEFAULT_CHAPTER, args.reference)
    serve(args.port, open_browser=not args.no_browser)


if __name__ == "__main__":
    main()
