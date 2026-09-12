# Master Implementation Plan — Translation Quality & Performance

> **SUPERSEDED (2026-09-12):** historical July-era proposal pack — NOT the
> current plan. The Batch translation architecture it reasoned about was
> replaced by T924's chapter-profile pipeline, and the legacy
> SequentialBatchCoordinator it targets has been deleted. The current plan
> of record is `Plan/active/2026-09-05_T924_chunk-sizing-and-fast-feedback/`
> (start at its `README.md` and `implementation-sequence.md`). Some
> investigation material in this pack (AOT/inpainting/efficiency analyses)
> may retain background value; nothing here is implementation guidance.

**Date:** 2026-07-12
**Scope:** Implementation. Sequenced execution plan across all four design docs, with git checkpoints at every safe-to-review boundary.
**Source designs:**
- `AOT_NPU_FIXED512_DESIGN_2026-07-12.md`
- `TRANSLATION_RACE_CONDITION_DESIGN_2026-07-12.md`
- `PIPELINE_EFFICIENCY_DESIGN_2026-07-12.md`
- `INPAINTING_EFFICIENCY_DESIGN_2026-07-12.md`

**Test contracts:** `SUBAGENT_TEST_CONTRACTS_2026-07-12.md` — every subagent's regression baseline, RED-first test, exact assertions, and testability workarounds. **Required reading for every subagent.** Without it, a subagent has no falsifiable definition of "done."

**Status:** Awaiting approval. Not initiated.

---

## Executive Summary

This is **one execution plan for four reconciled designs.** They cannot be executed independently because of hard cross-dependencies:
- Race-condition **P0-1** (SIGSEGV) is a hard prerequisite for AOT NPU Phase 3-2.
- Inpainting **P0** (copyIfNeeded) and **P2** (onnxslim) are absorbed into AOT NPU Phases 0-1 and 1-A.
- Inpainting **P1.5** (pool feeding arrays) must ship before AOT NPU Phase 1-C rewrites the feeding loop.

**Structure:** 6 waves. Each wave is a git-checkpointed unit (branch → implement → commit → ready-for-review). Within a wave, independent fixes run as parallel subagents. Waves are ordered by dependency; parallelism is maximized within each wave.

**Git discipline:** Every wave starts on a fresh branch off `main` (or off the prior wave's merge). Every commit is a reviewable, reversible unit. Massive changes are gated behind explicit checkpoints — no wave proceeds until the prior wave is merged or explicitly excepted.

**Honest scope:** This is 2-4 weeks of focused work across 6 waves, ~4 subagents at peak parallelism, with 3 hard quality gates (Tier 2 numerics, Tier 3 corpus, Tier 4 on-device). Waves 1-3 are no-regret and shippable even if Wave 4-6 (AOT NPU) is abandoned. Wave 6 (on-device) requires physical/emulator devices.

---

## Branch Strategy

**Convention:** `fix/...` for bug fixes, `perf/...` for performance, `feat/...` for new capability. Match the project's existing style (`feat/reader-translation-flexibility`, `Quality-Improvement`).

**Rule:** Each wave = one branch. Each task within a wave = one commit (or a small set). A wave merges before the next wave branches, *unless* the next wave is explicitly parallel-safe.

```
main
 ├─ Wave 1: fix/translation-race-p0          (highest severity, hard prereq)
 ├─ Wave 2: fix/translation-inpainting-safe   (parallel-safe with Wave 1 tail)
 ├─ Wave 3: perf/translation-pipeline-safe    (parallel-safe with Wave 2)
 ├─ Wave 4: perf/translation-deployment-fix   (copyIfNeeded — unblocks AOT)
 ├─ Wave 5: feat/translation-aot-npu          (the big one; depends on W1+W4)
 └─ Wave 6: release-gate                      (manual, on-device)
```

**Commit message style** (from `git log`): `feat(translation): ...`, `refactor(translation): ...`. Match it.

---

## Wave 0 — Commit Existing Design Docs

**Branch:** `docs/translation-plan-docs`
**Why first:** The 4 design docs are currently untracked (`git status` shows `??`). They should land on `main` as the reference before any code branch diverges, so every implementation branch can point at a stable doc commit.

**Tasks:**
1. Create `docs/translation-plan-docs` off `main`.
2. `git add Plan/*DESIGN*.md Plan/*INVESTIGATION*.md`
3. Commit: `docs(translation): add audited investigation reports and implementation designs`
4. Open PR → merge to `main`.

**Checkpoint:** ✅ Docs on main. All subsequent branches off main reference stable doc commit.

---

## Wave 1 — Race-Condition P0 Fixes (Highest Severity + Hard Prereq)

**Branch:** `fix/translation-race-p0`
**Why first:** P0-1 (SIGSEGV) is the most severe finding across all four plans AND a hard prereq for AOT NPU Phase 3-2. P0-2/P0-3/P0-4 are quick wins. Ship together as one safety-focused branch.

**Phases within Wave 1:**

### Phase 0 — Test infrastructure (sequential, blocks P0-2/3/4 verification)

| Task | Detail |
|---|---|
| Add `MainDispatcherRule` | New file `app/src/test/java/.../MainDispatcherRule.kt`. Standard kotlinx-coroutines-test rule pinning `Dispatchers.Main` to a `TestDispatcher`. No shared rule exists today. |
| Add `InFlightPageKeysProbe` | `internal` test accessor on `TranslationPipeline` for `inFlightPageKeys`. Same-module visibility; no public API change. |
| **Commit:** | `test(translation): add MainDispatcherRule and inFlightPageKeys test seam` |

### Phase 1 — P0-1, P0-3, P0-4 in parallel (3 subagents, no shared files)

| Subagent | Task | File:Line | Test |
|---|---|---|---|
| **A (P0-1 SIGSEGV)** | Add `nativeGuard.tryLock()` in `forceReleaseNativeBuffers`; skip if held. Mirror `close()` at 689-712. | `RoiPageRecognitionEngine.kt:725-731` | Structural regression: assert tryLock present. Manual emulator repro (stress `onTrimMemory` mid-translation). |
| **B (P0-3 clear-at-top)** | Move `inFlightPageKeys.clear()` to top of `closeEngines`, unconditional. Remove redundant clear at 459. | `TranslationPipeline.kt:449-465` | `closeEnginesClearsKeysUnderPermitTest` (uses the new probe). |
| **C (P0-4 watchdog guard)** | Wrap `onPageStuck?.invoke` at line 286 in try/catch, mirroring onTimeout (275-281) and onForceRelease (289-295). | `TranslationPipeline.kt:286` | `watchdogOnPageStuckThrowTest`: throw from fake `onPageStuck`, assert `onForceRelease` + permit release still run. |

**Commits (one per subagent):**
- `fix(translation): guard forceReleaseNativeBuffers with nativeGuard to prevent SIGSEGV`
- `fix(translation): clear inFlightPageKeys unconditionally in closeEngines`
- `fix(translation): wrap watchdog onPageStuck in try/catch to prevent deadlock`

### Phase 2 — P0-2 (depends on Phase 0 infra)

| Task | Detail |
|---|---|
| Add synchronous CANCELLED flip in `cancelPageTranslation` | `TranslationScheduler.kt:491-499`. Sketch in race-condition design §1. Guard mirrors `markPageCancelled` (hasRenderedResult \|\| isStageFailed). |
| **Test:** | `cancelPageTranslationSyncFlipTest`: call cancel, assert store shows CANCELLED within the same test tick (not 90s later). Uses MainDispatcherRule. |
| **Commit:** | `fix(translation): flip page to CANCELLED synchronously on stop` |

### Phase 3 — Wave 1 Merge Gate

**Before merging Wave 1 to main:**
- ✅ All Phase 1/Phase 2 tests pass (`./gradlew :app:testDebugUnitTest`).
- ✅ CI build passes (`.github/workflows/build_pull_request.yml`).
- ✅ Manual emulator repro for P0-1 (run translation, fire `onTrimMemory` mid-inference, confirm no SIGSEGV).
- ✅ PR review.

**Checkpoint:** 🟢 Wave 1 merged. **AOT NPU's hard prereq is now satisfied.** Race-condition P1/P2/P3 (display fixes) deferred to Wave 2.

---

## Wave 2 — Inpainting Safe Wins + Race-Condition P1/P2/P3 (Parallel-Safe)

**Branch:** `perf/translation-inpainting-safe` AND `fix/translation-race-display` (two branches, parallel)

**Why now:** Wave 2 items are independent of Wave 1's merge (different files) and independent of AOT NPU. Running two parallel branches maximizes throughput.

### Branch 2A: `perf/translation-inpainting-safe`

Single agent, related AOT-session changes:

| Task | Detail | Test |
|---|---|---|
| P1 spinning-off | Reorder `configure` lambda before EP branch OR add `allowSpinning` param in `OnnxRuntimeProvider.kt`. Pass `addConfigEntry("session.intra_op.allow_spinning", "0")` in `AOTInpainting.kt:111` before `addXnnpack()`. | Structural: assert spinning-disable scoped to AOT only (4 NNAPI models + OCR sessions unaffected). Manual timing (≥20 inferences averaged; neutral acceptable). |
| P1.5 pool feeding IntArrays | Pool `imgPixels`/`maskPixels` at `AOTInpainting.kt:514, 516` (mirror postprocess pooling at 63-75). | Output-identical test; no stale-pixel bleed. |
| P3 ORT 1.21→1.23 | `gradle/libs.versions.toml:17`: bump version ref. | Build verification + existing test suite + on-device smoke (manual). |

**Commits:**
- `perf(translation): disable intra-op spinning for AOT XNNPACK session`
- `perf(translation): pool AOT feeding-loop IntArrays to reduce heap churn`
- `build(translation): upgrade onnxruntime-android 1.21 → 1.23`

**⚠️ ORDERING NOTE:** P1.5 MUST land before AOT NPU Phase 1-C rewrites the feeding loop. Wave 2A merges before Wave 5 branches. ✅ Satisfied by wave ordering.

### Branch 2B: `fix/translation-race-display`

| Subagent | Task | File:Line | Test |
|---|---|---|---|
| **D (P1-5 index resolver)** | Hybrid optional `indexResolver: Map<String,Int>?` param. Delete all 3 regex copies. Thread resolver from `ChapterTranslator.kt:452` and `ReaderViewModel` pages. | Snapshot.kt:146, BatchTracker.kt:302, ReaderViewModel.kt:286-291 | Extend `TranslationProgressSnapshotTest.kt`: `009__002.jpg`→9, `page-09.png`→9, `009.jpg`→9. |
| **E (P1-6 RUNNING vs QUEUED)** | Snapshot picks permit-holder only; prefetched pages show QUEUED. Needs scheduler `internal` accessor for permit-owner. | `TranslationProgressSnapshot.kt:125` | Snapshot permit-holder test. |
| **F (P2-7 enginesClosed observer + P3-9 @Volatile)** | Add EngineSignature-change observer that sets `enginesClosed=true` only (NOT cancelling — would evict batch queue). Add `@Volatile` to `consecutiveOomCount` + `currentChapterTranslation`. | `ReaderViewModel.kt:172`, `TranslationPipeline.kt:467-468` | Observer-fires test; inspection for `@Volatile`. |

**Commits:**
- `fix(translation): resolve page index via real map, delete conflicting regexes`
- `fix(translation): distinguish translating-now vs queued in progress snapshot`
- `fix(translation): rebuild engines on signature change; volatile-annotate shared fields`

### Phase 2 Merge Gate (both branches)

- ✅ All tests pass on both branches.
- ✅ CI passes on both.
- ✅ Manual: page-number display correct on `009__002.jpg`-style filenames; logcat confirms engine rebuild on config change.
- ✅ Both PRs reviewed and merged.

**Checkpoint:** 🟢 Wave 2 merged. All race-condition + inpainting safe wins shipped.

---

## Wave 3 — Pipeline Safe Wins (Parallel-Safe with Wave 2)

**Branch:** `perf/translation-pipeline-safe`

**Why separate from Wave 2:** Different files (RenderColorEstimator, MangaOcrEngine, GoogleTranslator) and one fix (P2a) needs a refactor. Kept on its own branch for review clarity.

| Subagent | Task | File:Line | Test |
|---|---|---|---|
| **G (P1a dedup)** | Merge duplicate `extractClusters` + `bubbleInteriorMask` in `RenderColorEstimator.estimate()`. Feed both `colorPolicy` + `sampleBackgroundLuma` from one call. | `RenderColorEstimator.kt:127-132, 152, 170` | Snapshot-then-diff against `RenderColorEstimatorSamplingTest` matrix. **Bit-identical output required** (pure dedup). |
| **H (P1b JPEG off permit)** | Move `compress(JPEG,90)` from `inpaintPage` (~2463) and `processSinglePage` (~2597) to after `send()` / in HTTP phase. | `TranslationPipeline.kt:2463, 2597` | Ordering test: `cleanedImageName` set before `tryRender`. Manual: logcat `[inpaint_encode]` timing confirms move. |
| **I (P2a MangaOcr pool)** | **Refactor first:** extract `preprocess` pixel-write loop into pure function taking `IntArray`. Then single-pass direct-write to `inputPixelPool`. Eliminate `FloatArray(1*3*224*224)` at 364. | `MangaOcrEngine.kt:329-387, 364-379` | Bit-identical output test on extracted pure function (CHW preserved). |
| **J (P2b Google batch) — OPTIONAL/DEFER** | Inject `OkHttpClient` + base URL into `GoogleTranslator`. Make `translateBatch` internal. Add MockWebServer dep. Concatenate blocks + URL-length cap. | `GoogleTranslator.kt:14, 20, 26-30, 33, 68` | MockWebServer: single request for multi-block; chunking at cap. **DEFER unless Google users are meaningful segment** (Google isn't default — MLKit is). |

**Parallelism:** G, H, I are file-disjoint → 3 subagents in parallel. J is optional/last.

**Commits:**
- `perf(translation): merge duplicate extractClusters in RenderColorEstimator`
- `perf(translation): move JPEG encode off translation permit`
- `refactor(translation): extract MangaOcr preprocess pure function; pool FloatArray`
- (conditional) `feat(translation): batch Google translator requests`

### Phase 3 Merge Gate

- ✅ All tests pass (especially P1a bit-identical).
- ✅ CI passes.
- ✅ Manual: logcat timing shows encode moved out of permit window; MangaOcr output unchanged.
- ✅ PR reviewed.

**Checkpoint:** 🟢 Wave 3 merged. All safe performance wins shipped. **At this point, every "safe, ship-now" item from all four plans is done.** Waves 4-6 are the AOT NPU project.

---

## Wave 4 — Deployment Fix (copyIfNeeded — Unblocks AOT)

**Branch:** `fix/translation-model-deployment`
**Why its own wave:** This is the systemic prereq for ALL model changes. It's independently correct (a latent bug fix) but its *value* is unlocking Wave 5. Ship separately so the model-variant wiring in Wave 5 builds on a merged, reviewed base.

**Single agent (coherent change to one file):**

| Task | Detail | Test |
|---|---|---|
| Version-stamp copyIfNeeded | Add a sibling `.version` file containing `BuildConfig.VERSION_CODE` + model content hash (SHA-256 of first N KB — cheaper than full hash, sufficient for change detection). `copyIfNeeded` re-copies on mismatch. Handles missing stamp (legacy cache) by treating absence as "always re-copy once." | **Blocker:** `OnnxModelStore` takes Android `Context` — NOT JVM-testable as-is. **Refactor required:** inject base `File` dir + asset-open abstraction (or extract `copyIfNeeded` logic into a pure helper taking `InputStream` + `File`). Then `OnnxModelStoreVersionTest`: re-copies on mismatch; keeps cache on match; handles missing stamp. |

**Commit:**
- `refactor(translation): extract copyIfNeeded pure helper for testability`
- `fix(translation): version-stamp model cache to propagate asset updates`

### Phase 4 Merge Gate

- ✅ Version-stamp tests pass.
- ✅ CI passes.
- ✅ Manual: install old build, then install new build over it; logcat shows "Copying model from assets" on UPDATE (not just fresh install) when a model asset changed; shows no re-copy when unchanged.
- ✅ PR reviewed.

**Checkpoint:** 🟢 Wave 4 merged. **Model updates now reach existing users.** Wave 5 unblocked.

---

## Wave 5 — AOT NPU (The Big One)

**Branch:** `feat/translation-aot-npu`
**Why last:** Largest scope, 3 internal gates, depends on Wave 1 (P0-1) + Wave 4 (copyIfNeeded) + Wave 2A (P1.5 pool arrays).

**Internal phases mirror AOT NPU design §5. Each phase is a commit (not a separate branch — too tightly coupled to split across branches). Git checkpoints are per-commit.**

### Phase 5.1 — Three Parallel Workstreams (no shared state)

| Subagent | Task | Output | Gate |
|---|---|---|---|
| **K (model conversion)** | Offline Python: convert `aot.onnx` → `aot-512.onnx` (static `[1,3,512,512]`). Run onnxslim constant folding. Run **Tier 2 numerics test**. | `app/src/main/assets/models/inpainting/aot-512.onnx` | max-abs-diff < 1e-3 across corpus samples |
| **L (corpus + harness)** | Curate 20-page corpus into `app/src/test/assets/corpus/aot/`. Build **Tier 3 guard-rejection harness** (JVM, uses `AotOutputGuard`). Establish baseline: dynamic model on corpus. | corpus + `baseline_report.json` | baseline recorded |
| **M (geometry + pad path)** | Fix `centeredReportCrop` sub-512 crash. Add bg-color pad-to-512 + crop-back in `inpaint()`. Pool sizing. | `AotBoxGeometry.kt`, `AOTInpainting.kt` | Tier 1 unit tests pass (extend `AotBoxGeometryTest`, new `AotPadPathTest`) |

**⚠️ M inherits P1.5's pooled arrays** (Wave 2A) — confirmed by wave ordering.

**Commits:**
- `feat(translation): add static-512 AOT model variant`
- `test(translation): add 20-page AOT quality corpus and guard-rejection harness`
- `fix(translation): handle sub-512 pages in centeredReportCrop; add bg-color 512 pad path`

### Phase 5.2 — Integration (single agent, after all 5.1 done)

| Task | Detail | Gate |
|---|---|---|
| Wire static-512 model | `OnnxModelStore`: add `aot-512.onnx` variant (version-stamped via Wave 4). `AOTInpainting`: load 512-static, feed padded, crop back. | **Tier 3 corpus gate:** run harness against integrated path. Zero new guard rejections. |

**Commit:** `feat(translation): wire static-512 AOT model into inpaint pipeline`

**🔴 HARD GATE — do NOT proceed on failure.** If Tier 3 shows new rejections:
- Diagnose: model (loop to K) or pad path (loop to M).
- If unresolvable, abandon Wave 5; Waves 1-4 are still shippable.

### Phase 5.3 — NNAPI EP + Fallback Cascade (single agent, sequential)

| Task | Detail | Gate |
|---|---|---|
| NNAPI capability probe + Layer 0/1 gate | New `NnapiCapabilityGate` (probe session, inspect coverage/partitions). Device detection beyond dead `isQualcommSnapdragon`. | Tier 1: `NnapiCapabilityGateTest` boundary cases |
| Dual-session holder + Layer 2/3 runtime fallback | `AOTInpainting` holds NNAPI + XNNPACK sessions. Per-inference: NNAPI → guard → XNNPACK fallback on reject. Memory-pressure teardown: NNAPI first (**safe because Wave 1 P0-1 landed**). | Inspection + structural test |
| Layer 4 aggregate health monitor | Rolling window of guard verdicts; auto-disable NNAPI on systematic failure (>20%). | Inspection |

**Commits:**
- `feat(translation): add NNAPI capability probe and device gate`
- `feat(translation): hold dual NNAPI+XNNPACK AOT sessions with runtime fallback`
- `feat(translation): auto-disable NNAPI on systematic guard-rejection`

### Phase 5.4 — Op Surgery (CONDITIONAL)

**Only if Phase 5.3 on-device testing (Wave 6) shows NNAPI partition-stall dominates.** Otherwise skip.

| Task | Gate |
|---|---|
| Replace 4 ConvTranspose + fold Shape/ReduceProd. Offline Python. | **Re-run Tier 2 (stricter — op replacement changes math). Re-run Tier 3.** |

**Commit (if executed):** `feat(translation): replace ConvTranspose ops for NNAPI compatibility`

### Phase 5 Merge Gate (internal to the branch)

- ✅ All Tier 1 tests pass.
- ✅ Tier 2 numerics gate passed (5.1).
- ✅ Tier 3 corpus gate passed (5.2).
- ✅ CI passes.
- ✅ Code review.
- Do NOT merge to main until Wave 6 on-device gate passes.

**Checkpoint:** 🟡 Wave 5 ready for on-device validation. Not yet merged.

---

## Wave 6 — Release Gate (On-Device, Manual/Instrumented)

**Not a branch — this is the validation before Wave 5 merges.**

**Devices (minimum):** one Qualcomm flagship, one MediaTek, one old/emulated ARMv7.

**Per device:**
1. Install Wave 5 build.
2. Verify `copyIfNeeded` re-copied (logcat: "Copying model from assets" on update).
3. Translate 5 chapters including corpus pages.
4. Check logcat: EP selected (NNAPI on capable, XNNPACK fallback on others), guard rejections, fallback invocations.
5. Confirm: no SIGSEGV, no OOM, no 90s stalls.
6. Visual check: translations render correctly.

**🔴 HARD GATE.** If any device crashes or shows visual regression:
- Do not merge Wave 5.
- Diagnose; loop to Wave 5 appropriate phase.
- If unresolvable on a device class, expand the Layer 0 device gate to exclude it.

**Checkpoint:** ✅ Wave 6 passes → merge Wave 5 to main. Release.

---

## Dependency Graph (visual)

```
Wave 0 (docs)
   ↓
Wave 1 (race P0: SIGSEGV + 3 fixes)  ←── hard prereq for Wave 5.3
   ↓
   ├──────────────────┐
Wave 2A (inpaint safe)│  ←── P1.5 prereq for Wave 5.1
   │                  │
   │              Wave 2B (race display P1/P2/P3)
   │                  │
   ├──────────────────┘
   ↓
Wave 3 (pipeline safe: P1a/P1b/P2a/P2b)
   ↓
Wave 4 (copyIfNeeded)  ←── hard prereq for Wave 5
   ↓
Wave 5 (AOT NPU)
   │  ├─ 5.1: 3 parallel (model / corpus / pad path)
   │  ├─ 5.2: integration + Tier 3 gate
   │  ├─ 5.3: NNAPI + fallback
   │  └─ 5.4: op surgery (conditional)
   ↓
Wave 6 (on-device gate) → merge Wave 5
```

---

## Git Checkpoint Protocol

**Before each wave:**
1. Confirm prior wave merged to `main` (or explicitly excepted as parallel).
2. Branch fresh off `main`: `git checkout main && git pull && git checkout -b <wave-branch>`.
3. Verify clean: `git status`.

**During each wave:**
- One commit per task (subagent). Small, reviewable, reversible.
- **Every subagent follows the test contract** (`SUBAGENT_TEST_CONTRACTS_2026-07-12.md`): (1) capture green baseline, (2) write the RED-first test, (3) implement to GREEN, (4) full suite stays green, (5) commit.
- After each commit: run `./gradlew :app:testDebugUnitTest` (or targeted test task) — verify before the next task.
- After parallel subagents finish: run full test suite + CI before merge.

**Before each merge:**
- Tests pass locally.
- CI passes (`.github/workflows/build_pull_request.yml`).
- Manual repro for items marked "manual" in the gate.
- PR review.

**Rollback:**
- Any wave can be reverted independently (separate branch/merge).
- Waves 1-4 are no-regret — reverting any leaves the others valuable.
- Wave 5 is the riskiest; if abandoned after merge, revert the Wave 5 merge commit (Wave 4's copyIfNeeded fix remains valuable independently).

---

## Parallelism Summary

| Wave | Peak parallel subagents | Duration estimate |
|---|---|---|
| 0 | 1 | minutes |
| 1 | 3 (Phase 1) then 1 (Phase 2) | 2-3 days |
| 2 | 3 (branch 2B) + 1 (branch 2A) = 4 | 2-3 days |
| 3 | 3 (G/H/I, optional J) | 2-3 days |
| 4 | 1 | 1 day |
| 5 | 3 (5.1) then 1 (5.2) then 1 (5.3/5.4) | 5-8 days + device time |
| 6 | manual | 1-2 days |

**Total:** ~2-4 weeks elapsed, depending on device availability and gate outcomes.

---

## Hard Gates Summary (do-not-proceed conditions)

| Gate | Location | If failed |
|---|---|---|
| Wave 1 P0-1 manual repro | Before Wave 1 merge | Do not merge; SIGSEGV not proven fixed. |
| Wave 2 P1a bit-identical | Before Wave 3 merge | Do not merge; dedup changed output. |
| Wave 4 manual update test | Before Wave 4 merge | Do not merge; deployment bug not proven fixed. |
| **Wave 5.2 Tier 3 corpus** | Before Wave 5.3 | **Abandon Wave 5; loop to 5.1.** |
| **Wave 5.4 Tier 2/3 (if executed)** | Before Wave 6 | Loop to 5.4. |
| **Wave 6 on-device** | Before Wave 5 merge to main | **Do not release.** |

---

## What's Shippable If We Stop Early

| Stop after | Shippable value |
|---|---|
| Wave 1 | SIGSEGV fixed + 3 race bugs fixed. Highest-severity work done. |
| Wave 2 | + Inpainting safe wins + all race-condition display fixes. |
| Wave 3 | + All pipeline safe wins (~35-88ms/page). **All "safe, ship-now" items done.** |
| Wave 4 | + copyIfNeeded deployment bug fixed (independently correct, unblocks future). |
| Wave 5 | + AOT on NPU (if gates pass). |
| Wave 6 | Released. |

**No-regret line is Wave 4.** Everything through Wave 4 is positive value with no quality risk. Wave 5+ is where quality risk lives (GAN model changes), gated by the corpus + on-device validation.

---

## Reconciliation With Source Designs

| Design item | Wave | Notes |
|---|---|---|
| Race P0-1 (SIGSEGV) | W1 | Hard prereq for W5.3 |
| Race P0-2/3/4 | W1 | |
| Race P1-5/6, P2-7, P3-9 | W2B | |
| Inpainting P0 (copyIfNeeded) | **W4** (not W2) | Moved to its own wave — systemic, unblocks W5 |
| Inpainting P1 (spinning) | W2A | |
| Inpainting P1.5 (pool arrays) | W2A | Prereq for W5.1 |
| Inpainting P2 (onnxslim) | **W5.1** (not W2) | Absorbed into AOT model conversion |
| Inpainting P3 (ORT upgrade) | W2A | |
| Pipeline P1a/P1b | W3 | |
| Pipeline P2a | W3 | Needs preprocess refactor |
| Pipeline P2b | W3 (optional) | Defer unless Google users meaningful |
| AOT NPU Phase 0-1 (copyIfNeeded) | = W4 | Unified — single execution |
| AOT NPU Phase 1-A/C | W5.1 | |
| AOT NPU Phase 2 | W5.2 | |
| AOT NPU Phase 3 | W5.3 | Depends on W1 P0-1 |
| AOT NPU Phase 4 | W5.4 (conditional) | |
| AOT NPU Phase 5 | W6 | |

---

## Risks & Assumptions

- **Wave 1 P0-1 is not fully automatable.** Structural test + manual repro is the honest bar. Full automated repro needs instrumented memory-pressure injection (`androidTest/` doesn't exist — separate effort).
- **Wave 2A P1 gain (~1-5%) is an estimate,** possibly neutral on big.LITTLE devices. Safe to try; not a guaranteed win.
- **Wave 3 P2a refactor risk:** extracting `preprocess` changes its signature. Bit-identical output test is the guard.
- **Wave 4 `OnnxModelStore` refactor:** injecting Context dependencies is a signature change. Must not break the 12 call sites. Test thoroughly.
- **Wave 5.2 Tier 3 gate is the project's main quality risk.** If the static-512 model produces guard-rejections the dynamic model didn't, Wave 5 fails. The 20-page corpus is the defense; it MUST cover dense text, screentone, color, small pages, asymmetric pages.
- **Wave 5.3 NNAPI driver quality:** older Mali/Adreno drivers claim graphs then produce garbage. Layer 4 aggregate monitor is the defense; cannot be fully tested without real devices (Wave 6).
- **Wave 5.4 op-count table unverified** (audit Issue 3). If "4 ConvTranspose / 23 partitions" is wrong, Wave 5.4 scope is wrong. Re-verify with live `onnxruntime` capability check in Wave 5.1.
- **Speed estimates are estimates.** No on-device benchmarking exists. Waves 2A/3 gains are ~35-88ms/page total — noticeable but not transformative. Wave 5 NPU gain is the biggest but unverified until Wave 6.
- **Total elapsed time depends on device availability** for Waves 5.6 and 6. If devices aren't available, Wave 5 cannot merge.

---

## Out of Scope (Deferred)

- On-device benchmarking infrastructure (would benefit all waves' verification).
- `androidTest/` instrumented test infrastructure (large, separate effort).
- F8-OptionA (delete first-pass color estimate) — needs guard test proving every `recomputeFor` path (4 sites, corrected from source plan's "5").
- FP16, INT8 PTQ, model distillation, QAT — needs training pipeline or on-device verification that doesn't exist.
- QNN/HTP path — user chose NNAPI-broad; revisit only if NNAPI proves insufficient.
- Centralizing the 4 existing pad implementations — pure refactor, no behavior change, separate effort.

---

## Method Note

This plan sequences four audited designs ( Race Condition: 24/25 verified; Pipeline: 21/24 verified + 1 factual error; Inpainting: both BLOCKERs verified + 2 issues found; AOT NPU: design-only). Cross-design reconciliation done: copyIfNeeded unified into Wave 4; onnxslim absorbed into Wave 5.1; SIGSEGV fix (W1) confirmed as hard prereq for AOT NPU W5.3. Git facts verified: on `main`, clean except 4 untracked design docs, CI exists (build_pull_request + build_push workflows), feature-branch convention in use.

**No code changes made. Plan only. Awaiting approval.**
