# T937 D1 — Translation Studio UI Refactor

**Status:** Implemented and verified
**Branch:** `t937-d1-ui-refactor`
**Base:** `main@9444276`
**Scope:** `tools/translation_studio/` plus this D1 evidence and report.

## Implementation

- Reorganized the Studio around Detect → OCR → Translate → Inpaint → Render. Each stage has a cached status, timing, Studio dispatch count, rerun action, and relevant parameters. The Overview now reports per-stage time, model load time, and Studio dispatches.
- Split the former `app.js` monolith into seven classic load-order scripts under `static/modules/`. `python studio.py` still serves them directly; no build step or bundler is needed. The legacy `static/app.js` is not loaded by the page.
- Fixed phone and responsive layout issues: top/status bars wrap and truncate safely; the fixed rail collapses at narrow widths; the viewer retains usable space; controls and filters wrap; dialogs and chapter flyout stay within the viewport; and phone controls meet the 24px target. OCR and provenance badges use shared collision-aware placement.
- Kept B2 filters and cached visualization behavior display-only, and preserved B3 variant selection, provenance, and controls. The stage settings and Advanced Settings fields are wired to the existing settings API; mask preview opacity is stored as a browser display preference.
- Rewrote the README to describe active inpainting, available engines and leg matrix, variants and provenance, filters, metrics, and saved data. Added the server MIME mapping for nested `.js` assets after review found they were served as `application/octet-stream`.
- No pipeline, inpaint, OCR, or translation logic was changed.

The stage cards use the requested review order. **Run Page** follows the current production pipeline order, which runs Inpaint before Translate after OCR; this distinction is documented in the README.

## Layout acceptance

Pass 3 ran against a temporary synthetic chapter fixture using the UI audit rubric. The final run produced **20 snapshots, 0 findings, 0 blockers, and 0 minor findings**. The five required viewport checks were:

| Viewport | Document width / client width | Horizontal overflow | UI and badge collisions |
|---|---:|---:|---:|
| 1920×1080 | 1920 / 1920 | 0 | 0 |
| 1366×768 | 1366 / 1366 | 0 | 0 |
| 1024×768 | 1024 / 1024 | 0 | 0 |
| 824×1100 | 824 / 824 | 0 | 0 |
| 390×844 | 390 / 390 | 0 | 0 |

At 390px, the smallest measured touch target was 28×28px. The audit also covered settings and overview dialogs, chapter flyout, stage parameters, filters, console, selected inspector, sidebar collapse, and viewer interactions. Stale F001–F012 finding crops from an intermediate run were removed; the final measurements record no findings.

Screenshots and raw measurements are under `evidence/layout/`: `measurements.json`, `screenshots/matrix_{1920x1080,1366x768,1024x768,824x1100,390x844}_default.png`, and phone-state screenshots for settings, overview, chapter flyout, filters, stage parameters, and console.

## Verification

All commands were run from the repository root.

| Command | Result | Evidence |
|---|---|---|
| `Get-ChildItem tools/translation_studio/static -Recurse -File -Filter *.js \| ForEach-Object { node --check $_.FullName }` | All 8 JavaScript files passed, including every module. | `evidence/node_check.log` |
| `git diff --check -- tools/translation_studio` | Passed. | Command output |
| `python Plan/active/2026-09-23_T937_translation-studio-parity/team/13-D1-refactor/evidence/run_required_tests.py` | 8/8 passed, including the requested `py_compile` command (which compiles `server.py`). | `evidence/required_tests.json` and per-suite `.log` files |
| `python Plan/active/2026-09-23_T937_translation-studio-parity/team/13-D1-refactor/evidence/smoke_b2.py` | 11/11 passed; no browser errors; filter changes did not dispatch a pipeline stage. | `evidence/smoke_results.json`, `evidence/filtered_view.json`, `evidence/smoke_ui.png` |
| `python Plan/active/2026-09-23_T937_translation-studio-parity/team/13-D1-refactor/evidence/stage_ui_smoke.py` | 2/2 passed; direct Translate updated status, session dispatch count, time, and Overview benchmark; no browser errors. | `evidence/stage_ui_smoke.json`, `evidence/stage_pipeline_translation_smoke.png` |
| `python Plan/active/2026-09-23_T937_translation-studio-parity/team/13-D1-refactor/evidence/layout/run_layout_audit.py` | 20 snapshots; 0 findings. | `evidence/layout/measurements.json`, `evidence/layout/screenshots/` |

The required suite expands to these exact commands:

```text
python tools/translation_studio/selftest_cache_fingerprints.py
python tools/translation_studio/selftest_mask_planner.py
python tools/translation_studio/selftest_ocr.py
python tools/translation_studio/test_translation_providers.py
python tools/translation_studio/selftest_inpaint.py
python tools/translation_studio/selftest_translation_mapping.py
python tools/translation_studio/selftest_inpaint_variants.py
python -m py_compile tools/translation_studio/inpaint_android.py tools/translation_studio/inpaint_provenance.py tools/translation_studio/pipeline.py tools/translation_studio/server.py tools/translation_studio/selftest_inpaint_variants.py
```

Independent review is recorded in [`CODE_REVIEW.md`](CODE_REVIEW.md). The reviewer re-verified the MIME fix, clean final layout evidence, README execution-order note, and stage-time labels; the final verdict is **PASS**.

## Deviations and remaining risk

- The layout and B2 Playwright runs use synthetic cached artifacts and intentionally run no model stage (`pipeline_runs: 0`). The direct Translate UI smoke intercepts the request. These checks verify presentation and dispatch behavior, not a real-model chapter end-to-end run.
- Translate duration is client-measured only for direct Studio Translate requests. It accumulates within the browser session and is labelled accordingly. Bundled page or chapter runs do not expose separate translation timing in the current metrics response.
- The stage cards keep the requested order while Run Page follows the production execution sequence described above. Pipeline behavior was left unchanged as required.
- The worktree contains unrelated modified model/evidence data reported by the repository steward. None of that data was restored or staged; the D1 commit is restricted to Studio code and `team/13-D1-refactor/`.
