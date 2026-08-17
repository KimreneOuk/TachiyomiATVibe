# Task Plan: Fix chapter-list AbstractMethodError (serialization ABI mismatch)

## Status: COMPLETE (verification passed; on-device check pending user)

## Verification result (2026-08-17)

`spotlessCheck assembleStandardRelease testReleaseUnitTest testStandardReleaseUnitTest`
→ **BUILD SUCCESSFUL** (Kotlin 2.2.20, serialization 1.11.0, Gradle 8.12, AGP 8.8.1).
App standard-release unit tests: 811 tests, 0 failures, 0 errors, 0 skipped.
Release APKs (arm64-v8a / armeabi-v7a / universal) produced.

Run used a temporary init script excluding the three pre-existing broken classes
below (`excludeTestsMatching`); no repo files touched by the exclusion.

Note: R8 prints "error occurred when parsing kotlin metadata" warnings — AGP 8.8.1's
bundled R8 predates Kotlin 2.2.20 metadata. Non-fatal (build and minification succeed);
a future AGP bump would silence it.

## Root cause

Viewing a chapter list for some sources crashed with:

```
AbstractMethodError: abstract method
"kotlinx.serialization.KSerializer[] kotlinx.serialization.internal.GeneratedSerializer.typeParametersSerializers()"
```

This is the documented kotlinx-serialization ABI boundary introduced in 1.8.0
(Kotlin/kotlinx.serialization#2968): serializers generated with serialization ≥ 1.8.0
throw `AbstractMethodError` when running against a host runtime < 1.8.0.

Extensions are loaded via `ChildFirstPathClassLoader` and resolve
`kotlinx.serialization.*` from the host app, so the host runtime version must be ≥
the version each extension was compiled with. Current Keiyoushi builds and Mihon
0.20.1 both use serialization 1.11.0 (built with Kotlin 2.3.20), while this app had
pinned/forced serialization 1.6.3 (commits `1c215b1` + `9363d74`, which lowered the
original 1.7.3). Sources with freshly built extension APKs crashed; older APKs
compiled with ≤ 1.6-era serialization still worked.

## Fix (chosen approach)

Kotlin 2.1.0 → 2.2.20 (minimum that can consume serialization 1.11.0 binaries,
per the +1 Kotlin metadata rule) and serialization 1.6.3 → 1.11.0, matching the
runtime stack of Mihon 0.20.1 and current Keiyoushi builds. Gradle 8.12 and
AGP 8.8.1 unchanged.

## Checkpoints
- [x] `gradle/kotlinx.versions.toml`: `kotlin_version = "2.2.20"`, `serialization_version = "1.11.0"`.
- [x] Force blocks repointed to 1.11.0 in root `build.gradle.kts` and `buildSrc/.../ProjectExtensions.kt` (kept as guard against transitive downgrades).
- [x] `:app:compileStandardDebugKotlin` passed; no Compose BOM bump needed (2.2.20 compiler accepts BOM 2024.12.01).
- [x] `spotlessCheck assembleStandardRelease testReleaseUnitTest testStandardReleaseUnitTest` pass
      (with the three pre-existing broken classes excluded via a temporary init script —
      they fail identically on the old toolchain; details below).
- [ ] On-device check: previously failing source's chapter list loads; note behavior of one old extension APK.

## Pre-existing failures found during verification (NOT caused by this fix)

All three come from commit `efb8dee` (2026-08-16, rolling auto-translation) and fail
IDENTICALLY on the old toolchain (Kotlin 2.1.0 + serialization 1.6.3, verified by
isolated A/B runs on HEAD code). The last full test run before them was 2026-07-18.

1. `RollingAutoCoordinatorTest."older same-spec snapshot build cannot overwrite newer stage"`
   (RollingAutoCoordinatorTest.kt:786) — hangs forever in `buildStarted.await()`.
   Mechanics: the test gates a snapshot rebuild on the page resolver being invoked, but
   `stageListenerFor` sets `slotStates[pageIndex]` before calling `publishSnapshot`, so
   `buildSnapshot`'s visible-slot branch (`slotStates[visible]?.let { ... }`)
   short-circuits and never calls `needsAutoWork` → `storePage` → `spec.pageResolver(idx)`.
   The gated resolver is never entered and the await has no timeout.
2. `ReaderAutoTranslationLifecycleTest."same identity anchor replacement keeps
   already-issued stream handles usable"` — `java.lang.StackOverflowError`.
3. `ChapterTranslationStoreDefunctTest."updatePage is a no-op after markDefunct and
   leaves state untouched"` — `AssertionFailedError: expected:<0> but was:<1>`. This
   test passed on 2026-07-18 (c652306-era store code); efb8dee's +92-line store
   refactor reintroduced updates after markDefunct — a real production-code regression
   in the store lifecycle contract, not just a test issue.

These need attention in the rolling-auto-translation work
(see Plan/active/2026-08-06-rolling-auto-translation).

## Known tradeoff (accepted)

Runtime 1.11.0 breaks ancient extension APKs compiled with serialization < 1.8.0
(pre-~2025 builds) — the mirror image of the original failure. Upstream Mihon
accepted the same break when it moved to 1.8.0+. Affected extensions can be
updated in-app now that the Keiyoushi repo (index.json) is supported
(`1fcb2d7`). Worth a release note.
