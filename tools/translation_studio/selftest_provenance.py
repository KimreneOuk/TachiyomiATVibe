"""Focused inpaint crop persistence, API, and fingerprint invalidation probe."""
from __future__ import annotations

import json
import tempfile
import threading
from http.server import HTTPServer
from pathlib import Path
from urllib.parse import urlencode
from urllib.request import urlopen

import numpy as np
from PIL import Image

import pipeline
import server
from detection_artifacts import box_artifact


REPO_ROOT = Path(__file__).resolve().parents[2]
EVIDENCE_PATH = (REPO_ROOT / "Plan/active/2026-09-23_T937_translation-studio-parity"
                 / "team/10-B1-provenance/evidence/provenance_selftest.json")


def _capture(page: str, width: int, height: int, box: list[int],
             label: int = 1) -> dict:
    detector_sha = pipeline._asset_sha12(pipeline.DETECTOR_PATH)
    panel_sha = pipeline._asset_sha12(pipeline.PANEL_DETECTOR_PATH)
    record = box_artifact("td0000", page, "text-detector",
                          pipeline.DETECTOR_PATH.name, detector_sha,
                          None, box, 0.93, label)
    return {
        "capture_version": 1,
        "boxes": [[label, 0.93, *box]],
        "page_wh": [width, height],
        "is_tall": False,
        "infer_ms": 1.0,
        "load_ms": 0.0,
        "models": {
            "text-detector": {"asset": pipeline.DETECTOR_PATH.name,
                              "asset_sha": detector_sha, "status": "ready",
                              "outputs": [record]},
            "panel-detector": {"asset": pipeline.PANEL_DETECTOR_PATH.name,
                               "asset_sha": panel_sha, "status": "disabled",
                               "outputs": [], "kept_ids": []},
            "bubble-segmenter": {"asset_sha": None,
                                 "status": "disabled", "outputs": []},
        },
        "mask_cache": {"bubble-segmenter": {}},
        "decisions": {},
    }


def _rgb_fixture(width: int, height: int, tweak: int = 0) -> Image.Image:
    yy, xx = np.mgrid[0:height, 0:width]
    pixels = np.stack((70 + xx + tweak, 90 + yy, 150 + (xx + yy) // 2), axis=2)
    return Image.fromarray(np.clip(pixels, 0, 255).astype(np.uint8))


def _record_file_paths(record: dict) -> list[Path]:
    paths = []
    for kind in ("input", "output", "mask"):
        for representation in ("debug", "exact"):
            entry = record["crops"][kind][representation]
            paths.append((Path(entry["path"]), entry["url"]))
    return paths


def _assert_crop_triple(chapter: Path, source: Image.Image,
                        output_path: Path, record: dict) -> dict:
    crops = record["crops"]
    assert crops["status"] == "ready"
    assert crops["basis"] in ("region-mask-union", "route-context")
    assert crops["context_crop_bbox"] == record["context_crop_bbox"]
    x1, y1, x2, y2 = crops["crop_bbox"]
    expected_size = (x2 - x1, y2 - y1)
    original_array = np.asarray(source.crop((x1, y1, x2, y2)))
    with Image.open(output_path) as opened:
        inpainted = opened.convert("RGB")
    output_array = np.asarray(inpainted.crop((x1, y1, x2, y2)))
    checked = {}
    for kind, mode in (("input", "RGB"), ("output", "RGB"), ("mask", "L")):
        entry = crops[kind]["exact"]
        path = chapter / Path(entry["path"])
        with Image.open(path) as opened:
            image = opened.copy()
        assert image.mode == mode
        assert image.size == expected_size
        debug = crops[kind]["debug"]
        assert debug["path"] == entry["path"]
        assert debug["url"].endswith("representation=debug")
        assert entry["url"].endswith("representation=exact")
        if kind == "input":
            assert np.array_equal(np.asarray(image), original_array)
        elif kind == "output":
            assert np.array_equal(np.asarray(image), output_array)
        elif kind == "mask":
            mask_array = np.asarray(image)
            assert set(np.unique(mask_array)).issubset({0, 255})
            assert mask_array.any()
        checked[kind] = {"path": entry["path"],
                         "size": list(image.size), "mode": image.mode}
    return {"id": record["id"], "route_taken": record["route_taken"],
            "a4_context_crop_bbox": record["context_crop_bbox"],
            "crop_bbox": crops["crop_bbox"], "basis": crops["basis"],
            "triples": checked}


def main() -> None:
    page = "page.png"
    free_page = "free.png"
    width, height = 96, 72
    free_width, free_height = 320, 240
    with tempfile.TemporaryDirectory(prefix="t937-b1-provenance-") as temp:
        chapter = Path(temp)
        source_path = chapter / page
        source_image = _rgb_fixture(width, height)
        source_image.save(source_path)
        free_source = _rgb_fixture(free_width, free_height, tweak=3)
        free_source.save(chapter / free_page)

        p = pipeline.Pipeline()
        p.chapter = chapter
        p.pages = [page, free_page]
        p.settings = dict(pipeline.DEFAULT_SETTINGS)
        p.settings.update({"conf": 0.6, "inpaint_engine": "android",
                           "inpaint_bubble_leg": "android-fill",
                           "inpaint_free_leg": "opencv",
                           "reading_order": "ltr",
                           "bubble_segmentation": False})
        p._cache = {"detections": {}, "ocr": {}, "translations": {}}
        p._render_dirty = {page: True, free_page: True}
        p._inpaint_paddle_det = lambda: None
        p._capture_detection_models = lambda page_name, image: _capture(
            page_name, image.width, image.height,
            [24, 18, 68, 48] if page_name == page else [100, 80, 150, 120],
            label=1 if page_name == page else 2)

        def recognize(crops, regions=None, page_image=None):
            return ([{"text": "synthetic text", "raw_text": "synthetic text",
                      "confidence": 0.98, "lines": [], "engine": "mangaocr"}
                     for _ in crops], {"runs": 1}, 1.0)

        p._recognize_crops = recognize
        for current_page in (page, free_page):
            p.detect_page(current_page, force=True)
            p.ocr_page(current_page)
        first = p.inpaint_page(page, force=True)
        free_first = p.inpaint_page(free_page, force=True)
        first_document = json.loads(Path(first["provenance_path"]).read_text(
            encoding="utf-8"))
        free_document = json.loads(Path(free_first["provenance_path"]).read_text(
            encoding="utf-8"))
        assert first_document["provenance_schema_version"] == 3
        records = first_document["regions"]
        assert records, "fixture should produce at least one routed region"
        assert all(record.get("route_taken") == "bubble/android-fill"
                   for record in records)
        first_crop_evidence = [_assert_crop_triple(
            chapter, source_image, Path(first["path"]), record)
            for record in records]
        for record in records:
            assert record["context_crop_bbox"] == [0, 0, width, height]
            assert record["crops"]["basis"] == "region-mask-union"
            assert record["crops"]["crop_bbox"] != record["context_crop_bbox"]
            assert record["crops"]["note"]
        free_records = free_document["regions"]
        assert free_records and all(record["route_taken"].startswith("freetext/")
                                    for record in free_records)
        free_crop_evidence = [_assert_crop_triple(
            chapter, free_source, Path(free_first["path"]), record)
            for record in free_records]
        for record in free_records:
            assert record["crops"]["basis"] == "route-context"
            assert record["crops"]["crop_bbox"] == record["context_crop_bbox"]

        original_server_pipeline = server.PIPELINE
        server.PIPELINE = p
        httpd = HTTPServer(("127.0.0.1", 0), server.Handler)
        worker = threading.Thread(target=httpd.serve_forever, daemon=True)
        worker.start()
        base_url = f"http://127.0.0.1:{httpd.server_port}"
        try:
            with urlopen(base_url + "/api/inpaint_provenance?" +
                         urlencode({"p": page}), timeout=10) as response:
                api_document = json.loads(response.read().decode("utf-8"))
            assert api_document["cache_key"]["fingerprint"] == first["cache_fingerprint"]
            api_record = api_document["regions"][0]
            api_crop_url = base_url + api_record["crops"]["mask"]["exact"]["url"]
            with urlopen(api_crop_url, timeout=10) as response:
                served_png = response.read()
                assert response.headers.get_content_type() == "image/png"
            from io import BytesIO
            with Image.open(BytesIO(served_png)) as opened:
                served_size = opened.size
            assert served_size == tuple(
                api_record["crops"]["crop_bbox"][2:4][i]
                - api_record["crops"]["crop_bbox"][0:2][i]
                for i in range(2))

            old_paths = {chapter / relative for record in api_document["regions"]
                         for relative, _ in _record_file_paths(record)}
            _rgb_fixture(width, height, tweak=9).save(source_path)
            with urlopen(base_url + "/api/inpaint_provenance?" +
                         urlencode({"p": page}), timeout=10) as response:
                refreshed_document = json.loads(response.read().decode("utf-8"))
            refreshed_fingerprint = refreshed_document["cache_key"]["fingerprint"]
            assert refreshed_fingerprint != first_document["cache_key"]["fingerprint"]
            assert all(not path.exists() for path in old_paths)
            refreshed_paths = [chapter / relative
                               for record in refreshed_document["regions"]
                               for relative, _ in _record_file_paths(record)]
            assert refreshed_paths and all(path.is_file() for path in refreshed_paths)
        finally:
            httpd.shutdown()
            httpd.server_close()
            worker.join(timeout=5)
            server.PIPELINE = original_server_pipeline

        evidence = {
            "status": "ok",
            "page": page,
            "provenance_schema_version": 3,
            "first_fingerprint": first_document["cache_key"]["fingerprint"],
            "refreshed_fingerprint": refreshed_fingerprint,
            "android_fill_window_tradeoff": {
                "route_context_bbox_preserved": records[0]["context_crop_bbox"],
                "region_crop_bbox": records[0]["crops"]["crop_bbox"],
                "basis": records[0]["crops"]["basis"],
                "note": records[0]["crops"]["note"],
            },
            "regions": first_crop_evidence,
            "free_text_route_context": free_crop_evidence,
            "api": {
                "provenance_route": "GET /api/inpaint_provenance?p=page.png",
                "crop_route": "GET /img/inpaint_crop?p=page.png&id=r00&kind=mask&representation=exact",
                "served_png_content_type": "image/png",
            },
            "fingerprint_invalidation": {
                "changed_page_bytes": True,
                "fingerprint_changed": True,
                "all_old_crop_paths_removed": True,
                "new_crop_paths_exist": True,
            },
        }

    EVIDENCE_PATH.parent.mkdir(parents=True, exist_ok=True)
    EVIDENCE_PATH.write_text(json.dumps(evidence, indent=2, ensure_ascii=False),
                             encoding="utf-8")
    print(json.dumps(evidence, indent=2, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
