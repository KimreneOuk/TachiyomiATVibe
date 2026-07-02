"""Tests for build_translator: engine/provider resolution, openai_compatible,
and the no-silent-placeholder guarantee (unknown engine raises)."""
from __future__ import annotations

import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from translate.translator import (
    DeepLTranslator, DeepSeekTranslator, GeminiTranslator, GoogleTranslator,
    LmStudioTranslator, OpenRouterTranslator, PlaceholderTranslator,
    build_translator,
)


def test_none_engine_returns_placeholder():
    assert isinstance(build_translator({"engine": "none"}), PlaceholderTranslator)
    assert isinstance(build_translator({}), PlaceholderTranslator)


def test_provider_key_is_accepted_as_alias():
    # config.yaml historically used "provider" instead of "engine".
    t = build_translator({"provider": "google"})
    assert isinstance(t, GoogleTranslator)


def test_each_known_engine_maps_to_its_class():
    assert isinstance(build_translator({"engine": "google"}), GoogleTranslator)
    assert isinstance(build_translator({"engine": "deepl", "api_key": "k"}), DeepLTranslator)
    assert isinstance(build_translator({"engine": "gemini", "api_key": "k"}), GeminiTranslator)
    assert isinstance(build_translator({"engine": "openrouter", "api_key": "k"}), OpenRouterTranslator)
    assert isinstance(build_translator({"engine": "deepseek", "api_key": "k"}), DeepSeekTranslator)
    assert isinstance(build_translator({"engine": "lmstudio", "base_url": "http://x"}), LmStudioTranslator)


def test_openai_compatible_maps_to_lmstudio_translator():
    t = build_translator({"engine": "openai_compatible", "base_url": "http://localhost:1234/v1"})
    assert isinstance(t, LmStudioTranslator)
    assert t.base_url == "http://localhost:1234/v1"


def test_unknown_engine_raises_not_silent_placeholder():
    # The no-fallback rule: a typo/misconfiguration must surface, not degrade
    # to PlaceholderTranslator pretending everything is fine.
    with pytest.raises(ValueError, match="unknown translator engine"):
        build_translator({"engine": "chatgpt"})


def test_engine_is_case_insensitive():
    assert isinstance(build_translator({"engine": "GOOGLE"}), GoogleTranslator)
    assert isinstance(build_translator({"engine": "  Gemini  ", "api_key": "k"}), GeminiTranslator)
