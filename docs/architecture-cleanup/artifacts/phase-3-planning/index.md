---
title: "Phase 3 architecture planning - sequential page ownership"
kind: ticket
status: 2
assignee: ""
comments: none
---

# Authorization and starting point

User instruction on15 October2026: begin Phase3 architecture planning with GLM5.3Flash/max and Gemini through Antigravity reviewing. GLM uses OpenCode; Gemini3.8Flash/high uses Antigravity. This authorizes source investigation, options discussion, plan critique and eventual architecture specification. It does not start native/page ownership implementation.

Branch `architecture-cleanup`, published HEAD `fbbd19d19a49ea1a0b43995f8c0814fc21dfa1ec`. [Prerequisite closure](../store-baseline-investigation/repair/closure) records both full app variants at332 suites/2254 tests, zero failures/errors, one benchmark skip. Ten metadata-only tracked M entries have empty content diffs; the protected user report remains untracked and untouched. No baseline rerun is needed for read-only planning.

# Planning work (historical sequence)

1. Refresh the [entry packet](../phase-3-entry) against actual source paths and trace request/resource ownership from decode through recognition, inpaint, provider translation, final style/render, publication and teardown. Include reader and batch call sites.
2. Compare the smallest safe durable/non-owning handoff with a minimal exclusive in-memory operation owner. Neither is selected. State how caller abandonment, late native exit, bitmap alias/null/textless cases, persistence writer borrows and uncertain publication receipts are handled; identify hidden ownership states and compatibility costs.
3. Gemini independently critiques entry assumptions and then the GLM decision brief. Source citations and controlled-test feasibility must support recommendations; no invented runtime or performance evidence.
4. Discuss consequential choices and readiness with the user before drafting a settled implementation plan. Keep undecided mechanisms explicitly open. Then record resolved contracts and a small ordered implementation/verification sequence, followed by independent critique.

The GLM [ownership options brief](ownership-options) and Gemini [entry critique](entry-review) are assigned as decision preparation. They are not an approved implementation design.

# Constraints and exit

Keep existing store authority, source/user-edit/version/generation/lease fences, engine/native exclusion and quarantine until a concrete safe replacement is proven. Prefer sequential stages within one page; preserve legitimate overlap across pages and HTTP/native classes. Keep OCR/LiteRT algorithms/models and Phase4 reader queue redesign outside this planning scope. Use existing block identities and private-copy facilities; avoid generic workflow/allocation registries without a demonstrated need.

No source/test/config edits, Gradle runs, devices/providers, toolchain operations, commits or pushes during investigation. Phone/JNI cancellation, native occupancy/performance, JDK17 CI parity and existing LiteRT formatting debt remain explicit unverified obligations. Host coroutine tests do not establish physical JNI termination.

Planning exits when the governing boundary and cancellation/publication contracts are discussed and settled, the source-grounded plan and meaningful proof matrix are independently reviewed, and open physical validation gates are attached to the changes they constrain. Code implementation needs its own bounded execution scope.

# Options brief review in progress

GLM delivered the [source-grounded options brief](ownership-options). Its mechanism proposal is unaccepted. Parent identified that a producer-local flag set before return does not prove quarantine acceptance or downstream caller delivery; a timeout/cancellation may still discard a completed value. The design must close that ownership gap and distinguish a failed/cancelled flush attempt from actual writer exit. [Independent options critique](ownership-options-review) is assigned to Gemini before user readiness discussion.

Other review targets: memory-first bitmap transfer is not a durable/non-owning handoff; a small resource owner need not replicate store authority or require a registry. Native retry admission, uncertain commit retention and style eligibility must be resolved against reachable code paths. Safety requirements are not choices to waive. User-visible serial/overlap and memory/disk tradeoffs remain open, with no measured performance conclusion. No implementation or test runs are authorized in this stage.

# Independent critique disposition

Gemini's [options critique](ownership-options-review) requires revisions. GLM is revising the decision brief; no mechanism is accepted. Required corrections cover native-result delivery/abandonment, writer completion, uncertain publication deletion, all retry admission paths, fair owner-vs-durable alternatives, style eligibility and teardown/memory coexistence. Parent independently read the writer coordinator: its shared flush mutex serializes task execution, so a successful protected flush must be distinguished from a failed or cancelled attempt; a new joining mechanism is not preselected.

The serial per-page goal remains the user's simplicity-first direction. Safety obligations are mandatory. Any remaining material memory/disk/performance tradeoff will be discussed after a sound revised brief and independent recheck, before final plan drafting. No source changes or runtime runs have occurred.

# V3 review status

The revised brief now locates one enclosing owner in the real pipeline call, which includes HTTP/render. It remains unaccepted pending re-review. Parent source checks contradict three proposed proof premises: `updatePageFromCurrentSnapshot` takes a fresh write-time snapshot rather than original-attempt evidence; ONNX resume paths enqueue deferred actions inside native execution (only their drain is outside); and the lazy task receipt is created inside the enqueue callee, not already held by the outer owner before queue mutation. These require explicit cancellation/fencing mechanisms, not test-only hypotheses. Shared retry callers, all existing bitmap recyclers, deletion authorization interleavings and pending task liveness are also part of the active critique. No architecture decision or implementation acceptance follows from the brief's claimed closed dispositions.

# Settled rendering handoff and drafting authorization

The user answered the Phase3 readiness question on15 October2026: **"Keep memory-first; draft the plan on that basis."** This selects retaining the operation-owned cleaned image through translation/rendering, rather than making durable-image reload mandatory. It preserves the current lazy reader handoff shape; it does not establish latency/memory measurements or relax ownership, cancellation, stale-write and publication safety.

The sole product choice is closed. Final plan drafting is authorized on that basis once the mandatory protocol has a sound independent review. No further confirmation of this choice is needed. Implementation is not authorized by this planning decision. GLM and Gemini are consolidating the cancellation-safe writer receipt, abandonment/write linearization, resume-tail authorization and retry-output ownership details; v4 is under review. All final artifacts must be self-contained and distinguish the measured baseline from proposed mechanisms and deferred physical validation.

# Plan drafting underway

Gemini's latest options recheck clears memory-first for bounded plan drafting, with two required clarifications: serialize attempt patch execution and local receipt advancement with timeout closure; and separate always-required deferred resource work from semantic updates that must still have attempt authority. The parent has dispatched the [architecture plan](plan) draft incorporating both, under the user's existing drafting authorization. Final plan acceptance remains pending independent critique; code implementation is not started.

The draft must explicitly cover timeout callback failures without losing real native exit, cancellation-safe writer receipt registration, all recycler ownership, reader-only retry admission, original user/source/version fencing, and conservative uncertain-file retention debt. Device/native behavior gates stay in Phase3 where behavior changes; a reviewed plan is not evidence that those gates have passed.

# Draft delivered and fresh final critique

GLM delivered the [implementation-plan draft](plan). A fresh Gemini3.8Flash/high agent through Antigravity is independently reviewing it against source and will write [final plan review](plan-review). The previous reviewer helped shape the candidate protocol; the fresh review preserves independent pressure testing. The plan and options brief are frozen during critique. No implementation or runtime runs are authorized by this handoff.

Parent preliminary review targets include callback exceptions escaping native real-exit cleanup, compile-safe ordering when removing `inpaintJob` and converting its consumers, consistent publication receipt claims, and accurate spec frontmatter/status. These are pending critique findings, not already-accepted repairs.

# Fresh final critique - bounded revisions

The [fresh review](plan-review) requests bounded changes to the draft. GLM is revising the full timeout callback chain, explicit cancellation-safe receipt enrollment, compile/resource-safe slice dependencies, and documentation of changed receipt APIs and uncollected uncertain-file debt. Parent rejected blindly completing a receipt in a caller catch: after queue enrollment only the actual worker/never-started queue termination may release the borrow. A narrow enrollment transaction is preferred. Coalescing producer/consumer wiring into one atomic slice avoids adding a compatibility field solely to satisfy ticket boundaries. The memory-first decision remains settled; no implementation authorization or runtime validation is implied.

# Planning closure ? 15 October 2026

**Architecture planning is complete. Phase 3 implementation is not started.** GLM5.3Flash/max produced the [memory-first implementation plan](plan); a fresh independent Gemini3.8Flash reviewer through Antigravity [approved its revised contract](plan-review). The earlier review/revision sections above are historical, superseded by this disposition. Two Antigravity startup attempts timed out before the successful final review; no review acceptance was inferred from those failed starts.

The settled direction is inline native preparation with one enclosing page owner, original-attempt write authority, actual writer-exit receipts, reader-only retry admission, private drafts, final styling, and conservative retention on uncertain file publication. Six ordered slices put receipt and publisher safety before one atomic producer/consumer/boundary cutover, then consolidate validation. There is no new registry or competing store authority.

The implementation must verify host interleavings and both variants, preserve the baseline source/user-edit/publication contracts, and retain Phase 3 device gates D1?D4. Real I/O can stall an actual-exit join indefinitely; uncertain retained files are explicitly unbounded uncollected disk debt. These are visible design limits, not measured or waived outcomes. JDK17 parity and standing LiteRT formatting debt remain open.

Only planning artifacts changed; no source/test/config changes, Gradle runs, device/provider operations, commits or pushes occurred during planning. The published code baseline remains fbbd19d1. The standing commit/push instruction applies when reviewed code phases are completed; artifact-only planning lives outside the Git repository. Next direction is a separately authorized bounded implementation of the approved plan.

# Reopened for same-page overlap revision

The user has selected concurrent inpainting and external translation after OCR; [execution decision](../phase-3-implementation#user-concurrency-revision--15-october-2026) records the settled requirement. Prior plan approval does not approve a mechanism for this new ordering. GLM must revise the technical design and proof matrix, Gemini must critique the actual revision, and tickets must then align before implementation resumes. No new product-choice approval is required for preserving this overlap.

# Overlap planning closure

The independent final recheck approved the structured inpaint/external-translation overlap revision. The [plan](plan) now supersedes serial same-page ordering; memory-first, native exclusion, paid-provider policy, original-attempt fencing and actual-exit ownership remain mandatory. Planning is complete again, and [implementation](../phase-3-implementation) resumes at S1 under existing user authorization. No code or test execution occurred during revision.
