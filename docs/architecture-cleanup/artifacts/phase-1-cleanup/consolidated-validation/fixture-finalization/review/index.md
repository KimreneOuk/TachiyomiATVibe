---
title: "P1.5 fixture-finalization code review - ProfileEnvelopeDispatchTest teardown barrier"
kind: review
comments: none
---

# Executive Summary

This independent code review evaluates the changes and fresh test execution evidence for **Phase 1 Fixture Finalization** on branch `architecture-cleanup` (committed in `dab5c124`).

- **Verdict:** **ACCEPTED (APPROVED; COMMITTED IN DAB5C124)**.
- **Diff summary:** Exactly 1 file modified (`app/src/test/java/eu/kanade/translation/pipeline/batch/ProfileEnvelopeDispatchTest.kt`), **+52 / -13 lines (net +39 lines)**. Zero production code modified.
- **Integrity checks:** User report `TachiyomiATVibe_Translation_Simplicity_First_Architecture_Report.md` SHA256 verified byte-identical (`EFC11B90A7D0B367F3878BD600BF893AA99ECCDB5879D902E10EB6E06EF88DDD`).
- **Teardown Barrier Mechanics:** Introduces an explicit `@AfterEach fun finalizeOwnedResources()` barrier that drains all registered `ChapterTranslationStore` instances via `withContext(NonCancellable) { store.closeAndFlush() }` and cancels all registered standalone `ChapterArtifactEngine` instances via `withContext(NonCancellable) { engine.cancelRetentionWorkAndJoin() }`.
- **Fault-Tolerant Aggregation:** Cleanup attempts every registered resource regardless of earlier failures, aggregating subsequent exceptions via Java's `Throwable.addSuppressed()`.
- **Test Integrity:** Zero test assertions, mock behaviors, execution flows, or reopen orders were modified.

# 1. Technical Audit of the Teardown Barrier

### 1.1 Store & Engine Registration Coverage

- **Lazy Store Helper Registration:** `private fun lazyStore(): ChapterTranslationStore` registers every created lazy store instance into `ownedStores += it` directly inside the helper, ensuring all 20 test methods that acquire stores via `lazyStore()` are tracked.
- **Reopened Store Instances:** All 7 direct `ChapterTranslationStore.openArtifact(root(), "Chapter 1.json")` call sites across the test methods (lines 536, 648, 755, 932, 1041, 1116, 1220) are explicitly registered via `.also { ownedStores += it }`.
- **Standalone Artifact Engine Helper Registration:** `private fun artifactStore(): ChapterArtifactEngine` registers standalone engine instances directly inside the helper (`.also { ownedArtifactEngines += it }`), covering all 4 standalone engine query/read call sites (lines 361, 503, 1031, 1062) without modifying individual caller sites.
- **Audit finding:** Registration coverage of store and engine instances across `ProfileEnvelopeDispatchTest.kt` is complete and robust (centralized inside constructor/factory helpers plus explicit registration at the 7 reopen sites).

### 1.2 JUnit 5 Lifecycle & Ordering Invariants

- **Ordering Guarantee:**
  - In JUnit 5, `@AfterEach` lifecycle callbacks on the test instance execute within the `AfterEachCallback` execution phase of `NodeTestTask.executeRecursively`.
  - JUnit's `@TempDir` extension teardown (`TempDirectory$CloseablePath.close` walking and deleting the temp directory) is registered in the method extension context and executes subsequently during `NodeTestTask.cleanUp` -> `TestMethodTestDescriptor.cleanUp` -> `JupiterEngineExecutionContext.close` -> `AbstractExtensionContext.close`.
  - Therefore, `@AfterEach fun finalizeOwnedResources()` is guaranteed to execute and complete its asynchronous flush and cancel joins **before** JUnit walks and deletes the temporary directory `mangaDir`.

### 1.3 Asynchronous Drain & Cancellation Safety

- **Coroutines Teardown:** The barrier runs inside `runBlocking { ... }`, bridging the non-suspending JUnit `@AfterEach` interface to the suspendable store drain functions.
- **Cancellation Immunity:** Calls are wrapped in `withContext(NonCancellable)`, preventing cancelled test scopes from aborting the resource flush and cleanup.
- **Drain Depth:**
  - `store.closeAndFlush()` executes `writeDrain.closeAndFlush { journalWriter.drainAndClose() }` and in its `finally` block runs `artifactEngine.cancelRetentionWorkAndJoin()`. This ensures both in-flight file/journal writes and background directory sweeps are joined (this seam drains file/journal persistence; no SQLite work is performed).
  - Standalone `artifactStore()` instances invoke `engine.cancelRetentionWorkAndJoin()`, cancelling their respective `retentionScopeJob` routines.
- **Fault Tolerance:** Each store and engine is drained inside an independent `try / catch (failure: Throwable)` block. If an earlier store fails to drain, subsequent stores and engines are still processed. The first encountered failure is rethrown with subsequent failures attached as suppressed exceptions, ensuring failures are never silently swallowed.

# 2. Fresh Test Evidence & Verification Audit (Shell `082a2522`)

### 2.1 Full-Suite StandardDebug Recheck (`full-standard-after-fix-raw`)

- **Suite Totals:** Exactly 329 XML files, 2,234 test executions, 4 failures, 0 errors, 1 skipped.
- **Failures Audit:** Exactly the 4 ledgered baseline `ChapterTranslationStoreArtifactOpenProbeTest` failure methods, matching baseline literal signatures:
  1. `lazy store cuts over before first candidate...`: `java.lang.AssertionError`
  2. `pre-registered pages publish one durable baseline...`: `io.kotest.assertions.AssertionFailedError`
  3. `artifact-only lazy store writes pages...`: `org.junit.platform.commons.JUnitException`
  4. `lazy store without parent resolves artifact authority...`: `org.junit.platform.commons.JUnitException`
- **Observed Post-Change N2 Outcome:**
  - `TEST-eu.kanade.translation.pipeline.batch.ProfileEnvelopeDispatchTest.xml` passed **20 tests / 0 failures / 0 errors / 0 skipped** in 12.471s.
  - Specifically, `N2 normal AI batch makes zero analysis transport calls()` completed cleanly in 0.209s under the teardown barrier without encountering the prior teardown extension failure.
  - Zero new failures were introduced in the observed fresh full run across the entire 2,234-test suite. Original actor/race attribution and universal stability remain unproven.

### 2.2 Narrow Suite Evidence

- **DevDebug Narrow Run:** Preserved XML in `narrow-devdebug-raw` confirms `ProfileEnvelopeDispatchTest` passes 20/20 in 1m 13s.
- **StandardDebug Narrow Run:** Command log `082a2522-9120-4886-af73-005e9740178e/output.log` (line 616) confirms narrow execution succeeded (`BUILD SUCCESSFUL in 49s`, exit 0). The honest observation that its narrow XML was overwritten by the subsequent full-suite Standard task in the same directory before copy is noted and fully superseded by the fresh, complete 329-file full Standard suite XML.

# 3. Causal Boundary & Scope Assessment

- **Scope Boundary:** This change represents a deterministic fixture-level hardening that resolves an established lifecycle omission in `ProfileEnvelopeDispatchTest.kt`.
- **Honest Causal Distinction:** As explicitly maintained throughout this audit, applying this fixture barrier addresses the established lifecycle omission, and N2 passed in the observed post-change full run; original actor/race attribution and universal stability remain unproven. It does **not** make claims regarding runtime production store concurrency, nor does it address or purport to fix the unrelated coroutine timeout observed at P0 baseline.
- **Non-regression on Existing Tests:** The test bodies and assertions remain verbatim. Reopen tests continue to verify cold-start rehydration from disk artifact files, now followed by clean per-test disposal.

# 4. Conclusion & Final Verdict

The fixture finalization implementation cleanly closes the resource teardown gap in `ProfileEnvelopeDispatchTest.kt`. Fresh execution evidence confirms that test N2 passed in the observed post-change full run under the teardown barrier, with the full test suite returning to the expected baseline state of exactly 4 known `ArtifactOpenProbe` failures and zero unexpected regressions in the observed fresh run.

**Phase 1 Fixture Finalization is ACCEPTED (COMMITTED IN DAB5C124).**
