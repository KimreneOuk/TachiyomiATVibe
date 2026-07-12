"""Translation engine for the companion server.

Mirrors the Android app's translator architecture:
  - STANDARD: Google Translate, DeepL
  - AI_MODEL: Gemini, OpenRouter, DeepSeek, LM Studio

Each AI translator:
  - Sends ALL text blocks in ONE LLM call (batch, not per-block)
  - Uses the manga-specific TranslationPrompts system prompt
  - Uses NumberedLineResponseParser or JSON format per provider
  - Sanitizes output with OcrArtifactSanitizer

AiModelFetcher fetches available models from each provider's /models endpoint.
"""
from __future__ import annotations

import json
import re
import urllib.error
import urllib.parse
import urllib.request
from typing import Any


# ═══════════════════════════════════════════════════════════════
# Language labels (mirrors TextRecognizerLanguage / TextTranslatorLanguage)
# ═══════════════════════════════════════════════════════════════

LANG_LABELS = {
    "JAPANESE": "Japanese", "CHINESE": "Chinese", "KOREAN": "Korean",
    "ENGLISH": "English", "SPANISH": "Spanish", "FRENCH": "French",
    "GERMAN": "German", "PORTUGUESE": "Portuguese", "ITALIAN": "Italian",
    "RUSSIAN": "Russian", "CHINESESIM": "Simplified Chinese",
    "CHINESETRAD": "Traditional Chinese",
}

LANG_CODES = {
    "JAPANESE": "ja", "CHINESE": "zh", "KOREAN": "ko",
    "ENGLISH": "en", "SPANISH": "es", "FRENCH": "fr",
    "GERMAN": "de", "PORTUGUESE": "pt", "ITALIAN": "it",
    "RUSSIAN": "ru", "CHINESESIM": "zh-CN", "CHINESETRAD": "zh-TW",
}

SPEECH_TAG = "SPEECH"


def _lang_label(lang: str) -> str:
    return LANG_LABELS.get(lang.upper(), lang)


def _lang_code(lang: str) -> str:
    return LANG_CODES.get(lang.upper(), lang.lower()[:2])


def _is_cjk_target(lang: str) -> bool:
    return lang.upper() in ("JAPANESE", "KOREAN", "CHINESESIM", "CHINESETRAD", "CHINESE")


def _reading_direction(from_lang: str) -> str:
    if from_lang.upper() == "JAPANESE":
        return "right-to-left, top-to-bottom"
    return "left-to-right, top-to-bottom"


# ═══════════════════════════════════════════════════════════════
# Translation Prompts (ported from TranslationPrompts.kt)
# ═══════════════════════════════════════════════════════════════

def numbered_system_prompt(from_lang: str, to_lang: str) -> str:
    return _base_guidance(from_lang, to_lang, numbered=True)


def json_system_prompt(from_lang: str, to_lang: str) -> str:
    return _base_guidance(from_lang, to_lang, numbered=False)


def _base_guidance(from_lang: str, to_lang: str, numbered: bool) -> str:
    fl = _lang_label(from_lang)
    tl = _lang_label(to_lang)
    direction = _reading_direction(from_lang)
    output_rule = (
        "Output ONLY one line per block in the exact format `[index] translation` — no preambles, notes, or explanations."
        if numbered
        else "Return ONLY a JSON object with the same keys and array lengths as the input; each element is ONLY the translation string (no explanations)."
    )
    return f"""You are an expert manga/manhwa/manhua translator and localization specialist. Translate the source text blocks from {fl} to {tl}.

SOURCE-LANGUAGE CONTEXT: {fl} frequently omits subjects and pronouns (it is a pro-drop language). English requires an explicit subject. Infer the implied subject from the line itself, the surrounding blocks, and the provided "previous pairs" context, then choose ONE consistent pronoun and keep it. Never leave a subject ambiguous and never switch person mid-utterance.

POINT OF VIEW / PERSON (critical):
- Lines prefixed [{SPEECH_TAG}] are CONVERSATION inside a speech bubble: a character speaking aloud to an addressee. The speaker = "I/we", the addressee = "you", anyone else mentioned = "he/she/they". When the subject is omitted and cannot be resolved, a [{SPEECH_TAG}] line defaults to the speaker ("I/we") — UNLESS the line is an imperative, an offer/question directed at the addressee ("you"), or quoted/reported speech (keep the quoted clause in its original person).
- Lines with NO [{SPEECH_TAG}] tag are narration or self-dialogue (inner monologue). These are VERY OFTEN the point-of-view character's FIRST-PERSON voice: use "I" when it reads as a character's own thought or recount. Use third person ONLY for objective external description. Do NOT assume free text is third-person.
- If a character refers to themselves by their own name (illeism), convert it to the matching first-person pronoun ("I").
- Keep the point of view consistent across a scene.

DEICTICS: directional/location words are anchored to the speaker; resolve them consistently with the person you chose.

READING ORDER: blocks are roughly ordered {direction}, but comic layouts is irregular — use narrative judgment to connect adjacent bubbles, not the numbers alone.

OTHER RULES:
- Honorifics (-san / -kun / -chan / -sama / -senpai etc.) may be preserved or naturalized as fits the dialogue.
- Sound effects / onomatopoeia: provide standard comic-style equivalents.
- Script fidelity: if the target is a Latin-script language, do NOT output Japanese/Chinese/Korean characters.

THE [{SPEECH_TAG}] TAG IS METADATA, NOT TEXT: use it only to choose voice/POV. NEVER include the word "{SPEECH_TAG}" in your translation.

{output_rule}"""


# ═══════════════════════════════════════════════════════════════
# Numbered Line Response Parser (ported from NumberedLineResponseParser.kt)
# ═══════════════════════════════════════════════════════════════

_NUMBERED_LINE_RE = re.compile(r"^\[(\d+)\]\s*(.+)$", re.MULTILINE)

# CJK Unicode ranges
_CJK_RANGES = [
    (0x4E00, 0x9FFF), (0x3400, 0x4DBF), (0x20000, 0x2A6DF),
    (0x2A700, 0x2B73F), (0x2B740, 0x2B81F), (0xF900, 0xFAFF),
    (0x2F800, 0x2FA1F), (0x3000, 0x303F), (0x3040, 0x309F),
    (0x30A0, 0x30FF), (0x31F0, 0x31FF), (0xAC00, 0xD7AF),
    (0xFF00, 0xFFEF), (0xFE30, 0xFE4F),
]


def _contains_cjk(text: str) -> bool:
    for ch in text:
        cp = ord(ch)
        if any(lo <= cp <= hi for lo, hi in _CJK_RANGES):
            return True
    return False


def parse_numbered_lines(raw: str, expected_count: int, target_lang: str | None = None) -> dict[int, str]:
    """Parse `[index] translation` format. Mirrors NumberedLineResponseParser.kt."""
    result: dict[int, str] = {}
    enforce_no_cjk = target_lang is not None and not _is_cjk_target(target_lang)
    for m in _NUMBERED_LINE_RE.finditer(raw):
        idx = int(m.group(1))
        text = m.group(2).strip()
        if idx < 0 or idx >= expected_count:
            continue
        if not text:
            continue
        if enforce_no_cjk and _contains_cjk(text):
            continue
        if idx in result:
            continue
        result[idx] = text
    return result


# ═══════════════════════════════════════════════════════════════
# OCR Artifact Sanitizer (ported from OcrArtifactSanitizer.kt)
# ═══════════════════════════════════════════════════════════════

_ARTIFACT = r"(?:[N\uff2e][\u00ba\u00b0\u02da]|[N\uff2e]\u2070|\u2116|\uff2e\uff10|N0)"
_LEADING_SPEECH_RE = re.compile(r"^(?:\[SPEECH\]|\(SPEECH\)|SPEECH:)\s*")
_BEFORE_PUNCT_RE = re.compile(r"\s+" + _ARTIFACT + r"(?=[.,!?;:\-])")
_INLINE_RE = re.compile(r"\s+" + _ARTIFACT + r"(?=\s|$)")
_LEADING_RE = re.compile(r"^" + _ARTIFACT + r"\s*")


def sanitize_ocr_artifacts(text: str) -> str:
    cleaned = _LEADING_SPEECH_RE.sub("", text)
    cleaned = _BEFORE_PUNCT_RE.sub("", cleaned)
    cleaned = _INLINE_RE.sub(" ", cleaned)
    cleaned = _LEADING_RE.sub("", cleaned)
    cleaned = re.sub(r"\s{2,}", " ", cleaned).strip()
    return cleaned


# ═══════════════════════════════════════════════════════════════
# HTTP Helper
# ═══════════════════════════════════════════════════════════════

class TranslatorError(RuntimeError):
    """Raised when a translator backend is unreachable or returns an invalid
    response. Propagates so translate_page logs it via log_failure and reports
    translationStatus=FAILED — never silently returns empty translations."""


def _http_json(request: urllib.request.Request, timeout: int = 60) -> dict:
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        raise TranslatorError(f"HTTP {exc.code}: {exc.reason}") from exc
    except urllib.error.URLError as exc:
        raise TranslatorError(f"unreachable: {exc.reason}") from exc
    except Exception as exc:
        raise TranslatorError(f"{type(exc).__name__}: {exc}") from exc


# ═══════════════════════════════════════════════════════════════
# Base Translator Interface
# ═══════════════════════════════════════════════════════════════

class BaseTranslator:
    """Base: translate a list of text blocks in batch."""

    def translate_blocks(self, texts: list[str], from_lang: str, to_lang: str) -> list[str]:
        """Return translations for each text. Empty string on failure."""
        raise NotImplementedError

    def translate(self, text: str, target_lang: str) -> str:
        """Single-text convenience (used by /web/llm/test)."""
        results = self.translate_blocks([text], "JAPANESE", target_lang)
        return results[0] if results else ""


class PlaceholderTranslator(BaseTranslator):
    def translate(self, text: str, target_lang: str) -> str:
        if not text:
            return ""
        return ""

    def translate_blocks(self, texts: list[str], from_lang: str, to_lang: str) -> list[str]:
        return [""] * len(texts)


# ═══════════════════════════════════════════════════════════════
# Google Translator (free gtx endpoint)
# ═══════════════════════════════════════════════════════════════

class GoogleTranslator(BaseTranslator):
    def __init__(self, config: dict[str, Any] | None = None) -> None:
        pass

    def translate_blocks(self, texts: list[str], from_lang: str, to_lang: str) -> list[str]:
        tl = _lang_code(to_lang)
        sl = _lang_code(from_lang)
        results = []
        for text in texts:
            if not text:
                results.append("")
                continue
            params = urllib.parse.urlencode({"client": "gtx", "sl": sl, "tl": tl, "dt": "t", "q": text})
            url = f"https://translate.googleapis.com/translate_a/single?{params}"
            req = urllib.request.Request(url, headers={"User-Agent": "MangaTranslationServer/1.0"})
            data = _http_json(req, timeout=30)
            if data is None:
                results.append("")
            else:
                try:
                    results.append("".join(s[0] for s in data[0] if s[0]))
                except Exception:
                    results.append("")
        return results


# ═══════════════════════════════════════════════════════════════
# DeepL Translator (v2 API with auth_key)
# ═══════════════════════════════════════════════════════════════

class DeepLTranslator(BaseTranslator):
    def __init__(self, config: dict[str, Any]) -> None:
        self.api_key = config.get("api_key", "")
        self.base_url = str(config.get("base_url", "")).rstrip("/") or "https://api-free.deepl.com/v2"

    def translate_blocks(self, texts: list[str], from_lang: str, to_lang: str) -> list[str]:
        if not self.api_key:
            return [""] * len(texts)
        non_empty = [(i, t) for i, t in enumerate(texts) if t.strip()]
        if not non_empty:
            return [""] * len(texts)
        data = urllib.parse.urlencode(
            [("auth_key", self.api_key), ("source_lang", _lang_code(from_lang).upper()),
             ("target_lang", _lang_code(to_lang).upper())] +
            [("text", t) for _, t in non_empty]
        ).encode("utf-8")
        req = urllib.request.Request(f"{self.base_url}/translate", data=data, method="POST")
        result = _http_json(req, timeout=30)
        if result is None:
            return [""] * len(texts)
        try:
            translations = result["translations"]
            out = [""] * len(texts)
            for (idx, _), tr in zip(non_empty, translations):
                out[idx] = tr["text"]
            return out
        except Exception:
            return [""] * len(texts)


# ═══════════════════════════════════════════════════════════════
# AI Chat Translator Base (numbered-line format: DeepSeek, LM Studio)
# ═══════════════════════════════════════════════════════════════

class _ChatTranslatorBase(BaseTranslator):
    """Base for OpenAI chat-completions translators using [index] text format."""

    def _build_request(self, model: str, system_prompt: str, user_content: str,
                       api_key: str, url: str, temperature: float, max_tokens: int) -> urllib.request.Request:
        body = json.dumps({
            "model": model,
            "temperature": temperature,
            "max_tokens": max_tokens,
            "messages": [
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": user_content},
            ],
        }).encode("utf-8")
        req = urllib.request.Request(url, data=body, method="POST")
        req.add_header("Content-Type", "application/json")
        if api_key:
            req.add_header("Authorization", f"Bearer {api_key}")
        return req

    def _extract_content(self, data: dict) -> str:
        try:
            return data["choices"][0]["message"]["content"]
        except (KeyError, IndexError, TypeError):
            return ""

    def translate_blocks(self, texts: list[str], from_lang: str, to_lang: str) -> list[str]:
        non_empty = [(i, t) for i, t in enumerate(texts) if t.strip()]
        if not non_empty:
            return [""] * len(texts)

        numbered_input = "\n".join(f"[{idx}] {t}" for idx, t in non_empty)
        system_prompt = numbered_system_prompt(from_lang, to_lang)
        user_content = f"Translate these {_lang_label(from_lang)} text blocks to {_lang_label(to_lang)}:\n\n{numbered_input}"

        req = self._build_chat_request(system_prompt, user_content)
        data = _http_json(req, timeout=120)
        if data is None:
            return [""] * len(texts)

        raw_output = self._extract_content(data)
        if not raw_output:
            return [""] * len(texts)

        parsed = parse_numbered_lines(raw_output, len(non_empty), to_lang)
        out = [""] * len(texts)
        for j, (orig_idx, _) in enumerate(non_empty):
            if j in parsed:
                out[orig_idx] = sanitize_ocr_artifacts(parsed[j])
        return out

    def _build_chat_request(self, system_prompt: str, user_content: str) -> urllib.request.Request:
        raise NotImplementedError


class DeepSeekTranslator(_ChatTranslatorBase):
    def __init__(self, config: dict[str, Any]) -> None:
        self.api_key = config.get("api_key", "")
        self.model = config.get("model", "") or "deepseek-chat"
        self.temperature = float(config.get("temperature", 0.3))
        self.max_tokens = int(config.get("max_output_tokens", 8192))

    def _build_chat_request(self, system_prompt: str, user_content: str) -> urllib.request.Request:
        return self._build_request(
            self.model, system_prompt, user_content,
            self.api_key, "https://api.deepseek.com/chat/completions",
            self.temperature, self.max_tokens,
        )


class LmStudioTranslator(_ChatTranslatorBase):
    def __init__(self, config: dict[str, Any]) -> None:
        self.base_url = str(config.get("base_url", "")).rstrip("/")
        self.model = config.get("model", "")
        self.temperature = float(config.get("temperature", 0.3))
        self.max_tokens = int(config.get("max_output_tokens", 8192))

    def _build_chat_request(self, system_prompt: str, user_content: str) -> urllib.request.Request:
        return self._build_request(
            self.model, system_prompt, user_content,
            "", f"{self.base_url}/chat/completions",
            self.temperature, self.max_tokens,
        )


# ═══════════════════════════════════════════════════════════════
# OpenRouter Translator (JSON format)
# ═══════════════════════════════════════════════════════════════

class OpenRouterTranslator(BaseTranslator):
    def __init__(self, config: dict[str, Any]) -> None:
        self.api_key = config.get("api_key", "")
        self.model = config.get("model", "")
        self.temperature = float(config.get("temperature", 0.3))
        self.max_tokens = int(config.get("max_output_tokens", 8192))

    def translate_blocks(self, texts: list[str], from_lang: str, to_lang: str) -> list[str]:
        non_empty = [(i, t) for i, t in enumerate(texts) if t.strip()]
        if not non_empty:
            return [""] * len(texts)
        if not self.api_key or not self.model:
            return [""] * len(texts)

        data_obj = {"p": [t for _, t in non_empty]}
        system_prompt = json_system_prompt(from_lang, to_lang)
        body = json.dumps({
            "model": self.model,
            "response_format": {"type": "json_object"},
            "top_p": 0.5,
            "temperature": self.temperature,
            "max_tokens": self.max_tokens,
            "messages": [
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": f"JSON {json.dumps(data_obj)}"},
            ],
        }).encode("utf-8")
        req = urllib.request.Request(
            "https://openrouter.ai/api/v1/chat/completions", data=body, method="POST",
        )
        req.add_header("Content-Type", "application/json")
        req.add_header("Authorization", f"Bearer {self.api_key}")

        result = _http_json(req, timeout=120)
        if result is None:
            return [""] * len(texts)
        try:
            content = result["choices"][0]["message"]["content"]
            res_json = json.loads(content)
            translations = res_json.get("p", [])
        except (KeyError, IndexError, TypeError, json.JSONDecodeError):
            return [""] * len(texts)

        out = [""] * len(texts)
        for j, (orig_idx, _) in enumerate(non_empty):
            if j < len(translations):
                t = translations[j]
                if t and t != "NULL":
                    out[orig_idx] = sanitize_ocr_artifacts(t)
        return out


# ═══════════════════════════════════════════════════════════════
# Gemini Translator (Generative AI API, JSON format)
# ═══════════════════════════════════════════════════════════════

class GeminiTranslator(BaseTranslator):
    def __init__(self, config: dict[str, Any]) -> None:
        self.api_key = config.get("api_key", "")
        self.model = config.get("model", "gemini-1.5-flash")
        self.temperature = float(config.get("temperature", 0.3))
        self.max_tokens = int(config.get("max_output_tokens", 8192))

    def translate_blocks(self, texts: list[str], from_lang: str, to_lang: str) -> list[str]:
        non_empty = [(i, t) for i, t in enumerate(texts) if t.strip()]
        if not non_empty:
            return [""] * len(texts)
        if not self.api_key:
            return [""] * len(texts)

        data_obj = {"p": [t for _, t in non_empty]}
        system_prompt = json_system_prompt(from_lang, to_lang)
        body = json.dumps({
            "system_instruction": {"parts": {"text": system_prompt}},
            "contents": [{"parts": {"text": f"JSON {json.dumps(data_obj)}"}}],
            "generationConfig": {
                "topP": 0.5,
                "topK": 30,
                "temperature": self.temperature,
                "maxOutputTokens": self.max_tokens,
                "responseMimeType": "application/json",
            },
            "safetySettings": [
                {"category": c, "threshold": "BLOCK_NONE"}
                for c in ("HARM_CATEGORY_HARASSMENT", "HARM_CATEGORY_HATE_SPEECH",
                          "HARM_CATEGORY_SEXUALLY_EXPLICIT", "HARM_CATEGORY_DANGEROUS_CONTENT")
            ],
        }).encode("utf-8")
        url = f"https://generativelanguage.googleapis.com/v1beta/models/{self.model}:generateContent?key={self.api_key}"
        req = urllib.request.Request(url, data=body, method="POST")
        req.add_header("Content-Type", "application/json")

        result = _http_json(req, timeout=120)
        if result is None:
            return [""] * len(texts)
        try:
            content = result["candidates"][0]["content"]["parts"][0]["text"]
            res_json = json.loads(content)
            translations = res_json.get("p", [])
        except (KeyError, IndexError, TypeError, json.JSONDecodeError):
            return [""] * len(texts)

        out = [""] * len(texts)
        for j, (orig_idx, _) in enumerate(non_empty):
            if j < len(translations):
                t = translations[j]
                if t and t != "NULL":
                    out[orig_idx] = sanitize_ocr_artifacts(t)
        return out


# ═══════════════════════════════════════════════════════════════
# Factory (mirrors TranslationEngineBuilder.kt)
# ═══════════════════════════════════════════════════════════════

# AI provider labels (mirrors AiTranslatorKind.kt)
AI_PROVIDERS = {
    "gemini": {"label": "Gemini AI", "needs_key": True, "needs_base_url": False, "default_model": "gemini-1.5-flash"},
    "openrouter": {"label": "OpenRouter", "needs_key": True, "needs_base_url": False, "default_model": ""},
    "deepseek": {"label": "DeepSeek", "needs_key": True, "needs_base_url": False, "default_model": "deepseek-chat"},
    "lmstudio": {"label": "LM Studio", "needs_key": False, "needs_base_url": True, "default_model": ""},
}

STANDARD_PROVIDERS = {
    "google": {"label": "Google Translate", "needs_key": False},
    "deepl": {"label": "DeepL", "needs_key": True},
}


def build_translator(config: dict[str, Any]) -> BaseTranslator:
    # Accept either key: "engine" (UI) or "provider" (config.yaml default).
    engine = (config.get("engine") or config.get("provider") or "none").strip().lower()
    if engine in ("", "none"):
        return PlaceholderTranslator()
    if engine == "google":
        return GoogleTranslator(config)
    if engine == "deepl":
        return DeepLTranslator(config)
    if engine == "gemini":
        return GeminiTranslator(config)
    if engine == "openrouter":
        return OpenRouterTranslator(config)
    if engine == "deepseek":
        return DeepSeekTranslator(config)
    # OpenAI-compatible servers (LM Studio, vLLM, Ollama's /v1, etc.) and the
    # legacy "lmstudio" id both hit <base_url>/chat/completions.
    if engine in ("lmstudio", "openai_compatible"):
        return LmStudioTranslator(config)
    # Unknown engine: raise instead of silently substituting a placeholder.
    raise ValueError(f"unknown translator engine: {engine!r}")


# ═══════════════════════════════════════════════════════════════
# AI Model Fetcher (ported from AiModelFetcher.kt)
# ═══════════════════════════════════════════════════════════════

def fetch_ai_models(engine: str, api_key: str = "", base_url: str = "") -> dict[str, Any]:
    """Fetch available models from an AI provider. Returns {'models': [...]} or {'error': '...'}."""
    try:
        if engine == "gemini":
            return _fetch_gemini_models(api_key)
        if engine == "openrouter":
            return _fetch_openai_models("https://openrouter.ai/api/v1/models", api_key)
        if engine == "deepseek":
            return _fetch_openai_models("https://api.deepseek.com/models", api_key)
        if engine in ("lmstudio", "openai_compatible"):
            url = base_url.strip().rstrip("/")
            if not url:
                return {"error": "Base URL is required"}
            return _fetch_openai_models(f"{url}/models", api_key)
        return {"error": f"Unknown engine: {engine}"}
    except Exception as e:
        return {"error": str(e)}


def _fetch_gemini_models(api_key: str) -> dict[str, Any]:
    if not api_key:
        return {"error": "API key is required"}
    url = f"https://generativelanguage.googleapis.com/v1beta/models?key={api_key}"
    req = urllib.request.Request(url, method="GET")
    data = _http_json(req, timeout=30)
    if data is None:
        return {"error": "Failed to fetch from Gemini API"}
    models = []
    for m in data.get("models", []):
        methods = m.get("supportedGenerationMethods", [])
        if "generateContent" not in methods:
            continue
        name = m.get("name", "").removeprefix("models/").strip()
        if name:
            models.append(name)
    models.sort()
    return {"models": models}


def _fetch_openai_models(url: str, api_key: str) -> dict[str, Any]:
    req = urllib.request.Request(url, method="GET")
    req.add_header("Content-Type", "application/json")
    if api_key:
        req.add_header("Authorization", f"Bearer {api_key}")
    data = _http_json(req, timeout=30)
    if data is None:
        return {"error": "Failed to fetch models"}
    models = []
    for m in data.get("data", []):
        mid = m.get("id", "").strip()
        if mid:
            models.append(mid)
    models.sort()
    return {"models": models}
