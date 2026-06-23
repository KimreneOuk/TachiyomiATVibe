# OCR Engine Notes & Known Limitations

Notes from deep investigations into the OCR engines (June 2026). Captures what
was fixed, what's a documented model limitation, the recommended engine choice
per language/orientation, and the MangaOcr ONNX decoder contract.

## TL;DR — recommended OCR engine per use case

| Content | Recommended engine | Why |
|---------|--------------------|-----|
| **Japanese manga** (vertical text) | **MangaOcr** | Purpose-built for Japanese manga; handles vertical text natively (resizes to 224×224, orientation-agnostic). This is the project's built-in default for Japanese. |
| **Chinese manhua** with **horizontal** text | **PaddleOCR v6 small** | Reads horizontal Chinese well (`【第3話】`, full sentences). |
| **Chinese manhua** with **vertical** text | **MangaOcr** (or bundle full PP-OCRv6 — see below) | PaddleOCR v6 *small* fails on vertical columns (documented model limit). |
| Latin / English text | **ML Kit** or **PaddleOCR v6 small** | Both handle horizontal Latin fine. |

The user has the freedom to choose any engine for any language (the original
two-bug fix in this branch — escape hatch + live config — ensures the choice
takes effect immediately).

## What was fixed (all committed)

Four genuine preprocessing bugs in the PaddleOCR pipeline, found and fixed via
on-device diagnostic logging + the reference implementation at
[ogkalu2/comic-translate `modules/ocr/ppocr`](https://github.com/ogkalu2/comic-translate/tree/main/modules/ocr/ppocr):

1. **Aspect-ratio destruction** (`PaddleOcrV6SmallEngine.preprocess`): the
   proportional recognition width was clamped with `.coerceIn(48, MAX)`, which
   forced every narrow crop UP to 48px wide — destroying the aspect ratio. A
   383×719 manga bubble became 48×48. Fixed: scale proportionally, cap only at
   MAX, pad the remainder.

2. **No vertical-text rotation** (`RoiPageRecognitionEngine`): PaddleOCR's CNN+CTC
   rec model reads horizontal text lines, but Japanese/Chinese manga text is
   vertical. The code detected vertical text for *rendering* (`direction="TTB"`)
   but never rotated the crop before OCR. Fixed: rotate tall CJK crops 90°
   clockwise before recognition (matches the official PaddleOCR pipeline per
   [discussion #15695](https://github.com/PaddlePaddle/PaddleOCR/discussions/15695)).

3. **Width floor too small** (reference-matched): recognition input was padded to
   only 16–32px wide; the reference pads every crop to a minimum of 320px (the
   model's training shape `(3, 48, 320)`). Fixed: pad to min width 320.

4. **Wrong padding color** (reference-matched): padded with white (normalizes to
   1.0); the reference pads with the normalization mean (gray 128 → normalized
   0.0, equivalent to `np.zeros` post-normalize). Fixed: pad with gray 128.

5. **Rotation threshold** (reference-matched): rotated at `h/w > 1.2`; reference
   rotates at `h/w >= 1.5`. Aligned to the reference.

These fixes are all **correct and match the reference pipeline**. They
materially improved **horizontal** text recognition. They did **not** fix
vertical-text recognition (see below).

## Known limitation: vertical text with PaddleOCR v6 *small*

Even after all five fixes, **vertical (rotated) manga text recognition remains
poor** — the model returns empty strings or Latin/ASCII garbage (`"RSORE"`,
`"Jauic"`, `"3duds"`) for rotated vertical columns, while horizontal text on the
same page recognizes correctly. Confirmed across both Chinese and Japanese test
pages.

This is a **documented limitation of the lightweight PP-OCR mobile models on
vertical text**, not a pipeline bug:

> *"PP-OCRv5 currently only supports the recognition of vertical text in ancient
> books, and the support for vertical text in other languages is relatively
> poor."* — PaddleOCR maintainer,
> [discussion #15695](https://github.com/PaddlePaddle/PaddleOCR/discussions/15695)

The maintainer-suggested workarounds (not implemented) are:
- **Per-character segmentation** of vertical columns before recognition (a
  Korean user confirmed this makes the mobile model work — each character is
  recognized correctly when cropped individually).
- **Fine-tune** the model on vertical manga text.
- **Use a different model**: MangaOcr for Japanese, or the **full** (non-small,
  34.5M-parameter) PP-OCRv6 rec model for stronger Chinese.

### Bundled PaddleOCR v6 small source package

The bundled PaddleOCR v6 small recognition asset is sourced from
`PaddlePaddle/PP-OCRv6_small_rec_onnx` on Hugging Face. The upstream files are
checked in under `app/src/main/assets/models/ocr/paddle-v6-small/`:
`inference.onnx`, `inference.yml`, `inference.json`, `README.md`, and
`.gitattributes`.

Runtime still reads `PP-OCRv6_small_rec.txt` as a plain UTF-8 dictionary instead
of parsing YAML on-device. That text file is the `PostProcess.character_dict`
from `inference.yml` flattened to one character per line; it has 18,708 entries,
matching the `inference.onnx` CTC class count of 18,710
(blank + 18,708 labels + space).

### PP-OCRv6 small **det** model — replacing the ink-gap column splitter

The vertical-text workarounds above (per-column splitting + 90° CCW rotation)
relied on an **ink-gap heuristic** (`RoiPageRecognitionEngine.detectVerticalColumns`)
to split a tall manga bubble into individual text columns. That heuristic is
brittle: it merges multi-column bubbles, splits on inter-character gaps, and
cannot see tilted/curved text.

It is now replaced — for the PaddleOCR rec path only — by the **PP-OCRv6 small
det** ONNX model (`PaddlePaddle/PP-OCRv6_small_det_onnx`, 2.48M params, ~10 MB),
checked in under `app/src/main/assets/models/ocr/paddle-v6-small/det/`. The det
model is a DB (Differentiable Binarization) text-line detector that runs inside
each Stage-1 ROI crop (the bubble boxes `detector-v4-s` already finds) and emits
precise text-line boxes. Stage-1 detection, the `Detection` label semantics
(0/1/2), and all inpaint/parent-bubble logic are unchanged — det is strictly a
Stage-2 refinement of the column split.

Key components:
- `DbPostProcess` — pure DB postprocess (threshold → connected components →
  axis-aligned bbox → unclip). Unit-tested in isolation.
- `DbPostProcess.mergeLineFragments` — **required fix** discovered during
  validation: the axis-aligned connected-components step over-segments a
  horizontal CJK line into one box per character (normal inter-character spacing
  becomes a component boundary). Without merge, a Chinese bubble reading
  `所因誤` came back as 5 fragments (`因誤`, `所`, `以`, `為`, `2`); after merge
  it correctly returns 2 lines (`所因誤`, `以為？`). Merge joins same-row
  horizontal fragments (and symmetrically same-column vertical fragments) and is
  iterated to a fixed point. Validated on both a Japanese vertical crop and a
  Chinese horizontal bubble — merge fixes horizontal without breaking vertical.
- `PaddleOcrV6DetEngine` — ONNX I/O glue + preprocess (BGR, resize-longer-to-736
  pad-square, ImageNet mean/std) + back-projection (map→crop coords).

The det model is **optional and best-effort**: if the asset is missing or fails
to load, `RoiPageRecognitionEngine` falls back to the ink-gap heuristic, so OCR
degrades to prior behavior instead of breaking. Per-ROI det failures also fall
back to the heuristic for that ROI (logged, never suppressed).

Design spec: `docs/superpowers/specs/2026-06-23-paddleocr-v6-det-onnx-integration-design.md`.

### How to investigate further (diagnostics already in place)

The per-block diagnostic logging added during this investigation is kept and
gated behind the existing `translation_diagnostics` preference (off by default).
When on, logcat shows, per text bubble:

```
[ocr_block] box=[x1,y1,x2,y2] size=WxH rotated=90cw text="<recognized>"
[paddle_ocr] total=Nms crop=WxH input=Wx48 chars=N text="<recognized>"
```

To capture for analysis (see `docs/build-and-install.md` for adb setup):
```
adb logcat --pid=$(adb shell pidof app.kanade.tachiyomi.at.debug) | grep ocr_block
```

Also requires `verbose_logging` = ON (Settings → Advanced) so the `logcat()`
logger is installed — without it, all `logcat()` calls are no-ops.

## MangaOcr ONNX decoder contract (do NOT regress)

MangaOcr (`MangaOcrEngine.kt`) is a **ViT encoder + GPT-2-style transformer
decoder** exported as three ONNX graphlets: `encoder.onnx`, `decoder_init.onnx`,
`decoder_step.onnx`. Understanding the decode loop is essential — it has been
broken twice by well-intentioned "fixes," each of which passed unit tests but
broke OCR on-device.

### How the autoregressive decode loop works

1. **Encoder** (`encoder.onnx`): the grayscale 224×224 crop → `encoder_hidden_states`.
2. **Decoder init** (`decoder_init.onnx`): takes `encoder_hidden_states` + the
   START token (`input_ids=[2]`), returns the first logits plus the initial
   self-attention KV cache (`self_k_init`, `self_v_init`) and the cross-attention
   KV cache (`cross_k`, `cross_v`). The self-cache is **copied into a pre-allocated
   `[4,1,4,MAX_LEN=256,64]` direct FloatBuffer** (`kCachePool`/`vCachePool`).
3. **Decoder step** (`decoder_step.onnx`): runs once per generated token. Inputs:
   `encoder_hidden_states`, `input_ids` (the last selected token), `position_ids`
   (absolute, 1..255), the **full** `self_k_cache`/`self_v_cache` buffers,
   `cross_k_cache`/`cross_v_cache`. Outputs: logits, and the updated KV slices
   which are written back into the cache buffer at `pos` via `writeCacheAtPos`.
4. **Loop** (`for (stepIdx in 0 until MAX_GENERATION_LENGTH)`): `pos >= DECODER_POSITION_COUNT`
   (128) is the ceiling — the position-embedding table size (see below). argmax runs
   over the **full vocabulary** (`logitsBuf.remaining()`). The loop stops at the END
   token (3) or at 128 positions.

### Why the position-embedding bound (`pos < 128`) is required

The `decoder_step` graph handles its own KV-cache offsetting internally, but the
**position-embedding Gather (`node_embedding_1`) is absolute and has exactly 128
entries** (indices 0..127). `position_ids` are fed as absolute values (1, 2, 3,
...), so once `pos` reaches 128 the Gather overflows:

```
ONNX recognition failed: ... Gather node 'node_embedding_1' ...
indices element 0 of shape [4] is not in the exclusive range [-128,127]
```

This crashes the chapter. The loop MUST be bounded by `DECODER_POSITION_COUNT`
(128), **not** `MAX_LEN` (256). `MAX_LEN` is the KV-cache sequence dimension,
which is *larger* than the position table — bounding by it lets `pos` reach 128
on long bubbles (128+ generated tokens) and crash. Confirmed on-device: the
`MAX_LEN` ceiling translated short pages fine but crashed on long-text bubbles;
`DECODER_POSITION_COUNT` fixes both. argmax remains full-vocabulary (the token
embedding does not overflow).

### The zero-fill trap (separate bug, also real)

During debugging, **zeroing the KV-cache buffers** was also attempted and
corrupted OCR independently of the position bound: zeroed K/V vectors made the
attention softmax spread uniformly across all 256 positions, producing
degenerate repetitive output (`viletetetetotetoteritetiteterinitijanijijan`).
This bug masked/confused the position-bound diagnosis for a long time, because
reverting the (correct) position bound while also reverting the (also-correct)
zero-fill revert made *short* pages work again — hiding the long-bubble crash
until a text-dense page surfaced it. **Both fixes are needed and both are
correct**: `pos < 128` AND no zero-fill.

### Process lesson

Two "fixes" were each individually validated by unit tests (the extracted
helpers were pure and correct in isolation) yet still broke or masked the
on-device behavior. The decoder loop's correctness depends on graph internals
that are not unit-testable. **Verify every change to this loop against a
text-dense manga page on-device, with `translation_diagnostics` on, reading the
`[ocr_block]` output and checking for `node_embedding_1` errors** — not against
unit tests alone.

### KV-cache buffer invariant (DirectBufferPool)

The `kCachePool`/`vCachePool` direct buffers are **reused across ROIs without
being zeroed** (`FloatBuffer.clear()` resets position/limit only). This is
correct: `MangaOcrEngine` overwrites every cache position it reads back, and the
decoder's causal attention depends on the cache contents. Zeroing the buffers on
acquire was attempted and **corrupted OCR** — zeroed K/V vectors made the
attention softmax spread uniformly across all 256 positions, producing
degenerate repetitive output (`viletetetetotetoteritetiteterinitijanijijan`).
Do not re-add zeroing to `DirectBufferPool.acquire`.

## References

- [comic-translate ppocr module](https://github.com/ogkalu2/comic-translate/tree/main/modules/ocr/ppocr) — reference implementation used to validate the pipeline.
- [PaddleOCR discussion #15695](https://github.com/PaddlePaddle/PaddleOCR/discussions/15695) — maintainer on vertical-text support limits.
- [PaddleOCR discussion #14463](https://github.com/PaddlePaddle/PaddleOCR/discussions/14463) — "Can I set paddleOCR to read vertical text?"
- [PP-OCRv6 arXiv paper](https://arxiv.org/abs/2606.13108) — model tiers (tiny/small/medium) and parameter counts.
- [manga-ocr-base (kha-white)](https://huggingface.co/kha-white/manga-ocr-base) — ViT encoder + GPT-2 decoder model; `config.json` documents the decoder (`model_type: gpt2`).
- [onnx-community/manga-ocr-base-ONNX](https://huggingface.co/onnx-community/manga-ocr-base-ONNX) — community ONNX export used as the mobile model source.
- [manga-ocr ONNX export issue #45](https://github.com/kha-white/manga-ocr/issues/45) — notes on exporting the decoder graphlets.
