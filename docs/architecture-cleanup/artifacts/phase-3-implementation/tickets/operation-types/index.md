---
title: "S1 - operation-scoped attempt authority and cleaned owner"
kind: ticket
status: 2
assignee: ""
comments: none
---

# Governing contract

[Execution](../..) and the [approved plan](../../../phase-3-planning/plan) govern this ticket. Follow the plan's mechanism, failure matrix and physical gates. GLM5.3Flash/max implements; Gemini3.8Flash/high through Antigravity reviews actual diff and evidence. No subagents or concurrent Gradle owner.

# Dependencies

None; baseline fbbd19d1.

# Scope

Add only the concrete operation-scoped AttemptAuthority and CleanedOwner capabilities described in plan sections 4-5 (revised overlap plan: authority serializes TWO concurrent branch writers). Keep production call sites unwired. Reuse the existing internal `PageSnapshot.toPrecondition()` extension (PageStoreWriter.kt:286) and real store patch receipts; no registry or competing state authority. The terminal-marker patch function is a `markTimeout` parameter (production marker content is S5 wiring). Record the precise API/source manifest with the implementation.

# Verification and delivery

Deterministic tests must exercise write/timeout serialization, accepted-receipt advancement, foreign rejection, cancellation suppression, owner release idempotence, borrow completion before recycling and alias transfer — including TWO concurrent attempt writers on one page (the overlap shape, R3/R22). Use real relevant store paths where feasible; fakes must not duplicate the policy under test. Meaningful host verification on both flavors, fresh compile, complete format inventory. Explain Android bitmap host-test limits rather than claiming native proof.

Preserve existing user files, baseline failure evidence and protected report. Use byte-safe edits and unique pre-created evidence paths. Record results and exact meaningful manifest under this ticket. Report completion to parent with expectReply true; stop for review before dependent work. Parent commits/pushes; do not stage or publish independently.

# Accepted delivery

Revision r1 received final Gemini approval. Committed locally as `19697f8cd362e126783b95e0b48d5ff620a38605` (three S1 files only). Each flavor passed 19 focused tests; format debt remains confined to the untouched LiteRT baseline file. Types remain unwired; this closes S1 host verification, not Phase 3 device gates. Prior failed-attempt XML loss and preserved logs are disclosed in [results](results) and the [validation audit](../../validation-audit). Remote publication follows completed-phase delivery.
