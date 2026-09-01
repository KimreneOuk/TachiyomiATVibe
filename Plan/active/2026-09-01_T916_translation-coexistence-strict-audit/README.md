# Task T916 — Strict Audit of Translation Subsystem Coexistence Specification

## Objective

Adversarially audit `docs/architecture/translation-subsystem-coexistence.md` against the current implementation and tests. The Director reports that batch translation does not coexist correctly with manual and rolling-auto translation, and expects the audit to find documentation defects, logic defects, missing contracts, unhandled races, and edge cases—not to defend the document.

## Required questions

1. Which claims in the document are verified, contradicted, unsupported, misleading, stale, or non-testable?
2. What does the code actually do when batch, rolling auto, and manual work overlap on the same chapter, same page, different chapters, and during cancellation/retry/reset?
3. Where can duplicate native work, duplicate provider calls, stale writes, starvation, UI inconsistency, context corruption, artifact mismatch, or lifecycle leakage occur?
4. Which claimed guarantees rely on primitives that do not provide the stated property (for example fairness, priority, atomicity, process scope, or persistence)?
5. Which state-machine transitions, ownership rules, invariants, failure policies, and tests are missing from the specification?
6. What evidence would prove or disprove each material concern?

## Scope and constraints

- Audit only; do not change production code or the target architecture document.
- Treat live source and tests as primary evidence. Prior T914/T915 reports are context, not authority.
- Distinguish code defects from documentation defects and from unknowns requiring runtime evidence.
- Cite exact file and line evidence for material findings.
- Preserve Android 8.0+, bounded memory, reader stability, and normal-manga non-regression constraints.

## Deliverables

- `engineering/actual-coexistence-trace.md` — Technical Lead trace of actual code behavior, contradictions, and missing contracts.
- `review/strict-adversarial-audit.md` — Independent Reviewer findings with severity, likelihood, classification, and confirmation/refutation evidence.
- `STRICT_AUDIT_REPORT.md` — Main Leader synthesis and prioritized attack questions for the Director.
