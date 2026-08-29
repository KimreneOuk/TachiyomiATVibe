# T910 — Test-Suite Remediation (T906 decisions + guards)

Date: 2026-08-29 · Branch: `optimize_translation_finishing_page`

## Goal

Execute the Director-approved recommendations arising from the T906 test-suite
audit and the T908 deadlock discovery. Incremental commits on the current
branch; each work item verified by compile + targeted tests before the next.

## Work items

| # | Item | Source | Owner |
|---|---|---|---|
| 1 | Decision batch: execute every T906 DELETE/cleanup/rename recommendation (redundant tests, duplicate, dead setup, dead `insetPx` variable) | `Plan/active/2026-08-27_T906_test-suite-audit/review/area1..4` | decision-batch implementer |
| 2 | `runBlocking<Unit>` guard: Gradle check task failing on the silent-skip pattern, allowlisted until item 4 resolves | T906 U3 recommendation | guard implementer |
| 3 | Coverage gaps F3–F7: add the manager-level characterization tests T906 recommended | area3 report | coverage implementer |
| 4 | Deadlock P1: enable + re-validate the 8 never-executed `RollingAutoCoordinatorTest` tests with per-test timeouts; diagnose the line-169 hang; fix test infra or disable with diagnosis | T908 `reports/cleanup-implementation.md` §5 | deadlock investigator |

## Constraints

- Sequential execution (shared working tree + single gradle daemon).
- The full suite must stay green and non-hanging after each item.
- No production behavior changes (item 4 may expose one — document, don't fix
  production in this task).

## Reports (in `reports/`)

`t906-decision-batch.md`, `runblocking-guard.md`, `coverage-gaps.md`,
`deadlock-investigation.md`
