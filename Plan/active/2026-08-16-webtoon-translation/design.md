# Design Document: Webtoon & Long-Strip Translation Pipeline

## Architecture Overview

```
                      [Input Page / Strip Bitmap]
                                  │
                  Is height / width >= 2.0 (Tall Strip)?
                         ├── YES ──► [WebtoonSlidingDetector]
                         │             │ Slices into 1:1.4 windows (250px overlap)
                         │             │ Runs OnnxPageTextDetector on each window
                         │             │ Global NMS deduplication
                         │             ▼
                         └── NO ───► [Standard 640x640 Detector]
                                       │
                                       ▼
                       [Page N Detections & Bounding Boxes]
                                       │
                    Any bubble intersects bottom border?
                         ├── YES ──► [WebtoonSeamStitcher]
                         │             │ Pairs Page N bottom + Page N+1 top
                         │             │ Reconstructs unified bounding box & full text
                         │             │ Emits unified TranslationBlock
                         └── NO ───► Standard Pipeline Flow
```

## Detailed Component Design

### 1. `WebtoonSlidingDetector` (`eu.kanade.translation.webtoon.WebtoonSlidingDetector`)
- **Trigger:** Evaluated when `bitmap.height / bitmap.width >= 2.0`.
- **Window Sizing:**
  - Window height: $W_{\text{height}} = \text{round}(\text{width} \times 1.4)$.
  - Overlap: $O = 250\text{ px}$.
  - Step size: $S = W_{\text{height}} - O$.
  - Number of slices: $K = \lceil (\text{height} - O) / S \rceil$.
- **Window Inference:**
  - Crops each window $k \in [0, K-1]$ using `BitmapPool`.
  - Runs `OnnxPageTextDetector.detect(crop)`.
  - Maps local coordinates $y_{\text{local}}$ to global coordinates: $y_{\text{global}} = y_{\text{local}} + (k \times S)$.
- **Global NMS / Box Merge:**
  - Boxes in overlap zones with $\text{IoU} \ge 0.45$ or containment are merged into a single bounding box:
    $$B_{\text{merged}} = [\min(x_1), \min(y_1), \max(x_2), \max(y_2)]$$

### 2. `WebtoonSeamStitcher` (`eu.kanade.translation.webtoon.WebtoonSeamStitcher`)
- **Seam Candidate Detection:**
  - Any bubble on Page $N$ with $\text{bbox.bottom} \ge \text{pageHeight} - 15\text{ px}$ is flagged as `bottomEdgeCandidate`.
- **Virtual Seam Canvas:**
  - Combines bottom $300\text{ px}$ of Page $N$ ($H_{\text{bottom}}$) and top $300\text{ px}$ of Page $N+1$ ($H_{\text{top}}$) into a $600\text{ px}$ tall seam canvas.
  - Detects and OCRs the unified bubble.
- **Unified Block Emission:**
  - Emits **one single `TranslationBlock`** for AI translation containing the complete sentence.
  - For rendering and inpainting:
    - Page $N$ receives the top portion with bottom clip.
    - Page $N+1$ receives the bottom portion with top clip.

### 3. Orientation & Panel Rules
- **Orientation:**
  - Korean (`TextRecognizerLanguage.KOREAN`) or `readingOrder == LTR_COMIC` or `height / width >= 2.0`:
    - Text is classified as **Horizontal LTR** (no $90^\circ$ CCW rotation on multi-line boxes).
  - Japanese (`TextRecognizerLanguage.JAPANESE`) with standard manga aspect ratio:
    - Retains **Vertical TTB** ($90^\circ$ CCW rotation for PaddleOCR + vertical glyph stacking).
- **Panel Detection Bypass:**
  - For Webtoons / Manhwa (`height / width >= 2.0` or `fromLang == KOREAN` or `readingOrder == LTR_COMIC`), `assignPanels()` is skipped.
  - Saves an entire 640x640 ONNX pass per page and avoids letterbox distortion.

### 4. Integration Across Execution Modes
- **Batch / Pre-Translation:** Sequential chapter loop pairs adjacent page seams.
- **Auto-Translation:** Rolling reader window ($P \rightarrow P+1$) resolves seams before the user scrolls past the boundary.
- **Manual Translation:** Single-page translation on Page $P$ peeks the header of Page $P+1$ if a bottom-edge bubble is detected.

## Memory & Safety Invariants
- Window crops and seam bitmaps are leased from `BitmapPool` and recycled immediately after inference.
- Max memory footprint for seam canvas: $\approx 2.4\text{ MB}$ (well within the 6 GB RAM budget).
