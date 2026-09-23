# A1 — Detection parity and filter-ready capture

## Result

Implemented Android-aligned detection filtering and tall-page inference in
Translation Studio. Detection outputs are cached per source model with model
asset hashes, window provenance, stable page-local IDs, lifecycle traces, and
deduplication/merge records. Existing `detections.json` fields remain
available, with the new capture fields added alongside them.

## Thresholds verified and drift corrected

| Behavior | Android source of truth | Desktop change |
|---|---|---|
| Production text confidence | `app/src/main/java/eu/kanade/translation/detection/OnnxPageTextDetector.kt:169-195,224` filters NaN and scores below `0.6` before rounding retained scores to four decimals. | Changed settings and UI fallback/default from `0.45` to `0.6`; the detector still stores candidates down to `CONF_FLOOR = 0.05` for display-time re-filtering. `Detector.detect` now preserves `raw_score`, truncates coordinates like Kotlin `Float.toInt()`, and uses four-decimal Java-style rounding for the display/decision score. |
| Detector-stage text dedup | `app/src/main/java/eu/kanade/translation/detection/OnnxPageTextDetector.kt:197-237` applies thresholds IoU `>.75`, containment `>.88`, center `.12`, size `.18` independently to labels 1 and 2; label 0 bubbles are not in this dedup pass. | Threshold constants already matched. Corrected `_regions_at_conf` to apply this pass per `(window, label)` and emit `det-dedup` suppression records. Earlier desktop wiring deduplicated labels together and did not capture losers. |
| OCR-stage same-label dedup | `app/src/main/java/eu/kanade/translation/ocr/BoxGeometry.kt:60-77,98-103`; `app/src/main/java/eu/kanade/translation/ocr/OcrBlockDeduplication.kt:78-103,135-140` uses `.62/.86/.12/.20`, prioritizes text-box coverage by its center-selected parent (`intersection / text-box area`) then confidence, and compares same-label candidates across the page. | Constants already matched. Corrected the old desktop helper’s parent-scoped comparison and containment formula to page-wide same-label comparison and Android's text-box coverage formula; added winner/loser decision records. |
| Cross-label suppression | `app/src/main/java/eu/kanade/translation/ocr/OcrBlockDeduplication.kt:37-76` suppresses different labels only when their center-selected parent bubble is the same and IoU is `>.3`; equal confidence removes the later candidate. | Behavior retained and now emits `xlabel` records with threshold `.3`. |
| Tall-page windows and merging | `app/src/main/java/eu/kanade/translation/webtoon/WebtoonSlidingDetector.kt:22-68,92-147,152-184`: aspect ratio `>=2.0`, rounded width×`1.4` window height, overlap `min(250, target/4)`, projection by window top, same-label IoU `>=.40` / containment `>=.70` / horizontal overlap `>=.60` with vertical overlap or gap `<=20px`; union box and max score. | Added windowed detector/segmenter execution and same-label merge traces. Raw outputs stay in window-projected page coordinates; the merge record links each absorbed raw ID to the surviving ID. |
| Optional panel detector | `app/src/main/java/eu/kanade/translation/detection/OnnxPanelDetector.kt:88-114,146-151,217-224,262-274`: 640 square, gray 114 letterbox, NCHW `/255`, class 0 confidence `>=.5`, greedy NMS IoU `>.45`, then inverse letterbox. `app/src/main/java/eu/kanade/translation/ocr/RoiPageRecognitionEngine.kt:933-945` gates panel context for tall, Korean, and LTR pages. | Added optional panel capture and reading-order/assignment context. Missing asset disables the model cleanly. Panel outputs are advisory context and do not change text-region OCR order. |
| Bubble-mask assignment | `app/src/main/java/eu/kanade/translation/ocr/RoiPageRecognitionEngine.kt:605-607` assigns the first RLE containing the block-center pixel; `app/src/main/java/eu/kanade/translation/segmentation/OnnxBubbleSegmenter.kt:159-225` returns decoded `BubbleMaskRle` outputs; `app/src/main/java/eu/kanade/translation/webtoon/WebtoonSlidingDetector.kt:190-251` projects window RLE runs into page coordinates. | Added detection-side RLE center-pixel assignment and records `mask_ref`, `mask_component_id`, and segmenter source/window provenance on matching text regions. |

`boxgeom.py` threshold values themselves did not need correction. The drift was
in the desktop call scope and parent grouping, plus the OCR parent-containment
priority formula (the desktop divided by parent area; Android divides by text
box area). The separate page-space cross-label check remains same-parent and
uses strict IoU `>.3` as Android does.

## Capture format

`detections.json[page]` keeps `boxes`, `page_wh`, `model_ms`, `infer_ms`, and
`load_ms` in their existing shapes. New fields include `capture_version`,
`is_tall`, `windows`, and `models` entries for `text-detector`,
`panel-detector`, and `bubble-segmenter`. Each model entry records status,
asset name, 12-hex SHA-256, decoded outputs and timing as available. Box and
mask artifacts follow FILTER_SPEC §1 (`id`, `page`, `kind`, `source`,
`geometry` or `mask_ref`, `attrs`, `lifecycle`). Segmenter RLEs are cached in
`mask_cache.bubble-segmenter`; text candidates keep their raw model score and
window index. Changing the confidence threshold replays cached outputs and
does not call the models again.

Text, panel, and window-merge decisions persist `loser_id`, `winner_id`,
`rule`, and `threshold` records. Text raw and derived artifact lifecycle traces
also show floor, detector dedup, merge, OCR dedup, cross-label suppression,
panel assignment, and segmenter assignment decisions. Label-0 text-detector
boxes are retained as `context` and are not sent to OCR.

Decision replay now runs under the pipeline's reentrant cache lock. The chapter
cache is written after a model capture or when the replay context changes
(confidence or panel/segmenter assignment eligibility); repeated reads at the
same context do not rewrite the full detections file. Stale-cache recapture is
logged.

## File surface

- Modified: `tools/translation_studio/pipeline.py` (detection/cache replay,
  model capture, dedup wiring, and `Detector.detect`), `boxgeom.py`,
  `static/app.js`, and `static/index.html` (confidence default only).
- Added: `detection_artifacts.py`, `sliding_detector.py`, and
  `panel_detector.py`.
- Evidence: `team/04-A1-detection/evidence/before_boxes.json`,
  `after_boxes.json`, `verification.json`, and `selftest_detect.py`.
- Independent review: `team/04-A1-detection/REVIEW.md` (PASS WITH NOTES).

No OCR dispatch or inpaint implementation files were changed.

## Verification

- `python -m py_compile` passed for all five touched/new Python implementation
  modules and the evidence driver.
- `selftest_detect.py` ran direct `Pipeline.detect_page` calls on both
  `demo_chapter` pages and a synthetic vertical stack (900×2800, aspect 3.11).
- It confirmed all three model-cache keys and asset hashes, unchanged raw
  outputs after re-filtering at a lower confidence, page-space tall-window
  boxes and mask RLE runs, and suppression/merge winner and loser IDs against
  fixture artifact IDs. The real demo produced four tall-page merge records;
  it produced zero suppression records, so suppression provenance was checked
  with deterministic geometry fixtures.
- The selftest also covers same-label OCR duplicates across different bubble
  parents, block-center RLE component assignment with window provenance, and
  one cache write for a confidence change followed by zero writes at the same
  confidence.

## Integration gaps for Gate 1 merge

- Legacy text rendering still recomputes assignment in
  `tools/translation_studio/pipeline.py::render_regions` via
  `tools/translation_studio/segmentation.py::assign_region_component`
  (`pipeline.py:1871-1875`, `segmentation.py:371-396`). Merge-phase wiring
  should look up the matching region's detection-side `segmenter_assignment`
  and consume its `mask_ref` plus `mask_component_id`, with the current helper
  retained only as a fallback for old caches.
- Legacy bubble erase logic in
  `tools/translation_studio/aot_inpaint.py::inpaint_report_bubbles`
  (`aot_inpaint.py:288-333`) still builds a union mask and decides whether a
  bubble box is covered using its own 35% area-coverage test. It does not
  consume detection-side component IDs. The Gate 1 integration should pass the
  captured segmenter IDs into the replacement/inpaint planning interface where
  a text region needs component-specific treatment; the legacy bubble erase
  path remains its current global union-mask behavior until that wiring is
  agreed with A2.
- Captured segmenter records are decoded `BubbleMask` instances returned by the
  existing `segmentation.py` wrapper, not raw dense ONNX logits. This keeps
  cache geometry aligned with Android's public RLE output and avoids changes to
  the parallel inpaint work surface.
- Tall-page segmenter RLE is captured per window, but the legacy
  `bubble_masks()` consumer does not load those RLEs as dense masks. Its later
  inpaint/render request therefore runs segmentation again against the full
  tall image. A2/Gate 1 should wire captured page-space mask outputs into the
  replacement inpaint path; this ticket leaves legacy consumers untouched.

## Known gaps

- The legacy render and bubble erase consumers are not switched to the new
  assignment provenance in this ticket; see the Gate 1 integration items.
- Panel context is intentionally disabled for tall pages, Korean source, and
  LTR reading order; a missing/invalid panel asset stays disabled with a
  recorded reason.
- Text artifacts stop at detection-stage lifecycle. OCR text/outcome and
  inpaint route/consumption fields remain follow-on ticket work.
- The legacy `inpaint_page` fallback expression still spells `0.45`, but the
  method is outside A1's allowed edit surface and its settings are merged with
  `DEFAULT_SETTINGS` (now `0.6`) before use; detection-facing overview/page
  defaults use the shared `DEFAULT_SETTINGS` value.
