# T931 Director Report — Is the test suite a bottleneck?

2026-09-14 · HEAD 9c19ad0 · Evidence: team/restrictiveness/report.md,
team/staleness/report.md (all claims file:line-cited). Main leader
independently verified the four decision-relevant claims (CI wiring,
Page15MockRig self-declaration + rig-out writes, D6 arity-7 reflection
bridge, recorded 78.1s/201-class run from test-results XML).

## Verdict

The Director's instinct — tests are taxing development — is RIGHT, but the
diagnosis ("too restrictive or stale legacy") is wrong on both counts.

- **Stale legacy: FALSE.** 0 of 1,914 tests reference deleted machinery as
  live API. T924 deleted dead tests in the same commits as dead code.
  Legacy-migration tests (~1,400 lines) guard a REAL upgrade path still
  live in production (ChapterArtifactStore.kt:206, LegacyArtifactRescue.kt:49).
  Total stale mass ≈ 1.6% of suite lines (one scratch rig + stale KDoc +
  2 trivial flag tests).
- **Too restrictive: FALSE.** ~14 of 1,914 test methods (0.7%) are true
  change-detectors pinning today's exact write mechanics, concentrated in
  6 files. ~98.7% assert durability truth — the state on disk after a
  commit/crash — which is precisely what survives redesigns. No test pins
  persist-before-UI for the chapter store; no test pins manifest-rewrite-
  per-mutation; the only emit-ordering pin ALREADY asserts emit-before-commit
  (TranslationManagerPendingAcknowledgementTest.kt:43-66) — in-repo
  precedent for T930's target shape.

## The real bottlenecks (verified)

1. **CI runs the entire suite TWICE per invocation.** Both workflows run
   `testReleaseUnitTest testStandardReleaseUnitTest`
   (build_pull_request.yml:44, build_push.yml:39); with standard+dev flavors
   the aggregate task already includes the standard variant. ~3–7 min pure
   waste per PR/push.
2. **A 1,018-line scratch debug rig executes inside the test suite.**
   Page15MockRig.kt self-declares "NOT committed, NOT a regression test"
   (:21-22), runs on every suite execution on this machine (fixtures
   present), and writes SVG files into Plan/ as a side effect.
3. **Harness/production reflection coupling.** TranslationCoexistenceHarness
   pins private production fields by name across 17 reflection call sites
   (EngineLane/Pipeline/Scheduler/Manager wiring); a routine field rename in
   TranslationPipeline/EngineLane becomes a 17-file test wave in one commit.
   This is the friction that FEELS like "restrictive tests."
4. **~17s of blind sleeps, 2 genuinely flake-prone patterns**
   (TranslationRequestGenerationFenceTest.kt:244 150ms lock race;
   MangaScreenModelMultiSelectBatchTest.kt:410 250ms blind settle;
   DownloadCacheRenewalGuardTest 16s of sleep/poll).

The suite itself is FAST: median 1ms/test, 78.1s serial for one variant
(recorded 2026-09-03); ~2–4 min today after the T918–T927 wave. Minutes,
not hours. Local builds and commits run NO tests at all.

## What this means for T930

- Slice A (flag OFF): **0 behavioral test conversions.** Two structural
  rules for the implementer: (a) append new constructor params with
  defaults — 40 positional `ChapterTranslationStore(null, null)` call sites
  in 9 files break only on mid-signature inserts; (b) the D6 arity-7
  reflection bridge (D6DrainNotCancelTest.kt:180-196) must become a direct
  constructor call if RollingAutoCoordinator's ctor changes.
- Flag-ON slices: ~12–18 methods need CONVERSION (re-pointed at
  commit-point equivalents), never deletion.
- **The review rule:** ~12% of the suite (~225 tests) is the load-bearing
  race/coexistence safety net. If a T930 slice must edit one of those tests
  to pass, the slice is wrong, not the test. Notably D6DrainNotCancelTest
  and D11PermitFreeCommitTest already pin T930's TARGET semantics
  (drain-to-commit, fail-closed commit) — they are allies, not obstacles.

## Recommended actions (rank: saving/risk)

1. De-dup CI: `testReleaseUnitTest testStandardReleaseUnitTest` →
   `testStandardReleaseUnitTest` in both workflows. (~3–7 min/PR back, near-zero risk.)
2. Evict Page15MockRig from the test source set → tools source set or
   dev-launcher task. (Zero risk; stops Plan/ pollution.)
3. Replace the 2 flake-prone sleeps with deterministic waits; convert
   DownloadCacheRenewalGuardTest polling to `job.join()`. (~17s + trust.)
4. Extract shared page/block/PNG fixtures (56 + 5 + 3 per-file copies →
   one TestPages object). (Reduces T930 fixture churn.)
5. Sequence harness updates WITH T930 commits, not after; promote
   ManualRenderProbeBaseline.kt (currently untracked) into the tracked
   suite as the fast canary.

None of these delete a single test. All five are mechanical, low-risk, and
can run alongside T930 Slice A.
