# Translation Pipeline Efficiency Investigation

**Date:** 2026-07-12
**Scope:** Full pipeline beyond inpainting — OCR, detection, segmentation, HTTP translation, rendering, memory, orchestration. Investigation only. No code changes.
**Method:** 4 parallel mapping agents → 3-round critique loop (defender verifies + subagents attack/refine). All claims verified against live code.
**Companion reports:** `TRANSLATION_RACE_CONDITION_INVESTIGATION_2026-07-11.md`, `INPAINTING_EFFICIENCY_INVESTIGATION_2026-07-12.md`

---

## Executive Summary

**The pipeline is already well-optimized.** The architecture's core design choices are correct:
- ONNX ‖ HTTP overlap exists (batch producer/consumer splits permit-held ONNX work from network-bound translation).
- The single `translatorPermit = Semaphore(1)` serializes ONNX work for a real reason (native session lifecycle + OOM avoidance), not by oversight.
- The `nativeGuard` Mutex preventing within-stage parallelism is a necessary SIGSEGV guard.
- Buffer pooling is comprehensive (9 DirectBufferPools, ~56MB resident ceiling, identity-tracked).

**The critique loop was devastating to the initial findings.** Of 7 candidate inefficiencies, 3 were refuted (false positives or in the noise), 2 were real but low-priority, and the highest-impact finding was one the initial scan MISSED (redundant color estimation + JPEG encode under permit).

**Honest answer to "can we push for more speed?":** Moderately, for small wins (~35–90ms/page out of 5–15s = 0.5–1.5%). The safe optimizations are micro-refactors. The big-ticket items are correctly blocked by load-bearing safety mechanisms.

---

## 1. Pipeline Architecture (Verified)

### 1.1 Stage Flow

```
Page source bytes
    ↓ [under translatorPermit]
Decode (BitmapFactory, sample-sized)          ← F7: under permit
    ↓
Text detection (RT-DETR, NNAPI, 640×640)      ← whole page
    ↓
Bubble segmentation (YOLO11-seg, NNAPI)        ← whole page, every page
    ↓ [per detected text box]
OCR (MangaOCR ViT encoder+decoder, OR PaddleOCR det+rec)
    ↓
Panel detection (YOLO-nano, NNAPI)             ← whole page, if blocks exist
    ↓
Color estimation pass 1 (against original bitmap)  ← F8: redundant on inpaint path
    ↓
Inpainting (AOT-GAN XNNPACK OR CPU FAST)
    ↓
JPEG encode cleaned bitmap                    ← F9: under permit, 30-80ms
    ↓ [permit RELEASED]
HTTP translation (AI batch / DeepL batch / Google per-block / MLKit local)
    ↓
Color estimation pass 2 / recomputeFor (against cleaned bitmap)
    ↓
Persist metadata
    ↓ [reader display path, separate]
TranslationOverlayView draws text on SSIV (Main thread, lightweight)
```

### 1.2 Concurrency Model

| Path | Phase 1 (permit-held) | Phase 2 (no permit) |
|---|---|---|
| **Single-page** (reader) | decode → detect → OCR → inpaint → JPEG encode | HTTP translate → color recompute → persist |
| **Batch** (chapter) | Lane A: same as above, sends to channel | Lane B: HTTP translate → color recompute → render |

**Overlap exists**: In batch mode, Lane A (ONNX) and Lane B (HTTP) run concurrently via `produce{}`/consumer. While page N translates over HTTP, page N+1's decode+detect+OCR+inpaint runs. This is documented and intentional (`TranslationPipeline.kt:766-772`).

**No `async`/`awaitAll` within stages.** This is correct — the `nativeGuard` Mutex (`RoiPageRecognitionEngine.kt:87,273`) wraps the entire detect+OCR loop because `close()` (on language change/stop) can free native ONNX sessions mid-call → SIGSEGV. Parallelizing per-ROI OCR would have every coroutine block on `nativeGuard` anyway.

---

## 2. Findings (9 total, post-critique)

### 2.1 REAL — Worth Fixing

#### F8 — Redundant double color estimation
**Impact: ~2–8ms/page. Priority: P1 (safest fix).**

`RenderColorEstimator.estimate()` runs during analyze (`RoiPageRecognitionEngine.kt:389`, against original bitmap). Then `recomputeFor()` runs during render (`TranslationPipeline.kt:908/1948/1978/2115`, against cleaned bitmap). On the default inpaint path, **both run and the first is overwritten.**

The comment at `RoiPageRecognitionEngine.kt:386-388` documents the first pass as "a fallback if inpainting is skipped." However, FAST mode still produces a cleaned bitmap (median fill, not original), so `recomputeFor` always runs against a genuinely modified bitmap. There is **no path where a rendered block reads the first-pass color** — verified by tracing all 5 `recomputeFor` call sites.

**Within each `estimate()` call**: `decideTextFill` (line 127) and `sampleBackgroundLuma` (line 130) each independently call `extractClusters` (5-iteration 2-means) AND `bubbleInteriorMask` on the same pixels. This doubles the k-means work needlessly.

**Safest fix (Option C):** Refactor `estimate()` to call `extractClusters` once and feed both `colorPolicy` and `sampleBackgroundLuma`. Halves k-means iterations. Zero behavioral change. ~15 lines.

**Riskier fix (Option A — defer):** Skip the first-pass `estimate` entirely. Semantically safe today (no rendered block reads it) but removes a documented fallback. Needs a guard test proving every render path calls `recomputeFor`.

#### F9 — JPEG encode of cleaned bitmap under the permit
**Impact: ~30–80ms/page. Priority: P1 (highest total savings).**

`pageTranslation.cleanedBitmap!!.compress(JPEG, 90, os)` at `TranslationPipeline.kt:2463` (batch) and line 2597 (single-page), inside the permit-held ONNX phase. This is pure CPU work (software JPEG encoder), no ONNX dependency, no native session interaction. The `[inpaint_encode]` log already exists — timing is measurable.

Moving the encode OUT of the permit block (after `send`, before/during translate) lets the next page's decode+OCR start 30–80ms sooner.

**Safer than F7** (decode-off-permit) because:
- No extra concurrent bitmap — the cleaned bitmap already exists.
- JPEG encode is CPU-bound, cannot OOM (fixed-size output stream).
- No interaction with ONNX native memory.
- Only requirement: `cleanedImageName` must be set before `tryRender` reads it — an ordering constraint, not a memory risk.

#### F3 — MangaOcrEngine intermediate FloatArray
**Impact: ~5–15ms/page. Priority: P2.**

`MangaOcrEngine.kt:364`: `FloatArray(1*3*224*224)` = ~588KB allocated fresh per `recognize()` call, then copied into the pooled direct buffer. PaddleOCR engines write directly to the pooled buffer in a single pass; MangaOcr does it in two passes (build FloatArray → copy to direct buffer).

At 30 regions/page = ~18MB heap churn. The fix mirrors Paddle's single-pass `out.put` loop. The CHW layout must be preserved. Low risk — identical output values.

**Correction from initial finding:** MangaOcr **already pools** the ORT-facing direct buffer (`inputPixelPool`, line 41). Only the intermediate FloatArray is un-pooled. The initial claim "MangaOCR doesn't pool" was half-false.

#### F1 — GoogleTranslator per-block HTTP
**Impact: seconds/page for Google users. Priority: P3 (few users).**

`GoogleTranslator.kt:26-30`: `pages.mapValues { v.blocks.map { translateText(...) } }` — one GET per block, sequentially. A 20-block page = 20 round-trips. Plus `OkHttpClient()` with bare defaults (10s timeouts, line 20).

**Caveats (from critique):**
- Google is NOT the default translator (MLKit is). Most users use AI translators (DeepSeek/OpenRouter) which batch correctly.
- The free `translate.google.com/translate_a/single?client=gtx` endpoint accepts a single `q=` param — no batch API.
- Parallel calls would trigger faster rate-limiting (429 + HTML bot-detection).
- The fix (concatenate blocks with delimiter, single request, split response) is ~5 lines but risks URL-length ceiling (~16KB) on text-heavy pages. Must cap or chunk.

---

### 2.2 REFUTED — False Positives or In the Noise

#### F2 — OnnxBubbleSegmenter unpooled allocations — **IN THE NOISE**
The 4.8MB `ByteBuffer.allocateDirect` per page (line 45) is a single `malloc` (sub-microsecond), GC'd trivially. The 640×640 bitmap + IntArray are one-shot ~3.2MB. Total ~8MB transient, once per page, against segment inference of ~300–600ms. Allocation+free is <5ms. **Not worth pooling.**

**Correction:** "Runs on EVERY page unconditionally" was **false** — the segmenter is optional (`bubbleSegmenter?.segment(bitmap) ?: emptyList()`, line 276). It's null-gated on asset availability.

**The real question** (not an efficiency issue): should the segmenter run in FAST inpaint mode? That's a feature/quality decision, not a pooling optimization.

#### F4 — RoiPageRecognitionEngine.cropBitmap unpooled — **FALSE POSITIVE**
`BitmapPool` is keyed by exact `"${width}x${height}"`. ROI crops are heterogeneous sizes (100×40, 230×180, 50×400...). Pool hit rate = **zero**. Worse, pooling variable-size crops would fill the pool with wrong-sized bitmaps that never reuse, wasting memory. The current `createBitmap`+`recycle` is **the correct design** for variable-size crops. Cost is ~0.1–0.5ms per crop × 30 = 6–15ms/page. In the noise vs OCR.

#### F5 — No parallelism within stages — **LARGELY FALSE**
Stage-level parallelism **exists**: Lane A (ONNX) ‖ Lane B (HTTP) via batch producer/consumer. This is the highest-value overlap.

Within-stage parallelism is **impossible by design**: `nativeGuard` Mutex (`RoiPageRecognitionEngine.kt:273`) wraps the entire detect+OCR loop. Adding `async`/`awaitAll` would have every coroutine block on the mutex. The mutex prevents `close()` (language change/stop) from freeing native sessions mid-inference → SIGSEGV. Removing it reintroduces a crash class.

#### F6 — PaddleOCR vertical CJK per-glyph OCR — **EDGE CASE, correct design**
`recognizeVerticalColumnPerChar` (line 1038) splits vertical CJK columns into individual glyphs, runs rec once per glyph. Only fires when ALL are true: (a) JP/ZH/KO, (b) tall vertical box, (c) PaddleOCR engine, (d) >1 glyph row. For horizontal manga/manhwa (majority), this path never executes. For MangaOcr (reads vertical natively), no per-glyph loop at all. It's a maintainer-validated correctness workaround — PaddleOCR's CTC misreads rotated whole columns.

#### F7 — Decode under permit — **RISKY, DO NOT PURSUE**
Decode (50–150ms) is under the permit (`TranslationPipeline.kt:1140,1674`). Moving it off-permit would overlap with the prior page's OCR — BUT requires 2 full-size bitmaps alive simultaneously (~2×4–12MB). On armeabi-v7a (32-bit, ~192MB heap), this is the OOM the permit prevents. The `TranslationMemoryBudget` preflight gates exist precisely for this. **The serial decode cost is the price of OOM safety.**

---

## 3. Memory Management Assessment

### 3.1 DirectBufferPool — well-designed
9 pools across the app, total resident ceiling ~56MB. Identity-tracked (`IdentityHashMap`) to handle FloatBuffer position/limit mutations. The documented June-2026 leak (489 stranded buffers) is **fixed**.

| Pool | Capacity | maxPoolSize | Ceiling |
|---|---|---|---|
| Text detector input | 4.69 MiB | 2 | 9.4 MiB |
| Panel detector input | 4.69 MiB | 2 | 9.4 MiB |
| AOT inpaint image | 6.75 MiB | 2 | 13.5 MiB |
| AOT inpaint mask | 2.25 MiB | 2 | 4.5 MiB |
| MangaOcr K-cache | 1.00 MiB | 2 | 2.0 MiB |
| MangaOcr V-cache | 1.00 MiB | 2 | 2.0 MiB |
| MangaOcr input pixels | 0.57 MiB | 2 | 1.15 MiB |
| Paddle det input | 6.23 MiB | 2 | 12.5 MiB |
| Paddle rec input | 0.88 MiB | 2 | 1.76 MiB |

**Note:** Paddle det + MangaOcr are mutually exclusive OCR backends; realistic peak ≈ 43–53 MiB. `OnnxBubbleSegmenter` is NOT in this table (doesn't pool — see F2, in the noise).

### 3.2 BitmapPool — latent over-retention risk
`BitmapPool` counts bitmaps, not bytes (`maxTotalPoolSize=12` for ARGB). On large pages (1500×2000 = ~12MB each), 12 bitmaps = ~144MB resident before eviction. The 32MB per-bitmap cap catches single huge bitmaps but doesn't bound the total. **Low priority** — eviction does eventually kick in, and `releaseAll()` is called on OOM recovery.

### 3.3 Memory gates — defer, not prevent
`TranslationMemoryBudget` gates (`canStartDecode`, `canStartAnalyze`, `canStartInpaint`, `canRunNeuralInpaint`) are **advisory preflight checks** against JVM heap + `ActivityManager` system memory. They return `Defer`/`false`; callers retry later. They do NOT account for the ~56MB resident DirectBufferPool memory or ONNX session weights — those are covered by rough heuristic constants (128MB/96MB system headroom). Real OOM prevention is the reactive `catch (OutOfMemoryError)` + `BitmapPool.releaseAll()` + `System.gc()` cluster.

---

## 4. HTTP Translation Assessment

### 4.1 Batching by translator

| Translator | Batching | HTTP calls/page |
|---|---|---|
| DeepSeek, OpenRouter, LmStudio, OpenAI-compatible | ✅ All blocks in one request | 1 (reader) / 1 per chunk (batch) |
| Gemini | ✅ All blocks in one `generateContent` | 1 |
| DeepL | ✅ Repeated `text` form params | 1 |
| **Google** | ❌ One call per block | N (20+) |
| MLKit | N/A (on-device, per-line) | 0 |

### 4.2 HTTP correctly NOT under permit
Both paths run HTTP outside `translatorPermit`:
- Single-page: `translateSinglePageHttpRender` (line 677, after permit phase returns)
- Batch: Lane B consumer loop (line 1237/1281, no permit)

This is documented and intentional — the phase split lets the next page's ONNX overlap the current page's network call.

### 4.3 Missing: shared OkHttpClient
Every translator creates its own `OkHttpClient` with default pool sizes (5 connections, 5min keepalive). Translators are rebuilt on every config change (`ensureEnginesBuiltFor`), discarding pools. Low-priority cold-path concern (config changes are rare).

---

## 5. Rendering Assessment

### 5.1 Overlay, not baked — VERIFIED CORRECT
Memory note confirmed: translated text is a live overlay (`TranslationOverlayView`), NOT baked into the bitmap. `PageTranslationState.displayImageName` returns `cleanedImageName` — the SSIV shows the inpainted background, the overlay draws text on top.

### 5.2 Render path is efficient
- `RenderColorEstimator` and `TextLayoutPlanner` are pure (no Canvas/Bitmap drawing).
- Drawing happens in `TranslationOverlayView.onDraw` (Main thread, lightweight — precomputed layouts, one `drawText` per block).
- No blocking I/O on Main thread.
- Pan/zoom coalesced to one redraw per Choreographer frame.

---

## 6. Recommendations (Priority Order)

### P1 — Safe, immediate wins (~35–90ms/page total)

**P1a: Merge duplicate `extractClusters` in `RenderColorEstimator.estimate()` (F8-OptionC)**
Refactor `estimate()` to call `extractClusters` once, feed both `colorPolicy` and `sampleBackgroundLuma`. Also merge the duplicate `bubbleInteriorMask` call. ~15 lines. Zero behavioral change. Safest fix in the investigation.

**P1b: Move JPEG encode off the permit (F9)**
Move `cleanedBitmap.compress(JPEG, 90, os)` from `inpaintPage` (under permit) to after `send()` / in the HTTP phase. The cleaned bitmap already stays alive. 30–80ms/page savings. No OOM risk (fixed-size output, no extra bitmap). Must ensure `cleanedImageName` is set before `tryRender` reads it.

### P2 — Low-risk, smaller wins

**P2a: Pool MangaOcr intermediate FloatArray (F3)**
Mirror Paddle's single-pass direct-write loop. Eliminates 588KB × 30 = 18MB heap churn per page. ~20 lines. Must preserve CHW layout.

**P2b: Batch GoogleTranslator (F1)**
Concatenate non-empty blocks with delimiter, single request, split response. ~5 lines + URL-length cap. Helps only Google users (minority). Low priority.

### P3 — Do NOT pursue (load-bearing safety mechanisms)

- **F7 (decode off-permit)**: OOM risk on armeabi-v7a. The memory preflight gates are load-bearing.
- **F5 (within-stage parallelism)**: Blocked by necessary `nativeGuard` mutex (SIGSEGV prevention).
- **F8-OptionA (delete first-pass estimate)**: Removes documented fallback. Defer until guard test exists.

---

## 7. Safety Assessment ("How safe is it to push for more speed?")

| Optimization | Risk | Verdict |
|---|---|---|
| P1a (merge extractClusters) | **ZERO** — pure dedup, identical output | ✅ Ship immediately |
| P1b (JPEG off permit) | **LOW** — ordering constraint, no memory risk | ✅ Ship after testing |
| P2a (pool MangaOcr FloatArray) | **LOW** — identical values, CHW preserved | ✅ Ship after testing |
| P2b (batch Google) | **LOW** — network only, cap URL length | ⚠️ Low priority |
| F7 (decode off permit) | **HIGH** — OOM on 32-bit devices | ❌ Do not pursue |
| F5 (within-stage parallelism) | **CRITICAL** — SIGSEGV on close-during-inference | ❌ Do not pursue |

**Direct answer:** The pipeline is already fast and well-architected. The safe optimizations (P1a + P1b) save ~35–88ms/page — noticeable in aggregate but not transformative. The unsafe optimizations (F7, F5) are blocked by real safety mechanisms that exist for good reasons. **There is no free lunch left.**

---

## 8. Files To Touch (when implementing)

**P1a (merge extractClusters):**
- `app/src/main/java/eu/kanade/translation/rendering/RenderColorEstimator.kt` (lines 127-132: merge `decideTextFill` + `sampleBackgroundLuma` into one `extractClusters` call; lines 152, 170: remove duplicate `bubbleInteriorMask`)

**P1b (JPEG off permit):**
- `app/src/main/java/eu/kanade/translation/TranslationPipeline.kt` (batch: move compress from `inpaintPage` ~line 2463 to after `send` ~line 1198; single-page: move from `processSinglePage` ~line 2597 to `translateSinglePageHttpRender`)

**P2a (pool MangaOcr FloatArray):**
- `app/src/main/java/eu/kanade/translation/ocr/MangaOcrEngine.kt` (lines 364-379: replace FloatArray + copy with single-pass direct-write to `inputPixelPool`)

**P2b (batch Google):**
- `app/src/main/java/eu/kanade/translation/translator/GoogleTranslator.kt` (lines 26-30: concatenate blocks, single request)

---

## 9. Method Note

4 parallel mapping agents covered: OCR engines, pipeline orchestration, detection/segmentation/memory, HTTP/rendering. Then a 3-round critique loop:

- **Round 1** (1 attack agent): Devastating. Refuted F2 (segmenter pooling = noise), F4 (ROI crop pooling = false positive, would regress memory), F5 (no parallelism = largely false, nativeGuard is necessary). Corrected F3 (MangaOcr already pools direct buffer). Downgraded F1 (Google not default, endpoint can't batch). Found the investigation MISSED F8 (double color estimation) and F9 (JPEG under permit).
- **Round 2-3** (1 convergence agent): Pressure-tested F8 (traced all 5 recomputeFor call sites — first-pass is dead weight on rendered pages, but removing it deletes a documented fallback; safest fix is merging the duplicate extractClusters within each estimate). Assessed F7 as unsafe (OOM on armeabi-v7a). Confirmed convergence.

Key corrections from critique:
- "MangaOCR doesn't pool" → half-false (pools direct buffer, only intermediate FloatArray un-pooled)
- "Segmenter runs unconditionally" → false (optional, null-gated)
- "No parallelism in pipeline" → largely false (ONNX‖HTTP overlap exists and is documented)
- "ROI crops should use BitmapPool" → false positive (variable sizes = zero hit rate, would regress)
- "F8 is highest impact" → downgraded (~2-8ms, real but small; F9 JPEG encode is bigger at 30-80ms)

**No code changes made. Report-only.**

---

## Appendix — Refuted Claims

| Claim | Source | Verdict | Evidence |
|---|---|---|---|
| Segmenter runs unconditionally every page | Initial scan | **FALSE** | `bubbleSegmenter?.segment()` is null-gated (line 276) |
| MangaOCR doesn't pool buffers | Initial scan | **HALF-FALSE** | Pools ORT-facing direct buffer; only intermediate FloatArray un-pooled |
| No parallelism in pipeline | Initial scan | **LARGELY FALSE** | ONNX‖HTTP overlap exists (batch producer/consumer, documented) |
| ROI crops should use BitmapPool | Initial scan | **FALSE POSITIVE** | Variable sizes = zero hit rate; would regress memory |
| GoogleTranslator batching is easy | Initial scan | **OVERSTATED** | Free endpoint has single `q=` param, no batch API; parallel triggers rate-limiting |
| Decode-off-permit is "free 10%" | Initial scan | **REFUTED** | Requires 2 concurrent bitmaps = OOM on 32-bit devices |
| F8 (double color est) is highest impact | Round 1 critique | **DOWNGRADED** | ~2-8ms (getPixels + IntArray alloc); F9 (JPEG encode) is bigger at 30-80ms |
