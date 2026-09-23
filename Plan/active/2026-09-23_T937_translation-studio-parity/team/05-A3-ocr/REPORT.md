# A3 — OCR provider parity

**Status:** Implemented. Model-backed Paddle detector verification is blocked by the absent LFS model payload.

## Changes

- Kept MangaOCR and PaddleOCR v6 as selectable providers; MLKit remains excluded. Paddle recognition now uses 48px inputs, 640/1600 width buckets with gray-128 padding, supported B1/B2/B4/B8 caps, original-order result restoration, and per-crop retry after a failed multi-crop run ([`paddle_ocr.py`](../../../../../tools/translation_studio/paddle_ocr.py#L214), [`pipeline.py`](../../../../../tools/translation_studio/pipeline.py#L1031)).
- Wired the max-batch setting into MangaOCR and PaddleOCR. MangaOCR's optional serial timing setting forces B1 only when enabled; its default is false, so normal accelerated batching stays available ([`pipeline.py`](../../../../../tools/translation_studio/pipeline.py#L1031), [`pipeline.py`](../../../../../tools/translation_studio/pipeline.py#L2259), [`index.html`](../../../../../tools/translation_studio/static/index.html#L493)).
- Added provider-specific recognition crops: MangaOCR keeps the detected box and PaddleOCR receives a page-clamped 12px pad ([`pipeline.py`](../../../../../tools/translation_studio/pipeline.py#L1674)).
- Added Paddle region planning and assembly for detector lines, vertical CJK split/rotate handling, ink-gap vertical fallback, confidence filtering at 0.5, ordered batched composition, and the unpadded reread path for an empty vertical result ([`paddle_ocr.py`](../../../../../tools/translation_studio/paddle_ocr.py#L359), [`paddle_ocr.py`](../../../../../tools/translation_studio/paddle_ocr.py#L399), [`paddle_ocr.py`](../../../../../tools/translation_studio/paddle_ocr.py#L471), [`paddle_ocr.py`](../../../../../tools/translation_studio/paddle_ocr.py#L532), [`paddle_ocr.py`](../../../../../tools/translation_studio/paddle_ocr.py#L578)). The existing DB fragment merge retains its three-pass and same-line/gap thresholds ([`paddle_ocr.py`](../../../../../tools/translation_studio/paddle_ocr.py#L33), [`paddle_ocr.py`](../../../../../tools/translation_studio/paddle_ocr.py#L98)).
- Added 13 model-free focused checks for preprocessing/buckets, batch fallback/order, line planning, vertical fallback/reread, confidence, MangaOCR decoding/preprocessing, settings wiring, and crop padding ([`selftest_ocr.py`](../../../../../tools/translation_studio/selftest_ocr.py#L72)).

## Verification

- `python -m py_compile tools/translation_studio/paddle_ocr.py tools/translation_studio/pipeline.py tools/translation_studio/selftest_ocr.py` — passed.
- `python tools/translation_studio/selftest_ocr.py` — 13 tests passed. Pillow emitted a deprecation warning in the existing `tools/mangaocr_lab/lab/preprocessing.py:44` call to `Image.fromarray(mode="L")`.
- `node --check tools/translation_studio/static/app.js` — passed.
- `git diff --check` — passed.

## Paddle detector evidence gap

The focused vertical-line tests use `FixedDetector` fixtures; they exercise region planning but do not establish real `PaddleDet.detect_lines` behavior ([`selftest_ocr.py`](../../../../../tools/translation_studio/selftest_ocr.py#L51), [`selftest_ocr.py`](../../../../../tools/translation_studio/selftest_ocr.py#L122)). A real free-text detector case could not run in this worktree: `PaddleDet` loads `app/src/main/assets/models/ocr/paddle-v6-small/det/inference.onnx` ([`paddle_ocr.py`](../../../../../tools/translation_studio/paddle_ocr.py#L174)), but that file is 132 bytes and contains only a Git LFS pointer (`oid sha256:d73e0058b7a8086bbd57f3d10b8bcd4ff95363f67e06e2762b5e814fe9c9410e`, declared payload size 9,880,512 bytes). No real Paddle `detect_lines` inference or free-text refiner path is claimed as verified. The demo page files also have no cached OCR/detection region to use as a real free-text input.

## Scope and remaining risk

No A1 detection or A2 inpaint files were changed. CPU ONNX inference and real-image refiner behavior remain unverified until the detector LFS payload is present; the model-free checks cover the surrounding ordering and region-planning behavior only.
