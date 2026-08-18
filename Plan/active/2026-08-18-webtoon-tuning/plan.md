# Webtoon-Specific Tuning Implementation Plan

Address speech bubble text overflow (media_1787063343108.png) and sliding/seam text loss in Webtoon/Manhwa mode, while strictly guaranteeing zero regressions for standard Manga.

## Global Constraints & Architecture
- Webtoon helpers are strictly gated by `WebtoonSlidingDetector.isTallImage(width, height) >= 2.0`.
- Standard manga pages (1:1.4 aspect ratio) bypass sliding window splitting and seam stitching entirely.
- Hard Bubble Envelope applies to all parented/masked speech bubbles (`hasParent || segmentationMask != null`), preventing text from growing outside white speech bubble boundaries.
- All 29 existing Manga unit tests must remain 100% passing.

## Proposed Changes

### 1. Rendering & Typography (`app/src/main/java/eu/kanade/translation/rendering/TextLayoutPlanner.kt`)
- **Hard Mask/ParentBox Envelope:** In `growIntoFreeSpaceIfNeeded`, check if the block is parented or has a segmentation mask (`hasParent || regionOverride != null || block.segmentationMask != null`). If true, disable external height growth outside the bubble boundary so text never spills over character artwork or panel borders.
- **Aspect-Aware Horizontal Ellipse Wrapping:** For wide horizontal speech bubbles ($W / H \ge 1.8$), adjust line wrapping width budget so text flows naturally into 2–3 wide lines (matching the Korean source layout) instead of collapsing into 5 narrow, tall lines.
- **Strict Mask Ceiling:** Ensure `binarySearchFontSize` always enforces `totalHeight <= safeH` and `maxLineWidth <= safeW`.

### 2. Webtoon Sliding Detection (`app/src/main/java/eu/kanade/translation/webtoon/WebtoonSlidingDetector.kt`)
- **Collinear Seam Merge in `mergeDetections`:**
  - When two detections of the same label overlap in the sliding window overlap band (250 px zone) and share horizontal span (collinear X-overlap >= 60%), merge them into a single union bounding box even if global IoU is low (>= 0.15).
  - Prevents speech bubbles on window seams from being split into two broken half-boxes.

### 3. Cross-Page Seam Stitching (`app/src/main/java/eu/kanade/translation/webtoon/WebtoonSeamStitcher.kt`)
- **Whole-Sentence Crossing Translation:**
  - Prevent `WebtoonSeamStitcher` from slicing raw Korean text before translation.
  - Keep the unified full OCR sentence intact on the crossing block so the translation engine localizes the complete thought.
  - Partition the translated lines during layout planning / render time across Page N and Page N+1.

### 4. Unit & Regression Tests
- `app/src/test/java/eu/kanade/translation/rendering/TextLayoutPlannerTest.kt`
- `app/src/test/java/eu/kanade/translation/webtoon/WebtoonSlidingDetectorTest.kt`

## Verification Plan
1. `./gradlew :app:testDevDebugUnitTest --tests "eu.kanade.translation.rendering.TextLayoutPlannerTest" --tests "eu.kanade.translation.webtoon.*" --no-daemon`
2. `./gradlew spotlessCheck --no-daemon`
3. Assemble & install APK on `192.168.100.223:32941`.
