---
title: "Phase 3 Slice S3 Final Code and Verification Review: Protected Timeout Callback Chain (Revision r1)"
kind: review
comments: none
---

# Phase 3 Slice S3 Final Code and Verification Review (Revision r1)

**Verdict: APPROVE**

This is the independent cold-read review of Slice S3 ([tickets/timeout-adapter](..)) in Revision r1, evaluating the protected timeout callback chain at the `EngineLane.withNativeLane` choke point, hardened unit test teardown lifecycles in `EngineLaneTimeoutAdapterTest.kt`, and dual-flavor test execution evidence ([results](../results)).

All confirmed findings from Revision r0 have been resolved: test gate lifetimes encompass the full async lifecycle inside `try-finally`, and true dual-flavor verification has been established across `testStandardDebugUnitTest` and `testDevDebugUnitTest` (6/0 + 6/0 green).

## 1. Verified Source Hashes (Revision r1)

| File | Size (bytes) | SHA-256 Hash | Status |
| --- | --- | --- | --- |
| `app/src/main/java/eu/kanade/translation/pipeline/EngineLane.kt` | 31,902 | `5928E8C711AE7C4E98087B36BC6A61ED8763D7051AAD09237CB0A02D62036338` | Verified (Unchanged, choke point approved) |
| `app/src/test/java/eu/kanade/translation/pipeline/EngineLaneTimeoutAdapterTest.kt` | 13,182 | `DEA42CFF3657E043FC102A8E72ED6C09BA386E795B1379550500B1A92C08C197` | Verified (Revision r1 gate teardown hardened) |

## 2. Verification of Revision r1 Corrections

### 1. Test Teardown Hardening (Finding F1 Resolved)

- **Source Inspection (`EngineLaneTimeoutAdapterTest.kt:65-310`)**:
  - Across all 4 tests (`throwing marker`, `throwing onPageStuck getter`, `throwing onPageStuck invoke`, and `caller cancelled while native block held`):
    - Child `Deferred` references (`var first: Deferred<String?>? = null`, `var second: ...`, `var caller: ...`) are declared as nullable variables *before* any coroutines are launched.
    - A single enclosing `try` block immediately wraps the entire gate-holding lifetime, from the first `async` launch, through virtual clock advances (`advanceTimeBy`, `testScheduler.runCurrent()`), through all intermediate and final assertions.
    - The `finally` block unconditionally ensures that if `firstMayExit` is not yet completed, it is completed (`if (!firstMayExit.isCompleted) firstMayExit.complete(Unit)`), and any active coroutines (`first`, `second`, `caller`) are cancelled.
  - This eliminates the pre-try vulnerability identified in Revision r0: assertion failures at any stage can no longer strand the native worker parked on `firstMayExit.await()`.

### 2. Dual-Flavor Verification & Task Parity (Finding F2 Resolved)

- **Task Verification**:
  - `r1-std` ran task `:app:testStandardDebugUnitTest` (verified directly from log line: `> Task :app:testStandardDebugUnitTest`).
  - `r1-devdebug` ran task `:app:testDevDebugUnitTest` (verified directly from log line: `> Task :app:testDevDebugUnitTest`).
  - The erroneous `StandardRelease` runs from Revision r0 have been truthfully disclosed and excluded from the accepted evidence set.
- **Results**:
  - `EngineLaneTimeoutAdapterTest`: 4/4 passed on both flavors.
  - `NativeRunQuarantineTest`: 2/2 passed on both flavors (regression check).
  - Total: **6/0 Green** on StandardDebug (29s) and **6/0 Green** on DevDebug (31s) with `S1_GUARD_BREACH=0`.

### 3. Spotless & Cost Discipline (Finding F3 Resolved)

- `format-r1/spotless-check.log` (shell `be612d6b-ba1a-4909-a2a1-8312c233bf41`): Shows zero violations in `EngineLane.kt` or `EngineLaneTimeoutAdapterTest.kt`. The only violations reported are the known pre-existing debt hunks in `LiteRTMangaInpaintingEngine.kt:160-163, 357-360`.
- In compliance with cost discipline, Revision r1 executed strictly the minimum authorized pair of gates (`r1-std` and `r1-devdebug`) followed by a single format check without duplicate runs.

## 3. Dual-Flavor Verification Evidence (Revision r1)

Guarded execution ran with mechanical preflight checks (`PREFLIGHT_OK` with atomic `.launch-claim` verification) under evidence root `C:\Users\ADMIN\AppData\Local\Temp\opencode\phase-3-s3`:

- **Standard Debug (`testStandardDebugUnitTest`)**:
  - Registered Shell: `84d0da62-a0bf-4008-96d2-21e515ecfe28`
  - Guard Run ID: `b70f50a9-377f-4180-9319-de53396cad87`
  - Directory: `r1-std`
  - Tests: `EngineLaneTimeoutAdapterTest` 4/4, `NativeRunQuarantineTest` 2/2 (**6/0 Green**, 0 failures, 0 errors, 0 skipped in 29s)
  - Exit code: 0, `S1_GUARD_BREACH=0`
  - XML reports verified at `r1-std/xml/`
- **Dev Debug (`testDevDebugUnitTest`)**:
  - Registered Shell: `df1c97e4-b02d-48b2-b0fd-330d92d96666`
  - Guard Run ID: `42fb061e-8de8-4f86-90f1-a2cb438de0fe`
  - Directory: `r1-devdebug`
  - Tests: `EngineLaneTimeoutAdapterTest` 4/4, `NativeRunQuarantineTest` 2/2 (**6/0 Green**, 0 failures, 0 errors, 0 skipped in 31s)
  - Exit code: 0, `S1_GUARD_BREACH=0`
  - XML reports verified at `r1-devdebug/xml/`

## 4. Historical Progression Record

| Iteration | Hashes / Flavor Counts | Status | Reason / Disposition |
| --- | --- | --- | --- |
| **Initial Delivery (Revision r0)** | `5928E8C7` (Lane), `7F18D213` (Test) / 6 green | **REVISIONS REQUIRED** | Pre-try gate lifetime unhardened; DevDebug leg omitted (ran StandardRelease instead); duplicate test runs. |
| **Revision r1 Delivery** | `5928E8C7` (Lane), `DEA42CFF` (Test) / 6 green | **APPROVED** | Gate lifetime wrapped in single try-finally; true DevDebug executed & green (6/0); spotless clean. |

## 5. Scope & Boundary Disclosures

- **Host-Only Verification**: Evidence is derived from JVM virtual-time unit tests. Actual hardware device liveness and real OS clock behavior remain bounded to plan device gates (D-gates).
- **Callback Cancellation Semantics**: If a callback throws `CancellationException`, it is rethrown to `NativeRunQuarantine`, whose pre-existing `catch (CancellationException)` (:103) awaits real block exit. The 6 host tests prove marker, getter, invoke, and caller cancellation; they do not simulate callback-injected CE.
- **Production Scope Pin**: Slice S3 adapts solely the timeout callback chain at the `EngineLane.withNativeLane` choke point. Production owner cutover (`AttemptAuthority` and `CleanedOwner` wiring) remains strictly deferred to Slice S5.

## 6. Next Slice Authorization

Slice S3 is complete, verified, and **APPROVED**. Ready for parent coordinator commit and push. Dependent Slice S4 implementation remains held until parent commit is completed:

- **Slice S4**: Publication policy ([tickets/publication-policy](../publication-policy) — non-durable and durable publication separation, boundary flushes, and commit preconditions).
