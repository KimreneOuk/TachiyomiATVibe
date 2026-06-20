# Translation Module — Structure & Test Coverage

> Reference for the `eu.kanade.translation` package (TachiyomiAT exclusive),
> the automatic manga-translation pipeline (Detect → OCR → Translate → Inpaint
> → Render). Captures the file layout after the readability/structure pass,
> including which units are unit-tested and which are Android/device-bound.

---

## Package map (`app/src/main/java/eu/kanade/translation/`)

```
translation/
├─ ChapterTranslator.kt          Orchestrates the 5-stage pipeline for one chapter
├─ ChapterTranslationStore.kt    Per-chapter JSON store of page translation states
├─ TranslationManager.kt         Central coordinator: jobs, auto-prefetch, cancel, lifecycle
├─ TranslationPipeline.kt        Singleton pipeline: engines, decode, persist, OOM recovery
├─ TranslationSession.kt         Active translation session (work kind + store handle)
│
├─ data/
│  ├─ TranslationFont.kt         Render font enum
│  └─ TranslationProvider.kt     File/cache path resolution for translated images
│
├─ detection/
│  ├─ Detection.kt               Bounding-box + label + score model
│  └─ OnnxPageTextDetector.kt    ONNX text-region detector (delegates dedupe to BoxGeometry)
│
├─ inpainting/
│  ├─ AOTInpainting.kt           AOT-based bubble inpainting
│  ├─ BubbleMaskBuilder.kt       ★ PURE mask/morphology helpers (BubbleMaskBuilderTest)
│  ├─ InpaintingMode.kt          QUALITY / FAST enum
│  └─ SmartBubbleTextCleaner.kt  Bubble cleaning core; delegates masks to BubbleMaskBuilder
│
├─ model/
│  ├─ PageTranslation.kt         Page state + TranslationBlock + StageStatus
│  ├─ PageTranslationHelper.kt   ★ PURE overlapping-block merge (mergeOverlap)
│  ├─ PageTranslationState.kt    ★ PURE lifecycle/status predicates + cancelInFlightStages
│  ├─ PageView.kt                Reader-side view model
│  └─ Translation.kt             Per-chapter Translation aggregate
│
├─ ocr/
│  ├─ MangaOcrEngine.kt          Japanese manga OCR (ONNX); see Memory contract #2
│  │                              (decoder position bound pos < 128) + ocr-engine-notes.md
│  ├─ MlKitOcrPreprocessor.kt    ML Kit crop preprocessor (scale/pad/contrast)
│  ├─ MlKitRoiOcrEngine.kt       ML Kit ROI OCR
│  ├─ OcrModelCatalog.kt         ★ PURE model/language catalog (entries, coerce, defaults)
│  ├─ PaddleCtcDecoder.kt        ★ PURE CTC decode (decode, argmaxIndices)
│  ├─ PaddleOcrV6SmallEngine.kt  PaddleOCR v6 small engine (ONNX)
│  ├─ RoiOcrEngine.kt            ROI OCR interface + reclaimPooledMemory contract
│  ├─ TextRecognizer.kt          OCR engine selector facade
│  └─ TextRecognizerLanguage.kt  Source-language enum
│
├─ recognition/
│  ├─ BoxGeometry.kt             ★ PURE bbox IoU/area/geometric-dedupe (shared by detector + OCR)
│  ├─ MlKitFullPageRecognitionEngine.kt
│  ├─ PageRecognitionEngine.kt   Recognition engine interface + reclaimPooledMemory
│  └─ RoiPageRecognitionEngine.kt ROI recognition (delegates dedupe to BoxGeometry;
│                                 reclaims sub-engine native caches on OOM)
│
├─ rendering/
│  ├─ PageTextRenderer.kt        Draws translated text onto cleaned pages
│  └─ RenderColorEstimator.kt    ★ PURE colorPolicy/snapGray + Bitmap-bound estimate()
│
├─ runtime/onnx/
│  ├─ DeviceCapability.kt        ONNX EP capability detection
│  ├─ OnnxModelStore.kt          ONNX model asset loading/caching
│  └─ OnnxRuntimeProvider.kt     OrtSystem singleton
│
├─ scheduling/
│  ├─ TranslationExecutor.kt     Per-page work interface
│  ├─ TranslationLifecyclePolicy.kt ★ PURE scheduling classification (shouldSchedule/classify)
│  ├─ TranslationScheduler.kt    Job lifecycle: dedup, cancel, auto-prefetch windows
│  ├─ TranslationStoreResolver.kt  Resolves live store for a chapter id
│  └─ TranslationStreamRegistry.kt ★ PURE per-page stream factory registry
│
├─ translator/
│  ├─ AiModelFetcher.kt          ★ PURE parseOpenAiModels/parseGeminiModels/normalizeBaseUrl
│  ├─ AiTranslatorKind.kt        AI translator enum (Gemini/DeepSeek/OpenRouter/LM Studio)
│  ├─ DeepSeekTranslator.kt      DeepSeek adapter (delegates parsing to NumberedLineResponseParser)
│  ├─ GeminiTranslator.kt        Gemini adapter
│  ├─ GoogleTranslator.kt        Google Translate adapter
│  ├─ LmStudioTranslator.kt      LM Studio adapter (delegates parsing to NumberedLineResponseParser)
│  ├─ MLKitTranslator.kt         On-device ML Kit translator
│  ├─ NumberedLineResponseParser.kt ★ PURE `[index] text` parser (DeepSeek/LM Studio)
│  ├─ OcrArtifactSanitizer.kt    ★ PURE OCR misread (N°/№/Ｎ０) stripper
│  ├─ OpenRouterTranslator.kt    OpenRouter adapter
│  ├─ StandardTranslatorKind.kt  Standard translator enum (ML Kit/Google)
│  ├─ TextTranslator.kt          Translator interface
│  ├─ TextTranslatorLanguage.kt  Target-language enum
│  ├─ TranslationBlockFilters.kt ★ PURE watermark (RTMTH) block removal
│  └─ TranslationEngineBuilder.kt Resolves active translator from preferences
│
└─ util/
   ├─ ShortHash.kt               ★ PURE FNV-1a digest (API-key change detection)
   ├─ TaskExtensions.kt          gms Task → coroutine bridge
   └─ TranslationMemoryBudget.kt Heap-aware decode/inpaint gating
```

★ = pure JVM-testable logic (no Android/Bitmap/ONNX/ML Kit dependency).

---

## Deduplication done

Before the structure pass, several algorithms were copy-pasted across files;
each now has a single tested source of truth:

| What | Was duplicated in | Now lives in |
|------|-------------------|--------------|
| `[index] text` LLM response parsing | DeepSeekTranslator, LmStudioTranslator | `NumberedLineResponseParser` |
| OCR artifact (N°/№/Ｎ０) stripping | DeepSeekTranslator (inline) | `OcrArtifactSanitizer` |
| bbox IoU / intersection / area + geometric-dedupe | OnnxPageTextDetector, RoiPageRecognitionEngine | `BoxGeometry` (+ `DedupThresholds`) |
| Mask building / dilation / feathering | SmartBubbleTextCleaner (private) | `BubbleMaskBuilder` |
| "any stage failed" + "cancel in-flight stages" | TranslationScheduler (3× inlined) | `PageTranslationState` (`isStageFailed`, `cancelInFlightStages`) |
| FNV-1a API-key digest | TranslationPipeline (private `shortHash`) | `ShortHash` |
| Translator interface + 2 enums + builder | TextTranslator.kt (god file) | `TextTranslator` + `StandardTranslatorKind` + `AiTranslatorKind` + `TranslationEngineBuilder` |

Per-stage threshold constants stay on their owners (`DedupThresholds`) so each
dedupe stage keeps its own tuning while sharing the algorithm.

---

## Memory & cross-page state contracts

> Established after the "translation degrades over time" investigation
> (progressive blur, text leaving its box, pipeline halting). These invariants
> are load-bearing — violating any of them reintroduces that class of bug.

### 1. Pooled native buffers are NOT zeroed on acquire (`DirectBufferPool`)
`MangaOcrEngine` writes only the KV-cache positions it actually visits during a
decode; the decoder attends over the full `[4,1,4,MAX_LEN,64]` cache. The pool
hands out buffers in whatever state `FloatBuffer.clear()` left them (position
reset; backing bytes implementation-defined). **Contract: do NOT zero these
buffers.** Zeroing was tried once and corrupted OCR: zeroed key/value vectors
made the attention softmax spread uniformly across all positions, producing
degenerate repetitive output (e.g. `viletetetetotetoteritetiteterinitijanijijan`).
The consumer must overwrite every position it reads back. Correctness DOES
depend on the model's causal attention. Additionally, calling `clear()` uses
reflection to immediately invoke the cleaner on direct byte buffers, avoiding
native OS memory leaks.

### 2. MangaOcr decoder position bound (`MangaOcrEngine`)
The autoregressive decoder loop runs `decoder_step` ONNX graphlets one token at a
time. The loop MUST be bounded by `pos >= DECODER_POSITION_COUNT` (**128**), the
GPT-2 position-embedding table size (`node_embedding_1` has 128 entries; `pos==128`
overflows it → native Gather throws `idx=128 must be within [-128,127]` → chapter
ERROR → pipeline halt). This is **separate from** `MAX_LEN` (256), which is only
the KV-cache sequence dimension and is larger than the position table — bounding
by `MAX_LEN` crashes on long bubbles (128+ generated tokens). argmax stays
full-vocabulary (the token embedding does not overflow). See
`docs/ocr-engine-notes.md` "MangaOcr ONNX decoder contract" for the full graph
analysis. **Verify any change to this loop on a text-dense page on-device**, not
unit tests alone — graph internals aren't unit-testable.

### 3. Watchdog force-release clears in-flight bookkeeping
`withLeakProofPermit` force-releases the permit when a page is stuck in
uncancellable native code. The watchdog's `onForceRelease` callback MUST clear
any dedup key (`inFlightPageKeys`) the worker would otherwise only clear in its
own (unreachable-while-stuck) `finally`, or the page is silently blacklisted
for the rest of the process. `closeEngines()` also clears `inFlightPageKeys`.

### 4. OOM recovery frees native, not just Java, memory
`recoverHeapAfterOnnxPressure` calls `recognitionEngine.reclaimPooledMemory()`
in addition to `BitmapPool.releaseAll()` + `System.gc()`. The ONNX direct
KV-cache buffers and the inpainter's retained working arrays are off-heap and
survive a Java GC; without reclaiming them the pressure that OOM'd page N
persists into page N+1 and the OOM recurs (the chronic-OOM feedback loop).
Engines expose `reclaimPooledMemory()` on `PageRecognitionEngine` /
`RoiOcrEngine` (no-op default); `MangaOcrEngine` overrides this to clear its
off-heap `kCachePool` and `vCachePool` buffers.

### 5. Neural-inpaint downgrade is visible
`AOTInpainting` logs the `canRunNeuralInpaint()` fallback to flat bubble-fill at
WARN unconditionally (not just under `translation_diagnostics`), because it is a
user-visible quality drop. After contract #4 frees native heap, the next page's
fresh snapshot lets neural inpaint recover on its own (no separate retry path).

### 6. Heap pressure must not persist blurry translations
`TranslationPipeline.decodePageBitmapForTranslation` uses
`TranslationMemoryBudget.chooseDecodeDecision()` before decoding. If the page is
a normal manga page, heap pressure can only produce a retryable low-memory
failure after reclaiming `BitmapPool`, engine native pools, Coil memory cache,
and running GC; it must not silently choose `sampleSize > 1`. Only pages that
exceed the hard source-pixel cap may persist sampled output, and those are
marked `RenderQuality.SIZE_LIMITED`. New rendered images are saved as
`.rendered.png` with `renderQuality`, `renderedWidth`, and `renderedHeight`.
Legacy rendered images with `decodeSampleSize > 1` and unknown quality are
treated as stale and scheduled again.

### 7. BitmapPool aspect-ratio exact keying
`BitmapPool` keys cached Bitmaps by exact dimensions (`"${width}x${height}"`) instead of area (`width * height`). Area-based keying caused aspect-ratio collisions (e.g., 200x1000 and 1000x200), where the pool returned a bitmap of the wrong orientation. This failed validation and was silently dropped to GC while allocating a new Bitmap, fragmenting heap. **Contract: always key pools by exact dimensions.**

### 8. SmartBubbleTextCleaner buffer size limits
To prevent massive text bubbles from permanently hoarding heap memory, `SmartBubbleTextCleaner` enforces `MAX_CACHED_PIXELS = 1_000_000` (4MB). Buffers exceeding this limit are allocated transiently and discarded after the clean operation rather than cached in long-lived instance fields.

### 9. Stuck RUNNING status prevention (Throwable catch)
Uncaught errors (such as `java.lang.OutOfMemoryError`, which is a `java.lang.Error` rather than a `java.lang.Exception`) must never escape the page translation pipeline without updating the store status. Catching `Throwable` in `translateSinglePage` and `translateSinglePageFromStream` and recording a `FAILED` page state (along with the error type/message) to the store prevents pages from getting stuck in a permanent `RUNNING` status. In the reader UI, a `RUNNING` status disables the "translate" icon and shows a cancel button; marking the page `FAILED` immediately restores the "translate" button to allow user manual retry.

### 10. Memory relief after every page translation
Calling `recognitionEngine.reclaimPooledMemory()` in the outer `finally` block of `translateSinglePageInternal` ensures that off-heap direct float buffers (KV-cache pools for MangaOcr) and working arrays (for SmartBubbleTextCleaner) are freed immediately after each page translation completes or fails. This maintains a flat memory footprint and prevents progressive memory pressure accumulation across large chapters (25+ pages).

Reader memory is bounded by `ReaderPageWarmWindow` (current page +/- 2). The
reader keeps translation metadata for the whole chapter, but translated image
streams are attached only inside that warm window and reopened from disk on
demand. This keeps a 200-page chapter proportional to the visible working set,
not to total chapter length.

---

## Translation status semantics & delete teardown

### "Translated" requires real output (`TranslationManager.isChapterTranslated`)
A chapter counts as translated **only** when at least one page produced real
output — a rendered/displayable image (`hasRenderedResult`) OR recognized text
blocks whose OCR + translation stages are READY (`hasRecognizedTranslation`).
Both helpers live in `PageTranslationState.kt` and are the same predicates the
reader uses to treat a page as `Done` / `NeedsRender`, so the on-disk status
stays consistent with the live UI.

Previously the check was `pages.isNotEmpty()`, which meant any single
placeholder page — all-`PENDING`/`CANCELLED`, no blocks, no rendered image —
made the chapter show `TRANSLATED` forever. Those placeholders are routinely
written by the stranded-page sweep (`ReaderViewModel.sweepStrandedPageStatus`,
on every chapter open) and by failed/aborted translations, so a chapter merely
opened once read as fully translated. Requiring real content closes that
false-positive. (Downloading a chapter never creates a translation file; it
only makes the chapter list query status, so the icon "appeared on download.")

### Placeholder pages are not persisted (`ChapterTranslationStore.shouldPersistUpdate`)
`updatePage` persists only durable progress: a rendered result, recognized
blocks, a cleaned image, a stage failure (for retry-exhaustion bookkeeping),
or a rendered→cleared transition. Transient placeholders (`RUNNING`/`PENDING`/
`CANCELLED` with no content and no failure) stay in the in-memory `StateFlow`
only — the reader still observes them live — and never reach disk. This stops
the stranded-page sweep's `CANCELLED`+`errorMessage` heals from writing entries
that would later read as `TRANSLATED`. The explicit cancel snapshot path
(`clearTransientQueuePages`) writes via `persistLocked()` directly and bypasses
this gate, so legitimate user cancels are still recorded when needed.

### `deleteTranslation` tears down work before deleting files
`TranslationManager.deleteTranslation` cancels **all** in-flight single-page
and auto-prefetch jobs for the chapter and joins them (bounded by
`JOIN_TIMEOUT_MS`) **before** deleting the on-disk translation file and
companion images. Ordering is load-bearing: an auto job can be mid-native-call
inside `OrtSession.run()` when the user taps delete; tearing work down first
lets it unwind at its next suspension point so a subsequent translate's engine
rebuild/close cannot free a native session out from under a running inference
(a native use-after-free / SIGSEGV that exits the app). The teardown also bumps
the chapter's auto generation (so any in-flight window stops dispatching new
pages) and evicts the shared `ChapterTranslationStore` so the next translate
resolves a fresh lazy store instead of one bound to the deleted file.

`ReaderViewModel.deleteCurrentChapterTranslation` mirrors the chapter-change
cleanup: clears per-page translated fields, re-observes the live store to bind
to the fresh one, and resets the merged translation state.

### Native close()/run() serialization (`RoiPageRecognitionEngine`)
`analyze()` and `inpaint()` hold a `nativeGuard` `Mutex` across each native
`OrtSession.run()`, and `close()` `tryLock`s it before freeing native sessions.
Every `close()` call site is already guarded by the translator permit (so no
translate, hence no native run, is in flight at close time), making the lock
free in practice; the `Mutex` is defense-in-depth that converts any future
permit-guard bypass into serialized execution instead of a SIGSEGV. `close()`
uses non-suspending `tryLock` (never blocks the main thread it is called from);
if the lock is ever held, it logs, skips the native free, and leaves the
`closed` flag + rebuild gate as the backstop.

### Note: `autoTranslateAfterDownload` preference is currently unwired
The `auto_translate_after_download` preference (`TranslationPreferences.kt`)
and its settings toggle (`SettingsTranslationScreen`) have **no consumer** —
downloading a chapter does not trigger translation. It is dead surface; either
wire it or remove it in a follow-up. It is unrelated to the false-translated
bug above.

---

## Test coverage (`app/src/test/java/eu/kanade/translation/`)

All tests are **plain JVM unit tests** — JUnit 5 + Kotest assertions, no
Robolectric, no MockWebServer. They target the ★ pure logic above; Android /
Bitmap / ONNX / ML Kit code is deliberately excluded (it needs a device).

> The `domain`-module pool test (`domain/src/test/.../pools/DirectBufferPoolTest`)
> covers the off-heap KV-cache buffer pool: recycle-on-release, drop-when-full,
> capacity preservation, `clear()` empties the pool. It deliberately does NOT
> assert zeroed buffers — see contract #1 (zeroing corrupts OCR).

---

## Staged batch pre-translation

The manga-screen "translate chapter" action (`MangaScreenModel.runChapterTranslationActions`
→ `TranslationManager.translateChapter` → `ChapterTranslator.translateChapterInternal`)
now runs a **staged batch** instead of the old page-1-first sequential loop.
The reader's per-page / auto-prefetch path is unchanged.

### Resume-aware ordering (`ResumeOrdering`)
Pages are ordered **forward-first from the resume page, then backfill**: a user
mid-chapter (resume page 50 of 200) gets pages 50–199 translated first, then
1–49. This makes pre-translating a chapter you've started actually useful — the
next page you'll read is ready first. `Chapter.lastPageRead` (already carried on
the `Translation`) seeds the split. The helper is pure + unit-tested
(`ResumeOrderingTest`).

### Three stages (`TranslationPipeline.translateBatch`)
1. **DETECT + OCR batch** — `analyzePage` (split from `processSinglePage`) for
   each page, serialized under `translatorPermit`. One page's bitmap/tensor set
   is alive at a time. Persists `ocrStatus=READY` + blocks per page (resumable —
   pages already analyzed are skipped).
2. **INPAINT ‖ TRANSLATE** — for each analyzed page, inpaint (re-decoded bitmap,
   serialized under the permit) runs concurrently with `textTranslator.translatePage`
   (HTTP-only, no permit). HTTP overlaps ONNX — free parallelism, no extra peak
   memory. Both are awaited before render.
3. **RENDER** — for each page with translated text + a cleaned image, render
   translated text onto the cleaned bitmap (Canvas only, no permit) and persist.

### Memory model
**Re-decode per stage**: the page bitmap is recycled after analyze and re-decoded
for inpaint. `decodePageBitmapForTranslation` buffers source bytes into a private
`ByteArray`, so there is no shared-stream race (and the reader is not open on
this path, so no concurrent display decode). One bitmap alive at a time honors
the 6GB / 20-30% heap constraint. Translation (HTTP) and render (Canvas) need no
ONNX permit and overlap the inpaint serial loop. Because the reader is closed,
memory is more relaxed than the in-reader path — but we keep the one-bitmap
discipline rather than hold N bitmaps, to avoid OOM on the target devices.

### Stage split (`analyzePage` / `inpaintPage`)
The fused `processSinglePage` (which calls `recognize` = analyze+inpaint) is
preserved for the reader path. The batch path uses two new methods extracted from
it: `analyzePage` (detect+OCR, leaves `cleanedBitmap` null) and `inpaintPage`
(needs the bitmap + the analyzed `PageTranslation`, persists the cleaned image).
The `PageRecognitionEngine` interface already exposes `analyze`/`inpaint`
separately; `inpaint` reads `allTextDetections` + `blocks` from the passed-in
translation.

### Per-chapter progress indicator (`TranslationProgress`)
The chapter-list translate indicator turns **determinate** while a batch runs,
showing `done/total` (e.g. `12/40`). `TranslationProgress.compute` derives
(done, total) from the active `ChapterTranslationStore` page map: total = entries
the batch has registered; done = pages at a terminal state (rendered, textless,
or retry-exhausted). `TranslationManager.observeTranslationProgress(chapterId)`
exposes it as a flow; `MangaScreenModel` starts a per-chapter collector when the
chapter enters `QUEUE`/`TRANSLATING` and stops it on a terminal state. Pure +
unit-tested (`TranslationProgressTest`).

---

| Test | Guards |
|------|--------|
| `translator/NumberedLineResponseParserTest` | `[index] text` parse, sparse/gaps, positional fallback, expectedCount cap, index>count contract, index collisions |
| `translator/OcrArtifactSanitizerTest` | N°/Nº/№/Ｎ０/N⁰ strip (before-punct / inline / leading / end-of-string), space collapse, glued-word limitation |
| `translator/TranslationBlockFiltersTest` | RTMTH watermark removal (case-insensitive, multi-page, embedded) |
| `translator/AiModelFetcherParseTest` | OpenAI `data[].id` + Gemini model filtering/prefix-strip, kotlinx Json, JSON-null id guard |
| `ocr/PaddleCtcDecoderTest` | CTC blank-collapse, space class, argmax |
| `ocr/MangaOcrDecoderGuardTest` | (REMOVED — the guard helpers it tested were reverted; see Memory contract #2. Do not reintroduce without an on-device regression test.) |
| `ocr/OcrModelCatalogTest` | entries/coerce/defaultFor/isCompatible/labelsFor |
| `recognition/BoxGeometryTest` | IoU/area/intersection, degenerate boxes, dedupe thresholds (iou/containment/center+size paths) |
| `model/PageTranslationStateTest` | lifecycle, retry exhaustion, cancelled-page rescheduling, render-quality trust, forced retry reset |
| `model/ChapterTranslatedPredicateTest` | `isChapterTranslated` content predicate: placeholder/pending/failed/running → not translated; rendered or recognized → translated; mixed/empty lists |
| `model/TranslationProgressTest` | batch (done,total): rendered/textless/retry-exhausted count as done; pending/running don't; empty → (0,0) |
| `util/ResumeOrderingTest` | forward-first-then-backfill ordering; resume mid/start/end/last; empty; no aliasing |
| `ChapterTranslationStorePersistTest` | `shouldPersistUpdate`: placeholders (CANCELLED+error, pending, running) not persisted; rendered/cleaned/blocks/failed/transition ARE persisted |
| `model/PageTranslationHelperTest` | overlapping-block merge, orientation guard, transitive merge |
| `rendering/RenderColorEstimatorTest` | dark/light colorPolicy, gray-snap (saturated preserved) |
| `inpainting/SmartBubbleTextCleanerTest` | local-background fill (gray-rectangle regression guard) |
| `inpainting/BubbleMaskBuilderTest` | andMasks/maskCoverage/insideRoundedRect/bubbleInteriorMask/roundedAllowedMask + dilateMask quirk pin + removeEdgeTouchingComponents 2px margin + featherAlpha |
| `scheduling/TranslationStreamRegistryTest` | per-page/chapter/all/window stream registry eviction semantics |
| `scheduling/TranslationLifecyclePolicyTest` | shouldSchedule / classify / retry-exhaustion |
| `util/ShortHashTest` | FNV-1a digest: empty input, equality, determinism, hex output |
| `util/TranslationMemoryBudgetTest` | full-quality vs heap-constrained vs source-size-limited decode decisions |
| `reader/ReaderPageWarmWindowTest` | current +/-2 warm-window boundaries for long chapters |

Run: `.\gradlew.bat :app:testStandardDebugUnitTest`

---

## Remaining debt — prioritized map for future work

Each item is grounded in a full read of the current code. Difficulty and payoff
noted so the next pass can pick the highest-value, lowest-risk item first.

### 1. `TranslationPipeline.kt` — partial split (MEDIUM effort, HIGH payoff)

Still ~1.65k lines. The single-page path is cohesive, but four responsibilities
are entangled with it. A full seam map exists (generated by exhaustive read);
the key constraint is that `recognitionEngine`, `textTranslator`, and
`autoFallbackToFast` are **three-way shared mutable state**, so most extractions
must funnel through a single owner rather than becoming stateless helpers.

**Cleanest remaining extraction — `PageBitmapIO` (do this first):**
All plain (non-suspend) functions, touching only injected singletons
(`streamRegistry`, `context`) plus a nested `DecodedPage`. No host mutable state.
Move these out together:
- `peekReaderPageStream`, `createFailedPagePlaceholder`, `copyForResume`
- `loadPersistedCleanedBitmap`, `persistCleanedBitmap`, `persistRenderedBitmap`
- `decodePageBitmap`, `decodePageBitmapAtSize`, `getChapterPages`
- nested `DecodedPage` (must move with `decodePageBitmap`; promote to top-level)
- Callers: `processSinglePage`, `resumeInpaintAndRender`, `retryInpaintDownscaled`
  take `DecodedPage`, so they need the promoted type.

**Harder extractions — defer until PageBitmapIO lands:**
- `EngineConfig`: `EngineSignature`, `computeTranslatorSignature`,
  `inpaintingModeFromPref`, `createRecognitionEngine`, `closeEngines`, and the
  six engine fields. Must be a **stateful collaborator** (the orchestrator
  live-mutates `textTranslator`/`recognitionEngine` on config + lang change).
- `OomRecovery`: `consecutiveOomCount`, `autoFallbackToFast`, `enginesClosed` +
  `downgradeOnnx*`, `handleCriticalTranslationOom`, `persistPageWithOomRecovery`.
  Most entangled group — `autoFallbackToFast` is R/W from three places and
  `downgradeOnnxAfterOom` rewrites `recognitionEngine`. Needs a shared-state
  design, not a pure helper.
- Two further implicit responsibilities surfaced: **permit/watchdog infra**
  (`translatorPermit`, `inFlightPageKeys`, `withLeakProofPermit`, ~lines 105–196)
  and **active-store bookkeeping** (`currentChapterTranslation`,
  `activeStoreResolver`, `register/unregisterActiveStore`).

**Already done this pass:** extracted `ShortHash`; removed dead
`currentChapterPath` field (was write-only, never read) and the unused `store`
parameter of `registerActiveStore`.

### 2. Translator adapter consolidation (MEDIUM effort, MEDIUM payoff)

OpenRouter / DeepSeek / LM Studio share the same `choices[0].message.content`
→ parse flow with near-identical OkHttp setup, 60s timeouts, `close()` body
(evict pool + shutdown dispatcher), and `response.body` shape-checking. Gemini
is the odd one (Google SDK, not raw HTTP). A future `OpenAiChatTranslator` base
could fold the three OpenAI-shape adapters together; DeepSeek/LM Studio already
share `NumberedLineResponseParser`, OpenRouter + Gemini share the JSON-object
shape. Watch: the `sanitizeOcrArtifacts` step is currently DeepSeek-only — if the
others start emitting の-misreads, route them through `OcrArtifactSanitizer` too.

### 3. Pre-existing lint debt (LOW effort, LOW payoff, mechanical)

`spotlessKotlinApply` reports ~12 `max-line-length` / property-naming lints in
files not touched by the structure pass: long AI prompt strings in
`GeminiTranslator`/`OpenRouterTranslator`/`GoogleTranslator`, comment lines in
`MangaOcrEngine`/`TranslationManager`/`TranslationPipeline`, and
`AOTInpainting` property naming. None break compilation; all are one-line wraps.
A dedicated `spotlessApply` + wrap pass would clear them, but wrapping the
multi-line prompt strings risks subtle whitespace changes in the LLM prompts —
verify translation output is byte-identical before/after if you touch those.

### 4. Untested Android-bound logic (by design, LOW priority)

`RenderColorEstimator.estimate`, `MlKitOcrPreprocessor.preprocessRoi`,
`AOTInpainting`, the ONNX engines, and `PageTextRenderer` touch
`android.graphics.Bitmap`/ONNX/ML Kit and are correctly excluded from unit
tests. The project deliberately avoids Robolectric. Prefer the pattern already
established (`colorPolicy`, `snapGray`, `BoxGeometry`, `BubbleMaskBuilder`):
keep extracting pure decision functions out of Bitmap-coupled classes so the
*logic* is testable even when the *I/O* isn't.

### Out of scope — intentionally left cohesive

- **`TranslationScheduler.kt`** (~640 lines): one coherent job-lifecycle
  responsibility. Its internal duplication was DRY'd this pass
  (`cancelInFlightStages`, `isStageFailed`); forcing a file split would create
  awkward coupling between pieces that all share the same ConcurrentHashMaps.
- **`ChapterTranslator.kt`** (~510 lines) / **`TranslationManager.kt`** (~490):
  cohesive orchestrators. No clean extraction seam worth the churn.
