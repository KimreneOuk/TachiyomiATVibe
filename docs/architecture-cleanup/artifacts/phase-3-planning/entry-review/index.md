---
title: "Phase 3 Entry Review: Constraints, Boundary Tradeoffs, and Load-Bearing Questions"
kind: review
comments: none
---

# Review Verdict: ENTRY APPROVED FOR READ-ONLY ARCHITECTURAL PLANNING

Phase 3 entry at published commit `fbbd19d19a49ea1a0b43995f8c0814fc21dfa1ec` on branch `architecture-cleanup` is **APPROVED FOR READ-ONLY ARCHITECTURAL PLANNING**.

This review applies the `traycer-artifact-critique` technical lens to the assumptions in [Phase 3 Entry Packet](../../phase-3-entry) and [Phase Roadmap](../../translation-simplicity-investigation/phases). It independently evaluates the boundary between two competing ownership archetypes - **Durable / Non-Owning Prepared-Page Handoff** vs **Small Registered In-Memory Operation Owner** - without preselecting either design.

All work in this stage is strictly read-only decision preparation. No code edits, Gradle runs, configuration changes, or commits are authorized.

# Audit of Current Source Reality & Entry Assumptions

The current codebase at HEAD (`fbbd19d1`) retains the identical production pipeline from Phase 2 (`999bb9be`). Source analysis reveals several critical realities that constrain any sequential page ownership redesign:

### 1. Detached Async Inpainting and Native Lane Scope

- **Observed Source**: In `pipeline/SinglePageOnnxPhase.kt` (lines 1183-1207), inpainting is spawned inside `CoroutineScope(Dispatchers.Default).async`.
- **Architectural Consequence**: In `pipeline/TranslationPipeline.kt` (lines 576-620), `withNativeLane` encloses `translateSinglePageOnnx`, which invokes `SinglePageOnnxPhase.processSinglePage`. When `processSinglePage` and its surrounding lane block complete, `withNativeLane` returns and releases the native permit. However, the detached `inpaintJob` continues executing concurrently on `Dispatchers.Default`.
- **Critique**: The existing pipeline claims native lane mutual exclusion, but native inpainting work can outlive the native lane permit boundary. Sequential page ownership must bring inpainting back inside coherent native admission without blocking unrelated HTTP requests across pages.

### 2. Dual Style Estimation & Text-Dependent Eligibility

- **Observed Source**:
  - `SinglePageOnnxPhase.kt:1204`: `RenderColorEstimator.recomputeFor(it, blocksCopy)` runs inside the detached inpaint job.
  - `SinglePageHttpRenderPhase.kt:757`: `RenderColorEstimator.recomputeFor(cleanedBitmap, pageTranslation.blocks)` runs again before overlay rendering.
  - `SinglePageHttpRenderPhase.kt:876`: Runs inside retry handling if a new cleaned bitmap is provided.
  - `RenderColorEstimator.kt:298`: `if (block.translation.isBlank() && block.text.isBlank()) continue`.
- **Architectural Consequence**: While pixel color sampling is based on background regions within bounding boxes (`x, y, width, height`), block eligibility explicitly depends on text presence (`block.translation` or `block.text`). At OCR completion, `block.translation` is blank.
- **Critique**: Pre-HTTP styling cannot be assumed equivalent to post-HTTP styling. Translation provider mutations, text replacement, block filtering, or retries can alter the block set. Running color estimation on `blocksCopy` in a detached coroutine while HTTP translation is in flight also introduces a concurrent mutation hazard on mutable `TranslationBlock` fields (`textColor`, `strokeColor`). Final styling must be evaluated where the translated blocks and background pixels are settled.

# Archetype Comparison: Durable Handoff vs In-Memory Owner

Neither archetype is preselected. Both represent distinct tradeoffs between memory lifecycle, decode latency, teardown coordination, and failure handling. Across any pipeline, decoded source bitmaps, intermediate cleaned bitmaps, rendered overlay outputs, and persistence writer buffers may coexist; each path must be individually traced.

| Dimension | Option A: Durable / Non-Owning Prepared-Page Handoff | Option B: Small Registered In-Memory Operation Owner |
| --- | --- | --- |
| **Handoff Mechanism** | Native stage persists cleaned image to disk, disposes of intermediate memory references, and hands off non-owning metadata (`PreparedPage`). | Native stage hands off an active in-memory bitmap handle wrapped in an explicit lifecycle-bound owner. |
| **Memory Lifecycle** | Releases the in-memory cleaned bitmap before HTTP translation begins; relies on file storage across network operations. | Cleaned bitmap remains resident in memory across network operations until rendering finishes or the page cancels. |
| **Decode / Latency Tradeoff** | Requires reading and re-decoding the persisted file from disk storage when rendering or color sampling requires the cleaned image. | Renders directly from the resident in-memory bitmap without a secondary storage decode pass. |
| **Storage Dependency** | Tightly couples stage progression to durable storage availability and serialization. Requires handling storage write failures before translation. | Permits decoupled or memory-first persistence; rendering can proceed before disk writes settle. |
| **Failure & Cleanup Surface** | Incomplete operations risk leaving unreferenced files on disk if publication is interrupted. | Incomplete operations risk leaking in-memory bitmaps if cancellation or timeout fails to trigger deterministic disposal. |
| **Textless Page Handling** | Must accommodate pages with no cleaned file on disk (0 text blocks) without attempting invalid file re-reads. | Naturally represents textless pages as an owner carrying a null cleaned bitmap. |

# Independent Analysis of 5 Critical Dealbreakers

### 1. Quarantine Abandoned Results & Late JNI Exit

- **Source Grounding**: `pipeline/execution/NativeRunQuarantine.kt:64-126`.
- **The Vulnerability**:
  - `NativeRunQuarantine.run()` is generic over `T` and executes the native block in `scope.async(callerContext.minusKey(Job))` (line 74).
  - When a timeout occurs or the caller job cancels, the quarantine catches `CancellationException` or triggers `onTimeout`, increments the generation counter, and enters `awaitExitAndLogLate()` under `withContext(NonCancellable)` (lines 112-126).
  - Inside `awaitExitAndLogLate`:
        ```kotlin
        exited.await()
        runCatching { invocation.await() }
        logcat(LogPriority.WARN) { ... }
        ```
  - **Dealbreaker**: `NativeRunQuarantine` possesses no generic disposal mechanism for `T`. When `invocation.await()` resolves, the returned result is discarded without inspection.
  - If `T` carries reachable resource-bearing allocations (such as an Android `Bitmap` or native engine buffers), relying on eventual JVM garbage collection fails to provide a deterministic lifecycle under timeout or cancellation.
  - Furthermore, while JNI cancellation feasibility depends on the specific engine and platform backend, Kotlin coroutine cancellation cannot forcibly abort an in-flight JNI call.
- **Requirement for GLM Brief**: The design must define an explicit cleanup obligation for reachable resource-bearing returns from quarantined execution on cancellation and timeout paths, without relying on non-deterministic GC or assuming JNI calls instantly abort.

### 2. Exclusive Bitmap Aliases, Null, and Textless Pages

- **Source Grounding**: `SinglePageOnnxPhase.kt:1177-1179`, `PreparedPageBoundary.kt:75-86`, `CleanedPublication.kt:243-247`.
- **The Nuance**:
  - When OCR detects zero text blocks (`pageTranslation.blocks.isEmpty()`), or OCR fails, inpainting is skipped entirely. `pageTranslation.cleanedBitmap` remains `null`.
  - In `PreparedPageBoundary.kt:86`, `cleanedImageName` is `null` for such pages, but the page represents a valid terminal state.
  - Furthermore, if an engine returns the input source bitmap or an alias instead of a distinct allocation, recycling semantics depend on decode ownership.
- **Dealbreaker**:
  - **Durable Handoff**: If the handoff expects an on-disk cleaned file, textless pages have no file. The contract must explicitly represent `cleanedImageName == null` and fall back to the source bitmap without attempting to decode a non-existent file.
  - **In-Memory Owner**: The owner must trace decode ownership per call site to determine whether a bitmap is exclusively owned for disposal or borrowed from an external caller/cache.

### 3. Pending Writer Borrows & Lazy Storage Drain

- **Source Grounding**: `PreparedPageBoundary.kt:66-77`, `CleanedPublication.kt:248-310`.
- **The Tension**:
  - Active reader stores support lazy persistence (`persistLazyCleanedBitmap`), where page metadata is updated immediately, while JPEG compression and storage writes run asynchronously on a background worker.
  - In `PreparedPageBoundary.kt:71-73`:
        ```kotlin
        if (store.isLazyPersistenceEnabled()) {
            store.flush()
        }
        try {
            ocrResult.cleanedBitmap?.recycle()
        } catch (_: Exception) {}
        ocrResult.cleanedBitmap = null
        ```
  - Disposing of or recycling a bitmap while a background persistence worker is actively reading it creates a critical concurrency hazard.
- **Dealbreaker**:
  - The synchronization must separate flush completion/failure/cancellation from actual borrower exit: even if `store.flush()` throws or cancels, the underlying worker thread must be confirmed to have released the bitmap buffer before disposal occurs.
  - If **Durable Handoff** mandates synchronous persistence before handoff, native lane clearance must not be delayed by slow storage I/O.
  - If an **In-Memory Owner** holds the bitmap across HTTP translation, it avoids blocking the native lane on storage writes, but must guarantee that writer borrows have fully completed before final page disposal.

### 4. Uncertain Commit Reference Retention & Cleanup Authority

- **Source Grounding**: `persistence/chapter/CleanedImagePublisher.kt:32-58`.
- **The Problem**:
  - `CleanedImagePublisher.publish()` writes the file (`files.writeVerifiedVersionedFile()`), then calls `commit(newName)` to patch the store.
  - Lines 44-58 show:
        ```kotlin
        val patch = try {
            commit(newName)
        } catch (cancelled: CancellationException) {
            runCatching { files.delete(newName) }
            throw cancelled
        } catch (failure: Throwable) {
            val cleaned = runCatching { files.delete(newName) }.getOrDefault(false)
            ...
        }
        ```
  - **Dealbreaker**: Deleting `newName` immediately upon `CancellationException` or error assumes the commit did not take effect. But if the store committed the entry to its in-memory state or manifest before cancellation was delivered, deleting the file leaves the store referencing a missing file.
  - When a commit is interrupted, the publication status is uncertain (a reference in the store may or may not exist).
- **Requirement for GLM Brief**: Safe deletion cannot be assumed on unhandled throws. Store authorization and receipt knowledge must govern whether physical file deletion is safe, ensuring files referenced by valid store states are never deleted.

### 5. Store/Source Compatibility & Detached Early Style Removal

- **Source Grounding**: `SinglePageOnnxPhase.kt:1204`, `SinglePageHttpRenderPhase.kt:757`, `engines/rendering/RenderColorEstimator.kt:295-321`.
- **The Observation**:
  - Removing the detached early style pass from `SinglePageOnnxPhase` (line 1204) eliminates redundant execution and prevents concurrent mutation of `TranslationBlock` instances during async inpainting.
  - However, `RenderColorEstimator.recomputeFor(cleanedBitmap, blocks)` requires the `cleaned` image pixels to evaluate background luminance.
  - **Dealbreaker for Durable Handoff**: If the cleaned bitmap is disposed of immediately after the native stage under Durable Handoff, the Render stage cannot execute `RenderColorEstimator.recomputeFor` without re-reading and re-decoding the image from storage.
  - Conversely, performing styling before HTTP translation means any subsequent block geometry or text changes during translation cannot update styling without re-accessing the cleaned image.
- **Requirement for GLM Brief**: The brief must trace when styling occurs and how cleaned pixel access is provided for post-translation rendering without imposing redundant storage decodes.

# Open Verification Gates & Bounded Scope Constraints

In accordance with reporting integrity and standing project policies:

1. **Deferred Phone / Physical Validation**: Verification on physical Android hardware (JNI native cancellation, memory pressure under thermal throttling, GPU lane exclusivity) remains deferred, **not waived**. Host unit tests and JVM mocks cannot certify physical JNI termination behavior across all backends.
2. **Platform Toolchain Gate**: Parity between local host JDK 21 (`Oracle Java SE 21.0.8`) and CI JDK 17 remains an open verification gate.
3. **Format Debt Isolation**: Standing formatting debt in `LiteRTMangaInpaintingEngine.kt` remains strictly isolated. No formatting passes or unrelated code churn may be introduced during Phase 3.
4. **Scope Boundaries**: Phase 3 is limited to sequential page ownership and lifecycle boundaries. It does **not** include reader queue redesign (Phase 4), on-device telemetry (Phase 5), or GPU monolithic surgery (Phase 6).

# Load-Bearing Decision Checklist for the GLM Brief

The forthcoming GLM ownership-options brief must explicitly answer these five architectural questions before any implementation plan is drafted:

1. **Quarantine Disposal**: How does the proposed ownership model fulfill cleanup obligations for reachable resource-bearing returns from `NativeRunQuarantine` when caller cancellation or timeout occurs?
2. **Textless & Alias Lifecycle**: How does the design handle pages with zero text blocks or failed inpainting, and how is decode ownership traced to prevent erroneous disposal of shared or borrowed bitmaps?
3. **Persistence vs Lane Clearance**: Where does cleaned-image storage persistence occur in the sequence, and how are writer borrows synchronized with bitmap disposal without coupling native lane occupancy to disk I/O?
4. **Uncertain Publication Receipts**: How does the contract govern file deletion when commits are interrupted, ensuring store receipt knowledge authorizes any file removal?
5. **Style Pass Placement & Pixel Access**: When is `RenderColorEstimator.recomputeFor` executed, and how is cleaned pixel access provided without incurring redundant storage decodes or premature styling before translated blocks are settled?
