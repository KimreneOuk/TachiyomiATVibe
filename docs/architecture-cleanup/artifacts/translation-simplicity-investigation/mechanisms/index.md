---
kind: review
title: "Minimal page ownership and fingerprint cache: alternatives and proof obligations"
comments: none
---

# What can disappear with sequential stages

Baseline: `4de121bf7ed486e9f39b5a3f4f5c4288f439c8c8`. Static investigation only. Parent [reassessment](..) records the new priority order; alternatives here are not final implementation contracts.

`PageInpaintingEngine.inpaint` is already synchronous. The detached `async` is created by `SinglePageOnnxPhase.processSinglePage`, not required by its engine API. Calling recognition and inpaint in order inside the same quarantine invocation removes a child task and prevents native admission from ending before that inpaint finishes.

The operation should capture the original caller Job **outside** quarantine, and maintain explicit abandonment for timeout/session replacement as well. The independent invocation must check that intent before subsequent stages/writes. Its own `ensureActive()` cannot substitute for the original token because quarantine strips the caller Job.

Removing the child means there is no child to cancel, lazily start, register or join. It does **not** mean abandoning the independent native invocation is safe. Its actual completion still controls native occupancy, engine close and resource release.

## Resource boundary options

| Option | Mechanism | Benefits / costs | Investigation status |
| --- | --- | --- | --- |
| A: own bitmap through preparation publication | Quarantined invocation performs OCR/inpaint, required guarded preparation publication and writer drain, then recycles its bitmaps and returns only non-owning metadata or durable PreparedPage evidence. Translation/render subsequently loads the background. | Smallest owning transfer surface; may hold admission across disk work and add image decode/JPEG effects. No bitmap in the ignored quarantine return. | Leading minimal prototype, conditional on all supported store modes, source/display semantics and measurements. Existing auto preparation provides useful boundary code but is not copied wholesale. |
| B: one registered operation owner across native return | External page operation owns allocation fields before invocation; native call fills them; quarantine returns a non-owning completion signal. A small exclusive handoff/finalizer handles abandon versus claim. Storage runs outside native. | Retains shorter native occupancy and avoids mandatory reload; still needs ownership transfer, cancellation race tests and writer-borrow tracking. | Fallback if A's real costs are material. Prefer a small single-owner holder without lazy child or generic allocation registry. |
| C: retain detached concurrent inpaint | Existing async overlap plus full registered child/output lifecycle and styles isolation. | Highest number of interactions; benchmark must justify same-page overlap. | Not the default under the supplied report. Old plan remains evidence for hazards if this is deliberately selected. |

Option A is **not** permission to perform unauthorized publication while a timed-out invocation drains. Check intent and existing store fences at every acceptance boundary. If an accepted file already exists when cancellation wins result delivery, retain the file and recycle only the local allocation. The returned metadata being ignored cannot grant a later stage write authority.

`CleanedPublication.persistLazyCleanedBitmap` queues a closure using `cleanedBitmap` at lines 346–360 and returns its completion at line 431. Therefore every option must join the writer's **actual** completion before recycling that allocation. `store.flush()` / `publishPreparedPageFromOcr` already supply relevant barriers. Flush failure must not be mistaken for proof that every borrower is gone; the lifetime barrier and publication-success decision are distinct. Never hold the page-store mutex while awaiting its persistence worker.

Do not introduce mandatory disk-only handoff for a store/source mode until that mode's publication and reload path is verified. Do not add a memory-only fallback that silently becomes a second ownership model. This compatibility/performance question is why A remains a prototype recommendation rather than settled architecture.

## Null, alias and empty-text results

`PageInpaintingEngine.kt:38–42` returns null with READY when its erase plan is empty; failures also return null but record FAILED. These are not equivalent. `SinglePageOnnxPhase.kt:1178` currently skips the detached fresh path for empty OCR blocks; preserve the intended textless terminal path separately.

Use a small local result classification or explicit status check at the owned boundary: cleaned image, verified no erase needed, failure/defer, textless terminal. It need not become a broad public result hierarchy. If output aliases input, dispose once by object identity. If no erase is needed and rendering needs the original, keep it owned until its actual use ends. Do not mark every null result FAILED or recycle the original before drawing. Existing inpaint revisions/fingerprints and detector erase evidence remain authoritative.

## Block ownership and styling

With sequential inpaint, provider work and color estimation:

1. Obtain an operation-private working copy using existing detached-copy helpers. Published store views remain immutable; a private copy is not a new store authority.
2. Finish inpaint before passing the draft to provider translation. Remove `SinglePageOnnxPhase.kt:1203` early `recomputeFor`.
3. Let existing provider/request protocols sort/filter/retry their owned blocks with their current identity semantics. Inspect `SinglePageHttpRenderPhase.kt:555` retry subset and actual provider parsers; do not add a second identity scheme merely to merge two stages that no longer run concurrently.
4. After translation, run existing final `recomputeFor` on the owned draft and actual background. Preserve its text/translation skip semantics and layout-owned stroke width.
5. Recompute only for a changed background on downscaled retry; preserve resume-only final styling where it is currently needed.
6. Guard final publication against the original OCR/source version, lease/generation and user-edit policy. Serial draft mutation alone cannot prevent stale store commits.

`TranslationBlock.blockId` and `StableBlockIds` remain existing contracts. Unique IDs, legacy null IDs and repeated geometry still need tests, but request-scoped ReaderBlockKey/envelope sidecars are no longer assumed necessary. If source inspection or provider tests demonstrate a real copied-result merge, use existing stable identity or a minimal occurrence association at that boundary only.

## Publication receipt safety remains independent

`CleanedImagePublisher.publish` catches cancellation from its commit callback and deletes the new file; other thrown commit exceptions are converted to rejection and deletion too. A throw does not alone prove there is no accepted reference.

Retain the former plan's principle: a narrow non-cancellable commit-decision/receipt section, conservative store-owner-supplied deletion permission and retention of uncertain outcomes. These are correctness measures, not optional throughput optimizations. Keep previous committed files until owner-aware retirement permits removal. Do not give the publisher independent state authority or hold the whole network/page operation NonCancellable.

The synchronous design still needs receipt/delivery race tests; removing async inpaint does not fix accepted-commit cancellation by itself.

# Simpler fingerprint cache first

The current Map performs hidden blocking disk work. Remove that interface regardless of the concurrency policy selected.

**Recommended initial API shape**, co-located with the batch owner:

```kotlin
internal class SourceFingerprintCache(
    private val streams: Map<String, () -> InputStream>,
    private val compute: suspend (() -> InputStream) -> String?,
) {
    private val mutex = Mutex()
    private val resolved = mutableMapOf<String, String>()

    suspend fun resolve(pageKey: String): String? = mutex.withLock {
        val stream = streams[pageKey] ?: return@withLock null
        resolved[pageKey]?.let { return@withLock it }
        // Deliberately serialize cache misses; this mutex is never a store lock.
        // compute owns dispatcher selection and stream use/close.
        val value = compute(stream) ?: UNKNOWN_SOURCE_FINGERPRINT
        currentCoroutineContext().ensureActive()
        resolved[pageKey] = value
        value
    }
}
```

This is illustrative; it is not a compiled patch. Holding a **suspending cache mutex** across hashing deliberately serializes misses. It does not hold a ConcurrentHashMap bin monitor or block an IO carrier with runBlocking. It is simpler than a Deferred registry, but different-key progress is serial and cancellation of the producer requires a later waiter to recompute. These are acceptable candidates, not measured conclusions. `compute` must remain cancellable between reads and close streams; cancellation is never stored as successful unknown evidence.

Keep keys/size as cheap source metadata, and an explicit resolved-evidence snapshot where planning needs a plain map. No property getter performs I/O. Preserve computed-null UNKNOWN fail-closed semantics; thrown failures remain eligible for the existing retry policy. Freeze the cache to one source/configuration run.

All seven propagation owners identified in the older [bridge plan](../../translation-step1-plan/boundaries-and-bridges) remain relevant: BatchChapterTranslator, BatchResumePlanner, StandardLaneWorker, BatchLaneWorkers, ChapterProfileBatchCoordinator, PreflightWorker and FinalizeWorker. Resolve evidence **before** actual store/coordinator critical sections and pass it into synchronous planners. Do not infer those sections from names: `ChapterProfileBatchCoordinator.buildEnvelopeDispatchWorkLocked` is called at line 672 outside the short `withArtifactEngineLocked` blocks that begin/end manifest coalescing at lines 668/674. There is no encompassing mutex in that shown caller. Its per-page lookup at line 710 can become suspending without first resolving the whole corpus merely because the method is named Locked; preserve subsequent snapshot/version revalidation and inspect real nested store-lock scopes separately.

`PageDecode.computeSourceFingerprint:121–138` currently closes the stream with `use` and rethrows CancellationException, but its read loop has no cooperative cancellation checkpoint. Add/check cancellation between reads in the suspending-cache slice if prompt hash cancellation is required. Do not claim cancellation interrupts a blocking stream read; cleanup still follows that read's actual exit. Ordinary caught I/O exceptions currently become null/UNKNOWN evidence, so making them retryable needs an explicit error-policy change rather than an accidental cache redesign.

Precompute only needed evidence as a still simpler alternative for phases already serialized. Do not hash every pristine chapter page merely to satisfy a synchronous planning API. Legacy alias resolution and completed-corpus validation have broader legitimate scans; preserve them explicitly.

Tests should cover reuse, same-key contention, serialized distinct keys, failed compute retry, cancelled producer/waiter, unknown evidence and batch/source generation changes. Do not carry over the old different-key-concurrency assertion as mandatory for the serial option.

# Simplicity proof gates

Compare alternatives using: owning transfers, independently mutable working copies, spawned tasks, wait/lock ordering edges, cancellation exits, source files that need signature-only edits, and essential test effort. A design with fewer files but more ownership states is not automatically simpler.

The old native-holder review was correct to require drainage **given its lazy child design**. Deleting the child eliminates that particular requirement; it does not overturn the review's safety invariant. New designs require a new critique and actual race/device gates before implementation readiness.
