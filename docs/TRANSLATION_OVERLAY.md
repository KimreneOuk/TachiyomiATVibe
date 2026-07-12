# Experimental pager translation overlay

The **Experimental live pager overlay** setting is disabled by default. When enabled, the interactive pager loads the persisted `.cleaned.png` background and draws translated blocks in native Canvas coordinates over the active `SubsamplingScaleImageView`. This avoids producing and decoding a second full-page `.rendered.png` for the pager path.

The overlay is deliberately pager-only. Animated pages have no stable SSIV transform and therefore must be baked before display. Webtoon does not attach a cleaned live-overlay result: it logs an explicit error and keeps the original page visible rather than silently presenting the cleaned image without its translated text. Batch translation remains baked so its output can be opened in any reader mode.

`PageTranslation` persists the cleaned image name, `cleanedRevision`, source overlay dimensions, blocks, and normal stage state. `CURRENT_INPAINT_REVISION` is versioned so stale cleanup output is reprocessed. Failed overlay binding is logged with the page's existing reader error path; it never attempts to show baked text over a missing cleaned image.

The Canvas renderer maps source coordinates through SSIV's `sourceToViewCoord` transform, redraws at most once per frame for pan/zoom updates, uses geometric 1.25× scale buckets to avoid layout churn, and renders the same vertical punctuation/layout rules as baked output.

## Inpainting and persisted masks

The persisted inpaint mask is the durable resume input. Current box-mask persistence is invalidated through `CURRENT_INPAINT_REVISION`; resume gates never accept stale cleanup. Bubble median sampling is restricted to the local ROI and uses fixed 256-bin channel histograms, snapping only very light (`luma > 220`) or very dark (`luma < 60`) fills. Free-text Push-Pull remains strictly ROI-local.

Every image/model/pipeline failure is logged. Overlay mode does not create a hidden second rendered bitmap or silently switch to one. Text fills are deliberately binary: light sampled backgrounds use pure black and dark sampled backgrounds use pure white, while the outline uses the inverse pole.

## Segmentation model attribution

The bundled bubble segmentation model is **huyvux3005/manga109-segmentation-bubble**, distributed under **AGPL-3.0**; its ONNX metadata also identifies the Ultralytics YOLO11 segmentation export as AGPL-3.0. The packaged asset is `models/segmentation/best_int8.onnx`, copied verbatim from `experimental/models/manga109-segmentation-bubble/best_int8.onnx`. Its raw ONNX contract is verified as `images=[1,3,640,640]`, `output0=[1,37,8400]`, and `output1=[1,32,160,160]`. Android decodes the class confidence, 32 prototype coefficients, sigmoid prototype matmul, NMS, thresholded masks, and inverse letterbox transform. Page-space masks are RLE-persisted with `PageTranslation`; their exact interiors determine bubble ownership, constrain cleanup, and clip baked rendering. Segmenter initialization/inference failures are logged and fail only the affected page; they do not silently fall back to rectangular bubble detections.
