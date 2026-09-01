# Task T914 — Batch, Auto, and Manual Translation Co-existence & Failure Investigation

## Objective

Conduct a comprehensive team investigation into:
1. How batch translation works under the hood and why it is currently not working.
2. How batch translation co-exists with manual and automatic (rolling) translation in the Reader.
3. Storage, I/O, ownership, and artifact caching/loading across foreground (Reader) and background (Batch).
4. Race conditions, handoffs, and synchronization between Reader and Batch processes.
5. Lifecycle states: what happens during app startup, before translation, during translation, and after translation.
6. UX/UI communication and visual indicators to prevent user confusion and race conditions.
7. Executive synthesis explaining the entire system, root causes, and recommended roadmap in clear, decision-maker terms.

## Scope & Constraints

- Follow AGENTS.md and repository standards.
- Android 8.0+, bounded memory, min 6GB RAM target devices.
- Preserve reader stability and normal manga reading performance.
- Read live source code, tests, and active architecture plans as primary evidence.
- Specialist outputs must be written to durable markdown files under this task directory.

## Team Deliverables

1. engineering/technical-investigation.md (Technical Lead)
2. review/audit-report.md (Reviewer / Auditor)
3. EXECUTIVE_REPORT.md / Main Leader Synthesis
