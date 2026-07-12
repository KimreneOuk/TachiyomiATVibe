# Handoff — test backfill

**Status:** DONE for achievable subset. Deferred items documented in `deferred.md`.
**Suite:** 570 tests green (`./gradlew :app:testStandardDebugUnitTest`).

## What shipped (2026-07-12)

### New tests (10 cases, all green)

1. `RenderColorEstimatorDedupTest.kt` — 7 cases. Golden guard on `colorPolicy`,
   `sampleBackgroundLuma`, `decideTextFill`. Locks the P1a dedup output values
   (Rec.601 weights, DARK_BG_LUMA=85 threshold, polarity agreement between
   luma and fill). Package-internal access; no Bitmap needed.
2. `TranslationSchedulerCancellationTest.kt` — added 2 cases (was 1, now 3):
   - `cancel is a no-op on a page that already has a rendered result` (hasRenderedResult branch)
   - `cancel is a no-op on a page that already failed a stage` (isStageFailed branch)

### Comment fixes (stale = defect per AGENT.md)

- `TranslationPipeline.kt:2480-2490` — `inpaintPage` KDoc claimed it set
  `inpaintStatus=READY + cleanedImageName` and handled "FAILED on storage
  failure". Post-P1b it sets only RUNNING/FAILED; `cleanedImageName` and
  READY happen in `persistCleanedBitmap`. Reworded to match.
- `RoiPageRecognitionEngine.kt:725-735` — `forceReleaseNativeBuffers` comment
  said "deferring" but the code skips (no retry). Reworded to "skipping".

### Dead code removed (user direction: "if it is dead, then kill it")

- `MainDispatcherRule.kt` — DELETED. Zero callers.
- `TranslationPipeline.inFlightPageKeysSnapshot()` — DELETED. Zero callers.
- `TranslationPipeline.permitHolderPageKeySnapshot()` — KEPT. Has 3 production
  callers in `TranslationManager.kt:396,432,458` (batch tracker permit-owner
  resolver). Initially deleted in this session; restored after compile error
  surfaced the callers. Lesson: `app/src/` grep excluding the declaration file
  is insufficient — must check cross-file.

## What did NOT ship (deferred — see deferred.md)

- P0-1 `ForceReleaseNativeBuffersGuardTest` — needs nativeGuard seam extraction.
- P0-3 `CloseEnginesClearsKeysTest` — needs closeEngines helper extraction.
- P0-4 `PermitWatchdogCallbackGuardTest` — needs withLeakProofPermit extraction.

All three blocked on the same shape of refactor: extract `private` logic into
`internal` pure helper (ShortHash pattern). Per AGENT.md autonomy policy,
architecture refactor → approval required. Decision: defer, document, do not
write theater tests.

## Net test-debt change

- Review flagged ~9 missing contract tests + 2 dead-infra items.
- This session: +10 real test cases, 2 stale comments fixed, 2 dead items killed.
- Still missing: 3 concurrency tests (deferred) + Wave 4 `OnnxModelStoreVersionTest`
  (out of scope, separate task).

## Next safe action

Bring the deferred P0-1/P0-3/P0-4 extraction proposal back for approval. Three
small `internal` helpers, no behavior change, each with a RED-first test. After
that: Wave 4 copyIfNeeded stamp design decision + test.
