# TachiyomiATVibe — Translation Architecture Audit & Simplicity-First Rebuild Plan

**Status:** Final consolidated architectural direction (planning document; not an implementation log)  
**Date:** 14 October 2026  
**Repository:** [KimreneOuk/TachiyomiATVibe](https://github.com/KimreneOuk/TachiyomiATVibe)  
**Audited scope:** `app/src/main/java/eu/kanade/translation/`  
**Audited revision:** [`4de121bf7ed486e9f39b5a3f4f5c4288f439c8c8`](https://github.com/KimreneOuk/TachiyomiATVibe/commit/4de121bf7ed486e9f39b5a3f4f5c4288f439c8c8)  
**Sources:** Two external architecture reviews, their follow-up reconciliations, and targeted repository source inspection.  
**Verification limitation:** Static source review only. No build, unit tests, emulator tests, device performance measurements, or race reproductions were run for this report.

> **Architectural decision:** Prioritize correctness, simplicity, and maintainability over maximum throughput. Accept a *measured and consciously chosen* performance cost when it materially reduces complexity. Preserve externally observable correctness guarantees, **not necessarily the current implementation, optimization, class structure, or scheduling machinery**.

---

## 1. Executive decision

The translation subsystem is not lacking in sophistication. It has **too much coordination complexity relative to the desired maintainability**. Multiple orchestrators, extensive callback forwarding, transient state models, resource-management mechanisms, and concurrency optimizations make changes spread across files and increase the number of possible interactions.

The goal is **not** a cosmetic refactor or a rewrite that blindly preserves all existing optimizations. The goal is to find the **simplest design that reliably supports the product's required behavior**.

### Priority order

1. **Correctness and data integrity:** Never corrupt or falsely publish translations.
2. **Simplicity:** Prefer straightforward control flow, obvious ownership, and fewer moving parts.
3. **Maintainability and change isolation:** A change should generally remain within its responsible component.
4. **Testability:** The essential behaviors and races must be easy to verify.
5. **Performance:** Optimize only when the measured benefit justifies the implementation and maintenance cost.

**Working principle:** Simpler code is not automatically correct; complexity should be removed **while testing the behavior that complexity was intended to protect**.

### What success looks like

- A developer can identify **one owner** for a feature, task, state transition, or resource.
- A local OCR/inpaint/provider change rarely requires adjusting intermediate orchestrator constructor signatures.
- Manual and automatic reader translation use a simple, intelligible execution policy; batch remains predictable and recoverable.
- The app does **not** depend on unnecessary speculative concurrency, adaptive knobs, or redundant state bookkeeping.
- The implementation is smaller and easier to navigate **without converting it into a handful of giant god-classes**.
- Resource cleanup, cancelled-job exclusion, atomic publication, and recovery still work.
- Any performance loss is measured on representative workloads and explicitly accepted.

**Not a goal:** Reaching an arbitrary source-file count, such as 290 → 115. A large reduction is welcome if justified, but a numerical target must not dictate architecture.

---

## 2. Current architecture: strengths and defects

### Existing strengths to retain as behaviors

- **Single live chapter-state authority** with guarded updates.
- **Generation/version/lease checks** preventing outdated workers from publishing results.
- **Candidate-versus-committed output separation** so an incomplete attempt cannot replace the last good display result.
- **Durable artifact publication and recovery** for interrupted chapters.
- **Explicit native-work admission and teardown safety**: cancelled coroutines do not prove JNI/ONNX/LiteRT work has exited.
- **Existing production/test contracts** for reader and batch modes, including distinct views for durable progress, transient scheduling, and display readiness.

These are necessary outcomes. Their **current classes and implementation details are not sacred**.

### Core architectural defects

1. **Too much stage knowledge in orchestration:** `TranslationPipeline`, `BatchChapterTranslator`, and `BatchLaneWorkers` forward low-level operations through long callback chains.
2. **Unclear ownership of overlapping tasks:** Detached single-page inpainting, native quarantine, and mutable bitmaps interact through several layers.
3. **Shared mutable block data:** Background color estimation can touch the same block instances modified during provider translation.
4. **Reverse dependencies:** Engine and chapter-context code import pipeline implementation details.
5. **Stringly typed contracts:** UI outcome logic branches on internal human-readable error text.
6. **Potentially overengineered reader scheduling:** The auto coordinator has extensive lifecycle, retry, prefetch, and drain logic. Some of it may be unnecessary for the intended user experience.
7. **Blocking bridges:** Some operations synchronize async IO into synchronous APIs, including inside `ConcurrentHashMap.computeIfAbsent`.
8. **Oversized central responsibilities and naming drift:** Major classes accumulate several reasons to change; some names no longer describe their full responsibility.

### Confirmed source facts

| Evidence | Verified at audited revision |
|---|---|
| `BatchChapterTranslator` constructor | **24 parameters**, including **20 function-typed** parameters reported in the subsequent audit |
| `BatchLaneWorkers` constructor | **39 parameters**, including **11 function-typed** parameters reported in the subsequent audit |
| `SinglePageOnnxPhase` | Launches inpainting using `CoroutineScope(Dispatchers.Default).async` around line 1182 |
| `NativeRunQuarantine` | Removes caller `Job` with `currentCoroutineContext().minusKey(Job)` and drains native exit under `NonCancellable` |
| `RenderColorEstimator.recomputeFor` | Mutates `textColor` / `strokeColor` on supplied blocks and reads block text/translation |
| `SinglePageHttpRenderPhase` | Sorts and translates mutable blocks while inpainting may overlap; later re-estimates colors before render |
| `TranslationBlock` | Already has `blockId: String?` |
| `StableBlockIds` | Already provides persisted, reading-order-independent block identity for batch flows |
| `LazySourceFingerprints` | Calls `runBlocking(Dispatchers.IO)` inside `ConcurrentHashMap.computeIfAbsent` |
| `ChapterContextService` | Imports `pipeline.batch.BatchContextFrontier` |
| `RoiPageRecognitionEngine` | Imports pipeline normalization and implements both recognition and inpainting |
| `PaddlePageOcrCoordinator` | Imports `pipeline.NativeVisionTelemetry` |
| `TranslationUiTruth` | Compares a rejection reason to `TranslationPipeline.REASON_TRANSLATION_NOT_SAVED`; interpolates raw reasons into UI strings |
| `docs/translation-architecture.md` | **Exists** and documents package ownership, state authority, native quarantine, and naming conventions |

A design concern is **not** the same thing as a reproduced production bug. The inpainting-handle and shared-block issues are high-confidence **hazards**; the exact user-visible failures remain to be demonstrated through controlled tests.

---

## 3. The intended minimal architecture

Keep the number of concepts small:

```text
Reader request / Batch request
               |
               v
        Session ownership
               |
               v
       Work scheduling
   (Reader / Chapter batch)
               |
               v
       Page processing
  Decode → Detect/OCR → Inpaint
        → Translate → Render
               |
               v
       Guarded publication
  Version/lease checks → atomic commit
               |
               v
     Authoritative chapter store
               |
               v
          Reader display
```

**This is an ownership model, not a demand to create a class for each box.**

### 3.1 Prefer sequential per-page processing by default

The baseline implementation should be easy to trace: one page job, one sequential sequence of stages, one cleanup path. Do not create separate child tasks merely to overlap stages unless benchmarking demonstrates a meaningful benefit to the user.

Potential tradeoff: translating only after inpainting may increase individual page latency. That is acceptable **only after measuring the impact and choosing it knowingly**. Avoid regressions that make the application practically unusable.

**Still allow simple concurrency between independent pages** when necessary for responsive reading; sequential processing *within a page* is not the same as globally serializing the entire app.

### 3.2 One reader execution policy, if simpler

Manual tap and auto scroll should preferably share the same underlying page execution code. A **single reader queue** is a reasonable candidate, with priorities such as:

- **Visible/current page:** Highest priority.
- **Next-page prefetch:** Best effort; limited or disabled under memory pressure.
- **Batch:** Independently admitted according to clear session policy.

The queue must specify deduplication, promotion of an already running prefetch, chapter switching, cancellation, and any paid provider request that cannot safely be abandoned.

**Do not delete `RollingAutoCoordinator` before proving this smaller design covers the required product behavior.** The current coordinator manages generations, memory admission, prepared-page handoffs, retry pauses, and bounded provider drains. Some of those features may be deliberately retired; others are correctness or user-experience requirements.

### 3.3 Batch remains distinct in scheduling, shared in processing

Batch differs from reader work because it requires chapter traversal, contextual chunks, resume, and durable checkpoints. It does **not** need to duplicate low-level OCR/inpainting/rendering behavior.

Keep separate scheduling when semantics differ; share existing processing operations where their contracts really match. Prefer concrete cohesive collaborators over dozens of forwarded suspend lambdas or a new generic plugin framework.

### 3.4 One authority for page publication

The store/publication boundary remains responsible for rejecting stale attempts and committing valid results. Make other stages produce outputs; **do not allow each stage to decide independently that it can overwrite committed output**.

---

## 4. Findings and simplification decisions

### F-01 — Callback forwarding and change amplification

**Severity:** High maintainability · **Evidence:** Confirmed.

`TranslationPipeline` wires `BatchChapterTranslator`, which forwards numerous operations into `BatchLaneWorkers`. Stage implementation signatures leak across multiple intermediate constructors.

**Simplify:** Group genuinely related processing capabilities; pass a small immutable run configuration; keep per-page identity and fences per page. Reuse existing abstractions first. Avoid a mega-interface or generic workflow engine.

**Prove improvement:** Perform controlled changes to OCR output, inpaint retry inputs, and cleaned-image publication logic, then record how many *semantic* file edits are required before and after. Do not claim every change currently touches five files; that generalization is not proven.

### F-02 — Detached inpainting and native cancellation

**Severity:** High correctness investigation · **Evidence:** Unparented job confirmed; leak/failure conditions require tests.

`SinglePageOnnxPhase` returns a `Deferred<Bitmap?>` created in an independent scope. `NativeRunQuarantine.run` deliberately strips the caller job and, upon cancellation, drains a native invocation and can reject its result. If the result carries the only accessible inpainting handle, that handle may not reach the outer caller.

**Simplification preferred:** First ask whether inpainting **needs** its own asynchronously returned job. A sequential OCR → inpaint flow may eliminate the lifetime split and much cleanup machinery. If concurrency is kept, create explicit request-scoped job and bitmap ownership before launch, with deterministic cancellation/drain and stale-result rejection.

**Do not:** Replace native quarantine with ordinary job cancellation. A JNI call may continue after coroutine cancellation. Do not parent inpainting to a native admission scope without checking whether this unintentionally holds admission across the whole inpaint.

### F-03 — Shared mutable block race and duplicate color passes

**Severity:** High investigation · **Evidence:** In-place mutation and shared references confirmed; race consequences unverified.

`RenderColorEstimator.recomputeFor(cleanedBitmap, blocks)` mutates block styling. Background inpainting passes `pageTranslation.blocks.toList()`, a shallow copy retaining the **same block instances**. Meanwhile, provider translation modifies text and the HTTP/render phase reorders blocks. The render phase runs another color-estimation pass after awaiting the cleaned image.

**Simplest first option:** Determine whether the **early** color-estimation pass has any required downstream consumer. If not, remove it and do one final style pass after translation and inpainting join, before rendering. This may simultaneously eliminate a shared-memory hazard and duplicate CPU work.

**Alternative if concurrency remains:** Pass immutable geometry/style inputs; merge derived styling by existing stable block identity. `TranslationBlock.blockId: String?` and `StableBlockIds` already exist—**do not add a competing `blockId: Int`**.

### F-04 — Reverse dependencies and UI string semantics

**Severity:** High architecture · **Evidence:** Confirmed.

- `engines/vision/ocr/RoiPageRecognitionEngine` imports `pipeline.adaptive.DeviceStageNormalization`.
- `engines/vision/ocr/PaddlePageOcrCoordinator` imports `pipeline.NativeVisionTelemetry`.
- `context/ChapterContextService` imports `pipeline.batch.BatchContextFrontier`.
- `presentation/TranslationUiTruth` branches on `TranslationPipeline.REASON_TRANSLATION_NOT_SAVED` and presents raw reason text.

**Simplify:** Place truly shared contracts with their actual low-level/domain owners; replace UI string comparisons with typed rejection categories; leave rich diagnostics separate from user-facing localized messages. Add a narrow CI rule for forbidden imports.

### F-05 — Blocking bridges and cache design

**Severity:** Medium–High · **Evidence:** Confirmed `runBlocking`; thread impact varies by call site.

`LazySourceFingerprints` does synchronous `runBlocking(Dispatchers.IO)` inside `ConcurrentHashMap.computeIfAbsent`. This can block callers and compute under a map-bin synchronization boundary. Other `runBlocking` uses occur in scheduler, manager, and store initialization paths.

**Simplify:** Prefer a straightforward suspending fingerprint preparation/cache API **if its callers can be made suspending**, or explicitly precompute the needed data before entering synchronous map access. Avoid inventing a `Deferred` cache plus new locks unless required. Preserve immediate cancellation/commit ordering where synchronization is intentional.

The second review traced chapter replacement through `launchNonCancellable`, which dispatches to IO; therefore that *specific call path* should not be characterized as proven main-thread blocking.

### F-06 — Reader/auto orchestration complexity

**Severity:** High simplification opportunity · **Evidence:** Complex coordinator exists; redundancy is not yet proven.

`RollingAutoCoordinator` includes prefetch admission, generation ownership, transient snapshots, prepared-page queues, pauses/retries, and provider drain behavior.

**Simplify conditionally:** Draft a smaller, unified reader queue with a complete behavior table. Mark each existing feature **keep / simplify / intentionally remove**. A missing advanced optimization is acceptable when the reduced behavior is intentional and compatible with the core product.

Do not pretend deleting the coordinator is automatically equivalent to preserving it. Prefer a staged replacement behind the existing execution contract, followed by removal of redundant machinery once validated.

### F-07 — Oversized owners and package sprawl

**Severity:** Medium · **Evidence:** Confirmed.

Examples: `ChapterTranslationStore` (~5,061 lines), `ChapterArtifactEngine` (~2,526), `TranslationManager` (~2,175), `NeuralInpaintingCoordinator` under `engines/inpainting/aot` (~2,033), `TranslationPipeline` (~1,472), `BatchLaneWorkers` (~1,331), `SinglePageOnnxPhase` (~1,323).

**Simplify:** Remove demonstrably unnecessary delegation and consolidate narrowly related behavior into cohesive owners; extract genuinely independent journal/serialization work behind store authority. Reduce reasons to change, not just lines per file. Retain exact ownership rules for the store and native sessions.

A one-caller utility can be valuable; a large class can be cohesive. **No automatic deletion based on file length, suffix, or call count.**

### F-08 — Similar names, different state contracts

**Severity:** Low–Medium · **Evidence:** Confirmed distinct semantics.

Do **not** merge durable `ArtifactStage` / `ArtifactStageStatus` with live `PageStage` / `StageStatus`. Durable statuses include `STALE`, `CORRUPT`, `FAILED_RETRYABLE`, and `FAILED_TERMINAL`, which have no simple equivalent in live page state. Similarly, `PageLifecycle`, `PageDisplayProjection`, `PageView`, and `AutoSlotState` have different roles.

**Simplify:** Remove actual duplicate business rules and unnecessary transformations, but preserve intentionally distinct durable, scheduling, and presentation representations. Do not add another page state machine by default.

### F-09 — Engine role/naming ambiguity

**Severity:** Medium · **Evidence:** Confirmed.

`RoiPageRecognitionEngine` performs recognition and inpainting. The `SinglePageOnnxPhase` name also over-specifies one runtime in a multi-backend environment.

**Simplify:** Decide which component truly owns each capability before changing classes. Prefer directly using an existing `PageInpaintingEngine` rather than adding another wrapper. Preserve the shared native guard/drain needed for safe teardown. Rename only as part of a real ownership improvement.

### F-10 — Documentation and test structure

**Severity:** Low–Medium · **Evidence:** Architecture documentation exists; certain references may be stale.

Update `docs/translation-architecture.md` once decisions are implemented and add enforceable package rules. Consolidate **redundant** tests if the same invariants remain protected. Do not delete test suites merely because they mock constructor wiring, target one-caller utilities, or use real clocks. Virtual-time tests are excellent for controlled scheduling; actual JNI/IO races require appropriate integration/device tests.

---

## 5. Simplicity-first rebuild sequence

> **Do one slice at a time.** Establish a baseline, implement a small change, validate its behavior, benchmark where relevant, and then commit. Never combine an architectural rewrite with an unrelated model optimization.

### Phase 0 — Preserve a reproducible baseline

- [ ] Pin the audited commit and record the working branch/commit under test.
- [ ] Run existing applicable unit tests and builds; record failures **before** modifications.
- [ ] Record representative single-page latency, batch throughput, peak memory, and native occupancy on actual supported devices.
- [ ] Create a behavior inventory: manual, auto, batch, cancellation, chapter replacement, recovery, paid provider draining, and image publication.
- [ ] Identify critical tests for store leases, generation fencing, candidate/committed display, journal/manifest recovery, and native teardown.
- [ ] Trace three change-impact scenarios and count unnecessary forwarding edits.

**Exit gate:** Baseline is reproducible and correctness requirements are explicit.

### Phase 1 — Simplify a single-page execution without losing safety

- [ ] Identify all owners of OCR, inpaint, provider, render, bitmap, and native execution tasks.
- [ ] Investigate and test the detached-inpaint/quarantine handoff.
- [ ] Prefer one sequential page-stage lifetime when it simplifies the design sufficiently; benchmark before accepting the change.
- [ ] Confirm whether the early background color estimation is redundant; remove it if safe.
- [ ] Give every decoded/cleaned bitmap one clear owner and a deterministic cleanup path.
- [ ] Exercise cancel, timeout, chapter-switch, and late-native-completion races.

**Exit gate:** No orphaned job, no incorrect bitmap release, no stale publication; simpler execution trace and documented performance cost.

### Phase 2 — Remove obvious contract coupling

- [ ] Replace string-based UI rejection decisions with typed outcomes.
- [ ] Move telemetry, normalization, and chapter-context contracts away from reverse implementation imports.
- [ ] Add narrow package dependency assertions to CI.
- [ ] Replace or contain blocking fingerprint cache APIs with the simplest correct solution for actual callers.
- [ ] Keep only essential synchronous semantics in cancellation and initialization paths.

**Exit gate:** Intended dependency direction is enforced and caller contracts are clearer.

### Phase 3 — Collapse batch callback plumbing

- [ ] Document why each `BatchChapterTranslator` and `BatchLaneWorkers` constructor argument exists.
- [ ] Separate fixed chapter-run configuration from mutable page-specific work identity.
- [ ] Replace repeated low-level callbacks with existing cohesive operations where possible.
- [ ] Share actual OCR/inpainting/translation/rendering behavior with reader work where identical.
- [ ] Keep batch-specific context chunks, ordered traversal, and recovery rules.
- [ ] Repeat change-impact exercises to prove reduced semantic edit propagation.

**Exit gate:** A stage implementation change is no longer routinely reflected through multiple unrelated constructors.

### Phase 4 — Redesign the reader scheduler *only if the product benefits*

- [ ] Write down the **minimum desired behavior**: current-page priority, optional next-page prefetch, deduplication, promotion, cancellation, and chapter replacement.
- [ ] Classify advanced coordinator features as required, optional, or unnecessary.
- [ ] Prototype a small shared reader queue behind the existing execution interface.
- [ ] Verify ownership, late callback handling, retry limits, memory pressure, and any required paid-call drain behavior.
- [ ] Compare complexity **and** latency with the current scheduler.
- [ ] Remove old coordinator files/tests only after new behavioral coverage exists and the replacement is accepted.

**Exit gate:** The simpler design matches the approved reduced feature set and preserves protected invariants. Otherwise, retain and simplify the existing coordinator instead.

### Phase 5 — Clean up storage, engines, file structure, and documentation

- [ ] Preserve `ChapterTranslationStore` as the sole live mutation/synchronization authority.
- [ ] Extract independent codecs and journal IO if this actually reduces owner complexity.
- [ ] Retain separate durable artifact status semantics and explicit mapping to live page status.
- [ ] Separate recognition/inpainting capabilities only where it improves real ownership.
- [ ] Merge truly redundant wrappers and policies; delete proven dead code.
- [ ] Consolidate overlapping tests **without losing coverage of distinct invariants**.
- [ ] Rename misleading classes after responsibilities stabilize.
- [ ] Refresh architecture docs, onboarding references, and CI checks.

**Exit gate:** Less unnecessary code, clearer ownership, no loss of correctness or operational recoverability.

---

## 6. Mandatory safety invariants

These are **requirements**, not instructions to retain the old abstractions:

1. **Committed output is protected.** A failed, incomplete, or stale candidate never silently replaces the last valid committed result.
2. **Stale workers cannot commit.** Chapter generation, page version, and lease/authorization checks reject outdated results.
3. **Cancellation is honest.** Cancelled request intent is distinguishable from actual native completion and any non-interruptible provider activity.
4. **Resources are safe.** Bitmaps and native sessions are not closed or recycled while still in use, and they do not remain permanently retained after abandonment.
5. **Restart is recoverable.** Process death during work or publication cannot leave an unrecognized corrupt completed state.
6. **Reader modes are predictable.** Manual and auto requests do not accidentally create competing owners of the same page.
7. **Batch can resume.** Required progress and committed artifacts survive application restart without silently duplicating successful work.
8. **Visible state tells the truth.** A ready translation is only shown when its matching display artifact is valid; otherwise use an intentional fallback.

An optimization may be removed. An invariant may be implemented differently. **Neither should be removed without an explicit product decision and an adequate replacement guarantee.**

---

## 7. Test matrix and minimum validation

| Scenario | Mandatory assertion |
|---|---|
| Cancel while OCR/inpainting runs | No unauthorized late commit; native and bitmap resources released safely |
| Cancel while provider request runs | Defined drain/cancel behavior, including paid-call handling if required |
| Switch chapters mid-operation | Old generation cannot update new chapter state |
| Manual request while auto work exists | Single correct owner; no unexpected duplicate work |
| Prefetch becomes visible page | Existing work reused or replaced according to documented policy |
| Low memory during image processing | Safe failure/retry; no bitmap misuse or perpetual admission lock |
| Source or model fingerprint changes | Incompatible output is invalidated and correctly recomputed |
| Previously translated page is refreshed | Last committed result remains readable until new result commits |
| Failure during artifact/manifest publication | Atomicity and valid recovery after restart |
| Restart partially completed batch | Only eligible incomplete work resumes |
| Textless page | Correct terminal result, no infinite reprocessing |
| Provider partial translation | Deterministic final display and retry semantics |
| Native completion after caller cancellation | No premature teardown, stale publication, or stranded resource |
| Invalid/corrupt artifact | Not presented as valid committed output |

### Verification levels

- **Unit tests:** Pure stage logic, typed outcomes, projections, scheduling decisions.
- **Controlled coroutine tests:** Parent/child ownership, timeout, cancellation, race ordering.
- **Integration tests:** Store/manifest transactions, lifecycle transitions, recovery.
- **Device tests:** Native teardown, bitmap lifetime, performance, real dispatcher behavior.

Do not claim virtual-time unit tests fully reproduce native inference, actual disk IO, or device concurrency.

---

## 8. Performance policy

Performance remains important, but optimization must earn its complexity.

**Establish actual baselines first.** Track at minimum:

- End-to-end latency per page (including p50/p95 if sample size permits).
- Time per stage: decode, detection/OCR, inpaint, provider, render, commit.
- Chapter batch throughput and restart recovery time.
- Peak memory and large-bitmap occupancy.
- Responsiveness for the visible/current page.
- Native device admission/idle time, if relevant.

A sequential path that is modestly slower but eliminates fragile coordination can be preferred. A major slowdown that visibly harms reading still requires investigation and an explicit decision.

**Decision template for every optimization:**

| Question | Required evidence |
|---|---|
| What user-visible bottleneck exists? | Measurement from representative workloads |
| What improvement does the optimization provide? | Before/after timings and memory data |
| What extra complexity does it introduce? | New states, jobs, locks, ownership paths, files, tests |
| Is a simpler solution adequate? | Comparison against sequential or lower-concurrency baseline |
| Can it be safely removed later? | Clear ownership and test contract |

Do not optimize because hardware *can* run tasks concurrently. Optimize when users demonstrably benefit.

---

## 9. Complexity reduction rules

### Prefer

- One clear owner per lifecycle.
- Sequential operations when overlap is optional.
- Small cohesive collaborators, not callback bags.
- Direct calls when there is no meaningful abstraction boundary.
- Immutable **stage inputs/results** where they prevent shared mutation.
- A clear distinction between **live state**, **durable artifacts**, and **UI projections**.
- Refactors that reduce the number of states, locks, tasks, and decisions a developer must understand.
- Removing obsolete compatibility mechanisms only after checking persisted-data compatibility.

### Avoid

- A hard target for file count or maximum LOC.
- Replacing many tiny helpers with an opaque mega-manager.
- Introducing new stage interfaces solely for aesthetic symmetry.
- Creating an additional global state machine without proving existing representations inadequate.
- Adding new block IDs when the existing persisted string IDs can be reused.
- Combining durable artifact status vocabulary with transient live-stage states.
- Dropping critical tests to reduce test-file count.
- Collapsing manual/auto/batch into one universal scheduler without specifying reduced product behavior.
- Blindly removing native quarantine because it looks like extra concurrency code.
- Performing architecture rewrites alongside OCR model conversion, quantization, or performance tuning.

### Review checklist for each deletion/merge

- What requirement was the code originally enforcing?
- Is that requirement still part of the product?
- Is the behavior handled elsewhere after this change?
- Which tests verify that?
- Does the change reduce **cognitive load**, or merely move code?
- Are new locks, indirection layers, state transitions, or object lifetimes being introduced?

---

## 10. Measurable success criteria

Use these instead of an arbitrary file-count target:

| Measure | Desired direction | Method |
|---|---|---|
| Files requiring semantic edits for a local stage change | Down | Three predefined change-impact exercises |
| Forwarded callbacks/constructor arguments | Down | Count at relevant orchestration boundaries |
| Independently owned coroutine tasks | Down | Trace job and resource ownership |
| Required locks and coordination states | Down where safe | Annotated concurrency/state inventory |
| Duplicate work and passes | Down | Profile and instrument stage calls |
| Unnecessary execution abstractions | Down | Ownership review and dependency map |
| Protected invariant regressions | **Zero accepted** | Focused test matrix + integration/device tests |
| User-visible performance | Acceptable and measured | Baseline vs rebuilt implementation |
| Recoverability after process interruption | Preserved | Simulated death/restart tests |
| Developer onboarding and navigation effort | Down | Can a new contributor locate and modify a stage without traversing multiple unrelated owners? |

A dramatic file-count reduction is a possible by-product, not a pass/fail metric.

---

## 11. Agent / implementor operating instructions

Use this section as a direct handoff to a coding orchestrator (including multi-agent workflows).

### Mission

Simplify the translation subsystem while preserving the mandatory invariants above. Favor removal of unnecessary coordination, optional concurrency, duplicated logic, and abstraction layers over preservation of the existing design for its own sake.

### For each work item

1. **Inspect current code at the actual working commit.** The audit baseline may be stale by implementation time.
2. **Write a short evidence note:** existing behavior, exact owners, call graph, tests, runtime measurements if relevant.
3. **Ask whether the entire mechanism is still necessary** before optimizing or extracting it.
4. **Compare at least two approaches:** simplest correct design vs retaining/repairing the current optimized design.
5. **Choose based on correctness and cognitive complexity**, explicitly documenting any expected performance tradeoff.
6. **Implement one bounded change** without unrelated renames or broad code cleanup.
7. **Run focused tests and applicable regression suites.** For native-sensitive work, include device validation.
8. **Update documentation and benchmarks** to reflect what was actually changed.
9. **Report evidence:** removed states/parameters/files, improved change isolation, test results, and latency/memory differences.
10. **Stop before deleting behavior whose requirements or ownership cannot be established.** Escalate the specific uncertainty rather than guessing.

### Required deliverable per change

```text
Change: <specific responsibility>
Before: <verified current behavior and failure/complexity>
After: <simpler behavior and owners>
Preserved invariants: <tests / evidence>
Removed complexity: <jobs, callbacks, states, files, locks>
Behavior intentionally removed: <explicitly listed, or none>
Performance delta: <measured, or not measured>
Risks / remaining questions: <specific>
```

**Agent warning:** A successful build does not prove safety in coroutine cancellation, native memory, chapter publication, or process-death recovery. Do not treat compile success as refactor completion.

---

## 12. Final architectural verdict

The current architecture contains valuable correctness safeguards but too much orchestration complexity for the project's maintainability goals. The most effective remedy is a **simplicity-first structural reduction**, not another layer of elaborate interfaces or a purely cosmetic file reorganization.

**Simplify execution, clarify ownership, and remove optional concurrency first.** Preserve the user-visible guarantees of safe publication, stale-write rejection, resource safety, and recoverable batch work. Then selectively reintroduce performance optimizations only when measurements show that they are worth their cognitive cost.

The desired end state is **not the fastest theoretically possible translation pipeline**. It is a reliable, understandable application where future OCR, inpainting, translation, and rendering changes do not cause repeated chained refactors.

---

## 13. Repository evidence links (pinned to audited commit)

- [Architecture documentation](https://github.com/KimreneOuk/TachiyomiATVibe/blob/4de121bf7ed486e9f39b5a3f4f5c4288f439c8c8/docs/translation-architecture.md)
- [TranslationPipeline.kt](https://github.com/KimreneOuk/TachiyomiATVibe/blob/4de121bf7ed486e9f39b5a3f4f5c4288f439c8c8/app/src/main/java/eu/kanade/translation/pipeline/TranslationPipeline.kt)
- [SinglePageOnnxPhase.kt](https://github.com/KimreneOuk/TachiyomiATVibe/blob/4de121bf7ed486e9f39b5a3f4f5c4288f439c8c8/app/src/main/java/eu/kanade/translation/pipeline/SinglePageOnnxPhase.kt)
- [SinglePageHttpRenderPhase.kt](https://github.com/KimreneOuk/TachiyomiATVibe/blob/4de121bf7ed486e9f39b5a3f4f5c4288f439c8c8/app/src/main/java/eu/kanade/translation/pipeline/SinglePageHttpRenderPhase.kt)
- [NativeRunQuarantine.kt](https://github.com/KimreneOuk/TachiyomiATVibe/blob/4de121bf7ed486e9f39b5a3f4f5c4288f439c8c8/app/src/main/java/eu/kanade/translation/pipeline/execution/NativeRunQuarantine.kt)
- [BatchChapterTranslator.kt](https://github.com/KimreneOuk/TachiyomiATVibe/blob/4de121bf7ed486e9f39b5a3f4f5c4288f439c8c8/app/src/main/java/eu/kanade/translation/pipeline/batch/BatchChapterTranslator.kt)
- [BatchLaneWorkers.kt](https://github.com/KimreneOuk/TachiyomiATVibe/blob/4de121bf7ed486e9f39b5a3f4f5c4288f439c8c8/app/src/main/java/eu/kanade/translation/pipeline/batch/BatchLaneWorkers.kt)
- [RollingAutoCoordinator.kt](https://github.com/KimreneOuk/TachiyomiATVibe/blob/4de121bf7ed486e9f39b5a3f4f5c4288f439c8c8/app/src/main/java/eu/kanade/translation/scheduling/RollingAutoCoordinator.kt)
- [ChapterTranslationStore.kt](https://github.com/KimreneOuk/TachiyomiATVibe/blob/4de121bf7ed486e9f39b5a3f4f5c4288f439c8c8/app/src/main/java/eu/kanade/translation/persistence/chapter/ChapterTranslationStore.kt)
- [ArtifactContracts.kt](https://github.com/KimreneOuk/TachiyomiATVibe/blob/4de121bf7ed486e9f39b5a3f4f5c4288f439c8c8/app/src/main/java/eu/kanade/translation/persistence/artifact/ArtifactContracts.kt)
- [PageTranslation.kt](https://github.com/KimreneOuk/TachiyomiATVibe/blob/4de121bf7ed486e9f39b5a3f4f5c4288f439c8c8/app/src/main/java/eu/kanade/translation/model/PageTranslation.kt)
- [PageTranslationState.kt](https://github.com/KimreneOuk/TachiyomiATVibe/blob/4de121bf7ed486e9f39b5a3f4f5c4288f439c8c8/app/src/main/java/eu/kanade/translation/model/PageTranslationState.kt)
- [StableBlockIds.kt](https://github.com/KimreneOuk/TachiyomiATVibe/blob/4de121bf7ed486e9f39b5a3f4f5c4288f439c8c8/app/src/main/java/eu/kanade/translation/engines/translator/contextual/StableBlockIds.kt)
- [RenderColorEstimator.kt](https://github.com/KimreneOuk/TachiyomiATVibe/blob/4de121bf7ed486e9f39b5a3f4f5c4288f439c8c8/app/src/main/java/eu/kanade/translation/engines/rendering/RenderColorEstimator.kt)
- [TextTranslator.kt](https://github.com/KimreneOuk/TachiyomiATVibe/blob/4de121bf7ed486e9f39b5a3f4f5c4288f439c8c8/app/src/main/java/eu/kanade/translation/engines/translator/TextTranslator.kt)
- [TranslationUiTruth.kt](https://github.com/KimreneOuk/TachiyomiATVibe/blob/4de121bf7ed486e9f39b5a3f4f5c4288f439c8c8/app/src/main/java/eu/kanade/translation/presentation/TranslationUiTruth.kt)
- [ChapterContextService.kt](https://github.com/KimreneOuk/TachiyomiATVibe/blob/4de121bf7ed486e9f39b5a3f4f5c4288f439c8c8/app/src/main/java/eu/kanade/translation/context/ChapterContextService.kt)

### General engineering references

- [Android Guide to App Architecture](https://developer.android.com/topic/architecture)
- [Android Architecture Recommendations](https://developer.android.com/topic/architecture/recommendations)
- [Android Modularization](https://developer.android.com/topic/modularization)
- [Coroutines Best Practices on Android](https://developer.android.com/kotlin/coroutines/coroutines-best-practices)
- [Kotlin Coroutines: Structured Concurrency](https://kotlinlang.org/docs/coroutines-basics.html)

**End of document.**
