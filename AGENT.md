# AGENT.md

## Project

- Kotlin Android manga/manhwa/manhua reader.
- Translation includes OCR, ONNX detection/segmentation/inpainting, translators, and rendering.
- Preserve correctness, bounded memory, performance, and support for devices with at least 6 GB RAM.

## Source of truth

Use this order:

1. Executable code and call paths.
2. Tests and build/packaging configuration.
3. Runtime logs and generated artifacts.
4. Current architecture/workflow docs under `docs/`.
5. Active plans under `Plan/active/`.
6. Comments and historical notes only as background; never treat them as behavior.

When investigating models or pipelines, verify all of the following from code/configuration:

- the asset path and aliases;
- whether the asset is packaged;
- the loader and initialization path;
- whether the stage is actually invoked;
- whether its output is consumed downstream.

## Required workflow

1. Read the relevant `docs/project_context/` guidance.
2. Use `rg` to locate entry points, model paths, symbols, and tests.
3. Trace the live call path and inspect dependencies before concluding behavior.
4. State observed behavior, scope, constraints, and success criteria.
5. Make a small plan before editing.
6. Make the smallest safe change and preserve unrelated work.
7. Add or update focused regression coverage where practical.
8. Update stable user-facing or architectural documentation in `docs/` when needed.
9. Review the diff and report exactly what was verified.

## Change rules

- Prefer self-documenting code.
- Do not use comments to establish behavior or compensate for unclear code.
- If code and comments conflict, follow code and fix the stale comment when in scope.
- Ask before architecture rewrites, migrations, public API changes, dependency replacement, or destructive operations.
- Never claim a build, test, or runtime result that was not successfully verified.

## Validation checklist

- [ ] Symbols, imports, paths, and APIs exist.
- [ ] Model assets and packaging rules were checked when relevant.
- [ ] The smallest relevant test or check was run.
- [ ] Changed behavior has regression coverage where practical.
- [ ] Documentation/comments match verified behavior, or conflicts are reported.
- [ ] Unperformed validation, assumptions, risks, and follow-up are explicit.

## Knowledge base

- Stable architecture belongs under `docs/`.
- Substantial task reasoning belongs in `Plan/active/<YYYY-MM-DD>-<topic>/`.
- Do not create external brainstorming files unless explicitly requested.

## Completion report

Report:

- files modified;
- tests added or updated;
- validation performed;
- validation not performed;
- assumptions, risks, and follow-up work.
