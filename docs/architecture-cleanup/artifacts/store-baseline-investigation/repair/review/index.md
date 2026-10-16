---
title: "Review of focused store test helper repair and baseline resolution"
kind: review
comments: none
---

# Review Verdict: ACCEPTED

The permanent test-fixture repair applied to `app/src/test/java/eu/kanade/translation/persistence/chapter/ChapterTranslationStoreArtifactOpenProbeTest.kt` is **ACCEPTED**.

The code change strictly matches the accepted one-helper patch from the investigation control, cleanly scopes the leaked manifest `FileInputStream` with `.use { ... }`, introduces zero regressions or collateral edits, and successfully resolves all four baseline failures across both `DevDebug` and `StandardDebug` variants (14 tests, 0 failures, 0 errors).

# Code Inspection & Line-by-Line Audit

Target file: `app/src/test/java/eu/kanade/translation/persistence/chapter/ChapterTranslationStoreArtifactOpenProbeTest.kt`
Git diff: Exactly 1 file modified, 3 insertions (+), 3 deletions (-).

### Line Diff Excerpt (Lines 180–187):

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

     @Test
     fun `committed-only artifact fixture rehydrates with no legacy flat file`() {
```

### Invariant Checks:

1. **Helper Scoping**: `File.inputStream()` is directly enclosed in Kotlin's standard `.use { input -> ... }` block, ensuring deterministic stream closure immediately after JSON deserialization completes.
2. **Method Bodies & Assertions Intact**: All 14 test methods retain their exact original names, execution bodies, lifecycle calls, and assertion predicates without modification.
3. **No Store Teardown Additions**: Store-lifecycle omissions present in the baseline test bodies (e.g. unclosed store instances in tests #1, #3, and #4) were preserved without modification.
4. **Production Code Zero-Touch**: Production `ChapterTranslationStore.kt` remains unmodified at SHA-256 `70C67D83FEB15BCD8F08F52597A6F75786C717DD470040F0B81162D4D40BEF59`.

# Verification Evidence & Test Method Audit

### 1. Focused Test Execution

Two fresh, serial Gradle test runs were executed by Luna via Git Bash from repository root using `--rerun` and the full class selector `eu.kanade.translation.persistence.chapter.ChapterTranslationStoreArtifactOpenProbeTest`:

| Variant | Shell ID | Exit | Tests | Result | Runtime | Evidence Paths |
| --- | --- | --- | --- | --- | --- | --- |
| **DevDebug** | `efcdf595-30e1-4d03-8ffd-1f42426f67b4` | 0 | 14 | 14 passed, 0 failed, 0 errors | 3.695 s | `scratch/store-baseline-repair/2026-10-15-999bb9be/dev/xml/TEST-...xml`<br>`scratch/store-baseline-repair/2026-10-15-999bb9be/dev/log/gradle.log` |
| **StandardDebug** | `79c76646-cb7f-45be-84bb-dea788ba4f0d` | 0 | 14 | 14 passed, 0 failed, 0 errors | 1.999 s | `scratch/store-baseline-repair/2026-10-15-999bb9be/standard/xml/TEST-...xml`<br>`scratch/store-baseline-repair/2026-10-15-999bb9be/standard/log/gradle.log` |

### 2. Full 14 Method Identities Verified

Inspection of the resulting JUnit XML testsuites (`6F882306...` Dev, `688F7D17...` Standard) confirms all 14 original baseline test method identities passed without omission, renaming, or synthetic substitution:

1. `committed-only artifact fixture rehydrates with no legacy flat file()`
2. `artifact candidate cancel and failure reopen retain committed display()`
3. `openArtifact falls back to synthesized page and getOrLoadPageSnapshot lazily hydrates()`
4. `production promotion retains a held previous cleaned stream until release()`
5. `missing expected pages warn while a durable stage failure remains error()`
6. `lazy store cuts over before first candidate and reopens from artifact pointers()` *(Previously failed: now PASS)*
7. `pre-registered pages publish one durable baseline with only the changed candidate()` *(Previously failed: now PASS)*
8. `artifact-only lazy store writes pages without materializing flat compatibility file()` *(Previously failed: now PASS)*
9. `lazy store without parent rejects mutation when artifactParentResolver fails()`
10. `chapter without a legacy translation file gains no manifest()`
11. `artifact-authoritative reopen keeps committed display while incomplete candidate resumes()`
12. `batch baseline certifies complete artifact pages()`
13. `released batch callbacks cannot overwrite a reacquired artifact candidate()`
14. `lazy store without parent resolves artifact authority through artifactParentResolver()` *(Previously failed: now PASS)*

### 3. Read-Only Spotless Check

A full read-only spotless check (`./gradlew.bat spotlessCheck --console=plain --stacktrace`, shell `4d990474-b0d1-4171-bba9-605e734ce7a9`, exit 1) was audited via its complete 293-line log at `scratch/store-baseline-repair/2026-10-15-999bb9be/spotless/log/gradle.log` (SHA-256 `9E16398C...`):

- Execution verified across all 56 tasks (2 executed, 54 up-to-date).
- **Single failure source**: `:app:spotlessKotlinCheck` reported format violations strictly and exclusively in `src\main\java\eu\kanade\translation\engines\inpainting\litert\LiteRTMangaInpaintingEngine.kt` (the known pre-existing LiteRT debt).
- The modified test file `ChapterTranslationStoreArtifactOpenProbeTest.kt` produced **zero** formatting violations.

# Checksum & Provenance Ledger

| Entity | Verified Path | SHA-256 Checksum | Status |
| --- | --- | --- | --- |
| Repaired Test Source | `app/src/test/.../ChapterTranslationStoreArtifactOpenProbeTest.kt` | `FAA79D2C72D1B5F7086649C2DF019429E5D45F87FDFB507D71A0644920F54F57` | Verified |
| Production Store | `app/src/main/.../ChapterTranslationStore.kt` | `70C67D83FEB15BCD8F08F52597A6F75786C717DD470040F0B81162D4D40BEF59` | Untouched |
| Architecture Report | `TachiyomiATVibe_Translation_Simplicity_First_Architecture_Report.md` | `EFC11B90A7D0B367F3878BD600BF893AA99ECCDB5879D902E10EB6E06EF88DDD` | Untracked, Untouched |
| Accepted Patch Copy | `scratch/.../source/accepted-helper.patch` | `9A78C6EDC51630613E458A4D98DADE2F778CB84F40B08FF5613C1C39AAAFD6E4` | Matches Investigation Control |
| Repaired Source Copy | `scratch/.../source/Chapter...repaired.kt` | `FAA79D2C72D1B5F7086649C2DF019429E5D45F87FDFB507D71A0644920F54F57` | Matches Workspace Target |
| Dev XML | `scratch/.../dev/xml/TEST-...xml` | `6F8823066DD432AE7907FF29763358A1F2AFCD8BAC3302C66A964A39D4DB4797` | Verified (14/0) |
| Dev Gradle Log | `scratch/.../dev/log/gradle.log` | `02494EEC0CB1F00A9DE586D3CFAA80E32183B2B01BA9E5383ADC190CD82492C8` | Exit 0 |
| Standard XML | `scratch/.../standard/xml/TEST-...xml` | `688F7D1717BE8FA32CCAC65E6A06D814B4BAD5B03EC1417445C74E9FDD04CD62` | Verified (14/0) |
| Standard Gradle Log | `scratch/.../standard/log/gradle.log` | `107254824FE7917A0809EE7FD937F9283A6A602702CA411C8EACB413980FC1A4` | Exit 0 |
| Spotless Gradle Log | `scratch/.../spotless/log/gradle.log` | `9E16398C475F2BF03DC3A6DF5E93088E66BF9A9A7176D2C12D878693E9B809B0` | Verified LiteRT Only |

### Workspace Hygiene:

- `git diff --stat` confirms exactly 1 file modified (+3/-3).
- 11 pre-existing tracked files remain status `M` with empty content diffs (CRLF/stat cache).
- No changes staged; no commits or pushes executed.

# Bounded Scope Affirmation

As established throughout the investigation and causal critique:

- This repair resolves the test-fixture stream leak that caused four test failures in `ChapterTranslationStoreArtifactOpenProbeTest.kt` on Windows.
- It does **not** assert or prove generic handle safety or coroutine lifecycle invariants across arbitrary production workloads, nor does it certify storage-decomposition readiness.

# Next Steps

1. **Authorization for Full Suite Execution**: The focused repair is accepted. Luna may proceed to execute the full application test suites (`:app:testDevDebugUnitTest` and `:app:testStandardDebugUnitTest`) to verify baseline health across the remaining 331 classes (~2,240 tests) against Phase 2 results.
2. **Rebaselining & Publication**: Following successful full suite completion and evidence review, the one-file repair should be committed and pushed to `origin/architecture-cleanup`.
