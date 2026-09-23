"""Focused, model-free checks for the T937 A4 planner/mask contract."""
from __future__ import annotations

import hashlib
import json
import tempfile
from pathlib import Path

import numpy as np
from PIL import Image

import pipeline
from detection_artifacts import (box_artifact, iter_rle_row_spans,
                                 mask_artifact)
from segmentation import BubbleMask


REPO_ROOT = Path(__file__).resolve().parents[2]
EVIDENCE_PATH = (REPO_ROOT / "Plan/active/2026-09-23_T937_translation-studio-parity"
                 / "team/06-A4-mask-planner/evidence/selftest_mask_planner.json")


def _sha(mask: np.ndarray) -> str:
    return hashlib.sha256(memoryview(np.ascontiguousarray(mask))).hexdigest()


def _capture(page: str, width: int, height: int, detections: list[dict],
             mask: np.ndarray, *, tall: bool = False, window: int | None = None,
             window_top: int = 0) -> tuple[dict, dict, list[dict]]:
    text_records = []
    legacy_boxes = []
    for index, (label, score, box) in enumerate(detections):
        record = box_artifact(
            f"td{index:04d}", page, "text-detector", "fixture.onnx",
            "fixture-sha", window, box, score, label)
        text_records.append(record)
        legacy_boxes.append([label, score, *box])

    segmenter_record, encoded = mask_artifact(
        "bs0000", page, "fixture-segmenter.onnx", "segmenter-sha",
        window, window_top, (width, height), BubbleMask.build(mask, 0.93))
    raw = {
        "capture_version": 1,
        "boxes": legacy_boxes,
        "page_wh": [width, height],
        "is_tall": tall,
        "models": {
            "text-detector": {"asset": "fixture.onnx", "asset_sha": "fixture-sha",
                              "status": "ready", "outputs": text_records},
            "panel-detector": {"status": "disabled", "outputs": [], "kept_ids": []},
            "bubble-segmenter": {"status": "ready", "outputs": [segmenter_record]},
        },
        "mask_cache": {"bubble-segmenter": {"bs0000": encoded}},
        "decisions": {"conf": None, "panel_assignments_enabled": False,
                      "segmenter_assignments_enabled": False},
    }
    return raw, encoded, [segmenter_record]


def _pipeline(chapter: Path, page: str, raw: dict, ocr_regions: list[dict]) -> pipeline.Pipeline:
    p = pipeline.Pipeline()
    p.chapter = chapter
    p.pages = [page]
    p.settings = dict(pipeline.DEFAULT_SETTINGS)
    p.settings.update({"conf": 0.6, "inpaint_engine": "android",
                       "inpaint_bubble_leg": "android-fill",
                       "inpaint_free_leg": "opencv", "bubble_segmentation": True})
    p._cache = {"detections": {page: raw},
                "ocr": {page: {"regions": ocr_regions, "engine": "mangaocr"}},
                "translations": {page: {}}}
    page_info = p._compute_page_fingerprint(page)
    p._page_fingerprints[page] = page_info
    text_model = raw["models"]["text-detector"]
    text_model["asset"] = pipeline.DETECTOR_PATH.name
    text_model["asset_sha"] = pipeline._asset_sha12(pipeline.DETECTOR_PATH)
    panel_model = raw["models"]["panel-detector"]
    panel_model["asset"] = pipeline.PANEL_DETECTOR_PATH.name
    panel_model["asset_sha"] = pipeline._asset_sha12(pipeline.PANEL_DETECTOR_PATH)
    panel_model.setdefault("outputs", [])
    segmenter_model = raw["models"]["bubble-segmenter"]
    from segmentation import SEGMENTER_MODEL
    segmenter_model["asset"] = SEGMENTER_MODEL.name
    segmenter_model["asset_sha"] = pipeline._asset_sha12(SEGMENTER_MODEL)
    p._stamp_detection_capture(raw, page_info)
    p._dims = {page: list(raw["page_wh"])}
    p._render_dirty = {page: True}
    p._bubble_mask_cache = {}
    p._inpaint_paddle_det = lambda: None
    # These A4 planner cases intentionally exercise synthetic OCR records,
    # including records excluded at the current confidence. Keep that fixture
    # payload fixed while A5 production tests cover OCR cache validation.
    p.ocr_page = lambda requested_page, conf=None, force=False: {
        "page": requested_page,
        "regions": p._cache["ocr"].get(requested_page, {}).get("regions", []),
    }
    return p


def _ocr_region(page_artifact_id: str, box: list[int], *, region_id: str = "r00",
                label: int = 1, text: str = "hello") -> dict:
    return {"id": region_id, "artifact_id": page_artifact_id, "label": label,
            "class": "text_bubble", "score": 0.9, "box": box,
            "ocr_box": box, "text": text, "raw_text": text,
            "confidence": 0.99, "engine": "mangaocr"}


def _normal_page_checks() -> dict:
    page = "normal.png"
    width, height = 80, 64
    with tempfile.TemporaryDirectory(prefix="t937-a4-normal-") as temp:
        chapter = Path(temp)
        Image.new("RGB", (width, height), (168, 160, 150)).save(chapter / page)
        component = np.zeros((height, width), dtype=bool)
        component[8:46, 10:48] = True
        detections = [
            (0, 0.41, [8, 6, 50, 48]),
            (1, 0.90, [18, 19, 34, 31]),
            (2, 0.41, [58, 8, 74, 18]),
            (2, 0.41, [56, 32, 78, 56]),
        ]
        raw, encoded, segmenter_records = _capture(
            page, width, height, detections, component)
        source_ids = [record["id"] for record in raw["models"]["text-detector"]["outputs"]]
        p = _pipeline(chapter, page, raw,
                      [_ocr_region("dt-td0001", [18, 19, 34, 31]),
                       _ocr_region("dt-td0003", [56, 32, 78, 56],
                                   region_id="r02", label=2,
                                   text="low confidence")])

        projected = p._regions_at_conf(page, 0.6)["regions"]
        assignment = projected[0]["segmenter_assignment"]
        assert assignment["mask_ref"] == "bs0000"
        assert assignment["mask_component_id"] == 1
        expected_sha = _sha(component)

        p.settings["conf"] = 0.6
        first = p.inpaint_page(page, force=True)
        with open(first["provenance_path"], "r", encoding="utf-8") as stream:
            first_json = json.load(stream)
        first_region = next(item for item in first_json["regions"]
                            if item["kind"] == "ocr-region")
        assigned_source = next(item for item in first_region["source_boxes"]
                               if item["source"] == "bubble-segmenter")
        assert first_json["provenance_schema_version"] == 2
        assert first_json["execution"]["confidence_threshold"] == 0.6
        assert first_json["execution"]["parity"] == "android-production"
        assert first_json["stats"]["segmenter_union_sha256"] == expected_sha
        assert first_json["stats"]["segmenter_union_pixels"] == int(component.sum())
        assert assigned_source["mask_ref"] == assignment["mask_ref"]
        assert assigned_source["segmenter_component_id"] == assignment["mask_component_id"]
        assert assigned_source["model"] == "bubble-segmenter"
        assert assigned_source["window"] is None
        assert first_region["segmenter_component_id"] == 1
        assert first_region["erase_mask_component_id"] == first_region["mask_component_id"]
        assert first_region["erase_mask_component_id"].startswith("c")
        assert first_json["execution"]["candidate_artifact_ids"] == ["dt-td0001"]
        assert "dt-td0000" not in first_json["execution"]["candidate_artifact_ids"]
        assert "detector-dt-td0002" not in {item["id"] for item in first_json["regions"]}
        assert "parent_bubble_artifact_id" not in first_region
        # Raw model records remain cached for the diagnosis/display filters.
        assert [record["id"] for record in raw["models"]["text-detector"]["outputs"]] == source_ids
        assert len(raw["models"]["text-detector"]["outputs"]) == 4
        assert raw["models"]["text-detector"]["outputs"][0]["attrs"]["score"] == 0.41
        assert raw["models"]["text-detector"]["outputs"][0]["lifecycle"]["state"] == "raw"
        assert raw["models"]["text-detector"]["outputs"][0]["lifecycle"]["trace"][0]["kept"] is False
        low_candidate_state_at_0_6 = raw["models"]["text-detector"]["outputs"][0]["lifecycle"]["state"]

        p._cache["translations"][page] = {"r00": "translated",
                                          "r02": "low confidence"}
        render = p.render_page(page, force=True)
        render_assignment = next(item for item in render["mask_assignments"]
                                 if item["region_id"] == "r00")
        assert render_assignment["status"] == "resolved"
        assert render_assignment["mask_ref"] == assignment["mask_ref"]
        assert render_assignment["segmenter_component_id"] == assignment["mask_component_id"]
        low_initial_render = next(item for item in render["mask_assignments"]
                                  if item["region_id"] == "r02")
        assert low_initial_render["status"] == "not-in-current-execution-projection"
        rendered_pixels = np.asarray(Image.open(render["path"]).convert("RGB"))
        baseline_pixels = np.asarray(p.inpainted_image(page))
        assert np.array_equal(rendered_pixels[32:56, 56:78],
                              baseline_pixels[32:56, 56:78])

        # Lowering the execution control admits both .41 candidates, while
        # keeping the original detector capture immutable.
        p.settings["conf"] = 0.3
        second = p.inpaint_page(page, force=False)
        with open(second["provenance_path"], "r", encoding="utf-8") as stream:
            second_json = json.load(stream)
        second_region = next(item for item in second_json["regions"]
                             if item["kind"] == "ocr-region")
        second_ids = second_json["execution"]["candidate_artifact_ids"]
        assert set(second_ids) == {"dt-td0000", "dt-td0001", "dt-td0002", "dt-td0003"}
        assert second_json["execution"]["parity"] == "experimental/non-parity"
        assert second_json["execution"]["confidence_threshold"] == 0.3
        assert second_json["cache_key"]["execution_confidence"] == 0.3
        assert second_region["parent_bubble_artifact_id"] == "dt-td0000"
        parent_source = next(item for item in second_region["source_boxes"]
                             if item.get("role") == "parent-bubble")
        assert parent_source["artifact_id"] == "dt-td0000"
        assert parent_source["model"] == "text-detector"
        assert parent_source["asset"].endswith(".onnx")
        assert parent_source["asset_sha"] == pipeline._asset_sha12(
            pipeline.DETECTOR_PATH)
        assert parent_source["window"] is None
        detector_only = next(item for item in second_json["regions"]
                             if item["kind"] == "detector-only")
        assert detector_only["artifact_id"] == "dt-td0002"
        assert detector_only["route_taken"].startswith("freetext/")
        assert second_json["stats"]["mask_pixels"] > first_json["stats"]["mask_pixels"]
        assert len(raw["models"]["text-detector"]["outputs"]) == 4

        p.render_page(page, force=True)
        p.settings["conf"] = 0.6
        raised_conf = p.inpaint_page(page, force=False)
        with open(raised_conf["provenance_path"], "r", encoding="utf-8") as stream:
            raised_json = json.load(stream)
        assert raised_json["execution"]["candidate_artifact_ids"] == ["dt-td0001"]
        raised_render = p.render_page(page, force=True)
        low_raised_render = next(item for item in raised_render["mask_assignments"]
                                 if item["region_id"] == "r02")
        assert low_raised_render["status"] == "not-in-current-execution-projection"
        rendered_pixels = np.asarray(Image.open(raised_render["path"]).convert("RGB"))
        baseline_pixels = np.asarray(p.inpainted_image(page))
        assert np.array_equal(rendered_pixels[32:56, 56:78],
                              baseline_pixels[32:56, 56:78])

        p.settings["bubble_segmentation"] = False
        disabled = p.inpaint_page(page, force=False)
        with open(disabled["provenance_path"], "r", encoding="utf-8") as stream:
            disabled_json = json.load(stream)
        assert disabled_json["cache_key"]["bubble_segmentation"] is False
        assert disabled_json["stats"]["segmenter_union_pixels"] == 0
        disabled_overlay = np.asarray(p.segmentation_overlay_image(page))
        assert not np.any(disabled_overlay[..., 3])
        p.settings["bubble_segmentation"] = True
        reenabled = p.inpaint_page(page, force=False)
        with open(reenabled["provenance_path"], "r", encoding="utf-8") as stream:
            reenabled_json = json.load(stream)
        assert reenabled_json["cache_key"]["bubble_segmentation"] is True
        assert reenabled_json["stats"]["segmenter_union_pixels"] == int(component.sum())

        return {
            "page": page,
            "captured_mask_ref": assignment["mask_ref"],
            "captured_segmenter_component_id": assignment["mask_component_id"],
            "inpaint_mask_ref": assigned_source["mask_ref"],
            "render_mask_ref": render_assignment["mask_ref"],
            "erase_mask_component_id": first_region["erase_mask_component_id"],
            "mask_component_id_alias": first_region["mask_component_id"],
            "schema_version": first_json["provenance_schema_version"],
            "threshold_0_6": {
                "parity": first_json["execution"]["parity"],
                "candidate_ids": first_json["execution"]["candidate_artifact_ids"],
                "raw_low_candidate_state": low_candidate_state_at_0_6,
                "low_label_0_excluded_from_parent": "parent_bubble_artifact_id" not in first_region,
                "low_label_2_excluded_from_erase": "detector-dt-td0002" not in {
                    item["id"] for item in first_json["regions"]},
            },
            "threshold_0_3": {
                "parity": second_json["execution"]["parity"],
                "candidate_ids": second_ids,
                "parent_bubble_artifact_id": second_region["parent_bubble_artifact_id"],
                "detector_only_artifact_id": detector_only["artifact_id"],
                "mask_pixels": second_json["stats"]["mask_pixels"],
            },
            "confidence_raise_render": {
                "excluded_region_status": low_raised_render["status"],
                "excluded_region_drawn": False,
            },
            "segmentation_setting_cache": {
                "disabled_union_pixels": disabled_json["stats"]["segmenter_union_pixels"],
                "reenabled_union_pixels": reenabled_json["stats"]["segmenter_union_pixels"],
            },
            "expected_union_sha256": expected_sha,
            "actual_union_sha256": first_json["stats"]["segmenter_union_sha256"],
        }


def _tall_page_checks() -> dict:
    page = "tall.png"
    width, height = 48, 256
    window_top = 64
    with tempfile.TemporaryDirectory(prefix="t937-a4-tall-") as temp:
        chapter = Path(temp)
        Image.new("RGB", (width, height), (140, 145, 150)).save(chapter / page)
        local_mask = np.zeros((96, width), dtype=bool)
        local_mask[30:70, 8:40] = True
        expected = np.zeros((height, width), dtype=bool)
        expected[window_top:window_top + local_mask.shape[0], :] = local_mask
        raw, _, _ = _capture(
            page, width, height, [(1, 0.94, [12, 103, 30, 121])], local_mask,
            tall=True, window=2, window_top=window_top)
        p = _pipeline(chapter, page, raw,
                      [_ocr_region("dt-td0000", [12, 103, 30, 121])])

        def forbidden_full_page_segmenter(*args, **kwargs):
            raise AssertionError("current A1 capture triggered a segmenter fallback")

        p.bubble_masks = forbidden_full_page_segmenter
        first_projection = p._regions_at_conf(page, 0.6)["regions"][0]
        assignment = first_projection["segmenter_assignment"]
        assert assignment["mask_ref"] == "bs0000"
        assert assignment["source"]["window"] == 2
        overlay = np.asarray(p.segmentation_overlay_image(page))
        assert overlay.shape == (height, width, 4)
        assert int(overlay[..., 3].sum()) > 0
        assert tuple(overlay[105, 16]) == (56, 189, 248, 110)
        result = p.inpaint_page(page, force=True)
        with open(result["provenance_path"], "r", encoding="utf-8") as stream:
            provenance = json.load(stream)
        region = next(item for item in provenance["regions"]
                      if item["kind"] == "ocr-region")
        source = next(item for item in region["source_boxes"]
                      if item["source"] == "bubble-segmenter")
        expected_sha = _sha(expected)
        assert provenance["stats"]["segmenter_union_sha256"] == expected_sha
        assert provenance["stats"]["segmenter_union_pixels"] == int(expected.sum())
        assert source["mask_ref"] == assignment["mask_ref"]
        assert source["window"] == 2
        assert provenance["mask_source"] == "A1-cached-page-space-rle"
        return {
            "page": page,
            "mask_ref": source["mask_ref"],
            "window": source["window"],
            "segmenter_union_pixels": provenance["stats"]["segmenter_union_pixels"],
            "expected_union_sha256": expected_sha,
            "actual_union_sha256": provenance["stats"]["segmenter_union_sha256"],
            "full_page_segmenter_reruns": 0,
            "captured_rle_overlay_pixels": int(np.count_nonzero(overlay[..., 3])),
        }


def _bubble_union_scope_check() -> dict:
    page = "union-scope.png"
    width, height = 64, 64
    with tempfile.TemporaryDirectory(prefix="t937-a4-union-scope-") as temp:
        chapter = Path(temp)
        Image.new("RGB", (width, height), (155, 150, 145)).save(chapter / page)
        components = np.zeros((height, width), dtype=bool)
        components[8:12, 8:12] = True    # readable bubble text
        components[8:12, 28:32] = True   # blank OCR region
        components[34:38, 8:12] = True  # free-text detector proposal
        detections = [
            (1, 0.91, [5, 5, 15, 15]),
            (1, 0.92, [25, 5, 35, 15]),
            (2, 0.93, [5, 30, 15, 42]),
        ]
        raw, _, _ = _capture(page, width, height, detections, components)
        regions = [
            _ocr_region("dt-td0000", [5, 5, 15, 15],
                        region_id="r-bubble", text="bubble text"),
            _ocr_region("dt-td0001", [25, 5, 35, 15],
                        region_id="r-blank", text=""),
        ]
        p = _pipeline(chapter, page, raw, regions)
        result = p.inpaint_page(page, force=True)
        with open(result["provenance_path"], "r", encoding="utf-8") as stream:
            provenance = json.load(stream)
        expected = np.zeros((height, width), dtype=bool)
        expected[8:12, 8:12] = True
        assert provenance["stats"]["segmenter_union_pixels"] == int(expected.sum())
        assert provenance["stats"]["segmenter_union_sha256"] == _sha(expected)
        blank_record = next(item for item in provenance["regions"]
                            if item["id"] == "r-blank")
        free_record = next(item for item in provenance["regions"]
                           if item["kind"] == "detector-only")
        bubble_record = next(item for item in provenance["regions"]
                             if item["id"] == "r-bubble")
        assert blank_record["mask_resolution"]["status"] == \
            "not-selected-for-bubble-leg"
        assert free_record["mask_resolution"]["status"] == \
            "not-selected-for-bubble-leg"
        assert bubble_record["mask_resolution"]["status"] == "resolved"
        return {
            "page": page,
            "selected_bubble_union_pixels": provenance["stats"]["segmenter_union_pixels"],
            "expected_bubble_union_pixels": int(expected.sum()),
            "blank_ocr_mask_status": blank_record["mask_resolution"]["status"],
            "free_detector_mask_status": free_record["mask_resolution"]["status"],
            "bubble_mask_status": bubble_record["mask_resolution"]["status"],
            "union_sha256": provenance["stats"]["segmenter_union_sha256"],
        }


def _missing_reference_check() -> dict:
    page = "missing-mask.png"
    width, height = 40, 40
    with tempfile.TemporaryDirectory(prefix="t937-a4-missing-ref-") as temp:
        chapter = Path(temp)
        Image.new("RGB", (width, height), (150, 145, 140)).save(chapter / page)
        component = np.zeros((height, width), dtype=bool)
        component[8:32, 7:33] = True
        raw, _, _ = _capture(
            page, width, height, [(1, 0.91, [13, 14, 27, 25])], component)
        p = _pipeline(chapter, page, raw,
                      [_ocr_region("dt-td0000", [13, 14, 27, 25])])
        p._cache["detections"][page]["mask_cache"]["bubble-segmenter"].clear()
        result = p.inpaint_page(page, force=True)
        with open(result["provenance_path"], "r", encoding="utf-8") as stream:
            provenance = json.load(stream)
        integrity = provenance["execution"]["mask_capture_integrity"]
        assert integrity["status"] == "degraded-missing-references"
        assert integrity["degraded"] is True
        assert integrity["missing_mask_refs"] == ["bs0000"]
        assert provenance["mask_source"] == "A1-cached-page-space-rle"
        return {"status": integrity["status"],
                "degraded": integrity["degraded"],
                "missing_mask_refs": integrity["missing_mask_refs"],
                "fallback_segmenter_rerun": False}


def _rle_row_split_check() -> dict:
    spans = list(iter_rle_row_spans([6, 4], width=8, height=5))
    assert spans == [(0, 6, 8), (1, 0, 2)]
    return {"flat_run": [6, 4], "width": 8, "decoded_row_spans": spans}


def main() -> None:
    result = {
        "ticket": "T937 A4",
        "status": "passed",
        "normal_page": _normal_page_checks(),
        "bubble_union_scope": _bubble_union_scope_check(),
        "synthetic_tall_page": _tall_page_checks(),
        "missing_current_reference": _missing_reference_check(),
        "row_crossing_rle": _rle_row_split_check(),
    }
    EVIDENCE_PATH.parent.mkdir(parents=True, exist_ok=True)
    EVIDENCE_PATH.write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps(result, indent=2))
    print(f"Evidence: {EVIDENCE_PATH.relative_to(REPO_ROOT)}")


if __name__ == "__main__":
    main()
