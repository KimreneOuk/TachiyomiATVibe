---
kind: review
title: "Simplicity-first continuation: source findings and Step 1 plan reassessment"
comments: none
---

# Scope and authority

The [final rebuild phase structure](phases) records the ordered Phase 0–8 roadmap. It settles sequencing while retaining explicit measurement/ownership decisions inside the affected phases.

Investigation date: 14 October 2026. Source baseline rechecked: `4de121bf7ed486e9f39b5a3f4f5c4288f439c8c8`, clean working tree. This continuation changes planning/review artifacts only. No production, test, build, model or repository documentation changes; no builds, tests, device measurements or race reproduction.

The user supplied a new architectural direction: correctness, simplicity, maintainability and testability before maximum throughput. Preserve necessary observable guarantees rather than every current optimization or class. Sequential processing within a page is the preferred starting point; bounded independent-page concurrency remains an option when justified.

The supplied report stops in Phase 0 at “Trace three cha”. Its omitted remainder has not been inferred. This review covers the complete supplied sections 1–4 and available Phase 0 items.

This is a source-grounded reassessment, **not a newly approved implementation plan**. Alternatives below are recommendations with explicit validation requirements. The previous [Step 1 plan](../translation-step1-plan) remains useful source/caller/retirement evidence, but its mechanism-heavy C05–C08 prescriptions and independent approval apply to the older requirements. They must not be executed unchanged under the new direction.

## Main conclusion

The former plan largely preserved optimization boundaries and then added machinery to make their lifetimes safe. The new direction permits eliminating some of those boundaries first. The most valuable investigation is therefore not another file-count purge: it is whether sequential page ownership removes the need for detached tasks, transferred bitmap results, block-result sidecars, deferred caches and multiple priority observers.

One new concrete candidate emerged: adaptive scheduling currently computes a concurrency value that production never applies. Retiring that experiment can remove coordination overhead while retaining the actual fixed-capacity safety gate.

# Reconciliation with the supplied report

Finding numbers below follow the **new** report. They differ from the original ten-finding audit; for example, the shared-block issue was previously F-07 and is now F-03.

| New finding | Source check / existing evidence | Investigation disposition |
| --- | --- | --- |
| F-01 forwarding | `pipeline/batch/BatchChapterTranslator.kt:80–138` exposes stage callbacks across engine construction, fingerprints, decode, OCR, inpaint, retry and publication. The earlier constructor and impact audits remain applicable. | Keep the measured change-isolation objective. Investigate a small concrete page-preparation collaborator after settling sequential ownership; do not merely bundle all callbacks into one service. Batch callback restructuring remains a later slice. |
| F-02 native ownership | `pipeline/execution/NativeRunQuarantine.kt:72–85` strips caller Job and completes its exit signal in the invocation finally; `SinglePageOnnxPhase.kt:1182–1232` starts independent inpaint, recycles decode in that child and returns its Deferred. | Prefer deleting the child task. Inline synchronous inpaint in the admitted invocation, then solve remaining bitmap handoff/publication ownership separately. This removes the child-drain problem, not the possibility of losing an owning return value. |
| F-03 shared blocks / duplicate styles | `SinglePageOnnxPhase.kt:1181/:1203` shallow-copies blocks and recomputes styles; `SinglePageHttpRenderPhase.kt:517/:555/:662/:756/:875` sorts, retries, awaits inpaint and recomputes styles. `RenderColorEstimator.kt:295` reads text/translation and mutates styles. | Recommend one operation-owned mutable draft and final style pass. Concurrent style/result merging is unnecessary if those stages no longer overlap. Preserve retry, watermark filtering and user-edit/store conflict rules. |
| F-04 dependencies / outcomes | ROI imports normalization from pipeline; Paddle imports telemetry; `context/ChapterContextService.kt:10/:143` uses `BatchContextFrontier`; outcome string mapping remains as audited. | Existing telemetry/normalization moves and typed outcome work remain sound. Add a separate frontier relocation slice, preserving its contextual semantics; do not label it a trivial counter. |
| F-05 blocking cache | `BatchChapterTranslator.kt:1203–1228` implements Map lookups by `computeIfAbsent` plus `runBlocking(IO)`. Multiple worker/profile callers exist. Manager replacement and store bridges remain as previously traced. | Prefer a serial suspending read-through cache first. A suspending mutex may serialize cache I/O deliberately; this is distinct from blocking a JVM map monitor or holding a store lock. Deferred single-flight is an optional optimization, not a baseline requirement. |
| F-06 auto complexity | `RollingAutoCoordinator.kt:84/:95/:423–448/:536` handles memory admission, provider drain, local-compute exclusion and preparation channels. | Unified queue remains the leading candidate. Retire channels and arbitrary ahead windows intentionally. Retain or explicitly replace session fences, bounded demand, nonpreemptive active work, memory limits and paid-call policy. See the [behavior ledger](behavior-ledger). |
| F-07 sprawl | Source still has active central orchestrators and separate state/persistence authorities. New adaptive finding below is a more concrete opportunity than file-size thresholds. | Remove inert coordination before splitting large owners. Keep store authority in one place; persistence extraction remains later work. |
| F-08 state contracts | `persistence/artifact/ArtifactContracts.kt:11–44` contains durable retryable/terminal/stale/corrupt statuses; `model/PageTranslationState.kt:3–19` contains live execution/lifecycle concepts. | Preserve these differences and PageView/display readiness. AutoSlotState retirement requires replacement behavior; it is not evidence that the durable/live models are duplicates. |
| F-09 engine roles | PageInpaintingEngine is already synchronous; ROI still owns/shared-guards its inpainting collaborator. | Do not split native guards or promote a new engine owner in the cancellation fix. Recognition/inpaint separation is a later ownership slice coordinated with OCR/LiteRT. |
| F-10 tests/docs | Existing architecture documentation and boundary tests are real. Prior flaky-test assertions were corrected in the saved review. | Retain boundary tests; retire only retired policies/helpers or covered implementation-specific handoffs. No new flakiness claim or deletion based on suffix/clock/mocks. |

# New finding S-01: adaptive output is pinned

**Verdict:** confirmed source behavior; high confidence. **Classification:** active instrumentation for an unapplied experiment, not zero-caller dead code.

- `pipeline/adaptive/AdaptiveKnobController.kt:80` returns `floor` from `appliedConcurrency`; `DEFAULT_FLOOR` is 1. Candidate concurrency and anchor state are maintained separately.
- `pipeline/TranslationPipeline.kt:277–282` creates the controller and signal adapter, but gives `DevicePagePermitGate` only `appliedConcurrency`.
- `pipeline/adaptive/AdaptiveKnobSignalAdapter.kt:30–96` registers a stage observer and tracks spans, page counts, memory/thermal/durability snapshots and observations. Its output feeds the controller; source references reveal no separate production consumer of the computed candidate value.
- `TranslationPipeline.kt:138` closes the adapter registration. `DevicePagePermitGate.kt:28` imports the controller's floor constant as its default.

**Recommendation:** deliberately retire adaptive computation and its adapter; make the existing device gate's conservative capacity explicit. Preserve the gate, current capacity, actual memory preflight, trace measurements and cancellation accounting. Retiring this does not mean removing TranslationMemoryBudget, OOM handling or native admission.

| Candidate file | Proposed action | Required caller/test work |
| --- | --- | --- |
| `app/src/main/java/eu/kanade/translation/pipeline/adaptive/AdaptiveKnobController.kt` | Remove once fixed gate capacity replaces its production/default references. | Pipeline wiring and gate default; remove only experiment-specific test expectations. |
| `app/src/main/java/eu/kanade/translation/pipeline/adaptive/AdaptiveKnobSignalAdapter.kt` | Remove registration, construction and close wiring. | Audit future dependency/reflection/resources before deletion; remove harness commentary referencing its context setup. |
| `app/src/test/java/eu/kanade/translation/pipeline/adaptive/AdaptiveKnobControllerTest.kt` | Split preservation assertions from retired experiment tests, then retire file if nothing remains. | First test checks real pipeline floor admission; second checks trace normalization/duration. Move those assertions into existing admission/concurrency and telemetry/trace suites before deleting. Neither assertion is an obsolete optimization contract. |

The default pipeline already applies capacity 1, so this recommendation does not require accepting a newly reduced gate capacity. It does retire candidate adaptive behavior and its observability experiment. No runtime overhead or speedup has been measured.

If accepted and verified, this adds **2 production and 1 test deletion** to the prior retirement candidates. It is not folded into a new final count because native-owner additions and reader policy are being reassessed too.

# New source corrections S-02 / S-03: lock names and hash cancellation

`ChapterProfileBatchCoordinator.buildEnvelopeDispatchWorkLocked` does not run inside an encompassing `withArtifactEngineLocked` in the inspected caller. At lines 668/674, the caller acquires that lock only to begin/end manifest coalescing; line 672 calls the planner between those blocks. Its lookup at line 710 should not force whole-corpus precomputation merely because the helper name says Locked. Audit real lock scopes and keep per-page evidence/revalidation. This corrects an overly broad inference in prior planning advice; it does not establish that every nested store call is lock-free.

`PageDecode.computeSourceFingerprint:121–138` uses/ closes its stream and rethrows CancellationException, but has no cooperative cancellation check within the read loop. A suspend cache alone does not make a blocking read immediately cancellable. The revised cache investigation must cover checkpoints between reads, actual read completion and existing null/UNKNOWN error policy.

# Where the former plan can shrink

| Former Step 1 prescription | Simpler candidate | What still needs proof |
| --- | --- | --- |
| Registered lazy inpaint child, Deferred slot, child join, several allocation states | No inpaint child; one admitted synchronous OCR/inpaint sequence with one owner/finally | Input/result aliases, null outcome, cancellation after native result, lazy writer borrow and abandoned delivery. Native quarantine stays. |
| Separate provider draft, ReaderBlockKey, envelope sidecars and typed style joins | One private page draft mutated sequentially; use existing provider request IDs/StableBlockIds where already required | Provider request/retry ordering and page draft isolation from store snapshots. No async style task means no style-to-provider join. |
| Pure keyed style output API | Existing synchronous final `recomputeFor` on the owned draft | Audit all reader/resume/retry consumers; rerun only if the background changes. Pure extraction can follow if it independently improves tests. |
| Batch-parented Deferred fingerprint producers, completion cleanup and waiter lifecycle | One run-scoped suspend cache with serialized miss computation | No cache/store lock reverse edge; cancellation must release its mutex and not cache partial hashes; source/config invalidation and null sentinel policy. |
| New live priority observers in native and provider gates | Priority choice in the reader queue; keep selected work nonpreemptive | Accept/measure bounded admission waits and foreground behavior under batch/provider contention. Strict reordering of already-waiting gate entries is a separate product requirement. |
| Deferred storage/render-tail queue to release native as early as possible | Explicit straight-line stage boundary; optionally finish required publication before releasing native | This trades lane occupancy for fewer owning transfers; measure disk stalls, input lifetime and overlap. Do not remove drain barriers before their closures are gone. |
| Preserve cross-page overlap in every configuration | Compare serial-reader baseline with bounded two-page execution | Reader latency, memory, paid-call overlap, LOCAL_COMPUTE exclusion and device safety. Sequential within-page processing is the common requirement. |

See [minimal mechanism options](mechanisms) for the remaining correctness boundaries and why “just inline it” is insufficient at the quarantine return.

# Investigation and implementation ordering

1. Preserve the existing source/retirement evidence and mark prior mechanism approval as version-specific. This artifact does that.
2. Record the behavior inventory and required/optional distinctions before deleting the auto coordinator. See the linked ledger.
3. Establish the runtime baseline before any behavior change. Existing XMLs cannot stand in for the missing tests/devices.
4. Independent small slices remain: verified dead helper/render island purge, typed outcomes/package moves, ordered suspending chapter replacement, pinned adaptive experiment retirement.
5. Prototype synchronous inpaint and final-only color estimation against ownership/cancellation tests. Choose a bitmap boundary based on those results, not by carrying the old holder design forward automatically.
6. Compare simple serial and bounded reader policies on representative devices; retain overlap only where it materially helps. Record accepted latency/memory tradeoffs explicitly.
7. Remove old auto coordination only after the behavior ledger is covered. Revise C05–C11, new-file counts and test absorption to the chosen mechanism.
8. Continue with shared stage capabilities and batch callback change-isolation exercises, then persistence internals; keep backend model work out of these slices.

These are investigation/slice recommendations, not executable tickets. The old C00–C11 sequence cannot be treated as the final implementation sequence after this report.

## Change-isolation exercises to carry forward

| Exercise | Responsible semantic owner | Target |
| --- | --- | --- |
| Add OCR metadata without changing the scheduling contract | Recognition output/its consumer | No unrelated reader/batch scheduling edits. Extending existing PageTranslation already avoids callback signature propagation; measure real changes rather than assuming five files. |
| Change inpaint retry inputs | Inpaint/preparation capability | Intermediate Pipeline → BatchTranslator → LaneWorkers wiring should not need signature-only edits. Avoid a separate wrapper at every hop. |
| Change cleaned-image acceptance/retirement rules | Guarded publication/store boundary | One authoritative semantic decision; UI and schedulers consume typed outcomes. Existing PatchPrecondition fields can evolve without expanding every callback. |

For each exercise, separate semantic changes, forwarding-only changes and meaningful tests. Record before/after changed paths and dependency edges. No fixed score improvement or file-count target is promised.

# Validation still outstanding

- Build and unit/integration baseline at the exact revision, with failures recorded rather than retried away.
- Physical native completion despite request cancellation, caller timeout, chapter replacement and engine disposal.
- Bitmap distinct/alias/no-erase/failure/textless paths plus queued writer completion before recycling.
- Accepted or uncertain publication followed by cancellation; no deletion of referenced output; committed display preserved.
- Reader same-job promotion, deduplication, memory suppression, stream-ready wakeup and paid-provider policy.
- Cache serialized miss, failure retry, cancellation, unknown-source evidence, legacy resume and profile caller lock ordering.
- Representative per-page latency, next-page readiness, native occupancy, batch throughput, peak memory and responsiveness, with device noise bands and explicit acceptance of costs.
- OCR/LiteRT owner review of admission/drain/interface boundaries. No detector, recognizer, tensor, model or backend fallback changes in this investigation.

# File accounting status

The previously stated **18 removals / 7 additions / net 11 fewer files** is the old Step 1 forecast. It remains a useful retirement inventory, not a newly validated count for the simplicity-first design. No files have been removed or added to the repository. Adaptive candidates above are additional; removing the proposed PageNativeOperation addition may change counts again, but is not yet a settled decision.

Production source remains 290 and translation unit-test source remains 318 at this baseline. The completed independent critique proved consistency of the former plan; it does not prove that its complexity is minimal or that these new recommendations pass runtime gates.
