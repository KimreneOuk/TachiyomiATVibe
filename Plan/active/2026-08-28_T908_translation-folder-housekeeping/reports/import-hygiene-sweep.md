# T908 addendum — repo-wide dead-import sweep (post-T909 QC)

Date: 2026-08-28. Director mandate: quality control pass before any further
improvement/fix work. Follow-up to the TranslationPipeline.kt prune (c568eec,
28 dead imports) and the T909 god-file dismantle that caused the residue.

## Scope and method

- All 13 gradle modules (`app`, `core*`, `data`, `domain`, `i18n*`,
  `presentation-*`, `source-*`, `buildSrc`), every `src/{main,test}` Kotlin
  file (~1,200 files). Non-compiled scratch trees (`experimental/`,
  `onnx_check*/`, `overlay_lab/`) excluded — no compiler backstop exists for
  them.
- Textual scan: an import is a *candidate* when its simple name never occurs
  in the file body (word-boundary match). Wildcard and aliased imports
  excluded from deletion logic.

## Results

| Stage | Files | Import lines |
|---|---|---|
| Raw textual candidates | 138 | 227 |
| After compile-judge protocol | 4 | 11 |

The 227 broke down as:

- `getValue`/`setValue`/`provideDelegate` (200) — **false-positive class**.
  Compose/state delegate imports are invoked *implicitly* by the `by`
  keyword; the symbol never appears at the usage site, so a text scan cannot
  prove them dead. Every flagged file does real delegation. Almost all
  restored.
- `plus` (16) — **false-positive class**. Operator extensions invoked via `+`
  (e.g. `tachiyomi.presentation.core.util.plus`, `kotlinx.coroutines.plus`).
  Compiler confirmed 2 of 16 were essential (`domain` category interctors);
  the rest were restored unproven rather than churn further builds.
- Genuinely dead (11, all in the translation module, all compiler-verified):
  - `TranslationManager.kt` (7): `AtomicChapterDocuments`,
    `ChapterArtifactDeletionPlan`, `UniFileChapterDocumentIo`,
    `toPageDisplayProjection`, `toPageView`, `flow.combine`, `withLock`
    — moved out during T909 phases 15–20.
  - `RoiPageRecognitionEngine.kt` (2): `kotlin.math.min`, `kotlin.math.max`.
  - `pipeline/batch/BatchLaneWorkers.kt` (1): `TextTranslatorLanguage`.
  - `TranslationQueueStoreTest.kt` (1): unused kotest `shouldBe`.

## Protocol that made this safe

Text-scan candidates were **deleted en masse and arbitrated by the Kotlin
compiler** (kotlinc resolution is semantic ground truth: a file that compiles
without the import did not need it). Failing files were restored via
`git checkout --` and the build re-run until green. Two tooling lessons,
recorded for future sweeps:

1. **buildSrc masks downstream errors.** Its failure aborts configuration
   before module compilation, so the first build reported only one error.
   Iterate with `--continue` and restore from the full error list.
2. **Stale incremental-compilation state produces phantom errors.**
   `NotificationReceiver.kt` and `TranslationManager.kt` kept "failing" after
   being restored to HEAD-identical content, for six consecutive iterations.
   After `./gradlew --stop` (daemon restart), the same deletions compiled and
   tested green. A post-restore build failure should be re-checked under a
   fresh daemon before being believed.

Verification: `:app:testStandardDebugUnitTest` BUILD SUCCESSFUL with the
final diff (11 deletions); suite green. Landed as 340762a.

## Residual / recommendation

~216 ambiguous delegate/operator imports remain in place. They are *not*
proven dead — most are live Compose `by`-delegation. Judging them requires
type resolution, not text matching: wiring a lint with an unused-import rule
(detekt `UnusedImports` / ktlint `no-unused-imports`, treating warnings as
errors on CI) would keep import hygiene self-maintaining and catch this
class automatically at authoring time. Not acted on; Director decision
required for lint adoption.
