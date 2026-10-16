---
title: "Parallel read-only preparation for later Phase 2 slices"
kind: story
status: 2
assignee: ""
comments: none
---

# Coordination decision

Both read-only preparations have been delivered and source-checked. Corrections to source counts, XML provenance and lock/snapshot wording are being recorded in the child reviews; settled implementation choices are recorded in the respective tickets. No preparation agent changed source or ran Gradle. P2.2 remains the sole active implementation slice; later code changes stay sequential and independently reviewed.

Parallelize source inspection and test planning while P2.2 is implemented. Two existing GLM 5.3 Flash/max agents prepare later boundaries in distinct artifact directories. They may read source/tests and write their assigned preparation artifacts only. No source/config/test edits, Gradle, commits, pushes, installs, provider/device/native calls or further subagents. The main implementer remains the sole code/Gradle owner; Gemini/Antigravity reviews delivered code.

Source baseline is d8252cbe. P2.2 may be changing outcome imports/types in the shared workspace; distinguish those unrelated edits from inspected baseline facts. No checkout/reset/stash or other interference. Use pinned git reads if needed. Later code slices remain separately compiled/reviewed; preparation is not execution acceptance.

| Owner | Read-only task | Delivery |
| --- | --- | --- |
| GLM source auditor | Context-frontier move and suspending chapter replacement: exact references, behavior invariants, relevant tests and smallest meaningful ordering regression | [Context and replacement](context-and-replacement) |
| GLM source auditor | Fingerprint caller/lock/suspension trace and simplest safe cache contract, lazy/resume/null/error/cancellation requirements | [Source fingerprints](source-fingerprints) |

Implementation can be performed concurrently only where paths and integration gates are independent and workspaces isolated. In this shared workspace, keep mutation and Gradle ownership singular to avoid competing writes and output overwrites; parallel audits are immediately useful without those risks.
