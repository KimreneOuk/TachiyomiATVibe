"""Focused offline tests for Android-compatible translation providers."""
from __future__ import annotations

import io
import json
from pathlib import Path
import threading
import unittest
import urllib.error
import urllib.parse
import urllib.request
from types import SimpleNamespace
from unittest.mock import patch

from translation_providers import (
    AI_MAX_CONTEXT_TOKENS,
    AI_SAFETY_MARGIN_TOKENS,
    GOOGLE_MAX_ENVELOPE_CODEPOINTS,
    GOOGLE_MAX_ATTEMPTS,
    GOOGLE_MAX_INLINE_BACKOFF_SECONDS,
    GOOGLE_MINIMUM_SPACING_SECONDS,
    GOOGLE_REQUESTS_PER_MINUTE,
    GOOGLE_WINDOW_SECONDS,
    GoogleRequestGovernor,
    cache_valid_translations,
    parse_google_envelope_html,
    parse_openai_translation_lines,
    plan_google_envelopes,
    plan_openai_chunks,
    stable_openai_block_indexes,
    translate_google_batch,
    translate_openai_compat_batch,
)


FIXTURES = Path(__file__).with_name("fixtures")


def fixture_text(name: str) -> str:
    return (FIXTURES / name).read_text(encoding="utf-8")


class FakeClock:
    def __init__(self) -> None:
        self.now = 0.0
        self.sleeps: list[float] = []

    def monotonic(self) -> float:
        return self.now

    def sleep(self, seconds: float) -> None:
        self.sleeps.append(seconds)
        self.now += seconds


class FakeResponse:
    status = 200
    code = 200

    def __init__(self, body: str) -> None:
        self.body = body.encode("utf-8")

    def read(self) -> bytes:
        return self.body

    def close(self) -> None:
        pass


def gtx_response(translations: list[str]) -> str:
    """Build the documented Google JSON segment shape around translated HTML."""
    segments = [[text, "source", None, None, 1] for text in translations]
    return json.dumps([segments, None, None, "ja", "en", 1], ensure_ascii=False)


class GoogleProtocolTests(unittest.TestCase):
    def test_gtx_envelope_request_and_ordered_id_match(self) -> None:
        body = fixture_text("google_gtx_envelope.json")
        calls = []

        def opener(request: urllib.request.Request, timeout: float) -> FakeResponse:
            calls.append(request)
            query = urllib.parse.parse_qs(urllib.parse.urlsplit(request.full_url).query)
            self.assertEqual(query["client"], ["gtx"])
            self.assertEqual(query["sl"], ["ja"])
            self.assertEqual(query["tl"], ["en"])
            self.assertIn('<span data-id="b0">行く。</span>', query["q"][0])
            self.assertIn('<span data-id="b1">あの日、彼と出会った。</span>', query["q"][0])
            return FakeResponse(body)

        clock = FakeClock()
        governor = GoogleRequestGovernor(clock=clock.monotonic, sleeper=clock.sleep)
        actual = translate_google_batch(
            ["行く。", "あの日、彼と出会った。"],
            opener=opener,
            governor=governor,
            sleeper=clock.sleep,
        )
        self.assertEqual(actual, ["Let's go!", "See you tomorrow."])
        self.assertEqual(len(calls), 1)

    def test_gtx_parser_requires_exact_ids_and_order(self) -> None:
        self.assertEqual(
            parse_google_envelope_html(
                '<span data-id="b0">A &amp; B</span><span data-id="b1">C</span>',
                ["b0", "b1"],
            ),
            ["A & B", "C"],
        )
        self.assertIsNone(parse_google_envelope_html(
            '<span data-id="b0">A</span><span data-id="extra">C</span>', ["b0", "b1"],
        ))
        self.assertIsNone(parse_google_envelope_html(
            '<span data-id="b0">A</span>', ["b0", "b1"],
        ))
        self.assertIsNone(parse_google_envelope_html(
            '<span data-id="b1">B</span><span data-id="b0">A</span>', ["b0", "b1"],
        ))
        self.assertIsNone(parse_google_envelope_html(
            '<span data-id="b0">A</span><span data-id="b0">B</span>', ["b0", "b1"],
        ))

    def test_gtx_chunk_boundaries_count_unicode_codepoints(self) -> None:
        overhead = len('<span data-id="b0"></span>')
        exact = "😀" * (GOOGLE_MAX_ENVELOPE_CODEPOINTS - overhead)
        chunks = plan_google_envelopes([exact])
        self.assertIsNotNone(chunks)
        self.assertEqual(len(chunks[0].source), GOOGLE_MAX_ENVELOPE_CODEPOINTS)
        self.assertIsNone(plan_google_envelopes([exact + "😀"]))

        split = plan_google_envelopes(["😀" * 2_500, "文" * 2_500])
        self.assertIsNotNone(split)
        self.assertEqual([chunk.block_indices for chunk in split], [(0,), (1,)])
        self.assertTrue(all(len(chunk.source) <= GOOGLE_MAX_ENVELOPE_CODEPOINTS for chunk in split))

    def test_invalid_envelope_falls_back_to_android_gtx_single_path(self) -> None:
        calls = []

        def opener(request: urllib.request.Request, timeout: float) -> FakeResponse:
            calls.append(request)
            query = urllib.parse.parse_qs(urllib.parse.urlsplit(request.full_url).query)
            if "translate.googleapis.com" in request.full_url:
                return FakeResponse(fixture_text("google_gtx_bad_id.json"))
            self.assertIn("translate.google.com", request.full_url)
            self.assertEqual(query["client"], ["gtx"])
            self.assertIn("tk", query)
            return FakeResponse(fixture_text("google_gtx_single.json"))

        clock = FakeClock()
        actual = translate_google_batch(
            ["First", "Second"], opener=opener,
            governor=GoogleRequestGovernor(clock=clock.monotonic, sleeper=clock.sleep),
            sleeper=clock.sleep,
        )
        self.assertEqual(actual, ["Fallback text", "Fallback text"])
        self.assertEqual(len(calls), 3)

    def test_retry_uses_three_attempts_one_second_base_and_inline_cap(self) -> None:
        clock = FakeClock()
        calls = 0

        def opener(request: urllib.request.Request, timeout: float) -> FakeResponse:
            nonlocal calls
            calls += 1
            if calls < 3:
                raise urllib.error.HTTPError(
                    request.full_url, 503, "temporary", {}, io.BytesIO(b""),
                )
            return FakeResponse(fixture_text("google_gtx_envelope.json"))

        actual = translate_google_batch(
            ["行く。", "あの日、彼と出会った。"], opener=opener,
            governor=GoogleRequestGovernor(clock=clock.monotonic, sleeper=clock.sleep),
            sleeper=clock.sleep,
        )
        self.assertEqual(actual, ["Let's go!", "See you tomorrow."])
        self.assertEqual(calls, GOOGLE_MAX_ATTEMPTS)
        self.assertEqual(clock.sleeps, [1.0, 2.0])
        self.assertEqual(GOOGLE_MAX_INLINE_BACKOFF_SECONDS, 30.0)

    def test_retry_after_beyond_inline_cap_fails_without_waiting(self) -> None:
        clock = FakeClock()
        calls = 0

        def throttled_opener(request: urllib.request.Request, timeout: float):
            nonlocal calls
            calls += 1
            raise urllib.error.HTTPError(
                request.full_url, 429, "throttled", {"Retry-After": "31"}, io.BytesIO(b""),
            )

        actual = translate_google_batch(
            ["source"], opener=throttled_opener,
            governor=GoogleRequestGovernor(clock=clock.monotonic, sleeper=clock.sleep),
            sleeper=clock.sleep,
        )
        # The over-cap quota wait is deferred; no per-block load is added.
        self.assertEqual(actual, [None])
        self.assertEqual(calls, 1)
        self.assertEqual(clock.sleeps, [])

    def test_governor_enforces_minimum_spacing_and_rolling_minute_limit(self) -> None:
        clock = FakeClock()
        governor = GoogleRequestGovernor(
            requests_per_minute=2,
            minimum_spacing_seconds=1.0,
            window_seconds=60.0,
            clock=clock.monotonic,
            sleeper=clock.sleep,
        )
        for _ in range(3):
            with governor.request_slot():
                pass
        self.assertEqual(clock.sleeps, [1.0, 59.0])
        self.assertEqual(GOOGLE_REQUESTS_PER_MINUTE, 60)
        self.assertEqual(GOOGLE_MINIMUM_SPACING_SECONDS, 1.0)
        self.assertEqual(GOOGLE_WINDOW_SECONDS, 60.0)

    def test_total_google_failure_never_caches_source_text(self) -> None:
        clock = FakeClock()
        calls = 0

        def failing_opener(request: urllib.request.Request, timeout: float):
            nonlocal calls
            calls += 1
            raise urllib.error.URLError("offline test fixture")

        source = "そのままの原文"
        results = translate_google_batch(
            [source], opener=failing_opener,
            governor=GoogleRequestGovernor(clock=clock.monotonic, sleeper=clock.sleep),
            sleeper=clock.sleep,
        )
        cache: dict[str, str] = {}
        accepted = cache_valid_translations(cache, [("r00", source)], {"r00": results[0]})
        self.assertEqual(results, [None])
        self.assertEqual(calls, GOOGLE_MAX_ATTEMPTS)
        self.assertEqual(accepted, [])
        self.assertEqual(cache, {})

    def test_pipeline_never_persists_a_google_source_echo_on_failure(self) -> None:
        import pipeline

        source = "そのままの原文"
        fake_pipeline = SimpleNamespace(
            lock=threading.RLock(),
            pages=["p001.jpg"],
            settings={"translate_backend": "google", "target_lang": "English"},
            _cache={
                "ocr": {"p001.jpg": {"regions": [{"id": "r00", "text": source}]}},
                "translations": {},
            },
            _render_dirty={},
            _save_json=lambda *_args: self.fail("failed translation must not be saved"),
            log=lambda *_args: None,
        )
        with patch.object(pipeline, "translate_google_batch", return_value=[source]):
            result = pipeline.Pipeline.translate_page(fake_pipeline, "p001.jpg")
        self.assertEqual(result["translated"], 0)
        self.assertEqual(result["untranslated"], ["r00"])
        self.assertEqual(fake_pipeline._cache["translations"], {})

    def test_pipeline_clears_and_retries_legacy_source_echo(self) -> None:
        import pipeline

        source = "previously cached source"
        saves = []
        fake_pipeline = SimpleNamespace(
            lock=threading.RLock(),
            pages=["p001.jpg"],
            settings={"translate_backend": "google", "target_lang": "English"},
            _cache={
                "ocr": {"p001.jpg": {"regions": [{"id": "r00", "text": source}]}},
                "translations": {"p001.jpg": {"r00": source}},
            },
            _render_dirty={},
            _save_json=lambda name, cache: saves.append((name, json.loads(json.dumps(cache)))),
            log=lambda *_args: None,
        )
        with patch.object(pipeline, "translate_google_batch", return_value=[None]) as translate:
            result = pipeline.Pipeline.translate_page(fake_pipeline, "p001.jpg")
        translate.assert_called_once_with([source], "English", "Japanese")
        self.assertEqual(result["untranslated"], ["r00"])
        self.assertEqual(fake_pipeline._cache["translations"]["p001.jpg"], {})
        self.assertEqual(saves[0][0], "translations.json")
        self.assertEqual(saves[0][1]["p001.jpg"], {})

    def test_pipeline_clears_and_retries_legacy_source_echo(self) -> None:
        import pipeline

        source = "previously cached source"
        saves = []
        fake_pipeline = SimpleNamespace(
            lock=threading.RLock(),
            pages=["p001.jpg"],
            settings={"translate_backend": "google", "target_lang": "English"},
            _cache={
                "ocr": {"p001.jpg": {"regions": [{"id": "r00", "text": source}]}},
                "translations": {"p001.jpg": {"r00": source}},
            },
            _render_dirty={},
            _save_json=lambda name, cache: saves.append((name, json.loads(json.dumps(cache)))),
            log=lambda *_args: None,
        )
        with patch.object(pipeline, "translate_google_batch", return_value=[None]) as translate:
            result = pipeline.Pipeline.translate_page(fake_pipeline, "p001.jpg")
        translate.assert_called_once_with([source], "English", "Japanese")
        self.assertEqual(result["untranslated"], ["r00"])
        self.assertEqual(fake_pipeline._cache["translations"]["p001.jpg"], {})
        self.assertEqual(saves[0][0], "translations.json")
        self.assertEqual(saves[0][1]["p001.jpg"], {})

    def test_source_equal_provider_echo_is_not_cached(self) -> None:
        cache: dict[str, str] = {}
        accepted = cache_valid_translations(
            cache, [("r00", " same ")], {"r00": "same"},
        )
        self.assertEqual(accepted, [])
        self.assertEqual(cache, {})


class OpenAiCompatibilityTests(unittest.TestCase):
    def test_prompt_and_payload_use_android_id_protocol(self) -> None:
        body = fixture_text("openai_compat_valid.json")
        calls = []

        def opener(request: urllib.request.Request, timeout: float) -> FakeResponse:
            calls.append(request)
            payload = json.loads(request.data.decode("utf-8"))
            self.assertEqual(request.full_url, "http://127.0.0.1:1234/v1/chat/completions")
            self.assertEqual(payload["model"], "local-model")
            self.assertEqual(payload["temperature"], 0.2)
            self.assertLessEqual(payload["max_tokens"], AI_MAX_CONTEXT_TOKENS)
            self.assertEqual(payload["messages"][1]["content"], "p0_b0|行く。\np0_b1|あの日、彼と出会った。")
            system = payload["messages"][0]["content"]
            self.assertIn("You are a manga localization specialist.", system)
            self.assertIn("p0_b0|I'm going.", system)
            self.assertIn("p0_b1|That day, I met him.", system)
            self.assertIn("exactly one line per block", system)
            return FakeResponse(body)

        actual = translate_openai_compat_batch(
            [("p0_b0", "行く。"), ("p0_b1", "あの日、彼と出会った。")],
            "English", "http://127.0.0.1:1234/v1", "local-model", opener=opener,
        )
        self.assertEqual(actual, {"p0_b0": "I'm going.", "p0_b1": "That day, I met him."})
        self.assertEqual(len(calls), 1)

    def test_openai_requests_retry_and_share_provider_pacing(self) -> None:
        clock = FakeClock()
        calls = 0
        governor = GoogleRequestGovernor(
            clock=clock.monotonic,
            sleeper=clock.sleep,
        )

        def opener(request: urllib.request.Request, timeout: float) -> FakeResponse:
            nonlocal calls
            calls += 1
            if calls in (1, 2):
                raise urllib.error.HTTPError(
                    request.full_url, 503, "temporary", {}, io.BytesIO(b""),
                )
            return FakeResponse(fixture_text("openai_compat_valid.json"))

        blocks = [("p0_b0", "行く。"), ("p0_b1", "あの日、彼と出会った。")]
        first = translate_openai_compat_batch(
            blocks, "English", "http://127.0.0.1:1234/v1", "local-model",
            opener=opener, governor=governor, sleeper=clock.sleep,
        )
        second = translate_openai_compat_batch(
            blocks, "English", "http://127.0.0.1:1234/v1", "local-model",
            opener=opener, governor=governor, sleeper=clock.sleep,
        )

        self.assertEqual(first, {"p0_b0": "I'm going.", "p0_b1": "That day, I met him."})
        self.assertEqual(second, first)
        self.assertEqual(calls, 4)
        # Two transient retries use Android's 1s, 2s exponential delays;
        # the next translation request is admitted only after 1s spacing.
        self.assertEqual(clock.sleeps, [1.0, 2.0, 1.0])

    def test_response_validation_rejects_unknown_duplicate_blank_and_missing_ids(self) -> None:
        expected = ["p0_b0", "p0_b1"]
        self.assertEqual(
            parse_openai_translation_lines("p0_b0|One\np0_b1|Two", expected),
            {"p0_b0": "One", "p0_b1": "Two"},
        )
        self.assertEqual(
            parse_openai_translation_lines("p0_b0> One\np0_b1|Two", expected),
            {"p0_b0": "One", "p0_b1": "Two"},
        )
        self.assertIsNone(parse_openai_translation_lines(
            "p0_b0|One\np0_b2|Unknown", expected,
        ))
        self.assertIsNone(parse_openai_translation_lines(
            "p0_b0|First\np0_b0|Duplicate\np0_b1|Two", expected,
        ))
        self.assertIsNone(parse_openai_translation_lines(
            "p0_b0|\np0_b1|Two", expected,
        ))
        self.assertIsNone(parse_openai_translation_lines("p0_b0|One", expected))

    def test_token_planner_splits_and_respects_context_ceiling(self) -> None:
        chunks, rejected = plan_openai_chunks([
            ("p0_b0", "A short line."),
            ("p0_b1", "Another short line."),
        ])
        self.assertEqual(rejected, [])
        self.assertEqual(len(chunks), 1)
        self.assertEqual(chunks[0].block_ids, ("p0_b0", "p0_b1"))
        for chunk in chunks:
            self.assertLessEqual(
                chunk.estimated_prompt_tokens
                + chunk.max_output_tokens
                + chunk.protocol_reserve_tokens
                + AI_SAFETY_MARGIN_TOKENS,
                AI_MAX_CONTEXT_TOKENS,
            )

        chunks, rejected = plan_openai_chunks([
            ("p0_b0", "A" * 4_000),
            ("p0_b1", "B" * 4_000),
        ])
        self.assertEqual(chunks, [])
        self.assertEqual(rejected, ["p0_b0", "p0_b1"])
        too_large, rejected = plan_openai_chunks([("p0_b2", "X" * 10_000)])
        self.assertEqual(too_large, [])
        self.assertEqual(rejected, ["p0_b2"])

    def test_stable_request_block_indexes_follow_android_geometry_order(self) -> None:
        # Input order is bottom-right then top-left; translation IDs remain
        # stable in top-to-bottom/left-to-right geometry order.
        self.assertEqual(
            stable_openai_block_indexes([(20, 30, 8, 9), (2, 4, 6, 7)]),
            [1, 0],
        )


if __name__ == "__main__":
    unittest.main()
