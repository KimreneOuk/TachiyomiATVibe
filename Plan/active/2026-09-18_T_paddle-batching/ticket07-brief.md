# Ticket 07 — Wire B1→B4 batch path into normal translation flow behind the device gate

## Context

Main now contains the full reviewed batching stack (merge ba3c729 of research/paddle-ocr-android-batching, tickets 01–06, six GLM PASS reviews). Production behavior today: Paddle OCR runs B1 on the legacy path. The user-facing Paddle provider selector (T935, commit 26e77bf) is live. The Director has ordered: make the proven B1→B4 path usable from the normal translation flow, behind the device gate.

## Current wiring facts (verified on main @ ba3c729)

- `app/src/main/java/eu/kanade/translation/recognition/RoiPageRecognitionEngine.kt`
  - Line ~227: `val activation = PaddleOcrBatchActivationPolicy.current()` — only `forceCpuB1EmergencyFallback` is consumed (provider configuration). `activation.activeBatchSize` is DISCARDED.
  - Line ~265: `paddlePageOcrCoordinator = PaddlePageOcrCoordinator(...)` — runs with `validatedBatchSize = B1` default. This is the production page loop.
- `app/src/main/java/eu/kanade/translation/ocr/paddle/batch/PaddleOcrBatchActivationPolicy.kt` — reads `BuildConfig.PADDLE_BATCHING_REQUESTED_BATCH` (1/2/4/8) + `BuildConfig.PADDLE_BATCHING_STAGED`; resolves via `PaddleOcrDevicePolicy.resolveActivation(...)`. Default profile `PaddleOcrDeviceProfile.untested()` → any B>1 request resolves to `emergency()` (CPU B1, reason `combination_not_confirmed`).
- `app/src/main/java/eu/kanade/translation/ocr/paddle/batch/PaddleOcrDevicePolicy.kt` — pure gate: B1 → `b1_default`; staged off / no provider / unconfirmed cell / thermal → emergency CPU B1.
- `app/src/main/java/eu/kanae/translation/ocr/paddle/batch/PaddleOcrRollingP95Policy.kt` — `PaddleOcrRollingP95HysteresisDowngradePolicy`: 20-sample window, 2 high windows → downgrade one step, 3 low windows → recover one step. Implemented and unit-tested, but consumed by NOTHING in production (Ticket 06 binding follow-up).
- `PaddleOcrV6SmallEngine.initialize(modelFile, dictionaryFile, providerResolution, strictProviderMode, providerConfiguration)` — unified merge of T935 provider selector path and Ticket 06 test-configuration path. Exposes `recognizeBucketBatch` + `lastBatchTelemetry`.
- Provider selector preference: `tachiyomi/domain/src/main/java/tachiyomi/domain/translation/TranslationPreferences.kt` → `translation_paddle_ocr_execution_provider` (T935). Settings row in `SettingsTranslationScreen.kt`.

## Required work

1. **Batch-size preference** (device gate input):
   - Add `PaddleOcrRecognitionBatch` (B1 default, B2, B4 — NO B8 in the user setting) to the domain translation model next to `PaddleOcrExecutionProvider`.
   - Add persisted preference `translation_paddle_ocr_recognition_batch` in `TranslationPreferences.kt`.
   - Add a Settings row in the translation screen beside the existing Paddle provider row (T935 pattern, string resources in moko base strings.xml). Mark the non-B1 options as experimental. Changing it latches at engine init — same restart semantics as the provider selector; the UI copy must say so.

2. **Activation policy consumption (the gate)**:
   - Feed the preference into `PaddleOcrBatchActivationPolicy.current(...)` as the requested batch (BuildConfig value becomes a cap/debug override, not the only source).
   - Debug builds: an explicit user opt-in to B2/B4 counts as a PROVISIONAL device confirmation — the activation resolves `activeBatchSize = requested` with `reason = "debug_provisional_optin"`, while thermal guard and staged-flag rules still apply. Release builds keep the strict `profile.isConfirmed(combination)` requirement (unconfirmed → emergency CPU B1 exactly as today).
   - B1 request must resolve byte-identical to today (`b1_default`, no emergency, no provider force).
   - Emergency paths must never silently lie: reason string propagates to logs.

3. **Production loop consumption**:
   - In `RoiPageRecognitionEngine`, pass `activation.activeBatchSize` into `PaddlePageOcrCoordinator(validatedBatchSize = ...)`. The coordinator's own validation (page-scoped, bounded in-flight window, generation fences) is untouched.
   - Coordinator/engine are created at page-engine init: a preference change takes effect at next engine init (restart), consistent with the provider selector. Do NOT attempt mid-page batch-size mutation.

4. **Wire the p95 governor (Ticket 06 binding follow-up)**:
   - Own one `PaddleOcrRollingP95HysteresisDowngradePolicy(initialBatchSize = activation.activeBatchSize)` in `RoiPageRecognitionEngine`.
   - Record every microbatch latency for Paddle pages (from the batch executor telemetry — collect per-batch latencies through the coordinator seam; engine `lastBatchTelemetry` alone is last-write-only, so extend the seam minimally, e.g. coordinator accumulates batch wall times and the engine records them when the page result returns).
   - On `DOWNGRADE`: subsequent PAGES use the lowered size (recreate coordinator with new size at next page boundary; never mid-page). On `RECOVER`: step back up, CAPPED at the user-requested size — never above what the gate approved. Log each DOWNGRADE/RECOVER decision (one line).
   - Governor changes batch size ONLY. Provider never changes (no CPU fallback from the governor).

5. **Honesty telemetry**: when a page runs at B>1, the existing `[ocr_block]`/stage logs must be able to show it (batched=true / batch size / governor reason). Keep log volume bounded (per-page summary, not per-crop).

6. **Tests (JVM, deterministic, no Robolectric)**:
   - Preference → activation mapping: B1 default; debug provisional opt-in for B2/B4; release unconfirmed-cell → emergency CPU B1; thermal guard still blocks in debug.
   - Governor capping: recovery never exceeds requested size; downgrade ladder from B4 lands B2 then B1.
   - Coordinator accepts `validatedBatchSize = B4` and still enforces page-scoped fences (extend existing coordinator tests, do not weaken any).
   - All existing suites must stay green: planner, executor, B1 parity, page lifecycle, coordinator, contract, provider factory, discovery, provenance, catalog, settings summary.

## Hard rules

- Production default stays B1. No path may activate B>1 in release without a confirmed device-profile cell.
- No cross-page batching. No MangaOCR/MLKit changes. No checkpoint/lease/quarantine changes.
- No new god files; `RoiPageRecognitionEngine` gets wiring only (+~40 lines max); policy logic goes in focused batch-policy files.
- Minimal comments; no large explanatory blocks.
- Build/test env: `$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'; $env:ANDROID_HOME='C:\Users\User\AppData\Local\Android\Sdk'; .\gradlew.bat <task> --no-daemon`.
- Commit on main (single commit) with a conventional message; `git diff --check` clean; do not stage unrelated untracked files.

## Deliverable

Handoff report: commit hash, files changed, test counts (focused + regression), activation truth table (request × buildType × profile → resolved size/reason), governor decision log format, and explicit UNTESTED list (on-device B4 latency/memory/thermal).
