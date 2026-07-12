"""LLM translation bridge — thin wrapper over companion_server's translator.

The Android LM Studio translator is faithfully reproduced in
``companion_server/translate/translator.py`` (build_translator,
fetch_ai_models, the numbered-line protocol, the manga-localization prompt).
The lab already puts companion_server on sys.path (pipeline.py:33-35), so this
module wires it in rather than re-porting it.

No fallback: on any failure, errors are logged loudly and surfaces as an error
in the returned dict — never a silent substitution of the source text, never a
crash of the caller.
"""
from __future__ import annotations

import hashlib
import json
import logging
import sys
import time
from typing import Any

from backend import config

logger = logging.getLogger("overlay_lab")

# companion_server is a sibling package whose translator stack we reuse. Put it
# on sys.path so `from translate.translator import ...` works (matches the
# idiom in pipeline.py / strategies.py).
_CS = str(config.COMPANION_SERVER)
if _CS not in sys.path:
    sys.path.insert(0, _CS)


def _config_hash(cfg: dict[str, Any]) -> str:
    raw = json.dumps(cfg, sort_keys=True, default=str)
    return hashlib.md5(raw.encode("utf-8")).hexdigest()


# Session cache: one translator instance per distinct config so the (cheap)
# build isn't repeated on every translate call. Keyed by config hash.
_translator_cache: dict[str, Any] = {}


def _get_translator(llm_config: dict[str, Any]) -> Any:
    from translate.translator import build_translator  # companion_server
    key = _config_hash(llm_config)
    t = _translator_cache.get(key)
    if t is None:
        t = build_translator(llm_config)
        _translator_cache[key] = t
    return t


def fetch_models(engine: str, base_url: str) -> dict[str, Any]:
    """Fetch available models from an OpenAI-compatible / LM Studio server.

    Returns ``{"models": ["name", ...]}`` on success, or ``{"error": "..."}``
    on any failure (never raises to the caller — the endpoint surfaces the
    error string to the UI).
    """
    try:
        from translate.translator import fetch_ai_models
        result = fetch_ai_models(engine, api_key="", base_url=base_url)
        # fetch_ai_models returns {'models': [...]} or {'error': '...'}.
        return result
    except Exception as e:
        logger.error("fetch_models(%s, %s) failed: %s", engine, base_url, e)
        return {"error": str(e)}


def translate_blocks(
    blocks: list[dict[str, Any]],
    llm_config: dict[str, Any],
    from_lang: str = "ja",
    to_lang: str = "en",
) -> dict[str, Any]:
    """Translate the ``text`` of each block, overwriting its ``translation``.

    Parameters
    ----------
    blocks : list of dicts
        Each must have ``"text"``; ``"translation"`` is overwritten in place.
    llm_config : dict
        Translator config: ``engine``, ``base_url``, ``model``,
        ``temperature``, ``max_output_tokens``.
    from_lang, to_lang : str
        ISO 639-1 codes (e.g. ``"ja"`` → ``"en"``).

    Returns
    -------
    dict
        ``{"translations": [str, ...], "ms": float, "model": str,
        "block_count": int}`` on success, or ``{"error": str,
        "translations": [], "ms": float}`` on failure. On failure the blocks'
        ``translation`` fields are left untouched (no source-text substitution).
    """
    t0 = time.perf_counter()
    texts = [str(b.get("text", "")) for b in blocks]

    try:
        translator = _get_translator(llm_config)
        translations = translator.translate_blocks(texts, from_lang, to_lang)
    except Exception as e:
        logger.error(
            "translate_blocks failed (engine=%s base_url=%s model=%s): %s",
            llm_config.get("engine"), llm_config.get("base_url"),
            llm_config.get("model"), e,
        )
        return {
            "error": str(e),
            "translations": [],
            "ms": round((time.perf_counter() - t0) * 1000, 1),
        }

    # Write back per-block (translate_blocks returns a list aligned to input).
    translated_count = 0
    for i, block in enumerate(blocks):
        t = translations[i] if i < len(translations) else ""
        if t:
            block["translation"] = t
            translated_count += 1
        # No else: leave the existing translation intact (no source substitution).

    return {
        "translations": translations,
        "ms": round((time.perf_counter() - t0) * 1000, 1),
        "model": llm_config.get("model", ""),
        "block_count": len(blocks),
        "translated_count": translated_count,
    }
