---
kind: spec
title: "Phase 2 closure ? contracts and suspending boundaries published"
comments: none
---

# Published result

Phase 2 host work is complete and independently accepted. All five reviewed commits were pushed to [origin/architecture-cleanup](https://github.com/KimreneOuk/TachiyomiATVibe/tree/architecture-cleanup). A fresh git ls-remote check confirmed remote HEAD equals local HEAD: `999bb9be6563f957ee508f739ba2f042bf1c90ee`. Parent published after the user's standing instruction and [final evidence acceptance](../tickets/validation/review); no Phase 3 execution is included.

| Slice | Result | Commit |
| --- | --- | --- |
| P2.1 | Vision telemetry and stage normalization moved to diagnostics; engine imports corrected | d8252cbe |
| P2.2 | Typed single-page rejection outcomes; fixed UI copy independent of diagnostic text; package dependency test | f015a33f |
| P2.3 | Shared context frontier moved out of pipeline | aef57913 |
| P2.4 | Chapter replacement suspends and awaits cancellation, clearing, removal in order | 2efde3b5 |
| P2.5 | Explicit suspending source-fingerprint cache; post-rekey ordered evidence; cancellation/contention regressions | 999bb9be |

Implementation began with GLM 5.3 Flash/max and switched at the user's request to GPT 6 Luna/max through Codex with fast mode. Gemini 3.8 Flash/high through Antigravity reviewed the slices and final evidence.

# Executed verification

Both DevDebug and StandardDebug assemblies succeeded. Each fresh full app suite executed 332 classes / 2,254 tests: 2,249 passed, four failed, one deliberate benchmark skipped, zero errors. Domain executed 66/66 passing. Package-boundary, cache and post-rekey regression cases passed in both app variants. The four failures match the preserved baseline methods/types/stable messages and nested cleanup causes; their causes remain unresolved. This is not an all-green test claim.

Read-only Spotless still fails on the single pre-existing LiteRT wrapping file. No Phase 2 formatting violations remain. StandardDebug's nonfatal AAPT2 shutdown timeout is disclosed. Local JDK 21 validation does not establish CI JDK 17 parity.

[Detailed results and commands](../tickets/validation/results) and [independent evidence review](../tickets/validation/review) retain the execution records. The two current output.log files are rotated tails beginning mid-stack; earlier output is retained in sibling rotation chunks and their manifests. Shell registry, start chunks, retained success/exit tails and distinct invocation records corroborate build identity. No build rerun was used to reconstruct output.

# Accounting and integrity

Phase 2 against dab5c124: 46 changed paths, +1,063/-189 lines (net +874). Package moves are not source retirements. Translation production sources: 285 (283 Kotlin, two Java), plus one non-source README. Ordinary translation unit source files: 318; testDebug: one; androidTest: six. Compared with the original audited commit, the combined reviewed work changes 67 paths, +1,704/-4,952 lines (net 3,248 removed).

No staged or unstaged content diff remains. Eleven index/stat-only M entries remain with verified empty content diffs; the tree is not represented as fully status-clean. The user's architecture report remains untracked, unstaged, and byte-identical: SHA256 `EFC11B90A7D0B367F3878BD600BF893AA99ECCDB5879D902E10EB6E06EF88DDD`.

# Remaining entry gates

- Before Phase 3/4 ownership changes, discriminate the four baseline store assertion/cleanup failures and establish candidate/committed, registration and close/drain contracts; do not presume corruption, fixture drift or a particular handle owner.
- Device/native cancellation, physical teardown and performance evidence remain deferred by user choice and required for the relevant behavior changes.
- JDK 17 parity and existing LiteRT formatting debt remain recorded.

Phase 3 has not started. Its prerequisite investigation and sequential page ownership design need a separate authorized slice; deep persistence restructuring remains Phase 7.
