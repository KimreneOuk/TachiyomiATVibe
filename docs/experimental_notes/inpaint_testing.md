# Manga Translation Pipeline (Native Bitmap, No Heavy Models)

This document outlines the finalized architectural plan provided by the user, alongside a technical review of the implementation details required for the Android Kotlin codebase.

## User Review Required
> [!IMPORTANT]
> Your refined plan is **excellent** from an algorithmic and theoretical standpoint. It elegantly solves the gray text, overlap, and inpainting defect issues without relying on heavy ML models. 
> 
> However, implementing this plan requires a **massive architectural rewrite** of the Android app's translation pipeline. Before we commit to doing that in Kotlin, we will build a **Python Prototype** to validate that "Layout First, Then Inpaint" visually outperforms the current pipeline and justifies the rewrite.

## Phase 0: Python Prototype & Web Viewer (Current Focus)
We will build a Python prototype to process a batch of manga images and a web viewer to compare the results side-by-side.

### 1. Prototype Script (`prototype.py`)
- **Text Detection:** We will use `easyocr` or `cv2` contour detection to approximate the text bounding boxes and masks on your sample images.
- **Pipeline A (Current Android App):** 
  - Erase the full original text mask using `cv2.inpaint` (Telea).
  - Render a dummy English translation over the inpainted area.
- **Pipeline B (Proposed Layout-First):**
  - Render the dummy English translation to an off-screen mask.
  - Subtract the new text mask from the original text mask to get the "exposed" mask.
  - Inpaint *only* the exposed mask.
  - Render the English translation over it.
- **Output:** Saves the Original, Pipeline A, and Pipeline B images.

### 2. Web Viewer (`index.html`)
- A simple static website served locally.
- It will load the images from the output folder and display them in a 3-column grid (Original | Current Pipeline | Proposed Pipeline) so you can easily compare "tons of image runs".

---

## Phase 1: Kotlin Android Implementation (Pending Prototype Validation)

## Open Questions
> [!WARNING]
> 1. **Screentone Autocorrelation**: Writing a 2D autocorrelation tile-generator in pure Kotlin `Bitmap` operations (without OpenCV or native C++) is computationally expensive and complex. Would you be open to falling back to the existing **Telea Fast Marching Method** (which is already implemented natively in the app and very fast) for free-text textures, or do you strictly want the tile-copy algorithm?
> 2. **Pipeline Reorder**: Currently, `RoiPageRecognitionEngine` handles both OCR and inpainting in one pass. To reorder the pipeline, we must decouple them so `TranslationPipeline.kt` can do layout between OCR and Inpainting. This will touch the core pipeline logic.

---

## Finalized Plan

### 1. Pipeline Reorder – Layout First, Then Inpaint
**New order:**
1. **Estimate** bubble/free‑text background colours and textures (once).
2. **Plan** the final layout of all translated text (size, position, wrapping).
3. **Inpaint** only the *exposed* parts of the original text – i.e., the union of original masks *minus* the new text masks.
4. **Render** new text on top.

### 2. Background Estimation (The Core Colour Fix)
#### 2.1 For `bubble` boxes (speech bubbles)
- **Step A – Shrink** the bubble bounding box by 3% inward.
- **Step B – Remove** all child `bubble_text` regions.
- **Step C – Sample** the **mode** (most frequent colour). If mode luminance < 50, take mode of pixels with luminance > 80.
- **Step D – Store** as `bubbleBackgroundColor`.

#### 2.2 For `free_text` (text on artwork/screentones)
- Rely on **patch‑copy inpainting** or the existing Telea FMM algorithm. If a flat fallback is needed, use the border‑ring mode.

### 3. Inpainting (No Gray, No Flat Shine‑Through)
#### 3.1 Bubbles – flat fill with bubble background
- Mask = original text mask MINUS new text mask.
- Fill with `bubbleBackgroundColor` and feather the edges (1-2px Gaussian blur).

#### 3.2 Free text / screentones
- **Patch-copy (nearest-neighbour fill)**: Search outward in a spiral to find outside pixels and copy them in.
- **Screentone preservation**: Detect 2D autocorrelation on a 20x20 tile. If repeating, tile it. Otherwise, neighbour copy. *(Note: May fall back to Telea FMM if pure Kotlin implementation is too slow).*

### 4. Layout – Bigger Text, No Overlaps
#### 4.1 Bubble‑aware layout
- **Container**: The bubble bounding box itself (with 5% inner padding).
- **Placement**: Grow the font size until it fills the bubble width or collides. Expand downwards. If overflow, shrink stepwise.

#### 4.2 Overlap resolution without shrinking
- **Iterative expansion**: Place all blocks at minimum readable size, then grow in descending area order. Nudge sideways/upwards before shrinking font size.

#### 4.3 Free text blocks
- Strip newlines for wide horizontal text boxes (width > height * 1.5).

### 5. Text Colour Extraction
#### For bubble text:
- Subtract background (Euclidean distance to `bubbleBackgroundColor` < 30). Take mode of remaining pixels.
- If contrast < 3, snap to black (`#FF000000`) for light backgrounds or white (`#FFFFFFFF`) for dark backgrounds.

#### For free text:
- Use border-ring mode for background subtraction. If high variance, default to pure black or pure white based on overall box luminosity.

## Implementation Steps
1. **Refactor Pipeline**: Update `TranslationPipeline.kt` and `RoiPageRecognitionEngine.kt` to separate OCR from Inpainting.
2. **Update RenderColorEstimator**: Implement the mode-based background subtraction and color snapping.
3. **Update TextLayoutPlanner**: Implement iterative growth layout and newline stripping.
4. **Update SmartBubbleTextCleaner**: Implement exposed-mask inpainting, patch-copy, and tile-copy fallback.
