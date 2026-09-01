# Task T912 — Text layout renderer visibility and shape safety

## Status

2026-08-30: Director approved implementation on branch
`codex/text-layout-renderer`, based exactly on T911 final commit `ece0e72`.

## Objective

Improve translated-text visibility and containment for thin, medium, small,
fused, and connected speech regions without regressing ordinary manga or the
recent batch translation/download/drawer/progress repair.

## Director-approved behavior

1. Distinct OCR/translation blocks are never merged or concatenated, including
   when they share one continuous segmentation mask or borderless drawing.
2. Invented Latin hyphenation is disabled by default. Existing source hyphens
   remain valid breakpoints. Optional inserted breaks are limited to eligible
   ALL-CAPS text and must materially improve visibility.
3. Mask-aware placement uses actual page-space row spans and verifies
   stroke-inclusive containment. Thin and medium-small regions should use
   adaptive per-line/per-band width where it improves visibility over one
   conservative inscribed rectangle.
4. A shared component with several distinct text blocks is partitioned into
   independent fit-aware cells derived from block geometry/centers and reading
   order. Each cell retains independent text, font fitting, and placement.
5. Confidently jointed speech lobes may be separated conservatively. Ambiguous
   topology falls back safely without merging texts.
6. Long `text_free` (`label == 2`) may receive a bounded OCR-centered horizontal
   region widening trial. Persisted source direction and block identity remain
   unchanged; ordinary short SFX and normal bubbles remain unchanged.
7. The final no-overlap check runs after anchoring and includes outline/stroke
   extent plus a scaled safety gap. Unsafe candidates shrink, shift, clip/refit,
   or fall back.

## Performance and compatibility constraints

- Android 8.0+.
- Bounded memory; no new full-page dense mask or bitmap allocation in layout.
- Prefer span/rectangle math and at most a small bounded candidate set.
- Preserve reader stability and normal manga behavior.
- Do not import or merge sibling commit `7c517d5`.
- Keep `PageTextRenderer.draw()` allocation-free after `bind()`.

## Initial tuning envelope

- Long free-text trigger: target length around 24+ non-whitespace graphemes,
  tall original region, and demonstrated fit pressure.
- Free-text widening candidates: `1.25x`, then `1.50x`; accept the smallest
  useful candidate, cap added width relative to page size/free space, and
  require a meaningful fit/font improvement.
- Mask padding, font ceiling, and joint confidence thresholds are tunable and
  must be isolated constants backed by focused tests.

## Required implementation evidence

- Current behavior and data-flow report.
- Reviewed architecture/algorithm plan before production edits.
- Focused JVM tests for wrapping, containment, row-span geometry, independent
  shared-mask cells, joint fallback, free-text widening, stroke-aware overlap,
  determinism, and bounded stress behavior.
- Renderer instrumentation coverage where Android `StaticLayout`/clip behavior
  cannot be proven on the JVM.

## Regression gate

In addition to renderer-focused tests, run the relevant T911 suites covering:

- batch translation admission, arbitration, progress, and terminal exits;
- download lifecycle notifications, recovery, and handoff failure split;
- manga drawer opening, restored state, multi-select, queue position, and
  chapter indicators;
- batch hero/progress projections and durable reconstruction.

Then run the narrowest practical module-wide test target. Record exact command,
suite/test counts, failures, errors, and skipped tests.

## Deliverables

- `engineering/architecture.md`
- implementation report(s) under `engineering/`
- independent plan and implementation reviews under `review/`
- final Director summary with exact branch, commit(s), and verification results

