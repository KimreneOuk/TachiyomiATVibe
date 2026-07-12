# Translation Race Condition — Implementation Design

**Date:** 2026-07-12
**Scope:** Design only. No code changes. Implementation route for the fixes proposed in `TRANSLATION_RACE_CONDITION_INVESTIGATION_2026-07-11.md`.
**Source plan:** `TRANSLATION_RACE_CONDITION_INVESTIGATION_2026-07-11.md` (audited 2026-07-12; 24/25 claims verified, 1 cosmetic line-off)
**Companion designs:** `AOT_NPU_FIXED512_DESIGN_2026-07-12.md`, `PIPELINE_EFFICIENCY_DESIGN_2026-07-12.md`, `INPAINTING_EFFICIENCY_DESIGN_2026-07-12.md`
**Status:** Awaiting approval. Not initiated.

---

## Executive Summary

The source plan identified **4 user-reported bugs + 1 SIGSEGV (HIGH severity)** and proposed 8 fixes (P0-1..P0-4, P1-5, P1-6, P2-7, P3-9). Audit verified all bug claims against live code; all 8 fixes are technically sound.

This design adds the layer the source plan deferred: **verification strategy, sequencing, parallelism, and cross-plan reconciliation.**

**Key constraint from audit:** The plan's "Validation Approach" table admitted "No unit tests exist for these concurrency paths." Verified: **zero precedent** for Semaphore/Mutex unit tests anywhere in the codebase, and **no shared coroutine dispatcher test rule** exists. So verification requires establishing new infrastructure, not extending existing tests. Several fixes (P0-1 SIGSEGV, P0-2 synchronous cancel) are genuinely hard to unit-test because they defend against native-code timing windows.

**Highest-value sequencing insight:** P0-1 (SIGSEGV) is a **hard prerequisite for AOT NPU Phase 3-2** (dual-session teardown). It must land first or in parallel with the AOT track. It is also independently the most severe finding across all four plans — a native crash reachable on every memory-pressure event.

---

## 1. Fixes In Scope (from source plan, all verified sound)

### P0 — Correctness / Safety (ship first, in order)

| ID | Bug | Fix | File:Line (verified) |
|---|---|---|---|
| **P0-1** | Bug 5: `forceReleaseNativeBuffers` SIGSEGV (HIGH, native crash) | Add `nativeGuard.tryLock()`; skip if held (worker frees on completion) | `RoiPageRecognitionEngine.kt:725-731`; mirrors existing `close()` at 689-712 |
| **P0-2** | Bug 1: Stop shows "translating" up to 90s | Synchronous CANCELLED flip in `cancelPageTranslation` | `TranslationScheduler.kt:491-499` |
| **P0-3** | Bug 2: Stop+reconfigure+re-click does nothing | Move `inFlightPageKeys.clear()` to top of `closeEngines` (unconditional) | `TranslationPipeline.kt:449-465` (clear currently only at 459) |
| **P0-4** | Watchdog deadlock if `onPageStuck` throws | Wrap `onPageStuck?.invoke` in try/catch (mirrors onTimeout/onForceRelease) | `TranslationPipeline.kt:286` |

### P1 — Display Correctness

| ID | Bug | Fix | File:Line |
|---|---|---|---|
| **P1-5** | Bug 3: Wrong page number (3 conflicting regexes) | Hybrid index resolver; delete all 3 regex copies | Snapshot.kt:146, BatchTracker.kt:302, ReaderViewModel.kt:286-291 |
| **P1-6** | Bug 4: Manual translate lights up page n+3 | Distinguish permit-holder (RUNNING) from queued (QUEUED) in snapshot | `TranslationProgressSnapshot.kt:125` |

### P2 — Config Consistency

| ID | Bug | Fix | File:Line |
|---|---|---|---|
| **P2-7** | Mid-batch config change doesn't rebuild engines | Set `enginesClosed=true` on EngineSignature change (NOT a cancelling observer — that would evict the batch queue) | `ReaderViewModel.kt:172` (new observer); `EngineSignature` at `TranslationPipeline.kt:339-352` (verified: no `translationEnabled` field) |

### P3 — Hygiene

| ID | Issue | Fix | File:Line |
|---|---|---|---|
| **P3-9** | `consecutiveOomCount` / `currentChapterTranslation` read outside permit without `@Volatile` | Add `@Volatile` | `TranslationPipeline.kt:467-468` |

---

## 2. Verification Design

The source plan's table admitted most fixes are "verified by inspection" or "hard to repro." This design specifies what automated coverage is achievable and what is not, honestly.

### Test infrastructure to establish (one-time, in P0-1)

Two pieces of new infrastructure, needed by multiple fixes:

1. **`MainDispatcherRule`** (or equivalent `TestDispatcher` injector) — does not exist today. Needed for P0-2, P0-3, P1-5, P1-6 which all touch coroutine/scheduler state. Pattern: standard kotlinx-coroutines-test rule pinning `Dispatchers.Main` to a `TestDispatcher`.
2. **`InFlightPageKeysProbe`** test seam — `inFlightPageKeys` is private. P0-3 and P0-4 verification need to observe its contents from a test. Add an `internal` test accessor (same-module visibility, not a public API change).

### Per-fix verification matrix

| Fix | Automatable test? | Approach | If not automatable |
|---|---|---|---|
| **P0-1** (SIGSEGV) | **NO** — native timing window, uncatchable from Kotlin | Verify by **inspection** + a regression test that asserts `nativeGuard.tryLock()` is *called* in `forceReleaseNativeBuffers` (guards against regression by deletion). Manual emulator repro: stress `onTrimMemory` mid-translation, confirm no SIGSEGV. | This is the honest answer — full automated repro needs instrumented memory-pressure injection which doesn't exist. |
| **P0-2** (sync CANCELLED flip) | **PARTIAL** — `runTest`, establish the sync flip happens immediately after `job?.cancel()` before the worker unwinds | New test: `cancelPageTranslationSyncFlipTest` — call cancel, assert store shows CANCELLED within the same test tick (not 90s later). Needs the new MainDispatcherRule. | Re-translate-after-cancel verification is manual (logcat trace). |
| **P0-3** (clear at top) | **YES** — unit testable with the InFlightPageKeysProbe seam | New test: `closeEnginesClearsKeysUnderPermitTest` — hold the permit via a fake, call `closeEngines`, assert `inFlightPageKeys` is empty regardless of permit state. | — |
| **P0-4** (onPageStuck try/catch) | **YES** — verify by structural test + behavior test | Structural: assert all three watchdog callbacks (onTimeout, onPageStuck, onForceRelease) are wrapped. Behavior: throw from a fake `onPageStuck`, assert `onForceRelease` + permit release still run. | — |
| **P1-5** (index resolver) | **YES** — pure function, extend existing test | Add cases to `TranslationProgressSnapshotTest.kt`: `009__002.jpg` → 9, `page-09.png` → 9, `009.jpg` → 9. Test the resolver directly with a `Map<String,Int>`. | — |
| **P1-6** (RUNNING vs QUEUED) | **PARTIAL** — snapshot logic is testable; permit-owner exposure needs a scheduler seam | Test the snapshot picks permit-holder only. Exposing permit-owner identity from the scheduler needs an `internal` accessor. | Visual confirmation of UI labels is manual. |
| **P2-7** (enginesClosed on sig change) | **PARTIAL** — observer wiring is testable; engine rebuild verification is manual (logcat signature mismatch) | Test the observer fires on signature change and sets the flag. | Logcat confirmation of rebuild is manual. |
| **P3-9** (`@Volatile`) | **NO** — annotation, no behavior change | Inspection only. The annotation is correctness hygiene; its absence was a latent stale-read bug, not an observed failure. | — |

### Verification gate criterion

- All **YES** and **PARTIAL** fixes must have their automated tests passing before merge.
- **NO** fixes (P0-1, P3-9) require: (a) a structural regression test (asserts the guard/wrapper/annotation is present), plus (b) inspection notes in the PR description, plus (c) manual emulator repro for P0-1.
- **No fix ships without a structural regression test**, even if behavior isn't automatable. This prevents silent deletion later.

---

## 3. Implementation Route

### Phase 0 — Infrastructure (sequential, blocking)

```
P0-infra: Establish test seams
  - MainDispatcherRule (new, in app/src/test/)
  - InFlightPageKeysProbe (internal accessor on TranslationPipeline)
  Single agent, ~half-day
  Blocks: P0-2, P0-3, P0-4 verification
```

### Phase 1 — P0 Fixes (parallel where safe)

P0-1, P0-3, P0-4 touch different files and have no shared state. P0-2 depends on P0-infra.

```
┌──────────────────────────────────────────────────────────────────┐
│ P0-1: SIGSEGV guard (subagent)                                   │
│   File: RoiPageRecognitionEngine.kt:725-731                      │
│   Add nativeGuard.tryLock() + skip-on-held                        │
│   Test: structural regression (assert tryLock present)            │
│   Manual: emulator memory-pressure repro                         │
└──────────────────────────────────────────────────────────────────┘
┌──────────────────────────────────────────────────────────────────┐
│ P0-3: clear at top (subagent)                                    │
│   File: TranslationPipeline.kt:449-465                           │
│   Move inFlightPageKeys.clear() before tryAcquire                 │
│   Test: closeEnginesClearsKeysUnderPermitTest (needs P0-infra)   │
└──────────────────────────────────────────────────────────────────┘
┌──────────────────────────────────────────────────────────────────┐
│ P0-4: onPageStuck try/catch (subagent)                           │
│   File: TranslationPipeline.kt:286                               │
│   Wrap invoke; mirror onTimeout/onForceRelease                   │
│   Test: watchdogOnPageStuckThrowTest (needs P0-infra)            │
└──────────────────────────────────────────────────────────────────┘

  After P0-infra lands:
┌──────────────────────────────────────────────────────────────────┐
│ P0-2: sync CANCELLED flip (subagent)                             │
│   File: TranslationScheduler.kt:491-499                          │
│   Test: cancelPageTranslationSyncFlipTest (needs P0-infra)       │
└──────────────────────────────────────────────────────────────────┘
```

**Parallelism:** P0-1/P0-3/P0-4 run concurrently (3 agents). P0-2 waits for P0-infra.

### Phase 2 — P1 Display Fixes (parallel)

```
P1-5: index resolver (subagent)        P1-6: RUNNING vs QUEUED (subagent)
  Files: Snapshot, BatchTracker,         Files: TranslationProgressSnapshot,
         ReaderViewModel                          scheduler seam
  Test: extend SnapshotTest              Test: snapshot permit-holder test
```

Independent files, run concurrently.

### Phase 3 — P2 + P3 (single agent, small)

```
P2-7: enginesClosed observer + P3-9: @Volatile annotations
  Single agent — small, related (both ReaderViewModel/TranslationPipeline config-consistency)
```

---

## 4. Cross-Plan Reconciliation

| Item | Overlap | Resolution |
|---|---|---|
| **P0-1 (SIGSEGV) is a hard prereq for AOT Phase 3-2** | AOT NPU dual-session teardown reuses `onMemoryPressure` → `forceReleaseNativeBuffers`. Without P0-1, AOT Phase 3-2 SIGSEGVs. | **P0-1 ships first.** AOT Phase 3 cannot start until P0-1 is merged. |
| P0-3 (clear inFlightPageKeys) vs AOT Phase 0 (copyIfNeeded) | Different files, no conflict. | Parallel-safe. |
| P2-7 (enginesClosed observer) vs AOT model variant loading | Both touch engine lifecycle. P2-7 makes config-change rebuild reliable; AOT adds a model variant. | AOT Phase 2 (variant wiring) benefits from P2-7 being in place but doesn't hard-depend on it. Ship P2-7 first if convenient. |
| P3-9 (`@Volatile`) | Orthogonal. | Anytime. |

---

## 5. Files To Touch

**Phase 0 (infra):**
- `app/src/test/java/.../MainDispatcherRule.kt` (new)
- `TranslationPipeline.kt` (add `internal` InFlightPageKeysProbe accessor)

**Phase 1 (P0):**
- `RoiPageRecognitionEngine.kt:725-731` (P0-1)
- `TranslationPipeline.kt:449-465` (P0-3)
- `TranslationPipeline.kt:286` (P0-4)
- `TranslationScheduler.kt:491-499` (P0-2)
- New tests per §2 matrix

**Phase 2 (P1):**
- `TranslationProgressSnapshot.kt` (P1-5 resolver, P1-6 permit-holder pick)
- `TranslationBatchProgressTracker.kt` (P1-5)
- `ReaderViewModel.kt:286-291` (P1-5 delete regex)
- New test cases in existing test files

**Phase 3 (P2/P3):**
- `ReaderViewModel.kt:172` (P2-7 observer)
- `TranslationPipeline.kt:467-468` (P3-9 @Volatile)

---

## 6. Risks & Assumptions

- **P0-2 assumption** (from source plan): `Mutex.withLock` throws `CancellationException` at the suspend point — verified for kotlinx.coroutines 1.x. If a future version changes this, persist guards become necessary. Low risk.
- **P0-1 is not fully automatable.** The honest trade-off: a structural regression test + manual repro is the achievable bar. A full automated repro would need instrumented memory-pressure injection (`androidTest/` does not exist today — see pipeline design §6). Flagged as a known gap.
- **P1-5 assumption** (from source plan): `orderedStreams` at `ChapterTranslator.kt:452` is in real page order, not reordered by `ResumeOrdering`. Must verify before trusting the resolver.
- **Test-seam visibility:** `InFlightPageKeysProbe` and the permit-owner accessor are `internal`, not public. Same-module test access only; no API surface change.

---

## 7. Out of Scope

- Establishing `app/src/androidTest/` instrumented test infrastructure (large, separate effort; see pipeline design §6).
- Fixing the regex page-number bug at the source (page-layout index propagation) beyond the hybrid resolver — the resolver is the minimal fix.
- The 5 "Refuted Claims" in the source plan — explicitly NOT fixed; the codebase already defends.

---

## 8. Method Note

Source plan audited 2026-07-12: 24/25 claims verified exactly, 1 cosmetic line-off (claim 8: try/catch wrapping at 289-295 not 289-294). All 8 fixes verified technically sound against live code. Test-infrastructure facts verified separately: `runTest` in 6 files/21 sites; zero Semaphore/Mutex test precedent; no shared dispatcher rule; no `androidTest/` dir.

**No code changes made. Design only. Awaiting approval.**
