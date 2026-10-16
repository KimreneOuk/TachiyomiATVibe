---
title: "S2 - cancellation-safe writer enrollment"
kind: ticket
status: 2
assignee: ""
comments: none
---

# Governing contract

[Execution](../..) and the [approved plan](../../../phase-3-planning/plan) govern this ticket. Follow the plan's mechanism, failure matrix and physical gates. GLM5.3Flash/max implements; Gemini3.8Flash/high through Antigravity reviews actual diff and evidence. No subagents or concurrent Gradle owner.

# Dependencies

S1 accepted.

# Scope

Change enqueueLazyPersistence to accept a caller-held receipt; use narrow NonCancellable enrollment with store-exclusive completion after queueing. Add no-target/closed-scope rejection guards. Keep existing publication/recycle flow resource-safe using the local receipt until S5; no early caller false completion of a queued borrower.

# Verification and delivery

Cover cancellation before/after enrollment, actual queued worker exit, stale generation, defunct, no target and closed scope. Assert no premature recycle and no identified never-enrolled receipt gap. Actual OS I/O liveness remains device evidence. Fresh focused checks both flavors.

Preserve existing user files, baseline failure evidence and protected report. Use byte-safe edits and unique pre-created evidence paths. Record results and exact meaningful manifest under this ticket. Report completion to parent with expectReply true; stop for review before dependent work. Parent commits/pushes; do not stage or publish independently.

# Start pin

S1 accepted and committed as `19697f8cd362e126783b95e0b48d5ff620a38605`. Follow the [validation audit](../../validation-audit): incremental focused tests, guarded test execution, one Gradle owner, and immutable per-attempt evidence directories. No broad suite during iteration. S1 fail-closed receipt policy requires normal true/false store completion to represent actual borrower exit or work never started.

# Accepted delivery

Revision r1 received final Gemini approval. Committed locally as `c0e3ded43ee52c8bb733c2113284a21dead0e330` (six S2 files only). Both flavors passed 25 focused tests; the full final format inventory flags only baseline LiteRT debt. Writer-enrollment and cleanup obligations are verified at the documented host scope; device I/O liveness remains open. Historical r0 formatting misreport and failed attempts remain in [results](results).
