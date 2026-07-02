"""End-to-end test: upload, batch process all pages in parallel, export, stats."""
import io
import shutil
import time
import zipfile
from pathlib import Path

from PIL import Image
from fastapi.testclient import TestClient

import sys
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from chapter.processor import DATA_ROOT


def main():
    if DATA_ROOT.exists():
        shutil.rmtree(DATA_ROOT, ignore_errors=True)

    from server import app
    client = TestClient(app)

    # 1. Upload a 5-page chapter
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as zf:
        for i in range(5):
            img = Image.new("RGB", (200, 300), (180 + i * 10, 180, 180))
            ib = io.BytesIO()
            img.save(ib, format="PNG")
            zf.writestr(f"page{i+1}.png", ib.getvalue())
    buf.seek(0)

    r = client.post(
        "/web/upload",
        files={"file": ("test_batch.zip", buf.read(), "application/zip")},
        data={"target_lang": "ENGLISH", "mode": "FAST"},
    )
    print(f"1. Upload: {r.status_code}")
    data = r.json()
    cid = data["chapter_id"]
    print(f"   chapter_id={cid}, pages={data['page_count']}")

    # 2. Start batch
    r = client.post(f"/web/{cid}/batch")
    print(f"2. Batch start: {r.status_code}")
    print(f"   {r.json()}")

    # 3. Poll until done
    for _ in range(30):
        r = client.get(f"/web/{cid}/batch/status")
        status = r.json()
        print(f"3. Status: processed={status['processed']}/{status['total']} running={status['running']} done={status['done']}")
        if status["done"]:
            break
        time.sleep(1)

    assert status["done"], "Batch did not complete"
    assert status["processed"] == 5, f"Expected 5 processed, got {status['processed']}"
    print(f"   Batch complete!")

    # 4. Get stats
    r = client.get(f"/web/{cid}/stats")
    stats = r.json()
    print(f"4. Stats: {stats}")
    assert stats["total_pages"] == 5
    assert stats["processed_pages"] == 5

    # 5. Export rendered
    r = client.get(f"/web/{cid}/export?type=rendered")
    print(f"5. Export: {r.status_code}, content-type={r.headers.get('content-type')}")
    assert r.status_code == 200
    assert "attachment" in r.headers.get("content-disposition", "")
    export_zip = zipfile.ZipFile(io.BytesIO(r.content))
    print(f"   Export contains: {export_zip.namelist()}")
    assert len(export_zip.namelist()) == 5

    # 6. Export original
    r = client.get(f"/web/{cid}/export?type=original")
    print(f"6. Export original: {r.status_code}, files={len(zipfile.ZipFile(io.BytesIO(r.content)).namelist())}")

    # 7. Delete
    r = client.delete(f"/web/chapters/{cid}")
    print(f"7. Delete: {r.status_code} {r.json()}")

    shutil.rmtree(DATA_ROOT, ignore_errors=True)
    print("\nAll batch/export/stats tests passed!")


if __name__ == "__main__":
    main()
