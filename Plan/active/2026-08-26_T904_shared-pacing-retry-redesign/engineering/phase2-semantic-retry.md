---
kind: spec
title: "T904 Phase 2 — semantic AI retry"
---

# T904 Phase 2 — semantic AI retry

Implementation commit: `1f62fe03ed4d8c40bae4ee419f9998b37df37265` (on Phase 1 governor commit `dcd29c89c7d2e28a73bb639c2b6b3ce3336462f6`).

## Delivered

`AiTranslationRetryController` is now a typed, side-effect-free semantic retry engine. It freezes detached OCR/source pages, glossary/context inputs, page order, and stable block IDs before the first request. Whole-envelope retries reuse that frozen identity; missing-only retries request only unresolved IDs. An immutable first-wins accumulator merges responses, fences duplicate/conflicting/unknown IDs, source echoes, malformed payloads, refusals, and user edits, and returns `AiChunkOutcome.Complete`, `.Paused`, or `.Terminal` with detached results, missing IDs, safe failure metadata, and retry counters.

Only a complete natural-order prefix produces a rolling-context delta. Paused or terminal outcomes do not advance rolling context in the controller. Cancellation propagates without mutating caller-owned pages.

## Request-budget/API decisions

`RequestRetryBudget` is an explicit per-semantic-envelope budget (`maxAttempts`, atomically consumed), with no global mutable state. `withTranslationRetry` accepts an optional budget and inherits one from `withRequestRetryBudget`, preserving the existing direct-call syntax for ordinary page/manual calls. The controller creates one shared budget for the initial envelope, whole-envelope retries, missing-only requests, and Gemini thinking/fallback POSTs. Every real HTTP attempt is admitted and counted by Phase 1's `ProviderRequestGovernor`; budget exhaustion is a typed pause outcome and never bypasses governor admission/cooldown rules. Legacy/ungoverned callbacks receive one compatibility charge per logical wrapper attempt.

`applyAiChunkOutcomeToPages` is an explicit compatibility bridge for the existing pipeline. It applies detached accepted translations by stable ID while respecting user-edit fences; durable provisional/paused/terminal persistence remains outside this phase. `TranslationPipeline.translateChunkAi` captures the typed outcome and uses that bridge so existing direct callers continue to compile.

## Deviations and handoff risks

- The pipeline compatibility path still has its pre-Phase-3 commit/context behavior. It may apply provisional accepted translations and advance its existing context frontier for an incomplete outcome; Phase 3 must consume the typed outcome and own pause propagation, durable status, and queue semantics.
- Protocol violations are conservatively sticky: after malformed, duplicate/conflicting, or unknown IDs, a later complete response remains terminal protocol failure rather than silently being promoted.
- Compatibility charging cannot observe multiple hidden HTTP calls inside a future ungoverned callback; such boundaries must migrate to the governor.
- Coordinator state, durable failure artifacts, queue state, and UI were intentionally not changed.

## Validation

- Focused translator tests: 28 passing, 0 failures/errors (12 controller, 9 retry-budget, 7 governor tests).
- `:app:compileStandardDebugKotlin` passed.
- Root `spotlessCheck` passed.
- `git diff --check` passed.

Focused controller coverage includes first-pass success, whole-envelope transient retry, missing-only merge, finite shared-budget exhaustion, nested semantic/transport retry ceilings, stable request identity, malformed/duplicate IDs, refusal/terminal failures, user-edit fencing, and cancellation.

## Key entry points

- `AiChunkOutcome` and `AiTranslationRetryController`: typed semantic outcomes and bounded accumulator/retry orchestration.
- `RequestRetryBudget` / `withTranslationRetry`: shared per-envelope request accounting.
- `applyAiChunkOutcomeToPages`: explicit live-page compatibility adapter.
- `TranslationPipeline.translateChunkAi`: existing-call-site compatibility bridge pending Phase 3 outcome integration.
