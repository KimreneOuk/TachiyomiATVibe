# T910 — T906 housekeeping decision batch (delivery report)

Branch: `optimize_translation_finishing_page` (working tree only — no commits made, per assignment).
Date: 2026-08-28.
Sources: the four T906 audit reports under
`Plan/active/2026-08-27_T906_test-suite-audit/review/` (areas 1–4).

Baseline check performed first: several T906 fix-round dispositions were
already merged onto this branch before this task started. Those are listed
under "Already applied — verified, no action" below. Every audit-only
second-pass recommendation (the DELETE/LOW items) was NOT applied and is
executed here.

## Findings executed (16 actions, 21 tests + 1 redundant assertion line removed, −273 lines)

| # | Source | Action | File(s) | Verification |
|---|---|---|---|---|
| 1 | Area1 U1 (`BatchOomPolicyTest` whole file, 4 tests, "DELETE") | Deleted test file. Paired production deletion deliberately NOT done (see skipped #1). Also removed the now-dangling KDoc `[eu.kanade.translation.batch.BatchOomPolicyTest]` reference in `MemoryPressurePolicyTest.kt` (doc-only). | `app/src/test/java/eu/kanade/translation/batch/BatchOomPolicyTest.kt` (deleted), `app/src/test/java/eu/kanade/translation/MemoryPressurePolicyTest.kt` | `MemoryPressurePolicyTest` 14/14 green |
| 2 | Area1 U2 (`TranslationRetryTest` 2 `isTransientRateOrServerError` tests, "DELETE") | Deleted both tests. Dead shim `TranslationRetry.kt:275` left in place (Director-gated). | `app/src/test/java/eu/kanade/translation/translator/TranslationRetryTest.kt` | `TranslationRetryTest` 7/7 green (was 9) |
| 3 | Area1 U3 (`TranslationBatchEventContractTest` 3 negative tests, "DELETE") | Deleted `BatchStarted/BatchResumed/Revision events are not members`; kept the exact-set surface guard; removed unused `shouldNotContain` import. | `app/src/test/java/eu/kanade/translation/batch/TranslationBatchEventContractTest.kt` | 5/5 green (was 8) |
| 4 | Area1 U4 (`BatchEnvelopeLimitsTest` "envelopes preserve natural page order", DELETE) | Deleted test; removed the orphaned `naturalIndex` helper with it. | `app/src/test/java/eu/kanade/translation/batch/BatchEnvelopeLimitsTest.kt` | 2/2 green (was 3) |
| 5 | Area1 U5 (`AiTranslationRetryPlannerSinglePageTest` 2 duplicate-clause tests, DELETE) | Deleted `all blank translations returns every non-blank-source block` and `whitespace-only translation is treated as untranslated`; one representative per predicate clause remains. | `app/src/test/java/eu/kanade/translation/translator/AiTranslationRetryPlannerSinglePageTest.kt` | 5/5 green (was 7) |
| 6 | Area1 keep-with-note (redundant assertion line, "can be dropped opportunistically") | Dropped `(transportCalls <= 5) shouldBe true` (subsumed by `transportCalls shouldBe 5`); test kept. | `app/src/test/java/eu/kanade/translation/translator/AiTranslationRetryControllerTest.kt` | 12/12 green |
| 7 | Area2 F1 (`ChapterTranslationStoreRekeyTest` "CBZ entry names are valid targets", "DELETE; optionally fold") | Deleted (simpler option; report confirms no name-shape branching in `rekeyPages`). | `app/src/test/java/eu/kanade/translation/ChapterTranslationStoreRekeyTest.kt` | 3/3 green (was 4) |
| 8 | Area3 U1 (`ReaderTranslationOverlayBindingTest` 3 result-identical tier cases, DELETE) | Deleted the three tier variants; kept `translated image without a current clean result…` and `original image never receives translated text`; removed unused `StageStatus` import. | `app/src/test/java/eu/kanade/tachiyomi/ui/reader/viewer/ReaderTranslationOverlayBindingTest.kt` | 2/2 green (was 5) |
| 9 | Area4 pass-2 U1 (Checkpoint2 "compute class routing classifies ML Kit…", delete) | Deleted; identical coverage in `TranslatorComputeClassTest`. | `app/src/test/java/eu/kanade/translation/translator/Checkpoint2IntegrationTest.kt` | `Checkpoint2IntegrationTest` 1/1 green (kept unique tail-flush test) |
| 10 | Area4 pass-2 U2 (Checkpoint2 "compute class routes the configured standard engines…", delete) | Deleted (zero remaining delta vs `TranslatorComputeClassTest`). Same file as #9. | same as #9 | same run |
| 11 | Area4 pass-2 U3 (`MaskGeometryTest` "large sparse geometry… stay bounded", delete) | Deleted (superseded by `MaskGeometryStressTest` scale test; removes the area's only wall-clock `assertTimeoutPreemptively` gate); removed unused `assertTimeoutPreemptively`/`Duration` imports. | `app/src/test/java/eu/kanade/translation/segmentation/MaskGeometryTest.kt` | 7/7 green (was 8) |
| 12 | Area4 pass-2 U4 (`MaskGeometryStressTest` "exceeding the configured span budget…", delete) | Deleted (duplicate of `MaskGeometryTest` maxSpans rejection); removed unused `assertThrows` import. | `app/src/test/java/eu/kanade/translation/segmentation/MaskGeometryStressTest.kt` | 2/2 green (was 3) |
| 13 | Area4 pass-2 U5 (`BubbleCleanerMathTest` "unionMasks empty inputs return empty", delete LOW) | Deleted (min-size truncation already pinned by sibling). | `app/src/test/java/eu/kanade/translation/inpainting/BubbleCleanerMathTest.kt` | 22/22 green |
| 14 | Area4 pass-2 U6 (`DirectBufferPoolTest` "pool with maxPoolSize one never allocates more than one buffer under churn", "delete (or keep as optional stress guard)") | Deleted (simpler option; identity-reuse invariant pinned by the single-cycle test). | `domain/src/test/java/tachiyomi/domain/translation/pools/DirectBufferPoolTest.kt` | `DirectBufferPoolTest` 6/6 green (was 8) |
| 15 | Area4 pass-2 U7 (`DirectBufferPoolTest` "buffer is usable for normal put_get round-trips", "delete (LOW)") | Deleted (java.nio semantics, not pool logic; recycle angle covered by the release-acquire test). Same file as #14. | same as #14 | same run |
| 16 | Area4 H1 production wart (`AotReportBubbleFill.kt:38` dead `insetPx = 5` + misleading comment — the one production item named in the assignment) | Already applied on this branch — verified: only the live `smoothMaskedComponent` parameter remains (lines 205/221); the dead val and stale five-pixel comment are gone; behavior identical (no change made by this task). | — | `:app:compileStandardDebugKotlin` BUILD SUCCESSFUL |

## Already applied before this task (verified on this branch — no action)

- Area1 dispositions (fix branch `t906/fix-area1`): A1-06 legacy-merge test deleted, A1-09 tautological `translateFlat` test deleted, A1-10 `DisplayReadyStageCountTest` rewritten to drive real `computeSnapshot` (5 scenarios incl. committed-display case; no `countPages` mirror remains).
- Area2 dispositions (fix branch `t906/fix-area2`): Finding 1 rename → `committed-only artifact fixture rehydrates with no legacy flat file`; Finding 2 rename → `reopen with a changed legacy identity returns the prior manifest unchanged` with the dead `io.failWrites` toggle removed from that test. (The remaining `io.failWrites` uses at `ChapterArtifactStoreTest.kt:653/657` are live setup in the `NotStored on write failure` test — correctly retained.)
- Area4 fix slice (commit `ba24abc` lineage): `AotReportBubbleFillTest` tests 1-3 rewritten as policy smoke checks; duplicate `normalizeBaseUrl` test deleted from `AiModelFetcherParseTest`.
- Area3 dispositions (F1 fixture fix + F2 latch stabilization, `2bd73b3`): non-housekeeping classes (WRONG/FLAKY) — out of this batch's scope anyway.

## Intentionally skipped

1. **Area1 U1/U2 paired production deletions** — deleting dead `BatchOomPolicy.kt` + `AbortDecision` and the `isTransientRateOrServerError` shim (`TranslationRetry.kt:274-279`). Both reports mark the production halves as needing Director approval; the assignment authorizes only the `AotReportBubbleFill.kt` production deletion (already applied). Test-only deletion is explicitly called safe by the reports and was executed (items 1-2 above); consequence is the known one the reports state — the dead production symbols now have no test guard. Recommend surfacing both production deletions for a Director decision.
2. **Area3 U2** (duplicate `isTranslationActive` assertion in the arbitration paused test) — report is titled "Trivial-but-keep", offers keep-or-drop, and states "No action taken"; it does not recommend deletion, so it is outside the DELETE class. Kept.
3. **Area3 U3** (`RollingAutoCoordinatorTest` 8 silently-skipped non-void methods) — explicitly excluded: another task owns that file.
4. **Coverage-gap / add-test recommendations** (Area3 F3-F7 incl. the overlay positive case; Area4 H2/H3/H4 Gemini fallback, gemini-3 thinkingLevel, `fetch()`/`validateAuth`, OpenAI-family adapters; Area4 pass-2 "keep only if a smoke assert is wanted" alternatives) — adding tests is a different task; several also require Director approval per the reports.
5. **Area4 H1 test rewrites** (AotReportBubbleFillTest 1-3) — already applied on this branch; also classified STALE (fix), not housekeeping.
6. **Area2 F1 optional fold** (append CBZ keys as a second data case) — chose the simpler delete per assignment rules.

## Gates (all green)

- `:app:spotlessApply` / `:domain:spotlessApply` → clean; `:app:spotlessCheck` / `:domain:spotlessCheck` → BUILD SUCCESSFUL.
- `:app:compileStandardDebugKotlin` → BUILD SUCCESSFUL (no production source changed by this task; run per instructions).
- Focused `:app:testStandardDebugUnitTest` (12 touched/adjacent classes): 82 tests, 0 failures, 0 skipped — ReaderTranslationOverlayBindingTest 2, ChapterTranslationStoreRekeyTest 3, MemoryPressurePolicyTest 14, BatchEnvelopeLimitsTest 2, TranslationBatchEventContractTest 5, BubbleCleanerMathTest 22, MaskGeometryStressTest 2, MaskGeometryTest 7, AiTranslationRetryControllerTest 12, AiTranslationRetryPlannerSinglePageTest 5, Checkpoint2IntegrationTest 1, TranslationRetryTest 7.
- `:domain:testDebugUnitTest --tests DirectBufferPoolTest`: 6 tests, 0 failures, 0 skipped.
- Full suite intentionally not run (per assignment; one class is under investigation elsewhere). `RollingAutoCoordinatorTest.kt` untouched.

Net: 21 low-value/duplicate tests + 1 redundant assertion line removed, ~273 lines, zero coverage loss per the audit evidence; no production behavior change.
