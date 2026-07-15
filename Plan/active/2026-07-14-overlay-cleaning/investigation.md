# Investigation

## Verified call paths

- `PageRecognitionEngine.recognize()` calls `analyze()` then `inpaint()`.
- FAST constructs the shared inpainting engine but skips neural AOT session initialization; `AOTInpainting.inpaintRegions()` still runs bubble cleaning and push-pull free-text cleaning.
- QUALITY initializes AOT sessions when assets are available. Memory admission, neural candidate exhaustion, and the explicit preference can route individual groups to push-pull. The requested mode is not proof of the actual route.
- A successful cleaned bitmap is persisted before the reader exposes `displayImageName`.
- `ReaderPage.stream` selects `translatedStream` only when `showTranslatedImage` is true. `translatedStream` resolves to the cleaned file. Overlay readiness separately requires current cleaned output, translation readiness, and render readiness.

## Findings

1. The strongest concrete display defect was a lifecycle race: a holder could retain cached blocks while `setImage()` was replacing the page view, allowing a stale overlay to be rebound before the selected image finished decoding. The fix gates binding on the selected image's decode lifecycle and clears it when original image selection begins.
2. Existing corpus artifacts validate algorithm guards and FAST/QUALITY behavior but do not prove an Android stream mismatch. No current device log proves a broad pixel-level incomplete-inpaint defect.
3. The implementation intentionally stays narrow: mask composition, mode metadata propagation, and QUALITY route policy are not changed without runtime evidence.

## Rejected approaches

- Do not force QUALITY to neural-only for this task; QUALITY-to-push-pull fallback is an explicit, memory-aware route and changing it would expand scope.
- Do not erase all blank-OCR regions; current revision 10 intentionally preserves unread source pixels.
- Do not add a direct Android Bitmap/ONNX end-to-end unit test; the JVM test module has no lightweight Bitmap/session seam. Cover pure planner/state behavior instead.
