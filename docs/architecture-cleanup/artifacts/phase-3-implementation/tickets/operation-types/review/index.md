---
title: "Phase 3 Slice S1 Final Code and Verification Review: AttemptAuthority and CleanedOwner (Revision r1)"
kind: review
comments: none
---

# Phase 3 Slice S1 Final Code and Verification Review (Revision r1)

**Verdict: APPROVE**

This is the final independent review of Slice S1 ([tickets/operation-types](..)), covering the foundation primitives (`AttemptAuthority` and `CleanedOwner`), deterministic unit tests, and dual-flavor verification evidence ([results](tickets/operation-types/results)).

All four parent hold directives and bounded corrections have been implemented, verified in source code, and proven green across both build flavors under guarded execution.

## 1. Verified Source Hashes (Revision r1)

| File | Size (bytes) | SHA-256 Hash |
| --- | --- | --- |
| `app/src/main/java/eu/kanade/translation/pipeline/AttemptAuthority.kt` | 13,926 | `964B1DC975AC70B45727ABD996AF59686C776CB66AE0D627823B764B31D29C66` |
| `app/src/test/java/eu/kanade/translation/pipeline/AttemptAuthorityTest.kt` | 7,598 | `9B84831D747CF1CF1A7B66CA438742A135FAAB9BF16AB09CCBD1C83DF348791C` |
| `app/src/test/java/eu/kanade/translation/pipeline/CleanedOwnerTest.kt` | 10,912 | `8F97390A5BAD879942FB69D3654A53CAA72E056C41EA4701A6F2E7920AF0653A` |

## 2. Verification of Revision r1 Corrections

### 1. Fail-Closed Physical Exit on Receipt Failure

- **Fail-Closed Semantics**:
  - Normal boolean completion (`true` on successful persist, `false` on abort/suppression) successfully satisfies `await()`, drains open borrows, and recycles the bitmap.
  - An exceptional completion (`completeExceptionally`) or external cancellation (`cancel()`) propagates immediately out of `release()`. The releaser does **not** proceed to `openBorrows` or `bitmap.recycle()`, leaving `slot` un-recycled and preventing use-after-free races against an unverified background worker.
- **Test Verification (`CleanedOwnerTest.kt:133-195`)**:
  - `an exceptional receipt completion fails release without recycling`: Asserts that `releaseJob.await()` throws `IllegalStateException("receipt protocol violation")` and verifies `bitmap.recycle()` was called **exactly 0 times**.
  - `a cancelled receipt fails release without recycling`: Asserts that `releaseJob.await()` throws `CancellationException` and verifies `bitmap.recycle()` was called **exactly 0 times**.

### 2. Test Teardown Hardening (`finally` Cleanup)

- **Source Verification (`CleanedOwnerTest.kt:113-131, 145-163, 175-194`)**:
  - In `a suppressed receipt completion still releases`, the test body is enclosed in `try-finally` with `if (!receiptCompleted) receipt.complete(false)`, ensuring any assertion failure before completion does not leave `releaseJob` indefinitely suspended.
  - In both failure-asserting tests, the child coroutine runs under an isolated `CoroutineScope(SupervisorJob() + testScheduler)` to prevent uncaught child exceptions from tearing down the test root, and the `finally` block cancels both `releaseJob` and `releaseScope`.
  - The Attempt 2 stranded-worker defect cannot recur.

### 3. Concurrency Coverage Boundaries Grounded

- **CO-01 Precision**: Documented strictly as a sequential lifecycle ordering guard (`check(!released.get()) { "registerReceipt after release started" }`), not an atomic synchronization claim over concurrent registration and release.
- **AttemptAuthority Async Coverage**: Documented as verifying field-merging ordering across concurrent writers under the authority's mutex, not held-store contention (which is governed at S2 store enrollment).

### 4. Portable Linking and Slice Hierarchy

- Disallowed @/// URIs replaced with relative markdown links.
- Pipeline slice sequence confirmed: Slice S1 (AttemptAuthority & CleanedOwner) -> Slice S2 (writer-receipt enrollment & store integration) -> Slice S3 (native multi-segment adapter).

## 3. Dual-Flavor Verification Evidence (Revision r1)

Verification ran under guard v3.4 in evidence directory `C:\Users\ADMIN\AppData\Local\Temp\opencode\phase-3-s1\2026-10-15T20-08-45Z\revision-r1`:

- **Standard Debug (`testStandardDebugUnitTest`)**:
  - Registered Shell: `b00f5484-8f89-40cb-a167-dc0336c70f28`
  - Guard Run ID: `dabbc714-ced5-488e-b6ee-433e81021102`
  - Tests: `AttemptAuthorityTest` 7/7, `CleanedOwnerTest` 12/12 (Total: **19/19 Green**, 0 failures, 0 errors, 0 skipped in 27s)
  - Exit code: 0, `S1_GUARD_BREACH=0`
  - XML reports verified at `std-tests/xml/`
- **Dev Debug (`testDevDebugUnitTest`)**:
  - Registered Shell: `288f5c59-5df3-4ac5-9571-e74970bc0268`
  - Guard Run ID: `0954a3d4-08ca-4062-aedd-bd87c156ef67`
  - Tests: `AttemptAuthorityTest` 7/7, `CleanedOwnerTest` 12/12 (Total: **19/19 Green**, 0 failures, 0 errors, 0 skipped in 56s)
  - Exit code: 0, `S1_GUARD_BREACH=0`
  - XML reports verified at `dev-tests/xml/`
- **Spotless Check**:
  - Registered Shell: `a43ff30a-809b-4e3d-83fd-934920b18c24`
  - Guard Run ID: `3bb72c42-ac22-44ae-b437-b62d8d8aeb9f`
  - Verified: Zero formatting violations in all three S1 files. (Single pre-existing debt in `LiteRTMangaInpaintingEngine.kt:160-163` unchanged).
- **Failure Provenance Disclosure**:
  - Revision attempt 1 failure log retained at `std-tests/guarded-run.revision-attempt1-scopefail.log` (19 tests, 1 failure due to un-supervised `async` child in `runTest`).
  - Overwrite of attempt 1 XML by attempt 2 disclosed truthfully per validation rules.

## 4. Historical Progression Record

| Iteration | Hashes / Flavor Counts | Status | Reason / Disposition |
| --- | --- | --- | --- |
| **Initial S1 Delivery** | `86DF11FE` (AA), `3D1A417F` (CO) / 18 green | **REVISIONS REQUIRED** | Blanket catch in `release()` recycled on failure; receipt tests lacked `finally`; concurrency claims over-broad. |
| **Revision r1 Delivery** | `964B1DC9` (AA), `8F97390A` (CO) / 19 green | **APPROVED** | Fail-closed physical exit enforced; `finally` teardown hardened; 19/19 green on both flavors without breach. |

## 5. Next Slice Authorization

Slice S1 is complete, verified, and **APPROVED**. Production call sites remain safely unwired. Implementation may proceed to Slice S2:

- **Slice S2**: Writer receipt enrollment and store integration (`ChapterTranslationStore.enqueueLazyPersistence` receipt wiring and boundary enrollment).
