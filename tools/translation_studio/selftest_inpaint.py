"""Run both Android inpaint presets on both synthetic demo pages.

This intentionally uses the live Pipeline methods so detector, OCR, segmenter,
Paddle refinement, the Android port, and the persistent artifact writer share
the same path as the studio.
"""
from __future__ import annotations

import json
import shutil
from pathlib import Path

import numpy as np
from PIL import Image

from pipeline import Pipeline
from inpaint_android import inpaint_page_android

HERE = Path(__file__).resolve().parent
DEMO_CHAPTER = HERE / "demo_chapter"
EVIDENCE = (HERE.parents[1] / "Plan" / "active" /
            "2026-09-23_T937_translation-studio-parity" / "team" /
            "03-A2-inpaint" / "evidence")
B1_EVIDENCE = (HERE.parents[1] / "Plan" / "active" /
               "2026-09-23_T937_translation-studio-parity" / "team" /
               "10-B1-provenance" / "evidence" / "demo")
PRESETS = {
    "android-fast": {"inpaint_bubble_leg": "android-fill",
                     "inpaint_free_leg": "opencv"},
    "android-quality": {"inpaint_bubble_leg": "android-fill",
                         "inpaint_free_leg": "aot"},
}


def main() -> None:
    if not DEMO_CHAPTER.is_dir():
        raise SystemExit(f"demo chapter is missing: {DEMO_CHAPTER}")
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    B1_EVIDENCE.mkdir(parents=True, exist_ok=True)
    pipeline = Pipeline()
    pipeline.open_folders(str(DEMO_CHAPTER), None)

    # The demo folder is intentionally synthetic and starts without a .studio
    # cache in a clean checkout. Populate OCR exactly once before the variants.
    for page in pipeline.pages:
        print(f"[selftest] detect {page}", flush=True)
        pipeline.detect_page(page, force=True)
        print(f"[selftest] OCR {page}", flush=True)
        pipeline.ocr_page(page)

    summary = []
    cache_switch_check = None
    for preset, legs in PRESETS.items():
        pipeline.save_settings({"inpaint_engine": "android", **legs})
        for page in pipeline.pages:
            print(f"[selftest] {preset} {page}", flush=True)
            result = pipeline.inpaint_page(page, force=True, mode="QUALITY")
            mask = np.asarray(Image.open(result["mask_path"]).convert("L")) > 0
            if not mask.any():
                raise AssertionError(f"empty inpaint mask: {preset} {page}")
            records = result.get("provenance") or []
            missing = [r.get("id") for r in records if not r.get("route_taken")]
            if missing:
                raise AssertionError(f"regions without routes: {preset} {page}: {missing}")

            stem = Path(page).stem
            prefix = f"{stem}_{preset}"
            image_name = f"{prefix}_inpainted.png"
            mask_name = f"{prefix}_mask.png"
            route_name = f"{prefix}_routes.json"
            shutil.copy2(result["path"], EVIDENCE / image_name)
            shutil.copy2(result["mask_path"], EVIDENCE / mask_name)
            with open(result["provenance_path"], "r", encoding="utf-8") as src:
                provenance = json.load(src)
            b1_dir = B1_EVIDENCE / prefix
            crop_root = b1_dir / "crops"
            b1_dir.mkdir(parents=True, exist_ok=True)
            provenance_copy = json.loads(json.dumps(provenance))
            crop_triples = 0
            for record in provenance_copy.get("regions", []):
                crops = record.get("crops", {})
                if crops.get("status") != "ready":
                    continue
                region_dir = crop_root / str(record.get("id", "region"))
                region_dir.mkdir(parents=True, exist_ok=True)
                for kind in ("input", "output", "mask"):
                    entries = crops[kind]
                    runtime_entry = entries["exact"]
                    source_crop = DEMO_CHAPTER / Path(runtime_entry["path"])
                    if not source_crop.is_file():
                        raise AssertionError(f"missing persisted crop: {source_crop}")
                    evidence_crop = region_dir / f"{kind}.png"
                    shutil.copy2(source_crop, evidence_crop)
                    relative = evidence_crop.relative_to(b1_dir).as_posix()
                    for representation in ("debug", "exact"):
                        entry = entries[representation]
                        entry["runtime_path"] = entry["path"]
                        entry["path"] = relative
                    crop_triples += 1
            provenance_copy["evidence"] = {
                "crop_root": "crops/",
                "crop_triples": crop_triples,
            }
            with open(b1_dir / "provenance.json", "w", encoding="utf-8") as f:
                json.dump(provenance_copy, f, indent=2, ensure_ascii=False)
            route_dump = {
                "page": page,
                "preset": preset,
                "engine": result["engine"],
                "leg_matrix": result["leg_matrix"],
                "mask_pixels": int(mask.sum()),
                "inpainted_png": image_name,
                "mask_png": mask_name,
                **provenance,
            }
            with open(EVIDENCE / route_name, "w", encoding="utf-8") as f:
                json.dump(route_dump, f, indent=2, ensure_ascii=False)
            summary.append({"page": page, "preset": preset,
                            "mask_pixels": int(mask.sum()),
                            "regions": len(records),
                            "routes": sorted({r["route_taken"] for r in records}),
                            "inpainted_png": image_name,
                            "mask_png": mask_name,
                            "routes_json": route_name,
                            "b1_provenance": (b1_dir / "provenance.json").relative_to(
                                B1_EVIDENCE).as_posix(),
                            "b1_crop_triples": crop_triples})
            if preset == "android-fast" and page == pipeline.pages[0]:
                with open(result["provenance_path"], "r", encoding="utf-8") as f:
                    before = json.load(f)["cache_key"]
                pipeline.save_settings({
                    "inpaint_engine": "android",
                    "inpaint_bubble_leg": "android-fill",
                    "inpaint_free_leg": "aot",
                })
                switched = pipeline.inpaint_page(page, force=False, mode="QUALITY")
                with open(switched["provenance_path"], "r", encoding="utf-8") as f:
                    after = json.load(f)["cache_key"]
                if before == after or after.get("leg_matrix", {}).get("free_text") != "aot":
                    raise AssertionError(f"variant cache did not switch: {before} -> {after}")
                cache_switch_check = {"before": before, "after": after,
                                      "recomputed_infer_ms": switched["infer_ms"]}
                pipeline.save_settings({
                    "inpaint_engine": "android",
                    "inpaint_bubble_leg": "android-fill",
                    "inpaint_free_leg": "opencv",
                })

    # The detected demo OCR is bubble-only. Exercise the free-text route matrix
    # and the experimental bubble fills with one bounded synthetic box on p001.
    probe_page = "p001.jpg"
    original = pipeline.page_image(probe_page)
    width, height = original.size
    cx, cy = width // 2, height // 2
    box = [max(0, cx - 32), max(0, cy - 16),
           min(width, cx + 32), min(height, cy + 16)]
    aot = pipeline._aot_inpainter()
    probes = [
        ("free-opencv", {"label": 2}, [], "android-fill", "opencv"),
        ("free-aot", {"label": 2}, [], "android-fill", "aot"),
        ("free-aot-dynamic", {"label": 2}, [], "android-fill", "aot"),
        ("free-pushpull", {"label": 2}, [], "android-fill", "pushpull"),
        ("bubble-opencv", {"label": 1}, [[0, 0.99, max(0, cx - 120),
                                             max(0, cy - 100), min(width, cx + 120),
                                             min(height, cy + 100)]], "opencv", "opencv"),
        ("bubble-aot", {"label": 1}, [[0, 0.99, max(0, cx - 120),
                                         max(0, cy - 100), min(width, cx + 120),
                                         min(height, cy + 100)]], "aot", "opencv"),
        ("bubble-pushpull", {"label": 1}, [[0, 0.99, max(0, cx - 120),
                                              max(0, cy - 100), min(width, cx + 120),
                                              min(height, cy + 100)]], "pushpull", "opencv"),
    ]
    for probe, region_options, detections, bubble_leg, free_leg in probes:
        regions = [{"id": f"probe-{probe}", "box": box,
                    "text": "probe", **region_options}]
        prior_guard = getattr(aot, "_guard_rejects", None) if "dynamic" in probe else None
        if prior_guard is not None:
            # Force the fixed-candidate rejection only in this probe to verify
            # that the dynamic session and second cascade step actually run.
            aot._guard_rejects = lambda candidate, mask: True
        try:
            cleaned, mask, records, stats = inpaint_page_android(
                original, regions, raw_detections=detections, seg_masks=[],
                bubble_leg=bubble_leg, free_leg=free_leg, paddle_det=None,
                aot=aot if "aot" in probe else None)
        finally:
            if prior_guard is not None:
                aot._guard_rejects = prior_guard
        if not mask.any() or any(not r.get("route_taken") for r in records):
            raise AssertionError(f"probe did not route cleanly: {probe}")
        if "dynamic" in probe and "aot-dynamic" not in records[0]["route_taken"]:
            raise AssertionError(f"dynamic AOT session was not reached: {records[0]['route_taken']}")
        prefix = f"p001_probe-{probe}"
        image_name = f"{prefix}_inpainted.png"
        mask_name = f"{prefix}_mask.png"
        route_name = f"{prefix}_routes.json"
        cleaned.save(EVIDENCE / image_name)
        Image.fromarray(mask.astype(np.uint8) * 255).save(EVIDENCE / mask_name)
        with open(EVIDENCE / route_name, "w", encoding="utf-8") as f:
            json.dump({"page": probe_page, "probe": probe,
                       "leg_matrix": {"bubble": bubble_leg, "free_text": free_leg},
                       "stats": stats, "regions": records,
                       "inpainted_png": image_name, "mask_png": mask_name},
                      f, indent=2, ensure_ascii=False)
        summary.append({"page": probe_page, "probe": probe,
                        "routes": [r["route_taken"] for r in records],
                        "mask_pixels": int(mask.sum()),
                        "inpainted_png": image_name, "mask_png": mask_name,
                        "routes_json": route_name})

    with open(EVIDENCE / "selftest_summary.json", "w", encoding="utf-8") as f:
        json.dump({"cases": summary, "cache_switch_check": cache_switch_check},
                  f, indent=2, ensure_ascii=False)
    print(json.dumps(summary, indent=2, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
