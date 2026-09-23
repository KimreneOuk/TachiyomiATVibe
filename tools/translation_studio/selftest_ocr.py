"""Focused OCR parity checks that do not require downloaded ONNX models."""
from __future__ import annotations

import sys
import unittest
from pathlib import Path
from unittest import mock

import numpy as np
from PIL import Image, ImageDraw

STUDIO_DIR = Path(__file__).resolve().parent
TOOLS_DIR = STUDIO_DIR.parent
sys.path.insert(0, str(STUDIO_DIR))
sys.path.insert(0, str(TOOLS_DIR / "mangaocr_lab"))

import paddle_ocr  # noqa: E402
import pipeline as studio_pipeline  # noqa: E402
from boxgeom import Box  # noqa: E402
from lab import decode_common as manga_decode  # noqa: E402
from lab.preprocessing import preprocess as manga_preprocess  # noqa: E402


class FakeRecSession:
    def __init__(self, fail_multi: bool = False):
        self.fail_multi = fail_multi
        self.calls: list[np.ndarray] = []

    def run(self, _outputs, feed):
        x = feed["input"]
        self.calls.append(x.copy())
        if self.fail_multi and x.shape[0] > 1:
            raise MemoryError("simulated batch allocation failure")
        logits = np.zeros((x.shape[0], 2, 3), dtype=np.float32)
        for row, value in enumerate(x[:, 0, 0, 0]):
            token = 1 if value < -0.4 else 2
            logits[row, 0, token] = 1.0
            logits[row, 1, 0] = 1.0
        return [logits]


def fake_paddle_rec(session: FakeRecSession) -> paddle_ocr.PaddleRec:
    rec = paddle_ocr.PaddleRec.__new__(paddle_ocr.PaddleRec)
    rec.dictionary = ["A", "B"]
    rec.input_name = "input"
    rec.sess = session
    rec.last_batch_trace = []
    return rec


class FixedDetector:
    def __init__(self, lines):
        self.lines = lines

    def detect_lines(self, _crop):
        return self.lines


class RecordingRec:
    def __init__(self, results):
        self.results = results
        self.calls = []

    def recognize_batch(self, crops, max_batch=8):
        self.calls.append([crop.size for crop in crops])
        if callable(self.results):
            return self.results(crops, max_batch)
        return list(self.results)


class OcrParitySelfTest(unittest.TestCase):
    def test_paddle_width_buckets_gray_padding_and_order(self):
        session = FakeRecSession()
        rec = fake_paddle_rec(session)
        crops = [
            Image.new("RGB", (2, 48), (32, 32, 32)),
            Image.new("RGB", (700, 48), (224, 224, 224)),
            Image.new("RGB", (2, 48), (128, 128, 128)),
        ]
        try:
            result = rec.recognize_batch(crops, max_batch=8)
        finally:
            for crop in crops:
                crop.close()

        self.assertEqual([row[0] for row in result], ["A", "B", "B"])
        self.assertEqual([call.shape for call in session.calls], [
            (2, 3, 48, 640), (1, 3, 48, 1600),
        ])
        self.assertAlmostEqual(float(session.calls[0][0, 0, 0, 0]), -0.749, places=2)
        self.assertAlmostEqual(float(session.calls[0][0, 0, 0, 10]), 1 / 255, places=5)

    def test_paddle_batch_failure_retries_ordered_single_crops(self):
        session = FakeRecSession(fail_multi=True)
        rec = fake_paddle_rec(session)
        crops = [Image.new("RGB", (2, 48), color) for color in
                 ((32, 32, 32), (224, 224, 224))]
        try:
            result = rec.recognize_batch(crops, max_batch=4)
        finally:
            for crop in crops:
                crop.close()

        self.assertEqual([row[0] for row in result], ["A", "B"])
        self.assertEqual([call.shape[0] for call in session.calls], [2, 1, 1])
        self.assertTrue(rec.last_batch_trace[0]["fallback"])
        self.assertEqual(paddle_ocr.PaddleRec._batch_limit(3), 2)
        self.assertEqual(paddle_ocr.PaddleRec._batch_limit(32), 8)

    def test_paddle_batch_plans_only_android_batch_sizes(self):
        session = FakeRecSession()
        rec = fake_paddle_rec(session)
        crops = [Image.new("RGB", (2, 48), (32, 32, 32)) for _ in range(6)]
        try:
            result = rec.recognize_batch(crops, max_batch=8)
        finally:
            for crop in crops:
                crop.close()
        self.assertEqual(len(result), 6)
        self.assertEqual([call.shape[0] for call in session.calls], [4, 2])

    def test_vertical_detector_lines_split_and_rotate_glyphs(self):
        crop = Image.new("RGB", (50, 120), "white")
        draw = ImageDraw.Draw(crop)
        draw.rectangle((5, 10, 25, 25), fill="black")
        draw.rectangle((5, 50, 25, 65), fill="black")
        plan = paddle_ocr.plan_region(
            FixedDetector([[5, 10, 25, 100, 0.9]]), crop,
            vertical_fallback=True, language="ja")
        try:
            self.assertEqual(len(plan.leaves), 2)
            self.assertEqual([leaf.size for leaf in plan.leaves], [(15, 20), (15, 20)])
            self.assertEqual(plan.groups[0].leaf_indices, [0, 1])
        finally:
            for leaf in plan.leaves:
                leaf.close()
            crop.close()

    def test_ink_gap_fallback_reads_columns_right_to_left(self):
        crop = Image.new("RGB", (60, 100), "white")
        draw = ImageDraw.Draw(crop)
        draw.rectangle((2, 10, 16, 90), fill=(20, 20, 20))
        draw.rectangle((30, 10, 44, 90), fill=(80, 80, 80))
        plan = paddle_ocr.plan_region(None, crop, vertical_fallback=True,
                                      language="ja", force_heuristic=True)
        try:
            self.assertEqual([leaf.size for leaf in plan.leaves], [(100, 14), (100, 14)])
            means = [float(np.asarray(leaf.convert("L")).mean()) for leaf in plan.leaves]
            self.assertGreater(means[0], means[1])
        finally:
            for leaf in plan.leaves:
                leaf.close()
            crop.close()

    def test_empty_vertical_result_rereads_unpadded_roi(self):
        det = FixedDetector([])
        rec = RecordingRec([("", 1.0)])
        rec.results = lambda crops, _batch: (
            [("", 1.0)] if len(rec.calls) == 1 else [("retry", 1.0)])
        padded = Image.new("RGB", (54, 104), "white")
        unpadded = Image.new("RGB", (30, 80), "white")
        calls = []

        def fallback_crop(_index):
            calls.append(True)
            return unpadded.copy()

        try:
            result = paddle_ocr.recognize_regions(
                det, rec, [padded], max_batch=4,
                region_boxes=[(0, 0, 30, 80)], language="ja",
                unpadded_crop_factory=fallback_crop)
        finally:
            padded.close()
            unpadded.close()

        self.assertEqual(result[0]["text"], "retry")
        self.assertEqual(calls, [True])
        self.assertEqual(rec.calls, [[(104, 54)], [(80, 30)]])

    def test_webtoon_mode_does_not_use_vertical_heuristic(self):
        rec = RecordingRec([("plain", 1.0)])
        crop = Image.new("RGB", (40, 100), "white")
        try:
            paddle_ocr.recognize_regions(
                None, rec, [crop], max_batch=1,
                region_boxes=[(0, 0, 30, 90)], language="ja",
                webtoon_mode=True)
        finally:
            crop.close()
        self.assertEqual(rec.calls, [[(40, 100)]])

    def test_batch_results_reassemble_across_regions_and_apply_confidence(self):
        rec = RecordingRec([("first", 0.9), ("filtered", 0.49)])
        crops = [Image.new("RGB", (30, 20), "white") for _ in range(2)]
        try:
            result = paddle_ocr.recognize_regions(
                None, rec, crops, max_batch=2,
                region_boxes=[(0, 0, 30, 20), (40, 0, 70, 20)],
                language="en")
        finally:
            for crop in crops:
                crop.close()
        self.assertEqual(rec.calls, [[(30, 20), (30, 20)]])
        self.assertEqual([row["text"] for row in result], ["first", ""])

    def test_manga_decoder_contract_and_preprocessing(self):
        self.assertEqual((manga_decode.BOS, manga_decode.EOS,
                          manga_decode.POSITION_LIMIT,
                          manga_decode.MAX_FED_POSITION), (2, 3, 128, 127))
        self.assertEqual(manga_decode.android_postprocess("N0 ... A0B №"), "...AoB")
        crop = Image.new("RGB", (1, 224), "black")
        try:
            tensor = manga_preprocess(crop)
        finally:
            crop.close()
        self.assertEqual(tensor.shape, (3, 224, 224))
        self.assertTrue(np.all(tensor[:, :, 111] == -1.0))
        self.assertTrue(np.all(tensor[:, :, 110] == 1.0))

    def test_manga_batch_setting_and_serial_timing_override(self):
        class FakeMangaEngine:
            def __init__(self, max_batch=8):
                self.max_batch = max_batch
                self.gate = {"pass": True, "encoder_max_abs_diff": 0.0,
                             "decoder_max_abs_diff": 0.0}
                self.seen_batches = []

            def gate_summary(self):
                return self.gate

            def decode_regions(self, crops):
                self.seen_batches.append(self.max_batch)
                return ([{"text": "ok"} for _ in crops], {}, 1.0)

        with mock.patch.object(studio_pipeline, "OcrEngine", FakeMangaEngine), \
                mock.patch.object(studio_pipeline, "log", lambda _message: None):
            pipeline = studio_pipeline.Pipeline()
            pipeline.settings = {"ocr_engine": "mangaocr", "max_batch": 4,
                                 "mangaocr_serial_timing": False}
            engine = pipeline._ocr_engine()
            crop = Image.new("RGB", (10, 10), "white")
            pipeline._recognize_crops([crop])
            pipeline.settings["max_batch"] = 2
            pipeline._recognize_crops([crop])
            pipeline.settings["mangaocr_serial_timing"] = True
            pipeline._recognize_crops([crop])
            crop.close()
        self.assertEqual(engine.seen_batches, [4, 2, 1])
        self.assertFalse(studio_pipeline.DEFAULT_SETTINGS["mangaocr_serial_timing"])

    def test_pipeline_passes_paddle_batch_and_vertical_policy(self):
        import paddle_ocr

        pipeline = studio_pipeline.Pipeline()
        pipeline.settings = {"ocr_engine": "paddle", "max_batch": 5,
                             "source_language": "ja", "reading_order": "rtl"}
        pipeline._paddle = (None, object())
        crop = Image.new("RGB", (54, 104), "white")
        normal_page = Image.new("RGB", (200, 250), "white")
        tall_page = Image.new("RGB", (200, 500), "white")
        region = {"box": [20, 30, 50, 110], "ocr_box": [8, 18, 62, 122]}
        response = [{"text": "ok", "lines": [], "error": None}]
        try:
            with mock.patch.object(paddle_ocr, "recognize_regions",
                                   return_value=response) as recognize:
                output, _runs, _ms = pipeline._recognize_crops(
                    [crop], regions=[region], page_image=normal_page)
                self.assertEqual(output[0]["text"], "ok")
                self.assertEqual(recognize.call_args.kwargs["max_batch"], 5)
                self.assertEqual(recognize.call_args.kwargs["region_boxes"],
                                 [[20, 30, 50, 110]])
                self.assertFalse(recognize.call_args.kwargs["webtoon_mode"])
                self.assertTrue(callable(
                    recognize.call_args.kwargs["unpadded_crop_factory"]))

                pipeline._recognize_crops([crop], regions=[region],
                                          page_image=tall_page)
                self.assertTrue(recognize.call_args.kwargs["webtoon_mode"])
        finally:
            crop.close()
            normal_page.close()
            tall_page.close()

    def test_switching_ocr_engine_invalidates_cached_input_crops(self):
        pipeline = studio_pipeline.Pipeline()
        pipeline.settings = {"ocr_engine": "mangaocr"}
        pipeline._crop_cache["cached"] = Image.new("RGB", (2, 2))
        pipeline.save_settings({"ocr_engine": "paddle"})
        self.assertEqual(pipeline._crop_cache, {})

    def test_ocr_crop_padding_remains_twelve_pixels(self):
        self.assertEqual(studio_pipeline.OCR_PAD, 12)
        padded = studio_pipeline.clamp_pad(Box(20, 30, 40, 50), [200, 200])
        self.assertEqual((padded.x1, padded.y1, padded.x2, padded.y2),
                         (8, 18, 52, 62))
        region = {"box": [20, 30, 40, 50], "ocr_box": [8, 18, 52, 62]}
        self.assertEqual(studio_pipeline.recognition_input_box(region, "mangaocr"),
                         [20, 30, 40, 50])
        self.assertEqual(studio_pipeline.recognition_input_box(region, "paddle"),
                         [8, 18, 52, 62])


if __name__ == "__main__":
    unittest.main(verbosity=2)
