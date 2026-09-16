# T934 — Verification checklist

Legend: [ ] pending · [x] verified by Main Leader with recorded evidence.
Every [x] requires: diff reviewed + targeted test output (names, counts) +
file:line. No assertion weakened anywhere (checked per lane).

## W0 — Baseline

- [x] W0.1 Dirty diff classified (E-fixes vs prior in-flight work): prior-session
      E-fixes + in-flight milestone tail; committed wholesale as baseline `25a7589`.
- [x] W0.2 Full suite green: 26 baseline failures (team/w0-baseline-failures.md)
      repaired by the W0.5 lane across 3 rounds (GroupCommit leak teardowns,
      render-stamp conversions, retention race — see commit ce8d925), plus the
      flake root in SinglePageHttpRenderPhase. Final: BUILD SUCCESSFUL,
      2020 tests, 0 failed (run 2026-09-17 /tmp/t934_full_suite6.log).
      Residue: 4 order-dependent flakes in one run (run 5) — all green in
      isolation and in the immediate clean re-run; signatures handed to the
      repair lane as a follow-up (see "Flake follow-up" below).
- [x] W0.3 Branch + baseline: `t934/resume-rebuild-and-parallelism` from `8010961`,
      baseline commit `25a7589`.
- [x] W0.4 Tree clean after baseline — verified 2026-09-16.

## R1 — Lease abort/re-work fix (commit 845975d)

- [x] R1.1 Owner-proof heal in BatchWriteGate: token-mismatch reject →
      re-acquire BATCH lease; Denied → typed contention yield; Granted AND
      generation+candidateGenerationId match → identity re-armed FROM THE
      GRANTED LEASE, one retry. `BatchWriteGate.kt` `LEASE_TOKEN_CHANGED`
      branch after the drift heal (diff verified by Main Leader).
- [x] R1.2 Never-heal guards: no heal from bare snapshot re-arm; absent-lease
      ("page lease required") rejects never enter the heal (branch is
      token-mismatch only). Verified in diff + pinned by BatchLeaseFlipHealTest
      resumed-run/manual-owner tests.
- [x] R1.3 Flip-source fix: envelope completion cannot release a page a sibling
      still holds — all 5 ProfileEnvelopeExecutor release sites call
      `releasePageStageLeaseIfUnattached` (attaches==0 required);
      PageStageLeaseTable.attaches counter bumps on same-origin re-acquire.
- [x] R1.4 Flip-heal test: BatchLeaseFlipHealTest "a flipped batch token heals
      via owner-proof re-acquire and the write succeeds".
- [x] R1.5 Manual-owner denial test: BatchLeaseFlipHealTest "a manual owner
      denies the owner-proof heal - rejection kept with no write".
- [x] R1.6 Resumed-run identity test: BatchLeaseFlipHealTest "a resumed-run
      candidate identity denies the heal - no retry write".
- [x] R1.7 T1→T2 reproduction test: BatchLeaseFlipHealTest "t1-to-t2
      reproduction - envelope release keeps a re-attached overlap identity
      alive". Class green: 4/4 (verify run 2026-09-16).
- [x] R1.8 Existing fences green unedited: BatchWriteGateHealTest,
      ChapterTranslationStorePhase3Test, D1OriginPriorityTest,
      ProfileEnvelopeDispatchTest all green in targeted runs (2026-09-16/17).
- [x] R1.9 No net assertion weakened — full diff audit by Main Leader
      (plain `releasePageStageLease` byte-identical; Phase3Test contract kept).

## U — Phase truth + simplified UI (commit 818ce68)

- [x] U.1 Phase + payload in the existing projection: TranslationBatchPhase
      appends REBUILDING/RESTORING (no competing enum) + BatchRebuildProgress;
      derived from durable run-record state/counters by
      BatchProgressProjector.rebuildTruthFromRunRecord / withRunRecordTruth
      (live FIRST_PASS window only; OCR_PLAN done==0 is NOT a rebuild).
- [x] U.2 Sheet Simple default (hero + ready chip + progress bar —
      indeterminate during rebuild — + ONE status line + failure notice +
      actions) and persisted Advanced chevron (stages, page grid, failure
      groups, queue detail): TranslationProgressSheet.kt advancedView +
      chevron row + view split; TranslationPreferences.
      translationProgressSheetAdvancedView (default false).
- [x] U.3 Priority chain once in TranslationUiTruth.batchStatusLine:
      isResuming → requestState → queuePosition → pauseReason → rebuild/
      restore → batchPhase; each stage a private helper.
- [x] U.4 BottomReaderBar consumes TranslationUiTruth.readerBarLine —
      REBUILDING → "Rebuilding pipeline…", RESTORING → "Restoring N pages…";
      legacy "Batch X/Y" fallback byte-identical, visibility rule unchanged.
- [x] U.5 "Resuming…" snapshot-driven: synchronous self-reset deleted
      (formerly TranslationProgressSheet.kt:471-480); LaunchedEffect clears
      on phase truth change + bounded 10s escape hatch
      (RESUME_PENDING_TIMEOUT_MS).
- [x] U.6 New copy via truth + NEW string keys only (8 keys in
      i18n-at moko base strings.xml; no existing key modified); existing
      truth assertions unedited (TranslationProgressSheetSubtitleTest,
      TranslationQueuePositionAndPhasesTest green in full suite).
- [x] U.7 New tests: T934RebuildTruthTransitionsTest (13),
      T934ReaderBarTruthTest (12), T934SheetAdvancedViewPreferenceTest (3),
      T934ProjectorRebuildTruthTest (4) — all green.
- [x] U.8 Existing UI-truth/projector tests green in the full suite
      (2020/2020, run 6). Main-leader compile fixes during verification:
      BatchStatusLineKind/BatchStatusLine de-nested to top level (nested
      declarations made sheet/bar imports unresolvable); projector test
      helper converted from runTest-wrapping (returns Unit on JVM) to a
      suspend TestScope extension.

## R2a — Write-time digests + typed adoption failures (wave 2 — pending)

- [ ] R2a.1 Per-page source SHA-256 recorded durably at first admission
- [ ] R2a.2 Run start consumes recorded digests; whole-chapter re-hash removed
- [ ] R2a.3 Test: second run with unchanged sources performs zero source re-hash
- [ ] R2a.4 Test: changed source detected at consumption fails closed
- [ ] R2a.5 Typed adoption-failure reasons + WARN + run-record counters
- [ ] R2a.6 D5/D6/D9/D10/D11 test files byte-identical

## R2c — Consolidation spike

- [x] R2c.1 Report filed: team/r2c-spike.md — Option A (consolidated resume
      snapshot, M) recommended first; Option B (SQLDelight store, L) sized
      and not blocked by A.

## I — Inpaint decoupling (commit c085f0f)

- [x] I.1 Gate relaxed: OCR-final candidacy (nextInpaintCandidate
      `ocrStatus != READY → skip` + `blocks.isEmpty() → skip`), translation
      status irrelevant; textless/already-inpainted/in-flight skips preserved;
      mirrored in the inpaintOne fresh-snapshot re-check.
- [x] I.2 "No inpaint during OCR preflight" enforced: structural (scheduler
      never calls runOcrStage), lifecycle (TRANSLATE-only), and the new
      OCR-final admission layer; pinned by "non-final ocr pages are never
      inpaint candidates" test (green).
- [x] I.3 Bitmap-budget caps unchanged: inpaintMutex one-native-job rule
      untouched; observedMax()==1 asserted in window + I.4 tests.
- [x] I.4 Test: OCR READY + translation PENDING inpainted in-window
      ("ocr-final translation-pending page is inpainted in the window…", green).
- [x] I.5 Test: displayReady still requires translation terminal + cleaned
      image even when inpaint commits first (real store + cleaned-image probe
      seam; promotion gate file untouched; green).
- [x] I.6 Conversions documented: harness transport-await removal (R2.3),
      T918 remainder conversion to zero-restart-decode (R2.8) — both in
      team/i-inpaint-decoupling.md with before/after; no net weakening.
      Round-2/3 additions: one-attempt-per-window defer discipline (fixes the
      T5 virtual-time hang), BATCH write-slot admission pre-check (never
      sibling-attach onto a live same-origin hold — the D2 breakage),
      stampRenderTerminalOrphans at drainSerial (render-terminal had no owner
      for the inverted inpaint/translation order — the T918 gap).

## Wave gates

- [x] GATE-W1: full suite green after R1 + U commits — BUILD SUCCESSFUL,
      2020 tests, 0 failed, 65 skipped (2026-09-17, /tmp/t934_full_suite6.log;
      identical tree to the committed HEAD after the four path-staged
      commits ce8d925/845975d/c085f0f/818ce68).
- [ ] GATE-W2: full suite green after R2a + I commits
- [ ] On-device smoke: resume shows "Rebuilding pipeline…" then completes;
      no lease-abort storm in logcat

## Flake follow-up (post-GATE-W1, repair lane)

Run 5 of the full suite (identical tree to the green run 6) produced 4
order-dependent failures, all green in isolation and in the re-run:
- MangaScreenModelTranslationDrawerTest — executionError "Already resumed,
  but proposed with update kotlin.Unit" (double-resumed continuation).
- BatchDispatchResumeWiringTest "re-dispatch after a reset demotes real
  work…" — expected COMPLETE but was TRANSLATE (run not finished in window).
- StandardPipelineCoexistenceTest "flagged standard lane runs the real shell
  end-to-end…" — expected 2 but was 3 (counter off-by-one).
- StandardPipelineCoordinatorTest "standard lane records freeze the standard
  provider identity…" — JUnitException: Failed to close extension context.
Suspects: teardown/cleanup races in real-concurrency coexistence classes
(possibly amplified by new T934 tests' store/scope lifetimes).
