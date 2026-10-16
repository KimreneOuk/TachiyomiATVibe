---
title: "P1.5 code review - consolidated validation & cleanup evidence"
kind: review
comments: none
---

# Executive Summary

This independent code review evaluates the consolidated evidence, full-suite test results, follow-up lint remediation, and fixture finalization for **Phase 1 Slice 5 (P1.5)** on branch `architecture-cleanup`.

- **Verdict:** **CONSOLIDATED VALIDATION & FIXTURE FINALIZATION ACCEPTED (COMMITTED IN DAB5C124 / PHASE 1 HOST CLOSURE APPROVED)**.
- **Production Cleanup Integrity:** The five locally committed Phase 1 slices (P1.1 `b452499c`, P1.2 `a9c38fb5`, P1.3a `444b5abf`, P1.3b `1c1d247e`, P1.4 `b44143f2`), the coordinator-committed lint fix `f39cfd2a`, and the fixture finalization commit `dab5c124` (+52 / -13 lines) remain strictly accepted and verified across all 7 commits.
- **Cumulative Metrics:**
  - Net cumulative lines removed: **4,122 lines** (+641 / -4,763 lines across 23 paths; commit `dab5c124`).
  - Production source count: **284 source files** (282 .kt, 2 .java).
  - Ordinary unit test count: **315 files** (.kt).
- **Follow-up Lint Remediation:**
  - Verified and committed under `f39cfd2a`: resolved all 3 cleanup-introduced issues (`CollisionRelaxation`, `BatchParallelWindowBudgetTest`, `ChapterTranslatorQueueRestoreTest`).
  - Spotless debt: Pre-existing `LiteRTMangaInpaintingEngine.kt` wrapping hunks confirmed verbatim at baseline `4de121bf` and properly retained as baseline debt outside Phase 1 scope.
- **Fixture Finalization & Full-Suite Standard Recheck (Shell `082a2522`):**
  - Addressed the established lifecycle omission in `ProfileEnvelopeDispatchTest` via deterministic `@AfterEach` teardown barrier draining all stores and engines. Test N2 passed in the observed post-change full run (in 0.209s). Original actor/race attribution and universal stability remain unproven.
  - Fresh full StandardDebug recheck: Exactly **329 classes / 2,234 test executions / 4 failures / 0 errors / 1 skipped**.
  - Exactly the 4 ledgered baseline `ChapterTranslationStoreArtifactOpenProbeTest` failure methods failed; `ProfileEnvelopeDispatchTest` passed 20/20.
  - DevDebug full-suite baseline: 329 classes / 2,234 tests / 4 failures / 1 skipped (same 4 baseline failures).
  - Domain suite: 6 classes / 66 tests / 0 failures (100% pass).
- **User report integrity:** SHA256 verified byte-identical (`EFC11B90A7D0B367F3878BD600BF893AA99ECCDB5879D902E10EB6E06EF88DDD`).
- **Gating Dispositions:**
  - The 4 known `ChapterTranslationStoreArtifactOpenProbeTest` failures remain tracked and form the explicit entry gate for Phase 3/4 storage contract work.
  - Physical device measurements remain deferred per user instruction.

# 1. Full-Suite Test Evidence & Failure Audit

### 1.1 Repaired Baseline Failure (P1.3a)

- `TranslationDebugMeasurementTest.file sink preserves queue order and sanitizes embedded line breaks`:
  - Baseline (P0): Failed in both flavors due to unanchored prefix formatting drift.
  - Consolidated (P1.5): **PASSES in both DevDebug and StandardDebug** (3 tests, 0 failures per flavor, including the added passthrough case).

### 1.2 Tracked Baseline Failures (4 Methods Literal-Identical)

Four methods in `ChapterTranslationStoreArtifactOpenProbeTest` failed in both flavors at baseline and continue to fail with identical signatures:

1. `lazy store cuts over before first candidate and reopens from artifact pointers()`: `java.lang.AssertionError: Expected "page.cleaned.retry.jpg" but actual was null`.
2. `pre-registered pages publish one durable baseline with only the changed candidate()`: `io.kotest.assertions.AssertionFailedError: expected:<["p1.jpg", "p2.jpg", "p3.jpg"]> but was:<[]>`.
3. `artifact-only lazy store writes pages without materializing flat compatibility file()`: `org.junit.platform.commons.JUnitException: Failed to close extension context`.
4. `lazy store without parent resolves artifact authority through artifactParentResolver()`: `org.junit.platform.commons.JUnitException: Failed to close extension context`.

Note: The entity holding the open handle on `Chapter {2,8}.manifest.json` at extension context cleanup is technically unresolved, rather than proven from unclosed stores.

### 1.3 Observed Post-Change ProfileEnvelopeDispatchTest.N2 Outcome

- Following the fixture finalization barrier (`@AfterEach fun finalizeOwnedResources()`), the established lifecycle omission was addressed, and test N2 passed in the observed post-change full run (in 0.209s). Fresh execution in shell `082a2522` confirms:
  - Narrow DevDebug: 20 tests / 0 failures.
  - Narrow StandardDebug: 20 tests / 0 failures (exit 0).
  - Full StandardDebug suite: 20 tests / 0 failures for `ProfileEnvelopeDispatchTest` (N2 passed in 0.209s).
- The full suite returned cleanly to the expected 4-failure baseline ledger in the observed fresh run. Original actor/race attribution and universal stability remain unproven.

# 2. Build & Command Provenance Audit

- `assembleStandardDebug`: Verified BUILD SUCCESSFUL in 4m 11s (log `4ac69adc-937a-48b4-b25f-bdb127a75302`, line 34900).
- `assembleDevDebug`: Verified BUILD SUCCESSFUL in 35s cached exit 0.
- Command provenance across both debug assemble variants and full test suites is complete and verified.

# 3. Phase 1 Exit Gates & Final Disposition

| Exit Gate | Current Status | Disposition |
| --- | --- | --- |
| 5 Accepted Cleanup Slices | **ACCEPTED** | Slices P1.1 through P1.4 verified sound. |
| Test Parity Accounting | **VERIFIED** | 329 classes / 2,234 tests per flavor (exact match). |
| Baseline Failure Comparison | **VERIFIED** | 1 repaired (timestamp), 4 tracked baseline failures confirmed. |
| Lint Remediation | **COMMITTED** | Committed under `f39cfd2a`; LiteRT baseline debt retained. |
| N2 Fixture Finalization | **COMMITTED** | Committed under `dab5c124`; verified green in full StandardDebug run. |
| Physical Device Baseline | **DEFERRED** | Deferred per user choice; gate not waived. |

### Final Conclusion

All Phase 1 cleanup slices, lint remediations, and fixture finalization barriers have been independently reviewed, committed across 7 commits, and verified against fresh execution evidence. No unexplained regressions exist in the observed fresh full runs.

**Phase 1 Host Cleanup is APPROVED FOR CLOSURE & COMMITTED IN DAB5C124.**
