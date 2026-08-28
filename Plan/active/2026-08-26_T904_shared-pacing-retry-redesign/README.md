# Task T904 — Shared pacing and retry redesign

## Status

IMPLEMENTATION AUTHORIZED. This task implements the approved technical plan;
the T903 audit remains investigation-only.

## Objective

Implement the shared remote-call pacing and retry redesign for TachiyomiAT:

- one process-wide, quota-aware admission service for batch, reader manual, and
  rolling-auto provider calls;
- typed provider failures and bounded transport/semantic retry;
- stable missing-block retry without blank “successful” output;
- pause-aware ordered batching that preserves the completed prefix and leaves
  the unresolved tail pending;
- durable retryable/terminal state, safe resume, queue rearm, and visible
  `PAUSED` status; and
- focused tests, diagnostics, and regression verification.

## Contract

- Base: current committed code at HEAD 926ae00 on
  `optimize_translation_finishing_page`; do not reset, clean, or overwrite
  unrelated changes.
- Plan: `Plan/active/2026-08-26_T903_batch-pretranslate-broad-audit/engineering/shared-pacing-retry-technical-plan.md`
- State model:
  `Plan/active/2026-08-26_T903_batch-pretranslate-broad-audit/engineering/shared-pacing-retry-state-model.md`
- Audit evidence is in the T903 `engineering/` and `review/` reports. Reverify
  source facts against the branch before relying on older reports.
- Android 8.0+, bounded memory, and normal-manga behavior must not regress.
- Every implementation slice must add/update focused tests, preserve guarded
  store/user-edit preconditions, and commit on its assigned branch.
- No raw API keys, prompts, or response bodies in diagnostics.
- Expected provider pauses are data outcomes, not swallowed exceptions or
  synthetic tail failures.

## Delivery order

1. Repository health and Phase 0 characterization/instrumentation.
2. Shared governor, typed transport failures, and all provider-call migration.
3. Typed AI semantic retry and stable missing-block merge.
4. Pause-aware coordinator, artifact transaction, planner/reconciliation, and
   queue lifecycle.
5. UI/notification projection, full integration tests, and independent review.

## Phase ledger

| # | Slice | Branch | Commit | Status |
|---|-------|--------|--------|--------|
| 0 | Repository health | t904/repo-health | 99c11a492e8480a1c19a007c9ab83c4525cd6fb3 | Complete (base YELLOW, safe) |
| 1 | Shared provider request governor, typed transport failures, all call-site migration | t904/shared-pacing-governor | dcd29c89c7d2e28a73bb639c2b6b3ce3336462f6 | Complete; compile + translator unit tests + spotless green |
| 2 | Bounded semantic AI retry (AiChunkOutcome, RequestRetryBudget, missing-block retries) | t904/semantic-retry | 1f62fe03ed4d8c40bae4ee419f9998b37df37265 | Complete; controller/retry/governor tests + compile + spotless green; pipeline compatibility bridge awaits Phase 3 |
| 3 | Pause-aware coordinator, durable retryable artifacts, queue rearm | t904/pause-durable | da56c68 (Phases 1+2 local picks bdba261/5869092) | Complete; focused + 1048-test tier green (AOT excluded); report engineering/phase3-pause-durable-queue.md |
| 4 | UX/notification projection, lifecycle integration | t904/ui-integration | e18ed84 | Complete; full linear chain 011e601→f1450e9→b56f56c→e18ed84 over 926ae00; 1053-test tier green (AOT excluded); report engineering/phase4-ux-lifecycle.md |
| 5 | Assembly + verification | t904/integration | a53fc50 (chain e18ed84 + integration hardening commit) | Complete; spotless/compile/app 1053 (AOT excluded)/domain 68 all green; cross-phase checks pass; report engineering/phase5-integration-verification.md |
| 6 | Independent review | t904/review | a53fc50 reviewed | Complete — verdict REJECT: 1 BLOCKER + 7 MAJOR + 2 MINOR + 2 NOTE; report review/independent-review.md |
| 7 | Review-blocker fix slice + delta re-review | t904/integration | 0e94786 (fixes on a53fc50) | Fix slice complete: blocker + 7 majors resolved (verified in delta review); gates green; report engineering/phase7-review-fixes.md |
| 8 | Round-2 fixes for 4 delta MAJORs + delta re-review 2 | t904/integration | 56179d7 → reviewer self-fix 40c213e | **COMPLETE — FINAL VERDICT: APPROVE-AFTER-SELF-FIX.** Reviewer verified round-2 fixes, self-fixed 4 residual MAJORs (render outcomes, non-durable warning queue retention, live-wins restore merge, request mutation lock/version fence) in 40c213e; all gates green; Delta-2 + applied-fixes documented in review/independent-review.md |

Final chain (approved): `926ae00 → 011e601 → f1450e9 → b56f56c → e18ed84 → a53fc50 → 0e94786 → 56179d7 → 40c213e` on `t904/integration`. Primary-workspace merge pending Director approval.

## Delivery record (2026-08-28)

Director approved delivery to APK. Assembled branch extended with T906 test
fixes: `47f0046` (area-1), `3091c61` (area-2), `710b80f` (area-3),
`aef6c9b` (area-4), `c924bbd` (fixture integration fix). Full gates on the
assembled branch: 1,064 app unit tests, 0 failures, 0 errors (AOT exclusion
moot - test rewritten and passing); domain 68/68; spotless clean. Primary
workspace fast-forwarded `926ae00 → c924bbd`. APK built:
`app/build/outputs/apk/standard/debug/` (arm64-v8a 435.5 MB, armeabi-v7a
223.3 MB, universal 542.4 MB, x86_64 239 MB, x86 237.8 MB). Follow-up:
area-1 RollingAutoCoordinatorTest revival (8 formerly silently-skipped tests;
one revived test hangs - under diagnosis; not in this build, no release risk).

Agents must report commit hashes, verification performed, deviations, and
remaining risks to the Main Leader. Do not address the Director directly.

## Test policy (Director decision, 2026-08-27)

`AotReportBubbleFillTest` (AOT bubble fill, e.g. AotReportBubbleFillTest.kt:49)
is a known deterministic failure that reproduces on the untouched base 926ae00
and is considered inaccurate by the Director. Exclude it from pass/fail gates:
do not treat it as a regression, do not chase or fix it as part of T904, and
filter it out of verification reports. All other test failures remain gating.

## Loop policy (Director decision, 2026-08-27)

Each task is limited to two implementation/review loops. If the second
review still reports blockers, the reviewer applies the remaining fixes
itself on `t904/integration`, runs the gates, and records the final PASS
with its findings documented. No third implementation round-trip.
