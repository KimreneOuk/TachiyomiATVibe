# Brief: Webtoon & Long-Strip Translation Pipeline

## Objective
Enable high-accuracy OCR, AI translation, clean inpainting, and seamless text rendering for Webtoons, Manhwa, Manhua, and continuous long-strip comics in TachiyomiAT without regressions for traditional manga.

## Current Symptoms / Problem Statements
1. **Tall Strip Squashing:** Long continuous strips (aspect ratio $\ge 2.0$, e.g. $1000 \times 20,000\text{ px}$) are squashed into $640 \times 640$ model inputs in `OnnxPageTextDetector`, compressing text height by up to $20\times$ and collapsing detection confidence to $0\%$.
2. **Cross-Page Seam Slicing:** When webtoon chapters are divided into consecutive strip files ($001\text{.jpg}, 002\text{.jpg}\dots$), speech bubbles crossing the page seam are cut in half, producing fragmented OCR, broken AI translation context, visible inpainting borders, and split rendering.
3. **Mismatched Orientation & Panel Overhead:**
   - Stacked horizontal Korean/English multi-line bubbles in webtoons are incorrectly treated as tall vertical Japanese text and rotated $90^\circ$ CCW.
   - Unnecessary panel detection passes run on letterboxed continuous webtoon strips.

## Scope Boundary
- **In Scope:**
  - `eu.kanade.translation.webtoon.*` sliding window detector and cross-page seam stitcher.
  - Integration with `OnnxPageTextDetector`, `RoiPageRecognitionEngine`, `TranslationPipeline`, and `ChapterTranslationStore`.
  - Webtoon-aware orientation handling (preserving LTR for Korean/webtoons while keeping TTB for Japanese manga).
  - Webtoon panel-detection bypass (saving CPU/ONNX cycles).
  - Multi-mode support across Pre-translation (batch), Auto-translation (rolling reader window), and Manual single-page translation.
- **Out of Scope:**
  - Retraining ONNX models.
  - Modifying base Tachiyomi manga download formats or cloud extensions.

## Acceptance Criteria
1. Single tall strips of aspect ratio $\ge 2.0$ achieve $>98\%$ detection rate using aspect-preserving sliding windows with zero squashing.
2. Speech bubbles crossing adjacent page seams ($P_N \rightarrow P_{N+1}$) are detected, OCR'd, and translated as 1 unified sentence across Batch, Auto, and Manual modes.
3. Zero regressions for standard Japanese manga in Pager or Webtoon viewer modes.
4. Memory safety: bounded heap allocation conforming to the 6 GB RAM device budget.

## Constraints
- Android 8.0+ compatibility.
- Adherence to `TranslationMemoryBudget` and direct buffer pooling.
- Pure Kotlin / JVM testability for coordinate and layout math.
