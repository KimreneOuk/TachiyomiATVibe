# TachiyomiAT Rolling Chapter Context & Prepare Chapter Plan

## Goal

Improve AI manga translation quality by giving each page's translation call
awareness of the chapter around it, and add a user-initiated "Prepare chapter"
mode that fully or partially translates a chapter before reading.

This is an Android app, so the plan must prioritize:

* Low memory usage (no new heavy parallel work)
* Predictable lifecycle cleanup (context resets cleanly per chapter)
* Current-page-first UX unchanged (auto-translate behavior preserved)
* Opt-in cost (no surprise token spend for metered API users)
* Reuse of the existing per-chapter store, permit, and pipeline

---

## Background: why this is needed

Every AI-translation call today is isolated. Both call sites:

* batch: `ChapterTranslator.kt:~900` (`textTranslator.translatePage(...)`)
* single-page: `ChapterTranslator.kt:~1431` (`textTranslator.translatePage(...)`)

wrap one page in a single-element map. The AI translators
(`GeminiTranslator`, `OpenRouterTranslator`, `DeepSeekTranslator`,
`LmStudioTranslator`) build one JSON request per page with zero knowledge of
any other page. No character names, prior dialogue, story state, or established
terminology flows forward. Auto-translate prefetch (n, n+1, n+2) is processed
sequentially but independently; only which page is being worked on rolls
forward, never context.

The consequences:

* A character called "Yuki" on page 3 may become "Snow" on page 15.
* "Did HE really say that?" on page 4 cannot resolve "he" without page 3.
* Tone/register established early drifts across a long chapter.

---

## Decisions Locked (from brainstorming)

| Decision | Choice |
| --- | --- |
| Primary goal | All of: consistency, reference resolution, overall quality |
| Context scope | Within current chapter only (resets on chapter switch) |
| Context model | Sliding window (no two-pass pre-analysis) |
| History layer | Verbatim window + rolling summary |
| Window anchor | Reading order (`index < current`), sparse-aware |
| Consistency gap (cold page filled later) | Option 1: leave as-is, manual `force=true` re-translate |
| Default state | Opt-in setting, off by default |
| Phase order | Rolling-context → Prepare (full) → Prepare (OCR-only) |
| Entry point for Prepare | Chapter list / long-press menu (pre-reader) |
| Window size / cadence | K = 3 verbatim pages, M = 3 summarize cadence |

Open items deferred to implementation:

* Exact chapter-list menu wiring (long-press bottom-sheet vs. overflow).
* Whether M is also user-tunable (K is fixed at 3 for v1).

---

## Architecture Overview

Three phases, each independently shippable. Shared components are called out
at the end.

### Phase 1: Rolling Context

A new `ChapterContextBuilder` assembles a context bundle before each
`textTranslator.translatePage(...)` call and injects it into the translator's
prompt. Built per-page, reading-order anchored, sparse-aware.

**Context bundle:**

* **Verbatim window:** translated dialogue (target language, not OCR source) of
  the last `K=3` pages with `index < current` and `translationStatus == READY`.
* **Rolling summary:** one paragraph compressing everything older than the
  window, regenerated every `M=3` pages. Gated to fire only when
  `pages-before-current > K` (otherwise the window already carries everything
  and a summarize call is wasted). Summarizer prompt instructs it to carry
  proper nouns and recurring terms forward verbatim (free soft-glossary).

So page N's translation prompt is:

```
[summary of pages older than window]
[verbatim translated dialogue of last K pages before N]
[page N's OCR text to translate]
```

**New chapter-level state** (persisted alongside the existing page map):

* `contextSummary: String?` — current rolled summary text
* `contextSummaryUpTo: String?` — storage key of the page up to which the
  summary covers (so the builder knows whether to roll further)

This lives in a new `ChapterContextState` carried by `ChapterTranslationStore`,
not per-page. It rolls correctly regardless of read order and resets with the
store on chapter switch.

**Translate-call shape change:**

```kotlin
interface TextTranslator {
    suspend fun translate(
        pages: MutableMap<String, PageTranslation>,
        context: TranslationContext? = null,
    )
}
```

```kotlin
data class TranslationContext(
    val summary: String?,
    val recentPages: List<Pair<String, String>>, // (pageKey, translatedText)
)
```

Each AI translator prepends a "Preceding context" block to its existing
system/user prompt when `context != null`. Standard translators (MLKit,
Google) ignore it.

**Setting:** `translationRollingContext()` — opt-in bool, default false, placed
near `translationDiagnostics()` in `TranslationPreferences`.

**Sparse / cold-start behavior (no special code needed):**

| Page translated | Predecessors present | Verbatim | Summary | Summarize call? |
| --- | --- | --- | --- | --- |
| 176 | none | — | — | no (cold, like today) |
| 177 | 176 | [176] | — | no |
| 180 | 176..179 | [177,178,179] | would fire | **no** (gated: history not > K) |
| 183 | 176..182 | [180,181,182] | covers 176..179 | yes, useful |

Scrolling backward stays safe: a page only ever sees strictly-earlier pages,
never future dialogue. Gaps in the middle are fine (shorter window, not
malformed).

**Consistency gap (decided):** Option 1. A page translated cold stays cold
even if predecessors are filled in later. The existing per-page translate
button (`force = true`) is the escape hatch. Auto re-translate on gap fill
(Option 2) is documented as future work.

### Phase 2: Prepare (Full)

Expose the existing full-chapter batch translate behind a user-initiated entry
point with a config-confirmation dialog.

**Entry point:** "Prepare chapter" action in the chapter list / long-press
menu (pre-reader). Matches the "prepare at home, read on the go" story.

**Config-confirm sheet:** shows the current effective config read live from
prefs — from-lang, to-lang, OCR model, AI engine, model. User confirms (or
edits in place), then the existing `TranslationManager.translateChapter(...)`
batch path runs for the whole chapter.

**Why it is small:** zero new pipeline logic. The `EngineSignature` rebuild
machinery in `ChapterTranslator` already handles config changes at batch start.
The work is:

* the action / entry point
* the config-confirm sheet (reusing existing translation-settings fields)
* wiring to the existing batch call

**Closes a real loop:** a user who wants a fully-translated chapter before
reading (e.g. offline) gets a first-class path instead of fighting
auto-prefetch.

### Phase 3: Prepare (OCR-only)

A genuinely new partial-stage runner. Detect + OCR every page, persist
`ocrStatus = READY` with source text + blocks, leave
`translationStatus / inpaintStatus / renderStatus = PENDING`. Reuses the same
config-confirm sheet and entry point as Phase 2; the sheet gains a mode
toggle: "Full" vs "OCR only".

**New code:** a `stages: Set<Stage>` parameter (or a
`translateChapterPartial`) on the existing batch loop in
`translateChapterInternal`. The existing `while (pageIndex < streams.size)`
loop already runs per-page; this mode skips the translate / inpaint / render
stages and persists the partial result.

**What it unlocks:** supercharges Phase 1. After an OCR-only prepare pass,
every read-time translation has full source-side backward context (from the
pre-OCR'd pages) and can have forward source lookahead too. The page-176
cold-start problem gets source context even when translated context is sparse.

**Memory note:** detect+OCR skips the inpaint memory spike (the AOT generative
model), which is the exact stage that drives `autoFallbackToFast` and OOM
instability today. A speculative OCR prepass is more memory-stable than a full
translate prepass. With MangaOcr (autoregressive, up to 300 decoder steps per
ROI, CPU-forced) a wide prepass over a long chapter is still minutes of work;
with ML Kit / PaddleOCR it is much faster. Progress UI must set honest
expectations.

---

## Shared Components (across all phases)

| Concern | Decision |
| --- | --- |
| Master gate | `translationRollingContext()` gates Phase 1. Phases 2 & 3 are user-initiated actions gated only by the existing master `translationEnabled()`. |
| Config-confirm sheet | Built in Phase 2, reused in Phase 3. Single UI component. |
| Per-chapter store | All three write to the same `ChapterTranslationStore`. Phase 1 adds chapter-level context state; Phases 2/3 write per-page state the store already models. |
| Permit / serialization | All three run under the existing `translatorPermit`. No new concurrency model. |

---

## Phase 1 — Files

* `domain/src/main/java/tachiyomi/domain/translation/TranslationPreferences.kt`
  — add `translationRollingContext()`.
* `app/src/main/java/eu/kanade/translation/translator/TextTranslator.kt`
  — add `context: TranslationContext?` param + the `TranslationContext` type.
* `app/src/main/java/eu/kanade/translation/translator/GeminiTranslator.kt`
  — prepend context block to prompt when present.
* `app/src/main/java/eu/kanade/translation/translator/OpenRouterTranslator.kt`
  — prepend context block to prompt when present.
* `app/src/main/java/eu/kanade/translation/translator/DeepSeekTranslator.kt`
  — prepend context block to prompt when present.
* `app/src/main/java/eu/kanade/translation/translator/LmStudioTranslator.kt`
  — prepend context block to prompt when present.
* `app/src/main/java/eu/kanade/translation/ChapterTranslator.kt`
  — build context before both `translatePage(...)` call sites and pass it
  through; skip when `translationRollingContext()` is off or engine is STANDARD.
* `app/src/main/java/eu/kanade/translation/ChapterTranslationStore.kt`
  — carry `ChapterContextState` alongside the page map; expose read/update.
* NEW `app/src/main/java/eu/kanade/translation/context/ChapterContextBuilder.kt`
  — reads store, assembles verbatim window + summary, decides whether to roll
  the summary, returns `TranslationContext`.
* NEW `app/src/main/java/eu/kanade/translation/context/ChapterContextState.kt`
  — serializable state (`contextSummary`, `contextSummaryUpTo`).
* `app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsTranslationScreen.kt`
  — expose the opt-in toggle.

## Phase 1 — Verify

* Context is only built when `translationRollingContext()` is on AND the active
  engine category is `AI_MODEL`. Standard engines never receive context.
* The verbatim window contains only pages with `index < current` in reading
  order and `translationStatus == READY`.
* The summarize call fires only when `pages-before-current > K`.
* A page scrolled-back-to never sees future dialogue.
* Rolling context off = byte-for-byte the current behavior (no prompt change).
* Chapter switch / reader close / "Stop all translation" clears context state
  exactly once, with the store, with no orphaned coroutines.

## Phase 1 — Acceptance Tests

* Open a chapter at page 1 with rolling context on. Translate forward. Pages
  4+ show context-aware terminology consistent with pages 1-3.
* Open a chapter at page 30 of 40. Page 30 is cold; pages 31-32 carry page 30;
  from ~page 33 the summary starts rolling. No crash, no missing-data branch.
* Toggle rolling context off mid-chapter. Next page translates without any
  context block (identical to today).
* Use a STANDARD engine (MLKit). Translation completes; context is never
  passed; no prompt errors.

---

## Phase 2 — Files

* `app/src/main/java/eu/kanade/translation/TranslationManager.kt`
  — entry method to enqueue a full prepare (wraps existing `translateChapter`).
* `app/src/main/java/eu/kanade/translation/ChapterTranslator.kt`
  — (minimal) ensure batch path is callable from the new entry; reuse as-is.
* NEW `app/src/main/java/eu/kanade/translation/prepare/PrepareChapterSheet.kt`
  (or Presentation composable) — config-confirm sheet.
* Chapter list / long-press menu host — add the "Prepare chapter" action.

## Phase 2 — Verify

* Config-confirm sheet reads live prefs and reflects current effective config.
* Editing a field in the sheet invalidates the cached translator (the existing
  `EngineSignature` rebuild gate fires on the next batch).
* Confirming enqueues the chapter for full batch translate; the existing
  queue/status flow surfaces progress.
* A chapter already translated is re-queued cleanly (existing
  `queueChapter` delete-and-replace path).

## Phase 2 — Acceptance Tests

* Long-press a chapter, tap "Prepare chapter", confirm. The chapter translates
  fully; opening the reader shows translated pages with no per-page tap needed.
* Change the target language in the sheet before confirming. The chapter
  translates into the newly selected language, not the prior one.
* Cancel mid-batch. Partial progress is preserved in the store; reopening the
  reader shows the already-translated pages.

---

## Phase 3 — Files

* `app/src/main/java/eu/kanade/translation/ChapterTranslator.kt`
  — add partial-stage runner (stop after OCR, persist partial).
* `app/src/main/java/eu/kanade/translation/prepare/PrepareChapterSheet.kt`
  — add mode toggle (Full / OCR only).
* `app/src/main/java/eu/kanade/translation/ChapterContextBuilder.kt`
  — use pre-OCR'd source pages for forward source lookahead when available.

## Phase 3 — Verify

* OCR-only prepare fills `ocrStatus = READY` + `blocks` for every page; leaves
  `translationStatus / inpaintStatus / renderStatus = PENDING`.
* A subsequent read-time translate of a pre-OCR'd page does not re-run OCR.
* The inpaint memory spike is never hit during the OCR-only pass.
* Progress UI reports honestly (slow on MangaOcr, fast on ML Kit/Paddle).

## Phase 3 — Acceptance Tests

* Long-press a chapter, "Prepare chapter" → "OCR only", confirm. Every page
  reaches `ocrStatus = READY`. No rendered images exist yet.
* Open the reader. Pages translate using pre-OCR source context; rendered
  output appears per page as read.
* Run an OCR-only prepare on a 200-page chapter with MangaOcr. No OOM; the
  pass completes (slowly) with accurate progress.

---

## Patch Order

### Phase 1

1. **Add preference + types.** `translationRollingContext()` in
   `TranslationPreferences`; `TranslationContext` type; extend the
   `TextTranslator.translate` signature with the optional context param
   (default null = current behavior).
2. **Wire context into the four AI translators.** Each prepends a context
   block when `context != null`. No change to standard translators.
3. **Add `ChapterContextState` + store support.** Persist
   `contextSummary` / `contextSummaryUpTo` alongside the page map.
4. **Implement `ChapterContextBuilder`.** Reading-order window, sparse-aware,
   summarize-call gate, summarizer prompt with proper-noun preservation.
5. **Wire the builder into both `translatePage(...)` call sites** in
   `ChapterTranslator`, gated on the preference and engine category.
6. **Expose the opt-in toggle** in `SettingsTranslationScreen`.

### Phase 2

7. **Build the config-confirm sheet** (live config read, editable fields).
8. **Add the "Prepare chapter" action** to the chapter list / long-press menu.
9. **Wire to the existing batch path.**

### Phase 3

10. **Add the partial-stage runner** to the batch loop.
11. **Add the mode toggle** to the config-confirm sheet.
12. **Feed pre-OCR source pages into the context builder** for forward lookahead.

---

## Non-Goals (v1)

* **Cross-chapter context.** Context resets at the chapter boundary. A
  whole-manga memory is out of scope.
* **Two-pass glossary.** No full-chapter pre-analysis before page 1.
* **Backward scanning on cold start.** Pages before the entry page are never
  pre-OCR'd just for context — the user reads forward.
* **Auto re-translate on consistency-gap fill (Option 2).** A cold page stays
  cold unless the user force-retranslates. Documented as future work.
* **Parallel heavy stages.** The single `translatorPermit` serialization is
  preserved to protect Android memory.

---

## Open Questions for Implementation

* Exact chapter-list menu host for the "Prepare chapter" action (long-press
  bottom-sheet vs. per-row overflow). Decide during Phase 2 wiring.
* Whether the summarize cadence M is also user-tunable, or fixed at 3 with K.
* Whether the OCR-only prepare pass should expose a tunable forward-lookahead
  page count, or fix it (e.g. whole-chapter, since it is user-initiated).

---

## Final Acceptance Checklist

Rolling context is done when:

* Rolling context off = identical behavior to today (no prompt change).
* Rolling context on, AI engine = each page's translation sees the last K
  translated pages + a rolling summary; terminology stays consistent.
* Starting mid-chapter degrades gracefully (cold page, then warm window).
* Scrolling backward never leaks future dialogue.
* Context state resets cleanly on chapter switch / reader close / stop-all.

Prepare (full) is done when:

* "Prepare chapter" from the chapter list runs the full batch translate.
* The config-confirm sheet shows and can edit the effective config before
  committing.
* A prepared chapter opens fully translated in the reader.

Prepare (OCR-only) is done when:

* "Prepare chapter" → "OCR only" fills source text for every page without
  inpainting or rendering.
* Read-time translation consumes the pre-OCR source for richer context.
* A long OCR-only pass completes without OOM and with honest progress.
