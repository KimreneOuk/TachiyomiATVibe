# Task T906 — Test-suite audit

## Status

AUDIT ONLY. No test or production code changes without Director approval.

## Objective

The Director believes some test cases are stale and wrong. Audit the test
suite against the current production contracts and identify every test that
codifies buggy behavior, asserts an outdated contract, is redundant, or is
inherently flaky.

## Audit target

The current truth is commit `56179d7` on `t904/integration` (all T904 phases
plus both review-fix rounds). Read files directly from
`C:\Users\User\.traycer\worktrees\kimreneouk__tachiyomiatvibe\t904-integration`
(read-only; HEAD may advance during the audit — use
`git -C <worktree> show 56179d7:<path>` for the exact snapshot when needed).
Never modify anything in that worktree.

## Classification scheme

- **WRONG** — asserts buggy or incorrect behavior (the test would fail if
  the code were right, or locks in a defect as "expected").
- **STALE** — asserts an outdated contract that has since been redesigned
  (e.g., pre-pause semantics, pre-typed-outcome behavior).
- **REDUNDANT** — duplicates another test with no added coverage.
- **FLAKY** — timing-, ordering-, or environment-dependent; can fail
  spuriously.
- **VALID** — correct and current (report only a summary count, not each).

Every WRONG/STALE/REDUNDANT/FLAKY finding needs file:line evidence, what the
test asserts today, what the current contract requires (with source
reference), and a recommended action (rewrite / delete / relax / stabilize).

## Known seed evidence

- `AotReportBubbleFillTest` (e.g. AotReportBubbleFillTest.kt:49) fails
  deterministically on the untouched base `926ae00`; the Director calls the
  test inaccurate. Determine whether the test or the production code is wrong.
- `SequentialBatchCoordinatorTest.kt:408-437` previously codified unsafe
  `RuntimeException -> Completed` behavior (fixed in T904 round 1) — check
  for similar patterns elsewhere.
- T904 round 2 fixed another stale assertion (terminal-provider-failure
  fixture expecting later pages to render after an untyped exception).

## Areas (one auditor each)

1. Batch pipeline tests: `eu.kanade.translation.batch.*` (coordinator,
   frontier, planner), retry/controller/governor tests.
2. Artifact/store/durability tests: artifact store, manifest/schema,
   legacy migration, chapter translation store tests.
3. Manager/lifecycle/UI tests: manager arbitration/teardown, foreground
   policy, queue/pending stores, reader viewmodels/sheets.
4. Provider/inpainting tests: translator adapters (Gemini, OpenAI-compatible,
   DeepL, Google), inpainting/AOT suite, plus the `:domain` module tests.

## Inpainting test policy (Director decision, 2026-08-27)

The Director: "inpainting tests do not make sense as written. It would only
make sense to see if inpaint does run and a cleaned image is provided. That
is all." AOT/AI inpainting output is model-generated and inherently
unpredictable, so exact-output or geometric assertions are invalid by nature.
Inpainting/AOT tests must verify only: (a) the inpaint stage
executes, and (b) a cleaned output image is produced (non-empty, usable).
Geometric/pixel-perfect bubble-fill assertions (e.g., diagonal components,
inset interiors) are not acceptance criteria — do not chase or gate on them.
When recommending dispositions for these tests, prefer rewrite-to-run-check
or delete over stabilizing geometric assertions.

## Fix and second-pass policy (Director decision, 2026-08-27)

The four area auditors fix their own findings on isolated branches
(`t906/fix-area1..4` off `56179d7`), gates per area are focused-only, and the
Main Leader assembles the commits and runs the full suite. After committing
fixes, each auditor performs a second AUDIT-ONLY pass over its area hunting
unnecessary tests (no real invariant, duplicate coverage, trivial assertions,
dead contract); findings are documented with delete/keep recommendations and
no further changes are made without Director approval.

## Contract

- Auditors read only their reviewer role file
  (`docs/roles/reviewer.md`) and this README. Do not read root AGENTS.md.
- Compare tests against current source behavior and the T903/T904 contracts
  (plan: `Plan/active/2026-08-26_T903_batch-pretranslate-broad-audit/engineering/shared-pacing-retry-technical-plan.md`
  and state model in the same folder; T904 phase reports under
  `Plan/active/2026-08-26_T904_shared-pacing-retry-redesign/engineering/`).
- Report to the Main Leader: "Completed. <one-line result>.
  Report: <path>". Do not address the Director.
