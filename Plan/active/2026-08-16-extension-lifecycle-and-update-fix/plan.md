# Task Plan: Extension Lifecycle Audit and TachiyomiX 1.6 Compatibility Fix

## Status: COMPLETE

## Checkpoints
- [x] Initial checkpoint commit `22604c4` created.
- [x] Updated `ExtensionLoader.kt`: `LIB_VERSION_MAX = 1.6`, `tachiyomix.extensionLib` metadata parsing, `"Tachiyomix: "` prefix handling.
- [x] Updated `ExtensionApi.kt`: `libVersion` DTO parsing, `"Tachiyomix: "` prefix handling.
- [x] Updated `AppUpdateChecker.kt`: switched `GITHUB_REPO` from `mihonapp/mihon` to `KimreneOuk/TachiyomiATVibe`.
- [x] Added `ExtensionLoaderCompatibilityTest.kt`.
- [x] Verification: `:app:compileDevDebugKotlin` passed, `:app:spotlessCheck` passed.
