# Checkpoints

## 0. Plan and characterization

- Status: complete
- Verified live run: 67 pages; native lane advanced; no contextual request events; later translations remained pending.
- Verified page 1: zero blocks/masks, empty errors, and `inpaint=PENDING` after textless semantics were overwritten.
- Settled: dynamic safe boundary shrinking and bounded provider recovery.

## 1. Textless regression repair

- Status: implemented; validation in progress
- Added focused zero-mask and detector-only-mask tests. OCR now establishes the default inpaint handoff before applying terminal textless semantics, so page 1 cannot be left at `inpaint=PENDING`.

## 2. Page-atomic chunk planner

- Status: implemented; focused planner tests passing
- Chapter targets are 5/7/10 pages. The post-OCR planner chooses the largest whole-page prefix fitting the 24-block/token provider envelope, with an explicit oversized-single-page recovery case.

## 3. Sequential chunk coordinator

- Status: implemented; compilation and integration validation in progress
- Contextual chapters now run explicit OCR, translation-flush, and inpaint/render barriers per logical chunk. The 250 ms inactivity scheduler is no longer used by this path.

## 4. Resume, cancellation, and recovery

- Status: implemented
- Existing guarded writes, leases, scene-prefix commits, and adaptive provider retry remain in the workers reused by the new barrier schedule. Textless detector-only masks retain their lease through inpaint.

## 5. Validation and device run

- Status: device verification pending a newly started chapter batch
- Focused chunk/textless tests: pass (9 tests).
- `spotlessCheck`: pass. `:domain:testReleaseUnitTest`: pass. `git diff --check`: pass.
- Broad app test attempts did not complete: `RollingAutoCoordinatorTest` produced an unrelated scheduler assertion failure and then hung in `older same-spec snapshot build cannot overwrite newer stage`; the bounded runs were terminated and are not reported as passing.
- `:app:assembleStandardDebug`: pass. Installed arm64 debug `0.17.1-182`; SHA-256 `F1F133941943103FE8D3FF25EB881B629CD3B0D34538D72750C983E0F9236DF0` verified on device.
- App launch is clean, but it opened at Library rather than resuming the old reader. Start the chapter batch to verify real chunk range/request/publication logs.
