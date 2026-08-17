# Deferred — P0-1, P0-3, P0-4 regression tests

**Date:** 2026-07-12
**Decision:** defer. Needs approval-gated refactor.

## Why deferred

All three fixes live behind `private` members that cannot be driven from a JVM
test without constructing the full Android+ONNX pipeline (which is not
JVM-testable):

| Fix | Private member | Why blocked |
|---|---|---|
| P0-1 SIGSEGV guard | `nativeGuard: Mutex` in `RoiPageRecognitionEngine.kt:87` | To prove skip-when-held, test must hold the lock. Mutex is private. |
| P0-3 clear-at-top | `closeEngines()` private in `TranslationPipeline.kt:468` | Test cannot invoke. `inFlightPageKeysSnapshot()` probe exists but has no trigger. |
| P0-4 watchdog throw-isolation | `withLeakProofPermit()` private suspend | Test cannot invoke the permit block. |

The honest fix is the ShortHash pattern: extract each logic unit into an
`internal` pure helper. Examples:

- P0-1: extract `forceReleaseNativeBuffers` drain into
  `internal fun drainChildBuffers(engines: List<PageRecognitionEngine>)` and
  test that the drain is gated by an injected lock state.
- P0-3: extract `closeEngines` clear-then-acquire into an internal helper
  parameterized by the key set + permit.
- P0-4: extract the watchdog callback chain into an internal pure runner
  taking the three callbacks + a rethrow policy.

Each extraction changes the production class shape. Per AGENT.md autonomy
policy, "architecture rewrites or broad cross-module refactors" require user
approval. These are localized but they DO change the structure of two
load-bearing classes (`RoiPageRecognitionEngine`, `TranslationPipeline`).

## Stop condition met

The task brief's stop condition says: "If a test cannot be made RED-first
without a larger refactor, defer it with a documented reason; do not write a
theater test." This is that case.

## What ships instead

- A structural/inspection guard via reflection is NOT being written. Such a
  test (e.g. "assert `nativeGuard.tryLock` literal appears in the source via
  reflection") is theater: it asserts source text, not behavior, and breaks
  on any equivalent rewrite.
- The previously-added test seams (`MainDispatcherRule`,
  `inFlightPageKeysSnapshot`, `permitHolderPageKeySnapshot`) were DELETED on
  2026-07-12 per user direction ("if it is dead, then kill it"). They had
  zero callers. The deferred refactor below can re-introduce the seams it
  actually needs, scoped to the helper it extracts — no point carrying
  speculative API.
- Manual verification stays the gate (progress.md "Validation history" +
  SUBAGENT_TEST_CONTRACTS "Manual-Only Checklist"): `adb shell am send-trim-memory
  <pid> RUNNING_CRITICAL` mid-inference for P0-1; permit-contention scenario
  for P0-3; logcat watchdog-threw lines for P0-4.

## Next safe action to unblock (requires approval)

Propose the ShortHash-style extraction as a small, separate refactor task:
three internal pure helpers, no behavior change, each with a RED-first test.
Bring back for approval before executing.
