---
title: "S5 - consolidation and regression validation"
kind: ticket
status: 0
assignee: ""
comments: none
---

# Goal

Consolidated regression validation of the whole Phase 4 surface, adapter-zero proof, behavior-ledger sign-off, and artifact updates. Broad suites are justified here by the consolidation boundary.

# Work items

1. Focused-then-broad run plan: queue + scheduler + workflow + coexistence + reader-UI classes first on StandardDebug, then DevDebug; if green, one broader translation-package run per flavor (the justified consolidation sweep). Guarded, attempt-specific evidence, fresh XML preserved.
2. Temporary-adapter census: snapshot adapter and every counted shim must be gone; record the census result.
3. Behavior ledger table (plan §Required behavior ledger) signed off item by item against executed tests; items only expressible on device are recorded as deferred gates, not claims.
4. File accounting reconciliation vs the plan table (−1 production, −7 tests, rename net zero); deviations recorded, not silently compensated.
5. Artifact updates: this index and the survey/plan gain execution evidence links; the phases index Phase 4 row status is updated only per parent policy.
6. Spotless full-inventory pass; known LiteRT baseline hunks expected, no new violations.

# Gates

All executed runs green or explicitly owned failures preserved; no unresolved adapter; ledger complete; device/physical gates (foreground latency, serial-vs-overlap acceptance, teardown-on-hardware) recorded as open Phase 4/8 gates. No push. Commit discipline per parent; slices committed only after independent review.
