# Audit-Driven Translation Fixes — Design Spec (v3)

**Date:** 2026-06-22
**Source:** Audit of `Plan/translation_pipeline_investigation_report.md` + follow-up redundancy/resume audit + inpainting quality audit
**Branch:** `Pre-translation-feature`

> **v3 changelog:** Added inpainting quality tracks (K1/K2/K4-small) after the owner reported visible artifacts on small/tight bubbles (sharp corners, over-erasure) and provided an example image. The inpainting code audit found that contract #16's claimed rounded-corner masking is **dead code** (`BubbleMaskBuilder.roundedAllowedMask`/`insideRoundedRect` have zero production call sites) — directly causing the "corners too sharp" symptom.
>
> **v2 changelog:** After owner challenge, re-verified all load-bearing claims. Two errors found and corrected (§16 diagnosis overstated; standard-path resume claim wrong). Scope expanded to address the owner's actual goal — *"continue where we left off after cancellation or app crashing"* — adding resume-overhaul and crash-recoverable-queue tracks the v1 missed entirely.

---

## 1. Background

`Plan/translation_pipeline_investigation_report.md` is a **living design doc**. An audit against the actual code found four classes of issue; a follow-up redundancy/resume audit (prompted by the owner) found a fifth, larger class; an inpainting-quality audit (prompted by an owner-provided artifact image) found a sixth:

1. **Mislabeled report sections** — §11/§13/§14/§16 read as current-state but are partly proposals, or quote a bug narrower than described, or propose a fix referencing a non-existent method.
2. **§11 PARTIAL gate regression** — single-page gate at `TranslationPipeline.kt:1641` is strict `== READY`, contradicting batch path (`L1133-1137`), `TranslationBlockValidation` design intent, and docs contract #14b.
3. **§16 missing ordering guarantee** — re-flash of original image before translated image on navigation. v1 framed this as a universal race; **v2 correction**: `Dispatchers.Main.immediate` makes the per-holder collector emit synchronously in the common case, so the bug is narrower (offscreen-window and collector-bail paths). The bug is real — confirmed by the in-code diagnostic at `ReaderViewModel.kt:2096-2099` — but the diagnosis must be reframed.
4. **§15 UI gaps** — no in-reader delete affordance; handle always full-opacity.
5. **Resume/redundancy (NEW in v2)** — batch Stage 2/3 never resume despite data being on disk; AI planner re-translates already-translated blocks; manual tap bypasses all resume logic; batch queue is in-memory only and lost on crash.
6. **Inpainting quality (NEW in v3)** — visible artifacts on small/tight bubbles: sharp/angular corners, over-erasure/halos, rectangular borders. The audit found contract #16's rounded-corner fix is **dead code** (zero production call sites), and all morphology constants are absolute pixels (not scaled to bubble size), so the worst output lands on the smallest bubbles.

The §14 PaddleOCR BGR→RGB claim remains highest-risk; ships only behind an on-device A/B validation gate.

---

## 2. Scope

**In scope** (13 tracks, sequenced in §5):

| Track | Area | Type | Visual? |
|-------|------|------|---------|
| A | Report rewrite (§11/§13/§14/§16 corrections, +resume +inpainting findings) | Documentation | No |
| B | §14 PaddleOCR BGR→RGB (gated on empirical A/B) | Code | No (OCR accuracy) |
| C | §11 PARTIAL gate + single-page retries | Code | Minimal |
| D | §16 re-flash fix (reframed) | Code | Removes a flicker |
| E | §15 Delete-in-handle + auto-hide | Code + doc-comment update | **Yes** |
| **G** | **Resume: cleaned-image short-circuit** | Code | No |
| **H** | **Resume: AI block-resume (skip translated blocks)** | Code | No |
| **I** | **Resume: manual `force=false` default** | Code | No |
| **J** | **Crash-recoverable queue (`TranslationQueueStore`)** | Code | No |
| **K1** | **Inpaint: wire in dead rounded-corner masking + disk dilation** | Code | **Yes** (smoother corners) |
| **K2** | **Inpaint: scale morphology constants by bubble size** | Code | **Yes** (less over-erasure on small bubbles) |
| **K4s** | **Inpaint: gate small-box→flat-fill bypass on memory budget** | Code | **Yes** (small bubbles reach neural path) |
| F | Tests, docs, commits | Process | No |

**Out of scope:**
- §11 proposal #3 "selective source overlay" — reverts deliberate fix (`TranslationPipeline.kt:1690-1698`, `renderSourceText=false`). Rejected.
- §12 mobile UI mockup — existing `TranslationProgressSheet` covers it.
- Persisting `Translation.State` across crash — only the queue membership + order is persisted; state is rebuilt as `QUEUE` on rehydration (per owner decision: rehydrate but require Start).
- Touching the `chapters` SQLDelight table — its UPDATE triggers would cause version/sync churn. Avoided.
- **K3 — organic bubble-shape masks for the neural path** (intersect erase mask with `bubbleInteriorMask`). Real fix for rectangular borders, but the owner deferred it — K1's corner rounding handles the visible symptom adequately for now.
- **K4-general — patch-based texture synthesis** for screentone reconstruction. Fundamentally hard (averaging destroys dot frequency); the owner accepted the limitation. Only K4-small (letting small bubbles reach the neural path) is in scope.
- **"Render source under translation" escape hatch** — would reverse the deliberate no-source-fallback design (contract #14b). Rejected.

---

## 3. Track-by-Track Design

### Track A — Report rewrite (no code)

Edit `Plan/translation_pipeline_investigation_report.md`:

- **§11**: relabel all three sub-items as open proposals (verified NOT done). Remove proposal #3 (selective source overlay — reverts a deliberate fix).
- **§13**: keep the exact code quote; add reachability caveat (real toggle-off runs through the preference collector at `PagerPageHolder.kt:120-127`, so "locked out" is narrower than stated).
- **§14**: replace the unconditional "PaddleOCR is RGB-trained" rationale with an evidence-gap note pointing to Track B. Record both possible outcomes.
- **§16**: reframe. The race is **not universal** — `Main.immediate` synchronously delivers the store value to the per-holder collector in the common case. The bug is real in narrower windows: (a) holder created outside the warm window where `attachTranslatedStreamIfWarm` actively nulls the stream (`ReaderViewModel.kt:589-592`); (b) `observePageView` bails on null store/source (`ReaderViewModel.kt:2091-2094`); (c) possible `resolvePageKey` mismatch the in-code diagnostic hints at (`ReaderViewModel.kt:2118-2120`). Reference the diagnostic at `:2096-2099` and note `attachTranslatedStreamForPage` (the proposed fix) does not exist yet — created by Track D.
- **NEW §17** (or expand §4): document the resume/redundancy findings — batch Stage 2/3 never resume; AI planner re-translates finished blocks; manual `force=true` default; in-memory queue lost on crash. Each is resolved by Tracks G–J.
- **NEW §18** (or expand inpainting section): document the inpainting findings — contract #16's rounded-corner masking was dead code (`BubbleMaskBuilder.roundedAllowedMask`/`insideRoundedRect` had zero production call sites); morphology constants were absolute pixels (worst on small bubbles); small boxes force-routed to flat-fill before the neural path could run. Each is resolved by Tracks K1/K2/K4s. **Correct contract #16** to reflect what was actually wired in vs. dead code.

Resolve doc/code drift: contract #14b vs `TranslationPipeline.kt:1641` (resolved by Track C).

---

### Track B — §14 PaddleOCR swap, gated on empirical validation

**File:** `app/src/main/java/eu/kanade/translation/ocr/PaddleOcrV6SmallEngine.kt` (`preprocess()`, L147-159)

**Procedure:**
1. Add a **temporary** diagnostic toggle in `preprocess()` that logs recognition output for a fixed set of text crops under both BGR (current) and RGB tensor ordering. Gated on `translation_diagnostics` to avoid production cost.
2. Build/install, run against known-good vertical + horizontal manga text crops; capture logcat via the absolute `adb.exe` path.
3. **Branch on result:**
   - **RGB wins** → apply the swap at L155-157 (write R, G, B: `pixel shr 16`, `pixel shr 8`, `pixel`). Rewrite the comment block (L151-154) with validated order + test date. Remove the diagnostic toggle.
   - **BGR wins** → leave the code. Document the result in report §14; close as "misdiagnosed — real garbage-output fixes are the preprocess() padding/resize at L103-120."
4. Update report §14 with the evidence either way.

**Rationale for the gate:** the existing comment asserts BGR is deliberate; the report provides no evidence about *this specific exported ONNX model's* expected channel order. Swapping unvalidated could introduce the exact garbage output the report describes.

---

### Track C — §11 PARTIAL render gate + single-page retries

**Files:** `TranslationPipeline.kt` (L1641 gate; L1613 retry insertion), `translator/AiTranslationRetryPlanner.kt` (new pure helper), `docs/TRANSLATION_MODULE.md`.

**C1 — Gate fix** at `TranslationPipeline.kt:1641`:
```kotlin
// before
if (pageTranslation.blocks.isNotEmpty() && pageTranslation.translationStatus == StageStatus.READY)
// after
if (pageTranslation.blocks.isNotEmpty() &&
    (pageTranslation.translationStatus == StageStatus.READY ||
     pageTranslation.translationStatus == StageStatus.PARTIAL))
```
Aligns single-page with batch (`L1133-1137`) and contract #14b. Failed blocks render blank by construction.

**C2 — Single-page retry loop:** after `TranslationBlockValidation.applyTo()` at `L1613`, if `translationStatus == PARTIAL`, loop up to 2 times: extract untranslated blocks → re-call `textTranslator.translatePage(pageKey, pageTranslation)` → re-validate; break early on READY.

New pure helper in `AiTranslationRetryPlanner.kt` (mirrors `untranslatedPages` L17-19 but returns blocks directly, unit-testable):
```kotlin
fun untranslatedBlocks(page: PageTranslation): List<TranslationBlock> =
    page.blocks.filter { block ->
        block.text.isNotBlank() &&
            (block.translation.isBlank() || block.translation.trim() == block.text.trim())
    }
```

**Retry budget invariant:** `retryCount` is NOT bumped for PARTIAL (contract #14b). Bounded by a local counter (max 2).

**C3 — Doc resolution:** update `docs/TRANSLATION_MODULE.md` contract #14b to note the single-page path now also runs local retries.

**Tests:** JVM unit test `AiTranslationRetryPlannerSinglePageTest` for `untranslatedBlocks` (all-translated / partial / all-blank / source-equal cases). Gate/retry loop is Android-bound → on-device verification.

**Cost note for commit:** PARTIAL pages now trigger up to 2 extra LLM round-trips on the interactive path. Acceptable; batch already had this.

---

### Track D — §16 re-flash fix (reframed)

**Files:** `ReaderViewModel.kt` (new public method), `PagerPageHolder.kt` (L303 eager call), `WebtoonPageHolder.kt` (L293 — verify).

**Reframed diagnosis:** the bug is a **missing ordering guarantee**, not a universal race. In the common case `Main.immediate` delivers the store value synchronously and the stream attaches before `setImage()`. The fix is justified because: (a) the bug is confirmed real by the in-code diagnostic at `ReaderViewModel.kt:2096-2099`; (b) it breaks in the offscreen-window path where `attachTranslatedStreamIfWarm` *actively nulls* the stream; (c) it breaks when `observePageView` bails.

**New public method** on `ReaderViewModel`:
```kotlin
fun attachTranslatedStreamForPage(page: ReaderPage) {
    val manga = manga ?: return
    val chapter = getCurrentChapter() ?: return
    val source = sourceManager.get(manga.source) as? HttpSource ?: return
    attachTranslatedStreamIfWarm(page, manga, chapter, source)
}
```
Thin wrapper around existing private `attachTranslatedStreamIfWarm` (`L583-616`). Builds a **lazy** `(() -> InputStream)?` — no disk I/O at attach time (per `TranslationManager.getRenderedImageStream`/`getCleanedImageStream` at `L485,496`). Main-thread-safe.

**Eager call** in `setImage()` at `PagerPageHolder.kt:303`:
```kotlin
if (page.translatedStream == null && showTranslations) {
    viewer.activity.viewModel.attachTranslatedStreamForPage(page)
}
page.showTranslatedImage = showTranslations && page.translatedStream != null
val streamFn = page.stream ?: return
```

**Webtoon:** the audit found webtoon hoisted pref handling to `WebtoonViewer` (`WebtoonPageHolder.kt:46-54, 111-114`). **During implementation:** read `WebtoonPageHolder.setImage()` at `L293`. If it reads `page.translatedStream` without a guaranteed-attached guarantee from the viewer, apply the same eager-attach. If the viewer already attaches before any holder's `setImage()`, skip and document why in the commit.

---

### Track E — §15 Delete-in-handle + auto-hide

**Files:** `TranslationCompareHandle.kt`, `ReaderActivity.kt` (L505), new string resource.

**E1 — Delete row:** add `onDeleteTranslation: () -> Unit` param. New `MenuRow` at bottom of `MenuColumn`, only when `hasTranslation`:
```kotlin
if (hasTranslation) {
    MenuRow(
        icon = Icons.Outlined.Delete,
        label = stringResource(ATMR.strings.reader_compare_delete_translation),
        tint = MaterialTheme.colorScheme.error,
        enabled = true,
        onClick = { onDeleteTranslation(); expanded = false },
    )
}
```
Wire at `ReaderActivity.kt:505`: `onDeleteTranslation = { viewModel.deleteCurrentChapterTranslation() }` (method exists at `ReaderViewModel.kt:1614`).

**E2 — Auto-hide (Dim + slide off-edge):** new `isIdle` state + `LaunchedEffect` 2.5s timer (starts when menu collapsed). Animate handle: `alpha → 0.4f`, `offsetX → (-20).dp` via `animateFloatAsState`/`animateDpAsState` (tween 180). Any handle/screen tap resets `isIdle = false`. Idle triggers only when `!expanded`. Add screen-tap listener for recovery (handle `clickable` already covers handle taps).

**Mandatory doc-comment update:** rewrite `TranslationCompareHandle.kt:54-57` from "always present… non-intrusive" to describe the new idle behavior. Per AGENT.md, stale comments are worse than none.

---

### Track G — Resume: cleaned-image short-circuit (NEW)

**Problem (confirmed):** `inpaintPage` (`TranslationPipeline.kt:2270`) has **no** `cleanedImageName != null` short-circuit. The expensive neural inpainter re-runs on every resumed page even when `.cleaned.png` sits on disk. Cost: EXPENSIVE (one of the 3 most expensive stages).

**Fix:** add a short-circuit at the top of the inpaint call sites. When `cleanedImageName != null && inpaintStatus == READY && hasCurrentInpaintResult`, skip the neural inpaint and load the persisted cleaned bitmap instead.

**Call sites:**
1. `inpaintPage()` itself (`TranslationPipeline.kt:2270`) — return the persisted cleaned bitmap early.
2. Batch Stage 2 AI loop gate (`TranslationPipeline.kt:1133-1137`) — extend the existing `translationStatus` check with an inpaint-skip branch.
3. Batch Stage 2 non-AI loop (`TranslationPipeline.kt:878-925`) — add the same skip.
4. `resumeInpaintAndRender()` (`TranslationPipeline.kt:1912-1983`) — currently re-inpaints unconditionally at `L1941` even when `cleanedImageName != null`. Add the short-circuit; the single-page fast-path at `L1385-1407` already loads cleaned-from-disk correctly — generalize it.

**Invariant:** never skip inpaint if `!hasCurrentInpaintResult` (the mask may be stale — contract #14a). The short-circuit requires both the cleaned image AND a current mask.

**Cost saved:** neural inpaint (AOT, multiple full-page ARGB_8888 allocations per `AOTInpainting.kt:455-601`) is skipped on every resumed page. This is the single biggest perf win in the resume path.

---

### Track H — Resume: AI block-resume (NEW)

**Problem (confirmed):** `TranslationContextChunkPlanner.plan()` at `L72` only skips blank *source* blocks — never checks `block.translation.isNotBlank()`. On a resumed AI batch, **every already-translated block is re-sent to the LLM**. Cost: EXPENSIVE (wasted LLM tokens + HTTP per finished block).

**Fix:** extend the skip filter in `TranslationContextChunkPlanner.kt:67-102`:
```kotlin
page.blocks.forEachIndexed { blockIndex, block ->
    if (rejectReason != null) return@forEachIndexed
    if (block.text.isBlank()) return@forEachIndexed
    // NEW: skip blocks that already have a real translation (resume case)
    if (block.translation.isNotBlank() && block.translation.trim() != block.text.trim()) {
        return@forEachIndexed
    }
    val ref = BlockRef(pageKey, blockIndex, block)
    ...
}
```

**Edge case:** a page where ALL blocks are already translated → produces an empty chunk → the planner's existing "no blocks to plan" path must mark the page `translationStatus = READY` directly (not skip it entirely). Verify the planner's empty-result handling; if it currently treats "no blocks" as "page failed", add a branch: "all blocks already translated → page READY".

**Tests:** extend `TranslationContextChunkPlannerTest` with resume cases (page with mixed translated/untranslated blocks → only untranslated planned; page fully translated → page marked READY, no LLM call).

**Cost saved:** on resume, only genuinely-untranslated blocks consume LLM budget. For a chapter resumed at page 150/200, blocks on pages 1-150 are no longer re-sent.

---

### Track I — Resume: manual `force=false` default (NEW)

**Problem (confirmed):** `TranslationExecutor.translateSinglePage` defaults `force = true` (`TranslationExecutor.kt:31`). The interface comment (`L18-22`) says this is "by design" — manual taps always re-run. But the consequence is: a user tapping translate on a page that auto-prefetch already completed burns a full re-OCR + re-translate + re-inpaint + re-render. The resume logic at `TranslationPipeline.kt:1369-1543` is effectively dead for the manual path.

**Fix:** change the default to `force = false` for the manual per-page path, and keep `force = true` only for the explicit "force re-translate" UI affordance (long-press / re-translate button).

**Specific changes:**
1. `TranslationExecutor.translateSinglePage` and `translateSinglePageFromStream` (`L26-41`): change default `force: Boolean = true` → `force: Boolean = false`.
2. `TranslationScheduler.translatePage` (`L418-438`) and `TranslationScheduler.kt:443` (manual tap call site): rely on the new default (do not pass `force` explicitly).
3. The two existing `force=false` auto-prefetch call sites (`TranslationScheduler.kt:219-225, 264-271`) are unaffected.
4. **Find or add** an explicit "force re-translate" entry point (e.g., long-press menu, or a dedicated retry button) that passes `force = true`. If no such affordance exists today, the manual button becomes non-forcing by default and the user gets the resume behavior; a separate "redo this page" action can be added later if needed.

**Risk:** this changes existing user-visible behavior (tapping translate no longer nukes existing work). Per AGENT.md, document this in the commit message and update the interface comment at `TranslationExecutor.kt:18-22`.

**Cost saved:** manual tap on an already-prefetched page no longer redoes OCR/inpaint/translate/render.

---

### Track J — Crash-recoverable queue (NEW)

**Problem (confirmed):** the batch queue is `MutableStateFlow<List<Translation>>` at `ChapterTranslator.kt:131` — purely in-memory. `ChapterTranslator` has no `init` block; on app launch the queue is always empty. **A crash mid-batch loses the entire queue.**

**Fix:** new `TranslationQueueStore` mirroring the existing `DownloadStore` pattern (per owner decision + the consistency argument that the download queue already solves the identical problem).

**New file:** `app/src/main/java/eu/kanade/translation/TranslationQueueStore.kt`, modeled on `app/src/main/java/eu/kanade/tachiyomi/data/download/DownloadStore.kt`:
```kotlin
class TranslationQueueStore(
    context: Context,
    private val json: Json = Injekt.get(),
) {
    // Separate prefs file — mirrors DownloadStore's "active_downloads" isolation.
    private val preferences = context.getSharedPreferences("translation_queue", Context.MODE_PRIVATE)

    fun save(queue: List<Long>) {  // ordered list of chapter ids
        preferences.edit {
            clear()
            queue.forEachIndexed { index, chapterId ->
                putString("$index", chapterId.toString())
            }
        }
    }

    fun load(): List<Long> {
        val result = mutableListOf<Long>()
        var i = 0
        while (true) {
            val raw = preferences.getString("$i", null) ?: break
            result += raw.toLongOrNull() ?: break
            i++
        }
        return result
    }

    fun clear() { preferences.edit { clear() } }
}
```

**Wiring:**
1. Inject `TranslationQueueStore` into `ChapterTranslator`.
2. **Persist** on every queue mutation: `addToQueue`, `removeFromQueue`, `removeFromQueueIf`, `internalClearQueue` (`ChapterTranslator.kt:266, 504, 513, 535`). One-line `queueStore.save(_queueState.value.map { it.chapter.id })` after each `_queueState.update`.
3. **Rehydrate** in a new `ChapterTranslator.init` block (or a dedicated `restoreQueue()` called from `TranslationManager.init`): read `queueStore.load()` → for each chapterId, call `Translation.fromChapterId(chapterId)` (`Translation.kt:42-55`) → set status to `QUEUE` (per owner decision: rehydrate but require Start) → populate `_queueState`. Filter out nulls (deleted chapters self-heal).
4. **Clear** on app uninstall / when the queue legitimately empties (already covered by the persist-on-mutation pattern — saving an empty list clears the prefs).

**Why SharedPreferences and not SQLDelight:** the download queue (`DownloadStore.kt`) uses the identical pattern for the identical problem (persist ordered chapter list across restart, rebuild via lookups). Consistency with that established pattern outweighs the cleaner schema of a new table. The `chapters` table is explicitly avoided (its UPDATE triggers at `chapters.sq:26-49` would cause version/sync churn). SharedPreferences self-heals via `Translation.fromChapterId`'s null return for deleted chapters.

**Resume trigger (per owner decision):** rehydrated entries get `status = QUEUE`, not `TRANSLATING`. The user sees them in the Queue screen and must tap Start to resume. Never auto-starts background OCR/LLM work on launch.

**Tests:** JVM unit test with `InMemoryPreferenceStore`-style fake — save/load round-trip preserves order, deleted-chapter filtering, empty-queue clear.

---

### Track K1 — Inpaint: corner rounding (wire dead code + disk dilation) (NEW)

**Problem (confirmed):** contract #16's rounded-corner masking is **dead code**. `BubbleMaskBuilder.roundedAllowedMask` (`BubbleMaskBuilder.kt:24`) and `insideRoundedRect` (`BubbleMaskBuilder.kt:78`) have zero production call sites — only referenced in `BubbleMaskBuilderTest`. Every erase mask in both paths is a sharp rectangle softened only by a box-blur chamfer. Additionally, `BubbleMaskBuilder.dilateMask` (`:175-200`) is a 4-neighbourhood (Manhattan-diamond) grower producing 45° chamfers, and the neural path's `AOTInpainting.dilateMask` (`:717-751`) uses a square (Chebyshev) kernel — neither rounds corners.

**Fix (two parts):**

**K1a — Switch dilation to a disk structuring element.** Add a disk-SE variant in `BubbleMaskBuilder` (precomputed kernel where `dx²+dy² ≤ r²`) and use it instead of the 4-neighbourhood loop in `dilateMask` (`BubbleMaskBuilder.kt:175-200`). A disk SE grows masks isotropically, rounding corners without the "bridge thin gaps" concern of an 8-neighbourhood square (a disk of radius N has the same diagonal reach as a diamond of radius N). Mirror this in `AOTInpainting.dilateMask` (`:717-751`) — switch the square SE to a disk. The docstring at `BubbleMaskBuilder.kt:166-173` already notes callers wanting rounder growth should use 8-neighbourhood; a disk is the proper round-corner version.

**K1b — Wire in `roundedAllowedMask` on the FAST path.** In `SmartBubbleTextCleaner.cleanBubbleGroup`, intersect the final erase mask with `BubbleMaskBuilder.roundedAllowedMask` derived from the parent bubble box (when one exists). This bounds the erase region by the bubble's rounded interior rather than the text bounding box. For free text without a parent bubble, leave the existing behavior (no organic shape to bound against).

**Why safe:** pure morphology math, no model/IO changes. Existing tests cover it: `BubbleMaskBuilderTest` (dilateMask growth + andMasks + roundedAllowedMask + insideRoundedRect) and `SmartBubbleTextCleanerTest` (fill behavior). Add a test case for the disk-SE growth shape (diamond vs disk at radius 3).

**Risk:** a disk SE bridges slightly more than a diamond at the same iteration count. Mitigation: the "bridge thin gaps" concern matters at 1px gaps, which 3-iteration growth already bridges regardless of SE shape. Verify on a dense-bubble test page on-device.

---

### Track K2 — Inpaint: scale morphology constants by bubble size (NEW)

**Problem (confirmed):** all morphology constants in `SmartBubbleTextCleaner` are **absolute pixels**, not scaled to bubble size:
- `featherRadius: Int = 6` (`SmartBubbleTextCleaner.kt:24`)
- `dilationIterations: Int = 3` (`:23`)
- `contextPad: Int = 10`, `textMaskPad: Int = 2` (`:14-15`), and `mp = max(textMaskPad, 8)` at `:131`/`:320`

On a 40px bubble, the 6px feather ring consumes ~15% of each side → over-erasure/halo. On a 200px bubble it's negligible. This is why small/tight bubbles produce the worst artifacts.

**Fix:** make `featherRadius`, `dilationIterations`, and `mp` functions of `min(bubbleW, bubbleH)` rather than absolute. Proposed scaling (tune on-device):
```kotlin
val minDim = min(bubbleW, bubbleH)
val featherRadius = max(2, min(6, minDim / 12))   // 2 for tiny, 6 for ≥72px
val dilationIterations = max(1, min(3, minDim / 20))  // 1 for tiny, 3 for ≥60px
val mp = max(textMaskPad, min(8, minDim / 8))     // smaller pad for tiny bubbles
```

**Touch points:** `SmartBubbleTextCleaner.kt:23-24` (fields → computed per-call), `:131`/`:320` (mp), `:200`/`:416` (dilation call sites), `:216`/`:432` (featherAlpha call sites). The scaling happens inside `cleanBubbleGroup` / `cleanSingleRegion` where `bubbleW`/`bubbleH` are known, not at construction.

**Why safe:** pure morphology tuning. Existing tests (`SmartBubbleTextCleanerTest` local-background fill, tightDifferenceMask, applyFeatheredFill ring-blend) cover the behavior; add a small-bubble-size case (e.g. 30×30) to pin the scaling.

**Risk:** too-aggressive downscaling could under-cover anti-aliased stroke edges on small bubbles (the original reason for `dilationIterations=3`). Mitigation: the floors (`max(2,...)`, `max(1,...)`) prevent zero; verify on a small-bubble test page that text is still fully erased.

---

### Track K4s — Inpaint: gate small-box→flat-fill bypass on memory budget (NEW)

**Problem (confirmed):** `AOTInpainting.inpaintRegions` force-routes small boxes to the flat-fill path before the neural model can see them, regardless of QUALITY mode:

```kotlin
// AOTInpainting.kt:223-235
val smallBoxAreaThreshold = pageArea / 200   // ~128×128 for a typical page
for (box in freeBoxesForAot) {
    val boxArea = (box[2] - box[0]).toLong() * (box[3] - box[1]).toLong()
    if (boxArea < smallBoxAreaThreshold) {
        freeSmallBoxes.add(box)              // → flat fill, never neural
    } else if (bubbleCleaner.isFlatBackgroundRegion(result, box)) {
        freeFlatBoxes.add(box)
    } else {
        freeNeuralBoxes.add(box)
    }
}
```

So a user in QUALITY mode expecting neural reconstruction on a small bubble silently gets the flat color-average fill — the exact case that produces the worst screentone mismatch. The bypass exists for memory/speed, not quality.

**Fix:** reorder the checks so `isFlatBackgroundRegion` runs **first**, and gate the small-box bypass on memory budget instead of absolute area:
```kotlin
for (box in freeBoxesForAot) {
    when {
        bubbleCleaner.isFlatBackgroundRegion(result, box) -> freeFlatBoxes.add(box)
        // Only fall back to flat-fill for small boxes when memory is constrained;
        // otherwise let them reach the neural path for screentone reconstruction.
        !TranslationMemoryBudget.canRunNeuralInpaint() -> freeSmallBoxes.add(box)
        else -> freeNeuralBoxes.add(box)
    }
}
```

**Why this works:** `isFlatBackgroundRegion` (`SmartBubbleTextCleaner.kt:464-497`) classifies by `grayStd`; a flat white bubble correctly routes to flat-fill (fast and correct), while a small bubble on screentone (high `grayStd`) now reaches the neural path when memory allows. The memory gate (`canRunNeuralInpaint`, already used elsewhere in the pipeline) preserves the OOM-safety intent of the original bypass.

**Why safe:** the memory gate preserves the original safety purpose; only the routing order + criterion change. The neural path already handles arbitrary box sizes correctly (its crop-margin logic at `AOTInpainting.kt:322-331` is proportional).

**Risk:** more boxes reaching the neural path → more model inferences → slower inpainting on text-heavy pages under QUALITY mode. Mitigation: the memory gate caps this under pressure; the user opted into QUALITY mode knowing it's slower. Note the perf tradeoff in the commit message.

**Verification:** on a page with small bubbles on screentone background, confirm under QUALITY mode + adequate memory that small bubbles now get neural reconstruction (visible dot pattern) instead of flat gray.

---

### Track F — Tests, docs, commits

- **JVM unit tests:** Track C (`untranslatedBlocks`), Track H (planner resume cases), Track J (queue store round-trip), Track K1 (disk-SE growth + roundedAllowedMask wiring), Track K2 (small-bubble scaling case). Run `.\gradlew.bat :app:testStandardDebugUnitTest`.
- **Docs:** `docs/TRANSLATION_MODULE.md` — Track C drift resolution; new resume short-circuits in Tracks G/H/I; new `TranslationQueueStore` in Track J; new `attachTranslatedStreamForPage` in Track D; new `TranslationCompareHandle` behavior in Track E; **Track K1/K2 corrections to contract #16** (rounded-corner masking is now actually wired in, not dead code; morphology constants now scale by bubble size); Track K4s small-box routing change. Update report (Track A). All immediate per AGENT.md doc-first rule.
- **Build:** `.\gradlew.bat :app:assembleStandardDebug`.
- **On-device:** install via absolute `adb.exe` (per AGENT.md) for B/C/D/E/G/H/I/J/K1/K2/K4s validation. Track B requires the A/B OCR comparison; Track J requires kill-app-then-relaunch queue recovery test; Tracks K1/K2/K4s require before/after comparison on a small-bubble + screentone test page.
- **Commits:** one logical commit per track, in execution order (§5). Descriptive imperative messages. Review `git diff --staged` per AGENT.md.

---

## 4. Sequencing

```
Phase 1 — Documentation & low-risk correctness
  A (report rewrite)         ── sets the reference doc
  C (PARTIAL gate)           ── small, restores consistency, unit-tested
  D (re-flash fix)           ── contained, one new public method

Phase 2 — Resume overhaul (the owner's actual goal)
  G (cleaned-image short-circuit)  ── biggest perf win
  H (AI block-resume)              ── LLM token savings
  I (manual force=false)           ── behavior change, needs care
  J (crash-recoverable queue)      ── new component, ships last in this phase

Phase 3 — Inpainting quality (safe morphology wins first)
  K1 (corner rounding)       ── wires in dead code + disk SE; existing tests cover it
  K2 (scale by bubble size)  ── pure constant tuning; existing tests cover it
  K4s (small-box neural)     ── routing change, biggest visual win on screentone

Phase 4 — UI + highest-uncertainty
  E (UI additions)           ── isolated to reader components
  B (PaddleOCR)              ── on-device validation gate, last
```

Each track ships independently; the reader remains functional throughout.

---

## 5. Risks & Mitigations

| Risk | Mitigation |
|------|------------|
| §14 RGB swap regresses OCR | On-device A/B gate in Track B — ships only if RGB empirically wins |
| §11 retries add latency to interactive path | Bounded to 2 retries; only on PARTIAL; commit notes the tradeoff |
| §15b auto-hide reverses documented design | Doc comment rewrite mandatory in Track E2 |
| §16 eager-attach blocks main thread | Streams are lazy factories — no disk I/O at attach time |
| Track I changes manual-tap behavior | Update interface comment; document in commit; provide explicit force affordance if needed |
| Track J rehydrates stale chapter ids | `Translation.fromChapterId` returns null for deleted chapters — self-heals; rehydrate sets QUEUE (no auto-start) |
| Track G short-circuits stale cleaned image | Requires both `cleanedImageName != null` AND `hasCurrentInpaintResult`; contract #14a invariant preserved |
| Track H planner empty-chunk case | Verify planner's empty-result handling; add "all already-translated → READY" branch if needed |
| K1 disk-SE bridges more than diamond | Same-radius disk has same diagonal reach as diamond; 3-iter growth already bridges 1px gaps. Verify on dense-bubble page on-device |
| K2 over-aggressive downscaling under-covers stroke edges | Floors (`max(2,...)`, `max(1,...)`) prevent zero; verify on small-bubble page that text is still fully erased |
| K4s slows QUALITY-mode inpainting (more boxes → neural) | Memory gate (`canRunNeuralInpaint`) caps under pressure; user opted into slower QUALITY mode. Note tradeoff in commit |
| Contract #16 docs overstate what's wired in | Track A report rewrite + docs update must correct contract #16: rounded-corner masking was dead code until K1; K3 (organic shape) explicitly deferred |

---

## 6. Open Items Resolved by Owner Decision

- Report role: **living design doc**
- §14: **trust the report**, with empirical safeguard
- PARTIAL: **Option A + retries (full §11)**
- §15b idle state: **Dim + slide off-edge** (0.4f, −20.dp, ~2.5s)
- Scope: **full resume overhaul** (Tracks G/H/I/J added in v2)
- §16: **reframe + keep fix** (diagnosis corrected)
- Queue storage: **SharedPreferences mirroring DownloadStore** (consistency with existing download-queue pattern; avoids chapters-table triggers; no migration)
- Resume trigger: **rehydrate but require Start**
- **Inpainting scope (v3): K1 + K2 + K4s only.** K3 (organic bubble-shape masks) and K4-general (patch-based texture synthesis) are out of scope — owner accepted the screentone limitation; K1's corner rounding handles the visible rectangular-border symptom adequately for now.

---

## 7. Spec revision history (what earlier versions got wrong)

**v1 → v2 corrections:**
1. **§16 diagnosis overstated.** v1 framed the re-flash as a universal race ("`setImage()` reads null before attachment"). Re-verification found `Dispatchers.Main.immediate` (`PagerPageHolder.kt:78`) makes the per-holder collector emit synchronously, so in the common case the stream attaches before `setImage()`. The bug is real but narrower (offscreen-window, collector-bail, possible key-mismatch). Fix shape unchanged; diagnosis reframed.
2. **"Standard path has different resume behavior."** v1 implied a `translateBatchStandardStage2` function. **No such function exists** — the standard path is an inline `else` (`TranslationPipeline.kt:836-931`) with the same no-resume behavior as AI. Both paths always re-translate + re-inpaint + re-render.
3. **Missed the entire resume/redundancy class.** v1 did not address: cleaned-image re-inpaint (Track G), AI block re-translation (Track H), manual force-default (Track I), or the in-memory-only queue (Track J) — despite these being the owner's actual concern. Added in v2 after the redundancy/resume audit.

**v2 → v3 corrections:**
4. **Docs contradict the code on rounded-corner masking.** `docs/TRANSLATION_MODULE.md` contract #16 implies rounded-corner masking is applied; the audit found `BubbleMaskBuilder.roundedAllowedMask`/`insideRoundedRect` have **zero production call sites** (test-only). Track K1 wires it in; Track A corrects the doc.
5. **Inpainting was entirely out of scope.** v1/v2 didn't address inpainting quality at all. The owner provided an artifact image showing sharp corners and small-bubble over-erasure; the inpainting audit (K1/K2/K4s) was added in v3.
