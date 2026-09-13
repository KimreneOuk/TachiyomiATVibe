# T924 Phase 4 Wave B — standard-engine batch lane: lifecycle + integration

Implementer report. Worktree `TachiyomiAT-t924-impl`, branch `t924/batch-profile-pipeline`,
base HEAD `3a4fa8b` (Wave A). **Nothing committed** — all changes working-tree only:
1 main-source file + 3 modified test files + 1 new test file.

Full sweep `./gradlew :app:testStandardDebugUnitTest --tests "eu.kanade.translation.*"`:
**244 suites / 1794 tests / 0 failures / 0 errors** (Wave A baseline 243/1788 →
+1 suite, +6 tests, zero regressions).

---

## Task 1 — empty/textless-page checkpoint CLOSE gap (fix + RED/GREEN)

### Investigation (mechanism confirmed)

For a genuinely blank page (OCR returns ZERO blocks), the OCR merge through
`ChapterTranslationStore.publishLocked` is gated by `shouldPersistUpdate`
(`ChapterTranslationStore.kt:2519`): no rendered result, no blocks, no cleaned
image, no stage failure → **transient**. Because the batch pre-registers every
page in the artifact manifest, `artifactManifest.pages.containsKey(pageKey)` is
already true, so `persistArtifactMutationLocked` is skipped entirely — the page
opens **no candidate**. At checkpoint time `OcrReadyPageRef.candidateGenerationId`
is null (derived from `artifactPage?.candidate?.generationId`), so
`checkpointPage` (`ChapterProfileBatchCoordinator.kt:2824`) takes the
**adopt-committed branch** of `ChapterArtifactStore.checkpointOcrOnce`, which
requires `page.committed` — also absent — and rejected with
`"committed bundle missing for checkpoint adoption"`.

Outcome was BAD, exactly as Wave A anticipated: CHECKPOINT_REJECTED → typed
failure-ledger charge + `cancelPageStageWork` + `BatchPass1Status.FAILED` — a
healthy blank page fails the whole flagged run (both lanes; the preflight is
shared). Order-dependent too: a blank page OCR'd *first* would coincidentally
open a candidate and pass — the failure only manifests for blank pages that
follow a durable page.

### Fix (option (a)-equivalent, minimal blast radius — ONE main-source change)

`app/src/main/java/eu/kanade/translation/artifact/ChapterArtifactStore.kt:683-703`
(`checkpointOcrOnce`, adopt branch): when `page.committed == null`, the
adoption is allowed if the OCR snapshot itself is the no-content work product
(`ocrSnapshot.ocrStatus == READY && ocrSnapshot.blocks.isEmpty()`); every
content-bearing page still requires the committed anchor (fail closed). The
transaction publishes the OCR snapshot sidecar + TEXTLESS/READY `ocr` stage
record + `manifest.ocrCheckpoints` pointer — the page's complete OCR content is
durable in this very transaction, so adopting without a committed bundle is
exactly as durable as the candidate CLOSE.

Why this shape and not extending `shouldPersistUpdate` with
`isTextlessTerminal`: that would change FF-01-OFF durable behavior (legacy
blank pages would gain committed textless bundles and stop re-running) —
forbidden by the OFF byte-identical rule. The chosen fix is reachable ONLY via
the flagged preflight (`store.checkpointOcr` facade has exactly one production
caller: `ChapterProfileBatchCoordinator.checkpointPage:2836`).

### Predicates stay consistent (verified, no change needed)

- `finalizePostOcrStage` (PostOcrStageSemantics.kt): blank pages get
  translation/render SKIPPED; `mergeOcrLocked` copies render/inpaint but never
  translationStatus — the store page stays PENDING until the tail's legacy
  textless commit.
- `standardPageTerminalAtTranslate` / `t924PageTerminalAtFinalize` /
  `pageWorkProductResolvable.isNoTextTerminal`: the fixed page flows through
  the tail's normal textless commit and the finalize terminal; the blank
  page's `isNoTextTerminal` (live SKIPPED) satisfies the LI-2 evidence gate.
- Resume parity (the deliberate decision point): the checkpoint IS published,
  so `reusableCheckpointFingerprint` (manifest.ocrCheckpoints) reuses the page
  with **zero re-OCR** — option (b)'s re-pay concern does not arise.

### RED/GREEN evidence

RED (`StandardPipelineCoordinatorTest.genuinely empty textless page...`, :700):
`expected:<COMPLETED> but was:<FAILED>` at the first assertion, 2-page chapter
(p2 zero-block). GREEN after the fix: run COMPLETED /
`TRANSLATE_COMPLETE_REASON`; p2 SKIPPED/SKIPPED with zero provider calls;
`manifest.ocrCheckpoints.keys == {p1, p2}`; second run →
`RESUME_COMPLETE_REASON` with zero OCR and zero seam invocations. Test also
pins that the genuinely blank page becomes a TRUE `isTextlessTerminal`
(zero blocks) — unlike blank-TEXT pages (whitespace block present), which are
`isNoTextTerminal` evidence only (see Task 2d).

---

## Task 2 — flag-flip lifecycle parity pins (tests only; no defects surfaced)

### a) OFF + COMPLETE — deliberate asymmetry pinned
`BatchDispatchResumeWiringTest.kt:546` — `flag-off dispatch over a standard-lane
COMPLETE with unrendered pages does not treat the chapter as finished`.
Standard-lane COMPLETE record (`standard:google`, corpus fingerprint) over
{textless p0, READY-unrendered p1 (render PENDING, candidate-only evidence)}.
Pure pins: `decideResume(record, OFF) == TreatAsFinished` yet
`resumeCompletedOutcome(..., allPagesDisplayCommitted=false) == null`; shell
level: the legacy schedule STARTS (transport start signal awaited) and the
record stays byte-untouched. KDoc documents the asymmetry: the ON-side
coordinator gate (`pageWorkProductResolvable`) accepts candidate snapshots, the
OFF-side shell gate (`activeRunPagesDisplayCommitted`) is deliberately STRICT
(committed || textless) because dropping to legacy re-derives display safely.

### b) OFF mid-run — DropToLegacy, sidecars untouched
`BatchDispatchResumeWiringTest.kt:618` — `flag-off dispatch over an interrupted
standard-lane TRANSLATE record starts the legacy schedule`. Durable standard
record at TRANSLATE (corpus fingerprint + COUNTER_STOP, the Wave A published
shape) over unworked pages: `decideResume == DropToLegacy`, legacy standard
schedule starts (transport start signal), record byte-equal + activeRun pointer
unchanged (Case-2 idiom, standard-lane records).

### c) ON re-dispatch over a stale non-FINALIZE record (investigator risk 7)
`StandardPipelineCoordinatorTest.kt:756` — `re-dispatch over an interrupted
standard run continues the same run id and completes exactly once`. Run 1
pauses mid-translate (typed PAUSED seam hook, `StandardSeam.pauseOnPages`,
:281) leaving a TRANSLATE record; run 2 (flag ON, same config):
`resumeFinalizeOrComplete` → null → same-runId continuation with a fresh
RUN_SNAPSHOT, checkpoints reused (zero OCR), only the uncommitted pages
re-seamed (terminal-skip parity), final record COMPLETE with
`COUNTER_RUN_COMPLETE == 1` and runId unchanged — no crash, no double closure.

### d) ON + COMPLETE zero-work resume with the SKIPPED-textless evidence shape
`StandardPipelineCoordinatorTest.kt:812` — `flag-on zero-work resume accepts
SKIPPED textless pages that are not full textless terminals`. New fixture
fidelity: `ocrPage` now mirrors `finalizePostOcrStage` exactly (blank-text →
render SKIPPED; inpaint SKIPPED only without mask boxes). Pins: an unmasked
blank-TEXT page (inpaint SKIPPED) and a masked one (inpaint PENDING) are BOTH
`isTextlessTerminal == false` (blocks present) — their zero-work resume
evidence is the `isNoTextTerminal` extension (`translationStatus == SKIPPED`),
which `resumeFinalizeOrComplete`'s LI-2 gate accepts: RESUME_COMPLETE with zero
OCR, zero seam calls, pointer unchanged.

---

## Task 3 — real-shell integration (coexistence harness, FF-01 ON + STANDARD)

Harness-only changes (`TranslationCoexistenceHarness.kt`); **zero production
changes** — the shell resolves the standard translator through the normal
EngineLane path (`textTranslatorFn = { engineLane.textTranslator }` =
`FakeTransportTranslator`).

1. `create` params (:195, :204): `flagProfilePipelineOn` (seeds
   `translation_batch_profile_pipeline=true` in `harnessPreferences`, :836) and
   `transportWaitsForNativeStage` (:263) — the legacy per-page "native inpaint
   lands before the paid call returns" serialization would deadlock the flagged
   lane (a page's inpaint is only a candidate AFTER its own translation commit,
   OverlapScheduler.nextInpaintCandidate), a harness-invented ordering, not a
   production one.
2. `artifactAuthorityStore(pageKeys)` (:869): the durable recipe —
   ARTIFACTS-authority store over in-memory document IO (the
   BatchDispatchResumeWiringTest authority-flip recipe), fresh PENDING pages.
3. `batchAnalyze` fake (:473-507): when flagged, performs the production OCR
   merge (`store.mergeOcr` under the live OCR lease read from the snapshot) —
   the REAL `SinglePageOnnxPhase.analyzePage` persists the OCR product, the
   legacy harness fake left persistence to the per-page commit, and the flagged
   preflight checkpoints the STORE's per-page OCR state. Also records decoded
   geometry (imgWidth/imgHeight) and the current inpaint revision — both
   required by the checkpoint's SourceIdentity/revision gates and both real in
   production engines.
4. `createStandard(pageKeys)` (:907): the one-call recipe binding all of it.

New `StandardPipelineCoexistenceTest.kt` (1 test) drives ONE chapter end-to-end
through the REAL shell (ChapterTranslator → pipeline → BCT → flagged
coordinator + standard seam) and pins the Director contract:

1. legacy SBC NOT constructed — by behavior: transport exactly once per page +
   activeRun COMPLETE record with `providerKey "standard:mlkit"`,
   `flagProfilePipeline=true`, `COUNTER_RUN_COMPLETE=1` (legacy never
   publishes run records);
2. full OCR before any translate — every decode (NATIVE_ACQUIRE) precedes the
   first PROVIDER_START (observed log: p0,p1 decodes → PROVIDER_START/END p0 →
   PROVIDER_START/END p1 → inpaint decodes; the legacy page-serial lane would
   show exactly one decode before the first paid call);
3. single COMPLETE; pages end `translationStatus READY`, `renderStatus
   PENDING` (`"tr-"` stamps present); zero RENDER barrier arrivals (no in-pass
   render); reconciliation TRANSLATED, no stranded pages;
4. glossary snapshot equal before/after (and empty).

Bonus pin: `manifest.ocrCheckpoints == {p0, p1}` — the real-shell path lands
the checkpoint-resume parity end to end.

During bring-up the harness surfaced one pre-existing gate working as designed
(the first run failed with `checkpoint invalid: stale inpaintMaskRevision: 0`
— the fake OCR product carried revision 0); fixed in the fake, not the gate.

---

## Suite evidence

- RED: `StandardPipelineCoordinatorTest` T6 `expected:<COMPLETED> but was:<FAILED>`
  (preflight checkpoint rejection on the blank page). GREEN after the
  ChapterArtifactStore fix: 7/7 in that suite.
- MUST-green battery (one invocation, 29 suites / 108 tests / 0 failures):
  StandardPipelineCoordinatorTest (7), ProfilePipelineDispatchGateTest (6),
  Stage7FinalizeCoordinatorTest (2), Stage7FinalizeResumeCoordinatorTest (3),
  OcrPreflightFlagOffMidRunTest (5), BatchDispatchResumeWiringTest (5),
  OcrPreflightCoordinatorTest (4), OcrPreflightRejectedMidRunDurabilityTest (2),
  ProfileEnvelopeDispatchTest (10), BatchPostPassProjectionTest (4),
  OverlapSchedulerTest (4), T918CancelledBatchRestartTest (1), the full
  coexistence package (D1/D2/D3/D5/D6x2/D7/D8/D9/D10/D11/P5/NormalManga/
  StandardLaneMultiPage/StandardPipelineCoexistence — 36), CheckpointOcrTransactionTest (13),
  ChapterResetPreflightTest (2).
- `*Coexistence*`/`*Preflight*`/`*Checkpoint*` grep: all matching suites ran in
  the battery and again in the full sweep — green.
- FULL SWEEP `eu.kanade.translation.*`: **244 suites / 1794 tests / 0 failures
  / 0 errors**.

## Decisions and deviations

1. **Fix shape (Task 1):** checkpoint-without-committed-bundle adoption for the
   zero-block READY OCR snapshot, instead of making empty-block writes durable
   in `shouldPersistUpdate`. Rationale: `shouldPersistUpdate` is shared with
   the FF-01-OFF path (legacy blank pages would durably commit as textless and
   stop re-running — a legacy semantics change); the chosen gate is reachable
   only from the flagged preflight, fixes both lanes, and still satisfies the
   (a) goal — resume + ST-05-style reuse with zero re-OCR (pinned in T6 pass 2).
2. **Fail-closed boundary:** adoption without a committed bundle requires
   `ocrStatus READY && blocks.isEmpty()`; any content-bearing page without a
   committed bundle still rejects (content-bearing pages always open
   candidates, so this remains a pure anomaly guard).
3. **Harness fake fidelity (Task 3):** `batchAnalyze` now records decoded
   geometry + current inpaint revision, and performs the production OCR merge
   only when FF-01 is ON — legacy harness behavior (OCR persisted via the
   per-page commit) is unchanged byte-for-byte.
4. **Transport wait drop (Task 3):** documented above; the flagged lane's
   same-page ordering (commit → later inpaint via OverlapScheduler candidacy)
   is structurally guaranteed, so the legacy wait models nothing real there.
5. **Fresh-process COMPLETE resume of the blank page (documented, not a
   defect):** `pageWorkProductResolvable` accepts the blank page from LIVE
   store state (SKIPPED); after a process death the live state is gone and the
   zero-work gate re-runs the run — the checkpoint makes that re-run zero-OCR,
   the tail re-commits the textless terminal cheaply. Deliberate: the blank
   page has no committed/candidate snapshot to read (nothing was ever rendered
   or translated); the alternative (committed blank-page bundles) would change
   OFF-path durability.

## Left for the pre-A/B checklist

- Fresh-process zero-work resume for chapters containing blank pages re-runs
  the (checkpoint-reused) standard tail to re-commit the textless terminals —
  cheap, but worth remembering when reading A/B wall-clock numbers for
  blank-page-heavy chapters.
- The legacy lane still treats blank pages as transient (re-OCRs every
  dispatch). Unchanged by design in this wave; if the Director ever wants
  legacy blank-page durability, that is a separate decision (it changes OFF).
- `COUNTER_PAGES_TRANSLATED` counts the blank page's textless seam commit
  (legacy parity, same as Wave A's whitespace fixture).
