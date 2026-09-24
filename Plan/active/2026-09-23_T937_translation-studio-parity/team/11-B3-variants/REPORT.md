# T937 B3 — Variant iteration delivery report

**Status:** Complete
**Branch:** `t937-b3-variant-iteration`
**Integrated base:** `main` at `1b42b321eb735b471059837dc597a842f65c5b41`

## Delivered

The inherited B3 work added independent bubble and free-text fill legs, variant controls for radius, erosion, and feathering, output/mask/provenance caching by variant fingerprint, active-output invalidation when inpaint settings change, and forced regeneration through Process/Inpaint actions (`inpaint_android.py:1045`, `static/index.html:559`, `static/index.html:575`, `pipeline.py:2731`, `pipeline.py:872`, `pipeline.py:919`, `pipeline.py:2918`, `app.js:2395`, `app.js:2400`). Variant pruning keeps crop runs in step with retained fingerprints (`pipeline.py:2781`, `pipeline.py:2802`, `inpaint_provenance.py:248`).

The merge retained C2's stable translation IDs and corresponding artifact lookup (`pipeline.py:122`, `app.js:415`). The only merge conflict was in `pipeline.py`: both imports were retained; B3's `force` propagation was retained; C2's removal of the old `carry_translations` call was preserved.

## Completion of review findings

- **MEDIUM — Telea/NS selector:** Added an OpenCV method selector defaulting to Telea, with Navier–Stokes labelled experimental (`static/index.html:570`). The Android inpaint route selects `cv2.INPAINT_TELEA` or `cv2.INPAINT_NS` and records the selected method in route names (`inpaint_android.py:766`, `inpaint_android.py:771`, `inpaint_android.py:776`, `inpaint_android.py:1173`). The normalized method is included in variant parameters, the cache key hash, and persisted variant provenance (`pipeline.py:2547`, `pipeline.py:2672`, `pipeline.py:2980`). The Android FAST/QUALITY preset buttons reset the method to Telea; NS also makes the UI status experimental (`app.js:2023`, `app.js:2037`, `app.js:3092`).
- **LOW — restored-cache recency:** Replaced `Path.touch()` on a directory with `os.utime()` so Windows updates the directory mtime used by variant eviction (`pipeline.py:2786`, `pipeline.py:2913`). The focused test backdates the cached directory and checks that switching back promotes its mtime (`selftest_inpaint_variants.py:230`, `selftest_inpaint_variants.py:248`).

## Verification

All requested commands passed on the merged branch:

```text
python tools/translation_studio/selftest_inpaint_variants.py — PASS
python tools/translation_studio/selftest_cache_fingerprints.py — PASS
python tools/translation_studio/selftest_mask_planner.py — PASS
python tools/translation_studio/selftest_ocr.py — PASS (17 tests)
python tools/translation_studio/test_translation_providers.py — PASS (16 tests)
python tools/translation_studio/selftest_inpaint.py — PASS
python -m py_compile tools/translation_studio/inpaint_android.py tools/translation_studio/inpaint_provenance.py tools/translation_studio/pipeline.py tools/translation_studio/server.py tools/translation_studio/selftest_inpaint_variants.py — PASS
python Plan/active/2026-09-23_T937_translation-studio-parity/team/08-B2-visualization/evidence/smoke_b2.py — PASS (11 checks; no browser errors)
```

Focused evidence was regenerated under `team/11-B3-variants/evidence/`. Key files are `variant_selftest.json`, `opencv_method_ns_routes.json`, `opencv_ns.png`, `opencv_ns_mask.png`, the leg/parameter route JSONs, and switchback/force route JSONs. Existing Gate 1, inpaint baseline, and provenance crop evidence is retained alongside them.

The focused harness confirms method changes alter output for unchanged legs, method data appears in the fingerprint/provenance, upstream planning/partition/Paddle-refinement evidence remains stable, cache switchback avoids recomputation, force regenerates output, and cache recency is refreshed (`selftest_inpaint_variants.py:175`, `selftest_inpaint_variants.py:199`, `selftest_inpaint_variants.py:208`, `selftest_inpaint_variants.py:248`).

## Remaining risk

Navier–Stokes remains experimental. The B3 selftest uses a synthetic page and a deterministic fake Paddle detector (`selftest_inpaint_variants.py:22`), so it validates route, cache, and selection behavior without claiming real-model image-quality parity.
