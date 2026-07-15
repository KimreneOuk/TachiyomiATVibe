# AGENT.md

## Project

- Kotlin Android manga/manhwa/manhua reader.
- Translation uses OCR, ONNX recognition/inpainting, AI/API translators, and
  live or persisted rendering.
- Optimize for correctness, bounded memory, performance, and low-end devices
  with at least 6 GB RAM.

## Source of truth

1. Live code and tests.
2. Current architecture/workflow docs under `docs/`.
3. Active task notes under `Plan/active/`.
4. Comments and historical notes, after verifying them against code.

Comments are hypotheses, not behavior. A stale comment is a defect: update it,
mark it historical, or remove it when the code changes.

## Required workflow

1. Read `docs/project_context/planning.md`, `implementing.md`, or
   `knowledge_base.md` when the task touches that workflow.
2. Use fast codebase search (`rg`) to locate symbols and trace dependencies.
3. Read relevant docs, entry points, tests, and dependencies before editing.
4. State the observed behavior, scope, constraints, and success criteria.
5. Make and challenge a small implementation plan before editing.
6. Make the smallest safe change that follows existing patterns.
7. Add or update regression coverage for changed behavior.
8. Update current user-facing or architectural documentation in the same
   change. Do not scatter task notes through module directories.
9. Review the final diff and report exactly what was verified.

## Autonomy and approval

Proceed without approval for inspection, test discovery, focused tests, normal
bug fixes, routine documentation, and reversible local changes.

Ask before:

- architecture rewrites or broad cross-module refactors;
- database migrations, public API changes, or dependency replacement;
- destructive or difficult-to-reverse operations;
- materially ambiguous behavior or scope expansion;
- changes that cannot be safely validated with available tests.

For large work, use checkpoints: baseline, isolated change, validation, and
diff review. Delegate only work that can run meaningfully in parallel.

## Change rules

- Prefer self-documenting code.
- Do not add comments that merely restate code.
- Add comments/KDoc only for intent, tradeoffs, memory/performance limits,
  non-obvious behavior, or algorithm details.
- Preserve unrelated work in a dirty worktree.
- Fix causes; do not suppress errors or warnings without understanding them.
- Never claim a build, test, or compile result that was not run successfully.

## Knowledge base

- Substantial task reasoning belongs in a dated `Plan/active/` subfolder.
- Stable architecture and workflow knowledge belongs under `docs/`.
- Preserve an explicitly supplied external brainstorming packet in its existing
  `external/` location, verify it against code, and update the active design or
  handoff. Do not create external files during ordinary investigation.

## Validation checklist

- [ ] Referenced symbols, imports, paths, and APIs exist.
- [ ] The smallest relevant test or check was run.
- [ ] Changed behavior has regression coverage where practical.
- [ ] Documentation and comments match the live implementation.
- [ ] Unperformed validation and environment limits are explicit.
- [ ] Final report lists modified files, tests, validation, risks, and follow-up.

## Completion report

Report:

- files modified;
- tests added or updated;
- validation performed;
- validation not performed;
- assumptions, risks, and follow-up work.
