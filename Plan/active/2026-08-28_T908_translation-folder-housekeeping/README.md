# T908 — Translation Folder Housekeeping Audit

Date: 2026-08-28
Branch under audit: `optimize_translation_finishing_page`

## Goal

Audit the translation codebase for dead code, stale files, and noise, and
audit the local git state (branches, worktrees, uncommitted changes) for
housekeeping. This task is **report-only**: no deletions, no branch/worktree
removal, no code changes.

## Scope

- Production: `app/src/main/java/eu/kanade/translation/` (169 files; packages:
  artifact, batch, detection, inpainting, model, ocr, recognition, remote,
  rendering, runtime, scheduling, segmentation, translator, util, webtoon + root)
- Tests: `app/src/test/java/eu/kanade/translation/` (130 files)
- Git: local branches, 22 registered worktrees (gemini antigravity + traycer),
  remote branches, uncommitted changes on the current branch
  (3 modified files, 2 untracked test paths)

## Constraints

- READ-ONLY audit. Every finding must cite `file:line` evidence or exact
  command output used to derive it.
- Distinguish **verified dead** (zero references found across the full source
  tree) from **suspicious / needs review**.
- Entry points (manifest-registered components, DI wiring, reflection, JNI)
  count as references.

## Reports (in `reports/`)

| File | Owner | Topic |
|---|---|---|
| `dead-code.md` | dead-code auditor | unreferenced classes/functions in production sources |
| `code-noise.md` | noise auditor | commented-out code, stale markers, duplicates, unused resources, debug noise |
| `git-housekeeping.md` | git auditor | branches, worktrees, uncommitted/untracked state, cleanup commands |
| `test-orphans.md` | test auditor | orphaned tests, tests for removed code, duplicate coverage, untracked test files |

## Prior context

- T905 workspace hygiene (2026-08-27):
  `Plan/active/2026-08-27_T905_workspace-hygiene-investigation/workspace-hygiene-report.md`
- T906 test suite audit (2026-08-27):
  `Plan/active/2026-08-27_T906_test-suite-audit/review/`

New auditors should verify which prior findings still hold on the current
checkout and report drift, rather than re-deriving everything from scratch.

## Exit criteria

All four reports exist with evidence-backed findings, and the Main Leader has
synthesized a cleanup recommendation for the Director.
