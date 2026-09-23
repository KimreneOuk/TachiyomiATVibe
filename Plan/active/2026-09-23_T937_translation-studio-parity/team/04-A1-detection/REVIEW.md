# A1 — Detection Parity Reconciliation: Independent Review

Review date: 2026-09-23. Reviewer: opencode/glm-5.3 (per task README model config).
Scope: worktree diff of `tools/translation_studio/{boxgeom.py, pipeline.py, static/app.js, static/index.html}` plus new `detection_artifacts.py`, `panel_detector.py`, `sliding_detector.py`, against PLAN.md A1, `spec/FILTER_SPEC.md`, and Android sources (read-only).

**Verdict: PASS WITH NOTES.** The parity claims check out against the Kotlin sources; the capture/replay architecture satisfies the A1 data requirements of FILTER_SPEC. Two P2 robustness/performance issues and one interim-design question should be addressed (or consciously accepted) before Gate 1.

## Verification performed

- Re-ran `evidence/selftest_detect.py` independently → `status: passed` (conf default 0.6, floor 0.05, 3 pages incl. synthetic tall page, 4 window merges, panel NMS 7→5, per-region panel/segmenter assignments present in `after_boxes.json`).
- Line-by-line parity diff of every ported algorithm against its Kotlin source (citations below).
- Measured detections.json footprint: 118,449 bytes for the 2 demo pages (~59 KB/page; `mask_cache` 17.7 KB + `models` 14.2 KB per page).
- Probe-verified tie/ordering behavior of the new dedup helpers (containment priority uses text-area divisor, matching Android).

## Findings (priority order)

### 1. [P2] Lock-free mutation of the shared detections cache from GET paths

`_regions_at_conf` (pipeline.py:760) is no longer a pure re-filter: it mutates the cached per-page dict (lifecycle reassignment at pipeline.py:792, 890; `derived_outputs` at 993; `decisions` at 1001). It is called **without `self.lock`** from `overview` (pipeline.py:385→399), `page_data` (1453→1457), and `overlay_image` (1391→1393). The server is a `ThreadingHTTPServer` (server.py:32), so a POST `/api/detect` (holding the lock, mid-`json.dumps` inside `_save_json`, pipeline.py:139) can interleave with a GET `/api/overview` or `/img/overlay` mutating the same structures. Worst case: a torn `detections.json` write — `_save_json` is a plain `write_text` with no temp+replace (pipeline.py:134-140; contrast the atomic `thumbnail_path` at 341-353) — and `_load_json` silently swallows corruption (pipeline.py:125-132), dropping the whole chapter cache on next open. The old `_regions_at_conf` was read-only, so this exposure is new in A1. Fix: take `self.lock` in those three getters (or split replay-mutation into a locked path).

### 2. [P2] detections.json rewritten on every detect call

pipeline.py:754 saves the entire chapter detections cache unconditionally after every `_regions_at_conf`, including pure cache hits. At ~59 KB/page (measured), a 200-page chapter is a ~12 MB JSON rewritten per call; `process_page` hits it at least twice per page (detect + ocr→detect), so chapter-wide processing churns hundreds of MB of redundant disk writes and adds serializable-size latency to every call. Fix: save only on capture/recapture or when a dirty flag is set.

### 3. [P2→decision] Tall-page segmenter runs twice; legacy inpaint path still non-sliding

Capture now runs the Android-parity per-window segmentation (pipeline.py:664-682) but deliberately skips `_bubble_mask_cache` for tall pages (69-693 comment). Consequently `bubble_masks()` (pipeline.py:1208-1233) later re-segments the **full tall image** in one pass for inpaint/render — a second ONNX pass per tall page and a non-Android mask set feeding the erase path. Acceptable as an explicit interim until A2 re-ports inpainting, but it deserves a ticket link or an explicit "known interim" note so it isn't mistaken for finished parity.

### 4. [P3] Dead legacy dedup helper retains a real parity bug

`dedupe_within_parents` (boxgeom.py:186-208) still computes containment as `intersection / parent.area` (boxgeom.py:199), while Android uses `intersection / text.area` (OcrBlockDeduplication.kt:135-140). The active path `dedupe_within_parents_with_suppressions` (boxgeom.py:220-224) is correct — verified. But the legacy trio (`greedy_dedup`, `suppress_cross_label`, `dedupe_within_parents`) is now called nowhere (pipeline only invokes the `*_with_suppressions` variants, pipeline.py:817/889/904), its docstring was rewritten to claim page-wide parity, and pipeline.py:52-55 still imports the dead names. Delete the trio (and the unused imports) or fix the divisor so nobody reuses a wrong-parity helper.

### 5. [P3] Silent recapture on stale cache

The stale-cache branch of `detect_page` (pipeline.py:747-751) re-runs all three models without any log line (the fresh branch logs at 745-746). A "recapture (stale)" log with the failing check would make Gate demos and cache-behavior audits visible.

### 6. [P4 nits]

- Stale conf fallbacks: `overview` pipeline.py:392, `inpaint_page` 1246, `page_data` 1454 still default `0.45` while `DEFAULT_SETTINGS`/UI now say 0.6. Dead in practice (defaults merged at 198-199) but inconsistent; align to one constant.
- The legacy v0 injection inside `_regions_at_conf` (pipeline.py:772-785) inserts keys into a possibly-stale cache dict during the same unguarded GET calls as finding 1 — same fix covers it.

## Parity verification detail (what was checked and confirmed)

| Area | Desktop | Android reference | Result |
|---|---|---|---|
| Conf default 0.6, raw floor 0.05, slider kept | pipeline.py:2084-2085, Detector floor | PLAN A1 mandate | ✓ (selftest asserts) |
| Box truncation `int(v)`, score 4dp half-up | pipeline.py:1543-1545 | OnnxPageTextDetector.kt:169-195 (`toInt()`, `Math.round(s*10000)/10000`) | ✓ fixed (was `round()`) |
| Detector dedup .75/.88/.12/.18, greedy desc-score, per (window,label) group | pipeline.py:813-824, boxgeom.py:100-127 | OnnxPageTextDetector.kt:197-237 (dedup inside per-window `detect`) | ✓ |
| OCR-stage dedup .62/.86/.12/.20 page-wide same-label, priority containment(text-area)→score | boxgeom.py:211-238 | OcrBlockDeduplication.kt:78-103, 135-140 | ✓ (active path; see F4 for the dead twin) |
| Cross-label suppression AFTER same-label dedup, same-parent identity, IoU>0.3, tie→later loses | pipeline.py:889-917, boxgeom.py:160-183 | RoiPageRecognitionEngine.kt:466-467, OcrBlockDeduplication.kt:37-76 | ✓ order fixed (old code ran xlabel before ocr-dedup) |
| Tall gate h/w≥2.0; windows w×1.4 round-half-up, overlap min(250,h/4) int-div, windowH clamp, overlap≤h/2, step, count=ceil((H−ov)/step), break rules; per-window y-shift | sliding_detector.py:25-50, 65-84 | WebtoonSlidingDetector.kt:33-84 | ✓ (integer semantics incl. Kotlin/Python int-div and stable rounding; float32-vs-float64 1.4 and the 0.6 threshold boundary analyzed — no divergence reachable with real page sizes/scores) |
| Merge: same-label, IoU≥.40 / containment≥.70 / seam (xOverlap≥.60 ∧ (yOverlap>0 ∨ gap≤20)), union box, max score, current-box re-comparison, tall-only | sliding_detector.py:87-144, pipeline.py:846-853 | WebtoonSlidingDetector.kt:92-147, 152-184 | ✓ plus auditable merge records with criterion |
| Panel detector: letterbox 640 pad 114 /255 NCHW, class-0, conf≥.5 (floor-0.05 raw capture), NMS IoU>.45 on model-space boxes, inverse letterbox + clamp | panel_detector.py:43-81, 101-119 | OnnxPanelDetector.kt:82-160, 216-232 | ✓ |
| Panel reading order: recursive XY-cut, event sort `coord*2−event` (starts before ends), gutter ≥1px, center split, RTL right-first, column fallback cx/y | panel_detector.py:122-172 | ReadingOrderSorter.kt:23-97 | ✓ exact |
| Panel assignment: max containment / text area, owned≥.80 / spanning≥.10 / free-floating / orphan / invalid; panelIndex only when owned; reading-order indices | panel_detector.py:175-210, pipeline.py:931-951 | PanelAssignment.kt:24-122, RoiPageRecognitionEngine.kt:933-993 | ✓ |
| Panel gating tall/Korean/LTR (assignment only, never OCR) | pipeline.py:501-525 | RoiPageRecognitionEngine.kt:940-946 | ✓ |
| Segmenter sliding windows + page-flat RLE projection (offset windowTop×pageW, bounds y+top, clamp bottom) | detection_artifacts.py:49-109, pipeline.py:664-682 | WebtoonSlidingDetector.kt:190-251 | ✓ |
| Mask↔block center-pixel first-match, int-truncated center | detection_artifacts.py:112-143 | RoiPageRecognitionEngine.kt:567-568, 605-607 | ✓ |
| Cache/provenance per FILTER_SPEC §1-2: per-model raw outputs (unrounded score, window, asset_sha12), lifecycle raw/kept/suppressed/merged/context, (loser_id, winner_id, rule, threshold) records, kept/context ids | pipeline.py:568-732, 760-1006 | FILTER_SPEC §1-2, PLAN A1 | ✓ A1 data-capture requirements met; display-time re-filter without re-runs verified (selftest conf 0.3 re-run, boxes unchanged) |
| Compat: legacy `boxes` rows preserved for inpaint/overlay/page_data; v0 caches recaptured via capture_version + asset SHA + gating-status checks | pipeline.py:711-714, 527-566, 747-751 | — | ✓ |
| File surface | git status: only `tools/translation_studio/**` + Plan folder | README constraint | ✓ no app/ changes |

## Evidence assessment

`evidence/selftest_detect.py` is genuinely offline (temp chapter, no network), covers normal + synthetic tall pages, asserts window containment of raw outputs, RLE bounds/length invariants, id integrity of all suppression/merge records, the cross-parent OCR-dedup fixture (the exact drift A1 fixes), re-filter-at-lower-conf without inference, and the default-conf/floor constants. `before/after_boxes.json` + `verification.json` are consistent with an independent re-run. Not covered (acceptable gaps, worth adding later): a real-pipeline xlabel/ocr-dedup suppression case on natural detections, and a `_detection_cache_is_current` transition test (settings flip → recapture).

## Follow-up re-review (post-fix, same day)

Re-checked every applied fix against the current worktree; selftest re-run → `status: passed`.

| Fix | Verification | Result |
|---|---|---|
| F1 lock | `self.lock = threading.RLock()` (pipeline.py:86); `_regions_at_conf` acquires it and all cache mutation + the save happen inside `_regions_at_conf_locked` under the lock; nested detect→replay calls exercised by selftest | ✓ resolved |
| F2 conditional save | replay-context tuple `(conf, panel_enabled, segmenter_enabled)` (pipeline.py:1007-1012, 1035-1038); probe: 3 same-conf replays → file untouched; conf change → rewrite; fresh captures persist via first replay (capture seeds `decisions.conf=None`) | ✓ resolved |
| F3 recapture log | "stale capture; recapturing model outputs" in the stale branch | ✓ resolved |
| F4 divisor + dead imports | `dedupe_within_parents` now `/ texts[i].area` (boxgeom.py:199-200) matching Android; dead trio no longer imported (pipeline.py:51-55) | ✓ resolved |
| F5 conf fallbacks | overview (pipeline.py:391) and page_data (1467) use `DEFAULT_SETTINGS["conf"]` | ✓ resolved (residual below) |
| F6 panel NMS order | candidates kept model-valid with `page_box_valid` flag (panel_detector.py:66-79); NMS on model boxes; page-invalid NMS survivors marked raw with an `unletterbox/empty-page-box` trace (pipeline.py:629-637); `kept_ids` filters them (645-647) — matches OnnxPanelDetector.kt:142-160 ordering | ✓ resolved, parity now exact |
| F7 tall-page interim | documented in team REPORT.md:106-110 as A2/Gate 1 wiring item | ✓ accepted |

Also noted as improvements in this iteration: label-0 raw records now get lifecycle state `context` at floor (better FILTER_SPEC §2 alignment), and derived label-0 traces use a `context` step instead of the misleading `det-dedup kept` (Android never dedups label 0 at the detector stage).

Residual nits (P4, non-blocking, no action required for Gate 1):

- `inpaint_page` fallback still spells `0.45` (pipeline.py:1276) — documented in REPORT.md known-gaps; the literal is dead in practice because `self.settings` always carries `conf` from `DEFAULT_SETTINGS`. The "outside A1's edit surface" rationale is weak (same file, one-line change) — fold into any later pipeline touch-up.
- `README.md:37` still documents "conf 0.45 default" — now factually stale; ensure D1's README rewrite picks it up.
- The save-context tuple omits `reading_order_rtl`: flipping RTL while panels stay enabled reorders panels in the in-memory replay correctly, but persisted `decisions` lag until the next context change. Display is always correct; persistence-only lag. Cosmetic.

**Updated verdict: PASS.** All P2/P3 findings from the initial review are resolved or consciously accepted with documentation; remaining items are P4 nits. A1 detection parity is ready for Gate 1.
