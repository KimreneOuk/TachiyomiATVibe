# Gate 1 closure record — 2026-09-23

Verdict at review: **CONDITIONAL-PASS** (condition: B1 merge + regression
verification of the changed-detector/direct-render stale-OCR case).

## Condition status: MET — Gate 1 CLOSED

- B1 merged to `main` at `4ff20b4` (provenance crops, render-lineage fix,
  `.studio` reader lock sweep) and `5a9e9b1` (legacy-cache adoption:
  `_accept_page_fingerprint` adopts on ABSENT stored identity, invalidates
  only on CHANGED identity; focused regression added to
  `selftest_cache_fingerprints.py`).
- Gate's own repro rerun on merged main:
  `reproduced_stale_snapshot: false`; render cache now keyed by the
  post-validation OCR fingerprint (`ocr-after-validation`). See
  `evidence/stale_render_snapshot_repro.json` for the original reproducing
  run and B1's `team/10-B1-provenance/` evidence for the fixed-state runs.
- Full verification on merged `main@5a9e9b1`+ : py_compile, cache
  fingerprints, mask planner, OCR (17), translation providers (16), inpaint
  FAST/QUALITY — all exit 0.

## Supervisor fixes landed during closure (integration-level)

- `2ed934c` — serve-or-fallback for `/img/render` + `/img/inpainted`: GET
  image routes no longer auto-run pipeline stages under the global lock
  (B2 review LOW finding; load-bearing after B1's lock made the auto-run
  block all readers).
- Determinism guard in the B2 smoke fixture (`smoke_b2.py`): the fixture
  copies the gitignored repo `demo_chapter`, which had accumulated generated
  p002 artifacts from earlier runs, breaking the p002 no-cache assertions
  environmentally. The guard strips all p002 artifacts after copytree.
  Post-fix: B2 smoke 11/11, exit 0, `browser_errors: []`.
- Root-caused en route (fixed by B1 `5c5d38f`): fingerprint acceptance was
  EMPTYING cache entries on first touch for fingerprint-less (legacy /
  hand-seeded) chapters — deterministic, no race. Design rule now:
  adopt-on-absent, invalidate-on-change; stage-time validation already
  prevents stale USE, so adoption is safe.

## Residuals (documented, accepted)

- Same-stat model replacement limited to unsampled middle bytes retains the
  prior digest (head/tail 4 KiB probe); dormant `asset_sha12` helper remains
  stat-keyed with no current call sites.
- B2 review LOW notes F4–F7 (unlocked display snapshot accessor, per-request
  JSON reparse, transient fetch-error caching, dead CSS) — Gate 2 cleanup
  candidates.
