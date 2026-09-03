# T919 — Text-layout renderer maintainability audit

## Decision

Refactor incrementally by responsibility. Keep output logic, thresholds,
rounding, candidate order, page budgets, and the public planner facade unchanged
while extracting. Do not rewrite the planner or replace the live reader renderer
with `PageTextRenderer` as part of this work.

## Why

- `TextLayoutPlanner.kt` is 3,793 lines / 168 KB; the next largest rendering
  source file is 454 lines. It currently owns contracts, tuning, text rules,
  mask assembly, legacy fitting, contained rescue, placement safety, page
  ordering/budgets, and final result assembly.
- The reader's live drawing path is `TranslationOverlayView`, which calls the
  planner and draws directly on `Canvas`. `PageTextRenderer` is a separate,
  test-only bind-time `StaticLayout` implementation. Their clipping, malformed
  metadata, and shaping behaviors are not proven equivalent.
- Existing leaf modules are useful seams (`MaskTextRegionPlanner`,
  `AdaptiveBandPlanner`, and `TextLineBreaker`), but they point back to planner
  statics for Unicode/stroke rules. Extract shared primitives before moving
  algorithms.
- Page-scoped caps and ledgers are behavior: mask conversion/cell/rescue
  budgets, positioned-line/static-layout budgets, accepted footprints, and
  component assignments must remain one coordinator-owned state.

## Recommended extraction order

1. Add production-overlay characterization: source transform, lifecycle,
   clipping, legacy/positioned/vertical draw modes, and normalized page-plan
   fixtures.
2. Move unchanged contracts/rules: geometry DTOs, page-plan/result DTOs,
   tuning, stroke metrics, script/wrap/vertical rules. Keep temporary facade
   delegates if necessary.
3. Extract the live overlay painter intact while keeping view lifecycle and
   SSIV transforms in `TranslationOverlayView`. Do not route it through
   `PageTextRenderer`.
4. Move mask session/grouping/shared-cell metadata assembly.
5. Move legacy fitting/placement/free-space growth.
6. Move contained rescue, free-text widening, final placement resolver, and
   sibling font harmony as unchanged named policies.
7. Leave `TextLayoutPlanner` as a thin facade/coordinator for ordering, one
   page ledger, and result assembly. Consider renderer convergence only after
   Android pixel-parity and allocation evidence, under a separate decision.

## Important risks to resolve before implementation

- `BlockLayout` has overlapping clip representations; choose and document one
  canonical authority during the contracts move, without changing current
  rendering behavior.
- Current live code applies post-placement same-component font harmony, whereas
  a prior T912 architecture document described independent sibling fitting.
  Preserve current behavior first and obtain a later explicit decision on
  whether the policy should remain.
- The planner has no verified local cap on total input blocks; avoid creating
  new per-candidate/per-line context objects during extraction. Characterize
  high-cardinality pages before adding any allocation-sensitive abstraction.

## Verification state

- Source audit found 20 focused JVM rendering test files (183 test methods) and
  one instrumentation renderer test file (15 test methods), plus overlay
  instrumentation coverage.
- Focused Gradle verification could not begin in this environment: `JAVA_HOME`
  is unset and `java` is unavailable. This is an environment limitation, not a
  code-pass result.
- No production code or tests were changed. The working tree already contained
  unrelated T918 and test files; they were left untouched.

## Detailed evidence

- Technical audit: `engineering/architecture-and-extraction-audit.md`
- Independent risk review: `review/extraction-risk-audit.md`
