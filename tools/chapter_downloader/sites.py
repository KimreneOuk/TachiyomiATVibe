"""Site adapters for the chapter downloader.

The GUI/CLI knows nothing about specific sites — every source-specific rule
lives here. When a new site misbehaves: probe it (curl the chapter page and
look at how the image URLs appear), then tune the shared regexes below or
add a dedicated adapter to ADAPTERS (most specific first).

Contract for every adapter:
  * extract() returns image URLs in READING ORDER (the order they appear in
    the page source, which for manga is page 1..N right-to-left).
  * extract() must never raise on unexpected HTML — return a possibly empty
    list; the UI surfaces that as "No chapter images found".

Verified against rawkuma.net (2026-09-12): chapter pages embed pages as
plain <img src="https://kuma.kyut.dev/.../N.jpg"> tags; the CDN does not
require a Referer, but one is sent anyway for other hosts.
"""
from __future__ import annotations

import html as html_mod
import json
import re
from dataclasses import dataclass
from urllib.parse import urljoin, urlparse
from urllib.request import Request, urlopen

USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
)

IMG_EXTS = (".jpg", ".jpeg", ".png", ".webp", ".avif", ".gif")

# --- extraction regexes -----------------------------------------------------

# Full <img> tags, then the candidate URL attributes inside each tag.
IMG_TAG_RE = re.compile(r"<img\b[^>]*>", re.I)
DATA_ATTR_RE = re.compile(
    r"""\b(?:data-src|data-lazy-src|data-original|data-cfsrc)\s*=\s*["']([^"']+)["']""",
    re.I,
)
SRC_ATTR_RE = re.compile(r"""\bsrc\s*=\s*["']([^"']+)["']""", re.I)

# Chrome (non-page) markers: in the tag's class attribute, or in the URL.
TAG_CHROME_RE = re.compile(
    r"""class\s*=\s*["'][^"']*(?:custom-logo|wp-post-image|avatar|emoji|score|rating|star|smiley)""",
    re.I,
)
URL_CHROME_RE = re.compile(
    r"(?:logo|favicon|sprite|placeholder|loading|gravatar|/avatar|/icons?/|banner|emoji"
    r"|stats|analytic|tracking|beacon|tagmanager|doubleclick|adserv|/ads?/|/pixel)",
    re.I,
)

# Last-resort sweep for JS-driven readers that build <img> tags dynamically.
SWEEP_RE = re.compile(
    r"""https?://[^\s"'<>\\)\]]+?\.(?:jpe?g|png|webp|avif)(?:\?[^\s"'<>]*)?""", re.I
)

# WordPress "MangaStream"-family readers embed the page list as JSON.
TS_READER_RE = re.compile(r"ts_reader\.run\((\{.*?\})\)\s*;?", re.S)


@dataclass
class ChapterInfo:
    adapter: str
    image_urls: list[str]
    referer: str
    suggested_name: str


# --- small helpers ----------------------------------------------------------


def normalize_url(url: str) -> str:
    url = url.strip().strip("\"'")
    if url and not urlparse(url).scheme:
        url = "https://" + url
    return url


def sanitize(name: str, limit: int = 120) -> str:
    name = _BAD_NAME_CHARS.sub("_", html_mod.unescape(name)).strip(" .")
    name = re.sub(r"\s+", " ", name)
    return name[:limit] or "chapter"


_BAD_NAME_CHARS = re.compile(r'[<>:"/\\|?*\x00-\x1f]')


def origin(url: str) -> str:
    p = urlparse(url)
    return f"{p.scheme or 'https'}://{p.netloc}/"


def _dedupe(urls: list[str]) -> list[str]:
    seen: set[str] = set()
    out = []
    for u in urls:
        if u and u not in seen:
            seen.add(u)
            out.append(u)
    return out


def _is_chapter_image(u: str) -> bool:
    if u.startswith("data:") or URL_CHROME_RE.search(u):
        return False
    return True


def extract_images(html: str, base_url: str) -> list[str]:
    """Image URLs from <img> tags (data-* first, plain src second), in order."""
    out: list[str] = []
    for tag in IMG_TAG_RE.findall(html):
        if TAG_CHROME_RE.search(tag):
            continue
        candidates = [m for m in DATA_ATTR_RE.findall(tag)] + [
            m for m in SRC_ATTR_RE.findall(tag)
        ]
        for raw in candidates:
            u = html_mod.unescape(raw.strip())
            if not _is_chapter_image(u):
                continue
            if not urlparse(u).netloc:
                u = urljoin(base_url, u)
            out.append(u)
            break  # one URL per <img> tag
    return _dedupe(out)


def extract_ts_reader(html: str) -> list[str]:
    """Image list from WordPress ts_reader.run({...}) style readers."""
    m = TS_READER_RE.search(html)
    if not m:
        return []
    try:
        data = json.loads(m.group(1))
    except json.JSONDecodeError:
        return []
    out: list[str] = []
    for source in data.get("sources", []):
        for u in source.get("images", []) or []:
            u = html_mod.unescape(str(u)).strip()
            if _is_chapter_image(u):
                out.append(u)
    return _dedupe(out)


def extract_sweep(html: str) -> list[str]:
    """Broad sweep for reader scripts that build tags in JS."""
    out: list[str] = []
    for u in SWEEP_RE.findall(html):
        u = html_mod.unescape(u)
        if _is_chapter_image(u):
            out.append(u)
    return _dedupe(out)


def _wp_chapter_name(url: str) -> str | None:
    """'.../manga/risou-no-kanojo/chapter-43.403741/' -> 'risou-no-kanojo_ch43'.

    The trailing '.NNNNNN' on the chapter segment is the site's internal id,
    not part of the chapter number, and is dropped.
    """
    parts = [p for p in urlparse(url).path.split("/") if p]
    if not parts:
        return None
    if "manga" in parts:
        i = parts.index("manga")
        manga = parts[i + 1] if len(parts) > i + 1 else ""
        chapter = parts[-1] if parts[-1] != manga else ""
    else:
        manga = ""
        chapter = parts[-1]
    chapter = re.sub(r"^chapter[-_ ]?", "", chapter, flags=re.I)
    chapter = re.sub(r"\.\d+$", "", chapter)
    bits = [b for b in (manga, f"ch{chapter}" if chapter else "") if b]
    return sanitize("_".join(bits)) if bits else None


# --- network ----------------------------------------------------------------


def fetch_page(url: str, timeout: int = 30) -> str:
    req = Request(url, headers={"User-Agent": USER_AGENT, "Accept": "text/html,*/*"})
    with urlopen(req, timeout=timeout) as resp:
        raw = resp.read(10 * 1024 * 1024)
        charset = resp.headers.get_content_charset() or "utf-8"
    return raw.decode(charset, errors="replace")


def fetch_bytes(url: str, referer: str | None = None, timeout: int = 30) -> bytes:
    headers = {"User-Agent": USER_AGENT, "Accept": "image/*,*/*"}
    if referer:
        headers["Referer"] = referer
    req = Request(url, headers=headers)
    with urlopen(req, timeout=timeout) as resp:
        return resp.read(64 * 1024 * 1024)


# --- adapters ---------------------------------------------------------------


class SiteAdapter:
    name = "generic"

    def matches(self, url: str) -> bool:
        return True

    def referer(self, url: str) -> str:
        return origin(url)

    def suggest_name(self, url: str) -> str:
        return _wp_chapter_name(url) or sanitize(urlparse(url).netloc)

    def extract(self, html: str, url: str) -> list[str]:
        return extract_ts_reader(html) or extract_images(html, url) or extract_sweep(html)

    def handle(self, url: str) -> ChapterInfo:
        page = fetch_page(url)
        return ChapterInfo(
            adapter=self.name,
            image_urls=self.extract(page, url),
            referer=self.referer(url),
            suggested_name=self.suggest_name(url),
        )


class DirectImageAdapter(SiteAdapter):
    """The URL is itself an image — download it as a single-page 'chapter'."""

    name = "direct-image"

    def matches(self, url: str) -> bool:
        return urlparse(url).path.lower().endswith(IMG_EXTS)

    def handle(self, url: str) -> ChapterInfo:
        stem = sanitize(urlparse(url).path.rsplit("/", 1)[-1].rsplit(".", 1)[0])
        return ChapterInfo(
            adapter=self.name,
            image_urls=[url],
            referer=origin(url),
            suggested_name=stem or "image",
        )


class RawkumaAdapter(SiteAdapter):
    """rawkuma.net — WordPress reader; pages live on the kuma.kyut.dev CDN."""

    name = "rawkuma"

    def matches(self, url: str) -> bool:
        return urlparse(url).netloc.lower() in ("rawkuma.net", "www.rawkuma.net")


ADAPTERS: list[SiteAdapter] = [DirectImageAdapter(), RawkumaAdapter(), SiteAdapter()]


def resolve(url: str) -> ChapterInfo:
    """Pick the first adapter that claims the URL and resolve the chapter."""
    url = normalize_url(url)
    for adapter in ADAPTERS:
        if adapter.matches(url):
            return adapter.handle(url)
    return SiteAdapter().handle(url)  # unreachable; SiteAdapter matches all
