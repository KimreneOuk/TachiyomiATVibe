---
title: "Phase 0 — narrow failure triage (owned by build-and-tests)"
kind: spec
comments: none
---

# Scope and method

Narrow diagnostic triage of the 6 distinct failing test methods from the [baseline build-and-tests ledger](../index.md). No repository edits, no config changes, no fixes — classification and reproduction only. The original full-suite ledger and its 670 fresh XML remain authoritative; isolated runs are DIAGNOSTIC and never replace them.

Method: `./gradlew.bat :app:test{Standard,Dev}DebugUnitTest --tests <3 failing classes> --rerun --console=plain --stacktrace` — per-task `--rerun` (supported by Gradle 8.12) used to bypass the FAILED-task skip; `--tests` filter isolates the three classes. Standard run 12:39:27–12:40:17Z (BUILD FAILED in 49s, gradlew exit 1 — the 5 expected failures); Dev run 12:42:01–12:42:40Z (BUILD FAILED in 38s, gradlew exit 1 — same 5). Narrow XML preserved to scratch `phase-0-baseline/narrow-triage-test-results/2026-10-14T12-43-14Z/` (6 files); original full-suite XML re-verified intact afterwards (670 files).

# Reproduction matrix

| Failing method (6 distinct) | Standard full suite | Dev full suite | Standard isolated | Dev isolated | Classification |
| --- | --- | --- | --- | --- | --- |
| `TranslationDebugMeasurementTest > file sink preserves queue order and sanitizes embedded line breaks()` | FAIL | FAIL | FAIL (1/2) | FAIL (1/2) | **Assertion — production format drift**; reproduced in every observed run (4/4) |
| `ChapterTranslationStoreArtifactOpenProbeTest > lazy store cuts over before first candidate and reopens from artifact pointers()` (fails at kt:563-area `cleanedImageName shouldBe`) | FAIL | FAIL | FAIL | FAIL | **Assertion — behavior** (candidate→committed promotion lost on reopen); reproduced 4/4 |
| `ChapterTranslationStoreArtifactOpenProbeTest > pre-registered pages publish one durable baseline with only the changed candidate()` (fails at kt:253, expected 3 page keys, actual `[]`) | FAIL | FAIL | FAIL | FAIL | **Assertion — behavior** (pre-registered pages not materialized as manifest entries after flush); reproduced 4/4 |
| `ChapterTranslationStoreArtifactOpenProbeTest > artifact-only lazy store writes pages without materializing flat compatibility file()` | FAIL | FAIL | FAIL | FAIL | **WindowsTempDir resource** — test body PASSES; failure is JUnit `@TempDir` close-time; reproduced 4/4 |
| `ChapterTranslationStoreArtifactOpenProbeTest > lazy store without parent resolves artifact authority through artifactParentResolver()` | FAIL | FAIL | FAIL | FAIL | **WindowsTempDir resource** — test body PASSES; failure is JUnit `@TempDir` close-time; reproduced 4/4 |
| `ProfileEnvelopeDispatchTest > protocol outcome commits fully covered pages and parks omitted block pages without pausing()` | pass | **FAIL** (UncompletedCoroutinesError, 60s wall) | **pass 20/20, 10.9s** | **pass 20/20, 14.3s** | **Full-suite FAIL / isolated PASS** (one observation each); mechanism hypothesized, pending a clock-controlled test — see below |

Isolated failure messages for the 5 reproduced failures are byte-identical to the full-suite ledger (verified from narrow XML). "Reproduced 4/4" means every run observed so far (2 full-suite + 2 isolated × both flavors); it does NOT prove platform/JDK independence or exhaust the input space.

# Per-failure findings

## 1. TranslationDebugMeasurementTest — file sink (observed failing in every executed run)

- Site: `app/src/testDebug/java/eu/kanade/translation/diagnostics/TranslationDebugMeasurementTest.kt:23` (class lives on the **testDebug source set** — runs in BOTH flavor suites; absent from `src/test`).
- Production: `app/src/debug/java/eu/kanade/translation/diagnostics/TranslationTraceFileSink.kt:69-83` — `prefixTimestamp()` prepends `ts=<local timestamp> ` to every line lacking one, before the async queue write.
- Actual vs expected: every logged line gains `ts=2026-10-14T19:21:16.293 …`; the test expects the unprefixed lines verbatim. Queue order and line sanitization (the behaviors the test names) both still hold — only the timestamp prefix differs.
- Most consistent explanation (hypothesis, high confidence from source inspection): the test expectation predates the sink's timestamp-prefix feature. Reproduced in every observed run; platform/JDK independence not proven (not tested elsewhere).
- Responsible owner: shared contract — debug diagnostics sink format (production `app/src/debug`) vs its testDebug expectation.
- Repair gate: Phase 1 test repair MAY fix this diagnostic assertion once the sink line contract is settled (see F5 disposition — the Phase 2 diagnostics/telemetry relocation is NOT a necessary precondition); NOT a Phase 0 repair.

## 2. ArtifactOpenProbe — committed cleaned-image name assertion mismatch on reopen (observed failing in every executed run)

- Site: `ChapterTranslationStoreArtifactOpenProbeTest.kt:514` (observed failure message `Expected "page.cleaned.retry.jpg" but actual was null`, produced by the `shouldBe` after the second `openArtifact` reopen, line ~563).
- Expected (test contract as written) vs actual (reported): the test EXPECTS the reopened store's committed display to carry `cleanedImageName = "page.cleaned.retry.jpg"` after an all-READY candidate update + `flush()` + `closeAndFlush()`; the store REPORTED `null`. This is an observed assertion mismatch — the triage does NOT establish that a commit was lost, only that expected and actual diverge at this point in the flow.
- Flow (as executed by the fixture): lazy store writes a RUNNING candidate → close → reopen from artifact → page updated to all-READY with `cleanedImageName = "page.cleaned.retry.jpg"` + `CURRENT_INPAINT_REVISION` → `flush()` + `closeAndFlush()` → reopen: `display.value[page].cleanedImageName` observed null.
- Hypothesis space (open — behavior-contract source audit REQUIRED before attributing): (a) production candidate→committed promotion for a reopen-from-artifact page may not durably publish on close (promotion condition or publication timing); (b) the second reopen may read the committed slot before promotion; (c) the test fixture may assert semantics the current design never intended (contract/fixture drift). Which explanation holds requires reading the `ChapterTranslationStore` lazy-publication/promotion/reopen source against the related PASSING tests — a contract source audit — not from this assertion output alone.
- Required baseline investigation (BEFORE Phase 3/4 entry): audit the store's promotion/reopen/publication contract and fixtures; produce an explicit expected-contract statement for this flow. If the audit shows a defect, the repair is a SMALL FIRST SLICE with re-baseline; if it shows drift, re-align the fixture. Phase 0 itself authorizes NO implementation repair.
- Repair gate: investigation owned in Phase 0 baseline scope (contract/source/fixture audit); any resulting repair slice or re-baseline lands before Phase 3/4 entry. NOT deferred to Phase 7 — Phase 3/4 depend directly on these store contracts.

## 3. ArtifactOpenProbe — pre-registered pages absent from flushed manifest (observed failing in every executed run)

- Site: `ChapterTranslationStoreArtifactOpenProbeTest.kt:237`, observed failure at kt:253.
- Expected (test contract as written) vs actual (reported): the test EXPECTS `manifest.pages.keys == {p1, p2, p3}` after pre-registration + one RUNNING update + flush; the manifest REPORTED `[]` (zero page entries).
- Observed semantics: immediately after `preRegisterPages`, the manifest DOES show `expectedPageCount=3, expectedPageCountTrusted=true` (lines 246-247 pass). After `updatePage(p1 → RUNNING)` + `flush()`, the manifest contains ZERO page entries — neither the pre-registered untouched pages nor the RUNNING candidate materialize as entries.
- Hypothesis space (open — behavior-contract source audit REQUIRED before attributing): (a) the lazy flush intentionally persists only expected-count metadata and skips materializing entries for untouched pre-registered pages and transient (RUNNING)-only pages as an optimization; (b) entry materialization was intended and regressed; (c) the test fixture asserts superseded semantics. The observed intermediate fact: the same manifest shows `expectedPageCount=3, expectedPageCountTrusted=true` right after pre-registration (lines 246-247 pass), then zero entries after `updatePage(p1 → RUNNING)` + `flush()`. Whether an untouched pre-registered page is SUPPOSED to materialize a disk record is a design-contract question that only a source audit can settle.
- Required baseline investigation (BEFORE Phase 3/4 entry): same contract/source/fixture audit as #2 (one combined audit of pre-registration, candidate publication, promotion, and reopen semantics). Repair/re-baseline small first slice if the audit finds a defect; re-align fixture if it finds drift. Phase 0 authorizes NO implementation repair.
- Repair gate: Phase 0 baseline investigation; NOT deferred to Phase 7.

## 4-5. ArtifactOpenProbe — two WindowsTempDir cleanup failures (test-body PASS)

- Sites: tests at `ChapterTranslationStoreArtifactOpenProbeTest.kt:301` and `:569`. Both test bodies pass all assertions; the recorded failure is `JUnitException: Failed to close extension context` caused by `java.io.IOException` at JUnit `TempDirectory.java:449` — JUnit's recursive delete of the class `@TempDir` (line 50) fails.
- Mechanism evidence from the nested exception chain (verified in the preserved XML, not inferred): the cause chain is `JUnitException: Failed to close extension context` → `Caused by: java.io.IOException: Failed to delete temp directory C:\Users\ADMIN\AppData\Local\Temp\junit-…: paths could not be deleted: <root>, Chapter 2.manifest.json` (test 4) / `Chapter 8.manifest.json` (test 5), with SUPPRESSED exceptions `java.nio.file.FileSystemException: …\Chapter {2,8}.manifest.json: The process cannot access the file because it is being used by another process` and `DirectoryNotEmptyException` (JUnit `TempDirectory$CloseablePath.close`, TempDirectory.java:327 → :449). Both tests end WITHOUT closing the store (no `closeAndFlush()`; test 4 ends at kt:315, test 5 at kt:593).
- Open question — handle ownership is NOT settled: the Windows lock proves SOME handle on the manifest file is open at cleanup; it does NOT identify the holder and does NOT prove a test-only leak. No closure-controlled experiment (adding explicit store teardown in a controlled variant and observing the error disappear) has been executed. Production ownership is NOT excluded: an unclosed stream inside `ChapterTranslationStore`/its manifest writer, or a background flush still active at test end, would produce the identical symptom. Conversely the fixture-level gap (store never closed at scope end) is real and would also produce it. Both hypotheses stand until the closure-controlled test runs.
- No prior-run claim: there is NO local evidence this class ever ran or passed on CI/Linux — the preserved pre-existing XML subsets (30 files) do NOT contain `ChapterTranslationStoreArtifactOpenProbeTest`. Earlier drafts' "consistent with prior CI-shaped runs" was unsupported and is withdrawn. POSIX deletion semantics (unlink of open files succeeds) remain a general-plausibility note only, not evidence.
- Required baseline investigation (BEFORE Phase 3/4 entry, part of the same store contract/fixture/cleanup audit as #2/#3): (1) closure-controlled diagnostic variant to identify the handle holder; (2) audit of the store's manifest-writer lifecycle for idle-open handles or background flushes at scope end. Outcome decides fixture-teardown repair vs production handle-lifecycle repair as a small first slice with re-baseline. Phase 0 authorizes NO implementation repair.
- Repair gate: Phase 0 baseline investigation; NOT deferred to Phase 7.

## 6. ProfileEnvelopeDispatchTest — protocol parking (full-suite FAIL / isolated PASS; mechanism hypothesized)

- Site: `ProfileEnvelopeDispatchTest.kt:635-691` (17-page batch; envelope 2+ responses omit all but one block; rides the REAL adaptive retry controller and the REAL strict request builder).
- Full-suite failure: `UncompletedCoroutinesError: After waiting for 1m, the test body did not run to completion` (`runTest` 60s wall timeout) — observed ONLY in the DevDebug full-suite run (Standard full suite passed it).
- Observed outcome: FAILED in the DevDebug full-suite run only; PASSED in the StandardDebug full-suite run; PASSED isolated on both flavors (Standard 20/20 in 10.9s; Dev 20/20 in 14.3s — real seconds of work, i.e. genuine wall-time involvement). Single observation per cell — reported as "full-suite FAIL / isolated PASS", not as a confirmed load-sensitivity law.
- Mechanism hypothesis (unverified, pending a clock-controlled test): real-vs-virtual time-base interplay. The batch request path admits through `BatchRequestSublimitGate` → `ProviderRequestGovernor` whose default `SystemProviderRequestClock` uses real `System.currentTimeMillis()` and real `delay`, while the test body runs under `runTest` virtual time; attempt-budgeted retry/parking interleaved with real-clock admission windows may fail to drain within the 60s wall budget under full-suite JVM load. Discriminating experiment (NOT run, out of Phase 0 triage scope): re-run the class under an injected `TestProviderRequestClock`/virtual clock, or under an isolated full-suite-like load.
- Reproduction constraint: NOT reproducible with the 3-class filter so far; requires the full-suite context (or a yet-unknown trigger). Any future fix must reproduce against the full suite, never by weakening the test's parking assertions.
- Responsible owner (hypothesis, not attribution): test determinism seam first (injectable clock for governor/retry in this test), production governor time-base second — pending the clock-controlled experiment.
- Repair gate: Phase 6 batch orchestration (reader/batch contention and interrupted/restarted-run gates). The clock-controlled discriminating experiment is part of the required baseline/Phase 6 investigation (test-only seam; Phase 0 authorizes no edits). Observed status in this ledger: full-suite FAIL on Dev (1/2 full-suite runs), isolated PASS on both flavors (1 run each).

# Cross-cutting notes

- The 5 failures reproduced in every observed run are NOT explained by the local JDK-21-vs-CI-17 difference (no evidence either way — CI/JDK17 untested locally; do not infer).
- Pre-existing partial evidence (25 OCR/engine classes Dev + 5 classes Standard from 10/13–10/14, preserved under `pre-existing-test-results/`) contained zero failures and included NONE of these classes — today's full-suite XML is the first full-suite evidence of all six. No claim is made about CI/Linux pass history for any of these classes.
- No retry-until-green occurred anywhere: each failing configuration ran exactly once in this triage (2 full-suite original runs + 2 isolated diagnostic runs).

# Reviewer disposition (F1–F5)

- **F1 (Windows fixture-leak overclaim + unsupported prior-CI claim):** Addressed. "Windows-specific test-fixture resource leak, not production logic failure" withdrawn; #4-5 now records the verified suppressed `FileSystemException` chain as evidence that a handle was open, states holder identity as an OPEN question, requires a closure-controlled experiment, and the unsupported "prior CI-shaped runs" claim is explicitly withdrawn (pre-existing XML excluded the class).
- **F2 (premature lost-commit attribution):** Addressed. #2/#3 now separate EXPECTED (test contract as written) from ACTUAL (reported values), present production-regression vs contract-drift vs fixture-drift as an open hypothesis space, and require a contract source audit as a Phase 0 baseline investigation. No "lost commit" claim remains.
- **F3 (load-sensitive race overclaim):** Addressed. #6 is reported as observed full-suite FAIL / isolated PASS with N=1 per cell; the time-base mechanism is labeled an untested hypothesis with a named discriminating experiment; "load-sensitive" appears only as observed reproduction context, never as a settled classification.
- **F4 (headings asserting facts):** Addressed. Section headings now state observed outcomes ("observed failing in every executed run", "full-suite FAIL / isolated PASS; mechanism hypothesized"); the reproduction-matrix "reproduced 4/4" phrasing is observation-counting only.
- **F5 (Phase 7 deferral of storage failures):** Addressed. #2/#3/#4-5 gates are now a REQUIRED Phase 0 baseline contract/source/fixture/cleanup investigation whose outcome must land BEFORE Phase 3/4 entry (small first-slice repair/re-baseline if the audit finds a defect); deep persistence refactoring stays in Phase 7. Phase 0 authorizes no implementation repair — the investigation and any authorized slice are separately gated.
- Gate-owner correction: #1's diagnostic assertion may be repaired in Phase 1 test repair ONCE the sink line contract is settled by the audit; the Phase 2 telemetry/normalization relocation is NOT a necessary precondition for that repair.
- The authoritative full-suite ledger (11 failures / 4588 executions / 670 XML) is unchanged by this triage; the JDK 21-vs-17 coverage gap remains recorded and unresolved.
