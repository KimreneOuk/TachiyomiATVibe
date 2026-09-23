"""Real-model Studio run for the Gate 1 regular and A1-style tall pages."""
from __future__ import annotations

import gc
import hashlib
import json
import shutil
import sys
import tempfile
import time
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parent
REPO = Path(__file__).resolve().parents[6]
STUDIO = REPO / "tools" / "translation_studio"
sys.path.insert(0, str(STUDIO))

import inpaint_android  # noqa: E402
import paddle_ocr  # noqa: E402
import pipeline  # noqa: E402
import segmentation  # noqa: E402

DEMO = STUDIO / "demo_chapter"
MODELS = REPO / "app/src/main/assets/models/ocr/paddle-v6-small"
PADDLE_DET = MODELS / "det/inference.onnx"
PADDLE_REC = MODELS / "inference.onnx"
PRESETS = {
    "android-fast": {"inpaint_bubble_leg": "android-fill",
                     "inpaint_free_leg": "opencv"},
    "android-quality": {"inpaint_bubble_leg": "android-fill",
                         "inpaint_free_leg": "aot"},
}
CALLS: dict[str, list[dict]] = {}
ACTIVE_CASE = "unassigned"


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        while chunk := source.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def make_a1_tall_page(output: Path) -> dict:
    pages = [DEMO / "p001.jpg", DEMO / "p002.jpg"]
    with Image.open(pages[0]) as first_src, Image.open(pages[1]) as second_src:
        first, second = first_src.convert("RGB"), second_src.convert("RGB")
        width = max(first.width, second.width)
        height = first.height + second.height
        if height / width < 2.0:
            raise AssertionError(f"A1 stack is not tall enough: {width}x{height}")
        canvas = Image.new("RGB", (width, height), (255, 255, 255))
        canvas.paste(first, ((width - first.width) // 2, 0))
        canvas.paste(second, ((width - second.width) // 2, first.height))
        canvas.save(output, quality=95)
    return {"method": "A1 selftest_detect.py: stack p001 then p002, centered",
            "size": [width, height], "aspect_ratio": round(height / width, 4),
            "source_sha256": {page.name: file_sha256(page) for page in pages},
            "sha256": file_sha256(output)}


def make_free_text_control(source: Path, output: Path) -> dict:
    """Add known detector-only text to an empty panel on a full demo page."""
    with Image.open(source) as image_source:
        image = image_source.convert("RGB")
    text = "テスト"
    font_path = Path("C:/Windows/Fonts/msgothic.ttc")
    font = ImageFont.truetype(str(font_path), 72) if font_path.is_file() else ImageFont.load_default()
    draw = ImageDraw.Draw(image)
    x, y = 72, 78
    draw.text((x, y), text, fill=(10, 10, 10), font=font,
              stroke_width=0)
    bbox = draw.textbbox((x, y), text, font=font)
    image.save(output)
    return {"method": "known text drawn into p001's blank first-panel area",
            "text": text, "bbox": list(bbox), "size": list(image.size),
            "sha256": file_sha256(output)}


def install_call_counters() -> None:
    originals = [
        (pipeline.Detector, "detect", "text_detector", "image"),
        (segmentation.BubbleSegmenter, "segment", "bubble_segmenter", "image"),
        (paddle_ocr.PaddleRec, "recognize_batch", "paddle_rec", None),
        (paddle_ocr.PaddleDet, "detect_lines", "paddle_det", "crop"),
    ]
    for cls, method_name, label, image_arg in originals:
        original = getattr(cls, method_name)

        def tracked(self, *args, _original=original, _label=label,
                    _image_arg=image_arg, **kwargs):
            sizes = None
            if _image_arg == "image" and args:
                sizes = list(args[0].size)
            elif _image_arg == "crop" and args:
                sizes = list(args[0].size)
            row = {"method": _label, "size": sizes}
            if _label == "paddle_det":
                row["thresh"] = (kwargs["thresh"] if "thresh" in kwargs
                                  else args[1] if len(args) > 1
                                  else paddle_ocr.DB_THRESH)
                row["box_thresh"] = (
                    kwargs["box_thresh"] if "box_thresh" in kwargs
                    else args[2] if len(args) > 2
                    else paddle_ocr.DB_BOX_THRESH)
                row["kind"] = ("inpaint-refinement"
                               if abs(float(row["thresh"]) - inpaint_android.PADDLE_THRESH) < 1e-9
                               and abs(float(row["box_thresh"])
                                       - inpaint_android.PADDLE_BOX_THRESH) < 1e-9
                               else "ocr-line-planning")
            CALLS.setdefault(ACTIVE_CASE, []).append(row)
            result = _original(self, *args, **kwargs)
            if _label == "paddle_det":
                row["returned_lines"] = result
                row["returned_line_count"] = len(result or [])
            return result

        setattr(cls, method_name, tracked)


def save_json(path: Path, value) -> None:
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False, default=str),
                    encoding="utf-8")


def model_call_counts(case_id: str) -> dict:
    calls = CALLS.get(case_id, [])
    counts = {}
    for row in calls:
        counts[row["method"]] = counts.get(row["method"], 0) + 1
    return counts


def main() -> None:
    global ACTIVE_CASE
    HERE.mkdir(parents=True, exist_ok=True)
    if PADDLE_DET.stat().st_size != 9_880_512:
        raise AssertionError(f"unexpected PaddleDet size: {PADDLE_DET.stat().st_size}")
    if PADDLE_REC.stat().st_size != 21_159_378:
        raise AssertionError(f"unexpected PaddleRec size: {PADDLE_REC.stat().st_size}")

    input_dir = HERE / "studio_inputs"
    input_dir.mkdir(parents=True, exist_ok=True)
    tall_path = input_dir / "a1_tall_stack.jpg"
    tall_info = make_a1_tall_page(tall_path)
    probe_path = input_dir / "p001_free_text_control.png"
    control_info = make_free_text_control(DEMO / "p001.jpg", probe_path)
    regular_copy = input_dir / "p001.jpg"
    shutil.copy2(DEMO / "p001.jpg", regular_copy)
    cases = [
        ("regular-demo", regular_copy),
        ("a1-tall-stack", tall_path),
        ("regular-demo-free-text-control", probe_path),
    ]
    refinement_only = sys.argv[1:] == ["--refinement-probe"]
    if refinement_only:
        cases = [cases[-1]]
        presets = [("android-fast", PRESETS["android-fast"])]
    else:
        presets = list(PRESETS.items())
    case_records = []
    install_call_counters()

    with tempfile.TemporaryDirectory(prefix="t937-gate1-studio-",
                                     dir=HERE) as temp:
        chapter = Path(temp) / "chapter"
        chapter.mkdir()
        for case_id, source in cases:
            shutil.copy2(source, chapter / source.name)

        # Redirect the Studio recent-chapter side effect into the disposable run.
        pipeline.RECENT_FILE = Path(temp) / "recent.json"
        pipe = pipeline.Pipeline()
        pipe.open_folders(str(chapter), None)
        pipe.settings.update({"conf": 0.6, "ocr_engine": "paddle",
                              "inpaint_engine": "android",
                              "bubble_segmentation": True,
                              "source_language": "Japanese",
                              "target_lang": "English",
                              "translate_backend": "google"})
        if pipe.settings.get("inpaint_engine") != "android":
            raise AssertionError("Gate 1 runs require the Android inpaint engine")

        for case_id, source in cases:
            page = source.name
            for preset, legs in presets:
                ACTIVE_CASE = f"{case_id}/{preset}"
                CALLS[ACTIVE_CASE] = []
                pipe.settings.update(legs)
                started = time.perf_counter()
                first = pipe.process_page(page, translate=False)
                first_seconds = round(time.perf_counter() - started, 3)
                counts_after_first = model_call_counts(ACTIVE_CASE)
                det_state = pipe._cache["detections"][page]
                ocr_state = pipe._cache["ocr"][page]
                first_inpaint = pipe.inpaint_page(page)
                # The first process_page already inpainted. Capture a first-call
                # stage result from the route record; this call must be a hit.
                if not first_inpaint["cache_hit"]:
                    raise AssertionError("expected the immediate inpaint read to hit")

                run_dir = HERE / "studio_runs" / case_id / preset
                run_dir.mkdir(parents=True, exist_ok=True)
                detection_file = run_dir / "detection_capture.json"
                ocr_file = run_dir / "ocr_capture.json"
                route_file = run_dir / "routes.json"
                save_json(detection_file, det_state)
                save_json(ocr_file, ocr_state)
                provenance_path = Path(first_inpaint["provenance_path"])
                shutil.copy2(provenance_path, route_file)

                # Repeat an unchanged stage chain; count actual model invocations
                # and require downstream cache-hit flags for the second pass.
                before_second = len(CALLS[ACTIVE_CASE])
                det_second = pipe.detect_page(page)
                ocr_second = pipe.ocr_page(page)
                inpaint_second = pipe.inpaint_page(page)
                after_second = len(CALLS[ACTIVE_CASE])
                if not ocr_second.get("cache_hit"):
                    raise AssertionError(f"OCR did not hit on second run: {ACTIVE_CASE}")
                if not inpaint_second.get("cache_hit"):
                    raise AssertionError(f"inpaint did not hit on second run: {ACTIVE_CASE}")
                if before_second != after_second:
                    raise AssertionError(f"model recomputed on cache hit: {ACTIVE_CASE}")

                page_wh = [det_state["page_wh"][0], det_state["page_wh"][1]]
                segment_calls = [row for row in CALLS[ACTIVE_CASE]
                                 if row["method"] == "bubble_segmenter"]
                tall_full_page_segmenter_calls = [
                    row for row in segment_calls if row["size"] == page_wh]
                if det_state.get("is_tall") and tall_full_page_segmenter_calls:
                    raise AssertionError(
                        f"tall page segmenter ran at full-page size: {ACTIVE_CASE}")
                routes = json.loads(route_file.read_text(encoding="utf-8"))
                refined_regions = [region for region in routes.get("regions", [])
                                   if any(source_box.get("source") == "paddle-refined"
                                          for source_box in region.get("source_boxes", []))]
                refinement_calls = [row for row in CALLS[ACTIVE_CASE]
                                    if row.get("kind") == "inpaint-refinement"]
                case_records.append({
                    "case": case_id, "preset": preset, "page": page,
                    "page_wh": page_wh,
                    "page_fingerprint": det_state.get("page_fingerprint"),
                    "detection_capture_fingerprint": det_state.get("capture_fingerprint"),
                    "ocr_engine": ocr_state.get("engine"),
                    "ocr_regions": [{key: region.get(key) for key in
                                     ("id", "artifact_id", "label", "score", "box",
                                      "text", "confidence", "engine")}
                                    for region in ocr_state.get("regions", [])],
                    "inpaint_leg_matrix": first_inpaint.get("leg_matrix"),
                    "inpaint_cache_key": routes.get("cache_key", {}).get("fingerprint"),
                    "first_chain_seconds": first_seconds,
                    "first_chain_detection_counts": {
                        name: len((det_state.get("models", {}).get(name) or {}).get(
                            "outputs", [])) for name in
                        ("text-detector", "panel-detector", "bubble-segmenter")},
                    "first_chain_detection_status": {
                        name: (det_state.get("models", {}).get(name) or {}).get("status")
                        for name in
                        ("text-detector", "panel-detector", "bubble-segmenter")},
                    "tall": {"is_tall": det_state.get("is_tall"),
                             "windows": det_state.get("windows", []),
                             "segmenter_call_sizes": [row["size"] for row in segment_calls],
                             "full_page_segmenter_calls": len(tall_full_page_segmenter_calls)},
                    "route_counts": {
                        route: sum(1 for region in routes.get("regions", [])
                                   if region.get("route_taken") == route)
                        for route in sorted({region.get("route_taken")
                                             for region in routes.get("regions", [])})},
                    "paddle_rec_calls": sum(1 for row in CALLS[ACTIVE_CASE]
                                             if row["method"] == "paddle_rec"),
                    "paddle_det_calls": sum(1 for row in CALLS[ACTIVE_CASE]
                                             if row["method"] == "paddle_det"),
                    "paddle_refinement_calls": refinement_calls,
                    "paddle_refined_regions": refined_regions,
                    "second_run": {"ocr_cache_hit": ocr_second.get("cache_hit"),
                                   "inpaint_cache_hit": inpaint_second.get("cache_hit"),
                                   "detection_capture_fingerprint_unchanged": (
                                       pipe._cache["detections"][page].get(
                                           "capture_fingerprint") ==
                                       det_state.get("capture_fingerprint")),
                                   "model_calls_delta": 0,
                                   "detection_regions": len(det_second.get("regions", []))},
                    "evidence_files": {
                        "detection": detection_file.name,
                        "ocr": ocr_file.name,
                        "routes": route_file.name},
                })
            del pipe._render_assignments[page]

        # Keep only compact snapshots from this disposable chapter.
        for page in pipe.pages:
            del page
        del pipe
        gc.collect()

    models = {"paddle_det": {"path": str(PADDLE_DET),
                              "bytes": PADDLE_DET.stat().st_size,
                              "sha256": file_sha256(PADDLE_DET)},
              "paddle_rec": {"path": str(PADDLE_REC),
                              "bytes": PADDLE_REC.stat().st_size,
                              "sha256": file_sha256(PADDLE_REC)}}
    summary = {
        "commit": __import__("subprocess").run(
            ["git", "rev-parse", "HEAD"], cwd=REPO, text=True,
            capture_output=True, check=True).stdout.strip(),
        "mode": "real-model Studio pipeline; no fake ONNX sessions",
        "chain": "Pipeline.process_page(translate=False), then unchanged-stage replay",
        "inputs": {"regular_demo": {"file": "p001.jpg", "sha256": file_sha256(regular_copy)},
                   "a1_tall_stack": tall_info,
                   "free_text_control": control_info},
        "models": models,
        "presets": PRESETS,
        "cases": case_records,
        "model_calls": CALLS,
    }
    summary_path = (HERE / "real_model_refinement_probe.json" if refinement_only
                    else HERE / "real_model_studio_summary.json")
    save_json(summary_path, summary)
    print(json.dumps({"status": "passed", "commit": summary["commit"],
                      "cases": [{"case": item["case"], "preset": item["preset"],
                                 "ocr_regions": len(item["ocr_regions"]),
                                 "routes": item["route_counts"],
                                 "paddle_refinement_calls": len(
                                     item["paddle_refinement_calls"]),
                                 "second_run": item["second_run"]}
                                for item in case_records]},
                     indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
