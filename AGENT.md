# AGENT.md

## Project

Kotlin Android manga/manhwa/manhua reader with ONNX translation.

**Goals:** Correctness, Performance, Memory efficiency, Low-end device support (6 GB RAM minimum)

---

## Workflow

1. **Planning & Investigation** → [`@file:planning.md`](docs/project_context/planning.md)
2. **Implementation** → [`@file:implementing.md`](docs/project_context/implementing.md)
3. **Artifact organization** → [`@file:knowledge_base.md`](docs/project_context/knowledge_base.md)

## Autonomy Policy

Proceed automatically for low-risk, reversible work. For normal bugs and
features, inspect the evidence, create and internally challenge a plan,
implement the smallest defensible change, add regression coverage, run
targeted validation, and update the relevant handoff.

Require user approval before:

- architecture rewrites or broad cross-module refactors;
- database migrations, public API changes, or dependency replacement;
- destructive or difficult-to-reverse operations;
- materially ambiguous behavior or scope expansion;
- changes that cannot be safely validated with available tests.

Do not request approval for file inspection, test discovery, targeted test
creation, safe local validation, or routine documentation updates.

For large work, use checkpoints: establish a baseline, implement an isolated
part, validate it, then review the diff before continuing. Report unexpected
findings when they change the plan or the next checkpoint.

## Knowledge Base Organization

Every substantial task must leave its reasoning in the repository. Use the
routing rules in [`docs/project_context/knowledge_base.md`](docs/project_context/knowledge_base.md).
New task material belongs in a dated subfolder under `Plan/active/`; stable
architecture and workflow knowledge belongs under `docs/`. Do not create a
second competing folder or scatter task notes through module directories.

If the user returns brainstorming from an external AI, preserve it in the
task's `external/brainstorm_inbox.md`, verify it against the codebase, and
update the active design or handoff without requiring a detailed follow-up
instruction.

Create the `external/` files only when the user explicitly asks for an
external brainstorming packet or context package. Do not create them during
ordinary investigation.

---

## Principles

- **Documentation First** – Read relevant docs before changes
- **Never Guess** – Read implementations; trace dependencies
  - Use codebase search (index available) for faster exploration, this ensure you can get faster result and know where the code exist in which files.
  - **Comments describe intent; code is the source of truth.** When the two disagree, the code wins and the comment is stale. Treat a comment as a *hypothesis* to verify against the live code path, never as evidence. This codebase has shipped comments that contradicted the running behavior (e.g. a "translation ONNX sessions are CPU-only" comment while the live path requested NNAPI; a "NPU outputs black shapes" comment describing a past failure as if it were current). Acting on such comments without verifying caused wrong recommendations. Before relying on a comment's claim — about behavior, configuration, performance, or a "deliberate decision" — confirm it against the actual call sites, dependencies, and runtime path. If a comment's claim cannot be backed by code, flag the comment as stale and treat the code as authoritative.
- **Smallest Safe Change** – Only change what's necessary
- **Architecture Consistency** – Follow existing patterns; consider performance/memory
- **Test-Driven** – Test before/alongside implementation changes

---

## Rules

- Update docs when public behavior, architecture, or user-visible behavior changes
- Prefer self-documenting code
- Do not create unrelated documentation or comments

---

Do not add comments that simply restate what the code does.

Only add comments when they explain:

* Why a decision was made
* Performance considerations
* Memory constraints
* Non-obvious behavior
* Algorithm details

Remove or update comments that become inaccurate. A stale comment is a defect, not a cosmetic issue: a comment claiming behavior the code no longer does will mislead the next reader (human or agent) into wrong decisions. When you change behavior, update the comment in the same change. When you find a comment that contradicts the code, fix it (reword to match, mark as historical, or delete) rather than leaving it.

Public APIs and complex algorithms may use concise KDoc when it improves understanding.

---

## Validation

Before finishing:

1. Verify referenced symbols exist.
2. Verify imports, paths, and APIs are correct.
3. Run the smallest relevant validation available.
4. Clearly report any validation not performed.

Do not claim code compiles, tests pass, or builds succeed unless verified.

---

## Error Handling

Never suppress errors or warnings without understanding the root cause.

Fix causes rather than hiding symptoms.

Maintain visibility into failures.

---

## Agent Delegation

Use additional agents only when work can be meaningfully performed in parallel.

Examples:

* Implementation
* Tests
* Documentation
* Independent subsystem investigations

Do not spawn additional agents for:

* Small single-file edits
* Simple renames
* Small documentation updates
* Narrow localized changes

Prefer one agent whenever practical.

---

## Communication Style

Work silently during routine investigation and implementation.

Communicate when:

* Presenting findings
* Explaining decisions
* Proposing a plan
* Reporting results
* Clarifying ambiguity

Keep responses concise and purposeful.

---

## Completion Report

At completion, report:

* Files modified
* Tests added or updated
* Validation performed
* Validation not performed
* Any risks, assumptions, or follow-up work

---

## Core Principles

* Documentation-driven development
* Test-driven behavior changes
* Performance-first mindset
* Memory-conscious design
* Understand before changing
* Verify before claiming
* Smallest safe change
* No guessing
