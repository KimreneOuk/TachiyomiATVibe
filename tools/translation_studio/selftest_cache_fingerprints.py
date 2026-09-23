"""Model-free regression checks for T937 A5 stage cache fingerprints."""
from __future__ import annotations

import json
import os
import tempfile
import copy
from pathlib import Path
from unittest.mock import patch

import numpy as np
from PIL import Image

import inpaint_android
import pipeline
from detection_artifacts import box_artifact


REPO_ROOT = Path(__file__).resolve().parents[2]
EVIDENCE_PATH = (REPO_ROOT / "Plan/active/2026-09-23_T937_translation-studio-parity"
                 / "team/10-B1-provenance/evidence/cache_selftest.json")


def _asset_spot_check() -> dict:
    with tempfile.TemporaryDirectory(prefix="t937-a5-asset-") as temp:
        path = Path(temp) / "model.bin"
        path.write_bytes(b"a" * 16384)
        original_stat = path.stat()
        before = pipeline._asset_identity(path)
        path.write_bytes(b"b" * 16384)
        os.utime(path, ns=(original_stat.st_atime_ns,
                           original_stat.st_mtime_ns))
        replacement_stat = path.stat()
        assert replacement_stat.st_size == original_stat.st_size
        assert replacement_stat.st_mtime_ns == original_stat.st_mtime_ns
        after = pipeline._asset_identity(path)
        assert after["sha256"] != before["sha256"]
        assert pipeline._asset_identity(path)["sha256"] == after["sha256"]
        return {"same_size": True, "mtime_restored": True,
                "sample_changed": True, "full_digest_changed": True}


def _capture(page: str, width: int, height: int, box: list[int]) -> dict:
    detector_sha = pipeline._asset_sha12(pipeline.DETECTOR_PATH)
    panel_sha = pipeline._asset_sha12(pipeline.PANEL_DETECTOR_PATH)
    try:
        from segmentation import SEGMENTER_MODEL
        segmenter_sha = pipeline._asset_sha12(SEGMENTER_MODEL)
    except Exception:
        segmenter_sha = None
    record = box_artifact("td0000", page, "text-detector",
                          pipeline.DETECTOR_PATH.name, detector_sha, None,
                          box, 0.93, 1)
    return {
        "capture_version": 1,
        "boxes": [[1, 0.93, *box]],
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
            "bubble-segmenter": {"asset_sha": segmenter_sha,
                                 "status": "disabled", "outputs": []},
        },
        "mask_cache": {"bubble-segmenter": {}},
        "decisions": {},
    }


def _run_checks() -> dict:
    page = "page.png"
    width, height = 80, 64
    asset_spot_check = _asset_spot_check()
    counts = {"capture": 0, "ocr": 0, "inpaint": 0, "render": 0}
    with tempfile.TemporaryDirectory(prefix="t937-a5-cache-") as temp:
        chapter = Path(temp)
        source = chapter / page
        Image.new("RGB", (width, height), (180, 20, 30)).save(source)
        first_bytes = source.read_bytes()

        p = pipeline.Pipeline()
        p.chapter = chapter
        p.pages = [page]
        p.settings = dict(pipeline.DEFAULT_SETTINGS)
        p.settings.update({"conf": 0.6, "ocr_engine": "mangaocr",
                           "inpaint_engine": "android",
                           "inpaint_bubble_leg": "android-fill",
                           "inpaint_free_leg": "opencv",
                           "reading_order": "ltr",
                           "bubble_segmentation": False})
        p._cache = {"detections": {}, "ocr": {}, "translations": {}}
        p._render_dirty = {page: True}
        p._inpaint_paddle_det = lambda: None
        detector_box = {"value": [12, 14, 48, 36]}

        def capture(page_name, image):
            counts["capture"] += 1
            return _capture(page_name, image.width, image.height,
                            detector_box["value"])

        p._capture_detection_models = capture

        capture_revision = {"value": "detector-rev-1"}
        original_capture_inputs = p._detection_capture_inputs

        def capture_inputs(page_info):
            inputs = original_capture_inputs(page_info)
            inputs["fixture_model_revision"] = capture_revision["value"]
            return inputs

        p._detection_capture_inputs = capture_inputs
        render_observations = []

        def recognize(crops, regions=None, page_image=None):
            counts["ocr"] += 1
            return ([{"text": "source text", "raw_text": "source text",
                      "confidence": 0.98, "lines": [], "engine": "mangaocr"}
                     for _ in crops], {"runs": 1}, 1.0)

        p._recognize_crops = recognize

        def inpaint(image, regions, **kwargs):
            counts["inpaint"] += 1
            return (image.copy(), np.zeros((image.height, image.width), dtype=bool),
                    [], {"mask_pixels": 0})

        def render(image, regions, translations, settings, pipe=None,
                   page=None, assignment_records=None):
            counts["render"] += 1
            render_observations.append({
                "regions": [(region.get("id"), tuple(region.get("box", [])))
                            for region in regions],
                "ocr_fingerprint": ((pipe._cache.get("ocr", {}).get(page) or {})
                                    .get("ocr_fingerprint")) if pipe else None,
            })
            return sum(bool((translations.get(region["id"]) or "").strip())
                       for region in regions)

        with patch.object(inpaint_android, "inpaint_page_android", inpaint), \
                patch.object(pipeline, "render_regions", render):
            detection = p.detect_page(page)
            page_fp_before = p._cache["detections"][page]["page_fingerprint"]
            assert len(page_fp_before) == 64
            ocr_first = p.ocr_page(page)
            inpaint_first = p.inpaint_page(page)
            p._cache["translations"][page] = {"r00": "translated"}
            render_first = p.render_page(page)

            # An unchanged operation chain is entirely cache-backed.
            assert p.detect_page(page)["decision_fingerprint"] == detection[
                "decision_fingerprint"]
            assert p.ocr_page(page)["cache_hit"] is True
            assert p.inpaint_page(page)["cache_hit"] is True
            assert p.render_page(page)["cache_hit"] is True
            after_unchanged = dict(counts)
            assert after_unchanged == {"capture": 1, "ocr": 1,
                                       "inpaint": 1, "render": 1}

            # The LTR fixture skips panel detection. A change to that unused
            # asset identity must not recapture the text detector outputs.
            original_asset_sha12 = pipeline._asset_sha12

            def changed_panel_sha12(path):
                if path == pipeline.PANEL_DETECTOR_PATH:
                    return "changed-panel-asset"
                return original_asset_sha12(path)

            with patch.object(pipeline, "_asset_sha12", changed_panel_sha12):
                p.detect_page(page)
            assert counts["capture"] == 1

            # Display-only GET helpers replay into copies and don't rewrite the
            # shared detections.json cache.
            raw = p._cache["detections"][page]
            raw_before_get = copy.deepcopy(raw)
            ocr_entry = p._cache["ocr"].pop(page)
            writes = []
            original_save = p._save_json
            p._save_json = lambda name, data: writes.append(name)
            p.page_data(page)
            p.overlay_image(page, conf=0.3)
            p._save_json = original_save
            p._cache["ocr"][page] = ocr_entry
            assert raw == raw_before_get
            assert writes == []

            # Replaying the same decision through the execution stage is also
            # a no-op for persistence once its decision fingerprint is saved.
            writes = []
            p._save_json = lambda name, data: writes.append(name)
            p.detect_page(page)
            p.detect_page(page)
            p._save_json = original_save
            assert writes == []

            # Translation is a render input, never an inpaint input.
            p._cache["translations"][page]["r00"] = "translation edit"
            assert p.inpaint_page(page)["cache_hit"] is True
            translation_render = p.render_page(page)
            assert translation_render["cache_hit"] is False
            assert counts["inpaint"] == 1 and counts["render"] == 2

            # A direct inpaint call validates both upstream stages. The
            # revision simulates a changed detector model identity; OCR must
            # then follow its new detection fingerprint without a force flag.
            capture_revision["value"] = "detector-rev-2"
            detector_refresh = p.inpaint_page(page)
            assert detector_refresh["cache_hit"] is False
            assert counts == {"capture": 2, "ocr": 2,
                              "inpaint": 2, "render": 2}
            detector_refresh_render = p.render_page(page)
            assert detector_refresh_render["cache_hit"] is False

            # OCR settings are also validated by a direct inpaint request.
            p.settings["mangaocr_serial_timing"] = True
            ocr_setting_refresh = p.inpaint_page(page)
            assert ocr_setting_refresh["cache_hit"] is False
            assert counts == {"capture": 2, "ocr": 3,
                              "inpaint": 3, "render": 3}
            ocr_setting_render = p.render_page(page)
            assert ocr_setting_render["cache_hit"] is False

            # A changed captured box changes replay -> OCR -> inpaint -> render.
            raw = p._cache["detections"][page]
            raw["models"]["text-detector"]["outputs"][0]["geometry"].update(
                {"x1": 14, "y1": 14, "x2": 51, "y2": 36})
            raw["boxes"][0] = [1, 0.93, 14, 14, 51, 36]
            changed_detection = p.detect_page(page)
            assert changed_detection["decision_fingerprint"] != detection[
                "decision_fingerprint"]
            changed_ocr = p.ocr_page(page)
            assert changed_ocr["cache_hit"] is False
            changed_inpaint = p.inpaint_page(page)
            assert changed_inpaint["cache_hit"] is False
            changed_render = p.render_page(page)
            assert changed_render["cache_hit"] is False
            assert counts == {"capture": 2, "ocr": 4, "inpaint": 4,
                              "render": 5}

            # Repeating after the upstream change also reuses all four stages.
            assert p.ocr_page(page)["cache_hit"] is True
            assert p.inpaint_page(page)["cache_hit"] is True
            assert p.render_page(page)["cache_hit"] is True
            assert counts == {"capture": 2, "ocr": 4, "inpaint": 4,
                              "render": 5}

            # Regression: render_page is the first stage called after a
            # detector-input change. Its nested inpaint validation refreshes
            # OCR, and both drawing and persisted lineage must use that entry.
            previous_ocr_fingerprint = p._cache["ocr"][page]["ocr_fingerprint"]
            capture_revision["value"] = "detector-rev-3"
            detector_box["value"] = [18, 20, 55, 44]
            direct_render = p.render_page(page)
            refreshed_ocr = p._cache["ocr"][page]
            refreshed_fingerprint = refreshed_ocr["ocr_fingerprint"]
            assert refreshed_fingerprint != previous_ocr_fingerprint
            expected_regions = [(region.get("id"), tuple(region.get("box", [])))
                                for region in refreshed_ocr["regions"]]
            assert render_observations[-1]["regions"] == expected_regions
            assert render_observations[-1]["ocr_fingerprint"] == refreshed_fingerprint
            render_metadata = json.loads(
                (p.studio_dir / "render" / "page.json").read_text(encoding="utf-8"))
            assert render_metadata["cache_key"]["ocr_fingerprint"] == refreshed_fingerprint
            assert render_metadata["cache_key"]["render_regions"] == [
                {key: region.get(key) for key in
                 ("id", "artifact_id", "box", "label", "score",
                  "segmenter_assignment", "panel_assignment")}
                for region in refreshed_ocr["regions"]]
            render_lineage_regression = {
                "previous_ocr_fingerprint": previous_ocr_fingerprint,
                "refreshed_ocr_fingerprint": refreshed_fingerprint,
                "rendered_regions": [{"id": region_id, "box": list(box)}
                                     for region_id, box in expected_regions],
                "render_cache_fingerprint": direct_render["cache_fingerprint"],
                "render_used_refreshed_ocr": True,
            }
            assert counts == {"capture": 3, "ocr": 5, "inpaint": 5,
                              "render": 6}

            # Replace bytes at the same relative path and dimensions. This
            # clears dimensions, masks, stage records, and derived image files.
            p._dims[page] = [999, 999]
            p._bubble_mask_cache[page] = ["old-mask"]
            Image.new("RGB", (width, height), (10, 30, 180)).save(source)
            second_bytes = source.read_bytes()
            assert len(first_bytes) == len(second_bytes)
            assert p.page_dims(page) == [width, height]
            assert page not in p._cache["detections"]
            assert page not in p._cache["ocr"]
            assert page not in p._bubble_mask_cache
            assert not (p.studio_dir / "inpaint" / "page.png").exists()
            assert not (p.studio_dir / "render" / "page.png").exists()
            assert p.detect_page(page)["page_fingerprint"] != page_fp_before
            page_changed_ocr = p.ocr_page(page)
            page_changed_inpaint = p.inpaint_page(page)
            page_changed_render = p.render_page(page)
            assert page_changed_ocr["cache_hit"] is False
            assert page_changed_inpaint["cache_hit"] is False
            assert page_changed_render["cache_hit"] is False
            assert counts == {"capture": 4, "ocr": 6, "inpaint": 6,
                              "render": 7}

            # process_page computes the compressed source digest once and
            # shares it across its nested detection/OCR/inpaint/render calls.
            with patch.object(p, "_compute_page_fingerprint",
                              wraps=p._compute_page_fingerprint) as hash_page:
                p.process_page(page, translate=False)
                assert hash_page.call_count == 1
            assert counts == {"capture": 4, "ocr": 6, "inpaint": 6,
                              "render": 7}

        evidence = {
            "page": page,
            "same_size_source_replacement": {
                "dimensions": [width, height],
                "compressed_bytes_before": len(first_bytes),
                "compressed_bytes_after": len(second_bytes),
                "fingerprint_changed": True,
                "dependent_cache_counts": counts,
            },
            "upstream_detection_change": {
                "capture_reused": True,
                "decision_fingerprint_changed": True,
                "ocr_inpaint_render_recomputed": True,
            },
            "translation_only_edit": {
                "inpaint_reused": True,
                "render_recomputed": True,
            },
            "direct_inpaint_validates_upstream": {
                "detector_identity_change_recaptured": True,
                "ocr_setting_change_recomputed": True,
                "force_required": False,
            },
            "direct_render_uses_refreshed_ocr": render_lineage_regression,
            "skipped_model_asset_change": {
                "panel_inference_eligible": False,
                "text_detector_recaptured": False,
            },
            "asset_stat_cache_spot_check": asset_spot_check,
            "unchanged_inputs": {
                "capture_count": counts["capture"],
                "ocr_count": counts["ocr"],
                "inpaint_count": counts["inpaint"],
                "render_count": counts["render"],
                "process_page_hash_calls": 1,
            },
            "initial_cache_fingerprints": {
                "ocr": ocr_first["cache_fingerprint"],
                "inpaint": inpaint_first["cache_fingerprint"],
                "render": render_first["cache_fingerprint"],
            },
            "changed_upstream_fingerprints": {
                "ocr": changed_ocr["cache_fingerprint"],
                "inpaint": changed_inpaint["cache_fingerprint"],
                "render": changed_render["cache_fingerprint"],
            },
            "changed_page_fingerprints": {
                "ocr": page_changed_ocr["cache_fingerprint"],
                "inpaint": page_changed_inpaint["cache_fingerprint"],
                "render": page_changed_render["cache_fingerprint"],
            },
        }

    EVIDENCE_PATH.parent.mkdir(parents=True, exist_ok=True)
    EVIDENCE_PATH.write_text(json.dumps(evidence, indent=2), encoding="utf-8")
    return evidence


def main() -> None:
    evidence = _run_checks()
    print(json.dumps({
        "status": "ok",
        "evidence": str(EVIDENCE_PATH),
        "counts": evidence["same_size_source_replacement"]["dependent_cache_counts"],
    }, indent=2))


if __name__ == "__main__":
    main()
