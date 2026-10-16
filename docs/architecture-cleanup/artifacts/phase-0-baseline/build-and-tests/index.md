---
title: "Phase 0 — reproducible build and test baseline"
kind: spec
comments: none
---

# Status

COMPLETE (build/test baseline layers). Environment verified, both DevDebug and StandardDebug assembles green, all three unit suites executed with fresh provenance, owned failures recorded, evidence preserved. Device/paid-provider layers and behavior-ledger work belong to other Phase 0 owners.

# Layer 1 — Environment and toolchain (COMPLETE)

| Item | Value | Notes |
| --- | --- | --- |
| Baseline revision | `4de121bf7ed486e9f39b5a3f4f5c4288f439c8c8` | branch `inpainting-modes-redesign` |
| Tracked tree state | clean | verified `git status --porcelain` pre-run; only untracked user-owned `TachiyomiATVibe_Translation_Simplicity_First_Architecture_Report.md` (SHA256 `EFC11B90A7D0B367F3878BD600BF893AA99ECCDB5879D902E10EB6E06EF88DDD`, re-verified byte-identical after env inspection AND after all builds/tests) |
| Gradle | 8.12 (wrapper; distribution already cached in `~/.gradle/wrapper/dists`) | `gradlew --version` UTC 2026-10-14T11:56Z, exit 0 |
| JVM | Oracle JDK 21.0.8+12-LTS-250 from PATH (`java` on PATH); `JAVA_HOME` unset | no local JDK 17 exists: `C:\Program Files\Java\jdk-21` (21), JRE 1.8, Android Studio JBR 21.0.10. **Deviation from CI (JDK 17) recorded**; no toolchain pin exists in the build (`compileOptions`/`jvmTarget` = 17 bytecode only), so daemon JDK 21 compiles bytecode 17 |
| Android Gradle Plugin | 8.8.1 | `gradle/androidx.versions.toml` |
| Android SDK | `C:\Users\ADMIN\AppData\Local\Android\Sdk` | via `local.properties` `sdk.dir` (file present in this worktree) |
| Model assets | PRESENT | `app/src/main/assets/models/` holds provisioned git-ignored onnx/tflite (detector, inpainting aot/manga-lama, Paddle OCR). `fetch_models.py` NOT run (instructed); CI's release-asset prerequisite is already satisfied on disk |
| Gradle config | as committed | `org.gradle.parallel=true`, `caching=true`, `configureondemand=true`, configuration cache deliberately disabled (commented in `gradle.properties`) |
| Spotless | standalone convention plugin (`mihon.code.lint.gradle.kts`) | not wired into `assemble`; out of assigned scope |
| Env overrides applied | NONE | shell-local `JAVA_HOME`/`ANDROID_HOME` overrides not needed; gradlew resolved PATH java |

# Layer 2 — Pre-existing test evidence (COMPLETE, preserved BEFORE any new run)

Copied UTC 2026-10-14T11:56:55Z to epic scratch `phase-0-baseline/pre-existing-test-results/2026-10-14T11-56-55Z/`:

- `app/build/test-results/testDevDebugUnitTest` — 25 XML files (last write 2026-10-13T13:37 local)
- `app/build/test-results/testStandardDebugUnitTest` — 5 XML files (last write 2026-10-14T00:11 local)
- `domain/build/test-results/testDebugUnitTest` — 0 XML (empty dir)
- Total preserved: 30 XML. These are historical, NOT evidence of the fresh runs below.

# Layer 3 — Builds/compile (COMPLETE)

Sequential Gradle invocations, one per background shell, plain console:

| # | Command | Started (UTC) | Exit | Outcome |
| --- | --- | --- | --- | --- |
| 1 | `./gradlew.bat :app:assembleDevDebug --console=plain --stacktrace` | 2026-10-14T11:58:22Z | 0 (ended 12:08:22Z, BUILD SUCCESSFUL in 9m56s) | 273 actionable tasks: 35 executed, 9 from-cache, 229 up-to-date (warm build). Full log: epic scratch `commands/f528b8ec-6cf2-491a-9ca2-c2c9cab2983a/output.log` |
| 2 | `./gradlew.bat :app:assembleStandardDebug --console=plain --stacktrace` | 2026-10-14T12:08:43Z | 0 (ended 12:12:12Z, BUILD SUCCESSFUL in 3m28s) | 273 actionable tasks: 16 executed, 3 from-cache, 254 up-to-date. Kotlin compile pre-existing warnings only (`-Xcontext-receivers` deprecation, delicate-API notes). Log: `commands/5ac01636-377c-4d66-ad58-4ae7f3fb5e06/output.log` |
| 3 | `./gradlew.bat :app:testDevDebugUnitTest :app:testStandardDebugUnitTest :domain:testDebugUnitTest --console=plain --stacktrace` | 2026-10-14T12:12:38Z | gradlew exit 1 (ended 12:18:18Z, BUILD FAILED in 5m39s) | Executed **:app:testStandardDebugUnitTest** (owned failures below) and **:domain:testDebugUnitTest** (parallel, PASS). Gradle fail-fast aborted **:app:testDevDebugUnitTest** before execution (no fresh XML) — that suite re-run individually as #4. Log: `commands/98199554-d2e0-428d-bd37-5dfabacbf4da/output.log` |
| 4 | `./gradlew.bat :app:testDevDebugUnitTest --console=plain --stacktrace` | 2026-10-14T12:20:14Z | gradlew exit 1 (task window ended 12:25:37Z, BUILD FAILED in 5m22s) | **:app:testDevDebugUnitTest executed fully:** see Layer 4. Log: `commands/bd8eda34-8536-4daa-b62f-d120d3701609/output.log` |
| 5 | `./gradlew.bat :app:testStandardDebugUnitTest --tests "eu.kanade.translation.diagnostics.TranslationDebugMeasurementTest" --tests "eu.kanade.translation.persistence.chapter.ChapterTranslationStoreArtifactOpenProbeTest" --tests "eu.kanade.translation.pipeline.batch.ProfileEnvelopeDispatchTest" --rerun --console=plain --stacktrace` (diagnostic) | 2026-10-14T12:39:27Z | gradlew exit 1 (ended 12:40:17Z, BUILD FAILED in 49s) | Narrow triage: the 5 both-flavor failures reproduce; `ProfileEnvelopeDispatchTest` PASSES 20/20 isolated (10.9s). Log: `commands/b86b6ff7-093e-4ee8-8abb-a8c1ab58c9a3/output.log` |
| 6 | `./gradlew.bat :app:testDevDebugUnitTest --tests "eu.kanade.translation.diagnostics.TranslationDebugMeasurementTest" --tests "eu.kanade.translation.persistence.chapter.ChapterTranslationStoreArtifactOpenProbeTest" --tests "eu.kanade.translation.pipeline.batch.ProfileEnvelopeDispatchTest" --rerun --console=plain --stacktrace` (diagnostic) | 2026-10-14T12:42:01Z | gradlew exit 1 (ended 12:42:40Z, BUILD FAILED in 38s) | Same 5 reproduce on Dev; `ProfileEnvelopeDispatchTest` PASSES 20/20 isolated (14.3s). Log: `commands/a909916a-a79d-4d7a-ba16-11ad177513f3/output.log` |

Runs 5-6 are DIAGNOSTIC narrow runs (per-task `--rerun` to bypass the FAILED-task skip; `--tests` filter); their XML overwrote the workspace test-results dirs but the authoritative full-suite XML was preserved to scratch BEFORE (670 files, re-verified intact) and the narrow-run class XML is separately preserved. Full analysis in [failure triage](failure-triage/index.md).

Benign build noise (pre-existing, NOT failures): thousands of non-fatal `WARNING: D8: Unexpected error during rewriting of Kotlin metadata` during dexing (with stack traces, inflate the logs); `Unable to strip the following libraries` for ~33 prebuilt native libs (LiteRT/QNN/ONNX/OpenCV/etc.); Gradle 9.0 deprecation notice. APKs produced under `app/build/outputs/apk/{dev,standard}/debug/`.

# Layer 4 — Test suites (COMPLETE — fresh provenance; all XML timestamped 2026-10-14T12:13–12:25Z)

Test source inventory (exact scopes, verified 2026-10-14T12:45Z):

| Scope | .kt files |
| --- | --- |
| `app/src/test` | 342 (0 .java) |
| `domain/src/test` | 6 |
| **Ordinary unit test sources** | **348** |
| `app/src/testDebug` (debug-build-type unit tests; runs in BOTH flavor suites) | 1 — `TranslationDebugMeasurementTest` (separate source set) |
| `app/src/androidTest` (instrumentation) | 9 (separate) |

Reconciliation: the earlier "357" = 348 ordinary unit + 9 instrumentation (NOT 357 ordinary src/test); `342` was app/src/test only; the debug sink tested by the testDebug class lives in `app/src/debug/java/…` (not `src/main`). Translation-named files (name-match `Translat`, includes the non-test `TranslationCoexistenceHarness.kt`): 81 in `app/src/test` + 1 in `testDebug` + 3 in `domain/src/test` = 85 across all test scopes (earlier "84" = src/test + domain, missing the testDebug file). Authoritative executed-evidence counts are the fresh XML: 332 test classes per app flavor.

| Suite | Tests | Failures | Errors | Skipped | Passed (tests − failures − skips) | Result |
| --- | --- | --- | --- | --- | --- | --- |
| `:app:testStandardDebugUnitTest` | 2261 | 5 | 0 | 1 | 2255 | BUILD FAILED (owned failures) |
| `:app:testDevDebugUnitTest` | 2261 | 6 | 0 | 1 | 2254 | BUILD FAILED (owned failures) |
| `:domain:testDebugUnitTest` | 66 | 0 | 0 | 0 | 66 | PASS |

Totals: **4588 test executions, 11 failures, 0 errors, 2 skips** across the three fresh suites. **6 distinct failing methods**: 5 failed in EVERY observed run (both flavors, full-suite AND isolated — reproduction, not a stability proof); the 6th (`ProfileEnvelopeDispatchTest`, `UncompletedCoroutinesError`) shows full-suite FAIL (Dev) / full-suite pass (Standard) / isolated PASS on both flavors — see [failure triage](failure-triage/index.md), mechanism hypothesis pending a clock-controlled test. Skip in both app suites: `SyntheticJournalReplayBenchTest.synthetic200PageJournalFullReplayBenchmark()` (empty `<skipped/>`, deliberate benchmark skip).

### Owned test failures (identical on both flavors unless noted)

| Test class | Test | Failure type | First message line |
| --- | --- | --- | --- |
| `eu.kanade.translation.diagnostics.TranslationDebugMeasurementTest` | `file sink preserves queue order and sanitizes embedded line breaks()` | `io.kotest.assertions.AssertionFailedError` | `Element differ at index: [0, 1, 2]` |
| `eu.kanade.translation.persistence.chapter.ChapterTranslationStoreArtifactOpenProbeTest` | `lazy store cuts over before first candidate and reopens from artifact pointers()` | `java.lang.AssertionError` | `Expected "page.cleaned.retry.jpg" but actual was null` |
| `ChapterTranslationStoreArtifactOpenProbeTest` | `pre-registered pages publish one durable baseline with only the changed candidate()` | `io.kotest.assertions.AssertionFailedError` | `expected:<["p1.jpg", "p2.jpg", "p3.jpg"]> but was:<[]>` |
| `ChapterTranslationStoreArtifactOpenProbeTest` | `artifact-only lazy store writes pages without materializing flat compatibility file()` | `org.junit.platform.commons.JUnitException` | `Failed to close extension context` (Caused by `java.io.IOException` at JUnit `TempDirectory.java:449`) |
| `ChapterTranslationStoreArtifactOpenProbeTest` | `lazy store without parent resolves artifact authority through artifactParentResolver()` | `org.junit.platform.commons.JUnitException` | `Failed to close extension context` (Caused by `java.io.IOException` at JUnit `TempDirectory.java:449`) |
| `eu.kanade.translation.pipeline.batch.ProfileEnvelopeDispatchTest` (**failed only in the DevDebug full-suite run**; passed Standard full-suite and isolated on both flavors) | `protocol outcome commits fully covered pages and parks omitted block pages without pausing()` | `kotlinx.coroutines.test.UncompletedCoroutinesError` | coroutine test left unfinished jobs |

Classification: these are **owned baseline test failures, not precondition/infrastructure failures** — each suite ran to completion (Standard: 2261 − 5 failures − 1 skip = **2255 passed**; Dev: 2261 − 6 − 1 = **2254 passed**). The 5 methods above it failed identically in both flavors and again isolated; the `TempDirectory` cases fail while JUnit CLOSES the `@TempDir` extension (corroborated Windows cleanup failures — see [failure triage](failure-triage/index.md)); `ProfileEnvelopeDispatchTest` reproduces only in the full-suite context so far (hypothesis pending a clock-controlled test) and must not be assumed a pure logic failure.

Fresh evidence: 670 XML copied to epic scratch `phase-0-baseline/fresh-run-test-results/2026-10-14T12-26-30Z/` (app DevDebug 332 + app StandardDebug 332 + domain 6). Pre-run historical evidence (30 XML) is under `pre-existing-test-results/2026-10-14T11-56-55Z/`. Raw Gradle logs retained under epic scratch `commands/{f528b8ec,5ac01636,98199554,bd8eda34}-*/output.log`.

# Layer 5 — Conclusions (COMPLETE)

1. **Build layer: GREEN.** `:app:assembleDevDebug` (9m56s) and `:app:assembleStandardDebug` (3m28s) both BUILD SUCCESSFUL at baseline on JDK 21.0.8 / Gradle 8.12 / AGP 8.8.1 with zero environment overrides. Model-asset precondition satisfied by already-provisioned git-ignored assets — `fetch_models.py` was NOT executed.
2. **Test layer: 11 owned failures across 4588 executions, all in translation behavior** — 5 methods failed in every observed run (`TranslationDebugMeasurementTest` ×1, `ChapterTranslationStoreArtifactOpenProbeTest` ×4) + `ProfileEnvelopeDispatchTest` ×1 (full-suite Dev FAIL, isolated PASS both flavors). Full per-failure triage, reproduction matrix, classifications, and reviewer disposition in [failure triage](failure-triage/index.md). These are the Phase 0 "existing failures" ledger entries: diagnose before moving on; never retry away; never delete the live contract to get green. Sequencing correction per review: the four `ChapterTranslationStoreArtifactOpenProbeTest` failures (2 assertion mismatches + 2 Windows `@TempDir` close failures with verified suppressed `FileSystemException` "used by another process") are NOT deferred to Phase 7 — they require a Phase 0 baseline contract/source/fixture/cleanup investigation (handle-holder closure-controlled test; promotion/reopen/pre-registration contract audit) whose outcome must land BEFORE Phase 3/4 entry, with a small first-slice repair/re-baseline if a defect is found. Phase 0 authorizes no implementation repair. `TranslationDebugMeasurementTest` may be repaired in Phase 1 test repair once the sink line contract is settled (the Phase 2 telemetry relocation is not a necessary precondition).
3. **Environment deviation from CI:** local daemon JDK 21 (Oracle 21.0.8; no JDK 17 installed on this machine) vs CI's JDK 17. Bytecode target 17 is honored either way. Both local suites ran on the SAME JDK 21, so these runs provide NO evidence about JDK-dependence of the failures; JDK 17 behavior is untested locally.
4. **Baseline infrastructure: no blockers.** Every environment/precondition category (SDK, assets, JDK, Gradle dist) was verified before the first test task; nothing was installed, deleted, or reconfigured to obtain these results. `git status` clean at start and end; user-owned report byte-identical (SHA256 `EFC11B…DDDD`).
5. **Out of scope here, owned elsewhere in Phase 0:** spotlessCheck and release variants (CI parity run), device latency/native-occupancy/throughput/memory distributions, paid-provider/cancellation/source-ready/refresh/recovery behavior inventory, change-isolation exercise traces.
