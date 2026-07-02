"""Test: model scanning, no auto-process, explicit process, translator engines, block editing, llm test."""
import io, shutil, zipfile
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

    # 1. Model scanning
    r = c.get("/web/models")
    avail = r.json()["available"]
    ocr_ids = [m["id"] for m in avail["ocr"]]
    assert "paddle_v6_small" not in ocr_ids
    assert "manga_ocr" in ocr_ids
    for stage in ["detector", "ocr", "inpaint"]:
        loaded = [m for m in avail[stage] if m.get("loaded")]
        assert len(loaded) <= 1
    print("1. Model scan OK")

    # 2. Upload
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as zf:
        for i in range(3):
            img = Image.new("RGB", (200, 300), (180, 180, 180))
            ib = io.BytesIO(); img.save(ib, format="PNG")
            zf.writestr(f"page{i+1}.png", ib.getvalue())
    buf.seek(0)
    r = c.post("/web/upload", files={"file": ("test.zip", buf.read(), "application/zip")}, data={"target_lang":"ENGLISH"})
    cid = r.json()["chapter_id"]
    print("2. Upload OK")

    # 3. No auto-process
    r = c.get(f"/web/{cid}/page/0")
    assert r.json().get("processed") == False
    print("3. No auto-process OK")

    # 4. Process page
    r = c.post(f"/web/{cid}/page/0/process")
    assert r.json().get("translated") == False
    cache_dir = DATA_ROOT / "chapters" / cid / "cache"
    cleaned_path = cache_dir / "0_cleaned.png"
    assert cleaned_path.exists()
    print("4. Process OK")

    # 4b. Missing cleaned artifact invalidates cached metadata and regenerates.
    cleaned_path.unlink()
    r = c.get(f"/web/{cid}/page/0")
    assert r.json().get("processed") == False
    assert r.json().get("inpaintStatus") == "FAILED"
    r = c.post(f"/web/{cid}/page/0/process")
    assert r.status_code == 200
    assert cleaned_path.exists()
    print("4b. Missing cleaned artifact gate OK")

    # 5. Update settings (no crash)
    r = c.put("/web/settings", json={"target_lang": "FRENCH"})
    assert r.status_code == 200
    print("5. Settings update OK")

    # 6. All translator engines from Android app
    engines = [
        {"engine": "gemini", "api_key": "fake", "model": "gemini-1.5-flash"},
        {"engine": "openrouter", "api_key": "fake", "model": "test"},
        {"engine": "deepseek", "api_key": "fake", "model": "deepseek-chat"},
        {"engine": "lmstudio", "base_url": "http://localhost:1234/v1", "model": "test"},
        {"engine": "google"},
        {"engine": "deepl", "api_key": "fake-key"},
        {"engine": "none"},
    ]
    for tc in engines:
        r = c.put("/web/settings", json={"translator": tc})
        assert r.status_code == 200, f"Failed for engine={tc['engine']}: {r.status_code}"
        r = c.get("/web/settings")
        assert r.json().get("translator", {}).get("engine") == tc["engine"]
    print("6. All 7 engines OK")

    # 7. Model fetcher — provider-specific
    r = c.post("/web/llm/models", json={"engine": "gemini", "api_key": ""})
    assert r.status_code == 502  # missing key
    r = c.post("/web/llm/models", json={"engine": "lmstudio", "base_url": ""})
    assert r.status_code == 502  # missing base_url
    r = c.post("/web/llm/models", json={"engine": "deepseek", "api_key": "fake"})
    assert r.status_code in (200, 502)  # might fail with fake key
    print("7. Model fetcher OK")

    # 8. Translate page (placeholder engine = none)
    c.put("/web/settings", json={"translator": {"engine": "none"}})
    r = c.post(f"/web/{cid}/page/0/translate")
    assert r.json().get("translated") == True
    print("8. Batch translate OK")

    # 9. Block editing
    r = c.get(f"/web/{cid}/page/0")
    blocks = r.json().get("blocks", [])
    if blocks:
        bi = blocks[0]["index"]
        r = c.put(f"/web/{cid}/page/0/block/{bi}", json={"text": "EDITED", "translation": "TRANSLATED"})
        assert r.status_code == 200
    print("9. Block editing OK")

    # 10. LLM test
    r = c.post("/web/llm/test", json={"engine": "none", "target_lang": "ENGLISH"})
    assert r.status_code == 200
    assert "ok" in r.json()
    print("10. LLM test OK")

    shutil.rmtree(DATA_ROOT, ignore_errors=True)
    print("\n=== All tests passed! ===")

if __name__ == "__main__":
    main()
