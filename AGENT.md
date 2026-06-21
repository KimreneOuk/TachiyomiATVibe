## Project Overview

This project is a **mobile manga / manhwa / manhua reader** built in **Kotlin**, featuring **forked translation capabilities**.  
It leverages **ONNX models** and supporting libraries as part of its processing pipeline.

***

## Performance & Resource Constraints

When designing any **pipeline or feature**, always prioritize:

* **Memory efficiency (OOM prevention)**
* **Runtime performance**
* **Low-end device compatibility**

### Target Device Constraints

* Devices with **\~6 GB physical RAM**
* Only **20–30% heap availability**

All implementations **must operate reliably within these limits**.

***

## Documentation-First Approach

Before starting any investigation or development task:

* **Always read the `@docs` folder first**
  * Contains:
    * Architecture overview
    * Design decisions
    * System constraints
    * Key implementation details

This ensures all work is aligned with the **existing system design and intent**.

***

## Development & Debugging Guidelines

For any **debugging, implementation, or system design task**:

1. **Understand the Codebase Thoroughly**
   * Read relevant files end-to-end
   * Trace:
     * Data flow
     * Dependencies
     * Execution paths

2. **Work With Context**
   * Avoid assumptions
   * Base all decisions on:
     * Existing architecture
     * Established patterns
     * Project constraints

3. **Produce Grounded Solutions**
   * Ensure changes are consistent with system design
   * Avoid introducing unnecessary complexity

***

## Code Change Requirements

When modifying or adding code:

* ✅ **Update documentation in `@docs` immediately**
* ✅ Ensure all changes are **well-documented and traceable**
* ✅ Maintain consistency with existing architecture

***

## Testing Policy

* Always create or update **test cases** alongside code changes
* Verify:
  * Functional correctness
  * Performance impact
  * Memory usage

***

## Error Handling Policy

* ❌ **Never suppress errors or warnings**
* ✅ Always:
  * Investigate root causes
  * Resolve issues properly
  * Maintain visibility into failures

***

## Tooling & Debugging Utilities

Use all available tools when necessary, including:

* `logcat`
* **ADB (wireless debugging)**
* Any relevant profiling or debugging tools

Leverage these tools to:

* Diagnose issues efficiently
* Validate runtime behavior
* Monitor performance and memory usage

***

## Communication Style

Keep commentary minimal and purposeful.

* Avoid talking at every turn during routine work such as reading files, writing code, or running tools.
* Only provide commentary when it is genuinely needed, such as:
  * Giving a final report
  * Explaining a decision or finding
  * Sharing a plan
  * Asking a clarifying question
* Prefer silent progress during implementation and reserve speech for meaningful handoffs or decisions.

***

## Key Principles

* Performance-first mindset
* Memory-conscious design
* Documentation-driven development
* Thorough understanding before action
* No shortcuts in error handling or testing
