# Task brief — test backfill

**Date:** 2026-07-12
**Branch:** `fix/translation-race-p0-quality` (no new branch — Waves 1-4 not merged yet)

## Objective

Backfill missing regression tests flagged by the 4-way review subagent audit
(2026-07-12). Code is correct; tests must lock it in.

## Current symptom

Review found ~9 of ~19 contracted Wave 1/3/4 tests missing or partial.
Headline concurrency fixes (P0-1, P0-3, P0-4) ship with zero automated guard.
Dead infrastructure: `MainDispatcherRule` + `inFlightPageKeysSnapshot` probe
have no callers.

## Scope boundary

IN SCOPE:
- P0-1 structural test (nativeGuard tryLock present + skip-when-held)
- P0-3 ordering test (clear before permit check, via existing probe)
- P0-4 throw-isolation test (onPageStuck throw does not block onForceRelease/release)
- P0-2 two missing cases (no-op finished, re-translate over CANCELLED)
- P1a RenderColorEstimator dedup golden test
- P1b inpaint encode ordering test (if automatable via a seam)
- Fix 2 stale comments (RoiPageRecognitionEngine:731-733, TranslationPipeline:2487-2489)
- Wire or remove dead MainDispatcherRule + probe

OUT OF SCOPE (require approval — separate work):
- Wave 4 copyIfNeeded Context-decoupling refactor + OnnxModelStoreVersionTest
  (architecture refactor per AGENT.md autonomy policy)
- aot-512.onnx re-conversion (Wave 5.1, needs onnxslim env)
- 20-page corpus + Tier 2/3 harness (Wave 5.1)
- copyIfNeeded stamp design decision (Step 3 — user decision)

## Acceptance criteria

- Every new test RED first (fails on a deliberately-reverted production line),
  GREEN after revert undone.
- `./gradlew :app:testStandardDebugUnitTest` fully green.
- No production behavior change except the 2 stale-comment rewordings.
- Dead infra either used or deleted.

## Constraints

- Match repo style: JUnit 5 + Kotest `shouldBe` + MockK + `runTest`.
- Backtick descriptive test names.
- No new deps (no Robolectric/MockWebServer/AssertJ).
- 6 GB RAM device target — tests must not allocate huge buffers.

## Stop conditions

- If any production code turns out buggy (not just untested), STOP, report, do
  not silently fix outside scope.
- If a test cannot be made RED-first without a larger refactor, defer it with
  a documented reason; do not write a theater test.

## Validation

```
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testStandardDebugUnitTest --no-daemon
```
