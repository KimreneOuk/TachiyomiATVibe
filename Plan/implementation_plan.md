# Final Implementation Plan

This document outlines the final technical plan for the remaining translation service improvements. Please review the proposed changes and provide your approval to begin execution.

## 1. Prefetch for Webtoon / Long Strip Modes
Currently, prefetch is broken on non-Pager modes because the `ReaderPageWarmWindow` uses a static radius of 2, which falls behind during continuous scrolling.

### Proposed Changes
*   **Dynamic Radius:** Update `ReaderPageWarmWindow.kt` to calculate the warm window radius dynamically based on the current `ReadingMode`. Continuous modes (`WEBTOON`, `CONTINUOUS_VERTICAL`, `LONG_STRIP`) will scale to a radius of 4-5.
*   **Viewport Offset:** In Webtoon modes, the translation scheduler will offset its prefetch starting index by the number of currently visible pages on screen, ensuring the "warm window" always extends into the *unseen* pages below the viewport rather than just queuing pages already visible.

## 2. Webtoon / Long Strip Bounding Box Cut-offs
When Webtoon image tiles split a text bubble across their boundary, the OCR fails to read the complete text. We will implement the **Boundary Splicing** approach.

### Proposed Changes
*   **Edge Detection & Stitching:** In the `TranslationPipeline`, after `paddleDet` detects text bounding boxes, we check if any box intersects the top/bottom edge. If so, we dynamically load the adjacent `ReaderPage` from cache, crop a vertical slice, and stitch it to the current page's boundary in memory.
*   **Re-OCR:** Run OCR detection and recognition on the stitched region to capture the complete speech bubble.
*   **Native Canvas Clipping for Rendering:** To avoid duplicating text rendering across both image tiles:
    * `Page N` (top) and `Page N+1` (bottom) will independently detect the cutoff, stitch, and OCR the exact same text.
    * `Page N` calculates the full bounding box (e.g., `bottom = 120% of height`) and renders the full text. The Android `Canvas` will automatically clip the bottom half.
    * `Page N+1` calculates the bounding box in its local space (e.g., `top = -20% of height`) and renders it at a negative offset. The `Canvas` clips the top half, seamlessly merging visually with `Page N` in the `RecyclerView`.

## 3. Paddle OCR v6 Dynamic Recognition (Multi-language Support)
PaddleCTC currently fails on multi-line English text because hardcoded aspect ratio checks misclassify tall English paragraphs as a single vertical line of CJK text, bypassing line detection entirely.

### Proposed Changes
*   **Universal Line Detection:** Remove the hardcoded `isVerticalLanguage` and `boxHeightPre > boxWidthPre * 1.5f` checks on the main text bubble. *Always* run the `paddleDet` line detector on every text bubble crop to split paragraphs into component lines.
*   **Dynamic Orientation per Line:** Evaluate the aspect ratio *only* on the individual lines detected by `paddleDet`. For English, bypass CCW rotation for vertical boxes to avoid feeding sideways characters to the recognizer.
*   **Dynamic Spacing:** Add a helper to `TextRecognizerLanguage` based on the source language to determine if recognized lines should be joined with a space (English/Korean) or without a space (CJK).

## 4. Plug Native Memory Leaks in ONNX Models
`OnnxPageTextDetector.kt` and `AOTInpainting.kt` use heap-backed buffers, causing the ONNX Runtime to create native C++ copies that leak memory per page.

### Proposed Changes
*   **DirectBuffer Migration:** Migrate both models to use natively allocated direct buffers (via `DirectBufferPool`).
*   **Safety Cleanup:** Enforce explicit buffer cleanup in a `finally` block to prevent pool exhaustion if the pipeline crashes or is cancelled during high-speed scrolling.

## 5. Promote PaddleOCR Masking to Default
PaddleOCR-v6 inpainting masking is currently gated behind an experimental toggle. We will make it the permanent default.

### Proposed Changes
*   **Remove UI Toggle:** Remove `translationExperimentalPaddleMasking` from preferences and the Settings UI.
*   **Enforce Masking:** Update `RoiPageRecognitionEngine.kt` and `AOTInpainting.kt` to unconditionally use the paddle masking logic.

## 6. Text Rendering Visibility
Translated text must be clearly legible over complex backgrounds.

### Proposed Changes
*   **Strict White Outline:** Update `PageTextRenderer.kt` to enforce a strictly white outline (`0xFFFFFFFF`) for all rendered text, increasing the `strokeWidth` multiplier (e.g., to `0.12f` or `0.15f`).
*   **Dark Fill Clamping:** To prevent invisible "white-on-white" text, dynamically clamp the inner text fill color to a dark value (e.g., black or dark gray) whenever its luminance is too high.

## 7. Reader Settings UI Cleanup
The translation settings overlay in the reader is too cluttered.

### Proposed Changes
*   **Advanced Settings Toggle:** In `TranslationSettingsSheet.kt`, group the `LanguagesSection`, `InpaintSection`, and `EngineSection` behind a remembered "Show Advanced" toggle.
*   **Default View:** Only show core operational controls by default: Enable Translation, Auto Translation, Prefetch Slider, Queue, and Stop All Translation. 

## 8. Reader Animation/Resume Fix
When the user tabs out and back in, the translation animation gets stuck suspended.

### Proposed Changes
*   **Resume Translation on Foreground:** Add `viewModel.resumeTranslationsOnForeground()` in `ReaderActivity.onResume()`.
*   **ViewModel Trigger:** Implement the resume logic in `ReaderViewModel.kt` to re-kick `translateCurrentPageForAuto()`.
*   **Ready-State Kick:** Ensure pages that finish downloading (`Page.State.READY`) properly kick the UI to start animation for the very first page load.

---

> [!IMPORTANT]
> **User Review Required:** The plan now includes all edge-case safeguards found in our super investigation. If everything looks good, click **Proceed** and I will begin implementing!
