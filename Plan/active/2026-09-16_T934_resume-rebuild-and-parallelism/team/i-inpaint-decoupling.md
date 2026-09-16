# Lane report — I (Inpaint decoupling) — T934

Lane: Implementer, track I. Date: 2026-09-16 (round 1 + round 2).
Files touched (complete list, inside the hard allowlist + round-2 extension):

- `app/src/main/java/eu/kanade/translation/pipeline/batch/OverlapScheduler.kt`
- `app/src/test/java/eu/kanade/translation/pipeline/batch/OverlapSchedulerTest.kt` (extended; no existing assertion changed)
- `app/src/test/java/eu/kanade/translation/coexistence/TranslationCoexistenceHarness.kt` (round 2: one await conversion, doc comment)
- `app/src/test/java/eu/kanade/translation/coexistence/{NormalMangaIsolationTest, StandardLaneMultiPageCompletionTest, T918CancelledBatchRestartTest, D2ManualBatchInterleavingTest, D7EngineEpochStopRaceTest}.kt` (round 2: READ for verification — zero edits required; see R2.5/R2.6)
- `app/src/test/java/eu/kanade/translation/pipeline/batch/StandardPipelineCoordinatorTest.kt` (round 2: READ T5 only — zero edits required; see R2.1)

No other file touched. No git-mutating command run (read-only git log/status/diff
used for evidence). No Gradle run (per lane constraints; Main Leader builds/tests).
Note: the working tree also carries the uncommitted R1 lane changes
(BatchWriteGate / ProfileEnvelopeExecutor / PageStageLeaseTable /
ChapterTranslationStore / ChapterProfileBatchCoordinator); this lane read them,
builds on the surviving BATCH slot semantics, and did not modify them.

---

## I.1 — Gate relaxed: OCR-final page is a candidate regardless of translation status

`OverlapScheduler.kt`:

- `nextInpaintCandidate` — `:280-321`. The old gate
  (`translation != READY && translation != PARTIAL → skip`, formerly ~:287) is
  replaced by:
  - `:298` — `if (page.ocrStatus != StageStatus.READY) continue` — candidacy
    now gates on OCR being FINAL, with a comment block (`:288-297`) stating the
    fingerprint evidence (`StageFingerprints.inpaint` has no translation input).
  - `:303` — `if (page.blocks.isEmpty()) continue` — textless preservation for
    the window between OCR-final and the translation stage marking the page
    SKIPPED (before which `isTextlessTerminal` is not yet true). No mask → no
    lane work.
  - Existing skips preserved verbatim: `:286` `isTextlessTerminal`,
    `:287` `hasRenderedResult`, and the inpaint-terminal skips at
    `:304-310` (READY / FAILED / TEXTLESS), plus `:283-284` (in-flight page,
    lease-owner deferrals).
- `inpaintOne` fresh-snapshot re-check — `:344-354`. Mirrors the relaxed gate
  (OCR-final + has mask); without this mirror a selected translation-PENDING
  page would have been discarded as `NoWork` and the relaxation would have been
  a no-op. Old translation-based condition removed here too.
- Design comment updated as instructed — the "Inpaint overlaps ONLY the remote
  wait" bullet in the class KDoc is rewritten at `:27-41`: window/drain
  exclusivity and the never-rules (no detector/OCR here, one native job at a
  time) are unchanged; what is deliberately relaxed — candidate admission no
  longer waits for the page's own translation — is now stated explicitly.
- `drainSerial` KDoc `:237-242`: "(translation committed, inpaint still
  pending)" → "(OCR-final, inpaint still pending — regardless of translation
  status, T934 track I)".

Selection-set parity: every page the old gate admitted (translation
READY/PARTIAL ⇒ OCR READY + non-empty blocks) the new gate still admits; the
new gate adds exactly {OCR READY, has blocks, inpaint non-terminal} — the
intended superset.

## I.2 — "No inpaint during OCR preflight" still enforced

Where the rule lives (all verified, all untouched):

1. Structural, inside the scheduler: `OverlapScheduler` only ever calls
   `nativeWorker.runInpaintStage(...)` (`OverlapScheduler.kt:369`) — it never
   calls `runOcrStage`. Class KDoc `:38-41` restates the never-rule.
2. Lifecycle: the scheduler is constructed only for the batch TRANSLATE lanes
   (`BatchChapterTranslator.kt:787` and `:845`) and the coordinator starts
   `runOverlapLoop` only inside the TRANSLATE-phase overlap wrapper
   (`ChapterProfileBatchCoordinator.kt:1740` AI lane, `:2096` standard lane);
   `drainSerial` runs at FINALIZE (`ChapterProfileBatchCoordinator.kt:1903`).
   TRANSLATE is entered only after ST-10 PROFILE_FROZEN; OCR_PREFLIGHT is a
   distinct earlier run state (`ChapterProfileBatchCoordinator.kt:648-671`,
   phase doc `:107-136`) that is fully terminal before TRANSLATE. The
   scheduler's own KDoc pins this at `:49-51`.
3. New second layer added by this lane: even if a window fired while some page
   were mid-preflight, `nextInpaintCandidate:298` requires `ocrStatus == READY`
   and `inpaintOne:348` re-checks it on the fresh snapshot — non-final OCR
   pages (PENDING/RUNNING/FAILED) are never admitted.

Pinning tests (new, `OverlapSchedulerTest.kt`):

- `non-final ocr pages are never inpaint candidates - no inpaint before ocr
  final` — `:522`. Seeds p1 OCR PENDING and p2 OCR merged-then-RUNNING; drives
  both a window AND `drainSerial`; asserts the lane is never touched
  (`inpainted`/`ocrEntries` empty, `overlapInpaintsExecuted == 0`,
  `serialInpaintsExecuted == 0`) and the OCR statuses are unchanged. This is
  the test that fails if anyone later drops the OCR-final condition.
- `ocr-final translation-pending page is inpainted in the window without
  waiting for translation` — `:487` also asserts `lane.ocrEntries shouldBe
  emptyList()` under the new candidate shape — zero detector/OCR work survives
  the decoupling.

## I.3 — Bitmap-budget concurrency caps unchanged

- `OverlapScheduler.kt:42-44` (class KDoc bullet) and `:120`
  (`private val inpaintMutex = Mutex()`) — strictly one native inpaint at a
  time; the legacy one-decoded-bitmap envelope. Untouched.
- Outside the lane (untouched, for the record): `MemoryGovernance`
  preflight gates wired at `TranslationPipeline.kt:1442-1448`
  (`preflightAnalyzeGate` / `preflightInpaintGate`).
- Concurrency is still asserted by tests: `observedMax() shouldBe 1` in the
  pre-existing window test (`:262`) and in the new I.4 test (`:513`).

## I.4 — Test: OCR READY + translation PENDING page gets inpainted

`OverlapSchedulerTest.kt:487` —
`ocr-final translation-pending page is inpainted in the window without waiting
for translation`. Seeds two OCR-only pages (new helper `seedOcrOnlyPage`,
`:136-158` — OCR merge only, translation left PENDING), opens one window, and
asserts: both pages inpainted in order (`inpainted shouldBe pageKeys`),
`overlapInpaintsExecuted == 2`, zero OCR entries, max lane concurrency 1, each
page ends `ocrStatus READY / inpaintStatus READY / translationStatus PENDING`,
and BATCH leases released (TX-06 teardown unchanged).

## I.5 — Test: displayReady still requires translation terminal + cleaned image

`OverlapSchedulerTest.kt:569` —
`display promotion still requires translation terminal and cleaned image even
when inpaint commits first`. End-to-end through the real store:

1. OCR-only page runs through the scheduler; inpaint commits (READY) while
   translation is PENDING and `cleanedImageName` is null.
2. Asserts the reader gate does NOT flip: `toPageDisplayProjection().displayReady
   == false`, `hasRenderedResult == false`, and the committed-display promotion
   did not fire (`store.resolveDisplayPage(...)` also projects not-ready).
3. Then commits translation READY + render READY + `cleanedImageName` +
   non-blank block translation through the standard guarded update, and asserts
   the page IS display-ready in both the live snapshot and
   `resolveDisplayPage` (promotion fired).

The gate itself lives in `ChapterTranslationStore.kt`
(`promoteDisplayIfReadyLocked` `:2353`, fed from `publishLocked` `:1999` and
`updatePage` `:1692`; readiness shape = `isTranslationDisplayShapeReady` in
`PageDisplayProjection.kt:140-144`). That file was NOT modified — the test
pins its behavior from the public API.

## I.6 — Test conversions (OverlappingSchedulerTest)

None required. The relaxation is strictly additive (superset of the old
selection set), and all seven pre-existing tests seed translation-READY pages,
which remain valid candidates. All existing assertions are retained verbatim:

- `:242` inpaint runs inside the remote window… zero ocr overlap — unchanged
- `:277` manual-owned lease is never preempted… — unchanged
- `:306` serial drain is the keep-serial arm… — unchanged
- `:325` inpaint commit hook fires per committed page… — unchanged
- `:413` serial drain yields a contended page… — unchanged
- `:435` serial drain defers a persistently failing page… — unchanged
- `:451` overlap loop yields a contended page and commits the rest… — unchanged

Diff audit: `git diff app/src/test/.../OverlapSchedulerTest.kt` = +186/-0 plus
two import additions (`model.hasRenderedResult`, `model.toPageDisplayProjection`)
and the new `seedOcrOnlyPage` helper. No assertion weakened, none deleted.

---

## Off-allowlist test compatibility (read-only audit — no edits)

These files consume the scheduler; I could not edit them, so I verified the
relaxation cannot change their outcomes:

- Scheduler-counter assertions exist only in `OverlapSchedulerTest.kt`,
  `Stage7FinalizeCoordinatorTest.kt:344` (`serialInpaintsExecuted == 3`) and
  `StandardPipelineCoordinatorTest.kt` (T5 latch test + FINALIZE hook).
- `Stage7FinalizeCoordinatorTest` / `Stage7FinalizeResumeCoordinatorTest` /
  `StandardPipelineCoordinatorTest` (non-T5 tests) run the coordinator on the
  TestScheduler. Their green baseline already proves the overlap loop is never
  dispatched mid-run: under the OLD gate, translated-but-uninpainted pages were
  already mid-run candidates during later windows, and any mid-run dispatch
  would have broken the `FINALIZE`-first-inpaint / all-serial counters they
  pin. The relaxation only ADDS candidates; added candidates are consumed only
  on dispatch; no dispatch ⇒ identical behavior.
- `StandardPipelineCoordinatorTest` T5 (`:623`, latches p1's translate inside
  its open window so the loop IS dispatched): its `FakeOverlapInpaintLane`
  throws for any non-translated page (`:214-222`); the scheduler swallows that
  as a typed `Failed` (no commit, no `inpainted` append), so the latch-time
  assertions (`inpainted empty`, overlap/serial == 0) still hold; after the
  latch, each page inpaints in its own window and the final sum assertion
  (overlap+serial == 3) and terminal statuses hold.
- Coexistence suites (`runBlocking`, real concurrency — inpaints genuinely run
  earlier under the new gate): `StandardPipelineCoexistenceTest`,
  `StandardLaneMultiPageCompletionTest`, `D6DrainNotCancelTest:266`,
  `D10:404`, `D11:178`, `P5`, `Milestone*` assert only decode-counts before the
  first provider call (the harness fake inpaint performs no NATIVE_ACQUIRE),
  transport call-counts, and terminal states — all timing-agnostic. The
  harness's fake inpaint still waits on `transportStarted[pageKey]`
  (`TranslationCoexistenceHarness.kt:516-518`), so per-page ordering evidence
  is preserved. No `NATIVE_RELEASE` ordering assertions exist against the batch
  path. `transportWaitsForNativeStage` is never used by any test.
- `BatchLeaseFlipHealTest` (R1) drives the write gate directly; the scheduler
  candidate gate is not involved.

Two stale DOC comments in off-allowlist files (non-blocking, for the Main
Leader to sweep in a later lane or commit):

- `TranslationCoexistenceHarness.kt:192-199` — the `transportWaitsForNativeStage`
  comment still reasons "a page is a candidate only AFTER its translation
  commit". The mode is unused; comment-only staleness.
- `StandardPipelineCoordinatorTest.kt:212-214` — the fake-lane guard comment
  calls the translation-first rule "the scheduler's candidacy rule". The guard
  itself now acts as the fake's own discipline; behavior unaffected.

## Verification handoff

Main Leader to run: `:app:testStandardDebugUnitTest` — targeted classes
`OverlapSchedulerTest`, `Stage7FinalizeCoordinatorTest`,
`Stage7FinalizeResumeCoordinatorTest`, `StandardPipelineCoordinatorTest`,
`StandardPipelineCoexistenceTest`, `StandardLaneMultiPageCompletionTest`,
`D6DrainNotCancelTest`, `D10PartialDownloadAdmissionTest`,
`D11PermitFreeCommitTest`, then the full suite at GATE-W2. No assertion in this
lane was weakened or deleted; new tests only strengthen (OCR-final requirement
and display-promotion gate are now pinned).

---

# ROUND 2 (2026-09-16) — verified-failure fixes

Round 1's off-allowlist compatibility audit (the section above, "Off-allowlist
test compatibility") was WRONG on several counts. The five verification
failures are addressed here; each is classified production-vs-fixture with the
evidence trail. Round-1 audit errors are called out per item.

## R2.0 — Root cause shared by failures 2/3/4/5-of-5: attach-then-plain-release on a live BATCH hold

The page-stage lease model grants a SAME-ORIGIN re-acquire as a SIBLING ATTACH
(`PageStageLeaseTable.kt:105-126` — same token, `attaches+1`), while the plain
`releasePageStageLease` removes the record on origin match REGARDLESS of
attaches (`:151-162`; R1.2 added `releasePageStageLeaseIfUnattached`
`:176-194`, which removes only when `attaches == 0` and does NOT decrement).

Round 1's relaxed gate made the overlap scheduler attempt pages whose own
translation was still in flight. `inpaintOne`'s `tryAcquirePageStageLease`
against a slot already held by the page's own translation writer (the standard
tail's `standardTranslateOutcome`, `BatchChapterTranslator.kt:667-733`, or the
profile envelope commit) returned a SIBLING GRANT, so the scheduler:

1. registered a competing `BatchWriteIdentity` for the same page (clobbering
   the writer's plan-time CAS inputs), and
2. on completion, its TX-06 plain release REMOVED the writer's lease record
   mid-flight — the writer's own commit then failed closed
   (`BatchPersistenceRejectedException` / `EnvelopeDispatchResult.Paused`;
   reason "Batch persistence publication rejected", typed at
   `SinglePageHttpRenderPhase.kt:764-767` for the AUTO boundary and
   `BatchChapterTranslator.kt:949-960` for the standard tail).

This is a production defect in the SCHEDULER (my file), fixable without
touching the lease table, the write gate, or the coordinator.

## R2.1 — StandardPipelineCoordinatorTest T5 hang (:623) — PRODUCTION (scheduler), FIXED

- Classification: production. jstack showed `inpaintOne` (:412 pre-edit) inside
  `runOverlapLoop` (:215) spinning. Mechanism: T5's `FakeOverlapInpaintLane`
  throws for any non-translated page (:216-222); under the relaxed gate the
  window loop selected p2/p3 (translation PENDING) every scan, the lane threw,
  `inpaintOne` returned `Failed`, the loop treated it as progress and
  RE-SELECTED the same pages — an unbounded retry spin inside the still-open
  window. On the TestScheduler this is a hang (`advanceUntilIdle` never idles).
  Round-1 audit error: I traced the throw being swallowed as `Failed` but
  missed that `Failed` did not remove the page from the candidate scan.
- Fix (scheduler): one-attempt-per-window defer discipline.
  - New window-scoped set `deferredUntilNextWindow`
    (`OverlapScheduler.kt`, declared next to `deferredByLeaseOwner`); checked
    in `nextInpaintCandidate`; cleared in `onRemoteWindowOpened` (a new window
    is a state change — every page gets one fresh attempt) and at
    `drainSerial` start (the drain is its own pass).
  - `runOverlapLoop`: `InpaintOutcome.Failed` now adds the page to
    `deferredUntilNextWindow` instead of leaving it re-selectable.
  - Design (documented in the class KDoc "Defer-retry discipline" bullet):
    an unsuccessful attempt is retried only when something CHANGES —
    lease-denied (foreign owner) pages stay deferred for the rest of the pass
    (owner outcome authoritative); slot-busy and lane-failed pages are
    re-admitted no earlier than the next window open or the drain start; the
    serial drain keeps its existing "deferred for the rest of this drain"
    rule. No spin is possible: every outcome either commits, or puts the page
    into a set the next scan skips, so the inner while always terminates.
- T5 assertions: NONE converted. Full trace under the fixed scheduler: window 1
  (p1 translating, slot held) → p1 SlotBusy-deferred, p2/p3 attempted exactly
  once each (lane throws → Failed → deferred), loop idles; mid-latch
  assertions (:663-669: calls == [p1], windows >= 1, overlap/serial == 0,
  inpainted empty) hold; after releaseP1 each page inpaints exactly once
  (p1 in p2's window, p2 in p3's window, p3 in the FINALIZE drain) so
  `inpainted shouldContainExactly pageKeys` (:679) and the sum==3 counter
  assertion (:681) hold unchanged.
- Neighbor test :426 (FINALIZE onFirstInpaint order) re-verified untouched:
  with instant fakes the loop only ever sees already-closed windows (the inner
  while guards on `windowOpen.get()`), so the first lane entry remains the
  FINALIZE drain. My changes alter no event timing.

## R2.2 — OverlapSchedulerTest :569 ARTIFACT_PUBLICATION_FAILED — FIXTURE, FIXED

- Classification: fixture. Production is right: the committed-display
  promotion (`ChapterArtifactStore.promoteLiveCandidate` :1281 via
  `displayBaseIsValid` :1801-1812) validates the cleaned image FOR REAL — the
  companion file must exist on disk with length > 0 and probe as a decodable
  image matching the page's source identity. My round-1 test wrote
  `cleanedImageName = "cleaned-p1.jpg"` with no file and the JVM-default
  probe (`BitmapFactoryCleanedImageProbe` — no BitmapFactory on the JVM), so
  the guarded update was correctly rejected. Round-1 audit error: I never
  verified the publication requirements of the promotion I was pinning.
- Fix (test fixture only, following the `ChapterTranslationStorePersistenceTest`
  / D7-fixture recipes): install `CleanedImageProbe { ProbedImage(100, 160) }`
  on the `ChapterTranslationStore.artifactImageProbe` seam (:2835, `@Volatile
  internal var`, read when the lazy store first builds its artifact store) and
  restore it in `finally`; write the companion file the layout expects
  (`ChapterArtifactLayout.legacyCompanionImageFile` = `Chapter
  1_images/cleaned-p1.jpg`) before the final guarded update. Assertions
  UNCHANGED — the test still pins exactly what it pinned in round 1.

## R2.3 — Coexistence stalls (NormalMangaIsolationTest :43, StandardLaneMultiPageCompletionTest :112, T918CancelledBatchRestartTest :60) — FIXTURE deadlock (+ the R2.0 scheduler defect), FIXED

- Classification: the stall itself is a FIXTURE deadlock; the scheduler's
  attach-then-plain-release (R2.0) is the production defect underneath it.
  The harness fake inpaint awaited `transportStarted[pageKey]`
  (`TranslationCoexistenceHarness.kt:517` pre-edit) WHILE holding the ONE
  native-lane permit. Under the relaxed gate the overlap legitimately
  dispatches a page's inpaint BEFORE (or without) that page's transport:
  - NormalManga (single page): the inpaint parks awaiting a transport that is
    serially behind it — the native lane never frees, the phase never
    completes, `reconciliation.await()` times out.
  - StandardLane test 2 (`failed predecessor…`, :112): p1 is honestly
    stranded with NO transport (the pinned contract), so its inpaint awaited
    forever.
  - T918: run 1's park + restart choreography depends on the inpaint not
    blocking the lane.
  The await pinned the PRE-decoupling lane order ("identity check passed once
  the transport started"); track I removed exactly that ordering.
  Round-1 audit error: I explicitly claimed "the harness's fake inpaint still
  waits on transportStarted, so per-page ordering evidence is preserved" —
  that wait is precisely what deadlocked four suites.
- Fix (harness, one conversion): the await is REMOVED and replaced with a
  conversion comment (before/after documented in code): the same-page
  write-exclusivity the wait used to approximate is now provided by the
  scheduler's BATCH write-slot admission (R2.4), and the fake inpaint runs
  unconditionally like the real native worker. `transportStarted` itself is
  KEPT (positive probes in StandardLaneMultiPage/T918/D7 still use it);
  `transportWaitsForNativeStage` remains dormant (no test sets it).
- Suite re-verification (traced, no test edits needed):
  - NormalMangaIsolationTest: p0 defers while its own translation commit holds
    the slot; inpaints at the drain; NATIVE_ACQUIRE p0 == 2 (preflight +
    inpaint), transport calls == 1, translation/render READY all hold.
  - StandardLaneMultiPageCompletionTest: both tests — fresh 3-page run
    completes with exactly-once transports and render-terminal stamps; the
    FAILED-predecessor test still never calls p1's transport (p1Started false)
    and p1 still lands typed FAILED (the inpaint commit merges inpaint fields
    only).
  - T918CancelledBatchRestartTest: run 1 inpaints p0 during p1's parked window
    (state.first at :98-103 satisfied BEFORE the cancel, so the before-restart
    decode counts hold); the restart's p1 inpaint runs exactly once at the
    drain (`NATIVE_ACQUIRE p1 == before+1`), p0 is resume-skipped (no
    re-decode), paid calls 1/2 unchanged.

## R2.4 — D2ManualBatchInterleavingTest :37 — PRODUCTION (scheduler), FIXED

- Classification: production (round-1 regression via R2.0). The batch's
  overlap inpaint of p0 sibling-attached to the batch's own held slot while
  the transport was parked at PROVIDER_START, committed, and its plain release
  freed the lease — so the manual tap's wait-and-attach found the page free
  and completed inside the negative-probe window (:81-92 failed), and the
  exactly-once/ownership pins broke. Round-1 audit error: my "no
  mid-run-dispatch" property ignored that the coexistence harness's real
  concurrency DOES dispatch mid-window.
- Fix (scheduler): BATCH write-slot exclusivity admission pre-check at the top
  of `inpaintOne` (the commented block before `tryAcquirePageStageLease`): if
  `store.snapshot(pageKey).leaseToken != null` the page is NOT acquired at
  all —
  - overlap arm: `deferredUntilNextWindow += pageKey` +
    `serialFallbacks.incrementAndGet()` (gate-6.5 accounting: this page could
    not ride the window) → `InpaintOutcome.SlotBusy` (new enum value; loop
    treats it like LeaseDenied);
  - drain arm: `deferredByLeaseOwner += pageKey` (the drain's existing
    defer-for-this-drain discipline).
  The scheduler therefore NEVER sibling-attaches to a live hold in the
  deterministic choreographies: it inpaints a page only under its own fresh
  grant, its plain release retires exactly its own record, and same-page
  write races with the in-flight translation commit are gone (this is the
  write-exclusivity rule that replaces the old translation-status gate — the
  decoupled overlap waits for the page's own in-flight batch WRITE to settle,
  never for translation DATA). The `tryAcquirePageStageLease` denied-branch is
  kept as the race backstop (a foreign-origin owner is still never
  preempted).
- Residual (documented in code): the pre-check snapshot and the acquire are
  not atomic; a same-origin writer acquiring in that window yields a sibling
  grant whose plain release retires the record per the R1 sibling contract
  (`PageStageLeaseTable.kt:164-174` — "a re-attached record is left for the
  attached sibling's own (plain) release") and the writer heals
  (`guardedBatchUpdate` refresh-retry, `BatchWriteGate.kt:123-141`) or pauses.
  Narrow race vs the deterministic breakage removed; fixing it fully would
  need an attach-aware acquire in the lease table (forbidden file).
- D2 re-verified: test 1 (both PROVIDER_START/PROVIDER_END iterations) — the
  inpaint defers while p0 is parked, the tap waits (manualWaited true), after
  release the batch commits, the drain inpaints, reconciliation completes,
  exactly-once holds. Test 2 unchanged semantics (MANUAL hold defers; after
  the manual release the pass's defer-and-rescan skips re-pay; p1's inpaint is
  the manual path's own, already READY → never re-touched).

## R2.5 — D7EngineEpochStopRaceTest :362 — PRE-EXISTING at W0, production root OUTSIDE this lane's allowlist — REPORTED, NOT FIXED

- Evidence it is NOT a track-I regression: `team/w0-baseline-failures.md`
  lists "D7EngineEpochStopRaceTest — engine close with a large grace waits
  for the translator borrow to end" (exactly test (b), :362) among the 26
  known failures at the WIP baseline commit 25a7589 — BEFORE track I touched
  anything. Track I's only production file (OverlapScheduler.kt) is not in
  D7's path at all: the test drives the REAL AUTO prepared-page boundary
  (`pipeline.prepareSinglePage` + `pipeline.translatePreparedPage`,
  D7EngineEpochStopRaceTest.kt:194-200), and `OverlapScheduler` is
  constructed ONLY inside `BatchChapterTranslator` (:787, :845) — no batch
  runs in D7.
- Failure mechanism (traced): the AUTO boundary commits through
  `SinglePageHttpRenderPhase`'s final `patchPage` with the precondition
  captured at `translatePreparedPage` ENTRY (`TranslationPipeline.kt:1124`,
  carried via `OnnxPhaseResult.commitPrecondition`). On rejection it types
  `ChunkCompletionOutcome.PersistenceRejected(p0, TRANSLATION, "Batch
  persistence publication rejected")` (`SinglePageHttpRenderPhase.kt:759-767`)
  — the observed signature, asserted at :387-397. The in-call healing at
  `:731-741` re-derives the precondition only on a GENERATION change; a
  same-generation pageVersion/artifact drift between entry snapshot and
  commit rejects. (a) passes / (b) fails fits a deferred publication landing
  inside (b)'s 2s negative-probe window (`:376-384`) — consistent with the
  W0 in-flight "universal standard group commit / page-cache fast path" work
  (8010961) whose async publication the batch lanes already had to heal
  (the refresh-before-persist idiom at `BatchLaneWorkers.kt:714-730`,
  "2026-09-16 bubble-cleaning failures").
- Why not fixed here: the fix belongs in `SinglePageHttpRenderPhase` (extend
  the :731-741 same-owner re-derivation to cover same-generation
  pageVersion/artifact drift, mirroring `guardedBatchUpdate`'s refresh-retry)
  or in the store's commit path — both OUTSIDE this lane's allowlist. Per the
  lane rules I did not touch them. The D7 TEST file needed no conversion (its
  drain-not-close contract is correct and untouched).

## R2.6 — Scheduler regression sweep (all existing tests re-traced under the new logic)

- `OverlapSchedulerTest` (all 7 pre-existing + 2 round-1): manual-owned :279 —
  the pre-check now produces the defer (SlotBusy + serialFallbacks==1, one
  window) with identical counters and the MANUAL lease untouched; contention
  (:415/:437/:453) unchanged (contended pages throw inside the lane →
  Deferred/drain-defer paths untouched); window/drain/hook/I.3/I.4 unchanged
  (free slots at admission).
- Round-1 converted assertions: none; round 2 adds none. Net assertion
  strength INCREASED (OCR-final candidacy + promotion gate pinned; harness
  ordering wait converted with before/after documented in code).
- Coordinator production file: NOT changed (verified not needed — R2.1/R2.4
  fixes are fully inside OverlapScheduler.kt).

## R2.7 — Round-2 verification handoff

Main Leader to run (Gradle): targeted classes `OverlapSchedulerTest`,
`StandardPipelineCoordinatorTest` (T5 + :426), `NormalMangaIsolationTest`,
`StandardLaneMultiPageCompletionTest`, `T918CancelledBatchRestartTest`,
`D2ManualBatchInterleavingTest`, then the W0 coexistence cluster
(`Stage7FinalizeCoordinatorTest`, `Stage7FinalizeResumeCoordinatorTest`,
`StandardPipelineCoexistenceTest`) and D7 at GATE-W2. D7 (b) is expected to
REMAIN RED until the R2.5 production fix is owned by a lane with
SinglePageHttpRenderPhase/ChapterTranslationStore in its allowlist.

## R2.8 — Round 3: T918 :160 (p1.renderStatus PENDING after restart)

Root cause found via the recorded Gradle test report's harness probe trace
(`app/build/test-results/testStandardDebugUnitTest/TEST-eu.kanade.translation.coexistence.
T918CancelledBatchRestartTest.xml`, system-out): my R2.3 claim "the restart's
p1 inpaint runs exactly once at the drain" is FALSE under the decoupled
scheduler. Actual choreography:

1. Run 1: OCR preflight runs p0+p1 (durable checkpoints). During p0's window
   the relaxed (round-1) candidacy selects p1 — OCR-final, lease-free,
   translation PENDING — and inpaints it (`nativeLane p1` + `inpaint p1 run`
   appear BEFORE `transport call p1` in the trace). At that moment
   `target = translationRegistry[p1] ?: latest` has translationStatus PENDING
   (registry not even populated — p1's translate never ran), so the
   BatchLaneWorkers render-terminal stamp (:799-820, gated on
   `target.translationStatus == READY || PARTIAL`) is correctly skipped — and
   the inpaint artifact becomes DURABLE (inpaintStatus READY +
   cleanedImageName "p1.cleaned.jpg" persisted by the harness publication).
2. p1's paid call parks at PROVIDER_START; during that parked window p0's
   inpaint runs with p0's translation already terminal → p0's stamp fires in
   run 1 (this is why :159 passes).
3. Cancel. Restart: p1's INPAINT plan is now REUSE (durable READY artifact,
   payload valid) — the trace shows NO inpaint work in the restart. p1's
   translation runs (call #2), commits READY — and NO code ever stamps p1
   render-terminal: the only post-translation stamp owner is the
   inpaint-completion path, which never executes for p1 again. The
   coordinator's E2 adoption stamp (`stampAdoptedRenderTerminal`,
   ChapterProfileBatchCoordinator :2758) cannot heal it: it fires only at
   OCR-preflight checkpoint adoption (:441/:615), BEFORE any translation.

Classification: PRODUCTION GAP exposed by the track-I decoupling (not a
fixture bug): the decoupling legitimately inverts a page's inpaint/translation
order, and the render-terminal stamp has no owner for the inverted order —
renderStatus stays PENDING forever, the reader shows the original (the exact
field-report signature the stamp exists for), and the E2 comment's promise
("a later run's adoption stamp retries it") is not kept for this shape. T918
itself was RED at W0, but with a STALL signature (the round-2 harness-await
deadlock); the :160 signature is new — round 1/2 moved the test PAST the stall
onto the real decoupling gap.

Fix (production, inside the allowlisted scheduler — OverlapScheduler.kt):
`stampRenderTerminalOrphans()`, called at the end of `drainSerial()` (the
pass's serial settle point, before FINALIZE's reconciliation). For every page
durably display-complete under the SAME predicate as E2 (translation
READY/PARTIAL + inpaintStatus READY + cleanedImageName != null + renderStatus
PENDING + a non-blank translated block) it performs the idempotent
render-terminal stamp via `store.updatePageGuarded` with a fresh
POST-ACQUIRE snapshot precondition (the store's lease fence rejects a null
expected leaseToken while a lease is held — ChapterTranslationStore :1578-1580
— so the precondition is snapshotted after acquiring the Render-stage BATCH
lease; release in finally, TX-06). ONE bounded pass — no waiting, no retries;
a denied lease or rejected write skips the page (a later run's drain retries),
identical to E2's rejection semantics. Same-run invocations are covered too:
a page inpainted in an earlier window of the SAME pass is stamped at that
pass's drain.

Test conversion (allowlisted T918 file, DOCUMENTED in code): :166-175 asserted
`NATIVE_ACQUIRE p1 == decodeP1BeforeRestart + 1` ("the inpaint is the
restart's own native work") — that encoded the PRE-decoupling choreography.
Under the decoupling the inpaint is run-1 completed work; the restart
re-decodes NOTHING. Converted to `== decodeP1BeforeRestart` — strictly LESS
restart work (a stronger reuse assertion, no weakening); the render-terminal
comment at :153-156 was updated to name the real stamp owners. All other
assertions unchanged (:163-165 p0 zero re-decode; :188-193 transport
p0==1/p1==2).

New scheduler unit test (allowlisted OverlapSchedulerTest):
`serial drain stamps order-inverted display-complete pages render-terminal` —
window inpaints p1+p2 while both translations are PENDING (the inversion),
translations commit afterward, p1 with full display evidence (cleaned
companion file + probe, mirroring the promotion-test fixture) and p2 without;
drain must stamp exactly p1 (renderStatus READY + committed-display promotion
fires) and leave p2 PENDING, with leases released. Verified compatibility: no
batch/coexistence test asserts renderStatus PENDING through a drain (grep);
the existing OverlapSchedulerTest drain tests have cleanedImageName == null at
drain time, so the sweep is a no-op for them.

Verification still owed (Main Leader, Gradle): `OverlapSchedulerTest`,
`T918CancelledBatchRestartTest`, then the round-2 green set
(`StandardPipelineCoordinatorTest`, `NormalMangaIsolationTest`,
`StandardLaneMultiPageCompletionTest`, `D2ManualBatchInterleavingTest`,
Stage7 cluster) to confirm the sweep is behavior-neutral where no page is
display-complete at drain.
