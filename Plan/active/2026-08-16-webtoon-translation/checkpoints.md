# Implementation Checkpoints: Webtoon Translation

## Checkpoint 1: Sliding-Window Detector & Overlap Merge
- Implement `WebtoonSlidingDetector.kt` with pure coordinate projection and IoU box merging.
- Add focused unit tests verifying:
  - Non-tall image bypass (aspect ratio $< 2.0$).
  - Correct window coordinate projection for tall images.
  - Accurate IoU deduplication of bubbles in overlap zones.

## Checkpoint 2: Orientation & Panel Detection Bypass
- Update `RoiPageRecognitionEngine.kt` to:
  - Bypass `assignPanels` on webtoons (`aspectRatio >= 2.0` or `KOREAN` or `LTR_COMIC`).
  - Preserve Horizontal LTR for Korean/webtoons without 90° CCW rotation.
  - Preserve Vertical TTB for Japanese manga.
- Add unit tests verifying orientation selection across language and aspect ratios.

## Checkpoint 3: Cross-Page Seam Stitcher
- Implement `WebtoonSeamStitcher.kt` for pairing adjacent page boundaries.
- Add unit tests verifying:
  - Detection of edge-intersecting bubbles.
  - Seam composite and coordinate splitting between Page $N$ and Page $N+1$.

## Checkpoint 4: Pipeline & Store Integration
- Integrate sliding detection and seam stitching into `TranslationPipeline.kt` and `BatchCoordinator.kt`.
- Support seamless caching in `ChapterTranslationStore.kt`.
- Validate Batch, Auto, and Manual single-page execution paths.
