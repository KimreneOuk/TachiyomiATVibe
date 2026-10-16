---
title: "P1.3b follow-up — unconditional fixture cleanup and evidence precision"
kind: ticket
status: 2
assignee: ""
comments: none
---

# Completion

GLM applied the bounded try/finally change, corrected count and evidence wording, and preserved both recheck XMLs (12 tests per flavor, zero failures/errors/skips). Gemini/Antigravity final recheck accepted; changes included in parent local commit 1c1d247e. No production or sibling-test changes in this follow-up.

# Scope

Gemini/Antigravity accepted P1.3b's actual production extraction and test invariants. Coordinator elects the small reviewer-noted cleanup improvement before committing, rather than adding a newly avoidable assertion-failure resource leak to this test-repair slice.

Edit only the new four-combination StandardLaneWorker test in app/src/test/java/eu/kanade/translation/pipeline/batch/StandardPipelineCoordinatorTest.kt: place all fixture-dependent work after lazyStore construction inside try, closeAndFlush in finally. Preserve all assertions, contexts, cases, spies, other tests and production bodies. No additional behavioral scope. Remove copied cleanup comments claiming an unverified flake diagnosis.

Amend parent results artifact: ChapterTranslator count 952→963, +11; describe captured publishRecord callback records as in-memory evidence of tail arrival, not durable records or publication verification. Update exact actual numstat after cleanup change. Original successful test evidence and superseded compile logs retained; do not rewrite historical run outcomes.

Run only StandardPipelineCoordinatorTest in DevDebug and StandardDebug with fresh test compilation after this change. Preserve XML/logs and report counts; other two unchanged classes already passed both final checks, no repeated run required. Return actual scope/diff/evidence for reviewer recheck, no commits/pushes/subagents. Original Phase 0 evidence and user report stay untouched.
