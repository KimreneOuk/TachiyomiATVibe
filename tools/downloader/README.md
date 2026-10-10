# Rawkuma Chapter Image Downloader

A standalone Python desktop tool for collecting chapter page images for local inpainting experiments. It uses only the Python standard library and is separate from the Android/Gradle build.

## Requirements

- Python 3.10 or newer
- Tkinter (included with most Windows Python installations)
- Network access to the Rawkuma chapter page and its image host

No `pip install` step is needed.

## Run

From the repository root:

```powershell
python tools/downloader/app.py
```

Paste a Rawkuma chapter URL such as `https://rawkuma.net/manga/<slug>/<chapter>/`. The default output root is `tools/downloader/output`; the downloader creates `<root>/<slug>/<chapter>/1.jpg`, `2.jpg`, and so on. Use the output-root field to choose another location. Downloaded output is ignored by Git.

The GUI shows the current image number, has a Cancel button, and lists each image failure while continuing with the remaining pages. Requests use a browser-style User-Agent, a 20-second timeout, two retries, and a 400 ms minimum pause between requests.

## Tests

Run the fixture-only unit tests from the repository root:

```powershell
python -m unittest discover -s tools/downloader/tests -v
```

The tests use a saved HTML fixture and do not make network requests.

## Site behavior and limits

The parser prefers images under Madara's `reading-content`/`readerarea` container or Rawkuma's `data-image-data` reader section, and supports `src`, `data-src`, `data-lazy-src`, and `data-original`. It resolves relative and protocol-relative image URLs. If a chapter page advertises Madara `lazy_load`/AJAX pagination, or has no image URLs in its HTML, the tool stops with an explanation because that endpoint is not implemented. If Rawkuma changes its reader markup, check the page source and update the parser markers.

Image data is written unchanged with the requested numeric `.jpg` filenames; the tool does not transcode formats. Some chapters may serve WebP bytes, in which case an image viewer that relies only on the filename extension may not open those files.
