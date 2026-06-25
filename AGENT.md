# AGENTS.md

## Project Overview

This project is a Kotlin Android manga/manhwa/manhua reader with integrated translation features using ONNX models.

Primary goals:

1. Correctness
2. Performance
3. Memory efficiency
4. Low-end device compatibility

Target devices may have as little as 6 GB physical RAM and limited heap availability. All implementations must operate reliably within these constraints.

---

## Documentation First

Before making changes:

1. Read relevant documentation in `docs/`.
2. Understand the existing architecture and design decisions.
3. Follow established patterns before introducing new ones.

Only read documentation relevant to the subsystem being modified.

---

## Investigation Rules

Never guess.

Before using or modifying any:

* Function
* Class
* Interface
* File path
* Package
* Resource

Locate and read the actual implementation.

Trace relevant callers, dependencies, data flow, and side effects.

If evidence cannot be found, ask for clarification rather than inventing behavior.

---

## Scope Control

Prefer the smallest safe change.

Do not perform unrelated:

* Refactors
* Cleanups
* Formatting changes
* Architectural rewrites
* Dependency updates

Unless:

* Explicitly requested
* Required for correctness
* Required to prevent breakage

If the task specifies particular files, modify only those files.

---

## Code Changes

When modifying code:

* Maintain consistency with existing architecture.
* Consider performance and memory impact before introducing allocations, caches, buffers, background work, or new dependencies.
* Avoid unnecessary complexity.
* Update any documentation, KDoc, or comments that become inaccurate.

---

## Test-Driven Change Policy

For behavior changes, use a test-first workflow when practical:

1. Inspect existing tests for the affected subsystem.
2. If relevant tests already exist, run or review them before editing.
3. If coverage is missing, design the smallest automatic test that proves the intended behavior.
4. Make the code change.
5. Run the smallest relevant test set.
6. Report what was tested and what was not.

Do not add tests for purely mechanical edits unless they protect against a real regression.

Do not create broad, brittle, or unrelated tests just to satisfy process.

---

## Documentation

Update documentation when:

* Public behavior changes
* Architecture changes
* User-visible behavior changes
* Existing documentation becomes inaccurate

Do not create unrelated documentation.

---

## Comments

Prefer self-documenting code.

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
