from __future__ import annotations

import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path

import numpy as np
from PIL import Image

TOOLS_DIR = Path(__file__).resolve().parent


def load_module(name: str):
    spec = importlib.util.spec_from_file_location(name, TOOLS_DIR / f"{name}.py")
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load {name}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


generator = load_module("generate_masks_faithful")
emitter = load_module("emit_corpus_outputs")


class GenerateMasksFaithfulTest(unittest.TestCase):
    def test_fragment_merge_matches_horizontal_and_vertical_rules(self):
        lines = [
            generator.TextLine((0, 0, 10, 8), 0.5),
            generator.TextLine((15, 0, 25, 8), 0.7),
            generator.TextLine((50, 0, 58, 10), 0.6),
            generator.TextLine((50, 15, 58, 25), 0.8),
        ]
        merged = generator.merge_line_fragments(lines)
        self.assertEqual([line.bbox for line in merged], [(0, 0, 25, 8), (50, 0, 58, 25)])
        self.assertEqual([line.score for line in merged], [0.7, 0.8])

    def test_db_postprocess_filters_noise_and_merges_fragments(self):
        probability = np.zeros((40, 60), dtype=np.float32)
        probability[5:10, 5:10] = 0.9
        probability[5:10, 13:18] = 0.8
        probability[30, 30] = 1.0
        lines = generator.db_postprocess(probability)
        self.assertEqual(len(lines), 1)
        self.assertEqual(lines[0].bbox, (5, 5, 17, 9))

    def test_kotlin_round_to_int_and_centered_crop_round_half_up(self):
        self.assertEqual(generator.kotlin_round_to_int(2.5), 3)
        self.assertEqual(generator.kotlin_round_to_int(-2.5), -2)
        self.assertEqual(generator.centered_report_crop([(0, 0, 5, 5)], 20, 20), (0, 0, 20, 20))
        self.assertEqual(generator.centered_report_crop([(512, 512, 517, 517)], 1024, 1024), (259, 259, 771, 771))

    def test_paddle_dimensions_use_kotlin_float_rounding(self):
        self.assertEqual(generator.paddle_resized_dimensions(1472, 1471), (736, 736))
        self.assertEqual(generator.paddle_resized_dimensions(736, 1), (736, 1))
        with self.assertRaisesRegex(ValueError, "invalid crop dimensions"):
            generator.paddle_resized_dimensions(0, 1)

    def test_db_max_candidates_counts_only_min_area_components(self):
        probability = np.zeros((30, 60), dtype=np.float32)
        probability[1, 1] = 1.0  # ignored noise does not consume maxCandidates
        probability[5:9, 5:9] = np.float32(0.9)
        probability[20:24, 40:44] = np.float32(0.8)
        lines = generator.db_postprocess(probability, max_candidates=1)
        self.assertEqual([line.bbox for line in lines], [(5, 5, 8, 8)])
        self.assertAlmostEqual(lines[0].score, float(np.float32(0.9)), places=6)

    def test_independent_identity_is_stable(self):
        with tempfile.TemporaryDirectory() as temporary:
            source = Path(temporary) / "page-007.jpg"
            source.touch()
            self.assertEqual(generator.source_page_id(source, "real_"), "real_007")
            identities = [f"{generator.source_page_id(source, 'real_')}__ft_{index:03d}" for index in (1, 2)]
            self.assertEqual(identities, ["real_007__ft_001", "real_007__ft_002"])


class EmitCorpusOutputsValidationTest(unittest.TestCase):
    def write_generation_report(self, root: Path, identities: list[str], failures: list[dict] | None = None) -> None:
        failures = failures or []
        report = {
            "sample_count": len(identities),
            "failure_count": len(failures),
            "samples": identities,
            "failures": failures,
        }
        (root / "generation_report.json").write_text(json.dumps(report), encoding="utf-8")

    def make_sample(self, root: Path, identity: str, *, fallback_used: bool = False) -> None:
        sample = root / identity
        sample.mkdir()
        Image.new("RGB", (512, 512), "white").save(sample / "page.jpg")
        mask = np.zeros((512, 512), dtype=np.uint8)
        mask[10:20, 10:20] = 255
        Image.fromarray(mask, mode="L").save(sample / "mask.png")
        manifest = {
            "identity": identity,
            "page": identity,
            "source_page": identity[:8],
            "paddle_line_count": 1,
            "fallback_used": fallback_used,
        }
        (sample / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")

    def test_validation_accepts_complete_multi_page_corpus(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            identities = ["real_001__ft_001", "real_002__ft_001"]
            for identity in identities:
                self.make_sample(root, identity)
            self.write_generation_report(root, identities)
            samples = emitter.validate_corpus(root, minimum_samples=2)
            self.assertEqual([path.name for path, _ in samples], ["real_001__ft_001", "real_002__ft_001"])

    def test_validation_rejects_hidden_fallback(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            identities = ["real_001__ft_001", "real_002__ft_001"]
            self.make_sample(root, identities[0], fallback_used=True)
            self.make_sample(root, identities[1])
            self.write_generation_report(root, identities)
            with self.assertRaisesRegex(ValueError, "validation failed"):
                emitter.validate_corpus(root, minimum_samples=2)

    def test_validation_rejects_missing_or_failed_generation_report(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            identities = ["real_001__ft_001", "real_002__ft_001"]
            for identity in identities:
                self.make_sample(root, identity)
            with self.assertRaisesRegex(ValueError, "validation failed"):
                emitter.validate_corpus(root, minimum_samples=2)
            self.write_generation_report(root, identities, [{"stage": "paddle", "error": "failed"}])
            with self.assertRaisesRegex(ValueError, "validation failed"):
                emitter.validate_corpus(root, minimum_samples=2)

    def test_validation_rejects_report_identity_mismatch(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            identities = ["real_001__ft_001", "real_002__ft_001"]
            for identity in identities:
                self.make_sample(root, identity)
            self.write_generation_report(root, list(reversed(identities)))
            with self.assertRaisesRegex(ValueError, "validation failed"):
                emitter.validate_corpus(root, minimum_samples=2)


class ProductionSub512PathTest(unittest.TestCase):
    def test_fixture_spec_is_deterministic_and_exact(self):
        self.assertEqual(
            emitter.SUB512_FIXTURE_SPECS,
            (
                ("real_001__ft_001", 300),
                ("real_002__ft_001", 400),
                ("real_008__ft_003", 480),
                ("real_024__ft_001", 511),
            ),
        )

    def test_center_crop_uses_production_odd_remainder_rule(self):
        source = np.arange(512 * 512, dtype=np.int32).reshape(512, 512)
        cropped = emitter.center_crop(source, 511)
        np.testing.assert_array_equal(cropped, source[:511, :511])

    def test_static_input_is_center_background_pad_with_zero_mask(self):
        side = 300
        page = np.zeros((side, side, 3), dtype=np.uint8)
        page[..., 0] = 17
        page[..., 1] = 83
        page[..., 2] = 149
        mask = np.zeros((side, side), dtype=np.uint8)
        mask[140:160, 140:160] = 255

        dynamic_image, dynamic_mask, static_image, static_mask = emitter.production_inputs(page, mask)
        offset = (512 - side) // 2

        self.assertEqual(dynamic_image.shape, (1, 3, 304, 304))
        self.assertEqual(dynamic_mask.shape, (1, 1, 304, 304))
        self.assertEqual(static_image.shape, (1, 3, 512, 512))
        self.assertEqual(static_mask.shape, (1, 1, 512, 512))
        self.assertTrue(np.all(static_mask[:, :, :offset, :] == 0.0))
        self.assertTrue(np.all(static_mask[:, :, :, :offset] == 0.0))
        np.testing.assert_array_equal(
            static_image[:, :, offset : offset + side, offset : offset + side],
            dynamic_image[:, :, :side, :side],
        )
        expected_background = page[0, 0].astype(np.float32) / 127.5 - 1.0
        np.testing.assert_allclose(static_image[0, :, 0, 0], expected_background, rtol=0, atol=1e-7)

    def test_background_is_real_channel_median_not_grayscale_or_fake_color(self):
        page = np.empty((20, 20, 3), dtype=np.uint8)
        page[...] = (11, 77, 203)
        mask = np.zeros((20, 20), dtype=np.uint8)
        mask[8:12, 8:12] = 255
        np.testing.assert_array_equal(emitter.production_background(page, mask), [11, 77, 203])

    def test_crop_back_recovers_supported_native_sizes_exactly(self):
        padded = np.arange(512 * 512 * 3, dtype=np.int32).reshape(512, 512, 3)
        for side in (300, 400, 480, 511):
            offset = (512 - side) // 2
            np.testing.assert_array_equal(
                emitter.center_crop(padded, side),
                padded[offset : offset + side, offset : offset + side],
            )


if __name__ == "__main__":
    unittest.main()
