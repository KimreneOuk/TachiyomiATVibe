---
title: "P2.6 consolidated validation results"
kind: spec
comments: none
---

# Status

P2.6 validation completed at HEAD 999bb9be6563f957ee508f739ba2f042bf1c90ee. Both app debug assemblies and the fresh domain test run succeeded. Each full app flavor ran 2,254 tests; the same four ArtifactOpenProbe failures recorded in Phase 0 and Phase 1 remain, with no additional failures. The three TranslationPackageBoundaryTest cases pass in both flavors. The app Spotless check reports the existing LiteRT formatting debt only.

P2.5 actual-diff acceptance is documented in [P2.5 source-fingerprints review](../../source-fingerprints/review). P2.6 final evidence review and parent disposition remain pending. No source edits, commits, pushes, staging, or duplicate P2.6 runs were made. No separate P2.5 focused-set rerun followed its EOF-only formatting fix; the later P2.6 full app suites intentionally exercised the P2.5 rekey regression and cache tests within each flavor's 2,254-test run.

# Validation pin and working tree

- Branch: architecture-cleanup, tracking origin/architecture-cleanup [ahead 5].
- HEAD: 999bb9be6563f957ee508f739ba2f042bf1c90ee.
- Phase 2 diff from dab5c124: 46 paths, +1,063 / -189, rename-aware across five commits. The source moves are not deletions.
- git diff --numstat is empty. Eleven tracked status-M entries remain with empty content diffs; these are previously verified exact-HEAD line-ending/stat artifacts and were not rewritten or staged:
  - app/src/main/java/eu/kanade/translation/pipeline/TranslationPipeline.kt
  - app/src/main/java/eu/kanade/translation/scheduling/TranslationScheduler.kt
  - app/src/test/java/eu/kanade/translation/coexistence/P5HonestOutcomeTypingTest.kt
  - app/src/test/java/eu/kanade/translation/coexistence/RenderPermitReacquisitionTest.kt
  - app/src/test/java/eu/kanade/translation/presentation/P5OutcomeProjectionTest.kt
  - app/src/test/java/eu/kanade/translation/scheduling/CancelBatchLeaseSkipTest.kt
  - app/src/test/java/eu/kanade/translation/scheduling/CancelSyncStoreWriteTest.kt
  - app/src/test/java/eu/kanade/translation/scheduling/RollingAutoCoordinatorRegistrationRaceTest.kt
  - app/src/test/java/eu/kanade/translation/scheduling/RollingAutoCoordinatorTest.kt
  - app/src/test/java/eu/kanade/translation/scheduling/RollingAutoCoordinatorTraceTest.kt
  - app/src/test/java/eu/kanade/translation/scheduling/TranslationSchedulerTraceTest.kt
- The untracked architecture report remains untouched: SHA-256 EFC11B90A7D0B367F3878BD600BF893AA99ECCDB5879D902E10EB6E06EF88DDD. It is excluded from the Phase 2 diff.
- The P2.5 revision evidence, including its genuine RED, corrected fixture false-start, both-flavor GREEN, and post-EOF Spotless inventory, is in [P2.5 results](../../source-fingerprints/results).

# Toolchain and inventory

| Item | Recorded value |
| --- | --- |
| OS / architecture | Windows 11, 10.0, amd64 |
| Java | Oracle JDK 21.0.8+12-LTS-250; JAVA_HOME unset; PATH resolves through Oracle javapath; Gradle daemon at C:/Program Files/Java/jdk-21 |
| Gradle / Kotlin / AGP | Gradle 8.12 / Kotlin 2.0.21 / Android Gradle Plugin 8.8.1 |
| Android SDK | C:/Users/ADMIN/AppData/Local/Android/Sdk |
| CI Java comparison | CI selects Java 17; JDK 17 is absent locally, so JDK 17 parity is unverified |
| app source sets | app/src/main: 862 files (860 Kotlin, 2 Java); app/src/test: 342 Kotlin; app/src/testDebug: 1 Kotlin; app/src/androidTest: 9 Kotlin |
| domain source sets | domain/src/main: 99 Kotlin; domain/src/test: 6 Kotlin |
| translation subtree | main: 285 files (283 Kotlin, 2 Java); ordinary tests: 318 Kotlin; testDebug: 1 Kotlin; androidTest: 6 Kotlin |

# Commands and outcomes

Each Gradle invocation had its own retained shell log. Full app test XML was copied into its pre-created unique directory immediately after the task, before another test task could overwrite Gradle's reports. No test filters were supplied to either full app test task.

| Run | Literal command | Shell / exit | Result and preserved evidence |
| --- | --- | --- | --- |
| DevDebug assembly | ./gradlew.bat :app:assembleDevDebug --console=plain --stacktrace | d63b5f74-f6a6-4e74-a69d-3118fdf83874 / 0 | BUILD SUCCESSFUL in 3m55s; 273 actionable tasks (17 executed, 256 up-to-date). Retained log: commands/699de408-00ac-4fe5-a903-b541c23063cc/d63b5f74-f6a6-4e74-a69d-3118fdf83874/output.log (known missing prefix; see assemble log audit below). |
| StandardDebug assembly | ./gradlew.bat :app:assembleStandardDebug --console=plain --stacktrace | bfa9f03b-b559-4435-9250-caeeaa228913 / 0 | BUILD SUCCESSFUL in 7m08s; 273 actionable tasks (16 executed, 1 from cache, 256 up-to-date). The retained log contains a nonfatal AAPT2 shutdown TimeoutException (“Failed to shut down within 30 seconds. Forcing shutdown”) and has a known missing prefix; see assemble log audit below. Retained log: commands/699de408-00ac-4fe5-a903-b541c23063cc/bfa9f03b-b559-4435-9250-caeeaa228913/output.log. |
| Full DevDebug app tests | ./gradlew.bat :app:testDevDebugUnitTest --rerun --console=plain --stacktrace | 17526176-980f-4a98-8d2b-13f87598363e / 1 | XML: scratch/phase-2-contracts/validation/2026-10-15-999bb9be/dev/xml. 332 suites, 2,254 tests, 4 failures, 0 errors, 1 skipped, 2,249 passed. The nonzero Gradle exit reflects the four baseline failures. Log: commands/699de408-00ac-4fe5-a903-b541c23063cc/17526176-980f-4a98-8d2b-13f87598363e/output.log |
| Full StandardDebug app tests | ./gradlew.bat :app:testStandardDebugUnitTest --rerun --console=plain --stacktrace | 7e7b8253-5e63-405d-a23e-a07c74ca4a46 / 1 | XML: scratch/phase-2-contracts/validation/2026-10-15-999bb9be/standard/xml. 332 suites, 2,254 tests, 4 failures, 0 errors, 1 skipped, 2,249 passed. The same four baseline failures account for the nonzero Gradle exit. Log: commands/699de408-00ac-4fe5-a903-b541c23063cc/7e7b8253-5e63-405d-a23e-a07c74ca4a46/output.log |
| Domain debug tests | ./gradlew.bat :domain:testDebugUnitTest --rerun --console=plain --stacktrace | 684d3d10-32f0-44c4-9fbf-e5e829d88fce / 0 | XML: scratch/phase-2-contracts/validation/2026-10-15-999bb9be/domain/xml. 6 suites, 66 tests, 0 failures, 0 errors, 0 skipped, 66 passed. Log: commands/699de408-00ac-4fe5-a903-b541c23063cc/684d3d10-32f0-44c4-9fbf-e5e829d88fce/output.log |
| Read-only app formatting check | ./gradlew.bat :app:spotlessCheck --console=plain --stacktrace | b289617b-b189-4230-a0ba-6b6de8f8cea7 / 1 | Full filename inventory is in the log; one existing LiteRT file is listed. No format changes were applied. Log: commands/699de408-00ac-4fe5-a903-b541c23063cc/b289617b-b189-4230-a0ba-6b6de8f8cea7/output.log |

Across the three test tasks, XML reports 4,574 tests, 8 failures, 0 errors, 2 skipped, and 4,564 passed. The app suites each include all three TranslationPackageBoundaryTest cases: 3/3 passed for DevDebug and 3/3 passed for StandardDebug. Both app XML directories also include the two P2.5 revision test paths: app/src/test/java/eu/kanade/translation/pipeline/batch/BatchResumeEvidenceSelectionTest.kt (3/3 passed) and app/src/test/java/eu/kanade/translation/pipeline/batch/SourceFingerprintCacheTest.kt (9/9 passed). No redundant standalone boundary run was performed. The single skipped case per flavor is SyntheticJournalReplayBenchTest.synthetic200PageJournalFullReplayBenchmark().

# App failure comparison

The four failures below are present in both current flavors and match the Phase 0 immutable XML and Phase 1 full-run XML by test method, exception type, and stable message/cause. The comparison used the actual baseline XML rather than classifying by the outer JUnitException alone. Temp directory names embedded in cleanup traces vary between runs.

| Test method | Type and matching detail |
| --- | --- |
| lazy store cuts over before first candidate and reopens from artifact pointers() | java.lang.AssertionError: Expected "page.cleaned.retry.jpg" but actual was null |
| pre-registered pages publish one durable baseline with only the changed candidate() | io.kotest.assertions.AssertionFailedError: expected:<["p1.jpg", "p2.jpg", "p3.jpg"]> but was:<[]> |
| artifact-only lazy store writes pages without materializing flat compatibility file() | org.junit.platform.commons.JUnitException: Failed to close extension context; nested IOException during temp-directory cleanup for the Chapter2 manifest, with DirectoryNotEmptyException and a FileSystemException reporting that a file was in use by another process |
| lazy store without parent resolves artifact authority through artifactParentResolver() | org.junit.platform.commons.JUnitException: Failed to close extension context; nested IOException during temp-directory cleanup for the Chapter8 manifest, with DirectoryNotEmptyException and a FileSystemException reporting that a file was in use by another process |

Baseline XML sources:

- Phase 0 DevDebug and StandardDebug: scratch/phase-0-baseline/fresh-run-test-results/2026-10-14T12-26-30Z/{app-testDevDebugUnitTest,app-testStandardDebugUnitTest}
- Phase 1 DevDebug: scratch/phase-1-cleanup/consolidated-validation/full-suite/app-devdebug-raw
- Phase 1 final StandardDebug: scratch/phase-1-cleanup/consolidated-validation/fixture-finalization/full-standard-after-fix-raw

# Spotless filename and truncation audit

The complete retained log is 30,137 bytes and 253 newline records. A full-log scan found zero truncation markers and exactly one reported source path:

app/src/main/java/eu/kanade/translation/engines/inpainting/litert/LiteRTMangaInpaintingEngine.kt

Its reported wrapping hunks are around lines 160 and 357. No other filename appears in the format-violation inventory. This is the pre-existing LiteRT wrapping debt; Spotless was read-only and no spotlessApply or source correction was run.

Separate app-test, domain-test, and Spotless logs are retained as individual files. No explicit truncation marker was found in those logs; absence of a marker is not, by itself, proof that a log is complete.

- DevDebug tests: 50,770 bytes / 506 newline records.
- StandardDebug tests: 59,146 bytes / 564 newline records.
- Domain tests: 8,849 bytes / 113 newline records.

## Assemble log registry corroboration and limitation

I checked both assembly records with the Traycer shell registry. Each record confirms the literal command, repository working directory, exited state, and exit code 0:

- DevDebug shell d63b5f74-f6a6-4e74-a69d-3118fdf83874: command ./gradlew.bat :app:assembleDevDebug --console=plain --stacktrace; cwd C:\Users\ADMIN\Documents\Coding Related\tachiyomiATVIBE-inpainting-modes-redesign; creator 3b81dcb4-fa80-48c4-9e9b-20b49c3a10d0; exit 0. Its 3,991,814-byte, 29,539-record retained log begins at 2026-10-15T06:50:22.880Z inside a Gradle worker stack trace. Its tail contains BUILD SUCCESSFUL and the shell exit line.
- StandardDebug shell bfa9f03b-b559-4435-9250-caeeaa228913: command ./gradlew.bat :app:assembleStandardDebug --console=plain --stacktrace; cwd C:\Users\ADMIN\Documents\Coding Related\tachiyomiATVIBE-inpainting-modes-redesign; creator 3b81dcb4-fa80-48c4-9e9b-20b49c3a10d0; exit 0. Its 3,993,755-byte, 29,557-record retained log begins at 2026-10-15T06:56:03.874Z inside a Gradle worker stack trace. Its tail contains BUILD SUCCESSFUL and the shell exit line.
- Neither retained assembly log has an explicit truncation marker. The missing prefixes are established by the first retained records, not by such a marker. The separate shell registrations remove the combined-build identity ambiguity from Phase 1; they do not restore the missing output prefix or expose all startup/task output. The registry records and successful tails corroborate the invocations and their exit codes, while the retained logs are not complete build transcripts. No build rerun was made to reconstruct the missing prefixes.

# Remaining gates

- Local validation used JDK 21 while CI selects JDK 17; matching JDK 17 validation remains outstanding.
- Physical-device, JNI, and native occupancy measurements were not part of these host unit tests.
- The Phase 0 ArtifactOpenProbe/store cleanup causes remain unresolved. Preserve that gate before Phase 3/4.
- Parent/Gemini evidence review is pending. No commit, push, ticket closure, or Phase 3 work is included in this result.
