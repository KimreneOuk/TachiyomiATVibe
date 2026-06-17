# OCR Engine Notes & Known Limitations

Notes from a deep investigation into PaddleOCR v6 small recognition quality
(June 2026). Captures what was fixed, what's a documented model limitation, and
the recommended engine choice per language/orientation.

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

## References

- [comic-translate ppocr module](https://github.com/ogkalu2/comic-translate/tree/main/modules/ocr/ppocr) — reference implementation used to validate the pipeline.
- [PaddleOCR discussion #15695](https://github.com/PaddlePaddle/PaddleOCR/discussions/15695) — maintainer on vertical-text support limits.
- [PaddleOCR discussion #14463](https://github.com/PaddlePaddle/PaddleOCR/discussions/14463) — "Can I set paddleOCR to read vertical text?"
- [PP-OCRv6 arXiv paper](https://arxiv.org/abs/2606.13108) — model tiers (tiny/small/medium) and parameter counts.
