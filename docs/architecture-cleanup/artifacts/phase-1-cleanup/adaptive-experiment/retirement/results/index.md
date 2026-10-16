---
title: "P1.4 retirement — results and evidence"
kind: spec
comments: none
---

# P1.4 implementation results

Base: `architecture-cleanup` @ `1c1d247e`. Manifest per `adaptive-experiment/retirement/index.md` under the parent's RETIRE decision. 5 logical files / 6 filesystem paths (old+new test names). No new framework, no scheduling/policy claims, no commits/pushes/subagents, no device/model/provider contact.

# Exact change

Tracked `git diff --numstat`: **+10 / −858** (`--check` exit 0); new untracked test file +233 lines. Worktree net ≈ **−615 lines**.

| Filesystem path | Δ | Content |
| --- | --- | --- |
| `app/src/main/java/eu/kanade/translation/pipeline/adaptive/AdaptiveKnobController.kt` | deleted (−170) | Controller + `DeviceActiveStage`/`DeviceStageObservation`/`KnobSignals`/`KnobSignalSnapshot` |
| `app/src/main/java/eu/kanade/translation/pipeline/adaptive/AdaptiveKnobSignalAdapter.kt` | deleted (−167) | Experiment-only global observer; registry and trace producers untouched |
| `app/src/main/java/eu/kanade/translation/pipeline/adaptive/DevicePagePermitGate.kt` | +9 / −6 (169→172 lines) | Co-located `const val DEFAULT_CAPACITY = 1`; constructor default `{ DEFAULT_CAPACITY }`; admission/priority/cancellation/release bodies byte-unchanged; stale E20a wording adjusted |
| `app/src/main/java/eu/kanade/translation/pipeline/TranslationPipeline.kt` | +1 / −8 | Controller/adapter fields + imports + `adapter.close()` removed; default gate now `DevicePagePermitGate()`; `TestConstruction` injected-gate precedence unchanged |
| `app/src/test/java/eu/kanade/translation/pipeline/adaptive/AdaptiveKnobControllerTest.kt` | deleted (−507) | Old mixed suite |
| `app/src/test/java/…/adaptive/DevicePagePermitGateTest.kt` | new (+233) | Renamed suite, 7 live tests, LF-only |

Deleted-symbol scan after edits (all `app` `.kt` + resources/keep-rules/config): zero executable references to `AdaptiveKnobController`, `AdaptiveKnobSignalAdapter`, `KnobSignals`, `KnobSignalSnapshot`, `DeviceStageObservation`, `DeviceActiveStage`, `computedConcurrency`, `appliedConcurrency`, `DEFAULT_FLOOR`, `warmedController`. One documentation-only mention remains OUTSIDE the manifest: a stale comment at `TranslationCoexistenceHarness.kt:224` naming `AdaptiveKnobSignalAdapter.forContext`; the functional mock stub below it (line 225, `context.applicationContext`) is unrelated to the adapter and untouched, per the no-additional-harness-edits bound. Flagged for a later docs-touching slice.

# Preserved invariants (diff/reference comparison)

- Gate operational bodies byte-unchanged: acquire/waiter states/cancellation `NonCancellable` cleanup/foreground wait cap/priority-ordered grant/`Permit.markReleased`/`releaseLocked` — diff shows only the capacity default, the companion const, and the KDoc wording.
- `TranslationPipeline.withNativeLane` admission (:310), MANUAL→FOREGROUND priority (:594-598), `SinglePageHttpRenderPhase` acquire (:113-114) and release/reacquisition protocol untouched; `TestConstruction.devicePagePermitGate` injection precedence untouched.
- `DeviceStageNormalization`, diagnostics taxonomy/observers/registry, `TranslationMemoryBudget` memory admission, stage normalization setters, EngineLane/NativeRunQuarantine/engine/store code: untouched (outside the 6-path manifest).

# Validation

Selectors both phases: `--tests "eu.kanade.translation.pipeline.adaptive.*" --tests "eu.kanade.translation.coexistence.*"`, `--rerun` fresh compilation, DevDebug then StandardDebug sequentially per shell. Baseline recorded BEFORE any edit.

| Run | Shell | Exit | Result |
| --- | --- | --- | --- |
| Pre-baseline DevDebug | `d646d8bc` | 0 | 19 classes / 61 tests / 0 failures |
| Pre-baseline StandardDebug | same shell | 0 | 19 classes / 61 tests / 0 failures |
| Post DevDebug | `d98235b5` | 0 | fresh `compileDevDebugKotlin` + `compileDevDebugUnitTestKotlin`; BUILD SUCCESSFUL 1m 1s; **19 classes / 54 tests / 0 failures** |
| Post StandardDebug | same shell | 0 | fresh `compileStandardDebugKotlin` + `compileStandardDebugUnitTestKotlin`; BUILD SUCCESSFUL 1m 2s; **19 classes / 54 tests / 0 failures** |

Per-class accounting (both variants identical):

- Coexistence package: **18 classes / 47 tests — unchanged, all green** (the justified regression surface: pipeline construction lost one global observer).
- Adaptive package: `AdaptiveKnobControllerTest` (14 tests) → `DevicePagePermitGateTest` (**7 tests**, class count 1→1 via rename): 7 computed-only/adapter tests retired with their exclusively-used helpers (`warmedController`, `observe`, `observation`, `record`, `TranslationTraceProvider` import); 7 live tests retained with original assertions and `FakeClock`; the FIFO test's controller warm-up/computed assertions were removed and it now uses the fixed default `DevicePagePermitGate()`, retaining the FIFO/release assertions; test 1's constant reference now `DevicePagePermitGate.DEFAULT_CAPACITY`.

Total: 61→54 tests (−7, exactly the retired experiment tests); no unexpected failures; nothing retried away.

# Evidence paths (epic 699de408-00ac-4fe5-a903-b541c23063cc)

- Reviewer diff: `scratch/phase-1-cleanup/adaptive-experiment/retirement/p1.4-actual.diff` (new test file is untracked in `git diff`; its full content is in the worktree at the path above)
- Pre-baseline XML: `scratch/phase-1-cleanup/adaptive-experiment/retirement/pre-baseline/{devdebug,standarddebug}/` (19 files each)
- Post XML: `scratch/phase-1-cleanup/adaptive-experiment/retirement/post-change/{devdebug,standarddebug}/` (19 files each)
- Run logs: `commands/699de408-00ac-4fe5-a903-b541c23063cc/{d646d8bc,d98235b5}/output.log`
- Audit (with the four parent-decision wording corrections applied): `artifacts/phase-1-cleanup/adaptive-experiment/audit/index.md`
- User report re-hashed after all work: SHA256 `EFC11B90A7D0B367F3878BD600BF893AA99ECCDB5879D902E10EB6E06EF88DDD` — byte-identical. `git status` = exactly the 6 manifest paths + the untracked report; HEAD `1c1d247e`.

# Limits and notes

- Test file count unchanged (315 .kt on disk) via rename; main translation scope 286→284 sources (282 .kt + 2 .java) — matches ticket forecast.
- The stale harness comment (documentation-only) is the only remaining textual reference to a deleted symbol; left by manifest bound.
- Phase 0 ledger and original XML untouched; no other slices or P1.5 work started.

# Next

Slice complete; stopping for fresh Gemini/Antigravity review of the actual diff before any commit.
