---
title: "S5 preparation - comprehensive specification and source verification (r6)"
kind: spec
comments: none
---

# Status

Revision r6 incorporates the complete, concrete `AttemptArtifactLease` mechanism designed in consensus with GLM review (`review/glm/index.md`) and parent coordinator directives.
Zero source edits and zero Gradle commands have been executed. The approved Phase 3 Plan (§4–17) and validation audit rules govern this implementation orientation.

## 1. Governing Principles & Approved Structure

1. **Execution Model**:
 Structured fork/join within a single page boundary:
  - **Segment 1**: Admitted native OCR (`withNativeLane`, `nativeTimeoutMs`) -> decodes source bitmap $S$, registers source custody immediately into `SourceBitmapCustody` slot, performs OCR (`recognitionEngine.analyze`). Segment 1 native execution DOES start. If Segment 1 times out or aborts, Segment 2 NEVER starts, and $S$ is recycled in boundary outer finally.
  - **Fork**:
    - **Native Branch (Segment 2)**: Admitted inpaint (`withNativeLane`, `nativeTimeoutMs`) -> `val s = checkNotNull(custody.take())` inside the admitted block at first guarded use -> passes $s$ to JNI `inpaint` -> deposits cleaned bitmap $c$ into `CleanedOwner` (`produce`) -> local `finally` recycles $s$ only if not owned by `CleanedOwner` (`if (!owner.owns(s)) s.recycle()`) -> exits native lane -> boundary-scope persistence enrollment: `owner.registerReceipt(receipt)` + NonCancellable `store.enqueueLazyPersistence` + persist tail enqueue.
    - **External Branch**: `translateSinglePageHttpOnly(...)` inside the single shared `singlePageTimeoutMs` (T3) boundary budget -> provider call, classification, translation publication via `attempt.write`. NO native permit.
  - **Join**: Boundary awaits both branches. Native branch outcome: `CleanedInOwner` | `TimedOut` | `Failed`.
  - **Post-Join**: Admitted retry (Segment 3) if `cleanedBitmap == null` -> `renderSinglePageTranslated(...)` (lines 704–1060: borrow availability, `withRenderPermit`, single authoritative color estimator at 757–760, canvas render, render commit via `attempt.write`, closes borrow).
  - **Close**: Boundary drain (fail-closed) -> boundary nested `finally` chain (see §4).
2. **Memory-First Guarantee**:
 The native lane permit is held strictly for active JNI inference and local release, never across HTTP calls, storage persistence, or receipt joins.
3. **Timer Budgeting**:
 A single shared `singlePageTimeoutMs` (T3) covers external translation, join wait, and post-join render. It is NOT reset between stages.
4. **Sibling Coordination & Paid Call Preservation (Approved Table C1–C7)**:
 A native failure (timeout or exception) is captured inside the child branch and does NOT cancel the sibling HTTP call. Mid-flight provider translations are never discarded due to native timeout.
5. **S4 File Policy Intact**:
 Uncertain publication outcomes unconditionally retain files without blind retirement registration, logging uncollected debt at ERROR level.

## 2. Concrete Interface & Seam Architectures

### Monolith Seam Ranges of `SinglePageHttpRenderPhase.kt`

The monolith is split cleanly into two distinct, non-overlapping functions:

1. `suspend fun translateSinglePageHttpOnly(...)`:
  - Extracts lines 211–660.
  - Captures `activeTranslator = textTranslator` and `epochAtCapture = engines.currentEngineEpoch()`.
  - Manages bounded epoch-guard retry via `engines.ensureTranslatorRebuiltForEpochRetry(...)`.
  - Prepares execution context, sets interactive priority, executes `textTranslator.translate(...)`, classifies outcomes, publishes translation results to store via `attempt.write`, and records translate stage trace spans.
  - Settles `governorSpan`: opened when preparing translator dispatch (line 271/531), settled on the first provider invocation/request dispatch (lines 424–427: `if (!governorSpanSettled) { governorSpanSettled = true; governorSpan?.end() }`) or upon HTTP exit without invocation (lines 1061–1065).
  - Returns `TranslationCompletionOutcome`.
2. `suspend fun renderSinglePageTranslated(...)`:
  - Extracts lines 704–1060 (including lines 808–984 retry and failure branches, with obsolete `inpaintJob.await()` and cancel branches removed).
  - Line 704–795: Primary render path. Borrows cleaned image via `owner.borrow()`. Acquires `withRenderPermit`, executes single authoritative `RenderColorEstimator.recomputeFor(cleanedBitmap, blocks)` (lines 757–760), renders canvas, commits via `attempt.write`, and closes borrow.
  - Line 808–984: Retry and failure paths. If inpaint produced no bitmap (`cleanedBitmap == null`), executes `retryInpaintDownscaled` wrapped in `withNativeLane(nativeTimeoutMs)` (Segment 3). If retry succeeds, deposits new bitmap into `owner.replace()`, publishes to disk companion, runs the single authoritative estimator on the retried bitmap, and commits via `attempt.write`. If retry fails, records typed failure message and marks render failed without drawing text.
  - Line 985–1060: Post-render cleanup and final guarded commit. Eliminates obsolete cancel branches and routes commit through `attempt.write`.

## 3. Source Bitmap ($S$) Custody: Single `AtomicReference` Slot

### Concrete Holder API: `SourceBitmapCustody`

```kotlin
internal class SourceBitmapCustody : AutoCloseable {
    private val slot = java.util.concurrent.atomic.AtomicReference<Bitmap?>(null)

    fun register(bitmap: Bitmap) {
        check(slot.compareAndSet(null, bitmap)) { "source bitmap already registered in custody slot" }
    }

    /** Takes ownership out of the boundary slot at first guarded use inside the producing try/finally. */
    fun take(): Bitmap? = slot.getAndSet(null)

    /** Boundary cleanup: safely recycles only if the bitmap was never taken by a producer block. */
    override fun close() {
        slot.getAndSet(null)?.let { bitmap ->
            try { bitmap.recycle() } catch (_: Exception) {}
        }
    }
}
```

### Detach Rules Across Lifecycle Stages

1. **Segment 1 Entry & Decode**:
  - Inside `withNativeLane`, `decoded = decodePage(stream)`.
  - `decoded.bitmap` ($S$) is immediately registered: `sourceCustody.register(decoded.bitmap)` before `analyzePage` runs. Segment 1 native execution DID start.
2. **Segment 1 Timeout or Abort**:
  - If Segment 1 times out or throws, `withNativeLane` returns null or throws after quarantine real exit.
  - Segment 2 NEVER starts, so `slot` still holds $S$.
  - The boundary outer `finally` calls `sourceCustody.close()`, which takes and recycles $S$. Zero bitmap leak, zero double-recycle.
3. **Segment 2 Transfer & Execution**:
  - Segment 2 opens inside `withNativeLane`:
  - **Non-Alias Outcome ($C \neq S$)**: `owner.owns(s)` is false. The local `finally` recycles $s$. Because `sourceCustody.take()` already set `slot` to null, boundary `sourceCustody.close()` sees null and does nothing. Exactly one recycle.
  - **Alias Outcome ($C \equiv S$)**: `owner.produce(c, INPAINT)` deposits $s$ into `CleanedOwner`. `owner.owns(s)` is true. Local `finally` does NOT recycle $s$. Boundary `sourceCustody.close()` sees null. `CleanedOwner.release()` recycles $s$ when all borrows and persistence receipts complete. Exactly one recycle.
4. **Resume Path** (`SinglePageOnnxPhase.kt:540–582`):
  - Adopts the exact same pattern: takes $S$, produces into `CleanedOwner` before `finally`, local `finally` checks `if (!owner.owns(s)) s.recycle()`.
5. **No-Fork Paths**:
  - If Segment 2 is never reached (OCR error, textless page, early cancellation), `slot` remains populated. Boundary `sourceCustody.close()` takes and recycles $S$.

## 4. Executable Nested-Try/Finally Resource Scoping

Resources are acquired and released in strict LIFO executable nesting with isolated cleanup:

```kotlin
// 1. Exclusive lease acquisition
acquireReaderPageLease(store, pageKey, PageStage.Ocr, origin, site)
try {
    val attempt = AttemptAuthority(store, pageKey, store.snapshot(pageKey).toPrecondition(), callerJob)
    val owner = CleanedOwner()
    val sourceCustody = SourceBitmapCustody()
    val deferredPublications = DeferredPagePublications()

    // 2. Translator borrow
    engines.beginTranslatorUse()
    try {
        // Native Segment 1 (admitted)
        withNativeLane(nativeTimeoutMs) {
            val decoded = decodePage(stream)
            sourceCustody.register(decoded.bitmap)
            // analyzePage...
        }

        // Fork Segment 2 (native inpaint) & HTTP Translation Branch
        // ... (Join & Post-Join Render) ...

    } finally {
        // 3. Nested release per resource
        try {
            deferredPublications.drain()
        } finally {
            try {
                // Isolated stream registry clear
                chapter.id?.let { chapterId ->
                    streamRegistry.clearPage(source.id, manga.id, chapterId, pageKey)
                }
            } finally {
                try {
                    // Releases source bitmap if never taken by inpaint
                    sourceCustody.close()
                } finally {
                    try {
                        // Awaits lazy persistence receipt + open borrows == 0 -> recycles cleaned bitmap
                        owner.release()
                    } finally {
                        try {
                            engines.endTranslatorUse()
                        } finally {
                            try {
                                recognitionEngine.reclaimPooledMemory()
                            } catch (e: Throwable) {
                                logcat(LogPriority.WARN, e) {
                                    "Failed to reclaim pooled recognition memory for $pageKey"
                                }
                            }
                        }
                    }
                }
            }
        }
    }
} finally {
    // 4. Page lease release
    releaseReaderPageLease(store, pageKey, origin)
}
```

## 5. Store-Linked `AttemptArtifactLease` Mechanism

### Placement & Type Design

To prevent architectural layering violations (no persistence -> pipeline imports), `AttemptArtifactLease` is located in the persistence layer:
`eu.kanade.translation.persistence.chapter.AttemptArtifactLease` (or internal to `ChapterTranslationStore`):

```kotlin
/** Binds one attempt's authority to its own lazy-drain artifact progressions. */
internal class AttemptArtifactLease internal constructor() {
    @Volatile internal var lastOwnDrainSnapshot: ChapterTranslationStore.PageSnapshot? = null
}
```

### Store Integration Details

1. **Visibility & Overloads**:
  - The public `patchPage(pageKey, expected, description, patch)` signature remains unchanged for external compatibility.
  - An internal overload `internal suspend fun patchPage(pageKey: String, expected: PatchPrecondition, description: String, artifactLease: AttemptArtifactLease?, patch: (PageTranslation?) -> PageTranslation)` is introduced.
  - `AttemptAuthority` holds one `private val artifactLease = AttemptArtifactLease()` per attempt and passes it to the internal `patchPage`.
2. **Absorption Logic (Inside `store.mutex.withLock`)**:
  - In `patchPage`, after generation and live `pageVersion` invariant checks succeed, and before evaluating candidate/manifest fences:
  - Candidate-less dependency fingerprint matches `snapshotLocked(pageKey)` derivation (`pages[pageKey]?.toDraft()?.let(StageFingerprints::pageSnapshot)`), ensuring candidate-less pages evaluate correctly.
  - Candidate and manifest fences are evaluated against `effective`.
3. **Queue & Drain Stamping**:
  - `PendingLazyMutation` carries `val artifactLease: AttemptArtifactLease?`.
  - `publishLazyLocked` stamps `artifactLease` into `writeDrain.pendingLazyMutations[pageKey]` (coalesced entries automatically carry the active lease).
  - In `flushLazyMutationsLocked`, when `persistArtifactMutationLocked` completes a successful `Committed` manifest swap, it executes under the same store mutex:
`mutation.artifactLease?.lastOwnDrainSnapshot = snapshotLocked(pageKey)`.
  - On a `Rejected` or recoverable partial drain: **NO stamp** is applied; `lastOwnDrainSnapshot` remains untouched.
4. **Guarantees**:
  - Deadlock-free: lease update is a plain volatile field write inside existing `store.mutex`.
  - Zero unbounded registry: one small instance per attempt, GC'd with `AttemptAuthority`.
  - Deterministic: only verified successful own-drain transitions are absorbed; foreign candidate modifications, resets, and user edits are strictly rejected.

## 6. Approved Sibling Coordination Table (C1–C7 Verbatim)

Verbatim from approved Phase 3 Plan §2:

| # | Case | Outcome |
| --- | --- | --- |
| **C1** | inpaint segment TimedOut, HTTP already completed (translation published pre-join) | translation stays durable (store truth outranks timers — Pipeline:705-708 precedent); marker written POST-JOIN via `attempt.markTimeout` (nativeTimer truth); render suppressed (closed); outcome Failed("native phase timed out") with translationStatus durable — exactly today's coexistence shape (markPageTimedOut only fails non-READY stages, PageStoreWriter:157-175) |
| **C2** | inpaint TimedOut, HTTP still in flight | sibling NOT cancelled (a mid-flight provider call is never lost to a native outcome — preserves today's order, where translate always completes before the join); boundary waits the sibling (bounded by T3); then C1 classification. The native timeout CALLBACK may record/notify (trace/log) at the callback moment but does not cancel the sibling and does not write the marker — callback moment $\\neq$ withNativeLane return $\\neq$ marker time |
| **C3** | HTTP completes first, inpaint still running | boundary waits the native branch (T2 + real-exit join); classify per store truth (translated + no cleaned $\\to$ retry path or render-FAILED, mirroring today's cleaned==null flow) |
| **C4** | genuine parent cancellation mid-join | structured scope cancels both branches; provider call cancelled — this is the EXISTING reader-stop cancellation policy, unchanged; CANCELLED fast-cancel paths run; native real-exit joined; owner release receipt-joins |
| **C5** | native branch fails (non-timeout exception) | sibling NOT cancelled; branch failure recorded; sibling completes; classification Failed(original native cause) with translation durable (today's analog: inpaintStatus FAILED + translation READY coexist); reviewer's "unconditional sibling cancellation" is REJECTED as a provider-policy change |
| **C6** | cleaned == null at join (inpaint soft-failed) | retry segment 3 (T4 inside T3); success $\\to$ `owner.replace` + estimator re-run (background changed); fail $\\to$ render FAILED path — existing behavior |
| **C7** | both branches terminal-failed | post-join classification picks by existing precedence (store truth first, then native vs provider failure ordering as today); ONE terminal marker via idempotent `markTimeout`/`markPageFailed` equivalent; loser terminal-and-ignore |

## 7. Changed File Manifest

### Production Code (8 files)

1. `app/src/main/java/eu/kanade/translation/persistence/chapter/ChapterTranslationStore.kt`:
 Hosts `AttemptArtifactLease` (or imports it from persistence package), adds internal `patchPage` overload with `artifactLease`, implements exact certified absorption before candidate fences in `patchPage`, stamps `artifactLease` upon successful `Committed` candidate persist in `flushLazyMutationsLocked`.
2. `app/src/main/java/eu/kanade/translation/pipeline/AttemptAuthority.kt`:
 Holds `artifactLease = AttemptArtifactLease()`, passes to internal `patchPage`, hosts `CleanedOwner` and `SourceBitmapCustody`.
3. `app/src/main/java/eu/kanade/translation/pipeline/TranslationPipeline.kt`:
 Boundary fork/join, single T3 budget, retry admitted wrapper, prepared await removal, verbatim C1–C7 coordination, post-join terminal marker, nested LIFO cleanup chain.
4. `app/src/main/java/eu/kanade/translation/pipeline/SinglePageOnnxPhase.kt`:
 OCR-only `processSinglePage` (removes `Pair`, `inpaintJob`, detached scope, early estimator), source custody registration, resume owner & transfer-aware `finally`, attempt-fenced `renderResumedPage`.
5. `app/src/main/java/eu/kanade/translation/pipeline/SinglePageHttpRenderPhase.kt`:
 Split into `translateSinglePageHttpOnly` (211–660) + `renderSinglePageTranslated` (704–1060); eliminates `inpaintJob` await and cancel branches; owner-gated recycles; single authoritative estimator; governorSpan settled on first provider invocation; translator epoch retry mapped.
6. `app/src/main/java/eu/kanade/translation/model/PageTranslationView.kt`:
 Promotes `BubbleMaskRle.immutableCopy()` visibility from `private` to `internal` for branch-private defensive copies.
7. `app/src/main/java/eu/kanade/translation/pipeline/execution/PreparedPageBoundary.kt`:
 Flush barrier retained; recycle becomes owner-gated release; OCR persist rides receipt path.
8. `app/src/main/java/eu/kanade/translation/pipeline/CleanedPublication.kt`:
 Boundary-scope enrollment call shape (`registerReceipt` -> `withContext(NonCancellable) { enqueueLazyPersistence }` -> tail enqueue); field-specific merge via attempt.

### Test Suites (3+ files)

9. `app/src/test/java/eu/kanade/translation/coexistence/TranslationCoexistenceHarness.kt` & `CoexistenceBarrier.kt`:
 Narrow test-only inpaint barrier gate to simulate and verify true concurrent overlap without production seams.
10. `app/src/test/java/eu/kanade/translation/pipeline/SinglePageOverlapBoundaryTest.kt` (NEW):
Targeted proof suite: true overlap, both completion orders, cancel mid-join, native timeout with completed translation, both-terminal race, two concurrent writers, field-merge isolation, defensive mask copy immutability, retry serialization, tail suppression, abandonment custody, lazy drain revalidation via lease, coalescing, candidate-less grace, and foreign rejection.
11. Existing regression test selectors updated for signature compatibility.
