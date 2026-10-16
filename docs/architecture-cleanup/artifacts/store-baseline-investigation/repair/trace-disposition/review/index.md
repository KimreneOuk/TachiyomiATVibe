---
title: "Review and causal disposition of full-rebaseline trace failure"
kind: review
comments: none
---

# Review Verdict: REPAIR HELD PENDING TRACE COMPLETION FIX; STORE FIX VERIFIED

The full application test rebaseline results and trace completion boundary investigation are **DISPOSITIONED AS FOLLOWS**:

1. **Store Fixture Repair (ProbeTest) Holds Under Full Suite**:
  - Across both full application suites (332 test classes, 2,254 tests per flavor), all 14 tests in `ChapterTranslationStoreArtifactOpenProbeTest` passed cleanly (14/0 in `DevDebug`, 14/0 in `StandardDebug`).
  - The 3-line manifest reader `.use { ... }` scoping fix is conclusively verified under full app test execution and does not cause collateral regressions.
2. **Authoritative Failure in DevDebug Reported Honestly**:
  - `StandardDebug`: 332 classes, 2,254 tests, 0 failures, 0 errors, 1 skipped (`BUILD SUCCESSFUL` in 3m 6s, `GRADLE_EXIT: 0`).
  - `DevDebug`: 332 classes, 2,254 tests, 1 failure, 0 errors, 1 skipped (`BUILD FAILED` in 3m 20s, `GRADLE_EXIT: 1`).
  - While the wrapper execution script reported exit 0, the authoritative Gradle exit is **1**. Per reporting integrity standards, this is recorded as a genuine build failure.
  - The single skipped test in both suites is the expected benchmark `SyntheticJournalReplayBenchTest.synthetic200PageJournalFullReplayBenchmark()`.
3. **Overall Closure & Phase 3 Entry Held**:
  - Overall epic closure and Phase 3 entry are **HELD**. Approval cannot be granted with an unresolved test failure in `DevDebug`.
  - A dedicated test-only fix for `TranslationSchedulerTraceTest` must be implemented and verified before establishing a clean baseline.

# Causal Analysis of the DevDebug Failure

The single failure occurred in:

- **Suite**: `eu.kanade.translation.scheduling.TranslationSchedulerTraceTest`
- **Test**: `cancelled manual intent closes exactly once as cancelled()`
- **Location**: `TranslationSchedulerTraceTest.kt:164`
- **Error**: `io.kotest.assertions.AssertionFailedError: expected:<1> but was:<0>`

### 1. The Observation Window Defect

Audit of `TranslationScheduler.kt` and `TranslationSchedulerTraceTest.kt` reveals a clear, structural race condition in the test harness synchronization:

In `TranslationScheduler.kt` (lines 438–446):

```kotlin
job.invokeOnCompletion { _ ->
    val outcomeToken = traceOutcome.get()
        ?: if (job.isCancelled) {
            TranslationTraceOutcome.CANCELLED
        } else {
            TranslationTraceOutcome.TEARDOWN_EXCEPTION
        }
    leaseWaitSpan.end()
    run.end(outcomeToken)      // Line 443: emits "run_end"
    schedule.end(outcomeToken) // Line 444: emits "schedule_end"
}
```

In `TranslationSchedulerTraceTest.kt` (lines 150–165):

```kotlin
try {
    val (manga, chapter, source) = mockSession()
    scheduler.translatePage(manga, chapter, source, "p0")
    executor.awaitTranslateStarted()
    awaitCondition { lines("run_end").isNotEmpty() } // <-- Synced to intermediate event
} finally {
    scheduler.close() // Calls scope.cancel(), does NOT join invokeOnCompletion
}

val runEnds = lines("run_end")
runEnds.size shouldBe 1
field(runEnds.single(), "outcome") shouldBe "cancelled"
lines("run_start").size shouldBe 1
lines("schedule_end").size shouldBe 1 // <-- Fails here: expected 1, was 0
```

### 2. Failure Sequence

1. The coroutine execution is cancelled by `ManualFakeExecutor(throwCancellation = true)`.
2. Kotlin Coroutines triggers `job.invokeOnCompletion` on a worker thread.
3. The completion handler executes line 443: `run.end(outcomeToken)`. This publishes `run_end` to `TranslationPipelineDiagnostics.sink`, which appends it to `capturedLines`.
4. The test thread, polling in `awaitCondition { lines("run_end").isNotEmpty() }`, sees `run_end` and immediately unblocks.
5. The test thread executes the `finally` block: `scheduler.close()`, which executes `scope.cancel()`. `scope.cancel()` does not join or wait for running completion handlers.
6. The test thread proceeds directly to assert `lines("schedule_end").size shouldBe 1`.
7. If the worker thread executing `invokeOnCompletion` has not yet executed line 444 (`schedule.end(outcomeToken)`), `schedule_end` has not yet been delivered to `capturedLines`.
8. Kotest asserts `expected:<1> but was:<0>` on line 164.

### 3. Vulnerability in Sibling Test Cases

The identical completion boundary flaw exists across all three methods in `TranslationSchedulerTraceTest.kt`:

- **Method 1** (`manual translate emits one schedule and one run...`): Awaits `scheduler.manualOutcomeFor(7L, "p0") != null`. This outcome is set in the coroutine body *before* `invokeOnCompletion` is even triggered. The test asserts `run_end` and `schedule_end` while relying on unjoined timing.
- **Method 2** (`cancelled manual intent closes exactly once...`): Awaits `run_end`, then asserts `schedule_end`. This was caught by the DevDebug run.
- **Method 3** (`cancel before dispatch still closes...`): Closes the scheduler, awaits `run_end`, and immediately asserts `schedule_end`.

# Architectural & Causal Safeguards

1. **Not Baseline Flakiness**:
 This failure must not be dismissed as "flaky test debt" or ignored because the file was untouched in Phase 2. It is a genuine synchronization boundary defect exposed under multi-suite execution load.
2. **Production Scheduler is Correct**:
 The hierarchy of trace events in `TranslationScheduler` is architecturally sound:
  - `schedule` is the outer parent span representing the user/caller intent.
  - `run` is the inner execution span representing coroutine execution.
  - When a job completes, `run.end()` finishes the run, and `schedule.end()` finishes the schedule.
  - `schedule.end()` is the ultimate terminal event for the request.
  - Production `close()` is non-blocking (`scope.cancel()`); adding blocking joins or mutex locks to production `close()` is unnecessary and harmful to UI responsiveness.
3. **No Sleeps or Global Synchronization Rewrites**:
 Arbitrary `Thread.sleep` or rewriting global diagnostics logging to be synchronous across all threads is prohibited. Synchronization belongs cleanly at the test observation boundary.

# Prescribed Test-Only Minimal Repair & Proof Requirements

### 1. Minimal Test Repair Strategy

The test harness must synchronize on the **outer, final terminal event** of the intent lifecycle: `schedule_end`.

In `TranslationSchedulerTraceTest.kt`:

- For **Method 2** (`cancelled manual intent...`):
Change `awaitCondition { lines("run_end").isNotEmpty() }` to:
    ```kotlin
    awaitCondition { lines("schedule_end").isNotEmpty() }
    ```
- For **Method 3** (`cancel before dispatch...`):
Change `awaitCondition { lines("run_end").isNotEmpty() }` to:
    ```kotlin
    awaitCondition { lines("schedule_end").isNotEmpty() }
    ```
- For **Method 1** (`manual translate emits...`):
In addition to (or replacing) awaiting `manualOutcomeFor`, await:
    ```kotlin
    awaitCondition { lines("schedule_end").isNotEmpty() }
    ```

### 2. Invariants Guaranteed by Final Terminal Synchronization

Because `invokeOnCompletion` executes sequentially:

1. `leaseWaitSpan.end()` runs first.
2. `run.end(outcomeToken)` runs second.
3. `schedule.end(outcomeToken)` runs third.

Awaiting `schedule_end` guarantees that both `leaseWaitSpan` and `run` have fully ended and published their trace lines to `capturedLines`. This allows all downstream assertions to remain 100% intact:

- Exact cardinalities (`scheduleStarts.size shouldBe 1`, `runStarts.size shouldBe 1`, `leaseWaitEnds.size shouldBe 1`, `runEnds.size shouldBe 1`, `schedule_end.size shouldBe 1`).
- Exact outcome tokens (`success`, `cancelled`).
- Correlation IDs (`sid`, `rid`).
- Real-thread coroutine semantics.

### 3. Proof Requirements Before Rebaseline Acceptance

1. **Deterministic Reproducer**: Verify the race mechanism using the existing `TranslationTraceSink` test hook (e.g. holding or observing the gap between `run_end` and `schedule_end`).
2. **Focused Verification**: Run `TranslationSchedulerTraceTest` under both `DevDebug` and `StandardDebug` variants (100% passing).
3. **Full Suite Re-run**: Execute `:app:testDevDebugUnitTest` and `:app:testStandardDebugUnitTest` with zero filters, verifying 332 classes / 2,254 tests with 0 failures, 0 errors, 1 skip on both variants.

# Gate Disposition Summary

| Gate / Work Item | Status | Action Required |
| --- | --- | --- |
| **Store Probe Test Repair** | **ACCEPTED** | Verified 14/0 across both full application suites. Fix remains staged. |
| **Trace Test Boundary Synchronization** | **REPAIR REQUIRED** | Implement final-terminal wait (`schedule_end`) across the 3 trace test cases. |
| **Full Rebaseline & Phase 3 Entry** | **HELD** | Await trace fix verification and full clean suite re-run. |
