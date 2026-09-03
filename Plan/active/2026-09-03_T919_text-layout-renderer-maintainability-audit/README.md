# Task T919 — Text layout renderer maintainability audit

## Objective

Audit the text-layout renderer and directly related rendering/layout code for
maintainability. Determine how to preserve current rendering logic and safety
contracts while reducing god-file size, clarifying ownership, and enabling
safer future changes. This task is investigation and recommendation only:
make no production-code changes.

## Scope

- `app/src/main/java/eu/kanade/translation/rendering/`, especially
  `TextLayoutPlanner.kt`, `PageTextRenderer.kt`, and their direct collaborators.
- Call sites and focused JVM/instrumentation tests needed to establish the
  externally observable contracts.
- Existing T912 architecture and review reports only where they illuminate the
  current implementation's deliberate behavior.

## Constraints

- Preserve all currently intended output behavior and deterministic bounded
  search/budget guarantees.
- Android 8.0+, bounded memory, no layout-time full-page dense allocation.
- Keep `PageTextRenderer.draw()` allocation-free after `bind()`.
- Ordinary manga must not regress for specialized manhwa behavior.
- Recommend incremental, independently testable extraction slices; do not
  recommend a rewrite.

## Deliverables

- A verified current ownership/data-flow map and file/function size evidence.
- A target module boundary proposal, with dependency direction and migration
  sequence that keeps logic unchanged initially.
- Risks, seams, regression gates, and estimated benefit/effort for each slice.
- An independent review focused on behavior-preservation and extraction risks.

## Prior context

Read only when relevant:

- `Plan/active/2026-08-30_T912_text-layout-renderer/engineering/architecture.md`
- `Plan/active/2026-08-30_T912_text-layout-renderer/review/architecture-review.md`
