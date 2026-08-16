# Task Plan: Extension Lifecycle Audit and TachiyomiX 1.6 Compatibility Fix

## Problem Statement
1. Extensions disappeared from available and installed extension lists after community repositories (Keiyoushi) upgraded to TachiyomiX 1.6 / Mihon 0.20+.
2. The app repeatedly prompts to update to Mihon v0.20 due to `AppUpdateChecker` querying `mihonapp/mihon` releases.

## Root Cause
- Hardcoded `ExtensionLoader.LIB_VERSION_MAX = 1.5` causes `ExtensionApi` to filter out all 1.6 extension items from repository JSON feeds and causes `ExtensionLoader` to return `LoadResult.Error` on 1.6 extension APKs.
- `AppUpdateChecker.GITHUB_REPO` defaults to `mihonapp/mihon` instead of TachiyomiAT repo.

## Execution Steps
1. Update `ExtensionLoader.kt`: bump `LIB_VERSION_MAX` to `1.6`, support `tachiyomix.extensionLib` metadata, strip `"Tachiyomix: "` prefix.
2. Update `ExtensionApi.kt`: support `libVersion` field and `"Tachiyomix: "` prefix in JSON parsing.
3. Update `AppUpdateChecker.kt`: adjust `GITHUB_REPO` / updater logic.
4. Add unit tests for extension version extraction and verification.
5. Run `./gradlew :app:testDevDebugUnitTest` and `./gradlew spotlessCheck`.
