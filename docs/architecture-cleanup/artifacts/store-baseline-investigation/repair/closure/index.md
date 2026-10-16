---
kind: spec
title: "Published test repairs and Phase 3 entry baseline"
comments: none
---

# Published outcome

On 15 October 2026 the two reviewed test-only fixes were committed and pushed to `origin/architecture-cleanup` as `fbbd19d19a49ea1a0b43995f8c0814fc21dfa1ec`. A fresh `git ls-remote origin refs/heads/architecture-cleanup` returned that exact local HEAD. Commit scope: two test paths, +6/-5 lines; zero production changes. The original architecture report remains untracked, unstaged and SHA-256 EFC11B90A7D0B367F3878BD600BF893AA99ECCDB5879D902E10EB6E06EF88DDD. Remaining ten tracked status-M entries have empty content diffs; there are no staged or outstanding content changes.

The store fixture now closes its manifest input stream with `use`. Three scheduler trace tests wait for final `schedule_end` before making unchanged terminal-event assertions. The controlled reader intervention and trace counterexample support these specific repairs; neither certifies general production handle or native safety.

# Accepted replacement baseline

| Full app flavor | Suites | Tests | Passed | Failed | Errors | Skipped | Gradle exit |
| --- | --- | --- | --- | --- | --- | --- | --- |
| DevDebug | 332 | 2254 | 2253 | 0 | 0 | 1 | 0 |
| StandardDebug | 332 | 2254 | 2253 | 0 | 0 | 1 | 0 |

The sole skip is the existing synthetic journal replay benchmark. All suite/method identities match the immutable Phase 2 ledger. ProbeTest is14/0, TraceTest3/0 and the package boundary suite3/0 in both flavors. The original four store failures and subsequently discovered Dev trace failure remain preserved as historical evidence. Final runs used the exact committed tree before publication; no test or production bytes changed after those runs.

[Final results](../full-rebaseline/final-results) retain literal commands, real Gradle exits, complete logs and XML hashes. [Independent Gemini acceptance](../full-rebaseline/final-review) authorizes publication and Phase 3 entry. Implementation used GLM5.3Flash/max through OpenCode, review Gemini3.8Flash/high through Antigravity. No extra broad rerun was needed after acceptance.

# Gate disposition

The targeted store investigation, minimal repair and full rebaseline prerequisite is complete. The [Phase 3 entry packet](../../../phase-3-entry) is ready for architecture planning and contract definition. Native/page ownership implementation has not started and no ownership mechanism has been selected by this repair.

Physical device/JNI cancellation and native occupancy evidence remain deferred; host JDK21.0.8 does not establish CI JDK17 parity. Standing Spotless debt remains confined to the pre-existing LiteRT engine wrapping; both changed tests have no violations. These limitations remain explicit gates for relevant future changes.
