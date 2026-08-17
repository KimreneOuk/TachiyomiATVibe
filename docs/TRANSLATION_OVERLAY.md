# Live translation overlay

The reader displays the persisted cleaned image and draws translated blocks in
native Canvas coordinates over the active `SubsamplingScaleImageView`. Pager and
webtoon holders both bind the same overlay path across reader modes. This avoids
producing a second full-page image
for every text update and lets a corrected block appear without waiting for a
new baked bitmap.

Batch pre-translation is readable page-by-page. The first-pass translation is
shown as soon as the cleaned image and translated blocks are ready, including
blocks marked for automatic revision. When delayed Pass 2 returns a correction,
the shared store updates and the reader refreshes the overlay for that page.
The batch progress surfaces expose the separate first-pass and revision phases;
“rendered” does not mean “revision complete.”

`PageTranslation` persists the cleaned image name, `inpaintRevision`, source
overlay dimensions, blocks, and normal stage state. `CURRENT_INPAINT_REVISION`
is versioned so stale cleanup output is reprocessed. Failed overlay binding is
logged with the page's existing reader error path; it never attempts to show
translated text over a missing cleaned image.

The Canvas renderer maps source coordinates through SSIV's `sourceToViewCoord` transform, redraws at most once per frame for pan/zoom updates, uses geometric 1.25× scale buckets to avoid layout churn, and renders the same vertical punctuation/layout rules as baked output.

## Inpainting and persisted masks

The persisted inpaint mask is the durable resume input. Current box-mask persistence is invalidated through `CURRENT_INPAINT_REVISION`; resume gates never accept stale cleanup. Bubble median sampling is restricted to the local ROI and uses fixed 256-bin channel histograms, snapping only very light (`luma > 220`) or very dark (`luma < 60`) fills. Free-text Push-Pull remains strictly ROI-local.

Every image/model/pipeline failure is logged. Overlay mode does not create a hidden second rendered bitmap or silently switch to one. Text fills are deliberately binary: light sampled backgrounds use pure black and dark sampled backgrounds use pure white, while the outline uses the inverse pole.

## Segmentation model attribution

The bundled bubble segmentation model is **huyvux3005/manga109-segmentation-bubble**, distributed under **AGPL-3.0**; its ONNX metadata also identifies the Ultralytics YOLO11 segmentation export as AGPL-3.0. The packaged asset is `models/segmentation/best_int8.onnx`, copied verbatim from `experimental/models/manga109-segmentation-bubble/best_int8.onnx`. Its raw ONNX contract is verified as `images=[1,3,640,640]`, `output0=[1,37,8400]`, and `output1=[1,32,160,160]`. Android decodes the class confidence, 32 prototype coefficients, sigmoid prototype matmul, NMS, thresholded masks, and inverse letterbox transform. Page-space masks are RLE-persisted with `PageTranslation`; their exact interiors determine bubble ownership, constrain cleanup, and clip baked rendering. Segmenter initialization/inference failures are logged and fail only the affected page; they do not silently fall back to rectangular bubble detections.
