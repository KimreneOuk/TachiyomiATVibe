---
title: "S1 - core ReaderTranslationQueue and standalone suite"
kind: ticket
status: 2
assignee: "e8d65346-97a4-4523-aeeb-be9f87b85905"
comments: none
---

# Goal

Execution resumed 17 October 2026 at user request. Implementer owns the additive S1 candidate and red-first checkpoint; coordinator owns Gradle execution and independent reviewer owns actual-code review. No test or implementation completion claimed yet. Phase 3 remaining closure checks and device gates stay open.

Add the queue as a self-contained unit: contract types, demand/promotion/drain logic, and its own test suite. No production caller changes; the coordinator path stays live and untouched. The slice compiles because the executor seam is additive with default-preserving behavior.

# Files

| Path | Action |
| --- | --- |
| `app/src/main/java/eu/kanade/translation/scheduling/ReaderTranslationQueue.kt` | New — types + queue per the [plan](../../plan/index.md). |
| `app/src/main/java/eu/kanade/translation/pipeline/execution/TranslationExecutor.kt` | Add trailing optional `readerStreamFn: (() -> InputStream)? = null` and `readerPriority: (() -> AdmissionPriority)? = null` to `translateSinglePage`. |
| `app/src/main/java/eu/kanade/translation/pipeline/TranslationPipeline.kt` | Override honors `readerPriority` at the two fixed `INTERACTIVE` sites (`:482`/`:501`) and threads `readerStreamFn` into preparation; nulls preserve today's behavior exactly. No prepared-boundary API change yet. |
| The seven explicit `TranslationExecutor` fake implementations in tests | Add the trailing parameters (compile-only). |
| `app/src/test/java/eu/kanade/translation/scheduling/ReaderTranslationQueueTest.kt` | New — the standalone suite. |

# Work items

1. Types and class shape exactly per the master contract (`ReaderPriority`, `ReaderTrigger`, `ReaderSessionToken`, `ReaderPageWork`, `ReaderSubmission`, `ReaderDrainResult`, queue constructor and methods).
2. Control lock owns entries/demands/priority only; viewport revision captured under lock, resolver applied outside it; worker jobs registered under the lock, started outside.
3. Demand engine: explicit vs viewport demand, N/N+1 window from `updateViewport(pageIndex, pageCount, isAutoEnabled, resolve)`, canonical-key dedup, prune of speculative entries outside the window that lack explicit demand, auto-off semantics, display-ready skip via the store projection.
4. Admission: capacity 2 automatic operations / 1 native occupancy; LOCAL_COMPUTE `Semaphore(1)` around the whole runner (REMOTE_IO exempt); typed `ReaderSubmission` outcomes; bounded explicit pending with typed rejection; outcome map capped at 32.
5. Execution: one `executor.translateSinglePage` call per operation with MANUAL origin, captured stream factory, live priority source; one typed terminal + one trace terminal per operation including cancellation before body; identity-checked entry removal.
6. Deferral policy: memory gate, source-unavailable retryable demand, provider pause honoring `nextEligibleRetryAtEpochMs`, bounded reprepare (cap 3), attempt-cap store gate for auto attempts; event/cooldown reconciliation only.
7. Drain: `cancelSession`/`closeAndDrain` with bounded wait returning `Completed` or `PendingNativeDrain(completion handle)`.

# Test plan (virtual dispatchers/clock; fake executor records operation identity and attempt count)

Dedup and force ordering (case 1); N/N+1 window edges — last page, empty chapter, double-page insertion dedup (case 2); rapid-swipe prune retaining explicit demand and counting abandoned work (case 4); stale-token rejection and retryable missing-source (case 6); deferral policy table incl. no-per-scroll recharge and reprepare cap (case 7); promotion-in-place preserving one attempt at admission-time states the fake can express (partial case 3); drain results and bounded-wait expiry; cancellation-before-body terminal.

# Gates

Focused new test class on StandardDebug then DevDebug under the guard; existing executor-fake-dependent classes still compile; read-only Spotless inventory. Out of scope: scheduler/manager/controller changes, coordinator edits, `PageNativeOperation` reintroduction.

## Settled staging correction — 17 October 2026

The cap of three reprepare retries applies specifically to stale prepared-page handoff (`RollingAutoCoordinator.handleStaleTranslate`), not to ordinary automatic executions, provider pauses, or terminal `SinglePageOutcome.Failed`. S1 uses only `translateSinglePage`, whose typed outcomes cannot identify this stale handoff. S1 must preserve terminal outcome semantics and must not invent a generic attempt counter or infer retryability from diagnostic text.

Implement the bounded stale-handoff policy at the typed prepared/stale boundary in S2, before production reader cutover. This is a mandatory S2 acceptance dependency; its absence is not a completed S1 test or an accepted loss of runtime protection. Keep attempt-ledger admission and provider cooldown budgets independent. S1 tests remain responsible for terminal failure retention and event/cooldown reconciliation that the single-page contract can express.


## Host validation progress — 17 October 2026

Independent review cleared the root repair at queue `dcbe282b` / test `61bf23f4`, copied byte-identically into this branch. The force-order regression first failed correctly: XML1/failure1, expected one attempt versus two while predecessor was held (`validation/s1-force-red-standard-02`).

StandardDebug focused class passed: declared48, quarantined0, XML48, failures0, errors0, skipped0; guarded exit0 with no breach or monitor error (`validation/s1-root-green-standard-01`, source freeze `validation/s1-root-repair-source-01.json`, four hashes exact). DevDebug focused class is running in `validation/s1-root-green-dev-01`; no Dev result yet. Formatting/inventory and final evidence review remain. This is additive S1 only; no production reader cutover or device acceptance is claimed.


## Final host gate results — 17 October 2026

| Gate | Result | Evidence |
| --- | --- | --- |
| Force-order red | XML1, failures1, expected1 attempt versus actual2 | `validation/s1-force-red-standard-02` |
| StandardDebug focused class | declared48, quarantined0, XML48, failures0, errors0, skipped0 | `validation/s1-root-green-standard-01` |
| DevDebug focused class | declared48, quarantined0, XML48, failures0, errors0, skipped0 | `validation/s1-root-green-dev-01` |
| Explicit scoped Spotless apply | exit0, eleven target files | `validation/s1-scoped-format-03` |
| Standalone scoped Spotless check | exit0; KotlinCheck executed | `validation/s1-scoped-format-check-01` |

All guards reported no breach or monitor error. Tested-source freeze and backups establish pre-format source identity. Post-format freeze11/11 is exact; import sets are identical, with all nontrivia tokens unchanged except six formatter-braced expression branches and four semicolon-to-newline boundaries, reviewed as formatting-only. Evidence: `validation/s1-format-equivalence-01.json`, `s1-format-token-delta-01.json`, `s1-postformat-source-01.json`. Hook-based format01 was excluded (checks skipped/no changes); explicit format02 failed configuration before changes and was diagnosed/fixed before format03. Final independent closure disposition remains pending at this entry.

No production caller cutover, coordinator retirement, full-suite, Phase3 closure, or device acceptance is included. Reprepare and live native/provider waiter promotion remain mandatory S2 prerequisites.


## Closure

S1 independently accepted on 17 October 2026 after source review, both focused flavor gates, formatting equivalence verification and standalone scoped check. Final disposition: `epics/5fe9a53a-a3da-4b2e-9341-ce60bb69c663/artifacts/phase-4-execution/s1-final-disposition/index.md`. S2 remains a separate ticket and diff. Never push.
