# B2 — Visualization upgrade

## Result

Implemented the FILTER_SPEC display-time filter and provenance UI in Translation Studio. The UI reads the A1 detection capture, OCR and translation cache records, and A4 inpaint provenance through `GET /api/artifacts?p=<page>`; changing filters only evaluates those cached records. The new route uses plain JSON reads of the following exact chapter files and existence checks for the two PNG assets: `.studio/detections.json`, `.studio/ocr.json`, `.studio/translations.json`, `.studio/inpaint/<page-stem>.json`, `.studio/inpaint/<page-stem>.png`, and `.studio/inpaint_mask/<page-stem>.png`. Missing or invalid sources include a status and reason. See `server.py:46-99,195-197`.

The filter panel provides the Production, All raw, Diagnosis: suppressed, and Inpaint audit presets; six source groups (text-detector, panel-detector, bubble-segmenter, paddle-det, OCR, inpaint); per-label toggles; live score ranges and shown/total counts; lifecycle and suppression-rule filters; and geometry, OCR, route, window, and translation dimensions. Grouped values are evaluated against each cached artifact record, and controls are disabled with source-specific explanations when cache fields are absent. Filter state persists through the existing settings endpoint. See `app.js:89-105,116-181,534-624,720-792,825-843` and `index.html:325-391`.

The SVG overlay draws detector and OCR boxes, Paddle-refined lines, mask component outlines, and source/route/artifact provenance badges. OCR text and cached translation are shown in SVG text badges controlled by the layer toggle. Near-misses can be dimmed, and detector candidates admitted only by a lowered score filter receive the “not processed at this threshold — rerun stage to cover” hatch and inspector note. Mask paths are memoized in a bounded LRU; filter updates re-render visible/current/focused pages and defer other pages, using one evaluation result for rendering and counts. See `app.js:895-1001,1031-1047,1866-1883` and `style.css:1518-1535`.

The click-through inspector shows the full normalized artifact record and lifecycle trace, including suppression and merge loser/winner/rule/threshold details, and displays A4 route, artifact ID, mask reference, component IDs, and provenance JSON when present. The inspector degrades cleanly when source or image data is missing; B1 per-region crop records are not assumed. Compare mode adds Original | Inpainted. See `app.js:1622-1709` and `index.html:489`.

## API and cache boundary

`GET /api/artifacts?p=<page>` is a read-only B2 endpoint. It reads the JSON sources and checks asset existence listed above; it does not write caches or call model-processing methods. The new handler is at `server.py:195-197`, with source reads and payload construction at `server.py:46-99`.

`GET /img/seg_overlay?p=<page>` serves a saved overlay if present. Otherwise, for a current A1 capture, it composites the cached page-space mask component outlines over the original page through the A4 segmentation helper; without a current capture it returns a transparent image. See `server.py:254-279`. A saved overlay is served as-is.

`POST /api/export-filtered` validates the open chapter and page, sanitizes the page/preset filename components, and writes the filter state and matching artifact records to `.studio/exports/<page>-<preset>-<timestamp>.json`. This is the only new route that writes files. See `server.py:328-359` and `app.js:1144-1157`.

## Verification

Commands run from the repository root:

```powershell
node --check tools/translation_studio/static/app.js
python -m py_compile tools/translation_studio/server.py Plan/active/2026-09-23_T937_translation-studio-parity/team/08-B2-visualization/evidence/smoke_b2.py
python -u Plan/active/2026-09-23_T937_translation-studio-parity/team/08-B2-visualization/evidence/smoke_b2.py
```

All passed. The headless smoke uses a temporary copy of `demo_chapter` and synthetic cache fixtures; it verifies artifact and missing-source responses, the dynamic `seg_overlay` composite, the badge toggle, All raw and suppression views, inspector decision details, lowered-score hatching, Original | Inpainted compare with both present and missing cached inpaint images, JSON export, and that filter changes issue no stage/process POSTs. Latest smoke results record 11 checks, zero browser errors, and 7 exported records. Evidence is in `team/08-B2-visualization/evidence/`: `smoke_b2.py`, `smoke_results.json`, `filtered_view.json`, and `smoke_ui.png`.

Independent review re-ran the smoke and cleared the implementation for commit with a PASS WITH NOTES verdict; F1 (B2 UI path), F2, and F3 were resolved after review. See `team/08-B2-visualization/REVIEW.md`.

## Spec deviations and cache-driven limits

No intentional display-semantics deviations from FILTER_SPEC were needed. `user-edited` translation provenance and the `pruned` lifecycle distinction are disabled with explanations because current `.studio/translations.json` records text without origin metadata and C2 prune records are not yet available (`app.js:580-604`). The inpaint inspector uses the A4 region route/provenance JSON and existing page-level saved images; it reports absent B1 per-region crop data rather than fabricating it (`app.js:1644-1708`).

The smoke uses synthetic cache outputs instead of a fresh run with real detector, OCR, Paddle, and inpaint models. The LFS-pointer Paddle artifacts in this worktree were not hydrated or staged.

## Remaining risk

The B2 client guards every Inpainted image producer with the cache asset flag; before artifact data is loaded, the flag is undefined and the UI uses Original (`app.js:78-81,1321,1339-1342,1660`). The smoke confirms cacheless p002 falls back to Original without requesting `/img/inpainted`. The pre-existing server `GET /img/inpainted` handler still invokes `PIPELINE.inpaint_page(page)` when called directly for a missing PNG (`server.py:236-249`); this is outside the B2 UI path and is documented for a future server-side fix. Review also records low-risk notes on the `seg_overlay` cache read locking, per-request parsing of chapter-wide JSON files, and transient artifact-fetch failures remaining cached until reset; see `REVIEW.md`. A real-model end-to-end run remains unverified.
