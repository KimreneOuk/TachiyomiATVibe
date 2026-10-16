---
title: "Phase 3 ownership — candidate protocol (memory-first, structured same-page overlap)"
kind: spec
comments: none
---

# Status and decision record

**Decision (user, structured readiness answer): "Keep memory-first; draft the plan on that basis."** Candidate C (memory-first lazy render handoff) is selected; the durable-only alternative (D) is retired from this document. **Overlap revision (user steering): same-page inpaint may run beside external translation after OCR — the detached-scope overlap that exists today is restructured into an admitted, cancellable, timed fork/join; the fully-serial same-page stage assumption is superseded.** This brief reflects the bounded revision resolving the Gemini REVISE findings (`../plan-review/index.md`): HttpRender split at the await seam, early estimator retired outright, enrollment/publication outside the native segment, corrected await/cancellation facts + sibling case table preserving the paid-provider policy, branch-private working instances + defensive copies + field-specific authority merges + idempotent first-terminal-wins. Facts **F** (pinned @line), proposals **P**. Decision brief only — the authoritative revised contract lives in `../plan/index.md` (status: revised, pending Gemini recheck — docs only, no code/runs).

# §1 Orchestration scope (facts, self-contained)

- Reader-manual: `TranslationScheduler` job → `TranslationExecutor.translateSinglePage` (TranslationExecutor.kt:41) → `pipeline.translateSinglePage` (TranslationPipeline.kt:471). The HTTP render phase runs INSIDE this call (HttpRender invoked at 698-699; deferredPublications threaded 566-576), and teardown joins the scheduler job (ReaderTeardownCoordinator.kt:90-108). One structured operation scope spans OCR → fork(native inpaint segment ∥ external HTTP branch) → join → render ⇒ the owner and attempt live as locals of `translateSinglePage`.
- **F (overlap exists today, unsafely)**: the fresh reader path forks inpaint onto a naked detached scope (`CoroutineScope(Dispatchers.Default).async`, SinglePageOnnxPhase:1183-1232) — no lane admission, no quarantine, uncancellable, outside `nativeTimeoutMs`; the join is a naked unbounded `inpaintJob.await()` (SinglePageHttpRenderPhase:663-669). Revised (P): structured fork in the boundary — inpaint under its own `withNativeLane` (quarantine run 2, own timer), HTTP branch with NO native permit (EngineLane.kt:511-519), bounded join of two bounded branches; detached scope + `inpaintJob` + `Pair` return retired (plan §3 table).
- Prepared/auto: `RollingAutoCoordinator` (902/917) → `executor.prepareSinglePage` (74) → `pipeline.prepareSinglePage` (850); no external branch exists there — OCR + inpaint run inline in ONE lane segment (single-segment shape, detached scope retired); the bitmap ends at the durable boundary inside this call. `translatePreparedPage` (1065) holds no bitmap.
- Quarantine: caller Job stripped at NativeRunQuarantine.kt:72 inside `run`; on TimedOut and on caller cancellation it waits REAL exit under NonCancellable (99-106) before returning/throwing; the completed block value is discarded at 121. `EngineLane.withNativeLane` maps Accepted→value, TimedOut→null (EngineLane.kt:163-192). Segment reuse: OCR, inpaint, and reader retry each run as their own quarantine run.

# §2 Attempt authority (settled; authoritative contract in the plan §4)

The v5 field-capture design was UNSOUND: between `store.patchPage` accepting S0→S1 and the volatile field assignment, `markTimeout` could read stale S0, get Rejected, and strand the page RUNNING — the field is not a linearization point. Settled design (full pseudocode, lock-order proof, and case matrix live in the plan, §4 — this brief does not stack revisions): an internal `attemptMutex` serializes gate-check + `store.patchPage` + own-receipt field advancement AND `closed`/`markTimeout`. `markTimeout` carries an already-closed guard (idempotent no-op ⇒ first-terminal-wins is executable, not aspirational), and the terminal marker is written POST-JOIN after both branches settle, so branch publications never race the terminal transition (plan §4/§14). Under overlap the authority serializes TWO concurrent branch writers with FIELD-SPECIFIC merges — no update ever replaces the page from a stale whole-object copy of the other branch's working instance. Ordering invariants: `attemptMutex → store APIs` only; no store callback acquires attemptMutex; nothing holds `store.mutex` while awaiting borrowers; attemptMutex never waits on native/HTTP. The terminal marker runs under `NonCancellable` through a safe adapter at `EngineLane.withNativeLane`, so marker errors/cancellation can never escape the quarantine's real-exit barrier (inspected edge: a non-cancellation throw from the select's onTimeout branch would exit `run` via the finally WITHOUT `awaitExitAndLogLate`, NativeRunQuarantine.kt:87-109 — the adapter exists because of this). Failures are logged with `markerCompleted` truthfulness (never a silent success claim); NO automatic fallback is claimed — a failed marker leaves the stage marker unwritten, resolved only by the store's existing cancel/stop mechanisms exactly as they exist today (`fastCancelInFlightStagesInMemory` on explicit reader-stop, store 2285-2314). A marker `Rejected` means a foreign writer legitimately advanced the page — terminal-and-ignore, never an overwrite of the winner. Distinct markers preserved: native segment timeout → attempt-scoped `markTimeout` (at the post-join/barrier point, never inside the onTimeout callback — callback moment ≠ withNativeLane return); HTTP timer → existing `markPageTimedOut(nativeTimer = false)` path; cancellation → existing CANCELLED paths (`fastCancelInFlightStagesInMemory`, store 2285-2314, lease-aware).

# §3 The owner (memory-first, one file, operation-scoped)

`CleanedOwner` local to the operation; RETAINED through publication + HTTP + finally (never emptied on handoff). API: `produce(b)` (admitted block only, fresh inpaint output), `borrow(): Bitmap?` (non-owning; borrowers never recycle), `replace(b)` (retry swap), `registerReceipt(d)` (before enqueue, §4), `release()` (sole recycler; enclosing finally; recycles OWNED allocations only after real-native-exit AND receipt-join per §4). Provenance: OWNED (created by this operation or explicitly transferred) vs BORROWED (reader-cache/store-display references — never recycled here). **Executed alias transfer**: `produce`/retry-step MUST null the producer's local reference (and skip pool release) when `cleaned === source` BEFORE the producer's finally — classification alone is not protection (parent #6). H3 (host): engines return fresh output or null.

# §4 Writer receipt — airtight handshake (parent gap 1)

```kotlin
// Store (P — signature change only; body reuses existing 1277-1293 logic):
suspend fun enqueueLazyPersistence(expectedGeneration: Long, receipt: CompletableDeferred<Boolean>,
                                   work: suspend () -> Boolean) {
    var queued = false
    try {
        mutex.withLock {
            if (defunct || generation != expectedGeneration) { receipt.complete(false); return } // F: 1279-1281 semantics
            // H5/P one-line guard: also reject when !hasArtifactPersistenceTarget() (scheduleDrain
            // early-returns at StoreWriteDrainCoordinator.kt:148 — queued-never-drained otherwise)
            writeDrain.pendingLazyTasks.addLast(LazyPersistenceTask(expectedGeneration, work, receipt))
            queued = true
            writeDrain.scheduleDrain()   // F: non-suspending, under the same lock (:1292)
        }
    } finally {
        if (!queued) receipt.complete(false)  // cancellation-before-lock / any throw ⇒ not queued ⇒ no borrower
    }
}
// Caller (boundary scope, AFTER the native segment exits — plan §6; the lane holds
// only JNI inference + owner production + local release):
val receipt = CompletableDeferred<Boolean>()
owner.registerReceipt(receipt)                 // owner holds the handle BEFORE the call — survives any cancellation
store.enqueueLazyPersistence(generation, receipt) { /* borrow bitmap: writeVerifiedCleanedImage */ }
```

- **Completion authority (F+P)**: not-queued ⇒ the callee completes `false` (in-line or finally); queued ⇒ exactly the drain completes it — flush success (StoreWriteDrainCoordinator.kt:78), stale generation (80), flusher cancellation (82-84), flusher failure (89), defunct release of queued tasks (172-177). Receipt completion ⇒ `work()` exited or never started ⇒ no in-flight bitmap borrower. Double-complete is a no-op.
- **F (classification)**: rejected-at-enqueue (1279-1281) / queued (1282-1288) / active (flush loop 64-90) / completed (receipt). `task.result` is only ever completed via `complete(true|false)` — never exceptionally — so the owner joins with plain `join()`.
- **F (liveness honesty)**: the unbounded join can stall the operation finalizer (and scheduler-job join, ReaderTeardownCoordinator 90-108) for the duration of real JPEG/SAF I/O including an indefinite OS-read stall; **no time bound claimed**. Defunct does NOT pre-empt an active borrower (markDefunct joins ≤2s then cancels cooperatively, 116-124). **Device gate, Phase 3**: stall magnitude.
- **F (trace for the target guard)**: lazy mode is enabled per registered production chapter only (`enableLazyPersistence`, store 1259-1261); H5 = verify lazy ⇒ artifact target in production wiring; the one-line enqueue reject is the fallback if not provable.

# §5 File policy (retain/authorize; conservative retention)

- **P**: on commit throw/cancellation the publisher RETAINS `newName` unconditionally (replaces deletes at CleanedImagePublisher.kt:44-46/47-58). On `Rejected`, delete only if neverAcceptedLiveName (derivable: the publisher knows its own live-patch outcome — CleanedPublication 307-338; non-lazy Rejected is the only patch for a fresh versioned name) AND store-authorized.
- **F (authority)**: `mayDeleteCleanedImage` + `referencedCleanedImageNames()` (ChapterTranslationStore.kt:4442-4471) — live ∪ committed ∪ retired ∪ artifact manifest + cross-page (4453-4460). Not an atomic check-and-delete: **P** a small suspend store method returns the decision under `store.mutex`; the file delete runs OUTSIDE the lock (no SAF wait under store lock). Interleave benign for fresh versioned new-names (no foreign committer can reference them); previous-name deletes keep the commit-flow authorization (publisher 59-77).
- **P (uncertain-file ending — smallest justified)**: conservative retention WITHOUT new store state: the retained file is logged with chapter/page/name (logcat ERROR at retain time — the log is the only ledger); reset/teardown does NOT sweep untracked companion names (untracked = invisible to store-driven cleanup); explicitly **UNBOUNDED** retention debt — no budget proof exists and none is claimed; documented as correctness-over-cleanup. Not registered into `retiredCleanedImages` because the registration variant was rejected (plan §10): H8 verified safe for long-settled retired names (CleanedPublication.kt:72, CleanedImageLifecycleController.kt:71 both consult `mayDeleteCleanedImage`), but an uncertain name may have an in-flight commit landing between the point-in-time guard check and delete — blind registration could delete a just-referenced file.

# §6 Retry — admission split and concrete output capture (parent gap 3)

- **F (graph)**: reader: SinglePageHttpRenderPhase.kt:803 → 146-154 → fn (86) wired Pipeline 801-802 → Pipeline 1343-1352 → OnnxPhase 814-887. Batch: BatchLaneWorkers.kt:732 → 331-339 → fn (138) wired BatchChapterTranslator 702 ← Pipeline 1242 → the SAME delegate. Batch calls retry INSIDE its `withNativeLane` (683→732) — wrapping the shared delegate self-deadlocks (non-reentrant Mutex).
- **P (split)**: batch wiring (1242) + delegate (1343) UNCHANGED; the READER wiring lambda (801-802) wraps the retry in `withNativeLane` (params from the pipeline closure; retry's degrade-to-unchanged paths 825-835/846-849 map to TimedOut→null → return page unchanged).
- **P (concrete capture)**: INSIDE the admitted retry block, after `recognize` (844) and the scale decision (857-867), the captured output is the instance assigned to `pageTranslation.cleanedBitmap` (868) — i.e., `scaledCleaned`: `owner.replace(scaledCleaned)` runs in-block BEFORE the retry-scope finally recycles the retry source (880-885); the pre-scale temp is recycled at 861-865 only when distinct (same-instance short-circuit at 857 = single reference, no double free); `retryTranslation.cleanedBitmap = null` (879) completes the producer-side transfer. The retry re-decode is retry-step-owned and never enters the owner.

# §7 Tails — semantic/physical split (settled; authoritative contract in the plan §7)

Enqueue sites: the flush/stream-clear tail keeps its existing site (OnnxPhase:623-633 defer); the persist tail (receipt-based) and render/publication tails enqueue at BOUNDARY scope (persist moved out of the native segment and out of the old in-phase position — plan §6/§7 states the queue-site moves plainly). The drain/orphan discipline is unchanged (Pipeline 634 best-effort on exception, 645-652 fail-closed normal, timeout classified only after drain at 656-663; DeferredPagePublications.kt:40-46 inline-on-orphan). Settled change: each tail is classified — **PHYSICAL** (store.flush, stream release, JPEG completion via receipt, borrow cleanup) ALWAYS finishes; **SEMANTIC** (persist/publication, render commit, status/UI publication) routes through attempt-write authority AT EXECUTION TIME and is suppressed once the attempt is closed/cancelled — gating the outer enqueue alone is insufficient because orphaned tails run inline. A suppressed semantic tail returns normally: it never throws into the fail-closed drain and never overwrites a terminal marker with stale READY. Accepted borrow work is never dropped. Resume/deferred-cleanup paths traced in plan §7 (resume persist inline in `renderResumedPage` finally, OnnxPhase:684 — unchanged; prepared boundary flush barrier retained).

# §8 Working state — branch isolation (parent; Gemini #9, corrected per plan-review #5)

**P**: at OCR completion each branch builds its OWN working `PageTranslation` from the OCR result via existing copy facilities — blocks copied with `it.copy(segmentationMask = it.segmentationMask?.immutableCopy())` (`TranslationBlock` is a data class with `blockId`, PageTranslation.kt:303; `BubbleMaskRle`'s `List` vals are NOT intrinsically immutable — the defensive-copy helpers at PageTranslationView.kt:425-436 and the `immutableCopy()` idiom at :233 exist for exactly this). `PageInpaintingEngine` mutates the instance it is handed (status RUNNING/FAILED/READY, errorMessage, inpaintingModeUsed stamp, updatedAt, attempt counters — PageInpaintingEngine:44-45/137-142/153-161), so whole-INSTANCE separation (not just block-list copies) is what prevents cross-branch data races. Branch engine/status writes stay branch-local; the store receives state only via preconditioned `attempt.write` FIELD-SPECIFIC merges (each update composes against the store's current page; never a stale whole-object replacement). NO new `TranslationDraft` class — isolation is by existing facilities plus branch structure; "draft-only reads" is by construction, verified by test, not by types (the provider API takes a full `PageTranslation`). The store's published-view detached helper `PublishedTranslationBlock.toDraft()` (PageTranslationView.kt:244) remains available should copies ever be built from published state.

# §9 Recyclers — every site, one owner per allocation

| Allocation | Today's recycler (F) | v5 (P) |
| --- | --- | --- |
| source decode, fresh path | detached-job finally, OnnxPhase 1226-1231 | inpaint segment's own block-scoped finally (admitted, transfer-aware), SinglePageOnnxPhase fork body moved under lane segment 2 |
| source decode, resume inpaint | OnnxPhase 576-581 | unchanged shape, inside block |
| retry re-decode | OnnxPhase 880-885 | unchanged (retry-step-owned) |
| scaling temp | OnnxPhase 861-865 | unchanged; 857 same-instance = single ref |
| cleaned, non-lazy publication failure | CleanedPublication 276-284 | owner release at boundary |
| cleaned, prepared path | PreparedPageBoundary 71-77 (flush barrier then recycle) | **CHANGED**: boundary recycle becomes owner-gated release (join receipt, then recycle) inside `prepareSinglePage`'s scope |
| cleaned, render path | HttpRenderPhase 792-797 (render end), 956-974 (flush join), 1049-1067 (defensive) | borrow-return → owner release (join per §4) |
| batch bitmaps | batch-lane-owned | untouched |

# §10 Changed vs retained (self-contained, consistent with §9)

Changed: `TranslationPipeline` (attempt+owner entry; structured fork/join in `runGrantedSinglePageBoundary`; reader retry adapter at 801-802), `SinglePageOnnxPhase` (processSinglePage returns OCR-only result; inpaint moved to an admitted segment-entry point reused by reader fork and prepare inline; draft; produce/replace transfer; retry internal body reused by both wirings), `OnnxPhaseResult` (drop `inpaintJob`; no bitmap in any Deferred value), `SinglePageHttpRenderPhase` (no inpaintJob ctx/await/cancel branches; borrows; owner-gated recycles; gated semantic tails; retry call unchanged), `PreparedPageBoundary` (recycle site → owner-gated release), `CleanedPublication`/`CleanedImagePublisher` (receipt wiring; §5 policy), `ChapterTranslationStore.enqueueLazyPersistence` (caller-created receipt + optional H5 guard), `EngineLane.withNativeLane` (whole-chain adapter, per-segment), NEW one-file `CleanedOwner`+`AttemptAuthority`. Retired: detached scope, naked await + cancel branches, unadmitted reader retry. Retained: `NativeRunQuarantine` (no API change — segment reuse), `EngineLane` guards, batch paths and wiring (1242/1343 untouched), `DeferredPagePublications` (mechanics unchanged), `PostOcrStageSemantics`, store fences/`patchPage` atomicity/`mayDeleteCleanedImage` (reused), retry algorithm, OCR/LiteRT internals, provider call chain (no new paid calls).

# §11 Recheck-gap closure table

| Gap | Closure |
| --- | --- |
| receipt local / pending-forever on cancel-before-lock | owner registers before enqueue; callee completes iff not queued (§4 handshake; finally covers lock-wait cancellation) |
| mutable attempt precondition breaks exactly-once | closure flag + capture-once marker + local-snapshot writes + callerJob gate (§2 case proof) |
| outcome-after-barrier ≠ intent during block | `closed` (set by onTimeout) + `callerJob.isCancelled` are visible to the block DURING execution (§2) |
| retry output escapes via quarantine return | capture = scaledCleaned assigned at OnnxPhase 868, produced to owner INSIDE the block (§6) |
| §6/§9 contradiction on PreparedPageBoundary | boundary recycle IS changed to owner-gated release (§9/§10) |
| v4 referenced overwritten sections | v5 self-contained |
| options removed / decision recorded | memory-first selected (header); D retired |
| uncertain-file bounded claim | conservative retention, explicitly NOT bounded, H8 recorded (§5) |
| write-vs-marker race (field not a linearization point) | attemptMutex serializes patch + receipt-advance + close/marker (plan §4) |
| un-gated resume tails could overwrite terminal markers | semantic/physical tail split with execution-time authorization (plan §7) |
| onTimeout callback error could escape the real-exit barrier | safe NonCancellable adapter at EngineLane.withNativeLane; MARKER_FAILED logged, fallback fast-cancel (plan §4) |

# §12 Gates

Host: §2 case proof (residual-vs-marker, in-flight completion), §4 handshake under cancellation, join across all five completion paths, §5 policy matrix, §6 retry serialization, H1 (OCR text non-blank), H3 (engine alias), H4 (estimator value-neutrality), H5 (lazy⇒target), H7 (defensive mask copies verified by test — masks are not type-immutable), H8 (closed by source — see §5). Fork cases: true overlap via the added harness inpaint gate (CoexistenceBarrier has no native-inpaint point today), BOTH completion orders, cancel mid-join (provider-call cancellation only via genuine caller cancellation — paid-call test), native-timeout-with-completed-translation, both-terminal race, two concurrent attempt writers, field-merge isolation. **Device, Phase 3 (behavior changed)**: real-timeout abandonment disposal (both native segments), retry admission timing, join stall magnitude on SAF I/O, marker policy end-to-end incl. branch interleavings. No new product questions; no performance claims. **Authoritative contract, slices, failure matrix, and gates live in `../plan/index.md` (status: revised, pending Gemini recheck — no coding authorization)**.
