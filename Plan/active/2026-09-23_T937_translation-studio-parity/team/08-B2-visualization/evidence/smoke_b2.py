"""Headless B2 UI and route smoke using temporary cached demo artifacts."""
from __future__ import annotations

import json
import shutil
import socket
import subprocess
import sys
import tempfile
import time
from io import BytesIO
from pathlib import Path
from urllib.error import URLError
from urllib.parse import quote
from urllib.request import Request, urlopen

from PIL import Image, ImageDraw
from playwright.sync_api import sync_playwright


REPO = next(parent for parent in Path(__file__).resolve().parents
            if (parent / "tools" / "translation_studio").is_dir())
STUDIO = REPO / "tools" / "translation_studio"
DEMO = STUDIO / "demo_chapter"
EVIDENCE = Path(__file__).resolve().parent
PAGE = "p001.jpg"


def _write_json(path: Path, data: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")


def _record(artifact_id: str, model: str, label: int | str, score: float,
            box: list[float], state: str, window=None) -> dict:
    x1, y1, x2, y2 = box
    width, height = x2 - x1, y2 - y1
    return {
        "id": artifact_id,
        "page": PAGE,
        "kind": "box",
        "source": {"model": model, "asset": f"{model}.onnx",
                   "asset_sha": "1234567890ab", "window": window},
        "geometry": {"x1": x1, "y1": y1, "x2": x2, "y2": y2},
        "attrs": {"label": label, "score": score,
                  "shape": {"w": width, "h": height, "ar": width / height}},
        "lifecycle": {"state": state, "trace": [{"step": "floor", "kept": state != "raw", "threshold": 0.6}]},
    }


def _make_fixture(chapter: Path) -> tuple[int, int]:
    shutil.copytree(DEMO, chapter)
    # Determinism guard: the repo demo folder is gitignored and accumulates
    # generated artifacts from interactive/automated runs. Strip every
    # p002-stage artifact and generated cache so this fixture asserts against
    # a known state (p001 caches are fully overwritten below; p002 must end
    # with NO caches at all).
    _studio = chapter / ".studio"
    for _sub in ("inpaint", "inpaint_mask", "render", "thumbs", "inpaint_crops"):
        _dir = _studio / _sub
        if _dir.is_dir():
            for _f in _dir.glob("p002*"):
                if _f.is_dir():
                    shutil.rmtree(_f, ignore_errors=True)
                else:
                    _f.unlink()
    for _name in ("detections.json", "ocr.json", "translations.json"):
        _file = _studio / _name
        if _file.is_file():
            _data = json.loads(_file.read_text(encoding="utf-8"))
            _data.pop("p002.jpg", None)
            _write_json(_file, _data)
    page_path = chapter / PAGE
    with Image.open(page_path) as source:
        width, height = source.size
        original = source.convert("RGB")

    top = min(150, max(12, height // 10))
    left = min(120, max(12, width // 10))
    right = min(width - 8, left + max(80, width // 8))
    bottom = min(height - 8, top + max(42, height // 20))
    primary_box = [left, top, right, bottom]
    low_box = [max(2, left + 24), min(height - 4, bottom + 20),
               min(width - 4, right + 24), min(height - 2, bottom + 64)]

    raw_survivor = _record("td0001", "text-detector", 1, 0.92, primary_box, "kept")
    raw_loser = _record("td0002", "text-detector", 1, 0.71, primary_box, "suppressed")
    raw_low = _record("td-low", "text-detector", 2, 0.42, low_box, "raw")
    derived_survivor = _record("dt-td0001", "text-detector", 1, 0.92, primary_box, "kept")
    derived_survivor["attrs"]["artifact_id"] = "dt-td0001"
    derived_survivor["lifecycle"]["trace"].extend([
        {"step": "det-dedup", "kept": True},
        {"step": "ocr-dedup", "kept": True},
        {"step": "xlabel", "kept": True},
    ])
    derived_loser = _record("dt-td0002", "text-detector", 1, 0.71, primary_box, "suppressed")
    derived_loser["attrs"]["artifact_id"] = "dt-td0002"
    derived_loser["lifecycle"]["trace"].append({
        "step": "ocr-dedup", "kept": False, "suppressed_by": "dt-td0001", "threshold": 0.62,
    })

    panel = _record("pd0001", "panel-detector", "panel", 0.88,
                    [1, 1, width - 1, height - 1], "kept")
    mask_runs = []
    for y in range(top, bottom):
        mask_runs.extend((y * width + left, right - left))
    segmenter = {
        "id": "bs0001", "page": PAGE, "kind": "mask",
        "source": {"model": "bubble-segmenter", "asset": "segmenter.onnx",
                   "asset_sha": "abcdef123456", "window": None},
        "mask_ref": "mask-1", "attrs": {"label": 0, "score": 1.0, "component_count": 1},
        "lifecycle": {"state": "kept", "trace": [{"step": "segmenter", "kept": True}]},
    }
    detection_cache = {
        "capture_version": 1,
        "page_wh": [width, height],
        "is_tall": False,
        "windows": [{"index": None, "top": 0, "bottom": height, "height": height}],
        "models": {
            "text-detector": {"status": "ready", "asset": "text-detector.onnx",
                              "outputs": [raw_survivor, raw_loser, raw_low],
                              "derived_outputs": [derived_survivor, derived_loser]},
            "panel-detector": {"status": "ready", "outputs": [panel]},
            "bubble-segmenter": {"status": "ready", "outputs": [segmenter]},
        },
        "mask_cache": {"bubble-segmenter": {
            "mask-1": {"width": width, "height": height, "runs": mask_runs,
                       "bounds": [left, top, right, bottom], "components": {"4": mask_runs}},
        }},
        "decisions": {
            "conf": 0.6,
            "suppression_records": [{"loser_id": "dt-td0002", "winner_id": "dt-td0001",
                                      "rule": "ocr-dedup", "threshold": 0.62}],
            "merge_records": [], "kept_ids": ["dt-td0001"], "context_ids": [],
        },
    }
    ocr_cache = {"engine": "mangaocr", "regions": [{
        "id": "r00", "artifact_id": "dt-td0001", "label": 1, "score": 0.92,
        "box": primary_box, "ocr_box": [max(0, left - 6), max(0, top - 6), right + 6, bottom + 6],
        "text": "cached source line", "raw_text": "cached source line", "confidence": 0.98,
    }]}
    inpaint_record = {
        "schema_version": 2,
        "execution": {"confidence_threshold": 0.6},
        "regions": [{
            "id": "r00", "artifact_id": "dt-td0001", "kind": "bubble", "label": 1,
            "score": 0.92, "text": "cached source line", "route_taken": "bubble/android-fill",
            "mask_resolution": {"mask_ref": "mask-1"}, "segmenter_component_id": 4,
            "erase_mask_component_id": 1, "context_crop_bbox": primary_box,
            "timing_ms": {"fill": 1.2}, "timing_scope": "region",
            "source_boxes": [
                {"role": "ocr-origin", "box": primary_box},
                {"role": "refined-line", "source": "paddle-refined", "box": [left + 4, top + 10, right - 4, bottom - 10]},
            ],
        }],
    }
    studio = chapter / ".studio"
    _write_json(studio / "detections.json", {PAGE: detection_cache})
    _write_json(studio / "ocr.json", {PAGE: ocr_cache})
    _write_json(studio / "translations.json", {PAGE: {"r00": "cached translation"}})
    _write_json(studio / "inpaint" / f"{page_path.stem}.json", inpaint_record)
    inpaint_path = studio / "inpaint" / f"{page_path.stem}.png"
    inpaint_path.parent.mkdir(parents=True, exist_ok=True)
    original.save(inpaint_path, format="PNG")
    render_path = studio / "render" / f"{page_path.stem}.png"
    render_path.parent.mkdir(parents=True, exist_ok=True)
    original.save(render_path, format="PNG")
    mask = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    ImageDraw.Draw(mask).rectangle(primary_box, fill=(255, 65, 100, 170))
    mask_path = studio / "inpaint_mask" / f"{page_path.stem}.png"
    mask_path.parent.mkdir(parents=True, exist_ok=True)
    mask.save(mask_path, format="PNG")
    return width, height


def _request(url: str, payload: dict | None = None) -> tuple[int, bytes, str]:
    data = None if payload is None else json.dumps(payload).encode("utf-8")
    request = Request(url, data=data, headers={"Content-Type": "application/json"} if data else {})
    try:
        with urlopen(request, timeout=5) as response:
            return response.status, response.read(), response.headers.get("Content-Type", "")
    except URLError:
        raise


def main() -> None:
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    results: dict = {"checks": []}
    with tempfile.TemporaryDirectory(prefix="t937-b2-demo-") as temp_root:
        chapter = Path(temp_root) / "demo_chapter"
        width, height = _make_fixture(chapter)
        with socket.socket() as sock:
            sock.bind(("127.0.0.1", 0))
            port = sock.getsockname()[1]
        base = f"http://127.0.0.1:{port}"
        server_code = (
            "import server; server.PIPELINE.record_recent=lambda *args, **kwargs: None; "
            f"server.serve(port={port}, open_browser=False)"
        )
        process = subprocess.Popen([sys.executable, "-c", server_code], cwd=STUDIO,
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        try:
            ready = False
            for _ in range(60):
                try:
                    _request(f"{base}/api/state")
                    ready = True
                    break
                except Exception:
                    if process.poll() is not None:
                        raise RuntimeError("Translation Studio server exited during startup")
                    time.sleep(0.2)
            assert ready, "server did not become ready"

            status, body, _ = _request(f"{base}/api/open", {"chapter": str(chapter)})
            assert status == 200 and json.loads(body)["pages"][0] == PAGE
            results["checks"].append("open temporary demo_chapter with current A1/A4 cache fixtures")

            status, body, _ = _request(f"{base}/api/artifacts?p={quote(PAGE)}")
            artifact_payload = json.loads(body)
            assert status == 200
            assert all(artifact_payload["sources"][key]["status"] == "ready"
                       for key in ("detections", "ocr", "translations", "inpaint"))
            results["checks"].append("GET /api/artifacts reads existing A1/A4/OCR/translation JSON")

            status, body, _ = _request(f"{base}/api/artifacts?p=p002.jpg")
            missing = json.loads(body)
            assert status == 200 and missing["sources"]["detections"]["status"] == "missing"
            assert ".studio/detections.json" in missing["sources"]["detections"]["reason"]
            results["checks"].append("missing cache source returns empty data with reason")

            status, body, content_type = _request(f"{base}/img/seg_overlay?p={quote(PAGE)}")
            composite = Image.open(BytesIO(body)).convert("RGB")
            original = Image.open(chapter / PAGE).convert("RGB")
            left = min(120, max(12, width // 10))
            top = min(150, max(12, height // 10))
            assert status == 200 and content_type.startswith("image/png")
            assert composite.size == (width, height)
            assert composite.getpixel((left + 1, top + 1)) != original.getpixel((left + 1, top + 1))
            results["checks"].append("GET /img/seg_overlay composites cached page-space RLE over the original")

            browser_errors: list[str] = []
            browser_posts: list[str] = []
            browser_image_gets: list[str] = []
            with sync_playwright() as playwright:
                browser = playwright.chromium.launch(headless=True)
                page = browser.new_page(viewport={"width": 1500, "height": 1100}, device_scale_factor=1)
                page.set_default_timeout(8000)
                page.on("pageerror", lambda error: browser_errors.append(str(error)))
                page.on("request", lambda request: browser_posts.append(request.url.split(base)[-1])
                        if request.method == "POST" else None)
                page.on("request", lambda request: browser_image_gets.append(request.url.split(base)[-1])
                        if request.method == "GET" and "/img/" in request.url else None)
                page.goto(base, wait_until="domcontentloaded")
                page.wait_for_function("document.querySelector('#filterPreset option[value=\\\"inpaint-audit\\\"]') !== null")
                page.wait_for_function("document.querySelector('[data-filter-count=\\\"text-detector\\\"]')?.textContent.includes('/')")
                page.wait_for_function("document.querySelector('[data-filter-count=\\\"text-detector\\\"]')?.textContent.split('/')[1] !== '0'")

                assert page.locator(".artifact-text-badge").count() > 0
                assert "cached source line" in (page.locator(".artifact-text-badge").first.text_content() or "")
                page.locator("#treeTextBadges").uncheck(force=True)
                page.wait_for_function("document.querySelectorAll('.artifact-text-badge').length === 0")
                page.locator("#treeTextBadges").check(force=True)
                page.wait_for_selector(".artifact-text-badge")
                results["checks"].append("Text badges show cached OCR/translation and the layer toggle hides them")

                presets = page.locator("#filterPreset option").evaluate_all("nodes => nodes.map(node => node.value)")
                assert all(preset in presets for preset in ("production", "all-raw", "suppressed", "inpaint-audit"))
                page.locator("#filterPreset").select_option("all-raw")
                assert page.locator(".artifact-item[data-artifact-id='td-low']").count() == 1
                results["checks"].append("All raw preset exposes every cached candidate")

                page.locator("#filterPreset").select_option("suppressed")
                page.locator("#filterSuppression").select_option("ocr-dedup")
                page.wait_for_timeout(300)
                suppressed_debug = page.evaluate("""() => ({
                  errors: document.querySelector('#filterExportStatus')?.textContent,
                  groups: document.querySelector('#artifactModelGroups')?.innerText,
                  svg: document.querySelector('.artifact-svg')?.innerHTML,
                  posts: performance.getEntriesByType('resource').map(x => x.name).filter(x => x.includes('/api/'))
                })""")
                assert page.locator(".artifact-item[data-artifact-id='dt-td0002']").count() == 1, json.dumps(suppressed_debug)
                page.wait_for_selector(".artifact-item[data-artifact-id='dt-td0001'].ghost")
                page.locator(".artifact-item[data-artifact-id='dt-td0002']").dispatch_event(
                    "click", {"bubbles": True})
                page.wait_for_function("document.querySelector('#inspBody')?.textContent.includes('winner_id')")
                inspector = page.locator("#inspBody").inner_text()
                assert "dt-td0001" in inspector and "dt-td0002" in inspector and "0.62" in inspector
                results["checks"].append("suppression view dims survivor; inspector exposes loser/winner/rule/threshold")

                page.locator("#tabViz").click()
                page.locator("#filterPreset").select_option("production")
                page.locator('[data-filter-score-min="text-detector"]').evaluate(
                    "el => { el.value = '0.30'; el.dispatchEvent(new Event('input', {bubbles:true})); }")
                page.wait_for_selector(".artifact-item[data-artifact-id='td-low'].no-downstream")
                hatch_fill = page.locator(".artifact-item[data-artifact-id='td-low'] .artifact-shape").get_attribute("fill")
                assert hatch_fill and hatch_fill.startswith("url(#artifact-hatch-")
                results["checks"].append("lowered detector score slider adds cached no-downstream hatch")

                page.locator("#filterPreset").select_option("production")
                page.locator('#modeSeg button[data-mode="compare"]').click()
                page.locator('#cmpSeg button[data-cmp="orig-inpainted"]').click()
                page.wait_for_selector('.pg[data-page="p001.jpg"] .pg-cell')
                cells = page.locator('.pg[data-page="p001.jpg"] .pg-cell')
                assert cells.count() == 2
                inpaint_src = cells.nth(1).locator("img.ph").get_attribute("src")
                assert "/img/inpainted?p=p001.jpg" in (inpaint_src or "")
                results["checks"].append("Original | Inpainted compare displays saved image without a model POST")

                page.locator("#btnFilterExport").click()
                page.wait_for_function("document.querySelector('#filterExportStatus')?.textContent.startsWith('Saved ')")
                export_status = page.locator("#filterExportStatus").inner_text()
                export_relative = export_status.split(" to ", 1)[1]
                export_path = chapter / ".studio" / Path(export_relative)
                export_data = json.loads(export_path.read_text(encoding="utf-8"))
                assert export_data["schema"] == "T937-FILTER_SPEC-1"
                export_evidence = EVIDENCE / "filtered_view.json"
                shutil.copyfile(export_path, export_evidence)
                screenshot_path = EVIDENCE / "smoke_ui.png"
                page.screenshot(path=str(screenshot_path), full_page=True)
                page.locator('.pg[data-page="p002.jpg"]').scroll_into_view_if_needed()
                page.wait_for_function("document.querySelector('.pg[data-page=\\\"p002.jpg\\\"] .pg-cell') !== null")
                page.wait_for_timeout(350)
                missing_cells = page.locator('.pg[data-page="p002.jpg"] .pg-cell')
                assert missing_cells.count() == 2
                missing_inpaint_src = missing_cells.nth(1).locator("img.ph").get_attribute("src") or ""
                assert "/img/original?p=p002.jpg" in missing_inpaint_src
                assert not any("/img/inpainted?p=p002.jpg" in path for path in browser_image_gets)
                results["checks"].append("compare without an inpaint cache falls back to Original without requesting /img/inpainted")
                assert not browser_errors, browser_errors
                forbidden_posts = [path for path in browser_posts
                                   if any(stage in path for stage in ("/api/detect", "/api/ocr", "/api/inpaint", "/api/process"))]
                assert not forbidden_posts, forbidden_posts
                assert all(path.startswith(("/api/settings", "/api/export-filtered")) for path in browser_posts)
                results["export"] = {"source_path": f".studio/{export_relative}",
                                      "evidence_path": str(export_evidence.relative_to(REPO)),
                                      "record_count": export_data["records"].__len__(),
                                      "screenshot_path": str(screenshot_path.relative_to(REPO))}
                results["browser_posts"] = browser_posts
                results["image_gets"] = browser_image_gets
                results["browser_errors"] = browser_errors
                browser.close()

            results["checks"].append("filter changes posted settings only; no model stage or process POST")
            _write_json(EVIDENCE / "smoke_results.json", results)
        finally:
            process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)

    print(json.dumps(results, indent=2))


if __name__ == "__main__":
    main()
