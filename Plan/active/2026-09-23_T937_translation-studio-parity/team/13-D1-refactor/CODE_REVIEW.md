# T937 D1 — Independent Code Review (UI refactor)

Reviewer: D1 independent reviewer (fresh agent, traycer-review skill).
Scope: uncommitted diff on `t937-d1-ui-refactor` in `tools/translation_studio`
(`README.md`, `static/index.html`, `static/style.css`, `static/app.js` →
`static/modules/*`), checked against the D1 ticket (PLAN.md Phase D), the
Director phone-viewport decision (2026-09-24), the UI layout acceptance rule,
and the preserved B2/B3/C2 contracts. Production behavior was not edited by
this review. One runtime probe was performed (local server start +
`/static/modules/*` fetch, then stopped).

## Verdict

**PASS WITH REQUIRED FIXES → updated after remediation: PASS.** See
"Follow-up verification" below: F1/F3/F4/F5 are resolved and reviewer-verified;
F2 is resolved by the implementer's commitment to scope the commit to D1 files
only (verify the staged list at commit time). The original findings text is
retained unchanged below for the record.

## Acceptance vs PLAN.md D1 — all met

| Requirement | Status | Evidence |
|---|---|---|
| Stage pipeline reorg with per-stage status/rerun/params | MET | `static/index.html:380-408` (Stage Pipeline card), `modules/stages.js:95-131` (`refreshStagePipeline`) |
| Split app.js into modules | MET | 7 classic scripts, `index.html:543-549`; 115/115 old functions present, `node --check` pass on all 7 (reviewer-run) |
| Benchmark table (per-stage ms, model loads, dispatch counts) | MET | Overview dialog `modules/stages.js:644-662,727-732`; field wiring matches `pipeline.py:1181-1215` and load keys `detector`/`ocr_*`/`aot`/`segmenter` (`pipeline.py:1245,1940,1959,2385,2405`) |
| Settings all functional (no decorative fields) | MET | Every settings field posted in `modules/stages.js:843-867`; stage-card controls each POST on change (`stages.js:197-284`) |
| README rewrite to reality (inpainting active, engines, variants) | MET | README diff; matches `DEFAULT_SETTINGS` leg matrix and `.studio/inpaint_variants/` (`pipeline.py:883,907`) |
| Fix all five baseline phone patterns + clear B2-N1 (Director 2026-09-24) | MET | ≤600px block + global `dialog` rule (`style.css:1619-1781`); Pass 3 measurements: `findings: []`, `scrollWidth == clientWidth` at 390×844 in all states incl. dialogs/flyout/console/filter/stage-params |
| Zero horizontal scroll across viewport matrix | MET | `evidence/layout/measurements.json` (1920/1366/1024/824/390 + mid-resize + interactions) |

## Contract preservation — validated

- **B2 display-only semantics (VERIFIED):** adapted smoke
  `evidence/smoke_results.json` shows browser POSTs limited to
  `/api/settings` + `/api/export-filtered`; 11/11 checks, `browser_errors: []`.
  Filter engine (`modules/layers.js`) is byte-identical to the pre-D1 code
  except the intentional badge-placement change (see below).
- **Code-movement proof (VERIFIED):** normalized-line diff of old app.js vs
  the union of modules loses exactly 4 unique lines, each traced to an
  intentional replacement: translate dispatch gained client timing
  (`stages.js:322-326`), "Clean" chip renamed "Inpaint" (`stages.js:606`),
  re-render button is now a static ↻ icon (`viewer.js:594-650`), and the badge
  `<text>` line became collision-avoiding `artifactBadgeMarkup`
  (`layers.js:897-942`, which also resolves audit minor B2-N2).
- **B3 variant surface (VERIFIED):** `syncInpaintVariantStatus`, preset
  buttons, Telea/NS selector, variant-change auto-dispatch (`force: false`)
  identical to old app.js:2399/3130 regions; now in `stages.js:134-159,
  821-841, 870-880` with an added inpaint dispatch count.
- **C2 mapping/prune UI (VERIFIED):** stable-id translation attrs and
  `_pruned` record construction carried unchanged (`layers.js:319-384`).
- **Classic-script ordering (VERIFIED):** load order core → layers → viewer →
  inspector → stages → console → app; all top-level statements touch only DOM
  present in index.html (removed stepper IDs accessed via `?.`,
  `stages.js:359-370`); cross-module calls resolve at event/boot time; boot
  runs last (`modules/app.js:308-348`).

## Findings (prioritized)

### F1 — MEDIUM · defect · new · must fix before integration
**Module scripts served as `application/octet-stream`.**
`index.html:543-549` loads 7 scripts from `/static/modules/`, but the static
route's MIME map only knows `style.css` and `app.js`
(`server.py:185-190`). Reviewer-verified live:
`GET /static/modules/core.js` → `200`, `Content-Type: application/octet-stream`
(4,167 bytes). `_send_file` sends no `X-Content-Type-Options` header
(`server.py:160-167`), so Chromium/Firefox still execute the scripts — which
is why the smoke and layout runs are green — but every session logs MIME
warnings, and any future `nosniff` header, strict CSP, or stricter browser
policy bricks the whole UI. Fix is 1–2 lines in `server.py` (resolve MIME by
extension). Likelihood of functional break today: low; of console warnings
every session: certain. server.py is inside the permitted studio surface.
Evidence class: VERIFIED (live request + code).

### F2 — MEDIUM · process/hygiene · must resolve before commit
**Changeset pollution: hydrated LFS blobs + other tickets' evidence rewritten.**
(a) `app/src/main/assets/models/ocr/paddle-v6-small/det/inference.onnx` and
`.../inference.onnx` are hydrated blobs (9,880,512 / 21,159,378 bytes) while
HEAD holds LFS pointers (verified: `git show HEAD:...` returns pointer text;
size matches). Repo precedent (PLAN.md A2/A4) is that hydrated payloads are
never committed. (b) The D1 verification suite reruns rewrote 100+ evidence
files belonging to other tickets (`review/phase-a-final/evidence`,
`team/03`, `team/08`, `team/10`, `team/11`); ~29 have content changes
(e.g. `team/11-B3-variants/evidence/switchback_routes.json` ±14), the rest are
CRLF↔LF churn. The D1 commit should stage only the four studio files +
`static/modules/` + `team/13-D1-refactor/`, restore the two ONNX files to
pointers, and restore the out-of-scope evidence files (or get an explicit
decision to refresh them). Evidence class: VERIFIED (git numstat/diff/show).

### F3 — LOW · evidence hygiene · recommended
**Stale violation crops contradict the clean final measurements.**
`evidence/layout/crops/F001–F012.png` (written 10:26) are from an intermediate
audit run that had findings; the final `measurements.json` (10:47) records
`"findings": []`. The harness only writes crops when a finding fires
(`evidence/layout/audit_playwright.py:76-90`) and never cleans the folder, so
the shipped evidence can be misread as 12 open findings. Delete the stale
crops or annotate their provenance. Evidence class: VERIFIED (timestamps,
harness code, empty findings array).

### F4 — LOW · docs/UX drift · Director/implementer call
**UI stage order does not match pipeline execution order.**
The Stage Pipeline card, Run Page tooltip, and README present
Detect → OCR → **Translate → Inpaint** → Render (order taken from PLAN.md D1),
but `process_page` executes detect → ocr → **inpaint → translate** → render
(`pipeline.py:3374-3382`). D1 is plan-conformant, and the per-stage Run
buttons are order-independent, but in an instrumented parity bench the
presented order misleads about execution sequence. Options: reorder the card
to execution order (Inpaint row above Translate), or keep the plan's order and
annotate. Evidence class: VERIFIED.

### F5 — LOW · residual limitation · acceptable, label it
**Stage time node accumulates across reruns; Translate timing partial.**
`recordStageElapsed` sums durations per page+stage in sessionStorage
(`stages.js:28-36`), so the per-stage "ms" grows across reruns in a session
(last-run semantics would be less surprising); the time node has no scope
tooltip (the dispatch node does, `stages.js:124`). Benchmark "Translate" only
has client-measured time for direct Translate runs — bundled chapter runs
record dispatches but no timing — which the table note honestly discloses
(`stages.js:732`). Dispatch counts are session-scoped by design (tooltip).
Evidence class: VERIFIED.

## Notes (no action required)

- Pre-existing, carried verbatim: keyup handler removes class `pannable`
  while keydown/pointer handlers use `panning` (`modules/app.js:241,304`;
  old app.js:3178/3241) — harmless cursor-class typo, out of D1 scope.
- Dead CSS for the removed stepper deck/dropdown remains
  (`style.css:348,362-363`) and `static/app.js` is now a one-line pointer
  comment (no longer loaded by index.html). Consequence worth recording: the
  B2-era verification command `node --check tools/translation_studio/static/app.js`
  is now vacuous — future suites should target `static/modules/*.js`
  (reviewer ran `node --check` on all 7: pass). Consider adding it to the
  required-tests list.
- `modules/stages.js` adds real stage-run button disabling during processing
  (`stages.js:126-128`), an improvement over the old always-enabled stepper.
- D1's `cfgInpaintTag` markup default "quality" now matches the server
  default (`pipeline.py` DEFAULT_SETTINGS `inpaint_mode: "quality"`); the
  stale `class="active"` on the Fast segment is corrected by
  `syncSettingsUI()` on boot.

## Reviewer verification performed

- Read all four changed files + all 7 modules in full; old vs new line-level
  comparison (see code-movement proof).
- `node --check` on all 7 modules (pass).
- Live server probe for F1 (started `studio.py --port 8931`, fetched
  `/static/modules/core.js` etc., stopped).
- Cross-checked benchmark fields against `pipeline.py` overview/load-times
  schema; settings dialog fields against `setSave`; stage-button IDs against
  markup; stepper references against removals.
- Evidence inspection: `required_tests.json` (8/8 pass), `smoke_results.json`
  (11 checks, no browser errors), `layout/measurements.json` (20 snapshots,
  0 findings), crop/measurement timestamp reconciliation, smoke_b2.py
  identical to the B2 original (passed unadapted on the refactored UI).

## Residual limitations of this review

- No real-model pipeline execution was re-run by the reviewer; stage-run
  behavior beyond the smoke's cached-artifact path relies on the implementer's
  suite evidence plus code reading.
- The Pass 3 layout audit used a 1-region synthetic fixture
  (`coverage.cached_page_1_region_count: 1`); overlay-registration and badge
  layout under denser real caches were not re-measured here.
- Playwright layout evidence was produced by the implementer's adapted
  harness; the reviewer validated its check families, thresholds (2px / 4px),
  and coverage against the Pass 1/2 rubric but did not re-execute it.

## Follow-up verification (2026-09-24, post-remediation)

The implementer applied fixes; the reviewer independently re-verified each
claim against the working tree.

- **F1 — RESOLVED (VERIFIED).** `server.py:185-194` now falls back to
  `text/javascript; charset=utf-8` for any `/static/**.js` path (minimal diff;
  `style.css` mapping unchanged). Reviewer live probe:
  `/static/modules/core.js` and `/static/modules/stages.js` →
  `text/javascript; charset=utf-8`; `style.css` unchanged. `server.py` is now
  a fifth modified studio file — in-scope and minimal.
- **F3 — RESOLVED (VERIFIED).** `evidence/layout/crops/` is empty (0 files);
  final `measurements.json` regenerated 11:01 with 20 snapshots and
  `findings: []`.
- **F4 — RESOLVED by annotation (VERIFIED).** Card order kept per Director;
  `README.md:30-34` now states the stage cards are in "review order" and that
  Run Page "inpaints before translation after OCR" (matching
  `pipeline.py:3374-3382`).
- **F5 — RESOLVED by labeling (VERIFIED).** Stage time node gains a translate
  tooltip: "Cumulative client-measured time for direct Translate requests in
  this browser session" (`modules/stages.js:120-121`); overview note updated
  to "Translate sums client-measured direct Studio request time"
  (`modules/stages.js:735`).
- **F2 — RESOLVED by commit scoping (commit-time check).** Implementer will
  stage only the five studio files + `static/modules/` + `team/13-D1-refactor/`
  and leave the hydrated ONNX blobs and out-of-scope evidence churn unstaged
  rather than restoring them (restoring could discard unrelated workspace
  work — accepted rationale). Whoever lands the D1 commit must verify the
  staged list contains no `app/src/main/assets/models/**` or other tickets'
  evidence files.

Updated verdict: **PASS.** All findings closed; the commit-scope check in F2
is the only remaining gate-time obligation.
