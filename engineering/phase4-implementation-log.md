# T917 Phase 4 — Implementation Log (D7, D8)

Task: `Plan/active/2026-09-01_T917_coexistence-v3-implementation`
Design: `engineering/phase4-design.md`
Branch: `t917/coexistence-v3`

Scope of this log: D7 (engine epoch + borrow drain + boundary retry + drain-grace
alignment) and D8 (occupancy watchdog + honest stall/timeout outcomes + truthful
timer). D10 and D11 are implemented after this log and get their own sections.

Evidence rule: every green claim below is read from
`app/build/test-results/testStandardDebugUnitTest/TEST-*.xml`, never from
Gradle's up-to-date banner. All focused runs used `--rerun`.

---

## 1. D7 — engine epoch, borrow drain, boundary retry

### 1.1 Salvage note

The first Phase 4-A implementer ran to its usage limit mid-D8. D7 was already
complete and committed at that point; nothing was redone:

| commit | content |
| --- | --- |
| `74d037a` | `t917(p4): d7 tests` — `D7EngineEpochStopRaceTest` + harness/fake-engine seams |
| `f49a82f` | `t917(p4): d7 engine epoch + borrow drain + boundary retry` — `EngineLane`, `TranslationPipeline`, `SinglePageHttpRenderPhase` |
| `a37e3f7` | `t917(p4): d7 provider drain-grace budget alignment` — `PROVIDER_DRAIN_GRACE_MS` aligned to `ATTACH_TIMEOUT_MS` (P3 finding 4) |

### 1.2 What D7 changed (production)

- `EngineLane.engineEpoch` increments only in `closeEngines()`; the close
  snapshots the exact old engine references.
- `translatorUseCount` makes an in-flight HTTP translator borrow observable;
  `closeEngines()` stops non-blockingly and waits for the borrow release for a
  bounded grace before tearing the captured engines down.
- An epoch mismatch on a translator call retries exactly once against the
  rebuilt translator; a second mid-retry close fails the page honestly (no
  loop).
- `PROVIDER_DRAIN_GRACE_MS` can never be shorter than the legitimate chain
  budget (`ATTACH_TIMEOUT_MS`).

### 1.3 D7 verification

Targeted `--rerun` run, all green: `D7EngineEpochStopRaceTest`,
`D6DrainNotCancelTest` (including the new bound assertion),
`D9AttemptLedgerTest`, `ChapterTranslatorTerminalExitsTest`.

---

## 2. D8 — occupancy watchdog, honest stall/timeout outcomes

### 2.1 Commit trail

| commit | content |
| --- | --- |
| `a3dd2d6` | `t917(p4): d8 tests` — RED: pure watchdog tests + graph tests with named-defect assertions |
| `954ddfd` | `t917(p4): d8 implementation WIP — compile checkpoint before correctness verification` — production classes compile; graph tests still failing |
| `99dfb1d` | `t917(p4): d8 stall watchdog + honest timeout/stalled outcomes + timer message fix` — GREEN |

### 2.2 What D8 changed (production)

- `NativeStallWatchdog` (new): injectable `Clock`, one-shot occupancy timer,
  token replacement, release clearing, bounded `StateFlow<NativeStallState?>`.
- `NativeRunQuarantine.OccupancyObserver`: `onLaneOccupied(token, pageKey,
  startedAtEpochMs)` at grant, `onLaneReleased(token)` in the `finally` — i.e.
  release fires only when the residual invocation REALLY exits.
- `TranslationExecutor.SinglePageOutcome.Stalled(pageKey, stalledSinceEpochMs)`
  and `.Failed(pageKey, reason)` typed outcomes.
- `TranslationPipeline`:
  - exposes `nativeStall: StateFlow<NativeStallState?>`;
  - `translateSinglePage` refuses a new promise with `Stalled` BEFORE lease and
    native admission while the watchdog is firing;
  - a same-page request whose predecessor still owns the stove (timed-out-but-
    parked native call) is `Rejected("page already translating")` BEFORE
    queueing behind the quarantine. Membership in `inFlightPageKeys` is the
    honest occupancy truth because the entry is cleared only inside the parked
    block; the in-block add-check remains the concurrent-race backstop;
  - the primary native timeout maps to `Failed(pageKey, "native phase timed
    out")` — never `Completed`;
  - all timeout call sites pass the actual configured timer
    (`nativeTimeoutMs` / `SINGLE_PAGE_TIMEOUT_MS`).
- `PageStoreWriter.markPageTimedOut`: truthful timer text. The old
  `${timeoutMs / 1000}s` rendered a 100 ms timeout as "after 0s" (and the
  pre-existing defect reported "120 seconds" while the native timeout was 90).
  Whole seconds stay human-readable; sub-second timeouts render as ms.
- NO native cancel, abort, interrupt, or kill was introduced anywhere. The
  quarantine still waits for the native invocation to really exit after the
  result timeout (`awaitExitAndLogLate`), and the late result is discarded.

### 2.3 Test-infrastructure fixes discovered during GREEN (documented deviations)

1. Harness `nativeTimeoutMs` injection — the harness builds `TranslationPipeline`
   via `Unsafe.allocateInstance`, which skips constructor defaults, so the
   `nativeTimeoutMs` field stayed at the primitive default `0` and EVERY native
   call raced a 0 ms deadline. The harness now injects
   `nativeTimeoutMs ?: TranslationPipeline.ONNX_PHASE_TIMEOUT_MS`. This bug was
   invisible before D8 because Phase 1–3 call sites used the `EngineLane`
   default directly.
2. D8 graph tests use `preRegisterInStore = false` — a pre-registered PENDING
   page resume-skips the native phase before the decode seam, so the manual tap
   never reached the parked stove (harness note §1 documented this for the
   manual path).
3. `CapturingJobMap` completes each key's deferred at the FIRST registration
   only, so `capturedManualJob` on a re-tap returns the stale first job. The
   retry assertion uses the barrier arrival + store terminal state instead of
   the stale job handle.
4. The residual re-tap probe drives the REAL pipeline boundary
   (`pipeline.translateSinglePage`, `origin = MANUAL`) directly. The
   scheduler-level anti-blink dedup (`translatePage` "already active") would
   silently swallow a second `translatePage` while the first job is still
   parked in the quarantine's late-exit wait, and the D8 typed-rejection
   contract lives at pipeline admission.

### 2.4 D8 verification

Focused `--rerun` run:

- `eu.kanade.translation.translator.NativeStallWatchdogTest` — tests 3,
  failures 0, errors 0, skipped 0.
- `eu.kanade.translation.coexistence.D8StallWatchdogTest` — tests 3,
  failures 0, errors 0, skipped 0.

Regression sweep over the packages sharing the touched seams
(`eu.kanade.translation.coexistence.*`, `eu.kanade.translation.translator.*`,
`eu.kanade.translation.scheduling.*`), `--rerun`:

- 39 suites, 268 tests, 0 failures, 0 errors, 0 skipped.

### 2.5 Known behavior notes (not defects)

- During the residual window the first scheduler job is still active, so a
  reader re-tap on the SAME page is swallowed by the scheduler's anti-blink
  dedup (no outcome). The typed `Rejected` path governs every admission that
  reaches the pipeline. The stalled state surfaces via `nativeStall` for the
  Phase 5 UI, which is the designed honesty channel for this window.
- The prepared-page (AUTO) native path keeps the in-block duplicate backstop
  only; the rolling coordinator owns AUTO dedup and D6/D7 govern its drain.
  Extending the pre-admission gate there is intentionally deferred to avoid
  changing AUTO semantics inside Phase 4.

---

## 3. Status

- D7: complete, committed, verified.
- D8: complete, committed (`99dfb1d`), verified.
- Next: D10 (partial-download admission + manifest truth), D11 (release the
  native permit before storage publication), then the Phase 4 gate
  (reviewer → clean-build full sweep → 100-run deterministic soak →
  `checkpoint/t917-p4-done`).
