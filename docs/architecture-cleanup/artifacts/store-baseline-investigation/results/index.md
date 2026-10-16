---
title: "Store artifact test failure investigation results"
kind: ticket
status: 0
assignee: ""
comments: none
---

# Result

The four `ChapterTranslationStoreArtifactOpenProbeTest` baseline failures are reproduced by a single test-fixture resource leak: the shared `readManifest()` helper passes `File.inputStream()` directly to `Json.decodeFromStream` without closing it. A helper-only diagnostic change that scopes that stream with `use` preserved every test body, assertion, and store-lifecycle omission; it changed the original class from 14 tests / 4 failures to 14 / 0 in both DevDebug and StandardDebug. This is strong, bounded evidence that the leaked manifest reader streams are sufficient to explain these four failures in this test fixture. No production store change is supported by these results.

The test source has been restored byte-for-byte to the published HEAD. The helper-only change is preserved for review but was not left in the workspace.

# Per-failure evidence

| Baseline failure | Existing test interaction with `readManifest()` | Baseline outcome | Helper-only control outcome |
| --- | --- | --- | --- |
| `lazy store cuts over before first candidate and reopens from artifact pointers()` | Reads the session-one candidate manifest before session-two `updatePage`, then reads the committed manifest at the end. An unclosed reader can block the later manifest replacement. | Expected `page.cleaned.retry.jpg`, actual `null`; XML also contains a `FileSystemException` naming `Chapter 2.manifest.json` (“being used by another process”). | Passed in both flavors with only the helper stream lifetime changed. The corrected diagnostic twin observed session-two commit persisted before the final reopen; reopened display contained `page.cleaned.retry.jpg`. |
| `pre-registered pages publish one durable baseline with only the changed candidate()` | Reads the pre-registration manifest twice before the first page mutation, then reads it again after `flush()`. | Expected page keys `{p1,p2,p3}`, actual `{}`; XML includes manifest replacement `FileSystemException` causes. | Passed in both flavors. Corrected diagnostic snapshots showed registration state before mutation, then all three manifest page entries after the Unit-returning wrapper update, with only `p1` carrying a candidate. |
| `artifact-only lazy store writes pages without materializing flat compatibility file()` | Reads the manifest twice after `flush()` immediately before TempDir teardown. | JUnit extension cleanup failed deleting `Chapter 2.manifest.json`; XML includes the Windows “being used by another process” exception. | Passed in both flavors with the helper-only stream closure; the original store remained unclosed exactly as in the baseline test. |
| `lazy store without parent resolves artifact authority through artifactParentResolver()` | Reads the manifest twice after `flush()` before TempDir teardown. | JUnit extension cleanup failed deleting `Chapter 8.manifest.json`; XML includes the same Windows exception. | Passed in both flavors with the helper-only stream closure; the original store remained unclosed exactly as in the baseline test. |

The helper-only control made no changes to test bodies, assertions, `closeAndFlush()` calls, or fixture setup. Its saved patch contains only the `readManifest()` resource-scope change.

# Corrected diagnostic observations

A first instrumentation run (v1) was invalid for causal interpretation: `recordStoreProbe()` reused the original unclosed `readManifest()` helper. The v1 sampler therefore could itself retain manifest handles and reject subsequent publications. Its 19-test / 9-failure outcome and all raw observations are preserved as a probe false start; they are not used to characterize store behavior.

The corrected v2 sampler uses a separate `readManifestForProbe()` with `inputStream().use { ... }`. It copies the store's mutable registration and write-drain collections briefly under the existing `store.mutex`, then releases that mutex before disk reads, Restart Manager, or output. Each observation records sequential cross-layer samples, not an atomic snapshot.

Both v2 flavor runs passed all five diagnostic methods (5 / 0 / 0). Their material observations matched:

- Immediately after `preRegisterPages(p1,p2,p3)`, live/display contained three pending pages, the manifest had the expected count marked trusted but no page records, and the in-memory pending-registration set contained all three keys. This is the observed pre-registration contract: placeholders and registration are prepared before page records are published.
- After the real Unit-returning `updatePage("p1.jpg")` wrapper with a RUNNING, zero-block page, live state reflected the update; the disk/memory manifest contained all three page entries, with a candidate only for `p1`. There was no publication rejection. The content-bearing control produced the same registration/candidate shape. This demonstrates the wrapper path progressing to manifest publication under the corrected fixture; it does not redefine the separate guarded `PatchResult` API.
- In the promotion twin, session one closed with a candidate and no committed pointer. Session two’s update reached a committed pointer by the post-wrapper/post-flush samples. The manifest read after session-two close and before final reopen already contained that committed pointer; the final reopened display contained `page.cleaned.retry.jpg`. This isolates the successful artifact promotion path from reopen projection when the reader stream is scoped.
- Cleanup twins for the artifact-only and resolver shapes completed their existing TempDir teardown. Their pre-close Restart Manager queries returned `status=ok` with no listed processes. Per the accepted protocol, no post-close query was made after an empty result. This and the passes do not identify a particular handle or prove general store-resource ownership.
- Across sampled corrected twins, pending lazy task counts were zero at the recorded checkpoints; no active drain or retention children were observed, `privateStorageRoot` was null, and no journal writer was present. These are observations at the recorded times, not proof that future asynchronous work can never be scheduled.

# Validation ledger

All runs used the same branch and HEAD: `architecture-cleanup`, `999bb9be6563f957ee508f739ba2f042bf1c90ee`. Commands ran serially, with separate scratch logs and XML copied immediately after each invocation. A full app suite was not run.

| Run | Traycer shell ID | Exit | XML outcome | Retained evidence |
| --- | --- | --- | --- | --- |
| Original baseline DevDebug | `4780e208-f9e5-474c-acfe-b629c56cc7bf` | 1 | 14 tests, 4 failures, 0 errors | `scratch/store-baseline-investigation/2026-10-15-999bb9be/baseline/dev/{xml,log}` |
| Original baseline StandardDebug | `7d84d781-7b3f-4a51-82ab-d68ee1ed7843` | 1 | 14 tests, 4 failures, 0 errors | `scratch/store-baseline-investigation/2026-10-15-999bb9be/baseline/standard/{xml,log}` |
| Diagnostic v1, DevDebug full class | `bb465ff4-b26d-4576-98ec-6bb5f1f3add6` | 1 | 19 tests, 9 failures, 0 errors; probe false start | `scratch/store-baseline-investigation/2026-10-15-999bb9be/diagnostics/2026-10-15-instrumented-v1/dev/{xml,log,raw}` |
| Diagnostic v2, DevDebug five method selectors | `91d15e34-7afc-4f3d-a042-ed0a62987fbe` | 0 | 5 tests, 0 failures, 0 errors | `scratch/store-baseline-investigation/2026-10-15-999bb9be/diagnostics/2026-10-15-instrumented-v2/dev/{xml,log,raw}` |
| Diagnostic v2, StandardDebug five method selectors | `b128bf87-4dbd-4026-85a4-5228fc405017` | 0 | 5 tests, 0 failures, 0 errors | `scratch/store-baseline-investigation/2026-10-15-999bb9be/diagnostics/2026-10-15-instrumented-v2/standard/{xml,log,raw}` |
| Helper-only control, DevDebug original class | `d3276925-ba64-41ea-8224-14339f7b01ce` | 0 | 14 tests, 0 failures, 0 errors | `scratch/store-baseline-investigation/2026-10-15-999bb9be/diagnostics/2026-10-15-helper-only/dev/{xml,log}` |
| Helper-only control, StandardDebug original class | `6d096164-0cf7-4f07-803e-170cf55f1263` | 0 | 14 tests, 0 failures, 0 errors | `scratch/store-baseline-investigation/2026-10-15-999bb9be/diagnostics/2026-10-15-helper-only/standard/{xml,log}` |

Literal commands:

```bash
# Original baseline, run once for each task flavor
./gradlew.bat :app:testDevDebugUnitTest --tests "eu.kanade.translation.persistence.chapter.ChapterTranslationStoreArtifactOpenProbeTest" --rerun --console=plain --stacktrace
./gradlew.bat :app:testStandardDebugUnitTest --tests "eu.kanade.translation.persistence.chapter.ChapterTranslationStoreArtifactOpenProbeTest" --rerun --console=plain --stacktrace

# v1 diagnostic false start (DevDebug; same class plus five appended diagnostic tests)
run_base='C:/Users/ADMIN/.traycer/epics/699de408-00ac-4fe5-a903-b541c23063cc/scratch/store-baseline-investigation/2026-10-15-999bb9be/diagnostics/2026-10-15-instrumented-v1/dev'
export STORE_BASELINE_PROBE_DIR="$run_base/raw"
export STORE_BASELINE_RM_HELPER='C:/Users/ADMIN/.traycer/epics/699de408-00ac-4fe5-a903-b541c23063cc/scratch/store-baseline-investigation/2026-10-15-999bb9be/diagnostics/2026-10-15-instrumented-v1/helper/query-manifest-owner.ps1'
./gradlew.bat :app:testDevDebugUnitTest --tests "eu.kanade.translation.persistence.chapter.ChapterTranslationStoreArtifactOpenProbeTest" --rerun --console=plain --stacktrace

# v2 diagnostic twins; run the following selector set once per flavor
run_base='C:/Users/ADMIN/.traycer/epics/699de408-00ac-4fe5-a903-b541c23063cc/scratch/store-baseline-investigation/2026-10-15-999bb9be/diagnostics/2026-10-15-instrumented-v2/dev'
export STORE_BASELINE_PROBE_DIR="$run_base/raw"
export STORE_BASELINE_RM_HELPER='C:/Users/ADMIN/.traycer/epics/699de408-00ac-4fe5-a903-b541c23063cc/scratch/store-baseline-investigation/2026-10-15-999bb9be/diagnostics/2026-10-15-instrumented-v1/helper/query-manifest-owner.ps1'
./gradlew.bat :app:testDevDebugUnitTest --tests "eu.kanade.translation.persistence.chapter.ChapterTranslationStoreArtifactOpenProbeTest.diagnosticPreregisterRunningWrapperPath" --tests "eu.kanade.translation.persistence.chapter.ChapterTranslationStoreArtifactOpenProbeTest.diagnosticPreregisterDurableContentControl" --tests "eu.kanade.translation.persistence.chapter.ChapterTranslationStoreArtifactOpenProbeTest.diagnosticPromotionAndReopenWithAllStoresClosed" --tests "eu.kanade.translation.persistence.chapter.ChapterTranslationStoreArtifactOpenProbeTest.diagnosticArtifactOnlyCleanupWithClose" --tests "eu.kanade.translation.persistence.chapter.ChapterTranslationStoreArtifactOpenProbeTest.diagnosticResolverCleanupWithClose" --rerun --console=plain --stacktrace
run_base='C:/Users/ADMIN/.traycer/epics/699de408-00ac-4fe5-a903-b541c23063cc/scratch/store-baseline-investigation/2026-10-15-999bb9be/diagnostics/2026-10-15-instrumented-v2/standard'
export STORE_BASELINE_PROBE_DIR="$run_base/raw"
export STORE_BASELINE_RM_HELPER='C:/Users/ADMIN/.traycer/epics/699de408-00ac-4fe5-a903-b541c23063cc/scratch/store-baseline-investigation/2026-10-15-999bb9be/diagnostics/2026-10-15-instrumented-v1/helper/query-manifest-owner.ps1'
./gradlew.bat :app:testStandardDebugUnitTest --tests "eu.kanade.translation.persistence.chapter.ChapterTranslationStoreArtifactOpenProbeTest.diagnosticPreregisterRunningWrapperPath" --tests "eu.kanade.translation.persistence.chapter.ChapterTranslationStoreArtifactOpenProbeTest.diagnosticPreregisterDurableContentControl" --tests "eu.kanade.translation.persistence.chapter.ChapterTranslationStoreArtifactOpenProbeTest.diagnosticPromotionAndReopenWithAllStoresClosed" --tests "eu.kanade.translation.persistence.chapter.ChapterTranslationStoreArtifactOpenProbeTest.diagnosticArtifactOnlyCleanupWithClose" --tests "eu.kanade.translation.persistence.chapter.ChapterTranslationStoreArtifactOpenProbeTest.diagnosticResolverCleanupWithClose" --rerun --console=plain --stacktrace

# Helper-only control, run once for each task flavor
unset STORE_BASELINE_PROBE_DIR STORE_BASELINE_RM_HELPER
./gradlew.bat :app:testDevDebugUnitTest --tests "eu.kanade.translation.persistence.chapter.ChapterTranslationStoreArtifactOpenProbeTest" --rerun --console=plain --stacktrace
./gradlew.bat :app:testStandardDebugUnitTest --tests "eu.kanade.translation.persistence.chapter.ChapterTranslationStoreArtifactOpenProbeTest" --rerun --console=plain --stacktrace
```

The environment assignments above are the exact paths passed to each run. Each registered Git Bash wrapper also enabled `pipefail`, teed complete output to the listed run’s `log/gradle.log`, immediately copied the task XML to its `xml/` directory, appended `gradle_exit=<code>`, and returned that exit. V1 used the original unscoped `readManifest()` in its sampler; v2 used `readManifestForProbe()` with `.use`. Helper-only commands explicitly unset the diagnostic variables.

# Evidence fingerprints and restoration

Scratch root: `C:\Users\ADMIN\.traycer\epics\699de408-00ac-4fe5-a903-b541c23063cc\scratch\store-baseline-investigation\2026-10-15-999bb9be`.

- Original baseline XML SHA-256: DevDebug `EB0F1E8104D4036DF70B8B57D0FC0A040B0454863812A5918E991EE5AA9C9A75`; StandardDebug `618C1ED55FDC3DACE56962C016F85E2FCC5AA2AB519FE45CE9FF2E9BE32C9994`.
- v1 diagnostic XML SHA-256: `35B58BDF26F039EC2DE4CFDBA9C6034250ED93066400E5DB606012F276C84935`. Modified source `A4A5D8EBCAE834045D0A896D570CAF2C0816BD74C8DA871A2BED052A0E5A9298`; patch `0230717BF1AB1998F15BC3A79510AAA996BE610383AEEFD975E63036378922A8`.
- v2 diagnostic XML SHA-256: DevDebug `200E791501AF31C4768D0835FF4647E497C70263F85C27D46F5311F612FE3E5B`; StandardDebug `0A65381BF81BE1CC542532AF6525458555C428061CDFF6C1AABFFBCBE6BD76B5`. Modified source `1641F865DC220D85AD6718C4CE5C0CB000490B7973AF0E7BD7839AB098B62BBD`; patch `36F9D0A63172BEECA2BFC80F8697880EE51014AF4AA7C8B6F0EECEB584687D15`. Each flavor has 21 raw observation files under its `raw/` directory.
- Helper-only XML SHA-256: DevDebug `90183F6D0AB97FB0397DD06134BF23209DE7E93183BC5392624EA411ABD3805E`; StandardDebug `C21CC94DDC09F3D18BD5EEB0B98C11F06D7778C76605143C29934F38B516B74B`. Original source `33E01BB37B12019D67E990581EFB27E614364259F579A6510D9DC89185B9E541`; helper-only source `FAA79D2C72D1B5F7086649C2DF019429E5D45F87FDFB507D71A0644920F54F57`; patch `9A78C6EDC51630613E458A4D98DADE2F778CB84F40B08FF5613C1C39AAAFD6E4`.
- The scratch Restart Manager helper is `diagnostics/2026-10-15-instrumented-v1/helper/query-manifest-owner.ps1`, SHA-256 `9399CE6F53977DBA745DCB0578C553CCF4E8CC1E8DAA3E26F367950E9CAB8E17`. It only registers and queries a path; it never signals reported processes.
- Restored test source SHA-256: `33E01BB37B12019D67E990581EFB27E614364259F579A6510D9DC89185B9E541`, matching the original at HEAD. Production `ChapterTranslationStore.kt` remained unchanged at SHA-256 `70C67D83FEB15BCD8F08F52597A6F75786C717DD470040F0B81162D4D40BEF59`. The untracked architecture report remains unchanged at SHA-256 `EFC11B90A7D0B367F3878BD600BF893AA99ECCDB5879D902E10EB6E06EF88DDD`.
- `git diff --check`, `git diff --numstat`, and `git diff --cached --stat` were empty after restoration. The eleven previously verified status-M paths remain untouched with empty content diffs; no source edit remains staged or unstaged, and no commit or push was made.

# Interpretation and next action

The most economical repair is to scope the shared test-fixture manifest input stream with `use`, then accept/rebaseline the four focused regressions in the normal review process. That correction is supported by the helper-only A/B result and is not applied here. The accepted investigation constraints prohibited a permanent fix in this diagnostic pass.

Windows Restart Manager reports process/resource association only. The v1 Java PID was the diagnostic test process, but because v1 itself used the unclosed reader, it does not identify the owning handle or prove a production store leak. Corrected v2 queries returned no affected process at their pre-close checkpoints; post-close queries were intentionally skipped per protocol. The closed twins passing alone do not identify a holder. This evidence is specific to these four ProbeTest failures and does not establish general production handle safety, device/native behavior, or storage-decomposition readiness.

The investigation plan and its accepted causal-safety constraints are documented in [the plan](../investigation-plan) and [the critique](../investigation-plan/review). Independent parent/Gemini review remains the next disposition step.

# Final parent disposition

Independent evidence review accepted the bounded fixture-reader finding. Investigation complete; root-cause prerequisite grounded for these four failures, test-helper repair/rebaseline pending. Original source restored, published HEAD unchanged. Generic production handle safety and native/device behavior remain outside the evidence. See the [investigation disposition](..) and [review](../review).
