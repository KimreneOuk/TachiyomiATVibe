---
title: "P1.5 follow-up — repair introduced lint and investigate new cleanup failure"
kind: ticket
status: 2
assignee: ""
comments: none
---

# Entry and status

Completed and independently accepted. The three bounded lint changes were committed as `f39cfd2a` on `architecture-cleanup` (+2/-4 lines). Focused checks passed 46 tests per flavor. Remaining LiteRT formatting is unchanged baseline debt. Build provenance is corrected in results; original full-suite evidence remains preserved. N2 disposition continues in [Fixture finalization](../fixture-finalization); this follow-up completion does not close Phase 1.

Consolidated runs at b44143f2 preserve2234 tests per app flavor and66 domain. Four ArtifactOpenProbe methods reproduce original signatures; StandardDebug additionally fails ProfileEnvelopeDispatchTest.N2 during TempDir cleanup, on a run-record sidecar. This is a newly observed failed method, not automatically a known baseline failure merely because the outer JUnit message matches. Phase1 closure is pending lint fixes and this failure's disposition. Full initial XML remains immutable.

# Authorized source edits — lint only

| Path | Exact edit |
| --- | --- |
| app/src/main/java/eu/kanade/translation/engines/rendering/CollisionRelaxation.kt | Remove now-unused TranslationBlockView import |
| app/src/test/java/eu/kanade/translation/coexistence/BatchParallelWindowBudgetTest.kt | Remove excess blank before final brace |
| app/src/test/java/eu/kanade/translation/workflow/ChapterTranslatorQueueRestoreTest.kt | Correct imports to project ordering |

No blanket spotlessApply; leave pre-existing LiteRT formatting and OCR/LiteRT source untouched. Run read-only app spotlessCheck after edits; report any remaining introduced issue separately. Compile affected main/tests and run the three affected test classes in both flavors. Preserve narrow evidence and actual diff, no commits/pushes until review.

# N2 investigation — no fixture edits yet

Read preserved new XML fully: nested AccessDeniedException names Chapter1_artifacts/runs/f-e703…json; suppressed NoSuchFileException names the same path. The body completed its assertions; temp cleanup failed. Actual descriptor/deletion owner remains unproven. Trace N2's lazyStore, producer/retention tasks and closeAndFlush barrier. Inventory all store allocations in ProfileEnvelopeDispatchTest, including reopened stores, per-test ownership and lifecycle cleanup. Identify a concrete proposed minimal fixture correction that preserves production assertions, and distinguish it from any production handle defect. No native/store production changes, suppressing TempDir failures, removing the test, arbitrary sleeps or repeated green-seeking runs.

One isolated StandardDebug execution of ProfileEnvelopeDispatchTest is authorized as a diagnostic comparison, not replacement for the failed full run. Save XML separately immediately and label N1-per-configuration. A pass cannot establish a fix or baseline-only causality. Return code-grounded fixture proposal, exact closure semantics and unresolved alternatives; coordinator settles separate implementation scope.

# Provenance and reporting corrections

Amend parent results so verdict is pending, not passes/no unexplained regression before N2 disposition. Inspect actual shell command metadata/logs: Standard assemble task at34890 is followed by BUILD SUCCESSFUL4m11s; explain which invocation this belongs to and correct command/time ledger. If independent Dev/Standard completion lacks evidence, run only the missing cached assemble with captured exit and separate log, not full tests. Preserve original logs and distinguish corrected attribution from newly executed checks.

Results at results/index.md; original baseline/current full XML/report unchanged. One Gradle owner. No broad retry, configs, toolchain/model/provider/device operations, subagents, further phase or publication.
