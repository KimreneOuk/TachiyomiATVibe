---
kind: spec
title: "Phase 0 independent review — correction ledger and prerequisite gates"
comments: none
---

# Review authority

Gemini 3.8 Flash / high / Antigravity completed the [independent review](../review). It corroborated baseline integrity and observed execution totals while rejecting unsupported causal interpretation and implementation readiness. This ledger records the response; it does not replace original test outcomes.

# Disposition

| Finding | Required correction | Owner / status |
| --- | --- | --- |
| Failure triage overclaims | Use observed reproduction labels. Qualify unclosed fixture/store versus handle ownership; remove unsupported prior-CI statements. Failed lazy-artifact expectations require contract/fixture review, not an assumed production root cause. Real/virtual clock explanation remains a hypothesis. | GLM amendments delivered and Gemini recheck accepted. No new test runs or code changes. |
| Persistence deferred too late | Ground the four failing store methods' expected contracts and cleanup/lifecycle evidence before Phase 3 entry. Any necessary repair/rebaseline is an isolated slice before dependent refactors, not postponed wholesale to Phase 7. | Prerequisite and corrected static audit accepted through Addendum 4. No further broad searches required; runtime discrimination/repairs remain outstanding. |
| Exact diagnostic commands missing | Replace `<3 failing classes>` with the literal three `--tests` filters and task options for each flavor; preserve original full-suite/diagnostic distinction. | GLM amendment delivered and Gemini recheck accepted. |
| Behavior inventory and impact exercises missing | Deliver gathered source/test mapping and three exercises, distinguish signature forwarding from metadata/precondition-field additions; classify known coverage gaps. | GLM corrections delivered; Gemini Addendum 2 accepted static inventory/impact/selector/failure cross-reference scope. Runtime/native/device and store-contract evidence remains outstanding. |
| Device gate deferred for verified cleanup | User subsequently authorized proceeding and deferring phone tests. Close host/static Phase 0 baseline; carry physical/device obligations to relevant Phase 3–4/8 changes. | No hardware data or physical/native success claimed. Phase 1 is authorized; historical reviewer sequencing is superseded by this explicit user decision. |

Gemini accepted the amended build/triage artifacts, the static behavior inventory in Addendum 2, and the corrected store-contract document in Addendum 4. Controlled runtime discrimination and device measurements remain pending. Historical review text is retained so the accepted scope and remaining gates are explicit.

## Addendum 3 — store-audit corrections

Gemini reviewed the audit and confirmed five corrections: `updatePage` returns Unit; “missing pages” belongs to the source/download-delta context; no unique rejection cause has been demonstrated; writer allocation does not prove a persistent channel opened/leaked in the fixtures; unused probes do not exclude other image/reference eligibility checks. GLM applied these amendments and removed the spec's unnecessary completion-status field. Addendum 4 accepted the corrected document. The early store-contract gate and unresolved runtime evidence remain explicit.

# Store-contract prerequisite

**Final documentation disposition:** Gemini Addendum 4 accepted all audit amendments. The following runtime/repair prerequisites remain open; document acceptance does not satisfy them or waive device evidence.

Before Phases 3–4 modify execution, ownership or reader scheduling:

1. Resolve whether pre-registration must materialize manifest page records and how lazy flush publishes RUNNING candidates. Use source/spec and meaningful tests, not either expectation by default.
2. Trace the reopened-page promotion test through candidate eligibility, artifact validation and display projection. Identify whether the test fixture or production violates the intended contract.
3. Identify/drain actual owners of manifest files and persistence jobs through test teardown and store close. Windows undeletable paths corroborate a resource-lifetime issue but do not identify a particular owner without controlled evidence.
4. Record an actionable fix/rebaseline slice if needed; preserve all lease/generation/version checks, committed output and recovery guarantees. Deep storage decomposition stays in Phase 7.

Phase 0's preserved evidence is complete as a host/static baseline under the revised user gate. [Phase 1](../../phase-1-cleanup) starts on explicit user authorization; it does not implicitly authorize store/publication or reader-ownership redesign. Controlled storage investigation remains necessary before dependent changes. Original review findings remain technical evidence, not implementation authorization.

# Later controlled investigation ? 15 October 2026

The [store baseline investigation](../../store-baseline-investigation) supersedes the historical unresolved-cause status for these four specific ProbeTest failures. A helper-only input-stream use scope changed original14/4 to14/0 in bothflavors; production and all test bodies/assertions/store-finalization omissions stayed unchanged. Gemini accepted bounded fixture-reader sufficiency. Corrected diagnostic twins also ground registration materialization and promotion-before-reopen for these fixtures. The root-cause investigation is satisfied; landing the minimal test helper repair and establishing the new baseline remain pending. No generic production store/native safety conclusion follows.

# Published prerequisite completion - 15 October 2026

The [repair closure](../../store-baseline-investigation/repair/closure) supersedes historical pending investigation/repair status for the four ProbeTest failures. Controlled evidence identified the fixture reader leak; the minimal reader repair plus a separately diagnosed trace completion-wait fix were published at `fbbd19d19a49ea1a0b43995f8c0814fc21dfa1ec`. Both full app flavors2254 tests/0 failures/0 errors/1 benchmark skip. The targeted store-contract entry prerequisite is satisfied; device/native and generic production resource safety are separate, unproven obligations.
