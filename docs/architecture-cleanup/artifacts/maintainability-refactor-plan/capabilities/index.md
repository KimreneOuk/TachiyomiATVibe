---
title: "Maintainability plan: shared capabilities and enforceable contracts"
kind: spec
comments: none
---

<user_quoted_section>Requirements revision — 14 October 2026: The user has since prioritized simplicity over preserving optimization machinery. This document records the previous plan/review; its source evidence remains useful, but mechanism choices, execution readiness and file-count forecasts are version-specific. Read the simplicity-first investigation before using this artifact for implementation. Native ownership, block merging, fingerprint caching and admission priority are being reassessed. No repository changes have been made.</user_quoted_section>

<user_quoted_section>Reader-policy update, October 14: The user's subsequent Step 1 reader rebuild supersedes this document wherever it requires separate manual/auto scheduling or preservation of same-page HTTP/inpaint overlap. Reader tap/viewport now use one queue and common runner; local preparation finishes before HTTP. Batch independence, store authority, quarantine and other correctness constraints remain. This is a planning change; implementation is not authorized or delivered.</user_quoted_section>

# Contract design rules

Return to the [master plan](..). A shared capability earns its place by owning bounded behavior used by more than one execution mode, eliminating signature forwarding, or isolating concurrent mutation. An interface is not required for every helper. Stateless configuration and page-specific identity may be grouped into typed values; live registries, callbacks, engines, mutexes and arbitrary utilities must not become one generic context object.

Schedulers continue owning ordering, priority, preemption, prefetch windows, batch chunks, context availability, resume and paid-call policies. Capability implementations own processing and focused retry mechanics. `ChapterTranslationStore` owns final write authority. Neither stage implementations nor composition builders acquire new authority from a snapshot.

# Reuse inventory and proposed seams

| Behavior | Existing implementation/contract | Recommended change |
| --- | --- | --- |
| Stage reuse planning | `pipeline/planning/PageWorkPlanner` | Reuse unchanged; no new generic execution framework |
| Recognition | `engines/vision/ocr/PageRecognitionEngine`, ROI/Paddle helpers | Expose recognition-only engine capability and detached stage result; preserve ROI geometry, masks, panel/text metadata and backend selection |
| Inpainting | `engines/inpainting/PageInpaintingEngine`, planner, neural/LiteRT/classical implementations | Make a separately owned capability; capture mask/config/input contracts; delegate to existing algorithms |
| Native lifecycle | `EngineLane`, `NativeRunQuarantine`, backend/native guards | Extend registration/handoff contract described in [ownership](../ownership); retain protection during extraction |
| Translation | `TextTranslator`, contextual planner/parser, providers and retry/governor | Wrap mutating providers on private drafts first; return typed stable-key outcomes; migrate provider contracts incrementally |
| Cleaned publication/load/retirement | `pipeline/CleanedPublication`; existing `persistence/chapter/CleanedImagePublisher` | Inject publication behavior intact into batch workers. Reuse the existing file-ordering publisher; do not create a second `CleanedImagePublisher` |
| Guarded updates | `PageStoreWriter`, store stage patches and preconditions | Reuse authoritative update pathways; narrow orchestration inputs without copying fencing logic |
| Layout/rendering | Existing rendering engines, planner, coordinator and sidecars | Leave algorithms intact; consume stable stage results after join |
| Store internals | `ChapterAttemptLedger`, lease table, drain/journal collaborators, registry | Extend or delegate existing collaborators only when a residual independent responsibility is proved |

## Recognition

Inputs include an admitted image read handle, source/decode facts and immutable recognition configuration. The engine borrows the input; the registered native operation retains bitmap disposal responsibility. The engine knows no reader window, batch registry or store instance.

Output carries recognized block source text, coordinates, panel/bubble associations, masks/detections needed by inpainting, source/config evidence and decode dimensions. Do not drop watermark/detector-only regions because they are not OCR text blocks. Performance diagnostics remain separate from eligibility evidence.

Reuse `PublishedPageTranslation`/block views or detached copies where they already express the required data. Introduce a focused stage value only if those types force consumers to depend on unrelated mutable state or transient bitmap fields. A new result type is ephemeral until an explicit durable-format migration is approved.

## Inpainting

Inputs are the operation-owned image, isolated recognition/mask/geometry values, selected mode/model and a focused retry policy. The operation coordinator owns native admission; internal engine execution does not reacquire it. OOM handling, downscale sampling and fallback semantics remain those of the existing implementation until separately measured.

The result distinguishes successful cleaned output, legitimate no-erase/no-bitmap outcome, deferred low-memory outcome and failure. It includes degradation/fallback facts and current revision/stamp/fingerprint metadata, rather than mutating provider-owned blocks or relying on backend last-run globals as the only receipt. Final color-style calculation belongs at the join/render boundary described below.

Style results associate by stable block identity and OCR evidence. All page-level effects of `PageInpaintingEngine.markReady`, error handling and revision stamping must be mapped explicitly; isolated copies alone can otherwise discard status changes or failure charges. Ownership of an output bitmap follows [ownership](../ownership).

Recommend removing the fresh detached-inpaint `RenderColorEstimator.recomputeFor` call and computing final cleaned-background styles once for each actual joined bitmap/block-set/render attempt, after final translation results are available. `SinglePageHttpRenderPhase` already performs render-time recomputation on normal and retried cleaned outputs. First inventory all manual/resume/Auto/batch consumers; move any required preparation-time style effects to their equivalent joined boundary before deleting a call. A retried bitmap or changed block/layout evidence can legitimately require a new pass, so do not impose one estimator call per page forever.

The estimator receives a cleaned-image read borrow and immutable final source/translated text plus geometry, returns stable-key styles and releases the borrow before bitmap disposal. Keep numerical estimation behavior and blank-source eligibility unchanged. This removes the translation read/write race and avoids producing styles merely to overwrite them. Persisted layout/style parity and existing recognition color facts remain separate obligations; do not remove them merely because the fresh inpaint call was redundant. If caller inventory disproves redundancy for a path, document and isolate that path rather than blindly dropping its output.

Do not split locks or backend pools simply to make the interface look independent. Separate capability ownership first, then split physical resources only after lifecycle and memory evidence justify it.

## Translation

The first compatibility adapter receives a read-only request snapshot, builds a provider-private mutable draft using existing `detachedCopy` behavior, invokes current `TextTranslator`/contextual protocols and returns a stable-key result. That keeps providers and their tests usable while removing shared mutation between HTTP and inpaint.

Preserve rolling context, chunk/envelope limits, request governor, output validation, partial retry and interruption ledger semantics. A result contains translated text plus status/failure/retry eligibility actually supplied by the existing provider boundary. It does not independently decide durable terminal state or retry admission.

Subsequent provider migrations may replace the mutable `TextTranslator.translate(MutableMap<...>)` contract with a read-only request and typed result. Migrate all Standard and contextual paths before removing the adapter; do not change provider parsing, batching, network retries or language semantics in the same patch.

Result application is a coordinator/store operation after identity checks. Partial retry applies only eligible returned block results while retaining successful existing translations. Reordered blocks, missing responses, unknown/duplicate IDs, source mismatch and late provider results have explicit rejected/partial dispositions. No array index or response arrival order is authoritative.

## Stable identity across sorting and resume

`TranslationBlock.blockId` is nullable, and HTTP sorts blocks before translation. Therefore a `Map<Int, ColorStyle>` keyed by list position is unsafe.

Prefer valid existing OCR/durable stable block IDs and retain their source/fingerprint meaning. At operation entry, inventory missing or duplicate IDs. For legacy data, create a nonpersisted key binding page execution identity, OCR evidence and the original block occurrence; maintain its association through sorting/private copying. Geometry/text alone may not be unique, so duplicate occurrences must be distinguished. Do not modify durable IDs or source hashes merely to satisfy the adapter.

Before merging any stage output, verify that its key belongs to the same recognized block set and OCR evidence. Missing/ambiguous mappings reject the affected output through an existing typed failure/partial path; they do not silently attach styles/translations to a nearby block. MR-00 must characterize real legacy cases, and MR-03 must prove deterministic behavior.

## Publication and guarded result receipts

`CleanedPublication` already has the substantial source/location/loading behavior; `CleanedImagePublisher` already orders file creation, guarded commit and prior-file retirement. Use them as the implementation, exposing a small publication request/result contract if direct injection would otherwise retain a ten-argument public callback.

A request carries chapter/page identity, the existing applicable `PatchPrecondition`, output provenance and an owned bitmap borrow. A receipt distinguishes queued live publication from verified durable completion and provides the store-generated successor snapshot. Auto's `PreparedPage` requires durable verification; manual lazy publication may use the existing memory-first path but cannot advertise committed display prematurely.

Add fields inside an existing cohesive precondition/request when semantically appropriate; do not add a top-level constructor lambda parameter through three layers for each new publication rule. Only the store evaluates write eligibility. The capability transports and returns receipts, preserving lease, generation, candidate, artifact-version and dependency evidence.

Cancellation/stale rejection cleans uncommitted new files according to existing recovery ordering. Never retire an image that still backs a committed bundle or delete a successfully committed new file merely because the caller stopped waiting. Lazy workers retain bitmap borrow until their completion acknowledgment, and `flush` remains a durability/ownership boundary until an equivalent mechanism is proved.

Follow the commit-decision/receipt contract in [ownership](../ownership). Exception or cancellation is not proof of rejection. Memory-first pointer publication and durable acceptance must be reconciled separately, with a noncancellable decision/receipt window or an equivalent authoritative transaction receipt before deletion is eligible.

# Replace callback forwarding without hiding it

`BatchChapterTranslator` currently has 24 parameters, including 20 function-typed parameters; `BatchLaneWorkers` has 39, including 11 function-typed parameters. These counts motivate investigation, not an arbitrary target of five parameters.

Inject a small number of cohesive operations as intact dependencies into both modes. Separate immutable run configuration from page-specific authority and batch-owned mutable registries. `BatchChapterTranslator` may pass capability references to its workers but must not redeclare each implementation signature or unpack the capability back into closures.

A focused recognition/inpaint execution facade is acceptable only if it owns real shared stage behavior and hides neither scheduling nor persistence authority. Prefer separate capabilities if a broad facade merely recreates a service bag. Composition-time adapters are temporary migration tools, with named owners and removal packages.

The target for an inpaint retry input or publication request-field change is: edit the responsible contract/implementation and true semantic callers; leave unrelated scheduler policy and intermediary constructor wiring unchanged. Adding an engine-private metadata field can require zero orchestration edits both before and after refactoring; do not claim a gain where the baseline already isolates the change.

# Typed rejection semantics

Keep `SinglePageOutcome` as the existing execution result boundary. Recommend a stable `PageRejectionKind` beside that contract, carried by `Rejected` together with owner and separate diagnostic detail. Distinguish at least not-saved publication, already-running/owned work, session conflict, defunct/source-unavailable cases where producers currently expose them, and an explicit fallback kind. Final enumeration comes from the producer inventory, not invented categories without real callers.

Lower-level lease/store APIs expose their own typed rejection codes in their owning packages; the execution layer maps those codes into `PageRejectionKind`. Never infer categories from strings or make persistence import the pipeline enum. Preserve the current batch-owner session-switch precedence where applicable and explicitly test it against persistence rejection.

Presentation maps typed kind and owner to label, severity, retry affordance, actions and accessibility description. Detailed diagnostic text remains available through logs/details. Changing a diagnostic sentence must change none of those decisions. Typed outcomes do not justify adding a second durable lifecycle.

# Package ownership and enforcement

| Contract/helper | Proposed owner | Rationale |
| --- | --- | --- |
| `NativeVisionTelemetry` | `diagnostics` | Shared logging vocabulary used by engines, pipeline and reader UI |
| `DeviceStageNormalization` | `diagnostics` | Pure trace-normalization denominators; adaptive policy remains in `pipeline/adaptive` |
| Shared `BatchContextFrontier` behavior | `context` | Shared natural-order rolling history algorithm; retain semantics and defer renaming until migration |
| Recognition/inpaint engine inputs/results | Respective engine package or existing shared read-only model | No dependency on scheduler, operation registry or store implementation |
| Page operation/result transport and orchestration capabilities | Focused `pipeline/execution` or stage-owned pipeline package | Execution ownership belongs above engines; no generic new framework |
| Write receipts/preconditions | Existing persistence-owned contracts | Store/artifact authority remains local to persistence |
| Typed execution rejection | Existing `pipeline/execution` result boundary | Outer presentation may consume it; low-level stores map their own typed codes |

Add a lightweight source dependency test to existing JVM CI. It must recognize imports and fully qualified references, with comments/strings excluded where feasible; include a self-test showing a planted forbidden edge is detected. An AST-backed tool is optional if it is already available or simpler than a home-grown scanner; do not introduce a large framework solely for three imports.

Rules initially forbid engines, context, model, diagnostics and persistence from referencing implementation in pipeline/scheduling/workflow. Verify the entire baseline before enabling a rule; legitimate exceptions must name the exact symbol, rationale, owner and removal/review condition, not a wildcard package allowance. Presentation consuming execution results and workflow's existing pure presentation projection are documented exceptions to a simplistic total ordering.

Also test that recognition capability no longer exposes inpaint and that stage engine inputs cannot carry a store authority. Keep these checks behavior/contract focused rather than asserting filenames or line limits. Package relocation inside `:app` repairs source boundaries; module compile isolation is not claimed.

# Contract coverage required

- Recognition retains block/mask/detection evidence and source/config parity.
- Inpaint and translation can run concurrently without sharing mutable blocks or page statuses; all returned effects survive joining.
- Reordering, legacy/missing/duplicate IDs and partial retry cannot misassociate results.
- Stage diagnostics can change without presentation behavior or scheduler policy changes.
- Publication receipts preserve existing fences and committed-file retirement; pending lazy writes do not masquerade as durable prepared results.
- Capability contract changes do not require forwarding edits in `BatchChapterTranslator` or unrelated scheduling files.

Use [implementation](../implementation) for the adapter removal sequence and [validation](../validation) for change-impact targets and CI gates.
