# AGENT.md

## Project

Kotlin Android manga/manhwa/manhua reader with ONNX translation.

**Goals:** Correctness, Performance, Memory efficiency, Low-end device support (6 GB RAM minimum)

---

## Workflow

1. **Planning & Investigation** → [`@file:planning.md`](docs/project_context/planning.md)
2. **Implementation** → [`@file:implementing.md`](docs/project_context/implementing.md)

---

## Principles

- **Documentation First** – Read relevant docs before changes
- **Never Guess** – Read implementations; trace dependencies
  - Use codebase search (index available) for faster exploration
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

Remove or update comments that become inaccurate.

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
