---
kind: spec
title: "P1.5 consolidated host validation — results and evidence"
comments: none
---

# P1.5 implementation results

Validation-only slice on `architecture-cleanup` @ `b44143f2` vs immutable baseline `4de121bf`. One Gradle owner, sequential individual invocations, no filters, `--rerun` fresh execution, no retries, no clean, no code/config modifications (final `git status --porcelain` = only the untracked user report). Coordinator's clarification followed: the 5 remaining ledgered methods are tracked/unedited anchors — changed observed outcomes are recorded as observations, not forced failures, and no fix is claimed for any pass.

# Environment (recorded)

- HEAD `b44143f2` "refactor(translation): retire unused adaptive concurrency experiment"; branch `architecture-cleanup`; upstream `origin/architecture-cleanup` still at `4de121bf` (ahead 5, unpushed — coordinator publishes).
- Tree clean except untracked `TachiyomiATVibe_Translation_Simplicity_First_Architecture_Report.md`, SHA256 `EFC11B90A7D0B367F3878BD600BF893AA99ECCDB5879D902E10EB6E06EF88DDD` — unchanged, re-verified at start and end.
- Toolchain: JDK `21.0.8` LTS local; Gradle 8.12 deprecation warnings unchanged. localJDK21-vs-CI17 remains a stated limitation, not a proven cause; no release-check waived.
- Spotless/Gradle/build config files are not in the cumulative diff (config unchanged since baseline).

# Commands and outcomes (shell 4ac69adc, sequential)

| # | Command | Outcome |
| --- | --- | --- |
| 1 | `./gradlew.bat :app:assembleDevDebug --console=plain` | BUILD SUCCESSFUL in 4m 11s (273 tasks: 16 executed, 1 from cache, 256 up-to-date) |
| 2 | `./gradlew.bat :app:assembleStandardDebug --console=plain` | Green: task executed 17:28:34, zero failure indicators anywhere between invocations, next invocation started cleanly 17:28:41. Its completion tail line is not separately retained in the merged per-stream log (interleave artifact); no failure markers. |
| 3 | `./gradlew.bat :app:testDevDebugUnitTest --rerun --console=plain --stacktrace` | `2234 tests completed, 4 failed, 1 skipped`; BUILD FAILED in 4m 35s — expected: ledgered failures stayed visible; task ran to completion, nothing retried |
| 4 | `./gradlew.bat :app:testStandardDebugUnitTest --rerun --console=plain --stacktrace` | `2234 tests completed, 5 failed, 1 skipped`; BUILD FAILED in 3m 26s — same treatment |
| 5 | `./gradlew.bat :domain:testDebugUnitTest --rerun --console=plain --stacktrace` | BUILD SUCCESSFUL in 8s; 66 tests / 0 failures |
| 6 | `./gradlew.bat :app:spotlessCheck --console=plain` | BUILD FAILED in 23s — `spotlessKotlinCheck` format violations, 4 files (below) |

XML preservation note: the in-shell `cp` steps failed (destination parent not pre-created); corrected afterward by copying the per-task Gradle output directories, which no later command modified (per-flavor dirs; spotless writes no test-results). XML mtimes prove run correspondence: dev batch 17:33:09 (task window ended 17:33:10), standard batch 17:36:37 (ended 17:36:38), domain 17:36:46. No task was re-run for this correction.

# Counts vs forecast (actual XML authoritative)

| Suite | Forecast | Actual | Verdict |
| --- | --- | --- | --- |
| app per flavor | 329 classes / 2234 tests | **329 classes / 2234 tests / 1 skipped** (both flavors) | exact match |
| domain | 66 tests | 66 tests / 6 classes / 0 failures | exact match |
| app classes | 332 − 3 retired = 329 | 329 | exact match |

Delta 2261→2234 = −27 = −4 orphaned −22 rendering-island +1 repaired-timestamp-suite +7 production-replacement tests −2 mirrors −7 adaptive (forecast line reconciled with zero divergence). Retired classes: `InpaintingProviderRecoveryTest`, `AdaptiveBandPlannerTest`, `MaskTextRegionPlannerTest`; `AdaptiveKnobControllerTest`→`DevicePagePermitGateTest` rename keeps class count. The 1 skipped per flavor matches baseline count (parity at count level; not claimed to be the same method). Translation suite class (`TranslationDebugMeasurementTest`) now 3 tests incl. the P1.3a-added passthrough test — all pass both flavors.

# Failure comparison vs Phase 0 ledger (literal class/method/type/message)

**Tracked failures — byte-identical literal signature in all four sets (P0 dev, P0 standard, P1.5 dev, P1.5 standard)** — verified programmatically (type+message equality; full stack-trace text differs only in dynamic frames, e.g. 19210–19214 chars, incidental):

1. `ChapterTranslationStoreArtifactOpenProbeTest.lazy store cuts over before first candidate and reopens from artifact pointers()` — `java.lang.AssertionError` — `Expected "page.cleaned.retry.jpg" but actual was null`
2. `…ArtifactOpenProbeTest.pre-registered pages publish one durable baseline with only the changed candidate()` — `io.kotest.assertions.AssertionFailedError` — `expected:<["p1.jpg", "p2.jpg", "p3.jpg"]> but was:<[]>`
3. `…ArtifactOpenProbeTest.artifact-only lazy store writes pages without materializing flat compatibility file()` — `org.junit.platform.commons.JUnitException` — `Failed to close extension context`
4. `…ArtifactOpenProbeTest.lazy store without parent resolves artifact authority through artifactParentResolver()` — `org.junit.platform.commons.JUnitException` — `Failed to close extension context`

**Repaired (P1.3a, test-only change)**: `TranslationDebugMeasurementTest.file sink preserves queue order and sanitizes embedded line breaks` — FAILED at P0 both flavors (`AssertionFailedError: Element differ at index: [0, 1, 2]`) → **PASSES P1.5 both flavors**. Same-signature resolution as designed; no production code touched by that repair.

**Observed passes, no fix claimed (class untouched)**: `ProfileEnvelopeDispatchTest.protocol outcome commits fully covered pages and parks omitted block pages without pausing` failed at P0 dev full run (`kotlinx.coroutines.test.UncompletedCoroutinesError`) → **passes P1.5 dev full run**. Class has 20 tests in both P0 and P1.5; untouched by Phase 1 (not in the 22-path manifest).

**Observed outcome change — flagged for disposition, not classified away**: P1.5 **standard** full run: `ProfileEnvelopeDispatchTest.N2 normal AI batch makes zero analysis transport calls` FAILED — `org.junit.platform.commons.JUnitException` / `Failed to close extension context`. History of this exact method: PASS at P0 dev full, P0 standard full, P0 isolated triage (both flavors); PASS at P1.5 dev full. Facts for review: (a) same type+message as ledgered failures #3/#4 — a known extension-cleanup failure family in the baseline ledger, here on a different class; (b) the class showed run-to-run instability at P0 already (dev full failed, standard full and isolated passed); (c) class and all 20 tests are untouched by Phase 1 (not in the manifest); (d) no isolated re-run was performed — the no-retry bound was respected. Per the ticket, Phase 1 is not closed before this observation's disposition.

**No other new failures**: dev full run has exactly the 4 tracked failures; standard has those 4 + the N2 observation; domain 0.

# Spotless findings (read-only; no formatting applied)

| File | Violation | Provenance |
| --- | --- | --- |
| `src/main/java/…/engines/inpainting/litert/LiteRTMangaInpaintingEngine.kt` | 2 line-wrapping hunks (@160 `npuAttempted =` continuation; @357 `targetFile.exists()` condition) | **Pre-existing at baseline**: file not in the 22-path diff; violation text verbatim at `4de121bf` (verified); unrelated to Phase 1 |
| `src/main/java/…/engines/rendering/CollisionRelaxation.kt` | unused import `eu.kanade.translation.model.TranslationBlockView` (line 3) | **Cleanup-introduced** (P1.2 removed its usages, import left). Fix request: drop line 3 |
| `src/test/java/…/coexistence/BatchParallelWindowBudgetTest.kt` | stray blank line before final brace (EOF) | **Cleanup-introduced** (P1.3b mirror deletion). Fix request: drop blank line |
| `src/test/java/…/workflow/ChapterTranslatorQueueRestoreTest.kt` | import order: added `tachiyomi.domain.*` imports placed after `java.*` | **Cleanup-introduced** (P1.3b additions). Fix request: reorder imports above the `java.*` block |

Three cleanup-introduced violations are returned as meaningful fix requests for coordinator decision; nothing was modified in this ticket.

# Cumulative source accounting (4de121bf→b44143f2)

- `git diff --numstat`: 22 paths, **+589 / −4748** (net −4159); `--name-status`: 10 D / 1 A / 11 M.
- Raw-vs-semantic: git replacement detection shows `AdaptiveKnobControllerTest` deleted + `DevicePagePermitGateTest` added; **semantic retirement = 6 main files** (InpaintingProviderRecovery, AdaptiveBandPlanner, MaskTextRegionPlanner, AdaptiveKnobController, AdaptiveKnobSignalAdapter, BatchResumePolicy) **+ 3 unit files** (InpaintingProviderRecoveryTest, AdaptiveBandPlannerTest, MaskTextRegionPlannerTest) **+ 1 mixed unit suite rename** with zero count impact. 11 modified files. No newly introduced production files (the only added path is the renamed test suite).
- Source counts: main 290→284 (282 `.kt` + 2 `.java`), ordinary translation unit 318→315, `testDebug` 1 and `androidTest` 6 unchanged.

# Preservation gates honored

No source/test/config modification (tree still clean except the report); no commits/pushes; no subagents; no device/model/provider/native operations; no toolchain changes; no clean; Phase 0 XML/logs and the user report byte-untouched; stale doc-only comment `TranslationCoexistenceHarness.kt:224` recorded for a later docs-cleanup slice; no isolated re-run of N2 (no-retry bound).

# Consolidation verdict (plain statement)

Consolidation passes as **no new unexplained regression while known baseline failures remain**: the 4 ArtifactOpenProbe failures are literal-identical to the ledger, the timestamp failure is repaired by its reviewed test-only slice, one prior full-run flake passes, and one changed observed outcome (N2 on standard, extension-close family) is flagged for review disposition rather than silently folded into "known failures". No stability, storage-ownership, or native-cancellation claims are made; device performance/JNI parity remains deferred; runtime store-contract/handle-owner discrimination remains required before Phase 3/4.

# Evidence paths (epic 699de408-00ac-4fe5-a903-b541c23063cc)

- Preserved XML: `scratch/phase-1-cleanup/consolidated-validation/full-suite/{app-devdebug-raw,app-standarddebug-raw,domain-raw}/` (329+329+6 XML)
- Full command log: `commands/699de408-00ac-4fe5-a903-b541c23063cc/4ac69adc-937a-48b4-b25f-bdb127a75302/output.log`
- Baseline comparators (immutable): `scratch/phase-0-baseline/fresh-run-test-results/2026-10-14T12-26-30Z/` (full-suite ledger), `…/narrow-triage-test-results/2026-10-14T12-43-14Z/` (isolated), `…/pre-existing-test-results/2026-10-14T11-56-55Z/`
- Ticket: `artifacts/phase-1-cleanup/consolidated-validation/index.md`

# Next

Slice complete; stopping for independent Gemini/Antigravity evidence review of the actual XML/logs and this account. No publication, no P1.6, no Phase-1 closure until final review and the N2 disposition.
