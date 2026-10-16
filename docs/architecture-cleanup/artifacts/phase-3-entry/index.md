---
kind: spec
title: "Phase 3 entry packet - sequential page ownership"
comments: none
---

# Scope and entry status

Phase 3 architecture planning is complete and independently approved; see the [approved plan](../phase-3-planning/plan) and [planning closure](../phase-3-planning). Published baseline: `fbbd19d19a49ea1a0b43995f8c0814fc21dfa1ec` on local and remote `architecture-cleanup`. Phase 3 implementation has not started. Production source remains identical to Phase 2 at999bb9be; the new commit changes only two tests. The [roadmap](../translation-simplicity-investigation/phases) defines Phase 3 as one sequential page job with safe bitmap/native/publication ownership. The [older mechanism alternatives](../translation-simplicity-investigation/mechanisms) remain alternatives, not a chosen implementation contract.

The [published repair closure](../store-baseline-investigation/repair/closure) satisfies the targeted store investigation/repair/rebaseline prerequisite. Both full app flavors ran332 suites/2254 tests with0 failures,0 errors and1 benchmark skip; ProbeTest14/0 and TraceTest3/0. Gemini independently accepted code and final evidence. No production storage repair was needed. Existing source indices below remain valid because publication changed tests only. Implementer preference is GLM5.3Flash/max through OpenCode; reviewer Gemini3.8Flash/high through Antigravity.

# Current source entry points

Paths relative to app/src/main/java/eu/kanade/translation; references refreshed by source search at999bb9be.

| Owner | Relevant current surface | Phase3 question |
| --- | --- | --- |
| pipeline/SinglePageOnnxPhase.kt | processSinglePage at1093; detached CoroutineScope(Default).async at1183; early style pass at1204; result carries inpaintJob at1323 | Replace detached same-page work with sequential ownership without discarding physical native completion or alias/null/textless cases |
| pipeline/SinglePageHttpRenderPhase.kt | inpaintJob await at663?665; final style passes at757/876; cleanup at1069 | Keep provider/render processing compatible while removing the asynchronous inpaint handoff; distinguish required changed-background style work |
| pipeline/TranslationPipeline.kt | withNativeLane at286; reader native preparation around579; inpaintJob branching at669 and952 | Native admission ends after actual local work and before remote HTTP; preserve existing reader/auto contracts until Phase4 |
| pipeline/execution/NativeRunQuarantine.kt | run at64, caller Job removed at72, NonCancellable late-exit wait at112?119 | Cancellation and timeout cannot imply JNI completion; abandoned results must not leak owning allocations |
| pipeline/EngineLane.kt | withNativeLane at163 | Preserve engine/native teardown and generation exclusion without redesigning OCR/LiteRT internals |
| pipeline/CleanedPublication.kt | persistOnnxCleanedImage at235; lazy cleaned writer at294; guarded commit callbacks | Writer borrow lifetime and publication success are separate; recycle only after actual borrower completion |
| pipeline/execution/PreparedPageBoundary.kt | publishPreparedPageFromOcr at33 and recycle path at75 | Verify existing prepared-page handoff compatibility across supported store/source modes |
| persistence/chapter/CleanedImagePublisher.kt | publish at24, commit callback at43 | A cancelled/thrown commit does not prove newly published file is unreferenced; preserve accepted/uncertain receipts with store-owned deletion permission |
| engines/rendering/RenderColorEstimator.kt | mutable styling implementation | One final style pass on an operation-private draft, preserving text/translation/background retry semantics |
| workflow/ReaderTeardownCoordinator.kt | awaitReaderStop at90, cancellation at132/154 | Reader/chapter teardown waits for actual work/resource exit; no assumption ordinary coroutine cancellation stops native inference |

# Decisions required before a concrete implementation ticket

- Choose the smallest safe boundary: finish local preparation/publication inside one owner and return durable/non-owning evidence, or retain a minimal registered owner with exclusive handoff outside native admission. A disk-only handoff is not preselected; inspect store/source compatibility and decode/latency consequences first.
- Keep per-page stages sequential by default and preserve simple cross-page native/HTTP overlap where existing admission permits it. Do not add a new producer/consumer framework or rewrite the reader queue in Phase3.
- Define original caller abandonment versus independent quarantine execution, exclusive bitmap identity ownership, native-exit barrier, queued writer borrowing, and accepted/uncertain publication receipts. These correctness obligations survive removal of detached inpaint.
- Use existing detached-copy helpers and TranslationBlock.blockId/StableBlockIds. Do not add a competing block identity or merge sidecar without a demonstrated copied-result boundary needing it.
- Preserve one ChapterTranslationStore authority and all generation/version/lease/PatchPrecondition, source and user-edit fences; keep PageLifecycle/PageView/PageDisplayProjection intact.

# Proof and remaining evidence

A concrete Phase3 plan needs current per-path ownership traces plus meaningful cancellation/timeout/late-native-exit, bitmap alias/no-erase/textless, writer completion, uncertain-publication and display-fence tests. Host-controlled probes do not establish physical JNI cancellation safety. Phone/native performance and physical teardown evidence remain deferred and explicit for relevant behavior acceptance; local JDK21 does not establish CI17 parity. Existing LiteRT formatting debt is unrelated and stays untouched.

At Phase3 entry, prepare a small ordered commit/test plan and independent Gemini critique before changing native/page ownership. This packet locates work and unresolved decisions; it is not approval of an old holder/priority-gate rewrite or a generic workflow engine.

# Planning started

The user subsequently began [Phase3 architecture planning](../phase-3-planning) using GLM5.3Flash/max with Gemini/Antigravity review, and chose memory-first rendering as the basis for the plan. Native/page ownership implementation remains unstarted. The planning artifact records the settled handoff decision and current critique status; the alternatives listed above are historical entry questions, not still-open product choices.

# Planning disposition

The memory-first ownership contract and ordered implementation/proof sequence are now independently approved. Entry questions above are historical; the approved plan is authoritative for the chosen mechanism. Implementation and device validation have not started.

# Implementation authorization

On 15 October 2026 the user authorized [Phase 3 implementation](../phase-3-implementation) using GLM5.3Flash/max with Gemini through Antigravity reviewing. S1 is dispatched. The approved plan governs; implementation and device gates must be evidenced before full phase closure. Earlier statements of unstarted implementation are historical.
