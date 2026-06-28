# Inpaint Parity Audit — Phase 0

Audit of `cci_inpaint.inpaint_coherent` + `server.py:/api/inpaint` against the
Android dispatch (`AOTInpainting.kt`, `BubbleMaskBuilder.kt`,
`LegacyFreeTextInpainter.kt`). Material gaps were patched in `server.py`.

## Divergence table

| Area | Android reference | Python (before) | Python (after patch) | Status |
|---|---|---|---|---|
| Parented text routing | `SmartBubbleTextCleaner.fillContained`; erase mask NOT clipped to bubble interior, bg sampled from interior | cci tiered FLAT/TEXTURED/COLOR + `parent_rect`-constrained bg sampling; erase mask (`build_cluster_mask`) padded+dilated, not clipped | unchanged | parity |
| Free-text routing | Paddle refine -> neural AOT (`inpaintFreeRegions`) or legacy (`LegacyFreeTextInpainter`) | sandbox legacy/coherent reused detect-stage boxes | `refine_free_text_boxes` re-runs Paddle for label-2 boxes in both legacy and coherent | patched |
| Per-region fallback | detector-v4 box when Paddle returns 0 lines (`refineFreeTextBoxes` `fallbackCount`) | detect-stage `fallback_boxes` | `refine_free_text_boxes` falls back to the detector box on 0 lines | patched |
| `PADDLE_CROP_PAD` | 12 (`AOTInpainting.kt:37`) | 12 (detect stage) | 12 (inpaint refine) | parity |
| `PADDLE_THRESH` | 0.18 (`AOTInpainting.kt:38`) | 0.2 (detect thresh, reused) | 0.18 (inpaint refine) | patched |
| `PADDLE_BOX_THRESH` | 0.34 (`AOTInpainting.kt:39`) | 0.34 | 0.34 | parity |
| `MASK_PAD` | 8 (`AOTInpainting.kt:40`) | 8 | 8 | parity |
| free-text refine guard pad | 4 px, free-text-only tight-box safety margin | none | 4 px (`FREE_TEXT_REFINE_PAD`) in Android + sandbox refine | patched |
| `FEATHER_RAMP_PX` (neural) | 12 (`AOTInpainting.kt:47`) | 4 (`CCIOptions.feather_ramp`) | 12 (`opts.feather_ramp = FEATHER_RAMP_PX`) | patched |
| `FEATHER_RAMP` (legacy free-text) | 3 (`LegacyFreeTextInpainter.kt:33`) | 3 (`FREE_TEXT_FEATHER`) | 3 | parity |
| `regionPad` | 10 (box pad before mask) | cci adaptive mask pad `clip(round(0.15*short),4,12)` | unchanged — equivalent coverage, different mechanism | note |
| `MAX_INFERENCE_DIM` | 512 (`AOTInpainting.kt:26`) | 512 (`aot_inpaint`) | unchanged | parity |
| dilate disk radius | 2 (`BubbleMaskBuilder.buildRectMask` `dilateRadius=2`) | 2 (`CCIOptions.dilate_radius`) | unchanged | parity |
| neural crop sizing | `clamp(long×3, 384, 512)` (`computeNeuralCrop`) | `clamp(long×3, 384, 512)` (`aot_inpaint`) | unchanged | parity |
| gray-fill guard | `AotOutputGuard.isSuspiciousGrayFill` | `cci_inpaint.is_uniform_output` (same variance/channel-delta rule) | unchanged | parity |

## Patches applied (`server.py`)

1. `refine_free_text_boxes(rgb, detector_boxes, thresh=0.18, box_thresh=0.34, crop_pad=12)` — mirrors `AOTInpainting.refineFreeTextBoxes`; back-projects Paddle line boxes to page coords, falls back to the detector-v4 box on 0 lines.
2. Legacy and coherent branches now rebuild the **free-text** (label 2) mask items from `refine_free_text_boxes`; parented (label 0/1) items keep their detect-stage paddle lines (Android `fillContained` consumes the OCR-stage group boxes).
3. Android and sandbox `LEGACY` now route all free-text boxes through the validated legacy local-ring + push-pull path. Coherent keeps AOT experimentation isolated from the viewer's default legacy behavior.
4. `opts.feather_ramp = FEATHER_RAMP_PX` (12) for the coherent neural/textured blend path. Legacy keeps `FREE_TEXT_FEATHER = 3`, matching `LegacyFreeTextInpainter`.
5. New constants: `PADDLE_INPAINT_THRESH`, `PADDLE_INPAINT_BOX_THRESH`, `PADDLE_INPAINT_CROP_PAD`, `FEATHER_RAMP_PX`.
6. `FREE_TEXT_REFINE_PAD = 4` expands Paddle-returned free-text line boxes only; parented/bubble line boxes are not dilated.
7. Diagnostics now report `paddle_refine` counts + `feather_ramp_px`.

## Known remaining divergences (out of Phase 0 scope)

- **Color estimation** — Android re-derives text/stroke colors post-inpaint via `RenderColorEstimator`; the Python render applies the hard-outline invariant with default black text. Phase 0 targets positions/sizes/wrapping/direction parity; color is a Phase 1+ concern.
- **neural crop centering** — Android centers the crop on the box *union* with `cropMargin = (targetCropLong - boxLongSide)/2`; Python `aot_inpaint` centers on the mask bbox. Equivalent in effect; minor sub-pixel centering differences when the mask != box union.
- **`regionPad` mechanism** — Android pads each box by a flat 10 px then dilates (disk r=2); cci uses an adaptive pad derived from the union short side. Coverage is similar; exact erase extents differ by a few px.
