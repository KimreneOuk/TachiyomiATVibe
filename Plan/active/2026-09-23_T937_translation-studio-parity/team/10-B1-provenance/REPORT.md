# T937 B1 — Inpaint provenance artifacts

## Delivered

- Added eager, lossless PNG crops for every routed Android inpaint region: input, final output, and the associated erase-mask component. Crops are written on cache miss/force and stored under `.studio/inpaint_crops/<page>/<region_id>/<run_fingerprint>/`. A5 invalidation removes the page crop tree; cache hits require every current crop path to exist under the active fingerprint.
- Added a namespaced `crops` object to each provenance region and advanced `provenance_schema_version` from 2 to 3. Existing A4 fields remain verbatim, including `context_crop_bbox`, route data, `timing_ms`, and `timing_scope`. `crops.mask_ref` mirrors the existing `mask_resolution.mask_ref` for inspector joins.
- Added `GET /api/inpaint_provenance?p=<page>` and `GET /img/inpaint_crop?p=<page>&id=<region_id>&kind=input|output|mask&representation=debug|exact`. Crop reads resolve only through current page provenance and are confined to `.studio/inpaint_crops`.
- Fixed the A5 render-lineage issue: `render_page()` now re-reads OCR immediately after `inpaint_page()` and uses the refreshed entry for region geometry and render cache lineage. The cache selftest changes detector input, directly calls render, and verifies both the rendered region geometry and persisted OCR fingerprint use the refreshed record.
- Corrected A5 first-touch fingerprint handling for legacy caches: a missing persisted page fingerprint is adopted without deleting inspectable cache records, while a differing previously accepted fingerprint still invalidates. Added a model-free regression that preserves hand-written detection/OCR/inpaint records on first touch, then changes the source and verifies invalidation on the next touch.

## Crop and schema decisions

- A4 `context_crop_bbox` is never rewritten. For routes with a distinct cropped inference window, `crops.basis` is `route-context` and `crops.crop_bbox` matches that window. For page-space `bubble/android-fill`, the saved crop is the region erase-mask component plus 12px of context, clamped to the page; it is marked `region-mask-union` with a note that Android fill itself used page-space inputs.
- `input` is the source page crop; `output` is the final inpainted page crop; `mask` is the region's connected erase-mask component aligned to `crop_bbox`.
- PNG is the exact pixel artifact: lossless RGB8 for input/output and L8 for mask. `debug` and `exact` are path/URL aliases for the same PNG bytes. No `.npy` arrays or normalized tensor copies are stored.
- Crops are generated eagerly on a successful inpaint cache miss or force. This makes inspector data immediately available and avoids full-page duplicate artifacts for page-space Android fill; the cost is bounded PNG writes when an inpaint fingerprint changes.
- Provenance GET validates A5 fingerprints through the existing inpaint path before returning. It is read-only to its caller, but can refresh derived stage caches when inputs are stale.

## Follow-up fix: Windows `.studio/` reader race

The intermittent browser-boot 500 was reproduced on the merged integration base (`main@e00f0c38d95f9317a9591adce11eff93684b9aa1`): 30 fresh browser boots produced one `500 /api/page?p=p001.jpg` on round 16. The traceback ends in `PermissionError [WinError 32]` while `_invalidate_page_artifacts()` unlinks `.studio/inpaint/p001.json`.

The concurrent reader was B2 `GET /api/artifacts`: `_artifact_payload()` called `_read_artifact_source()` and held the JSON file open without `PIPELINE.lock`. A5 page fingerprint validation did hold the lock while invalidating, but the route reader did not participate in that lock. The full captured traceback is in `evidence/server_race_traceback.txt`; the summarized diagnosis is in `evidence/server_race_repro.json`.

All server-side `.studio/` read paths now use the same `PIPELINE.lock` through file read/send or image generation/send:

| Surface | Lock coverage |
|---|---|
| `/api/artifacts`, `/api/inpaint_provenance` | JSON/provenance reads and response serialization |
| `/img/render`, `/img/inpainted`, `/img/inpaint_mask`, `/img/segmentation`, `/img/seg_overlay`, `/img/overlay`, `/img/crop`, `/img/thumb`, `/img/ocr_input`, `/img/render_crop`, `/img/inpaint_crop` | cache validation, image/path reads, and response send |
| `/img/original`, `/img/goal` | path lookup and file/image reads, including chapter paths that resolve into `.studio` |
| `/api/state`, `/api/overview` | state/cache reads |

Pipeline file readers use the same RLock: fingerprint metadata reads run inside `_page_operation_scope`; chapter cache loading is under `open_folders()`; render, thumbnail, inpaint, mask, provenance, and segmentation image reads are page-scoped. I also page-scoped the direct `Pipeline.thumbnail()` image open so it cannot escape the lock after `thumbnail_path()` returns. No unlink retry was added; the regression exercises the lock itself so it cannot conceal a missed reader.

`selftest_server_concurrency.py` replays the browser boot participants with a real held Windows handle on `.studio/inpaint/p001.json`. It verifies both the page GET and settings POST wait until the artifact read is released, then asserts all three responses are 200 and invalidation removes the stale JSON. Evidence records 12/12 successful rounds in `evidence/server_concurrency_selftest.json`.

## Follow-up fix: legacy cache adoption

The merged-main regression was deterministic and did not require a race: a fingerprint-less `.studio` fixture opened successfully, then its first `GET /api/page` caused `_accept_page_fingerprint()` to treat the absent value as a mismatch and empty `detections.json`. The supplied reproducer was `team/08-B2-visualization/evidence/merge_diag/repro_b2j.py`; before the fix it reported `detections pages now: []` and `/api/artifacts` returned `missing`.

Fingerprint acceptance now distinguishes absence from change. If no in-memory fingerprint has yet been accepted and no persisted fingerprint exists, the current page fingerprint becomes the in-memory baseline without invalidation. Existing detector/OCR/inpaint stage validators remain responsible for deciding whether fingerprint-less records can be reused. If a persisted fingerprint exists and differs, or an in-memory accepted fingerprint changes, page artifacts are still invalidated. `evidence/cache_selftest.json` records both first-touch preservation and second-touch invalidation; `evidence/legacy_cache_adoption_route_repro.txt` records the post-fix route result (`detections pages now: ['p001.jpg']`, artifact status `ready`).

This branch was fast-forwarded to `main@2ed934c`, including the serve-or-fallback `/img/render` and `/img/inpainted` change. The upstream B2 smoke expects p002 to have no inpaint cache, but this worktree's ignored demo folder contains generated p002 inpaint files from the inpaint selftest. A direct run therefore fails only at the no-cache fixture assertion. The compatibility runner executes the upstream B2 smoke against this branch's merged static UI and server, adapting only its temporary fixture (including removing those generated p002 outputs); it passes all 11 checks.

## Verification

Commands were run from the repository root unless stated otherwise:

| Command | Result / evidence |
|---|---|
| `python tools/translation_studio/selftest_cache_fingerprints.py` | Passed; includes direct-render refreshed-OCR lineage and legacy-cache first-touch/changed-page regressions. `evidence/cache_selftest.json`. |
| `python Plan/active/2026-09-23_T937_translation-studio-parity/team/08-B2-visualization/evidence/merge_diag/repro_b2j.py` | Passed after the fix; first `/api/page` leaves the legacy p001 detections record intact and `/api/artifacts` returns `ready`. `evidence/legacy_cache_adoption_route_repro.txt`. |
| `python tools/translation_studio/selftest_mask_planner.py` | Passed; A4 routes and mask references remain compatible with schema v3. `evidence/mask_planner_selftest.json`. |
| `python tools/translation_studio/selftest_ocr.py` | Passed, 17 tests. |
| `python tools/translation_studio/test_translation_providers.py` | Passed, 16 tests. |
| `python tools/translation_studio/selftest_inpaint.py` | Passed on both demo pages under Android FAST and Android QUALITY, plus existing free-text/bubble route probes. Each page/preset has 3 regions and 9 PNGs (3 crop triples). |
| `python tools/translation_studio/selftest_provenance.py` | Passed. Checks both crop bases, pixel values, alignment/dimensions, both GET routes, and fingerprint invalidation/regeneration. `evidence/provenance_selftest.json`. |
| `python tools/translation_studio/selftest_server_concurrency.py` | Passed; 12/12 seeded browser-boot overlaps, all artifact/page/settings responses 200. `evidence/server_concurrency_selftest.json`. |
| `python -m py_compile tools/translation_studio/pipeline.py tools/translation_studio/server.py tools/translation_studio/inpaint_android.py tools/translation_studio/inpaint_provenance.py tools/translation_studio/selftest_cache_fingerprints.py tools/translation_studio/selftest_mask_planner.py tools/translation_studio/selftest_inpaint.py tools/translation_studio/selftest_provenance.py tools/translation_studio/selftest_server_concurrency.py tools/translation_studio/selftest_ocr.py tools/translation_studio/test_translation_providers.py` | Passed. |
| `$env:T937_B2_STATIC=(Resolve-Path tools/translation_studio/static).Path; python Plan/active/2026-09-23_T937_translation-studio-parity/team/10-B1-provenance/evidence/run_b2_smoke_compat.py` | Passed all 11 B2 browser smoke checks on the fast-forwarded branch; no browser errors or stage/process POSTs. Evidence: `evidence/smoke_results.json`, `evidence/smoke_ui.png`, `evidence/filtered_view.json`, and `evidence/b2_smoke_compat.json`. |
| `git diff --check -- tools/translation_studio` | Passed. |

The B2 smoke uses the merged branch's `static/` files. Its compatibility runner only adjusts the temporary smoke fixture: stamps current A5 detection/OCR cache fingerprints, shifts the synthetic `td0002` box so current replay retains the intended OCR-dedup loser, removes inherited p002 inpaint outputs so p002 is the no-cache control, and waits for the asynchronous text badge before asserting it. This keeps the upstream smoke assertions intact while making the copied demo fixture represent current A5 caches.

FAST and QUALITY demo artifacts are under `evidence/demo/`:

- `p001_android-fast/`, `p002_android-fast/`
- `p001_android-quality/`, `p002_android-quality/`

Each contains `provenance.json` plus `crops/<region_id>/{input,output,mask}.png`. Each region record carries three route timings where available from A4 and six representation entries pointing to its three canonical PNGs.

## Deviations and remaining risks

- Android bubble fill is page-space internally. The crop files are intentionally compact views of the associated mask component, while A4's full-page `context_crop_bbox` remains unchanged and the B1 note explains the distinction.
- The exact and debug representations alias the same canonical PNG bytes; normalized model tensors are not persisted.
- The B2 smoke compatibility runner changes only its temporary synthetic fixture and adds a wait for an asynchronous overlay. The merged B2 static UI is used unchanged; the command now runs from this B1 branch without the B2 worktree.
- The direct upstream B2 smoke command is sensitive to generated files under `tools/translation_studio/demo_chapter/.studio/inpaint`; the compatibility runner isolates and fixes its no-cache control in a temporary copy, without deleting those worktree files.
- The concurrency regression deliberately targets Windows file-handle semantics, which produced the reported WinError 32. It serializes small local artifact reads under a single-user RLock; it does not attempt parallel cache file streaming.
