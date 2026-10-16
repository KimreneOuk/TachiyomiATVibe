---
title: "Phase 3 implementation plan review — structured same-page overlap (Final)"
kind: review
comments: none
---

# Executive Summary & Verdict

**Verdict: APPROVE**

The revised Phase 3 overlap architecture plan (`artifacts/phase-3-planning/plan/index.md`, length ~31KB) comprehensively resolves all five blocking architectural and source-grounding defects identified in the previous review cycle.

The updated plan:

1. **Splits the monolithic phase**: Formally decouples pre-join translation from post-join render at the `:662` await seam (`translateSinglePageHttpOnly` and `renderSinglePageTranslated`), establishing an implementable call graph without artificial DTOs.
2. **Retires the redundant early estimator**: Eliminates the vestigial in-branch color estimation pass at inpaint finish; preserves exactly one authoritative color estimation pass post-join at render entry (`SinglePageHttpRenderPhase:757`), plus the background-changed retry re-run.
3. **Restricts native lane admission scope**: Confines `withNativeLane` strictly to JNI inference, `CleanedOwner.produce`, and local release. Storage receipt registration, NonCancellable enrollment, and `DeferredPagePublications` tail enqueuing execute at boundary scope outside the native permit, protecting global native lane concurrency (`NativeRunQuarantine:46`).
4. **Corrects timer facts and coordinates sibling drain**: Accurately reflects that `inpaintJob.await()` sat inside `singlePageTimeoutMs` and was cancellable. Implements the verified provider preservation policy where sibling HTTP calls are not cancelled on native timeout or non-timeout failure (cases C2, C5), protecting paid provider calls and allowing store truth to outrank timers.
5. **Enforces branch isolation and defensive copies**: Employs branch-private working `PageTranslation` instances, defensive `BubbleMaskRle.immutableCopy()` calls, field-specific merges under `AttemptAuthority.write`, and post-join `attempt.markTimeout` with an executable already-closed guard.
6. **Grounds test harness seams**: Accurately recognizes that `CoexistenceBarrier` currently has no native inpaint gate, specifying a narrow test-harness gate in `TranslationCoexistenceHarness` for deterministic testing rather than inventing nonexistent production seams.

## Detailed Disposition of Review Findings

| # | Review Finding | Status | Verification in Updated Plan |
| --- | --- | --- | --- |
| **1** | **Monolithic `SinglePageHttpRenderPhase` Call Graph** | **RESOLVED** | **§11, §12**: The monolith is split at the existing `:662` await seam into `translateSinglePageHttpOnly(...)` (lines 211–660 logic returning `TranslationCompletionOutcome`) and `renderSinglePageTranslated(...)` (lines 704–795 plus persist-block minus dead await). The boundary explicitly coordinates pre-join translation and post-join render. Legacy monolith is not invoked; no artificial DTOs or wrapper frameworks introduced. |
| **2** | **Contradictory Early Color Estimator Pass** | **RESOLVED** | **§3, §12, §16**: The redundant early estimator pass on inpaint completion is completely retired. Exactly **one** authoritative `RenderColorEstimator.recomputeFor` run executes post-join at render entry ([`SinglePageHttpRenderPhase.kt:757`](file:///C:/Users/ADMIN/Documents/Coding%20Related/tachiyomiATVibe-inpainting-modes-redesign/app/src/main/java/eu/kanade/translation/pipeline/SinglePageHttpRenderPhase.kt#L757)). The retry path's re-run is preserved because a retry genuinely changes the background bitmap. |
| **3** | **Native Lane Scope Pollution via In-Block Enqueue** | **RESOLVED** | **§3, §6, §7, §12**: JNI inpainting produces `cleanedBitmap` into `CleanedOwner` and exits `withNativeLane` immediately. Writer receipt registration, NonCancellable enrollment, and `DeferredPagePublications` tail enqueue occur at **boundary scope outside the native permit**, preventing store synchronization from holding the global native lane mutex ([`NativeRunQuarantine.kt:46`](file:///C:/Users/ADMIN/Documents/Coding%20Related/tachiyomiATVibe-inpainting-modes-redesign/app/src/main/java/eu/kanade/translation/pipeline/execution/NativeRunQuarantine.kt#L46)). |
| **4** | **Imprecise Timer Facts & Sibling Cancellation Policy** | **RESOLVED** | **§3, §14**: The plan accurately acknowledges that current `inpaintJob.await()` sat inside `withTimeoutOrNull(singlePageTimeoutMs)` and that `Deferred.await()` is cancellable. In §14, sibling provider calls are **not** cancelled on native inpaint timeout or failure (cases C2, C5), protecting paid provider calls and adhering to existing store truth outranking timers. Mid-flight provider calls drain normally within T3. Sibling cancellation occurs only upon genuine parent cancellation (C4). |
| **5** | **Working State Isolation & Defensive Mask Copying** | **RESOLVED** | **§4, §9, §13**: Concurrent branches operate on branch-private working `PageTranslation` instances. Segmentation masks are defensively copied using `BubbleMaskRle.immutableCopy()` ([`PageTranslationView.kt:425-436`](file:///C:/Users/ADMIN/Documents/Coding%20Related/tachiyomiATVibe-inpainting-modes-redesign/app/src/main/java/eu/kanade/translation/model/PageTranslationView.kt#L425-L436)). Store mutations are performed exclusively through field-specific merges via `AttemptAuthority.write`. Terminal markers are written post-join via `attempt.markTimeout` with an executable already-closed guard. |

## Architectural Analysis & Verification

### 1. Timer Hierarchy and Exit Semantics (§14)

The timing model defines clear boundaries without introducing artificial timeouts:

- **T1 (OCR Segment)**: Bounded by `nativeTimeoutMs` under `withNativeLane` (quarantine run 1).
- **T2 (Inpaint Segment)**: Select bounded by `nativeTimeoutMs` under `withNativeLane` (quarantine run 2). JNI real-exit join remains `NonCancellable` and potentially unbounded in wall time (device verification gate D3).
- **T3 (Boundary Pipeline Budget)**: `singlePageTimeoutMs` wraps external translation, join wait, and post-join render, preserving the exact wall-clock contract of the existing HTTP+render phase.
- **T4 (Retry Segment)**: Bounded by `nativeTimeoutMs` (quarantine run 3) executing within T3's budget.
- **T5 (Terminal Cleanups & Drains)**: `NonCancellable` real-exit and receipt joins run to physical completion.

### 2. Sibling Coordination & Provider Preservation (§14, C1–C7)

The case analysis preserves provider economy and pipeline semantics:

- **C1 & C2 (Native Inpaint Timeout)**: Mid-flight HTTP translation is allowed to drain normally. If translation completes, translation status remains durable (`translationStatus = READY`). The native timeout marker is written post-join via `attempt.markTimeout`, suppressing render. No paid API call is wasted.
- **C3 (HTTP Finishes First)**: Boundary awaits native inpaint completion; downstream branches proceed based on store truth.
- **C4 (Parent Cancellation)**: Reader cancellation cancels both branches cooperatively, invoking standard reader-stop fast paths.
- **C5 (Native Non-Timeout Failure)**: Sibling HTTP call completes normally; final status records native failure while preserving durable translation.
- **C6 (Soft Failure / Null Cleaned)**: Triggers retry segment 3 post-join under T3/T4; on success, replaces cleaned owner and re-runs color estimation.
- **C7 (Dual Terminal Failure)**: Idempotent `markTimeout` / `markPageFailed` ensures exactly one terminal marker is recorded without race conditions.

### 3. Native Permit Scoping & Storage Decoupling (§6, §7)

Segment 2 inpainting inside `withNativeLane` is strictly confined to:

1. Native JNI execution (`PageInpaintingEngine.inpaint`).
2. Immediate handoff of the result bitmap to `CleanedOwner.produce(cleaned)`.
3. Local native resource release and quarantine slot exit.

Only after releasing `withNativeLane` does the boundary thread register the storage receipt (`owner.registerReceipt`) and enqueue the lazy persistence tail into `DeferredPagePublications`. This prevents disk I/O and store mutexes from holding the global native permit, eliminating queue head-of-line blocking for subsequent OCR requests.

### 4. Working State Isolation (§9)

Because `PageInpaintingEngine` directly mutates its input `PageTranslation` (updating statuses and error messages at lines 44–45, 137–142, and 153–161) and `RenderColorEstimator.recomputeFor` mutates block text colors in place:

- Native and HTTP branches operate on independent working instances.
- Nested segmentation masks are copied defensively via `BubbleMaskRle.immutableCopy()`.
- Updates commit to the central `ChapterTranslationStore` only through `AttemptAuthority.write` using field-specific merges (native branch updates inpaint fields; HTTP branch updates translation fields).

### 5. Implementation Slices & Grounded Test Seams (§13, §15)

The delivery slices are ordered for build and test safety:

- **Slice S1**: Foundation interfaces (`AttemptAuthority`, `CleanedOwner`).
- **Slice S2**: Native adapter (`EngineLane.withNativeLane` multi-segment support).
- **Slice S3**: Method extraction (`translateSinglePageHttpOnly` and `renderSinglePageTranslated`).
- **Slice S4**: Boundary fork/join orchestration in `TranslationPipeline`.
- **Slice S5**: Test harness & coexistence tests. The plan accurately notes that `CoexistenceBarrier` currently has no native inpaint barrier point; S5 defines a narrowly added gate in `TranslationCoexistenceHarness` for deterministic overlap testing rather than fabricating nonexistent production hooks.

## Historical Serial Plan Approval

<details>
<summary>Click to expand historical serial plan approval (superseded by user steering)</summary>

### Verdict: APPROVE (Historical)

The prior serial implementation plan was approved on 15 October 2026 for fully-sequential stage execution. That approval validated:

- `AttemptAuthority` write serialization and timeout closure.
- Whole-chain exception protection in `EngineLane.withNativeLane`.
- Narrow NonCancellable receipt enrollment with exclusive store flusher completion authority.
- Coalesced atomic cutover (Slice S5) preserving build compilation.
- Conservative unbounded retention debt policy.

All underlying correctness primitives (`AttemptAuthority`, `CleanedOwner`, receipt handshake, publisher retention policy) remain valid and are successfully preserved under the revised overlap topology.

</details>
