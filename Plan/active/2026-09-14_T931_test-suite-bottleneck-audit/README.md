# T931 — Test-suite bottleneck audit

## Question (Director)

"I believe test cases are also a huge bottleneck in this development phase.
I believe it is too restrictive or stale legacy."

Verify against the codebase. Code over belief, code over documentation.

## Established facts (main-leader recon, 2026-09-14)

- 277 Kotlin test files under `app/src/test` (~63,557 lines).
  No `app/src/androidTest` content found.
- Suite is dominated by the translation feature (`eu/kanade/translation/**`),
  not the upstream Mihon base.
- Test churn moves in the SAME commits as main-code churn through T924
  (2026-09-08..13): tests are actively co-maintained, not abandoned.
- 0 Robolectric tests. 56 files use `kotlinx.coroutines.test`/`runTest`.
  7 files use `Thread.sleep`. 0 `@Ignore`.
- Largest files: RollingAutoCoordinatorTest (1,444), ChapterArtifactStoreTest
  (1,269), TranslationCoexistenceHarness (1,254), Page15MockRig (1,018).

## Why this matters now

T930 (group commit) will change WHEN bytes hit disk, not WHO owns writes:
commit-point contract, writer registry, staged manifest mutations behind a
flag, read-back elision, drain-to-commit stop, restricted UI-before-persist.
Any test pinning the CURRENT mechanics (exact publish op sequence,
persist-before-UI ordering, per-mutation manifest rewrite) collides with it.

The coexistence/race invariants are the project's load-bearing safety net.
The audit must separate: crust that blocks development vs net that keeps
the pipeline safe. Recommendation must not gut the net.

## Deliverables

- `team/restrictiveness/report.md` — which tests pin mechanics T930 changes;
  behavior-contract vs change-detector classification; keep/convert/cull list.
- `team/staleness/report.md` — dead-path & legacy-migration test inventory,
  harness quality (coexistence harness, mock rigs), timing/flakiness,
  build wiring, runtime cost estimate.
- `report/DIRECTOR_REPORT.md` — main-leader synthesis after verification.

## Rules

- Every claim cites file:line at HEAD.
- No test may be deleted by this audit; classification only.
- Baseline: 200 pages / one-translation-at-a-time / 8k lockdown (T929).
