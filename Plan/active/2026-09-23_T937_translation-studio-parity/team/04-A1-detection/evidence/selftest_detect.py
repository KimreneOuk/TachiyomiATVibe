"""Offline A1 detector/capture smoke test. Writes compact box evidence here."""
from __future__ import annotations

import json
import shutil
import sys
from pathlib import Path
from tempfile import TemporaryDirectory

from PIL import Image

REPO = Path(__file__).resolve().parents[6]
STUDIO = REPO / "tools" / "translation_studio"
sys.path.insert(0, str(STUDIO))

import pipeline  # noqa: E402
from boxgeom import (  # noqa: E402
    Box,
    DET_THRESHOLDS,
    TEXT_THRESHOLDS,
    greedy_dedup_with_suppressions,
    dedupe_within_parents_with_suppressions,
    suppress_cross_label_with_suppressions,
)
from detection_artifacts import assign_mask_center  # noqa: E402
from panel_detector import panel_nms  # noqa: E402
from sliding_detector import merge_window_detections  # noqa: E402

EVIDENCE = Path(__file__).resolve().parent
DEMO = STUDIO / "demo_chapter"
MODEL_KEYS = {"text-detector", "panel-detector", "bubble-segmenter"}


def assert_decision_ids_exist(cache: dict) -> dict:
    models = cache["models"]
    all_ids = set()
    for model in models.values():
        all_ids.update(item["id"] for item in model.get("outputs", []))
        all_ids.update(item["id"] for item in model.get("derived_outputs", []))
    suppression_records = cache.get("decisions", {}).get("suppression_records", [])
    merge_records = cache.get("decisions", {}).get("merge_records", [])
    for event in suppression_records:
        assert event["loser_id"] in all_ids, event
        assert event["winner_id"] in all_ids, event
    for event in merge_records:
        assert event["loser_id"] in all_ids, event
        assert event["winner_id"] in all_ids, event
        assert event["merged_with"] == event["winner_id"], event
    return {"artifact_ids": len(all_ids),
            "suppression_records": len(suppression_records),
            "merge_records": len(merge_records)}


def helper_provenance_smoke() -> dict:
    def assert_ids(events: list[dict], known: set[str]) -> None:
        for event in events:
            assert event["loser_id"] in known, event
            assert event["winner_id"] in known, event

    boxes = [Box(10, 10, 50, 50), Box(11, 11, 51, 51)]
    kept, det_suppressed = greedy_dedup_with_suppressions(
        boxes, [0.9, 0.8], ["d0", "d1"], DET_THRESHOLDS,
        groups=[(0, 1), (0, 1)])
    assert kept == [0] and det_suppressed[0]["winner_id"] == "d0"
    assert_ids(det_suppressed, {"d0", "d1"})

    bubbles = [Box(0, 0, 100, 100)]
    kept, ocr_suppressed = dedupe_within_parents_with_suppressions(
        boxes, [1, 1], [0.9, 0.8], ["o0", "o1"], bubbles)
    assert kept == [0] and ocr_suppressed[0]["rule"] == "ocr-dedup"
    assert_ids(ocr_suppressed, {"o0", "o1"})
    kept, cross_suppressed = suppress_cross_label_with_suppressions(
        boxes, [1, 2], [0.9, 0.8], ["x0", "x1"], bubbles)
    assert kept == [0] and cross_suppressed[0]["threshold"] == 0.3
    assert_ids(cross_suppressed, {"x0", "x1"})

    # Android's OCR dedup is page-wide for same-label boxes, even when their
    # center-pixel parents differ. This catches the former parent-group drift.
    separate_parents = [Box(0, 0, 49, 100), Box(50, 0, 100, 100)]
    overlapping = [Box(29, 30, 69, 70), Box(30, 30, 70, 70)]
    kept, cross_parent_suppressed = dedupe_within_parents_with_suppressions(
        overlapping, [1, 1], [0.9, 0.8], ["p0", "p1"], separate_parents)
    assert kept == [0] and cross_parent_suppressed[0]["winner_id"] == "p0"
    assert_ids(cross_parent_suppressed, {"p0", "p1"})

    merged, merge_records = merge_window_detections([
        {"id": "w0", "label": 1, "score": 0.9, "raw_score": 0.9,
         "box": [10, 100, 50, 130], "window": 0},
        {"id": "w1", "label": 1, "score": 0.8, "raw_score": 0.8,
         "box": [10, 135, 50, 165], "window": 1},
    ])
    assert len(merged) == 1 and merged[0]["box"] == [10, 100, 50, 165]
    assert merge_records[0]["merged_with"] == "w0"
    assert_ids(merge_records, {"w0", "w1"})

    panel_kept, panel_suppressed = panel_nms([
        {"score": 0.9, "box": [0, 0, 0, 1], "page_box_valid": False,
         "model_box": [10, 10, 100, 100]},
        {"score": 0.8, "box": [0, 0, 10, 10], "page_box_valid": True,
         "model_box": [11, 11, 101, 101]},
    ], ["pd0", "pd1"])
    assert panel_kept == [0]
    assert panel_suppressed[0]["winner_id"] == "pd0"
    assert_ids(panel_suppressed, {"pd0", "pd1"})

    # The block center selects the first containing RLE mask, then its
    # component id, carrying the segmenter asset/window provenance forward.
    mask_source = {"model": "bubble-segmenter", "asset": "seg.onnx",
                   "asset_sha": "0123456789ab", "window": 2}
    mask_outputs = [{"id": "m0", "mask_ref": "m0", "source": mask_source},
                    {"id": "m1", "mask_ref": "m1", "source": {**mask_source, "window": 3}}]
    mask_cache = {
        "m0": {"width": 4, "height": 4, "runs": [5, 6],
               "components": {"7": [5, 6]}},
        "m1": {"width": 4, "height": 4, "runs": [5, 6],
               "components": {"8": [5, 6]}},
    }
    assignment = assign_mask_center(mask_outputs, mask_cache, [1, 1, 3, 3])
    assert assignment == {"mask_ref": "m0", "mask_component_id": 7,
                          "source": mask_source}
    return {"det_thresholds": vars(DET_THRESHOLDS),
            "ocr_thresholds": vars(TEXT_THRESHOLDS),
            "det_dedup": det_suppressed,
            "ocr_dedup": ocr_suppressed,
            "cross_parent_ocr_dedup": cross_parent_suppressed,
            "xlabel": cross_suppressed,
            "win_merge": merge_records,
            "panel_nms": panel_suppressed,
            "mask_center_assignment": assignment}


def main() -> None:
    source_pages = sorted(DEMO.glob("*.jpg"))
    assert len(source_pages) >= 2, f"expected the two demo pages under {DEMO}"
    before = {}
    after = {}
    cache_summaries = {}

    with TemporaryDirectory(prefix="t937-a1-detect-") as temp:
        chapter = Path(temp) / "demo_chapter"
        chapter.mkdir()
        page_sizes = {}
        for source in source_pages:
            target = chapter / source.name
            shutil.copy2(source, target)
            with Image.open(target) as image:
                page_sizes[source.name] = list(image.size)

        # Pre-change baseline: the previous desktop path made one full-page
        # detector call per image, with no tall-window slicing or panel/mask cache.
        baseline_detector = pipeline.Detector(pipeline.DETECTOR_PATH)
        for source in source_pages:
            with Image.open(source) as image:
                outputs = baseline_detector.detect(image.convert("RGB"))
                before[source.name] = {
                    "mode": "single full-page detector call",
                    "page_wh": list(image.size),
                    "boxes": [[item["label"], item["score"], *item["box"]]
                              for item in outputs],
                }

        with Image.open(source_pages[0]) as first, Image.open(source_pages[1]) as second:
            first, second = first.convert("RGB"), second.convert("RGB")
            width = max(first.width, second.width)
            height = first.height + second.height
            tall_image = Image.new("RGB", (width, height), (255, 255, 255))
            tall_image.paste(first, ((width - first.width) // 2, 0))
            tall_image.paste(second, ((width - second.width) // 2, first.height))
            assert tall_image.height / tall_image.width >= 2.0
            tall_path = chapter / "tall_synthetic.jpg"
            tall_image.save(tall_path, quality=95)
            before["tall_synthetic.jpg"] = {
                "mode": "single full-page detector call",
                "page_wh": [width, height],
                "boxes": [[item["label"], item["score"], *item["box"]]
                          for item in baseline_detector.detect(tall_image)],
            }
            first.close()
            second.close()
            tall_image.close()

        pipe = pipeline.Pipeline()
        pipe.open_folders(str(chapter), None)
        assert pipe.settings["conf"] == 0.6
        assert pipeline.CONF_FLOOR == 0.05
        pages = [source.name for source in source_pages] + ["tall_synthetic.jpg"]
        for page in pages:
            result = pipe.detect_page(page)
            cache = json.loads((chapter / ".studio" / "detections.json").read_text(
                encoding="utf-8"))[page]
            assert set(cache["models"]) == MODEL_KEYS
            assert cache["page_wh"] == page_sizes.get(page, before[page]["page_wh"])
            refs = assert_decision_ids_exist(cache)
            image_width, image_height = cache["page_wh"]
            if cache["is_tall"]:
                assert len(cache["windows"]) >= 2
                windows = {window["index"]: window for window in cache["windows"]}
                raw_text_outputs = cache["models"]["text-detector"]["outputs"]
                assert raw_text_outputs
                for record in raw_text_outputs:
                    window_index = record["source"]["window"]
                    assert window_index in windows
                    geom = record["geometry"]
                    window = windows[window_index]
                    assert window["top"] - 2 <= geom["y1"] <= window["bottom"] + 2
                    assert window["top"] - 2 <= geom["y2"] <= image_height + 2
                    assert -2 <= geom["y1"] <= image_height + 2
                for encoded in cache["mask_cache"]["bubble-segmenter"].values():
                    assert 0 <= encoded["bounds"][1] <= encoded["bounds"][3] <= image_height
                    assert all(start + length <= image_width * image_height
                               for start, length in zip(encoded["runs"][0::2],
                                                        encoded["runs"][1::2]))
            after[page] = {
                "mode": "windowed detector with replayed Android stages",
                "page_wh": cache["page_wh"],
                "is_tall": cache["is_tall"],
                "windows": cache["windows"],
                "raw_boxes": cache["boxes"],
                "regions": result["regions"],
                "panels": result["panels"],
                "model_counts": {key: len(model.get("outputs", []))
                                 for key, model in cache["models"].items()},
                "model_status": {key: model["status"]
                                 for key, model in cache["models"].items()},
            }
            cache_summaries[page] = {**refs,
                                     "model_counts": after[page]["model_counts"],
                                     "model_status": after[page]["model_status"]}

        # Re-filter cached raw outputs without invoking inference again.
        tall_before = len(after["tall_synthetic.jpg"]["raw_boxes"])
        saved_detection_writes = []
        save_json = pipe._save_json

        def track_save(name, data):
            if name == "detections.json":
                saved_detection_writes.append(name)
            return save_json(name, data)

        pipe._save_json = track_save
        lower_conf = pipe.detect_page("tall_synthetic.jpg", conf=0.3)
        assert saved_detection_writes == ["detections.json"]
        saved_detection_writes.clear()
        pipe.detect_page("tall_synthetic.jpg", conf=0.3)
        assert saved_detection_writes == []
        refreshed = json.loads((chapter / ".studio" / "detections.json").read_text(
            encoding="utf-8"))["tall_synthetic.jpg"]
        assert len(refreshed["boxes"]) == tall_before
        assert refreshed["models"]["text-detector"]["asset_sha"]
        after["tall_synthetic.jpg"]["lower_conf_region_count"] = len(lower_conf["regions"])

    (EVIDENCE / "before_boxes.json").write_text(
        json.dumps(before, indent=2), encoding="utf-8")
    (EVIDENCE / "after_boxes.json").write_text(
        json.dumps(after, indent=2), encoding="utf-8")
    verification = {
        "status": "passed",
        "conf_default": 0.6,
        "raw_cache_floor": 0.05,
        "pages": cache_summaries,
        "replay_save_behavior": {"on_context_change": 1,
                                 "same_context_cache_hits": 0},
        "decision_fixture_checks": helper_provenance_smoke(),
    }
    (EVIDENCE / "verification.json").write_text(
        json.dumps(verification, indent=2), encoding="utf-8")
    print(json.dumps(verification, indent=2))


if __name__ == "__main__":
    main()
