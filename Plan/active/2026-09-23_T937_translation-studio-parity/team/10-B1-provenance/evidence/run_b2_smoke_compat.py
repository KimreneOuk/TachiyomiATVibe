"""Run the upstream B2 smoke against this branch's server and B2 static UI.

The B2 fixture predates A5's cache fingerprints, and the current demo folder
may have generated p002 outputs. This runner makes that fixture current while
preserving its intended synthetic records and no-cache control.
"""
from __future__ import annotations

import importlib.util
import json
import os
import sys
import tempfile
from pathlib import Path


REPO = next(parent for parent in Path(__file__).resolve().parents
            if (parent / "tools" / "translation_studio").is_dir())
STUDIO = REPO / "tools" / "translation_studio"
EVIDENCE = Path(__file__).resolve().parent
SMOKE_PATH = (REPO / "Plan/active/2026-09-23_T937_translation-studio-parity"
              / "team/08-B2-visualization/evidence/smoke_b2.py")


def _load_smoke(path: Path = SMOKE_PATH):
    spec = importlib.util.spec_from_file_location("t937_b2_smoke", path)
    module = importlib.util.module_from_spec(spec)
    assert spec and spec.loader
    spec.loader.exec_module(module)
    return module


def main() -> None:
    static_root = Path(os.environ["T937_B2_STATIC"]).resolve()
    assert (static_root / "app.js").is_file()
    assert (static_root / "index.html").is_file()
    sys.path.insert(0, str(STUDIO))
    import pipeline

    runtime_path = STUDIO / "_t937_smoke_b2_runtime.py"
    source = SMOKE_PATH.read_text(encoding="utf-8")
    marker = 'assert page.locator(".artifact-text-badge").count() > 0'
    if marker not in source:
        raise RuntimeError("could not locate the B2 badge smoke assertion")
    source = source.replace(marker,
                            'page.wait_for_selector(".artifact-text-badge")\n'
                            '                ' + marker, 1)
    if os.environ.get("T937_B2_DEBUG") == "1":
        diagnostic = '''print("B2_DEBUG", json.dumps(page.evaluate("""async () => {
          const response = await fetch('/api/artifacts?p=p001.jpg');
          const payload = await response.json();
          const pageData = await (await fetch('/api/page?p=p001.jpg')).json();
          const state = await (await fetch('/api/state')).json();
          const logs = await (await fetch('/api/log')).json();
          return {http: response.status, sources: Object.fromEntries(
            Object.entries(payload.sources || {}).map(([k,v]) => [k, v.status])),
            ocrRegions: payload.sources?.ocr?.data?.regions?.length,
            ocrText: payload.sources?.ocr?.data?.regions?.map(x => x.text),
            pageRegions: pageData.regions?.length,
            pageText: pageData.regions?.map(x => x.text),
            logs: logs.lines?.slice(-20),
            chapter: state.chapter, pages: state.pages,
            badgeCount: document.querySelectorAll('.artifact-text-badge').length,
            textToggle: document.querySelector('#treeTextBadges')?.checked,
            mediaClasses: document.querySelector('.pg[data-page=\\"p001.jpg\\"] .pg-media')?.className,
            artifactSvgs: document.querySelectorAll('.artifact-svg').length,
            artifactItems: document.querySelectorAll('.artifact-item').length,
            apiResources: performance.getEntriesByType('resource').map(x => x.name).filter(x => x.includes('/api/')),
            filterCounts: [...document.querySelectorAll('[data-filter-count]')].map(x => [x.dataset.filterCount, x.textContent]),
            log: document.querySelector('#log')?.innerText?.slice(-1200),
            body: document.body.innerText.slice(0, 1000)};
        }"""), indent=2), flush=True)
'''
        diagnostic_lines = diagnostic.splitlines()
        diagnostic = (diagnostic_lines[0] + "\n" +
                      "\n".join("                " + line
                                for line in diagnostic_lines[1:]) + "\n")
        source = source.replace(marker, diagnostic + "                " + marker)
    runtime_path.write_text(source, encoding="utf-8")
    try:
        smoke = _load_smoke(runtime_path)
    except Exception:
        runtime_path.unlink(missing_ok=True)
        raise
    original_make_fixture = smoke._make_fixture

    def make_current_fixture(chapter: Path):
        dimensions = original_make_fixture(chapter)
        # The source demo chapter can contain generated page-two outputs;
        # B2's fixture contract uses p002 as the no-inpaint-cache control.
        for relative in ("inpaint/p002.png", "inpaint/p002.json",
                         "inpaint_mask/p002.png"):
            (chapter / ".studio" / relative).unlink(missing_ok=True)
        pipe = pipeline.Pipeline()
        pipe.record_recent = lambda *_args, **_kwargs: []
        pipe.open_folders(str(chapter), None)
        # Keep the synthetic loser past detector dedup so the real replay can
        # emit its expected dt-* record and suppress it at the OCR-dedup step.
        capture = pipe._cache["detections"][smoke.PAGE]
        text_outputs = capture["models"]["text-detector"]["outputs"]
        loser = next(row for row in text_outputs if row["id"] == "td0002")
        width = capture["page_wh"][0]
        dx = 20
        loser["geometry"]["x1"] += dx
        loser["geometry"]["x2"] += dx
        loser["attrs"]["shape"] = {
            "w": loser["geometry"]["x2"] - loser["geometry"]["x1"],
            "h": loser["geometry"]["y2"] - loser["geometry"]["y1"],
            "ar": ((loser["geometry"]["x2"] - loser["geometry"]["x1"])
                   / (loser["geometry"]["y2"] - loser["geometry"]["y1"])),
        }
        assert loser["geometry"]["x2"] < width
        page_info = pipe._compute_page_fingerprint(smoke.PAGE)
        pipe._page_fingerprints[smoke.PAGE] = page_info
        raw = pipe._cache["detections"][smoke.PAGE]
        capture_inputs = pipe._detection_capture_inputs(page_info)
        models = raw["models"]
        for model_name, input_name in (
                ("text-detector", "text_detector"),
                ("panel-detector", "panel_detector"),
                ("bubble-segmenter", "bubble_segmenter")):
            identity = capture_inputs[input_name]
            models[model_name]["asset_sha"] = (
                identity.get("sha256", "")[:12]
                if identity.get("status") == "present" else None)
        pipe._stamp_detection_capture(raw, page_info)
        assert pipe._detection_cache_is_current(raw, page_info), (
            "B2 seed detection record did not become a current A5 capture")
        pipe._save_json("detections.json", pipe._cache["detections"])

        detected = pipe.detect_page(smoke.PAGE)
        ocr = pipe._cache["ocr"][smoke.PAGE]
        engine = pipe._ocr_engine_name()
        ocr["engine"] = engine
        ocr_inputs = pipe._ocr_cache_inputs(
            smoke.PAGE, engine, detected["decision_fingerprint"],
            detected["regions"])
        ocr["page_fingerprint"] = page_info["fingerprint"]
        ocr["detection_fingerprint"] = detected["decision_fingerprint"]
        ocr["cache_inputs"] = ocr_inputs
        ocr["cache_fingerprint"] = pipeline._stable_fingerprint(ocr_inputs)
        ocr["ocr_fingerprint"] = pipeline._stable_fingerprint({
            "inputs": ocr_inputs,
            "regions": [{key: region.get(key) for key in
                         ("id", "artifact_id", "label", "score", "box", "ocr_box",
                          "text", "raw_text", "confidence", "lines", "engine",
                          "error", "position_limit")}
                        for region in ocr.get("regions", [])],
        })
        pipe._save_json("ocr.json", pipe._cache["ocr"])
        assert pipe.ocr_page(smoke.PAGE)["cache_hit"], (
            "B2 seeded OCR record did not become a current A5 cache")
        return dimensions

    smoke._make_fixture = make_current_fixture
    smoke.EVIDENCE = EVIDENCE
    if os.environ.get("T937_B2_DEBUG") == "1":
        original_request = smoke._request

        def debug_request(url, payload=None):
            result = original_request(url, payload)
            if "/api/artifacts?" in url:
                status, body, _content_type = result
                data = json.loads(body)
                summary = [(k, v.get("status"),
                            len((v.get("data") or {}).get("regions", [])),
                            (((v.get("data") or {}).get("regions") or [{}])[0].get("text")))
                           for k, v in data.get("sources", {}).items()]
                print("B2_ARTIFACT_RESPONSE", json.dumps(
                    {"status": status, "sources": summary}, ensure_ascii=True),
                    flush=True)
            return result

        smoke._request = debug_request
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="t937-b2-sitecustomize-") as site_dir:
        sitecustomize = Path(site_dir) / "sitecustomize.py"
        sitecustomize.write_text(
            "import os\nfrom pathlib import Path\nimport server\n"
            "server.STATIC = Path(os.environ['T937_B2_STATIC'])\n",
            encoding="utf-8")
        previous_pythonpath = os.environ.get("PYTHONPATH", "")
        os.environ["PYTHONPATH"] = (site_dir + os.pathsep + previous_pythonpath)
        try:
            smoke.main()
        finally:
            runtime_path.unlink(missing_ok=True)

    adaptation = {
        "smoke": str(SMOKE_PATH.relative_to(REPO)),
        "static_ui": str(static_root),
        "server_and_pipeline": "this B1 worktree",
        "fixture_adaptation": [
            "stamp current A5 detection-capture and OCR cache fingerprints",
            "shift td0002 so real replay preserves the expected OCR-dedup loser",
            "remove inherited p002 inpaint outputs so p002 remains the no-cache control",
            "wait for asynchronous text-badge overlay before its assertion",
        ],
        "reason": "B2 fixture predates A5 and demo cache contents; a short overlay wait stabilizes its browser assertion",
        "smoke_results": str((EVIDENCE / "smoke_results.json").relative_to(REPO)),
    }
    (EVIDENCE / "b2_smoke_compat.json").write_text(
        json.dumps(adaptation, indent=2), encoding="utf-8")
    print(json.dumps(adaptation, indent=2), flush=True)


if __name__ == "__main__":
    main()
