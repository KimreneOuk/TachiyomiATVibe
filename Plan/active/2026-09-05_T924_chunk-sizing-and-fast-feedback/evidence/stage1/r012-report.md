# T924 Stage 1 / Phase 1b — R012 implementation report (Implementer S1)

Date: 2026-09-05
Base: `adbe643` (branch `t924/batch-profile-pipeline`, worktree
`TachiyomiAT-t924-impl`)
Commit: **`732f7ff`** — "feat(translation): T924-R012 decouple
forced-translation OCR reuse from inpaint readiness"
Requirement: **T924-R012** — forced translation must independently reuse valid
detection/OCR based on current source and configuration evidence, decoupled
from inpaint readiness.

## 1. Stage-0 re-verification (both bullets, worktree at `adbe643`)

1. **Force path couples OCR reuse to inpaint readiness — CONFIRMED.**
   `app/src/main/java/eu/kanade/translation/model/PageWorkPlanner.kt`
   `object PageWorkPlanner`, `fun plan(page, force)` force branch (cited as
   `:30-41`): `val canReuseNative = ocrReady && inpaintReady` with
   `runOcr = !canReuseNative` and `runInpaint = !canReuseNative`. A page with
   `ocrStatus == READY` and non-empty blocks was re-OCR'd whenever the cleaned
   image was missing/stale.
2. **Fingerprint matching passes when expectations are missing — CONFIRMED.**
   Non-force `plan()` delegates to `planPage(BatchPlannerInput(pageKey, page))`
   with the default all-null `BatchExpectedFingerprints`, so
   `fingerprintMatches` (`PageWorkPlanner.kt:366-372`, now `:388-394`) returns
   true and `stageEvidence`'s `takeUnless { !provenanceRequired && record ==
   null && fingerprint == null }` keeps the legacy pass-through.
   `pipeline/batch/BatchResumePlanner.kt:91-108` (`buildBatchPagePlans`) does
   supply `expectedFingerprints = expectedBatchFingerprints` and
   `sourceFingerprint = sourceFingerprints[pageKey]` to `planChapter`.

## 2. Exact diff summary (commit 732f7ff, 2 files, +469/−4)

`app/src/main/java/eu/kanade/translation/model/PageWorkPlanner.kt` (+64/−4):

- `plan()` gained two ADDITIONAL optional parameters (no overload, no renames):
  `expectedFingerprints: BatchExpectedFingerprints = BatchExpectedFingerprints()`
  and `sourceFingerprint: String? = null`.
- Force branch only: `ocrEvidenceValid = ocrReady &&
  forceOcrEvidenceMatches(page, expectedFingerprints, sourceFingerprint)`;
  `runOcr = !ocrEvidenceValid` (decoupled); `canReuseNative` is now
  `ocrEvidenceValid && inpaintReady` and still gates only `runInpaint`.
  Behavior matrix (old → new): the ONLY changed case is
  OCR-ready(+valid evidence) + inpaint-not-ready:
  `runOcr=true,runInpaint=true` → `runOcr=false,runInpaint=true`. The other
  three combinations are identical. Keeping `runInpaint` gated on
  `canReuseNative` preserves R017 (a stale OCR stage drags inpaint, its
  downstream, with it).
- New private helper `forceOcrEvidenceMatches(page, expected, sourceFingerprint)`
  mirroring the batch path's rules exactly: supplied config expectations
  (detection/ocr) must equal the recorded fingerprint, with the batch
  `!provenanceRequired && recorded == null` legacy pass-through; a supplied
  current source hash must equal the recorded `sourceFingerprint`, and a null
  recorded hash under a known current hash does NOT reuse (batch
  UNKNOWN_PROVENANCE rule, `stageEvidence` lines 275-279 pre-change).
- `planPage`/`planChapter`/`decideStage`/`stageEvidence`/`fingerprintMatches`
  are byte-identical — non-force planning cannot change.

`app/src/test/java/eu/kanade/translation/model/PageWorkPlannerForceReuseTest.kt`
(new, 405 lines, 16 tests): (a) force + valid OCR + inpaint PENDING and
metadata-only-cleaned variants ⇒ `runOcr=false, runInpaint=true`; (b) OCR /
detection / source fingerprint mismatch and provenance-required legacy
snapshot ⇒ `runOcr=true`; (c) non-force characterization (fresh page, OCR-only
page, complete page, plus an exact-delegation equivalence assertion for five
page states); (d) no-expectation pass-through and legacy-snapshot pass-through
plus the strict known-source-hash/unknown-provenance rule; plus unchanged
force behaviors (all-valid reuse, stale OCR reruns both, null page).

## 3. Call sites audited (grep over app/src/main/java, incl. translator/, pipeline/, ui/)

- `pipeline/SinglePageOnnxPhase.kt:279` — the ONLY caller of
  `PageWorkPlanner.plan(...)`: `plan(adjustedResume, force)` (positional).
  Compiles unchanged; defaults preserve its evidence inputs. It already
  handles the newly reachable plan (`!runOcr && runInpaint` → resume-inpaint
  from persisted `inpaintMaskBoxes` + translate + render, lines 460-512),
  which is the same code path the non-force resume uses for that state.
  `force` defaults to `true` here, so Manual/Auto reader retries now reuse
  committed OCR instead of re-OCR'ing (design §11 intent). No evidence
  provider is wired into this caller yet (see §6 Deviations).
- `pipeline/batch/BatchResumePlanner.kt:91` (`planChapter`) — supplies
  expected fingerprints; already validated by the untouched batch path
  (mismatch ⇒ FINGERPRINT_MISMATCH ⇒ RUN/no reuse; missing expectations pass
  through). NO changes needed.
- `pipeline/batch/BatchResumeGateDecider.kt:30` and
  `scheduling/TranslationLifecyclePolicy.kt:52` (`planPage`) — untouched,
  unchanged.
- `ui/` and `translator/` — grep found no `PageWorkPlanner` usage
  (`TranslationOverlayView.kt:80` hits `TextLayoutPlanner.plan`, unrelated).
- artifact/**, ChapterTranslationStore.kt, StageFingerprints.kt — NOT touched
  (owned by the parallel WP1 agent); their in-flight uncommitted changes were
  preserved in the worktree and excluded from my commit.

## 4. Build/test invocations and results

- `./gradlew :app:compileStandardDebugKotlin` → **BUILD SUCCESSFUL** (after
  the parallel WP1 agent's artifact/ files stopped being mid-write; the two
  intermediate failures were exclusively in THEIR new untracked
  `artifact/*.kt` files — `Unresolved SidecarPointer/isSha256Hex/isWellFormed`,
  then nullability errors in `AnalysisChunkResult.kt`/`ChapterRunRecord.kt` —
  never in my files).
- `./gradlew :app:testStandardDebugUnitTest --tests
  "eu.kanade.translation.model.*" --tests
  "eu.kanade.translation.coexistence.*"` → **BUILD SUCCESSFUL**, 0 failures:
  model = 114 tests across 15 classes incl. `PageWorkPlannerForceReuseTest`
  (16/16 green) and pre-existing `PageWorkPlannerTest` (10/10 green — the
  non-force regression anchor); coexistence = 39 tests across 15 classes
  (D1-D11, NormalMangaIsolation, StandardLaneMultiPageCompletion,
  D5GlossaryAwareReuse, P5HonestOutcomeTyping, T918CancelledBatchRestart) all
  green.

## 5. Requirement trace

- R012 (decouple force reuse from inpaint readiness, validate evidence when
  supplied, pass-through when not) — implemented; tests (a)-(d) as mandated.
- R016/R021 coexistence — non-force path byte-identical; existing planner and
  coexistence suites green.
- R017 consistency — force inpaint gating keeps OCR→inpaint downstream
  invalidation (stale OCR ⇒ inpaint reruns).

## 6. Deviations / notes / risks

1. **SinglePageOnnxPhase is not yet wired to COMPUTE evidence** (current OCR
   model/language signature → `PageDecode.batchExpectedFingerprints`, source
   hash via `PageDecode.computeSourceFingerprint`). The task scope was
   planner-side ("extend PageWorkPlanner's evidence inputs/API narrowly;
   update BatchResumePlanner call sites only as needed"), and no single-page
   fingerprint provider exists in that class today; wiring one would have
   required new plumbing into `EngineLane`/constructor — a scope decision for
   the preflight-reuse stage (S3) that first consumes R013/R012 together.
   Until then, forced reader-path reuse validates status+payload evidence
   only (the mandated pass-through), which is exactly the pre-R012 force
   strength for every input except the decoupled inpaint dimension.
2. `runInpaint` remains gated on `ocrEvidenceValid && inpaintReady` rather
   than `!inpaintReady` alone: per R017, inpaint is downstream of OCR, so a
   stale OCR stage must drag inpaint. This matches old behavior in 3 of 4
   input combinations and is unobservable in the current caller anyway
   (`runOcr=true` routes to the fused fresh-OCR path regardless of
   `runInpaint`).
3. No conflicts with existing tests were found; no existing test asserts the
   old coupled force behavior.
4. Environment note: two mid-flight compile failures were caused solely by
   the parallel WP1 agent's uncommitted artifact/ files sharing the worktree;
   they landed fixes and the final compile/test runs above are fully green.
   My commit `732f7ff` contains ONLY the two R012 files.
