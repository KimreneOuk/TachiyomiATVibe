"""Focused model-free regression for inpaint variant cache switching."""
from __future__ import annotations

import json
import os
import shutil
import tempfile
import time
from pathlib import Path
from types import MethodType, SimpleNamespace

import numpy as np
from PIL import Image, ImageDraw

import inpaint_android
from pipeline import DEFAULT_SETTINGS, Pipeline


HERE = Path(__file__).resolve().parent
EVIDENCE = (HERE.parents[1] / "Plan" / "active" /
            "2026-09-23_T937_translation-studio-parity" / "team" /
            "11-B3-variants" / "evidence")
PAGE = "variant-page.png"


class FakePaddleDetector:
    def detect_lines(self, crop, thresh: float, box_thresh: float):
        assert (thresh, box_thresh) == (0.18, 0.34)
        return [SimpleNamespace(bbox=[10, 8, 35, 25])]


def _make_page(path: Path) -> None:
    y, x = np.mgrid[0:160, 0:240]
    base = np.empty((160, 240, 3), dtype=np.uint8)
    base[..., 0] = 188 + ((x * 3 + y) % 43)
    base[..., 1] = 174 + ((x + y * 2) % 49)
    base[..., 2] = 196 + ((x * 2 + y * 3) % 37)
    image = Image.fromarray(base)
    draw = ImageDraw.Draw(image)
    for y0 in (61, 70, 79):
        draw.line((68, y0, 94, y0), fill=(18, 17, 19), width=2)
    for y0 in (49, 58, 67):
        draw.line((154, y0, 188, y0), fill=(12, 13, 14), width=2)
    image.save(path)


def _seed_pipeline(chapter: Path) -> tuple[Pipeline, FakePaddleDetector]:
    _make_page(chapter / PAGE)
    pipeline = Pipeline()
    pipeline.chapter = chapter
    pipeline.pages = [PAGE]
    pipeline.settings = dict(DEFAULT_SETTINGS)
    pipeline.settings.update({
        "inpaint_engine": "android",
        "inpaint_bubble_leg": "android-fill",
        "inpaint_free_leg": "opencv",
        "inpaint_opencv_method": "telea",
        "inpaint_mode": "quality",
        "bubble_mask_erosion": 5,
        "inpaint_telea_radius": 3,
        "inpaint_feather_px": None,
        "bubble_segmentation": True,
    })
    pipeline._cache = {
        "detections": {PAGE: {"boxes": [
            [0, 0.99, 39, 28, 122, 119],
            [1, 0.98, 63, 55, 100, 87],
            [2, 0.97, 150, 44, 193, 75],
        ]}},
        "ocr": {PAGE: {"regions": [
            {"id": "bubble-text", "artifact_id": "td-bubble", "box": [63, 55, 100, 87],
             "label": 1, "score": 0.98, "text": "bubble text"},
            {"id": "free-text", "artifact_id": "td-free", "box": [150, 44, 193, 75],
             "label": 2, "score": 0.97, "text": "free text"},
        ]}},
        "translations": {},
    }
    seg = np.zeros((160, 240), dtype=bool)
    yy, xx = np.mgrid[0:160, 0:240]
    seg[((xx - 81) / 42) ** 2 + ((yy - 73) / 43) ** 2 <= 1] = True
    pipeline.detect_page = MethodType(
        lambda self, page, conf=None, force=False: self._cache["detections"][page],
        pipeline)
    pipeline.ocr_page = MethodType(
        lambda self, page, conf=None, force=False: self._cache["ocr"][page],
        pipeline)
    pipeline.bubble_masks = MethodType(
        lambda self, page: [SimpleNamespace(mask=seg, bounds=[39, 30, 123, 120])],
        pipeline)
    fake_paddle = FakePaddleDetector()
    pipeline._inpaint_paddle_det = MethodType(
        lambda self: fake_paddle, pipeline)
    return pipeline, fake_paddle


def _read_provenance(result: dict) -> dict:
    return json.loads(Path(result["provenance_path"]).read_text(encoding="utf-8"))


def _route_evidence(label: str, provenance: dict) -> dict:
    cache = provenance["cache_key"]
    return {
        "page": PAGE,
        "scenario": label,
        "variant": provenance.get("variant"),
        "leg_matrix": provenance.get("leg_matrix"),
        "cache": {key: cache.get(key) for key in (
            "fingerprint", "base_fingerprint", "variant_params",
            "variant_params_hash")},
        "planner_partition": {key: cache.get(key) for key in (
            "planner_candidate_ids_scores", "planner_items", "bubble_items",
            "free_text_items", "mask_rles")},
        "regions": [{key: record.get(key) for key in (
            "id", "artifact_id", "route_taken", "source_boxes",
            "mask_resolution", "context_crop_bbox", "timing_ms")}
                    for record in provenance.get("regions", [])],
    }


def _assert_route_matrix(provenance: dict, bubble_leg: str, free_leg: str) -> None:
    assert provenance["leg_matrix"] == {
        "bubble": bubble_leg, "free_text": free_leg}
    assert provenance["variant"]["leg_matrix"] == provenance["leg_matrix"]
    routes = {record["id"]: record["route_taken"]
              for record in provenance.get("regions", [])}
    assert routes.get("bubble-text", "").startswith(f"bubble/{bubble_leg}"), routes
    assert routes.get("free-text", "").startswith(f"freetext/{free_leg}"), routes


def _assert_same_upstream(first: dict, other: dict) -> None:
    first_key, other_key = first["cache_key"], other["cache_key"]
    for field in ("regions", "planner_candidate_ids_scores", "planner_items",
                  "bubble_items", "free_text_items", "mask_rles"):
        assert first_key.get(field) == other_key.get(field), field

    def paddle_boxes(provenance: dict) -> dict:
        return {
            region["id"]: [box for box in region.get("source_boxes", [])
                           if box.get("source") == "paddle-refined"]
            for region in provenance.get("regions", [])
        }
    expected = paddle_boxes(first)
    assert expected.get("free-text"), "fake Paddle refinement must be visible in provenance"
    assert expected == paddle_boxes(other), "Paddle refinement changed with fill leg"


def main() -> None:
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="t937-b3-variants-") as temp:
        chapter = Path(temp)
        pipeline, _ = _seed_pipeline(chapter)
        original_inpaint = inpaint_android.inpaint_page_android
        call_count = 0

        def counted_inpaint(*args, **kwargs):
            nonlocal call_count
            call_count += 1
            return original_inpaint(*args, **kwargs)

        inpaint_android.inpaint_page_android = counted_inpaint
        try:
            first = pipeline.inpaint_page(PAGE, force=False, mode="QUALITY")
            assert not first["cache_hit"]
            first_doc = _read_provenance(first)
            _assert_route_matrix(first_doc, "android-fill", "opencv")
            baseline_output = np.asarray(Image.open(first["path"]).convert("RGB"))
            shutil.copy2(first["path"], EVIDENCE / "baseline_fast.png")
            shutil.copy2(first["mask_path"], EVIDENCE / "baseline_fast_mask.png")
            (EVIDENCE / "baseline_fast_routes.json").write_text(
                json.dumps(_route_evidence("android-fast", first_doc), indent=2),
                encoding="utf-8")
            first_fingerprint = first["cache_fingerprint"]

            scenarios = [
                ("opencv_method_ns", {"inpaint_opencv_method": "ns"}),
                ("free_leg_pushpull", {"inpaint_opencv_method": "telea",
                                        "inpaint_free_leg": "pushpull"}),
                ("bubble_leg_opencv", {"inpaint_bubble_leg": "opencv"}),
                ("telea_radius_4", {"inpaint_opencv_method": "telea",
                                     "inpaint_telea_radius": 4}),
                ("erosion_radius_3", {"bubble_mask_erosion": 3}),
                ("feather_override_6", {"inpaint_feather_px": 6}),
            ]
            previous_fingerprint = first_fingerprint
            computed_fingerprints = [first_fingerprint]
            for index, (label, patch) in enumerate(scenarios):
                pipeline.save_settings(patch)
                active_png = chapter / ".studio" / "inpaint" / "variant-page.png"
                assert not active_png.exists(), f"stale output survived setting change: {label}"
                result = pipeline.inpaint_page(PAGE, force=False, mode="QUALITY")
                assert not result["cache_hit"], f"new variant unexpectedly hit cache: {label}"
                assert result["cache_fingerprint"] != previous_fingerprint
                doc = _read_provenance(result)
                _assert_route_matrix(
                    doc, pipeline.settings["inpaint_bubble_leg"],
                    pipeline.settings["inpaint_free_leg"])
                _assert_same_upstream(first_doc, doc)
                assert doc["variant"]["params_hash"] == doc["cache_key"]["variant_params_hash"]
                if label == "opencv_method_ns":
                    assert doc["variant"]["params"]["opencv_method"] == "ns"
                    assert doc["cache_key"]["variant_params"]["opencv_method"] == "ns"
                    ns_route = next(record["route_taken"]
                                    for record in doc["regions"]
                                    if record["id"] == "free-text")
                    assert "opencv-ns" in ns_route, ns_route
                    ns_output = np.asarray(Image.open(result["path"]).convert("RGB"))
                    assert np.any(baseline_output != ns_output), (
                        "NS and Telea produced identical pixels for the same legs")
                    shutil.copy2(result["path"], EVIDENCE / "opencv_ns.png")
                    shutil.copy2(result["mask_path"], EVIDENCE / "opencv_ns_mask.png")
                computed_fingerprints.append(result["cache_fingerprint"])
                (EVIDENCE / f"{label}_routes.json").write_text(
                    json.dumps(_route_evidence(label, doc), indent=2),
                    encoding="utf-8")
                if label == "free_leg_pushpull":
                    changed_output = np.asarray(Image.open(result["path"]).convert("RGB"))
                    assert np.any(baseline_output != changed_output), (
                        "FAST and PushPull variants produced identical pixels")
                    shutil.copy2(result["path"], EVIDENCE / "free_pushpull.png")
                    shutil.copy2(result["mask_path"], EVIDENCE / "free_pushpull_mask.png")
                previous_fingerprint = result["cache_fingerprint"]

            assert len(set(computed_fingerprints)) == len(computed_fingerprints)
            variant_dirs = (chapter / ".studio" / "inpaint_variants" /
                            Path(PAGE).stem)
            assert all((variant_dirs / fingerprint / "provenance.json").is_file()
                       for fingerprint in computed_fingerprints), "variant entries not retained"

            # Return to the original FAST variant; restore its saved result without fill work.
            original_variant_dir = variant_dirs / first_fingerprint
            stale_mtime_ns = time.time_ns() - 120_000_000_000
            os.utime(original_variant_dir, ns=(stale_mtime_ns, stale_mtime_ns))
            stale_mtime_ns = original_variant_dir.stat().st_mtime_ns
            pipeline.save_settings({
                "inpaint_engine": "android",
                "inpaint_bubble_leg": "android-fill",
                "inpaint_free_leg": "opencv",
                "inpaint_opencv_method": "telea",
                "inpaint_telea_radius": 3,
                "bubble_mask_erosion": 5,
                "inpaint_feather_px": None,
            })
            calls_before_switchback = call_count
            switched_back = pipeline.inpaint_page(PAGE, force=False, mode="QUALITY")
            assert switched_back["cache_hit"], "switching back should hit original variant cache"
            assert call_count == calls_before_switchback, "switchback recomputed inpaint"
            assert switched_back["cache_fingerprint"] == first_fingerprint
            assert original_variant_dir.stat().st_mtime_ns > stale_mtime_ns, (
                "cache restore did not refresh variant eviction recency")
            switchback_compute_delta = call_count - calls_before_switchback
            switchback_doc = _read_provenance(switched_back)
            _assert_route_matrix(switchback_doc, "android-fill", "opencv")

            # Explicit force always reruns even when the matching variant is cached.
            calls_before_force = call_count
            forced = pipeline.inpaint_page(PAGE, force=True, mode="QUALITY")
            assert not forced["cache_hit"]
            assert call_count == calls_before_force + 1, "force did not regenerate the variant"
            forced_doc = _read_provenance(forced)
            assert forced_doc["variant"]["params_hash"] == first_doc["variant"]["params_hash"]
            (EVIDENCE / "switchback_routes.json").write_text(
                json.dumps(_route_evidence("switchback", switchback_doc), indent=2),
                encoding="utf-8")
            (EVIDENCE / "force_routes.json").write_text(
                json.dumps(_route_evidence("forced", forced_doc), indent=2),
                encoding="utf-8")

            summary = {
                "passed": True,
                "page": PAGE,
                "variant_fingerprints": computed_fingerprints,
                "switchback_cache_hit": switched_back["cache_hit"],
                "switchback_recomputed": switchback_compute_delta > 0,
                "force_regenerated": call_count == calls_before_force + 1,
                "opencv_method_ns_verified": True,
                "variant_cache_recency_refreshed": True,
                "inpaint_compute_count": call_count,
                "selected_legs_verified": True,
                "planner_partition_and_paddle_refinement_stable": True,
                "evidence": [
                    "baseline_fast_routes.json", "free_leg_pushpull_routes.json",
                    "opencv_method_ns_routes.json", "bubble_leg_opencv_routes.json",
                    "telea_radius_4_routes.json",
                    "erosion_radius_3_routes.json", "feather_override_6_routes.json",
                    "switchback_routes.json", "force_routes.json",
                    "baseline_fast.png", "baseline_fast_mask.png",
                    "opencv_ns.png", "opencv_ns_mask.png",
                    "free_pushpull.png", "free_pushpull_mask.png",
                ],
            }
            (EVIDENCE / "variant_selftest.json").write_text(
                json.dumps(summary, indent=2), encoding="utf-8")
            print(json.dumps(summary, indent=2), flush=True)
        finally:
            inpaint_android.inpaint_page_android = original_inpaint


if __name__ == "__main__":
    main()
