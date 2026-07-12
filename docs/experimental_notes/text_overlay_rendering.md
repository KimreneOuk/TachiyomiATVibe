# Text Overlay & Rendering Architecture Decisions

> **Status:** Experimental — validated in the Manga Render Lab (`overlay_lab/`), not yet ported to Android.
> **Last updated:** 2026-07-10 (post-experimentation revision)
> **Companion doc:** `text_overlay_rendering_research.md` (full investigation, approach ranking, model I/O contract).

This document records the architectural and algorithmic decisions for the text overlay rendering system, revised after hands-on experimentation in the lab. The decisions below supersede the original plan where the experiments showed a different direction was needed.

---

## 1. Native DOM Overlay vs. Baked Rendering
**Decision**: Text is rendered as native HTML DOM elements (`<div>`) layered over the inpainted image, rather than being "baked" directly into the image pixels.
- **Why**: Baking text into the image subjects it to the same compression and resolution limits as the image itself. By rendering it on the DOM (lab) / `Canvas` (Android), the text remains infinitely crisp at any zoom level, and allows for interactive features.
- **Core project rule**: *We are not baking the inpainting onto the images but instead showing the overlay on top of the image with precise masking/position tracking.*
- **Proof of Concept**: In the lab, text is fully draggable/repositionable by the user, proving it is not baked in.

## 2. Reading Scalability & Zoom Strategy (Hybrid Approach)
**Decision**: A hybrid approach to font scaling depending on the user's action.
- **App Reading (Dynamic Mode)**: When the user is reading the manga in the app, the text is dynamically scaled using CSS `transform` (lab) / a single image-space `Matrix` (Android) relative to the current zoom level. This guarantees the text is always legible and sharp, regardless of how far the user is zoomed out.
- **Exporting (Hardcoded Mode)**: When the user exports the image to save or share, the system will apply a hardcoded font size and bake it into the final output image.
- **Zoom tracking mechanism**: Every text block is defined in **image (source) space** (`0..sWidth/sHeight`). Each frame applies **one** transform built from the image view's `scale + center`. Reuse the already-working `sourceToViewCoord()` path. This makes the "text locked to canvas/screen" bug structurally impossible.

## 3. Text-Bounding Policy: Segmentation Mask as Sole Limit  *(REVISED after experimentation)*

> **⚠️ This decision changed during experimentation.** The original plan used the YOLO detector-v4 parent bounding box (`parentBbox`) as the text limit, with the segmentation mask used only for inpainting. Hands-on testing in the lab showed the bbox approach caused two problems that the mask-sole-limit approach fixes. See §3.2 below for the revision history.

### 3.1 Current decision — mask-sole-limit
**Decision**: The **segmentation mask polygon's bounding box** is the sole strict limit on where translated text may grow.

- **Text is centered on the child OCR box** (where the source text was detected) and may expand up to the mask bbox, but **never past it — not even the stroke outline**.
- The detector-v4 parent bbox is **no longer consulted** as a text limit. It is a loose rectangle larger than the actual bubble; sizing text to it made text overflow the curved bubble edge.
- **Strict containment**: the clip rect is the mask bbox shrunk **inward by `strokeWidth/2`**, so the glyph body *and* its stroke outline both stay strictly inside the mask.
- **Connected-bubble separation**: the segmentation mask already separates distinct bubbles (each is its own YOLO detection with its own polygon). When two close bubbles are **fused into one mask polygon** by the seg model, the shared mask bbox is partitioned by child-box-center midpoints (dominant-axis perpendicular bisector cuts), so each child is fenced to its own slice. This is the **fused-mask split** (`splitMask`), not a parent-bbox split.

### 3.2 Why the policy changed (experimentation findings)

| Attempt | Policy | Result |
|---|---|---|
| **A1 (original plan)** | Parent bbox (`parentBbox`) as the text limit; mask for inpaint only. | ❌ Text overflowed curved bubble edges — the bbox is a loose rectangle, larger than the actual bubble. Text was also **shifted upward** because centering used the parent-box center, which differs from the true bubble center. |
| **Mask ∩ parent bbox** | Intersection of mask bbox and parent bbox. | ❌ Better shape adherence, but the parent bbox still pulled text away from center and the rectangular shape still didn't match the bubble contour. |
| **Mask sole limit** *(current* | Mask bbox only; child-box centering; fused-mask split for connected bubbles. | ✅ Text stays inside the true bubble contour. Text is centered where the source text was. Connected bubbles are separated by partitioning the shared mask. |

**Key lesson**: the mask is the true bubble shape; the bbox is a loose approximation. Using the mask as the limit produces tighter, more accurate containment. The mask is cheap in the lab (already computed once per page for inpainting; using it here is just a bbox intersection, <1ms/block). The cost caveat applies only to a future Android port at view time.

## 4. Symmetrical-First Flexible Growth Algorithm
**Decision**: The algorithm for determining text placement and size calculates growth dynamically based on free space, prioritizing symmetry.
- **Anchor**: The text always begins perfectly centered on its original Japanese OCR bounding box (the "child box").
- **Growth**: To fit legible English text (which is horizontal and requires more width than vertical Japanese text), the engine attempts to expand the bounding box symmetrically in all directions.
- **Limit**: Growth is capped by the segmentation mask bbox (§3). Text cannot grow past the bubble contour.
- **Obstacle Avoidance**: If the text hits the edge of its mask region or another text block, it seamlessly transfers its required growth to the opposite side. This safely shifts the text's center to prevent overflow without abandoning its relative position.

## 5. Next Steps / Pending Implementations
- **Dynamic Color Logic**: Implementation of the agreed-upon color rules based on inpaint background (e.g., White background → Black text with White outline; Dark background → White text with Black outline).
- **Visual quality tuning**: the current mask-sole-limit + fused-mask split is functional but "not perfect" — further iteration on the split heuristic (e.g. watershed distance-transform instead of midpoint cut) may improve connected-bubble edge cases.
- **Android port**: the full RAW ONNX mask decode (sigmoid → NMS → proto matmul → threshold) must be implemented in Kotlin; see `text_overlay_rendering_research.md` §6 for the I/O contract and risk analysis.
