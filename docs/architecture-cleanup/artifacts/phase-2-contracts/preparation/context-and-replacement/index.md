---
title: "P2.3/P2.4 preparation — BatchContextFrontier relocation + suspending chapter replacement"
kind: review
comments: none
---

# P2.3/P2.4 Preparation — Context-Frontier Move and Suspending Chapter Replacement

**Status:** read-only preparation per [preparation](..). Baseline **`d8252cbe`**. **Git facts (read-only):** HEAD *is* `d8252cbe`; at the start of this audit the tracked tree was clean (one untracked non-code report file), and concurrent P2.2 work has since landed edits in the shared workspace — so mappings were verified against the source as found and the reviewer independently re-checked them. **This audit ran no tests and makes no pass/failure claims.** Executed evidence is the recorded phase ledgers: the Phase0 fresh-670 and Phase1 fresh-329-suite sets under the epic scratch — which include prior-slice runs of `ProfileEnvelopeDispatchTest`, `StandardPipelineCoordinatorTest`, `TranslationManagerDeleteResetOrderingTest`, and `ChapterQueueConflictDetectionTest` — plus their preserved XML; **the actual executed XML remains authoritative.** (v1's "no suite has any recorded run on this host" claim, based only on the 30 stale OCR-side XMLs in `app/build/test-results`, is **withdrawn** — those directories were not inventoried against the phase ledgers.)

## A. P2.3 — `BatchContextFrontier` relocation `pipeline/batch` → `context` (name and body preserved)

### A.1 Complete reference inventory (verified; zero reflective/string coupling)

`rg "BatchContextFrontier" app/src --glob "*.kt"` hits exactly 7 files; **0 non-Kotlin references**; **0 string literals, `Class.forName`, or reflection targets** naming the type (the repo's 41 reflection-using files were checked — none touch it).

| File | Lines | Kind | Relocation edit |
| --- | --- | --- | --- |
| `pipeline/batch/BatchContextFrontier.kt` | 1 (pkg), 19 (class) | **definition**; imports only `engines.translator.contextual.TranslationContextChunkPlanner` + `model.*` — **no `pipeline.batch` imports**, so the move creates no reverse dependency | `package` line → `eu.kanade.translation.context`; **body unchanged** (110 lines incl. seed/record/gap/failure/snapshot semantics — the file already depends "downward" on the contextual planner, so the move removes a `context → pipeline.batch` import direction, exactly the ticket's goal) |
| `pipeline/batch/BatchChapterTranslator.kt` | 409 | same-package **usage** (no import line exists) | **ADD** `import eu.kanade.translation.context.BatchContextFrontier` |
| `pipeline/batch/BatchLaneWorkers.kt` | 118 | same-package usage (ctor param) | **ADD** same import |
| `pipeline/batch/envelope/ProfileEnvelopeExecutor.kt` | 53, 328, 571, 905, 1093, 1256-1257 | import + 4 signatures + `buildFrontier` | change 1 import line |
| `pipeline/batch/recovery/BatchResumePlanner.kt` | 16, 50 | import + data-class field `contextFrontier` | change 1 import line |
| `context/ChapterContextService.kt` | 10, 143 | import + usage | **REMOVE the import line** (after the move the type is same-package; keeping it would be a redundant import) |
| `pipeline/batch/BatchContextFrontierTest.kt` (test tree) | 10, 14, 30, 43, 61, 83 | same-package test (5 `@Test`) | **file stays at its present path** (per ticket); **ADD** the new import |

**Compile-set is exactly these 7 logical files** — edit-count summary: **1** definition `package`-line edit; **2** existing imports replaced (`ProfileEnvelopeExecutor`, `BatchResumePlanner`); **1** import removed (`ChapterContextService` — same-package after the move); **3** new imports added (the two same-package batch callers `BatchChapterTranslator`, `BatchLaneWorkers`, and `BatchContextFrontierTest`). No signature, body, ordering, or persistence edit anywhere.

### A.2 Target package

`eu.kanade.translation.context` exists (prod: `ChapterContextService.kt`, `SeriesProfileRegistry.kt`). `BatchContextFrontier.kt` joins as its third file.

### A.3 Minimal selector set (frontier + context + resume; no callback rewrite; measured counts)

```
.\gradlew :app:testDevDebugUnitTest --tests "eu.kanade.translation.pipeline.batch.BatchContextFrontierTest" --tests "eu.kanade.translation.pipeline.batch.ProfileEnvelopeDispatchTest" --tests "eu.kanade.translation.pipeline.batch.StandardPipelineCoordinatorTest" --tests "eu.kanade.translation.context.*" --tests "eu.kanade.translation.workflow.TranslationManagerDeleteResetOrderingTest" --tests "eu.kanade.translation.workflow.ChapterQueueConflictDetectionTest"
```

| Suite | Tests | Covers |
| --- | --- | --- |
| `BatchContextFrontierTest` | 5 | frontier unit: seed order, gap fencing, PARTIAL non-advance, textless, blocksLaterAi |
| `ProfileEnvelopeDispatchTest` | 20 | frontier through the real envelope executor (`buildFrontier` path) |
| `StandardPipelineCoordinatorTest` | 12 | resume: interrupted standard-run continuation (frontier-adjacent); count includes the Phase-1 X6 addition — the executed XML ledger remains authoritative |
| `context.*` (RollingHistoryContextContract 16 + ChapterContextLegacySnapshotCompatibility 2) | 18 | context package neighbors that must keep compiling/behaving |
| `TranslationManagerDeleteResetOrderingTest` | 3 | ordering-contract suite that must stay green alongside (import-adjacent) |
| `ChapterQueueConflictDetectionTest` | 7 | replacement conflict preflight (pairs with B) |
| **Total** | **65 / 7 files** |  |

Explicitly excluded: `TranslationContextChunkPlannerTest` (18) — the planner is untouched by the move and is only a downstream callee of `updateRollingContext`; `BatchResumePlanner` has **no direct test** (its behavior is covered transitively by the coordinator test). Re-run both flavors fresh per ticket.

## B. P2.4 — `cancelRunningChapterForReplace` suspending conversion

### B.1 Current shape (verified)

`workflow/TranslationManager.kt:1065-1079` — non-suspend; exactly **two `runBlocking` wrappers** (1070-1072, 1074-1076); guard: returns silently if `chapterId` not in `queueState`; ordering: `scheduler.cancelPageTranslations(chapterId)` (suspend, `TranslationScheduler.kt:657`) → `activeStores.get(chapterId)?.let { store.clearTransientQueuePages("Replaced by another chapter's batch") }` (suspend, `ChapterTranslationStore.kt:3600`) → `removeFromTranslationQueue(chapter)` (**non-suspend**, `TranslationManager.kt:2093` — pause-if-running, `translator.removeFromQueue`, stop/start). The committed/terminal-artifact preservation lives inside `clearTransientQueuePages` (store-level contract; additionally pinned store-side by `ChapterTranslationStoreDefunctTest` 2 tests).

### B.2 Callers, mocks, dispatcher (exact compile-set)

- **Single production caller:** `MangaScreenModel.confirmReplaceRunningChapter` (`MangaScreenModel.kt:1306-1314`) — already wraps the call in `screenModelScope.launchNonCancellable { cancelRunningChapterForReplace(...); translateChapter(...) }`. **`launchNonCancellable` = `launchIO { withContext(NonCancellable, block) }`** (`core/common/src/main/kotlin/tachiyomi/core/common/util/lang/CoroutinesExtensions.kt:52-53`) — so the call site already runs on **`Dispatchers.IO`**, non-cancellably, and needs **no edit** when the function becomes `suspend` (a suspend call is legal exactly where the `runBlocking` calls are today).
- **No other production callers.** `ChapterQueuePreflight.kt:20` and `MangaScreenModel.kt:1817` are comments only; `ChapterQueueConflictDetectionTest.kt:82` is a comment only.
- **No test stubs or fakes implement/verify `cancelRunningChapterForReplace`** anywhere in the test tree — the three screen-model fixtures use `mockk<TranslationManager>(relaxed = true)`, which absorbs the added `suspend` without edits.
- **Synchronous callers preventing simple migration: none.** The conversion compile-set is `TranslationManager.kt` (drop 2 `runBlocking`, add `suspend`) — one file, one function.

### B.3 Strongest existing test anchor (no new harness)

**`workflow/TranslationManagerDeleteResetOrderingTest`** — its `newManager` fixture (`TranslationManager.createForTesting` + field injection) already provides: scheduler mock with **`coEvery { scheduler.cancelPageTranslations(any()) }` event-logged** (lines 94-97), `activeStores` registry with the store under test (134/148), a queued `Translation` (171), and the `removeFromQueue` event stub (106). `runBlocking` test bodies with exact `events == listOf(...)` assertions are its established idiom.

**Recommended meaningful test change (one new method in this existing file, zero new infrastructure):**

1. Add one stub next to the others: `coEvery { store.clearTransientQueuePages(any()) } answers { log("clearTransientQueuePages") }`.
2. New test `cancelRunningChapterForReplace cancels, clears transient pages, then removes the queue entry in order`, seeded with the chapter queued (as line 171) and the store registered; assert the exact event list `["cancelPageTranslations", "clearTransientQueuePages", "removeFromQueue"]`.
3. **Gate-based await proof (the actual P2.4 regression point — externally observed):** launch `cancelRunningChapterForReplace` as a **controlled child** coroutine (`launch`/`async` under the test's `runBlocking`). The `cancelPageTranslations` stub suspends on `gate1: CompletableDeferred`; while `gate1` is held, assert **from the test body** that neither `"clearTransientQueuePages"` nor `"removeFromQueue"` has appeared in `events`. Release `gate1`; the `clearTransientQueuePages` stub then suspends on `gate2`; while `gate2` is held, assert `"removeFromQueue"` has **not** begun. Release `gate2`, `join()` the child, then assert the exact event list `["cancelPageTranslations", "clearTransientQueuePages", "removeFromQueue"]`. The external assertions between gates are the point: they demonstrate the function actually **suspends at each await** (a `runBlocking` parking or a fire-and-forget launch-ahead would let the later steps begin while a gate is still held); an in-stub pre-clear check alone cannot demonstrate that. No new harness, no `Thread.sleep`, no timeout additions beyond the existing fixture conventions.
4. Also assert the guard path once (chapter absent from `queueState` ⇒ no events) — one extra `events.isEmpty()` line, same method or a sibling.

Adjacent (do **not** extend): `EvictionOffMainTest`/`ResetRetiresActiveRunTest` exercise `cancelPageTranslationsFn` through the *session-coordinator* seam (different surface); `ChapterQueueConflictDetectionTest` pins that the conflict path routes through this flow.

### B.4 Boundaries (per instruction)

The caller is inside `launchNonCancellable` on IO; this audit records that wiring as a source fact and, per ticket, **does not infer** a prior main-thread ANR, closure semantics beyond the `NonCancellable` context shown, or any latency claim. The two `runBlocking` wrappers are the only blocking bridges in this function; no other call path wraps it.

## C. Recommended implementation order (compact manifest)

| Step | File(s) | Action | Risk |
| --- | --- | --- | --- |
| 1 | `BatchContextFrontier.kt` | move to `context/`, package line only | import-only fidelity, Gate-checked |
| 2 | 6 reference files (§A.1) | import edits/additions | mechanical |
| 3 | `BatchContextFrontierTest.kt` | +1 import, path unchanged | mechanical |
| 4 | selector §A.3 (65 tests) + both flavors compile | validate | — |
| 5 | `TranslationManager.kt:1065` | `suspend`, drop both `runBlocking`, direct awaits | single-caller verified |
| 6 | `TranslationManagerDeleteResetOrderingTest` | +1 ordering test with await-gate stubs (§B.3) + guard assertion | existing fixture |
| 7 | selector §A.3 + `workflow/` focused set; fresh compile both flavors; preserve prior XML | validate | — |

All of the above is implementation-side (owned by the P2.2/P2.3/P2.4 implementer); this artifact is the read-only evidence base for it.
