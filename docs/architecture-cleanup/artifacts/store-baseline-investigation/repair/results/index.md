---
title: "Store fixture helper repair focused rebaseline"
kind: ticket
status: 1
assignee: ""
comments: none
---

# Result

Applied the accepted test-fixture repair at branch architecture-cleanup, HEAD 999bb9be6563f957ee508f739ba2f042bf1c90ee: ChapterTranslationStoreArtifactOpenProbeTest.readManifest() now scopes the manifest File.inputStream() with use. The original test class passed all 14 tests in each flavor. This matches the already-preserved helper-only diagnostic control; the original 14/4 baseline was not rerun.

No test body, assertion, fixture setup, store lifecycle, production source, or other repository content changed. No full app suite, commit, or push was run. The source is held for Gemini actual-diff review; full app rebaseline follows only after review acceptance.

# Exact change and workspace state

Target: app/src/test/java/eu/kanade/translation/persistence/chapter/ChapterTranslationStoreArtifactOpenProbeTest.kt, helper at line 183. The only content change replaces the unscoped inputStream passed to Json.decodeFromStream with inputStream().use { input -> Json.decodeFromStream<ChapterArtifactManifest>(input) }.

| Evidence | SHA-256 |
| --- | --- |
| Original target source before repair | 33E01BB37B12019D67E990581EFB27E614364259F579A6510D9DC89185B9E541 |
| Repaired target source | FAA79D2C72D1B5F7086649C2DF019429E5D45F87FDFB507D71A0644920F54F57 |
| Accepted one-helper patch | 9A78C6EDC51630613E458A4D98DADE2F778CB84F40B08FF5613C1C39AAAFD6E4 |
| Untracked architecture report, preserved unchanged | EFC11B90A7D0B367F3878BD600BF893AA99ECCDB5879D902E10EB6E06EF88DDD |

git diff --check passed. git diff --stat is one file, 3 insertions and 3 deletions; git diff --name-only contains only the target test file. Eleven other tracked paths remain status M with no content diff and were left untouched. The untracked architecture report remains untouched. Repaired source and accepted patch copies are retained under scratch/store-baseline-repair/2026-10-15-999bb9be/source/.

# Focused runs

Both invocations ran serially from the repository root with Git Bash. Each used --rerun, selected the original full test class (no method selectors), and copied the class XML to its unique scratch directory before another Gradle invocation.

| Flavor | Literal Gradle command | Traycer shell / exit | XML result | Preserved evidence |
| --- | --- | --- | --- | --- |
| DevDebug | ./gradlew.bat :app:testDevDebugUnitTest --tests 'eu.kanade.translation.persistence.chapter.ChapterTranslationStoreArtifactOpenProbeTest' --rerun --console=plain --stacktrace | efcdf595-30e1-4d03-8ffd-1f42426f67b4 / 0 | 14 tests, 0 failures, 0 errors, 0 skipped; 3.695 s | XML: scratch/store-baseline-repair/2026-10-15-999bb9be/dev/xml/TEST-eu.kanade.translation.persistence.chapter.ChapterTranslationStoreArtifactOpenProbeTest.xml (3,330 bytes; SHA-256 6F8823066DD432AE7907FF29763358A1F2AFCD8BAC3302C66A964A39D4DB4797). Log: .../dev/log/gradle.log (16,590 bytes; SHA-256 02494EEC0CB1F00A9DE586D3CFAA80E32183B2B01BA9E5383ADC190CD82492C8). |
| StandardDebug | ./gradlew.bat :app:testStandardDebugUnitTest --tests 'eu.kanade.translation.persistence.chapter.ChapterTranslationStoreArtifactOpenProbeTest' --rerun --console=plain --stacktrace | 79c76646-cb7f-45be-84bb-dea788ba4f0d / 0 | 14 tests, 0 failures, 0 errors, 0 skipped; 1.999 s | XML: scratch/store-baseline-repair/2026-10-15-999bb9be/standard/xml/TEST-eu.kanade.translation.persistence.chapter.ChapterTranslationStoreArtifactOpenProbeTest.xml (3,330 bytes; SHA-256 688F7D1717BE8FA32CCAC65E6A06D814B4BAD5B03EC1417445C74E9FDD04CD62). Log: .../standard/log/gradle.log (16,735 bytes; SHA-256 107254824FE7917A0809EE7FD937F9283A6A602702CA411C8EACB413980FC1A4). |

Both scratch roots are C:\Users\ADMIN.traycer\epics\699de408-00ac-4fe5-a903-b541c23063cc\scratch\store-baseline-repair\2026-10-15-999bb9be. The individual shell records confirm repository-root cwd, literal Gradle command, Git Bash interpreter, and exit 0. Original baseline and accepted helper-only control details remain in the investigation results artifact at ../../results.

# Read-only Spotless check

Literal command: ./gradlew.bat spotlessCheck --console=plain --stacktrace

Traycer shell 4d990474-b0d1-4171-bba9-605e734ce7a9, exit 1. The root check reached :app:spotlessKotlinCheck, which reported the existing LiteRT formatting debt:

src\main\java\eu\kanade\translation\engines\inpainting\litert\LiteRTMangaInpaintingEngine.kt

Full distinct filename inventory: one path, corresponding to app/src/main/java/eu/kanade/translation/engines/inpainting/litert/LiteRTMangaInpaintingEngine.kt. The repaired test file was not reported. No spotlessApply or formatting edit was run.

The complete tee capture is at scratch/store-baseline-repair/2026-10-15-999bb9be/spotless/log/gradle.log (25,303 bytes, 293 lines; SHA-256 9E16398C475F2BF03DC3A6DF5E93088E66BF9A9A7176D2C12D878693E9B809B0). Full-file inspection found the initial Gradle configuration lines, failed task and filename list, and final BUILD FAILED summary. The capture contains no truncation/omission marker; the separately displayed tool output was shorter than this retained log. The check executed 56 actionable tasks (2 executed, 54 up-to-date) and failed in 14 seconds.

# Disposition

Focused acceptance evidence is ready for Gemini actual-diff review. Keep the one-file patch unchanged while review is pending. Do not start either full app test suite until the review accepts this diff. The older original baseline (14 tests / 4 failures per flavor), helper-only control (14 / 0 per flavor), and diagnostic evidence are preserved in the investigation results artifact at ../../results.

# Exact helper excerpt

Old:
    private fun readManifest(chapterName: String = "Chapter 1"): ChapterArtifactManifest =
        Json.decodeFromStream<ChapterArtifactManifest>(
            File(mangaDir, "$chapterName.manifest.json").inputStream(),
        )

New:
    private fun readManifest(chapterName: String = "Chapter 1"): ChapterArtifactManifest =
        File(mangaDir, "$chapterName.manifest.json").inputStream().use { input ->
            Json.decodeFromStream<ChapterArtifactManifest>(input)
        }
