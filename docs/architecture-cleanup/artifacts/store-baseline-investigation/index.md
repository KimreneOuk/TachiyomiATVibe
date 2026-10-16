---
title: "Store baseline investigation ? four failures before Phase 3"
kind: ticket
status: 2
assignee: ""
comments: none
---

# Authorization and scope

User authorized investigation of the four preserved baseline store failures, using GPT 6 Luna/max/fast through Codex and Gemini 3.8 Flash/high through Antigravity review. Start on architecture-cleanup at published `999bb9be6563f957ee508f739ba2f042bf1c90ee`. This is the narrow prerequisite investigation for Phases 3?4; it does not begin their ownership changes or Phase 7 storage decomposition.

Use the [accepted corrected static audit](../phase-0-baseline/store-contract-audit), [review prerequisite ledger](../phase-0-baseline/review-response), and [fresh Phase 2 evidence](../phase-2-contracts/tickets/validation/results). They are historical mapping/evidence, not proof of runtime causes.

# Four observations to discriminate

| Failing method in ChapterTranslationStoreArtifactOpenProbeTest | Preserved observation | Investigation target |
| --- | --- | --- |
| lazy store cuts over before first candidate and reopens from artifact pointers | Expected page.cleaned.retry.jpg, actual null | Distinguish mutation admission, candidate eligibility, promotion/rejection, post-close manifest and reopen projection |
| pre-registered pages publish one durable baseline with only the changed candidate | Expected p1/p2/p3, manifest pages empty | Settle registration timing contract and distinguish unavailable initialization, queue/drain, publication guards and fixture expectation |
| artifact-only lazy store writes pages without materializing flat compatibility file | JUnit TempDir cleanup failure naming Chapter 2 manifest | Observe actual ownership/lifecycle and closure-controlled behavior without presuming journal or external process holder |
| lazy store without parent resolves artifact authority through artifactParentResolver | JUnit TempDir cleanup failure naming Chapter 8 manifest | Same discriminating lifecycle investigation, keeping resolver/publication semantics intact |

# Execution and evidence

One Luna agent owns diagnostic edits and Gradle. Begin with a refreshed source/fixture trace at the current commit, focused original-class baseline both flavors, and a concrete bounded experiment matrix. Parent accepts that matrix before diagnostic edits. Preserve each run's XML and literal command/exit in pre-created separate directories; no broad suite, retries to erase failures, sleeps as synchronization, or toolchain/model/provider/device work.

Investigation may use reversible diagnostic test instrumentation and, only when needed and justified in the experiment matrix, temporary production observability with no policy change. Keep a byte-safe patch and original bytes; restore diagnostic-only edits after extracting evidence. No permanent production/fixture repair, test assertion weakening, lease/version/generation/PatchPrecondition change, storage architecture rewrite, commit or push during diagnosis. User authorization for investigation is sufficient for controlled reversible diagnostics; do not add another user approval step for routine experiments.

Observe the original compatibility updatePage path as written: it returns Unit; a guarded PatchResult API is a distinct alternate experiment whose lease/admission differences must be described. Capture publication reason/manifest before and after flush/close/reopen rather than inferring it from final output. A passing closure-controlled variant supports lifecycle intervention but does not identify the original holder without additional evidence. Resource cleanup must be reliable in new diagnostic fixtures.

Deliver per-failure supported cause or bounded remaining alternatives, expected contract with citations and comparator tests, raw observations, minimal repair/rebaseline recommendations and exact retained/restored source status. Gemini independently reviews the findings and diagnostic diffs/evidence. Then record which Phase 3 entry gates are actually satisfied and which remain open. Do not claim all failures solved, corruption, a fixture-only cause or native/device safety from static mapping or isolated passes.

Original user report remains untracked, unstaged and hash-identical. Preserve all Phase 0/1/2 XML/log ledgers. Eleven existing status-M paths have empty content diffs; do not rewrite them as part of diagnosis.

# Accepted investigation disposition

[Evidence review](review) accepted the controlled findings. Closing only the shared test helper's manifest input stream changed the original fourteen-test class from 14/4 to 14/0 in both flavors, without changing test bodies, assertions, store-finalization behavior or production code. The test-fixture reader lifetime is a supported sufficient explanation for these four observed Windows failures. The baseline-cause investigation prerequisite is grounded; implementation and rebaseline remain pending because the diagnostic change was restored to HEAD.

This is not proof of generic store handle safety or an exclusive handle owner in every execution. In particular, observed success with unclosed store fixtures does not prove all idle store instances never hold resources. The Windows replacement mechanism is supported by source ordering and the controlled intervention, not a per-handle writer trace. No production repair is justified by these observations.

No repository change, commit or push resulted from the investigation. Published HEAD remains 999bb9be. Follow-up [minimal test-only repair](repair) is proposed and has not started. Phase 3 ownership work has not started.

# Published follow-up

The subsequent [repair closure](repair/closure) supersedes the pending repair status above. Reader and trace test-only fixes are published at `fbbd19d19a49ea1a0b43995f8c0814fc21dfa1ec`, with both full app variants2254/0 failures/0 errors/1 skip. Targeted Phase3 prerequisite is satisfied; general production/native safety remains unproven.
