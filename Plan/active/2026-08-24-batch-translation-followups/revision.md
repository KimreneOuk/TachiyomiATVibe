# Batch Translation Revision — Page-Atomic Gemini Envelopes

## Settled behavior

- An AI request never divides a source page. The streaming planner adds whole
  pages in natural reading order until adding the next complete page would
  exceed the calculated provider budget.
- The old static limits of four pages and 28 blocks do not decide envelope
  boundaries. A dense page is sent alone after auxiliary context is trimmed;
  only a page whose source itself cannot fit the provider budget fails.
- Gemini translation uses the direct Gemini API rather than the deprecated
  `generativeai:0.9.0` Android client. Reasoning is disabled by default where
  the selected model supports it, with Auto and Low available to the user.
- Gemini provider failures preserve the HTTP status and retry delay. Rate
  limiting delays and retries the same page-atomic envelope rather than
  splitting it into more requests. Structural output failures remain
  fail-closed and page-scoped.

## Evidence motivating the revision

The device run showed that a five-block request can span only part of a page.
Its Gemini responses were structurally invalid even for single-block retries:
one-block requests returned three response lines and two-block requests
returned 17. The legacy client cannot configure model thinking and obscures the
provider status as `UnknownException`.

## Implementation boundaries

- Extend the existing `StreamingChunkPlanner`; do not restore the removed
  `PageAtomicChunkPlanner` or add an AI scheduler.
- Preserve one ordered translation lane and native lookahead bounds.
- Keep the strict stable-ID batch parser. Do not make malformed Gemini output
  promotable merely to recover progress.
- Preserve the existing user-supplied Gemini-key UX while migrating the
  request implementation.

## Validation

- Planner tests cover complete-page packing, a dense standalone page, and a
  source-over-budget page.
- Gemini transport tests cover thinking configuration, status-aware 429 retry,
  and strict response rejection.
- Device validation repeats the same free-tier Gemini chapter run and records
  emitted envelopes, retry delays, and committed page progress.
