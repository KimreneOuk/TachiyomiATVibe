# T918 (+T919 co-ship) — Merge Acceptance Gate

Date: 2026-09-03
Scope reviewed: `main...fix/batch-retry-affordance` (6 commits: 74abc05, df4e6a9, fe9f095 = T918 + task docs; f4d8a0b, 0365c62, 43e7b6b = Director-initiated T919 page-text-renderer removal + overlay test migration).

## Verdict

**ACCEPT-WITH-NOTES.** No blockers. Cleared for merge to `main`.

## Evidence

- Independent XML re-count (Reviewer, from `app/build/test-results/testStandardDebugUnitTest/`):
  **201 suites / 1,454 tests / 0 failures / 0 errors / 0 skipped.**
  Named classes confirmed green in XML: T918SheetRetryTruthTest (4), T918CancelledBatchRestartTest (1),
  MangaScreenModelCancelledBatchReconciliationTest (2), TextLayoutPlannerDirectionTest (8),
  NormalMangaIsolationTest (1).
- Contract verified in source: Retry button truth-gated via `TranslationUiTruth.forSheetRetryAction`
  (aborted OR terminal-error only); `reconcileAbortedBatch` cannot clobber a newly queued batch
  (queue guard + QUEUE/TRANSLATING/PAUSED-only mapping); `retryBatchTranslation` reuses the
  `translateChapter` resume path (T918CancelledBatchRestartTest pins zero re-decodes, p0 paid calls = 1).
- T919 coherence: zero `PageTextRenderer` references remain; `TextLayoutPlanner.kt` delta is one
  KDoc line only; `ComponentClipCache` extracted verbatim (was inner class of the removed file) so
  its pre-existing test keeps compiling; no Robolectric/sleeps/polling in new tests.

## Notes (all MINOR/NIT, none blocking)

1. Sweep XML finished 16:08:51 +0700; `df4e6a9` committed 16:10:00 — evidence-tree gap only;
   XML proves the T918 tests ran against the implementation, and the Director field-tested
   build 0.17.1-380 from this exact tree.
2. T919's migrated overlay coverage is instrumented-tier (androidTest) — outside this unit-XML
   gate by design; planner logic covered at unit tier by TextLayoutPlannerDirectionTest.
3. `manga_batch_retry` string lives at `i18n-at/src/commonMain/moko-resources/base/strings.xml:161`.

## Gates

- Reviewer acceptance: ACCEPT-WITH-NOTES (this record).
- Director sign-off: given 2026-09-03 ("everything is good… commit this into a larger branch"),
  after field-verifying build 0.17.1-380 on device (in-place upgrade, data preserved).
