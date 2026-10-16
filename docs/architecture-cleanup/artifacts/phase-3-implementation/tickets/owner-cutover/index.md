---
title: "S5 - atomic fork/join page-owner cutover"
kind: ticket
status: 1
assignee: ""
comments: none
---

# Governing contract

[Execution](../..) and the [revised overlap plan](../../../phase-3-planning/plan) govern this ticket (the revised overlap plan is FINAL — independent Gemini recheck approved it; the plan's own §1/§16 record the approval). Follow the plan's mechanism, failure matrix and physical gates. Current user-selected roles: Gemini3.8Flash/high through Antigravity implements; GLM5.3Flash/max reviews actual diff and evidence. The [current execution policy](epics/699de408-00ac-4fe5-a903-b541c23063cc/artifacts/execution-policy/index.md) overrides older role assignments. No subagents or concurrent Gradle owner.

# Dependencies

S1-S4 accepted.

# Scope

One atomic producer/consumer/boundary slice implementing plan sections 3, 11 and 14 (plan sections FINAL and approved — this scope restates the approved mechanism, no new design choice): after OCR the reader boundary forks a structured scope — native branch: inpaint segment (own `withNativeLane`/quarantine run + `nativeTimeoutMs`) holding ONLY JNI inference + `CleanedOwner.produce` + local release; receipt registration + NC enrollment + persist-tail enqueue at BOUNDARY scope after the segment exits; external branch: `translateSinglePageHttpOnly` (SMALL EXTRACTION of SinglePageHttpRenderPhase:211-660 at the :662 await seam — provider call, classification, translation publication; NO native permit; `singlePageTimeoutMs`); bounded join of the two bounded branches; POST-JOIN `renderSinglePageTranslated` (:704-795 content + persist block minus dead await; render permit, SINGLE authoritative estimator, draw, commit via attempt.write). Remove the detached scope, `Pair`/`inpaintJob`, the await/cancel branches (662-695, 1069-1079) and the early estimator (:1204) with ALL dependent consumers together; prepare path = OCR + inline inpaint in ONE lane segment; branch-private working `PageTranslation` instances with defensive `immutableCopy()` masks; store via FIELD-SPECIFIC authority merges only; idempotent `markTimeout` with the terminal marker written POST-JOIN per the section 14 coordination table (native outcome NEVER cancels the sibling — paid-provider policy preserved; only genuine caller cancellation cancels provider work); reader-only admitted retry (segment 3) with in-block output capture and its estimator re-run retained (background changed); physical/semantic tail split with the stated queue-site moves; adapt every listed recycler including prepared boundary. Preserve batch admission/wiring, algorithms, textless/null/alias behavior and committed-display/source/user-edit fences. No new provider calls; no new DTO/framework class.

# Verification and delivery

Plan sections 13-17 and the full race matrix govern (C1-C7 coordination table included). Include abandonment (both native segments), actual borrower exit, alias/no-erase/textless, stale tails both drain/orphan, TRUE overlap via a narrowly added harness inpaint gate (CoexistenceBarrier has no native-inpaint point today — test-only seam, no invented production seam), BOTH completion orders, cancel mid-join with a paid-call test (provider-call cancellation only via genuine caller cancellation), native-timeout-with-completed-translation (store truth outranks timers), both-terminal race, two concurrent attempt writers, field-merge isolation (no stale whole-object replacement), defensive mask copies verified by test, retry reader+batch non-reentrancy, tail suppression drain+orphan, and publication fences. Complete all consumers before compilation; no always-null compatibility field for sequencing. Fresh focused verification both flavors before consolidation. Device D1-D4 remain explicit.

Preserve existing user files, baseline failure evidence and protected report. Use byte-safe edits and unique pre-created evidence paths. Record results and exact meaningful manifest under this ticket. Report completion to parent with expectReply true; stop for review before dependent work. Parent commits/pushes; do not stage or publish independently.

# Start pin and implementation gates

S1-S4 are accepted; S4 committed as `ff21fd15cfd7b5d359095ccfc26710452ecc0378`. The final overlap plan review approved this direction; stale proposed wording above has been removed. This is one atomic producer/consumer cutover, not separately published partial wiring. First provide the source-refreshed manifest and concrete interface/ownership orchestration checkpoint so current S1-S4 APIs and all consumers are accounted for. Do not redesign the approved product behavior. Before any Gradle run, coordinator inspects the actual changed cleanup/test structure and exact targeted StandardDebug/DevDebug launch plan per the [validation audit](../../validation-audit).
