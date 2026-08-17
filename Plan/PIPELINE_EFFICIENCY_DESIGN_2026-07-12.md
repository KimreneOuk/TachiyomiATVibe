# Pipeline Efficiency — Implementation Design

**Date:** 2026-07-12
**Scope:** Design only. No code changes. Implementation route for the fixes proposed in `PIPELINE_EFFICIENCY_INVESTIGATION_2026-07-12.md`.
**Source plan:** `PIPELINE_EFFICIENCY_INVESTIGATION_2026-07-12.md` (audited 2026-07-12; 21/24 claims verified, 1 factual error found, 2 stale line refs)
**Companion designs:** `AOT_NPU_FIXED512_DESIGN_2026-07-12.md`, `TRANSLATION_RACE_CONDITION_DESIGN_2026-07-12.md`, `INPAINTING_EFFICIENCY_DESIGN_2026-07-12.md`
**Status:** Awaiting approval. Not initiated.

---

## Executive Summary

The source plan identified **9 findings** (4 real, 5 refuted) and proposed 4 fixes (P1a, P1b, P2a, P2b) plus 2 explicit "do not pursue" items (F7, F5). Audit verified the fix locations accurate; corrected one factual error (the plan says "5 `recomputeFor` call sites" — there are exactly **4**: TranslationPipeline.kt:908/1948/1978/2115).

**The honest headline:** the safe optimizations (P1a + P1b) save an estimated **~35-88ms/page** — noticeable in aggregate but not transformative. There is no free lunch; the big-ticket speedups are correctly blocked by load-bearing safety mechanisms (`nativeGuard` for SIGSEGV, `translatorPermit` for OOM).

**Key constraint from audit (testability):**
- `RenderColorEstimator` is **already unit-tested** with synthetic IntArrays (`RenderColorEstimatorSamplingTest.kt`) → **P1a is easy to verify.**
- `MangaOcrEngine.preprocess` is **private + Bitmap-coupled** → **P2a needs a refactor or Robolectric to test.** Robolectric is not currently a dependency.
- `GoogleTranslator` **hardcodes the URL and instantiates its own OkHttpClient** → **P2b needs a refactor to be testable** (inject client + base URL, add MockWebServer dep).
- **No `androidTest/` dir exists** anywhere — zero instrumented tests.

These facts change the per-fix effort: P1a is cheap, P2a/P2b carry testability-refactor overhead.

---

## 1. Fixes In Scope (from source plan, all verified sound)

### P1 — Safe, immediate wins (~35-88ms/page)

| ID | Finding | Fix | File:Line (verified) | Testability |
|---|---|---|---|---|
| **P1a** | F8: redundant `extractClusters`/`bubbleInteriorMask` in `estimate()` | Merge duplicate calls; feed both `colorPolicy` + `sampleBackgroundLuma` from one `extractClusters` | `RenderColorEstimator.kt:127-132, 152, 170` | **EASY** — existing test pattern |
| **P1b** | F9: JPEG encode under permit (~30-80ms/page) | Move `compress(JPEG,90)` from `inpaintPage`/`processSinglePage` to after `send()` / in HTTP phase | `TranslationPipeline.kt:2463` (batch), `:2597` (single-page) | **MEDIUM** — needs ordering-correctness test |

### P2 — Low-risk, smaller wins

| ID | Finding | Fix | File:Line | Testability |
|---|---|---|---|---|
| **P2a** | F3: MangaOcr intermediate `FloatArray(1*3*224*224)` (~588KB × 30 = 18MB churn/page) | Mirror Paddle's single-pass direct-write to `inputPixelPool`; eliminate the intermediate array | `MangaOcrEngine.kt:364-379` | **NEEDS REFACTOR** — `preprocess` is private + Bitmap-coupled |
| **P2b** | F1: GoogleTranslator per-block HTTP (20+ round-trips/page) | Concatenate non-empty blocks with delimiter, single request, split response; cap URL length | `GoogleTranslator.kt:26-30` | **NEEDS REFACTOR** — URL hardcoded, client not injected |

### Explicitly NOT pursued (verified load-bearing)

| ID | Finding | Why not |
|---|---|---|
| **F7** | Decode under permit → off-permit | OOM on armeabi-v7a (32-bit, ~192MB heap). `translatorPermit` + `TranslationMemoryBudget` gates are load-bearing. |
| **F5** | Within-stage parallelism | `nativeGuard` Mutex prevents SIGSEGV (close-during-inference). Removing it reintroduces a crash class. |

---

## 2. Verification Design

### P1a — RenderColorEstimator dedup

**Pattern exists:** `RenderColorEstimatorSamplingTest.kt:37` calls `decideTextFill(pixels, 60, 60, ...)` with synthetic ARGB IntArray. `RenderColorEstimatorTest.kt` covers `colorPolicy`.

**Test approach:**
1. Snapshot current `decideTextFill` + `sampleBackgroundLuma` outputs across a matrix of synthetic inputs (dense ink, sparse ink, uniform, gradient).
2. Apply the merge refactor.
3. Re-run; assert **bit-identical outputs** (zero behavioral change is the requirement).

**Gate:** outputs unchanged. This is a pure-dedup refactor; any output difference is a bug.

### P1b — JPEG encode off permit

This is an **ordering** change, not a pure refactor. Verification must prove:
- `cleanedImageName` is set before `tryRender` reads it (the source plan flags this as the ordering constraint).
- The encode still happens exactly once per page (not dropped, not doubled).
- No extra concurrent bitmap is introduced (the source plan's safety argument).

**Test approach:**
1. Extend or add a test in the `TranslationPipeline` test family (runTest infra exists).
2. Assert: encode runs after `send()`, before render; `cleanedImageName` is non-null when `tryRender` is called.
3. **Manual:** logcat timing of `[inpaint_encode]` before/after — confirm it moved out of the permit window.

**Gate:** ordering test passes; manual logcat confirms encode moved.

### P2a — MangaOcr FloatArray pooling

**Blocker:** `preprocess` is `private` + uses `android.graphics.Bitmap/Canvas`. Not JVM-testable as-is.

**Two options:**
- **(i) Refactor** `preprocess` to `internal` + extract the pixel-write loop into a pure function taking an `IntArray` source (the `Bitmap.getPixels` result). Test the pure function with synthetic IntArrays (AotOutputGuard pattern). ~15 lines refactor.
- **(ii) Robolectric** — add the dependency, test `preprocess` with a Robolectric Bitmap. Heavier; new dep.

**Recommendation: option (i).** Smaller, no new dependency, matches the codebase's established synthetic-IntArray testing pattern. The refactor is itself a quality improvement (separates pure pixel math from Android framework).

**Test approach:** pure function takes `(sourcePixels: IntArray, width, height) → FloatArray` (or writes to a provided buffer). Assert output identical to current 2-pass version bit-for-bit.

**Gate:** identical output; CHW layout preserved.

### P2b — GoogleTranslator batching

**Blocker:** URL hardcoded to `https://translate.google.com/...` (GoogleTranslator.kt:68); client is a public `val` but not constructor-injected; `translateText` is private.

**Refactor required:**
1. Constructor-inject `OkHttpClient` (or expose a setter for tests).
2. Make base URL parameterized (default to current value).
3. Make `translateText` (or a new `translateBatch`) `internal`.
4. Add MockWebServer test dependency.

**Test approach:** MockWebServer returns a canned multi-block response; assert single request made (not N), assert response split correctly, assert URL-length cap triggers chunking on text-heavy pages.

**Gate:** single request for multi-block input; chunking triggers at cap; response split correct.

**Honest note:** This is the lowest-priority fix (Google is not the default translator — MLKit is; verified at TranslationPreferences.kt:123-124). The refactor overhead may exceed the user benefit. **Defer unless Google-translator users are a meaningful segment.**

---

## 3. Implementation Route

### Phase 1 — P1a (cheapest, ship first)

```
P1a: RenderColorEstimator dedup (single agent)
  Files: RenderColorEstimator.kt:127-132, 152, 170
  Test: snapshot-then-diff against RenderColorEstimatorSamplingTest matrix
  Risk: ZERO (pure dedup, identical output required)
  ~half-day
```

### Phase 2 — P1b (parallel with P1a after P1a's test pattern is set)

```
P1b: JPEG encode off permit (single agent)
  Files: TranslationPipeline.kt (batch ~2463→after 1198; single-page ~2597→translateSinglePageHttpRender)
  Test: ordering test (cleanedImageName set before tryRender)
  Manual: logcat [inpaint_encode] timing
  Risk: LOW (ordering constraint, no memory risk per source plan)
  ~1 day
```

**P1a and P1b touch different files** — parallel-safe.

### Phase 3 — P2a (needs refactor first)

```
P2a-step1: Refactor MangaOcrEngine.preprocess (extract pure pixel function)
P2a-step2: Single-pass direct-write to inputPixelPool
P2a-step3: Bit-identical output test
  Sequential within one agent; ~1-1.5 days
```

### Phase 4 — P2b (lowest priority, needs refactor)

```
P2b-step1: Refactor GoogleTranslator (inject client + URL, make translateBatch internal)
P2b-step2: Add MockWebServer dep
P2b-step3: Implement batching + URL-length cap
P2b-step4: Test
  Sequential; ~1.5 days
  DEFER unless Google users are a meaningful segment
```

### Parallelism summary

| Phase | Concurrency |
|---|---|
| P1a + P1b | 2 agents parallel (different files) |
| P2a | 1 agent (sequential refactor + impl) |
| P2b | 1 agent (sequential, deferrable) |

---

## 4. Cross-Plan Reconciliation

| Item | Overlap | Resolution |
|---|---|---|
| **P1b (JPEG off permit) complements AOT NPU** | AOT on NPU speeds inpaint; P1b speeds the encode after inpaint. Independent gains stack. | Can ship in any order; both touch TranslationPipeline.kt but different functions. |
| P2a (MangaOcr pool) vs AOT Phase 1C (pool sizing) | Both touch buffer pools, different engines (MangaOcr input vs AOT img/mask). | Parallel-safe. |
| F7/F5 "do not pursue" vs race-condition P0-1 | The `nativeGuard` that blocks F5 is the same one P0-1 fixes the SIGSEGV on. P0-1 makes `forceReleaseNativeBuffers` safe; it does NOT unlock F5 (the mutex still serializes inference). | Independent. F5 stays blocked. |
| Speed estimates unverified | Source plan's "~35-88ms/page" and "~1-5%" are estimates — no on-device benchmarking exists (same gap as AOT). | Treat as estimates. On-device measurement is a separate infrastructure effort (see §6). |

---

## 5. Files To Touch

**P1a:**
- `RenderColorEstimator.kt:127-132, 152, 170`
- `RenderColorEstimatorSamplingTest.kt` (extend) / `RenderColorEstimatorTest.kt` (extend)

**P1b:**
- `TranslationPipeline.kt` (batch: move compress from ~2463 to after ~1198; single-page: from ~2597 to `translateSinglePageHttpRender`)
- New ordering test in TranslationPipeline test family

**P2a:**
- `MangaOcrEngine.kt:329-387` (refactor preprocess) + `:364-379` (single-pass write)
- New `MangaOcrPreprocessTest.kt`

**P2b (if pursued):**
- `GoogleTranslator.kt:14, 20, 26-30, 33, 68` (inject + URL + batching)
- `app/build.gradle.kts` (add MockWebServer)
- New `GoogleTranslatorBatchTest.kt`

---

## 6. Risks & Assumptions

- **P1a zero-risk claim** rests on `extractClusters` being pure and deterministic. Verified it's a 5-iteration 2-means (RenderColorEstimator.kt:219) — deterministic given input. Safe.
- **P1b ordering constraint** (`cleanedImageName` set before `tryRender`): the source plan asserts this is satisfiable. Must verify in implementation — if `tryRender` can fire before `send()` returns, the move is unsafe.
- **P2a CHW layout preservation:** the source plan flags this. The pure-function extraction makes it testable.
- **P2b URL-length ceiling (~16KB):** the source plan flags text-heavy pages may exceed it. The cap-and-chunk design handles this but adds complexity. Real risk of rate-limiting (429 + bot-detection) if chunks fire too fast — may need throttling.
- **Speed estimates are estimates.** No on-device benchmarking harness exists. Building one is out of scope here but is the prerequisite for verifying any claimed speedup.
- **Factual correction to source plan:** it says "5 `recomputeFor` call sites." There are **4** (908, 1948, 1978, 2115). The listed line numbers are correct; only the count is wrong. Doesn't affect P1a (which is about `extractClusters` dedup, not `recomputeFor`), but the source plan's F8-OptionA safety argument ("needs a guard test proving every render path calls recomputeFor") should target 4 sites, not 5.

---

## 7. Out of Scope

- On-device benchmarking infrastructure (large, separate effort; would benefit all plans).
- F8-OptionA (delete first-pass estimate) — source plan defers this; needs a guard test proving every render path calls `recomputeFor` (the 4-site count corrected above).
- Shared `OkHttpClient` across translators (cold-path concern, rare config changes — source plan calls it low-priority).
- `BitmapPool` byte-accounting (latent over-retention risk, source plan calls low-priority — eviction eventually kicks in).

---

## 8. Method Note

Source plan audited 2026-07-12: 21/24 claims verified exactly; 1 factual error (`recomputeFor` count: 4 not 5); 2 stale line refs (Lane overlap cite 766-772 is a KDoc — real code at 1105/1237/1281; stage-flow diagram orders panel-detect before color-est-pass1 — actually after). All fix locations verified accurate. Testability facts verified separately: RenderColorEstimator already unit-tested (RenderColorEstimatorSamplingTest.kt:37); MangaOcrEngine.preprocess private+Bitmap-coupled; GoogleTranslator URL hardcoded; no androidTest/ dir.

**No code changes made. Design only. Awaiting approval.**
