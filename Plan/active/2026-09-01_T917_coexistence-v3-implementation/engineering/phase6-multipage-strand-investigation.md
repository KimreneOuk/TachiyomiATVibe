# Phase 6 investigation — multi-page standard-lane batch strands every page after the first

Task: T917 coexistence-v3. Investigation only; no code changed.
Scope: reproduced on-device defect — 4-page RESUME batch on the STANDARD ML-Kit lane
translated only page 001; 002–004 skipped translation AND render (0 ms, success=true)
and were stranded by the reconciler ("expected page has no readable terminal output");
outcome=ERROR.

---

## Defect signature

On the standard (non-AI) batch lane, every chapter page AFTER the first page whose
translation needs ordered work is permanently skipped for translation and render,
while its OCR and inpaint still run. The skip is reported as SUCCESS to the
coordinator, so pass 1 "completes", no second wave exists, and the reconciler then
strands those pages → chapter outcome=ERROR.

De facto behavior: **one translated page per batch run** for any multi-page chapter
on the standard lane whenever no earlier page is already terminally translated
(fresh chapter, or a resume whose earlier pages have no prior stage results —
exactly the reproduced scenario).

## On-device evidence (logcat, TachiyomiAT.Batch)

- `stage_decision` events exist ONLY for `stage=ocr` (4x, execute/reference_ready).
  There is no translation-stage decision event anywhere in the batch path — the only
  `stageDecision` emitters in the batch pipeline are
  `SequentialBatchCoordinator.runOcr` (SequentialBatchCoordinator.kt:91–108).
  The translation skip is invisible to diagnostics.
- `stage_timing`: 001 ocr 25237 / translation 36066 / inpaint 8932 / render 7077.
  002, 003, 004: ocr 7377/8395/5494, **translation 0** (items=6/9/5), inpaint
  4472/7552/8688, **render 0**. `success=true` on the 0 ms events because
  `BatchTranslationDiagnostics.timing` defaults `success = true`
  (BatchTranslationDiagnostics.kt:36) and the coordinator emits timing in `finally`
  blocks regardless of whether the stage did work.
- `event=memory stage="chunk_ocr_barrier" activePages=1` → 1-page chunks, matching
  the standard lane (LOCAL_COMPUTE → `fallbackChunkPageCount = 1`,
  SequentialBatchCoordinator.kt:50).
- Then "batch first pass complete", three "stranded page" warnings (002/003/004),
  "batch complete outcome=ERROR".
- Lane: translator=MLKitTranslator (STANDARD, isAi=false), engine=RoiPageRecognitionEngine.
- Context: RESUME of a previously paused batch; fresh store; pages pre-registered;
  no prior stage results existed.

## Code-level mechanism

### 1. The plan is built once and marks 002+ as WAIT_FOR_DEPENDENCY/PRIOR_PAGE_INCOMPLETE

- `BatchResumePlanner.batchPagePlans` is an eager `val` built at construction
  (BatchResumePlanner.kt:53, `buildBatchPagePlans()` 91–108) from store snapshots at
  batch start. It is **immutable for the whole run**.
- `PageWorkPlanner.planChapter` (PageWorkPlanner.kt:114–159): the first page whose
  translation `translationNeedsOrderedWork()` (161–168 — includes WAIT_FOR_DEPENDENCY)
  sets `translationBlocked`; every later page's TRANSLATION stage is overwritten to
  `WAIT_FOR_DEPENDENCY + PRIOR_PAGE_INCOMPLETE` (132–136) and its LAYOUT to
  `WAIT_FOR_DEPENDENCY + DEPENDENCY_INCOMPLETE` (137–148).
- Note: for a fresh/PENDING page, the within-page dependency cascade already yields
  TRANSLATION = `WAIT_FOR_DEPENDENCY + DEPENDENCY_INCOMPLETE` (a stage is blocked
  unless its dependencies are REUSE/TERMINAL_COMPLETE — decideStage 319–331,
  dependencyOrRun 333–346, dependencies() 348–355). So on this run page 001 was ALSO
  planned WAIT (reason DEPENDENCY_INCOMPLETE) — which is why 001 translated: see below.
  Pages 002–004 got the chapter-level overwrite to PRIOR_PAGE_INCOMPLETE, which is the
  fatal difference.

### 2. translate() turns the static plan reason into a permanent skip (standard lane)

`BatchLaneWorkers.translatorWorker.translate` (BatchLaneWorkers.kt:1168):

- 1184–1186: `plannedTranslation` read from the immutable plan snapshot.
- 1189–1190: `priorPageBlocksStandardTranslation = !isAi && plannedTranslation.reason == PRIOR_PAGE_INCOMPLETE`.
- 1201–1213: `shouldSkipTranslation` includes
  `plannedTranslation?.decision == WAIT_FOR_DEPENDENCY && (priorPageBlocksStandardTranslation || !dependencyReadyAfterNative)`.
- For 002–004: decision = WAIT_FOR_DEPENDENCY, reason = PRIOR_PAGE_INCOMPLETE,
  isAi = false → **the `priorPageBlocksStandardTranslation` disjunct is unconditionally
  true**, independent of what actually happened to page 001 → early return at
  1214–1248 without invoking the provider. Page 001 translated because its plan reason
  was DEPENDENCY_INCOMPLETE (within-page), so `priorPageBlocksStandardTranslation`
  was false and `dependencyReadyAfterNative` (ocrStatus READY after native) was true.
- The premise ("prior page incomplete") is only true at plan time; once 001 commits,
  it is false — but the skip never re-evaluates. There is no re-plan.

### 3. The skip is laundered into SUCCESS

- `translateOutcome` (1162–1166): `standardOutcome` is only set by real work; after the
  early return it is null → line 1165 returns the silent default
  `ChunkCompletionOutcome.Completed(setOf(ref.pageKey))`.
- Coordinator (standard lane, `!remote`, chunk of 1): inline loop calls
  `translateOutcome` (SequentialBatchCoordinator.kt:368–420) → outcome Completed →
  `settleTranslationBranches` completes the translation gate, render join runs, and
  `completedPassPageKeys += {page}` (line 538). Pass 1 finishes COMPLETED; the shell
  logs "first pass complete" and goes straight to reconciliation
  (BatchChapterTranslator.kt:546, 635–643). **There is exactly one pass.**

### 4. Why render was also 0 ms

- `BatchRenderJoin.awaitAndRender` → `tryRender` (BatchRenderJoin.kt:101–270):
  translationStatus is PENDING (never ran) → the gate at 128–136
  (`status != READY && status != PARTIAL`) returns silently — no render, no failure
  mark. The coordinator's render `finally` then emits timing ≈0 ms, success=true.
- OCR and inpaint still ran because the resume gate treats OCR
  `WAIT_FOR_DEPENDENCY` as "needs work" (`resumeGate`, BatchResumePlanner.kt:220–251:
  `ocrNeedsWork = RUN || WAIT_FOR_DEPENDENCY || FAILED` → FULL), and the coordinator
  runs inpaint for every chunk page holding a ref regardless of plan
  (SequentialBatchCoordinator.kt:423–456).

### 5. The reconciler's exact message

Pages 002–004 have ocr=READY, inpaint=READY, translation=PENDING, render=PENDING,
runGeneration == active generation (they were written this run), so they fall to the
final `else` branch: `"Translation incomplete — expected page has no readable terminal output"`
(BatchProgressReconciler.kt:99) → failedCount>0 → chapterStatus=ERROR. Matches the
on-device trace byte-for-byte.

## Designed-vs-actual unblock analysis

- **No unblock mechanism exists for the standard lane.** Searched the pass coordinator,
  lane workers, and shell: the only same-pass re-admission is the T917 D3
  defer-and-rescan (`deferredPages`), which handles lease-denial (another ORIGIN owning
  the page), not plan-WAIT pages. The context frontier (`BatchContextFrontier`) only
  feeds AI rolling context / gap blocking; `recordContextPage` is skipped for the
  standard lane. `PageWorkPlanner`/`BatchResumePlanner` are never re-consulted or
  refreshed mid-run.
- Therefore: **one-page-per-pass IS the de facto contract** for every multi-page
  chapter on the standard lane, except chapters where every predecessor of each
  needs-work page is already terminally translated (then the first needs-work page is
  the last needs-work page — e.g. D10's rerun fixture).
- **The AI lane does NOT have this defect.** `priorPageBlocksStandardTranslation`
  requires `!isAi` (BatchLaneWorkers.kt:1189), and WAIT pages on the AI lane are still
  admitted into the `StreamingChunkPlanner` (1177–1309), which batches multiple pages
  per provider envelope (greedy token budget, pages atomic — StreamingChunkPlanner.kt);
  `translateChunkAi` guards context gaps via the live frontier, not the static plan.
  (Unverified on-device in this investigation; code path analysis only.)

## Why tests missed it

The T917 harness runs the REAL production graph on the STANDARD/ML-Kit lane
(`harnessPreferences`: `translation_engine_category` = STANDARD,
`translation_standard_engine` = MLKIT — TranslationCoexistenceHarness.kt:777–793), so
the lane is faithful. The masking is in the FIXTURES:

1. **Every "batch completes end-to-end" test uses a ONE-page chapter.**
   `NormalMangaIsolationTest.kt:44–47` says it outright: *"the production ordered-wait
   planner (WAIT_FOR_DEPENDENCY/PRIOR_PAGE_INCOMPLETE for pages behind a needs-work
   predecessor) makes a one-page chapter the deterministic 'batch completes end-to-end'
   fixture."* Same pattern in D2 test 1, D6, D9 (`launchBatch(listOf("p0"))`),
   D10 run 1. `Phase0BatchTranslationCharacterizationTest` never drives the batch
   pipeline at all (pure ordering/gate/store-emission checks).
2. **The two-page tests outsource page 2 to the MANUAL path.** D2 test 2 and D3
   (`launchBatch(listOf("p0","p1"))`) park p1 on a reader tap; the batch defers it,
   the manual path completes it, and the rescan observes it already terminal — the
   batch itself never has to translate two pages.
3. **The multi-page batch test has an already-terminal first page.** D10's rerun
   (D10PartialDownloadAdmissionTest.kt:493–540) reopens with p0 terminal (SKIP_ALL) so
   p1 is the FIRST needs-work page; its plan stays within-page
   WAIT/DEPENDENCY_INCOMPLETE → it translates. The overwrite never fires.
4. **D11 deliberately omits p1 from the store** (D11PermitFreeCommitTest.kt:59–62),
   documenting the planner's WAIT projection as the reason.
5. `PageWorkPlannerTest.kt` pins the WAIT cascade at the pure-planner level as
   intended (`source change invalidates detection and inpaint with downstream waits`
   :98–117; `failed page blocks later translation…` :176–190) — correct for the
   planner, but nothing drives a standard-lane batch where page 1 needs work AND pages
   2+ are batch-owned.

The test that WOULD have caught this: a harness test that launches a batch of ≥2
pre-registered PENDING pages and asserts `strandedPages shouldBe emptyMap()` +
`chapterStatus TRANSLATED` + `fakeTransport.callsFor(p1) == 1`. No such test exists;
the closest (NormalMangaIsolation) is the one that documents why the fixture was
shrunk to one page.

## Recommended fix seam + regression test plan (do NOT implement yet)

### Fix seam (minimal): make the standard-lane skip condition dynamic against live predecessor state

Location: `BatchLaneWorkers.kt` `translate()`, lines 1189–1213.

Shape: replace the static plan-reason test
`priorPageBlocksStandardTranslation = !isAi && reason == PRIOR_PAGE_INCOMPLETE`
with a live evaluation: on the standard lane, a plan entry
WAIT_FOR_DEPENDENCY/PRIOR_PAGE_INCOMPLETE must skip ONLY if the natural-order
predecessor page (predecessor by `resolvedNaturalPageIndexes` / `orderedStreams`, both
already injected into this class) has not reached a terminal translation outcome in the
store at translate() time (terminal = translationStatus READY/SKIPPED/TEXTLESS, or a
durable terminal/failed state per the existing fence semantics).

Why this seam:

- The standard lane is strictly ordered end-to-end (1-page chunks; inline translation
  lane; next chunk not admitted until the current chunk renders —
  SequentialBatchCoordinator.kt:467–543), so by the time page N is offered, page N−1 is
  already terminal. The live check therefore unblocks 002–004 in-pass with zero
  scheduling changes and bounded cost (one store read per page).
- It preserves the plan snapshot's other consumers (resumeGate, plannedRenderNeedsWork,
  translationFailureFence, seed) — no re-plan, no mutable shared plan, no second wave.
- Honest outcomes are preserved: pages whose predecessor terminally FAILED still skip
  translation (and the reconciler still reports them), satisfying "no fake success";
  no native cancel/kill involved; no new memory retained.
- The AI lane is untouched (`!isAi` branch unchanged).

Secondary hardening (optional, same file): when `translate()` early-returns via
`shouldSkipTranslation` for a page that is NOT durably terminal,
`translateOutcome` (1162–1166) should not return the silent
`Completed(setOf(ref.pageKey))` — introduce/return an explicit outcome so
`completedPassPageKeys` cannot count a skipped page as completed. Also emit a
`stage_decision` diagnostics event for the translation skip (reason code) — today the
only batch-path `stageDecision` emitter is OCR, which is why this defect took an
on-device trace to diagnose.

### Regression test (RED first)

Add to the coexistence suite (satisfies: no Robolectric, no sleeps/polling —
barrier/`withTimeout` event-driven; fakes only at sanctioned seams):

- `TranslationCoexistenceHarness.create(listOf("p0","p1","p2"))` (default
  pre-registered PENDING store — exactly the on-device resume context),
  `installGraphicsShims()`, `stubChapterPages(...)`, `launchBatch()`.
- RED assertions (fail today): `reconciliation.strandedPages` contains p1/p2;
  `chapterStatus` ERROR; `fakeTransport.callsFor("p1") == 0`.
- GREEN contract: `strandedPages shouldBe emptyMap()`, chapterStatus TRANSLATED,
  `callsFor` each page == 1 (exactly-once, no double pay), all pages renderStatus READY,
  natural-order provider call order p0→p1→p2.
- Keep a negative control: a chapter whose p0 is durably terminal-failed must still
  strand p1/p2 (ordered-context honesty is preserved).
- After GREEN, revisit `NormalMangaIsolationTest`'s one-page fixture comment — the
  "batch completes end-to-end" fixture can now be multi-page.

## Open questions

1. `plannedRenderNeedsWork` (BatchResumePlanner.kt:187–215) also returns false for
   WAIT/PRIOR_PAGE_INCOMPLETE pages. Harmless in this defect (render gate was
   translationStatus), but if the fix keeps static plans, confirm no other consumer
   depends on the stale WAIT reason for admission decisions.
2. Should the AI lane get an equivalent dynamic guard for its `!dependencyReadyAfterNative`
   disjunct, or is planner admission sufficient? (Analysis says sufficient; not
   reproduced.)
3. Whether the silent-`Completed` outcome type deserves its own variant (e.g. `Skipped`)
   in `ChunkCompletionOutcome` — touches coordinator pass-completion semantics; needs a
   small design note before implementation.
4. Confirm on-device that a fresh (non-resume) multi-page standard-lane batch exhibits
   the identical trace (expected: yes — plan inputs are identical), and re-run the same
   chapter after the fix to verify pages 002–004 complete.
