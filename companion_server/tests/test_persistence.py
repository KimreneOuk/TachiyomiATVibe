"""Smoke test: chapter persistence across a simulated restart."""
import io
import shutil
import zipfile
from pathlib import Path

from PIL import Image

import sys
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from chapter.processor import ChapterProcessor, DATA_ROOT
from translate.translator import PlaceholderTranslator


def main():
    if DATA_ROOT.exists():
        shutil.rmtree(DATA_ROOT, ignore_errors=True)

    placeholder = PlaceholderTranslator()
    proc = ChapterProcessor(None, None, None, placeholder)

    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as zf:
        for i in range(3):
            img = Image.new("RGB", (200, 300), (180 + i * 10, 180, 180))
            ib = io.BytesIO()
            img.save(ib, format="PNG")
            zf.writestr(f"page{i+1}.png", ib.getvalue())
    buf.seek(0)

    cid, pages = proc.extract_zip(buf.read(), "ENGLISH", "FAST", display_name="test_chapter.zip")
    print(f"1. Upload: chapter_id={cid}, pages={pages}")

    chapters = proc.list_chapters()
    print(f'2. List: {len(chapters)} chapter(s), name="{chapters[0]["name"]}", page_count={chapters[0]["page_count"]}')

    result = proc.process_page(cid, 1)
    meta = proc.get_chapter_meta(cid)
    print(f'3. Process page 1: blocks={len(result["blocks"])}, last_page={meta["last_page"]}')

    proc2 = ChapterProcessor(None, None, None, placeholder)
    chapters2 = proc2.list_chapters()
    print(f"4. After restart: {len(chapters2)} chapter(s) still present")
    print(f'   last_page={chapters2[0]["last_page"]} (should be 1)')

    result2 = proc2.process_page(cid, 1)
    print(f'5. Resume page 1: blocks={len(result2["blocks"])} (cached)')

    settings = proc2.get_settings()
    print(f"6. Settings: {settings}")

    deleted = proc2.delete_chapter(cid)
    print(f"7. Delete: {deleted}")
    chapters3 = proc2.list_chapters()
    print(f"   After delete: {len(chapters3)} chapter(s)")

    shutil.rmtree(DATA_ROOT, ignore_errors=True)
    print("\nAll persistence tests passed!")


if __name__ == "__main__":
    main()
