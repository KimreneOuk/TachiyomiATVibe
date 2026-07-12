"""E2E: library → upload → view (no auto-translate) → translate page → settings → batch."""
import io, shutil, time, zipfile
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
    c = TestClient(app)

    # 1. Library starts empty
    r = c.get("/web/chapters")
    print(f"1. Library (empty): {r.json()}")

    # 2. Upload 3-page chapter
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as zf:
        for i in range(3):
            img = Image.new("RGB", (200, 300), (180+i*10, 180, 180))
            ib = io.BytesIO(); img.save(ib, format="PNG")
            zf.writestr(f"page{i+1}.png", ib.getvalue())
    buf.seek(0)
    r = c.post("/web/upload", files={"file": ("test.zip", buf.read(), "application/zip")}, data={"target_lang":"ENGLISH","mode":"FAST"})
    cid = r.json()["chapter_id"]
    print(f"2. Upload: cid={cid}, pages={r.json()['page_count']}")

    # 3. Library now shows 1 chapter
    r = c.get("/web/chapters")
    print(f"3. Library: {len(r.json()['chapters'])} chapter(s)")

    # 4. View page 0 — should NOT auto-translate
    r = c.get(f"/web/{cid}/page/0")
    pdata = r.json()
    has_translation = any(b.get("translation","").strip() for b in pdata.get("blocks",[]))
    print(f"4. View page 0: blocks={len(pdata['blocks'])}, translated={pdata.get('translated')}, has_translations={has_translation}")
    assert pdata.get("translated") == False, "Should not be auto-translated"
    assert not has_translation, "Blocks should have empty translations"

    # 5. Get settings — translator should be none/placeholder
    r = c.get("/web/settings")
    print(f"5. Settings: {r.json()}")
    assert "translator" in r.json()

    # 6. Update translator config
    r = c.put("/web/settings", json={"translator": {"engine":"openai_compatible","base_url":"http://localhost:9999/v1","model":"test-model","temperature":0.3}})
    print(f"6. Update translator: {r.json()}")

    # 7. Verify settings persisted
    r = c.get("/web/settings")
    tc = r.json().get("translator",{})
    print(f"7. Translator config: engine={tc.get('engine')}, model={tc.get('model')}")
    assert tc.get("engine") == "openai_compatible"
    assert tc.get("model") == "test-model"

    # 8. Translate page 0 (translator will fall back to placeholder since localhost:9999 is unreachable)
    r = c.post(f"/web/{cid}/page/0/translate")
    tdata = r.json()
    has_translation = any(b.get("translation","").strip() for b in tdata.get("blocks",[]))
    print(f"8. Translate page 0: translated={tdata.get('translated')}, has_translations={has_translation}")
    assert tdata.get("translated") == True

    # 9. Stats
    r = c.get(f"/web/{cid}/stats")
    print(f"9. Stats: {r.json()}")

    # 10. Batch with translation
    r = c.post(f"/web/{cid}/batch?translate=true")
    print(f"10. Batch start: {r.json().get('total')} pages")
    for _ in range(30):
        r = c.get(f"/web/{cid}/batch/status")
        s = r.json()
        if s["done"]: break
        time.sleep(1)
    print(f"    Batch done: {s['processed']}/{s['total']}")

    # 11. Export
    r = c.get(f"/web/{cid}/export?type=rendered")
    ez = zipfile.ZipFile(io.BytesIO(r.content))
    print(f"11. Export: {len(ez.namelist())} files")

    # 12. Settings shows models
    r = c.get("/v1/health")
    print(f"12. Health models: {r.json()['models']}")

    # 13. Delete and verify library empty
    c.delete(f"/web/chapters/{cid}")
    r = c.get("/web/chapters")
    print(f"13. After delete: {len(r.json()['chapters'])} chapter(s)")

    shutil.rmtree(DATA_ROOT, ignore_errors=True)
    print("\n=== All tests passed! ===")

if __name__ == "__main__":
    main()
