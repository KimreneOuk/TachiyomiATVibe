---
title: "Phase 2 — clear contracts and suspending boundaries"
kind: ticket
status: 2
assignee: ""
comments: none
---

# Authorization and baseline

User explicitly authorized starting Phase 2 on architecture-cleanup, with GLM 5.3 Flash implementation and Gemini 3.8 Flash/high through Antigravity review. This maintainability phase is separate from the older OCR phase-2-instrumentation artifacts. Starting local/remote head is dab5c124; tracked tree clean, original user report remains untracked and unchanged.

Use the [simplicity-first roadmap](../translation-simplicity-investigation/phases), [source reassessment](../translation-simplicity-investigation), and [Phase 1 closure](../phase-1-cleanup/closure). The [older bridge manifest](../translation-step1-plan/boundaries-and-bridges) remains caller evidence, not permission to execute its obsolete Deferred cache, queue, or ownership machinery unchanged.

# Ordered slices

| Slice | Scope | State |
| --- | --- | --- |
| P2.1 | [Move shared vision telemetry and normalization](tickets/diagnostic-ownership) | Complete, reviewed commit d8252cbe |
| P2.2 | [Typed single-page rejections and package enforcement](tickets/typed-outcomes) | Complete, reviewed local commit f015a33f |
| P2.3 | [Shared chapter-context frontier ownership](tickets/context-frontier) | Complete, reviewed local commit aef57913 |
| P2.4 | [Suspending ordered chapter replacement](tickets/chapter-replacement) | Complete, reviewed local commit 2efde3b5 |
| P2.5 | [Suspending source-fingerprint boundary](tickets/source-fingerprints) | Complete, reviewed local commit 999bb9be |
| P2.6 | [Consolidated validation and accounting](tickets/validation) | Complete, reviewed and published at 999bb9be |

One implementation agent owns code edits and Gradle execution. On 15 October 2026 the user switched it from GLM to GPT 6 Luna through Codex, max reasoning and fast mode enabled; the same agent and workspace retain the task. The setting applies from its next turn; any already-running turn or shell keeps its existing execution. Gemini 3.8 Flash/high through Antigravity remains the reviewer. Parent coordinates and integrates accepted commits; Gemini/Antigravity independently reviews actual diffs. Each slice compiles and preserves required behavior before the next starts. No extra agents, devices, models, paid-provider calls, toolchain changes or blanket formatting. Preserve original XML/logs and capture new runs separately with directories created before copying.

# Protected boundaries and known debt

No reader queue rebuild, detached-inpaint fix, engine-role split, native admission/guard changes, or persistence initialization refactor here. Preserve ChapterTranslationStore authority, lease/generation/version/PatchPrecondition fences, candidate/committed display, source identity and completion ordering. PageLifecycle/PageView/PageDisplayProjection remain intact. OCR/LiteRT files may receive necessary package imports only; no algorithm, tensor, backend or model changes.

Four baseline ArtifactOpenProbe failures remain an early Phase 3/4 entry gate. Pre-existing LiteRT wrapping violations and JDK21-local/JDK17-CI parity remain recorded. Physical device and JNI cancellation measurements stay deferred, not asserted passed. An unseen failure is investigated rather than classified away or retried to green.

# Completion requirements

At reviewed phase completion, commit all approved in-scope changes, push to `origin/architecture-cleanup`, verify the remote commit matches local HEAD and record that commit in the closure artifact. This is the user's standing instruction for every phase, recorded in the roadmap. P2.1's local slice commit is not yet a Phase 2 completion/publication claim. Exclude the original untracked user report from staging.

UI severity/actions/accessibility must derive from typed semantics, never diagnostic sentences. Engines and presentation have no pipeline implementation imports, with a fast regression check. Context frontier is owned by its shared context package without policy change. Chapter replacement preserves cancel → clear transient state → queue replacement ordering while suspending. Fingerprint lookup exposes I/O explicitly, retains lazy/fail-closed source evidence, and does not hash a pristine entire chapter as a hidden migration cost. Final full suites/builds/checks retain failure provenance and exact file/line accounting; no all-green or performance claim without evidence.

# Published phase closure

[Phase 2 closure](epics/699de408-00ac-4fe5-a903-b541c23063cc/artifacts/phase-2-contracts/closure/index.md): reviewed commits pushed; local and remote architecture-cleanup verified equal at `999bb9be6563f957ee508f739ba2f042bf1c90ee`. Host validation accepted with four unchanged baseline app failures and recorded LiteRT/JDK/device/store gates. Phase 3 has not started.
