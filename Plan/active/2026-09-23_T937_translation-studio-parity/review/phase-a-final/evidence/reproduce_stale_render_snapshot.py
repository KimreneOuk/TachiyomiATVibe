"""Focused reproduction for the render/OCR stale-snapshot review finding."""
from __future__ import annotations

import json
import sys
import tempfile
from contextlib import nullcontext
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[6]
sys.path.insert(0, str(ROOT / "tools" / "translation_studio"))

import pipeline as studio_pipeline  # noqa: E402


def main() -> None:
    page = "p001.png"
    stale = {
        "ocr_fingerprint": "ocr-before-validation",
        "regions": [{"id": "stale-region", "artifact_id": "old", "box": [1, 2, 20, 30]}],
    }
    fresh = {
        "ocr_fingerprint": "ocr-after-validation",
        "regions": [{"id": "fresh-region", "artifact_id": "new", "box": [60, 70, 100, 110]}],
    }

    with tempfile.TemporaryDirectory(prefix="t937-stale-render-") as temporary:
        chapter = Path(temporary)
        Image.new("RGB", (160, 120), "white").save(chapter / page)
        pipe = studio_pipeline.Pipeline()
        pipe.chapter = chapter
        pipe.pages = [page]
        pipe.settings = dict(studio_pipeline.DEFAULT_SETTINGS)
        pipe._cache = {
            "detections": {page: {"decisions": {"fingerprint": "new-decision"}}},
            "ocr": {page: stale},
            "translations": {page: {}},
        }
        pipe._render_dirty = {page: True}
        pipe._render_assignments = {}
        pipe._render_times = {}
        pipe._page_fingerprints = {
            page: {"fingerprint": "repro-page", "width": 160, "height": 120}
        }
        pipe._dims = {}
        # Keep this focused repro at render_page's snapshot boundary; a real
        # page-operation scope would invalidate the intentionally seeded OCR
        # fixture before the method under test starts.
        pipe._page_operation_scope = lambda requested_page: nullcontext()

        def inpaint_with_upstream_validation(requested_page: str) -> dict:
            assert requested_page == page
            pipe._cache["ocr"][page] = fresh
            return {"cache_fingerprint": "inpaint-after-validation"}

        pipe.inpaint_page = inpaint_with_upstream_validation
        pipe.inpainted_image = lambda requested_page: Image.open(
            chapter / requested_page
        ).copy()
        rendered_region_ids: list[str] = []

        def capture_regions(image, regions, translations, settings, pipeline, **kwargs):
            rendered_region_ids.extend(region["id"] for region in regions)
            return len(regions)

        previous_render_regions = studio_pipeline.render_regions
        studio_pipeline.render_regions = capture_regions
        try:
            result = pipe.render_page(page, force=True)
        finally:
            studio_pipeline.render_regions = previous_render_regions

        saved = json.loads((chapter / ".studio" / "render" / "p001.json").read_text(encoding="utf-8"))
        observed = {
            "fresh_ocr_in_cache_after_inpaint_validation": pipe._cache["ocr"][page]["ocr_fingerprint"],
            "rendered_region_ids": rendered_region_ids,
            "render_cache_ocr_fingerprint": saved["cache_key"]["ocr_fingerprint"],
            "render_cache_inpaint_fingerprint": saved["cache_key"]["inpaint_fingerprint"],
            "render_cache_fingerprint": result["cache_fingerprint"],
            "reproduced_stale_snapshot": (
                pipe._cache["ocr"][page]["ocr_fingerprint"] == fresh["ocr_fingerprint"]
                and rendered_region_ids == ["stale-region"]
                and saved["cache_key"]["ocr_fingerprint"] == stale["ocr_fingerprint"]
                and saved["cache_key"]["inpaint_fingerprint"] == "inpaint-after-validation"
            ),
        }
        if not observed["reproduced_stale_snapshot"]:
            raise AssertionError(json.dumps(observed, indent=2))
        output_path = Path(__file__).with_name("stale_render_snapshot_repro.json")
        output_path.write_text(json.dumps(observed, indent=2), encoding="utf-8")
        print(json.dumps(observed, indent=2))


if __name__ == "__main__":
    main()
