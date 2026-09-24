---
kind: review
title: "T937 B3 variant iteration review"
---

# T937 B3 — Variant iteration review

## Headline

One medium plan gap remains: OpenCV method selection exposes Telea radius but no Telea/NS choice. One low-severity cache issue means a restored variant is not promoted in the eviction order. Selector/default wiring, active-output invalidation, force behavior, and the synthetic evidence for upstream invariance otherwise check out.

## Findings

### 1. [MEDIUM] The promised Telea/NS choice is not exposed

- **Likelihood:** High; the OpenCV route always takes this path.
- **Classification:** Verified plan gap / defect.
- **Evidence:** B3 calls for parameter overrides “(Telea/NS, erosion radius, feather px)” (`PLAN.md:245-250`). The variant UI exposes a Telea radius, erosion radius, and feather override, but no OpenCV method selector (`tools/translation_studio/static/index.html:570-575`). `_fast_reconstruct()` passes `cv2.INPAINT_TELEA` unconditionally (`tools/translation_studio/inpaint_android.py:764-769`); the changed radius parameter does not change that method.
- **Impact:** Bubble and free-text `opencv` legs cannot exercise OpenCV Navier–Stokes, so the requested method comparison is missing even though radius variants work. Either add and fingerprint a Telea/NS selector, or narrow the B3 wording if Telea-only was intended.

### 2. [LOW] A variant-cache hit does not refresh eviction recency

- **Likelihood:** High when more than eight variants are cycled; every restored directory follows the same call path.
- **Classification:** Strong inference / cache-efficiency defect.
- **Evidence:** Eviction keeps the eight directories with newest directory mtimes (`tools/translation_studio/pipeline.py:2301-2323`). A successful cache restore calls `variant_dir.touch()` and suppresses `OSError` (`tools/translation_studio/pipeline.py:2422-2436`). `Path.touch()` opens its target as a file, which fails for a directory on the supported Windows environment; because that failure is ignored, a cache hit leaves the directory mtime unchanged.
- **Impact:** After a user switches back to an older cached variant, the next new variant can evict that just-used entry and its associated crop run (`pipeline.py:2319-2323`). The active fingerprint remains pinned, so this does not delete the active result or its crops; it can cause avoidable recomputation when returning to an evicted variant.

## Verified behavior and limits

- **Selector and Android-default wiring — VERIFIED.** The two selectors offer the planned bubble/free-text legs (`static/index.html:559-568`); preset buttons select Android and the two production pairs, then reset Telea radius 3, erosion 5, and feather override to blank/default (`static/app.js:1945-1949,2998-3009`). Settings save maps those values into the backend (`static/app.js:3018-3040`); normalization clamps the ranges and leaves feather `None` to retain the path-specific Android defaults (`pipeline.py:2069-2093`). The Android call passes all three normalized values through (`pipeline.py:2473-2487`).
- **Cache hit, miss, force, and stale active output — VERIFIED.** The cache fingerprint includes engine, leg matrix, and the variant-parameter hash (`pipeline.py:2377-2397`). A matching entry must also have current B1 crop artifacts; a variant restore is skipped for `force=True` (`pipeline.py:2401-2438`). Changed inpaint settings preserve the current result under its fingerprint and remove the canonical output, mask, provenance, and render before reuse (`pipeline.py:466-470,513-539`). The existing B3 selftest asserts misses on new variants, switchback hit without another inpaint call, and force regeneration (`selftest_inpaint_variants.py:181-192,211-236`; its generated summary is `team/11-B3-variants/evidence/variant_selftest.json`).
- **Crop eviction coupling — VERIFIED for the reviewed single-page path.** Variant output, mask, and provenance are stored together (`pipeline.py:2251-2280`); loads validate referenced crop artifacts (`pipeline.py:2282-2299`); pruning retains crop runs for the same fingerprint set retained in the variant cache (`pipeline.py:2301-2323`). The recency finding above affects which inactive entry survives, not synchronization between retained variant files and their crop runs.
- **Force propagation — VERIFIED.** The UI sends `force: true` for Clean and the full Process action (`static/app.js:2312-2318`); `/api/process` forwards it (`server.py:339-343`), and `process_page()` passes it to detect, OCR, inpaint, and render (`pipeline.py:2887-2901`). `inpaint_page()` returns `cache_hit: false` when forced (`pipeline.py:2579-2587`).
- **Planner, partition, and Paddle refinement invariance — VERIFIED within the synthetic B3 harness.** `_assert_same_upstream()` compares regions, planner candidates/items, both partitions, raw mask RLE references, and Paddle-refined source boxes across each leg/parameter scenario (`selftest_inpaint_variants.py:127-141,171-192`). The generated summary reports `planner_partition_and_paddle_refinement_stable: true` (`team/11-B3-variants/evidence/variant_selftest.json`). The harness uses a deterministic fake Paddle detector, so this demonstrates selection invariance under its fixture; it is not a real Paddle-model parity measurement.

No CRITICAL or HIGH severity implementation issue was found in the requested review areas. The B3 selftest evidence was inspected but not rerun by this reviewer. The implementer subsequently reported that the focused variant test and full six-check Gate 1 suite passed against the current diff; that test status is attributed to the implementer, while the findings above are based on this review's source inspection.
