# Task T905 — Workspace hygiene investigation

## Status

INVESTIGATION ONLY. No cleanup execution without Director approval.

## Objective

Determine why the primary workspace keeps getting dirty, classify every
current deviation from HEAD, and produce an approved end-state plus a
prevention policy so the workspace stays clean during multi-agent work.

## Known dirty state at opening (2026-08-27)

- Deleted: `AGENT.md`, `docs/project_context/implementing.md`,
  `docs/project_context/knowledge_base.md`, `docs/project_context/planning.md`
- Modified: `AGENTS.md`
- Untracked: `Plan/active/2026-08-25_T901_*`, `2026-08-25_T902_*`,
  `2026-08-26_T903_*`, `2026-08-26_T904_*`, `docs/roles/`

## Questions to answer

1. Origin: which agent/tool/session created or deleted each item, and was it
   intentional (e.g., role-file additions) or accidental (e.g., project_context
   deletions)?
2. End-state per item: commit, restore, relocate, or .gitignore — with
   rationale, including a tracked-vs-ignored policy for `Plan/` task folders
   (worktree agents currently cannot see each other's reports because `Plan/`
   is untracked; reports had to be copied manually).
3. Worktree hygiene: are child worktrees (`t904/*`, older feature worktrees,
   `.gemini/antigravity/*`) clean, stale, or diverged; which can be removed
   after T904 completes?
4. Prevention: a repeatable steward checklist (pre-task baseline, post-task
   sweep), .gitignore additions, and commit conventions for docs/roles and
   Plan artifacts.

## Contract

- Investigator reads only its steward role file and this README.
- Produce a classified inventory with recommended disposition per item and a
  prevention policy; no destructive or committing actions.
- Report to the Main Leader with the report path; do not address the Director.
