# B2 — Visualization / Filter UI — Independent Review

Reviewer: independent B2 code-review agent (opencode/GLM-5.3), per `docs/roles/reviewer.md`
and the traycer-review skill. Scope: B2's uncommitted changes only —
`tools/translation_studio/server.py`, `static/app.js`, `static/index.html`,
`static/style.css`, plus B2 smoke evidence (`evidence/smoke_b2.py` and outputs).
Task contract: `README.md`; spec: `spec/FILTER_SPEC.md`; inputs: A1 report
(`team/04-A1-detection/REPORT.md`), A4 report (`team/06-A4-mask-planner/REPORT.md`).

**Verdict: PASS WITH NOTES (re-audited twice post-fix; cleared for commit).**
No CRITICAL/HIGH findings. F1, F2, and F3 from the first audit are RESOLVED
and smoke-verified (F1's B2 UI path verified fail-closed with no unguarded
`/img/inpainted` producer; the remaining item is pre-existing server-route
behavior, documented below). Remaining notes are LOW and non-blocking.

## Re-audit (post-fix) — verified against current source + smoke

Independent smoke re-run on the current files: **11/11 checks pass**, zero
browser errors, POSTs limited to `/api/settings` + `/api/export-filtered`,
and the captured `image_gets` trace shows `/img/inpainted` requested only for
p001 (which has the asset) and never for p002 (no asset). VERIFIED.

### F1 — display-only leak via `/img/inpainted` auto-run → RESOLVED (B2 UI path); pre-existing server behavior documented
- Status of the fixes (VERIFIED in source):
  - Single-view Inpainted mode now also selects its source through the guard:
    `app.js:1339-1342` (`imgSrcFor("inpainted", page)` + notice).
  - Compare `orig-inpainted` was already guarded (`app.js:1321,1330-1332`).
  - Smoke now proves the guarded path end-to-end: p002 compare falls back to
    `/img/original?p=p002.jpg` and the browser never requests
    `/img/inpainted?p=p002.jpg` (`smoke_b2.py:301-309`; confirmed by my
    re-run's `image_gets`).
- Retraction of the first re-audit's "scroll race" residual — that claim was
  wrong and is withdrawn: the guard fails closed. In `imgSrcFor`
  (`app.js:78-81`), `S.artifactData.get(page)?.assets?.inpaint_image`
  evaluates to `undefined` when artifact data has not loaded yet, which is
  falsy → `/img/original`. An audit of every `/img/inpainted` producer in the
  client finds no unguarded call path: `VIZ.inpainted.src` (`app.js:67`) is
  referenced only inside `imgSrcFor` (`app.js:81`), and the inspector crop
  URL is conditional on `assets.inpaint_image` (`app.js:1660`). Building a
  section before `/api/artifacts` resolves therefore yields the original
  image, never `/img/inpainted`. The unguarded `/img/inpaint_mask` consumers
  (`app.js:1360-1362`, viz mode at `app.js:3053` → `app.js:83`) are also
  safe: `inpaint_mask_image` is serve-or-blank with no pipeline call
  (`pipeline.py:1689-1694`).
- Remaining exposure (LOW, pre-existing server behavior, unchanged by B2 and
  outside its edit scope): the route `server.py:236-249` →
  `inpainted_image` (`pipeline.py:1683-1687`) still auto-runs
  `inpaint_page` when the PNG is missing, so (a) a direct/external GET of
  `/img/inpainted` for a cacheless page triggers a model stage, and (b) a
  check-then-request TOCTOU exists if the asset disappears between the
  client's existence check and the request. The B2 UI itself never issues
  the request unless the asset was present at check time. A serve-or-fallback
  server route would close (a) outright; noted for a future server-side
  ticket, not a B2 defect.

### F2 — dead "Text badges" toggle / lost inline OCR+translation → RESOLVED
- `artifactTextBadge` (`app.js:926-949`) draws cached OCR text + translation
  (`→ translation`) as SVG tspans for OCR region records, escaped, width- and
  page-clamped; gated on `S.tree.textBadges`; CSS at `style.css:1530-1532`.
- Smoke covers presence, content ("cached source line"), and the toggle
  hiding/showing the badges (`smoke_b2.py:239-245`). VERIFIED by re-run.
- Dead code removed: no `updateRboxText` and no `.pg-mask-layer` queries
  remain in `app.js`. VERIFIED by grep.
- Minor residual (LOW, cosmetic): dead `.rbox*` / `.pg-mask-layer` /
  `.show-ocr` CSS rules remain in `style.css:507-631`; the comment at
  `app.js:1394` ("persisted sessions") doesn't apply since DOM isn't
  persisted. Safe to prune later; non-blocking.

### F3 — per-tick RLE re-decode / full-page rebuilds → RESOLVED
- Bounded LRU path cache: `cachedMaskOutlinePath` (`app.js:892-913`) — max 2
  pages, 2M-char budget per page, LRU touch, invalidated on records rebuild
  (`app.js:500`) and `clearArtifactData` (`app.js:276,281`).
- `applyArtifactFilters` (`app.js:1031-1047`) now evaluates only the
  current/focus/visible pages, reuses one `evaluateArtifactStates` map across
  rendering and counts, and marks non-visible pages'
  overlays stale (`_hasOverlays = false`) for lazy rebuild via `applyOverlays`
  (`app.js:1501-1509`, guarded at `1504`). VERIFIED in source; slider input
  path now does O(visible) work per tick.

## Carried over unchanged from first audit (LOW, non-blocking)

- **F4** — `PIPELINE._cache` read without the pipeline lock in display
  endpoints (`server.py:262`; same pattern pre-existing at
  `pipeline.py:1701`). Worst case a transiently inconsistent overlay under
  concurrent replay; a locked snapshot accessor would remove it.
- **F5** — `/api/artifacts` reparses whole-chapter JSON per page request
  (`server.py:47-79`). Fine for demo chapters; add an mtime-checked parse
  cache when chapters grow.
- **F6** — transient artifact-fetch errors are cached until a stage/reset
  (`app.js:261-268` early-return at `250`); no retry on revisit.
- **F7** — evidence hygiene: `evidence/__pycache__/smoke_b2.cpython-311.pyc`
  should be excluded before merge; `smoke_results.json` /
  `filtered_view.json` / `smoke_ui.png` are regenerated per run (timestamps in
  `source_path` differ between runs — expected; my re-run at 14:34 local
  reproduced 11/11).

## Verification performed (cumulative, both audits)

- Full-diff read of all four B2 source files; every consumed field
  cross-checked against primary evidence: capture schema (`pipeline.py:537-740,
  842-1032`; `detection_artifacts.py:65-110`), inpaint provenance
  (`inpaint_android.py:216-334,1022-1148`; `execution.confidence_threshold`
  at `pipeline.py:1578`), translations.json shape (`pipeline.py:16,1206-1226`),
  settings passthrough for the `filters` key (`pipeline.py:210-227`),
  seg_overlay path (`pipeline.py:1390-1441,1697-1779`).
- Independent smoke re-runs: first audit (9/9) and post-fix re-audit (11/11,
  incl. new text-badge toggle check and p002 no-inpaint-request check);
  zero browser errors; browser POSTs limited to `/api/settings` +
  `/api/export-filtered`.
- Constraint compliance: `git status/diff` still limited to `server.py` +
  `static/*` — no pipeline/detection/inpaint/translator source changes;
  `/api/artifacts` is plain reads of existing `.studio` JSON;
  `/api/export-filtered` is chapter/page-scoped with sanitized filenames.
  VERIFIED.

## Spec conformance (FILTER_SPEC — unchanged conclusions, re-verified)

- §3: all dimensions present (source model groups + per-label toggles, dual
  score sliders, lifecycle multi-select, suppression rule, geometry incl.
  tall, OCR text/engine/outcome/conf, route, window, translation), AND across
  groups / OR within, honest disabling with reasons.
- §4: display-only confirmed by smoke (no model POSTs; `/img/inpainted` only
  requested when the asset exists); no-downstream hatch + inspector note;
  dim/ghost mode; live `shown/total` counts; four presets + custom.
- §5: filters persist via `settings.json`; presets reset to canonical
  definition; export writes `.studio/exports/<page>-<preset>-<ts>.json`
  schema `T937-FILTER_SPEC-1` with client-internal `_` fields stripped.
- §2: `pruned` / `user-edited` surfaced as disabled with explicit reasons
  (C2 pending; translations.json lacks origin metadata).
- Legacy caches: `legacy-det-*` box fallback; `/img/seg_overlay` returns blank
  for non-current captures rather than running the legacy segmenter
  (`server.py:269-273`).

## Evidence index

- Post-fix independent smoke re-run: 11/11 checks, `browser_errors: []`,
  POSTs `/api/settings`, `/api/export-filtered`, `/api/settings`; `image_gets`
  shows `/img/inpainted?p=p001.jpg` only (never p002).
- Fixture: `evidence/smoke_b2.py:51-153`; browser flow
  `evidence/smoke_b2.py:222-320`; text-badge check `:239-245`; p002 fallback
  + no-request assertion `:301-309`.
