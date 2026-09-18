# T934 lane report — fix-display-tail-drain (COMPLETE means every page readable)

Implementer lane. Branch `t934/resume-rebuild-and-parallelism` (worktree
`orchestrate_execution_order_v3`), base f8f8d50, tree clean at start. No
git-mutating commands, no Gradle runs (verified by reading + structural
checks per the standing constraints).

## 1. The publish chain — what actually makes a page display-ready

`displayReady` (the progress sheet's "N of M ready to read") is
`PageDisplayProjection.displayReady`; for a live page it is
`isTranslationDisplayShapeReady()`:

    cleanedImageName published (isCleanedImageReady: inpaint READY +
      current inpaintRevision)  AND  translation READY/PARTIAL  AND
      renderStatus READY  AND  some non-blank translated block

(`model/PageDisplayProjection.kt:140-144`, `model/PageTranslationState.kt:31-34`,
`:70-71`). The reader's gate is the same predicate
(`displayImageName`, `PageTranslationState.kt:20-21`).

The WRITE that flips a page to display-ready is any durable store emission
whose post-state satisfies the above: `publishLocked` →
`promoteDisplayIfReadyLocked` (`ChapterTranslationStore.kt:2010/:2404`) —
the committed display bundle + manifest committed pointer. Promotion is
gated on `hasRenderedResult`, i.e. it requires **renderStatus == READY**.

Who writes `renderStatus = READY` in the flagged envelope lane:

1. **The inpaint lane's render-terminal stamp** —
   `BatchLaneWorkers.kt:797-820` (inside `NativeLaneWorker.runInpaintStage`,
   right after the cleaned-image publication substage at :718). Gated on the
   lane's in-memory `target.translationStatus` being ALREADY READY/PARTIAL
   at inpaint time. This is the ride-along the fifth implementer found.
2. **`OverlapScheduler.stampRenderTerminalOrphans`** (`OverlapScheduler.kt:421`)
   — order-inverted pages (inpaint committed before translation, T934 track I
   decoupled candidacy — `nextInpaintCandidate` :502 admits OCR-final pages
   regardless of translation). Called ONLY from `drainSerial()` :373, i.e.
   ONLY at FINALIZE — ONE bounded pass; a denied Render lease or a rejected
   write silently skips the page ("a later run's drain retries it").
3. `ChapterProfileBatchCoordinator.stampAdoptedRenderTerminal` (OCR-preflight
   adoption only).

The D2 machinery (`onInpaintCommitted` →
`BatchRenderJoin.publishPersistedLayoutForCompletedPage`,
`BatchRenderJoin.kt:597-620`, FF-02 layout-plan sidecars) is NOT part of
display-readiness: with the FF-02 flag OFF it returns false at :598 for
every page. **`layouts=0` in the COMPLETE line is exactly this** — the FINALIZE
publication sweep ran and published nothing; it is a red herring for the
count and contributed nothing to the 152.

## 2. Root cause of the lag — and of the frozen 52

With decoupled inpaint candidacy the overlap/serial lane regularly inpaints
a page BEFORE its envelope translation commits. For every such page the
in-lane stamp (1) sees translation PENDING and does not fire; the later
`mergeTranslation` envelope commit does not stamp either. The page is then
translate+inpaint COMPLETE with `renderStatus PENDING`:

- `promoteDisplayIfReadyLocked` never fires (needs render READY) — no
  committed bundle, reader gate null, page shows ORIGINAL_ONLY;
- the only in-run repair is FINALIZE's orphan sweep (2) — before
  879fc8f it did not exist at all (run6, 2026-09-17);
- the stranded sweep cannot see it: `t924PageTerminalAtFinalize`
  (coordinator :2648) treats translation READY/PARTIAL as terminal —
  **work-complete-but-display-frozen pages are invisible to every
  FINALIZE reconciliation**.

Hence the run signature: ready(display-commit) trails cleaning by the
order-inverted fraction (~4.8 vs ~8.2 pages/min — the lag accumulates all
pass), and the tail FREEZES the moment the last translate+inpaint finishes:
COMPLETE publishes (:2120-2168), the wrapper tears the overlap loop down,
NPU released — 204/206 translated+inpainted, only 152/206 display-ready,
52 silently stranded between the sweeps.

## 3. Fix design

### (a) FINALIZE drain-before-COMPLETE (`ChapterProfileBatchCoordinator.kt`)

New FINALIZE step 2b (`drainFinalizeAndComplete`, :2075-2090), between
`drainSerial()` and the layout sweep, both lanes (AI envelope tail and the
standard tail funnel into the same finalize; ST-14 resume re-enters it):

- `displayTailPending` (:3217) — the exact tail predicate: translation
  READY/PARTIAL + inpaint READY + cleaned image + a translated block +
  renderStatus PENDING. Deliberately NARROW: textless terminals, genuinely
  unfinished pages (stranded sweep's job — never swept as failures here,
  preserving the stranded fix), and already-stamped pages are excluded.
- `drainDisplayTailBeforeComplete` (:3246) — bounded to
  `MAX_DISPLAY_TAIL_DRAIN_PASSES = 3` passes (:4142); each pass re-stamps
  the remaining tail via the generalized stamp and re-derives pending from
  FRESH snapshots, so a stale-write rejection (group-commit moved
  artifactPageVersion) heals on retry; a MANUAL Render owner persists
  across all passes by design.
- `stampRenderTerminalIfDisplayComplete` (:3148) — generalized from
  `stampAdoptedRenderTerminal` (now a delegating wrapper, :3124), returns
  success. One deliberate hardening: the page is snapshotted AFTER the
  Render-lease acquire (the `stampRenderTerminalOrphans` idiom) — the
  original pre-acquire snapshot fenced on a null token, so
  `updatePageGuarded` rejected every FREE page with "page lease token
  required" (the adopted-page stamp silently no-op'd for them). Returns
  true for already-stamped pages (idempotent).
- A page still pending after the last pass takes the SPECIFIC typed
  terminal `persistDisplayTailFailure` (:3309) — durable
  `LAYOUT`-stage `FAILED_RETRYABLE`/`TRANSIENT` record + live
  `renderStatus = FAILED` flip + errorMessage, carrier
  "display commit did not land at FINALIZE", reason from
  `displayTailFailureReason` (:3281) naming the blocking evidence — the
  stranded fix's carrier/reason style. Best-effort: a rejected record
  keeps the page pending, where the next FINALIZE re-drains it. The
  stranded sweep then skips the page (translation READY is terminal) —
  no double-failure, satisfying "display-incomplete-but-work-complete
  pages must NOT be swept as stranded".
- The run still completes: COMPLETE publishes with two new counters
  `displayTailDrained` / `displayTailFailed` (:4132-4135, additive keys,
  ~21 total « the 32-key bound) and they ride the COMPLETE log line.
- Resume coherence: every drain artifact is store state; a run killed
  mid-drain re-enters FINALIZE and re-runs the idempotent drain.

### (b) Run outcome "Ready (Warnings)" (`BatchProgressReconciler.kt`)

`reconcileFlaggedCompleted` (:158) now counts translation-terminal pages
with `renderStatus FAILED` into a warning bucket; the completed chapter
projects `READY_WITH_WARNINGS` when any exist (:195) instead of a clean
TRANSLATED. Both callers benefit: the pass-1 terminal snapshot
(`BatchChapterTranslator.kt:1010` — the `outcome=` log line) and the
queue-completion projection (`ChapterTranslator.kt:829`). The page itself
surfaces in the existing pages-need-attention groups
(`isStageFailed || errorMessage != null` inclusion, stranded-fix group key)
with its specific reason.

### (c) Commit-settle nudge (`ProfileEnvelopeExecutor.kt`)

New constructor seam `onCommitSettled: () -> Unit` (:125-135), invoked at
the end of `commitPages` when at least one page was accepted (:1088).
The coordinator wires it to `overlapScheduler?.notifyCandidatesChanged()`
(:1896) — public API only, scheduler untouched. Per the scheduler's KDoc
the settle (not window-CLOSE) is the earliest a deferral can be invalid;
this is exactly the wake the `inpaintOne` KDoc already promised ("T934
track V wakes the loop at every envelope commit boundary") — the seam just
makes it real. The lane drains deferred candidates at every commit
boundary instead of waiting a whole envelope cycle for the next window's
open; keeps one-native-job-at-a-time, the write-slot pre-check, the
per-drain one-attempt rule, and every lease/write-gate fence untouched.

## 4. Tests (`DisplayTailDrainTest.kt`, new — Stage7FinalizeCoordinatorTest idioms)

1. `an order-inverted display tail is committed by finalize and COMPLETE
   carries every page display-ready` — p2 seeded inpaint-committed BEFORE
   its translation (the run6 shape); asserts COMPLETE, tail counters zero,
   every page translate+inpaint+render terminal, `hasRenderedResult` and a
   promoted `committedDisplayPage` for all 3 — displayReady == translated
   + cleaned count.
2. `a display tail page the drain cannot finish takes the typed terminal
   and the run completes as a warning` — MANUAL Render owner attaches
   mid-run (never preempted by BATCH); the tail page ends render FAILED
   with the specific carrier, durable LAYOUT FAILED_RETRYABLE/TRANSIENT,
   work intact; healthy page display-committed; `reconcileFlaggedCompleted`
   → READY_WITH_WARNINGS; stranded=0; COMPLETE.
3. `the commit settle nudge drains a slot-deferred page before the next
   window opens` — recording gate + lane events; the page slot-busy during
   window 1 inpaints on the post-commit-settle nudge BEFORE dispatch-2
   records (pin: remove the wiring and the inpaint lands inside window 2);
   3 overlap inpaints, 0 serial; single-threaded deterministic.

Existing suites reasoned safe: Stage7/Stranded fakes never publish a
cleaned image → their pages are never tail (no typed failures, counters
unchanged); StandardPipelineCoordinatorTest drives the STANDARD seam (no
executor → no nudge) and asserts overlap+serial totals only;
reconciler tests use renderStatus PENDING; all phaseCounters assertions
are per-key (new keys additive). NOT run per constraints — Main Leader
owns the test gate.

## 5. Risks & residuals

1. **Re-drive of a typed-FAILED page is not automatic**: the drain
   predicate requires renderStatus PENDING, so a page typed-failed this
   run is not re-stamped by the next run's drain — the reader Retry path
   (FAILED_RETRYABLE) or the reader's own stranded sweep owns it. Follow-up
   if field data wants drain-side re-drive (must stay adoption-safe: a
   genuine render failure must never be silently promoted).
2. **Chapter status after restart**: under a COMPLETE run record
   `completedRunRecordStatus` counts the typed-failed page "done" via its
   open candidate pointer (pre-existing shortcut shared with stranded
   pages), so the durable re-open projection can read TRANSLATED; the
   per-page failure record and reason remain. Same residual as the
   stranded fix.
3. **Extra overlap drain passes** (one per envelope commit) add candidate
   scans; each pass attempts each candidate at most once and the lane
   stays strictly serialized — no new spin surface, but window/serial
   counter SPLITS may shift slightly on device (totals unchanged).
4. The generalized stamp now succeeds for free-page adoption stamps that
   previously bounced off the lease fence (behavior improvement, aligned
   with the stamp's documented purpose; partially addresses the stranded
   fix's follow-up 1).
5. Deliberately NOT touched: `OverlapScheduler.kt` internals (public nudge
   only), `BatchWriteGate.kt`, `PageStageLeaseTable.kt`,
   `ReaderViewModel.kt`, reader UI.

## Files changed

- `app/src/main/java/eu/kanade/translation/pipeline/batch/ChapterProfileBatchCoordinator.kt`
  — FINALIZE step 2b (drain + typed terminal + counters + log), generalized
  render-terminal stamp, executor nudge wiring.
- `app/src/main/java/eu/kanade/translation/pipeline/batch/ProfileEnvelopeExecutor.kt`
  — `onCommitSettled` seam + commit-settle nudge in `commitPages`.
- `app/src/main/java/eu/kanade/translation/pipeline/batch/BatchProgressReconciler.kt`
  — display-failed warning bucket in `reconcileFlaggedCompleted`.
- `app/src/test/java/eu/kanade/translation/pipeline/batch/DisplayTailDrainTest.kt`
  (new) — the three tests above.

---

# Addendum (review round 2): promotion rejection root cause + drain hardening

Review gate evidence: every `stampRenderTerminalIfDisplayComplete` guarded
write was rejected `ARTIFACT_PUBLICATION_FAILED`, so ALL tail pages (not just
the MANUAL-blocked one) took the typed terminal — which also broke
`D10PartialDownloadAdmissionTest` (its worked page p1 carries the same frozen
tail shape) by typing a healthy page FAILED (`doneCount` 2 -> 1).

## 1. Root cause (file:line)

The render-terminal stamp is the FIRST write in an order-inverted page's
lifecycle that carries BOTH `hasRenderedResult` and a `cleanedImageName`, so
it is the first to run the committed-display promotion inside
`publishLocked` -> `persistArtifactMutationLocked`
(`ChapterTranslationStore.kt:2280-2322` with group commit off — the flag
defaults false and only `BatchChapterTranslator.translateBatchTraced:294`
turns it on; the :2225 T930 Slice B2 branch is the same promotion under
group commit). The promotion is
`ChapterArtifactStore.promoteLiveCandidateOnce`
(`ChapterArtifactStore.kt:1446-1577`), and its **display-base validation**
rejects the write:

- `ChapterArtifactStore.kt:1474-1483` —
  `displayBaseIsValid(layout.legacyCompanionImageFile(cleanedName), source)`
  requires the cleaned companion file to exist on the chapter's document IO
  with decodable dimensions matching the page's source identity
  (`displayBaseIsValid`, `ChapterArtifactStore.kt:2023-2034`).
- In the DisplayTailDrainTest harness the fake overlap lane publishes the
  `cleanedImageName` REFERENCE (like the real lane) but no companion bytes
  existed, and the store used the default
  `BitmapFactoryCleanedImageProbe` (android.graphics, unavailable on the
  JVM) -> every stamp rejected -> `publishLocked` false ->
  `ARTIFACT_PUBLICATION_FAILED`.
- Production is NOT affected by this path: the real inpaint lane writes the
  companion JPEG bytes before publishing `cleanedImageName` and inpaint
  preserves dimensions, so a legitimate tail stamp promotes. The T930 branch
  itself is sound for this write shape — no store change.
- D10's second-run p1 is the DOCUMENTED fixture gap (test (b) comment: "the
  final display-promotion publication is rejected on this fixture"): its
  `freshStore` seeds no companion bytes and no probe stub, so p1's in-lane
  stamp has always silently failed there (non-fatal,
  `BatchLaneWorkers.kt:813-820`). The drain re-ran the same doomed stamp and
  — unlike the lane — typed-failed the healthy page.

## 2. What changed

1. `ChapterProfileBatchCoordinator.kt` — removed the 3 temporary T934DIAG
   `println` blocks and reworked the stamp's contract from `Boolean` to a
   sealed `RenderStampOutcome`: `Committed` /
   `PublicationRejected` (reason `ARTIFACT_PUBLICATION_FAILED`, the
   `REJECTED_ARTIFACT_PUBLICATION` companion const) / `Blocked(reason)`
   (lease owned elsewhere, page missing, evidence gone, or a non-publication
   write rejection). `drainDisplayTailBeforeComplete` now types-fails ONLY
   pages whose last attempt was `Blocked`; publication-rejected pages stay
   pending for the next run's re-drain (invisible to the stranded sweep,
   translation READY is terminal there) and count in neither drain counter.
   `stampAdoptedRenderTerminal` is unchanged in behavior (it ignores the
   outcome, as before).
2. `DisplayTailDrainTest.kt` — promotion fixture fidelity, the coexistence
   harness's documented display-commit recipe: `@BeforeEach` seeds the
   cleaned companion bytes for the page set under
   `Chapter 1_images/<key>.cleaned.jpg` (read through the real
   `UniFileChapterDocumentIo`) and stubs the documented
   `ChapterTranslationStore.artifactImageProbe` JVM seam with
   `ProbedImage(100, 160)` (the pages' source identity);
   `@AfterEach` restores the probe. Test bodies unchanged.

## 3. Gate expectations

- DisplayTailDrainTest (3 tests): stamps now promote in-harness — all pages
  display-committed in tests 1/3; test 2 keeps exactly the MANUAL-blocked p1
  on the typed terminal (Blocked, lease-owned reason) with p2 committed.
- D10PartialDownloadAdmissionTest: p1's stamp is publication-rejected (the
  documented fixture gap), p1 stays pending (render PENDING, no durable
  failure) -> `reconcileFlaggedCompleted` counts both pages done, no
  stranded, no failed — original assertions hold.
- Stage7FinalizeResumeCoordinatorTest ("Failed to close extension context"):
  its harness publishes no `cleanedImageName`, so `displayTailPending` never
  matches and the drain writes NOTHING in that harness — the failure is not
  the drain; left as the known Windows temp-dir flake for the Main Leader's
  gate to confirm.

## Round 3 addendum (2026-09-17): the typed-fail record and the closure CAS

Gate input: DisplayTailDrainTest test 2 fails at the `durableFailure("p1",
LAYOUT)` assertion (flip + carrier present, metadata null), and
BatchDispatchResumeWiringTest run 1 stalls at record state TRANSLATE.

### F1 — `durableFailure("p1", LAYOUT)` null while render-FAILED sticks

- Write side: `persistDisplayTailFailure` builds the metadata with
  `stage = LAYOUT` (coordinator :3373 old numbering) and calls
  `ChapterTranslationStore.persistDurableStageFailure`
  (ChapterTranslationStore.kt:812-859): page flip at :841, ONE atomic
  `persistArtifactMutationLocked(durableFailure = ...)` publication at
  :842-848; on `false` the flip is rolled back (:849-851), so
  "flip present" strictly implies "publication returned true".
- Read side: `durableFailure` -> `StoreStatusProjector.durableFailure`
  (StoreStatusProjector.kt:53-56) reads
  `artifactManifest.durableFailures["$pageKey:${stage.name}"]` — the SAME
  facade field every publication refreshes. No surface mismatch.
- The failure record itself is written generation-less
  (ChapterArtifactStore.kt:1322-1338) precisely so `cancelCandidate` cannot
  strip it (:1806-1808 only removes candidate-STAMPED records), and no
  writer removes a LAYOUT key. So "committed but later dropped" is
  impossible — the publication itself must have left the single-publication
  path.
- ROOT CAUSE: `persistArtifactMutationLocked` derived the publication
  origin from the CURRENT LEASE HOLDER
  (ChapterTranslationStore.kt:2166-2168 old numbering,
  `pageLeases[pageKey]?.origin?.toArtifactOrigin()` first). Test 2's p1 is
  blocked exactly BECAUSE a MANUAL reader lease owns its Render stage, so
  the typed-fail persist derived `origin = MANUAL` against a BATCH
  candidate and took the :2173-2199 branch:
  `cancelLiveCandidate` (destroys the BATCH candidate holding the page's
  translated work product) + `openCandidate(origin = MANUAL)` +
  `persistLiveCandidateAndFailure(origin = MANUAL)` — a THREE-publication
  chain against a candidate the failure writer does not own, instead of the
  ONE atomic page+failure publication the API documents
  (ChapterTranslationStore.kt:806-810). Any drift inside that chain leaves
  exactly the observed state: the in-memory flip sticks while the durable
  failure record is missing. The reviewer's suspect (a) — a null-token
  fence — is disproven: the persist snapshots AFTER the stamp attempts, the
  MANUAL token is current, and `pageWriteRejection`
  (ChapterTranslationStore.kt:1568-1594) passes.
- FIX (store, ChapterTranslationStore.kt:2161-2181 new numbering): a
  durable-FAILURE publication with a live candidate now keeps the
  CANDIDATE's provenance (`origin = record.candidate.origin`) — the
  cancel/reopen chain is skipped and the flip + failure land in ONE
  publication. Non-failure writes are unchanged (lease-first provenance,
  the compatibility-writer case intact). This also repairs the stranded
  sweep's `persistEnvelopeStructuralFailure` for reader-owned pages.
- FIX (coordinator, `persistDisplayTailFailure`): result-aware — captures
  the `PatchResult`, retries ONCE against a fresh snapshot (heals a fence
  drifted by the drain's intervening stamp attempts), and logs the final
  rejection reason at WARN (the `persistDurablePreflightFailure` idiom,
  coordinator :4398-4412) instead of only catching exceptions.

### F2 — run 1 never reaches COMPLETE, record state TRANSLATE

- The commit-settle nudge is INNOCENT: the call site is
  `ChapterProfileBatchCoordinator.kt:1896`
  (`onCommitSettled = { overlapScheduler?.notifyCandidatesChanged() }`),
  invoked after commit at `ProfileEnvelopeExecutor.kt:1088`, and
  `notifyCandidatesChanged` is a non-blocking `trySend` on an UNLIMITED
  channel (OverlapScheduler.kt:211-213) — the drain never runs inline on
  the executor's dispatcher, and nothing in the commit path can suspend on
  it. Production semantics ("wake at every commit settle") are unchanged.
- The failure signature (reconciliation completed non-null, disk record
  stays TRANSLATE) is the closure path: `drainFinalizeAndComplete` step 6
  publishes the single COMPLETE record via `publishRecord`
  (coordinator :3964-3999), which CASes against the FACADE manifest
  snapshot (:3968) — and a non-Committed outcome takes the typed pause
  (:2181-2193 new numbering, RUN_CLOSURE_REJECTED_REASON), leaving the
  durable record at its last TRANSLATE-phase publication.
- Every artifact-store transaction carries a one-shot fresh-baseline retry
  (`retryOnStaleManifest`, ChapterArtifactStore.kt:1999-2014) EXCEPT the
  coordinator's closure publication, which used the facade snapshot
  as-is with NO retry — the one seam where drain-time store activity
  (stamps, promotions, staged flushes) could leave the closure holding a
  snapshot that is merely stale and turn that into a typed PAUSED run.
- FIX (coordinator, drainFinalizeAndComplete :2141-2205 new numbering):
  the COMPLETE publication retries ONCE — re-reading the durable manifest
  into the facade (`artifact.readManifest()`) before re-publishing the
  identical record (content-addressed sidecar, idempotent). A genuine
  rejection still pauses; a stale-snapshot rejection now commits, which is
  what the store's own retry idiom does at every other seam.

### Gate expectations (round 3)

- DisplayTailDrainTest test 2: p1 takes the typed terminal in ONE atomic
  publication — render FAILED + carrier + `durableFailure("p1", LAYOUT)`
  non-null, FAILED_RETRYABLE/TRANSIENT. Tests 1/3 unchanged (no failure
  publications, no lease-held pages).
- BatchDispatchResumeWiringTest: run 1's closure lands on the fresh-baseline
  retry -> record COMPLETE on disk; run 2's zero-work resume unchanged.
- D10 and Stage7FinalizeResumeCoordinatorTest: unchanged from round 2
  (no durableFailure publications on their paths).
