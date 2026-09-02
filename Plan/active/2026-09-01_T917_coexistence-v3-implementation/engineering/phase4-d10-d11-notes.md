# T917 Phase 4-B implementation notes — D10 + D11

Date: 2026-09-02
Branch: `t917/coexistence-v3`
Commits:
- D10: `3ff5d3f` — `t917(p4): d10 partial-download admission + honest missing-page accounting`
- D11: `b8c0974` — `t917(p4): d11 release native permit before storage publication (safe slice)`

Implementer report for phase4-design §3 (D10) and §4.4 safe slice (D11).
RED-first discipline was used for both decisions; nothing was committed at RED.

---

## D10 — partial-download admission + honest missing-page accounting (§3)

### Implemented per design section

**Pure probe** (`pipeline/batch/BatchAdmissionProbe.kt`, new):
`BatchAdmissionProbe.evaluate(downloadedPageCount, sourcePageList)` returns sealed
`BatchAdmissionDecision`: `Complete` (count >= list size), `Partial(expectedSourcePageCount,
downloadedPageCount)` (0 < count < size), `UnknownCount` (no list or empty). Plus
`PartialDownloadChoice { FINISH_DOWNLOAD_FIRST, TRANSLATE_WHAT_EXISTS }` and
`BatchAdmissionRouting.route(decision, choice)` → `AdmitBatch / AdmitSubset / WaitForDownload /
AskUser` (Complete → AdmitBatch regardless of choice; Partial + null choice → AskUser).
Deviation on the probe's inputs: see Deviations #1.

**Manifest truth** (`artifact/ChapterArtifactManifest.kt`): additive-nullable
`PartialBatchInfo(expectedSourcePageCount, missingPageCount, determinedFrom, recordedAtEpochMs)`
with `PartialBatchDetermination { DOWNLOAD_CROSSCHECK, UNKNOWN }`, new manifest field
`partialBatchInfo: PartialBatchInfo? = null`. Old manifests deserialize unchanged
(default-null field, no schema break).

**Store admission truth** (`ChapterTranslationStore.preRegisterPages`): now accepts
`(pageKeys, probedSourcePageCount, sourceCountKnown)`. Trusted total is the SOURCE count when
known (target = max(found, total)); expected-page-count trust is forced FALSE when the total is
unknown (`UNKNOWN` determination). Found-count is distinct page keys. Partial info records
missing = max(0, total − found), is cleared when a later run records missing = 0
(DOWNLOAD_CROSSCHECK), and legacy runs preserve the manifest's prior value. Skip-publish
equality extended with `partialBatchInfo`.

**Threading** (`ChapterTranslator.translateChapterInternal`, `TranslationManager`,
`model/Translation.kt`): `probedSourcePageCount` / `sourceCountKnown` carried on the live
`Translation` body (set only when `sourceCountKnown`) and passed to `preRegisterPages`.

**Trigger routing** (`MangaScreenModel` + `MangaScreen`): trigger partitions candidates by
probing in-queue downloads (queue entry + `download.pages?.count { it.status == READY }`),
maps each decision through `BatchAdmissionRouting`, shows the PLAN-mandated
`Dialog.PartialDownloadTranslation` (literal truthful copy: "X of Y pages are downloaded…",
confirm = "Translate what exists", dismiss = "Finish download first") and implements
`translatePartialDownloadNow` (translate with `BatchAdmissionContext` per page) and
`finishDownloadBeforeTranslation` (queue-translation-after-download + fresh download
generations). Settled downloads admit unchanged — Deviation #2.

**What was NOT done (per design)**: full write-behind persistence (Phase 6); missing pages are
never registered as page records or ledger entries (planner REUSE/absence handles them).

### RED/GREEN evidence

Suite: `eu.kanade.translation.coexistence.D10PartialDownloadAdmissionTest` (5 tests).

- RED (re-validated 2026-09-02 against `3ff5d3f~1` production code, XML:
  `TEST-eu.kanade.translation.coexistence.D10PartialDownloadAdmissionTest.xml`):
  `tests=5 failures=5 errors=0 skipped=0`. All five failures are NAMED, zero choreography
  timeouts:
  1. "T917 D10 RED defect: BatchAdmissionProbe is missing — batch admission cannot distinguish
     complete vs partial vs unknown-total download" (probe matrix)
  2. "D10: the durable record stops claiming trust it does not have — expected:<false> but
     was:<true>" (expectedPageCountTrusted on unknown total)
  3. "D10: the trusted total is the SOURCE total (2), not the found count (1) — expected:<2>
     but was:<1>" (subset run manifest truth)
  4. "T917 D10 RED defect: BatchAdmissionProbe is missing …" (routing matrix)
  5. "D10 precondition: run 1 recorded the partial truth. RED defect: no partial info exists to
     clear (audit M-08)" (re-run clears partial info)
- GREEN at `3ff5d3f` (and re-verified at `b8c0974`): `tests=5 failures=0 errors=0 skipped=0`.

Coverage: probe matrix purity; 2-page subset run (1 of source=2 downloaded → manifest
`expectedPageCount=2` trusted + `partialBatchInfo{expected=2, missing=1, DOWNLOAD_CROSSCHECK}`,
only p0 admitted/committed, p1 absent from store and ledger, failedCount=0, stranded empty);
unknown-total run (expected = found, trusted=false, `UNKNOWN` determination); routing matrix
(FINISH→WaitForDownload, TRANSLATE→AdmitSubset, null→AskUser, Complete→AdmitBatch); re-run
completes the missing page → partial info cleared, expected=2 trusted, both pages present,
ledger still has no missing-page entry.

### Deviations (with reasons)

1. **Probe inputs are 2-arg pure** `(downloadedPageCount, sourcePageList)`; the chapterId →
   queued-download resolution stays at the trigger. The design folds these into one probe;
   keeping resolution at the trigger preserves the pure-function testability the design also
   demands. No behavioral difference.
2. **Settled downloads admit unchanged instead of UnknownCount → dialog.** The literal design
   ("no Download object → UnknownCount → AskUser") would show the dialog on EVERY settled
   downloaded chapter, because `Downloader` removes the queue entry when a download completes
   (Downloader.kt:292-293). A settled download is, from the user's viewpoint, complete; the
   trusted total still comes from the downloaded page list. The probe therefore runs only for
   candidates with a live queue entry. Closest safe behavior; recorded here per the mandate.
3. **D10 tests assert admission truth at the page-commit level, not terminal done/stranded
   counts.** Pre-existing T909 §4 gap (out of D10 scope): on ARTIFACTS-authority fixtures the
   batch render/display-promotion commit is rejected (`patchPage` candidate-grace), pausing the
   batch with `nonDurableFailure` at display promotion. D5's phase log already documents this
   as another task's scope. The page-level commit truth (translation/cleaned/inpaint READY in
   the store) fully pins D10's admission semantics; the subset re-run keeps `doneCount=1` for
   the reopened terminal page.
4. **2-page fixtures** (not larger multi-page subsets) due to the harness's known multi-page
   fresh-batch lane-serialization limitation; 2 pages are sufficient to pin subset semantics.
5. **Commit sequence collapsed**: design §6 lists probe/manifest/store/trigger as separate
   commits; the task mandate required exactly two commits (`d10`, `d11`). All D10 changes are
   in `3ff5d3f`.

---

## D11 — release native permit before storage publication, safe slice (§4.4)

### Implemented per design section

New internal holder `pipeline/DeferredPagePublications.kt`: `ConcurrentLinkedQueue<suspend
() -> Unit>` + `@Volatile orphaned`; `enqueue(action)` runs inline when orphaned, else queues;
`drainAll()` runs queued actions in enqueue order.

Wired through both boundaries (`TranslationPipeline.runGrantedSinglePageBoundary` and
`prepareSinglePage` → wrapper → `SinglePageOnnxPhase.translateSinglePageOnnx(…,
deferredPublications)`; null = legacy inline for any other caller):

- Defer points (SinglePageOnnxPhase):
  - `resumeInpaintAndRender`: cleaned-image `persistCleanedBitmap` + `renderResumedPage` tail
    enqueued as ONE lambda (persist-failure branch keeps its exact pre-D11 semantics: recycle,
    render FAILED, persist — fail-closed inside the tail);
  - render-only resume branch: `renderResumedPage` tail enqueued;
  - `finally`: `store.flush()` + `streamRegistry.clearPage` enqueued AFTER the persist tails
    (publication order identical to the inline sequence).
- NOT deferred (per design): fresh-path `persistOnnxCleanedImage` (already outside the permit);
  `recognitionEngine.reclaimPooledMemory()` (memory work, stays inline).
- Boundary: holder created before `withNativeLane`; `onTimeout` sets `orphaned = true` BEFORE
  `markPageTimedOut` (a residual block invocation then runs its tails inline; quarantine exit
  is awaited before `withNativeLane` returns, so nothing is dropped and no tail races the
  boundary's drain). The normal-path drain runs AFTER `withNativeLane` returns and BEFORE the
  null-result handling, so a drained tail is visible to the terminal-state inspection.
  Fail-closed: a drain exception → `markPageFailed` + rethrow (persistence failure fails the
  page, never silently). Exception path: best-effort inline drain + rethrow of the original
  failure (the block already failed the page) — Deviation #8.

All store mutex fences and the synchronous fail-closed publication contract are preserved; no
native work is killed/aborted/cancelled; full write-behind persistence stays deferred to
Phase 6 per design.

### RED/GREEN evidence

Suite: `eu.kanade.translation.coexistence.D11PermitFreeCommitTest` (1 test). Choreography: p0
resumes at inpaint (OCR+translation committed, cleaned missing) so its path runs the resume
tail whose publication is a REAL store commit through the delegating harness publication mock;
the test parks that publication at a COMMIT barrier mid-commit, taps p1, and probes (bounded,
converted to a NAMED assertion) whether p1's native admission arrives while the publication is
parked.

- RED (re-validated 2026-09-02 against pre-D11 production code, XML:
  `TEST-eu.kanade.translation.coexistence.D11PermitFreeCommitTest.xml`):
  `tests=1 failures=1 errors=0 skipped=0` — named AssertionError: "T917 D11 RED defect: the
  second page's native admission never arrived while the first page's cleaned-image publication
  was still parked — the native permit spans the storage commit (phase4-design §4.4);
  arrivals=[(NATIVE_ACQUIRE, p0), (COMMIT, p0)] permitHolder=p1 p1JobActive=true p1Outcomes=[]
  p1Store=null". Zero raw choreography timeouts.
- GREEN at `b8c0974`: `tests=1 failures=0 errors=0 skipped=0` — p1's NATIVE_ACQUIRE arrives
  while p0's publication is parked (`permitHolder=null` at that moment), and after release the
  parked commit lands (p0 cleanedImageName + inpaintStatus READY) and p1 finishes its fresh
  path (translation READY).
- Regression sweep at `b8c0974` (mandated command, `--rerun`): `tests=274 failures=0 errors=0
  skipped=0` across `coexistence.*`, `translator.*`, `scheduling.*`.

### Deviations (with reasons)

6. **D11 is the safe slice only**: the design's §4.4(1) first bullet (move resume-path
   persist/render tail + store.flush() out of the permit). The broader write-behind
   persistence architecture stays deferred to Phase 6 per design; no planner/reconciler
   changes.
7. **Adjacent defect observed and documented, NOT fixed (D8 scope)**: a null native result —
   which the resume paths return on SUCCESS — conflates with timeout in
   `runGrantedSinglePageBoundary`'s `?: return Failed("native phase timed out")`. Store state
   remains truthful (both D11 assertions are store-state assertions); only the typed
   `SinglePageOutcome` is wrong. Left to the D8 owner; recorded here so the Reviewer knows the
   conflation is pre-existing, not introduced.
8. **Exception-path drain is best-effort** (`runCatching` + rethrow of the original failure):
   on that path the block has already failed the page; making the drain strictly fail-closed
   there would mask the original failure. The normal path is strictly fail-closed.

### Harness note (D11 enabler, behavior-preserving)

`TranslationCoexistenceHarness.cleanedPublicationMock` became a DELEGATING mock (all four
public methods forward to the real `CleanedPublication`) wired into BOTH the pipeline field and
`SinglePageOnnxPhase`/`SinglePageHttpRenderPhase`, so a test seam covers the boundary's
`persistOnnxCleanedImage` AND the resume tail's `persistCleanedBitmap`. Previously the phases
used the real instance directly and the D11 park was unreachable (that wiring miss was the
first RED-run fix). The manual publish shim still overrides `persistOnnxCleanedImage` exactly
as before.

### Test-fixture note (reused from D8's harness note)

A pre-registered PENDING page projects WAIT_FOR_DEPENDENCY for every stage under the
non-force single-page plan and resume-skips before the decode seam. The D11 test therefore
pre-registers only p0 (the resume fixture) and leaves p1 absent so it plans a fresh native run.
