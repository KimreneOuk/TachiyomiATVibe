---
kind: review
title: "Architecture audit: coverage, gaps, and loading-plan boundaries"
comments: none
---

# Assessment

**Partly considered, not fully incorporated.** The earlier pipeline audit covered execution lifetime, performance accounting, blocking bridges, recovery and publication safeguards. The loading plan then deliberately prioritized chapter-list availability and entry into already translated chapters. It is not a complete subsystem architecture refactor.

This crosswalk compares the owner's supplied October 14 architecture report against the current local source and existing artifacts. Source baseline: `4de121bf7ed486e9f39b5a3f4f5c4288f439c8c8`; working tree clean before this review. No build, test suite, device benchmark or runtime race reproduction was performed. Source references below are relative to `app/src/main/java/eu/kanade/translation/` unless stated otherwise. The supplied report's remote-main links and subjective score were not independently revalidated.

# Coverage of F-01 through F-10

| Finding | Previous coverage | Remaining work |
| --- | --- | --- |
| F-01: callback forwarding and change amplification | Execution paths were mapped; the loading plan does not reduce stage callback chains. | Add the three change-impact exercises for OCR metadata, inpaint retry input and cleaned publication preconditions before choosing cohesive capabilities. Constructors in `pipeline/batch/BatchChapterTranslator.kt:79` and `BatchLaneWorkers.kt:95` visibly expose substantial operations and forward them. No fixed number of required edits has been proved. |
| F-02: detached inpaint lifecycle | Explicit earlier R1 / round-2 lifetime and deadline finding. Native quarantine is protected. | Verify launch-to-handoff cancellation, native exit, bitmap cleanup, publication authority, chapter switch and engine teardown. This is an unresolved correctness investigation, not a proven speedup or a delivered fix. |
| F-03: reverse dependencies | Package ownership was used as orientation; repair and CI enforcement were not loading deliverables. | Source confirms both engine-to-pipeline imports and context-to-batch import. Establish narrow ownership rules and automated checks, then relocate shared helpers or extract shared behavior without transferring pipeline policy into models. |
| F-04: string rejection semantics | Typed loading badge results were proposed, but page rejection semantics were not covered. | Replace presentation decisions based on `TranslationPipeline.REASON_TRANSLATION_NOT_SAVED` with typed rejection semantics; keep diagnostic detail separate from labels and accessibility copy. |
| F-05: broad orchestration responsibilities | Store opening, journal hydration and shared probes were examined for loading. | Composition/execution separation and persistence-internal extraction remain separate work. Keep live mutation, lease and synchronization authority in `ChapterTranslationStore`; file size alone is not an acceptance criterion. |
| F-06: recognition/inpainting overlap | Known in the execution map; no contract separation was planned. | Review `engines/vision/ocr/PageRecognitionEngine.kt:7`, which already declares both `analyze` and `inpaint`. The overlap is in the contract as well as the ROI implementation. Reuse existing inpainting components before introducing wrappers. Coordinate shared OCR files with the separate OCR/LiteRT effort. |
| F-07: mutable stage data and bitmaps | Resource lifetimes and immutable early-reader bundles were loading constraints. | A bundle does not fix mutable translator inputs. `engines/translator/TextTranslator.kt:16` mutates page maps; `model/PageTranslation.kt:91` retains a transient bitmap. Establish per-operation ownership and incremental stage result contracts. Mutable design alone does not prove a race. |
| F-08: state projections | Strongly aligned: candidate/committed separation, honest badge states and writer readiness are preserved. | Investigate duplicate rules individually. Do not collapse durable state, execution state, committed display and UI copy into another state machine. |
| F-09: synchronous bridges | Earlier fingerprint finding and loading-path analysis covered some bridges. | Audit all remaining real callers and thread contexts. Preserve synchronous cancellation completion ordering. Loading work does not establish that every remaining `runBlocking` is UI-reachable. |
| F-10: names, documentation and enforcement | Existing architecture documentation was read and used. | Ownership-based naming updates and package checks remain architecture work. Update documentation after responsibilities are verified; no mass rename or line-count target. |

# Corrections and qualifications that affect decisions

1. **Detached does not mean impossible to cancel.** `pipeline/SinglePageOnnxPhase.kt:1182` creates an unparented scope. However, `SinglePageHttpRenderPhase.kt:1068` explicitly attempts to cancel an active deferred in its `finally` block. Earlier shorthand describing the task as universally "uncancellable" was too strong. That cleanup does not establish safe lifetime management when cancellation happens before entry into that phase, or after native work has begun.
2. **Native admission ends before Auto awaits inpainting.** The native block closes at `TranslationPipeline.kt:927`; Auto awaits at `:956-963`. Its producer still waits for the tail, but this is distinct from holding the native permit throughout the wait. Native quarantine, engine locking and the detached deferred are different ownership mechanisms.
3. **Overlap differs by mode.** The manual HTTP/render path awaits the inpaint deferred at `SinglePageHttpRenderPhase.kt:662-664`, after provider work. Auto awaits during prepare, before handing that page to its translation consumer; preparation can overlap another page's provider work. A local `coroutineScope { async(...) }` that waits before returning can change the manual critical path. No universal "false overlap" conclusion or contention magnitude is supported here.
4. **The synchronous reader API exists, but it is not the ordinary downloaded-reader path.** `TranslationManager.getChapterTranslation()` blocks at `:1220`; `tachiyomi/ui/reader/loader/DownloadPageLoader.kt:70-84` normally selects the suspending `getChapterTranslationForReader` when a chapter ID exists and uses the synchronous fallback without an ID. Caller context must determine actual Main-thread risk. `cancelRunningChapterForReplace` and scheduler cancellation bridges require separate ordering analysis.
5. **Planned hardening is not runtime validation.** Prior descriptions that critique findings were "resolved" refer to specification changes. Observed revisions, scoped invalidation, shared probe lifetime and progressive-open lock tenure have not been implemented or tested. LP-02 must define one coherent identity revision authority across active stores, probes, mutations and reopens; two independently incremented counters do not become comparable merely by checking equality.

# How to integrate this report

The report's preferred direction is compatible with the loading plan: preserve separate scheduling policies, singular live-state authority, publication fences, committed-output visibility and native quarantine while reducing unnecessary interaction among operations.

| Workstream | Next evidence / acceptance gate | Relation to loading work |
| --- | --- | --- |
| Operation and resource ownership | Explicit request/native/publication lifetime map; cancellation race tests; no premature resource disposal or stale commit; preserve observed native/provider overlap. | Highest correctness priority. Track beside loading work; require these gates for any loading change that touches execution teardown or ownership. |
| Change isolation | Record actual semantic edits versus signature-only forwarding in the three report scenarios; choose a small shared capability only when it removes demonstrated coupling. | Separate architecture workstream. Does not need to delay independent pure list/cache fixes. |
| Dependency and outcome contracts | Narrow package rules; typed rejection tests insensitive to diagnostic wording; automated checks that detect forbidden imports. | Separate, bounded refactors. The badge proposal alone does not cover these outcomes. |
| Cohesion and mutable stage boundaries | Identify existing reusable contracts and single owners; extract one responsibility at a time; preserve store authority. | Follow the preceding investigations, coordinated with OCR/LiteRT ownership. |
| Performance and regression | Matched mode-specific timing, memory, native occupancy and throughput; focused cancellation/recovery tests from the report's matrix. | Required before declaring changed behavior safe or faster. Static source checks do not satisfy these gates. |

Do not bundle model migration, mutable-model conversion, scheduler changes and persistence refactoring into the loading changeset. The broader report supplies additional work and preservation constraints; it does not itself authorize production refactoring.

# Related evidence

- [Loading plan](..)
- [Loading validation and delivery](../validation-and-delivery)
- [Round-2 consolidated audit and evidence qualifications](../../pipeline-efficiency-audit/round-2)
- [Verified execution follow-up](../../pipeline-efficiency-audit/round-2/verified-follow-up)
- [Independent loading-plan critique](../independent-review)
