# HF-03 — Independent Review Report (page-latency program)

Date: 2026-09-21
Reviewer: Independent Reviewer (adversarial verification; durability invariants traced
through the store/scheduler diffs, envelope protocol reviewed line-by-line against the
research protocol, testcase-level XML inspection, evidence artifacts checked)
Branch reviewed: `t936/perf-manual-latency` @ `c7ba84a`, base `main` @ `d8f0e7b`. 5 commits;
30 files, +2,747/−77 (includes 17 committed evidence XMLs for failures + isolation retries).

## Verdict

**PASS WITH NOTES**

The three implemented items (envelope batching, memory-first persistence, admission-time
truth) are correct against every named invariant, the fourth item was cancelled with probe
evidence rather than silently dropped, tests are alive and behavioral, and 2,043 × 2 green
suites are on disk. Notes are minor (direct-to-final-name cleaned-image writes rely on the
pre-existing commit-gate discipline; a couple of doc nits).

---

## 1. Lazy persistence vs durability contracts — PASS

Design: opt-in per store (`enableLazyPersistence()` called only on manager-registered
active stores; probe/test stores keep the synchronous default — blast radius contained).
`updatePageGuarded`'s persist step branches: when lazy-enabled and no durable-failure record
(failure ledger stays synchronous — cancellation/retry semantics can never report a failure
before its ledger entry), `publishLazyLocked` publishes the complete live snapshot to the
StateFlows immediately and queues a detached-copy mutation for the worker.

- **(a) Hydration store-first:** the change touches only the WRITE timing; hydration
  ordering is untouched (active-store registry consulted before disk, pre-existing). Live
  state is authoritative until close: both `close()` and the evict path route through
  `flush()` (drains lazy tasks + lazy mutations + staged + dirty persist) before the scope
  is cancelled, and the pre-existing evict-time persist-join remains. No HF-01-class
  stale-disk window introduced.
- **(b) Generation/defunct fences airtight:** three independent fences — enqueue rejects
  when `defunct || generation != expected`; the worker re-checks `isLazyGenerationCurrent`
  before each task and completes its `CompletableDeferred(false)` on mismatch;
  `flushLazyMutationsLocked` re-checks per mutation, discards on mismatch, and re-queues
  precondition-rejected mutations ONLY while the generation is still current (bounded
  retry; the "failed flush leaves prior manifest authoritative" test pins this). No
  resurrect path.
- **(c) Atomic writes intact:** `persistArtifactMutationLocked` remains the sole durable
  bridge — the change moves its timing, not its atomicity. `CleanedImagePublisher` keeps
  its write→verify→commit-gate discipline: on a mid-write crash the manifest never points
  at the uncommitted file (worst case an inert orphan, handled by the pre-existing orphan
  sweep — unchanged discipline, not weakened).
- **(d) Batch-end barrier real:** `flushStagedMutationsBlocking` now routes through
  `persistenceScheduler.flush()`, which (under the scheduler mutex so nothing outruns it)
  drains queued lazy tasks (joining each task's `CompletableDeferred`), then
  `flushLazyMutationsLocked`, staged mutations, and the dirty persist. The registry's
  flush-all (`flushAllActiveStores` → per-store `flushStagedMutationsBlocking`) is the
  session-end barrier — "complete" is declared only after durability lands. The
  cleaned-image `Deferred` is additionally surfaced as `pendingCleanedPublication` and
  joined by the render phase's final flush barrier.
- **(e) Transient-vs-durable filter preserved:** `publishLazyLocked` reuses
  `shouldPersistUpdate(previous, updated)` plus the not-yet-in-manifest exception for the
  first durable publication; transient RUNNING/PENDING emissions update live StateFlows
  only. Mutations coalesce per pageKey (LinkedHashMap — latest durable state wins).
- **Bitmap lifecycle:** when a lazy cleaned publication is pending, the bitmap reference is
  retained via the deferred task's closure and recycled only AFTER the final flush barrier
  joins the worker (`deferredCleanedBitmap`); the display path renders from live state and
  never awaits the disk write. No recycle-before-display-use window.
- Thread-safety: `pendingLazyMutations`/`pendingLazyTasks` are touched only under the store
  mutex (all accessors are `*Locked`/`withLock`).

## 2. Envelope protocol safety — PASS

`GoogleTranslationEnvelope` + `GoogleTranslator.translatePage`, checked line-by-line
against the research §C.2 protocol:

- **Total validation:** `plan` returns null for an oversized single block (never violates
  the 5,000-code-point cap; greedy chunk fill is bounded by block count — no unbounded
  loop). `parse` requires span count == expected count, ordered ID list equality (reordered
  → null), and uniqueness (duplicates → null, belt-and-braces); malformed JSON → null.
  ANY anomaly → `envelopeValid = false` → **whole-page fallback to the existing serial
  per-block path**, with no envelope retry (the break exits to fallback; HTTP-layer
  `withTranslationRetry` still applies to transport failures exactly as before).
- **Governor:** one `ProviderRequestMetadata` per chunk (token-estimated on the full
  envelope source) → 1 request charged per page-envelope; priority forwarded; per-block
  fallback uses the unchanged `translateText` path (same governor, same spacing — nothing
  relaxed).
- **Challenge bodies:** `isHtmlChallengeBody` (doctype/`<html` prefix or captcha+markup)
  throws `QUOTA_EXHAUSTED`/`PAUSE` **inside** the governor execution — charged, breaker
  opens, and the body is never parsed as a translation (throw precedes parse).
- **No concurrency added:** serial chunk loop; `client=gtx`, `dt=t` only, `sl` pinned,
  `translate.googleapis.com` host — per protocol. Escaping covers `&`/`<`/`>`; Jsoup
  `text()` unescapes on reconstruction.

## 3. Admission-time truth — PASS

- OCR `RUNNING` + READING stage event now emit **before** chapter enumeration, source
  decode, and engine/session work; the engine-rebuild block was explicitly reordered after
  the admission emission, and **engine-init failure produces an OCR FAILED snapshot**
  (verified in the diff) instead of a false pre-stage state.
- Translation `RUNNING` publishes at admission ("single-page translation admission"),
  before sorting/diagnostics/governor/provider preparation — the old post-governor publish
  was replaced. Persist time no longer bills any stage label (root cause addressed by
  Item 3's memory-first publication).
- Label sequence contract unchanged (Reading Text → Cleaning Bubbles → Translating Text →
  Finishing Page), pinned by the updated `ReaderTranslationFeedbackTest` (now 16 tests,
  including the admission-ordering case).
- HF-02 session gating untouched (`TranslationSessionCoordinator.kt` not in diff); HF-01
  keying respected (admission publishes set `sourceFileName = pageKey`).

## 4. Tests alive + evidence — PASS

- 12 new tests across `ChapterTranslationStoreLazyPersistenceTest` (6: live-before-artifact,
  transient live-only, generation discard, defunct discard, **batch barrier waits for the
  lazy worker**, failed-flush-keeps-manifest) and `GoogleTranslatorEnvelopeTest` (6: valid
  reconstruction + single charge, anomaly→fallback without envelope retry, dup/reordered
  fallback, missing/dup/reordered rejection, 5K chunking cap, challenge→breaker). All are
  `runTest` or block bodies — **no T906 expression-body hazard**; all verified present and
  green in the XMLs. Assertions pin behavior (store state, request counts, fallback
  delegation), not implementation. `ReaderTranslationFeedbackTest` grew to 16 with the
  admission-ordering case; no existing assertion weakened.
- **XML: 2,043 tests × both flavors, 295 files each, 0 failures / 0 errors / 0 skipped**
  (fresh, 09-21 17:16 Dev / 17:40 Standard) — matches the claim. Delta vs HF-02's 2,030:
  +12 new, +1 admission test = 2,043 ✓ reconciles.
- **Flake ledger honest:** four failed full-suite attempts documented with signatures, and
  — a first for this campaign — **the actual failure/isolation XMLs are committed** under
  `team/hf-03-evidence/` (17 files). All signatures are the known load-sensitive
  coexistence family; no touched lazy-persistence/envelope/truth suite failed anywhere.
- APK re-inspected (universal): 2,042 entries; best_int8 0; OCR docs 0; manga109 1;
  inference.onnx 2; aot-512 1; aot.onnx 1. Working tree clean; google-services absent.

## 5. Root-cause narrative — PASS

The report's claim (store-mutex durable publication, not prefetch contention) is
falsifiable and was tested before being believed: the ticket's own instrument-then-implement
protocol ran a temporary native queue-wait probe (`queueMs=0`, no AUTO/prefetch activity,
sessions already warm), which **falsified** the prefetch-contention premise; the gaps were
traced to synchronous `updatePageGuarded → persistArtifactMutationLocked` under the store
mutex (~290 ms GC noted as a watch item, no tuning attempted). Item 2 was then formally
cancelled and the ticket text updated to record the cancellation — a transparent,
evidence-backed deviation rather than a silent scope cut. Item 3 removes exactly the traced
mechanism (synchronous publication) from the display path.

## Notes (non-blocking)

1. **Cleaned-image lazy writes go direct-to-final-name** (write → verify length → commit
   gate). This is the publisher's pre-existing discipline and the commit gate keeps the
   manifest authoritative, but a crash mid-encode can leave an orphan partial file for the
   orphan sweep — unchanged from main, worth remembering if the sweep is ever touched.
2. **Lazy mode is registration-gated:** any future store creation path that bypasses the
   three `enableLazyPersistence()` call sites silently keeps synchronous persistence (safe
   default, but a new call site is a behavior decision — the ticket's hydration-first
   rationale should be re-checked there).
3. The report's per-class focused counts (6/6/16) match the committed XMLs; the "6 tests"
   for `CleanedImagePublisherTest` and the Defunct/Persistence suites were not separately
   re-counted (covered by the full-suite green).
4. Item 2's cancellation leaves the ticket's "Native priority" test item unimplemented —
   correctly recorded in both ticket and report; no orphan test for it exists.

## Conclusion

The highest-risk delta of the campaign holds up: memory-first publication preserves every
T930/T934 invariant with three independent generation fences and a real session-end barrier,
the envelope protocol fails closed at every anomaly class with the breaker armed, and truth
is now emitted at admission. Merge-ready from this reviewer's standpoint, pending the
orchestrator's device verification of the latency targets (tap→Reading Text <300 ms, warm
manual page <5 s) which no local test can substitute for.

---

# DELTA RE-REVIEW — HF-03 hotfix: lazy bitmap release + fallback truth (2026-09-21)

Post-merge device regression: the AUTO lane recycled the cleaned bitmap after lazy enqueue
but before the flush worker compressed it (`IllegalStateException: Can't compress a recycled
bitmap`, CleanedImagePublisher phase=write), poisoning page truth to inpaint/render FAILED
and dead-ending the cleaned swap. Delta under review: `68034b4` (fix) + `4c8f732`
(report/evidence). Scope: 5 production files + 2 test files + report/evidence — **exactly
the two fix surfaces; no scope creep** (HF-01 keying untouched; HF-02 gating untouched;
`TranslationSessionCoordinator` not in delta).

## 1. Recycle-vs-flush ordering — PASS (full site census)

Every cleaned/source bitmap recycle site enumerated and classified:

- **`PreparedPageBoundary.kt:74` (the AUTO bug site) — FIXED:** `publishPreparedPageFromOcr`
  now calls `store.flush()` when the store is lazy-enabled BEFORE
  `ocrResult.cleanedBitmap?.recycle()`. `flush()` is the scheduler-serialized drain that
  joins the lazy JPEG task (worker's `compress` completes inside the task), so the recycle
  is provably post-compression. Probe stores keep the historical synchronous path (write
  already completed before this boundary).
- **Manual lane (`SinglePageHttpRenderPhase`):** L643 recycle is guarded by
  `pendingCleanedPublication == null` (sync-published ⇒ no worker owns it); L725 is a
  retry-render FAILURE finally (nothing enqueued); L743/L847/L855 recycle only after the
  final flush barrier joins `deferredCleanedBitmap` (the HF-03 original retention). ✓
- **ONNX phase:** L642 resume-render failure finally (publication never succeeded — owning);
  L727 `persisted == null` (publish rejected BEFORE enqueue — owning); L819 recycles the
  pre-scale original only when a scaled copy was created (the kept `s` moves forward);
  L832 `retryBitmap` is the freshly DECODED source of `retryInpaintDownscaled`, recycled in
  its finally — no lazy enqueue exists in that function (publication happens later on the
  kept `scaledCleaned`, which is never recycled there).
- **`finalizePostOcrStage` (PostOcrStageSemantics:23):** recycles `page.cleanedBitmap` only
  when `inpaintMaskBoxes.isEmpty()` and inpaint never ran — structurally, no lazy cleaned
  task can exist for such a page; guarded by `runCatching` besides.
- **Sync path (`CleanedPublication.kt:250`):** recycles after the synchronous write on the
  non-lazy branch.
- **Batch lane:** `CleanedPublication` is constructed only in `TranslationPipeline` (the
  single-page path); batch bitmap ownership is `HeldBitmapRegistry`/`BatchRenderJoin`
  internal and **never enqueues lazy tasks** — no enqueue-then-recycle possible.
- **Worker failure paths / bounded memory:** the scheduler dequeues the task before running
  it and completes its `CompletableDeferred` on EVERY path (success, thrown failure,
  cancellation, generation-mismatch) — the closure (and its bitmap reference) becomes
  unreachable after the task leaves the queue, so a failed flush cannot retain the bitmap
  indefinitely; drain paths (schedule/close/evict barrier) bound the queue lifetime.

## 2. Fallback truth correctness — PASS

Publication failure now routes to `markOriginalImageFallback`: `cleanedImageName = null`,
`originalImageFallback = true`, `inpaintStatus = renderStatus = READY`, `errorMessage =
null` — a display-artifact (storage) failure no longer poisons pipeline truth.

- **No misreporting of genuine failures:** the helper is reachable ONLY from the lazy
  cleaned-write failure branch. A genuine inpaint exception takes the unchanged inpaint
  catch (`FAILED` + `recordAttemptFailure`); a genuine render failure takes the unchanged
  render catch — both still project `FAILED_NO_RESULT` via `isStageFailed`. Separation is
  structural (storage failure vs pipeline failure are different code paths).
- **Reader renders original + overlay (no dead swap):** pinned end-to-end by the new
  `PageDisplayProjectionTest` case — fallback page projects `DISPLAY_READY` /
  `displayReady = true` via `isTranslationDisplayShapeReady` accepting
  `originalImageFallback` in place of cleaned-readiness, `displayImageName == null` (no
  cleaned swap attempted), `shouldSurfaceError == false`, and
  `selectReaderTranslationOverlayBinding(true, fallback).blocks == fallback.blocks` (the
  actual reader binding draws the translated blocks over the original image).
- **State hygiene:** all four success paths (lazy + sync, initial + retry) now clear
  `originalImageFallback = false`, and both reset points (`prepareForcedRetry`, the
  inpaint-artifact reset) clear it — a later successful publication or retry cannot inherit
  a stale fallback. `differsFromCommitted` includes the flag, so committed-state overlay
  refresh triggers on the transition.
- The dropped `recordAttemptFailure()` on this branch is semantically consistent: a storage
  write failure is not a pipeline attempt; the KDoc documents the retry intent.

## 3. Tests — PASS

- `PreparedPageRuntimeBoundaryTest.lazy auto boundary flushes before eager cleaned bitmap
  recycle` (now 9/9 executed in XML, +1): **actually constructs the race** — lazy-enabled
  store, a lazy task recording "flush", a recording mock bitmap whose `recycle()` logs
  "recycle", then asserts `events == ["flush", "recycle"]` after the boundary runs. Under
  the old code the order inverts; the assertion pins ordering, not implementation.
  `runTest` body — alive (no T906).
- `PageDisplayProjectionTest.cleaned publication failure keeps translated overlay on the
  original image` (now 9/9, +1): drives the real `markOriginalImageFallback` helper through
  the real projection and the real reader overlay binding — behavioral, statement body,
  alive.
- No existing assertion weakened (both files only gained tests).

## 4. Scope — PASS

Delta files: `CleanedPublication`, `PreparedPageBoundary`, `PageTranslation`,
`PageTranslationState`, `PageDisplayProjection` + the two test files + report/evidence.
HF-01 keying (`sourceFileName = pageKey`) untouched; HF-02 session gating untouched; no
durability-contract change beyond the two fix surfaces.

## 5. Gates — PASS WITH NOTE

- **Standard full: 2,045 tests, 295 files, 0 failures** (fresh, 09-21 21:41) ✓.
- **Focused suites:** `PreparedPageRuntimeBoundaryTest` 9/9 and `PageDisplayProjectionTest`
  9/9 executed and green in XML ✓.
- **Tracked residual:** the last full Dev run on disk shows the known
  `StandardLaneMultiPageCompletionTest` load flake (1 failure), with
  `hf03-hotfix-standardlane-isolation-1/2/3.xml` committed as the 3/3 isolation evidence —
  exactly as the gate brief describes. Note: no final green full **Dev** run is on disk or
  claimed; the residual is the established family, but a clean Dev full run should be
  captured when the queue is quiet. Test arithmetic: 2,043 + 2 new = 2,045 ✓.

## FINAL HF-03 VERDICT (including hotfix delta): **PASS WITH NOTES**

The original review's PASS WITH NOTES stands; the hotfix delta remediates the bitmap race
with a real ordering test, fixes the truth poisoning with a correctly-scoped fallback that
cannot mask genuine failures, and stays inside its two surfaces. Carried notes: (1) capture
a clean full Dev run to replace the residual flake state on disk; (2) the direct-to-final-name
cleaned-write/orphan-sweep and registration-gated lazy-mode observations from the original
review remain valid watch items; (3) my original HF-03 review file went missing from the
worktree between turns (untracked file lost during the hotfix evidence commit) — this
document restores it with the delta appended; recommend committing review reports alongside
implementation reports so they survive worktree cleanups.
