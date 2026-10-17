---
title: "Phase 4 execution slices"
kind: ticket
status: 0
assignee: ""
comments: none
---

# Sequence

| Ticket | Dependency | State |
| --- | --- | --- |
| [S1 core queue](s1-core-queue/index.md) | None (Phase 3 closed) | Todo |
| [S2 facade wiring](s2-facade-wiring/index.md) | S1 reviewed | Todo |
| [S3 UI integration](s3-ui-integration/index.md) | S2 reviewed | Todo |
| [S4 retirement](s4-retirement/index.md) | S3 reviewed | Todo |
| [S5 consolidation](s5-consolidation/index.md) | S4 reviewed | Todo |

One implementation owner, one Gradle owner; independent actual-code/evidence review gates each slice. Per-slice validation: focused classes on `testStandardDebugUnitTest`, then `testDevDebugUnitTest`, under the monitored guard with attempt-specific evidence directories, one-use launch claims, preserved fresh XML (including failures), and one read-only Spotless inventory. Formatting-only changes never re-run tests. Production always has exactly one live reader execution path; the old coordinator is never deleted before its replacement coverage exists (S1-S3 deliver it; S4 spends it).
