# Translation Refactor — Adversarial Architecture Audit

> **Status:** Staff/Principal review of `translation-architecture-refactor.md` (the proposal).
> **Method:** Every claim below was verified against current source (`ChapterTranslator.kt`,
> `TranslationManager.kt`, `ChapterTranslationStore.kt`, `ReaderViewModel.kt`, both holders,
> `RoiPageRecognitionEngine.kt`, `MangaOcrEngine.kt`, `AOTInpainting.kt`, `OnnxRuntimeProvider.kt`,
> `TranslationMemoryBudget.kt`).
> **Stance:** The proposal correctly diagnoses the *orchestration* disease. It is silent or
> wrong about *throughput*, *memory realism*, and several places where the proposed design
> regresses against the current one. This document exists to break those assumptions.

---

## 1. Executive Summary

The refactor plan is a **correct and well-evidenced fix for the state/orchestration layer**.
The four-status mess, the mutable shared `ReaderPage` bus, the event fan-out, and the
duplicated render blocks are real and the proposed fixes are sound *in their lane*.

The plan is **dangerously incomplete** in three areas that determine whether this app can
actually ship 200-page chapters:

1. **It does not address throughput at all.** 10 s/page on a Snapdragon 8 Elite is not an
   orchestration problem — it is a *model + serialization* problem. No state machine makes
   OCR or the LLM call faster. The single biggest cost (autoregressive manga-ocr, up to 300
   decoder steps × N ROIs, forced onto 2 CPU cores) is untouched by every phase in the plan.

2. **Its memory model is physically wrong.** `TranslationMemoryBudget` measures the Dalvik
   heap (`Runtime.maxMemory()`), which is ~256–512 MiB on most "8 GB" phones — *not* the
   "500 MB–2 GB" the brief assumes. Worse, ONNX native tensors (k/v caches, inpainting
   activations) live in **native** memory that the heap budget cannot see. The proposal
   inherits this blindness and there is no `onTrimMemory()` anywhere in the codebase
   (verified).

3. **Two of its headline changes can regress against today.** (a) A *single* `PageState`
   enum collapses "is it done for scheduling?" with "what stage is it in / what's cached?",
   which destroys exactly the retry-from-render information the current ugly heuristics
   preserve. (b) "One `Flow<PageView>` per holder" only scales if implemented as a single
   shared upstream conflation — a naive `store.state.map { it[key] }` per holder is O(N²)
   work per emission.

Net recommendation: **do Phase 0 + Phase 1 (state machine, but split state from stage
metadata — see §8.1), and the bug fixes.** Do **not** proceed past Phase 1 expecting any
throughput gain. Before Phase 2+, measure (§6) and fix OCR (§8.6) — otherwise you ship a
beautifully-architected 10 s/page.

---

## 2. Critical Risks

| # | Risk | Evidence | Severity |
|---|---|---|---|
| R1 | **OCR is the throughput wall and the plan ignores it.** manga-ocr is autoregressive: up to `MAX_GENERATION_LENGTH=300` decoder steps **per ROI**, 30+ ROIs/page, each step a separate ONNX `sess.run` with JNI crossing, `forceCpu=true`, 2 threads. | `MangaOcrEngine.kt:217`, `OnnxRuntimeProvider.kt:128` | **Critical** |
| R2 | **No `onTrimMemory` anywhere.** Under system (not heap) pressure the app cannot evict bitmaps/sessions. Translation `scope` keeps running while backgrounded until a low-memory kill. | grep: none in `App.kt` or `translation/` | **Critical** |
| R3 | **Budget is heap-only; ONNX native memory is invisible.** `TranslationMemoryBudget.snapshot()` reads `Runtime.maxMemory()`/`freeMemory()`. ONNX tensors (`OnnxTensor` over direct `FloatBuffer`s), the `kCachePool`/`vCachePool` direct buffers, and inpainting activations are native — uncounted. `canRunNeuralInpaint` sizes only the *heap-side* float arrays. | `TranslationMemoryBudget.kt:26`, `MangaOcrEngine.kt:36/155` | **High** |
| R4 | **Native uncancellable holds the sole permit for 120 s.** `withLeakProofPermit` watchdog is 120 s. A hung `inpaint()` (`sess.run`, also uncancellable) blocks *all* translation for two minutes. | `ChapterTranslator.kt:87`, `AOTInpainting.kt:385` | High |
| R5 | **Thermal throttling unhandled.** Sustained 2-core CPU OCR on a 50+ page chapter will trip thermal, dropping clocks 30–50%; 10 s/page silently becomes 15–20 s/page mid-chapter. No `PowerManager`/thermal awareness. | grep: none | High |
| R6 | **`closeEngines()` races an in-flight native `run`.** Runs *without* the permit; the `closed` flag only prevents the Kotlin NPE, not the underlying native use-after-free (acknowledged in code). | `ChapterTranslator.kt:417`, `RoiPageRecognitionEngine.kt:51` | High |
| R7 | **Store does a full O(N) map copy on every page update.** `pages.entries.associate { (k,p) -> k to p.copy() }` runs on *every* `updatePage`. 200-page chapter × ~4 transitions/page = ~800 full-map rebuilds. The immutability proposal does not fix this; it can worsen it. | `ChapterTranslationStore.kt:47` | Medium |
| R8 | **`translatePagesSequential` re-reads live store state per page but "done?" still has 3 definitions** that the state machine must actually unify, not just rename. | `TranslationManager.kt:484` vs `ReaderViewModel.kt:770` vs holder dedup | Medium |

---

## 3. Invalid Assumptions

### 3.1 The brief's memory assumption is wrong for Android
> "Android device with 8 GB RAM — realistically 500 MB–2 GB safely available."

**False.** `Runtime.maxMemory()` is the per-process Dalvik/ART heap, set by the OEM via
`largeHeap`/per-device profile. On most 8 GB phones it is **256–512 MiB**, not 1–2 GiB.
The whole `TranslationMemoryBudget` is calibrated against this heap (e.g. "20%/25% of
available heap"), and the 20-page OOM the code comments reference is *heap* OOM. Treat the
working budget as **≤512 MiB**, not 2 GiB. Native (ONNX) memory is on top of this and is
*not accounted for* (R3).

### 3.2 "Is a single state machine sufficient?" — No, not as specified.
The proposed `PageState` (`Pending/Recognizing/Translating/Inpainting/Rendering/Done/Failed/Textless`)
**conflates two orthogonal concerns**:

- **Terminality for the scheduler** ("should I enqueue this page again?") — binary.
- **Stage progress + cached artifacts** ("OCR+translate succeeded, render failed — retry
  *only render*") — multi-dimensional.

Today, the ugly-but-real `isRecognizedAndTranslated` heuristic (`ReaderViewModel.kt:798`)
exists precisely to express the second: it skips re-OCR/re-translate when those stages
already succeeded even though render failed. The proposed `Failed(atStage, reason, retry)`
**discards** the OCR and translation artifacts that a render-only retry needs — unless
`Failed` carries them, in which case it is no simpler than four statuses, just reshaped.

Once a page is `Done(renderedImage)`, you also **cannot query** "render succeeded but the
LLM actually returned a watermark-stripped empty block list" — the current `errorMessage`
slot (buggy, B9) at least surfaces this. A flat terminal enum hides it.

**Answer to the question:** state and stage metadata **must be separated**. See §8.1.

### 3.3 "Does one Flow per page scale to 200 pages?" — The framing is misleading.
Collector count scales with **attached holders**, not page count (~3–12, not 200). The real
scaling trap is upstream: if each holder does `store.state.map { it[pageKey] }`, a single
store emission runs the `map` body **once per attached collector**. That is fine at 6
holders but the proposal must **mandate a single shared conflation** (`store.state.stateIn`
or `shareIn` → per-holder `.map { derived[pageKey] }.distinctUntilChanged()`). A naive
implementation where each holder independently collects `store.state` creates N upstream
subscriptions. **The proposal does not specify this; a junior implementer will regress it.**

### 3.4 "Stages should remain independent jobs" — No, not across pages.
The `translatorPermit = Semaphore(1)` (`ChapterTranslator.kt:176`) exists *deliberately*
to keep one page's bitmap/tensor set alive at a time — it was the fix for the ~20-page OOM.
Cross-page stage pipelining (page N translate ∥ page N+1 detect) requires **two** decoded
bitmaps + two tensor sets resident → re-introduces the OOM the permit killed. Intra-page
pipelining is meaningless (each stage consumes the previous's output). So "independent
jobs" is the wrong axis; the constraint is memory, and the permit is the correct expression
of it. Do not promise stage parallelism without re-budgeting memory.

### 3.5 "10 s/page is an architecture problem." — It is not.
See §6. The 10 s is ~OCR + ~LLM + ~inpaint, all model/IO-bound. Orchestration overhead
(event fan-out, store copy, dedup) is in the **milliseconds**. The entire refactor saves
single-digit milliseconds per page while the wall clock is dominated by 5–8 s of autoregressive
decode. Sell the refactor for what it is: **correctness and maintainability**, not speed.

---

## 4. Scalability Analysis

### 4.1 20 / 50 / 100 / 200-page chapters

| Size | Today | After proposed refactor | Real limiter |
|---|---|---|---|
| 20 | Works (the permit keeps it alive). ~3 min on flagship. | Same throughput, fewer re-translate loops. | OCR + LLM |
| 50 | Works; ~8 min. Thermal starts mid-chapter. | Same. | Thermal (R5) |
| 100 | Borderline. `consecutiveOomCount` auto-fallback to MLKit (no inpainter) likely trips. Store JSON grows (~100 pages × blocks). | Same OOM risk; JSON decode on reopen gets slow. | Heap + native fragmentation |
| 200 | **Will not complete reliably.** No `onTrimMemory`, no eviction, store copy is O(N)/emission, `isChapterTranslated` re-parses the entire JSON per chapter list build (separate audit F2). | Throughput unchanged. Still no eviction. | All of the above + process death |

### 4.2 Per-emission store cost (R7) — scales linearly with chapter size
`ChapterTranslationStore.updatePage` (line 47) does `pages.entries.associate { ... copy() }`
on **every** update. For N pages and T transitions/page:
- allocations ≈ N × T new `PageTranslation` wrappers + N × T map entries,
- each emission also re-runs every `observe(pageKey)` collector's `map` body (§3.3).

At N=200, T≈4, that's 800 full-map rebuilds + 800 × (attached holders) map-lambda runs.
The immutability proposal makes each *field change* a `copy()` too. Net: **the store must
switch to a persistent/structural-share map** (kotlinx.collections.immutable
`PersistentMap`) so an unchanged page shares its node. This is a precondition for the
immutable-snapshot design, and the plan does not list it.

### 4.3 `isChapterTranslated` full-JSON-decode (separate-audit F2) compounds at scale
Every downloaded chapter in the manga-detail list triggers a full
`Json.decodeFromStream<Map<String,PageTranslation>>` *just to check `.isNotEmpty()`*
(`TranslationManager.kt:200`). A 200-page translated chapter's JSON can be hundreds of KB.
This is out of scope of the refactor but **must be fixed before 200-page chapters are a
real target**, or the manga-detail screen becomes unusable.

---

## 5. Memory Analysis

### 5.1 What `TranslationMemoryBudget` can and cannot see
- **Sees:** Dalvik heap (`Runtime.maxMemory/totalMemory/freeMemory`).
- **Blind to:**
  - ONNX native tensor backing (the `FloatBuffer.wrap` tensors are heap-side, but ORT also
    allocates internal activations natively for the inpainting session — a 768² fp32 forward
    pass is ~17 MB of activation the budget never counts).
  - `DirectBufferPool` for k/v caches (`MangaOcrEngine.kt:36`) — direct ByteBuffers, off-heap.
  - Bitmap *native* pixel memory for `Bitmap.Config.ARGB_8888` on newer ART (pixel memory
    is moved to native `Ashmem`/host malloc post-Android O). `BitmapPool` reuses these but
    the budget's `availableHeapBytes` does not reflect them.

**Consequence:** `canRunNeuralInpaint`/`hasHeadroomForPrefetch` can report "headroom" while
native memory is near exhaustion → the subsequent OOM (or low-memory-kill) is
"unexplained". R3 is not theoretical.

### 5.2 Decoded bitmaps resident
With the `translatorPermit(1)`, the translator keeps **one** decoded page at a time — good.
The reader keeps its own separately (the comment at `ChapterTranslator.kt:1745` explicitly
flags the concurrent reader decode as the OOM trigger). So peak ≈ 2 full-res ARGB_8888
pages (reader + translator) + inpainting working set. At 45 MP cap (`MAX_FULL_RES_DECODE_PIXELS`)
that's 2 × 180 MB = 360 MB just in two page bitmaps — **most of a 512 MiB heap**. The 25%/20%
decode thresholds exist for this reason and are correctly conservative.

### 5.3 Rendered/cleaned page cache — **unbounded**
Companion `.rendered.webp` / `.cleaned.png` files accumulate on disk per translated page
and are never evicted by quota (`deleteCompanionImages` only runs on chapter/manga delete).
A 200-page chapter at ~150 KB/webp = ~30 MB; across a library this is unbounded storage
growth. Out of refactor scope but in scope for "200-page target device".

### 5.4 `onTrimMemory()` — required behavior (verified absent)
- `TRIM_MEMORY_RUNNING_LOW` / `MODERATE`: stop prefetch (`autoTranslatePrefetchCount → 0`),
  drop the `BitmapPool`, cancel non-current-page translation.
- `TRIM_MEMORY_COMPLETE`: `translatorStop()`, `closeEngines()` (properly, §8.4), clear
  `readerPageStreams`.
- This must be wired at the `Application`/`ReaderActivity` level and call into
  `TranslationManager`. Today nothing handles it.

### 5.5 What to pool vs never pool
- **Pool:** `Bitmap` (already, `BitmapPool`), `DirectBuffer`s for k/v cache (already). Add:
  the large `IntArray`/`FloatArray` scratch buffers in `OnnxPageTextDetector.preprocess`
  (640² = 1.6 M Ints ≈ 6.4 MB, allocated per detect) and `AOTInpainting.inpaint`
  (`3 * totalPixels` floats). These are per-page allocations that churn the heap; a
  thread-local scratch would help.
- **Never pool:** `OnnxTensor` (lifecycle bound to session/run), `OrtSession.Result`, the
  `translatedStream` closures (they capture page identity — pooling would alias across
  pages). Current code correctly closes these in `finally`.

---

## 6. Throughput Analysis

### 6.1 Where the ~10 s/page actually goes (reasoned from code; measure to confirm)
| Stage | Cost driver | Estimated share |
|---|---|---|
| **OCR (manga-ocr)** | Autoregressive: encoder (1 pass) + up to **300 decoder steps × R ROIs**. Each step = `sess.run` + JNI + cache copy. `forceCpu=true`, 2 threads. | **~5–8 s** (dominant) |
| **Text translation** | LLM HTTP (DeepSeek/Gemini/OpenRouter), 1 round-trip/page, serialized. | ~1–3 s |
| Inpaint (AOT) | 1 NNAPI forward per cluster, ≤768². | ~0.1–0.3 s |
| Detect | 1× 640² CPU forward. | ~0.05–0.1 s |
| Decode + render + persist | JPEG decode, color recompute, WEBP encode, atomic JSON write. | ~0.1–0.3 s |

**The refactor touches none of the bold rows.** Orchestr­ation (events, dedup, store copy)
is sub-10 ms/page — invisible next to OCR.

### 6.2 The theoretical bottleneck is CPU decode-bandwidth on an autoregressive model
Not GPU, not memory bandwidth, not disk. The NPU *could* help, but the code deliberately
force-disables it for the decoder because NNAPI partitions the decoder graphlet into ~145
segments with a sync per boundary × 300 steps (`OnnxRuntimeProvider.kt:118` reasoning is
correct). So on the current model, **NPU is the wrong tool** and CPU is the only option,
and CPU is capped at 2 threads because >2 caused SIGSEGV historically.

### 6.3 Measurements required before *any* throughput change (the plan proposes none)
The code already instruments all of this behind the `translation_diagnostics` pref — it just
isn't being used to decide architecture. Collect, per device class:
1. `[ocr]` P50/P95 **total** and `decoder_steps` count and per-step latency (`MangaOcrEngine.kt:272`).
2. `[translation_page]` end-to-end and `blocks` count (`ChapterTranslator.kt:1622`).
3. `[detection]`/`[inpaint]` per-stage ms.
4. LLM round-trip latency (not currently logged — add).
5. `decode sampleSize` distribution (`TranslationMemoryBudget.logSnapshot` decode tag).
6. Thermal state over a 50-page run (add `PowerManager.getCurrentThermalStatus` polling).

Without (1) and (2), any claim "the refactor is faster" is unfalsifiable.

### 6.4 What to optimize first (independent of the refactor)
1. **OCR model** — replace autoregressive manga-ocr with a **parallel-decode / CTC** Japanese
   OCR (one forward pass per ROI instead of ≤300). This alone takes 5–8 s → <0.5 s. This is
   the single highest-leverage change in the entire system and it is *not in the plan*.
2. **Batch LLM translation across pages** — the translators already flatten blocks per page;
   extend to N pages per request to amortize the round-trip (1–3 s → 0.2 s/page amortized).
3. **Move only the *encoder* to NNAPI** — it is a single big pass (the decoder's problem),
   unlike the decoder. The current `forceCpu=true` is applied to the whole OCR pipeline
   (`MangaOcrEngine.kt:61`); splitting encoder (NNAPI) from decoder (CPU) is free throughput.
4. **Stop the double color-estimate** (B6) — minor, but the plan already has this.

---

## 7. UX Analysis (Progressive Translation — proposal H)

**Recommendation: do not ship progressive rendering as a default. At most, gate it behind
the existing `translation_diagnostics` pref as a debug view.**

- **Visual instability is real.** OCR overlay → translation overlay → cleaned → rendered on a
  manga page means 3–4 full-image swaps per page within a few seconds. For a reader whose
  *purpose* is the final clean page, every intermediate frame is noise. On a webtoon
  (continuous scroll) this is genuinely disorienting because adjacent pages swap at
  different times.
- **No successful precedent in the manga-translation space.** Cotrans (the de-facto
  gold-standard browser pipeline that this feature descends from) shows exactly two states:
  original, then final. So does MangaTr/Pocket Manga. Progressive overlay is a debug affordance.
- **The current app is already correct here:** original → spinner overlay → final rendered.
  The overlay/spinner already communicates "working". Adding intermediate visual stages
  adds *perceived* latency (the user sees unfinished work they previously didn't) without
  reducing real latency.
- **Disable progressive when:** always, for end users. Enable only when the diagnostics pref
  is on, or for a per-page long-press "inspect" affordance.

The one legitimate progressive signal — **showing a spinner + dimming the in-flight page**
— is already implemented (`frame.showProcessingOverlay`, `syncTranslationStatus`). Do not
confuse this (good) with multi-stage image swapping (bad).

---

## 8. Recommended Architecture Changes

### 8.1 Split state from stage metadata (revision to proposal 5.1)
Keep a terminal/scheduling enum, but carry stage metadata separately so render-failed-with-
cached-OCR is expressible:

```kotlin
sealed interface PageLifecycle {                       // scheduler-facing: "enqueue?"
    data object Pending : PageLifecycle
    data object Done : PageLifecycle                    // terminal success
    data object Textless : PageLifecycle                // terminal, nothing to do
    data class Failed(val stage: Stage, val reason: String, val retry: Int) : PageLifecycle
}
data class PageProgress(                                // always present, orthogonal
    val lifecycle: PageLifecycle,
    val currentStage: Stage?,                           // Recognizing/Translating/.../null
    val cachedOcr: OcrRef?,                             // survives a render-only retry
    val cachedTranslation: TranslatedRef?,
    val renderedImage: ImageRef?,
)
```
`PageProgress` is the immutable snapshot in the store; `PageLifecycle` is what the scheduler
and "is it done?" check. This keeps the proposal's "one definition of done" *and* preserves
the retry-from-render information today's heuristics encode.

### 8.2 PersistentMap for the store (precondition for immutability)
Replace `LinkedHashMap` + per-update `associate{copy()}` with
`kotlinx.collections.immutable.PersistentMap`. `put` returns a structurally-shared map in
O(log N) instead of O(N) copy. This is mandatory before Phase 2, not optional — otherwise
immutability makes R7 worse.

### 8.3 Per-page Flow with a single shared upstream (revision to 5.5)
```kotlin
val pageViews: Flow<Map<String, PageView>> =
    store.state.map { deriveAllPageViews(it) }.distinctUntilChanged()
// holder:
pageViews.map { it[pageKey] }.distinctUntilChanged().collect(...)
```
One upstream subscription (conflated via `StateFlow`), per-holder derivation only filters.
Explicitly forbid per-holder `store.state` subscription in review.

### 8.4 `TranslationEngineRegistry` with cancel-and-await (fixes R6/B14)
`closeEngines()` must acquire the permit *or* use a cancel-and-await on the in-flight job
rather than the polled `closed` flag. The current "runs without permit to avoid re-entrancy"
is a deadlock-avoidance hack that leaves a native use-after-free window. Restructure so
`stop()` cancels the job first, *awaits* unwind (bounded), then closes — the
`withLeakProofPermit` watchdog already proves bounded await is feasible.

### 8.5 Wire `onTrimMemory` (fixes R2)
`ReaderActivity.onTrimMemory(level)` → `TranslationManager.onMemoryPressure(level)`:
- `MODERATE`: pause prefetch.
- `LOW`: `BitmapPool.releaseAll()`, cancel non-current jobs.
- `COMPLETE`: `stopAllTranslation()` + `closeEngines()` (via 8.4).

### 8.6 Throughput track (parallel to, not part of, the refactor)
- Evaluate a CTC/parallel-decode Japanese OCR to replace manga-ocr (§6.4-1).
- Split OCR encoder onto NNAPI, decoder on CPU (§6.4-3).
- Cross-page LLM batching (§6.4-2).
- Add thermal-aware prefetch backoff (R5): poll `PowerManager.getCurrentThermalStatus`;
  when ≥ `MODERATE`, cut `autoTranslatePrefetchCount` to 0 and lower decode resolution.

### 8.7 Lower the permit watchdog for inpaint (R4)
120 s for a single inpaint `sess.run` is too long. Either (a) give inpaint its own shorter
deadline (e.g. 20 s) since it's a single forward pass, or (b) run inpaint *outside* the sole
permit if memory allows (it does not today — keep it in, but shorten the watchdog).

---

## 9. Priority Order of Changes

Ordered by (production impact) ÷ (risk), and **separating correctness from throughput** —
they are independent tracks.

**Correctness/orchestration track (the refactor):**
1. **Phase 0 bug fixes** — B1 (`MangaOcrEngine.close()`), B2 (`GoogleTranslator` client),
   B4 (RTMTH filter dedup). Ship now, zero architecture risk. *(Already correctly sequenced
   by the plan.)*
2. **`onTrimMemory` wiring (8.5)** — add before any scale claim. Missing today, cheap to add.
3. **Phase 1 state machine, but split state/stage (8.1)** — keystone correctness fix.
4. **`TranslationEngineRegistry` cancel-and-await (8.4)** — fixes R6/B14 native race.
5. **PersistentMap store (8.2)** — precondition; do before Phase 2.
6. Phase 2 (`advance()` consolidation, pure stages, color-estimate-in-render) and Phase 3
   (per-page Flow, 8.3) — only after 5.

**Throughput track (do NOT block the refactor on this, but do NOT claim the refactor
delivers speed):**
7. **Measure** (§6.3) on ≥3 device classes before touching anything.
8. OCR encoder→NNAPI split (8.6) — lowest-risk throughput win.
9. Cross-page LLM batching (8.6).
10. Replace autoregressive OCR (8.6) — highest-leverage, highest-effort.

---

## 10. What should NOT be changed from the current system

The plan's §3 list is correct and verified. Emphasizing the ones a refactor最容易 over-rotate
on:

- **`translatorPermit = Semaphore(1)`.** It is not a bottleneck to remove; it is the memory
  contract that prevents the 20-page OOM. Any "parallelize stages" change must re-prove the
  memory budget first. **Do not widen it.**
- **ONNX EP strategy: detector + OCR decoder on CPU, AOT inpaint on NNAPI, QNN deferred.**
  Verified sound (`OnnxRuntimeProvider.kt`, `DeviceCapability.kt`). The NNAPI-on-decoder
  partitioning analysis is real; do not "try NNAPI everywhere" to chase throughput — it
  regresses (§6.2).
- **`ChapterTranslationStore` atomic write-temp-then-rename + lazy materialization.**
  Production-grade. Keep. (Only the in-memory map representation changes — 8.2.)
- **`withLeakProofPermit` watchdog.** Necessary (uncancellable native code is real). Keep
  the mechanism; only tune the inpaint deadline (8.7).
- **`WebtoonAdapter.areContentsTheSame == true` + no `notifyItemChanged`-without-payload.**
  Correct — adapter-driven refresh reintroduces the blink. The per-page Flow (8.3) must
  preserve "the adapter does not drive refresh".
- **`bindGeneration` guard on the webtoon holder + the stable-image-name dedup.** Port to
  the pager holder (B13); do not replace with "Flow handles it" — a Flow does not protect
  against a holder re-bound mid-decode unless the collection is generation-scoped.
- **`SmartBubbleTextCleaner` / `AOTInpainting` algorithms.** The math is fine; only the
  plumbing (color estimate site, status writers) is diseased.
- **Original→spinner→final UX.** Do not regress to progressive multi-stage rendering (§7).

---

### Bottom line
Ship Phase 0 + 1 (with §8.1's split) for correctness. Add `onTrimMemory` and the
PersistentMap as gating preconditions. **Stop describing the refactor as a performance
project** — it is a correctness/maintainability project. Open a *separate* throughput track
(§8.6) whose first deliverable is measurement, because the 10 s/page is an OCR-model
problem hiding behind an orchestration problem, and no state machine will move it.
