# Translation Pipeline — Architecture Audit & Refactor Plan

> **Status:** Design document (architecture proposal). No code changes yet.
> **Scope:** The full translation feature — detection, OCR, translation, inpainting,
> rendering, persistence, and the reader UI binding in both paged and webtoon modes.
> **Audience:** Engineers deciding whether/how to refactor the translation pipeline.

## Purpose

This document captures the findings of a senior-architect audit of the current
translation pipeline and proposes a target architecture. The current system has
accumulated enough special-case patches (2-second dedup tokens, `renderJustFinished`
re-wires, `READY`-for-textless hacks) that each new bug fix adds complexity rather
than removing it. The symptoms users see — re-translation loops, pages not
appearing, webtoon behaving differently from paged mode, gray-box inpainting — are
all expressions of three root causes identified below.

This is **not** a symptom-fix list. It identifies the structural problems and the
minimum set of architectural changes that make those symptoms unrepresentable.

---

## 1. What the symptoms actually are

| Symptom (user-visible) | Where it shows up |
|---|---|
| Auto-translate re-processes the same image repeatedly | Both readers, worse in webtoon |
| Pages 5+ fail to display (or display then revert) | Paged reader |
| Webtoon and paged mode behave differently for the same input | Any chapter |
| Inpainting fills the whole bounding box with gray | Any mode |
| Textless splash pages loop or appear half-done | Any chapter |

Each of these has been "fixed" at least once in the recent history of this codebase
(see the long comment blocks in `ChapterTranslator.kt`, `ReaderViewModel.kt`, and
the holders). The fixes treat symptoms; the underlying structure keeps generating
new instances.

---

## 2. Root causes

### Root cause #1 — There is no single definition of "is this page done?"

`PageTranslation` carries **four independent status strings** (`ocrStatus`,
`translationStatus`, `inpaintStatus`, `renderStatus`), each a free-form
`PENDING / RUNNING / READY / FAILED` value. "Is this page finished?" is then
answered differently in three places, each reading a different subset:

| Site | Definition of "done / skip" |
|---|---|
| `TranslationManager.translatePagesSequential` | `hasResult \|\| (ocrStatus == READY && translationStatus == READY)` |
| `ReaderViewModel.handleAutoTranslation` gate | `isRecognizedAndTranslated` **and** `isTextlessDone` |
| Holders' dedup | `lastShownImageName == renderedImageName` |

These disagree. When they disagree, the symptoms appear:

- A page where inpaint **failed** but OCR + translate **succeeded** → the Manager
  says "done, skip" → the holder shows FAILED → the VM's gate says "not done" →
  **re-translate**. This is the webtoon loop.
- A textless page leaves `translationStatus = PENDING` and `renderStatus = PENDING`
  **forever**, while `ocrStatus = READY`. That only works because of a bolted-on
  `isTextlessDone` special case. Remove the special case and every textless page
  re-processes infinitely.

A flat four-enum status also allows **impossible states**: the combination
`ocrStatus = READY, translationStatus = PENDING, inpaintStatus = READY` is
representable but meaningless. Correctness cannot be reasoned about when illegal
states exist.

### Root cause #2 — Mutable shared state crossing thread boundaries, with no contract

`ReaderPage` is a shared-mutable bus. The fields `translation`, `translatedStream`,
and `showTranslatedImage` are written by the ViewModel on an IO coroutine
(`observeLiveTranslationStore`) and read/written by holders on the main thread.
`PageTranslation` has `var` on **every** field, including the `@Transient`
`cleanedBitmap` and `allTextDetections`. The store collector reassigns
`readerPage.translation = updated` (a fresh snapshot) while a holder may be
mid-`refreshTranslation()` reading the old one. `sweepStrandedPageStatus` even
mutates a snapshot's `var` fields in place.

No visible corruption has occurred yet — only because of thread timing. There is
**no memory-consistency model anywhere**. The `renderJustFinished` re-wire, the
`needsStreamUpdate` clause, and the ordering of `translatedStream =` before
`translation =` are all hand-tuned compensations for the absence of a
synchronization contract.

### Root cause #3 — Hand-rolled event fan-out instead of per-page reactive binding

The translation result does **not** flow through a Flow to the thing that displays
it. It travels:

```
Store.state (StateFlow<Map<pageKey, PageTranslation>>)
  → VM collector (partitions into changedPages / statusPages)
  → eventChannel.trySend(RefreshTranslationPages | TranslationStatusChanged)
  → Activity collects event
  → viewer.refreshTranslationPages(set)
  → holder.refreshTranslation()    (dedups via lastShownImageName)
```

Four hops, two event types, a hand-built partition, and a string-comparison dedup
at the end. This exists only because holders do not subscribe to their own page's
output. Contrast with the **original-image** path, which is clean: the holder
collects `page.statusFlow`, and on `READY` calls `setImage()`. That is the entire
pattern. Translation should look identical.

There are **four independent triggers** into `refreshTranslation()` on webtoon:
the two VM events, the holder's own `statusFlow → READY`, and
`WebtoonTranslationPrefs.refreshVisibleHolders`. All converge on the same dedup.
This is correct today, by accident.

---

## 3. What the current code does well (do not regress)

A rewrite that ignores these will regress them. These subsystems are sound and
should be carried forward largely unchanged:

- **ONNX execution-provider strategy** — `forceCpu` per model, NNAPI enabled only on
  AOT inpainting, CPU forced for the autoregressive manga-ocr decoder and the
  detector. The fragmentation analysis (NNAPI producing ~145 partitions on the
  decoder) is real and the per-model pinning is correctly reasoned.
  See `OnnxRuntimeProvider`, `DeviceCapability`.
- **`ChapterTranslationStore` persistence** — atomic write-temp-then-rename,
  lazy file materialization (no empty file on chapter open), SAF-write failure
  tolerance with retained in-memory state. Production-grade.
- **`TranslationMemoryBudget`** — single-snapshot heap decisions and diagnostics
  gating behind a preference.
- **`RenderColorEstimator`** consolidation and the **post-inpaint recompute**.
  This is the correct fix for the gray-box inpaint symptom; it should become the
  *only* place colors are estimated.
- **`withLeakProofPermit`** — the watchdog that force-releases the singleton
  translation permit when a coroutine is stuck in uncancellable native JNI.
  Thoughtful and necessary.
- **`WebtoonAdapter.areContentsTheSame == true`** and the deliberate removal of
  adapter-driven refresh. `notifyDataSetChanged` / `notifyItemChanged`-without-payload
  destroy dedup state and reintroduce the blink. Correct call.
- **Companion-image caching** (`.cleaned.png` / `.rendered.webp`) for resume across
  sessions.

The algorithms in `SmartBubbleTextCleaner` (ring-median background, 2-means
clustering, feathering) and `AOTInpainting`'s box clustering are fine. The problem
is the **plumbing around them**, not the math.

---

## 4. Concrete bugs found during the audit

These are real, present-tense defects — fixable independently of the refactor.

| # | Defect | Location |
|---|---|---|
| B1 | `MangaOcrEngine.close()` nulls its ONNX sessions without calling `OrtSession.close()` — native-memory leak on every language change / engine rebuild. (Contrast: `OnnxPageTextDetector.close()` and `AOTInpainting.close()` both close correctly.) | `ocr/MangaOcrEngine.kt` |
| B2 | `GoogleTranslator.close()` is a no-op; its OkHttp client is never shut down — connection-pool + dispatcher leak per language change. (DeepSeek and OpenRouter were already fixed.) | `translator/GoogleTranslator.kt` |
| B3 | Translators mutate the block list **after** inpaint boxes were computed from the original list (the RTMTH-watermark filter). Inpainted regions and rendered blocks can diverge. | `translator/DeepSeekTranslator.kt`, `translator/OpenRouterTranslator.kt`, `translator/GeminiTranslator.kt` |
| B4 | The RTMTH watermark filter is copy-pasted in three translators. Any change must be made in triplicate. | same three files |
| B5 | `inpaintStatus` has two writers: the recognition engine sets it in `inpaint()`, then `ChapterTranslator` overwrites it based on **file-write** success. | `ChapterTranslator.kt` persist step |
| B6 | Render colors are estimated **twice**: once at recognition against the original bitmap, once at render against the cleaned bitmap. The first pass is dead compute that produces wrong colors if the recompute is ever missed. | `RoiPageRecognitionEngine.kt`, `MlKitFullPageRecognitionEngine.kt` (first pass); `ChapterTranslator.kt` (recompute) |
| B7 | `PageRecognitionEngine.recognize()` (an interface **default implementation**) writes the `@Transient` `cleanedBitmap` field on the returned model as a side channel. An interface method should not mutate a model field. | `recognition/PageRecognitionEngine.kt` |
| B8 | The batch and single-page render paths are ~200 lines of near-identical code, each with a three-way retry/failed/persist block. | `ChapterTranslator.kt` lines ~775–907 (batch) vs ~1234–1410 (single-page) |
| B9 | `errorMessage` is a single shared slot overwritten by every stage; after a successful retry it carries `"Inpainted on retry (downscaled decode)"` despite the page being READY. Anything treating `errorMessage != null` as "problem" is wrong. | `PageTranslation.kt`, multiple writers |
| B10 | `Detection` is a `data class` holding an `IntArray`, so its `equals`/`hashCode` are identity-based. Works today because the dedup passes use sets expecting identity, but it is a footgun. | `detection/Detection.kt` |
| B11 | `PageTranslationHelper.mergeOverlap` appears to have no callers in the engine pipeline — dead code, or viewer-only. | `model/PageTranslationHelper.kt` |
| B12 | `handleAutoTranslation`'s 2-second dedup is `@Volatile`-but-not-atomic; the landing-page IO path and the `onPageSelected` main-thread path can both pass the guard under contention. | `ReaderViewModel.kt` |
| B13 | `PagerPageHolder` has no `bindGeneration` guard (unlike `WebtoonPageHolder`). It relies on `loadJob.cancel()` + `holderScope.isActive`, the weaker pattern. | `viewer/pager/PagerPageHolder.kt` |
| B14 | `closeEngines()` deliberately runs **without** the permit, so it can race an in-flight `analyze()`/`inpaint()`. The polled `closed` flag only prevents the catchable Kotlin NPE, not the underlying native SIGSEGV if a session is freed mid-`run`. | `ChapterTranslator.kt`, `recognition/RoiPageRecognitionEngine.kt` |

---

## 5. Target architecture

### 5.1 Replace four flat statuses with one explicit state machine

```kotlin
sealed interface PageState {
    object Pending : PageState

    data class Recognizing(val retry: Int) : PageState
    data class Translating(val ocr: OcrResult, val retry: Int) : PageState
    data class Inpainting(val ocr: OcrResult, val translation: TranslatedBlocks) : PageState
    data class Rendering(val cleaned: Bitmap, val translation: TranslatedBlocks) : PageState

    // ---- terminal states ----
    object Textless : PageState                                    // no boxes detected; nothing to do
    data class Done(val renderedImage: ImageRef, val meta: ...) : PageState
    data class Failed(val atStage: Stage, val reason: String, val retry: Int) : PageState
}
```

"Is this page done?" becomes **one check**: `state is Done || state is Textless`.
Every subsystem asks the same question and gets the same answer. The
re-translation loop becomes unrepresentable — the scheduler only enters pages
whose state is non-terminal. No dedup token, no `isTextlessDone`, no
`recognizedTranslated` heuristic.

Illegal states become unrepresentable: you cannot have "OCR done but translation
pending forever", because `Recognizing → Translating(ocr)` is the only transition,
and a textless page goes straight to `Textless`.

### 5.2 Make the model immutable; move transient pipeline state out

`PageTranslation` becomes a `val`-only snapshot. The in-flight artifacts that must
not be serialized or shared across threads — `cleanedBitmap`, `allTextDetections`,
decoded bitmaps — move into a `PageProcessing` context that lives **only for the
duration of one pipeline run**, owned by the coroutine running that page. Never on
the model, never visible to the store or holders.

The store holds immutable snapshots (`Map<pageKey, PageTranslation>` or
`Map<pageKey, PageState>`), replaced via `copy()` — never mutated in place.
`sweepStrandedPageStatus`'s in-place mutation becomes impossible to express.

### 5.3 One pipeline function, three schedulers

Today there are two near-identical ~200-line render blocks (batch vs single-page),
plus `retryInpaintDownscaled`, plus inline "which stages to run" logic at each
call site. Collapse to one:

```kotlin
suspend fun advance(page: PageJob): PageState
// runs whatever stages the current state demands; returns the next terminal/failed state
```

Then:

- **Batch** = `for (page in chapter.pages) if (page.state non-terminal) advance(page)` — sequential.
- **Single-page button** = `advance(page)` once.
- **Auto-prefetch** = `advance()` on the next *N* pages with headroom.

Three schedulers, one pipeline. "Which stages to run" is derived from `state`,
not re-encoded at each call site. The duplicated render/retry/persist code is gone.

### 5.4 Pure stage functions with clean ownership

Each stage becomes a pure function: input → output, no shared-model mutation.

| Stage | Input | Output | Owns color estimate? |
|---|---|---|---|
| Detect | `Bitmap` | `List<Detection>` | — |
| OCR | `Bitmap, List<Detection>` | `OcrResult(blocks, geometry)` | — |
| Translate | `OcrResult` | `TranslatedBlocks` | — |
| Inpaint | `Bitmap, OcrResult` | `Bitmap` (cleaned) | — |
| Render | `cleaned Bitmap, TranslatedBlocks` | `rendered Bitmap` | **yes — here** |

This kills three real bugs at once:

- **B6 (color estimated twice)** — the first pass is dead compute. Move color
  estimation to Render, where the cleaned bitmap already exists. One pass, on the
  correct surface.
- **B3 (translators mutate the block list post-inpaint)** — with immutable
  `TranslatedBlocks`, the translator returns a new list; Render inpaints and
  renders from the same list. Inpainted regions and rendered blocks cannot diverge.
- **B5 (`inpaintStatus` has two writers)** — with a pure `Inpaint → Bitmap`,
  status is set once, by the stage that owns it.

### 5.5 Per-page Flow binding to the UI (delete the event fan-out)

Each holder subscribes to **its own page's** derived view:

```kotlin
fun observe(pageKey: String): Flow<PageView>
// distinctUntilChanged on the rendered-image handle + overlay status
```

`PageView` is an immutable UI model:

```kotlin
data class PageView(
    val imageToDisplay: ImageRef?,
    val showOriginal: Boolean,
    val overlay: OverlayState,   // running stage, error message, translate/cancel affordance
)
```

When the store updates, the holder's flow emits and the holder reconciles by
comparing the `imageToDisplay` handle. **This replaces**: the `changedPages` /
`statusPages` partition, the two event types, the Channel, the Activity dispatch,
`refreshTranslationPages` / `refreshTranslationStatus`, and the
`lastShownImageName` hand-dedup. `distinctUntilChanged` *is* the dedup.

Both viewers now behave identically because they run the same collector — the
webtoon-vs-paged behavioral divergence disappears. The pager holder should also
adopt the webtoon holder's `bindGeneration` guard (fixes B13).

### 5.6 Consolidate dedup and resource lifecycle

- **Six overlapping block-dedup passes** (`dedupe_textDetections`,
  `suppressCrossLabelDuplicates`, `removePostOcrDuplicateBlocks`,
  `OnnxPageTextDetector.deduplicateLabels`, `smartMergeBlocks`, and the apparently
  dead `PageTranslationHelper.mergeOverlap`) → one dedup stage with one threshold
  set, run once between Detect and OCR. (Also resolves B11.)
- **RTMTH watermark filter copy-pasted in 3 translators** → one filter at the
  Translate → Render boundary. (Resolves B4.)
- **`MangaOcrEngine.close()` session leak** → unconditional fix. (Resolves B1.)
- **`GoogleTranslator` OkHttp leak** → unconditional fix. (Resolves B2.)
- A single `TranslationEngineRegistry` owns all sessions/translators and guards
  them with the permit **properly**. Replace the polled-`closed`-flag pattern with
  cancel-and-await so `closeEngines()` cannot free a session mid-`run`. (Resolves B14.)

---

## 6. Symptom → root-cause → fix map

| Symptom | Root cause | Fix |
|---|---|---|
| Auto-translate re-processes the same image | Three disagreeing "done?" definitions | Single terminal-state check; non-terminal is the only scheduler entry condition |
| Pages 5+ don't display / display then revert | Event fan-out + mutable `translatedStream` race + the mis-named `needsStreamUpdate` clause | Per-page Flow; `distinctUntilChanged` on the image handle |
| Webtoon ≠ paged behavior | Two different trigger paths, two different dedups | Both viewers run the same `observe(pageKey)` collector |
| Gray-box inpaint | Colors estimated on the original bitmap, not the cleaned one | Color estimation lives in Render only |
| Textless pages loop | `translationStatus` left `PENDING` forever | `Textless` terminal state; no special case |

---

## 7. Migration phasing

This is **not** a big-bang rewrite. The riskiest part (state machine + immutability)
is also the highest-value, so it goes first. The app stays working at the end of
every phase.

### Phase 0 — Free wins (no architecture change)

Fix B1 (`MangaOcrEngine.close()`), B2 (`GoogleTranslator` OkHttp leak), and B4
(RTMTH filter de-duplication). Pure bug fixes, shippable in isolation, no risk to
the working feature.

### Phase 1 — The state machine (keystone)

Replace the four `*Status` strings with the sealed `PageState`. This is the change
that kills the re-translation loop at its root. Keep the rest of the plumbing; the
store simply holds `PageState` instead of four strings. Once "done?" has one
definition, most of the symptom-patches (`isTextlessDone`, the 2-second dedup
token, `recognizedTranslated`) become deletable.

### Phase 2 — One pipeline

Collapse the duplicated batch / single-page render code into `advance()`. Move
color estimation into Render (single site). Make stages pure. Move `cleanedBitmap`
and `allTextDetections` off the model into a `PageProcessing` context owned by the
running coroutine. Resolves B3, B5, B6, B7, B8, B9.

### Phase 3 — Per-page Flow binding

Replace the event fan-out with `observe(pageKey): Flow<PageView>`. This deletes
the most code (the partition logic, the two event types, the Activity dispatch,
the holder hand-dedup) and unifies the two viewers. Do it last because it depends
on the immutable model from Phase 2. Also port `bindGeneration` to the pager
holder (B13) here.

### Phase 4 — Cleanup

Consolidate the six dedup passes into one. Introduce `TranslationEngineRegistry`
and replace the polled-`closed`-flag pattern with cancel-and-await (B14). Decide
the fate of `PageTranslationHelper.mergeOverlap` (B11). Optionally fix
`Detection`'s `IntArray`-in-data-class footgun (B10).

---

## 8. Recommendation

The instinct to refactor is correct. The system has crossed the threshold where
another special-case patch makes the next bug harder to find.

**Phase 1 (the state machine) alone will kill the re-translation loop at its root**
and should be done before anything else — it is also the change that makes every
subsequent bug impossible to reintroduce. Phases 2 and 3 then retire the
duplicated pipeline code and the event fan-out that have been generating the
display bugs.

The engine internals — ONNX EP strategy, inpainting algorithms, memory budget,
atomic store — are sound and should be carried forward largely unchanged. The
disease is in the **orchestration and state representation above them**, not in the
models themselves.

---

## 9. Appendix — files in scope

### Orchestration & state
- `app/src/main/java/eu/kanade/translation/ChapterTranslator.kt`
- `app/src/main/java/eu/kanade/translation/TranslationManager.kt`
- `app/src/main/java/eu/kanade/translation/ChapterTranslationStore.kt`

### Data model
- `app/src/main/java/eu/kanade/translation/model/PageTranslation.kt`
- `app/src/main/java/eu/kanade/translation/model/PageTranslationHelper.kt`
- `app/src/main/java/eu/kanade/translation/model/Translation.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/reader/model/ReaderPage.kt`

### Recognition / detection / OCR
- `app/src/main/java/eu/kanade/translation/recognition/PageRecognitionEngine.kt`
- `app/src/main/java/eu/kanade/translation/recognition/RoiPageRecognitionEngine.kt`
- `app/src/main/java/eu/kanade/translation/recognition/MlKitFullPageRecognitionEngine.kt`
- `app/src/main/java/eu/kanade/translation/detection/OnnxPageTextDetector.kt`
- `app/src/main/java/eu/kanade/translation/detection/Detection.kt`
- `app/src/main/java/eu/kanade/translation/ocr/MangaOcrEngine.kt`
- `app/src/main/java/eu/kanade/translation/ocr/MlKitRoiOcrEngine.kt`
- `app/src/main/java/eu/kanade/translation/ocr/RoiOcrEngine.kt`
- `app/src/main/java/eu/kanade/translation/ocr/TextRecognizer.kt`

### Translation (text)
- `app/src/main/java/eu/kanade/translation/translator/TextTranslator.kt`
- `app/src/main/java/eu/kanade/translation/translator/MLKitTranslator.kt`
- `app/src/main/java/eu/kanade/translation/translator/GoogleTranslator.kt`
- `app/src/main/java/eu/kanade/translation/translator/DeepSeekTranslator.kt`
- `app/src/main/java/eu/kanade/translation/translator/OpenRouterTranslator.kt`
- `app/src/main/java/eu/kanade/translation/translator/GeminiTranslator.kt`

### Inpainting & rendering
- `app/src/main/java/eu/kanade/translation/inpainting/AOTInpainting.kt`
- `app/src/main/java/eu/kanade/translation/inpainting/SmartBubbleTextCleaner.kt`
- `app/src/main/java/eu/kanade/translation/inpainting/InpaintingMode.kt`
- `app/src/main/java/eu/kanade/translation/rendering/PageTextRenderer.kt`
- `app/src/main/java/eu/kanade/translation/rendering/RenderColorEstimator.kt`

### Runtime
- `app/src/main/java/eu/kanade/translation/runtime/onnx/OnnxModelStore.kt`
- `app/src/main/java/eu/kanade/translation/runtime/onnx/OnnxRuntimeProvider.kt`
- `app/src/main/java/eu/kanade/translation/runtime/onnx/DeviceCapability.kt`
- `app/src/main/java/eu/kanade/translation/util/TranslationMemoryBudget.kt`

### Reader UI binding (both viewers)
- `app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderActivity.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/Viewer.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/ReaderPageImageView.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/PagerViewer.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/PagerPageHolder.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/PagerViewerAdapter.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/webtoon/WebtoonViewer.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/webtoon/WebtoonPageHolder.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/webtoon/WebtoonAdapter.kt`
