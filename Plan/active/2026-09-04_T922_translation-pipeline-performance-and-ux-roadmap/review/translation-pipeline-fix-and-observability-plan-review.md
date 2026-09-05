# T922 Reviewer Report — Pipeline Fix and Unified Tracing Plan

## Verdict

**APPROVE AFTER PLAN REVISION.** CPU-primary bubble segmentation is the correct low-risk response to the verified QNN execution failure and reaches manual, rolling Auto, and batch through their shared recognition engine. The observability design is directionally sound, but the implementation contract needs the must-fix deltas below before coding. In particular, the current plan over-couples the stability fix to global routing refactors, does not fully guarantee terminal trace events under cancellation/stale handoff, and calls deterministic `ShortHash` output privacy-safe when it is not suitable for enumerable page/chapter identifiers.

## Evidence assessment

- **VERIFIED:** Bubble initialization currently requests acceleration, and its first real `OrtSession.run()` has no runtime recovery (`app/src/main/java/eu/kanade/translation/segmentation/OnnxBubbleSegmenter.kt:33-43,49-126`).
- **VERIFIED:** CPU-primary initialization bypasses QNN through `createSessionWithFallback(useAccelerator=false,useXnnpack=false)` and therefore removes the observed bubble QNN-1100 route (`app/src/main/java/eu/kanade/translation/runtime/onnx/OnnxRuntimeProvider.kt:56-96`).
- **VERIFIED:** Batch calls the same recognition entry point, while rolling Auto and manual use the shared single-page pipeline; the plan's mode-parity conclusion is code-grounded (`app/src/main/java/eu/kanade/translation/pipeline/batch/BatchChapterTranslator.kt:500-518`; `app/src/main/java/eu/kanade/translation/TranslationPipeline.kt:388-486`).
- **STRONG INFERENCE:** Default CPU will execute `bubble_segmenter.onnx` reliably. This is the correct route, but the retained handover supplies no controlled CPU run. Device validation in the plan is therefore required before calling the fix verified.
- **CONTRADICTION:** The prior handover attributes the manual/Auto difference to removed mode 3, while the reviewed plan says successful and failing retained sessions both used bare HTP options. The plan correctly refuses to restore mode 3 globally; the handover's causal claim must not be copied into implementation notes.
- **VERIFIED:** Debug startup automatically runs QNN diagnostics (`app/src/main/java/eu/kanade/tachiyomi/App.kt:170-175`); removing this automatic launch is necessary for clean timings.

## Must-fix plan changes

### 1. Keep the stability slice surgically independent

**Severity:** HIGH  
**Likelihood:** High  
**Class:** Design limitation

The first shippable slice should be only: CPU-primary bubble, automatic-diagnostics removal, focused routing test, and three-mode device verification. Global changes to `OnnxRuntimeProvider` and the untracked `ModelRoutingEngine` should be a separate prerequisite for truthful cross-model telemetry, not a dependency of the production failure fix. Those files are already heavily modified/owner-unknown per `repository/REPO_HEALTH.md`, and `createSessionWithFallback()` is used by detector and OCR models as well as bubble. A provider refactor can regress unrelated normal manga even when the bubble change is correct.

**Confirm/refute:** focused CPU bubble test plus manual/Auto/batch device runs; separate call-site regression tests for every `createSessionWithFallback()` user before merging the provider refactor.

### 2. Define terminal ownership for every trace before cancellation can occur

**Severity:** HIGH  
**Likelihood:** High  
**Class:** Defect in proposed lifecycle contract

`try/finally` around stage bodies is insufficient. A manual job can be cancelled before its launched coroutine body starts (`TranslationScheduler.kt:636-709`). In rolling Auto, a received `PreparedWork` is discarded by `continue` before the existing `try/finally` when it is stale (`RollingAutoCoordinator.kt:424-427`), and cancellation can occur while sending the prepared item (`RollingAutoCoordinator.kt:708-738`). If a run is created at admission as proposed, these paths can leave `run_start` without `run_end` and lane counters unbalanced.

Require an idempotent run-terminal operation and balanced lane token/handle. Specify closure at every pre-start, send-failure, stale receive, eviction, timeout, coordinator replacement, and scope-cancellation path. Manual jobs need a completion handler or equivalent owner outside the coroutine body. Schedule closure must itself be protected from exceptions in existing teardown (for example batch flush/callback work at `BatchChapterTranslator.kt:665-682`).

**Confirm/refute:** deterministic tests for cancel-before-dispatch, cancel-during-channel-send, stale item before consumer `try`, teardown exception, and repeated terminal calls; assert exactly one end per started run and zero active lane counts.

### 3. Replace `ShortHash` for page/chapter trace identity

**Severity:** HIGH  
**Likelihood:** High  
**Class:** Privacy defect

`ShortHash` is unsalted deterministic FNV-1a and explicitly disclaims cryptographic security (`app/src/main/java/eu/kanade/translation/util/ShortHash.kt:3-29`). Page keys such as `1.png` and numeric chapter IDs are enumerable and can be recovered by dictionary matching. This does not satisfy the plan's privacy-safe claim.

Use a process-random keyed digest or process-local opaque ID mapping. If a mapping is used, bound it to active schedules and release it at schedule termination. Keep raw `pageIndex` only if its diagnostic value is explicitly accepted.

**Confirm/refute:** privacy tests that precomputed hashes of common page names cannot correlate across process launches, plus a bounded-state test over a long batch.

### 4. Migrate or gate legacy translation logs, not only the new tag

**Severity:** HIGH  
**Likelihood:** Certain  
**Class:** Design limitation

The new schema forbids raw names/text, but existing manual/Auto logs still emit raw page keys, chapter names, manga titles, source IDs, and throwable messages (`TranslationScheduler.kt:627-632,668-670`; `RollingAutoCoordinator.kt:371-374,528`; `BatchChapterTranslator.kt:254-256,647-648`). Duplicate legacy lines also defeat the user's goal of one readable schedule view. The plan only explicitly retires duplicate batch timing messages.

Add an inventory and migration rule for all translation-path logs: replace correlated legacy timing/status lines after schema parity, sanitize retained errors, and keep raw native/ORT output outside the privacy-safe tag. Safe error extraction must use a bounded whitelist (`errorType`, recognized provider code) rather than copying arbitrary exception messages.

**Confirm/refute:** scan captured logs from all three modes for known manga/chapter/page/API/glossary sentinel strings and assert none occur under the translation diagnostics policy.

### 5. Specify tracing enablement and an overhead budget

**Severity:** MEDIUM  
**Likelihood:** High on long batch/rapid Auto navigation  
**Class:** Design limitation

Two events per stage plus schedule-state events can produce substantial synchronous logcat traffic. The plan gives bounded retained memory but no release/debug gate, sampling policy, or measured CPU/I/O budget. `schedule_state` on repeated viewport reconciliation is especially noisy.

Define whether detailed events are debug-only or user-enabled, keep terminal summaries and lag/error events at a deliberate level, coalesce repeated identical state transitions, and add a performance acceptance threshold. Logging must not suspend and formatter failure must never affect pipeline behavior.

**Confirm/refute:** A/B benchmark diagnostics off/on for the same long batch and scrolling Auto run; compare wall time, CPU, allocations, dropped frames, and log volume.

### 6. Correct overlap semantics and repeated-stage aggregation

**Severity:** MEDIUM  
**Likelihood:** Medium  
**Class:** Diagnostic correctness defect

Accumulating one `overlapMs` whenever at least two lanes are active measures overlap-union time, not concurrency time saved. With three lanes active for one second, work exceeds wall time by two seconds while the proposed overlap adds one. Also, one `EnumMap<Stage, Long>` must explicitly **sum** repeated translation/retry or substage intervals; overwriting produces a false bottleneck.

Report separately: lane busy totals, union-active time, overlap-union time, and `concurrencySavingsMs = sum(max(activeLaneCount - 1, 0) * delta)`. Define whether queue waits participate in page bottleneck and how repeated attempts aggregate.

**Confirm/refute:** fake-clock tests for triple overlap, nested/reentrant transitions, repeated stage attempts, and queue-dominated runs.

### 7. Resolve the legacy Auto coverage ambiguity

**Severity:** MEDIUM  
**Likelihood:** Medium  
**Class:** Scope ambiguity

The plan fully instruments `RollingAutoCoordinator`, but `TranslationScheduler.requestAutoWindow()` remains a second Auto path and invokes `translateSinglePageFromStream()` whose pipeline boundary defaults to `MANUAL` origin (`TranslationScheduler.kt:282-470`; `TranslationPipeline.kt:427-469`). Merely correlating its decisions does not guarantee correct `mode=auto/origin=auto` page traces.

Either prove the legacy path is unreachable and remove/deprecate it in a separate change, or include it in mode propagation and parity tests. Acceptance should say every reachable Auto entry point, not only rolling Auto.

**Confirm/refute:** call-site inventory from reader entry points and a test asserting the legacy path, if reachable, emits Auto identity through terminal completion.

### 8. Distinguish provider registration from execution proof

**Severity:** MEDIUM  
**Likelihood:** Medium  
**Class:** Diagnostic correctness limitation

Successful EP registration/session creation does not always prove that a provider executed model nodes. Strict QNN sessions with CPU fallback disabled provide much stronger evidence, but XNNPACK/default sessions do not. The proposed typed `RegisteredExecutionProvider` should not be described universally as an execution fact.

Represent provenance explicitly (`requested`, `registered`, `effective/proven`) or limit `provider=` claims to strict/full-partition routes and known CPU fallback. Do not mark support until representative inference and output-contract validation succeed.

**Confirm/refute:** forced registration failure tests, strict QNN partition rejection, successful representative inference, and validation that CPU fallback cannot retain an accelerator label.

## Optional improvements

- Add `traceEnabled`, schema version, app build, ORT version, and model revision to one schedule-start event rather than every stage line; this makes cross-build comparisons trustworthy with constant overhead.
- Emit p50/p95 only offline; the on-device accumulator should remain O(1) and report counts/max/means unless a bounded histogram is explicitly accepted.
- Label setup and batch translation-envelope work at schedule scope; page waits may reference the envelope ID but must not duplicate provider duration.
- Add cold-start versus warm-session/cache flags because engine setup and QNN context-cache creation can otherwise dominate comparisons.
- Define clock overflow/clamping and lane-token misuse behavior as fail-open diagnostics, never an application exception.

## Recommended sequencing

1. Minimal stability patch and focused tests; validate default CPU bubble in manual, rolling Auto, and batch while confirming AOT remains QNN HTP.
2. Trace core with keyed opaque identities, idempotent terminal handles, thread-safe overlap math, a diagnostics gate, and privacy/overhead tests.
3. Manual plus every reachable Auto entry point, including cancellation/stale-handoff tests.
4. Batch wiring and envelope attribution, then legacy-log retirement.
5. Provider/routing-state refactor as an independently reviewed change with all detector/OCR/AOT call-site regressions covered.

No production source should be changed until the must-fix lifecycle, privacy, overhead, overlap, and reachable-mode contracts are incorporated into the plan.
