---
title: "Phase 0 — reproducible translation baseline"
kind: ticket
status: 2
assignee: ""
comments: none
---

# Authorization and scope

## Exit decision — host baseline complete; device work deferred

On 14 October 2026 the user instructed: “Proceed and defer phone testing for now.” This authorizes the proposed gate revision and the verified cleanup on `architecture-cleanup`. Phase 0 is complete **as a recorded host/static baseline**, not as a green test suite or a device-validated implementation. Its six observed failing methods, original XML/log evidence, environment deviations and coverage gaps remain unchanged.

Device latency/memory/native-occupancy measurements and physical JNI cancellation/drain verification are deferred, not passed or dismissed. They no longer block reference-verified dead-code removal and host test repair. Their evidence is required before accepting relevant native-lifetime, scheduling and performance-sensitive changes in Phases 3–4 and final release validation in Phase 8. Runtime storage discrimination remains a prerequisite before altering publication/page/reader ownership; deep persistence restructuring remains Phase 7.

Phase 1 execution is now authorized: [verified purge and test repair](../phase-1-cleanup). Phone deferral does not authorize OCR/LiteRT algorithm changes or relaxed store fences. Earlier OPEN/BLOCKED conclusions below and in independent review addenda describe the former gate; this user decision supersedes their sequencing, not their evidence or technical findings.

User authorized starting Phase 0 on 14 October 2026, using GLM 5.3 Flash for execution and Gemini 3.8 Flash for review. This phase records and tests the existing implementation; it does not authorize Phase 1 purge or production refactoring.

Roadmap: [final phase structure](../translation-simplicity-investigation/phases). Evidence and work in this phase must distinguish source checks, attempted commands, actual test execution and actual device measurements.

# Pinned provenance

## Current working branch — architecture cleanup

On 14 October 2026, at the user's request, created and switched to `architecture-cleanup` from `inpainting-modes-redesign`, then pushed it with upstream tracking to `origin/architecture-cleanup`. Both new branch tips were verified at `4de121bf7ed486e9f39b5a3f4f5c4288f439c8c8`. Future rebuild work belongs on this new branch. The branch recorded in the baseline table below is the historical execution branch.

There were no tracked changes to transfer. The user's untracked report remains in the working directory, byte-identical to its pinned SHA-256; it was not committed or uploaded. Epic planning/evidence artifacts remain outside the repository and are unaffected by the branch switch. No Phase 0 gate or implementation authorization changed.

## Current disposition — host/static work reviewed

Both debug assembles, the three full unit suites and the two diagnostic selected-class runs are recorded. GLM delivered the corrected behavior/impact inventory and bounded storage audit; Gemini 3.8 Flash/high/Antigravity accepted all corrected **document and static mapping scopes** through [review Addendum 4](review). Original six failing methods remain unresolved; accepted documentation does not turn them into passing tests or proven causes.

No further broad static searches are required for the audited store failures. The user subsequently revised the gate as recorded above: **the host/static Phase 0 baseline is complete**, with device/native checks deferred and storage runtime discrimination retained before Phase 3–4. Phase 1 starts on explicit user authorization, not merely on reviewer acceptance.

The tables below are the current status; later narrative updates preserve the history of how the evidence was qualified.

| Fact | Recorded baseline |
| --- | --- |
| Repository revision | `4de121bf7ed486e9f39b5a3f4f5c4288f439c8c8` |
| Branch | `inpainting-modes-redesign` |
| Workspace | `C:\Users\ADMIN\Documents\Coding Related\tachiyomiATVibe-inpainting-modes-redesign` |
| Tracked source status at start | No tracked changes |
| User-owned untracked file | `TachiyomiATVibe_Translation_Simplicity_First_Architecture_Report.md`; preserve unchanged |
| User report SHA-256 | `EFC11B90A7D0B367F3878BD600BF893AA99ECCDB5879D902E10EB6E06EF88DDD` |
| Executable Gradle Java | Oracle JDK 21.0.8 from PATH, JAVA_HOME unset; executor records this deviation from CI JDK 17 |
| Project bytecode / CI JDK | Java/Kotlin target 17; CI setup JDK 17 |
| Gradle wrapper | 8.12 |
| Android config | Compile SDK 35, target SDK 34, minimum SDK 26, NDK 27.1.12297006 |
| Existing SDK | `C:\Users\ADMIN\AppData\Local\Android\Sdk` |
| ADB probe | Version 37.0.0 / adb 1.0.41; `adb devices -l` returned no connected devices |

The workspace is not described as entirely clean: the user's untracked report exists. The tracked baseline is unchanged. The full report is now locally available, including the continuation omitted from the earlier chat paste; Phase 0 requirements agree with the recorded baseline/behavior/measurement scope.

# Work ownership and progress

| Work item | Owner / scope | Current status |
| --- | --- | --- |
| P0.1 provenance and environment | Coordinator | Revision/SDK/ADB/CI inspection recorded; [post-build model/APK hashes](provenance-assets) captured; no device connected |
| P0.2 builds and tests | GLM 5.3 Flash max, agent `661ba691-432c-4643-a55e-c67024900687` | Execution evidence and qualified [triage](build-and-tests/failure-triage) reviewed and accepted. Both debug builds passed; six original failing methods remain; no causes or repairs validated. |
| P0.3 behavior, critical tests and impact exercises | GLM 5.3 Flash max, agent `a6e2f669-2489-4057-9001-2148263e7e9b` | Corrected [static inventory](behavior-and-change-isolation) accepted by Gemini Addendum 2: behavior/impact/selector/failure cross-reference scope. No runtime coverage or device outcome inferred. |
| P0.4 actual device distributions | Coordinator / reference device | Deferred by user; [protocol](device-baseline) retained. No distributions or device validation claimed. Relevant Phase 3–4/8 gates remain. |
| P0.5 independent evidence review | Gemini 3.8 Flash high through Antigravity, agent `871251fc-68c9-446b-bc64-8c15de349940` | [Addendum 4](review) accepted corrected host/static evidence and audit documentation. Physical/device and runtime storage-discrimination gates remain open. |
| P0.6 bounded store-contract audit | GLM 5.3 Flash max, build executor | Corrected document/static mapping accepted by Gemini Addendum 4. No further broad source searches requested; runtime rejection/lock-owner experiments remain unperformed. |

Delegated agents may write their artifact and generated build outputs only. They may not change production/tests/build logic, fetch/convert models, install toolchains, delete evidence, commit/push, or contact paid providers to make the baseline green. Shell-local use of an existing compatible SDK/JDK is permitted and must be recorded. Diagnose infrastructure failures separately from compiler/test failures.

# Required exit evidence

| Exit evidence | Status |
| --- | --- |
| Revision, branch and user-owned input pinned | Recorded |
| Applicable local builds/tests with run provenance | Both debug assembles and full app/domain suites executed; release/CI parity remains separately unverified |
| Baseline failures identified and owned | Six methods and reproduction matrix documented; qualified interpretations reviewed. Runtime causes, necessary repairs and regression rebaseline remain outstanding |
| Required behavior inventory linked to actual assertions | Corrected static artifact accepted by Gemini; explicit native/publication/store/runtime gaps retained |
| Three change-isolation exercises | Static trace and semantic-versus-mechanical caveats accepted by Gemini |
| Actual supported-device distributions | Deferred by user; carried to relevant execution-change and release gates, not inferred from host results |
| Independent evidence review | Corrected host/static documentation accepted through Addendum 4; user subsequently authorized narrowed host-baseline closure and Phase 1 |
| Tracked diff and input hash | Unchanged at latest check; verify again on completion |

Closure follows the user's explicit gate revision, not a claim that host tests pass or that a device protocol supplies measurements. All missing evidence remains visible; Phase 1 must preserve baseline failures and cannot use this closure as permission to change unverified native/publication mechanisms.

# Baseline execution policy

Run existing tasks before modifying implementation. Preserve old XMLs before overwrite; fresh summaries need actual task timestamps/commands, not inferred freshness from file presence. An UP-TO-DATE/FROM-CACHE result is labeled as such. Missing prerequisites are environment failures, not test failures. Test absence is a coverage gap, not a failing test. Repeated runs diagnose an identified condition; retries never erase the original result.

The repo CI runs `spotlessCheck assembleStandardRelease testDevReleaseUnitTest testStandardReleaseUnitTest` after model provisioning. Local DevDebug/StandardDebug compiler and boundary tasks are useful evidence but do not prove this release pipeline passed. Record each attempted flavor and stage separately, and never modify provisioning/backends during the architectural baseline.

The earlier planning statements “no build/test commands executed” apply to the time they were written. This execution artifact and its child results become the authoritative runtime record for Phase 0.

## Execution update — first build started

The build executor reported preserving 30 pre-existing app/domain XML files into epic scratch `phase-0-baseline/pre-existing-test-results/2026-10-14T11-56-55Z` before test execution. It found existing ignored `local.properties` with the same SDK path and a cached Gradle 8.12 distribution. This refines the initial SDK probe; no SDK configuration file was created by the coordinator. Generated reports still require fresh command/result provenance before classification as current baseline evidence.

## Observed host results — coordinator XML cross-check

Both `assembleDevDebug` and `assembleStandardDebug` completed successfully. These were warm incremental builds with executed, cached and up-to-date subtasks; no cold-build timing or release-variant success is claimed.

The coordinator independently parsed the preserved fresh-run XML directory `scratch/phase-0-baseline/fresh-run-test-results/2026-10-14T12-26-30Z`, rather than relying on subsequently overwritten live reports:

| Task | XML suites | Test executions | Failures | Errors | Skips | Passed |
| --- | --- | --- | --- | --- | --- | --- |
| `:app:testStandardDebugUnitTest` | 332 | 2261 | 5 | 0 | 1 | 2255 |
| `:app:testDevDebugUnitTest` | 332 | 2261 | 6 | 0 | 1 | 2254 |
| `:domain:testDebugUnitTest` | 6 | 66 | 0 | 0 | 0 | 66 |
| **Total** | **670** | **4588** | **11** | **0** | **2** | **4575** |

There are **six distinct failing methods**, not eleven unique defects. Five failed in both app flavors; one additional method failed in DevDebug. This is observed flavor parity/difference, not proof of deterministic failure or flakiness.

| Current failure owner | Distinct methods | Observed category |
| --- | --- | --- |
| `diagnostics/TranslationDebugMeasurementTest` | 1 | File sink order/sanitization assertion |
| `persistence/chapter/ChapterTranslationStoreArtifactOpenProbeTest` | 4 | Two lazy-artifact assertions and two JUnit temporary-directory close failures |
| `pipeline/batch/ProfileEnvelopeDispatchTest` | 1 | DevDebug unfinished-coroutine error |

The executor is performing narrow diagnosis with original full-suite evidence preserved. A passing isolated run cannot erase a full-suite failure or prove suite-interaction correctness. No implementation repair or test deletion is authorized in this baseline task.

Local JDK 21 and debug flavors do not reproduce CI JDK 17/release/spotless coverage. Running both flavors on the same JDK cannot rule out a JDK-dependent failure. These gaps stay explicit.

## Source inventory cross-check

Coordinator verified Git tracked paths and filesystem enumeration: requested production subtree **290 = 288 Kotlin + 2 Java**; ordinary unit tests **318**; testDebug **1**; translation Android tests **6**. Java files are `engines/runtime/onnx/CheckNnapi.java` and `CheckXnnpack.java`. Do not add unrelated paths to reconcile a Kotlin-only count.

## User device decision

The structured question was answered **Keep device measurements pending**. Continue host build, diagnosis and source/coverage work. This does not complete or remove Phase 0's device gate. No repeated connection prompts are needed until the user supplies hardware or changes this instruction.

## Interim triage continuation

The build executor ended a turn while its diagnostic DevDebug shell was still running, without sending the requested result reply. Coordinator read its transcript, verified source/failure evidence, and sent an explicit continuation request. The original reply obligation remains open; no diagnostic completion is inferred from a quiet agent.

Coordinator confirmed the debug trace sink format mismatch: `app/src/debug/java/eu/kanade/translation/diagnostics/TranslationTraceFileSink.kt:69–80` adds `ts=`; `app/src/testDebug/java/eu/kanade/translation/diagnostics/TranslationDebugMeasurementTest.kt:23` expects raw unprefixed lines. The full-suite XML shows the timestamped payload still in the expected queue order with the embedded newline sanitized. This supports assertion-format drift; it does not justify deleting the ordering/sanitization contract.

Executor reports the original five StandardDebug failures reproduced in a selected-class StandardDebug diagnostic run, with ProfileEnvelopeDispatchTest passing in that run. DevDebug diagnostic comparison and finalized triage evidence are still pending. Do not describe this as proof of deterministic failure, confirmed load sensitivity or a clock-related root cause. Record observed reproduction and a testable hypothesis separately.

Repository-wide **ordinary unit-test Kotlin sources** were independently re-counted as 348 (app 342 + domain 6). The one testDebug source and nine repository-wide Android test sources are separate; 357 includes instrumentation and is not the ordinary-unit-source count. The requested translation-subtree counts above remain unchanged.

## Completed isolated reproduction — supersedes interim pending status

GLM delivered the corrected [build baseline](build-and-tests) and [failure triage](build-and-tests/failure-triage). Coordinator parsed the six preserved diagnostic XMLs from `scratch/phase-0-baseline/narrow-triage-test-results/2026-10-14T12-43-14Z`:

| Isolated class | StandardDebug tests / failures | DevDebug tests / failures |
| --- | --- | --- |
| TranslationDebugMeasurementTest | 2 / 1 | 2 / 1 |
| ChapterTranslationStoreArtifactOpenProbeTest | 14 / 4 | 14 / 4 |
| ProfileEnvelopeDispatchTest | 20 / 0 | 20 / 0 |
| **Diagnostic total per flavor** | **36 / 5** | **36 / 5** |

These diagnostics add 72 test executions and 10 failure executions; they do not replace the original full-suite 4588-execution / 11-failure ledger. Five methods reproduced in all four observed full/isolated runs; the protocol method failed in full DevDebug and passed the other observed configurations. No stability probability or confirmed clock/load root cause follows from this matrix.

Nested cleanup exceptions name manifest paths and the fixtures leave stores unclosed. This supports investigation of fixture/resource ownership, but does not identify a particular handle owner or rule out production lifecycle behavior without a closure-controlled reproduction. Likewise, a null reopened cleaned-image name and empty manifest page map establish failed assertions; their underlying cause may involve contract/fixture assumptions or production behavior and needs source/behavior review.

The fresh Antigravity Gemini reviewer has been briefed on completed triage and these interpretation limits. No repository fix or additional broad run has been started.

## Independent review delivered

The [Gemini Antigravity review](review) corroborates observed baseline integrity/counts and requires corrections to failure interpretation and literal diagnostic commands. Coordinator accepted the early store-contract prerequisite, assigned documentation amendments to GLM and requested delivery of the pending source/behavior map. The [correction ledger](review-response) distinguishes accepted sequencing changes from pending evidence. Phases 3–4 may not proceed on unresolved candidate/commit and persistence-resource assumptions merely because deep persistence restructuring is scheduled in Phase 7.

## Behavior inventory delivered — validation pending

**Final static recheck update:** Gemini Addendum 2 accepted the corrected inventory, selectors and impact exercises. Coordinator mechanically checked the 46 ordinary-source selector paths/FQNs; all matched the source package and filename. The separate testDebug selector accounts for the 47th entry. This supersedes earlier pending static-amendment status below, while retaining unmeasured runtime and storage-contract gates.

## Bounded store-contract audit delivered — interpretation pending

**Addendum 3 update:** Gemini confirmed the correction points below. GLM is revising the audit; source mapping is accepted with qualifications, not as a proven root cause or a completed Phase 3 entry gate. No diagnostic experiment was run.

**Amendment delivery:** GLM corrected the Unit diagnostic, KDoc scope, single-cause assertion, channel allocation assumptions and probe exclusions, and removed the spec completion-status field. Coordinator checked these changes and requested final Gemini acceptance of document accuracy only. Runtime rejection/handle ownership and physical/device evidence remain open.

GLM mapped registration, lazy mutation, promotion/reopen and file ownership paths in the [store-contract audit](store-contract-audit). It performed no new runs or code changes. Publication rejection is a plausible source path, but the runtime reason and competing queue/validation/fixture/reopen paths are not yet discriminated.

Coordinator identified points for Gemini review: the pre-registration KDoc's “missing pages” sentence refers to the source/download cross-check context and may not contradict later materialization of supplied placeholders; `updatePage` returns Unit, so its PatchResult cannot be asserted as suggested; allocating a journal writer does not prove its FileChannel was opened in each fixture; unused probe seams alone do not eliminate image/reference validation hypotheses. The observed manifest lock owner remains unresolved. No production-root-cause or contract-conflict conclusion is accepted yet.

**Review update:** Gemini accepted the build/triage amendments. Initial behavior inventory review confirmed the publication-invariant, source-set and coverage wording corrections below. GLM source amendments are pending; the new bounded store-contract audit is separately assigned and has not yet delivered evidence.

The [behavior/change-isolation artifact](behavior-and-change-isolation) maps existing manual/auto/batch, cancellation, native completion, provider drain, resume, publication, user edits, source-ready, memory and local-compute behavior. It distinguishes existing configurable auto windows from proposed N/N+1 demand and data-field evolution from callback signature expansion.

Coordinator found a contract wording error in its publication row: deletion after a cancelled commit is **observed baseline behavior/hazard**, not a preservation requirement. Required behavior retains accepted or potentially referenced output and only retires a definitively unreferenced file with authoritative permission. GLM is correcting that row, source-set labels and coverage claims, plus test-method names and literal selectors. No new production behavior has been implemented.

Textual absence of `inpaintJob` references is useful coverage evidence but does not prove the absence of every indirect cancellation test. Actual native cancellation/late completion remains a device/integration gap, not coverage automatically supplied by existing OCR/inpaint measurement classes. The Antigravity reviewer is checking this new inventory alongside amended triage.
