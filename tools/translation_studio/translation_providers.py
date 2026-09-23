"""Offline-testable provider protocols for the Translation Studio.

The Kotlin translator sources are the protocol reference. This module keeps
the desktop transport, framing, pacing, and translation-cache acceptance rules
independent of the model-heavy pipeline module.
"""
from __future__ import annotations

from collections import deque
from dataclasses import dataclass
from email.utils import parsedate_to_datetime
from html import escape as html_escape
from html.parser import HTMLParser
import json
import re
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from typing import Callable, Mapping, Sequence


GOOGLE_ENVELOPE_URL = "https://translate.googleapis.com/translate_a/single"
GOOGLE_SINGLE_URL = "https://translate.google.com/translate_a/single"
GOOGLE_MAX_ENVELOPE_CODEPOINTS = 5_000
GOOGLE_MAX_ATTEMPTS = 3
GOOGLE_BASE_BACKOFF_SECONDS = 1.0
GOOGLE_MAX_INLINE_BACKOFF_SECONDS = 30.0
GOOGLE_REQUESTS_PER_MINUTE = 60
GOOGLE_MINIMUM_SPACING_SECONDS = 1.0
GOOGLE_WINDOW_SECONDS = 60.0
PROVIDER_MAX_ATTEMPTS = GOOGLE_MAX_ATTEMPTS
PROVIDER_BASE_BACKOFF_SECONDS = GOOGLE_BASE_BACKOFF_SECONDS
PROVIDER_MAX_INLINE_BACKOFF_SECONDS = GOOGLE_MAX_INLINE_BACKOFF_SECONDS
PROVIDER_REQUESTS_PER_MINUTE = GOOGLE_REQUESTS_PER_MINUTE
PROVIDER_MINIMUM_SPACING_SECONDS = GOOGLE_MINIMUM_SPACING_SECONDS
PROVIDER_WINDOW_SECONDS = GOOGLE_WINDOW_SECONDS

AI_MAX_CONTEXT_TOKENS = 8_192
AI_SAFETY_MARGIN_TOKENS = 512
AI_MIN_OUTPUT_TOKENS = 256
AI_DEFAULT_OUTPUT_TOKENS = 8_192
AI_PROMPT_OVERHEAD_TOKENS = 1_400
AI_BATCH_RESPONSE_FIXED_OVERHEAD_TOKENS = 32
AI_BATCH_RESPONSE_PAGE_OVERHEAD_TOKENS = 24
AI_BATCH_RESPONSE_BLOCK_OVERHEAD_TOKENS = 8
AI_BATCH_RESPONSE_WHITESPACE_OVERHEAD_TOKENS = 2
AI_DEFAULT_TEMPERATURE = 0.2  # Preserve the Studio's existing setting.


_LANGUAGE_CODES = {
    "English": "en",
    "Japanese": "ja",
    "Spanish": "es",
    "French": "fr",
    "German": "de",
    "Portuguese": "pt",
    "Italian": "it",
    "Russian": "ru",
    "Korean": "ko",
    "Chinese": "zh-CN",
    "Chinese (Simplified)": "zh-CN",
    "Chinese (Traditional)": "zh-TW",
}
_LANGUAGE_NAMES_BY_CODE = {value.lower(): key for key, value in _LANGUAGE_CODES.items()}
_PRO_DROP_LANGUAGES = {
    "japanese", "chinese", "korean", "spanish", "portuguese", "italian",
}


def language_code(language: str, default: str = "en") -> str:
    value = (language or "").strip()
    if value in _LANGUAGE_CODES:
        return _LANGUAGE_CODES[value]
    if value.lower() in _LANGUAGE_NAMES_BY_CODE:
        return value
    if re.fullmatch(r"[a-zA-Z]{2,3}(?:-[a-zA-Z0-9]{2,8})?", value):
        return value
    return default


def language_name(language: str, default: str = "Japanese") -> str:
    value = (language or "").strip()
    if value in _LANGUAGE_CODES:
        return value
    return _LANGUAGE_NAMES_BY_CODE.get(value.lower(), value or default)


class ProviderRequestGovernor:
    """Serial per-provider governor matching Android's default request policy."""

    def __init__(
        self,
        requests_per_minute: int = GOOGLE_REQUESTS_PER_MINUTE,
        minimum_spacing_seconds: float = GOOGLE_MINIMUM_SPACING_SECONDS,
        window_seconds: float = GOOGLE_WINDOW_SECONDS,
        clock: Callable[[], float] = time.monotonic,
        sleeper: Callable[[float], None] = time.sleep,
    ) -> None:
        if requests_per_minute <= 0 or minimum_spacing_seconds < 0 or window_seconds <= 0:
            raise ValueError("invalid provider request-governor policy")
        self.requests_per_minute = requests_per_minute
        self.minimum_spacing_seconds = minimum_spacing_seconds
        self.window_seconds = window_seconds
        self._clock = clock
        self._sleep = sleeper
        self._admissions: deque[float] = deque()
        self._last_admission: float | None = None
        self._request_lock = threading.Lock()

    def request_slot(self):
        """Hold the serial slot for one complete HTTP request."""
        return _ProviderRequestSlot(self)

    def _admit(self) -> None:
        while True:
            now = self._clock()
            cutoff = now - self.window_seconds
            while self._admissions and self._admissions[0] <= cutoff:
                self._admissions.popleft()

            waits = []
            if self._last_admission is not None:
                waits.append(self._last_admission + self.minimum_spacing_seconds - now)
            if len(self._admissions) >= self.requests_per_minute:
                waits.append(self._admissions[0] + self.window_seconds - now)
            wait_seconds = max(waits, default=0.0)
            if wait_seconds <= 0:
                admitted_at = self._clock()
                self._admissions.append(admitted_at)
                self._last_admission = admitted_at
                return
            self._sleep(wait_seconds)


class _ProviderRequestSlot:
    def __init__(self, governor: ProviderRequestGovernor) -> None:
        self.governor = governor

    def __enter__(self):
        self.governor._request_lock.acquire()
        try:
            self.governor._admit()
        except BaseException:
            self.governor._request_lock.release()
            raise
        return self

    def __exit__(self, exc_type, exc, traceback):
        self.governor._request_lock.release()
        return False


# Keep the old name as an alias for callers/tests focused on the Google path.
GoogleRequestGovernor = ProviderRequestGovernor
_SHARED_GOOGLE_GOVERNOR = ProviderRequestGovernor()
_SHARED_OPENAI_GOVERNORS: dict[tuple[str, str], ProviderRequestGovernor] = {}
_OPENAI_GOVERNORS_LOCK = threading.Lock()


def _openai_request_governor(endpoint: str, model: str) -> ProviderRequestGovernor:
    # Android keys request buckets by backend, model, and credential scope.
    # Translation Studio has one OpenAI-compatible backend and no credential
    # field, so endpoint plus model is the closest local bucket identity.
    key = (endpoint.rstrip("/").lower(), model.strip())
    with _OPENAI_GOVERNORS_LOCK:
        governor = _SHARED_OPENAI_GOVERNORS.get(key)
        if governor is None:
            governor = ProviderRequestGovernor()
            _SHARED_OPENAI_GOVERNORS[key] = governor
        return governor


@dataclass(frozen=True)
class GoogleTranslationChunk:
    block_indices: tuple[int, ...]
    source: str


def _google_span(block_index: int, text: str) -> str:
    return f'<span data-id="b{block_index}">{html_escape(text, quote=False)}</span>'


def plan_google_envelopes(texts: Sequence[str]) -> list[GoogleTranslationChunk] | None:
    """Greedily pack Android-compatible span envelopes by Unicode codepoint."""
    if not texts:
        return []
    chunks: list[GoogleTranslationChunk] = []
    indices: list[int] = []
    source_parts: list[str] = []
    current_codepoints = 0
    for index, text in enumerate(texts):
        wrapped = _google_span(index, text)
        wrapped_codepoints = len(wrapped)
        if wrapped_codepoints > GOOGLE_MAX_ENVELOPE_CODEPOINTS:
            # Android's planner declines the whole envelope and uses its
            # per-block request path if one block cannot fit.
            return None
        if indices and current_codepoints + wrapped_codepoints > GOOGLE_MAX_ENVELOPE_CODEPOINTS:
            chunks.append(GoogleTranslationChunk(tuple(indices), "".join(source_parts)))
            indices = []
            source_parts = []
            current_codepoints = 0
        indices.append(index)
        source_parts.append(wrapped)
        current_codepoints += wrapped_codepoints
    if indices:
        chunks.append(GoogleTranslationChunk(tuple(indices), "".join(source_parts)))
    return chunks


class _DataIdSpanParser(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.spans: list[tuple[str, list[str]]] = []
        self._open_spans: list[list[str] | None] = []

    def handle_starttag(self, tag: str, attrs) -> None:
        if tag.lower() != "span":
            return
        attr_map = dict(attrs)
        span_text = [] if "data-id" in attr_map else None
        if span_text is not None:
            self.spans.append((attr_map["data-id"], span_text))
        self._open_spans.append(span_text)

    def handle_startendtag(self, tag: str, attrs) -> None:
        self.handle_starttag(tag, attrs)
        self.handle_endtag(tag)

    def handle_data(self, data: str) -> None:
        for span_text in self._open_spans:
            if span_text is not None:
                span_text.append(data)

    def handle_endtag(self, tag: str) -> None:
        if tag.lower() == "span" and self._open_spans:
            self._open_spans.pop()


def parse_google_envelope_html(raw_html: str, expected_ids: Sequence[str]) -> list[str] | None:
    parser = _DataIdSpanParser()
    try:
        parser.feed(raw_html)
        parser.close()
    except Exception:
        return None
    if len(parser.spans) != len(expected_ids):
        return None
    actual_ids = [span_id for span_id, _ in parser.spans]
    if actual_ids != list(expected_ids) or len(set(actual_ids)) != len(actual_ids):
        return None
    # Jsoup's Element.text() collapses HTML whitespace and trims the result.
    return [re.sub(r"\s+", " ", "".join(text)).strip() for _, text in parser.spans]


def extract_google_translation_text(body: str) -> str | None:
    """Join the first translation column from Google's nested JSON response."""
    try:
        root = json.loads(body)
        segments = root[0]
        if not isinstance(segments, list):
            return None
        parts = [segment[0] for segment in segments
                 if isinstance(segment, list) and segment and isinstance(segment[0], str)]
        return "".join(parts)
    except (TypeError, ValueError, IndexError, KeyError):
        return None


def _retry_after_seconds(value: str | None) -> float | None:
    if not value:
        return None
    try:
        return max(0.0, float(value.strip()))
    except ValueError:
        try:
            retry_at = parsedate_to_datetime(value)
            return max(0.0, retry_at.timestamp() - time.time())
        except (TypeError, ValueError, OverflowError):
            return None


def _retryable_http_status(status: int) -> bool:
    return status in {408, 425, 429} or 500 <= status <= 599


class _GoogleChallengeError(Exception):
    pass


def _read_http_response(
    request: urllib.request.Request,
    opener: Callable = urllib.request.urlopen,
    timeout: float = 15.0,
) -> str:
    response = opener(request, timeout=timeout)
    try:
        status = getattr(response, "status", getattr(response, "code", 200))
        raw = response.read()
        if status < 200 or status >= 300:
            headers = getattr(response, "headers", None)
            raise urllib.error.HTTPError(
                request.full_url, status, "provider request failed", headers, None,
            )
        return raw.decode("utf-8") if isinstance(raw, bytes) else str(raw)
    finally:
        close = getattr(response, "close", None)
        if close:
            close()


def _request_google_text(
    url: str,
    *,
    opener: Callable | None = None,
    governor: ProviderRequestGovernor | None = None,
    sleeper: Callable[[float], None] = time.sleep,
    timeout: float = 15.0,
) -> str:
    active_opener = opener or urllib.request.urlopen
    active_governor = governor or _SHARED_GOOGLE_GOVERNOR
    request = urllib.request.Request(url)
    body = _request_with_retry(
        request,
        opener=active_opener,
        governor=active_governor,
        sleeper=sleeper,
        timeout=timeout,
        provider="Google",
    )
    lowered = body.lstrip().lower()
    if lowered.startswith(("<!doctype html", "<html")) or (
        "captcha" in lowered and "<" in lowered
    ):
        # Android opens a quota pause circuit for CAPTCHA/challenge bodies.
        raise _GoogleChallengeError("Google Translate challenge response")
    return body


def _request_with_retry(
    request: urllib.request.Request,
    *,
    opener: Callable,
    governor: ProviderRequestGovernor,
    sleeper: Callable[[float], None],
    timeout: float,
    provider: str,
) -> str:
    """Retry transient HTTP/transport failures, pacing each actual attempt."""
    last_error: Exception | None = None
    for attempt in range(1, PROVIDER_MAX_ATTEMPTS + 1):
        try:
            with governor.request_slot():
                return _read_http_response(request, opener, timeout)
        except urllib.error.HTTPError as exc:
            close = getattr(exc, "close", None)
            if close:
                close()
            last_error = exc
            if not _retryable_http_status(exc.code) or attempt >= PROVIDER_MAX_ATTEMPTS:
                raise
            retry_after = _retry_after_seconds(exc.headers.get("Retry-After") if exc.headers else None)
            delay = retry_after if retry_after is not None else (
                PROVIDER_BASE_BACKOFF_SECONDS * (2 ** (attempt - 1))
            )
            if delay > PROVIDER_MAX_INLINE_BACKOFF_SECONDS:
                raise
        except (urllib.error.URLError, TimeoutError, ConnectionError, OSError) as exc:
            last_error = exc
            if attempt >= PROVIDER_MAX_ATTEMPTS:
                raise
            delay = PROVIDER_BASE_BACKOFF_SECONDS * (2 ** (attempt - 1))
        sleeper(delay)
    raise last_error or RuntimeError(f"{provider} request retry exhausted")


def _google_envelope_url(source: str, source_language: str, target_language: str,
                         endpoint: str = GOOGLE_ENVELOPE_URL) -> str:
    params = urllib.parse.urlencode([
        ("client", "gtx"),
        ("sl", language_code(source_language, "ja")),
        ("tl", language_code(target_language, "en")),
        ("dt", "t"),
        ("q", source),
    ])
    return f"{endpoint}?{params}"


def _google_rl(value: int, pattern: str) -> int:
    result = value
    index = 0
    while index < len(pattern) - 2:
        shift_char = pattern[index + 2]
        shift = ord(shift_char) - ord("W") if "a" <= shift_char <= "z" else int(shift_char)
        shift_value = result >> shift if pattern[index + 1] == "+" else result << shift
        if pattern[index] == "+":
            result = (result + shift_value) & 0xFFFFFFFF
        else:
            result ^= shift_value
        index += 3
    return result


def calculate_google_token(text: str) -> str:
    """Port GoogleTranslator.calculateToken for Android's serial fallback URL."""
    value = 406644
    for byte in text.encode("utf-8", errors="replace"):
        value = _google_rl(value + byte, "+-a^+6")
    value = _google_rl(value, "+-3^+b+-f") ^ 3293161072
    if value < 0:
        value = (value & 0x7FFFFFFF) + 0x80000000
    token = value % 1_000_000
    return f"{token}.{406644 ^ token}"


def _google_single_url(text: str, source_language: str, target_language: str,
                       endpoint: str = GOOGLE_SINGLE_URL) -> str:
    params = [
        ("client", "gtx"),
        ("sl", language_code(source_language, "ja")),
        ("tl", language_code(target_language, "en")),
    ]
    params.extend(("dt", kind) for kind in (
        "at", "bd", "ex", "ld", "md", "qca", "rw", "rm", "ss", "t",
    ))
    params.extend([
        ("otf", "1"),
        ("ssel", "0"),
        ("tsel", "0"),
        ("kc", "1"),
        ("tk", calculate_google_token(text)),
        ("q", text),
    ])
    return f"{endpoint}?{urllib.parse.urlencode(params)}"


def _translate_google_single(
    text: str,
    source_language: str,
    target_language: str,
    *,
    opener: Callable | None,
    governor: GoogleRequestGovernor | None,
    sleeper: Callable[[float], None],
    endpoint: str,
) -> str | None:
    if not text.strip():
        return ""
    try:
        body = _request_google_text(
            _google_single_url(text, source_language, target_language, endpoint),
            opener=opener, governor=governor, sleeper=sleeper,
        )
        translated = extract_google_translation_text(body)
        return translated.strip() if translated is not None else None
    except Exception:
        return None


def translate_google_batch(
    texts: Sequence[str],
    target_language: str = "English",
    source_language: str = "Japanese",
    *,
    opener: Callable | None = None,
    governor: GoogleRequestGovernor | None = None,
    sleeper: Callable[[float], None] = time.sleep,
    envelope_endpoint: str = GOOGLE_ENVELOPE_URL,
    single_endpoint: str = GOOGLE_SINGLE_URL,
) -> list[str | None]:
    """Translate with strict gtx span envelopes, then Android's serial gtx path."""
    if not texts:
        return []
    chunks = plan_google_envelopes(texts)
    if chunks is not None:
        all_translations: dict[int, str] = {}
        envelope_valid = True
        for chunk in chunks:
            expected_ids = [f"b{index}" for index in chunk.block_indices]
            try:
                body = _request_google_text(
                    _google_envelope_url(
                        chunk.source, source_language, target_language, envelope_endpoint,
                    ),
                    opener=opener, governor=governor, sleeper=sleeper,
                )
            except Exception:
                # Android only invokes per-block fallback after an envelope
                # response fails strict reconstruction. A transport/quota
                # failure remains untranslated rather than sending more load.
                return [None] * len(texts)
            try:
                html = extract_google_translation_text(body)
                parsed = parse_google_envelope_html(html, expected_ids) if html is not None else None
            except Exception:
                parsed = None
            if parsed is None:
                envelope_valid = False
                break
            all_translations.update(zip(chunk.block_indices, parsed))
        if envelope_valid:
            return [all_translations.get(index) for index in range(len(texts))]

    # Any envelope mismatch or unfit block follows Android's per-block fallback
    # path for the complete page, preserving block-to-result alignment.
    return [
        _translate_google_single(
            text, source_language, target_language,
            opener=opener, governor=governor, sleeper=sleeper,
            endpoint=single_endpoint,
        )
        for text in texts
    ]


def make_openai_system_prompt(source_language: str, target_language: str) -> str:
    source = language_name(source_language)
    target = language_name(target_language, "English")
    lines = [
        f"You are a manga localization specialist. Translate the comic dialogue text blocks from {source} to {target}.",
        "",
    ]
    if source.lower() in _PRO_DROP_LANGUAGES:
        lines.extend([
            f"Note: {source} frequently omits subjects (pro-drop). Infer explicit subjects and maintain consistent character voice and pronouns.",
            "",
        ])
    lines.extend([
        "RULES:",
        "- Output format: `ID|Translated Text`, exactly one line per block.",
        "- Naturalize dialogue into lively spoken comic English, preserving tone and humor.",
        "- Localize sound effects (e.g. *gasp*, *thud*).",
        "- Output ONLY the `ID|Translated Text` lines. No preambles, markdown formatting, or explanations.",
        "",
        "EXAMPLE:",
        "Input:",
        "p0_b0|行く。",
        "p0_b1|あの日、彼と出会った。",
        "Output:",
        "p0_b0|I'm going.",
        "p0_b1|That day, I met him.",
    ])
    return "\n".join(lines)


def _flatten_source_line(text: str) -> str:
    return text.replace("\r\n", " ").replace("\r", " ").replace("\n", " ")


@dataclass(frozen=True)
class OpenAiTranslationChunk:
    block_ids: tuple[str, ...]
    user_prompt: str
    max_output_tokens: int
    estimated_prompt_tokens: int
    protocol_reserve_tokens: int


def _ai_protocol_reserve(block_count: int) -> int:
    return (
        AI_BATCH_RESPONSE_FIXED_OVERHEAD_TOKENS
        + AI_BATCH_RESPONSE_WHITESPACE_OVERHEAD_TOKENS
        + AI_BATCH_RESPONSE_PAGE_OVERHEAD_TOKENS
        + block_count * AI_BATCH_RESPONSE_BLOCK_OVERHEAD_TOKENS
    )


def _build_openai_chunk(
    blocks: Sequence[tuple[str, str]], requested_output_tokens: int,
) -> OpenAiTranslationChunk | None:
    prompt = "\n".join(f"{block_id}|{_flatten_source_line(text)}" for block_id, text in blocks)
    # Android counts with CL100K_BASE. UTF-8 bytes are an offline conservative
    # upper bound on content tokens; prompt overhead remains Android's 1,400.
    estimated_prompt_tokens = AI_PROMPT_OVERHEAD_TOKENS + len(prompt.encode("utf-8"))
    reserve = _ai_protocol_reserve(len(blocks))
    available = AI_MAX_CONTEXT_TOKENS - AI_SAFETY_MARGIN_TOKENS - estimated_prompt_tokens - reserve
    if available < AI_MIN_OUTPUT_TOKENS:
        return None
    output_tokens = min(max(requested_output_tokens + reserve, AI_MIN_OUTPUT_TOKENS), available)
    return OpenAiTranslationChunk(
        tuple(block_id for block_id, _ in blocks), prompt,
        output_tokens, estimated_prompt_tokens, reserve,
    )


def plan_openai_chunks(
    blocks: Sequence[tuple[str, str]],
    requested_output_tokens: int = AI_DEFAULT_OUTPUT_TOKENS,
) -> tuple[list[OpenAiTranslationChunk], list[str]]:
    """Plan the current page atomically under Android's context/output ceilings.

    Android packs complete pages into envelopes and rejects a page that cannot
    fit; it does not split one page's blocks across separate requests.
    """
    block_ids = [block_id for block_id, _ in blocks]
    if len(set(block_ids)) != len(block_ids):
        raise ValueError("request block IDs must be unique")
    if not blocks:
        return [], []
    page = _build_openai_chunk(blocks, requested_output_tokens)
    return ([page], []) if page is not None else ([], block_ids)


def stable_openai_block_indexes(
    geometries: Sequence[tuple[float, float, float, float]],
) -> list[int]:
    """Return Android's y/x/width/height order as request-local block indexes."""
    ordered = sorted(
        range(len(geometries)),
        key=lambda index: (
            geometries[index][1], geometries[index][0],
            geometries[index][2], geometries[index][3], index,
        ),
    )
    stable_indexes = [0] * len(geometries)
    for stable_index, original_index in enumerate(ordered):
        stable_indexes[original_index] = stable_index
    return stable_indexes


def _normalize_batch_id(raw_id: str) -> str:
    match = re.fullmatch(r"p0*(\d+)_b0*(\d+)", raw_id.strip().lower())
    if not match:
        return raw_id.strip()
    return f"p{int(match.group(1))}_b{int(match.group(2))}"


def parse_openai_translation_lines(
    raw_response: str, expected_ids: Sequence[str],
) -> dict[str, str] | None:
    """Strictly validate one nonblank ID-mapped line for every requested block."""
    expected = set(expected_ids)
    parsed: dict[str, str] = {}
    for raw_line in raw_response.splitlines():
        line = raw_line.strip()
        if not line or line.startswith("```"):
            continue
        if "|" not in line:
            # ContextualResponseParser has a narrow recovery rule for a model
            # that substitutes one punctuation/whitespace character for '|'.
            # It only salvages a requested canonical ID with nonblank content.
            recovered = re.fullmatch(r"(p\d+_b\d+)([^|\w])(.*)", line, re.IGNORECASE)
            if recovered is None:
                return None
            raw_id, text = recovered.group(1), recovered.group(3)
        else:
            raw_id, text = line.split("|", 1)
        block_id = _normalize_batch_id(raw_id)
        translation = text.strip()
        if block_id not in expected or block_id in parsed or not translation:
            return None
        parsed[block_id] = translation
    if set(parsed) != expected:
        return None
    return parsed


def translate_openai_compat_batch(
    blocks: Sequence[tuple[str, str]],
    target_language: str,
    endpoint: str,
    model: str,
    *,
    source_language: str = "Japanese",
    temperature: float = AI_DEFAULT_TEMPERATURE,
    requested_output_tokens: int = AI_DEFAULT_OUTPUT_TOKENS,
    opener: Callable | None = None,
    governor: ProviderRequestGovernor | None = None,
    sleeper: Callable[[float], None] = time.sleep,
    timeout: float = 120.0,
) -> dict[str, str]:
    """Post Android-style envelopes with the shared retry and pacing policy."""
    if not blocks or not endpoint.strip() or not model.strip():
        return {}
    system_prompt = make_openai_system_prompt(source_language, target_language)
    chunks, _rejected_ids = plan_openai_chunks(blocks, requested_output_tokens)
    active_opener = opener or urllib.request.urlopen
    active_governor = governor or _openai_request_governor(endpoint, model)
    results: dict[str, str] = {}
    url = endpoint.rstrip("/") + "/chat/completions"
    for chunk in chunks:
        payload = {
            "model": model,
            "temperature": temperature,
            "max_tokens": chunk.max_output_tokens,
            "messages": [
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": chunk.user_prompt},
            ],
        }
        request = urllib.request.Request(
            url,
            data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        try:
            body = _request_with_retry(
                request,
                opener=active_opener,
                governor=active_governor,
                sleeper=sleeper,
                timeout=timeout,
                provider="OpenAI-compatible",
            )
            data = json.loads(body)
            content = data["choices"][0]["message"]["content"]
            if not isinstance(content, str):
                continue
            parsed = parse_openai_translation_lines(content, chunk.block_ids)
            if parsed is not None:
                results.update(parsed)
        except Exception:
            # Failed/malformed chunks remain untranslated and are not cached.
            continue
    return results


def cache_valid_translations(
    page_cache: dict[str, str],
    targets: Sequence[tuple[str, str]],
    results: Mapping[str, str | None],
) -> list[dict[str, str]]:
    """Persist only nonblank, non-source-equal provider results."""
    accepted = []
    for region_id, source in targets:
        result = results.get(region_id)
        if not isinstance(result, str):
            continue
        translation = result.strip()
        if not translation or translation == source.strip():
            continue
        page_cache[region_id] = translation
        accepted.append({"id": region_id, "text": translation})
    return accepted
