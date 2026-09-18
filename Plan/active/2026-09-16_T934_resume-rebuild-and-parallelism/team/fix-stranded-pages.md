# T934 lane report — fix-stranded-pages (silent plan skips + generic failure carriers)

Implementer lane. Branch `t934/resume-rebuild-and-parallelism` (worktree
`orchestrate_execution_order_v3`). No git-mutating commands used, no Gradle
runs; verified by careful reading per the standing constraints.

## Root cause (confirmed with file:line evidence)

**Hypothesis (A) confirmed — plus a second sub-case the same line hides.
Hypotheses (B) and (C) are disproven for this run, and (B)'s loud path
already existed.**

The bug lives in `ChapterProfileBatchCoordinator.buildEnvelopeDispatchWorkLocked`
(`pipeline/batch/ChapterProfileBatchCoordinator.kt`, pre-fix line 2831):

```kotlin
if (dispatchBlocks.isEmpty()) return@forEachIndexed
```

A page that is not `pageEnvelopeDone` (translation still PENDING) but whose
blocks are all non-requestable produced an empty dispatch set and was
silently skipped — in EVERY planning round, forever (re-plans included:
`rebuildDispatchWork` re-runs the same builder). Nothing logs, nothing
fails, nothing re-plans it. At FINALIZE the stranded sweep then stamped it
`FAILED` with the generic carrier (`persistEnvelopeStructuralFailure`,
pre-fix line 2096) — "envelope planner rejected the page: stranded page
reconciled at FINALIZE: translation left non-terminal at FINALIZE
(status=PENDING)" — which the sheet's failure-group mapping
(`TranslationBatchProgressTracker.computeSnapshot`, line ~460) rendered from
`errorMessage ?: "Unknown error"`.

Why the blocks were non-requestable — two deterministic classes:

1. **All-blocks-already-translated (hypothesis A as written).** An earlier
   interrupted run wrote block-level translations (or the user edited them)
   while the page-level commit never landed. The requestability skip at
   :2817-2825 ("mirrors the retry controller's requestability rule so page
   completeness stays achievable") removes every block from the dispatch set
   — but nothing ever completed the PAGE, so the page hung at PENDING. The
   comment's promise was defeated by the very next line.
2. **Adopted block-less checkpoint (textless page).** The plan-build resume
   hydration (`adoptCheckpointSnapshot`, :2903 pre-fix) merges the
   checkpointed OCR snapshot but, unlike the single-page pipeline
   (`finalizePostOcrStage`, `PostOcrStageSemantics.kt:6`), applies NO
   textless stamp — an adopted textless page keeps `translationStatus
   PENDING` with zero text blocks. `GlobalEnvelopePlanner` excludes
   zero-block pages by design (planner doc "Zero-block (textless) pages
   carry nothing translatable"). A chapter's page 002 (front
   matter/credits) and page 206 (final notice page) fit this exactly.
   Deterministic across runs because the textless checkpoints persist.

**Evidence fit:** run6 logcat shows the two pages reused checkpoints at
preflight (10:16:58 / 10:31:30), ZERO planner-rejection / oversized /
plan-deferred lines anywhere in the run, no CorpusDrift pause, run COMPLETE
pages=206 stranded=2 (10:58:00), and the two stranded pages at FINALIZE
(10:57:53 / 10:57:56). A planner rejection (B) would have aborted the whole
ENVELOPE_PLAN phase (nothing would have translated); a CorpusDrift (C)
would have PAUSED the run. Neither happened — only the silent per-page skip
is consistent with a run that translated 204/206 and completed.

**(B) oversized pages** — `GlobalEnvelopePlanner.plan` already rejects a
single page over `maxBlocksPerEnvelope` with real numbers
(`GlobalEnvelopePlanner.kt:222-231`, "page X oversized: N blocks > cap"),
and the coordinator's `EnvelopeWorkBuild.PlannerRejected` consumption
(:1764-1789 pre-fix) already persists a specific durable failure per named
page and pauses the phase loudly. No silent drop there; no code change
needed beyond the carrier/reason work shared with the sweep.

**(C) adoption failure** — `adoptCheckpointSnapshot` failures carry typed
`CheckpointAdoptionFailure` reasons (T934 R2a) and abort the build as
`CorpusDrift` → typed PAUSE. Loud, not silent. Note: for the plan-build
adoption branch the only reachable deterministic shape turned out to be the
textless adoption (class 2 above), which previously SUCCEEDED and then fell
into the silent skip — i.e. the real adoption-path bug was (A)'s skip line,
now fixed.

## Fix design

1. **No silent skip** (`buildEnvelopeDispatchWorkLocked`): the empty
   dispatch-set branch now calls `stampUnplannablePageTerminal(pageKey,
   effectivePage)` — explicit terminal routing:
   - **All text blocks already translated or user-edited** → the page's
     translation work IS complete: adopt it `translationStatus = READY`
     (guarded store write under the BATCH Translation-stage lease, M1
     idiom, snapshot taken after the (re)acquire so the lease fence sees
     the live token). READY — not FAILED — because the reader already draws
     these block translations (`shouldShowTranslationOverlay`), a FAILED
     stamp would paint a false red error (`shouldSurfaceError`), and READY
     is exactly the terminal shape the FINALIZE predicate
     (`t924PageTerminalAtFinalize`) and the display/compose tail already
     treat as completed work. This is the minimal display-completion
     routing (see Follow-ups).
   - **No translatable text** → the `finalizePostOcrStage` textless idiom:
     translation SKIPPED, render SKIPPED, and — parity rule — inpaint
     SKIPPED when there are no mask boxes (the scheduler's empty-block
     preservation skip at `OverlapScheduler.kt:526` never inpaints such a
     page). An adopted zero-block page thereby becomes a full
     `isTextlessTerminal` durable terminal (TEXTLESS_COMPLETE display
     state).
   - Best-effort: a rejected stamp leaves the page pending, where the
     FINALIZE sweep's specific reasons still name it — never a silent
     drop. A defensive guard returns without stamping if a requestable
     block somehow reached the branch.
   Writes happen inside the plan-build manifest-coalescing window
   (precedent: `adoptCheckpointSnapshot`'s mergeOcr), so no extra manifest
   rewrites; the mandatory end-of-window flush covers durability.
2. **FINALIZE sweep stays, with specific reasons** (`drainFinalizeAndComplete`
   + new `strandedPageReason`): the reason now names the page's actual work
   state — "no translatable text (status=…, blocks=N)", "all N text blocks
   already translated or user-edited (status=…)", or "translation left
   non-terminal (status=…, textBlocks=N, unfinished=M)".
3. **Accurate carrier**: `persistEnvelopeStructuralFailure` gained a
   `carrier` parameter (default "envelope planner rejected the page" — the
   planner-rejection call site is unchanged). The sweep passes "stranded
   page reconciled at FINALIZE", so a stranded page's durable record no
   longer claims the PLANNER rejected it.
4. **Sheet failure mapping** (`TranslationBatchProgressTracker.computeSnapshot`):
   the failure-group key is now `activeError ?: "Unknown error"`
   (per-stage errors, then errorMessage) instead of `errorMessage` alone.
   The INCLUSION set is unchanged (`isStageFailed || errorMessage != null`)
   so a stale stage error on an eventually-successful page can never become
   a phantom failure row. "Unknown error" now only appears when a failed
   page genuinely carries no reason anywhere.

## Files changed

- `app/src/main/java/eu/kanade/translation/pipeline/batch/ChapterProfileBatchCoordinator.kt`
  — empty-dispatch branch → `stampUnplannablePageTerminal`; new
  `stampUnplannablePageTerminal` + `strandedPageReason`;
  `persistEnvelopeStructuralFailure` carrier param; sweep call updated.
- `app/src/main/java/eu/kanade/translation/pipeline/batch/TranslationBatchProgressTracker.kt`
  — failure-group key → `activeError` fallback chain (inclusion set
  unchanged).
- `app/src/test/java/eu/kanade/translation/pipeline/batch/StrandedPageTerminalRoutingTest.kt`
  (new) — Stage7FinalizeCoordinatorTest harness idioms:
  (a) interrupted-run page (block translated, page PENDING) → run COMPLETES,
  page adopted READY, no durable failure, strandedReconciled=0, provider
  asked only about the healthy page;
  (b) 33-block page vs 32-block cap → PAUSED with "page p2 oversized:
  33 blocks > 32", page durably FAILED_RETRYABLE with that exact reason,
  healthy page untouched, zero provider calls;
  (c) adopted block-less checkpoint page → SKIPPED/SKIPPED/SKIPPED
  textless terminal (isTextlessTerminal), never stranded.
- `app/src/test/java/eu/kanade/translation/pipeline/batch/TranslationBatchProgressTrackerTest.kt`
  — new test: failure groups key on per-stage error and specific
  errorMessage, never the generic "Unknown error".

Not touched: `OverlapScheduler.kt`, `PageStageLeaseTable.kt`,
`ChapterTranslationStore.kt`, `BatchWriteGate.kt` (other agent's in-flight
work; read for context only). `StandardPipelineCoordinatorTest.kt` was
modified by the track-V lane during this session — left exactly as found.

## Verification

Per constraints: no Gradle run. Verification was done by reading:
requestability/deadlock path, store write fences (`pageWriteRejection` —
lease token = live table read, snapshot taken post-acquire passes in both
the re-acquire and fresh-acquire cases), textless terminal shape
(`isTextlessTerminal` field-by-field), FINALIZE predicate, planner budget
math for the oversized test (only the block-count rule can fire: ~400
input / ~660 output estimated tokens vs 4096/3584 budgets), analysis-chunk
behavior for oversized and textless pages, and checkpoint sidecar
availability for the plan-build adoption. No existing test asserts the old
generic reason strings (grepped).

## Intentional deviations & local choices

- Adopted-READY instead of FAILED_RETRYABLE for the all-blocks-translated
  page (the task allowed "at minimum" FAILED): FAILED would paint a false
  reader error over block translations the reader already displays and
  would count as a failure the user cannot act on. READY is the honest
  translation-stage terminal and routes the page into the existing
  display/completion machinery.
- Tracker group-key change keeps the old inclusion set (see fix design) to
  eliminate phantom-failure risk rather than broaden it.

## Follow-ups (documented, not implemented)

1. **Full display-completion routing for adopted-READY pages**: today such
   a page is translation-terminal and the reader draws its overlay, but its
   display bundle/compose commit (pageSnapshotFileName / committed display)
   is only produced by the in-flight compose-tail drain task. The ideal fix
   routes the page through that tail explicitly at adoption time (compose +
   committed promotion) instead of relying on the tail's later sweep.
2. `stampAdoptedRenderTerminal` (:2965) covers inpaint-READY adopted pages;
   an analogous stamp could be extended to inpaint-SKIPPED no-render pages
   once the compose tail defines the target display state for them.
3. If oversized chapters become common, consider excluding the oversized
   page into a parked-FAILED state while planning the remainder (today the
   whole phase pauses loudly by design — page atomicity is a planner
   invariant; changing it is a planner-semantics decision, not a bug fix).
