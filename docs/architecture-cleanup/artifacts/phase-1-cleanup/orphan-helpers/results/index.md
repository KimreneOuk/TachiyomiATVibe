---
title: "P1.1 orphan helpers — implementation results and evidence"
kind: spec
comments: none
---

# P1.1 implementation results

Branch `architecture-cleanup` @ `4de121bf7ed486e9f39b5a3f4f5c4288f439c8c8` (tracking `origin/architecture-cleanup`, still at initial commit — no commits made). Worktree root: `C:\Users\ADMIN\Documents\Coding Related\tachiyomiATVIBE-inpainting-modes-redesign`. Status after work: exactly 3 deletions + 3 modifications, nothing else staged or untracked except the user's untouched report.

# Reference audit (performed before editing, repo-wide)

Method: full-tree content search including hidden files (`.comments`, dotfiles), all file types — Kotlin, XML, `.pro`/keep rules, Gradle scripts, resources, string literals that could feed reflection. Results:

| Symbol | Definition | Production callers | Test callers | Other references (XML/keep/reflection) |
| --- | --- | --- | --- | --- |
| `runWithInpaintingProviderRecovery` | InpaintingProviderRecovery.kt:7 | none | own test only | none |
| `BatchResumePolicy` | BatchResumePolicy.kt:17 | `BatchResumeGateDecider` only | `StandardPipelineCoexistenceTest.kt:11,84,119` | none |
| `markBatchTranslationFailed` | TranslationPipeline.kt:1265 (pre-change) | none | none | none |
| `isStructuralAnalysisRefusal` | AnalysisChunkExecutor.kt:353 (pre-change) | none | none | none |
| `batchSublimitKeyFor` | AnalysisChunkExecutor.kt:357 (pre-change) | none | none | none |

Conclusion: the four helper/seam symbols (`runWithInpaintingProviderRecovery`, `markBatchTranslationFailed`, `isStructuralAnalysisRefusal`, `batchSublimitKeyFor`) are production-unreachable — dead as forecast and removed. `BatchResumePolicy` is **not** dead: it is live, with one production caller (`BatchResumeGateDecider`) and direct test callers (`StandardPipelineCoexistenceTest`); its standalone file was consolidated (co-located into `BatchResumeGateDecider.kt`), not removed as dead behavior. The coexistence test's direct `BatchResumePolicy` calls were the one usage the ticket's "private file-level object" wording did not anticipate (see Interpretations).

# Exact changes

| Path | Change |
| --- | --- |
| `app/src/main/java/eu/kanade/translation/engines/inpainting/InpaintingProviderRecovery.kt` | Deleted (30 lines) |
| `app/src/test/java/eu/kanade/translation/engines/inpainting/InpaintingProviderRecoveryTest.kt` | Deleted (103 lines) — only tests of the retired helper |
| `app/src/main/java/eu/kanade/translation/pipeline/batch/recovery/BatchResumePolicy.kt` | Deleted (60 lines) — body co-located unchanged |
| `app/src/main/java/eu/kanade/translation/pipeline/batch/recovery/BatchResumeGateDecider.kt` | +56 / −0 (git byte-diff): merged imports; `BatchResumePolicy` object (KDoc + body byte-identical, verified) inserted above the untouched decider |
| `app/src/main/java/eu/kanade/translation/pipeline/TranslationPipeline.kt` | −25: removed unused private `markBatchTranslationFailed` + imports exclusively used by it (`eu.kanade.translation.model.StageStatus`, `eu.kanade.translation.model.recordAttemptFailure` — each had no other use in the file) |
| `app/src/main/java/eu/kanade/translation/engines/translator/analysis/AnalysisChunkExecutor.kt` | −9: removed unused `isStructuralAnalysisRefusal` and `batchSublimitKeyFor` wrappers with their test-seam KDoc + import exclusively used by them (`eu.kanade.translation.engines.translator.BatchProviderSublimit`). Live call `TranslationResponseFaithfulness.isStructuralRefusal(...)` (line ~263) retained untouched |

Totals: 56 insertions, 227 deletions, net −171 lines, 6 files. Diff preserved at epic scratch `phase-1-cleanup/orphan-helpers/p1.1-actual.diff` (+ `p1.1-diffstat.txt`).

No production algorithm, scheduling, native guard, quarantine, store, provider-contract, or batch-callback behavior touched. No other production or test file modified.

# Interpretations and deviations

1. **Policy visibility `internal`, not `private` (deliberate deviation from ticket wording).** The ticket says "private file-level object", but Kotlin `private` top-level declarations are file-scoped, and `StandardPipelineCoexistenceTest` — a different package (`eu.kanade.translation.coexistence`) that imports the policy from `eu.kanade.translation.pipeline.batch.recovery` — asserts `BatchResumePolicy.nextStage(...)` directly at lines 84 and 119. The justification for `internal` is cross-file module visibility: `internal` declarations are visible from every file in the same Gradle module (the app module, where this test lives), whereas `private` would confine the policy to `BatchResumeGateDecider.kt` and break the test's compilation. The slice forbids touching tests beyond exclusive imports and mandates "preserving all decisions and tests". Resolution: co-located the object byte-identical with its original `internal` visibility — the structural goal (standalone file deleted) and all hard gates (no test edits, no behavior change) hold. If the reviewer prefers strict `private`, the follow-up is a separate decision to rewrite those two coexistence assertions onto `BatchResumeGateDecider.decide(...)` — a test-semantics change this slice deliberately did not make.
2. **Counts — two scopes, corrected after coordinator review.** The initial version of this artifact reported only the Kotlin-only scope and wrongly called the ticket's 290 baseline a miscount; the coordinator corrected this. Both scopes, tracked via `git ls-files` at HEAD=4de121bf, scope `app/src/main/java/eu/kanade/translation`:
  - **All source files (ticket scope): 290→288.** 290 = 288 Kotlin + 2 Java; 288 = 286 Kotlin + 2 Java. The 2 `.java` files are untouched by this slice, so the ticket's "290→288" baseline and target were both correct.
  - **Kotlin-only: 288→286** (my original measurement used `Get-ChildItem -Filter *.kt`).
  - Test scope `app/src/test/java/eu/kanade/translation`: **318→317**, all Kotlin (0 `.java` per Phase 0 inventory) — exact match in either scope.
  - Delta is −2 main / −1 test in both readings, exactly as specified.
3. **Source-audit omission in the ticket, caught by the pre-edit repo-wide audit.** The ticket's "private file-level object" wording omitted that `StandardPipelineCoexistenceTest` — in package `eu.kanade.translation.coexistence`, importing the policy (line 11) — calls `BatchResumePolicy.nextStage(...)` directly at lines 84 and 119. The mandated repo-wide recheck surfaced this before editing, which is why the policy was co-located with its original `internal` visibility instead of `private` (see item 1). Lesson recorded for planning: the plan's file-level table did not carry test-consumer evidence for the policy symbol; reference audits must precede every symbol move, as the parent artifact already requires.
4. Exclusive-import cleanup extended to the two edited production files themselves (TranslationPipeline: 2 imports; AnalysisChunkExecutor: 1 import), per the ticket's "imports exclusively used by it" rule. No test file needed any import fix (the deleted test was self-contained).

# Validation executed

Gradle 8.12 wrapper, PATH JDK 21 (same environment as Phase 0; recorded deviation from CI JDK 17 stands). Sequential runs, one logged traycer shell each; Gradle owned exclusively during runs.

| Run | Command (repo root) | Exit | Result |
| --- | --- | --- | --- |
| Baseline (pre-change) | `./gradlew.bat :app:testDevDebugUnitTest --tests "eu.kanade.translation.pipeline.batch.recovery.BatchResumeGateDeciderTest" --tests "eu.kanade.translation.pipeline.batch.BatchResumeAndDisplayContractTest" --tests "eu.kanade.translation.coexistence.StandardPipelineCoexistenceTest" --tests "eu.kanade.translation.engines.translator.analysis.AnalysisChunkValidationTest" --tests "eu.kanade.translation.engines.translator.ProviderGovernorBatchSublimitTest" --console=plain --stacktrace` | 0 | BUILD SUCCESSFUL in 2m 28s; 42 tests / 0 failures / 0 errors / 0 skipped (recompiled main sources first — cold `compileDevDebugKotlin` from input invalidation, tree unchanged at 4de121bf) |
| Post-change | same filter + `--rerun` | 0 | BUILD SUCCESSFUL in 37s; 42 tests / 0 failures / 0 errors / 0 skipped; includes full `compileDevDebugKotlin` + `compileDevDebugUnitTestKotlin` of the edited tree (affected-compilation evidence) |

Per-class (identical before and after): BatchResumeGateDeciderTest 9, BatchResumeAndDisplayContractTest 3, StandardPipelineCoexistenceTest 3, AnalysisChunkValidationTest 23, ProviderGovernorBatchSublimitTest 4.

Log paths (traycer command logs):

- Baseline: `C:\Users\ADMIN\.traycer\commands\699de408-00ac-4fe5-a903-b541c23063cc\36424205-7587-4d2f-a569-ed8088125099\output.log`
- Post-change: `C:\Users\ADMIN\.traycer\commands\699de408-00ac-4fe5-a903-b541c23063cc\86656b81-a1ad-4ebb-98f3-f50b22369f6b\output.log`

JUnit XML preserved (5 class XMLs each) at epic scratch:

- Baseline: `epics/699de408-00ac-4fe5-a903-b541c23063cc/scratch/phase-1-cleanup/orphan-helpers/baseline-test-results/2026-10-14T14-34-36Z/`
- Post-change: `epics/699de408-00ac-4fe5-a903-b541c23063cc/scratch/phase-1-cleanup/orphan-helpers/post-change-test-results/2026-10-14T14-37-58Z/`

No compiler warnings attributable to the changed files in either run (scan of `w:` lines); only pre-existing `-Xcontext-receivers`/delicate-API notes and the pre-existing Gradle 9.0 deprecation notice.

# Preservation and isolation checks

- User report `TachiyomiATVibe_Translation_Simplicity_First_Architecture_Report.md` re-hashed after all work: SHA256 `EFC11B90A7D0B367F3878BD600BF893AA99ECCDB5879D902E10EB6E06EF88DDD` — byte-identical.
- Phase 0 evidence untouched at epic scratch `phase-0-baseline/fresh-run-test-results/2026-10-14T12-26-30Z/` (verified present; this slice's filtered runs overwrote only workspace `app/build/test-results` class XMLs for the 5 rerun classes, same accepted pattern as Phase 0 diagnostic runs — authoritative full-suite XML remains preserved in scratch).
- Branch/commit state unchanged: `architecture-cleanup` @ 4de121bf tracking `origin/architecture-cleanup`; no commits, no pushes.
- Pre-existing baseline failures: none of the six ledgered failing methods belong to the touched suites (none of the 5 rerun classes is among them); no all-suite rerun was performed and no unrelated failures were touched or repaired.

# Assumptions and limitations

- Verification scope is the five affected classes on the DevDebug unit-test task plus full main+test compilation of that variant; StandardDebug variant and the untouched suites were not re-executed in this slice (P1.5 owns consolidated regression).
- The `internal`-vs-`private` visibility deviation awaits independent review; no known behavioral consequence either way (original `internal` module visibility preserved, identical JVM signature).
- `StandardPipelineCoexistenceTest` documents a historical intermittent (1-in-9 forced full-suite runs, characterized 2026-09-30); it passed in both filtered runs here, which is consistent evidence only, not a stability proof.

# Next

Slice complete and verified; stopping here for fresh independent review per parent phase gating. Rendering-island deletion (P1.2) not started.
