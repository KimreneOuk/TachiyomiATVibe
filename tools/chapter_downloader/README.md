# Chapter Downloader

Standalone desktop GUI (tkinter, stdlib-only — no pip installs) for saving one
manga chapter as a folder of numbered images. Built to assemble test material
for the TachiyomiAT translation pipeline: the output folder opens directly in
`tools/translation_studio` as a chapter.

## Run

```bash
cd tools/chapter_downloader
python chapter_downloader.py                      # GUI
python chapter_downloader.py <chapter-url>        # GUI with URL prefilled
python chapter_downloader.py <chapter-url> <dir>  # headless, for scripts
```

In the GUI: paste the chapter URL, pick a folder with **Browse…** (the native
Windows dialog has *Make New Folder*; a typed path is created automatically if
missing), then **Download**. With *Create subfolder per chapter* on, files land
in `<dest>/<manga>_ch<N>/` (e.g. `risou-no-kanojo_ch43/`).

## Output

- Pages are renamed to zero-padded order-of-appearance: `01.jpg`, `02.jpg`, …
  so file order == reading order in any file browser and in translation_studio.
- Original extensions are preserved; a direct image URL downloads as a
  single-page "chapter".
- Parallel downloads (default 4), 3 retries with backoff per page, HTML error
  pages rejected, and *Skip existing files* makes re-runs a safe resume.

CLI flags: `--no-subfolder`, `--overwrite` (disable skip-existing), `--workers N`.
Last-used folder and options persist in `.settings.json` (gitignored).

## Site support — `sites.py`

All source-specific rules live in `sites.py`; the GUI is generic. Current
adapters (first match wins):

| Adapter | Matches | How it extracts |
|---|---|---|
| `direct-image` | URL ends in an image extension | downloads the URL itself |
| `rawkuma` | `rawkuma.net` | plain `<img>` tags on the chapter page (pages live on the `kuma.kyut.dev` CDN; browser UA + Referer sent regardless) |
| generic | anything | WordPress `ts_reader.run({...})` JSON → `<img>` tags (`src`/`data-src` variants) → broad URL sweep for JS-built readers |

Extraction filters out site chrome (logo/avatar/cover classes, favicon/icon/
banner URL patterns, analytics pixels like histats) and preserves document
order, which for manga is page 1..N right-to-left.

Verified against rawkuma.net chapter-43 of *Risou no Kanojo* (2026-09-12):
13/13 pages, all valid 726×1032 JPEGs, resume re-run kept all 13.

## Adding or fixing a site

1. `curl -A "Mozilla/5.0 …" "<chapter-url>" -o page.html` and inspect how the
   image URLs appear (plain `<img>`, `data-src`, JSON blob, JS-built).
2. Tune the shared regexes at the top of `sites.py`, or add an adapter class:
   set `name`, `matches()`, and (only if needed) `extract()`/`suggest_name()`,
   and register it in `ADAPTERS` before the generic fallback.
3. `extract()` must return URLs in reading order and must never raise — an
   empty list surfaces as "No chapter images found" in the UI.

## Limits

- No Cloudflare cookie handling: if a site serves a challenge page, resolution
  fails with "No chapter images found" (the challenge HTML contains no page
  images). Extend `sites.fetch_page()` headers if a site needs cookies.
- One chapter per download; run again for the next chapter (the settings
  persistence makes repeat runs quick).
