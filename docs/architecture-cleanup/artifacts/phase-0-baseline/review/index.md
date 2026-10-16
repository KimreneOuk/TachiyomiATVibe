---
title: "Independent review: Phase 0 baseline execution, failure triage, and exit readiness"
kind: review
comments: none
---

# Executive assessment

This independent review evaluates the Phase 0 baseline evidence, execution protocols, and failure triage for the `tachiyomiATVibe-inpainting-modes-redesign` repository at revision `4de121bf7ed486e9f39b5a3f4f5c4288f439c8c8`.

**Verdict:** Phase 0 is **INCOMPLETE / IN PROGRESS**. The host build and test execution evidence is well-preserved and truthful to actual command outputs, but significant overclaims and causal leaps in the failure triage must be corrected. Furthermore, core Phase 0 exit gates (device baseline distributions, assertion-linked behavior inventory, and change-isolation exercises) remain pending. Implementation (Phase 1) must **not** begin until Phase 0 exit criteria are satisfied or explicitly adjusted by the user.

| Gate / Requirement | Status | Review finding |
| --- | --- | --- |
| **Pinned revision & integrity** | **VERIFIED** | HEAD pinned at `4de121bf7ed486e9f39b5a3f4f5c4288f439c8c8`; git tracked working tree clean; user report SHA256 verified `EFC11B90A7D0B367F3878BD600BF893AA99ECCDB5879D902E10EB6E06EF88DDD`. |
| **Source file inventory** | **VERIFIED** | Translation main: 290 files (288 Kotlin + 2 Java: `CheckNnapi.java`, `CheckXnnpack.java`). Translation tests: 318 ordinary unit, 1 testDebug, 6 androidTest. Repo-wide: 348 ordinary unit (app 342 + domain 6), 9 androidTest. |
| **Full test suite execution** | **VERIFIED** | 670 fresh XMLs preserved in `scratch/.../2026-10-14T12-26-30Z/`. 4588 executions, 11 failures, 0 errors, 2 skips. 6 distinct failing methods (5 on both flavors, 1 on Dev only). |
| **Build layer status** | **VERIFIED** | Both `assembleDevDebug` and `assembleStandardDebug` succeeded incrementally. Precondition satisfied by existing local assets; no network provisioning run. |
| **Failure triage claims** | **DISPUTED / NEEDS REVISION** | Multiple causal leaps: premature "deterministic" labels, unproven "test fixture leak" claim without closure controls, unsubstantiated "passed in prior CI" claims, and unproven "load-sensitive race" mechanism. |
| **Persistence repair sequencing** | **CRITIQUE / AT RISK** | Triage proposes deferring critical `ChapterTranslationStore` failures to Phase 7. Because Phase 3 and Phase 4 depend directly on store contracts, this deferral creates compounding architectural risk. |
| **Exact diagnostic commands** | **DEFICIENT** | Diagnostic runs 5 and 6 in `build-and-tests` use placeholder `<3 failing classes>` rather than reproducible, copy-pasteable command lines. |
| **Device baseline** | **PENDING** | User selected **Keep device measurements pending**. No connected hardware; zero distributions measured. Gate remains active and not waived. |
| **Behavior inventory & exercises** | **PENDING** | Required behavior inventory linked to actual test assertions and the 3 change-isolation exercises are not yet complete in Phase 0 artifacts. |

# 1. Authoritative baseline ledger integrity

The full-suite test results captured by the coordinator and GLM executor represent the authoritative baseline and must be preserved without dilution:

| Suite | XML suites | Test executions | Failures | Errors | Skips | Passed |
| --- | --- | --- | --- | --- | --- | --- |
| `:app:testStandardDebugUnitTest` | 332 | 2261 | 5 | 0 | 1 | 2255 |
| `:app:testDevDebugUnitTest` | 332 | 2261 | 6 | 0 | 1 | 2254 |
| `:domain:testDebugUnitTest` | 6 | 66 | 0 | 0 | 0 | 66 |
| **Total** | **670** | **4588** | **11** | **0** | **2** | **4575** |

The 6 distinct failing methods observed across the full suites are:

1. `TranslationDebugMeasurementTest.file sink preserves queue order and sanitizes embedded line breaks()` (both flavors)
2. `ChapterTranslationStoreArtifactOpenProbeTest.lazy store cuts over before first candidate and reopens from artifact pointers()` (both flavors)
3. `ChapterTranslationStoreArtifactOpenProbeTest.pre-registered pages publish one durable baseline with only the changed candidate()` (both flavors)
4. `ChapterTranslationStoreArtifactOpenProbeTest.artifact-only lazy store writes pages without materializing flat compatibility file()` (both flavors)
5. `ChapterTranslationStoreArtifactOpenProbeTest.lazy store without parent resolves artifact authority through artifactParentResolver()` (both flavors)
6. `ProfileEnvelopeDispatchTest.protocol outcome commits fully covered pages and parks omitted block pages without pausing()` (DevDebug only)

Subsequent narrow diagnostic runs (runs 5 and 6) reproduced the 5 failures in both flavors and showed `ProfileEnvelopeDispatchTest` passing isolated (20/20). These diagnostic runs provide data points regarding execution context, but they do **not** erase, replace, or override the authoritative full-suite results.

# 2. Detailed critique of failure triage claims

The analysis in [failure-triage/index.md](../build-and-tests/failure-triage/index.md) contains valuable technical inspection, but oversteps observed evidence into speculative causal conclusions.

### Overclaim A: Unsubstantiated "Windows test-fixture leak" conclusion (#4 & #5)

- **Triage claim:** Classifies failures #4 and #5 strictly as a "Windows-specific test-fixture resource leak, not production logic failure" because the test method does not explicitly invoke `closeAndFlush()`.
- **Evidence check:** The nested failure stack is `java.nio.file.FileSystemException: ... Chapter {2,8}.manifest.json: The process cannot access the file because it is being used by another process`, thrown during JUnit's `@TempDir` extension cleanup.
- **Challenge:** The presence of an open file lock on Windows proves that *a* process or thread has not closed the file descriptor. It does **not** prove *who* holds that descriptor:
  1. No closure-controlled experiment was executed (e.g., adding `store.closeAndFlush()` in a controlled scratch test to see if the error actually disappears).
  2. It does not rule out production defects inside `ChapterTranslationStore` or its manifest writer (e.g., an unclosed `FileOutputStream`, leaked channel, or background async flush coroutine still active).
  3. The assertion that this passed in "prior CI-shaped runs" is unsupported: the 30 pre-existing XML files preserved under `pre-existing-test-results/2026-10-14T11-56-55Z` do **not** contain `ChapterTranslationStoreArtifactOpenProbeTest`. There is zero local evidence that this test class ever ran or passed on CI/Linux.

### Overclaim B: Premature "lost commit" root-cause assertion (#2 & #3)

- **Triage claim:** Asserts failure #2 is a production defect where "committed cleaned-image name lost on reopen" and failure #3 is where "pre-registered pages absent from flushed manifest" due to lazy publication semantics skipping untouched pages.
- **Evidence check:**
  - Failure #2 (`shouldBe` expected `"page.cleaned.retry.jpg"`, actual was `null`).
  - Failure #3 (`manifest.pages.keys shouldBe setOf("p1", "p2", "p3")`, actual was `[]`).
- **Challenge:** The triage attributes these directly to production publication bugs. However, this could equally be **contract drift or test fixture specification drift**:
  - In a lazy store, is an untouched pre-registered page *supposed* to materialize an empty record on disk, or is omitting unmutated pages an intentional optimization?
  - Did the retry naming convention or candidate promotion lifecycle deliberately evolve without the test fixture being updated?
  - Calling this a "lost commit in production" before validating the architectural specification is an unverified conclusion.

### Overclaim C: ProfileEnvelopeDispatchTest "load-sensitive coroutine race" (#6)

- **Triage claim:** Classifies failure #6 as a "load-sensitive coroutine race" caused by time-base divergence (`SystemProviderRequestClock` using real time vs `runTest` virtual time).
- **Evidence check:** Failed once in full DevDebug (60s `UncompletedCoroutinesError`), passed once in full StandardDebug, passed isolated 20/20 in both flavors.
- **Challenge:**
  - N=1 for each condition is insufficient to declare a confirmed "load-sensitive race".
  - The hypothesis regarding `SystemProviderRequestClock` wall-clock delay interacting with `runTest` is plausible and well-reasoned, but remains an **untested hypothesis**. No clock-controlled test (e.g., injecting a virtual test clock or testing under simulated thread pool saturation) was executed.
  - Section headings must be phrased as observed behaviors and testable hypotheses, not settled facts.

### Overclaim D: Misleading section headings

- Headings such as `## 2. ... (deterministic assertion)` and `## 6. ... (load-sensitive coroutine race)` present hypotheses as established facts. Given the small number of observed runs, headings should reflect observed outcomes (e.g., `Observed failure in all executed runs` or `Observed full-suite failure / isolated pass`), reserving mechanism discussions for the body.

# 3. Persistence repair sequencing & architectural risk

The failure triage recommends postponing all four `ChapterTranslationStoreArtifactOpenProbeTest` failures to **Phase 7 ("Cohesive persistence internals")**.

**This sequencing is flawed and introduces serious project risk:**

1. **Dependency on store contracts:** Phase 3 ("One sequential page job with safe resources") and Phase 4 ("Unified reader queue and auto retirement") directly rely on `ChapterTranslationStore`, candidate-versus-committed separation, store fences, and publication receipts.
2. **Compounding uncertainty:** If developers redesign reader orchestration and sequential execution on top of a persistence layer whose pre-registration, candidate promotion, and file handle lifecycles are broken or ambiguous, new tests and implementations will inherit that fragility.
3. **Required action:** Contract ambiguity and file handle lifecycle expectations for `ChapterTranslationStore` must be defined as a **Phase 3 prerequisite gate**. While deep persistence refactoring belongs in Phase 7, Phase 3 cannot proceed safely without an unambiguous specification of how the store publishes candidates and manages file resources.

# 4. Reproducibility & command documentation defect

In [build-and-tests/index.md](../build-and-tests/index.md), Table "Layer 3 — Command provenance", Runs 5 and 6 document the diagnostic commands as:
`./gradlew.bat :app:testDevDebugUnitTest --tests <3 failing classes> --rerun --console=plain --stacktrace`

This is **not reproducible as written**. A third-party engineer or audit cannot copy and paste `<3 failing classes>`.
The artifact must be updated to document the exact, literal command executed, for example:

```powershell
./gradlew.bat :app:testDevDebugUnitTest `
  --tests "eu.kanade.translation.diagnostics.TranslationDebugMeasurementTest" `
  --tests "eu.kanade.translation.persistence.chapter.ChapterTranslationStoreArtifactOpenProbeTest" `
  --tests "eu.kanade.translation.pipeline.batch.ProfileEnvelopeDispatchTest" `
  --rerun --console=plain --stacktrace
```

# 5. Phase 0 exit status & outstanding gates

Phase 0 remains **open** and incomplete. The following items must be addressed before Phase 0 can be closed:

1. **Failure triage corrections:**
  - Soften causal overclaims in `failure-triage/index.md` headings and text.
  - Record the file-lock issue as an open question regarding handle ownership, rather than a proven test-only leak.
  - Clarify that `ProfileEnvelopeDispatchTest` time-base divergence is a hypothesis awaiting validation.
  - Note that lazy store assertions could represent contract/fixture drift.
2. **Command provenance update:**
  - Replace `<3 failing classes>` in `build-and-tests/index.md` with the exact Gradle command syntax.
3. **Behavior inventory and change-isolation exercises:**
  - Link required manual, auto, batch, provider, cancellation, source-ready, refresh, and recovery behaviors to concrete unit and integration assertions in a Phase 0 artifact.
  - Complete the three change-isolation exercises.
4. **Device baseline gate tracking:**
  - Keep the device baseline status clearly recorded as **Pending by user decision** (not waived). No device distributions exist.
5. **No implementation authorization:**
  - Confirm that no production, test, or build logic edits have occurred, and that Phase 1 implementation remains blocked until Phase 0 is formally signed off.

# Addendum: Recheck of Amended Triage & Initial Review of Behavior Artifact (2026-10-14)

Following the independent review above, the build executor and coordinator delivered amended artifacts:

- [build-and-tests/index.md](../build-and-tests/index.md) (amended with exact command lines)
- [build-and-tests/failure-triage/index.md](../build-and-tests/failure-triage/index.md) (amended with qualified headings, open handle questions, hypothesis-only coroutine race, and early store gate)
- [behavior-and-change-isolation/index.md](../behavior-and-change-isolation/index.md) (new delivery: behavior inventory and change-amplification exercises)

## 1. Evaluation of Build-and-Tests & Failure Triage Amendments

The amendments in `build-and-tests` and `failure-triage` have been rechecked and verified:

1. **Command Reproducibility Verified**: Runs 5 and 6 in `build-and-tests/index.md` now specify the exact literal multi-line Gradle commands with the three target classes explicitly quoted:
`--tests "eu.kanade.translation.diagnostics.TranslationDebugMeasurementTest" --tests "eu.kanade.translation.persistence.chapter.ChapterTranslationStoreArtifactOpenProbeTest" --tests "eu.kanade.translation.pipeline.batch.ProfileEnvelopeDispatchTest"`. The placeholder `<3 failing classes>` has been eliminated.
2. **Qualified Headings & Triage Wording**: Headings in `failure-triage/index.md` now truthfully represent observed outcomes rather than premature conclusions:
  - Section 2 is titled `## 2. ArtifactOpenProbe — committed cleaned-image name lost on reopen (observed failing in every executed run)`.
  - Section 4-5 is titled `## 4-5. ArtifactOpenProbe — two WindowsTempDir cleanup failures (open handle at scope end; holder not proven)`.
  - Section 6 is titled `## 6. ProfileEnvelopeDispatchTest — protocol parking (full-suite FAIL / isolated PASS; mechanism hypothesized)`.
3. **Handle Ownership Open Question**: The claim that Windows `@TempDir` failures were strictly a "test-fixture resource leak" has been withdrawn. The text now clearly notes that while the suppressed `FileSystemException` confirms an open file lock on `Chapter {2,8}.manifest.json`, the descriptor holder is unproven, and production handle leaks inside `ChapterTranslationStore` or its manifest writer remain unruled out pending a closure-controlled experiment. The unsupported claim regarding prior CI pass history has also been withdrawn.
4. **Early Store-Contract Gate Accepted**: The recommendation to defer `ChapterTranslationStoreArtifactOpenProbeTest` failures to Phase 7 has been formally reversed. The triage now establishes an explicit Phase 0 contract and cleanup investigation required **before Phase 3/4 entry**, preventing ungrounded persistence contracts from undermining sequential page and reader queue refactoring.

**Verdict on Build & Triage Amendments:** **ACCEPTED / SATISFIED.**

## 2. Review of Delivered Behavior & Change-Isolation Artifact

An initial independent review of [behavior-and-change-isolation/index.md](../behavior-and-change-isolation/index.md) was conducted. While the artifact provides comprehensive structural mapping, the following critical issues, overclaims, and inaccuracies must be corrected before it can serve as a dependable baseline:

### Finding 1: Incorrect Source-Set Scope Labeling

- **Location:** Table under "Verified source inventory".
- **Issue:** The scope `app/src/test/java/eu/kanade/translation` (318 files) is labeled as `(testDebug unit set)`.
- **Correction:** This is factually wrong. `app/src/test` is the **main ordinary unit test source set** (runs across all variants). `app/src/testDebug` is a completely separate Gradle source set containing exactly **1** test file (`diagnostics/TranslationDebugMeasurementTest.kt`). Calling `app/src/test` the "testDebug unit set" creates confusion regarding variant compilation and test execution boundaries.

### Finding 2: False Invariant on Cancelled Commit File Deletion (Row 8)

- **Location:** Executable behavior inventory, Row 8 (`Image publication / candidate vs committed`).
- **Issue:** The "Required invariant" column states: `cancelled commit deletes new file`.
- **Correction:** This describes a known baseline hazard, **not** a required invariant. In fact, deleting a file on an uncertain or cancelled commit risks destroying accepted or referenced bitmaps if the commit actually succeeded or was in-flight. Guarded publication requires safe lifecycle and retention fences, not aggressive opportunistic deletion on cancellation.

### Finding 3: Overstated Physical Native Cancellation Coverage (Row 5)

- **Location:** Executable behavior inventory, Row 5 (`Native physical completion`).
- **Issue:** The gap column states: `physical JNI exit only via pending androidTest`.
- **Correction:** The existing translation instrumentation tests (`PaddleOcrLiteRtOnDeviceTest`, `LiteRTOnDeviceVerificationTest`, `PageInpaintingEngineOnDeviceVerificationTest`, etc.) test model execution and inference sanity; they do **not** verify physical JNI thread exit upon coroutine cancellation. Suggesting that existing instrumentation covers physical native cancellation overstates the project's actual test coverage.

### Finding 4: Method-Level Specificity & Textual Search Limitations

- **Claim check:** The artifact claims that the absence of `inpaintJob` across 318 test sources proves detached inpaint lifetime has zero test coverage.
- **Critique:** Grepping for a specific variable name (`inpaintJob`) checks only direct identifier references. It does not rule out black-box behavioral coverage of the inpainting phase through higher-level pipeline tests (`SinglePageHttpRenderPhaseTest`, etc.).
- **Selector accuracy:** In the "Verified critical suite selectors" section, test references are largely class abbreviations or dot-separated prefixes rather than fully qualified method selectors, and several table rows cite file line numbers rather than stable method names.

### Finding 5: Change-Amplification Signature Analysis Nuance (Exercise B)

- **Location:** Change-amplification exercises, Exercise B (`Change inpaint retry inputs`).
- **Issue:** Lists 5 production files as "Signature-only forwarding (declarations of the same function type)" including `BatchChapterTranslator.kt` and `BatchLaneWorkers.kt`.
- **Critique:** While intermediate orchestrators forward function parameters, analysis must distinguish between pure transparent pass-throughs and call sites that construct new parameters or adapt stage inputs.

### Finding 6: Inaccurate Opening Statement on Failed Method Identity

- **Location:** Section "Scope, baseline, verification limits", paragraph 1.
- **Issue:** States: `which methods fail is not verifiable from this host (no runtimes executed here) and must come from the build agent's recorded output`.
- **Correction:** The exact 6 failing methods are **fully accessible and verified** in the preserved fresh-run XML ledger under `scratch/phase-0-baseline/fresh-run-test-results/2026-10-14T12-26-30Z/` on this host. This should be referenced as an authoritative known fact, rather than phrased as an inaccessible knowledge gap.

## 3. Current Phase 0 Readiness Summary

| Gate | Status | Next Required Action |
| --- | --- | --- |
| **Pinned revision & integrity** | **VERIFIED** | Clean git status at `4de121bf7ed486e9f39b5a3f4f5c4288f439c8c8`. |
| **Build & full suite test execution** | **VERIFIED** | Authoritative 4588 execution ledger preserved (6 failing methods). |
| **Failure triage & provenance** | **VERIFIED** | Amendments accepted; exact commands and open handle hypotheses documented. |
| **Behavior inventory & exercises** | **NEEDS AMENDMENT** | GLM source agent must correct source set misnomers, Row 8 invariant error, and native cancellation coverage overclaims. |
| **Device baseline distributions** | **PENDING** | Explicitly kept pending per user selection (not waived). |
| **Phase 1 entry** | **BLOCKED** | Implementation and code repairs remain blocked until Phase 0 is formally closed. |

# Addendum 2: Recheck of Final Behavior Inventory Amendments & Phase 0 Static Review Verdict (2026-10-14)

The GLM source agent delivered final amendments to [behavior-and-change-isolation/index.md](../behavior-and-change-isolation/index.md). A thorough recheck against our prior critique confirms all identified issues have been rigorously corrected:

1. **Source-Set Scope Nomenclature Verified**:
  - `app/src/test/java/eu/kanade/translation` (318 files) is now accurately categorized as `(unit tests)`.
  - `app/src/testDebug/java/eu/kanade/translation` (1 file, `TranslationDebugMeasurementTest.kt`) is explicitly identified as a separate debug-variant source set running across both flavor unit suites.
2. **Publication Invariant Truthfulness (Row 8)**:
  - The erroneous claim that "cancelled commit deletes new file" is a required invariant has been completely corrected.
  - It is now formally split: **Required invariant** is retaining accepted or possibly-referenced cleaned files until owner-aware retirement, with unreferenced rejected files cleaned up only under store-owner authority; the baseline's unproven deletion on cancelled commits is explicitly labeled as a **Known hazard (observed baseline, NOT a requirement)**.
3. **Physical Native Cancellation Coverage Verified (Row 5 & Gap 3)**:
  - The overclaim that native cancellation is covered by existing `androidTest` has been withdrawn.
  - The gap is now explicitly documented: automated physical JNI exit verification does not exist in unit or instrumentation test suites, as the 6 translation `androidTest` classes are model execution and benchmark utilities.
4. **Method Selectors and Grep Scoping**:
  - Every inventory table row now cites literal, named test methods (e.g. `PipelineStallWatchdogTest > parked native lane emits stall...`).
  - The "Verified critical suite selectors" table now provides 47 literal, copy-pasteable relative paths and fully qualified class names.
  - Identifier grep limitations are acknowledged: the absence of the identifier `inpaintJob` is carefully scoped as ruling out direct references, without overclaiming absence of indirect black-box behavioral coverage.
5. **Known Failure Transparency**:
  - The opening section now incorporates the full table of the 6 distinct known failing methods from the authoritative scratch XML ledger, eliminating the previous statement regarding inaccessibility.

### Formal Phase 0 Static Scope Verdict

The **static behavior inventory, failure triage, command provenance, and change-amplification exercises** meet all requirements and are hereby **ACCEPTED**.

### Outstanding Gates & Explicit Phase Status

Phase 0 remains **OPEN** due to the following explicitly pending items:

1. **Physical Device Baseline Gate**: Remains **PENDING** by explicit user selection (not waived; no attached hardware, zero distributions measured).
2. **Store-Contract Investigation**: The bounded diagnostic audit of `ChapterTranslationStoreArtifactOpenProbeTest` contracts (reopen promotion, pre-registration manifest materialization, and file descriptor closure controls) remains in progress as an explicit prerequisite before Phase 3/4 entry.
3. **Phase 1 Implementation Gate**: Remains **BLOCKED**. No implementation repairs, code deletions, or optimization refactors are authorized.

# Addendum 3: Independent Review of ChapterTranslationStore Contract Audit (2026-10-14)

A detailed review of [store-contract-audit/index.md](../store-contract-audit/index.md) was conducted against the audited production codebase and test suites.

While the audit delivers deep and valuable code-path tracing into the artifact manifest and journal subsystems, it contains several critical overclaims, misread KDocs, unproven causal leaps, and inaccurate API assertions that must be formally corrected:

### Finding 1: Inaccurate Diagnostic Recommendation (`updatePage` Returns `Unit`)

- **Audit Claim:** Recommends as a discriminating diagnostic experiment: `assert updatePage's PatchResult on the reopened store` (and notes in failure #2 that the test "ignores updatePage's result").
- **Code Reality:** In [`ChapterTranslationStore.kt:453`](file:///C:/Users/ADMIN/Documents/Coding%20Related/tachiyomiATVibe-inpainting-modes-redesign/app/src/main/java/eu/kanade/translation/persistence/chapter/ChapterTranslationStore.kt#L453), `updatePage` has the signature:
`internal suspend fun updatePage(pageKey: String, update: (PageTranslation?) -> PageTranslation): Unit`
It returns `Unit` unconditionally; internal rejections call `rejected(...)` and return. The test *cannot* assert a `PatchResult` on `updatePage` because no such return value exists. Only `updatePageGuarded` returns a `PatchResult`. Recommending a nonexistent return check demonstrates a static audit hallucination.

### Finding 2: Misread of `preRegisterPages` KDoc on "Missing pages are never registered"

- **Audit Claim:** The audit alleges a contradiction between the KDoc and production behavior regarding whether pre-registered pages should materialize in the manifest.

### Finding 3: Unproven Claim that CAS Rejection is the "ONLY" Consistent Cause (#3)

- **Audit Claim:** Asserts that registration CAS rejection is the "ONLY source path consistent with count-kept/entries-absent after a drained flush".
- **Evidence Reality:** Without runtime diagnostics or observed logcat output, this claim is unverified. Other structural explanations exist:
  - Upstream precondition or pointer document rejections prior to the registration CAS.
  - Asynchronous write queue draining timing or generation context mismatches.
  - Intentional contract semantics where `preRegisterPages` entries remain unmaterialized in `manifest.pages` until each individual page is mutated into a durable terminal/committed stage (a single `RUNNING` update without final blocks might not trigger durable materialization of untouched sibling pages).
Declaring CAS rejection as the sole possible cause is an ungrounded deductive leap.

### Finding 4: Handle Allocation & File Lock Scoping (#4 & #5)

- **Audit Findings & Nuance:**
  - The audit correctly identifies that `ChapterDocumentIo.write` uses per-call `openOutputStream().use { ... }`, meaning manifest file writers do not hold open static streams.
  - However, attributing journal writer leaks as holding only journal segments overlooks how `FileChapterJournalStorage` and directory handles interact on Windows NTFS filesystems. More importantly, journal writer channels are created on-demand, not necessarily for every idle store.
  - The audit's speculation that an external process (e.g., Windows Defender / search indexer) is locking `Chapter {2,8}.manifest.json` is plausible on Windows, but remains entirely **unproven**.
  - As established in earlier reviews, descriptor ownership remains an open question that can only be resolved by a controlled closure test (`store.closeAndFlush()`).

### Finding 5: Image Directory & Probe Seam Hypotheses (#2)

- **Audit Claim:** The audit claims that because `artifactImageProbe` and `displayBaseProbe` are unread in production paths, this "removes wrong image directory / failed probe from the #2 hypothesis space".
- **Code Reality:** Disconnecting a specific probe companion does not eliminate image validation or file existence checks from the hypothesis space. Production candidate promotion still verifies cleaned image existence and bitmap validity before committed display promotion. Test-local fixture directory conventions (`image-fixtures/` vs `Chapter 2_images/`) could still result in silent promotion failure if the expected image file is missing or unreadable on disk during reopen.

### Audit Disposition & Gate Verdict

1. **Static Store-Contract Audit Disposition**: **ACCEPTED WITH CORRECTIONS NOTED**.
  - The audit provides valuable architectural tracing of the manifest and journal subsystems.
  - All speculative causal claims (sole CAS rejection, external indexer blame, and proof of lost commits) remain classified as **untested hypotheses**.
  - The diagnostic recommendation to assert `updatePage`'s return value is rejected due to API signature mismatch (`Unit`).
2. **Phase 3/4 Prerequisite Status**:
  - The requirement for an explicit Phase 0 store contract investigation remains a strict **prerequisite gate before Phase 3/4 entry**.
  - No production code edits, test changes, or Phase 1 refactoring are authorized based on this audit.
3. **Phase 0 Status**:
  - Phase 0 remains **OPEN**. Device baseline remains pending by user choice; physical JNI cancellation evidence is absent; store contracts remain under investigation.

# Addendum 4: Final Recheck & Review of Amended Store-Contract Audit (2026-10-14)

A final recheck of the amended [store-contract-audit/index.md](../store-contract-audit/index.md) was conducted following the application of Addendum 3 reviewer corrections by the GLM baseline executor.

### Recheck Verifications & Dispositions

1. **`updatePage` Return Signature & Diagnostic Seams**:
  - The erroneous recommendation to assert a `PatchResult` return value on `updatePage` has been completely eliminated.
  - The text accurately reflects that `updatePage` returns `Unit` (`ChapterTranslationStore.kt:3015–3044`).
  - The diagnostic proposal now correctly references the separate, existing guarded API (`updatePageGuarded` / `patchPage`) while explicitly cautioning that it is an alternate diagnostic surface with different lease-checking semantics (WARN 3025–3027) rather than an automatic drop-in replacement.
2. **KDoc Context on "Missing Pages" & Materialization Semantics**:
  - The misread claiming an unavoidable contradiction between KDoc and implementation has been removed.
  - The text correctly places `"Missing pages are never registered as page records"` within the context of partial/source-download deltas (not-supplied pages).
  - The distinction between memory-only placeholders at pre-registration and deferred durable materialization at mutation time is now appropriately characterized as a plausible design requiring future contract tracing, rather than a proven production defect.
3. **Plausible vs. Exclusive Cause Attribution (#3)**:
  - The previous claim that registration CAS rejection was the "ONLY" source path has been withdrawn.
  - It is now framed as a plausible, unproven hypothesis alongside unexcluded upstream alternatives (initialization failure, manifest unavailable/null at drain time, queue anomalies, fixture-shape divergence).
4. **File Handle Lifecycle & Lock Ownership (#4 & #5)**:
  - Channel allocation is correctly documented as file-free until a record is appended.
  - The distinction between the scoped `RandomAccessFile.use` block (line 285) and the persistent `openSegment` `FileChannel` (line 303) is accurately stated.
  - Overclaims asserting a "proven journal writer leak" have been removed; descriptor holder identity remains explicitly unobserved and unresolved. Speculation regarding external Windows indexers is appropriately qualified as an untested possibility.
5. **Image Eligibility & Probe Seams (#2)**:
  - Overreaching assertions that disconnected companion probes eliminated image validation or wrong directory hypotheses have been removed. Image existence and path validation remain valid hypotheses.
6. **Frontmatter Integrity**:
  - The invalid `status` field was removed from the artifact's frontmatter, ensuring adherence to the Traycer artifact model for `kind: spec`.

### Scope Acceptance & Final Phase 0 Disposition

1. **Static Store-Contract Audit Acceptance**:
  - The **static audit document and structural code mapping** in [store-contract-audit/index.md](../store-contract-audit/index.md) are **ACCEPTED**.
  - No further broad static code searches are required for these four failure sites.
2. **Explicitly Retained Outstanding Gates**:
  - **Runtime Discrimination & Handle-Owner Experiments**: The runtime discrimination experiments (rejection reason capture, post-close manifest reads, and closure-controlled tests) remain unperformed and are preserved as explicit prerequisites before Phase 3/4 entry.
  - **Physical Device Baseline Gate**: Remains **PENDING by explicit user choice** (not waived; no hardware, zero distributions).
  - **Automated Physical Native Cancellation**: Remains **ABSENT** from both unit and instrumentation suites.
  - **Overall Phase 0 Gate**: Phase 0 remains **OPEN**. Phase 1 implementation remains **BLOCKED**.
