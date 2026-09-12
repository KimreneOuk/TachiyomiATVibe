# T924 Stage 3 exit report — coordinator shell + durable failure ledger + dispatch gate

Date: 2026-09-06 · Verdict: **COMPLETE-PENDING-DEVICE-GATES**

Worktree `TachiyomiAT-t924-impl`, branch `t924/batch-profile-pipeline`.
Slice reports: `evidence/stage3/wp4-report.md` (WP4 shell, wave 2),
`evidence/stage3/s3-remainder-report.md` (R2 + gap 9, wave 3),
`evidence/stage5/s5-preconditions-report.md` (F3 + gap-2 legs, wave 3).
Review: `evidence/wave3-review.md` **ACCEPT-WITH-FIXES** (fixes landed;
special review items 1–3 — generation-less store fix, test-assertion
change, D2 post-cancel ordering — all ruled SAFE/HONEST), preceded by the
wave-2 acceptance of the shell (`evidence/wave2-review.md`).

## Commit map

| Commit | Content |
|---|---|
| `5427b35` | WP4 coordinator shell (wave 2): RUN_SNAPSHOT freeze (ST-03/03.1), OCR_PLAN (ST-05), serial preflight loop (ST-06/TX-06), terminal PAUSED stop-not-finished, FF-01a/b/e/g/FF-10 dispatch + `decideResume`, D1 flag-freeze fix (FF-01d) |
| `c2705c8` | Wave-3 slice A, stage-3 legs: **F3** non-AI dispatch gate (`profilePipelineDispatchKind(flagOn, contextualAiParity = isAi)` at the single FF-01a dispatch point in `BatchChapterTranslator`; OFF branch byte-identical) + **gap 2** queue-restore test (`ChapterTranslatorQueueRestoreTest` +199: interrupted flagged pass, `DropToLegacy`/`RunFlaggedPath` decision-only, no side effects). The F2/gap-4 legs of this commit belong to the S4/S5 substrate (`evidence/stage4/exit-report.md`) |
| `2cc209e` | S3 remainder (R2 + gap 9): durable failure ledger — constructor-injected recorder with a real default writer mirroring `persistDurableStageFailure`; REJECTED + worker-exception triggers; ledger write AFTER B0 candidate teardown (deviation D2, review item 3 SAFE); consecutive cap 3 restamped INTERRUPTED manual-retry; best-effort write, honest FAILED outcome stands; new `OcrPreflightRejectedMidRunDurabilityTest` (2 tests incl. 4 restart cycles) |
| `74302ec` | (ledger-side content of the WP9 commit) orchestrator generation-less fix: the durable failure `StageArtifactRecord` is stamped `generationId = null`, so `cancelCandidate` can no longer strip a written failure entry (closes s3-remainder defect §7.2, cross-restart cap now works); disclosed in the commit message; `isCandidateOwned` predicate otherwise untouched |
| `a56f232` | Review fix **F-W3-1**: `checkpointOcr` CLOSE clears the stale `"$pageKey:OCR"` ledger entry on success (mirror of the TRANSLATION clear), removing the indefinite PAUSED projection for a recovered chapter, plus comment |

## Gates status (JVM legs green; device gates 3.4/3.6 owed per R1)

| Gate | Status | Evidence |
|---|---|---|
| 3.1–3.3 provider-fake/trace rows through the coordinator | JVM parity PASS; through-coordinator trace rows OWED | Flag-off parity suite unmodified green (`OcrPreflightFlagOffMidRunTest` 5, `Phase0BatchTranslationCharacterizationTest`, full coexistence suites; `SequentialBatchCoordinator.kt` zero diff); the provider-fake/trace rows are listed open per the stage-3 progress record |
| 3.4/3.6 ~200-page on-device memory/thermal/throughput | **OWED (device)** | Includes **R1** — measure per-page manifest-publication cost before any default-on decision; de-slide counters to every-N-pages if it shows (correctness-neutral, ST-06 advisory) |
| 3.5 native admission device tuning | OWED (device) | Reader-priority `yield()` between pages landed; thresholds stay PROPOSED until device-tuned |
| 3.8 flag-off-mid-run | PASS for producible states (JVM) | `OcrPreflightFlagOffMidRunTest` cases 2–5 + wave-3 gap-2 `ChapterTranslatorQueueRestoreTest`; the dispatch-level full obligation (first `COMPLETE` publisher) stays with F1/gap-1 at S5 — not claimable here |

## Deviations

Ratified under `evidence/wave3-review.md` (items 1–3) and the wave-2
rulings: s3-remainder D1 (recorder lambda with a real default writer) and
D2 (ledger write after B0 teardown — SAFE, item 3); the orchestrator
generation-less store fix (item 1 SAFE, bounded cost = F-W3-1, now fixed in
`a56f232`); the gap-9 test-assertion change `errorMessage` → `activeError`
(item 2 HONEST — durable surfaces genuinely asserted via `ocrError`);
wave-2 D2/D3/D4/I1 as recorded in the shell report.

## Verification

- Final wave-3 run: `:app:compileStandardDebugKotlin` exit 0; targeted
  `testStandardDebugUnitTest` suites **834 tests / 0 failures / 0 errors /
  0 skipped** (post-fix), independently reproduced by the reviewer at
  **833/0 pre-fix** (JUnit XML tally, classes=107).
- `OcrPreflightRejectedMidRunDurabilityTest` 2/2 incl. 4 restart cycles
  (cross-restart cap verified post-store-fix); `ChapterTranslatorQueueRestoreTest`
  4/4; `ProfilePipelineDispatchGateTest` 4/4.
- Safety: no OCR parallelization (zero async/launch/runBlocking in the
  coordinator); serial loop intact; lease release strictly after the
  checkpoint attempt (TX-06); committed display untouched (`committed ==
  null` asserted on every touched page record).

## Remaining owed (not done in this stage)

1. **F1/gap 1** — wire `decideResume` into the production resume path when
   the first stage publishes `ChapterRunState.COMPLETE` (S5) + dispatch-level
   flag-off-mid-run test.
2. **gap 3** — request-builder test pinning contributing-set order =
   core-then-context + schemas contract §1.3 note (S5).
3. **D4** — real engine/model identity replacing `MODEL_HASH_UNSPECIFIED`
   before analysis fingerprinting freezes it (S5).
4. **Device gates 3.4/3.6** incl. **R1** per-page publication-cost
   measurement; **gate 3.5** device tuning.
5. Gates 3.1–3.3 through-coordinator provider-fake/trace rows (per §S3).
6. **R013** sparse stream→download rekey (`implementation-sequence.md` §S3).
7. **F-W3-2** (LOW) — comment reword at `ChapterProfileBatchCoordinator.kt:240-242`
   at next touch.

## Rollback state

FF-01 default OFF remains the standing kill switch; OFF path
byte-for-byte legacy (`SequentialBatchCoordinator.kt` untouched across the
whole range); the durable failure ledger is an ADDITION on the flagged path
only (best-effort writer, never a new failure source); rollback = revert
the commits in order (`a56f232` → `74302ec` → `2cc209e` → `c2705c8`).
