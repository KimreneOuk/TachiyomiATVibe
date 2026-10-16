---
title: "Store baseline investigation evidence review and causal assessment"
kind: review
comments: none
---

# Review Verdict: ACCEPTED WITH BOUNDED SCOPE

The diagnostic evidence, raw telemetry, and helper-only control runs for the store baseline investigation are **ACCEPTED**. The investigation has conclusively and rigorously isolated the root cause of the four persistent failures in `ChapterTranslationStoreArtifactOpenProbeTest.kt` under both `DevDebug` and `StandardDebug` variants.

The four failures are completely explained by a single, reproducible test-fixture resource leak: the shared test helper `readManifest()` opened an unclosed `FileInputStream` on `Chapter {2,8}.manifest.json` via `File.inputStream()` without `.use { ... }`. When scoped with `.use`, all 14 tests in the class pass cleanly (14/14, 0 failures, 0 errors) in both build variants, with zero changes to any test body, assertion, store lifecycle call, or production code.

### Core Findings & Boundary Ledger

1. **No production store repair is warranted or supported for these observed failures**: No production store changes are supported by the evidence. General production store handle correctness under arbitrary production workloads is not generally proven by this test-scoped investigation.
2. **Causal attribution is strictly bounded**: Proving that the test-fixture reader caused these four specific failures establishes fixture-reader sufficiency for the observed regressions; it does **not** constitute proof of generic store handle safety, does not prove that background asynchronous jobs (`drainJob`, `retentionScopeJob`) can never leak or race under device workloads, and does not certify storage-decomposition readiness.
3. **Investigation intervention removed; fix not yet landed**: The test source has been restored byte-for-byte to published HEAD (`33E01BB37B12...`). The published test suite therefore still fails until the focused helper fix is landed and rebaselined under normal review.

# Verification and Provenance Ledger

Every file, hash, and command executed by Luna has been independently audited and verified against the workspace and scratch trees.

### 1. Workspace State and Source Restoration

- **Branch**: `architecture-cleanup` at HEAD `999bb9be6563f957ee508f739ba2f042bf1c90ee` (`refactor: suspend batch source fingerprint preparation`).
- **Restored Test Source**: `app/src/test/java/eu/kanade/translation/persistence/chapter/ChapterTranslationStoreArtifactOpenProbeTest.kt`
  - SHA-256: `33E01BB37B12019D67E990581EFB27E614364259F579A6510D9DC89185B9E541` (exact byte-for-byte match with repository HEAD).
- **Production Store Source**: `app/src/main/java/eu/kanade/translation/persistence/chapter/ChapterTranslationStore.kt`
  - SHA-256: `70C67D83FEB15BCD8F08F52597A6F75786C717DD470040F0B81162D4D40BEF59` (unmodified).
- **Architecture Report**: `TachiyomiATVibe_Translation_Simplicity_First_Architecture_Report.md`
  - SHA-256: `EFC11B90A7D0B367F3878BD600BF893AA99ECCDB5879D902E10EB6E06EF88DDD` (untracked, unmodified).
- **Working Tree Hygiene**: `git diff` produces 0 content diffs across all 11 modified paths (empty CRLF/stat diffs preserved); zero staged changes; zero commits or pushes created.

### 2. Scratch Artifact Fingerprints

Root: `C:\Users\ADMIN\.traycer\epics\699de408-00ac-4fe5-a903-b541c23063cc\scratch\store-baseline-investigation\2026-10-15-999bb9be`

| Artifact | Path | Verified SHA-256 |
| --- | --- | --- |
| Baseline Dev XML | `baseline/dev/xml/TEST-...xml` | `EB0F1E8104D4036DF70B8B57D0FC0A040B0454863812A5918E991EE5AA9C9A75` |
| Baseline Standard XML | `baseline/standard/xml/TEST-...xml` | `618C1ED55FDC3DACE56962C016F85E2FCC5AA2AB519FE45CE9FF2E9BE32C9994` |
| Diagnostic v1 Dev XML | `diagnostics/2026-10-15-instrumented-v1/dev/xml/TEST-...xml` | `35B58BDF26F039EC2DE4CFDBA9C6034250ED93066400E5DB606012F276C84935` |
| Diagnostic v1 Source | `diagnostics/2026-10-15-instrumented-v1/source/Chapter...modified.kt` | `A4A5D8EBCAE834045D0A896D570CAF2C0816BD74C8DA871A2BED052A0E5A9298` |
| Diagnostic v1 Patch | `diagnostics/2026-10-15-instrumented-v1/source/diagnostic.patch` | `0230717BF1AB1998F15BC3A79510AAA996BE610383AEEFD975E63036378922A8` |
| Diagnostic v1 RM Script | `diagnostics/2026-10-15-instrumented-v1/helper/query-manifest-owner.ps1` | `9399CE6F53977DBA745DCB0578C553CCF4E8CC1E8DAA3E26F367950E9CAB8E17` |
| Diagnostic v2 Dev XML | `diagnostics/2026-10-15-instrumented-v2/dev/xml/TEST-...xml` | `200E791501AF31C4768D0835FF4647E497C70263F85C27D46F5311F612FE3E5B` |
| Diagnostic v2 Standard XML | `diagnostics/2026-10-15-instrumented-v2/standard/xml/TEST-...xml` | `0A65381BF81BE1CC542532AF6525458555C428061CDFF6C1AABFFBCBE6BD76B5` |
| Diagnostic v2 Source | `diagnostics/2026-10-15-instrumented-v2/source/Chapter...modified.kt` | `1641F865DC220D85AD6718C4CE5C0CB000490B7973AF0E7BD7839AB098B62BBD` |
| Diagnostic v2 Patch | `diagnostics/2026-10-15-instrumented-v2/source/diagnostic.patch` | `36F9D0A63172BEECA2BFC80F8697880EE51014AF4AA7C8B6F0EECEB584687D15` |
| Helper-Only Dev XML | `diagnostics/2026-10-15-helper-only/dev/xml/TEST-...xml` | `90183F6D0AB97FB0397DD06134BF23209DE7E93183BC5392624EA411ABD3805E` |
| Helper-Only Standard XML | `diagnostics/2026-10-15-helper-only/standard/xml/TEST-...xml` | `C21CC94DDC09F3D18BD5EEB0B98C11F06D7778C76605143C29934F38B516B74B` |
| Helper-Only Original Source | `diagnostics/2026-10-15-helper-only/source/Chapter...original.kt` | `33E01BB37B12019D67E990581EFB27E614364259F579A6510D9DC89185B9E541` |
| Helper-Only Modified Source | `diagnostics/2026-10-15-helper-only/source/Chapter...helper-only.kt` | `FAA79D2C72D1B5F7086649C2DF019429E5D45F87FDFB507D71A0644920F54F57` |
| Helper-Only Patch | `diagnostics/2026-10-15-helper-only/source/helper-only.patch` | `9A78C6EDC51630613E458A4D98DADE2F778CB84F40B08FF5613C1C39AAAFD6E4` |

# Causal Assessment: The Helper-Only A/B Control

The pivotal finding of this investigation is established by `diagnostics/2026-10-15-helper-only/source/helper-only.patch`.

Inspection of this patch confirms it modifies **only one helper function** and changes **no test bodies, assertions, lifecycle calls, or setup**:

```kotlin
@@ -180,9 +180,9 @@ class ChapterTranslationStoreArtifactOpenProbeTest {
         )

     private fun readManifest(chapterName: String = "Chapter 1"): ChapterArtifactManifest =
-        Json.decodeFromStream<ChapterArtifactManifest>(
-            File(mangaDir, "$chapterName.manifest.json").inputStream(),
-        )
+        File(mangaDir, "$chapterName.manifest.json").inputStream().use { input ->
+            Json.decodeFromStream<ChapterArtifactManifest>(input)
+        }
```

### Analysis of the 4 Baseline Failures Under the A/B Control

*(Note: The exact Windows manifest-replacement failure chains described below represent strongly supported mechanism/source inferences grounded in the read-before-write sequence, the Windows sharing-violation exception traces, and the helper-only A/B control, rather than direct per-handle OS ownership tracing).*

| Test Case | Baseline Failure Mode | Windows Causal Mechanism (Supported Source Inference) | Helper-Only Control Outcome |
| --- | --- | --- | --- |
| **Test #1** (`lazy store cuts over before first candidate and reopens from artifact pointers`) | Assertion failed: expected `page.cleaned.retry.jpg`, actual `null`. Suppressed `FileSystemException` on `Chapter 2.manifest.json`. | Session 1 reads candidate manifest before Session 2 mutation. The unclosed `readManifest` locks `Chapter 2.manifest.json`. When Session 2 attempts atomic manifest replacement, Windows throws a sharing violation. Session 2 commit never reaches disk, leaving reopen with no committed pointer. | **PASS** (0.089s Dev / 0.097s Standard). With the stream scoped, Session 2 replaces the manifest cleanly; reopened store displays `page.cleaned.retry.jpg`. |
| **Test #2** (`pre-registered pages publish one durable baseline with only the changed candidate`) | Assertion failed: expected `{p1.jpg, p2.jpg, p3.jpg}`, actual `{}`. Underlying `FileSystemException` on manifest replacement. | Pre-registration assertions call `readManifest("Chapter 2")` twice before the first mutation. When `updatePage("p1.jpg")` flushes the batch baseline, manifest replacement fails due to the open reader handle. Disk manifest remains unpopulated `{}`. | **PASS** (0.033s Dev / 0.040s Standard). Wrapper mutation publishes all 3 registered keys with candidate on `p1.jpg`. |
| **Test #3** (`artifact-only lazy store writes pages without materializing flat compatibility file`) | `@TempDir` cleanup failure: `FileSystemException: The process cannot access the file because it is being used by another process` (`Chapter 2.manifest.json`). | Test calls `readManifest("Chapter 2")` twice after `store.flush()`. The unclosed stream prevents JUnit extension from deleting the temporary directory on teardown. | **PASS** (0.029s Dev / 0.029s Standard). `@TempDir` deletes cleanly even though `store` instance was not closed before exit. |
| **Test #4** (`lazy store without parent resolves artifact authority through artifactParentResolver`) | `@TempDir` cleanup failure: `FileSystemException` on `Chapter 8.manifest.json`. | Test calls `readManifest("Chapter 8")` twice after `store.flush()`. The unclosed stream prevents JUnit extension from deleting the temporary directory on teardown. | **PASS** (0.032s Dev / 0.029s Standard). `@TempDir` deletes cleanly even though `store` instance was not closed before exit. |

### Crucial Store Lifecycle Observation

In baseline tests #3 and #4, as well as in test #1 (line 557 where `val promoted = ChapterTranslationStore.openArtifact(...)` is opened), the test author omitted store closure before test completion.

In these observed helper-only runs, leaving those store instances unclosed did not prevent cleanup; closing only the fixture reader removed all four failures, supporting fixture-reader sufficiency without excluding other holders in other executions.

# Diagnostic v1 vs. v2: Measurement Contamination Isolated

### 1. The Diagnostic v1 False Start

During the initial diagnostic run (v1), the probe instrumentation sampler `recordStoreProbe()` inadvertently reused the original unclosed `readManifest()` helper. This resulted in:

- 19 tests executed, 9 failures (the original 4 failures + 5 new cleanup failures on the diagnostic twin tests).
- Windows Restart Manager reported the Gradle `TestWorker` process (e.g. PID `22988`).
- **Review assessment**: As identified by Luna and the coordinator, v1 was **measurement-contaminated**. The sampler itself contaminated downstream publications and directory teardown. The RM process association in v1 did not identify a production leak; it identified the test process holding streams leaked by the measurement tool itself.

### 2. The Corrected Diagnostic v2 Observations

In Diagnostic v2, probe sampling was decoupled into `readManifestForProbe()` with strict `.use { ... }` scoping. Mutable store collections (`pendingRegistrations`, `pendingLazyMutations`) were briefly copied under `store.mutex`, which was released before any disk read, RM query, or serialization.

Results from v2 (5/5 passed across both flavors):

- **Sequential cross-layer sampling**: Raw probe logs confirm that observations represent sequential snapshots across layers rather than an artificial global lock.
- **Pre-registration contract verified**: Probe `registration-running-after-preregister.txt` confirmed that `preRegisterPages` registers placeholders and pending keys (`pendingRegistrations=p1.jpg,p2.jpg,p3.jpg`), leaving `memoryManifest.pages={}` until mutation admission.
- **Wrapper mutation admission verified**: Probe `registration-running-after-wrapper.txt` confirmed that calling `updatePage("p1.jpg")` with a `RUNNING` page admitted the update without rejection (`lastArtifactPublicationRejectionDiagnostic=null`), published all 3 keys to disk and memory manifests, and cleared pending registrations.
- **Promotion timing verified**: Probe `promotion-session-two-after-close-before-reopen.txt` confirmed that Session 2 committed the cleaned image pointer to disk **before** the final reopen was executed.
- **Restart Manager pre-close query**: Pre-close RM queries in cleanup twins returned `{"status":"ok","needed":0,"rebootReasons":0,"processes":[]}`.

# Causal-Safety Guardrails and Boundary Distinctions

In accordance with the review critique criteria established in [the plan review](../investigation-plan/review), the following distinctions must be strictly maintained:

1. **Test-Fixture Sufficiency vs. Production Store Guarantees**:
 The evidence proves that unclosed `FileInputStream` handles in `readManifest()` are *sufficient* to explain all four observed failures in `ChapterTranslationStoreArtifactOpenProbeTest.kt`. However, this does **not** prove that `ChapterTranslationStore` or its internal coroutines (`drainJob`, `retentionScopeJob`) are universally free of handle leaks or race conditions under high-concurrency production workloads or Android device runtimes.
2. **Restart Manager Negative Reporting**:
 An empty Restart Manager result (`processes: []`) at a pre-close checkpoint indicates that no active lock was detected on the queried path at that instant. It is not affirmative proof of handle ownership architecture.
3. **Intervention State (Published Suite Still Fails)**:
 The workspace source has been restored byte-for-byte to HEAD `999bb9be`. Because the helper-only fix was evaluated as an A/B control and then backed out, running `:app:testDevDebugUnitTest` on the repository right now will continue to reproduce the four baseline failures.

# Recommended Minimal Repair and Gate Disposition

### 1. Minimal Recommended Repair

Apply the 3-line scoping fix from `diagnostics/2026-10-15-helper-only/source/helper-only.patch` to `app/src/test/java/eu/kanade/translation/persistence/chapter/ChapterTranslationStoreArtifactOpenProbeTest.kt`:

```kotlin
private fun readManifest(chapterName: String = "Chapter 1"): ChapterArtifactManifest =
    File(mangaDir, "$chapterName.manifest.json").inputStream().use { input ->
        Json.decodeFromStream<ChapterArtifactManifest>(input)
    }
```

**No changes to production code (`ChapterTranslationStore.kt`), test assertions, or test bodies are needed.**

### 2. Phase 3 / Phase 4 Gate Disposition

- **Baseline Root-Cause Investigation Gate**: **SATISFIED**. The root cause of the baseline store failures is conclusively identified as a test-fixture stream leak rather than a production defect or pipeline regression.
- **Implementation & Rebaselining Gate**: **PENDING EXECUTION**. A focused implementation ticket should be opened to land the 3-line helper fix, execute the verification suite, and rebaseline the class from 14/4 to 14/0.
