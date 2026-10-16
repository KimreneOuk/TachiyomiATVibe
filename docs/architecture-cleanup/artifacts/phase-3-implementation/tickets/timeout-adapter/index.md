---
title: "S3 - protected timeout callback chain"
kind: ticket
status: 2
assignee: ""
comments: none
---

# Governing contract

[Execution](../..) and the [approved plan](../../../phase-3-planning/plan) govern this ticket. Follow the plan's mechanism, failure matrix and physical gates. GLM5.3Flash/max implements; Gemini3.8Flash/high through Antigravity reviews actual diff and evidence. No subagents or concurrent Gradle owner.

# Dependencies

S2 accepted.

# Scope

Protect marker plus onPageStuck getter/invoke at EngineLane choke point so callback failures cannot bypass quarantine real-exit waiting. Preserve truthful markerCompleted diagnostics. The adapter serves every lane segment (OCR, inpaint fork segment, reader retry segment). Do not change quarantine internals or invent marker-fallback success.

# Verification and delivery

Latch-driven native stub proves marker/getter/invoke throws and caller cancellation cannot release admission before actual block exit. No sleeps or JNI termination claims. Fresh focused checks both flavors.

Preserve existing user files, baseline failure evidence and protected report. Use byte-safe edits and unique pre-created evidence paths. Record results and exact meaningful manifest under this ticket. Report completion to parent with expectReply true; stop for review before dependent work. Parent commits/pushes; do not stage or publish independently.

# Start pin

S2 accepted and committed as `c0e3ded43ee52c8bb733c2113284a21dead0e330`. S3 is only the protected timeout callback chain at EngineLane and focused proofs; AttemptAuthority/CleanedOwner production wiring remains S5. Follow the [validation audit](../../validation-audit) for guarded focused runs, finally-protected test gates, mechanical launch preflight and immutable attempt evidence.

# Accepted delivery

Revision r1 approved by Gemini and committed locally as `999bd6a3932a9163f65f3656e426e729584ff0af` (EngineLane plus the new four-test adapter class only). Actual StandardDebug and DevDebug each passed six focused tests, including the two unchanged quarantine regressions. Prior wrong-variant/redundant r0 runs remain disclosed in [results](results). Native device behavior and callback-injected cancellation remain outside this executed proof.
