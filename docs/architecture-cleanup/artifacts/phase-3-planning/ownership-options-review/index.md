---
title: "Review of Phase 3 Ownership Options Brief"
kind: review
comments: none
---

# Review Verdict: REVISION REQUIRED BEFORE SETTLED SPECIFICATION

The Phase 3 ownership options brief (`phase-3-planning/ownership-options/index.md`) provides valuable source tracing of existing pipeline paths, shallow block mutation hazards, and single-result precedents. However, independent adversarial review reveals several load-bearing gaps, unhandled race conditions, and causal mischaracterizations.

The brief cannot be accepted as a settled basis for implementation planning in its current form. **Revisions are required** to resolve the identified safety gaps before drafting an architectural specification.

# Detailed Critique of Load-Bearing Gaps (v1 Baseline)

### 1. The `delivered = true` Producer-Side Handoff Race

- **The Race Condition**:
  1. The block completes successfully on its background worker coroutine (`invocation = scope.async(callerContext)` in `NativeRunQuarantine.kt:74`). It sets `delivered = true` and returns `result`.
  2. The block's `finally` executes: because `delivered == true`, it skips recycling `cleanedBitmap`.
  3. Meanwhile, on the caller coroutine, `select` (lines 87-98) timed out (`onTimeout` won the race), or the caller coroutine was cancelled.
  4. The quarantine executes `awaitExitAndLogLate(invocation, exited, ...)` (lines 112-126), where `runCatching { invocation.await() }` retrieves `result` and discards it.
  5. The caller never receives `Outcome.Accepted`. The producer's `finally` did not recycle because `delivered` was already `true`.
  6. **Result**: The cleaned bitmap has no deterministic disposal and is left to eventual garbage collection under timeout and cancellation, reproducing the current unowned lifecycle.
- **Root Cause & Resolution**: Setting a local boolean in the producer tracks only that the producer reached its return statement; it does not confirm that the consumer / caller claimed or accepted the result.
- **Design Options**: Solving this does not strictly require modifying `NativeRunQuarantine`. A late-safe pattern could use:
  - An outer operation owner established before invocation, with cleanup running after confirmed real exit; or
  - An explicit two-sided handshake between producer and consumer (caller claims before producer releases disposal responsibility); or
  - Quarantine-level disposal of abandoned resource-bearing return values.

### 2. Writer Borrows: Flush Success vs Aborted/Cancelled Drains

- **Brief Assertion (Section 1.5, Section 3.2)**:
The brief claims that structured locals and `store.flush()` completely solve writer borrows, asserting that `flush()` joins the worker and that defensive `finally` blocks ensure deterministic cleanup.
- **Source Reality (`persistence/internal/StoreWriteDrainCoordinator.kt:47-81`)**:
  - `StoreWriteDrainCoordinator.flush()` acquires `flushMutex` (line 60) and drains tasks in a `while (true)` loop, executing `task.work()` (line 70) and completing `task.result` (lines 78-89).
  - Background task execution scheduled via `scheduleDrain()` (line 154) delegates to the exact same `flush()` method.
  - Therefore, a **successful** execution of `flush()`—such as when protected by `withContext(NonCancellable)`—serializes behind `flushMutex` and drains all pending tasks to completion, successfully establishing a borrower barrier.
  - **The Inferred Hazard**: The risk arises when `flush()` is **interrupted, cancelled, or fails**. If a caller's `flush()` call is cancelled prior to lock acquisition, or if `flush()` throws, the caller unwinds into cleanup. If cleanup attempts an unprotected `flush()` in a cancelled context, it will immediately abort, leaving background tasks in `drainScope` potentially in-flight while the bitmap buffer is recycled.
- **Critique**: Bitmap recycling must be gated on verified borrower completion (such as a guaranteed, non-cancellable flush completion), separating successful drain barriers from aborted or failed flush attempts.

### 3. Commit Uncertainty, Throws, and False Cancellation Claims

- **Brief Assertion (Section 1.5, Section 5 Q4)**:
  - The brief claims: *"On commit CANCELLATION the file is deleted and the cancellation rethrown (44-46) — the store-visible outcome is 'no committed name', matching the delete."*
  - The brief treats addressing uncertain commits as an optional, orthogonal improvement (Q4).
- **Source Reality (`CleanedImagePublisher.kt:42-58`, `CleanedPublication.kt:167-198`)**:
  - `commit(newName)` executes `store.patchPage(pageKey, precondition, "publish cleaned image") { ... }`.
  - If `store.patchPage` updates internal in-memory state and enqueues a journal write, and cancellation strikes immediately upon returning from `patchPage`:
`kotlin } catch (cancelled: CancellationException) {     runCatching { files.delete(newName) }     throw cancelled } `
  - The publisher deletes `newName` from disk, but the store state **already committed and references `newName`**.
  - Deleting the file leaves the store with an inferred dangling reference to a deleted file.
- **Critique**: The claim that cancellation guarantees "no committed name" is false. A thrown exception or cancellation after state mutation creates an uncertain publication state. File deletion must be authorized by store receipt knowledge, ensuring that files referenced by the store are never deleted. This is a mandatory correctness invariant, not an optional optimization.

### 4. Native Lane Exclusion Leak via `retryInpaintDownscaled`

- **Brief Observation & Proposal (Section 1.4, Section 3.2 Item 5)**:
  - The brief correctly observes that `retryInpaintDownscaled` runs without native admission today (line 47).
  - However, in Section 3.2 Item 5, the brief recommends leaving the retry machinery untouched.
- **Architectural Contradiction**:
  - `SinglePageOnnxPhase.retryInpaintDownscaled` invokes `recognitionEngine.recognize(retryBitmap)` (line 844), running both OCR analysis and native inpainting in C++/LiteRT.
  - It is invoked from `SinglePageHttpRenderPhase.kt:803`, entirely outside `withNativeLane`.
  - Claiming that inlining fresh inpainting establishes strict native lane exclusion while leaving `retryInpaintDownscaled` unadmitted is a direct contradiction. If a retry runs unadmitted, it can execute native inference concurrently with another page's native lane work.
- **Critique**: All reachable native execution paths—including retry, resume, and batch—must be reconciled with the native admission model.

### 5. Strawman Comparison of Option B vs Option A Sub-Variant

- **Brief Framing (Section 3.1, Section 3.2)**:
  - The brief characterizes Option B exclusively as a complex, global "keyed registry" acting as a "second authority" duplicating the store, and rejects it on those grounds.
  - Simultaneously, in Section 3.1 and Part 5 Q2, the brief defines Option A as allowing a sub-variant where the in-memory `cleanedBitmap` crosses the native permit boundary for memory-first lazy rendering.
- **Critique**:
  - An operation-scoped owner does not require a global keyed registry or a second authority. An exclusive token or lifecycle-bound handle for a single page operation across stage boundaries is standard structured concurrency.
  - More importantly, handing off an in-memory `Bitmap` across the native permit boundary to the HTTP Render phase is **not** a durable/non-owning handoff (Option A); it is an in-memory handoff.
  - The brief presents a hybrid in-memory handoff disguised as Option A while dismissing all in-memory ownership models via a registry strawman. The alternatives must be compared on their true technical merits.

### 6. Early Style Pass: Eligibility Predicate & HTTP Concurrency

- **Brief Assertion (Section 1.2, Section 2.5)**:
  - The brief asserts: *"At early-pass time translation is always blank (HTTP translate has not run)... The two passes are therefore NOT equivalent in eligibility for blocks whose translation ends blank (provider empty result / error paths)."*
- **Source Reality (`RenderColorEstimator.kt:298`, `SinglePageOnnxPhase.kt:1182-1204`)**:
  - The predicate in `RenderColorEstimator.kt:298` is:
`kotlin if (block.translation.isBlank() && block.text.isBlank()) continue `
  - A block is skipped only if **both** `translation` is blank **and** `text` is blank.
  - For any normal OCR-detected block, `text` is non-blank. Therefore, the predicate evaluates to `false` (block is processed) regardless of whether `translation` is blank or populated.
  - Furthermore, because the detached inpaint job ran concurrently with HTTP translation on shared `TranslationBlock` instances, `translation` was not guaranteed to be blank when the early pass ran.
- **Critique**: The eligibility difference claim is logically invalid for OCR blocks. However, removing the early pass remains correct because: (a) it eliminates an unsynchronized cross-thread write on shared mutable blocks, and (b) final styling must reflect the settled block set after translation, filtering, and retries.

### 7. Teardown Coordination & Inter-Stage Memory Coexistence

- **Brief Assertion (Section 1.6, Part 4)**:
  - The brief claims that `tryRunExclusive` in `EngineLane.kt` prevents engine teardown mid-native during reader stop.
  - It also claims that sequential execution removes concurrent `page-N-cleaned + page-N+1-decoded` coexistence.
- **Source Reality (`EngineLane.kt:496`, `ReaderTeardownCoordinator.kt:90-108`)**:
  - `tryRunExclusive` acquires the native lane lock. In the current codebase, the detached inpaint job runs outside the native lane permit; thus, `tryRunExclusive` could tear down engines while a detached inpaint was still executing.
  - Under the brief's proposed memory-first sub-variant, Page N holds its in-memory cleaned bitmap while in the HTTP phase (outside the native permit). Page N+1 can then acquire the native permit and decode its source bitmap.
  - **Critique**: Memory coexistence across adjacent page stages is not eliminated if the cleaned bitmap crosses the native lane boundary into the HTTP phase. The memory profile must honestly account for stage overlap.

# Independent Re-Review of Revision v2 (Candidate C & Protocol Audit)

The brief author has submitted Revision v2 (`phase-3-planning/ownership-options/index.md` at 2026-10-15T18:00Z), introducing **Candidate C** (a single operation-scoped `CleanedSlot` created before `withNativeLane`), formalizing the per-task join, mandating retry admission, and preserving uncertain publication files.

**V2 Re-Review Verdict: SUBSTANTIAL ARCHITECTURAL PROGRESS; NINE TARGETED SAFETY AMENDMENTS REQUIRED BEFORE IMPLEMENTATION SPECIFICATION.**

Candidate C successfully shifts the architecture toward a sound, single operation-scoped owner without an overbuilt global registry. However, deep protocol audit against the actual codebase reveals nine specific edges where ownership transfer, borrower synchronization, and cancellation boundaries remain incomplete:

### 1. Bounded Join Timeout & Indefinite Bitmap Retention Gap (Section 4)

- **The Gap**: If `withTimeoutOrNull` expires at render end, and the outer `finally` block runs, it invokes the exact same bounded check. If the store writer is delayed beyond the timeout (e.g. disk I/O stall during SAF file creation), the outer `finally` also times out and exits. Once the coroutine scope terminates, all references to the bitmap are dropped. The bitmap is abandoned to eventual GC with no deterministic cleanup.
- **Resolution**: A physical borrower join must not be casually abandoned on a synthetic timeout if the goal is deterministic disposal. Either:
(a) Allow `task.work()`'s own `finally` block to execute the final `bitmap.recycle()` if it observes that the caller has already relinquished ownership; or
(b) The join must be an unbounded `pendingCleanedPublication.join()` under `NonCancellable`, guaranteeing that the borrower has physically exited before the caller frees the buffer. Because `StoreWriteDrainCoordinator` executes `task.work()` within a structured loop, `task.result.complete()` reliably signals physical completion of that task.

### 2. The `claim()` Nulling Gap & Window of Vulnerability (Section 4 Item 4)

- **Proposed Protocol**: `slot.claim()` is called immediately on `Accepted`, nulling the slot. The bitmap is then passed to boundary publication and forwarded to `SinglePageHttpRenderPhase`.
- **The Gap**: When `slot.claim()` nulls the slot, ownership is transferred to local variables in `TranslationPipeline.kt` (lines 665-695). If an exception or cancellation occurs *after* `slot.claim()` but *before* `SinglePageHttpRenderPhase` installs its outer defensive `try/finally` block (or if boundary publication throws), the slot's `disposeIfUnclaimed()` does nothing because `claimed == true` and `bitmap == null`.
- **Resolution**: Ownership must be enclosed in a continuous `try/finally` scope spanning from pre-admission slot creation through final HTTP render completion, or `claim()` must atomically transfer ownership into a designated downstream holder rather than leaving a naked local reference window.

### 3. "Abandoned Enqueues/Writes Nothing" vs Inline Status Mutations (Section 4)

- **Brief Assertion**: *"publication only ever runs on the claimed path with the accepted snapshot's commitPrecondition; an abandoned attempt enqueues nothing, writes nothing, so timeouts/cancellations cannot publish late."*
- **Source Reality**: In `SinglePageOnnxPhase.processSinglePage` (lines 1187-1193) and `TranslationPipeline.kt` (lines 584-586, 606-611), status updates (`updatePageFromCurrentSnapshot`, `markPageTimedOut`) are dispatched during execution. Furthermore, `deferredPublications` are accumulated during the native run.
- **Resolution**: The protocol must explicitly define the fence separating transient status updates from final publication commits. While final JPEG publication is gated on `claim()`, intermediate status mutations must be guarded by generation checks (`store.isLazyGenerationCurrent`) to ensure a timed-out attempt cannot overwrite subsequent page generation states.

### 4. Alias Handling: `cleaned !== bitmap` Transfer Incomplete (Section 3)

- **Proposed Protocol**: The brief suggests: *"set S's local to null when handing C≡S to the slot (defensive identity check `if (cleaned !== bitmap)`)"*.
- **The Gap**: Merely checking `cleaned !== bitmap` inside the admitted block protects the source decode bitmap `S`, but does not account for aliases that may originate from:
  - Downscale retry buffers;
  - Decoded source cache references held by the reader;
  - Store-provided previous display bitmaps.
- **Resolution**: A `try/catch` around `bitmap.recycle()` is a runtime defensive fallback, not an architectural safety proof. The design must explicitly declare that only freshly allocated inpainting output bitmaps are eligible for slot ownership, and any borrowed or aliased reference must be flagged as non-recyclable.

### 5. `retryInpaintDownscaled` Admission & Context Inheritance (Section 6 Item 4)

- **Brief Proposal**: Bringing `retryInpaintDownscaled` under `withNativeLane` without recursive deadlocks because "render holds no permit".
- **The Gap**:
  - `retryInpaintDownscaled` (lines 814-887) performs both page re-decode (`decodePageBitmapAtSize`) and native recognition (`recognitionEngine.recognize`).
  - If `withNativeLane` is wrapped solely around `recognitionEngine.recognize`, the re-decode occurs unadmitted, but more importantly, a timeout in `withNativeLane` during retry would drop the newly created `retryTranslation.cleanedBitmap` unless retry also uses a `CleanedSlot`.
  - Furthermore, `SinglePageHttpRenderPhase` does not currently possess chapter ID or permit priority parameters needed for `withNativeLane`.
- **Resolution**: The retry flow must either: (a) instantiate its own mini-`CleanedSlot` with native lane admission, or (b) be scheduled through the pipeline's primary native lane dispatcher rather than ad-hoc invocation from the HTTP phase.

### 6. Contradiction between Section 7 and Section 6 Receipts/Retry State

- **Brief Contradiction**:
  - In Section 6, Items 3 & 4 mandate modifying publisher receipts to retain uncertain files and bringing retry under native admission.
  - In Section 7, the brief asserts: *"Both leave untouched: quarantine, EngineLane guards, OCR/LiteRT internals, DeferredPagePublications, PreparedPageBoundary, retry machinery, all store fences, receipts."*
- **Resolution**: Textual reconciliation required. The brief cannot claim receipts and retry machinery are "untouched" while simultaneously mandating their alteration.

### 7. Decode Costs & Compatibility Under Alternative D (Section 5)

- **Brief Assertion**: Candidate D adds *"one extra decode per lazy page"* and eliminates in-memory holding during HTTP.
- **Source Reality**:
  - Textless pages (0 text blocks) produce no cleaned image; Candidate D must not attempt to decode a non-existent file for textless pages.
  - If storage fails during boundary persist, Candidate D must gracefully fall back to the original source image rather than attempting a decode of a missing file.
  - In non-lazy stores, durable handoff is already the status quo.
- **Resolution**: Candidate D's cost model must explicitly account for textless bypass, storage fallback paths, and probe store compatibility, rather than asserting a universal single-decode latency delta.

### 8. Hypotheses H1, H2, and H4 are Insufficient for Unsafe Deletion (Section 6)

- **Analysis**:
  - The brief lists H2 (`PatchResult.Rejected` never mutates store state) as a hypothesis justifying immediate file deletion on `Rejected`.
  - However, in lazy stores, a file name may already have been committed by an earlier live patch (`finalName` committed with `hash=null` in `CleanedPublication.kt:313`). A subsequent patch recording content hash may be `Rejected` due to generation change, yet the file *is already committed and referenced*.
- **Resolution**: H2 is invalid for multi-stage lazy publication. Immediate deletion cannot be predicated solely on `Rejected` without confirming that the file name was not committed in the initial live patch. The store must retain sole deletion authority.

### 9. Private Working Drafts & User-Edit Invariants (Section 1.2, Section 3)

- **Analysis**:
  - The brief focuses heavily on removing the early style pass race on shared block instances.
  - However, translation providers can return updated block boundaries, split/merged blocks, or text replacements.
- **Resolution**: The operation must maintain an operation-private working draft of `TranslationBlock` instances through HTTP translation, ensuring that user edits, re-translations, or provider failures cannot corrupt store snapshots or display projections until the final render patch commits.

# Separation of Safety Invariants vs Product Decisions

| Category | Item | Classification & Recommendation |
| --- | --- | --- |
| **Safety Invariant** | Deterministic disposal of abandoned native results | **Mandatory**: Candidate C's slot pattern is causally sound, provided the `claim()` gap (Item 2) is closed. |
| **Safety Invariant** | Borrower exit verification before recycle | **Mandatory**: Must use an unbounded `join()` under `NonCancellable` (or worker-owned release) rather than dropping the bitmap on synthetic timeout. |
| **Safety Invariant** | Store-authorized deletion on interrupted commits | **Mandatory**: Deletion must be governed by store receipt knowledge; no blind deletion on throws/rejections. |
| **Safety Invariant** | Coherent native lane admission for retries | **Mandatory**: `retryInpaintDownscaled` must be integrated into native admission with proper slot disposal. |
| **Product Decision** | Candidate C (Memory-First) vs Candidate D (Durable-Only) | **User Choice**: Candidate C minimizes render latency by keeping the bitmap in memory; Candidate D minimizes memory footprint during HTTP at the cost of a disk re-decode. Both are viable once safety invariants are met. |

# Summary & Next Steps

Revision v2 has resolved the primary producer-side race by introducing the pre-admission `CleanedSlot` and establishing serial native execution. With the nine targeted technical refinements documented above, the candidate architecture is solid.

## Once these nine points are acknowledged in the final specification brief, the design is ready for user presentation on the **Candidate C vs Candidate D** product choice (Q2').

### Reviewer Precision Note on V2 Edge Refinements

In incorporating coordinator guidance on the nine protocol edges:

1. **Liveness & OS Storage Stalls vs Unbounded Joins**: An unbounded NonCancellable join on pendingCleanedPublication guarantees borrower exit *provided* the task completes, but does not protect against unrecoverable OS filesystem stalls (e.g., deadlocked SAF streams). A bounded join with a fail-safe fallback—where 	ask.work() retains exclusive disposal responsibility upon late exit—preserves liveness while guaranteeing that no bitmap buffer is recycled prematurely.
2. **Comprehensive Fencing Beyond Generation**: Fencing during abandoned runs cannot rely solely on store.isLazyGenerationCurrent. All relevant fences—including page version, lease tokens, source fingerprints, candidate generation IDs, and user-edit timestamps—must be respected across all store modes (lazy, probe, and standard).
3. **Eligible Ownership Scope**: Eligible bitmaps include not only freshly allocated inpainting outputs, but also explicitly transferred owned source allocations or private pool allocations. Only borrowed, non-owned references (such as shared reader view buffers or cache entries) must be protected from disposal.
4. **Provider Behavior Scope**: Block transformations and text mutations must be traced against actual translation provider implementations rather than assuming generic split/merge behavior across all engines.
