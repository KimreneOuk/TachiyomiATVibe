---
title: "P1.3b — replace local test mirrors with production coverage"
kind: ticket
status: 2
assignee: ""
comments: none
---

# Completion

Gemini/Antigravity final recheck accepted P1.3b including unconditional fixture closure. Coordinator committed the four scoped paths locally as 1c1d247e (+266/−120 lines). No new/deleted files, no scheduling-policy changes; production extraction and tests are as reviewed. Final affected classes pass 24 tests per flavor; only the changed coordinator class needed the follow-up rerun, passing 12/12 in each. Residual documentation wording was corrected separately before this completion. User report remains untracked and unchanged; remote not updated yet.

# Review accepted — small follow-up before commit (historical)

[Gemini/Antigravity actual-code review](review) accepted the production extraction and replacement coverage. Coordinator requested [unconditional fixture cleanup and reporting corrections](fixture-finalization) before committing: close the new store fixture in finally; label captured callback records as in-memory tail evidence; correct ChapterTranslator 952→963 lines. GLM owns this bounded follow-up and narrow two-flavor coordinator-class verification; production policy and other tests remain unchanged. Parent ticket stays in progress until follow-up recheck and commit.

# Delivery checkpoint — historical pre-review record

Implemented against 444b5abf, uncommitted: four scoped files changed, +267/−120 lines, no new/deleted files. Production wave-selection body moved into the same-file function and combine calls it. Six real-Translation wave tests and one four-combination worker-tail test replace two local mirrors. Coordinator independently verified saved XML: baseline 19 tests per flavor, replacement gate 26 DevDebug tests while mirrors remained, final 24 tests per flavor after mirror deletion, all zero failures/errors/skips. User report hash unchanged; Phase 0 evidence preserved.

[Results](results) record superseded compile errors and all verification runs. Gemini/Antigravity review requested before commit. Reviewer was asked to examine failure-path fixture cleanup, distinguish captured callback records from durable storage evidence, and reconcile reported source line counts. These are open checks, not resolved findings. No P1.4 implementation started.

# Base and change manifest

Base architecture-cleanup local commit 444b5abf. P1.3a is reviewed and committed. No new files or file deletions expected. GLM implementation, Gemini/Antigravity actual-code review before commit; one Gradle owner.

| Exact source path | Allowed change |
| --- | --- |
| app/src/main/java/eu/kanade/translation/workflow/ChapterTranslator.kt | Co-locate internal selectChapterWave(queue: List<Translation>): List<Translation> containing the current wave selection body; production combine callback calls it. Preserve every filtering, ordering, equality, source and capacity decision |
| app/src/test/java/eu/kanade/translation/workflow/ChapterTranslatorQueueRestoreTest.kt | Add tests calling selectChapterWave with actual Translation objects and shared/distinct source identities; retain restore tests |
| app/src/test/java/eu/kanade/translation/pipeline/batch/StandardPipelineCoordinatorTest.kt | Add direct StandardLaneWorker.runPhase coverage of the gap-terminal contract using its existing context; retain coordinator tests |
| app/src/test/java/eu/kanade/translation/coexistence/BatchParallelWindowBudgetTest.kt | Remove only the two local-mirror tests after production replacements pass; retain both actual bitmap-budget tests unchanged |

# Wave selection parity

Production ChapterTranslator.launchTranslatorJob selects QUEUE/TRANSLATING entries, prefers first active entry, includes at most the next queued entry from that active source, and retains final source grouping and limits. Extract exactly this logic into the same source file without simplifying policy or changing status-flow observation, coroutine ownership or cancellation. Concrete Translation contract; no generic strategies/callback abstraction or new micro-file.

Production tests cover all four old cases: fresh queue admits only first; active plus same-source queued admits two; active plus different-source queued admits only active; active with no queued admits only active. Cover excluded terminal/paused/error entries and multiple-active ordering if needed to pin less-obvious branches. Use real Translation objects with narrowly mocked existing HttpSource/Manga/Chapter dependencies; never recreate selection inside tests.

# X6 gap-terminal parity

StandardLaneWorker.runPhase at its drained translation tail returns PAUSED when hasGaps is true or corpusFingerprint is null; only full corpus/no gaps reaches runFinalizeAndComplete. Exercise the real worker and existing StandardLaneWorkerContext, not another boolean helper or local status copy. Cover all four combinations of present/absent corpus fingerprint and gaps true/false. Verify returned status, records remain TRANSLATE on gaps, finalizer never called for three incomplete combinations, exactly one finalizer call for present fingerprint/no gaps.

Use existing real artifact/store fixtures where appropriate, close resources newly created by tests, and controlled existing callback fakes for external provider/native work. Typed transport must be non-null to reach the target tail instead of earlier configuration pause. Empty ordered-page input is acceptable only as a deliberate isolated terminal-boundary fixture whose assertions prove the worker reached that tail and whose finalizer spy discriminates each branch; prefer a small available-checkpoint case when it avoids substantial fixture plumbing. No StandardLaneWorker/store edits for test convenience.

# Validation and limits

Preserve narrow baseline XML for three affected test classes. Add replacements, run DevDebug with fresh compilation and verify passing assertions before deleting the mirrors. Run final three-class set in DevDebug and StandardDebug with fresh compilation; preserve all results and per-class/count changes. If wider APIs/harness or behavior changes are necessary, return the gap instead of expanding scope. Phase 0 evidence untouched; P1.3a repaired one known failure, five others remain tracked. No phone/native/model/provider contact, policy changes, batch callbacks, persistence internals, broad retries, commits/pushes/subagents.

Record results at results/index.md: extraction body comparison and production linkage, replaced cases, exact paths/file/test counts, commands/XML/logs/hash and limits. Stop for fresh review.
