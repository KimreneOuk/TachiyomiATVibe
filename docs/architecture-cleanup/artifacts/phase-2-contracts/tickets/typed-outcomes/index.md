---
title: "P2.2 — typed rejection semantics and package enforcement"
kind: ticket
status: 2
assignee: ""
comments: none
---

# Scope and dependency

Dispatched after accepted P2.1 commit d8252cbe. Refresh full production/test/string-reflection usages before edits; the former manifest missed PipelineStallWatchdogTest and a fully qualified assertion in TranslationSessionCoordinatorTest. Do not rely on import-only scans.

After P2.1 acceptance, move SinglePageOutcome from pipeline/execution/TranslationExecutor.kt into model/SinglePageOutcome.kt. Keep executor/prepared-page/stage-event/completion contracts in place. Rejected carries a required typed category and separate diagnostic detail; no default category, legacy string constructor or diagnostic-text inference. Choose the smallest enum covering actual producers, based on the current call-site trace. Preserve PageWriteOrigin ownership semantics.

Update every production/test consumer in the same compiling slice. Map reader-session denial, known ownership denial and post-attempt persistence rejection explicitly. Remove TranslationPipeline.REASON_TRANSLATION_NOT_SAVED and TranslationUiTruth's raw reason interpolation. Preserve BATCH owner precedence, persistence warning and retry, committed-display precedence, and safe fixed user/accessibility copy. Existing Failed diagnostic semantics are not broadened into this rejection-only change.

Primary files: model/SinglePageOutcome.kt (new), pipeline/execution/TranslationExecutor.kt, pipeline/TranslationPipeline.kt, scheduling/TranslationScheduler.kt, presentation/TranslationUiTruth.kt, and all outcome consumers identified by source search. Extend existing presentation/outcome tests with diagnostic-wording independence, category mapping, batch precedence and committed-refresh cases; keep active auto tests.

Add one fast TranslationPackageBoundaryTest in the existing test architecture/package layout enforcing engines/** and presentation/** cannot depend on pipeline/** via imports or executable qualified references. Cover alias/wildcard imports and avoid comment/string false positives; keep scanner/test cases in that one file, no analyzer dependency/framework. Preserve legitimate persistence/domain dependencies rather than imposing a total package order.

# Gates

## Concrete contract and boundary-test scope

The current five production rejection sites need SESSION_DENIED (scheduler reader gate), ALREADY_OWNED (both in-flight membership checks and lease denial when owner is known), PERSISTENCE_FAILED (post-attempt guarded publication), and OTHER (owner-less generic lease denial). Do not add speculative SOURCE_UNAVAILABLE/STORE_UNAVAILABLE/CANCELLED variants without an actual typed producer. Rejected(owner, kind, diagnosticDetail = null) has a mandatory kind. For persistence failure, carry TranslationCompletionOutcome.PersistenceRejected.reason as diagnostic detail; do not retain a display-copy constant. Never classify LeaseAcquisition.Denied by its human-readable reason.

Preserve the existing PageUiTruth outcomes/actions for persistence failure and BATCH precedence. Duplicate and session-denied display copy is fixed and safe; unknown denial uses generic copy. Diagnostic detail must not appear in labels/accessibility or choose severity/retry. Do not change existing Failed.reason or merge durable/scheduling state representations. Preserve actual committed image visibility; the existing P5 persistence-rejection test deliberately projects warning/non-success even alongside a display-ready record.

One new production file model/SinglePageOutcome.kt may include PageRejectionKind. Keep PageWriteOrigin dependency as the existing contract; do not impose unrelated model-package purity. Add one new test file app/src/test/java/eu/kanade/translation/architecture/TranslationPackageBoundaryTest.kt. In the existing app/build.gradle.kts Test.configureEach block, a single narrowly scoped test system property may expose project.layout.projectDirectory.dir("src/main/java").asFile.absolutePath. Source lookup must not depend on the shell's working directory; do not alter test selection, parallelism or toolchain. No new Gradle plugin/dependency/buildSrc framework.

Validate checker fixtures for ordinary/alias/wildcard imports and executable FQ references, plus comments, string literals, Kotlin raw strings and Java chars so documentation is not a violation. Keep the scan narrow and maintainable; report any Kotlin string-interpolation limitation rather than silently promising complete language parsing. The primary enforcement target is actual engines/presentation source dependencies on pipeline. A planted executable/import violation must be detected, then removed before delivery.

Preserve a pre-edit baseline for P5OutcomeProjectionTest, P5HonestOutcomeTypingTest, TranslationSchedulerManualOutcomeTest and TranslationSessionCoordinatorTest in both flavors. Post-edit run those plus the new boundary test in both flavors with fresh module compilation. Add meaningful semantic tests in existing classes: same kind with divergent/internal diagnostic text gives identical UI label/actions/severity/accessibility; categories and BATCH precedence remain correct; no stale/failed refresh hides committed output. Existing outcome tests change constructors/imports/typed assertions only where the contract requires. Capture exact new test counts, source manifest and behavior changes (safe copy is intentional), then stop for actual-code review.

Baseline and fresh focused tests in both flavors; all producers/fakes compile without fallback string semantics. Fast checker proves planted forbidden edges fail and valid code passes. No scheduler behavior, persistence authority, native/bitmap lifecycle, UI state-machine rewrite or reader queue change. Exact dispatch/manifest is refreshed before implementation. Deliver actual diff/results and await Gemini/Antigravity review, no child commits/pushes.
