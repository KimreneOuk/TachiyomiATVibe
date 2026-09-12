# T924 Stage 3 progress record — WP4 shell landed, exit open (NOT an exit)

Date: 2026-09-05 · Progress record only; Stage 3 has NOT exited.
Slice report: `evidence/stage3/wp4-report.md`. Commit: `5427b35`
(S3/WP4 coordinator shell + D1 fix; orchestrator-committed).

## What landed (WP4 — OCR-preflight coordinator shell behind FF-01)

- `pipeline/batch/ChapterProfileBatchCoordinator.kt` (NEW, 545 lines incl.
  D1 fix): artifact-authority precheck (fail fast, zero OCR burned) →
  RUN_SNAPSHOT (T924-ST-03 freeze + fingerprint) → OCR_PLAN (T924-ST-05,
  persists nothing) → serial preflight loop (T924-ST-06: one page at a time,
  reader-priority `yield()` between pages, checkpoint reuse skip gated on
  usable checkpoint + source-sha equality fail-closed, native handoff + batch
  lease released per page in `finally` AFTER `checkpointOcr` CLOSE per
  TX-06) → STOP publication (`state=OCR_PREFLIGHT`, `ocrCorpusFingerprint` =
  FP-03 consumed, advisory counters), terminal PAUSED stop-not-finished (no
  completion redefinition, no committed display touched).
- Dispatch in `BatchChapterTranslator.runBatchPass1`: single FF-01 read per
  run (T924-FF-01a), OFF constructs `SequentialBatchCoordinator` with the
  verbatim legacy argument list (T924-FF-01b byte-for-byte;
  `SequentialBatchCoordinator.kt` zero diff); typed `decideResume`
  (T924-FF-01e); queue restore never auto-starts (T924-FF-10).
- D1 fix: `RunConfigSnapshot.flagProfilePipeline` optional field (no version
  bump) — the FF-01d freeze is now inside `frozenRunConfigFingerprint` (flag
  flip = config change = new runId); `phaseCounters` key kept for pre-field
  records.
- Tests: `OcrPreflightCoordinatorTest` (4) + `OcrPreflightFlagOffMidRunTest`
  (5); required scope 33 classes / 132 tests / 0 failures
  (characterization + coexistence suites green UNMODIFIED); wave-2
  integration run 102 classes / 792 tests / 0 failures (reviewer-reproduced).
- Ledger rows closed VERIFIED-EXIT: T924-FF-00, FF-01a..01e, FF-01g, FF-10;
  T924-ST-01.5, ST-03, ST-03.1, ST-05, ST-06.

## What remains for the Stage 3 exit

- R2: wire the preflight stop into the durable failure ledger
  (`persistUnexpectedBatchStageFailure` idiom) at the next S3 slice — today a
  checkpoint-REJECTED page loses its candidate-held OCR at teardown
  (crash-boundary B0 semantics).
- Gap 9: checkpoint-REJECTED mid-run durability test.
- Device gates 3.4/3.6: ~200-page real preflight run on the reference 6-GB
  device — bounded memory (peak ≤ baseline + 150 MB and ≤ 1.5 GB absolute),
  thermal, throughput; includes R1 (measure per-page counter manifest
  publication cost; de-slide to every-N-pages if it shows — correctness-
  neutral per ST-06).
- Also per `implementation-sequence.md` §S3: R013 sparse stream→download
  rekey, reader-priority native admission tuning (gate 3.5), gates 3.1-3.3
  provider-fake/trace rows through the coordinator, F1 production
  `decideResume` wiring lands at S5 (recorded obligation).

Obligations binding the S3-remainder and S5 kickoffs are recorded in
`implementation-sequence.md` §Wave-2 review obligations (wave-2 review
ACCEPT-WITH-FIXES).
