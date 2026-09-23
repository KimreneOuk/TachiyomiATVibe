# A3 — OCR provider parity

**Status:** Implemented. Real Paddle detector and A2 free-text refiner behavior are verified using temporary detector-model hydration. PaddleRec and end-to-end OCR remain unverified because the recognition model was not hydrated.

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

## Real Paddle detector and A2 refiner evidence

The original A3 worktree held only a 132-byte Git LFS pointer for the detector, with payload oid `sha256:d73e0058b7a8086bbd57f3d10b8bcd4ff95363f67e06e2762b5e814fe9c9410e` and expected payload size 9,880,512 bytes. For this probe, that one ONNX asset was copied temporarily from the A2 worktree into the A3 model path, then the original pointer bytes were restored. The model file is not part of the commit.

The reproducible [probe script](evidence/paddle_detector_probe.py) drew `FREE TEXT` using Arial Bold onto an 800×360 synthetic page, then invoked the real A3 `PaddleDet.detect_lines` at A2's `.18/.34` thresholds. The detector returned `[37, 32, 386, 62, 0.9445974449473197]`. The script next called A2 `inpaint_page_android` from worktree commit `bcc6846b398322d2aa1188380a3c58e9807185b1` with the synthetic region labeled 2 and the same real detector. A2 called it on a 447×119 padded crop at `.18/.34`; it returned `[49, 43, 395, 74, 0.9376340302767382]`, recorded a `paddle-refined` source box `[225, 127, 571, 158]`, and routed the region as `freetext/opencv`. The inpaint mask contained 13,012 pixels.

Machine-readable output and visual inputs/results are saved as [`paddle_free_text_probe.json`](evidence/paddle_free_text_probe.json), [`paddle_free_text_fixture.png`](evidence/paddle_free_text_fixture.png), [`paddle_free_text_inpainted.png`](evidence/paddle_free_text_inpainted.png), and [`paddle_free_text_mask.png`](evidence/paddle_free_text_mask.png). The JSON records the detector model SHA-256, the A2 module path, crop dimensions, thresholds, lines, provenance, and route.

## Scope and remaining risk

No A1 detection or A2 inpaint source files were changed. The real detector and A2 free-text refiner route are now verified on the synthetic crop. The Paddle recognition ONNX is a separate LFS pointer and was not hydrated, so this evidence does not verify full Paddle recognition or end-to-end OCR output. Model-free OCR planning/batching behavior remains covered by the 13 focused tests above.
