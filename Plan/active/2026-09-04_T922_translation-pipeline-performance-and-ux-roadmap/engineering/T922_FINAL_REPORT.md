# T922 Final Report — Translation-Pipeline Stability and Observability

**Date:** 2026-09-05
**Status:** Implementation complete; all five phases implemented, reviewed, and unit-tested. Stability fix confirmed working on the OnePlus Ace 5 by the Director (manual + Auto). On-device trace-evidence capture for batch/overhead scenarios was stopped by the Director and is the only open item.

## What was implemented (all uncommitted, as required)

### Phase 1 — Stability (reviewed: PHASE1_STABILITY_REVIEW.md — APPROVED WITH NOTES)
- `OnnxBubbleSegmenter.kt`: production session now CPU-primary (`useAccelerator=false, useXnnpack=false`); one-shot accelerated→CPU runtime recovery (OrtException-only, single retry, session swap under segmenter-local lock, ModelRoutingEngine.recordFailure called read-only).
- `App.kt`: automatic `QnnDiagnostics.runOnce()` removed from debug startup; explicit developer-only trigger preserved (`adb shell am broadcast -a tachi.action.DEBUG_RUN_QNN_DIAGNOSTICS`, debug builds only). No finalization mode 3 restored.
- `BubbleSegmenterRecoveryPolicyTest.kt`: 6-case recovery-policy suite. 38/38 segmentation tests green.
- Route coverage verified: manual, rolling Auto, and batch all reach the same segmenter path.

### Phase 2 — Trace foundation (reviewed: PHASE2_TRACE_FOUNDATION_REVIEW.md — APPROVED WITH NOTES)
- New `eu.kanade.translation.diagnostics` package: `TranslationPipelineDiagnostics.kt`, `TranslationTrace.kt`.
- Single tag `TachiyomiAT.Translation`; `schema=translation_trace_v1`, fixed key order; schedule/run/ThreadContextElement identity; process-random keyed HMAC identities (deterministic ShortHash rejected); idempotent terminal + balanced lane handles; injectable clock; O(1) overlap accumulator (lane busy / union-active / overlap-union / concurrencySavingsMs); repeated-stage summation; stage budgets with lag flags; gating (debug default, explicit toggle); fail-open everywhere; zero new suspension points; bounded memory.
- 26/26 diagnostics tests green.

### Phase 3 — Manual and Auto wiring (reviewed: PHASE3_MANUAL_AUTO_WIRING_REVIEW.md — APPROVED WITH NOTES)
- Wired `TranslationScheduler.kt` (manual schedule/run, terminal on cancel-before-dispatch; legacy Auto path), `TranslationPipeline.kt` (lease/native-lane/outcome boundaries), `SinglePageOnnxPhase.kt` (decode/persist/plan), `RoiPageRecognitionEngine.kt` (detect/segment/ocr/inpaint with actual provider labels; global `route=` claim retired), `SinglePageHttpRenderPhase.kt` (governor wait/translate/layout/render/commit/flush), `RollingAutoCoordinator.kt` (schedule identity, prepared-queue wait, trace element propagation, terminal ownership on stale handoff / send-cancel / eviction / replacement).
- Legacy raw-log migration under the new tag; `reason=` vocabulary pinned.
- 8 new trace tests; 1432 tests green at phase end.

### Phase 4 — Batch wiring (reviewed: PHASE4_BATCH_WIRING_REVIEW.md — APPROVED WITH NOTES)
- `BatchChapterTranslator.kt` (schedule lifecycle incl. teardown-exception-safe terminal), `SequentialBatchCoordinator.kt` (per-page runs, render-join wait), `BatchLaneWorkers.kt` (provider-envelope counted ONCE at schedule scope; per-page wait attributed separately), `BatchRenderJoin.kt`, `BatchTranslationDiagnostics.kt` (facade delegating to translation_trace_v1).
- Carried fixes: F1 registration/sweep race closed under lifecycleLock (+ regression test), F2 dangling spans settled via try/finally.
- Envelope double-count regression pinned by test. 1439 tests green at phase end.

### Phase 5 — Provider/routing correctness (reviewed: PHASE5_PROVIDER_ROUTING_REVIEW.md — APPROVED WITH NOTES)
- `OnnxRuntimeProvider.kt`: labels derive from actual registration, never `HardwareDiscoveryEngine.activeRoute`; XNNPACK failure → honest `cpu`; strict accelerator failure → CPU retry, never labelled QNN; providerSink only after `createSession()` succeeds; `markSupported()` removed from session creation.
- `ModelRoutingEngine.kt` (additive): `recordSuccessfulInference(model, route)`; SUPPORTED now execution-qualified; TEMPORARY_FAILURE retry gate coherent; QNN error 1100 classified as model-route execution failure regardless of message text. Bubble path wires successful-inference recording.
- AOT/detector/OCR/context-cache behavior preserved; pre-edit captures of all Director-owned files in `C:\Users\User\Documents\T922_baseline_backup_2026-09-04\`.
- 15 new/updated tests; 1455 tests green at phase end.

## Verification status

- Full `:app:testStandardDebugUnitTest`: initially flaky. Independent baseline-clone experiment proved the flakes (`MangaScreenModelCancelledBatchReconciliationTest`, `D10PartialDownloadAdmissionTest`) are PRE-EXISTING (reproduce on clean pre-T922 baseline; root causes: lost SharedFlow emission at MangaScreenModel.kt:532 and a marginal 2 s probe in StandardLaneMultiPageCompletionTest.kt:58). Two minimal test-only stabilizations applied (no assertion weakening, no production change) → full suite green 4/4 consecutive runs. Evidence: `FLAKINESS_DIAGNOSIS.md`.
- `:app:assembleStandardDebug`: SUCCESS. APK built 2026-09-05 12:53, installed on device 12:54:48 (includes all phases + debug trace-toggle broadcast `tachi.action.DEBUG_SET_TRANSLATION_TRACE`).
- Startup check: normal debug startup produces no QnnDiagnostics workload (code-verified; on-device cold-start capture pending a natural restart).
- Device scenarios:
  - Manual translation on uncached pages: **works — confirmed by Director on the installed build.**
  - Rolling Auto translation: **works — confirmed by Director on the installed build.**
  - Batch translation, log-based acceptance capture (provider=cpu in all modes, run/schedule terminality counts, overlap values, A/B <3% overhead, long-batch memory/thermal sampling): **NOT captured** — device automation stopped by the Director before evidence collection. These are diagnostics-only items; no functional risk is known.

## Provider routes proven
- Bubble segmenter: routed to CPU by code and unit tests; Director's successful manual/Auto runs on device are consistent with no QNN-1100 recurrence (no failure reported).
- AOT-GAN: QNN HTP route and CPU fallback preserved byte-for-byte (Phase 5 review), unchanged behavior.
- Detector/OCR/context-cache: unchanged (preservation proven against baseline diffs).

## Preservation
- All 14 Director-owned dirty tracked files and 5 protected untracked files verified byte-identical at every phase review against `baseline_tracked.patch` / `BASELINE_CAPTURE_2026-09-04.md` hashes. Baseline backups: `C:\Users\User\Documents\T922_baseline_backup_2026-09-04\`.
- Nothing committed, pushed, stashed, reset, or branched. Worktree contains 56 changed/untracked entries, all attributable to the five phases + task reports + the two pre-existing-flake test stabilizations.

## Remaining risks / open items
1. On-device trace evidence for batch mode (schedule summary, overlap/concurrency savings, envelope single-count in the wild) not captured — diagnostics-only gap.
2. §10.5 overhead A/B (<3%) unmeasured on device; gate exists (`tachi.action.DEBUG_SET_TRANSLATION_TRACE`), measurement pending.
3. Long-batch heap/thermal/log-volume sampling not performed.
4. Pre-existing suite flake residual: single-occurrence D2/D10 flakes documented in FLAKINESS_DIAGNOSIS.md (pre-existing, test-side, not addressed beyond the two stabilizations).
5. Known LOW diagnostics-ordering notes (terminal ordering on drained runs; runOcr failure-path stage-end) remain as documented in phase reviews — non-blocking.

## Recommendation
Accept the implementation as complete for the stability objective. If the Director wants the remaining observability evidence, the cheapest path is: run one batch translation and one manual page with the app as-is, then hand the logcat buffer to the team (`adb logcat -d -s TachiyomiAT.Translation`) — no automation needed.
