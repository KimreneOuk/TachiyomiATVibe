"""Stdlib-only chapter image fetching and extraction helpers."""

from __future__ import annotations

import re
import threading
import time
from dataclasses import dataclass
from http.client import HTTPException
from html.parser import HTMLParser
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import unquote, urldefrag, urljoin, urlsplit, urlunsplit
from urllib.request import Request, urlopen


USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
)
REQUEST_TIMEOUT_SECONDS = 20
REQUEST_DELAY_SECONDS = 0.4
RETRY_COUNT = 2


class DownloadError(RuntimeError):
    """A chapter could not be read or prepared for downloading."""


class DownloadCancelled(DownloadError):
    """The user cancelled an in-progress batch."""


@dataclass(frozen=True)
class DownloadResult:
    output_dir: Path
    image_count: int
    downloaded_count: int
    errors: tuple[tuple[int, str, str], ...]
    cancelled: bool


_VOID_ELEMENTS = {
    "area", "base", "br", "col", "embed", "hr", "img", "input", "link",
    "meta", "param", "source", "track", "wbr",
}
_READER_IDS = {"reading-content", "readerarea", "reader-content", "chapter-content"}
_READER_CLASSES = {
    "reading-content", "reading_area", "reading-area", "readerarea",
    "reader-area", "reader-content", "chapter-content",
}
_IMAGE_ATTRIBUTES = ("data-src", "data-lazy-src", "data-original", "src")
_GENERIC_BINARY_CONTENT_TYPES = {"application/octet-stream", "binary/octet-stream"}


class _ChapterHTMLParser(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.has_reader_container = False
        self.reader_images: list[str] = []
        self.other_images: list[str] = []
        self._stack: list[tuple[str, bool]] = []

    def _inside_reader(self) -> bool:
        return any(is_reader for _, is_reader in self._stack)

    @staticmethod
    def _is_reader_container(tag: str, attrs: dict[str, str | None]) -> bool:
        if attrs.get("data-image-data") is not None:
            return True
        element_id = (attrs.get("id") or "").casefold()
        classes = set((attrs.get("class") or "").casefold().split())
        return element_id in _READER_IDS or bool(classes & _READER_CLASSES)

    @staticmethod
    def _image_url(attrs: dict[str, str | None]) -> str | None:
        for attribute in _IMAGE_ATTRIBUTES:
            candidate = (attrs.get(attribute) or "").strip()
            if not candidate:
                continue
            if candidate.casefold().startswith(("data:", "javascript:", "about:")):
                continue
            parsed = urlsplit(candidate)
            if parsed.scheme and parsed.scheme.casefold() not in ("http", "https"):
                continue
            return candidate
        return None

    def handle_starttag(self, tag: str, attrs_list: list[tuple[str, str | None]]) -> None:
        attrs = dict(attrs_list)
        is_reader = self._is_reader_container(tag, attrs)
        inside_reader = self._inside_reader() or is_reader
        if is_reader:
            self.has_reader_container = True

        if tag == "img":
            candidate = self._image_url(attrs)
            if candidate:
                (self.reader_images if inside_reader else self.other_images).append(candidate)

        if tag not in _VOID_ELEMENTS:
            self._stack.append((tag, is_reader))

    def handle_endtag(self, tag: str) -> None:
        for index in range(len(self._stack) - 1, -1, -1):
            if self._stack[index][0] == tag:
                del self._stack[index:]
                break


def extract_image_urls(html: str, chapter_url: str) -> list[str]:
    """Extract distinct chapter image URLs in document order.

    When a known Madara reader container is present, images outside that
    container (such as covers and related manga) are ignored. Sites with a
    different container fall back to all images in the page.
    """
    parser = _ChapterHTMLParser()
    parser.feed(html)
    candidates = (
        parser.reader_images if parser.has_reader_container else parser.other_images
    )

    result: list[str] = []
    seen: set[str] = set()
    for candidate in candidates:
        absolute_url = urljoin(chapter_url, candidate)
        parts = urlsplit(absolute_url)
        if parts.scheme.casefold() not in ("http", "https") or not parts.netloc:
            continue
        normalized_url = urldefrag(urlunsplit(parts))[0]
        if normalized_url not in seen:
            seen.add(normalized_url)
            result.append(normalized_url)
    return result


def has_ajax_pagination_hint(html: str) -> bool:
    """Return true for common Madara chapter-content AJAX endpoint markers."""
    lowered = html.casefold()
    markers = (
        "madara_load_chapter_content",
        "action=lazy_load",
        "action%3dlazy_load",
        "wp-admin/admin-ajax.php?action=lazy_load",
    )
    return any(marker in lowered for marker in markers)


def filename_for(index: int) -> str:
    """Return the required 1-indexed, unpadded JPEG filename."""
    if not isinstance(index, int) or isinstance(index, bool) or index < 1:
        raise ValueError("Image index must be a positive integer")
    return f"{index}.jpg"


def chapter_output_dir(chapter_url: str, output_root: str | Path) -> Path:
    """Resolve the output folder as <root>/<manga slug>/<chapter>."""
    parsed = urlsplit(chapter_url)
    if parsed.scheme.casefold() not in ("http", "https") or not parsed.netloc:
        raise ValueError("Enter a complete http:// or https:// chapter URL")

    segments = [unquote(part) for part in parsed.path.split("/") if part]
    manga_index = next(
        (index for index, part in enumerate(segments) if part.casefold() == "manga"),
        None,
    )
    if manga_index is None or manga_index + 2 >= len(segments):
        raise ValueError(
            "Expected a Rawkuma chapter URL shaped like "
            "https://rawkuma.net/manga/<slug>/<chapter>/"
        )

    slug = _safe_path_component(segments[manga_index + 1])
    chapter = _safe_path_component(segments[manga_index + 2])
    return Path(output_root).expanduser() / slug / chapter


def _safe_path_component(value: str) -> str:
    value = value.strip()
    value = re.sub(r'[<>:"/\\|?*\x00-\x1f]', "_", value)
    value = value.strip(" .")
    if not value or value in (".", ".."):
        raise ValueError("The manga slug and chapter must be valid folder names")
    return value


class _PoliteFetcher:
    def __init__(self, cancel_event: threading.Event) -> None:
        self.cancel_event = cancel_event
        self._last_finished_at: float | None = None

    def get(self, url: str, referer: str | None = None) -> tuple[bytes, str]:
        last_error: Exception | None = None
        for attempt in range(RETRY_COUNT + 1):
            self._wait_between_requests()
            if self.cancel_event.is_set():
                raise DownloadCancelled("Download cancelled")

            headers = {
                "User-Agent": USER_AGENT,
                "Accept": "text/html,application/xhtml+xml,image/avif,image/webp,image/apng,image/*,*/*;q=0.8",
                "Accept-Language": "en-US,en;q=0.9",
            }
            if referer:
                headers["Referer"] = referer
            request = Request(url, headers=headers)
            try:
                with urlopen(request, timeout=REQUEST_TIMEOUT_SECONDS) as response:
                    body = response.read()
                    content_type = response.headers.get("Content-Type", "")
                return body, content_type
            except (HTTPError, URLError, TimeoutError, OSError, HTTPException) as error:
                last_error = error
                if attempt >= RETRY_COUNT or self.cancel_event.is_set():
                    break
            finally:
                self._last_finished_at = time.monotonic()

        if self.cancel_event.is_set():
            raise DownloadCancelled("Download cancelled")
        raise DownloadError(f"Request failed after {RETRY_COUNT + 1} attempts: {last_error}")

    def _wait_between_requests(self) -> None:
        if self._last_finished_at is None:
            return
        remaining = REQUEST_DELAY_SECONDS - (time.monotonic() - self._last_finished_at)
        if remaining > 0 and self.cancel_event.wait(remaining):
            raise DownloadCancelled("Download cancelled")


def _decode_html(body: bytes, content_type: str) -> str:
    match = re.search(r"charset\s*=\s*['\"]?([^;\s'\"]+)", content_type, re.IGNORECASE)
    encoding = match.group(1) if match else "utf-8"
    try:
        return body.decode(encoding, errors="replace")
    except LookupError:
        return body.decode("utf-8", errors="replace")


def _supported_image_format(image_data: bytes) -> str | None:
    if image_data.startswith(b"\xff\xd8\xff"):
        return "JPEG"
    if image_data.startswith(b"\x89PNG\r\n\x1a\n"):
        return "PNG"
    if image_data.startswith((b"GIF87a", b"GIF89a")):
        return "GIF"
    if image_data.startswith(b"RIFF") and image_data[8:12] == b"WEBP":
        return "WebP"
    if image_data.startswith(b"BM"):
        return "BMP"
    if image_data.startswith((b"II*\x00", b"MM\x00*")):
        return "TIFF"
    if image_data[4:8] == b"ftyp" and any(
        brand in image_data[8:32] for brand in (b"avif", b"avis")
    ):
        return "AVIF"
    return None


def _validate_image_payload(image_data: bytes, content_type: str) -> None:
    if not image_data:
        raise DownloadError("Server returned an empty response")

    normalized_content_type = content_type.split(";", 1)[0].strip().casefold()
    is_image_content_type = normalized_content_type.startswith("image/")
    is_generic_binary = normalized_content_type in _GENERIC_BINARY_CONTENT_TYPES
    if normalized_content_type and not (is_image_content_type or is_generic_binary):
        raise DownloadError(f"Server returned {content_type} instead of an image")

    image_format = _supported_image_format(image_data)
    if image_format is None:
        raise DownloadError(
            "Response did not have a recognized raster image signature"
        )


def download_chapter_images(
    chapter_url: str,
    output_root: str | Path,
    cancel_event: threading.Event,
    on_status=None,
    on_progress=None,
    on_image_error=None,
) -> DownloadResult:
    """Fetch a chapter and its images, continuing after individual image errors."""
    output_dir = chapter_output_dir(chapter_url, output_root)
    fetcher = _PoliteFetcher(cancel_event)
    if on_status:
        on_status("Fetching chapter HTML…")
    try:
        chapter_body, _ = fetcher.get(chapter_url)
    except DownloadCancelled:
        return DownloadResult(output_dir, 0, 0, (), True)
    except DownloadError as error:
        raise DownloadError(f"Could not fetch chapter page: {error}") from error

    html = _decode_html(chapter_body, "")
    image_urls = extract_image_urls(html, chapter_url)
    if has_ajax_pagination_hint(html):
        raise DownloadError(
            "This chapter advertises Madara lazy_load/AJAX pagination. The reader "
            "images are not safely available as a complete list in the page HTML, "
            "and this downloader does not implement that endpoint."
        )
    if not image_urls:
        raise DownloadError(
            "No reader image URLs were found in the chapter HTML. This page may "
            "load them through JavaScript/AJAX (for example Madara lazy_load); "
            "this downloader cannot fetch that content yet. Check that the URL "
            "is a chapter page."
        )
    if cancel_event.is_set():
        return DownloadResult(output_dir, len(image_urls), 0, (), True)

    try:
        output_dir.mkdir(parents=True, exist_ok=True)
    except OSError as error:
        raise DownloadError(f"Could not create output directory {output_dir}: {error}") from error

    errors: list[tuple[int, str, str]] = []
    downloaded_count = 0
    for index, image_url in enumerate(image_urls, start=1):
        if cancel_event.is_set():
            break
        if on_progress:
            on_progress(index, len(image_urls), image_url)
        try:
            image_data, content_type = fetcher.get(image_url, referer=chapter_url)
            _validate_image_payload(image_data, content_type)
            destination = output_dir / filename_for(index)
            destination.write_bytes(image_data)
            downloaded_count += 1
        except DownloadCancelled:
            break
        except Exception as error:
            error_text = str(error)
            record = (index, image_url, error_text)
            errors.append(record)
            if on_image_error:
                on_image_error(*record)

    return DownloadResult(
        output_dir=output_dir,
        image_count=len(image_urls),
        downloaded_count=downloaded_count,
        errors=tuple(errors),
        cancelled=cancel_event.is_set(),
    )
