"""Focused, model-free tests for stable translation identity and migration."""
from __future__ import annotations

import copy
from contextlib import nullcontext
import json
import threading
import tempfile
import unittest
from types import SimpleNamespace
from pathlib import Path

from PIL import Image

import pipeline


def make_region(region_id: str, artifact_id: str, box: list[int], text: str,
                stable_id: str | None = None) -> dict:
    region = {
        "id": region_id,
        "artifact_id": artifact_id,
        "label": 1,
        "score": 0.9,
        "box": box,
        "ocr_box": box,
        "parent_box": None,
        "text": text,
        "engine": "mangaocr",
    }
    if stable_id is not None:
        region["stable_id"] = stable_id
    region["ocr_fingerprint"] = pipeline._region_fingerprint(region)
    return region


class StableTranslationMappingTests(unittest.TestCase):
    def test_block_ids_use_android_sort_and_reuse_persisted_ids(self) -> None:
        regions = [
            make_region("r00", "dt-lower", [100, 200, 130, 230], "lower"),
            make_region("r01", "dt-upper", [40, 10, 70, 35], "upper"),
        ]
        pipeline._assign_stable_block_ids(regions)
        self.assertEqual({region["artifact_id"]: region["stable_id"]
                          for region in regions},
                         {"dt-upper": "b0", "dt-lower": "b1"})

        inserted_before_persisted = [
            make_region("r00", "dt-new", [2, 1, 12, 11], "new block"),
            make_region("r01", "dt-existing", [20, 80, 35, 95],
                        "existing block", "b0"),
        ]
        pipeline._assign_stable_block_ids(inserted_before_persisted)
        self.assertEqual(inserted_before_persisted[0]["stable_id"], "b1")
        self.assertEqual(inserted_before_persisted[1]["stable_id"], "b0")
        carried = {"b0": {"text": "existing auto", "origin": "auto",
                           "ocr_fingerprint": "old"}}
        pipeline._carry_prune_translation_page(carried, inserted_before_persisted)
        self.assertEqual(carried["b0"]["region"]["text"], "existing block")

        previous = [make_region("r02", "dt-upper", [40, 10, 70, 35], "upper", "b7")]
        refreshed = [make_region("r00", "dt-upper", [41, 10, 71, 35], "upper")]
        pipeline._assign_stable_block_ids(refreshed, previous)
        self.assertEqual(refreshed[0]["stable_id"], "b7")

    def test_region_fingerprint_covers_translation_identity_fields(self) -> None:
        base = make_region("r00", "dt-one", [10, 10, 30, 30], "source", "b0")
        base["parent_box"] = [0, 0, 40, 40]
        base["segmenter_assignment"] = {"mask_ref": "mask-1"}
        fingerprint = pipeline._region_fingerprint(base)
        mutations = (
            {"text": "edited source"},
            {"box": [10, 10, 31, 30]},
            {"parent_box": [0, 0, 41, 40]},
            {"label": 2},
            {"score": 0.8},
            {"segmenter_assignment": {"mask_ref": "mask-2"}},
        )
        for mutation in mutations:
            with self.subTest(mutation=mutation):
                changed = {**base, **mutation}
                self.assertNotEqual(pipeline._region_fingerprint(changed), fingerprint)

    def test_ocr_refresh_keeps_exact_user_edit_remaps_auto_and_prunes(self) -> None:
        page = "p001.jpg"
        old_user = make_region("r00", "dt-user", [10, 10, 30, 30], "same", "b0")
        old_auto = make_region("r01", "dt-auto", [50, 10, 80, 35], "before", "b1")
        current_user = make_region("r00", "dt-user", [10, 10, 30, 30], "same")
        current_auto = make_region("r01", "dt-auto", [50, 10, 80, 35], "after")
        current_regions = [current_user, current_auto]
        translations = {
            "b0": {"text": "manual text", "origin": "user",
                   "ocr_fingerprint": current_user["ocr_fingerprint"],
                   "region": pipeline._region_snapshot(old_user)},
            "b1": {"text": "automatic text", "origin": "auto",
                   "ocr_fingerprint": old_auto["ocr_fingerprint"],
                   "region": pipeline._region_snapshot(old_auto)},
            "b9": {"text": "orphan text", "origin": "user",
                   "ocr_fingerprint": "removed-fingerprint",
                   "region": {"id": "r09", "box": [1, 1, 5, 5]}},
            "_schema": pipeline._TRANSLATION_SCHEMA,
        }
        cache_fingerprint = pipeline._stable_fingerprint({"engine": "mangaocr"})
        saved = []
        fake = SimpleNamespace(
            lock=threading.RLock(),
            settings={"conf": 0.6},
            _cache={"ocr": {page: {"regions": [old_user, old_auto],
                                    "engine": "mangaocr"}},
                    "translations": {page: translations}},
            _render_dirty={},
            _crop_cache={},
            _load_times={},
            log=lambda *_args: None,
            _save_json=lambda name, value: saved.append((name, copy.deepcopy(value))),
            _page_operation_scope=lambda _page: nullcontext(None),
            _ocr_engine_name=lambda: "mangaocr",
            _ocr_cache_inputs=lambda *_args: {"engine": "mangaocr"},
            detect_page=lambda *_args: {
                "regions": copy.deepcopy(current_regions),
                "decision_fingerprint": "detection-refresh",
            },
            page_image=lambda _page: Image.new("RGB", (100, 100), "white"),
            _recognize_crops=lambda crops, **_kwargs: (
                [{"text": region["text"], "raw_text": region["text"],
                  "confidence": 0.99, "lines": [], "engine": "mangaocr"}
                 for region in current_regions], {}, 2),
            _current_page_fingerprint=lambda _page: "page-fingerprint",
        )
        fake._prepare_region_identity = lambda p, rows, previous=None: (
            pipeline.Pipeline._prepare_region_identity(fake, p, rows, previous))
        fake._refresh_page_ocr_fingerprint = pipeline.Pipeline._refresh_page_ocr_fingerprint
        fake.carry_translations = lambda p: pipeline.Pipeline.carry_translations(fake, p)

        result = pipeline.Pipeline.ocr_page(fake, page, force=True)

        self.assertFalse(result["cache_hit"])
        self.assertEqual(len(result["pruned"]), 1)
        self.assertEqual(result["pruned"][0]["text"], "orphan text")
        page_cache = fake._cache["translations"][page]
        self.assertEqual(page_cache["b0"]["origin"], "user")
        self.assertEqual(page_cache["b0"]["text"], "manual text")
        self.assertEqual(page_cache["b1"]["origin"], "auto")
        self.assertEqual(page_cache["b1"]["text"], "automatic text")
        self.assertEqual(page_cache["b1"]["ocr_fingerprint"],
                         result["regions"][1]["ocr_fingerprint"])
        self.assertNotIn("b9", page_cache)
        self.assertEqual(page_cache["_pruned"][-1]["reason"], "migration-unmapped")
        self.assertTrue(any(name == "translations.json" for name, _ in saved))

        page_cache["b77"] = {"text": "late orphan", "origin": "auto",
                             "ocr_fingerprint": "stale"}
        cached_result = pipeline.Pipeline.ocr_page(fake, page, force=False)
        self.assertTrue(cached_result["cache_hit"])
        self.assertEqual(cached_result["pruned"][0]["text"], "late orphan")
        self.assertNotIn("b77", page_cache)

    def test_user_edit_with_changed_fingerprint_is_pruned(self) -> None:
        current = make_region("r00", "dt-one", [10, 10, 30, 30], "new text", "b0")
        cache = {"b0": {"text": "manual", "origin": "user",
                        "ocr_fingerprint": "old-fingerprint"}}
        pruned = pipeline._carry_prune_translation_page(cache, [current])
        self.assertEqual(len(pruned), 1)
        self.assertEqual(pruned[0]["reason"], "user-edit-fingerprint-mismatch")
        self.assertEqual(pruned[0]["text"], "manual")
        self.assertNotIn("b0", cache)

    def test_legacy_ordinal_cache_migrates_by_old_position_and_fingerprint(self) -> None:
        first = make_region("r00", "dt-first", [10, 10, 30, 30], "first", "b3")
        second = make_region("r01", "dt-second", [50, 10, 80, 35], "second", "b5")
        cache = {
            "r00": "manual legacy translation",
            "r77": {"text": "fingerprint translation", "origin": "legacy",
                    "ocr_fingerprint": second["ocr_fingerprint"]},
            "r99": "unmapped legacy text",
        }
        changed = pipeline._migrate_translation_page(
            cache, {"r00": "b3", "r01": "b5"}, [first, second])
        self.assertTrue(changed)
        self.assertNotIn("r00", cache)
        self.assertEqual(cache["b3"]["text"], "manual legacy translation")
        self.assertEqual(cache["b3"]["origin"], "legacy")
        self.assertEqual(cache["b3"]["ocr_fingerprint"], first["ocr_fingerprint"])
        self.assertEqual(cache["b5"]["text"], "fingerprint translation")
        self.assertEqual(cache["_pruned"][-1]["text"], "unmapped legacy text")
        self.assertEqual(cache["_pruned"][-1]["reason"], "migration-unmapped")

    def test_open_folders_persists_legacy_cache_migration(self) -> None:
        page = "p001.png"
        with tempfile.TemporaryDirectory(prefix="c2-translation-migration-") as temp:
            chapter = Path(temp)
            Image.new("RGB", (40, 30), "white").save(chapter / page)
            studio = chapter / ".studio"
            studio.mkdir()
            legacy_region = make_region("r00", "dt-first", [5, 5, 20, 20], "source")
            (studio / "ocr.json").write_text(json.dumps({page: {
                "engine": "mangaocr", "regions": [legacy_region],
            }}), encoding="utf-8")
            (studio / "translations.json").write_text(json.dumps({page: {
                "r00": "legacy translation",
            }}), encoding="utf-8")

            instance = pipeline.Pipeline()
            instance.record_recent = lambda *_args: []
            instance.get_recent = lambda: []
            instance.open_folders(str(chapter), None)

            migrated = json.loads((studio / "translations.json").read_text(encoding="utf-8"))[page]
            self.assertNotIn("r00", migrated)
            self.assertEqual(migrated["b0"]["text"], "legacy translation")
            self.assertEqual(migrated["b0"]["origin"], "legacy")
            self.assertEqual(migrated["b0"]["ocr_fingerprint"],
                             instance._cache["ocr"][page]["regions"][0]["ocr_fingerprint"])
            self.assertEqual(instance._cache["ocr"][page]["regions"][0]["stable_id"], "b0")


def main() -> int:
    suite = unittest.defaultTestLoader.loadTestsFromTestCase(
        StableTranslationMappingTests)
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    return 0 if result.wasSuccessful() else 1


if __name__ == "__main__":
    raise SystemExit(main())
