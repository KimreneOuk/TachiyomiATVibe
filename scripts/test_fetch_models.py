import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from fetch_models import FetchError, MANIFEST_PATH, load_manifest, select_models


class ModelProvisioningTest(unittest.TestCase):
    def setUp(self):
        self.files = load_manifest(MANIFEST_PATH)

    def test_manifest_covers_all_packaged_paddle_litert_assets_and_inpainters(self):
        required = {
            f"ocr/paddle-v6-small/litert/manga_rec_{width}_fp16.tflite"
            for width in (160, 320, 480, 640, 1600)
        } | {
            "ocr/paddle-v6-small/litert/manga_det_960_fp16.tflite",
            "ocr/paddle-v6-small/litert/PP-OCRv6_small_rec.txt",
            "inpainting/aot_512_regen_gap_fp16.tflite",
            "inpainting/manga_lama_fused_fp16.tflite",
        }
        entries = {entry["path"]: entry for entry in self.files}
        self.assertFalse(required - entries.keys(), "fresh builds must include optimized OCR and both inpainters")
        for path in required:
            self.assertNotIn("convert", entries[path], "optimized binaries must not require local reconversion")

    def test_paddle_group_includes_onnx_fallbacks_and_all_litert_buckets(self):
        selected = select_models(self.files, ["paddle"])
        self.assertEqual(10, len(selected))
        self.assertTrue(all(entry["path"].startswith("ocr/paddle-v6-small/") for entry in selected))
        self.assertIn("ocr/paddle-v6-small/inference.onnx", {entry["path"] for entry in selected})
        self.assertIn("ocr/paddle-v6-small/det/inference.onnx", {entry["path"] for entry in selected})

    def test_three_family_selection_excludes_unrelated_conversions(self):
        selected = select_models(self.files, ["paddle", "aot", "lama", "paddle"])
        self.assertEqual(12, len(selected))
        self.assertEqual(len(selected), len({entry["path"] for entry in selected}))
        self.assertTrue(all("convert" not in entry for entry in selected))

    def test_default_still_provisions_full_inventory(self):
        self.assertEqual(self.files, select_models(self.files, None))

    def test_missing_requested_family_is_an_error(self):
        with self.assertRaises(FetchError):
            select_models([entry for entry in self.files if not entry["path"].startswith("ocr/")], ["paddle"])


if __name__ == "__main__":
    unittest.main()
